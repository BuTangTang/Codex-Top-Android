package com.butang.codextop;

import com.google.gson.JsonObject;
import com.iwebpp.crypto.TweetNaclFast;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 兼容现有机器 bulk transfer 的 v0 AES-GCM 分块与 NaCl 数据密钥封套。 */
public final class BulkTransferCrypto {
    // 与 CLI transferChunkSizeLimit.ts 的既有上限一致，不另设文件大小规则。
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类不保存账号或机器连接。 */
    private BulkTransferCrypto() { }

    /** 仅本次下载使用的接收密钥；退出传输即销毁，不写入账号存储。 */
    public static final class Recipient implements AutoCloseable {
        public final String publicKeyBase64;
        private final byte[] seed;

        /** 按 libsodium seed 派生规则生成与 CLI 一致的 Curve25519 公钥。 */
        private Recipient(byte[] seed) throws GeneralSecurityException {
            this.seed = seed;
            byte[] scalar = deriveScalar(seed);
            try { publicKeyBase64 = Base64.getEncoder().encodeToString(TweetNaclFast.Box.keyPair_fromSecretKey(scalar).getPublicKey()); }
            finally { Arrays.fill(scalar, (byte) 0); }
        }

        /** 下载结束擦除这次接收密钥。 */
        @Override public void close() { Arrays.fill(seed, (byte) 0); }
    }

    /** 每次下载独立生成临时接收密钥，不复用机器 RPC 的账号密钥。 */
    public static Recipient createRecipient() throws GeneralSecurityException {
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        return new Recipient(seed);
    }

    /** 加密一块有效长度的数据，附加认证绑定传输身份与序号，返回既有 JSON 字段。 */
    public static JsonObject encrypt(String transferId, int index, byte[] bytes, int length,
            String recipientPublicKeyBase64) throws GeneralSecurityException {
        if (length < 0 || length > bytes.length || length > MAX_CHUNK_BYTES)
            throw new GeneralSecurityException("附件分块长度无效");
        byte[] recipient = decode(recipientPublicKeyBase64, 64);
        if (recipient.length != 32) throw new GeneralSecurityException("附件接收密钥无效");
        byte[] dataKey = new byte[32], nonce = new byte[12], ephemeral = new byte[32], boxNonce = new byte[24];
        RANDOM.nextBytes(dataKey); RANDOM.nextBytes(nonce); RANDOM.nextBytes(ephemeral); RANDOM.nextBytes(boxNonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(transferId, index));
            byte[] ciphertext = cipher.doFinal(bytes, 0, length);
            byte[] payload = new byte[1 + nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 1, nonce.length);
            System.arraycopy(ciphertext, 0, payload, 13, ciphertext.length);
            byte[] boxed = new TweetNaclFast.Box(recipient, ephemeral).box(dataKey, boxNonce);
            byte[] envelope = new byte[1 + 32 + 24 + boxed.length];
            System.arraycopy(TweetNaclFast.Box.keyPair_fromSecretKey(ephemeral).getPublicKey(), 0, envelope, 1, 32);
            System.arraycopy(boxNonce, 0, envelope, 33, 24);
            System.arraycopy(boxed, 0, envelope, 57, boxed.length);
            JsonObject result = new JsonObject();
            result.addProperty("payloadBase64", Base64.getEncoder().encodeToString(payload));
            result.addProperty("encryptedDataKeyEnvelopeBase64", Base64.getEncoder().encodeToString(envelope));
            return result;
        } finally {
            Arrays.fill(dataKey, (byte) 0); Arrays.fill(ephemeral, (byte) 0);
        }
    }

    /** 先认证密钥封套和分块身份，再返回明文；原序号、密文或版本变化均不能接受。 */
    public static byte[] decrypt(String transferId, int index, String payloadBase64,
            String encryptedDataKeyEnvelopeBase64, Recipient recipient) throws GeneralSecurityException {
        byte[] envelope = decode(encryptedDataKeyEnvelopeBase64, 1024);
        byte[] payload = decode(payloadBase64, MAX_CHUNK_BYTES + 29);
        if (envelope.length < 73 || envelope[0] != 0 || payload.length < 29 || payload[0] != 0)
            throw new GeneralSecurityException("附件密文格式无效");
        byte[] publicKey = Arrays.copyOfRange(envelope, 1, 33);
        byte[] boxNonce = Arrays.copyOfRange(envelope, 33, 57);
        byte[] boxed = Arrays.copyOfRange(envelope, 57, envelope.length);
        // 沿协议先尝试直接密钥，再兼容 SHA-512(seed) 派生密钥。
        byte[] key = new TweetNaclFast.Box(publicKey, recipient.seed).open(boxed, boxNonce);
        if (key == null) {
            byte[] scalar = deriveScalar(recipient.seed);
            try { key = new TweetNaclFast.Box(publicKey, scalar).open(boxed, boxNonce); }
            finally { Arrays.fill(scalar, (byte) 0); }
        }
        if (key == null) throw new GeneralSecurityException("附件分块密钥认证失败");
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new GeneralSecurityException("附件分块密钥认证失败");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, payload, 1, 12));
            cipher.updateAAD(aad(transferId, index));
            return cipher.doFinal(payload, 13, payload.length - 13);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    /** 原协议认证串为 0:transferId:index，不能改为空附加数据或只校验字节。 */
    private static byte[] aad(String transferId, int index) throws GeneralSecurityException {
        if (transferId == null || transferId.isEmpty() || index < 0) throw new GeneralSecurityException("附件分块身份无效");
        return ("0:" + transferId + ":" + index).getBytes(StandardCharsets.UTF_8);
    }

    /** 对临时种子应用既有 NaCl box 的 SHA-512 前 32 字节派生规则。 */
    private static byte[] deriveScalar(byte[] seed) throws GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-512").digest(seed);
        try { return Arrays.copyOf(hash, 32); }
        finally { Arrays.fill(hash, (byte) 0); }
    }

    /** 沿既有分块上限检查编码长度后解码，坏编码统一作为传输失败。 */
    private static byte[] decode(String text, int maxBytes) throws GeneralSecurityException {
        if (text == null || text.length() > ((maxBytes + 2) / 3) * 4)
            throw new GeneralSecurityException("附件密文长度无效");
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            if (bytes.length > maxBytes) throw new GeneralSecurityException("附件密文长度无效");
            return bytes;
        } catch (IllegalArgumentException error) { throw new GeneralSecurityException("附件密文编码无效", error); }
    }
}
