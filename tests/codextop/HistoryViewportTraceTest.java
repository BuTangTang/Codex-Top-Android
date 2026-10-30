package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实短屏诊断方法，Android只替代度量/日志边界；不读取账号或历史正文。 */
public final class HistoryViewportTraceTest {
    /** 可传入前像核对去除本片后的AST；生产方法按Java8编译并执行。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source = Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java");
        CompilationUnit unit = StaticJavaParser.parse(source);
        var owner = unit.getClassByName("ChatActivity").orElseThrow();
        var helper = owner.getMethodsByName("traceCodexHistoryViewport");
        if (helper.size() != 1) throw new AssertionError("真实诊断owner缺失或不唯一");
        var calls = owner.findAll(MethodCallExpr.class).stream().filter(m -> m.getNameAsString().equals("traceCodexHistoryViewport")).toList();
        if (calls.size() != 3) throw new AssertionError("诊断调用不是限定三处");
        var schedule = owner.getMethodsByName("scheduleCodexHistoryInitialFill").get(0);
        if (!schedule.getBody().orElseThrow().getStatement(0).toString().startsWith("traceCodexHistoryViewport(\"schedule\""))
            throw new AssertionError("缺少schedule入口观察");
        String prepare = owner.getMethodsByName("prepareCodexHistoryPage").get(0).toString();
        int accept = prepare.indexOf("acceptLatestHistory(page)"), trace = prepare.indexOf("traceCodexHistoryViewport(\"replace\""), change = prepare.indexOf("codexHistoryEpoch = page.toEpoch;", trace);
        if (!(accept >= 0 && trace > accept && change > trace)) throw new AssertionError("replacement观察不是接受后清屏前");
        if (!schedule.toString().contains("traceCodexHistoryViewport(\"draw\", ticket, false);\n        if (codexHistoryInitialFillTicket != ticket)"))
            throw new AssertionError("缺少preDraw转UI执行前观察");
        StringBuilder fields = new StringBuilder();
        for (var field : owner.getFields()) if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().startsWith("codexViewport"))) fields.append(field);
        if (args.length > 0) {
            CompilationUnit restored = unit.clone();
            var restoredOwner = restored.getClassByName("ChatActivity").orElseThrow();
            restoredOwner.getMethodsByName("traceCodexHistoryViewport").forEach(Node::remove);
            restoredOwner.getFields().stream().filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().startsWith("codexViewport"))).toList().forEach(Node::remove);
            restoredOwner.findAll(MethodCallExpr.class).stream().filter(m -> m.getNameAsString().equals("traceCodexHistoryViewport")).toList().forEach(m -> m.getParentNode().orElseThrow().remove());
            CompilationUnit before = StaticJavaParser.parse(Path.of(args[0]));
            withoutComments(restored); withoutComments(before);
            if (!restored.equals(before)) throw new AssertionError("去除唯一helper/字段/三调用后仍有业务AST变化");
            System.out.println("PASS production AST unchanged outside trace fields/helper/three calls");
        }
        Path tmp = Files.createTempDirectory("viewport-trace-");
        try {
            Path probe = tmp.resolve("ViewportTraceProbe.java");
            Files.writeString(probe, FIXTURE + fields + helper.get(0) + CASES + "}\n");
            Path log = tmp.resolve("Log.java"), clock = tmp.resolve("SystemClock.java");
            Files.writeString(log, LOG); Files.writeString(clock, CLOCK);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", tmp.toString(), probe.toString(), log.toString(), clock.toString()) != 0)
                throw new AssertionError("真实诊断方法编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()})) {
                try { loader.loadClass("ViewportTraceProbe").getMethod("run").invoke(null); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实诊断行为失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(tmp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** AST比较只忽略注释，不忽略任何可执行节点。 */
    private static void withoutComments(Node root) {
        root.walk(n -> { n.removeComment(); for (var c : java.util.List.copyOf(n.getOrphanComments())) n.removeOrphanComment(c); });
    }

