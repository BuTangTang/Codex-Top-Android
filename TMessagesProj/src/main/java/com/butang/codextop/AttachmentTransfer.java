package com.butang.codextop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** 沿已认证机器 RPC 分块收发附件；调用方负责工作队列、原会话归属及缓存位置。 */
public final class AttachmentTransfer {
    /** 由既有电脑连接提供定向 RPC，本层不建立第二条连接或账号状态。 */
    public interface Rpc {
        /** 向当前绑定的电脑发送一个既有传输请求。 */
        JsonObject call(String method, JsonObject params) throws Exception;
    }
    /** 进度在调用传输的工作线程回调，由界面 owner 决定如何发布。 */
    public interface Progress {
        /** 已完成字节与电脑确认的总字节，不把上传完成当作消息已发送。 */
        void onProgress(long transferred, long total);
    }
    /** 校验完成的文件信息；上传路径属于电脑，下载路径属于手机的指定目标。 */
    public static final class Result {
        public final String fileName, path, sha256;
        public final long sizeBytes;
        /** 只在完成字节校验后构造结果。 */
        private Result(String fileName, String path, long sizeBytes, String sha256) {
            this.fileName = fileName; this.path = path; this.sizeBytes = sizeBytes; this.sha256 = sha256;
        }
    }
    private final Rpc rpc;
    private final Progress progress;

    /** 接收既有连接与可选进度回调，不持有账号凭据或添加后台任务。 */
    public AttachmentTransfer(Rpc rpc, Progress progress) {
        this.rpc = java.util.Objects.requireNonNull(rpc); this.progress = progress;
    }

    /** 上传当前本地文件到电脑临时目录；失败只终止本传输，保留本地原件供原发送流程处理。 */
    public Result upload(File file, String kind, String localId, String cwd) throws IOException {
        if (!("image".equals(kind) || "file".equals(kind)) || localId == null || localId.isEmpty()
                || !absolute(cwd) || file == null || !file.isFile()) throw new IOException("附件上传信息不完整");
        long size = file.length();
        JsonObject params = new JsonObject();
        params.addProperty("t", "session_attachment_upload_v1"); params.addProperty("messageLocalId", localId);
        params.addProperty("fileName", file.getName()); params.addProperty("sizeBytes", size);
        params.addProperty("workspaceRootPath", cwd); params.addProperty("uploadLocation", "os_temp");
        params.addProperty("vcsIgnoreStrategy", "none"); params.addProperty("vcsIgnoreWritesEnabled", false);
        JsonObject init = call("upload.init", params);
        String id = string(init, "uploadId");
        boolean complete = false;
        try {
            int chunkSize = chunkSize(init);
            String recipient = string(init, "recipientPublicKeyBase64");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[chunkSize];
            long sent = 0;
            updateProgress(0, size);
            try (FileInputStream input = new FileInputStream(file)) {
                for (int index = 0; sent < size; index++) {
                    checkInterrupted();
                    int wanted = (int) Math.min(buffer.length, size - sent), count = 0;
                    while (count < wanted) {
                        int read = input.read(buffer, count, wanted - count);
                        if (read < 0) throw new IOException("上传期间附件长度已变化");
                        count += read;
                    }
                    JsonObject chunk = BulkTransferCrypto.encrypt(id, index, buffer, count, recipient);
                    chunk.addProperty("uploadId", id); chunk.addProperty("index", index);
                    call("upload.chunk", chunk);
                    digest.update(buffer, 0, count); sent += count;
                    updateProgress(sent, size);
                }
                if (input.read() != -1) throw new IOException("上传期间附件长度已变化");
            } finally { Arrays.fill(buffer, (byte) 0); }
            checkInterrupted();
            String hash = hex(digest.digest());
            JsonObject done = call("upload.finalize", identity("uploadId", id));
            String path = string(done, "path");
            if (number(done, "sizeBytes") != size || !hash.equals(string(done, "sha256")) || !absolute(path))
                throw new IOException("附件上传完成校验不一致");
            complete = true;
            return new Result(file.getName(), path, size, hash);
        } catch (Exception error) { throw failure(error); }
        finally { if (!complete) abort("upload", id); }
    }

