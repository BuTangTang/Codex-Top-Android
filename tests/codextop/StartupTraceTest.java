package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实诊断代码并按 Java 8 编译，仅用合成环境替代 Android 边界。 */
public final class StartupTraceTest {
    private static int checks;

    /** 从默认或传入源码验证插桩顺序，再编译执行真实助手与绘制方法；临时产物在结束时清理。 */
    public static void main(String[] args) throws Exception {
        Path appPath = Path.of(args.length > 0 ? args[0]
                : "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java");
        Path launchPath = Path.of(args.length > 1 ? args[1]
                : "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java");
        var app = StaticJavaParser.parse(appPath).getClassByName("ApplicationLoader").orElseThrow();
        var launch = StaticJavaParser.parse(launchPath).getClassByName("LaunchActivity").orElseThrow();
        check(app.getMethodsByName("beginCodexStartupTrace").size() == 1
                && app.getMethodsByName("traceCodexStartup").size() == 1, "missing real startup trace helper");
        verifyHooks(app, launch);
        StringBuilder actual = new StringBuilder();
        app.getFields().stream().filter(field -> field.getVariables().stream().anyMatch(variable ->
                variable.getNameAsString().startsWith("codexStartup")
                        || variable.getNameAsString().equals("CODEX_STARTUP_STAGES")))
                .forEach(field -> actual.append(field).append('\n'));
        actual.append(method(app, "beginCodexStartupTrace")).append('\n');
        actual.append(method(app, "traceCodexStartup")).append('\n');
        check(!actual.toString().contains("CodexRuntime") && !actual.toString().contains("FileLog."),
                "trace must not initialize application logging or Codex runtime");
        var root = launch.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(value -> value.getNameAsString().equals("ActivityContentLayout")).findFirst().orElseThrow();
        actual.append("static final class ActivityContentLayout extends DrawBase {\n");
        root.getFields().forEach(field -> actual.append(field).append('\n'));
        var draw = method(root, "dispatchDraw").clone();
        draw.getParameters().forEach(parameter -> parameter.getAnnotations().clear());
        actual.append(draw).append("}\n");
        Path temporary = Files.createTempDirectory("codex-startup-trace-");
        try {
            Path fixture = temporary.resolve("StartupTraceProbe.java");
            Path log = temporary.resolve("android/util/Log.java");
            Files.createDirectories(log.getParent());
            Files.writeString(log, LOG_STUB);
            Files.writeString(fixture, FIXTURE + actual + SCENARIOS + "}\n");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                    "-d", temporary.toString(), fixture.toString(), log.toString()) == 0,
                    "extracted production helper must compile as Java 8");
            try (var loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("StartupTraceProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("real startup trace boundary failed", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
        System.out.println("PASS startup trace source boundaries: " + checks + " checks");
    }

    /** 取得指定类中的目标方法，供源码顺序检查与真实代码提取共同使用。 */
    private static MethodDeclaration method(ClassOrInterfaceDeclaration owner, String name) {
        return owner.getMethodsByName(name).get(0);
    }

    /** 核对原初始化与阶段标记的先后关系，限制阶段为唯一固定字面量且总数不超过二十。 */
    private static void verifyHooks(ClassOrInterfaceDeclaration app, ClassOrInterfaceDeclaration launch) {
        order(method(app, "onCreate"), "beginCodexStartupTrace(getPackageName())", "applicationLoaderInstance = this",
                "super.onCreate()", "AndroidUtilities.getHelloWorld()", "traceCodexStartup(\"app_native_begin\")",
                "NativeLoader.initNativeLibs", "ConnectionsManager.native_setJava(false)",
                "traceCodexStartup(\"app_native_end\")", "new ForegroundDetector", "ProxyRotationController.init()",
                "traceCodexStartup(\"app_create_end\")");
        order(method(app, "postInitApplication"), "if (applicationInited || applicationContext == null)", "return;",
                "applicationInited = true", "traceCodexStartup(\"post_init_begin\")", "NativeLoader.initNativeLibs",
                "traceCodexStartup(\"post_init_config_begin\")", "SharedConfig.loadConfig()", "SharedPrefsHelper.init",
                "traceCodexStartup(\"post_init_accounts_begin\")", "UserConfig.getInstance(a).loadConfig()",
                "MessagesController.getInstance(a)", "traceCodexStartup(\"post_init_accounts_end\")",
                "app.initPushServices()", "FileLog.d(\"app initied\")", "traceCodexStartup(\"post_init_media_begin\")",
                "MediaController.getInstance()", "ContactsController.getInstance(a)", "DownloadController.getInstance(a)",
                "BillingController.getInstance().startConnection()", "traceCodexStartup(\"post_init_end\")");
        order(method(launch, "onCreate"), "traceCodexStartup(\"launch_create_begin\")", "ApplicationLoader.postInitApplication()",
                "traceCodexStartup(\"launch_theme_begin\")", "Theme.getColor(Theme.key_actionBarDefault)",
                "traceCodexStartup(\"launch_theme_end\")", "flagSecureReason.attach()", "super.onCreate(savedInstanceState)",
                "traceCodexStartup(\"launch_theme_resources_begin\")", "Theme.createCommonChatResources()",
                "Theme.createDialogsResources(this)", "traceCodexStartup(\"launch_theme_resources_end\")",
                "traceCodexStartup(\"launch_content_view_begin\")", "setContentView(frameLayout)",
                "traceCodexStartup(\"launch_content_view_end\")", "checkFrameMetrics()", "traceCodexStartup(\"launch_create_end\")");
        var root = launch.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(value -> value.getNameAsString().equals("ActivityContentLayout")).findFirst().orElseThrow();
        order(method(root, "dispatchDraw"), "super.dispatchDraw(canvas)", "drawRippleAbove(canvas, this)",
                "traceCodexStartup(\"root_first_draw\")");
        Set<String> stages = new HashSet<>();
        for (Node owner : new Node[]{app, launch}) {
            for (var call : owner.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("traceCodexStartup")) continue;
                check(call.getArguments().size() == 1 && call.getArgument(0).isStringLiteralExpr(), "all stages must be fixed literals");
                check(stages.add(call.getArgument(0).asStringLiteralExpr().asString()), "each stage has only one production call site");
            }
        }
        check(stages.size() == 19 && stages.size() <= 20, "bounded startup stages");
    }

