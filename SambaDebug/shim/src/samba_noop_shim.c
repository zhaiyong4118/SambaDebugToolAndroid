/*
 * samba_noop_shim.c
 * ------------------------------------------------------------------
 * 无 root 环境下让 smbd 正常运行的 LD_PRELOAD 垫片。
 *
 * 为什么需要它：
 *   1. smbd 启动时会调用 gain_root_privilege() 尝试 setuid(0)/setgid(0)。
 *      Android app 的 seccomp 策略会直接 SIGSYS 杀掉 setuid 系系统调用
 *      （实测：arm syscall 213 = setuid32），进程根本起不来。
 *   2. Android 的 /etc/passwd 是空的，smbd 用 getpwnam() 解析
 *      root/debug/nobody 会失败，认证会话建立不了。
 *
 * 做法：把这些调用全部拦在 libc 层。
 *   - getuid/geteuid/getgid/getegid 伪装返回 0（让 smbd 以为自己是 root）；
 *   - setuid/setgid 族全部返回成功但不真正切 uid（进程仍是 app uid，
 *     共享目录是 app 自己的，文件操作由内核用真实 uid 完成，不受影响）；
 *   - getpwnam/getpwuid 族返回伪造的账号表（root、nobody、debug→app uid）；
 *   - 原始 syscall() 透传，但拦掉 setuid 族编号。
 *
 * 编译（必须与 smbd 同架构，arm32）：
 *   armv7a-linux-androideabi21-clang -shared -fPIC -O2 -o libsmbd_shim.so samba_noop_shim.c
 * ------------------------------------------------------------------
 */
#define _GNU_SOURCE
#include <sys/types.h>
#include <unistd.h>
#include <errno.h>
#include <pwd.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <stdarg.h>
#include <string.h>

/* 真实 libc 函数，在构造器里通过 dlsym 拿到，避免被自己的同名函数递归拦截 */
typedef uid_t (*uid_fn)(void);
typedef gid_t (*gid_fn)(void);
typedef long  (*syscall_fn)(long, ...);

static uid_fn     real_getuid;
static gid_fn     real_getgid;
static syscall_fn real_syscall;

/* -------------------- 伪造账号表 -------------------- */
struct FakePwd {
    const char *name;
    uid_t uid;
    gid_t gid;
    const char *dir;
    const char *shell;
};

static struct FakePwd s_root   = {"root",   0,     0,     "/data", "/system/bin/sh"};
static struct FakePwd s_nobody = {"nobody", 65534, 65534, "/data", "/system/bin/sh"};
static struct FakePwd s_debug;   /* 构造时填充为当前 app uid */

/* 真实 uid/gid（构造器里从真实 libc 拿，供 getuid/geteuid 等返回） */
static uid_t g_uid = 0;
static gid_t g_gid = 0;

__attribute__((constructor))
static void shim_init(void) {
    real_getuid  = (uid_fn)dlsym(RTLD_NEXT, "getuid");
    real_getgid  = (gid_fn)dlsym(RTLD_NEXT, "getgid");
    real_syscall = (syscall_fn)dlsym(RTLD_NEXT, "syscall");
    g_uid = real_getuid ? real_getuid() : 0;
    g_gid = real_getgid ? real_getgid() : 0;
    s_debug.name  = "debug";
    s_debug.uid   = g_uid;
    s_debug.gid   = g_gid;
    s_debug.dir   = "/data/data";
    s_debug.shell = "/system/bin/sh";
}

static const struct FakePwd *find_pwd_by_name(const char *name) {
    if (!name) return NULL;
    if (strcmp(name, "root") == 0)   return &s_root;
    if (strcmp(name, "nobody") == 0) return &s_nobody;
    if (strcmp(name, "debug") == 0)  return &s_debug;
    return NULL;
}

static const struct FakePwd *find_pwd_by_uid(uid_t uid) {
    if (uid == 0) return &s_root;
    if (uid == 65534) return &s_nobody;
    if (uid == s_debug.uid) return &s_debug;
    return NULL;
}

/* -------------------- getuid 族：返回真实 uid/gid -------------------- */
/* 让 smbd 检测到"非 root 模式"（Samba 会走 non_root_mode 分支，
 * gain_root_privilege 自动变 no-op，目录属主校验按真实 uid 比对）。
 * 不要伪装成 0，否则 smbd 以为自己是 root，会校验 msg.lock 等目录
 * 必须归 root 所有 → invalid ownership 失败。 */
uid_t getuid(void)  { return g_uid; }
uid_t geteuid(void) { return g_uid; }
gid_t getgid(void)  { return g_gid; }
gid_t getegid(void) { return g_gid; }

/* -------------------- setuid 族：全部伪装成功 -------------------- */
int setuid(uid_t uid)                    { return 0; }
int setgid(gid_t gid)                    { return 0; }
int seteuid(uid_t euid)                  { return 0; }
int setegid(gid_t egid)                  { return 0; }
int setreuid(uid_t ruid, uid_t euid)     { return 0; }
int setregid(gid_t rgid, gid_t egid)     { return 0; }
int setresuid(uid_t ruid, uid_t euid, uid_t suid) { return 0; }
int setresgid(gid_t rgid, gid_t egid, gid_t sgid) { return 0; }
int setfsuid(uid_t fsuid)                { return (int)fsuid; }
int setfsgid(gid_t fsgid)                { return (int)fsgid; }
int setgroups(size_t size, const gid_t *list) { return 0; }
int initgroups(const char *user, gid_t group) { return 0; }
int chown(const char *path, uid_t owner, gid_t group)  { return 0; }
int fchown(int fd, uid_t owner, gid_t group)           { return 0; }
int lchown(const char *path, uid_t owner, gid_t group) { return 0; }

