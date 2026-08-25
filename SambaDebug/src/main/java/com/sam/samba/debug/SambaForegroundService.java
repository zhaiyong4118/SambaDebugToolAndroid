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
                            + "User: debug / debug123\n"
                            + "macOS: ⌘K → smb://%s:%d/Private\n"
                            + "Win: netsh interface portproxy add v4tov4 "
                            + "listenport=445 listenaddress=0.0.0.0 "
                            + "connectport=%d connectaddress=%s",
                    ip, SMB_PORT, ip, SMB_PORT, SMB_PORT, ip);
        } else {
            connectInfo = "✅ Running | Port: " + SMB_PORT + "\nUser: debug / debug123";
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