package com.example.opencodemobile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProjectFiles {
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
    private static final long MAX_MANIFEST_SIZE = 4L * 1024 * 1024;
    private static final int MAX_SYNC_ENTRIES = 10_000;
    private static final int MAX_DIRECTORY_DEPTH = 64;
    private static final Set<String> EXCLUDED_DIRECTORIES = new HashSet<>(java.util.Arrays.asList(
            ".git", ".gradle", ".idea", "node_modules"));
    interface SyncProgress {
        void onProgress(String message);
    }
    private static final String[] CHILD_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private final Context context;
    private final ContentResolver resolver;

    ProjectFiles(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
    }

    void saveTree(Uri treeUri) {
        context.getSharedPreferences("project", Context.MODE_PRIVATE)
                .edit().putString("tree_uri", treeUri.toString()).apply();
    }

    Uri loadTree() {
        String value = context.getSharedPreferences("project", Context.MODE_PRIVATE)
                .getString("tree_uri", "");
        if (value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission()
                    && permission.isWritePermission()) return uri;
        }
        return null;
    }

    String treeName(Uri treeUri) throws Exception {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
        try (Cursor cursor = resolver.query(documentUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return "项目目录";
    }

    JSONArray list(String relativePath) throws Exception {
        Uri parent = resolve(relativePath, true);
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                requireTree(), parentId);
        JSONArray result = new JSONArray();
        try (Cursor cursor = resolver.query(children, CHILD_COLUMNS, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE")) {
            if (cursor == null) throw new IllegalStateException("无法读取项目目录");
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                JSONObject item = new JSONObject();
                item.put("name", name);
                item.put("directory", DocumentsContract.Document.MIME_TYPE_DIR.equals(mime));
                result.put(item);
            }
        }
        return result;
    }

    String read(String relativePath) throws Exception {
        Uri document = resolve(relativePath, false);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (InputStream input = resolver.openInputStream(document)) {
            if (input == null) throw new IllegalStateException("无法打开文件");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (result.size() + read > MAX_PREVIEW_BYTES) {
                    throw new IllegalArgumentException("文件超过 8 MiB，暂不支持预览");
                }
                result.write(buffer, 0, read);
            }
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    void write(String relativePath, String contents) throws Exception {
        String safePath = validatePath(relativePath);
        if (safePath.isEmpty()) throw new IllegalArgumentException("文件路径不能为空");
        int separator = safePath.lastIndexOf('/');
        String parentPath = separator < 0 ? "" : safePath.substring(0, separator);
        String name = separator < 0 ? safePath : safePath.substring(separator + 1);
        Uri parent = resolve(parentPath, true);
        Uri existing = findChild(parent, name);
        Uri target = existing;
        if (target == null) {
            String mime = mimeType(name);
            target = DocumentsContract.createDocument(resolver, parent, mime, name);
        }
        if (target == null) throw new IllegalStateException("无法创建文件：" + name);
        try (OutputStream output = resolver.openOutputStream(target, "wt")) {
            if (output == null) throw new IllegalStateException("无法写入文件：" + name);
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    JSONArray search(String query, int limit) throws Exception {
        String needle = query.toLowerCase(Locale.ROOT);
        JSONArray matches = new JSONArray();
        searchDirectory("", needle, Math.min(Math.max(limit, 1), 50), matches, 0);
        return matches;
    }

    File workspaceDirectory() throws Exception {
        Uri tree = requireWritableTree();
        File base = new File(context.getFilesDir(), "opencode/workspaces");
        File workspace = new File(base, sha256(tree.toString())).getCanonicalFile();
        if (!workspace.getPath().startsWith(base.getCanonicalPath() + File.separator)) {
            throw new IllegalStateException("项目工作目录无效");
        }
        if (!workspace.isDirectory() && !workspace.mkdirs()) {
            throw new IllegalStateException("无法创建项目工作目录");
        }
        return workspace;
    }

    void syncToWorkspace(File workspace) throws Exception {
        syncToWorkspace(workspace, message -> {});
    }

    void syncToWorkspace(File workspace, SyncProgress progress) throws Exception {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        Uri tree = requireWritableTree();
        File root = requireWorkspace(workspace);
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Map<String, FileStamp> previous = loadManifest(tree);
        SyncStats stats = new SyncStats(progress, previous);
        mirrorDirectory(rootDocument, root, root, stats, 0);
        saveManifest(tree, stats.cached);
        progress.onProgress("项目同步完成：检查 " + stats.entries + " 项，扫描目录 "
                + stats.directories + " 个，缓存命中 " + stats.cacheHits + " 项，耗时 "
                + elapsedSeconds(startedAt) + " 秒");
    }

    void syncFromWorkspace(File workspace) throws Exception {
        syncFromWorkspace(workspace, message -> {});
    }

    void syncFromWorkspace(File workspace, SyncProgress progress) throws Exception {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        Uri tree = requireWritableTree();
        File root = requireWorkspace(workspace);
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        SyncStats stats = new SyncStats(progress, loadManifest(tree));
        syncDirectoryToTree(tree, rootDocument, root, stats, 0);
        progress.onProgress("项目更改已同步：检查 " + stats.entries + " 项，扫描目录 "
                + stats.directories + " 个，缓存命中 " + stats.cacheHits + " 项，耗时 "
                + elapsedSeconds(startedAt) + " 秒");
    }

    private static String elapsedSeconds(long startedAt) {
        return String.format(Locale.ROOT, "%.1f",
                (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0);
    }

    private void searchDirectory(String directory, String needle, int limit,
                                 JSONArray matches, int depth) throws Exception {
        if (depth > 8 || matches.length() >= limit) return;
        JSONArray items = list(directory);
        for (int i = 0; i < items.length() && matches.length() < limit; i++) {
            JSONObject item = items.getJSONObject(i);
            String name = item.getString("name");
            String path = directory.isEmpty() ? name : directory + "/" + name;
            if (item.getBoolean("directory")) {
                searchDirectory(path, needle, limit, matches, depth + 1);
            } else {
                String matchText = name.toLowerCase(Locale.ROOT);
                if (matchText.contains(needle)) {
                    matches.put(path);
                } else if (isTextFile(name)) {
                    try {
                        if (read(path).toLowerCase(Locale.ROOT).contains(needle)) matches.put(path);
                    } catch (IllegalArgumentException ignored) {
                        // Large files are skipped during search.
                    }
                }
            }
        }
    }

    private Uri resolve(String relativePath, boolean directory) throws Exception {
        Uri tree = requireTree();
        String safePath = validatePath(relativePath);
        String currentId = DocumentsContract.getTreeDocumentId(tree);
        Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, currentId);
        if (safePath.isEmpty()) return current;
        for (String part : safePath.split("/")) {
            Uri child = findChild(current, part);
            if (child == null) throw new IllegalArgumentException("找不到项目路径：" + safePath);
            current = child;
        }
        if (directory && !isDirectory(current)) {
            throw new IllegalArgumentException("不是目录：" + safePath);
        }
        return current;
    }

    private Uri findChild(Uri parent, String name) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                requireTree(), DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(children, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("无法访问项目目录");
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(nameColumn))) {
                    return DocumentsContract.buildDocumentUriUsingTree(requireTree(), cursor.getString(idColumn));
                }
            }
        }
        return null;
    }

    private boolean isDirectory(Uri document) throws Exception {
        try (Cursor cursor = resolver.query(document,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            return cursor != null && cursor.moveToFirst()
                    && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0));
        }
    }

    private Uri requireTree() {
        Uri tree = loadTree();
        if (tree == null) throw new IllegalStateException("请先选择并授权项目目录");
        return tree;
    }

    private Uri requireWritableTree() {
        Uri tree = requireTree();
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isReadPermission()
                    && permission.isWritePermission()) return tree;
        }
        throw new IllegalStateException("项目目录需要同时授予读取和写入权限，请重新选择目录");
    }

    private File requireWorkspace(File workspace) throws Exception {
        File expectedParent = new File(context.getFilesDir(), "opencode/workspaces").getCanonicalFile();
        File root = workspace.getCanonicalFile();
        if (!root.getPath().startsWith(expectedParent.getPath() + File.separator)) {
            throw new IllegalArgumentException("项目工作目录必须位于应用私有目录中");
        }
        if (!root.isDirectory()) throw new IllegalArgumentException("项目工作目录不存在");
        return root;
    }

    private void mirrorDirectory(Uri parentDocument, File localDirectory, File workspace,
                                 SyncStats stats, int depth) throws Exception {
        stats.enter(depth);
        Map<String, RemoteDocument> remote = children(parentDocument, stats);
        Set<String> seen = new HashSet<>();
        for (RemoteDocument entry : remote.values()) {
            validateName(entry.name);
            File local = childFile(localDirectory, entry.name, workspace);
            if (isExcluded(entry.name)) {
                deleteLocal(local);
                continue;
            }
            seen.add(entry.name);
            if (entry.directory) {
                if (local.exists() && !local.isDirectory()) deleteLocal(local);
                if (!local.isDirectory() && !local.mkdirs()) {
                    throw new IllegalStateException("无法创建项目目录：" + entry.name);
                }
                mirrorDirectory(entry.uri, local, workspace, stats, depth + 1);
            } else {
                if (local.isDirectory()) deleteLocal(local);
                if (stats.isUnchanged(entry, local)) {
                    stats.cache(entry, local);
                    stats.cacheHit();
                    continue;
                }
                if (!sameContents(entry.uri, local)) {
                    copyRemoteToLocal(entry.uri, local);
                }
                stats.cache(entry, local);
            }
        }

        File[] localChildren = localDirectory.listFiles();
        if (localChildren == null) throw new IllegalStateException("无法列出项目工作目录");
        for (File local : localChildren) {
            if (isExcluded(local.getName()) || !seen.contains(local.getName())) deleteLocal(local);
        }
    }

    private void syncDirectoryToTree(Uri tree, Uri parentDocument, File localDirectory,
                                     SyncStats stats, int depth) throws Exception {
        stats.enter(depth);
        Map<String, RemoteDocument> remote = children(parentDocument, stats);
        File[] localChildren = localDirectory.listFiles();
        if (localChildren == null) throw new IllegalStateException("无法列出项目工作目录");
        Set<String> seen = new HashSet<>();
        for (File local : localChildren) {
            String name = local.getName();
            validateName(name);
            if (isExcluded(name)) continue;
            if (!remote.containsKey(name)) stats.entry();
            if (Files.isSymbolicLink(local.toPath())) {
                throw new IllegalArgumentException("不允许同步符号链接：" + name);
            }
            seen.add(name);
            RemoteDocument existing = remote.get(name);
            if (local.isDirectory()) {
                if (existing != null && !existing.directory) {
                    invalidateManifest(tree);
                    deleteRemote(existing.uri);
                    existing = null;
                }
                if (existing == null) {
                    invalidateManifest(tree);
                    Uri created = DocumentsContract.createDocument(
                            resolver, parentDocument, DocumentsContract.Document.MIME_TYPE_DIR, name);
                    if (created == null) throw new IllegalStateException("无法创建项目目录：" + name);
                    existing = new RemoteDocument(name, created, true);
                }
                syncDirectoryToTree(tree, existing.uri, local, stats, depth + 1);
            } else if (local.isFile()) {
                if (existing != null && existing.directory) {
                    invalidateManifest(tree);
                    deleteRemote(existing.uri);
                    existing = null;
                }
                if (existing == null) {
                    invalidateManifest(tree);
                    Uri created = DocumentsContract.createDocument(
                            resolver, parentDocument, mimeType(name), name);
                    if (created == null) throw new IllegalStateException("无法创建文件：" + name);
                    existing = new RemoteDocument(name, created, false);
                }
                if (stats.isUnchanged(existing, local)) {
                    stats.cacheHit();
                    continue;
                }
                if (!sameContents(existing.uri, local)) {
                    invalidateManifest(tree);
                    copyLocalToRemote(local, existing.uri);
                }
            } else {
                throw new IllegalArgumentException("项目中存在不支持的文件类型：" + name);
            }
        }

        for (RemoteDocument entry : remote.values()) {
            if (!isExcluded(entry.name) && !seen.contains(entry.name)) {
                invalidateManifest(tree);
                deleteRemote(entry.uri);
            }
        }
    }

    private Map<String, RemoteDocument> children(Uri parent, SyncStats stats) throws Exception {
        stats.readDirectory();
        Uri tree = requireWritableTree();
        Uri childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(parent));
        Map<String, RemoteDocument> result = new HashMap<>();
        try (Cursor cursor = resolver.query(childUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("无法读取项目目录");
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                stats.entry();
                String name = cursor.getString(nameColumn);
                String id = cursor.getString(idColumn);
                String mime = cursor.getString(mimeColumn);
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                long size = sizeColumn < 0 || cursor.isNull(sizeColumn) ? -1 : cursor.getLong(sizeColumn);
                long modified = modifiedColumn < 0 || cursor.isNull(modifiedColumn)
                        ? -1 : cursor.getLong(modifiedColumn);
                RemoteDocument entry = new RemoteDocument(name, uri,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), size, modified);
                if (result.put(name, entry) != null) {
                    throw new IllegalStateException("项目目录中存在重名项目：" + name);
                }
            }
        }
        return result;
    }

    private boolean sameContents(Uri remote, File local) throws Exception {
        if (!local.isFile()) return false;
        try (InputStream input = resolver.openInputStream(remote);
             InputStream existing = new FileInputStream(local)) {
            if (input == null) throw new IllegalStateException("无法读取项目文件");
            byte[] left = new byte[8192];
            byte[] right = new byte[8192];
            while (true) {
                int leftRead = input.read(left);
                int rightRead = existing.read(right);
                if (leftRead != rightRead) return false;
                if (leftRead < 0) return true;
                for (int i = 0; i < leftRead; i++) {
                    if (left[i] != right[i]) return false;
                }
            }
        }
    }

    private void copyRemoteToLocal(Uri remote, File local) throws Exception {
        File temporary = File.createTempFile(".opencode-", ".tmp", local.getParentFile());
        try {
            try (InputStream input = resolver.openInputStream(remote);
                 OutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IllegalStateException("无法读取项目文件：" + local.getName());
                copy(input, output);
            }
            if (local.exists() && !local.delete()) {
                throw new IllegalStateException("无法更新项目文件：" + local.getName());
            }
            if (!temporary.renameTo(local)) {
                throw new IllegalStateException("无法保存项目文件：" + local.getName());
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("无法清理临时项目文件");
            }
        }
    }

    private void copyLocalToRemote(File local, Uri remote) throws Exception {
        try (InputStream input = new FileInputStream(local);
             OutputStream output = resolver.openOutputStream(remote, "wt")) {
            if (output == null) throw new IllegalStateException("无法写入项目文件：" + local.getName());
            copy(input, output);
        }
    }

    private void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private void deleteRemote(Uri document) throws Exception {
        if (!DocumentsContract.deleteDocument(resolver, document)) {
            throw new IllegalStateException("无法删除项目中的文件");
        }
    }

    private static File childFile(File parent, String name, File workspace) throws Exception {
        File child = new File(parent, name).getCanonicalFile();
        if (!child.getPath().startsWith(workspace.getCanonicalPath() + File.separator)) {
            throw new IllegalArgumentException("项目文件路径越界：" + name);
        }
        return child;
    }

    private static void deleteLocal(File file) throws Exception {
        if (!file.exists()) return;
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IllegalStateException("无法清理项目工作目录");
            for (File child : children) deleteLocal(child);
        }
        if (!file.delete()) throw new IllegalStateException("无法清理项目文件：" + file.getName());
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains("\0")) {
            throw new IllegalArgumentException("项目包含无效文件名");
        }
    }

    private static boolean isExcluded(String name) {
        return EXCLUDED_DIRECTORIES.contains(name) || name.equals(".DS_Store");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private Map<String, FileStamp> loadManifest(Uri tree) throws Exception {
        File file = manifestFile(tree);
        if (!file.isFile()) return Collections.emptyMap();
        if (file.length() > MAX_MANIFEST_SIZE) {
            throw new IllegalStateException("项目同步缓存过大，无法安全读取");
        }
        byte[] data;
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            data = output.toByteArray();
        }
        JSONObject manifest = new JSONObject(new String(data, StandardCharsets.UTF_8));
        Map<String, FileStamp> result = new HashMap<>();
        Iterator<String> keys = manifest.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = manifest.optJSONObject(key);
            if (item == null || !item.has("size") || !item.has("modified")
                    || !item.has("localSize") || !item.has("localModified")) {
                throw new IllegalStateException("项目同步缓存格式无效");
            }
            result.put(key, new FileStamp(item.getLong("size"), item.getLong("modified"),
                    item.getLong("localSize"), item.getLong("localModified")));
        }
        return result;
    }

    private void saveManifest(Uri tree, Map<String, FileStamp> manifest) throws Exception {
        File file = manifestFile(tree);
        JSONObject value = new JSONObject();
        for (Map.Entry<String, FileStamp> entry : manifest.entrySet()) {
            FileStamp stamp = entry.getValue();
            JSONObject item = new JSONObject();
            item.put("size", stamp.remoteSize);
            item.put("modified", stamp.remoteModified);
            item.put("localSize", stamp.localSize);
            item.put("localModified", stamp.localModified);
            value.put(entry.getKey(), item);
        }
        byte[] data = value.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_MANIFEST_SIZE) {
            throw new IllegalStateException("项目同步缓存超过 4 MB，无法安全保存");
        }
        File temporary = File.createTempFile("sync-manifest-", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("无法清理临时项目同步缓存");
            }
        }
    }

    private void invalidateManifest(Uri tree) throws Exception {
        File file = manifestFile(tree);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("无法使项目同步缓存失效");
        }
    }

    private File manifestFile(Uri tree) throws Exception {
        File directory = new File(context.getFilesDir(), "opencode/sync-manifests");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建项目同步缓存目录");
        }
        return new File(directory, sha256(tree.toString()) + ".json");
    }

    private static final class FileStamp {
        final long remoteSize;
        final long remoteModified;
        final long localSize;
        final long localModified;

        FileStamp(long remoteSize, long remoteModified, long localSize, long localModified) {
            this.remoteSize = remoteSize;
            this.remoteModified = remoteModified;
            this.localSize = localSize;
            this.localModified = localModified;
        }
    }

    private static final class RemoteDocument {
        final String name;
        final Uri uri;
        final boolean directory;
        final long size;
        final long modified;

        RemoteDocument(String name, Uri uri, boolean directory) {
            this(name, uri, directory, -1, -1);
        }

        RemoteDocument(String name, Uri uri, boolean directory, long size, long modified) {
            this.name = name;
            this.uri = uri;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }
    }

    private static final class SyncStats {
        private final SyncProgress progress;
        private final Map<String, FileStamp> previous;
        final Map<String, FileStamp> cached = new HashMap<>();
        private int entries;
        int cacheHits;
        int directories;
        private int nextProgress = 25;
        private long lastDirectoryProgress;

        SyncStats(SyncProgress progress) {
            this(progress, Collections.emptyMap());
        }

        SyncStats(SyncProgress progress, Map<String, FileStamp> previous) {
            this.progress = progress;
            this.previous = previous;
        }

        void enter(int depth) {
            if (depth > MAX_DIRECTORY_DEPTH) {
                throw new IllegalArgumentException("项目目录层级超过 64 层，无法同步");
            }
            directories++;
        }

        void entry() {
            entries++;
            if (entries > MAX_SYNC_ENTRIES) {
                throw new IllegalArgumentException("项目文件数量超过 10000 个，无法同步");
            }
            if (entries >= nextProgress) {
                progress.onProgress("项目同步中：已检查 " + entries + " 项，缓存命中 "
                        + cacheHits + " 项");
                nextProgress += 25;
            }
        }

        boolean isUnchanged(RemoteDocument document, File local) {
            if (document.size < 0 || document.modified <= 0 || !local.isFile()) return false;
            FileStamp stamp = previous.get(document.uri.toString());
            return stamp != null && stamp.remoteSize == document.size
                    && stamp.remoteModified == document.modified
                    && stamp.localSize == local.length()
                    && stamp.localModified == local.lastModified();
        }

        void cache(RemoteDocument document, File local) {
            if (document.size < 0 || document.modified <= 0 || !local.isFile()) return;
            cached.put(document.uri.toString(), new FileStamp(
                    document.size, document.modified, local.length(), local.lastModified()));
        }

        void cacheHit() {
            cacheHits++;
        }

        void readDirectory() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastDirectoryProgress == 0 || now - lastDirectoryProgress >= 2_000) {
                progress.onProgress("项目同步中：正在读取目录，已扫描 " + entries + " 项");
                lastDirectoryProgress = now;
            }
        }

    }

    private static String validatePath(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        if (value.startsWith("/") || value.contains("\0")) {
            throw new IllegalArgumentException("项目路径无效");
        }
        for (String part : value.split("/")) {
            if (part.equals("..")) throw new IllegalArgumentException("不允许访问项目目录之外的路径");
        }
        return value.replaceAll("^\\./+", "").replaceAll("/+$", "");
    }

    private static boolean isTextFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".py")
                || lower.endsWith(".gradle") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".sh");
    }

    private static String mimeType(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".md")) return "text/markdown";
        if (isTextFile(name)) return "text/plain";
        return "application/octet-stream";
    }
}