    /** 下载到同目录临时文件，字节、摘要及电脑 finalize 均成功后才原子替换目标；失败不破坏旧文件。 */
    public Result download(String path, Long expectedSize, String expectedSha256, File target) throws IOException {
        if (!absolute(path) || target == null || expectedSize != null && expectedSize < 0)
            throw new IOException("附件下载信息不完整");
        String id = null;
        File temporary = null;
        boolean complete = false;
        try (BulkTransferCrypto.Recipient recipient = BulkTransferCrypto.createRecipient()) {
            JsonObject params = new JsonObject();
            params.addProperty("t", "session_file_download_v1"); params.addProperty("path", path);
            params.addProperty("recipientPublicKeyBase64", recipient.publicKeyBase64);
            JsonObject init = call("download.init", params);
            id = string(init, "downloadId");
            int chunkSize = chunkSize(init);
            long size = number(init, "sizeBytes");
            String name = string(init, "name");
            if (expectedSize != null && expectedSize != size) throw new IOException("附件大小已变化");
            File destination = target.getAbsoluteFile();
            Files.createDirectories(destination.getParentFile().toPath());
            temporary = File.createTempFile("codex-attachment-", ".part", destination.getParentFile());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            updateProgress(0, size);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                for (int index = 0; ; index++) {
                    checkInterrupted();
                    JsonObject request = identity("downloadId", id); request.addProperty("index", index);
                    JsonObject chunk = call("download.chunk", request);
                    byte[] bytes = BulkTransferCrypto.decrypt(id, index, string(chunk, "payloadBase64"),
                            string(chunk, "encryptedDataKeyEnvelopeBase64"), recipient);
                    try {
                        boolean last = bool(chunk, "isLast");
                        if (bytes.length > chunkSize || bytes.length > size - received || !last && bytes.length == 0)
                            throw new IOException("附件下载分块长度不一致");
                        output.write(bytes); digest.update(bytes); received += bytes.length;
                        updateProgress(received, size);
                        if (last) break;
                    } finally { Arrays.fill(bytes, (byte) 0); }
                }
                output.getFD().sync();
            }
            String hash = hex(digest.digest());
            if (received != size || expectedSha256 != null && !hash.equalsIgnoreCase(expectedSha256))
                throw new IOException("附件下载内容校验不一致");
            checkInterrupted();
            call("download.finalize", identity("downloadId", id));
            checkInterrupted();
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            complete = true;
            return new Result(name, destination.getPath(), size, hash);
        } catch (Exception error) { throw failure(error); }
        finally {
            if (!complete && id != null) abort("download", id);
            if (temporary != null) Files.deleteIfExists(temporary.toPath());
        }
    }

    /** 仅接受电脑明确成功的原传输响应，失败保留其错误原因，不重试请求。 */
    private JsonObject call(String suffix, JsonObject params) throws IOException {
        checkInterrupted();
        try {
            JsonObject response = rpc.call("daemon.bulkTransfer." + suffix, params);
            if (response == null || !bool(response, "success")) {
                String reason = response != null && response.has("error") && response.get("error").isJsonPrimitive()
                        ? response.get("error").getAsString() : "电脑未确认附件操作";
                throw new IOException(reason);
            }
            return response;
        } catch (Exception error) { throw failure(error); }
    }

    /** 失败后的 abort 只释放当前传输；清理失败不覆盖最初错误，也不重发附件或消息。 */
    private void abort(String direction, String id) {
        try { rpc.call("daemon.bulkTransfer." + direction + ".abort", identity(direction + "Id", id)); }
        catch (Exception ignored) { }
    }

    /** 进度使用累计已传字节，零字节文件也有明确的开始回调。 */
    private void updateProgress(long done, long total) { if (progress != null) progress.onProgress(done, total); }

    /** 沿电脑返回的分块大小工作，并保持现有协议的 1 MiB 硬上限。 */
    private static int chunkSize(JsonObject value) throws IOException {
        long size = number(value, "chunkSizeBytes");
        if (size == 0 || size > BulkTransferCrypto.MAX_CHUNK_BYTES) throw new IOException("附件分块大小无效");
        return (int) size;
    }

    /** 构造已有协议中的 uploadId 或 downloadId，不混用不同方向的身份。 */
    private static JsonObject identity(String key, String value) { JsonObject result = new JsonObject(); result.addProperty(key, value); return result; }

    /** 仅接受协议的非空字符串，避免把缺失字段当作成功路径。 */
    private static String string(JsonObject value, String key) throws IOException {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString() || field.getAsString().isEmpty())
            throw new IOException("附件响应缺少 " + key);
        return field.getAsString();
    }

    /** 字节数与分块大小必须是非负整数，不截断小数或溢出值。 */
    private static long number(JsonObject value, String key) throws IOException {
        try {
            JsonElement field = value.get(key);
            if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long result = field.getAsBigDecimal().longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (RuntimeException error) { throw new IOException("附件响应数值无效", error); }
    }

    /** 协议布尔值必须明确给出，不把缺失的末块标记解释为继续成功。 */
    private static boolean bool(JsonObject value, String key) throws IOException {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isBoolean())
            throw new IOException("附件响应状态无效");
        return field.getAsBoolean();
    }

    /** 手机只携带电脑实际返回的绝对路径，兼容 macOS/Linux 与 Windows 电脑。 */
    private static boolean absolute(String path) {
        return path != null && (path.startsWith("/") || path.startsWith("\\\\")
                || path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && (path.charAt(2) == '/' || path.charAt(2) == '\\'));
    }

    /** 保留调用线程的取消标记，并由外层关闭本次传输。 */
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("附件传输已取消");
    }

    /** 把加密、RPC 或文件系统错误作为本次传输失败返回，不伪造完成。 */
    private static IOException failure(Exception error) {
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        return error instanceof IOException ? (IOException) error : new IOException("附件传输失败", error);
    }

    /** 输出与电脑 finalize 一致的小写 SHA-256 字符串。 */
    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { result[i * 2] = digits[(bytes[i] & 255) >>> 4]; result[i * 2 + 1] = digits[bytes[i] & 15]; }
        return new String(result);
    }
}
