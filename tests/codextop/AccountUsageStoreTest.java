package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 实际被动存储在合成私有目录验证冷恢复、来源隔离和原子失败，不读取真实账号。 */
public final class AccountUsageStoreTest {
    /** 新实例等同进程内对象丢失，文件本身必须保留实际采集字段和明确选择。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("codex-usage-store-");
        try {
            AccountUsageStore store = new AccountUsageStore(root.toFile(), "server-a", "account-a");
            check(store.read().machines.isEmpty() && store.read().selectedMachine == null, "missing cache invented values");
            AccountUsage usage = AccountUsage.parse(JsonParser.parseString("{\"status\":\"available\",\"source\":{\"kind\":\"codexHome\",\"home\":\"user\"},\"account\":{\"accountLabel\":\"synthetic account\",\"notStored\":\"extra\"},\"fetchedAtMs\":100,\"staleAtMs\":200,\"meters\":[{\"label\":\"hour\",\"remainingPct\":12.5,\"windowDurationMs\":3600000,\"resetAtMs\":300,\"status\":\"estimated\"},{\"label\":\"missing\",\"remainingPct\":null}]}" ).getAsJsonObject());
            store.save("machine-a", usage); store.select("machine-a"); store.save("machine-b", usage);
            AccountUsageStore.Snapshot cold = new AccountUsageStore(root.toFile(), "server-a", "account-a").read();
            AccountUsage restored = cold.machines.get("machine-a");
            check(cold.machines.size() == 2 && "machine-a".equals(cold.selectedMachine), "cold restore lost source choice or machine snapshots");
            check(restored.accountLabel.equals(usage.accountLabel) && restored.fetchedAtMs == 100 && restored.staleAtMs == 200
                    && restored.isStale(200) && restored.meters.get(0).estimated && restored.meters.get(0).remainingPercent == 12.5
                    && restored.meters.get(0).windowDurationMs == 3600000 && restored.meters.get(0).resetsAtMs == 300
                    && restored.meters.get(1).remainingPercent == null && restored.meters.get(1).resetsAtMs == null,
                    "roundtrip changed source timing, estimated or missing fields");
            check(new AccountUsageStore(root.toFile(), "server-b", "account-a").read().machines.isEmpty()
                    && new AccountUsageStore(root.toFile(), "server-a", "account-b").read().machines.isEmpty(), "cache crossed server/account");
            Path file = root.resolve(TranscriptStore.digest("server-a\naccount-a") + ".json");
            String valid = Files.readString(file);check(!valid.contains("notStored") && !file.getFileName().toString().contains("account-a"), "raw extras or plain account leaked into cache naming");
            store.save("machine-a", null);cold = store.read();
            check(!cold.machines.containsKey("machine-a") && cold.machines.containsKey("machine-b") && "machine-a".equals(cold.selectedMachine), "account change affected other machine or invented selection");
            String beforeFailure = Files.readString(file);
            AccountUsage unavailable = AccountUsage.parse(JsonParser.parseString("{\"status\":\"unavailable\",\"reason\":\"read_failed\"}").getAsJsonObject());
            try { store.save("machine-b", unavailable); throw new AssertionError("unavailable stored as sampled quota"); } catch (IOException expected) { }
            check(Files.readString(file).equals(beforeFailure), "invalid value replaced previous valid file");
            // 临时文件不能写入时，原子保存不能先删除上一份有效文件。
            Path temporary = Path.of(file + ".tmp"); Files.createDirectories(temporary);Files.writeString(temporary.resolve("occupied"), "fixture");
            try { store.save("machine-b", usage); throw new AssertionError("blocked temp unexpectedly saved"); } catch (IOException expected) { }
            check(Files.readString(file).equals(beforeFailure), "failed atomic write removed original cache");
            Files.delete(temporary.resolve("occupied"));Files.delete(temporary);
            JsonObject corrupt = JsonParser.parseString(valid).getAsJsonObject();corrupt.addProperty("scope", "wrong-scope");Files.writeString(file, corrupt.toString());
            expectInvalid(store);corrupt = JsonParser.parseString(valid).getAsJsonObject();corrupt.addProperty("v", "1");Files.writeString(file, corrupt.toString());expectInvalid(store);
            Files.writeString(file, "not-json");expectInvalid(store);
            store.save("machine-a", usage);check(store.read().machines.get("machine-a").available, "new real sample could not replace malformed cache");
            System.out.println("PASS AccountUsageStoreTest: actual atomic store cold restore, choice, source/account isolation, missing/estimated, corrupt and failed write");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }
    /** 损坏本地投影只能报失败，不能伪造可用或认领其他账号缓存。 */
    private static void expectInvalid(AccountUsageStore store) throws Exception {
        try { store.read(); throw new AssertionError("malformed cache accepted"); } catch (IOException expected) { }
    }
    /** 测试失败直接抛出真实边界。 */
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
