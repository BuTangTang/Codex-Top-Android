package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.Statement;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实预热、搜索展开、recent 归约及观察器生命周期；Android 绘图与异步存储只替换边界。 */
public final class DialogsSearchPreloadTest {
    private static final Path SOURCE = Path.of("TMessagesProj/src/main/java");

    /** 可传旧前像和场景组，分别证明首页预建及首次空搜索异步结果的真实旧行为失败。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source = args.length == 0 ? SOURCE.resolve("org/telegram/ui/DialogsActivity.java") : Path.of(args[0]);
        String original = Files.readString(source);
        var owner = StaticJavaParser.parse(original).getClassByName("DialogsActivity").orElseThrow();
        var adapter = read("org/telegram/ui/Adapters/DialogsSearchAdapter.java", "DialogsSearchAdapter");
        var pager = read("org/telegram/ui/Components/SearchViewPager.java", "SearchViewPager");
        var utilities = read("org/telegram/messenger/AndroidUtilities.java", "AndroidUtilities");
        var watcher = read("org/telegram/messenger/utils/SearchTextWatcher.java", "SearchTextWatcher");
        StringBuilder code = new StringBuilder(FIXTURE);
        owner.getFields().stream().filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().startsWith("DIALOGS_TYPE_") || v.getNameAsString().startsWith("codexSearch"))).forEach(code::append);
        for (String name : new String[]{"onBecomeFullyVisible", "isArchive", "search"}) code.append(clean(method(owner, name)));
        owner.getMethods().stream().filter(m -> m.getNameAsString().contains("CodexSearch")).forEach(m -> code.append(clean(m)));
        for (String name : new String[]{"onSearchExpand", "onSearchCollapse", "onTextChanged"}) {
            MethodDeclaration callback = method(owner, "createView").findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(name)).findFirst().orElseThrow();
            code.append(clean(callback));
        }
        code.append("void rebuild(){").append(lifecycleCalls(method(owner, "createView"))).append("fragmentView=new ContentView(); searching=false; searchIsShowed=false;}\n");
        code.append("void destroy(){").append(lifecycleCalls(method(owner, "onFragmentDestroy"))).append("isFinished=true;}\n");
        code.append("void resume(){isPaused=false;");
        method(owner, "onResume").getBody().orElseThrow().getStatements().stream().filter(s -> calls(s, "onResume", "searchViewPager")).forEach(code::append);
        code.append("}\nvoid preset(){");
        method(owner, "createView").getBody().orElseThrow().getStatements().stream().filter(s -> s.isIfStmt() && s.asIfStmt().getCondition().toString().equals("searchString != null")).forEach(code::append);
        code.append("}\nvoid showSearch(boolean show,boolean downloads,boolean animated){showSearch(show,downloads,animated,false);}\n");
        MethodDeclaration show = owner.getMethodsByName("showSearch").stream().filter(m -> m.getParameters().size() == 4).findFirst().orElseThrow();
        code.append("void showSearch(boolean show,boolean startFromDownloads,boolean animated,boolean forceNotOnlyDialogs){");
        for (Statement statement : show.getBody().orElseThrow().getStatements()) {
            code.append(statement);
            if (statement.toString().equals("searchIsShowed = show;")) break;
        }
        code.append("if(show){showCalls++;searchViewPager.visible=true;searchViewPager.dialogsSearchAdapter.notifyDataSetChanged();}else if(searchViewPager!=null)searchViewPager.visible=false;");
        show.getBody().orElseThrow().getStatements().stream().filter(s -> calls(s, "showDownloads", "searchViewPager")).forEach(code::append);
        code.append("}\nvoid createSearchViewPager(){");
        var create = method(owner, "createSearchViewPager");
        for (Statement statement : create.getBody().orElseThrow().getStatements()) {
            Statement copy = statement.clone();
            if (calls(copy, "setDelegate", "searchViewPager.dialogsSearchAdapter")) continue;
            if (copy.toString().startsWith("searchViewPager = new SearchViewPager")) {
                copy.findAll(ObjectCreationExpr.class).forEach(c -> { if(c.getAnonymousClassBody().isPresent()) c.setAnonymousClassBody(new com.github.javaparser.ast.NodeList<>()); });
                code.append(copy);
            } else if (create.getBody().orElseThrow().getStatements().indexOf(statement) < 6
                    || calls(copy, "addView", "((ContentView) fragmentView)")
                    || copy.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().contains("CodexSearch"))) code.append(copy);
        }
        code.append("}\nstatic class AndroidUtilities{");
        utilities.getMethodsByName("runOnUIThread").forEach(code::append);
        code.append("static void requestAdjustResize(Object a,int b){}static void hideKeyboard(Object v){} }\n");
        code.append("static class SearchViewPager extends View { interface ChatPreviewDelegate{} final Adapter dialogsSearchAdapter=new Adapter(); final Node searchListView=new Node(); boolean visible; int downloads; SearchViewPager(Context c,DialogsSearchPreloadProbe p,int t,int d,int f,long g,ChatPreviewDelegate v){factories++;dialogsSearchAdapter.dialogsActivity=p;dialogsSearchAdapter.dialogsType=d;} void onShown(){} void onTextChanged(String s){} void showDownloads(){downloads++;}\n");
        code.append(clean(method(pager, "onResume"))).append("}\n");
        code.append(ADAPTER);
        for (String name : new String[]{"setRecentSearch", "filterRecent", "wordStartsWith", "filter", "hasRecentSearch", "recentSearchAvailable", "getRecentItemsCount", "hasHints"}) code.append(clean(method(adapter, name)));
        code.append("}\nstatic class SearchTextWatcher{EditText editText;DialogsSearchPreloadProbe listener;String searchQuery;boolean searchIsExpanded,doNotCloseAfterFieldEmpty;SearchTextWatcher(EditText e,DialogsSearchPreloadProbe p){editText=e;listener=p;}\n");
        for(String name:new String[]{"afterTextChanged","toggleSearch","isSearchExpanded"})code.append(clean(method(watcher,name)));
        code.append("}\n").append(SCENARIOS).append("}\nclass Base{public void onBecomeFullyVisible(){}}\nclass CodexRuntime{static boolean active=true;static boolean enabled(){return active;}}\n");
        String generated = code.toString().replace("DialogsActivity.", "DialogsSearchPreloadProbe.");
        Path temp = Files.createTempDirectory("codex-search-preload-");
        try {
            Path file = temp.resolve("DialogsSearchPreloadProbe.java"); Files.writeString(file, generated);
            if (ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-encoding","UTF-8","-d",temp.toString(),file.toString()) != 0) throw new AssertionError("真实方法 Java 8 编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.DialogsSearchPreloadProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{args.length > 1 ? args[1] : "all"}); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("真实搜索行为失败", failure.getCause()); }
            }
            if (!Files.readString(source).equals(original)) throw new AssertionError("专项执行期间生产源码变化");
        } finally {
            try(var files=Files.walk(temp)){for(Path file:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(file);}
        }
    }

    /** 读取当前生产类，测试不手写其门禁表达式。 */
    private static ClassOrInterfaceDeclaration read(String file,String name) throws Exception {return StaticJavaParser.parse(SOURCE.resolve(file)).getClassByName(name).orElseThrow();}
    /** 只取顶层类的方法，避免匿名回调同名匹配。 */
    private static MethodDeclaration method(ClassOrInterfaceDeclaration owner,String name){return owner.getMethodsByName(name).get(0);}
    /** 去掉 Android 接口注解，保留方法体、条件和执行顺序。 */
    private static String clean(MethodDeclaration method){var copy=method.clone();copy.getAnnotations().clear();return copy.toString();}
    /** 定位真实调用所在语句，不以源码字符串是否存在代替行为断言。 */
    private static boolean calls(Statement statement,String name,String scope){return statement.findAll(MethodCallExpr.class).stream().anyMatch(c->c.getNameAsString().equals(name)&&c.getScope().map(Object::toString).orElse("").equals(scope));}
    /** 只替换无关布局销毁边界，执行生产生命周期中新观察器的全部顶层调用。 */
    private static String lifecycleCalls(MethodDeclaration method){StringBuilder body=new StringBuilder();method.getBody().orElseThrow().getStatements().stream().filter(s->s.isExpressionStmt() && s.asExpressionStmt().getExpression().isMethodCallExpr() && s.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().contains("CodexSearch")).forEach(body::append);return body.toString();}

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public final class DialogsSearchPreloadProbe extends Base {
          static int checks,factories; int showCalls,currentAccount,initialDialogsType,folderId,classGuid; long communityId;
          boolean hasMainTabs=true,onlySelect,inPreviewMode,isPaused,isFinished,searching,searchWas,searchIsShowed,searchFiltersWasShowed,hasStories,canShowStoryHint,storyHintShown,storiesEnabled;
          boolean allowBots=true,allowUsers=true,allowChannels=true,allowGroups=true,allowMegagroups=true,allowLegacyGroups=true;
          String searchString,initialSearchString; int initialSearchType=-1; ContentView fragmentView=new ContentView();
          SearchViewPager searchViewPager; int searchViewPagerIndex; Node switchItem,storyHint,storyPremiumHint,dialogStoriesCell,searchAnimator;
          Node animatorSearchVisible=new Node(),actionBar=new Node(); Page[] viewPages={new Page()}; Field fragmentSearchField=new Field();
          final MessagesController controller=new MessagesController();
          Context getContext(){return new Context();}Object getParentActivity(){return this;}MessagesController getMessagesController(){return controller;}
          static List<Object> getDialogsArray(int a,int b,int c,boolean d){return Arrays.asList(new Object());}
          void onPreToggleSearch(){}boolean canToggleSearch(){return true;}void showArchiveHelp(){}void setScrollY(int y){}void updateProxyButton(boolean a,boolean b){}void blur3_InvalidateBlur(){}void updateFloatingButtonVisibility(boolean a){}void checkUi_mainTabsVisible(){}void updateSpeedItem(boolean b){}
          static final class Context{} static class View{static final int GONE=8,VISIBLE=0;Object parent;Object getParent(){return parent;}}
          static class ContentView extends View{void addView(View v,int i){v.parent=this;}}
          static class Node extends View{void setVisibility(int v){}void setTranslationY(float v){}void show(){}void hide(){}void setValue(boolean a,boolean b){}void cancel(){}void setEmptyView(Object v){}void setBackButtonContentDescription(String s){}void setCloseButtonVisible(boolean v){}Node getPremiumHint(){return null;}}
          static class Page{Node listView=new Node();Object progressView=new Object();}
          static class Editable{String text;Editable(String t){text=t;}public String toString(){return text;}}
          static class EditText{String text="";boolean focused=true;String getText(){return text;}boolean hasFocus(){return focused;}void setText(String s){text=s;}void setSelection(int n){}}
          static class Field extends Node{EditText editText=new EditText();Runnable clearing;void clearSearchFiltersWithCallback(){if(clearing!=null)clearing.run();}}
          static class TextUtils{static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}
          static int FILTER_TABS_HEIGHT;static int dp(int n){return n;}int getSearchFieldAdditionOffset(){return 0;}static String getString(int id){return "synthetic";}static class R{static class string{static int AccDescrGoBack;}}
          static class NotificationCenter{static int needCheckSystemBarColors;static NotificationCenter getGlobalInstance(){return new NotificationCenter();}void postNotificationName(int i,Object...v){}}
          static class Prefs extends SharedPreferences{boolean getBoolean(String k,boolean d){return false;}Prefs edit(){return this;}Prefs putBoolean(String k,boolean v){return this;}void commit(){}}
          static class SharedPreferences{boolean getBoolean(String k,boolean d){return false;}Prefs edit(){return new Prefs();}}
          static class MessagesController{int count=2;static Prefs getGlobalMainSettings(){return new Prefs();}static MessagesController getInstance(int a){return new MessagesController();}int getTotalDialogsCount(){return count;}void putUser(Object a,boolean b){}void putChat(Object a,boolean b){}void putEncryptedChat(Object a,boolean b){}}
          static class RecyclerView{static abstract class AdapterDataObserver{public void onChanged(){}}}
          static class Handler{List<Runnable> queued=new ArrayList<>();List<Long> delays=new ArrayList<>();void post(Runnable r){queued.add(r);delays.add(0L);}void postDelayed(Runnable r,long d){queued.add(r);delays.add(d);}void drain(){for(Runnable r:new ArrayList<>(queued)){queued.remove(r);r.run();}}}
          static class ApplicationLoader{static Handler applicationHandler=new Handler();}
          static class LongSparseArray<T>{}
          static class TLRPC{static class User{boolean bot;String username;}static class Chat{boolean monoforum;String title,username;}static class EncryptedChat{}static class ChatInvite{String title;}}
          static class ChatObject{static boolean isChannel(Object c){return false;}static boolean isMegagroup(Object c){return false;}}
          static class UserObject{static String getUserName(TLRPC.User u){return "synthetic";}}
          static class ForumUtilities{static String getMonoForumTitle(int a,TLRPC.Chat c){return "synthetic";}}
          static class MediaDataController{static MediaDataController instance=new MediaDataController();List<Object> hints=new ArrayList<>();static MediaDataController getInstance(int a){return instance;}}
        """;
    private static final String ADAPTER = """
          static class Adapter{
            static class RecentSearchObject{long did;Object object;}
            interface Delegate{long getSearchForumDialogId();}Delegate delegate;int currentAccount,dialogsType;boolean searchWas;
            DialogsSearchPreloadProbe dialogsActivity;String filteredRecentQuery;
            ArrayList<RecentSearchObject> recentSearchObjects=new ArrayList<>(),filteredRecentSearchObjects=new ArrayList<>(),filtered2RecentSearchObjects=new ArrayList<>();LongSparseArray<RecentSearchObject> recentSearchObjectsById=new LongSparseArray<>();
            final List<RecyclerView.AdapterDataObserver> observers=new ArrayList<>();
            void registerAdapterDataObserver(RecyclerView.AdapterDataObserver o){if(observers.contains(o))throw new AssertionError("重复注册");observers.add(o);}
            void unregisterAdapterDataObserver(RecyclerView.AdapterDataObserver o){if(!observers.remove(o))throw new AssertionError("重复解绑");}
            void notifyDataSetChanged(){for(RecyclerView.AdapterDataObserver o:new ArrayList<>(observers))o.onChanged();}
            void deliver(boolean row){ArrayList<RecentSearchObject> rows=new ArrayList<>();if(row){RecentSearchObject item=new RecentSearchObject();item.did=17;item.object=new TLRPC.User();rows.add(item);}setRecentSearch(rows,new LongSparseArray<>());}
        """;
    private static final String SCENARIOS = """
          /** 全部输入为合成状态，排期和 late recent 由测试显式推动。 */
          static DialogsSearchPreloadProbe fresh(){CodexRuntime.active=true;ApplicationLoader.applicationHandler=new Handler();MediaDataController.instance=new MediaDataController();factories=0;return new DialogsSearchPreloadProbe();}
          /** 断言观察到的创建、展开、订阅和队列结果。 */
          static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
          /** 直接执行首次输入框展开，随后在同一原适配器上归约异步 recent。 */
          static void recent(){DialogsSearchPreloadProbe p=fresh();p.onSearchExpand();check(factories==1&&!p.searchIsShowed,"cold focus creates once before async recent");p.searchViewPager.dialogsSearchAdapter.deliver(true);check(p.searchIsShowed&&p.showCalls==1&&p.searchWas,"late recent must open the focused empty search once");p.searchViewPager.dialogsSearchAdapter.deliver(true);check(p.showCalls==1,"repeated result cannot reopen");p.createSearchViewPager();check(factories==1&&p.searchViewPager.dialogsSearchAdapter.observers.size()==1,"same pager keeps one observer");}
          /** 普通首页不排预热，每个独立反例保留原 200ms 主线程任务。 */
          static void prewarm(){DialogsSearchPreloadProbe p=fresh();p.onBecomeFullyVisible();check(ApplicationLoader.applicationHandler.queued.isEmpty(),"normal Codex home must not queue search prewarm");check(factories==0,"normal home must not build a hidden search tree");for(int i=0;i<9;i++){p=fresh();switch(i){case 0:CodexRuntime.active=false;break;case 1:p.hasMainTabs=false;break;case 2:p.initialDialogsType=1;break;case 3:p.folderId=1;break;case 4:p.communityId=2;break;case 5:p.onlySelect=true;break;case 6:p.searchString="preset";break;case 7:p.searchString="";break;default:p.inPreviewMode=true;}p.onBecomeFullyVisible();check(ApplicationLoader.applicationHandler.queued.size()==1&&ApplicationLoader.applicationHandler.delays.get(0)==200,"non-home keeps 200ms prewarm "+i);ApplicationLoader.applicationHandler.drain();check(factories==1&&p.searchViewPager.dialogsSearchAdapter.observers.isEmpty(),"non-home construction stays original "+i);}}
          /** 关闭、重建、销毁及查询变化拒绝迟结果；暂停结果只在原恢复通知后重新评估。 */
          static void boundaries(){
            DialogsSearchPreloadProbe p=fresh();p.onSearchExpand();p.searchViewPager.dialogsSearchAdapter.deliver(false);check(!p.searchIsShowed,"empty recent does not manufacture a page");MediaDataController.instance.hints.add(new Object());p.searchViewPager.dialogsSearchAdapter.notifyDataSetChanged();check(p.searchIsShowed&&p.showCalls==1,"late hints use same existing adapter notification");
            for(int i=0;i<7;i++){p=fresh();p.onSearchExpand();Adapter old=p.searchViewPager.dialogsSearchAdapter;switch(i){case 0:p.onSearchCollapse();break;case 1:p.fragmentSearchField.editText.text="new query";break;case 2:p.fragmentSearchField.editText.focused=false;break;case 3:p.currentAccount++;break;case 4:p.fragmentView=new ContentView();break;case 5:p.searchViewPager.parent=null;break;default:p.searchViewPager=new SearchViewPager(p.getContext(),p,1,0,0,0,null);}old.deliver(true);check(p.showCalls==0,"late result rejects changed intent/identity "+i);}
            p=fresh();p.onSearchExpand();Adapter old=p.searchViewPager.dialogsSearchAdapter;p.isPaused=true;old.deliver(true);check(p.showCalls==0,"paused does not navigate");p.resume();check(p.showCalls==1,"original resume notify recovers still-focused intent");
            p=fresh();p.onSearchExpand();old=p.searchViewPager.dialogsSearchAdapter;p.rebuild();check(old.observers.isEmpty(),"rebuild unbinds old pager");old.deliver(true);check(p.showCalls==0,"old result cannot reopen rebuilt home");p.onSearchExpand();check(factories==2,"rebuilt root gets one new pager on demand");p.searchViewPager.dialogsSearchAdapter.deliver(true);check(p.showCalls==1,"new pager receives own result");old=p.searchViewPager.dialogsSearchAdapter;p.destroy();check(old.observers.isEmpty(),"destroy unbinds observer");old.deliver(true);check(p.showCalls==1,"destroyed result cannot navigate");p.destroy();
            p=fresh();p.onSearchExpand();DialogsSearchPreloadProbe q=p;p.fragmentSearchField.clearing=()->q.searchViewPager.dialogsSearchAdapter.deliver(true);p.onSearchCollapse();check(p.showCalls==0&&!p.searching,"close intent precedes reentrant filter notification");
            p=fresh();SearchTextWatcher watcher=new SearchTextWatcher(p.fragmentSearchField.editText,p);watcher.toggleSearch(true);p.fragmentSearchField.editText.text="typed";watcher.afterTextChanged(new Editable("typed"));check(p.searchIsShowed,"typing opens original search");p.fragmentSearchField.editText.text="";watcher.afterTextChanged(new Editable(""));int before=p.showCalls;p.searchViewPager.dialogsSearchAdapter.deliver(true);check(!watcher.isSearchExpanded()&&!p.searching&&!p.searchIsShowed&&p.showCalls==before,"real text watcher clear rejects late recent");
          }
          /** 显式文字、预置搜索和下载搜索不依赖预热；恢复后仍保持原选择。 */
          static void entries(){DialogsSearchPreloadProbe p=fresh();p.search("synthetic",false);check(factories==1&&p.searchIsShowed&&p.fragmentSearchField.editText.text.equals("synthetic"),"explicit search creates then sets query");p=fresh();p.searchString="preset";p.preset();check(factories==1&&p.searchIsShowed,"preset searchString creates synchronously");p=fresh();p.initialSearchString="restore";p.preset();check(factories==1&&p.searchIsShowed&&p.initialSearchString==null,"restored initial query creates synchronously");p=fresh();p.showSearch(true,true,false);check(factories==1&&p.searchViewPager.downloads==1,"first download entry creates and selects downloads");p=fresh();p.controller.count=11;p.onSearchExpand();check(p.showCalls==1,"large list retains immediate empty-search opening");}
          /** 分组用于证明旧代码两种独立失败；默认执行全部行为。 */
          public static void main(String[] args){String group=args.length==0?"all":args[0];if(group.equals("all")||group.equals("prewarm"))prewarm();if(group.equals("all")||group.equals("recent"))recent();if(group.equals("all")){boundaries();entries();}System.out.println("DialogsSearchPreloadTest "+checks+" checks passed ("+group+")");}
        """;
}
