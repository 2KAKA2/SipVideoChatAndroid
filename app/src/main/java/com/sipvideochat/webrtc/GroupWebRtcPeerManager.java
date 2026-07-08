package com.sipvideochat.webrtc;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared local-media, multi-peer WebRTC manager for small mesh rooms.
 */
public class GroupWebRtcPeerManager {
    private static final String TAG = "GroupWebRtcPeerMgr";
    private static final String VIDEO_TRACK_ID = "GROUP_VIDEO_TRACK";
    private static final String AUDIO_TRACK_ID = "GROUP_AUDIO_TRACK";
    private static final String LOCAL_STREAM_ID = "GROUP_LOCAL_STREAM";
    private static final AtomicBoolean FACTORY_INITIALIZED = new AtomicBoolean(false);

    public interface SignalingListener {
        void sendSdp(String peerId, SessionDescription sdp);
        void sendIceCandidate(String peerId, IceCandidate candidate);
    }

    public interface TrackListener {
        void onRemoteVideoTrack(String peerId, VideoTrack videoTrack);
        void onPeerConnectionStateChange(String peerId, PeerConnection.PeerConnectionState state);
        void onPeerRemoved(String peerId);
        void onError(String reason);
    }

    private static final class PeerState {
        final PeerConnection peerConnection;
        final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
        VideoTrack remoteVideoTrack;
        boolean remoteDescriptionSet;

        PeerState(PeerConnection peerConnection) {
            this.peerConnection = peerConnection;
        }
    }

    private final Context appContext;
    private final boolean videoEnabled;
    private final SignalingListener signalingListener;
    private final TrackListener trackListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EglBase eglBase = EglBase.create();
    private final Map<String, PeerState> peers = new ConcurrentHashMap<>();

    private PeerConnectionFactory peerConnectionFactory;
    private JavaAudioDeviceModule audioDeviceModule;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private boolean initialized;
    private volatile boolean closing;

    public GroupWebRtcPeerManager(Context context,
                                  boolean videoEnabled,
                                  SignalingListener signalingListener,
                                  TrackListener trackListener) {
        this.appContext = context.getApplicationContext();
        this.videoEnabled = videoEnabled;
        this.signalingListener = signalingListener;
        this.trackListener = trackListener;
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        ensureFactoryInitialized();
        createPeerConnectionFactory();
        createLocalMedia();
        initialized = true;
    }

    public EglBase.Context getEglBaseContext() {
        return eglBase.getEglBaseContext();
    }

    public VideoTrack getLocalVideoTrack() {
        return localVideoTrack;
    }

