package com.butang.codextop;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;

/** 独立合成缓存证明有限正文读取与提交边界，不读取用户数据或操作App。 */
public final class IndexedTranscriptStoreTest {
    private static int assertions;

    /** 合成近期/旧页、失败发布、重开与并发代次，不把候选通过当Runtime或设备通过。 */
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("indexed-history-test").toRealPath();
        try {
            TrackingIo io = new TrackingIo();
            IndexedTranscriptStore store = measured(base, io);
            TranscriptWindow original = new TranscriptWindow();
            original.prepend(page(0, 2000, false));
            JsonObject expected = original.snapshot();
            IndexedTranscriptStore.Snapshot saved = store.importLegacy("thread", original);
            check(saved.generation() != null, "导入无提交代次");
            long originalBytes = io.writtenBytes;
            check(originalBytes > 500_000, "样例不足以区分全读");
            io.reset();
            IndexedTranscriptStore.Snapshot reopened = store.read("thread");
            check(io.readBytes == 0 && io.readOpens == 0, "恢复索引偷偷读正文");
            check(reopened.window.size() == 2000 && reopened.window.localSegments().get(0).count == 2000, "完整轻索引丢行");
            check(reopened.window.allOutgoingLocalIds().contains("local-0") && io.readBytes == 0, "旧待发回显需要全读");
            ArrayList<TranscriptWindow.Entry> recent = reopened.window.before(0, 30);
            check(recent.size() == 30 && recent.get(0).message.id.equals("row-1999") && recent.get(29).message.id.equals("row-1970"), "近期窗口错序");
            long recentBytes = io.readBytes;
            check(io.readOpens == 30 && recentBytes < originalBytes / 20, "30条读取超出请求正文");
            System.out.println("recent rows=30 bodyBytes=" + recentBytes + " completeBodyBytes=" + originalBytes + " readFiles=" + io.readOpens);
            io.reset();
            reopened.window.tailCursor = "tail-new";
            store.write("thread", reopened);
            check(io.readBytes == 0 && io.writtenBytes == 0, "只改游标仍读取或复制旧正文");
            IndexedTranscriptStore.Snapshot afterCursor = store.read("thread");
            check(afterCursor.window.tailCursor.equals("tail-new") && io.readBytes == 0, "游标未持久化或重开全文");
            ArrayList<TranscriptWindow.Entry> older = afterCursor.window.before(recent.get(29).id, 50);
            check(older.size() == 50 && older.get(0).message.id.equals("row-1969") && older.get(49).message.id.equals("row-1920"), "旧向跳页");
            check(io.readOpens == 50, "本地旧页读条数错误");
            io.reset();
            afterCursor.window.append(delta(2000));
            store.write("thread", afterCursor);
            check(io.readBytes == 0 && io.writtenBytes > 0 && io.writtenBytes < 2000, "单条增量复制了全量正文");
            System.out.println("delta rows=1 bodyReadBytes=" + io.readBytes + " bodyWriteBytes=" + io.writtenBytes);
            IndexedTranscriptStore.Snapshot full = store.read("thread");
            TranscriptWindow model = TranscriptWindow.restore(expected);
            model.tailCursor = "tail-new"; model.append(delta(2000));
            check(full.window.snapshot().equals(model.snapshot()), "有限读取后完整导出丢历史/编号/状态");
            aliasAndArchive(store, io);
            staleAndFailure(base, store, io);
            corruptedIndexAndBody(base, store, io);
            check(new IndexedTranscriptStore(base, "server", "other-account", "machine").read("thread").window.isEmpty(), "跨账号读出正文");
            check(new IndexedTranscriptStore(base, "server", "account", "other-machine").read("thread").window.isEmpty(), "跨电脑读出正文");
            check(new IndexedTranscriptStore(base, "other-server", "account", "machine").read("thread").window.isEmpty(), "跨服务器读出正文");
            System.out.println("IndexedTranscriptStore prototype PASS assertions=" + assertions);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(base)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** localId别名和旧段信息保存到完整索引，不依赖旧正文驻留。 */
    private static void aliasAndArchive(IndexedTranscriptStore store, TrackingIo io) throws Exception {
        TranscriptWindow window = new TranscriptWindow(); window.prepend(page(0, 60, true));
        JsonObject alias = delta(0); alias.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("id", "row-alias");
        check(window.append(alias).isEmpty(), "别名回显被追加");
        IndexedTranscriptStore.Snapshot snapshot = store.importLegacy("segments", window);
        io.reset(); snapshot = store.read("segments");
        check(snapshot.window.sourceNumber("row-alias", null).equals(snapshot.window.sourceNumber("row-0", null)), "来源别名未恢复");
        TranscriptWindow old = snapshot.window;
        snapshot.window = snapshot.window.acceptLatest(page(100, 102, true));
        check(snapshot.window != old && snapshot.window.findSegment("row-0", null) == old, "换根丢旧段");
        check(io.readBytes == 0, "切新段暗读旧正文");
        store.write("segments", snapshot); io.reset();
        snapshot = store.read("segments");
        check(snapshot.window.localSegments().size() == 2 && snapshot.window.allOutgoingLocalIds().contains("local-0"), "重开遗漏归档待发身份");
        check(io.readBytes == 0, "目录/回显核对读了旧正文");
        TranscriptWindow historical = snapshot.window.findSegment("row-0", null);
        check(historical != null && historical.before(0, 10).size() == 10, "本地旧段不可读");
        check(io.readOpens == 10, "旧段实际查询超出10条");
        io.reset();
        snapshot.window.prependWithCachedBridge(page(59, 101, true));
        check(io.readBytes == 0, "桥接为了编号恢复整段正文");
        store.write("segments", snapshot); io.reset();
        IndexedTranscriptStore.Snapshot bridged = store.read("segments");
        check(bridged.window.pruneMergedSegments(Collections.emptySet()) && bridged.window.localSegments().size() == 1,
                "重开丢共享正文身份，完整合并段不能收尾");
        check(io.readBytes == 0, "清理已合并副本偷偷读全文");
    }

    /** 晚写和索引发布失败保住既有提交；失败不能卸载仍在内存的新正文。 */
    private static void staleAndFailure(Path base, IndexedTranscriptStore store, TrackingIo io) throws Exception {
        IndexedTranscriptStore.Snapshot stale = store.read("thread"), current = store.read("thread");
        current.window.tailCursor = "newest-tail"; store.write("thread", current);
        expectIo(() -> store.write("thread", stale), "迟到根覆盖新代次");
        check(store.read("thread").window.tailCursor.equals("newest-tail"), "迟到写改了游标");
        expectIo(() -> store.write("other-thread", current), "跨会话写入接受原root");
        IndexedTranscriptStore failing = new IndexedTranscriptStore(base, "server", "account", "machine", () -> { throw new IOException("synthetic publish failure"); },
                directory -> new TranscriptBodyStore(directory, TranscriptBodyStore.MAX_BYTES, io));
        IndexedTranscriptStore.Snapshot attempt = failing.read("thread");
        String oldGeneration = attempt.generation(); attempt.window.append(delta(2001));
        expectIo(() -> failing.write("thread", attempt), "发布故障被吞");
        check(attempt.generation().equals(oldGeneration), "失败改了内存提交代次");
        check(attempt.window.exportIndexedSegments().get(0).rows.get(2001).body.resident() != null, "失败已释放未提交正文");
        IndexedTranscriptStore.Snapshot kept = store.read("thread");
        check(kept.generation().equals(oldGeneration) && kept.window.sourceNumber("row-2001", null) == null, "失败索引已发布");
        store.write("thread", attempt);
        check(store.read("thread").window.sourceNumber("row-2001", null) != null, "失败后无法安全重试提交");
    }

    /** 索引坏字节必须整体拒绝；未请求旧正文的局部损坏不能触发首屏全扫。 */
    private static void corruptedIndexAndBody(Path base, IndexedTranscriptStore store, TrackingIo io) throws Exception {
        Path scope = base.resolve(TranscriptStore.digest("server\naccount\nmachine"));
        Path index = scope.resolve(TranscriptStore.digest("thread") + ".window");
        byte[] valid = Files.readAllBytes(index), bad = valid.clone(); bad[bad.length - 1] ^= 1;
        Files.write(index, bad);
        expectIo(() -> store.read("thread"), "坏索引变空根"); Files.write(index, valid);
        Object persisted = store.read("thread").window.exportIndexedSegments().get(0).rows.get(0).body.content();
        java.lang.reflect.Field reference = persisted.getClass().getDeclaredField("ref"); reference.setAccessible(true);
        TranscriptBodyStore.Ref first = (TranscriptBodyStore.Ref) reference.get(persisted);
        Path body = scope.resolve(TranscriptStore.digest("thread") + ".bodies").resolve(first.blobSha256 + ".blob");
        long offset = first.offset;
        try (SeekableByteChannel channel = Files.newByteChannel(body, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            channel.position(offset); ByteBuffer old = ByteBuffer.allocate(1); channel.read(old);
            channel.position(offset); channel.write(ByteBuffer.wrap(new byte[]{(byte)(old.array()[0] ^ 1)}));
        }
        io.reset(); IndexedTranscriptStore.Snapshot snapshot = store.read("thread");
        check(io.readBytes == 0 && snapshot.window.before(0, 30).size() == 30, "旧坏块阻塞近期未涉行");
        check(io.readOpens == 30, "近期查询为检查损坏扫旧正文");
        expectIo(() -> snapshot.window.findSource("row-0", null), "损坏正文被交付");
        check(Arrays.equals(valid, Files.readAllBytes(index)), "读取失败覆盖了持久索引");
    }

    /** 计量沿真实块文件channel，不以模型回调次数冒充实际IO。 */
    private static IndexedTranscriptStore measured(Path base, TrackingIo io) throws IOException {
        return new IndexedTranscriptStore(base, "server", "account", "machine", () -> {}, directory -> new TranscriptBodyStore(directory, TranscriptBodyStore.MAX_BYTES, io));
    }

    /** 合成消息页稳定编号，包含中文、多字节与换行，不读真实会话。 */
    private static JsonObject page(int from, int to, boolean more) {
        JsonObject page = new JsonObject(); JsonArray items = new JsonArray();
        for (int i = from; i < to; i++) items.add(item(i));
        page.add("items", items); page.addProperty("hasMore", more); page.addProperty("historyAvailability", "available");
        page.addProperty("nextCursor", more ? "older-" + from : null); page.addProperty("tailCursor", "tail-" + to); return page;
    }

    /** 单条增量沿原tail协议，并允许构造localId重复回显。 */
    private static JsonObject delta(int id) {
        JsonObject value = new JsonObject(); JsonArray items = new JsonArray(); items.add(item(id));
        value.add("items", items); value.addProperty("nextCursor", "delta-" + id); return value;
    }

    /** 正文足够长以区别有限范围读与读取全部blob；用户回显身份只在用户行出现。 */
    private static JsonObject item(int id) {
        JsonObject item = new JsonObject(); item.addProperty("id", "row-" + id); item.addProperty("localId", id % 3 == 0 ? "local-" + id : null);
        item.addProperty("createdAtMs", 1700000000000L + id); JsonObject raw = new JsonObject(), content = new JsonObject();
        raw.addProperty("role", id % 3 == 0 ? "user" : "agent"); content.addProperty("type", "text");
        char[] fill = new char[256]; Arrays.fill(fill, 'x'); content.addProperty("text", "合成正文\n" + id + new String(fill));
        raw.add("content", content); item.add("raw", raw); return item;
    }

    /** 业务断言统一计数，失败保留具体边界。 */
    private static void check(boolean ok, String description) { assertions++; if (!ok) throw new AssertionError(description); }

    /** 异常必须来自IO失败合同，不能把任何Runtime错误冒作预期拒绝。 */
    private static void expectIo(Throwing action, String description) throws Exception {
        assertions++; try { action.run(); throw new AssertionError(description); } catch (IOException expected) { }
    }

    /** 可检查的合成动作只用于本测试。 */
    interface Throwing { void run() throws Exception; }

    /** 包装真正NIO读取/写入计量，目录枚举与索引字节不冒充正文IO。 */
    static final class TrackingIo extends TranscriptBodyStore.NioIo {
        long readBytes, writtenBytes; int readOpens;
        /** 每个场景独立计数。 */
        void reset() { readBytes = 0; writtenBytes = 0; readOpens = 0; }
        /** 委托原打开与关闭语义，只观察实际成功的字节数。 */
        @Override public SeekableByteChannel open(Path path, Set<OpenOption> options) throws IOException {
            SeekableByteChannel base = super.open(path, options); if (options.contains(StandardOpenOption.READ)) readOpens++;
            return new SeekableByteChannel() {
                public int read(ByteBuffer target) throws IOException { int n = base.read(target); if (n > 0) readBytes += n; return n; }
                public int write(ByteBuffer source) throws IOException { int n = base.write(source); if (n > 0) writtenBytes += n; return n; }
                public long position() throws IOException { return base.position(); }
                public SeekableByteChannel position(long value) throws IOException { base.position(value); return this; }
                public long size() throws IOException { return base.size(); }
                public SeekableByteChannel truncate(long value) throws IOException { base.truncate(value); return this; }
                public boolean isOpen() { return base.isOpen(); }
                public void close() throws IOException { base.close(); }
            };
        }
    }
}
