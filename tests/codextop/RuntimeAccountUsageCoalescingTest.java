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
            if(Set.of("readAccountUsage","isAccountCurrent").contains(method.getNameAsString()))extracted.append(method).append('\n');
        for(ClassOrInterfaceDeclaration type:unit.findAll(ClassOrInterfaceDeclaration.class))
            if(type.getNameAsString().equals("AccountUsageRead"))extracted.append(type).append('\n');
        for(VariableDeclarator field:unit.findAll(VariableDeclarator.class))
            if(field.getNameAsString().equals("accountUsageReads"))extracted.append(field.getParentNode().orElseThrow()).append('\n');
        Path temp=Files.createTempDirectory("codex-usage-probe");
        try{
            Path probe=temp.resolve("UsageProbe.java");Files.writeString(probe,FIXTURE+extracted+SCENARIOS+"}");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-cp",System.getProperty("java.class.path"),"-d",temp.toString(),
                    probe.toString(),source.resolve("AccountUsage.java").toString())!=0)throw new AssertionError("真实额度方法夹具编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},RuntimeAccountUsageCoalescingTest.class.getClassLoader())){
                try{loader.loadClass("com.butang.codextop.UsageProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("真实额度并发合并回归失败",e.getCause());}
            }
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    // 替身仅安排调用时机和返回内容；合并、归属校验和缓存发布均来自真实方法。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import java.util.*;import java.util.function.*;import com.google.gson.*;
        public final class UsageProbe {
            static final class Queue {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();void postRunnable(Runnable r){tasks.add(r);}void one(){tasks.remove().run();}void all(){int n=60;while(!tasks.isEmpty()){if(--n==0)throw new AssertionError("queue loop");one();}}}
            static final Queue approvalQueue=new Queue(),ui=new Queue();
            static final class AndroidUtilities {static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
            static final class PasswordLogin {static final class Session {}}
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
            static DesktopConnection reset()throws Exception{accountGeneration++;session=new PasswordLogin.Session();loggingOut=false;opens=0;failOpen=false;afterOpenPut=null;
                approvalQueue.tasks.clear();ui.tasks.clear();desktopConnections.clear();accountUsageSnapshots.clear();opened=new DesktopConnection(usage(10));desktopConnections.put("m",opened);return opened;}
            static void pump(){approvalQueue.all();ui.all();}
            static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
            /** 同时读取、失败重试、来源替换和重入均执行真实产品入口。 */
            public static void main(String[] ignored)throws Exception{
                DesktopConnection first=reset();Result a=new Result(),b=new Result();readAccountUsage("m",a);readAccountUsage("m",b);pump();
                check(first.reads==1,"same-source concurrent calls performed multiple RPC reads");check(a.calls==1&&b.calls==1&&a.usage==first.value&&b.usage==first.value,"merged callbacks not delivered exactly once");
                Result next=new Result();readAccountUsage("m",next);pump();check(first.reads==2&&next.calls==1,"completed read retained new quota cache or suppressed refresh");
                first=reset();a=new Result();b=new Result();readAccountUsage("m",a);approvalQueue.one();readAccountUsage("m",b);
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
                check(first.reads==1&&a.calls==1&&b.calls==1&&a.error!=null&&b.error!=null&&!accountUsageSnapshots.containsKey("m"),"failed read did not finish all callbacks or clear old balance");
                first.fail=false;next=new Result();readAccountUsage("m",next);pump();check(first.reads==2&&next.usage==first.value,"failure could not retry");
                first=reset();failOpen=true;desktopConnections.clear();a=new Result();b=new Result();readAccountUsage("m",a);readAccountUsage("m",b);pump();
                check(opens==1&&a.calls==1&&b.calls==1&&a.error!=null&&b.error!=null,"open failure did not coalesce/finish");
                failOpen=false;next=new Result();readAccountUsage("m",next);pump();check(first.reads==1&&next.usage==first.value,"open failure suppressed retry");
                first=reset();failOpen=true;desktopConnections.clear();a=new Result();b=new Result();readAccountUsage("m",a);approvalQueue.one();
                failOpen=false;desktopConnections.put("m",first);readAccountUsage("m",b);pump();
                check(first.reads==1&&a.calls==1&&a.usage==null&&b.calls==1&&b.usage==first.value,"UI-pending initial open failure swallowed read from new connection");
                first=reset();a=new Result();b=new Result();readAccountUsage("m",a);DesktopConnection replacement=new DesktopConnection(usage(20));desktopConnections.put("m",replacement);readAccountUsage("m",b);pump();
                check(first.reads==0&&replacement.reads==1&&a.calls==1&&a.usage==null&&a.error!=null&&b.usage==replacement.value,"queued old connection rebound to replacement");
                first=reset();DesktopConnection replacement2=new DesktopConnection(usage(20));Result old=new Result(),fresh=new Result(),joined=new Result();
                first.during=()->{desktopConnections.put("m",replacement2);readAccountUsage("m",fresh);};readAccountUsage("m",old);approvalQueue.one();ui.one();readAccountUsage("m",joined);pump();
                check(first.reads==1&&replacement2.reads==1&&old.calls==1&&old.usage==null&&old.error!=null&&fresh.calls==1&&joined.calls==1&&fresh.usage==replacement2.value&&accountUsageSnapshots.get("m")==replacement2.value,"old completion cleared new connection read or published old balance");
                first=reset();a=new Result();b=new Result();readAccountUsage("m",a);approvalQueue.one();DesktopConnection uiReplacement=new DesktopConnection(usage(20));
                desktopConnections.put("m",uiReplacement);accountUsageSnapshots.put("m",uiReplacement.value);readAccountUsage("m",b);ui.one();
                check(a.calls==1&&a.usage==null&&accountUsageSnapshots.get("m")==uiReplacement.value,"UI-delayed old response removed newer connection snapshot");
                next=new Result();readAccountUsage("m",next);pump();check(uiReplacement.reads==1&&b.calls==1&&next.calls==1,"UI-delayed old response detached new connection read");
                first=reset();DesktopConnection newAccount=new DesktopConnection(usage(30));Result prior=new Result(),current=new Result(),same=new Result();
                first.during=()->{accountGeneration++;session=new PasswordLogin.Session();desktopConnections.put("m",newAccount);accountUsageSnapshots.clear();readAccountUsage("m",current);};
                readAccountUsage("m",prior);approvalQueue.one();ui.all();readAccountUsage("m",same);pump();
                check(prior.calls==0&&newAccount.reads==1&&current.calls==1&&same.calls==1&&current.usage==newAccount.value&&accountUsageSnapshots.get("m")==newAccount.value,"old account callback escaped or removed new in-flight request");
                first=reset();a=new Result();readAccountUsage("m",a);approvalQueue.one();loggingOut=true;accountGeneration++;ui.all();check(a.calls==0&&!accountUsageSnapshots.containsKey("m"),"logout accepted late quota");
                first=reset();Result again=new Result();a=new Result();Result original=a;readAccountUsage("m",(value,error)->{original.accept(value,error);readAccountUsage("m",again);});pump();pump();
                check(first.reads==2&&a.calls==1&&again.calls==1,"callback reentry joined already completed read");
                first=reset();a=new Result();readAccountUsage(null,a);check(a.calls==1&&a.error!=null&&approvalQueue.tasks.isEmpty(),"invalid source queued RPC");
                System.out.println("RuntimeAccountUsageCoalescing: 真实方法同源并发/首连/分机、失败重试、账号连接迟到与各有效回调一次通过");
            }
        """;
}
