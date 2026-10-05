package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/** 提取真实 File 点击分支及权限标记；只替代 Android 权限和打开布局边界。 */
public final class DocumentPickerPermissionTest {
    /** 用当前源或指定前像重放，所有权限均为合成值，不访问设备和真实文件选择器。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0
                ? "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java" : args[0]);
        String body = Files.readString(source);
        String click = slice(body, "} else if (num == 4) {", "} else if (num == 5) {");
        BlockStmt actualClick = StaticJavaParser.parseBlock("{" + click + "}");
        String badgeBranch = slice(body, "} else if (position == documentButton) {", "} else if (position == locationButton) {");
        Matcher badge = Pattern.compile("\\berr\\s*=\\s*([^;]+);").matcher(badgeBranch);
        check(badge.find(), "缺少真实文件权限标记表达式");
        String badgeExpression = StaticJavaParser.parseExpression(badge.group(1)).toString();
        String permission = "private static boolean checkPhotoAndDocumentsPermission(Context context) {"
                + slice(body, "private static boolean checkPhotoAndDocumentsPermission(Context context) {", "private boolean shownAiButton;");
        permission = StaticJavaParser.parseMethodDeclaration(permission.trim()).toString();
        String actual = ("void click() " + actualClick + "\nboolean badge() { return " + badgeExpression + "; }\n" + permission)
                .replace("com.butang.codextop.CodexRuntime", "CodexRuntime");
        Path temp = Files.createTempDirectory("codex-document-permission-");
        try {
            Path fixture = temp.resolve("DocumentPermissionProbe.java");
            Files.writeString(fixture, FIXTURE + actual + SCENARIOS + "}\n");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                    "-d", temp.toString(), fixture.toString()) == 0, "真实 File 分支 Java 8 合成编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("DocumentPermissionProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("真实 File 权限回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 按相邻真实分支截取正文；边界变化时拒绝静默测试替代实现。 */
    private static String slice(String source, String begin, String end) {
        int start = source.indexOf(begin);
        check(start >= 0, "缺少真实分支起点 " + begin);
        start += begin.length();
        int finish = source.indexOf(end, start);
        check(finish > start, "缺少真实分支终点 " + end);
        return source.substring(start, finish);
    }

    /** 报告合成断言失败，不忽略缺失的生产分支。 */
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final String FIXTURE = """
        import java.util.*;
        public final class DocumentPermissionProbe {
            static final class Build {static final class VERSION {static int SDK_INT;}}
            static final class BuildVars {static boolean NO_SCOPED_STORAGE;}
            static final class PackageManager {static final int PERMISSION_GRANTED=0;}
            static final class Manifest {static final class permission {
                static final String READ_MEDIA_IMAGES="images",READ_MEDIA_VIDEO="videos",READ_EXTERNAL_STORAGE="storage";
            }}
            static final class BasePermissionsActivity {static final int REQUEST_CODE_EXTERNAL_STORAGE=4;}
            static final class CodexRuntime {
                static boolean owns;
                /** 合成已发现电脑对话，未知会话保持普通 Telegram 路径。 */
                static boolean ownsConversation(long id){return owns&&id==42;}
            }
            static class Context {
                final Set<String> granted=new HashSet<>();
                /** Android 权限边界只返回本轮指定的合成授权。 */
                int checkSelfPermission(String permission){return granted.contains(permission)?0:-1;}
            }
            static final class Activity extends Context {
                final List<String> requests=new ArrayList<>();
                /** 记录权限请求，不显示系统 UI。 */
                void requestPermissions(String[] permissions,int requestCode){
                    if(requestCode!=4)throw new AssertionError("request code changed");
                    requests.addAll(Arrays.asList(permissions));
                }
            }
            static final class AndroidUtilities {
                /** 合成 Context 对应当前页面的活动。 */
                static Activity findActivity(Context context){return (Activity)context;}
            }
            static final class ContextCompat {
                /** 保留原标记方法的 Android 权限查询边界。 */
                static int checkSelfPermission(Context context,String permission){return context.checkSelfPermission(permission);}
            }
            final Activity activity=new Activity();final Context mContext=activity;
            boolean documentsEnabled=true,restrictionHandled;int opens;
            /** 提供与拥有者守卫一致的合成对话。 */
            long getDialogId(){return 42;}
            /** 提供原点击分支使用的活动上下文。 */
            Context getContext(){return activity;}
            /** 保留原限制已处理时提前返回的边界。 */
            boolean checkCanRemoveRestrictionsByBoosts(){return restrictionHandled;}
            /** 仅观察是否进入原文件布局，不替换其系统选择器或发送链。 */
            void openDocumentsLayout(boolean show){if(!show)throw new AssertionError("layout show changed");opens++;}
        """;

