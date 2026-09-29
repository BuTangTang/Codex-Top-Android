package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/** 私有目录保存尚未收到桌面回显的文字；恢复只展示，不自动投递。 */
public final class OutboxStore {
    private static final Object LOCK = new Object();
    private final File directory;
    public static final class Item {
        public final String localId, remote, text;
        public final int messageId, date;
        public Item(String localId, String remote, String text, int messageId, int date) {
            if (localId == null || localId.isEmpty() || remote == null || remote.isEmpty()
                    || text == null || text.isEmpty() || messageId >= 0 || date <= 0)
                throw new IllegalArgumentException("待发消息数据不完整");
            this.localId = localId; this.remote = remote; this.text = text;
            this.messageId = messageId; this.date = date;
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
                        || prior.messageId != item.messageId || prior.date != item.date)
                    throw new IOException("发送编号冲突");
                return;
            }
            JsonObject value = new JsonObject();
            value.addProperty("version", 1); value.addProperty("localId", item.localId);
            value.addProperty("remote", item.remote); value.addProperty("text", item.text);
            value.addProperty("messageId", item.messageId); value.addProperty("date", item.date);
            File temporary = new File(directory, target.getName() + ".tmp");
            try {
                Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary.toPath()); }
        }
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
            Item item = new Item(value.get("localId").getAsString(), value.get("remote").getAsString(),
                    value.get("text").getAsString(), value.get("messageId").getAsInt(), value.get("date").getAsInt());
            if (!file.getName().equals(file(item.localId).getName())) throw new IllegalArgumentException();
            return item;
        } catch (RuntimeException error) { throw new IOException("待发消息无法读取", error); }
    }
}
