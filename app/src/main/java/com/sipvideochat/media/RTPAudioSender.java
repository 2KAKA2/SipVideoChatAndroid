package com.sipvideochat.media;

import android.util.Log;

import com.sipvideochat.util.DiagnosticLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class RTPAudioSender {
    private static final String TAG = "RTPAudioSender";

    private final DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;
    private int sequenceNumber;
    private long timestamp;
    private final int ssrc;
    private int sentPackets;

    public RTPAudioSender(int localPort) throws SocketException {
        socket = new DatagramSocket(localPort);
        ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        Log.i(TAG, "Audio sender ready on port " + localPort);
        DiagnosticLog.i(TAG, "sender ready localPort=" + localPort);
    }

    public void setRemote(String ip, int port) throws UnknownHostException {
        remoteAddress = InetAddress.getByName(ip);
        remotePort = port;
        Log.i(TAG, "Audio sender remote=" + ip + ":" + port);
        DiagnosticLog.i(TAG, "sender remote=" + ip + ":" + port);
    }

    public void sendAudio(byte[] pcmData, int length) {
        if (remoteAddress == null || length <= 0) {
            return;
        }
        try {
            byte[] frame = length == pcmData.length ? pcmData : Arrays.copyOf(pcmData, length);
            byte[] mulawData = PCMUEncoder.encode(frame);
            byte[] rtpPacket = buildRtpPacket(mulawData);
            socket.send(new DatagramPacket(rtpPacket, rtpPacket.length, remoteAddress, remotePort));
            sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
            timestamp += 160;
            sentPackets++;
            if (sentPackets == 1 || sentPackets % 50 == 0) {
                DiagnosticLog.i(TAG, "sent packets=" + sentPackets
                        + ", remote=" + remoteAddress.getHostAddress() + ":" + remotePort
                        + ", payloadBytes=" + mulawData.length);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send RTP audio", e);
            DiagnosticLog.e(TAG, "failed to send RTP audio", e);
        }
    }

    private byte[] buildRtpPacket(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + payload.length);
        buffer.put((byte) 0x80);
        buffer.put((byte) 0x00);
        buffer.putShort((short) sequenceNumber);
        buffer.putInt((int) timestamp);
        buffer.putInt(ssrc);
        buffer.put(payload);
        return buffer.array();
    }

    public void close() {
        DiagnosticLog.i(TAG, "sender close packets=" + sentPackets);
        if (!socket.isClosed()) {
            socket.close();
        }
    }
}
