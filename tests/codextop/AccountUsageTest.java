package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

public final class AccountUsageTest {
    /** 独立JVM验证实际窗口映射，不依赖安卓UI或真实账号。 */
    public static void main(String[] args) throws Exception {
        AccountUsage usage = AccountUsage.parse(json("{\"status\":\"available\",\"source\":{\"kind\":\"codexHome\",\"home\":\"user\"},\"account\":{\"accountLabel\":\"source@example.test\"},\"fetchedAtMs\":100,\"staleAtMs\":200,\"meters\":[{\"label\":\"Codex\",\"remainingPct\":12.5,\"windowDurationMs\":604800000,\"resetsAt\":300,\"status\":\"ok\"},{\"label\":\"Other\",\"remainingPct\":null,\"resetsAt\":null,\"status\":\"unavailable\"}]}"));
        check(usage.available && usage.meters.size() == 2, "Keep every source window");
        check(usage.meters.get(0).remainingPercent == 12.5 && usage.meters.get(0).windowDurationMs == 604800000L, "Read exact source values");
        check(usage.meters.get(1).remainingPercent == null && usage.meters.get(1).resetsAtMs == null, "Missing is not zero");
        check(!usage.isStale(199) && usage.isStale(200), "Honor source freshness boundary");
        check("source@example.test".equals(usage.accountLabel), "Use source account label only");
        AccountUsage absent = AccountUsage.parse(json("{\"status\":\"unavailable\",\"reason\":\"account_changed\"}"));
        check(!absent.available && absent.meters.isEmpty() && absent.accountLabel == null, "Unavailable never carries previous balance");
        rejected("{\"status\":\"available\",\"source\":{\"kind\":\"codexHome\",\"home\":\"connectedService\"},\"fetchedAtMs\":100,\"staleAtMs\":200,\"meters\":[]}");
        rejected("{\"status\":\"available\",\"source\":{\"kind\":\"codexHome\",\"home\":\"user\"},\"fetchedAtMs\":100,\"staleAtMs\":200,\"meters\":[{\"label\":\"Codex\",\"remainingPct\":101}]}");
        rejected("{\"status\":\"available\",\"source\":{\"kind\":\"codexHome\",\"home\":\"user\"},\"fetchedAtMs\":100,\"staleAtMs\":null,\"meters\":[]}");
        System.out.println("AccountUsageTest passed");
    }
    /** 合成JSON只包含展示字段。 */
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    /** 无效响应不能变成成功额度。 */
    private static void rejected(String text) throws Exception {
        try { AccountUsage.parse(json(text)); throw new AssertionError("Invalid result was accepted"); }
        catch (IOException expected) { }
    }
    /** 失败直接终止测试。 */
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
