package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/** 私有目录保存尚未收到桌面回显的文字与附件描述；恢复只展示，不自动投递。 */
public final class OutboxStore {
    private static final Object LOCK = new Object();
    private final File directory;
    public static final class Item {
        public final String localId, remote, text;
        public final int messageId, date;
        public final java.util.List<DesktopAttachment.Pending> attachments;
        public final java.util.List<DesktopAttachment> uploaded;
        /** 旧文字调用保持原构造方式与版本1记录兼容。 */
        public Item(String localId, String remote, String text, int messageId, int date) {
            this(localId, remote, text, messageId, date, java.util.Collections.emptyList());
        }
        /** 无说明的附件也属于单条消息，所有原件共享同一localId。 */
        public Item(String localId, String remote, String text, int messageId, int date,
                java.util.List<DesktopAttachment.Pending> attachments) {
            this(localId, remote, text, messageId, date, attachments, java.util.Collections.emptyList());
        }
        /** 已校验上传引用属于同一待发记录，重试不换路径或消息内容身份。 */
        public Item(String localId, String remote, String text, int messageId, int date,
                java.util.List<DesktopAttachment.Pending> attachments, java.util.List<DesktopAttachment> uploaded) {
            if (localId == null || localId.isEmpty() || remote == null || remote.isEmpty()
                    || attachments == null || attachments.stream().anyMatch(java.util.Objects::isNull)
                    || (text == null || text.isEmpty()) && attachments.isEmpty() || messageId >= 0 || date <= 0)
                throw new IllegalArgumentException("待发消息数据不完整");
            this.localId = localId; this.remote = remote; this.text = text == null ? "" : text;
            this.messageId = messageId; this.date = date;
            this.attachments = java.util.Collections.unmodifiableList(new ArrayList<>(attachments));
            if (uploaded == null || uploaded.size() > attachments.size()) throw new IllegalArgumentException("上传引用数量不匹配");
            for (int i = 0; i < uploaded.size(); i++) {
                DesktopAttachment value = uploaded.get(i);
                DesktopAttachment.Pending original = attachments.get(i);
                if (value == null || !value.isAvailable() || value.sizeBytes == null || value.sha256 == null
                        || !value.name.equals(original.name) || !value.kind.equals(original.kind)
                        || !java.util.Objects.equals(value.mimeType, original.mimeType))
                    throw new IllegalArgumentException("上传引用与原件不匹配");
                original.uploaded(value.path, value.sizeBytes, value.sha256);
            }
            this.uploaded = java.util.Collections.unmodifiableList(new ArrayList<>(uploaded));
        }
    }
    public OutboxStore(File root, String server, String account, String machine) {
        if (server == null || account == null || machine == null) throw new IllegalArgumentException("缺少账号或电脑");
        directory = new File(root, TranscriptStore.digest(server + "\n" + account + "\n" + machine));
    }
    /** 原发送编号不变；已有记录内容不同则拒绝覆盖。 */
    public void put(Item item) throws IOException {
        synchronized (LOCK) {
            Files.createDirectories(directory.toPath());
            File target = file(item.localId);
            if (target.exists()) {
                Item prior = read(target);
                if (!prior.remote.equals(item.remote) || !prior.text.equals(item.text)
                        || prior.messageId != item.messageId || prior.date != item.date || !prior.attachments.equals(item.attachments))
                    throw new IOException("发送编号冲突");
                return;
            }
            write(target, item);
        }
    }
    /** 只读本次发送原件及已校验引用；读取不自动执行投递。 */
    public Item get(String localId) throws IOException {
        synchronized (LOCK) { File target = file(localId); return target.exists() ? read(target) : null; }
    }
    /** 每件上传后原子保存，失败重试复用已上传前缀，不能替换已完成的电脑文件身份。 */
    public void rememberUploaded(String localId, java.util.List<DesktopAttachment> uploaded) throws IOException {
        synchronized (LOCK) {
            File target = file(localId);
            Item prior = read(target);
            if (uploaded.size() < prior.uploaded.size()) throw new IOException("上传状态不能回退");
            for (int i = 0; i < prior.uploaded.size(); i++)
                if (!prior.uploaded.get(i).toJson().equals(uploaded.get(i).toJson())) throw new IOException("上传引用冲突");
            write(target, new Item(prior.localId, prior.remote, prior.text, prior.messageId, prior.date,
                    prior.attachments, uploaded));
        }
    }
    /** 同目录临时文件发布完整小型记录，不复制附件字节到JSON。 */
    private void write(File target, Item item) throws IOException {
            JsonObject value = new JsonObject();
            value.addProperty("version", 1); value.addProperty("localId", item.localId);
            value.addProperty("remote", item.remote); value.addProperty("text", item.text);
            value.addProperty("messageId", item.messageId); value.addProperty("date", item.date);
            if (!item.attachments.isEmpty()) {
                com.google.gson.JsonArray attachments = new com.google.gson.JsonArray();
                for (DesktopAttachment.Pending attachment : item.attachments) attachments.add(attachment.toJson());
                value.add("attachments", attachments);
            }
            if (!item.uploaded.isEmpty()) {
                com.google.gson.JsonArray uploaded = new com.google.gson.JsonArray();
                for (DesktopAttachment attachment : item.uploaded) uploaded.add(attachment.toJson());
                value.add("uploaded", uploaded);
            }
            File temporary = new File(directory, target.getName() + ".tmp");
            try {
                Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    public ArrayList<Item> list(String remote) throws IOException {
        synchronized (LOCK) {
            ArrayList<Item> result = new ArrayList<>();
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return result;
            for (File file : files) {
                Item item = read(file);
                if (item.remote.equals(remote)) result.add(item);
            }
            result.sort((a, b) -> a.date != b.date ? Integer.compare(b.date, a.date) : Integer.compare(a.messageId, b.messageId));
            return result;
        }
    }
    /** 只有已保存到历史缓存的同编号桌面回显才清理待发记录。 */
    public void remove(String localId) throws IOException {
        synchronized (LOCK) { Files.deleteIfExists(file(localId).toPath()); }
    }
    private File file(String localId) { return new File(directory, TranscriptStore.digest(localId) + ".json"); }
    private Item read(File file) throws IOException {
        try {
            JsonObject value = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            if (value.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            ArrayList<DesktopAttachment.Pending> attachments = new ArrayList<>();
            if (value.has("attachments")) for (com.google.gson.JsonElement entry : value.getAsJsonArray("attachments"))
                attachments.add(DesktopAttachment.Pending.read(entry.getAsJsonObject()));
            java.util.List<DesktopAttachment> uploaded = java.util.Collections.emptyList();
            if (value.has("uploaded")) {
                JsonObject raw = new JsonObject();
                JsonObject meta = new JsonObject(), happier = new JsonObject(), payload = new JsonObject();
                payload.add("attachments", value.getAsJsonArray("uploaded"));
                happier.addProperty("kind", "attachments.v1"); happier.add("payload", payload);
                meta.add("happier", happier); raw.add("meta", meta);
                uploaded = DesktopAttachment.readRaw(raw);
            }
            Item item = new Item(value.get("localId").getAsString(), value.get("remote").getAsString(),
                    value.get("text").getAsString(), value.get("messageId").getAsInt(), value.get("date").getAsInt(), attachments, uploaded);
            if (!file.getName().equals(file(item.localId).getName())) throw new IllegalArgumentException();
            return item;
        } catch (RuntimeException error) { throw new IOException("待发消息无法读取", error); }
    }
}
