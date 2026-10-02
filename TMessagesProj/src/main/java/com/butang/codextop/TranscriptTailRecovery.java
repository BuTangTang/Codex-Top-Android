package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

/** 当前 watch 内存中的缺锚点桥接；不落盘，完成或失效后不再保留页。 */
public final class TranscriptTailRecovery {
    /** 与原 watch 连续追页和历史加载相同，一轮最多四页。 */
    static final int ROUND_PAGES = 4;
    private final String anchorId;
    private final String anchorLocalId;
    private final long generation;
    private final String boundTail;
    private final String boundOlder;
    private Object connection;
    private TranscriptWindow history;
    private final ArrayList<JsonArray> pages = new ArrayList<>();
    private final ArrayList<String> cursors = new ArrayList<>();
    private String fixedTail;
    private String resume;
    private int pagesThisRound;
    private boolean yielded;
    private boolean continueNow;
    private boolean released;

    /** 绑定这次观察的末尾身份和游标；页数据随后才进入。 */
    private TranscriptTailRecovery(String anchorId, String anchorLocalId, Object connection,
            TranscriptWindow history, long generation) {
        this.anchorId = anchorId;
        this.anchorLocalId = anchorLocalId;
        this.connection = connection;
        this.history = history;
        this.generation = generation;
        this.boundTail = history.tailCursor;
        this.boundOlder = history.cursor;
    }

    /** 只有已加载且缺少尾游标的非空缓存才需要沿旧页找锚点。 */
    public static boolean required(TranscriptWindow history) {
        return history.needsTailBootstrap() && !history.before(0, 1).isEmpty();
    }

    /** 开始一次只属于当前连接、历史和观察代的桥接。 */
    public static TranscriptTailRecovery start(TranscriptWindow history, Object connection, long generation) throws IOException {
        if (!required(history)) throw new IOException("当前历史不需要尾部桥接");
        TranscriptWindow.Entry newest = history.before(0, 1).get(0);
        String localId = newest.message.outgoing ? newest.message.localId : null;
        return new TranscriptTailRecovery(newest.message.id, localId, connection, history, generation);
    }

    /** 最新页已经含缓存末尾身份时，调用方应直接把原页交给 recoverTail。 */
    public static boolean containsAnchor(TranscriptWindow history, JsonObject page) throws IOException {
        if (!required(history)) return false;
        if (discontinuous(page)) throw new IOException("消息来源出现断档");
        TranscriptWindow.Entry newest = history.before(0, 1).get(0);
        String localId = newest.message.outgoing ? newest.message.localId : null;
        for (TranscriptText text : TranscriptText.read(itemsOf(page))) {
            if (newest.message.id.equals(text.id)) return true;
            if (localId != null && !localId.isEmpty() && localId.equals(text.localId) && text.outgoing) return true;
        }
        return false;
    }

    /** 连接、历史对象、观察代、尾游标或旧游标有任一变化就不再是这次桥接。 */
    public boolean matches(Object connection, TranscriptWindow history, long generation) {
        return !released && this.connection == connection && this.history == history && this.generation == generation
                && Objects.equals(boundTail, history.tailCursor) && Objects.equals(boundOlder, history.cursor);
    }

    /** 下一请求要带的旧页游标；还没有桥接进度时仍请求最新整页。 */
    public String resumeCursor() { return released ? null : resume; }

    /** 本页之后同一轮是否还要立刻再取一页。 */
    public boolean continueNow() { return !released && continueNow; }

    /** 当前还留着的桥接页数；释放后为零。 */
    int retainedPages() { return released ? 0 : pages.size(); }

