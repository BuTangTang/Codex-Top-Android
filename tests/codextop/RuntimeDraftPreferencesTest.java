package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实草稿命名、冷恢复、登录及连接方法；仅替代存储、网络和Android边界。 */
public final class RuntimeDraftPreferencesTest {
    /** 可用前像复跑同一并发断言；全部身份为合成值，不读取任何正式草稿或凭据。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        String original = Files.readString(source);
        var owner = StaticJavaParser.parse(original).getClassByName("CodexRuntime").orElseThrow();
        StringBuilder methods = new StringBuilder();
        for (String name : new String[]{"loggedIn", "restoreSession", "isAccountCurrent", "draftPreferencesName", "openDesktop", "login"}) {
            for (var method : owner.getMethodsByName(name)) methods.append(method.toString().replace("android.content.SharedPreferences", "Prefs")).append('\n');
        }
        // 执行logout实际同步失效前缀，后续清理队列不参与草稿名读取。
        methods.append("static void beginLogout(){");
        for (var statement : owner.getMethodsByName("logout").get(0).getBody().orElseThrow().getStatements()) {
            if (statement.toString().startsWith("Utilities.globalQueue.postRunnable")) break;
            methods.append(statement.toString().replace("org.telegram.messenger.UserConfig.selectedAccount", "selectedAccount"));
        }
        methods.append("}\n");
        Path sourceRoot = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        var store = StaticJavaParser.parse(sourceRoot.resolve("TranscriptStore.java")).getClassByName("TranscriptStore").orElseThrow();
        methods.append("static class TranscriptStore {").append(store.getMethodsByName("digest").get(0)).append("}\n");
        var password = StaticJavaParser.parse(sourceRoot.resolve("PasswordLogin.java")).getClassByName("PasswordLogin").orElseThrow();
        var session = password.getMembers().stream().filter(v -> v.isClassOrInterfaceDeclaration() && v.asClassOrInterfaceDeclaration().getNameAsString().equals("Session")).findFirst().orElseThrow();
        methods.append("static class PasswordLogin {").append(session).append("static Session login(String server,String name,String password){return nextLogin;} }\n");
        String invoke = args.length > 1 ? args[1] : "connectionDoesNotBlockDrafts();naming();races();cold(\"restored\");cold(\"damaged\");cold(\"absent\");";
        Path temp = Files.createTempDirectory("runtime-draft-preferences-");
        try {
            Path probe = temp.resolve("DraftPreferencesProbe.java");
            Files.writeString(probe, FIXTURE + methods + CASES + "public static void run()throws Exception{" + invoke + "}\n}\n");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temp.toString(), probe.toString()) != 0)
                throw new AssertionError("实际草稿方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("DraftPreferencesProbe").getMethod("run").invoke(null); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("实际草稿方法行为失败", error.getCause()); }
            }
            if (!Files.readString(source).equals(original)) throw new AssertionError("测试期间Runtime发生变化");
        } finally {
            try (var paths = Files.walk(temp)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p); }
        }
    }

    private static final String FIXTURE = """
        import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
        public final class DraftPreferencesProbe {
            static final String SERVER="synthetic-server";
            static volatile PasswordLogin.Session session;static volatile long accountGeneration;static volatile boolean loggingOut,sessionRestored;
            static boolean draftOwnerSaved;static String preferredMachine;static volatile DesktopConnection desktop;
            static Map<String,DesktopConnection> desktopConnections=new ConcurrentHashMap<>();static Map<String,String> machineNames=new ConcurrentHashMap<>();
            static long watchGeneration,watchedDialog;static int selectedAccount;
            static PasswordLogin.Session restored,nextLogin;static boolean readFails;
            static AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();
            static CountDownLatch connectionEntered,connectionRelease,readEntered,readRelease;
            static final class ApplicationLoader {static Context applicationContext=new Context();}
            static final class Context {
                final Prefs preferences=new Prefs();
                /** 只提供合成归属元数据，不读取Android偏好。 */
                Prefs getSharedPreferences(String name,int mode){check(name.equals("codex-preferences"),"wrong metadata owner");return preferences;}
            }
            static final class Prefs {
                volatile String legacyOwner;volatile Runnable onGet;int gets,commits;
                /** 沿原元数据是否存在判断。 */ boolean contains(String key){return legacyOwner!=null;}
                /** 允许精确在正式命名计算之后切换账号，测试最终重验。 */
                String getString(String key,String fallback){gets++;Runnable action=onGet;onGet=null;if(action!=null)action.run();return legacyOwner==null?fallback:legacyOwner;}
                /** 只模拟原一次归属保存的Android边界。 */ Editor edit(){return new Editor(this);}
            }
            static final class Editor {
                final Prefs prefs;String value;Editor(Prefs prefs){this.prefs=prefs;}
                Editor putString(String key,String value){this.value=value;return this;}
                boolean commit(){prefs.legacyOwner=value;prefs.commits++;return true;}
            }
            static final class SessionStore {
                SessionStore(Context ignored){}
                /** 网络和磁盘均不执行；闩锁只控制实际restoreSession的并发时序。 */
                PasswordLogin.Session read()throws Exception{reads.incrementAndGet();if(readEntered!=null){readEntered.countDown();await(readRelease,"cold read release");}if(readFails)throw new java.io.IOException("synthetic damaged session");return restored;}
                void save(PasswordLogin.Session value){writes.incrementAndGet();}
            }
            static final class DesktopConnection {
                String machineId="synthetic-machine",machineName="synthetic-name";
                /** 真openDesktop持锁调用本边界，未释放时模拟连接仍等待。 */
                static DesktopConnection open(PasswordLogin.Session value,String machine)throws Exception{connectionEntered.countDown();await(connectionRelease,"connection release");return new DesktopConnection();}
                void close(){}
            }
            static void onDesktopConnected(DesktopConnection connection){}
            static void await(CountDownLatch latch,String label)throws Exception{check(latch.await(3,TimeUnit.SECONDS),label);}
            static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
            static PasswordLogin.Session account(String server,String id){return new PasswordLogin.Session(server,"synthetic-token",id,"synthetic-name",new byte[32]);}
            static void reset(){ApplicationLoader.applicationContext=new Context();session=account("synthetic-server","a");accountGeneration=10;loggingOut=false;sessionRestored=true;draftOwnerSaved=true;desktop=null;preferredMachine=null;desktopConnections.clear();machineNames.clear();reads.set(0);writes.set(0);readFails=false;readEntered=readRelease=null;restored=session;nextLogin=account("synthetic-server","b");connectionEntered=new CountDownLatch(1);connectionRelease=new CountDownLatch(1);}
            static String key(PasswordLogin.Session owner){return TranscriptStore.digest(owner.server+"\\n"+owner.accountId);}
            static String scoped(PasswordLogin.Session owner,int telegram){return "codex-drafts-"+key(owner)+"-"+telegram;}
            static String signedOut(int telegram){return "codex-drafts-signed-out-"+telegram;}
        """;

    private static final String CASES = """
        /** 决定性反例：连接锁持续持有时，已经恢复账号的草稿查询必须完成。 */
        static void connectionDoesNotBlockDrafts()throws Exception{
            reset();ApplicationLoader.applicationContext.preferences.legacyOwner=key(session);
            ExecutorService pool=Executors.newFixedThreadPool(2);Future<?> connector=null;
            try{
                connector=pool.submit(()->{try{openDesktop();}catch(Exception e){throw new RuntimeException(e);}});
                await(connectionEntered,"real openDesktop entered external boundary");
                check(loggedIn(),"restored loggedIn");Future<String> result=pool.submit(()->draftPreferencesName(0));
                try{check(result.get(500,TimeUnit.MILLISECONDS).equals("drafts"),"legacy draft result");}
                catch(TimeoutException error){throw new AssertionError("已恢复草稿命名仍等待后台openDesktop网络类锁",error);}
                check(connectionRelease.getCount()==1&&!connector.isDone(),"network was released to pass fast read");
                check(reads.get()==0,"restored draft lookup touched SessionStore");
                System.out.println("PASS actual openDesktop pending / restored draft lookup completes / original legacy name / no session read");
            }finally{connectionRelease.countDown();if(connector!=null)connector.get(1,TimeUnit.SECONDS);pool.shutdown();check(pool.awaitTermination(1,TimeUnit.SECONDS),"threads ended");}
        }
        /** 账号、服务、原legacy归属和Telegram槽位保持原命名。 */
        static void naming(){
            for(boolean legacy:new boolean[]{false,true})for(int telegram:new int[]{0,1,3}){
                reset();if(legacy)ApplicationLoader.applicationContext.preferences.legacyOwner=key(session);
                check(draftPreferencesName(telegram).equals(legacy?(telegram==0?"drafts":"drafts"+telegram):scoped(session,telegram)),"stable naming");
            }
            reset();String first=draftPreferencesName(0);session=account("synthetic-server","b");check(!draftPreferencesName(0).equals(first),"different account alias");session=account("synthetic-other","a");check(!draftPreferencesName(0).equals(first),"different server alias");
            for(int state=0;state<2;state++){reset();if(state==0)session=null;else loggingOut=true;check(draftPreferencesName(1).equals(signedOut(1)),"signed out naming");check(ApplicationLoader.applicationContext.preferences.gets==0,"signed out read account preferences");}
            System.out.println("PASS naming 10 cases: legacy/scoped, 3 Telegram slots, account/server isolation, null/logout");
        }
        /** 在真实摘要计算后、返回前切换身份，不能泄露过期的账号草稿名。 */
        static void races(){
            for(String kind:new String[]{"logout","logout-failed","null","epoch","same-account","different-account","logout-login"}){
                reset();ApplicationLoader.applicationContext.preferences.legacyOwner=key(session);
                ApplicationLoader.applicationContext.preferences.onGet=()->{
                    if(kind.equals("logout"))beginLogout();
                    else if(kind.equals("logout-failed")){beginLogout();loggingOut=false;}
                    else if(kind.equals("null"))session=null;
                    else if(kind.equals("epoch"))accountGeneration++;
                    else if(kind.equals("same-account"))session=account("synthetic-server","a");
                    else if(kind.equals("different-account"))session=account("synthetic-server","b");
                    else {beginLogout();session=null;loggingOut=false;try{login("synthetic-name","synthetic-password");}catch(Exception e){throw new RuntimeException(e);}}
                };
                check(draftPreferencesName(0).equals(signedOut(0)),"stale name escaped: "+kind);
                if(kind.equals("logout-login"))check(writes.get()==1&&session==nextLogin,"actual login owner path absent");
                if(kind.equals("logout-failed")||kind.equals("epoch"))check(draftPreferencesName(0).equals("drafts"),"transient invalidation permanently hid current drafts");
                if(kind.equals("logout-login"))check(draftPreferencesName(0).equals(scoped(nextLogin,0)),"new login kept old owner");
            }
            System.out.println("PASS 7 deterministic races: actual logout prefix/login plus null, epoch, same/different Session identity");
        }
        /** 真restoreSession的类锁和二次检查保持冷恢复单飞及原legacy保存。 */
        static void cold(String kind)throws Exception{
            boolean damaged=kind.equals("damaged"),empty=kind.equals("absent"),signedOutExpected=damaged||empty;
            reset();session=null;sessionRestored=false;draftOwnerSaved=false;readFails=damaged;if(empty)restored=null;readEntered=new CountDownLatch(1);readRelease=new CountDownLatch(1);
            ExecutorService pool=Executors.newFixedThreadPool(6);List<Future<String>> results=new ArrayList<>();CountDownLatch callersStarted=new CountDownLatch(6);
            try{
                results.add(pool.submit(()->{callersStarted.countDown();return draftPreferencesName(0);}));await(readEntered,"cold restore entered");
                for(int i=1;i<6;i++)results.add(pool.submit(()->{callersStarted.countDown();return draftPreferencesName(0);}));
                await(callersStarted,"all cold callers started");
                check(reads.get()==1&&!sessionRestored,"cold restore published too early");readRelease.countDown();
                for(Future<String> result:results)check(result.get(1,TimeUnit.SECONDS).equals(signedOutExpected?signedOut(0):"drafts"),"cold result");
                check(reads.get()==1&&sessionRestored&&draftOwnerSaved&&ApplicationLoader.applicationContext.preferences.commits==1,"cold restore duplicated or legacy not committed");
                check(ApplicationLoader.applicationContext.preferences.legacyOwner.equals(signedOutExpected?"":key(restored)),"cold legacy identity changed");
                if(empty){login("synthetic-name","synthetic-password");check(draftPreferencesName(0).equals(scoped(nextLogin,0)),"first login inherited anonymous legacy drafts");check(ApplicationLoader.applicationContext.preferences.legacyOwner.equals(""),"first login rewrote legacy owner");}
                System.out.println("PASS six concurrent actual cold restores: "+kind+", one read/commit, absent account never inherited on first login");
            }finally{readRelease.countDown();pool.shutdown();check(pool.awaitTermination(1,TimeUnit.SECONDS),"cold threads ended");}
        }
        """;
}
