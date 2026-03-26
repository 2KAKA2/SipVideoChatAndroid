package com.sipvideochat.ui.main;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.media.LocalMediaServer;
import com.sipvideochat.media.PcMediaRelayClient;
import com.sipvideochat.model.Message;
import com.sipvideochat.model.MessageRepository;
import com.sipvideochat.protocol.SipMessageBody;
import com.sipvideochat.sip.SipClient;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;
import com.sipvideochat.ui.call.CallActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main screen for contacts, chats, and incoming call entry points.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private SipService sipService;
    private boolean serviceBound = false;
    private MessageRepository messageRepository;

    private final Map<String, List<Message>> chatHistory = new LinkedHashMap<>();
    private final MutableLiveData<MessageEvent> messageEventsLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<String>> contactListLiveData = new MutableLiveData<>();
    private final MutableLiveData<IncomingCallData> incomingCallLiveData = new MutableLiveData<>();
    private final ExecutorService mediaMessageExecutor = Executors.newSingleThreadExecutor();

    private String currentChatUser = null;
    private String myUsername = "";
    private final List<String> contacts = new ArrayList<>();

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
            Log.i(TAG, "SipService bound, username: " + myUsername);
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
        }

        @Override
        public void onRegisterFailed(String reason) {
            Log.e(TAG, "SIP register failed: " + reason);
        }

        @Override
        public void onMessageReceived(String fromUser, SipMessageBody body) {
            if (!SipMessageBody.ACTION_CHAT.equals(body.getAction())) {
                return;
            }

            Message message = fromSipMessageBody(fromUser, body);
            String contactUser = fromUser;
            addContactInternal(contactUser, true);
            upsertMessage(contactUser, message, MessageEvent.EventType.ADDED, true);
        }

        @Override
        public void onMessageStatusChanged(String messageId, Message.MessageStatus status, String reason) {
            updateMessageStatus(messageId, status, reason);
        }

        @Override
        public void onIncomingCall(String fromUser, String sdp, SipClient.IncomingInvite invite) {
            incomingCallLiveData.postValue(new IncomingCallData(fromUser, sdp, invite));
        }

        @Override
        public void onCallRinging() {
            Log.i(TAG, "Remote is ringing");
        }

        @Override
        public void onCallConnected(String remoteSdp) {
            Log.i(TAG, "Call connected");
        }

        @Override
        public void onCallEnded() {
            Log.i(TAG, "Call ended");
        }

        @Override
        public void onCallFailed(String reason) {
            Log.e(TAG, "Call failed: " + reason);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        messageRepository = new MessageRepository(this);
        contacts.addAll(messageRepository.getContacts());
        contactListLiveData.setValue(new ArrayList<>(contacts));

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("SipVideoChat");

        Intent serviceIntent = new Intent(this, SipService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, new ContactsFragment())
                    .commit();
        }

        incomingCallLiveData.observe(this, this::showIncomingCallDialog);
    }

    private void showIncomingCallDialog(IncomingCallData callData) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("来电")
                .setMessage(callData.fromUser + " 邀请您通话")
                .setPositiveButton("接听", (dialog, which) -> {
                    Intent intent = new Intent(this, CallActivity.class);
                    intent.putExtra("remoteUser", callData.fromUser);
                    intent.putExtra("isOutgoing", false);
                    intent.putExtra("videoEnabled", callData.sdp != null && callData.sdp.contains("m=video"));
                    intent.putExtra("remoteSdp", callData.sdp);
                    CallActivity.pendingInvite = callData.invite;
                    startActivity(intent);
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    if (sipService != null) {
                        sipService.rejectCall(callData.invite);
                    }
                })
                .setCancelable(false)
                .show();
    }

    public void sendTextMessage(String targetUser, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Message message = new Message();
        message.setSenderId(myUsername);
        message.setSenderName(myUsername);
        message.setReceiverId(targetUser);
        message.setType(Message.MessageType.TEXT);
        message.setContent(text.trim());
        message.setStatus(Message.MessageStatus.SENDING);
        dispatchOutgoingMessage(targetUser, message);
    }

    public void sendImageMessage(String targetUser, Uri uri) {
        sendMediaMessage(targetUser, uri, Message.MessageType.IMAGE, 0);
    }

    public void sendVideoMessage(String targetUser, Uri uri) {
        sendMediaMessage(targetUser, uri, Message.MessageType.VIDEO, extractDurationSeconds(uri));
    }

    public void sendVoiceMessage(String targetUser, Uri uri, int durationSeconds) {
        sendMediaMessage(targetUser, uri, Message.MessageType.VOICE, durationSeconds);
    }

    private void sendMediaMessage(String targetUser, Uri uri, Message.MessageType type, int durationSeconds) {
        mediaMessageExecutor.execute(() -> {
            try {
                Log.i(TAG, "Preparing media message type=" + type
                        + ", target=" + targetUser
                        + ", uri=" + uri
                        + ", duration=" + durationSeconds);
                Message message = buildMediaMessage(targetUser, uri, type, durationSeconds);
                Log.i(TAG, "Prepared media message id=" + message.getId()
                        + ", type=" + message.getType()
                        + ", url=" + message.getMediaUrl()
                        + ", fileName=" + message.getFileName()
                        + ", fileSize=" + message.getFileSize());
                runOnUiThread(() -> dispatchOutgoingMessage(targetUser, message));
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare media message", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "媒体发送失败: " + friendlyErrorMessage(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void dispatchOutgoingMessage(String targetUser, Message message) {
        Log.i(TAG, "Dispatch outgoing message id=" + message.getId()
                + ", type=" + message.getType()
                + ", target=" + targetUser
                + ", status=" + message.getStatus());
        addContactInternal(targetUser, true);
        upsertMessage(targetUser, message, MessageEvent.EventType.ADDED, true);

        if (sipService == null) {
            markSendFailed(message, "SIP service unavailable");
            return;
        }

        try {
            sipService.sendMessage(targetUser, toSipMessageBody(message));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message", e);
            markSendFailed(message, e.getMessage());
        }
    }

    private void markSendFailed(Message message, String reason) {
        message.setStatus(Message.MessageStatus.FAILED);
        message.setErrorMessage(reason);
        upsertMessage(message.getReceiverId(), message, MessageEvent.EventType.UPDATED, true);
    }

    public void makeAudioCall(String targetUser) {
        if (sipService == null) return;
        sipService.makeCall(targetUser, false);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("remoteUser", targetUser);
        intent.putExtra("isOutgoing", true);
        intent.putExtra("videoEnabled", false);
        startActivity(intent);
    }

    public void makeVideoCall(String targetUser) {
        if (sipService == null) return;
        sipService.makeCall(targetUser, true);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("remoteUser", targetUser);
        intent.putExtra("isOutgoing", true);
        intent.putExtra("videoEnabled", true);
        startActivity(intent);
    }

    public void addContact(String username) {
        addContactInternal(username, true);
    }

    public void openChat(String username) {
        currentChatUser = username;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("与 " + username + " 聊天");

        ChatFragment chatFragment = ChatFragment.newInstance(username);
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

    public List<Message> getChatHistory(String username) {
        return new ArrayList<>(getOrLoadConversation(username));
    }

    public MutableLiveData<MessageEvent> getMessageEventsLiveData() {
        return messageEventsLiveData;
    }

    public MutableLiveData<List<String>> getContactListLiveData() {
        return contactListLiveData;
    }

    public List<String> getContacts() {
        return new ArrayList<>(contacts);
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

    private void addContactInternal(String username, boolean notify) {
        if (username == null || username.isEmpty() || username.equals(myUsername)) {
            return;
        }
        if (!contacts.contains(username)) {
            contacts.add(username);
            messageRepository.saveContacts(contacts);
            if (notify) {
                contactListLiveData.postValue(new ArrayList<>(contacts));
            }
        }
    }

    private List<Message> getOrLoadConversation(String contactUser) {
        List<Message> messages = chatHistory.get(contactUser);
        if (messages == null) {
            messages = messageRepository.getMessages(contactUser);
            chatHistory.put(contactUser, messages);
        }
        return messages;
    }

    private void upsertMessage(String contactUser, Message message, MessageEvent.EventType type, boolean notify) {
        List<Message> messages = getOrLoadConversation(contactUser);
        int existingIndex = indexOfMessage(messages, message.getId());
        if (existingIndex >= 0) {
            messages.set(existingIndex, message);
        } else {
            messages.add(message);
        }
        messageRepository.upsertMessage(contactUser, message);
        if (notify) {
            messageEventsLiveData.postValue(new MessageEvent(contactUser, message, type));
        }
    }

    private void updateMessageStatus(String messageId, Message.MessageStatus status, String reason) {
        Log.i(TAG, "Update message status id=" + messageId + ", status=" + status + ", reason=" + reason);
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

        for (String contact : contacts) {
            List<Message> messages = getOrLoadConversation(contact);
            int index = indexOfMessage(messages, messageId);
            if (index >= 0) {
                Message message = messages.get(index);
                message.setStatus(status);
                message.setErrorMessage(reason);
                upsertMessage(contact, message, MessageEvent.EventType.UPDATED, true);
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

    private SipMessageBody toSipMessageBody(Message message) {
        SipMessageBody body;
        switch (message.getType()) {
            case IMAGE:
                body = SipMessageBody.createImageMessage(myUsername, message.getReceiverId(),
                        message.getMediaUrl(), message.getFileName(), message.getFileSize());
                break;
            case VOICE:
                body = SipMessageBody.createVoiceMessage(myUsername, message.getReceiverId(),
                        message.getMediaUrl(), message.getDuration());
                break;
            case VIDEO:
                body = SipMessageBody.createVideoMessage(myUsername, message.getReceiverId(),
                        message.getMediaUrl(), message.getDuration());
                break;
            case FILE:
                body = SipMessageBody.createFileMessage(myUsername, message.getReceiverId(),
                        message.getMediaUrl(), message.getFileName(), message.getFileSize());
                break;
            case TEXT:
            default:
                body = SipMessageBody.createTextMessage(myUsername, message.getReceiverId(), message.getContent());
                break;
        }

        body.setMessageId(message.getId());
        body.setMimeType(message.getMimeType());
        body.setMsgContent(message.getContent());
        body.setFileName(message.getFileName());
        body.setFileSize(message.getFileSize());
        body.setDuration(message.getDuration());
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

    private Message buildMediaMessage(String targetUser, Uri uri, Message.MessageType type, int durationSeconds)
            throws IOException {
        Message message = new Message();
        message.setSenderId(myUsername);
        message.setSenderName(myUsername);
        message.setReceiverId(targetUser);
        message.setType(type);
        message.setStatus(Message.MessageStatus.SENDING);
        message.setTimestamp(System.currentTimeMillis());
        message.setLocalUri(uri.toString());
        message.setMimeType(getContentResolver().getType(uri));
        message.setFileName(resolveFileName(uri));
        message.setFileSize(resolveFileSize(uri));
        message.setDuration(durationSeconds);
        message.setMediaUrl(saveMediaAndGetUrl(uri, message.getFileName()));
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
            throw new IOException("SIP config unavailable");
        }

        String relayHost = config.getSipServerHost();
        if (relayHost != null && !relayHost.trim().isEmpty()) {
            return PcMediaRelayClient.upload(this, uri, fileName, getContentResolver().getType(uri),
                    relayHost.trim(), PcMediaRelayClient.DEFAULT_PORT);
        }

        String localIp = config.getLocalIp();
        if (localIp == null || localIp.isEmpty() || "0.0.0.0".equals(localIp)) {
            localIp = ClientConfig.detectLocalIp(this);
            config.setLocalIp(localIp);
            config.save(this);
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
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private String friendlyErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "请稍后重试";
        }
        return message;
    }
}
