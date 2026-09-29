package com.butang.codextop;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 只保存已登录凭据，不保存密码；系统密钥库密钥不随应用备份迁移。 */
public final class SessionStore {
    private static final String ALIAS = "codextop-session-v1";
    private final AtomicFile file;
    /** 使用应用私有目录中的登录文件，不与聊天缓存共用文件。 */
    public SessionStore(Context context) {
        this(new File(context.getNoBackupFilesDir(), "codex-session"));
    }

    /** 允许独立临时目录验证系统原子文件恢复，不接触应用真实账号。 */
    SessionStore(File target) {
        file = new AtomicFile(target);
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new java.io.IOException("登录密钥不存在");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    public void save(PasswordLogin.Session session) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("server", session.server);
        data.addProperty("token", session.token);
        data.addProperty("account", session.accountId);
        data.addProperty("name", session.loginName);
        data.addProperty("secret", Base64.encodeToString(session.secret, Base64.NO_WRAP));
        byte[] plain = data.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(plain);
            output = file.startWrite();
            output.write(cipher.getIV().length);
            output.write(cipher.getIV());
            output.write(encrypted);
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw error;
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    /** 退出时仅移除凭据及原子写入残留；删除失败须上报，不能宣称已经退出。 */
    public void clear() throws java.io.IOException {
        file.delete();
        File target = file.getBaseFile();
        if (target.exists() || new File(target + ".bak").exists() || new File(target + ".new").exists())
            throw new java.io.IOException("登录凭据未能清除");
    }

    /** 先由系统恢复原子备份；只有文件真正缺失才返回未登录，损坏或读失败继续上报。 */
    public PasswordLogin.Session read() throws Exception {
        byte[] bytes;
        try { bytes = file.readFully(); }
        catch (java.io.FileNotFoundException error) {
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) throw error;
            return null;
        }
        if (bytes.length < 30 || bytes[0] != 12) throw new java.io.IOException("登录缓存格式无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, bytes, 1, 12));
        byte[] plain = cipher.doFinal(bytes, 13, bytes.length - 13);
        try {
            JsonObject data = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] secret = Base64.decode(data.get("secret").getAsString(), Base64.NO_WRAP);
            if (secret.length != 32) throw new java.io.IOException("登录缓存身份无效");
            return new PasswordLogin.Session(data.get("server").getAsString(), data.get("token").getAsString(),
                    data.get("account").getAsString(), data.get("name").getAsString(), secret);
        } finally { Arrays.fill(plain, (byte) 0); }
    }
}
