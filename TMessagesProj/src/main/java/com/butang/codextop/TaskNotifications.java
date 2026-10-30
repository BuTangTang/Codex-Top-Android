package com.butang.codextop;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import com.google.gson.*;
import java.util.*;
import org.telegram.messenger.ApplicationLoader;

/** 复用已有状态同步发本地系统通知，不启动服务、轮询或未经配置的推送。本地与后台服务在类锁内串行消费。 */
public final class TaskNotifications {
    public static final String ACTION = "com.butang.codextop.TASK_NOTIFICATION";
    private static final String CHANNEL = "codex_tasks";
    private static TaskNotificationEvents events;
    private static String activeOwner;
    private static JsonObject routes;
    private static TaskNotificationDelivery delivery;
    private static Context notificationContext;

    /** 后台服务传入应用上下文，无需初始化Telegram账号或桌面连接。 */
    public static synchronized void initializeContext(Context context) {
        notificationContext = context.getApplicationContext();
    }

    /** 本地既有入口可沿应用上下文，服务路径优先使用显式上下文。 */
    private static Context context() {
        return notificationContext != null ? notificationContext : ApplicationLoader.applicationContext;
    }
    /** 本地票据对应的可信路由，Intent 不携带电脑、会话或凭据。 */
    public static final class Route {
        public final String machine, remote;
        /** 仅从当前账号持久票据恢复路由。 */
        private Route(String machine,String remote) { this.machine=machine; this.remote=remote; }
    }
    /** 使用应用私有偏好，只保存最多256个事件与64条路由。 */
    private static SharedPreferences preferences() {
        return context().getSharedPreferences("codex-task-notifications",0);
    }
    /** 取得本应用通知管理器，不触碰其他应用通知。 */
    private static NotificationManager manager() {
        return (NotificationManager) context().getSystemService(Context.NOTIFICATION_SERVICE);
    }
    /** 按真实服务与账号摘要隔离；切换身份清除旧通知及路由。 */
    private static void owner(String owner) {
        if(owner.equals(activeOwner)&&events!=null) return;
        SharedPreferences prefs=preferences();
        Set<String> saved=new LinkedHashSet<>();
        Set<String> delivered=new LinkedHashSet<>();
        routes=new JsonObject();
        if(owner.equals(prefs.getString("owner",""))) {
            try {
                for(JsonElement key:JsonParser.parseString(prefs.getString("seen","[]")).getAsJsonArray()) saved.add(key.getAsString());
                routes=JsonParser.parseString(prefs.getString("routes","{}")).getAsJsonObject();
                for(JsonElement key:JsonParser.parseString(prefs.getString("delivered","[]")).getAsJsonArray()) delivered.add(key.getAsString());
            } catch(RuntimeException error) { routes=new JsonObject(); saved.clear(); delivered.clear(); }
        } else { manager().cancelAll(); prefs.edit().clear().apply(); }
        activeOwner=owner;
        events=new TaskNotificationEvents(saved);
        delivery=new TaskNotificationDelivery(delivered);
    }
    /** 主列表首次前台请求通知权限；拒绝后不随刷新或重进反复打扰。 */
    public static void requestPermissionIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || Build.VERSION.SDK_INT < 33 || !CodexRuntime.loggedIn()) return;
        String permission = android.Manifest.permission.POST_NOTIFICATIONS;
        if (activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences consent = ApplicationLoader.applicationContext
                .getSharedPreferences("codex-notification-permission", Context.MODE_PRIVATE);
        if (consent.getBoolean("requested", false)) return;
        // 先记本次展示，系统弹窗引发的生命周期恢复不得立即再请求。
        consent.edit().putBoolean("requested", true).apply();
        activity.requestPermissions(new String[]{permission}, 1108);
    }

    /** 断连后所有首快照静默；不从失效状态恢复期间补推历史。 */
    public static synchronized void disconnected() { if(events!=null) events.reset(); }
    /** 退出或换号立即取消通知，销毁旧路由且不保存账号凭据。 */
    public static synchronized void clear() {
        manager().cancelAll(); preferences().edit().clear().apply();
        events=null; routes=null; activeOwner=null; delivery=null;
    }
    /** 同次已接受事实才可通知；过期、缓存及被owner拒绝的响应不能驱动事件。 */
    public static synchronized void accept(String owner,String machine,String remote,SessionStatus.Snapshot fact,
            JsonObject observation,long receivedAt) {
        owner(owner);
        String scope=TranscriptStore.digest(machine.length()+":"+machine+remote);
        if(!"current".equals(fact.validity)) { events.invalidate(scope); return; }
        if(fact.observedAtElapsedMs!=receivedAt) return;
        Set<String> requests=new LinkedHashSet<>();
        Set<String> requestIds=new LinkedHashSet<>();
        if("needs_input".equals(fact.state)) {
            try {
                if(observation==null||!fact.turnId.equals(observation.get("turnId").getAsString())) { events.invalidate(scope); return; }
                for(JsonElement value:observation.getAsJsonArray("requests")) {
                    JsonObject request=value.getAsJsonObject();
                    String kind=request.get("kind").getAsString();
                    String id=request.get("requestId").getAsString();
                    if(id.isEmpty()||(!"permission_request".equals(kind)&&!"user_action_request".equals(kind))) return;
                    requests.add(kind+":"+id);
                    requestIds.add(id);
                }
            } catch(RuntimeException error) { events.invalidate(scope); return; }
        }
        boolean emit=events.accept(scope,fact.turnId,fact.state,requests,receivedAt);
        // 去重先持久化，权限关闭时也消费事实，稍后开启不会补刷历史。
        JsonArray seen=new JsonArray(); for(String key:events.seen()) seen.add(key);
        String serialized=seen.toString();
        if(!owner.equals(preferences().getString("owner",""))
                ||!serialized.equals(preferences().getString("seen","")))
            preferences().edit().putString("owner",owner).putString("seen",serialized).apply();
        if(emit) deliver(owner,machine,remote,fact.turnId,fact.state,requestIds);
    }

    /** 已由服务核对账号、服务和时效的事件无需在线基线；不改当前状态或授权回答。 */
    public static synchronized void acceptRemote(Context context,String owner,TaskPushEvent event) {
        initializeContext(context);
        owner(owner);
        deliver(owner,event.machine,event.remote,event.turn,event.kind,
                "needs_input".equals(event.kind)?Collections.singleton(event.requestId):Collections.emptySet());
    }

    /** 两通道共用同一投递账本、固定文案和私有路由；持久化成功后才展示系统通知。 */
    private static void deliver(String owner,String machine,String remote,String turn,String kind,Set<String> requests) {
        if(!delivery.claim(TaskPushEvent.scope(machine,remote,turn),kind,requests)) return;
        JsonArray delivered=new JsonArray(); for(String key:delivery.saved()) delivered.add(key);
        NotificationManager manager=manager();
        // 权限关闭也消费事件，稍后开权限不会重放通知。FCM回调返回前完成小型落盘。
        if(Build.VERSION.SDK_INT>=24&&!manager.areNotificationsEnabled()) {
            preferences().edit().putString("owner",owner).putString("delivered",delivered.toString()).commit();
            return;
        }
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(new NotificationChannel(CHANNEL,"任务完成与待处理",NotificationManager.IMPORTANCE_DEFAULT));
        String ticket=UUID.randomUUID().toString();
        JsonObject route=new JsonObject(); route.addProperty("machine",machine); route.addProperty("remote",remote);
        route.addProperty("turn",turn);
        JsonArray pending=new JsonArray(); for(String request:requests) pending.add(request);
        route.add("requests",pending);
        routes.add(ticket,route);
        while(routes.size()>64) {
            String oldest=routes.keySet().iterator().next(); routes.remove(oldest); manager.cancel(oldest,1);
        }
        if(!preferences().edit().putString("owner",owner).putString("routes",routes.toString())
                .putString("delivered",delivered.toString()).commit()) return;
        Intent intent=new Intent(context(),org.telegram.ui.LaunchActivity.class)
                .setAction(ACTION).setData(Uri.parse("codextop-task:"+ticket))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent click=PendingIntent.getActivity(context(),0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(context(),CHANNEL):new Notification.Builder(context());
        builder.setSmallIcon(org.telegram.messenger.R.drawable.notification).setContentTitle("Codex Top")
                .setContentText("completed".equals(kind)?"任务已完成，点击查看":"任务需要你处理，点击查看")
                .setContentIntent(click).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE);
        try { manager.notify(ticket,1,builder.build()); }
        catch(SecurityException ignored) { /* 未授予通知权限时不伪造送达结果。 */ }
    }
    /** Intent 只提供随机票据；目标从当前账号私有存储查出，外部extras不授予读取权限。 */
    public static synchronized Route resolve(String owner,Intent intent) {
        owner(owner);
        if(intent==null||!ACTION.equals(intent.getAction())||intent.getData()==null
                ||!"codextop-task".equals(intent.getData().getScheme())) return null;
        String ticket=intent.getData().getSchemeSpecificPart();
        try {
            JsonObject route=routes.getAsJsonObject(ticket);
            if(route==null) return null;
            return new Route(route.get("machine").getAsString(),route.get("remote").getAsString());
        } catch(RuntimeException error) { return null; }
    }
}
