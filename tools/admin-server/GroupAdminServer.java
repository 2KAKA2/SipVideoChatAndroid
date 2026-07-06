import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public class GroupAdminServer {
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8090;

    private final Map<String, GroupRecord> groups = new LinkedHashMap<>();
    private final Path storeFile;

    public GroupAdminServer(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        Path storeFile = args.length > 2
                ? Paths.get(args[2])
                : Paths.get("GroupAdminStore.txt");

        GroupAdminServer app = new GroupAdminServer(storeFile);
        app.start(host, port);
    }

    private void start(String host, int port) throws IOException {
        Files.createDirectories(storeFile.toAbsolutePath().getParent() == null
                ? Paths.get(".")
                : storeFile.toAbsolutePath().getParent());

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/health", exchange -> writeJson(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/api/groups", new GroupsHandler());
        server.createContext("/api/groups/", new GroupByIdHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Group admin server listening on http://" + host + ":" + port);
        System.out.println("Data file: " + storeFile.toAbsolutePath());
    }

    private synchronized void load() {
        groups.clear();
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                GroupRecord record = GroupRecord.fromLine(line);
                groups.put(record.id, record);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load groups from " + storeFile, e);
        }
    }

    private synchronized void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (GroupRecord record : groups.values()) {
                lines.add(record.toLine());
            }
            Files.write(storeFile, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save groups to " + storeFile, e);
        }
    }

    private synchronized List<GroupRecord> listGroups() {
        return new ArrayList<>(groups.values());
    }

    private synchronized GroupRecord getGroup(String id) {
        return groups.get(id);
    }

    private synchronized GroupRecord createGroup(Map<String, String> form) {
        GroupRecord record = new GroupRecord();
        record.id = UUID.randomUUID().toString();
        record.name = requireValue(form.get("name"), "name");
        record.description = form.getOrDefault("description", "");
        record.ownerId = defaultValue(form.get("ownerId"), "admin");
        record.members = normalizeUsers(form.get("members"), record.ownerId);
        record.admins = normalizeUsers(form.get("admins"), record.ownerId);
        if (!record.admins.contains(record.ownerId)) {
            record.admins.add(record.ownerId);
        }
        record.createdAt = Instant.now().toString();
        groups.put(record.id, record);
        save();
        return record;
    }

    private synchronized GroupRecord updateGroup(String id, Map<String, String> form) {
        GroupRecord record = groups.get(id);
        if (record == null) {
            return null;
        }
        record.name = requireValue(form.get("name"), "name");
        record.description = form.getOrDefault("description", "");
        record.ownerId = defaultValue(form.get("ownerId"), record.ownerId);
        record.members = normalizeUsers(form.get("members"), record.ownerId);
        record.admins = normalizeUsers(form.get("admins"), record.ownerId);
        if (!record.members.contains(record.ownerId)) {
            record.members.add(0, record.ownerId);
        }
        if (!record.admins.contains(record.ownerId)) {
            record.admins.add(0, record.ownerId);
        }
        save();
        return record;
    }

    private synchronized boolean deleteGroup(String id) {
        GroupRecord removed = groups.remove(id);
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    private static List<String> normalizeUsers(String raw, String ownerId) {
        LinkedHashSet<String> users = new LinkedHashSet<>();
        if (ownerId != null && !ownerId.trim().isEmpty()) {
            users.add(ownerId.trim());
        }
        if (raw != null && !raw.trim().isEmpty()) {
            for (String item : raw.split(",")) {
                String value = item.trim();
                if (!value.isEmpty()) {
                    users.add(value);
                }
            }
        }
        return new ArrayList<>(users);
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String defaultValue(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String raw = "";
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            URI uri = exchange.getRequestURI();
            raw = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        } else {
            try (InputStream input = exchange.getRequestBody()) {
                raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        if (raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static void writeText(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        writeText(exchange, statusCode, "application/json", body);
    }

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            writeText(exchange, 200, "text/html", buildAdminPage());
        }
    }

    private class GroupsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    writeJson(exchange, 200, buildGroupsJson(listGroups()));
                    return;
                }
                if ("POST".equalsIgnoreCase(method)) {
                    GroupRecord created = createGroup(parseForm(exchange));
                    writeJson(exchange, 201, created.toJson());
                    return;
                }
                writeText(exchange, 405, "text/plain", "Method Not Allowed");
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }
    }

    private class GroupByIdHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String id = path.substring("/api/groups/".length()).trim();
            if (id.isEmpty()) {
                writeJson(exchange, 400, "{\"error\":\"missing group id\"}");
                return;
            }

            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    GroupRecord record = getGroup(id);
                    if (record == null) {
                        writeJson(exchange, 404, "{\"error\":\"group not found\"}");
                        return;
                    }
                    writeJson(exchange, 200, record.toJson());
                    return;
                }
                if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                    GroupRecord updated = updateGroup(id, parseForm(exchange));
                    if (updated == null) {
                        writeJson(exchange, 404, "{\"error\":\"group not found\"}");
                        return;
                    }
                    writeJson(exchange, 200, updated.toJson());
                    return;
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    if (!deleteGroup(id)) {
                        writeJson(exchange, 404, "{\"error\":\"group not found\"}");
                        return;
                    }
                    writeJson(exchange, 200, "{\"deleted\":true}");
                    return;
                }
                writeText(exchange, 405, "text/plain", "Method Not Allowed");
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }
    }

    private String buildGroupsJson(List<GroupRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"groups\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(records.get(i).toJson());
        }
        builder.append("]}");
        return builder.toString();
    }

    private String buildAdminPage() {
        return "<!doctype html><html><head><meta charset='utf-8'><title>Group Admin</title>"
                + "<style>"
                + "body{font-family:Segoe UI,Arial,sans-serif;background:#f3f6fb;margin:0;padding:24px;color:#102038;}"
                + ".wrap{max-width:1100px;margin:0 auto;display:grid;grid-template-columns:360px 1fr;gap:20px;}"
                + ".card{background:#fff;border-radius:16px;padding:18px;box-shadow:0 12px 30px rgba(16,32,56,.08);}"
                + "input,textarea{width:100%;box-sizing:border-box;margin:8px 0 14px;padding:10px 12px;border:1px solid #c8d4e3;border-radius:10px;}"
                + "button{border:0;border-radius:10px;padding:10px 14px;background:#1463ff;color:#fff;cursor:pointer;margin-right:8px;}"
                + "button.secondary{background:#e8eef8;color:#102038;}"
                + "table{width:100%;border-collapse:collapse;}td,th{padding:10px 8px;border-bottom:1px solid #edf1f7;text-align:left;vertical-align:top;}"
                + ".muted{color:#5e718c;font-size:13px;}.pill{display:inline-block;padding:2px 8px;border-radius:999px;background:#edf4ff;color:#1463ff;font-size:12px;}"
                + "</style></head><body>"
                + "<h1>群聊后台管理</h1><p class='muted'>当前版本是最小可用后台，用于课程项目演示。支持群组增删改查和成员管理。</p>"
                + "<div class='wrap'>"
                + "<div class='card'><h3 id='formTitle'>新建群组</h3>"
                + "<input id='groupId' type='hidden'>"
                + "<label>群名称</label><input id='name'>"
                + "<label>群描述</label><textarea id='description' rows='3'></textarea>"
                + "<label>群主</label><input id='ownerId' value='admin'>"
                + "<label>成员，逗号分隔</label><textarea id='members' rows='3'></textarea>"
                + "<label>管理员，逗号分隔</label><textarea id='admins' rows='2'></textarea>"
                + "<button onclick='saveGroup()'>保存</button>"
                + "<button class='secondary' onclick='resetForm()'>重置</button>"
                + "</div>"
                + "<div class='card'><h3>群组列表</h3><table><thead><tr><th>名称</th><th>成员</th><th>操作</th></tr></thead><tbody id='rows'></tbody></table></div>"
                + "</div>"
                + "<script>"
                + "let loadedGroups=[];"
                + "async function loadGroups(){const res=await fetch('/api/groups');const data=await res.json();loadedGroups=data.groups||[];const rows=document.getElementById('rows');rows.innerHTML='';"
                + "loadedGroups.forEach(g=>{const tr=document.createElement('tr');"
                + "const nameCell=document.createElement('td');"
                + "nameCell.innerHTML='<strong>'+escapeHtml(g.name)+'</strong><div class=\"muted\">'+escapeHtml(g.description||'')+'</div><div class=\"muted\">ID: '+escapeHtml(g.id)+'</div>';"
                + "const memberCell=document.createElement('td');"
                + "memberCell.innerHTML='<span class=\"pill\">'+((g.members||[]).length)+' 人</span><div class=\"muted\">'+escapeHtml((g.members||[]).join(', '))+'</div>';"
                + "const actionCell=document.createElement('td');"
                + "actionCell.innerHTML='<button type=\"button\">编辑</button><button type=\"button\" class=\"secondary\">删除</button>';"
                + "actionCell.children[0].onclick=function(){editGroup(g.id);};"
                + "actionCell.children[1].onclick=function(){removeGroup(g.id);};"
                + "tr.appendChild(nameCell);tr.appendChild(memberCell);tr.appendChild(actionCell);rows.appendChild(tr);});}"
                + "function escapeHtml(v){return String(v||'').replace(/[&<>\"']/g,s=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[s]));}"
                + "function formData(){const data=new URLSearchParams();['name','description','ownerId','members','admins'].forEach(id=>data.append(id,document.getElementById(id).value));return data;}"
                + "async function saveGroup(){const id=document.getElementById('groupId').value;const url=id?'/api/groups/'+encodeURIComponent(id):'/api/groups';const res=await fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:formData()});if(!res.ok){alert((await res.json()).error||'保存失败');return;}resetForm();await loadGroups();}"
                + "function editGroup(id){const group=loadedGroups.find(item=>item.id===id);if(!group)return;document.getElementById('formTitle').textContent='编辑群组';document.getElementById('groupId').value=group.id;document.getElementById('name').value=group.name||'';document.getElementById('description').value=group.description||'';document.getElementById('ownerId').value=group.ownerId||'';document.getElementById('members').value=(group.members||[]).join(', ');document.getElementById('admins').value=(group.admins||[]).join(', ');window.scrollTo({top:0,behavior:'smooth'});}"
                + "async function removeGroup(id){if(!confirm('确认删除该群组？'))return;const res=await fetch('/api/groups/'+encodeURIComponent(id),{method:'DELETE'});if(!res.ok){alert('删除失败');return;}await loadGroups();resetForm();}"
                + "function resetForm(){document.getElementById('formTitle').textContent='新建群组';document.getElementById('groupId').value='';document.getElementById('name').value='';document.getElementById('description').value='';document.getElementById('ownerId').value='admin';document.getElementById('members').value='';document.getElementById('admins').value='';}"
                + "loadGroups();"
                + "</script></body></html>";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class GroupRecord {
        String id;
        String name;
        String description;
        String ownerId;
        List<String> members = new ArrayList<>();
        List<String> admins = new ArrayList<>();
        String createdAt;

        String toLine() {
            return encode(Arrays.asList(id, name, description, ownerId, String.join(",", members), String.join(",", admins), createdAt));
        }

        static GroupRecord fromLine(String line) {
            List<String> parts = decode(line);
            GroupRecord record = new GroupRecord();
            record.id = get(parts, 0);
            record.name = get(parts, 1);
            record.description = get(parts, 2);
            record.ownerId = get(parts, 3);
            record.members = splitUsers(get(parts, 4));
            record.admins = splitUsers(get(parts, 5));
            record.createdAt = get(parts, 6);
            return record;
        }

        String toJson() {
            return "{"
                    + "\"id\":\"" + escape(id) + "\","
                    + "\"name\":\"" + escape(name) + "\","
                    + "\"description\":\"" + escape(description) + "\","
                    + "\"ownerId\":\"" + escape(ownerId) + "\","
                    + "\"members\":" + toJsonArray(members) + ","
                    + "\"admins\":" + toJsonArray(admins) + ","
                    + "\"createdAt\":\"" + escape(createdAt) + "\""
                    + "}";
        }

        private static String toJsonArray(List<String> values) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append("\"").append(escape(values.get(i))).append("\"");
            }
            builder.append("]");
            return builder.toString();
        }

        private static List<String> splitUsers(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return new ArrayList<>();
            }
            List<String> result = new ArrayList<>();
            for (String value : raw.split(",")) {
                String item = value.trim();
                if (!item.isEmpty()) {
                    result.add(item);
                }
            }
            return result;
        }

        private static String encode(List<String> fields) {
            List<String> safe = new ArrayList<>();
            for (String field : fields) {
                String value = field == null ? "" : field;
                safe.add(value.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n"));
            }
            return String.join("|", safe);
        }

        private static List<String> decode(String line) {
            if (line == null || line.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean escaped = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (escaped) {
                    if (ch == 'p') {
                        current.append('|');
                    } else if (ch == 'n') {
                        current.append('\n');
                    } else {
                        current.append(ch);
                    }
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '|') {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
            parts.add(current.toString());
            return parts;
        }

        private static String get(List<String> values, int index) {
            return index >= 0 && index < values.size() ? values.get(index) : "";
        }
    }
}
