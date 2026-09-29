package com.butang.codextop;
import com.google.gson.JsonObject;
/** 仅生命周期事实决定任务状态，连接成功和近期活动不等于运行。 */
public final class SessionStatus {
    /** 正常未观察到状态仅表示同步等待；离线、未知协议和明确错误不能用等待掩盖。 */
    public static String label(JsonObject response) {
        try {
            if (!response.get("ok").getAsBoolean()) return "状态暂不可用";
            if (!response.get("machineOnline").getAsBoolean()) return "电脑离线";
            JsonObject observation = response.getAsJsonObject("observation");
            if (observation == null || observation.get("v").getAsInt() != 1) return "状态未知";
            String state = observation.get("state").getAsString();
            if ("unknown".equals(state)) {
                return observation.has("reason") && !observation.get("reason").isJsonNull()
                        && "not_observed".equals(observation.get("reason").getAsString()) ? "同步中" : "状态未知";
            }
            String source = observation.get("source").getAsString();
            if ((!"desktop".equals(source) && !"rollout".equals(source))
                    || observation.get("turnId").getAsString().isEmpty()) return "状态未知";
            switch (state) {
                case "running": return "运行中";
                case "completed": return "已完成";
                case "failed": return "执行失败";
                case "cancelled": return "已取消";
                case "needs_input":
                    if (observation.getAsJsonArray("requests").size() == 0) return "状态未知";
                    for (com.google.gson.JsonElement value : observation.getAsJsonArray("requests")) {
                        JsonObject request = value.getAsJsonObject();
                        String kind = request.get("kind").getAsString();
                        if (request.get("requestId").getAsString().isEmpty()
                                || (!"permission_request".equals(kind) && !"user_action_request".equals(kind))) return "状态未知";
                    }
                    return "待处理";
                default: return "状态未知";
            }
        } catch (RuntimeException error) { return "状态未知"; }
    }
}
