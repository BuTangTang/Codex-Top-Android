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
    /** 仅保存已确认的附件大小拒绝，不持久化服务器错误正文。 */
    public static final String FAILURE_FILE_TOO_LARGE = "file_too_large";
    private static final Object LOCK = new Object();
    private final File directory;
    /** 原选择描述没有字节校验含义；路径丢失也保留条目，不伪造暂存摘要。 */
    public static final class Selection {
        public final String localPath, name, kind, mimeType;
        /** 只接受选择器提供的真实本地路径；缺失来源用空值表示，不存内容URI。 */
        public Selection(String localPath, String name, String kind, String mimeType) {
            if (localPath != null && !new File(localPath).isAbsolute() || name == null || name.isEmpty()
                    || !("image".equals(kind) || "file".equals(kind))) throw new IllegalArgumentException("附件选择信息无效");
            this.localPath = localPath; this.name = name; this.kind = kind; this.mimeType = mimeType;
        }
        /** 选择阶段只保存来源描述，不能当作已经完成的附件。 */
        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            if (localPath != null) value.addProperty("localPath", localPath);
            value.addProperty("name", name); value.addProperty("kind", kind);
            if (mimeType != null) value.addProperty("mimeType", mimeType);
            return value;
        }
        /** 按原字段恢复未暂存条目；不因文件消失删除选择。 */
        private static Selection read(JsonObject value) {
            return new Selection(value.has("localPath") ? value.get("localPath").getAsString() : null,
                    value.get("name").getAsString(), value.get("kind").getAsString(),
                    value.has("mimeType") ? value.get("mimeType").getAsString() : null);
        }
        /** 同发送编号不能替换尚未暂存的选择来源。 */
        @Override public boolean equals(Object other) {
            if (!(other instanceof Selection)) return false;
            Selection value = (Selection) other;
            return java.util.Objects.equals(localPath, value.localPath) && name.equals(value.name)
                    && kind.equals(value.kind) && java.util.Objects.equals(mimeType, value.mimeType);
        }
        /** 选择身份比较使用相同字段。 */
        @Override public int hashCode() { return java.util.Objects.hash(localPath, name, kind, mimeType); }
    }

    public static final class Item {
        public final String localId, remote, text;
        public final int messageId, date;
        public final java.util.List<Selection> selections;
        public final java.util.List<DesktopAttachment.Pending> attachments;
        public final java.util.List<DesktopAttachment> uploaded;
        public final boolean submissionUncertain;
        /** 仅真实成功ACK可写入；回显前冷恢复仍保留电脑已接受的事实。 */
        public final boolean submissionAccepted;
        /** 可选失败解释与原待发记录同存，不能用来改变消息身份。 */
        public final String failureCode;
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
            this(localId, remote, text, messageId, date, attachments, uploaded, false);
        }
        /** 发送前持久化不确定状态；只有可靠未派发才能重新允许提交原身份。 */
        public Item(String localId, String remote, String text, int messageId, int date,
                java.util.List<DesktopAttachment.Pending> attachments, java.util.List<DesktopAttachment> uploaded,
                boolean submissionUncertain) {
            this(localId, remote, text, messageId, date, attachments, uploaded, submissionUncertain, java.util.Collections.emptyList(), null, false);
        }
        /** 一条待发记录同时持有原选择与已校验暂存前缀，网络阶段不得早于全部暂存。 */
        private Item(String localId, String remote, String text, int messageId, int date,
                java.util.List<DesktopAttachment.Pending> attachments, java.util.List<DesktopAttachment> uploaded,
                boolean submissionUncertain, java.util.List<Selection> selections, String failureCode, boolean submissionAccepted) {
            if (localId == null || localId.isEmpty() || remote == null || remote.isEmpty()
                    || attachments == null || attachments.stream().anyMatch(java.util.Objects::isNull)
                    || selections == null || selections.stream().anyMatch(java.util.Objects::isNull)
                    || (text == null || text.isEmpty()) && attachments.isEmpty() && selections.isEmpty()
                    || messageId >= 0 || date <= 0)
                throw new IllegalArgumentException("待发消息数据不完整");
            this.localId = localId; this.remote = remote; this.text = text == null ? "" : text;
            this.messageId = messageId; this.date = date;
            this.attachments = java.util.Collections.unmodifiableList(new ArrayList<>(attachments));
            this.selections = java.util.Collections.unmodifiableList(new ArrayList<>(selections));
            if (!selections.isEmpty()) {
                if (attachments.size() > selections.size()) throw new IllegalArgumentException("暂存附件数量不匹配");
                for (int i = 0; i < attachments.size(); i++) {
                    DesktopAttachment.Pending staged = attachments.get(i);
                    Selection selected = selections.get(i);
                    if (!staged.name.equals(selected.name) || !staged.kind.equals(selected.kind)
                            || !java.util.Objects.equals(staged.mimeType, selected.mimeType))
                        throw new IllegalArgumentException("暂存附件与原选择不匹配");
                }
            }
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
            if (!isPrepared() && (submissionUncertain || submissionAccepted || !uploaded.isEmpty()))
                throw new IllegalArgumentException("附件尚未全部暂存");
            this.uploaded = java.util.Collections.unmodifiableList(new ArrayList<>(uploaded));
            this.submissionAccepted = submissionAccepted;
            this.submissionUncertain = !submissionAccepted && submissionUncertain;
            if (failureCode != null && !FAILURE_FILE_TOO_LARGE.equals(failureCode))
                throw new IllegalArgumentException("待发失败解释无效");
            // 已接受或结果未知都不能继续使用此前的上传拒绝解释。
            this.failureCode = submissionUncertain || submissionAccepted ? null : failureCode;
        }
        /** 首次暂存前就保存完整选择，文件稍后消失仍能恢复整批失败气泡。 */
        public static Item selected(String localId, String remote, String text, int messageId, int date,
                java.util.List<Selection> selections) {
            return new Item(localId, remote, text, messageId, date, java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(), false, selections, null, false);
        }
        /** 旧记录的附件已经全部暂存；新记录数量以不可变的完整选择为准。 */
        public int attachmentCount() { return selections.isEmpty() ? attachments.size() : selections.size(); }
        /** 仅全部原件已经校验的批次能进入上传和桌面发送。 */
        public boolean isPrepared() { return attachments.size() == attachmentCount(); }
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
                        || prior.messageId != item.messageId || prior.date != item.date || !prior.selections.equals(item.selections)
                        || !(item.attachments.isEmpty() && !item.selections.isEmpty()) && !prior.attachments.equals(item.attachments))
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
    /** 每件暂存后保存已验证前缀；之后失败不能丢掉完整选择或覆盖已确认原件。 */
    public void rememberStaged(String localId, java.util.List<DesktopAttachment.Pending> staged) throws IOException {
        synchronized (LOCK) {
            File target = file(localId);
            Item prior = read(target);
            if (prior.selections.isEmpty() && !prior.attachments.equals(staged)) throw new IOException("原附件批次已经固定");
            if (staged.size() < prior.attachments.size()) throw new IOException("暂存状态不能回退");
            for (int i = 0; i < prior.attachments.size(); i++)
                if (!prior.attachments.get(i).equals(staged.get(i))) throw new IOException("暂存原件身份冲突");
            write(target, new Item(prior.localId, prior.remote, prior.text, prior.messageId, prior.date,
                    staged, prior.uploaded, prior.submissionUncertain, prior.selections, prior.failureCode, prior.submissionAccepted));
        }
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
                    prior.attachments, uploaded, prior.submissionUncertain, prior.selections, prior.failureCode, prior.submissionAccepted));
        }
    }
    /** 与附件原件和上传路径同记录原子发布，重启也不能把未知结果当作可再次发送。 */
    public void markSubmissionUncertain(String localId, boolean uncertain) throws IOException {
        synchronized (LOCK) {
            File target = file(localId);
            Item prior = read(target);
            write(target, new Item(prior.localId, prior.remote, prior.text, prior.messageId, prior.date,
                    prior.attachments, prior.uploaded, uncertain, prior.selections, prior.failureCode, prior.submissionAccepted));
        }
    }

    /** 原记录收到成功ACK后原子保存；已由回显清理的记录不重新创建。 */
    public void markSubmissionAccepted(String localId) throws IOException {
        synchronized (LOCK) {
            File target = file(localId);
            if (!target.exists()) return;
            Item prior = read(target);
            write(target, new Item(prior.localId, prior.remote, prior.text, prior.messageId, prior.date,
                    prior.attachments, prior.uploaded, false, prior.selections, null, true));
        }
    }

    /** 只记入或清除固定解释，沿原记录原子写入；不存在的记录不得被复活。 */
    public void markFailureCode(String localId, String failureCode) throws IOException {
        if (failureCode != null && !FAILURE_FILE_TOO_LARGE.equals(failureCode))
            throw new IllegalArgumentException("待发失败解释无效");
        synchronized (LOCK) {
            File target = file(localId);
            Item prior = read(target);
            write(target, new Item(prior.localId, prior.remote, prior.text, prior.messageId, prior.date,
                    prior.attachments, prior.uploaded, prior.submissionUncertain, prior.selections, failureCode, prior.submissionAccepted));
        }
    }

    /** 同目录临时文件发布完整小型记录，不复制附件字节到JSON。 */
    private void write(File target, Item item) throws IOException {
            JsonObject value = new JsonObject();
            value.addProperty("version", 1); value.addProperty("localId", item.localId);
            value.addProperty("remote", item.remote); value.addProperty("text", item.text);
            value.addProperty("messageId", item.messageId); value.addProperty("date", item.date);
            if (item.submissionUncertain) value.addProperty("submissionUncertain", true);
            if (item.submissionAccepted) value.addProperty("submissionAccepted", true);
            if (item.failureCode != null) value.addProperty("failureCode", item.failureCode);
            if (!item.selections.isEmpty()) {
                com.google.gson.JsonArray selections = new com.google.gson.JsonArray();
                for (Selection selection : item.selections) selections.add(selection.toJson());
                value.add("selections", selections);
            }
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
            ArrayList<Selection> selections = new ArrayList<>();
            if (value.has("selections")) for (com.google.gson.JsonElement entry : value.getAsJsonArray("selections"))
                selections.add(Selection.read(entry.getAsJsonObject()));
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
            // 可选解释缺失、未知或格式错误只退回泛失败，不能因此丢掉原待发批次。
            String failureCode = value.has("failureCode") && value.get("failureCode").isJsonPrimitive()
                    && value.get("failureCode").getAsJsonPrimitive().isString()
                    && FAILURE_FILE_TOO_LARGE.equals(value.get("failureCode").getAsString()) ? FAILURE_FILE_TOO_LARGE : null;
            Item item = new Item(value.get("localId").getAsString(), value.get("remote").getAsString(),
                    value.get("text").getAsString(), value.get("messageId").getAsInt(), value.get("date").getAsInt(), attachments, uploaded,
                    value.has("submissionUncertain") && value.get("submissionUncertain").getAsBoolean(), selections, failureCode,
                    value.has("submissionAccepted") && value.get("submissionAccepted").isJsonPrimitive()
                            && value.getAsJsonPrimitive("submissionAccepted").isBoolean()
                            && value.get("submissionAccepted").getAsBoolean());
            if (!file.getName().equals(file(item.localId).getName())) throw new IllegalArgumentException();
            return item;
        } catch (RuntimeException error) { throw new IOException("待发消息无法读取", error); }
    }
}
