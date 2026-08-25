# SambaDebugTool — Android 无 root Samba 服务器

一个纯 Android **Library**（模块名 `SambaDebug`，包名 `com.sam.samba.debug`），在 **不 root** 的前提下，于 Android 设备上运行真正的 Samba(`smbd`) 服务器，让电脑通过 SMB 协议直接访问 App 的文件。

> 适用于调试场景：需要快速查看/拉取/推送 App 私有目录、`Android/data` 目录下文件，而不用反复 `adb pull/push` 或 root。

---

## 背景与目的

开发 Android 应用时经常需要查看 App 数据目录里的文件（数据库、缓存、导出文件等）。常见手段：
- `adb shell run-as` + `ls/cat/pull` —— 每次都要敲命令，看目录树很痛苦；
- root 后用文件管理器 —— 不是所有测试机都能 root；
- 用第三方 SMB Server App —— 无法自定义共享哪个目录。

本项目把 **Samba 服务器直接塞进你的 App**：App 一启动，同一局域网（或 USB 数据线）上的电脑就能像访问网络共享一样浏览、读写指定目录，**无需 root、无需密码**。

---

## 特性

- ✅ **无 root**：全程以 App 自身 uid 运行，不调用 `su`
- ✅ **免密**：用户名任意、密码留空即可连接（内部映射到 `debug` 空密码账号）
- ✅ **多共享目录**：
  - `/data` —— App 私有目录（`/data/user/0/<pkg>`）
  - `/appdata` —— 外部存储 `Android/data/<pkg>`
- ✅ **多连接方式**：WiFi 局域网 / USB 数据线（`adb forward`，实测快数倍）
- ✅ **自动启动**：Debug 构建集成后，ContentProvider 自动拉起服务，无需手动调用
- ✅ **开机即用**：启动后完整使用说明直接打印到 logcat

---

## 工作原理（无 root 是如何做到的）

Android 对非 root 进程跑原生 SMB 服务器有一系列限制，本库逐一绕过：

| 限制 | 现象 | 解决方案 |
|------|------|----------|
| `app_process` 不能 exec 原生二进制 | Android 13 把类名当 Java 类加载，SIGABRT | 用 `/system/bin/linker` **加载**二进制（mmap 方式，绕过 execve） |
| SELinux 禁止 App execve 自己目录的二进制 | `avc: denied execute_no_trans` | 链接器 mmap 加载，只需 `execute` 权限 |
| seccomp 杀掉 setuid/setgroups 系统调用 | `SIGSYS (SYS_SECCOMP)`，exit 159 | `LD_PRELOAD` shim 拦截 setuid 族，伪装成功 |
| Android `/etc/passwd` 为空 | smbd 解析不到用户 | shim 伪造 `getpwnam/getpwuid` |
| smbd 以为自己是 root 导致目录校验失败 | `invalid ownership` | shim 返回真实 uid，smbd 走「非 root 模式」 |
| 二进制编译期默认路径不可创建 | `mkdir /data/samba/...` 失败 | 配置里把所有目录指向 `cache/samba/var` |
| getifaddrs 接口探测失败 | `no network interfaces found` | 配置显式 `interfaces = <实际IP>/24` |

核心是一个 **LD_PRELOAD 垫片** `libsmbd_shim.so`（源码在 `SambaDebug/shim/src/samba_noop_shim.c`），它在 libc 层拦截并处理上述系统调用/库函数。

---

## 快速开始

### 1. 运行 demo App

```bash
./gradlew :app:installDebug
```

### 2. 启动后查看 logcat 里的使用说明

```bash
adb logcat -s SambaDebug
```

启动成功后会打印完整连接指南（服务地址、共享目录完整路径、WiFi/USB 连接方法）。

### 3. 连接

- **WiFi**（手机和电脑同一局域网）：Finder → `⌘K` → `smb://<手机IP>:1445/data`
- **USB**（更快）：`adb forward tcp:1445 tcp:1445` 后访问 `smb://127.0.0.1:1445/data`

**用户名 `debug`、密码留空**（或用户名任意已在 `username.map` 里配置的名字、密码留空）。

---

## 集成到自己的项目

1. 把 `SambaDebug` 模块拷进你的工程，并在 `settings.gradle` 里 `include(":SambaDebug")`
2. 主工程 `build.gradle` 添加依赖（建议只打 debug 包）：

   ```groovy
   dependencies {
       debugImplementation project(":SambaDebug")
   }
   ```

3. 无需任何初始化代码 —— Debug 构建下 `SambaInitProvider`（ContentProvider）会自动启动服务。

> ⚠️ 服务只在 **debug 构建**（`android:debuggable=true`）下自动启动，release 包不会启动，避免误打包进线上版本。

### 集成后要检查的点

