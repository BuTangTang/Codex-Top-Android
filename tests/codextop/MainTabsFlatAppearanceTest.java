package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/** 原方法合成验收：玻璃来源关闭、长按恢复、隐藏和RTL；不操作设备或真实账号。 */
public final class MainTabsFlatAppearanceTest {
    private MainTabsFlatAppearanceTest() {}

    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path ui = root.resolve("TMessagesProj/src/main/java/org/telegram/ui");
        ClassOrInterfaceDeclaration activity = StaticJavaParser.parse(ui.resolve("MainTabsActivity.java")).getClassByName("MainTabsActivity").orElseThrow();
        ClassOrInterfaceDeclaration layout = StaticJavaParser.parse(ui.resolve("MainTabsLayout.java")).getClassByName("MainTabsLayout").orElseThrow();
        StringBuilder actualLayout = new StringBuilder();
        for (String name : new String[]{"setFlatAppearance", "setSkipDrawSelector", "findSelectedTab", "dispatchDraw", "drawFlatMarker"}) actualLayout.append(method(layout, name)).append('\n');
        StringBuilder actualActivity = new StringBuilder();
        for (String name : new String[]{"createTabsBackground", "drawCodexTabsBackground", "checkUi_fadeView", "checkUi_tabsPosition", "blur3_invalidateBlur", "blur3_updateFadeColors", "blur3_updateColors", "selectTab", "indexToPosition", "setGestureSelectedOverride", "onViewPagerTabAnimationUpdate"}) actualActivity.append(method(activity, name)).append('\n');
        ForStmt clicks = method(activity, "createView").findAll(ForStmt.class).stream().filter(v -> v.toString().contains("setOnClickListener")).findFirst().orElseThrow();
        actualActivity.append("void bindClicks(){").append(clicks).append("}\n");
        for (String name : new String[]{"drawFlatMarker", "drawCodexTabsBackground"}) {
            MethodDeclaration draw = method(name.equals("drawFlatMarker") ? layout : activity, name);
            if (!draw.findAll(ObjectCreationExpr.class).isEmpty() || draw.toString().contains("postInvalidateOnAnimation")) throw new AssertionError("底栏draw新增对象或逐帧调度: " + name);
        }
        String create = method(activity, "createView").toString();
        if (create.indexOf("tabsView.setFlatAppearance(codex)") < create.indexOf("selectTab(viewPager.getCurrentPosition(), false)")) throw new AssertionError("flat必须在全部原tab初始化后启用");
        if (!create.contains("tabsViewWrapper.setWillNotDraw(!codex)")) throw new AssertionError("非Codex仍应跳过wrapper绘图");
        IfStmt sourceGuard = activity.getConstructors().get(0).findAll(IfStmt.class).stream()
                .filter(v -> v.getCondition().toString().contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S")).findFirst().orElseThrow();
        if (!sourceGuard.getCondition().toString().contains("!com.butang.codextop.CodexRuntime.enabled()")) throw new AssertionError("Codex仍创建RenderNode来源");
        String sourceInit = "if (" + sourceGuard.getCondition() + ") {" + sourceGuard.getThenStmt().asBlockStmt().getStatement(0) + "} " + sourceGuard.getElseStmt().map(v -> "else " + v).orElse("");
        AssignExpr colorSource = activity.getConstructors().get(0).findAll(AssignExpr.class).stream().filter(v -> v.getTarget().toString().equals("iBlur3SourceColor")).findFirst().orElseThrow();
        sourceInit += colorSource + ";";
        String restore = layout.getFieldByName("restoreDrawSelector").orElseThrow().toString();
        String layoutCode = actualLayout.toString();
        String activityCode = actualActivity.toString();
        if (args.length > 1 && args[1].equals("selector")) {
            String mutated = layoutCode.replace("flatAppearance || skipDrawSelector", "skipDrawSelector");
            if (mutated.equals(layoutCode)) throw new AssertionError("selector mutation未命中");
            layoutCode = mutated;
        }
        if (args.length > 1 && args[1].equals("hidden")) {
            String mutated = activityCode.replace("if (factor <= 0f)", "if (factor < 0f)");
            if (mutated.equals(activityCode)) throw new AssertionError("hidden mutation未命中");
            activityCode = mutated;
        }
        String palette = Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
        Path temp = Files.createTempDirectory("codextop-flat-tabs-");
        try {
            put(temp, "org/telegram/ui/FlatTabsProbe.java", FIXTURE + "\nclass MainTabsLayout extends PlatformLayout {\n" + LAYOUT_FIELDS + restore + layoutCode + "}\nclass MainTabsActivity extends PlatformActivity {\n" + ACTIVITY_FIELDS + "void initSources(){" + sourceInit + "}" + activityCode + "}\n");
            put(temp, "org/telegram/ui/ActionBar/Theme.java", theme(palette));
            put(temp, "android/util/SparseIntArray.java", "package android.util; public class SparseIntArray {private final java.util.Map<Integer,Integer> a=new java.util.HashMap<>();public SparseIntArray(int n){}public void put(int k,int v){a.put(k,v);}public int get(int k){Integer v=a.get(k);return v==null?0:v;}}");
            put(temp, "com/butang/codextop/CodexRuntime.java", "package com.butang.codextop;public class CodexRuntime{public static boolean on=true;public static boolean enabled(){return on;}}");
            put(temp, "com/butang/codextop/CodexPixelPalette.java", palette);
            ArrayList<String> compile = new ArrayList<>();
            compile.add("--release"); compile.add("8"); compile.add("-d"); compile.add(temp.toString());
            try (var paths = Files.walk(temp)) { paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> compile.add(p.toString())); }
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0) throw new AssertionError("真实底栏方法平台夹具编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("org.telegram.ui.FlatTabsProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实底栏方法回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p); }
        }
    }

    private static MethodDeclaration method(ClassOrInterfaceDeclaration owner, String name) {
        return owner.getMethodsByName(name).stream().findFirst().orElseThrow(() -> new AssertionError("缺少真实生产方法 " + name));
    }
    private static void put(Path temp, String name, String source) throws Exception {
        Path file = temp.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, source);
    }
    private static String theme(String palette) {
        TreeSet<String> keys = new TreeSet<>(); var m = Pattern.compile("Theme\\.(key_[A-Za-z0-9_]+)").matcher(palette);
        while (m.find()) keys.add(m.group(1));
        StringBuilder result = new StringBuilder("package org.telegram.ui.ActionBar; import com.butang.codextop.CodexPixelPalette; public class Theme { public static class ResourcesProvider {} public static int style=2;");
        int n = 1; for (String key : keys) result.append("public static final int ").append(key).append('=').append(n++).append(';');
        return result + "public static CodexPixelPalette.Style getCodexPixelStyle(){return CodexPixelPalette.metadata(style);} public static int getColor(int key,Object ignored){return CodexPixelPalette.byStyle(style).get(key);} public static int multAlpha(int c,float a){return (c&0xffffff)|((Math.round((c>>>24)*a)&255)<<24);} }";
    }

    private static final String LAYOUT_FIELDS = """
        final Object resourcesProvider=null;boolean drawCustomSelector,flatAppearance;Paint flatMarkerPaint;Path flatMarkerPath;
        final Paint selectorPaint=new Paint();float animatedLongSelectedViewCenterX,animatedLongSelectedViewOffsetX;
        static int dp(float x){return AndroidUtilities.dp(x);}static float getInterpolatedWidthByX(float x,Object ignored){return 72;}
        void restore(){restoreDrawSelector.run();}void skip(boolean value){setSkipDrawSelector(value);}void draw(Canvas c){dispatchDraw(c);}
        """;
    private static final String ACTIVITY_FIELDS = """
        MainTabsLayout tabsView=new MainTabsLayout();GlassTabView[] tabs=new GlassTabView[3];View fadeView;
        FrameLayout tabsViewWrapper=new FrameLayout(),contentView=new FrameLayout(),fragmentView=new FrameLayout();
        BlurredBackgroundDrawable tabsViewBackground;Paint codexTabsBackgroundPaint;BlurredBackgroundSourceColor iBlur3SourceColor;
        BlurredBackgroundSourceRenderNode iBlur3SourceTabGlass;Object resourceProvider;ViewPager viewPager=new ViewPager();
        final BoolAnimator animatorTabsVisible=new BoolAnimator();final UpdateLayoutWrapper updateLayoutWrapper=new UpdateLayoutWrapper();
        static final int POSITION_PROFILE=3;boolean tabletLayout;int navigationBarHeight=24;
        interface TabFragmentDelegate {void onParentScrollToTop();}BaseFragment currentVisible;BaseFragment getCurrentVisibleFragment(){return currentVisible;}
        static int dp(float v){return AndroidUtilities.dp(v);}static float lerp(float a,float b,float f){return a+(b-a)*f;}
        int getThemedColor(int key){return Theme.getColor(key,null);}int getEstBackgroundColor(){return getThemedColor(Theme.key_windowBackgroundWhite);}
        void background(Canvas c){drawCodexTabsBackground(c);}void setup(){createTabsBackground(new Context());}
        void colors(){blur3_updateColors();}void hidden(){checkUi_tabsPosition();checkUi_fadeView();}void blur(){blur3_invalidateBlur();}
        """;

    private static final String FIXTURE = """
        package org.telegram.ui;
        import java.util.*;import org.telegram.ui.ActionBar.Theme;import com.butang.codextop.*;
        @interface NonNull {} class Context {}
        class Paint {static final int ANTI_ALIAS_FLAG=1;enum Style{FILL,STROKE}int color;float width=1;Style style=Style.FILL;
          Paint(){}Paint(int flags){}void setColor(int c){color=c;}void setStyle(Style s){style=s;}void setStrokeWidth(float w){width=w;}}
        class Path {final ArrayList<float[]> points=new ArrayList<>();void reset(){points.clear();}void moveTo(float x,float y){points.add(new float[]{x,y});}void lineTo(float x,float y){points.add(new float[]{x,y});}void close(){}}
        class Op {final String kind;final float[] bounds;final int color;final Paint.Style style;Op(String k,float[] b,Paint p){kind=k;bounds=b;color=p.color;style=p.style;}}
        class Canvas {final ArrayList<Op> ops=new ArrayList<>();int childDraws;void drawRect(float l,float t,float r,float b,Paint p){ops.add(new Op("rect",new float[]{l,t,r,b},p));}void drawRoundRect(float l,float t,float r,float b,float x,float y,Paint p){ops.add(new Op("round",new float[]{l,t,r,b},p));}void drawPath(Path path,Paint p){float[] b=new float[path.points.size()*2];for(int i=0;i<path.points.size();i++){b[i*2]=path.points.get(i)[0];b[i*2+1]=path.points.get(i)[1];}ops.add(new Op("path",b,p));}}
        class View {interface OnClickListener{void onClick(View v);}OnClickListener click;static final int VISIBLE=0,GONE=8;int visibility=VISIBLE,width=100,height=48,left,top=12,invalidations;float alpha=1,translationX,translationY;Object background;
          View(){}View(Context c){}int getVisibility(){return visibility;}void setVisibility(int v){visibility=v;}int getWidth(){return width;}int getHeight(){return height;}int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}float getX(){return left+translationX;}float getY(){return top+translationY;}void invalidate(){invalidations++;}void setAlpha(float a){alpha=a;}void setTranslationY(float y){translationY=y;}void setBackground(Object b){background=b;}void setClickable(boolean b){}void setEnabled(boolean b){}void setOnClickListener(OnClickListener c){click=c;}void performClick(){if(click!=null)click.onClick(this);}}
        class FrameLayout extends View {FrameLayout(){width=360;height=96;}}
        class PlatformLayout extends View {final ArrayList<View> children=new ArrayList<>();PlatformLayout(){height=72;width=344;}void addView(View v){children.add(v);}void setViewVisible(View v,boolean visible,boolean animated){v.visibility=visible?VISIBLE:GONE;}int getChildCount(){return children.size();}View getChildAt(int i){return children.get(i);}int getPaddingTop(){return 12;}int getPaddingBottom(){return 12;}protected void dispatchDraw(Canvas c){c.childDraws++;}}
        class GlassTabView extends View {boolean selected,skip;int colors;float gesture;boolean gestureAllowed;void setSkipDrawSelector(boolean s){skip=s;}void setSelected(boolean s,boolean animated){selected=s;}boolean isTabSelected(){return selected;}void updateColorsLottie(){colors++;}void setGestureSelectedOverride(float value,boolean allow){gesture=value;gestureAllowed=allow;}}
        class BoolAnimator {float factor=1;float getFloatValue(){return factor;}}
        class ViewPager {float position;boolean manual,touch;int scrolls;float getPositionAnimated(){return position;}boolean isManualScrolling(){return manual;}boolean isTouch(){return touch;}int getCurrentPosition(){return Math.round(position);}void scrollToPosition(int p){position=p;scrolls++;}}
        class BaseFragment {}class PlatformActivity{protected void onViewPagerTabAnimationUpdate(boolean manual){}}
        class UpdateLayoutWrapper {static final int HEIGHT=56;boolean isUpdateLayoutVisible(){return false;}}
        class MathUtils {static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}}
        class AndroidUtilities {static float density=1;static int dp(float v){return (int)Math.ceil(v*density);}static float dpf2(float v){return v*density;}static float getNavigationBarThirdButtonsFactor(float a,float b,int h){return 1;}}
        class DialogsActivity {static final int MAIN_TABS_HEIGHT=56,MAIN_TABS_MARGIN=8;}
        class LiteMode {static final int FLAG_LIQUID_GLASS=1;static boolean isEnabled(int f){return true;}}
        class Build {static class VERSION {static int SDK_INT=31;}static class VERSION_CODES {static final int S=31;}}
        class Allocations {static int factories,watchers,sources,nodes,fades;static void clear(){factories=watchers=sources=nodes=fades=0;}}
        class BlurredBackgroundSourceColor {int updates;BlurredBackgroundSourceColor(){Allocations.sources++;}void setColor(int c){updates++;}}
        class BlurredBackgroundSourceRenderNode {int updates;BlurredBackgroundSourceRenderNode(Object o){Allocations.nodes++;}void setSize(int w,int h){}void updateDisplayListIfNeeded(){updates++;}}
        class ViewPositionWatcher {ViewPositionWatcher(Object o){Allocations.watchers++;}}
        class BlurredBackgroundDrawable {int updates;void setRadius(float f){}void setPadding(float f){}void updateColors(){updates++;}}
        class BlurredBackgroundDrawableViewFactory {BlurredBackgroundDrawableViewFactory(Object o){Allocations.factories++;}void setSourceRootView(Object w,Object c){}void setLiquidGlassEffectAllowed(boolean b){}BlurredBackgroundDrawable create(Object v,Object p){return new BlurredBackgroundDrawable();}}
        class BlurredBackgroundProviderImpl {static Object mainTabs(Object r){return null;}}
        class BlurredBackgroundWithFadeDrawable {BlurredBackgroundWithFadeDrawable(Object d){Allocations.fades++;}void setFadeHeight(float h,boolean b){}}

        public final class FlatTabsProbe {
          static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}static void close(float x,float y,String s){check(Math.abs(x-y)<.01f,s);}
          static MainTabsActivity activity(){MainTabsActivity a=new MainTabsActivity();for(int i=0;i<3;i++){a.tabs[i]=new GlassTabView();a.tabs[i].left=12+i*106;}a.bindClicks();a.initSources();return a;}
          static class TopFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate{int tops;public void onParentScrollToTop(){tops++;}}
          static double lum(int c){double[] p={(c>>16)&255,(c>>8)&255,c&255};for(int i=0;i<3;i++){p[i]/=255;p[i]=p[i]<=.04045?p[i]/12.92:Math.pow((p[i]+.055)/1.055,2.4);}return p[0]*.2126+p[1]*.7152+p[2]*.0722;}
          static double contrast(int a,int b){double x=lum(a),y=lum(b);return(Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
          public static void main(String[] args){
            CodexRuntime.on=true;Allocations.clear();MainTabsActivity a=activity();a.setup();a.tabsView.setFlatAppearance(true);
            check(Allocations.nodes==0&&Allocations.sources==0&&Allocations.factories==0&&Allocations.watchers==0&&Allocations.fades==0,"Codex创建玻璃来源");
            check(a.tabsViewBackground==null&&a.tabsView.background==null&&a.fadeView.getVisibility()==View.GONE,"Codex背景未关闭");
            boolean initialSkip=true;for(GlassTabView t:a.tabs)initialSkip&=t.skip;
            a.tabsView.skip(true);a.tabsView.restore();for(GlassTabView t:a.tabs)check(t.skip,"450ms恢复后子胶囊回归");
            check(initialSkip,"初次flat子胶囊未关闭");
            check(!a.tabsView.drawCustomSelector,"长按父胶囊回归");
            TopFragment page=new TopFragment();a.currentVisible=page;a.tabs[0].performClick();check(page.tops==1&&a.viewPager.scrolls==0,"重复点击当前栏没有原回顶");
            a.tabs[1].performClick();check(a.viewPager.getCurrentPosition()==1&&a.viewPager.scrolls==1&&a.tabs[1].selected,"原切页点击路径改变");
            a.viewPager.manual=true;a.tabs[2].performClick();check(a.viewPager.scrolls==1,"手动滚动守卫改变");a.viewPager.manual=false;a.viewPager.touch=true;a.tabs[2].performClick();check(a.viewPager.scrolls==1,"触摸切页守卫改变");a.viewPager.touch=false;
            a.viewPager.position=1.7f;a.onViewPagerTabAnimationUpdate(false);check(a.tabs[2].selected&&a.tabs[2].gestureAllowed,"原gesture选择路径改变");a.onViewPagerTabAnimationUpdate(true);check(!a.tabs[2].gestureAllowed,"原manual覆盖状态改变");
            for(int id:new int[]{1,2,3,4,6}){
              Theme.style=id;a.selectTab(1,false);Canvas c=new Canvas();a.tabsView.draw(c);check(c.childDraws==1,"原子控件未绘制");check(!c.ops.isEmpty(),"五主题缺选中标记");
              for(Op op:c.ops){check(!op.kind.equals("round"),"flat仍画胶囊");check(op.bounds[0]>=a.tabs[1].getX(),"标记没有跟随物理child");}
              check(a.tabsView.flatMarkerPaint.style==Paint.Style.FILL,"Paint描边未复位");
            }
            Theme.style=4;Canvas green=new Canvas();a.tabsView.draw(green);Op fill=green.ops.get(0);check(fill.color==CodexPixelPalette.metadata(4).accentFill,"04绿块色错误");
            check(contrast(Theme.getColor(Theme.key_glass_tabSelected,null),fill.color)>=4.5&&contrast(Theme.getColor(Theme.key_glass_tabSelectedText,null),fill.color)>=4.5,"04已选文字图标对比度不足");
            int invalidations=a.tabsView.invalidations;a.selectTab(2,false);check(a.tabsView.invalidations>invalidations,"切页没有重画父标记");
            for(int i=0;i<3;i++)check(a.tabs[i].selected==(i==2),"原三栏selected映射改变");
            a.tabs[2].left=12;a.tabs[0].left=224;a.tabs[2].translationX=3;Canvas rtl=new Canvas();a.tabsView.draw(rtl);close(rtl.ops.get(0).bounds[0],19,"RTL未使用实际child bounds");
            a.tabs[2].visibility=View.GONE;Canvas gone=new Canvas();a.tabsView.draw(gone);check(gone.ops.isEmpty(),"GONE child仍画选中");a.tabs[2].visibility=View.VISIBLE;
            a.tabs[2].width=0;Canvas zero=new Canvas();a.tabsView.draw(zero);check(zero.ops.isEmpty(),"零宽child仍画标记");a.tabs[2].width=100;
            a.animatorTabsVisible.factor=0;Canvas hidden=new Canvas();a.background(hidden);check(hidden.ops.isEmpty(),"隐藏factor=0仍画平底");
            a.animatorTabsVisible.factor=1;a.tabsView.visibility=View.GONE;Canvas hiddenView=new Canvas();a.background(hiddenView);check(hiddenView.ops.isEmpty(),"GONE底栏仍画平底");a.tabsView.visibility=View.VISIBLE;
            a.animatorTabsVisible.factor=.5f;Canvas partial=new Canvas();a.background(partial);check(partial.ops.size()==2,"半隐藏背景缺填充/分隔");check((partial.ops.get(0).color>>>24)==128,"背景未复用原factor");
            a.hidden();check(a.fadeView.visibility==View.GONE,"原检查又显示fade");int wrapperDraws=a.tabsViewWrapper.invalidations;a.animatorTabsVisible.factor=0;a.hidden();check(a.tabsView.visibility==View.GONE&&a.tabsViewWrapper.invalidations>wrapperDraws,"背景隐藏未同步重画");
            BlurredBackgroundSourceRenderNode node=new BlurredBackgroundSourceRenderNode(null);a.iBlur3SourceTabGlass=node;a.blur();check(node.updates==0,"Codex仍更新RenderNode");
            int colorCount=a.tabs[0].colors,rootPaints=a.tabsViewWrapper.invalidations;a.colors();check(a.tabs[0].colors==colorCount+1&&a.tabsViewWrapper.invalidations>rootPaints,"换主题未沿原委托刷新");
            CodexRuntime.on=false;Allocations.clear();MainTabsActivity legacy=activity();legacy.setup();legacy.tabsView.setFlatAppearance(false);
            check(Allocations.nodes==1&&Allocations.sources==1&&Allocations.factories==2&&Allocations.watchers==1&&Allocations.fades==1,"非Codex原玻璃来源改变");
            check(legacy.tabsViewBackground!=null&&legacy.fadeView.background!=null,"非Codex背景改变");
            legacy.tabsView.skip(true);Canvas old=new Canvas();legacy.tabsView.draw(old);check(old.ops.size()==1&&old.ops.get(0).kind.equals("round"),"非Codex父胶囊改变");legacy.tabsView.restore();for(GlassTabView t:legacy.tabs)check(!t.skip,"非Codex原恢复改变");
            Canvas oldBg=new Canvas();legacy.background(oldBg);check(oldBg.ops.isEmpty(),"非Codex增加平底");legacy.blur();check(legacy.iBlur3SourceTabGlass.updates==1,"非Codex原blur未更新");
            for(int sdk:new int[]{30,31,35}){Build.VERSION.SDK_INT=sdk;CodexRuntime.on=true;Allocations.clear();MainTabsActivity x=activity();x.setup();check(Allocations.nodes+Allocations.sources+Allocations.factories+Allocations.fades==0,"不同SDK创建Codex玻璃");}
            AndroidUtilities.density=3;CodexRuntime.on=true;Theme.style=6;a.animatorTabsVisible.factor=1;a.tabsView.visibility=View.VISIBLE;Canvas dense=new Canvas();a.tabsView.draw(dense);check(dense.ops.get(0).style==Paint.Style.STROKE,"06边框缺失");check(a.tabsView.flatMarkerPaint.width>=2,"轮廓未按density缩放");
            System.out.println("PASS "+checks+" assertions; actual tabs/background/restore/select methods; platform boundary only");
          }
        }
        """;
}
