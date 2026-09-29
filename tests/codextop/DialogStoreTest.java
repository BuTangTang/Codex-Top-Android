package com.butang.codextop;
import java.nio.file.Files;
import com.google.gson.JsonParser;
public final class DialogStoreTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("codex-dialog-test");
        try {
            var store = new DialogStore(root.toFile(), "server", "a");
            var value = JsonParser.parseString("{\"machineId\":\"machine-a\",\"candidates\":[{\"remoteSessionId\":\"thread-a\",\"updatedAtMs\":1000,\"title\":\"测试\"}]}").getAsJsonObject();
            store.write(value);
            if (!new DialogStore(root.toFile(), "server", "a").read().equals(value)) throw new AssertionError("冷启列表丢失");
            if (new DialogStore(root.toFile(), "server", "b").read() != null) throw new AssertionError("账号串用");
            var invalid = value.deepCopy(); invalid.remove("machineId");
            try { store.write(invalid); throw new AssertionError("缺少电脑归属仍保存"); }
            catch (java.io.IOException expected) { }
            if (!store.read().equals(value)) throw new AssertionError("坏响应覆盖已有列表");
            System.out.println("DialogStore: 冷启恢复、账号隔离、归属验证与失败保留通过");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path);
            }
        }
    }
}
