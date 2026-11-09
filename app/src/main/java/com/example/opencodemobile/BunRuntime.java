package com.example.opencodemobile;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class BunRuntime {
    private static final String HOST_ASSET = "opencode-host.js";
    private static final long OUTPUT_LIMIT = 8L * 1024 * 1024;
    private static final long STARTUP_TIMEOUT_MILLIS = 60_000;
    private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final Object RUNTIME_LOCK = new Object();
    private static final ScheduledExecutorService IDLE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "opencode-runtime-idle");
                thread.setDaemon(true);
                return thread;
            });
    private static RuntimeProcess runtimeProcess;
    private static ScheduledFuture<?> idleShutdown;

    private BunRuntime() {}

    static String version(Context context) throws Exception {
        Process process = processBuilder(context, context.getFilesDir())
                .command(executable(context).getAbsolutePath(), "--version")
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Android Bun 运行时启动超时");
        }
        String output = read(process.getInputStream(), 1024);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Android Bun 启动失败：" + output);
        }
        return output.trim();
    }

    static JSONObject request(Context context, ModelConfig config, File workspace,
                              JSONObject request, long timeoutMillis) throws Exception {
        return request(context, config, workspace, request, timeoutMillis, null);
    }

    static JSONObject request(Context context, ModelConfig config, File workspace,
                              JSONObject request, long timeoutMillis,
                              OpenCodeClient.Progress progress) throws Exception {
        synchronized (RUNTIME_LOCK) {
            cancelIdleShutdown();
            long operationStartedAt = System.nanoTime();
            RuntimeProcess runtime = ensureRuntime(
                    context, config, workspace, timeoutMillis, progress, operationStartedAt);
            try {
                runtime.input.write(request.toString());
                runtime.input.newLine();
                runtime.input.flush();
                long requestStartedAt = System.nanoTime();
                String expectedId = String.valueOf(request.opt("id"));
                while (true) {
                    long waitMillis = 250;
                    if (timeoutMillis > 0) {
                        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - operationStartedAt);
                        long remainingMillis = timeoutMillis - elapsedMillis;
                        if (remainingMillis <= 0) {
                            String detail = errorDetail(runtime);
                            resetRuntime(runtime);
                            throw new IllegalStateException("OpenCode 请求超时："
                                    + detail);
                        }
                        waitMillis = Math.min(waitMillis, remainingMillis);
                    }

                    OutputLine output;
                    try {
                        output = runtime.output.poll(waitMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        resetRuntime(runtime);
                        throw e;
                    }
                    if (output == null) {
                        if (!runtime.process.isAlive()) {
                            String detail = errorDetail(runtime);
                            resetRuntime(runtime);
                            throw new IllegalStateException("OpenCode Core 已退出："
                                    + detail);
                        }
                        continue;
                    }
                    if (output.error != null) {
                        resetRuntime(runtime);
                        throw new IllegalStateException("无法读取 OpenCode Core 输出", output.error);
                    }
                    if (output.eof) {
                        String detail = errorDetail(runtime);
                        resetRuntime(runtime);
                        throw new IllegalStateException("OpenCode Core 未返回请求结果："
                                + detail);
                    }
                    if (output.value.getBytes(StandardCharsets.UTF_8).length > OUTPUT_LIMIT) {
                        resetRuntime(runtime);
                        throw new IllegalStateException("OpenCode 响应超过 8 MB，已拒绝读取");
                    }

                    JSONObject item;
                    try {
                        item = new JSONObject(output.value);
                    } catch (Exception e) {
                        resetRuntime(runtime);
                        throw new IllegalStateException("OpenCode Core 输出了无效的 JSONL 响应", e);
                    }

                    if ("progress".equals(item.optString("type"))) {
                        String message = item.optString("message", "").trim();
                        if (progress != null && !message.isEmpty()) progress.onProgress(message);
                        continue;
                    }
                    if ("fatal".equals(item.optString("type"))) {
                        String message = item.optString("error", "OpenCode Core 启动失败");
                        resetRuntime(runtime);
                        throw new IllegalStateException(message);
                    }
                    if (!item.has("id")
                            || !expectedId.equals(String.valueOf(item.opt("id")))) {
                        resetRuntime(runtime);
                        throw new IllegalStateException("OpenCode Core 返回了不匹配的请求结果");
                    }
                    if (item.has("error")) {
                        throw new IllegalStateException(item.optString("error", "OpenCode 请求失败"));
                    }
                    if (!item.has("result") || !(item.opt("result") instanceof JSONObject)) {
                        throw new IllegalStateException("OpenCode Core 未返回有效请求结果："
                                + errorDetail(runtime));
                    }
                    if (progress != null) {
                        progress.onProgress("本地 Core 请求完成（"
                                + elapsedSeconds(requestStartedAt) + " 秒）");
                    }
                    return item.getJSONObject("result");
                }
            } catch (Exception e) {
                if (runtimeProcess == runtime && (e instanceof IOException
                        || !runtime.process.isAlive())) {
                    resetRuntime(runtime);
                }
                throw e;
            } finally {
                if (runtimeProcess == runtime && runtime.process.isAlive()) {
                    scheduleIdleShutdown(runtime);
                }
            }
        }
    }

    private static RuntimeProcess ensureRuntime(Context context, ModelConfig config, File workspace,
                                                long timeoutMillis, OpenCodeClient.Progress progress,
                                                long operationStartedAt) throws Exception {
        File host = hostBundle(context);
        JSONObject runtimeConfig = new JSONObject();
        runtimeConfig.put("apiKey", config.apiKey);
        runtimeConfig.put("baseURL", config.baseUrl);
        runtimeConfig.put("databasePath", new File(
                new File(context.getFilesDir(), "opencode/database"), "opencode.db").getAbsolutePath());
        runtimeConfig.put("model", config.model);
        runtimeConfig.put("contextLimit", config.contextLimit);
        runtimeConfig.put("outputLimit", config.outputLimit);
        runtimeConfig.put("projectRoot", workspace.getCanonicalPath());
        String identity = identity(runtimeConfig);

        if (runtimeProcess != null && (!runtimeProcess.process.isAlive()
                || !runtimeProcess.identity.equals(identity))) {
            resetRuntime(runtimeProcess);
        }
        if (runtimeProcess != null) {
            if (progress != null) progress.onProgress("复用已启动的本地 OpenCode Core…");
            return runtimeProcess;
        }

        if (progress != null) progress.onProgress("正在启动本地 OpenCode Core…");
        File error = File.createTempFile("opencode-error-", ".log", context.getCacheDir());
        try {
            ProcessBuilder builder = processBuilder(context, workspace)
                    .command(executable(context).getAbsolutePath(), host.getAbsolutePath())
                    .redirectError(error);
            builder.environment().put("OPENCODE_MOBILE_CONFIG_JSON", runtimeConfig.toString());
            long runtimeStartedAt = System.nanoTime();
            Process process = builder.start();
            RuntimeProcess started = new RuntimeProcess(
                    process, identity, error, config.apiKey, runtimeStartedAt);
            runtimeProcess = started;
            long startupWaitMillis = STARTUP_TIMEOUT_MILLIS;
            if (timeoutMillis > 0) {
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - operationStartedAt);
                startupWaitMillis = Math.min(STARTUP_TIMEOUT_MILLIS,
                        Math.max(0, timeoutMillis - elapsedMillis));
            }
            OutputLine ready = startupWaitMillis == 0 ? null
                    : started.output.poll(startupWaitMillis, TimeUnit.MILLISECONDS);
            if (ready == null) {
                String detail = errorDetail(started);
                resetRuntime(started);
                throw new IllegalStateException("OpenCode Core 启动超时：" + detail);
            }
            if (ready.error != null) {
                resetRuntime(started);
                throw new IllegalStateException("无法读取 OpenCode Core 启动信息", ready.error);
            }
            if (ready.eof) {
                String detail = errorDetail(started);
                resetRuntime(started);
                throw new IllegalStateException("OpenCode Core 启动失败：" + detail);
            }
            JSONObject message = new JSONObject(ready.value);
            if ("fatal".equals(message.optString("type"))) {
                String detail = message.optString("error", "未知错误");
                resetRuntime(started);
                throw new IllegalStateException("OpenCode Core 启动失败：" + detail);
            }
            if (!"ready".equals(message.optString("type"))) {
                resetRuntime(started);
                throw new IllegalStateException("OpenCode Core 未返回就绪状态");
            }
            if (progress != null) {
                progress.onProgress("本地 OpenCode Core 已就绪（冷启动 "
                        + elapsedSeconds(started.startedAtNanos) + " 秒）");
            }
            return started;
        } catch (Exception e) {
            if (runtimeProcess != null && runtimeProcess.error == error) {
                resetRuntime(runtimeProcess);
            } else if (error.exists() && !error.delete()) {
                error.deleteOnExit();
            }
            throw e;
        }
    }

    private static void scheduleIdleShutdown(RuntimeProcess runtime) {
        cancelIdleShutdown();
        idleShutdown = IDLE_SCHEDULER.schedule(() -> {
            synchronized (RUNTIME_LOCK) {
                if (runtimeProcess == runtime) resetRuntime(runtime);
            }
        }, IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void cancelIdleShutdown() {
        if (idleShutdown != null) {
            idleShutdown.cancel(false);
            idleShutdown = null;
        }
    }

    private static void resetRuntime(RuntimeProcess runtime) {
        if (runtimeProcess == runtime) runtimeProcess = null;
        cancelIdleShutdown();
        try {
            runtime.input.close();
        } catch (Exception ignored) {
        }
        if (runtime.process.isAlive()) {
            try {
                if (!runtime.process.waitFor(1, TimeUnit.SECONDS)) runtime.process.destroy();
                if (!runtime.process.waitFor(2, TimeUnit.SECONDS)) runtime.process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                runtime.process.destroyForcibly();
            }
        }
        if (runtime.error.exists() && !runtime.error.delete()) runtime.error.deleteOnExit();
    }

    private static String errorDetail(RuntimeProcess runtime) {
        try {
            String detail = read(new FileInputStream(runtime.error), 2048);
            if (!runtime.apiKey.isEmpty()) detail = detail.replace(runtime.apiKey, "[redacted]");
            return detail;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String elapsedSeconds(long startedAtNanos) {
        return String.format(java.util.Locale.ROOT, "%.1f",
                (System.nanoTime() - startedAtNanos) / 1_000_000_000.0);
    }

    private static String identity(JSONObject runtimeConfig) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(runtimeConfig.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(
                java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static ProcessBuilder processBuilder(Context context, File workingDirectory) throws Exception {
        File home = new File(context.getFilesDir(), "opencode/home");
        File temporary = new File(context.getCacheDir(), "opencode/tmp");
        File config = new File(home, ".config");
        File data = new File(home, ".local/share");
        File cache = new File(home, ".cache");
        File database = new File(context.getFilesDir(), "opencode/database");
        ensureDirectory(home);
        ensureDirectory(temporary);
        ensureDirectory(config);
        ensureDirectory(data);
        ensureDirectory(cache);
        ensureDirectory(database);
        ProcessBuilder builder = new ProcessBuilder();
        builder.directory(workingDirectory);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", temporary.getAbsolutePath());
        builder.environment().put("XDG_CONFIG_HOME", config.getAbsolutePath());
        builder.environment().put("XDG_DATA_HOME", data.getAbsolutePath());
        builder.environment().put("XDG_CACHE_HOME", cache.getAbsolutePath());
        return builder;
    }

    private static File hostBundle(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "opencode/runtime");
        ensureDirectory(directory);
        File bundle = new File(directory, HOST_ASSET);
        File marker = new File(directory, "host-version");
        PackageInfo packageInfo = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
        String version = Long.toString(packageInfo.lastUpdateTime);
        if (bundle.isFile() && marker.isFile()
                && version.equals(read(new FileInputStream(marker), 128).trim())) {
            return bundle;
        }

        File temporary = File.createTempFile("opencode-host-", ".js", directory);
        try (InputStream input = context.getAssets().open(HOST_ASSET);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), bundle.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try (FileOutputStream output = new FileOutputStream(marker, false)) {
                output.write(version.getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("无法清理 OpenCode Core 临时文件");
            }
        }
        return bundle;
    }

    private static File executable(Context context) {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libopencode-bun.so");
        if (!executable.isFile() || !executable.canExecute()) {
            throw new IllegalStateException("APK 中未找到可执行的 Android Bun 运行时");
        }
        return executable;
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建运行时目录：" + directory.getAbsolutePath());
        }
    }

    private static String read(InputStream input, int limit) throws Exception {
        try (InputStream stream = input;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     stream, StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append('\n');
                int available = limit - output.length();
                if (available <= 0) break;
                output.append(line, 0, Math.min(line.length(), available));
            }
            return output.toString();
        }
    }

    private static final class RuntimeProcess {
        final Process process;
        final String identity;
        final File error;
        final String apiKey;
        final BufferedWriter input;
        final BlockingQueue<OutputLine> output = new LinkedBlockingQueue<>();
        final long startedAtNanos;

        RuntimeProcess(Process process, String identity, File error, String apiKey,
                       long startedAtNanos) throws Exception {
            this.process = process;
            this.identity = identity;
            this.error = error;
            this.apiKey = apiKey;
            this.startedAtNanos = startedAtNanos;
            this.input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> readOutput(process), "opencode-runtime-output");
            reader.setDaemon(true);
            reader.start();
        }

        private void readOutput(Process process) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.offer(OutputLine.value(line));
                output.offer(OutputLine.eof());
            } catch (Exception e) {
                output.offer(OutputLine.error(e));
            }
        }
    }

    private static final class OutputLine {
        final String value;
        final Exception error;
        final boolean eof;

        private OutputLine(String value, Exception error, boolean eof) {
            this.value = value;
            this.error = error;
            this.eof = eof;
        }

        static OutputLine value(String value) {
            return new OutputLine(value, null, false);
        }

        static OutputLine error(Exception error) {
            return new OutputLine(null, error, false);
        }

        static OutputLine eof() {
            return new OutputLine(null, null, true);
        }
    }
}
