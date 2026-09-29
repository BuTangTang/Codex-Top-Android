package com.butang.codextop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** 在 Android 实际 AtomicFile 上验证缺失、损坏备份与退出清理，不使用真实身份。 */
public final class SessionStoreFileTest {
    /** 参数为设备上独立临时目录；合成文件在结束后清理。 */
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        if (!root.mkdir()) throw new IOException("测试目录必须全新");
        File target = new File(root, "session");
        try {
            SessionStore store = new SessionStore(target);
            if (store.read() != null) throw new AssertionError("缺失文件应未登录");
            Files.write(new File(target + ".bak").toPath(), new byte[]{1, 2, 3});
            try {
                store.read();
                throw new AssertionError("备份被误判为缺失，没有恢复及校验");
            } catch (IOException expected) {
                if (!target.exists()) throw new AssertionError("没有调用系统备份恢复");
            }
            File history = new File(root, "history-sentinel");
            Files.write(history.toPath(), new byte[]{9});
            Files.write(new File(target + ".bak").toPath(), new byte[]{4});
            Files.write(new File(target + ".new").toPath(), new byte[]{5});
            store.clear();
            if (target.exists() || new File(target + ".bak").exists() || new File(target + ".new").exists())
                throw new AssertionError("退出留下可恢复凭据");
            if (new SessionStore(target).read() != null) throw new AssertionError("退出后重新读取仍登录");
            if (!history.exists() || Files.readAllBytes(history.toPath())[0] != 9)
                throw new AssertionError("误删聊天缓存");
            store.clear();
            // 非空目录模拟删除失败，不能静默确认退出。
            if (!target.mkdir()) throw new IOException("无法准备删除失败样例");
            File child = new File(target, "keep");
            Files.write(child.toPath(), new byte[]{6});
            try { store.clear(); throw new AssertionError("删除失败仍报告成功"); }
            catch (IOException expected) { }
            Files.delete(child.toPath());
            Files.delete(target.toPath());
            System.out.println("SessionStore: 缺失、备份恢复、凭据清理、重读、缓存保留和失败上报通过");
        } finally {
            for (File file : root.listFiles()) Files.delete(file.toPath());
            Files.delete(root.toPath());
        }
    }
}
