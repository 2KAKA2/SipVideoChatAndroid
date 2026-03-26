package com.sipvideochat.sip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.media.LocalMediaServer;
import com.sipvideochat.model.Message;
import com.sipvideochat.protocol.SipMessageBody;
import com.sipvideochat.ui.main.MainActivity;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground SIP service that keeps registration and media serving alive.
 */
public class SipService extends Service {
    private static final String TAG = "SipService";
    private static final String CHANNEL_ID = "sip_service_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long RE_REGISTER_INTERVAL = 90_000L;

    private SipClient sipClient;
    private ClientConfig config;
    private final IBinder binder = new SipBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler;
    private Handler reRegisterHandler;
    private Runnable reRegisterRunnable;
    private volatile SipEventListener externalListener;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    public class SipBinder extends Binder {
        public SipService getService() {
            return SipService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        reRegisterHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();

        try {
            startForeground(NOTIFICATION_ID, buildNotification("SIP 服务启动中..."));
        } catch (Exception e) {
            Log.e(TAG, "Failed to enter foreground", e);
        }

        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void initSip(ClientConfig config, SipEventListener listener) {
        this.config = config;
        this.externalListener = listener;

        if (sipClient != null) {
            Log.w(TAG, "SIP already initialized");
            ensureMediaServer();
            return;
        }

        executor.execute(() -> {
            try {
                sipClient = new SipClient(config);
                sipClient.addListener(new SipEventListener() {
                    @Override
                    public void onRegistered() {
                        mainHandler.post(() -> {
                            updateNotification("SIP 在线 - " + config.getUsername());
                            startReRegisterTimer();
                            if (externalListener != null) {
                                externalListener.onRegistered();
                            }
                        });
                    }

                    @Override
                    public void onRegisterFailed(String reason) {
                        mainHandler.post(() -> {
                            updateNotification("SIP 注册失败");
                            if (externalListener != null) {
                                externalListener.onRegisterFailed(reason);
                            }
                        });
                    }

                    @Override
                    public void onIncomingCall(String fromUser, String sdp, SipClient.IncomingInvite invite) {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onIncomingCall(fromUser, sdp, invite);
                            }
                        });
                    }

                    @Override
                    public void onCallRinging() {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onCallRinging();
                            }
                        });
                    }

                    @Override
                    public void onCallConnected(String remoteSdp) {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onCallConnected(remoteSdp);
                            }
                        });
                    }

                    @Override
                    public void onCallEnded() {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onCallEnded();
                            }
                        });
                    }

                    @Override
                    public void onCallFailed(String reason) {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onCallFailed(reason);
                            }
                        });
                    }

                    @Override
                    public void onMessageReceived(String fromUser, SipMessageBody message) {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onMessageReceived(fromUser, message);
                            }
                        });
                    }

                    @Override
                    public void onMessageStatusChanged(String messageId, Message.MessageStatus status, String reason) {
                        mainHandler.post(() -> {
                            if (externalListener != null) {
                                externalListener.onMessageStatusChanged(messageId, status, reason);
                            }
                        });
                    }
                });

                ensureMediaServer();
                sipClient.init();
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize SIP", e);
                mainHandler.post(() -> {
                    if (externalListener != null) {
                        externalListener.onRegisterFailed("初始化失败: " + e.getMessage());
                    }
                });
            }
        });
    }

    public void setEventListener(SipEventListener listener) {
        this.externalListener = listener;
    }

    public void sendMessage(String targetUser, SipMessageBody body) {
        executor.execute(() -> {
            try {
                if (sipClient != null) {
                    Log.i(TAG, "Queue SIP MESSAGE target=" + targetUser
                            + ", messageId=" + body.getMessageId()
                            + ", msgType=" + body.getMsgType()
                            + ", fileUrl=" + body.getFileUrl());
                    sipClient.sendMessage(targetUser, body);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send SIP MESSAGE", e);
                if (externalListener != null) {
                    String reason = e.getMessage() != null ? e.getMessage() : "Send failed";
                    mainHandler.post(() -> externalListener.onMessageStatusChanged(
                            body.getMessageId(), Message.MessageStatus.FAILED, reason));
                }
            }
        });
    }

    public void makeCall(String targetUser, boolean videoEnabled) {
        executor.execute(() -> {
            try {
                if (sipClient != null) {
                    sipClient.makeCall(targetUser, videoEnabled);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to place call", e);
            }
        });
    }

    public void answerCall(SipClient.IncomingInvite invite, boolean videoEnabled) {
        executor.execute(() -> {
            try {
                if (sipClient != null) {
                    sipClient.answerCall(invite, videoEnabled);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to answer call", e);
            }
        });
    }

    public void rejectCall(SipClient.IncomingInvite invite) {
        executor.execute(() -> {
            try {
                if (sipClient != null) {
                    sipClient.rejectCall(invite);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to reject call", e);
            }
        });
    }

    public void hangup() {
        executor.execute(() -> {
            try {
                if (sipClient != null) {
                    sipClient.hangup();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to hang up", e);
            }
        });
    }

    public void addUserContact(String username, String ip, int port) {
        if (sipClient != null) {
            sipClient.addUserContact(username, ip, port);
        }
    }

    public boolean isRegistered() {
        return sipClient != null && sipClient.isRegistered();
    }

    public boolean isInCall() {
        return sipClient != null && sipClient.isInCall();
    }

    public Set<String> getKnownContacts() {
        return sipClient != null ? sipClient.getKnownContacts() : Collections.emptySet();
    }

    public ClientConfig getConfig() {
        return config;
    }

    private void startReRegisterTimer() {
        stopReRegisterTimer();
        reRegisterRunnable = () -> {
            executor.execute(() -> {
                try {
                    if (sipClient != null) {
                        Log.w(TAG, "Periodic re-register");
                        sipClient.register();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to re-register", e);
                }
            });
            reRegisterHandler.postDelayed(reRegisterRunnable, RE_REGISTER_INTERVAL);
        };
        reRegisterHandler.postDelayed(reRegisterRunnable, RE_REGISTER_INTERVAL);
    }

    private void stopReRegisterTimer() {
        if (reRegisterRunnable != null) {
            reRegisterHandler.removeCallbacks(reRegisterRunnable);
            reRegisterRunnable = null;
        }
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.w(TAG, "Network available, refreshing SIP registration");
                executor.execute(() -> {
                    try {
                        if (sipClient != null && config != null) {
                            String newIp = ClientConfig.detectLocalIp(SipService.this);
                            if (!"0.0.0.0".equals(newIp) && !newIp.equals(config.getLocalIp())) {
                                Log.w(TAG, "Local IP changed: " + config.getLocalIp() + " -> " + newIp);
                                config.setLocalIp(newIp);
                                config.save(SipService.this);
                            }
                            ensureMediaServer();
                            sipClient.register();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to refresh after network recovery", e);
                    }
                });
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.w(TAG, "Network lost");
                updateNotification("SIP 离线 - 网络断开");
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SIP 服务",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持 SIP 注册与媒体分享在线");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SipVideoChat")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void ensureMediaServer() {
        if (config == null) {
            return;
        }

        String localIp = config.getLocalIp();
        if (localIp == null || localIp.isEmpty() || "0.0.0.0".equals(localIp)) {
            localIp = ClientConfig.detectLocalIp(this);
            config.setLocalIp(localIp);
            config.save(this);
        }

        if (localIp == null || localIp.isEmpty() || "0.0.0.0".equals(localIp)) {
            Log.w(TAG, "Skip media server startup because local IP is unavailable");
            return;
        }

        int mediaPort = config.getLocalSipPort() + 1000;
        try {
            LocalMediaServer.getInstance(this, localIp, mediaPort);
            Log.i(TAG, "Media server ready at http://" + localIp + ":" + mediaPort + "/media/");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start media server", e);
        }
    }

    @Override
    public void onDestroy() {
        stopReRegisterTimer();

        if (networkCallback != null && connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }

        LocalMediaServer mediaServer = LocalMediaServer.peek();
        if (mediaServer != null) {
            mediaServer.stop();
        }

        executor.execute(() -> {
            if (sipClient != null) {
                sipClient.shutdown();
                sipClient = null;
            }
        });

        executor.shutdown();
        super.onDestroy();
    }
}
