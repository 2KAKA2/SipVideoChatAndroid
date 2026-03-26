package com.sipvideochat.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 音频采集（Android版本）
 * 替代桌面端 javax.sound.sampled.TargetDataLine → android.media.AudioRecord
 * 保持相同的回调模式：AudioCaptureListener.onAudioData(byte[], int)
 */
public class AudioCapture {
    private static final String TAG = "AudioCapture";

    // 与桌面端一致：8kHz, 16bit, 单声道, 20ms帧 = 320字节
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 320; // 20ms @ 8kHz × 16bit = 320 bytes

    private AudioRecord audioRecord;
    private boolean isRunning = false;
    private Thread captureThread;

    public interface AudioCaptureListener {
        void onAudioData(byte[] data, int length);
    }

    public AudioCapture() {}

    /**
     * 启动音频采集
     */
    public void start(AudioCaptureListener listener) {
        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufSize, FRAME_SIZE * 4);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord初始化失败");
            return;
        }

        isRunning = true;
        audioRecord.startRecording();

        captureThread = new Thread(() -> {
            byte[] buffer = new byte[FRAME_SIZE];

            while (isRunning) {
                int read = audioRecord.read(buffer, 0, FRAME_SIZE);
                if (read > 0 && listener != null) {
                    listener.onAudioData(buffer, read);
                }
            }
        }, "AudioCapture");
        captureThread.start();

        Log.i(TAG, "音频采集已启动 (8kHz, 16bit, mono, 20ms frames)");
    }

    /**
     * 停止音频采集
     */
    public void stop() {
        isRunning = false;

        if (captureThread != null) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException e) {
                captureThread.interrupt();
            }
            captureThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止AudioRecord失败", e);
            }
            audioRecord = null;
        }

        Log.i(TAG, "音频采集已停止");
    }

    public boolean isRunning() {
        return isRunning;
    }
}
