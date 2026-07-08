package com.sipvideochat.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 客户端配置管理（Android版本）
 * 使用 SharedPreferences 替代桌面端的 Properties 文件
 *
 * 支持两种模式:
 * - 普通模式: sip:100@192.168.1.100:5060 (LAN/WiFi)
 * - IMS 模式: sip:001010000000001@ims.mnc001.mcc001.3gppnetwork.org (4G USRP)
 */
public class ClientConfig {
    private static final String PREFS_NAME = "sip_config";

    // ===== SIP 服务器 =====
    private String sipServerHost = "10.29.112.119";  // Linux 宿主机 (docker Open5GS+IMS)
    private int sipServerPort = 5060;
    private String localIp = "";
    private int localSipPort = 5061;
    private int localAudioPort = 8000;
    private int localVideoPort = 9000;

    // ===== IMS 参数 =====
    private String realm = "ims.mnc001.mcc001.3gppnetwork.org";
    private boolean imsMode = false;
    private String impi = "";  // 如 001010000000001@ims.mnc001.mcc001.3gppnetwork.org
    private String impu = "";  // 如 sip:001010000000001@ims.mnc001.mcc001.3gppnetwork.org

    // ===== 管理服务器 =====
    private String adminServerHost = "";
    private int adminServerPort = 8090;

    // ===== 账号 =====
    private String username = "";
    private String password = "";

    // ===== 视频参数 =====
    private int videoWidth = 640;
    private int videoHeight = 480;
    private int videoFrameRate = 15;
    private int videoBitrate = 800000;

    public ClientConfig() {}

    /**
     * 从 SharedPreferences 加载配置
     */
    public void load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sipServerHost = prefs.getString("sip.server.host", sipServerHost);
        sipServerPort = prefs.getInt("sip.server.port", sipServerPort);
        localIp = prefs.getString("local.ip", localIp);
        localSipPort = prefs.getInt("local.sip.port", localSipPort);
        localAudioPort = prefs.getInt("local.audio.port", localAudioPort);
        localVideoPort = prefs.getInt("local.video.port", localVideoPort);
        realm = prefs.getString("ims.realm", realm);
        imsMode = prefs.getBoolean("ims.mode", imsMode);
        impi = prefs.getString("ims.impi", impi);
        impu = prefs.getString("ims.impu", impu);
        adminServerHost = prefs.getString("admin.server.host", adminServerHost);
        adminServerPort = prefs.getInt("admin.server.port", adminServerPort);
        username = prefs.getString("username", username);
        password = prefs.getString("password", password);
        videoWidth = prefs.getInt("video.width", videoWidth);
        videoHeight = prefs.getInt("video.height", videoHeight);
        videoFrameRate = prefs.getInt("video.framerate", videoFrameRate);
        videoBitrate = prefs.getInt("video.bitrate", videoBitrate);

        boolean migrated = false;

        // Normalize historical low-quality profiles to the current LAN test profile.
        if ((videoWidth == 320 && videoHeight == 240)
                || (videoWidth == 240 && videoHeight == 320)
                || (videoWidth == 160 && videoHeight == 120)
                || (videoWidth == 120 && videoHeight == 160)
                || (videoWidth == 288 && videoHeight == 384)
                || (videoWidth == 144 && videoHeight == 192)
                || (videoWidth == 96 && videoHeight == 128)
                || (videoWidth == 128 && videoHeight == 96)) {
            videoWidth = 640;
            videoHeight = 480;
            migrated = true;
        }
        if (videoFrameRate == 25 || videoFrameRate == 12
                || videoFrameRate == 10 || videoFrameRate == 8
                || videoFrameRate == 6 || videoFrameRate == 4) {
            videoFrameRate = 15;
            migrated = true;
        }
        if (videoBitrate == 500000 || videoBitrate == 450000 || videoBitrate == 350000
                || videoBitrate == 320000 || videoBitrate == 160000 || videoBitrate == 120000
                || videoBitrate == 300000 || videoBitrate == 96000 || videoBitrate == 80000) {
            videoBitrate = 800000;
            migrated = true;
        }

        // 自动检测本机IP
        String detectedLocalIp = detectLocalIp(context);
        if (isUsableLocalIp(detectedLocalIp) && !detectedLocalIp.equals(localIp)) {
            localIp = detectedLocalIp;
            migrated = true;
        } else if (localIp.isEmpty()) {
            localIp = detectedLocalIp;
            migrated = true;
        }

