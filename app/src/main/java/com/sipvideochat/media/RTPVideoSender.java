package com.sipvideochat.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

/**
 * RTP视频发送器（Android版本）
 * 替代桌面端 FFmpegFrameRecorder → MediaCodec + 手动RTP打包
 *
 * 编码策略：优先 VP8（与桌面端兼容），回退 H.264
 * RTP打包：手动实现 VP8 RTP payload format (RFC 7741)
 */
public class RTPVideoSender extends Thread {
    private static final String TAG = "RTPVideoSender";

    private String remoteIp;
    private int remotePort;
    private int width;
    private int height;
    private int frameRate;
    private int bitrate;

    private MediaCodec encoder;
    private DatagramSocket socket;
    private InetAddress remoteAddress;

    private final Queue<byte[]> frameQueue = new LinkedList<>();
    private volatile boolean running = false;

    // RTP 参数
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private int ssrc;
    private static final int RTP_PAYLOAD_TYPE = 96; // 动态 VP8
    private static final int MAX_RTP_PAYLOAD = 1200; // MTU 安全值

    private boolean useVP8 = true; // true=VP8, false=H.264

    public RTPVideoSender(String remoteIp, int remotePort, int width, int height, int frameRate) {
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.bitrate = 500000; // 500kbps
        this.ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        setName("RTPVideoSender");
        setDaemon(true);
    }

