package com.butang.codextop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/** 列表候选和当前聊天共用的展示事实 owner；不授予发送、提问或审批能力。 */
public final class SessionStatus {
    // 沿用原聊天状态的15秒有效期；不随列表周期延长，慢请求也消耗该窗口。
    public static final long FRESHNESS_MS = 15000;

    /** UI 只读快照；只有 validity=current 才能把 state 当作当前事实。时间缺失使用 -1。 */
    public static final class Snapshot {
        public final String state, pendingKind, validity, source, turnId, label;
        public final long eventAtMs, checkedAtMs, observedAtElapsedMs;

        /** 保留来源事实与本机接收时刻，来源时钟不与手机墙钟比较。 */
        private Snapshot(String state, String pendingKind, String validity, String source, String turnId,
                long eventAtMs, long checkedAtMs, long observedAtElapsedMs, String label) {
            this.state = state; this.pendingKind = pendingKind; this.validity = validity;
            this.source = source; this.turnId = turnId; this.label = label;
            this.eventAtMs = eventAtMs; this.checkedAtMs = checkedAtMs;
            this.observedAtElapsedMs = observedAtElapsedMs;
        }

        /** 失效保留上次事实供说明，但禁止将其继续显示为实时运行或完成。 */
        private Snapshot invalid(String validity, String label) {
            return new Snapshot(state, pendingKind, validity, source, turnId, eventAtMs, checkedAtMs, observedAtElapsedMs, label);
        }
    }

    /** 仅由界面线程读写；本机 dialogId 已由账号及电脑身份 owner 隔离。 */
    public static final class Store {
        private final Map<Long, Entry> entries = new HashMap<>();
        private final Map<String, Long> unavailableAt = new HashMap<>();

        /** 保存每条事实的原请求时刻，旧慢响应不能覆盖较新的观察或断连。 */
        private static final class Entry {
            final String machine;
            final long startedAt;
            final Snapshot snapshot;
            /** 合并本机请求顺序与来源归属，不存消息正文。 */
            Entry(String machine, long startedAt, Snapshot snapshot) {
                this.machine = machine; this.startedAt = startedAt; this.snapshot = snapshot;
            }
        }

        /** 消费现有批量 LIST 的一行；磁盘缓存只能作为未更新事实，不能获得新的有效期。 */
        public void candidate(long dialogId, String machine, JsonObject row, long startedAt, long receivedAt, boolean cached) {
            Snapshot snapshot = candidateSnapshot(row, cached ? -1 : receivedAt);
            if (cached) {
                if (!entries.containsKey(dialogId)) entries.put(dialogId,
                        new Entry(machine, -1, "current".equals(snapshot.validity)
                                ? snapshot.invalid("stale", "状态未更新") : snapshot));
                return;
            }
            put(dialogId, machine, startedAt, snapshot);
        }

        /** 消费当前聊天的原 STATUS 响应；与候选走同一缓存和待办判定。 */
        public void observation(long dialogId, String machine, JsonObject response, long startedAt, long receivedAt) {
            put(dialogId, machine, startedAt, responseSnapshot(response, receivedAt));
        }

        /** 合并规则只使用同一手机的请求顺序，不比较跨端墙钟。 */
        private void put(long dialogId, String machine, long startedAt, Snapshot snapshot) {
            Long disconnected = unavailableAt.get(machine);
            Entry previous = entries.get(dialogId);
            if (disconnected != null && startedAt <= disconnected) return;
            if (previous != null && startedAt < previous.startedAt) return;
            entries.put(dialogId, new Entry(machine, startedAt, snapshot));
        }

        /** LIST 失败只失效尚未被更新观察替代的条目，不建立整机断连边界。 */
        public void listFailed(String machine, long startedAt) {
            for (Map.Entry<Long, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                if (entry.machine.equals(machine) && entry.startedAt <= startedAt)
                    item.setValue(new Entry(machine, startedAt,
                            entry.snapshot.invalid("unavailable", "状态暂不可用")));
            }
        }

        /** 真实连接断开使对应电脑失效；该时刻之前发出的回包不能恢复它。 */
        public void unavailable(String machine, long now) {
            unavailableAt.put(machine, Math.max(now, unavailableAt.getOrDefault(machine, -1L)));
            for (Map.Entry<Long, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                if (entry.machine.equals(machine)) item.setValue(new Entry(machine, entry.startedAt,
                        entry.snapshot.invalid("unavailable", "连接暂不可用")));
            }
        }

        /** 有效期从请求开始计时；列表恰好15秒刷新或后台深睡都不能延长旧事实。 */
        public Snapshot get(long dialogId, long now) {
            Entry entry = entries.get(dialogId);
            if (entry == null) return unknown("syncing", "同步中", -1);
            if ("current".equals(entry.snapshot.validity)
                    && (now < entry.startedAt || now - entry.startedAt >= FRESHNESS_MS))
                return entry.snapshot.invalid("stale", "状态已过期");
            return entry.snapshot;
        }

        /** 账号退出清空展示事实和断连边界，不让下一账号继承状态。 */
        public void clear() { entries.clear(); unavailableAt.clear(); }
    }

    /** 兼容原展示入口；候选与 STATUS 都委托同一事实解析，不读取网络或磁盘。 */
    public static String label(JsonObject response) {
        return (response != null && response.has("details") ? candidateSnapshot(response, -1) : responseSnapshot(response, -1)).label;
    }

