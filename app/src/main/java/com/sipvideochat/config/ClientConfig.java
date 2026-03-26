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
 */
public class ClientConfig {
    private static final String PREFS_NAME = "sip_config";

    private String sipServerHost = "10.29.173.2";
    private int sipServerPort = 5060;
    private String localIp = "";
    private int localSipPort = 5061;
    private int localAudioPort = 8000;
    private int localVideoPort = 9000;
    private String username = "";
    private String password = "";
    private int videoWidth = 320;
    private int videoHeight = 240;
    private int videoFrameRate = 25;
    private int videoBitrate = 500000;

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
        username = prefs.getString("username", username);
        password = prefs.getString("password", password);
        videoWidth = prefs.getInt("video.width", videoWidth);
        videoHeight = prefs.getInt("video.height", videoHeight);
        videoFrameRate = prefs.getInt("video.framerate", videoFrameRate);
        videoBitrate = prefs.getInt("video.bitrate", videoBitrate);

        // 自动检测本机IP
        if (localIp.isEmpty()) {
            localIp = detectLocalIp(context);
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
     */
    public static String detectLocalIp(Context context) {
        try {
            // 先尝试WiFi
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

        // 遍历网络接口
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

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
}
