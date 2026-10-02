package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;

/** 用合成分页验证缺锚点桥接；不读取真实对话，也不改原 recoverTail 的拒绝条件。 */
public final class TranscriptTailRecoveryTest {
    /** 覆盖跨轮续页、重叠后的原恢复、空页、坏游标、断档和身份失效。 */
    public static void main(String[] args) throws Exception {
        crossesRoundUntilAnchor();
        latestPageAlreadyAnchored();
        emptyProjectedPageStillReachesAnchor();
        rejectsBadCursorsAndDiscontinuity();
        identityChangeDropsBridge();
        existingTailUsesOriginalIncrement();
        System.out.println("TranscriptTailRecovery: 缺锚点跨轮桥接、原尾游标与身份失效通过");
    }

    /** 前四页没有 A，第五页才重叠；尾游标保持最初最新页，旧编号和旧游标不变。 */
    private static void crossesRoundUntilAnchor() throws Exception {
        TranscriptWindow window = cached("a", "older-a");
        var before = window.snapshot();
        int idA = window.before(0, 1).get(0).id;
        Object computer = new Object();
        TranscriptTailRecovery bridge = TranscriptTailRecovery.start(window, computer, 3);
        JsonObject first = page(item("b") + "," + item("c"), true, "c1", "live-tail");
        if (TranscriptTailRecovery.containsAnchor(window, first) || bridge.accept(first, null) != null)
            throw new AssertionError("最新页没有A就被合成");
        if (!bridge.continueNow() || !"c1".equals(bridge.resumeCursor()) || !before.equals(window.snapshot()))
            throw new AssertionError("第一页后没有在本轮继续或已改缓存");
        for (int n = 2; n <= 4; n++) {
            JsonObject older = page(item("f" + n), true, "c" + n, "middle-tail");
            if (bridge.accept(older, "c" + (n - 1)) != null || !before.equals(window.snapshot()))
                throw new AssertionError("未重叠A就生成了恢复页");
        }
        if (bridge.continueNow() || !"c4".equals(bridge.resumeCursor()) || bridge.retainedPages() != 4)
            throw new AssertionError("四页用尽后仍要求立刻续页或丢掉已到达游标");
        JsonObject anchor = page(item("a"), true, "c5", "other-tail");
        JsonObject ready = bridge.accept(anchor, "c4");
        if (ready == null || !"live-tail".equals(ready.get("tailCursor").getAsString())
                || ready.get("tailCursor").getAsString().equals("other-tail"))
            throw new AssertionError("合成页没有固定最初尾游标");
        String joined = ids(ready);
        if (!joined.equals("a,f4,f3,f2,b,c")) throw new AssertionError("合成顺序不是从旧锚点到最新页: " + joined);
        var added = window.recoverTail(ready);
        var rows = window.before(0, 10);
        if (added.size() != 5 || rows.size() != 6 || rows.get(0).message.id.equals("e")
                || !rows.get(0).message.id.equals("c") || rows.get(5).id != idA
                || !rows.get(5).message.id.equals("a") || !"older-a".equals(window.cursor)
                || !"live-tail".equals(window.tailCursor) || window.needsTailBootstrap())
            throw new AssertionError("重叠后旧A编号、旧游标或最初尾游标变化");
        for (int i = 1; i < rows.size(); i++)
            if (rows.get(i).id >= rows.get(i - 1).id) throw new AssertionError("新消息编号没有沿原顺序增长");
        bridge.release();
        if (bridge.retainedPages() != 0 || bridge.matches(computer, window, 3))
            throw new AssertionError("完成后仍保留桥接页");
        var during = page(item("e"), false, "after-live", null);
        var extra = window.append(during);
        if (extra.size() != 1 || !extra.get(0).message.id.equals("e") || !"after-live".equals(window.tailCursor)
                || window.before(0, 1).get(0).id <= rows.get(0).id)
            throw new AssertionError("桥接期间的新消息没有从固定尾游标增量补上");
    }

    /** 最新页本身已经含缓存末尾或同一用户回显时，直接走原 recoverTail，不另开桥接。 */
    private static void latestPageAlreadyAnchored() throws Exception {
        TranscriptWindow window = cached("a", "older-a");
        var overlapping = page(item("a") + "," + item("d"), false, null, "real-tail");
        if (!TranscriptTailRecovery.containsAnchor(window, overlapping))
            throw new AssertionError("最新页已含A仍被要求向旧桥接");
        int idA = window.before(0, 1).get(0).id;
        if (window.recoverTail(overlapping).size() != 1 || window.before(0, 1).get(0).message.id.equals("a")
                || window.before(0, 10).get(1).id != idA || !"real-tail".equals(window.tailCursor))
            throw new AssertionError("已含锚点的最新页没有走原恢复");
        TranscriptWindow echo = new TranscriptWindow();
        var local = page(item("local-source"), false, null, null);
        local.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("localId", "same-send");
        echo.prepend(local);
        echo = TranscriptWindow.restore(echo.snapshot());
        var desktop = page(item("desktop-source") + "," + item("answer"), false, null, "echo-tail");
        desktop.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("localId", "same-send");
        if (!TranscriptTailRecovery.containsAnchor(echo, desktop))
            throw new AssertionError("同一用户回显没有算作缓存末尾身份");
        int stable = echo.before(0, 1).get(0).id;
        if (echo.recoverTail(desktop).size() != 1 || echo.before(0, 10).get(1).id != stable)
            throw new AssertionError("回显锚点改变了旧编号");
    }

