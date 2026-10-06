package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实列表预热判断、主线程排期和聊天初始化调用，仅替换 Android 与绘图资源边界。 */
public final class DialogsChatResourcesTest {
    private static final Path SOURCE = Path.of("TMessagesProj/src/main/java");

    /** 可传入未修前像；同一普通首页断言在旧代码失败，全部特殊入口继续验证原排期行为。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path dialogsPath = args.length == 0 ? SOURCE.resolve("org/telegram/ui/DialogsActivity.java") : Path.of(args[0]);
        String original = Files.readString(dialogsPath);
        var dialogs = StaticJavaParser.parse(original).getClassByName("DialogsActivity").orElseThrow();
        var chat = owner("org/telegram/ui/ChatActivity.java", "ChatActivity");
        var launch = owner("org/telegram/ui/LaunchActivity.java", "LaunchActivity");
        var utilities = owner("org/telegram/messenger/AndroidUtilities.java", "AndroidUtilities");
        StringBuilder code = new StringBuilder(FIXTURE);
        code.append(dialogs.getFields().stream().filter(field -> field.getVariables().stream()
                .anyMatch(variable -> variable.getNameAsString().equals("DIALOGS_TYPE_DEFAULT"))).findFirst().orElseThrow());
        code.append("void createList(Context context) {").append(containingCall(method(dialogs, "createView"), "createChatResources")).append("}\n");
        code.append("void createChat(Context context) {").append(directCall(method(chat, "createView"), "createChatResources")).append("}\n");
        code.append("void createLaunchResources(Context context) {");
        Statement common = directCall(method(launch, "onCreate"), "createCommonChatResources");
        Statement dialog = directCall(method(launch, "onCreate"), "createDialogsResources");
        if (common.getBegin().orElseThrow().line >= dialog.getBegin().orElseThrow().line)
            throw new AssertionError("原首页公共资源顺序改变");
        code.append(common).append(dialog.toString().replace("(this)", "(context)")).append("}\n");
        code.append("static final class AndroidUtilities {\n");
        var dispatch = utilities.getMethodsByName("runOnUIThread");
        if (dispatch.size() != 2) throw new AssertionError("真实主线程排期边界改变");
        dispatch.forEach(code::append);
        code.append("}\n").append(SCENARIOS).append("}\nclass CodexRuntime {static boolean enabled; static boolean enabled(){return enabled;}}\n");
        Path temporary = Files.createTempDirectory("codex-dialog-chat-resources-");
        try {
            Path fixture = temporary.resolve("DialogsChatResourcesProbe.java");
            Files.writeString(fixture, code);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temporary.toString(), fixture.toString()) != 0)
                throw new AssertionError("真实资源调用与排期代码未通过 Java 8 编译");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.DialogsChatResourcesProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("真实资源调用行为失败", failure.getCause()); }
            }
            if (!Files.readString(dialogsPath).equals(original)) throw new AssertionError("测试期间源码发生变化");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 只读取当前生产类，不复制其判断条件。 */
    private static ClassOrInterfaceDeclaration owner(String path, String name) throws Exception {
        return StaticJavaParser.parse(SOURCE.resolve(path)).getClassByName(name).orElseThrow();
    }

    /** 顶层类的真实方法是提取范围，不采纳匿名子类的同名方法。 */
    private static MethodDeclaration method(ClassOrInterfaceDeclaration owner, String name) {
        return owner.getMethodsByName(name).get(0);
    }

