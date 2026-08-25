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

    public static File setup(Context context) throws Exception {
//        File sambaRoot = new File(context.getFilesDir(), SAMBA_DIR);
        File sambaRoot = new File(context.getCacheDir(), SAMBA_DIR);

        // 1. 从 assets 递归拷贝二进制和库文件
        copyAssetDir(context.getAssets(), SAMBA_DIR, sambaRoot);
        Log.i(TAG, "Files copied to " + sambaRoot.getAbsolutePath());

        // 2. 设置执行权限
        setExecutableRecursive(new File(sambaRoot, "bin"));
        setExecutableRecursive(new File(sambaRoot, "lib"));

        // 3. 创建运行目录
        new File(sambaRoot, "etc").mkdirs();
        new File(sambaRoot, "private").mkdirs();
        new File(sambaRoot, "var").mkdirs();

        // 4. 生成 smb.conf
        generateSmbConf(context, sambaRoot, new File(context.getDataDir().getAbsolutePath()));

        // 5. 生成 smbpasswd
        generateSmbPasswd(sambaRoot);

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

        String[] cmd = new String[]{
                "/system/bin/app_process",
                "/system/bin",
                "com.example.samba",
                binFile.getAbsolutePath(),
                "-D",
                "-S",
                "-l", logFile,
                "--configfile=" + confFile.getAbsolutePath()
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("TMPDIR", System.getProperty("java.io.tmpdir"));
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

    private static void generateSmbConf(Context context, File sambaRoot, File shareDir) throws Exception {
        // 1. 准备路径
        // 注意：这里使用 shareDir 作为共享目录，确保路径正确
        String sharePath = shareDir.getAbsolutePath();
        String sambaEtc = new File(sambaRoot, "etc").getAbsolutePath();
        String sambaLib = new File(sambaRoot, "lib").getAbsolutePath();
        // 指定一个可写的目录作为锁文件和pid文件的存放地，避免权限问题
        String lockDir = context.getCacheDir().getAbsolutePath();

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
        conf.append("   # 网络接口\n");
        conf.append("   interfaces = 0.0.0.0\n");
        conf.append("   bind interfaces only = yes\n");
        conf.append("   \n");
        conf.append("   # 安全与用户配置\n");
        conf.append("   security = user\n");
        conf.append("   passdb backend = smbpasswd:").append(sambaEtc).append("/smbpasswd\n");
        conf.append("   map to guest = Bad User\n");
        conf.append("   guest account = nobody\n");
        conf.append("   \n");
// 👇 修改 3: 增加一个参数，强制使用 Unix 换行符，避免某些解析问题
        conf.append("   unix extensions = no\n");
        conf.append("   \n");
        conf.append("   # 性能与日志\n");
        conf.append("   socket options = TCP_NODELAY\n");
        conf.append("   dns proxy = no\n");
        conf.append("   pid directory = ").append(lockDir).append("\n");
        conf.append("   lock directory = ").append(lockDir).append("\n");
        conf.append("   state directory = ").append(lockDir).append("\n");
        conf.append("   # 👇 修改 4: 指定日志文件，方便我们调试\n");
        conf.append("   log file = ").append(lockDir).append("/smbd.log\n");
        conf.append("   log level = 2\n"); // 增加日志详细程度

        // 3. 定义共享目录
        conf.append("[Public]\n");
        conf.append("   comment = Public Share\n");
        conf.append("   path = ").append(sharePath).append("\n");
        conf.append("   browseable = yes\n");
        conf.append("   writable = yes\n");
        // 关键：允许访客访问此共享
        conf.append("   guest ok = yes\n");
        // 关键：赋予最大权限，避免 Android 写入失败
        conf.append("   create mask = 0777\n");
        conf.append("   directory mask = 0777\n");
        conf.append("   force user = nobody\n");

        // 写入文件
        writeFile(new File(sambaRoot, "etc/smb.conf"), conf.toString());
        Log.i(TAG, "smb.conf generated at: " + sambaRoot);
    }

    private static void generateSmbPasswd(File sambaRoot) throws Exception {
        String ntHash = computeNtHash("debug123");
        String line = String.format(
                "debug:2000:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:%s:[U          ]:LCT-00000000:\n",
                ntHash
        );
        writeFile(new File(sambaRoot, "etc/smbpasswd"), line);
        Log.i(TAG, "smbpasswd generated.");
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
