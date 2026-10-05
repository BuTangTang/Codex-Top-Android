package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取真实 ANR 注册、抓栈、heap 与 fatal 方法，仅替代 Android 输出边界。 */
public final class AnrHeapPolicyTest {
    /** 可对前像重放；不启动 Android、不制造真实 OOM，也不创建 heap 文件。 */
    public static void main(String[] args) throws Exception {
        Path sources = Path.of(args.length == 0
                ? "TMessagesProj/src/main/java/org/telegram/messenger" : args[0]);
        var application = StaticJavaParser.parse(sources.resolve("ApplicationLoader.java"));
        var onCreate = application.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("onCreate")).findFirst().orElseThrow();
        var registrations = onCreate.findAll(IfStmt.class).stream()
                .filter(statement -> statement.toString().contains("new ANRDetector("))
                .toArray(IfStmt[]::new);
        check(registrations.length == 1, "自动 ANR 注册边界变化");
        String hook = registrations[0].toString();
        check(!hook.contains("CodexRuntime"), "ANR 注册不能初始化 CodexRuntime");
        var logging = StaticJavaParser.parse(sources.resolve("FileLog.java"));
        var owner = logging.getClassByName("FileLog").orElseThrow();
        StringBuilder methods = new StringBuilder(owner.getFieldByName("dumpedHeap").orElseThrow().toString());
        for (MethodDeclaration method : owner.getMethods()) {
            String name = method.getNameAsString();
            if (name.equals("dumpANR") || name.equals("dumpMemory") || name.equals("fatal")) methods.append(method).append('\n');
        }
        Path temporary = Files.createTempDirectory("codex-anr-policy-");
        try {
            Path fixture = temporary.resolve("AnrHeapPolicyProbe.java");
            Files.writeString(fixture, FIXTURE + methods + "}\nstatic void register() {" + hook + "}\n" + SCENARIOS + "}\n");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                    "-d", temporary.toString(), fixture.toString()) == 0, "真实 ANR 方法 Java 8 编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("AnrHeapPolicyProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("真实 ANR 行为回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 缺失真实源码时立即失败，不能降级为复制条件的测试。 */
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final String FIXTURE = """
        import java.util.*;import java.io.*;import java.text.SimpleDateFormat;
        public final class AnrHeapPolicyProbe {
            static final class BuildConfig {static boolean DEBUG_VERSION;}
            static final class BuildVars {static boolean LOGS_ENABLED=true,DEBUG_VERSION=false,DEBUG_PRIVATE_VERSION=false;}
            static final class Context {
                String packageName;int reads;
                /** 包名只来自合成场景，记录是否在回调前完成判断。 */
                String getPackageName(){reads++;return packageName;}
            }
            static final Context applicationContext=new Context();
            static final class ANRDetector {
                static Runnable callback;static int registrations;
                /** 保留实际注册的回调，由测试显式模拟一次检测事件。 */
                ANRDetector(Runnable callback){ANRDetector.callback=callback;registrations++;}
            }
            static final class Debug {
                static int heaps;
                /** 只记录 Android heap API 调用，不创建大文件。 */
                static void dumpHprofData(String path){heaps++;}
            }
            static final class AndroidUtilities {
                /** 独立合成路径从不写入磁盘。 */
                static File getLogsDir(){return new File("synthetic-anr-logs");}
                /** 本片不改变原异常上报条件。 */
                static void appCenterLog(Throwable error){}
            }
            static final class Queue {
                /** 原日志队列在合成场景同步推进。 */
                void postRunnable(Runnable runnable){runnable.run();}
            }
            static final class FileLog {
                static final FileLog instance=new FileLog();static final List<String> messages=new ArrayList<>();
                final SimpleDateFormat dateFormat=new SimpleDateFormat("yyyyMMdd",Locale.ROOT);
                final Queue logQueue=new Queue();OutputStreamWriter streamWriter;
                static FileLog getInstance(){return instance;}
                static void ensureInitied(){}
                static boolean needSent(Throwable error){return true;}
                static void e(String message){messages.add(message);}
                static void e(Throwable error){throw new AssertionError("unexpected logging failure",error);}
        """;

    private static final String SCENARIOS = """
            static final List<String> failures=new ArrayList<>();static int cases;
            /** 每例重置 Android 边界与原节流字段，不改实际方法。 */
            static void reset(String packageName,boolean debug){
                applicationContext.packageName=packageName;applicationContext.reads=0;
                BuildConfig.DEBUG_VERSION=debug;BuildVars.LOGS_ENABLED=true;
                ANRDetector.callback=null;ANRDetector.registrations=0;
                Debug.heaps=0;FileLog.dumpedHeap=0;FileLog.messages.clear();
            }
            static void expect(boolean value,String message){cases++;if(!value)failures.add(message);}
            /** 必须保留实际线程栈，不能只打印一个 ANR 标签。 */
            static void stack(String label){
                expect(FileLog.messages.size()==1&&FileLog.messages.get(0).startsWith("ANR thread dump\\n")
                        &&FileLog.messages.get(0).contains("Thread: "+Thread.currentThread().getName()+"\\n")
                        &&FileLog.messages.get(0).contains("AnrHeapPolicyProbe"),label+" missing real thread stack");
            }
            /** 从实际包名守卫到真正 dumpMemory 边界重放一次自动 ANR。 */
            static void automatic(String packageName,boolean debug,boolean expectedHeap){
                reset(packageName,debug);register();
                expect(ANRDetector.registrations==(debug?1:0),packageName+" detector registration");
                if(!debug){expect(Debug.heaps==0&&FileLog.messages.isEmpty(),packageName+" release side effects");return;}
                int readsBefore=applicationContext.reads;
                applicationContext.packageName=expectedHeap?"com.butang.codextop.nativepreview.beta":"org.telegram.messenger";
                ANRDetector.callback.run();
                expect(applicationContext.reads==readsBefore,packageName+" callback re-read package");
                expect(Debug.heaps==(expectedHeap?1:0),packageName+" automatic heap="+Debug.heaps);
                stack(packageName);
            }
            /** 原无参入口、显式请求、真实 OOM 分支及30秒节流单独核验。 */
            public static void main(String[] args)throws Exception{
                for(String packageName:new String[]{"com.butang.codextop.nativepreview","com.butang.codextop.nativepreview.beta",
                        "com.butang.codextop.nativepreview.web","com.butang.codextop.nativepreviewXYZ"}){
                    automatic(packageName,true,false);automatic(packageName,false,false);
                }
                for(String packageName:new String[]{"org.telegram.messenger","org.telegram.messenger.beta",
                        "com.butang.codextop.debug","org.example.com.butang.codextop.nativepreview"}){
                    automatic(packageName,true,true);automatic(packageName,false,false);
                }
                reset("com.butang.codextop.nativepreview.beta",true);
                FileLog.dumpANR();expect(Debug.heaps==1,"legacy no-arg ANR must keep heap");stack("legacy no-arg");
                FileLog.dumpANR();expect(Debug.heaps==1,"legacy ANR heap throttle changed");
                try{
                    java.lang.reflect.Method optional=FileLog.class.getDeclaredMethod("dumpANR",boolean.class);
                    reset("org.telegram.messenger",true);optional.invoke(null,false);
                    expect(Debug.heaps==0&&FileLog.dumpedHeap==0,"thread-only ANR touched heap or throttle");stack("explicit thread-only");
                    FileLog.getInstance().dumpMemory(false);expect(Debug.heaps==1,"thread-only ANR suppressed later heap");
                    reset("com.butang.codextop.nativepreview.beta",true);optional.invoke(null,true);
                    expect(Debug.heaps==1,"explicit includeHeap ignored");stack("explicit includeHeap");
                }catch(NoSuchMethodException error){expect(false,"missing optional ANR heap entry");}
                reset("com.butang.codextop.nativepreview.beta",true);
                FileLog.getInstance().dumpMemory(true);FileLog.getInstance().dumpMemory(true);
                expect(Debug.heaps==2,"explicit forced heap throttled");
                reset("com.butang.codextop.nativepreview.beta",true);
                OutOfMemoryError oom=new OutOfMemoryError("synthetic"){public void printStackTrace(){}};
                FileLog.fatal(oom,false);expect(Debug.heaps==1,"OOM heap path changed");
                FileLog.fatal(oom);expect(Debug.heaps==1,"OOM throttle changed");
                reset("com.butang.codextop.nativepreview.beta",true);BuildVars.LOGS_ENABLED=false;
                FileLog.fatal(oom,false);expect(Debug.heaps==0,"original logs-disabled OOM guard changed");
                if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
                System.out.println("AnrHeapPolicy: "+cases+" checks passed using actual registration, ANR, heap and fatal methods");
            }
        """;
}