    /** 逐项检查指定源码片段的出现顺序，防止插桩移动或改变原初始化边界。 */
    private static void order(Node node, String... tokens) {
        String source = node.toString();
        int previous = -1;
        for (String token : tokens) {
            int next = source.indexOf(token, previous + 1);
            check(next > previous, "startup work/marker order: " + token);
            previous = next;
        }
    }

    /** 累计专项断言数；条件不满足时立即给出对应边界的失败原因。 */
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final String LOG_STUB = """
        package android.util;
        public final class Log {
            public static final int DEBUG=3;
            public static boolean loggable;
            public static int queries;
            public static final java.util.List<String> records=new java.util.ArrayList<>();
            public static boolean isLoggable(String tag,int level) {
                if(!"CodexStartup".equals(tag)||level!=DEBUG)throw new AssertionError("unexpected log switch");
                queries++;return loggable;
            }
            public static int d(String tag,String value) { records.add(tag+"|"+value);return 0; }
        }
        """;

    private static final String FIXTURE = """
        import java.util.*;
        public final class StartupTraceProbe {
            static final class BuildConfig { static boolean DEBUG_VERSION; }
            static final class SystemClock {
                static long now;static int reads;
                static long elapsedRealtime(){reads++;return now;}
            }
            static int rootTraceCalls;
            static final class Canvas {}
            static class DrawBase { protected void dispatchDraw(Canvas canvas){} }
            static void drawRippleAbove(Canvas canvas,ActivityContentLayout view){}
            static final class ApplicationLoader {
                static void traceCodexStartup(String stage){rootTraceCalls++;StartupTraceProbe.traceCodexStartup(stage);}
            }
        """;

