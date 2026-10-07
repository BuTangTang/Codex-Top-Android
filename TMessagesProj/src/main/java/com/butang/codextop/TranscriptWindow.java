package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

/** 将桌面游标分页映射为原聊天列表的有序消息编号；仅在同一后台队列访问。 */
public final class TranscriptWindow {
    public static final class Entry {
        public final String epoch;
        public final int id;
        public final TranscriptText message;
        /** 编号仅在所属连续段内有效，段身份与源消息身份共同防止跨段误认。 */
        Entry(String epoch, int id, TranscriptText message) { this.epoch = epoch; this.id = id; this.message = message; }
    }
    private final String epoch;
    private final TreeMap<Integer, Entry> entries = new TreeMap<>();
    private final HashMap<String, Integer> sourceIds = new HashMap<>();
    private final HashMap<String, Integer> localIds = new HashMap<>();
    private final java.util.LinkedHashMap<String, TranscriptWindow> archive = new java.util.LinkedHashMap<>();
    private int oldest = 1_000_000_000;
    public String cursor;
    public String tailCursor;
    public boolean hasMore = true;
    public boolean loaded;
    public boolean complete;

    /** 新缓存从独立连续段开始，UUID 不依赖正文、本地编号或当前游标。 */
    public TranscriptWindow() { this(java.util.UUID.randomUUID().toString()); }

    /** 恢复时沿用已经落盘的段身份，不重新编号。 */
    private TranscriptWindow(String epoch) { this.epoch = epoch; }

    /** 当前连续段的持久身份，不能拿另一段的同号消息代替。 */
    public String epoch() { return epoch; }

    /** 从根查找当前段或保留段；调用方保存及管理归档时始终使用最新根。 */
    public TranscriptWindow segment(String requestedEpoch) {
        return epoch.equals(requestedEpoch) ? this : archive.get(requestedEpoch);
    }

    /** 旧异步工作必须仍绑定同一个保留对象，重开后的同 epoch 副本不冒认原对象。 */
    public boolean containsSegment(TranscriptWindow candidate) {
        return candidate != null && segment(candidate.epoch) == candidate;
    }

    /** 显式编号只在本段精确查找；缺项不以邻近编号代替。 */
    public Entry findNumber(int number) { return entries.get(number); }

    /** 仅在本段按原消息身份查找，发送身份的回退只匹配用户消息。 */
    public Entry findSource(String sourceId, String outgoingLocalId) {
        Integer number = sourceId == null ? null : sourceIds.get(sourceId);
        if (number == null && outgoingLocalId != null && !outgoingLocalId.isEmpty()) number = localIds.get(outgoingLocalId);
        return number == null ? null : entries.get(number);
    }

    /** 跨段定位始终优先当前段，再按来源身份查保留段，绝不比较不同段的本地编号。 */
    public TranscriptWindow findSegment(String sourceId, String outgoingLocalId) {
        if (findSource(sourceId, outgoingLocalId) != null) return this;
        for (TranscriptWindow window : archive.values())
            if (window.findSource(sourceId, outgoingLocalId) != null) return window;
        return null;
    }

    /** 本地选择器只读首末锚与计数，不复制正文；仅在历史所属队列构建。 */
    public static final class LocalSegment {
        public final String epoch;
        public final int count;
        public final long firstTime, lastTime;
        final TranscriptWindow window;
        final String sourceId;
        final int anchorId;
        private LocalSegment(TranscriptWindow window) {
            this.window = window; epoch = window.epoch; count = window.entries.size();
            Entry first = window.entries.firstEntry().getValue(), last = window.entries.lastEntry().getValue();
            firstTime = first.message.createdAtMs; lastTime = last.message.createdAtMs;
            sourceId = last.message.id; anchorId = last.id;
        }
    }

    /** 按原保存顺序返回非空段的轻量描述，不推断跨段的时间顺序或完整性。 */
    public ArrayList<LocalSegment> localSegments() {
        ArrayList<LocalSegment> result = new ArrayList<>();
        if (!entries.isEmpty()) result.add(new LocalSegment(this));
        for (TranscriptWindow window : archive.values())
            if (!window.entries.isEmpty()) result.add(new LocalSegment(window));
        return result;
    }

