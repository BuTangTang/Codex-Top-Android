package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 用合成控制快照验证身份、展示范围和审批参数，不访问真实桌面任务。 */
public final class DesktopApprovalTest {
    /** 详情缺失、旧终态和不支持的操作均不能构造决定；完整请求保留全部原身份。 */
    public static void main(String[] args) throws Exception {
        JsonObject snapshot = JsonParser.parseString("{\"v\":1,\"turnId\":\"turn-a\",\"state\":\"running\",\"requests\":[{\"requestId\":\"request-a\",\"revision\":\"revision-a\",\"kind\":\"command\",\"command\":\"echo synthetic\",\"canDecide\":true}]}").getAsJsonObject();
        // 协议版本必须为数值 1，不可通过整数截断或字符串转换放行。
        for (String version : new String[]{"1.5", "4294967297", "\"1\"", "null", "true", "{}", "[]"}) {
            JsonObject invalid = snapshot.deepCopy(); invalid.add("v", JsonParser.parseString(version));
            try { DesktopApproval.read(invalid); throw new AssertionError("无效版本被接受：" + version); }
            catch (java.io.IOException expected) { }
        }
        DesktopApproval request = DesktopApproval.read(snapshot).get(0);
        JsonObject action = request.action("machine-a", "session-a", "operation-a", true);
        if (action.size() != 8 || !action.get("expectedTurnId").getAsString().equals("turn-a")
                || !action.get("revision").getAsString().equals("revision-a")
                || !action.get("sessionId").getAsString().equals("session-a")
                || !action.get("machineId").getAsString().equals("machine-a")
                || !action.get("requestId").getAsString().equals("request-a")
                || !action.get("decision").getAsString().equals("allow_once")
                || !request.action("machine-a", "session-a", "operation-a", false).get("decision").getAsString().equals("deny"))
            throw new AssertionError("审批参数改变原请求身份");
        for (String field : new String[]{"command", "canDecide"}) {
            JsonObject missing = snapshot.deepCopy(); missing.getAsJsonArray("requests").get(0).getAsJsonObject().remove(field);
            expectDisabled(missing);
        }
        JsonObject completed = snapshot.deepCopy(); completed.addProperty("state", "completed"); expectDisabled(completed);
        JsonObject unsupported = snapshot.deepCopy(); unsupported.getAsJsonArray("requests").get(0).getAsJsonObject().addProperty("kind", "unsupported"); expectDisabled(unsupported);
        JsonObject broken = snapshot.deepCopy(); broken.getAsJsonArray("requests").get(0).getAsJsonObject().remove("revision");
        try { DesktopApproval.read(broken); throw new AssertionError("缺少版本仍接受"); } catch (java.io.IOException expected) { }
        if (snapshot.getAsJsonArray("requests").get(0).getAsJsonObject().size() != 5) throw new AssertionError("修改来源快照");
        JsonObject files = snapshot.deepCopy();
        JsonObject change = files.getAsJsonArray("requests").get(0).getAsJsonObject();
        change.addProperty("kind", "file_change"); change.remove("command");
        change.add("files", JsonParser.parseString("[{\"path\":\"sample.txt\",\"kind\":\"update\",\"diff\":\"-old\\n+new\"}]"));
        DesktopApproval fileRequest = DesktopApproval.read(files).get(0);
        if (!fileRequest.canDecide || !fileRequest.details.contains("-old\n+new"))
            throw new AssertionError("完整文件差异未展示");
        change.getAsJsonArray("files").get(0).getAsJsonObject().remove("diff"); expectDisabled(files);
        JsonObject hidden = snapshot.deepCopy(); hidden.getAsJsonArray("requests").get(0).getAsJsonObject().addProperty("canDecide", false);
        expectDisabled(hidden);
        JsonObject empty = snapshot.deepCopy(); empty.add("requests", new com.google.gson.JsonArray());
        if (!DesktopApproval.read(empty).isEmpty()) throw new AssertionError("空待办生成审批");
        System.out.println("DesktopApproval: 请求身份、单次批准/拒绝、详情不足与旧终态限制通过");
    }

    /** 不可决定的内容可以显示，但不能形成控制请求。 */
    private static void expectDisabled(JsonObject snapshot) throws Exception {
        DesktopApproval request = DesktopApproval.read(snapshot).get(0);
        if (request.canDecide) throw new AssertionError("错误开放审批");
        try { request.action("machine", "session", "operation", true); throw new AssertionError("不可决定仍提交"); }
        catch (IllegalStateException expected) { }
    }
}
