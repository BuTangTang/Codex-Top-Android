package com.butang.codextop;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
    /** 保持原UTF-8与SHA-256缓存键，以固定字符表生成64位小写十六进制，避免逐字节格式化分配。 */
    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[bytes.length * 2];
            String hex = "0123456789abcdef";
            for (int i = 0; i < bytes.length; i++) {
                int valueByte = bytes[i] & 255;
                result[i * 2] = hex.charAt(valueByte >>> 4);
                result[i * 2 + 1] = hex.charAt(valueByte & 15);
            }
            return new String(result);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    /** 规范缓存逐行恢复；只有整份值及文件尾均通过才交付，兼容布局最多重开一次沿旧读法。 */
    public TranscriptWindow read(String remote) throws IOException {
        File file = new File(directory, digest(remote) + ".json");
        if (!file.exists()) return new TranscriptWindow();
        // InputStreamReader沿原new String的非法UTF-8替换语义，避免改成严格解码后拒绝旧缓存。
        try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)))) {
            try {
                TranscriptWindow window = TranscriptWindow.readSnapshot(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("缓存含尾随内容");
                return window;
            } catch (TranscriptWindow.SnapshotCompatibilityException | RuntimeException compatibility) {
                // 仅布局和纯模型值交原整树裁定；原JsonElement adapter直接抛出流IO，不包装为Runtime。
                // open/read/close的IOException和Error不在兼容范围，关闭失败也不能进入第二次打开。
            }
        }
        // 流已成功关闭、局部窗口未交付且可回收；只走一次原读法，失败沿原IOException返回。
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            return TranscriptWindow.restore(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException error) { throw new IOException("历史缓存无法读取", error); }
    }
    /** 完整写临时文件后原子替换，再淘汰旧正文；超大单会话不覆盖旧缓存。仅由原串行历史队列调用。 */
    public void write(String remote, TranscriptWindow window) throws IOException {
        Files.createDirectories(directory.toPath());
        File target = new File(directory, digest(remote) + ".json");
        File temporary = new File(directory, digest(remote) + ".tmp");
        try {
            // 限制实际UTF-8字节而非字符数；关闭和排空成功后才能沿原原子替换及淘汰路径发布。
            try (JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                    new LimitedOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary.toPath()), 4096), maxBytes),
                    StandardCharsets.UTF_8), 4096))) {
                window.writeSnapshot(writer);
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        trimOldTranscripts(target);
    }

    /** 单次写入的字节计数只属于临时文件；超过原预算立即失败，不让部分正文成为正式缓存。 */
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        /** 复用原输出关闭语义，不另建文件owner或写入线程。 */
        LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }

        /** 单字节与块写入共用同一剩余额度，避免FilterOutputStream逐字节重复计数。 */
        @Override public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            written++;
        }

        /** 先核整块再交给小缓冲，失败后关闭也不会把超额块写入临时文件。 */
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        /** 以差值比较防止累加溢出；原单会话错误保持不变。 */
        private void requireCapacity(int length) throws IOException {
            if (length > limit - written) throw new IOException("单个会话超过正文缓存容量");
        }
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
        // 先完成全部目录检查和容量统计；未超预算时不读取mtime或排序，也不掩盖枚举失败。
        if (total <= maxBytes) return;
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
