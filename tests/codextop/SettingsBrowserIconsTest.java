package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;

/** 执行原电脑浏览分支和条目工厂，平台替身只承接绘制结果，不重写实体判断。 */
public final class SettingsBrowserIconsTest {
    /** 合成样例核对语义素材及原条目身份、顺序和分页；不读取真实电脑或操作界面。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path sourcePath = Path.of(args.length == 0 ? "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java" : args[0]);
        var source = StaticJavaParser.parse(sourcePath).getClassByName("SettingsActivity").orElseThrow();
        var branch = source.getMethodsByName("fillItems").get(0).findAll(IfStmt.class).stream()
                .filter(value -> value.getCondition().toString().equals("codexBrowser()")).findFirst().orElseThrow();
        StringBuilder actual = new StringBuilder();
        for (String method : List.of("codexBrowser", "codexConversations")) actual.append(source.getMethodsByName(method).get(0));
        ClassOrInterfaceDeclaration factory = source.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(value -> value.getNameAsString().equals("SettingCell")).findFirst().orElseThrow()
                .findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(value -> value.getNameAsString().equals("Factory")).findFirst().orElseThrow();
        actual.append("static class SettingCell {static class Factory extends FactoryBase {");
        for (String method : List.of("of", "ofBrowse", "equals", "contentsEquals"))
            for (var overload : factory.getMethodsByName(method)) actual.append(overload);
        actual.append("}} void fill(ArrayList<UItem> items) {").append(branch).append("}");
        Path model = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        var identity = StaticJavaParser.parse(model.resolve("BrowseStore.java")).getClassByName("BrowseStore").orElseThrow()
                .getMethodsByName("identity").get(0);
        Path temp = Files.createTempDirectory("codex-settings-browser-");
        try {
            Path probe = temp.resolve("BrowserIconsProbe.java"), runtime = temp.resolve("CodexRuntime.java"), store = temp.resolve("BrowseStore.java");
            Files.writeString(probe, FIXTURE + actual + SCENARIOS + "}");
            Files.writeString(runtime, "package com.butang.codextop; public class CodexRuntime {public static boolean active=true;public static boolean enabled(){return active;}}");
            Files.writeString(store, "package com.butang.codextop;import com.google.gson.JsonObject;public class BrowseStore {" + identity + "}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", temp.toString(), probe.toString(), runtime.toString(), store.toString()) != 0)
                throw new AssertionError("真实浏览分支夹具编译失败");
            ArrayList<URL> urls = new ArrayList<>(); urls.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("org.telegram.ui.BrowserIconsProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("原浏览分支回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    // 只替换 Bundle、素材编号和 UItem 展示容器；实体判断、分支、工厂和判等均提取自产品源。
    private static final String FIXTURE = """
        package org.telegram.ui;
        import java.util.*;import com.google.gson.*;import com.butang.codextop.*;
        public final class BrowserIconsProbe {
            final Args args=new Args();JsonArray codexBrowseRows=new JsonArray();
            boolean codexBrowseLoading,codexBrowseLoaded,codexBrowseIncomplete;String codexBrowseError,codexBrowseCursor;
            static class Args extends HashMap<String,Object>{String getString(String key){return (String)get(key);}boolean getBoolean(String key,boolean fallback){return containsKey(key)?(boolean)get(key):fallback;}}
            Args getArguments(){return args;}
            static class R {static class drawable {static final int settings_data=1,settings_chat=2,settings_devices=3,settings_folders=4;}}
            static class IconBackgroundColors {static final IconBackgroundColors BLUE_DEEP=new IconBackgroundColors();int top=10,bottom=20;}
            static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
            static class UItem {int id,iconResId;long longValue;CharSequence text,subtext,textValue;Object object,object2;
                static UItem ofFactory(Class<?> factory){return new UItem();}static UItem asShadow(Object ignored){UItem i=new UItem();i.id=-1;return i;}}
            static class FactoryBase {public boolean equals(UItem a,UItem b){return a.id==b.id;}
                public boolean contentsEquals(UItem a,UItem b){return equals(a,b)&&Objects.equals(a.text,b.text);}}
        """;

    private static final String SCENARIOS = """
            /** 各实体仅包含真实分支要求的合成字段，数组次序就是原页次序。 */
            static JsonObject row(String id){JsonObject r=new JsonObject();r.addProperty("id",id);r.addProperty("remoteSessionId",id);r.addProperty("name","name-"+id);r.addProperty("title","title-"+id);r.addProperty("active",true);r.addProperty("available",true);return r;}
            static BrowserIconsProbe page(boolean machine,boolean conversations){CodexRuntime.active=true;BrowserIconsProbe p=new BrowserIconsProbe();p.args.put("codexComputerBrowser",true);if(machine)p.args.put("codexMachine","synthetic-machine");p.args.put("codexConversations",conversations);return p;}
            static ArrayList<UItem> items(BrowserIconsProbe p){ArrayList<UItem> items=new ArrayList<>();p.fill(items);return items;}
            static UItem item(List<UItem> items,int id){return items.stream().filter(i->i.id==id).findFirst().orElseThrow();}
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            /** 电脑和项目只换现有语义素材，真实行负载、原稳定键和顺序不变。 */
            static void entities(){
                BrowserIconsProbe p=page(false,false);JsonObject a=row("a"),b=row("b");p.codexBrowseRows.add(a);p.codexBrowseRows.add(b);p.codexBrowseLoaded=true;
                ArrayList<UItem> rows=items(p);check(rows.size()==3&&rows.get(0).iconResId==R.drawable.settings_devices,"computer row kept unrelated brand icon");
                check(rows.get(0).id==1000&&rows.get(0).object==a&&"computers:a".equals(rows.get(0).object2)&&rows.get(1).object==b,"computer identity, click payload or order changed");
                p=page(true,false);p.codexBrowseRows.add(a);rows=items(p);
                check(rows.size()==3&&rows.get(0).id==8&&rows.get(0).iconResId==R.drawable.settings_chat,"all conversations entry lost semantic icon or id");
                check(rows.get(1).iconResId==R.drawable.settings_folders&&rows.get(1).object==a&&"projects:a".equals(rows.get(1).object2),"project identity or semantic icon changed");
            }
            /** 会话实体不给头像装饰代理，缺标题仍沿原文案；仅图标变化不影响行身份规则。 */
            static void conversations(){
                BrowserIconsProbe p=page(true,true);JsonObject r=row("r");r.remove("title");p.codexBrowseRows.add(r);p.codexBrowseCursor="next";ArrayList<UItem> rows=items(p);UItem entity=item(rows,1000);
                check(entity.iconResId==0&&"conversations:r".equals(entity.object2)&&entity.object==r&&"未命名对话".equals(entity.text),"conversation retained avatar proxy or lost original payload/title fallback");
                check(item(rows,10).iconResId==0&&rows.get(0)==entity&&rows.get(1).id==10,"more row kept decoration or changed position");
                UItem renamed=SettingCell.Factory.ofBrowse("conversations:r",r,10,20,0,"renamed","");SettingCell.Factory factory=new SettingCell.Factory();
                check(factory.equals(entity,renamed)&&!factory.contentsEquals(entity,renamed),"rename changed stable identity or suppressed content update");
            }
            /** 加载、错误、空页和部分页仍用原入口；分页失败不并列重复操作。 */
            static void loadingAndError(){
                BrowserIconsProbe p=page(true,true);p.codexBrowseLoading=true;UItem loading=item(items(p),9);check(loading.iconResId==0&&"正在加载".equals(loading.text),"loading retained decoration or changed message");
                p.codexBrowseLoaded=true;p.codexBrowseLoading=false;p.codexBrowseError="synthetic error";p.codexBrowseCursor="next";ArrayList<UItem> rows=items(p);
                check(item(rows,9).iconResId==0&&"synthetic error".equals(item(rows,9).text)&&rows.stream().noneMatch(i->i.id==10),"error icon or original paging retry changed");
                p.codexBrowseError=null;p.codexBrowseCursor=null;check("当前页暂无匹配对话".equals(item(items(p),9).text),"empty page semantics changed");
                p.codexBrowseIncomplete=true;p.codexBrowseRows.add(row("r"));check("部分对话尚未读取".equals(item(items(p),9).text),"incomplete page indication lost");
                p.codexBrowseIncomplete=false;check(items(p).stream().noneMatch(i->i.id==9||i.id==10),"settled page gained placeholder");
            }
            /** 非 Codex 或非浏览设置页没有进入这些实体分支。 */
            static void scope(){BrowserIconsProbe p=page(false,false);CodexRuntime.active=false;check(items(p).isEmpty(),"ordinary Telegram entered Codex browser");CodexRuntime.active=true;p.args.clear();check(items(p).isEmpty(),"quota root entered browser branch");}
            public static void main(String[] args)throws Exception{int failures=0;for(String name:new String[]{"entities","conversations","loadingAndError","scope"}){
                try{BrowserIconsProbe.class.getDeclaredMethod(name).invoke(null);System.out.println("PASS "+name);}catch(java.lang.reflect.InvocationTargetException e){failures++;System.out.println("FAIL "+name+": "+e.getCause().getMessage());}}
                if(failures!=0)throw new AssertionError(failures+" browser branch scenario(s) failed");}
        """;
}
