package com.sipvideochat.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.sipvideochat.util.DiagnosticLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

public class RTPVideoReceiver extends Thread {
    private static final String TAG = "RTPVideoReceiver";
    private static final long MIN_FRAME_INTERVAL_US = 30_000L;

    private final int localPort;
    private DatagramSocket socket;
    private MediaCodec decoder;
    private volatile boolean running;
    private Surface outputSurface;
    private byte[] frameBuffer = new byte[200_000];
    private int frameBufferPos;
    private long currentTimestamp = -1;
    private int decoderWidth;
    private int decoderHeight;
    private long baseRtpTimestamp = -1L;
    private long lastQueuedPtsUs = -1L;
    private int lastSequenceNumber = -1;
    private boolean awaitingFrameStart = true;
    private boolean dropCurrentFrame;
    private long packetsReceived;
    private long framesQueued;
    private long framesRendered;
    private long keyFramesDetected;
    private long incompleteFramesDropped;

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

    public void setOutputSurface(Surface surface) {
        this.outputSurface = surface;
    }

    @Override
    public void run() {
        running = true;
        try {
            socket = new DatagramSocket(localPort);
            socket.setSoTimeout(5000);
            Log.i(TAG, "Video receiver started on " + localPort + " VP8");
            DiagnosticLog.i(TAG, "video receiver started localPort=" + localPort + ", codec=VP8");

            byte[] buffer = new byte[2000];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    processVp8Packet(packet.getData(), packet.getLength());

                    if (decoder == null) {
                        continue;
                    }

                    int outputIndex;
                    int latestOutputIndex = -1;
                    while ((outputIndex = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                        if (latestOutputIndex >= 0) {
                            decoder.releaseOutputBuffer(latestOutputIndex, false);
                        }
                        latestOutputIndex = outputIndex;
                    }
                    if (latestOutputIndex >= 0) {
                        decoder.releaseOutputBuffer(latestOutputIndex, true);
                        framesRendered++;
                        if (framesRendered == 1 || framesRendered % 60 == 0) {
                            DiagnosticLog.i(TAG, "rendered vp8 frames=" + framesRendered
                                    + ", queued=" + framesQueued
                                    + ", packetsReceived=" + packetsReceived);
                        }
                    }
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Video output format changed: " + decoder.getOutputFormat());
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (running) {
                        Log.e(TAG, "Video receive error", e);
                        DiagnosticLog.e(TAG, "video receive loop error", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Video receiver failed", e);
            DiagnosticLog.e(TAG, "video receiver failed", e);
            if (listener != null) {
                listener.onError(e.getMessage());
            }
        } finally {
            releaseDecoder();
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            Log.i(TAG, "Video receiver stopped");
            DiagnosticLog.i(TAG, "video receiver stopped localPort=" + localPort
                    + ", packetsReceived=" + packetsReceived
                    + ", framesQueued=" + framesQueued
                    + ", framesRendered=" + framesRendered
                    + ", keyFrames=" + keyFramesDetected
                    + ", incompleteFramesDropped=" + incompleteFramesDropped);
        }
    }

    private void processVp8Packet(byte[] data, int length) {
        if (length < 13) {
            return;
        }
        packetsReceived++;
        if (packetsReceived == 1 || packetsReceived % 150 == 0) {
            DiagnosticLog.i(TAG, "received video packets=" + packetsReceived
                    + ", localPort=" + localPort
                    + ", packetBytes=" + length);
        }

        int payloadEnd = length;
        if ((data[0] & 0x20) != 0) {
            int paddingLength = data[length - 1] & 0xFF;
            if (paddingLength <= 0 || paddingLength >= length) {
                return;
            }
            payloadEnd -= paddingLength;
        }

        int rtpHeaderLength = 12 + ((data[0] & 0x0F) * 4);
        if (rtpHeaderLength >= payloadEnd) {
            return;
        }
        if ((data[0] & 0x10) != 0) {
            if (rtpHeaderLength + 4 > payloadEnd) {
                return;
            }
            int extensionLengthWords = ((data[rtpHeaderLength + 2] & 0xFF) << 8)
                    | (data[rtpHeaderLength + 3] & 0xFF);
            rtpHeaderLength += 4 + extensionLengthWords * 4;
            if (rtpHeaderLength >= payloadEnd) {
                return;
            }
        }

        boolean marker = (data[1] & 0x80) != 0;
        int sequenceNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long rtpTimestamp = ((long) (data[4] & 0xFF) << 24)
                | ((long) (data[5] & 0xFF) << 16)
                | ((long) (data[6] & 0xFF) << 8)
                | (data[7] & 0xFF);
        boolean startOfPartition = (data[rtpHeaderLength] & 0x10) != 0;

        int offset = parseVp8PayloadOffset(data, rtpHeaderLength, payloadEnd);
        if (offset < 0 || offset >= payloadEnd) {
            return;
        }

        if (currentTimestamp != rtpTimestamp) {
            if (frameBufferPos > 0 || dropCurrentFrame) {
                incompleteFramesDropped++;
            }
            currentTimestamp = rtpTimestamp;
            frameBufferPos = 0;
            awaitingFrameStart = true;
            dropCurrentFrame = false;
        } else if (!awaitingFrameStart
                && !dropCurrentFrame
                && lastSequenceNumber >= 0
                && sequenceNumber != ((lastSequenceNumber + 1) & 0xFFFF)) {
            dropCurrentFrame = true;
            incompleteFramesDropped++;
            frameBufferPos = 0;
            if (incompleteFramesDropped == 1 || incompleteFramesDropped % 20 == 0) {
                DiagnosticLog.w(TAG, "dropping incomplete vp8 frame due to sequence gap"
                        + ", expected=" + (((lastSequenceNumber + 1) & 0xFFFF))
                        + ", actual=" + sequenceNumber
                        + ", timestamp=" + rtpTimestamp
                        + ", dropped=" + incompleteFramesDropped);
            }
        }

        if (awaitingFrameStart) {
            if (!startOfPartition) {
                dropCurrentFrame = true;
                lastSequenceNumber = sequenceNumber;
                if (marker) {
                    resetFrameAssembly();
                }
                return;
            }
            awaitingFrameStart = false;
        }

        if (dropCurrentFrame) {
            lastSequenceNumber = sequenceNumber;
            if (marker) {
                resetFrameAssembly();
            }
            return;
        }

        int payloadLength = payloadEnd - offset;
        if (frameBufferPos + payloadLength > frameBuffer.length) {
            incompleteFramesDropped++;
            resetFrameAssembly();
            lastSequenceNumber = sequenceNumber;
            return;
        }
        System.arraycopy(data, offset, frameBuffer, frameBufferPos, payloadLength);
        frameBufferPos += payloadLength;
        lastSequenceNumber = sequenceNumber;

        if (marker && frameBufferPos > 0) {
            feedDecoder(frameBuffer, frameBufferPos, currentTimestamp);
            resetFrameAssembly();
        }
    }

    private void resetFrameAssembly() {
        frameBufferPos = 0;
        currentTimestamp = -1L;
        awaitingFrameStart = true;
        dropCurrentFrame = false;
    }

    private void feedDecoder(byte[] data, int length, long timestamp) {
        try {
            long ptsUs = toPresentationTimeUs(timestamp);
            if (lastQueuedPtsUs > 0L && ptsUs - lastQueuedPtsUs < MIN_FRAME_INTERVAL_US) {
                return;
            }

            int[] size = tryParseVp8KeyFrameSize(data, length);
            if (size != null) {
                keyFramesDetected++;
                if (keyFramesDetected == 1 || keyFramesDetected % 30 == 0) {
                    DiagnosticLog.i(TAG, "vp8 keyframe detected count=" + keyFramesDetected
                            + ", size=" + size[0] + "x" + size[1]
                            + ", frameBytes=" + length);
                }
                ensureDecoder(size[0], size[1]);
            } else if (decoder == null) {
                if (packetsReceived % 120 == 0) {
                    DiagnosticLog.w(TAG, "waiting for vp8 keyframe packets=" + packetsReceived
                            + ", bufferedFrameBytes=" + length);
                }
                return;
            }

            int inputIndex = decoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);
                    decoder.queueInputBuffer(inputIndex, 0, length, ptsUs, 0);
                    lastQueuedPtsUs = ptsUs;
                    framesQueued++;
                    if (framesQueued == 1 || framesQueued % 60 == 0) {
                        DiagnosticLog.i(TAG, "queued vp8 frames=" + framesQueued
                                + ", ptsUs=" + ptsUs
                                + ", frameBytes=" + length);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to queue VP8 frame", e);
            DiagnosticLog.e(TAG, "failed to queue vp8 frame", e);
        }
    }

    private int parseVp8PayloadOffset(byte[] data, int offset, int payloadEnd) {
        if (offset >= payloadEnd) {
            return -1;
        }

        int descriptor = data[offset++] & 0xFF;
        if ((descriptor & 0x80) == 0) {
            return offset;
        }
        if (offset >= payloadEnd) {
            return -1;
        }

        int extension = data[offset++] & 0xFF;
        if ((extension & 0x80) != 0) {
            if (offset >= payloadEnd) {
                return -1;
            }
            int pictureId = data[offset++] & 0xFF;
            if ((pictureId & 0x80) != 0) {
                if (offset >= payloadEnd) {
                    return -1;
                }
                offset++;
            }
        }
        if ((extension & 0x40) != 0) {
            if (offset >= payloadEnd) {
                return -1;
            }
            offset++;
        }
        if ((extension & 0x20) != 0 || (extension & 0x10) != 0) {
            if (offset >= payloadEnd) {
                return -1;
            }
            offset++;
        }
        return offset;
    }

    private void ensureDecoder(int width, int height) throws Exception {
        if (decoder != null && decoderWidth == width && decoderHeight == height) {
            return;
        }
        releaseDecoder();
        MediaFormat format = MediaFormat.createVideoFormat("video/x-vnd.on2.vp8", width, height);
        decoder = MediaCodec.createDecoderByType("video/x-vnd.on2.vp8");
        decoder.configure(format, outputSurface, null, 0);
        decoder.start();
        decoderWidth = width;
        decoderHeight = height;
        Log.i(TAG, "Video decoder configured for " + width + "x" + height);
        DiagnosticLog.i(TAG, "video decoder configured width=" + width + ", height=" + height);
    }

    private void releaseDecoder() {
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {
            }
            try {
                decoder.release();
            } catch (Exception ignored) {
            }
            decoder = null;
        }
        decoderWidth = 0;
        decoderHeight = 0;
        baseRtpTimestamp = -1L;
        lastQueuedPtsUs = -1L;
        lastSequenceNumber = -1;
        awaitingFrameStart = true;
        dropCurrentFrame = false;
        frameBufferPos = 0;
        currentTimestamp = -1L;
    }

    private long toPresentationTimeUs(long rtpTimestamp) {
        if (baseRtpTimestamp < 0L) {
            baseRtpTimestamp = rtpTimestamp;
        }
        long relativeTicks = rtpTimestamp - baseRtpTimestamp;
        if (relativeTicks < 0L) {
            relativeTicks += (1L << 32);
        }
        return relativeTicks * 1_000_000L / 90_000L;
    }

    private int[] tryParseVp8KeyFrameSize(byte[] data, int length) {
        if (length < 10) {
            return null;
        }
        boolean isKeyFrame = (data[0] & 0x01) == 0;
        if (!isKeyFrame) {
            return null;
        }
        if ((data[3] & 0xFF) != 0x9D || (data[4] & 0xFF) != 0x01 || (data[5] & 0xFF) != 0x2A) {
            return null;
        }

        int width = ((data[7] & 0x3F) << 8) | (data[6] & 0xFF);
        int height = ((data[9] & 0x3F) << 8) | (data[8] & 0xFF);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    public void stopReceiving() {
        DiagnosticLog.i(TAG, "stopReceiving requested localPort=" + localPort);
        running = false;
        try {
            join(2000);
        } catch (InterruptedException e) {
            interrupt();
        }
    }
}
