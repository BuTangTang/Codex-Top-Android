package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 连续段模型使用合成来源及临时目录，验证最新先交付而旧正文与读者不丢失。 */
public final class TranscriptSegmentsTest {
    private static int scenarios;

    /** 每项直接执行真实窗口及文件缓存，不替换分页、去重或归档算法。 */
    public static void main(String[] args) throws Exception {
        continuousLatestKeepsIdentity();
        gapsRetainFlatArchiveAndReaders();
        migratesV1AndReopensV2();
        rejectsPartialMalformedAndReorderedPages();
        emptyProjectionKeepsOldSegment();
        cachedBridgeReusesPrefixAndCursor();
        multipleGapsMergeOnlyWhenBridged();
        missingPrefixDoesNotDropArchive();
        attachmentsAndOutgoingIdentitySurvive();
        pruningRequiresContentAndOrder();
        capacityFailureIsAtomic();
        rejectsCorruptArchive();
        System.out.println("TranscriptSegments: scenarios=" + scenarios + " failures=0");
    }

    /** 连续最新页沿用原段、旧对象及编号，重复页不再添加消息。 */
    private static void continuousLatestKeepsIdentity() throws Exception {
        TranscriptWindow old = window("a", "b", "c");
        TranscriptWindow.Entry a = old.findSource("a", null);
        String epoch = old.epoch();
        TranscriptWindow latest = old.acceptLatest(page(false, null, "tail-2", "b", "c", "d"));
        check(latest == old && epoch.equals(latest.epoch()) && latest.findSource("a", null) == a, "continuous identity");
        check(ids(latest).equals("d,c,b,a") && "tail-2".equals(latest.tailCursor), "continuous append");
        var snapshot = latest.snapshot();
        check(latest.acceptLatest(page(false, null, "tail-2", "b", "c", "d")) == latest, "duplicate owner");
        check(snapshot.equals(latest.snapshot()), "duplicate latest changed cache");
        pass("continuous and duplicate latest");
    }

    /** 重复缺口只搬归档所有权，旧显示对象和重号消息仍属于不同段。 */
    private static void gapsRetainFlatArchiveAndReaders() throws Exception {
        TranscriptWindow first = window("a", "b");
        var firstRows = first.before(0, 10);
        TranscriptWindow second = first.acceptLatest(page(true, "before-c", "tail-d", "c", "d"));
        check(second != first && !second.epoch().equals(first.epoch()), "gap did not create epoch");
        check(second.findNumber(firstRows.get(0).id) != firstRows.get(0), "same number shared object");
        check(!second.findNumber(firstRows.get(0).id).epoch.equals(firstRows.get(0).epoch), "same number shared epoch");
        check(second.segment(first.epoch()) == first && second.containsSegment(first), "old reader was lost");
        TranscriptWindow third = second.acceptLatest(page(true, "before-e", "tail-f", "e", "f"));
        check(third.segment(first.epoch()) == first && third.segment(second.epoch()) == second, "older archive was lost");
        check(third.snapshot().getAsJsonArray("archive").size() == 2, "archive is not flat");
        for (var row : third.snapshot().getAsJsonArray("archive")) check(!row.getAsJsonObject().has("archive"), "nested archive");
        check(second.snapshot().getAsJsonArray("archive").size() == 0, "old owner retained archive chain");
        check(first.before(0, 10).get(0) == firstRows.get(0) && ids(first).equals("b,a"), "old view changed");
        check(third.findSegment("b", null) == first && third.findSegment("e", null) == third, "source resolver wrong owner");
        check(third.findSegment("missing", null) == null && third.segment("missing") == null && third.findNumber(7) == null, "missing lookup guessed");
        TranscriptWindow reopened = TranscriptWindow.restore(third.snapshot());
        check(!reopened.containsSegment(first) && reopened.containsSegment(reopened.segment(first.epoch())), "restored epoch accepted stale object");
        first.prepend(page(false, null, "ignored-old-tail", "older-a"));
        check(third.segment(first.epoch()).findSource("older-a", null) != null && ids(third).equals("f,e"), "bound old view update polluted latest");
        pass("flat repeated gaps, exact identity and old readers");
    }

