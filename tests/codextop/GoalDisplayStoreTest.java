package com.butang.codextop;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 实际原子JSON在临时目录验证目标投影，不读取真实账号、聊天或额度文件。 */
public final class GoalDisplayStoreTest {
    /** 覆盖冷盘往返、隔离、损坏、容量和写失败；展示事实仍由SessionStatus持有。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("codex-goal-cache-");
        try {
            GoalDisplayStore store = new GoalDisplayStore(root.toFile(), "server-a", "account-a");
            GoalDisplayStore.Loaded missing = store.read();
            check(missing.records.isEmpty() && missing.diagnostic == null, "缺失缓存被当成损坏或伪造目标");
            store.save("machine", "remote", "codexHome", "user", "available", "真实目标", "active",
                    null, 1L, 2L, 9L, null);
            GoalDisplayStore.Record restored = new GoalDisplayStore(root.toFile(), "server-a", "account-a")
                    .find("machine", "remote", "codexHome", "user");
            check(restored != null && "真实目标".equals(restored.objective) && "active".equals(restored.status)
                    && restored.tokenBudget == null && Long.valueOf(1).equals(restored.tokensUsed)
                    && Long.valueOf(2).equals(restored.timeUsedSeconds) && restored.updatedAt == 9, "可用目标往返改写原字段");
            store.save("machine", "remote", "codexHome", "user", "none", "", "", null, null, null, -1L, null);
            restored = store.find("machine", "remote", "codexHome", "user");
            check(restored != null && "none".equals(restored.availability) && restored.objective.isEmpty(), "明确无目标没有覆盖旧目标");
            check(store.find("machine", "other", "codexHome", "user") == null
                    && store.find("other", "remote", "codexHome", "user") == null
                    && store.find("machine", "remote", "codexHome", "other") == null
                    && store.find("machine", "remote", "rollout", "user") == null, "电脑、远端或实际来源串用");
            check(new GoalDisplayStore(root.toFile(), "server-b", "account-a").read().records.isEmpty()
                    && new GoalDisplayStore(root.toFile(), "server-a", "account-b").read().records.isEmpty(), "缓存跨服务器或账号");
            Path file = file(root, "server-a", "account-a");
            String valid = Files.readString(file);
            check(!valid.contains("linked") && !valid.contains("lease") && !valid.contains("question")
                    && !valid.contains("permission") && !valid.contains("elapsed") && !valid.contains("\"desktop\""),
                    "投影写入关联会话、生命周期或目标来源装饰");
            String beforeUnknown = Files.readString(file);
            check(!store.save("machine", "remote", "codexHome", "user", "unknown", "不该落地", "active",
                    null, null, null, 1L, null) && Files.readString(file).equals(beforeUnknown), "未知目标覆盖磁盘");
            String beforeFailure = Files.readString(file);
            Path temporary = Path.of(file + ".tmp");
            Files.createDirectories(temporary);
            Files.writeString(temporary.resolve("occupied"), "fixture");
            try {
                store.save("machine", "remote", "codexHome", "user", "available", "新目标", "paused", null, null, null, 4L, null);
                throw new AssertionError("受阻临时文件仍保存成功");
            } catch (java.io.IOException expected) { }
            check(Files.readString(file).equals(beforeFailure), "失败的原子保存破坏原文件");
            Files.delete(temporary.resolve("occupied"));
            Files.delete(temporary);
            Files.writeString(file, "not-json");
            expectEmpty(store);
            Files.writeString(file, "{\"v\":2,\"scope\":\"x\",\"records\":[]}");
            expectEmpty(store);
            var wrong = JsonParser.parseString(valid).getAsJsonObject();
            wrong.addProperty("scope", "other-scope");
            Files.writeString(file, wrong.toString());
            expectEmpty(store);
            store.save("machine", "remote", "codexHome", "user", "available", "恢复", "complete", 3L, null, null, 5L, null);
            check("恢复".equals(store.find("machine", "remote", "codexHome", "user").objective), "新事实不能替换损坏投影");
            String scope = JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("scope").getAsString();
            Files.writeString(file, oversized(scope));
            GoalDisplayStore.Loaded huge = store.read();
            check(huge.records.isEmpty() && GoalDisplayStore.UNAVAILABLE.equals(huge.diagnostic), "超限文件仍被解析成目标");
            for (int i = 0; i < 51; i++) store.save("machine", "remote-" + i, "codexHome", "user", "available",
                    "目标" + i, "active", null, null, null, i, null);
            GoalDisplayStore.Loaded capped = store.read();
            check(capped.records.size() == 50 && store.find("machine", "remote-0", "codexHome", "user") == null
                    && "目标50".equals(store.find("machine", "remote-50", "codexHome", "user").objective), "第51条没有整条淘汰最旧记录");
            Path wideRoot = root.resolve("wide");
            GoalDisplayStore wideStore = new GoalDisplayStore(wideRoot.toFile(), "server-a", "account-a");
            String escaped = "\u0001".repeat(10000);
            for (int i = 0; i < 40; i++) wideStore.save("machine", "wide-" + i, "codexHome", "user", "available",
                    escaped, "active", null, null, null, i, null);
            Path wideFile = file(wideRoot, "server-a", "account-a");
            byte[] written = Files.readAllBytes(wideFile);
            GoalDisplayStore.Loaded wide = wideStore.read();
            check(written.length <= 2 * 1024 * 1024 && wide.records.size() < 40 && !wide.records.isEmpty()
                    && wideStore.find("machine", "wide-0", "codexHome", "user") == null
                    && escaped.equals(wideStore.find("machine", "wide-39", "codexHome", "user").objective), "转义后超限没有整条淘汰");
            for (GoalDisplayStore.Record record : wide.records) check(record.objective.length() == 10000, "超限时截断了目标正文");
            String preserved = Files.readString(wideFile);
            store = wideStore;
            file = wideFile;
            String longObjective = "长".repeat(10001);
            check(!store.save("machine", "legal-long", "codexHome", "user", "available", longObjective, "active",
                    null, null, null, 1L, null) && Files.readString(file).equals(preserved), "超长但合法的当前目标被写入或破坏旧投影");
            Path falseTmp = Path.of(file.toString() + ".tmp");
            check(!store.save("machine", "remote-50", "codexHome", "user", "available", "保留", "active",
                    null, null, null, 1L, () -> false) && Files.readString(file).equals(preserved) && !Files.exists(falseTmp),
                    "守卫直接拒绝时替换了文件或留下临时文件");
            try {
                store.save("machine", "remote-50", "codexHome", "user", "available", "抛出", "active", null, null, null, 1L,
                        () -> { throw new IllegalStateException("guard"); });
                throw new AssertionError("抛出的守卫仍保存成功");
            } catch (IllegalStateException expected) { }
            check(Files.readString(file).equals(preserved) && !Files.exists(falseTmp), "抛出的守卫替换了文件或留下临时文件");
            SessionStatus.Store facts = new SessionStatus.Store();
            facts.observation(1, "machine", "remote", JsonParser.parseString("{\"ok\":true,\"machineOnline\":true,\"observation\":{\"v\":1,\"state\":\"unknown\",\"reason\":\"missing_turn_id\"},\"goal\":{\"availability\":\"available\",\"source\":\"desktop\",\"threadId\":\"remote\",\"objective\":\"已接受\",\"status\":\"active\",\"tokenBudget\":null,\"tokensUsed\":null,\"timeUsedSeconds\":null,\"updatedAt\":1}}").getAsJsonObject(), 100, 101);
            Path boundaryTmp = Path.of(file.toString() + ".tmp");
            boolean replaced = store.save("machine", "boundary", "codexHome", "user", "available", "边界目标", "active",
                    null, null, null, 2L, () -> !Files.exists(boundaryTmp));
            check(!replaced && Files.readString(file).equals(preserved) && !Files.exists(boundaryTmp), "临时文件出现后仍原子替换或没有清掉临时文件");
            check("已接受".equals(facts.get(1, 102).goal.objective) && "current".equals(facts.get(1, 102).goal.validity), "拒绝替换后改写了已接受的内存目标");
            Path limited = root.resolve("limited");
            GoalDisplayStore limitedStore = new GoalDisplayStore(limited.toFile(), "server-a", "account-a");
            limitedStore.save("machine", "remote", "codexHome", "user", "none", "", "", null, null, null, -1L, null);
            Path limitedFile = file(limited, "server-a", "account-a");
            String limitedScope = JsonParser.parseString(Files.readString(limitedFile)).getAsJsonObject().get("scope").getAsString();
            Files.writeString(limitedFile, envelope(limitedScope, fiftyOne()));
            expectEmpty(limitedStore);
            Files.writeString(limitedFile, envelope(limitedScope, one("\"objective\":\"" + "长".repeat(10001) + "\",\"status\":\"active\",\"tokenBudget\":null,\"tokensUsed\":null,\"timeUsedSeconds\":null,\"updatedAt\":1,\"availability\":\"available\"")));
            expectEmpty(limitedStore);
            for (String bad : new String[]{
                    one("\"machine\":1"),
                    one("\"machine\":\" \""),
                    one("\"remote\":\"\""),
                    one("\"sourceKind\":\"\""),
                    one("\"sourceHome\":\" \""),
                    one("\"objective\":1"),
                    one("\"status\":true"),
                    one("\"availability\":1")}) {
                Files.writeString(limitedFile, envelope(limitedScope, bad));
                expectEmpty(limitedStore);
            }
            Files.writeString(limitedFile, "{\"v\":1,\"scope\":1,\"records\":[]}");
            expectEmpty(limitedStore);
            limitedStore.save("machine", "remote", "codexHome", "user", "available", "短目标", "active", null, null, null, 1L, null);
            check("短目标".equals(limitedStore.find("machine", "remote", "codexHome", "user").objective), "合法短目标不能替换被拒绝的超限投影");
            System.out.println("PASS GoalDisplayStoreTest: stale available/none, isolation, corrupt, 51, escaped cap, write failure");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 用正确 scope 包住记录数组。 */
    private static String envelope(String scope, String records) {
        return "{\"v\":1,\"scope\":\"" + scope + "\",\"records\":[" + records + "]}";
    }