    private static final String SCENARIOS = """
            static final List<String> failures=new ArrayList<>();static int cases;
            /** 同时验证能否进入原布局、实际请求权限集合和 File 错误标记。 */
            static void scenario(String name,int sdk,boolean codex,String[] grants,boolean enabled,boolean handled,
                    int expectedOpens,String[] expectedRequests,boolean expectedBadge){
                Build.VERSION.SDK_INT=sdk;BuildVars.NO_SCOPED_STORAGE=sdk<=29;CodexRuntime.owns=codex;
                DocumentPermissionProbe probe=new DocumentPermissionProbe();
                probe.activity.granted.addAll(Arrays.asList(grants));probe.documentsEnabled=enabled;probe.restrictionHandled=handled;
                probe.click();boolean badge=probe.badge();cases++;
                if(probe.opens!=expectedOpens||!probe.activity.requests.equals(Arrays.asList(expectedRequests))||badge!=expectedBadge)
                    failures.add(name+" opens="+probe.opens+" requests="+probe.activity.requests+" badge="+badge);
            }
            /** 覆盖无权限、有限媒体、旧 Android、普通 Telegram 与原限制守卫。 */
            public static void main(String[] args){
                String[] none={},storage={"storage"},media={"images","videos"};
                for(int sdk:new int[]{30,32,33,34,36}){
                    scenario("codex no grants api"+sdk,sdk,true,none,true,false,1,none,false);
                    scenario("codex images only api"+sdk,sdk,true,new String[]{"images"},true,false,1,none,false);
                    scenario("codex videos only api"+sdk,sdk,true,new String[]{"videos"},true,false,1,none,false);
                    scenario("codex existing media api"+sdk,sdk,true,media,true,false,1,none,false);
                    scenario("codex restriction api"+sdk,sdk,true,none,false,true,0,none,false);
                }
                for(boolean codex:new boolean[]{false,true}){
                    for(int sdk:new int[]{23,29}){
                        scenario("legacy denied "+codex+" api"+sdk,sdk,codex,none,true,false,0,storage,true);
                        scenario("legacy granted "+codex+" api"+sdk,sdk,codex,storage,true,false,1,none,false);
                    }
                    scenario("pre-runtime permissions "+codex,22,codex,none,true,false,1,none,false);
                }
                for(int sdk:new int[]{30,32}){
                    scenario("telegram storage denied api"+sdk,sdk,false,none,true,false,0,storage,true);
                    scenario("telegram storage granted api"+sdk,sdk,false,storage,true,false,1,none,false);
                }
                for(int sdk:new int[]{33,34,36}){
                    scenario("telegram denied api"+sdk,sdk,false,none,true,false,0,media,true);
                    scenario("telegram images only api"+sdk,sdk,false,new String[]{"images"},true,false,0,media,true);
                    scenario("telegram videos only api"+sdk,sdk,false,new String[]{"videos"},true,false,0,media,true);
                    scenario("telegram granted api"+sdk,sdk,false,media,true,false,1,none,false);
                    scenario("telegram restriction api"+sdk,sdk,false,none,false,true,0,none,true);
                }
                if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
                System.out.println("DocumentPickerPermission: "+cases+" cases passed using actual click and badge source");
            }
        """;
}
