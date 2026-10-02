package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 目标展示的被动磁盘投影。事实仍在 SessionStatus，这里只原子保存可回放的目标本身。 */
public final class GoalDisplayStore {
    /** 只约束这份本地投影，不改变协议或在线目标。 */
    public static final int MAX_RECORDS = 50;
    public static final int MAX_OBJECTIVE_UTF16 = 10000;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** 缺失不算错误；损坏、超限、版本或账号不符都用这一句，不弹窗。 */
    public static final String UNAVAILABLE = "目标缓存不可用";
    private final File file;
    private final String scope;

    /** 目录由调用方隔开，不能和额度 scope.json 放在同一文件夹。 */
    public GoalDisplayStore(File root, String server, String account) {
        scope = TranscriptStore.digest(server + "\n" + account);
        file = new File(root, scope + ".json");
    }

    /** 一条可回放目标，不含生命周期、关联会话、权限或有效期。 */
    public static final class Record {
        public final String machine, remote, sourceKind, sourceHome, availability, objective, status;
        public final Long tokenBudget, tokensUsed, timeUsedSeconds;
        public final long updatedAt;

        private Record(String machine, String remote, String sourceKind, String sourceHome, String availability,
                String objective, String status, Long tokenBudget, Long tokensUsed, Long timeUsedSeconds, long updatedAt) {
            this.machine = machine; this.remote = remote; this.sourceKind = sourceKind; this.sourceHome = sourceHome;
            this.availability = availability; this.objective = objective; this.status = status;
            this.tokenBudget = tokenBudget; this.tokensUsed = tokensUsed; this.timeUsedSeconds = timeUsedSeconds;
            this.updatedAt = updatedAt;
        }
    }

    /** 读取结果。diagnostic 为空表示缺失或可读；有诊断时记录一定为空。 */
    public static final class Loaded {
        public final List<Record> records;
        public final String diagnostic;

        private Loaded(List<Record> records, String diagnostic) {
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.diagnostic = diagnostic;
        }
    }

    /** 先看文件大小，超限不解析。坏文件返回空投影和固定诊断。 */
    public Loaded read() {
        if (!file.exists()) return new Loaded(List.of(), null);
        if (file.length() > MAX_BYTES) return new Loaded(List.of(), UNAVAILABLE);
        try {
            JsonObject value = DialogStore.readJson(file);
            if (value == null) return new Loaded(List.of(), null);
            return parse(value);
        } catch (IOException | RuntimeException error) {
            return new Loaded(List.of(), UNAVAILABLE);
        }
    }

    /** 按电脑、远端和实际来源查找。不把 goal.source 的 desktop 当作来源。 */
    public Record find(String machine, String remote, String sourceKind, String sourceHome) {
        for (Record record : read().records)
            if (machine.equals(record.machine) && remote.equals(record.remote)
                    && sourceKind.equals(record.sourceKind) && sourceHome.equals(record.sourceHome))
                return record;
        return null;
    }

