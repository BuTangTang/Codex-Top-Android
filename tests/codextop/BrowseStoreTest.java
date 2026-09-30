package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

/** 独立临时目录验证浏览元数据，不读取手机、真实账号或原生 Codex 数据。 */
public final class BrowseStoreTest {
    /** 覆盖空页、分区、分页保留、排序、坏响应和真实状态不续期。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("codex-browse-test");
        try {
            isolationAndEmpty(root);
            pagesAndOrdering(root);
            invalidAndWriteFailure(root);
            cachedStatusIsStale(root);
            System.out.println("BrowseStore: 空页恢复、账号及范围隔离、多页保留、稳定排序、损坏失败与状态不续期通过");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 空列表必须跨实例恢复；服务、账号、电脑及不同项目不能串用。 */
    private static void isolationAndEmpty(Path root) throws Exception {
        BrowseStore store = new BrowseStore(root.toFile(), "server", "account");
        JsonObject empty = page(null, false);
        store.write("projects", "machine", null, empty);
        JsonObject restored = new BrowseStore(root.toFile(), "server", "account").read("projects", "machine", null);
        check(restored != null && restored.getAsJsonArray("rows").size() == 0, "成功空页未恢复");
        check(new BrowseStore(root.toFile(), "other-server", "account").read("projects", "machine", null) == null, "服务串用");
        check(new BrowseStore(root.toFile(), "server", "other-account").read("projects", "machine", null) == null, "账号串用");
        check(store.read("projects", "other-machine", null) == null, "电脑串用");
        store.write("conversations", "machine", Arrays.asList("/one", "/two"), page(null, false, row("thread", 1)));
        check(store.read("conversations", "machine", Arrays.asList("/two", "/one", "/one")) != null, "项目集合顺序影响缓存");
        check(store.read("conversations", "machine", Collections.singletonList("/other")) == null, "项目串用");
        check(store.read("conversations", "machine", null) == null, "全部会话与项目串用");
        JsonObject project = JsonParser.parseString("{\"id\":\"p\",\"name\":\"\",\"rootPaths\":[],\"available\":false}").getAsJsonObject();
        store.write("projects", "unavailable", null, page(null, false, project));
        check(store.read("projects", "unavailable", null).getAsJsonArray("rows").size() == 1, "空根目录项目被丢弃");
    }

    /** 首屏更新保留已浏览尾页；不完整及下一页失败不能把旧内容变成空白。 */
    private static void pagesAndOrdering(Path root) throws Exception {
        JsonObject first = BrowseStore.merge("conversations", null, page("page2", false, row("b", 10), row("a", 10)), false);
        check(ids(first).equals("a,b"), "同时间顺序不稳定");
        JsonObject all = BrowseStore.merge("conversations", first, page(null, false, row("tail", 1)), true);
        check(ids(all).equals("a,b,tail"), "加载第二页丢失首屏");
        JsonObject renamed = row("a", 10); renamed.addProperty("title", "updated");
        JsonObject refresh = BrowseStore.merge("conversations", all, page("new-page2", false, renamed, row("new", 20)), false);
        check(ids(refresh).equals("new,a,b,tail"), "首屏刷新截断已加载尾页");
        check(refresh.getAsJsonArray("rows").get(1).getAsJsonObject().get("title").getAsString().equals("updated"), "去重保留了旧内容");
        JsonObject incomplete = BrowseStore.merge("conversations", refresh, page(null, true), false);
        check(ids(incomplete).equals(ids(refresh)), "不完整空页清空旧行");
        check(incomplete.get("searchIncomplete").getAsBoolean(), "伪造了完整结果");
        BrowseStore store = new BrowseStore(root.toFile(), "server", "pages");
        store.write("conversations", "machine", null, refresh);
        check(ids(new BrowseStore(root.toFile(), "server", "pages").read("conversations", "machine", null)).equals(ids(refresh)), "重启丢失多页");
        JsonObject finalPage = BrowseStore.merge("conversations", refresh, page(null, false, row("only", 30)), false);
        check(ids(finalPage).equals("only"), "完整无后续首屏没有清理已移除结果");
    }

