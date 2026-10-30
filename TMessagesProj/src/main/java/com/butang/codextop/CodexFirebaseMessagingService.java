package com.butang.codextop;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** 独立Codex的FCM入口；不读取Telegram推送字段，不记录消息data或token。 */
public final class CodexFirebaseMessagingService extends FirebaseMessagingService {
    /** FCM服务线程短同步消费data-only消息，返回前完成账号校验与系统通知落盘。 */
    @Override public void onMessageReceived(RemoteMessage message) {
        if(message==null||message.getNotification()!=null) return;
        CodexPushCoordinator.receive(this,message.getData(),message.getSentTime(),message.getTtl());
    }
    /** token更新交独立串行协调器，未经登录绑定的设备不注册到任何账号。 */
    @Override public void onNewToken(String token) { CodexPushCoordinator.onNewToken(this,token); }
}
