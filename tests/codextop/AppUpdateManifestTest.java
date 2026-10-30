package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** 实际生产清单及下载字节规则，全部使用合成输入和临时目录。 */
public final class AppUpdateManifestTest {
    private static int checks;
    private static final String URL = "https://example.test/updates/android/manifest.json";
    private static final String PACKAGE = "com.butang.codextop.nativepreview.beta";

    /** 旧源码缺少更新契约时本测试不能编译；新实现须通过正反边界。 */
    public static void main(String[] args) throws Exception {
        JsonObject json = valid();
        UpdateManifest value = parse(json.toString());
        require(value.versionCode == 71060 && value.sizeBytes == 5, "准确版本与长度");
        require(value.isNewerThan(71059) && !value.isNewerThan(71060) && !value.isNewerThan(71061), "同版降版不更新");
        require(value.toJson().equals(json.toString()), "持久化格式一致");
        rejectChange("schema", "2"); rejectChange("versionCode", "71060.0");
        rejectChange("versionCode", "\"71060\""); rejectChange("versionCode", "2147483648");
        rejectChange("sizeBytes", "0"); rejectChange("sizeBytes", "268435457");
        rejectChange("minSdk", "37"); rejectChange("abi", "\"x86\"");
        rejectChange("packageName", "\"other.app\""); rejectChange("sha256", "\"bad\"");
        for (String url : new String[]{"http://example.test/updates/android/a.apk", "https://other.test/updates/android/a.apk", "https://example.test/other/a.apk", "https://example.test/updates/android/../a.apk", "https://example.test/updates/android/%2e%2e/a.apk", "https://user@example.test/updates/android/a.apk", "https://example.test/updates/android/a.apk?q=1", "https://example.test/updates/android/a.apk#x"}) {
            JsonObject bad = valid(); bad.addProperty("apkUrl", url); reject(bad.toString());
        }
        reject(json.toString().replace("\"schema\":1", "\"schema\":1,\"schema\":1"));
        reject(json.toString() + " {}"); reject("/*comment*/" + json);
        reject(json.toString().replace("\"schema\"", "schema"));
        reject(json.toString().replace("\"schema\":1", "\"unknown\":1,\"schema\":1"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UpdateManifest.copyLimited(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), output, 5, () -> true, n -> {});
        require("hello".equals(output.toString("UTF-8")), "流式字节保持");
        try { UpdateManifest.copyLimited(new ByteArrayInputStream(new byte[6]), new ByteArrayOutputStream(), 5, () -> true, n -> {}); throw new AssertionError("超限写入"); }
        catch (java.io.IOException expected) { checks++; }
        try { UpdateManifest.copyLimited(new ByteArrayInputStream(new byte[5]), new ByteArrayOutputStream(), 5, () -> false, n -> {}); throw new AssertionError("暂停仍写入"); }
        catch (UpdateManifest.PausedException expected) { checks++; }
        require(UpdateManifest.resumeOffset(206, "bytes 2-4/5", 2, 5) == 2, "206准确续传");
        require(UpdateManifest.resumeOffset(200, null, 2, 5) == 0, "200重下不拼接");
        for (String range : new String[]{null, "bytes 1-4/5", "bytes 2-4/6", "bytes 2-3/5", "bytes */5"}) {
            try { UpdateManifest.resumeOffset(206, range, 2, 5); throw new AssertionError("接受错Range"); }
            catch (java.io.IOException expected) { checks++; }
        }
        File dir = Files.createTempDirectory("codextop-update-rule-").toFile();
        try {
            File apk = new File(dir, "test.apk"); Files.write(apk.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            value.verifyFile(apk); checks++;
            Files.write(apk.toPath(), "hellx".getBytes(StandardCharsets.UTF_8));
            try { value.verifyFile(apk); throw new AssertionError("接受错SHA"); } catch (java.io.IOException expected) { checks++; }
            Files.write(apk.toPath(), "hello!".getBytes(StandardCharsets.UTF_8));
            try { value.verifyFile(apk); throw new AssertionError("接受错长度"); } catch (java.io.IOException expected) { checks++; }
        } finally { for (File file : dir.listFiles()) file.delete(); dir.delete(); }
        System.out.println("AppUpdateManifestTest PASS checks=" + checks);
    }
    /** 使用精确产品身份和合成设备参数执行真实解析。 */
    private static UpdateManifest parse(String text) throws Exception { return UpdateManifest.parse(text, URL, PACKAGE, 36, new String[]{"arm64-v8a"}); }
    /** 每个反例都从同一合法清单派生。 */
    private static JsonObject valid() throws Exception {
        JsonObject value = new JsonObject(); value.addProperty("schema", 1); value.addProperty("packageName", PACKAGE);
        value.addProperty("versionCode", 71060); value.addProperty("versionName", "1.0.1"); value.addProperty("minSdk", 21);
        value.addProperty("abi", "arm64-v8a"); value.addProperty("apkUrl", "https://example.test/updates/android/71060-a.apk");
        value.addProperty("sizeBytes", 5); value.addProperty("sha256", UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest("hello".getBytes(StandardCharsets.UTF_8)))); return value;
    }
    /** 替换单字段，不能通过Gson数值转换掩盖非法字面量。 */
    private static void rejectChange(String field, String literal) throws Exception { JsonObject value = valid(); value.add(field, JsonParser.parseString(literal)); reject(value.toString()); }
    /** 清单解析失败是预期，不能把异常误判为最新版。 */
    private static void reject(String value) throws Exception { try { parse(value); throw new AssertionError("接受非法清单: " + value); } catch (java.io.IOException expected) { checks++; } }
    /** 简短断言计数，便于独立运行。 */
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
