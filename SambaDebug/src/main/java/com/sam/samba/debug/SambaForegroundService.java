package com.sam.samba.debug;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class SambaForegroundService extends Service {

    private static final String TAG = "SambaDebug";
    private static final String CHANNEL_ID = "samba_debug_channel";
    private static final int NOTIFICATION_ID = 9527;
    private static final int SMB_PORT = 1445;

    private Process smbdProcess;
//    private Process nmbdProcess;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. 先显示通知（前台服务要求 5 秒内调用 startForeground）
        startForeground(NOTIFICATION_ID, buildNotification("Starting Samba..."));

        // 2. 在后台线程执行耗时操作
        new Thread(() -> {
            try {
                // 获取 IP 地址
                String ip = getLocalIpAddress();

                // 拷贝文件 & 生成配置
                File sambaRoot = SambaSetup.setup(this, ip);

                // 启动 smbd
                smbdProcess = SambaSetup.startDaemon(sambaRoot, "smbd");

                // 启动 nmbd（可选，用于 NetBIOS 名称发现）
//                nmbdProcess = SambaSetup.startDaemon(sambaRoot, "nmbd");

                // 持有 WakeLock 防止 CPU 休眠
                acquireWakeLock();

                // 持有 MulticastLock 让 nmbd 能收到广播
                acquireMulticastLock();

                // 更新通知为最终状态
                updateNotification(ip);

                Log.i(TAG, "Samba service started successfully on " + ip + ":" + SMB_PORT);

                // 打印完整使用说明到 logcat，方便不熟悉用法的开发者
                printUsageInfo(ip);

            } catch (Exception e) {
                Log.e(TAG, "Failed to start Samba service", e);
                updateNotificationError(e.getMessage());
            }
        }, "samba-start-thread").start();

        return START_STICKY; // 被杀后自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 停止 smbd 和 nmbd
        if (smbdProcess != null) smbdProcess.destroy();
//        if (nmbdProcess != null) nmbdProcess.destroy();
        // 释放锁
        releaseWakeLock();
        releaseMulticastLock();
        Log.i(TAG, "Samba service stopped.");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================== 通知 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "SMB Debug Server", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("SMB debug server status");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("SMB Debug Server")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String ip) {
        String connectInfo;
        if (ip != null) {
            connectInfo = String.format(
                    "✅ Running | smb://%s:%d\n"
                            + "免密 | 用户名任意，密码留空\n"
                            + "共享: /data /appdata\n"
                            + "macOS: ⌘K → smb://%s:%d/data",
                    ip, SMB_PORT, ip, SMB_PORT);
        } else {
            connectInfo = "✅ Running | Port: " + SMB_PORT + "\n免密 | 用户名任意，密码留空";
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("SMB Debug Server")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(connectInfo))
                .setContentText("Running on port " + SMB_PORT)
                .setOngoing(true)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }

    private void updateNotificationError(String error) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("SMB Debug Server")
                .setContentText("❌ Failed: " + error)
                .setOngoing(true)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }

    /** 打印完整的使用说明到 logcat，供不熟悉用法的开发者快速上手。 */
    private void printUsageInfo(String ip) {
        String addr = (ip != null && !ip.isEmpty()) ? ip : "<本机局域网IP>";
        StringBuilder sb = new StringBuilder();
        sb.append("\n===========================================================\n");
        sb.append("Samba 服务已启动\n");
        sb.append("\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append("smb://").append(addr).append(":").append(SMB_PORT).append("/data\n");
        sb.append("smb://").append(addr).append(":").append(SMB_PORT).append("/appdata\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append("adb forward tcp:").append(SMB_PORT).append(" tcp:").append(SMB_PORT).append("\n");
        sb.append("smb://127.0.0.1:").append(SMB_PORT).append("/data\n");
        sb.append("smb://127.0.0.1:").append(SMB_PORT).append("/appdata\n");
        sb.append("\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append("-------------------------使用说明----------------------------\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append("服务地址 : smb://").append(addr).append(":").append(SMB_PORT).append("\n");
        sb.append("账号     : 免密（用户名任意，密码留空）\n");
        sb.append("共享目录（完整路径，可直接复制）:\n");
        sb.append(SambaSetup.getShareSummary(this));
        sb.append("-----------------------------------------------------------\n");
        sb.append("【WiFi 连接】手机与电脑同局域网，直接访问:\n");
        sb.append("  smb://").append(addr).append(":").append(SMB_PORT).append("/data\n");
        sb.append("  smb://").append(addr).append(":").append(SMB_PORT).append("/appdata\n");
        sb.append("  macOS : Finder → ⌘K → smb://").append(addr).append(":").append(SMB_PORT).append("/data\n");
        sb.append("  Linux : smbclient //").append(addr).append("/data -p ").append(SMB_PORT).append("\n");
        sb.append("  Windows: 系统 SMB 客户端固定用 445 端口，需在本机转发:\n");
        sb.append("    netsh interface portproxy add v4tov4 listenport=445 ")
          .append("listenaddress=0.0.0.0 connectport=").append(SMB_PORT)
          .append("connectaddress=").append(addr).append("\n");
        sb.append("    （执行后访问 \\\\").append(addr).append("\\data）\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append("【USB 连接】更快更稳（实测比 WiFi 快数倍）:\n");
        sb.append("  1) 手机连接电脑 USB，执行:\n");
        sb.append("     adb forward tcp:").append(SMB_PORT).append(" tcp:").append(SMB_PORT).append("\n");
        sb.append("  2) 然后访问（用 127.0.0.1，不要用 localhost）:\n");
        sb.append("     smb://127.0.0.1:").append(SMB_PORT).append("/data\n");
        sb.append("     smb://127.0.0.1:").append(SMB_PORT).append("/appdata\n");
        sb.append("     macOS : Finder → ⌘K → smb://127.0.0.1:").append(SMB_PORT).append("/data\n");
        sb.append("     Linux : smbclient //127.0.0.1/data -p ").append(SMB_PORT).append("\n");
        sb.append("  注意: 重插 USB / adb 重启后 forward 会失效，需重新执行\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append("日志/运行文件 : cache/samba/var/（直接删除该目录即可清理）\n");
        sb.append("===========================================================\n");
        Log.i(TAG, sb.toString());
    }

    // ==================== 工具方法 ====================

    private String getLocalIpAddress() {
        try {
            // 只向客户端展示局域网 IPv4 地址。优先 WiFi(wlan) 接口，
            // 否则容易取到 rmnet_data0(蜂窝) 的 fe80:: link-local IPv6，客户端根本连不上。
            String ipv4Fallback = null;
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName();
                if (name == null) continue;
                // 跳过蜂窝数据/虚拟/回环接口
                if (name.contains("rmnet") || name.contains("tun")
                        || name.contains("ppp") || name.contains("dummy")) continue;

                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.contains(":")) continue; // 只要 IPv4
                    if (name.startsWith("wlan")) {
                        return ip; // WiFi IPv4 首选
                    }
                    if (ipv4Fallback == null) ipv4Fallback = ip;
                }
            }
            if (ipv4Fallback != null) return ipv4Fallback;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IP address", e);
        }
        return null;
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SambaDebug::WakeLock");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void acquireMulticastLock() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wm.createMulticastLock("SambaDebug::MulticastLock");
        multicastLock.acquire();
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }
}