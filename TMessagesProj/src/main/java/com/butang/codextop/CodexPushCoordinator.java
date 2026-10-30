package com.butang.codextop;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 独立FCM初始化与账号绑定；只运行事件触发的串行任务，不初始化Telegram账号或新增轮询。 */
public final class CodexPushCoordinator {
    /** 短生命周期HTTP授权，刻意不携带会话解密种子或密码，不写日志或持久化。 */
    public static final class Account {
        public final String server, accountId, bearerToken;
        /** 从已解密真实登录会话提取服务端注册所需的最小身份。 */
        private Account(PasswordLogin.Session session) {
            server=session.server; accountId=session.accountId; bearerToken=session.token;
        }
    }
    /** 由主应用注入已确认的服务端契约；所有调用位于独立串行后台线程。 */
    public interface RegistrationTransport {
        /** 以认证账号幂等绑定本安装与FCM token；实现须设置有限网络超时。 */
        void register(Account account,String fcmToken) throws Exception;
        /** 撤销本安装的旧账号绑定；失败不得阻止本地退出。 */
        void unregister(Account account,String fcmToken) throws Exception;
    }
    private static final ExecutorService queue=Executors.newSingleThreadExecutor(runnable -> {
        Thread thread=new Thread(runnable,"codex-fcm-registration"); thread.setDaemon(true); return thread;
    });
    private static volatile Context application;
    private static volatile RegistrationTransport transport;
    private static volatile boolean configured;
    private static volatile long generation;
    private static String registeredOwner, registeredToken;

    /** 仅用显式Codex配置初始化默认Firebase应用；发现已有不同项目时拒绝借用其token。 */
    public static void initialize(Context context,FirebaseOptions options,RegistrationTransport registration) {
        if(context==null||options==null) return;
        Context app=context.getApplicationContext();
        if(!app.getPackageName().startsWith("com.butang.codextop.nativepreview")) return;
        synchronized(TaskNotifications.class) {
            application=app;
            TaskNotifications.initializeContext(app);
            try {
                FirebaseApp firebase;
                try { firebase=FirebaseApp.getInstance(); }
                catch(IllegalStateException missing) { firebase=FirebaseApp.initializeApp(app,options); }
                FirebaseOptions actual=firebase.getOptions();
                configured=options.getApplicationId().equals(actual.getApplicationId())
                        && java.util.Objects.equals(options.getProjectId(),actual.getProjectId())
                        && java.util.Objects.equals(options.getGcmSenderId(),actual.getGcmSenderId())
                        && java.util.Objects.equals(options.getApiKey(),actual.getApiKey());
                if(!configured) return;
                transport=registration;
            } catch(RuntimeException error) { configured=false; return; }
        }
        reconcile();
    }

    /** 前台恢复、网络恢复或首次初始化时补注册；账号未变且token未变则复用成功绑定。 */
    public static void reconcile() {
        final Context app=application;
        final long epoch=generation;
        if(app==null||!configured) return;
        queue.execute(() -> {
            if(!current(app,epoch)) return;
            Account account=readAccount(app);
            if(account==null) return;
            try {
                FirebaseMessaging messaging=FirebaseMessaging.getInstance();
                messaging.setAutoInitEnabled(true);
                messaging.getToken().addOnCompleteListener(queue,task -> {
                    if(!task.isSuccessful()||!current(app,epoch)) return;
                    register(app,epoch,account,task.getResult());
                });
            } catch(RuntimeException ignored) { /* 下次既有生命周期事件重试，不建立后台定时器。 */ }
        });
    }

    /** 登录保存成功后解除退出屏障，再按真实当前账号重新注册。 */
    public static void onLogin() {
        Context app=application;
        if(app==null) return;
        synchronized(TaskNotifications.class) {
            generation++;
            preferences(app).edit().putBoolean("logoutPending",false).commit();
        }
        reconcile();
    }

    /** 退出删除凭据前调用；立即阻止迟到接收，并排队撤销旧账号安装绑定。 */
    public static void onLogout(PasswordLogin.Session session) {
        Context app=application;
        if(app==null) return;
        final Account old=session==null?null:new Account(session);
        synchronized(TaskNotifications.class) {
            generation++;
            // 持久屏障覆盖退出到凭据删除之间的进程重启，不把旧登录重新认领为有效。
            preferences(app).edit().putBoolean("logoutPending",true).commit();
            TaskNotifications.clear();
        }
        final long logoutEpoch=generation;
        final RegistrationTransport currentTransport=transport;
        queue.execute(() -> {
            registeredOwner=null; registeredToken=null;
            if(old!=null&&currentTransport!=null) for(String token:tokens(app,old)) {
                try {
                    currentTransport.unregister(old,token);
                    forgetToken(app,old,token);
                } catch(Exception ignored) { /* 删除失败保留token元数据；不能宣称服务端已撤销或存在过期兜底。 */ }
            }
            synchronized(TaskNotifications.class) {
                try { if(configured&&generation==logoutEpoch) FirebaseMessaging.getInstance().setAutoInitEnabled(false); }
                catch(RuntimeException ignored) { /* 不恢复账号，也不借用其他Firebase项目。 */ }
            }
        });
    }

    /** 系统刷新token时只触发当前账号绑定，不输出token，也不调用Telegram注册。 */
    public static void onNewToken(Context context,String token) {
        Context app=application;
        if(app==null||!configured||token==null||token.isEmpty()) return;
        final long epoch;
        synchronized(TaskNotifications.class) {
            if(preferences(app).getBoolean("logoutPending",false)) return;
            // 新token废弃旧getToken回调，不能让迟到结果覆盖已刷新的注册。
            epoch=++generation;
        }
        queue.execute(() -> {
            if(!current(app,epoch)) return;
            Account account=readAccount(app);
            if(account!=null) register(app,epoch,account,token);
        });
    }

