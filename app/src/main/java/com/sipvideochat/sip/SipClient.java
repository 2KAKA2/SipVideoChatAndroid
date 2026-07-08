package com.sipvideochat.sip;

import android.util.Log;

import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.media.LocalMediaServer;
import com.sipvideochat.model.Message;
import com.sipvideochat.protocol.SipMessageBody;
import com.sipvideochat.util.DiagnosticLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SipClient {
    private static final String TAG = "SipClient";
    private static final String MESSAGE_CONTENT_TYPE_JSON = "application/json";
    private static final String MESSAGE_CONTENT_TYPE_TEXT = "text/plain;charset=UTF-8";
    private static final String HEADER_CALL_TYPE = "X-SipVideoChat-Call-Type";
    private static final String HEADER_WEBRTC_SDP_URL = "X-SipVideoChat-WebRTC-Sdp-Url";
    private static final String CALL_TYPE_VIDEO = "video";
    private static final int REMOTE_SDP_FETCH_TIMEOUT_MS = 5000;
    private static final int PC_COMPAT_SIP_PORT = 5062;

    // IMS 必选头
    private static final String HEADER_PANI = "P-Access-Network-Info";
    private static final String PANI_LTE = "3GPP-E-UTRAN; utran-cell-id-3gpp=0010100000000001";
    private static final String HEADER_SECURITY_CLIENT = "Security-Client";
    private static final String HEADER_SUPPORTED_IMS = "Supported";
    private static final String SUPPORTED_IMS_VAL = "100rel, precondition, timer";

    private final ClientConfig config;
    private final List<SipEventListener> listeners = new ArrayList<>();

    // UDP Socket
    private DatagramSocket socket;
    private DatagramSocket compatibilitySocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> registerTimeoutFuture;
    private ScheduledFuture<?> inviteTimeoutFuture;
    private static final long REGISTER_TIMEOUT_MS = 8000;
    private static final long MESSAGE_TIMEOUT_MS = 8000;
    private static final long INVITE_TIMEOUT_MS = 12000;
    private static final int MAX_SAFE_UDP_SIP_BYTES = 1200;

    private String registerCallId;
    private String registerFromTag;
    private int registerCseq = 1;
    private boolean registerAuthAttempted = false;
    private long lastRegisterSuccessAtMs;
    private long registrationExpiresAtMs;

    private String currentCallId;
    private String currentFromTag;
    private String currentToTag;
    private String currentRemoteUri;
    private int currentLocalCseq = 1;
    private String currentInviteBranch;
    private boolean currentInviteVideo;
    private int currentInviteBodyBytes;
    private int currentInvitePacketBytes;
    private String remoteContact;
    private boolean inCall = false;
    private boolean outgoingInvitePending = false;
    private String lastInviteFailureKey;

    private IncomingInvite pendingIncomingInvite;

    private final Map<String, String> userContacts = new HashMap<>();
    private final Map<String, OutgoingMessageTx> pendingMessageTx = new ConcurrentHashMap<>();

    // Keep-alive
    private final AtomicBoolean keepAliveRunning = new AtomicBoolean(false);
    private Thread keepAliveThread;
    private Thread recvThread;
    private Thread compatibilityRecvThread;

    public SipClient(ClientConfig config) {
        this.config = config;
    }

    public void addListener(SipEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SipEventListener listener) {
        listeners.remove(listener);
    }


    public synchronized void init() throws Exception {
        if (socket != null) return;

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(config.getLocalSipPort()));
        running.set(true);

        // UDP 接收线程
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

        // TCP 接收线程 (P-CSCF 用 TCP 投递来电 INVITE)
        java.net.ServerSocket tcpServer = new java.net.ServerSocket();
        tcpServer.setReuseAddress(true);
        tcpServer.bind(new java.net.InetSocketAddress(config.getLocalSipPort()));
        Thread tcpThread = new Thread(() -> {
            while (running.get()) {
                try {
                    java.net.Socket client = tcpServer.accept();
                    new Thread(() -> {
                        try {
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(client.getInputStream(), "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            int contentLength = 0;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                sb.append(line).append("\r\n");
                                if (line.toLowerCase().startsWith("content-length:")) {
                                    contentLength = Integer.parseInt(line.substring(15).trim());
                                }
                            }
                            sb.append("\r\n");
                            if (contentLength > 0) {
                                char[] body = new char[contentLength];
                                reader.read(body, 0, contentLength);
                                sb.append(body);
                            }
                            String msg = sb.toString();
                            Log.d(TAG, "<<[tcp] " + msg.split("\r\n")[0] + " from " + client.getInetAddress().getHostAddress());
                            handleIncoming(msg, client.getInetAddress(), client.getPort());
                            client.close();
                        } catch (Exception e) {
                            if (running.get()) Log.w(TAG, "tcp recv error: " + e.getMessage());
                        }
                    }, "sip-tcp-recv").start();
                } catch (Exception e) {
                    if (running.get()) Log.w(TAG, "tcp accept error: " + e.getMessage());
                }
            }
        }, "sip-tcp-accept");
        tcpThread.setDaemon(true);
        tcpThread.start();
        startCompatibilityReceiverIfNeeded();

        Log.w(TAG, "SIP Client started: " + config.getLocalIp() + ":" + config.getLocalSipPort());

        register();
    }

    public void shutdown() {
        running.set(false);
        keepAliveRunning.set(false);
        registered.set(false);
        cancelInviteTimeout();
        resetCallState();
        for (OutgoingMessageTx tx : pendingMessageTx.values()) {
            cancelMessageTimeout(tx);
        }
        pendingMessageTx.clear();
        registerCallId = null;
        registerFromTag = null;
        registerAuthAttempted = false;
        cancelRegisterTimeout();
        try {
            if (socket != null) socket.close();
        } catch (Exception e) { /* ignore */ }
        try {
            if (compatibilitySocket != null) compatibilitySocket.close();
        } catch (Exception e) { /* ignore */ }
        socket = null;
        compatibilitySocket = null;
        scheduler.shutdownNow();
    }


    private void startCompatibilityReceiverIfNeeded() {
        if (config.getLocalSipPort() == PC_COMPAT_SIP_PORT || compatibilitySocket != null) {
            return;
        }
        try {
            compatibilitySocket = new DatagramSocket(PC_COMPAT_SIP_PORT);
        } catch (Exception e) {
            Log.w(TAG, "Compat SIP listener unavailable on port " + PC_COMPAT_SIP_PORT + ": " + e.getMessage());
            DiagnosticLog.w(TAG, "compat sip listener unavailable port=" + PC_COMPAT_SIP_PORT + ": " + e.getMessage());
            compatibilitySocket = null;
            return;
        }
        compatibilityRecvThread = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            while (running.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    DatagramSocket s = compatibilitySocket;
                    if (s == null) break;
                    s.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                    Log.d(TAG, "<<[compat] " + msg.split("\r\n")[0] + " from "
                            + pkt.getAddress().getHostAddress() + ":" + pkt.getPort());
                    handleIncoming(msg, pkt.getAddress(), pkt.getPort());
                } catch (Exception e) {
                    if (running.get()) {
                        Log.w(TAG, "compat recv error: " + e.getMessage());
                    }
                }
            }
        }, "sip-recv-compat-5062");
        compatibilityRecvThread.setDaemon(true);
        compatibilityRecvThread.start();
        Log.i(TAG, "Compat SIP listener started on port " + PC_COMPAT_SIP_PORT);
        DiagnosticLog.i(TAG, "compat sip listener started port=" + PC_COMPAT_SIP_PORT);
    }

    public void register() throws Exception {
        boolean hadActiveRegistration = isRegistrationAlive();
        registerCallId = (registerCallId != null) ? registerCallId : uuid();
        registerFromTag = (registerFromTag != null) ? registerFromTag : shortUuid();
        registerCseq = 1;
        registerAuthAttempted = false;
        if (!hadActiveRegistration) {
            registered.set(false);
            registrationExpiresAtMs = 0L;
        }
        String req = buildRegisterRequest(120, registerCallId, registerCseq, null);
        scheduleRegisterTimeout(registerCallId, registerCseq);
        send(req);
        Log.d(TAG, "REGISTER context user=" + config.getUsername()
                + ", local=" + config.getLocalIp() + ":" + config.getLocalSipPort()
                + ", server=" + config.getSipServerHost() + ":" + config.getSipServerPort()
                + ", hadActiveRegistration=" + hadActiveRegistration);
        Log.w(TAG, "REGISTER sent");
        DiagnosticLog.i(TAG, "register sent user=" + config.getUsername()
                + ", local=" + config.getLocalIp() + ":" + config.getLocalSipPort()
                + ", server=" + config.getSipServerHost() + ":" + config.getSipServerPort()
                + ", hadActiveRegistration=" + hadActiveRegistration);
    }

    private void handleRegisterResponse(Map<String, String> headers, int statusCode) {
        Log.i(TAG, "REGISTER response status=" + statusCode
                + ", callId=" + getHeader(headers, "Call-ID")
                + ", cseq=" + getHeader(headers, "CSeq"));
        DiagnosticLog.i(TAG, "register response status=" + statusCode
                + ", callId=" + getHeader(headers, "Call-ID")
                + ", cseq=" + getHeader(headers, "CSeq"));
        if (statusCode == 200) {
            cancelRegisterTimeout();
            registered.set(true);
            lastRegisterSuccessAtMs = System.currentTimeMillis();
            long expiresSeconds = resolveRegisterExpiresSeconds(headers);
            registrationExpiresAtMs = lastRegisterSuccessAtMs + expiresSeconds * 1000L;
            startKeepAlive();
            Log.w(TAG, "REGISTER success, expiresIn=" + expiresSeconds + "s, registeredUntil=" + registrationExpiresAtMs);
            DiagnosticLog.i(TAG, "register success expiresIn=" + expiresSeconds + "s, registeredUntil=" + registrationExpiresAtMs);
            for (SipEventListener l : listeners) l.onRegistered();
        } else if (statusCode == 401) {
            cancelRegisterTimeout();
            if (registerAuthAttempted) {
                for (SipEventListener l : listeners) l.onRegisterFailed("Digest auth rejected");
                return;
            }
            String www = getHeader(headers, "WWW-Authenticate");
            if (www == null) www = getHeader(headers, "Proxy-Authenticate");
            if (www == null || www.isEmpty()) {
                for (SipEventListener l : listeners) l.onRegisterFailed("Digest auth rejected");
                return;
            }
            try {
                registerAuthAttempted = true;
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
                Log.e(TAG, "Digest auth calculation failed", e);
                for (SipEventListener l : listeners) l.onRegisterFailed("Digest auth calculation failed");
            }
        } else {
            cancelRegisterTimeout();
            for (SipEventListener l : listeners) l.onRegisterFailed("SIP status: " + statusCode);
        }
    }


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
        scheduleMessageTimeout(tx);
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
        String fromUri = config.getFromUri();
        String toUri = config.isImsMode()
                ? "sip:" + tx.targetUser + "@" + config.getRealm()
                : "sip:" + tx.targetUser + "@" + config.getSipServerHost();
        hdrs.put("From", "<" + fromUri + ">;tag=" + tx.fromTag);
        hdrs.put("To", "<" + toUri + ">");
        hdrs.put("Call-ID", tx.callId);
        hdrs.put("CSeq", tx.cseq + " MESSAGE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");
        if (tx.authHeader != null && !tx.authHeader.isEmpty()) {
            hdrs.put("Authorization", tx.authHeader);
        }
        addImsHeaders(hdrs);

        String msg = buildSipMessage("MESSAGE " + tx.requestUri + " SIP/2.0", hdrs, contentType, tx.bodyJson);
        send(msg);
        Log.w(TAG, "MESSAGE sent to " + tx.targetUser
                + ", callId=" + tx.callId
                + ", cseq=" + tx.cseq
                + ", contentType=" + contentType
                + ", bodyLength=" + tx.bodyJson.length());
    }

    public void makeCall(String targetUser, boolean videoEnabled) throws Exception {
        makeCall(targetUser, videoEnabled, null);
    }

    public void makeCall(String targetUser, boolean videoEnabled, String customSdp) throws Exception {
        if (!isRegistrationAlive()) {
            Log.w(TAG, "Skip INVITE because registration is not active for user=" + config.getUsername());
            for (SipEventListener l : listeners) l.onCallFailed("SIP not registered");
            return;
        }
        resetCallState();
        currentCallId = uuid();
        currentFromTag = shortUuid();
        currentToTag = null;
        currentLocalCseq = 1;
        lastInviteFailureKey = null;
        outgoingInvitePending = true;
        currentInviteVideo = videoEnabled;
        String sdpUrl = null;
        String sdp;
        if (videoEnabled) {
            String offerSdp = normalizeSdpBody(customSdp);
            if (offerSdp == null || offerSdp.trim().isEmpty()) {
                throw new IllegalArgumentException("Video offer SDP unavailable");
            }
            sdpUrl = publishWebRtcSdp(offerSdp, currentCallId, "offer");
            sdp = buildVideoSignalSdp(sdpUrl);
        } else {
            sdp = normalizeSdpBody((customSdp != null && !customSdp.trim().isEmpty()) ? customSdp : buildSDP(false));
        }
        currentInviteBodyBytes = utf8Length(sdp);
        currentInviteBranch = branch();

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + currentInviteBranch + ";rport");
        hdrs.put("Max-Forwards", "70");
        String fromUri = config.getFromUri();
        String toUri = config.isImsMode()
                ? "sip:" + targetUser + "@" + config.getRealm()
                : "sip:" + targetUser + "@" + config.getSipServerHost();
        hdrs.put("From", "<" + fromUri + ">;tag=" + currentFromTag);
        hdrs.put("To", "<" + toUri + ">");
        hdrs.put("Call-ID", currentCallId);
        hdrs.put("CSeq", "1 INVITE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");
        if (videoEnabled) {
            hdrs.put(HEADER_CALL_TYPE, CALL_TYPE_VIDEO);
            if (sdpUrl != null && !sdpUrl.trim().isEmpty()) {
                hdrs.put(HEADER_WEBRTC_SDP_URL, sdpUrl);
            }
        }
        // IMS 必选头
        addImsHeaders(hdrs);

        String requestUri = getRequestUri(targetUser);
        currentRemoteUri = requestUri;
        String msg = buildSipMessage("INVITE " + requestUri + " SIP/2.0", hdrs, "application/sdp", sdp);
        currentInvitePacketBytes = utf8Length(msg);
        send(msg);
        scheduleInviteTimeout(currentCallId, currentLocalCseq, targetUser);
        Log.i(TAG, "INVITE sent target=" + targetUser
                + ", requestUri=" + requestUri
                + ", callId=" + currentCallId
                + ", cseq=" + currentLocalCseq
                + ", contentType=application/sdp"
                + ", bodyLength=" + currentInviteBodyBytes
                + ", packetBytes=" + currentInvitePacketBytes
                + ", sdpUrl=" + sdpUrl
                + ", video=" + currentInviteVideo
                + ", registrationAlive=" + isRegistrationAlive());
        DiagnosticLog.i(TAG, "invite sent target=" + targetUser
                + ", requestUri=" + requestUri
                + ", callId=" + currentCallId
                + ", bodyLength=" + currentInviteBodyBytes
                + ", packetBytes=" + currentInvitePacketBytes
                + ", sdpUrl=" + sdpUrl
                + ", video=" + currentInviteVideo);
        if (currentInvitePacketBytes > MAX_SAFE_UDP_SIP_BYTES) {
            Log.w(TAG, "INVITE packet likely exceeds safe UDP size, packetBytes=" + currentInvitePacketBytes
                    + ", threshold=" + MAX_SAFE_UDP_SIP_BYTES);
        }
        Log.w(TAG, "INVITE sent to: " + targetUser);
    }


    public void answerCall(IncomingInvite invite, boolean videoEnabled) throws Exception {
        answerCall(invite, videoEnabled, null);
    }

    public void answerCall(IncomingInvite invite, boolean videoEnabled, String customSdp) throws Exception {
        resetCallState();
        String sdpUrl = null;
        String sdp;
        if (videoEnabled) {
            String answerSdp = normalizeSdpBody(customSdp);
            if (answerSdp != null && !answerSdp.trim().isEmpty()) {
                sdpUrl = publishWebRtcSdp(answerSdp, invite.callId, "answer");
                sdp = buildVideoSignalSdp(sdpUrl);
            } else {
                // Legacy RTP video answer for peers that do not understand the WebRTC bridge.
                sdp = normalizeSdpBody(buildSDP(true));
            }
        } else {
            sdp = normalizeSdpBody((customSdp != null && !customSdp.trim().isEmpty()) ? customSdp : buildSDP(false));
        }
        String localTag = invite.toTag != null ? invite.toTag : shortUuid();
        String responseToHeader = invite.toHeader + (invite.toTag != null ? "" : ";tag=" + localTag);

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", invite.viaHeader);
        hdrs.put("From", invite.fromHeader);
        hdrs.put("To", responseToHeader);
        hdrs.put("Call-ID", invite.callId);
        hdrs.put("CSeq", invite.cseq + " INVITE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");
        if (videoEnabled) {
            hdrs.put(HEADER_CALL_TYPE, CALL_TYPE_VIDEO);
            if (sdpUrl != null && !sdpUrl.trim().isEmpty()) {
                hdrs.put(HEADER_WEBRTC_SDP_URL, sdpUrl);
            }
        }

        String resp = buildSipMessage("SIP/2.0 200 OK", hdrs, "application/sdp", sdp);
        sendTo(resp, invite.sourceAddress, invite.sourcePort);

        currentCallId = invite.callId;
        currentFromTag = extractTag(responseToHeader);
        currentToTag = extractTag(invite.fromHeader);
        currentRemoteUri = extractSipUri(invite.fromHeader);
        currentLocalCseq = Math.max(1, invite.cseq + 1);
        remoteContact = extractSipUri(invite.contactHeader);
        if (remoteContact == null || remoteContact.isEmpty()) {
            remoteContact = currentRemoteUri;
        }
        inCall = true;
        outgoingInvitePending = false;
        currentInviteBranch = null;
        lastInviteFailureKey = null;
        pendingIncomingInvite = null;
        Log.i(TAG, "Answered INVITE with 200 OK, from=" + invite.fromUser
                + ", callId=" + invite.callId
                + ", cseq=" + invite.cseq
                + ", bodyLength=" + sdp.length()
                + ", sdpUrl=" + sdpUrl
                + ", remoteContact=" + remoteContact);
        DiagnosticLog.i(TAG, "answer sent 200 OK from=" + invite.fromUser
                + ", callId=" + invite.callId
                + ", cseq=" + invite.cseq
                + ", bodyLength=" + sdp.length()
                + ", sdpUrl=" + sdpUrl
                + ", remoteContact=" + remoteContact);
        Log.w(TAG, "200 OK sent");
    }


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
        DiagnosticLog.i(TAG, "incoming call rejected callId=" + invite.callId + ", from=" + invite.fromUser);
    }


    public void hangup() throws Exception {
        if (currentCallId == null) return;

        if (outgoingInvitePending && !inCall) {
            sendCancelForPendingInvite();
            Log.i(TAG, "CANCEL sent for pending INVITE, callId=" + currentCallId
                    + ", requestUri=" + currentRemoteUri);
            resetCallState();
            return;
        }

        currentLocalCseq = Math.max(2, currentLocalCseq + 1);
        String requestUri = (remoteContact != null && !remoteContact.isEmpty())
                ? remoteContact
                : ((currentRemoteUri != null && !currentRemoteUri.isEmpty())
                ? currentRemoteUri
                : "sip:" + config.getSipServerHost() + ":" + config.getSipServerPort());
        HostPort directTarget = extractHostPort(remoteContact);
        String fromTag = currentFromTag != null ? currentFromTag : shortUuid();
        String toHeader = "<" + requestUri + ">" + (currentToTag != null ? ";tag=" + currentToTag : "");

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        String fromUri = config.getFromUri();
        hdrs.put("From", "<" + fromUri + ">;tag=" + fromTag);
        hdrs.put("To", toHeader);
        hdrs.put("Call-ID", currentCallId);
        hdrs.put("CSeq", currentLocalCseq + " BYE");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String msg = buildSipMessage("BYE " + requestUri + " SIP/2.0", hdrs, null, null);
        if (directTarget != null) {
            sendTo(msg, InetAddress.getByName(directTarget.host), directTarget.port);
        } else {
            send(msg);
        }
        String callId = currentCallId;
        resetCallState();
        Log.i(TAG, "BYE sent, callId=" + callId
                + ", requestUri=" + requestUri
                + ", directTarget=" + (directTarget != null ? directTarget.host + ":" + directTarget.port : "server"));
    }

    public void addUserContact(String username, String ip, int port) {
        userContacts.put(username, ip + ":" + port);
    }


    private void handleIncoming(String raw, InetAddress sourceAddr, int sourcePort) {
        String[] parts = raw.split("\r\n\r\n", 2);
        if (parts.length == 0) return;
        String head = parts[0];
        String body = parts.length > 1 ? parts[1] : null;

        String[] lines = head.split("\r\n");
        if (lines.length == 0) return;
        String startLine = lines[0].trim();

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
                String[] sp = startLine.split("\\s+", 3);
                int statusCode = Integer.parseInt(sp[1]);
                String cseqHeader = getHeader(headers, "CSeq");
                if (cseqHeader == null) return;

                String method = extractCSeqMethod(cseqHeader);
                if ("REGISTER".equals(method)) {
                    handleRegisterResponseCompat(headers, statusCode);
                } else if ("INVITE".equals(method)) {
                    handleInviteResponseCompat(headers, statusCode, body, sourceAddr, sourcePort);
                } else if ("MESSAGE".equals(method)) {
                    handleMessageResponse(headers, statusCode);
                }
            } else {
                String[] sp = startLine.split("\\s+", 3);
                if (sp.length < 2) return;
                String method = sp[0].toUpperCase();

                switch (method) {
                    case "INVITE":
                        handleIncomingInviteCompat(headers, body, sourceAddr, sourcePort);
                        break;
                    case "ACK":
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
            String contactHeader = getHeader(headers, "Contact");
            if (contactHeader != null && !contactHeader.isEmpty()) {
                remoteContact = extractSipUri(contactHeader);
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
                    for (SipEventListener l : listeners) l.onCallFailed("Remote busy");
                } else if (statusCode == 603) {
                    for (SipEventListener l : listeners) l.onCallFailed("Remote declined");
                } else {
                    for (SipEventListener l : listeners) l.onCallFailed("Call failed: " + statusCode);
                }
                lastInviteFailureKey = failureKey;
            }

            resetCallState();
        }
    }

    private void handleRegisterResponseCompat(Map<String, String> headers, int statusCode) {
        Log.i(TAG, "REGISTER response status=" + statusCode
                + ", callId=" + getHeader(headers, "Call-ID")
                + ", cseq=" + getHeader(headers, "CSeq"));
        // 1xx 都是临时响应，忽略不处理
        if (statusCode >= 100 && statusCode < 200) {
            return;
        }
        if (statusCode == 200) {
            cancelRegisterTimeout();
            registered.set(true);
            lastRegisterSuccessAtMs = System.currentTimeMillis();
            long expiresSeconds = resolveRegisterExpiresSeconds(headers);
            registrationExpiresAtMs = lastRegisterSuccessAtMs + expiresSeconds * 1000L;
            startKeepAlive();
            Log.i(TAG, "REGISTER success expiresIn=" + expiresSeconds + "s");
            for (SipEventListener listener : listeners) {
                listener.onRegistered();
            }
            return;
        }

        if (statusCode == 401) {
            cancelRegisterTimeout();
            if (registerAuthAttempted) {
                registered.set(false);
                registrationExpiresAtMs = 0L;
                for (SipEventListener listener : listeners) {
                    listener.onRegisterFailed("Digest auth rejected");
                }
                return;
            }
            String www = getHeader(headers, "WWW-Authenticate");
            if (www == null) {
                www = getHeader(headers, "Proxy-Authenticate");
            }
            if (www == null || www.isEmpty()) {
                registered.set(false);
                registrationExpiresAtMs = 0L;
                for (SipEventListener listener : listeners) {
                    listener.onRegisterFailed("Digest auth rejected");
                }
                return;
            }
            try {
                registerAuthAttempted = true;
                String auth = buildDigestAuth(www, "REGISTER",
                        "sip:" + config.getSipServerHost() + ":" + config.getSipServerPort(),
                        config.getUsername(), config.getPassword());
                String callId = getHeader(headers, "Call-ID");
                if (callId == null || callId.isEmpty()) {
                    callId = registerCallId;
                }
                registerCseq++;
                String req = buildRegisterRequest(120, callId, registerCseq, auth);
                scheduleRegisterTimeout(callId, registerCseq);
                send(req);
                Log.i(TAG, "Authenticated REGISTER sent callId=" + callId + ", cseq=" + registerCseq);
            } catch (Exception e) {
                registered.set(false);
                registrationExpiresAtMs = 0L;
                Log.e(TAG, "Digest auth calculation failed", e);
                for (SipEventListener listener : listeners) {
                    listener.onRegisterFailed("Digest auth calculation failed");
                }
            }
            return;
        }

        cancelRegisterTimeout();
        registered.set(false);
        registrationExpiresAtMs = 0L;
        for (SipEventListener listener : listeners) {
            listener.onRegisterFailed("Register failed: " + statusCode);
        }
    }

    private void handleInviteResponseCompat(Map<String, String> headers, int statusCode, String body,
                                            InetAddress sourceAddr, int sourcePort) {
        String callId = getHeader(headers, "Call-ID");
        int cseq = parseCSeqNumber(getHeader(headers, "CSeq"));
        String normalizedBody = normalizeSdpBody(body);
        String sdpUrl = firstNonEmpty(
                getHeader(headers, HEADER_WEBRTC_SDP_URL),
                extractWebRtcSdpUrlFromBody(normalizedBody));
        Log.i(TAG, "INVITE response status=" + statusCode
                + ", callId=" + callId
                + ", cseq=" + cseq
                + ", from=" + (sourceAddr != null ? sourceAddr.getHostAddress() : "unknown") + ":" + sourcePort
                + ", bodyLength=" + (normalizedBody != null ? normalizedBody.length() : 0)
                + ", sdpUrl=" + sdpUrl);

        if (currentCallId != null && callId != null && !callId.equals(currentCallId)) {
            Log.w(TAG, "Ignore stale INVITE response callId=" + callId + ", currentCallId=" + currentCallId);
            return;
        }

        if (statusCode >= 100 && statusCode < 200) {
            cancelInviteTimeout();
            if (statusCode == 180 || statusCode == 183) {
                for (SipEventListener listener : listeners) {
                    listener.onCallRinging();
                }
            }
            return;
        }

        if (statusCode >= 200 && statusCode < 300) {
            cancelInviteTimeout();
            String toHeader = getHeader(headers, "To");
            if (toHeader != null && toHeader.contains("tag=")) {
                currentToTag = extractTag(toHeader);
            }
            String contactHeader = getHeader(headers, "Contact");
            if (contactHeader != null && !contactHeader.isEmpty()) {
                remoteContact = extractSipUri(contactHeader);
            }
            try {
                sendAck(headers, sourceAddr, sourcePort);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send ACK for 2xx INVITE", e);
            }
            String resolvedSdp = normalizedBody;
            if (currentInviteVideo && sdpUrl != null && !sdpUrl.trim().isEmpty()) {
                try {
                    resolvedSdp = fetchRemoteWebRtcSdp(sdpUrl);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to fetch remote video answer from " + sdpUrl, e);
                    DiagnosticLog.e(TAG, "failed to fetch remote video answer url=" + sdpUrl, e);
                    for (SipEventListener listener : listeners) {
                        listener.onCallFailed("Failed to resolve remote video answer");
                    }
                    resetCallState();
                    return;
                }
            }
            inCall = true;
            outgoingInvitePending = false;
            lastInviteFailureKey = null;
            for (SipEventListener listener : listeners) {
                listener.onCallConnected(resolvedSdp);
            }
            return;
        }

        cancelInviteTimeout();
        outgoingInvitePending = false;
        try {
            sendAck(headers, sourceAddr, sourcePort);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send ACK for non-2xx INVITE", e);
        }
        String failureKey = (callId != null ? callId : "") + "|" + cseq + "|" + statusCode;
        if (!failureKey.equals(lastInviteFailureKey)) {
            notifyInviteFailure(statusCode);
            lastInviteFailureKey = failureKey;
        }
        resetCallState();
    }

    private void handleIncomingInviteCompat(Map<String, String> headers, String body,
                                            InetAddress sourceAddr, int sourcePort) throws Exception {
        String contentType = getHeader(headers, "Content-Type");
        String normalizedBody = normalizeSdpBody(body);
        String remoteSdpUrl = firstNonEmpty(
                getHeader(headers, HEADER_WEBRTC_SDP_URL),
                extractWebRtcSdpUrlFromBody(normalizedBody));
        boolean videoHint = CALL_TYPE_VIDEO.equalsIgnoreCase(getHeader(headers, HEADER_CALL_TYPE))
                || CALL_TYPE_VIDEO.equalsIgnoreCase(extractCallTypeFromBody(normalizedBody))
                || (remoteSdpUrl != null && !remoteSdpUrl.trim().isEmpty());
        int bodyBytes = utf8Length(normalizedBody);
        InviteSdpInfo placeholderInfo = inspectInviteSdp(normalizedBody);
        Log.i(TAG, "Incoming INVITE raw contentType=" + contentType
                + ", bodyBytes=" + bodyBytes
                + ", hasAudio=" + placeholderInfo.hasAudio
                + ", hasVideo=" + placeholderInfo.hasVideo
                + ", validSdp=" + placeholderInfo.valid
                + ", parseResult=" + placeholderInfo.parseResult
                + ", videoHint=" + videoHint
                + ", sdpUrl=" + remoteSdpUrl
                + ", firstLine=" + firstSdpLine(normalizedBody));
        DiagnosticLog.i(TAG, "incoming invite raw contentType=" + contentType
                + ", bodyBytes=" + bodyBytes
                + ", hasAudio=" + placeholderInfo.hasAudio
                + ", hasVideo=" + placeholderInfo.hasVideo
                + ", validSdp=" + placeholderInfo.valid
                + ", parseResult=" + placeholderInfo.parseResult
                + ", videoHint=" + videoHint
                + ", sdpUrl=" + remoteSdpUrl
                + ", firstLine=" + firstSdpLine(normalizedBody));

        if (!isSupportedInviteContentType(contentType)) {
            Log.w(TAG, "Reject incoming INVITE due to unsupported content type: " + contentType);
            sendResponse(headers, 415, "Unsupported Media Type", sourceAddr, sourcePort);
            return;
        }

        sendResponse(headers, 100, "Trying", sourceAddr, sourcePort);

        String resolvedSdp = normalizedBody;
        if (videoHint && remoteSdpUrl != null && !remoteSdpUrl.trim().isEmpty()) {
            try {
                resolvedSdp = fetchRemoteWebRtcSdp(remoteSdpUrl);
            } catch (Exception e) {
                Log.e(TAG, "Reject incoming video INVITE because remote SDP fetch failed: " + remoteSdpUrl, e);
                DiagnosticLog.e(TAG, "incoming video invite fetch failed url=" + remoteSdpUrl, e);
                sendResponse(headers, 488, "Not Acceptable Here", sourceAddr, sourcePort);
                return;
            }
        }

        InviteSdpInfo effectiveInfo = inspectInviteSdp(resolvedSdp);
        if (!effectiveInfo.valid || (videoHint && !effectiveInfo.hasVideo)) {
            Log.w(TAG, "Reject incoming INVITE due to invalid effective SDP: " + effectiveInfo.parseResult
                    + ", videoHint=" + videoHint
                    + ", hasVideo=" + effectiveInfo.hasVideo);
            sendResponse(headers, 488, "Not Acceptable Here", sourceAddr, sourcePort);
            return;
        }

        sendResponse(headers, 180, "Ringing", sourceAddr, sourcePort);

        String fromHeader = getHeader(headers, "From");
        String fromUser = extractUser(fromHeader);
        String callId = getHeader(headers, "Call-ID");
        String cseqHeader = getHeader(headers, "CSeq");
        int cseq = parseCSeqNumber(cseqHeader);
        String contactHeader = getHeader(headers, "Contact");
        cacheUserContact(fromUser, contactHeader);

        Log.i(TAG, "Incoming INVITE from=" + fromUser
                + ", callId=" + callId
                + ", cseq=" + cseq
                + ", source=" + sourceAddr.getHostAddress() + ":" + sourcePort
                + ", contentType=" + contentType
                + ", bodyLength=" + bodyBytes
                + ", contact=" + contactHeader
                + ", resolvedSdpLength=" + utf8Length(resolvedSdp)
                + ", detectedCallType=" + (videoHint || effectiveInfo.hasVideo ? "video" : "audio"));
        DiagnosticLog.i(TAG, "incoming invite from=" + fromUser
                + ", callId=" + callId
                + ", cseq=" + cseq
                + ", source=" + sourceAddr.getHostAddress() + ":" + sourcePort
                + ", resolvedSdpLength=" + utf8Length(resolvedSdp)
                + ", detectedCallType=" + (videoHint || effectiveInfo.hasVideo ? "video" : "audio"));

        IncomingInvite invite = new IncomingInvite();
        invite.fromUser = fromUser;
        invite.sdp = resolvedSdp;
        invite.callId = callId;
        invite.cseq = cseq;
        invite.viaHeader = getHeader(headers, "Via");
        invite.fromHeader = fromHeader;
        invite.toHeader = getHeader(headers, "To");
        invite.toTag = extractTag(invite.toHeader);
        invite.contactHeader = contactHeader;
        invite.sourceAddress = sourceAddr;
        invite.sourcePort = sourcePort;

        pendingIncomingInvite = invite;

        for (SipEventListener listener : listeners) {
            listener.onIncomingCall(fromUser, resolvedSdp, invite);
        }
    }

    private void notifyInviteFailure(int statusCode) {
        String reason;
        switch (statusCode) {
            case 408:
                reason = "Request Timeout";
                break;
            case 480:
                reason = "Temporarily Unavailable";
                break;
            case 486:
                reason = "Busy Here";
                break;
            case 487:
                reason = "Request Terminated";
                break;
            case 603:
                reason = "Declined";
                break;
            default:
                reason = "SIP " + statusCode;
                break;
        }
        for (SipEventListener listener : listeners) {
            listener.onCallFailed(reason);
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
            cancelMessageTimeout(tx);
            pendingMessageTx.remove(callId);
            Log.w(TAG, "MESSAGE delivered, callId=" + callId + ", status=" + statusCode);
            notifyMessageStatus(tx.messageId, Message.MessageStatus.SENT, null);
            return;
        }

        if ((statusCode == 401 || statusCode == 407) && !tx.retriedWithAuth) {
            String www = getHeader(headers, "WWW-Authenticate");
            if (www == null || www.isEmpty()) {
                www = getHeader(headers, "Proxy-Authenticate");
            }
            if (www != null && !www.isEmpty()) {
                tx.retriedWithAuth = true;
                tx.cseq++;
                tx.authHeader = buildDigestAuth(www, "MESSAGE", tx.requestUri,
                        config.getUsername(), config.getPassword());
                try {
                    scheduleMessageTimeout(tx);
                    sendMessageRequest(tx, MESSAGE_CONTENT_TYPE_JSON);
                    Log.w(TAG, "MESSAGE auth challenge handled, retried callId=" + callId
                            + ", status=" + statusCode);
                } catch (Exception e) {
                    cancelMessageTimeout(tx);
                    pendingMessageTx.remove(callId);
                    Log.e(TAG, "MESSAGE auth retry failed, callId=" + callId, e);
                    notifyMessageStatus(tx.messageId, Message.MessageStatus.FAILED, "Auth retry failed");
                }
                return;
            }
        }

        if (statusCode == 406 && !tx.retriedAsTextPlain) {
            tx.retriedAsTextPlain = true;
            tx.cseq++;
            try {
                scheduleMessageTimeout(tx);
                sendMessageRequest(tx, MESSAGE_CONTENT_TYPE_TEXT);
                Log.w(TAG, "MESSAGE got 406, retried with contentType=" + MESSAGE_CONTENT_TYPE_TEXT
                        + ", callId=" + callId);
            } catch (Exception e) {
                cancelMessageTimeout(tx);
                pendingMessageTx.remove(callId);
                Log.e(TAG, "MESSAGE 406 fallback retry failed, callId=" + callId, e);
                notifyMessageStatus(tx.messageId, Message.MessageStatus.FAILED, "406 fallback retry failed");
            }
            return;
        }

        cancelMessageTimeout(tx);
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

        String requestUri = extractSipUri(getHeader(headers, "Contact"));
        if (requestUri == null || requestUri.isEmpty()) {
            requestUri = extractSipUri(toH);
        }
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
        sendResponse(headers, 180, "Ringing", sourceAddr, sourcePort);

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
        String callId = getHeader(headers, "Call-ID");
        Log.i(TAG, "Incoming BYE callId=" + callId
                + ", from=" + sourceAddr.getHostAddress() + ":" + sourcePort);
        sendResponse(headers, 200, "OK", sourceAddr, sourcePort);
        if (!isCurrentCallId(callId)) {
            Log.w(TAG, "Ignore stale BYE callId=" + callId + ", currentCallId=" + currentCallId);
            DiagnosticLog.w(TAG, "ignore stale BYE callId=" + callId + ", currentCallId=" + currentCallId);
            return;
        }
        resetCallState();
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
            Log.w(TAG, "Incoming MESSAGE from: " + fromUser);
            for (SipEventListener l : listeners) {
                l.onMessageReceived(fromUser, message);
            }
        }
    }

    private void handleIncomingCancel(Map<String, String> headers, InetAddress sourceAddr, int sourcePort) throws Exception {
        String callId = getHeader(headers, "Call-ID");
        Log.i(TAG, "Incoming CANCEL callId=" + callId
                + ", cseq=" + getHeader(headers, "CSeq")
                + ", from=" + sourceAddr.getHostAddress() + ":" + sourcePort);
        sendResponse(headers, 200, "OK", sourceAddr, sourcePort);
        if (!isCurrentCallId(callId)) {
            Log.w(TAG, "Ignore stale CANCEL callId=" + callId + ", currentCallId=" + currentCallId);
            DiagnosticLog.w(TAG, "ignore stale CANCEL callId=" + callId + ", currentCallId=" + currentCallId);
            return;
        }
        resetCallState();
        for (SipEventListener l : listeners) l.onCallEnded();
    }

    private boolean isCurrentCallId(String callId) {
        if (callId == null || callId.isEmpty()) {
            return false;
        }
        if (currentCallId != null) {
            return callId.equals(currentCallId);
        }
        return pendingIncomingInvite != null && callId.equals(pendingIncomingInvite.callId);
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
        Log.i(TAG, "Send SIP response code=" + code
                + ", reason=" + reason
                + ", callId=" + callId
                + ", cseq=" + cseq
                + ", target=" + addr.getHostAddress() + ":" + port);
        DiagnosticLog.i(TAG, "send response code=" + code
                + ", reason=" + reason
                + ", callId=" + callId
                + ", cseq=" + cseq
                + ", target=" + addr.getHostAddress() + ":" + port);
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

    private synchronized void scheduleInviteTimeout(String callId, int cseq, String targetUser) {
        cancelInviteTimeout();
        inviteTimeoutFuture = scheduler.schedule(() -> {
            if (!Objects.equals(currentCallId, callId) || !outgoingInvitePending || inCall) {
                return;
            }
            String reason = currentInvitePacketBytes > MAX_SAFE_UDP_SIP_BYTES
                    ? "Invite timeout (possible UDP fragmentation)"
                    : "Invite timeout (no provisional response)";
            Log.w(TAG, "INVITE timeout target=" + targetUser
                    + ", callId=" + callId
                    + ", cseq=" + cseq
                    + ", timeoutMs=" + INVITE_TIMEOUT_MS
                    + ", packetBytes=" + currentInvitePacketBytes
                    + ", bodyBytes=" + currentInviteBodyBytes
                    + ", video=" + currentInviteVideo
                    + ", classifiedReason=" + reason);
            DiagnosticLog.e(TAG, "invite timeout target=" + targetUser
                    + ", callId=" + callId
                    + ", cseq=" + cseq
                    + ", packetBytes=" + currentInvitePacketBytes
                    + ", bodyBytes=" + currentInviteBodyBytes
                    + ", video=" + currentInviteVideo
                    + ", reason=" + reason);
            outgoingInvitePending = false;
            for (SipEventListener listener : listeners) {
                listener.onCallFailed(reason);
            }
            resetCallState();
        }, INVITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelInviteTimeout() {
        if (inviteTimeoutFuture != null) {
            inviteTimeoutFuture.cancel(false);
            inviteTimeoutFuture = null;
        }
    }

    private void scheduleMessageTimeout(OutgoingMessageTx tx) {
        cancelMessageTimeout(tx);
        tx.timeoutFuture = scheduler.schedule(() -> {
            OutgoingMessageTx pending = pendingMessageTx.remove(tx.callId);
            if (pending == null) {
                return;
            }
            Log.w(TAG, "MESSAGE timeout, callId=" + tx.callId + ", messageId=" + tx.messageId);
            notifyMessageStatus(tx.messageId, Message.MessageStatus.FAILED, "Message timeout");
        }, MESSAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelMessageTimeout(OutgoingMessageTx tx) {
        if (tx.timeoutFuture != null) {
            tx.timeoutFuture.cancel(false);
            tx.timeoutFuture = null;
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


    private void resetCallState() {
        cancelInviteTimeout();
        inCall = false;
        outgoingInvitePending = false;
        currentCallId = null;
        currentFromTag = null;
        currentToTag = null;
        currentInviteBranch = null;
        currentInviteVideo = false;
        currentInviteBodyBytes = 0;
        currentInvitePacketBytes = 0;
        remoteContact = null;
        currentRemoteUri = null;
        currentLocalCseq = 1;
        lastInviteFailureKey = null;
        pendingIncomingInvite = null;
    }

    private String buildRegisterRequest(int expires, String callId, int cseq, String authHeader) {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        String fromTag = (registerFromTag != null) ? registerFromTag : shortUuid();
        registerFromTag = fromTag;

        // IMS 模式使用完整 IMPU，普通模式使用简单格式
        String fromUri = config.getFromUri();
        hdrs.put("From", "<" + fromUri + ">;tag=" + fromTag);
        hdrs.put("To", "<" + fromUri + ">");
        hdrs.put("Call-ID", callId);
        hdrs.put("CSeq", cseq + " REGISTER");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ";transport=udp>");
        hdrs.put("Expires", String.valueOf(expires));
        hdrs.put("User-Agent", "SipVideoChat-Android");
        if (authHeader != null && !authHeader.isEmpty()) {
            hdrs.put("Authorization", authHeader);
        }
        // IMS 必选头
        addImsHeaders(hdrs);
        // IMS 模式: REGISTER 到 IMS 域; 普通模式: REGISTER 到服务器 IP
        String registerUri = config.isImsMode()
                ? "sip:" + config.getRealm()
                : "sip:" + config.getSipServerHost() + ":" + config.getSipServerPort();
        return buildSipMessage("REGISTER " + registerUri + " SIP/2.0", hdrs, null, null);
    }

    /**
     * 为 IMS 模式添加必选 SIP 头
     */
    private void addImsHeaders(LinkedHashMap<String, String> hdrs) {
        if (!config.isImsMode()) return;
        hdrs.put(HEADER_PANI, PANI_LTE);
        hdrs.put(HEADER_SUPPORTED_IMS, SUPPORTED_IMS_VAL);
    }

    private String buildOptionsRequest(int cseq) {
        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + branch() + ";rport");
        hdrs.put("Max-Forwards", "70");
        String fromUri = config.getFromUri();
        hdrs.put("From", "<" + fromUri + ">;tag=" + shortUuid());
        hdrs.put("To", "<sip:" + config.getSipServerHost() + ">");
        hdrs.put("Call-ID", uuid());
        hdrs.put("CSeq", cseq + " OPTIONS");
        hdrs.put("Contact", "<sip:" + config.getUsername() + "@" + config.getLocalIp() + ":" + config.getLocalSipPort() + ">");
        hdrs.put("User-Agent", "SipVideoChat-Android");
        addImsHeaders(hdrs);
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


    private void send(String msg) {
        try {
            DatagramSocket s = socket;
            int localPort = (s != null) ? s.getLocalPort() : -1;
            byte[] data = msg.getBytes("UTF-8");
            Log.d(TAG, ">> " + msg.split("\r\n")[0]
                    + " via local " + config.getLocalIp() + ":" + localPort
                    + " to " + config.getSipServerHost() + ":" + config.getSipServerPort()
                    + " bytes=" + data.length);
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
            byte[] data = msg.getBytes("UTF-8");
            Log.d(TAG, ">> " + msg.split("\r\n")[0] + " to " + addr + ":" + port + " bytes=" + data.length);
            DatagramSocket s = socket;
            if (s != null && !s.isClosed()) {
                s.send(new DatagramPacket(data, data.length, addr, port));
            }
        } catch (Exception e) {
            Log.w(TAG, "sendTo failed: " + e.getMessage());
        }
    }


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


    private String getRequestUri(String targetUser) {
        String contact = userContacts.get(targetUser);
        if (contact != null) {
            String[] parts = contact.split(":");
            return "sip:" + targetUser + "@" + parts[0] + ":" + parts[1];
        }
        if (config.isImsMode()) {
            return "sip:" + targetUser + "@" + config.getRealm();
        }
        return "sip:" + targetUser + "@" + config.getSipServerHost() + ":" + config.getSipServerPort();
    }

    private String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String extractUser(String headerValue) {
        if (headerValue == null) return "unknown";
        int sipIdx = headerValue.indexOf("sip:");
        if (sipIdx < 0) return headerValue;
        String s = headerValue.substring(sipIdx + 4);
        int atIdx = s.indexOf('@');
        if (atIdx > 0) s = s.substring(0, atIdx);
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

    private long resolveRegisterExpiresSeconds(Map<String, String> headers) {
        String expiresHeader = getHeader(headers, "Expires");
        long expires = parseLong(expiresHeader, -1L);
        if (expires > 0L) {
            return expires;
        }

        String contactHeader = getHeader(headers, "Contact");
        if (contactHeader != null) {
            Matcher matcher = Pattern.compile("(?i)(?:^|;)\\s*expires\\s*=\\s*\"?(\\d+)\"?").matcher(contactHeader);
            if (matcher.find()) {
                return parseLong(matcher.group(1), 120L);
            }
        }
        return 120L;
    }

    public boolean isRegistrationAlive() {
        if (!registered.get()) {
            return false;
        }
        if (registrationExpiresAtMs <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        boolean alive = now < registrationExpiresAtMs - 5_000L;
        if (!alive) {
            registered.set(false);
        }
        return alive;
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void cacheUserContact(String username, String contactHeader) {
        if (username == null || username.trim().isEmpty() || contactHeader == null || contactHeader.trim().isEmpty()) {
            return;
        }
        String contactUri = extractSipUri(contactHeader);
        HostPort hostPort = extractHostPort(contactUri);
        if (hostPort == null) {
            return;
        }
        userContacts.put(username, hostPort.host + ":" + hostPort.port);
        Log.i(TAG, "Cached contact user=" + username + ", host=" + hostPort.host + ", port=" + hostPort.port);
    }

    private HostPort extractHostPort(String sipUri) {
        if (sipUri == null || sipUri.isEmpty()) {
            return null;
        }
        int sipIndex = sipUri.indexOf("sip:");
        String value = sipIndex >= 0 ? sipUri.substring(sipIndex + 4) : sipUri;
        int atIndex = value.indexOf('@');
        if (atIndex >= 0) {
            value = value.substring(atIndex + 1);
        }
        int semicolonIndex = value.indexOf(';');
        if (semicolonIndex >= 0) {
            value = value.substring(0, semicolonIndex);
        }
        int greaterThanIndex = value.indexOf('>');
        if (greaterThanIndex >= 0) {
            value = value.substring(0, greaterThanIndex);
        }
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }
        int colonIndex = value.lastIndexOf(':');
        if (colonIndex <= 0 || colonIndex >= value.length() - 1) {
            return new HostPort(value, config.getSipServerPort());
        }
        String host = value.substring(0, colonIndex);
        int port = (int) parseLong(value.substring(colonIndex + 1), config.getSipServerPort());
        return new HostPort(host, port);
    }

    private void sendCancelForPendingInvite() throws Exception {
        if (currentCallId == null || currentRemoteUri == null || currentInviteBranch == null) {
            return;
        }

        LinkedHashMap<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Via", "SIP/2.0/UDP " + config.getLocalIp() + ":" + config.getLocalSipPort() + ";branch=" + currentInviteBranch + ";rport");
        hdrs.put("Max-Forwards", "70");
        hdrs.put("From", "<sip:" + config.getUsername() + "@" + config.getSipServerHost() + ">;tag=" + currentFromTag);
        hdrs.put("To", "<" + currentRemoteUri + ">" + (currentToTag != null ? ";tag=" + currentToTag : ""));
        hdrs.put("Call-ID", currentCallId);
        hdrs.put("CSeq", "1 CANCEL");
        hdrs.put("User-Agent", "SipVideoChat-Android");

        String msg = buildSipMessage("CANCEL " + currentRemoteUri + " SIP/2.0", hdrs, null, null);
        send(msg);
    }

    private String normalizeSdpBody(String sdp) {
        if (sdp == null) {
            return "";
        }
        String normalized = sdp.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        if (!normalized.endsWith("\r\n")) {
            normalized += "\r\n";
        }
        return normalized;
    }

    private String publishWebRtcSdp(String sdp, String callId, String direction) throws Exception {
        LocalMediaServer mediaServer = LocalMediaServer.peek();
        if (mediaServer == null) {
            throw new IllegalStateException("Local media server unavailable");
        }
        String normalized = normalizeSdpBody(sdp);
        String fileName = "webrtc-" + (callId != null ? callId : uuid()) + "-" + direction + ".sdp";
        String url = mediaServer.saveText(normalized, fileName, "application/sdp");
        Log.i(TAG, "Published WebRTC SDP direction=" + direction
                + ", callId=" + callId
                + ", sdpLength=" + utf8Length(normalized)
                + ", url=" + url);
        DiagnosticLog.i(TAG, "published webrtc sdp direction=" + direction
                + ", callId=" + callId
                + ", sdpLength=" + utf8Length(normalized)
                + ", url=" + url);
        return url;
    }

    private String fetchRemoteWebRtcSdp(String sdpUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(sdpUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(REMOTE_SDP_FETCH_TIMEOUT_MS);
            connection.setReadTimeout(REMOTE_SDP_FETCH_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.connect();
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("HTTP " + statusCode);
            }
            try (InputStream inputStream = connection.getInputStream();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                String sdp = normalizeSdpBody(outputStream.toString("UTF-8"));
                Log.i(TAG, "Fetched remote WebRTC SDP url=" + sdpUrl
                        + ", sdpLength=" + utf8Length(sdp));
                DiagnosticLog.i(TAG, "fetched remote webrtc sdp url=" + sdpUrl
                        + ", sdpLength=" + utf8Length(sdp));
                return sdp;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildVideoSignalSdp(String sdpUrl) {
        String base = normalizeSdpBody(buildSDP(true));
        StringBuilder builder = new StringBuilder(base != null ? base : "");
        builder.append("a=x-sipvideochat-call-type:").append(CALL_TYPE_VIDEO).append("\r\n");
        if (sdpUrl != null && !sdpUrl.trim().isEmpty()) {
            builder.append("a=x-sipvideochat-sdp-url:").append(sdpUrl).append("\r\n");
        }
        return builder.toString();
    }

    private String extractWebRtcSdpUrlFromBody(String sdp) {
        return extractCustomSdpAttribute(sdp, "x-sipvideochat-sdp-url");
    }

    private String extractCallTypeFromBody(String sdp) {
        return extractCustomSdpAttribute(sdp, "x-sipvideochat-call-type");
    }

    private String extractCustomSdpAttribute(String sdp, String attributeName) {
        if (sdp == null || attributeName == null || attributeName.isEmpty()) {
            return null;
        }
        String normalized = normalizeSdpBody(sdp);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String prefix = "a=" + attributeName + ":";
        String[] lines = normalized.split("\r\n");
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private String firstSdpLine(String sdp) {
        if (sdp == null || sdp.isEmpty()) {
            return "";
        }
        int lineEnd = sdp.indexOf("\r\n");
        return lineEnd >= 0 ? sdp.substring(0, lineEnd) : sdp;
    }

    private int utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return value.getBytes("UTF-8").length;
        } catch (Exception e) {
            return value.length();
        }
    }

    private boolean isSupportedInviteContentType(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return false;
        }
        return contentType.toLowerCase(Locale.US).startsWith("application/sdp");
    }

    private InviteSdpInfo inspectInviteSdp(String sdp) {
        InviteSdpInfo info = new InviteSdpInfo();
        if (sdp == null || sdp.trim().isEmpty()) {
            info.parseResult = "empty body";
            return info;
        }
        String normalized = normalizeSdpBody(sdp);
        info.hasAudio = normalized.contains("\r\nm=audio ") || normalized.startsWith("m=audio ");
        info.hasVideo = normalized.contains("\r\nm=video ") || normalized.startsWith("m=video ");
        boolean hasVersion = normalized.startsWith("v=0\r\n");
        boolean hasOrigin = normalized.contains("\r\no=") || normalized.startsWith("o=");
        boolean hasTiming = normalized.contains("\r\nt=") || normalized.startsWith("t=");
        info.valid = hasVersion && hasOrigin && hasTiming && info.hasAudio;
        info.parseResult = info.valid ? "ok" : "missing required SDP lines";
        return info;
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


    private String buildDigestAuth(String wwwAuth, String method, String uri, String username, String password) {
        Map<String, String> params = parseDigestParams(wwwAuth);
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String algorithm = params.getOrDefault("algorithm", "MD5");
        String qop = params.get("qop");
        String nc = "00000001";
        String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // AKAv1-MD5: 使用 401 中的 ck+ik 作为密码
        String ha1Password = password;
        if ("AKAv1-MD5".equalsIgnoreCase(algorithm)) {
            String ck = params.getOrDefault("ck", "");
            String ik = params.getOrDefault("ik", "");
            if (!ck.isEmpty() && !ik.isEmpty()) {
                ha1Password = ck + ik;
                Log.i(TAG, "Using AKAv1-MD5 auth: ck=" + ck + ", ik=" + ik);
            }
        }

        String ha1 = md5(username + ":" + realm + ":" + ha1Password);
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
        sb.append("algorithm=").append(algorithm);
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
    public long getLastRegisterSuccessAtMs() { return lastRegisterSuccessAtMs; }
    public long getRegistrationExpiresAtMs() { return registrationExpiresAtMs; }
    public boolean isInCall() { return inCall; }
    public String getCurrentCallId() { return currentCallId; }
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
        boolean retriedWithAuth = false;
        String authHeader;
        ScheduledFuture<?> timeoutFuture;

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


    public static class IncomingInvite {
        public String fromUser;
        public String sdp;
        public String callId;
        public int cseq;
        public String viaHeader;
        public String fromHeader;
        public String toHeader;
        public String toTag;
        public String contactHeader;
        public InetAddress sourceAddress;
        public int sourcePort;
    }

    private static class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static class InviteSdpInfo {
        boolean valid;
        boolean hasAudio;
        boolean hasVideo;
        String parseResult = "not parsed";
    }
}


