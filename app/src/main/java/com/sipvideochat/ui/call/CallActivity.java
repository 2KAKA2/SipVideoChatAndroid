package com.sipvideochat.ui.call;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.media.AudioCapture;
import com.sipvideochat.media.RTPAudioReceiver;
import com.sipvideochat.media.RTPAudioSender;
import com.sipvideochat.media.RTPVideoReceiver;
import com.sipvideochat.media.RTPVideoSender;
import com.sipvideochat.media.VideoCapture;
import com.sipvideochat.model.CallLogRecord;
import com.sipvideochat.model.CallLogRepository;
import com.sipvideochat.sip.SipClient;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;
import com.sipvideochat.webrtc.WebRtcVideoSession;
import com.sipvideochat.util.DiagnosticLog;

import org.webrtc.SurfaceViewRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallActivity extends AppCompatActivity {
    private static final String TAG = "CallActivity";
    private static final int LEGACY_VIDEO_WIDTH = 160;
    private static final int LEGACY_VIDEO_HEIGHT = 120;
    private static final int LEGACY_VIDEO_FRAME_RATE = 8;
    private static final int LEGACY_VIDEO_BITRATE = 120_000;

    public static SipClient.IncomingInvite pendingInvite;

    private SipService sipService;
    private boolean serviceBound;

    private String remoteUser;
    private boolean isOutgoing;
    private boolean videoEnabled;
    private String remoteSdp;

    private String remoteIp;
    private int remoteAudioPort;
    private int remoteVideoPort;

    private AudioCapture audioCapture;
    private RTPAudioSender audioSender;
    private RTPAudioReceiver audioReceiver;
    private VideoCapture videoCapture;
    private RTPVideoSender videoSender;
    private RTPVideoReceiver videoReceiver;
    private WebRtcVideoSession webRtcVideoSession;

    private TextView tvRemoteUser;
    private TextView tvCallType;
    private TextView tvCallStatus;
    private TextView tvTimer;
    private MaterialButton btnMute;
    private MaterialButton btnHangup;
    private MaterialButton btnAccept;
    private MaterialButton btnToggleVideo;
    private SurfaceViewRenderer svRemoteVideo;
    private SurfaceView svLegacyRemoteVideo;
    private SurfaceViewRenderer pvLocalVideo;
    private PreviewView pvLegacyLocalVideo;

    private boolean connected;
    private boolean legacyVideoMode;
    private boolean legacyRemoteSurfaceReady;
    private boolean legacyVideoReceiveFailed;
    private boolean legacyVideoSendFailed;
    private boolean mediaStarted;
    private boolean videoSending;
    private boolean muted;
    private boolean permissionsGranted;
    private boolean outgoingVideoInviteStarted;
    private boolean remoteAnswerApplied;
    private boolean callRecordSaved;
    private long callStartTime;

    private Handler timerHandler;
    private Runnable timerRunnable;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SipService.SipBinder binder = (SipService.SipBinder) service;
            sipService = binder.getService();
            serviceBound = true;
            sipService.setEventListener(sipEventListener);
            maybeStartOutgoingVideoCall();
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
            runOnUiThread(() -> tvCallStatus.setText("Remote device is ringing..."));
        }

        @Override
        public void onCallConnected(String sdp) {
            runOnUiThread(() -> onConnected(sdp));
        }

        @Override
        public void onCallEnded() {
            runOnUiThread(CallActivity.this::onEnded);
        }

        @Override
        public void onCallFailed(String reason) {
            runOnUiThread(() -> onFailed(reason));
        }
    };

    private final SurfaceHolder.Callback legacyRemoteSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            legacyRemoteSurfaceReady = holder.getSurface() != null && holder.getSurface().isValid();
            if (videoReceiver != null) {
                videoReceiver.setOutputSurface(holder.getSurface());
            }
            if (legacyVideoMode) {
                runOnUiThread(CallActivity.this::startLegacyVideoIfReady);
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            legacyRemoteSurfaceReady = holder.getSurface() != null && holder.getSurface().isValid();
            if (videoReceiver != null && legacyRemoteSurfaceReady) {
                videoReceiver.setOutputSurface(holder.getSurface());
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            legacyRemoteSurfaceReady = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        remoteUser = getIntent().getStringExtra("remoteUser");
        isOutgoing = getIntent().getBooleanExtra("isOutgoing", true);
        videoEnabled = getIntent().getBooleanExtra("videoEnabled", false);
        remoteSdp = getIntent().getStringExtra("remoteSdp");
        Log.i(TAG, "onCreate remoteUser=" + remoteUser
                + ", outgoing=" + isOutgoing
                + ", video=" + videoEnabled
                + ", remoteSdpLength=" + (remoteSdp == null ? 0 : remoteSdp.length()));
        DiagnosticLog.i(TAG, "onCreate remoteUser=" + remoteUser
                + ", outgoing=" + isOutgoing
                + ", video=" + videoEnabled
                + ", remoteSdpLength=" + (remoteSdp == null ? 0 : remoteSdp.length()));

        bindViews();
        setupUi();
        timerHandler = new Handler(Looper.getMainLooper());
        requestRequiredPermissions();

        Intent serviceIntent = new Intent(this, SipService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void bindViews() {
        tvRemoteUser = findViewById(R.id.tvRemoteUser);
        tvCallType = findViewById(R.id.tvCallType);
        tvCallStatus = findViewById(R.id.tvCallStatus);
        tvTimer = findViewById(R.id.tvTimer);
        btnMute = findViewById(R.id.btnMute);
        btnHangup = findViewById(R.id.btnHangup);
        btnAccept = findViewById(R.id.btnAccept);
        btnToggleVideo = findViewById(R.id.btnToggleVideo);
        svRemoteVideo = findViewById(R.id.svRemoteVideo);
        svLegacyRemoteVideo = findViewById(R.id.svLegacyRemoteVideo);
        pvLocalVideo = findViewById(R.id.pvLocalVideo);
        pvLegacyLocalVideo = findViewById(R.id.pvLegacyLocalVideo);
        svLegacyRemoteVideo.getHolder().addCallback(legacyRemoteSurfaceCallback);
        legacyRemoteSurfaceReady = svLegacyRemoteVideo.getHolder().getSurface() != null
                && svLegacyRemoteVideo.getHolder().getSurface().isValid();
    }

    private void setupUi() {
        tvRemoteUser.setText(remoteUser);
        tvCallType.setText(videoEnabled ? "Video Call" : "Voice Call");
        tvCallStatus.setText(isOutgoing ? "Calling..." : "Incoming call...");

        btnAccept.setVisibility(isOutgoing ? View.GONE : View.VISIBLE);
        btnMute.setEnabled(false);
        btnToggleVideo.setVisibility(videoEnabled ? View.VISIBLE : View.GONE);
        btnToggleVideo.setEnabled(false);
        btnToggleVideo.setText("Stop Video");

        svRemoteVideo.setVisibility(videoEnabled ? View.VISIBLE : View.GONE);
        pvLocalVideo.setVisibility(videoEnabled ? View.VISIBLE : View.GONE);
        pvLegacyLocalVideo.setVisibility(View.GONE);

        videoSending = videoEnabled;
        legacyVideoMode = videoEnabled && isLegacyRtpVideoSdp(remoteSdp);
        applyLegacyVideoMode(legacyVideoMode);

        if (!videoEnabled && remoteSdp != null && !remoteSdp.isEmpty()) {
            parseRemoteMediaSdp(remoteSdp);
        }

        btnHangup.setOnClickListener(v -> hangup());
        btnMute.setOnClickListener(v -> toggleMute());
        btnAccept.setOnClickListener(v -> acceptCall());
        btnToggleVideo.setOnClickListener(v -> toggleVideoSending());
    }

    private boolean isLegacyRtpVideoSdp(String sdp) {
        if (sdp == null || sdp.trim().isEmpty()) {
            return false;
        }
        String normalized = sdp.toLowerCase(Locale.US);
        return normalized.contains("m=video ")
                && normalized.contains("rtp/avp")
                && !normalized.contains("udp/tls/rtp/savpf")
                && !normalized.contains("a=ice-ufrag:")
                && !normalized.contains("a=fingerprint:");
    }

    private void applyLegacyVideoMode(boolean legacy) {
        legacyVideoMode = legacy;
        if (!videoEnabled) {
            svRemoteVideo.setVisibility(View.GONE);
            svLegacyRemoteVideo.setVisibility(View.GONE);
            pvLocalVideo.setVisibility(View.GONE);
            pvLegacyLocalVideo.setVisibility(View.GONE);
            return;
        }
        svRemoteVideo.setVisibility(legacy ? View.GONE : View.VISIBLE);
        svLegacyRemoteVideo.setVisibility(legacy ? View.VISIBLE : View.GONE);
        pvLocalVideo.setVisibility(legacy ? View.GONE : View.VISIBLE);
        pvLegacyLocalVideo.setVisibility(legacy ? View.VISIBLE : View.GONE);
    }

    private void requestRequiredPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        addPermissionIfMissing(missingPermissions, Manifest.permission.RECORD_AUDIO);
        if (videoEnabled) {
            addPermissionIfMissing(missingPermissions, Manifest.permission.CAMERA);
        }

        if (missingPermissions.isEmpty()) {
            permissionsGranted = true;
            maybeStartOutgoingVideoCall();
            return;
        }
        permissionLauncher.launch(missingPermissions.toArray(new String[0]));
    }

    private void addPermissionIfMissing(List<String> missingPermissions, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(permission);
        }
    }

    private void onPermissionResult(Map<String, Boolean> grantResults) {
        boolean allGranted = true;
        for (Boolean granted : grantResults.values()) {
            if (!Boolean.TRUE.equals(granted)) {
                allGranted = false;
                break;
            }
        }

        permissionsGranted = allGranted;
        if (!allGranted) {
            Toast.makeText(this, "Call permissions were not granted.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        maybeStartOutgoingVideoCall();
    }

    private void maybeStartOutgoingVideoCall() {
        if (!videoEnabled || !isOutgoing || !permissionsGranted || sipService == null || outgoingVideoInviteStarted) {
            return;
        }
        outgoingVideoInviteStarted = true;
        tvCallStatus.setText("Preparing video...");

        Log.i(TAG, "acceptCall remoteSdpLength=" + (remoteSdp == null ? 0 : remoteSdp.length())
                + ", hasAudio=" + (remoteSdp != null && remoteSdp.contains("m=audio"))
                + ", hasVideo=" + (remoteSdp != null && remoteSdp.contains("m=video")));
        DiagnosticLog.i(TAG, "prepare outgoing video offer, remoteSdpLength=" + (remoteSdp == null ? 0 : remoteSdp.length())
                + ", hasAudio=" + (remoteSdp != null && remoteSdp.contains("m=audio"))
                + ", hasVideo=" + (remoteSdp != null && remoteSdp.contains("m=video")));
        try {
            ensureWebRtcSession();
            requestAudioFocus();
            webRtcVideoSession.createOffer(new WebRtcVideoSession.SdpCallback() {
                @Override
                public void onSuccess(String sdp) {
                    runOnUiThread(() -> {
                        mediaStarted = true;
                        videoSending = true;
                        btnToggleVideo.setText("Stop Video");
                        DiagnosticLog.i(TAG, "outgoing video offer ready, sdpLength=" + (sdp == null ? 0 : sdp.length()));
                        sipService.makeCall(remoteUser, true, sdp);
                        tvCallStatus.setText("Calling...");
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        outgoingVideoInviteStarted = false;
                        DiagnosticLog.e(TAG, "outgoing video offer failed: " + error);
                        onFailed(error);
                    });
                }
            });
        } catch (Exception e) {
            outgoingVideoInviteStarted = false;
            Log.e(TAG, "Failed to prepare outgoing WebRTC call", e);
            DiagnosticLog.e(TAG, "failed to prepare outgoing WebRTC call", e);
            onFailed(e.getMessage());
        }
    }

    private void acceptCall() {
        legacyVideoReceiveFailed = false;
        legacyVideoSendFailed = false;
        DiagnosticLog.i(TAG, "acceptCall clicked, videoEnabled=" + videoEnabled
                + ", pendingInvite=" + (pendingInvite != null)
                + ", remoteSdpLength=" + (remoteSdp == null ? 0 : remoteSdp.length()));
        if (!permissionsGranted) {
            requestRequiredPermissions();
            return;
        }
        if (sipService == null || pendingInvite == null) {
            onFailed("SIP unavailable");
            return;
        }

        btnAccept.setVisibility(View.GONE);
        tvCallStatus.setText("Answering...");

        if (!videoEnabled) {
            if (remoteSdp != null && !remoteSdp.isEmpty()) {
                parseRemoteMediaSdp(remoteSdp);
            }
            DiagnosticLog.i(TAG, "answering audio call directly");
            sipService.answerCall(pendingInvite, false);
            return;
        }

        if (remoteSdp == null || remoteSdp.trim().isEmpty()) {
            onFailed("Remote video SDP is missing");
            return;
        }

        if (isLegacyRtpVideoSdp(remoteSdp)) {
            applyLegacyVideoMode(true);
            parseRemoteMediaSdp(remoteSdp);
            DiagnosticLog.i(TAG, "answering legacy RTP video call");
            sipService.answerCall(pendingInvite, true);
            return;
        }

        try {
            applyLegacyVideoMode(false);
            ensureWebRtcSession();
            requestAudioFocus();
            webRtcVideoSession.createAnswer(remoteSdp, new WebRtcVideoSession.SdpCallback() {
                @Override
                public void onSuccess(String sdp) {
                    runOnUiThread(() -> {
                        mediaStarted = true;
                        videoSending = true;
                        btnToggleVideo.setText("Stop Video");
                        DiagnosticLog.i(TAG, "incoming video answer ready, sdpLength=" + (sdp == null ? 0 : sdp.length()));
                        sipService.answerCall(pendingInvite, true, sdp);
                    });
                }

                @Override
                public void onError(String error) {
                    DiagnosticLog.e(TAG, "incoming video answer failed: " + error);
                    runOnUiThread(() -> onFailed(error));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare incoming WebRTC answer", e);
            DiagnosticLog.e(TAG, "failed to prepare incoming WebRTC answer", e);
            onFailed(e.getMessage());
        }
    }

    private void onConnected(String sdp) {
        legacyVideoReceiveFailed = false;
        legacyVideoSendFailed = false;
        DiagnosticLog.i(TAG, "onConnected videoEnabled=" + videoEnabled
                + ", connectedBefore=" + connected
                + ", remoteSdpLength=" + (sdp == null ? 0 : sdp.length()));
        if (!videoEnabled) {
            if (connected) {
                return;
            }
            if (sdp != null && !sdp.isEmpty()) {
                remoteSdp = sdp;
                parseRemoteMediaSdp(sdp);
            }
            connected = true;
            tvCallStatus.setText("In call");
            tvTimer.setVisibility(View.VISIBLE);
            btnMute.setEnabled(true);
            startTimer();
            startAudioIfReady();
            pendingInvite = null;
            return;
        }

        if (sdp != null && !sdp.isEmpty()) {
            remoteSdp = sdp;
            if (isLegacyRtpVideoSdp(sdp)) {
                applyLegacyVideoMode(true);
                parseRemoteMediaSdp(sdp);
                if (webRtcVideoSession != null) {
                    webRtcVideoSession.release();
                    webRtcVideoSession = null;
                }
                mediaStarted = false;
                remoteAnswerApplied = false;
            } else if (webRtcVideoSession != null && isOutgoing && !remoteAnswerApplied) {
                applyLegacyVideoMode(false);
                webRtcVideoSession.setRemoteAnswer(sdp);
                remoteAnswerApplied = true;
            }
        }

        if (connected) {
            return;
        }

        connected = true;
        tvCallStatus.setText("In call");
        tvTimer.setVisibility(View.VISIBLE);
        btnMute.setEnabled(true);
        btnToggleVideo.setEnabled(true);
        startTimer();
        if (legacyVideoMode) {
            startLegacyVideoIfReady();
        } else if (webRtcVideoSession != null) {
            webRtcVideoSession.setMuted(muted);
            webRtcVideoSession.setVideoEnabled(videoSending);
        }
        pendingInvite = null;
    }

    private void startAudioIfReady() {
        DiagnosticLog.i(TAG, "startAudioIfReady connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", remoteIp=" + remoteIp
                + ", remoteAudioPort=" + remoteAudioPort);
        if (!permissionsGranted || !connected || mediaStarted || sipService == null) {
            return;
        }

        ClientConfig config = sipService.getConfig();
        if (config == null) {
            onFailed("SIP config is unavailable");
            return;
        }
        if (remoteIp == null || remoteIp.isEmpty() || remoteAudioPort <= 0) {
            onFailed("Remote media address is unavailable");
            return;
        }

        try {
            requestAudioFocus();
            if (audioReceiver == null) {
                audioReceiver = new RTPAudioReceiver(config.getLocalAudioPort());
                audioReceiver.start();
                DiagnosticLog.i(TAG, "audio receiver started localPort=" + config.getLocalAudioPort());
            }
            if (audioSender == null) {
                audioSender = new RTPAudioSender(config.getLocalAudioPort() + 1);
                audioSender.setRemote(remoteIp, remoteAudioPort);
                DiagnosticLog.i(TAG, "audio sender remote=" + remoteIp + ":" + remoteAudioPort
                        + ", localPort=" + (config.getLocalAudioPort() + 1));
            }
            if (audioCapture == null) {
                audioCapture = new AudioCapture();
                audioCapture.start((data, length) -> {
                    if (!muted && audioSender != null) {
                        audioSender.sendAudio(data, length);
                    }
                });
                DiagnosticLog.i(TAG, "audio capture started");
            }
            mediaStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio media", e);
            DiagnosticLog.e(TAG, "failed to start audio media", e);
            onFailed(e.getMessage());
        }
    }

    private void startLegacyVideoIfReady() {
        DiagnosticLog.i(TAG, "startLegacyVideoIfReady connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", surfaceReady=" + legacyRemoteSurfaceReady
                + ", remoteIp=" + remoteIp
                + ", remoteAudioPort=" + remoteAudioPort
                + ", remoteVideoPort=" + remoteVideoPort
                + ", receiveFailed=" + legacyVideoReceiveFailed
                + ", sendFailed=" + legacyVideoSendFailed);
        if (!permissionsGranted || !connected || mediaStarted || sipService == null) {
            return;
        }
        if (!legacyRemoteSurfaceReady) {
            DiagnosticLog.i(TAG, "wait for legacy remote surface before starting legacy video");
            return;
        }

        ClientConfig config = sipService.getConfig();
        if (config == null) {
            onFailed("SIP config is unavailable");
            return;
        }
        if (remoteIp == null || remoteIp.isEmpty() || remoteAudioPort <= 0 || remoteVideoPort <= 0) {
            onFailed("Remote video media address is unavailable");
            return;
        }

        try {
            applyLegacyVideoMode(true);
            requestAudioFocus();
            if (audioReceiver == null) {
                audioReceiver = new RTPAudioReceiver(config.getLocalAudioPort());
                audioReceiver.start();
                DiagnosticLog.i(TAG, "legacy audio receiver started localPort=" + config.getLocalAudioPort());
            }
            if (audioSender == null) {
                audioSender = new RTPAudioSender(config.getLocalAudioPort() + 1);
                audioSender.setRemote(remoteIp, remoteAudioPort);
                DiagnosticLog.i(TAG, "legacy audio sender remote=" + remoteIp + ":" + remoteAudioPort
                        + ", localPort=" + (config.getLocalAudioPort() + 1));
            }
            if (audioCapture == null) {
                audioCapture = new AudioCapture();
                audioCapture.start((data, length) -> {
                    if (!muted && audioSender != null) {
                        audioSender.sendAudio(data, length);
                    }
                });
                DiagnosticLog.i(TAG, "legacy audio capture started");
            }
            if (videoReceiver == null) {
                videoReceiver = new RTPVideoReceiver(config.getLocalVideoPort());
                videoReceiver.setListener(error -> runOnUiThread(() -> handleLegacyVideoReceiveError(error)));
                videoReceiver.setOutputSurface(svLegacyRemoteVideo.getHolder().getSurface());
                videoReceiver.start();
                DiagnosticLog.i(TAG, "legacy video receiver started localPort=" + config.getLocalVideoPort());
            }
            if (videoSender == null) {
                videoSender = new RTPVideoSender(
                        remoteIp,
                        remoteVideoPort,
                        getLegacyVideoWidth(config),
                        getLegacyVideoHeight(config),
                        getLegacyVideoFrameRate(config));
                videoSender.setBitrate(getLegacyVideoBitrate(config));
                videoSender.start();
                DiagnosticLog.i(TAG, "legacy video sender remote=" + remoteIp + ":" + remoteVideoPort
                        + ", width=" + getLegacyVideoWidth(config)
                        + ", height=" + getLegacyVideoHeight(config)
                        + ", fps=" + getLegacyVideoFrameRate(config)
                        + ", bitrate=" + getLegacyVideoBitrate(config));
            }
            if (videoCapture == null) {
                videoCapture = new VideoCapture(
                        getLegacyVideoWidth(config),
                        getLegacyVideoHeight(config),
                        getLegacyVideoFrameRate(config));
                videoCapture.addListener(new VideoCapture.VideoFrameListener() {
                    @Override
                    public void onFrame(byte[] yuvData, int width, int height) {
                        if (videoSending && videoSender != null) {
                            videoSender.pushFrame(yuvData, width, height);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> handleLegacyVideoSendError(error));
                    }
                });
            }
            if (!videoCapture.isRunning()) {
                videoCapture.start(this, this, pvLegacyLocalVideo);
                DiagnosticLog.i(TAG, "legacy video capture started width=" + getLegacyVideoWidth(config)
                        + ", height=" + getLegacyVideoHeight(config)
                        + ", fps=" + getLegacyVideoFrameRate(config));
            }
            mediaStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start legacy video media", e);
            DiagnosticLog.e(TAG, "failed to start legacy video media", e);
            onFailed(e.getMessage());
        }
    }

    private void handleLegacyVideoReceiveError(String error) {
        String safeError = (error == null || error.trim().isEmpty()) ? "unknown" : error;
        DiagnosticLog.e(TAG, "legacy video receive error: " + safeError);
        legacyVideoReceiveFailed = true;
        if (videoReceiver != null) {
            videoReceiver.stopReceiving();
            videoReceiver = null;
        }
        if (audioCapture == null && audioSender == null && audioReceiver == null) {
            onFailed("Legacy video receive failed: " + safeError);
            return;
        }
        if (!isFinishing() && !isDestroyed()) {
            tvCallStatus.setText("é–«æ°³ç˜½æ¶“? (video receive degraded)");
        }
    }

    private void handleLegacyVideoSendError(String error) {
        String safeError = (error == null || error.trim().isEmpty()) ? "unknown" : error;
        DiagnosticLog.e(TAG, "legacy video send error: " + safeError);
        legacyVideoSendFailed = true;
        if (videoCapture != null) {
            videoCapture.stop();
            videoCapture = null;
        }
        if (videoSender != null) {
            videoSender.stopSending();
            videoSender = null;
        }
        videoSending = false;
        if (btnToggleVideo != null) {
            btnToggleVideo.setEnabled(false);
        }
        if (audioCapture == null && audioSender == null && audioReceiver == null) {
            onFailed("Legacy video send failed: " + safeError);
            return;
        }
        if (!isFinishing() && !isDestroyed()) {
            tvCallStatus.setText("é–«æ°³ç˜½æ¶“? (video send degraded)");
        }
    }

    private void ensureWebRtcSession() throws Exception {
        if (webRtcVideoSession != null) {
            return;
        }
        if (isFinishing() || isDestroyed()) {
            throw new IllegalStateException("CallActivity is finishing");
        }
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) {
            throw new IllegalStateException("SIP config unavailable");
        }
        webRtcVideoSession = new WebRtcVideoSession(
                this,
                config.getVideoWidth(),
                config.getVideoHeight(),
                config.getVideoFrameRate(),
                config.getVideoBitrate());
        applyLegacyVideoMode(false);
        webRtcVideoSession.initialize(pvLocalVideo, svRemoteVideo, error ->
                runOnUiThread(() -> onFailed(error)));
    }

    private void onEnded() {
        DiagnosticLog.i(TAG, "onEnded connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode);
        persistCallRecord("completed");
        connected = false;
        tvCallStatus.setText("Call ended");
        stopTimer();
        stopMedia("call ended");
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200L);
    }

    private void onFailed(String reason) {
        persistCallRecord("failed");
        DiagnosticLog.e(TAG, "onFailed reason=" + (reason == null ? "unknown" : reason)
                + ", connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode);
        tvCallStatus.setText("Call failed: " + (reason == null ? "Unknown error" : reason));
        btnHangup.setText("Close");
        stopMedia("call failed: " + (reason == null ? "unknown" : reason));
    }

    private void parseRemoteMediaSdp(String sdp) {
        Matcher ipMatcher = Pattern.compile("c=IN IP4 ([\\d.]+)").matcher(sdp);
        if (ipMatcher.find()) {
            remoteIp = ipMatcher.group(1);
        }

        Matcher audioMatcher = Pattern.compile("m=audio (\\d+)").matcher(sdp);
        if (audioMatcher.find()) {
            remoteAudioPort = Integer.parseInt(audioMatcher.group(1));
        }

        Matcher videoMatcher = Pattern.compile("m=video (\\d+)").matcher(sdp);
        if (videoMatcher.find()) {
            remoteVideoPort = Integer.parseInt(videoMatcher.group(1));
        }

        Log.i(TAG, "Remote media ip=" + remoteIp
                + ", audioPort=" + remoteAudioPort
                + ", videoPort=" + remoteVideoPort);
    }

    private int getLegacyVideoWidth(ClientConfig config) {
        // Keep the legacy PC interop path on a fixed profile instead of inheriting stale
        // persisted app settings from earlier experiments.
        return LEGACY_VIDEO_WIDTH;
    }

    private int getLegacyVideoHeight(ClientConfig config) {
        return LEGACY_VIDEO_HEIGHT;
    }

    private int getLegacyVideoFrameRate(ClientConfig config) {
        return LEGACY_VIDEO_FRAME_RATE;
    }

    private int getLegacyVideoBitrate(ClientConfig config) {
        return LEGACY_VIDEO_BITRATE;
    }

    private void stopMedia() {
        stopMedia("unspecified");
    }

    private void stopMedia(String reason) {
        DiagnosticLog.i(TAG, "stopMedia reason=" + reason
                + ", connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode
                + ", audioCapture=" + (audioCapture != null)
                + ", audioSender=" + (audioSender != null)
                + ", audioReceiver=" + (audioReceiver != null)
                + ", videoCapture=" + (videoCapture != null)
                + ", videoSender=" + (videoSender != null)
                + ", videoReceiver=" + (videoReceiver != null)
                + ", webRtcSession=" + (webRtcVideoSession != null));
        connected = false;
        mediaStarted = false;
        remoteAnswerApplied = false;
        remoteVideoPort = 0;
        legacyVideoReceiveFailed = false;
        legacyVideoSendFailed = false;

        if (webRtcVideoSession != null) {
            webRtcVideoSession.release();
            webRtcVideoSession = null;
        }

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
        if (videoCapture != null) {
            videoCapture.stop();
            videoCapture = null;
        }
        if (videoSender != null) {
            videoSender.stopSending();
            videoSender = null;
        }
        if (videoReceiver != null) {
            videoReceiver.stopReceiving();
            videoReceiver = null;
        }

        if (videoEnabled) {
            videoSending = true;
        }
        applyLegacyVideoMode(false);
        abandonAudioFocus();
    }

    private void hangup() {
        DiagnosticLog.i(TAG, "hangup clicked connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode);
        if (sipService != null) {
            sipService.hangup();
        }
        persistCallRecord(callStartTime > 0L ? "hung up" : "cancelled");
        stopTimer();
        stopMedia("local hangup");
        finish();
    }

    private void persistCallRecord(String status) {
        if (callRecordSaved) {
            return;
        }
        callRecordSaved = true;

        CallLogRecord record = new CallLogRecord();
        record.setRemoteUser(remoteUser);
        record.setOutgoing(isOutgoing);
        record.setVideo(videoEnabled);
        long startTime = callStartTime > 0L ? callStartTime : System.currentTimeMillis();
        record.setStartTime(startTime);
        long durationSeconds = callStartTime > 0L
                ? Math.max(0L, (System.currentTimeMillis() - callStartTime) / 1000L)
                : 0L;
        record.setDurationSeconds(durationSeconds);
        record.setStatus(status == null ? "unknown" : status);
        new CallLogRepository(this).addCallLog(record);
    }

    private void toggleMute() {
        muted = !muted;
        btnMute.setText(muted ? "Unmute" : "Mute");
        if (videoEnabled && webRtcVideoSession != null) {
            webRtcVideoSession.setMuted(muted);
        }
    }

    private void toggleVideoSending() {
        if (!videoEnabled) {
            return;
        }
        videoSending = !videoSending;
        if (legacyVideoMode) {
            if (!videoSending && videoCapture != null && videoCapture.isRunning()) {
                videoCapture.stop();
            } else if (videoSending && videoCapture != null && !videoCapture.isRunning()) {
                videoCapture.start(this, this, pvLegacyLocalVideo);
            }
        } else if (webRtcVideoSession != null) {
            webRtcVideoSession.setVideoEnabled(videoSending);
        }
        btnToggleVideo.setText(videoSending ? "Stop Video" : "Start Video");
    }

    private void startTimer() {
        if (timerRunnable != null) {
            return;
        }
        callStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedSeconds = (System.currentTimeMillis() - callStartTime) / 1000L;
                long minutes = elapsedSeconds / 60;
                long seconds = elapsedSeconds % 60;
                tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000L);
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
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }
        if (audioManager == null) {
            return;
        }
        if (audioFocusRequest == null) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .build();
        }
        audioManager.requestAudioFocus(audioFocusRequest);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        DiagnosticLog.i(TAG, "onPause finishing=" + isFinishing()
                + ", connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode);
        super.onPause();
    }

    @Override
    protected void onStop() {
        DiagnosticLog.i(TAG, "onStop finishing=" + isFinishing()
                + ", connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        DiagnosticLog.i(TAG, "onDestroy finishing=" + isFinishing()
                + ", connected=" + connected
                + ", mediaStarted=" + mediaStarted
                + ", legacyMode=" + legacyVideoMode);
        stopMedia("activity destroy");
        stopTimer();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
