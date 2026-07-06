package com.sipvideochat.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import com.sipvideochat.util.DiagnosticLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

public class RTPVideoSender extends Thread {
    private static final String TAG = "RTPVideoSender";
    private static final int RTP_PAYLOAD_TYPE_VP8 = 96;
    private static final int MAX_RTP_PAYLOAD = 1000;
    private static final int MAX_QUEUED_FRAMES = 3;
    private static final long OUTPUT_DRAIN_TIMEOUT_US = 15_000L;

    private final String remoteIp;
    private final int remotePort;
    private final int targetWidth;
    private final int targetHeight;
    private final int frameRate;
    private final Queue<VideoFrame> frameQueue = new LinkedList<>();

    private int bitrate;
    private MediaCodec encoder;
    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private volatile boolean running;
    private int sequenceNumber;
    private final int ssrc;
    private long baseCaptureTimeNs = -1L;
    private long framesEncoded;
    private long keyFramesEncoded;
    private long packetsSent;
    private boolean syncFrameRequestUnsupportedLogged;
    public RTPVideoSender(String remoteIp, int remotePort, int width, int height, int frameRate) {
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.targetWidth = width;
        this.targetHeight = height;
        this.frameRate = frameRate;
        this.bitrate = 96_000;
        this.ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        setName("RTPVideoSender");
        setDaemon(true);
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    public void pushFrame(byte[] nv21Data, int width, int height) {
        if (!running || nv21Data == null || width <= 0 || height <= 0) {
            return;
        }
        synchronized (frameQueue) {
            while (frameQueue.size() >= MAX_QUEUED_FRAMES) {
                frameQueue.poll();
            }
            frameQueue.offer(new VideoFrame(nv21Data, width, height, System.nanoTime()));
            frameQueue.notify();
        }
    }

    @Override
    public void run() {
        running = true;
        try {
            socket = new DatagramSocket();
            remoteAddress = InetAddress.getByName(remoteIp);
            Log.i(TAG, "Video sender started: " + remoteIp + ":" + remotePort + " VP8");
            DiagnosticLog.i(TAG, "video sender started remote=" + remoteIp + ":" + remotePort
                    + ", width=" + targetWidth
                    + ", height=" + targetHeight
                    + ", frameRate=" + frameRate
                    + ", bitrate=" + bitrate);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (running) {
                VideoFrame frame;
                synchronized (frameQueue) {
                    while (frameQueue.isEmpty() && running) {
                        frameQueue.wait(100);
                    }
                    if (!running) {
                        break;
                    }
                    frame = frameQueue.poll();
                }
                if (frame == null) {
                    continue;
                }

                ensureEncoder();

                int inputIndex = encoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        byte[] normalizedFrame = normalizeFrame(frame.nv21Data, frame.width, frame.height);
                        byte[] i420 = nv21ToI420(normalizedFrame, targetWidth, targetHeight);
                        inputBuffer.put(i420);
                        long ptsUs = toPresentationTimeUs(frame.captureTimeNs);
                        encoder.queueInputBuffer(inputIndex, 0, i420.length, ptsUs, 0);
                    }
                    drainEncoder(bufferInfo, OUTPUT_DRAIN_TIMEOUT_US);
                } else {
                    drainEncoder(bufferInfo, 0L);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Video sender failed", e);
            DiagnosticLog.e(TAG, "video sender failed", e);
        } finally {
            releaseEncoder();
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            Log.i(TAG, "Video sender stopped");
            DiagnosticLog.i(TAG, "video sender stopped remote=" + remoteIp + ":" + remotePort
                    + ", framesEncoded=" + framesEncoded
                    + ", keyFramesEncoded=" + keyFramesEncoded
                    + ", packetsSent=" + packetsSent);
        }
    }

    private void ensureEncoder() throws Exception {
        if (encoder != null) {
            return;
        }
        releaseEncoder();
        initEncoder(targetWidth, targetHeight);
        requestSyncFrame();
        Log.i(TAG, "Video encoder configured for " + targetWidth + "x" + targetHeight);
    }

    private void initEncoder(int width, int height) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat("video/x-vnd.on2.vp8", width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, frameRate));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        encoder = MediaCodec.createEncoderByType("video/x-vnd.on2.vp8");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }

    private void releaseEncoder() {
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            try {
                encoder.release();
            } catch (Exception ignored) {
            }
            encoder = null;
        }
        syncFrameRequestUnsupportedLogged = false;
    }

    private long toRtpTimestamp(long captureTimeNs) {
        if (baseCaptureTimeNs < 0L) {
            baseCaptureTimeNs = captureTimeNs;
        }
        return ((captureTimeNs - baseCaptureTimeNs) * 90_000L) / 1_000_000_000L;
    }

    private long toRtpTimestampUs(long presentationTimeUs) {
        return (presentationTimeUs * 90_000L) / 1_000_000L;
    }

    private long toPresentationTimeUs(long captureTimeNs) {
        if (baseCaptureTimeNs < 0L) {
            baseCaptureTimeNs = captureTimeNs;
        }
        return (captureTimeNs - baseCaptureTimeNs) / 1_000L;
    }

    private void drainEncoder(MediaCodec.BufferInfo bufferInfo, long firstTimeoutUs) throws Exception {
        long timeoutUs = firstTimeoutUs;
        while (running && encoder != null) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                timeoutUs = 0L;
                continue;
            }
            if (outputIndex < 0) {
                timeoutUs = 0L;
                continue;
            }

            ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
            if (outputBuffer != null && bufferInfo.size > 0
                    && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                byte[] encodedData = new byte[bufferInfo.size];
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                outputBuffer.get(encodedData);
                sendVp8RtpPackets(encodedData, toRtpTimestampUs(bufferInfo.presentationTimeUs), keyFrame);
                framesEncoded++;
                if (keyFrame) {
                    keyFramesEncoded++;
                }
                if (framesEncoded == 1 || framesEncoded % 60 == 0) {
                    DiagnosticLog.i(TAG, "encoded video frames=" + framesEncoded
                            + ", keyFramesEncoded=" + keyFramesEncoded
                            + ", encodedBytes=" + encodedData.length
                            + ", ptsUs=" + bufferInfo.presentationTimeUs
                            + ", keyFrame=" + keyFrame);
                }
            }
            encoder.releaseOutputBuffer(outputIndex, false);
            timeoutUs = 0L;
        }
    }

    private void requestSyncFrame() {
        if (encoder == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
        } catch (Exception e) {
            if (!syncFrameRequestUnsupportedLogged) {
                syncFrameRequestUnsupportedLogged = true;
                DiagnosticLog.w(TAG, "sync frame request unsupported: " + e.getMessage());
            }
        }
    }

    private void sendVp8RtpPackets(byte[] data, long rtpTimestamp, boolean keyFrame) throws Exception {
        int offset = 0;
        boolean isFirst = true;
        int packetsInFrame = 0;
        while (offset < data.length) {
            int remaining = data.length - offset;
            int chunkSize = Math.min(remaining, MAX_RTP_PAYLOAD - 1);
            boolean isLast = offset + chunkSize >= data.length;

            byte[] rtpPacket = new byte[12 + 1 + chunkSize];
            writeRtpHeader(rtpPacket, isLast, rtpTimestamp);
            rtpPacket[12] = (byte) (isFirst ? 0x10 : 0x00);
            System.arraycopy(data, offset, rtpPacket, 13, chunkSize);

            socket.send(new DatagramPacket(rtpPacket, rtpPacket.length, remoteAddress, remotePort));
            sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
            offset += chunkSize;
            isFirst = false;
            packetsInFrame++;
            packetsSent++;
            if (!isLast && shouldPauseBetweenPackets(keyFrame, packetsInFrame)) {
                Thread.sleep(1L);
            }
        }
    }

    private boolean shouldPauseBetweenPackets(boolean keyFrame, int packetsInFrame) {
        return packetsInFrame % (keyFrame ? 2 : 3) == 0;
    }

    private void writeRtpHeader(byte[] packet, boolean marker, long rtpTimestamp) {
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | RTP_PAYLOAD_TYPE_VP8);
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);

        int ts = (int) rtpTimestamp;
        packet[4] = (byte) ((ts >> 24) & 0xFF);
        packet[5] = (byte) ((ts >> 16) & 0xFF);
        packet[6] = (byte) ((ts >> 8) & 0xFF);
        packet[7] = (byte) (ts & 0xFF);

        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
    }

    private byte[] nv21ToI420(byte[] nv21, int width, int height) {
        int ySize = width * height;
        int uvSize = ySize / 4;
        byte[] i420 = new byte[ySize + uvSize * 2];

        System.arraycopy(nv21, 0, i420, 0, Math.min(ySize, nv21.length));

        int uPos = ySize;
        int vPos = ySize + uvSize;
        for (int i = 0; i < uvSize && (ySize + i * 2 + 1) < nv21.length; i++) {
            i420[uPos + i] = nv21[ySize + i * 2 + 1];
            i420[vPos + i] = nv21[ySize + i * 2];
        }
        return i420;
    }

    private byte[] normalizeFrame(byte[] nv21Data, int width, int height) {
        if (width == targetWidth && height == targetHeight) {
            return nv21Data;
        }

        int cropX = 0;
        int cropY = 0;
        int cropWidth = width;
        int cropHeight = height;
        float srcAspect = (float) width / (float) height;
        float dstAspect = (float) targetWidth / (float) targetHeight;

        if (srcAspect > dstAspect) {
            cropWidth = Math.max(2, ((int) (height * dstAspect)) & ~1);
            cropX = Math.max(0, ((width - cropWidth) / 2) & ~1);
        } else if (srcAspect < dstAspect) {
            cropHeight = Math.max(2, ((int) (width / dstAspect)) & ~1);
            cropY = Math.max(0, ((height - cropHeight) / 2) & ~1);
        }

        byte[] scaled = new byte[targetWidth * targetHeight * 3 / 2];

        for (int y = 0; y < targetHeight; y++) {
            int srcY = cropY + y * cropHeight / targetHeight;
            for (int x = 0; x < targetWidth; x++) {
                int srcX = cropX + x * cropWidth / targetWidth;
                scaled[y * targetWidth + x] = nv21Data[srcY * width + srcX];
            }
        }

        int targetUvOffset = targetWidth * targetHeight;
        int srcUvOffset = width * height;
        for (int y = 0; y < targetHeight / 2; y++) {
            int srcY = (cropY / 2) + y * (cropHeight / 2) / (targetHeight / 2);
            for (int x = 0; x < targetWidth / 2; x++) {
                int srcX = (cropX / 2) + x * (cropWidth / 2) / (targetWidth / 2);
                int targetIndex = targetUvOffset + y * targetWidth + x * 2;
                int sourceIndex = srcUvOffset + srcY * width + srcX * 2;
                if (sourceIndex + 1 < nv21Data.length && targetIndex + 1 < scaled.length) {
                    scaled[targetIndex] = nv21Data[sourceIndex];
                    scaled[targetIndex + 1] = nv21Data[sourceIndex + 1];
                }
            }
        }
        return scaled;
    }

    public void stopSending() {
        running = false;
        synchronized (frameQueue) {
            frameQueue.notifyAll();
        }
        try {
            join(2000);
        } catch (InterruptedException e) {
            interrupt();
        }
    }

    private static final class VideoFrame {
        final byte[] nv21Data;
        final int width;
        final int height;
        final long captureTimeNs;

        VideoFrame(byte[] nv21Data, int width, int height, long captureTimeNs) {
            this.nv21Data = nv21Data;
            this.width = width;
            this.height = height;
            this.captureTimeNs = captureTimeNs;
        }
    }
}
