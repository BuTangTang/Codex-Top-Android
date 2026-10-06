package com.butang.codextop;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper.SendMessageParams;

/** 原有 Telegram 页面共享的 Codex 业务状态，不创建或管理任何新界面。 */
public final class CodexRuntime {
    public static final String SERVER = "https://47.111.133.100";
    private static volatile PasswordLogin.Session session;
    private static volatile long accountGeneration;
    private static volatile boolean loggingOut;
    private static volatile boolean sessionRestored;
    private static boolean draftOwnerSaved;
    private static volatile DesktopConnection desktop;
    private static String preferredMachine;
    private static final Map<String, DesktopConnection> desktopConnections = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean cachedDialogsRead;
    private static DialogIdentityStore dialogIdentities;
    private static final Object dialogIdentityLock = new Object();
    // 仅缓存浏览元数据；界面快读不等待网络队列或磁盘。
    private static final Map<String, JsonObject> browseSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    // 只保留本次账号内各电脑用户来源的最近采集值；页面始终展示原采集时间。
    private static final Map<String, AccountUsage> accountUsageSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private static String accountUsageMachine;
    // 仅通用队列读取一次本账号磁盘投影；退出等待队列后复位。
    private static long accountUsageRestoredEpoch = -1;
    // 仅界面线程合并当前在途回调；不等待openDesktop的类锁，也不保存另一份额度。
    private static final Map<String, AccountUsageRead> accountUsageReads = new HashMap<>();
    private static final org.telegram.messenger.DispatchQueue dialogQueue = new org.telegram.messenger.DispatchQueue("codex-dialogs");
    private static final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
    private static final Map<Long, String> remoteIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, String> dialogMachines = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, TranscriptWindow> histories = new HashMap<>();
    private static final org.telegram.messenger.DispatchQueue sendQueue = new org.telegram.messenger.DispatchQueue("codex-send");
    // 审批读取可能等待桌面加载；独立串行执行，避免阻塞普通消息发送。
    private static final org.telegram.messenger.DispatchQueue approvalQueue = new org.telegram.messenger.DispatchQueue("codex-approval");
    private static final org.telegram.messenger.DispatchQueue transcriptQueue = new org.telegram.messenger.DispatchQueue("codex-transcript");
    private static final org.telegram.messenger.DispatchQueue historyQueue = new org.telegram.messenger.DispatchQueue("codex-history");
    private static final Map<Long, String> linkedSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, org.telegram.messenger.MessageObject> pendingMessages = new HashMap<>();
    // 原选择器按 groupId/final 交付；仅在界面线程收齐同批，不新增发送队列。
    private static final Map<String, ArrayList<SendMessageParams>> attachmentGroups = new HashMap<>();
    private static final Map<String, AttachmentState> attachmentStates = new HashMap<>();
    private static final Map<String, ArrayList<Runnable>> attachmentCallbacks = new HashMap<>();
    private static final java.util.HashSet<String> sendingBatches = new java.util.HashSet<>();
    private static final org.telegram.messenger.DispatchQueue statusQueue = new org.telegram.messenger.DispatchQueue("codex-status");
    private static final SessionStatus.Store statuses = new SessionStatus.Store();
    private static final Map<Long, String> dialogTitles = new HashMap<>();
    private static final Map<Long, String> dialogDirectories = new HashMap<>();
    private static final Map<String, String> machineNames = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long watchGeneration;
    private static volatile long watchedDialog;
    // 仅 statusQueue 访问；返回列表的短暂间隔复用一个观察租约，不缓存多份桌面历史。
    private static Runnable statusPoll;
    private static DesktopConnection observationOwner;
    private static long observationDialog;
    private static String observationSession;
    private static String observationLease;
    private static long observationRenewAt;

    private static void releaseObservation() {
        try {
            if (observationOwner != null && observationLease != null)
                observationOwner.stopObserving(observationSession, observationLease);
        } catch (Exception ignored) { /* 断网时由服务端有限租期回收。 */ }
        observationOwner = null;
        observationLease = null;
        observationDialog = 0;
        observationRenewAt = 0;
    }

    private static boolean loading;
    private static boolean listRefreshScheduled;
    private static final org.telegram.messenger.DispatchQueue prefetchQueue = new org.telegram.messenger.DispatchQueue("codex-prefetch");
    private static final Map<Long, Long> prefetchedRevisions = new HashMap<>();
    private static final java.util.HashSet<Long> prefetching = new java.util.HashSet<>();
    // 仍由原列表周期触发，单飞门禁防止慢电脑在现预取队列里逐轮堆积。
    private static boolean connectedComputersPrefetching;
    public static String loadError;

    /** 仅独立 Codex 包启用适配，避免改变原版 Telegram 构建。 */
    public static boolean enabled() {
        return ApplicationLoader.applicationContext != null
                && ApplicationLoader.applicationContext.getPackageName().startsWith("com.butang.codextop.nativepreview");
    }

    /** 后台完成真实账号认证；结果只供已有登录页切换使用。 */
    public static synchronized void login(String name, String password) throws Exception {
        if (loggingOut || loggedIn()) throw new IllegalStateException("请先退出当前账号");
        // 先确认升级前账号的草稿归属，首次新登录不能在下次启动时认领旧文件。
        loggedIn();
        if (!draftOwnerSaved) throw new java.io.IOException("草稿账号归属尚未保存");
        PasswordLogin.Session next = PasswordLogin.login(SERVER, name, password);
        try { new SessionStore(ApplicationLoader.applicationContext).save(next); }
        catch (Exception error) { next.close(); throw error; }
        if (session != null) session.close();
        session = next;
        accountGeneration++;
        sessionRestored = true;
    }

    /** 已恢复会话只读内存，UI 不等待后台连接的类锁；首次调用仍沿原冷恢复入口。 */
    public static boolean loggedIn() {
        if (!sessionRestored) restoreSession();
        return session != null && !loggingOut;
    }

    /** 冷恢复和草稿归属保存仍串行执行；全部完成后才向无锁读取者发布恢复标记。 */
    private static synchronized void restoreSession() {
        if (!sessionRestored && ApplicationLoader.applicationContext != null) {
            try {
                session = new SessionStore(ApplicationLoader.applicationContext).read();
            } catch (Exception error) {
                // 无法解密时留在原登录页，不输出凭据也不伪造已登录。
                session = null;
            }
            // 仅已保存账号可继承旧草稿；空值也持久化，防止首次登录在下次启动时误认领。
            android.content.SharedPreferences preferences = ApplicationLoader.applicationContext
                    .getSharedPreferences("codex-preferences", 0);
            draftOwnerSaved = preferences.contains("legacyDraftOwner");
            if (!draftOwnerSaved) draftOwnerSaved = preferences.edit().putString("legacyDraftOwner",
                    session == null ? "" : TranscriptStore.digest(session.server + "\n" + session.accountId)).commit();
            sessionRestored = true;
        }
    }

    /** 每个异步入口保存账号代次，旧账号迟到响应不能更新下一账号的缓存或页面。 */
    private static boolean isAccountCurrent(long expected) {
        return !loggingOut && session != null && expected == accountGeneration;
    }

