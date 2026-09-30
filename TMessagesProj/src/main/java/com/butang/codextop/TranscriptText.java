package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

    /** 只读取用户文字和主对话助手文字；保留顺序，隐藏助手末尾的内部引用元数据。 */
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

    /** 仅移除独立位于回复末尾的完整内部引用块；正文中引用或未闭合内容原样保留。 */
    private static String visibleAssistantText(String text) {
        return text.replaceFirst("(?s)(?:\\A|\\r?\\n)[\\t \\r\\n]*<oai-mem-citation>\\s*<citation_entries>.*?</citation_entries>\\s*<rollout_ids>.*?</rollout_ids>\\s*</oai-mem-citation>\\s*$", "");
    }

    /** 读取可选文本，缺失或空值保持未知，避免把 null 变成界面正文。 */
    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