    public void setMuted(boolean muted) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
        }
    }

    public void setLocalVideoEnabled(boolean enabled) {
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        }
    }

    public void attachLocalRenderer(SurfaceViewRenderer renderer) {
        if (renderer == null || localVideoTrack == null) {
            return;
        }
        localVideoTrack.addSink(renderer);
    }

    public void detachLocalRenderer(SurfaceViewRenderer renderer) {
        if (renderer == null || localVideoTrack == null) {
            return;
        }
        localVideoTrack.removeSink(renderer);
    }

    public Set<String> getPeerIds() {
        return peers.keySet();
    }

    public void addPeer(String peerId, boolean createOffer) {
        if (peerId == null || peerId.trim().isEmpty() || closing) {
            return;
        }
        if (peers.containsKey(peerId)) {
            return;
        }
        PeerState peerState = createPeerState(peerId.trim());
        if (peerState == null) {
            return;
        }
        peers.put(peerId, peerState);
        if (createOffer) {
            createOffer(peerId, peerState);
        }
    }

    public void handleRemoteSdp(String peerId, SessionDescription sdp) {
        if (peerId == null || sdp == null || closing) {
            return;
        }
        PeerState peerState = peers.get(peerId);
        if (peerState == null) {
            addPeer(peerId, false);
            peerState = peers.get(peerId);
        }
        if (peerState == null) {
            return;
        }

        String normalized = normalizeSdp(sdp.description);
        if (normalized == null) {
            dispatchError("Remote SDP invalid for " + peerId);
            return;
        }

        SessionDescription remoteDescription = new SessionDescription(sdp.type, normalized);
        PeerState finalPeerState = peerState;
        peerState.peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                finalPeerState.remoteDescriptionSet = true;
                flushPendingCandidates(finalPeerState);
                if (remoteDescription.type == SessionDescription.Type.OFFER) {
                    createAnswer(peerId, finalPeerState);
                }
            }

            @Override
            public void onSetFailure(String error) {
                dispatchError("Set remote description failed for " + peerId + ": " + error);
            }
        }, remoteDescription);
    }

    public void handleRemoteIceCandidate(String peerId, IceCandidate candidate) {
        if (peerId == null || candidate == null || closing) {
            return;
        }
        PeerState peerState = peers.get(peerId);
        if (peerState == null) {
            addPeer(peerId, false);
            peerState = peers.get(peerId);
        }
        if (peerState == null) {
            return;
        }
        if (peerState.remoteDescriptionSet) {
            peerState.peerConnection.addIceCandidate(candidate);
        } else {
            peerState.pendingRemoteCandidates.add(candidate);
        }
    }

    public void removePeer(String peerId) {
        PeerState peerState = peers.remove(peerId);
        if (peerState == null) {
            return;
        }
        if (peerState.remoteVideoTrack != null) {
            peerState.remoteVideoTrack = null;
        }
        try {
            peerState.peerConnection.dispose();
        } catch (Exception ignored) {
        }
        if (trackListener != null) {
            postToMain(() -> trackListener.onPeerRemoved(peerId));
        }
    }

    public void close() {
        if (closing) {
            return;
        }
        closing = true;

        for (String peerId : new ArrayList<>(peers.keySet())) {
            removePeer(peerId);
        }
        peers.clear();

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
        eglBase.release();
        initialized = false;
    }

    private void ensureFactoryInitialized() {
        if (FACTORY_INITIALIZED.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .createInitializationOptions());
        }
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
    }

    private void createLocalMedia() {
        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);

        if (!videoEnabled) {
            return;
        }

        videoCapturer = createVideoCapturer();
        if (videoCapturer == null) {
            Log.w(TAG, "No camera capturer available; continuing with audio only");
            return;
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("GroupCallCapture", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource.getCapturerObserver());
        videoSource.adaptOutputFormat(320, 240, 10);
        try {
            videoCapturer.startCapture(320, 240, 10);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start capture", e);
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
    }

    private PeerState createPeerState(String peerId) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        configuration.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;

        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                if (trackListener != null) {
                    postToMain(() -> trackListener.onPeerConnectionStateChange(peerId, newState));
                }
                if (!closing && (newState == PeerConnection.PeerConnectionState.FAILED
                        || newState == PeerConnection.PeerConnectionState.CLOSED)) {
                    removePeer(peerId);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                if (signalingListener != null && iceCandidate != null && !closing) {
                    signalingListener.sendIceCandidate(peerId, iceCandidate);
                }
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
                if (track instanceof VideoTrack && trackListener != null) {
                    VideoTrack videoTrack = (VideoTrack) track;
                    PeerState state = peers.get(peerId);
                    if (state != null) {
                        state.remoteVideoTrack = videoTrack;
                    }
                    postToMain(() -> trackListener.onRemoteVideoTrack(peerId, videoTrack));
                }
            }

            @Override
            public void onRemoveTrack(RtpReceiver receiver) {
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                if (transceiver == null || transceiver.getReceiver() == null) {
                    return;
                }
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack && trackListener != null) {
                    VideoTrack videoTrack = (VideoTrack) track;
                    PeerState state = peers.get(peerId);
                    if (state != null) {
                        state.remoteVideoTrack = videoTrack;
                    }
                    postToMain(() -> trackListener.onRemoteVideoTrack(peerId, videoTrack));
                }
            }
        });

        if (peerConnection == null) {
            dispatchError("Failed to create peer connection for " + peerId);
            return null;
        }

        List<String> streamIds = Collections.singletonList(LOCAL_STREAM_ID);
        RtpSender audioSender = peerConnection.addTrack(localAudioTrack, streamIds);
        if (audioSender == null) {
            dispatchError("Failed to attach local audio for " + peerId);
        }
        if (localVideoTrack != null) {
            RtpSender videoSender = peerConnection.addTrack(localVideoTrack, streamIds);
            configureVideoSender(videoSender);
        }
        return new PeerState(peerConnection);
    }

    private void configureVideoSender(RtpSender sender) {
        if (sender == null) {
            return;
        }
        try {
            RtpParameters parameters = sender.getParameters();
            if (parameters == null || parameters.encodings == null || parameters.encodings.isEmpty()) {
                return;
            }
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;
            for (RtpParameters.Encoding encoding : parameters.encodings) {
                if (encoding == null) {
                    continue;
                }
                encoding.active = true;
                encoding.maxBitrateBps = 250_000;
                encoding.maxFramerate = 10;
            }
            sender.setParameters(parameters);
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure video sender", e);
        }
    }

    private void createOffer(String peerId, PeerState peerState) {
        peerState.peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                setLocalDescription(peerId, peerState, sessionDescription);
            }

            @Override
            public void onCreateFailure(String error) {
                dispatchError("Create offer failed for " + peerId + ": " + error);
            }
        }, createSdpConstraints());
    }

    private void createAnswer(String peerId, PeerState peerState) {
        peerState.peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                setLocalDescription(peerId, peerState, sessionDescription);
            }

            @Override
            public void onCreateFailure(String error) {
                dispatchError("Create answer failed for " + peerId + ": " + error);
            }
        }, createSdpConstraints());
    }

    private void setLocalDescription(String peerId, PeerState peerState, SessionDescription sessionDescription) {
        String normalized = normalizeSdp(sessionDescription.description);
        SessionDescription normalizedDescription = new SessionDescription(sessionDescription.type,
                normalized == null ? sessionDescription.description : normalized);
        peerState.peerConnection.setLocalDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                if (signalingListener != null && !closing && peerState.peerConnection.getLocalDescription() != null) {
                    signalingListener.sendSdp(peerId, peerState.peerConnection.getLocalDescription());
                }
            }

            @Override
            public void onSetFailure(String error) {
                dispatchError("Set local description failed for " + peerId + ": " + error);
            }
        }, normalizedDescription);
    }

    private void flushPendingCandidates(PeerState peerState) {
        if (peerState.pendingRemoteCandidates.isEmpty()) {
            return;
        }
        for (IceCandidate candidate : new ArrayList<>(peerState.pendingRemoteCandidates)) {
            peerState.peerConnection.addIceCandidate(candidate);
        }
        peerState.pendingRemoteCandidates.clear();
    }

    private MediaConstraints createSdpConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", String.valueOf(videoEnabled)));
        return constraints;
    }

    private VideoCapturer createVideoCapturer() {
        return createCapturer(new Camera1Enumerator(true));
    }

    private VideoCapturer createCapturer(CameraEnumerator enumerator) {
        for (String deviceName : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }
        for (String deviceName : enumerator.getDeviceNames()) {
            VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
            if (capturer != null) {
                return capturer;
            }
        }
        return null;
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

    private void dispatchError(String reason) {
        Log.e(TAG, reason);
        if (trackListener != null) {
            postToMain(() -> trackListener.onError(reason));
        }
    }

    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }
}

