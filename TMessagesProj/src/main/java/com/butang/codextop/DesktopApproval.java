package com.butang.codextop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;

/** 当前桌面审批的只读显示模型，身份直接来自控制快照，不由历史文字推测。 */
public final class DesktopApproval {
    public final String turnId, requestId, revision, details;
    public final boolean canDecide;

    /** 固定弹窗对应的请求版本，后续刷新不得悄悄替换用户正在确认的操作。 */
    private DesktopApproval(String turnId, String requestId, String revision, String details, boolean canDecide) {
        this.turnId = turnId; this.requestId = requestId; this.revision = revision;
        this.details = details; this.canDecide = canDecide;
    }

    /** 读取完整快照；未知类型与详情不足仅展示，格式损坏直接报告读取失败。 */
    public static ArrayList<DesktopApproval> read(JsonObject snapshot) throws IOException {
        try {
            JsonElement version = snapshot.get("v");
            // 与协议的数值字面量 1 一致，避免整数截断、溢出或字符串转换接受其他版本。
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0)
                throw new IOException("审批版本不支持");
            String turn = required(snapshot, "turnId");
            String state = required(snapshot, "state");
            ArrayList<DesktopApproval> result = new ArrayList<>();
            for (JsonElement value : snapshot.getAsJsonArray("requests")) {
                JsonObject request = value.getAsJsonObject();
                String id = required(request, "requestId"), revision = required(request, "revision");
                String kind = required(request, "kind");
                StringBuilder details = new StringBuilder();
                boolean complete = false;
                if ("command".equals(kind)) {
                    String command = optional(request, "command");
                    complete = !command.trim().isEmpty();
                    details.append("执行命令\n").append(command);
                } else if ("file_change".equals(kind)) {
                    details.append("修改文件");
                    complete = request.has("files") && request.get("files").isJsonArray()
                            && !request.getAsJsonArray("files").isEmpty();
                    if (complete) for (JsonElement fileValue : request.getAsJsonArray("files")) {
                        JsonObject file = fileValue.getAsJsonObject();
                        details.append("\n\n").append(required(file, "path"))
                                .append("\n").append(required(file, "kind"));
                        String diff = optional(file, "diff");
                        details.append("\n").append(diff);
                        if (diff.trim().isEmpty()) complete = false;
                    }
                } else details.append("此操作请在电脑上处理");
                String cwd = optional(request, "cwd"), reason = optional(request, "reason");
                if (!cwd.isEmpty()) details.append("\n\n工作目录：").append(cwd);
                if (!reason.isEmpty()) details.append("\n\n").append(reason);
                boolean allowed = request.has("canDecide") && request.get("canDecide").isJsonPrimitive()
                        && request.get("canDecide").getAsJsonPrimitive().isBoolean() && request.get("canDecide").getAsBoolean();
                result.add(new DesktopApproval(turn, id, revision, details.toString(),
                        "running".equals(state) && allowed && complete));
            }
            return result;
        } catch (RuntimeException error) { throw new IOException("审批详情无法读取", error); }
    }

    /** 构造已有严格接口的参数；只允许单次批准或拒绝，调用方保留同一操作编号。 */
    public JsonObject action(String machine, String session, String operationId, boolean allow) {
        if (!canDecide || machine == null || machine.isEmpty() || session == null || session.isEmpty()
                || operationId == null || operationId.isEmpty()) throw new IllegalStateException("当前审批不可提交");
        JsonObject result = new JsonObject();
        result.addProperty("machineId", machine); result.addProperty("sessionId", session);
        result.addProperty("kind", "approval"); result.addProperty("operationId", operationId);
        result.addProperty("expectedTurnId", turnId); result.addProperty("requestId", requestId);
        result.addProperty("revision", revision); result.addProperty("decision", allow ? "allow_once" : "deny");
        return result;
    }

    /** 身份字段必须为非空字符串，禁止将数字或空值作为请求身份。 */
    private static String required(JsonObject value, String key) throws IOException {
        String text = optional(value, key);
        if (text.trim().isEmpty()) throw new IOException("审批缺少必要信息");
        return text;
    }

    /** 可选显示文字保留原文，不把其他 JSON 类型转换成用户应批准的内容。 */
    private static String optional(JsonObject value, String key) {
        JsonElement item = value.get(key);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : "";
    }
}
