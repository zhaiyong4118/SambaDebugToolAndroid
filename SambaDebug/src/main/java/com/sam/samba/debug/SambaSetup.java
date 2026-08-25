package com.sam.samba.debug;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
        generateSmbConf(context, sambaRoot);

        // 5. 生成 smbpasswd
        generateSmbPasswd(sambaRoot);

        return sambaRoot;
    }

    public static Process startDaemon(File sambaRoot, String daemonName) throws Exception {
        File binFile = new File(sambaRoot, "bin/" + daemonName);
        File confFile = new File(sambaRoot, "etc/smb.conf");
        String libPath = new File(sambaRoot, "lib").getAbsolutePath()
                + ":" + new File(sambaRoot, "lib/private").getAbsolutePath();

        // 👇 核心修改：使用 app_process 来执行二进制文件
        // 命令解释：
        // app_process: Android 的进程启动器，位于 /system/bin，有执行权限。
        // /system/bin: app_process 的第一个参数，指定进程启动的根目录，随意指定一个系统目录即可。
        // com.example.samba: 一个假的类名，app_process 需要一个类名参数，但我们实际不加载任何 Java 类。
        // -D -S ...: 这些是传递给 smbd 的实际参数。
        String[] cmd = new String[] {
                "/system/bin/app_process",
                "/system/bin",
                "com.example.samba", // 这个类名是假的，只是为了满足 app_process 的参数格式
                binFile.getAbsolutePath(),
                "-D",
                "-S",
                "--configfile=" + confFile.getAbsolutePath()
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 设置库路径环境变量
        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("TMPDIR", System.getProperty("java.io.tmpdir"));

        pb.redirectErrorStream(true);

        Process process = pb.start();
        Log.i(TAG, daemonName + " started via app_process");

        // 等待一小段时间检查是否立即退出
        Thread.sleep(500);
        try {
            int exitCode = process.exitValue();
            String output = readStream(process.getInputStream());
            throw new RuntimeException(daemonName + " exited immediately with code "
                    + exitCode + "\nOutput: " + output);
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

    private static void generateSmbConf(Context context, File sambaRoot) throws Exception {
        String template = readAssetString(context, "samba/smb.conf.template");
        String appPrivateDir = context.getFilesDir().getAbsolutePath();
        String sambaEtc = new File(sambaRoot, "etc").getAbsolutePath();

        String config = template
                .replace("${app_private_dir}", appPrivateDir)
                .replace("${samba_etc}", sambaEtc);

        writeFile(new File(sambaRoot, "etc/smb.conf"), config);
        Log.i(TAG, "smb.conf generated.");
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
