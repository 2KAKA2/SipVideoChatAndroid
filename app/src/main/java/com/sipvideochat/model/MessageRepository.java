package com.sipvideochat.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight local persistence for chat conversations.
 */
public class MessageRepository {
    private static final String PREFS_NAME = "chat_message_store";
    private static final String KEY_CONTACTS = "contacts";
    private static final String KEY_MESSAGES_PREFIX = "messages_";
    private static final String GROUP_KEY_PREFIX = "group:";

    private final SharedPreferences preferences;

    public MessageRepository(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<String> getContacts() {
        List<String> contacts = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_CONTACTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String contact = array.optString(i);
                if (isDirectContactKey(contact)) {
                    contacts.add(contact);
                }
            }
        } catch (JSONException ignored) {
        }
        return contacts;
    }

    public synchronized void saveContacts(List<String> contacts) {
        JSONArray array = new JSONArray();
        for (String contact : contacts) {
            if (isDirectContactKey(contact)) {
                array.put(contact);
            }
        }
        preferences.edit().putString(KEY_CONTACTS, array.toString()).apply();
    }

    public synchronized List<Message> getMessages(String contactUser) {
        List<Message> messages = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(getMessagesKey(contactUser), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    messages.add(fromJson(object));
                }
            }
        } catch (JSONException ignored) {
        }
        return messages;
    }

    public synchronized void upsertMessage(String contactUser, Message message) {
        List<Message> messages = getMessages(contactUser);
        boolean replaced = false;
        for (int i = 0; i < messages.size(); i++) {
            if (message.getId().equals(messages.get(i).getId())) {
                messages.set(i, message);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            messages.add(message);
        }
        saveMessages(contactUser, messages);
        ensureContact(contactUser);
    }

    private void ensureContact(String contactUser) {
        if (!isDirectContactKey(contactUser)) {
            return;
        }
        List<String> contacts = getContacts();
        if (!contacts.contains(contactUser)) {
            contacts.add(contactUser);
            saveContacts(contacts);
        }
    }

    private boolean isDirectContactKey(String key) {
        return key != null && !key.isEmpty() && !key.startsWith(GROUP_KEY_PREFIX);
    }

    private void saveMessages(String contactUser, List<Message> messages) {
        JSONArray array = new JSONArray();
        for (Message message : messages) {
            array.put(toJson(message));
        }
        preferences.edit().putString(getMessagesKey(contactUser), array.toString()).apply();
    }

    private String getMessagesKey(String contactUser) {
        return KEY_MESSAGES_PREFIX + contactUser;
    }

    private JSONObject toJson(Message message) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", message.getId());
            object.put("senderId", message.getSenderId());
            object.put("senderName", message.getSenderName());
            object.put("receiverId", message.getReceiverId());
            object.put("groupId", message.getGroupId());
            object.put("type", message.getType() != null ? message.getType().name() : JSONObject.NULL);
            object.put("content", message.getContent());
            object.put("mediaUrl", message.getMediaUrl());
            object.put("localUri", message.getLocalUri());
            object.put("mimeType", message.getMimeType());
            object.put("fileName", message.getFileName());
            object.put("fileSize", message.getFileSize());
            object.put("duration", message.getDuration());
            object.put("timestamp", message.getTimestamp());
            object.put("status", message.getStatus() != null ? message.getStatus().name() : JSONObject.NULL);
            object.put("errorMessage", message.getErrorMessage());
        } catch (Exception ignored) {
        }
        return object;
    }

    private Message fromJson(JSONObject object) {
        Message message = new Message();
        message.setId(object.optString("id", message.getId()));
        message.setSenderId(optNullableString(object, "senderId"));
        message.setSenderName(optNullableString(object, "senderName"));
        message.setReceiverId(optNullableString(object, "receiverId"));
        message.setGroupId(optNullableString(object, "groupId"));

        String type = optNullableString(object, "type");
        if (type != null) {
            try {
                message.setType(Message.MessageType.valueOf(type));
            } catch (IllegalArgumentException ignored) {
            }
        }

        message.setContent(optNullableString(object, "content"));
        message.setMediaUrl(optNullableString(object, "mediaUrl"));
        message.setLocalUri(optNullableString(object, "localUri"));
        message.setMimeType(optNullableString(object, "mimeType"));
        message.setFileName(optNullableString(object, "fileName"));
        message.setFileSize(object.optLong("fileSize", 0));
        message.setDuration(object.optInt("duration", 0));
        message.setTimestamp(object.optLong("timestamp", System.currentTimeMillis()));

        String status = optNullableString(object, "status");
        if (status != null) {
            try {
                message.setStatus(Message.MessageStatus.valueOf(status));
            } catch (IllegalArgumentException ignored) {
            }
        }

        message.setErrorMessage(optNullableString(object, "errorMessage"));
        return message;
    }

    private String optNullableString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key, null);
    }
}
