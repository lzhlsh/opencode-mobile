package com.example.opencodemobile;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class OpenCodeClient {
    private static final String PREFERENCES = "opencode_session";
    interface Progress {
        void onProgress(String message);
    }
    private final Context context;
    private final ProjectFiles files;

    OpenCodeClient(Context context, ProjectFiles files) {
        this.context = context.getApplicationContext();
        this.files = files;
    }

    String respond(ModelConfig config, String prompt) throws Exception {
        return respond(config, prompt, message -> {});
    }

    String respond(ModelConfig config, String prompt, Progress progress) throws Exception {
        Uri tree = files.loadTree();
        if (tree == null) throw new IllegalStateException("请先选择并授权项目目录");
        File workspace = files.workspaceDirectory();
        progress.onProgress("正在扫描并同步手机项目…");
        files.syncToWorkspace(workspace, progress::onProgress);

        String sessionID = currentSession(tree);
        if (sessionID == null) {
            progress.onProgress("正在创建 OpenCode 会话…");
            sessionID = createSession(config, prompt.trim().substring(0, Math.min(prompt.trim().length(), 100)));
        }

        JSONObject result;
        Exception promptFailure = null;
        try {
            JSONObject params = new JSONObject();
            params.put("directory", workspace.getCanonicalPath());
            params.put("sessionID", sessionID);
            params.put("text", prompt);
            progress.onProgress("项目已同步，正在启动 Core 并等待 "
                    + config.displayName() + " 返回（等待模型完成，不设客户端时间上限）…");
            result = request(config, workspace, "session.respond", params, 0, progress);
        } catch (Exception e) {
            promptFailure = e;
            result = null;
        }

        try {
            progress.onProgress(promptFailure == null
                    ? "模型已返回，正在把项目更改同步回手机…"
                    : "请求未完成，正在同步可能已产生的项目更改…");
            files.syncFromWorkspace(workspace, progress::onProgress);
        } catch (Exception syncError) {
            if (promptFailure != null) promptFailure.addSuppressed(syncError);
            else throw syncError;
        }
        if (promptFailure != null) throw promptFailure;

        JSONObject messages = result.getJSONObject("messages");
        return latestAssistantText(messages, config.apiKey);
    }

    List<ChatMessage> history(ModelConfig config) throws Exception {
        Uri tree = files.loadTree();
        if (tree == null) return new ArrayList<>();
        String sessionID = currentSession(tree);
        if (sessionID == null) return new ArrayList<>();
        File workspace = files.workspaceDirectory();
        JSONObject params = new JSONObject();
        params.put("directory", workspace.getCanonicalPath());
        params.put("sessionID", sessionID);
        params.put("limit", 100);
        JSONObject result = request(config, workspace, "session.messages", params, 30_000);
        JSONArray data = result.optJSONArray("data");
        List<ChatMessage> messages = new ArrayList<>();
        if (data == null) return messages;
        for (int i = data.length() - 1; i >= 0; i--) {
            JSONObject message = data.optJSONObject(i);
            if (message == null) continue;
            JSONObject info = messageInfo(message);
            String role = messageType(info);
            String speaker = "user".equals(role) ? "你" : "assistant".equals(role)
                    ? "OpenCode 2.0.32" : "";
            String text = messageText(message);
            if (text.isEmpty() && "assistant".equals(role)) {
                text = assistantErrorText(message, config.apiKey);
            }
            if (!speaker.isEmpty() && !text.isEmpty()) messages.add(new ChatMessage(speaker, text));
        }
        return messages;
    }

    List<SessionInfo> sessions(ModelConfig config) throws Exception {
        return sessions(config, null);
    }

    List<SessionInfo> sessions(ModelConfig config, Progress progress) throws Exception {
        if (files.loadTree() == null) return new ArrayList<>();
        File workspace = files.workspaceDirectory();
        long startedAt = android.os.SystemClock.elapsedRealtime();
        JSONObject request = new JSONObject();
        request.put("id", 1);
        request.put("method", "session.list");
        request.put("params", new JSONObject());
        JSONObject result = BunRuntime.request(context, config, workspace, request, 30_000, progress);
        JSONArray data = result.optJSONArray("data");
        List<SessionInfo> sessions = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                JSONObject time = item.optJSONObject("time");
                sessions.add(new SessionInfo(item.optString("id"),
                        item.optString("title", "新会话"),
                        time == null ? 0 : time.optLong("updated", 0)));
            }
        }
        if (progress != null) {
            long elapsedMillis = android.os.SystemClock.elapsedRealtime() - startedAt;
            progress.onProgress("会话查询完成：" + sessions.size() + " 个，请求耗时 "
                    + String.format(java.util.Locale.ROOT, "%.1f", elapsedMillis / 1000.0) + " 秒");
        }
        return sessions;
    }

    String createSession(ModelConfig config, String title) throws Exception {
        Uri tree = files.loadTree();
        if (tree == null) throw new IllegalStateException("请先选择并授权项目目录");
        File workspace = files.workspaceDirectory();
        JSONObject params = new JSONObject();
        params.put("directory", workspace.getCanonicalPath());
        params.put("title", title.isEmpty() ? "新会话" : title);
        JSONObject created = request(config, workspace, "session.create", params, 30_000);
        String sessionID = created.getString("id");
        setActiveSession(tree, sessionID);
        return sessionID;
    }

    void activateSession(String sessionID) {
        Uri tree = files.loadTree();
        if (tree == null) throw new IllegalStateException("请先选择并授权项目目录");
        setActiveSession(tree, sessionID);
    }

    String activeSession() {
        Uri tree = files.loadTree();
        return tree == null ? null : currentSession(tree);
    }

    void deleteSession(ModelConfig config, String sessionID) throws Exception {
        File workspace = files.workspaceDirectory();
        JSONObject params = new JSONObject();
        params.put("sessionID", sessionID);
        request(config, workspace, "session.delete", params, 30_000);
        Uri tree = files.loadTree();
        if (tree != null && sessionID.equals(currentSession(tree))) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .remove(sessionPreferenceKey(tree)).apply();
        }
    }

    private JSONObject request(ModelConfig config, File workspace,
                               String method, JSONObject params, long timeoutMillis) throws Exception {
        return request(config, workspace, method, params, timeoutMillis, null);
    }

    private JSONObject request(ModelConfig config, File workspace,
                               String method, JSONObject params, long timeoutMillis,
                               Progress progress) throws Exception {
        JSONObject request = new JSONObject();
        request.put("id", 1);
        request.put("method", method);
        request.put("params", params);
        return BunRuntime.request(context, config, workspace, request, timeoutMillis, progress);
    }

    private String currentSession(Uri tree) {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String sessionID = preferences.getString(sessionPreferenceKey(tree), null);
        if (sessionID != null) return sessionID;
        if (tree.toString().equals(preferences.getString("tree_uri", ""))) {
            sessionID = preferences.getString("session_id", null);
            if (sessionID != null) setActiveSession(tree, sessionID);
        }
        return sessionID;
    }

    private void setActiveSession(Uri tree, String sessionID) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(sessionPreferenceKey(tree), sessionID)
                .apply();
    }

    private static String sessionPreferenceKey(Uri tree) {
        return "session_" + android.util.Base64.encodeToString(
                tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    private static String latestAssistantText(JSONObject messages, String apiKey) {
        JSONArray data = messages.optJSONArray("data");
        if (data == null) return "（OpenCode 没有返回消息列表）";
        boolean assistantSeen = false;
        for (int i = 0; i < data.length(); i++) {
            JSONObject message = data.optJSONObject(i);
            if (message == null) continue;
            JSONObject info = messageInfo(message);
            String type = messageType(info);
            if ("user".equals(type)) {
                return assistantSeen
                        ? "（OpenCode 已处理请求，但没有生成文本回复）"
                        : "（OpenCode 会话中没有 assistant 回复）";
            }
            if (!"assistant".equals(type)) continue;
            assistantSeen = true;
            String error = assistantErrorText(message, apiKey);
            if (!error.isEmpty()) return error;
            String text = messageText(message);
            if (!text.isEmpty()) return text;
        }
        return assistantSeen
                ? "（OpenCode 已生成回复，但回复中没有文本内容）"
                : "（OpenCode 会话中没有 assistant 回复）";
    }

    private static String assistantErrorText(JSONObject message, String apiKey) {
        JSONObject error = messageInfo(message).optJSONObject("error");
        if (error == null) return "";
        String errorType = error.optString("type", "模型错误");
        int status = error.optInt("status", 0);
        String detail = error.optString("message", "").trim();
        String responseDetail = errorResponseDetail(error.optJSONObject("response"));
        if (!responseDetail.isEmpty() && !detail.contains(responseDetail)) {
            detail = detail.isEmpty() ? responseDetail : detail + "；服务端说明：" + responseDetail;
        }
        if (!apiKey.isEmpty()) detail = detail.replace(apiKey, "[密钥已隐藏]");
        detail = detail.replaceAll("(?i)(Bearer\\s+)[^\\s,;\\\"']+", "$1[已隐藏]");
        detail = detail.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"']+", "$1[已隐藏]");
        String connectionHint = connectionHint(detail);
        if (!connectionHint.isEmpty()) detail = connectionHint;
        if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
        return "模型请求失败：" + errorType
                + (status == 0 ? "" : "（HTTP " + status + "）")
                + (detail.isEmpty() ? "" : "：" + detail);
    }

    private static String connectionHint(String detail) {
        if (detail.matches("(?is).*\\b(ENOTFOUND|EAI_AGAIN|name or service not known|temporary failure in name resolution)\\b.*")) {
            return "设备当前无法解析模型服务域名（DNS）。请检查 Wi-Fi/移动网络的 DNS 或私人 DNS 设置，切换网络后重试。";
        }
        if (detail.matches("(?is).*\\b(ETIMEDOUT|timed out|timeout|aborted)\\b.*")) {
            return "连接模型服务超时。请检查网络连接，或稍后重试。";
        }
        if (detail.matches("(?is).*\\b(SSL|TLS|CERTIFICATE|CERT_.*)\\b.*")) {
            return "与模型服务建立安全连接失败。请检查设备日期时间和网络代理设置。";
        }
        if (detail.matches("(?is).*\\b(ECONNRESET|ECONNREFUSED|fetch failed|network request failed)\\b.*")) {
            return "无法连接模型服务（可能是网络或 DNS 解析异常）。请检查网络和代理设置，切换网络后重试。";
        }
        return "";
    }

    private static String errorResponseDetail(JSONObject response) {
        if (response == null) return "";
        String body = response.optString("body", "").trim();
        if (body.isEmpty()) return "";
        try {
            JSONObject parsed = new JSONObject(body);
            JSONObject error = parsed.optJSONObject("error");
            StringBuilder detail = new StringBuilder();
            appendErrorField(detail, parsed, "message");
            appendErrorField(detail, parsed, "code");
            if (error != null) {
                appendErrorField(detail, error, "message");
                appendErrorField(detail, error, "code");
                appendErrorField(detail, error, "type");
            }
            return detail.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void appendErrorField(StringBuilder detail, JSONObject source, String key) {
        String value = source.optString(key, "").trim();
        if (value.isEmpty() || value.equals("null")) return;
        if (detail.length() > 0 && !detail.toString().contains(value)) detail.append(": ");
        if (detail.indexOf(value) < 0) detail.append(value);
    }

    private static String messageText(JSONObject message) {
        JSONArray parts = message.optJSONArray("parts");
        if (parts == null) parts = message.optJSONArray("content");
        JSONObject info = messageInfo(message);
        if (parts == null) parts = info.optJSONArray("parts");
        if (parts == null) parts = info.optJSONArray("content");
        if (parts == null) return message.optString("text", info.optString("text", "")).trim();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && "text".equals(part.optString("type"))) {
                String value = part.optString("text", "");
                if (!value.isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(value);
                }
            }
        }
        return text.toString().trim();
    }

    private static JSONObject messageInfo(JSONObject message) {
        JSONObject info = message.optJSONObject("info");
        return info == null ? message : info;
    }

    private static String messageType(JSONObject info) {
        String role = info.optString("role");
        return role.isEmpty() ? info.optString("type") : role;
    }

    static final class ChatMessage {
        final String speaker;
        final String text;

        ChatMessage(String speaker, String text) {
            this.speaker = speaker;
            this.text = text;
        }
    }

    static final class SessionInfo {
        final String id;
        final String title;
        final long updated;

        SessionInfo(String id, String title, long updated) {
            this.id = id;
            this.title = title;
            this.updated = updated;
        }
    }
}
