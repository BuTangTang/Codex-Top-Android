package com.butang.codextop;

import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 编译真实Store，缓存仅在独立临时目录；文件替身只观测listFiles和mtime系统边界。 */
public final class TranscriptStoreCostTest {
    private static int groups, assertions;
    private static volatile Object sink;
    private static final String SERVER = " synthetic-server\n", ACCOUNT = "账号🌌", MACHINE = "synthetic-machine\t";
    private static final String REMOTE = "\n合成会话🌌\t", LOCAL = " local\uFFFD-id ";

    /** 各模式在独立JVM执行，旧源与候选共用断言；性能模式不把桌面JVM数据等同Android表现。 */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("mode required");
        if (args[0].equals("digest")) { digestCompatibility(); knownDigestBytes(); }
        else if (args[0].equals("trim")) trimCases();
        else if (args[0].equals("allocation")) digestAllocation();
        else if (args[0].equals("seed")) seed(Paths.get(args[1]));
        else if (args[0].equals("reopen")) reopen(Paths.get(args[1]));
        else throw new IllegalArgumentException("unknown mode");
        System.out.println("TranscriptStoreCost " + args[0] + ": groups=" + groups + " assertions=" + assertions + " PASS");
    }

    /** 已知SHA向量、旧格式器以及独立BigInteger oracle同时核对UTF-8、空白及替换字符规则。 */
    private static void digestCompatibility() throws Exception {
        String[] inputs = {"", "abc", " ", "\n\r\t", "账号/电脑/会话", "e\u0301", "é", "🌌", "\uFFFD", "\uD800", "x\uDC00y", "  preserved \n", repeat("汉🌌", 4096)};
        check(TranscriptStore.digest("").equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), "empty SHA256 vector");
        check(TranscriptStore.digest("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "abc SHA256 vector");
        for (String input : inputs) {
            String actual = TranscriptStore.digest(input);
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            String independent = new java.math.BigInteger(1, bytes).toString(16);
            while (independent.length() < 64) independent = "0" + independent;
            check(actual.equals(independent), "independent known UTF8 digest mismatch");
            StringBuilder original = new StringBuilder();
            for (byte value : bytes) original.append(String.format(Locale.ROOT, "%02x", value & 255));
            check(actual.equals(original.toString()) && actual.matches("[0-9a-f]{64}"), "original lower-case format compatibility");
        }
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check(TranscriptStore.digest("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "locale changed hash");
        } finally { Locale.setDefault(previous); }
        groups++;
    }

    /** 用测试进程的SHA工厂返回固定32字节，覆盖所有256个字节的前导零与符号位；随后移除provider。 */
    private static void knownDigestBytes() {
        Provider provider = new Provider("SyntheticDigestCostTest", 1.0, "synthetic digest byte boundary") {};
        provider.put("MessageDigest.SHA-256", FixedDigest.class.getName());
        check(Security.insertProviderAt(provider, 1) == 1, "synthetic provider not installed");
        try {
            for (int block = 0; block < 8; block++) {
                FixedDigest.block = block;
                String expected = "";
                for (int i = 0; i < 32; i++) {
                    int value = block * 32 + i;
                    expected += Character.forDigit(value / 16, 16);
                    expected += Character.forDigit(value % 16, 16);
                }
                check(TranscriptStore.digest("synthetic input").equals(expected), "leading zero/signed byte conversion");
            }
        } finally { Security.removeProvider(provider.getName()); }
        groups++;
    }

    /** 只供已安装的测试provider实例化，不连接真实摘要源。 */
    public static final class FixedDigest extends MessageDigestSpi {
        static int block;
        /** 测试仅关注编码，输入字节仍由真实Store按UTF-8提供。 */
        @Override protected void engineUpdate(byte value) { }
        /** 接受原SHA工厂的块输入，不修改Store。 */
        @Override protected void engineUpdate(byte[] bytes, int offset, int length) { }
        /** 每轮产生32个预知字节，共八轮覆盖全部可能值。 */
        @Override protected byte[] engineDigest() { byte[] bytes = new byte[32]; for (int i = 0; i < 32; i++) bytes[i] = (byte)(block * 32 + i); return bytes; }
        /** 测试工厂没有跨调用状态。 */
        @Override protected void engineReset() { }
    }

    /** 对真实方法测线程分配，宽阈值只防止逐字节Formatter重引入，不对耗时作脆弱门槛。 */
    private static void digestAllocation() throws Exception {
        Object bean = ManagementFactory.getThreadMXBean();
        Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
        check((Boolean)type.getMethod("isThreadAllocatedMemorySupported").invoke(bean), "allocation counter unsupported");
        type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
        Method allocated = type.getMethod("getThreadAllocatedBytes", long.class);
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < 2000; i++) sink = TranscriptStore.digest("synthetic-cache-key-" + (i % 20));
        String input = "synthetic-cache-key-constant";
        int calls = 3000;
        long startBytes = (Long)allocated.invoke(bean, thread);
        long startCpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(), startNanos = System.nanoTime();
        for (int i = 0; i < calls; i++) sink = TranscriptStore.digest(input);
        long nanos = System.nanoTime() - startNanos, cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - startCpu;
        long bytes = (Long)allocated.invoke(bean, thread) - startBytes;
        double perCall = (double)bytes / calls;
        System.out.println("METRIC digest calls=" + calls + " totalAllocatedBytes=" + bytes + " allocatedBytesPerCall=" + perCall + " wallNanos=" + nanos + " cpuNanos=" + cpu);
        check(perCall < 4096, "digest allocates >=4096 bytes per call: " + perCall);
        groups++;
    }

    /** 真实文件枚举、长度、删除保持；只观测mtime并让目录失败可确定复现。 */
    private static void trimCases() throws Exception {
        Path root = Files.createTempDirectory("codex-store-cost-trim-");
        try {
            noSortUnderBudget(root.resolve("under"), 100);
            noSortUnderBudget(root.resolve("exact"), 30);
            evictionOrder(root.resolve("over"), false);
            evictionOrder(root.resolve("tie"), true);
            enumerationFailure(root.resolve("root-error"), false);
            enumerationFailure(root.resolve("scope-error"), true);
            laterScopeFailureAtBudget(root.resolve("later-scope-error"));
            rejectedEntries(root.resolve("rejected"));
            actualWriteUnderBudget(root.resolve("write"));
        } finally { deleteTree(root); }
    }

    /** 未满与恰好到预算都必须完成枚举/长度统计且不调用mtime，也不删除任何缓存。 */
    private static void noSortUnderBudget(Path root, long budget) throws Exception {
        Fixture f = new Fixture(root, budget);
        for (int i = 0; i < 3; i++) f.add("file-" + i, 10, i + 1);
        f.install(); trim(f.store, f.children.get(2));
        check(f.root.lists == 1 && f.scope.lists == 1, "under budget skipped enumeration");
        for (ObservedFile file : f.children) {
            check(file.exists() && file.lengths >= 1, "under budget skipped size or deleted data");
            check(file.modified == 0, "under budget queried mtime " + file.modified + " times");
        }
        groups++;
    }

    /** 超限时维持最旧优先与同时间的路径排序，刚保存的文件即使最旧也不会淘汰。 */
    private static void evictionOrder(Path root, boolean tie) throws Exception {
        Fixture f = new Fixture(root, 20);
        ObservedFile saved = f.add("saved", 10, 1);
        ObservedFile first = f.add("candidate-a", 10, tie ? 2 : 3);
        ObservedFile second = f.add("candidate-b", 10, 2);
        ObservedFile removed = tie ? (first.getPath().compareTo(second.getPath()) < 0 ? first : second) : second;
        ObservedFile kept = removed == first ? second : first;
        f.install(); trim(f.store, saved);
        check(saved.exists() && kept.exists() && !removed.exists(), "over budget order/save exclusion changed");
        check(saved.modified + first.modified + second.modified > 0, "over budget omitted mtime sort");
        check(Files.size(saved.toPath()) + Files.size(kept.toPath()) == 20, "over budget wrong final bytes");
        groups++;
    }

    /** 空的部分统计不能掩盖目录枚举失败；失败仍是原IOException。 */
    private static void enumerationFailure(Path root, boolean scopeFailure) throws Exception {
        Fixture f = new Fixture(root, 100); f.install();
        if (scopeFailure) f.scope.children = null; else f.root.children = null;
        try { trim(f.store, new File(root.toFile(), "not-saved")); throw new AssertionError("directory failure swallowed"); }
        catch (IOException expected) { check(expected.getMessage().equals(scopeFailure ? "正文缓存分区无法读取" : "正文缓存目录无法读取"), "enumeration error changed"); }
        groups++;
    }

    /** 第一分区恰好达到预算仍须继续枚举，后续合法分区失败不能被提前返回掩盖。 */
    private static void laterScopeFailureAtBudget(Path root) throws Exception {
        Fixture f = new Fixture(root, 10); ObservedFile saved = f.add("saved", 10, 1); f.install();
        ObservedFile later = new ObservedFile(Files.createDirectories(root.resolve(repeat("f", 64))).toFile());
        later.children = null; f.root.children = new File[]{f.scope, later};
        try { trim(f.store, saved); throw new AssertionError("later scope failure at budget swallowed"); }
        catch (IOException expected) { check(expected.getMessage().equals("正文缓存分区无法读取"), "later scope error changed"); }
        check(f.scope.lists == 1 && later.lists == 1 && saved.exists(), "later scope skipped or existing body deleted");
        groups++;
    }

    /** 保持哈希名与NOFOLLOW_LINKS规则，非正文、目录以及文件/目录软链不计入预算也不删除。 */
    private static void rejectedEntries(Path root) throws Exception {
        Fixture f = new Fixture(root, 10);
        ObservedFile saved = f.add("saved", 10, 1);
        Path external = Files.createDirectories(root.resolve("outside"));
        Path externalFile = Files.write(external.resolve("preserved"), new byte[50]);
        Path wrongName = Files.write(f.scope.toPath().resolve("draft.json"), new byte[50]);
        Path tmp = Files.write(f.scope.toPath().resolve(repeat("a",64) + ".tmp"), new byte[50]);
        Path dir = Files.createDirectory(f.scope.toPath().resolve(repeat("b",64) + ".json"));
        Path link = Files.createSymbolicLink(f.scope.toPath().resolve(repeat("c",64) + ".json"), externalFile);
        Path scopeLink = Files.createSymbolicLink(root.resolve(repeat("d",64)), external);
        f.install();
        f.scope.children = new File[]{saved, wrongName.toFile(), tmp.toFile(), dir.toFile(), link.toFile()};
        f.root.children = new File[]{f.scope, scopeLink.toFile(), external.toFile()};
        trim(f.store, saved);
        check(saved.exists() && saved.modified == 0 && Files.exists(externalFile) && Files.isSymbolicLink(link) && Files.isSymbolicLink(scopeLink), "rejected entry entered budget or was removed");
        check(Files.exists(wrongName) && Files.exists(tmp) && Files.isDirectory(dir), "non-body data changed");
        groups++;
    }

    /** 从真实write调用进入同一淘汰路径，确认提前返回不是仅测试反射路径的优化。 */
    private static void actualWriteUnderBudget(Path root) throws Exception {
        Files.createDirectories(root);
        ObservedFile rootFile = new ObservedFile(root.toFile());
        ObservedFile scope = new ObservedFile(Files.createDirectories(root.resolve(TranscriptStore.digest(SERVER + "\n" + ACCOUNT + "\n" + MACHINE))).toFile());
        Path oldPath = Files.write(scope.toPath().resolve(TranscriptStore.digest("existing") + ".json"), new byte[10]);
        ObservedFile old = new ObservedFile(oldPath.toFile());
        ObservedFile target = new ObservedFile(scope.toPath().resolve(TranscriptStore.digest(REMOTE) + ".json").toFile());
        scope.children = new File[]{old,target}; rootFile.children = new File[]{scope};
        TranscriptStore store = new TranscriptStore(rootFile, SERVER, ACCOUNT, MACHINE, 10000);
        store.write(REMOTE, window());
        check(rootFile.lists == 1 && scope.lists == 1 && old.modified + target.modified == 0, "write still performs unnecessary mtime sorting");
        check(store.read(REMOTE).findSource("synthetic-message", LOCAL) != null && old.exists(), "write/read owner or other cache changed");
        groups++;
    }

    /** 跨两个独立JVM保存真实Transcript/Outbox/Dialog数据，目录和文件键不可迁移或重命名。 */
    private static void seed(Path root) throws Exception {
        new TranscriptStore(root.resolve("history").toFile(), SERVER, ACCOUNT, MACHINE).write(REMOTE, window());
        new OutboxStore(root.resolve("outbox").toFile(), SERVER, ACCOUNT, MACHINE).put(new OutboxStore.Item(LOCAL, REMOTE, "合成待发", -42, 123));
        new DialogStore(root.resolve("dialogs").toFile(), SERVER, ACCOUNT).write(JsonParser.parseString("{\"machineId\":\"synthetic-machine\",\"candidates\":[{\"remoteSessionId\":\"synthetic-remote\",\"updatedAtMs\":123,\"title\":\"synthetic\"}]}").getAsJsonObject());
        groups++;
    }

    /** 旧源写候选读、候选写旧源读沿相同落盘位置；账号隔离不因哈希优化改变。 */
    private static void reopen(Path root) throws Exception {
        TranscriptWindow read = new TranscriptStore(root.resolve("history").toFile(), SERVER, ACCOUNT, MACHINE).read(REMOTE);
        check(read.findSource("synthetic-message", LOCAL) != null && read.before(0,1).get(0).message.text.equals("合成历史🌌"), "persisted history identity/body lost");
        List<OutboxStore.Item> pending = new OutboxStore(root.resolve("outbox").toFile(), SERVER, ACCOUNT, MACHINE).list(REMOTE);
        check(pending.size() == 1 && pending.get(0).localId.equals(LOCAL) && pending.get(0).messageId == -42 && pending.get(0).text.equals("合成待发"), "persisted outbox lost");
        check(new DialogStore(root.resolve("dialogs").toFile(), SERVER, ACCOUNT).read().get("machineId").getAsString().equals("synthetic-machine"), "persisted dialog metadata lost");
        check(!new TranscriptStore(root.resolve("history").toFile(), SERVER, ACCOUNT + "other", MACHINE).read(REMOTE).loaded, "account isolation lost");
        check(new OutboxStore(root.resolve("outbox").toFile(), SERVER, ACCOUNT, MACHINE + "other").list(REMOTE).isEmpty(), "machine isolation lost");
        groups++;
    }

    /** 生成稳定身份、时间和Unicode正文，完全是合成数据。 */
    private static TranscriptWindow window() throws Exception {
        com.google.gson.JsonObject item = new com.google.gson.JsonObject(); item.addProperty("id", "synthetic-message"); item.addProperty("localId", LOCAL); item.addProperty("createdAtMs", 1000);
        com.google.gson.JsonObject raw = new com.google.gson.JsonObject(), content = new com.google.gson.JsonObject(); content.addProperty("type", "text"); content.addProperty("text", "合成历史🌌"); raw.addProperty("role", "user"); raw.add("content",content); item.add("raw",raw);
        com.google.gson.JsonObject page = new com.google.gson.JsonObject(); com.google.gson.JsonArray items = new com.google.gson.JsonArray(); items.add(item); page.add("items",items); page.addProperty("hasMore",false); page.addProperty("tailCursor","synthetic-tail");
        TranscriptWindow window = new TranscriptWindow(); window.prepend(page); return window;
    }

    /** 不改可见性或生产算法，只让测试调用原私有淘汰owner并还原其异常。 */
    private static void trim(TranscriptStore store, File saved) throws Exception {
        Method method = TranscriptStore.class.getDeclaredMethod("trimOldTranscripts",File.class); method.setAccessible(true);
        try { method.invoke(store,saved); } catch (InvocationTargetException e) { if (e.getCause() instanceof Exception) throw (Exception)e.getCause(); throw (Error)e.getCause(); }
    }

    /** 替身路径全是真文件，只允许系统边界的枚举结果和mtime调用可观察。 */
    private static final class ObservedFile extends File {
        File[] children; int lists, modified, lengths;
        /** 保留真实绝对路径，不模拟文件内容、长度、mtime值或删除行为。 */
        ObservedFile(File source) { super(source.getPath()); }
        /** 目录返回确定序列，null用于复现原文件系统枚举失败。 */
        @Override public File[] listFiles() { lists++; return children; }
        /** 统计真实mtime读取次数，实际排序值由文件系统返回。 */
        @Override public long lastModified() { modified++; return super.lastModified(); }
        /** 统计扫描仍执行的真实长度读取。 */
        @Override public long length() { lengths++; return super.length(); }
    }

    /** 每个场景有独立真实目录，Store只替换根File对象而没有测试专用生产分支。 */
    private static final class Fixture {
        final ObservedFile root, scope; final TranscriptStore store; final ArrayList<ObservedFile> children = new ArrayList<>();
        /** 初始化合法哈希分区，预算通过既有包内构造器传入。 */
        Fixture(Path path,long budget) throws Exception { Files.createDirectories(path); root=new ObservedFile(path.toFile()); scope=new ObservedFile(Files.createDirectories(path.resolve(repeat("e",64))).toFile()); store=new TranscriptStore(root,SERVER,ACCOUNT,MACHINE,budget); }
        /** 添加真实正文候选文件并固定mtime，不依赖机器运行时刻。 */
        ObservedFile add(String id,int bytes,long time) throws Exception { Path path=scope.toPath().resolve(TranscriptStore.digest(id)+".json"); Files.write(path,new byte[bytes]); Files.setLastModifiedTime(path,FileTime.fromMillis(time)); ObservedFile file=new ObservedFile(path.toFile()); children.add(file); return file; }
        /** 将真实文件按插入顺序交给原枚举算法，排序必须由生产owner执行。 */
        void install() { root.children=new File[]{scope}; scope.children=children.toArray(new File[0]); }
    }

    /** Java8兼容的合成字符串工具。 */
    private static String repeat(String value,int count) { StringBuilder builder=new StringBuilder(); for(int i=0;i<count;i++)builder.append(value); return builder.toString(); }
    /** 只清理本测试创建的临时目录，不访问应用或账号文件。 */
    private static void deleteTree(Path root) throws Exception { try(Stream<Path> paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);} }
    /** 逐项计数，失败保留对应真实断言。 */
    private static void check(boolean condition,String message) { assertions++; if(!condition)throw new AssertionError(message); }
}