    /**
     * 纳入一页服务端结果。只有从锚点到最初最新页已经连续时才返回合成页；
     * 返回空表示旧缓存保持原样，调用方不能把这一页发给界面。
     */
    public JsonObject accept(JsonObject page, String requestedCursor) throws IOException {
        if (released) throw new IOException("桥接已失效");
        continueNow = false;
        if (yielded) {
            pagesThisRound = 0;
            yielded = false;
        }
        // 只能接上一次留下的游标，不能把另一页或另一会话插进来。
        if (!Objects.equals(requestedCursor, resume)) throw fail("历史游标无法继续");
        if (discontinuous(page)) throw fail("消息来源出现断档");
        JsonArray items = items(page);
        boolean more = hasMore(page);
        if (fixedTail == null) {
            fixedTail = tailOf(page);
            if (fixedTail == null || fixedTail.isEmpty()) throw fail("最新页缺少尾部游标");
        }
        ArrayList<TranscriptText> texts;
        try {
            texts = TranscriptText.read(items);
        } catch (RuntimeException error) {
            throw fail("最新消息格式无效");
        }
        if (contains(texts)) return compose(items);
        if (!more) throw fail("最新页缺少缓存连续锚点");
        String next = cursorOf(page);
        if (next == null || next.isEmpty() || Objects.equals(next, requestedCursor) || cursors.contains(next))
            throw fail("历史游标无法继续");
        pages.add(copy(items));
        cursors.add(next);
        resume = next;
        if (++pagesThisRound >= ROUND_PAGES) yielded = true;
        else continueNow = true;
        return null;
    }

    /** 完成、失效或切页时丢掉页副本和身份引用。 */
    public void release() {
        released = true;
        continueNow = false;
        pages.clear();
        cursors.clear();
        resume = null;
        fixedTail = null;
        connection = null;
        history = null;
    }

    /** 按从旧到新拼页，尾游标始终用最初最新页，不用中间页自己的尾游标。 */
    private JsonObject compose(JsonArray oldest) {
        JsonArray items = new JsonArray();
        for (JsonElement element : copy(oldest)) items.add(element);
        for (int index = pages.size() - 1; index >= 0; index--) {
            for (JsonElement element : pages.get(index)) items.add(element);
        }
        JsonObject ready = new JsonObject();
        ready.add("items", items);
        ready.addProperty("tailCursor", fixedTail);
        return ready;
    }

    /** 本页展开后的文字里是否出现缓存最后一项的来源或用户发送身份。 */
    private boolean contains(ArrayList<TranscriptText> texts) {
        for (TranscriptText text : texts) {
            if (anchorId.equals(text.id)) return true;
            if (anchorLocalId != null && !anchorLocalId.isEmpty() && anchorLocalId.equals(text.localId) && text.outgoing)
                return true;
        }
        return false;
    }

    /** 来源断档不能当成还能继续向旧翻的普通页。 */
    private static boolean discontinuous(JsonObject page) {
        return page != null && page.has("truncationReason") && !page.get("truncationReason").isJsonNull()
                && "source_discontinuity".equals(page.get("truncationReason").getAsString());
    }

    /** 没有条目数组的响应不能进入桥接，并丢掉已积累的页。 */
    private JsonArray items(JsonObject page) throws IOException {
        try {
            return itemsOf(page);
        } catch (IOException error) {
            throw fail(error.getMessage());
        }
    }

    /** 最新页快捷判断使用同一条目校验，此时还没有可释放的桥接。 */
    private static JsonArray itemsOf(JsonObject page) throws IOException {
        if (page == null || !page.has("items") || !page.get("items").isJsonArray())
            throw new IOException("最新消息格式无效");
        return page.getAsJsonArray("items");
    }

    /** 是否还有更旧的一页必须是明确的布尔值。 */
    private boolean hasMore(JsonObject page) throws IOException {
        if (!page.has("hasMore") || !page.get("hasMore").isJsonPrimitive() || !page.get("hasMore").getAsJsonPrimitive().isBoolean())
            throw fail("历史消息格式无效");
        return page.get("hasMore").getAsBoolean();
    }

    /** 读取本页声明的尾游标；缺失时返回空，由调用处决定是否拒绝。 */
    private static String tailOf(JsonObject page) {
        if (!page.has("tailCursor") || page.get("tailCursor").isJsonNull()) return null;
        return page.get("tailCursor").getAsString();
    }

    /** 读取向旧翻页的游标；缺失时返回空。 */
    private static String cursorOf(JsonObject page) {
        if (!page.has("nextCursor") || page.get("nextCursor").isJsonNull()) return null;
        return page.get("nextCursor").getAsString();
    }

    /** 复制条目，避免调用方随后改写网络响应时带动桥接页。 */
    private static JsonArray copy(JsonArray items) {
        JsonArray copy = new JsonArray();
        for (JsonElement element : items) copy.add(element.deepCopy());
        return copy;
    }

    /** 失败时先丢掉已积累的页，再把原契约错误交还调用方。 */
    private IOException fail(String message) {
        release();
        return new IOException(message);
    }
}
