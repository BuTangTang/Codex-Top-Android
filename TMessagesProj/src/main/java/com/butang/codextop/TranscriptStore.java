package com.butang.codextop;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

/** 同一历史队列的薄持久化入口：索引按需读正文，完整旧缓存只迁移一次。 */
public final class TranscriptStore {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final Path root, directory;
    private final String server, account, machine, scopeKey;
    private final long maxBytes;

    public TranscriptStore(File root, String server, String account, String machine) {
        this(root, server, account, machine, MAX_BYTES);
    }
    TranscriptStore(File root, String server, String account, String machine, long maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("缓存容量无效");
        this.root = root.toPath().toAbsolutePath().normalize();
        this.server = server; this.account = account; this.machine = machine; this.maxBytes = maxBytes;
        scopeKey = digest(server + "\n" + account + "\n" + machine);
        directory = this.root.resolve(scopeKey);
    }

    /** 目录键沿用原SHA256，正式旧缓存路径保持可发现。 */
    static String digest(String value) { return hex(sha256().digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        final String alphabet = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) { int b = bytes[i] & 255; result[2*i] = alphabet.charAt(b >>> 4); result[2*i+1] = alphabet.charAt(b & 15); }
        return new String(result);
    }
    private Path family(String remote) { return directory.resolve(digest(remote)); }
    private static Path suffix(Path family, String suffix) { return family.resolveSibling(family.getFileName() + suffix); }
    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    private IndexedTranscriptStore indexed() throws IOException { return new IndexedTranscriptStore(root, server, account, machine); }

    /** 普通重开只恢复完整轻索引；旧v2的第一次读取不强制等待迁移写入。 */
    public TranscriptWindow read(String remote) throws IOException {
        Path family = family(remote);
        if (exists(suffix(family, ".window"))) {
            IndexedTranscriptStore.Snapshot snapshot = indexed().read(remote);
            finishMigration(family, snapshot.importedLegacySha());
            TranscriptPersistenceToken.attachIndexed(snapshot.window, family, snapshot.generation(), snapshot.importedLegacySha());
            return snapshot.window;
        }
        if (exists(suffix(family, ".window.previous")))
            throw new IOException("当前历史索引缺失，保留的前一代需要恢复");
        Path legacy = legacyPath(family);
        if (legacy != null) {
            Legacy result = readLegacy(legacy);
            TranscriptPersistenceToken.attachLegacy(result.window, family, result.sha);
            return result.window;
        }
        TranscriptWindow window = new TranscriptWindow();
        TranscriptPersistenceToken.attachMissing(window, family, () -> requireMissing(family));
        return window;
    }

    /** 显式旧格式路径选择；两个未完成迁移副本同时存在时先核一致，不能按时间猜。 */
    private Path legacyPath(Path family) throws IOException {
        Path live = suffix(family, ".json"), imported = suffix(family, ".json.imported");
        if (exists(live) && exists(imported) && !fileSha(live).equals(fileSha(imported)))
            throw new IOException("旧历史缓存有两个不同版本，不能自动覆盖");
        return exists(live) ? live : exists(imported) ? imported : null;
    }

    /** 失败后新建的空业务根没有写权限；盘上任何有效或待核缓存都必须先完整读取。 */
    private static void requireMissing(Path family) throws IOException {
        for (String ext : new String[]{".json", ".json.imported", ".window", ".window.previous"})
            if (exists(suffix(family, ext))) throw new IOException("已有历史缓存未读取，不能覆盖");
    }

    /** 测试和独立调用默认仅保护当前根；Runtime必须传入其全部已载入历史根。 */
    public void write(String remote, TranscriptWindow window) throws IOException {
        write(remote, window, Collections.singletonList(window));
    }

    /** 保持原落盘成功后才清待发的调用合同，不拥有新线程或第二份会话缓存。 */
    public void write(String remote, TranscriptWindow window, Collection<TranscriptWindow> protectedRoots) throws IOException {
        if (window == null || protectedRoots == null) throw new IOException("历史写入缺少根");
        Path family = family(remote);
        TranscriptPersistenceToken token = window.persistenceToken();
        if (token == null) {
            TranscriptPersistenceToken.attachMissing(window, family, () -> requireMissing(family));
            token = window.persistenceToken();
        }
        token.requireWritable(window, family);
        String expected = token.generation();
        if (expected == null) {
            if (exists(suffix(family, ".window.previous"))) throw new IOException("历史索引已有前一代，不能作为首次迁移");
            Path legacy = legacyPath(family);
            if (token.legacySha256() != null) {
                if (legacy == null || !token.legacySha256().equals(fileSha(legacy))) throw new IOException("旧历史缓存已改变");
            } else requireMissing(family);
        } else finishMigration(family, token.legacySha256());
        IndexedTranscriptStore store = indexed();
        IndexedTranscriptStore.Snapshot snapshot = store.resume(remote, window, expected, token.legacySha256());
        TranscriptCacheBudget.CacheKey saved = new TranscriptCacheBudget.CacheKey(scopeKey, digest(remote));
        Set<TranscriptCacheBudget.CacheKey> pins = new HashSet<>();
        for (TranscriptWindow live : protectedRoots) {
            if (live == null) continue;
            TranscriptPersistenceToken owner = live.persistenceToken();
            if (owner == null) continue;
            Path path = owner.familyPath();
            if (path.getParent() != null && root.equals(path.getParent().getParent()))
                pins.add(new TranscriptCacheBudget.CacheKey(path.getParent().getFileName().toString(), path.getFileName().toString()));
        }
        TranscriptCacheBudget budget = new TranscriptCacheBudget(root, maxBytes, new TranscriptCacheBudget.NioMetadataIo());
        store.write(remote, snapshot, bytes -> budget.ensureCapacity(saved, bytes, pins));
        // 索引已提交，此后即使旧副本rename失败也必须推进代次，避免重试把已提交误作失败。
        token.committed(window, family, expected, snapshot.generation());
        finishMigration(family, token.legacySha256());
    }