    /** 创建无可用事实的明确状态，不从活动时间、未读或连接成功猜测生命周期。 */
    private static Snapshot unknown(String validity, String label, long observedAt) {
        return new Snapshot("unknown", "unknown", validity, "", "", -1, -1, observedAt, label);
    }

    /** 安全收窄可选 JSON 对象，格式错误由对应事实入口降级。 */
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** 只接受协议字符串，不把数值或布尔值强制转成有效身份。 */
    private static String text(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    /** 来源时间必须是非负整数；缺失、非整数和类型错误都不能获得有效期。 */
    private static long time(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try { long result = value.getAsBigDecimal().longValueExact(); return result >= 0 ? result : -1; }
        catch (RuntimeException error) { return -1; }
    }

    /** 验证原观察身份并仅按真实请求类型区分提问、审批或两者同时存在。 */
    private static Snapshot observationSnapshot(JsonObject observation, long receivedAt) {
        if (time(observation, "v") != 1) return unknown("unknown", "状态未知", receivedAt);
        String state = text(observation, "state");
        if ("unknown".equals(state)) return "not_observed".equals(text(observation, "reason"))
                ? unknown("syncing", "同步中", receivedAt) : unknown("unknown", "状态未知", receivedAt);
        String source = text(observation, "source"), turnId = text(observation, "turnId");
        if ((!"desktop".equals(source) && !"rollout".equals(source)) || turnId.trim().isEmpty())
            return unknown("unknown", "状态未知", receivedAt);
        String pending = "none";
        if ("needs_input".equals(state)) {
            JsonElement requests = observation.get("requests");
            if (requests == null || !requests.isJsonArray() || requests.getAsJsonArray().size() == 0)
                return unknown("unknown", "状态未知", receivedAt);
            boolean question = false, approval = false;
            for (JsonElement value : requests.getAsJsonArray()) {
                if (!value.isJsonObject()) return unknown("unknown", "状态未知", receivedAt);
                JsonObject request = value.getAsJsonObject();
                if (text(request, "requestId").trim().isEmpty()) return unknown("unknown", "状态未知", receivedAt);
                String kind = text(request, "kind");
                if ("permission_request".equals(kind)) approval = true;
                else if ("user_action_request".equals(kind)) question = true;
                else return unknown("unknown", "状态未知", receivedAt);
            }
            pending = question && approval ? "mixed" : question ? "question" : "approval";
        }
        return known(state, pending, source, turnId, -1, -1, receivedAt);
    }

    /** STATUS 的在线和错误信息优先；runnerActive/activity 都不进入生命周期判断。 */
    private static Snapshot responseSnapshot(JsonObject response, long receivedAt) {
        try {
            if (response == null || !response.get("ok").getAsBoolean()) return unknown("unavailable", "状态暂不可用", receivedAt);
            if (!response.get("machineOnline").getAsBoolean()) return unknown("unavailable", "电脑离线", receivedAt);
            return observationSnapshot(object(response, "observation"), receivedAt);
        } catch (RuntimeException error) { return unknown("unknown", "状态未知", receivedAt); }
    }

    /** 批量候选只接受正式生命周期字段；新待办投影缺失时保留旧协议的笼统待处理。 */
    private static Snapshot candidateSnapshot(JsonObject candidate, long receivedAt) {
        JsonObject details = object(candidate, "details"), fact = object(details, "codexLifecycle");
        long checkedAt = time(fact, "checkedAtMs"), eventAt = time(fact, "eventAtMs");
        if (time(fact, "v") != 1 || checkedAt < 0) return unknown("unknown", "状态未知", receivedAt);
        String state = text(fact, "state");
        if ("unknown".equals(state) || eventAt < 0 || eventAt > checkedAt) return unknown("unknown", "状态未知", receivedAt);
        String pending = "needs_input".equals(state) ? "unknown" : "none";
        String source = "rollout", turnId = "";
        if (details.has("codexObservation")) {
            Snapshot observation = observationSnapshot(object(details, "codexObservation"), receivedAt);
            if (!"current".equals(observation.validity) || !state.equals(observation.state)) return unknown("unknown", "状态未知", receivedAt);
            pending = observation.pendingKind; source = observation.source; turnId = observation.turnId;
        }
        return known(state, pending, source, turnId, eventAt, checkedAt, receivedAt);
    }

    /** 两种来源只在此处将已校验事实映射为可读标签；UI 不反向解析标签。 */
    private static Snapshot known(String state, String pending, String source, String turnId,
            long eventAt, long checkedAt, long receivedAt) {
        String label;
        switch (state) {
            case "running": label = "运行中"; break;
            case "completed": label = "已完成"; break;
            case "failed": label = "执行失败"; break;
            case "cancelled": label = "已取消"; break;
            case "needs_input": label = "question".equals(pending) ? "待你回复" : "approval".equals(pending)
                    ? "待批准" : "mixed".equals(pending) ? "待你回复／批准" : "待处理"; break;
            default: return unknown("unknown", "状态未知", receivedAt);
        }
        return new Snapshot(state, pending, "current", source, turnId, eventAt, checkedAt, receivedAt, label);
    }
}
