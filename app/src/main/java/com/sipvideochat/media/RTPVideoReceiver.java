package com.sipvideochat.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * RTP视频接收器（Android版本）
 * 替代桌面端 FFmpegFrameGrabber → DatagramSocket 接收 + RTP 解包 + MediaCodec 解码
 *
 * 支持 VP8 RTP 解包 (RFC 7741)
 */
public class RTPVideoReceiver extends Thread {
    private static final String TAG = "RTPVideoReceiver";

    private int localPort;
    private DatagramSocket socket;
    private MediaCodec decoder;
    private volatile boolean running = false;
    private Surface outputSurface;

    private boolean useVP8 = true; // 与发送端匹配

    // VP8 帧重组缓冲区
    private byte[] frameBuffer = new byte[200000]; // 200KB max frame
    private int frameBufferPos = 0;
    private long currentTimestamp = -1;

    public interface VideoFrameListener {
        void onError(String error);
    }

    private VideoFrameListener listener;

    public RTPVideoReceiver(int localPort) {
        this.localPort = localPort;
        setName("RTPVideoReceiver-" + localPort);
        setDaemon(true);
    }

    public void setListener(VideoFrameListener listener) {
        this.listener = listener;
    }

    /**
     * 设置解码输出的 Surface（来自 SurfaceView）
     */
    public void setOutputSurface(Surface surface) {
        this.outputSurface = surface;
    }

    @Override
    public void run() {
        running = true;

        try {
            socket = new DatagramSocket(localPort);
            socket.setSoTimeout(5000);

            // 初始化解码器
            initDecoder();

            Log.i(TAG, "视频接收启动，端口: " + localPort);

            byte[] buffer = new byte[2000];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    processRtpPacket(packet.getData(), packet.getLength());

                    // 尝试读取解码输出
                    int outputIndex;
                    while ((outputIndex = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                        // 直接渲染到 Surface
                        decoder.releaseOutputBuffer(outputIndex, true);
                    }

                } catch (SocketTimeoutException e) {
                    // 正常超时
                } catch (Exception e) {
                    if (running) {
                        Log.e(TAG, "视频接收错误: " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "视频接收失败", e);
            if (listener != null) {
                listener.onError(e.getMessage());
            }
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "关闭解码器失败", e);
                }
                decoder = null;
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            Log.i(TAG, "视频接收已停止");
        }
    }

    private void initDecoder() throws Exception {
        // 尝试 VP8
        try {
            String mimeType = "video/x-vnd.on2.vp8";
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, 320, 240);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            decoder = MediaCodec.createDecoderByType(mimeType);
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();
            useVP8 = true;
            Log.i(TAG, "使用VP8解码器");
            return;
        } catch (Exception e) {
            Log.w(TAG, "VP8解码器不可用: " + e.getMessage());
        }