    public RTPVideoSender(String remoteIp, int remotePort) {
        this(remoteIp, remotePort, 320, 240, 25);
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    /**
     * 推送一帧 NV21 数据
     */
    public void pushFrame(byte[] nv21Data) {
        if (!running) return;
        synchronized (frameQueue) {
            if (frameQueue.size() < 3) {
                frameQueue.offer(nv21Data);
                frameQueue.notify();
            }
        }
    }

    @Override
    public void run() {
        running = true;

        try {
            socket = new DatagramSocket();
            remoteAddress = InetAddress.getByName(remoteIp);

            // 初始化编码器
            initEncoder();

            Log.i(TAG, "视频推流已启动: " + remoteIp + ":" + remotePort +
                    " (" + (useVP8 ? "VP8" : "H.264") + ")");

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (running) {
                byte[] frameData;
                synchronized (frameQueue) {
                    while (frameQueue.isEmpty() && running) {
                        frameQueue.wait(100);
                    }
                    if (!running) break;
                    frameData = frameQueue.poll();
                }

                if (frameData == null) continue;

                // 送入编码器
                int inputIndex = encoder.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();

                        // NV21 → I420 (if needed for VP8)
                        byte[] i420 = nv21ToI420(frameData, width, height);
                        inputBuffer.put(i420);

                        long pts = timestamp * 1000000 / 90000; // RTP时间戳→微秒
                        encoder.queueInputBuffer(inputIndex, 0, i420.length, pts, 0);
                    }
                }

                // 读取编码输出并发送 RTP
                int outputIndex;
                while ((outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)) >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] encodedData = new byte[bufferInfo.size];
                        outputBuffer.get(encodedData);

                        // 通过RTP发送
                        sendRtpPackets(encodedData, bufferInfo);
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);
                }

                // RTP时间戳增量 (90kHz clock / fps)
                timestamp += 90000 / frameRate;
            }

        } catch (Exception e) {
            Log.e(TAG, "视频推流异常", e);
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "关闭编码器失败", e);
                }
                encoder = null;
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            Log.i(TAG, "视频推流已停止");
        }
    }

    private void initEncoder() throws Exception {
        // 尝试 VP8
        try {
            String mimeType = "video/x-vnd.on2.vp8";
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            encoder = MediaCodec.createEncoderByType(mimeType);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            useVP8 = true;
            Log.i(TAG, "使用VP8编码器（与桌面端兼容）");
            return;
        } catch (Exception e) {
            Log.w(TAG, "VP8编码器不可用，回退到H.264: " + e.getMessage());
        }

        // 回退 H.264
        String mimeType = "video/avc";
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        encoder = MediaCodec.createEncoderByType(mimeType);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        useVP8 = false;
        Log.i(TAG, "使用H.264编码器");
    }

    /**
     * 将编码后的数据按 RTP 分包发送
     */
    private void sendRtpPackets(byte[] encodedData, MediaCodec.BufferInfo info) {
        try {
            boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

            if (useVP8) {
                sendVP8RtpPackets(encodedData, isKeyFrame);
            } else {
                sendH264RtpPackets(encodedData, isKeyFrame);
            }
        } catch (Exception e) {
            Log.e(TAG, "RTP发送失败", e);
        }
    }

    /**
     * VP8 RTP 打包 (RFC 7741 简化版)
     */
    private void sendVP8RtpPackets(byte[] data, boolean isKeyFrame) throws Exception {
        int offset = 0;
        boolean isFirst = true;

        while (offset < data.length) {
            int remaining = data.length - offset;
            // VP8 payload descriptor: 1 byte minimum
            int maxPayload = MAX_RTP_PAYLOAD - 1; // 减去VP8 payload descriptor
            int chunkSize = Math.min(remaining, maxPayload);
            boolean isLast = (offset + chunkSize >= data.length);

            // 构建 RTP 包
            byte[] rtpPacket = new byte[12 + 1 + chunkSize]; // RTP header + VP8 desc + payload

            // RTP Header
            rtpPacket[0] = (byte) 0x80; // V=2
            rtpPacket[1] = (byte) ((isLast ? 0x80 : 0x00) | RTP_PAYLOAD_TYPE); // M bit on last
            rtpPacket[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
            rtpPacket[3] = (byte) (sequenceNumber & 0xFF);

            int ts = (int) timestamp;
            rtpPacket[4] = (byte) ((ts >> 24) & 0xFF);
            rtpPacket[5] = (byte) ((ts >> 16) & 0xFF);
            rtpPacket[6] = (byte) ((ts >> 8) & 0xFF);
            rtpPacket[7] = (byte) (ts & 0xFF);

            rtpPacket[8] = (byte) ((ssrc >> 24) & 0xFF);
            rtpPacket[9] = (byte) ((ssrc >> 16) & 0xFF);
            rtpPacket[10] = (byte) ((ssrc >> 8) & 0xFF);
            rtpPacket[11] = (byte) (ssrc & 0xFF);

            // VP8 Payload Descriptor (1 byte)
            // X=0, R=0, N=0, S=start, PartID=0
            rtpPacket[12] = (byte) (isFirst ? 0x10 : 0x00); // S bit

            // Payload
            System.arraycopy(data, offset, rtpPacket, 13, chunkSize);

            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, remoteAddress, remotePort);
            socket.send(packet);

            sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
            offset += chunkSize;
            isFirst = false;
        }
    }

    /**
     * H.264 RTP 打包 (RFC 6184 FU-A)
     */
    private void sendH264RtpPackets(byte[] data, boolean isKeyFrame) throws Exception {
        // 跳过起始码 (0x00 0x00 0x00 0x01 或 0x00 0x00 0x01)
        int offset = 0;
        if (data.length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            offset = 4;
        } else if (data.length > 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            offset = 3;
        }

        int naluLength = data.length - offset;
        byte naluHeader = data[offset];

        if (naluLength <= MAX_RTP_PAYLOAD) {
            // 单NAL单元包
            byte[] rtpPacket = new byte[12 + naluLength];
            writeRtpHeader(rtpPacket, true);
            System.arraycopy(data, offset, rtpPacket, 12, naluLength);

            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, remoteAddress, remotePort);
            socket.send(packet);
            sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
        } else {
            // FU-A 分片
            int naluType = naluHeader & 0x1F;
            int nri = naluHeader & 0x60;
            int pos = offset + 1; // skip NALU header

            boolean isFirst2 = true;
            while (pos < data.length) {
                int remaining = data.length - pos;
                int chunkSize = Math.min(remaining, MAX_RTP_PAYLOAD - 2); // FU indicator + FU header
                boolean isLast = (pos + chunkSize >= data.length);

                byte[] rtpPacket = new byte[12 + 2 + chunkSize];
                writeRtpHeader(rtpPacket, isLast);

                // FU indicator
                rtpPacket[12] = (byte) (nri | 28); // type=28 (FU-A)

                // FU header
                byte fuHeader = (byte) naluType;
                if (isFirst2) fuHeader |= 0x80; // S bit
                if (isLast) fuHeader |= 0x40;   // E bit
                rtpPacket[13] = fuHeader;

                System.arraycopy(data, pos, rtpPacket, 14, chunkSize);

                DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, remoteAddress, remotePort);
                socket.send(packet);

                sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
                pos += chunkSize;
                isFirst2 = false;
            }
        }
    }

    private void writeRtpHeader(byte[] packet, boolean marker) {
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | RTP_PAYLOAD_TYPE);
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);

        int ts = (int) timestamp;
        packet[4] = (byte) ((ts >> 24) & 0xFF);
        packet[5] = (byte) ((ts >> 16) & 0xFF);
        packet[6] = (byte) ((ts >> 8) & 0xFF);
        packet[7] = (byte) (ts & 0xFF);

        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
    }

    /**
     * NV21 → I420 转换
     */
    private byte[] nv21ToI420(byte[] nv21, int w, int h) {
        int ySize = w * h;
        int uvSize = ySize / 4;
        byte[] i420 = new byte[ySize + uvSize * 2];

        // Y plane 直接拷贝
        System.arraycopy(nv21, 0, i420, 0, ySize);

        // NV21: VUVU... → I420: UUU...VVV...
        for (int i = 0; i < uvSize; i++) {
            i420[ySize + i] = nv21[ySize + i * 2 + 1];           // U
            i420[ySize + uvSize + i] = nv21[ySize + i * 2];      // V
        }

        return i420;
    }

    public void stopSending() {
        running = false;
        synchronized (frameQueue) {
            frameQueue.notify();
        }
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
