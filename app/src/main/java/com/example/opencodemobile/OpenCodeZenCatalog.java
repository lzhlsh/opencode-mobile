package com.example.opencodemobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class OpenCodeZenCatalog {
    static final String BASE_URL = "https://opencode.ai/zen/v1";
    private static final String MODELS_URL = BASE_URL + "/models";
    private static final Set<String> FREE_MODELS = new HashSet<>(Arrays.asList(
            "big-pickle",
            "beam-free",
            "mimo-v2.6-flash-free",
            "nemotron-3.5-super-vl-free",
            "nemotron-3.5-lightning-free",
            "jev-1.13-free",
            "longcat-2.5-preview-free",
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "space-bunny-free"));

    private OpenCodeZenCatalog() {}

    static List<Model> fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(MODELS_URL).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IllegalStateException("OpenCode Zen 没有返回模型列表");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 2_000_000) {
                        throw new IllegalStateException("OpenCode Zen 模型列表响应过大");
                    }
                }
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("OpenCode Zen 模型列表请求失败（HTTP " + status + "）");
            }
            JSONObject response = new JSONObject(body.toString());
            JSONArray data = response.optJSONArray("data");
            if (data == null) throw new IllegalStateException("OpenCode Zen 返回了无效的模型列表");
            List<Model> models = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (id.isEmpty() || !id.matches("[a-zA-Z0-9._:-]+") || !seen.add(id)) continue;
                boolean free = FREE_MODELS.contains(id) || id.toLowerCase(Locale.ROOT).endsWith("-free");
                JSONObject limits = item.optJSONObject("limit");
                int contextLimit = positiveLimit(limits, "context", ModelConfig.DEFAULT_CONTEXT_LIMIT);
                int outputLimit = positiveLimit(limits, "output", ModelConfig.DEFAULT_OUTPUT_LIMIT);
                models.add(new Model(id, displayName(id, free), free, contextLimit, outputLimit));
            }
            models.sort(Comparator.comparing((Model model) -> !model.free)
                    .thenComparing(model -> model.name.toLowerCase(Locale.ROOT)));
            return models;
        } finally {
            connection.disconnect();
        }
    }

    private static String displayName(String id, boolean free) {
        switch (id) {
            case "big-pickle":
                return "Big Pickle Free";
            case "beam-free":
                return "Beam Free";
            case "mimo-v2.6-flash-free":
                return "MiMo V2.6 Flash Free";
            case "deepseek-v4.1-flash-free":
                return "DeepSeek V4.1 Flash Free";
            case "glm-5.3-flash-free":
                return "GLM 5.3 Flash Free";
            case "longcat-2.5-preview-free":
                return "LongCat 2.5 Preview Free";
            case "nemotron-3.5-super-vl-free":
                return "Nemotron 3.5 Super VL Free";
            case "nemotron-3.5-lightning-free":
                return "Nemotron 3.5 Lightning Free";
            case "jev-1.13-free":
                return "JEV 1.13 Free";
            case "space-bunny-free":
                return "Space Bunny Free";
            default:
                break;
        }
        String[] words = id.split("-");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            if (word.matches("\\d+")) name.append(word);
            else name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        if (free && !name.toString().toLowerCase(Locale.ROOT).contains("free")) name.append(" Free");
        return name.toString();
    }

    private static int positiveLimit(JSONObject limits, String key, int fallback) {
        if (limits == null) return fallback;
        long value = limits.optLong(key, fallback);
        if (value < 1_024 || value > 2_000_000) return fallback;
        return (int) value;
    }

    static final class Model {
        final String id;
        final String name;
        final boolean free;
        final int contextLimit;
        final int outputLimit;

        Model(String id, String name, boolean free, int contextLimit, int outputLimit) {
            this.id = id;
            this.name = name;
            this.free = free;
            this.contextLimit = contextLimit;
            this.outputLimit = outputLimit;
        }
    }
}