        // 回退 H.264
        String mimeType = "video/avc";
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, 320, 240);
        decoder = MediaCodec.createDecoderByType(mimeType);
        decoder.configure(format, outputSurface, null, 0);
        decoder.start();
        useVP8 = false;
        Log.i(TAG, "使用H.264解码器");
    }

    /**
     * 处理 RTP 包，解包后送入解码器
     */
    private void processRtpPacket(byte[] data, int length) {
        if (length < 12) return;

        // 解析 RTP 头
        boolean marker = (data[1] & 0x80) != 0;
        int payloadType = data[1] & 0x7F;
        int seqNum = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long rtpTimestamp = ((long)(data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) |
                ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);

        int headerLen = 12;
        int cc = data[0] & 0x0F;
        headerLen += cc * 4;

        // 扩展头
        if ((data[0] & 0x10) != 0 && length >= headerLen + 4) {
            int extLen = ((data[headerLen + 2] & 0xFF) << 8) | (data[headerLen + 3] & 0xFF);
            headerLen += 4 + extLen * 4;
        }

        if (length <= headerLen) return;

        if (useVP8) {
            processVP8Payload(data, headerLen, length, rtpTimestamp, marker);
        } else {
            processH264Payload(data, headerLen, length, rtpTimestamp, marker);
        }
    }

    /**
     * VP8 RTP 解包
     */
    private void processVP8Payload(byte[] data, int offset, int length, long rtpTimestamp, boolean marker) {
        // VP8 payload descriptor (至少1字节)
        if (offset >= length) return;

        byte desc = data[offset];
        boolean startOfPartition = (desc & 0x10) != 0; // S bit
        offset++;

        // 扩展标志
        if ((desc & 0x80) != 0) { // X bit
            if (offset >= length) return;
            byte xByte = data[offset];
            offset++;
            if ((xByte & 0x80) != 0) offset++; // I bit (PictureID)
            if ((xByte & 0x40) != 0) offset++; // L bit (TL0PICIDX)
            if ((xByte & 0x20) != 0 || (xByte & 0x10) != 0) offset++; // T/K bits
        }

        if (offset >= length) return;

        // 新帧开始
        if (currentTimestamp != rtpTimestamp) {
            // 如果有上一帧数据，先送入解码器
            if (frameBufferPos > 0 && currentTimestamp >= 0) {
                feedDecoder(frameBuffer, frameBufferPos, currentTimestamp);
            }
            frameBufferPos = 0;
            currentTimestamp = rtpTimestamp;
        }

        // 追加 payload 数据
        int payloadLen = length - offset;
        if (frameBufferPos + payloadLen < frameBuffer.length) {
            System.arraycopy(data, offset, frameBuffer, frameBufferPos, payloadLen);
            frameBufferPos += payloadLen;
        }

        // Marker = 帧的最后一个包
        if (marker && frameBufferPos > 0) {
            feedDecoder(frameBuffer, frameBufferPos, currentTimestamp);
            frameBufferPos = 0;
        }
    }

    /**
     * H.264 RTP 解包
     */
    private void processH264Payload(byte[] data, int offset, int length, long rtpTimestamp, boolean marker) {
        if (offset >= length) return;

        byte naluByte = data[offset];
        int naluType = naluByte & 0x1F;

        if (naluType >= 1 && naluType <= 23) {
            // 单NALU包
            int payloadLen = length - offset;
            byte[] nalu = new byte[4 + payloadLen]; // 起始码 + NALU
            nalu[0] = 0; nalu[1] = 0; nalu[2] = 0; nalu[3] = 1;
            System.arraycopy(data, offset, nalu, 4, payloadLen);
            feedDecoder(nalu, nalu.length, rtpTimestamp);

        } else if (naluType == 28) {
            // FU-A
            if (offset + 1 >= length) return;
            byte fuHeader = data[offset + 1];
            boolean fuStart = (fuHeader & 0x80) != 0;
            boolean fuEnd = (fuHeader & 0x40) != 0;
            int fuNaluType = fuHeader & 0x1F;

            if (fuStart) {
                frameBufferPos = 0;
                // 写起始码 + 重构的NALU header
                frameBuffer[0] = 0; frameBuffer[1] = 0; frameBuffer[2] = 0; frameBuffer[3] = 1;
                frameBuffer[4] = (byte) ((naluByte & 0xE0) | fuNaluType);
                frameBufferPos = 5;
            }

            int payloadLen = length - offset - 2;
            if (frameBufferPos + payloadLen < frameBuffer.length) {
                System.arraycopy(data, offset + 2, frameBuffer, frameBufferPos, payloadLen);
                frameBufferPos += payloadLen;
            }

            if (fuEnd && frameBufferPos > 0) {
                feedDecoder(frameBuffer, frameBufferPos, rtpTimestamp);
                frameBufferPos = 0;
            }
        }
    }

    /**
     * 将数据送入 MediaCodec 解码器
     */
    private void feedDecoder(byte[] data, int length, long timestamp) {
        try {
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);
                    long pts = timestamp * 1000000 / 90000;
                    decoder.queueInputBuffer(inputIndex, 0, length, pts, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "送入解码器失败: " + e.getMessage());
        }
    }

    public void stopReceiving() {
        running = false;
        try {
            join(2000);
        } catch (InterruptedException e) {
            interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }
}
