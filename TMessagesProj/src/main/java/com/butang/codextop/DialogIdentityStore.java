package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** 独立于最近列表保存本地编号，列表裁剪不能使旧草稿失去归属。仅后台调用。 */
public final class DialogIdentityStore {
    private static final long LEGACY_BASE = 1000000000000L;
    private static final long EXTRA_BASE = LEGACY_BASE + 0x100000000L;
    private final File file;
    private final Map<String, Long> ids = new HashMap<>();
    private String legacyMachine;
    private long next = EXTRA_BASE;

    /** 按服务和账号恢复分配表；损坏时拒绝重新分配，避免旧草稿绑定到其他对话。 */
    public DialogIdentityStore(File root, String server, String account) throws IOException {
        file = new File(root, TranscriptStore.digest(server + "\n" + account) + ".json");
        if (!file.exists()) return;
        try {
            JsonObject saved = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            legacyMachine = saved.get("legacyMachine").getAsString();
            next = saved.get("next").getAsLong();
            if (legacyMachine.isEmpty() || next < EXTRA_BASE) throw new IOException("本地编号表无效");
            java.util.HashSet<Long> used = new java.util.HashSet<>();
            for (com.google.gson.JsonElement value : saved.getAsJsonArray("rows")) {
                JsonObject row = value.getAsJsonObject();
                String key = row.get("key").getAsString();
                long id = row.get("id").getAsLong();
                if (key.isEmpty() || id < LEGACY_BASE || id >= next || ids.containsKey(key) || !used.add(id))
                    throw new IOException("本地编号表冲突");
                ids.put(key, id);
            }
        } catch (RuntimeException error) { throw new IOException("本地编号表无法读取", error); }
    }

    /** 首台旧电脑保留哈希编号；其他电脑及碰撞分配新编号，落盘成功才对外返回。 */
    public synchronized long bind(String machine, String remote) throws IOException {
        if (machine == null || machine.isEmpty() || remote == null || remote.isEmpty())
            throw new IOException("缺少对话归属");
        // 长度前缀避免名称中的分隔符造成身份混淆。
        String key = machine.length() + ":" + machine + remote;
        Long existing = ids.get(key);
        if (existing != null) return existing;
        String previousMachine = legacyMachine;
        long previousNext = next;
        if (legacyMachine == null) legacyMachine = machine;
        long id = LEGACY_BASE + Integer.toUnsignedLong(remote.hashCode());
        if (!legacyMachine.equals(machine) || ids.containsValue(id)) id = next++;
        ids.put(key, id);
        try { write(); }
        catch (IOException error) {
            ids.remove(key);
            legacyMachine = previousMachine;
            next = previousNext;
            throw error;
        }
        return id;
    }

    /** 原子替换编号表；失败时保持旧文件与内存映射一致。 */
    private void write() throws IOException {
        JsonObject saved = new JsonObject();
        saved.addProperty("legacyMachine", legacyMachine);
        saved.addProperty("next", next);
        JsonArray rows = new JsonArray();
        for (Map.Entry<String, Long> entry : ids.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("key", entry.getKey());
            row.addProperty("id", entry.getValue());
            rows.add(row);
        }
        saved.add("rows", rows);
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = new File(file.getPath() + ".tmp");
        try {
            Files.write(temporary.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
