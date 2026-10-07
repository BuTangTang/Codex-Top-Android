package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;
import java.net.URL;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;

/** 新格式常态持久化另测；此专项验证真实Window逐行v2导出及独立回退文件，与冻结95字节对照。 */
public final class TranscriptStoreStreamingTest {
    private static int scenarios;
    private static final String SERVER = "synthetic-server", ACCOUNT = "synthetic-account", MACHINE = "synthetic-machine";

    /** 新测试和实际模型均可用Java8编译；不访问账号、网络或真实消息。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("codex-stream-store-").toRealPath();
        try {
            emptyFieldOrderAndNulls();
            escapedTextAndSplitSurrogates();
            archivedRowsAndAttachmentMetadata();
            migratedV1WritesV2();
            exactByteBudget(root.resolve("limits"));
            midWriteFailurePreservesOldAndOutbox(root.resolve("failure"));
            moveFailureCleansTemporary(root.resolve("move-failure"));
            readingKeepsOriginalParserAndDecoder(root.resolve("reader"));
            if (args.length > 0) originalSerializerOracle(java.nio.file.Paths.get(args[0]), root.resolve("baseline-classes"));
            System.out.println("TranscriptStoreStreaming explicit rollback export: scenarios=" + scenarios + " failures=0; Java8 synthetic file tests");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 独立固定字段顺序样例，不能让两条共用helper的路径一起丢字段还互相判定通过。 */
    private static void emptyFieldOrderAndNulls() throws Exception {
        TranscriptWindow window = new TranscriptWindow();
        String expected = "{\"version\":2,\"epoch\":\"" + window.epoch()
                + "\",\"oldest\":1000000000,\"cursor\":null,\"tailCursor\":null,\"hasMore\":true,\"loaded\":false,\"complete\":false,\"rows\":[],\"archive\":[]}";
        check(Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), streamed(window, false)), "empty field order/null/flags changed");
        pass("empty exact v2 bytes");
    }

    /** 单字符转交真实编码器，强制代理对跨write；控制字符、HTML、分隔符及孤立代理沿原规则。 */
    private static void escapedTextAndSplitSurrogates() throws Exception {
        String[] values = {"汉字 café e\u0301 🌌 <>&='\" \\ / \b\f\n\r\t\u0000 \u2028\u2029",
                repeat("a", 4095) + "🌌" + repeat("界", 4100), repeat("b", 8191) + "🌌tail",
                "lone-high:\uD83D end-low:\uDC00"};
        for (int i = 0; i < values.length; i++) {
            TranscriptWindow window = window("unicode-" + i, values[i], null, i == 0 ? Long.MAX_VALUE : Long.MIN_VALUE + i, null);
            checkSameBytes(window, false);
            checkSameBytes(window, true);
        }
        pass("escaping/nonASCII/split surrogate and long timestamp");
    }

    /** 三段平铺、空正文附件、可空附件字段与最大长整型大小全部经原元数据owner写回。 */
    private static void archivedRowsAndAttachmentMetadata() throws Exception {
        DesktopAttachment available = new DesktopAttachment("合成🌌.png", "image", "/synthetic/a.png", "image/png", Long.MAX_VALUE,
                repeat("a", 64), null, null);
        TranscriptWindow first = window("first", "", "outgoing-local", 1, available);
        first.cursor = ""; first.tailCursor = null;
        TranscriptWindow second = first.acceptLatest(page("second", "archived", null, 2, null));
        TranscriptWindow latest = second.acceptLatest(page("third", "latest", null, 3,
                new DesktopAttachment("不可用.txt", "file", null, null, null, null, "unavailable", "合成原因\nunknown")));
        latest.cursor = null;
        check(latest.snapshot().getAsJsonArray("archive").size() == 2, "archive fixture incomplete");
        checkSameBytes(latest, true);
        TranscriptWindow recovered = TranscriptWindow.restore(JsonParser.parseString(new String(streamed(latest, false), StandardCharsets.UTF_8)).getAsJsonObject());
        check(recovered.snapshot().equals(latest.snapshot()), "archive/attachment restore changed model");
        pass("flat archives and complete attachment metadata");
    }

    /** v1原编号与游标先由真实restore恢复，流式输出与原snapshot同为v2且复用生成的epoch。 */
    private static void migratedV1WritesV2() throws Exception {
        JsonObject old = window("legacy", "旧缓存", null, 4, null).snapshot();
        old.addProperty("version", 1); old.remove("epoch"); old.remove("archive");
        TranscriptWindow migrated = TranscriptWindow.restore(old);
        checkSameBytes(migrated, false);
        JsonObject saved = JsonParser.parseString(new String(streamed(migrated, false), StandardCharsets.UTF_8)).getAsJsonObject();
        check(saved.get("version").getAsInt() == 2 && saved.get("epoch").getAsString().equals(migrated.epoch()), "v1 migration epoch changed");
        pass("v1 restore then v2 exact bytes");
    }

    /** 显式回退导出按真实UTF8字节验正负一；新正常写入是索引+body，不把v2专项冒称正常保存。 */
    private static void exactByteBudget(Path root) throws Exception {
        TranscriptWindow window = window("budget", "汉🌌\n\"", null, 5, null);
        byte[] expected = window.snapshot().toString().getBytes(StandardCharsets.UTF_8);
        check(expected.length > window.snapshot().toString().length(), "fixture has no multibyte content");
        Path cache=root.resolve("cache"),exported=root.resolve("exported");
        store(cache,64L*1024*1024).write("thread",window);
        TranscriptStore exact = store(cache, expected.length);
        exact.exportForRollback("thread",window,exported);
        check(Arrays.equals(Files.readAllBytes(target(exported,"thread")), expected), "exact byte budget failed");
        store(cache, expected.length + 1).exportForRollback("thread",window,exported);
        FileTime modified = Files.getLastModifiedTime(target(exported,"thread"));
        expectExportFailure(store(cache, expected.length - 1), "thread", window,exported);
        check(Arrays.equals(Files.readAllBytes(target(exported,"thread")), expected)
                && modified.equals(Files.getLastModifiedTime(target(exported,"thread"))), "close-time overflow changed old export");
        assertNoTemporary(root);
        pass("explicit export exact UTF8 bytes and plus/minus one boundary");
    }

    /** 大于全部输出缓冲后才触发导出上限，旧导出、其他会话及待发保留，正式索引不受影响。 */
    private static void midWriteFailurePreservesOldAndOutbox(Path root) throws Exception {
        Path cache=root.resolve("cache"),exported=root.resolve("exported");
        TranscriptWindow old = window("old", "cached", "old-local", 6, null);
        TranscriptStore store = store(cache,64L*1024*1024);
        store.write("thread",old);TranscriptWindow otherWindow=TranscriptWindow.restore(old.snapshot());store.write("other",otherWindow);
        TranscriptStore exporter=store(cache,12000);
        exporter.exportForRollback("thread",old,exported);exporter.exportForRollback("other",otherWindow,exported);
        byte[] before = Files.readAllBytes(target(exported,"thread")), other = Files.readAllBytes(target(exported,"other"));
        OutboxStore outbox = new OutboxStore(exported.resolve("outbox").toFile(), SERVER, ACCOUNT, MACHINE);
        outbox.put(new OutboxStore.Item("pending", "thread", "synthetic pending", -1, 1));
        TranscriptWindow large=store.read("thread");large.append(page("large", repeat("汉🌌abc", 9000), null, 7, null));store.write("thread",large);
        Path current=target(cache,"thread").resolveSibling(TranscriptStore.digest("thread")+".window");byte[] committed=Files.readAllBytes(current);
        expectExportFailure(exporter, "thread", large,exported);
        check(Arrays.equals(before, Files.readAllBytes(target(exported,"thread")))
                && Arrays.equals(other, Files.readAllBytes(target(exported,"other"))), "mid-write overflow replaced/trimmed export");
        check(Arrays.equals(committed,Files.readAllBytes(current)),"failed export changed live index");
        check(outbox.list("thread").size() == 1 && "pending".equals(outbox.list("thread").get(0).localId), "export failure touched outbox");
        check(store(exported,12000).read("thread").findSource("old", "old-local") != null, "old export no longer reopens");
        assertNoTemporary(root);
        pass("mid-export overflow preserves old file/other/outbox and live index");
    }

    /** 独立导出的真实原子move失败必须清pending，非空原目标保留；没有常态v2写入假象。 */
    private static void moveFailureCleansTemporary(Path root) throws Exception {
        Path cache=root.resolve("cache"),exported=root.resolve("exported");
        TranscriptWindow window=window("move", "text", null, 8, null);TranscriptStore source=store(cache,100000);source.write("thread",window);
        Path directoryTarget = target(exported, "thread");
        Files.createDirectories(directoryTarget);
        Path retained = directoryTarget.resolve("retained"); Files.write(retained, new byte[]{1, 2, 3});
        expectExportFailure(source, "thread", window,exported);
        check(Arrays.equals(Files.readAllBytes(retained), new byte[]{1, 2, 3}), "move failure changed previous export target");
        assertNoTemporary(root);
        pass("explicit export atomic move failure and pending cleanup");
    }

    /** 与旧new String+parseString逐项比较，含非法UTF8替换、BOM、空白、宽松语法及尾随坏数据。 */
    private static void readingKeepsOriginalParserAndDecoder(Path root) throws Exception {
        TranscriptWindow original = window("read", "marker", null, 9, null);
        byte[] plain = original.snapshot().toString().getBytes(StandardCharsets.UTF_8);
        byte[] malformed = plain.clone();
        int position = new String(plain, StandardCharsets.UTF_8).indexOf("marker");
        malformed[position] = (byte) 0xc3; malformed[position + 1] = 0x28;
        byte[][] inputs = {plain, malformed,
                ("\uFEFF \n" + original.snapshot() + "\t ").getBytes(StandardCharsets.UTF_8),
                ("/*synthetic*/" + original.snapshot()).getBytes(StandardCharsets.UTF_8),
                (original.snapshot() + "{} ").getBytes(StandardCharsets.UTF_8), "null".getBytes(StandardCharsets.UTF_8)};
        Path file = target(root, "thread"); Files.createDirectories(file.getParent());
        TranscriptStore store = store(root, 100000);
        for (byte[] bytes : inputs) {
            Files.write(file, bytes);
            TranscriptWindow expected = null;
            try { expected = TranscriptWindow.restore(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()); }
            catch (RuntimeException | IOException invalid) { }
            if (expected == null) {
                try { store.read("thread"); throw new AssertionError("reader accepted original-rejected file"); }
                catch (IOException required) { }
            } else check(expected.snapshot().equals(store.read("thread").snapshot()), "reader changed original parse/decode semantics");
        }
        pass("buffered read matches original parseString/decoder");
    }

    /** 可选冻结源码oracle在独立loader里执行原snapshot，手工输入不经过当前序列化helper。 */
    private static void originalSerializerOracle(Path baseline, Path classes) throws Exception {
        Files.createDirectories(classes);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                "-cp", System.getProperty("java.class.path"), "-d", classes.toString(),
                baseline.resolve("TranscriptWindow.java").toString(), baseline.resolve("TranscriptStore.java").toString());
        check(compiled == 0, "frozen original sources did not compile");
        ArrayList<URL> urls = new ArrayList<>(); urls.add(classes.toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            urls.add(java.nio.file.Paths.get(entry).toUri().toURL());
        try (URLClassLoader original = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            String[] texts = {"null-fields", "汉🌌 <>&=\"\\\n\u2028\u2029\u0000", repeat("x", 8191) + "🌌", "bad-surrogate:\uD83D"};
            for (int i = 0; i < texts.length; i++) {
                JsonObject input = goldenSnapshot(texts[i], i % 2 == 0);
                String saved = originalSnapshot(original, input);
                TranscriptWindow current = TranscriptWindow.restore(input);
                check(Arrays.equals(saved.getBytes(StandardCharsets.UTF_8), streamed(current, true)), "new writer differs from frozen original serializer");
            }
            JsonObject legacy = goldenSnapshot("legacy-正文", false); legacy.addProperty("version", 1); legacy.remove("epoch"); legacy.remove("archive");
            String migrated = originalSnapshot(original, legacy);
            check(Arrays.equals(migrated.getBytes(StandardCharsets.UTF_8), streamed(TranscriptWindow.restore(JsonParser.parseString(migrated).getAsJsonObject()), false)), "original v1 migration not preserved as v2");
        }
        pass("frozen original serializer independent-loader byte oracle");
    }

    /** 只经原类恢复并序列化，不把新版snapshot作为预期结果。 */
    private static String originalSnapshot(ClassLoader original, JsonObject input) throws Exception {
        Class<?> parser = original.loadClass("com.google.gson.JsonParser"), json = original.loadClass("com.google.gson.JsonObject");
        Object element = parser.getMethod("parseString", String.class).invoke(null, input.toString());
        Object root = original.loadClass("com.google.gson.JsonElement").getMethod("getAsJsonObject").invoke(element);
        Class<?> model = original.loadClass("com.butang.codextop.TranscriptWindow");
        Object window = model.getMethod("restore", json).invoke(null, root);
        return model.getMethod("snapshot").invoke(window).toString();
    }

    /** 原格式手工数据覆盖固定epoch、null/空值、正负long与附件；禁止调用当前snapshot构造。 */
    private static JsonObject goldenSnapshot(String text, boolean archived) {
        JsonObject root = JsonParser.parseString("{\"version\":2,\"epoch\":\"00000000-0000-4000-8000-000000000001\",\"oldest\":1,\"cursor\":null,\"tailCursor\":\"\",\"hasMore\":true,\"loaded\":true,\"complete\":false,\"rows\":[],\"archive\":[]}").getAsJsonObject();
        DesktopAttachment full = new DesktopAttachment("图🌌", "image", "/synthetic/image", "image/png", Long.MAX_VALUE, repeat("b", 64), null, null);
        DesktopAttachment missing = new DesktopAttachment("缺失", "file", null, null, null, null, "unavailable", "unknown\n");
        JsonObject first = new JsonObject(); first.addProperty("number", 1);
        first.add("item", page("golden-one", text, null, Long.MIN_VALUE, full).getAsJsonArray("items").get(0));
        JsonObject second = new JsonObject(); second.addProperty("number", Integer.MAX_VALUE);
        second.add("item", page("golden-two", "", "local-two", Long.MAX_VALUE, missing).getAsJsonArray("items").get(0));
        root.getAsJsonArray("rows").add(first); root.getAsJsonArray("rows").add(second);
        if (archived) {
            JsonObject old = goldenSnapshot("archived", false); old.remove("archive");
            old.addProperty("epoch", "00000000-0000-4000-8000-000000000002"); old.addProperty("cursor", "before"); old.addProperty("tailCursor", (String) null);
            root.getAsJsonArray("archive").add(old);
        }
        return root;
    }

    /** 对照仍公开的原snapshot，写方不拥有缓存文件也不负责移动。 */
    private static void checkSameBytes(TranscriptWindow window, boolean splitCharacters) throws Exception {
        check(Arrays.equals(window.snapshot().toString().getBytes(StandardCharsets.UTF_8), streamed(window, splitCharacters)), "streamed bytes differ from canonical snapshot");
    }

    /** 使用真实UTF8编码器；可选单字符Writer只用于强制代理对跨写边界。 */
    private static byte[] streamed(TranscriptWindow window, boolean splitCharacters) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer encoded = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
        if (splitCharacters) encoded = new OneCharacterWriter(encoded);
        try (JsonWriter writer = new JsonWriter(encoded)) {
            writer.setSerializeNulls(false); writer.setHtmlSafe(true); writer.setIndent("  ");
            window.writeSnapshot(writer);
        }
        return bytes.toByteArray();
    }

    /** 测试边界只拆分写入调用，不替换JSON转义或UTF8编码。 */
    private static final class OneCharacterWriter extends Writer {
        private final Writer target;
        OneCharacterWriter(Writer target) { this.target = target; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            for (int index = offset; index < offset + length; index++) target.write(chars[index]);
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() throws IOException { target.close(); }
    }

    /** 合成来源经真实分页投影，保留附件路径描述而不创建或读取附件文件。 */
    private static TranscriptWindow window(String id, String text, String local, long time, DesktopAttachment attachment) throws Exception {
        TranscriptWindow window = new TranscriptWindow(); window.prepend(page(id, text, local, time, attachment)); return window;
    }

    /** 为断档归档提供完整可用页，所有来源和时间均为合成值。 */
    private static JsonObject page(String id, String text, String local, long time, DesktopAttachment attachment) {
        JsonObject content = new JsonObject(); content.addProperty("type", "text"); content.addProperty("text", text);
        JsonObject raw = new JsonObject(); raw.addProperty("role", local == null ? "agent" : "user"); raw.add("content", content);
        if (attachment != null) raw.add("meta", DesktopAttachment.meta(Collections.singletonList(attachment)));
        JsonObject item = new JsonObject(); item.addProperty("id", id); item.addProperty("localId", local); item.addProperty("createdAtMs", time); item.add("raw", raw);
        JsonArray items = new JsonArray(); items.add(item);
        JsonObject page = new JsonObject(); page.add("items", items); page.addProperty("hasMore", true);
        page.addProperty("historyAvailability", "available"); page.addProperty("nextCursor", "older-" + id); page.addProperty("tailCursor", "tail-" + id); return page;
    }

    private static TranscriptStore store(Path root, long limit) { return new TranscriptStore(root.toFile(), SERVER, ACCOUNT, MACHINE, limit); }
    private static Path target(Path root, String thread) { return root.resolve(TranscriptStore.digest(SERVER + "\n" + ACCOUNT + "\n" + MACHINE)).resolve(TranscriptStore.digest(thread) + ".json"); }
    private static void expectExportFailure(TranscriptStore store, String thread, TranscriptWindow window,Path destination) throws Exception {
        try { store.exportForRollback(thread, window,destination); throw new AssertionError("expected bounded/atomic write failure"); }
        catch (IOException expected) { }
    }
    private static void assertNoTemporary(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) { check(!files.anyMatch(path -> (path.getFileName().toString().endsWith(".tmp")||path.getFileName().toString().endsWith(".pending"))), "temporary file survived failure"); }
    }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void pass(String name) { scenarios++; System.out.println("PASS " + name); }
}
