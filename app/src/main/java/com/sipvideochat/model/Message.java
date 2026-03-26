package com.sipvideochat.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * 消息实体类
 * (从桌面端 src/com/location/im/common/Message.java 移植)
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String senderId;
    private String senderName;
    private String receiverId;
    private String groupId;
    private MessageType type;
    private String content;
    private byte[] mediaData;
    private String mediaUrl;
    private String localUri;
    private String mimeType;
    private String fileName;
    private long fileSize;
    private int duration;
    private long timestamp;
    private MessageStatus status;
    private String errorMessage;

    public enum MessageType {
        TEXT, IMAGE, VOICE, VIDEO, FILE, SYSTEM,
        CALL_INVITE, CALL_ACCEPT, CALL_REJECT, CALL_END,
        TYPING, READ_RECEIPT
    }

    public enum MessageStatus {
        SENDING, SENT, DELIVERED, READ, FAILED
    }

    public Message() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.status = MessageStatus.SENDING;
    }

    public Message(String senderId, String receiverId, MessageType type, String content) {
        this();
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.type = type;
        this.content = content;
    }

    public static Message createTextMessage(String senderId, String receiverId, String text) {
        return new Message(senderId, receiverId, MessageType.TEXT, text);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public byte[] getMediaData() { return mediaData; }
    public void setMediaData(byte[] mediaData) { this.mediaData = mediaData; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }

    public String getLocalUri() { return localUri; }
    public void setLocalUri(String localUri) { this.localUri = localUri; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isGroupMessage() {
        return groupId != null && !groupId.isEmpty();
    }
}
