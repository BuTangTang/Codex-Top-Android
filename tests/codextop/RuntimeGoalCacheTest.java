package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.google.gson.JsonParser;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实入队与冷恢复，只替换队列、账号和来源描述；不复制目标事实或原子写。 */
public final class RuntimeGoalCacheTest {
    /** 临时目录执行原方法。缺少入队或恢复方法时直接失败。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"));
        StringBuilder extracted = new StringBuilder();
        var names = Set.of("rememberAcceptedGoal", "scheduleGoalRestore", "goalDisplayStore", "goalCacheIdentityCurrent", "isAccountCurrent", "stopWatching", "releaseObservation", "publishDialogFacts", "candidateDirectory", "logout");
        int found = 0;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class))
            if (names.contains(method.getNameAsString())) { extracted.append(method).append('\n'); found++; }
        if (found != names.size()) throw new AssertionError("原目标缓存入队或恢复方法缺失");
        Path temporary = Files.createTempDirectory("codex-goal-runtime-");
        try {
            Path probe = temporary.resolve("RuntimeGoalCacheProbe.java");
            Files.writeString(probe, FIXTURE + extracted + scenarios(temporary) + "\n}");
            Path user = temporary.resolve("UserConfig.java");
            Path center = temporary.resolve("NotificationCenter.java");
            Path messages = temporary.resolve("MessagesController.java");
            Files.writeString(user, "package org.telegram.messenger; public class UserConfig { public static int selectedAccount; }");
            Files.writeString(center, "package org.telegram.messenger; public class NotificationCenter { public static int posts; public static final int updateInterfaces=1; public static NotificationCenter getInstance(int a){return new NotificationCenter();} public void postNotificationName(int id,int mask){posts++;} }");
            Files.writeString(messages, "package org.telegram.messenger; public class MessagesController { public static final int UPDATE_MASK_STATUS=8; public final java.util.Map<Long,Object> dialogMessage=new java.util.HashMap<>(); public static MessagesController getInstance(int account){return new MessagesController();} }");
            Path queues = temporary.resolve("DispatchQueue.java");
            Path runtimeLock = temporary.resolve("CodexRuntime.java");
            Files.writeString(queues, "package org.telegram.messenger; import java.util.ArrayDeque; public class DispatchQueue { public final ArrayDeque<Runnable> tasks=new ArrayDeque<>(); public DispatchQueue(String name){} public void postRunnable(Runnable runnable){tasks.add(runnable);} public void postRunnable(Runnable runnable,long delay){tasks.add(runnable);} public void cancelRunnable(Runnable runnable){tasks.remove(runnable);} public void next(){tasks.remove().run();} public void all(){int guard=80; while(!tasks.isEmpty()){if(--guard==0)throw new AssertionError(\"queue loop\"); next();}} }");
            Files.writeString(runtimeLock, "package com.butang.codextop; public class CodexRuntime {}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), user.toString(), center.toString(), messages.toString(), queues.toString(), runtimeLock.toString()) != 0)
                throw new AssertionError("原目标缓存方法夹具编译失败");
            var urls = new java.util.ArrayList<java.net.URL>();
            urls.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimeGoalCacheProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("原目标缓存回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** 场景只安排原方法顺序；合并、守卫和写盘都在生产代码里。 */
    private static String scenarios(Path root) {
        return """
            public static void main(String[] args) throws Exception {
                Path root = Path.of("%s");
                ApplicationLoader.applicationContext = new Context(root.toFile());
                SessionStatus.onConfirmedGoal(RuntimeGoalCacheProbe::rememberAcceptedGoal);
                PasswordLogin.Session owner = new PasswordLogin.Session();
                session = owner; accountGeneration = 1; loggingOut = false;
                dialogMachines.put(7L, "machine"); remoteIds.put(7L, "remote");
                statuses.candidate(7, "machine", JsonParser.parseString("{\\"remoteSessionId\\":\\"remote\\",\\"title\\":\\"t\\",\\"updatedAtMs\\":1,\\"activity\\":\\"running\\",\\"details\\":{\\"codexLifecycle\\":{\\"v\\":1,\\"state\\":\\"running\\",\\"eventAtMs\\":1,\\"checkedAtMs\\":1},\\"codexObservation\\":{\\"v\\":1,\\"source\\":\\"rollout\\",\\"turnId\\":\\"t\\",\\"state\\":\\"running\\"}}}" ).getAsJsonObject(), 10, 11, true);
                goalDisplayStore(owner).save("machine", "remote", "codexHome", "user", "available", "磁盘目标", "active", null, null, null, 4L, null);
                dialogMachines.put(11L, "machine"); remoteIds.put(11L, "remote-11");
                goalDisplayStore(owner).save("machine", "remote-11", "codexHome", "user", "available", "列表目标", "paused", null, null, null, 2L, null);
                publishDialogFacts(11, "machine", row("remote-11"), 10, 11, true);
                check(Utilities.globalQueue.tasks.size() == 1, "第一次列表没有安排冷恢复");
                publishDialogFacts(11, "machine", row("remote-11"), 12, 13, false);
                check(Utilities.globalQueue.tasks.size() == 1, "重复列表再次读取冷盘");
                Utilities.globalQueue.next(); AndroidUtilities.ui.next();
                check("列表目标".equals(statuses.get(11, 14).goal.objective) && "stale".equals(statuses.get(11, 14).goal.validity)
                        && statuses.get(11, 14).goal.startedAtElapsedMs == -1, "第一次冷恢复没有保持过期");
                int listed = Utilities.globalQueue.tasks.size();
                publishDialogFacts(11, "machine", row("remote-11"), 30, 31, false);
                check(Utilities.globalQueue.tasks.size() == listed && "stale".equals(statuses.get(11, 40000).goal.validity)
                        && statuses.get(11, 40000).goal.startedAtElapsedMs == -1 && "列表目标".equals(statuses.get(11, 40000).goal.objective), "恢复后的列表重读或续期");
                dialogMachines.put(8L, "machine"); remoteIds.put(8L, "remote-8");
                statuses.candidate(8, "machine", JsonParser.parseString("{\\"remoteSessionId\\":\\"remote-8\\",\\"title\\":\\"t\\",\\"updatedAtMs\\":1,\\"activity\\":\\"running\\",\\"details\\":{\\"codexLifecycle\\":{\\"v\\":1,\\"state\\":\\"running\\",\\"eventAtMs\\":1,\\"checkedAtMs\\":1},\\"codexObservation\\":{\\"v\\":1,\\"source\\":\\"rollout\\",\\"turnId\\":\\"t\\",\\"state\\":\\"running\\"}}}" ).getAsJsonObject(), 10, 11, true);
                goalDisplayStore(owner).save("machine", "remote-8", "codexHome", "user", "available", "旧磁盘", "paused", null, null, null, 3L, null);
                scheduleGoalRestore(8, "machine", "remote-8", owner, 1);
                Utilities.globalQueue.next();
                observeAt(8, availableOn("remote-8", "当前目标"), 150);
                AndroidUtilities.ui.next();
                check("当前目标".equals(statuses.get(8, 160).goal.objective) && "current".equals(statuses.get(8, 160).goal.validity), "新的当前目标被迟到磁盘覆盖");
                Utilities.globalQueue.all();
                observe("{\\"availability\\":\\"unknown\\"}", 100);
                check(Utilities.globalQueue.tasks.isEmpty(), "未知目标进入写盘队列");
                String preserved = Files.readString(goalFile());
                check(preserved.contains("磁盘目标"), "未知目标改写了已有投影");
                scheduleGoalRestore(7, "machine", "remote", owner, 1);
                Utilities.globalQueue.next(); AndroidUtilities.ui.next();
                check("磁盘目标".equals(statuses.get(7, 120).goal.objective) && "stale".equals(statuses.get(7, 120).goal.validity)
                        && statuses.get(7, 120).goal.startedAtElapsedMs == -1 && linkedSessions.isEmpty(), "未知之前的冷盘没有恢复或写入了linked");
                observe("{\\"availability\\":\\"none\\",\\"source\\":\\"desktop\\"}", 200);
                observe("{\\"availability\\":\\"unknown\\"}", 210);
                scheduleGoalRestore(7, "machine", "remote", owner, 1);
                Utilities.globalQueue.all(); AndroidUtilities.ui.all();
                check(!statuses.get(7, 220).goal.hasValue() && "unknown".equals(statuses.get(7, 220).goal.availability), "none之后的unknown被旧磁盘目标覆盖");
                Utilities.globalQueue.tasks.clear(); AndroidUtilities.ui.tasks.clear();
                observe(available("第一目标"), 300);
                check(Utilities.globalQueue.tasks.size() == 1, "可用目标没有入队");
                Utilities.globalQueue.next();
                check("available".equals(goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").availability)
                        && "第一目标".equals(goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").objective), "FIFO 第一步不是可用目标");
                observe("{\\"availability\\":\\"none\\",\\"source\\":\\"desktop\\"}", 310);
                check(Utilities.globalQueue.tasks.size() == 1, "明确无目标没有入队");
                Utilities.globalQueue.next();
                check("none".equals(goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").availability)
                        && goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").objective.isEmpty(), "FIFO 第二步不是明确无目标");
                observe(available("第二目标"), 320);
                check(Utilities.globalQueue.tasks.size() == 1, "再次可用没有入队");
                Utilities.globalQueue.next();
                check("available".equals(goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").availability)
                        && "第二目标".equals(goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").objective), "FIFO 第三步不是可用目标");
                observe("{\\"availability\\":\\"none\\",\\"source\\":\\"desktop\\"}", 330);
                watchedDialog = 7;
                stopWatching(7);
                check(Utilities.globalQueue.tasks.size() == 1, "返回取消了已确认的无目标写入");
                Utilities.globalQueue.next();
                check("none".equals(goalDisplayStore(owner).find("machine", "remote", "codexHome", "user").availability), "返回后没有落地明确无目标");
                dialogMachines.put(15L, "machine"); remoteIds.put(15L, "remote");
                publishDialogFacts(15, "machine", row("remote"), 10, 11, true);
                check(Utilities.globalQueue.tasks.size() == 1, "冷无目标没有入队");
                Utilities.globalQueue.next(); AndroidUtilities.ui.next();
                check("none".equals(statuses.get(15, 12).goal.availability) && "stale".equals(statuses.get(15, 12).goal.validity)
                        && statuses.get(15, 12).goal.startedAtElapsedMs == -1 && statuses.get(15, 12).goal.objective.isEmpty(), "冷恢复没有读到明确无目标");
                int afterCold = Utilities.globalQueue.tasks.size();
                publishDialogFacts(15, "machine", row("remote"), 20, 21, false);
                check(Utilities.globalQueue.tasks.size() == afterCold && "stale".equals(statuses.get(15, 40000).goal.validity)
                        && statuses.get(15, 40000).goal.startedAtElapsedMs == -1, "重复列表重读或续期了冷无目标");
                arm(12, "remote-12", "读后账号");
                scheduleGoalRestore(12, "machine", "remote-12", owner, 1);
                Utilities.globalQueue.next();
                accountGeneration = 9;
                AndroidUtilities.ui.next();
                check(!"读后账号".equals(statuses.get(12, 20).goal.objective), "读完磁盘后换账号仍写回界面");
                accountGeneration = 1;
                arm(13, "remote-13", "读后来源");
                scheduleGoalRestore(13, "machine", "remote-13", owner, 1);
                Utilities.globalQueue.next();
                DesktopConnection.home = "other";
                AndroidUtilities.ui.next();
                check(!"读后来源".equals(statuses.get(13, 20).goal.objective), "读完磁盘后换来源仍写回界面");
                DesktopConnection.home = "user";
                arm(14, "remote-14", "读后关联");
                linkedSessions.put(14L, "linked-a");
                scheduleGoalRestore(14, "machine", "remote-14", owner, 1);
                Utilities.globalQueue.next();
                linkedSessions.put(14L, "linked-b");
                AndroidUtilities.ui.next();
                check(!"读后关联".equals(statuses.get(14, 20).goal.objective), "读完磁盘后换关联仍写回界面");
                linkedSessions.clear();
                arm(16, "remote-16", "读后电脑");
                scheduleGoalRestore(16, "machine", "remote-16", owner, 1);
                Utilities.globalQueue.next();
                dialogMachines.put(16L, "other-machine");
                AndroidUtilities.ui.next();
                check(!"读后电脑".equals(statuses.get(16, 20).goal.objective), "读完磁盘后换电脑仍写回界面");
                dialogMachines.put(16L, "machine");
                String settled = Files.readString(goalFile());
                observe(available("换账号"), 400);
                accountGeneration++;
                Utilities.globalQueue.all();
                check(Files.readString(goalFile()).equals(settled), "账号变化后的旧写盘仍落盘");
                accountGeneration = 1;
                observe(available("换来源"), 410);
                DesktopConnection.home = "other";
                Utilities.globalQueue.all();
                check(Files.readString(goalFile()).equals(settled), "来源变化后的旧写盘仍落盘");
                DesktopConnection.home = "user";
                linkedSessions.put(7L, "linked-a");
                observe(available("换关联"), 420);
                linkedSessions.put(7L, "linked-b");
                Utilities.globalQueue.all();
                check(Files.readString(goalFile()).equals(settled) && !Files.readString(goalFile()).contains("linked-a"), "已知linked变化仍写盘或把linked写入投影");
                linkedSessions.clear();
                String beforeMachine = statuses.get(7, 430).goal.objective;
                dialogMachines.put(7L, "other-machine");
                scheduleGoalRestore(7, "machine", "remote", owner, 1);
                Utilities.globalQueue.all(); AndroidUtilities.ui.all();
                check(beforeMachine.equals(statuses.get(7, 430).goal.objective), "电脑变化后仍恢复旧目标");
                dialogMachines.put(7L, "machine");
                Path blocked = Path.of(goalFile().toString() + ".tmp");
                Files.createDirectories(blocked); Files.writeString(blocked.resolve("occupied"), "fixture");
                String beforeWrite = Files.readString(goalFile());
                observe(available("写失败"), 440);
                try { Utilities.globalQueue.all(); } catch (RuntimeException ignored) { }
                check("写失败".equals(statuses.get(7, 450).goal.objective) && Files.readString(goalFile()).equals(beforeWrite), "写失败撤回了内存事实或破坏原文件");
                Files.delete(blocked.resolve("occupied")); Files.delete(blocked);
                String longObjective = "长".repeat(10001);
                observe(available(longObjective), 460);
                Utilities.globalQueue.all();
                check(longObjective.equals(statuses.get(7, 470).goal.objective) && !Files.readString(goalFile()).contains(longObjective), "超长当前目标被改写或写入磁盘");
                Utilities.globalQueue.all(); AndroidUtilities.ui.all();
                accountGeneration = 1; loggingOut = false; session = owner;
                String keptAvailable = statuses.get(7, 500).goal.objective;
                String keptStale = statuses.get(11, 500).goal.objective;
                String keptNone = statuses.get(15, 500).goal.availability;
                dialogMachines.put(21L, "machine"); remoteIds.put(21L, "remote-21");
                goalDisplayStore(owner).save("machine", "remote-21", "codexHome", "user", "available", "退出前目标", "active", null, null, null, 6L, null);
                publishDialogFacts(21, "machine", row("remote-21"), 10, 11, true);
                check(Utilities.globalQueue.tasks.size() == 1, "失败退出前没有排队冷恢复");
                long oldEpoch = accountGeneration;
                failSessionClear = true;
                int[] failed = {0}, succeeded = {0};
                logout(() -> succeeded[0]++, error -> failed[0]++);
                Utilities.globalQueue.next();
                check(!statuses.get(21, 12).goal.hasValue(), "旧代次冷恢复在退出后仍写入");
                Utilities.globalQueue.next(); AndroidUtilities.ui.next();
                check(failed[0] == 1 && succeeded[0] == 0 && session == owner && !loggingOut, "清凭据失败没有留在原账号");
                check(keptAvailable.equals(statuses.get(7, 500).goal.objective) && keptStale.equals(statuses.get(11, 500).goal.objective)
                        && "none".equals(keptNone) && "none".equals(statuses.get(15, 500).goal.availability) && !statuses.get(15, 500).goal.hasValue(),
                        "失败退出改写了已确认目标、已恢复正文或明确无目标");
                publishDialogFacts(21, "machine", row("remote-21"), 20, 21, true);
                check(Utilities.globalQueue.tasks.size() == 1, "同一账号下一次列表没有重新认领冷恢复");
                Utilities.globalQueue.next(); AndroidUtilities.ui.next();
                long expiry = statuses.currentExpiry(21, "machine", 22);
                check("退出前目标".equals(statuses.get(21, 22).goal.objective) && "stale".equals(statuses.get(21, 22).goal.validity)
                        && statuses.get(21, 22).goal.startedAtElapsedMs == -1 && expiry == -1, "重新认领没有按过期目标恢复");
                scheduleGoalRestore(21, "machine", "remote-21", owner, oldEpoch);
                Utilities.globalQueue.next();
                check(AndroidUtilities.ui.tasks.isEmpty() && "退出前目标".equals(statuses.get(21, 23).goal.objective)
                        && "stale".equals(statuses.get(21, 23).goal.validity) && statuses.currentExpiry(21, "machine", 23) == expiry,
                        "旧代次迟到回调覆盖了目标或授予当前有效");
                dialogMachines.put(22L, "machine"); remoteIds.put(22L, "remote-22");
                goalDisplayStore(owner).save("machine", "remote-22", "codexHome", "user", "available", "读盘后退出", "paused", null, null, null, 7L, null);
                publishDialogFacts(22, "machine", row("remote-22"), 10, 11, true);
                Utilities.globalQueue.next();
                failSessionClear = true;
                logout(() -> succeeded[0]++, error -> failed[0]++);
                Utilities.globalQueue.next(); AndroidUtilities.ui.next(); AndroidUtilities.ui.next();
                check(!statuses.get(22, 12).goal.hasValue() && failed[0] == 2, "读完磁盘后的旧界面回调在失败退出后仍写入");
                publishDialogFacts(22, "machine", row("remote-22"), 30, 31, true);
                Utilities.globalQueue.next(); AndroidUtilities.ui.next();
                check("读盘后退出".equals(statuses.get(22, 32).goal.objective) && "stale".equals(statuses.get(22, 32).goal.validity), "读盘后的失败退出没有允许下一次列表恢复");
                failSessionClear = false;
                int[] cleared = {0}, clearFailed = {0};
                logout(() -> cleared[0]++, error -> clearFailed[0]++);
                for (int turn = 0; turn < 8 && cleared[0] == 0; turn++) {
                    Utilities.globalQueue.all(); dialogQueue.all(); sendQueue.all(); approvalQueue.all(); transcriptQueue.all();
                    historyQueue.all(); statusQueue.all(); prefetchQueue.all(); AndroidUtilities.ui.all();
                }
                check(cleared[0] == 1 && clearFailed[0] == 0 && session == null && !statuses.get(21, 40).goal.hasValue(), "成功退出没有清空原展示");
                System.out.println("RuntimeGoalCache: FIFO、返回后仍落地、身份撤销、冷盘优先顺序、写失败和长目标通过");
            }
            static JsonObject row(String remote) {
                return JsonParser.parseString("{\\"remoteSessionId\\":\\"" + remote + "\\",\\"title\\":\\"t\\",\\"updatedAtMs\\":1,\\"activity\\":\\"running\\",\\"details\\":{\\"codexLifecycle\\":{\\"v\\":1,\\"state\\":\\"running\\",\\"eventAtMs\\":1,\\"checkedAtMs\\":1},\\"codexObservation\\":{\\"v\\":1,\\"source\\":\\"rollout\\",\\"turnId\\":\\"t\\",\\"state\\":\\"running\\"}}}" ).getAsJsonObject();
            }
            static void arm(long id, String remote, String objective) throws Exception {
                dialogMachines.put(id, "machine"); remoteIds.put(id, remote);
                statuses.candidate(id, "machine", row(remote), 10, 11, true);
                goalDisplayStore(session).save("machine", remote, "codexHome", "user", "available", objective, "active", null, null, null, 1L, null);
            }
            static void observe(String goal, long started) { observeAt(7, goal, started); }
            static void observeAt(long id, String goal, long started) {
                var response = JsonParser.parseString("{\\"ok\\":true,\\"machineOnline\\":true,\\"observation\\":{\\"v\\":1,\\"state\\":\\"unknown\\",\\"reason\\":\\"missing_turn_id\\"},\\"goal\\":" + goal + "}").getAsJsonObject();
                statuses.observation(id, "machine", id == 8 ? "remote-8" : "remote", response, started, started + 1);
            }
            static String available(String objective) { return availableOn("remote", objective); }
            static String availableOn(String thread, String objective) {
                return "{\\"availability\\":\\"available\\",\\"source\\":\\"desktop\\",\\"threadId\\":\\"" + thread + "\\",\\"objective\\":\\"" + objective + "\\",\\"status\\":\\"active\\",\\"tokenBudget\\":null,\\"tokensUsed\\":null,\\"timeUsedSeconds\\":null,\\"updatedAt\\":1}";
            }
            static Path goalFile() { return ApplicationLoader.applicationContext.root.toPath().resolve("codex-goals").resolve(TranscriptStore.digest("server\\naccount") + ".json"); }
            static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
            """.formatted(root);
    }

