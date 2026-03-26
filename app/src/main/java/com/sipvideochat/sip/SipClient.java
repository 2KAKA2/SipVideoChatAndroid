package com.sipvideochat.sip;

import android.util.Log;

import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.model.Message;
import com.sipvideochat.protocol.SipMessageBody;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIP鐎广垺鍩涚粩?- 閸樼喎顫?UDP 鐎圭偟骞囬敍鍫滅瑝娓氭繆绂?JAIN-SIP閿?
 * 閸欏倻鍙?sip0 閺嶈渹绶ラ敍灞惧閸斻劍鐎?SIP 濞戝牊浼呴敍宀勪缉閸?Android voip-common.jar 閸愯尙鐛婇妴?
 */
public class SipClient {
    private static final String TAG = "SipClient";
    private static final String MESSAGE_CONTENT_TYPE_JSON = "application/json";
    private static final String MESSAGE_CONTENT_TYPE_TEXT = "text/plain;charset=UTF-8";

    private final ClientConfig config;
    private final List<SipEventListener> listeners = new ArrayList<>();

    // UDP Socket
    private DatagramSocket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> registerTimeoutFuture;
    private static final long REGISTER_TIMEOUT_MS = 8000;

    // 濞夈劌鍞介悩鑸碘偓?
    private String registerCallId;
    private String registerFromTag;
    private int registerCseq = 1;
    private boolean registerAuthAttempted = false;

    // 闁俺鐦介悩鑸碘偓?
    private String currentCallId;
    private String currentFromTag;
    private String currentToTag;
    private String remoteContact; // 鐎佃鏌熼惃?Contact 閸︽澘娼?
    private boolean inCall = false;
    private String lastInviteFailureKey;

    // 娣囨繂鐡ㄩ弶銉ф暩娣団剝浼?
    private IncomingInvite pendingIncomingInvite;

    // P2P 閼辨梻閮存禍鍝勬勾閸р偓
    private final Map<String, String> userContacts = new HashMap<>();
    private final Map<String, OutgoingMessageTx> pendingMessageTx = new ConcurrentHashMap<>();

    // Keep-alive
    private final AtomicBoolean keepAliveRunning = new AtomicBoolean(false);
    private Thread keepAliveThread;
    private Thread recvThread;

    public SipClient(ClientConfig config) {
        this.config = config;
    }

    public void addListener(SipEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SipEventListener listener) {
        listeners.remove(listener);
    }

    // ============== 閸氼垰濮?/ 閸嬫粍顒?==============

