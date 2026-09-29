package com.butang.codextop;

import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** 应用私有目录中的历史缓存；按服务、账号、电脑和会话隔离。仅后台历史队列访问。 */
public final class TranscriptStore {
    // 本机正文总预算沿用此前64 MiB额度；草稿、账号和待发记录不在此目录。
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final File root;
    private final File directory;
    private final long maxBytes;
    /** 保持原账号/电脑目录格式，旧缓存无需迁移即可继续读取。 */
    public TranscriptStore(File root, String server, String account, String machine) {
        this(root, server, account, machine, MAX_BYTES);
    }
    /** 测试以小预算验证同一淘汰路径，正式入口始终使用固定正文总预算。 */
    TranscriptStore(File root, String server, String account, String machine, long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("缓存容量无效");
        this.root = root;
        this.maxBytes = maxBytes;
        directory = new File(root, digest(server + "\n" + account + "\n" + machine));
    }
    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public TranscriptWindow read(String remote) throws IOException {
        File file = new File(directory, digest(remote) + ".json");
        if (!file.exists()) return new TranscriptWindow();
        try {
            return TranscriptWindow.restore(JsonParser.parseString(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException error) { throw new IOException("历史缓存无法读取", error); }
    }
    /** 完整写临时文件后原子替换，再淘汰旧正文；超大单会话不覆盖旧缓存。仅由原串行历史队列调用。 */
    public void write(String remote, TranscriptWindow window) throws IOException {
        byte[] bytes = window.snapshot().toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IOException("单个会话超过正文缓存容量");
        Files.createDirectories(directory.toPath());
        File target = new File(directory, digest(remote) + ".json");
        File temporary = new File(directory, digest(remote) + ".tmp");
        try {
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        trimOldTranscripts(target);
    }

    /** 按最后保存时间整会话淘汰，只扫描本owner的哈希正文文件，不读取其他账号内容。 */
    private void trimOldTranscripts(File saved) throws IOException {
        java.util.ArrayList<File> files = new java.util.ArrayList<>();
        long total = 0;
        File[] scopes = root.listFiles();
        if (scopes == null) throw new IOException("正文缓存目录无法读取");
        for (File scope : scopes) {
            if (!scope.getName().matches("[0-9a-f]{64}")
                    || !Files.isDirectory(scope.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            File[] children = scope.listFiles();
            if (children == null) throw new IOException("正文缓存分区无法读取");
            for (File file : children) {
                if (!file.getName().matches("[0-9a-f]{64}\\.json")
                        || !Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                total += file.length();
                files.add(file);
            }
        }
        files.sort(java.util.Comparator.comparingLong(File::lastModified).thenComparing(File::getPath));
        for (File file : files) {
            if (total <= maxBytes) break;
            if (file.equals(saved)) continue;
            long bytes = file.length();
            Files.delete(file.toPath());
            total -= bytes;
        }
    }
}
