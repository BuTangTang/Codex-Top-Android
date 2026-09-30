package com.butang.codextop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 仅在调用方已有私有目录内暂存原件与计算目标，不持有账号、连接或重试状态。 */
public final class AttachmentFiles {
    /** 暂存后原选择文件可被删除；同一发送身份只能复用相同原件，不能覆盖已有字节。 */
    public static synchronized DesktopAttachment.Pending stage(File source, String displayName, String kind,
            String mime, File directory, String identity) throws IOException {
        if (source == null || !source.isFile() || displayName == null || displayName.isEmpty()
                || !("image".equals(kind) || "file".equals(kind))) throw new IOException("附件原件不可读取");
        File destination = target(directory, identity, displayName);
        Files.createDirectories(destination.getParentFile().toPath());
        File[] existing = destination.getParentFile().listFiles();
        if (existing == null) throw new IOException("附件暂存目录不可读取");
        if (existing.length > 1 || existing.length == 1 && !existing[0].equals(destination))
            throw new IOException("发送编号对应的附件原件已存在");
        File temporary = File.createTempFile(".stage-", ".part", directory.getAbsoluteFile());
        try {
            Digest copied;
            try (FileOutputStream output = new FileOutputStream(temporary)) { copied = digest(source, output); }
            if (destination.exists()) {
                Digest prior = digest(destination, null);
                if (prior.size != copied.size || !prior.sha.equals(copied.sha))
                    throw new IOException("发送编号对应的附件内容已变化");
            } else {
                try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException error) { Files.move(temporary.toPath(), destination.toPath()); }
            }
            return new DesktopAttachment.Pending(destination.getAbsolutePath(), displayName, kind, mime, copied.size, copied.sha);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    /** 完成下载仍由原传输器写入此目标；这里只按既有scope与消息身份确定路径。 */
    public static File target(File directory, String identity, String displayName) throws IOException {
        if (directory == null || identity == null || identity.isEmpty()) throw new IOException("附件缓存身份缺失");
        String name = displayName == null ? "attachment" : new File(displayName).getName().replace('\\', '_');
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "attachment";
        return new File(new File(directory.getAbsoluteFile(), hex(sha256().digest(identity.getBytes(StandardCharsets.UTF_8)))), name);
    }

    /** 固定小块流式读取，摘要与大小对应实际暂存字节，不把整个附件装入内存。 */
    private static Digest digest(File source, FileOutputStream output) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        try (FileInputStream input = new FileInputStream(source)) {
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (output != null) output.write(buffer, 0, count);
                digest.update(buffer, 0, count); size += count;
            }
        }
        return new Digest(size, hex(digest.digest()));
    }

    /** 使用平台必备摘要算法；异常沿现有文件失败路径交回调用方。 */
    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("附件摘要不可用", error); }
    }

    /** 摘要只作为原件校验和目录键，不保存文件正文。 */
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }

    /** 一次流式读取的结果，不建立额外持久化记录。 */
    private static final class Digest {
        final long size;
        final String sha;
        /** 保留实际读取长度与对应摘要。 */
        Digest(long size, String sha) { this.size = size; this.sha = sha; }
    }
}