- **二进制架构**：`SambaDebug/src/main/assets/samba/bin` 和 `lib` 里的 smbd 及 `.so` 必须与目标设备 ABI 一致（当前为 **armeabi-v7a 32 位**）。换架构时同时要：
  - 换掉 `bin/`、`lib/` 下全部文件；
  - 用同架构重新编译 shim（见下文）；
  - 把 `SambaSetup.startDaemon()` 里的链接器路径改成 `/system/bin/linker64`（arm64）或 `/system/bin/linker`（arm32）。
- **客户端用户名**：`SambaSetup.generateUsernameMap()` 里的 `username.map` 预置了一些常见用户名（含本调试项目 Mac 的 `zhaiyongdev`），把你们客户端的登录名加进去，或直接用 `debug` 用户名连接。

---

## 共享目录与配置

| 共享名 | 对应路径 | 说明 |
|--------|----------|------|
| `data` | `/data/user/0/<pkg>` | App 私有目录（始终存在） |
| `appdata` | `/storage/emulated/0/Android/data/<pkg>` | App 外部存储目录（外部存储可用时存在） |

- 端口：`1445`（>1024，无 root 可绑定）
- 账号：**免密**，用户名 `debug`（或已配置在 `username map` 的名字）+ 空密码即可连接
- 日志/锁文件：统一在 `cache/samba/var/`，删除该目录即可完整清理
- 连接信息与共享列表会在启动时打印到 logcat

---

## 目录结构

```
SambaDebug/
├── src/main/
│   ├── AndroidManifest.xml        # 权限、ContentProvider、前台服务声明
│   └── java/com/sam/samba/debug/
│       ├── SambaInitProvider.java     # ContentProvider，debug 下自动启动服务
│       ├── SambaForegroundService.java# 前台服务：启动/停止 smbd，打印使用说明
│       ├── SambaSetup.java            # 拷贝二进制、生成配置、链接器启动 smbd
│       └── MD4.java                   # 计算 NT hash（备用）
│   └── assets/samba/                  # 打包的 samba 运行时
│       ├── bin/                       # smbd / nmbd / smbpasswd（按架构）
│       ├── lib/                       # samba 动态库（按架构）
│       └── shim/libsmbd_shim.so       # 无 root 垫片（按架构）
└── shim/src/samba_noop_shim.c         # 垫片源码（手工编译）
```

---

## 编译 shim（更换架构时）

shim 必须与 smbd 同架构，用 Android NDK 的 clang 手工编译：

```bash
# arm32
armv7a-linux-androideabi21-clang -shared -fPIC -O2 \
  -o SambaDebug/src/main/assets/samba/shim/libsmbd_shim.so \
  SambaDebug/shim/src/samba_noop_shim.c

# arm64
aarch64-linux-android21-clang -shared -fPIC -O2 \
  -o SambaDebug/src/main/assets/samba/shim/libsmbd_shim.so \
  SambaDebug/shim/src/samba_noop_shim.c
```

---

## 连接方式详解

### WiFi 局域网

手机和电脑连同一个路由器/网络：
- macOS：`Finder` → `⌘K` → `smb://<IP>:1445/data`
- Linux：`smbclient //<IP>/data -p 1445`
- Windows：系统 SMB 客户端固定用 445 端口，需先在本机转发：
  ```bat
  netsh interface portproxy add v4tov4 listenport=445 listenaddress=0.0.0.0 connectport=1445 connectaddress=<IP>
  ```
  然后访问 `\\<IP>\data`

### USB 数据线（更快，推荐）

```bash
adb forward tcp:1445 tcp:1445
```
然后访问 `smb://127.0.0.1:1445/data`（**用 `127.0.0.1`，不要用 `localhost`**，避免 IPv6 解析问题）。

> USB 走有线传输，实测比 WiFi 快数倍；但重插 USB / adb 重启后 forward 会失效，需重新执行。

---

## 常见问题（排障）

| 现象 | 处理 |
|------|------|
| `adb logcat -s SambaDebug` 没有使用说明 | 确认是 debug 构建；查看 `SambaSetup` 日志里 smbd 是否报错退出 |
| 连接提示「服务器不允许登录」 | 用户名留任意 + 密码留空；不要用 `guest` 之外的认证方式 |
| macOS 报「检查服务器名称或 IP」 | 端口必须是 `1445`（非默认 445）；确认手机 IP 正确 |
| smbd 启动即退出 | 查看 `cache/samba/var/smbd.log` 的报错 |
| 二进制架构不匹配 | `adb shell getprop ro.product.cpu.abi` 核对，按上文换对应架构二进制 + shim |

---

## 已知限制

- smbd 以 App 自身 uid 运行，只能访问 **该 App 有权访问** 的目录（自己的私有目录、自己的 `Android/data` 目录）；无法访问其他 App 的数据或整张 SD 卡。
- 当前 bundled 二进制为 **armeabi-v7a（32 位）**，换设备 ABI 需要替换二进制并重新编译 shim。
- 服务随 App 进程存活；App 被系统杀死后服务停止（`START_STICKY` 会自动尝试重启）。
- 无 root 方案依赖 Android 动态链接器的 mmap 加载行为，极端定制 ROM 上可能不适用。