    private static final String FIXTURE = """
        import java.util.*;
        public class ViewportTraceProbe {
            static class BuildVars {static boolean DEBUG_VERSION;}
            static int metrics, owners, asserts; static boolean owned=true, metricThrows;
            long codexHistoryViewToken=7,codexHistoryInitialFillToken=7,codexHistoryInitialFillUsedToken=7;
            Object codexHistoryInitialFillTicket=new Object(),chatLayoutManager=new Object();
            String codexHistoryEpoch="PRIVATE_EPOCH",account="PRIVATE_ACCOUNT",path="PRIVATE_PATH";
            boolean paused,loading,loadingForward,codexHistoryReadingLocked,searching,waitingForGetDifference;
            boolean[] endReached={false};int[] maxMessageId={1};
            List<String> messages=new ArrayList<>(Arrays.asList("PRIVATE_BODY"));
            RecyclerListView chatListView=new RecyclerListView();Adapter chatAdapter=new Adapter();Bar actionBar;
            static class Adapter {boolean isFrozen,isFiltered;}
            static class Bar {boolean isActionModeShowed(){return true;}boolean isSearchFieldVisible(){return true;}}
            static class RecyclerListView {
                boolean hasPendingAdapterUpdates(){touch();return true;}boolean isComputingLayout(){touch();return false;}boolean isLayoutRequested(){touch();return true;}
                int getHeight(){touch();return 500;}int getChildCount(){touch();return 2;}boolean canScrollVertically(int d){touch();return d<0;}
                int computeVerticalScrollRange(){touch();return 600;}int computeVerticalScrollOffset(){touch();return 100;}int computeVerticalScrollExtent(){touch();return 500;}
            }
            static void touch(){metrics++;if(metricThrows)throw new IllegalStateException("PRIVATE_ERROR");}
            boolean isCodexHistoryView(){owners++;return owned;}boolean hasTextSelection(){touch();return false;}
        """;
    private static final String LOG = """
        package android.util;
        public class Log {
            public static final int DEBUG=3;public static boolean enabled,gateThrows,logThrows;public static int gateCalls;
            public static java.util.ArrayList<String> rows=new java.util.ArrayList<>();
            public static boolean isLoggable(String tag,int level){gateCalls++;if(gateThrows)throw new IllegalStateException("PRIVATE_GATE");return enabled;}
            public static int d(String tag,String text){if(logThrows)throw new IllegalStateException("PRIVATE_LOG");rows.add(tag+" "+text);return 0;}
        }
        """;
    private static final String CLOCK = """
        package android.os;
        public class SystemClock {public static int calls;public static long elapsedRealtime(){calls++;return 123;}}
        """;
    private static final String CASES = """
        static void check(boolean v,String message){asserts++;if(!v)throw new AssertionError(message);}
        static void reset(){metrics=owners=0;owned=true;metricThrows=false;BuildVars.DEBUG_VERSION=true;android.util.Log.enabled=true;android.util.Log.gateThrows=android.util.Log.logThrows=false;android.util.Log.gateCalls=0;android.util.Log.rows.clear();android.os.SystemClock.calls=0;codexViewportScheduleTraces=codexViewportDrawTraces=codexViewportReplaceTraces=0;}
        public static void run(){
            reset();ViewportTraceProbe p=new ViewportTraceProbe();BuildVars.DEBUG_VERSION=false;p.traceCodexHistoryViewport("schedule",null,false);
            check(metrics==0&&owners==0&&android.util.Log.gateCalls==0&&android.os.SystemClock.calls==0&&android.util.Log.rows.isEmpty(),"release gate did work");
            reset();android.util.Log.enabled=false;p.traceCodexHistoryViewport("draw",p.codexHistoryInitialFillTicket,false);
            check(metrics==0&&owners==0&&android.os.SystemClock.calls==0&&android.util.Log.rows.isEmpty(),"disabled gate read metrics/clock/log");
            reset();owned=false;p.traceCodexHistoryViewport("replace",null,true);
            check(metrics==0&&android.os.SystemClock.calls==0&&android.util.Log.rows.isEmpty(),"Telegram logged");
            reset();p.traceCodexHistoryViewport("PRIVATE_PHASE",null,false);
            check(metrics==0&&android.os.SystemClock.calls==0&&android.util.Log.rows.isEmpty()&&codexViewportScheduleTraces==0,"unknown phase used quota");
            reset();Object ticket=p.codexHistoryInitialFillTicket;p.traceCodexHistoryViewport("draw",ticket,true);
            String row=android.util.Log.rows.get(0);int mask=Integer.parseInt(row.split(" mask=")[1].split(" ")[0]);
            check((mask&(1<<4))!=0&&(mask&(1<<20))!=0&&(mask&(1<<22))!=0&&(mask&(1<<23))!=0,"numeric state mask lost lifecycle/geometry");
            check(row.contains("height=500 children=2 scrollUp=1 scrollDown=0 range=600 offset=100 extent=500 messages=1 used=1"),"real metric values missing");
            check(p.codexHistoryInitialFillTicket==ticket&&p.codexHistoryViewToken==7&&p.codexHistoryEpoch.equals("PRIVATE_EPOCH")&&!p.loading,"diagnostic changed business state");
            reset();for(String phase:new String[]{"schedule","draw","replace"})for(int i=0;i<40;i++)p.traceCodexHistoryViewport(phase,ticket,false);
            check(android.util.Log.rows.size()==96&&android.os.SystemClock.calls==93,"fixed phase cap or truncation changed");
            int beforeMetrics=metrics;for(String phase:new String[]{"schedule","draw","replace"})p.traceCodexHistoryViewport(phase,ticket,false);check(metrics==beforeMetrics,"exhausted quota read metrics");
            for(String phase:new String[]{"schedule","draw","replace"})check(android.util.Log.rows.stream().filter(s->s.equals("CodexHistoryViewport viewport_phase="+phase+" trace_limited=true")).count()==1,"phase limit not emitted once");
            for(String value:android.util.Log.rows){check(!value.contains("PRIVATE")&&!value.contains("epoch")&&!value.contains("account")&&!value.contains("path"),"private value escaped");check(value.matches("CodexHistoryViewport viewport_phase=(schedule|draw|replace) (trace_limited=true|elapsedRealtimeMs=[0-9]+ mask=[0-9]+ height=-?[0-9]+ children=-?[0-9]+ scrollUp=[01] scrollDown=[01] range=-?[0-9]+ offset=-?[0-9]+ extent=-?[0-9]+ messages=[0-9]+ used=[01])"),"log whitelist violated");}
            reset();p.chatListView=null;p.messages.clear();p.codexHistoryViewToken=0;p.traceCodexHistoryViewport("draw",null,false);check(android.util.Log.rows.get(0).contains("height=-1 children=-1 scrollUp=0 scrollDown=0 range=-1 offset=-1 extent=-1 messages=0 used=0"),"null metric semantics");
            reset();p.chatListView=new RecyclerListView();metricThrows=true;for(int i=0;i<40;i++)p.traceCodexHistoryViewport("schedule",null,false);check(codexViewportScheduleTraces==32&&android.util.Log.rows.size()==1,"metric exception escaped or bypassed quota");
            reset();android.util.Log.gateThrows=true;p.traceCodexHistoryViewport("draw",null,false);check(metrics==0&&android.util.Log.rows.isEmpty(),"gate exception escaped");
            reset();android.util.Log.logThrows=true;p.traceCodexHistoryViewport("replace",null,true);check(android.util.Log.rows.isEmpty()&&codexViewportReplaceTraces==1,"log exception escaped");
            System.out.println("HistoryViewportTrace: "+asserts+" assertions PASS; default no metrics/clock/log, bounded phases, whitelist, exceptions, unchanged business state");
        }
        """;
}
