package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
    /** 浏览缓存沿用临时文件加原子替换；失败不会先删除上一份快照。 */
    static void writeJson(File file, JsonObject snapshot) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = new File(file.getPath() + ".tmp");
        try {
            Files.write(temporary.toPath(), snapshot.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
