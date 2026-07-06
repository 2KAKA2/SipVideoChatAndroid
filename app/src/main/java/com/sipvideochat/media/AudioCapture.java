package com.sipvideochat.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.sipvideochat.util.DiagnosticLog;

public class AudioCapture {
    private static final String TAG = "AudioCapture";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 320;

    private AudioRecord audioRecord;
    private boolean isRunning = false;
    private Thread captureThread;
    private int capturedFrames;

    public interface AudioCaptureListener {
        void onAudioData(byte[] data, int length);
    }

    public void start(AudioCaptureListener listener) {
        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufSize, FRAME_SIZE * 4);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            DiagnosticLog.e(TAG, "AudioRecord init failed");
            return;
        }

        isRunning = true;
        audioRecord.startRecording();

        captureThread = new Thread(() -> {
            byte[] buffer = new byte[FRAME_SIZE];
            while (isRunning) {
                int read = audioRecord.read(buffer, 0, FRAME_SIZE);
                if (read > 0 && listener != null) {
                    capturedFrames++;
                    if (capturedFrames == 1 || capturedFrames % 50 == 0) {
                        DiagnosticLog.i(TAG, "captured frames=" + capturedFrames + ", bytes=" + read);
                    }
                    listener.onAudioData(buffer, read);
                }
            }
        }, "AudioCapture");
        captureThread.start();

        Log.i(TAG, "Audio capture started");
        DiagnosticLog.i(TAG, "capture started sampleRate=" + SAMPLE_RATE + ", frameSize=" + FRAME_SIZE);
    }

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
                Log.e(TAG, "Failed to stop AudioRecord", e);
                DiagnosticLog.e(TAG, "failed to stop AudioRecord", e);
            }
            audioRecord = null;
        }

        Log.i(TAG, "Audio capture stopped");
        DiagnosticLog.i(TAG, "capture stopped frames=" + capturedFrames);
    }

    public boolean isRunning() {
        return isRunning;
    }
}