    /** 索引内记录原v2 SHA，真实崩溃留下双文件时才完整核对并收尾；通常只做exists。 */
    private static void finishMigration(Path family, String expectedSha) throws IOException {
        Path live = suffix(family, ".json"), imported = suffix(family, ".json.imported");
        if (!exists(live)) return;
        if (expectedSha == null || !expectedSha.equals(fileSha(live))) throw new IOException("旧版本写入了不同历史，不能覆盖");
        if (exists(imported)) {
            if (!expectedSha.equals(fileSha(imported))) throw new IOException("历史迁移备份不一致");
            Files.delete(live);
        } else Files.move(live, imported, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 明确降级前导出独立回退目录；不撤当前索引、不混入在线缓存，安装流程另验完整导出。 */
    void exportForRollback(String remote, TranscriptWindow window, Path destinationRoot) throws IOException {
        Path family = family(remote);
        TranscriptPersistenceToken token = TranscriptPersistenceToken.requireWritable(window, family);
        Path destination = destinationRoot.toAbsolutePath().normalize();
        if (destination.startsWith(root) || root.startsWith(destination)) throw new IOException("回退导出必须使用独立目录");
        IndexedTranscriptStore store = indexed();
        store.verify(remote, token.generation());
        Path legacy = legacyPath(family);
        if (token.generation() == null && token.legacySha256() != null
                && (legacy == null || !token.legacySha256().equals(fileSha(legacy))))
            throw new IOException("回退导出的旧缓存已改变");
        if (exists(suffix(family, ".json")) && token.generation() != null
                && !Objects.equals(token.legacySha256(), fileSha(suffix(family, ".json"))))
            throw new IOException("旧版本缓存与当前索引冲突");
        writeLegacy(destination.resolve(scopeKey).resolve(digest(remote) + ".json"), window);
        store.verify(remote, token.generation());
    }

    /** 原流式兼容读取；本次完整读取同步计算迁移SHA，不为标记再读一遍旧正文。 */
    private static Legacy readLegacy(Path file) throws IOException {
        requireRegular(file);
        MessageDigest digest = sha256();
        try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest), StandardCharsets.UTF_8)))) {
            try {
                TranscriptWindow window = TranscriptWindow.readSnapshot(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("缓存含尾随内容");
                return new Legacy(window, hex(digest.digest()));
            } catch (TranscriptWindow.SnapshotCompatibilityException | RuntimeException compatibility) { }
        }
        digest = sha256();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest), StandardCharsets.UTF_8))) {
            TranscriptWindow window = TranscriptWindow.restore(JsonParser.parseReader(reader).getAsJsonObject());
            return new Legacy(window, hex(digest.digest()));
        } catch (RuntimeException error) { throw new IOException("历史缓存无法读取", error); }
    }
    private static final class Legacy {
        final TranscriptWindow window; final String sha;
        Legacy(TranscriptWindow window, String sha) { this.window = window; this.sha = sha; }
    }
    private static void requireRegular(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
            throw new IOException("历史缓存文件无效");
    }
    private static String fileSha(Path file) throws IOException {
        requireRegular(file); MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }

    /** 回退导出也先完整写临时文件，失败不得覆盖旧缓存。 */
    private void writeLegacy(Path target, TranscriptWindow window) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".pending");
        boolean published = false;
        try {
            try (JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                    new LimitedOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary), 4096), maxBytes), StandardCharsets.UTF_8)))) {
                window.writeSnapshot(writer);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
        } finally { if (!published) Files.deleteIfExists(temporary); }
    }
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit; private long written;
        LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException { require(1); out.write(value); written++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { require(length); out.write(bytes, offset, length); written += length; }
        private void require(int length) throws IOException { if (length > limit - written) throw new IOException("单个会话超过正文缓存容量"); }
    }
}