    /**
     * 写入一条当前 available 或 none。超长正文和未知状态不改原文件。
     * 身份守卫交给原来的 writeJson：写临时文件前一次，临时文件写完后、原子替换前再一次。
     */
    public boolean save(String machine, String remote, String sourceKind, String sourceHome, String availability,
            String objective, String status, Long tokenBudget, Long tokensUsed, Long timeUsedSeconds, long updatedAt,
            BooleanSupplier stillCurrent) throws IOException {
        if (!acceptable(availability, objective, status, tokenBudget, tokensUsed, timeUsedSeconds, updatedAt)) return false;
        if ("available".equals(availability) && objective.length() > MAX_OBJECTIVE_UTF16) return false;
        Loaded loaded = read();
        List<Record> records = new ArrayList<>(loaded.diagnostic == null ? loaded.records : List.of());
        records.removeIf(record -> machine.equals(record.machine) && remote.equals(record.remote)
                && sourceKind.equals(record.sourceKind) && sourceHome.equals(record.sourceHome));
        records.add(new Record(machine, remote, sourceKind, sourceHome, availability, objective, status,
                tokenBudget, tokensUsed, timeUsedSeconds, updatedAt));
        while (records.size() > MAX_RECORDS) records.remove(0);
        JsonObject body = document(records);
        byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
        while (encoded.length > MAX_BYTES && records.size() > 1) {
            records.remove(0);
            body = document(records);
            encoded = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (encoded.length > MAX_BYTES) return false;
        return DialogStore.writeJson(file, body, stillCurrent);
    }

    /** 只接受能原样恢复的 available 与空 none，拒绝未知和半截字段。 */
    private static boolean acceptable(String availability, String objective, String status, Long tokenBudget,
            Long tokensUsed, Long timeUsedSeconds, long updatedAt) {
        if (machineMissing(objective) || machineMissing(status)) return false;
        try {
            if ("none".equals(availability))
                return objective.isEmpty() && status.isEmpty() && tokenBudget == null && tokensUsed == null
                        && timeUsedSeconds == null && updatedAt == -1;
            return "available".equals(availability) && !objective.isEmpty() && knownStatus(status)
                    && inRange(tokenBudget) && inRange(tokensUsed) && inRange(timeUsedSeconds)
                    && updatedAt >= 0 && updatedAt <= 9007199254740991L;
        } catch (RuntimeException error) { return false; }
    }

    /** 空身份不能落成一条记录。来源和电脑由调用方另行传入。 */
    private static boolean machineMissing(String value) { return value == null; }

    /** 协议里的空数值可以保留，超出安全整数则不写。 */
    private static boolean inRange(Long value) {
        return value == null || value >= 0 && value <= 9007199254740991L;
    }

    /** 与展示文案同一组状态；这里只决定能否落盘。 */
    private static boolean knownStatus(String status) {
        switch (status) {
            case "active": case "paused": case "blocked":
            case "usageLimited": case "budgetLimited": case "complete": return true;
            default: return false;
        }
    }

    /** 版本、账号范围和记录形状不对时整份作废，不挑出半条。 */
    private Loaded parse(JsonObject value) {
        try {
            JsonElement scopeValue = value.get("scope");
            if (!value.keySet().equals(java.util.Set.of("v", "scope", "records"))
                    || !value.get("v").isJsonPrimitive() || !value.get("v").getAsJsonPrimitive().isNumber()
                    || value.get("v").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0
                    || scopeValue == null || !scopeValue.isJsonPrimitive() || !scopeValue.getAsJsonPrimitive().isString()
                    || !scope.equals(scopeValue.getAsString()) || !value.get("records").isJsonArray())
                return new Loaded(List.of(), UNAVAILABLE);
            JsonArray rows = value.getAsJsonArray("records");
            if (rows.size() > MAX_RECORDS) return new Loaded(List.of(), UNAVAILABLE);
            List<Record> records = new ArrayList<>();
            for (JsonElement element : rows) records.add(record(element.getAsJsonObject()));
            return new Loaded(records, null);
        } catch (RuntimeException error) { return new Loaded(List.of(), UNAVAILABLE); }
    }

    /** 记录只含目标和实际来源，多一个字段就整份无效。 */
    private static Record record(JsonObject value) {
        if (!value.keySet().equals(java.util.Set.of("machine", "remote", "sourceKind", "sourceHome", "availability",
                "objective", "status", "tokenBudget", "tokensUsed", "timeUsedSeconds", "updatedAt")))
            throw new IllegalArgumentException("shape");
        String machine = identity(value, "machine");
        String remote = identity(value, "remote");
        String sourceKind = identity(value, "sourceKind");
        String sourceHome = identity(value, "sourceHome");
        String availability = text(value, "availability");
        String objective = text(value, "objective");
        String status = text(value, "status");
        if (objective.length() > MAX_OBJECTIVE_UTF16) throw new IllegalArgumentException("objective");
        Long budget = number(value, "tokenBudget");
        Long tokens = number(value, "tokensUsed");
        Long seconds = number(value, "timeUsedSeconds");
        long updated = updated(value.get("updatedAt"));
        if (!acceptable(availability, objective, status, budget, tokens, seconds, updated))
            throw new IllegalArgumentException("goal");
        return new Record(machine, remote, sourceKind, sourceHome, availability, objective, status, budget, tokens, seconds, updated);
    }

    /** 身份和来源必须是非空白字符串，数字或布尔值不能被转成身份。 */
    private static String identity(JsonObject object, String key) {
        String value = text(object, key);
        if (value.trim().isEmpty()) throw new IllegalArgumentException("blank");
        return value;
    }

    /** 目标字段只接受 JSON 字符串。 */
    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("type");
        return value.getAsString();
    }

    /** null 保留未知用量；其他类型和越界都拒绝。 */
    private static Long number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("type");
        long number = value.getAsBigDecimal().longValueExact();
        if (number < 0 || number > 9007199254740991L) throw new IllegalArgumentException("range");
        return number;
    }

    /** none 用 -1，可用目标只用非负安全整数。 */
    private static long updated(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("updated");
        return value.getAsBigDecimal().longValueExact();
    }

    /** 按当前顺序整份序列化，淘汰时丢掉最旧的整条。 */
    private JsonObject document(List<Record> records) {
        JsonArray array = new JsonArray();
        for (Record record : records) {
            JsonObject row = new JsonObject();
            row.addProperty("machine", record.machine);
            row.addProperty("remote", record.remote);
            row.addProperty("sourceKind", record.sourceKind);
            row.addProperty("sourceHome", record.sourceHome);
            row.addProperty("availability", record.availability);
            row.addProperty("objective", record.objective);
            row.addProperty("status", record.status);
            row.add("tokenBudget", json(record.tokenBudget));
            row.add("tokensUsed", json(record.tokensUsed));
            row.add("timeUsedSeconds", json(record.timeUsedSeconds));
            row.addProperty("updatedAt", record.updatedAt);
            array.add(row);
        }
        JsonObject body = new JsonObject();
        body.addProperty("v", 1);
        body.addProperty("scope", scope);
        body.add("records", array);
        return body;
    }

    /** 显式 null，避免省略字段后被当成损坏。 */
    private static JsonElement json(Long value) { return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value); }
}
