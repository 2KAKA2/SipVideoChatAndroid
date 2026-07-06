package com.sipvideochat.media;

import android.content.Context;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.sipvideochat.util.DiagnosticLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCapture {
    private static final String TAG = "VideoCapture";

    private final int width;
    private final int height;
    private final int frameRate;
    private final List<VideoFrameListener> listeners = new ArrayList<>();

    private boolean running;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService analysisExecutor;
    private long lastDeliveredTimestampNs;

    public interface VideoFrameListener {
        void onFrame(byte[] yuvData, int width, int height);

        void onError(String error);
    }

    public VideoCapture(int width, int height, int frameRate) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
    }

    public VideoCapture() {
        this(96, 128, 4);
    }

    public void addListener(VideoFrameListener listener) {
        listeners.add(listener);
    }

    public void removeListener(VideoFrameListener listener) {
        listeners.remove(listener);
    }

    public void start(Context context, LifecycleOwner lifecycleOwner, PreviewView previewView) {
        lastDeliveredTimestampNs = 0L;
        DiagnosticLog.i(TAG, "start requested width=" + width
                + ", height=" + height
                + ", frameRate=" + frameRate
                + ", running=" + running);
        analysisExecutor = Executors.newSingleThreadExecutor();
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(context);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();

                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(width, height))
                        .build();
                if (previewView != null) {
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                }

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(width, height))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setImageQueueDepth(1)
                        .build();
                imageAnalysis.setAnalyzer(analysisExecutor, this::processImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalysis);

                running = true;
                Log.i(TAG, "Camera started (" + width + "x" + height + "@" + frameRate + "fps)");
                DiagnosticLog.i(TAG, "camera started width=" + width
                        + ", height=" + height
                        + ", frameRate=" + frameRate);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera", e);
                DiagnosticLog.e(TAG, "failed to start camera", e);
                for (VideoFrameListener listener : listeners) {
                    listener.onError("启动相机失败: " + e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void processImage(@NonNull ImageProxy image) {
        try {
            if (!running) {
                return;
            }

            long captureTimestampNs = image.getImageInfo().getTimestamp();
            if (captureTimestampNs <= 0L) {
                captureTimestampNs = System.nanoTime();
            }
            long minFrameIntervalNs = 1_000_000_000L / Math.max(1, frameRate);
            if (lastDeliveredTimestampNs > 0L
                    && captureTimestampNs - lastDeliveredTimestampNs < minFrameIntervalNs) {
                return;
            }
            lastDeliveredTimestampNs = captureTimestampNs;

            int frameWidth = image.getWidth();
            int frameHeight = image.getHeight();
            int rotationDegrees = image.getImageInfo().getRotationDegrees();

            byte[] nv21 = imageProxyToNv21(image);
            if (rotationDegrees != 0) {
                nv21 = rotateNv21(nv21, frameWidth, frameHeight, rotationDegrees);
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    int rotatedWidth = frameHeight;
                    frameHeight = frameWidth;
                    frameWidth = rotatedWidth;
                }
            }

            for (VideoFrameListener listener : listeners) {
                try {
                    listener.onFrame(nv21, frameWidth, frameHeight);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to deliver frame", e);
                }
            }
        } finally {
            image.close();
        }
    }

    private byte[] imageProxyToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int w = image.getWidth();
        int h = image.getHeight();

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();

        ByteBuffer uBuffer = planes[1].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();

        ByteBuffer vBuffer = planes[2].getBuffer();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[w * h * 3 / 2];

        int pos = 0;
        for (int row = 0; row < h; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv21, pos, w);
            pos += w;
        }

        int uvHeight = h / 2;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < w / 2; col++) {
                int vIndex = row * vRowStride + col * vPixelStride;
                int uIndex = row * uRowStride + col * uPixelStride;
                nv21[pos++] = vBuffer.get(vIndex);
                nv21[pos++] = uBuffer.get(uIndex);
            }
        }
        return nv21;
    }

    private byte[] rotateNv21(byte[] nv21, int width, int height, int rotationDegrees) {
        switch (rotationDegrees) {
            case 90:
                return rotateNv21Clockwise90(nv21, width, height);
            case 180:
                return rotateNv21180(nv21, width, height);
            case 270:
                return rotateNv21Clockwise270(nv21, width, height);
            default:
                return nv21;
        }
    }

    private byte[] rotateNv21Clockwise90(byte[] nv21, int width, int height) {
        byte[] rotated = new byte[nv21.length];
        int ySize = width * height;
        int outIndex = 0;

        for (int x = 0; x < width; x++) {
            for (int y = height - 1; y >= 0; y--) {
                rotated[outIndex++] = nv21[y * width + x];
            }
        }

        for (int x = 0; x < width; x += 2) {
            for (int y = height / 2 - 1; y >= 0; y--) {
                int uvIndex = ySize + y * width + x;
                rotated[outIndex++] = nv21[uvIndex];
                rotated[outIndex++] = nv21[uvIndex + 1];
            }
        }
        return rotated;
    }

    private byte[] rotateNv21Clockwise270(byte[] nv21, int width, int height) {
        byte[] rotated = new byte[nv21.length];
        int ySize = width * height;
        int outIndex = 0;

        for (int x = width - 1; x >= 0; x--) {
            for (int y = 0; y < height; y++) {
                rotated[outIndex++] = nv21[y * width + x];
            }
        }

        for (int x = width - 2; x >= 0; x -= 2) {
            for (int y = 0; y < height / 2; y++) {
                int uvIndex = ySize + y * width + x;
                rotated[outIndex++] = nv21[uvIndex];
                rotated[outIndex++] = nv21[uvIndex + 1];
            }
        }
        return rotated;
    }

    private byte[] rotateNv21180(byte[] nv21, int width, int height) {
        byte[] rotated = new byte[nv21.length];
        int ySize = width * height;
        int outIndex = 0;

        for (int i = ySize - 1; i >= 0; i--) {
            rotated[outIndex++] = nv21[i];
        }

        for (int i = nv21.length - 2; i >= ySize; i -= 2) {
            rotated[outIndex++] = nv21[i];
            rotated[outIndex++] = nv21[i + 1];
        }
        return rotated;
    }

    public void stop() {
        running = false;
        lastDeliveredTimestampNs = 0L;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
        Log.i(TAG, "Camera stopped");
        DiagnosticLog.i(TAG, "camera stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFrameRate() {
        return frameRate;
    }
}
