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
        JsonArray attachedItems = new JsonArray();
        JsonObject attachmentOnly = item("attachment-only", "user", "text", "", null);
        attachmentOnly.getAsJsonObject("raw").add("meta", com.google.gson.JsonParser.parseString(
                "{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"sample.png\",\"kind\":\"image\",\"path\":\"/tmp/sample.png\"}]}}}"));
        attachedItems.add(attachmentOnly);
        ArrayList<TranscriptText> attached = TranscriptText.read(attachedItems);
        if (attached.size() != 1 || attached.get(0).attachments.size() != 1
                || !attached.get(0).text.isEmpty() || !attached.get(0).attachments.get(0).path.equals("/tmp/sample.png"))
            throw new AssertionError("纯附件无文字的原消息被过滤或丢失引用");
        JsonObject hiddenTool = item("attached-tool", "agent", "tool-call", "", null);
        hiddenTool.getAsJsonObject("raw").add("meta", attachmentOnly.getAsJsonObject("raw").get("meta").deepCopy());
        JsonObject hiddenChild = item("attached-child", "agent", "message", "", "child-id");
        hiddenChild.getAsJsonObject("raw").add("meta", attachmentOnly.getAsJsonObject("raw").get("meta").deepCopy());
        attachedItems.add(hiddenTool); attachedItems.add(hiddenChild);
        if (TranscriptText.read(attachedItems).size() != 1) throw new AssertionError("工具或侧链附件生成了主对话气泡");
        JsonObject attachmentReply = item("attachment-reply", "agent", "message", null, null);
        attachmentReply.getAsJsonObject("raw").add("meta", com.google.gson.JsonParser.parseString(
                "{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"cloud.txt\",\"kind\":\"file\",\"availability\":\"unavailable\",\"reason\":\"cloud_reference_unavailable\"}]}}}"));
        attachedItems.add(attachmentReply);
        attached = TranscriptText.read(attachedItems);
        if (attached.size() != 2 || attached.get(1).outgoing || attached.get(1).attachments.get(0).isAvailable())
            throw new AssertionError("助手纯附件的不可用引用没有保留");
        multipleAttachmentRows();
        assistantCitationBoundaries();
        System.out.println("TranscriptText: 主对话文字、来源身份与工具过滤通过");
    }

    /** 无精确标签的文字保持原样；带标签的内嵌、未闭合和末尾完整块仍沿原规则。 */
    private static void assistantCitationBoundaries() {
        String block = "<oai-mem-citation><citation_entries>synthetic</citation_entries>"
                + "<rollout_ids></rollout_ids></oai-mem-citation>";
        String[][] cases = {
                {"普通文字🙂\n第二行\r\n", "普通文字🙂\n第二行\r\n"},
                {"<oai-mem-citatio>", "<oai-mem-citatio>"},
                {"<OAI-MEM-CITATION>", "<OAI-MEM-CITATION>"},
                {"＜oai-mem-citation＞", "＜oai-mem-citation＞"},
                {"内嵌 " + block, "内嵌 " + block},
                {"正文\n<oai-mem-citation>", "正文\n<oai-mem-citation>"},
                {"正文\n" + block + " 后文", "正文\n" + block + " 后文"},
                {"正文\r\n" + block + "\r\n \t", "正文"},
                {"正文\n" + block + "\n" + block, "正文"},
                {block, ""}
        };
        for (int i = 0; i < cases.length; i++) {
            for (String role : new String[]{"agent", "user"}) {
                JsonArray items = new JsonArray();
                items.add(item("boundary-" + i, role, "text", cases[i][0], null));
                var rows = TranscriptText.read(items);
                String expected = "user".equals(role) ? cases[i][0] : cases[i][1];
                if (expected.isEmpty() ? !rows.isEmpty() : rows.size() != 1 || !rows.get(0).text.equals(expected))
                    throw new AssertionError("引用边界改变正文或用户内容: " + i + "/" + role);
            }
        }
    }

    /** 同批文件仅在本地展开，第一行保留说明和原身份，其余行逐个派生身份。 */
    private static void multipleAttachmentRows() {
        JsonObject batch = item("batch", "user", "text", "同批说明", null);
        batch.getAsJsonObject("raw").add("meta", DesktopAttachment.meta(java.util.List.of(
                new DesktopAttachment("one.png", "image", "/computer/one.png", null, null, null, null, null),
                new DesktopAttachment("two.txt", "file", "/computer/two.txt", null, null, null, null, null),
                new DesktopAttachment("three.txt", "file", null, null, null, null, "unavailable", "missing"))));
        String original = batch.toString();
        JsonArray items = new JsonArray(); items.add(batch);
        var rows = TranscriptText.read(items);
        if (rows.size() != 3) throw new AssertionError("多附件未展开为独立原生消息");
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String suffix = i == 0 ? "" : ":attachment:" + i;
            if (!row.id.equals("batch" + suffix) || !row.localId.equals("local-batch" + suffix)
                    || !row.text.equals(i == 0 ? "同批说明" : "") || row.attachments.size() != 1
                    || row.createdAtMs != 1234 || !row.outgoing)
                throw new AssertionError("多附件展开破坏原身份、说明或方向时间");
        }
        if (!batch.toString().equals(original)) throw new AssertionError("本地展开改写了原始协议");
        batch.remove("localId");
        for (TranscriptText row : TranscriptText.read(items))
            if (row.localId != null) throw new AssertionError("缺失localId被生成了虚假发送身份");
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