    /** 中间空投影页仍要前进，不能当成已经追平，也不能丢掉两侧正文。 */
    private static void emptyProjectedPageStillReachesAnchor() throws Exception {
        TranscriptWindow window = cached("a", "older-a");
        TranscriptWindow empty = new TranscriptWindow();
        if (TranscriptTailRecovery.required(empty) || !TranscriptTailRecovery.required(window))
            throw new AssertionError("空投影被桥接，或缺少尾游标的旧缓存没有进入桥接");
        TranscriptTailRecovery bridge = TranscriptTailRecovery.start(window, new Object(), 1);
        if (bridge.accept(page(item("b") + "," + item("c"), true, "c1", "live-tail"), null) != null)
            throw new AssertionError("空页之前的最新页被直接恢复");
        if (bridge.accept(page("", true, "c2", "empty-tail"), "c1") != null || !bridge.continueNow())
            throw new AssertionError("空投影页中断了桥接或被当成尾页");
        JsonObject ready = bridge.accept(page(item("a"), false, null, "older-tail"), "c2");
        var added = window.recoverTail(ready);
        if (added.size() != 2 || window.before(0, 10).size() != 3
                || !window.before(0, 10).get(2).message.id.equals("a")
                || !window.before(0, 10).get(0).message.id.equals("c")
                || !"live-tail".equals(window.tailCursor) || !"older-a".equals(window.cursor))
            throw new AssertionError("空投影跨页后顺序或尾游标错误");
        bridge.release();
    }

    /** 重复、循环、耗尽和来源断档都失败，旧缓存保持，坏游标不能留在内存里。 */
    private static void rejectsBadCursorsAndDiscontinuity() throws Exception {
        TranscriptWindow window = cached("a", "older-a");
        var before = window.snapshot();
        TranscriptTailRecovery stuck = TranscriptTailRecovery.start(window, new Object(), 1);
        if (stuck.accept(page(item("b"), true, "c1", "live-tail"), null) != null)
            throw new AssertionError("夹具未进入桥接");
        expect("历史游标无法继续", () -> stuck.accept(page(item("b"), true, "c1", "live-tail"), "c1"));
        if (stuck.retainedPages() != 0 || !before.equals(window.snapshot()) || window.tailCursor != null)
            throw new AssertionError("重复游标被保存或改写了旧缓存");
        TranscriptTailRecovery cycle = TranscriptTailRecovery.start(window, new Object(), 1);
        cycle.accept(page(item("b"), true, "c1", "live-tail"), null);
        cycle.accept(page(item("f"), true, "c2", "live-tail"), "c1");
        expect("历史游标无法继续", () -> cycle.accept(page(item("g"), true, "c1", "live-tail"), "c2"));
        if (cycle.retainedPages() != 0) throw new AssertionError("循环游标继续累积桥接页");
        TranscriptTailRecovery ended = TranscriptTailRecovery.start(window, new Object(), 1);
        ended.accept(page(item("b"), true, "c1", "live-tail"), null);
        expect("最新页缺少缓存连续锚点", () -> ended.accept(page(item("f"), false, null, "end-tail"), "c1"));
        if (!before.equals(window.snapshot()) || ended.retainedPages() != 0)
            throw new AssertionError("末页无锚点被当成追平");
        TranscriptTailRecovery broken = TranscriptTailRecovery.start(window, new Object(), 1);
        broken.accept(page(item("b"), true, "c1", "live-tail"), null);
        JsonObject discontinuity = page(item("a"), true, "c2", "live-tail");
        discontinuity.addProperty("truncationReason", "source_discontinuity");
        expect("消息来源出现断档", () -> broken.accept(discontinuity, "c1"));
        if (!before.equals(window.snapshot()) || broken.retainedPages() != 0 || window.tailCursor != null)
            throw new AssertionError("来源断档改写了旧缓存或保留了桥接页");
        TranscriptTailRecovery missingTail = TranscriptTailRecovery.start(window, new Object(), 1);
        expect("最新页缺少尾部游标", () -> missingTail.accept(page(item("b"), true, "c1", null), null));
        if (missingTail.retainedPages() != 0) throw new AssertionError("缺少尾游标仍保存了桥接页");
    }

