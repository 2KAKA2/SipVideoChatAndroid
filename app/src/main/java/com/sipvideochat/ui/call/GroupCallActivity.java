package com.sipvideochat.ui.call;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.model.ChatGroup;
import com.sipvideochat.model.GroupRepository;
import com.sipvideochat.protocol.SipMessageBody;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;
import com.sipvideochat.webrtc.GroupWebRtcPeerManager;

import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android group audio/video call activity backed by SIP MESSAGE room signaling.
 */
public class GroupCallActivity extends AppCompatActivity {
    private static final String TAG = "GroupCallActivity";
    private static final String EXTRA_ROOM_ID = "room_id";
    private static final String EXTRA_ROOM_TITLE = "room_title";
    private static final String EXTRA_VIDEO_ENABLED = "video_enabled";

    private SipService sipService;
    private boolean serviceBound;
    private boolean permissionsGranted;
    private boolean videoEnabled;
    private boolean joinedRoom;
    private boolean leaving;

    private String roomId;
    private String roomTitle;
    private String myUsername;

    private GroupRepository groupRepository;
    private ChatGroup roomGroup;
    private GroupWebRtcPeerManager peerManager;
    private AudioManager audioManager;

    private TextView tvRoomTitle;
    private TextView tvStatus;
    private TextView tvParticipants;
    private LinearLayout participantContainer;
    private MaterialButton btnMute;
    private MaterialButton btnHangup;

    private boolean muted;

