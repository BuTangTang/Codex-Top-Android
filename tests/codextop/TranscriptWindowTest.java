package com.butang.codextop;
import com.google.gson.JsonParser;
import java.io.IOException;

/** 用合成重叠分页验证原消息编号顺序和真实结束条件。 */
public final class TranscriptWindowTest {
    /** 验证重复项不产生气泡、旧页保持编号关系、无游标不能伪造历史结束。 */
    public static void main(String[] args) throws Exception {
        TranscriptWindow window = new TranscriptWindow();
        window.prepend(JsonParser.parseString("{\"items\":[" + item("b") + "," + item("c") + "],\"hasMore\":true,\"nextCursor\":\"older\"}").getAsJsonObject());
        int c = window.before(0, 10).get(0).id;
        int b = window.before(0, 10).get(1).id;
        window.prepend(JsonParser.parseString("{\"items\":[" + item("a") + "," + item("b") + "],\"hasMore\":false,\"historyAvailability\":\"available\"}").getAsJsonObject());
        if (window.before(0, 10).size() != 3 || window.before(0, 10).get(0).id != c || !window.complete)
            throw new AssertionError("重叠页改变已有身份或重复消息");
        if (!window.before(b, 10).get(0).message.id.equals("a")) throw new AssertionError("旧消息排序错误");
        var delta = JsonParser.parseString("{\"items\":[" + item("c") + "," + item("d") + "],\"nextCursor\":\"tail2\",\"truncated\":false}").getAsJsonObject();
        delta.add("truncationReason", com.google.gson.JsonNull.INSTANCE);
        var added = window.append(delta);
        if (added.size() != 1 || added.get(0).id <= c || !"tail2".equals(window.tailCursor))
            throw new AssertionError("增量顺序或尾部游标错误");
        if (!window.append(delta).isEmpty()) throw new AssertionError("重复增量产生重复气泡");
        var newer = window.after(b, 10);
        if (newer.size() != 2 || !newer.get(0).message.id.equals("d") || !newer.get(1).message.id.equals("c"))
            throw new AssertionError("向新翻页必须只返回锚点之后的消息");
        if (!window.after(newer.get(0).id, 10).isEmpty())
            throw new AssertionError("到达最新消息仍重复交付旧页");
        var nextOne = window.after(b, 1);
        if (nextOne.size() != 1 || !nextOne.get(0).message.id.equals("c")
                || !window.after(nextOne.get(0).id, 1).get(0).message.id.equals("d"))
            throw new AssertionError("限制页大小时跳过了紧邻消息");
        // 旧页在网络等待期间尾部已有新消息；旧响应不能回退尾部或改变新消息编号。
        TranscriptWindow interleaved = new TranscriptWindow();
        var initial = JsonParser.parseString("{\"items\":[" + item("b") + "," + item("c")
                + "],\"hasMore\":true,\"nextCursor\":\"older\",\"tailCursor\":\"tail1\"}").getAsJsonObject();
        interleaved.prepend(initial);
        var newest = interleaved.append(delta).get(0);
        interleaved.prepend(JsonParser.parseString("{\"items\":[" + item("a") + "," + item("b")
                + "],\"hasMore\":false,\"historyAvailability\":\"available\",\"tailCursor\":\"stale-tail\"}").getAsJsonObject());
        if (!"tail2".equals(interleaved.tailCursor) || interleaved.before(0, 10).get(0).id != newest.id
                || interleaved.before(0, 10).size() != 4 || !interleaved.complete)
            throw new AssertionError("旧页迟到破坏尾部游标、新消息编号或去重");
        TranscriptWindow reopened = TranscriptWindow.restore(interleaved.snapshot());
        if (!"tail2".equals(reopened.tailCursor) || reopened.before(0, 10).get(0).id != newest.id)
            throw new AssertionError("交错分页保存重开后身份变化");
        // 只有服务端确认还有后页且进度变化才快速续页，包括过滤后只有工具的空文字页。
        var backlog = JsonParser.parseString("{\"items\":[],\"nextCursor\":\"next\",\"truncated\":true,\"truncationReason\":\"page_limit\"}").getAsJsonObject();
        if (!TranscriptWindow.hasPendingTail(backlog, "previous")) throw new AssertionError("遗漏明确积压页");
        if (TranscriptWindow.hasPendingTail(backlog, "next")) throw new AssertionError("游标不前进仍加速");
        backlog.addProperty("truncationReason", "source_discontinuity");
        if (TranscriptWindow.hasPendingTail(backlog, "previous")) throw new AssertionError("来源断档仍加速");
        backlog.addProperty("truncationReason", "page_limit");
        backlog.addProperty("truncated", false);
        if (TranscriptWindow.hasPendingTail(backlog, "previous")) throw new AssertionError("空闲轮询被加速");
        backlog.addProperty("truncated", true);
        backlog.remove("nextCursor");
        if (TranscriptWindow.hasPendingTail(backlog, "previous") || TranscriptWindow.hasPendingTail(null, "previous"))
            throw new AssertionError("无效响应仍加速");
        TranscriptWindow unknown = new TranscriptWindow();
        unknown.prepend(JsonParser.parseString("{\"items\":[],\"hasMore\":false}").getAsJsonObject());
        if (unknown.complete) throw new AssertionError("未知来源被标记完整");
        try {
            new TranscriptWindow().prepend(JsonParser.parseString("{\"items\":[],\"hasMore\":true}").getAsJsonObject());
            throw new AssertionError("接受了缺失游标");
        } catch (IOException expected) { }
        System.out.println("TranscriptWindow: 重叠去重、旧页顺序、完整性与游标校验通过");
    }
    /** 创建不含真实对话内容的最小文字消息。 */
    private static String item(String id) {
        return "{\"id\":\"" + id + "\",\"createdAtMs\":1000,\"raw\":{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"sample\"}}}";
    }
}