    /** 连接、历史对象、代际、尾游标或旧游标变化后，这次桥接不能再用于别的会话。 */
    private static void identityChangeDropsBridge() throws Exception {
        TranscriptWindow window = cached("a", "older-a");
        Object computer = new Object();
        TranscriptTailRecovery bridge = TranscriptTailRecovery.start(window, computer, 9);
        bridge.accept(page(item("b"), true, "c1", "live-tail"), null);
        TranscriptWindow other = cached("a", "older-a");
        if (!bridge.matches(computer, window, 9)) throw new AssertionError("同一观察被判定失效");
        if (bridge.matches(new Object(), window, 9) || bridge.matches(computer, other, 9) || bridge.matches(computer, window, 10))
            throw new AssertionError("连接、历史或代际变化仍沿用桥接");
        window.tailCursor = "prefetched-tail";
        if (bridge.matches(computer, window, 9)) throw new AssertionError("预取推进尾游标后仍沿用桥接");
        window.tailCursor = null;
        window.cursor = "prefetched-older";
        if (bridge.matches(computer, window, 9)) throw new AssertionError("预取推进旧游标后仍沿用桥接");
        window.cursor = "older-a";
        if (!bridge.matches(computer, window, 9)) throw new AssertionError("恢复原游标后身份判断反了");
        bridge.release();
        if (bridge.retainedPages() != 0 || bridge.resumeCursor() != null || bridge.matches(computer, window, 9))
            throw new AssertionError("失效后仍保留桥接页或游标");
        if (TranscriptTailRecovery.required(window)) {
            var again = TranscriptTailRecovery.start(window, computer, 11);
            again.release();
        }
        TranscriptWindow live = cached("a", "older-a");
        live.recoverTail(page(item("a") + "," + item("d"), false, null, "already-tail"));
        if (TranscriptTailRecovery.required(live)) throw new AssertionError("已有尾游标仍进入桥接");
    }

    /** 尾游标已存在时只走原增量，不重新桥接最新整页。 */
    private static void existingTailUsesOriginalIncrement() throws Exception {
        TranscriptWindow live = cached("a", "older-a");
        live.recoverTail(page(item("a") + "," + item("d"), false, null, "already-tail"));
        int idA = 0;
        for (var row : live.before(0, 10)) if (row.message.id.equals("a")) idA = row.id;
        var added = live.append(page(item("d") + "," + item("e"), false, "after-tail", "ignored-tail"));
        if (TranscriptTailRecovery.required(live) || added.size() != 1 || !added.get(0).message.id.equals("e")
                || !"after-tail".equals(live.tailCursor) || !"older-a".equals(live.cursor))
            throw new AssertionError("已有尾游标没有走原增量");
        for (var row : live.before(0, 10))
            if (row.message.id.equals("a") && row.id != idA) throw new AssertionError("增量改变了旧编号");
        try {
            TranscriptTailRecovery.start(live, new Object(), 1);
            throw new AssertionError("已有尾游标仍能开始桥接");
        } catch (IOException expected) { }
    }

    /** 构造只有 A、没有尾游标的旧缓存。 */
    private static TranscriptWindow cached(String id, String older) throws Exception {
        TranscriptWindow window = new TranscriptWindow();
        window.prepend(page(item(id), true, older, null));
        return TranscriptWindow.restore(window.snapshot());
    }

    /** 读取合成页里的来源编号，保持从旧到新。 */
    private static String ids(JsonObject page) {
        ArrayList<String> ids = new ArrayList<>();
        for (var item : page.getAsJsonArray("items")) ids.add(item.getAsJsonObject().get("id").getAsString());
        return String.join(",", ids);
    }

    /** 断言原契约的失败文字，并确认异常没有被吞掉。 */
    private static void expect(String text, Throwing call) throws Exception {
        try {
            call.run();
            throw new AssertionError("缺少失败: " + text);
        } catch (IOException error) {
            if (error.getMessage() == null || !error.getMessage().contains(text)) throw error;
        }
    }

    /** 只用于期待失败的合成调用。 */
    private interface Throwing { void run() throws Exception; }

    /** 合成一页，不包含真实对话正文。 */
    private static String item(String id) {
        return "{\"id\":\"" + id + "\",\"createdAtMs\":1000,\"raw\":{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"sample\"}}}";
    }

    /** 合成页只带契约需要的游标和条目。 */
    private static JsonObject page(String items, boolean more, String cursor, String tail) {
        var page = JsonParser.parseString("{\"items\":[" + items + "],\"hasMore\":" + more
                + ",\"historyAvailability\":\"available\"}").getAsJsonObject();
        if (cursor != null) page.addProperty("nextCursor", cursor);
        if (tail != null) page.addProperty("tailCursor", tail);
        return page;
    }
}
