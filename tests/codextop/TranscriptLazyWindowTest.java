package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 使用真实 Window 和原 TranscriptText 投影，验证完整索引与按页正文分离。 */
public final class TranscriptLazyWindowTest {
    private static int scenarios;

    /** 每项只用合成消息，计数 Content.read 不替换窗口的分页、合并或身份算法。 */
    public static void main(String[] args) throws Exception {
        recentWindowOnlyReadsRequestedBodies();
        queriesPreserveIdsAndLiveEntryIdentity();
        bridgeCopiesReferencesWithoutReadingBodies();
        incompleteBridgeDoesNotInventContinuity();
        pruneDoesNotReadDifferentStoredBodies();
        appendAndLatestUseOnlyIdentityIndex();
        aliasesAndUserOnlyEchoIdentitySurvive();
        badBodiesThrowWithoutEmptyOrEndResult();
        indexedCorruptionIsRejectedBeforeReading();
        indexedV2ExportIsExplicitlyFullAndEquivalent();
        committedBindingReleasesResidentAndPreservesReader();
        System.out.println("TranscriptLazyWindow: scenarios=" + scenarios + " failures=0");
    }

    /** 2000 行冷索引完整可定位，目录和待发核对无需正文，最近 30 行仅读取 30 行。 */
    private static void recentWindowOnlyReadsRequestedBodies() throws Exception {
        TranscriptWindow original = window(2000);
        Lazy lazy = lazy(original);
        TranscriptWindow restored = lazy.window;
        check(lazy.reads == 0 && restored.size() == 2000 && !restored.isEmpty(), "restore eagerly read body or truncated index");
        check(restored.complete && !restored.hasMore && restored.loaded, "index lost authoritative terminal");
        check(restored.latestNumber() == original.latestNumber(), "latest number changed");
        check(restored.sourceNumber("r0", null) != null && restored.findSegment("r0", null) == restored, "oldest source not indexed");
        check(restored.localSegments().get(0).count == 2000 && restored.allOutgoingLocalIds().size() == 200, "catalog or outgoing lost older rows");
        check(restored.exportIndexedSegments().get(0).rows.size() == 2000 && lazy.reads == 0, "metadata access loaded body");
        ArrayList<TranscriptWindow.Entry> page = restored.before(0, 30);
        check(page.size() == 30 && lazy.reads == 30, "recent page must read exactly 30 bodies");
        check(page.get(0).message.id.equals("r1999") && page.get(29).message.id.equals("r1970"), "recent order changed");
        for (TranscriptWindow.Entry row : page) check(row.message != null, "null Entry.message escaped");
        check(restored.before(page.get(29).id, 31).size() == 31 && lazy.reads == 61, "old local page did not read full logical index");
        check(restored.exportIndexedSegments().get(0).rows.get(1999).body.resident() == null, "lazy read became permanent body cache");
        pass("2000 full identities, metadata zero reads, exact recent page cost");
    }

    /** 各真实正文查询保持原排序和编号；仍被调用方持有的 Entry 对象不变。 */
    private static void queriesPreserveIdsAndLiveEntryIdentity() throws Exception {
        TranscriptWindow original = window(12);
        Lazy lazy = lazy(original);
        int pivot = original.sourceNumber("r5", null);
        ArrayList<TranscriptWindow.Entry> around = lazy.window.around(pivot, 5);
        check(signature(around).equals(signature(original.around(pivot, 5))), "around sequence changed");
        check(lazy.reads == 5, "around read outside requested page");
        TranscriptWindow.Entry held = lazy.window.findNumber(pivot);
        check(held == lazy.window.findSource("r5", null), "live Entry identity changed");
        check(signature(lazy.window.after(pivot, 3)).equals(signature(original.after(pivot, 3))), "after sequence changed");
        check(lazy.window.findNumber(-17) == null && lazy.window.findSource("absent", null) == null, "missing source guessed");
        pass("before/after/around/find preserve order and live identity");
    }

