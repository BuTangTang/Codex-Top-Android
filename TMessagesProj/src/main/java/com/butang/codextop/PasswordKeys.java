package com.butang.codextop;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 与现有服务共用的密码协议；只负责派生，不保存密码或执行网络请求。 */
public final class PasswordKeys implements AutoCloseable {
    public final byte[] loginSecret;
    public final byte[] wrappingKey;

    /** 接管派生结果，调用方使用完毕后必须关闭以擦除临时密钥。 */
    private PasswordKeys(byte[] loginSecret, byte[] wrappingKey) {
        this.loginSecret = loginSecret;
        this.wrappingKey = wrappingKey;
    }

    /** 使用协议固定参数派生；必须在后台线程执行，保留密码的原始空白和 UTF-8。 */
    public static PasswordKeys derive(String password, byte[] salt, byte[] credentialId,
                                      int iterations) throws GeneralSecurityException {
        if (password == null || password.isEmpty() || password.length() > 1024
                || salt == null || salt.length != 32 || credentialId == null
                || credentialId.length != 32 || iterations != 220000) {
            throw new IllegalArgumentException("密码协议参数无效");
        }
        byte[] utf8 = password.getBytes(StandardCharsets.UTF_8);
        byte[] master = null;
        byte[] prk = null;
        try {
            // 显式使用字节密码，避免不同平台 PBEKeySpec 的字符编码差异。
            master = pbkdf2(utf8, salt, iterations);
            prk = hmac(credentialId).doFinal(master);
            return new PasswordKeys(expand(prk, "loginSecret"), expand(prk, "wrappingKey"));
        } finally {
            Arrays.fill(utf8, (byte) 0);
            if (master != null) Arrays.fill(master, (byte) 0);
            if (prk != null) Arrays.fill(prk, (byte) 0);
        }
    }

    /** PBKDF2-HMAC-SHA512 的单个 64 字节输出块，与服务器协议长度一致。 */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        Mac mac = hmac(password);
        byte[] block = Arrays.copyOf(salt, salt.length + 4);
        block[block.length - 1] = 1;
        byte[] u = mac.doFinal(block);
        byte[] result = u.clone();
        try {
            for (int round = 1; round < iterations; round++) {
                byte[] next = mac.doFinal(u);
                Arrays.fill(u, (byte) 0);
                u = next;
                for (int i = 0; i < result.length; i++) result[i] ^= u[i];
            }
            return result;
        } finally {
            Arrays.fill(u, (byte) 0);
            Arrays.fill(block, (byte) 0);
        }
    }

    /** HKDF 扩展为指定用途的 32 字节密钥，登录证明与解封密钥相互独立。 */
    private static byte[] expand(byte[] prk, String purpose) throws GeneralSecurityException {
        byte[] info = ("codextop/password-auth/v1/" + purpose).getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOf(info, info.length + 1);
        input[input.length - 1] = 1;
        byte[] full = hmac(prk).doFinal(input);
        try {
            return Arrays.copyOf(full, 32);
        } finally {
            Arrays.fill(full, (byte) 0);
        }
    }

    /** 创建平台自带的 SHA-512 HMAC 实例，不引入额外密码库。 */
    private static Mac hmac(byte[] key) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key, "HmacSHA512"));
        return mac;
    }

    /** 擦除调用方持有的两份临时派生结果。 */
    @Override public void close() {
        Arrays.fill(loginSecret, (byte) 0);
        Arrays.fill(wrappingKey, (byte) 0);
    }
}