    private static final String SCENARIOS = """
            static int checks;
            static void expect(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
            static void reset(boolean debug,boolean loggable) {
                codexStartupTraceChecked=false;codexStartupTraceEnabled=false;
                codexStartupTraceStartedAt=0;codexStartupTraceStages=0;
                BuildConfig.DEBUG_VERSION=debug;android.util.Log.loggable=loggable;
                android.util.Log.queries=0;android.util.Log.records.clear();SystemClock.now=1000;SystemClock.reads=0;
            }
            static void blocked(boolean debug,boolean loggable,String packageName,int queries) {
                reset(debug,loggable);beginCodexStartupTrace(packageName);
                traceCodexStartup("root_first_draw");
                expect(android.util.Log.records.isEmpty(),"disabled gate emitted log");
                expect(SystemClock.reads==0,"disabled gate read clock");
                expect(android.util.Log.queries==queries,"package/debug must short circuit tag check");
            }
            public static void main(String[] args) throws Exception {
                reset(true,true);traceCodexStartup("app_native_begin");
                expect(android.util.Log.records.isEmpty()&&SystemClock.reads==0,"no mark before Application onCreate");
                blocked(true,false,"com.butang.codextop.nativepreview.beta",1);
                blocked(false,true,"com.butang.codextop.nativepreview.beta",0);
                blocked(true,true,"org.telegram.messenger",0);
                blocked(true,true,"com.butang.codextop.debug",0);
                blocked(true,true,"other.com.butang.codextop.nativepreview",0);
                blocked(true,true,null,0);
                // Switching the property after startup does not enable an already-disabled process.
                reset(true,false);beginCodexStartupTrace("com.butang.codextop.nativepreview.beta");
                android.util.Log.loggable=true;beginCodexStartupTrace("com.butang.codextop.nativepreview.beta");
                traceCodexStartup("root_first_draw");
                expect(android.util.Log.records.isEmpty()&&android.util.Log.queries==1,"switch sampled once per process");
                for(String packageName:new String[]{"com.butang.codextop.nativepreview","com.butang.codextop.nativepreview.beta"}) {
                    reset(true,true);beginCodexStartupTrace(packageName);
                    expect(android.util.Log.records.equals(Arrays.asList("CodexStartup|stage=app_create_begin elapsedMs=0")),"initial fixed log");
                    SystemClock.now=1234;traceCodexStartup("app_native_begin");
                    expect(android.util.Log.records.get(1).equals("CodexStartup|stage=app_native_begin elapsedMs=234"),"elapsedRealtime origin");
                    int reads=SystemClock.reads;SystemClock.now=1500;traceCodexStartup("app_native_begin");
                    beginCodexStartupTrace(packageName);traceCodexStartup(null);traceCodexStartup("synthetic-body/account/path");
                    expect(android.util.Log.records.size()==2&&SystemClock.reads==reads,"duplicates and unknown stages are silent");
                    SystemClock.now=2000;traceCodexStartup("root_first_draw");
                    expect(android.util.Log.records.get(2).endsWith("elapsedMs=1000"),"recreation must not reset origin");
                }
                String[] expected={"app_create_begin","app_native_begin","app_native_end","app_create_end",
                    "post_init_begin","post_init_config_begin","post_init_accounts_begin","post_init_accounts_end",
                    "post_init_media_begin","post_init_end","launch_create_begin","launch_theme_begin","launch_theme_end",
                    "launch_theme_resources_begin","launch_theme_resources_end","launch_content_view_begin",
                    "launch_content_view_end","launch_create_end","root_first_draw"};
                expect(Arrays.equals(CODEX_STARTUP_STAGES,expected),"only approved fixed stage names");
                reset(true,true);beginCodexStartupTrace("com.butang.codextop.nativepreview.beta");SystemClock.now=4321;
                // Synthetic concurrent/repeated callbacks exercise the real once-only helper.
                List<Thread> threads=new ArrayList<>();
                for(int worker=0;worker<4;worker++) {
                    Thread thread=new Thread(()->{for(int round=0;round<100;round++)for(String stage:expected)traceCodexStartup(stage);});
                    threads.add(thread);thread.start();
                }
                for(Thread thread:threads)thread.join();
                expect(android.util.Log.records.size()==expected.length,"at most 19 lines per process despite repeated callbacks");
                Set<String> seen=new HashSet<>();
                for(String line:android.util.Log.records) {
                    expect(line.matches("CodexStartup[|]stage=[a-z_]+ elapsedMs=[0-9]+"),"log contains only fixed name and elapsed time");
                    seen.add(line.substring(line.indexOf("stage=")+6,line.indexOf(" elapsedMs=")));
                }
                expect(seen.equals(new HashSet<>(Arrays.asList(expected))),"all and only fixed stages emitted");
                expect(SystemClock.reads==expected.length+1,"duplicates never read clock");
                reset(true,true);beginCodexStartupTrace("com.butang.codextop.nativepreview.beta");rootTraceCalls=0;
                ActivityContentLayout root=new ActivityContentLayout();
                for(int frame=0;frame<100;frame++)root.dispatchDraw(new Canvas());
                expect(rootTraceCalls==1,"draw hot path stops invoking trace after first frame");
                new ActivityContentLayout().dispatchDraw(new Canvas());
                expect(android.util.Log.records.size()==2,"activity recreation must not log a second root draw");
                reset(true,false);rootTraceCalls=0;root=new ActivityContentLayout();
                beginCodexStartupTrace("org.telegram.messenger");
                for(int frame=0;frame<100;frame++)root.dispatchDraw(new Canvas());
                expect(rootTraceCalls==1&&android.util.Log.records.isEmpty(),"ordinary Telegram draws remain silent without repeated trace calls");
                System.out.println("PASS extracted startup trace behavior: "+checks+" checks");
            }
        """;
}
