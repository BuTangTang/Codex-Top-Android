package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.client.AckWithTimeout;
import io.socket.thread.EventThread;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 账号下单台电脑的现有 Socket.IO RPC 连接，所有阻塞方法仅在后台线程调用。 */
public final class DesktopConnection implements AutoCloseable {
    public final String machineId;
    public final String machineName;
    private final Socket socket;
    private final WireCrypto crypto;
    private SocketAckWireTrace wireTrace;
    private final java.util.Set<PendingRpc> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 注册、完成及取消共用门锁，并按注册顺序唤醒同连接尚未派发的发送批次。
    private final java.util.Set<CompletableFuture<Void>> connectionWaiters = new java.util.LinkedHashSet<>();
    private android.net.ConnectivityManager connectivity;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private android.net.Network currentNetwork;
    private volatile boolean closed;
    private volatile Long attachmentUploadMaxBytes;
    private long attachmentUploadGeneration;
    // 仅 Socket.IO 事件线程读写；首次连接由 openDesktop 完成登记后唤醒。
    private boolean hasConnected;

    /** 首页尚未选择电脑时，沿用账号下首台在线电脑。 */
    public static DesktopConnection open(PasswordLogin.Session session) throws Exception {
        return open(session, null);
    }

    /** 指定电脑必须匹配同一账号返回的身份；离线时不能回退到另一台电脑。 */
    public static DesktopConnection open(PasswordLogin.Session session, String machineId) throws Exception {
        // 调试仅记录阶段耗时，不输出服务地址、电脑标识或认证内容。
        long startedAt = android.os.SystemClock.elapsedRealtime();
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
            android.util.Log.i("CodexBridge", "connection_open=machines_start");
        JsonArray machines = PasswordLogin.machines(session);
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
            android.util.Log.i("CodexBridge", "connection_open=machines_ready elapsedMs="
                    + (android.os.SystemClock.elapsedRealtime() - startedAt));
        for (JsonElement element : machines) {
            JsonObject machine = element.getAsJsonObject();
            if (machineId != null && !machineId.equals(machine.get("id").getAsString())) continue;
            if (!machine.has("active") || !machine.get("active").getAsBoolean()) continue;
            if (machine.has("revokedAt") && !machine.get("revokedAt").isJsonNull()) continue;
            return new DesktopConnection(session, machine);
        }
        throw new IOException(machineId == null ? "没有在线电脑，请先打开电脑端连接" : "所选电脑未连接，请在该电脑上打开连接");
    }

