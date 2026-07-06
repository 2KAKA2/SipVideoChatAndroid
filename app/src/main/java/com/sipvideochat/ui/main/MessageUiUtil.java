package com.sipvideochat.ui.main;

import com.sipvideochat.model.Message;

import java.util.Locale;

public final class MessageUiUtil {
    private MessageUiUtil() {
    }

    public static String buildPreviewText(Message message) {
        if (message == null || message.getType() == null) {
            return "";
        }

        switch (message.getType()) {
            case IMAGE:
                return "[Image] " + safeName(message);
            case VOICE:
                return String.format(Locale.getDefault(), "[Voice] %ds", Math.max(message.getDuration(), 1));
            case VIDEO:
                return String.format(Locale.getDefault(), "[Video] %s", safeName(message));
            case FILE:
                return String.format(Locale.getDefault(), "[File] %s", safeName(message));
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
                return "Sending";
            case SENT:
                return "Sent";
            case DELIVERED:
                return "Delivered";
            case READ:
                return "Read";
            case FAILED:
                return "Failed";
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
                return String.format(Locale.getDefault(), "Voice message %ds", Math.max(message.getDuration(), 1));
            case VIDEO:
                return String.format(Locale.getDefault(), "Video message %s", detailWithSize(message));
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
            return name + " - " + formatFileSize(message.getFileSize());
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
        return "Media file";
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
