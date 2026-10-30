package com.butang.codextop;

import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.Transport;
import io.socket.parser.Packet;
import io.socket.parser.Parser;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** 有限的收包边界诊断；只包装原工厂与监听，不创建客户端、连接、工作线程或重投。 */
final class SocketAckWireTrace {
    private static final int MAX_RECORDS = 512;
    private static final int PREFIX_LIMIT = 64;
    private static int nextConnection;
    private final int connection;
    private final AtomicInteger records = new AtomicInteger();
    // 代次在原EventThread推进；握手/终态与快照在同一短锁内核对，锁内不读取队列或调用原监听。
    private volatile int generation;
    private WebSocket observedSocket;
    private boolean observationEnded;
    private int latestAckId = -1;
    private final Emitter.Listener transportListener = args -> {
        try {
            if (args.length > 0 && args[0] instanceof io.socket.engineio.client.transports.WebSocket)
                wrapTransport((Transport) args[0]);
        } catch (Throwable ignored) { /* 诊断不能中断原传输创建。 */ }
    };
    private final Emitter.Listener packetListener = args -> {
        try {
            if (args.length > 0 && args[0] instanceof Packet) {
                Packet<?> packet = (Packet<?>) args[0];
                if (packet.type == Parser.ACK || packet.type == Parser.BINARY_ACK) {
                    latestAckId = packet.id;
                    record("packet", generation, 4, packet.type, packet.id, -1, 0);
                }
            }
        } catch (Throwable ignored) { /* 编号观察失败不能跳过原 Socket ACK 查表。 */ }
    };

    /** 开门后才分配进程内序号，区分共用EventThread的多连接，不使用机器或账号身份。 */
    private SocketAckWireTrace() {
        synchronized (SocketAckWireTrace.class) { connection = ++nextConnection; }
    }

    /** 三门全开才登记监听；默认关闭不分配诊断实例、不反射、不读诊断时钟。 */
    static SocketAckWireTrace install(Socket socket) {
        if (!enabled()) return null;
        SocketAckWireTrace trace = null;
        try {
            trace = new SocketAckWireTrace();
            socket.io().on(Manager.EVENT_TRANSPORT, trace.transportListener);
            socket.io().on(Manager.EVENT_PACKET, trace.packetListener);
            return trace;
        } catch (Throwable ignored) {
            if (trace != null) {
                try { socket.io().off(Manager.EVENT_TRANSPORT, trace.transportListener); } catch (Throwable unavailable) { }
                try { socket.io().off(Manager.EVENT_PACKET, trace.packetListener); } catch (Throwable unavailable) { }
            }
            return null;
        }
    }

    /** 与原 SEND ACK 保持相同构建、标签及包名三门；标签关闭后已安装观察也立即静默。 */
    private static boolean enabled() {
        if (!org.telegram.messenger.BuildConfig.DEBUG_VERSION) return false;
        try {
            return android.util.Log.isLoggable("CodexSendAck", android.util.Log.DEBUG)
                    && org.telegram.messenger.ApplicationLoader.applicationContext != null
                    && org.telegram.messenger.ApplicationLoader.applicationContext.getPackageName()
                            .startsWith("com.butang.codextop.nativepreview");
        } catch (Throwable ignored) { return false; }
    }

    /** 原已安装观察在新传输创建时总是失效旧引用；反射包装仍需过门，失败保持原工厂。 */
    private void wrapTransport(Transport transport) {
        final int current;
        synchronized (this) {
            current = ++generation;
            observedSocket = null;
            observationEnded = false;
        }
        latestAckId = -1;
        // 关门期间也可能重连；先退役旧代次，避免重开标签后读取尚未完成关闭握手的旧socket。
        if (!enabled() || records.get() >= MAX_RECORDS) return;
        try {
            Field field = Transport.class.getDeclaredField("webSocketFactory");
            field.setAccessible(true);
            WebSocket.Factory original = (WebSocket.Factory) field.get(transport);
            if (original == null) throw new IllegalStateException();
            WebSocket.Factory forwarding = (request, listener) -> {
                WebSocketListener observed = listener;
                try { observed = forwardingListener(listener, current); } catch (Throwable ignored) { }
                // 原调用的异常原样传播；不能捕获后再创建第二个 WebSocket。
                return original.newWebSocket(request, observed);
            };
            field.set(transport, forwarding);
        } catch (Throwable ignored) { record("unavailable", current, -1, -1, -1, -1, 0); }
    }

