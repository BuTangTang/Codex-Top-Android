package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;

/** 合成题目验证原请求身份、多题单次提交及已处理问题，完全不读取真实对话。 */
public final class DesktopQuestionTest {
    /** 数字与字符串请求不得混同，回答必须完整且保留原题顺序及自填能力。 */
    public static void main(String[] args) throws Exception {
        JsonObject snapshot = JsonParser.parseString("{\"v\":1,\"questions\":[{\"kind\":\"user_input\",\"requestId\":7,\"itemId\":\"item\",\"turnId\":\"turn\",\"revision\":\"r1\",\"status\":\"pending\",\"canAnswer\":true,\"questions\":[{\"id\":\"choice\",\"header\":\"选择\",\"question\":\"选一个\",\"options\":[{\"label\":\"甲\",\"description\":\"说明\"},{\"label\":\"乙\",\"description\":\"\"}]},{\"id\":\"free\",\"header\":\"\",\"question\":\"填写\",\"isSecret\":true,\"options\":[]}]}]}").getAsJsonObject();
        LinkedHashMap<String, String> answers = new LinkedHashMap<>();
        answers.put("free", "合成内容"); answers.put("choice", "乙");
        DesktopQuestion numeric = DesktopQuestion.read(snapshot).get(0);
        JsonObject action = numeric.action("machine", "session", "operation", answers);
        check(action.get("requestId").getAsJsonPrimitive().isNumber(), "数字请求身份被字符串化");
        check(action.getAsJsonObject("answers").keySet().iterator().next().equals("choice"), "回答未按原题排序");
        check(action.getAsJsonObject("answers").getAsJsonArray("free").get(0).getAsString().equals("合成内容"), "自填回答丢失");
        check(numeric.questions.get(1).isSecret, "秘密题目未标记");
        check(!numeric.questions.get(0).accepts("其它"), "原题未授权自填却接受");
        JsonObject stringId = snapshot.deepCopy(); request(stringId).addProperty("requestId", "7");
        DesktopQuestion string = DesktopQuestion.read(stringId).get(0);
        check(!string.identity().equals(numeric.identity()), "数字与字符串身份发生合并");
        check(string.action("m", "s", "op", answers).get("requestId").getAsJsonPrimitive().isString(), "字符串请求身份被数值化");
        JsonObject async = snapshot.deepCopy(); request(async).addProperty("kind", "async_questions"); request(async).add("requestId", com.google.gson.JsonNull.INSTANCE);
        check(DesktopQuestion.read(async).get(0).action("m", "s", "op", answers).get("requestId").isJsonNull(), "异步请求丢失空身份");
        for (String state : new String[]{"answered", "expired"}) {
            JsonObject closed = snapshot.deepCopy(); request(closed).addProperty("status", state);
            expectCannotSubmit(DesktopQuestion.read(closed).get(0), answers);
        }
        LinkedHashMap<String, String> missing = new LinkedHashMap<>(answers); missing.remove("free"); expectCannotSubmit(numeric, missing);
        LinkedHashMap<String, String> invalid = new LinkedHashMap<>(answers); invalid.put("choice", "其它"); expectCannotSubmit(numeric, invalid);
        JsonObject other = snapshot.deepCopy(); request(other).getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("isOther", true);
        check(DesktopQuestion.read(other).get(0).action("m", "s", "op", invalid).getAsJsonObject("answers").has("choice"), "原题允许自填却拒绝");
        JsonObject partial = snapshot.deepCopy(); request(partial).add("answers", JsonParser.parseString("{\"choice\":[\"乙\"]}"));
        DesktopQuestion partlyAnswered = DesktopQuestion.read(partial).get(0);
        check(partlyAnswered.answers.get("choice").equals("乙"), "桌面已答项丢失");
        partlyAnswered.action("m", "s", "op", answers);
        LinkedHashMap<String, String> changed = new LinkedHashMap<>(answers); changed.put("choice", "甲");
        expectCannotSubmit(partlyAnswered, changed);
        JsonObject revision = snapshot.deepCopy(); request(revision).addProperty("revision", "r2");
        check(!DesktopQuestion.read(revision).get(0).identity().equals(numeric.identity()), "修订未隔离草稿/提交身份");
        JsonObject oldServer = snapshot.deepCopy(); oldServer.remove("questions"); expectUnreadable(oldServer);
        JsonObject duplicate = snapshot.deepCopy(); request(duplicate).getAsJsonArray("questions").get(1).getAsJsonObject().addProperty("id", "choice"); expectUnreadable(duplicate);
        for (String version : new String[]{"1.5", "\"1\"", "null", "true"}) {
            JsonObject wrongVersion = snapshot.deepCopy(); wrongVersion.add("v", JsonParser.parseString(version)); expectUnreadable(wrongVersion);
        }
        System.out.println("DesktopQuestion: 原题身份、多题回答、选择/自填、保密标记、已处理/旧服务限制通过");
    }

    /** 取得合成快照的唯一题组以构造变体。 */
    private static JsonObject request(JsonObject snapshot) { return snapshot.getAsJsonArray("questions").get(0).getAsJsonObject(); }
    /** 不符合原题提交条件时必须拒绝，不能形成另一个普通消息。 */
    private static void expectCannotSubmit(DesktopQuestion question, LinkedHashMap<String, String> answers) {
        try { question.action("m", "s", "op", answers); throw new AssertionError("不可回答的问题仍提交"); }
        catch (IllegalStateException expected) { }
    }
    /** 来源不支持或身份冲突不能伪装成空问题成功。 */
    private static void expectUnreadable(JsonObject snapshot) throws Exception {
        try { DesktopQuestion.read(snapshot); throw new AssertionError("无效快照被接受"); }
        catch (IOException expected) { }
    }
    /** 输出失败原因，不记录任何真实内容。 */
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
