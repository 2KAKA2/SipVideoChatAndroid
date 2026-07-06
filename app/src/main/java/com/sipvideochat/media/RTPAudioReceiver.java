package com.sipvideochat.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import com.sipvideochat.util.DiagnosticLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class RTPAudioReceiver {
    private static final String TAG = "RTPAudioReceiver";

    private final DatagramSocket socket;
    private final AudioTrack audioTrack;
    private volatile boolean isRunning;
    private int receivedPackets;

    public RTPAudioReceiver(int localPort) throws Exception {
        socket = new DatagramSocket(localPort);

        int sampleRate = 8000;
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBufferSize, 1600);

        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        audioTrack = builder.build();
        audioTrack.play();
        Log.i(TAG, "Audio receiver ready on port " + localPort);
        DiagnosticLog.i(TAG, "receiver ready localPort=" + localPort);
    }

    public void start() {
        isRunning = true;
        new Thread(() -> {
            byte[] buffer = new byte[512];
            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    int packetLength = packet.getLength();
                    if (packetLength <= 12) {
                        continue;
                    }

                    int payloadLength = packetLength - 12;
                    byte[] mulawData = new byte[payloadLength];
                    System.arraycopy(packet.getData(), 12, mulawData, 0, payloadLength);
                    byte[] pcmData = PCMUEncoder.decode(mulawData);
                    audioTrack.write(pcmData, 0, pcmData.length);
                    receivedPackets++;
                    if (receivedPackets == 1 || receivedPackets % 50 == 0) {
                        DiagnosticLog.i(TAG, "received packets=" + receivedPackets
                                + ", source=" + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                                + ", payloadBytes=" + payloadLength);
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        Log.e(TAG, "Failed to receive RTP audio", e);
                        DiagnosticLog.e(TAG, "failed to receive RTP audio", e);
                    }
                }
            }
        }, "RTPAudioReceiver").start();
    }

    public void stop() {
        isRunning = false;
        DiagnosticLog.i(TAG, "receiver stop packets=" + receivedPackets);
        try {
            audioTrack.stop();
        } catch (Exception ignored) {
        }
        try {
            audioTrack.release();
        } catch (Exception ignored) {
        }
        if (!socket.isClosed()) {
            socket.close();
        }
    }
}
