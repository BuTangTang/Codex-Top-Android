package com.butang.codextop;

import com.iwebpp.crypto.TweetNaclFast;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 与现有机器 RPC 共用的密钥封套及密文格式，不改变云端协议。 */
public final class WireCrypto implements AutoCloseable {
    private final byte[] key;
    private final boolean aes;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 为一台电脑解封数据密钥；没有封套的旧电脑沿用账号 SecretBox。 */
    public WireCrypto(byte[] accountSecret, String encryptedDataKey) throws GeneralSecurityException {
        aes = encryptedDataKey != null;
        key = aes ? openDataKey(accountSecret, encryptedDataKey) : accountSecret.clone();
    }

    /** 根据账号内容密钥树解封机器数据密钥，拒绝无效版本与认证失败。 */
    private static byte[] openDataKey(byte[] accountSecret, String encoded) throws GeneralSecurityException {
        byte[] envelope = Base64.getDecoder().decode(encoded);
        if (envelope.length < 73 || envelope[0] != 0) throw new GeneralSecurityException("电脑密钥封套无效");
        byte[] root = hmac("Happy EnCoder Master Seed".getBytes(StandardCharsets.UTF_8), accountSecret);
        byte[] content = hmac(Arrays.copyOfRange(root, 32, 64), "\0content".getBytes(StandardCharsets.UTF_8));
        byte[] scalar = Arrays.copyOf(MessageDigest.getInstance("SHA-512").digest(Arrays.copyOf(content, 32)), 32);
        try {
            byte[] opened = new TweetNaclFast.Box(Arrays.copyOfRange(envelope, 1, 33), scalar)
                    .open(Arrays.copyOfRange(envelope, 57, envelope.length), Arrays.copyOfRange(envelope, 33, 57));
            if (opened == null || opened.length != 32) throw new GeneralSecurityException("电脑密钥认证失败");
            return opened;
        } finally {
            Arrays.fill(root, (byte) 0);
            Arrays.fill(content, (byte) 0);
            Arrays.fill(scalar, (byte) 0);
        }
    }

    /** 将 JSON 编码为现有 RPC 的 base64 密文，每次使用新的随机 nonce。 */
    public String encrypt(String json) throws GeneralSecurityException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("__happierSerializedJsonValueV1", true);
        envelope.addProperty("type", "json");
        envelope.add("value", JsonParser.parseString(json));
        byte[] plain = envelope.toString().getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[aes ? 12 : 24];
        RANDOM.nextBytes(nonce);
        try {
            byte[] ciphertext;
            if (aes) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                ciphertext = cipher.doFinal(plain);
            } else {
                ciphertext = new TweetNaclFast.SecretBox(key).box(plain, nonce);
            }
            int start = aes ? 1 : 0;
            byte[] wire = new byte[start + nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, wire, start, nonce.length);
            System.arraycopy(ciphertext, 0, wire, start + nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(wire);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    /** 认证解密后返回 JSON，失败不伪造空记录。 */
    public String decrypt(String encoded) throws GeneralSecurityException {
        byte[] wire = Base64.getDecoder().decode(encoded);
        byte[] plain;
        if (aes) {
            if (wire.length < 29 || wire[0] != 0) throw new GeneralSecurityException("电脑密文格式无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(wire, 1, 13)));
            plain = cipher.doFinal(Arrays.copyOfRange(wire, 13, wire.length));
        } else {
            if (wire.length < 40) throw new GeneralSecurityException("电脑密文格式无效");
            plain = new TweetNaclFast.SecretBox(key).open(Arrays.copyOfRange(wire, 24, wire.length),
                    Arrays.copyOf(wire, 24));
            if (plain == null) throw new GeneralSecurityException("电脑密文认证失败");
        }
        try { return unpack(new String(plain, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    /** 解开服务端通用 JSON 包装；旧版裸 JSON 仍可读取，undefined 不能冒充有效响应。 */
    static String unpack(String serialized) throws GeneralSecurityException {
        if ("undefined".equals(serialized)) throw new GeneralSecurityException("电脑未返回数据");
        JsonElement parsed = JsonParser.parseString(serialized);
        if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            JsonElement marker = object.get("__happierSerializedJsonValueV1");
            if (marker != null && marker.isJsonPrimitive() && marker.getAsJsonPrimitive().isBoolean()
                    && marker.getAsBoolean()) {
                if ("undefined".equals(object.has("type") ? object.get("type").getAsString() : null))
                    throw new GeneralSecurityException("电脑未返回数据");
                if (object.has("type") && "json".equals(object.get("type").getAsString()) && object.has("value"))
                    return object.get("value").toString();
            }
        }
        return serialized;
    }

    /** 派生与现有内容密钥树完全一致的 HMAC 分支。 */
    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key, "HmacSHA512"));
        return mac.doFinal(data);
    }

    /** 释放电脑连接时擦除数据密钥。 */
    @Override public void close() { Arrays.fill(key, (byte) 0); }
}
