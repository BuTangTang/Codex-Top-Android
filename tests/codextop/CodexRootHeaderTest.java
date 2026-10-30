package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/**
 * 提取完整生产页头类、ActionBar 测量/布局、设置根页守卫与 contentView 测量，按 Java 8 编译执行。
 * 平台替身记录字体尺寸、图形和 View 边界，不声称覆盖 Android 真字体或真实窗口验收。
 * javac -cp "$PARSER_JAR" -d "$TEST_CLASSES" tests/codextop/CodexRootHeaderTest.java
 * java -Xmx64m -cp "$TEST_CLASSES:$PARSER_JAR" com.butang.codextop.CodexRootHeaderTest
 * 可依次传入 ActionBar、DialogsActivity、SettingsActivity 源路径运行已编译故障注入负例。
 */
public final class CodexRootHeaderTest {
    /** 用独立临时目录运行完整方法，结束后回收合成源码和类文件。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String root = "TMessagesProj/src/main/java/org/telegram/ui/";
        List<String> barNames = List.of("setCodexRootHeader", "hasCodexRootHeader", "getHeaderContentHeight", "getCurrentActionBarHeight", "onMeasure", "onLayout", "updateColors", "updateCodexPlainChatHeaderDividerColors", "setUseContainerForTitles");
        List<String> settingsNames = List.of("codexRootHeaderPage", "codexBrowser", "codexConversations", "codexMyRoot", "codexQuota", "codexAppearance", "fillItems", "updateColors");
        List<String> simpleNames = List.of("createLayout", "calcOffset", "onMeasure", "setTextSize", "setTextSizePx", "recreateLayoutMaybe",
                "getTextPaint", "getText", "getTextHeight", "setTextColor", "getTextWidth", "getSideDrawablesSize", "getTextStartX", "setGravity", "getAlignment");
        Source barSource = read(args, 0, root + "ActionBar/ActionBar.java", "ActionBar");
        ClassOrInterfaceDeclaration bar = barSource.parse("ActionBar", barNames, List.of("CodexRootHeader"));
        Source dialogsSource = read(args, 1, root + "DialogsActivity.java", "DialogsActivity");
        ClassOrInterfaceDeclaration dialogs = dialogsSource.parse("DialogsActivity", List.of("codexListHeaderHeight"), List.of());
        Source settingsSource = read(args, 2, root + "SettingsActivity.java", "SettingsActivity");
        ClassOrInterfaceDeclaration settings = settingsSource.parse("SettingsActivity", settingsNames, List.of());
        ClassOrInterfaceDeclaration simple = read(args, 3, root + "ActionBar/SimpleTextView.java", "SimpleTextView").parse("SimpleTextView", simpleNames, List.of());
        StringBuilder simpleMethods = new StringBuilder();
        for (String name : simpleNames) {
            for (MethodDeclaration method : simple.getMethodsByName(name)) simpleMethods.append(method).append('\n');
        }
        ClassOrInterfaceDeclaration header = bar.getMembers().stream().filter(member -> member.isClassOrInterfaceDeclaration()
                && member.asClassOrInterfaceDeclaration().getNameAsString().equals("CodexRootHeader"))
                .findFirst().orElseThrow().asClassOrInterfaceDeclaration();
        MethodDeclaration draw = header.getMethodsByName("draw").get(0);
        check(draw.findAll(ObjectCreationExpr.class).isEmpty(), "页头绘制分配对象");
        check(draw.findAll(MethodCallExpr.class).stream().noneMatch(call -> call.getNameAsString().equals("measureText")
                || call.getNameAsString().equals("requestLayout") || call.getScope().map(scope -> scope.toString().contains("Theme")
                || scope.toString().contains("Runtime")).orElse(false)), "页头绘制重新测量、查状态或请求布局");
        Source createDialogs = new Source(dialogsSource.methods("createView", 1).get(0));
        MethodCallExpr gate = StaticJavaParser.parseStatement(createDialogs.statement("actionBar.setCodexRootHeader(")).findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals("setCodexRootHeader"))
                .findFirst().orElseThrow();
        String dialogGate = gate.getArgument(0).toString();
        Source createSettings = new Source(settingsSource.methods("createView", 1).get(0));
        Source content = createSettings.blockAfter("contentView = new SizeNotifierFrameLayout(context)");
        MethodDeclaration contentMeasure = StaticJavaParser.parseMethodDeclaration(content.methods("onMeasure", 1).get(0));
        MethodDeclaration listMeasure = dialogsSource.methods("onMeasure", -1).stream().filter(method -> method.contains("t = codexListHeaderHeight()"))
                .map(StaticJavaParser::parseMethodDeclaration).findFirst().orElseThrow();
        check(listMeasure.toString().contains("t = codexListHeaderHeight()"), "会话列表没有使用实际标题区");
        check(settings.getMethodsByName("fillItems").get(0).toString().contains("codexRootHeaderPage() != 0 ? 0 : ActionBar.getCurrentActionBarHeight()"),
                "设置根页实测 padding 与占位重复或子页占位改变");
        check(settings.getMethodsByName("updateColors").get(0).toString().contains("if (codexRootHeaderPage() != 0)\n        actionBar.updateColors();"),
                "设置主题刷新没有更新根页几何缓存");
        MethodDeclaration container = bar.getMethodsByName("setUseContainerForTitles").get(0);
        check(container.toString().contains("codexRootHeader.draw(canvas"), "装饰没有接到原标题动画容器");

        StringBuilder methods = new StringBuilder(header.toString()).append('\n');
        for (String name : barNames) {
            for (MethodDeclaration method : bar.getMethodsByName(name)) methods.append(method).append('\n');
        }
        StringBuilder settingsMethods = new StringBuilder();
        for (String name : List.of("codexRootHeaderPage", "codexBrowser", "codexConversations", "codexMyRoot", "codexQuota", "codexAppearance")) {
            for (MethodDeclaration method : settings.getMethodsByName(name)) settingsMethods.append(method).append('\n');
        }
        Path temp = Files.createTempDirectory("codex-root-header-test-");
        try {
            Path source = temp.resolve("RootHeaderProbe.java");
            Files.writeString(source, FIXTURE.replace("// ACTUAL_SIMPLE_METHODS", simpleMethods.toString()) + methods + "}\nstatic class SettingsGate {boolean hasMainTabs=true;Bundle args=new Bundle();Bundle getArguments(){return args;}\n"
                    + settingsMethods + "}\nstatic class DialogGate {static final int DIALOGS_TYPE_DEFAULT=0;boolean hasMainTabs=true,onlySelect,inPreviewMode;int initialDialogsType,folderId,communityId;String searchString;int page(){return "
                    + dialogGate + ";}ActionBar actionBar;" + dialogs.getMethodsByName("codexListHeaderHeight").get(0) + "}\nstatic class SettingsContent extends FrameLayout {boolean rootHeader;ActionBar actionBar;View listView;\n" + contentMeasure + "}\n" + SCENARIOS + "}\n" + "class CodexRuntime {static boolean active=true;static boolean enabled(){return active;}}\nclass CodexPixelPalette {static final int STYLE_BLUEPRINT=6;static class Style {int id,ink=11,secondary=12,accentFill=13,accentText=14,onAccentFill=15,outlineColor;Style(int id){this.id=id;}}}\n");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temp.toString(), source.toString()) == 0,
                    "完整真实页头方法 Java 8 编译失败");
            try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RootHeaderProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("已编译真实页头方法验证失败", error.getCause()); }
            }
        } finally {
            try (var walk = Files.walk(temp)) { for (Path path : walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** 先按词法括号边界保留完整生产方法，再解析小片段；不为大型 Activity 建整棵 AST。 */
    private static Source read(String[] args, int index, String fallback, String name) throws Exception {
        return new Source(Files.readString(Path.of(args.length > index ? args[index] : fallback))).classBody(name);
    }

