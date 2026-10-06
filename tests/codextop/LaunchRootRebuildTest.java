package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行实际冷启动捕获与导航重建方法；仅 Android 视图及账号边界使用合成数据，不读取真实会话。 */
public final class LaunchRootRebuildTest {
    private static final Path UI = Path.of("TMessagesProj/src/main/java/org/telegram/ui");

    /** 可传入冻结前像；相同普通 ACTION_MAIN 行为断言必须在前像失败、修后通过。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source = args.length == 0 ? UI.resolve("LaunchActivity.java") : Path.of(args[0]);
        String original = Files.readString(source);
        var launch = StaticJavaParser.parse(original).getClassByName("LaunchActivity").orElseThrow();
        var nav = owner("ActionBar/ActionBarLayout.java", "ActionBarLayout");
        var contract = owner("ActionBar/INavigationLayout.java", "INavigationLayout");
        var base = owner("ActionBar/BaseFragment.java", "BaseFragment");
        var pager = owner("ViewPagerActivity.java", "ViewPagerActivity");
        StringBuilder actual = new StringBuilder(FIXTURE);
        actual.append("void createRoot(Bundle savedInstanceState) {\n");
        boolean copying = false, finished = false;
        for (Statement statement : method(launch, "onCreate").getBody().orElseThrow().getStatements()) {
            if (statement.toString().contains("LiteMode.addOnPowerSaverAppliedListener")) { copying = true; continue; }
            if (!copying) continue;
            actual.append(statement).append('\n');
            if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isMethodCallExpr()
                    && statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("handleIntent")) { finished = true; break; }
        }
        if (!finished) throw new AssertionError("实际 onCreate 首次根创建到 handleIntent 边界缺失");
        actual.append("}\n").append(method(launch, "getClientNotActivatedFragment"));
        // 保留 handleIntent 的真实普通启动收尾分支与 action 消费；其他深链/密码业务不在此合成 Android 边界执行。
        actual.append("boolean handleIntent(Intent intent, boolean isNew, boolean restore, boolean fromPassword, Object progress, boolean rebuildFragments, boolean openedTelegram) { handleCalls++; passedRebuild=rebuildFragments; passedRestore=restore; if(isNew || fromPassword || progress!=null || !openedTelegram) throw new AssertionError(\"调用参数改变\"); boolean pushOpened=false; String searchQuery=null;\n");
        boolean tail = false, consume = false;
        for (Statement statement : method(launch, "handleIntent").getBody().orElseThrow().getStatements()) {
            if (statement.isIfStmt() && statement.asIfStmt().getCondition().toString().equals("!pushOpened && !isNew")) {
                actual.append(statement); tail = true;
            }
            if (statement.toString().equals("intent.setAction(null);")) { actual.append(statement); consume = true; }
        }
        if (!tail || !consume) throw new AssertionError("实际 handleIntent 重建或 action 消费边界缺失 tail=" + tail + " consume=" + consume);
        actual.append("return pushOpened; }\n");
        actual.append("interface INavigationLayout { int FORCE_NOT_ATTACH_VIEW=-2, FORCE_ATTACH_VIEW_AS_FIRST=-3, REBUILD_FLAG_REBUILD_LAST=1, REBUILD_FLAG_REBUILD_ONLY_LAST=2; boolean addFragmentToStack(BaseFragment f,int p); void rebuildAllFragmentViews(boolean last,boolean show); void showLastFragment();\n");
        actual.append(method(contract, "rebuildFragments"));
        actual.append(contract.getMethodsByName("addFragmentToStack").stream().filter(m -> m.getParameters().size()==1).findFirst().orElseThrow());
        actual.append("}\n");
        actual.append(NAV_FIELDS);
        for (String name : new String[]{"addFragmentToStack", "attachView", "bringToFront", "showLastFragment", "rebuildAllFragmentViews"}) actual.append(method(nav, name));
        actual.append("}\n").append(BASE_FIELDS).append(method(base, "clearViews")).append("}\n");
        actual.append(PAGER_FIELDS).append(method(pager, "clearViews")).append(method(pager, "createActionBar")).append("}\n");
        actual.append(STUBS).append(SCENARIOS).append("}\nclass CodexRuntime {static boolean enabled=true,logged=true;static boolean enabled(){return enabled;}static boolean loggedIn(){return logged;}}\n");
        Path temp = Files.createTempDirectory("codex-launch-root-");
        try {
            Path fixture = temp.resolve("LaunchRootProbe.java");
            Files.writeString(fixture, actual);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temp.toString(), fixture.toString()) != 0)
                throw new AssertionError("真实启动/导航方法夹具未编译");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.LaunchRootProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("真实冷启动方法行为失败", failure.getCause()); }
            }
            if (!Files.readString(source).equals(original)) throw new AssertionError("测试期间源码改变");
        } finally {
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** 读取实际导航类；测试不复制生产判断条件。 */
    private static ClassOrInterfaceDeclaration owner(String path, String name) throws Exception {
        return StaticJavaParser.parse(UI.resolve(path)).findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(value -> value.getNameAsString().equals(name)).findFirst().orElseThrow();
    }