    /** 连同包裹调用的真实条件一并提取；缺失或重复初始化都使测试失败。 */
    private static Statement containingCall(MethodDeclaration method, String name) {
        var found = method.getBody().orElseThrow().getStatements().stream().filter(statement -> statement.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getNameAsString().equals(name) && call.getScope().map(Object::toString).orElse("").equals("Theme"))).toList();
        if (found.size() != 1) throw new AssertionError("真实资源调用必须唯一: " + name);
        return found.get(0);
    }

    /** 聊天和首页公共资源必须沿原同步直接调用，不能被包进延迟任务或新条件。 */
    private static Statement directCall(MethodDeclaration method, String name) {
        Statement statement = containingCall(method, name);
        if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isMethodCallExpr()
                || !statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals(name))
            throw new AssertionError("原同步初始化不可跳过或异步搬移: " + name);
        return statement;
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public final class DialogsChatResourcesProbe {
            static int checks;
            boolean hasMainTabs=true, onlySelect, inPreviewMode;
            int initialDialogsType, folderId; long communityId;
            String searchString;
            static final class Context {}
            static final class Handler {
                final List<Runnable> queued=new ArrayList<>();
                void post(Runnable task){queued.add(task);}
                void postDelayed(Runnable task,long delay){throw new AssertionError("预热不能新增延迟");}
                void drain(){for(Runnable task:new ArrayList<>(queued)){queued.remove(task);task.run();}}
            }
            static final class ApplicationLoader {static Handler applicationHandler;}
            static final class Theme {
                static final List<String> calls=new ArrayList<>(); static Context context;
                static void createCommonChatResources(){calls.add("common");}
                static void createDialogsResources(Context value){context=value;calls.add("dialogs");}
                static void createChatResources(Context value,boolean fontsOnly){
                    if(fontsOnly)throw new AssertionError("完整聊天初始化被替换");
                    context=value;calls.add("chat");
                }
            }
        """;

    private static final String SCENARIOS = """
            /** 每个场景使用全新合成首页、队列和资源计数，不接触账号或磁盘数据。 */
            static DialogsChatResourcesProbe fresh(){
                CodexRuntime.enabled=true;ApplicationLoader.applicationHandler=new Handler();
                Theme.calls.clear();Theme.context=null;return new DialogsChatResourcesProbe();
            }
            /** 断言实际调用结果，不复制生产判断表达式作为期望。 */
            static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
            /** 特殊入口仍排入原 Handler；执行队列后才初始化同一 Context 的完整聊天资源。 */
            static void prewarms(DialogsChatResourcesProbe probe,String name){
                Context context=new Context();probe.createList(context);
                check(ApplicationLoader.applicationHandler.queued.size()==1,name+" retains original queued prewarm");
                check(Theme.calls.isEmpty(),name+" must not initialize resources synchronously");
                ApplicationLoader.applicationHandler.drain();
                check(Theme.calls.equals(Arrays.asList("chat")),name+" performs full resources once");
                check(Theme.context==context,name+" preserves context");
                probe.createChat(context);
                check(Theme.calls.equals(Arrays.asList("chat","chat")),name+" preserves original chat entry");
            }
            /** 覆盖普通首页、每个独立反例和两种首次开聊天顺序。 */
            public static void main(String[] args){
                DialogsChatResourcesProbe probe=fresh();Context context=new Context();
                probe.createLaunchResources(context);
                check(Theme.calls.equals(Arrays.asList("common","dialogs")),"Launch retains required list resources");
                probe.createList(context);
                check(ApplicationLoader.applicationHandler.queued.isEmpty(),"normal Codex home must not enqueue full chat prewarm");
                check(Theme.calls.equals(Arrays.asList("common","dialogs")),"list retains initialized common resources");
                probe.createList(context);
                check(ApplicationLoader.applicationHandler.queued.isEmpty(),"recreated normal home must not enqueue prewarm");
                probe.createChat(context);
                check(Theme.calls.equals(Arrays.asList("common","dialogs","chat")),"first chat initializes full resources synchronously");
                check(Theme.context==context,"first chat preserves original context");
                probe=fresh();CodexRuntime.enabled=false;prewarms(probe,"Telegram");
                probe=fresh();probe.hasMainTabs=false;prewarms(probe,"standalone dialogs");
                probe=fresh();probe.initialDialogsType=1;prewarms(probe,"nondefault dialogs");
                probe=fresh();probe.folderId=1;prewarms(probe,"archive");
                probe=fresh();probe.communityId=1;prewarms(probe,"community");
                probe=fresh();probe.onlySelect=true;prewarms(probe,"selection/forward");
                probe=fresh();probe.searchString="synthetic";prewarms(probe,"preset search");
                probe=fresh();probe.searchString="";prewarms(probe,"empty preset search");
                probe=fresh();probe.inPreviewMode=true;prewarms(probe,"preview");
                probe=fresh();probe.createChat(context);
                check(Theme.calls.equals(Arrays.asList("chat")),"direct first chat retains initialization");
                check(ApplicationLoader.applicationHandler.queued.isEmpty(),"direct first chat does not defer resources");
                probe=fresh();probe.onlySelect=true;probe.createList(context);probe.createChat(context);
                check(Theme.calls.equals(Arrays.asList("chat")),"first chat initializes before pending list callback");
                check(ApplicationLoader.applicationHandler.queued.size()==1,"chat entry does not consume pending list callback");
                ApplicationLoader.applicationHandler.drain();
                check(Theme.calls.equals(Arrays.asList("chat","chat")),"pending list callback retains its original invocation");
                probe=fresh();CodexRuntime.enabled=false;ApplicationLoader.applicationHandler=null;probe.createList(context);
                check(Theme.calls.isEmpty(),"missing handler retains original no-op");
                System.out.println("PASS real dialog/chat resource boundaries: "+checks+" checks");
            }
        """;
}