    // 替身只提供队列、账号和来源；目标合并与写盘调用原方法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;
        import java.io.File;
        import java.nio.file.*;
        import java.util.*;
        import org.telegram.messenger.MessagesController;
        import org.telegram.messenger.NotificationCenter;
        public final class RuntimeGoalCacheProbe {
            static boolean loggingOut, failSessionClear, sessionRestored, listRefreshScheduled, loading, connectedComputersPrefetching, cachedDialogsRead;
            static PasswordLogin.Session session;
            static DesktopConnection desktop;
            static long accountGeneration = 1, watchGeneration = 1, watchedDialog, accountUsageRestoredEpoch = -1;
            static String accountUsageMachine, preferredMachine, loadError;
            static final Object dialogIdentityLock = new Object();
            static Object dialogIdentities;
            static final Map<Long, String> dialogMachines = new HashMap<>(), remoteIds = new HashMap<>(), linkedSessions = new HashMap<>(), dialogTitles = new HashMap<>(), dialogDirectories = new HashMap<>();
            static final Map<String, DesktopConnection> desktopConnections = new HashMap<>();
            static final Map<String, Object> accountUsageSnapshots = new HashMap<>(), accountUsageReads = new HashMap<>(), browseSnapshots = new HashMap<>(), machineNames = new HashMap<>();
            static final Map<Long, Object> histories = new HashMap<>(), pendingMessages = new HashMap<>(), attachmentGroups = new HashMap<>(), attachmentStates = new HashMap<>(), attachmentCallbacks = new HashMap<>(), sendingBatches = new HashMap<>(), prefetchedRevisions = new HashMap<>();
            static final java.util.Set<Long> prefetching = new HashSet<>();
            static final java.util.List<Object> dialogs = new ArrayList<>();
            static final SessionStatus.Store statuses = new SessionStatus.Store();
            static final org.telegram.messenger.DispatchQueue statusQueue = new org.telegram.messenger.DispatchQueue("status"), dialogQueue = new org.telegram.messenger.DispatchQueue("dialog"), sendQueue = new org.telegram.messenger.DispatchQueue("send"), approvalQueue = new org.telegram.messenger.DispatchQueue("approval"), transcriptQueue = new org.telegram.messenger.DispatchQueue("transcript"), historyQueue = new org.telegram.messenger.DispatchQueue("history"), prefetchQueue = new org.telegram.messenger.DispatchQueue("prefetch");
            static Runnable statusPoll;
            static DesktopConnection observationOwner;
            static long observationDialog, observationRenewAt;
            static String observationSession, observationLease;
            static final class Utilities { static final org.telegram.messenger.DispatchQueue globalQueue = new org.telegram.messenger.DispatchQueue("global"); }
            static final class AndroidUtilities { static final org.telegram.messenger.DispatchQueue ui = new org.telegram.messenger.DispatchQueue("ui"); static void runOnUIThread(Runnable runnable) { ui.postRunnable(runnable); } }
            static final class DesktopConnection {
                static String kind = "codexHome", home = "user";
                static JsonObject userCodexSource() { JsonObject source = new JsonObject(); source.addProperty("kind", kind); source.addProperty("home", home); return source; }
                void stopObserving(String sessionId, String leaseId) {}
                void close() {}
            }
            static final class PasswordLogin { static final class Session { String server = "server", accountId = "account"; void close() {} } }
            static final class ApplicationLoader { static Context applicationContext; }
            static final class Context { final File root; Context(File root) { this.root = root; } File getNoBackupFilesDir() { return root; } }
            static final class SessionStore { SessionStore(Context ignored) {} void clear() throws java.io.IOException { if (failSessionClear) throw new java.io.IOException("synthetic clear failure"); } }
        """;
}
