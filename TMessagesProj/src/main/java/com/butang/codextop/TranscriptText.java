package com.butang.codextop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.ArrayList;

/** 将现有桌面历史契约投影为原生消息模型所需的文字，不展示工具调用。 */
public final class TranscriptText {
    public final String id;
    public final String localId;
    public final long createdAtMs;
    public final String text;
    public final boolean outgoing;
    public final java.util.List<DesktopAttachment> attachments;

    /** 保留来源身份和时间，后续回显去重不得按正文猜测。 */
    private TranscriptText(JsonObject item, String text, boolean outgoing,
            java.util.List<DesktopAttachment> attachments, int attachmentIndex) {
        id = attachmentIdentity(item.get("id").getAsString(), attachmentIndex);
        localId = attachmentIdentity(string(item, "localId"), attachmentIndex);
        createdAtMs = item.get("createdAtMs").getAsLong();
        this.text = text;
        this.outgoing = outgoing;
        this.attachments = attachments;
    }

    /** 只读取用户文字和主对话助手文字；原生回答只作可读投影，助手末尾内部引用隐藏。 */
    public static ArrayList<TranscriptText> read(JsonArray items) {
        ArrayList<TranscriptText> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            JsonObject raw = item.getAsJsonObject("raw");
            if (raw == null || !raw.has("content") || !raw.get("content").isJsonObject()) continue;
            JsonObject content = raw.getAsJsonObject("content");
            String role = string(raw, "role");
            if (!"user".equals(role) && !"agent".equals(role)) continue;
            String text = null;
            if ("text".equals(string(content, "type"))) {
                text = string(content, "text");
            } else if ("agent".equals(role) && "codex".equals(string(content, "type"))
                    && content.has("data") && content.get("data").isJsonObject()) {
                JsonObject data = content.getAsJsonObject("data");
                if ("message".equals(string(data, "type")) && string(data, "sidechainId") == null) {
                    text = string(data, "message");
                }
            }
            // 只给原主消息读取附件，工具和侧链即使携带meta也不成为手机消息。
            boolean mainMessage = "text".equals(string(content, "type"))
                    || "agent".equals(role) && "codex".equals(string(content, "type"))
                    && content.has("data") && content.get("data").isJsonObject()
                    && "message".equals(string(content.getAsJsonObject("data"), "type"))
                    && string(content.getAsJsonObject("data"), "sidechainId") == null;
            if (!mainMessage) continue;
            java.util.List<DesktopAttachment> attachments = DesktopAttachment.readRaw(raw);
            if (text != null && "agent".equals(role)) text = visibleAssistantText(text);
            if (text != null && "user".equals(role)) text = visibleUserText(text);
            if (attachments.isEmpty()) {
                if (text != null && !text.trim().isEmpty())
                    result.add(new TranscriptText(item, text, "user".equals(role), attachments, 0));
            } else {
                // 只在本地按附件顺序展开；快照中的单附件行再次读取时仍使用原身份。
                for (int index = 0; index < attachments.size(); index++)
                    result.add(new TranscriptText(item, index == 0 && text != null ? text : "", "user".equals(role),
                            java.util.Collections.singletonList(attachments.get(index)), index));
            }
        }
        return result;
    }

    /** 第一件沿原身份，后续件与待发投影共用后缀；缺失localId不生成虚假发送编号。 */
    public static String attachmentIdentity(String base, int index) {
        if (index < 0) throw new IllegalArgumentException("附件序号无效");
        return index == 0 || base == null || base.isEmpty() ? base : base + ":attachment:" + index;
    }

    /** 缺少必要字面开标签时跳过正则；存在时仍仅移除原规则认定的完整末尾引用块。 */
    private static String visibleAssistantText(String text) {
        if (!text.contains("<oai-mem-citation>")) return text;
        return text.replaceFirst("(?s)(?:\\A|\\r?\\n)[\\t \\r\\n]*<oai-mem-citation>\\s*<citation_entries>.*?</citation_entries>\\s*<rollout_ids>.*?</rollout_ids>\\s*</oai-mem-citation>\\s*$", "");
    }

    /** 完整异步回答封套仅转换可见文字；引用、正文嵌入及非法结构原样保留，不据此确认提交或状态。 */
    private static String visibleUserText(String text) {
        String source = text.trim();
        String start = "<send_user_message_question_reply>", end = "</send_user_message_question_reply>";
        if (!source.startsWith(start) || !source.endsWith(end)) return text;
        try {
            JsonElement payload = questionReplyJson(source.substring(start.length(), source.length() - end.length()));
            JsonArray replies;
            if (payload.isJsonObject()) {
                replies = new JsonArray(); replies.add(payload);
            } else if (payload.isJsonArray()) replies = payload.getAsJsonArray();
            else return text;
            if (replies.size() == 0) return text;
            StringBuilder visible = new StringBuilder();
            // 整批校验后才返回文字，不能把部分合法回答从含未知内容的封套中摘出来。
            for (JsonElement entry : replies) {
                if (!entry.isJsonObject()) return text;
                JsonObject reply = entry.getAsJsonObject();
                String questionId = string(reply, "questionItemId"), question = string(reply, "question"), answer = string(reply, "answer");
                if (reply.size() != 3 || questionId == null || question == null || answer == null) return text;
                JsonElement parsedId = questionReplyJson(questionId);
                if (!parsedId.isJsonArray()) return text;
                JsonArray identity = parsedId.getAsJsonArray();
                if (identity.size() != 3 || !identity.get(0).isJsonPrimitive() || !identity.get(0).getAsJsonPrimitive().isString()
                        || !"request_user_input_async".equals(identity.get(0).getAsString())
                        || !identity.get(1).isJsonPrimitive() || !identity.get(1).getAsJsonPrimitive().isString()
                        || identity.get(1).getAsString().trim().isEmpty()
                        || !identity.get(2).isJsonPrimitive() || !identity.get(2).getAsJsonPrimitive().isNumber()) return text;
                if (identity.get(2).getAsBigDecimal().intValueExact() < 0) return text;
                if (visible.length() > 0) visible.append("\n\n");
                visible.append(question).append('\n').append(answer);
            }
            // 空问题和空答案不能让原本可见的封套消息消失。
            return visible.toString().trim().isEmpty() ? text : visible.toString();
        } catch (java.io.IOException | RuntimeException invalid) {
            return text;
        }
    }

    /** 严格读取一份完整 JSON，拒绝注释、单引号及尾随正文，避免示例内容被误作原生封套。 */
    private static JsonElement questionReplyJson(String source) throws java.io.IOException {
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = new Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new java.io.IOException("回答封套含尾随内容");
            return value;
        }
    }

    /** 读取可选文本，缺失或空值保持未知，避免把 null 变成界面正文。 */
    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
