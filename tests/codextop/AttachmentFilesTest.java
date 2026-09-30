package com.butang.codextop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** 使用独立合成目录检查暂存原件、同编号冲突与大文件流式处理。 */
public final class AttachmentFilesTest {
    /** 原件删除后待发仍可读取，重复编号不覆盖，文件大于测试堆仍可暂存。 */
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("codex-attachment-files");
        try {
            File source = root.resolve("source.txt").toFile();
            Files.write(source.toPath(), new byte[] { 1, 2, 3 });
            File scope = root.resolve("private-scope").toFile();
            DesktopAttachment.Pending pending = AttachmentFiles.stage(source, "report:2026.txt", "file", "text/plain", scope, "send-1");
            File staged = new File(pending.localPath);
            check(staged.isFile() && staged.getName().equals("report:2026.txt") && pending.sizeBytes == 3, "原件暂存信息错误");
            check(AttachmentFiles.stage(source, pending.name, pending.kind, pending.mimeType, scope, "send-1").equals(pending), "同编号同原件未复用");
            Files.write(source.toPath(), new byte[] { 9, 8, 7 });
            try {
                AttachmentFiles.stage(source, pending.name, pending.kind, pending.mimeType, scope, "send-1");
                throw new AssertionError("同编号不同内容覆盖原件");
            } catch (IOException expected) { }
            Files.delete(source.toPath());
            DesktopAttachment.Pending reopened = DesktopAttachment.Pending.read(pending.toJson());
            check(Arrays.equals(Files.readAllBytes(new File(reopened.localPath).toPath()), new byte[] { 1, 2, 3 }), "原件移除或冲突后重试原件丢失");
            check(AttachmentFiles.stage(staged, reopened.name, reopened.kind, reopened.mimeType, scope, "send-1").equals(reopened),
                    "重开后使用私有原件重试改变了发送身份");
            check(!AttachmentFiles.target(root.resolve("other-scope").toFile(), "send-1", pending.name).equals(staged), "不同scope共用原件路径");
            check(AttachmentFiles.target(scope, "download-1", "../../reply.txt").getName().equals("reply.txt"), "下载名字穿出目标目录");
            File large = root.resolve("large.bin").toFile();
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i * 31);
            MessageDigest expectedHash = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream output = new FileOutputStream(large)) {
                for (int i = 0; i < 640; i++) { output.write(chunk); expectedHash.update(chunk); }
                output.write(new byte[] { 5, 4, 3 }); expectedHash.update(new byte[] { 5, 4, 3 });
            }
            DesktopAttachment.Pending largePending = AttachmentFiles.stage(large, "large.bin", "file", null, scope, "send-large");
            check(largePending.sizeBytes == 40L * 1024 * 1024 + 3 && largePending.sha256.equals(hex(expectedHash.digest()))
                    && new File(largePending.localPath).length() == largePending.sizeBytes, "大文件分块长度或摘要不一致");
            try (var paths = Files.walk(scope.toPath())) {
                check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".stage-")), "暂存结束遗留半文件");
            }
            System.out.println("AttachmentFiles: 原件移除后重试、编号冲突、scope隔离、冒号文件名与40MiB流式暂存通过");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 使用独立摘要预期核对完整文件，不以暂存实现自己的返回值为预期。 */
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    /** 条件偏离时终止合成验证，临时目录仍会清理。 */
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
