package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 只保存附件描述与文件关联；实际字节仍由原传输和本地文件承担。 */
public final class DesktopAttachment {
    public final String name, kind, path, mimeType, sha256, availability, reason;
    public final Long sizeBytes;

    /** 可用附件必须有电脑路径；明确不可用的引用只保留名字和实际原因。 */
    public DesktopAttachment(String name, String kind, String path, String mimeType, Long sizeBytes,
            String sha256, String availability, String reason) {
        validate(name, kind, sizeBytes, sha256);
        if (availability != null && !"unavailable".equals(availability)) throw new IllegalArgumentException("附件状态无效");
        if (!"unavailable".equals(availability) && (path == null || path.isEmpty()))
            throw new IllegalArgumentException("附件缺少电脑路径");
        if (path != null && path.startsWith("data:")) throw new IllegalArgumentException("附件不能内嵌正文");
        this.name = name; this.kind = kind; this.path = path; this.mimeType = mimeType;
        this.sizeBytes = sizeBytes; this.sha256 = sha256; this.availability = availability; this.reason = reason;
    }

    /** 明确不可用的引用仍显示附件，但不能当作可下载路径。 */
    public boolean isAvailable() { return !"unavailable".equals(availability); }

    /** 白名单序列化元数据，不把内嵌图片、传输块或未知扩展字段写入手机缓存。 */
    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("name", name); value.addProperty("kind", kind);
        if (path != null) value.addProperty("path", path);
        if (mimeType != null) value.addProperty("mimeType", mimeType);
        if (sizeBytes != null) value.addProperty("sizeBytes", sizeBytes);
        if (sha256 != null) value.addProperty("sha256", sha256);
        if (availability != null) value.addProperty("availability", availability);
        if (reason != null) value.addProperty("reason", reason);
        return value;
    }

    /** 仅识别已冻结的原消息附件封套，普通文字及其他扩展保持无附件。 */
    public static List<DesktopAttachment> readRaw(JsonObject raw) {
        if (raw == null || !raw.has("meta") || !raw.get("meta").isJsonObject()) return Collections.emptyList();
        JsonObject meta = raw.getAsJsonObject("meta");
        if (!meta.has("happier") || !meta.get("happier").isJsonObject()) return Collections.emptyList();
        JsonObject happier = meta.getAsJsonObject("happier");
        if (!"attachments.v1".equals(text(happier, "kind"))) return Collections.emptyList();
        JsonObject payload = happier.getAsJsonObject("payload");
        if (payload == null || !payload.has("attachments") || !payload.get("attachments").isJsonArray())
            throw new IllegalArgumentException("附件元数据缺失");
        ArrayList<DesktopAttachment> values = new ArrayList<>();
        for (JsonElement entry : payload.getAsJsonArray("attachments")) {
            JsonObject item = entry.getAsJsonObject();
            values.add(new DesktopAttachment(text(item, "name"), text(item, "kind"), text(item, "path"),
                    text(item, "mimeType"), number(item, "sizeBytes"), text(item, "sha256"),
                    text(item, "availability"), text(item, "reason")));
        }
        return Collections.unmodifiableList(values);
    }

    /** 写回同一raw.meta封套，消息快照和发送投影共用附件格式。 */
    public static JsonObject meta(List<DesktopAttachment> attachments) {
        JsonArray values = new JsonArray();
        for (DesktopAttachment item : attachments) values.add(item.toJson());
        JsonObject payload = new JsonObject(); payload.add("attachments", values);
        JsonObject happier = new JsonObject(); happier.addProperty("kind", "attachments.v1"); happier.add("payload", payload);
        JsonObject meta = new JsonObject(); meta.add("happier", happier);
        return meta;
    }

    /** 待发只引用手机私有暂存文件；上传结果不改写原发送内容身份。 */
    public static final class Pending {
        public final String localPath, name, kind, mimeType, sha256;
        public final long sizeBytes;

        /** 由选择文件的原owner传入暂存路径与字节摘要，不读取content URI或执行上传。 */
        public Pending(String localPath, String name, String kind, String mimeType, long sizeBytes, String sha256) {
            validate(name, kind, sizeBytes, sha256);
            if (localPath == null || !new File(localPath).isAbsolute() || sha256 == null)
                throw new IllegalArgumentException("待发附件本地身份不完整");
            this.localPath = localPath; this.name = name; this.kind = kind; this.mimeType = mimeType;
            this.sizeBytes = sizeBytes; this.sha256 = sha256;
        }

        /** 电脑已校验的上传结果必须与原件匹配，再生成本次发送的电脑附件引用。 */
        public DesktopAttachment uploaded(String remotePath, long verifiedSize, String verifiedSha256) {
            if (sizeBytes != verifiedSize || !sha256.equalsIgnoreCase(verifiedSha256))
                throw new IllegalArgumentException("上传结果与待发附件不一致");
            return new DesktopAttachment(name, kind, remotePath, mimeType, sizeBytes, sha256, null, null);
        }

        /** 原待发文件只增加小型描述，不复制附件字节到JSON。 */
        public JsonObject toJson() {
            JsonObject value = new JsonObject(); value.addProperty("localPath", localPath);
            value.addProperty("name", name); value.addProperty("kind", kind); value.addProperty("mimeType", mimeType);
            value.addProperty("sizeBytes", sizeBytes); value.addProperty("sha256", sha256);
            return value;
        }

        /** 重开沿原Outbox读取同一附件身份，不自动重传。 */
        public static Pending read(JsonObject value) {
            Long size = number(value, "sizeBytes");
            if (size == null) throw new IllegalArgumentException("待发附件大小缺失");
            return new Pending(text(value, "localPath"), text(value, "name"), text(value, "kind"),
                    text(value, "mimeType"), size, text(value, "sha256"));
        }

        /** 同发送编号必须指向同一组原件，不能用新文件覆盖失败消息。 */
        @Override public boolean equals(Object other) {
            if (!(other instanceof Pending)) return false;
            Pending value = (Pending) other;
            return sizeBytes == value.sizeBytes && localPath.equals(value.localPath) && name.equals(value.name)
                    && kind.equals(value.kind) && Objects.equals(mimeType, value.mimeType) && sha256.equals(value.sha256);
        }

        /** 与附件身份比较采用相同字段，不以文件名代替内容身份。 */
        @Override public int hashCode() { return Objects.hash(localPath, name, kind, mimeType, sizeBytes, sha256); }
    }

    /** 描述字段遵守已有传输语义，不增加文件数量、扩展名或大小策略。 */
    private static void validate(String name, String kind, Long size, String sha) {
        if (name == null || name.isEmpty() || !("image".equals(kind) || "file".equals(kind))
                || size != null && size < 0 || sha != null && !sha.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("附件描述无效");
    }

    /** 缺失可选字段保持null，已提供字段必须是原字符串。 */
    private static String text(JsonObject value, String key) {
        JsonElement field = value.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("附件文字字段无效");
        return field.getAsString();
    }

    /** 字节数只接受非负整数，不把小数或溢出截成另一个文件大小。 */
    private static Long number(JsonObject value, String key) {
        JsonElement field = value.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("附件大小无效");
        try {
            long result = field.getAsBigDecimal().longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException error) { throw new IllegalArgumentException("附件大小无效", error); }
    }
}
