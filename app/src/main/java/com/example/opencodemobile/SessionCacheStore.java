package com.example.opencodemobile;

import android.content.Context;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionCacheStore {
    private static final String TAG = "SessionCacheStore";
    private static final String KEY_ALIAS = "opencode_session_cache";
    private static final int MAX_CACHE_BYTES = 1024 * 1024;
    private final Context context;

    SessionCacheStore(Context context) {
        this.context = context.getApplicationContext();
    }

    List<OpenCodeClient.SessionInfo> load(Uri tree) {
        File file;
        try {
            file = cacheFile(tree);
            if (!file.isFile()) return null;
            if (file.length() > MAX_CACHE_BYTES) {
                discard(file, new IllegalStateException("会话缓存超过大小限制"));
                return null;
            }
            byte[] encrypted;
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                encrypted = output.toByteArray();
            }
            byte[] combined = Base64.decode(new String(encrypted, StandardCharsets.UTF_8), Base64.NO_WRAP);
            int ivLength = 12;
            if (combined.length <= ivLength) throw new IllegalStateException("会话缓存格式无效");
            byte[] iv = new byte[ivLength];
            byte[] data = new byte[combined.length - ivLength];
            System.arraycopy(combined, 0, iv, 0, ivLength);
            System.arraycopy(combined, ivLength, data, 0, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            JSONArray array = new JSONArray(new String(cipher.doFinal(data), StandardCharsets.UTF_8));
            List<OpenCodeClient.SessionInfo> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) continue;
                result.add(new OpenCodeClient.SessionInfo(id,
                        item.optString("title", "新会话"),
                        item.optLong("updated", 0)));
            }
            return result;
        } catch (Exception e) {
            try {
                discard(cacheFile(tree), e);
            } catch (Exception ignored) {
                Log.w(TAG, "Could not remove unreadable session cache", ignored);
            }
            return null;
        }
    }

    void save(Uri tree, List<OpenCodeClient.SessionInfo> sessions) {
        File temporary = null;
        try {
            JSONArray array = new JSONArray();
            for (OpenCodeClient.SessionInfo session : sessions) {
                JSONObject item = new JSONObject();
                item.put("id", session.id);
                item.put("title", session.title);
                item.put("updated", session.updated);
                array.put(item);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(array.toString().getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            byte[] encoded = Base64.encode(combined, Base64.NO_WRAP);
            if (encoded.length > MAX_CACHE_BYTES) {
                throw new IllegalStateException("会话缓存超过大小限制");
            }

            File target = cacheFile(tree);
            temporary = File.createTempFile(".sessions-", ".tmp", target.getParentFile());
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(encoded);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.w(TAG, "Could not save encrypted session cache", e);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Could not remove temporary session cache");
            }
        }
    }

    void clear(Uri tree) {
        try {
            File file = cacheFile(tree);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not clear session cache");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve session cache file", e);
        }
    }

    private File cacheFile(Uri tree) throws Exception {
        File directory = new File(context.getFilesDir(), "opencode/session-cache");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建会话缓存目录");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(tree.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(digest.length * 2);
        for (byte value : digest) name.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return new File(directory, name + ".cache");
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

    private void discard(File file, Exception reason) {
        Log.w(TAG, "Discarding unreadable encrypted session cache", reason);
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete unreadable session cache");
    }
}