    /** 本地段存在与网络hasMore无关；已合并但仍被阅读引用保留的段也不擅自隐藏。 */
    public boolean hasOtherLocalSegments(TranscriptWindow displayed) {
        if (this != displayed && !entries.isEmpty()) return true;
        for (TranscriptWindow window : archive.values())
            if (window != displayed && !window.entries.isEmpty()) return true;
        return false;
    }

    /** 缺少旧末锚只是未接齐的正证据；没有该证据绝不等于已证明完整连续。 */
    public boolean hasMissingCachedTailAnchor() {
        for (TranscriptWindow window : archive.values()) {
            if (window.entries.isEmpty()) continue;
            TranscriptText last = window.entries.lastEntry().getValue().message;
            if (findSource(last.id, hasLocalIdentity(last) ? last.localId : null) == null) return true;
        }
        return false;
    }

    /** 待发回显核对包含仍保留的旧段，切到最新页不能让旧回显重新进入待发。 */
    public java.util.Set<String> allOutgoingLocalIds() {
        java.util.HashSet<String> result = new java.util.HashSet<>(localIds.keySet());
        for (TranscriptWindow window : archive.values()) result.addAll(window.localIds.keySet());
        return result;
    }

    /** v2 平铺保存当前段和归档，旧段不递归携带归档；消息编号及游标保持。 */
    public JsonObject snapshot() {
        JsonObject root = segmentSnapshot();
        com.google.gson.JsonArray archived = new com.google.gson.JsonArray();
        for (TranscriptWindow window : archive.values()) archived.add(window.segmentSnapshot());
        root.add("archive", archived);
        return root;
    }

    /** 逐段逐行写出原v2快照，不构造整份正文JSON树；调用方负责UTF-8、字节上限及关闭。 */
    public void writeSnapshot(JsonWriter writer) throws IOException {
        writer.setSerializeNulls(true);
        writer.setHtmlSafe(false);
        writer.setIndent("");
        TypeAdapter<JsonElement> values = new Gson().getAdapter(JsonElement.class);
        writeSegment(writer, values, true);
    }

    /** 当前段与扁平归档共用原字段顺序，仅根段带archive，单行写完即可释放临时树。 */
    private void writeSegment(JsonWriter writer, TypeAdapter<JsonElement> values, boolean root) throws IOException {
        writer.beginObject();
        for (java.util.Map.Entry<String, JsonElement> field : segmentMetadata().entrySet()) {
            writer.name(field.getKey());
            values.write(writer, field.getValue());
        }
        writer.name("rows").beginArray();
        for (Entry entry : entries.values()) values.write(writer, rowSnapshot(entry));
        writer.endArray();
        if (root) {
            writer.name("archive").beginArray();
            for (TranscriptWindow window : archive.values()) window.writeSegment(writer, values, false);
            writer.endArray();
        }
        writer.endObject();
    }

    /** 每个段只序列化自己，避免多次断档后重复嵌套旧正文。 */
    private JsonObject segmentSnapshot() {
        JsonObject root = segmentMetadata();
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (Entry entry : entries.values()) rows.add(rowSnapshot(entry));
        root.add("rows", rows);
        return root;
    }