    /** 已证连续后缀桥接全段时只重编号共享 Body，保留 pin 时不删，释放后无需解码即可删。 */
    private static void bridgeCopiesReferencesWithoutReadingBodies() throws Exception {
        TranscriptWindow old = window(2000);
        String oldEpoch = old.epoch();
        TranscriptWindow current = old.acceptLatest(page(true, "gap", "new-tail", item("new-a", false, null, 3001), item("new-b", false, null, 3002)));
        Lazy lazy = lazy(current);
        TranscriptWindow root = lazy.window, archived = root.segment(oldEpoch);
        check(root.hasMissingCachedTailAnchor() && root.hasUnbridgedCachedHistory() && lazy.reads == 0, "bridge eligibility decoded cache");
        TranscriptWindow.Body oldFirst = archived.exportIndexedSegments().get(0).rows.get(0).body;
        root.prependWithCachedBridge(page(true, "next-gap", "ignored", item("r1998", false, null, 1998), item("r1999", false, null, 1999), item("new-a", false, null, 3001)));
        check(root.size() == 2002 && !root.hasMore && root.complete && root.cursor == null, "bridge lost prefix/cursor/terminal");
        check(lazy.reads == 0 && root.exportIndexedSegments().get(0).rows.get(0).body == oldFirst, "bridge hydrated or copied cached bodies");
        check(!root.pruneMergedSegments(Collections.singleton(oldEpoch)) && root.containsSegment(archived), "pinned archive pruned");
        check(root.pruneMergedSegments(Collections.emptySet()) && root.segment(oldEpoch) == null && lazy.reads == 0, "proven shared archive not pruned without reads");
        check(root.allOutgoingLocalIds().size() == 200, "bridge lost original outgoing identity set");
        pass("2000-row bridge and prune perform zero body reads");
    }

    /** 仅命中旧段内部、不抵达真实末项或跳过中间项时，仍沿原分页保留缺口。 */
    private static void incompleteBridgeDoesNotInventContinuity() throws Exception {
        TranscriptWindow old = window(6); String epoch = old.epoch();
        TranscriptWindow latest = old.acceptLatest(page(true, "gap", "tail", item("new", false, null, 20)));
        Lazy lazy = lazy(latest);
        lazy.window.prependWithCachedBridge(page(true, "gap-2", "ignored", item("r2", false, null, 2), item("r4", false, null, 4)));
        check(lazy.window.size() == 3 && lazy.window.segment(epoch).size() == 6 && lazy.window.hasMore, "partial suffix fabricated full bridge");
        check(lazy.reads == 0 && lazy.window.hasMissingCachedTailAnchor(), "missing tail lost or body read");
        check(!lazy.window.pruneMergedSegments(Collections.emptySet()), "incomplete archive pruned");
        pass("unproved suffix retains missing interval and archive");
    }

    /** 不同持久 Body 即使身份相同，也不为清理主动读取全文比较。 */
    private static void pruneDoesNotReadDifferentStoredBodies() throws Exception {
        TranscriptWindow old = window(3); String epoch = old.epoch();
        TranscriptWindow latest = old.acceptLatest(page(true, "gap", "tail", item("new", false, null, 20)));
        latest.prepend(page(false, null, "ignored", item("r0", true, "l0", 0), item("r1", false, null, 1), item("r2", false, null, 2)));
        Lazy lazy = lazy(latest);
        check(!lazy.window.pruneMergedSegments(Collections.emptySet()) && lazy.window.segment(epoch) != null, "unknown stored equality pruned");
        check(lazy.reads == 0, "prune decoded independently stored bodies");
        pass("unknown stored equality conservatively retains archive");
    }

    /** 小量尾增量及缺锚切段只查全量身份索引，不读取既存正文。 */
    private static void appendAndLatestUseOnlyIdentityIndex() throws Exception {
        Lazy lazy = lazy(window(2000)); TranscriptWindow root = lazy.window;
        JsonObject delta = page(false, null, "next-tail", item("r1999", false, null, 1999), item("new-a", false, null, 2001));
        delta.addProperty("nextCursor", "next-tail");
        check(root.append(delta).size() == 1 && root.size() == 2001 && lazy.reads == 0, "incremental append decoded old bodies");
        check(root.acceptLatest(page(false, null, "tail-b", item("new-a", false, null, 2001), item("new-b", false, null, 2002))) == root, "valid last anchor replaced root");
        check(lazy.reads == 0 && root.size() == 2002, "latest anchor validation read old body");
        TranscriptWindow newer = root.acceptLatest(page(true, "gap", "tail-z", item("z", false, null, 3000)));
        check(newer != root && newer.containsSegment(root) && root.size() == 2002 && lazy.reads == 0, "missing anchor failed to preserve full root");
        pass("append/latest retain source identity without old body reads");
    }

    /** 用户 local 别名在索引往返后仍去重，助手同名字段不吞回答。 */
    private static void aliasesAndUserOnlyEchoIdentitySurvive() throws Exception {
        TranscriptWindow window = new TranscriptWindow();
        window.prepend(page(false, null, "tail", item("u", true, "shared", 1), item("a", false, "shared", 2)));
        JsonObject echo = page(false, null, "tail2", item("u-echo", true, "shared", 1)); echo.addProperty("nextCursor", "tail2");
        check(window.append(echo).isEmpty(), "original user alias not deduplicated");
        Lazy lazy = lazy(window);
        check(lazy.window.size() == 2 && lazy.window.sourceNumber("u-echo", null).equals(lazy.window.sourceNumber("u", null)), "source alias lost on indexed restore");
        check(!lazy.window.sourceNumber("a", null).equals(lazy.window.sourceNumber("u", null)) && lazy.reads == 0, "assistant local swallowed or read");
        check(lazy.window.findSource("u-echo", null).message.id.equals("u"), "alias replaced canonical body");
        pass("full source aliases and outgoing-only identity retained");
    }

