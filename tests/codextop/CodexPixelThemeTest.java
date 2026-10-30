package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/** 提取真实主题迁移、选择、色表复制和静态纸面决策，只替换偏好、系统配置与 Drawable 等平台边界。 */
public final class CodexPixelThemeTest {
    /** 编译真实决策并核对五样式、日夜槽、原迁移、聊天对比度、小纹理缓存和底栏映射。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setStoreTokens(false).setAttributeComments(false);
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        String themeSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java"));
        String tabsSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java"));
        String glassSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Components/glass/GlassTabView.java"));
        String chatSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"));
        CompilationUnit themeUnit = StaticJavaParser.parse(themeSource);
        LinkedHashSet<String> paletteKeys = new LinkedHashSet<>();
        collectThemeKeys(paletteKeys, Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java")));
        java.util.Set<String> declaredKeys = themeUnit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .flatMap(field -> field.getVariables().stream()).map(variable -> variable.getNameAsString())
                .collect(java.util.stream.Collectors.toSet());
        for (String key : paletteKeys) check(declaredKeys.contains(key), "色表引用了不存在的真实 Theme 键 " + key);
        MethodDeclaration plan = method(themeUnit, "planCodexPixelThemeMigration");
        MethodDeclaration commit = method(themeUnit, "commitCodexPixelThemeMigration");
        MethodDeclaration copy = method(themeUnit, "copyCodexPixelPalette");
        MethodDeclaration wallpaper = method(themeUnit, "codexPixelWallpaper");
        StringBuilder appearance = new StringBuilder();
        for (String name : new String[]{"getCodexPixelStyle", "codexPixelStyleForSlot", "getCodexPixelAppearanceLabel", "selectCodexPixelAppearance", "needSwitchToTheme", "applyDayNightThemeMaybe"}) {
            appearance.append(method(themeUnit, name)).append('\n');
        }
        appearance.append(remoteSaveGuards(themeUnit));
        appearance.append(localApplyState(themeUnit));
        for (MethodDeclaration overload : themeUnit.findAll(MethodDeclaration.class)) {
            if (overload.getNameAsString().equals("applyTheme") && overload.getParameters().size() >= 1
                    && overload.getParameters().size() <= 3) appearance.append(overload).append('\n');
        }
        java.util.List<com.github.javaparser.ast.expr.MethodCallExpr> coldCalls = themeUnit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("applyTheme") && call.getArguments().size() == 4
                        && call.getArgument(0).toString().equals("applyingTheme"))
                .collect(java.util.stream.Collectors.toList());
        check(coldCalls.size() == 1, "缺少唯一真实冷恢复 applyTheme 调用");
        appearance.append("static void coldApply(ThemeInfo applyingTheme,int switchToTheme){").append(coldCalls.get(0)).append(";}\n");
        String restoreSlots = themeUnit.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .filter(assign -> (assign.getTarget().toString().equals("codexPixelLightStyle") || assign.getTarget().toString().equals("codexPixelDarkStyle"))
                        && assign.getValue().toString().contains("preferences.getInt"))
                .map(assign -> assign.toString() + ";").collect(java.util.stream.Collectors.joining("\n"));
        check(restoreSlots.contains("codexPixelStyleForSlot") && restoreSlots.contains("\"codexPixelLightStyle\"")
                && restoreSlots.contains("\"codexPixelDarkStyle\""), "启动没有恢复两个有明暗身份的样式槽");
        appearance.append("static void restoreSlots(android.content.SharedPreferences preferences) {\n").append(restoreSlots).append("\n}\n");
        String fields = themeUnit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .filter(field -> field.getVariables().stream().anyMatch(variable -> java.util.Set.of(
                        "codexPixelLightStyle", "codexPixelDarkStyle", "currentTheme", "currentDayTheme", "currentNightTheme",
                        "selectedAutoNightType", "isInNigthMode", "themesDict", "previousTheme", "defaultTheme",
                        "switchingNightTheme", "lastThemeSwitchTime").contains(variable.getNameAsString())))
                .map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
        String migrationType = themeUnit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("CodexPixelThemeMigration"))
                .findFirst().orElseThrow(() -> new AssertionError("缺少一次性迁移结果")).toString();
        String bubbleSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/MessageDrawable.java"));
        int stroke = bubbleSource.indexOf("Paint.Style.STROKE");
        int genericDraw = bubbleSource.indexOf("public void draw(Canvas canvas, Paint paintToUse)");
        check(stroke > 0 && genericDraw > stroke, "描边进入了通用 draw");
        check(bubbleSource.contains("getActiveTheme()") && bubbleSource.contains("currentType != TYPE_TEXT") && bubbleSource.contains("outlineColor"), "文字描边没有限定当前 Day/Night");
        checkOrder(themeSource, themeUnit, tabsSource, glassSource, chatSource, commit, copy, wallpaper);
        Path temp = Files.createTempDirectory("codex-pixel-theme-");
        Path sentinel = temp.resolve("wallpaper.jpg");
        Files.writeString(sentinel, "original-wallpaper");
        try {
            writeStubs(temp, root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
            Files.writeString(temp.resolve("Theme.java"), themeShell(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"))
                    + fields + "\n" + migrationType + "\n" + plan + "\n" + commit + "\n" + copy + "\n" + wallpaper + "\n" + appearance + "\n}\n");
            Files.writeString(temp.resolve("CodexPixelThemeProbe.java"), PROBE);
            Files.writeString(temp.resolve("ManualThemeHost.java"), manualThemeHost(root));
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(),
                    temp.resolve("SparseIntArray.java").toString(),
                    temp.resolve("Rect.java").toString(),
                    temp.resolve("Bitmap.java").toString(),
                    temp.resolve("Shader.java").toString(),
                    temp.resolve("BitmapShader.java").toString(),
                    temp.resolve("Paint.java").toString(),
                    temp.resolve("Canvas.java").toString(),
                    temp.resolve("Drawable.java").toString(),
                    temp.resolve("ColorDrawable.java").toString(),
                    temp.resolve("SharedPreferences.java").toString(),
                    temp.resolve("Build.java").toString(),
                    temp.resolve("AndroidUtilities.java").toString(),
                    temp.resolve("Theme.java").toString(),
                    temp.resolve("MessagesController.java").toString(),
                    temp.resolve("CodexRuntime.java").toString(),
                    temp.resolve("Configuration.java").toString(),
                    temp.resolve("ApplicationLoader.java").toString(),
                    temp.resolve("ManualThemeHost.java").toString(),
                    root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java").toString(),
                    root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelWallpaper.java").toString(),
                    temp.resolve("CodexPixelThemeProbe.java").toString()) != 0) {
                throw new AssertionError("主题决策夹具编译失败");
            }
            ArrayList<URL> classpath = new ArrayList<>();
            classpath.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                classpath.add(Path.of(entry).toUri().toURL());
            }
            try (URLClassLoader loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("org.telegram.ui.ActionBar.CodexPixelThemeProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]);
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new AssertionError("像素主题决策回归失败", error.getCause());
            }
            check("original-wallpaper".equals(Files.readString(sentinel)), "平色决策改动了墙纸文件");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    /** 提取实际 saveTheme 外层 IfStmt，运行原条件和 RPC 调用，不用文本命中或复写判断代替。 */
    private static String remoteSaveGuards(CompilationUnit unit) {
        ArrayList<com.github.javaparser.ast.expr.MethodCallExpr> calls = new ArrayList<>();
        for (com.github.javaparser.ast.expr.MethodCallExpr call : unit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
            if (call.getNameAsString().equals("saveTheme") && call.getArguments().size() == 4
                    && call.getArgument(0).toString().equals("themeInfo")) calls.add(call);
        }
        check(calls.size() == 3, "实际主题保存点不再是三个，必须重新核对完整范围");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < calls.size(); index++) {
            com.github.javaparser.ast.expr.MethodCallExpr call = calls.get(index);
            MethodDeclaration owner = call.findAncestor(MethodDeclaration.class).orElseThrow();
            check(owner.getNameAsString().equals(index == 0 ? "applyTheme" : "applyThemeInBackground"), "保存点所属实际方法变化");
            check(call.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isPresent() == (index == 1),
                    "异步有资源回调和无资源尾部没有分别覆盖");
            com.github.javaparser.ast.stmt.IfStmt guard = call.findAncestor(com.github.javaparser.ast.stmt.IfStmt.class).orElseThrow();
            result.append("static void remoteGuard").append(index).append("(ThemeInfo themeInfo,boolean save,boolean nightTheme){\n")
                    .append(guard).append("\n}\n");
        }
        return result.toString();
    }

    /** 保留真实资产主题的本机偏好和日间身份分支；Day/Night 均有 assetName。 */
    private static String localApplyState(CompilationUnit unit) {
        MethodDeclaration apply = unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("applyTheme") && method.getParameters().size() == 4)
                .findFirst().orElseThrow();
        com.github.javaparser.ast.stmt.IfStmt asset = apply.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream()
                .filter(statement -> statement.getCondition().toString().equals("themeInfo.pathToFile != null || themeInfo.assetName != null"))
                .findFirst().orElseThrow();
        com.github.javaparser.ast.stmt.IfStmt save = asset.getThenStmt().asBlockStmt().getStatement(0).asIfStmt();
        check(save.getCondition().toString().equals("!nightTheme && save"), "本机主题保存条件变化");
        com.github.javaparser.ast.stmt.IfStmt day = apply.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream()
                .filter(statement -> statement.getCondition().toString().equals("!nightTheme && previousTheme == null"))
                .findFirst().orElseThrow();
        String current = apply.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .filter(assign -> assign.toString().equals("currentTheme = themeInfo")).findFirst().orElseThrow().toString();
        return "static void localApplyState(ThemeInfo themeInfo,boolean save,boolean nightTheme){\n" + save + "\n" + day + "\n" + current + ";\n}\n"
                + method(unit, "isCurrentThemeNight") + "\n";
    }

    /** 编译真实宿主方法；Launch 仅取 args[2]==null 的原接收分支，位图揭示不在本次范围。 */
    private static String manualThemeHost(Path root) throws Exception {
        CompilationUnit launch = StaticJavaParser.parse(Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java")));
        CompilationUnit layout = StaticJavaParser.parse(Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java")));
        CompilationUnit navigation = StaticJavaParser.parse(Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/INavigationLayout.java")));
        com.github.javaparser.ast.stmt.BlockStmt receiver = launch.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream()
                .filter(statement -> statement.getCondition().toString().equals("id == NotificationCenter.needSetDayNightTheme"))
                .findFirst().orElseThrow().getThenStmt().asBlockStmt();
        check(receiver.getStatement(1).asIfStmt().getCondition().toString().equals("args[2] != null"), "接收方位图分支位置变化");
        StringBuilder source = new StringBuilder(HOST_BOUNDARY);
        source.append("static class Launch {ManualThemeHost actionBarLayout,layersActionBarLayout,rightActionBarLayout;\n")
                .append("void deliver(Object... args){if(args[2]!=null)throw new AssertionError(\"仅测试原无位图接收分支\");\n")
                .append(receiver.getStatement(0)).append('\n')
                .append(receiver.getStatement(1).asIfStmt().getElseStmt().orElseThrow()).append('\n');
        for (int index = 2; index < receiver.getStatements().size(); index++) source.append(receiver.getStatement(index)).append('\n');
        source.append("}}\n");
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration settings = navigation.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("ThemeAnimationSettings")).findFirst().orElseThrow().clone();
        settings.setStatic(true);
        source.append(settings).append('\n');
        for (MethodDeclaration overload : navigation.findAll(MethodDeclaration.class)) {
            if (overload.getNameAsString().equals("animateThemedValues") && overload.getBody().isPresent()) {
                MethodDeclaration actual = overload.clone();
                actual.setDefault(false);actual.setPublic(true);actual.getAnnotations().clear();
                source.append(actual).append('\n');
            }
        }
        for (String name : new String[]{"animateThemedValues", "checkNeedRebuild", "addStartDescriptions", "addEndDescriptions", "setThemeAnimationValue", "globallyUpdateColors"}) {
            MethodDeclaration actual = method(layout, name).clone();actual.getAnnotations().clear();
            source.append(actual).append('\n');
        }
        return source.append("}\n").toString();
    }

    /** 核对调用顺序、一次保存、底栏映射，以及聊天页和 getColor 没有被另开一条路径。 */
    private static void checkOrder(String themeSource, CompilationUnit themeUnit, String tabsSource, String glassSource,
            String chatSource, MethodDeclaration commit, MethodDeclaration copy, MethodDeclaration wallpaper) {
        MethodDeclaration refresh = themeUnit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("refreshThemeColors") && method.getParameters().size() == 2)
                .findFirst().orElseThrow(() -> new AssertionError("缺少 refreshThemeColors"));
        String refreshText = refresh.toString();
        int accent = refreshText.indexOf("fillAccentColors");
        int palette = refreshText.indexOf("copyCodexPixelPalette");
        int table = refreshText.indexOf("applyCalculatedTableColors");
        int article = refreshText.indexOf("applyCalculatedArticleCodeColors");
        check(accent >= 0 && palette > accent && table > palette && article > table, "色表没有插在强调色填充之后、派生色之前");
        check(!copy.toString().contains("setColor"), "色表复制不应逐键走 setColor");
        MethodDeclaration color = themeUnit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("getColor") && method.getParameters().size() == 3)
                .findFirst().orElseThrow(() -> new AssertionError("缺少 getColor"));
        check(!color.toString().contains("Codex") && !color.toString().contains("Pixel"), "getColor 被改成全局像素短路");
        MethodDeclaration background = themeUnit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("createBackgroundDrawable") && method.getParameters().size() > 8)
                .findFirst().orElseThrow(() -> new AssertionError("缺少中央壁纸方法"));
        String backgroundText = background.toString();
        int pixel = backgroundText.indexOf("codexPixelWallpaper");
        int file = backgroundText.indexOf("wallpaperFile.exists");
        int output = backgroundText.indexOf("FileOutputStream");
        check(pixel >= 0 && file > pixel && output > pixel, "平色壁纸没有在读或写墙纸文件之前返回");
        check(!wallpaper.toString().contains("File") && !wallpaper.toString().contains("delete"), "平色壁纸决策接触了文件");
        check(!themeSource.contains("setRoundRadius"), "像素主题改写了气泡圆角");
        int migration = themeSource.indexOf("boolean codexPixelThemesMigrated = commitCodexPixelThemeMigration");
        int restore = themeSource.indexOf("String theme = preferences.getString(\"theme\", null)");
        int loaded = themeSource.indexOf("autoNightLastSunCheckDay = preferences.getInt");
        int save = themeSource.indexOf("if (codexPixelThemesMigrated)");
        check(migration >= 0 && restore > migration, "迁移发生在原主题名恢复之后");
        check(save > loaded && save - loaded < 500 && themeSource.indexOf("saveAutoNightThemeConfig()", save) - save < 180,
                "迁移没有在自动夜间参数读入后沿原方法保存");
        String commitBody = commit.getBody().orElseThrow().toString();
        int themeStore = commitBody.indexOf("themePreferences.edit");
        int mainStore = commitBody.indexOf("main.edit");
        check(themeStore >= 0 && mainStore > themeStore, "主题记忆键没有先于 main 标记落盘");
        check(!commitBody.contains("saveAutoNightThemeConfig"), "迁移提交过早写回自动夜间参数");
        check(count(themeSource, "commitCodexPixelThemeMigration") == 2, "迁移入口被重复调用");
        check(count(themeSource, "copyCodexPixelPalette") == 2, "色表复制被重复调用");
        check(count(themeSource, "codexPixelWallpaper") == 2, "平色壁纸被重复调用");
        check(!chatSource.contains("codexPixelWallpaper") && !chatSource.contains("copyCodexPixelPalette"), "聊天页另写了壁纸或色表");
        check(glassSource.contains("CHATS_PIXEL(TabAnimationType.STATIC, R.drawable.codextop_pixel_chat)")
                && glassSource.contains("DEVICE_PIXEL(TabAnimationType.STATIC, R.drawable.codextop_pixel_devices)")
                && glassSource.contains("PROFILE_PIXEL(TabAnimationType.STATIC, R.drawable.codextop_pixel_profile)"),
                "底栏像素枚举没有引用三个静态图标");
        check(glassSource.contains("if (tabAnimation.iconStatic != -1)"), "原静态图标绘制被换掉");
        check(tabsSource.contains("codex ? GlassTabView.TabAnimation.CHATS_PIXEL : GlassTabView.TabAnimation.CHATS")
                && tabsSource.contains("codex ? GlassTabView.TabAnimation.DEVICE_PIXEL : GlassTabView.TabAnimation.CONTACTS")
                && tabsSource.contains("codex ? GlassTabView.TabAnimation.PROFILE_PIXEL : GlassTabView.TabAnimation.SETTINGS")
                && tabsSource.contains("new GlassTabView[codex ? 3 : 5]")
                && tabsSource.contains("tabs[INDEX_CHATS].setText(\"会话\")")
                && tabsSource.contains("tabs[INDEX_CONTACTS].setText(\"电脑\")")
                && tabsSource.contains("tabs[INDEX_SETTINGS].setText(\"我的\")"),
                "Codex 底栏没有只替换会话、电脑、我的三个图标");
    }

    /** 取出真实方法；签名消失时失败，避免夹具悄悄改测自己的副本。 */
    private static MethodDeclaration method(CompilationUnit unit, String name) {
        return unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("缺少主题方法 " + name));
    }

    /** 统计标识出现次数，用来确认生产路径只调用一次。 */
    private static int count(String source, String token) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(token, index)) >= 0; index += token.length()) count++;
        return count;
    }

    /** 断言失败时直接说明哪条主题契约没有成立。 */
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** 桩键取色表与探针的并集，缺字段时仍能编译，避免把编译失败当成对比度 RED。 */
    private static void collectThemeKeys(LinkedHashSet<String> keys, String source) {
        Matcher matcher = Pattern.compile("Theme\\.(key_[A-Za-z0-9_]+)").matcher(source);
        while (matcher.find()) keys.add(matcher.group(1));
    }

    /** 为真实色表生成同名颜色键，不复制主题实现。 */
    private static String themeShell(Path palette) throws Exception {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        collectThemeKeys(keys, Files.readString(palette));
        collectThemeKeys(keys, PROBE);
        StringBuilder keysText = new StringBuilder();
        keysText.append("public static int colorsCount;\n");
        for (String key : keys) keysText.append("public static final int ").append(key).append("=colorsCount++;\n");
        return """
                package org.telegram.ui.ActionBar;
                import android.util.SparseIntArray;
                import android.graphics.drawable.ColorDrawable;
                import android.content.SharedPreferences;
                import android.os.Build;
                import org.telegram.messenger.AndroidUtilities;
                import org.telegram.messenger.MessagesController;
                import org.telegram.messenger.ApplicationLoader;
                import android.content.res.Configuration;
                import java.util.HashMap;
                import java.util.Calendar;
                public class Theme {
                public static final int AUTO_NIGHT_TYPE_NONE=0;
                public static final int AUTO_NIGHT_TYPE_SCHEDULED=1;
                public static final int AUTO_NIGHT_TYPE_AUTOMATIC=2;
                public static final int AUTO_NIGHT_TYPE_SYSTEM=3;
                """ + keysText + """
                public static int applied,checked;
                public static boolean lastSave,lastNight;
                public static int accentsSaved;static int switchNightThemeDelay;static long lastDelayUpdateTime;
                public interface Colorable {void updateColors();}
                public interface ResourcesProvider {}
                public static void setAnimatingColor(boolean value){}
                public static int getColor(int key,ResourcesProvider provider){return com.butang.codextop.CodexPixelPalette.byStyle(getCodexPixelStyle().id).get(key);}
                public static void saveThemeAccents(ThemeInfo info,boolean a,boolean b,boolean c,boolean d){accentsSaved++;}
                public static void applyThemeInBackground(ThemeInfo info,boolean night,Runnable done){throw new AssertionError("手动主题不应进入带回调背景应用");}
                public static class ThemeInfo {
                    final String name; final boolean dark;final int account=0;
                    ThemeInfo(String name,boolean dark){this.name=name;this.dark=dark;}
                    public String getKey(){return name;}
                    public boolean isDark(){return dark;}
                    public Object getAccent(boolean create){return null;}
                    public void setCurrentAccentId(int id){accentsSaved++;}
                }
                // 只替换资源加载；本机保存、日间身份和 RPC 条件均提取真实源码。
                private static void applyTheme(ThemeInfo info,boolean save,boolean removeWallpaper,boolean night){applied++;lastSave=save;lastNight=night;localApplyState(info,save,night);remoteGuard0(info,save,night);}
                private static void checkAutoNightThemeConditions(){checked++;}
                private static void saveAutoNightThemeConfig(){}
                static void resetBoundary(){
                    themesDict=new HashMap<String,ThemeInfo>();
                    themesDict.put("Day",new ThemeInfo("Day",false));themesDict.put("Night",new ThemeInfo("Night",true));
                    currentDayTheme=currentTheme=defaultTheme=themesDict.get("Day");currentNightTheme=themesDict.get("Night");
                    previousTheme=null;switchingNightTheme=false;NotificationCenter.pending=null;NotificationCenter.args=null;NotificationCenter.posts=0;
                    org.telegram.messenger.MessagesController.saved=0;
                    codexPixelLightStyle=2;codexPixelDarkStyle=1;selectedAutoNightType=AUTO_NIGHT_TYPE_SYSTEM;
                    applied=checked=accentsSaved=0;lastSave=lastNight=false;isInNigthMode=false;
                    com.butang.codextop.CodexRuntime.active=true;
                }
                static void observeNight(boolean night){currentTheme=themesDict.get(night?"Night":"Day");}
                static ThemeInfo dayBoundary(){return currentDayTheme;}
                /** 用例在通知方法返回后交付，覆盖 switchingNightTheme 已释放的异步接收情形。 */
                static void deliverTheme(){ThemeInfo info=NotificationCenter.pending;boolean night=NotificationCenter.pendingNight;NotificationCenter.pending=null;applyTheme(info,night);}
                static void guard(int index,ThemeInfo info,boolean save,boolean night){if(index==0)remoteGuard0(info,save,night);else if(index==1)remoteGuard1(info,save,night);else remoteGuard2(info,save,night);}
                static class SystemClock {static long elapsedRealtime(){return 1000L;}}
                static class NotificationCenter {
                    static final int needSetDayNightTheme=1;static final NotificationCenter instance=new NotificationCenter();
                    static ThemeInfo pending;static boolean pendingNight;static Object[] args;static int posts;
                    static NotificationCenter getGlobalInstance(){return instance;}
                    void postNotificationName(int id,Object... values){posts++;args=values;pending=(ThemeInfo)values[0];pendingNight=(Boolean)values[1];}
                }
                static boolean autoNightScheduleByLocation,lightSensorRegistered,switchNightRunnableScheduled,switchDayRunnableScheduled;
                static int autoNightLastSunCheckDay,autoNightSunriseTime,autoNightSunsetTime,autoNightDayStartTime,autoNightDayEndTime;
                static double autoNightLocationLatitude=10000,autoNightLocationLongitude=10000;
                static float lastBrightnessValue,autoNightBrighnessThreshold;
                static Sensor lightSensor;static SensorManager sensorManager;static Object ambientSensorListener;
                static class Context {static final String SENSOR_SERVICE="sensor";}
                static class Sensor {static final int TYPE_LIGHT=1;}
                static class SensorManager {Sensor getDefaultSensor(int type){return null;}void registerListener(Object listener,Sensor sensor,int interval){}}
                static class BuildVars {static final boolean LOGS_ENABLED=false;}
                static class FileLog {static void d(String text){}}
                static class SunDate {static int[] calculateSunriseSunset(double latitude,double longitude){return new int[]{0,0};}}
                public static class BackgroundDrawableSettings {
                    public android.graphics.drawable.Drawable wallpaper;
                    public android.graphics.drawable.Drawable themedWallpaper;
                    public Boolean isWallpaperMotion;
                    public Boolean isPatternWallpaper;
                    public Boolean isCustomTheme;
                }
                """;
    }

    /** 写出最小平台类型。偏好提交、颜色数组和纯色 Drawable 是这里要观察的边界。 */
    private static void writeStubs(Path temp, Path palette) throws Exception {
        Files.writeString(temp.resolve("MessagesController.java"), "package org.telegram.messenger; public class MessagesController { public static android.content.SharedPreferences main=new android.content.SharedPreferences(); public static android.content.SharedPreferences getGlobalMainSettings(){return main;} public static int saved;private static final MessagesController instance=new MessagesController();public static MessagesController getInstance(int account){return instance;}public void saveTheme(Object theme,Object accent,boolean night,boolean install){saved++;} }");
        Files.writeString(temp.resolve("CodexRuntime.java"), "package com.butang.codextop; public class CodexRuntime { public static boolean active=true; public static boolean enabled(){return active;} }");
        Files.writeString(temp.resolve("Configuration.java"), "package android.content.res; public class Configuration {public static final int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_NO=16,UI_MODE_NIGHT_UNDEFINED=0,UI_MODE_NIGHT_YES=32;public int uiMode=16;}");
        Files.writeString(temp.resolve("ApplicationLoader.java"), "package org.telegram.messenger; public class ApplicationLoader {public static final AppContext applicationContext=new AppContext();public static class AppContext {public final Resources resources=new Resources();public Resources getResources(){return resources;}public Object getSystemService(String name){return null;}}public static class Resources {public final android.content.res.Configuration configuration=new android.content.res.Configuration();public android.content.res.Configuration getConfiguration(){return configuration;}}}");
        Files.writeString(temp.resolve("AndroidUtilities.java"), "package org.telegram.messenger; public class AndroidUtilities { public static float density=1f;public static boolean tablet;public static boolean isTablet(){return tablet;}public static void runOnUIThread(Runnable action){action.run();}public static void runOnUIThread(Runnable action,long delay){} }");
        Files.writeString(temp.resolve("SparseIntArray.java"), """
                package android.util;
                public class SparseIntArray {
                    private final int[] keys=new int[512];
                    private final int[] values=new int[512];
                    private int count;
                    public SparseIntArray(){}
                    public SparseIntArray(int capacity){}
                    public void put(int key,int value){int index=indexOfKey(key);if(index>=0)values[index]=value;else{keys[count]=key;values[count]=value;count++;}}
                    public int get(int key){int index=indexOfKey(key);return index<0?0:values[index];}
                    public int indexOfKey(int key){for(int index=0;index<count;index++)if(keys[index]==key)return index;return -1;}
                    public int keyAt(int index){return keys[index];}
                    public int valueAt(int index){return values[index];}
                    public int size(){return count;}
                }
                """);
        Files.writeString(temp.resolve("Rect.java"), "package android.graphics; public class Rect { public Rect(){} }");
        Files.writeString(temp.resolve("Bitmap.java"), """
                package android.graphics;
                public class Bitmap {
                    public enum Config { ARGB_8888 }
                    public static int created;
                    public final int width,height;
                    private Bitmap(int width,int height){this.width=width;this.height=height;created++;}
                    public static Bitmap createBitmap(int width,int height,Config config){if(width<1||height<1||width>128||height>128)throw new AssertionError("纹理尺寸越界");return new Bitmap(width,height);}
                }
                """);
        Files.writeString(temp.resolve("Shader.java"), "package android.graphics; public class Shader { public enum TileMode { CLAMP, REPEAT } }");
        Files.writeString(temp.resolve("BitmapShader.java"), "package android.graphics; public class BitmapShader extends Shader { public final Bitmap bitmap; public BitmapShader(Bitmap bitmap,Shader.TileMode x,Shader.TileMode y){this.bitmap=bitmap;} }");
        Files.writeString(temp.resolve("Paint.java"), """
                package android.graphics;
                public class Paint {
                    public static final int ANTI_ALIAS_FLAG=1;
                    public static int created;
                    public Shader shader;
                    public Paint(){created++;}
                    public Paint(int flags){created++;}
                    public void setColor(int color){}
                    public void setAlpha(int alpha){}
                    public void setShader(Shader shader){this.shader=shader;}
                }
                """);
        Files.writeString(temp.resolve("Canvas.java"), """
                package android.graphics;
                public class Canvas {
                    public Canvas(Bitmap bitmap){}
                    public void drawRect(float left,float top,float right,float bottom,Paint paint){}
                    public void drawRect(Rect rect,Paint paint){}
                }
                """);
        Files.writeString(temp.resolve("Drawable.java"), """
                package android.graphics.drawable;
                import android.graphics.Canvas;
                import android.graphics.Rect;
                public class Drawable { private final Rect bounds=new Rect(); public Rect getBounds(){return bounds;} public void draw(Canvas canvas){} public int getAlpha(){return 255;} }
                """);
        Files.writeString(temp.resolve("ColorDrawable.java"), """
                package android.graphics.drawable;
                public class ColorDrawable extends Drawable {
                    private final int color;
                    public ColorDrawable(int color){this.color=color;}
                    public int getColor(){return color;}
                }
                """);
        Files.writeString(temp.resolve("SharedPreferences.java"), """
                package android.content;
                import java.util.HashMap;
                import java.util.HashSet;
                import java.util.Map;
                import java.util.Set;
                public class SharedPreferences {
                    public static final Object REMOVED=new Object();
                    public final HashMap<String,Object> values=new HashMap<>();
                    public final HashMap<String,Object> disk=new HashMap<>();
                    public final Set<String> written=new HashSet<>();
                    public boolean failNextCommit;
                    public String getString(String key,String def){Object value=values.get(key);return value instanceof String?(String)value:def;}
                    public int getInt(String key,int def){Object value=values.get(key);return value instanceof Integer?(Integer)value:def;}
                    public boolean getBoolean(String key,boolean def){Object value=values.get(key);return value instanceof Boolean?(Boolean)value:def;}
                    public java.util.Map<String,?> getAll(){return new HashMap<String,Object>(values);}
                    public Editor edit(){return new Editor();}
                    public class Editor {
                        final HashMap<String,Object> pending=new HashMap<>();
                        public Editor putString(String key,String value){pending.put(key,value);return this;}
                        public Editor putInt(String key,int value){pending.put(key,value);return this;}
                        public Editor putBoolean(String key,boolean value){pending.put(key,value);return this;}
                        public Editor putFloat(String key,float value){pending.put(key,value);return this;}
                        public Editor putLong(String key,long value){pending.put(key,value);return this;}
                        public Editor remove(String key){pending.put(key,REMOVED);return this;}
                        public void apply(){commit();}
                        public boolean commit(){
                            boolean fail=failNextCommit;
                            failNextCommit=false;
                            for (Map.Entry<String,Object> entry:pending.entrySet()) {
                                if (entry.getValue()==REMOVED) values.remove(entry.getKey());
                                else values.put(entry.getKey(), entry.getValue());
                            }
                            if (fail) { pending.clear(); return false; }
                            for (Map.Entry<String,Object> entry:new HashMap<String,Object>(pending).entrySet()) {
                                if (entry.getValue()==REMOVED) disk.remove(entry.getKey());
                                else { disk.put(entry.getKey(), entry.getValue()); written.add(entry.getKey()); }
                            }
                            pending.clear();
                            return true;
                        }
                    }
                }
                """);
        Files.writeString(temp.resolve("Build.java"), "package android.os; public class Build { public static class VERSION { public static int SDK_INT=29; } }");
    }

    /** 动画和 View 是可观察平台边界；主题宿主、递归刷新和延后覆盖执行真实方法。 */
    private static final String HOST_BOUNDARY = """
            package org.telegram.ui.ActionBar;
            import java.util.ArrayList;
            import org.telegram.messenger.AndroidUtilities;
            class View {}
            class ViewGroup extends View {
                final ArrayList<View> children=new ArrayList<View>();
                int getChildCount(){return children.size();}View getChildAt(int index){return children.get(index);}
            }
            public class ManualThemeHost extends ViewGroup {
                boolean transitionAnimationInProgress,startedTracking,animateThemeAfterAnimation;
                boolean animateSetThemeNightAfterAnimation,animateSetThemeAfterAnimationApply;
                int animateSetThemeAccentIdAfterAnimation;
                Theme.ThemeInfo animateSetThemeAfterAnimation;
                boolean inPreviewMode,transitionAnimationPreviewMode,rebuildAfterAnimation,rebuildLastAfterAnimation,showLastAfterAnimation;
                final ArrayList<BaseFragment> fragmentsStack=new ArrayList<BaseFragment>();
                final ArrayList<ArrayList<ThemeDescription>> themeAnimatorDescriptions=new ArrayList<ArrayList<ThemeDescription>>();
                final ArrayList<int[]> animateStartColors=new ArrayList<int[]>(),animateEndColors=new ArrayList<int[]>();
                final ArrayList<ThemeDescription.ThemeDescriptionDelegate> themeAnimatorDelegate=new ArrayList<ThemeDescription.ThemeDescriptionDelegate>();
                ArrayList<ThemeDescription> presentingFragmentDescriptions;
                ThemeAnimationSettings.onAnimationProgress animationProgressListener;
                ThemeDescription.ThemeDescriptionDelegate delegate;
                AnimatorSet themeAnimatorSet;float themeAnimationValue;
                MessageDrawable messageDrawableOutStart,messageDrawableOutMediaStart;
                final StartColorsProvider startColorsProvider=new StartColorsProvider();
                final Locker notificationsLocker=new Locker();
                final Surface background=new Surface(Theme.key_windowBackgroundWhite),title=new Surface(Theme.key_actionBarDefaultTitle);
                ManualThemeHost(){fragmentsStack.add(new BaseFragment());fragmentsStack.add(new BaseFragment());ViewGroup nested=new ViewGroup();nested.children.add(background);nested.children.add(title);children.add(nested);}
                BaseFragment getLastFragment(){return fragmentsStack.isEmpty()?null:fragmentsStack.get(fragmentsStack.size()-1);}
                void rebuildAllFragmentViews(boolean last,boolean show){throw new AssertionError("本用例不走重建分支");}
                void flush(){transitionAnimationInProgress=startedTracking=false;checkNeedRebuild();}
                int hiddenClears(){return fragmentsStack.get(0).cleared;}
                static class Surface extends View implements Theme.Colorable {
                    final int key;int updates,color,styleId;
                    Surface(int key){this.key=key;color=Theme.getColor(key,null);styleId=Theme.getCodexPixelStyle().id;}
                    public void updateColors(){updates++;color=Theme.getColor(key,null);styleId=Theme.getCodexPixelStyle().id;}
                }
                static class BaseFragment {Object visibleDialog;int cleared,parents;
                    ArrayList<ThemeDescription> getThemeDescriptions(){return new ArrayList<ThemeDescription>();}
                    void clearViews(){cleared++;}void setParentLayout(ManualThemeHost host){parents++;}
                }
                static class BottomSheet {ArrayList<ThemeDescription> getThemeDescriptions(){return new ArrayList<ThemeDescription>();}}
                static class AlertDialog {ArrayList<ThemeDescription> getThemeDescriptions(){return new ArrayList<ThemeDescription>();}}
                static class DialogsActivity {static boolean switchingTheme;}
                static class ThemeDescription {
                    Theme.ResourcesProvider resourcesProvider;
                    int getSetColor(){return 0;}int getCurrentKey(){return Theme.key_windowBackgroundWhite;}
                    ThemeDescriptionDelegate setDelegateDisabled(){return null;}
                    void setAnimatedColor(int color){}void setColor(int color,boolean useDefault,boolean save){}
                    static class ThemeDescriptionDelegate {void didSetColor(){}void onAnimationProgress(float value){}void onThemeProgress(float value){}}
                }
                static class MessageDrawable {static final int TYPE_TEXT=0,TYPE_MEDIA=1;boolean isCrossfadeBackground;
                    MessageDrawable(int type,boolean out,boolean selected,StartColorsProvider provider){}
                }
                static class StartColorsProvider {void saveColors(Theme.ResourcesProvider provider){}}
                static class Locker {int locks;void lock(){locks++;}void unlock(){locks--;}}
                static class Animator {}
                static class AnimatorListenerAdapter {public void onAnimationEnd(Animator animator){}public void onAnimationCancel(Animator animator){}}
                static class ObjectAnimator {final ManualThemeHost host;final float end;
                    ObjectAnimator(ManualThemeHost host,float end){this.host=host;this.end=end;}
                    static ObjectAnimator ofFloat(ManualThemeHost host,String property,float start,float end){return new ObjectAnimator(host,end);}
                }
                static class AnimatorSet extends Animator {AnimatorListenerAdapter listener;ObjectAnimator value;
                    void addListener(AnimatorListenerAdapter listener){this.listener=listener;}
                    void playTogether(ObjectAnimator value){this.value=value;}void setDuration(long duration){}
                    void start(){value.host.setThemeAnimationValue(value.end);listener.onAnimationEnd(this);}
                    void cancel(){if(listener!=null)listener.onAnimationCancel(this);}
                }
                static class Color {static int red(int color){return color>>>16&255;}static int green(int color){return color>>>8&255;}static int blue(int color){return color&255;}static int alpha(int color){return color>>>24;}static int argb(int a,int r,int g,int b){return a<<24|r<<16|g<<8|b;}}
            """;

    private static final String PROBE = """
            package org.telegram.ui.ActionBar;
            import android.content.SharedPreferences;
            import android.graphics.drawable.ColorDrawable;
            import android.os.Build;
            import android.util.SparseIntArray;
            public final class CodexPixelThemeProbe {
                static int assertions;
                static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
                public static void main(String[] args){
                    plan();
                    persist();
                    storeFailures();
                    Theme.resetBoundary();
                    palette();
                    foreground();
                    styles();
                    wallpaper();
                    tiles();
                    appearance();
                    remoteSave();
                    manualHost();
                    System.out.println("PASS CodexPixelThemeTest: "+assertions+" assertions, five styles, independent foreground, bounded reusable tiles, original theme lifecycle");
                }
                static void plan(){
                    Theme.CodexPixelThemeMigration manual=Theme.planCodexPixelThemeMigration(true,false,Theme.AUTO_NIGHT_TYPE_NONE,true);
                    check(manual.apply&&"Night".equals(manual.theme)&&"Night".equals(manual.nightTheme)&&"Day".equals(manual.lastDayTheme)&&"Night".equals(manual.lastDarkTheme),"手动深色没有保持 Night");
                    Theme.CodexPixelThemeMigration light=Theme.planCodexPixelThemeMigration(true,false,Theme.AUTO_NIGHT_TYPE_NONE,false);
                    check(light.apply&&"Day".equals(light.theme)&&"Night".equals(light.nightTheme),"手动浅色没有记为 Day");
                    for(int type:new int[]{Theme.AUTO_NIGHT_TYPE_SYSTEM,Theme.AUTO_NIGHT_TYPE_SCHEDULED,Theme.AUTO_NIGHT_TYPE_AUTOMATIC}){
                        Theme.CodexPixelThemeMigration auto=Theme.planCodexPixelThemeMigration(true,false,type,true);
                        check(auto.apply&&"Day".equals(auto.theme)&&"Night".equals(auto.nightTheme),"自动模式没有把日夜键交给原切换");
                    }
                    check(!Theme.planCodexPixelThemeMigration(true,true,Theme.AUTO_NIGHT_TYPE_NONE,true).apply,"已迁移后再次计划写偏好");
                    check(!Theme.planCodexPixelThemeMigration(false,false,Theme.AUTO_NIGHT_TYPE_NONE,true).apply,"普通包被迁移到 Day/Night");
                }
                static void persist(){
                    Build.VERSION.SDK_INT=29;
                    SharedPreferences main=new SharedPreferences();
                    SharedPreferences themes=new SharedPreferences();
                    main.values.put("theme","Blue");
                    main.values.put("nighttheme","Dark Blue");
                    main.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_SYSTEM);
                    main.values.put("autoNightDayStartTime",22*60);
                    main.values.put("autoNightBrighnessThreshold",0.25f);
                    check(Theme.commitCodexPixelThemeMigration(true,main,themes,"Blue",false),"Codex 首次迁移没有写偏好");
                    check("Day".equals(main.getString("theme",null))&&"Night".equals(main.getString("nighttheme",null)),"自动模式的日夜主题名不对");
                    check("Day".equals(themes.getString("lastDayTheme",null))&&"Night".equals(themes.getString("lastDarkTheme",null)),"两侧记忆键没有写成 Day/Night");
                    check(main.getBoolean("codexPixelDayNightMigrated",false),"迁移标志没有落盘");
                    check(main.getInt("selectedAutoNightType",-1)==Theme.AUTO_NIGHT_TYPE_SYSTEM&&main.getInt("autoNightDayStartTime",-1)==22*60,"迁移改写了自动夜间设置");
                    check(!main.written.contains("selectedAutoNightType")&&!main.written.contains("autoNightDayStartTime"),"迁移写入了应保留的自动夜间键");
                    main.values.put("theme","Arctic Blue");
                    main.written.clear();
                    themes.written.clear();
                    check(!Theme.commitCodexPixelThemeMigration(true,main,themes,"Arctic Blue",false),"第二次启动再次迁移");
                    check("Arctic Blue".equals(main.getString("theme",null))&&main.written.isEmpty()&&themes.written.isEmpty(),"已迁移后的普通主题被覆写");
                    SharedPreferences manual=new SharedPreferences();
                    manual.values.put("theme","Dark");
                    manual.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_NONE);
                    SharedPreferences manualThemes=new SharedPreferences();
                    check(Theme.commitCodexPixelThemeMigration(true,manual,manualThemes,null,false)&&"Night".equals(manual.getString("theme",null)),"NONE 且原主题明确深色时没有保持 Night");
                    SharedPreferences named=new SharedPreferences();
                    named.values.put("theme","Dark Blue");
                    named.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_SCHEDULED);
                    check(Theme.commitCodexPixelThemeMigration(true,named,new SharedPreferences(),"Dark Blue",true)&&"Day".equals(named.getString("theme",null))&&"Night".equals(named.getString("nighttheme",null)),"定时模式把当前主题直接写成了 Night");
                    SharedPreferences ocean=new SharedPreferences();
                    ocean.values.put("theme","remote7");
                    ocean.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_NONE);
                    check(Theme.commitCodexPixelThemeMigration(true,ocean,new SharedPreferences(),"remote7",true)&&"Night".equals(ocean.getString("theme",null)),"已解析的深色主题没有当作手动深色");
                    SharedPreferences plain=new SharedPreferences();
                    plain.values.put("theme","Blue");
                    check(!Theme.commitCodexPixelThemeMigration(false,plain,new SharedPreferences(),"Blue",false)&&"Blue".equals(plain.getString("theme",null)),"关闭 Codex 后仍迁移主题");
                    Build.VERSION.SDK_INT=28;
                    SharedPreferences old=new SharedPreferences();
                    old.values.put("theme","Night");
                    check(Theme.commitCodexPixelThemeMigration(true,old,new SharedPreferences(),"Night",true)&&"Night".equals(old.getString("theme",null)),"旧系统缺省关闭自动夜间时没有保持深色");
                }
                static SharedPreferences restart(SharedPreferences source){
                    SharedPreferences next=new SharedPreferences();
                    next.values.putAll(source.disk);
                    next.disk.putAll(source.disk);
                    return next;
                }
                static void seed(SharedPreferences prefs){prefs.disk.clear();prefs.disk.putAll(prefs.values);}
                /** 第一处主题记忆或第二处 main 落盘失败都不能留下成功标记；重启后原入口可完成，且只迁移一次。 */
                static void storeFailures(){
                    Build.VERSION.SDK_INT=29;
                    SharedPreferences main=new SharedPreferences();
                    SharedPreferences themes=new SharedPreferences();
                    main.values.put("theme","Blue");
                    main.values.put("nighttheme","Dark Blue");
                    main.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_SYSTEM);
                    main.values.put("autoNightDayStartTime",22*60);
                    themes.values.put("lastDayTheme","Arctic Blue");
                    themes.values.put("lastDarkTheme","Dark Blue");
                    seed(main);seed(themes);
                    themes.failNextCommit=true;
                    check(!Theme.commitCodexPixelThemeMigration(true,main,themes,"Blue",false),"主题记忆键落盘失败仍宣告迁移成功");
                    check("Blue".equals(main.getString("theme",null))&&!main.getBoolean("codexPixelDayNightMigrated",false),"第一处失败后同进程写成了新主题或成功标记");
                    check("Arctic Blue".equals(themes.getString("lastDayTheme",null))&&"Dark Blue".equals(themes.getString("lastDarkTheme",null)),"第一处失败后同进程把未落盘的记忆键当成成功");
                    check(main.getInt("selectedAutoNightType",-1)==Theme.AUTO_NIGHT_TYPE_SYSTEM&&!main.disk.containsKey("codexPixelDayNightMigrated"),"第一处失败写入了标记或自动夜间配置");
                    SharedPreferences mainRestart=restart(main);
                    SharedPreferences themeRestart=restart(themes);
                    check("Arctic Blue".equals(themeRestart.getString("lastDayTheme",null))&&"Blue".equals(mainRestart.getString("theme",null)),"第一处失败重启后磁盘不再是旧记忆键");
                    check(Theme.commitCodexPixelThemeMigration(true,mainRestart,themeRestart,"Blue",false),"第一处失败重启后不能沿原入口重试");
                    check("Day".equals(themeRestart.disk.get("lastDayTheme"))&&"Night".equals(themeRestart.disk.get("lastDarkTheme")),"重试没有落盘 Day/Night 记忆键");
                    check(Boolean.TRUE.equals(mainRestart.disk.get("codexPixelDayNightMigrated"))&&"Day".equals(mainRestart.disk.get("theme"))&&"Night".equals(mainRestart.disk.get("nighttheme")),"重试没有最后落盘主题、夜键和标记");
                    check(!Theme.commitCodexPixelThemeMigration(true,mainRestart,themeRestart,"Day",false),"重试成功后再次迁移");
                    main=new SharedPreferences();themes=new SharedPreferences();
                    main.values.put("theme","Blue");
                    main.values.put("nighttheme","Dark Blue");
                    main.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_NONE);
                    main.values.put("autoNightDayStartTime",8*60);
                    themes.values.put("lastDayTheme","Blue");
                    themes.values.put("lastDarkTheme","Dark Blue");
                    seed(main);seed(themes);
                    main.failNextCommit=true;
                    check(!Theme.commitCodexPixelThemeMigration(true,main,themes,null,false),"main 落盘失败仍宣告迁移成功");
                    check("Blue".equals(main.getString("theme",null))&&"Dark Blue".equals(main.getString("nighttheme",null))&&!main.getBoolean("codexPixelDayNightMigrated",false),"第二处失败后同进程把内存标记当成已迁移");
                    check("Day".equals(themes.disk.get("lastDayTheme"))&&"Night".equals(themes.disk.get("lastDarkTheme")),"记忆键已经成功时被失败回滚");
                    check(!main.disk.containsKey("codexPixelDayNightMigrated")&&"Blue".equals(main.disk.get("theme")),"第二处失败仍把标记写入磁盘");
                    check(main.getInt("autoNightDayStartTime",-1)==8*60&&!main.written.contains("selectedAutoNightType")&&!main.written.contains("autoNightDayStartTime"),"失败路径扩写了自动夜间配置");
                    mainRestart=restart(main);themeRestart=restart(themes);
                    check("Day".equals(themeRestart.getString("lastDayTheme",null))&&"Blue".equals(mainRestart.getString("theme",null))&&!mainRestart.getBoolean("codexPixelDayNightMigrated",false),"第二处失败重启后不能保留记忆键并重试标记");
                    check(Theme.commitCodexPixelThemeMigration(true,mainRestart,themeRestart,"Blue",false)&&Boolean.TRUE.equals(mainRestart.disk.get("codexPixelDayNightMigrated")),"第二处失败重启后重试没有成功");
                    check(!Theme.commitCodexPixelThemeMigration(true,mainRestart,themeRestart,"Day",false),"重启重试成功后又迁移一次");
                }
                static void palette(){
                    SparseIntArray day=new SparseIntArray();
                    Theme.copyCodexPixelPalette(day,"Day",false,true);
                    SparseIntArray light=com.butang.codextop.CodexPixelPalette.light();
                    check(day.size()==light.size()&&day.get(Theme.key_chat_wallpaper)==light.get(Theme.key_chat_wallpaper),"Day 没有复制浅色表");
                    SparseIntArray night=new SparseIntArray();
                    Theme.copyCodexPixelPalette(night,"Night",true,true);
                    SparseIntArray dark=com.butang.codextop.CodexPixelPalette.dark();
                    check(night.size()==dark.size()&&night.get(Theme.key_chat_wallpaper)==dark.get(Theme.key_chat_wallpaper),"Night 没有复制深色表");
                    check(day.get(Theme.key_chat_wallpaper)!=night.get(Theme.key_chat_wallpaper),"日夜壁纸色被复制成同一张表");
                    SparseIntArray blue=new SparseIntArray();
                    Theme.copyCodexPixelPalette(blue,"Blue",false,true);
                    Theme.copyCodexPixelPalette(blue,"Day",false,false);
                    Theme.copyCodexPixelPalette(blue,null,true,true);
                    check(blue.size()==0,"普通主题或关闭 Codex 时写入了像素色");
                    com.butang.codextop.CodexPixelPalette.Style ls=com.butang.codextop.CodexPixelPalette.metadata(2);
                    com.butang.codextop.CodexPixelPalette.Style ds=com.butang.codextop.CodexPixelPalette.metadata(1);
                    check(light.get(Theme.key_chats_unreadCounterText)==ls.onAccentFill&&dark.get(Theme.key_chats_unreadCounterText)==ds.onAccentFill,"填充上的符号没有跟随独立 onAccentFill");
                    check(light.get(Theme.key_chat_wallpaper)==ls.wallpaper&&dark.get(Theme.key_chat_wallpaper)==ds.wallpaper,"聊天壁纸没有使用各自纸面色");
                    check(light.get(Theme.key_windowBackgroundWhiteGrayText)==ls.secondary&&dark.get(Theme.key_windowBackgroundWhiteGrayText)==ds.secondary,"全局辅助色被聊天覆盖");
                    check(light.get(Theme.key_windowBackgroundWhiteBlueText)==ls.accentText&&dark.get(Theme.key_windowBackgroundWhiteBlueText)==ds.accentText,"全局强调色被聊天覆盖");
                }

                /** 旧 Day 资源的白和浅蓝前景必须被收发独立覆盖，并在选中气泡与回复叠色上达到 4.5。 */
                static void foreground(){
                    SparseIntArray day=new SparseIntArray();
                    seedDayChrome(day);
                    Theme.copyCodexPixelPalette(day,"Day",false,true);
                    assertForeground(day,com.butang.codextop.CodexPixelPalette.metadata(2));
                    SparseIntArray night=new SparseIntArray();
                    seedDayChrome(night);
                    Theme.copyCodexPixelPalette(night,"Night",true,true);
                    assertForeground(night,com.butang.codextop.CodexPixelPalette.metadata(1));
                    SparseIntArray plain=new SparseIntArray();
                    seedDayChrome(plain);
                    Theme.copyCodexPixelPalette(plain,"Blue",false,true);
                    Theme.copyCodexPixelPalette(plain,"Day",false,false);
                    check(plain.get(Theme.key_chat_messageLinkOut)==DAY_WHITE&&plain.get(Theme.key_chat_messageLinkIn)==DAY_LINK_IN&&plain.get(Theme.key_chat_outFileInfoText)==DAY_PALE,"普通蓝主题或关闭 Codex 写入了新的前景键");
                }
                static final int DAY_WHITE=0xFFFFFFFF,DAY_PALE=0xFFC7E6FF,DAY_LINK_IN=0xFF127ACA;
                static void seedDayChrome(SparseIntArray colors){
                    int[] white=new int[]{Theme.key_chat_messageLinkOut,Theme.key_chat_outForwardedNameText,Theme.key_chat_outSiteNameText,Theme.key_chat_outReplyLine,Theme.key_chat_outReplyLine2,Theme.key_chat_outReplyNameText,Theme.key_chat_outReplyMessageText,Theme.key_chat_outFileNameText};
                    for(int key:white)colors.put(key,DAY_WHITE);
                    int[] pale=new int[]{Theme.key_chat_outReplyMediaMessageText,Theme.key_chat_outReplyMediaMessageSelectedText,Theme.key_chat_outFileInfoText,Theme.key_chat_outFileInfoSelectedText};
                    for(int key:pale)colors.put(key,DAY_PALE);
                    colors.put(Theme.key_chat_messageLinkIn,DAY_LINK_IN);
                }
                /** 分别核对收发正文、链接和说明；浅纸发泡不得沿用暗页面的浅色字。 */
                static void assertForeground(SparseIntArray colors,com.butang.codextop.CodexPixelPalette.Style style){
                    int[] inInk={Theme.key_chat_messageTextIn,Theme.key_chat_inReplyNameText,Theme.key_chat_inReplyMessageText,
                        Theme.key_chat_inReplyMediaMessageText,Theme.key_chat_inReplyMediaMessageSelectedText,Theme.key_chat_inSiteNameText,
                        Theme.key_chat_inFileNameText,Theme.key_chat_inContactNameText,Theme.key_chat_inAudioTitleText};
                    int[] outInk={Theme.key_chat_messageTextOut,Theme.key_chat_outReplyNameText,Theme.key_chat_outReplyMessageText,
                        Theme.key_chat_outReplyMediaMessageText,Theme.key_chat_outReplyMediaMessageSelectedText,Theme.key_chat_outSiteNameText,
                        Theme.key_chat_outFileNameText,Theme.key_chat_outContactNameText,Theme.key_chat_outAudioTitleText};
                    int[] inAux={Theme.key_chat_inTimeText,Theme.key_chat_inTimeSelectedText,Theme.key_chat_inFileInfoText,Theme.key_chat_inFileInfoSelectedText,
                        Theme.key_chat_inContactPhoneText,Theme.key_chat_inContactPhoneSelectedText,Theme.key_chat_inAudioPerformerText,
                        Theme.key_chat_inAudioPerformerSelectedText,Theme.key_chat_inAudioDurationText,Theme.key_chat_inAudioDurationSelectedText,
                        Theme.key_chat_inVenueInfoText,Theme.key_chat_inVenueInfoSelectedText,Theme.key_chat_inAdminText,Theme.key_chat_inAdminSelectedText,
                        Theme.key_chat_inViews,Theme.key_chat_inViewsSelected,Theme.key_chat_inSentClock,Theme.key_chat_inSentClockSelected};
                    int[] outAux={Theme.key_chat_outTimeText,Theme.key_chat_outTimeSelectedText,Theme.key_chat_outFileInfoText,Theme.key_chat_outFileInfoSelectedText,
                        Theme.key_chat_outContactPhoneText,Theme.key_chat_outContactPhoneSelectedText,Theme.key_chat_outAudioPerformerText,
                        Theme.key_chat_outAudioPerformerSelectedText,Theme.key_chat_outAudioDurationText,Theme.key_chat_outAudioDurationSelectedText,
                        Theme.key_chat_outVenueInfoText,Theme.key_chat_outVenueInfoSelectedText,Theme.key_chat_outAdminText,Theme.key_chat_outAdminSelectedText,
                        Theme.key_chat_outViews,Theme.key_chat_outViewsSelected,Theme.key_chat_outSentClock,Theme.key_chat_outSentClockSelected};
                    int[] inLink={Theme.key_chat_messageLinkIn,Theme.key_chat_inForwardedNameText,Theme.key_chat_inViaBotNameText,Theme.key_chat_inPsaNameText,
                        Theme.key_chat_inReplyLine,Theme.key_chat_inInstant,Theme.key_chat_inInstantSelected,Theme.key_chat_inPreviewInstantText};
                    int[] outLink={Theme.key_chat_messageLinkOut,Theme.key_chat_outForwardedNameText,Theme.key_chat_outViaBotNameText,Theme.key_chat_outPsaNameText,
                        Theme.key_chat_outReplyLine,Theme.key_chat_outReplyLine2,Theme.key_chat_outInstant,Theme.key_chat_outInstantSelected,
                        Theme.key_chat_outPreviewInstantText,Theme.key_chat_outSentCheck,Theme.key_chat_outSentCheckRead,
                        Theme.key_chat_outSentCheckSelected,Theme.key_chat_outSentCheckReadSelected};
                    int in=colors.get(Theme.key_chat_inBubble),inSel=colors.get(Theme.key_chat_inBubbleSelected);
                    int out=colors.get(Theme.key_chat_outBubble),outSel=colors.get(Theme.key_chat_outBubbleSelected);
                    int[] inSheets={in,inSel,replyBackground(style.incomingLink,in,style.dark),replyBackground(style.incomingLink,inSel,style.dark)};
                    int[] outSheets={out,outSel,replyBackground(style.outgoingLink,out,style.dark),replyBackground(style.outgoingLink,outSel,style.dark)};
                    group(colors,inInk,style.incomingInk,inSheets,"收到正文");
                    group(colors,outInk,style.outgoingInk,outSheets,"发出正文");
                    group(colors,inAux,style.incomingSecondary,inSheets,"收到说明");
                    group(colors,outAux,style.outgoingSecondary,outSheets,"发出说明");
                    group(colors,inLink,style.incomingLink,inSheets,"收到链接");
                    group(colors,outLink,style.outgoingLink,outSheets,"发出链接");
                }
                /** 每个真实颜色键既要覆盖旧资源，也要在普通、选中和回复叠色背景上可读。 */
                static void group(SparseIntArray colors,int[] keys,int expected,int[] sheets,String title){
                    for(int key:keys){
                        check(colors.indexOfKey(key)>=0&&colors.get(key)==expected,title+"未独立覆盖键 "+key);
                        for(int sheet:sheets)contrast(colors.get(key),sheet,title);
                    }
                }
                /** 五个固定 ID、明暗身份、回退、可变数组隔离和完整页面/气泡/填充覆盖。 */
                static void styles(){
                    int[] ids={1,2,3,4,6};
                    boolean[] dark={true,false,true,false,true};
                    com.butang.codextop.CodexPixelPalette.Style[] styles=com.butang.codextop.CodexPixelPalette.styles();
                    check(styles.length==5,"取消的 05 被重新实现");
                    SparseIntArray common=com.butang.codextop.CodexPixelPalette.light();
                    for(int i=0;i<ids.length;i++){
                        com.butang.codextop.CodexPixelPalette.Style style=styles[i];
                        check(style.id==ids[i]&&style.dark==dark[i],"固定 ID 或明暗身份变化");
                        check(com.butang.codextop.CodexPixelPalette.resolveStyle(style.id,!style.dark)==style.id,"有效 ID 被回退侧改写");
                        check(com.butang.codextop.CodexPixelPalette.metadata(style.id)==style,"metadata 不稳定");
                        SparseIntArray colors=com.butang.codextop.CodexPixelPalette.byStyle(style.id);
                        check(colors.size()==common.size(),"样式缺少键");
                        for(int k=0;k<common.size();k++)check(colors.indexOfKey(common.keyAt(k))>=0,"样式缺少共同颜色键");
                        check(colors.get(Theme.key_windowBackgroundWhite)==style.page&&colors.get(Theme.key_dialogBackground)==style.surface,"页面层面未随样式");
                        check(colors.get(Theme.key_chat_inBubble)==style.incoming&&colors.get(Theme.key_chat_outBubble)==style.outgoing
                            &&colors.get(Theme.key_chat_wallpaper)==style.wallpaper,"收发/壁纸未随样式");
                        contrast(style.ink,style.page,"页面正文");contrast(style.ink,style.surface,"层面正文");
                        contrast(style.secondary,style.page,"页面辅助");contrast(style.secondary,style.surface,"层面辅助");
                        contrast(style.accentText,style.page,"页面强调");contrast(style.accentText,style.surface,"层面强调");
                        contrast(colors.get(Theme.key_chats_unreadCounterText),style.accentFill,"角标符号");
                        contrast(colors.get(Theme.key_chats_actionIcon),style.accentFill,"浮动按钮符号");
                        contrast(colors.get(Theme.key_chat_messagePanelSend),style.surface,"发送图标");
                        contrast(colors.get(Theme.key_radioBackgroundChecked),style.surface,"单选圆环");
                        contrast(colors.get(Theme.key_dialogRadioBackgroundChecked),style.surface,"弹层单选圆环");
                        contrast(colors.get(Theme.key_featuredStickers_buttonText),colors.get(Theme.key_featuredStickers_addButtonPressed),"按下填充符号");
                        contrast(colors.get(Theme.key_chat_sentErrorIcon),colors.get(Theme.key_chat_sentError),"错误符号");
                        contrast(colors.get(Theme.key_chats_sentErrorIcon),colors.get(Theme.key_chats_sentError),"列表错误符号");
                        assertForeground(colors,style);
                        int[] zero={Theme.key_chat_wallpaper_gradient_to1,Theme.key_chat_wallpaper_gradient_to2,Theme.key_chat_wallpaper_gradient_to3,
                            Theme.key_chat_outBubbleGradient1,Theme.key_chat_outBubbleGradient2,Theme.key_chat_outBubbleGradient3,Theme.key_chat_outBubbleGradientAnimated};
                        for(int key:zero)check(colors.get(key)==0,"残留渐变");
                        colors.put(Theme.key_chat_outBubble,0);
                        check(com.butang.codextop.CodexPixelPalette.byStyle(style.id).get(Theme.key_chat_outBubble)==style.outgoing,"返回色表改坏静态样式");
                    }
                    styles[0]=null;
                    check(com.butang.codextop.CodexPixelPalette.styles()[0].id==1,"返回样式数组改坏静态列表");
                    for(int id:ids){
                        boolean isDark=com.butang.codextop.CodexPixelPalette.metadata(id).dark;
                        check(Theme.codexPixelStyleForSlot(id,isDark)==id,"合法同侧样式被重置");
                        check(Theme.codexPixelStyleForSlot(id,!isDark)==(!isDark?1:2),"合法错侧样式冒充槽位明暗");
                    }
                    int[] invalid={0,5,-1,Integer.MIN_VALUE,Integer.MAX_VALUE};
                    for(int id:invalid){
                        check(com.butang.codextop.CodexPixelPalette.resolveStyle(id,false)==2&&com.butang.codextop.CodexPixelPalette.resolveStyle(id,true)==1,"非法/取消 ID 未按侧回退");
                        check(com.butang.codextop.CodexPixelPalette.metadata(id,false).id==2&&com.butang.codextop.CodexPixelPalette.metadata(id,true).id==1,"metadata 回退不一致");
                        check(com.butang.codextop.CodexPixelPalette.byStyle(id,true).get(Theme.key_chat_wallpaper)==com.butang.codextop.CodexPixelPalette.metadata(1).wallpaper,"byStyle 回退不一致");
                    }
                }
                /** 与 ReplyMessageLine 相同：线条色乘 10% 浅色或 12% 深色后盖在气泡上。 */
                static int replyBackground(int line,int bubble,boolean dark){
                    int tint=multAlpha(line|0xFF000000,dark?0.12f:0.10f);
                    float sa=((tint>>>24)&255)/255f;
                    int r=(int)(((tint>>>16)&255)*sa+((bubble>>>16)&255)*(1f-sa));
                    int g=(int)(((tint>>>8)&255)*sa+((bubble>>>8)&255)*(1f-sa));
                    int b=(int)((tint&255)*sa+(bubble&255)*(1f-sa));
                    return 0xFF000000|(r<<16)|(g<<8)|b;
                }
                static int multAlpha(int color,float multiply){
                    if(multiply==1f)return color;
                    int alpha=(int)(((color>>>24)&255)*multiply);
                    if(alpha<0)alpha=0;
                    if(alpha>255)alpha=255;
                    return (alpha<<24)|(color&0x00FFFFFF);
                }
                static void contrast(int foreground,int background,String where){
                    double ratio=contrastRatio(foreground,background);
                    check(ratio>=4.5,where+" 对比 "+ratio+" 低于 4.5");
                }
                static double contrastRatio(int foreground,int background){
                    double hi=luminance(foreground),lo=luminance(background);
                    if(hi<lo){double swap=hi;hi=lo;lo=swap;}
                    return (hi+0.05)/(lo+0.05);
                }
                static double luminance(int color){
                    return 0.2126*channel((color>>>16)&255)+0.7152*channel((color>>>8)&255)+0.0722*channel(color&255);
                }
                static double channel(int value){
                    double c=value/255.0;
                    return c<=0.04045?c/12.92:Math.pow((c+0.055)/1.055,2.4);
                }
                static void wallpaper(){
                    SparseIntArray colors=new SparseIntArray();
                    int wash=com.butang.codextop.CodexPixelPalette.light().get(Theme.key_chat_wallpaper);
                    colors.put(Theme.key_chat_wallpaper,wash);
                    Theme.BackgroundDrawableSettings settings=Theme.codexPixelWallpaper("Day",colors,true);
                    check(settings!=null&&settings.wallpaper instanceof ColorDrawable&&((ColorDrawable)settings.wallpaper).getColor()==wash,"平色壁纸没有使用当前壁纸键");
                    check(Boolean.FALSE.equals(settings.isWallpaperMotion)&&Boolean.FALSE.equals(settings.isPatternWallpaper)&&Boolean.TRUE.equals(settings.isCustomTheme)&&settings.themedWallpaper==null,"平色壁纸标志仍像图案或动态壁纸");
                    check(Theme.codexPixelWallpaper("Blue",colors,true)==null&&Theme.codexPixelWallpaper("Day",new SparseIntArray(),true)==null&&Theme.codexPixelWallpaper("Night",colors,false)==null,"普通主题或缺少壁纸色时仍返回平涂");
                    colors.put(Theme.key_chat_wallpaper,com.butang.codextop.CodexPixelPalette.dark().get(Theme.key_chat_wallpaper));
                    Theme.BackgroundDrawableSettings dark=Theme.codexPixelWallpaper("Night",colors,true);
                    check(dark!=null&&((ColorDrawable)dark.wallpaper).getColor()==colors.get(Theme.key_chat_wallpaper),"Night 平色壁纸没有使用深色壁纸键");
                }
                /** 平台边界计数证明小 tile 复用、两项淘汰、密度归一和重复 draw 无新对象。 */
                static void tiles(){
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),3f);
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),3f);
                    int before=android.graphics.Bitmap.created;
                    com.butang.codextop.CodexPixelWallpaper terminal=new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),1f);
                    int first=android.graphics.Bitmap.created;
                    check(first==before+1,"终端纹理未构造一次");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),1f);
                    check(android.graphics.Bitmap.created==first,"相同纹理重复分配");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),1.01f);
                    check(android.graphics.Bitmap.created==first+1,"不同像素密度误复用");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),1f);
                    check(android.graphics.Bitmap.created==first+1,"第二缓存槽没有复用");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),1f);
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),1.01f);
                    check(android.graphics.Bitmap.created==first+3,"两项缓存没有淘汰最旧纹理");
                    int paper=android.graphics.Bitmap.created;
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(2),1f);
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(4),1f);
                    check(android.graphics.Bitmap.created==paper,"纯纸面分配 bitmap");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),1f);
                    int normalized=android.graphics.Bitmap.created;
                    float[] invalid={Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,0f,-1f};
                    for(float density:invalid)new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),density);
                    check(android.graphics.Bitmap.created==normalized,"异常密度没有安全归一");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),Float.MAX_VALUE);
                    int capped=android.graphics.Bitmap.created;
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),8f);
                    check(android.graphics.Bitmap.created==capped,"大密度没有统一至有界像素");
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),Float.MIN_VALUE);
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(6),0.01f);
                    check(android.graphics.Bitmap.created==capped+1,"极小正密度没有归一至相同有限尺寸");
                    int objects=android.graphics.Paint.created,bitmaps=android.graphics.Bitmap.created;
                    android.graphics.Canvas canvas=new android.graphics.Canvas(null);
                    for(int i=0;i<100;i++)terminal.draw(canvas);
                    check(android.graphics.Bitmap.created==bitmaps&&android.graphics.Paint.created==objects,"draw 创建了 bitmap 或 paint");
                    // 缓存已经淘汰该 terminal，但原 Shader 仍持有有效像素，不能通过 recycle 清理。
                    terminal.draw(canvas);
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1),1f);
                    int explicit=android.graphics.Bitmap.created;
                    new com.butang.codextop.CodexPixelWallpaper(com.butang.codextop.CodexPixelPalette.metadata(1).wallpaper,1f);
                    check(android.graphics.Bitmap.created==explicit+1,"旧方点纸面被底色猜测成新样式");
                }
                /** 编译实际选择方法，检查保存失败、关闭入口、非法 ID、两侧记忆和真实系统 uiMode 分支。 */
                static void appearance(){
                    Theme.resetBoundary();
                    org.telegram.messenger.MessagesController.main=new SharedPreferences();
                    SharedPreferences prefs=org.telegram.messenger.MessagesController.main;
                    prefs.values.put("theme","Day");prefs.values.put("nighttheme","Night");
                    prefs.values.put("selectedAutoNightType",Theme.AUTO_NIGHT_TYPE_SYSTEM);prefs.values.put("unrelated","keep");seed(prefs);
                    for(int id:new int[]{5,-1,Integer.MAX_VALUE})check(!Theme.selectCodexPixelAppearance(id)&&Theme.applied==0&&Theme.checked==0,"非法样式仍应用主题");
                    com.butang.codextop.CodexRuntime.active=false;
                    check(!Theme.selectCodexPixelAppearance(4)&&Theme.applied==0,"非 Codex 入口仍应用主题");
                    com.butang.codextop.CodexRuntime.active=true;
                    java.util.Map<String,Object> original=new java.util.HashMap<String,Object>(prefs.values);
                    prefs.failNextCommit=true;
                    check(!Theme.selectCodexPixelAppearance(4),"保存失败仍宣告选择成功");
                    check(prefs.values.equals(original)&&prefs.disk.equals(original),"保存失败没有回滚磁盘/同进程偏好");
                    check(Theme.applied==0&&Theme.checked==0&&Theme.getCodexPixelStyle().id==2,"保存失败改变显示状态或触发回调");
                    check(Theme.selectCodexPixelAppearance(4)&&Theme.getCodexPixelStyle().id==4&&!Theme.lastSave,"04未本地应用或进入 save=true");
                    check(prefs.getInt("codexPixelLightStyle",-1)==4&&prefs.getInt("codexPixelDarkStyle",-1)==1,"浅色选择改坏暗槽");
                    check("Day".equals(prefs.disk.get("theme"))&&prefs.getInt("selectedAutoNightType",-1)==Theme.AUTO_NIGHT_TYPE_NONE,"浅色固定没有一致落盘");
                    check(Theme.selectCodexPixelAppearance(6)&&Theme.getCodexPixelStyle().id==6&&!Theme.lastSave,"06未本地应用或进入 save=true");
                    check(prefs.getInt("codexPixelLightStyle",-1)==4&&prefs.getInt("codexPixelDarkStyle",-1)==6&&"Night".equals(prefs.disk.get("theme")),"暗色选择改坏浅槽或重启主题");
                    SharedPreferences afterDark=restart(prefs);
                    check(afterDark.getInt("codexPixelLightStyle",-1)==4&&afterDark.getInt("codexPixelDarkStyle",-1)==6&&"Night".equals(afterDark.getString("theme",null)),"重启磁盘未保留两个样式");
                    Theme.resetBoundary();
                    Theme.restoreSlots(afterDark);
                    Theme.observeNight(true);
                    check(Theme.getCodexPixelStyle().id==6,"实际启动槽表达式未恢复暗色样式");
                    Theme.observeNight(false);
                    check(Theme.getCodexPixelStyle().id==4,"实际启动槽表达式未恢复浅色样式");
                    SharedPreferences corrupted=new SharedPreferences();
                    corrupted.values.put("codexPixelLightStyle",3);corrupted.values.put("codexPixelDarkStyle",4);
                    Theme.restoreSlots(corrupted);
                    check(Theme.getCodexPixelStyle().id==2,"错侧浅槽没有回有效浅色");
                    Theme.observeNight(true);
                    check(Theme.getCodexPixelStyle().id==1,"错侧暗槽没有回有效暗色");
                    Theme.restoreSlots(afterDark);
                    org.telegram.messenger.ApplicationLoader.applicationContext.resources.configuration.uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    check(Theme.selectCodexPixelAppearance(0)&&Theme.lastNight&&Theme.getCodexPixelStyle().id==6&&!Theme.lastSave,"跟随系统暗色没有采用暗槽");
                    check("Day".equals(prefs.disk.get("theme"))&&"Night".equals(prefs.disk.get("nighttheme"))&&prefs.getInt("selectedAutoNightType",-1)==Theme.AUTO_NIGHT_TYPE_SYSTEM,"系统模式重启字段不一致");
                    check("跟随系统".equals(Theme.getCodexPixelAppearanceLabel()),"系统模式标签丢失");
                    org.telegram.messenger.ApplicationLoader.applicationContext.resources.configuration.uiMode=android.content.res.Configuration.UI_MODE_NIGHT_NO;
                    check(Theme.selectCodexPixelAppearance(0)&&!Theme.lastNight&&Theme.getCodexPixelStyle().id==4,"跟随系统浅色没有采用浅槽");
                    for(int id:new int[]{1,2,3,4,6}){
                        check(Theme.selectCodexPixelAppearance(id)&&Theme.getCodexPixelStyle().id==id&&!Theme.lastSave,"固定样式选择失败");
                        com.butang.codextop.CodexPixelPalette.Style style=com.butang.codextop.CodexPixelPalette.metadata(id);
                        SparseIntArray colors=new SparseIntArray();
                        Theme.copyCodexPixelPalette(colors,style.dark?"Night":"Day",style.dark,true);
                        check(colors.get(Theme.key_chat_outBubble)==style.outgoing,"实际 Theme 未复制所选发泡");
                        Theme.BackgroundDrawableSettings wallpaper=Theme.codexPixelWallpaper(style.dark?"Night":"Day",colors,true);
                        check(((ColorDrawable)wallpaper.wallpaper).getColor()==style.wallpaper,"实际 Theme 壁纸未采用同一所选样式");
                    }
                    check("keep".equals(prefs.values.get("unrelated")),"外观选择扩写无关偏好");
                }
                /** 三条实际 RPC 保存守卫都排除自有 Day/Night，正控制仍能进入原远端路径。 */
                static void remoteSave(){
                    for(int branch=0;branch<3;branch++){
                        Theme.resetBoundary();
                        for(String name:new String[]{"Day","Night"}){
                            Theme.guard(branch,new Theme.ThemeInfo(name,"Night".equals(name)),true,"Night".equals(name));
                            check(org.telegram.messenger.MessagesController.saved==0,"自有 Day/Night 分支 "+branch+"触发远端保存");
                        }
                        Theme.guard(branch,new Theme.ThemeInfo("Blue",false),true,false);
                        check(org.telegram.messenger.MessagesController.saved==1,"其他主题分支被禁用");
                        com.butang.codextop.CodexRuntime.active=false;
                        Theme.guard(branch,new Theme.ThemeInfo("Day",false),true,false);
                        Theme.guard(branch,new Theme.ThemeInfo("Night",true),true,true);
                        check(org.telegram.messenger.MessagesController.saved==3,"非 Codex 原 Day/Night 保存被禁用");
                        Theme.guard(branch,new Theme.ThemeInfo("Blue",false),false,false);
                        check(org.telegram.messenger.MessagesController.saved==3,"save=false 仍保存");
                    }
                    Theme.resetBoundary();
                    Theme.coldApply(new Theme.ThemeInfo("Day",false),1);
                    Theme.coldApply(new Theme.ThemeInfo("Night",true),2);
                    check(org.telegram.messenger.MessagesController.saved==0&&!Theme.lastSave,"实际冷恢复调用触发远端保存");
                    for(int id:new int[]{1,2,3,4,6,0})check(Theme.selectCodexPixelAppearance(id),"本地样式选择失败");
                    check(org.telegram.messenger.MessagesController.saved==0,"实际本地选择触发远端保存");
                    // 原自动切换会先发通知，再释放 switchingNightTheme；延后交付时不能依赖该瞬时锁阻止 RPC。
                    Theme.resetBoundary();
                    Theme.applyDayNightThemeMaybe(true);
                    check(Theme.NotificationCenter.posts==1&&Theme.NotificationCenter.pending.isDark(),"实际自动暗色分支没有发出 Night");
                    Theme.deliverTheme();
                    check(Theme.lastSave&&org.telegram.messenger.MessagesController.saved==0,"自动暗色 save=true 的延后接收仍触发 RPC");
                    Theme.applyDayNightThemeMaybe(false);
                    check(Theme.NotificationCenter.posts==2&&!Theme.NotificationCenter.pending.isDark(),"实际自动浅色分支没有发出 Day");
                    Theme.deliverTheme();
                    check(Theme.lastSave&&org.telegram.messenger.MessagesController.saved==0,"自动浅色 save=true 的延后接收仍触发 RPC");
                }
                /** 实际 Launch 无位图分支→导航重载→ActionBarLayout，覆盖同明暗及过渡结束后交付。 */
                static void manualHost(){
                    Theme.resetBoundary();
                    org.telegram.messenger.MessagesController.main=new SharedPreferences();
                    SharedPreferences prefs=org.telegram.messenger.MessagesController.main;
                    prefs.values.put("unrelated","keep");seed(prefs);
                    org.telegram.messenger.AndroidUtilities.tablet=false;
                    ManualThemeHost.Launch launch=new ManualThemeHost.Launch();
                    launch.actionBarLayout=new ManualThemeHost();
                    ManualThemeHost host=launch.actionBarLayout;
                    int posts=0;
                    for(int id:new int[]{1,3,6,2,4}){
                        int backgroundUpdates=host.background.updates,titleUpdates=host.title.updates,clears=host.hiddenClears();
                        check(Theme.selectCodexPixelAppearance(id),"手动宿主选择失败");
                        check(Theme.NotificationCenter.posts==++posts&&Theme.NotificationCenter.args!=null,"手动选择没有通知原宿主");
                        Object[] args=Theme.NotificationCenter.args;
                        com.butang.codextop.CodexPixelPalette.Style style=com.butang.codextop.CodexPixelPalette.metadata(id);
                        check(args.length==4&&((Theme.ThemeInfo)args[0]).isDark()==style.dark&&(Boolean)args[1]==style.dark
                                &&args[2]==null&&(Integer)args[3]==-1,"手动通知参数没有采用原夜间标志或进入了位图/强调色路径");
                        java.util.Map<String,Object> values=new java.util.HashMap<String,Object>(prefs.values),disk=new java.util.HashMap<String,Object>(prefs.disk);
                        Theme.ThemeInfo day=Theme.dayBoundary();
                        // 平台通知边界保存参数，方法已经返回再运行真实宿主接收，不能依赖临时 switchingNightTheme。
                        launch.deliver(args);
                        check(Theme.lastSave&&Theme.lastNight==style.dark&&Theme.getCodexPixelStyle().id==id,"宿主应用丢失固定明暗或当前样式");
                        check(prefs.values.equals(values)&&prefs.disk.equals(disk)&&Theme.dayBoundary()==day,"延后宿主应用改变了选择偏好或固定日间身份");
                        check(host.background.updates>backgroundUpdates&&host.title.updates>titleUpdates
                                &&host.background.styleId==id&&host.title.styleId==id,"同明暗宿主没有刷新已绑定背景和标题");
                        check(host.background.color==com.butang.codextop.CodexPixelPalette.byStyle(id).get(Theme.key_windowBackgroundWhite)
                                &&host.title.color==style.ink,"宿主刷新读取了旧色表");
                        check(host.hiddenClears()==clears+1&&host.fragmentsStack.get(0).parents==host.hiddenClears(),"返回页面没有清理并重新挂到原宿主");
                        check(Theme.accentsSaved==0&&org.telegram.messenger.MessagesController.saved==0,"手动宿主进入强调色或 RPC 保存");
                    }
                    for(boolean dark:new boolean[]{true,false}){
                        org.telegram.messenger.ApplicationLoader.applicationContext.resources.configuration.uiMode=dark?android.content.res.Configuration.UI_MODE_NIGHT_YES:android.content.res.Configuration.UI_MODE_NIGHT_NO;
                        check(Theme.selectCodexPixelAppearance(0),"系统宿主选择失败");
                        java.util.Map<String,Object> values=new java.util.HashMap<String,Object>(prefs.values),disk=new java.util.HashMap<String,Object>(prefs.disk);
                        int style=dark?6:4;
                        launch.deliver(Theme.NotificationCenter.args);
                        check(Theme.lastNight==dark&&Theme.getCodexPixelStyle().id==style&&host.background.styleId==style,"系统宿主应用错误明暗或槽位");
                        check(prefs.values.equals(values)&&prefs.disk.equals(disk)&&"Day".equals(prefs.disk.get("theme"))
                                &&prefs.getInt("selectedAutoNightType",-1)==Theme.AUTO_NIGHT_TYPE_SYSTEM,"系统宿主把固定/跟随偏好改写了");
                        check(org.telegram.messenger.MessagesController.saved==0,"系统手动宿主应用触发 RPC");
                    }
                    // 原过渡暂存会覆盖为最后一份主题；暗→暗和暗→浅都在过渡结束之后执行真实 checkNeedRebuild。
                    for(int last:new int[]{3,4}){
                        host.transitionAnimationInProgress=true;
                        check(Theme.selectCodexPixelAppearance(1),"过渡前选择失败");
                        launch.deliver(Theme.NotificationCenter.args);
                        int applied=Theme.applied,updates=host.background.updates,clears=host.hiddenClears();
                        check(host.animateThemeAfterAnimation&&host.background.updates==updates,"过渡中没有暂存宿主应用");
                        check(Theme.selectCodexPixelAppearance(last),"过渡后选择失败");
                        launch.deliver(Theme.NotificationCenter.args);
                        check(Theme.applied==applied+1&&host.background.updates==updates,"过渡中提前走了第二次宿主应用");
                        java.util.Map<String,Object> values=new java.util.HashMap<String,Object>(prefs.values),disk=new java.util.HashMap<String,Object>(prefs.disk);
                        host.flush();
                        check(!host.animateThemeAfterAnimation&&host.animateSetThemeAfterAnimation==null&&Theme.getCodexPixelStyle().id==last
                                &&host.background.styleId==last&&Theme.lastNight==com.butang.codextop.CodexPixelPalette.metadata(last).dark,"过渡结束没有应用最后一次选择");
                        check(prefs.values.equals(values)&&prefs.disk.equals(disk)&&host.hiddenClears()==clears+1,"过渡结束丢失偏好或返回页面刷新");
                        check(Theme.accentsSaved==0&&org.telegram.messenger.MessagesController.saved==0,"过渡结束后触发远端或强调色保存");
                    }
                    org.telegram.messenger.AndroidUtilities.tablet=true;
                    launch.layersActionBarLayout=new ManualThemeHost();launch.rightActionBarLayout=new ManualThemeHost();
                    check(Theme.selectCodexPixelAppearance(6),"平板宿主选择失败");
                    launch.deliver(Theme.NotificationCenter.args);
                    check(launch.layersActionBarLayout.background.styleId==6&&launch.rightActionBarLayout.title.styleId==6
                            &&launch.layersActionBarLayout.hiddenClears()==1&&launch.rightActionBarLayout.hiddenClears()==1,"原平板附加宿主没有接收主题");
                    check(Theme.accentsSaved==0&&org.telegram.messenger.MessagesController.saved==0&&"keep".equals(prefs.disk.get("unrelated")),"平板宿主改写无关偏好或远端保存");
                    org.telegram.messenger.AndroidUtilities.tablet=false;
                }
            }
            """;
}
