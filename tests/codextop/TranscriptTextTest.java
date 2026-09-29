package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;

/** 覆盖现有 Codex 主对话与工具调用的数据格式，不依赖真实会话内容。 */
public final class TranscriptTextTest {
    /** 验证文字、身份与时间保留，以及工具／侧链／空内容不会生成气泡。 */
    public static void main(String[] args) {
        JsonArray items = new JsonArray();
        items.add(item("u1", "user", "text", "  用户文字\n", null));
        items.add(item("a1", "agent", "message", "助手回复", null));
        items.add(item("tool", "agent", "tool-call", "工具内容", null));
        items.add(item("child", "agent", "message", "子任务正文", "child-id"));
        items.add(item("empty", "user", "text", " ", null));
        ArrayList<TranscriptText> result = TranscriptText.read(items);
        if (result.size() != 2 || !result.get(0).outgoing || result.get(1).outgoing
                || !result.get(0).text.equals("  用户文字\n") || !result.get(1).text.equals("助手回复")
                || !result.get(0).localId.equals("local-u1") || !result.get(1).id.equals("a1")
                || result.get(0).createdAtMs != 1234) throw new AssertionError("文字投影不符合契约");
        String metadata = "<oai-mem-citation>\n<citation_entries>\nMEMORY.md:1-2|note=[test]\n</citation_entries>\n<rollout_ids></rollout_ids>\n</oai-mem-citation>";
        JsonArray metadataItems = new JsonArray();
        metadataItems.add(item("meta", "agent", "message", "正常回复\n\n" + metadata, null));
        metadataItems.add(item("user-meta", "user", "text", metadata, null));
        metadataItems.add(item("example", "agent", "message", "```xml\n" + metadata + "\n```", null));
        metadataItems.add(item("incomplete", "agent", "message", "正文\n<oai-mem-citation>", null));
        ArrayList<TranscriptText> visible = TranscriptText.read(metadataItems);
        if (visible.size() != 4 || !visible.get(0).text.equals("正常回复")
                || !visible.get(1).text.equals(metadata)
                || !visible.get(2).text.equals("```xml\n" + metadata + "\n```")
                || !visible.get(3).text.equals("正文\n<oai-mem-citation>"))
            throw new AssertionError("内部引用过滤损坏可见正文");
        System.out.println("TranscriptText: 主对话文字、来源身份与工具过滤通过");
    }

    /** 根据正式 raw 契约生成独立的合成消息。 */
    private static JsonObject item(String id, String role, String type, String text, String sidechain) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text".equals(type) ? "text" : "codex");
        if ("text".equals(type)) content.addProperty("text", text);
        else {
            JsonObject data = new JsonObject();
            data.addProperty("type", type); data.addProperty("message", text);
            if (sidechain != null) data.addProperty("sidechainId", sidechain);
            content.add("data", data);
        }
        JsonObject raw = new JsonObject(); raw.addProperty("role", role); raw.add("content", content);
        JsonObject item = new JsonObject(); item.addProperty("id", id); item.addProperty("localId", "local-" + id);
        item.addProperty("createdAtMs", 1234); item.add("raw", raw);
        return item;
    }
}
