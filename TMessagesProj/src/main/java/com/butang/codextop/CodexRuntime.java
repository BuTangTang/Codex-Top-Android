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
        loggingOut = true;
        accountGeneration++;
        watchGeneration++;
        watchedDialog = 0;
        Utilities.globalQueue.postRunnable(() -> {
            try { new SessionStore(ApplicationLoader.applicationContext).clear(); }
            catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> {
                    loggingOut = false;
                    listRefreshScheduled = false;
                    loading = false;
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
                        preferredMachine = null;
                        cachedDialogsRead = false;
                        // 与冷盘恢复共用小锁；旧账号绑定不能越过清理写入下一账号。
                        synchronized (dialogIdentityLock) {
                            dialogIdentities = null;
                            browseSnapshots.clear(); remoteIds.clear(); dialogMachines.clear();
                        }
                        dialogs.clear();
                        histories.clear(); linkedSessions.clear(); pendingMessages.clear();
                        statuses.clear(); dialogTitles.clear(); dialogDirectories.clear(); machineNames.clear();
                        prefetchedRevisions.clear(); prefetching.clear();
                        loading = false; listRefreshScheduled = false; loadError = null;
                        loggingOut = false;
                    }
                    completed.run();
                });
            });
        });
    }

    /** 原草稿格式保持不变，仅按服务及账号选择文件；未登录不读取任何账号草稿。 */
    public static synchronized String draftPreferencesName(int telegramAccount) {
        String original = telegramAccount == 0 ? "drafts" : "drafts" + telegramAccount;
        if (!loggedIn()) return "codex-drafts-signed-out-" + telegramAccount;
        String owner = TranscriptStore.digest(session.server + "\n" + session.accountId);
        String legacyOwner = ApplicationLoader.applicationContext.getSharedPreferences("codex-preferences", 0)
                .getString("legacyDraftOwner", "");
        return owner.equals(legacyOwner) ? original : "codex-drafts-" + owner + "-" + telegramAccount;
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
        /** cached 回调保留后台加载，网络回调结束本次请求；失败的 snapshot 为空。 */
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
            if (memory == null) {
                try {
                    JsonObject cached = store.read(kind, machine, scopeRoots);
                    if (cached != null) {
                        JsonObject prepared = prepareBrowseSnapshot(kind, machine, cached, null, owner, accountEpoch);
                        publishBrowse(account, accountEpoch, key, kind, machine, prepared, true, -1, -1, callback);
                    }
                } catch (java.io.IOException ignored) { /* 坏缓存按无缓存处理，不阻止原网络入口恢复。 */ }
            }
            dialogQueue.postRunnable(() -> {
                if (!isAccountCurrent(accountEpoch)) return;
                long startedAt = android.os.SystemClock.elapsedRealtime();
                try {
                    JsonObject page = new JsonObject();
                    com.google.gson.JsonArray rows;
                    if ("computers".equals(kind)) rows = computers();
                    else if ("projects".equals(kind)) {
                        JsonObject response = openDesktop(machine).projects();
                        rows = response.getAsJsonArray("projects");
                        if (response.has("searchIncomplete")) page.add("searchIncomplete", response.get("searchIncomplete"));
                    } else {
                        DesktopConnection connection = openDesktop(machine);
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

    /** 原输入框继续负责清空与动画；先发布本地气泡，再在独立队列向电脑发送。 */
    public static void sendMessage(int account, org.telegram.messenger.SendMessagesHelper.SendMessageParams params) {
        final long accountEpoch = accountGeneration;
        if (!isAccountCurrent(accountEpoch)) return;
        String remote = remoteIds.get(params.peer);
        org.telegram.messenger.MessageObject object = params.retryMessageObject;
        if (object == null) {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.local_id = message.id = org.telegram.messenger.UserConfig.getInstance(account).getNewMessageId();
            org.telegram.messenger.UserConfig.getInstance(account).saveConfig(false);
            message.dialog_id = params.peer;
            message.date = (int) (System.currentTimeMillis() / 1000);
            message.message = params.message;
            message.out = true;
            message.unread = true;
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = params.peer;
            message.from_id = new TLRPC.TL_peerUser();
            message.flags |= 256;
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.params = new HashMap<>();
            message.params.put("codexLocalId", java.util.UUID.randomUUID().toString());
            message.send_state = org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SENDING;
            object = new org.telegram.messenger.MessageObject(account, message, true, false);
            object.wasJustSent = true;
            ArrayList<org.telegram.messenger.MessageObject> added = new ArrayList<>();
            added.add(object);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceiveNewMessages,
                    params.peer, added, false, 0);
            // 只在提交当下清除这一份原草稿，异步回执不得清除用户随后输入的新草稿。
            org.telegram.messenger.MediaDataController.getInstance(account).cleanDraft(params.peer,
                    params.replyToTopMsg == null ? 0 : params.replyToTopMsg.getId(), false);
        }
        final org.telegram.messenger.MessageObject pending = object;
        pendingMessages.put(pending.messageOwner.params.get("codexLocalId"), pending);
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge", "pending_key=" + pending.messageOwner.params.get("codexLocalId").hashCode());
        final long dialogId = params.peer;
        // 调试仅记录阶段耗时和随机消息身份的哈希；不记录正文、账号或会话路径。
        final long sendStarted = android.os.SystemClock.elapsedRealtime();
        final int sendTrace = pending.messageOwner.params.get("codexLocalId").hashCode();
        final OutboxStore outbox = outboxStore(dialogId);
        sendQueue.postRunnable(() -> {
            try {
                if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge",
                        "send_phase=queue key=" + sendTrace + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - sendStarted));
                // 先保存原身份，再接触网络；重启恢复只展示，不自动重发。
                outbox.put(new OutboxStore.Item(pending.messageOwner.params.get("codexLocalId"), remote,
                        pending.messageOwner.message, pending.getId(), pending.messageOwner.date));
                if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge",
                        "send_phase=persisted key=" + sendTrace + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - sendStarted));
                // 退出前已经出现的气泡仍落盘，但退出后不再发出新的网络请求。
                if (!isAccountCurrent(accountEpoch)) return;
                DesktopConnection connection = dialogConnection(dialogId);
                if (connection == null) throw new java.io.IOException("对话所属电脑尚未连接");
                String linked = linkedSessions.get(dialogId);
                if (linked == null) {
                    linked = connection.openConversation(remote).get("sessionId").getAsString();
                    linkedSessions.put(dialogId, linked);
                }
                // 发送接口独立核对原会话与原生接受结果；不等待展示状态的额外查询。
                // native-auto-v1 由桌面选择继续当前轮或开启下一轮，拒绝和未知均保留原待发身份。
                if (!isAccountCurrent(accountEpoch)) return;
                if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge",
                        "send_phase=linked key=" + sendTrace + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - sendStarted));
                connection.send(linked, pending.messageOwner.message, pending.messageOwner.params.get("codexLocalId"));
                if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge",
                        "send_phase=ack key=" + sendTrace + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - sendStarted));
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    pending.messageOwner.send_state = org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SENT;
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageReceivedByServer,
                            pending.getId(), pending.getId(), pending.messageOwner, dialogId, 0L, 0, false);
                });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    pending.messageOwner.send_state = org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageSendError, pending.getId());
                });
            }
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
                    if (selected.contains(item.messageId)) outbox.remove(item.localId);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    pendingMessages.entrySet().removeIf(entry -> entry.getValue().getDialogId() == dialogId
                            && selected.contains(entry.getValue().getId()));
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

    /** 原聊天页进入前台时跟随尾部游标；停止后旧响应不再投递到页面。 */
    public static void watchConversation(int account, long dialogId) {
        if (loggingOut || !loggedIn() || !ownsConversation(dialogId)) return;
        watchedDialog = dialogId;
        long generation = ++watchGeneration;
        watchStatus(account, dialogId, generation);
        String remote = remoteIds.get(dialogId);
        Utilities.globalQueue.postRunnable(new Runnable() {
            private int consecutiveTailPages;
            /** 每次只拉取新消息，后台失败延后重试，不发出空历史覆盖已有正文。 */
            @Override public void run() {
                if (generation != watchGeneration) return;
                long delay = 2000;
                try {
                    TranscriptWindow history = histories.get(dialogId);
                    DesktopConnection connection = dialogConnection(dialogId);
                    if (connection == null) {
                        // 离线冷启没有实时连接；回到网络后自动重建，不要求退出聊天手动刷新。
                        AndroidUtilities.runOnUIThread(() -> {
                            if (generation == watchGeneration) refreshDialogs(account);
                        });
                        delay = 5000;
                    } else if (history != null && history.loaded && history.tailCursor != null) {
                        final String previousCursor = history.tailCursor;
                        final Runnable next = this;
                        // 网络等待离开本地历史队列；同一观察轮只在请求结束后安排下一次。
                        transcriptQueue.postRunnable(() -> {
                            JsonObject page = null;
                            try {
                                if (generation == watchGeneration && connection == dialogConnection(dialogId))
                                    page = connection.readAfter(remote, previousCursor);
                            } catch (Exception error) {
                                logTranscriptFailure("tail_request", error, null);
                                /* 保留原记录，按失败间隔重试。 */
                            }
                            final JsonObject received = page;
                            Utilities.globalQueue.postRunnable(() -> {
                                if (generation != watchGeneration) return;
                                long nextDelay = 5000;
                                try {
                                    // 返回期间可能换电脑连接或被预取推进游标，旧响应不能回写。
                                    if (received != null && connection == dialogConnection(dialogId)
                                            && histories.get(dialogId) == history
                                            && java.util.Objects.equals(previousCursor, history.tailCursor)) {
                                        ArrayList<TranscriptWindow.Entry> added = history.append(received);
                                        if (!added.isEmpty() || !java.util.Objects.equals(previousCursor, history.tailCursor))
                                            saveHistory(dialogId, remote, history);
                                        AndroidUtilities.runOnUIThread(() -> {
                                            if (generation != watchGeneration) return;
                                            ArrayList<org.telegram.messenger.MessageObject> incoming = new ArrayList<>();
                                            for (TranscriptWindow.Entry entry : added) {
                                                org.telegram.messenger.MessageObject pending = entry.message.localId == null ? null
                                                        : pendingMessages.remove(entry.message.localId);
                                                if (org.telegram.messenger.BuildVars.DEBUG_VERSION && entry.message.outgoing) android.util.Log.i("CodexBridge", "echo_key=" + (entry.message.localId == null ? 0 : entry.message.localId.hashCode()) + " matched=" + (pending != null));
                                                TLRPC.TL_message message = historyMessage(dialogId, entry);
                                                if (pending != null) {
                                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageReceivedByServer,
                                                            pending.getId(), message.id, message, dialogId, 0L, 0, false);
                                                } else {
                                                    incoming.add(new org.telegram.messenger.MessageObject(account, message, true, false));
                                                }
                                            }
                                            if (!incoming.isEmpty()) NotificationCenter.getInstance(account).postNotificationName(
                                                    NotificationCenter.didReceiveNewMessages, dialogId, incoming, false, 0);
                                        });
                                        // 有积压才连续取页，最多四页后让出两秒；追平不增加空轮询。
                                        if (TranscriptWindow.hasPendingTail(received, previousCursor) && ++consecutiveTailPages < 4) {
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
                                    logTranscriptFailure("tail_merge", error, received);
                                    consecutiveTailPages = 0; /* 无效增量不清空已有正文。 */
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

    /** 按对话所属电脑观察状态，不能复用其他电脑的实时状态。 */
    private static void watchStatus(int account, long dialogId, long generation) {
        Runnable poll = new Runnable() {
            private int initialObservationRetries;

            /** 初次观察短暂追踪基线，稳定后及失败时恢复原查询频率。 */
            @Override public void run() {
                if (generation != watchGeneration) return;
                long nextDelay = 5000;
                String label = "连接暂不可用";
                JsonObject statusResponse = null;
                long observationStartedAt = android.os.SystemClock.elapsedRealtime();
                try {
                    DesktopConnection connection = dialogConnection(dialogId);
                    if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                        android.util.Log.i("CodexBridge", "status_connection=" + (connection == null ? "absent" : "present"));
                    if (connection != null) {
                        if (observationOwner != null && (observationOwner != connection || observationDialog != dialogId)) releaseObservation();
                        String linked = linkedSessions.get(dialogId);
                        if (linked == null) {
                            linked = connection.openConversation(remoteIds.get(dialogId)).get("sessionId").getAsString();
                            linkedSessions.put(dialogId, linked);
                        }
                        if (observationLease == null || android.os.SystemClock.elapsedRealtime() >= observationRenewAt) {
                            boolean renewing = observationLease != null;
                            JsonObject attached = connection.observe(remoteIds.get(dialogId), linked, observationLease);
                            if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                                android.util.Log.i("CodexBridge", renewing ? "observation_lease=renew" : "observation_lease=attach");
                            observationOwner = connection;
                            observationDialog = dialogId;
                            observationSession = linked;
                            observationLease = attached.get("leaseId").getAsString();
                            observationRenewAt = android.os.SystemClock.elapsedRealtime() + 30000;
                        }
                        if (generation != watchGeneration) return;
                        observationStartedAt = android.os.SystemClock.elapsedRealtime();
                        JsonObject response = connection.status(remoteIds.get(dialogId), linked);
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
                final JsonObject observed = statusResponse;
                final long startedAt = observationStartedAt;
                final long receivedAt = android.os.SystemClock.elapsedRealtime();
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation != watchGeneration) return;
                    statuses.observation(dialogId, dialogMachine(dialogId), observed, startedAt, receivedAt);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
                });
                if (generation == watchGeneration) statusQueue.postRunnable(this, nextDelay);
            }
        };
        // 注册和唤醒均由状态队列串行处理，切页后的旧轮次不能替换当前轮询。
        statusQueue.postRunnable(() -> {
            if (generation != watchGeneration) return;
            if (statusPoll != null) statusQueue.cancelRunnable(statusPoll);
            statusPoll = poll;
            statusQueue.postRunnable(poll);
        });
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override public void run() {
                if (generation != watchGeneration) return;
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
        return message;
    }

    /** 待发消息始终写入对话所属电脑目录。 */
    private static OutboxStore outboxStore(long dialogId) {
        return new OutboxStore(new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "codex-outbox"),
                session.server, session.accountId, dialogMachine(dialogId));
    }

    private static org.telegram.messenger.MessageObject restoredPending(int account, long dialogId, OutboxStore.Item item) {
        org.telegram.messenger.MessageObject existing = pendingMessages.get(item.localId);
        if (existing != null) return existing;
        org.telegram.messenger.UserConfig config = org.telegram.messenger.UserConfig.getInstance(account);
        config.lastSendMessageId = Math.min(config.lastSendMessageId, item.messageId - 1);
        config.saveConfig(false);
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.local_id = message.id = item.messageId;
        message.dialog_id = dialogId;
        message.date = item.date;
        message.message = item.text;
        message.out = true;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = dialogId;
        message.from_id = new TLRPC.TL_peerUser();
        message.flags |= 256;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.params = new HashMap<>();
        message.params.put("codexLocalId", item.localId);
        // 未取得回显不能声明已送达，也不能在恢复进程时自动执行指令。
        message.send_state = org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
        org.telegram.messenger.MessageObject result = new org.telegram.messenger.MessageObject(account, message, true, false);
        pendingMessages.put(item.localId, result);
        return result;
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

    /** 同一电脑目录先保存回显，再移除已送达的待发记录。 */
    private static void saveHistory(long dialogId, String remote, TranscriptWindow history) {
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
                for (OutboxStore.Item item : queued) if (echoed.contains(item.localId)) outbox.remove(item.localId);
            }
        }
        catch (java.io.IOException error) {
            if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.w("CodexBridge", "history_cache_write_failed");
        }
    }

    /** 用原聊天页的加载事件交付桌面文字，保持原列表布局和滚动逻辑。 */
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
                    restored.removeIf(item -> echoed.contains(item.localId));
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isAccountCurrent(accountEpoch)) return;
                        ArrayList<org.telegram.messenger.MessageObject> objects = new ArrayList<>();
                        for (int i = 0; i < Math.min(count, rows.size()); i++) {
                            TranscriptWindow.Entry row = rows.get(i);
                            TLRPC.TL_message message = historyMessage(dialogId, row);
                            objects.add(new org.telegram.messenger.MessageObject(account, message, true, false));
                        }
                        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.i("CodexBridge", "history type=" + loadType + " max=" + maxId + " count=" + objects.size());
                        // 原页以数量不足判断结束；只有来源明确完整时才允许进入结束分支。
                        int reportedCount = end ? Math.max(count, objects.size() + 1) : objects.size();
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDidLoad,
                                dialogId, reportedCount, objects, false, 0, 0, 0, 0,
                                loadType, end, classGuid, loadIndex, loadedAnchor, 0, mode);
                        if (!restored.isEmpty()) {
                            ArrayList<org.telegram.messenger.MessageObject> pending = new ArrayList<>();
                            for (OutboxStore.Item item : restored) pending.add(restoredPending(account, dialogId, item));
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

    /** 预取保留原账号快照，后台分配编号时使用同一代次校验。 */
    private static void prefetchDialogs(com.google.gson.JsonArray candidates, DesktopConnection connection, int catchupPages) {
        final long accountEpoch = accountGeneration;
        final PasswordLogin.Session owner = session;
        if (!isAccountCurrent(accountEpoch)) return;
        Utilities.globalQueue.postRunnable(() -> {
            if (!isAccountCurrent(accountEpoch)) return;
            int remaining = recentDialogLimit();
            for (com.google.gson.JsonElement value : candidates) {
                if (remaining-- <= 0) break;
                JsonObject candidate = value.getAsJsonObject();
                String remote = candidate.get("remoteSessionId").getAsString();
                long revision = candidate.get("updatedAtMs").getAsLong();
                long dialogId = bindDialog(connection.machineId, remote, owner, accountEpoch);
                if (dialogId == 0 || dialogId == watchedDialog || prefetching.contains(dialogId)
                        || java.util.Objects.equals(prefetchedRevisions.get(dialogId), revision)) continue;
                TranscriptWindow history = histories.computeIfAbsent(dialogId, ignored -> readHistory(dialogId, remote));
                String cursor = history.tailCursor;
                boolean initial = !history.loaded;
                if (!initial && cursor == null) continue;
                prefetching.add(dialogId);
                prefetchQueue.postRunnable(() -> {
                    if (!isAccountCurrent(accountEpoch)) return;
                    JsonObject page = null;
                    try {
                        if (connection == desktop && !ApplicationLoader.mainInterfacePaused)
                            page = initial ? connection.transcript(remote) : connection.readAfter(remote, cursor);
                    } catch (Exception error) {
                        logTranscriptFailure(initial ? "prefetch_initial_request" : "prefetch_tail_request", error, null);
                        /* 下一次列表刷新重试，不把失败记为已同步。 */
                    }
                    final JsonObject received = page;
                    Utilities.globalQueue.postRunnable(() -> {
                        if (!isAccountCurrent(accountEpoch)) return;
                        prefetching.remove(dialogId);
                        if (received == null || connection != desktop || dialogId == watchedDialog
                                || !java.util.Objects.equals(cursor, history.tailCursor) || initial != !history.loaded) return;
                        try {
                            if (initial) history.prepend(received); else history.append(received);
                            saveHistory(dialogId, remote, history);
                            // 服务端仍有后续增量时，下次继续同一游标，不能提前记为追平。
                            boolean more = received.has("hasMore") && received.get("hasMore").getAsBoolean();
                            boolean truncated = received.has("truncated") && received.get("truncated").getAsBoolean();
                            if (initial || (!more && !truncated)) prefetchedRevisions.put(dialogId, revision);
                            if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                                android.util.Log.i("CodexBridge", "prefetch_page batch=" + catchupPages
                                        + " initial=" + initial + " items=" + received.getAsJsonArray("items").size()
                                        + " pending=" + TranscriptWindow.hasPendingTail(received, cursor));
                            // 与前台相同：只在原游标明确仍有积压时有界追赶，空闲不增加请求。
                            if (!initial && catchupPages < 4 && TranscriptWindow.hasPendingTail(received, cursor)) {
                                com.google.gson.JsonArray pending = new com.google.gson.JsonArray();
                                pending.add(candidate);
                                prefetchDialogs(pending, connection, catchupPages + 1);
                            }
                        } catch (Exception error) {
                            logTranscriptFailure(initial ? "prefetch_initial_merge" : "prefetch_tail_merge", error, received);
                            /* 保留已有正文，下一轮仍可重试。 */
                        }
                    });
                });
            }
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

    /** 后台保存编号，界面复用稳定模型；只有顺序或展示字段改变才提交原列表差异。 */
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
                AndroidUtilities.runOnUIThread(() -> { if (isAccountCurrent(accountEpoch)) loading = false; });
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
                });
            }
        });
    }
}
