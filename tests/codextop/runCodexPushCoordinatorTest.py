#!/usr/bin/env python3
"""在独立临时目录用合成SDK和账号验证真实协调器，不读设备或凭据。"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVAC = os.environ.get("JAVAC", "/opt/homebrew/opt/openjdk@17/bin/javac")
JAVA = os.environ.get("JAVA", "/opt/homebrew/opt/openjdk@17/bin/java")
STUBS = {
"android/content/SharedPreferences.java": '''package android.content;
public class SharedPreferences {
 private final java.util.Map<String,Object> data=new java.util.concurrent.ConcurrentHashMap<>();
 public String getString(String k,String v){return (String)data.getOrDefault(k,v);}
 public boolean getBoolean(String k,boolean v){return (Boolean)data.getOrDefault(k,v);}
 public Editor edit(){return new Editor();}
 public class Editor { private final java.util.Map<String,Object> pending=new java.util.HashMap<>();
  public Editor putString(String k,String v){pending.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
  public boolean commit(){data.putAll(pending);return true;}
 }
}''',
"android/content/Context.java": '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 private final java.util.Map<String,SharedPreferences> prefs=new java.util.concurrent.ConcurrentHashMap<>();
 public Context getApplicationContext(){return this;}
 public String getPackageName(){return "com.butang.codextop.nativepreview";}
 public SharedPreferences getSharedPreferences(String k,int m){return prefs.computeIfAbsent(k,x->new SharedPreferences());}
}''',
"com/google/firebase/FirebaseOptions.java": '''package com.google.firebase;
public class FirebaseOptions {
 public String getApplicationId(){return "app";} public String getProjectId(){return "codex";}
 public String getGcmSenderId(){return "sender";} public String getApiKey(){return "public-key";}
}''',
"com/google/firebase/FirebaseApp.java": '''package com.google.firebase;
public class FirebaseApp {
 private static FirebaseApp app; private FirebaseOptions options;
 public static FirebaseApp getInstance(){if(app==null)throw new IllegalStateException();return app;}
 public static FirebaseApp initializeApp(android.content.Context c,FirebaseOptions o){app=new FirebaseApp();app.options=o;return app;}
 public FirebaseOptions getOptions(){return options;}
}''',
"com/google/firebase/messaging/FirebaseMessaging.java": '''package com.google.firebase.messaging;
public class FirebaseMessaging {
 private static final FirebaseMessaging instance=new FirebaseMessaging();
 public final java.util.List<Task> tasks=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static FirebaseMessaging getInstance(){return instance;}
 public final java.util.List<Boolean> autoInit=new java.util.concurrent.CopyOnWriteArrayList<>();
 public void setAutoInitEnabled(boolean e){autoInit.add(e);}
 public Task getToken(){Task task=new Task();tasks.add(task);return task;}
 public static class Task {
  private java.util.concurrent.Executor executor; private java.util.function.Consumer<Task> listener; private String token;
  public boolean isSuccessful(){return true;} public String getResult(){return token;}
  public Task addOnCompleteListener(java.util.concurrent.Executor e,java.util.function.Consumer<Task> l){executor=e;listener=l;return this;}
  public void complete(String t){token=t;executor.execute(()->listener.accept(this));}
 }
}''',
"com/butang/codextop/PasswordLogin.java": '''package com.butang.codextop;
public class PasswordLogin {
 public static class Session implements AutoCloseable {
  public String server="https://example.test",accountId="a",token="synthetic-bearer";
  public void close(){}
 }
}''',
"com/butang/codextop/SessionStore.java": '''package com.butang.codextop;
public class SessionStore {
 public static boolean loggedIn=true; public SessionStore(android.content.Context context){}
 public PasswordLogin.Session read(){return loggedIn?new PasswordLogin.Session():null;}
}''',
"com/butang/codextop/TaskNotifications.java": '''package com.butang.codextop;
public class TaskNotifications {
 public static int received; public static void initializeContext(android.content.Context c){}
 public static void clear(){}
 public static void acceptRemote(android.content.Context c,String owner,TaskPushEvent e){received++;}
}''',
}
TEST = '''package com.butang.codextop;
import android.content.Context;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.*;
import java.util.concurrent.*;
/** 用可控异步token模拟迟到回调，不以源码字符串断言替代执行。 */
public class CodexPushCoordinatorTest {
 /** 等待实际协调器串行队列完成当前测试阶段。 */
 private static void drain() throws Exception {
  java.lang.reflect.Field field=CodexPushCoordinator.class.getDeclaredField("queue");field.setAccessible(true);
  ExecutorService queue=(ExecutorService)field.get(null);
  for(int i=0;i<3;i++)queue.submit(()->{}).get(3,TimeUnit.SECONDS);
 }
 /** 失败立即抛出，并给出本阶段实际结果。 */
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 /** 验证token乱序、退出屏障、重登及短同步接收。 */
 public static void main(String[] args) throws Exception {
  Context context=new Context(); List<String> calls=new CopyOnWriteArrayList<>();
  CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
  java.util.concurrent.atomic.AtomicBoolean failDelete=new java.util.concurrent.atomic.AtomicBoolean();
  List<String> deletes=new CopyOnWriteArrayList<>();
  CodexPushCoordinator.initialize(context,new FirebaseOptions(),new CodexPushCoordinator.RegistrationTransport(){
   /** 记录合成注册，不进行网络调用。 */
   public void register(CodexPushCoordinator.Account account,String token) throws Exception {
    if(token.equals("blocked")){started.countDown();release.await(3,TimeUnit.SECONDS);}
    calls.add("register:"+token);
   }
   /** 记录退出撤销，不保存授权。 */
   public void unregister(CodexPushCoordinator.Account account,String token) throws Exception {
    calls.add("unregister"); deletes.add(token);
    if(failDelete.compareAndSet(true,false)) throw new java.io.IOException("synthetic temporary delete failure");
   }
  }); drain();
  CodexPushCoordinator.onNewToken(context,"new-token");drain();
  FirebaseMessaging.getInstance().tasks.get(0).complete("old-token");drain();
  check(calls.equals(Arrays.asList("register:new-token")),"旧getToken覆盖新token: "+calls);
  Map<String,String> data=new HashMap<>();
  data.put("type","codex_task");data.put("eventKind","completed");data.put("eventKey","e");
  data.put("machineId","m");data.put("remoteSessionId","s");data.put("accountId","a");
  data.put("turnId","t");data.put("serverUrl","https://example.test");
  CodexPushCoordinator.receive(context,data,System.currentTimeMillis(),300);
  check(TaskNotifications.received==1,"有效当前账号消息未处理");
  CodexPushCoordinator.onLogout(new PasswordLogin.Session());drain();
  CodexPushCoordinator.receive(context,data,System.currentTimeMillis(),300);
  CodexPushCoordinator.onNewToken(context,"after-logout");drain();
  check(TaskNotifications.received==1,"退出屏障失效");
  check(calls.equals(Arrays.asList("register:new-token","unregister")),"退出后重新注册: "+calls);
  CodexPushCoordinator.onLogin();drain();
  FirebaseMessaging.getInstance().tasks.get(1).complete("login-token");drain();
  check(calls.get(calls.size()-1).equals("register:login-token"),"重登未恢复注册");
  data.put("serverUrl","https://other.test");
  CodexPushCoordinator.receive(context,data,System.currentTimeMillis(),300);
  check(TaskNotifications.received==1,"跨服务器消息被接收");
  failDelete.set(true);
  CodexPushCoordinator.onNewToken(context,"rotated-token");drain();
  long firstDelete=deletes.stream().filter(token->token.equals("login-token")).count();
  check(firstDelete==1,"未执行第一次失败DELETE");
  String pending=context.getSharedPreferences("codex-fcm-registration",0).getString("tokenRows","");
  check(pending.contains("login-token"),"失败DELETE丢失了待撤销token");
  long beforePost=calls.stream().filter(call->call.equals("register:rotated-token")).count();
  CodexPushCoordinator.reconcile();drain();
  FirebaseMessaging.getInstance().tasks.get(FirebaseMessaging.getInstance().tasks.size()-1).complete("rotated-token");drain();
  check(deletes.stream().filter(token->token.equals("login-token")).count()==2,
        "缓存命中没有重试旧token DELETE");
  check(calls.stream().filter(call->call.equals("register:rotated-token")).count()==beforePost,
        "缓存命中重试DELETE时重复POST");
  pending=context.getSharedPreferences("codex-fcm-registration",0).getString("tokenRows","");
  check(!pending.contains("login-token")&&pending.contains("rotated-token"),"撤销成功未收回旧token元数据");
  long disabled=FirebaseMessaging.getInstance().autoInit.stream().filter(value->!value).count();
  CodexPushCoordinator.onNewToken(context,"blocked");
  check(started.await(3,TimeUnit.SECONDS),"没有进入合成inflight注册");
  CodexPushCoordinator.onLogout(new PasswordLogin.Session());
  CodexPushCoordinator.onLogin();
  release.countDown();drain();
  check(FirebaseMessaging.getInstance().autoInit.stream().filter(value->!value).count()==disabled,
        "旧logout关闭了新登录的Firebase auto-init");
  int blocked=calls.indexOf("register:blocked");
  check(blocked>=0 && calls.subList(blocked+1,calls.size()).contains("unregister"),
        "inflight旧注册未在串行队列后续撤销");
  System.out.println("CodexPushCoordinatorTest passed");
 }
}
'''
with tempfile.TemporaryDirectory(prefix="codex-fcm-test-") as temporary:
    root = Path(temporary)
    for name, source in STUBS.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    test = root / "CodexPushCoordinatorTest.java"
    test.write_text(TEST)
    source = ROOT / "TMessagesProj/src/main/java/com/butang/codextop"
    files = list(root.rglob("*.java")) + [source / "CodexPushCoordinator.java", source / "TaskPushEvent.java"]
    subprocess.run([JAVAC, "-d", str(root / "classes"), *map(str, files)], check=True)
    subprocess.run([JAVA, "-cp", str(root / "classes"), "com.butang.codextop.CodexPushCoordinatorTest"], check=True)
