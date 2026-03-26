package com.sipvideochat.protocol;

/**
 * SIP MESSAGE消息体 - JSON格式
 * 用于文字、图片、语音、视频消息以及群聊信令
 * (从桌面端 src/protocol/SipMessageBody.java 移植)
 */
public class SipMessageBody {
    // 消息动作类型
    public static final String ACTION_CHAT = "CHAT";
    public static final String ACTION_JOIN = "JOIN";
    public static final String ACTION_WELCOME = "WELCOME";
    public static final String ACTION_READY = "READY";
    public static final String ACTION_LEAVE = "LEAVE";
    public static final String ACTION_TYPING = "TYPING";
    public static final String ACTION_WEBRTC_OFFER = "WEBRTC_OFFER";
    public static final String ACTION_WEBRTC_ANSWER = "WEBRTC_ANSWER";
    public static final String ACTION_WEBRTC_ICE = "WEBRTC_ICE";

    // 消息内容类型
    public static final String MSG_TYPE_TEXT = "text";
    public static final String MSG_TYPE_IMAGE = "image";
    public static final String MSG_TYPE_VOICE = "voice";
    public static final String MSG_TYPE_VIDEO = "video";
    public static final String MSG_TYPE_FILE = "file";

    private String action;
    private String messageId;
    private String fromUser;
    private String toUser;
    private String roomId;
    private String ip;
    private int port;

    // WebRTC signaling fields
    private String sdp;
    private String sdpType;
    private String iceCandidate;
    private String iceSdpMid;
    private int iceSdpMLineIndex;

    // 消息内容
    private String msgType;
    private String msgContent;
    private String fileUrl;
    private String mimeType;
    private String fileName;
    private long fileSize;
    private int duration;

    private long timestamp;

    public SipMessageBody() {
        this.timestamp = System.currentTimeMillis();
    }

    // ============== 工厂方法 ==============

