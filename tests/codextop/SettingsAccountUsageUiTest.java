package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;

/** 提取原设置页的实际额度方法，仅替换页面绘制、生命周期和异步读取边界。 */
public final class SettingsAccountUsageUiTest {
    /** 在独立临时目录编译实际方法与额度模型；不加载 Android、不访问真实账号。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path java = Path.of("TMessagesProj/src/main/java");
        ClassOrInterfaceDeclaration source = StaticJavaParser.parse(java.resolve("org/telegram/ui/SettingsActivity.java"))
                .getClassByName("SettingsActivity").orElseThrow();
        StringBuilder actual = new StringBuilder();
        for (String field : List.of("codexBrowseRows", "codexBrowseError", "codexBrowseLoading", "codexBrowseGeneration",
                "codexBrowseCursor", "codexBrowseRequestCursor", "codexBrowseLoaded", "codexBrowseIncomplete", "codexUsage", "codexUsageError",
                "codexUsageLoading", "codexUsageGeneration", "codexUsageMachine", "codexUsageMachineName", "codexUsageSourcesRequested",
                "codexBrowseRequested"))
            actual.append(source.getFieldByName(field).orElseThrow()).append('\n');
        for (String method : List.of("codexQuota", "codexQuotaDetail", "codexMyRoot", "codexUsageMachineId",
                "codexUsageSourceLabel", "selectCodexUsageMachine", "applyCodexUsageSources", "loadCodexUsageSources",
                "loadCodexUsage", "restoreCodexUsageFromCache", "codexBrowser", "codexConversations", "applyCodexBrowse",
                "loadCodexBrowser", "onResume", "onFragmentDestroy"))
            for (var overload : source.getMethodsByName(method)) actual.append(overload).append('\n');
        Path temp = Files.createTempDirectory("codex-settings-usage-");
        try {
            Path probe = temp.resolve("SettingsUsageProbe.java"), runtime = temp.resolve("CodexRuntime.java");
            Files.writeString(probe, FIXTURE + actual + SCENARIOS + "}");
            Files.writeString(runtime, RUNTIME);
            int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-cp", System.getProperty("java.class.path"), "-d", temp.toString(), probe.toString(), runtime.toString(),
                    java.resolve("com/butang/codextop/AccountUsage.java").toString());
            if (compiled != 0) throw new AssertionError("设置页真实方法夹具编译失败");
            // 临时产物优先，避免共用模型 classpath 把同包类型拆到两个 classloader。
            ArrayList<URL> urls = new ArrayList<>();
            urls.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
                urls.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("org.telegram.ui.SettingsUsageProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("设置页真实方法回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    // 替身只存回调和计数；来源选择、缓存应用、去重及迟到保护全部来自实际设置页方法。
    private static final String RUNTIME = """
        package com.butang.codextop;
        import com.google.gson.*; import java.util.*; import java.util.function.*;
        public final class CodexRuntime {
            public interface BrowseCallback { void accept(JsonObject snapshot, String error, boolean cached); }
            public record Read(String machine, BiConsumer<AccountUsage,String> callback) {}
            public static boolean active = true;
            public static JsonObject snapshot;
            public static String selected;
            public static String selectedAccountUsageMachine(){return selected;}
            public static void selectAccountUsageMachine(String machine){selected=machine;}
            public static final Map<String,AccountUsage> cache = new HashMap<>();
            public static final List<Read> reads = new ArrayList<>();
            public static final List<BrowseCallback> browses = new ArrayList<>();
            public static final List<String> cursors = new ArrayList<>();
            /** 返回合成开关，不读取应用或账号。 */
            public static boolean enabled() { return active; }
            /** 返回指定来源的合成快照，不复制产品缓存规则。 */
            public static AccountUsage cachedAccountUsage(String machine) { return cache.get(machine); }
            /** 返回合成名单；测试决定回调派发时序。 */
            public static JsonObject cachedBrowse(String machine, Object roots, boolean conversations) { return snapshot; }
            /** 暂存异步边界，绝不自动触发第二次读取。 */
            public static void browseComputers(String machine, BrowseCallback callback) { cursors.add(null); browses.add(callback); }
            /** 记录项目会话原入口的游标，不合并分页。 */
            public static void browseConversations(int account, String machine, ArrayList<String> roots, String cursor, BrowseCallback callback) { cursors.add(cursor); browses.add(callback); }
            /** 记录实际设置方法发起的读取及原回调。 */
            public static void readAccountUsage(String machine, BiConsumer<AccountUsage,String> callback) { reads.add(new Read(machine, callback)); }
            /** 模拟原 Runtime 在派发 UI 回调前已更新同来源快照的边界。 */
            public static void complete(int index, AccountUsage value, String error) {
                Read read=reads.get(index);if(value!=null)cache.put(read.machine(),value);
                read.callback().accept(value,error);
            }
            /** 清空各场景的合成输入和调度记录。 */
            public static void reset() { active=true; snapshot=null; selected=null; cache.clear(); reads.clear(); browses.clear(); cursors.clear(); }
        }
        """;

    private static final String FIXTURE = """
        package org.telegram.ui;
        import com.google.gson.*; import com.butang.codextop.*; import java.util.*;
        class Base {
            /** 生命周期边界不模拟业务。 */
            public void onResume() {}
            /** 生命周期边界不模拟业务。 */
            public void onFragmentDestroy() {}
        }
        public final class SettingsUsageProbe extends Base {
            static final class Args extends HashMap<String,Object> {
                /** 对应原 Bundle 的可选布尔读取。 */
                boolean getBoolean(String key, boolean fallback) { return containsKey(key) ? (Boolean)get(key) : fallback; }
                /** 对应原 Bundle 的字符串读取。 */
                String getString(String key) { return (String)get(key); }
                /** 对应原 Bundle 的字符串列表读取。 */
                ArrayList<String> getStringArrayList(String key) { Object value=get(key); return value instanceof ArrayList ? (ArrayList<String>)value : null; }
            }
            static final class TextUtils {
                /** 平台文字比较边界。 */
                static boolean equals(CharSequence a, CharSequence b) { return Objects.equals(a,b); }
                /** 平台空文字边界。 */
                static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
            }
            static final class NotificationCenter {
                static final int updateInterfaces=1, starBalanceUpdated=2, newSuggestionsAvailable=3;
                /** 只计生命周期注销，不构造通知逻辑。 */
                void removeObserver(Object owner, int event) {}
            }
            final Args args = new Args(); Object listView = new Object(); int currentAccount, renders;
            /** 仅提供平台参数容器。 */
            Args getArguments() { return args; }
            /** 原适配器重绘作为观测边界，不镜像列表布局。 */
            void updateCodexBrowserItems() { renders++; }
            /** 注销通知的无副作用边界。 */
            NotificationCenter getNotificationCenter() { return new NotificationCenter(); }
        """;

    private static final String SCENARIOS = """
            /** 使用真实模型解析仅用于区分来源的合成额度。 */
            static AccountUsage usage(int percent) throws Exception {
                JsonObject value=new JsonObject();value.addProperty("status","available");JsonObject source=new JsonObject();source.addProperty("kind","codexHome");source.addProperty("home","user");value.add("source",source);
                value.addProperty("fetchedAtMs",System.currentTimeMillis());value.addProperty("staleAtMs",System.currentTimeMillis()+60000);JsonArray meters=new JsonArray();JsonObject meter=new JsonObject();meter.addProperty("label","synthetic");meter.addProperty("remainingPct",percent);meters.add(meter);value.add("meters",meters);return AccountUsage.parse(value);
            }
            /** 构造只含来源身份的电脑行。 */
            static JsonObject machine(String id) { JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("name","computer-"+id);return row; }
            /** 构造真实方法期望的名单形状。 */
            static JsonObject sources(String... ids) { JsonArray rows=new JsonArray();for(String id:ids)rows.add(machine(id));JsonObject result=new JsonObject();result.add("rows",rows);return result; }
            /** 每个场景使用新的页面及独立合成来源。 */
            static SettingsUsageProbe reset() { CodexRuntime.reset();return new SettingsUsageProbe(); }
            /** 失败时保留具体行为边界，避免把夹具编译当成产品失败。 */
            static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
            /** 缓存、名单双回调、回访及同来源选择不应隐式重读额度。 */
            static void cacheAndRevisit() throws Exception {
                SettingsUsageProbe page=reset();AccountUsage cached=usage(10);CodexRuntime.cache.put("A",cached);CodexRuntime.snapshot=sources("A");
                page.onResume();check(page.codexUsage==cached&&"A".equals(page.codexUsageMachine)&&CodexRuntime.reads.isEmpty(),"cached quota did not display before fresh source list");
                check(CodexRuntime.browses.size()==1&&page.codexBrowseLoading,"initial source refresh missing");
                CodexRuntime.browses.get(0).accept(sources("A"),null,true);
                CodexRuntime.browses.get(0).accept(sources("A"),null,false);
                check(page.codexUsage==cached&&CodexRuntime.reads.isEmpty(),"cached plus fresh source callbacks reread cached quota");
                int renders=page.renders;page.onResume();page.onResume();page.selectCodexUsageMachine(machine("A"),true);
                check(CodexRuntime.browses.size()==1&&CodexRuntime.reads.isEmpty()&&page.renders>renders,"tab revisit or same-source selection performed a new read");
            }
            /** 没有额度缓存时等待新鲜单电脑名单，两个名单回调仅发起一次实际读取。 */
            static void firstReadAndRefresh() throws Exception {
                SettingsUsageProbe page=reset();CodexRuntime.snapshot=sources("A");page.onResume();
                check(page.codexUsageMachine==null&&CodexRuntime.reads.isEmpty(),"cached source without quota triggered premature quota read");
                CodexRuntime.browses.get(0).accept(sources("A"),null,true);CodexRuntime.browses.get(0).accept(sources("A"),null,false);
                check(CodexRuntime.reads.size()==1&&page.codexUsageLoading,"fresh single source failed to start exactly one read");
                page.onResume();page.selectCodexUsageMachine(machine("A"));page.loadCodexUsage();
                check(CodexRuntime.reads.size()==1,"pending read duplicated by revisit, same-source selection or refresh");
                AccountUsage first=usage(20);CodexRuntime.complete(0,first,null);
                page.onResume();page.selectCodexUsageMachine(machine("A"));check(CodexRuntime.reads.size()==1&&page.codexUsage==first,"settled quota silently reread");
                page.loadCodexUsage();check(CodexRuntime.reads.size()==2&&page.codexUsage==first,"explicit refresh did not read or blanked cached quota");
                CodexRuntime.complete(1,null,"synthetic unavailable");
                check(page.codexUsage==first&&page.codexUsageError!=null&&!page.codexUsageLoading,"failed refresh removed the previous collected quota");
                page.loadCodexUsage();check(CodexRuntime.reads.size()==3,"failed refresh could not be explicitly retried");
            }
            /** 多电脑不猜来源；A 的迟到响应不能覆盖已选择的 B 缓存或正在读取状态。 */
            static void sourceSwitch() throws Exception {
                SettingsUsageProbe page=reset();AccountUsage b=usage(30);CodexRuntime.cache.put("B",b);CodexRuntime.snapshot=sources("A","B");
                page.onResume();CodexRuntime.browses.get(0).accept(sources("A","B"),null,false);
                check(page.codexUsageMachine==null&&CodexRuntime.reads.isEmpty(),"multiple sources selected without user choice");
                page.selectCodexUsageMachine(machine("A"),true);page.selectCodexUsageMachine(machine("B"),true);
                check(page.codexUsage==b&&CodexRuntime.reads.size()==2,"explicit switch did not use B cache and refresh once");
                page.loadCodexUsage();int renders=page.renders;
                check(CodexRuntime.reads.size()==2,"pending switch duplicated B refresh");CodexRuntime.complete(0,usage(90),null);
                check(page.codexUsage==b&&page.codexUsageLoading&&page.renders==renders&&"B".equals(page.codexUsageMachine),"late A response overwrote B");
                AccountUsage fresh=usage(40);CodexRuntime.complete(1,fresh,null);
                check(page.codexUsage==fresh&&!page.codexUsageLoading,"current B response did not publish");
                page.selectCodexUsageMachine(machine("A"),true);page.selectCodexUsageMachine(machine("B"),true);renders=page.renders;
                CodexRuntime.complete(2,null,"late A error");
                check(page.codexUsage==fresh&&page.codexUsageError==null&&page.codexUsageLoading&&page.renders==renders,"late A error cleared B snapshot");
            }
            /** 销毁后名单与额度两个回调均失效，不再发起读取或重绘。 */
            static void destruction() throws Exception {
                SettingsUsageProbe page=reset();page.onResume();int renders=page.renders;page.onFragmentDestroy();
                CodexRuntime.browses.get(0).accept(sources("A"),null,false);
                check(page.renders==renders&&page.codexUsageMachine==null&&CodexRuntime.reads.isEmpty(),"late source list updated destroyed page");
                page=reset();page.selectCodexUsageMachine(machine("A"));renders=page.renders;page.onFragmentDestroy();
                CodexRuntime.reads.get(0).callback().accept(usage(50),null);
                check(page.renders==renders&&page.codexUsage==null,"late quota updated destroyed page");
            }
            /** 普通 Telegram 与电脑浏览页不走根页额度初始化；空名单和名单失败允许原入口重试。 */
            static void scopesAndMissing() throws Exception {
                SettingsUsageProbe page=reset();CodexRuntime.active=false;page.onResume();
                check(CodexRuntime.browses.isEmpty()&&CodexRuntime.reads.isEmpty()&&page.renders==0,"ordinary Telegram entered Codex quota path");
                page=reset();page.args.put("codexComputerBrowser",true);page.onResume();
                check(CodexRuntime.browses.size()==1&&CodexRuntime.reads.isEmpty()&&page.codexUsageMachine==null,"computer browser changed to quota root");
                page=reset();page.onResume();CodexRuntime.browses.get(0).accept(sources(),null,false);
                check(page.codexUsageMachine==null&&!page.codexBrowseLoading&&CodexRuntime.reads.isEmpty(),"empty source list invented quota or retained loading");
                page.loadCodexUsageSources();CodexRuntime.browses.get(1).accept(null,"synthetic source unavailable",false);
                check(page.codexBrowseError!=null&&!page.codexBrowseLoading&&CodexRuntime.reads.isEmpty(),"source failure invented quota or blocked retry");
                page.loadCodexUsageSources();check(CodexRuntime.browses.size()==3,"explicit source retry did not read");
            }
            /** 磁盘阶段可独立于电脑名单到达；恢复明确来源，临时失败保留旧值，账号变化清旧值。 */
            static void persistedAndStale()throws Exception {
                SettingsUsageProbe page=reset();page.onResume();AccountUsage old=usage(10);CodexRuntime.selected="B";CodexRuntime.cache.put("B",old);
                CodexRuntime.browses.get(0).accept(null,null,true);
                check(page.codexUsage==old&&"B".equals(page.codexUsageMachine)&&CodexRuntime.reads.isEmpty(),"local quota waited for fresh source list");
                CodexRuntime.browses.get(0).accept(sources("A","B"),null,false);page.onResume();
                check(page.codexUsage==old&&CodexRuntime.reads.isEmpty()&&"computer-B".equals(page.codexUsageMachineName),"fresh multi-source list lost remembered machine or reread fresh quota");
                page=new SettingsUsageProbe();page.onResume();check(page.codexUsage==old&&"B".equals(page.codexUsageMachine)&&CodexRuntime.reads.isEmpty(),"new fragment lost cached value or selected source");
                JsonObject expiredJson=JsonParser.parseString("{\\"status\\":\\"available\\",\\"source\\":{\\"kind\\":\\"codexHome\\",\\"home\\":\\"user\\"},\\"fetchedAtMs\\":1,\\"staleAtMs\\":2,\\"meters\\":[{\\"label\\":\\"old\\",\\"remainingPct\\":20}]}").getAsJsonObject();
                AccountUsage expired=AccountUsage.parse(expiredJson);page=reset();CodexRuntime.selected="B";CodexRuntime.cache.put("B",expired);page.onResume();
                check(page.codexUsage==expired&&page.codexUsageLoading&&CodexRuntime.reads.size()==1,"stale remembered quota blanked before background refresh");
                CodexRuntime.browses.get(0).accept(sources("A","B"),null,true);CodexRuntime.browses.get(0).accept(sources("A","B"),null,false);
                check(CodexRuntime.reads.size()==1,"local plus fresh stages duplicated stale refresh");
                AccountUsage unavailable=AccountUsage.parse(JsonParser.parseString("{\\"status\\":\\"unavailable\\",\\"reason\\":\\"source_unavailable\\"}").getAsJsonObject());
                CodexRuntime.reads.get(0).callback().accept(unavailable,null);check(page.codexUsage==expired&&page.codexUsageError!=null&&!page.codexUsageLoading,"temporary source failure cleared old sample");
                page.loadCodexUsage();AccountUsage changed=AccountUsage.parse(JsonParser.parseString("{\\"status\\":\\"unavailable\\",\\"reason\\":\\"account_changed\\"}").getAsJsonObject());
                CodexRuntime.complete(1,changed,null);check(page.codexUsage==changed&&!page.codexUsage.available,"changed source account retained old account quota");
            }
            /** 缓存已展示不等于已请求；同一页返回不再发浏览，显式刷新和分页仍走原入口。 */
            static void browserReturn() {
                SettingsUsageProbe page=reset();page.args.put("codexComputerBrowser",true);
                JsonObject cached=sources("A");page.applyCodexBrowse(cached);
                check(page.codexBrowseLoaded&&page.codexBrowseRows.size()==1,"cached computer page did not keep its row before resume");
                page.onResume();
                check(CodexRuntime.browses.size()==1&&page.codexBrowseRows==cached.getAsJsonArray("rows"),"first cached resume skipped the background read or cleared rows");
                int renders=page.renders;page.onResume();
                check(CodexRuntime.browses.size()==1&&page.renders>renders,"in-flight resume issued another browse or skipped the adapter");
                CodexRuntime.browses.get(0).accept(sources("A","B"),null,false);
                check(page.codexBrowseRows.size()==2&&!page.codexBrowseLoading,"fresh computer page did not replace the cached rows");
                renders=page.renders;page.onResume();
                check(CodexRuntime.browses.size()==1&&page.renders>renders,"return after success issued another browse");
                page.loadCodexBrowser(false);
                check(CodexRuntime.browses.size()==2,"explicit refresh did not use the original browse");
                page=reset();page.args.put("codexComputerBrowser",true);page.onResume();
                check(CodexRuntime.browses.size()==1&&page.codexBrowseRows.size()==0,"first empty page skipped the browse");
                page.onResume();check(CodexRuntime.browses.size()==1,"in-flight empty page resume issued another browse");
                JsonObject kept=sources("kept");page.applyCodexBrowse(kept);
                CodexRuntime.browses.get(0).accept(null,"synthetic browse failed",false);
                check(page.codexBrowseRows==kept.getAsJsonArray("rows")&&page.codexBrowseError!=null&&!page.codexBrowseLoading,"failed browse cleared old rows");
                page.onResume();check(CodexRuntime.browses.size()==1,"return after failure issued another browse");
                page.loadCodexBrowser(false);check(CodexRuntime.browses.size()==2,"manual retry after failure did not browse");
                page=reset();page.args.put("codexComputerBrowser",true);page.args.put("codexConversations",true);page.args.put("codexMachine","machine");
                JsonObject paged=sources("thread");paged.addProperty("nextCursor","page-2");page.applyCodexBrowse(paged);page.onResume();
                check(CodexRuntime.browses.size()==1&&CodexRuntime.cursors.get(0)==null,"first conversation page sent a cursor");
                CodexRuntime.browses.get(0).accept(paged,null,false);page.loadCodexBrowser(true);
                check(CodexRuntime.browses.size()==2&&"page-2".equals(CodexRuntime.cursors.get(1)),"next page did not use the original cursor");
                page=reset();CodexRuntime.active=false;page.args.put("codexComputerBrowser",true);page.onResume();
                check(CodexRuntime.browses.isEmpty()&&page.renders==0,"ordinary Telegram started a Codex browse");
            }
            /** 各组只驱动真实生产方法和保存的原回调，不重写额度决策。 */
            public static void main(String[] args) throws Exception {
                cacheAndRevisit();firstReadAndRefresh();sourceSwitch();destruction();scopesAndMissing();persistedAndStale();browserReturn();
                System.out.println("PASS SettingsAccountUsageUiTest: cache, source callbacks, revisit, same source, refresh, switch, destroy, scopes");
            }
        """;
}
