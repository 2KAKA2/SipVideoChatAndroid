package com.sipvideochat.ui.main;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

import com.sipvideochat.model.Message;

/**
 * Inline audio player for voice message bubbles.
 */
public class InlineVoiceMessagePlayer {
    private static final String TAG = "InlineVoicePlayer";

    public interface PlaybackListener {
        void onPlaybackStateChanged();
    }

    private final PlaybackListener playbackListener;

    private MediaPlayer mediaPlayer;
    private String activeMessageId;
    private String preparingMessageId;
    private int requestVersion;
    private boolean prepared;

    public InlineVoiceMessagePlayer(PlaybackListener playbackListener) {
        this.playbackListener = playbackListener;
    }

    public void toggle(Context context, Message message) {
        if (context == null || message == null || message.getId() == null) {
            return;
        }

        String source = MediaPreviewHelper.resolvePreviewSource(message);
        if (source == null || source.isEmpty()) {
            return;
        }

        String messageId = message.getId();
        if (messageId.equals(preparingMessageId)) {
            stop();
            return;
        }

        if (messageId.equals(activeMessageId) && mediaPlayer != null && prepared) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
            notifyStateChanged();
            return;
        }

        stop();
        preparingMessageId = messageId;
        final int currentRequest = ++requestVersion;
        notifyStateChanged();

        MediaPreviewHelper.preparePreviewSource(context, source, new MediaPreviewHelper.SourceCallback() {
            @Override
            public void onSuccess(String resolvedSource) {
                if (currentRequest != requestVersion || !messageId.equals(preparingMessageId)) {
                    return;
                }
                startPlayer(context, messageId, resolvedSource, currentRequest);
            }

            @Override
            public void onFailure(Exception exception) {
                if (currentRequest != requestVersion) {
                    return;
                }
                Log.w(TAG, "Failed to prepare voice source for " + messageId, exception);
                preparingMessageId = null;
                activeMessageId = null;
                prepared = false;
                releasePlayer();
                notifyStateChanged();
            }
        });
    }

    public boolean isPlaying(String messageId) {
        return messageId != null
                && messageId.equals(activeMessageId)
                && mediaPlayer != null
                && prepared
                && mediaPlayer.isPlaying();
    }

    public boolean isPaused(String messageId) {
        return messageId != null
                && messageId.equals(activeMessageId)
                && mediaPlayer != null
                && prepared
                && !mediaPlayer.isPlaying();
    }

    public boolean isPreparing(String messageId) {
        return messageId != null && messageId.equals(preparingMessageId);
    }

    public void stop() {
        requestVersion++;
        preparingMessageId = null;
        activeMessageId = null;
        prepared = false;
        releasePlayer();
        notifyStateChanged();
    }

    private void startPlayer(Context context, String messageId, String resolvedSource, int currentRequest) {
        releasePlayer();
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(context, Uri.parse(resolvedSource));
            mediaPlayer.setOnPreparedListener(mp -> {
                if (currentRequest != requestVersion || !messageId.equals(preparingMessageId)) {
                    return;
                }
                preparingMessageId = null;
                activeMessageId = messageId;
                prepared = true;
                mp.start();
                notifyStateChanged();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                preparingMessageId = null;
                activeMessageId = null;
                prepared = false;
                releasePlayer();
                notifyStateChanged();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "Voice playback error what=" + what + " extra=" + extra);
                preparingMessageId = null;
                activeMessageId = null;
                prepared = false;
                releasePlayer();
                notifyStateChanged();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to start voice player for " + messageId, e);
            preparingMessageId = null;
            activeMessageId = null;
            prepared = false;
            releasePlayer();
            notifyStateChanged();
        }
    }

    private void releasePlayer() {
        if (mediaPlayer == null) {
            return;
        }
        try {
            mediaPlayer.reset();
        } catch (Exception ignored) {
        }
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
        }
        mediaPlayer = null;
    }

    private void notifyStateChanged() {
        if (playbackListener != null) {
            playbackListener.onPlaybackStateChanged();
        }
    }
}
