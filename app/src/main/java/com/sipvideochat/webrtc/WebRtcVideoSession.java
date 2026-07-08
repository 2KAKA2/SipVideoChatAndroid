package com.sipvideochat.webrtc;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebRtcVideoSession {
    private static final String TAG = "WebRtcVideoSession";
    public interface SdpCallback {
        void onSuccess(String sdp);

        void onError(String error);
    }

    public interface ErrorCallback {
        void onError(String error);
    }

    private static final String VIDEO_TRACK_ID = "VIDEO_TRACK";
    private static final String AUDIO_TRACK_ID = "AUDIO_TRACK";
    private static final String LOCAL_STREAM_ID = "LOCAL_STREAM";
    private static final long SDP_TIMEOUT_MS = 3000L;
    private static final AtomicBoolean FACTORY_INITIALIZED = new AtomicBoolean(false);

    private final Context appContext;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoFrameRate;
    private final int videoBitrate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EglBase eglBase = EglBase.create();

    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;
    private PeerConnectionFactory peerConnectionFactory;
    private JavaAudioDeviceModule audioDeviceModule;
    private PeerConnection peerConnection;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoTrack remoteVideoTrack;
    private RtpSender localVideoSender;
    private RtpSender localAudioSender;
    private ErrorCallback errorCallback;
    private SdpCallback pendingSdpCallback;
    private Runnable pendingSdpTimeout;
    private boolean initialized;
    private boolean released;

    public WebRtcVideoSession(Context context, int videoWidth, int videoHeight, int videoFrameRate, int videoBitrate) {
        this.appContext = context.getApplicationContext();
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoFrameRate = videoFrameRate;
        this.videoBitrate = videoBitrate;
    }

    public void initialize(SurfaceViewRenderer localRenderer,
                           SurfaceViewRenderer remoteRenderer,
                           ErrorCallback errorCallback) throws Exception {
        if (released) {
            throw new IllegalStateException("WebRtcVideoSession already released");
        }
        if (initialized) {
            return;
        }

        this.localRenderer = localRenderer;
        this.remoteRenderer = remoteRenderer;
        this.errorCallback = errorCallback;

        Log.i(TAG, "initialize start");
        ensureFactoryInitialized();
        initializeRenderers();
        createPeerConnectionFactory();
        createLocalMedia();
        createPeerConnection();
        initialized = true;
        Log.i(TAG, "initialize complete");
    }

    public void createOffer(SdpCallback callback) {
        if (!isPeerConnectionReady()) {
            callback.onError("PeerConnection unavailable");
            return;
        }
        Log.i(TAG, "createOffer");
        pendingSdpCallback = callback;
        peerConnection.createOffer(new SessionSdpObserver(SessionDescription.Type.OFFER), createSdpConstraints());
    }

    public void createAnswer(String remoteOfferSdp, SdpCallback callback) {
        if (!isPeerConnectionReady()) {
            callback.onError("PeerConnection unavailable");
            return;
        }
        String normalizedOfferSdp = sanitizeRemoteSdp(remoteOfferSdp, true);
        if (normalizedOfferSdp == null) {
            callback.onError("Remote offer SDP invalid");
            return;
        }
        Log.i(TAG, "createAnswer setRemoteDescription(offer), remoteLength=" + normalizedOfferSdp.length());
        pendingSdpCallback = callback;
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.i(TAG, "setRemoteDescription(offer) success, createAnswer");
                peerConnection.createAnswer(new SessionSdpObserver(SessionDescription.Type.ANSWER), createSdpConstraints());
            }

            @Override
            public void onSetFailure(String error) {
                dispatchError("Set remote offer failed: " + error + " (len=" + normalizedOfferSdp.length() + ")");
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, normalizedOfferSdp));
    }

    public void setRemoteAnswer(String remoteAnswerSdp) {
        if (!isPeerConnectionReady()) {
            return;
        }
        String normalizedAnswerSdp = sanitizeRemoteSdp(remoteAnswerSdp, false);
        if (normalizedAnswerSdp == null) {
            dispatchError("Remote answer SDP invalid");
            return;
        }
        Log.i(TAG, "setRemoteDescription(answer), remoteLength=" + normalizedAnswerSdp.length());
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.i(TAG, "setRemoteDescription(answer) success");
            }

            @Override
            public void onSetFailure(String error) {
                dispatchError("Set remote answer failed: " + error + " (len=" + normalizedAnswerSdp.length() + ")");
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, normalizedAnswerSdp));
    }

    public void setMuted(boolean muted) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
        }
    }

    public void setVideoEnabled(boolean enabled) {
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        }
        postToMain(() -> {
            if (localRenderer != null) {
                localRenderer.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        initialized = false;
        cancelPendingSdp();

        detachRemoteVideoTrack();
        detachLocalVideoTrack();

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (Exception ignored) {
            }
            try {
                videoCapturer.dispose();
            } catch (Exception ignored) {
            }
            videoCapturer = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
        }

        localVideoSender = null;
        localAudioSender = null;

        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (audioDeviceModule != null) {
            audioDeviceModule.release();
            audioDeviceModule = null;
        }
        if (localRenderer != null) {
            localRenderer.release();
            localRenderer = null;
        }
        if (remoteRenderer != null) {
            remoteRenderer.release();
            remoteRenderer = null;
        }
        eglBase.release();
    }

    private boolean isPeerConnectionReady() {
        return !released && peerConnection != null;
    }

    private void ensureFactoryInitialized() {
        if (FACTORY_INITIALIZED.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .createInitializationOptions());
        }
    }

    private void initializeRenderers() {
        if (localRenderer == null || remoteRenderer == null) {
            throw new IllegalStateException("Renderer unavailable");
        }

        localRenderer.init(eglBase.getEglBaseContext(), null);
        localRenderer.setMirror(true);
        localRenderer.setEnableHardwareScaler(true);
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        remoteRenderer.setMirror(false);
        remoteRenderer.setEnableHardwareScaler(true);
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
    }

    private void createPeerConnectionFactory() {
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .createAudioDeviceModule();

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(new SoftwareVideoEncoderFactory())
                .setVideoDecoderFactory(new SoftwareVideoDecoderFactory())
                .createPeerConnectionFactory();

        if (peerConnectionFactory == null) {
            throw new IllegalStateException("Failed to create PeerConnectionFactory");
        }
        Log.i(TAG, "PeerConnectionFactory created with software codecs");
    }

    private void createLocalMedia() throws Exception {
        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);

        videoCapturer = createVideoCapturer();
        if (videoCapturer == null) {
            throw new IllegalStateException("No camera capturer available");
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCapture", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource.getCapturerObserver());
        videoSource.adaptOutputFormat(videoWidth, videoHeight, Math.max(1, videoFrameRate));
        videoCapturer.startCapture(videoWidth, videoHeight, Math.max(1, videoFrameRate));

        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.addSink(localRenderer);
        Log.i(TAG, "Local media ready " + videoWidth + "x" + videoHeight + "@" + Math.max(1, videoFrameRate));
    }

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(new ArrayList<>());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        configuration.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;

        peerConnection = peerConnectionFactory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                    deliverPendingLocalDescription();
                }
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
            }

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {
            }

            @Override
            public void onRenegotiationNeeded() {
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = receiver != null ? receiver.track() : null;
                if (track instanceof VideoTrack) {
                    attachRemoteVideoTrack((VideoTrack) track);
                }
            }

            @Override
            public void onRemoveTrack(RtpReceiver receiver) {
                MediaStreamTrack track = receiver != null ? receiver.track() : null;
                if (track == remoteVideoTrack) {
                    postToMain(WebRtcVideoSession.this::detachRemoteVideoTrackInternal);
                }
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                if (transceiver == null || transceiver.getReceiver() == null) {
                    return;
                }
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack) {
                    attachRemoteVideoTrack((VideoTrack) track);
                }
            }
        });

        if (peerConnection == null) {
            throw new IllegalStateException("Failed to create PeerConnection");
        }

        List<String> streamIds = Collections.singletonList(LOCAL_STREAM_ID);
        localAudioSender = peerConnection.addTrack(localAudioTrack, streamIds);
        localVideoSender = peerConnection.addTrack(localVideoTrack, streamIds);
        if (localAudioSender == null || localVideoSender == null) {
            throw new IllegalStateException("Failed to add local tracks");
        }

        configureVideoSender();
        Log.i(TAG, "PeerConnection ready, local tracks added");
    }

    private void configureVideoSender() {
        if (localVideoSender == null) {
            return;
        }

        RtpParameters parameters = localVideoSender.getParameters();
        if (parameters == null || parameters.encodings == null || parameters.encodings.isEmpty()) {
            return;
        }

        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;
        for (RtpParameters.Encoding encoding : parameters.encodings) {
            if (encoding == null) {
                continue;
            }
            encoding.active = true;
            encoding.maxBitrateBps = Math.max(80_000, Math.min(videoBitrate, 180_000));
            encoding.maxFramerate = Math.max(4, Math.min(videoFrameRate, 10));
        }
        try {
            localVideoSender.setParameters(parameters);
        } catch (Exception ignored) {
        }
    }

    private MediaConstraints createSdpConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        return constraints;
    }

    private void attachRemoteVideoTrack(VideoTrack videoTrack) {
        postToMain(() -> {
            if (released || remoteRenderer == null || videoTrack == null) {
                return;
            }
            if (remoteVideoTrack == videoTrack) {
                return;
            }
            if (remoteVideoTrack != null) {
                remoteVideoTrack.removeSink(remoteRenderer);
            }
            remoteVideoTrack = videoTrack;
            remoteVideoTrack.addSink(remoteRenderer);
        });
    }

    private void detachRemoteVideoTrack() {
        postToMain(this::detachRemoteVideoTrackInternal);
    }

    private void detachRemoteVideoTrackInternal() {
        if (remoteVideoTrack != null && remoteRenderer != null) {
            remoteVideoTrack.removeSink(remoteRenderer);
        }
        remoteVideoTrack = null;
    }

    private void detachLocalVideoTrack() {
        postToMain(() -> {
            if (localVideoTrack != null && localRenderer != null) {
                localVideoTrack.removeSink(localRenderer);
            }
        });
    }

    private void schedulePendingSdpTimeout() {
        cancelPendingSdp();
        pendingSdpTimeout = this::deliverPendingLocalDescription;
        mainHandler.postDelayed(pendingSdpTimeout, SDP_TIMEOUT_MS);
    }

    private void cancelPendingSdp() {
        if (pendingSdpTimeout != null) {
            mainHandler.removeCallbacks(pendingSdpTimeout);
            pendingSdpTimeout = null;
        }
    }

    private void deliverPendingLocalDescription() {
        cancelPendingSdp();
        if (pendingSdpCallback == null || peerConnection == null || peerConnection.getLocalDescription() == null) {
            return;
        }
        SdpCallback callback = pendingSdpCallback;
        pendingSdpCallback = null;
        String sdp = compactSessionDescriptionForSip(peerConnection.getLocalDescription().description);
        Log.i(TAG, "deliverPendingLocalDescription size=" + sdp.length());
        postToMain(() -> callback.onSuccess(sdp));
    }

    private void dispatchError(String error) {
        cancelPendingSdp();
        if (pendingSdpCallback != null) {
            SdpCallback callback = pendingSdpCallback;
            pendingSdpCallback = null;
            postToMain(() -> callback.onError(error));
            return;
        }
        if (errorCallback != null) {
            postToMain(() -> errorCallback.onError(error));
        }
    }

    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private String compactSessionDescriptionForSip(String sdp) {
        String normalized = normalizeSdp(sdp);
        if (normalized == null) {
            return "";
        }
        Log.i(TAG, "compactSessionDescriptionForSip raw=" + sdp.length() + ", normalized=" + normalized.length());
        return normalized;
    }

    private String sanitizeRemoteSdp(String sdp, boolean requireVideo) {
        String normalized = normalizeSdp(sdp);
        if (normalized == null || normalized.isEmpty()) {
            Log.w(TAG, "sanitizeRemoteSdp failed: empty");
            return null;
        }
        boolean hasVersion = normalized.startsWith("v=0\r\n");
        boolean hasOrigin = normalized.contains("\r\no=") || normalized.startsWith("o=");
        boolean hasSession = normalized.contains("\r\ns=") || normalized.startsWith("s=");
        boolean hasTiming = normalized.contains("\r\nt=") || normalized.startsWith("t=");
        boolean hasAudio = normalized.contains("\r\nm=audio ") || normalized.startsWith("m=audio ");
        boolean hasVideo = normalized.contains("\r\nm=video ") || normalized.startsWith("m=video ");
        if (!hasVersion || !hasOrigin || !hasSession || !hasTiming || !hasAudio || (requireVideo && !hasVideo)) {
            Log.w(TAG, "sanitizeRemoteSdp failed: hasVersion=" + hasVersion
                    + ", hasOrigin=" + hasOrigin
                    + ", hasSession=" + hasSession
                    + ", hasTiming=" + hasTiming
                    + ", hasAudio=" + hasAudio
                    + ", hasVideo=" + hasVideo
                    + ", requireVideo=" + requireVideo);
            return null;
        }
        return normalized;
    }

    private String normalizeSdp(String sdp) {
        if (sdp == null) {
            return null;
        }
        String normalized = sdp.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (!normalized.endsWith("\r\n")) {
            normalized += "\r\n";
        }
        return normalized;
    }

    private String retainHostUdpCandidates(String sdp) {
        String[] lines = sdp.split("\r\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("a=candidate:")) {
                kept.add(line);
                continue;
            }
            String lower = line.toLowerCase();
            if (!lower.contains(" udp ")) {
                continue;
            }
            if (!lower.contains(" typ host")) {
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length < 8) {
                continue;
            }
            if (!"1".equals(parts[1])) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\r\n", kept) + "\r\n";
    }

    private String limitCandidatesPerMedia(String sdp, int maxCandidatesPerMedia) {
        String[] lines = sdp.split("\r\n");
        List<String> kept = new ArrayList<>();
        int currentMediaIndex = -1;
        int currentMediaCandidates = 0;
        for (String line : lines) {
            if (line.startsWith("m=")) {
                currentMediaIndex++;
                currentMediaCandidates = 0;
                kept.add(line);
                continue;
            }
            if (line.startsWith("a=candidate:")) {
                if (currentMediaIndex < 0) {
                    continue;
                }
                if (currentMediaCandidates >= maxCandidatesPerMedia) {
                    continue;
                }
                currentMediaCandidates++;
                kept.add(line);
                continue;
            }
            kept.add(line);
        }
        return String.join("\r\n", kept) + "\r\n";
    }

    private VideoCapturer createVideoCapturer() {
        return createCapturer(new Camera1Enumerator(true));
    }

    private VideoCapturer createCapturer(CameraEnumerator enumerator) {
        for (String deviceName : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    Log.i(TAG, "Using camera: " + deviceName);
                    return capturer;
                }
            }
        }
        for (String deviceName : enumerator.getDeviceNames()) {
            VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
            if (capturer != null) {
                Log.i(TAG, "Using fallback camera: " + deviceName);
                return capturer;
            }
        }
        return null;
    }

    private final class SessionSdpObserver extends SimpleSdpObserver {
        private final SessionDescription.Type type;

        private SessionSdpObserver(SessionDescription.Type type) {
            this.type = type;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            if (!isPeerConnectionReady()) {
                dispatchError(type.canonicalForm() + " failed: PeerConnection unavailable");
                return;
            }
            Log.i(TAG, "create" + type.canonicalForm() + " success, setLocalDescription");
            peerConnection.setLocalDescription(new SimpleSdpObserver() {
                @Override
                public void onSetSuccess() {
                    Log.i(TAG, "setLocalDescription success for " + type.canonicalForm());
                    schedulePendingSdpTimeout();
                }

                @Override
                public void onSetFailure(String error) {
                    dispatchError("Set local description failed: " + error);
                }
            }, sessionDescription);
        }

        @Override
        public void onCreateFailure(String error) {
            dispatchError("Create " + type.canonicalForm() + " failed: " + error);
        }
    }
}