    /** 服务工作线程同步完成短本地处理；只读真实登录账号，不联网查询历史或当前题目。 */
    public static void receive(Context context,Map<String,String> data,long sentAt,int ttlSeconds) {
        Context app=context.getApplicationContext();
        if(!configured||application==null) return;
        synchronized(TaskNotifications.class) {
            if(preferences(app).getBoolean("logoutPending",false)) return;
            try(PasswordLogin.Session session=new SessionStore(app).read()) {
                if(session==null) return;
                TaskPushEvent event=TaskPushEvent.parse(data,session.server,session.accountId,
                        sentAt,ttlSeconds,System.currentTimeMillis());
                if(event==null) return;
                TaskNotifications.acceptRemote(app,TaskPushEvent.digest(session.server+"\n"+session.accountId),event);
            } catch(Exception ignored) { /* 缺账号、解密失败及过期消息都不建立通知或泄漏详情。 */ }
        }
    }

    /** 注册调用串行化；成功缓存只跳过重复POST，既有生命周期仍会重试旧token撤销。 */
    private static void register(Context app,long epoch,Account account,String token) {
        if(token==null||token.isEmpty()||!current(app,epoch)) return;
        RegistrationTransport sender=transport;
        if(sender==null) return;
        String owner=TaskPushEvent.digest(account.server+"\n"+account.accountId);
        boolean alreadyRegistered=owner.equals(registeredOwner)&&token.equals(registeredToken);
        try {
            if(!alreadyRegistered) {
                // 至多保留当前和上一条待撤销token；容量满先尝试收回最旧绑定，不无限堆积。
                Set<String> pending=tokens(app,account);
                if(!pending.contains(token)&&pending.size()>=2) {
                    String oldest=pending.iterator().next();
                    sender.unregister(account,oldest);
                    forgetToken(app,account,oldest);
                }
                // 网络请求可能已经被服务端接受；先记撤销候选，进程退出也不丢失清理所需token。
                if(!rememberToken(app,account,token)) return;
                sender.register(account,token);
                if(current(app,epoch)) { registeredOwner=owner; registeredToken=token; }
            }
            // token轮换后撤销同账号的旧token，失败保留，后续真实事件再重试。
            for(String previous:tokens(app,account)) if(!previous.equals(token)) {
                try { sender.unregister(account,previous); forgetToken(app,account,previous); }
                catch(Exception ignored) { /* 本次新绑定仍有效，旧绑定待撤销。 */ }
            }
        } catch(Exception ignored) { /* 不伪造注册或撤销成功，下一次明确生命周期事件继续处理。 */ }
    }

    /** 仅串行线程恢复凭据副本；离开作用域即清除不用于推送的解密种子。 */
    private static Account readAccount(Context app) {
        synchronized(TaskNotifications.class) {
            try(PasswordLogin.Session session=new SessionStore(app).read()) {
                return session==null?null:new Account(session);
            } catch(Exception error) { return null; }
        }
    }

    /** 内存代次及持久退出屏障一起阻挡迟到结果。 */
    private static boolean current(Context app,long epoch) {
        return configured&&generation==epoch&&!preferences(app).getBoolean("logoutPending",false);
    }

    /** 私有偏好仅保留退出屏障及有界token撤销元数据，不保存账号凭据。 */
    private static SharedPreferences preferences(Context app) {
        return app.getSharedPreferences("codex-fcm-registration",Context.MODE_PRIVATE);
    }

    /** 用账号摘要选择待撤销token，原始账号及bearer均不写入此账本。 */
    private static String owner(Account account) {
        return TaskPushEvent.digest(account.server+"\n"+account.accountId);
    }

    /** 恢复最多16条token候选；仅串行线程访问，token不含空白分隔符。 */
    private static Set<String> tokenRows(Context app) {
        Set<String> rows=new LinkedHashSet<>();
        for(String row:preferences(app).getString("tokenRows","").split("\n"))
            if(!row.isEmpty()&&rows.size()<16) rows.add(row);
        return rows;
    }

    /** 返回当前认证账号的token副本，其他账号残留不得用此账号授权撤销。 */
    private static Set<String> tokens(Context app,Account account) {
        Set<String> result=new LinkedHashSet<>();
        String prefix=owner(account)+"\t";
        for(String row:tokenRows(app)) if(row.startsWith(prefix)) result.add(row.substring(prefix.length()));
        return result;
    }

    /** 注册前同步记账；容量或持久化失败则不发送无法追踪的注册请求。 */
    private static boolean rememberToken(Context app,Account account,String token) {
        if(token.length()>4096||token.chars().anyMatch(Character::isWhitespace)) return false;
        Set<String> rows=tokenRows(app);
        String row=owner(account)+"\t"+token;
        if(rows.contains(row)) return true;
        if(rows.size()>=16||tokens(app,account).size()>=2) return false;
        rows.add(row);
        return preferences(app).edit().putString("tokenRows",String.join("\n",rows)).commit();
    }

    /** 服务端确认撤销后才删除本地候选，失败元数据留给同账号后续清理。 */
    private static void forgetToken(Context app,Account account,String token) {
        Set<String> rows=tokenRows(app);
        rows.remove(owner(account)+"\t"+token);
        preferences(app).edit().putString("tokenRows",String.join("\n",rows)).commit();
    }
}
