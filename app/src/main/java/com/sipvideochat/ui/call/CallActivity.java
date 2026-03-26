package com.sipvideochat.ui.call;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.media.AudioCapture;
import com.sipvideochat.media.RTPAudioReceiver;
import com.sipvideochat.media.RTPAudioSender;
import com.sipvideochat.sip.SipClient;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通话界面（替代桌面端 CallFrame）
 * 支持音频通话（Phase 2），视频通话预留（Phase 3）
 */
public class CallActivity extends AppCompatActivity {
    private static final String TAG = "CallActivity";

    // 静态变量用于传递不可序列化的 IncomingInvite
    public static SipClient.IncomingInvite pendingInvite;

    private SipService sipService;
    private boolean serviceBound = false;

    private String remoteUser;
    private boolean isOutgoing;
    private boolean videoEnabled;
    private String remoteSdp;

    // 远程媒体信息
    private String remoteIp;
    private int remoteAudioPort;
    private int remoteVideoPort;

    // 媒体组件
    private AudioCapture audioCapture;
    private RTPAudioSender audioSender;
    private RTPAudioReceiver audioReceiver;

    // UI
    private TextView tvRemoteUser, tvCallType, tvCallStatus, tvTimer;
    private MaterialButton btnMute, btnHangup, btnAccept, btnToggleVideo;

    // 状态
    private boolean connected = false;
    private boolean muted = false;
    private long callStartTime;
    private Handler timerHandler;
    private Runnable timerRunnable;

    // 音频焦点
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SipService.SipBinder binder = (SipService.SipBinder) service;
            sipService = binder.getService();
            serviceBound = true;

