package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/**
 * 编译实际 ActionBar 绘制、颜色缓存、plain/glass、搜索和聊天短接线，生产方法按 Java 8 执行。
 * 确定性的 View/Canvas/Animator 替身只验证行为边界，不代表 Android 透明度或实际截图。
 * javac -d "$TEST_CLASSES" tests/codextop/BlueprintHeaderLineTest.java
 * java -Xmx64m -cp "$TEST_CLASSES" com.butang.codextop.BlueprintHeaderLineTest
 * 第二参数 width/style/search-exit/action-exit/preview/cache/draw-allocation 可执行编译后的行为负例。
 */
public final class BlueprintHeaderLineTest {
    private static final String BAR_SHA = "d42b8361c189f0780b6787b5c0f58b78b3da884b76ee9cca4112422287ef2358";
    private static final String CHAT_SHA = "5bcc111b20576818e1e3c12173e908ab469e0b1a7bfe87c704468fba63df763b";
    private static final String PREVIEW = "            actionBar.setCodexPlainChatHeaderDividerVisible(codexPlainHeader && !value);\n";
    private static final String REFRESH = "                actionBar.updateColors();\n";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path ui = root.resolve("TMessagesProj/src/main/java/org/telegram/ui");
        String bar = Files.readString(ui.resolve("ActionBar/ActionBar.java"));
        String chat = Files.readString(ui.resolve("ChatActivity.java"));
        check(BAR_SHA.equals(sha(protectedBar(bar))), "前片 ActionBar 测量/布局/动画/共享用途字节改变");
        check(CHAT_SHA.equals(sha(protectedChat(chat))), "前片 ChatActivity 普通门/输入/传输/生命周期字节改变");
        String setup = method(bar, "    public void setupPlainChatHeader(int backgroundColor)");
        String visibility = method(bar, "    public void setCodexPlainChatHeaderDividerVisible(boolean visible)");
        String cache = method(bar, "    private void updateCodexPlainChatHeaderDividerColors()");
        String glass = method(bar, "    public void setupGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider)")
                + method(bar, "    public void setupGlass(BlurredBackgroundDrawableViewFactory factory,\n");
        String draw = method(bar, "    protected void dispatchDraw(Canvas canvas)");
        String theme = method(bar, "    public void updateColors()");
        String search = method(bar, "    public void onSearchFieldVisibilityChanged(boolean visible)");
        String factor = method(bar, "    public float getActionModeFactor()");
        String nativeActionModeHidden = statement(method(bar, "    public ActionBarMenu createActionMode(boolean needTop, String tag)"), "        actionMode.setVisibility(INVISIBLE);");
        String gate = statement(chat, "        codexPlainHeader = com.butang.codextop.CodexRuntime.enabled()");
        String bind = block(chat, "        if (codexPlainHeader) {", chat.indexOf("public View createView(Context context)"));
        int elseStart = chat.indexOf(" else {", chat.indexOf(bind) + bind.length());
        check(elseStart >= 0, "真实普通顶栏 else 缺失");
        bind += " " + block(chat, "else {", elseStart);
        String preview = statement(chat, PREVIEW.stripLeading().stripTrailing());
        String themeHook = block(chat, "            if (codexPlainHeader && actionBar != null) {", chat.indexOf("// 主题刷新只换完整背景色"));
        String mutation = args.length > 1 ? args[1] : "";
        switch (mutation) {
            case "": break;
            case "width": draw = once(draw, "getWidth(), getHeight(), codexPlainChatHeaderDividerPaint", "getWidth() - dp(52), getHeight(), codexPlainChatHeaderDividerPaint"); break;
            case "style": draw = once(draw, "&& codexPlainChatHeaderStyleId == com.butang.codextop.CodexPixelPalette.STYLE_BLUEPRINT", ""); break;
            case "search-exit": draw = once(draw, " && searchFieldVisibleAlpha == 0f && searchFactor == 0f", ""); draw = once(draw, "            && (searchVisibleAnimator == null || !searchVisibleAnimator.isRunning())\n", ""); break;
            case "action-exit": draw = once(draw, " && (actionMode == null || actionMode.getVisibility() != View.VISIBLE || actionModeFactor == 0f) && actionModeAnimation == null", ""); break;
            case "preview": preview = once(preview, "codexPlainHeader && !value", "codexPlainHeader"); break;
            case "cache": theme = once(theme, "        updateCodexPlainChatHeaderDividerColors();\n", ""); break;
            case "draw-allocation": draw = once(draw, "        super.dispatchDraw(canvas);", "        super.dispatchDraw(canvas);\n        new Paint(Paint.ANTI_ALIAS_FLAG);"); break;
            default: throw new AssertionError("未知负例 " + mutation);
        }
        String palette = Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        var match = Pattern.compile("Theme\\.(key_[A-Za-z0-9_]+)").matcher(palette + setup + glass + themeHook);
        while (match.find()) keys.add(match.group(1));
        StringBuilder themeSource = new StringBuilder("package org.telegram.ui.ActionBar;import com.butang.codextop.CodexPixelPalette;public class Theme{public interface ResourcesProvider{}public static int id=6,reads;public static boolean forbid;public static CodexPixelPalette.Style getCodexPixelStyle(){if(forbid)throw new AssertionError(\"draw queried Theme\");reads++;return CodexPixelPalette.metadata(id,false);}public static int getColor(int k,ResourcesProvider p){if(forbid)throw new AssertionError(\"draw queried Theme color\");return k;}");
        int index = 1; for (String key : keys) themeSource.append("public static final int ").append(key).append('=').append(index++).append(';'); themeSource.append('}');
        Path temp = Files.createTempDirectory("codex-blueprint-header-");
        try {
            Path source = temp.resolve("BlueprintHeaderProbe.java");
            Files.writeString(source, FIXTURE + setup + visibility + cache + glass + draw + theme + search + factor + "void primeActionMode(){actionMode=new ActionBarMenu();" + nativeActionModeHidden + "}\n}\nstatic class Chat {ActionBar actionBar=new ActionBar();boolean codexPlainHeader,inPreviewMode,inBubbleMode,isInsideContainer,isComments,report;int chatMode,startLoadFromDate;long dialog_id=42;Object currentChat,themeDelegate;BlurredBackgroundDrawableViewFactory glassBackgroundDrawableFactory=new BlurredBackgroundDrawableViewFactory();boolean isReport(){return report;}Theme.ResourcesProvider getResourceProvider(){return null;}void bind(){" + gate + bind + "}void preview(boolean value){inPreviewMode=value;" + preview + "}void refresh(){" + themeHook + "}}\n" + SCENARIOS + "}\n");
            Path themeFile = temp.resolve("org/telegram/ui/ActionBar/Theme.java"); Files.createDirectories(themeFile.getParent()); Files.writeString(themeFile, themeSource);
            Path sparse = temp.resolve("android/util/SparseIntArray.java"); Files.createDirectories(sparse.getParent()); Files.writeString(sparse, "package android.util;public class SparseIntArray{public SparseIntArray(int n){}public void put(int k,int v){}}");
            Path paletteFile = temp.resolve("com/butang/codextop/CodexPixelPalette.java"); Files.createDirectories(paletteFile.getParent()); Files.writeString(paletteFile, palette);
            Path runtime = temp.resolve("com/butang/codextop/CodexRuntime.java"); Files.writeString(runtime, "package com.butang.codextop;public class CodexRuntime{public static boolean enabled=true,owns=true;public static boolean enabled(){return enabled;}public static boolean ownsConversation(long id){return owns&&id==42;}}");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temp.toString(), source.toString(), themeFile.toString(), sparse.toString(), paletteFile.toString(), runtime.toString()) == 0, "真实方法 Java 8 夹具编译失败");
            try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.BlueprintHeaderProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实方法行为失败", error.getCause()); }
            }
        } finally { try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); } }
    }

    /** 只在保护副本逆剥离本片，当前完整方法始终用于编译。 */
    public static String protectedBar(String source) {
        for (String field : new String[]{"    private boolean codexPlainChatHeaderDividerVisible;\n", "    private int codexPlainChatHeaderStyleId;\n", "    private Paint codexPlainChatHeaderDividerPaint;\n"}) source = once(source, field, "");
        int start = source.indexOf("    /** 仅撤销或恢复已显式启用的普通聊天底边，");
        int end = source.indexOf("    public void setupGlass(", start);
        check(start >= 0 && end >= 0, "底边 helper 边界缺失"); source = source.substring(0, start) + source.substring(end);
        source = once(source, "        setCodexPlainChatHeaderDividerVisible(true);\n", "");
        source = once(source, "            setCodexPlainChatHeaderDividerVisible(false);\n", "");
        source = once(source, "        updateCodexPlainChatHeaderDividerColors();\n", "");
        for (String indent : new String[]{"            ", "                "}) {
            String raw = indent + "if (codexPlainChatHeaderDividerVisible && codexPlainChatHeaderStyleId == com.butang.codextop.CodexPixelPalette.STYLE_BLUEPRINT) {\n" + indent + "    invalidate();\n" + indent + "}\n";
            source = once(source, raw, "");
        }
        String raw = block(source, "        if (codexPlainChatHeader && codexPlainChatHeaderDividerVisible", 0); source = once(source, raw + "\n", "");
        return source;
    }
    public static String protectedChat(String source) { return once(once(source, PREVIEW, ""), REFRESH, ""); }
    private static String once(String s,String a,String b) { int i=s.indexOf(a);check(i>=0&&i==s.lastIndexOf(a),"精确边界不唯一: "+a);return s.substring(0,i)+b+s.substring(i+a.length()); }
    private static String statement(String source,String signature) { int start=source.indexOf(signature);check(start>=0,"语句缺失 "+signature);return source.substring(start,source.indexOf(';',start)+1); }
    private static String method(String source,String signature) { return block(source,"\n"+signature,0).substring(1); }
    private static String block(String source,String signature,int from) { int start=source.indexOf(signature,from);check(start>=0,"方法缺失 "+signature);int open=source.indexOf('{',start),depth=1,end=open+1;while(depth>0){char c=source.charAt(end++);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end); }
    private static String sha(String source) throws Exception { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString(); }
    private static void check(boolean value,String why) { if(!value)throw new AssertionError(why); }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        import org.telegram.ui.ActionBar.Theme;
        public class BlueprintHeaderProbe {
            static float density=1;static int checks;static int dp(float v){return (int)Math.ceil(v*density);}
            static float lerp(float a,float b,float t){return a+(b-a)*t;}static int lerp(int a,int b,float t){return (int)lerp((float)a,(float)b,t);}
            static class Color {static final int TRANSPARENT=0;}static class LocaleController {static boolean isRTL;}
            static class TextUtils {static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}
            static class Paint {static final int ANTI_ALIAS_FLAG=1;static boolean forbid;static int objects;int color;Paint(int f){if(forbid)throw new AssertionError("draw allocated Paint");objects++;}void setColor(int c){color=c;}}
            static class Rect {void set(int l,int t,int r,int b){}}
            static class Canvas {int children,rects;float l,t,r,b;int color;void drawRect(float a,float c,float d,float e,Paint p){rects++;l=a;t=c;r=d;b=e;color=p.color;}}
            static class MarginLayoutParams {int leftMargin;}
            static class View {static final int VISIBLE=0,INVISIBLE=4,GONE=8;static final Object ALPHA=new Object(),SCALE_X=new Object(),SCALE_Y=new Object();int visibility=VISIBLE;float alpha=1,translationX,pivotX;Object background;MarginLayoutParams params=new MarginLayoutParams();int getVisibility(){return visibility;}void setVisibility(int v){visibility=v;}void setAlpha(float a){alpha=a;}float getAlpha(){return alpha;}void setScaleX(float a){}void setScaleY(float a){}void setTranslationX(float a){translationX=a;}void setPivotX(float a){pivotX=a;}void setBackground(Object b){background=b;}void setBackgroundColor(int c){background=c;}int getMeasuredWidth(){return 44;}Object getLayoutParams(){return params;}Drawable getDrawable(){return null;}void updateColors(){}}
            static class FrameLayout extends View {int width=360,height=80,invalidates,layouts;boolean clipChildren=true;void setClipChildren(boolean b){clipChildren=b;}void invalidate(){invalidates++;}void requestLayout(){layouts++;}int getWidth(){return width;}int getHeight(){return height;}int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}float getY(){return 0;}protected void dispatchDraw(Canvas c){c.children++;}}
            static class Drawable {}
            static class MenuDrawable extends Drawable {void setRotateToBack(boolean b){}void setRotation(int a,boolean b){}}
            static class AnimFactor {float factor;float getFactor(){return factor;}float getFloatValue(){return factor;}}
            static class BlurredBackgroundColorProvider {}
            static class BlurredBackgroundDrawable {BlurredBackgroundDrawable setColorProvider(BlurredBackgroundColorProvider p){return this;}BlurredBackgroundDrawable setPadding(int p){return this;}BlurredBackgroundDrawable setRadius(float... r){return this;}void setBounds(int a,int b,int c,int d){}void draw(Canvas c){}void setAlpha(int a){}void updateColors(){}}
            static class BlurredBackgroundDrawableViewFactory {BlurredBackgroundDrawable create(View v){return new BlurredBackgroundDrawable();}}
            static class BlurredBackgroundProviderImpl {static BlurredBackgroundColorProvider topPanelChatActivity(Object delegate){return new BlurredBackgroundColorProvider();}}
            static class ChatObject {static boolean isForum(Object chat){return false;}}
            static class ChatAvatarContainer extends View {int getLeftPadding(){return 0;}}
            static class ActionBarMenu extends View {boolean glass,plain;void setGlassMode(boolean b){glass=b;}void setCodexPlainHeader(boolean b){plain=b;}}
            static class Content {void drawBlurRect(Canvas c,float y,Rect r,Paint p,boolean b){}void drawBlurRect(Canvas c,float y,Rect r,Paint p,boolean b,float a){}}
            static class CodexRootHeader {void updateStyle(CodexPixelPalette.Style s){}}
            static class Animator {boolean running;List<AnimatorListenerAdapter> listeners=new ArrayList<>();boolean isRunning(){return running;}void addListener(AnimatorListenerAdapter l){listeners.add(l);}void cancel(){running=false;for(AnimatorListenerAdapter l:listeners)l.onAnimationCancel(this);for(AnimatorListenerAdapter l:listeners)l.onAnimationEnd(this);}}
            static class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}public void onAnimationCancel(Animator a){}}
            interface Update {void onUpdate(ValueAnimator a);}
            static class ValueAnimator extends Animator {float from,to,value;List<Update> updates=new ArrayList<>();static ValueAnimator ofFloat(float... f){ValueAnimator a=new ValueAnimator();a.from=f[0];a.to=f[f.length-1];a.value=a.from;return a;}void addUpdateListener(Update u){updates.add(u);}Object getAnimatedValue(){return value;}void pulse(float p){value=from+(to-from)*p;for(Update u:updates)u.onUpdate(this);}}
            static class ObjectAnimator extends Animator {static ObjectAnimator ofFloat(View v,Object property,float... f){return new ObjectAnimator();}}
            static class AnimatorSet extends Animator {List<Animator> children=new ArrayList<>();void playTogether(Animator... a){children.addAll(Arrays.asList(a));}AnimatorSet setDuration(int d){return this;}void start(){running=true;}void pulse(float p){for(Animator a:children)if(a instanceof ValueAnimator)((ValueAnimator)a).pulse(p);}void finish(){pulse(1);running=false;for(AnimatorListenerAdapter l:listeners)l.onAnimationEnd(this);}}
            static class ActionBar extends FrameLayout {
                boolean codexPlainChatHeader,codexPlainChatHeaderDividerVisible,glassMode,glassModeIsForum,glassOnlyBack,doNotDrawGlassMenu,hasForcedMenuWidth,hasForcedMenuMinWidth,isAnimationsAllowed,blurredBackground,adaptiveBackground,doNotDrawChild,isSearchFieldVisible,actionModeVisible,centerScale;
                int codexPlainChatHeaderStyleId,forcedMenuWidth,actionBarColor;float forcedMenuMinWidth,searchFactor,searchFieldVisibleAlpha,onTopAnimated;
                Paint codexPlainChatHeaderDividerPaint,blurScrimPaint=new Paint(1);Rect rectTmp=new Rect();Content contentView=new Content();
                ActionBarMenu menu,actionMode;View backButtonImageView,subtitleTextView,avatarSearchImageView,additionalSubTitleOverlayContainer;View[] titleTextView=new View[2];CharSequence subtitle;
                CodexRootHeader codexRootHeader;ChatAvatarContainer chatAvatarContainer;BlurredBackgroundDrawable glassDrawable,glassDrawableBack,glassDrawableMenu;
                AnimatorSet searchVisibleAnimator,actionModeAnimation;Runnable backgroundUpdateListener;AnimFactor animatorMenuItemsWidth=new AnimFactor(),animatorHasMenuItems=new AnimFactor(),animatorAvatarContainerHasAvatar=new AnimFactor(),animatorAvatarContainerWidth=new AnimFactor();
                int getThemedColor(int key){return Theme.getColor(key,null);}int getCurrentActionBarHeight(){return dp(56);}void checkMenuItemsWidth(){}boolean onSearchChangedIgnoreTitles(){return false;}void adaptive_updateColor(){}
        """;

    private static final String SCENARIOS = """
            static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}static void close(float a,float b,String why){check(Math.abs(a-b)<.001f,why+" "+a+" != "+b);}
            static Canvas draw(ActionBar b){Canvas c=new Canvas();int reads=Theme.reads,objects=Paint.objects;Theme.forbid=true;Paint.forbid=true;try{b.dispatchDraw(c);}finally{Theme.forbid=false;Paint.forbid=false;}check(reads==Theme.reads&&objects==Paint.objects,"draw allocated or queried Theme");return c;}
            static void line(ActionBar b,boolean expected,String why){Canvas c=draw(b);check(c.rects==(expected?1:0),why);if(expected){close(c.l,0,"line left");close(c.r,b.getWidth(),"line full width");close(c.t,b.getHeight()-dp(1),"line bottom band");close(c.b,b.getHeight(),"line bottom edge");check(c.color==CodexPixelPalette.metadata(6,false).outlineColor,"cached cyan color");}check(c.children==1,"original children not drawn");}
            static ActionBar normal(){Theme.id=6;ActionBar b=new ActionBar();b.setupPlainChatHeader(37);return b;}
            static void geometryAndTheme(){for(float d:new float[]{1,2}){density=d;for(int width:new int[]{240,641}){ActionBar b=normal();b.width=dp(width);for(int status:new int[]{0,24}){b.height=dp(56+status);line(b,true,"06 full width and statusbar boundary");}for(float alpha:new float[]{0,.4f,1}){b.alpha=alpha;line(b,true,"View composition retained");close(b.alpha,alpha,"draw changed View alpha");}for(int id:new int[]{1,2,3,4,5,6}){Theme.id=id;int before=Theme.reads;b.updateColors();check(Theme.reads==before+1,"theme did not refresh cache once");line(b,id==6,"only06 after live theme switch");}b.width=dp(320);line(b,true,"resized current width");LocaleController.isRTL=true;line(b,true,"RTL full width");LocaleController.isRTL=false;}}density=1;}
            static void search(){ActionBar b=normal();b.onSearchFieldVisibilityChanged(true);line(b,false,"search entry at0");int n=b.invalidates;b.searchVisibleAnimator.pulse(.5f);check(b.invalidates>n,"search alpha did not redraw parent");line(b,false,"search entering");b.searchVisibleAnimator.finish();line(b,false,"search visible");b.onSearchFieldVisibilityChanged(false);line(b,false,"search exit at1");b.searchVisibleAnimator.pulse(.5f);line(b,false,"search exit residual");b.searchVisibleAnimator.finish();line(b,true,"search fully exited");b.onSearchFieldVisibilityChanged(true);b.onSearchFieldVisibilityChanged(false);close(b.searchFieldVisibleAlpha,0,"search quick reversal precondition");line(b,false,"zero-progress search exit still running");b.searchVisibleAnimator.finish();line(b,true,"quick reversal complete");b.searchFactor=.2f;line(b,false,"original searchFactor residual");b.searchFactor=0;line(b,true,"searchFactor gone");}
            static void actionMode(){ActionBar b=normal();b.primeActionMode();close(b.getActionModeFactor(),1,"native invisible actionMode default alpha");line(b,true,"native newly created invisible actionMode permits ordinary line");b.actionMode.setVisibility(View.VISIBLE);b.actionMode.alpha=0;b.actionModeVisible=true;line(b,false,"action entry at0");b.actionMode.alpha=.5f;line(b,false,"action entry half");b.actionMode.alpha=1;b.actionModeVisible=false;b.actionModeAnimation=new AnimatorSet();b.actionModeAnimation.start();line(b,false,"action exit at1");b.actionMode.alpha=.3f;line(b,false,"action exit residual");b.actionMode.alpha=0;line(b,false,"action exit at0 before listener");b.actionModeAnimation=null;line(b,true,"action complete");}
            static void scopeAndPreview(){Chat c=new Chat();c.bind();line(c.actionBar,true,"actual ordinary gate binding");c.preview(true);line(c.actionBar,false,"actual preview hook disables");Theme.id=2;c.refresh();c.preview(false);line(c.actionBar,false,"preview return recaches latest style");Theme.id=6;c.refresh();line(c.actionBar,true,"actual Chat theme callback refreshes");c.preview(true);c.preview(false);line(c.actionBar,true,"preview return restores");for(int mode=0;mode<9;mode++){Theme.id=6;Chat x=new Chat();switch(mode){case 0:CodexRuntime.enabled=false;break;case 1:CodexRuntime.owns=false;break;case 2:x.chatMode=1;break;case 3:x.report=true;break;case 4:x.startLoadFromDate=1;break;case 5:x.inPreviewMode=true;break;case 6:x.inBubbleMode=true;break;case 7:x.isInsideContainer=true;break;case 8:x.isComments=true;break;}x.bind();line(x.actionBar,false,"excluded actual scope "+mode);x.preview(false);line(x.actionBar,false,"excluded preview return "+mode);CodexRuntime.enabled=CodexRuntime.owns=true;}}
            static void restoreAndSkip(){ActionBar b=normal();b.codexRootHeader=new CodexRootHeader();line(b,false,"root pages excluded");b.codexRootHeader=null;line(b,true,"root gate released");b.setupGlass(new BlurredBackgroundDrawableViewFactory(),new BlurredBackgroundColorProvider());line(b,false,"plain to original glass restored");check(!b.codexPlainChatHeaderDividerVisible,"glass left divider opt-in");b.setCodexPlainChatHeaderDividerVisible(true);line(b,false,"shared glass cannot enable divider");b=normal();b.doNotDrawChild=true;Canvas c=draw(b);check(c.rects==0&&c.children==0,"skip child path drew line");}
            public static void main(String[] args){geometryAndTheme();search();actionMode();scopeAndPreview();restoreAndSkip();System.out.println("PASS BlueprintHeaderLineTest: "+checks+" assertions; actual Java8 draw/cache/plain/glass/search/Chat hooks; Android compositor and screenshots unverified.");}
        """;
}
