package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

/** 保存最近列表及其电脑归属；缓存仅用于浏览，不证明电脑在线。 */
public final class DialogStore {
    private final File file;
    /** 最近列表按服务和产品账号隔离，文件名不包含账号原文。 */
    public DialogStore(File root, String server, String account) {
        file = new File(root, TranscriptStore.digest(server + "\n" + account) + ".json");
    }
    /** 验证最近列表的必需身份，坏响应不能覆盖可用缓存。 */
    public static void validate(JsonObject snapshot) throws IOException {
        try {
            if (snapshot.get("machineId").getAsString().isEmpty() || !snapshot.get("candidates").isJsonArray())
                throw new IOException("会话缓存格式无效");
            for (com.google.gson.JsonElement value : snapshot.getAsJsonArray("candidates")) {
                JsonObject row = value.getAsJsonObject();
                if (row.get("remoteSessionId").getAsString().isEmpty()) throw new IOException("缺少会话身份");
                row.get("updatedAtMs").getAsLong();
                if (row.has("title")) row.get("title").getAsString();
            }
        } catch (RuntimeException error) { throw new IOException("会话缓存格式无效", error); }
    }
    /** 读取并验证最近列表，缺失文件返回空。 */
    public JsonObject read() throws IOException {
        JsonObject snapshot = readJson(file);
        if (snapshot != null) validate(snapshot);
        return snapshot;
    }
    /** 为同账号的浏览元数据复用原有 JSON 读取，不解释其中的状态为在线。 */
    static JsonObject readJson(File file) throws IOException {
        if (!file.exists()) return null;
        try {
            return JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) { throw new IOException("会话缓存无法读取", error); }
    }
    /** 原子保存已验证的最近列表。 */
    public void write(JsonObject snapshot) throws IOException {
        validate(snapshot);
        writeJson(file, snapshot);
    }
    /** 原调用不查守卫，写入和替换行为与以前相同。 */
    static void writeJson(File file, JsonObject snapshot) throws IOException {
        writeJson(file, snapshot, null);
    }

    /**
     * 仍是原来的一次原子写入。守卫只在写临时文件前、以及临时文件写完后、原子替换前各看一次。
     * 这不是跨线程身份锁，也不保证检查之后到替换完成之间的文件系统或整段队列。
     * 守卫拒绝时返回 false，原文件保留，临时文件由 finally 清掉。
     */
    static boolean writeJson(File file, JsonObject snapshot, BooleanSupplier stillCurrent) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = new File(file.getPath() + ".tmp");
        try {
            if (stillCurrent != null && !stillCurrent.getAsBoolean()) return false;
            Files.write(temporary.toPath(), snapshot.toString().getBytes(StandardCharsets.UTF_8));
            if (stillCurrent != null && !stillCurrent.getAsBoolean()) return false;
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