    /** 解密电脑显示名供原列表使用；不为每台电脑创建套接字。 */
    public static JsonArray computers(PasswordLogin.Session session) throws Exception {
        JsonArray result = new JsonArray();
        for (JsonElement value : PasswordLogin.machines(session)) {
            JsonObject machine = value.getAsJsonObject();
            if (machine.has("revokedAt") && !machine.get("revokedAt").isJsonNull()) continue;
            String name = "电脑";
            try (WireCrypto key = new WireCrypto(session.secret,
                    machine.has("dataEncryptionKey") && !machine.get("dataEncryptionKey").isJsonNull()
                            ? machine.get("dataEncryptionKey").getAsString() : null)) {
                JsonObject metadata = JsonParser.parseString(key.decrypt(machine.get("metadata").getAsString())).getAsJsonObject();
                if (metadata.has("host") && !metadata.get("host").isJsonNull()) name = metadata.get("host").getAsString();
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", machine.get("id").getAsString());
            row.addProperty("name", name);
            row.addProperty("active", machine.has("active") && machine.get("active").getAsBoolean());
            // 服务器的最后在线时刻，供电脑页显示“上次在线”；缺失时不填，不估算。
            if (machine.has("activeAt") && machine.get("activeAt").isJsonPrimitive()
                    && machine.get("activeAt").getAsJsonPrimitive().isNumber())
                row.addProperty("activeAt", machine.get("activeAt").getAsLong());
            result.add(row);
        }
        return result;
    }

    /** 使用与现有客户端相同的用户级握手，连接失败及时释放密钥和套接字。 */
    private DesktopConnection(PasswordLogin.Session session, JsonObject machine) throws Exception {
        machineId = machine.get("id").getAsString();
        crypto = new WireCrypto(session.secret, machine.has("dataEncryptionKey") && !machine.get("dataEncryptionKey").isJsonNull()
                ? machine.get("dataEncryptionKey").getAsString() : null);
        Socket created = null;
        try {
            JsonObject metadata = JsonParser.parseString(crypto.decrypt(machine.get("metadata").getAsString())).getAsJsonObject();
            // 缺失名称保持空值，由页面说明未知；不能把通用占位文案当作电脑真名。
            machineName = metadata.has("host") && metadata.get("host").isJsonPrimitive()
                    && metadata.get("host").getAsJsonPrimitive().isString() ? metadata.get("host").getAsString() : "";
            IO.Options options = new IO.Options();
            options.path = "/v1/updates/";
            options.forceNew = true;
            options.multiplex = false;
            options.reconnection = true;
            options.transports = new String[]{"websocket"};
            Map<String, String> auth = new HashMap<>();
            auth.put("token", session.token);
            auth.put("clientType", "user-scoped");
            auth.put("clientPurpose", "sync");
            options.auth = auth;
            created = IO.socket(URI.create(session.server), options);
            socket = created;
            // 原Manager开始连接前安装有限观察，关闭时不触碰原传输工厂。
            wireTrace = SocketAckWireTrace.install(socket);
            CompletableFuture<Void> connected = new CompletableFuture<>();
            socket.once(Socket.EVENT_CONNECT, args -> connected.complete(null));
            // 重连成功才通知状态所有者，断线或握手失败不能伪造可用状态。
            socket.on(Socket.EVENT_CONNECT, args -> {
                if (closed) return;
                completeConnectionWaiters();
                if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                    android.util.Log.i("CodexBridge", "transport=" + (hasConnected ? "reconnected" : "connected"));
                // 新实例能力本为空；首次ready不能使同连接的第一份列表失效。
                if (hasConnected) {
                    clearAttachmentUploadLimit();
                    CodexRuntime.onDesktopConnected(this);
                }
                hasConnected = true;
            });
            socket.once(Socket.EVENT_CONNECT_ERROR, args -> connected.completeExceptionally(new IOException("实时连接失败")));
            // 断连同时使列表事实失效，不能等用户重新打开聊天页才发现旧状态。
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                failPending();
                if (!closed) CodexRuntime.onDesktopDisconnected(this);
            });
            watchNetwork();
            socket.connect();
            connected.get(20, TimeUnit.SECONDS);
            if (org.telegram.messenger.BuildVars.DEBUG_VERSION)
                android.util.Log.i("CodexBridge", "connection_open=socket_ready");
        } catch (Exception error) {
            closed = true;
            unwatchNetwork();
            if (created != null) { created.off(); created.disconnect(); }
            crypto.close();
            throw error;
        }
    }

    /** 默认网络改变时直接重建传输，不等待旧 WebSocket 心跳超时。 */
    private void watchNetwork() {
        if (android.os.Build.VERSION.SDK_INT < 24) return;
        connectivity = (android.net.ConnectivityManager) org.telegram.messenger.ApplicationLoader.applicationContext
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return;
        currentNetwork = connectivity.getActiveNetwork();
        networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network network) {
                EventThread.exec(() -> {
                    if (closed || network.equals(currentNetwork)) return;
                    currentNetwork = network;
                    failPending();
                    socket.disconnect();
                    socket.connect();
                });
            }
            @Override public void onLost(android.net.Network network) {
                EventThread.exec(() -> {
                    if (closed || !network.equals(currentNetwork)) return;
                    currentNetwork = null;
                    failPending();
                    socket.disconnect();
                });
            }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    private void unwatchNetwork() {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
            networkCallback = null;
        }
    }

    /** 原断连入口同时清容量与在途请求；派发门前可证明未发出，派发门后保留未知结果。 */
    private void failPending() {
        clearAttachmentUploadLimit();
        for (PendingRpc result : pending) {
            synchronized (result) {
                // 与事件线程的派发标记共用门锁，不能在判为未派发后仍进入emit。
                result.completeExceptionally(result.dispatchStarted
                        ? new IOException("连接已中断，发送结果需核对") : new RpcNotDispatchedException());
            }
        }
    }

    /** 当前传输的只读摘要；不能作为电脑在线、任务状态或执行权限的证明。 */
    public boolean isConnected() { return !closed && socket.connected(); }

    /** 只等待原Socket的连接事件，不创建连接或发RPC；调用方负责期限与取消。 */
    CompletableFuture<Void> whenConnected() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (connectionWaiters) {
            connectionWaiters.add(result);
            result.whenComplete((value, error) -> {
                synchronized (connectionWaiters) { connectionWaiters.remove(result); }
            });
            // 先登记再读取状态，覆盖CONNECT发生于调用与登记之间的漏唤醒窗口。
            completeConnectionWaiters();
        }
        return result;
    }

    /** 原连接成功或关闭时一次性完成已登记等待；断线继续等待原自动重连。 */
    private void completeConnectionWaiters() {
        synchronized (connectionWaiters) {
            if (!closed && !socket.connected()) return;
            for (CompletableFuture<Void> result : new java.util.ArrayList<>(connectionWaiters)) {
                if (closed) result.completeExceptionally(new RpcNotDispatchedException());
                else result.complete(null);
            }
        }
    }

    /** 只返回本连接已读的实际单文件上限；断线及旧机器缺字段均为未知。 */
    public Long attachmentUploadMaxBytes() { return isConnected() ? attachmentUploadMaxBytes : null; }

    /** 沿原连接生命周期清除能力，并阻止已完成旧列表在重连后迟到回写。 */
    private synchronized void clearAttachmentUploadLimit() {
        attachmentUploadMaxBytes = null;
        attachmentUploadGeneration++;
    }

    /** 最近列表沿用单页请求，数量由用户设置。 */
    public JsonObject candidates(int limit) throws Exception {
        return candidates(limit, null);
    }

    /** 沿原来源游标读列表与可选容量，不受首页数量截断，也不追加能力请求。 */
    public JsonObject candidates(int limit, String cursor) throws Exception {
        final long generation;
        synchronized (this) { generation = attachmentUploadGeneration; }
        JsonObject params = base(null);
        params.addProperty("limit", Math.max(1, Math.min(500, limit)));
        if (cursor != null && !cursor.isEmpty()) params.addProperty("cursor", cursor);
        JsonObject response = rpc("daemon.directSessions.candidates.list", params);
        Long maximum = null;
        if (response.has("capabilities") && response.get("capabilities").isJsonObject()) {
            JsonElement value = response.getAsJsonObject("capabilities").get("attachmentUploadMaxBytes");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                try {
                    long bytes = value.getAsBigDecimal().longValueExact();
                    if (bytes > 0 && bytes <= 9007199254740991L) maximum = bytes;
                } catch (ArithmeticException | NumberFormatException ignored) { }
            }
        }
        synchronized (this) {
            if (generation == attachmentUploadGeneration && isConnected()) attachmentUploadMaxBytes = maximum;
        }
        return response;
    }

    /** 项目来自指定电脑的真实配置；不能用最近会话路径拼造项目。 */
    public JsonObject projects() throws Exception {
        return rpc("daemon.directSessions.projects.list", base(null));
    }

    /** 获取指定会话的最近消息，保留返回游标供后续增量刷新。 */
    public JsonObject transcript(String remoteSessionId) throws Exception {
        return transcript(remoteSessionId, null);
    }

    /** 沿服务端返回的游标读取上一页，避免每次都重复请求最近一页。 */
    public JsonObject transcript(String remoteSessionId, String cursor) throws Exception {
        JsonObject params = base(remoteSessionId);
        params.addProperty("direction", "older");
        params.addProperty("maxItems", 100);
        params.addProperty("maxBytes", 512 * 1024);
        params.addProperty("projection", "conversation_text");
        if (cursor != null && !cursor.isEmpty()) params.addProperty("cursor", cursor);
        return rpc("daemon.directSessions.transcript.page", params);
    }

    /** 只读取尾部游标之后的新消息，避免轮询时重复下载整页历史。 */
    public JsonObject readAfter(String remoteSessionId, String cursor) throws Exception {
        if (cursor == null || cursor.isEmpty()) throw new IllegalArgumentException("缺少消息增量游标");
        JsonObject params = base(remoteSessionId);
        params.addProperty("cursor", cursor);
        params.addProperty("maxItems", 100);
        params.addProperty("maxBytes", 64 * 1024);
        params.addProperty("projection", "conversation_text");
        return rpc("daemon.directSessions.transcript.readAfter", params);
    }

    /** 关联已存在的电脑对话；用户已允许必要时打开原会话，不创建新任务。 */
    public JsonObject openConversation(String remoteSessionId) throws Exception {
        return awaitRpc(openConversationAsync(remoteSessionId, null, null));
    }

    /** 沿同一关联请求返回原future；派发时与调用方取消位共用gate，不阻塞发送队列等待回执。 */
    public CompletableFuture<JsonObject> openConversationAsync(String remoteSessionId, Object gate, BooleanSupplier permitted) {
        JsonObject params = base(remoteSessionId);
        params.addProperty("openExisting", true);
        return rpcAsync("daemon.directSessions.link.ensure", params, gate, permitted);
    }

    /** 已准备发送缺关联缓存时使用原RPC；热加载证明由桌面判断，旧handler仍按true打开。 */
    CompletableFuture<JsonObject> linkConversationForSendAsync(String remoteSessionId, Object gate, BooleanSupplier permitted) {
        JsonObject params = base(remoteSessionId);
        params.addProperty("openExisting", true);
        params.addProperty("openExistingMode", "if-unloaded");
        return rpcAsync("daemon.directSessions.link.ensure", params, gate, permitted);
    }

    /** 原调用保持省略目标字段，不让未消费目标的入口增加来源读取。 */
    public JsonObject status(String remoteSessionId, String sessionId) throws Exception {
        return status(remoteSessionId, sessionId, false);
    }

    /** 当前聊天在同一次原STATUS内显式请求只读目标；协议不接受false字段。 */
    public JsonObject status(String remoteSessionId, String sessionId, boolean includeGoal) throws Exception {
        JsonObject params = base(remoteSessionId);
        params.addProperty("sessionId", sessionId);
        if (includeGoal) params.addProperty("includeGoal", true);
        return rpc("daemon.directSessions.status.get", params);
    }

    private final java.util.Set<String> notificationFollows = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 已打开的真实关联在电脑沿原事件机制后台关注，每条连接只成功启用一次，不新增手机轮询。 */
    public void ensureTaskNotifications(String remoteSessionId, String sessionId) {
        if (closed || sessionId == null || remoteSessionId == null || !notificationFollows.add(sessionId)) return;
        JsonObject params = base(remoteSessionId);
        params.addProperty("sessionId", sessionId);
        params.addProperty("enabled", true);
        rpcAsync("daemon.directSessions.followPolicy.set", params, null, null).whenComplete((response, error) -> {
            // 失败只释放本地门禁，等下次明确打开或观察时再尝试，不建立重试定时器。
            if (error != null || response == null || !response.has("ok") || !response.get("ok").getAsBoolean()
                    || !response.has("leaseActive") || !response.get("leaseActive").getAsBoolean()) notificationFollows.remove(sessionId);
        });
    }

    /** 前台沿原短租约观察；后台通知关注由独立显式入口开启。 */
    public JsonObject observe(String remoteSessionId, String sessionId, String leaseId) throws Exception {
        JsonObject params = base(remoteSessionId);
        params.addProperty("sessionId", sessionId);
        params.addProperty("ttlMs", 60000);
        if (leaseId != null) params.addProperty("leaseId", leaseId);
        return rpc("daemon.directSessions.attach", params);
    }

    public void stopObserving(String sessionId, String leaseId) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId);
        params.addProperty("sessionId", sessionId);
        params.addProperty("leaseId", leaseId);
        rpc("daemon.directSessions.detach", params);
    }

    /** 使用调用方保存的唯一消息身份发送一次，失败或超时不擅自重发。 */
    public JsonObject send(String sessionId, String text, String localId) throws Exception {
        return send(sessionId, text, localId, java.util.Collections.emptyList());
    }

    /** 正文与已完成上传的附件使用同一原生输入及发送编号，纯附件不被当作空消息。 */
    public JsonObject send(String sessionId, String text, String localId,
            java.util.List<DesktopAttachment> attachments) throws Exception {
        return awaitRpc(sendAsync(sessionId, text, localId, attachments, null, null));
    }

    /** 复用原发送封套及消息编号；返回原future，取消位只能在原派发门内阻止尚未发出的请求。 */
    public CompletableFuture<JsonObject> sendAsync(String sessionId, String text, String localId,
            java.util.List<DesktopAttachment> attachments, Object gate, BooleanSupplier permitted) {
        if (sessionId == null || sessionId.isEmpty() || text == null || attachments == null
                || text.isEmpty() && attachments.isEmpty()
                || localId == null || localId.isEmpty()) throw new IllegalArgumentException("发送消息缺少必要信息");
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId);
        params.addProperty("sessionId", sessionId);
        params.addProperty("text", text);
        params.addProperty("localId", localId);
        JsonObject meta = attachments.isEmpty() ? new JsonObject() : DesktopAttachment.meta(attachments);
        meta.addProperty("desktopTextSendProtocol", "native-auto-v1");
        params.add("meta", meta);
        return rpcAsync("daemon.directSessions.send", params, gate, permitted);
    }

    /** 原分块传输也走此电脑已有的认证加密RPC，不创建另一条连接或自动重试。 */
    public JsonObject transfer(String method, JsonObject params) throws Exception {
        if (!method.matches("daemon\\.bulkTransfer\\.(?:upload|download)\\.(?:init|chunk|finalize|abort)"))
            throw new IllegalArgumentException("未知附件传输方法");
        return rpc(method, params);
    }

    /** 失败上传的清理沿原连接异步派发；归属在原派发门再次核对，期限与响应判定保持不变。 */
    CompletableFuture<JsonObject> abortUploadAsync(JsonObject params, Object gate, BooleanSupplier permitted) {
        return rpcAsync("daemon.bulkTransfer.upload.abort", params, gate, permitted);
    }

    /** 异步清理满额时沿原同步等待回退；不丢弃清理，也不另建等待队列或延长期限。 */
    JsonObject abortUpload(JsonObject params, Object gate, BooleanSupplier permitted) throws Exception {
        return awaitRpc(abortUploadAsync(params, gate, permitted));
    }

    /** 按需读取原桌面待审批内容，不放入常规状态轮询或本地历史缓存。 */
    public java.util.ArrayList<DesktopApproval> readApprovals(String sessionId) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) throw new IllegalArgumentException("缺少关联会话");
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId); params.addProperty("sessionId", sessionId);
        return DesktopApproval.read(rpc("daemon.directSessions.control.read", params).getAsJsonObject("snapshot"));
    }

    /** 只发出一次指定版本的决定；返回原结果，由调用方回读确认，不能把 ACK 当成批准成功。 */
    public JsonObject decideApproval(String sessionId, DesktopApproval request, String operationId, boolean allow) throws Exception {
        return rpc("daemon.directSessions.control.action", request.action(machineId, sessionId, operationId, allow))
                .getAsJsonObject("result");
    }

    /** 只在用户查看问题时读取完整原题，旧审批读取保持原快照契约。 */
    public java.util.ArrayList<DesktopQuestion> readQuestions(String sessionId) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId); params.addProperty("sessionId", sessionId);
        params.addProperty("includeQuestions", true);
        return DesktopQuestion.read(rpc("daemon.directSessions.control.read", params).getAsJsonObject("snapshot"));
    }

    /** 完整答案一次提交到原结构化请求，不走普通消息发送或 Telegram 投票。 */
    public JsonObject answerQuestions(String sessionId, DesktopQuestion request, String operationId,
            java.util.Map<String, String> answers) throws Exception {
        return rpc("daemon.directSessions.control.action", request.action(machineId, sessionId, operationId, answers))
                .getAsJsonObject("result");
    }

    /** 按需读取所选电脑当前用户 Codex 的实际额度，沿用浏览会话的同一来源。 */
    public AccountUsage readAccountUsage() throws Exception {
        JsonObject params = base(null);
        params.remove("providerId");
        return AccountUsage.parse(rpc("daemon.directSessions.accountUsage.read", params).getAsJsonObject("result"));
    }

    /** 用户 Codex 来源只有这一处。状态请求和目标缓存都用它，不另猜家目录。 */
    static JsonObject userCodexSource() {
        JsonObject source = new JsonObject();
        source.addProperty("kind", "codexHome");
        source.addProperty("home", "user");
        return source;
    }

    /** 构造已支持的用户 Codex 来源，不新增源路径猜测。 */
    private JsonObject base(String remoteSessionId) {
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId);
        params.addProperty("providerId", "codex");
        params.add("source", userCodexSource());
        if (remoteSessionId != null) params.addProperty("remoteSessionId", remoteSessionId);
        return params;
    }

    /** 仅表示本机传输门禁明确阻止了emit；已发出后的断线和超时仍属于未知结果。 */
    static final class RpcNotDispatchedException extends IOException {
        /** 未发送可由调用方保留草稿，等待用户手动重试。 */
        private RpcNotDispatchedException() { super("电脑连接正在恢复，请稍后重试"); }
    }

    /** 原发送handler明确返回未接受，保留原因供原待发状态决定是否允许手动重试。 */
    static final class SendRejectedException extends IOException {
        final String reason;
        /** 与超时和未知送达分开，不声称这个拒绝发生在传输派发之前。 */
        private SendRejectedException(String reason) { super("电脑暂未接受此消息"); this.reason = reason; }
    }

    /** 复用原请求future；派发状态仅在自身门锁内读写，关闭与事件线程不能交错解锁重试。 */
    private static final class PendingRpc extends CompletableFuture<JsonObject> {
        private boolean dispatchStarted;
        private BulkAckTrace bulkAckTrace;

        /** 取消也与派发门排序；越门后的取消不构成远端撤回或安全重发证明。 */
        @Override public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning);
        }
    }

    /** 上传诊断仅保留进程序号和固定阶段；最多32次请求、每次8条，不持有参数或错误文本。 */
    private static final class BulkAckTrace {
        private static int nextRequest;
        private final int sequence;
        private final String operation;
        private final SocketAckWireTrace wire;
        private final long startedAt;
        private int stages;
        private int wireGeneration = -1;
        private boolean queueTimeoutObserved;

        /** 仅三门通过且额度充足后创建；诊断时钟失败由创建入口隔离。 */
        private BulkAckTrace(int sequence, String operation, SocketAckWireTrace wire) {
            this.sequence = sequence;
            this.operation = operation;
            this.wire = wire;
            startedAt = android.os.SystemClock.elapsedRealtime();
        }

        /** 与既有SEND诊断使用相同门禁；关闭时不分配实例、不读时钟。 */
        private static boolean enabled() {
            if (!org.telegram.messenger.BuildConfig.DEBUG_VERSION) return false;
            try {
                return android.util.Log.isLoggable("CodexSendAck", android.util.Log.DEBUG)
                        && org.telegram.messenger.ApplicationLoader.applicationContext != null
                        && org.telegram.messenger.ApplicationLoader.applicationContext.getPackageName()
                                .startsWith("com.butang.codextop.nativepreview");
            } catch (Throwable ignored) { return false; }
        }

        /** 只接受四个固定上传方法；进程额度不因新连接或重新开标签而重置。 */
        private static BulkAckTrace create(String method, SocketAckWireTrace wire) {
            String operation;
            if ("daemon.bulkTransfer.upload.init".equals(method)) operation = "upload.init";
            else if ("daemon.bulkTransfer.upload.chunk".equals(method)) operation = "upload.chunk";
            else if ("daemon.bulkTransfer.upload.finalize".equals(method)) operation = "upload.finalize";
            else if ("daemon.bulkTransfer.upload.abort".equals(method)) operation = "upload.abort";
            else return null;
            if (!enabled()) return null;
            try {
                synchronized (BulkAckTrace.class) {
                    if (nextRequest >= 32) return null;
                    return new BulkAckTrace(++nextRequest, operation, wire);
                }
            } catch (Throwable ignored) { return null; }
        }

        /** 异常仅映射固定类别；不调用message、toString或记录任意服务端字段。 */
        private static String category(Throwable error) {
            if (error == null) return "none";
            if (error instanceof RpcNotDispatchedException) return "not_dispatched";
            if (error instanceof java.util.concurrent.TimeoutException) return "timeout";
            if (error instanceof java.util.concurrent.CancellationException) return "cancelled";
            if (error instanceof InterruptedException) return "interrupted";
            if (error instanceof IOException) return "io";
            if (error instanceof com.google.gson.JsonParseException) return "json";
            if (error instanceof java.security.GeneralSecurityException) return "crypto";
            if (error instanceof IllegalStateException) return "state";
            return "other";
        }

        /** 同请求每阶段最多一次；wire入口沿原Manager当前ACK编号关联，日志失败不影响业务。 */
        private synchronized void mark(int stage, int valid, int success, Throwable error) {
            if (stage < 0 || stage > 7 || (stages & (1 << stage)) != 0) return;
            stages |= 1 << stage;
            try {
                if (!enabled()) return;
                if (stage == 7) timeoutQueue();
                if (wire != null) {
                    try {
                        if (stage == 1) wireGeneration = wire.emitEntered(sequence);
                        if (stage == 2) wire.ackEntered(sequence);
                    } catch (Throwable ignored) { /* wire观察不可阻断bulk自身阶段或原回调。 */ }
                }
                String phase = stage == 0 ? "rpc_start" : stage == 1 ? "emit_enter" : stage == 2 ? "ack_enter"
                        : stage == 3 ? "response" : stage == 4 ? "exception" : stage == 5 ? "timeout"
                        : stage == 6 ? "terminal" : "wait_timeout";
                long now = android.os.SystemClock.elapsedRealtime();
                android.util.Log.i("CodexSendAck", "bulk_ack_phase=" + phase + " seq=" + sequence
                        + " method=" + operation + " valid=" + valid + " success=" + success
                        + " error=" + category(error) + " elapsedMs=" + (now - startedAt) + " elapsedRealtimeMs=" + now);
            } catch (Throwable ignored) { /* 诊断失败不可改变原响应、异常、取消或期限。 */ }
        }

        /** 原emit正常返回后才采样，保持相同EventThread和请求代次，不新增业务阶段或调度。 */
        private synchronized void emitted() {
            try { if (enabled() && wire != null) wire.emitReturned(sequence, wireGeneration); }
            catch (Throwable ignored) { /* 队列观察不能将已派发请求改成失败。 */ }
        }

        /** future或同步等待方先到期限均只采样一次；保持既有超时、取消及原连接归属。 */
        private synchronized void timeoutQueue() {
            try {
                if (!enabled() || queueTimeoutObserved) return;
                queueTimeoutObserved = true;
                if (wire != null) wire.timedOut(sequence, wireGeneration);
            } catch (Throwable ignored) { /* 不影响原超时和finally取消。 */ }
        }

        /** 只观察实际赢得future的终态；失败与取消使用原异常，超时快照不声明远端结果。 */
        private void finished(Throwable error) {
            if (error instanceof java.util.concurrent.TimeoutException) timeoutQueue();
            if (error != null) mark(error instanceof java.util.concurrent.TimeoutException ? 5 : 4, -1, -1, error);
            mark(6, -1, -1, error);
        }
    }

    /** 原同步调用只等待同一异步核心；保留既有异常解包与超时，不复制响应判定。 */
    private JsonObject rpc(String method, JsonObject params) throws Exception {
        return awaitRpc(rpcAsync(method, params, null, null));
    }

    /** 原等待方仅解包明确未派发及可靠拒绝；未知结果继续沿原包装异常交回。 */
    private JsonObject awaitRpc(CompletableFuture<JsonObject> result) throws Exception {
        try { return result.get(25, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException error) {
            // 等待方可能先于原Timer到期；明确记为wait_timeout，finally仍沿原逻辑取消future。
            if (result instanceof PendingRpc && ((PendingRpc) result).bulkAckTrace != null)
                ((PendingRpc) result).bulkAckTrace.mark(7, -1, -1, error);
            throw error;
        }
        catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof RpcNotDispatchedException)
                throw (RpcNotDispatchedException) error.getCause();
            if (error.getCause() instanceof SendRejectedException)
                throw (SendRejectedException) error.getCause();
            throw error;
        } finally { result.cancel(false); }
    }

    /** 唯一认证加密RPC核心；保留原25秒期限，显式诊断时观察SEND及四种上传RPC的固定边界。 */
    private CompletableFuture<JsonObject> rpcAsync(String method, JsonObject params, Object gate, BooleanSupplier permitted) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        PendingRpc result = new PendingRpc();
        final BulkAckTrace bulkAckTrace = BulkAckTrace.create(method, wireTrace);
        result.bulkAckTrace = bulkAckTrace;
        if (bulkAckTrace != null) bulkAckTrace.mark(0, -1, -1, null);
        if (closed || !socket.connected()) {
            RpcNotDispatchedException error = new RpcNotDispatchedException();
            result.completeExceptionally(error);
            if (bulkAckTrace != null) bulkAckTrace.finished(error);
            return result;
        }
        // 诊断状态只属于此请求；关闭时不创建对象、不读时钟，也不访问消息身份。
        final class SendAckTrace {
            private final int key;
            private final long startedAt;
            private int stages;

            /** 仅在开关全部通过后保存随机消息编号的摘要及单调时钟起点，不持有正文。 */
            private SendAckTrace(int key) {
                this.key = key;
                startedAt = android.os.SystemClock.elapsedRealtime();
            }

            /** 仅由原ACK回调记录四个固定阶段；完成边界包含同步回调，但不证明本次完成赢得终态。 */
            private void mark(int stage) {
                if (stage < 0 || stage > 3 || (stages & (1 << stage)) != 0) return;
                stages |= 1 << stage;
                try {
                    if (stage == 0 && wireTrace != null) wireTrace.ackEntered(key);
                    String phase = stage == 0 ? "ack_enter" : stage == 1 ? "ack_decoded"
                            : stage == 2 ? "ack_complete_begin" : "ack_complete_end";
                    long now = android.os.SystemClock.elapsedRealtime();
                    android.util.Log.i("CodexSendAck", "send_ack_phase=" + phase + " key=" + key
                            + " elapsedMs=" + (now - startedAt) + " elapsedRealtimeMs=" + now);
                } catch (Throwable ignored) { /* 诊断失败不改变原响应、异常或完成通知。 */ }
            }

            /** 仅在原派发位置补入口元数据，不改变Socket调度或期限。 */
            private void emitting() {
                if (wireTrace != null) wireTrace.emitEntered(key);
            }
        }
        SendAckTrace candidateTrace = null;
        if ("daemon.directSessions.send".equals(method) && org.telegram.messenger.BuildConfig.DEBUG_VERSION) {
            try {
                if (android.util.Log.isLoggable("CodexSendAck", android.util.Log.DEBUG)
                        && org.telegram.messenger.ApplicationLoader.applicationContext != null
                        && org.telegram.messenger.ApplicationLoader.applicationContext.getPackageName()
                                .startsWith("com.butang.codextop.nativepreview")) {
                    JsonElement localId = params.get("localId");
                    if (localId != null && localId.isJsonPrimitive() && localId.getAsJsonPrimitive().isString())
                        candidateTrace = new SendAckTrace(localId.getAsString().hashCode());
                }
            } catch (Throwable ignored) { /* 日志开关及诊断时钟不可用时沿用无诊断发送。 */ }
        }
        final SendAckTrace sendAckTrace = candidateTrace;
        final Object dispatchGate = gate == null ? result : gate;
        AckWithTimeout ack = new AckWithTimeout(25000) {
            /** 库已取消Timer并移除ACK编号；迟ACK只记入口，仍仅解析未完成响应。 */
            @Override public void onSuccess(Object... args) { try {
                if (sendAckTrace != null) sendAckTrace.mark(0);
                if (bulkAckTrace != null) bulkAckTrace.mark(2, -1, -1, null);
                if (result.isDone()) return;
                if (args.length == 0 || !(args[0] instanceof JSONObject)) throw new IOException("电脑响应无效");
                JSONObject envelope = (JSONObject) args[0];
                if (!envelope.optBoolean("ok")) throw new IOException("电脑暂时无法响应，请稍后重试");
                JsonObject response = JsonParser.parseString(crypto.decrypt(envelope.getString("result"))).getAsJsonObject();
                // 原bulk handler返回success，其余机器会话handler返回ok，不能改变传输既有封套。
                boolean bulk = method.startsWith("daemon.bulkTransfer.");
                String success = bulk ? "success" : "ok";
                boolean validSuccess = response.has(success) && response.get(success).isJsonPrimitive()
                        && response.get(success).getAsJsonPrimitive().isBoolean();
                if (bulkAckTrace != null) bulkAckTrace.mark(3, validSuccess ? 1 : 0,
                        validSuccess ? (response.get(success).getAsBoolean() ? 1 : 0) : -1, null);
                // 合法拒绝也已完成解密及类型校验，不能把本阶段当作接受发送。
                if (sendAckTrace != null && validSuccess) sendAckTrace.mark(1);
                if (!validSuccess || !response.get(success).getAsBoolean()) {
                    // 只交回既有失败封套，通用RPC不拼接或抛出服务器错误正文。
                    if (bulk && validSuccess && response.has("error") && response.get("error").isJsonPrimitive()
                            && response.get("error").getAsJsonPrimitive().isString()) {
                        result.complete(response);
                        return;
                    }
                    if (validSuccess && "daemon.directSessions.send".equals(method) && response.has("errorCode")
                            && response.get("errorCode").isJsonPrimitive()
                            && response.get("errorCode").getAsJsonPrimitive().isString()) {
                        String reason = response.get("errorCode").getAsString();
                        if (!reason.isEmpty() && !"delivery_outcome_unknown".equals(reason))
                            throw new SendRejectedException(reason);
                    }
                    throw new IOException("电脑未能完成此请求");
                }
                if (sendAckTrace != null) sendAckTrace.mark(2);
                result.complete(response);
                if (sendAckTrace != null) sendAckTrace.mark(3);
            } catch (Exception error) {
                if (sendAckTrace != null) sendAckTrace.mark(2);
                result.completeExceptionally(error);
                if (sendAckTrace != null) sendAckTrace.mark(3);
            } }
            /** Socket自身先清ACK/sendBuffer再回调；此时才能退役同一Timer的剩余任务。 */
            @Override public void onTimeout() {
                cancelTimer();
                synchronized (result) { result.completeExceptionally(new java.util.concurrent.TimeoutException()); }
            }
        };
        result.whenComplete((value, error) -> {
            pending.remove(result);
            synchronized (result) {
                // 门前完成不会进入Socket.schedule；门后保留库的任务，不能提前取消其ACK清理。
                if (!result.dispatchStarted) ack.cancelTimer();
            }
            if (bulkAckTrace != null) bulkAckTrace.finished(error);
        });
        try {
            synchronized (result) {
                pending.add(result);
                // 覆盖初检到登记之间的close；不能只等晚事件线程才能完成已关闭请求。
                if (closed || !socket.connected()) {
                    result.completeExceptionally(new RpcNotDispatchedException());
                    return result;
                }
                ack.schedule(new TimerTask() {
                    /** 独立于发送工作队列完成硬期限；派发门前仍能明确证明未发出。 */
                    @Override public void run() {
                        synchronized (result) {
                            if (!result.isDone()) {
                                if (bulkAckTrace != null) bulkAckTrace.mark(5, -1, -1, null);
                                result.completeExceptionally(result.dispatchStarted
                                        ? new java.util.concurrent.TimeoutException() : new RpcNotDispatchedException());
                            }
                        }
                    }
                });
            }
            JSONObject call = new JSONObject();
            call.put("method", machineId + ":" + method);
            call.put("params", crypto.encrypt(params.toString()));
            call.put("timeoutMs", 20000);
            if ("daemon.directSessions.send".equals(method) || "daemon.directSessions.control.read".equals(method)
                    || "daemon.directSessions.control.action".equals(method)) {
                JSONObject authorization = new JSONObject();
                authorization.put("kind", "session.write");
                authorization.put("sessionId", params.get("sessionId").getAsString());
                call.put("authorization", authorization);
            }
            EventThread.exec(() -> {
                try {
                    synchronized (result) {
                        synchronized (dispatchGate) {
                            // 调用方删除与许可检查共用gate；断线、deadline及future取消共用result门锁。
                            if (closed || !socket.connected() || result.isDone()
                                    || System.nanoTime() >= deadline || permitted != null && !permitted.getAsBoolean()) {
                                result.completeExceptionally(new RpcNotDispatchedException());
                                return;
                            }
                            result.dispatchStarted = true;
                        }
                    }
                    if (sendAckTrace != null) sendAckTrace.emitting();
                    if (bulkAckTrace != null) bulkAckTrace.mark(1, -1, -1, null);
                    socket.emit("rpc-call", new Object[]{call}, ack);
                    if (bulkAckTrace != null) bulkAckTrace.emitted();
                } catch (Exception error) {
                    synchronized (result) {
                        result.completeExceptionally(error);
                        if (result.dispatchStarted) retireFailedEmitTimer(ack);
                    }
                }
            });
        } catch (Exception error) { result.completeExceptionally(error); }
        return result;
    }

    /** emit异常可能在库登记前或后；复用同一Timer给原清理任务完整期限，再兜底退役空Timer。 */
    private void retireFailedEmitTimer(AckWithTimeout ack) {
        try {
            ack.schedule(new TimerTask() {
                /** 两个顺序期限确保库已登记的25秒任务先清ACK，避免相同毫秒的任务排序影响清理。 */
                @Override public void run() {
                    try {
                        ack.schedule(new TimerTask() {
                            /** 仅异常路径兜底；已有成功/Socket超时会先取消此任务。 */
                            @Override public void run() { ack.cancelTimer(); }
                        });
                    } catch (IllegalStateException alreadyRetired) { /* ACK或Socket已退役同一Timer。 */ }
                }
            });
        } catch (IllegalStateException alreadyRetired) { /* ACK或Socket已退役同一Timer。 */ }
    }

    /** 页面释放时关闭连接并擦除机器密钥。 */
    @Override public void close() {
        closed = true;
        completeConnectionWaiters();
        unwatchNetwork();
        failPending();
        socket.off();
        socket.disconnect();
        crypto.close();
    }
}
