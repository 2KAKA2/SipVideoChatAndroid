package com.sipvideochat.sip;

import com.sipvideochat.model.Message;
import com.sipvideochat.protocol.SipMessageBody;

/**
 * SIP事件监听器接口
 */
public interface SipEventListener {
    default void onRegistered() {}
    default void onRegisterFailed(String reason) {}
    default void onIncomingCall(String fromUser, String sdp, SipClient.IncomingInvite invite) {}
    default void onCallRinging() {}
    default void onCallConnected(String remoteSdp) {}
    default void onCallEnded() {}
    default void onCallFailed(String reason) {}
    default void onMessageReceived(String fromUser, SipMessageBody message) {}
    default void onMessageStatusChanged(String messageId, Message.MessageStatus status, String reason) {}
}
