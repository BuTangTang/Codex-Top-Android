package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;

/** 用实际 TranscriptText.read 验证异步回答只改变可见文字，不推断任务或提交状态。 */
public final class TranscriptQuestionReplyTest {
    /** 覆盖完整封套、旧缓存再投影及必须原样保留的正文和非法结构。 */
    public static void main(String[] args) {
        String reply = reply("选择验证项", "丙", 0);
        visible(envelope("[" + reply + "]"), "选择验证项\n丙");
        visible(envelope(reply), "选择验证项\n丙");
        visible(envelope("[" + reply + "," + reply("补充说明", "自填文字\n第二行", 1) + "]"),
                "选择验证项\n丙\n\n补充说明\n自填文字\n第二行");
        visible(" \n" + envelope(reply("保留空答案", "", 2)) + "\n ", "保留空答案\n");
        preserves(envelope(reply("", "", 0)));
        preserves("普通用户正文\n" + envelope(reply));
        preserves(envelope(reply) + "\n补充正文");
        preserves("> " + envelope(reply).replace("\n", "\n> "));
        preserves("```xml\n" + envelope(reply) + "\n```");
        preserves("\"" + envelope(reply) + "\"");
        preserves("<send_user_message_question_reply>\n" + reply);
        preserves(envelope("[]"));
        preserves(envelope("null"));
        preserves(envelope("[" + reply + ",null]"));
        preserves(envelope("[" + reply + ",{}]"));
        preserves(envelope(reply.replace("\"answer\":\"丙\"", "\"answer\":null")));
        preserves(envelope(reply.replace("\"question\":\"选择验证项\"", "\"question\":3")));
        preserves(envelope(reply.replace("request_user_input_async", "request_user_input")));
        preserves(envelope(reply.replace("synthetic-card", "")));
        preserves(envelope(reply.replace(",0]", ",-1]")));
        preserves(envelope(reply.replace(",0]", ",0.5]")));
        preserves(envelope(identityReply("[\"request_user_input_async\",\"synthetic-card\",\"0\"]")));
        preserves(envelope(identityReply("[\"request_user_input_async\",\"synthetic-card\",false]")));
        preserves(envelope(identityReply("[\"request_user_input_async\",\"synthetic-card\",0.999999999999999999999]")));
        preserves(envelope(identityReply("[\"request_user_input_async\",null,0]")));
        preserves(envelope(reply.replace(",0]", ",2147483648]")));
        preserves(envelope(reply.replace(",0]", ",0,1]")));
        preserves(envelope(reply.substring(0, reply.length() - 1) + ",\"unrecognized\":\"body\"}"));
        preserves(envelope(reply + " trailing text"));
        preserves(envelope("/* quoted example */" + reply));
        preserves(envelope(reply("问题\n第二行", "甲", 0).replace("\\n", "\n")));
        preserves(envelope("{'questionItemId':'[\"request_user_input_async\",\"synthetic-card\",0]',"
                + "'question':'quoted','answer':'example'}"));
        JsonArray agent = new JsonArray();
        JsonObject agentItem = item(envelope(reply));
        agentItem.getAsJsonObject("raw").addProperty("role", "agent");
        agent.add(agentItem);
        if (!TranscriptText.read(agent).get(0).text.equals(envelope(reply)))
            throw new AssertionError("助手引用被当作用户回答改写");
        System.out.println("TranscriptQuestionReplyTest: 合法封套显示、身份和旧缓存保留、正文及非法结构边界通过");
    }

    /** 合法回答经实际 read 再投影，保留原记录、发送身份、时间及缓存中的原始封套。 */
    private static void visible(String source, String expected) {
        JsonObject message = item(source);
        String cached = message.toString();
        JsonArray items = new JsonArray(); items.add(message);
        ArrayList<TranscriptText> rows = TranscriptText.read(items);
        if (rows.size() != 1 || !expected.equals(rows.get(0).text))
            throw new AssertionError("合法异步回答未投影为问题和答案");
        TranscriptText row = rows.get(0);
        if (!row.id.equals("message-id") || !row.localId.equals("local-id") || row.createdAtMs != 1234
                || !row.outgoing || !row.attachments.isEmpty() || !message.toString().equals(cached))
            throw new AssertionError("文字显示改写了原身份、方向、时间或缓存");
        JsonArray restored = com.google.gson.JsonParser.parseString("[" + cached + "]").getAsJsonArray();
        if (!TranscriptText.read(restored).get(0).text.equals(expected))
            throw new AssertionError("旧缓存没有经过同一实际文字投影");
    }

    /** 不完整或作为正文引用的内容保持原字节，不能只凭标签猜测提交。 */
    private static void preserves(String source) {
        JsonArray items = new JsonArray(); items.add(item(source));
        ArrayList<TranscriptText> rows = TranscriptText.read(items);
        if (rows.size() != 1 || !source.equals(rows.get(0).text))
            throw new AssertionError("普通正文或非法封套被改写");
    }

    /** 构造三元原题标识和三个字符串字段，与三选项的异步原生回答结构一致。 */
    private static String reply(String question, String answer, int index) {
        JsonArray identity = new JsonArray();
        identity.add("request_user_input_async"); identity.add("synthetic-card"); identity.add(index);
        JsonObject reply = new JsonObject();
        reply.addProperty("questionItemId", identity.toString());
        reply.addProperty("question", question); reply.addProperty("answer", answer);
        return reply.toString();
    }

    /** 保持外层 JSON 合法，仅替换题目身份，验证错误类型不会被宽松转换。 */
    private static String identityReply(String identity) {
        JsonObject reply = com.google.gson.JsonParser.parseString(reply("选择验证项", "丙", 0)).getAsJsonObject();
        reply.addProperty("questionItemId", identity);
        return reply.toString();
    }

    /** 使用完整原生封套；嵌入或引用情形由调用者另行构造。 */
    private static String envelope(String payload) {
        return "<send_user_message_question_reply>\n" + payload + "\n</send_user_message_question_reply>";
    }

    /** 构造原 raw 用户文字契约，不借真实任务或设备状态。 */
    private static JsonObject item(String text) {
        JsonObject content = new JsonObject(); content.addProperty("type", "text"); content.addProperty("text", text);
        JsonObject raw = new JsonObject(); raw.addProperty("role", "user"); raw.add("content", content);
        JsonObject item = new JsonObject(); item.addProperty("id", "message-id"); item.addProperty("localId", "local-id");
        item.addProperty("createdAtMs", 1234); item.add("raw", raw);
        return item;
    }
}
