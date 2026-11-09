package com.example.opencodemobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ConfigStore {
    private static final String PREFS = "model_config";
    private static final String KEY_ALIAS = "opencode_api_key";
    private static final String ZEN_API_KEY = "opencode_zen_api_key";
    private final SharedPreferences preferences;

    ConfigStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    ModelConfig load() {
        List<ModelConfig> profiles = profiles();
        String activeID = preferences.getString("active_profile", "");
        for (ModelConfig profile : profiles) {
            if (profile.id.equals(activeID)) return profile;
        }
        if (!profiles.isEmpty()) {
            select(profiles.get(0).id);
            return profiles.get(0);
        }
        return empty();
    }

    List<ModelConfig> profiles() {
        List<ModelConfig> result = new ArrayList<>();
        String encoded = preferences.getString("model_profiles", "");
        if (!encoded.isEmpty()) {
            try {
                JSONArray array = new JSONArray(encoded);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    String name = item.optString("name", "");
                    String url = item.optString("base_url", "");
                    String model = item.optString("model", "");
                    String key = item.optString("api_key", "");
                    if (!id.isEmpty() && !url.isEmpty() && !model.isEmpty()) {
                        result.add(new ModelConfig(id, name, url, decrypt(key), model,
                                item.optInt("context_limit", ModelConfig.DEFAULT_CONTEXT_LIMIT),
                                item.optInt("output_limit", ModelConfig.DEFAULT_OUTPUT_LIMIT)));
                    }
                }
            } catch (JSONException e) {
                throw new IllegalStateException("已保存的模型列表无法读取", e);
            }
            return result;
        }

        String baseUrl = preferences.getString("base_url", "");
        String model = preferences.getString("model", "");
        String key = preferences.getString("api_key", "");
        if (!baseUrl.isEmpty() || !model.isEmpty() || !key.isEmpty()) {
            String id = UUID.randomUUID().toString();
            String name = model.isEmpty() ? "OpenAI Compatible" : model;
            try {
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("name", name);
                item.put("base_url", baseUrl);
                item.put("api_key", key.isEmpty() ? "" : encrypt(decrypt(key)));
                item.put("model", model);
                preferences.edit().putString("model_profiles", new JSONArray().put(item).toString())
                        .putString("active_profile", id).apply();
            } catch (Exception e) {
                throw new IllegalStateException("无法迁移现有模型配置", e);
            }
            return profiles();
        }
        return result;
    }

    void save(String id, String name, String baseUrl, String apiKey, String model) throws Exception {
        int contextLimit = ModelConfig.DEFAULT_CONTEXT_LIMIT;
        int outputLimit = ModelConfig.DEFAULT_OUTPUT_LIMIT;
        for (ModelConfig profile : profiles()) {
            if (id != null && id.equals(profile.id)) {
                contextLimit = profile.contextLimit;
                outputLimit = profile.outputLimit;
                break;
            }
        }
        save(id, name, baseUrl, apiKey, model, contextLimit, outputLimit);
    }

    private void save(String id, String name, String baseUrl, String apiKey, String model,
                      int contextLimit, int outputLimit) throws Exception {
        String normalizedUrl = baseUrl.trim().replaceAll("/+$", "");
        if (!normalizedUrl.startsWith("https://")) {
            throw new IllegalArgumentException("API 地址必须使用 HTTPS，以保护 API Key");
        }
        String normalizedModel = model.trim();
        if (normalizedModel.isEmpty()) throw new IllegalArgumentException("模型 ID 不能为空");
        String profileID = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        boolean activate = id == null || id.isEmpty()
                || profileID.equals(preferences.getString("active_profile", ""));
        if ((apiKey == null || apiKey.trim().isEmpty()) && encryptedKey(profileID).isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        String displayName = name.trim().isEmpty() ? normalizedModel : name.trim();
        JSONArray profiles = new JSONArray();
        boolean replaced = false;
        for (ModelConfig profile : profiles()) {
            JSONObject item = new JSONObject();
            item.put("id", profile.id);
            item.put("name", profile.id.equals(profileID) ? displayName : profile.name);
            item.put("base_url", profile.id.equals(profileID) ? normalizedUrl : profile.baseUrl);
            item.put("api_key", profile.id.equals(profileID)
                    ? apiKey == null || apiKey.trim().isEmpty() ? encryptedKey(profileID) : encrypt(apiKey.trim())
                    : encryptedKey(profile.id));
            item.put("model", profile.id.equals(profileID) ? normalizedModel : profile.model);
            item.put("context_limit", profile.id.equals(profileID) ? contextLimit : profile.contextLimit);
            item.put("output_limit", profile.id.equals(profileID) ? outputLimit : profile.outputLimit);
            profiles.put(item);
            replaced |= profile.id.equals(profileID);
        }
        if (!replaced) {
            JSONObject item = new JSONObject();
            item.put("id", profileID);
            item.put("name", displayName);
            item.put("base_url", normalizedUrl);
            item.put("api_key", encrypt(apiKey.trim()));
            item.put("model", normalizedModel);
            item.put("context_limit", contextLimit);
            item.put("output_limit", outputLimit);
            profiles.put(item);
        }
        preferences.edit().putString("model_profiles", profiles.toString())
                .putString("active_profile", activate ? profileID
                        : preferences.getString("active_profile", "")).apply();
    }

    void select(String id) {
        boolean exists = false;
        for (ModelConfig profile : profiles()) exists |= profile.id.equals(id);
        if (!exists) throw new IllegalArgumentException("该模型配置已不存在");
        preferences.edit().putString("active_profile", id).apply();
    }

    String zenApiKey() {
        String saved = decrypt(preferences.getString(ZEN_API_KEY, ""));
        if (!saved.isEmpty()) return saved;
        for (ModelConfig profile : profiles()) {
            if (OpenCodeZenCatalog.BASE_URL.equals(profile.baseUrl)) return profile.apiKey;
        }
        return "";
    }

    void saveZenApiKey(String apiKey) throws Exception {
        String value = apiKey.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("OpenCode Zen API Key 不能为空");
        preferences.edit().putString(ZEN_API_KEY, encrypt(value)).apply();
    }

    void saveZenModel(OpenCodeZenCatalog.Model model, String apiKey) throws Exception {
        String profileID = "opencode-zen-" + model.id;
        String profileName = "OpenCode Zen · " + model.name;
        save(profileID, profileName, OpenCodeZenCatalog.BASE_URL, apiKey, model.id,
                model.contextLimit, model.outputLimit);
        select(profileID);
    }

    void updateModelLimits(String id, int contextLimit, int outputLimit) {
        JSONArray profiles;
        try {
            profiles = new JSONArray(preferences.getString("model_profiles", "[]"));
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject item = profiles.optJSONObject(i);
                if (item == null || !id.equals(item.optString("id"))) continue;
                item.put("context_limit", contextLimit);
                item.put("output_limit", outputLimit);
                preferences.edit().putString("model_profiles", profiles.toString()).apply();
                return;
            }
        } catch (JSONException e) {
            throw new IllegalStateException("无法更新模型上下文限制", e);
        }
    }

    void delete(String id) {
        JSONArray remaining = new JSONArray();
        String nextActive = "";
        for (ModelConfig profile : profiles()) {
            if (profile.id.equals(id)) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("id", profile.id);
                item.put("name", profile.name);
                item.put("base_url", profile.baseUrl);
                item.put("api_key", encryptedKey(profile.id));
                item.put("model", profile.model);
                item.put("context_limit", profile.contextLimit);
                item.put("output_limit", profile.outputLimit);
            } catch (JSONException e) {
                throw new IllegalStateException("无法更新模型配置", e);
            }
            remaining.put(item);
            if (nextActive.isEmpty()) nextActive = profile.id;
        }
        String active = preferences.getString("active_profile", "");
        if (id.equals(active)) active = nextActive;
        preferences.edit().putString("model_profiles", remaining.toString())
                .putString("active_profile", active).apply();
    }

    private String encryptedKey(String id) {
        try {
            JSONArray profiles = new JSONArray(preferences.getString("model_profiles", "[]"));
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject item = profiles.optJSONObject(i);
                if (item != null && id.equals(item.optString("id"))) return item.optString("api_key", "");
            }
        } catch (JSONException e) {
            throw new IllegalStateException("已保存的模型列表无法读取", e);
        }
        return "";
    }

    private static ModelConfig empty() {
        return new ModelConfig("", "", "https://api.openai.com/v1", "", "",
                ModelConfig.DEFAULT_CONTEXT_LIMIT, ModelConfig.DEFAULT_OUTPUT_LIMIT);
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) {
        if (encoded.isEmpty()) return "";
        try {
            byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
            int ivLength = 12;
            if (combined.length <= ivLength) throw new IllegalStateException("API 密钥数据无效");
            byte[] iv = new byte[ivLength];
            byte[] encrypted = new byte[combined.length - ivLength];
            System.arraycopy(combined, 0, iv, 0, ivLength);
            System.arraycopy(combined, ivLength, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            preferences.edit().remove("api_key").apply();
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
