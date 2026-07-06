package com.sipvideochat.ui.main;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.MutableLiveData;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.media.LocalMediaServer;
import com.sipvideochat.model.ChatGroup;
import com.sipvideochat.model.GroupAdminClient;
import com.sipvideochat.model.GroupRepository;
import com.sipvideochat.model.Message;
import com.sipvideochat.model.MessageRepository;
import com.sipvideochat.protocol.SipMessageBody;
import com.sipvideochat.sip.SipClient;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;
import com.sipvideochat.ui.call.CallActivity;
import com.sipvideochat.ui.call.GroupCallActivity;
import com.sipvideochat.util.DiagnosticLog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main screen for contacts, chats, and incoming call entry points.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String GROUP_KEY_PREFIX = "group:";
    private static final String CONTACT_REQUEST_PREFIX = "[[CONTACT_REQUEST]]";
    private static final String FRIEND_REQUEST_CHANNEL_ID = "friend_request_channel";

    private SipService sipService;
    private boolean serviceBound = false;
    private MessageRepository messageRepository;
    private GroupRepository groupRepository;

    private final Map<String, List<Message>> chatHistory = new LinkedHashMap<>();
    private final MutableLiveData<MessageEvent> messageEventsLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ConversationItem>> contactListLiveData = new MutableLiveData<>();
    private final MutableLiveData<IncomingCallData> incomingCallLiveData = new MutableLiveData<>();
    private final MutableLiveData<IncomingGroupCallData> incomingGroupCallLiveData = new MutableLiveData<>();
    private final ExecutorService mediaMessageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService adminSyncExecutor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Notifications are disabled, so invite alerts may be hidden.", Toast.LENGTH_SHORT).show();
                }
            });

    private String currentChatUser = null;
    private String myUsername = "";
    private final List<String> contacts = new ArrayList<>();
    private final List<ChatGroup> groups = new ArrayList<>();
    private final Set<String> pendingGroupCallPrompts = new LinkedHashSet<>();

    public static class ConversationItem {
        public final String key;
        public final String title;
        public final boolean group;

        public ConversationItem(String key, String title, boolean group) {
            this.key = key;
            this.title = title;
            this.group = group;
        }
    }

    public static class IncomingCallData {
        public final String fromUser;
        public final String sdp;
        public final SipClient.IncomingInvite invite;

        public IncomingCallData(String fromUser, String sdp, SipClient.IncomingInvite invite) {
            this.fromUser = fromUser;
            this.sdp = sdp;
            this.invite = invite;
        }
    }

    public static class IncomingGroupCallData {
        public final String roomId;
        public final String fromUser;
        public final boolean videoEnabled;

        public IncomingGroupCallData(String roomId, String fromUser, boolean videoEnabled) {
            this.roomId = roomId;
            this.fromUser = fromUser;
            this.videoEnabled = videoEnabled;
        }
    }

    public static class MessageEvent {
        public enum EventType { ADDED, UPDATED }

        public final String contactUser;
        public final Message message;
        public final EventType type;

        public MessageEvent(String contactUser, Message message, EventType type) {
            this.contactUser = contactUser;
            this.message = message;
            this.type = type;
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SipService.SipBinder binder = (SipService.SipBinder) service;
            sipService = binder.getService();
            serviceBound = true;

            if (sipService.getConfig() != null) {
                myUsername = sipService.getConfig().getUsername();
            }

            sipService.setEventListener(sipEventListener);
            publishConversationList();
            syncGroupsFromAdminServer(false);
            Log.i(TAG, "SipService bound, username: " + myUsername);
            DiagnosticLog.i(TAG, "service bound, username=" + myUsername);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sipService = null;
            serviceBound = false;
        }
    };

    private final SipEventListener sipEventListener = new SipEventListener() {
        @Override
        public void onRegistered() {
            Log.i(TAG, "SIP registered");
            DiagnosticLog.i(TAG, "sip registered");
        }

        @Override
        public void onRegisterFailed(String reason) {
            Log.e(TAG, "SIP register failed: " + reason);
            DiagnosticLog.e(TAG, "sip register failed: " + reason);
        }

        @Override
        public void onMessageReceived(String fromUser, SipMessageBody body) {
            if (body == null || body.getAction() == null) {
                return;
            }

            if (SipMessageBody.ACTION_GROUP_INVITE.equals(body.getAction())) {
                handleIncomingGroupInvite(fromUser, body);
                return;
            }

            if (isGroupCallSignal(body)) {
                ensureIncomingGroup(body.getRoomId(), fromUser);
                if (SipMessageBody.ACTION_JOIN.equals(body.getAction())) {
                    incomingGroupCallLiveData.postValue(new IncomingGroupCallData(
                            body.getRoomId(),
                            fromUser,
                            SipMessageBody.MSG_TYPE_VIDEO.equals(body.getMsgType())));
                }
                return;
            }

            if (!SipMessageBody.ACTION_CHAT.equals(body.getAction())) {
                return;
            }

            if (isContactRequestMessage(body)) {
                handleIncomingContactRequest(fromUser, body);
                return;
            }

            Message message = fromSipMessageBody(fromUser, body);
            String conversationKey = resolveConversationKey(fromUser, body);
            if (message.isGroupMessage()) {
                ensureIncomingGroup(body.getRoomId(), fromUser);
            } else {
                addContactInternal(conversationKey, true);
            }
            upsertMessage(conversationKey, message, MessageEvent.EventType.ADDED, true);
        }

        @Override
        public void onMessageStatusChanged(String messageId, Message.MessageStatus status, String reason) {
            updateMessageStatus(messageId, status, reason);
        }

        @Override
        public void onIncomingCall(String fromUser, String sdp, SipClient.IncomingInvite invite) {
            Log.i(TAG, "Incoming INVITE callback from=" + fromUser
                    + ", callId=" + (invite != null ? invite.callId : "null")
                    + ", hasSdp=" + (sdp != null && !sdp.isEmpty()));
            DiagnosticLog.i(TAG, "incoming invite callback from=" + fromUser
                    + ", callId=" + (invite != null ? invite.callId : "null")
                    + ", hasSdp=" + (sdp != null && !sdp.isEmpty())
                    + ", hasVideo=" + (sdp != null && sdp.contains("m=video")));
            incomingCallLiveData.postValue(new IncomingCallData(fromUser, sdp, invite));
        }

        @Override
        public void onCallRinging() {
            Log.i(TAG, "Remote is ringing");
        }

        @Override
        public void onCallConnected(String remoteSdp) {
            Log.i(TAG, "Call connected");
            DiagnosticLog.i(TAG, "call connected, remoteSdpLength=" + (remoteSdp == null ? 0 : remoteSdp.length()));
        }

        @Override
        public void onCallEnded() {
            Log.i(TAG, "Call ended");
        }

        @Override
        public void onCallFailed(String reason) {
            Log.e(TAG, "Call failed: " + reason);
            DiagnosticLog.e(TAG, "call failed: " + reason);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        createFriendRequestNotificationChannel();
        ensureNotificationPermission();

        messageRepository = new MessageRepository(this);
        groupRepository = new GroupRepository(this);
        contacts.addAll(messageRepository.getContacts());
        groups.addAll(groupRepository.getGroups());
        publishConversationList();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("SipVideoChat");
        toolbar.inflateMenu(R.menu.main_toolbar_menu);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClicked);

        Intent serviceIntent = new Intent(this, SipService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, new ContactsFragment())
                    .commit();
        }

        incomingCallLiveData.observe(this, this::showIncomingCallDialog);
        incomingGroupCallLiveData.observe(this, this::showIncomingGroupCallDialog);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sipService != null) {
            sipService.setEventListener(sipEventListener);
        }
        syncGroupsFromAdminServer(false);
    }

    private boolean onToolbarMenuItemClicked(MenuItem item) {
        if (item.getItemId() == R.id.action_admin_dashboard) {
            startActivity(new Intent(this, AdminDashboardActivity.class));
            return true;
        }
        return false;
    }

    private void showIncomingCallDialog(IncomingCallData callData) {
        Log.i(TAG, "Showing incoming call UI from=" + callData.fromUser
                + ", callId=" + (callData.invite != null ? callData.invite.callId : "null")
                + ", video=" + (callData.sdp != null && callData.sdp.contains("m=video"))
                + ", sdpLength=" + (callData.sdp == null ? 0 : callData.sdp.length()));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Incoming call")
                .setMessage(callData.fromUser + " 闂備緡鍘搁崑鎾绘偣閸ヮ亝鑵圭紓宥嗭耿閺屽懎顫濆畷鍥ㄢ枔")
                .setPositiveButton("Accept", (dialog, which) -> {
                    Intent intent = new Intent(this, CallActivity.class);
                    intent.putExtra("remoteUser", callData.fromUser);
                    intent.putExtra("isOutgoing", false);
                    intent.putExtra("videoEnabled", callData.sdp != null && callData.sdp.contains("m=video"));
                    intent.putExtra("remoteSdp", callData.sdp);
                    CallActivity.pendingInvite = callData.invite;
                    startActivity(intent);
                })
                .setNegativeButton("Decline", (dialog, which) -> {
                    if (sipService != null) {
                        sipService.rejectCall(callData.invite);
                    }
                })
                .setCancelable(false)
                .show();
    }

    public void sendTextMessage(String conversationKey, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Message message = new Message();
        message.setSenderId(myUsername);
        message.setSenderName(myUsername);
        message.setType(Message.MessageType.TEXT);
        message.setContent(text.trim());
        message.setStatus(Message.MessageStatus.SENDING);
        dispatchOutgoingMessage(conversationKey, message);
    }

    public void sendImageMessage(String conversationKey, Uri uri) {
        sendMediaMessage(conversationKey, uri, Message.MessageType.IMAGE, 0);
    }

    public void sendVideoMessage(String conversationKey, Uri uri) {
        sendMediaMessage(conversationKey, uri, Message.MessageType.VIDEO, extractDurationSeconds(uri));
    }

    public void sendVoiceMessage(String conversationKey, Uri uri, int durationSeconds) {
        sendMediaMessage(conversationKey, uri, Message.MessageType.VOICE, durationSeconds);
    }

    private void sendMediaMessage(String conversationKey, Uri uri, Message.MessageType type, int durationSeconds) {
        mediaMessageExecutor.execute(() -> {
            try {
                Message message = buildMediaMessage(conversationKey, uri, type, durationSeconds);
                runOnUiThread(() -> dispatchOutgoingMessage(conversationKey, message));
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare media message", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "婵犳鍨辩敮濠勭礊鐎ｎ喖鐭楅柟杈捐吂閸嬫挻鎷呴幖鐐版澀闁? " + friendlyErrorMessage(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void dispatchOutgoingMessage(String conversationKey, Message message) {
        if (isGroupConversation(conversationKey)) {
            ChatGroup group = findGroupByConversationKey(conversationKey);
            if (group == null) {
                markSendFailed(conversationKey, message, "Group not found");
                return;
            }

            message.setGroupId(group.getId());
            message.setReceiverId(null);
            upsertMessage(conversationKey, message, MessageEvent.EventType.ADDED, true);

            if (sipService == null || !sipService.isSessionReady()) {
                markSendFailed(conversationKey, message, "SIP unavailable");
                return;
            }

            boolean sent = false;
            for (String memberId : group.getMemberIds()) {
                if (memberId == null || memberId.trim().isEmpty() || myUsername.equals(memberId)) {
                    continue;
                }
                try {
                    sipService.sendMessage(memberId, toSipMessageBody(message, memberId));
                    sent = true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send group message to " + memberId, e);
                }
            }

            if (sent) {
                message.setStatus(Message.MessageStatus.SENT);
                upsertMessage(conversationKey, message, MessageEvent.EventType.UPDATED, true);
            } else {
                markSendFailed(conversationKey, message, "No group recipients available");
            }
            return;
        }

        message.setReceiverId(conversationKey);
        addContactInternal(conversationKey, true);
        upsertMessage(conversationKey, message, MessageEvent.EventType.ADDED, true);

        if (sipService == null || !sipService.isSessionReady()) {
            markSendFailed(conversationKey, message, "SIP unavailable");
            return;
        }

        try {
            sipService.sendMessage(conversationKey, toSipMessageBody(message, conversationKey));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message", e);
            markSendFailed(conversationKey, message, e.getMessage());
        }
    }

    private void markSendFailed(String conversationKey, Message message, String reason) {
        message.setStatus(Message.MessageStatus.FAILED);
        message.setErrorMessage(reason);
        upsertMessage(conversationKey, message, MessageEvent.EventType.UPDATED, true);
    }

    public void makeAudioCall(String targetUser) {
        if (sipService == null || !sipService.isSessionReady()) {
            Toast.makeText(this, "SIP session is not ready.", Toast.LENGTH_SHORT).show();
            return;
        }
        sipService.makeCall(targetUser, false);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("remoteUser", targetUser);
        intent.putExtra("isOutgoing", true);
        intent.putExtra("videoEnabled", false);
        startActivity(intent);
    }

    public void makeVideoCall(String targetUser) {
        if (sipService == null || !sipService.isSessionReady()) {
            Toast.makeText(this, "SIP session is not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("remoteUser", targetUser);
        intent.putExtra("isOutgoing", true);
        intent.putExtra("videoEnabled", true);
        startActivity(intent);
    }

    public void makeGroupAudioCall(String conversationKey) {
        startGroupCall(conversationKey, false);
    }

    public void makeGroupVideoCall(String conversationKey) {
        startGroupCall(conversationKey, true);
    }

    private void startGroupCall(String conversationKey, boolean videoEnabled) {
        ChatGroup group = findGroupByConversationKey(conversationKey);
        if (group == null || group.getId() == null || group.getId().trim().isEmpty()) {
            Toast.makeText(this, "Group conversation is unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sipService == null || !sipService.isSessionReady()) {
            Toast.makeText(this, "SIP session is not ready.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(GroupCallActivity.createIntent(
                this,
                group.getId(),
                getConversationTitle(conversationKey),
                videoEnabled));
    }

    public void addContact(String username) {
        if (addContactInternal(username, true)) {
            sendContactRequestReminder(username);
        }
    }

    public void createGroup(String groupName, String description, List<String> memberIds) {
        String trimmedName = groupName == null ? "" : groupName.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Group name is required");
        }

        ChatGroup group = new ChatGroup(trimmedName, myUsername);
        group.setDescription(description == null ? "" : description.trim());

        Set<String> uniqueMembers = new LinkedHashSet<>();
        uniqueMembers.add(myUsername);
        if (memberIds != null) {
            for (String memberId : memberIds) {
                if (memberId != null && !memberId.trim().isEmpty()) {
                    uniqueMembers.add(memberId.trim());
                }
            }
        }

        for (String memberId : uniqueMembers) {
            if (!group.isMember(memberId)) {
                group.addMember(memberId);
            }
            if (!memberId.equals(myUsername)) {
                addContactInternal(memberId, false);
            }
        }

        submitCreateGroup(group);
    }

    public void deleteGroup(String groupId) {
        submitDeleteGroup(groupId);
    }

    public ChatGroup getGroupForConversation(String conversationKey) {
        return findGroupByConversationKey(conversationKey);
    }

    public void updateGroup(String groupId, String groupName, String description, List<String> memberIds) {
        ChatGroup group = groupRepository.getGroup(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Group not found");
        }

        String trimmedName = groupName == null ? "" : groupName.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Group name is required");
        }

        group.setName(trimmedName);
        group.setDescription(description == null ? "" : description.trim());

        LinkedHashSet<String> uniqueMembers = new LinkedHashSet<>();
        uniqueMembers.add(myUsername);
        if (memberIds != null) {
            for (String memberId : memberIds) {
                if (memberId != null && !memberId.trim().isEmpty()) {
                    uniqueMembers.add(memberId.trim());
                }
            }
        }

        group.setMemberIds(new ArrayList<>(uniqueMembers));
        List<String> adminIds = new ArrayList<>();
        adminIds.add(group.getOwnerId() == null || group.getOwnerId().trim().isEmpty() ? myUsername : group.getOwnerId());
        group.setAdminIds(adminIds);

        for (String memberId : uniqueMembers) {
            if (!memberId.equals(myUsername)) {
                addContactInternal(memberId, false);
            }
        }
        submitUpdateGroup(group);
    }

    public void openChat(String conversationKey) {
        currentChatUser = conversationKey;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getConversationTitle(conversationKey));

        ChatFragment chatFragment = ChatFragment.newInstance(
                conversationKey,
                getConversationTitle(conversationKey),
                isGroupConversation(conversationKey));
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, chatFragment)
                .addToBackStack(null)
                .commit();
    }

    public void showContactsList() {
        currentChatUser = null;
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("SipVideoChat");
    }

    public List<Message> getChatHistory(String conversationKey) {
        return new ArrayList<>(getOrLoadConversation(conversationKey));
    }

    public MutableLiveData<MessageEvent> getMessageEventsLiveData() {
        return messageEventsLiveData;
    }

    public MutableLiveData<List<ConversationItem>> getContactListLiveData() {
        return contactListLiveData;
    }

    public List<ConversationItem> getContacts() {
        return buildConversationItems();
    }

    public String getMyUsername() {
        return myUsername;
    }

    public String getCurrentChatUser() {
        return currentChatUser;
    }

    public SipService getSipService() {
        return sipService;
    }

    private boolean addContactInternal(String username, boolean notify) {
        if (username == null || username.isEmpty() || username.equals(myUsername) || isGroupConversation(username)) {
            return false;
        }
        if (!contacts.contains(username)) {
            contacts.add(username);
            messageRepository.saveContacts(contacts);
            if (notify) {
                publishConversationList();
            }
            return true;
        }
        return false;
    }

    private void upsertGroup(ChatGroup group, boolean notify) {
        boolean replaced = false;
        for (int i = 0; i < groups.size(); i++) {
            if (group.getId().equals(groups.get(i).getId())) {
                groups.set(i, group);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            groups.add(group);
        }
        groupRepository.upsertGroup(group);
        if (notify) {
            publishConversationList();
        }
    }

    private void replaceGroups(List<ChatGroup> newGroups, boolean notify) {
        Map<String, ChatGroup> merged = new LinkedHashMap<>();
        for (ChatGroup group : groups) {
            if (group != null && group.getId() != null && !group.getId().trim().isEmpty()) {
                merged.put(group.getId(), group);
            }
        }
        if (newGroups != null) {
            for (ChatGroup group : newGroups) {
                if (group == null || group.getId() == null || group.getId().trim().isEmpty()) {
                    continue;
                }
                merged.put(group.getId(), group);
                groupRepository.upsertGroup(group);
            }
        }
        groups.clear();
        groups.addAll(merged.values());
        if (notify) {
            publishConversationList();
        }
    }

    private void publishConversationList() {
        contactListLiveData.postValue(buildConversationItems());
    }

    public void refreshGroupsFromAdmin() {
        syncGroupsFromAdminServer(true);
    }

    private void submitCreateGroup(ChatGroup group) {
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) {
            upsertGroup(group, true);
            sendGroupInviteMessages(group, true);
            return;
        }
        adminSyncExecutor.execute(() -> {
            try {
                ChatGroup saved = GroupAdminClient.createGroup(config, group);
                runOnUiThread(() -> {
                    upsertGroup(saved, true);
                    sendGroupInviteMessages(saved, true);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to create group on admin server", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to create group on admin server. Saved locally.", Toast.LENGTH_SHORT).show();
                    upsertGroup(group, true);
                    sendGroupInviteMessages(group, true);
                });
            }
        });
    }

    private void submitUpdateGroup(ChatGroup group) {
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) {
            upsertGroup(group, true);
            sendGroupInviteMessages(group, false);
            return;
        }
        adminSyncExecutor.execute(() -> {
            try {
                ChatGroup saved = GroupAdminClient.updateGroup(config, group);
                runOnUiThread(() -> {
                    upsertGroup(saved, true);
                    sendGroupInviteMessages(saved, false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to update group on admin server", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to update group on admin server. Changes kept locally.", Toast.LENGTH_SHORT).show();
                    upsertGroup(group, true);
                    sendGroupInviteMessages(group, false);
                });
            }
        });
    }

    private void sendGroupInviteMessages(ChatGroup group, boolean newlyCreated) {
        if (group == null || group.getId() == null || group.getId().trim().isEmpty()) {
            return;
        }

        String conversationKey = conversationKeyForGroup(group.getId());
        Message localMessage = new Message();
        localMessage.setSenderId(myUsername);
        localMessage.setSenderName(myUsername);
        localMessage.setReceiverId(null);
        localMessage.setGroupId(group.getId());
        localMessage.setType(Message.MessageType.SYSTEM);
        localMessage.setContent(extractGroupInviteText(group, myUsername, newlyCreated));
        localMessage.setTimestamp(System.currentTimeMillis());
        localMessage.setStatus(Message.MessageStatus.SENT);
        upsertMessage(conversationKey, localMessage, MessageEvent.EventType.ADDED, true);

        if (sipService == null || !sipService.isSessionReady()) {
            Toast.makeText(this, "Group saved locally, but invites were not sent.", Toast.LENGTH_SHORT).show();
            return;
        }

        String members = joinMembers(group.getMemberIds());
        for (String memberId : group.getMemberIds()) {
            if (memberId == null || memberId.trim().isEmpty() || memberId.equals(myUsername)) {
                continue;
            }
            try {
                SipMessageBody invite = SipMessageBody.createGroupInvite(
                        group.getId(),
                        myUsername,
                        memberId,
                        group.getName(),
                        group.getDescription(),
                        members);
                invite.setMsgContent(extractGroupInviteText(group, myUsername, newlyCreated));
                sipService.sendMessage(memberId, invite);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send group invite to " + memberId, e);
            }
        }
    }

    private void handleIncomingGroupInvite(String fromUser, SipMessageBody body) {
        ChatGroup group = buildGroupFromInvite(fromUser, body);
        upsertGroup(group, true);

        for (String memberId : group.getMemberIds()) {
            if (memberId != null && !memberId.trim().isEmpty() && !memberId.equals(myUsername)) {
                addContactInternal(memberId, false);
            }
        }

        Message message = new Message();
        if (body.getMessageId() != null && !body.getMessageId().isEmpty()) {
            message.setId(body.getMessageId());
        }
        message.setSenderId(fromUser);
        message.setSenderName(fromUser);
        message.setReceiverId(myUsername);
        message.setGroupId(group.getId());
        message.setType(Message.MessageType.SYSTEM);
        String content = body.getMsgContent();
        if (content == null || content.trim().isEmpty()) {
            content = extractGroupInviteText(group, fromUser, false);
        }
        message.setContent(content);
        message.setTimestamp(body.getTimestamp() > 0 ? body.getTimestamp() : System.currentTimeMillis());
        message.setStatus(Message.MessageStatus.DELIVERED);

        String conversationKey = conversationKeyForGroup(group.getId());
        upsertMessage(conversationKey, message, MessageEvent.EventType.ADDED, true);
        showGroupInviteNotification(group, fromUser, message.getContent());
        Toast.makeText(this, message.getContent(), Toast.LENGTH_SHORT).show();
    }

    private ChatGroup buildGroupFromInvite(String fromUser, SipMessageBody body) {
        String roomId = body.getRoomId() == null ? "" : body.getRoomId().trim();
        ChatGroup existing = roomId.isEmpty() ? null : groupRepository.getGroup(roomId);
        ChatGroup group = existing != null ? existing : new ChatGroup();

        if (!roomId.isEmpty()) {
            group.setId(roomId);
        }
        group.setName(safeGroupName(body.getGroupName(), roomId));
        group.setDescription(body.getGroupDescription() == null ? "" : body.getGroupDescription().trim());
        group.setOwnerId(fromUser);

        LinkedHashSet<String> memberIds = new LinkedHashSet<>();
        if (existing != null && existing.getMemberIds() != null) {
            memberIds.addAll(existing.getMemberIds());
        }
        memberIds.addAll(parseMembers(body.getGroupMembers()));
        if (fromUser != null && !fromUser.trim().isEmpty()) {
            memberIds.add(fromUser.trim());
        }
        if (myUsername != null && !myUsername.trim().isEmpty()) {
            memberIds.add(myUsername.trim());
        }
        group.setMemberIds(new ArrayList<>(memberIds));

        List<String> adminIds = new ArrayList<>();
        if (fromUser != null && !fromUser.trim().isEmpty()) {
            adminIds.add(fromUser.trim());
        }
        group.setAdminIds(adminIds);
        return group;
    }

    private String extractGroupInviteText(ChatGroup group, String fromUser, boolean newlyCreated) {
        String inviter = (fromUser == null || fromUser.trim().isEmpty()) ? "Someone" : fromUser.trim();
        String groupName = group != null ? safeGroupName(group.getName(), group.getId()) : "Group chat";
        if (newlyCreated) {
            return inviter + " created group \"" + groupName + "\".";
        }
        return inviter + " updated group \"" + groupName + "\".";
    }

    private void showGroupInviteNotification(ChatGroup group, String fromUser, String contentText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ensureNotificationPermission();
            Log.w(TAG, "Group invite notification skipped because POST_NOTIFICATIONS is not granted");
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                Math.abs((group.getId() + fromUser).hashCode()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FRIEND_REQUEST_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Group invite")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManagerCompat.from(this)
                .notify(Math.abs((group.getId() + contentText).hashCode()), builder.build());
    }

    private List<String> parseMembers(String rawMembers) {
        List<String> members = new ArrayList<>();
        if (rawMembers == null || rawMembers.trim().isEmpty()) {
            return members;
        }
        for (String item : rawMembers.split(",")) {
            String member = item == null ? "" : item.trim();
            if (!member.isEmpty()) {
                members.add(member);
            }
        }
        return members;
    }

    private String joinMembers(List<String> members) {
        List<String> cleaned = new ArrayList<>();
        if (members != null) {
            for (String member : members) {
                if (member != null && !member.trim().isEmpty()) {
                    cleaned.add(member.trim());
                }
            }
        }
        return String.join(",", cleaned);
    }

    private String safeGroupName(String groupName, String roomId) {
        if (groupName != null && !groupName.trim().isEmpty()) {
            return groupName.trim();
        }
        if (roomId != null && !roomId.trim().isEmpty()) {
            return "Group " + roomId.substring(0, Math.min(6, roomId.length()));
        }
        return "Group chat";
    }


    private void submitDeleteGroup(String groupId) {
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) {
            deleteGroupLocal(groupId);
            return;
        }
        adminSyncExecutor.execute(() -> {
            try {
                GroupAdminClient.deleteGroup(config, groupId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete group on admin server", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Failed to delete group on admin server.", Toast.LENGTH_SHORT).show());
            }
            runOnUiThread(() -> deleteGroupLocal(groupId));
        });
    }

    private void deleteGroupLocal(String groupId) {
        groups.removeIf(item -> groupId.equals(item.getId()));
        groupRepository.deleteGroup(groupId);
        publishConversationList();
    }

    private void syncGroupsFromAdminServer(boolean showFailureToast) {
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) {
            return;
        }
        adminSyncExecutor.execute(() -> {
            try {
                List<ChatGroup> remoteGroups = GroupAdminClient.listGroups(config);
                runOnUiThread(() -> replaceGroups(remoteGroups, true));
            } catch (Exception e) {
                Log.w(TAG, "Failed to sync groups from admin server", e);
                if (showFailureToast) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Failed to sync groups from admin server.", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private List<ConversationItem> buildConversationItems() {
        List<ConversationItem> items = new ArrayList<>();
        for (String contact : contacts) {
            items.add(new ConversationItem(contact, contact, false));
        }
        for (ChatGroup group : groups) {
            if (group.getId() == null) {
                continue;
            }
            items.add(new ConversationItem(conversationKeyForGroup(group.getId()), getConversationTitle(conversationKeyForGroup(group.getId())), true));
        }
        return items;
    }

    private String conversationKeyForGroup(String groupId) {
        return GROUP_KEY_PREFIX + groupId;
    }

    private boolean isGroupConversation(String key) {
        return key != null && key.startsWith(GROUP_KEY_PREFIX);
    }

    private ChatGroup findGroupByConversationKey(String key) {
        if (!isGroupConversation(key)) {
            return null;
        }
        String groupId = key.substring(GROUP_KEY_PREFIX.length());
        for (ChatGroup group : groups) {
            if (groupId.equals(group.getId())) {
                return group;
            }
        }
        return null;
    }

    private String getConversationTitle(String key) {
        if (!isGroupConversation(key)) {
            return key;
        }
        ChatGroup group = findGroupByConversationKey(key);
        if (group == null) {
            return "Group chat";
        }
        String name = group.getName();
        if (name == null || name.trim().isEmpty()) {
            return "缂傚倸娲㈤崐娑欑?" + group.getMemberCount() + ")";
        }
        return name;
    }

    private String resolveConversationKey(String fromUser, SipMessageBody body) {
        if (body.getRoomId() != null && !body.getRoomId().trim().isEmpty()) {
            return conversationKeyForGroup(body.getRoomId().trim());
        }
        return fromUser;
    }

    private boolean isGroupCallSignal(SipMessageBody body) {
        if (body == null || body.getAction() == null || body.getRoomId() == null || body.getRoomId().trim().isEmpty()) {
            return false;
        }
        switch (body.getAction()) {
            case SipMessageBody.ACTION_JOIN:
            case SipMessageBody.ACTION_WELCOME:
            case SipMessageBody.ACTION_LEAVE:
            case SipMessageBody.ACTION_WEBRTC_OFFER:
            case SipMessageBody.ACTION_WEBRTC_ANSWER:
            case SipMessageBody.ACTION_WEBRTC_ICE:
                return true;
            default:
                return false;
        }
    }

    private void showIncomingGroupCallDialog(IncomingGroupCallData data) {
        if (data == null || data.roomId == null || data.roomId.trim().isEmpty()) {
            return;
        }
        String promptKey = data.roomId + "|" + data.fromUser;
        if (!pendingGroupCallPrompts.add(promptKey)) {
            return;
        }

        String conversationKey = conversationKeyForGroup(data.roomId);
        String title = getConversationTitle(conversationKey);
        String message = data.fromUser + " invited you to join a " + (data.videoEnabled ? "group video call" : "group voice call") + ": " + title;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Group call invite")
                .setMessage(message)
                .setPositiveButton("Join", (dialog, which) -> startActivity(
                        GroupCallActivity.createIntent(this, data.roomId, title, data.videoEnabled)))
                .setNegativeButton("Later", null)
                .setOnDismissListener(dialog -> pendingGroupCallPrompts.remove(promptKey))
                .show();
    }

    private void ensureIncomingGroup(String roomId, String fromUser) {
        if (roomId == null || roomId.trim().isEmpty()) {
            return;
        }

        ChatGroup group = groupRepository.getGroup(roomId);
        if (group == null) {
            group = new ChatGroup();
            group.setId(roomId.trim());
            group.setName("缂傚倸娲㈤崐娑欑?" + roomId.substring(0, Math.min(6, roomId.length())));
            group.setOwnerId(fromUser);
            group.setMemberIds(new ArrayList<>(Collections.singletonList(fromUser)));
            group.setAdminIds(new ArrayList<>(Collections.singletonList(fromUser)));
        }

        if (!group.isMember(myUsername)) {
            group.addMember(myUsername);
        }
        if (!group.isMember(fromUser)) {
            group.addMember(fromUser);
        }
        upsertGroup(group, true);
    }

    private List<Message> getOrLoadConversation(String conversationKey) {
        List<Message> messages = chatHistory.get(conversationKey);
        if (messages == null) {
            messages = messageRepository.getMessages(conversationKey);
            chatHistory.put(conversationKey, messages);
        }
        return messages;
    }

    private void upsertMessage(String conversationKey, Message message, MessageEvent.EventType type, boolean notify) {
        List<Message> messages = getOrLoadConversation(conversationKey);
        int existingIndex = indexOfMessage(messages, message.getId());
        if (existingIndex >= 0) {
            messages.set(existingIndex, message);
        } else {
            messages.add(message);
        }
        messageRepository.upsertMessage(conversationKey, message);
        if (notify) {
            messageEventsLiveData.postValue(new MessageEvent(conversationKey, message, type));
            publishConversationList();
        }
    }

    private void updateMessageStatus(String messageId, Message.MessageStatus status, String reason) {
        for (Map.Entry<String, List<Message>> entry : chatHistory.entrySet()) {
            int index = indexOfMessage(entry.getValue(), messageId);
            if (index >= 0) {
                Message message = entry.getValue().get(index);
                message.setStatus(status);
                message.setErrorMessage(reason);
                upsertMessage(entry.getKey(), message, MessageEvent.EventType.UPDATED, true);
                return;
            }
        }

        for (ConversationItem item : buildConversationItems()) {
            List<Message> messages = getOrLoadConversation(item.key);
            int index = indexOfMessage(messages, messageId);
            if (index >= 0) {
                Message message = messages.get(index);
                message.setStatus(status);
                message.setErrorMessage(reason);
                upsertMessage(item.key, message, MessageEvent.EventType.UPDATED, true);
                return;
            }
        }
    }

    private int indexOfMessage(List<Message> messages, String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private SipMessageBody toSipMessageBody(Message message, String targetUser) {
        SipMessageBody body;
        switch (message.getType()) {
            case IMAGE:
                body = SipMessageBody.createImageMessage(myUsername, targetUser,
                        message.getMediaUrl(), message.getFileName(), message.getFileSize());
                break;
            case VOICE:
                body = SipMessageBody.createVoiceMessage(myUsername, targetUser,
                        message.getMediaUrl(), message.getDuration());
                break;
            case VIDEO:
                body = SipMessageBody.createVideoMessage(myUsername, targetUser,
                        message.getMediaUrl(), message.getDuration());
                break;
            case FILE:
                body = SipMessageBody.createFileMessage(myUsername, targetUser,
                        message.getMediaUrl(), message.getFileName(), message.getFileSize());
                break;
            case SYSTEM:
            case TEXT:
            default:
                body = SipMessageBody.createTextMessage(myUsername, targetUser, message.getContent());
                break;
        }

        body.setMessageId(message.getId());
        body.setMimeType(message.getMimeType());
        body.setMsgContent(message.getContent());
        body.setFileName(message.getFileName());
        body.setFileSize(message.getFileSize());
        body.setDuration(message.getDuration());
        if (message.isGroupMessage()) {
            body.setRoomId(message.getGroupId());
        }
        return body;
    }

    private Message fromSipMessageBody(String fromUser, SipMessageBody body) {
        Message message = new Message();
        if (body.getMessageId() != null && !body.getMessageId().isEmpty()) {
            message.setId(body.getMessageId());
        }
        message.setSenderId(fromUser);
        message.setSenderName(fromUser);
        message.setReceiverId(myUsername);
        message.setGroupId(body.getRoomId());
        message.setType(mapFromSipType(body.getMsgType()));
        message.setContent(body.getMsgContent());
        message.setMediaUrl(body.getFileUrl());
        message.setMimeType(body.getMimeType());
        message.setFileName(body.getFileName());
        message.setFileSize(body.getFileSize());
        message.setDuration(body.getDuration());
        message.setTimestamp(body.getTimestamp() > 0 ? body.getTimestamp() : System.currentTimeMillis());
        message.setStatus(Message.MessageStatus.DELIVERED);
        return message;
    }

    private Message.MessageType mapFromSipType(String msgType) {
        if (SipMessageBody.MSG_TYPE_IMAGE.equals(msgType)) {
            return Message.MessageType.IMAGE;
        }
        if (SipMessageBody.MSG_TYPE_VOICE.equals(msgType)) {
            return Message.MessageType.VOICE;
        }
        if (SipMessageBody.MSG_TYPE_VIDEO.equals(msgType)) {
            return Message.MessageType.VIDEO;
        }
        if (SipMessageBody.MSG_TYPE_FILE.equals(msgType)) {
            return Message.MessageType.FILE;
        }
        return Message.MessageType.TEXT;
    }

    private boolean isContactRequestMessage(SipMessageBody body) {
        if (body == null || body.getMsgContent() == null) {
            return false;
        }
        return body.getMsgContent().startsWith(CONTACT_REQUEST_PREFIX);
    }

    private void handleIncomingContactRequest(String fromUser, SipMessageBody body) {
        addContactInternal(fromUser, true);

        Message message = new Message();
        if (body.getMessageId() != null && !body.getMessageId().isEmpty()) {
            message.setId(body.getMessageId());
        }
        message.setSenderId(fromUser);
        message.setSenderName(fromUser);
        message.setReceiverId(myUsername);
        message.setType(Message.MessageType.SYSTEM);
        message.setContent(extractContactRequestText(body.getMsgContent(), fromUser));
        message.setTimestamp(body.getTimestamp() > 0 ? body.getTimestamp() : System.currentTimeMillis());
        message.setStatus(Message.MessageStatus.DELIVERED);

        upsertMessage(fromUser, message, MessageEvent.EventType.ADDED, true);
        showFriendRequestNotification(fromUser, message.getContent());
        Toast.makeText(this, message.getContent(), Toast.LENGTH_SHORT).show();
    }

    private void sendContactRequestReminder(String targetUser) {
        if (sipService == null || !sipService.isSessionReady()) {
            Toast.makeText(this, "SIP session is not ready. Reminder was not sent.", Toast.LENGTH_SHORT).show();
            return;
        }

        Message message = new Message();
        message.setSenderId(myUsername);
        message.setSenderName(myUsername);
        message.setReceiverId(targetUser);
        message.setType(Message.MessageType.SYSTEM);
        message.setContent(CONTACT_REQUEST_PREFIX + myUsername + " wants to add you as a contact.");
        message.setStatus(Message.MessageStatus.SENDING);
        try {
            sipService.sendMessage(targetUser, toSipMessageBody(message, targetUser));
            Toast.makeText(this, "Contact reminder sent.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to send contact request reminder", e);
            Toast.makeText(this, "Contact was added, but the reminder failed to send.", Toast.LENGTH_SHORT).show();
        }
    }
    private String extractContactRequestText(String rawContent, String fromUser) {
        if (rawContent == null || rawContent.isEmpty()) {
            return fromUser + " wants to add you as a contact.";
        }
        if (rawContent.startsWith(CONTACT_REQUEST_PREFIX)) {
            String message = rawContent.substring(CONTACT_REQUEST_PREFIX.length()).trim();
            return message.isEmpty() ? fromUser + " wants to add you as a contact." : message;
        }
        return rawContent;
    }
    private void createFriendRequestNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                FRIEND_REQUEST_CHANNEL_ID,
                "Contact Requests",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifications for incoming contact requests");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void showFriendRequestNotification(String fromUser, String contentText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ensureNotificationPermission();
            Log.w(TAG, "Notification skipped because POST_NOTIFICATIONS is not granted");
            DiagnosticLog.w(TAG, "friend request notification skipped: POST_NOTIFICATIONS denied");
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                Math.abs((fromUser + contentText).hashCode()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FRIEND_REQUEST_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("New contact request")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManagerCompat.from(this)
                .notify(Math.abs((fromUser + contentText).hashCode()), builder.build());
    }
    private Message buildMediaMessage(String conversationKey, Uri uri, Message.MessageType type, int durationSeconds)
            throws IOException {
        Message message = new Message();
        message.setSenderId(myUsername);
        message.setSenderName(myUsername);
        message.setType(type);
        message.setStatus(Message.MessageStatus.SENDING);
        message.setTimestamp(System.currentTimeMillis());
        message.setLocalUri(uri.toString());
        message.setMimeType(getContentResolver().getType(uri));
        message.setFileName(resolveFileName(uri));
        message.setFileSize(resolveFileSize(uri));
        message.setDuration(durationSeconds);
        message.setMediaUrl(saveMediaAndGetUrl(uri, message.getFileName()));
        if (isGroupConversation(conversationKey)) {
            ChatGroup group = findGroupByConversationKey(conversationKey);
            if (group != null) {
                message.setGroupId(group.getId());
            }
        } else {
            message.setReceiverId(conversationKey);
        }
        return message;
    }

    private String resolveFileName(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return new File(uri.getPath()).getName();
        }

        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            } finally {
                cursor.close();
            }
        }
        return uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "media";
    }

    private long resolveFileSize(Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }

        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst() && sizeIndex >= 0) {
                    return cursor.getLong(sizeIndex);
                }
            } finally {
                cursor.close();
            }
        }
        return 0L;
    }

    private int extractDurationSeconds(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs == null) {
                return 0;
            }
            return Math.max(1, (int) Math.round(Long.parseLong(durationMs) / 1000.0));
        } catch (Exception e) {
            Log.w(TAG, "Failed to read media duration", e);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String saveMediaAndGetUrl(Uri uri, String fileName) throws IOException {
        ClientConfig config = sipService != null ? sipService.getConfig() : null;
        if (config == null) {
            config = new ClientConfig();
            config.load(this);
            if (!config.isSipConfigured()) {
                throw new IOException("SIP config unavailable");
            }
        }

        String localIp = config.getLocalIp();
        String detectedLocalIp = ClientConfig.detectLocalIp(this);
        if (detectedLocalIp != null
                && !detectedLocalIp.isEmpty()
                && !"0.0.0.0".equals(detectedLocalIp)
                && !detectedLocalIp.equals(localIp)) {
            localIp = detectedLocalIp;
            config.setLocalIp(localIp);
            config.save(this);
        } else if (localIp == null || localIp.isEmpty() || "0.0.0.0".equals(localIp)) {
            localIp = detectedLocalIp;
            config.setLocalIp(localIp);
            config.save(this);
        }
        if (localIp == null || localIp.isEmpty() || "0.0.0.0".equals(localIp)) {
            throw new IOException("Local media server IP unavailable");
        }

        int mediaPort = config.getLocalSipPort() + 1000;
        LocalMediaServer mediaServer = LocalMediaServer.getInstance(this, localIp, mediaPort);

        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return mediaServer.saveFile(new File(uri.getPath()), fileName);
        }
        return mediaServer.saveContentUri(uri, fileName);
    }

    @Override
    public void onBackPressed() {
        if (currentChatUser != null) {
            showContactsList();
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mediaMessageExecutor.shutdownNow();
        adminSyncExecutor.shutdownNow();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private String friendlyErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Please try again later.";
        }
        return message;
    }
}








