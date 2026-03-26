package com.sipvideochat.ui.main;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sipvideochat.R;
import com.sipvideochat.model.Message;

import java.util.Locale;

/**
 * Full-screen preview page for image, video, and voice messages.
 */
public class MediaPreviewActivity extends AppCompatActivity {
    private ImageView ivPreview;
    private VideoView videoPreview;
    private LinearLayout audioContainer;
    private TextView tvPreviewTitle;
    private TextView tvAudioTitle;
    private TextView tvAudioSubtitle;
    private ImageButton btnCenterPlay;
    private ImageButton btnPlayPause;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvDuration;
    private View playbackBar;
    private View progressBar;
    private TextView tvError;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
            if (isPlaybackPrepared()) {
                progressHandler.postDelayed(this, 250L);
            }
        }
    };

    private Message.MessageType mediaType = Message.MessageType.TEXT;
    private MediaPlayer audioPlayer;
    private boolean isPrepared;
    private boolean isSeeking;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_preview);

        ivPreview = findViewById(R.id.ivPreview);
        videoPreview = findViewById(R.id.videoPreview);
        audioContainer = findViewById(R.id.audioContainer);
        tvPreviewTitle = findViewById(R.id.tvPreviewTitle);
        tvAudioTitle = findViewById(R.id.tvAudioTitle);
        tvAudioSubtitle = findViewById(R.id.tvAudioSubtitle);
        btnCenterPlay = findViewById(R.id.btnCenterPlay);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        seekBar = findViewById(R.id.seekBar);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvDuration = findViewById(R.id.tvDuration);
        playbackBar = findViewById(R.id.playbackBar);
        progressBar = findViewById(R.id.progressBar);
        tvError = findViewById(R.id.tvError);

        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finish());
        btnCenterPlay.setOnClickListener(v -> togglePlayback());
        btnPlayPause.setOnClickListener(v -> togglePlayback());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekTo(seekBar.getProgress());
                isSeeking = false;
            }
        });

        String source = getIntent().getStringExtra(MediaPreviewHelper.EXTRA_SOURCE);
        String typeName = getIntent().getStringExtra(MediaPreviewHelper.EXTRA_TYPE);
        String title = getIntent().getStringExtra(MediaPreviewHelper.EXTRA_TITLE);

        if (typeName != null) {
            try {
                mediaType = Message.MessageType.valueOf(typeName);
            } catch (IllegalArgumentException ignored) {
                mediaType = Message.MessageType.TEXT;
            }
        }

        String resolvedTitle = (title != null && !title.isEmpty()) ? title : defaultTitleForType(mediaType);
        tvPreviewTitle.setText(resolvedTitle);
        tvAudioTitle.setText(resolvedTitle);

        if (source == null || source.isEmpty()) {
            showError("无法打开媒体");
            return;
        }

        if (mediaType == Message.MessageType.IMAGE) {
            showImage(source);
        } else if (mediaType == Message.MessageType.VIDEO) {
            showVideo(source);
        } else if (mediaType == Message.MessageType.VOICE) {
            showVoice(source);
        } else {
            showError("暂不支持该媒体类型");
        }
    }

    private void showImage(String source) {
        showImageViewOnly();
        MediaPreviewHelper.loadFullImagePreview(this, ivPreview, source,
                () -> progressBar.setVisibility(View.GONE),
                () -> showError("图片加载失败"));
    }

    private void showVideo(String source) {
        showPlaybackLayout(true);
        MediaPreviewHelper.preparePreviewSource(this, source, new MediaPreviewHelper.SourceCallback() {
            @Override
            public void onSuccess(String resolvedSource) {
                videoPreview.setVideoURI(Uri.parse(resolvedSource));
                videoPreview.setOnPreparedListener(mp -> {
                    isPrepared = true;
                    progressBar.setVisibility(View.GONE);
                    configurePlaybackDuration(mp.getDuration());
                    startPlayback();
                });
                videoPreview.setOnCompletionListener(mp -> {
                    pausePlaybackUi();
                    seekBar.setProgress(seekBar.getMax());
                    tvCurrentTime.setText(formatDuration(seekBar.getMax()));
                });
                videoPreview.setOnErrorListener((mp, what, extra) -> {
                    showError("视频加载失败");
                    return true;
                });
                videoPreview.setOnClickListener(v -> togglePlayback());
                videoPreview.requestFocus();
            }

            @Override
            public void onFailure(Exception exception) {
                showError("视频加载失败");
            }
        });
    }

    private void showVoice(String source) {
        showPlaybackLayout(false);
        tvAudioSubtitle.setText("语音消息");
        MediaPreviewHelper.preparePreviewSource(this, source, new MediaPreviewHelper.SourceCallback() {
            @Override
            public void onSuccess(String resolvedSource) {
                releaseAudioPlayer();
                audioPlayer = new MediaPlayer();
                try {
                    audioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build());
                    audioPlayer.setDataSource(MediaPreviewActivity.this, Uri.parse(resolvedSource));
                    audioPlayer.setOnPreparedListener(mp -> {
                        isPrepared = true;
                        progressBar.setVisibility(View.GONE);
                        configurePlaybackDuration(mp.getDuration());
                        tvAudioSubtitle.setText("点击播放或拖动进度条");
                        startPlayback();
                    });
                    audioPlayer.setOnCompletionListener(mp -> {
                        pausePlaybackUi();
                        seekBar.setProgress(seekBar.getMax());
                        tvCurrentTime.setText(formatDuration(seekBar.getMax()));
                    });
                    audioPlayer.setOnErrorListener((mp, what, extra) -> {
                        showError("语音加载失败");
                        return true;
                    });
                    audioPlayer.prepareAsync();
                } catch (Exception e) {
                    showError("语音加载失败");
                }
            }

            @Override
            public void onFailure(Exception exception) {
                showError("语音加载失败");
            }
        });
    }

    private void showImageViewOnly() {
        stopProgressUpdates();
        isPrepared = false;
        tvPreviewTitle.setVisibility(View.VISIBLE);
        ivPreview.setVisibility(View.VISIBLE);
        videoPreview.setVisibility(View.GONE);
        audioContainer.setVisibility(View.GONE);
        playbackBar.setVisibility(View.GONE);
        btnCenterPlay.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
    }

    private void showPlaybackLayout(boolean videoMode) {
        stopProgressUpdates();
        isPrepared = false;
        tvPreviewTitle.setVisibility(videoMode ? View.VISIBLE : View.GONE);
        ivPreview.setVisibility(View.GONE);
        videoPreview.setVisibility(videoMode ? View.VISIBLE : View.GONE);
        audioContainer.setVisibility(videoMode ? View.GONE : View.VISIBLE);
        playbackBar.setVisibility(View.VISIBLE);
        btnCenterPlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        resetPlaybackUi();
    }

    private void resetPlaybackUi() {
        seekBar.setProgress(0);
        seekBar.setMax(0);
        tvCurrentTime.setText(formatDuration(0));
        tvDuration.setText(formatDuration(0));
        updatePlayButtons(false);
    }

    private void configurePlaybackDuration(int durationMs) {
        int safeDuration = Math.max(durationMs, 0);
        seekBar.setMax(safeDuration);
        tvDuration.setText(formatDuration(safeDuration));
        tvCurrentTime.setText(formatDuration(0));
        updatePlayButtons(false);
    }

    private void togglePlayback() {
        if (!isPrepared) {
            return;
        }
        if (isPlaying()) {
            pausePlayback();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        if (!isPrepared) {
            return;
        }

        if (mediaType == Message.MessageType.VIDEO) {
            videoPreview.start();
        } else if (mediaType == Message.MessageType.VOICE && audioPlayer != null) {
            audioPlayer.start();
        }

        updatePlayButtons(true);
        startProgressUpdates();
    }

    private void pausePlayback() {
        if (mediaType == Message.MessageType.VIDEO) {
            if (videoPreview.isPlaying()) {
                videoPreview.pause();
            }
        } else if (mediaType == Message.MessageType.VOICE && audioPlayer != null && audioPlayer.isPlaying()) {
            audioPlayer.pause();
        }

        pausePlaybackUi();
    }

    private void pausePlaybackUi() {
        updatePlayButtons(false);
        stopProgressUpdates();
        updatePlaybackProgress();
    }

    private void seekTo(int positionMs) {
        if (!isPrepared) {
            return;
        }

        if (mediaType == Message.MessageType.VIDEO) {
            videoPreview.seekTo(positionMs);
        } else if (mediaType == Message.MessageType.VOICE && audioPlayer != null) {
            audioPlayer.seekTo(positionMs);
        }
        updatePlaybackProgress();
    }

    private void updatePlaybackProgress() {
        if (!isPlaybackPrepared() || isSeeking) {
            return;
        }

        int position = getCurrentPosition();
        seekBar.setProgress(position);
        tvCurrentTime.setText(formatDuration(position));
        updatePlayButtons(isPlaying());
    }

    private int getCurrentPosition() {
        if (!isPrepared) {
            return 0;
        }
        if (mediaType == Message.MessageType.VIDEO) {
            return videoPreview.getCurrentPosition();
        }
        if (mediaType == Message.MessageType.VOICE && audioPlayer != null) {
            return audioPlayer.getCurrentPosition();
        }
        return 0;
    }

    private boolean isPlaying() {
        if (!isPrepared) {
            return false;
        }
        if (mediaType == Message.MessageType.VIDEO) {
            return videoPreview.isPlaying();
        }
        return mediaType == Message.MessageType.VOICE && audioPlayer != null && audioPlayer.isPlaying();
    }

    private boolean isPlaybackPrepared() {
        return isPrepared && (mediaType == Message.MessageType.VIDEO || mediaType == Message.MessageType.VOICE);
    }

    private void updatePlayButtons(boolean playing) {
        btnPlayPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        btnCenterPlay.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        btnCenterPlay.setVisibility(playing ? View.GONE : (playbackBar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE));
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        progressHandler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
    }

    private void showError(String message) {
        stopProgressUpdates();
        isPrepared = false;
        ivPreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);
        audioContainer.setVisibility(View.GONE);
        playbackBar.setVisibility(View.GONE);
        btnCenterPlay.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        tvError.setVisibility(View.VISIBLE);
        tvError.setText(message);
    }

    private String defaultTitleForType(Message.MessageType type) {
        switch (type) {
            case IMAGE:
                return "图片预览";
            case VIDEO:
                return "视频播放";
            case VOICE:
                return "语音播放";
            default:
                return "媒体预览";
        }
    }

    private String formatDuration(int durationMs) {
        int totalSeconds = Math.max(durationMs / 1000, 0);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void releaseAudioPlayer() {
        if (audioPlayer == null) {
            return;
        }
        try {
            audioPlayer.release();
        } catch (Exception ignored) {
        }
        audioPlayer = null;
    }

    @Override
    protected void onStop() {
        pausePlayback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopProgressUpdates();
        releaseAudioPlayer();
        super.onDestroy();
    }
}