    public synchronized void init() throws Exception {
        if (socket != null) return;

        socket = new DatagramSocket(config.getLocalSipPort());
        running.set(true);

        recvThread = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            while (running.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    DatagramSocket s = socket;
                    if (s == null) break;
                    s.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                    Log.d(TAG, "<< " + msg.split("\r\n")[0] + " from " + pkt.getAddress().getHostAddress() + ":" + pkt.getPort());
                    handleIncoming(msg, pkt.getAddress(), pkt.getPort());
                } catch (Exception e) {
                    if (running.get()) {
                        Log.w(TAG, "recv error: " + e.getMessage());
                    }
                }
            }
        }, "sip-recv");
        recvThread.setDaemon(true);
        recvThread.start();

        Log.w(TAG, "SIP Client started: " + config.getLocalIp() + ":" + config.getLocalSipPort());

        // 閼奉亜濮╁▔銊ュ斀
        register();
    }

    public void shutdown() {
        running.set(false);
        keepAliveRunning.set(false);
        registered.set(false);
        inCall = false;
        pendingMessageTx.clear();
        registerCallId = null;
        registerFromTag = null;
        registerAuthAttempted = false;
        cancelRegisterTimeout();
        try {
            if (socket != null) socket.close();
        } catch (Exception e) { /* ignore */ }
        socket = null;
        scheduler.shutdownNow();
    }

    // ============== 濞夈劌鍞?==============

    public void register() throws Exception {
        registerCallId = (registerCallId != null) ? registerCallId : uuid();
        registerFromTag = (registerFromTag != null) ? registerFromTag : shortUuid();
        registerCseq = 1;
        registerAuthAttempted = false;
        String req = buildRegisterRequest(120, registerCallId, registerCseq, null);
        scheduleRegisterTimeout(registerCallId, registerCseq);
        send(req);
        Log.d(TAG, "REGISTER context user=" + config.getUsername()
                + ", local=" + config.getLocalIp() + ":" + config.getLocalSipPort()
                + ", server=" + config.getSipServerHost() + ":" + config.getSipServerPort());
        Log.w(TAG, "REGISTER sent");
    }

    private void handleRegisterResponse(Map<String, String> headers, int statusCode) {
        if (statusCode == 200) {
            cancelRegisterTimeout();
            registered.set(true);
            startKeepAlive();
            Log.w(TAG, "REGISTER success");
            for (SipEventListener l : listeners) l.onRegistered();
        } else if (statusCode == 401) {
            cancelRegisterTimeout();
            if (registerAuthAttempted) {
                for (SipEventListener l : listeners) l.onRegisterFailed("鐠併倛鐦夋径杈Е閿涙俺澶勯崣閿嬪灗鐎靛棛鐖滈柨娆掝嚖");
                return;
            }
            String www = getHeader(headers, "WWW-Authenticate");
            if (www == null) www = getHeader(headers, "Proxy-Authenticate");
            if (www == null || www.isEmpty()) {
                for (SipEventListener l : listeners) l.onRegisterFailed("401 閺冪姾顓荤拠浣搞仈");
                return;
            }
            try {
                registerAuthAttempted = true;
                Log.d(TAG, "401 challenge: " + www);
                String auth = buildDigestAuth(www, "REGISTER",
                        "sip:" + config.getSipServerHost() + ":" + config.getSipServerPort(),
                        config.getUsername(), config.getPassword());
                Log.d(TAG, "Authorization header: " + auth);
                String callId = getHeader(headers, "Call-ID");
                if (callId == null) callId = registerCallId;
                registerCseq++;
                String req = buildRegisterRequest(120, callId, registerCseq, auth);
                scheduleRegisterTimeout(callId, registerCseq);
                send(req);
                Log.d(TAG, "Authenticated REGISTER sent with cseq=" + registerCseq);
                Log.w(TAG, "Authenticated REGISTER sent");
            } catch (Exception e) {
                Log.e(TAG, "鐠併倛鐦夋径杈Е", e);
                for (SipEventListener l : listeners) l.onRegisterFailed("Digest auth calculation failed");
            }
        } else {
            cancelRegisterTimeout();
            for (SipEventListener l : listeners) l.onRegisterFailed("濞夈劌鍞芥径杈Е: " + statusCode);
        }
    }

    // ============== 閸欐垿鈧焦绉烽幁?==============

    public void sendMessage(String targetUser, SipMessageBody body) throws Exception {
        String callId = uuid();
        String fromTag = shortUuid();
        String requestUri = getRequestUri(targetUser);
        String messageId = body.getMessageId();
        if (messageId == null || messageId.isEmpty()) {
            messageId = uuid();
            body.setMessageId(messageId);
        }
        OutgoingMessageTx tx = new OutgoingMessageTx(callId, fromTag, targetUser, requestUri, body.toJson(), messageId);
        pendingMessageTx.put(callId, tx);
        Log.i(TAG, "Create MESSAGE tx callId=" + callId
                + ", messageId=" + messageId
                + ", target=" + targetUser
                + ", bodyLength=" + tx.bodyJson.length()
                + ", msgType=" + body.getMsgType());
        sendMessageRequest(tx, MESSAGE_CONTENT_TYPE_JSON);
    }

    private void sendMessageRequest(OutgoingMessageTx tx, String contentType) throws Exception {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        hdrs.put("From", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">;tag=" + tx.fromTag);
        hdrs.put("To", "<sip:" + tx.targetUser + "@" + config.getSipServerHost() + ">");
        hdrs.put("Call-ID", tx.callId);
        hdrs.put("CSeq", tx.cseq + " MESSAGE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String msg = buildSipMessage("MESSAGE " + tx.requestUri + " SIP/2.0", hdrs, contentType, tx.bodyJson);
        send(msg);
        Log.w(TAG, "MESSAGE sent to " + tx.targetUser
                + ", callId=" + tx.callId
                + ", cseq=" + tx.cseq
                + ", contentType=" + contentType
                + ", bodyLength=" + tx.bodyJson.length());
    }
    // ============== 閸欐垼鎹ｉ柅姘崇樈 ==============

    public void makeCall(String targetUser, boolean videoEnabled) throws Exception {
        currentCallId = uuid();
        currentFromTag = shortUuid();
        currentToTag = null;
        lastInviteFailureKey = null;
        String sdp = buildSDP(videoEnabled);

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        hdrs.put("From", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">;tag=" + currentFromTag);
        hdrs.put("To", "<sip:" + targetUser + "@" + config.getSipServerHost() + ">");
        hdrs.put("Call-ID", currentCallId);
        hdrs.put("CSeq", "1 INVITE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String requestUri = getRequestUri(targetUser);
        String msg = buildSipMessage("INVITE " + requestUri + " SIP/2.0", hdrs, "application/sdp", sdp);
        send(msg);
        Log.w(TAG, "INVITE 閸欐垿鈧礁鍩? " + targetUser);
    }

    // ============== 閹恒儱鎯夐弶銉ф暩 ==============

    public void answerCall(IncomingInvite invite, boolean videoEnabled) throws Exception {
        String sdp = buildSDP(videoEnabled);

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", invite.viaHeader);
        hdrs.put("From", invite.fromHeader);
        hdrs.put("To", invite.toHeader + (invite.toTag != null ? "" : ";tag=" + shortUuid()));
        hdrs.put("Call-ID", invite.callId);
        hdrs.put("CSeq", invite.cseq + " INVITE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String resp = buildSipMessage("SIP/2.0 200 OK", hdrs, "application/sdp", sdp);
        sendTo(resp, invite.sourceAddress, invite.sourcePort);

        currentCallId = invite.callId;
        inCall = true;
        Log.w(TAG, "200 OK 瀹告彃褰傞柅渚婄礉閹恒儱鎯夐弶銉ф暩");
    }

    // ============== 閹锋帞绮烽弶銉ф暩 ==============

    public void rejectCall(IncomingInvite invite) throws Exception {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", invite.viaHeader);
        hdrs.put("From", invite.fromHeader);
        hdrs.put("To", invite.toHeader + ";tag=" + shortUuid());
        hdrs.put("Call-ID", invite.callId);
        hdrs.put("CSeq", invite.cseq + " INVITE");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String resp = buildSipMessage("SIP/2.0 603 Decline", hdrs, null, null);
        sendTo(resp, invite.sourceAddress, invite.sourcePort);
        Log.w(TAG, "Incoming call rejected");
    }

    // ============== 閹稿倹鏌?==============

    public void hangup() throws Exception {
        if (currentCallId == null) return;

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        hdrs.put("From", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">;tag=" + currentFromTag);
        hdrs.put("To", "<sip:remote@" + config.getSipServerHost() + ">" + (currentToTag != null ? ";tag=" + currentToTag : ""));
        hdrs.put("Call-ID", currentCallId);
        hdrs.put("CSeq", "2 BYE");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String msg = buildSipMessage("BYE sip:" + config.getSipServerHost() + ":" + config.getSipServerPort() + " SIP/2.0", hdrs, null, null);
        send(msg);

        inCall = false;
        currentCallId = null;
        Log.w(TAG, "BYE sent");
    }

    public void addUserContact(String username, String ip, int port) {
        userContacts.put(username, ip + ":" + port);
    }

    // ============== 婢跺嫮鎮婇弨璺哄煂閻ㄥ嫭绉烽幁?==============

    private void handleIncoming(String raw, InetAddress sourceAddr, int sourcePort) {
        String[] parts = raw.split("\r\n\r\n", 2);
        if (parts.length == 0) return;
        String head = parts[0];
        String body = parts.length > 1 ? parts[1] : null;

        String[] lines = head.split("\r\n");
        if (lines.length == 0) return;
        String startLine = lines[0].trim();

        // 鐟欙絾鐎?headers
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                String key = lines[i].substring(0, idx).trim();
                String value = lines[i].substring(idx + 1).trim();
                headers.put(key, value);
            }
        }

        try {
            if (startLine.startsWith("SIP/2.0")) {
                // 閸濆秴绨?
                String[] sp = startLine.split("\\s+", 3);
                int statusCode = Integer.parseInt(sp[1]);
                String cseqHeader = getHeader(headers, "CSeq");
                if (cseqHeader == null) return;

                String method = extractCSeqMethod(cseqHeader);
                if ("REGISTER".equals(method)) {
                    handleRegisterResponse(headers, statusCode);
                } else if ("INVITE".equals(method)) {
                    handleInviteResponse(headers, statusCode, body, sourceAddr, sourcePort);
                } else if ("MESSAGE".equals(method)) {
                    handleMessageResponse(headers, statusCode);
                }
            } else {
                // 鐠囬攱鐪?
                String[] sp = startLine.split("\\s+", 3);
                if (sp.length < 2) return;
                String method = sp[0].toUpperCase();

                switch (method) {
                    case "INVITE":
                        handleIncomingInvite(headers, body, sourceAddr, sourcePort);
                        break;
                    case "ACK":
                        // ACK 绾喛顓婚柅姘崇樈瀵よ櫣鐝?
                        for (SipEventListener l : listeners) l.onCallConnected(null);
                        break;
                    case "BYE":
                        handleIncomingBye(headers, sourceAddr, sourcePort);
                        break;
                    case "MESSAGE":
                        handleIncomingMessage(headers, body, sourceAddr, sourcePort);
                        break;
                    case "CANCEL":
                        handleIncomingCancel(headers, sourceAddr, sourcePort);
                        break;
                    case "OPTIONS":
                        sendResponse(headers, 200, "OK", sourceAddr, sourcePort);
                        break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle incoming SIP message", e);
        }
    }

    private void handleInviteResponse(Map<String, String> headers, int statusCode, String body,
                                      InetAddress sourceAddr, int sourcePort) {
        if (statusCode == 100) {
            return;
        }

        if (statusCode == 180) {
            for (SipEventListener l : listeners) l.onCallRinging();
            return;
        }

        if (statusCode == 200) {
            String toHeader = getHeader(headers, "To");
            if (toHeader != null && toHeader.contains("tag=")) {
                currentToTag = extractTag(toHeader);
            }
            try {
                sendAck(headers, sourceAddr, sourcePort);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send ACK for 200 INVITE", e);
            }
            inCall = true;
            lastInviteFailureKey = null;
            for (SipEventListener l : listeners) l.onCallConnected(body);
            return;
        }

        if (statusCode >= 400) {
            try {
                sendAck(headers, sourceAddr, sourcePort);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send ACK for non-2xx INVITE", e);
            }

            String callId = getHeader(headers, "Call-ID");
            int cseq = parseCSeqNumber(getHeader(headers, "CSeq"));
            String failureKey = (callId != null ? callId : "") + "|" + cseq + "|" + statusCode;

            if (!failureKey.equals(lastInviteFailureKey)) {
                if (statusCode == 486) {
                    for (SipEventListener l : listeners) l.onCallFailed("鐎佃鏌熻箛?");
                } else if (statusCode == 603) {
                    for (SipEventListener l : listeners) l.onCallFailed("鐎佃鏌熼幏鎺旂卜");
                } else {
                    for (SipEventListener l : listeners) l.onCallFailed("閸涚厧褰ㄦ径杈Е: " + statusCode);
                }
                lastInviteFailureKey = failureKey;
            }

            inCall = false;
            currentCallId = null;
            currentToTag = null;
        }
    }

    private void handleMessageResponse(Map<String, String> headers, int statusCode) {
        String callId = getHeader(headers, "Call-ID");
        if (callId == null || callId.isEmpty()) {
            return;
        }

        OutgoingMessageTx tx = pendingMessageTx.get(callId);
        if (tx == null) {
            return;
        }

        if (statusCode >= 200 && statusCode < 300) {
            pendingMessageTx.remove(callId);
            Log.w(TAG, "MESSAGE delivered, callId=" + callId + ", status=" + statusCode);
            notifyMessageStatus(tx.messageId, Message.MessageStatus.SENT, null);
            return;
        }

        if (statusCode == 406 && !tx.retriedAsTextPlain) {
            tx.retriedAsTextPlain = true;
            tx.cseq++;
            try {
                sendMessageRequest(tx, MESSAGE_CONTENT_TYPE_TEXT);
                Log.w(TAG, "MESSAGE got 406, retried with contentType=" + MESSAGE_CONTENT_TYPE_TEXT
                        + ", callId=" + callId);
            } catch (Exception e) {
                pendingMessageTx.remove(callId);
                Log.e(TAG, "MESSAGE 406 fallback retry failed, callId=" + callId, e);
                notifyMessageStatus(tx.messageId, Message.MessageStatus.FAILED, "406 fallback retry failed");
            }
            return;
        }

        pendingMessageTx.remove(callId);
        Log.w(TAG, "MESSAGE failed, status=" + statusCode
                + ", callId=" + callId
                + ", retried=" + tx.retriedAsTextPlain);
        notifyMessageStatus(tx.messageId, Message.MessageStatus.FAILED, "SIP " + statusCode);
    }

    private void sendAck(Map<String, String> headers, InetAddress addr, int port) throws Exception {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        String via = getHeader(headers, "Via");
        if (via != null && !via.isEmpty()) {
            hdrs.put("Via", via);
        } else {
            hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        }
        hdrs.put("Max-Forwards", "70");
        String fromH = getHeader(headers, "From");
        String toH = getHeader(headers, "To");
        if (fromH != null) hdrs.put("From", fromH);
        if (toH != null) hdrs.put("To", toH);
        hdrs.put("Call-ID", currentCallId != null ? currentCallId : getHeader(headers, "Call-ID"));

        int cseqNum = parseCSeqNumber(getHeader(headers, "CSeq"));
        if (cseqNum <= 0) cseqNum = 1;
        hdrs.put("CSeq", cseqNum + " ACK");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String requestUri = extractSipUri(toH);
        if (requestUri == null || requestUri.isEmpty()) {
            requestUri = "sip:" + config.getSipServerHost() + ":" + config.getSipServerPort();
        }

        String msg = buildSipMessage("ACK " + requestUri + " SIP/2.0", hdrs, null, null);
        if (addr != null && port > 0) {
            sendTo(msg, addr, port);
        } else {
            send(msg);
        }
    }
    private void handleIncomingInvite(Map<String, String> headers, String body, InetAddress sourceAddr, int sourcePort) throws Exception {
        // 閸忓牆褰?180 Ringing
        sendResponse(headers, 180, "Ringing", sourceAddr, sourcePort);

        // 閹绘劕褰囬弶銉ф暩娣団剝浼?
        String fromHeader = getHeader(headers, "From");
        String fromUser = extractUser(fromHeader);
        String callId = getHeader(headers, "Call-ID");
        String cseqHeader = getHeader(headers, "CSeq");
        int cseq = parseCSeqNumber(cseqHeader);

        IncomingInvite invite = new IncomingInvite();
        invite.fromUser = fromUser;
        invite.sdp = body;
        invite.callId = callId;
        invite.cseq = cseq;
        invite.viaHeader = getHeader(headers, "Via");
        invite.fromHeader = fromHeader;
        invite.toHeader = getHeader(headers, "To");
        invite.toTag = extractTag(invite.toHeader);
        invite.sourceAddress = sourceAddr;
        invite.sourcePort = sourcePort;

        pendingIncomingInvite = invite;

        for (SipEventListener l : listeners) {
            l.onIncomingCall(fromUser, body, invite);
        }
    }

    private void handleIncomingBye(Map<String, String> headers, InetAddress sourceAddr, int sourcePort) throws Exception {
        sendResponse(headers, 200, "OK", sourceAddr, sourcePort);
        inCall = false;
        currentCallId = null;
        for (SipEventListener l : listeners) l.onCallEnded();
    }

    private void handleIncomingMessage(Map<String, String> headers, String body, InetAddress sourceAddr, int sourcePort) throws Exception {
        sendResponse(headers, 200, "OK", sourceAddr, sourcePort);

        String fromHeader = getHeader(headers, "From");
        String fromUser = extractUser(fromHeader);

        if (body != null && !body.isEmpty()) {
            SipMessageBody message;
            String trimmedBody = body.trim();
            if (trimmedBody.startsWith("{")) {
                message = SipMessageBody.fromJson(trimmedBody);
            } else {
                message = SipMessageBody.createTextMessage(fromUser, config.getUsername(), body);
            }
            Log.w(TAG, "閺€璺哄煂 MESSAGE from: " + fromUser);
            for (SipEventListener l : listeners) {
                l.onMessageReceived(fromUser, message);
            }
        }
    }

    private void handleIncomingCancel(Map<String, String> headers, InetAddress sourceAddr, int sourcePort) throws Exception {
        sendResponse(headers, 200, "OK", sourceAddr, sourcePort);
        for (SipEventListener l : listeners) l.onCallEnded();
    }

    private void notifyMessageStatus(String messageId, Message.MessageStatus status, String reason) {
        for (SipEventListener listener : listeners) {
            listener.onMessageStatusChanged(messageId, status, reason);
        }
    }

    private void sendResponse(Map<String, String> headers, int code, String reason, InetAddress addr, int port) throws Exception {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        String via = getHeader(headers, "Via");
        if (via != null) hdrs.put("Via", via);
        String from = getHeader(headers, "From");
        if (from != null) hdrs.put("From", from);
        String to = getHeader(headers, "To");
        if (to != null) {
            if (!to.contains("tag=")) {
                to += ";tag=" + shortUuid();
            }
            hdrs.put("To", to);
        }
        String callId = getHeader(headers, "Call-ID");
        if (callId != null) hdrs.put("Call-ID", callId);
        String cseq = getHeader(headers, "CSeq");
        if (cseq != null) hdrs.put("CSeq", cseq);
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String resp = buildSipMessage("SIP/2.0 " + code + " " + reason, hdrs, null, null);
        sendTo(resp, addr, port);
    }

    // ============== Keep-Alive ==============

    private synchronized void scheduleRegisterTimeout(String callId, int cseq) {
        cancelRegisterTimeout();
        registerTimeoutFuture = scheduler.schedule(() -> {
            if (registered.get()) return;
            if (!Objects.equals(registerCallId, callId) || registerCseq != cseq) return;
            Log.w(TAG, "REGISTER timeout, no response within " + REGISTER_TIMEOUT_MS + " ms");
            for (SipEventListener l : listeners) {
                l.onRegisterFailed("Register timeout, please check server, credentials, or network");
            }
        }, REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelRegisterTimeout() {
        if (registerTimeoutFuture != null) {
            registerTimeoutFuture.cancel(false);
            registerTimeoutFuture = null;
        }
    }

    private void startKeepAlive() {
        if (!keepAliveRunning.compareAndSet(false, true)) return;
        keepAliveThread = new Thread(() -> {
            int optionsCseq = 1;
            long lastOptionsAt = 0;
            while (running.get() && keepAliveRunning.get()) {
                try {
                    if (!registered.get()) { Thread.sleep(500); continue; }
                    long now = System.currentTimeMillis();
                    if (now - lastOptionsAt >= 20_000) {
                        send(buildOptionsRequest(optionsCseq++));
                        lastOptionsAt = now;
                    }
                    Thread.sleep(500);
                } catch (Exception e) { /* ignore */ }
            }
        }, "sip-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    // ============== 閺嬪嫬缂?SIP 濞戝牊浼?==============

    private String buildRegisterRequest(int expires, String callId, int cseq, String authHeader) {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        String fromTag = (registerFromTag != null) ? registerFromTag : shortUuid();
        registerFromTag = fromTag;
        hdrs.put("From", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">;tag=" + fromTag);
        hdrs.put("To", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">");
        hdrs.put("Call-ID", callId);
        hdrs.put("CSeq", cseq + " REGISTER");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ";transport=udp>");
        hdrs.put("Expires", String.valueOf(expires));
        hdrs.put("User-Agent", "SipVideoChat-Android");
        if (authHeader != null && !authHeader.isEmpty()) {
            hdrs.put("Authorization", authHeader);
        }
        return buildSipMessage("REGISTER sip:" + config.getSipServerHost() + ":" + config.getSipServerPort() + " SIP/2.0", hdrs, null, null);
    }

    private String buildOptionsRequest(int cseq) {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        hdrs.put("From", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">;tag=" + shortUuid());
        hdrs.put("To", "<sip:" + config.getSipServerHost() + ">");
        hdrs.put("Call-ID", uuid());
        hdrs.put("CSeq", cseq + " OPTIONS");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");
        return buildSipMessage("OPTIONS sip:" + config.getSipServerHost() + ":" + config.getSipServerPort() + " SIP/2.0", hdrs, null, null);
    }

    private String buildSDP(boolean videoEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("v=0\r\n");
        sb.append("o=").append(config.getUsername()).append(" 123456 654321 IN IP4 ").append(config.getLocalIp()).append("\r\n");
        sb.append("s=IM Call\r\n");
        sb.append("c=IN IP4 ").append(config.getLocalIp()).append("\r\n");
        sb.append("t=0 0\r\n");
        sb.append("m=audio ").append(config.getLocalAudioPort()).append(" RTP/AVP 0 8 101\r\n");
        sb.append("a=rtpmap:0 PCMU/8000\r\n");
        sb.append("a=rtpmap:8 PCMA/8000\r\n");
        sb.append("a=rtpmap:101 telephone-event/8000\r\n");
        if (videoEnabled) {
            sb.append("m=video ").append(config.getLocalVideoPort()).append(" RTP/AVP 96\r\n");
            sb.append("a=rtpmap:96 VP8/90000\r\n");
        }
        return sb.toString();
    }

    // ============== 缂冩垹绮堕崣鎴︹偓?==============

    private void send(String msg) {
        try {
            DatagramSocket s = socket;
            int localPort = (s != null) ? s.getLocalPort() : -1;
            Log.d(TAG, ">> " + msg.split("\r\n")[0]
                    + " via local " + config.getLocalIp() + ":" + localPort
                    + " to " + config.getSipServerHost() + ":" + config.getSipServerPort());
            byte[] data = msg.getBytes("UTF-8");
            InetAddress addr = InetAddress.getByName(config.getSipServerHost());
            if (s != null && !s.isClosed()) {
                s.send(new DatagramPacket(data, data.length, addr, config.getSipServerPort()));
            }
        } catch (Exception e) {
            Log.w(TAG, "send failed: " + e.getMessage());
        }
    }

    private void sendTo(String msg, InetAddress addr, int port) {
        try {
            Log.d(TAG, ">> " + msg.split("\r\n")[0] + " to " + addr + ":" + port);
            byte[] data = msg.getBytes("UTF-8");
            DatagramSocket s = socket;
            if (s != null && !s.isClosed()) {
                s.send(new DatagramPacket(data, data.length, addr, port));
            }
        } catch (Exception e) {
            Log.w(TAG, "sendTo failed: " + e.getMessage());
        }
    }

    // ============== SIP 濞戝牊浼呮惔蹇撳灙閸?==============

    private String buildSipMessage(String startLine, LinkedHashMap<String, String> headers, String contentType, String body) {
        String b = (body != null) ? body : "";
        if (contentType != null && !contentType.isEmpty()) {
            headers.put("Content-Type", contentType);
        }
        try {
            headers.put("Content-Length", String.valueOf(b.getBytes("UTF-8").length));
        } catch (Exception e) {
            headers.put("Content-Length", String.valueOf(b.length()));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(startLine).append("\r\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        if (!b.isEmpty()) sb.append(b);
        return sb.toString();
    }

    // ============== 瀹搞儱鍙块弬瑙勭《 ==============

    private String getRequestUri(String targetUser) {
        String contact = userContacts.get(targetUser);
        if (contact != null) {
            String[] parts = contact.split(":");
            return "sip:" + targetUser + "@" + parts[0] + ":" + parts[1];
        }
        return "sip:" + targetUser + "@" + config.getSipServerHost() + ":" + config.getSipServerPort();
    }

    private String getHeader(Map<String, String> headers, String name) {
        // 婢堆冪毈閸愭瑤绗夐弫蹇斿妳閺屻儲澹?
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String extractUser(String headerValue) {
        if (headerValue == null) return "unknown";
        // 娴?<sip:user@host> 閹?sip:user@host 娑擃厽褰侀崣?user
        int sipIdx = headerValue.indexOf("sip:");
        if (sipIdx < 0) return headerValue;
        String s = headerValue.substring(sipIdx + 4);
        int atIdx = s.indexOf('@');
        if (atIdx > 0) s = s.substring(0, atIdx);
        // 閸樼粯甯€閸欘垵鍏橀惃?> 缂佹挸鐔?
        s = s.replace(">", "").trim();
        return s;
    }

    private String extractTag(String headerValue) {
        if (headerValue == null) return null;
        int idx = headerValue.indexOf("tag=");
        if (idx < 0) return null;
        String s = headerValue.substring(idx + 4);
        int end = s.indexOf(';');
        if (end > 0) s = s.substring(0, end);
        return s.trim();
    }

    private String extractSipUri(String headerValue) {
        if (headerValue == null) return null;
        int sipIdx = headerValue.indexOf("sip:");
        if (sipIdx < 0) return null;
        int end = headerValue.indexOf('>', sipIdx);
        if (end < 0) end = headerValue.indexOf(';', sipIdx);
        if (end < 0) end = headerValue.length();
        return headerValue.substring(sipIdx, end).trim();
    }

    private String extractCSeqMethod(String cseqHeader) {
        if (cseqHeader == null) return "";
        String[] parts = cseqHeader.trim().split("\\s+");
        return parts.length >= 2 ? parts[1].toUpperCase() : "";
    }

    private int parseCSeqNumber(String cseqHeader) {
        if (cseqHeader == null) return 0;
        String[] parts = cseqHeader.trim().split("\\s+");
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String branch() {
        return "z9hG4bK" + UUID.randomUUID().toString().replace("-", "");
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    // ============== Digest 鐠併倛鐦?==============

    private String buildDigestAuth(String wwwAuth, String method, String uri, String username, String password) {
        Map<String, String> params = parseDigestParams(wwwAuth);
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String qop = params.get("qop");
        String nc = "00000001";
        String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String response;
        if (qop != null && !qop.isEmpty() && qop.contains("auth")) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Digest ");
        sb.append("username=\"").append(username).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        if (qop != null && !qop.isEmpty() && qop.contains("auth")) {
            sb.append("qop=auth, ");
            sb.append("nc=").append(nc).append(", ");
            sb.append("cnonce=\"").append(cnonce).append("\", ");
        }
        sb.append("response=\"").append(response).append("\", ");
        sb.append("algorithm=MD5");
        return sb.toString();
    }

    private Map<String, String> parseDigestParams(String header) {
        int idx = header.toLowerCase().indexOf("digest");
        String s = (idx >= 0) ? header.substring(idx + 6) : header;
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : s.split(",")) {
            String kv = p.trim();
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim().toLowerCase();
            String v = kv.substring(eq + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            out.put(k, v);
        }
        return out;
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ============== Getters ==============

    public boolean isRegistered() { return registered.get(); }
    public boolean isInCall() { return inCall; }
    public ClientConfig getConfig() { return config; }
    public Set<String> getKnownContacts() { return new HashSet<>(userContacts.keySet()); }

    private static class OutgoingMessageTx {
        final String callId;
        final String fromTag;
        final String targetUser;
        final String requestUri;
        final String bodyJson;
        final String messageId;
        int cseq = 1;
        boolean retriedAsTextPlain = false;

        OutgoingMessageTx(String callId, String fromTag, String targetUser, String requestUri, String bodyJson,
                          String messageId) {
            this.callId = callId;
            this.fromTag = fromTag;
            this.targetUser = targetUser;
            this.requestUri = requestUri;
            this.bodyJson = bodyJson;
            this.messageId = messageId;
        }
    }

    // ============== 閺夈儳鏁搁弫鐗堝祦 ==============

    public static class IncomingInvite {
        public String fromUser;
        public String sdp;
        public String callId;
        public int cseq;
        public String viaHeader;
        public String fromHeader;
        public String toHeader;
        public String toTag;
        public InetAddress sourceAddress;
        public int sourcePort;
    }
}


