package com.butang.codextop;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

/** 将桌面游标分页映射为原聊天列表的有序消息编号；仅在同一后台队列访问。 */
public final class TranscriptWindow {
    public static final class Entry {
        public final int id;
        public final TranscriptText message;
        /** 编号只服务本地列表，源消息身份仍完整保留用于去重。 */
        Entry(int id, TranscriptText message) { this.id = id; this.message = message; }
    }
    private final TreeMap<Integer, Entry> entries = new TreeMap<>();
    private final HashMap<String, Integer> sourceIds = new HashMap<>();
    private final HashMap<String, Integer> localIds = new HashMap<>();
    private int oldest = 1_000_000_000;
    public String cursor;
    public String tailCursor;
    public boolean hasMore = true;
    public boolean loaded;
    public boolean complete;

    /** 本地快照保留消息编号及双向游标，重开不重新编号。 */
    public JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("oldest", oldest);
        root.addProperty("cursor", cursor);
        root.addProperty("tailCursor", tailCursor);
        root.addProperty("hasMore", hasMore);
        root.addProperty("loaded", loaded);
        root.addProperty("complete", complete);
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("number", entry.id);
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.message.id);
            item.addProperty("localId", entry.message.localId);
            item.addProperty("createdAtMs", entry.message.createdAtMs);
            JsonObject raw = new JsonObject();
            raw.addProperty("role", entry.message.outgoing ? "user" : "agent");
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", entry.message.text);
            raw.add("content", content);
            if (!entry.message.attachments.isEmpty()) raw.add("meta", DesktopAttachment.meta(entry.message.attachments));
            item.add("raw", raw);
            row.add("item", item);
            rows.add(row);
        }
        root.add("rows", rows);
        return root;
    }

    /** 损坏缓存不交付半份数据；调用方可保留文件并重新读取来源。 */
    public static TranscriptWindow restore(JsonObject root) throws IOException {
        try {
            if (root.get("version").getAsInt() != 1) throw new IOException("缓存版本不支持");
            TranscriptWindow window = new TranscriptWindow();
            window.oldest = root.get("oldest").getAsInt();
            window.cursor = root.get("cursor").isJsonNull() ? null : root.get("cursor").getAsString();
            window.tailCursor = root.get("tailCursor").isJsonNull() ? null : root.get("tailCursor").getAsString();
            window.hasMore = root.get("hasMore").getAsBoolean();
            window.loaded = root.get("loaded").getAsBoolean();
            window.complete = root.get("complete").getAsBoolean();
            for (com.google.gson.JsonElement value : root.getAsJsonArray("rows")) {
                JsonObject row = value.getAsJsonObject();
                int number = row.get("number").getAsInt();
                com.google.gson.JsonArray items = new com.google.gson.JsonArray();
                items.add(row.get("item"));
                ArrayList<TranscriptText> parsed = TranscriptText.read(items);
                if (number < window.oldest || parsed.size() != 1 || window.entries.containsKey(number)
                        || window.sourceIds.containsKey(parsed.get(0).id)) throw new IOException("缓存消息身份无效");
                TranscriptText text = parsed.get(0);
                if (window.containsMessage(text)) continue;
                window.entries.put(number, new Entry(number, text));
                window.remember(text, number);
            }
            return window;
        } catch (RuntimeException error) { throw new IOException("缓存格式无效", error); }
    }

    /** 接收从旧到新排列的上一页；过滤工具后仍沿真实游标继续，不按条数判断结束。 */
    public void prepend(JsonObject page) throws IOException {
        if (!page.has("items") || !page.get("items").isJsonArray() || !page.has("hasMore"))
            throw new IOException("历史消息格式无效");
        boolean more = page.get("hasMore").getAsBoolean();
        String next = page.has("nextCursor") && !page.get("nextCursor").isJsonNull()
                ? page.get("nextCursor").getAsString() : null;
        if (more && (next == null || next.isEmpty() || next.equals(cursor)))
            throw new IOException("历史游标无法继续");
        ArrayList<TranscriptText> fresh = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        java.util.HashSet<String> seenLocal = new java.util.HashSet<>();
        for (TranscriptText text : TranscriptText.read(page.getAsJsonArray("items"))) {
            if (containsMessage(text) || !seen.add(text.id)) continue;
            if (hasLocalIdentity(text) && !seenLocal.add(text.localId)) continue;
            fresh.add(text);
        }
        if (oldest <= fresh.size()) throw new IOException("本地消息编号已满");
        int first = oldest - fresh.size();
        for (int i = 0; i < fresh.size(); i++) {
            Entry entry = new Entry(first + i, fresh.get(i));
            entries.put(entry.id, entry);
            remember(entry.message, entry.id);
        }
        oldest = first;
        cursor = next;
        hasMore = more;
        complete = !more && page.has("historyAvailability")
                && "available".equals(page.get("historyAvailability").getAsString())
                && (!page.has("truncationReason") || page.get("truncationReason").isJsonNull());
        if (!loaded && page.has("tailCursor") && !page.get("tailCursor").isJsonNull())
            tailCursor = page.get("tailCursor").getAsString();
        loaded = true;
    }

    /** 合并尾部增量并返回新项，重复回传不重复添加，翻旧页不改写尾部游标。 */
    public ArrayList<Entry> append(JsonObject page) throws IOException {
        if (!page.has("items") || !page.get("items").isJsonArray()) throw new IOException("新增消息格式无效");
        if (page.has("truncationReason") && !page.get("truncationReason").isJsonNull() && "source_discontinuity".equals(page.get("truncationReason").getAsString()))
            throw new IOException("消息来源出现断档");
        ArrayList<Entry> added = new ArrayList<>();
        int latest = entries.isEmpty() ? 1_000_000_000 : entries.lastKey();
        for (TranscriptText text : TranscriptText.read(page.getAsJsonArray("items"))) {
            if (containsMessage(text)) continue;
            if (latest == Integer.MAX_VALUE) throw new IOException("本地消息编号已满");
            Entry entry = new Entry(++latest, text);
            entries.put(entry.id, entry);
            remember(text, entry.id);
            added.add(entry);
        }
        if (page.has("nextCursor") && !page.get("nextCursor").isJsonNull()) tailCursor = page.get("nextCursor").getAsString();
        return added;
    }

    /** 同一原发送的回显可有不同记录id，但不能生成第二条气泡或改掉稳定编号。 */
    private boolean containsMessage(TranscriptText text) {
        if (sourceIds.containsKey(text.id)) return true;
        Integer number = hasLocalIdentity(text) ? localIds.get(text.localId) : null;
        if (number == null) return false;
        sourceIds.put(text.id, number);
        return true;
    }

    /** 发送身份仅属于用户消息，助手即使携带同名字段也不能吞掉其回答。 */
    private static boolean hasLocalIdentity(TranscriptText text) {
        return text.outgoing && text.localId != null && !text.localId.isEmpty();
    }

    /** 正文和附件共享原消息编号与localId索引，不建立第二套消息缓存。 */
    private void remember(TranscriptText text, int number) {
        sourceIds.put(text.id, number);
        if (hasLocalIdentity(text)) localIds.put(text.localId, number);
    }

    /** 仅明确分页截断且游标前进时立即追赶；空闲、来源断档及坏响应不加速。 */
    public static boolean hasPendingTail(JsonObject page, String previousCursor) {
        try {
            if (page == null || !page.has("items") || !page.get("items").isJsonArray()
                    || !page.has("truncated") || !page.get("truncated").getAsBoolean()
                    || !page.has("truncationReason")
                    || !"page_limit".equals(page.get("truncationReason").getAsString())) return false;
            String next = page.get("nextCursor").getAsString();
            return previousCursor != null && !next.isEmpty() && !next.equals(previousCursor);
        } catch (RuntimeException error) { return false; }
    }

    /** 原聊天页向新翻页的独立入口，结果仍保持从新到旧。 */
    public ArrayList<Entry> after(int minId, int count) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : entries.tailMap(minId, false).values()) {
            if (result.size() >= count) break;
            result.add(0, entry);
        }
        return result;
    }

    /** 原聊天页使用从新到旧的顺序，并用最早可见编号请求更旧的记录。 */
    public ArrayList<Entry> before(int maxId, int count) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : entries.descendingMap().values()) {
            if (maxId != 0 && entry.id >= maxId) continue;
            if (result.size() >= count) break;
            result.add(entry);
        }
        return result;
    }
}
