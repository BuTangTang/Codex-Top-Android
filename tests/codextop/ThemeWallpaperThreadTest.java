package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实壁纸调度和通知线程断言，图形、Handler与队列仅提供确定性平台边界。 */
public final class ThemeWallpaperThreadTest {
    /** 可指定冻结Theme重放旧RED；不启动Android或读取真实偏好。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/org/telegram");
        Path theme = args.length == 0 ? source.resolve("ui/ActionBar/Theme.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(theme);
        String load = unit.findAll(MethodDeclaration.class).stream().filter(m -> m.getNameAsString().equals("loadWallpaper"))
                .findFirst().orElseThrow().toString();
        var notifications = StaticJavaParser.parse(source.resolve("messenger/NotificationCenter.java"));
        String guard = notifications.findAll(MethodDeclaration.class).stream().filter(m -> m.getNameAsString().equals("postNotificationNameInternal"))
                .findFirst().orElseThrow().getBody().orElseThrow().getStatement(0).toString();
        if (!guard.contains("postNotificationName allowed only from MAIN thread")) throw new AssertionError("真实线程断言边界变化");
        Path temp = Files.createTempDirectory("codex-theme-thread-");
        try {
            String fixture = FIXTURE.replace("ACTUAL_NOTIFICATION_GUARD", guard);
            Path environment = temp.resolve("WallpaperEnvironment.java"), normal = temp.resolve("WallpaperThreadProbe.java"), cold = temp.resolve("ColdWallpaperThreadProbe.java"), codex = temp.resolve("CodexRuntime.java");
            Files.writeString(environment, ENVIRONMENT);
            Files.writeString(normal, fixture + load + SCENARIOS + "\n}");
            Files.writeString(cold, fixture.replace("class WallpaperThreadProbe", "class ColdWallpaperThreadProbe") + load
                    + "\n/** 在后台持有本类初始化锁时执行真实加载入口，UI队列故意不消费。 */static {loadWallpaper(true);}\n}");
            Files.writeString(codex, "package com.butang.codextop;public final class CodexRuntime {public static boolean active=true;/** 仅提供合成包身份。 */public static boolean enabled(){return active;}}\n");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), environment.toString(), normal.toString(), cold.toString(), codex.toString()) != 0)
                throw new AssertionError("真实壁纸方法Java8夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> env = loader.loadClass("WallpaperEnvironment");
                env.getMethod("initializeMain").invoke(null);
                try { loader.loadClass("WallpaperThreadProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("真实壁纸线程回归失败", failure.getCause()); }
                env.getMethod("reset").invoke(null);
                loader.loadClass("com.butang.codextop.CodexRuntime").getField("active").setBoolean(null, true);
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread stage = new Thread(() -> {
                    try { Class.forName("ColdWallpaperThreadProbe", true, loader); }
                    catch (Throwable error) { failure.set(error); }
                }, "synthetic-stageQueue");
                stage.setDaemon(true);stage.start();stage.join(2000);
                if (stage.isAlive()) throw new AssertionError("Theme类初始化等待UI或壁纸队列导致阻塞");
                if (failure.get() != null) throw new AssertionError("后台首次类初始化失败", failure.get());
                Class<?> coldProbe = loader.loadClass("ColdWallpaperThreadProbe");
                int[] before = (int[])coldProbe.getMethod("counts").invoke(null);
                if (before[0] != 0 || before[1] != 0 || before[2] != 0) throw new AssertionError("类初始化内同步创建资源或通知");
                env.getMethod("drainBackground").invoke(null);env.getMethod("drainUi").invoke(null);
                int[] after = (int[])coldProbe.getMethod("counts").invoke(null);
                if (after[0] != 1 || after[1] != 1 || after[2] != 1) throw new AssertionError("初始化返回后原队列未完成单次交付");
                System.out.println("THEME_CLASS_INIT backgroundInitReturnedBeforeUi=true originalQueuesCompleted=true noUiWait=true");
            }
        } finally { try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); } }
    }

    private static final String ENVIRONMENT = """
        import java.util.concurrent.ConcurrentLinkedQueue;import java.util.concurrent.atomic.AtomicReference;
        public final class WallpaperEnvironment {
            static Thread main;static final ConcurrentLinkedQueue<Runnable> background=new ConcurrentLinkedQueue<>(),ui=new ConcurrentLinkedQueue<>();
            /** 测试入口线程作为原applicationHandler拥有者。 */public static void initializeMain(){main=Thread.currentThread();}
            /** 独立场景清空平台队列，不改变生产逻辑。 */public static void reset(){background.clear();ui.clear();}
            /** 有限工作线程保留真实线程身份；不因错误留下常驻测试线程。 */static Throwable worker(Runnable work)throws Exception{AtomicReference<Throwable> failure=new AtomicReference<>();Thread thread=new Thread(()->{try{work.run();}catch(Throwable error){failure.set(error);}},"synthetic-themeQueue");thread.setDaemon(true);thread.start();thread.join(2000);if(thread.isAlive())throw new AssertionError("background call blocked waiting for UI");return failure.get();}
            /** 原themeQueue先执行加载，再由Handler排入主线程。 */public static void drainBackground()throws Exception{Throwable failure=worker(()->{for(Runnable r;(r=background.poll())!=null;)r.run();});if(failure!=null)throw new AssertionError("theme worker failed",failure);}
            /** 只有合成主线程可交付通知。 */public static void drainUi(){if(Thread.currentThread()!=main)throw new AssertionError("invalid UI drain");for(Runnable r;(r=ui.poll())!=null;)r.run();}
        }
        """;

    private static final String FIXTURE = """
        import java.io.File;import java.util.*;
        public final class WallpaperThreadProbe {
            static final int DEFALT_THEME_ACCENT_ID=0;static Drawable wallpaper;static ThemeInfo currentTheme=new ThemeInfo("Day");static Runnable wallpaperLoadTask;static boolean disallowChangeServiceMessageColor;
            static int loads,common,notifications,service,selected;static Thread loadedOn,commonOn,notifiedOn;
            static final class Drawable {}
            static final class ThemeInfo {String name;boolean firstAccentIsDefault=true;int currentAccentId;float patternIntensity;OverrideWallpaperInfo overrideWallpaper;/** 指定合成主题身份。 */ThemeInfo(String value){name=value;} /** 不装载真实强调色或文件。 */ThemeAccent getAccent(boolean value){return null;}}
            static final class OverrideWallpaperInfo {float intensity;}
            static final class ThemeAccent {boolean patternMotion;float patternIntensity;Info info;/** 合成壁纸不读磁盘。 */File getPathToWallpaper(){return null;}}
            static final class Info {List<TLRPC.ThemeSettings> settings=new ArrayList<>();}
            static final class TLRPC {static class Document {}static class ThemeSettings {WallPaper wallpaper;}static class WallPaper {Document document;}}
            static final class BuildVars {static final boolean DEBUG_VERSION=true;}
            static final class ApplicationLoader {static final Handler applicationHandler=new Handler();}
            static final class Handler {/** 返回实际测试主线程对应的Looper。 */Looper getLooper(){return new Looper();}}
            static final class Looper {/** 线程身份与生产NotificationCenter断言相同。 */Thread getThread(){return WallpaperEnvironment.main;}}
            static final class Utilities {static final Queue themeQueue=new Queue();}
            static final class Queue {/** 延后执行原生产Runnable，保留负载与完成事件分离。 */void postRunnable(Runnable runnable){WallpaperEnvironment.background.add(runnable);}}
            static final class AndroidUtilities {/** Handler仅排队，不隐式在调用线程运行。 */static void runOnUIThread(Runnable runnable){WallpaperEnvironment.ui.add(runnable);}}
            static final class NotificationCenter {static final int didSetNewWallpapper=1;static final NotificationCenter INSTANCE=new NotificationCenter();/** 返回合成全局通知宿主。 */static NotificationCenter getGlobalInstance(){return INSTANCE;} /** 执行从实际通知中心提取的线程断言。 */void postNotificationName(int id){ACTUAL_NOTIFICATION_GUARD notifications++;notifiedOn=Thread.currentThread();}}
            /** 图形生产边界记录线程和次数，真实调度决策保留在loadWallpaper。 */static Drawable loadWallpaperInternal(OverrideWallpaperInfo a,File b,int c,boolean d,TLRPC.Document e,boolean f){loads++;loadedOn=Thread.currentThread();return wallpaper=new Drawable();}
            /** 记录原公共资源创建的交付线程。 */static void createCommonChatResources(){common++;commonOn=Thread.currentThread();}
            /** 服务色应用由原生产分支调度。 */static void applyChatServiceMessageColor(Object a,Object b,Drawable c){service++;}
            /** 选择色应用由原生产分支调度。 */static void applyChatMessageSelectedBackgroundColor(Object a,Drawable b){selected++;}
            /** 输出计数供独立class-init场景读取。 */public static int[] counts(){return new int[]{loads,common,notifications};}
            /** 使用合成平台状态重新开始一次加载。 */static void reset(boolean codex,String theme){WallpaperEnvironment.reset();com.butang.codextop.CodexRuntime.active=codex;currentTheme=new ThemeInfo(theme);wallpaper=null;wallpaperLoadTask=null;loads=common=notifications=service=selected=0;loadedOn=commonOn=notifiedOn=null;disallowChangeServiceMessageColor=false;}
            /** 断言不依赖真实Android和设备状态。 */static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
        """;

    private static final String SCENARIOS = """
        /** 日夜主/后台和原async输入交叉验证，普通包/其它主题沿原调用契约。 */
        static void matrix()throws Exception{
            int cases=0;
            for(boolean codex:new boolean[]{true,false})for(String theme:new String[]{"Day","Night","Blue"})for(boolean ui:new boolean[]{false,true})for(boolean incoming:new boolean[]{true,false}){
                reset(codex,theme);String label="codex="+codex+" theme="+theme+" ui="+ui+" incoming="+incoming;
                boolean expectedAsync=codex&&(theme.equals("Day")||theme.equals("Night"))?!ui:incoming;
                Throwable error=null;if(ui){try{loadWallpaper(incoming);}catch(Throwable failure){error=failure;}}else error=WallpaperEnvironment.worker(()->loadWallpaper(incoming));
                if(!ui&&!expectedAsync){check(error instanceof RuntimeException&&error.getMessage().equals("postNotificationName allowed only from MAIN thread"),"ordinary synchronous caller contract changed: "+label);check(loads==1&&common==1&&notifications==0,"ordinary synchronous producer changed");}
                else{
                    check(error==null,"unexpected direct worker notification: "+label+" failure="+(error==null?"none":error.getClass().getSimpleName()));
                    if(expectedAsync){check(loads==0&&common==0&&notifications==0&&WallpaperEnvironment.background.size()==1,"async caller loaded synchronously: "+label);WallpaperEnvironment.drainBackground();check(loads==1&&common==0&&notifications==0&&WallpaperEnvironment.ui.size()==1,"background skipped UI handoff: "+label);WallpaperEnvironment.drainUi();}
                    else check(WallpaperEnvironment.background.isEmpty()&&WallpaperEnvironment.ui.isEmpty(),"UI synchronous path queued work: "+label);
                    check(loads==1&&common==1&&notifications==1&&service==1&&selected==1&&commonOn==WallpaperEnvironment.main&&notifiedOn==WallpaperEnvironment.main,"resources/notification thread changed: "+label);
                    check(expectedAsync?loadedOn!=WallpaperEnvironment.main:loadedOn==WallpaperEnvironment.main,"wallpaper producer thread changed: "+label);
                }
                cases++;
            }
            System.out.println("THEME_THREAD_MATRIX cases="+cases+" dayNightBothInputs=true ordinaryContractUnchanged=true");
        }
        /** 已有壁纸时在主题未设置及任意线程下仍早返，不进入新增线程判断或图形工作。 */
        static void earlyReturn()throws Exception{
            for(boolean ui:new boolean[]{true,false})for(boolean incoming:new boolean[]{true,false}){reset(true,"Day");currentTheme=null;wallpaper=new Drawable();if(ui)loadWallpaper(incoming);else check(WallpaperEnvironment.worker(()->loadWallpaper(incoming))==null,"cached wallpaper failed on worker");check(loads==0&&common==0&&notifications==0&&WallpaperEnvironment.background.isEmpty()&&WallpaperEnvironment.ui.isEmpty(),"cached wallpaper no longer returns early");}
            System.out.println("THEME_WALLPAPER_CACHED cases=4 noWork=true noNotification=true");
        }
        /** 不允许换服务色时保留资源创建和单次通知，颜色分支仍跳过。 */
        static void serviceGuard()throws Exception{reset(true,"Day");disallowChangeServiceMessageColor=true;check(WallpaperEnvironment.worker(()->loadWallpaper(false))==null,"guarded background load failed");WallpaperEnvironment.drainBackground();WallpaperEnvironment.drainUi();check(loads==1&&common==1&&notifications==1&&service==0&&selected==0,"service color guard changed");System.out.println("THEME_SERVICE_GUARD preserved=true");}
        /** 所有数据与线程均为有限合成样例。 */public static void main(String[] args)throws Exception{matrix();earlyReturn();serviceGuard();}
        """;
}
