package com.sipvideochat.model;

import java.io.*;

/**
 * 协议工具类
 * (从桌面端 src/com/location/im/common/ProtocolUtil.java 移植)
 */
public class ProtocolUtil {

    private static final byte[] PROTOCOL_HEADER = new byte[]{(byte) 0xAB, (byte) 0xCD};

    public static final int MSG_TYPE_LOGIN = 1;
    public static final int MSG_TYPE_LOGIN_RESP = 2;
    public static final int MSG_TYPE_LOGOUT = 3;
    public static final int MSG_TYPE_CHAT = 10;
    public static final int MSG_TYPE_CHAT_ACK = 11;
    public static final int MSG_TYPE_GROUP_CHAT = 12;
    public static final int MSG_TYPE_CALL_INVITE = 30;
    public static final int MSG_TYPE_CALL_ACCEPT = 31;
    public static final int MSG_TYPE_CALL_REJECT = 32;
    public static final int MSG_TYPE_CALL_END = 33;
    public static final int MSG_TYPE_HEARTBEAT = 100;

    public static byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.flush();
        return baos.toByteArray();
    }

    public static Object deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        return ois.readObject();
    }

    public static byte[] buildPacket(int msgType, byte[] data) {
        int dataLen = data != null ? data.length : 0;
        byte[] packet = new byte[10 + dataLen];

        packet[0] = PROTOCOL_HEADER[0];
        packet[1] = PROTOCOL_HEADER[1];

        packet[2] = (byte) ((msgType >> 24) & 0xFF);
        packet[3] = (byte) ((msgType >> 16) & 0xFF);
        packet[4] = (byte) ((msgType >> 8) & 0xFF);
        packet[5] = (byte) (msgType & 0xFF);

        packet[6] = (byte) ((dataLen >> 24) & 0xFF);
        packet[7] = (byte) ((dataLen >> 16) & 0xFF);
        packet[8] = (byte) ((dataLen >> 8) & 0xFF);
        packet[9] = (byte) (dataLen & 0xFF);

        if (data != null && dataLen > 0) {
            System.arraycopy(data, 0, packet, 10, dataLen);
        }

        return packet;
    }

    public static boolean isValidHeader(byte[] header) {
        return header != null && header.length >= 2 &&
               header[0] == PROTOCOL_HEADER[0] && header[1] == PROTOCOL_HEADER[1];
    }

    public static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }
}
