package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实Runtime额度入口，只替换队列、RPC、账号与Android主线程边界。 */
public final class RuntimeAccountUsageCoalescingTest {
    /** 独立临时目录中编译真实方法和额度模型，不读取真实账号或发起网络。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source=Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        var unit=StaticJavaParser.parse(source.resolve("CodexRuntime.java"));
        StringBuilder extracted=new StringBuilder();
        for(MethodDeclaration method:unit.findAll(MethodDeclaration.class))
            if(Set.of("readAccountUsage","isAccountCurrent","cachedAccountUsage","selectedAccountUsageMachine","selectAccountUsageMachine","accountUsageStore","restoreAccountUsageCache","saveAccountUsageCache").contains(method.getNameAsString()))extracted.append(method).append('\n');
        // 额度缓存的真实 browse 入口完整保留；只将后续网络队列工作替换为可观察的网络边界。
        var browse=unit.getClassByName("CodexRuntime").orElseThrow().getMethodsByName("browse").get(0).clone();
        for(var call:browse.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class))
            if(call.getNameAsString().equals("postRunnable")&&call.getScope().isPresent()&&call.getScope().get().toString().equals("dialogQueue"))
                call.setArgument(0,StaticJavaParser.parseExpression("() -> networkBrowseRequests++"));
        extracted.append(browse).append('\n');
        for(ClassOrInterfaceDeclaration type:unit.findAll(ClassOrInterfaceDeclaration.class))
            if(type.getNameAsString().equals("AccountUsageRead"))extracted.append(type).append('\n');
        for(VariableDeclarator field:unit.findAll(VariableDeclarator.class))
            if(Set.of("accountUsageReads","accountUsageMachine","accountUsageRestoredEpoch").contains(field.getNameAsString()))extracted.append(field.getParentNode().orElseThrow()).append('\n');
        Path temp=Files.createTempDirectory("codex-usage-probe");
        try{
            Path probe=temp.resolve("UsageProbe.java");Files.writeString(probe,FIXTURE+extracted+SCENARIOS+"}");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-cp",System.getProperty("java.class.path"),"-d",temp.toString(),
                    probe.toString(),source.resolve("AccountUsage.java").toString(),source.resolve("AccountUsageStore.java").toString(),
                    source.resolve("DialogStore.java").toString(),source.resolve("BrowseStore.java").toString(),source.resolve("TranscriptStore.java").toString(),
                    source.resolve("TranscriptWindow.java").toString(),source.resolve("TranscriptText.java").toString(),source.resolve("DesktopAttachment.java").toString())!=0)throw new AssertionError("真实额度方法夹具编译失败");
            var urls=new java.util.ArrayList<java.net.URL>();urls.add(temp.toUri().toURL());
            for(String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator))urls.add(Path.of(entry).toUri().toURL());
            try(var loader=new URLClassLoader(urls.toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())){
                try{loader.loadClass("com.butang.codextop.UsageProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{temp.toString()});}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("真实额度并发合并回归失败",e.getCause());}
            }
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    // 替身仅安排调用时机和返回内容；合并、归属校验和缓存发布均来自真实方法。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import java.util.*;import java.util.function.*;import com.google.gson.*;import java.io.*;import java.nio.file.*;
        public final class UsageProbe {
            static final class Queue {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();void postRunnable(Runnable r){tasks.add(r);}void one(){tasks.remove().run();}void all(){int n=60;while(!tasks.isEmpty()){if(--n==0)throw new AssertionError("queue loop");one();}}}
            static final Queue approvalQueue=new Queue(),ui=new Queue(),dialogQueue=new Queue();
            static int networkBrowseRequests;
            static final Map<String,JsonObject> browseSnapshots=new HashMap<>();
            interface BrowseCallback{void accept(JsonObject value,String error,boolean cached);}
            static JsonObject cachedBrowseSnapshot(JsonObject value){return value.deepCopy();}
            static JsonObject prepareBrowseSnapshot(String kind,String machine,JsonObject value,JsonArray rows,PasswordLogin.Session owner,long epoch){return value;}
            static void publishBrowse(int account,long epoch,String key,String kind,String machine,JsonObject value,boolean cached,long start,long end,BrowseCallback callback){AndroidUtilities.runOnUIThread(()->{if(isAccountCurrent(epoch))callback.accept(value,null,cached);});}
            static final class AndroidUtilities {static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
            static final class PasswordLogin {static final class Session {String server="test-server",accountId="account";}}
            static final class Utilities {static final Queue globalQueue=new Queue();}
            static final class ApplicationLoader {static final Context applicationContext=new Context();}
            static final class Context {File root;File getNoBackupFilesDir(){return root;}}
            static boolean loggedIn(){return isAccountCurrent(accountGeneration);}
            static PasswordLogin.Session session;static long accountGeneration;static boolean loggingOut;
            static final Map<String,DesktopConnection> desktopConnections=new java.util.concurrent.ConcurrentHashMap<>();
            static final Map<String,AccountUsage> accountUsageSnapshots=new java.util.concurrent.ConcurrentHashMap<>();
            static DesktopConnection opened;static boolean failOpen;static int opens;static Runnable afterOpenPut;
            static final class DesktopConnection {final AccountUsage value;int reads;boolean fail;Runnable during;
                DesktopConnection(AccountUsage v){value=v;}AccountUsage readAccountUsage()throws Exception{reads++;if(during!=null){Runnable r=during;during=null;r.run();}if(fail)throw new java.io.IOException();return value;}}
            /** 提供原连接获取边界，不复制额度合并或归属判断。 */
            static DesktopConnection openDesktop(String machine)throws Exception{opens++;if(failOpen)throw new java.io.IOException();
                DesktopConnection c=desktopConnections.get(machine);if(c==null){c=opened;desktopConnections.put(machine,c);if(afterOpenPut!=null){Runnable r=afterOpenPut;afterOpenPut=null;r.run();}}return c;}
            static final class Result implements BiConsumer<AccountUsage,String> {int calls;AccountUsage usage;String error;
                public void accept(AccountUsage u,String e){calls++;usage=u;error=e;}}
        """;
    private static final String SCENARIOS="""
            /** 合成两个不同余额只用于区分响应归属，不触及真实来源。 */
            static AccountUsage usage(int remaining)throws Exception{return AccountUsage.parse(JsonParser.parseString("{\\"status\\":\\"available\\",\\"source\\":{\\"kind\\":\\"codexHome\\",\\"home\\":\\"user\\"},\\"fetchedAtMs\\":100,\\"staleAtMs\\":200,\\"meters\\":[{\\"label\\":\\"synthetic\\",\\"remainingPct\\":"+remaining+",\\"status\\":\\"ok\\"}]}").getAsJsonObject());}
            /** 每个场景换原账号代次；旧在途记录不得影响新场景。 */
            static DesktopConnection reset()throws Exception{accountGeneration++;session=new PasswordLogin.Session();session.accountId="account-"+accountGeneration;accountUsageRestoredEpoch=accountGeneration;accountUsageMachine=null;loggingOut=false;opens=0;failOpen=false;afterOpenPut=null;
                Utilities.globalQueue.tasks.clear();approvalQueue.tasks.clear();ui.tasks.clear();dialogQueue.tasks.clear();browseSnapshots.clear();networkBrowseRequests=0;desktopConnections.clear();accountUsageSnapshots.clear();opened=new DesktopConnection(usage(10));desktopConnections.put("m",opened);return opened;}
            static void networkOne(){Utilities.globalQueue.all();approvalQueue.one();}
            static void pump(){int rounds=0;do{Utilities.globalQueue.all();approvalQueue.all();ui.all();if(++rounds>20)throw new AssertionError("pump loop");}
                while(!Utilities.globalQueue.tasks.isEmpty()||!approvalQueue.tasks.isEmpty()||!ui.tasks.isEmpty());}
            static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
            /** 冷恢复仍来自真实存储和方法；合成队列确认本地不等待网络、账号及连接不串值。 */
            static void persistence()throws Exception{
                DesktopConnection first=reset();selectAccountUsageMachine("m");Result original=new Result();readAccountUsage("m",original);pump();
                AccountUsageStore store=accountUsageStore(session);check(store.read().machines.get("m").meters.get(0).remainingPercent==10,"successful quota not persisted");
                accountUsageSnapshots.clear();accountUsageMachine=null;accountUsageRestoredEpoch=-1;
                Result fresh=new Result();readAccountUsage("m",fresh);Utilities.globalQueue.all();ui.all();
                check(first.reads==1&&fresh.calls==0&&"m".equals(selectedAccountUsageMachine())&&cachedAccountUsage("m").meters.get(0).remainingPercent==10,"cold local quota or choice waited for queued network");
                first.fail=true;pump();check(fresh.calls==1&&fresh.error!=null&&cachedAccountUsage("m").available&&store.read().machines.containsKey("m"),"cold network failure erased stored quota");
                first.fail=false;AccountUsage b=usage(20);store.save("b",b);DesktopConnection changed=new DesktopConnection(AccountUsage.parse(JsonParser.parseString("{\\"status\\":\\"unavailable\\",\\"reason\\":\\"account_changed\\"}").getAsJsonObject()));
                desktopConnections.put("m",changed);readAccountUsage("m",new Result());pump();
                check(!cachedAccountUsage("m").available&&!store.read().machines.containsKey("m")&&store.read().machines.containsKey("b"),"explicit source account change did not clear only that machine");
                store.save("m",first.value);readAccountUsage("m",new Result());networkOne();ui.all();desktopConnections.put("m",new DesktopConnection(usage(40)));Utilities.globalQueue.all();
                check(!store.read().machines.containsKey("m"),"reconnect after accepted identity change resurrected old disk account");
                // 挂起磁盘恢复的UI回调后切账号，旧磁盘数据也不能进入新账号。
                first=reset();store=accountUsageStore(session);store.select("m");store.save("m",first.value);accountUsageRestoredEpoch=-1;
                restoreAccountUsageCache(session,accountGeneration);accountGeneration++;session=new PasswordLogin.Session();session.accountId="new-account";ui.all();
                check(accountUsageSnapshots.isEmpty()&&accountUsageMachine==null&&accountUsageStore(session).read().machines.isEmpty(),"late disk restore crossed account boundary");
                // 同账号已接受的新采集值优先于迟到的旧磁盘快照。
                first=reset();store=accountUsageStore(session);store.select("m");store.save("m",first.value);accountUsageRestoredEpoch=-1;
                restoreAccountUsageCache(session,accountGeneration);AccountUsage newest=usage(30);accountUsageSnapshots.put("m",newest);accountUsageMachine="b";ui.all();
                check(cachedAccountUsage("m")==newest&&"b".equals(selectedAccountUsageMachine()),"late disk restore replaced new quota or explicit choice");
                // 已采集值回调后切账号，旧写盘任务不能污染新账号文件。
                first=reset();Result accepted=new Result();readAccountUsage("m",accepted);networkOne();ui.all();accountGeneration++;session=new PasswordLogin.Session();session.accountId="next-account";pump();
                check(accountUsageStore(session).read().machines.isEmpty(),"late quota save crossed account boundary");
                // 真实文件写入失败不撤销已收到的数值。
                first=reset();File originalRoot=ApplicationLoader.applicationContext.root;Path blocked=originalRoot.toPath().resolve("blocked");Files.createDirectories(blocked.getParent());Files.writeString(blocked,"not-directory");ApplicationLoader.applicationContext.root=blocked.toFile();
                readAccountUsage("m",new Result());pump();check(cachedAccountUsage("m")==first.value,"disk failure cleared accepted memory");ApplicationLoader.applicationContext.root=originalRoot;
            }
            /** 原电脑浏览的本地阶段先发额度；网络暂不运行，迟到本地回调仍受账号保护。 */
            static void browseLocalStage()throws Exception{
                DesktopConnection first=reset();AccountUsageStore store=accountUsageStore(session);store.select("m");store.save("m",first.value);accountUsageRestoredEpoch=-1;
                int[] callbacks={0};browse(0,"computers",null,null,null,(value,error,cached)->{
                    callbacks[0]++;check(cached&&cachedAccountUsage("m")!=null&&"m".equals(selectedAccountUsageMachine()),"local browse callback preceded quota restoration");
                });
                Utilities.globalQueue.all();ui.all();check(callbacks[0]==1&&networkBrowseRequests==0&&first.reads==0,"local quota waited for computer RPC or created a quota RPC");
                check(dialogQueue.tasks.size()==1,"original computer network work lost");
                first=reset();store=accountUsageStore(session);store.select("m");store.save("m",first.value);accountUsageRestoredEpoch=-1;
                JsonObject list=JsonParser.parseString("{\\"rows\\":[{\\"id\\":\\"m\\",\\"name\\":\\"synthetic\\",\\"active\\":false}]}").getAsJsonObject();
                new BrowseStore(new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),"codex-browse"),session.server,session.accountId).write("computers",null,null,list);
                callbacks[0]=0;browse(0,"computers",null,null,null,(value,error,cached)->{callbacks[0]++;check(cached&&value!=null&&cachedAccountUsage("m")!=null,"disk computer callback lost local quota");});
                Utilities.globalQueue.all();ui.all();check(callbacks[0]==1&&networkBrowseRequests==0,"disk browse emitted duplicate local callback or waited network");
                first=reset();store=accountUsageStore(session);store.select("m");store.save("m",first.value);accountUsageRestoredEpoch=-1;
                readAccountUsage("m",new Result());Utilities.globalQueue.all();callbacks[0]=0;
                browse(0,"computers",null,null,null,(value,error,cached)->{callbacks[0]++;check(cachedAccountUsage("m")!=null,"preceding restore not visible in local phase");});
                Utilities.globalQueue.all();ui.all();check(callbacks[0]==1&&first.reads==0,"previously queued quota restoration left new page waiting for network");
                first=reset();accountUsageRestoredEpoch=-1;callbacks[0]=0;browse(0,"computers",null,null,null,(value,error,cached)->callbacks[0]++);
                Utilities.globalQueue.all();accountGeneration++;session=new PasswordLogin.Session();ui.all();check(callbacks[0]==0,"late local browse callback crossed account");
            }
            /** 同时读取、失败重试、来源替换和重入均执行真实产品入口。 */
            public static void main(String[] ignored)throws Exception{
                ApplicationLoader.applicationContext.root=Path.of(ignored[0],"private").toFile();
                DesktopConnection first=reset();Result a=new Result(),b=new Result();readAccountUsage("m",a);readAccountUsage("m",b);pump();
                check(first.reads==1,"same-source concurrent calls performed multiple RPC reads");check(a.calls==1&&b.calls==1&&a.usage==first.value&&b.usage==first.value,"merged callbacks not delivered exactly once");
                Result next=new Result();readAccountUsage("m",next);pump();check(first.reads==2&&next.calls==1,"completed read retained new quota cache or suppressed refresh");
                first=reset();a=new Result();b=new Result();readAccountUsage("m",a);networkOne();readAccountUsage("m",b);
                check(approvalQueue.tasks.isEmpty()&&a.calls==0&&b.calls==0,"UI-pending response was treated as completed");ui.all();
                check(first.reads==1&&a.calls==1&&b.calls==1&&a.usage==b.usage,"UI-pending response did not reach both callbacks once");
                first=reset();a=new Result();b=new Result();Result during=b;first.during=()->readAccountUsage("m",during);readAccountUsage("m",a);pump();
                check(first.reads==1&&a.calls==1&&b.calls==1,"inflight RPC did not coalesce");
                first=reset();desktopConnections.clear();a=new Result();b=new Result();readAccountUsage("m",a);readAccountUsage("m",b);pump();
                check(first.reads==1&&opens==1&&a.calls==1&&b.calls==1,"first connection duplicated open/read");
                first=reset();desktopConnections.clear();a=new Result();b=new Result();Result opening=b;afterOpenPut=()->readAccountUsage("m",opening);readAccountUsage("m",a);pump();
                check(first.reads==1&&a.calls==1&&b.calls==1&&a.usage==first.value&&b.usage==first.value,"initial open published connection before return duplicated same-source read");
                first=reset();DesktopConnection other=new DesktopConnection(usage(20));desktopConnections.put("other",other);a=new Result();b=new Result();readAccountUsage("m",a);readAccountUsage("other",b);pump();
                check(first.reads==1&&other.reads==1&&a.usage!=b.usage,"different machine responses merged");
                first=reset();first.fail=true;a=new Result();b=new Result();accountUsageSnapshots.put("m",first.value);readAccountUsage("m",a);readAccountUsage("m",b);pump();
                check(first.reads==1&&a.calls==1&&b.calls==1&&a.error!=null&&b.error!=null&&accountUsageSnapshots.get("m")==first.value,"failed refresh cleared the previous same-source quota");
                first.fail=false;next=new Result();readAccountUsage("m",next);pump();check(first.reads==2&&next.usage==first.value,"failure could not retry");
                first=reset();failOpen=true;desktopConnections.clear();a=new Result();b=new Result();readAccountUsage("m",a);readAccountUsage("m",b);pump();
                check(opens==1&&a.calls==1&&b.calls==1&&a.error!=null&&b.error!=null,"open failure did not coalesce/finish");
                failOpen=false;next=new Result();readAccountUsage("m",next);pump();check(first.reads==1&&next.usage==first.value,"open failure suppressed retry");
                first=reset();failOpen=true;desktopConnections.clear();a=new Result();b=new Result();readAccountUsage("m",a);networkOne();
                failOpen=false;desktopConnections.put("m",first);readAccountUsage("m",b);pump();
                check(first.reads==1&&a.calls==1&&a.usage==null&&b.calls==1&&b.usage==first.value,"UI-pending initial open failure swallowed read from new connection");
                first=reset();a=new Result();b=new Result();readAccountUsage("m",a);DesktopConnection replacement=new DesktopConnection(usage(20));desktopConnections.put("m",replacement);readAccountUsage("m",b);pump();
                check(first.reads==0&&replacement.reads==1&&a.calls==1&&a.usage==null&&a.error!=null&&b.usage==replacement.value,"queued old connection rebound to replacement");
                first=reset();DesktopConnection replacement2=new DesktopConnection(usage(20));Result old=new Result(),fresh=new Result(),joined=new Result();
                first.during=()->{desktopConnections.put("m",replacement2);readAccountUsage("m",fresh);};readAccountUsage("m",old);networkOne();ui.one();readAccountUsage("m",joined);pump();
                check(first.reads==1&&replacement2.reads==1&&old.calls==1&&old.usage==null&&old.error!=null&&fresh.calls==1&&joined.calls==1&&fresh.usage==replacement2.value&&accountUsageSnapshots.get("m")==replacement2.value,"old completion cleared new connection read or published old balance");
                first=reset();a=new Result();b=new Result();readAccountUsage("m",a);networkOne();DesktopConnection uiReplacement=new DesktopConnection(usage(20));
                desktopConnections.put("m",uiReplacement);accountUsageSnapshots.put("m",uiReplacement.value);readAccountUsage("m",b);ui.one();
                check(a.calls==1&&a.usage==null&&accountUsageSnapshots.get("m")==uiReplacement.value,"UI-delayed old response removed newer connection snapshot");
                next=new Result();readAccountUsage("m",next);pump();check(uiReplacement.reads==1&&b.calls==1&&next.calls==1,"UI-delayed old response detached new connection read");
                first=reset();DesktopConnection newAccount=new DesktopConnection(usage(30));Result prior=new Result(),current=new Result(),same=new Result();
                first.during=()->{accountGeneration++;session=new PasswordLogin.Session();desktopConnections.put("m",newAccount);accountUsageSnapshots.clear();readAccountUsage("m",current);};
                readAccountUsage("m",prior);networkOne();ui.all();readAccountUsage("m",same);pump();
                check(prior.calls==0&&newAccount.reads==1&&current.calls==1&&same.calls==1&&current.usage==newAccount.value&&accountUsageSnapshots.get("m")==newAccount.value,"old account callback escaped or removed new in-flight request");
                first=reset();a=new Result();readAccountUsage("m",a);networkOne();loggingOut=true;accountGeneration++;ui.all();check(a.calls==0&&!accountUsageSnapshots.containsKey("m"),"logout accepted late quota");
                first=reset();Result again=new Result();a=new Result();Result original=a;readAccountUsage("m",(value,error)->{original.accept(value,error);readAccountUsage("m",again);});pump();pump();
                check(first.reads==2&&a.calls==1&&again.calls==1,"callback reentry joined already completed read");
                first=reset();a=new Result();readAccountUsage(null,a);check(a.calls==1&&a.error!=null&&approvalQueue.tasks.isEmpty(),"invalid source queued RPC");
                persistence();browseLocalStage();
                System.out.println("RuntimeAccountUsageCoalescing: 真实方法同源并发/首连/分机、失败重试、账号连接迟到与各有效回调一次通过");
            }
        """;
}