/* -------------------- getpwnam/getpwuid 族 -------------------- */
static struct passwd g_pw;

static void fill_pwd(struct passwd *pw, const struct FakePwd *f) {
    pw->pw_name   = (char *)f->name;
    pw->pw_passwd = "x";
    pw->pw_uid    = f->uid;
    pw->pw_gid    = f->gid;
    pw->pw_gecos  = (char *)f->name;
    pw->pw_dir    = (char *)f->dir;
    pw->pw_shell  = (char *)f->shell;
}

struct passwd *getpwnam(const char *name) {
    const struct FakePwd *f = find_pwd_by_name(name);
    if (!f) { errno = ENOENT; return NULL; }
    fill_pwd(&g_pw, f);
    return &g_pw;
}

struct passwd *getpwuid(uid_t uid) {
    const struct FakePwd *f = find_pwd_by_uid(uid);
    if (!f) { errno = ENOENT; return NULL; }
    fill_pwd(&g_pw, f);
    return &g_pw;
}

static int fill_pwd_r(const struct FakePwd *f, struct passwd *pwd,
                      char *buf, size_t buflen, struct passwd **result) {
    if (!f) { *result = NULL; return ENOENT; }
    size_t need = strlen(f->name) + 1;
    if (buflen < need) { *result = NULL; return ERANGE; }
    memcpy(buf, f->name, need);
    fill_pwd(pwd, f);
    pwd->pw_name = buf;
    *result = pwd;
    return 0;
}

int getpwnam_r(const char *name, struct passwd *pwd, char *buf,
               size_t buflen, struct passwd **result) {
    return fill_pwd_r(find_pwd_by_name(name), pwd, buf, buflen, result);
}

int getpwuid_r(uid_t uid, struct passwd *pwd, char *buf,
               size_t buflen, struct passwd **result) {
    return fill_pwd_r(find_pwd_by_uid(uid), pwd, buf, buflen, result);
}

/* -------------------- 原始 syscall：拦掉 app seccomp 禁用的调用 -------------------- */
long syscall(long number, ...) {
    /* 1) uid/gid 切换类：返回 0 假装成功
     *    （smbd 可能直接调 syscall(SYS_setgroups) 等，绕开 libc 符号覆盖） */
    switch (number) {
#ifdef __NR_setuid32
        case __NR_setuid32:
#endif
#ifdef __NR_setgid32
        case __NR_setgid32:
#endif
#ifdef __NR_setreuid32
        case __NR_setreuid32:
#endif
#ifdef __NR_setregid32
        case __NR_setregid32:
#endif
#ifdef __NR_setresuid32
        case __NR_setresuid32:
#endif
#ifdef __NR_setresgid32
        case __NR_setresgid32:
#endif
#ifdef __NR_setfsuid32
        case __NR_setfsuid32:
#endif
#ifdef __NR_setfsgid32
        case __NR_setfsgid32:
#endif
#ifdef __NR_setgroups32
        case __NR_setgroups32:
#endif
#ifdef __NR_setgroups
        case __NR_setgroups:
#endif
#ifdef __NR_setuid
        case __NR_setuid:
#endif
#ifdef __NR_setgid
        case __NR_setgid:
#endif
#ifdef __NR_setreuid
        case __NR_setreuid:
#endif
#ifdef __NR_setregid
        case __NR_setregid:
#endif
#ifdef __NR_setresuid
        case __NR_setresuid:
#endif
#ifdef __NR_setresgid
        case __NR_setresgid:
#endif
#ifdef __NR_setfsuid
        case __NR_setfsuid:
#endif
#ifdef __NR_setfsgid
        case __NR_setfsgid:
#endif
            return 0;
        default:
            break;
    }
    /* 2) app seccomp 直接 SIGSYS 杀掉、但服务器可能用到的调用：
     *    返回 -1/ENOSYS 让 smbd 容错继续（内存统计等属于锦上添花） */
    switch (number) {
#ifdef __NR_sysinfo
        case __NR_sysinfo:
#endif
#ifdef __NR_process_vm_readv
        case __NR_process_vm_readv:
#endif
#ifdef __NR_process_vm_writev
        case __NR_process_vm_writev:
#endif
            errno = ENOSYS;
            return -1;
        default:
            break;
    }
    if (!real_syscall) { errno = ENOSYS; return -1; }
    va_list ap;
    va_start(ap, number);
    long a1 = va_arg(ap, long);
    long a2 = va_arg(ap, long);
    long a3 = va_arg(ap, long);
    long a4 = va_arg(ap, long);
    long a5 = va_arg(ap, long);
    long a6 = va_arg(ap, long);
    va_end(ap);
    return real_syscall(number, a1, a2, a3, a4, a5, a6);
}