        if (migrated) {
            save(context);
        }
    }

    /**
     * 保存配置到 SharedPreferences
     */
    public void save(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString("sip.server.host", sipServerHost);
        editor.putInt("sip.server.port", sipServerPort);
        editor.putString("local.ip", localIp);
        editor.putInt("local.sip.port", localSipPort);
        editor.putInt("local.audio.port", localAudioPort);
        editor.putInt("local.video.port", localVideoPort);
        editor.putString("ims.realm", realm);
        editor.putBoolean("ims.mode", imsMode);
        editor.putString("ims.impi", impi);
        editor.putString("ims.impu", impu);
        editor.putString("admin.server.host", adminServerHost);
        editor.putInt("admin.server.port", adminServerPort);
        editor.putString("username", username);
        editor.putString("password", password);
        editor.putInt("video.width", videoWidth);
        editor.putInt("video.height", videoHeight);
        editor.putInt("video.framerate", videoFrameRate);
        editor.putInt("video.bitrate", videoBitrate);
        editor.apply();
    }

    /**
     * 检测本机IP地址
     * 优先顺序: 蜂窝数据 (rmnet) > WiFi (wlan) > 其他接口
     */
    public static String detectLocalIp(Context context) {
        // 先检测蜂窝数据接口 (4G LTE via USRP) — 兼容 rmnet, rmnet_data, ccmni 等命名
        for (String prefix : new String[]{"rmnet_data", "rmnet", "ccmni"}) {
            String cellularIp = findInterfaceIp(prefix);
            if (isUsableLocalIp(cellularIp)) {
                return cellularIp;
            }
        }

        try {
            // 再尝试WiFi
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                if (ipInt != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                }
            }
        } catch (Exception e) {
            // fall through
        }

        // 遍历其他网络接口
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                // 跳过已检测过的 rmnet 和 wlan
                String name = ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("wlan")) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }

        return "0.0.0.0";
    }

    /**
     * 查找指定前缀的网络接口的 IPv4 地址
     */
    private static String findInterfaceIp(String prefix) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp()) continue;
                String name = ni.getName();
                if (name.startsWith(prefix)) {
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    private static boolean isUsableLocalIp(String ip) {
        return ip != null && !ip.trim().isEmpty() && !"0.0.0.0".equals(ip.trim());
    }

    // ============== Getters and Setters ==============

    public String getSipServerHost() { return sipServerHost; }
    public void setSipServerHost(String sipServerHost) { this.sipServerHost = sipServerHost; }

    public int getSipServerPort() { return sipServerPort; }
    public void setSipServerPort(int sipServerPort) { this.sipServerPort = sipServerPort; }

    public String getLocalIp() { return localIp; }
    public void setLocalIp(String localIp) { this.localIp = localIp; }

    public int getLocalSipPort() { return localSipPort; }
    public void setLocalSipPort(int localSipPort) { this.localSipPort = localSipPort; }

    public int getLocalAudioPort() { return localAudioPort; }
    public void setLocalAudioPort(int localAudioPort) { this.localAudioPort = localAudioPort; }

    public int getLocalVideoPort() { return localVideoPort; }
    public void setLocalVideoPort(int localVideoPort) { this.localVideoPort = localVideoPort; }

    // ===== IMS =====
    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }

    public boolean isImsMode() { return imsMode; }
    public void setImsMode(boolean imsMode) { this.imsMode = imsMode; }

    public String getImpi() { return impi; }
    public void setImpi(String impi) { this.impi = impi; }

    public String getImpu() { return impu; }
    public void setImpu(String impu) { this.impu = impu; }

    /**
     * 根据 IMS 模式生成 SIP URI
     * IMS 模式: sip:IMSI@realm
     * 普通模式: sip:username@serverHost:port
     */
    public String getSipUri() {
        if (imsMode && impu != null && !impu.isEmpty()) {
            return impu;
        }
        return "sip:" + username + "@" + sipServerHost + ":" + sipServerPort;
    }

    /**
     * 生成 From 头的 SIP URI (不带端口)
     */
    public String getFromUri() {
        if (imsMode && impu != null && !impu.isEmpty()) {
            return impu;
        }
        return "sip:" + username + "@" + sipServerHost;
    }

    public String getAdminServerHost() {
        if (adminServerHost == null || adminServerHost.trim().isEmpty()) {
            return sipServerHost;
        }
        return adminServerHost;
    }
    public void setAdminServerHost(String adminServerHost) { this.adminServerHost = adminServerHost; }

    public int getAdminServerPort() { return adminServerPort; }
    public void setAdminServerPort(int adminServerPort) { this.adminServerPort = adminServerPort; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getVideoWidth() { return videoWidth; }
    public void setVideoWidth(int videoWidth) { this.videoWidth = videoWidth; }

    public int getVideoHeight() { return videoHeight; }
    public void setVideoHeight(int videoHeight) { this.videoHeight = videoHeight; }

    public int getVideoFrameRate() { return videoFrameRate; }
    public void setVideoFrameRate(int videoFrameRate) { this.videoFrameRate = videoFrameRate; }

    public int getVideoBitrate() { return videoBitrate; }
    public void setVideoBitrate(int videoBitrate) { this.videoBitrate = videoBitrate; }

    public boolean isSipConfigured() {
        return sipServerHost != null
                && !sipServerHost.trim().isEmpty()
                && username != null
                && !username.trim().isEmpty()
                && localSipPort > 0
                && sipServerPort > 0;
    }
}
