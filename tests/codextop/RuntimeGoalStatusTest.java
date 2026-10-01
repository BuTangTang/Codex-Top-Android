package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取真实watchStatus及身份守卫，仅替代队列、Socket和手机时钟；不复制目标解析或发布判断。 */
public final class RuntimeGoalStatusTest {
    /** 可指定冻结基线源码用于实际旧STATUS的RED，其余均执行当前生产方法。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String source=Files.readString(args.length==0?Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"):Path.of(args[0]));
        StringBuilder methods=new StringBuilder();
        for(String name:new String[]{"watchStatus","statusTargetCurrent","isAccountCurrent","dialogConnection","releaseObservation"}) {
            String method=method(source,name);
            if(method!=null)methods.append(StaticJavaParser.parseMethodDeclaration(method)).append('\n');
        }
        Path temporary=Files.createTempDirectory("codex-goal-status-");
        try {
            Path probe=temporary.resolve("RuntimeGoalStatusProbe.java");
            Files.writeString(probe,FIXTURE+methods+SCENARIOS+"\n}");
            var sources=new ArrayList<String>();sources.add(probe.toString());
            String[][] platform={
                {"UserConfig","org.telegram.messenger","public static int selectedAccount;"},
                {"BuildVars","org.telegram.messenger","public static final boolean DEBUG_VERSION=false;"},
                {"Log","android.util","public static int i(String a,String b){return 0;}"},
                {"SystemClock","android.os","public static long now=100; public static long elapsedRealtime(){return now;}"}
            };
            for(String[] type:platform){Path p=temporary.resolve(type[0]+".java");Files.writeString(p,"package "+type[1]+"; public class "+type[0]+" {"+type[2]+"}");sources.add(p.toString());}
            var compile=new ArrayList<String>(java.util.List.of("-cp",System.getProperty("java.class.path"),"-d",temporary.toString()));
            compile.addAll(sources);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))!=0)throw new AssertionError("原STATUS夹具编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},RuntimeGoalStatusTest.class.getClassLoader())) {
                try{loader.loadClass("com.butang.codextop.RuntimeGoalStatusProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("实际watchStatus回归失败",e.getCause());}
            }
        } finally {try(var paths=Files.walk(temporary)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    /** 词法扫描仅定位原方法范围，字符串和注释内的括号不参与边界；语义由JavaParser解析原片段。 */
    static String method(String source,String name) {
        var matcher=java.util.regex.Pattern.compile("(?m)^    (?:public|private|static|protected)[^\\n]*\\b"+name+"\\([^\\n]*").matcher(source);
        if(!matcher.find())return null;
        int start=matcher.start(),open=source.indexOf('{',start),depth=0;boolean string=false,character=false,line=false,block=false,escape=false;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i),next=i+1<source.length()?source.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}if(block){if(c=='*'&&next=='/'){block=false;i++;}continue;}
            if(string||character){if(escape)escape=false;else if(c=='\\')escape=true;else if(string&&c=='"')string=false;else if(character&&c=='\'')character=false;continue;}
            if(c=='/'&&next=='/'){line=true;i++;}else if(c=='/'&&next=='*'){block=true;i++;}else if(c=='"')string=true;else if(c=='\'')character=true;
            else if(c=='{')depth++;else if(c=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("原方法边界未闭合："+name);
    }

    // 替代平台执行时间与队列，不替代原身份判定或SessionStatus事实owner。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import com.google.gson.*;
        import java.util.*;
        public class RuntimeGoalStatusProbe {
            static boolean loggingOut; static Object session=new Object();
            static long accountGeneration=1,watchGeneration=1,watchedDialog=7;
            static Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>(),linkedSessions=new HashMap<>();
            static Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static Queue statusQueue=new Queue();static SessionStatus.Store statuses=new SessionStatus.Store();
            static Runnable statusPoll;static DesktopConnection observationOwner;static long observationDialog,observationRenewAt;
            static String observationSession,observationLease;
            static class Queue {ArrayDeque<Runnable> ready=new ArrayDeque<>();ArrayList<Runnable> delayed=new ArrayList<>();void postRunnable(Runnable r){ready.add(r);}void postRunnable(Runnable r,long d){delayed.add(r);}void cancelRunnable(Runnable r){ready.remove(r);delayed.remove(r);}void next(){ready.remove().run();}}
            static class AndroidUtilities {static Queue ui=new Queue();static void runOnUIThread(Runnable r){ui.ready.add(r);}static void runOnUIThread(Runnable r,long d){ui.delayed.add(r);}}
            static class MessagesController {static final int UPDATE_MASK_STATUS=8;}
            static class NotificationCenter {static int notifications;static final int updateInterfaces=1;static NotificationCenter getInstance(int a){return new NotificationCenter();}void postNotificationName(int id,int mask){notifications++;}}
            static class DesktopConnection {
                boolean connected=true,optIn;int calls,observes;Runnable afterStatus,afterOpen,afterObserve;
                JsonObject response=JsonParser.parseString("{\\"ok\\":true,\\"machineOnline\\":true,\\"observation\\":{\\"v\\":1,\\"state\\":\\"unknown\\",\\"reason\\":\\"missing_turn_id\\"},\\"goal\\":{\\"availability\\":\\"available\\",\\"source\\":\\"desktop\\",\\"threadId\\":\\"remote\\",\\"objective\\":\\"真实目标\\",\\"status\\":\\"active\\",\\"tokenBudget\\":0,\\"tokensUsed\\":null,\\"timeUsedSeconds\\":0,\\"updatedAt\\":0}}").getAsJsonObject();
                JsonObject openConversation(String remote){if(afterOpen!=null)afterOpen.run();return JsonParser.parseString("{\\"sessionId\\":\\"linked\\"}").getAsJsonObject();}
                JsonObject observe(String remote,String linked,String lease){observes++;if(afterObserve!=null)afterObserve.run();return JsonParser.parseString("{\\"leaseId\\":\\"lease\\"}").getAsJsonObject();}
                void stopObserving(String s,String l){}boolean isConnected(){return connected;}
                JsonObject status(String remote,String linked){return status(remote,linked,false);}
                JsonObject status(String remote,String linked,boolean include){calls++;optIn=include;if(afterStatus!=null)afterStatus.run();return response;}
            }
            static String dialogMachine(long id){return dialogMachines.get(id);}
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static DesktopConnection setup(){loggingOut=false;session=new Object();accountGeneration=watchGeneration=1;watchedDialog=7;org.telegram.messenger.UserConfig.selectedAccount=0;android.os.SystemClock.now=100;statusQueue=new Queue();AndroidUtilities.ui=new Queue();statuses=new SessionStatus.Store();dialogMachines.clear();remoteIds.clear();linkedSessions.clear();desktopConnections.clear();observationOwner=null;observationLease=null;observationRenewAt=0;statusPoll=null;NotificationCenter.notifications=0;dialogMachines.put(7L,"machine");remoteIds.put(7L,"remote");linkedSessions.put(7L,"linked");DesktopConnection c=new DesktopConnection();desktopConnections.put("machine",c);return c;}
            static void query(){watchStatus(0,7,1);statusQueue.next();statusQueue.next();}
            static void publish(){while(!AndroidUtilities.ui.ready.isEmpty())AndroidUtilities.ui.next();}
        """;

    private static final String SCENARIOS="""
            /** 所有输入合成；只执行原一次查询及UI发布，不推进原后续轮询。 */
            public static void main(String[] args)throws Exception {
                DesktopConnection c=setup();query();check(c.calls==1&&c.optIn,"原watchStatus未在同次RPC请求目标");publish();
                check(statuses.get(7,101).goal.hasValue()&&statuses.get(7,101).goal.validity.equals("current"),"真实STATUS目标未发布");
                check(statusQueue.delayed.size()==1&&AndroidUtilities.ui.delayed.size()==1,"增加了目标poller或重复读取");
                for(int edge=0;edge<10;edge++) {
                    c=setup();query();switch(edge){
                        case 0:accountGeneration++;break;case 1:loggingOut=true;break;case 2:org.telegram.messenger.UserConfig.selectedAccount=1;break;
                        case 3:remoteIds.put(7L,"other-remote");break;case 4:dialogMachines.put(7L,"other-machine");break;
                        case 5:linkedSessions.put(7L,"other-linked");break;case 6:desktopConnections.put("machine",new DesktopConnection());break;
                        case 7:watchGeneration++;break;case 8:watchedDialog=8;break;case 9:c.connected=false;break;
                    }publish();check(!statuses.get(7,101).goal.hasValue()&&NotificationCenter.notifications==0,"迟到STATUS跨身份边界发布："+edge);
                }
                c=setup();linkedSessions.clear();c.afterOpen=()->remoteIds.put(7L,"other-remote");query();publish();
                check(c.calls==0&&observationOwner==null&&linkedSessions.isEmpty(),"迟到open写回旧linked或继续读目标");
                c=setup();c.afterObserve=()->accountGeneration++;query();publish();check(c.calls==0&&observationOwner==null,"迟到observe租约归入下一账号");
                c=setup();observationOwner=c;observationDialog=7;observationSession="old-linked";observationLease="old-lease";observationRenewAt=999999;
                query();publish();check(c.calls==1&&c.observes==1&&observationSession.equals("linked"),"复用了另一个linked的旧观察租约");
                c=setup();query();android.os.SystemClock.now=20000;publish();check(statuses.get(7,20000).goal.validity.equals("stale"),"慢STATUS使用接收时刻为目标续期");
                c=setup();query();statuses.unavailable("machine",150);publish();check(!statuses.get(7,151).goal.hasValue(),"断连前STATUS恢复旧目标");
                System.out.println("RuntimeGoalStatus: 原单次opt-in、账号/源/连接/linked迟到守卫、独立15秒与无新poller通过");
            }
        """;
}