    /** 保留方法体，只去除不参与合成环境编译的注解。 */
    private static MethodDeclaration method(ClassOrInterfaceDeclaration owner, String name) {
        var result = owner.getMethodsByName(name).stream()
                .max(Comparator.comparingInt(value -> value.getParameters().size())).orElseThrow().clone();
        result.getAnnotations().clear();
        return result;
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public final class LaunchRootProbe {
            static int checks;
            int currentAccount, handleCalls; boolean passedRebuild,passedRestore;
            Nav actionBarLayout=new Nav(),layersActionBarLayout=new Nav(),rightActionBarLayout=new Nav();
            Intent input=new Intent(Intent.ACTION_MAIN),passcodeSaveIntent;
            Runnable layoutHook=()->{};
            /** 合成入口对象每次都使用安装包图标的 ACTION_MAIN，消费后不重复利用。 */
            Intent getIntent(){return input;}
            /** 在原布局边界模拟栈变化，验证不能复用被替换或脱离挂载的根。 */
            void checkLayout(){layoutHook.run();}
            void checkSystemBarColors(){}
        """;
    private static final String NAV_FIELDS = """
            static class Nav extends View implements INavigationLayout {
                List<BaseFragment> fragmentsStack=new ArrayList<>();
                Delegate delegate=new Delegate(); Object parentActivity=new Context();
                ViewGroup containerView=new ViewGroup(); View backgroundView;
                boolean transitionAnimationInProgress,startedTracking,rebuildAfterAnimation,rebuildLastAfterAnimation,showLastAfterAnimation,inPreviewMode,useAlphaAnimations,removeActionBarExtraHeight;
                String titleOverlayText;int titleOverlayTextId;Runnable overlayAction;ActionBar currentActionBar;
                List<BaseFragment> getFragmentStack(){return fragmentsStack;}
                void onFragmentStackChanged(String action){}
                void attachViewTo(BaseFragment fragment,int position){throw new AssertionError("非本测试路径");}
            """;
    private static final String BASE_FIELDS = """
            static class BaseFragment {
                View fragmentView;ActionBar actionBar;Nav parentLayout;boolean hasOwnBackground;
                int creates,resumes,visible,fragmentCreates,pauses;
                boolean onFragmentCreate(){fragmentCreates++;return true;}
                void setParentLayout(Nav nav){parentLayout=nav;}
                View performCreateView(Object context){creates++;fragmentView=new View();actionBar=createActionBar((Context)context);return fragmentView;}
                ActionBar createActionBar(Context c){return new ActionBar();}
                View getFragmentView(){return fragmentView;}
                boolean isSupportEdgeToEdge(){return false;}boolean drawEdgeNavigationBar(){return false;}int getEdgeToEdgeSupportMode(){return 0;}
                Object onInsetsInternal(Object view,Object insets){return insets;}
                void onPause(){pauses++;}void onResume(){resumes++;}void onTransitionAnimationEnd(boolean a,boolean b){}void onBecomeFullyVisible(){visible++;}
                void onRemoveFromParent(){}void detachSheets(){}void attachSheets(ViewGroup parent){}void clearSheets(){}
                void setTitleOverlayTextIfActionBarAttached(String a,int b,Runnable c){}void restoreSelfArgs(Bundle b){}
            """;
    private static final String PAGER_FIELDS = """
            static class PagerFragment extends BaseFragment {
                Pager viewPager; int initialFragmentPosition=-1;
                SparseStates fragmentsArr=new SparseStates();
                BaseFragment child=new BaseFragment();
                PagerFragment(){fragmentsArr.values.add(new FragmentState(child));}
                /** 合成当前页渲染边界；真实导航 clearViews 和无 actionBar 方法在下方提取。 */
                @Override View performCreateView(Object context){super.performCreateView(context);viewPager=new Pager();if(child.fragmentView==null)child.performCreateView(context);return fragmentView;}
            """;
    private static final String STUBS = """
            static class AndroidUtilities {static boolean tablet;static boolean isTablet(){return tablet;}static void removeFromParent(View v){if(v.parent!=null)v.parent.removeView(v);}}
            static class SharedConfig {static String passcodeHash="";static boolean appLocked,isWaitingForPasscodeEnter;}
            static class UserConfig {static boolean activated=true;long clientUserId=42;static UserConfig getInstance(int account){return new UserConfig();}boolean isClientActivated(){return activated;}}
            static class MainTabsActivity extends PagerFragment {DialogsActivity prepareDialogsActivity(Object o){return new DialogsActivity();}}
            static class LoginActivity extends BaseFragment {static Bundle loadCurrentState(boolean b,int a){return new Bundle();}}
            static class IntroActivity extends BaseFragment {}
            static class DialogsActivity extends BaseFragment {void setInitialSearchString(String s){}}
            static class ChatActivity extends BaseFragment {ChatActivity(Bundle args){}}
            static class ProfileActivity extends BaseFragment {ProfileActivity(Bundle args){}}
            static class SettingsActivity extends BaseFragment {}
            static class GroupCreateFinalActivity extends BaseFragment {GroupCreateFinalActivity(Bundle args){}}
            static class ChannelCreateActivity extends BaseFragment {ChannelCreateActivity(Bundle args){}}
            static class WallpapersListActivity extends BaseFragment {static int TYPE_ALL;WallpapersListActivity(int t){}}
            static class Context {}
            static class Bundle extends HashMap<String,Object> {String getString(String k){return (String)get(k);}Bundle getBundle(String k){return (Bundle)get(k);}int getInt(String k,int fallback){return containsKey(k)?(Integer)get(k):fallback;}void putLong(String k,long v){put(k,v);}}
            static class Intent {static final String ACTION_MAIN="android.intent.action.MAIN";String action;Object data;Bundle extras;int flags=0x10200000;Intent(String a){action=a;}String getAction(){return action;}Object getData(){return data;}Bundle getExtras(){return extras;}void setAction(String a){action=a;}}
            static class View {static final int VISIBLE=0;ViewGroup parent;Object background;Object getParent(){return parent;}Object getBackground(){return background;}void setBackgroundColor(int color){background=color;}void setVisibility(int v){}}
            static class ViewGroup extends View {void addView(View v){v.parent=this;}void addView(View v,Object params){addView(v);}void removeView(View v){v.parent=null;}void removeViewInLayout(View v){removeView(v);}void invalidate(){}void setShouldHandleBottomInsets(int n){}void setDrawNavigationBar(boolean b){}}
            static class ActionBar extends View {boolean shouldAddToContainer(){return true;}void setOccupyStatusBar(boolean b){}}
            static class Delegate {boolean allow=true;boolean needAddFragmentToStack(BaseFragment f,Nav n){return allow;}void onRebuildAllFragments(Nav n,boolean b){}}
            static class LayoutHelper {static final int MATCH_PARENT=-1;static Object createFrame(int a,int b){return null;}}
            static class Theme {static final int key_windowBackgroundWhite=1;static int getColor(int key){return 0;}}
            interface InsetsListener {Object apply(Object view,Object insets);}
            static class ViewCompat {static void setOnApplyWindowInsetsListener(View v,InsetsListener l){}}
            static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
            static class Pager {int getCurrentPosition(){return 0;}}
            static class FragmentState {BaseFragment fragment;boolean isResumed;FragmentState(BaseFragment f){fragment=f;}}
            static class SparseStates {List<FragmentState> values=new ArrayList<>();int size(){return values.size();}FragmentState valueAt(int i){return values.get(i);}}
        """;
    private static final String SCENARIOS = """
            /** 每个用例重建独立合成账号和入口，不能复用已被消费成 null 的 action 来计时。 */
            static LaunchRootProbe fresh(){CodexRuntime.enabled=true;CodexRuntime.logged=true;AndroidUtilities.tablet=false;UserConfig.activated=true;SharedConfig.passcodeHash="";SharedConfig.appLocked=false;SharedConfig.isWaitingForPasscodeEnter=false;return new LaunchRootProbe();}
            /** 累计行为断言并保留具体失败场景。 */
            static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
            /** 验证真实调用未被跳过、原恢复参数不变且 Intent 仍由原收尾消费。 */
            static void finish(LaunchRootProbe p,Bundle saved,boolean expected,String name){p.createRoot(saved);check(p.handleCalls==1,name+": handleIntent must execute exactly once");check(p.passedRebuild==expected,name+": rebuild flag");check(p.passedRestore==(saved!=null),name+": restore flag");check(p.input.action==null,name+": original intent must be consumed");}
            /** 普通图标冷启既要少创建一次，也要保留实际首次挂载和 MainTabs 无 actionBar 的语义。 */
            static void normal(boolean activated,boolean emptyExtras){LaunchRootProbe p=fresh();UserConfig.activated=activated;if(emptyExtras)p.input.extras=new Bundle();p.createRoot(null);BaseFragment f=p.actionBarLayout.fragmentsStack.get(0);check(f.creates==1,"normal ACTION_MAIN root must be created once, actual="+f.creates);check(f instanceof MainTabsActivity,"normal root type");check(((MainTabsActivity)f).child.creates==1,"current child view created once");check(f.fragmentCreates==1&&f.resumes==1&&f.visible==1,"first lifecycle preserved");check(f.fragmentView.parent!=null,"first root attached");check(f.actionBar==null&&p.actionBarLayout.currentActionBar==null,"actual ViewPager has no actionBar");check(p.handleCalls==1&&!p.passedRebuild&&p.input.action==null,"handleIntent called and ACTION_MAIN consumed");}
            /** 单变量负例执行真实 onCreate 判断及真实导航收尾；密码/深链业务的提前返回另由全方法差分保护。 */
            public static void main(String[] args){
                normal(true,false);normal(false,false);normal(true,true);normal(true,false);
                LaunchRootProbe p=fresh();CodexRuntime.enabled=false;finish(p,null,true,"ordinary Telegram");check(p.actionBarLayout.fragmentsStack.get(0).creates==2,"Telegram retains actual second create");
                p=fresh();AndroidUtilities.tablet=true;finish(p,null,true,"tablet");
                p=fresh();finish(p,new Bundle(),true,"saved state");
                p=fresh();Bundle saved=new Bundle();saved.put("fragment","settings2");finish(p,saved,true,"restored settings");check(p.actionBarLayout.fragmentsStack.size()==2,"restore stack retained");
                p=fresh();p.actionBarLayout.addFragmentToStack(new MainTabsActivity());finish(p,null,true,"existing stack");
                p=fresh();p.layersActionBarLayout.addFragmentToStack(new BaseFragment());finish(p,null,true,"existing layers");
                p=fresh();p.input.action=null;finish(p,null,true,"null action is not launcher benchmark");
                p=fresh();p.input.action="android.intent.action.VIEW";p.input.data="synthetic://target";finish(p,null,true,"deep link");
                p=fresh();p.input.action="android.intent.action.SEND";finish(p,null,true,"share");
                p=fresh();p.input.data="synthetic://target";finish(p,null,true,"main with data");
                p=fresh();p.input.extras=new Bundle();p.input.extras.put("currentAccount",1);finish(p,null,true,"account extra");
                p=fresh();p.input.extras=new Bundle();p.input.extras.put("custom",true);finish(p,null,true,"unknown extra");
                p=fresh();SharedConfig.passcodeHash="synthetic";finish(p,null,true,"passcode configured");
                p=fresh();SharedConfig.appLocked=true;finish(p,null,true,"app locked");
                p=fresh();SharedConfig.isWaitingForPasscodeEnter=true;finish(p,null,true,"waiting for passcode");
                p=fresh();p.passcodeSaveIntent=new Intent(Intent.ACTION_MAIN);finish(p,null,true,"saved passcode intent");
                p=fresh();UserConfig.activated=false;CodexRuntime.logged=false;finish(p,null,true,"login root");check(p.actionBarLayout.fragmentsStack.get(0) instanceof LoginActivity,"login kept");check(p.actionBarLayout.currentActionBar!=null,"non-MainTabs original rebuild assigns actionBar");
                p=fresh();p.actionBarLayout.delegate.allow=false;finish(p,null,true,"add rejected");
                final LaunchRootProbe missing=fresh();missing.layoutHook=()->missing.actionBarLayout.fragmentsStack.get(0).fragmentView=null;finish(missing,null,true,"missing view");
                final LaunchRootProbe detached=fresh();detached.layoutHook=()->detached.actionBarLayout.fragmentsStack.get(0).fragmentView.parent=null;finish(detached,null,true,"detached view");
                final LaunchRootProbe extra=fresh();extra.layoutHook=()->extra.actionBarLayout.addFragmentToStack(new BaseFragment());finish(extra,null,true,"second root");
                final LaunchRootProbe replaced=fresh();replaced.layoutHook=()->{replaced.actionBarLayout.fragmentsStack.clear();replaced.actionBarLayout.addFragmentToStack(new MainTabsActivity());};finish(replaced,null,true,"root identity replaced");
                System.out.println("PASS LaunchRootRebuildTest: "+checks+" assertions; actual onCreate/navigation methods; ACTION_MAIN positive and negative guards");
            }
        """;
}