    /** 从原退出确认调用；停止接单、清凭据、关连接并等旧工作离开队列后返回登录页。 */
    public static void logout(Runnable completed, java.util.function.Consumer<String> failed) {
        if (loggingOut) return;
        final int previewAccount = org.telegram.messenger.UserConfig.selectedAccount;
        loggingOut = true;
        accountGeneration++;
        watchGeneration++;
        watchedDialog = 0;
        Utilities.globalQueue.postRunnable(() -> {
            try { new SessionStore(ApplicationLoader.applicationContext).clear(); }
            catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> {
                    statuses.rearmUnconfirmedGoalRestore();
                    loggingOut = false;
                    listRefreshScheduled = false;
                    loading = false;
                    connectedComputersPrefetching = false;
                    failed.accept("退出失败，请重试");
                });
                return;
            }
            // 关闭 socket 会立即结束已发出的等待；仍在创建的连接在 openDesktop 返回前拒绝加入。
            for (DesktopConnection connection : desktopConnections.values()) connection.close();
            org.telegram.messenger.DispatchQueue[] queues = {dialogQueue, sendQueue, approvalQueue, transcriptQueue,
                    historyQueue, statusQueue, prefetchQueue, Utilities.globalQueue};
            java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(queues.length);
            for (org.telegram.messenger.DispatchQueue queue : queues) queue.postRunnable(() -> {
                if (queue == statusQueue) {
                    if (statusPoll != null) statusQueue.cancelRunnable(statusPoll);
                    statusPoll = null;
                    releaseObservation();
                }
                if (remaining.decrementAndGet() != 0) return;
                AndroidUtilities.runOnUIThread(() -> {
                    // 所有旧任务已离开执行段；排队的后续回调仍由账号代次拒绝。
                    synchronized (CodexRuntime.class) {
                        if (session != null) session.close();
                        session = null;
                        sessionRestored = true;
                        desktop = null;
                        desktopConnections.clear();
                        accountUsageSnapshots.clear();
                        accountUsageReads.clear();
                        accountUsageMachine = null;
                        accountUsageRestoredEpoch = -1;
                        preferredMachine = null;
                        cachedDialogsRead = false;
                        // 与冷盘恢复共用小锁；旧账号绑定不能越过清理写入下一账号。
                        synchronized (dialogIdentityLock) {
                            // 原消息预览也属于当前账号，只清本包绑定行，不留下可被下一账号认领的摘要。
                            MessagesController controller = MessagesController.getInstance(previewAccount);
                            for (Long dialogId : remoteIds.keySet()) controller.dialogMessage.remove(dialogId);
                            dialogIdentities = null;
                            browseSnapshots.clear(); remoteIds.clear(); dialogMachines.clear();
                        }
                        dialogs.clear();
                        histories.clear(); linkedSessions.clear(); pendingMessages.clear();
                        attachmentGroups.clear(); attachmentStates.clear(); attachmentCallbacks.clear(); sendingBatches.clear();
                        statuses.clear(); dialogTitles.clear(); dialogDirectories.clear(); machineNames.clear();
                        prefetchedRevisions.clear(); prefetching.clear();
                        connectedComputersPrefetching = false;
                        loading = false; listRefreshScheduled = false; loadError = null;
                        loggingOut = false;
                    }
                    completed.run();
                });
            });
        });
    }

    /** 已恢复账号的草稿命名不等待联网类锁；冷恢复仍走原单飞入口，归属中途变化则隔离。 */
    public static String draftPreferencesName(int telegramAccount) {
        String original = telegramAccount == 0 ? "drafts" : "drafts" + telegramAccount;
        String signedOut = "codex-drafts-signed-out-" + telegramAccount;
        if (!loggedIn()) return signedOut;
        long epoch = accountGeneration;
        PasswordLogin.Session owner = session;
        if (owner == null || !isAccountCurrent(epoch)) return signedOut;
        // 两个身份字段只来自同一不可变会话，不能分别读取可能已切换的全局session。
        String ownerKey = TranscriptStore.digest(owner.server + "\n" + owner.accountId);
        String legacyOwner = ApplicationLoader.applicationContext.getSharedPreferences("codex-preferences", 0)
                .getString("legacyDraftOwner", "");
        if (session != owner || !isAccountCurrent(epoch)) return signedOut;
        return ownerKey.equals(legacyOwner) ? original : "codex-drafts-" + ownerKey + "-" + telegramAccount;
    }

    /** 首页重连优先沿用缓存电脑，不能在原电脑离线时自动切到其他电脑。 */
    public static synchronized DesktopConnection openDesktop() throws Exception {
        return openDesktop(preferredMachine);
    }

    /** 后台复用所选电脑连接；浏览其他电脑不替换首页来源。 */
    public static synchronized DesktopConnection openDesktop(String machine) throws Exception {
        if (session == null || loggingOut) throw new IllegalStateException("请先登录");
        DesktopConnection connection = machine == null ? desktop : desktopConnections.get(machine);
        if (connection == null) {
            connection = DesktopConnection.open(session, machine);
            if (loggingOut) { connection.close(); throw new IllegalStateException("正在退出账号"); }
            desktopConnections.put(connection.machineId, connection);
            machineNames.put(connection.machineId, connection.machineName);
            onDesktopConnected(connection);
        }
        return connection;
    }

    /** 新建或重连成功后唤醒原列表与同电脑的当前聊天，沿原门禁保持单次读取和单观察。 */
    static void onDesktopConnected(DesktopConnection connection) {
        final long accountEpoch = accountGeneration;
        AndroidUtilities.runOnUIThread(() -> {
            if (!isAccountCurrent(accountEpoch) || desktopConnections.get(connection.machineId) != connection) return;
            // 复用原列表单飞门禁，重连不为每个会话增加 STATUS 或历史请求。
            if (connection == desktop && !ApplicationLoader.mainInterfacePaused)
                refreshDialogs(org.telegram.messenger.UserConfig.selectedAccount);
        });
        statusQueue.postRunnable(() -> {
            // 旧账号、已替换连接及其他电脑的迟到事件不能唤醒当前页。
            if (!isAccountCurrent(accountEpoch) || desktopConnections.get(connection.machineId) != connection
                    || watchedDialog == 0 || statusPoll == null
                    || !connection.machineId.equals(dialogMachines.get(watchedDialog))) return;
            statusQueue.cancelRunnable(statusPoll);
            statusQueue.postRunnable(statusPoll);
        });
    }

    /** 连接事件只让所属电脑展示失效；旧连接或旧账号不能影响新来源。 */
    static void onDesktopDisconnected(DesktopConnection connection) {
        final long accountEpoch = accountGeneration;
        final long disconnectedAt = android.os.SystemClock.elapsedRealtime();
        AndroidUtilities.runOnUIThread(() -> {
            if (!isAccountCurrent(accountEpoch) || desktopConnections.get(connection.machineId) != connection) return;
            statuses.unavailable(connection.machineId, disconnectedAt);
            NotificationCenter.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                    .postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
        });
    }

    /** 仅读取当前账号的电脑资料，列出离线电脑不建立额外长连接。 */
    public static synchronized com.google.gson.JsonArray computers() throws Exception {
        if (session == null || loggingOut) throw new IllegalStateException("请先登录");
        return DesktopConnection.computers(session);
    }

    /** 缓存回调可能先于网络回调；cached 只表示已保存浏览资料，不能证明在线。 */
    public interface BrowseCallback {
        /** cached 回调保留后台加载；本地仅恢复额度时或网络失败时 snapshot 为空。 */
        void accept(JsonObject snapshot, String error, boolean cached);
    }

    /** 页面创建时直接读取进程内快照，空 rows 也代表已成功加载；不执行磁盘或网络。 */
    public static JsonObject cachedBrowse(String machine, ArrayList<String> roots, boolean conversations) {
        if (session == null || loggingOut) return null;
        String kind = conversations ? "conversations" : machine == null ? "computers" : "projects";
        JsonObject snapshot = browseSnapshots.get(BrowseStore.scope(kind, machine, roots));
        return snapshot == null ? null : cachedBrowseSnapshot(snapshot);
    }

    /** 回访沿原电脑/项目入口发一次请求，先投递本地数据再静默更新。 */
    public static void browseComputers(String machine, BrowseCallback callback) {
        browse(org.telegram.messenger.UserConfig.selectedAccount,
                machine == null ? "computers" : "projects", machine, null, null, callback);
    }

    /** 项目对话沿原分页入口读取，已浏览尾页不会被新的首屏截断。 */
    public static void browseConversations(int account, String machine, ArrayList<String> roots, String cursor,
            BrowseCallback callback) {
        browse(account, "conversations", machine, roots, cursor, callback);
    }

    /** 读取本地不排在网络队列后；所有成功页合并、注册归属、保存后投递原页面。 */
    private static void browse(int account, String kind, String machine, ArrayList<String> roots, String cursor,
            BrowseCallback callback) {
        final long accountEpoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        if (!isAccountCurrent(accountEpoch)) return;
        final ArrayList<String> scopeRoots = roots == null ? null : new ArrayList<>(roots);
        final String key = BrowseStore.scope(kind, machine, scopeRoots);
        final BrowseStore store = new BrowseStore(new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "codex-browse"), owner.server, owner.accountId);
        JsonObject memory = browseSnapshots.get(key);
        if (memory != null) callback.accept(cachedBrowseSnapshot(memory), null, true);
        // 冷盘读取只用现有通用队列；即使别的电脑正在等待网络，本地页仍可先出现。
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch)) return;
            if ("computers".equals(kind)) restoreAccountUsageCache(owner, accountEpoch);
            boolean localPublished = false;
            if (memory == null) {
                try {
                    JsonObject cached = store.read(kind, machine, scopeRoots);
                    if (cached != null) {
                        JsonObject prepared = prepareBrowseSnapshot(kind, machine, cached, null, owner, accountEpoch);
                        publishBrowse(account, accountEpoch, key, kind, machine, prepared, true, -1, -1, callback);
                        localPublished = true;
                    }
                } catch (java.io.IOException ignored) { /* 坏缓存按无缓存处理，不阻止原网络入口恢复。 */ }
            }
            // 无电脑列表缓存也投递原本地阶段，让已恢复额度不等待慢网络。
            if ("computers".equals(kind) && !localPublished) AndroidUtilities.runOnUIThread(() -> {
                if (isAccountCurrent(accountEpoch) && session == owner) callback.accept(null, null, true);
            });
            dialogQueue.postRunnable(() -> {
                if (!isAccountCurrent(accountEpoch)) return;
                long startedAt = android.os.SystemClock.elapsedRealtime();
                try {
                    JsonObject page = new JsonObject();
                    com.google.gson.JsonArray rows;
                    DesktopConnection conversationConnection = null;
                    if ("computers".equals(kind)) rows = computers();
                    else if ("projects".equals(kind)) {
                        JsonObject response = openDesktop(machine).projects();
                        rows = response.getAsJsonArray("projects");
                        if (response.has("searchIncomplete")) page.add("searchIncomplete", response.get("searchIncomplete"));
                    } else {
                        DesktopConnection connection = openDesktop(machine);
                        conversationConnection = connection;
                        startedAt = android.os.SystemClock.elapsedRealtime();
                        JsonObject response = connection.candidates(100, cursor);
                        rows = new com.google.gson.JsonArray();
                        for (com.google.gson.JsonElement value : response.getAsJsonArray("candidates")) {
                            JsonObject row = value.getAsJsonObject();
                            String directory = candidateDirectory(row);
                            boolean matched = scopeRoots == null;
                            if (scopeRoots != null) for (String root : scopeRoots)
                                if (ProjectPaths.contains(root, directory)) { matched = true; break; }
                            if (matched) rows.add(row);
                        }
                        if (response.has("nextCursor")) page.add("nextCursor", response.get("nextCursor"));
                        if (response.has("searchIncomplete")) page.add("searchIncomplete", response.get("searchIncomplete"));
                    }
                    if (rows == null) throw new java.io.IOException("浏览列表格式无效");
                    long receivedAt = android.os.SystemClock.elapsedRealtime();
                    page.add("rows", rows);
                    JsonObject merged = BrowseStore.merge(kind, browseSnapshots.get(key), page, cursor != null);
                    JsonObject prepared = prepareBrowseSnapshot(kind, machine, merged, rows, owner, accountEpoch);
                    // 缓存写失败不抹掉成功的内存页，也不改变完整性和下一页游标。
                    try { store.write(kind, machine, scopeRoots, merged); } catch (java.io.IOException ignored) { }
                    publishBrowse(account, accountEpoch, key, kind, machine, prepared, false, startedAt, receivedAt, callback);
                    // 只预取本次成功页，不把合并缓存页当新候选，也不改变首页来源。
                    if (conversationConnection != null && isAccountCurrent(accountEpoch)
                            && desktopConnections.get(conversationConnection.machineId) == conversationConnection)
                        prefetchDialogs(rows, conversationConnection);
                } catch (Exception error) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isAccountCurrent(accountEpoch)) callback.accept(null,
                                "conversations".equals(kind) ? "对话暂时无法读取，请重试" : "电脑或项目暂时无法读取，请重试", false);
                    });
                }
            });
        });
    }

    /** 磁盘和内存回访都标记旧资料，active/available 和历史生命周期不续期。 */
    private static JsonObject cachedBrowseSnapshot(JsonObject snapshot) {
        JsonObject result = snapshot.deepCopy();
        for (com.google.gson.JsonElement row : result.getAsJsonArray("rows")) row.getAsJsonObject().addProperty("cached", true);
        return result;
    }

    /** 后台恢复稳定对话编号；仅本次网络返回的行可更新状态有效期。 */
    private static JsonObject prepareBrowseSnapshot(String kind, String machine, JsonObject snapshot,
            com.google.gson.JsonArray freshRows, PasswordLogin.Session owner, long accountEpoch) throws java.io.IOException {
        java.util.HashSet<String> fresh = new java.util.HashSet<>();
        if (freshRows != null) for (com.google.gson.JsonElement row : freshRows)
            fresh.add(BrowseStore.identity(kind, row.getAsJsonObject()));
        JsonObject result = snapshot.deepCopy();
        for (com.google.gson.JsonElement value : result.getAsJsonArray("rows")) {
            JsonObject row = value.getAsJsonObject();
            row.addProperty("cached", !fresh.contains(BrowseStore.identity(kind, row)));
            if ("conversations".equals(kind)) {
                long local = bindDialog(machine, row.get("remoteSessionId").getAsString(), owner, accountEpoch);
                if (local == 0) throw new java.io.IOException("无法保存对话归属");
                row.addProperty("localDialogId", local);
            }
        }
        return result;
    }

    /** 统一发布浏览页，过期账号回包不写新账号内存；保留页只作为缓存事实。 */
    private static void publishBrowse(int account, long accountEpoch, String key, String kind, String machine,
            JsonObject snapshot, boolean cached, long startedAt, long receivedAt, BrowseCallback callback) {
        // 与退出清理共用锁，在后台页之间先发布不可变快照；不依赖 UI 消息处理进度。
        synchronized (dialogIdentityLock) {
            if (!isAccountCurrent(accountEpoch)) return;
            if (cached) browseSnapshots.putIfAbsent(key, snapshot);
            else browseSnapshots.put(key, snapshot);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!isAccountCurrent(accountEpoch)) return;
            if (cached && browseSnapshots.get(key) != snapshot) {
                // 冷盘完成期间可能已有网络或另一次浏览成功；旧盘页不能覆盖新元数据。
                JsonObject latest = browseSnapshots.get(key);
                if (latest != null) callback.accept(cachedBrowseSnapshot(latest), null, true);
                return;
            }
            boolean changed = false;
            if ("conversations".equals(kind)) for (com.google.gson.JsonElement value : snapshot.getAsJsonArray("rows")) {
                JsonObject row = value.getAsJsonObject();
                long local = row.get("localDialogId").getAsLong();
                String before = statusPresentation(local);
                publishDialogFacts(local, machine, row, startedAt, receivedAt, cached || row.get("cached").getAsBoolean());
                changed |= !before.equals(statusPresentation(local));
                changed |= publishDialogUser(account, local, dialogTitles.get(local));
            }
            if (changed) NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.updateInterfaces,
                            MessagesController.UPDATE_MASK_STATUS | MessagesController.UPDATE_MASK_NAME);
            callback.accept(snapshot.deepCopy(), null, cached);
        });
    }

    /** 原列表直接读取当前快照；此方法与快照替换都限定在界面线程。 */
    public static ArrayList<TLRPC.Dialog> dialogs() { return dialogs; }

    /** 暴露真实加载状态给 Telegram 原来的加载提示。 */
    public static boolean loadingDialogs() { return loading; }

    /** 仅拦截本客户端已经发现的电脑对话，其他原版数据入口保持独立。 */
    public static boolean ownsConversation(long dialogId) { return remoteIds.containsKey(dialogId); }

    /** 在编号锁内核对捕获的账号及代次；旧盘任务不能注册到已切换的新账号。 */
    private static long bindDialog(String machine, String remote, PasswordLogin.Session owner, long accountEpoch) {
        synchronized (dialogIdentityLock) {
            if (session != owner || !isAccountCurrent(accountEpoch)) return 0;
            try {
                if (dialogIdentities == null) dialogIdentities = new DialogIdentityStore(
                        new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "codex-dialog-identities"),
                        owner.server, owner.accountId);
                long id = dialogIdentities.bind(machine, remote);
                dialogMachines.put(id, machine);
                remoteIds.put(id, remote);
                return id;
            } catch (java.io.IOException error) {
                android.util.Log.w("CodexBridge", "dialog_identity_unavailable");
                return 0;
            }
        }
    }

    /** 未知归属禁止读写缓存，不能退回当前电脑或空电脑目录。 */
    private static String dialogMachine(long dialogId) {
        String machine = dialogMachines.get(dialogId);
        if (machine == null) throw new IllegalStateException("对话电脑归属缺失");
        return machine;
    }

    /** 只返回该对话所属电脑的连接；离线或其他电脑连接不能替代。 */
    private static DesktopConnection dialogConnection(long dialogId) {
        String machine = dialogMachines.get(dialogId);
        return machine == null ? null : desktopConnections.get(machine);
    }

    /** 只读原对话所属电脑已公布的实际容量，旧连接未知时不补默认值。 */
    public static Long attachmentUploadMaxBytes(long dialogId) {
        DesktopConnection connection = ownsConversation(dialogId) ? dialogConnection(dialogId) : null;
        return connection == null ? null : connection.attachmentUploadMaxBytes();
    }

    /** 只提前拒绝已知超限的真实文件；缺失原件仍由原暂存和校验流程解释。 */
    private static boolean attachmentTooLarge(long dialogId, File file) {
        Long maximum = attachmentUploadMaxBytes(dialogId);
        return maximum != null && file != null && file.isFile() && file.length() > maximum;
    }

    /** 传输只发布实际字节；没有取消契约时不把进度伪装成可取消操作。 */
    public static final class AttachmentState {
        public final boolean active, upload, failed;
        public final long transferredBytes, totalBytes;
        /** 保存一个分块传输快照，原气泡自行使用已有进度绘制。 */
        private AttachmentState(boolean active, boolean upload, boolean failed, long transferred, long total) {
            this.active = active; this.upload = upload; this.failed = failed;
            transferredBytes = transferred; totalBytes = total;
        }
    }

    /** 仅识别本产品在原消息模型内保存的附件，不将普通 Telegram 媒体改走电脑连接。 */
    public static boolean isAttachmentMessage(MessageObject message) {
        return message != null && ownsConversation(message.getDialogId()) && message.messageOwner.params != null
                && (message.messageOwner.params.containsKey("codexAttachment")
                || message.messageOwner.params.containsKey("codexPendingFile")
                || message.messageOwner.params.containsKey("codexSelectedFile"));
    }

    /** 描述只从已验证的本地消息参数恢复；不可用引用允许没有下载路径。 */
    public static DesktopAttachment attachment(MessageObject message) {
        if (!isAttachmentMessage(message)) return null;
        String encoded = message.messageOwner.params.get("codexAttachment");
        if (encoded == null) return null;
        try {
            com.google.gson.JsonArray values = new com.google.gson.JsonArray();
            values.add(com.google.gson.JsonParser.parseString(encoded));
            JsonObject payload = new JsonObject(); payload.add("attachments", values);
            JsonObject happier = new JsonObject(); happier.addProperty("kind", "attachments.v1"); happier.add("payload", payload);
            JsonObject meta = new JsonObject(); meta.add("happier", happier);
            JsonObject raw = new JsonObject(); raw.add("meta", meta);
            return DesktopAttachment.readRaw(raw).get(0);
        } catch (RuntimeException error) { return null; }
    }

    /** 文件缓存沿当前账号、电脑和会话的既有归属，服务地址与本机路径不用于公开诊断。 */
    private static File attachmentDirectory(long dialogId) {
        PasswordLogin.Session owner = session;
        String remote = remoteIds.get(dialogId);
        if (owner == null || remote == null) throw new IllegalStateException("附件会话归属缺失");
        File root = ApplicationLoader.applicationContext.getExternalFilesDir("codex-attachments");
        if (root == null) root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "codex-attachments");
        return new File(root, TranscriptStore.digest(owner.server + "\n" + owner.accountId
                + "\n" + dialogMachine(dialogId) + "\n" + remote));
    }

    /** 只有已原子发布或上传前保留的原件可就绪；界面线程不重新读取整图计算摘要。 */
    private static File cachedAttachment(File directory, String localId, DesktopAttachment value) {
        if (value == null || !value.isAvailable()) return null;
        try {
            if (localId != null) {
                File original = AttachmentFiles.target(directory, "send\n" + localId, value.name);
                if (original.isFile() && (value.sizeBytes == null || original.length() == value.sizeBytes)) return original;
            }
            File target = AttachmentFiles.target(directory, "receive\n" + value.toJson(), value.name);
            return target.isFile() && (value.sizeBytes == null || target.length() == value.sizeBytes) ? target : null;
        } catch (IOException error) { return null; }
    }

    /** 原预览和打开文件仅获得已就绪本地文件，不触发 Telegram 下载或网络预读。 */
    public static File attachmentFile(MessageObject message) {
        if (!isAttachmentMessage(message)) return null;
        try {
            Map<String, String> params = message.messageOwner.params;
            String pending = params.get("codexPendingFile");
            if (pending != null) {
                DesktopAttachment.Pending value = DesktopAttachment.Pending.read(com.google.gson.JsonParser.parseString(pending).getAsJsonObject());
                File local = new File(value.localPath);
                File expected = AttachmentFiles.target(attachmentDirectory(message.getDialogId()),
                        "send\n" + params.get("codexLocalId"), value.name);
                return local.equals(expected) && local.isFile() && local.length() == value.sizeBytes ? local : null;
            }
            String selected = params.get("codexSelectedFile");
            if (selected != null) {
                File local = new File(selected);
                return local.isAbsolute() && local.isFile() ? local : null;
            }
            return cachedAttachment(attachmentDirectory(message.getDialogId()), params.get("codexLocalId"), attachment(message));
        } catch (Exception error) { return null; }
    }

    /** 同一附件的进度由消息身份关联；账号切换后旧标识不能命中新账号。 */
    private static String attachmentKey(MessageObject message) {
        String localId = message.messageOwner.params == null ? null : message.messageOwner.params.get("codexLocalId");
        DesktopAttachment value = attachment(message);
        return accountGeneration + ":" + message.getDialogId() + ":"
                + (localId != null ? localId : value == null ? message.getId() : TranscriptStore.digest(value.toJson().toString()));
    }

    /** 原气泡只读内存快照，不为显示进度轮询电脑。 */
    public static AttachmentState attachmentTransferState(MessageObject message) {
        return isAttachmentMessage(message) ? attachmentStates.get(attachmentKey(message)) : null;
    }

    /** 每次网络操作及回调都核对账号、电脑、会话和连接实例，旧响应不得落到新归属。 */
    private static boolean attachmentCurrent(long epoch, long dialogId, String remote, DesktopConnection connection) {
        return isAccountCurrent(epoch) && remote.equals(remoteIds.get(dialogId))
                && connection != null && dialogConnection(dialogId) == connection;
    }

    /** 分块 RPC 沿原认证连接执行，归属变化立即结束后续传输。 */
    private static AttachmentTransfer transfer(long epoch, long dialogId, String remote, DesktopConnection connection,
            AttachmentTransfer.Progress progress) {
        return new AttachmentTransfer((method, params) -> {
            if (!attachmentCurrent(epoch, dialogId, remote, connection)) throw new IOException("附件连接已变化");
            JsonObject result = connection.transfer(method, params);
            if (!attachmentCurrent(epoch, dialogId, remote, connection)) throw new IOException("附件连接已变化");
            return result;
        }, progress);
    }

    /** 分块回调仅更新原气泡；不重新装载聊天历史或改变列表滚动。 */
    private static void attachmentProgress(int account, long epoch, long dialogId, String remote,
            DesktopConnection connection, String key, boolean upload, long done, long total) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!attachmentCurrent(epoch, dialogId, remote, connection)) return;
            attachmentStates.put(key, new AttachmentState(true, upload, false, done, total));
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                    MessagesController.UPDATE_MASK_SEND_STATE);
        });
    }

    /** 点击才沿已有网络队列下载，避免旧页读取阻塞；同文件复用在途，结果回到主线程。 */
    public static void downloadAttachment(int account, MessageObject message, Runnable completed) {
        final long epoch = accountGeneration;
        final DesktopAttachment value = attachment(message);
        if (!isAccountCurrent(epoch) || value == null || !value.isAvailable() || attachmentFile(message) != null) {
            AndroidUtilities.runOnUIThread(completed); return;
        }
        final String key = attachmentKey(message);
        ArrayList<Runnable> callbacks = attachmentCallbacks.get(key);
        if (callbacks != null) { callbacks.add(completed); return; }
        callbacks = new ArrayList<>(); callbacks.add(completed); attachmentCallbacks.put(key, callbacks);
        final long dialogId = message.getDialogId();
        final String remote = remoteIds.get(dialogId);
        final DesktopConnection connection = dialogConnection(dialogId);
        final File directory = attachmentDirectory(dialogId);
        attachmentStates.put(key, new AttachmentState(true, false, false, 0, value.sizeBytes == null ? 0 : value.sizeBytes));
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
        Utilities.externalNetworkQueue.postRunnable(() -> {
            File ready = null;
            try {
                File target = AttachmentFiles.target(directory, "receive\n" + value.toJson(), value.name);
                transfer(epoch, dialogId, remote, connection,
                        (done, total) -> attachmentProgress(account, epoch, dialogId, remote, connection, key, false, done, total))
                        .download(value.path, value.sizeBytes, value.sha256, target);
                ready = target;
            } catch (Exception ignored) { /* 原有效文件由传输层保留，失败可再次点击重试。 */ }
            final File received = ready;
            AndroidUtilities.runOnUIThread(() -> {
                if (!isAccountCurrent(epoch)) return;
                boolean valid = received != null && attachmentCurrent(epoch, dialogId, remote, connection);
                AttachmentState previous = attachmentStates.get(key);
                attachmentStates.put(key, new AttachmentState(false, false, !valid,
                        valid ? received.length() : previous == null ? 0 : previous.transferredBytes,
                        previous == null ? 0 : previous.totalBytes));
                if (valid && message.messageOwner instanceof TLRPC.TL_message) {
                    AttachmentMessages.apply((TLRPC.TL_message) message.messageOwner, value, received);
                    message.attachPathExists = message.mediaExists = true;
                    message.generateThumbs(false);
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
                ArrayList<Runnable> finished = attachmentCallbacks.remove(key);
                if (finished != null) for (Runnable callback : finished) callback.run();
            });
        });
    }

    /** 创建原本地气泡的最小共同字段，发送和重启恢复保持相同编号与正文。 */
    private static TLRPC.TL_message pendingMessage(long dialogId, int id, int date, String text, String base, int index) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = message.local_id = id; message.dialog_id = dialogId; message.date = date;
        message.message = text == null ? "" : text; message.out = message.unread = true;
        message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = dialogId;
        message.from_id = new TLRPC.TL_peerUser(); message.flags |= 256;
        message.media = new TLRPC.TL_messageMediaEmpty(); message.params = new HashMap<>();
        message.params.put("codexLocalId", TranscriptText.attachmentIdentity(base, index));
        message.params.put("codexBatchLocalId", base);
        message.params.put("codexAttachmentIndex", Integer.toString(index));
        return message;
    }

    /** 读取原准备器的实际文件；originalPath 只是来源标识，不能作为上传路径。 */
    private static File selectedAttachment(int account, SendMessageParams params) {
        File expected = params.path != null && new File(params.path).isAbsolute() ? new File(params.path) : null;
        if (expected != null && expected.isFile()) return expected;
        if (params.photo != null) for (int i = params.photo.sizes.size() - 1; i >= 0; i--) {
            File cached = org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(params.photo.sizes.get(i), true);
            if (expected == null) expected = cached;
            if (cached.isFile()) return cached;
        }
        return expected;
    }

    /** 已接受或结果未知均等待同身份回显，不能从原菜单重复提交。 */
    public static boolean canRetryMessage(MessageObject message) {
        return message != null && (message.messageOwner.params == null
                || !"true".equals(message.messageOwner.params.get("codexSendUncertain"))
                && !"true".equals(message.messageOwner.params.get("codexSendAccepted")));
    }

    /** 原输入框清空后立刻产生本地气泡；同组图片或文件收齐后只提交一个桌面输入。 */
    public static void sendMessage(int account, SendMessageParams params) {
        final long epoch = accountGeneration;
        if (!isAccountCurrent(epoch) || !ownsConversation(params.peer)) return;
        ArrayList<SendMessageParams> selected = new ArrayList<>();
        String groupId = params.params == null ? null : params.params.get("groupId");
        if (params.retryMessageObject == null && (params.photo != null || params.document != null)
                && groupId != null && !"0".equals(groupId)) {
            String key = account + ":" + params.peer + ":" + groupId;
            selected = attachmentGroups.computeIfAbsent(key, ignored -> new ArrayList<>());
            selected.add(params);
            if (!params.params.containsKey("final")) return;
            attachmentGroups.remove(key);
        } else selected.add(params);
        sendBatch(account, params, selected);
    }

    /** 原准备器结束不完整分组时沿同一个收齐入口交付，不靠超时猜测批次结束。 */
    public static boolean finishAttachmentGroup(int account, long groupId) {
        String suffix = ":" + groupId;
        for (String key : new ArrayList<>(attachmentGroups.keySet())) {
            if (key.startsWith(account + ":") && key.endsWith(suffix)) {
                ArrayList<SendMessageParams> selected = attachmentGroups.remove(key);
                if (selected != null && !selected.isEmpty()) sendBatch(account, selected.get(0), selected);
                return true;
            }
        }
        return false;
    }

    /** 重试复用原批次和气泡，首次发送保留每个附件的原生消息样式。 */
    private static void sendBatch(int account, SendMessageParams params, ArrayList<SendMessageParams> selected) {
        final long epoch = accountGeneration;
        if (!isAccountCurrent(epoch)) return;
        final long dialogId = params.peer;
        final String remote = remoteIds.get(dialogId);
        final String cwd = dialogDirectories.get(dialogId);
        final File directory = attachmentDirectory(dialogId);
        final OutboxStore outbox = outboxStore(dialogId);
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final String base;
        if (params.retryMessageObject != null) {
            MessageObject retry = params.retryMessageObject;
            if (!canRetryMessage(retry)) return;
            base = retry.messageOwner.params.getOrDefault("codexBatchLocalId", retry.messageOwner.params.get("codexLocalId"));
            if (base == null || sendingBatches.contains(base)) return;
            for (MessageObject pending : pendingMessages.values()) {
                if (pending.getDialogId() == dialogId && base.equals(pending.messageOwner.params.getOrDefault(
                        "codexBatchLocalId", pending.messageOwner.params.get("codexLocalId")))) messages.add(pending);
            }
            if (messages.isEmpty()) messages.add(retry);
            messages.sort((left, right) -> Integer.compare(right.getId(), left.getId()));
        } else {
            base = java.util.UUID.randomUUID().toString();
            StringBuilder text = new StringBuilder();
            for (SendMessageParams item : selected) {
                String caption = item.photo != null || item.document != null ? item.caption : item.message;
                if (caption != null && !caption.isEmpty()) { if (text.length() > 0) text.append('\n'); text.append(caption); }
            }
            org.telegram.messenger.UserConfig config = org.telegram.messenger.UserConfig.getInstance(account);
            for (int i = 0; i < selected.size(); i++) {
                SendMessageParams item = selected.get(i);
                TLRPC.TL_message message = pendingMessage(dialogId, config.getNewMessageId(),
                        (int) (System.currentTimeMillis() / 1000), i == 0 ? text.toString() : "", base, i);
                if (item.photo != null || item.document != null) {
                    File source = selectedAttachment(account, item);
                    String name = item.document == null ? source == null ? "图片.jpg" : source.getName()
                            : org.telegram.messenger.FileLoader.getDocumentFileName(item.document);
                    if (name == null || name.isEmpty()) name = source == null ? "文件" : source.getName();
                    String kind = item.photo == null ? "file" : "image";
                    message.params.put("codexSelectedFile", source == null ? "" : source.getAbsolutePath());
                    message.params.put("codexSelectedName", name); message.params.put("codexSelectedKind", kind);
                    message.params.put("codexSelectedMime", item.document == null ? "image/jpeg"
                            : item.document.mime_type == null ? "application/octet-stream" : item.document.mime_type);
                    AttachmentMessages.applySelected(message, source, name, kind);
                }
                message.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
                MessageObject object = new MessageObject(account, message, true, false);
                object.wasJustSent = true;
                object.attachPathExists = object.mediaExists = attachmentFile(object) != null;
                messages.add(object);
            }
            config.saveConfig(false);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceiveNewMessages,
                    dialogId, messages, false, 0);
            org.telegram.messenger.MediaDataController.getInstance(account).cleanDraft(dialogId,
                    params.replyToTopMsg == null ? 0 : params.replyToTopMsg.getId(), false);
        }
        sendingBatches.add(base);
        for (MessageObject pending : messages) {
            pending.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
            pending.messageOwner.params.remove("codexSendFailure");
            pendingMessages.put(pending.messageOwner.params.get("codexLocalId"), pending);
        }
        // 列表复用同一真实气泡；新批立即显示，重试旧批不抢占其他最新消息。
        publishPendingPreview(account, epoch, dialogId, messages, params.retryMessageObject == null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
        final long sendStarted = android.os.SystemClock.elapsedRealtime();
        final ArrayList<Map<String, String>> selectedMetadata = new ArrayList<>();
        for (MessageObject pending : messages) selectedMetadata.add(new HashMap<>(pending.messageOwner.params));
        // UI 可继续输入；暂存、摘要、上传和一次桌面发送始终串行进入既有发送队列。
        sendQueue.postRunnable(() -> {
            DesktopConnection connection = null;
            boolean uncertain = false;
            String failureCode = null;
            try {
                traceSend("queue", base, sendStarted);
                OutboxStore.Item stored = outbox.get(base);
                uncertain = stored != null && stored.submissionUncertain;
                if (stored == null) {
                    ArrayList<OutboxStore.Selection> choices = new ArrayList<>();
                    for (Map<String, String> metadata : selectedMetadata) {
                        if (metadata.containsKey("codexSelectedFile")) {
                            String path = metadata.get("codexSelectedFile");
                            choices.add(new OutboxStore.Selection(path == null || path.isEmpty() ? null : path,
                                    metadata.get("codexSelectedName"), metadata.get("codexSelectedKind"), metadata.get("codexSelectedMime")));
                        } else if (metadata.containsKey("codexPendingFile")) {
                            DesktopAttachment.Pending value = DesktopAttachment.Pending.read(
                                    com.google.gson.JsonParser.parseString(metadata.get("codexPendingFile")).getAsJsonObject());
                            choices.add(new OutboxStore.Selection(value.localPath, value.name, value.kind, value.mimeType));
                        }
                    }
                    MessageObject first = messages.get(0);
                    stored = OutboxStore.Item.selected(base, remote, first.messageOwner.message, first.getId(), first.messageOwner.date, choices);
                    // 任何附件读取之前先记录完整选择；首写本身失败时只能保留内存气泡。
                    outbox.put(stored);
                }
                traceSend("persisted", base, sendStarted);
                if (!isAccountCurrent(epoch)) return;
                if (stored.submissionAccepted) { finishSend(account, epoch, base, messages, remote, null, true, false, null); return; }
                // 原请求可能已派发；即使用户点击重试也等待回显，不重复进入桌面发送。
                if (stored.submissionUncertain) { finishSend(account, epoch, base, messages, remote, null, false, true, null); return; }
                // 清除旧解释必须先落盘；失败时停止本次远端工作，不能留下新失败的旧原因。
                if (stored.failureCode != null) outbox.markFailureCode(base, null);
                // 完整选择已保存再预检；已上传前缀不再受新的上传上限约束或被重传。
                for (int i = stored.uploaded.size(); i < stored.attachmentCount(); i++) {
                    String path = i < stored.attachments.size() ? stored.attachments.get(i).localPath : stored.selections.get(i).localPath;
                    if (attachmentTooLarge(dialogId, path == null ? null : new File(path))) {
                        failureCode = OutboxStore.FAILURE_FILE_TOO_LARGE;
                        throw new IOException("附件超过该电脑的上传上限");
                    }
                }
                ArrayList<DesktopAttachment.Pending> staged = new ArrayList<>(stored.attachments);
                for (int i = staged.size(); i < stored.selections.size(); i++) {
                    if (!isAccountCurrent(epoch)) return;
                    OutboxStore.Selection choice = stored.selections.get(i);
                    String localId = TranscriptText.attachmentIdentity(base, i);
                    DesktopAttachment.Pending original = AttachmentFiles.stage(
                            choice.localPath == null ? null : new File(choice.localPath), choice.name, choice.kind, choice.mimeType,
                            directory, "send\n" + localId);
                    staged.add(original);
                    outbox.rememberStaged(base, staged);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isAccountCurrent(epoch)) return;
                        MessageObject pending = pendingMessages.get(localId);
                        if (pending == null || pending.getDialogId() != dialogId) return;
                        AttachmentMessages.applyPending((TLRPC.TL_message) pending.messageOwner, original);
                        pending.attachPathExists = pending.mediaExists = true;
                        pending.generateThumbs(false);
                    });
                }
                stored = outbox.get(base);
                if (stored == null || !stored.isPrepared()) throw new IOException("附件尚未全部暂存");
                connection = dialogConnection(dialogId);
                if (!attachmentCurrent(epoch, dialogId, remote, connection)) throw new IOException("对话所属电脑尚未连接");
                String linked = linkedSessions.get(dialogId);
                if (linked == null) {
                    linked = connection.openConversation(remote).get("sessionId").getAsString();
                    if (!attachmentCurrent(epoch, dialogId, remote, connection)) throw new IOException("对话连接已变化");
                    linkedSessions.put(dialogId, linked);
                }
                traceSend("linked", base, sendStarted);
                final DesktopConnection transferConnection = connection;
                ArrayList<DesktopAttachment> uploaded = new ArrayList<>(stored.uploaded);
                if (!stored.attachments.isEmpty() && (cwd == null || cwd.isEmpty())) throw new IOException("原会话工作目录暂不可用");
                for (int i = uploaded.size(); i < stored.attachments.size(); i++) {
                    final DesktopAttachment.Pending original = stored.attachments.get(i);
                    final String localId = TranscriptText.attachmentIdentity(base, i);
                    final String key = epoch + ":" + dialogId + ":" + localId;
                    AttachmentTransfer.Result result;
                    // 选择后的文件或限额可能变化，实际上传前再次读取本件字节数。
                    if (attachmentTooLarge(dialogId, new File(original.localPath))) {
                        failureCode = OutboxStore.FAILURE_FILE_TOO_LARGE;
                        throw new IOException("附件超过该电脑的上传上限");
                    }
                    try {
                        result = transfer(epoch, dialogId, remote, transferConnection,
                                (done, total) -> attachmentProgress(account, epoch, dialogId, remote, transferConnection, key, true, done, total))
                                .upload(new File(original.localPath), original.kind, localId, cwd);
                    } catch (IOException uploadFailure) {
                        // 只有本次上传的明确拒绝可标大小，其他阶段错误继续原失败语义。
                        failureCode = attachmentUploadFailureCode(uploadFailure);
                        throw uploadFailure;
                    }
                    uploaded.add(original.uploaded(result.path, result.sizeBytes, result.sha256));
                    outbox.rememberUploaded(base, uploaded);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isAccountCurrent(epoch)) attachmentStates.put(key,
                                new AttachmentState(false, true, false, original.sizeBytes, original.sizeBytes));
                    });
                }
                if (!attachmentCurrent(epoch, dialogId, remote, connection)) throw new IOException("对话连接已变化");
                outbox.markSubmissionUncertain(base, true);
                uncertain = true;
                try { connection.send(linked, stored.text, stored.localId, uploaded); }
                catch (DesktopConnection.RpcNotDispatchedException | DesktopConnection.SendRejectedException notAccepted) {
                    outbox.markSubmissionUncertain(base, false);
                    uncertain = false;
                    throw notAccepted;
                }
                traceSend("ack", base, sendStarted);
                // ACK事实沿原记录落盘；磁盘失败不推翻当前已接受，冷恢复仍只按已存未知等待回显。
                try { outbox.markSubmissionAccepted(base); }
                catch (IOException error) { android.util.Log.w("CodexBridge", "send_ack_persist_failed"); }
                finishSend(account, epoch, base, messages, remote, connection, true, false, null);
            } catch (Exception error) {
                if (failureCode != null && !uncertain && isAccountCurrent(epoch)) {
                    try { outbox.markFailureCode(base, failureCode); }
                    catch (IOException ignored) { /* 保留原待发记录；当前气泡仍可解释，重开只认已落盘事实。 */ }
                }
                finishSend(account, epoch, base, messages, remote, connection, false, uncertain, failureCode);
            }
        });
    }

    /** 仅匹配上传层已确认的两条大小拒绝，不递归原因或持久化任意服务器正文。 */
    private static String attachmentUploadFailureCode(IOException error) {
        return "File exceeds upload size limit".equals(error.getMessage())
                || "File exceeds the server-routed transfer size limit".equals(error.getMessage())
                ? OutboxStore.FAILURE_FILE_TOO_LARGE : null;
    }

    /** 保留原收发分段计时，仅记录阶段与随机消息身份摘要，不记录正文或文件路径。 */
    private static void traceSend(String phase, String localId, long started) {
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge",
                "send_phase=" + phase + " key=" + localId.hashCode()
                        + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
    }

    /** 原账号与会话的ACK不因连接替换失效；未知使用原等待时钟，明确失败才显示重试。 */
    private static void finishSend(int account, long epoch, String base, ArrayList<MessageObject> messages,
            String remote, DesktopConnection connection, boolean success, boolean uncertain, String failureCode) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!isAccountCurrent(epoch) || !remote.equals(remoteIds.get(messages.get(0).getDialogId()))) return;
            sendingBatches.remove(base);
            for (MessageObject pending : messages) {
                String localId = pending.messageOwner.params.get("codexLocalId");
                if (pendingMessages.get(localId) != pending) continue;
                boolean accepted = success || "true".equals(pending.messageOwner.params.get("codexSendAccepted"));
                if (!accepted && !uncertain && OutboxStore.FAILURE_FILE_TOO_LARGE.equals(failureCode))
                    pending.messageOwner.params.put("codexSendFailure", OutboxStore.FAILURE_FILE_TOO_LARGE);
                else pending.messageOwner.params.remove("codexSendFailure");
                if (accepted) pending.messageOwner.params.put("codexSendAccepted", "true");
                if (!accepted && uncertain) pending.messageOwner.params.put("codexSendUncertain", "true");
                else pending.messageOwner.params.remove("codexSendUncertain");
                pending.messageOwner.send_state = accepted ? MessageObject.MESSAGE_SEND_STATE_SENT
                        : uncertain ? MessageObject.MESSAGE_SEND_STATE_SENDING : MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                if (isAttachmentMessage(pending)) {
                    String key = attachmentKey(pending);
                    AttachmentState previous = attachmentStates.get(key);
                    attachmentStates.put(key, new AttachmentState(false, true, !accepted && !uncertain,
                            previous == null ? 0 : previous.transferredBytes, previous == null ? 0 : previous.totalBytes));
                }
                if (accepted) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageReceivedByServer,
                        pending.getId(), pending.getId(), pending.messageOwner, pending.getDialogId(), 0L, 0, false);
                else if (!uncertain) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageSendError, pending.getId());
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
        });
    }

    /** 弹窗与一次真实读取绑定；不允许把旧账号或旧页面的决定移到新页面。 */
    public static final class ApprovalReview {
        public final ArrayList<DesktopApproval> requests;
        private final long epoch, generation, dialogId;
        private final DesktopConnection connection;
        private final String linked;
        private final android.content.SharedPreferences decisions;

        /** 保存请求的归属及生命周期；仅由当前控制读取创建。 */
        private ApprovalReview(long epoch, long generation, long dialogId, DesktopConnection connection,
                String linked, ArrayList<DesktopApproval> requests, android.content.SharedPreferences decisions) {
            this.epoch = epoch; this.generation = generation; this.dialogId = dialogId;
            this.connection = connection; this.linked = linked; this.requests = requests; this.decisions = decisions;
        }

        /** 同一版本请求只保留一个提交身份，偏好中不保存操作正文或凭据。 */
        private String key(DesktopApproval request) {
            return TranscriptStore.digest(connection.machineId + "\n" + linked + "\n" + request.turnId
                    + "\n" + request.requestId + "\n" + request.revision);
        }

        /** 请求已发出但未明确拒绝时，重新打开弹窗也不能重复提交。 */
        public boolean alreadyIssued(DesktopApproval request) { return decisions.contains(key(request)); }

        /** 只允许原账号和原页面接收结束通知；连接换代不应让该页面永久等待。 */
        private boolean pageCurrent() {
            return isAccountCurrent(epoch) && generation == watchGeneration && dialogId == watchedDialog;
        }

        /** 发出决定还必须匹配读取时的连接，不能把旧弹窗提交到新连接。 */
        private boolean current() {
            return pageCurrent() && dialogConnection(dialogId) == connection;
        }
    }

    /** 点击顶栏才读取控制快照；审批独立串行，不阻塞发送，也不增加后台查询。 */
    public static void readApprovals(long dialogId, java.util.function.BiConsumer<ApprovalReview, String> callback) {
        final long epoch = accountGeneration, generation = watchGeneration;
        if (!isAccountCurrent(epoch) || watchedDialog != dialogId) return;
        final String remote = remoteIds.get(dialogId);
        final android.content.SharedPreferences decisions = ApplicationLoader.applicationContext.getSharedPreferences(
                "codex-approval-" + TranscriptStore.digest(session.server + "\n" + session.accountId), 0);
        approvalQueue.postRunnable(() -> {
            ApprovalReview review = null; String failure = null;
            try {
                if (!isAccountCurrent(epoch) || generation != watchGeneration || watchedDialog != dialogId) return;
                DesktopConnection connection = dialogConnection(dialogId);
                if (connection == null) throw new java.io.IOException();
                String linked = linkedSessions.get(dialogId);
                if (linked == null) {
                    linked = connection.openConversation(remote).get("sessionId").getAsString();
                    if (!isAccountCurrent(epoch) || generation != watchGeneration) return;
                    linkedSessions.put(dialogId, linked);
                }
                review = new ApprovalReview(epoch, generation, dialogId, connection, linked,
                        connection.readApprovals(linked), decisions);
            } catch (Exception error) { failure = "暂时无法读取待处理操作，请稍后重试或在电脑查看。"; }
            final ApprovalReview loaded = review; final String message = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (isAccountCurrent(epoch) && generation == watchGeneration && watchedDialog == dialogId)
                    callback.accept(loaded, message);
            });
        });
    }

    /** 明确点击后只提交一次，并回读请求是否仍在；请求消失也不冒称决定已生效。 */
    public static void decideApproval(ApprovalReview review, DesktopApproval request, boolean allow,
            java.util.function.Consumer<String> callback) {
        approvalQueue.postRunnable(() -> {
            if (!review.current()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (review.pageCurrent()) callback.accept("电脑连接已变化，请重新查看待处理操作。");
                });
                return;
            }
            String message;
            try {
                if (!review.requests.contains(request) || !request.canDecide) throw new IllegalStateException();
                if (review.alreadyIssued(request)) {
                    message = "此请求已提交，结果尚需核对，请在电脑查看。";
                } else {
                    String operation = java.util.UUID.randomUUID().toString();
                    if (!review.decisions.edit().putString(review.key(request), operation).commit())
                        throw new java.io.IOException();
                    // 先持久化提交身份；断线、退出或进程中止后不自动重发未知结果。
                    if (!review.current()) {
                        // 尚未调用网络接口的取消不属于未知投递，允许下次重新读取后决定。
                        review.decisions.edit().remove(review.key(request)).commit();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (review.pageCurrent()) callback.accept("电脑连接已变化，请重新查看待处理操作。");
                        });
                        return;
                    }
                    JsonObject result = review.connection.decideApproval(review.linked, request, operation, allow);
                    if ("rejected".equals(result.get("status").getAsString())) {
                        review.decisions.edit().remove(review.key(request)).commit();
                        message = "电脑未接受此决定，请重新读取当前操作。";
                    } else {
                        boolean remains = false;
                        for (DesktopApproval fresh : review.connection.readApprovals(review.linked))
                            if (fresh.turnId.equals(request.turnId) && fresh.requestId.equals(request.requestId)
                                    && fresh.revision.equals(request.revision)) remains = true;
                        message = remains ? "决定已提交，电脑尚未确认处理结果，请在电脑查看。"
                                : "原请求已不在待处理列表，请查看对话的最新进展。";
                    }
                }
            } catch (Exception error) { message = "暂时无法确认处理结果，请重新查看或在电脑核对。"; }
            final String outcome = message;
            AndroidUtilities.runOnUIThread(() -> { if (review.pageCurrent()) callback.accept(outcome); });
        });
    }

    /** 当前问题弹窗持有一次读取的来源，迟到结果不会进入另一个账号或页面。 */
    public static final class QuestionReview {
        public final ArrayList<DesktopQuestion> requests;
        private final PasswordLogin.Session account;
        private final long epoch, generation, dialogId;
        private final int selectedAccount;
        private final DesktopConnection connection;
        private final String machine, remote, sourceKind, sourceHome, linked;
        private final android.content.SharedPreferences issued;
        private SessionStatus.Snapshot observation;
        private long expiry = -1, expiryStart = -1;
        private boolean refreshing, invalidNotified, revalidationRequired;

        /** 固定原题所属账号、页面、电脑、remote、连接、来源和 linked。期限单独保存，不参与误关表单。 */
        private QuestionReview(PasswordLogin.Session account, long epoch, int selectedAccount, long generation,
                long dialogId, String machine, String remote, DesktopConnection connection, String sourceKind,
                String sourceHome, String linked, ArrayList<DesktopQuestion> requests,
                android.content.SharedPreferences issued, SessionStatus.Snapshot observation) {
            this.account = account; this.epoch = epoch; this.selectedAccount = selectedAccount;
            this.generation = generation; this.dialogId = dialogId; this.machine = machine; this.remote = remote;
            this.connection = connection; this.sourceKind = sourceKind; this.sourceHome = sourceHome;
            this.linked = linked; this.requests = requests; this.issued = issued; this.observation = observation;
        }

        /** 同账号、电脑、会话和题目修订共享一个提交标识。 */
        private String key(DesktopQuestion request) {
            return TranscriptStore.digest(connection.machineId + "\n" + linked + "\n" + request.identity());
        }

        /** 请求结果未知时不让重新打开弹窗触发重复提交。 */
        public boolean alreadyIssued(DesktopQuestion request) { return issued.contains(key(request)); }

        /** 连接变化后原页仍能收到结束提示，退出页面则忽略迟到结果。 */
        private boolean pageCurrent() {
            return isAccountCurrent(epoch) && generation == watchGeneration && dialogId == watchedDialog;
        }

        /**
         * 只核对读取时固定的身份。过期不在这里，避免原表单被误关。
         * linked 为空只用于打开前的引导；已经记下的值变了就不再是原题。
         */
        static boolean identityCurrent(PasswordLogin.Session account, long epoch, int selectedAccount, long generation,
                long dialogId, String machine, String remote, DesktopConnection connection, String sourceKind,
                String sourceHome, String linked, boolean linkedRequired) {
            if (session != account || !isAccountCurrent(epoch)) return false;
            if (org.telegram.messenger.UserConfig.selectedAccount != selectedAccount) return false;
            if (generation != watchGeneration || dialogId != watchedDialog) return false;
            if (machine == null || remote == null || machine.isEmpty() || remote.isEmpty()) return false;
            if (!machine.equals(dialogMachines.get(dialogId)) || !remote.equals(remoteIds.get(dialogId))) return false;
            if (connection == null || dialogConnection(dialogId) != connection) return false;
            com.google.gson.JsonObject source = DesktopConnection.userCodexSource();
            if (source == null || source.get("kind") == null || source.get("home") == null
                    || !sourceKind.equals(source.get("kind").getAsString())
                    || !sourceHome.equals(source.get("home").getAsString())) return false;
            if (!linkedRequired) return true;
            return linked != null && !linked.isEmpty() && linked.equals(linkedSessions.get(dialogId));
        }

        /** 用户回答只能交给显示原题时的同一身份；过期由期限单独拒绝。 */
        public boolean current() {
            return identityCurrent(account, epoch, selectedAccount, generation, dialogId, machine, remote, connection,
                    sourceKind, sourceHome, linked, true);
        }

        /** 队列上只比较入队时的到期时刻，不再读取界面状态库。 */
        private static boolean fresh(long expiry, long expiryStart) {
            if (expiry < 0 || expiryStart < 0) return false;
            long elapsed = android.os.SystemClock.elapsedRealtime();
            return elapsed >= expiryStart && elapsed < expiry;
        }

        /** 界面线程记下当前期限。相同的 current 观察只更新这张票，不另读题。 */
        private void adoptExpiry(long expiry) {
            this.expiry = expiry;
            this.expiryStart = expiry < 0 ? -1 : expiry - SessionStatus.FRESHNESS_MS;
        }

        /** 只比较本题组在原STATUS中的待答身份，不因采集时间或其它审批变化重复读题。 */
        private String observationKey(SessionStatus.Snapshot snapshot, DesktopQuestion request) {
            StringBuilder key = new StringBuilder(snapshot.validity).append('\n').append(snapshot.source)
                    .append('\n').append(snapshot.turnId).append('\n').append(snapshot.state);
            if ("async_questions".equals(request.kind)) {
                for (DesktopQuestion.Question question : request.questions)
                    key.append('\n').append(snapshot.questionIds.contains(question.id));
            } else key.append('\n').append(snapshot.questionIds.contains(request.requestId.getAsString()));
            return key.toString();
        }
    }

    /** 仅已打开的原表单调用；先看事实是否仍有效，再按身份去重。未知只停止编辑而不伪报已答。 */
    public static boolean refreshQuestions(QuestionReview review, DesktopQuestion request,
            java.util.function.BiConsumer<QuestionReview, String> callback) {
        if (!review.current()) {
            callback.accept(null, "电脑连接或当前会话已变化。");
            return true;
        }
        SessionStatus.Snapshot observed = status(review.dialogId);
        long expiry = statuses.currentExpiry(review.dialogId, review.machine, android.os.SystemClock.elapsedRealtime());
        boolean fresh = "current".equals(observed.validity) && QuestionReview.fresh(expiry, expiry < 0 ? -1 : expiry - SessionStatus.FRESHNESS_MS);
        if (!fresh) {
            boolean same = review.observationKey(observed, request).equals(review.observationKey(review.observation, request));
            review.observation = observed;
            review.adoptExpiry(-1);
            if (same && review.invalidNotified) return false;
            review.invalidNotified = true;
            callback.accept(null, "暂时无法核对问题状态，回答已保留。");
            return true;
        }
        review.invalidNotified = false;
        // 在途只合并一次。失败后即使状态回到上次成功的键，下一次 current 仍要再核对。
        if (review.refreshing) return false;
        if (!review.revalidationRequired
                && review.observationKey(observed, request).equals(review.observationKey(review.observation, request))) {
            review.observation = observed;
            review.adoptExpiry(expiry);
            return false;
        }
        review.refreshing = true;
        readQuestions(review.dialogId, (loaded, error) -> {
            review.refreshing = false;
            if (!review.pageCurrent()) return;
            if (!review.current()) callback.accept(null, "电脑连接已变化。");
            else if (loaded != null && error == null) {
                // 忙碌期间STATUS仍会前进；旧读回不得重新启用已失效的原表单。
                review.revalidationRequired = false;
                review.observation = loaded.observation;
                if (!refreshQuestions(review, request, callback)) callback.accept(loaded, null);
            } else {
                review.revalidationRequired = true;
                review.adoptExpiry(-1);
                callback.accept(loaded, error);
            }
        });
        return true;
    }

    /** 沿已有控制队列按需读题，不把题目或保密回答写入普通历史缓存。 */
    public static void readQuestions(long dialogId, java.util.function.BiConsumer<QuestionReview, String> callback) {
        final long epoch = accountGeneration, generation = watchGeneration;
        final PasswordLogin.Session account = session;
        final int selectedAccount = org.telegram.messenger.UserConfig.selectedAccount;
        if (!isAccountCurrent(epoch) || watchedDialog != dialogId) {
            callback.accept(null, "当前会话已变化，请重新打开问题。"); return;
        }
        final String machine = dialogMachines.get(dialogId);
        final String remote = remoteIds.get(dialogId);
        final DesktopConnection connection = dialogConnection(dialogId);
        final com.google.gson.JsonObject source = DesktopConnection.userCodexSource();
        final String sourceKind = source.get("kind").getAsString();
        final String sourceHome = source.get("home").getAsString();
        final SessionStatus.Snapshot observation = status(dialogId);
        // 界面入队前固定已知 linked。之后映射变成别的值不能迁移，空串也不当作可打开的会话。
        final String knownLinked = linkedSessions.get(dialogId);
        if (knownLinked != null && knownLinked.isEmpty()) {
            callback.accept(null, "电脑连接已变化，请重新查看问题。");
            return;
        }
        final android.content.SharedPreferences issued = ApplicationLoader.applicationContext.getSharedPreferences(
                "codex-question-" + TranscriptStore.digest(session.server + "\n" + session.accountId), 0);
        approvalQueue.postRunnable(() -> {
            QuestionReview review = null; String failure = null;
            try {
                String liveLinked = linkedSessions.get(dialogId);
                String linked;
                if (knownLinked == null) {
                    if (liveLinked != null) throw new java.io.IOException();
                    if (!QuestionReview.identityCurrent(account, epoch, selectedAccount, generation, dialogId, machine, remote,
                            connection, sourceKind, sourceHome, null, false)) throw new java.io.IOException();
                    String opened = connection.openConversation(remote).get("sessionId").getAsString();
                    if (opened == null || opened.isEmpty()) throw new java.io.IOException();
                    if (!QuestionReview.identityCurrent(account, epoch, selectedAccount, generation, dialogId, machine, remote,
                            connection, sourceKind, sourceHome, null, false)) throw new java.io.IOException();
                    String raced = linkedSessions.putIfAbsent(dialogId, opened);
                    if (raced != null && !raced.equals(opened)) throw new java.io.IOException();
                    linked = opened;
                } else if (!knownLinked.equals(liveLinked)) {
                    throw new java.io.IOException();
                } else linked = knownLinked;
                if (!QuestionReview.identityCurrent(account, epoch, selectedAccount, generation, dialogId, machine, remote,
                        connection, sourceKind, sourceHome, linked, true)) throw new java.io.IOException();
                ArrayList<DesktopQuestion> questions = connection.readQuestions(linked);
                if (!QuestionReview.identityCurrent(account, epoch, selectedAccount, generation, dialogId, machine, remote,
                        connection, sourceKind, sourceHome, linked, true)) throw new java.io.IOException();
                review = new QuestionReview(account, epoch, selectedAccount, generation, dialogId, machine, remote,
                        connection, sourceKind, sourceHome, linked, questions, issued, observation);
            } catch (Exception error) { failure = "暂时无法读取问题，请稍后重试。"; }
            final QuestionReview loaded = review; final String message = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (!isAccountCurrent(epoch) || generation != watchGeneration || watchedDialog != dialogId) return;
                // 发布前再取界面上的最新事实。仍过期可以交给原表单停用，不能当成可回答。
                if (loaded == null || !loaded.current()) callback.accept(null, "电脑连接已变化，请重新查看问题。");
                else {
                    long expiry = statuses.currentExpiry(dialogId, loaded.machine, android.os.SystemClock.elapsedRealtime());
                    loaded.adoptExpiry(expiry);
                    callback.accept(loaded, message);
                }
            });
        });
    }

    /** 原题答案一次提交；明确拒绝可重新读题，未知保留意图与页面中的回答。 */
    public static void answerQuestions(QuestionReview review, DesktopQuestion request,
            java.util.Map<String, String> answers, java.util.function.BiConsumer<String, String> callback) {
        final java.util.Map<String, String> submitted = new java.util.LinkedHashMap<>(answers);
        final long expiry = statuses.currentExpiry(review.dialogId, review.machine, android.os.SystemClock.elapsedRealtime());
        final long expiryStart = expiry < 0 ? -1 : expiry - SessionStatus.FRESHNESS_MS;
        approvalQueue.postRunnable(() -> {
            String status = "rejected", message;
            String operation = null;
            try {
                if (!review.current()) throw new IllegalStateException("电脑连接已变化，请重新查看问题。");
                if (!QuestionReview.fresh(expiry, expiryStart))
                    throw new IllegalStateException("暂时无法核对问题状态，回答已保留。");
                if (!review.requests.contains(request) || !request.canAnswer)
                    throw new IllegalStateException("此问题已不能回答，请查看最新进展。");
                if (review.alreadyIssued(request)) {
                    status = "unknown"; message = "回答已提交，结果尚需核对，请查看最新进展。";
                } else {
                    operation = java.util.UUID.randomUUID().toString();
                    request.action(review.connection.machineId, review.linked, operation, submitted);
                    if (!review.issued.edit().putString(review.key(request), operation).commit())
                        throw new java.io.IOException();
                    // 写入期间身份或期限失效时尚未外发，可以撤去本次意图；网络调用后的未知则保留。
                    if (!review.current() || !QuestionReview.fresh(expiry, expiryStart)) {
                        review.issued.edit().remove(review.key(request)).commit();
                        throw new IllegalStateException(!review.current()
                                ? "电脑连接已变化，请重新查看问题。" : "暂时无法核对问题状态，回答已保留。");
                    }
                    status = "unknown";
                    JsonObject result = review.connection.answerQuestions(review.linked, request, operation, submitted);
                    String outcome = result.get("status").getAsString();
                    if ("rejected".equals(outcome)) {
                        status = "rejected"; review.issued.edit().remove(review.key(request)).commit();
                        message = "电脑未接受回答，问题可能已处理或更新。请重新查看；本次回答已保留。";
                    } else if ("accepted".equals(outcome)) {
                        status = "accepted"; message = "回答已收到。";
                    } else if ("recorded".equals(outcome)) {
                        status = "recorded"; message = "电脑已记录回答，请查看任务后续进展。";
                    } else { message = "暂时无法确认回答结果，本次回答已保留，请查看最新进展。"; }
                }
            } catch (DesktopConnection.RpcNotDispatchedException error) {
                // 只撤去本次且明确未外发的意图，其他提交身份与未知投递结果保持不变。
                if (operation != null && operation.equals(review.issued.getString(review.key(request), null)))
                    review.issued.edit().remove(review.key(request)).commit();
                status = "rejected";
                message = "电脑连接已中断，回答未发送；本次回答已保留，请连接恢复后重新查看。";
            } catch (IllegalStateException error) { message = error.getMessage(); }
            catch (Exception error) { message = "暂时无法确认回答结果，本次回答已保留，请稍后查看。"; }
            final String outcome = status, notice = message;
            AndroidUtilities.runOnUIThread(() -> { if (review.pageCurrent()) callback.accept(outcome, notice); });
        });
    }

    /** 已加载的额度页先显示同账号、同电脑的原采集值；不将它标为实时余额。 */
    public static AccountUsage cachedAccountUsage(String machineId) {
        return loggedIn() && machineId != null ? accountUsageSnapshots.get(machineId) : null;
    }

    /** 已选来源同账号恢复；只读内存，页面不等待磁盘或连接锁。 */
    public static String selectedAccountUsageMachine() {
        return loggedIn() ? accountUsageMachine : null;
    }

    /** 保存明确来源选择，仍在原通用队列串行写盘，不创建新额度请求。 */
    public static void selectAccountUsageMachine(String machine) {
        final long epoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        if (!isAccountCurrent(epoch) || machine == null || machine.isEmpty()) return;
        accountUsageMachine = machine;
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(epoch) || session != owner) return;
            try { accountUsageStore(owner).select(machine); }
            catch (IOException error) { /* 写盘失败保留原内存选择，下次启动不冒充已保存。 */ }
        });
    }

    /** 复用产品私有非备份目录，服务和登录账号在被动存储内部隔离。 */
    private static AccountUsageStore accountUsageStore(PasswordLogin.Session owner) {
        return new AccountUsageStore(new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "codex-account-usage"), owner.server, owner.accountId);
    }

    static {
        SessionStatus.onConfirmedGoal(CodexRuntime::rememberAcceptedGoal);
    }

    /** 目标投影单独放在 codex-goals，避免和额度目录里的 scope.json 撞名。 */
    private static GoalDisplayStore goalDisplayStore(PasswordLogin.Session owner) {
        return new GoalDisplayStore(new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "codex-goals"), owner.server, owner.accountId);
    }

    /** 入队时记下的身份仍有效。linked 还未知时不因此拒绝；已经知道的值变了就拒绝。 */
    private static boolean goalCacheIdentityCurrent(long epoch, PasswordLogin.Session owner, long dialogId,
            String machine, String remote, String linked, String sourceKind, String sourceHome) {
        if (session != owner || !isAccountCurrent(epoch)) return false;
        if (!java.util.Objects.equals(machine, dialogMachines.get(dialogId))
                || !java.util.Objects.equals(remote, remoteIds.get(dialogId))) return false;
        if (linked != null && !linked.equals(linkedSessions.get(dialogId))) return false;
        JsonObject source = DesktopConnection.userCodexSource();
        return sourceKind.equals(source.get("kind").getAsString()) && sourceHome.equals(source.get("home").getAsString());
    }

    /** 界面已经接受的不可变目标进入原全局队列。返回页面后不再要求当前还停在这个对话。 */
    private static void rememberAcceptedGoal(long dialogId, String machine, String remote, SessionStatus.Goal goal) {
        final long epoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        final String linked = linkedSessions.get(dialogId);
        JsonObject source = DesktopConnection.userCodexSource();
        final String sourceKind = source.get("kind").getAsString();
        final String sourceHome = source.get("home").getAsString();
        Utilities.globalQueue.postRunnable(() -> {
            if (!goalCacheIdentityCurrent(epoch, owner, dialogId, machine, remote, linked, sourceKind, sourceHome)) return;
            try {
                goalDisplayStore(owner).save(machine, remote, sourceKind, sourceHome, goal.availability, goal.objective,
                        goal.status, goal.tokenBudget, goal.tokensUsed, goal.timeUsedSeconds, goal.updatedAt,
                        () -> goalCacheIdentityCurrent(epoch, owner, dialogId, machine, remote, linked, sourceKind, sourceHome));
            } catch (java.io.IOException error) { /* 原子写失败保留原文件和已经接受的内存事实。 */ }
        });
    }

    /** 绑定之后把上次目标填进尚未确认的槽。磁盘任务不读取内存状态，界面线程才写回事实。 */
    private static void scheduleGoalRestore(long dialogId, String machine, String remote, PasswordLogin.Session owner, long epoch) {
        final String linked = linkedSessions.get(dialogId);
        JsonObject source = DesktopConnection.userCodexSource();
        final String sourceKind = source.get("kind").getAsString();
        final String sourceHome = source.get("home").getAsString();
        Utilities.globalQueue.postRunnable(() -> {
            if (!goalCacheIdentityCurrent(epoch, owner, dialogId, machine, remote, linked, sourceKind, sourceHome)) return;
            GoalDisplayStore.Record record = goalDisplayStore(owner).find(machine, remote, sourceKind, sourceHome);
            if (record == null) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (!goalCacheIdentityCurrent(epoch, owner, dialogId, machine, remote, linked, sourceKind, sourceHome)) return;
                if (!statuses.offerCachedGoal(dialogId, machine, record.availability, record.objective, record.status,
                        record.tokenBudget, record.tokensUsed, record.timeUsedSeconds, record.updatedAt)) return;
                NotificationCenter.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                        .postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
            });
        });
    }

    /** 原通用队列先恢复本地；回调仍经账号代次检查，不能覆盖已到达的新采集或明确选择。 */
    private static void restoreAccountUsageCache(PasswordLogin.Session owner, long epoch) {
        if (!isAccountCurrent(epoch) || session != owner || accountUsageRestoredEpoch == epoch) return;
        accountUsageRestoredEpoch = epoch;
        try {
            AccountUsageStore.Snapshot saved = accountUsageStore(owner).read();
            AndroidUtilities.runOnUIThread(() -> {
                if (!isAccountCurrent(epoch) || session != owner) return;
                for (Map.Entry<String, AccountUsage> entry : saved.machines.entrySet())
                    accountUsageSnapshots.putIfAbsent(entry.getKey(), entry.getValue());
                if (accountUsageMachine == null) accountUsageMachine = saved.selectedMachine;
            });
        } catch (IOException error) { /* 缺失或损坏本地投影不阻止原网络读取。 */ }
    }

    /** 只落地已采集值；明确采集账号变化删除该电脑旧值，临时网络失败不清旧缓存。 */
    private static void saveAccountUsageCache(String machine, AccountUsage usage,
            PasswordLogin.Session owner, long epoch) {
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(epoch) || session != owner
                    || accountUsageSnapshots.get(machine) != usage) return;
            try { accountUsageStore(owner).save(machine, usage.available ? usage : null); }
            catch (IOException error) { /* 写盘失败不撤回已展示的真实采集值。 */ }
        });
    }

    /** 原额度入口的一次在途读取；完成后移除，只保留各页面等待中的回调。 */
    private static final class AccountUsageRead {
        final long epoch;
        volatile DesktopConnection connection;
        volatile boolean completed;
        final ArrayList<java.util.function.BiConsumer<AccountUsage, String>> callbacks = new ArrayList<>();

        /** 固定账号代次和已知连接；首次建立连接由原后台入口补齐。 */
        AccountUsageRead(long epoch, DesktopConnection connection) {
            this.epoch = epoch;
            this.connection = connection;
        }
    }

    /** 界面入口按同账号、电脑和连接合并在途读取，仍沿原队列执行且不增加轮询。 */
    public static void readAccountUsage(String machineId,
            java.util.function.BiConsumer<AccountUsage, String> callback) {
        final long epoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        if (!isAccountCurrent(epoch) || machineId == null || machineId.isEmpty()) {
            callback.accept(null, "请先选择已连接的电脑。"); return;
        }
        AccountUsageRead pending = accountUsageReads.get(machineId);
        // 建连发布到返回的间隙仍可合并；后台已结束的首连失败不能吞掉新连接读取。
        if (pending != null && pending.epoch == epoch && ((!pending.completed && pending.connection == null)
                || pending.connection == desktopConnections.get(machineId))) {
            pending.callbacks.add(callback);
            return;
        }
        DesktopConnection current = desktopConnections.get(machineId);
        final AccountUsageRead read = new AccountUsageRead(epoch, current);
        read.callbacks.add(callback);
        accountUsageReads.put(machineId, read);
        Utilities.globalQueue.postRunnable(() -> {
            restoreAccountUsageCache(owner, epoch);
            approvalQueue.postRunnable(() -> {
                AccountUsage result = null; String failure = null;
                try {
                    if (isAccountCurrent(epoch)) {
                        DesktopConnection expected = read.connection;
                        if (expected != null && desktopConnections.get(machineId) != expected) throw new java.io.IOException();
                        // 网络建连仍在原队列；不能把旧连接上排队的读取迁给后来替换的连接。
                        DesktopConnection connection = openDesktop(machineId);
                        if (expected != null && connection != expected) throw new java.io.IOException();
                        read.connection = connection;
                        if (isAccountCurrent(epoch) && desktopConnections.get(machineId) == connection)
                            result = connection.readAccountUsage();
                    }
                } catch (Exception error) { failure = "暂时无法读取该电脑的额度，请检查连接后刷新。"; }
                finally { read.completed = true; }
                final AccountUsage loaded = result; final String message = failure;
                AndroidUtilities.runOnUIThread(() -> {
                    boolean ownsRead = accountUsageReads.get(machineId) == read;
                    if (ownsRead) accountUsageReads.remove(machineId);
                    if (!isAccountCurrent(epoch)) return;
                    if (!ownsRead || read.connection != desktopConnections.get(machineId)) {
                        // 旧来源只结束自己的回调，不清除新连接的在途记录或已发布额度。
                        for (java.util.function.BiConsumer<AccountUsage, String> waiting : read.callbacks)
                            waiting.accept(null, "电脑连接已变化，请刷新额度。");
                        return;
                    }
                    // 先解除本次合并，再发布原快照；回调中主动刷新仍会发起下一次真实读取。
                    if (loaded != null && (loaded.available || "account_changed".equals(loaded.reason))) {
                        accountUsageSnapshots.put(machineId, loaded);
                        saveAccountUsageCache(machineId, loaded, owner, epoch);
                    } else if (loaded != null) accountUsageSnapshots.putIfAbsent(machineId, loaded);
                    for (java.util.function.BiConsumer<AccountUsage, String> waiting : read.callbacks)
                        waiting.accept(loaded, message);
                });
            });
        });
    }

    /** 原失败气泡的删除只清理手机待发记录，不操作桌面消息。 */
    public static boolean deletePendingMessages(int account, long dialogId, ArrayList<Integer> messageIds) {
        final long accountEpoch = accountGeneration;
        if (!isAccountCurrent(accountEpoch)) return false;
        if (!enabled() || !remoteIds.containsKey(dialogId) || messageIds == null || messageIds.isEmpty()) return false;
        for (Integer id : messageIds) if (id == null || id >= 0) return false;
        final ArrayList<Integer> selected = new ArrayList<>(messageIds);
        final String remote = remoteIds.get(dialogId);
        final OutboxStore outbox = outboxStore(dialogId);
        // 与发送落盘串行，避免删除完成后较早排队的发送又把记录写回来。
        sendQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch)) return;
            try {
                for (OutboxStore.Item item : outbox.list(remote)) {
                    int count = Math.max(1, item.attachmentCount());
                    boolean remove = false;
                    for (int i = 0; i < count; i++) if (selected.contains(item.messageId - i)) remove = true;
                    if (remove) {
                        outbox.remove(item.localId);
                        for (int i = 0; i < count; i++) if (!selected.contains(item.messageId - i)) selected.add(item.messageId - i);
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    pendingMessages.entrySet().removeIf(entry -> entry.getValue().getDialogId() == dialogId
                            && selected.contains(entry.getValue().getId()));
                    ArrayList<MessageObject> preview = MessagesController.getInstance(account).dialogMessage.get(dialogId);
                    if (preview != null && !preview.isEmpty() && selected.contains(preview.get(0).getId())) {
                        MessagesController.getInstance(account).dialogMessage.remove(dialogId);
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
                        restoreDialogPreviews(account, accountEpoch, dialogMachines.get(dialogId), java.util.Collections.singletonMap(remote, dialogId));
                    }
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDeleted,
                            selected, 0L, false, false, false, 0);
                });
            } catch (Exception error) {
                // 持久化失败时保留原气泡，不宣称删除成功。
                android.util.Log.w("CodexBridge", "pending_delete_failed");
            }
        });
        return true;
    }

    /** 只记录失败阶段、异常类型及已知协议原因，不记录消息、游标、会话身份或异常正文。 */
    private static void logTranscriptFailure(String stage, Exception error, JsonObject page) {
        if (!org.telegram.messenger.BuildVars.DEBUG_VERSION) return;
        Throwable cause = error;
        for (int i = 0; i < 3 && (cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof java.util.concurrent.CompletionException); i++) {
            if (cause.getCause() == null || cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        String reason = "none";
        com.google.gson.JsonElement value = page == null ? null : page.get("truncationReason");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String candidate = value.getAsString();
            if ("source_discontinuity".equals(candidate) || "page_limit".equals(candidate)) reason = candidate;
        }
        android.util.Log.w("CodexBridge", "transcript_failed stage=" + stage
                + " type=" + cause.getClass().getSimpleName() + " reason=" + reason);
    }

    /** 原聊天页跟随尾部游标并发布真实列表摘要；停止后的旧响应不再投递到页面。缺锚点只在本轮内存沿旧游标补页。 */
    public static void watchConversation(int account, long dialogId) {
        if (loggingOut || !loggedIn() || !ownsConversation(dialogId)) return;
        final long accountEpoch = accountGeneration;
        watchedDialog = dialogId;
        long generation = ++watchGeneration;
        watchStatus(account, dialogId, generation);
        String remote = remoteIds.get(dialogId);
        Utilities.globalQueue.postRunnable(new Runnable() {
            private int consecutiveTailPages;
            private TranscriptTailRecovery tailGap;
            /** 释放本次观察的桥接页；连接、历史或代际变化后不能继续用于别的会话。 */
            private void releaseTailGap() {
                if (tailGap != null) {
                    tailGap.release();
                    tailGap = null;
                }
            }
            /** 跟随增量，旧缓存缺尾游标先连续恢复；失败延后重试，不清空已有正文。 */
            @Override public void run() {
                if (generation != watchGeneration) {
                    releaseTailGap();
                    return;
                }
                long delay = 2000;
                try {
                    TranscriptWindow history = histories.get(dialogId);
                    DesktopConnection connection = dialogConnection(dialogId);
                    if (connection == null) {
                        releaseTailGap();
                        // 离线冷启没有实时连接；回到网络后自动重建，不要求退出聊天手动刷新。
                        AndroidUtilities.runOnUIThread(() -> {
                            if (generation == watchGeneration) refreshDialogs(account);
                        });
                        delay = 5000;
                    } else if (history != null && history.loaded) {
                        final boolean bootstrap = history.needsTailBootstrap();
                        if (tailGap != null && (!bootstrap || !tailGap.matches(connection, history, generation)))
                            releaseTailGap();
                        final String previousCursor = history.tailCursor;
                        final String previousOlderCursor = history.cursor;
                        final String bridgeCursor = tailGap == null ? null : tailGap.resumeCursor();
                        final Runnable next = this;
                        // 网络等待离开本地历史队列；同一观察轮只在请求结束后安排下一次。
                        transcriptQueue.postRunnable(() -> {
                            JsonObject page = null;
                            try {
                                if (generation == watchGeneration && connection == dialogConnection(dialogId))
                                    page = !bootstrap ? connection.readAfter(remote, previousCursor)
                                            : bridgeCursor == null ? connection.transcript(remote)
                                            : connection.transcript(remote, bridgeCursor);
                            } catch (Exception error) {
                                logTranscriptFailure(bootstrap ? "tail_recovery_request" : "tail_request", error, null);
                                /* 保留原记录，按失败间隔重试。 */
                            }
                            final JsonObject received = page;
                            Utilities.globalQueue.postRunnable(() -> {
                                if (generation != watchGeneration) {
                                    releaseTailGap();
                                    return;
                                }
                                long nextDelay = 5000;
                                try {
                                    // 返回期间可能换电脑连接或被预取推进游标，旧响应不能回写。
                                    boolean sameWatch = received != null && connection == dialogConnection(dialogId)
                                            && histories.get(dialogId) == history
                                            && java.util.Objects.equals(previousCursor, history.tailCursor)
                                            && (!bootstrap || java.util.Objects.equals(previousOlderCursor, history.cursor));
                                    // 网络失败保留已走到的旧游标；身份变化则丢掉暂态页，不能把缺页的B、C先发给界面。
                                    if (received != null && !sameWatch) releaseTailGap();
                                    ArrayList<TranscriptWindow.Entry> added = null;
                                    boolean publish = false;
                                    if (sameWatch && bootstrap && TranscriptTailRecovery.required(history)
                                            && (bridgeCursor != null || !TranscriptTailRecovery.containsAnchor(history, received))) {
                                        if (tailGap == null) tailGap = TranscriptTailRecovery.start(history, connection, generation);
                                        JsonObject ready = tailGap.accept(received, bridgeCursor);
                                        if (ready == null) nextDelay = tailGap.continueNow() ? 0 : 2000;
                                        else {
                                            added = history.recoverTail(ready);
                                            releaseTailGap();
                                            publish = true;
                                        }
                                    } else if (sameWatch) {
                                        releaseTailGap();
                                        added = bootstrap ? history.recoverTail(received) : history.append(received);
                                        publish = true;
                                    }
                                    if (publish) {
                                        final ArrayList<TranscriptWindow.Entry> delivered = added;
                                        if (bootstrap || !delivered.isEmpty() || !java.util.Objects.equals(previousCursor, history.tailCursor))
                                            saveHistory(dialogId, remote, history);
                                        publishHistoryPreview(account, accountEpoch, dialogId, remote, connection.machineId, connection, history);
                                        AndroidUtilities.runOnUIThread(() -> {
                                            if (generation != watchGeneration) return;
                                            ArrayList<org.telegram.messenger.MessageObject> incoming = new ArrayList<>();
                                            for (TranscriptWindow.Entry entry : delivered) {
                                                TLRPC.TL_message message = historyMessage(dialogId, entry);
                                                boolean matched = confirmPendingEcho(account, dialogId, message);
                                                if (org.telegram.messenger.BuildVars.DEBUG_VERSION && entry.message.outgoing) android.util.Log.i("CodexBridge", "echo_key=" + (entry.message.localId == null ? 0 : entry.message.localId.hashCode()) + " matched=" + matched);
                                                if (!matched) {
                                                    incoming.add(historyObject(account, message));
                                                }
                                            }
                                            if (!incoming.isEmpty()) NotificationCenter.getInstance(account).postNotificationName(
                                                    NotificationCenter.didReceiveNewMessages, dialogId, incoming, false, 0);
                                        });
                                        // 有积压才连续取页，最多四页后让出两秒；追平不增加空轮询。
                                        if (!bootstrap && TranscriptWindow.hasPendingTail(received, previousCursor) && ++consecutiveTailPages < 4) {
                                            nextDelay = 0;
                                        } else {
                                            consecutiveTailPages = 0;
                                            nextDelay = 2000;
                                        }
                                        // 开发验证只记录页规模与调度，不输出对话身份或正文。
                                        if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                                            android.util.Log.i("CodexBridge", "tail_page items=" + received.getAsJsonArray("items").size()
                                                    + " bytes=" + received.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                                    + " continue=" + TranscriptWindow.hasPendingTail(received, previousCursor)
                                                    + " delay=" + nextDelay);
                                    }
                                } catch (Exception error) {
                                    logTranscriptFailure(bootstrap ? "tail_recovery_merge" : "tail_merge", error, received);
                                    consecutiveTailPages = 0; /* 无效增量不清空已有正文。 */
                                    releaseTailGap();
                                }
                                if (generation == watchGeneration) Utilities.globalQueue.postRunnable(next, nextDelay);
                            });
                        });
                        return;
                    }
                } catch (Exception error) { delay = 5000; }
                if (generation == watchGeneration) Utilities.globalQueue.postRunnable(this, delay);
            }
        });
    }

    /** 原顶栏和列表读取同一事实，不从展示文字反推状态或待办种类。 */
    public static String statusText(long dialogId) {
        return status(dialogId).label;
    }

    /** 界面线程只读入口；没有事实、过期、断连与当前事实保持类型区分。 */
    public static SessionStatus.Snapshot status(long dialogId) {
        return statuses.get(dialogId, android.os.SystemClock.elapsedRealtime());
    }

    /** 现有会话元数据的只读投影；工作目录不是另行推测的项目身份。 */
    public static final class ConversationInfo {
        public final String title, machineId, machineName, workingDirectory;
        /** 返回完整真值，标题与电脑名的省略交原版控件处理。 */
        private ConversationInfo(String title, String machineId, String machineName, String workingDirectory) {
            this.title = title; this.machineId = machineId; this.machineName = machineName;
            this.workingDirectory = workingDirectory;
        }
    }

    /** 只读当前已发现对话，不因点开标题增加网络或文件读取。 */
    public static ConversationInfo conversationInfo(long dialogId) {
        String machine = dialogMachines.getOrDefault(dialogId, "");
        return new ConversationInfo(dialogTitles.getOrDefault(dialogId, "未命名对话"), machine,
                machineNames.getOrDefault(machine, ""), dialogDirectories.getOrDefault(dialogId, ""));
    }

    /** 供“我的”使用的最小账号概况，不包含认证材料或内部账号标识。 */
    public static final class AccountInfo {
        public final String loginName, server, connectionLabel;
        /** 仅封装已有登录显示值及当前传输状态。 */
        private AccountInfo(String loginName, String server, String connectionLabel) {
            this.loginName = loginName; this.server = server; this.connectionLabel = connectionLabel;
        }
    }

    /** 只读 volatile 账号快照，不等待后台连接所持的类锁；服务器连接不等于电脑在线。 */
    public static AccountInfo accountInfo() {
        PasswordLogin.Session currentSession = session;
        if (currentSession == null || loggingOut) return new AccountInfo("", SERVER, "未登录");
        DesktopConnection connection = desktop;
        return new AccountInfo(currentSession.loginName, currentSession.server, connection == null ? "尚未连接"
                : connection.isConnected() ? "服务器已连接" : "连接已中断");
    }

    /** 回读当前原身份映射；不让换账号、电脑、连接或linked会话后的旧目标恢复页面。 */
    private static boolean statusTargetCurrent(int account,long epoch,long dialogId,long generation,
            String machine,String remote,String linked,DesktopConnection connection) {
        return isAccountCurrent(epoch)&&account==org.telegram.messenger.UserConfig.selectedAccount
                &&generation==watchGeneration&&watchedDialog==dialogId
                &&machine!=null&&remote!=null&&!remote.isEmpty()
                &&java.util.Objects.equals(machine,dialogMachines.get(dialogId))
                &&java.util.Objects.equals(remote,remoteIds.get(dialogId))
                &&java.util.Objects.equals(linked,linkedSessions.get(dialogId))
                &&connection==dialogConnection(dialogId);
    }

    /** 原STATUS同时请求只读目标，账号、来源和关联会话变化后的迟到回包不进入原展示owner。 */
    private static void watchStatus(int account, long dialogId, long generation) {
        final long accountEpoch=accountGeneration;
        Runnable poll = new Runnable() {
            private int initialObservationRetries;

            /** 初次观察沿原频率追踪基线；每个异步回包都回核原账号、来源与linked身份。 */
            @Override public void run() {
                if (!isAccountCurrent(accountEpoch) || generation != watchGeneration || watchedDialog != dialogId) return;
                final String machine=dialogMachines.get(dialogId), remote=remoteIds.get(dialogId);
                final DesktopConnection connection=dialogConnection(dialogId);
                String observedLinked=linkedSessions.get(dialogId);
                if(!statusTargetCurrent(account,accountEpoch,dialogId,generation,machine,remote,observedLinked,connection))return;
                long nextDelay = 5000;
                String label = "连接暂不可用";
                JsonObject statusResponse = null;
                long observationStartedAt = android.os.SystemClock.elapsedRealtime();
                try {
                    if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                        android.util.Log.i("CodexBridge", "status_connection=" + (connection == null ? "absent" : "present"));
                    if (connection != null) {
                        if (observationOwner != null && (observationOwner != connection || observationDialog != dialogId
                                ||!java.util.Objects.equals(observationSession,observedLinked))) releaseObservation();
                        String linked = observedLinked;
                        if (linked == null) {
                            String opened=connection.openConversation(remote).get("sessionId").getAsString();
                            if(!statusTargetCurrent(account,accountEpoch,dialogId,generation,machine,remote,linked,connection))return;
                            linked=opened;
                            linkedSessions.put(dialogId, linked);
                            observedLinked=linked;
                        }
                        if (observationLease == null || android.os.SystemClock.elapsedRealtime() >= observationRenewAt) {
                            boolean renewing = observationLease != null;
                            JsonObject attached = connection.observe(remote, linked, observationLease);
                            if(!statusTargetCurrent(account,accountEpoch,dialogId,generation,machine,remote,linked,connection))return;
                            if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                                android.util.Log.i("CodexBridge", renewing ? "observation_lease=renew" : "observation_lease=attach");
                            observationOwner = connection;
                            observationDialog = dialogId;
                            observationSession = linked;
                            observationLease = attached.get("leaseId").getAsString();
                            observationRenewAt = android.os.SystemClock.elapsedRealtime() + 30000;
                        }
                        if(!statusTargetCurrent(account,accountEpoch,dialogId,generation,machine,remote,linked,connection))return;
                        observationStartedAt = android.os.SystemClock.elapsedRealtime();
                        JsonObject response = connection.status(remote, linked, true);
                        statusResponse = response;
                        label = SessionStatus.label(response);
                        // 仅已验证协议和在线状态的同步等待可以加快，未知或离线仍按原频率。
                        // attach 返回时基线可能尚未到达；最多额外追踪三次，不能无限高频轮询。
                        if ("同步中".equals(label) && initialObservationRetries < 3) {
                            initialObservationRetries++;
                            nextDelay = 1000;
                        }
                        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) {
                            JsonObject observation = response.getAsJsonObject("observation");
                            String reason = "absent";
                            if (observation != null) {
                                reason = "present";
                                if (observation.has("reason")) {
                                    String candidate = observation.get("reason").getAsString();
                                    if (java.util.Arrays.asList("not_observed", "connection_closed", "owner_changed", "incompatible_protocol", "invalid_snapshot", "revision_gap", "missing_turn_id", "source_unavailable", "unsupported_request").contains(candidate)) reason = candidate;
                                }
                            }
                            android.util.Log.i("CodexBridge", "status=" + label + " observation=" + reason);
                        }
                    }
                } catch (Exception error) { observationRenewAt = 0; /* 请求失败明确展示，并在恢复后重新续租。 */ }
                final String linked=observedLinked;
                final JsonObject observed = statusResponse;
                final long startedAt = observationStartedAt;
                final long receivedAt = android.os.SystemClock.elapsedRealtime();
                AndroidUtilities.runOnUIThread(() -> {
                    if(!statusTargetCurrent(account,accountEpoch,dialogId,generation,machine,remote,linked,connection)
                            ||(observed!=null&&connection!=null&&!connection.isConnected()))return;
                    statuses.observation(dialogId, machine, remote, observed, startedAt, receivedAt);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
                });
                if (isAccountCurrent(accountEpoch) && generation == watchGeneration && watchedDialog == dialogId)
                    statusQueue.postRunnable(this, nextDelay);
            }
        };
        // 注册和唤醒均由状态队列串行处理，切页后的旧轮次不能替换当前轮询。
        statusQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch) || generation != watchGeneration || watchedDialog != dialogId) return;
            if (statusPoll != null) statusQueue.cancelRunnable(statusPoll);
            statusPoll = poll;
            statusQueue.postRunnable(poll);
        });
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override public void run() {
                if (!isAccountCurrent(accountEpoch) || generation != watchGeneration || watchedDialog != dialogId) return;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
                AndroidUtilities.runOnUIThread(this, 5000);
            }
        }, 5000);
    }

    /** 离开聊天页即停止该页轮询，不中断其他页面或发送中的请求。 */
    public static void stopWatching(long dialogId) {
        if (watchedDialog == dialogId) {
            watchedDialog = 0;
            final long stoppedGeneration = ++watchGeneration;
            statusQueue.postRunnable(() -> {
                if (watchGeneration != stoppedGeneration || watchedDialog != 0) return;
                if (statusPoll != null) statusQueue.cancelRunnable(statusPoll);
                statusPoll = null;
            });
            statusQueue.postRunnable(() -> {
                if (watchGeneration == stoppedGeneration && watchedDialog == 0) releaseObservation();
            }, 20000);
        }
    }

    /** 历史和增量回显沿同一localId迁移原负编号气泡，只匹配同对话用户消息。 */
    private static boolean confirmPendingEcho(int account, long dialogId, TLRPC.TL_message message) {
        String localId = message.out && message.params != null ? message.params.get("codexLocalId") : null;
        MessageObject pending = localId == null ? null : pendingMessages.get(localId);
        if (pending == null || pending.getDialogId() != dialogId || pending.getId() >= 0) return false;
        pendingMessages.remove(localId);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageReceivedByServer,
                pending.getId(), message.id, message, dialogId, 0L, 0, false);
        return true;
    }

    /** 转换已有来源消息，历史和增量复用同一个原版文字模型。 */
    private static TLRPC.TL_message historyMessage(long dialogId, TranscriptWindow.Entry row) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = row.id;
        message.dialog_id = dialogId;
        message.date = (int) (row.message.createdAtMs / 1000);
        message.message = row.message.text;
        message.out = row.message.outgoing;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = dialogId;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = message.out ? 0 : dialogId;
        message.flags |= 256;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.params = new HashMap<>();
        if (row.message.localId != null) message.params.put("codexLocalId", row.message.localId);
        if (!row.message.attachments.isEmpty()) {
            DesktopAttachment value = row.message.attachments.get(0);
            AttachmentMessages.apply(message, value, cachedAttachment(attachmentDirectory(dialogId), row.message.localId, value));
        }
        return message;
    }

    /** 恢复已下载附件的原消息就绪位，不让原版再根据虚拟媒体编号查找 Telegram 缓存。 */
    private static MessageObject historyObject(int account, TLRPC.TL_message message) {
        MessageObject object = new MessageObject(account, message, true, false);
        if (isAttachmentMessage(object)) object.attachPathExists = object.mediaExists = attachmentFile(object) != null;
        return object;
    }

    /** 界面线程绑定列表后恢复原历史及待发摘要；后台读取不能覆盖期间新创建的气泡。 */
    private static void restoreDialogPreviews(int account, long epoch, String machine, Map<String, Long> publishedIds) {
        if (!isAccountCurrent(epoch)) return;
        final Map<Long, MessageObject> previous = new HashMap<>();
        for (Long dialogId : publishedIds.values()) {
            ArrayList<MessageObject> preview = MessagesController.getInstance(account).dialogMessage.get(dialogId);
            previous.put(dialogId, preview == null || preview.isEmpty() ? null : preview.get(0));
        }
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(epoch)) return;
            for (Map.Entry<String, Long> binding : publishedIds.entrySet()) {
                long dialogId = binding.getValue();
                String remote = binding.getKey();
                DesktopConnection connection = dialogConnection(dialogId);
                if (!previewCurrent(account, epoch, dialogId, remote, machine, connection)) continue;
                TranscriptWindow history = histories.computeIfAbsent(dialogId, ignored -> readHistory(dialogId, remote));
                try {
                    ArrayList<OutboxStore.Item> queued = outboxStore(dialogId).list(remote);
                    if (!queued.isEmpty()) {
                        java.util.HashSet<String> echoed = new java.util.HashSet<>();
                        for (TranscriptWindow.Entry entry : history.before(0, Integer.MAX_VALUE)) {
                            if (entry.message.outgoing && entry.message.localId != null) echoed.add(entry.message.localId);
                        }
                        OutboxStore.Item newest = null;
                        for (OutboxStore.Item item : queued) {
                            if (!batchEchoed(item, echoed) && (newest == null || item.date > newest.date)) newest = item;
                        }
                        ArrayList<TranscriptWindow.Entry> latest = history.before(0, 1);
                        if (newest != null && (latest.isEmpty() || newest.date >= latest.get(0).message.createdAtMs / 1000)) {
                            final OutboxStore.Item selected = newest;
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!previewCurrent(account, epoch, dialogId, remote, machine, connection)) return;
                                ArrayList<MessageObject> current = MessagesController.getInstance(account).dialogMessage.get(dialogId);
                                MessageObject visible = current == null || current.isEmpty() ? null : current.get(0);
                                if (visible != previous.get(dialogId)) return;
                                if (visible != null && visible.messageOwner.params != null) {
                                    String localId = visible.messageOwner.params.get("codexLocalId");
                                    String batch = visible.messageOwner.params.getOrDefault("codexBatchLocalId", localId);
                                    if (pendingMessages.get(localId) == visible && !selected.localId.equals(batch)) return;
                                }
                                // 原恢复器保留ACK/未知/失败、每件附件及批内顺序，不写新缓存或自动提交。
                                ArrayList<MessageObject> pending = restoredPending(account, dialogId, selected, echoed);
                                publishPendingPreview(account, epoch, dialogId, pending, true);
                            });
                        }
                    }
                } catch (java.io.IOException error) {
                    logTranscriptFailure("preview_pending_restore", error, null);
                }
                publishHistoryPreview(account, epoch, dialogId, remote, machine, connection, history);
            }
        });
    }

    /** 在原历史队列取真实最新记录及回显身份，界面线程只发布不可变消息快照。 */
    private static void publishHistoryPreview(int account, long epoch, long dialogId, String remote, String machine,
            DesktopConnection connection, TranscriptWindow history) {
        if (!previewCurrent(account, epoch, dialogId, remote, machine, connection)) return;
        ArrayList<TranscriptWindow.Entry> latest = history.before(0, 1);
        if (latest.isEmpty()) return;
        TLRPC.TL_message message = historyMessage(dialogId, latest.get(0));
        java.util.HashSet<String> echoed = new java.util.HashSet<>();
        for (TranscriptWindow.Entry entry : history.before(0, Integer.MAX_VALUE)) {
            if (entry.message.outgoing && entry.message.localId != null) echoed.add(entry.message.localId);
        }
        AndroidUtilities.runOnUIThread(() -> publishDialogPreview(account, epoch, dialogId, remote, machine,
                connection, message, null, echoed));
    }

    /** 新批按原创建顺序显示最后一件；旧批重试只刷新仍属于该批的预览，不按负编号判断新旧。 */
    private static void publishPendingPreview(int account, long epoch, long dialogId, ArrayList<MessageObject> messages, boolean fresh) {
        if (messages.isEmpty()) return;
        String remote = remoteIds.get(dialogId), machine = dialogMachines.get(dialogId);
        DesktopConnection connection = dialogConnection(dialogId);
        if (!previewCurrent(account, epoch, dialogId, remote, machine, connection)) return;
        ArrayList<MessageObject> current = MessagesController.getInstance(account).dialogMessage.get(dialogId);
        if (!fresh && current != null && !current.isEmpty() && !messages.contains(current.get(0))) return;
        MessageObject pending = messages.get(messages.size() - 1);
        publishDialogPreview(account, epoch, dialogId, remote, machine, connection,
                pending.messageOwner, pending, java.util.Collections.emptySet());
    }

    /** 仅更新原对话消息映射及正文重绘事件，不更改日期、未读、全局消息编号表或历史通知。 */
    private static void publishDialogPreview(int account, long epoch, long dialogId, String remote, String machine,
            DesktopConnection connection, TLRPC.Message message, MessageObject pending, java.util.Set<String> echoed) {
        if (message.dialog_id != dialogId || !previewCurrent(account, epoch, dialogId, remote, machine, connection)) return;
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<MessageObject> old = controller.dialogMessage.get(dialogId);
        MessageObject current = old == null || old.isEmpty() ? null : old.get(0);
        String localId = current == null || current.messageOwner.params == null ? null : current.messageOwner.params.get("codexLocalId");
        // 未回显的本地气泡优先；原历史已包含同身份时才释放，真实新回复不会被负编号挡住。
        if (pending == null && localId != null && pendingMessages.get(localId) == current && !echoed.contains(localId)) return;
        if (current != null && sameDialogPreview(current.messageOwner, message)) return;
        MessageObject next = pending != null ? pending : historyObject(account, (TLRPC.TL_message) message);
        ArrayList<MessageObject> preview = new ArrayList<>();
        preview.add(next);
        controller.dialogMessage.put(dialogId, preview);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
    }

    /** 比较实际影响原摘要和附件就绪的消息字段，同一真实摘要不在原列表周期重复重建。 */
    private static boolean sameDialogPreview(TLRPC.Message left, TLRPC.Message right) {
        return left.id == right.id && left.date == right.date && left.out == right.out && left.send_state == right.send_state
                && java.util.Objects.equals(left.message, right.message) && java.util.Objects.equals(left.params, right.params)
                && java.util.Objects.equals(left.attachPath, right.attachPath);
    }

    /** 每次界面发布复核原账号、对话归属及连接；同电脑旧连接不能回写新来源。 */
    private static boolean previewCurrent(int account, long epoch, long dialogId, String remote, String machine, DesktopConnection connection) {
        return account == org.telegram.messenger.UserConfig.selectedAccount && isAccountCurrent(epoch)
                && remote != null && remote.equals(remoteIds.get(dialogId))
                && machine != null && machine.equals(dialogMachines.get(dialogId))
                && dialogConnection(dialogId) == connection
                && (connection == null || machine.equals(connection.machineId));
    }

    /** 待发消息始终写入对话所属电脑目录。 */
    private static OutboxStore outboxStore(long dialogId) {
        return new OutboxStore(new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "codex-outbox"),
                session.server, session.accountId, dialogMachine(dialogId));
    }

    /** 恢复同一批次的独立原气泡；已回显的成员不重复展示，也不自动重发。 */
    private static ArrayList<MessageObject> restoredPending(int account, long dialogId, OutboxStore.Item item,
            java.util.Set<String> echoed) {
        ArrayList<MessageObject> restored = new ArrayList<>();
        int count = Math.max(1, item.attachmentCount());
        org.telegram.messenger.UserConfig config = org.telegram.messenger.UserConfig.getInstance(account);
        config.lastSendMessageId = Math.min(config.lastSendMessageId, item.messageId - count);
        config.saveConfig(false);
        for (int i = 0; i < count; i++) {
            String localId = TranscriptText.attachmentIdentity(item.localId, i);
            if (echoed.contains(localId)) continue;
            MessageObject existing = pendingMessages.get(localId);
            if (existing != null) { restored.add(existing); continue; }
            TLRPC.TL_message message = pendingMessage(dialogId, item.messageId - i, item.date,
                    i == 0 ? item.text : "", item.localId, i);
            if (i < item.attachments.size()) AttachmentMessages.applyPending(message, item.attachments.get(i));
            else if (i < item.selections.size()) AttachmentMessages.applySelected(message, item.selections.get(i));
            if (item.submissionUncertain) message.params.put("codexSendUncertain", "true");
            if (item.submissionAccepted) message.params.put("codexSendAccepted", "true");
            if (!item.submissionUncertain && OutboxStore.FAILURE_FILE_TOO_LARGE.equals(item.failureCode))
                message.params.put("codexSendFailure", OutboxStore.FAILURE_FILE_TOO_LARGE);
            // 真实ACK保留成功，未知只等待回显；两者都不能在恢复进程时重新提交。
            message.send_state = item.submissionAccepted ? MessageObject.MESSAGE_SEND_STATE_SENT
                    : item.submissionUncertain ? MessageObject.MESSAGE_SEND_STATE_SENDING : MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
            MessageObject result = new MessageObject(account, message, true, false);
            result.attachPathExists = result.mediaExists = attachmentFile(result) != null;
            pendingMessages.put(localId, result);
            restored.add(result);
        }
        return restored;
    }

    /** 多附件只有全部原身份已写入历史后才可删除唯一待发记录。 */
    private static boolean batchEchoed(OutboxStore.Item item, java.util.Set<String> echoed) {
        if (!item.isPrepared()) return false;
        for (int i = 0; i < Math.max(1, item.attachmentCount()); i++) {
            if (!echoed.contains(TranscriptText.attachmentIdentity(item.localId, i))) return false;
        }
        return true;
    }

    /** 历史目录按对话归属定位，切换列表来源不改变原缓存位置。 */
    private static TranscriptStore transcriptStore(long dialogId) {
        return new TranscriptStore(new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "codex-history"),
                session.server, session.accountId, dialogMachine(dialogId));
    }

    /** 从对话所属电脑恢复正文，读取失败保留重新加载路径。 */
    private static TranscriptWindow readHistory(long dialogId, String remote) {
        try { return transcriptStore(dialogId).read(remote); }
        catch (java.io.IOException error) { return new TranscriptWindow(); }
    }

    /** 同一电脑目录先保存回显，再移除待发记录；失败不把预取版本记成已落地。 */
    private static boolean saveHistory(long dialogId, String remote, TranscriptWindow history) {
        try {
            transcriptStore(dialogId).write(remote, history);
            // 先确认历史已落盘，之后才能删除同一发送编号的待发记录。
            OutboxStore outbox = outboxStore(dialogId);
            ArrayList<OutboxStore.Item> queued = outbox.list(remote);
            if (!queued.isEmpty()) {
                java.util.HashSet<String> echoed = new java.util.HashSet<>();
                for (TranscriptWindow.Entry row : history.before(0, Integer.MAX_VALUE)) {
                    if (row.message.localId != null && row.message.outgoing) echoed.add(row.message.localId);
                }
                for (OutboxStore.Item item : queued) if (batchEchoed(item, echoed)) outbox.remove(item.localId);
            }
            return true;
        }
        catch (java.io.IOException error) {
            if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.w("CodexBridge", "history_cache_write_failed");
            return false;
        }
    }

    /** 原聊天加载同时发布真实最新摘要；分页交付仍保持原布局、锚点和滚动逻辑。 */
    public static void loadMessages(int account, long dialogId, int count, int maxId,
                                    int classGuid, int loadType, int loadIndex, int mode) {
        final long accountEpoch = accountGeneration;
        if (!isAccountCurrent(accountEpoch)) return;
        String remote = remoteIds.get(dialogId);
        Utilities.globalQueue.postRunnable(new Runnable() {
            private int pages;
            private Exception fetchError;
            /** 本地先交付；缺页才异步取来源，回到同一队列合并并重新选择锚点。 */
            @Override public void run() {
                if (!isAccountCurrent(accountEpoch)) return;
                try {
                    if (fetchError != null) throw fetchError;
                    TranscriptWindow history = histories.computeIfAbsent(dialogId, ignored -> readHistory(dialogId, remote));
                    DesktopConnection previewConnection = dialogConnection(dialogId);
                    publishHistoryPreview(account, accountEpoch, dialogId, remote, dialogMachines.get(dialogId), previewConnection, history);
                    boolean newer = loadType == 1;
                    ArrayList<TranscriptWindow.Entry> selected = newer ? history.after(maxId, count + 1) : history.before(maxId, count + 1);
                    boolean cachedPage = !selected.isEmpty();
                    DesktopConnection connection = dialogConnection(dialogId);
                    // 工具密集历史仍最多取四页；已有本地页无需等待网络补满。
                    if (!newer && connection != null && (!cachedPage || pages > 0)
                            && pages < 4 && selected.size() < count && history.hasMore) {
                        final String cursor = history.cursor;
                        final boolean loaded = history.loaded;
                        final Runnable resume = this;
                        pages++;
                        historyQueue.postRunnable(() -> {
                            if (!isAccountCurrent(accountEpoch)) return;
                            JsonObject page = null;
                            Exception failure = null;
                            try { page = connection.transcript(remote, cursor); }
                            catch (Exception error) { failure = error; }
                            final JsonObject received = page;
                            final Exception requestError = failure;
                            Utilities.globalQueue.postRunnable(() -> {
                                if (!isAccountCurrent(accountEpoch)) return;
                                try {
                                    if (requestError != null) throw requestError;
                                    // 其他分页或预取可能先推进旧页游标；不重复合并迟到页。
                                    if (connection != dialogConnection(dialogId) || histories.get(dialogId) != history)
                                        throw new java.io.IOException("历史连接已更换");
                                    if (loaded == history.loaded && java.util.Objects.equals(cursor, history.cursor)) {
                                        history.prepend(received);
                                        saveHistory(dialogId, remote, history);
                                    }
                                } catch (Exception error) { fetchError = error; }
                                Utilities.globalQueue.postRunnable(resume);
                            });
                        });
                        return;
                    }
                    boolean end = (newer ? history.loaded && history.tailCursor != null : history.complete) && selected.size() <= count;
                    // 向新翻页先交付紧邻锚点的一批；不能跳过最近的一项或拿旧页冒充新页。
                    if (newer && selected.size() > count) selected.remove(0);
                    ArrayList<TranscriptWindow.Entry> latest = history.before(0, 1);
                    int loadedAnchor = maxId == 0 && loadType == 2 && !latest.isEmpty() ? latest.get(0).id : maxId;
                    ArrayList<TranscriptWindow.Entry> rows = selected;
                    ArrayList<OutboxStore.Item> restored = maxId == 0 && loadType == 2
                            ? outboxStore(dialogId).list(remote) : new ArrayList<>();
                    java.util.HashSet<String> echoed = new java.util.HashSet<>();
                    for (TranscriptWindow.Entry row : history.before(0, Integer.MAX_VALUE)) {
                        if (row.message.localId != null && row.message.outgoing) echoed.add(row.message.localId);
                    }
                    restored.removeIf(item -> batchEchoed(item, echoed));
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isAccountCurrent(accountEpoch) || !remote.equals(remoteIds.get(dialogId))) return;
                        ArrayList<org.telegram.messenger.MessageObject> objects = new ArrayList<>();
                        for (int i = 0; i < Math.min(count, rows.size()); i++) {
                            TranscriptWindow.Entry row = rows.get(i);
                            TLRPC.TL_message message = historyMessage(dialogId, row);
                            confirmPendingEcho(account, dialogId, message);
                            objects.add(historyObject(account, message));
                        }
                        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge", "history type=" + loadType + " max=" + maxId + " count=" + objects.size());
                        // 原页以数量不足判断结束；只有来源明确完整时才允许进入结束分支。
                        int reportedCount = end ? Math.max(count, objects.size() + 1) : objects.size();
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDidLoad,
                                dialogId, reportedCount, objects, false, 0, 0, 0, 0,
                                loadType, end, classGuid, loadIndex, loadedAnchor, 0, mode);
                        if (!restored.isEmpty()) {
                            ArrayList<org.telegram.messenger.MessageObject> pending = new ArrayList<>();
                            for (OutboxStore.Item item : restored) pending.addAll(restoredPending(account, dialogId, item, echoed));
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceiveNewMessages,
                                    dialogId, pending, false, 0);
                        }
                    });
                } catch (Exception error) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isAccountCurrent(accountEpoch)) return;
                        android.widget.Toast.makeText(ApplicationLoader.applicationContext,
                                "暂时无法读取聊天记录，请稍后重新打开", android.widget.Toast.LENGTH_SHORT).show();
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDidLoad,
                                dialogId, 0, new ArrayList<org.telegram.messenger.MessageObject>(), false,
                                0, 0, 0, 0, loadType, false, classGuid, loadIndex, maxId, 0, mode);
                    });
                }
            }
        });
    }

    /** 在列表刷新时预取有变化的最近对话；网络工作不占用本地历史读取队列。 */
    private static void prefetchDialogs(com.google.gson.JsonArray candidates, DesktopConnection connection) {
        prefetchDialogs(candidates, connection, 1);
    }

    /** 预取保留原账号快照并发布真实最新摘要；未变版本的缓存也进入原消息映射。 */
    private static void prefetchDialogs(com.google.gson.JsonArray candidates, DesktopConnection connection, int catchupPages) {
        final long accountEpoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        final int account = org.telegram.messenger.UserConfig.selectedAccount;
        if (!isAccountCurrent(accountEpoch) || connection == null || !connection.isConnected()
                || desktopConnections.get(connection.machineId) != connection) return;
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch) || desktopConnections.get(connection.machineId) != connection
                    || !connection.isConnected()) return;
            int remaining = recentDialogLimit();
            for (com.google.gson.JsonElement value : candidates) {
                if (remaining <= 0) break;
                final JsonObject candidate;
                final String remote;
                final long revision;
                try {
                    candidate = value.getAsJsonObject();
                    remote = candidate.get("remoteSessionId").getAsString();
                    revision = candidate.get("updatedAtMs").getAsLong();
                } catch (RuntimeException error) {
                    // 坏行只记录类型并跳过，不中断队列或占用后续合法候选的预取额度。
                    logTranscriptFailure("prefetch_candidate", error, null);
                    continue;
                }
                remaining--;
                long dialogId = bindDialog(connection.machineId, remote, owner, accountEpoch);
                if (dialogId == 0 || dialogConnection(dialogId) != connection
                        || dialogId == watchedDialog || prefetching.contains(dialogId)) continue;
                TranscriptWindow history = histories.computeIfAbsent(dialogId, ignored -> readHistory(dialogId, remote));
                publishHistoryPreview(account, accountEpoch, dialogId, remote, connection.machineId, connection, history);
                final boolean bootstrap = history.needsTailBootstrap();
                final boolean older = !bootstrap && history.needsVisibleHistory();
                if (!bootstrap && !older && java.util.Objects.equals(prefetchedRevisions.get(dialogId), revision)) continue;
                final String tailCursor = history.tailCursor;
                final String olderCursor = history.cursor;
                final boolean wasLoaded = history.loaded;
                final String cursor = older ? olderCursor : tailCursor;
                prefetching.add(dialogId);
                prefetchQueue.postRunnable(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    JsonObject page = null;
                    try {
                        if (dialogConnection(dialogId) == connection && connection.isConnected()
                                && !ApplicationLoader.mainInterfacePaused)
                            page = bootstrap ? connection.transcript(remote)
                                    : older ? connection.transcript(remote, cursor) : connection.readAfter(remote, cursor);
                    } catch (Exception error) {
                        logTranscriptFailure(bootstrap ? "prefetch_initial_request" : older ? "prefetch_older_request" : "prefetch_tail_request", error, null);
                        /* 下一次列表刷新重试，不把失败记为已同步。 */
                    }
                    final JsonObject received = page;
                    Utilities.globalQueue.postRunnable(() -> {
                        if (!isAccountCurrent(accountEpoch)) return;
                        prefetching.remove(dialogId);
                        if (received == null || connection != dialogConnection(dialogId) || dialogId == watchedDialog
                                || histories.get(dialogId) != history || wasLoaded != history.loaded
                                || !java.util.Objects.equals(tailCursor, history.tailCursor)
                                || (bootstrap || older) && !java.util.Objects.equals(olderCursor, history.cursor)) return;
                        try {
                            if (bootstrap) history.recoverTail(received);
                            else if (older) history.prepend(received);
                            else history.append(received);
                            boolean saved = saveHistory(dialogId, remote, history);
                            publishHistoryPreview(account, accountEpoch, dialogId, remote, connection.machineId, connection, history);
                            // 服务端仍有后续增量时，下次继续同一游标，不能提前记为追平。
                            boolean more = received.has("hasMore") && received.get("hasMore").getAsBoolean();
                            boolean truncated = received.has("truncated") && received.get("truncated").getAsBoolean();
                            boolean missingBody = history.needsVisibleHistory();
                            // 向旧找到正文只补首屏，不能代表新尾部已追平；沿同一预算再核对原尾游标。
                            boolean pendingTail = older ? !missingBody : !bootstrap && TranscriptWindow.hasPendingTail(received, cursor);
                            if (saved && !older && !history.needsTailBootstrap() && !missingBody && (bootstrap || (!more && !truncated)))
                                prefetchedRevisions.put(dialogId, revision);
                            else prefetchedRevisions.remove(dialogId);
                            if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                                android.util.Log.i("CodexBridge", "prefetch_page batch=" + catchupPages
                                        + " initial=" + bootstrap + " older=" + older + " items=" + received.getAsJsonArray("items").size()
                                        + " pending=" + (missingBody || pendingTail));
                            // 首屏空投影也沿真实旧页游标找正文；每轮总共最多四页，剩余仍交原列表周期继续。
                            if (catchupPages < 4 && ((missingBody && !history.needsTailBootstrap()) || pendingTail)) {
                                com.google.gson.JsonArray pending = new com.google.gson.JsonArray();
                                pending.add(candidate);
                                prefetchDialogs(pending, connection, catchupPages + 1);
                            }
                        } catch (Exception error) {
                            logTranscriptFailure(bootstrap ? "prefetch_initial_merge" : older ? "prefetch_older_merge" : "prefetch_tail_merge", error, received);
                            /* 保留已有正文，下一轮仍可重试。 */
                        }
                    });
                });
            }
        });
    }

    /** 首页完成后复用现预取队列读取已连接电脑的一页候选；不另建连接或聚合首页。 */
    private static void prefetchConnectedComputers(long accountEpoch) {
        if (!isAccountCurrent(accountEpoch)) return;
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch) || connectedComputersPrefetching) return;
            ArrayList<DesktopConnection> connections = new ArrayList<>();
            for (DesktopConnection connection : desktopConnections.values()) {
                if (connection != desktop && connection.isConnected()) connections.add(connection);
            }
            if (connections.isEmpty()) return;
            connectedComputersPrefetching = true;
            prefetchQueue.postRunnable(() -> {
                try {
                    for (DesktopConnection connection : connections) {
                        if (!isAccountCurrent(accountEpoch) || ApplicationLoader.mainInterfacePaused) break;
                        if (connection == desktop || desktopConnections.get(connection.machineId) != connection
                                || !connection.isConnected()) continue;
                        try {
                            JsonObject page = connection.candidates(recentDialogLimit());
                            if (!isAccountCurrent(accountEpoch) || desktopConnections.get(connection.machineId) != connection
                                    || connection == desktop || !connection.isConnected()) continue;
                            if (!page.has("candidates") || !page.get("candidates").isJsonArray())
                                throw new IOException("电脑会话列表格式无效");
                            prefetchDialogs(page.getAsJsonArray("candidates"), connection);
                        } catch (Exception error) {
                            logTranscriptFailure("connected_candidates", error, null);
                        }
                    }
                } finally {
                    // 旧账号结束不得清除新账号的单飞门禁。
                    Utilities.globalQueue.postRunnable(() -> {
                        if (isAccountCurrent(accountEpoch)) connectedComputersPrefetching = false;
                    });
                }
            });
        });
    }

    /** 原网络广播在主线程通知；仅前台已登录且尚无连接时立即尝试，原 loading 门禁合并并发。 */
    public static void onNetworkAvailable() {
        if (!enabled() || ApplicationLoader.mainInterfacePaused || session == null || loggingOut || desktop != null) return;
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
            android.util.Log.i("CodexBridge", "connection_open=network_available");
        refreshDialogs(org.telegram.messenger.UserConfig.selectedAccount);
    }

    /** 前台定期发现新对话，后台挂起不发请求；只有变化的对话才补正文。 */
    private static void scheduleListRefresh(int account) {
        final long accountEpoch = accountGeneration;
        if (!isAccountCurrent(accountEpoch)) return;
        if (listRefreshScheduled) return;
        listRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(new Runnable() {
            /** 原列表周期也发布失效重绘；即使请求尚未返回，旧事实仍按15秒到期。 */
            @Override public void run() {
                if (!isAccountCurrent(accountEpoch)) return;
                if (!ApplicationLoader.mainInterfacePaused) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
                    refreshDialogs(account);
                }
                AndroidUtilities.runOnUIThread(this, 15000);
            }
        }, 15000);
    }

    private static DialogStore dialogStore() {
        return new DialogStore(new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "codex-dialogs"),
                session.server, session.accountId);
    }

    /** 列表和电脑子页共用候选消费点；缓存只补缺失元数据，不能覆盖别处已取得的新事实。 */
    private static void publishDialogFacts(long dialogId, String machine, JsonObject candidate,
            long startedAt, long receivedAt, boolean cached) {
        statuses.candidate(dialogId, machine, candidate, startedAt, receivedAt, cached);
        com.google.gson.JsonElement remoteValue = candidate.get("remoteSessionId");
        String remote = remoteValue != null && remoteValue.isJsonPrimitive() && remoteValue.getAsJsonPrimitive().isString()
                ? remoteValue.getAsString() : "";
        PasswordLogin.Session owner = session;
        if (owner != null && !remote.isEmpty() && statuses.claimGoalRestore(dialogId, machine))
            scheduleGoalRestore(dialogId, machine, remote, owner, accountGeneration);
        String title = candidate.has("title") && candidate.get("title").isJsonPrimitive()
                ? candidate.get("title").getAsString() : "未命名对话";
        if (cached) {
            dialogTitles.putIfAbsent(dialogId, title);
            dialogDirectories.putIfAbsent(dialogId, candidateDirectory(candidate));
        } else {
            dialogTitles.put(dialogId, title);
            dialogDirectories.put(dialogId, candidateDirectory(candidate));
        }
    }

    /** 工作目录只来自原始候选字段，供浏览筛选和标题信息共同读取。 */
    private static String candidateDirectory(JsonObject candidate) {
        JsonObject details = candidate.has("details") && candidate.get("details").isJsonObject() ? candidate.getAsJsonObject("details") : null;
        if (details != null) for (String field : new String[]{"cwd", "path"}) {
            if (details.has(field) && details.get(field).isJsonPrimitive() && details.get(field).getAsJsonPrimitive().isString()
                    && !details.get(field).getAsString().trim().isEmpty()) return details.get(field).getAsString();
        }
        return "";
    }

    /** 界面只关心展示状态变化，不因 checkedAt 刷新重建整个列表。 */
    private static String statusPresentation(long dialogId) {
        SessionStatus.Snapshot value = status(dialogId);
        return value.validity + ":" + value.state + ":" + value.pendingKind + ":" + value.label;
    }

    /** 相同标题复用原用户模型，防止每个轮询都替换未变化的展示对象。 */
    private static boolean publishDialogUser(int account, long id, String title) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User previous = controller.getUser(id);
        if (previous != null && java.util.Objects.equals(previous.first_name, title)) return false;
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id; user.first_name = title; user.last_name = ""; user.contact = true;
        controller.putUser(user, false);
        return true;
    }

    /** 最近列表上限只影响列表与预取，不删除历史和待发记录。 */
    public static int recentDialogLimit() {
        return ApplicationLoader.applicationContext.getSharedPreferences("codex-preferences", 0)
                .getInt("recentDialogLimit", 50);
    }

    /** 保存本机最近列表设置并触发原列表更新，不删除缓存正文。 */
    public static void setRecentDialogLimit(int account, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("会话数量超出范围");
        ApplicationLoader.applicationContext.getSharedPreferences("codex-preferences", 0)
                .edit().putInt("recentDialogLimit", limit).apply();
        // 立即收缩当前列表；网络结果稍后沿同一入口更新。
        while (dialogs.size() > limit) dialogs.remove(dialogs.size() - 1);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        refreshDialogs(account);
    }

    /** 后台保存编号，界面复用稳定模型并恢复真实摘要；列表差异仍只认顺序和原展示字段。 */
    private static void publishDialogs(int account, String machine, com.google.gson.JsonArray candidates,
            long startedAt, long receivedAt, boolean cached) {
        final long accountEpoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        if (!isAccountCurrent(accountEpoch)) return;
        final Map<String, Long> publishedIds = new HashMap<>();
        for (com.google.gson.JsonElement entry : candidates) {
            String remote = entry.getAsJsonObject().get("remoteSessionId").getAsString();
            publishedIds.put(remote, bindDialog(machine, remote, owner, accountEpoch));
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!isAccountCurrent(accountEpoch)) return;
            ArrayList<TLRPC.Dialog> updated = new ArrayList<>();
            Map<Long, Long> updatedAt = new HashMap<>();
            MessagesController controller = MessagesController.getInstance(account);
            boolean presentationChanged = !controller.dialogsLoaded, statusChanged = false;
            for (com.google.gson.JsonElement entry : candidates) {
                JsonObject candidate = entry.getAsJsonObject();
                long local = publishedIds.get(candidate.get("remoteSessionId").getAsString());
                if (local == 0) continue;
                String before = statusPresentation(local);
                publishDialogFacts(local, machine, candidate, startedAt, receivedAt, cached);
                statusChanged |= !before.equals(statusPresentation(local));
                presentationChanged |= publishDialogUser(account, local, dialogTitles.get(local));
                TLRPC.Dialog dialog = controller.dialogs_dict.get(local);
                if (dialog == null) {
                    dialog = new TLRPC.TL_dialog(); dialog.id = local;
                    dialog.peer = new TLRPC.TL_peerUser(); dialog.peer.user_id = local;
                    controller.dialogs_dict.put(local, dialog);
                    presentationChanged = true;
                }
                long timestamp = candidate.get("updatedAtMs").getAsLong();
                updatedAt.put(local, timestamp);
                int date = (int) (timestamp / 1000);
                presentationChanged |= dialog.last_message_date != date;
                dialog.last_message_date = date;
                updated.add(dialog);
            }
            // 保留原始毫秒排序；时间相同时用稳定远端身份，输入顺序不会让行来回跳动。
            updated.sort((a, b) -> {
                int date = Long.compare(updatedAt.get(b.id), updatedAt.get(a.id));
                return date != 0 ? date : remoteIds.get(a.id).compareTo(remoteIds.get(b.id));
            });
            while (updated.size() > recentDialogLimit()) updated.remove(updated.size() - 1);
            boolean orderChanged = dialogs.size() != updated.size();
            if (!orderChanged) for (int i = 0; i < dialogs.size(); i++)
                if (dialogs.get(i).id != updated.get(i).id) { orderChanged = true; break; }
            if (orderChanged) { dialogs.clear(); dialogs.addAll(updated); }
            controller.dialogsLoaded = true;
            controller.putDialogsEndReachedAfterRegistration();
            if (orderChanged || presentationChanged)
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            if (statusChanged || presentationChanged)
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                        MessagesController.UPDATE_MASK_STATUS | (presentationChanged ? MessagesController.UPDATE_MASK_NAME : 0));
            restoreDialogPreviews(account, accountEpoch, machine, publishedIds);
        });
    }

    /** 从真实电脑获取原列表；请求失败按原请求顺序失效状态，不冒充连接断开事件。 */
    public static void refreshDialogs(int account) {
        if (loading || !loggedIn()) return;
        final long accountEpoch = accountGeneration;
        if (!isAccountCurrent(accountEpoch)) return;
        scheduleListRefresh(account);
        loading = true;
        loadError = null;
        dialogQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch)) return;
            if (!cachedDialogsRead) {
                cachedDialogsRead = true;
                try {
                    JsonObject cached = dialogStore().read();
                    if (cached != null) {
                        preferredMachine = cached.get("machineId").getAsString();
                        if (cached.has("machineName") && cached.get("machineName").isJsonPrimitive()
                                && cached.get("machineName").getAsJsonPrimitive().isString())
                            machineNames.put(preferredMachine, cached.get("machineName").getAsString());
                        publishDialogs(account, preferredMachine, cached.getAsJsonArray("candidates"), -1, -1, true);
                    }
                } catch (java.io.IOException error) { /* 损坏列表不影响重新联网取得。 */ }
            }
            String stage = "connect";
            String requestedMachine = preferredMachine;
            long observationStartedAt = android.os.SystemClock.elapsedRealtime();
            try {
                if (desktop == null) desktop = openDesktop();
                preferredMachine = desktop.machineId;
                requestedMachine = desktop.machineId;
                stage = "candidates";
                observationStartedAt = android.os.SystemClock.elapsedRealtime();
                JsonObject response = desktop.candidates(recentDialogLimit());
                long observedAt = android.os.SystemClock.elapsedRealtime();
                stage = "decode";
                if (!response.has("candidates") || !response.get("candidates").isJsonArray())
                    throw new java.io.IOException("电脑会话列表格式无效");
                com.google.gson.JsonArray candidates = response.getAsJsonArray("candidates");
                // 在后台验证必需字段，异常由同一失败分支收口，不让坏数据进入界面线程。
                for (com.google.gson.JsonElement entry : candidates) {
                    JsonObject candidate = entry.getAsJsonObject();
                    candidate.get("remoteSessionId").getAsString();
                    candidate.get("updatedAtMs").getAsLong();
                    if (candidate.has("title")) candidate.get("title").getAsString();
                }
                response.addProperty("machineId", desktop.machineId);
                response.addProperty("machineName", desktop.machineName);
                try { dialogStore().write(response); }
                catch (java.io.IOException error) { /* 写缓存失败不丢弃已取得的列表。 */ }
                publishDialogs(account, desktop.machineId, candidates, observationStartedAt, observedAt, false);
                prefetchDialogs(candidates, desktop);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    loading = false;
                    prefetchConnectedComputers(accountEpoch);
                });
            } catch (Exception error) {
                // 仅记录阶段和异常类型，不输出响应正文、账号、密钥或电脑路径。
                if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                    android.util.Log.w("CodexBridge", "dialogs_failed stage=" + stage + " type=" + error.getClass().getSimpleName());
                final String failedMachine = requestedMachine;
                final long failedRequestStartedAt = observationStartedAt;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    if (failedMachine != null) statuses.listFailed(failedMachine, failedRequestStartedAt);
                    loading = false;
                    loadError = "电脑会话暂时无法读取";
                    if (dialogs.isEmpty()) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
                    // 首页来源离线也不阻断其它已连接电脑；仍在本次首页请求结束后才安排。
                    prefetchConnectedComputers(accountEpoch);
                });
            }
        });
    }
}
