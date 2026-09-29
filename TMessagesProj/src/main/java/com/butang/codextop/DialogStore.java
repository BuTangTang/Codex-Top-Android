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
    public DialogStore(File root, String server, String account) {
        file = new File(root, TranscriptStore.digest(server + "\n" + account) + ".json");
    }
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
    public JsonObject read() throws IOException {
        if (!file.exists()) return null;
        try {
            JsonObject snapshot = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            validate(snapshot);
            return snapshot;
        } catch (RuntimeException error) { throw new IOException("会话缓存无法读取", error); }
    }
    public void write(JsonObject snapshot) throws IOException {
        validate(snapshot);
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = new File(file.getPath() + ".tmp");
        try {
            Files.write(temporary.toPath(), snapshot.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
