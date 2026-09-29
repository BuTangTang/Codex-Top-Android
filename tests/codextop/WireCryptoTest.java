package com.butang.codextop;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.security.GeneralSecurityException;

/** 独立 Node/tweetnacl 合成向量验证机器封套和两种历史密文格式。 */
public final class WireCryptoTest {
    /** 检查跨语言解密、随机加密往返及认证标签损坏后的拒绝。 */
    public static void main(String[] args) throws Exception {
        JsonObject f = JsonParser.parseString(Files.readString(Paths.get(args[0]))).getAsJsonObject();
        byte[] secret = Base64.getDecoder().decode(f.get("secret").getAsString());
        String message = f.get("message").getAsString();
        String wrapped = "{\"__happierSerializedJsonValueV1\":true,\"type\":\"json\",\"value\":" + message + "}";
        if (!JsonParser.parseString(message).equals(JsonParser.parseString(WireCrypto.unpack(wrapped))))
            throw new AssertionError("服务端 JSON 包装未解开");
        for (String absent : new String[]{"undefined", "{\"__happierSerializedJsonValueV1\":true,\"type\":\"undefined\"}"}) {
            try { WireCrypto.unpack(absent); throw new AssertionError("接受了空响应"); }
            catch (GeneralSecurityException expected) { }
        }
        for (boolean aes : new boolean[]{false, true}) {
            try (WireCrypto crypto = new WireCrypto(secret, aes ? f.get("envelope").getAsString() : null)) {
                String wire = f.get(aes ? "aes" : "legacy").getAsString();
                if (!message.equals(crypto.decrypt(wire))) throw new AssertionError("跨语言密文不兼容");
                if (!aes && !JsonParser.parseString(message).equals(JsonParser.parseString(
                        crypto.decrypt(f.get("wrappedLegacy").getAsString()))))
                    throw new AssertionError("跨语言包装密文未解开");
                if (!message.equals(crypto.decrypt(crypto.encrypt(message)))) throw new AssertionError("往返失败");
                byte[] damaged = Base64.getDecoder().decode(wire); damaged[damaged.length - 1] ^= 1;
                try {
                    crypto.decrypt(Base64.getEncoder().encodeToString(damaged));
                    throw new AssertionError("接受了损坏的认证密文");
                } catch (GeneralSecurityException expected) { }
            }
        }
        System.out.println("WireCrypto: 机器封套、AES、旧格式与认证拒绝通过");
    }
}
