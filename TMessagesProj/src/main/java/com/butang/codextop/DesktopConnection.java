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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 账号下单台电脑的现有 Socket.IO RPC 连接，所有阻塞方法仅在后台线程调用。 */
public final class DesktopConnection implements AutoCloseable {
    public final String machineId;
    public final String machineName;
    private final Socket socket;
    private final WireCrypto crypto;
    private final java.util.Set<CompletableFuture<JsonObject>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
            CompletableFuture<Void> connected = new CompletableFuture<>();
            socket.once(Socket.EVENT_CONNECT, args -> connected.complete(null));
            // 重连成功才通知状态所有者，断线或握手失败不能伪造可用状态。
            socket.on(Socket.EVENT_CONNECT, args -> {
                if (closed) return;
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

    /** 原断连入口同时清容量与在途请求，不自动重发任何操作。 */
    private void failPending() {
        clearAttachmentUploadLimit();
        for (CompletableFuture<JsonObject> result : pending)
            result.completeExceptionally(new IOException("连接已中断，发送结果需核对"));
    }

    /** 当前传输的只读摘要；不能作为电脑在线、任务状态或执行权限的证明。 */
    public boolean isConnected() { return !closed && socket.connected(); }

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
        JsonObject params = base(remoteSessionId);
        params.addProperty("openExisting", true);
        return rpc("daemon.directSessions.link.ensure", params);
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
        return rpc("daemon.directSessions.send", params);
    }

    /** 原分块传输也走此电脑已有的认证加密RPC，不创建另一条连接或自动重试。 */
    public JsonObject transfer(String method, JsonObject params) throws Exception {
        if (!method.matches("daemon\\.bulkTransfer\\.(?:upload|download)\\.(?:init|chunk|finalize|abort)"))
            throw new IllegalArgumentException("未知附件传输方法");
        return rpc(method, params);
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

    /** 构造已支持的用户 Codex 来源，不新增源路径猜测。 */
    private JsonObject base(String remoteSessionId) {
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId);
        params.addProperty("providerId", "codex");
        JsonObject source = new JsonObject();
        source.addProperty("kind", "codexHome"); source.addProperty("home", "user");
        params.add("source", source);
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

    /** 沿原认证加密RPC调用一次；合法bulk拒绝交专用传输处理，超时不自动重发。 */
    private JsonObject rpc(String method, JsonObject params) throws Exception {
        if (closed || !socket.connected()) throw new RpcNotDispatchedException();
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
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        pending.add(result);
        EventThread.exec(() -> {
            // 检查与发出使用同一 Socket.IO 事件线程，断线时不进入离线发送缓冲。
            if (closed || !socket.connected() || result.isDone()) {
                result.completeExceptionally(new RpcNotDispatchedException());
                return;
            }
            socket.emit("rpc-call", new Object[]{call}, new AckWithTimeout(25000) {
            @Override public void onSuccess(Object... args) { try {
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
                if (!validSuccess || !response.get(success).getAsBoolean()) {
                    // 只交回既有失败封套，通用RPC不拼接或抛出服务器错误正文。
                    if (bulk && validSuccess && response.has("error") && response.get("error").isJsonPrimitive()
                            && response.get("error").getAsJsonPrimitive().isString()) {
                        result.complete(response);
                        return;
                    }
                    if ("daemon.directSessions.send".equals(method) && response.has("errorCode")
                            && response.get("errorCode").isJsonPrimitive()
                            && response.get("errorCode").getAsJsonPrimitive().isString()) {
                        String reason = response.get("errorCode").getAsString();
                        if (!reason.isEmpty() && !"delivery_outcome_unknown".equals(reason))
                            throw new SendRejectedException(reason);
                    }
                    throw new IOException("电脑未能完成此请求");
                }
                result.complete(response);
            } catch (Exception error) { result.completeExceptionally(error); } }
            @Override public void onTimeout() { result.completeExceptionally(new java.util.concurrent.TimeoutException()); }
            });
        });
        try { return result.get(25, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException error) {
            // 只解包明确未emit；其他传输错误保持原未知语义，不能据此重新发送。
            if (error.getCause() instanceof RpcNotDispatchedException)
                throw (RpcNotDispatchedException) error.getCause();
            if (error.getCause() instanceof SendRejectedException)
                throw (SendRejectedException) error.getCause();
            throw error;
        } finally { pending.remove(result); result.cancel(false); }
    }

    /** 页面释放时关闭连接并擦除机器密钥。 */
    @Override public void close() {
        closed = true;
        unwatchNetwork();
        failPending();
        socket.off();
        socket.disconnect();
        crypto.close();
    }
}