    public static SipMessageBody createTextMessage(String fromUser, String toUser, String content) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_CHAT);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setMsgType(MSG_TYPE_TEXT);
        body.setMsgContent(content);
        return body;
    }

    public static SipMessageBody createImageMessage(String fromUser, String toUser, String fileUrl, String fileName, long fileSize) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_CHAT);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setMsgType(MSG_TYPE_IMAGE);
        body.setFileUrl(fileUrl);
        body.setFileName(fileName);
        body.setFileSize(fileSize);
        return body;
    }

    public static SipMessageBody createVoiceMessage(String fromUser, String toUser, String fileUrl, int duration) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_CHAT);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setMsgType(MSG_TYPE_VOICE);
        body.setFileUrl(fileUrl);
        body.setDuration(duration);
        return body;
    }

    public static SipMessageBody createVideoMessage(String fromUser, String toUser, String fileUrl, int duration) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_CHAT);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setMsgType(MSG_TYPE_VIDEO);
        body.setFileUrl(fileUrl);
        body.setDuration(duration);
        return body;
    }

    public static SipMessageBody createFileMessage(String fromUser, String toUser, String fileUrl, String fileName, long fileSize) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_CHAT);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setMsgType(MSG_TYPE_FILE);
        body.setFileUrl(fileUrl);
        body.setFileName(fileName);
        body.setFileSize(fileSize);
        return body;
    }

    public static SipMessageBody createJoinMessage(String roomId, String fromUser, String ip, int port) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_JOIN);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        body.setIp(ip);
        body.setPort(port);
        return body;
    }

    public static SipMessageBody createWelcomeMessage(String roomId, String fromUser, String ip, int port) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_WELCOME);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        body.setIp(ip);
        body.setPort(port);
        return body;
    }

    public static SipMessageBody createReadyMessage(String roomId, String fromUser, String ip, int port) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_READY);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        body.setIp(ip);
        body.setPort(port);
        return body;
    }

    public static SipMessageBody createLeaveMessage(String roomId, String fromUser) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_LEAVE);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        return body;
    }

    public static SipMessageBody createWebRtcOffer(String roomId, String fromUser, String toUser, String sdp) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_WEBRTC_OFFER);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setSdp(sdp);
        body.setSdpType("offer");
        return body;
    }

    public static SipMessageBody createWebRtcAnswer(String roomId, String fromUser, String toUser, String sdp) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_WEBRTC_ANSWER);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setSdp(sdp);
        body.setSdpType("answer");
        return body;
    }

    public static SipMessageBody createWebRtcIceCandidate(String roomId, String fromUser, String toUser,
                                                          String candidate, String sdpMid, int sdpMLineIndex) {
        SipMessageBody body = new SipMessageBody();
        body.setAction(ACTION_WEBRTC_ICE);
        body.setRoomId(roomId);
        body.setFromUser(fromUser);
        body.setToUser(toUser);
        body.setIceCandidate(candidate);
        body.setIceSdpMid(sdpMid);
        body.setIceSdpMLineIndex(sdpMLineIndex);
        return body;
    }

    // ============== 序列化/反序列化 ==============

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "action", action, true);
        appendJsonField(sb, "messageId", messageId, true);
        appendJsonField(sb, "fromUser", fromUser, true);
        appendJsonField(sb, "toUser", toUser, true);
        appendJsonField(sb, "roomId", roomId, true);
        appendJsonField(sb, "ip", ip, true);
        sb.append("\"port\":").append(port).append(",");
        appendJsonField(sb, "msgType", msgType, true);
        appendJsonField(sb, "msgContent", msgContent, true);
        appendJsonField(sb, "fileUrl", fileUrl, true);
        appendJsonField(sb, "mimeType", mimeType, true);
        appendJsonField(sb, "fileName", fileName, true);
        sb.append("\"fileSize\":").append(fileSize).append(",");
        sb.append("\"duration\":").append(duration).append(",");
        appendJsonField(sb, "sdp", sdp, true);
        appendJsonField(sb, "sdpType", sdpType, true);
        appendJsonField(sb, "iceCandidate", iceCandidate, true);
        appendJsonField(sb, "iceSdpMid", iceSdpMid, true);
        sb.append("\"iceSdpMLineIndex\":").append(iceSdpMLineIndex).append(",");
        sb.append("\"timestamp\":").append(timestamp);
        sb.append("}");
        return sb.toString();
    }

    private void appendJsonField(StringBuilder sb, String key, String value, boolean hasNext) {
        sb.append("\"").append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
        if (hasNext) {
            sb.append(",");
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static SipMessageBody fromJson(String json) {
        SipMessageBody body = new SipMessageBody();
        if (json == null || json.isEmpty()) {
            return body;
        }

        body.setAction(extractJsonString(json, "action"));
        body.setMessageId(extractJsonString(json, "messageId"));
        body.setFromUser(extractJsonString(json, "fromUser"));
        body.setToUser(extractJsonString(json, "toUser"));
        body.setRoomId(extractJsonString(json, "roomId"));
        body.setIp(extractJsonString(json, "ip"));
        body.setPort(extractJsonInt(json, "port"));
        body.setMsgType(extractJsonString(json, "msgType"));
        body.setMsgContent(extractJsonString(json, "msgContent"));
        body.setFileUrl(extractJsonString(json, "fileUrl"));
        body.setMimeType(extractJsonString(json, "mimeType"));
        body.setFileName(extractJsonString(json, "fileName"));
        body.setFileSize(extractJsonLong(json, "fileSize"));
        body.setDuration(extractJsonInt(json, "duration"));
        body.setSdp(extractJsonString(json, "sdp"));
        body.setSdpType(extractJsonString(json, "sdpType"));
        body.setIceCandidate(extractJsonString(json, "iceCandidate"));
        body.setIceSdpMid(extractJsonString(json, "iceSdpMid"));
        body.setIceSdpMLineIndex(extractJsonInt(json, "iceSdpMLineIndex"));
        body.setTimestamp(extractJsonLong(json, "timestamp"));

        return body;
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();

        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return null;

        if (json.charAt(start) == 'n' && json.substring(start).startsWith("null")) {
            return null;
        }

        if (json.charAt(start) != '"') return null;
        start++;

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        if (sb.length() == 0) return 0;
        return Integer.parseInt(sb.toString());
    }

    private static long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        if (sb.length() == 0) return 0;
        return Long.parseLong(sb.toString());
    }

    // ============== Getters and Setters ==============

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getFromUser() { return fromUser; }
    public void setFromUser(String fromUser) { this.fromUser = fromUser; }

    public String getToUser() { return toUser; }
    public void setToUser(String toUser) { this.toUser = toUser; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }

    public String getMsgContent() { return msgContent; }
    public void setMsgContent(String msgContent) { this.msgContent = msgContent; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

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

    public String getSdp() { return sdp; }
    public void setSdp(String sdp) { this.sdp = sdp; }

    public String getSdpType() { return sdpType; }
    public void setSdpType(String sdpType) { this.sdpType = sdpType; }

    public String getIceCandidate() { return iceCandidate; }
    public void setIceCandidate(String iceCandidate) { this.iceCandidate = iceCandidate; }

    public String getIceSdpMid() { return iceSdpMid; }
    public void setIceSdpMid(String iceSdpMid) { this.iceSdpMid = iceSdpMid; }

    public int getIceSdpMLineIndex() { return iceSdpMLineIndex; }
    public void setIceSdpMLineIndex(int iceSdpMLineIndex) { this.iceSdpMLineIndex = iceSdpMLineIndex; }

    @Override
    public String toString() {
        return "SipMessageBody{" +
                "action='" + action + '\'' +
                ", fromUser='" + fromUser + '\'' +
                ", toUser='" + toUser + '\'' +
                ", msgType='" + msgType + '\'' +
                ", msgContent='" + msgContent + '\'' +
                '}';
    }
}