    /** 坏块、丢块和身份不符均抛 IOException，索引、游标与结束事实不变。 */
    private static void badBodiesThrowWithoutEmptyOrEndResult() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            TranscriptWindow original = window(4); List<TranscriptWindow.IndexedSegment> index = original.exportIndexedSegments();
            TranscriptWindow.IndexedSegment s = index.get(0); ArrayList<TranscriptWindow.RowRef> rows = new ArrayList<>(s.rows);
            TranscriptWindow.RowRef last = rows.get(3); final int mode = kind;
            TranscriptWindow.Content broken = () -> { if (mode == 0) throw new IOException("synthetic missing block"); if (mode == 1) return null; return text(item("wrong", false, null, 3)); };
            rows.set(3, new TranscriptWindow.RowRef(last.id, last.sourceId, last.localId, last.outgoing, last.createdAtMs, new TranscriptWindow.Body(broken)));
            TranscriptWindow restored = TranscriptWindow.restoreIndexed(Collections.singletonList(segment(s, rows, s.sourceNumbers)));
            expectIo(() -> restored.before(0, 30)); expectIo(() -> restored.findNumber(last.id));
            check(restored.size() == 4 && restored.complete && restored.loaded && !restored.hasMore && restored.latestNumber() == last.id, "failed body changed metadata/end");
        }
        pass("body failures propagate without false empty or terminal mutation");
    }

    /** 索引自身结构在正文加载前严格拒绝，不把坏归档或悬空别名交给 UI。 */
    private static void indexedCorruptionIsRejectedBeforeReading() throws Exception {
        Lazy lazy = lazy(window(4)); TranscriptWindow.IndexedSegment s = lazy.window.exportIndexedSegments().get(0);
        expectIo(() -> TranscriptWindow.restoreIndexed(java.util.Arrays.asList(s, s)));
        ArrayList<TranscriptWindow.RowRef> rows = new ArrayList<>(s.rows); rows.add(rows.get(0));
        expectIo(() -> TranscriptWindow.restoreIndexed(Collections.singletonList(segment(s, rows, s.sourceNumbers))));
        Map<String,Integer> dangling = new HashMap<>(s.sourceNumbers); dangling.put("dangling", 7);
        expectIo(() -> TranscriptWindow.restoreIndexed(Collections.singletonList(segment(s, s.rows, dangling))));
        Map<String,Integer> aliases = new HashMap<>(s.sourceNumbers); aliases.put("r0", s.rows.get(1).id);
        Map<String,Integer> conflict = aliases;
        expectIo(() -> TranscriptWindow.restoreIndexed(Collections.singletonList(segment(s, s.rows, conflict))));
        check(lazy.reads == 0, "index validation decoded bodies");
        pass("invalid indexes fail before body reads");
    }

    /** 旧 v2 兼容导出显式读取全量正文，保留附件、原编号和所有段字段。 */
    private static void indexedV2ExportIsExplicitlyFullAndEquivalent() throws Exception {
        TranscriptWindow old = window(40);
        TranscriptWindow root = old.acceptLatest(page(true, "gap", "tail", item("new", false, null, 100)));
        JsonObject original = root.snapshot(); Lazy lazy = lazy(root);
        check(lazy.window.snapshot().equals(original) && lazy.reads == 41, "full v2 export differs or hides lazy IO");
        check(TranscriptWindow.restore(lazy.window.snapshot()).snapshot().equals(original), "v2 import/export changed");
        pass("explicit full v2 export and import remain equivalent");
    }

    /** 只有持久提交完成才替换共享 Body；已交付 Entry 持有完整正文不失效。 */
    private static void committedBindingReleasesResidentAndPreservesReader() throws Exception {
        TranscriptWindow window = window(2); TranscriptWindow.Entry held = window.findSource("r1", null);
        TranscriptWindow.Body body = window.exportIndexedSegments().get(0).rows.get(1).body;
        String encoded = TranscriptWindow.bodySnapshot(body.resident()).toString();
        int[] reads = {0}; TranscriptWindow.Content stored = () -> { reads[0]++; return decode(encoded); };
        check(body.resident() != null && body.content() == null, "uncommitted memory row lost");
        body.bindPersisted(stored);
        check(body.resident() == null && body.content() == stored && window.findSource("r1", null) == held, "commit broke live Entry");
        TranscriptWindow restored = TranscriptWindow.restoreIndexed(window.exportIndexedSegments());
        check(restored.findSource("r1", null).message.text.equals(held.message.text) && reads[0] == 1, "committed body not used on new view");
        pass("successful body binding releases resident but preserves delivered Entry");
    }

    /** 用原 item 序列化与真实 TranscriptText.read 执行惰性读取，body 来源唯一且可计数。 */
    private static final class Lazy {
        int reads;
        TranscriptWindow window;
    }
    private static Lazy lazy(TranscriptWindow source) throws Exception {
        Lazy result = new Lazy(); ArrayList<TranscriptWindow.IndexedSegment> index = new ArrayList<>();
        IdentityHashMap<TranscriptWindow.Body,TranscriptWindow.Body> bodies = new IdentityHashMap<>();
        for (TranscriptWindow.IndexedSegment s : source.exportIndexedSegments()) {
            ArrayList<TranscriptWindow.RowRef> rows = new ArrayList<>();
            for (TranscriptWindow.RowRef row : s.rows) {
                TranscriptWindow.Body body = bodies.get(row.body);
                if (body == null) {
                    String encoded = TranscriptWindow.bodySnapshot(row.body.resident()).toString();
                    body = new TranscriptWindow.Body((TranscriptWindow.Content) () -> { result.reads++; return decode(encoded); });
                    bodies.put(row.body, body);
                }
                rows.add(new TranscriptWindow.RowRef(row.id, row.sourceId, row.localId, row.outgoing, row.createdAtMs, body));
            }
            index.add(segment(s, rows, s.sourceNumbers));
        }
        result.window = TranscriptWindow.restoreIndexed(index); return result;
    }
    /** 构造同一段头，便于仅替换测试所需索引引用。 */
    private static TranscriptWindow.IndexedSegment segment(TranscriptWindow.IndexedSegment s, List<TranscriptWindow.RowRef> rows, Map<String,Integer> aliases) {
        return new TranscriptWindow.IndexedSegment(s.epoch, s.oldest, s.cursor, s.tailCursor, s.hasMore, s.loaded, s.complete, rows, aliases);
    }
    /** 合成完整来源，不读取真实会话。 */
    private static TranscriptWindow window(int count) throws Exception {
        JsonObject[] items = new JsonObject[count];
        for (int i=0;i<count;i++) items[i]=item("r"+i, i%10==0, i%10==0?"l"+i:null, i);
        TranscriptWindow result=new TranscriptWindow();result.prepend(page(false,null,"tail",items));return result;
    }
    /** 稀疏附件使用原元数据封套，不把附件字节塞进历史。 */
    private static JsonObject item(String id, boolean outgoing, String local, long time) {
        JsonObject value=new JsonObject();value.addProperty("id",id);value.addProperty("localId",local);value.addProperty("createdAtMs",time);
        JsonObject raw=new JsonObject();raw.addProperty("role",outgoing?"user":"agent");JsonObject content=new JsonObject();content.addProperty("type","text");content.addProperty("text","synthetic "+id+" 文本 😀");raw.add("content",content);
        if (time%97==0) raw.add("meta",DesktopAttachment.meta(Collections.singletonList(new DesktopAttachment("synthetic.png","image","/synthetic/image.png","image/png",17L,null,null,null))));
        value.add("raw",raw);return value;
    }
    /** 页封套使用真实已确认游标与可用性契约。 */
    private static JsonObject page(boolean more,String cursor,String tail,JsonObject... items) {
        JsonObject p=new JsonObject();JsonArray a=new JsonArray();for(JsonObject item:items)a.add(item);p.add("items",a);p.addProperty("hasMore",more);p.addProperty("nextCursor",cursor);p.addProperty("tailCursor",tail);p.addProperty("historyAvailability","available");p.addProperty("truncated",false);return p;
    }
    /** 原投影必须精确生成一项，避免理想化的正文替身。 */
    private static TranscriptText text(JsonObject item) throws IOException {JsonArray a=new JsonArray();a.add(item);ArrayList<TranscriptText> parsed=TranscriptText.read(a);if(parsed.size()!=1)throw new IOException("synthetic projection count");return parsed.get(0);}
    private static TranscriptText decode(String encoded) throws IOException {return text(JsonParser.parseString(encoded).getAsJsonObject());}
    private static String signature(List<TranscriptWindow.Entry> rows) {StringBuilder s=new StringBuilder();for(TranscriptWindow.Entry row:rows)s.append(row.id).append(':').append(row.message.id).append(';');return s.toString();}
    private interface IoAction {void run() throws Exception;}
    private static void expectIo(IoAction action) throws Exception {try{action.run();throw new AssertionError("expected IOException");}catch(IOException expected){}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void pass(String label){scenarios++;System.out.println("PASS "+label);}
}
