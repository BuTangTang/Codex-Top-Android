package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;

/** 执行真实缓存读取、摘要恢复与开页方法；平台时钟和队列为合成边界，不读取正式账号。 */
public final class RuntimeHistoryStartupTraceTest {
    /** 可传入冻结前像取得行为RED；复用原历史夹具和模型，不复制待测排程算法。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        Path runtime = args.length == 0 ? source.resolve("CodexRuntime.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var owner = StaticJavaParser.parse(runtime).getClassByName("CodexRuntime").orElseThrow();
        Method extract = RuntimeLatestSegmentTest.class.getDeclaredMethod("extract", Path.class);
        extract.setAccessible(true);
        String methods = (String) extract.invoke(null, runtime);
        for (String name : new String[]{"readHistory", "restoreDialogPreviews", "previewCurrent", "enabled", "beginHistoryTrace", "traceHistoryDuration"}) {
            for (var method : owner.getMethodsByName(name)) methods += method + "\n";
        }
        for (var field : owner.getFields()) if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().endsWith("TraceEvents"))) methods += field + "\n";
        // 旧生产没有调用诊断helper；仅补可编译空边界，让原业务执行后因缺少真实日志而RED。
        if (owner.getMethodsByName("beginHistoryTrace").isEmpty()) methods += "static long beginHistoryTrace(){return -1;}\n";
        if (owner.getMethodsByName("traceHistoryDuration").isEmpty()) methods += "static void traceHistoryDuration(String phase,long started){}\n";
        methods = methods.replace("org.telegram.messenger.BuildVars", "TraceBuild")
                .replace("android.util.Log", "TraceLog").replace("android.os.SystemClock", "TraceClock");
        String fixture = field("FIXTURE");
        // 原相邻夹具关闭诊断；本专项移除它的空边界，编译执行上面提取的真实helper。
        fixture = replaceOnce(fixture, "static long beginHistoryTrace(String phase){return -1;}", "");
        fixture = replaceOnce(fixture, "static void traceHistoryDuration(String phase,long startedAt){}", "");
        fixture = replaceOnce(fixture, "static final Object applicationContext=new Object();", "static TraceContext applicationContext=new TraceContext();");
        fixture = replaceOnce(fixture, "static TranscriptWindow readHistory(long id,String r){try{return store.read(r);}catch(IOException e){throw new AssertionError(e);}}", "");
        fixture = replaceOnce(fixture, "static TranscriptStore transcriptStore(long id){saves++;return store;}",
                "static TranscriptStore transcriptStore(long id){saves++;TraceClock.now+=TraceClock.storeDelay;if(TraceClock.storeFailure!=null)throw TraceClock.storeFailure;return store;}");
        fixture = replaceOnce(fixture, "static OutboxStore outboxStore(long id){return outbox;}",
                "static OutboxStore outboxStore(long id){TraceClock.now+=TraceClock.outboxDelay;return outbox;}");
        fixture = replaceOnce(fixture, "static void publishHistoryPreview(int a,long epoch,long id,String r,String m,DesktopConnection c,TranscriptWindow h){previews++;}",
                "static void publishHistoryPreview(int a,long epoch,long id,String r,String m,DesktopConnection c,TranscriptWindow h){previews++;TraceClock.now+=TraceClock.previewDelay;}");
        Path scratch = Files.createTempDirectory("runtime-history-trace-");
        try {
            Path probe = scratch.resolve("RuntimeLatestSegmentProbe.java");
            Files.writeString(probe, fixture + methods + field("SCENARIOS") + BOUNDARIES + SCENARIOS + "\n}\n");
            var compile = new ArrayList<>(List.of("--release", "8", "-encoding", "UTF-8", "-cp", System.getProperty("java.class.path"), "-d", scratch.toString(), probe.toString()));
            for (String type : new String[]{"DesktopAttachment", "TranscriptText", "TranscriptWindow", "TranscriptTailRecovery", "TranscriptStore", "OutboxStore"}) compile.add(source.resolve(type + ".java").toString());
            String[][] stubs = {
                {"Utilities", "org.telegram.messenger", "public static com.butang.codextop.RuntimeLatestSegmentProbe.Queue globalQueue;"},
                {"MessageObject", "org.telegram.messenger", "public static final int MESSAGE_SEND_STATE_SENT=0,MESSAGE_SEND_STATE_SENDING=1,MESSAGE_SEND_STATE_SEND_ERROR=2;public com.butang.codextop.RuntimeLatestSegmentProbe.TLRPC.TL_message messageOwner;public boolean wasJustSent,attachPathExists,mediaExists;public MessageObject(int a,com.butang.codextop.RuntimeLatestSegmentProbe.TLRPC.TL_message m,boolean x,boolean y){messageOwner=m;}public int getId(){return messageOwner.id;}public long getDialogId(){return messageOwner.dialog_id;}"},
                {"UserConfig", "org.telegram.messenger", "public static int selectedAccount;public int lastSendMessageId=-1;static final UserConfig value=new UserConfig();public static UserConfig getInstance(int a){return value;}public void saveConfig(boolean x){}"},
                {"Toast", "android.widget", "public static final int LENGTH_SHORT=0;public static Toast makeText(Object c,String s,int t){return new Toast();}public void show(){com.butang.codextop.RuntimeLatestSegmentProbe.toasts++;}"}
            };
            for (String[] stub : stubs) {
                Path file = scratch.resolve(stub[0] + ".java");
                Files.writeString(file, "package " + stub[1] + ";public class " + stub[0] + "{" + stub[2] + "}");
                compile.add(file.toString());
            }
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0) throw new AssertionError("真实历史诊断夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{scratch.toUri().toURL()}, RuntimeHistoryStartupTraceTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimeLatestSegmentProbe").getMethod("runTraceScenarios").invoke(null); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实历史诊断行为失败", error.getCause()); }
            }
        } finally {
            try (var files = Files.walk(scratch)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
        }
    }

    /** 读取既有测试的唯一合成上下文，不另造历史业务或修改原断言。 */
    private static String field(String name) throws Exception {
        Field field = RuntimeLatestSegmentTest.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /** 平台替换要求唯一命中，夹具漂移必须显式失败。 */
    private static String replaceOnce(String text, String before, String after) {
        int index = text.indexOf(before);
        if (index < 0 || text.indexOf(before, index + 1) >= 0) throw new AssertionError("合成平台边界不唯一");
        return text.substring(0, index) + after + text.substring(index + before.length());
    }

    private static final String BOUNDARIES = """
        static class TraceBuild {static boolean DEBUG_VERSION=true;}
        static class TraceContext {String getPackageName(){if(TraceLog.failure.equals("context"))throw new AssertionError("private-error-canary");return TraceLog.packageName;}}
        static class TraceClock {
            static long now,storeDelay,outboxDelay,previewDelay;static int reads;static RuntimeException storeFailure;
            static long elapsedRealtime(){reads++;if(TraceLog.failure.equals("clock"))throw new AssertionError("private-error-canary");return now;}
        }
        static class TraceLog {
            static final int DEBUG=3;static boolean loggable;static String failure="",packageName="com.butang.codextop.nativepreview.beta";
            static int attempts;static final ArrayList<String> records=new ArrayList<>();
            static boolean isLoggable(String tag,int level){if(failure.equals("gate"))throw new AssertionError("private-error-canary");check(tag.equals("CodexHistoryStartup")&&level==DEBUG,"wrong trace gate");return loggable;}
            static int d(String tag,String line){attempts++;if(failure.equals("log"))throw new AssertionError("private-error-canary");check(tag.equals("CodexHistoryStartup"),"wrong trace tag");records.add(line);return 0;}
            static int i(String tag,String line){return 0;}static int w(String tag,String line){return 0;}
        }
        static class MessagesController {
            static final MessagesController instance=new MessagesController();final Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();
            static MessagesController getInstance(int a){return instance;}
        }
        static void publishPendingPreview(int a,long epoch,long id,ArrayList<MessageObject> rows,boolean fresh){MessagesController.instance.dialogMessage.put(id,rows);}
        """;

    private static final String SCENARIOS = """
        static int traceChecks;
        /** 每例只重置合成平台与独立真实缓存，保留原Runtime方法和FIFO执行。 */
        static void traceReset()throws Exception{
            reset(cache("A,B"));for(java.lang.reflect.Field f:RuntimeLatestSegmentProbe.class.getDeclaredFields())if(f.getName().endsWith("TraceEvents")){f.setAccessible(true);f.setInt(null,0);}TraceBuild.DEBUG_VERSION=true;TraceLog.loggable=true;
            TraceLog.failure="";TraceLog.packageName="com.butang.codextop.nativepreview.beta";TraceLog.records.clear();TraceLog.attempts=0;
            TraceClock.now=100;TraceClock.reads=0;TraceClock.storeDelay=7;TraceClock.outboxDelay=3;TraceClock.previewDelay=5;TraceClock.storeFailure=null;
            ApplicationLoader.applicationContext=new TraceContext();MessagesController.instance.dialogMessage.clear();
        }
        /** 固定输出语法既校验实际耗时，也排除正文、路径和合成身份泄漏。 */
        static void traceAssert(boolean condition,String reason){traceChecks++;check(condition,reason);}
        static long duration(String phase,int index){
            ArrayList<String> matches=new ArrayList<>();for(String line:TraceLog.records)if(line.startsWith("history_phase="+phase+" "))matches.add(line);
            traceAssert(matches.size()>index,"missing actual phase "+phase);String line=matches.get(index);
            traceAssert(line.matches("history_phase=(preview_queue|cache_read|preview_prepare|open_queue) elapsedRealtimeMs=[0-9]+ durationMs=[0-9]+"),"nonfixed trace grammar");
            return Long.parseLong(line.substring(line.indexOf(" durationMs=")+12));
        }
        static long phases(String phase){return TraceLog.records.stream().filter(line->line.startsWith("history_phase="+phase+" ")).count();}
        /** 真实完整Store读取有时长；原IOException仍返回空、RuntimeException不被诊断吞掉。 */
        static void traceRead()throws Exception{
            traceReset();try{
                TranscriptWindow value=readHistory(42,"synthetic-remote");traceAssert(ids(value).equals("B,A"),"read changed data");
                traceAssert(duration("cache_read",0)==7,"wrong whole-read duration");
                Path file=directory.resolve("history").resolve(TranscriptStore.digest("synthetic-server\\nsynthetic-account\\nsynthetic-machine")).resolve(TranscriptStore.digest("synthetic-remote")+".json");
                Files.write(file,"{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                traceAssert(ids(readHistory(42,"synthetic-remote")).isEmpty(),"IOException fallback changed");
                traceAssert(duration("cache_read",1)==7,"failed read duration missing");
                RuntimeException original=new IllegalStateException("private-error-canary");TraceClock.storeFailure=original;
                try{readHistory(42,"synthetic-remote");throw new AssertionError("runtime failure swallowed");}catch(RuntimeException actual){traceAssert(actual==original,"failure identity changed");}
                traceAssert(duration("cache_read",2)==7,"exceptional read lost finally");
            }finally{cleanup();}
        }
        /** 每道门关闭时真实三个入口仍正常，且没有诊断时钟读取。 */
        static void traceGates()throws Exception{
            for(String gate:new String[]{"release","tag","telegram","null"}){
                traceReset();try{
                    if(gate.equals("release"))TraceBuild.DEBUG_VERSION=false;if(gate.equals("tag"))TraceLog.loggable=false;
                    if(gate.equals("telegram"))TraceLog.packageName="org.telegram.messenger";if(gate.equals("null"))ApplicationLoader.applicationContext=null;
                    histories.clear();readHistory(42,"synthetic-remote");restoreDialogPreviews(0,accountGeneration,"synthetic-machine",Collections.singletonMap("synthetic-remote",42L));pump();
                    loadMessages(0,42,2,0,7,2,1,0);pump();traceAssert(rows(normal()).equals("B,A"),"disabled changed open");
                    traceAssert(TraceLog.records.isEmpty()&&TraceClock.reads==0,"closed gate still clocked/logged: "+gate);
                }finally{cleanup();}
            }
        }
        /** 真实摘要逐项让出；下一项排队从本次续排开始，读盘和准备不会混在同一耗时中。 */
        static void tracePreview()throws Exception{
            traceReset();try{
                histories.clear();remoteIds.put(43L,"private-remote-canary");dialogMachines.put(43L,"synthetic-machine");
                Map<String,Long> ids=new LinkedHashMap<>();ids.put("synthetic-remote",42L);ids.put("private-remote-canary",43L);
                restoreDialogPreviews(0,accountGeneration,"synthetic-machine",ids);TraceClock.now=137;Utilities.globalQueue.next();
                traceAssert(duration("preview_queue",0)==37&&duration("cache_read",0)==7&&duration("preview_prepare",0)==8,"preview stages overlap or moved");
                traceAssert(Utilities.globalQueue.ready.size()==1&&previews==1,"preview stopped yielding");TraceClock.now=200;Utilities.globalQueue.next();
                traceAssert(duration("preview_queue",1)==48&&previews==2,"preview requeue used first timestamp");
                for(String line:TraceLog.records)traceAssert(!line.contains("private")&&!line.contains("synthetic")&&!line.contains("/"),"trace leaked identity");
            }finally{cleanup();}
        }
        /** 首次普通开页只量一次入队；缓存命中不虚报IO，分页及取消保持原通知边界。 */
        static void traceOpening()throws Exception{
            traceReset();try{
                loadMessages(0,42,2,0,7,2,1,0);TraceClock.now=140;Utilities.globalQueue.next();ui();
                traceAssert(duration("open_queue",0)==40&&phases("cache_read")==0&&rows(normal()).equals("B,A"),"cached open trace changed");
            }finally{cleanup();}
            traceReset();try{
                histories.clear();Files.delete(directory.resolve("history").resolve(TranscriptStore.digest("synthetic-server\\nsynthetic-account\\nsynthetic-machine")).resolve(TranscriptStore.digest("synthetic-remote")+".json"));
                current.latest=page("",true,"resume-older","latest-tail");current.older("resume-older",page("Z",false,null,null));
                loadMessages(0,42,3,0,7,2,1,0);TraceClock.now=150;Utilities.globalQueue.next();
                TraceClock.now=500;historyQueue.next();TraceClock.now=900;pump();
                traceAssert(phases("open_queue")==1&&duration("open_queue",0)==50&&rows(normal()).equals("Z"),"network resume reused open timestamp");
            }finally{cleanup();}
            for(int[] request:new int[][]{{2,0,1,1},{0,0,2,0},{2,10,2,0},{2,0,0,0}}){
                traceReset();try{loadMessages(0,42,request[2],request[1],7,request[0],1,request[3]);pump();traceAssert(phases("open_queue")==0,"nondefault open traced as first page");}finally{cleanup();}
            }
            traceReset();try{loadMessages(0,42,2,0,7,2,1,0);historyViews.get(7).active=false;pump();traceAssert(events.isEmpty(),"stale open delivered");}finally{cleanup();}
        }
        /** 四十个真实摘要任务耗尽各自额度后，首次聊天仍保有独立观测机会且总日志有界。 */
        static void traceReservation()throws Exception{
            traceReset();try{
                Map<String,Long> ids=new LinkedHashMap<>();for(long id=100;id<140;id++){String remote="private-candidate-"+id;remoteIds.put(id,remote);dialogMachines.put(id,"synthetic-machine");ids.put(remote,id);}
                restoreDialogPreviews(0,accountGeneration,"synthetic-machine",ids);pump();traceAssert(previews==40,"budget changed original preview processing");
                loadMessages(0,42,2,0,7,2,1,0);TraceClock.now+=23;Utilities.globalQueue.next();ui();
                traceAssert(duration("open_queue",0)==23&&rows(normal()).equals("B,A"),"preview budget starved first open");
                traceAssert(TraceLog.records.size()==97,"per-phase preview budget not bounded");
                for(int i=0;i<40;i++){loadMessages(0,42,2,0,7,2,i+2,0);pump();}
                traceAssert(TraceLog.records.size()==128,"total trace budget exceeded four phases");
                for(String phase:new String[]{"cache_read","preview_queue","preview_prepare","open_queue"})traceAssert(TraceLog.records.stream().filter(line->line.equals("history_phase="+phase+" trace_limited=true")).count()==1,"missing per-phase truncation");
                int clocks=TraceClock.reads;loadMessages(0,42,2,0,7,2,99,0);pump();traceAssert(clocks==TraceClock.reads,"fully exhausted phases still clock");
            }finally{cleanup();}
        }
        /** 日志故障不能改变真实读和开页；耗尽预算后停止额外时钟读取。 */
        static void traceFaultsAndLimit()throws Exception{
            for(String failure:new String[]{"gate","context","clock","log"}){
                traceReset();try{TraceLog.failure=failure;histories.clear();loadMessages(0,42,2,0,7,2,1,0);pump();traceAssert(rows(normal()).equals("B,A"),"diagnostic error changed delivery: "+failure);}finally{cleanup();}
            }
            traceReset();try{
                for(int i=0;i<50;i++)readHistory(42,"synthetic-remote");
                traceAssert(TraceLog.records.size()==32&&TraceLog.records.get(31).equals("history_phase=cache_read trace_limited=true"),"trace budget not bounded");
                int clocks=TraceClock.reads;readHistory(42,"synthetic-remote");traceAssert(TraceClock.reads==clocks,"exhausted budget still clocks");
            }finally{cleanup();}
        }
        /** 先验证新诊断实际行为，再执行已有最新段原始场景，保持原断言和真实模型。 */
        public static void runTraceScenarios()throws Exception{
            traceGates();traceRead();tracePreview();traceOpening();traceReservation();traceFaultsAndLimit();
            TraceLog.loggable=false;main(new String[0]);
            System.out.println("RuntimeHistoryStartupTrace: checks="+traceChecks+" failures=0; original latest scenarios retained");
        }
        """;
}