    /** 每种回调原参转发一次；只隔离观察代码的异常，不吞掉原监听自己的异常。 */
    private WebSocketListener forwardingListener(WebSocketListener original, int current) {
        return new WebSocketListener() {
            /** 只捕获当前代次的原WebSocket，晚到旧握手不能覆盖新连接；原参仍转发一次。 */
            @Override public void onOpen(WebSocket socket, Response response) {
                observeSocket(socket, current, false);
                original.onOpen(socket, response);
            }
            /** 完整文字帧先记录元数据，再交回原 Engine.IO 排队入口。 */
            @Override public void onMessage(WebSocket socket, String text) {
                raw(text, current);
                original.onMessage(socket, text);
            }
            /** 二进制帧不转换或解析正文，仅记录既有字节长度。 */
            @Override public void onMessage(WebSocket socket, ByteString bytes) {
                try { record("raw_binary", current, -1, -1, -1, bytes == null ? -1 : bytes.size(), 0); }
                catch (Throwable ignored) { }
                original.onMessage(socket, bytes);
            }
            /** 不主动关闭套接字，保持原 listener 的关闭中语义。 */
            @Override public void onClosing(WebSocket socket, int code, String reason) { original.onClosing(socket, code, reason); }
            /** 只观察原传输代次和关闭码；原因原样转发且绝不写入诊断。 */
            @Override public void onClosed(WebSocket socket, int code, String reason) {
                observeSocket(socket, current, true);
                record("closed", current, -1, -1, -1, -1, 0, code);
                original.onClosed(socket, code, reason);
            }
            /** 异常仅映射固定类别；同一异常及Response原样转发，不读取消息或地址。 */
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                observeSocket(socket, current, true);
                String phase = error instanceof java.net.SocketTimeoutException || error instanceof java.util.concurrent.TimeoutException
                        ? "failure_timeout" : error instanceof java.io.IOException ? "failure_io" : "failure_other";
                record(phase, current, -1, -1, -1, -1, 0);
                original.onFailure(socket, error, response);
            }
        };
    }

    /** 捕获需过门；原终态无条件释放同代同引用，避免标签关闭或额度耗尽后滞留旧socket。 */
    private void observeSocket(WebSocket socket, int current, boolean ended) {
        try {
            if (!ended && (!enabled() || records.get() >= MAX_RECORDS)) return;
            synchronized (this) {
                if (current != generation) return;
                if (ended) {
                    if (observedSocket == null || observedSocket == socket) {
                        observedSocket = null;
                        observationEnded = true;
                    }
                } else if (!observationEnded) observedSocket = socket;
            }
        } catch (Throwable ignored) { /* 捕获失败不能改变原握手、关闭或失败回调。 */ }
    }

    /** 原始帧最多查看64个前缀字符；不反序列化、不截取、不记录任何负载。 */
    private void raw(String text, int current) {
        try {
            if (!enabled() || records.get() >= MAX_RECORDS) return;
            int length = text == null ? -1 : text.length();
            int engine = digit(text, 0), socket = engine == 4 ? digit(text, 1) : -1;
            record("raw_text", current, engine, socket, ackId(text), length, 0);
        } catch (Throwable ignored) { /* 即使诊断失败，外层仍转发原始对象。 */ }
    }

    /** 单字符协议类型只接受0到6；坏前缀不触发JSON解析或大包复制。 */
    private static int digit(CharSequence value, int index) {
        if (value == null || index >= value.length()) return -1;
        char c = value.charAt(index);
        return c >= '0' && c <= '6' ? c - '0' : -1;
    }

    /** 只识别ACK的有限协议头，超长命名空间、溢出编号或不完整头均记未知。 */
    static int ackId(CharSequence frame) {
        if (digit(frame, 0) != 4) return -1;
        int type = digit(frame, 1);
        if (type != Parser.ACK && type != Parser.BINARY_ACK) return -1;
        int limit = Math.min(frame.length(), PREFIX_LIMIT), i = 2;
        if (type == Parser.BINARY_ACK) {
            int begin = i;
            while (i < limit && frame.charAt(i) >= '0' && frame.charAt(i) <= '9') i++;
            if (i == begin || i >= limit || frame.charAt(i++) != '-') return -1;
        }
        if (i < limit && frame.charAt(i) == '/') {
            while (i < limit && frame.charAt(i) != ',') i++;
            if (i == limit) return -1;
            i++;
        }
        int begin = i, id = 0;
        while (i < limit) {
            char c = frame.charAt(i);
            if (c < '0' || c > '9') break;
            if (id > (Integer.MAX_VALUE - (c - '0')) / 10) return -1;
            id = id * 10 + c - '0';
            i++;
        }
        return i > begin && i < limit && frame.charAt(i) == '[' ? id : -1;
    }

    /** 派发门通过后捕获代次并采样原队列；返回值只供后续诊断关联，零字节不代表送达。 */
    int emitEntered(int key) {
        if (!enabled() || records.get() >= MAX_RECORDS) return -1;
        int current = generation;
        record("emit_enter", current, -1, -1, -1, -1, key, -1, true);
        return current;
    }

    /** 同一EventThread原emit返回后采样；只观察OkHttp待写字节，不把库的假drain当作写入完成。 */
    void emitReturned(int key, int transport) { record("emit_return", transport, -1, -1, -1, -1, key, -1, true); }

    /** 原请求超时只读当初代次；已重连记stale，不读取新连接队列、不延长原期限。 */
    void timedOut(int key, int transport) { record("terminal_timeout", transport, -1, -1, -1, -1, key, -1, true); }

    /** Manager监听先于原Socket查表；同一事件线程将当前ACK编号关联到原消息摘要。 */
    void ackEntered(int key) { record("ack_enter", generation, 4, -1, latestAckId, -1, key); }

    /** 每连接最多512条固定元数据；关闭或额度用完时不读时钟、不拼串。 */
    private void record(String phase, int transport, int engine, int socket, int id, int length, int key) {
        record(phase, transport, engine, socket, id, length, key, -1);
    }

    /** 关闭码共用原512条额度和门禁；不创建额外观察器或延长期限。 */
    private void record(String phase, int transport, int engine, int socket, int id, int length, int key, int closeCode) {
        record(phase, transport, engine, socket, id, length, key, closeCode, false);
    }

    /** 快照共用原512条额度，先过门再读取原queueSize；异常只记固定unknown，绝不读取请求或连接地址。 */
    private void record(String phase, int transport, int engine, int socket, int id, int length, int key, int closeCode, boolean sampleQueue) {
        try {
            if (!enabled() || records.get() >= MAX_RECORDS || records.getAndIncrement() >= MAX_RECORDS) return;
            String queue = "";
            if (sampleQueue) {
                WebSocket observed = null;
                String state;
                synchronized (this) {
                    if (transport < 0) state = "unavailable";
                    else if (transport != generation) state = "stale";
                    else if (observationEnded) state = "retired";
                    else {
                        observed = observedSocket;
                        state = observed == null ? "unavailable" : "available";
                    }
                }
                long bytes = -1;
                if (observed != null) {
                    try {
                        bytes = observed.queueSize();
                        if (bytes < 0) { bytes = -1; state = "error"; }
                    } catch (Throwable ignored) { state = "error"; }
                }
                queue = " queueBytes=" + bytes + " queueState=" + state;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            android.util.Log.i("CodexSendAck", "send_wire_phase=" + phase + " connection=" + connection + " transport=" + transport
                    + " engineType=" + engine + " socketType=" + socket + " ackId=" + id
                    + " length=" + length + " key=" + key + " closeCode=" + closeCode + queue + " elapsedRealtimeMs=" + now);
        } catch (Throwable ignored) { /* 日志、时钟故障不改变任何连接或原监听结果。 */ }
    }
}
