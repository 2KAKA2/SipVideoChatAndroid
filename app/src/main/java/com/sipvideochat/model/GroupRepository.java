package com.sipvideochat.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GroupRepository {
    private static final String PREFS_NAME = "chat_group_store";
    private static final String KEY_GROUPS = "groups";

    private final SharedPreferences preferences;

    public GroupRepository(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<ChatGroup> getGroups() {
        List<ChatGroup> groups = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_GROUPS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    groups.add(fromJson(object));
                }
            }
        } catch (Exception ignored) {
        }
        return groups;
    }

    public synchronized ChatGroup getGroup(String groupId) {
        for (ChatGroup group : getGroups()) {
            if (groupId.equals(group.getId())) {
                return group;
            }
        }
        return null;
    }

    public synchronized void upsertGroup(ChatGroup group) {
        List<ChatGroup> groups = getGroups();
        boolean replaced = false;
        for (int i = 0; i < groups.size(); i++) {
            if (group.getId().equals(groups.get(i).getId())) {
                groups.set(i, group);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            groups.add(group);
        }
        saveGroups(groups);
    }

    public synchronized void deleteGroup(String groupId) {
        List<ChatGroup> groups = getGroups();
        groups.removeIf(group -> groupId.equals(group.getId()));
        saveGroups(groups);
    }

    private void saveGroups(List<ChatGroup> groups) {
        JSONArray array = new JSONArray();
        for (ChatGroup group : groups) {
            array.put(toJson(group));
        }
        preferences.edit().putString(KEY_GROUPS, array.toString()).apply();
    }

    private JSONObject toJson(ChatGroup group) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", group.getId());
            object.put("name", group.getName());
            object.put("description", group.getDescription());
            object.put("ownerId", group.getOwnerId());

            JSONArray members = new JSONArray();
            for (String memberId : group.getMemberIds()) {
                members.put(memberId);
            }
            object.put("memberIds", members);

            JSONArray admins = new JSONArray();
            for (String adminId : group.getAdminIds()) {
                admins.put(adminId);
            }
            object.put("adminIds", admins);
        } catch (Exception ignored) {
        }
        return object;
    }

    private ChatGroup fromJson(JSONObject object) {
        ChatGroup group = new ChatGroup();
        group.setId(object.optString("id", group.getId()));
        group.setName(optNullableString(object, "name"));
        group.setDescription(optNullableString(object, "description"));
        group.setOwnerId(optNullableString(object, "ownerId"));

        List<String> memberIds = new ArrayList<>();
        JSONArray members = object.optJSONArray("memberIds");
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                String memberId = members.optString(i);
                if (memberId != null && !memberId.isEmpty()) {
                    memberIds.add(memberId);
                }
            }
        }
        group.setMemberIds(memberIds);

        List<String> adminIds = new ArrayList<>();
        JSONArray admins = object.optJSONArray("adminIds");
        if (admins != null) {
            for (int i = 0; i < admins.length(); i++) {
                String adminId = admins.optString(i);
                if (adminId != null && !adminId.isEmpty()) {
                    adminIds.add(adminId);
                }
            }
        }
        group.setAdminIds(adminIds);
        return group;
    }

    private String optNullableString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key, null);
    }
}
