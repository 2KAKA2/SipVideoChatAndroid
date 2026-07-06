package com.sipvideochat.ui.main;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.model.CallLogRecord;
import com.sipvideochat.model.CallLogRepository;
import com.sipvideochat.model.Message;
import com.sipvideochat.model.MessageRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Android admin dashboard for local message and call statistics.
 */
public class AdminDashboardActivity extends AppCompatActivity {
    private final SimpleDateFormat dateTimeFormat =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private TextView tvAccountSummary;
    private TextView tvMessageTotal;
    private TextView tvSentTotal;
    private TextView tvReceivedTotal;
    private TextView tvTextTotal;
    private TextView tvImageTotal;
    private TextView tvVoiceTotal;
    private TextView tvVideoTotal;
    private TextView tvCallTotal;
    private TextView tvCallDuration;
    private TextView tvRecentMessages;
    private TextView tvRecentCalls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_dashboard);

        MaterialToolbar toolbar = findViewById(R.id.toolbarAdmin);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvAccountSummary = findViewById(R.id.tvAccountSummary);
        tvMessageTotal = findViewById(R.id.tvMessageTotal);
        tvSentTotal = findViewById(R.id.tvSentTotal);
        tvReceivedTotal = findViewById(R.id.tvReceivedTotal);
        tvTextTotal = findViewById(R.id.tvTextTotal);
        tvImageTotal = findViewById(R.id.tvImageTotal);
        tvVoiceTotal = findViewById(R.id.tvVoiceTotal);
        tvVideoTotal = findViewById(R.id.tvVideoTotal);
        tvCallTotal = findViewById(R.id.tvCallTotal);
        tvCallDuration = findViewById(R.id.tvCallDuration);
        tvRecentMessages = findViewById(R.id.tvRecentMessages);
        tvRecentCalls = findViewById(R.id.tvRecentCalls);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDashboard();
    }

    private void loadDashboard() {
        ClientConfig config = new ClientConfig();
        config.load(this);
        String username = config.getUsername() == null ? "" : config.getUsername().trim();
        tvAccountSummary.setText(username.isEmpty()
                ? "当前显示的是这台手机本地保存的统计数据"
                : "当前账号: " + username + " · 显示这台手机本地保存的统计数据");

        MessageRepository messageRepository = new MessageRepository(this);
        List<String> keys = messageRepository.getContacts();
        List<Message> allMessages = new ArrayList<>();
        for (String key : keys) {
            allMessages.addAll(messageRepository.getMessages(key));
        }
        allMessages.sort(Comparator.comparingLong(Message::getTimestamp).reversed());

        int sent = 0;
        int received = 0;
        int text = 0;
        int image = 0;
        int voice = 0;
        int video = 0;

        for (Message message : allMessages) {
            boolean isSent = username.equals(message.getSenderId());
            if (isSent) {
                sent++;
            } else {
                received++;
            }

            Message.MessageType type = message.getType();
            if (type == null) {
                text++;
                continue;
            }
            switch (type) {
                case IMAGE:
                    image++;
                    break;
                case VOICE:
                    voice++;
                    break;
                case VIDEO:
                    video++;
                    break;
                case FILE:
                case SYSTEM:
                case TEXT:
                default:
                    text++;
                    break;
            }
        }

        tvMessageTotal.setText(String.valueOf(allMessages.size()));
        tvSentTotal.setText(String.valueOf(sent));
        tvReceivedTotal.setText(String.valueOf(received));
        tvTextTotal.setText(String.valueOf(text));
        tvImageTotal.setText(String.valueOf(image));
        tvVoiceTotal.setText(String.valueOf(voice));
        tvVideoTotal.setText(String.valueOf(video));

        CallLogRepository callLogRepository = new CallLogRepository(this);
        List<CallLogRecord> callLogs = callLogRepository.getCallLogs();
        long totalCallSeconds = 0L;
        for (CallLogRecord log : callLogs) {
            totalCallSeconds += Math.max(0L, log.getDurationSeconds());
        }
        tvCallTotal.setText(String.valueOf(callLogs.size()));
        tvCallDuration.setText(formatDuration(totalCallSeconds));

        tvRecentMessages.setText(buildRecentMessagesText(allMessages, username));
        tvRecentCalls.setText(buildRecentCallsText(callLogs));
    }

    private String buildRecentMessagesText(List<Message> messages, String username) {
        if (messages.isEmpty()) {
            return "暂无消息记录";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(messages.size(), 20);
        for (int i = 0; i < limit; i++) {
            Message message = messages.get(i);
            boolean isSent = username.equals(message.getSenderId());
            String counterpart = isSent
                    ? safeText(message.getGroupId() != null ? message.getGroupId() : message.getReceiverId(), "未知对象")
                    : safeText(message.getSenderId(), "未知对象");
            sb.append(dateTimeFormat.format(new Date(message.getTimestamp())))
                    .append("  ")
                    .append(isSent ? "发送" : "接收")
                    .append("  ")
                    .append(formatType(message.getType()))
                    .append("  ")
                    .append(counterpart)
                    .append('\n')
                    .append("  ")
                    .append(buildMessagePreview(message))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private String buildRecentCallsText(List<CallLogRecord> callLogs) {
        if (callLogs.isEmpty()) {
            return "暂无通话记录";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(callLogs.size(), 20);
        for (int i = 0; i < limit; i++) {
            CallLogRecord log = callLogs.get(i);
            sb.append(dateTimeFormat.format(new Date(log.getStartTime())))
                    .append("  ")
                    .append(log.isOutgoing() ? "主叫" : "被叫")
                    .append("  ")
                    .append(log.isVideo() ? "视频" : "语音")
                    .append("  ")
                    .append(safeText(log.getRemoteUser(), "未知用户"))
                    .append('\n')
                    .append("  状态: ")
                    .append(safeText(log.getStatus(), "未知"))
                    .append(" · 时长: ")
                    .append(formatDuration(log.getDurationSeconds()))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private String formatType(Message.MessageType type) {
        if (type == null) {
            return "文本";
        }
        switch (type) {
            case IMAGE:
                return "图片";
            case VOICE:
                return "语音";
            case VIDEO:
                return "视频";
            case FILE:
                return "文件";
            case SYSTEM:
                return "系统";
            case TEXT:
            default:
                return "文本";
        }
    }

    private String buildMessagePreview(Message message) {
        if (message == null) {
            return "";
        }
        Message.MessageType type = message.getType();
        if (type == null || type == Message.MessageType.TEXT || type == Message.MessageType.SYSTEM) {
            return safeText(message.getContent(), "(空文本)");
        }
        switch (type) {
            case IMAGE:
                return "[图片] " + safeText(message.getFileName(), "未命名图片");
            case VOICE:
                return "[语音] " + Math.max(1, message.getDuration()) + " 秒";
            case VIDEO:
                return "[视频] " + safeText(message.getFileName(), "未命名视频");
            case FILE:
                return "[文件] " + safeText(message.getFileName(), "未命名文件");
            default:
                return safeText(message.getContent(), "");
        }
    }

    private String formatDuration(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long hours = safeSeconds / 3600L;
        long minutes = (safeSeconds % 3600L) / 60L;
        long secs = safeSeconds % 60L;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
    }

    private String safeText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
