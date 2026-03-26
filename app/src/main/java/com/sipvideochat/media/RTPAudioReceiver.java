package com.sipvideochat.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.net.*;

/**
 * RTP音频接收器（Android版本）
 * RTP解析逻辑从桌面端直接复制（纯Java），仅替换播放部分：
 * javax.sound.sampled.SourceDataLine → android.media.AudioTrack
 */
public class RTPAudioReceiver {
    private static final String TAG = "RTPAudioReceiver";

    private DatagramSocket socket;
    private AudioTrack audioTrack;
    private boolean isRunning = false;

    public RTPAudioReceiver(int localPort) throws Exception {
        socket = new DatagramSocket(localPort);
        socket.setSoTimeout(5000); // 5秒超时

        // 构建 AudioTrack（替代桌面端 SourceDataLine）
        int sampleRate = 8000;
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBufSize, 3200); // 至少400ms buffer

        audioTrack = new AudioTrack.Builder()
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
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();

        Log.i(TAG, "RTP接收器已启动，监听端口: " + localPort);
    }

    public void start() {
        isRunning = true;

        new Thread(() -> {
            byte[] buffer = new byte[512];
            int consecutiveErrors = 0;

            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    consecutiveErrors = 0;

                    // 解析RTP包（与桌面端逻辑完全一致）
                    byte[] rtpData = packet.getData();
                    int packetLength = packet.getLength();

                    if (packetLength < 12) {
                        continue; // RTP头至少12字节
                    }

                    int rtpHeaderLength = 12;

                    // CSRC count
                    int cc = rtpData[0] & 0x0F;
                    rtpHeaderLength += cc * 4;

                    // 扩展头
                    if ((rtpData[0] & 0x10) != 0) {
                        if (packetLength < rtpHeaderLength + 4) continue;
                        int extLen = ((rtpData[rtpHeaderLength + 2] & 0xFF) << 8) |
                                (rtpData[rtpHeaderLength + 3] & 0xFF);
                        rtpHeaderLength += 4 + extLen * 4;
                    }

                    if (packetLength <= rtpHeaderLength) {
                        continue;
                    }

                    // 提取音频数据 (PCMU格式)
                    int payloadLength = packetLength - rtpHeaderLength;
                    byte[] mulawData = new byte[payloadLength];
                    System.arraycopy(rtpData, rtpHeaderLength, mulawData, 0, payloadLength);

                    // 解码 PCMU 为 PCM
                    byte[] pcmData = PCMUEncoder.decode(mulawData);

                    // 播放音频（AudioTrack 替代 SourceDataLine）
                    audioTrack.write(pcmData, 0, pcmData.length);

                } catch (SocketTimeoutException e) {
                    // 超时是正常的，继续等待
                } catch (Exception e) {
                    if (isRunning) {
                        consecutiveErrors++;
                        if (consecutiveErrors < 5) {
                            Log.e(TAG, "RTP接收错误: " + e.getMessage());
                        }
                        if (consecutiveErrors >= 10) {
                            Log.e(TAG, "连续错误过多，停止接收");
                            break;
                        }
                    }
                }
            }
        }, "RTPReceiver").start();
    }

    public void stop() {
        isRunning = false;
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "停止AudioTrack失败", e);
            }
            audioTrack = null;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        Log.i(TAG, "RTP接收器已停止");
    }
}
