package com.butang.codextop;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 私有候选：完整身份索引与不可变正文分离；尚未接正式Runtime、迁移或缓存淘汰。 */
public final class IndexedTranscriptStore {
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final byte[] MAGIC = "CTH4\n".getBytes(StandardCharsets.US_ASCII);
    private final Path scope;
    private final String scopeKey;
    private final PublishGate publishGate;
    private final BodyFactory bodyFactory;

    /** 仅对可信应用私有目录建账号/电脑隔离；正式接入前另核Android平台目录语义。 */
    public IndexedTranscriptStore(Path root, String server, String account, String machine) throws IOException {
        this(root, server, account, machine, () -> {});
    }

    /** 合成故障点仅在全部新块准备后、正式索引发布前运行，不增加后台线程。 */
    IndexedTranscriptStore(Path root, String server, String account, String machine, PublishGate gate) throws IOException {
        this(root, server, account, machine, gate, TranscriptBodyStore::new);
    }

    /** 合成验收通过原块IO边界计量读取，业务索引逻辑保持相同。 */
    IndexedTranscriptStore(Path root, String server, String account, String machine, PublishGate gate, BodyFactory factory) throws IOException {
        scopeKey = TranscriptStore.digest(server + "\n" + account + "\n" + machine);
        scope = root.toAbsolutePath().normalize().resolve(scopeKey);
        Files.createDirectories(scope);
        if (Files.isSymbolicLink(scope) || !Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("历史索引分区无效");
        publishGate = gate;
        bodyFactory = factory;
    }

    /** 测试用发布故障，不在失败时发布索引或修改内存的正文绑定。 */
    interface PublishGate { void beforePublish() throws IOException; }

    /** 仅供候选合成计量与故障注入，默认始终使用同一块owner实现。 */
    interface BodyFactory { TranscriptBodyStore open(Path directory) throws IOException; }

    /** 总预算由既有历史队列在任何新块或临时索引写入前核对。 */
    interface CapacityGate { void beforeWrite(long additionalPeakBytes) throws IOException; }

    /** 一个可变的业务根和它实际读取的持久代次；晚写不得覆盖后来提交。 */
    public static final class Snapshot {
        public TranscriptWindow window;
        private String generation;
        private String importedLegacySha;
        private final String scopeKey, remoteKey;

        /** 代次由成功读盘或成功提交产生，调用方只能沿原业务替换根。 */
        private Snapshot(TranscriptWindow window, String generation, String scopeKey, String remoteKey) {
            this.window = window; this.generation = generation; this.scopeKey = scopeKey; this.remoteKey = remoteKey;
        }

        /** 只读的持久代次可用于验证成功提交，不是消息或来源游标。 */
        public String generation() { return generation; }
        String importedLegacySha() { return importedLegacySha; }
    }

    /** 索引中的正文读取器只在查询实际需要正文时执行，完成后不把正文常驻到索引。 */
    private static final class PersistedContent implements TranscriptWindow.Content {
        final TranscriptBodyStore store;
        final TranscriptBodyStore.Ref ref;
        final Path directory;

        /** 绑定不可变块范围；不同会话scope的引用不能被直接复用。 */
        PersistedContent(TranscriptBodyStore store, TranscriptBodyStore.Ref ref, Path directory) {
            this.store = store; this.ref = ref; this.directory = directory;
        }

        /** 先由块owner核字节校验，再沿原投影解析单条，不读取相邻正文。 */
        @Override public TranscriptText read() throws IOException {
            try (Reader reader = store.read(ref).reader()) {
                JsonArray rows = new JsonArray();
                rows.add(JsonParser.parseReader(reader));
                ArrayList<TranscriptText> result = TranscriptText.read(rows);
                if (result.size() != 1) throw new IOException("索引正文投影无效");
                return result.get(0);
            } catch (RuntimeException error) { throw new IOException("索引正文无法读取", error); }
        }
    }

    /** 只读索引并建立惰性正文引用；损坏索引直接失败，不能变成空根覆盖旧记录。 */
    public Snapshot read(String remote) throws IOException {
        String key = TranscriptStore.digest(remote);
        byte[] payload = readPayload(key);
        if (payload == null) return new Snapshot(new TranscriptWindow(), null, scopeKey, key);
        Path bodyDirectory = bodyDirectory(key);
        TranscriptBodyStore bodies = bodyFactory.open(bodyDirectory);
        try (IndexInput input = new IndexInput(new ByteArrayInputStream(payload))) {
            Header header = readHeader(input, key);
            ArrayList<String> blobs = new ArrayList<>();
            int blobCount = readCount(input, 32);
            for (int i = 0; i < blobCount; i++) blobs.add(readHash(input));
            ArrayList<TranscriptWindow.Body> indexedBodies = new ArrayList<>();
            int bodyCount = readCount(input, 52);
            for (int i = 0; i < bodyCount; i++) {
                int blob = input.readInt(); long offset = input.readLong(), length = input.readLong();
                String rowHash = readHash(input);
                if (blob < 0 || blob >= blobs.size() || offset < 0 || length <= 0 || length > LIMIT || offset > LIMIT - length)
                    throw new IOException("历史正文索引范围无效");
                TranscriptBodyStore.Ref ref = new TranscriptBodyStore.Ref(blobs.get(blob), offset, length, rowHash);
                indexedBodies.add(new TranscriptWindow.Body(new PersistedContent(bodies, ref, bodyDirectory)));
            }
            ArrayList<TranscriptWindow.IndexedSegment> segments = new ArrayList<>();
            int segmentCount = readCount(input, 21);
            for (int i = 0; i < segmentCount; i++) {
                String epoch = readText(input, false); int oldest = input.readInt();
                String cursor = readText(input, true), tailCursor = readText(input, true);
                int flags = input.readUnsignedByte();
                if ((flags & ~7) != 0) throw new IOException("历史索引状态无效");
                ArrayList<TranscriptWindow.RowRef> rows = new ArrayList<>();
                int rowCount = readCount(input, 25);
                for (int j = 0; j < rowCount; j++) {
                    int number = input.readInt(); String source = readText(input, false), local = readText(input, true);
                    int outgoing = input.readUnsignedByte(); long time = input.readLong(); int body = input.readInt();
                    if (outgoing > 1 || body < 0 || body >= indexedBodies.size()) throw new IOException("历史消息索引无效");
                    rows.add(new TranscriptWindow.RowRef(number, source, local, outgoing == 1, time, indexedBodies.get(body)));
                }
                LinkedHashMap<String,Integer> aliases = new LinkedHashMap<>();
                int aliasCount = readCount(input, 8);
                for (int j = 0; j < aliasCount; j++) {
                    String source = readText(input, false); int number = input.readInt();
                    if (aliases.put(source, number) != null) throw new IOException("历史来源别名重复");
                }
                segments.add(new TranscriptWindow.IndexedSegment(epoch, oldest, cursor, tailCursor,
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, rows, aliases));
            }
            if (input.read() != -1) throw new IOException("历史索引含尾随内容");
            Snapshot snapshot = new Snapshot(TranscriptWindow.restoreIndexed(segments), header.generation, scopeKey, key);
            snapshot.importedLegacySha = header.importedLegacySha;
            return snapshot;
        } catch (RuntimeException error) { throw new IOException("历史索引格式无效", error); }
    }

    /** 仅供同队列薄门面使用，其token先验证当前可写根与磁盘家族。 */
    Snapshot resume(String remote, TranscriptWindow window, String generation, String importedLegacySha) {
        Snapshot snapshot = new Snapshot(window, generation, scopeKey, TranscriptStore.digest(remote));
        snapshot.importedLegacySha = importedLegacySha;
        return snapshot;
    }

    /** 回退导出也必须先验证实际代次，不能把迟到根导出为旧版正文。 */
    void verify(String remote, String expectedGeneration) throws IOException {
        verifyGeneration(TranscriptStore.digest(remote), expectedGeneration);
    }

    /** 显式导入已完整校验的v2；既有索引存在即拒绝，不碰原v2文件或官方对话。 */
    public Snapshot importLegacy(String remote, TranscriptWindow validatedLegacy) throws IOException {
        Snapshot snapshot = read(remote);
        if (snapshot.generation != null) throw new IOException("索引已存在，不能覆盖导入");
        snapshot.window = Objects.requireNonNull(validatedLegacy);
        write(remote, snapshot);
        return snapshot;
    }

    /** 新正文先落不可变块，再原子替换索引；已有块只保存引用，不再读取或复制整份历史。 */
    public void write(String remote, Snapshot snapshot) throws IOException {
        write(remote, snapshot, additional -> {});
    }

    /** 先计划本轮增量的物理峰值，再写块和索引；预算失败不留下新增正文。 */
    void write(String remote, Snapshot snapshot, CapacityGate capacity) throws IOException {
        String key = TranscriptStore.digest(remote);
        if (!scopeKey.equals(snapshot.scopeKey) || !key.equals(snapshot.remoteKey)) throw new IOException("索引写入归属已改变");
        verifyGeneration(key, snapshot.generation);
        List<TranscriptWindow.IndexedSegment> segments = snapshot.window.exportIndexedSegments();
        Path bodyDirectory = bodyDirectory(key);
        Files.createDirectories(bodyDirectory);
        TranscriptBodyStore bodies = bodyFactory.open(bodyDirectory);
        IdentityHashMap<TranscriptWindow.Body, PersistedContent> bindings = new IdentityHashMap<>();
        ArrayList<TranscriptWindow.Body> fresh = new ArrayList<>();
        ArrayList<byte[]> encoded = new ArrayList<>();
        for (TranscriptWindow.IndexedSegment segment : segments) for (TranscriptWindow.RowRef row : segment.rows) {
            if (bindings.containsKey(row.body)) continue;
            TranscriptWindow.Content old = row.body.content();
            if (old instanceof PersistedContent && ((PersistedContent) old).directory.equals(bodyDirectory)) {
                bindings.put(row.body, (PersistedContent) old);
            } else {
                // Body可以跨段共享；同一新增正文只编码和写入一次，不随桥接重复复制。
                TranscriptText text = row.body.resident();
                if (text == null) text = old.read();
                encoded.add(TranscriptWindow.bodySnapshot(text).toString().getBytes(StandardCharsets.UTF_8));
                fresh.add(row.body);
                bindings.put(row.body, null);
            }
        }
        TranscriptBodyStore.Plan plan = bodies.plan(encoded);
        List<TranscriptBodyStore.Ref> newRefs = plan.refs;
        for (int i = 0; i < fresh.size(); i++) bindings.put(fresh.get(i), new PersistedContent(bodies, newRefs.get(i), bodyDirectory));
        String generation = UUID.randomUUID().toString();
        byte[] payload = encodeIndex(key, generation, snapshot.importedLegacySha, segments, bindings);
        if (payload.length > LIMIT) throw new IOException("历史索引超过容量");
        long previousBytes = Files.exists(indexPath(key), LinkOption.NOFOLLOW_LINKS) ? Files.size(indexPath(key)) : 0;
        capacity.beforeWrite(plan.additionalBytes + payload.length + 70L + previousBytes);
        List<TranscriptBodyStore.Ref> written = bodies.write(encoded);
        if (written.size() != newRefs.size()) throw new IOException("正文块计划已改变");
        for (int i = 0; i < written.size(); i++) {
            if (!new RefKey(written.get(i)).equals(new RefKey(newRefs.get(i)))) throw new IOException("正文块计划已改变");
        }
        Path temporary = Files.createTempFile(scope, key + "-", ".pending");
        boolean published = false;
        try {
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                output.write(MAGIC); output.write(sha(payload).getBytes(StandardCharsets.US_ASCII)); output.write('\n'); output.write(payload);
            }
            publishGate.beforePublish();
            verifyGeneration(key, snapshot.generation);
            preservePrevious(key);
            Files.move(temporary, indexPath(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
        } finally { if (!published) Files.deleteIfExists(temporary); }
        // 发布失败永不重绑；成功后各段引用同一不可变body，卸下不再需要的常驻正文。
        for (Map.Entry<TranscriptWindow.Body, PersistedContent> binding : bindings.entrySet()) binding.getKey().bindPersisted(binding.getValue());
        snapshot.generation = generation;
    }

    /** 提交前保留当前完整索引；失败最多让previous与current相同，不撤掉可读的current。 */
    private void preservePrevious(String key) throws IOException {
        Path current = indexPath(key);
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        Path temporary = Files.createTempFile(scope, key + "-", ".pending");
        boolean moved = false;
        try {
            Files.copy(current, temporary, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            Files.move(temporary, scope.resolve(key + ".window.previous"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally { if (!moved) Files.deleteIfExists(temporary); }
    }

    /** 单一历史队列中以实际文件代次检查晚写；本原型不承诺跨进程CAS。 */
    private void verifyGeneration(String key, String expected) throws IOException {
        byte[] current = readPayload(key);
        String observed = null;
        if (current != null) try (IndexInput input = new IndexInput(new ByteArrayInputStream(current))) {
            observed = readHeader(input, key).generation;
        }
        if (!Objects.equals(expected, observed)) throw new IOException("历史索引代次已改变");
    }

    /** 固定文件头加整份索引校验；正文不在此文件，校验不会触发旧消息IO。 */
    private byte[] readPayload(String key) throws IOException {
        Path path = indexPath(key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > LIMIT + 70)
            throw new IOException("历史索引文件无效");
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
            byte[] header = new byte[70];
            new DataInputStream(input).readFully(header);
            if (!Arrays.equals(MAGIC, Arrays.copyOf(header, 5)) || header[69] != '\n')
                throw new IOException("历史索引头无效");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) bytes.size() + count > LIMIT) throw new IOException("历史索引超过容量");
                bytes.write(buffer, 0, count);
            }
            byte[] payload = bytes.toByteArray();
            if (!sha(payload).equals(new String(header, 5, 64, StandardCharsets.US_ASCII))) throw new IOException("历史索引校验失败");
            return payload;
        }
    }

    /** 哈希路径只由原scope和远端身份生成，不接受索引载入任意本机路径。 */
    private Path indexPath(String key) { return scope.resolve(key + ".window"); }

    /** 每会话块目录与索引共用同一个账号和来源分区。 */
    private Path bodyDirectory(String key) { return scope.resolve(key + ".bodies"); }

    /** 定序轻索引不重复字段名、块哈希和canonical来源映射；所有正文仍由原块owner管理。 */
    private byte[] encodeIndex(String key, String generation, String importedLegacySha, List<TranscriptWindow.IndexedSegment> segments,
            IdentityHashMap<TranscriptWindow.Body, PersistedContent> bindings) throws IOException {
        LinkedHashMap<String,Integer> blobs = new LinkedHashMap<>();
        LinkedHashMap<RefKey,Integer> refs = new LinkedHashMap<>();
        IdentityHashMap<TranscriptWindow.Body,Integer> bodyNumbers = new IdentityHashMap<>();
        for (TranscriptWindow.IndexedSegment segment : segments) for (TranscriptWindow.RowRef row : segment.rows) {
            if (bodyNumbers.containsKey(row.body)) continue;
            TranscriptBodyStore.Ref ref = bindings.get(row.body).ref;
            if (!blobs.containsKey(ref.blobSha256)) blobs.put(ref.blobSha256, blobs.size());
            RefKey reference = new RefKey(ref);
            Integer number = refs.get(reference);
            if (number == null) { number = refs.size(); refs.put(reference, number); }
            bodyNumbers.put(row.body, number);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(4); writeText(output, scopeKey); writeText(output, key); writeText(output, generation);
            writeText(output, importedLegacySha);
            output.writeInt(blobs.size()); for (String blob : blobs.keySet()) writeHash(output, blob);
            output.writeInt(refs.size());
            for (RefKey reference : refs.keySet()) {
                TranscriptBodyStore.Ref ref = reference.ref;
                output.writeInt(blobs.get(ref.blobSha256)); output.writeLong(ref.offset); output.writeLong(ref.length); writeHash(output, ref.rowSha256);
            }
            output.writeInt(segments.size());
            for (TranscriptWindow.IndexedSegment segment : segments) {
                writeText(output, segment.epoch); output.writeInt(segment.oldest);
                writeText(output, segment.cursor); writeText(output, segment.tailCursor);
                output.writeByte((segment.hasMore ? 1 : 0) | (segment.loaded ? 2 : 0) | (segment.complete ? 4 : 0));
                output.writeInt(segment.rows.size()); HashSet<String> canonical = new HashSet<>();
                for (TranscriptWindow.RowRef row : segment.rows) {
                    output.writeInt(row.id); writeText(output, row.sourceId); writeText(output, row.localId);
                    output.writeByte(row.outgoing ? 1 : 0); output.writeLong(row.createdAtMs); output.writeInt(bodyNumbers.get(row.body));
                    canonical.add(row.sourceId);
                }
                int aliases = 0;
                for (String source : segment.sourceNumbers.keySet()) if (!canonical.contains(source)) aliases++;
                output.writeInt(aliases);
                for (Map.Entry<String,Integer> source : segment.sourceNumbers.entrySet()) if (!canonical.contains(source.getKey())) {
                    writeText(output, source.getKey()); output.writeInt(source.getValue());
                }
                if (bytes.size() > LIMIT) throw new IOException("历史索引超过容量");
            }
        }
        return bytes.toByteArray();
    }

    /** 行引用相等依据四个真实字段，避免为每行拼接大型临时字符串或全局intern。 */
    private static final class RefKey {
        final TranscriptBodyStore.Ref ref;
        RefKey(TranscriptBodyStore.Ref ref) { this.ref = ref; }
        @Override public int hashCode() { return 31 * (31 * ref.blobSha256.hashCode() + ref.rowSha256.hashCode()) + Long.hashCode(ref.offset) + Long.hashCode(ref.length); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof RefKey)) return false;
            TranscriptBodyStore.Ref that = ((RefKey)other).ref;
            return ref.offset == that.offset && ref.length == that.length && ref.blobSha256.equals(that.blobSha256) && ref.rowSha256.equals(that.rowSha256);
        }
    }

