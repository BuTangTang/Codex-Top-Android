package com.butang.codextop;

import java.nio.file.Files;

/** 验证迁移旧编号、同会话跨电脑、哈希碰撞和倒序重启发现。 */
public final class DialogIdentityStoreTest {
    /** 全部使用临时目录和合成身份，不访问应用账号或真实对话。 */
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("codextop-identities-");
        try {
            var store = new DialogIdentityStore(root.toFile(), "server", "account");
            long old = store.bind("computer-a", "FB");
            if (old != 1000000000000L + Integer.toUnsignedLong("FB".hashCode()))
                throw new AssertionError("旧草稿编号改变");
            long collision = store.bind("computer-a", "Ea");
            long other = store.bind("computer-b", "FB");
            if (old == collision || old == other || collision == other) throw new AssertionError("对话串号");
            var reopened = new DialogIdentityStore(root.toFile(), "server", "account");
            if (reopened.bind("computer-b", "FB") != other || reopened.bind("computer-a", "Ea") != collision
                    || reopened.bind("computer-a", "FB") != old) throw new AssertionError("发现顺序改变编号");
            var independent = new DialogIdentityStore(root.toFile(), "server", "other-account");
            if (independent.bind("computer-b", "FB") != old) throw new AssertionError("账号共享了分配表");
            // 落盘失败不得消耗编号或留下仅在内存中存在的身份。
            var blocked = Files.createFile(root.resolve("not-a-directory"));
            var failed = new DialogIdentityStore(blocked.toFile(), "server", "account");
            try { failed.bind("computer-a", "FB"); throw new AssertionError("写盘失败仍返回编号"); }
            catch (java.io.IOException expected) { }
            Files.delete(blocked);
            if (failed.bind("computer-b", "FB") != old) throw new AssertionError("失败分配污染迁移来源");
            System.out.println("DialogIdentityStore: 旧编号兼容、跨电脑隔离、哈希碰撞、重启稳定、账号隔离、写失败回滚通过");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path);
            }
        }
    }
}
