package com.sam.samba.debug;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SambaSetup {

    private static final String TAG = "SambaSetup";
    private static final String SAMBA_DIR = "samba";
    private static final int SMB_PORT = 1445;

    public static File setup(Context context, String localIp) throws Exception {
//        File sambaRoot = new File(context.getFilesDir(), SAMBA_DIR);
        File sambaRoot = new File(context.getCacheDir(), SAMBA_DIR);

        // 1. 从 assets 递归拷贝二进制和库文件
        copyAssetDir(context.getAssets(), SAMBA_DIR, sambaRoot);
        Log.i(TAG, "Files copied to " + sambaRoot.getAbsolutePath());

        // 2. 设置执行权限（shim 是 LD_PRELOAD 的无 root 垫片，也需要可执行）
        setExecutableRecursive(new File(sambaRoot, "bin"));
        setExecutableRecursive(new File(sambaRoot, "lib"));
        setExecutableRecursive(new File(sambaRoot, "shim"));

        // 3. 创建运行目录（etc=配置，var=运行时日志/锁文件，方便统一清理）
        new File(sambaRoot, "etc").mkdirs();
        new File(sambaRoot, "var").mkdirs();

        // 4. 生成 smb.conf
        generateSmbConf(context, sambaRoot, localIp);

        // 5. 生成 smbpasswd
        generateSmbPasswd(sambaRoot);

        // 6. 生成 username map（把客户端可能发的用户名映射到 debug，实现免密）
        generateUsernameMap(sambaRoot);

        return sambaRoot;
    }

    public static Process startDaemon(File sambaRoot, String daemonName) throws Exception {
        File binFile = new File(sambaRoot, "bin/" + daemonName);
        File confFile = new File(sambaRoot, "etc/smb.conf");
        String libPath = new File(sambaRoot, "lib").getAbsolutePath()
                + ":" + new File(sambaRoot, "lib/private").getAbsolutePath();

        // 日志文件路径
        String logFile = new File(sambaRoot, "var/" + daemonName + ".log").getAbsolutePath();
        new File(sambaRoot, "var").mkdirs();

        // 不能直接 execve app data 目录里的二进制：Android SELinux 会拒绝
        // untrusted_app 对 app_data_file 的 execute_no_trans（实测 smbd 就是这么被拦下的）。
        // 正确姿势（Termux 同款）：exec 动态链接器，把二进制路径作为参数传给它。
        // 链接器用 mmap 加载（只需 execute 权限，不触发 execute_no_trans），
        // 再由 LD_LIBRARY_PATH 解析打包进来的 .so。
        // 注意：smbd 是 32 位 ARM（EABI5），必须用 32 位链接器 /system/bin/linker；
        // 若换成 arm64 二进制，这里要改成 /system/bin/linker64。
        // -F: 前台运行，不让 smbd 自行 fork，这样 process 句柄就是 smbd 本身，便于停止/存活检测。
        String[] cmd = new String[]{
                "/system/bin/linker",
                binFile.getAbsolutePath(),
                "-F",
                "-l", logFile,
                "--configfile=" + confFile.getAbsolutePath()
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("TMPDIR", sambaRoot.getAbsolutePath()); // Android 上没有可写的 /tmp

        // 把 app 数据目录传给 shim 作为账号的 home 目录：
        // smbd 会自动给用户加 [homes] 共享，路径取自账号 home；
        // 若 home 无效(如 /system/bin/sh)，macOS 枚举共享列表时会失败。
        // sambaRoot = <dataDir>/cache/samba，所以 getParentFile().getParentFile() = <dataDir>
        pb.environment().put("SAMBASHIM_HOME",
                sambaRoot.getParentFile().getParentFile().getAbsolutePath());

        // 无 root 垫片：拦截 setuid 系 syscall（seccomp 会 SIGSYS 杀 smbd）
        // 并伪造 getpwnam/getpwuid（Android /etc/passwd 为空）
        File shimFile = new File(sambaRoot, "shim/libsmbd_shim.so");
        if (shimFile.exists()) {
            pb.environment().put("LD_PRELOAD", shimFile.getAbsolutePath());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        Log.i(TAG, daemonName + " started, log: " + logFile);

        // 异步读取 stdout/stderr，打印到 Logcat
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, daemonName + ": " + line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to read " + daemonName + " output", e);
            }
        }, daemonName + "-log-reader").start();

        // 等待检查是否立即退出
        Thread.sleep(500);
        try {
            int exitCode = process.exitValue();
            throw new RuntimeException(daemonName + " exited immediately with code " + exitCode);
        } catch (IllegalThreadStateException e) {
            Log.i(TAG, daemonName + " is running.");
        }

        return process;
    }

    // ==================== 文件拷贝 ====================

    private static void copyAssetDir(AssetManager assets, String assetPath, File dest)
            throws Exception {
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            // 是文件，直接拷贝
            dest.getParentFile().mkdirs();
            try (InputStream in = assets.open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return;
        }
        // 是目录，递归拷贝
        dest.mkdirs();
        for (String entry : entries) {
            copyAssetDir(assets, assetPath + "/" + entry, new File(dest, entry));
        }
    }

    private static void setExecutableRecursive(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                setExecutableRecursive(file);
            } else {
                file.setExecutable(true, false);
            }
        }
    }

    // ==================== 配置生成 ====================

    private static void generateSmbConf(Context context, File sambaRoot, String localIp) throws Exception {
        // 1. 准备路径
        String sambaEtc = new File(sambaRoot, "etc").getAbsolutePath();
        // 运行时目录：日志/锁文件/pid 等全部统一到 cache/samba/var，便于清理
        String runDir = new File(sambaRoot, "var").getAbsolutePath();

        // 2. 构建配置字符串
        // 我们直接硬编码配置，不再依赖模板文件
        StringBuilder conf = new StringBuilder();

        conf.append("[global]\n");
        conf.append("   workgroup = WORKGROUP\n");
        conf.append("   netbios name = AndroidSamba\n");
        conf.append("   server string = Android Samba Server\n");
        conf.append("   \n");
        conf.append("   # 端口与协议\n");
        conf.append("   smb ports = 1445\n");
// 👇 修改 1: 降低最低协议版本，以兼容旧版客户端
        conf.append("   server min protocol = NT1\n");
// 👇 修改 2: 禁用 SMB1，但允许 NT1 (SMB 2.0)，这是一个更广泛的兼容设置
        conf.append("   server max protocol = SMB3\n");
        conf.append("   \n");
        conf.append("   # 网络接口：Android 上 getifaddrs 自动探测会失败，必须显式指定\n");
        conf.append("   # 用实际 WiFi IPv4 作为接口地址（0.0.0.0/0 会让 smbd 无法确定接口，macOS 可能因此拒连）\n");
        conf.append("   # 子网按 /24 处理（家庭局域网常见），不同子网可自行调整\n");
        String iface = (localIp != null && !localIp.isEmpty()) ? localIp + "/24" : "0.0.0.0/0";
        conf.append("   interfaces = ").append(iface).append("\n");
        conf.append("   bind interfaces only = no\n");
        conf.append("   \n");
        conf.append("   # 安全与用户配置\n");
        conf.append("   security = user\n");
        conf.append("   passdb backend = smbpasswd:").append(sambaEtc).append("/smbpasswd\n");
        // 免密核心：把客户端各种用户名(含 macOS 登录名/GUEST)映射到 debug(空密码)
        conf.append("   username map = ").append(sambaEtc).append("/username.map\n");
        // 免密访问：匿名/未知用户都映射到 guest，guest(nobody) 由 shim 映射到 app uid
        conf.append("   map to guest = Bad User\n");
        conf.append("   guest account = nobody\n");
        // guest 认证方法放在最前，保证匿名会话能直接成为 guest，不需要密码
        conf.append("   auth methods = guest sam\n");
        // 👇 允许 NTLMv1 认证：某些客户端(curl/旧版)只用 NTLMv1，smbd 默认禁止会 Login denied
        conf.append("   ntlm auth = yes\n");
        // 签名：auto(默认) 即可——mandatory 会让 macOS 在协商后直接断开；
        // auto 下客户端要求签名时服务端会签（python SMB3 客户端实测兼容）
        conf.append("   \n");
// 👇 修改 3: 增加一个参数，强制使用 Unix 换行符，避免某些解析问题
        conf.append("   unix extensions = no\n");
        conf.append("   \n");
        conf.append("   # 性能与日志\n");
        conf.append("   socket options = TCP_NODELAY\n");
        conf.append("   dns proxy = no\n");
        conf.append("   pid directory = ").append(runDir).append("\n");
        conf.append("   lock directory = ").append(runDir).append("\n");
        conf.append("   state directory = ").append(runDir).append("\n");
        // 👇 把二进制编译期默认路径(/data/samba/private 等)全部指到 cache/samba/var，否则 smbd 无权限创建
        conf.append("   private dir = ").append(runDir).append("\n");
        // 这个构建不认识 core directory，去掉以免报未知参数（corepath 失败是告警，不影响运行）
        conf.append("   ncalrpc dir = ").append(runDir).append("\n");
        conf.append("   cache directory = ").append(runDir).append("\n");
        conf.append("   # 指定日志文件（统一在 var 下）\n");
        conf.append("   log file = ").append(runDir).append("/smbd.log\n");
        conf.append("   log level = 4\n"); // 增加日志详细程度（排障用，可调低）

        // 3. 定义共享目录（用小写名，方便客户端输入）
        // 共享1：app 私有目录（data 目录本身）
        appendShare(conf, "data", "App private data", context.getDataDir().getAbsolutePath());

        // 共享2：外部存储 Android/data/<pkg>（Android 11+ 应用可访问自己的 Android/data 目录）
        File extFiles = context.getExternalFilesDir(null);
        if (extFiles != null) {
            File extPkgDir = extFiles.getParentFile(); // .../Android/data/<pkg>
            if (extPkgDir != null) {
                appendShare(conf, "appdata", "External Android/data app dir", extPkgDir.getAbsolutePath());
            }
        }

        // 写入文件
        writeFile(new File(sambaRoot, "etc/smb.conf"), conf.toString());
        Log.i(TAG, "smb.conf generated at: " + sambaRoot);
    }

    /** 返回已配置的共享清单（用于启动后打印使用说明），与 generateSmbConf 保持一致。 */
    public static String getShareSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("   /data     → App 私有目录\n");
        sb.append("               ").append(context.getDataDir().getAbsolutePath()).append("\n");
        File ext = context.getExternalFilesDir(null);
        if (ext != null && ext.getParentFile() != null) {
            sb.append("   /appdata  → Android/data 外部存储\n");
            sb.append("               ").append(ext.getParentFile().getAbsolutePath()).append("\n");
        }
        return sb.toString();
    }

    /** 追加一个共享段。统一 guest ok + 最大权限，避免重复。 */
    private static void appendShare(StringBuilder conf, String name, String comment, String path) {
        conf.append("[").append(name).append("]\n");
        conf.append("   comment = ").append(comment).append("\n");
        conf.append("   path = ").append(path).append("\n");
        conf.append("   browseable = yes\n");
        conf.append("   writable = yes\n");
        conf.append("   guest ok = yes\n");
        conf.append("   create mask = 0777\n");
        conf.append("   directory mask = 0777\n");
        // 不能用 force user = nobody：共享目录通常是 app uid 私有(0700)，
        // 只有 app uid(debug) 能访问，nobody 会被拒 → Access denied
    }

    private static void generateSmbPasswd(File sambaRoot) throws Exception {
        // 空密码的 NT hash：MD4("") = 31D6CFE0D16AE931B73C59D7E0C089C0
        // 免密方案：debug 账号无密码，配合 username map 把客户端用户名都映射到 debug
        String ntHash = "31D6CFE0D16AE931B73C59D7E0C089C0";
        // X = 密码永不过期；LCT 必须是非 0 的时间戳，否则 smbd 判定"密码必须修改"拒绝登录
        String lct = String.format("%08X", System.currentTimeMillis() / 1000);
        // uid 用应用真实 uid，保证与 shim 的 getpwuid 解析一致
        int uid = android.os.Process.myUid();
        String line = String.format(
                "debug:%d:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:%s:[UX         ]:LCT-%s:\n",
                uid, ntHash, lct
        );
        writeFile(new File(sambaRoot, "etc/smbpasswd"), line);
        Log.i(TAG, "smbpasswd generated (empty password).");
    }

    /**
     * username map：把客户端可能发送的各种用户名（macOS 登录名、GUEST、常见名）
     * 都映射到 debug（空密码）。这样 macOS 无论发什么用户名 + 空密码，都能认证成 debug，
     * 走真实用户会话（签名正常），绕开 macOS 不接受 guest 会话的问题。
     *
     * ⚠️ 集成到其他项目时：把你们客户端的登录用户名加进下面的列表（格式：debug = 用户名1 用户名2 ...）。
     * 这里预置了 zhaiyongdev（本调试项目 Mac 的登录名）等常见名字。
     */
    private static void generateUsernameMap(File sambaRoot) throws Exception {
        // 格式：unix用户名 = 客户端用户名列表（以空格分隔，大小写不敏感）
        String map = "debug = debug guest guestaccount zhaiyongdev administrator root user\n";
        writeFile(new File(sambaRoot, "etc/username.map"), map);
        Log.i(TAG, "username.map generated.");
    }

    // ==================== NT Hash 计算 ====================

    private static String computeNtHash(String password) {
        byte[] utf16le = password.getBytes(StandardCharsets.UTF_16LE);
        byte[] hash = new MD4().digest(utf16le);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private static String readAssetString(Context context, String assetPath) throws Exception {
        try (InputStream is = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private static String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static void writeFile(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

//    private static long getPid(Process process) {
//        try {
//            // Android API 26+ 支持 Process.pid()
//            return process.pid(); // 👈这里提示找不到 pid这个方法
//        } catch (Exception e) {
//            return -1;
//        }
//    }
}
