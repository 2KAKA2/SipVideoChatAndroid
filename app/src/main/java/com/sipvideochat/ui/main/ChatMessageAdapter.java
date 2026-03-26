package com.sipvideochat.ui.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sipvideochat.R;
import com.sipvideochat.model.Message;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for chat messages.
 */
public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.ViewHolder> {

    private final List<Message> messages;
    private final String myUsername;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final InlineVoiceMessagePlayer voiceMessagePlayer;

    public ChatMessageAdapter(List<Message> messages, String myUsername) {
        this.messages = messages;
        this.myUsername = myUsername;
        this.voiceMessagePlayer = new InlineVoiceMessagePlayer(this::notifyDataSetChanged);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message message = messages.get(position);
        String timeStr = timeFormat.format(new Date(message.getTimestamp()));
        boolean isSelf = myUsername.equals(message.getSenderId());

        if (isSelf) {
            holder.layoutSelf.setVisibility(View.VISIBLE);
            holder.layoutOther.setVisibility(View.GONE);
            bindSide(holder.layoutMediaSelf, holder.ivMediaSelf, holder.tvMediaCaptionSelf,
                    holder.tvMessageSelf, holder.tvTimeSelf, message, timeStr);
        } else {
            holder.layoutSelf.setVisibility(View.GONE);
            holder.layoutOther.setVisibility(View.VISIBLE);
            holder.tvSenderName.setText(message.getSenderName() != null ? message.getSenderName() : message.getSenderId());
            bindSide(holder.layoutMediaOther, holder.ivMediaOther, holder.tvMediaCaptionOther,
                    holder.tvMessageOther, holder.tvTimeOther, message, timeStr);
        }
    }

    private void bindSide(LinearLayout mediaLayout, ImageView mediaPreview, TextView mediaCaption,
                          TextView messageView, TextView timeView, Message message, String timeStr) {
        String statusText = MessageUiUtil.buildStatusText(message.getStatus());
        timeView.setText(statusText.isEmpty() ? timeStr : timeStr + " " + statusText);

        if (message.getType() == Message.MessageType.IMAGE || message.getType() == Message.MessageType.VIDEO) {
            bindMediaMessage(mediaLayout, mediaPreview, mediaCaption, messageView, message);
            return;
        }

        mediaLayout.setVisibility(View.GONE);
        mediaLayout.setOnClickListener(null);
        messageView.setVisibility(View.VISIBLE);

        if (message.getType() == Message.MessageType.VOICE) {
            bindVoiceMessage(messageView, message);
            return;
        }

        messageView.setOnClickListener(null);
        messageView.setText(MessageUiUtil.buildDetailText(message));
    }

    private void bindMediaMessage(LinearLayout mediaLayout, ImageView mediaPreview, TextView mediaCaption,
                                  TextView messageView, Message message) {
        messageView.setVisibility(View.GONE);
        mediaLayout.setVisibility(View.VISIBLE);
        mediaLayout.setOnClickListener(v -> MediaPreviewHelper.openPreview(v.getContext(), message));

        if (message.getType() == Message.MessageType.IMAGE) {
            bindImageMessage(mediaPreview, mediaCaption, message);
        } else {
            bindVideoMessage(mediaPreview, mediaCaption, message);
        }
    }

    private void bindImageMessage(ImageView mediaPreview, TextView mediaCaption, Message message) {
        mediaPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaPreview.setBackgroundResource(R.color.gray_light);
        mediaPreview.setImageResource(android.R.drawable.ic_menu_gallery);
        MediaPreviewHelper.loadImageThumbnail(mediaPreview.getContext(), mediaPreview,
                MediaPreviewHelper.resolvePreviewSource(message),
                null,
                () -> showImageFallback(mediaPreview));
        mediaCaption.setText(message.getFileName() != null ? message.getFileName() : "点击查看图片");
    }

    private void bindVideoMessage(ImageView mediaPreview, TextView mediaCaption, Message message) {
        mediaPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaPreview.setBackgroundResource(R.color.gray_dark);
        mediaPreview.setImageResource(android.R.drawable.ic_media_play);
        MediaPreviewHelper.loadVideoThumbnail(mediaPreview.getContext(), mediaPreview,
                MediaPreviewHelper.resolvePreviewSource(message),
                null,
                () -> showVideoFallback(mediaPreview));

        String fileName = message.getFileName() != null ? message.getFileName() : "视频";
        if (message.getDuration() > 0) {
            mediaCaption.setText(fileName + " · " + message.getDuration() + "s\n点击播放视频");
        } else {
            mediaCaption.setText(fileName + "\n点击播放视频");
        }
    }

    private void bindVoiceMessage(TextView messageView, Message message) {
        messageView.setOnClickListener(v -> voiceMessagePlayer.toggle(v.getContext(), message));

        String prefix;
        if (voiceMessagePlayer.isPreparing(message.getId())) {
            prefix = "…";
        } else if (voiceMessagePlayer.isPlaying(message.getId())) {
            prefix = "▮▮";
        } else {
            prefix = "▶";
        }

        String actionText;
        if (voiceMessagePlayer.isPreparing(message.getId())) {
            actionText = "准备播放";
        } else if (voiceMessagePlayer.isPlaying(message.getId())) {
            actionText = "点击暂停";
        } else if (voiceMessagePlayer.isPaused(message.getId())) {
            actionText = "继续播放";
        } else {
            actionText = "点击播放";
        }

        String durationText = Math.max(message.getDuration(), 1) + "s";
        messageView.setText(prefix + " 语音消息  " + durationText + "\n" + actionText);
    }

    private void showImageFallback(ImageView mediaPreview) {
        mediaPreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mediaPreview.setBackgroundResource(R.color.gray_light);
        mediaPreview.setImageResource(android.R.drawable.ic_menu_report_image);
    }

    private void showVideoFallback(ImageView mediaPreview) {
        mediaPreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mediaPreview.setBackgroundResource(R.color.gray_dark);
        mediaPreview.setImageResource(android.R.drawable.ic_media_play);
    }

    public void release() {
        voiceMessagePlayer.stop();
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout layoutSelf;
        LinearLayout layoutOther;
        LinearLayout layoutMediaSelf;
        LinearLayout layoutMediaOther;
        ImageView ivMediaSelf;
        ImageView ivMediaOther;
        TextView tvMediaCaptionSelf;
        TextView tvMediaCaptionOther;
        TextView tvMessageSelf;
        TextView tvTimeSelf;
        TextView tvMessageOther;
        TextView tvSenderName;
        TextView tvTimeOther;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            layoutSelf = itemView.findViewById(R.id.layoutSelf);
            layoutOther = itemView.findViewById(R.id.layoutOther);
            layoutMediaSelf = itemView.findViewById(R.id.layoutMediaSelf);
            layoutMediaOther = itemView.findViewById(R.id.layoutMediaOther);
            ivMediaSelf = itemView.findViewById(R.id.ivMediaSelf);
            ivMediaOther = itemView.findViewById(R.id.ivMediaOther);
            tvMediaCaptionSelf = itemView.findViewById(R.id.tvMediaCaptionSelf);
            tvMediaCaptionOther = itemView.findViewById(R.id.tvMediaCaptionOther);
            tvMessageSelf = itemView.findViewById(R.id.tvMessageSelf);
            tvTimeSelf = itemView.findViewById(R.id.tvTimeSelf);
            tvMessageOther = itemView.findViewById(R.id.tvMessageOther);
            tvSenderName = itemView.findViewById(R.id.tvSenderName);
            tvTimeOther = itemView.findViewById(R.id.tvTimeOther);
        }
    }
}
