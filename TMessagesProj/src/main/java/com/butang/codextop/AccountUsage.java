package com.butang.codextop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 所选电脑当前用户的Codex额度快照；缺失数值保持缺失，不取历史会话补齐。 */
public final class AccountUsage {
    public final boolean available;
    public final String reason;
    public final String accountLabel;
    public final long fetchedAtMs;
    public final long staleAtMs;
    public final List<Meter> meters;

    public static final class Meter {
        public final String label;
        public final Double remainingPercent;
        public final Long windowDurationMs;
        public final Long resetsAtMs;
        public final boolean estimated;

        /** 按提供方投影读取实际窗口；没有返回的周期或重置时间保持null。 */
        private Meter(JsonObject value) throws IOException {
            label = text(value, "label");
            if (label == null) throw new IOException("额度窗口缺少名称");
            Double remaining = number(value, "remainingPct");
            if (remaining != null && (remaining < 0 || remaining > 100)) throw new IOException("额度比例无效");
            remainingPercent = "unavailable".equals(text(value, "status")) ? null : remaining;
            windowDurationMs = optionalTime(value, "windowDurationMs");
            Long reset = optionalTime(value, "resetAtMs");
            resetsAtMs = reset == null ? optionalTime(value, "resetsAt") : reset;
            estimated = "estimated".equals(text(value, "status"));
        }
    }

    /** 只保留本次读取内容，错误结果不携带此前账号的数字。 */
    private AccountUsage(boolean available, String reason, String accountLabel, long fetchedAtMs, long staleAtMs, List<Meter> meters) {
        this.available = available;
        this.reason = reason;
        this.accountLabel = accountLabel;
        this.fetchedAtMs = fetchedAtMs;
        this.staleAtMs = staleAtMs;
        this.meters = Collections.unmodifiableList(meters);
    }

    /** 机器RPC的新能力明确区分可用与不可用，不把旧服务缺字段解释为零余额。 */
    public static AccountUsage parse(JsonObject value) throws IOException {
        try {
            if (value == null) throw new IOException("电脑尚未提供额度信息");
            String status = text(value, "status");
            if ("unavailable".equals(status)) {
                return new AccountUsage(false, text(value, "reason"), null, 0, 0, new ArrayList<>());
            }
            if (!"available".equals(status)) throw new IOException("电脑额度响应无效");
            JsonObject source = value.getAsJsonObject("source");
            if (source == null || !"codexHome".equals(text(source, "kind")) || !"user".equals(text(source, "home"))) {
                throw new IOException("额度来源与当前电脑不一致");
            }
            Long fetched = optionalTime(value, "fetchedAtMs"), stale = optionalTime(value, "staleAtMs");
            if (fetched == null || stale == null || stale < fetched) throw new IOException("额度采集时间缺失");
            ArrayList<Meter> meters = new ArrayList<>();
            for (JsonElement entry : value.getAsJsonArray("meters")) meters.add(new Meter(entry.getAsJsonObject()));
            if (meters.isEmpty()) throw new IOException("电脑未返回额度窗口");
            String label = value.has("account") && value.get("account").isJsonObject()
                    ? text(value.getAsJsonObject("account"), "accountLabel") : null;
            return new AccountUsage(true, null, label, fetched, stale, meters);
        } catch (IllegalStateException | ClassCastException | NullPointerException | NumberFormatException invalid) {
            throw new IOException("电脑额度响应无效");
        }
    }

    /** 使用来源标注的有效期，不通过打开页面延长快照时效。 */
    public boolean isStale(long now) { return !available || now >= staleAtMs; }

    /** 本地只保存已验证采集值的显示字段，恢复仍经过原协议校验，不保存原始响应。 */
    JsonObject cacheJson() throws IOException {
        if (!available) throw new IOException("不可用额度不能保存为采集快照");
        JsonObject value = new JsonObject(), source = new JsonObject(), account = new JsonObject();
        value.addProperty("status", "available");
        // parse 只接受这一已有来源；缓存不扩展其他账号或托管来源。
        source.addProperty("kind", "codexHome"); source.addProperty("home", "user");
        value.add("source", source);
        if (accountLabel != null) account.addProperty("accountLabel", accountLabel);
        value.add("account", account);
        value.addProperty("fetchedAtMs", fetchedAtMs); value.addProperty("staleAtMs", staleAtMs);
        com.google.gson.JsonArray windows = new com.google.gson.JsonArray();
        for (Meter meter : meters) {
            JsonObject window = new JsonObject();
            window.addProperty("label", meter.label);
            window.addProperty("remainingPct", meter.remainingPercent);
            window.addProperty("windowDurationMs", meter.windowDurationMs);
            window.addProperty("resetAtMs", meter.resetsAtMs);
            window.addProperty("status", meter.estimated ? "estimated" : "ok");
            windows.add(window);
        }
        value.add("meters", windows);
        return value;
    }

    /** 不向用户展示原始提供方异常或本地路径。 */
    public String unavailableMessage() {
        if ("account_changed".equals(reason)) return "电脑 Codex 账号已变化，请刷新";
        if ("account_unavailable".equals(reason)) return "电脑 Codex 账号暂不可用";
        if ("unsupported_source".equals(reason)) return "此 Codex 来源暂不支持读取额度";
        if ("source_unavailable".equals(reason)) return "电脑 Codex 来源暂不可用";
        if ("quota_unavailable".equals(reason)) return "当前账号未返回额度信息";
        return "暂时无法读取额度，请检查电脑连接后重试";
    }

    /** 可选文字只接受实际字符串，不将对象或空值展示成标签。 */
    private static String text(JsonObject value, String key) {
        JsonElement entry = value.get(key);
        return entry != null && entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()
                && !entry.getAsString().trim().isEmpty() ? entry.getAsString().trim() : null;
    }

    /** 保留缺失数值并拒绝非有限数字。 */
    private static Double number(JsonObject value, String key) throws IOException {
        JsonElement entry = value.get(key);
        if (entry == null || entry.isJsonNull()) return null;
        if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) throw new IOException("额度数值无效");
        double number = entry.getAsDouble();
        if (!Double.isFinite(number)) throw new IOException("额度数值无效");
        return number;
    }

    /** 时间字段沿机器协议使用毫秒，缺失时不推算。 */
    private static Long optionalTime(JsonObject value, String key) throws IOException {
        Double number = number(value, key);
        if (number == null) return null;
        if (number < 0 || number != Math.rint(number) || number > Long.MAX_VALUE) throw new IOException("额度时间无效");
        return number.longValue();
    }
}
