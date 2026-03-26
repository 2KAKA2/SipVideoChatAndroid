package com.sipvideochat.ui.main;

import com.sipvideochat.model.Message;

import java.util.Locale;

/**
 * UI-oriented message formatting helpers.
 */
public final class MessageUiUtil {
    private MessageUiUtil() {
    }

    public static String buildPreviewText(Message message) {
        if (message == null || message.getType() == null) {
            return "";
        }

        switch (message.getType()) {
            case IMAGE:
                return "[图片] " + safeName(message);
            case VOICE:
                return String.format(Locale.getDefault(), "[语音] %ds", Math.max(message.getDuration(), 1));
            case VIDEO:
                return String.format(Locale.getDefault(), "[视频] %s", safeName(message));
            case FILE:
                return String.format(Locale.getDefault(), "[文件] %s", safeName(message));
            case TEXT:
            default:
                return message.getContent() == null ? "" : message.getContent();
        }
    }

    public static String buildStatusText(Message.MessageStatus status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case SENDING:
                return "发送中";
            case SENT:
                return "已发送";
            case DELIVERED:
                return "已送达";
            case READ:
                return "已读";
            case FAILED:
                return "失败";
            default:
                return "";
        }
    }

    public static String buildDetailText(Message message) {
        if (message == null || message.getType() == null) {
            return "";
        }

        switch (message.getType()) {
            case IMAGE:
                return detailWithSize(message);
            case VOICE:
                return String.format(Locale.getDefault(), "语音消息 %ds", Math.max(message.getDuration(), 1));
            case VIDEO:
                return String.format(Locale.getDefault(), "视频消息 %s", detailWithSize(message));
            case FILE:
                return detailWithSize(message);
            case TEXT:
            default:
                return buildPreviewText(message);
        }
    }

    private static String detailWithSize(Message message) {
        String name = safeName(message);
        if (message.getFileSize() > 0) {
            return name + " · " + formatFileSize(message.getFileSize());
        }
        return name;
    }

    private static String safeName(Message message) {
        if (message.getFileName() != null && !message.getFileName().isEmpty()) {
            return message.getFileName();
        }
        if (message.getMediaUrl() != null && !message.getMediaUrl().isEmpty()) {
            return message.getMediaUrl();
        }
        return "媒体文件";
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