    /** v1 的原字段完全保留，迁移后 v2 经真实文件写读保持各段身份和正文。 */
    private static void migratesV1AndReopensV2() throws Exception {
        TranscriptWindow original = window("a", "b");
        JsonObject legacy = original.snapshot();
        legacy.addProperty("version", 1); legacy.remove("epoch"); legacy.remove("archive");
        TranscriptWindow migrated = TranscriptWindow.restore(legacy);
        JsonObject migratedFields = migrated.snapshot();
        migratedFields.addProperty("version", 1); migratedFields.remove("epoch"); migratedFields.remove("archive");
        check(legacy.equals(migratedFields), "v1 fields changed");
        String oldEpoch = migrated.epoch();
        TranscriptWindow latest = migrated.acceptLatest(page(true, "before-e", "tail-f", "e", "f"));
        var directory = Files.createTempDirectory("transcript-segments-").toRealPath();
        try {
            TranscriptStore store = new TranscriptStore(directory.toFile(), "synthetic-server", "synthetic-account", "synthetic-machine");
            store.write("synthetic-thread", latest);
            TranscriptWindow reopened = store.read("synthetic-thread");
            check(reopened.snapshot().equals(latest.snapshot()), "v2 file roundtrip");
            check(reopened.epoch().equals(latest.epoch()) && ids(reopened.segment(oldEpoch)).equals("b,a"), "v1 epoch not persisted");
            check(reopened.segment(oldEpoch).before(0, 2).get(0).epoch.equals(oldEpoch), "restored Entry lost epoch");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path);
            }
        }
        pass("v1 migration and v2 persisted archive");
    }

    /** 坏页、预览页和已知倒序不能被解释成没有锚点，从而偷换新段。 */
    private static void rejectsPartialMalformedAndReorderedPages() throws Exception {
        TranscriptWindow old = window("a", "b", "c");
        JsonObject discontinuity = page(true, "fresh-old", "fresh-tail", "x");
        discontinuity.addProperty("truncated", true); discontinuity.addProperty("truncationReason", "source_discontinuity");
        rejectLatest(old, discontinuity);
        JsonObject unavailable = page(false, null, "preview-tail", "x"); unavailable.addProperty("historyAvailability", "preview_only");
        rejectLatest(old, unavailable);
        JsonObject partial = page(true, "more", "tail", "c", "d"); partial.addProperty("truncated", true); partial.addProperty("truncationReason", "page_limit");
        rejectLatest(old, partial);
        JsonObject missingTail = page(false, null, null, "x"); rejectLatest(old, missingTail);
        JsonObject malformed = page(false, null, "tail", "c", "d"); malformed.getAsJsonArray("items").get(1).getAsJsonObject().remove("id");
        rejectLatest(old, malformed);
        rejectLatest(old, page(false, null, "tail", "c", "b", "x"));
        rejectLatest(old, page(false, null, "tail", "b", "a", "x"));
        TranscriptWindow root = old.acceptLatest(page(true, "before-x", "tail-x", "x"));
        rejectLatest(root, page(false, null, "tail", "c", "a", "y"));
        var before = root.snapshot();
        try { root.prependWithCachedBridge(partial); throw new AssertionError("partial older page accepted"); } catch (IOException expected) { }
        check(before.equals(root.snapshot()), "partial page merged archive");
        pass("invalid pages are atomic and never create a gap");
    }

    /** 空投影保留合法分页进度与旧显示段，不借空白消息或假编号填补缺口。 */
    private static void emptyProjectionKeepsOldSegment() throws Exception {
        TranscriptWindow old = window("a", "b");
        TranscriptWindow latest = old.acceptLatest(page(true, "next-projection", "latest-tail"));
        check(latest != old && latest.before(0, 20).isEmpty() && latest.needsVisibleHistory(), "empty latest handling");
        check(latest.segment(old.epoch()) == old && ids(old).equals("b,a"), "empty latest erased old text");
        String epoch = latest.epoch();
        latest.prependWithCachedBridge(page(true, "older-c", "ignored-tail", "c"));
        check(ids(latest).equals("c") && epoch.equals(latest.epoch()) && "latest-tail".equals(latest.tailCursor), "visible older projection changed latest owner");
        TranscriptWindow empty = new TranscriptWindow();
        check(empty.acceptLatest(page(false, null, "empty-tail")) == empty && empty.snapshot().getAsJsonArray("archive").size() == 0, "empty initial cache created needless archive");
        pass("empty projection and stable new owner");
    }

    /** 实际命中缓存末项时保留最新编号，并复用已有旧前缀和原更旧游标。 */
    private static void cachedBridgeReusesPrefixAndCursor() throws Exception {
        TranscriptWindow old = new TranscriptWindow(); old.prepend(page(true, "before-a", "tail-c", "a", "b", "c"));
        var oldSnapshot = old.snapshot();
        TranscriptWindow latest = old.acceptLatest(page(true, "before-g", "tail-h", "g", "h"));
        var h = latest.findSource("h", null);
        latest.prependWithCachedBridge(page(true, "before-c", "ignored-tail", "c", "d", "e", "f"));
        check(ids(latest).equals("h,g,f,e,d,c,b,a"), "cached bridge lost or reordered prefix");
        check("before-a".equals(latest.cursor) && latest.hasMore && !latest.complete && "tail-h".equals(latest.tailCursor), "bridge did not reuse actual cursors");
        check(latest.findSource("h", null) == h && oldSnapshot.equals(old.snapshot()), "bridge changed live old or newest identity");
        check(latest.findSource("a", null).epoch.equals(latest.epoch()) && latest.findSource("a", null).id != old.findSource("a", null).id, "cross-gap ID was reused");
        check(latest.findSegment("a", null) == latest, "source resolver did not prefer current merged segment");
        latest.pruneMergedSegments(Collections.singleton(old.epoch()));
        check(latest.containsSegment(old), "pinned old reader was pruned");
        latest.pruneMergedSegments(Collections.emptySet());
        check(!latest.containsSegment(old) && ids(old).equals("c,b,a"), "merged unpinned archive not released or old reader mutated");
        pass("cached bridge, pinned prune and source priority");
    }

    /** 跨多个缺口逐段桥接，尚未接上的旧段即使没有显示引用也不能清理。 */
    private static void multipleGapsMergeOnlyWhenBridged() throws Exception {
        TranscriptWindow first = window("a", "b");
        TranscriptWindow middle = first.acceptLatest(page(true, "before-e", "tail-f", "e", "f"));
        TranscriptWindow latest = middle.acceptLatest(page(true, "before-i", "tail-j", "i", "j"));
        latest.pruneMergedSegments(Collections.emptySet());
        check(latest.containsSegment(first) && latest.containsSegment(middle), "unbridged archive erased");
        latest.prependWithCachedBridge(page(true, "before-f", "ignored", "f", "g", "h"));
        check(ids(latest).equals("j,i,h,g,f,e") && "before-e".equals(latest.cursor), "first bridge wrong range");
        latest.pruneMergedSegments(Collections.singleton(middle.epoch()));
        check(latest.containsSegment(first) && latest.containsSegment(middle), "first gap or pinned middle erased");
        latest.prependWithCachedBridge(page(true, "before-b", "ignored", "b", "c", "d"));
        check(ids(latest).equals("j,i,h,g,f,e,d,c,b,a") && latest.complete && !latest.hasMore && latest.cursor == null, "oldest completed cache not reused");
        latest.pruneMergedSegments(Collections.emptySet());
        check(latest.snapshot().getAsJsonArray("archive").size() == 0, "fully merged archives retained");
        pass("multiple gaps merge only with real bridges");
    }

    /** 仅包含归档末项而中间漏项的响应，不能借缓存拼成已确认连续的页。 */
    private static void missingPrefixDoesNotDropArchive() throws Exception {
        TranscriptWindow old = window("a", "b", "c");
        TranscriptWindow latest = old.acceptLatest(page(true, "before-g", "tail-g", "g"));
        latest.prependWithCachedBridge(page(true, "server-before-a", "ignored", "a", "c", "f"));
        check(ids(latest).equals("g,f,c,a") && latest.findSource("b", null) == null, "missing middle was synthesized");
        check("server-before-a".equals(latest.cursor), "unproven prefix used archived cursor");
        latest.pruneMergedSegments(Collections.emptySet());
        check(latest.containsSegment(old) && old.findSource("b", null) != null, "unmerged cache text removed");
        pass("partial overlap keeps gap and cache");
    }

    /** 多附件投影身份在归档、桥接和重开中不重号；助手不能按用户发送编号被吞掉。 */
    private static void attachmentsAndOutgoingIdentitySurvive() throws Exception {
        JsonObject upload = item("upload"); upload.addProperty("localId", "send-upload");
        upload.getAsJsonObject("raw").addProperty("role", "user");
        upload.getAsJsonObject("raw").add("meta", JsonParser.parseString("{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"a.png\",\"kind\":\"image\",\"path\":\"/synthetic/a.png\",\"sizeBytes\":12,\"mimeType\":\"image/png\"},{\"name\":\"b.txt\",\"kind\":\"file\",\"availability\":\"unavailable\",\"reason\":\"missing\"}]}}}"));
        JsonObject initial = page(false, null, "upload-tail"); initial.getAsJsonArray("items").add(upload);
        TranscriptWindow old = new TranscriptWindow(); old.prepend(initial);
        TranscriptWindow latest = old.acceptLatest(page(true, "before-answer", "answer-tail", "answer"));
        Set<String> expected = new HashSet<>(); expected.add("send-upload"); expected.add("send-upload:attachment:1");
        check(latest.allOutgoingLocalIds().equals(expected) && latest.findSource(null, "send-upload") == null, "outgoing IDs not scoped");
        check(latest.findSegment(null, "send-upload:attachment:1") == old, "attachment origin lookup lost");
        JsonObject echo = upload.deepCopy(); echo.addProperty("id", "echo-upload");
        JsonObject bridge = page(true, "before-upload", "ignored"); bridge.getAsJsonArray("items").add(echo);
        latest.prependWithCachedBridge(bridge);
        check(ids(latest).equals("answer,upload:attachment:1,upload") && latest.allOutgoingLocalIds().equals(expected), "attachment bridge duplicated projection");
        JsonObject assistant = item("assistant"); assistant.addProperty("localId", "send-upload");
        JsonObject increment = new JsonObject(); JsonArray messages = new JsonArray(); messages.add(assistant); increment.add("items", messages); increment.addProperty("nextCursor", "assistant-tail");
        check(latest.append(increment).size() == 1 && latest.findSource("assistant", null).message.outgoing == false, "assistant matched outgoing local ID");
        check(latest.findSource(null, "send-upload").message.outgoing, "local ID resolved assistant");
        var snapshot = latest.snapshot();
        check(TranscriptWindow.restore(snapshot).snapshot().equals(snapshot), "attachment v2 roundtrip");
        pass("attachment identities and outgoing reconciliation");
    }

    /** 同源但内容被改变，或同一来源序列倒置时，完整缓存仍不能被清除。 */
    private static void pruningRequiresContentAndOrder() throws Exception {
        TranscriptWindow old = window("a", "b");
        TranscriptWindow latest = old.acceptLatest(page(true, "before-x", "tail-x", "x"));
        JsonObject altered = page(false, null, "ignored", "a", "b");
        altered.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("raw").getAsJsonObject("content").addProperty("text", "changed");
        latest.prepend(altered);
        latest.pruneMergedSegments(Collections.emptySet());
        check(latest.containsSegment(old), "changed text erased original cache");
        TranscriptWindow reversed = old.acceptLatest(page(true, "before-y", "tail-y", "y"));
        reversed.prepend(page(false, null, "ignored", "b", "a"));
        reversed.pruneMergedSegments(Collections.emptySet());
        check(reversed.containsSegment(old), "reordered identity erased archive");
        pass("pruning preserves changed content and order");
    }

    /** 编号容量失败在发布前发生，不留下已经追加的半页。 */
    private static void capacityFailureIsAtomic() throws Exception {
        JsonObject saved = window("a").snapshot();
        saved.getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("number", Integer.MAX_VALUE - 1);
        TranscriptWindow old = TranscriptWindow.restore(saved);
        rejectLatest(old, page(false, null, "tail-new", "a", "b", "c"));
        pass("capacity failure is atomic");
    }

    /** 重复 epoch 和递归归档都是损坏缓存，不能恢复成身份歧义或重复正文。 */
    private static void rejectsCorruptArchive() throws Exception {
        TranscriptWindow old = window("a");
        JsonObject valid = old.acceptLatest(page(true, "before-b", "tail-b", "b")).snapshot();
        JsonObject duplicate = valid.deepCopy(); duplicate.getAsJsonArray("archive").get(0).getAsJsonObject().addProperty("epoch", duplicate.get("epoch").getAsString());
        try { TranscriptWindow.restore(duplicate); throw new AssertionError("duplicate epoch accepted"); } catch (IOException expected) { }
        JsonObject nested = valid.deepCopy(); nested.getAsJsonArray("archive").get(0).getAsJsonObject().add("archive", new JsonArray());
        try { TranscriptWindow.restore(nested); throw new AssertionError("nested archive accepted"); } catch (IOException expected) { }
        JsonObject badEpoch = valid.deepCopy(); badEpoch.addProperty("epoch", "not-an-epoch");
        try { TranscriptWindow.restore(badEpoch); throw new AssertionError("bad epoch accepted"); } catch (IOException expected) { }
        pass("invalid archived identity rejected");
    }

    /** 确认新入口失败既不清空正文，也不移动归档、游标和段身份。 */
    private static void rejectLatest(TranscriptWindow window, JsonObject page) throws Exception {
        var before = window.snapshot();
        try { window.acceptLatest(page); throw new AssertionError("invalid latest accepted"); } catch (IOException expected) { }
        check(before.equals(window.snapshot()), "failed latest mutated cache");
    }

    /** 生成一个有明确历史末尾的合成段。 */
    private static TranscriptWindow window(String... sources) throws Exception {
        TranscriptWindow window = new TranscriptWindow(); window.prepend(page(false, null, "tail-initial", sources)); return window;
    }

    /** 页面使用正式契约，hasMore 是正常分页，不冒充截断或来源未知。 */
    private static JsonObject page(boolean more, String cursor, String tail, String... sources) {
        JsonObject result = new JsonObject(); JsonArray rows = new JsonArray();
        for (String id : sources) rows.add(item(id));
        result.add("items", rows); result.addProperty("hasMore", more); result.addProperty("historyAvailability", "available");
        if (cursor != null) result.addProperty("nextCursor", cursor);
        if (tail != null) result.addProperty("tailCursor", tail);
        return result;
    }

    /** 来源身份和正文都是合成值，故意不按时间戳排序以防引入时间猜测。 */
    private static JsonObject item(String id) {
        JsonObject result = new JsonObject(), raw = new JsonObject(), content = new JsonObject();
        result.addProperty("id", id); result.addProperty("createdAtMs", 1000L);
        raw.addProperty("role", "agent"); content.addProperty("type", "text"); content.addProperty("text", "synthetic-" + id);
        raw.add("content", content); result.add("raw", raw); return result;
    }

    /** 按用户实际看到的新到旧顺序比较真实来源身份。 */
    private static String ids(TranscriptWindow window) throws IOException {
        java.util.StringJoiner result = new java.util.StringJoiner(",");
        for (TranscriptWindow.Entry entry : window.before(0, 1000)) result.add(entry.message.id);
        return result.toString();
    }

    /** 断言失败立即结束，避免失败后继续修改夹具掩盖边界。 */
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** 输出独立场景结果，不输出真实正文或环境状态。 */
    private static void pass(String label) { scenarios++; System.out.println("PASS " + label); }
}
