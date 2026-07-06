package com.sipvideochat.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SharedPreferences-backed local call log storage.
 */
public class CallLogRepository {
    private static final String PREFS_NAME = "call_log_store";
    private static final String KEY_CALLS = "calls";
    private static final int MAX_RECORDS = 200;

    private final SharedPreferences preferences;

    public CallLogRepository(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<CallLogRecord> getCallLogs() {
        List<CallLogRecord> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_CALLS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                CallLogRecord record = new CallLogRecord();
                record.setRemoteUser(object.optString("remoteUser", ""));
                record.setOutgoing(object.optBoolean("outgoing", false));
                record.setVideo(object.optBoolean("video", false));
                record.setStartTime(object.optLong("startTime", System.currentTimeMillis()));
                record.setDurationSeconds(object.optLong("durationSeconds", 0));
                record.setStatus(object.optString("status", ""));
                records.add(record);
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    public synchronized void addCallLog(CallLogRecord record) {
        List<CallLogRecord> records = getCallLogs();
        records.add(0, record);
        while (records.size() > MAX_RECORDS) {
            records.remove(records.size() - 1);
        }
        saveCallLogs(records);
    }

    public synchronized void clearCallLogs() {
        preferences.edit().putString(KEY_CALLS, "[]").apply();
    }

    private void saveCallLogs(List<CallLogRecord> records) {
        JSONArray array = new JSONArray();
        for (CallLogRecord record : records) {
            JSONObject object = new JSONObject();
            try {
                object.put("remoteUser", record.getRemoteUser());
                object.put("outgoing", record.isOutgoing());
                object.put("video", record.isVideo());
                object.put("startTime", record.getStartTime());
                object.put("durationSeconds", record.getDurationSeconds());
                object.put("status", record.getStatus());
            } catch (Exception ignored) {
            }
            array.put(object);
        }
        preferences.edit().putString(KEY_CALLS, array.toString()).apply();
    }
}
