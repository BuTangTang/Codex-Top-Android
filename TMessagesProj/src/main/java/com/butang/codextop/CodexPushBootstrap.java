package com.butang.codextop;

import android.content.Context;
import com.google.firebase.FirebaseOptions;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.telegram.messenger.BuildConfig;

/** 使用构建时明确的独立项目配置启动推送；服务器私钥绝不进入客户端。 */
public final class CodexPushBootstrap {
    /** Application 的真实上下文就绪后调用；缺配置的本地构建保持关闭。 */
    public static void initialize(Context context) {
        if (context == null || !context.getPackageName().startsWith("com.butang.codextop.nativepreview")
                || BuildConfig.CODEX_FCM_APP_ID.isEmpty()) return;
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.CODEX_FCM_APP_ID)
                .setProjectId(BuildConfig.CODEX_FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.CODEX_FCM_SENDER_ID)
                .setApiKey(BuildConfig.CODEX_FCM_API_KEY).build();
        CodexPushCoordinator.initialize(context, options, new CodexPushCoordinator.RegistrationTransport() {
            /** 沿原账号认证接口注册独立 FCM token，不传会话正文和解密种子。 */
            @Override public void register(CodexPushCoordinator.Account account, String token) throws Exception {
                JsonObject body = new JsonObject();
                // 旧服务器忽略provider时也保留通道前缀，不能把FCM登记成旧Expo设备。
                body.addProperty("token", "fcm:" + token);
                body.addProperty("provider", "fcm");
                body.addProperty("clientServerUrl", account.server);
                request(account, "/v1/push-tokens", "POST", body);
            }

            /** 删除限定在旧账号及完整命名空间 token，迟到删除不能撤销新账号。 */
            @Override public void unregister(CodexPushCoordinator.Account account, String token) throws Exception {
                request(account, "/v1/push-tokens/" + URLEncoder.encode("fcm:" + token, "UTF-8"), "DELETE", null);
            }
        });
    }

    /** 独立注册队列的有界请求；禁止重定向，不记录认证头、token 或响应正文。 */
    private static void request(CodexPushCoordinator.Account account, String path, String method, JsonObject body) throws Exception {
        URI base = URI.create(account.server);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IOException("推送服务器地址无效");
        HttpURLConnection connection = (HttpURLConnection) URI.create(account.server.replaceAll("/+$", "") + path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + account.bearerToken);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                finally { java.util.Arrays.fill(bytes, (byte) 0); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("推送注册未被接纳");
        } finally { connection.disconnect(); }
    }
}
