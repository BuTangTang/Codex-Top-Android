package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.io.IOException;

/** 使用既有客户端 tweetnacl 生成的合成封套验证 Java 互操作。 */
public final class PasswordEnvelopeTest {
    /** 检查正常解封，以及错密、公钥、账号、凭据被替换时的拒绝行为。 */
    public static void main(String[] args) throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(Paths.get(args[0]))).getAsJsonObject();
        String credential = fixture.get("credentialId").getAsString();
        byte[] key = Base64.getUrlDecoder().decode(fixture.get("wrappingKey").getAsString());
        JsonObject response = fixture.getAsJsonObject("response");
        byte[] expected = Base64.getUrlDecoder().decode(fixture.get("expectedSecret").getAsString());
        if (!Arrays.equals(expected, PasswordLogin.openEnvelope(response, credential, key))) {
            throw new AssertionError("Java 与现有客户端解封结果不同");
        }
        byte[] wrong = key.clone(); wrong[0] ^= 1;
        reject(response, credential, wrong);
        reject(response, "wrong-credential", key);
        JsonObject otherAccount = response.deepCopy(); otherAccount.addProperty("accountId", "other-account");
        reject(otherAccount, credential, key);
        JsonObject otherPublicKey = response.deepCopy(); otherPublicKey.addProperty("publicKey", "00".repeat(32));
        reject(otherPublicKey, credential, key);
        System.out.println("PasswordEnvelope: 跨语言解封与四类替换拒绝通过");
    }

    /** 不输出任何封套内容，仅断言验证失败。 */
    private static void reject(JsonObject response, String credential, byte[] key) throws Exception {
        try {
            PasswordLogin.openEnvelope(response, credential, key);
            throw new AssertionError("应拒绝无效账号封套");
        } catch (IOException expected) { }
    }
}
