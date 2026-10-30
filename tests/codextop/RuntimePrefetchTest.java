package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实预取方法到独立临时夹具，以可控队列验证调度与迟到响应，不复制产品算法。 */
public final class RuntimePrefetchTest {
    /** 使用现成JDK、Gson和JavaParser；所有网络、账号及消息均为合成样例。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(args.length == 0 ? source.resolve("CodexRuntime.java") : Path.of(args[0]));
        var names = Set.of("prefetchDialogs", "prefetchConnectedComputers", "isAccountCurrent", "dialogConnection");
        StringBuilder methods = new StringBuilder();
        int extracted = 0;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (names.contains(method.getNameAsString())) { methods.append(method).append('\n'); extracted++; }
        }
        if (extracted != 5) throw new AssertionError("真实方法入口发生变化，请更新夹具边界");
        // 冷首屏的统一合入同样执行正式实现；只补其真实依赖，不在测试另写一套协调器。
        var owner = unit.getClassByName("CodexRuntime").orElseThrow();
        for (var member : owner.getMembers()) {
            if (member.isClassOrInterfaceDeclaration() && (member.asClassOrInterfaceDeclaration().getNameAsString().startsWith("ColdHistory")
                    || member.asClassOrInterfaceDeclaration().getNameAsString().equals("HistoryView")
                    || member.asClassOrInterfaceDeclaration().getNameAsString().equals("InitialLoadWaiter"))) methods.append(member).append('\n');
            else if (member.isMethodDeclaration() && (member.asMethodDeclaration().getNameAsString().contains("ColdHistory")
                    || member.asMethodDeclaration().getNameAsString().equals("historyViewCurrent")
                    || member.asMethodDeclaration().getNameAsString().equals("completeInitialLoadsFromLatest")
                    || member.asMethodDeclaration().getNameAsString().equals("offerFirstVisibleHistory")
                    || member.asMethodDeclaration().getNameAsString().equals("publishHistoryRoot"))) methods.append(member).append('\n');
            else if (member.isFieldDeclaration() && member.asFieldDeclaration().getVariables().stream().anyMatch(v -> v.getNameAsString().equals("coldHistoryRequests")))
                methods.append(member).append('\n');
        }
        methods.append("/** 每例释放实际请求表，避免夹具跨例残留。 */ static void resetColdHistoryFixture(){");
        if (owner.getFieldByName("coldHistoryRequests").isPresent()) methods.append("coldHistoryRequests.clear();");
        methods.append("}\n");
        Path temporary = Files.createTempDirectory("codex-runtime-prefetch");
        try {
            Path probe = temporary.resolve("RuntimePrefetchProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            Path flags = temporary.resolve("BuildVars.java"), log = temporary.resolve("Log.java"), account = temporary.resolve("UserConfig.java");
            Files.writeString(flags, "package org.telegram.messenger; public class BuildVars { public static final boolean DEBUG_VERSION=false; }");
            Files.writeString(log, "package android.util; public class Log { public static int i(String a,String b){return 0;} }");
            Files.writeString(account, "package org.telegram.messenger; public class UserConfig { public static int selectedAccount; }");
            var compile = new ArrayList<String>();
            compile.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), flags.toString(), log.toString(), account.toString()));
            for (String model : new String[]{"DesktopAttachment", "TranscriptText", "TranscriptWindow"})
                compile.add(source.resolve(model + ".java").toString());
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实预取方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, RuntimePrefetchTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimePrefetchProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实方法调度回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    // 以下只替代不可用于纯JVM的队列、账号和RPC边界；所有预取分支均来自当前CodexRuntime。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;
        import java.util.*;
        import java.io.IOException;
        public final class RuntimePrefetchProbe {
            static final class Queue {
                final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
                void postRunnable(Runnable task){tasks.add(task);}
                void one(){tasks.remove().run();}
                void all(){int limit=100;while(!tasks.isEmpty()){if(--limit==0)throw new AssertionError("queue loop");one();}}
            }
            static final class Utilities { static final Queue globalQueue=new Queue(); }
            static final class ApplicationLoader { static boolean mainInterfacePaused; }
            static final class PasswordLogin { static final class Session {} }
            static final class DesktopConnection {
                final String machineId; boolean connected=true;
                int initialCalls,olderCalls,tailCalls,candidateCalls,lastLimit;
                JsonArray candidates=new JsonArray(); final Map<String,JsonObject> pages=new HashMap<>(),tails=new HashMap<>();
                JsonObject tail; Exception tailFailure; Runnable onRequest;
                DesktopConnection(String machine){machineId=machine;}
                boolean isConnected(){return connected;}
                void requested(){if(onRequest!=null)onRequest.run();}
                JsonObject candidates(int limit){candidateCalls++;lastLimit=limit;requested();JsonObject p=new JsonObject();p.add("candidates",candidates);return p;}
                JsonObject transcript(String remote){initialCalls++;requested();return pages.get("head");}
                JsonObject transcript(String remote,String cursor){olderCalls++;requested();return pages.get(cursor);}
                /** 真实增量游标对应可控RPC结果，允许注入普通请求失败。 */
                JsonObject readAfter(String remote,String cursor)throws Exception{tailCalls++;requested();if(tailFailure!=null)throw tailFailure;return tails.containsKey(cursor)?tails.get(cursor):tail;}
            }
            static PasswordLogin.Session session;
            static long accountGeneration,watchedDialog,nextId; static boolean loggingOut,connectedComputersPrefetching;
            static DesktopConnection desktop;
            static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>();
            static final Map<Integer,HistoryView> historyViews=new HashMap<>();
            static final java.util.concurrent.atomic.AtomicLong historyTokens=new java.util.concurrent.atomic.AtomicLong();
            static final Map<String,Long> ids=new HashMap<>();
            static final Map<Long,TranscriptWindow> histories=new HashMap<>();
            static final Map<Long,Long> prefetchedRevisions=new HashMap<>();
            static final HashSet<Long> prefetching=new HashSet<>();
            static final Queue prefetchQueue=new Queue(),historyQueue=new Queue();
            static int binds,saves,failures,checks; static boolean saveSucceeds;
            static int recentDialogLimit(){return 2;}
            static long bindDialog(String machine,String remote,PasswordLogin.Session owner,long epoch){
                binds++; long id=ids.computeIfAbsent(machine+":"+remote,k->++nextId);dialogMachines.put(id,machine);remoteIds.put(id,remote);return id;
            }
            static TranscriptWindow readHistory(long id,String remote){return new TranscriptWindow();}
            static boolean saveHistory(long id,String remote,TranscriptWindow history){saves++;return saveSucceeds;}
            // D44换页由独立专项执行；此夹具只观察原预取边界，不替代调度或缓存决策。
            static void offerLatestHistory(long id,TranscriptWindow history){}
            /** 本组没有打开UI；意外产生可显示view直接失败，避免空stub掩盖错误。 */static boolean offerLatestHistory(HistoryView view,TranscriptWindow history,boolean explicit){throw new AssertionError("unexpected opened prefetch view");}
            // 摘要发布的真实选择与 owner 门禁由 RuntimeDialogPreviewTest 单独执行。
            static void publishHistoryPreview(int account,long epoch,long id,String remote,String machine,DesktopConnection connection,TranscriptWindow history){}
            static void logTranscriptFailure(String stage,Exception error,JsonObject page){failures++;}
        """;

    // 只描述输入和可观察结果，不在测试中重写队列的生产决策。
    private static final String SCENARIOS = """
            static void reset(){
                session=new PasswordLogin.Session();accountGeneration++;watchedDialog=0;loggingOut=false;nextId=0;
                connectedComputersPrefetching=false;ApplicationLoader.mainInterfacePaused=false;
                desktopConnections.clear();dialogMachines.clear();remoteIds.clear();historyViews.clear();historyQueue.tasks.clear();resetColdHistoryFixture();ids.clear();histories.clear();
                prefetchedRevisions.clear();prefetching.clear();Utilities.globalQueue.tasks.clear();prefetchQueue.tasks.clear();
                binds=saves=failures=0;saveSucceeds=true;desktop=new DesktopConnection("primary");desktopConnections.put("primary",desktop);
            }
            static void pump(){
                int limit=100;
                while(!Utilities.globalQueue.tasks.isEmpty()||!prefetchQueue.tasks.isEmpty()){
                    if(--limit==0)throw new AssertionError("unbounded scheduling");
                    Utilities.globalQueue.all();if(!prefetchQueue.tasks.isEmpty())prefetchQueue.one();
                }
            }
            static JsonArray candidate(String remote,long revision){
                JsonObject row=new JsonObject();row.addProperty("remoteSessionId",remote);row.addProperty("updatedAtMs",revision);
                JsonArray result=new JsonArray();result.add(row);return result;
            }
            static JsonObject page(String textId,boolean more,String cursor,String tail){
                JsonObject p=new JsonObject();JsonArray items=new JsonArray();
                if(textId!=null){JsonObject item=new JsonObject();item.addProperty("id",textId);item.addProperty("createdAtMs",1000);
                    JsonObject raw=new JsonObject(),content=new JsonObject();raw.addProperty("role","user");
                    content.addProperty("type","text");content.addProperty("text","synthetic");raw.add("content",content);item.add("raw",raw);items.add(item);}
                p.add("items",items);p.addProperty("hasMore",more);p.addProperty("historyAvailability","available");
                if(cursor!=null)p.addProperty("nextCursor",cursor);if(tail!=null)p.addProperty("tailCursor",tail);return p;
            }
            static long cache(DesktopConnection connection,JsonObject page)throws Exception{
                long id=bindDialog(connection.machineId,"qa",session,accountGeneration);TranscriptWindow w=new TranscriptWindow();w.prepend(page);histories.put(id,w);return id;
            }
            static DesktopConnection secondary(){DesktopConnection c=new DesktopConnection("secondary");desktopConnections.put(c.machineId,c);return c;}
            /** 计数真实方法产生的可观察断言，失败时保留具体输入边界。 */
            static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
            /** 预取沿真实tail推进，空页预算、保存失败和明确断档分别验证。 */
            public static void main(String[] args)throws Exception{
                reset();
                long warmId=cache(desktop,page("cached",true,"older","tail1"));TranscriptWindow warm=histories.get(warmId);
                prefetchedRevisions.put(warmId,1L);desktop.pages.put("head",page(null,true,"unused-older","unused-tail"));
                desktop.tail=page("new",false,"tail2",null);prefetchDialogs(candidate("qa",2),desktop);pump();
                check(desktop.initialCalls==0&&desktop.tailCalls==1&&histories.get(warmId)==warm
                    &&warm.findSource("cached",null)!=null&&warm.findSource("new",null)!=null
                    &&warm.snapshot().getAsJsonArray("archive").size()==0&&"tail2".equals(warm.tailCursor),"valid tail must append without rereading empty latest or archiving cache");
                check(Long.valueOf(2).equals(prefetchedRevisions.get(warmId)),"completed saved tail did not advance revision");

                reset();
                long id=cache(desktop,page("old",true,"older",null));prefetchedRevisions.put(id,1L);
                JsonObject head=page("old",false,null,"tail");head.getAsJsonArray("items").add(page("new",false,null,null).getAsJsonArray("items").get(0));
                desktop.pages.put("head",head);prefetchDialogs(candidate("qa",1),desktop);pump();
                check(desktop.initialCalls==1&&histories.get(id).before(0,10).size()==2&&"tail".equals(histories.get(id).tailCursor),"loaded missing tail never recovered");

                reset();desktop.pages.put("head",page(null,true,"older1","tail"));
                for(int n=1;n<=3;n++)desktop.pages.put("older"+n,page(null,true,"older"+(n+1),null));
                desktop.pages.put("older4",page("old-body",true,"older5",null));
                prefetchDialogs(candidate("qa",1),desktop);pump();id=ids.get("primary:qa");
                check(desktop.initialCalls==1&&desktop.olderCalls==3&&desktop.tailCalls==0&&"older4".equals(histories.get(id).cursor)
                    &&!prefetchedRevisions.containsKey(id),"empty initial scan exceeds four pages or pretends synced");
                // 空最新页的旧向扫描跨列表周期继续；找到正文后回到既有tail，不再次读空latest。
                TranscriptWindow retainedEmpty=histories.get(id);
                desktop.tail=page("new-body",false,"tail2",null);
                prefetchDialogs(candidate("qa",2),desktop);pump();
                TranscriptWindow latest=histories.get(id);
                check(desktop.initialCalls==1&&desktop.olderCalls==4&&desktop.tailCalls==1
                    &&latest==retainedEmpty&&latest.before(0,10).size()==2&&latest.findSource("new-body",null)!=null
                    &&latest.findSource("old-body",null)!=null&&latest.snapshot().getAsJsonArray("archive").size()==0
                    &&latest.hasMore&&"older5".equals(latest.cursor)&&"tail2".equals(latest.tailCursor)
                    &&Long.valueOf(2).equals(prefetchedRevisions.get(id)),"empty cache must resume older then tail in the same segment across the four-page limit");
                int calls=desktop.initialCalls+desktop.olderCalls+desktop.tailCalls;
                prefetchDialogs(candidate("qa",2),desktop);pump();
                check(calls==desktop.initialCalls+desktop.olderCalls+desktop.tailCalls,"unchanged candidate adds network requests");

                // 连续空投影仍推进真实tail；本轮四页耗尽只暂停，不新建段或伪造追平。
                reset();id=cache(desktop,page("cached",true,"older","tail0"));TranscriptWindow budget=histories.get(id);
                for(int n=0;n<5;n++){JsonObject limited=page(null,true,"tail"+(n+1),null);
                    limited.addProperty("truncated",true);limited.addProperty("truncationReason","page_limit");desktop.tails.put("tail"+n,limited);}
                desktop.tails.put("tail5",page("caught-up",false,"tail6",null));
                prefetchDialogs(candidate("qa",3),desktop);pump();
                check(desktop.tailCalls==4&&desktop.initialCalls==0&&desktop.olderCalls==0&&"tail4".equals(budget.tailCursor)
                    &&!prefetchedRevisions.containsKey(id)&&budget.snapshot().getAsJsonArray("archive").size()==0,"tail scan exceeded four pages or archived empty projection");
                prefetchDialogs(candidate("qa",3),desktop);pump();
                check(desktop.tailCalls==6&&histories.get(id)==budget&&budget.findSource("cached",null)!=null
                    &&budget.findSource("caught-up",null)!=null&&"tail6".equals(budget.tailCursor)
                    &&Long.valueOf(3).equals(prefetchedRevisions.get(id)),"next list cycle did not finish retained tail cursor");
                prefetchDialogs(candidate("qa",3),desktop);pump();check(desktop.tailCalls==6,"completed tail revision refetched");

                // 明确断档只失效tail，正文/旧cursor不动；下一正常周期才按现有bootstrap恢复。
                reset();id=cache(desktop,page("cached",true,"older","bad-tail"));TranscriptWindow broken=histories.get(id);
                JsonObject discontinuity=page(null,true,"irrelevant",null);discontinuity.addProperty("truncated",true);
                discontinuity.addProperty("truncationReason","source_discontinuity");desktop.tail=discontinuity;
                prefetchDialogs(candidate("qa",4),desktop);pump();
                check(desktop.tailCalls==1&&desktop.initialCalls==0&&failures==1&&saves==1&&broken.tailCursor==null
                    &&histories.get(id)==broken&&broken.findSource("cached",null)!=null&&"older".equals(broken.cursor)
                    &&broken.snapshot().getAsJsonArray("archive").size()==0&&!prefetchedRevisions.containsKey(id),"invalid tail must not trap all future prefetch in the same failure");
                JsonObject recovered=page("cached",false,null,"good-tail");recovered.getAsJsonArray("items").add(page("recovered",false,null,null).getAsJsonArray("items").get(0));
                desktop.pages.put("head",recovered);prefetchDialogs(candidate("qa",4),desktop);pump();
                check(desktop.initialCalls==1&&desktop.tailCalls==1&&histories.get(id)==broken&&"good-tail".equals(broken.tailCursor)
                    &&broken.findSource("recovered",null)!=null&&Long.valueOf(4).equals(prefetchedRevisions.get(id)),"explicit discontinuity did not return to existing bootstrap path");

                // 普通传输失败保留同一tail；请求没有断档证据时不能回退latest。
                reset();id=cache(desktop,page("cached",true,"older","retry-tail"));desktop.tailFailure=new IOException("synthetic transport failure");
                prefetchDialogs(candidate("qa",5),desktop);pump();
                check(failures==1&&saves==0&&desktop.tailCalls==1&&"retry-tail".equals(histories.get(id).tailCursor)
                    &&!prefetchedRevisions.containsKey(id),"ordinary failure cleared a valid tail or marked synced");
                desktop.tailFailure=null;desktop.tail=page("retried",false,"next-tail",null);prefetchDialogs(candidate("qa",5),desktop);pump();
                check(desktop.tailCalls==2&&desktop.initialCalls==0&&histories.get(id).findSource("retried",null)!=null,"ordinary tail failure did not retry incrementally");

                // 明确断档也必须服从原迟回包门禁，不得清除替换账号/连接/窗口或已前进tail。
                for(int mutation=0;mutation<5;mutation++){
                    reset();long staleId=cache(desktop,page("cached",true,"older","tail"));TranscriptWindow stale=histories.get(staleId);
                    desktop.tail=discontinuity;final int change=mutation;
                    desktop.onRequest=()->{if(change==0){accountGeneration++;session=new PasswordLogin.Session();}
                        else if(change==1)desktopConnections.put("primary",new DesktopConnection("primary"));
                        else if(change==2)histories.put(staleId,new TranscriptWindow());
                        else if(change==3)stale.tailCursor="advanced-tail";else watchedDialog=staleId;};
                    prefetchDialogs(candidate("qa",7),desktop);pump();
                    check(saves==0&&failures==0&&desktop.tailCalls==1
                        &&(change==3?"advanced-tail":"tail").equals(stale.tailCursor),"stale discontinuity bypassed owner guard "+change);
                }

                reset();saveSucceeds=false;desktop.pages.put("head",page("body",false,null,"tail"));desktop.tail=page(null,false,"tail",null);
                prefetchDialogs(candidate("qa",1),desktop);pump();id=ids.get("primary:qa");
                check(!prefetchedRevisions.containsKey(id),"failed persistence marked revision complete");
                saveSucceeds=true;prefetchDialogs(candidate("qa",1),desktop);pump();
                // 保存失败不记revision；同版本空闲tail也必须重试保存已有模型。
                check(saves==2&&desktop.initialCalls==1&&desktop.olderCalls==0&&desktop.tailCalls==1
                    &&histories.get(id).before(0,10).size()==1&&histories.get(id).findSource("body",null)!=null
                    &&Long.valueOf(1).equals(prefetchedRevisions.get(id)),"failed persistence never retried the existing tail owner");

                reset();id=cache(desktop,page("cached",true,"older1","tail1"));final TranscriptWindow interleaved=histories.get(id);
                // 增量与原末条有真实重叠；请求中推进旧cursor也不能拒绝同段tail。
                JsonObject overlappingHead=page("cached",false,"tail2",null);
                overlappingHead.getAsJsonArray("items").add(page("new-tail",false,null,null).getAsJsonArray("items").get(0));
                desktop.tail=overlappingHead;desktop.onRequest=()->{
                    try{interleaved.prepend(page("older-body",true,"older2",null));}catch(Exception e){throw new AssertionError(e);}};
                prefetchDialogs(candidate("qa",1),desktop);pump();
                check(desktop.initialCalls==0&&desktop.olderCalls==0&&desktop.tailCalls==1&&saves==1
                    &&histories.get(id)==interleaved&&interleaved.before(0,10).size()==3
                    &&interleaved.findSource("older-body",null)!=null&&interleaved.findSource("cached",null)!=null
                    &&interleaved.findSource("new-tail",null)!=null&&"tail2".equals(interleaved.tailCursor)
                    &&"older2".equals(interleaved.cursor),"tail page rejected by unrelated old cursor progress");

                reset();prefetchDialogs(candidate("qa",1),desktop);accountGeneration++;session=new PasswordLogin.Session();pump();
                check(binds==0&&desktop.initialCalls==0,"stale queued account bound a conversation");
                reset();DesktopConnection prior=desktop;prefetchDialogs(candidate("qa",1),prior);
                desktopConnections.put("primary",new DesktopConnection("primary"));pump();
                check(binds==0&&prior.initialCalls==0,"replaced connection bound old candidates");
                reset();desktop.pages.put("head",page("late",false,null,"tail"));desktop.onRequest=()->desktopConnections.remove("primary");
                prefetchDialogs(candidate("qa",1),desktop);pump();
                check(saves==0&&!histories.values().iterator().next().loaded,"removed in-flight connection wrote late body");

                reset();desktop.pages.put("head",page("late-account",false,null,"tail"));
                desktop.onRequest=()->{accountGeneration++;session=new PasswordLogin.Session();histories.put(1L,new TranscriptWindow());prefetching.add(1L);};
                prefetchDialogs(candidate("qa",1),desktop);pump();
                check(saves==0&&!histories.get(1L).loaded&&prefetching.contains(1L),"old body callback cleared or wrote new account prefetch state");

                reset();DesktopConnection other=secondary();other.candidates=candidate("qa",1);
                other.candidates.add(candidate("two",1).get(0));other.candidates.add(candidate("beyond-limit",1).get(0));
                other.pages.put("head",page("body",false,null,"tail"));
                prefetchConnectedComputers(accountGeneration);prefetchConnectedComputers(accountGeneration);Utilities.globalQueue.all();
                check(prefetchQueue.tasks.size()==1&&connectedComputersPrefetching,"connected candidate gate admits duplicate rounds");pump();
                check(other.candidateCalls==1&&other.lastLimit==2&&other.initialCalls==2&&desktop.candidateCalls==0
                    &&!connectedComputersPrefetching&&ids.size()==2,"secondary scope exceeds recent limit or changes primary requests");
                prefetchConnectedComputers(accountGeneration);pump();
                check(other.candidateCalls==2&&other.initialCalls==2,"unchanged secondary metadata repeats body fetch");

                reset();other=secondary();other.connected=false;prefetchConnectedComputers(accountGeneration);pump();
                check(other.candidateCalls==0&&!connectedComputersPrefetching,"disconnected computer queried or leaves gate stuck");
                reset();other=secondary();prefetchConnectedComputers(accountGeneration);Utilities.globalQueue.one();
                desktopConnections.remove("secondary");pump();
                check(other.candidateCalls==0&&!connectedComputersPrefetching,"removed computer still queried");
                reset();other=secondary();other.candidates=candidate("qa",1);
                other.onRequest=()->desktopConnections.put("secondary",new DesktopConnection("secondary"));
                prefetchConnectedComputers(accountGeneration);pump();
                check(binds==0&&saves==0&&!connectedComputersPrefetching,"late candidates from replaced connection bound to replacement");
                reset();other=secondary();other.onRequest=()->{throw new IllegalStateException("synthetic RPC failure");};
                prefetchConnectedComputers(accountGeneration);pump();
                check(failures==1&&!connectedComputersPrefetching&&binds==0,"secondary failure leaves single-flight gate stuck");
                reset();other=secondary();other.candidates=candidate("qa",1);
                other.onRequest=()->{accountGeneration++;session=new PasswordLogin.Session();connectedComputersPrefetching=true;desktopConnections.clear();};
                prefetchConnectedComputers(accountGeneration);pump();
                check(binds==0&&saves==0&&connectedComputersPrefetching,"old worker cleared new account gate or bound stale source");
                JsonObject missingRemote=new JsonObject();missingRemote.addProperty("updatedAtMs",1);
                JsonObject missingRevision=new JsonObject();missingRevision.addProperty("remoteSessionId","invalid");
                JsonObject invalidRevision=new JsonObject();invalidRevision.addProperty("remoteSessionId","invalid");invalidRevision.addProperty("updatedAtMs","not-a-number");
                JsonElement[] malformed={JsonNull.INSTANCE,new JsonPrimitive("invalid-row"),missingRemote,missingRevision,invalidRevision};
                int escaped=0,continued=0;
                for(JsonElement bad:malformed){
                    reset();other=secondary();other.candidates.add(bad);other.candidates.add(candidate("valid-after-bad",1).get(0));
                    other.pages.put("head",page("body",false,null,"tail"));
                    prefetchConnectedComputers(accountGeneration);
                    try{pump();}catch(RuntimeException error){escaped++;}
                    if(other.initialCalls==1&&ids.size()==1&&failures==1&&!connectedComputersPrefetching)continued++;
                }
                check(escaped==0&&continued==malformed.length,"malformed candidate escaped globalQueue="+escaped+"; valid successors loaded="+continued);
                reset();other=secondary();
                for(JsonElement bad:malformed)other.candidates.add(bad);
                other.candidates.add(candidate("valid-first",1).get(0));other.candidates.add(JsonNull.INSTANCE);
                other.candidates.add(candidate("valid-second",1).get(0));other.candidates.add(candidate("beyond-limit",1).get(0));
                other.pages.put("head",page("body",false,null,"tail"));
                prefetchConnectedComputers(accountGeneration);pump();
                check(other.initialCalls==2&&ids.size()==2&&ids.containsKey("secondary:valid-first")&&ids.containsKey("secondary:valid-second")
                    &&failures==malformed.length+1&&!connectedComputersPrefetching,"bad rows consume valid candidate budget or bypass recent limit");
                System.out.println("RuntimePrefetch: checks="+checks+" real bodies PASS — warm tail, empty four-page continuation, explicit discontinuity recovery, unchanged revisions, save failure, owner guards, secondary single-flight, malformed candidate isolation");
            }
        """;
}
