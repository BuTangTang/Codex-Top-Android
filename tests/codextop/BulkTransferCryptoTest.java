package com.butang.codextop;

import com.google.gson.JsonObject;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/** 合成分块验证身份认证、密钥隔离、版本和编码边界；跨语言互通另由真实 CLI handler 测试。 */
public final class BulkTransferCryptoTest {
    /** 改变传输身份、序号、密文或接收密钥必须失败，空块与原最大块保持兼容。 */
    public static void main(String[] args) throws Exception {
        try (BulkTransferCrypto.Recipient recipient = BulkTransferCrypto.createRecipient();
                BulkTransferCrypto.Recipient other = BulkTransferCrypto.createRecipient()) {
            for (int size : new int[]{0, 1, 256000, BulkTransferCrypto.MAX_CHUNK_BYTES}) {
                byte[] bytes = new byte[size];
                for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 37 + 11);
                JsonObject envelope = BulkTransferCrypto.encrypt("test-transfer", 3, bytes, size, recipient.publicKeyBase64);
                String body = envelope.get("payloadBase64").getAsString();
                String key = envelope.get("encryptedDataKeyEnvelopeBase64").getAsString();
                if (!Arrays.equals(bytes, BulkTransferCrypto.decrypt("test-transfer", 3, body, key, recipient))) throw new AssertionError("字节变化");
                fails(() -> BulkTransferCrypto.decrypt("other-transfer", 3, body, key, recipient));
                fails(() -> BulkTransferCrypto.decrypt("test-transfer", 4, body, key, recipient));
                fails(() -> BulkTransferCrypto.decrypt("test-transfer", 3, body, key, other));
                byte[] altered = Base64.getDecoder().decode(body); altered[altered.length - 1] ^= 1;
                fails(() -> BulkTransferCrypto.decrypt("test-transfer", 3, Base64.getEncoder().encodeToString(altered), key, recipient));
                byte[] version = Base64.getDecoder().decode(body); version[0] = 1;
                fails(() -> BulkTransferCrypto.decrypt("test-transfer", 3, Base64.getEncoder().encodeToString(version), key, recipient));
            }
            fails(() -> BulkTransferCrypto.encrypt("test", -1, new byte[0], 0, recipient.publicKeyBase64));
            fails(() -> BulkTransferCrypto.encrypt("test", 0, new byte[BulkTransferCrypto.MAX_CHUNK_BYTES + 1], BulkTransferCrypto.MAX_CHUNK_BYTES + 1, recipient.publicKeyBase64));
            fails(() -> BulkTransferCrypto.decrypt("test", 0, "bad!", "bad!", recipient));
        }
        System.out.println("BulkTransferCrypto: 身份/序号认证、错误密钥、篡改、版本、空块和 1 MiB 上限通过");
    }

    /** 仅接受预期认证错误，不把其他异常当作检查通过。 */
    private static void fails(Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("异常密文被接受"); }
        catch (GeneralSecurityException expected) { }
    }
    /** 测试操作允许抛出待验证的加密异常。 */
    private interface Attempt { void run() throws Exception; }
}