            // 设置通话期间的事件监听器
            sipService.setEventListener(sipEventListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sipService = null;
            serviceBound = false;
        }
    };

    private final SipEventListener sipEventListener = new SipEventListener() {
        @Override
        public void onCallRinging() {
            runOnUiThread(() -> tvCallStatus.setText("对方振铃中..."));
        }

        @Override
        public void onCallConnected(String sdp) {
            runOnUiThread(() -> onConnected(sdp));
        }

        @Override
        public void onCallEnded() {
            runOnUiThread(() -> onEnded());
        }

        @Override
        public void onCallFailed(String reason) {
            runOnUiThread(() -> onFailed(reason));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        // 获取Intent数据
        remoteUser = getIntent().getStringExtra("remoteUser");
        isOutgoing = getIntent().getBooleanExtra("isOutgoing", true);
        videoEnabled = getIntent().getBooleanExtra("videoEnabled", false);
        remoteSdp = getIntent().getStringExtra("remoteSdp");

        // 初始化UI
        tvRemoteUser = findViewById(R.id.tvRemoteUser);
        tvCallType = findViewById(R.id.tvCallType);
        tvCallStatus = findViewById(R.id.tvCallStatus);
        tvTimer = findViewById(R.id.tvTimer);
        btnMute = findViewById(R.id.btnMute);
        btnHangup = findViewById(R.id.btnHangup);
        btnAccept = findViewById(R.id.btnAccept);
        btnToggleVideo = findViewById(R.id.btnToggleVideo);

        tvRemoteUser.setText(remoteUser);
        tvCallType.setText(videoEnabled ? "视频通话" : "语音通话");
        tvCallStatus.setText(isOutgoing ? "正在呼叫..." : "来电...");

        if (!isOutgoing) {
            btnAccept.setVisibility(View.VISIBLE);
        }

        if (videoEnabled) {
            btnToggleVideo.setVisibility(View.VISIBLE);
        }

        btnHangup.setOnClickListener(v -> hangup());
        btnMute.setOnClickListener(v -> toggleMute());
        btnAccept.setOnClickListener(v -> acceptCall());

        timerHandler = new Handler(Looper.getMainLooper());

        // 请求音频焦点
        requestAudioFocus();

        // 绑定SipService
        Intent serviceIntent = new Intent(this, SipService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void acceptCall() {
        if (sipService == null || pendingInvite == null) return;

        btnAccept.setVisibility(View.GONE);
        tvCallStatus.setText("正在接听...");

        // 解析远程SDP
        if (remoteSdp != null) {
            parseRemoteSDP(remoteSdp);
        }

        // 接听
        sipService.answerCall(pendingInvite, videoEnabled);

        // 启动媒体
        startMedia();

        connected = true;
        tvCallStatus.setText("通话中");
        tvTimer.setVisibility(View.VISIBLE);
        btnMute.setEnabled(true);
        startTimer();

        pendingInvite = null;
    }

    private void onConnected(String sdp) {
        if (sdp != null) {
            parseRemoteSDP(sdp);
        }

        connected = true;
        tvCallStatus.setText("通话中");
        tvTimer.setVisibility(View.VISIBLE);
        btnMute.setEnabled(true);

        startMedia();
        startTimer();
    }

    private void onEnded() {
        connected = false;
        tvCallStatus.setText("通话已结束");
        stopTimer();
        stopMedia();

        // 3秒后关闭
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }

    private void onFailed(String reason) {
        tvCallStatus.setText("通话失败: " + reason);
        btnHangup.setText("关闭");
        stopMedia();
    }

    private void parseRemoteSDP(String sdp) {
        if (sdp == null) return;

        Pattern cPattern = Pattern.compile("c=IN IP4 ([\\d.]+)");
        Matcher cMatcher = cPattern.matcher(sdp);
        if (cMatcher.find()) {
            remoteIp = cMatcher.group(1);
        }

        Pattern audioPattern = Pattern.compile("m=audio (\\d+)");
        Matcher audioMatcher = audioPattern.matcher(sdp);
        if (audioMatcher.find()) {
            remoteAudioPort = Integer.parseInt(audioMatcher.group(1));
        }

        Pattern videoPattern = Pattern.compile("m=video (\\d+)");
        Matcher videoMatcher = videoPattern.matcher(sdp);
        if (videoMatcher.find()) {
            remoteVideoPort = Integer.parseInt(videoMatcher.group(1));
        }

        Log.i(TAG, "远程媒体: IP=" + remoteIp + ", 音频端口=" + remoteAudioPort +
                ", 视频端口=" + remoteVideoPort);
    }

    private void startMedia() {
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) return;

        try {
            startAudio(config);
        } catch (Exception e) {
            Log.e(TAG, "启动媒体失败", e);
        }
    }

    private void startAudio(ClientConfig config) throws Exception {
        // 音频接收
        audioReceiver = new RTPAudioReceiver(config.getLocalAudioPort());
        audioReceiver.start();

        // 音频发送
        audioSender = new RTPAudioSender(config.getLocalAudioPort() + 1);
        audioSender.setRemote(remoteIp, remoteAudioPort);

        // 音频采集
        audioCapture = new AudioCapture();
        audioCapture.start((data, length) -> {
            if (!muted) {
                audioSender.sendAudio(data, length);
            }
        });

        Log.i(TAG, "音频已启动");
    }

    private void stopMedia() {
        if (audioCapture != null) {
            audioCapture.stop();
            audioCapture = null;
        }
        if (audioSender != null) {
            audioSender.close();
            audioSender = null;
        }
        if (audioReceiver != null) {
            audioReceiver.stop();
            audioReceiver = null;
        }

        abandonAudioFocus();
        Log.i(TAG, "媒体已停止");
    }

    private void hangup() {
        if (sipService != null) {
            sipService.hangup();
        }
        stopMedia();
        stopTimer();
        finish();
    }

    private void toggleMute() {
        muted = !muted;
        btnMute.setText(muted ? "取消静音" : "静音");
    }

    private void startTimer() {
        callStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - callStartTime;
                int seconds = (int) (elapsed / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;
                tvTimer.setText(String.format("%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
    }

    private void requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .build();
        audioManager.requestAudioFocus(audioFocusRequest);

        // 切换到听筒/扬声器模式
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
    }

    @Override
    protected void onDestroy() {
        stopMedia();
        stopTimer();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
