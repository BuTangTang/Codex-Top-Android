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
        goalFacts();
        cacheFacts();
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
    /** 通过真实Store方法验证目标独立事实；修复前反射缺字段仍执行原STATUS后给出行为RED。 */
    private static void goalFacts() throws Exception {
        SessionStatus.Store store=new SessionStatus.Store();
        observeGoal(store,1,"machine","remote",available("active","0","null","0","50"),100,110);
        goalField(store,1,111,"availability","available");
        goalField(store,1,111,"validity","current");
        goalField(store,1,111,"tokensUsed",null);
        goalField(store,1,111,"tokenBudget",0L);
        goalField(store,1,111,"timeUsedSeconds",0L);
        // LIST生命周期独立变更，无goal字段不能抹掉或续期原STATUS目标。
        store.candidate(1,"machine",candidate("completed",""),10000,10010,false);
        goalField(store,1,14999,"validity","current");
        goalField(store,1,15100,"validity","stale");
        goalField(store,1,15100,"startedAtElapsedMs",100L);
        Object oldGoal=goal(store,1,15100);
        if(!String.valueOf(field(oldGoal,"label")).startsWith("上次："))throw new AssertionError("旧目标伪装为当前");
        // lifecycle较新的LIST不能挡住独立较新的目标STATUS。
        observeGoal(store,1,"machine","remote",available("paused","9007199254740991","1","2","0"),9000,9001);
        goalField(store,1,10011,"status","paused");
        snapshot(store,1,10011,"completed","none","current");
        // 明确unknown保留上次值但不可当前；迟到较早目标不能复活。
        observeGoal(store,1,"machine","remote","{\"availability\":\"unknown\"}",11000,11001);
        goalField(store,1,11002,"validity","unknown");
        goalField(store,1,11002,"status","paused");
        observeGoal(store,1,"machine","remote",available("active","1","1","1","0"),10000,12000);
        goalField(store,1,12001,"status","paused");
        // 老daemon字段缺失与明确none不同，只有none清除旧目标。
        observeGoal(store,1,"machine","remote",null,12000,12001);
        goalField(store,1,12002,"validity","unsupported");
        goalField(store,1,12002,"status","paused");
        observeGoal(store,1,"machine","remote","{\"availability\":\"none\",\"source\":\"desktop\"}",13000,13001);
        goalField(store,1,13002,"availability","none");
        goalField(store,1,13002,"objective","");
        // 目标有效性不依赖生命周期turn：unknown observation仍接受已验证目标。
        observeGoal(store,2,"machine","remote",available("complete","null","0","0","0"),100,101);
        snapshot(store,2,102,"unknown","unknown","unknown");
        goalField(store,2,102,"validity","current");
        goalField(store,2,102,"status","complete");
        var completed=JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"source\":\"desktop\",\"turnId\":\"real-turn\",\"state\":\"completed\"},\"goal\":"+available("active","0","0","0","0")+"}").getAsJsonObject();
        SessionStatus.Store.class.getMethod("observation",long.class,String.class,String.class,com.google.gson.JsonObject.class,long.class,long.class).invoke(store,6L,"machine","remote",completed,100L,101L);
        snapshot(store,6,102,"completed","none","current");goalField(store,6,102,"status","active");goalField(store,6,102,"validity","current");
        store.listFailed("machine",500);goalField(store,2,501,"validity","current");
        store.unavailable("machine",600);goalField(store,2,601,"validity","unavailable");
        observeGoal(store,2,"machine","remote",available("active","1","1","1","0"),550,602);
        goalField(store,2,603,"status","complete");
        // 只读协议严格检查安全整数、字段集与remote/source，不能把坏值当无目标。
        for(String raw:new String[]{"-1","1.5","9007199254740992","1e100","\"0\"","true","{}","[]"}){
            observeGoal(store,3,"machine","remote",available("active",raw,"1","1","0"),1000,1001);
            goalField(store,3,1002,"availability","unknown");
        }
        for(String bad:new String[]{available("active","1","1","1","null"),
                available("active","1","1","1","0").replace("remote","linked"),
                available("active","1","1","1","0").replace("desktop","rollout"),
                available("active","1","1","1","0").replace("active","running"),
                available("active","1","1","1","0").replace("objective\":\"真实目标","objective\":\"   "),
                "{\"availability\":\"none\",\"source\":\"desktop\",\"threadId\":\"remote\"}",
                "{\"availability\":\"unknown\",\"source\":\"desktop\"}"}){
            observeGoal(store,4,"machine","remote",bad,1000,1001);goalField(store,4,1002,"availability","unknown");
        }
        observeGoal(store,5,"machine","remote",available("blocked","0","0","0","0"),1000,1001);
        goalField(store,5,999,"validity","stale");
        store.candidate(5,"other-machine",candidate("running",""),2000,2001,false);
        goalField(store,5,2002,"availability","unsupported");
        store.clear();goalField(store,5,2003,"availability","unsupported");
        System.out.println("SessionStatus goal: 原STATUS独立15秒、LIST不续期、unknown/none/缺失、严格安全整数与来源通过");
    }

    /** 冷盘只填尚未被当前 available/none 确认的目标槽，不改请求顺序、提问身份或生命周期。 */
    private static void cacheFacts() throws Exception {
        SessionStatus.Store store=new SessionStatus.Store();
        store.candidate(1,"machine",candidate("running",""),10,11,true);
        var before=store.get(1,12);
        check(offer(store,1,"machine","available","磁盘目标","active",1L,null,2L,9), "未触碰目标拒绝上次可用目标");
        goalField(store,1,13,"availability","available");
        goalField(store,1,13,"validity","stale");
        goalField(store,1,13,"objective","磁盘目标");
        goalField(store,1,13,"startedAtElapsedMs",-1L);
        Object cached=goal(store,1,13);
        String label=String.valueOf(field(cached,"label"));
        check(label.contains("上次")&&label.contains("目标进行中")&&label.contains("目标信息暂不可用"), "上次目标缺少失效原因");
        var after=store.get(1,13);
        check(before.state.equals(after.state)&&before.questionIds.equals(after.questionIds)&&before.observedAtElapsedMs==after.observedAtElapsedMs
                &&!"current".equals(after.goal.validity), "冷盘改写生命周期、提问或把缓存标成当前");
        store.candidate(1,"machine",candidate("completed",""),1000,1010,false);
        goalField(store,1,20000,"validity","stale");
        goalField(store,1,20000,"startedAtElapsedMs",-1L);
        goalField(store,1,20000,"objective","磁盘目标");
        store.candidate(2,"machine",candidate("running",""),10,11,true);
        check(offer(store,2,"machine","none","","",null,null,null,-1), "未触碰目标拒绝上次明确无目标");
        goalField(store,2,12,"availability","none");
        goalField(store,2,12,"validity","stale");
        goalField(store,2,12,"objective","");
        goalField(store,2,12,"startedAtElapsedMs",-1L);
        check(String.valueOf(field(goal(store,2,12),"label")).contains("没有目标"), "上次无目标被写成可用目标");
        check(!offer(store,2,"other","available","别的电脑","active",null,null,null,1), "其他电脑缓存写入本对话");
        SessionStatus.Store live=new SessionStatus.Store();
        observeGoal(live,3,"machine","remote","{\"availability\":\"unknown\"}",100,110);
        check(offer(live,3,"machine","available","上次目标","paused",null,4L,null,8), "未知事实之前的冷盘不能补上次目标");
        goalField(live,3,113,"objective","上次目标");
        goalField(live,3,113,"validity","stale");
        goalField(live,3,113,"startedAtElapsedMs",-1L);
        check(String.valueOf(field(goal(live,3,113),"label")).contains("目标未知"), "冷盘丢掉本次未知原因");
        observeGoal(live,3,"machine","remote",available("active","1","1","1","0").replace("真实目标","更早目标"),99,120);
        goalField(live,3,121,"objective","上次目标");
        observeGoal(live,3,"machine","remote",available("active","1","1","1","0").replace("真实目标","新的当前目标"),101,130);
        goalField(live,3,131,"objective","新的当前目标");
        goalField(live,3,131,"validity","current");
        var response=JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"source\":\"desktop\",\"turnId\":\"turn\",\"state\":\"needs_input\",\"requests\":[{\"requestId\":\"q1\",\"kind\":\"user_action_request\"}]},\"goal\":{\"availability\":\"unknown\"}}").getAsJsonObject();
        live.observation(4,"machine","remote",response,100,111);
        var pending=live.get(4,112);
        check(offer(live,4,"machine","available","上次目标","paused",null,4L,null,8), "待回复槽拒绝上次目标");
        var restored=live.get(4,112);
        check(pending.state.equals(restored.state)&&pending.pendingKind.equals(restored.pendingKind)&&pending.validity.equals(restored.validity)
                &&pending.source.equals(restored.source)&&pending.turnId.equals(restored.turnId)&&pending.label.equals(restored.label)
                &&pending.eventAtMs==restored.eventAtMs&&pending.checkedAtMs==restored.checkedAtMs
                &&pending.observedAtElapsedMs==restored.observedAtElapsedMs&&pending.questionIds.equals(restored.questionIds)
                &&pending.questionIds.contains("q1")&&"needs_input".equals(restored.state), "冷盘改写了同一待回复槽的原字段");
        goalField(live,4,112,"objective","上次目标");
        goalField(live,4,112,"validity","stale");
        goalField(live,4,112,"startedAtElapsedMs",-1L);
        observeGoal(live,5,"machine","remote","{\"availability\":\"none\",\"source\":\"desktop\"}",200,201);
        observeGoal(live,5,"machine","remote","{\"availability\":\"unknown\"}",210,211);
        check(!offer(live,5,"machine","available","旧磁盘目标","active",null,null,null,1), "none之后的unknown仍接受旧磁盘目标");
        goalField(live,5,212,"objective","");
        goalField(live,5,212,"availability","unknown");
        observeGoal(live,6,"machine","remote",available("active","1","1","1","0"),300,301);
        check(!offer(live,6,"machine","available","迟到磁盘","paused",null,null,null,1), "当前目标被迟到磁盘覆盖");
        goalField(live,6,302,"objective","真实目标");
        goalField(live,6,302,"status","active");
        observeGoal(live,7,"machine","remote","{\"availability\":\"none\",\"source\":\"desktop\"}",400,401);
        check(!offer(live,7,"machine","available","迟到磁盘","active",null,null,null,1), "明确无目标被迟到磁盘复活");
        goalField(live,7,402,"availability","none");
        live.candidate(8,"machine",candidate("running",""),10,11,true);
        live.unavailable("machine",12);
        check(offer(live,8,"machine","available","断连前上次","blocked",null,null,null,3), "断连未知槽拒绝上次目标");
        check(String.valueOf(field(goal(live,8,13),"label")).contains("连接暂不可用")
                &&"stale".equals(String.valueOf(field(goal(live,8,13),"validity"))), "断连冷盘缺少实际不可用原因");
        StringBuilder longGoal=new StringBuilder();
        for(int i=0;i<10001;i++)longGoal.append('长');
        observeGoal(live,9,"machine","remote",available("active","1","1","1","0").replace("真实目标",longGoal.toString()),500,501);
        goalField(live,9,502,"objective",longGoal.toString());
        goalField(live,9,502,"validity","current");
        SessionStatus.Store once=new SessionStatus.Store();
        once.candidate(10,"machine",candidate("running",""),10,11,true);
        check(once.claimGoalRestore(10,"machine")&&!once.claimGoalRestore(10,"machine"), "第一次冷恢复没有认领或重复认领");
        observeGoal(once,10,"machine","remote","{\"availability\":\"unknown\"}",20,21);
        check(!once.claimGoalRestore(10,"machine"), "未知合并清掉了冷恢复认领");
        once.listFailed("machine",40);
        check(!once.claimGoalRestore(10,"machine"), "列表失败重新认领冷恢复");
        once.candidate(10,"machine",candidate("completed",""),1000,1010,false);
        check(!once.claimGoalRestore(10,"machine"), "再次列表重新认领冷恢复");
        check(offer(once,10,"machine","available","只读一次","active",null,null,null,1), "已认领的未确认槽不能接受第一次冷盘");
        goalField(once,10,20000,"validity","stale");
        goalField(once,10,20000,"startedAtElapsedMs",-1L);
        goalField(once,10,20000,"objective","只读一次");
        once.candidate(10,"machine",candidate("running",""),3000,3010,false);
        check(!once.claimGoalRestore(10,"machine"), "恢复后的列表再次读盘");
        goalField(once,10,40000,"validity","stale");
        goalField(once,10,40000,"startedAtElapsedMs",-1L);
        goalField(once,10,40000,"objective","只读一次");
        SessionStatus.Store dropped=new SessionStatus.Store();
        dropped.candidate(10,"machine",candidate("running",""),10,11,true);
        check(dropped.claimGoalRestore(10,"machine"), "断连前不能认领");
        dropped.unavailable("machine",50);
        check(!dropped.claimGoalRestore(10,"machine"), "断连清掉冷恢复认领并导致重读");
        dropped.candidate(10,"other-machine",candidate("running",""),60,61,false);
        check(dropped.claimGoalRestore(10,"other-machine")&&!dropped.claimGoalRestore(10,"other-machine"), "换电脑没有重新开始一次冷恢复");
        dropped.clear();
        dropped.candidate(10,"machine",candidate("running",""),70,71,true);
        check(dropped.claimGoalRestore(10,"machine"), "清空账号后不能重新认领");
        System.out.println("SessionStatus goal cache: 冷盘available/none、身份不匹配、当前事实优先与长目标展示通过");
    }

    /** 布尔结果失败直接抛出真实边界。 */
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }

    /** 调用原Store的冷盘入口；修复前缺少该方法即失败。 */
    private static boolean offer(SessionStatus.Store store,long id,String machine,String availability,String objective,String status,Long budget,Long tokens,Long seconds,long updated) throws Exception {
        return (boolean)SessionStatus.Store.class.getMethod("offerCachedGoal",long.class,String.class,String.class,String.class,String.class,Long.class,Long.class,Long.class,long.class)
                .invoke(store,id,machine,availability,objective,status,budget,tokens,seconds,updated);
    }

    /** 只构造协议输入，不复制生产目标判定。 */
    private static String available(String status,String budget,String tokens,String seconds,String updated) {
        return "{\"availability\":\"available\",\"source\":\"desktop\",\"threadId\":\"remote\",\"objective\":\"真实目标\",\"status\":\""+status
                +"\",\"tokenBudget\":"+budget+",\"tokensUsed\":"+tokens+",\"timeUsedSeconds\":"+seconds+",\"updatedAt\":"+updated+"}";
    }

    /** 修复前真实旧入口仍执行，修复后绑定实际remote身份的新入口。 */
    private static void observeGoal(SessionStatus.Store store,long id,String machine,String remote,String goal,long start,long received) throws Exception {
        var response=JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"state\":\"unknown\",\"reason\":\"missing_turn_id\"}"+(goal==null?"":",\"goal\":"+goal)+"}").getAsJsonObject();
        try {SessionStatus.Store.class.getMethod("observation",long.class,String.class,String.class,com.google.gson.JsonObject.class,long.class,long.class).invoke(store,id,machine,remote,response,start,received);}
        catch(NoSuchMethodException absent){store.observation(id,machine,response,start,received);}
    }

    /** 读取真实不可变快照，缺字段给行为断言而非编译失败。 */
    private static Object goal(SessionStatus.Store store,long id,long now) throws Exception {
        try{return store.get(id,now).getClass().getField("goal").get(store.get(id,now));}
        catch(NoSuchFieldException absent){throw new AssertionError("实际STATUS忽略目标事实，LIST无独立目标有效期",absent);}
    }

    /** 比较原事实字段，包括零值与明确null。 */
    private static Object field(Object goal,String name) throws Exception {return goal.getClass().getField(name).get(goal);}
    /** 所有判定均由真实Store进行，测试仅检查可观察结果。 */
    private static void goalField(SessionStatus.Store store,long id,long now,String name,Object expected) throws Exception {
        if(!java.util.Objects.equals(expected,field(goal(store,id,now),name)))throw new AssertionError("goal "+name+" expected="+expected+" actual="+field(goal(store,id,now),name));
    }

}