    /** 两种写法共用段头字段及插入顺序，空游标和所有原始状态均原样保存。 */
    private JsonObject segmentMetadata() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.addProperty("epoch", epoch);
        root.addProperty("oldest", oldest);
        root.addProperty("cursor", cursor);
        root.addProperty("tailCursor", tailCursor);
        root.addProperty("hasMore", hasMore);
        root.addProperty("loaded", loaded);
        root.addProperty("complete", complete);
        return root;
    }

    /** 行格式只保留一个构造owner，编号、长整型时间、正文转义及附件封套不分叉。 */
    private static JsonObject rowSnapshot(Entry entry) {
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
        return row;
    }

    /** 损坏缓存不交付半份数据；调用方可保留文件并重新读取来源。 */
    public static TranscriptWindow restore(JsonObject root) throws IOException {
        try {
            int version = root.get("version").getAsInt();
            if (version != 1 && version != 2) throw new IOException("缓存版本不支持");
            TranscriptWindow window = restoreSegment(root, version);
            if (version == 2) {
                for (com.google.gson.JsonElement value : root.getAsJsonArray("archive")) {
                    JsonObject saved = value.getAsJsonObject();
                    if (saved.has("archive") || saved.get("version").getAsInt() != 2) throw new IOException("归档段格式无效");
                    TranscriptWindow old = restoreSegment(saved, 2);
                    if (window.segment(old.epoch) != null) throw new IOException("缓存段身份重复");
                    window.archive.put(old.epoch, old);
                }
            }
            return window;
        } catch (RuntimeException error) { throw new IOException("缓存格式无效", error); }
    }

    /** 仅标记内存中的布局/模型兼容问题；文件流的IOException不可冒充该类型触发重读。 */
    static final class SnapshotCompatibilityException extends IOException {
        /** 明确的字段或版本布局需要原整树last-wins裁定。 */
        SnapshotCompatibilityException(String reason) { super(reason); }

        /** 只包装不访问文件的原模型校验，不包装JsonReader或adapter的读取异常。 */
        SnapshotCompatibilityException(IOException validation) { super("缓存格式需兼容读取", validation); }
    }

    /** 软件规范顺序的缓存逐行恢复；调用方须检查文件尾，仅兼容异常允许丢弃窗口后重读。 */
    static TranscriptWindow readSnapshot(JsonReader reader) throws IOException {
        Strictness previous = reader.getStrictness();
        reader.setStrictness(Strictness.LENIENT);
        try {
            // 直接使用原JsonElement适配器，数值及行内重复字段仍沿Gson；Error不转为重读DOM。
            TypeAdapter<JsonElement> values = new Gson().getAdapter(JsonElement.class);
            return readSnapshotSegment(reader, values, true);
        } finally {
            // 原JsonParser在整份值后恢复原严格度，再检查完整文件尾；尾随内容不能提前交付。
            reader.setStrictness(previous);
        }
    }

    /** 只快读原段头→rows→根archive顺序；乱序、重复或未知段字段交原整树读法处理last-wins。 */
    private static TranscriptWindow readSnapshotSegment(JsonReader reader, TypeAdapter<JsonElement> values,
            boolean root) throws IOException {
        reader.beginObject();
        JsonObject header = new JsonObject();
        readSnapshotField(reader, values, header, "version");
        int version = header.get("version").getAsInt();
        if (version != 1 && version != 2) throw new SnapshotCompatibilityException("缓存版本不支持");
        if (!root && version != 2) throw new SnapshotCompatibilityException("归档段格式无效");
        if (version == 2) readSnapshotField(reader, values, header, "epoch");
        for (String name : new String[]{"oldest", "cursor", "tailCursor", "hasMore", "loaded", "complete"})
            readSnapshotField(reader, values, header, name);
        TranscriptWindow window;
        try { window = restoreSegmentHeader(header, version); }
        catch (IOException validation) { throw new SnapshotCompatibilityException(validation); }
        requireSnapshotField(reader, "rows");
        reader.beginArray();
        while (reader.hasNext()) {
            // adapter直接抛出流IO；先读完整行，再单独捕获纯模型校验失败。
            JsonObject row = values.read(reader).getAsJsonObject();
            try { restoreRow(window, row); }
            catch (IOException validation) { throw new SnapshotCompatibilityException(validation); }
        }
        reader.endArray();
        if (root && version == 2) {
            requireSnapshotField(reader, "archive");
            reader.beginArray();
            while (reader.hasNext()) {
                TranscriptWindow old = readSnapshotSegment(reader, values, false);
                if (window.segment(old.epoch) != null) throw new SnapshotCompatibilityException("缓存段身份重复");
                window.archive.put(old.epoch, old);
            }
            reader.endArray();
        }
        if (reader.hasNext()) throw new SnapshotCompatibilityException("缓存字段顺序需兼容读取");
        reader.endObject();
        return window;
    }

    /** 每次只保留小型段头的原JsonElement，不能以nextInt/Boolean改变原标量及数组转换边界。 */
    private static void readSnapshotField(JsonReader reader, TypeAdapter<JsonElement> values,
            JsonObject header, String name) throws IOException {
        requireSnapshotField(reader, name);
        header.add(name, values.read(reader));
    }

    /** 非规范顺序必须退回整份旧解析，不能先用前一个重复字段的值交付部分历史。 */
    private static void requireSnapshotField(JsonReader reader, String name) throws IOException {
        if (!reader.hasNext() || !name.equals(reader.nextName())) throw new SnapshotCompatibilityException("缓存字段顺序需兼容读取");
    }

    /** 原整树路径仍按文件行顺序恢复；流式路径共用同一行投影及身份验证。 */
    private static TranscriptWindow restoreSegment(JsonObject root, int version) throws IOException {
        TranscriptWindow window = restoreSegmentHeader(root, version);
        for (com.google.gson.JsonElement value : root.getAsJsonArray("rows")) restoreRow(window, value.getAsJsonObject());
        return window;
    }

    /** v1只补独立段身份；两条读法共用原段头转换，不推算编号、状态或游标。 */
    private static TranscriptWindow restoreSegmentHeader(JsonObject root, int version) throws IOException {
        String epoch = version == 1 ? java.util.UUID.randomUUID().toString() : root.get("epoch").getAsString();
        if (!java.util.UUID.fromString(epoch).toString().equals(epoch)) throw new IOException("缓存段身份无效");
        TranscriptWindow window = new TranscriptWindow(epoch);
        window.oldest = root.get("oldest").getAsInt();
        window.cursor = root.get("cursor").isJsonNull() ? null : root.get("cursor").getAsString();
        window.tailCursor = root.get("tailCursor").isJsonNull() ? null : root.get("tailCursor").getAsString();
        window.hasMore = root.get("hasMore").getAsBoolean();
        window.loaded = root.get("loaded").getAsBoolean();
        window.complete = root.get("complete").getAsBoolean();
        return window;
    }

    /** 单行仍交原TranscriptText投影；先核编号/source身份，再沿原用户localId规则去重。 */
    private static void restoreRow(TranscriptWindow window, JsonObject row) throws IOException {
        int number = row.get("number").getAsInt();
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        items.add(row.get("item"));
        ArrayList<TranscriptText> parsed = TranscriptText.read(items);
        if (number < window.oldest || parsed.size() != 1 || window.entries.containsKey(number)
                || window.sourceIds.containsKey(parsed.get(0).id)) throw new IOException("缓存消息身份无效");
        TranscriptText text = parsed.get(0);
        if (window.containsMessage(text)) return;
        window.entries.put(number, new Entry(window.epoch, number, text));
        window.remember(text, number);
    }

    /** 没有真实尾游标时重新读取最新页；不能因为旧缓存标为loaded就永久跳过。 */
    public boolean needsTailBootstrap() {
        return !loaded || tailCursor == null || tailCursor.isEmpty()
                || needsVisibleHistory() && (cursor == null || cursor.isEmpty());
    }

    /** 空投影页仍可能有可显示的旧消息；沿已有历史游标继续，不能标记该版本已追平。 */
    public boolean needsVisibleHistory() { return loaded && entries.isEmpty() && hasMore; }

    /** 只在历史所属队列上读取归档末锚；缺锚仅作为一次补页准入，绝不证明两段连续。 */
    public boolean hasUnbridgedCachedHistory() {
        if (!loaded || !hasMore || entries.isEmpty()) return false;
        for (TranscriptWindow cached : archive.values()) {
            if (cached.entries.isEmpty()) continue;
            TranscriptText last = cached.entries.lastEntry().getValue().message;
            if (findSource(last.id, hasLocalIdentity(last) ? last.localId : null) == null) return true;
        }
        return false;
    }

    /** 合法最新页可直接成为新连续段；仅明确缺锚点时换段，坏响应和倒序仍然拒绝。 */
    public TranscriptWindow acceptLatest(JsonObject page) throws IOException {
        ArrayList<TranscriptText> source = readCompletePage(page, true);
        validateRetainedOrder(source);
        int anchor = -1;
        if (!entries.isEmpty()) {
            int latest = entries.lastKey();
            for (int i = 0; i < source.size(); i++) {
                TranscriptText text = source.get(i);
                Entry known = findSource(text.id, hasLocalIdentity(text) ? text.localId : null);
                if (known != null && known.id == latest) { anchor = i; break; }
            }
        }
        if (entries.isEmpty() || anchor >= 0) {
            ensureAppendCapacity(source.subList(anchor + 1, source.size()));
            recoverTail(page);
            return this;
        }
        TranscriptWindow latest = new TranscriptWindow();
        latest.prepend(page, true, source);
        // 新根独占扁平归档；旧视图保留原对象、正文、编号和游标，不保留重复归档链。
        latest.archive.putAll(archive);
        latest.archive.put(epoch, this);
        archive.clear();
        return latest;
    }

    /** 向旧读到归档末尾的真实锚点后，复用其连续前缀及旧游标，减少重复下载缓存正文。 */
    public void prependWithCachedBridge(JsonObject page) throws IOException {
        ArrayList<TranscriptText> source = readCompletePage(page, false);
        validateRetainedOrder(source);
        for (TranscriptWindow cached : archive.values()) {
            int anchor = cachedPrefixAnchor(source, cached);
            if (anchor < 0) continue;
            ArrayList<TranscriptText> joined = new ArrayList<>();
            for (Entry entry : cached.entries.values()) joined.add(entry.message);
            joined.addAll(source.subList(anchor + 1, source.size()));
            prepend(page, false, joined);
            cursor = cached.cursor;
            hasMore = cached.hasMore;
            complete = cached.complete;
            return;
        }
        prepend(page, false, source);
    }

    /** 仅释放完整并入且未被引用的归档，返回是否实际移除；缺口和旧读者都保留。 */
    public boolean pruneMergedSegments(java.util.Set<String> pinnedEpochs) {
        java.util.Objects.requireNonNull(pinnedEpochs, "pinnedEpochs");
        boolean changed = false;
        java.util.Iterator<TranscriptWindow> old = archive.values().iterator();
        while (old.hasNext()) {
            TranscriptWindow cached = old.next();
            if (pinnedEpochs.contains(cached.epoch)) continue;
            int previous = 0;
            boolean included = true;
            for (Entry row : cached.entries.values()) {
                Entry current = findSource(row.message.id, hasLocalIdentity(row.message) ? row.message.localId : null);
                if (current == null || current.id <= previous || !sameCachedContent(row.message, current.message)) {
                    included = false;
                    break;
                }
                previous = current.id;
            }
            if (included) { old.remove(); changed = true; }
        }
        return changed;
    }

    /** 确认保留的文字、原时间和附件都已在当前段，不能只靠同号或同正文清理旧缓存。 */
    private static boolean sameCachedContent(TranscriptText left, TranscriptText right) {
        return left.outgoing == right.outgoing && left.createdAtMs == right.createdAtMs
                && left.text.equals(right.text) && java.util.Objects.equals(left.localId, right.localId)
                && (left.attachments.isEmpty() && right.attachments.isEmpty()
                    || DesktopAttachment.meta(left.attachments).equals(DesktopAttachment.meta(right.attachments)));
    }

    /** 前缀必须是已缓存段的连续后缀且到达末项，不能用缺项页跨过尚未确认的缺口。 */
    private static int cachedPrefixAnchor(ArrayList<TranscriptText> source, TranscriptWindow cached) {
        if (cached.entries.isEmpty() || source.isEmpty()) return -1;
        Entry first = cached.findSource(source.get(0).id, hasLocalIdentity(source.get(0)) ? source.get(0).localId : null);
        if (first == null) return -1;
        java.util.Iterator<Entry> expected = cached.entries.tailMap(first.id, true).values().iterator();
        Entry next = expected.next();
        int previous = 0;
        for (int i = 0; i < source.size(); i++) {
            TranscriptText text = source.get(i);
            Entry known = cached.findSource(text.id, hasLocalIdentity(text) ? text.localId : null);
            if (known == null) return -1;
            if (known.id == previous) continue;
            if (known.id != next.id) return -1;
            if (!expected.hasNext()) return i;
            previous = known.id;
            next = expected.next();
        }
        return -1;
    }

    /** 在修改任何段之前验证响应形状及投影，预览、未知、截断和来源断档不能被拼接。 */
    private static ArrayList<TranscriptText> readCompletePage(JsonObject page, boolean latest) throws IOException {
        try {
            if (page == null || !page.has("items") || !page.get("items").isJsonArray()
                    || !page.has("hasMore") || !page.get("hasMore").isJsonPrimitive()
                    || !page.getAsJsonPrimitive("hasMore").isBoolean()
                    || !"available".equals(optionalString(page, "historyAvailability"))
                    || optionalString(page, "truncationReason") != null
                    || page.has("truncated") && (!page.get("truncated").isJsonPrimitive()
                        || !page.getAsJsonPrimitive("truncated").isBoolean() || page.get("truncated").getAsBoolean()))
                throw new IOException("历史页尚未完整可用");
            String next = optionalString(page, "nextCursor");
            String tail = optionalString(page, "tailCursor");
            if (page.get("hasMore").getAsBoolean() && (next == null || next.isEmpty())
                    || latest && (tail == null || tail.isEmpty())) throw new IOException("历史页缺少有效游标");
            return TranscriptText.read(page.getAsJsonArray("items"));
        } catch (RuntimeException error) { throw new IOException("历史消息格式无效", error); }
    }

    /** 可选字符串只接受真实文本或空值，不能把坏字段强制转换成游标。 */
    private static String optionalString(JsonObject object, String name) throws IOException {
        com.google.gson.JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("历史页文本字段无效");
        return value.getAsString();
    }

    /** 每个保留段都能证明自身顺序，已知消息倒退不是换新段的理由。 */
    private void validateRetainedOrder(ArrayList<TranscriptText> source) throws IOException {
        validateKnownOrder(source);
        for (TranscriptWindow cached : archive.values()) cached.validateKnownOrder(source);
    }

    /** 只用本段真实来源身份验证顺序，不以时间戳或其他段的同号消息推断。 */
    private void validateKnownOrder(ArrayList<TranscriptText> source) throws IOException {
        int previous = 0;
        for (TranscriptText text : source) {
            Entry known = findSource(text.id, hasLocalIdentity(text) ? text.localId : null);
            if (known == null) continue;
            if (known.id < previous) throw new IOException("最新页来源顺序不连续");
            previous = known.id;
        }
    }

    /** 先验证新增编号容量，失败时不把半份最新页写入正在显示的连续段。 */
    private void ensureAppendCapacity(java.util.List<TranscriptText> source) throws IOException {
        java.util.HashSet<String> ids = new java.util.HashSet<>(), locals = new java.util.HashSet<>();
        long latest = entries.isEmpty() ? 1_000_000_000L : entries.lastKey();
        for (TranscriptText text : source) {
            if (findSource(text.id, hasLocalIdentity(text) ? text.localId : null) != null || !ids.add(text.id)
                    || hasLocalIdentity(text) && !locals.add(text.localId)) continue;
            if (++latest > Integer.MAX_VALUE) throw new IOException("本地消息编号已满");
        }
    }

    /** 缺尾游标时只用缓存最新消息的真实连续锚点恢复，旧正文及编号均保持不变。 */
    public ArrayList<Entry> recoverTail(JsonObject page) throws IOException {
        String tail = page.has("tailCursor") && !page.get("tailCursor").isJsonNull()
                ? page.get("tailCursor").getAsString() : null;
        if (page.has("truncationReason") && !page.get("truncationReason").isJsonNull()
                && "source_discontinuity".equals(page.get("truncationReason").getAsString()))
            throw new IOException("消息来源出现断档");
        if (entries.isEmpty()) {
            prepend(page, true);
            // 缺失尾游标仍保留实际正文；下一轮必须重新恢复，不能伪造追平。
            tailCursor = tail;
            return new ArrayList<>(entries.values());
        }
        if (tail == null || tail.isEmpty()) throw new IOException("最新页缺少尾部游标");
        if (!page.has("items") || !page.get("items").isJsonArray()) throw new IOException("最新消息格式无效");
        ArrayList<TranscriptText> source = TranscriptText.read(page.getAsJsonArray("items"));
        int anchor = -1;
        int latest = entries.lastKey();
        for (int i = 0; i < source.size(); i++) {
            TranscriptText text = source.get(i);
            Integer known = sourceIds.get(text.id);
            if (known == null && hasLocalIdentity(text)) known = localIds.get(text.localId);
            if (known != null && known == latest && anchor < 0) anchor = i;
            if (anchor >= 0 && known != null && known < latest) throw new IOException("最新页来源顺序不连续");
        }
        if (anchor < 0) throw new IOException("最新页缺少缓存连续锚点");
        // 仅追加锚点之后的真实投影成员，多附件来源也不把锚点之前的项移到末尾。
        ArrayList<Entry> added = appendMessages(source.subList(anchor + 1, source.size()));
        tailCursor = tail;
        return added;
    }

    /** 接收从旧到新排列的上一页；过滤工具后仍沿真实游标继续，不按条数判断结束。 */
    public void prepend(JsonObject page) throws IOException { prepend(page, false); }

    /** 最新页恢复允许重复向旧游标；普通翻旧页仍要求游标前进，防止空页循环。 */
    private void prepend(JsonObject page, boolean recoveringLatest) throws IOException {
        prepend(page, recoveringLatest, null);
    }

    /** 已校验投影与缓存桥接共用原编号和游标提交规则，缓存正文无需重新投影。 */
    private void prepend(JsonObject page, boolean recoveringLatest, java.util.List<TranscriptText> source) throws IOException {
        if (!page.has("items") || !page.get("items").isJsonArray() || !page.has("hasMore"))
            throw new IOException("历史消息格式无效");
        boolean more = page.get("hasMore").getAsBoolean();
        String next = page.has("nextCursor") && !page.get("nextCursor").isJsonNull()
                ? page.get("nextCursor").getAsString() : null;
        if (more && (next == null || next.isEmpty() || !recoveringLatest && next.equals(cursor)))
            throw new IOException("历史游标无法继续");
        if (source == null) source = TranscriptText.read(page.getAsJsonArray("items"));
        ArrayList<TranscriptText> fresh = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        java.util.HashSet<String> seenLocal = new java.util.HashSet<>();
        for (TranscriptText text : source) {
            if (containsMessage(text) || !seen.add(text.id)) continue;
            if (hasLocalIdentity(text) && !seenLocal.add(text.localId)) continue;
            fresh.add(text);
        }
        if (oldest <= fresh.size()) throw new IOException("本地消息编号已满");
        int first = oldest - fresh.size();
        for (int i = 0; i < fresh.size(); i++) {
            Entry entry = new Entry(epoch, first + i, fresh.get(i));
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
        ArrayList<Entry> added = appendMessages(TranscriptText.read(page.getAsJsonArray("items")));
        if (page.has("nextCursor") && !page.get("nextCursor").isJsonNull()) tailCursor = page.get("nextCursor").getAsString();
        return added;
    }

    /** 尾部增量与连续恢复共用原编号规则，仅为实际新消息分配编号。 */
    private ArrayList<Entry> appendMessages(java.util.List<TranscriptText> messages) throws IOException {
        ArrayList<Entry> added = new ArrayList<>();
        int latest = entries.isEmpty() ? 1_000_000_000 : entries.lastKey();
        for (TranscriptText text : messages) {
            if (containsMessage(text)) continue;
            if (latest == Integer.MAX_VALUE) throw new IOException("本地消息编号已满");
            Entry entry = new Entry(epoch, ++latest, text);
            entries.put(entry.id, entry);
            remember(text, entry.id);
            added.add(entry);
        }
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

    /** 围绕锚点交付新到旧的一页，包含锚点并补齐首末；缺失时回退到相邻的本地消息。 */
    public ArrayList<Entry> around(int anchorId, int count) {
        ArrayList<Entry> result = new ArrayList<>();
        if (count <= 0 || entries.isEmpty()) return result;
        java.util.Map.Entry<Integer, Entry> pivot = anchorId == 0 ? entries.lastEntry() : entries.floorEntry(anchorId);
        if (pivot == null) pivot = entries.firstEntry();
        for (Entry entry : entries.tailMap(pivot.getKey(), false).values()) {
            if (result.size() >= count / 2) break;
            result.add(0, entry);
        }
        result.add(pivot.getValue());
        for (Entry entry : entries.headMap(pivot.getKey(), false).descendingMap().values()) {
            if (result.size() >= count) break;
            result.add(entry);
        }
        // 锚点靠近最旧端时，用另一侧补满，但不越过请求的页大小。
        if (result.size() < count) {
            for (Entry entry : entries.tailMap(result.get(0).id, false).values()) {
                if (result.size() >= count) break;
                result.add(0, entry);
            }
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
