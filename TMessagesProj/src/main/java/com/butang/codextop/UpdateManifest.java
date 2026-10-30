package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** 独立产品清单与有限下载规则，不访问账号、消息或 Android 安装状态。 */
public final class UpdateManifest {
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    public final String packageName, versionName, abi, apkUrl, sha256;
    public final long versionCode, sizeBytes;
    public final int minSdk;

    /** 只由完整校验后的清单构造不可变下载身份。 */
    private UpdateManifest(String packageName, long versionCode, String versionName, int minSdk,
            String abi, String apkUrl, long sizeBytes, String sha256) {
        this.packageName = packageName; this.versionCode = versionCode; this.versionName = versionName;
        this.minSdk = minSdk; this.abi = abi; this.apkUrl = apkUrl; this.sizeBytes = sizeBytes; this.sha256 = sha256;
    }

    /** 严格读取一个有界对象；重复字段、宽松JSON与尾随内容均不能定义更新事实。 */
    public static UpdateManifest parse(String text, String manifestUrl, String expectedPackage,
            int deviceSdk, String[] supportedAbis) throws IOException {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) throw new IOException("更新清单过大");
        JsonObject object = new JsonObject(); Set<String> names = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false); reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) throw new IOException("更新清单字段重复");
                switch (name) {
                    case "schema": case "versionCode": case "minSdk": case "sizeBytes":
                        if (reader.peek() != JsonToken.NUMBER) throw new IOException("更新清单数字无效");
                        String number = reader.nextString();
                        if (!number.matches("[0-9]+")) throw new IOException("更新清单需要整数");
                        object.addProperty(name, Long.parseLong(number)); break;
                    case "packageName": case "versionName": case "abi": case "apkUrl": case "sha256":
                        if (reader.peek() != JsonToken.STRING) throw new IOException("更新清单文本无效");
                        String value = reader.nextString();
                        if (value.isEmpty() || value.length() > 2048) throw new IOException("更新清单文本长度无效");
                        object.addProperty(name, value); break;
                    default: throw new IOException("更新清单版本不支持");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || names.size() != 9) throw new IOException("更新清单不完整");
            long code = object.get("versionCode").getAsLong(), size = object.get("sizeBytes").getAsLong();
            long sdk = object.get("minSdk").getAsLong(); String abi = object.get("abi").getAsString();
            boolean supported = false; for (String candidate : supportedAbis) if (abi.equals(candidate)) supported = true;
            if (object.get("schema").getAsLong() != 1 || !expectedPackage.equals(object.get("packageName").getAsString())
                    || code <= 0 || code > 2100000000L || size <= 0 || size > MAX_APK_BYTES
                    || sdk < 21 || sdk > deviceSdk || !supported
                    || !object.get("sha256").getAsString().matches("[0-9a-f]{64}")) throw new IOException("更新包与本机不匹配");
            String apkUrl = object.get("apkUrl").getAsString(); validateUrl(manifestUrl, apkUrl);
            return new UpdateManifest(expectedPackage, code, object.get("versionName").getAsString(), (int)sdk,
                    abi, apkUrl, size, object.get("sha256").getAsString());
        } catch (RuntimeException error) { throw new IOException("更新清单无效", error); }
    }

    /** 只允许同一 HTTPS 发布来源、清单父目录内的普通 APK 路径，禁止重定向绕过。 */
    public static void validateUrl(String manifestUrl, String apkUrl) throws IOException {
        try {
            URI source = new URI(manifestUrl), target = new URI(apkUrl);
            if (!"https".equals(source.getScheme()) || source.getHost() == null || source.getUserInfo() != null
                    || source.getQuery() != null || source.getFragment() != null || !source.normalize().equals(source)
                    || !"https".equals(target.getScheme()) || !source.getHost().equalsIgnoreCase(target.getHost())
                    || port(source) != port(target) || target.getUserInfo() != null || target.getQuery() != null
                    || target.getFragment() != null || !target.normalize().equals(target)) throw new IOException("更新地址无效");
            String base = source.getRawPath(); base = base.substring(0, base.lastIndexOf('/') + 1);
            String path = target.getRawPath();
            if (base.isEmpty() || path == null || !path.startsWith(base) || !path.matches("[A-Za-z0-9/_.-]+\\.apk")) throw new IOException("更新地址不在发布目录");
        } catch (java.net.URISyntaxException | NullPointerException error) { throw new IOException("更新地址无效", error); }
    }

    /** 将 HTTPS 默认端口与显式443作为同一来源。 */
    private static int port(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }
    /** 同版与降版都不提供安装动作。 */
    public boolean isNewerThan(long installedVersion) { return versionCode > installedVersion; }

    /** 小清单可持久化；下载本体始终留在有界文件中。 */
    public String toJson() {
        JsonObject value = new JsonObject(); value.addProperty("schema", 1); value.addProperty("packageName", packageName);
        value.addProperty("versionCode", versionCode); value.addProperty("versionName", versionName); value.addProperty("minSdk", minSdk);
        value.addProperty("abi", abi); value.addProperty("apkUrl", apkUrl); value.addProperty("sizeBytes", sizeBytes); value.addProperty("sha256", sha256);
        return value.toString();
    }

    /** 安装前再次校验完整长度与SHA，不把已下载标记当成文件仍可信。 */
    public void verifyFile(File file) throws IOException {
        if (!file.isFile() || file.length() != sizeBytes) throw new IOException("安装包大小不匹配");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0; byte[] buffer = new byte[16 * 1024];
            try (InputStream input = new FileInputStream(file)) {
                int count; while ((count = input.read(buffer)) != -1) { total += count; if (total > sizeBytes) throw new IOException("安装包过大"); digest.update(buffer, 0, count); }
            }
            if (total != sizeBytes || !sha256.equals(hex(digest.digest()))) throw new IOException("安装包校验失败");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException("无法校验安装包", error); }
    }

    /** 固定缓冲复制，超限字节不会写入；调用者负责最终长度、落盘与原子替换。 */
    public static long copyLimited(InputStream input, OutputStream output, long maxBytes,
            BooleanSupplier active, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[16 * 1024]; long total = 0;
        while (true) {
            if (!active.getAsBoolean()) throw new PausedException();
            int count = input.read(buffer); if (count == -1) break;
            if (count > maxBytes - total) throw new IOException("下载超过声明大小");
            if (!active.getAsBoolean()) throw new PausedException();
            output.write(buffer, 0, count); total += count; progress.accept(total);
        }
        return total;
    }

    /** 续传必须精确覆盖本次剩余范围；服务器忽略Range返回200时从头写入。 */
    public static long resumeOffset(int status, String contentRange, long offset, long total) throws IOException {
        if (status == 200) return 0;
        if (status == 206 && ("bytes " + offset + "-" + (total - 1) + "/" + total).equals(contentRange)) return offset;
        throw new IOException("下载响应或续传范围无效");
    }

    /** 固定小写十六进制用于清单与证书摘要。 */
    public static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return result.toString(); }
    /** 暂停是保留part的用户/生命周期状态，与损坏校验失败区分。 */
    public static final class PausedException extends IOException { /** 向更新owner交回暂停，不触及聊天错误状态。 */ public PausedException() { super("下载已暂停"); } }
}
