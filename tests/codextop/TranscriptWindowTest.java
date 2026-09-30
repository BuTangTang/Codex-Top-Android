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
        attachmentSnapshotsAndEchoes();
        missingTailRecovery();
        emptyProjectedHistory();
        System.out.println("TranscriptWindow: 重叠去重、旧页顺序、完整性与游标校验通过");
    }
    /** 缺尾游标必须以缓存最新项作连续锚点；恢复不改变旧正文、编号或历史分页位置。 */
    private static void missingTailRecovery() throws Exception {
        TranscriptWindow window = new TranscriptWindow();
        window.prepend(page(item("a") + "," + item("c"), true, "original-older", null));
        window = TranscriptWindow.restore(window.snapshot());
        if (!window.needsTailBootstrap()) throw new AssertionError("重开的旧缓存缺尾游标却不允许恢复");
        var original = window.before(0, 10);
        var latest = page(item("a") + "," + item("b") + "," + item("c") + "," + item("d"), false, null, "real-tail");
        latest.getAsJsonArray("items").get(2).getAsJsonObject().getAsJsonObject("raw")
                .getAsJsonObject("content").addProperty("text", "different-source-body");
        var added = window.recoverTail(latest);
        var rows = window.before(0, 10);
        if (rows.size() != 3 || added.size() != 1 || !added.get(0).message.id.equals("d")
                || !rows.get(0).message.id.equals("d") || rows.get(1).id != original.get(0).id
                || !rows.get(1).message.text.equals(original.get(0).message.text)
                || rows.get(2).id != original.get(1).id || !"original-older".equals(window.cursor)
                || !window.hasMore || window.complete || !"real-tail".equals(window.tailCursor)
                || window.needsTailBootstrap()) throw new AssertionError("恢复重排旧消息、改正文编号或错误接受旧页前缀");
        var storeRoot = java.nio.file.Files.createTempDirectory("codex-tail-recovery");
        try {
            var store = new TranscriptStore(storeRoot.toFile(), "server", "account", "machine");
            store.write("thread", window);
            if (!store.read("thread").snapshot().equals(window.snapshot()))
                throw new AssertionError("尾游标恢复落盘重开后身份变化");
        } finally {
            try (var paths = java.nio.file.Files.walk(storeRoot)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))
                    java.nio.file.Files.delete(path);
            }
        }
        // 只重叠更早的a，或完全无重叠，都不能把新tail当成已追平。
        for (String source : new String[] { item("a") + "," + item("e"), item("x") }) {
            var unchanged = window.snapshot();
            try { window.recoverTail(page(source, false, null, "unsupported-tail"));
                throw new AssertionError("没有缓存最新锚点仍接受尾游标");
            } catch (IOException expected) { }
            if (!unchanged.equals(window.snapshot())) throw new AssertionError("无锚点失败改变了原缓存");
        }
        var discontinuity = page(item("d") + "," + item("e"), false, null, "broken-tail");
        discontinuity.addProperty("truncationReason", "source_discontinuity");
        var unchanged = window.snapshot();
        try { window.recoverTail(discontinuity); throw new AssertionError("来源断档被当成连续恢复"); }
        catch (IOException expected) { }
        if (!unchanged.equals(window.snapshot())) throw new AssertionError("断档恢复改变了原缓存");
        try { window.recoverTail(page(item("d") + "," + item("a"), false, null, "reversed-tail"));
            throw new AssertionError("接受了锚点后回到旧消息的乱序来源");
        } catch (IOException expected) { }
        if (!unchanged.equals(window.snapshot())) throw new AssertionError("乱序来源改变了原缓存");
        try { window.recoverTail(page(item("d"), false, null, null)); throw new AssertionError("凭空接受尾游标"); }
        catch (IOException expected) { }
        if (!unchanged.equals(window.snapshot())) throw new AssertionError("无尾游标改变了原缓存");

        TranscriptWindow local = new TranscriptWindow();
        var localPage = page(item("local-source"), false, null, null);
        localPage.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("localId", "same-user-send");
        local.prepend(localPage);
        int stableId = local.before(0, 1).get(0).id;
        var echo = page(item("desktop-source") + "," + item("answer"), false, null, "echo-tail");
        echo.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("localId", "same-user-send");
        if (local.recoverTail(echo).size() != 1 || local.before(0, 10).get(1).id != stableId
                || !local.before(0, 10).get(1).message.id.equals("local-source"))
            throw new AssertionError("原用户localId不能连续恢复或改掉旧来源身份");

        var duplicateAnchor = new TranscriptWindow(); duplicateAnchor.prepend(localPage);
        var duplicatePage = page(item("desktop-source") + "," + item("d") + "," + item("echo-again") + "," + item("e"),
                false, null, "duplicate-echo-tail");
        duplicatePage.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("localId", "same-user-send");
        duplicatePage.getAsJsonArray("items").get(2).getAsJsonObject().addProperty("localId", "same-user-send");
        var duplicateAdded = duplicateAnchor.recoverTail(duplicatePage);
        if (duplicateAdded.size() != 2 || !duplicateAdded.get(0).message.id.equals("d")
                || !duplicateAdded.get(1).message.id.equals("e") || duplicateAnchor.before(0, 10).size() != 3)
            throw new AssertionError("选择末个重复锚点漏了新后缀，或重复用户回显生成气泡");

        // 助手即使携带相同localId也不能冒充用户发送的连续锚点。
        var assistant = new TranscriptWindow(); assistant.prepend(localPage);
        echo.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("raw").addProperty("role", "agent");
        try { assistant.recoverTail(echo); throw new AssertionError("助手localId被用作用户连续锚点"); }
        catch (IOException expected) { }

        // 同一来源多附件展开时，锚点之前未缓存的成员也不能追加到新消息后。
        var attachments = page(item("bundle"), false, null, "attachment-tail");
        attachments.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("raw").add("meta",
                JsonParser.parseString("{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":["
                        + "{\"name\":\"first.txt\",\"kind\":\"file\",\"path\":\"/synthetic/first.txt\"},"
                        + "{\"name\":\"second.txt\",\"kind\":\"file\",\"path\":\"/synthetic/second.txt\"},"
                        + "{\"name\":\"third.txt\",\"kind\":\"file\",\"path\":\"/synthetic/third.txt\"}]}}}"));
        var fullBundle = new TranscriptWindow(); fullBundle.prepend(attachments);
        var bundleSnapshot = fullBundle.snapshot(); bundleSnapshot.add("tailCursor", com.google.gson.JsonNull.INSTANCE);
        fullBundle = TranscriptWindow.restore(bundleSnapshot);
        int bundleLatest = fullBundle.before(0, 1).get(0).id;
        var bundleWithSuffix = attachments.deepCopy(); bundleWithSuffix.getAsJsonArray("items").add(JsonParser.parseString(item("after-bundle")));
        if (fullBundle.recoverTail(bundleWithSuffix).size() != 1 || fullBundle.before(0, 10).size() != 4
                || fullBundle.before(0, 10).get(1).id != bundleLatest)
            throw new AssertionError("未按展开后的多附件末行恢复锚点");
        var partial = new TranscriptWindow(); partial.prepend(attachments);
        var partialSnapshot = partial.snapshot();
        partialSnapshot.getAsJsonArray("rows").remove(2); partialSnapshot.getAsJsonArray("rows").remove(0);
        partialSnapshot.add("tailCursor", com.google.gson.JsonNull.INSTANCE);
        partial = TranscriptWindow.restore(partialSnapshot);
        int secondId = partial.before(0, 1).get(0).id;
        if (partial.recoverTail(attachments).size() != 1 || partial.before(0, 10).size() != 2
                || partial.before(0, 10).get(1).id != secondId
                || !partial.before(0, 10).get(0).message.id.equals("bundle:attachment:2"))
            throw new AssertionError("附件恢复把锚点前成员移到末尾或改变展开身份");
    }

    /** 空投影仍保留真实双向游标；四页预算用尽后保持待续，重开可从旧游标继续。 */
    private static void emptyProjectedHistory() throws Exception {
        TranscriptWindow window = new TranscriptWindow();
        window.recoverTail(page("", true, "older-1", "initial-tail"));
        if (!window.loaded || !window.needsVisibleHistory() || window.needsTailBootstrap() || window.complete)
            throw new AssertionError("空首屏被当成已完成或丢失真实游标");
        for (int pageNumber = 2; pageNumber <= 4; pageNumber++)
            window.prepend(page("", true, "older-" + pageNumber, "must-not-replace-tail"));
        window = TranscriptWindow.restore(window.snapshot());
        if (!window.needsVisibleHistory() || window.complete || !"older-4".equals(window.cursor)
                || !"initial-tail".equals(window.tailCursor)) throw new AssertionError("空页预算用尽被永久标记完成");
        var unchanged = window.snapshot();
        try { window.prepend(page("", true, "older-4", null)); throw new AssertionError("空旧页游标原地重复"); }
        catch (IOException expected) { }
        if (!unchanged.equals(window.snapshot())) throw new AssertionError("不前进的空旧页改变缓存");
        window.prepend(page(item("visible-older"), true, "older-5", "must-not-replace-tail"));
        if (window.needsVisibleHistory() || window.before(0, 10).size() != 1
                || !"initial-tail".equals(window.tailCursor)) throw new AssertionError("空页后正文未保留或尾部游标被旧页覆盖");
        var exhausted = new TranscriptWindow(); exhausted.recoverTail(page("", true, "older", "tail"));
        exhausted.prepend(page("", false, null, null));
        if (exhausted.needsVisibleHistory() || !exhausted.complete)
            throw new AssertionError("明确耗尽的空历史无法停止");
        var cachedEmpty = new TranscriptWindow(); cachedEmpty.prepend(page("", true, "same-older", null));
        cachedEmpty = TranscriptWindow.restore(cachedEmpty.snapshot());
        if (!cachedEmpty.needsTailBootstrap()
                || cachedEmpty.recoverTail(page(item("visible"), true, "same-older", "recovered-tail")).size() != 1
                || cachedEmpty.needsTailBootstrap()) throw new AssertionError("空旧缓存不能用同一个最新页向旧游标恢复");
        var noTail = new TranscriptWindow(); noTail.recoverTail(page(item("cached-body"), false, null, null));
        if (!noTail.needsTailBootstrap() || noTail.before(0, 1).isEmpty())
            throw new AssertionError("缺尾游标首屏丢正文或伪装已追平");
    }

    /** 所有页均为合成来源；明确标记真实历史耗尽时才允许complete。 */
    private static com.google.gson.JsonObject page(String items, boolean more, String cursor, String tail) {
        var page = JsonParser.parseString("{\"items\":[" + items + "],\"hasMore\":" + more
                + ",\"historyAvailability\":\"available\"}").getAsJsonObject();
        if (cursor != null) page.addProperty("nextCursor", cursor);
        if (tail != null) page.addProperty("tailCursor", tail);
        return page;
    }

    /** 附件沿原正文缓存落盘，重复localId回声不增气泡也不改稳定消息编号。 */
    private static void attachmentSnapshotsAndEchoes() throws Exception {
        var root = java.nio.file.Files.createTempDirectory("codex-attachment-history");
        try {
            var page = JsonParser.parseString("{\"items\":[" + item("attachment-source")
                    + "],\"hasMore\":false,\"historyAvailability\":\"available\"}").getAsJsonObject();
            var original = page.getAsJsonArray("items").get(0).getAsJsonObject();
            original.addProperty("localId", "same-upload");
            original.getAsJsonObject("raw").getAsJsonObject("content").addProperty("text", "");
            original.getAsJsonObject("raw").add("meta", JsonParser.parseString(
                    "{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"sample.png\",\"kind\":\"image\",\"path\":\"/computer/sample.png\",\"sizeBytes\":99,\"mimeType\":\"image/png\"},{\"name\":\"reply.txt\",\"kind\":\"file\",\"availability\":\"unavailable\",\"reason\":\"missing\"}]}}}"));
            TranscriptWindow window = new TranscriptWindow(); window.prepend(page);
            int id = window.before(0, 2).get(1).id;
            var store = new TranscriptStore(root.toFile(), "server", "account", "machine");
            store.write("thread", window);
            window = new TranscriptStore(root.toFile(), "server", "account", "machine").read("thread");
            var rows = window.before(0, 10);
            var restored = rows.get(1);
            if (rows.size() != 2 || restored.id != id || !restored.message.text.isEmpty() || restored.message.attachments.size() != 1
                    || !"/computer/sample.png".equals(restored.message.attachments.get(0).path)
                    || restored.message.attachments.get(0).sizeBytes != 99 || rows.get(0).message.attachments.get(0).isAvailable()
                    || !rows.get(0).message.id.equals("attachment-source:attachment:1")
                    || !rows.get(0).message.localId.equals("same-upload:attachment:1"))
                throw new AssertionError("附件元数据落盘重开丢失");
            var echo = original.deepCopy(); echo.addProperty("id", "another-desktop-id");
            var delta = new com.google.gson.JsonObject(); var echoes = new com.google.gson.JsonArray(); echoes.add(echo);
            delta.add("items", echoes); delta.addProperty("nextCursor", "new-tail");
            if (!window.append(delta).isEmpty() || window.before(0, 10).size() != 2 || window.before(0, 2).get(1).id != id)
                throw new AssertionError("同一附件localId回显生成第二条消息");
            store.write("thread", window); window = store.read("thread");
            if (!window.append(delta).isEmpty() || window.before(0, 10).size() != 2)
                throw new AssertionError("附件缓存重开后重复展开或重复回显");
            var assistant = echo.deepCopy(); assistant.addProperty("id", "assistant-answer");
            assistant.getAsJsonObject("raw").addProperty("role", "agent");
            echoes.add(assistant);
            if (window.append(delta).size() != 2) throw new AssertionError("助手附件被用户localId吞掉");
            var saved = window.snapshot();
            window = TranscriptWindow.restore(saved);
            if (!window.snapshot().equals(saved) || !window.append(delta).isEmpty())
                throw new AssertionError("多附件再次快照往返改变了身份或重复添加");
            var overlapping = new TranscriptWindow();
            page.getAsJsonArray("items").add(echo);
            overlapping.prepend(page);
            if (overlapping.before(0, 10).size() != 2) throw new AssertionError("同页重复localId没有合并");
        } finally {
            try (var paths = java.nio.file.Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))
                    java.nio.file.Files.delete(path);
            }
        }
    }

    /** 创建不含真实对话内容的最小文字消息。 */
    private static String item(String id) {
        return "{\"id\":\"" + id + "\",\"createdAtMs\":1000,\"raw\":{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"sample\"}}}";
    }
}
