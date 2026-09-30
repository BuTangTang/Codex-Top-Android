package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** 用合成描述验证手机原件、上传结果与接收引用的不同路径归属。 */
public final class DesktopAttachmentTest {
    /** 描述往返不包含字节，缺失引用明确不可用，上传结果必须匹配原件。 */
    public static void main(String[] args) {
        String hash = "a".repeat(64);
        DesktopAttachment.Pending pending = new DesktopAttachment.Pending("/private/staged/sample.png", "sample.png",
                "image", "image/png", 99, hash);
        DesktopAttachment ready = pending.uploaded("/computer/tmp/sample.png", 99, hash);
        JsonObject raw = new JsonObject(); raw.add("meta", DesktopAttachment.meta(List.of(ready)));
        var attachment = DesktopAttachment.readRaw(raw).get(0);
        check(attachment.isAvailable() && attachment.path.equals("/computer/tmp/sample.png")
                && attachment.sizeBytes == 99 && attachment.sha256.equals(hash), "上传引用字段丢失");
        check(pending.localPath.equals("/private/staged/sample.png"), "电脑路径覆盖了手机原件");
        check(DesktopAttachment.Pending.read(pending.toJson()).equals(pending), "待发原件重开身份改变");
        rejected(() -> pending.uploaded("/computer/tmp/other.png", 100, hash));
        rejected(() -> pending.uploaded("/computer/tmp/other.png", 99, "b".repeat(64)));
        rejected(() -> new DesktopAttachment.Pending("content://picker/document", "sample", "file", null, 99, hash));
        rejected(() -> new DesktopAttachment("missing", "file", null, null, null, null, null, null));
        rejected(() -> new DesktopAttachment("inline", "image", "data:image/png;base64,AA==", null, null, null, null, null));
        DesktopAttachment unavailable = new DesktopAttachment("remote.txt", "file", null, "text/plain", null,
                null, "unavailable", "cloud_reference_unavailable");
        raw.add("meta", DesktopAttachment.meta(List.of(unavailable)));
        attachment = DesktopAttachment.readRaw(raw).get(0);
        check(!attachment.isAvailable() && attachment.path == null && attachment.reason.equals("cloud_reference_unavailable"),
                "不可下载引用被隐藏或伪造路径");
        JsonObject fromDesktop = JsonParser.parseString("{\"meta\":{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"image.png\",\"kind\":\"image\",\"path\":\"/computer/image.png\",\"dataBase64\":\"DO_NOT_STORE\"}]}}}}").getAsJsonObject();
        String saved = DesktopAttachment.meta(DesktopAttachment.readRaw(fromDesktop)).toString();
        check(!saved.contains("dataBase64") && !saved.contains("DO_NOT_STORE"), "未知内嵌字节写入元数据");
        DesktopAttachment.Pending colonName = new DesktopAttachment.Pending("/private/staged/report:2026.txt", "report:2026.txt",
                "file", "text/plain", 104, hash);
        raw.add("meta", DesktopAttachment.meta(List.of(colonName.uploaded("/computer/tmp/report:2026.txt", 104, hash))));
        DesktopAttachment colonReply = DesktopAttachment.readRaw(raw).get(0);
        check(colonReply.name.equals("report:2026.txt") && colonReply.path.equals("/computer/tmp/report:2026.txt"),
                "原生带冒号文件名被当作URI或拆掉路径");
        check(DesktopAttachment.readRaw(new JsonObject()).isEmpty(), "旧文字被增加假附件");
        System.out.println("DesktopAttachment: 原件身份、上传校验、引用可用性及无内嵌字节通过");
    }
    /** 必须拒绝错误描述而非生成可用引用。 */
    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("错误附件被接受"); }
        catch (IllegalArgumentException expected) { }
    }
    /** 行为偏离即终止独立模型验证。 */
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