    /** 头部归属和代次仍在整份字节校验之后验证，不因快读头部省掉完整性检查。 */
    private Header readHeader(IndexInput input, String remote) throws IOException {
        if (input.readInt() != 4 || !scopeKey.equals(readText(input, false)) || !remote.equals(readText(input, false)))
            throw new IOException("历史索引归属无效");
        String generation = readText(input, false);
        try { if (!UUID.fromString(generation).toString().equals(generation)) throw new IOException("历史索引代次无效"); }
        catch (IllegalArgumentException error) { throw new IOException("历史索引代次无效", error); }
        String legacySha = readText(input, true);
        if (legacySha != null && !legacySha.matches("[0-9a-f]{64}")) throw new IOException("历史迁移来源无效");
        return new Header(generation, legacySha);
    }

    private static final class Header {
        final String generation, importedLegacySha;
        Header(String generation, String importedLegacySha) { this.generation = generation; this.importedLegacySha = importedLegacySha; }
    }

    /** 项数只与剩余真实字节核对，不按未验证声明预分配巨大数组。 */
    private static int readCount(DataInputStream input, int minimumBytes) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > input.available() / minimumBytes) throw new IOException("历史索引项数无效");
        return count;
    }

    /** UTF8显式长度区分null和空串，不使用有65535字节限制的modified UTF。 */
    private static void writeText(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IOException("历史索引文本不是完整Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IOException("历史索引文本不是完整Unicode");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("历史索引文本超过容量");
        output.writeInt(bytes.length); output.write(bytes);
    }

    /** 字符串只能分配已在校验载荷内的真实长度，截断与非法null直接失败。 */
    private static String readText(IndexInput input, boolean nullable) throws IOException {
        int count = input.readInt();
        if (count == -1 && nullable) return null;
        if (count < 0 || count > input.available()) throw new IOException("历史索引文本长度无效");
        byte[] bytes = new byte[count]; input.readFully(bytes);
        return input.utf8.reset().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /** 每次读索引独享一个严格解码器，坏字节不能替换成另一个来源身份；没有全局缓存或新线程。 */
    private static final class IndexInput extends DataInputStream {
        final java.nio.charset.CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        /** 复用本次读取流的关闭语义，解码器不会跨代次共享。 */
        IndexInput(InputStream input) { super(input); }
    }

    /** 磁盘哈希使用32字节，不重复保存64字符十六进制。 */
    private static void writeHash(DataOutputStream output, String value) throws IOException {
        if (value.length() != 64) throw new IOException("历史正文哈希无效");
        for (int i = 0; i < 64; i += 2) {
            int high = Character.digit(value.charAt(i), 16), low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) throw new IOException("历史正文哈希无效");
            output.writeByte((high << 4) | low);
        }
    }

    /** 与块owner保持小写哈希键，转换不启动每行正则或格式化对象。 */
    private static String readHash(DataInputStream input) throws IOException {
        byte[] bytes = new byte[32]; input.readFully(bytes);
        return hex(bytes);
    }

    /** 固定字符表输出哈希，字节保持不变。 */
    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255; result[i * 2] = alphabet[value >>> 4]; result[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(result);
    }

    /** 索引校验以实际UTF8字节为准，不对用户正文作字符串格式化或诊断输出。 */
    private static String sha(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray(), result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 255;
                result[i * 2] = hex[value >>> 4]; result[i * 2 + 1] = hex[value & 15];
            }
            return new String(result);
        }
        catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }

}
