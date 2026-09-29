package com.butang.codextop;
import com.google.gson.JsonParser;
public final class SessionStatusTest {
    public static void main(String[] args) {
        check("{\"ok\":true,\"machineOnline\":true,\"runnerActive\":true,\"activity\":\"running\"}", "状态未知");
        check("{\"ok\":true,\"machineOnline\":false}", "电脑离线");
        String base = "{\"ok\":true,\"machineOnline\":true,\"observation\":";
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"running\"}}", "运行中");
        check(base + "{\"v\":1,\"source\":\"rollout\",\"turnId\":\"t\",\"state\":\"completed\"}}", "已完成");
        check(base + "{\"v\":1,\"source\":\"desktop\",\"state\":\"completed\"}}", "状态未知");
        check(base + "{\"v\":2,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"completed\"}}", "状态未知");
        check(base + "{\"v\":1,\"state\":\"unknown\"}}", "状态未知");
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"needs_input\",\"requests\":[]}}", "状态未知");
        check(base + "{\"v\":1,\"state\":\"unknown\",\"reason\":\"not_observed\"}}", "同步中");
        for (String reason : new String[]{"source_unavailable", "connection_closed", "owner_changed",
                "incompatible_protocol", "invalid_snapshot", "revision_gap", "missing_turn_id", "unsupported_request"}) {
            check(base + "{\"v\":1,\"state\":\"unknown\",\"reason\":\"" + reason + "\"}}", "状态未知");
        }
        check("{\"ok\":true,\"machineOnline\":false,\"observation\":{\"v\":1,\"state\":\"unknown\",\"reason\":\"not_observed\"}}", "电脑离线");
        check(base + "{\"v\":2,\"state\":\"unknown\",\"reason\":\"not_observed\"}}", "状态未知");
        for (String state : new String[]{"failed", "cancelled", "needs_input"}) {
            String expected = state.equals("failed") ? "执行失败" : state.equals("cancelled") ? "已取消" : "待处理";
            check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"" + state
                    + "\",\"requests\":[{\"requestId\":\"r\",\"kind\":\"permission_request\"}]}}", expected);
        }
        check(base + "{\"v\":1,\"source\":\"desktop\",\"turnId\":\"t\",\"state\":\"needs_input\",\"requests\":[{}]}}", "状态未知");
        System.out.println("SessionStatus: 生命周期事实、缺失身份、未知协议和连接状态区分通过");
    }
    private static void check(String input, String expected) {
        if (!expected.equals(SessionStatus.label(JsonParser.parseString(input).getAsJsonObject()))) throw new AssertionError(expected);
    }
}