    private final Map<String, ParticipantTile> participantTiles = new LinkedHashMap<>();

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Boolean value : result.values()) {
                    if (!Boolean.TRUE.equals(value)) {
                        granted = false;
                        break;
                    }
                }
                permissionsGranted = granted;
                if (!granted) {
                    Toast.makeText(this,
                            videoEnabled ? "Camera and microphone permissions are required." : "Microphone permission is required.",
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                maybeStartRoom();
            });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SipService.SipBinder binder = (SipService.SipBinder) service;
            sipService = binder.getService();
            serviceBound = true;
            sipService.addEventListener(sipEventListener);
            maybeStartRoom();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sipService = null;
            serviceBound = false;
        }
    };

    private final SipEventListener sipEventListener = new SipEventListener() {
        @Override
        public void onMessageReceived(String fromUser, SipMessageBody message) {
            runOnUiThread(() -> handleIncomingSignal(fromUser, message));
        }
    };

    public static Intent createIntent(Context context, String roomId, String roomTitle, boolean videoEnabled) {
        Intent intent = new Intent(context, GroupCallActivity.class);
        intent.putExtra(EXTRA_ROOM_ID, roomId);
        intent.putExtra(EXTRA_ROOM_TITLE, roomTitle);
        intent.putExtra(EXTRA_VIDEO_ENABLED, videoEnabled);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ActiveCallGuard.markActive();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_call);

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        roomTitle = getIntent().getStringExtra(EXTRA_ROOM_TITLE);
        videoEnabled = getIntent().getBooleanExtra(EXTRA_VIDEO_ENABLED, true);
        if (roomId == null || roomId.trim().isEmpty()) {
            Toast.makeText(this, "Invalid room.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        roomId = roomId.trim();
        if (roomTitle == null || roomTitle.trim().isEmpty()) {
            roomTitle = "Group-" + roomId.substring(0, Math.min(6, roomId.length()));
        }

        groupRepository = new GroupRepository(this);
        roomGroup = groupRepository.getGroup(roomId);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        bindViews();
        configureUi();
        requestRequiredPermissions();
        bindService(new Intent(this, SipService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onBackPressed() {
        leaveRoomAndFinish();
    }

    @Override
    protected void onDestroy() {
        ActiveCallGuard.markInactive();
        super.onDestroy();
        releaseResources(false);
    }

    private void bindViews() {
        tvRoomTitle = findViewById(R.id.tvRoomTitle);
        tvStatus = findViewById(R.id.tvStatus);
        tvParticipants = findViewById(R.id.tvParticipants);
        participantContainer = findViewById(R.id.participantContainer);
        btnMute = findViewById(R.id.btnMute);
        btnHangup = findViewById(R.id.btnHangup);
    }

    private void configureUi() {
        tvRoomTitle.setText(roomTitle);
        tvStatus.setText(videoEnabled ? "Preparing group video..." : "Preparing group audio...");
        updateParticipantCount();

        btnMute.setOnClickListener(v -> toggleMute());
        btnHangup.setOnClickListener(v -> leaveRoomAndFinish());
    }

    private void requestRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (videoEnabled) {
            permissions.add(Manifest.permission.CAMERA);
        }

        boolean granted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }

        if (granted) {
            permissionsGranted = true;
            maybeStartRoom();
            return;
        }
        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private void maybeStartRoom() {
        if (!permissionsGranted || !serviceBound || sipService == null || peerManager != null) {
            return;
        }
        ClientConfig config = sipService.getConfig();
        if (config == null || config.getUsername() == null || config.getUsername().trim().isEmpty()) {
            Toast.makeText(this, "SIP session is not ready.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        myUsername = config.getUsername().trim();
        ensureRoomGroup();
        configureAudioRoute();

        peerManager = new GroupWebRtcPeerManager(
                this,
                videoEnabled,
                new GroupWebRtcPeerManager.SignalingListener() {
                    @Override
                    public void sendSdp(String peerId, SessionDescription sdp) {
                        sendSdpSignal(peerId, sdp);
                    }

                    @Override
                    public void sendIceCandidate(String peerId, IceCandidate candidate) {
                        sendIceSignal(peerId, candidate);
                    }
                },
                new GroupWebRtcPeerManager.TrackListener() {
                    @Override
                    public void onRemoteVideoTrack(String peerId, VideoTrack videoTrack) {
                        attachRemoteVideo(peerId, videoTrack);
                    }

                    @Override
                    public void onPeerConnectionStateChange(String peerId, PeerConnection.PeerConnectionState state) {
                        handlePeerStateChanged(peerId, state);
                    }

                    @Override
                    public void onPeerRemoved(String peerId) {
                        removeParticipant(peerId, false);
                    }

                    @Override
                    public void onError(String reason) {
                        if (!isFinishing()) {
                            tvStatus.setText(reason);
                        }
                    }
                });

        try {
            peerManager.initialize();
            ParticipantTile localTile = ensureParticipantTile(myUsername, true);
            if (videoEnabled && localTile != null) {
                peerManager.attachLocalRenderer(localTile.renderer);
            }
            joinRoom();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize group room", e);
            Toast.makeText(this, "Failed to initialize group call: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void ensureRoomGroup() {
        if (roomGroup == null) {
            roomGroup = new ChatGroup();
            roomGroup.setId(roomId);
            roomGroup.setName(roomTitle);
            roomGroup.setOwnerId(myUsername);
        }
        if (!roomGroup.isMember(myUsername)) {
            roomGroup.addMember(myUsername);
        }
        groupRepository.upsertGroup(roomGroup);
    }

    private void joinRoom() {
        if (joinedRoom || sipService == null) {
            return;
        }
        joinedRoom = true;
        int sentCount = 0;
        SipMessageBody joinMessage = SipMessageBody.createJoinMessage(roomId, myUsername,
                sipService.getConfig() != null ? sipService.getConfig().getLocalIp() : null,
                0);
        joinMessage.setMsgType(videoEnabled ? SipMessageBody.MSG_TYPE_VIDEO : SipMessageBody.MSG_TYPE_VOICE);

        List<String> roomMembers = roomGroup != null && roomGroup.getMemberIds() != null
                ? roomGroup.getMemberIds()
                : new ArrayList<>();
        for (String memberId : new LinkedHashSet<>(roomMembers)) {
            if (memberId == null || memberId.trim().isEmpty() || memberId.equals(myUsername)) {
                continue;
            }
            try {
                sipService.sendMessage(memberId, joinMessage);
                sentCount++;
            } catch (Exception e) {
                Log.w(TAG, "Failed to send JOIN to " + memberId, e);
            }
        }

        ensureParticipantTile(myUsername, true);
        updateParticipantCount();
        tvStatus.setText(sentCount > 0
                ? "Joined room. Waiting for other participants..."
                : "Joined room. No other participants yet.");
    }

    private void handleIncomingSignal(String fromUser, SipMessageBody message) {
        if (peerManager == null || message == null || message.getAction() == null || message.getRoomId() == null) {
            return;
        }
        if (!roomId.equals(message.getRoomId())) {
            return;
        }
        if (myUsername != null && myUsername.equals(fromUser)) {
            return;
        }

        switch (message.getAction()) {
            case SipMessageBody.ACTION_JOIN:
                updateVideoModeIfNecessary(message);
                ensureKnownMember(fromUser);
                ensureParticipantTile(fromUser, false);
                sendWelcome(fromUser);
                peerManager.addPeer(fromUser, shouldCreateOfferFor(fromUser));
                tvStatus.setText(fromUser + " joined the room.");
                break;
            case SipMessageBody.ACTION_WELCOME:
                updateVideoModeIfNecessary(message);
                ensureKnownMember(fromUser);
                ensureParticipantTile(fromUser, false);
                peerManager.addPeer(fromUser, shouldCreateOfferFor(fromUser));
                tvStatus.setText(fromUser + " acknowledged the room.");
                break;
            case SipMessageBody.ACTION_LEAVE:
                removeParticipant(fromUser, true);
                tvStatus.setText(fromUser + " left the room.");
                break;
            case SipMessageBody.ACTION_WEBRTC_OFFER:
                peerManager.handleRemoteSdp(fromUser,
                        new SessionDescription(SessionDescription.Type.OFFER, message.getSdp()));
                break;
            case SipMessageBody.ACTION_WEBRTC_ANSWER:
                peerManager.handleRemoteSdp(fromUser,
                        new SessionDescription(SessionDescription.Type.ANSWER, message.getSdp()));
                break;
            case SipMessageBody.ACTION_WEBRTC_ICE:
                if (message.getIceCandidate() != null) {
                    peerManager.handleRemoteIceCandidate(fromUser,
                            new IceCandidate(message.getIceSdpMid(), message.getIceSdpMLineIndex(), message.getIceCandidate()));
                }
                break;
            default:
                break;
        }
    }

    private void updateVideoModeIfNecessary(SipMessageBody message) {
        if (videoEnabled || !SipMessageBody.MSG_TYPE_VIDEO.equals(message.getMsgType())) {
            return;
        }
        videoEnabled = true;
        tvStatus.setText("Room switched to video mode.");
        for (ParticipantTile tile : participantTiles.values()) {
            tile.setVideoVisible(true);
        }
    }

    private void sendWelcome(String targetUser) {
        if (sipService == null) {
            return;
        }
        SipMessageBody welcome = SipMessageBody.createWelcomeMessage(roomId, myUsername,
                sipService.getConfig() != null ? sipService.getConfig().getLocalIp() : null,
                0);
        welcome.setMsgType(videoEnabled ? SipMessageBody.MSG_TYPE_VIDEO : SipMessageBody.MSG_TYPE_VOICE);
        try {
            sipService.sendMessage(targetUser, welcome);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send WELCOME to " + targetUser, e);
        }
    }

    private void sendSdpSignal(String peerId, SessionDescription sdp) {
        if (sipService == null || sdp == null) {
            return;
        }
        SipMessageBody body = sdp.type == SessionDescription.Type.OFFER
                ? SipMessageBody.createWebRtcOffer(roomId, myUsername, peerId, sdp.description)
                : SipMessageBody.createWebRtcAnswer(roomId, myUsername, peerId, sdp.description);
        try {
            sipService.sendMessage(peerId, body);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send SDP to " + peerId, e);
        }
    }

    private void sendIceSignal(String peerId, IceCandidate candidate) {
        if (sipService == null || candidate == null) {
            return;
        }
        SipMessageBody body = SipMessageBody.createWebRtcIceCandidate(
                roomId,
                myUsername,
                peerId,
                candidate.sdp,
                candidate.sdpMid,
                candidate.sdpMLineIndex);
        try {
            sipService.sendMessage(peerId, body);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send ICE to " + peerId, e);
        }
    }

    private boolean shouldCreateOfferFor(String peerId) {
        if (myUsername == null || peerId == null) {
            return false;
        }
        return myUsername.compareToIgnoreCase(peerId) < 0;
    }

    private void ensureKnownMember(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return;
        }
        if (roomGroup == null) {
            ensureRoomGroup();
        }
        if (!roomGroup.isMember(userId)) {
            roomGroup.addMember(userId);
            groupRepository.upsertGroup(roomGroup);
        }
        updateParticipantCount();
    }

    private ParticipantTile ensureParticipantTile(String userId, boolean local) {
        ParticipantTile tile = participantTiles.get(userId);
        if (tile != null) {
            return tile;
        }
        EglBase.Context eglContext = peerManager != null ? peerManager.getEglBaseContext() : null;
        tile = ParticipantTile.create(this, userId, local, eglContext, videoEnabled);
        participantTiles.put(userId, tile);
        participantContainer.addView(tile.rootView);
        updateParticipantCount();
        return tile;
    }

    private void attachRemoteVideo(String peerId, VideoTrack videoTrack) {
        ParticipantTile tile = ensureParticipantTile(peerId, false);
        if (tile == null || videoTrack == null) {
            return;
        }
        tile.attachVideoTrack(videoTrack, videoEnabled);
    }

    private void handlePeerStateChanged(String peerId, PeerConnection.PeerConnectionState state) {
        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            tvStatus.setText(peerId + " connected.");
        } else if (state == PeerConnection.PeerConnectionState.FAILED
                || state == PeerConnection.PeerConnectionState.CLOSED) {
            removeParticipant(peerId, false);
            tvStatus.setText(peerId + " disconnected.");
        }
    }

    private void removeParticipant(String peerId, boolean removePeerConnection) {
        if (peerId == null || peerId.equals(myUsername)) {
            return;
        }
        ParticipantTile tile = participantTiles.remove(peerId);
        if (tile != null) {
            participantContainer.removeView(tile.rootView);
            tile.release();
        }
        if (removePeerConnection && peerManager != null) {
            peerManager.removePeer(peerId);
        }
        updateParticipantCount();
    }

    private void updateParticipantCount() {
        int count = participantTiles.size();
        if (count == 0 && roomGroup != null) {
            count = Math.max(1, roomGroup.getMemberCount());
        }
        tvParticipants.setText("Participants: " + Math.max(count, 1));
    }

    private void toggleMute() {
        muted = !muted;
        if (peerManager != null) {
            peerManager.setMuted(muted);
        }
        btnMute.setText(muted ? "Unmute" : "Mute");
    }

    private void leaveRoomAndFinish() {
        if (leaving) {
            finish();
            return;
        }
        leaving = true;
        if (sipService != null && myUsername != null) {
            SipMessageBody leave = SipMessageBody.createLeaveMessage(roomId, myUsername);
            Set<String> targets = new LinkedHashSet<>();
            if (roomGroup != null && roomGroup.getMemberIds() != null) {
                targets.addAll(roomGroup.getMemberIds());
            }
            if (peerManager != null) {
                targets.addAll(peerManager.getPeerIds());
            }
            for (String target : targets) {
                if (target == null || target.trim().isEmpty() || target.equals(myUsername)) {
                    continue;
                }
                try {
                    sipService.sendMessage(target, leave);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send LEAVE to " + target, e);
                }
            }
        }
        releaseResources(true);
        finish();
    }

    private void releaseResources(boolean clearListener) {
        if (peerManager != null) {
            for (ParticipantTile tile : new ArrayList<>(participantTiles.values())) {
                if (tile.localTile) {
                    peerManager.detachLocalRenderer(tile.renderer);
                }
                tile.release();
            }
            participantTiles.clear();
            participantContainer.removeAllViews();
            peerManager.close();
            peerManager = null;
        }
        restoreAudioRoute();
        if (serviceBound) {
            if (clearListener && sipService != null) {
                sipService.removeEventListener(sipEventListener);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    private void configureAudioRoute() {
        if (audioManager == null) {
            return;
        }
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
    }

    private void restoreAudioRoute() {
        if (audioManager == null) {
            return;
        }
        audioManager.setSpeakerphoneOn(false);
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    private static final class ParticipantTile {
        final FrameLayout rootView;
        final SurfaceViewRenderer renderer;
        final TextView labelView;
        final boolean localTile;
        VideoTrack attachedVideoTrack;

        private ParticipantTile(FrameLayout rootView,
                                SurfaceViewRenderer renderer,
                                TextView labelView,
                                boolean localTile) {
            this.rootView = rootView;
            this.renderer = renderer;
            this.labelView = labelView;
            this.localTile = localTile;
        }

        static ParticipantTile create(Context context,
                                      String userId,
                                      boolean local,
                                      @Nullable EglBase.Context eglContext,
                                      boolean videoEnabled) {
            FrameLayout root = new FrameLayout(context);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f);
            int margin = dp(context, 8);
            layoutParams.setMargins(margin, margin, margin, margin);
            root.setLayoutParams(layoutParams);
            root.setBackgroundColor(local ? Color.parseColor("#223A5E") : Color.parseColor("#1F1F1F"));

            SurfaceViewRenderer renderer = new SurfaceViewRenderer(context);
            FrameLayout.LayoutParams rendererParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            renderer.setLayoutParams(rendererParams);
            if (eglContext != null) {
                renderer.init(eglContext, null);
                renderer.setEnableHardwareScaler(true);
                renderer.setMirror(local);
                renderer.setZOrderMediaOverlay(local);
            }
            renderer.setVisibility(videoEnabled ? View.VISIBLE : View.GONE);
            root.addView(renderer);

            TextView label = new TextView(context);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM);
            labelParams.setMargins(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
            label.setLayoutParams(labelParams);
            label.setText(local ? userId + " (You)" : userId);
            label.setTextColor(Color.WHITE);
            label.setTextSize(15f);
            label.setBackgroundColor(Color.parseColor("#66000000"));
            label.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
            root.addView(label);

            return new ParticipantTile(root, renderer, label, local);
        }

        void attachVideoTrack(VideoTrack videoTrack, boolean videoEnabled) {
            if (renderer == null) {
                return;
            }
            if (attachedVideoTrack != null && attachedVideoTrack != videoTrack) {
                attachedVideoTrack.removeSink(renderer);
            }
            attachedVideoTrack = videoTrack;
            renderer.setVisibility(videoEnabled ? View.VISIBLE : View.GONE);
            if (videoEnabled) {
                videoTrack.addSink(renderer);
            }
        }

        void setVideoVisible(boolean visible) {
            renderer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        void release() {
            if (attachedVideoTrack != null) {
                attachedVideoTrack.removeSink(renderer);
                attachedVideoTrack = null;
            }
            renderer.release();
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
