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
            machineName = metadata.has("host") ? metadata.get("host").getAsString() : "电脑";
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
                if (hasConnected) CodexRuntime.onDesktopConnected(this);
                hasConnected = true;
            });
            socket.once(Socket.EVENT_CONNECT_ERROR, args -> connected.completeExceptionally(new IOException("实时连接失败")));
            socket.on(Socket.EVENT_DISCONNECT, args -> failPending());
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

    private void failPending() {
        for (CompletableFuture<JsonObject> result : pending)
            result.completeExceptionally(new IOException("连接已中断，发送结果需核对"));
    }

    /** 最近列表沿用单页请求，数量由用户设置。 */
    public JsonObject candidates(int limit) throws Exception {
        return candidates(limit, null);
    }

    /** 电脑全部会话按来源游标继续读取，不受首页最近列表上限截断。 */
    public JsonObject candidates(int limit, String cursor) throws Exception {
        JsonObject params = base(null);
        params.addProperty("limit", Math.max(1, Math.min(500, limit)));
        if (cursor != null && !cursor.isEmpty()) params.addProperty("cursor", cursor);
        return rpc("daemon.directSessions.candidates.list", params);
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

    /** 读取关联会话的真实能力和运行状态，界面不能根据消息时间猜测。 */
    public JsonObject status(String remoteSessionId, String sessionId) throws Exception {
        JsonObject params = base(remoteSessionId);
        params.addProperty("sessionId", sessionId);
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
        if (sessionId == null || sessionId.isEmpty() || text == null || text.isEmpty()
                || localId == null || localId.isEmpty()) throw new IllegalArgumentException("发送消息缺少必要信息");
        JsonObject params = new JsonObject();
        params.addProperty("machineId", machineId);
        params.addProperty("sessionId", sessionId);
        params.addProperty("text", text);
        params.addProperty("localId", localId);
        JsonObject meta = new JsonObject();
        meta.addProperty("desktopTextSendProtocol", "native-auto-v1");
        params.add("meta", meta);
        return rpc("daemon.directSessions.send", params);
    }

    /** 构造已支持的用户 Codex 来源，不新增源路径猜测。 */
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

    /** 对现有机器 RPC 做一次认证加密调用；超时不自动重发，避免未来发送重复消息。 */
    private JsonObject rpc(String method, JsonObject params) throws Exception {
        if (closed || !socket.connected()) throw new IOException("电脑连接正在恢复");
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
                result.completeExceptionally(new IOException("电脑连接正在恢复"));
                return;
            }
            socket.emit("rpc-call", new Object[]{call}, new AckWithTimeout(25000) {
            @Override public void onSuccess(Object... args) { try {
                if (result.isDone()) return;
                if (args.length == 0 || !(args[0] instanceof JSONObject)) throw new IOException("电脑响应无效");
                JSONObject envelope = (JSONObject) args[0];
                if (!envelope.optBoolean("ok")) throw new IOException("电脑暂时无法响应，请稍后重试");
                JsonObject response = JsonParser.parseString(crypto.decrypt(envelope.getString("result"))).getAsJsonObject();
                if (!response.has("ok") || !response.get("ok").isJsonPrimitive()
                        || !response.get("ok").getAsJsonPrimitive().isBoolean()
                        || !response.get("ok").getAsBoolean()) throw new IOException("电脑未能完成此请求");
                result.complete(response);
            } catch (Exception error) { result.completeExceptionally(error); } }
            @Override public void onTimeout() { result.completeExceptionally(new java.util.concurrent.TimeoutException()); }
            });
        });
        try { return result.get(25, TimeUnit.SECONDS); }
        finally { pending.remove(result); result.cancel(false); }
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
