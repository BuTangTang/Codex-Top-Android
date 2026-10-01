package com.butang.codextop;
import com.google.gson.JsonParser;
public final class SessionStatusTest {
    /** 运行状态事实回归；runtime-lock 模式额外验证真实 Runtime 的已恢复会话快读。 */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "runtime-lock".equals(args[0])) {
            restoredSessionReadDoesNotWaitForConnection();
            System.out.println("CodexRuntime: 连接锁占用期间已恢复会话快读通过");
            return;
        }
        check("{\"ok\":true,\"machineOnline\":true,\"runnerActive\":true,\"activity\":\"running\"}", "状态未知");
        check("{\"ok\":true,\"machineOnline\":false}", "电脑离线");
        String base = "{\"ok\":true,\"machineOnline\":true,\"observation\":";
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"running\"}}", "运行中");
        check(base + "{\"v\":1,\"source\":\"rollout\",\"turnId\":\"t\",\"state\":\"completed\"}}", "已完成");
        check(base + "{\"v\":1,\"source\":\"desktop\",\"state\":\"completed\"}}", "状态未知");
        check(base + "{\"v\":2,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"completed\"}}", "状态未知");
        check(base + "{\"v\":1,\"state\":\"unknown\"}}", "状态未知");
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"needs_input\",\"requests\":[]}}", "状态未知");
        check(base + "{\"v\":1,\"state\":\"unknown\",\"reason\":\"not_observed\"}}", "同步中");
        for (String reason : new String[]{"source_unavailable", "connection_closed", "owner_changed",
                "incompatible_protocol", "invalid_snapshot", "revision_gap", "missing_turn_id", "unsupported_request"}) {
            check(base + "{\"v\":1,\"state\":\"unknown\",\"reason\":\"" + reason + "\"}}", "状态未知");
        }
        check("{\"ok\":true,\"machineOnline\":false,\"observation\":{\"v\":1,\"state\":\"unknown\",\"reason\":\"not_observed\"}}", "电脑离线");
        check(base + "{\"v\":2,\"state\":\"unknown\",\"reason\":\"not_observed\"}}", "状态未知");
        for (String state : new String[]{"failed", "cancelled", "needs_input"}) {
            String expected = state.equals("failed") ? "执行失败" : state.equals("cancelled") ? "已取消" : "待批准";
            check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"" + state
                    + "\",\"requests\":[{\"requestId\":\"r\",\"kind\":\"permission_request\"}]}}", expected);
        }
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"needs_input\",\"requests\":[{}]}}", "状态未知");
        // 列表只消费批量候选事实，未读、mtime 和最近活动都不能代替它。
        check("{\"updatedAtMs\":999999,\"activity\":\"running\",\"details\":{\"codexLifecycle\":{\"v\":1,\"state\":\"completed\",\"eventAtMs\":1000,\"checkedAtMs\":2000}}}", "已完成");
        check("{\"details\":{\"codexLifecycle\":{\"v\":1,\"state\":\"running\",\"eventAtMs\":3000,\"checkedAtMs\":2000}}}", "状态未知");
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"needs_input\",\"requests\":[{\"requestId\":\"q\",\"kind\":\"user_action_request\"}]}}", "待你回复");
        pendingQuestionIdentities();
        listAndConversationShareFacts();
        expirationAndRecovery();
        listFailureDoesNotDisconnectNewerObservations();
        invalidLabelsKeepLastKnownFact();
        System.out.println("SessionStatus: 生命周期事实、缺失身份、未知协议和连接状态区分通过");
    }

    /** 原待答身份只来自实际提问请求，快照不可变且失效不能沿用为当前事实。 */
    private static void pendingQuestionIdentities() {
        SessionStatus.Store store = new SessionStatus.Store();
        var response = JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"needs_input\",\"requests\":[{\"requestId\":\"a\",\"kind\":\"user_action_request\"},{\"requestId\":\"b\",\"kind\":\"user_action_request\"},{\"requestId\":\"approval\",\"kind\":\"permission_request\"}]}}").getAsJsonObject();
        store.observation(1, "machine", response, 100, 110);
        var first = store.get(1, 111);
        if (!first.questionIds.equals(java.util.Set.of("a", "b"))) throw new AssertionError("待答身份丢失或混入审批");
        try { first.questionIds.add("bad"); throw new AssertionError("外部可改写状态身份"); }
        catch (UnsupportedOperationException expected) { }
        response.getAsJsonObject("observation").getAsJsonArray("requests").remove(0);
        store.observation(1, "machine", response, 120, 130);
        if (!store.get(1, 131).questionIds.equals(java.util.Set.of("b")) || first.questionIds.size() != 2)
            throw new AssertionError("部分已答未更新或污染原快照");
        store.unavailable("machine", 140);
        if ("current".equals(store.get(1, 141).validity)) throw new AssertionError("失效身份仍作为当前待答");
    }

    /** LIST 迟到失败不能抹去更新的 STATUS，也不能阻断已经发出的下一条有效观察。 */
    private static void listFailureDoesNotDisconnectNewerObservations() {
        SessionStatus.Store store = new SessionStatus.Store();
        store.candidate(1, "machine", candidate("running", ""), 100, 110, false);
        store.candidate(2, "machine", candidate("running", ""), 100, 110, false);
        store.candidate(3, "other", candidate("completed", ""), 100, 110, false);
        var completed = JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"completed\"}}").getAsJsonObject();
        // LIST 在200开始，到500才失败；300开始的 STATUS 已经先返回。
        store.observation(1, "machine", completed, 300, 310);
        store.listFailed("machine", 200);
        snapshot(store, 1, 501, "completed", "none", "current");
        snapshot(store, 2, 501, "running", "none", "unavailable");
        snapshot(store, 3, 501, "completed", "none", "current");
        // 比失败 LIST 还早的慢观察也不能复活被失效的条目。
        store.observation(2, "machine", completed, 150, 505);
        snapshot(store, 2, 506, "running", "none", "unavailable");
        // 400开始的 STATUS 在旧 LIST 失败后才到；它仍比原 LIST 新，必须可以恢复。
        store.observation(2, "machine", completed, 400, 510);
        snapshot(store, 2, 511, "completed", "none", "current");
        store.unavailable("machine", 520);
        store.observation(1, "machine", completed, 510, 530);
        snapshot(store, 1, 531, "completed", "none", "unavailable");
        store.observation(1, "machine", completed, 540, 550);
        snapshot(store, 1, 551, "completed", "none", "current");
    }

    /** 合成账号只注入独立 JVM 内存；持有连接使用的同一类锁，确认 UI 判断无需等锁。 */
    private static void restoredSessionReadDoesNotWaitForConnection() throws Exception {
        Class<?> runtime = Class.forName("com.butang.codextop.CodexRuntime");
        Class<?> sessionType = Class.forName("com.butang.codextop.PasswordLogin$Session");
        var constructor = sessionType.getDeclaredConstructor(String.class, String.class, String.class, String.class, byte[].class);
        constructor.setAccessible(true);
        Object syntheticSession = constructor.newInstance("https://test.invalid", "synthetic", "test-account", "test", new byte[32]);
        var restored = runtime.getDeclaredField("sessionRestored"); restored.setAccessible(true);
        var session = runtime.getDeclaredField("session"); session.setAccessible(true);
        var loggingOut = runtime.getDeclaredField("loggingOut"); loggingOut.setAccessible(true);
        var loggedIn = runtime.getMethod("loggedIn");
        restored.setBoolean(null, true);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int scenario = 0; scenario < 3; scenario++) {
                session.set(null, scenario == 1 ? null : syntheticSession);
                loggingOut.setBoolean(null, scenario == 2);
                synchronized (runtime) {
                    var entered = new java.util.concurrent.CountDownLatch(1);
                    var result = executor.submit(() -> {
                        entered.countDown();
                        return (Boolean) loggedIn.invoke(null);
                    });
                    if (!entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("测试读取线程未启动");
                    try {
                        if (result.get(1, java.util.concurrent.TimeUnit.SECONDS) != (scenario == 0))
                            throw new AssertionError("已恢复会话快读返回了错误的登录状态");
                    } catch (java.util.concurrent.TimeoutException error) {
                        throw new AssertionError("已恢复会话的 UI 判断被后台连接类锁阻塞", error);
                    }
                }
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            session.set(null, null);
            loggingOut.setBoolean(null, false);
            ((AutoCloseable) syntheticSession).close();
        }
    }

    /** 未打开详情的列表和退出详情后的列表沿同一事实入口变化，待办类型不取自文字。 */
    private static void listAndConversationShareFacts() {
        SessionStatus.Store store = new SessionStatus.Store();
        store.candidate(1, "machine", candidate("running", ""), 100, 110, false);
        snapshot(store, 1, 111, "running", "none", "current");
        store.candidate(1, "machine", candidate("needs_input", "user_action_request"), 200, 210, false);
        snapshot(store, 1, 211, "needs_input", "question", "current");
        store.candidate(2, "other", candidate("completed", ""), 200, 210, false);
        var both = candidate("needs_input", "user_action_request");
        both.getAsJsonObject("details").getAsJsonObject("codexObservation").getAsJsonArray("requests")
                .add(JsonParser.parseString("{\"requestId\":\"approve\",\"kind\":\"permission_request\"}"));
        store.candidate(1, "machine", both, 300, 310, false);
        snapshot(store, 1, 311, "needs_input", "mixed", "current");
        var response = JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"running\"}}").getAsJsonObject();
        store.observation(1, "machine", response, 400, 410);
        // 较早发出的列表在聊天观察之后才返回，不得覆盖新事实。
        store.candidate(1, "machine", candidate("completed", ""), 350, 450, false);
        snapshot(store, 1, 451, "running", "none", "current");
        // 离开详情后不再调用 observation，只靠原列表刷新仍能完成转换。
        store.candidate(1, "machine", candidate("completed", ""), 500, 510, false);
        snapshot(store, 1, 511, "completed", "none", "current");
        var mismatch = candidate("completed", "");
        mismatch.getAsJsonObject("details").getAsJsonObject("codexObservation").addProperty("state", "running");
        store.candidate(1, "machine", mismatch, 600, 610, false);
        snapshot(store, 1, 611, "unknown", "unknown", "unknown");
        snapshot(store, 2, 611, "completed", "none", "current");
        var oldProducer = candidate("needs_input", "user_action_request");
        oldProducer.getAsJsonObject("details").remove("codexObservation");
        store.candidate(1, "machine", oldProducer, 700, 710, false);
        snapshot(store, 1, 711, "needs_input", "unknown", "current");
    }

    /** 15秒交界、慢回包、缓存恢复、断连、换账号均不得延长旧事实。 */
    private static void expirationAndRecovery() {
        SessionStatus.Store store = new SessionStatus.Store();
        var completed = candidate("completed", "");
        store.candidate(1, "machine", completed, 100, 110, false);
        snapshot(store, 1, 15099, "completed", "none", "current");
        snapshot(store, 1, 15100, "completed", "none", "stale");
        // 请求直到有效期之后才回包：回包时刻不能重新起15秒有效期。
        store.candidate(1, "machine", completed, 16000, 32000, false);
        snapshot(store, 1, 32000, "completed", "none", "stale");
        store.candidate(1, "machine", candidate("running", ""), 33000, 33010, false);
        store.candidate(2, "other", completed, 33000, 33010, false);
        store.unavailable("machine", 34000);
        store.candidate(1, "machine", completed, 33500, 35000, false);
        snapshot(store, 1, 35000, "running", "none", "unavailable");
        snapshot(store, 2, 35000, "completed", "none", "current");
        store.candidate(1, "machine", completed, 36000, 36010, false);
        snapshot(store, 1, 36011, "completed", "none", "current");
        if (store.get(1, 36011).checkedAtMs != 200000 || store.get(1, 36011).observedAtElapsedMs != 36010)
            throw new AssertionError("跨端来源时间与本机接收时刻混用");
        store.clear();
        snapshot(store, 1, 37000, "unknown", "unknown", "syncing");
        store.candidate(3, "machine", JsonParser.parseString("{\"remoteSessionId\":\"old-cache-without-facts\"}").getAsJsonObject(), -1, -1, true);
        snapshot(store, 3, 37000, "unknown", "unknown", "unknown");
        store.candidate(1, "machine", completed, -1, -1, true);
        snapshot(store, 1, 37001, "completed", "none", "stale");
        store.candidate(1, "machine", candidate("running", ""), 38000, 38010, false);
        store.candidate(1, "machine", completed, -1, -1, true);
        snapshot(store, 1, 38011, "running", "none", "current");
        // 与 elapsedRealtime 一样计入深睡，恢复后旧观察不自动复活。
        snapshot(store, 1, 900000, "running", "none", "stale");
        store.candidate(1, "machine", JsonParser.parseString("{\"activity\":\"running\",\"updatedAtMs\":999999}").getAsJsonObject(), 900001, 900002, false);
        snapshot(store, 1, 900003, "unknown", "unknown", "unknown");
    }

    /** 冷缓存和失效只补上次事实说明，不续有效期、嵌套前缀或恢复旧待办权限。 */
    private static void invalidLabelsKeepLastKnownFact() {
        SessionStatus.Store store = new SessionStatus.Store();
        String[][] facts = {
                {"completed", "", "已完成"}, {"running", "", "运行中"},
                {"failed", "", "执行失败"}, {"cancelled", "", "已取消"},
                {"needs_input", "user_action_request", "待你回复"},
                {"needs_input", "permission_request", "待批准"}
        };
        for (int i = 0; i < facts.length; i++) {
            String[] fact = facts[i];
            long id = i + 1;
            store.candidate(id, "machine", candidate(fact[0], fact[1]), -1, -1, true);
            label(store, id, 100, "上次：" + fact[2] + " · 状态未更新");
            if (!"stale".equals(store.get(id, 100).validity)) throw new AssertionError("缓存状态重新获得有效期");
        }
        var before = store.get(5, 100);
        store.listFailed("machine", 200);
        label(store, 1, 201, "上次：已完成 · 状态暂不可用");
        store.unavailable("machine", 300);
        store.unavailable("machine", 301);
        label(store, 5, 302, "上次：待你回复 · 连接暂不可用");
        var after = store.get(5, 302);
        if (!"unavailable".equals(after.validity) || !before.state.equals(after.state)
                || !before.pendingKind.equals(after.pendingKind) || !before.source.equals(after.source)
                || !before.turnId.equals(after.turnId) || !before.questionIds.equals(after.questionIds)
                || before.eventAtMs != after.eventAtMs || before.checkedAtMs != after.checkedAtMs
                || before.observedAtElapsedMs != after.observedAtElapsedMs)
            throw new AssertionError("失效文案改变了原事实、来源或有效性");
        store.candidate(1, "machine", candidate("running", ""), 400, 410, false);
        label(store, 1, 15399, "运行中");
        snapshot(store, 1, 15399, "running", "none", "current");
        label(store, 1, 15400, "上次：运行中 · 状态已过期");
        label(store, 1, 15401, "上次：运行中 · 状态已过期");
        snapshot(store, 1, 15401, "running", "none", "stale");
        store.candidate(1, "machine", candidate("completed", ""), 16000, 16010, false);
        label(store, 1, 16011, "已完成");
        store.candidate(1, "machine", JsonParser.parseString("{\"activity\":\"running\"}").getAsJsonObject(), 17000, 17010, false);
        label(store, 1, 17011, "状态未知");
        store.listFailed("machine", 17100);
        label(store, 1, 17101, "状态暂不可用");
        store.unavailable("machine", 17200);
        label(store, 1, 17201, "连接暂不可用");
        label(store, 99, 17201, "同步中");
    }

    /** 断言真实快照的原位文案，不根据展示文字推断状态。 */
    private static void label(SessionStatus.Store store, long dialogId, long now, String expected) {
        String actual = store.get(dialogId, now).label;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    /** 合成同次候选事实，使用独立于手机时间的电脑时间域。 */
    private static com.google.gson.JsonObject candidate(String state, String kind) {
        var result = JsonParser.parseString("{\"remoteSessionId\":\"remote\",\"updatedAtMs\":999999,\"activity\":\"running\",\"details\":{\"codexLifecycle\":{\"v\":1,\"state\":\"" + state
                + "\",\"eventAtMs\":100000,\"checkedAtMs\":200000},\"codexObservation\":{\"v\":1,\"source\":\"rollout\",\"turnId\":\"t\",\"state\":\"" + state + "\"}}}").getAsJsonObject();
        if (!kind.isEmpty()) result.getAsJsonObject("details").getAsJsonObject("codexObservation").add("requests",
                JsonParser.parseString("[{\"requestId\":\"r\",\"kind\":\"" + kind + "\"}]"));
        return result;
    }

    /** 验证只读类型契约，UI 无须根据中文标签猜测。 */
    private static void snapshot(SessionStatus.Store store, long dialogId, long now, String state, String pending, String validity) {
        var actual = store.get(dialogId, now);
        if (!state.equals(actual.state) || !pending.equals(actual.pendingKind) || !validity.equals(actual.validity))
            throw new AssertionError(actual.state + "/" + actual.pendingKind + "/" + actual.validity);
    }

    /** 保留原标签入口的兼容断言，真实分支仍由统一事实解析器执行。 */
    private static void check(String input, String expected) {
        if (!expected.equals(SessionStatus.label(JsonParser.parseString(input).getAsJsonObject()))) throw new AssertionError(expected);
    }
}
