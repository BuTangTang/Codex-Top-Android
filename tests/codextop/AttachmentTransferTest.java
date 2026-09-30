package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Java 与真实 TypeScript bulk handler 通过独立子进程互通，不调用 daemon、Codex 或真实账号。 */
public final class AttachmentTransferTest {
    /** 上传由 CLI 解密落盘，下载由 CLI 加密并经 Java 校验；失败时必须终止并保留原有效目标。 */
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("codextop-android-attachment-test-").toFile();
        try (NodeRpc rpc = new NodeRpc(args[0], args[1], directory)) {
            String cwd = rpc.call("test.info", new JsonObject()).get("workingDirectory").getAsString();
            File source = new File(directory, "same-name.bin"), target = new File(directory, "saved.bin");
            ArrayList<Long> progress = new ArrayList<>();
            AttachmentTransfer transfer = new AttachmentTransfer(rpc, (done, total) -> {
                if (done < 0 || done > total || !progress.isEmpty() && done < progress.get(progress.size() - 1)) throw new AssertionError("进度回退");
                progress.add(done);
            });
            String previousPath = null;
            AttachmentTransfer.Result large = null;
            for (int size : new int[]{0, 104, 630000, 2200123}) {
                byte[] bytes = new byte[size];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 73 + 29);
                Files.write(source.toPath(), bytes);
                progress.clear();
                AttachmentTransfer.Result uploaded = transfer.upload(source, size == 104 ? "image" : "file", "message-" + size, cwd);
                check(uploaded.sizeBytes == size && uploaded.sha256.equals(hash(bytes)), "Java 上传的真实 CLI 字节或 SHA 不同");
                check(!uploaded.path.equals(previousPath), "同名附件覆盖"); previousPath = uploaded.path;
                check(progress.get(progress.size() - 1) == size, "上传进度未完成");
                Files.write(target.toPath(), new byte[]{9, 8, 7});
                progress.clear();
                AttachmentTransfer.Result downloaded = transfer.download(uploaded.path, (long) size, uploaded.sha256, target);
                check(Arrays.equals(Files.readAllBytes(target.toPath()), bytes), "CLI 加密下载经 Java 得到不同字节");
                check(downloaded.sizeBytes == size && downloaded.sha256.equals(hash(bytes)), "下载结果未完整校验");
                check(progress.get(progress.size() - 1) == size, "下载进度未完成");
                large = uploaded;
            }
            final AttachmentTransfer.Result attachment = large;
            byte[] previous = {6, 2, 8, 4};
            Files.write(target.toPath(), previous);
            AttachmentTransfer plain = new AttachmentTransfer(rpc, null);
            failsPreserving(() -> plain.download(attachment.path, attachment.sizeBytes + 1, attachment.sha256, target), target, previous);
            failsPreserving(() -> plain.download(attachment.path, attachment.sizeBytes, "0".repeat(64), target), target, previous);
            for (String fault : new String[]{"download_ciphertext", "download_finalize", "download_chunk_size", "download_early_last"}) {
                JsonObject setting = new JsonObject(); setting.addProperty("kind", fault); rpc.call("test.fault", setting);
                failsPreserving(() -> plain.download(attachment.path, attachment.sizeBytes, attachment.sha256, target), target, previous);
            }
            // 进度回调取消既有线程：不得额外拉下一块，也不得留下 .part 文件。
            AttachmentTransfer cancelling = new AttachmentTransfer(rpc, (done, total) -> { if (done > 0) Thread.currentThread().interrupt(); });
            try { failsPreserving(() -> cancelling.download(attachment.path, attachment.sizeBytes, attachment.sha256, target), target, previous); }
            finally { Thread.interrupted(); }
            JsonObject fault = new JsonObject(); fault.addProperty("kind", "upload_hash"); rpc.call("test.fault", fault);
            try { plain.upload(source, "file", "bad-hash", cwd); throw new AssertionError("错误上传摘要通过"); }
            catch (IOException expected) { }
            File oversized = new File(directory, "oversized.bin");
            try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) { file.setLength(50L * 1024 * 1024 + 1); }
            try { plain.upload(oversized, "file", "too-large", cwd); throw new AssertionError("电脑大小限制未执行"); }
            catch (IOException expected) { check(expected.getMessage().contains("size limit"), "不是电脑既有大小限制"); }
            check(rpc.call("test.projectUntouched", new JsonObject()).get("success").getAsBoolean(), "写入了项目或忽略规则");
            JsonObject counts = rpc.call("test.info", new JsonObject()).getAsJsonObject("counts");
            check(counts.get("daemon.bulkTransfer.upload.abort").getAsInt() >= 1, "上传失败未中止");
            check(counts.get("daemon.bulkTransfer.download.abort").getAsInt() >= 7, "下载失败未中止");
            check(counts.get("daemon.bulkTransfer.upload.chunk").getAsInt() > 4, "上传未覆盖多块");
            check(counts.get("daemon.bulkTransfer.download.chunk").getAsInt() > 4, "下载未覆盖多块");
            System.out.println("AttachmentTransfer: Java↔TypeScript 真实 handler 双向字节/SHA、多块、空文件、同名隔离、失败原件保护、取消、大小限制与临时文件清理通过");
            System.out.println("RPC counts: " + counts);
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); } });
            }
        }
    }

    /** 失败只清理当前临时文件，不覆盖此前有效缓存。 */
    private static void failsPreserving(Attempt action, File target, byte[] expected) throws Exception {
        try { action.run(); throw new AssertionError("失败场景被接受"); } catch (IOException error) { }
        check(Arrays.equals(Files.readAllBytes(target.toPath()), expected), "破坏了旧有效文件");
        File[] temporary = target.getParentFile().listFiles((dir, name) -> name.endsWith(".part"));
        check(temporary != null && temporary.length == 0, "残留下载临时文件");
    }
    /** 合成测试只在预期条件成立时继续。 */
    private static void check(boolean result, String reason) { if (!result) throw new AssertionError(reason); }
    /** 独立计算合成文件摘要，用于检查真实 handler 结果。 */
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    /** 声明可失败的合成下载步骤。 */
    private interface Attempt { void run() throws Exception; }

    /** 子进程只加载 CLI 实现与合成工作目录，JSON 行作为独立语言边界。 */
    private static final class NodeRpc implements AttachmentTransfer.Rpc, AutoCloseable {
        private final Process process;
        private final BufferedReader input;
        private final BufferedWriter output;
        /** 显式传入 node 与桥接脚本位置，不依赖真实 daemon 或桌面凭据。 */
        private NodeRpc(String node, String bridge, File directory) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(node, bridge).redirectError(ProcessBuilder.Redirect.INHERIT);
            builder.environment().put("HAPPIER_HOME_DIR", new File(directory, "test-home").getPath());
            process = builder.start();
            input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
        /** 每条合成 RPC 等待真实 handler 的独立响应，不在 Java 内镜像服务端实现。 */
        @Override public JsonObject call(String method, JsonObject params) throws IOException {
            JsonObject request = new JsonObject(); request.addProperty("method", method); request.add("params", params);
            output.write(request.toString()); output.newLine(); output.flush();
            String line;
            while ((line = input.readLine()) != null) if (line.startsWith("RPC ")) return JsonParser.parseString(line.substring(4)).getAsJsonObject();
            throw new IOException("合成 TypeScript handler 已结束");
        }
        /** 让真实 handler 释放传输与本测试临时目录，再等待子进程退出。 */
        @Override public void close() throws Exception {
            try { call("test.close", new JsonObject()); } finally { output.close(); }
            check(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "TypeScript 测试进程未退出");
            check(process.exitValue() == 0, "TypeScript 测试进程失败");
        }
    }
}
