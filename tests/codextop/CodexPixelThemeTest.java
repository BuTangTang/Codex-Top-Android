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

/** 提取主题迁移、色表复制和平色壁纸决策，只替换偏好、颜色数组和 Drawable 这些平台边界。 */
public final class CodexPixelThemeTest {
    /** 编译真实决策并核对 Day/Night、一次迁移、平色壁纸和底栏映射。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        String themeSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java"));
        String tabsSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java"));
        String glassSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Components/glass/GlassTabView.java"));
        String chatSource = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"));
        CompilationUnit themeUnit = StaticJavaParser.parse(themeSource);
        MethodDeclaration plan = method(themeUnit, "planCodexPixelThemeMigration");
        MethodDeclaration commit = method(themeUnit, "commitCodexPixelThemeMigration");
        MethodDeclaration copy = method(themeUnit, "copyCodexPixelPalette");
        MethodDeclaration wallpaper = method(themeUnit, "codexPixelWallpaper");
        String migrationType = themeUnit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("CodexPixelThemeMigration"))
                .findFirst().orElseThrow(() -> new AssertionError("缺少一次性迁移结果")).toString();
        checkOrder(themeSource, themeUnit, tabsSource, glassSource, chatSource, commit, copy, wallpaper);
        Path temp = Files.createTempDirectory("codex-pixel-theme-");
        Path sentinel = temp.resolve("wallpaper.jpg");
        Files.writeString(sentinel, "original-wallpaper");
        try {
            writeStubs(temp, root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
            Files.writeString(temp.resolve("Theme.java"), themeShell(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"))
                    + migrationType + "\n" + plan + "\n" + commit + "\n" + copy + "\n" + wallpaper + "\n}\n");
            Files.writeString(temp.resolve("CodexPixelThemeProbe.java"), PROBE);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temp.toString(),
                    temp.resolve("SparseIntArray.java").toString(),
                    temp.resolve("Drawable.java").toString(),
                    temp.resolve("ColorDrawable.java").toString(),
                    temp.resolve("SharedPreferences.java").toString(),
                    temp.resolve("Build.java").toString(),
                    temp.resolve("Theme.java").toString(),
                    root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java").toString(),
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
                public class Theme {
                public static final int AUTO_NIGHT_TYPE_NONE=0;
                public static final int AUTO_NIGHT_TYPE_SCHEDULED=1;
                public static final int AUTO_NIGHT_TYPE_AUTOMATIC=2;
                public static final int AUTO_NIGHT_TYPE_SYSTEM=3;
                """ + keysText + """
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
        Files.writeString(temp.resolve("Drawable.java"), "package android.graphics.drawable; public class Drawable {}");
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
                    public Editor edit(){return new Editor();}
                    public class Editor {
                        final HashMap<String,Object> pending=new HashMap<>();
                        public Editor putString(String key,String value){pending.put(key,value);return this;}
                        public Editor putInt(String key,int value){pending.put(key,value);return this;}
                        public Editor putBoolean(String key,boolean value){pending.put(key,value);return this;}
                        public Editor putFloat(String key,float value){pending.put(key,value);return this;}
                        public Editor putLong(String key,long value){pending.put(key,value);return this;}
                        public Editor remove(String key){pending.put(key,REMOVED);return this;}
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

    private static final String PROBE = """
            package org.telegram.ui.ActionBar;
            import android.content.SharedPreferences;
            import android.graphics.drawable.ColorDrawable;
            import android.os.Build;
            import android.util.SparseIntArray;
            public final class CodexPixelThemeProbe {
                static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
                public static void main(String[] args){
                    plan();
                    persist();
                    storeFailures();
                    palette();
                    foreground();
                    wallpaper();
                    System.out.println("PASS CodexPixelThemeTest: migration, day/night palette, flat wallpaper, foreground contrast");
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
                    check(light.get(Theme.key_chats_unreadCounterText)==0xFFFFFFFF&&dark.get(Theme.key_chats_unreadCounterText)==0xFFFFFFFF,"ON_FILL 不再是白色");
                }
                /** Day 资源里这些键是白或浅蓝；覆盖后必须换成色表对应组，并在真实气泡和回复着色上达到 4.5。 */
                static void foreground(){
                    SparseIntArray day=new SparseIntArray();
                    seedDayChrome(day);
                    Theme.copyCodexPixelPalette(day,"Day",false,true);
                    assertForeground(day,com.butang.codextop.CodexPixelPalette.light(),false);
                    SparseIntArray night=new SparseIntArray();
                    seedDayChrome(night);
                    Theme.copyCodexPixelPalette(night,"Night",true,true);
                    assertForeground(night,com.butang.codextop.CodexPixelPalette.dark(),true);
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
                static void assertForeground(SparseIntArray colors,SparseIntArray palette,boolean dark){
                    int accent=palette.get(Theme.key_windowBackgroundWhiteBlueText);
                    int ink=palette.get(Theme.key_chat_messageTextOut);
                    int secondary=palette.get(Theme.key_chat_outTimeText);
                    int out=colors.get(Theme.key_chat_outBubble),outSel=colors.get(Theme.key_chat_outBubbleSelected);
                    int in=colors.get(Theme.key_chat_inBubble),inSel=colors.get(Theme.key_chat_inBubbleSelected);
                    int line=colors.get(Theme.key_chat_outReplyLine);
                    contrast(colors.get(Theme.key_chat_messageLinkIn),in,"收到链接/普通气泡");
                    contrast(colors.get(Theme.key_chat_messageLinkIn),inSel,"收到链接/选中气泡");
                    contrast(colors.get(Theme.key_chat_messageLinkOut),out,"发出链接/普通气泡");
                    contrast(colors.get(Theme.key_chat_messageLinkOut),outSel,"发出链接/选中气泡");
                    contrast(colors.get(Theme.key_chat_outForwardedNameText),out,"转发名/普通气泡");
                    contrast(colors.get(Theme.key_chat_outForwardedNameText),outSel,"转发名/选中气泡");
                    contrast(line,out,"回复线/普通气泡");
                    contrast(line,outSel,"回复线/选中气泡");
                    contrast(colors.get(Theme.key_chat_outReplyLine2),out,"回复线2/普通气泡");
                    contrast(colors.get(Theme.key_chat_outReplyLine2),outSel,"回复线2/选中气泡");
                    contrast(colors.get(Theme.key_chat_outFileNameText),out,"文件名/普通气泡");
                    contrast(colors.get(Theme.key_chat_outFileNameText),outSel,"文件名/选中气泡");
                    contrast(colors.get(Theme.key_chat_outFileInfoText),out,"文件说明/普通气泡");
                    contrast(colors.get(Theme.key_chat_outFileInfoText),outSel,"文件说明/选中气泡");
                    contrast(colors.get(Theme.key_chat_outFileInfoSelectedText),out,"选中文件说明/普通气泡");
                    contrast(colors.get(Theme.key_chat_outFileInfoSelectedText),outSel,"选中文件说明/选中气泡");
                    int reply=replyBackground(line,out,dark),replySel=replyBackground(line,outSel,dark);
                    int[] replyText=new int[]{Theme.key_chat_outReplyNameText,Theme.key_chat_outReplyMessageText,Theme.key_chat_outSiteNameText,Theme.key_chat_outReplyMediaMessageText,Theme.key_chat_outReplyMediaMessageSelectedText};
                    for(int key:replyText){
                        contrast(colors.get(key),reply,"回复块/普通合成底");
                        contrast(colors.get(key),replySel,"回复块/选中合成底");
                    }
                    check(colors.get(Theme.key_chat_messageLinkIn)==accent&&colors.get(Theme.key_chat_messageLinkOut)==accent&&colors.get(Theme.key_chat_outForwardedNameText)==accent&&line==accent&&colors.get(Theme.key_chat_outReplyLine2)==accent,"链接、转发名或回复线没有使用 accentText");
                    check(colors.get(Theme.key_chat_outReplyNameText)==ink&&colors.get(Theme.key_chat_outSiteNameText)==ink&&colors.get(Theme.key_chat_outReplyMessageText)==ink&&colors.get(Theme.key_chat_outFileNameText)==ink&&colors.get(Theme.key_chat_outReplyMediaMessageText)==ink&&colors.get(Theme.key_chat_outReplyMediaMessageSelectedText)==ink,"回复名、正文、媒体说明或站点名没有使用 ink");
                    check(colors.get(Theme.key_chat_outFileInfoText)==secondary&&colors.get(Theme.key_chat_outFileInfoSelectedText)==secondary,"文件说明没有使用 secondary");
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
            }
            """;
}
