package com.butang.codextop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 额度的被动磁盘投影；读取、调度、来源选择仍由原 Runtime 负责。 */
public final class AccountUsageStore {
    private final File file;
    private final String scope;

    /** 复用产品服务和账号散列隔离，文件位于调用方提供的私有非备份目录。 */
    public AccountUsageStore(File root, String server, String account) {
        scope = TranscriptStore.digest(server + "\n" + account);
        file = new File(root, scope + ".json");
    }

    /** 同账号的最后选择及各电脑采集值；内容不代表当前在线或仍未过期。 */
    public static final class Snapshot {
        public final String selectedMachine;
        public final Map<String, AccountUsage> machines;

        /** 快照不可变，调用方不能在异步写盘时改变同一份内容。 */
        private Snapshot(String selectedMachine, Map<String, AccountUsage> machines) {
            this.selectedMachine = selectedMachine;
            this.machines = Collections.unmodifiableMap(new LinkedHashMap<>(machines));
        }
    }

    /** 本地恢复只接受本账号及原格式，缺失为空、损坏上报，不猜来源和数值。 */
    public Snapshot read() throws IOException {
        JsonObject value = DialogStore.readJson(file);
        if (value == null) return new Snapshot(null, Collections.emptyMap());
        try {
            if (!value.get("v").isJsonPrimitive() || !value.get("v").getAsJsonPrimitive().isNumber()
                    || value.get("v").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0
                    || !scope.equals(machine(value.get("scope")))) throw new IOException("额度缓存归属无效");
            String selected = value.has("selectedMachine") && !value.get("selectedMachine").isJsonNull()
                    ? machine(value.get("selectedMachine")) : null;
            Map<String, AccountUsage> machines = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("machines").entrySet()) {
                if (entry.getKey().isEmpty()) throw new IOException("额度电脑身份缺失");
                AccountUsage usage = AccountUsage.parse(entry.getValue().getAsJsonObject());
                if (!usage.available) throw new IOException("额度缓存不是已采集快照");
                machines.put(entry.getKey(), usage);
            }
            return new Snapshot(selected, machines);
        } catch (RuntimeException error) { throw new IOException("额度缓存格式无效", error); }
    }

    /** 只保存明确选择；既有其它电脑快照保持，不读取或新增来源。 */
    public void select(String machine) throws IOException {
        if (machine == null || machine.isEmpty()) throw new IOException("额度电脑身份缺失");
        Snapshot previous = previousForWrite();
        write(new Snapshot(machine, previous.machines));
    }

    /** 成功值替换同电脑；明确账号变化传 null 移除该值，保留其它来源和选择。 */
    public void save(String machine, AccountUsage usage) throws IOException {
        if (machine == null || machine.isEmpty()) throw new IOException("额度电脑身份缺失");
        if (usage != null && !usage.available) throw new IOException("额度缓存不是已采集快照");
        Snapshot previous = previousForWrite();
        Map<String, AccountUsage> machines = new LinkedHashMap<>(previous.machines);
        if (usage == null) machines.remove(machine); else machines.put(machine, usage);
        write(new Snapshot(previous.selectedMachine, machines));
    }

    /** 损坏旧投影不能阻止下一次真实采集恢复；原子替换失败仍保留原文件。 */
    private Snapshot previousForWrite() {
        try { return read(); }
        catch (IOException error) { return new Snapshot(null, Collections.emptyMap()); }
    }

    /** 复用现有临时文件原子替换，不与登录凭据文件或聊天正文混存。 */
    private void write(Snapshot snapshot) throws IOException {
        JsonObject value = new JsonObject(), machines = new JsonObject();
        value.addProperty("v", 1); value.addProperty("scope", scope);
        value.addProperty("selectedMachine", snapshot.selectedMachine);
        for (Map.Entry<String, AccountUsage> entry : snapshot.machines.entrySet())
            machines.add(entry.getKey(), entry.getValue().cacheJson());
        value.add("machines", machines);
        DialogStore.writeJson(file, value);
    }

    /** 选择身份必须是原字符串，数值或空字段不能变成电脑标识。 */
    private static String machine(JsonElement value) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty())
            throw new IOException("额度电脑身份无效");
        return value.getAsString();
    }
}
