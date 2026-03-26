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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频采集（Android版本）
 * 替代桌面端 OpenCV VideoCapture → Android CameraX
 */
public class VideoCapture {
    private static final String TAG = "VideoCapture";

    private int width;
    private int height;
    private int frameRate;
    private boolean isRunning = false;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService analysisExecutor;
    private final List<VideoFrameListener> listeners = new ArrayList<>();

    public interface VideoFrameListener {
        /**
         * 收到一帧 YUV (NV21) 数据
         */
        void onFrame(byte[] yuvData, int width, int height);
        void onError(String error);
    }

    public VideoCapture(int width, int height, int frameRate) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
    }

    public VideoCapture() {
        this(320, 240, 25);
    }

    public void addListener(VideoFrameListener listener) {
        listeners.add(listener);
    }

    public void removeListener(VideoFrameListener listener) {
        listeners.remove(listener);
    }

    /**
     * 启动相机采集
     * @param context Activity 上下文
     * @param lifecycleOwner Activity 或 Fragment（CameraX 需要）
     * @param previewView 可选的预览 View，null 表示不显示本地预览
     */
    public void start(Context context, LifecycleOwner lifecycleOwner, PreviewView previewView) {
        analysisExecutor = Executors.newSingleThreadExecutor();

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // 预览 use case
                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(width, height))
                        .build();

                if (previewView != null) {
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                }

                // 帧分析 use case（获取每帧 YUV 数据）
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(width, height))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(analysisExecutor, this::processImage);

                // 使用前置摄像头
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis);

                isRunning = true;
                Log.i(TAG, "相机已启动 (" + width + "x" + height + "@" + frameRate + "fps)");

            } catch (Exception e) {
                Log.e(TAG, "启动相机失败", e);
                for (VideoFrameListener l : listeners) {
                    l.onError("启动相机失败: " + e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void processImage(@NonNull ImageProxy image) {
        try {
            if (!isRunning) {
                image.close();
                return;
            }

            // 将 ImageProxy (YUV_420_888) 转为 NV21 byte[]
            byte[] nv21 = imageProxyToNv21(image);

            for (VideoFrameListener l : listeners) {
                try {
                    l.onFrame(nv21, image.getWidth(), image.getHeight());
                } catch (Exception e) {
                    Log.e(TAG, "处理视频帧失败", e);
                }
            }
        } finally {
            image.close();
        }
    }

    /**
     * 将 CameraX ImageProxy (YUV_420_888) 转换为 NV21 格式
     */
    private byte[] imageProxyToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int w = image.getWidth();
        int h = image.getHeight();

        // Y plane
        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();

        // U plane
        ByteBuffer uBuffer = planes[1].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();

        // V plane
        ByteBuffer vBuffer = planes[2].getBuffer();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[w * h * 3 / 2];

        // Copy Y data
        int pos = 0;
        for (int row = 0; row < h; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv21, pos, w);
            pos += w;
        }

        // Interleave V and U (NV21 = VUVU...)
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

    public void stop() {
        isRunning = false;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            analysisExecutor = null;
        }
        Log.i(TAG, "相机已停止");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getFrameRate() { return frameRate; }
}
