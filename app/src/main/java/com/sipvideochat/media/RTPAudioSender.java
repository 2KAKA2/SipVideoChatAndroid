package com.sipvideochat.media;

import android.util.Log;

import java.net.*;
import java.nio.ByteBuffer;

/**
 * RTP音频发送器
 * (从桌面端 src/media/RTPAudioSender.java 直接移植，纯Java网络)
 */
public class RTPAudioSender {
    private static final String TAG = "RTPAudioSender";

    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private int ssrc;

    public RTPAudioSender(int localPort) throws SocketException {
        socket = new DatagramSocket(localPort);
        ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        Log.i(TAG, "RTP发送器已创建，本地端口: " + localPort);
    }

    public void setRemote(String ip, int port) throws UnknownHostException {
        this.remoteAddress = InetAddress.getByName(ip);
        this.remotePort = port;
        Log.i(TAG, "RTP目标设置为: " + ip + ":" + port);
    }

    public void sendAudio(byte[] pcmData, int length) {
        if (remoteAddress == null) return;

        try {
            byte[] mulawData = PCMUEncoder.encode(pcmData);

            byte[] rtpPacket = buildRTPPacket(mulawData);

            DatagramPacket packet = new DatagramPacket(
                    rtpPacket, rtpPacket.length, remoteAddress, remotePort
            );

            socket.send(packet);

            sequenceNumber++;
            if (sequenceNumber > 65535) {
                sequenceNumber = 0;
            }

            // 时间戳增量 = 样本数 (20ms @ 8kHz = 160 samples)
            timestamp += 160;

        } catch (Exception e) {
            Log.e(TAG, "RTP发送失败: " + e.getMessage());
        }
    }

    private byte[] buildRTPPacket(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + payload.length);

        // Byte 0: V(2)=2, P(1)=0, X(1)=0, CC(4)=0
        buffer.put((byte) 0x80);

        // Byte 1: M(1)=0, PT(7)=0 (PCMU)
        buffer.put((byte) 0x00);

        // Bytes 2-3: Sequence Number
        buffer.putShort((short) sequenceNumber);

        // Bytes 4-7: Timestamp
        buffer.putInt((int) timestamp);

        // Bytes 8-11: SSRC
        buffer.putInt(ssrc);

        // Payload
        buffer.put(payload);

        return buffer.array();
    }

    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
