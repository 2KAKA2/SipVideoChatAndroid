package com.sipvideochat.model;

import com.sipvideochat.config.ClientConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class GroupAdminClient {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private GroupAdminClient() {
    }

    public static List<ChatGroup> listGroups(ClientConfig config) throws IOException {
        HttpURLConnection connection = openConnection(config, "/api/groups", "GET");
        int responseCode = connection.getResponseCode();
        String body = readText(responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        connection.disconnect();

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("Admin list failed (" + responseCode + "): " + body);
        }

        List<ChatGroup> result = new ArrayList<>();
        try {
            JSONObject json = new JSONObject(body);
            JSONArray groups = json.optJSONArray("groups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject object = groups.optJSONObject(i);
                    if (object != null) {
                        result.add(fromJson(object));
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Invalid admin response", e);
        }
        return result;
    }

    public static ChatGroup createGroup(ClientConfig config, ChatGroup group) throws IOException {
        return writeGroup(config, "/api/groups", group);
    }

    public static ChatGroup updateGroup(ClientConfig config, ChatGroup group) throws IOException {
        return writeGroup(config, "/api/groups/" + encode(group.getId()), group);
    }

    public static void deleteGroup(ClientConfig config, String groupId) throws IOException {
        HttpURLConnection connection = openConnection(config, "/api/groups/" + encode(groupId), "DELETE");
        int responseCode = connection.getResponseCode();
        String body = readText(responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        connection.disconnect();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("Admin delete failed (" + responseCode + "): " + body);
        }
    }

    private static ChatGroup writeGroup(ClientConfig config, String path, ChatGroup group) throws IOException {
        HttpURLConnection connection = openConnection(config, path, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String payload = buildForm(group);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        String body = readText(responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        connection.disconnect();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("Admin save failed (" + responseCode + "): " + body);
        }
        try {
            return fromJson(new JSONObject(body));
        } catch (Exception e) {
            throw new IOException("Invalid admin save response", e);
        }
    }

    private static String buildForm(ChatGroup group) throws IOException {
        StringBuilder builder = new StringBuilder();
        appendForm(builder, "name", group.getName());
        appendForm(builder, "description", group.getDescription());
        appendForm(builder, "ownerId", group.getOwnerId());
        appendForm(builder, "members", join(group.getMemberIds()));
        appendForm(builder, "admins", join(group.getAdminIds()));
        return builder.toString();
    }

    private static void appendForm(StringBuilder builder, String key, String value) throws IOException {
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(encode(key)).append('=').append(encode(value == null ? "" : value));
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private static HttpURLConnection openConnection(ClientConfig config, String path, String method) throws IOException {
        if (config == null) {
            throw new IOException("Client config unavailable");
        }
        String host = config.getAdminServerHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("Admin server host unavailable");
        }
        URL url = new URL("http://" + host.trim() + ":" + config.getAdminServerPort() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Connection", "close");
        return connection;
    }

    private static ChatGroup fromJson(JSONObject object) {
        ChatGroup group = new ChatGroup();
        group.setId(object.optString("id", group.getId()));
        group.setName(object.optString("name", null));
        group.setDescription(object.optString("description", null));
        group.setOwnerId(object.optString("ownerId", null));

        List<String> members = new ArrayList<>();
        JSONArray memberArray = object.optJSONArray("members");
        if (memberArray != null) {
            for (int i = 0; i < memberArray.length(); i++) {
                String member = memberArray.optString(i);
                if (member != null && !member.isEmpty()) {
                    members.add(member);
                }
            }
        }
        group.setMemberIds(members);

        List<String> admins = new ArrayList<>();
        JSONArray adminArray = object.optJSONArray("admins");
        if (adminArray != null) {
            for (int i = 0; i < adminArray.length(); i++) {
                String admin = adminArray.optString(i);
                if (admin != null && !admin.isEmpty()) {
                    admins.add(admin);
                }
            }
        }
        group.setAdminIds(admins);
        return group;
    }

    private static String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }
}