    /** 51 条合法 none，读取必须整份拒绝。 */
    private static String fiftyOne() {
        StringBuilder records = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) records.append(',');
            records.append(one("\"remote\":\"r" + i + "\""));
        }
        return records.toString();
    }

    /** 一条 none，调用方可以替换个别字段。后写的同名键覆盖前面的默认值。 */
    private static String one(String override) {
        return "{\"machine\":\"m\",\"remote\":\"r\",\"sourceKind\":\"codexHome\",\"sourceHome\":\"user\",\"availability\":\"none\",\"objective\":\"\",\"status\":\"\",\"tokenBudget\":null,\"tokensUsed\":null,\"timeUsedSeconds\":null,\"updatedAt\":-1,"
                + override + "}";
    }

    /** 正确scope的超限JSON；若实现先解析，会读出正文而不是固定诊断。 */
    private static String oversized(String scope) {
        return "{\"v\":1,\"scope\":\"" + scope + "\",\"records\":[{\"machine\":\"m\",\"remote\":\"r\",\"sourceKind\":\"codexHome\",\"sourceHome\":\"user\",\"availability\":\"available\",\"objective\":\""
                + "x".repeat(2 * 1024 * 1024) + "\",\"status\":\"active\",\"tokenBudget\":null,\"tokensUsed\":null,\"timeUsedSeconds\":null,\"updatedAt\":1}]}";
    }

    /** 损坏、错误版本和错误scope都是空投影加同一诊断。 */
    private static void expectEmpty(GoalDisplayStore store) throws Exception {
        GoalDisplayStore.Loaded loaded = store.read();
        check(loaded.records.isEmpty() && GoalDisplayStore.UNAVAILABLE.equals(loaded.diagnostic), "坏投影没有固定诊断");
    }

    /** 文件名沿用账号散列，目录由调用方隔离。 */
    private static Path file(Path root, String server, String account) {
        return root.resolve(TranscriptStore.digest(server + "\n" + account) + ".json");
    }

    /** 失败直接抛出真实边界。 */
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
