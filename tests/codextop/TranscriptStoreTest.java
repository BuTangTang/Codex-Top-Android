package com.butang.codextop;
import com.google.gson.JsonParser;
import java.nio.file.Files;

/** 独立临时目录验证冷启恢复、增量去重、账号隔离和坏缓存拒绝。 */
public final class TranscriptStoreTest {
    /** 验证重启增量与各来源缓存互不覆盖，测试只使用独立临时目录。 */
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("codex-history-test").toRealPath();
        try {
            var store = new TranscriptStore(root.toFile(), "server", "account-a", "machine");
            var history = new TranscriptWindow();
            history.prepend(JsonParser.parseString("{\"items\":[{\"id\":\"a\",\"localId\":\"send-a\",\"createdAtMs\":1000,\"raw\":{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"中文测试\"}}}],\"hasMore\":false,\"historyAvailability\":\"available\",\"tailCursor\":\"tail-a\"}").getAsJsonObject());
            store.write("thread", history);
            var restored = new TranscriptStore(root.toFile(), "server", "account-a", "machine").read("thread");
            var row = restored.before(0, 10).get(0);
            if (!restored.complete || !restored.loaded || !"tail-a".equals(restored.tailCursor)
                    || row.id != history.before(0, 1).get(0).id || !"send-a".equals(row.message.localId)
                    || !"中文测试".equals(row.message.text)) throw new AssertionError("冷启丢失内容或身份");
            var delta = JsonParser.parseString("{\"items\":[],\"nextCursor\":\"tail-b\"}").getAsJsonObject();
            var duplicate = history.snapshot().getAsJsonArray("rows").get(0).getAsJsonObject().get("item");
            delta.getAsJsonArray("items").add(duplicate);
            if (!restored.append(delta).isEmpty()) throw new AssertionError("恢复后重复气泡");
            store.write("thread", restored);
            if (!"tail-b".equals(store.read("thread").tailCursor)) throw new AssertionError("替换丢游标");
            if (!new TranscriptStore(root.toFile(), "server", "account-b", "machine").read("thread").before(0, 10).isEmpty())
                throw new AssertionError("账号缓存串用");
            // 同名会话在不同电脑或服务器上必须拥有独立正文及增量游标。
            for (String[] owner : new String[][]{{"server", "machine-b"}, {"server-b", "machine"}}) {
                var isolated = new TranscriptStore(root.toFile(), owner[0], "account-a", owner[1]);
                if (isolated.read("thread").loaded) throw new AssertionError("其他来源读到原正文");
                var separate = new TranscriptWindow();
                separate.prepend(JsonParser.parseString("{\"items\":[],\"hasMore\":false,\"tailCursor\":\"separate-tail\"}").getAsJsonObject());
                isolated.write("thread", separate);
                if (!"separate-tail".equals(isolated.read("thread").tailCursor)
                        || !"tail-b".equals(store.read("thread").tailCursor)
                        || !"中文测试".equals(store.read("thread").before(0, 1).get(0).message.text))
                    throw new AssertionError("其他来源写入覆盖原正文或游标");
            }
            var broken = history.snapshot();
            broken.getAsJsonArray("rows").add(broken.getAsJsonArray("rows").get(0).deepCopy());
            try { TranscriptWindow.restore(broken); throw new AssertionError("接受重复编号"); }
            catch (java.io.IOException expected) { }
            // 多账号保持隔离，但本机正文总预算共享；满额时只移除旧会话，不碰待发目录。
            var outbox = new OutboxStore(root.resolve("outbox").toFile(), "server", "a", "machine");
            outbox.put(new OutboxStore.Item("unsent", "first", "离线草稿", -9, 1000));
            var limitedRoot = root.resolve("limited");
            // 新格式容量包括索引和块：真实独立写入测量一份会话，不沿用旧v2正文长度猜预算。
            var sizingRoot = root.resolve("sizing");
            new TranscriptStore(sizingRoot.toFile(), "server", "a", "machine").write("sizing", TranscriptWindow.restore(history.snapshot()));
            long snapshotBytes;
            try (var paths = Files.walk(sizingRoot)) {
                snapshotBytes = paths.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
            }
            var limitedA = new TranscriptStore(limitedRoot.toFile(), "server", "a", "machine", snapshotBytes * 2);
            var limitedB = new TranscriptStore(limitedRoot.toFile(), "server", "b", "machine", snapshotBytes * 2);
            limitedA.write("first", TranscriptWindow.restore(history.snapshot()));
            try (var paths = Files.walk(limitedRoot)) {
                for (var path : paths.filter(Files::isRegularFile).toArray(java.nio.file.Path[]::new))
                    Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(1));
            }
            limitedB.write("second", TranscriptWindow.restore(history.snapshot()));
            limitedA.write("third", TranscriptWindow.restore(history.snapshot()));
            if (limitedA.read("first").loaded || !limitedB.read("second").loaded || !limitedA.read("third").loaded)
                throw new AssertionError("容量淘汰没有保留新正文或跨账号读取");
            if (limitedA.read("second").loaded) throw new AssertionError("淘汰后账号隔离失效");
            long diskBytes;
            try (var paths = Files.walk(limitedRoot)) {
                diskBytes = paths.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
            }
            if (outbox.list("first").size() != 1 || !"离线草稿".equals(outbox.list("first").get(0).text))
                throw new AssertionError("正文淘汰影响未发送记录");
            if (diskBytes > snapshotBytes * 2) throw new AssertionError("缓存超过总预算");
            var tooSmall = new TranscriptStore(limitedRoot.toFile(), "server", "a", "machine", snapshotBytes - 1);
            try { tooSmall.write("third", limitedA.read("third")); throw new AssertionError("超大单会话被写入"); }
            catch (java.io.IOException expected) { }
            if (!limitedA.read("third").loaded) throw new AssertionError("超大写入破坏旧缓存");
            System.out.println("TranscriptStore: 冷启恢复、身份去重、原子替换、账号隔离、容量淘汰与待发保护通过");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path);
            }
        }
    }
}