    /** 只屏蔽注释和字面量中的括号；返回的声明始终是未经改写的生产源码原片段。 */
    private static final class Source {
        final String text;
        final String code;
        Source(String text) {
            this.text = text;
            char[] mask = text.toCharArray();
            for (int i = 0; i < mask.length;) {
                int start = i, end = i;
                if (i + 1 < mask.length && mask[i] == '/' && mask[i + 1] == '/') {
                    end = text.indexOf('\n', i + 2); if (end < 0) end = mask.length;
                } else if (i + 1 < mask.length && mask[i] == '/' && mask[i + 1] == '*') {
                    end = text.indexOf("*/", i + 2); check(end >= 0, "生产源码注释未闭合"); end += 2;
                } else if (mask[i] == '\"' || mask[i] == '\'') {
                    char quote = mask[i++];
                    while (i < mask.length) {
                        if (mask[i] == '\\') { i += 2; continue; }
                        if (mask[i++] == quote) break;
                    }
                    end = i;
                }
                if (end > start) {
                    for (int n = start; n < end; n++) if (mask[n] != '\n' && mask[n] != '\r') mask[n] = ' ';
                    i = end;
                } else i++;
            }
            code = new String(mask);
        }
        int depth(int at) { int depth = 0; for (int i = 0; i < at; i++) { if (code.charAt(i) == '{') depth++; else if (code.charAt(i) == '}') depth--; } return depth; }
        int closing(int open, char left, char right) {
            check(open >= 0 && code.charAt(open) == left, "生产源码缺少开括号");
            int depth = 1;
            for (int i = open + 1; i < code.length(); i++) { if (code.charAt(i) == left) depth++; else if (code.charAt(i) == right && --depth == 0) return i; }
            throw new AssertionError("生产源码括号未闭合");
        }
        int classStart(String name) {
            Matcher match = Pattern.compile("(?m)^[ \\t]*(?:(?:public|private|protected|static|final)\\s+)*class\\s+" + Pattern.quote(name) + "\\b").matcher(code);
            check(match.find(), "缺少真实生产类 " + name); return match.start();
        }
        Source classBody(String name) { int open = code.indexOf('{', classStart(name)); return new Source(text.substring(open, closing(open, '{', '}') + 1)); }
        String nestedClass(String name) { int start = classStart(name), open = code.indexOf('{', start); return text.substring(start, closing(open, '{', '}') + 1); }
        List<String> methods(String name, int requiredDepth) {
            Matcher match = Pattern.compile("(?m)^[ \\t]*(?:(?:public|private|protected|static|final|synchronized)\\s+)*(?:[\\w.$<>?\\[\\],]+\\s+)+" + Pattern.quote(name) + "\\s*\\(").matcher(code);
            List<String> methods = new ArrayList<>();
            while (match.find()) {
                if (requiredDepth >= 0 && depth(match.start()) != requiredDepth) continue;
                int paren = code.indexOf('(', match.start()), close = closing(paren, '(', ')'), open = code.indexOf('{', close + 1);
                int semicolon = code.indexOf(';', close + 1);
                check(open >= 0 && (semicolon < 0 || open < semicolon), "目标方法没有完整生产方法体 " + name);
                methods.add(text.substring(match.start(), closing(open, '{', '}') + 1));
            }
            check(!methods.isEmpty(), "缺少真实生产方法 " + name); return methods;
        }
        Source blockAfter(String marker) { int start = code.indexOf(marker); check(start >= 0, "缺少真实创建位置 " + marker); int open = code.indexOf('{', start + marker.length()); return new Source(text.substring(open, closing(open, '{', '}') + 1)); }
        String statement(String marker) { int start = code.indexOf(marker); check(start >= 0 && code.indexOf(marker, start + marker.length()) < 0, "真实调用缺失或重复 " + marker); int end = closing(code.indexOf('(', start), '(', ')') + 1; while (end < code.length() && Character.isWhitespace(code.charAt(end))) end++; check(code.charAt(end) == ';', "真实调用未保留完整语句"); return text.substring(start, end + 1); }
        ClassOrInterfaceDeclaration parse(String name, List<String> methodNames, List<String> nestedNames) {
            StringBuilder selected = new StringBuilder("class ").append(name).append(" {\n");
            for (String nested : nestedNames) selected.append(nestedClass(nested)).append('\n');
            for (String method : methodNames) for (String declaration : methods(method, 1)) selected.append(declaration).append('\n');
            return StaticJavaParser.parse(selected.append('}').toString()).getClassByName(name).orElseThrow();
        }
    }