    /** 格式错误不覆盖可用文件，损坏单一范围不影响其他页，写失败保留调用者成功结果。 */
    private static void invalidAndWriteFailure(Path root) throws Exception {
        BrowseStore store = new BrowseStore(root.toFile(), "server", "invalid");
        JsonObject good = page("next", false, row("good", 5));
        good.getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("token", "synthetic-secret");
        store.write("conversations", "machine", null, good);
        JsonObject duplicate = page(null, false, row("duplicate", 1), row("duplicate", 2));
        try { store.write("conversations", "machine", null, duplicate); throw new AssertionError("重复身份仍保存"); }
        catch (java.io.IOException expected) { }
        JsonObject kept = store.read("conversations", "machine", null);
        check(ids(kept).equals("good") && kept.get("nextCursor").getAsString().equals("next"), "失败改变了已有分页");
        check(!kept.getAsJsonArray("rows").get(0).getAsJsonObject().has("token"), "白名单之外的字段落盘");
        Path file = root.resolve(TranscriptStore.digest("server\ninvalid"))
                .resolve(TranscriptStore.digest(BrowseStore.scope("conversations", "machine", null)) + ".json");
        Files.writeString(file, "broken-json");
        try { store.read("conversations", "machine", null); throw new AssertionError("坏缓存未拒绝"); }
        catch (java.io.IOException expected) { }
        Path blocked = root.resolve("not-a-directory"); Files.writeString(blocked, "fixture");
        JsonObject successfulMemory = BrowseStore.merge("conversations", null, good, false);
        try { new BrowseStore(blocked.toFile(), "server", "write-failed").write("conversations", "machine", null, successfulMemory);
            throw new AssertionError("预期写失败"); } catch (java.io.IOException expected) { }
        check(ids(successfulMemory).equals("good") && successfulMemory.get("nextCursor").getAsString().equals("next"), "写失败更改成功内存页");
    }

    /** 缓存保留原状态证据以供解释，但读取不会产生新的在线有效期。 */
    private static void cachedStatusIsStale(Path root) throws Exception {
        JsonObject candidate = row("stale", 5);
        candidate.add("details", JsonParser.parseString("{\"codexLifecycle\":{\"v\":1,\"state\":\"running\",\"eventAtMs\":100,\"checkedAtMs\":200}}"));
        BrowseStore store = new BrowseStore(root.toFile(), "server", "stale");
        store.write("conversations", "machine", null, page(null, false, candidate));
        JsonObject loaded = store.read("conversations", "machine", null).getAsJsonArray("rows").get(0).getAsJsonObject();
        SessionStatus.Store status = new SessionStatus.Store();
        status.candidate(1, "machine", loaded, -1, -1, true);
        check(status.get(1, 90000).validity.equals("stale"), "缓存状态被续期");
    }

    /** 构造已有浏览契约的合成页面。 */
    private static JsonObject page(String cursor, boolean incomplete, JsonObject... rows) {
        JsonObject page = new JsonObject(); JsonArray array = new JsonArray();
        for (JsonObject row : rows) array.add(row);
        page.add("rows", array); page.addProperty("nextCursor", cursor); page.addProperty("searchIncomplete", incomplete);
        return page;
    }

    /** 构造不含真实对话内容的会话元数据。 */
    private static JsonObject row(String id, long updatedAt) {
        JsonObject row = new JsonObject(); row.addProperty("remoteSessionId", id);
        row.addProperty("title", id); row.addProperty("updatedAtMs", updatedAt); return row;
    }

    /** 只比较稳定身份顺序，避免测试依赖展示文字。 */
    private static String ids(JsonObject page) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (var row : page.getAsJsonArray("rows")) ids.add(row.getAsJsonObject().get("remoteSessionId").getAsString());
        return String.join(",", ids);
    }

    /** 失败提供场景含义，不打印缓存正文或路径。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
