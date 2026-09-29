package com.butang.codextop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iwebpp.crypto.TweetNaclFast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** 直接使用现有账号密码接口，恢复同一账号的会话解密种子。 */
public final class PasswordLogin {
    /** 登录结果由客户端账号存储接管，实例本身不写磁盘。 */
    public static final class Session implements AutoCloseable {
        public final String server;
        public final String token;
        public final String accountId;
        public final String loginName;
        public final byte[] secret;

        /** 保存已经交叉验证的身份，不包含密码。 */
        Session(String server, String token, String accountId, String loginName, byte[] secret) {
            this.server = server;
            this.token = token;
            this.accountId = accountId;
            this.loginName = loginName;
            this.secret = secret;
        }

        /** 退出账号时擦除内存中的恢复种子。 */
        @Override public void close() { Arrays.fill(secret, (byte) 0); }
    }

    /** 后台线程完成参数读取、密码证明、账号封套验证和 token 身份核对。 */
    public static Session login(String server, String loginName, String password) throws Exception {
        URI uri = new URI(server);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IOException("请填写有效的 HTTPS 服务器地址");
        }
        String base = server.replaceAll("/+$", "");
        String name = loginName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 128) throw new IOException("请输入账号");
        JsonObject request = new JsonObject();
        request.addProperty("loginName", name);
        JsonObject parameters = request(base + "/v1/auth/password/parameters", request, null);
        if (parameters.get("version").getAsInt() != 1
                || !"pbkdf2-sha512".equals(parameters.get("kdf").getAsString())) {
            throw new IOException("服务器密码协议不兼容");
        }
        String credential = parameters.get("credentialId").getAsString();
        try (PasswordKeys keys = PasswordKeys.derive(password,
                decode(parameters.get("salt").getAsString(), 32), decode(credential, 32),
                parameters.get("iterations").getAsInt())) {
            request.addProperty("credentialId", credential);
            request.addProperty("loginSecret", Base64.getUrlEncoder().withoutPadding().encodeToString(keys.loginSecret));
            JsonObject response = request(base + "/v1/auth/password/login", request, null);
            byte[] secret = openEnvelope(response, credential, keys.wrappingKey);
            boolean accepted = false;
            try {
                String token = response.get("token").getAsString();
                String account = response.get("accountId").getAsString();
                if (token.isEmpty() || account.isEmpty()) throw new IOException("账号响应无效");
                JsonObject profile = request(base + "/v1/account/profile", null, token);
                if (!account.equals(profile.get("id").getAsString())) throw new IOException("账号身份不一致");
                Session session = new Session(base, token, account, name, secret);
                accepted = true;
                return session;
            } finally {
                if (!accepted) Arrays.fill(secret, (byte) 0);
            }
        }
    }

    /** 验证认证封套、凭据上下文及 Ed25519 公钥，拒绝错密或跨账号封套。 */
    static byte[] openEnvelope(JsonObject response, String credential, byte[] key) throws IOException {
        JsonObject envelope = response.getAsJsonObject("envelope");
        if (envelope.get("version").getAsInt() != 1) throw new IOException("账号封套版本无效");
        String encoded = envelope.get("ciphertext").getAsString();
        if (encoded.length() < 22 || encoded.length() > 4096) throw new IOException("账号封套无效");
        byte[] plain = new TweetNaclFast.SecretBox(key).open(decode(encoded, -1),
                decode(envelope.get("nonce").getAsString(), 24));
        if (plain == null) throw new IOException("账号封套验证失败");
        byte[] secret = null;
        boolean accepted = false;
        try {
            JsonObject data = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
            if (data.get("version").getAsInt() != 1
                    || !credential.equals(data.get("credentialId").getAsString())
                    || !response.get("accountId").getAsString().equals(data.get("accountId").getAsString())) {
                throw new IOException("账号封套身份不一致");
            }
            secret = decode(data.get("secret").getAsString(), 32);
            TweetNaclFast.Signature.KeyPair pair = TweetNaclFast.Signature.keyPair_fromSeed(secret);
            try {
                StringBuilder hex = new StringBuilder();
                for (byte b : pair.getPublicKey()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                if (!hex.toString().equalsIgnoreCase(response.get("publicKey").getAsString())) {
                    throw new IOException("账号公钥不一致");
                }
            } finally { Arrays.fill(pair.getSecretKey(), (byte) 0); }
            accepted = true;
            return secret;
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (!accepted && secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    /** 只接受协议规定的无填充 base64url，防止宽松解码改变凭据表示。 */
    private static byte[] decode(String encoded, int length) throws IOException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if ((length >= 0 && bytes.length != length)
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new IOException("账号编码无效");
            }
            return bytes;
        } catch (IllegalArgumentException error) { throw new IOException("账号编码无效"); }
    }

    /** 请求始终留在起始服务器；不跟随重定向，也不把响应正文写入日志。 */
    private static JsonObject request(String url, JsonObject body, String token) throws IOException {
        return requestJson(url, body, token).getAsJsonObject();
    }

    /** 读取当前账号下的电脑列表，沿用登录时已经核对的服务器和令牌。 */
    public static com.google.gson.JsonArray machines(Session session) throws IOException {
        return requestJson(session.server + "/v1/machines", null, session.token).getAsJsonArray();
    }

    /** 共用有界 HTTP 读取，兼容对象形式的登录响应和数组形式的电脑列表。 */
    private static com.google.gson.JsonElement requestJson(String url, JsonObject body, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(payload); }
                finally { Arrays.fill(payload, (byte) 0); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException(status == 401
                    ? "账号或密码错误" : status == 429 ? "请求过于频繁，请稍后重试" : "服务器暂时无法连接");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (out.size() > 1048576) throw new IOException("服务器响应过大");
                }
                return JsonParser.parseString(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