    /** 前提或接线失败立即阻止继续，不把结构检查当作实机通过。 */
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public class RootHeaderProbe {
            static float density=1;
            static int dp(float value){return (int)Math.ceil(density*value);}
            static class AndroidUtilities {
                static int statusBarHeight=24;static Point displaySize=new Point(360,720);static boolean tablet;
                static boolean isTablet(){return tablet;}static Object bold(){return null;}
            }
            static class Point {int x,y;Point(int x,int y){this.x=x;this.y=y;}}
            static class LocaleController {static boolean isRTL;}
            static class Configuration {static final int ORIENTATION_LANDSCAPE=2;int orientation=1;float fontScale=1;}
            static class Resources {Configuration conf=new Configuration();Configuration getConfiguration(){return conf;}}
            static class Gravity {static final int TOP=48,LEFT=3,RIGHT=5,CENTER=17,CENTER_VERTICAL=16,CENTER_HORIZONTAL=1,BOTTOM=80,HORIZONTAL_GRAVITY_MASK=7,VERTICAL_GRAVITY_MASK=112;}
            static class MeasureSpec {
                static final int EXACTLY=0x40000000,AT_MOST=0x80000000,UNSPECIFIED=0;
                static int getSize(int value){return value&0x3fffffff;}static int getMode(int value){return value&0xc0000000;}
                static int makeMeasureSpec(int size,int mode){return size|mode;}
            }
            static class Paint {
                static final int ANTI_ALIAS_FLAG=1;enum Style {FILL,STROKE}
                static boolean forbidMeasure;int color;float size=11,stroke;Style style=Style.FILL;
                Paint(int flags){}void setStyle(Style value){style=value;}void setTypeface(Object value){}void setColor(int value){color=value;}
                void setStrokeWidth(float value){stroke=value;}void setTextSize(float value){size=value;}float getTextSize(){return size;}
                float measureText(String value){if(forbidMeasure)throw new AssertionError("draw measured text");return value.length()*size;}
                float measureText(CharSequence value,int start,int end){return (end-start)*size;}
                float ascent(){return -size;}float descent(){return size*.2f;}
            }
            static class Path {
                float left,top,right,bottom;boolean empty=true;
                void reset(){empty=true;}void moveTo(float x,float y){point(x,y);}void lineTo(float x,float y){point(x,y);}void close(){}
                void point(float x,float y){if(empty){left=right=x;top=bottom=y;empty=false;}else{left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}}
            }
            static class Draw {float left,top,right,bottom;String text;int color;Draw(float l,float t,float r,float b,String s,int c){left=l;top=t;right=r;bottom=b;text=s;color=c;}}
            static class Canvas {
                List<Draw> draws=new ArrayList<>();List<float[]> saved=new ArrayList<>();float x=0,y=0,sx=1;int children;
                void save(){saved.add(new float[]{x,y,sx});}void restore(){float[] state=saved.remove(saved.size()-1);x=state[0];y=state[1];sx=state[2];}
                void translate(float a,float b){x+=a*sx;y+=b;}void scale(float a,float b){sx*=a;}
                void add(float l,float t,float r,float b,String text,Paint paint){float a=l*sx+x,c=r*sx+x;draws.add(new Draw(Math.min(a,c),t+y,Math.max(a,c),b+y,text,paint.color));}
                void drawRect(float l,float t,float r,float b,Paint paint){add(l,t,r,b,null,paint);}
                void drawLine(float l,float t,float r,float b,Paint paint){add(l,t,r,b,null,paint);}
                void drawPath(Path path,Paint paint){add(path.left,path.top,path.right,path.bottom,null,paint);}
                void drawText(String text,float a,float b,Paint paint){add(a,b-paint.size,a+text.length()*paint.size,b+paint.size*.2f,text,paint);}
            }
            static class View {
                static final int GONE=8,VISIBLE=0;int visibility,mw,mh,w=48,h=48,l,t,r,b,pLeft,pTop,pRight,pBottom,paddingWrites,layouts,invalidates;
                float alpha=1;Resources res=new Resources();ViewGroup.LayoutParams params=new FrameLayout.LayoutParams();
                int getVisibility(){return visibility;}int getMeasuredWidth(){return mw;}int getMeasuredHeight(){return mh;}int getWidth(){return mw;}int getHeight(){return mh;}
                float getX(){return l;}int getPaddingTop(){return pTop;}int getPaddingBottom(){return pBottom;}int getPaddingLeft(){return pLeft;}int getPaddingRight(){return pRight;}
                void setPadding(int a,int c,int d,int e){pLeft=a;pTop=c;pRight=d;pBottom=e;paddingWrites++;}
                void setPivotX(float value){}void setPivotY(float value){}Resources getResources(){return res;}Object getContext(){return null;}
                ViewGroup.LayoutParams getLayoutParams(){return params;}void setMeasuredDimension(int width,int height){mw=width;mh=height;}
                void measure(int ws,int hs){onMeasure(ws,hs);}protected void onMeasure(int ws,int hs){mw=MeasureSpec.getMode(ws)==MeasureSpec.EXACTLY?MeasureSpec.getSize(ws):Math.min(w,MeasureSpec.getSize(ws));mh=MeasureSpec.getMode(hs)==MeasureSpec.UNSPECIFIED?h:MeasureSpec.getMode(hs)==MeasureSpec.EXACTLY?MeasureSpec.getSize(hs):Math.min(h,MeasureSpec.getSize(hs));}
                void layout(int a,int c,int d,int e){l=a;t=c;r=d;b=e;onLayout(true,a,c,d,e);}protected void onLayout(boolean changed,int l,int t,int r,int b){}
                void requestLayout(){layouts++;}void invalidate(){invalidates++;}public void updateColors(){}
            }
            static class ViewGroup extends View {
                static class LayoutParams {int width=-1,height=-1,topMargin,bottomMargin,leftMargin,rightMargin,gravity=Gravity.TOP|Gravity.LEFT;}
                List<View> children=new ArrayList<>();int getChildCount(){return children.size();}View getChildAt(int index){return children.get(index);}void addView(View child){children.add(child);}
            }
            static class FrameLayout extends ViewGroup {
                FrameLayout(){}FrameLayout(Object context){}static class LayoutParams extends ViewGroup.LayoutParams{}
                void measureChildWithMargins(View child,int ws,int wu,int hs,int hu){child.measure(ws,hs);}
                protected void dispatchDraw(Canvas canvas){canvas.children++;}
            }
            static class TextPaint extends Paint {
                float fontScale=1;TextPaint(){super(1);}float width(CharSequence text){return text.length()*getTextSize();}
                float measureText(String text){if(forbidMeasure)throw new AssertionError("draw measured text");return width(text);}
            }
            static class Drawable {int width;Drawable(int value){width=value;}int getIntrinsicWidth(){return width;}}
            static class Layout {
                enum Alignment {ALIGN_NORMAL}CharSequence text;float width,left;int height;
                int getLineCount(){return text.length()==0?0:1;}float getLineWidth(int line){return width;}float getLineLeft(int line){return left;}
                float getLineRight(int line){return left+width;}int getLineBottom(int line){return height;}float getPrimaryHorizontal(int i){return left;}
                int getLineEnd(int line){return text.length();}int getLineStart(int line){return 0;}CharSequence getText(){return text;}
            }
            static class StaticLayout extends Layout {
                StaticLayout(CharSequence value,int start,int end,TextPaint paint,int available,Alignment alignment,float spacing,float add,boolean pad){
                    text=value.subSequence(start,end);width=paint.width(text);height=(int)Math.ceil(paint.getTextSize()*1.3f);
                    if(text.length()>0&&text.charAt(0)>='\\u0600'&&text.charAt(0)<='\\u06ff')left=Math.max(0,available-width);
                }
            }
            static class StaticLayoutEx {static StaticLayout createStaticLayout(Object... args){CharSequence text=(CharSequence)args[0];return new StaticLayout(text,0,text.length(),(TextPaint)args[1],(Integer)args[2],Layout.Alignment.ALIGN_NORMAL,1,0,false);}}
            interface Spannable extends CharSequence{}
            static class SpannableStringBuilder implements Spannable {
                CharSequence value;static SpannableStringBuilder valueOf(CharSequence v){SpannableStringBuilder b=new SpannableStringBuilder();b.value=v;return b;}
                void setSpan(Object o,int start,int end,int flags){}public int length(){return value.length();}public char charAt(int i){return value.charAt(i);}
                public CharSequence subSequence(int start,int end){return value.subSequence(start,end);}public String toString(){return value.toString();}
            }
            static class DialogCell {static class FixedWidthSpan {FixedWidthSpan(int width){}}}static class EmptyStubSpan{}
            static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return a==b||a!=null&&b!=null&&a.toString().equals(b.toString());}enum TruncateAt {END}static CharSequence ellipsize(CharSequence value,TextPaint paint,int width,TruncateAt type){return paint.width(value)<=width?value:"…";}}
            static class SpoilerEffect {static void addSpoilers(Object... args){}}
            static class AnimatedEmojiSpan {static void release(Object... args){}static Object update(Object... args){return new Object();}}
            static class SimpleTextView extends View {
                TextPaint textPaint=new TextPaint();
                boolean wasLayout;Paint paint;CharSequence text="手机体验优化";Drawable leftDrawable,rightDrawable,rightDrawable2,replacedDrawable;
                int drawablePadding=dp(4),gravity=Gravity.LEFT,minusWidth,lastWidth,offsetX,offsetY,textWidth,textHeight,paddingRight,maxLines=1,fullTextMaxLines=3;
                int replacingDrawableTextIndex=-1,fullLayoutAdditionalWidth,emojiCacheType,currentScrollDelay;
                float rightDrawableScale=1,scrollingOffset,fullLayoutLeftCharactersOffset,replacingDrawableTextOffset;boolean rightDrawableInside,leftDrawableOutside,rightDrawableOutside,widthWrapContent;
                boolean rightDrawableHidden,canHideRightDrawable,buildFullLayout,scrollNonFitText,ellipsizeByGradient=true,textDoesNotFit,attachedToWindow;
                String replacedText;Layout layout,fullLayout,firstLineLayout,partLayout;Object emojiStack;
                List<Object> spoilers=new ArrayList<>(),spoilersPool=new ArrayList<>();Layout.Alignment mAlignment=Layout.Alignment.ALIGN_NORMAL;
                static final int SCROLL_DELAY_MS=500;void checkUi_layerType(){}
                SimpleTextView(String value){text=value;paint=textPaint;pTop=pBottom=dp(8);}
                int getMaxTextWidth(){return getMeasuredWidth();}
                protected void onLayout(boolean changed,int left,int top,int right,int bottom){wasLayout=true;}
                // ACTUAL_SIMPLE_METHODS
            }
            static class ActionBarMenu extends View {
                boolean search;boolean searchFieldVisible(){return search;}int getItemsMeasuredWidth(boolean exclude){return w;}void translateXItems(int value){}
            }
            static class Anim {boolean isAnimating(){return false;}}
            static class Glass {void updateColors(){}}
            static class Theme {
                static final int key_actionBarDefaultTitle=1;static CodexPixelPalette.Style style=new CodexPixelPalette.Style(1);static boolean forbid;
                static CodexPixelPalette.Style getCodexPixelStyle(){if(forbid)throw new AssertionError("draw queried theme");return style;}
            }
            static class Bundle {Map<String,Object> data=new HashMap<>();boolean getBoolean(String key,boolean fallback){Object v=data.get(key);return v instanceof Boolean?(Boolean)v:fallback;}String getString(String key){return (String)data.get(key);}}
            static class ActionBar extends FrameLayout {
                boolean occupyStatusBar=true,ignoreLayoutRequest,isSearchFieldVisible,menuOccupyBack,isMenuOffsetSuppressed,fromBottom,overlayTitleAnimation,titleAnimationRunning,centerScale,glassMode,isCenterTitle,useContainerForTitles;
                int extraHeight,titleRightMargin,additionalTextLeft,prevWidth,titleColorToSet;
                boolean codexPlainChatHeader,codexPlainChatHeaderDividerVisible;int codexPlainChatHeaderStyleId;Paint codexPlainChatHeaderDividerPaint;
                CodexRootHeader codexRootHeader;ActionBarMenu menu,actionMode;View actionModeTop,backButtonImageView,avatarSearchImageView,additionalSubTitleOverlayContainer;
                SimpleTextView[] titleTextView=new SimpleTextView[2];SimpleTextView subtitleTextView,additionalSubtitleTextView;FrameLayout titlesContainer;
                Anim animatorAvatarContainerWidth=new Anim();Glass glassDrawable,glassDrawableMenu,glassDrawableBack;
                void checkAvatarContainerWidth(boolean animating){}void adaptive_updateColor(){}int getThemedColor(int key){return 77;}
        """;

    private static final String SCENARIOS = """
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static ActionBar bar(int style,int page,int width,float scale,boolean rtl){Theme.style=new CodexPixelPalette.Style(style);LocaleController.isRTL=rtl;ActionBar bar=new ActionBar();bar.res.conf.fontScale=scale;bar.menu=new ActionBarMenu();bar.menu.w=48;bar.titleTextView[0]=new SimpleTextView(page==1?"会话":page==2?"电脑":"我的");bar.setUseContainerForTitles();bar.setCodexRootHeader(page);measure(bar,width);return bar;}
            static void measure(ActionBar bar,int width){bar.measure(MeasureSpec.makeMeasureSpec(dp(width),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(720),MeasureSpec.EXACTLY));bar.layout(0,0,bar.mw,bar.mh);}
            static void geometry(){
                for(int id:new int[]{1,2,3,4,6})for(int page=1;page<=3;page++)for(int width:new int[]{240,320,412})for(float scale:new float[]{1,1.6f,2})for(boolean rtl:new boolean[]{false,true}){
                    ActionBar bar=bar(id,page,width,scale,rtl);SimpleTextView title=bar.titleTextView[0];ActionBar.CodexRootHeader header=bar.codexRootHeader;
                    check(bar.getHeaderContentHeight()+AndroidUtilities.statusBarHeight==bar.mh,"共用标题高度与实测不一致");
                    if(scale==1)check(bar.getHeaderContentHeight()==dp(id==2?108:id==4?88:id==6?72:76),"默认标题区偏离参考图约定高度");
                    check(title.t>=AndroidUtilities.statusBarHeight&&title.b<=bar.mh,"大字标题垂直越过页头");
                    check(title.l>=dp(12)&&title.r<=dp(width)-bar.menu.mw-dp(16),"根页标题覆盖原菜单或安全区");
                    check(title.getTextWidth()>=title.paint.size*2-1&&!title.textDoesNotFit,"窄屏固定页名被省略");
                    check(bar.menu.mh==dp(56),"原菜单命中高度被扩大");
                    check((id==3||id==4?Theme.style.onAccentFill:Theme.style.ink)==title.textPaint.color,"彩色标题带上的文字颜色错误");
                    Canvas canvas=new Canvas();Paint.forbidMeasure=true;Theme.forbid=true;bar.titlesContainer.dispatchDraw(canvas);Theme.forbid=false;Paint.forbidMeasure=false;
                    for(Draw draw:canvas.draws){check(draw.left>=dp(10)&&draw.right<=dp(width)-bar.menu.mw-dp(14),"装饰侵入原菜单");check(draw.top>=AndroidUtilities.statusBarHeight&&draw.bottom<=bar.mh,"装饰越过页头");}
                    float glyphLeft=title.getTextStartX(),glyphRight=glyphLeft+title.getTextWidth();
                    check(Math.abs((rtl?glyphRight:glyphLeft)-(rtl?dp(12)+header.safeRight-header.titleLeft:header.titleLeft))<=1,"RTL文字与图形镜像轴没有保留原内距");
                    if(id==3||id==4){Draw accent=canvas.draws.stream().filter(draw->draw.text==null&&draw.color==Theme.style.accentFill).findFirst().orElseThrow(()->new AssertionError("彩色标题块丢失"));check((rtl?accent.right-glyphRight:glyphLeft-accent.left)>=dp(id==3?14:16)-1,"彩色块文字贴住边框");}
                    check(canvas.saved.isEmpty()&&canvas.children==1,"页头未恢复Canvas或未保留原children");
                    if(id==2){String number=page==1?"01":page==2?"02":"03";Draw numberDraw=canvas.draws.stream().filter(draw->number.equals(draw.text)).findFirst().orElseThrow(()->new AssertionError("蓝白页号丢失"));check(Math.abs((rtl?numberDraw.right:numberDraw.left)-(rtl?glyphRight:glyphLeft))<=1,"蓝白页号与真实标题没有对齐同一镜像轴");}
                    if(width==240)check(!header.drawBrand,"窄屏没有优先让出产品字样");
                    // 搜索、淡出和多选复用既有容器，不在测量时接管 alpha 或菜单状态。
                    bar.titlesContainer.alpha=.27f;bar.menu.search=true;bar.isSearchFieldVisible=true;bar.actionMode=new ActionBarMenu();bar.addView(bar.actionMode);measure(bar,width);
                    check(bar.titlesContainer.alpha==.27f&&bar.menu.search&&bar.isSearchFieldVisible,"原搜索/标题透明度被重置");
                    check(bar.actionMode.mh==dp(56)+AndroidUtilities.statusBarHeight,"多选扩大原命中区域");
                    int reserved=bar.getHeaderContentHeight();title.visibility=View.GONE;measure(bar,width);
                    check(bar.getHeaderContentHeight()==reserved,"搜索隐藏真实标题后安全区突然缩小");
                }
                density=2;AndroidUtilities.statusBarHeight=48;ActionBar bar=bar(2,1,320,2,false);check(bar.mh==bar.getHeaderContentHeight()+48,"双密度状态栏重复或缺失");density=1;AndroidUtilities.statusBarHeight=24;
            }
            static void fallbackAndTheme(){
                ActionBar bar=bar(3,2,320,1,true);bar.setCodexRootHeader(0);check(bar.titleTextView[0].paint.size==20,"显式退出未立刻恢复原标题字号");measure(bar,320);check(bar.codexRootHeader==null&&bar.mh==80,"禁用没有恢复原56dp标题区");check(bar.titleTextView[0].paint.size==20&&bar.titleTextView[0].gravity==(Gravity.LEFT|Gravity.CENTER_VERTICAL)&&bar.titleTextView[0].textPaint.color==77,"禁用没有恢复原标题样式");
                bar=bar(2,1,320,2,false);int previous=bar.mh;bar.titleTextView[1]=new SimpleTextView("连接中");measure(bar,320);check(bar.mh==previous,"覆盖标题第二视图把大字占位缩小");
                Theme.style=new CodexPixelPalette.Style(4);bar.updateColors();measure(bar,320);check(bar.codexRootHeader.styleId==4&&bar.titleTextView[0].textPaint.color==Theme.style.onAccentFill,"主题切换没有更新标题缓存");
                check(bar.titleTextView[1].text.equals("连接中"),"真实覆盖状态内容被改写");
            }
            static void hiddenTheme(){
                ActionBar bar=bar(2,1,320,2,false);int expanded=bar.getHeaderContentHeight();check(expanded>dp(108),"大字前提没有扩大标题区");
                bar.titleTextView[0].visibility=View.GONE;bar.updateColors();measure(bar,320);check(bar.getHeaderContentHeight()==expanded,"搜索隐藏标题时同主题刷新缩小实测安全区");
                Theme.style=new CodexPixelPalette.Style(1);bar.updateColors();measure(bar,320);check(bar.getHeaderContentHeight()==expanded,"搜索隐藏标题时换主题缩小实测安全区");
                bar.titleTextView[0].visibility=View.VISIBLE;measure(bar,320);check(bar.getHeaderContentHeight()<expanded,"标题可见后换主题无法重新测量缩小");
                bar.res.conf.fontScale=1;measure(bar,320);check(bar.getHeaderContentHeight()==dp(76),"降低字体后缓存成为永久最大值");
                bar.titleTextView[0].visibility=View.GONE;Theme.style=new CodexPixelPalette.Style(2);bar.updateColors();measure(bar,320);check(bar.getHeaderContentHeight()==dp(108),"搜索隐藏标题时新主题最低高度未生效");
                bar.titleTextView[0].visibility=View.VISIBLE;Theme.style=new CodexPixelPalette.Style(1);bar.updateColors();measure(bar,320);check(bar.getHeaderContentHeight()==dp(76),"02换01保留了旧108dp基准");
                bar.setCodexRootHeader(0);bar.updateColors();measure(bar,320);check(bar.getHeaderContentHeight()==dp(56),"普通导航主题刷新带入根页缓存");
            }
            static void gates(){
                DialogGate d=new DialogGate();check(d.page()==1,"会话根页未启用");CodexRuntime.active=false;check(d.page()==0,"非Codex会话启用");CodexRuntime.active=true;
                d.hasMainTabs=false;check(d.page()==0,"无主栏会话启用");d.hasMainTabs=true;d.onlySelect=true;check(d.page()==0,"选择页启用");d.onlySelect=false;d.inPreviewMode=true;check(d.page()==0,"预览页启用");d.inPreviewMode=false;
                d.folderId=1;check(d.page()==0,"归档启用");d.folderId=0;d.communityId=1;check(d.page()==0,"社区启用");d.communityId=0;d.initialDialogsType=1;check(d.page()==0,"非默认类型启用");d.initialDialogsType=0;d.searchString="";check(d.page()==0,"预置搜索启用");
                d.actionBar=bar(2,1,320,1,false);d.actionBar.extraHeight=23;measure(d.actionBar,320);check(d.codexListHeaderHeight()==d.actionBar.getHeaderContentHeight(),"根页扩展高度没有进入实际列表占位");
                d.actionBar.setCodexRootHeader(0);check(d.codexListHeaderHeight()==ActionBar.getCurrentActionBarHeight(),"非根页列表带入了原来不使用的extraHeight");
                SettingsGate s=new SettingsGate();check(s.codexRootHeaderPage()==3,"我的根页未启用");s.args.data.put("codexComputerBrowser",true);check(s.codexRootHeaderPage()==2,"电脑根页未启用");
                s.hasMainTabs=false;check(s.codexRootHeaderPage()==0,"电脑子栈启用");s.hasMainTabs=true;s.args.data.put("codexMachine","synthetic");check(s.codexRootHeaderPage()==0,"项目页启用");s.args.data.remove("codexMachine");s.args.data.put("codexConversations",true);check(s.codexRootHeaderPage()==0,"电脑会话页启用");
                s.args=new Bundle();s.args.data.put("codexQuota",true);check(s.codexRootHeaderPage()==0,"额度子页启用");s.args=new Bundle();s.args.data.put("codexAppearance",true);check(s.codexRootHeaderPage()==0,"外观子页启用");s.args=new Bundle();CodexRuntime.active=false;check(s.codexRootHeaderPage()==0,"普通Telegram设置启用");CodexRuntime.active=true;
            }
            static void settingsPadding(){
                ActionBar bar=bar(2,3,320,2,false);SettingsContent content=new SettingsContent();content.rootHeader=true;content.actionBar=bar;content.listView=new View();content.listView.setPadding(3,36,4,70);
                content.measure(MeasureSpec.makeMeasureSpec(320,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(720,MeasureSpec.EXACTLY));check(content.listView.pTop==bar.mh+12,"初次大字设置首行盖在标题下");check(content.listView.pLeft==3&&content.listView.pRight==4&&content.listView.pBottom==70,"根页安全区改了原侧边或底栏留白");
                int writes=content.listView.paddingWrites;content.measure(MeasureSpec.makeMeasureSpec(320,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(720,MeasureSpec.EXACTLY));check(writes==content.listView.paddingWrites,"尺寸不变仍重复写padding");
                bar.res.conf.fontScale=1;content.measure(MeasureSpec.makeMeasureSpec(240,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(720,MeasureSpec.EXACTLY));check(content.listView.pTop==bar.mh+12,"字体/宽度变化后占位未同步");
                content.rootHeader=false;content.listView.setPadding(3,36,4,70);content.measure(MeasureSpec.makeMeasureSpec(320,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(720,MeasureSpec.EXACTLY));check(content.listView.pTop==36,"子页原padding改变");
            }
            public static void main(String[] args){geometry();fallbackAndTheme();hiddenTheme();gates();settingsPadding();System.out.println("PASS CodexRootHeaderTest: Java 8 actual measure/layout/draw/createLayout/calcOffset, five styles, root gates, font/width/RTL, hidden-theme resize, settings first row, search/action mode fallback");}
        """;
}
