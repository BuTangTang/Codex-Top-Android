package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;

/**
 * 执行生产顶栏初始化、玻璃回退、菜单测量、ActionBar 测量与主题换色的 Java 8 夹具。
 * 平台替身仅记录原 View 参数；冻结其余源码字节，覆盖返回、标题、RTL、输入和共享 blur 边界。
 * 根页新增分支只在冻结层精确剥离；编译层提取完整真实 helper，非根页继续执行当前完整 onMeasure。
 * javac -cp "$PARSER_JAR" -d "$TEST_CLASSES" tests/codextop/ChatHeaderPlainSurfaceTest.java
 * java -Xmx64m -cp "$TEST_CLASSES:$PARSER_JAR" com.butang.codextop.ChatHeaderPlainSurfaceTest
 * 可依次传 ChatActivity、ActionBar、ActionBarMenu 源路径验证生产方法故障注入负例。
 */
public final class ChatHeaderPlainSurfaceTest {
    private static final String CHAT_SHA = "628460ac8cfa584d369f60ff2b2114aab7193314b078838d0e6fb469ebb4ec1e";
    private static final String BAR_SHA = "7abfe440ee87eeae5af37d21179de2660914d80479ef5bd08687ce66e1727502";
    private static final String MENU_PROTECTED_SHA = "c495dfc7b164cc130786281c31c83575aec4d7e1a2f46bad3466d4e35b41a5b3";

    /** 从限定源码提取真实方法，在独立临时目录编译执行并回收合成文件。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String prefix = "TMessagesProj/src/main/java/org/telegram/ui/";
        String chat = Files.readString(Path.of(args.length > 0 ? args[0] : prefix + "ChatActivity.java"));
        String bar = Files.readString(Path.of(args.length > 1 ? args[1] : prefix + "ActionBar/ActionBar.java"));
        String menu = Files.readString(Path.of(args.length > 2 ? args[2] : prefix + "ActionBar/ActionBarMenu.java"));
        ClassOrInterfaceDeclaration barClass = StaticJavaParser.parse(bar).getClassByName("ActionBar").orElseThrow();
        ClassOrInterfaceDeclaration menuClass = StaticJavaParser.parse(menu).getClassByName("ActionBarMenu").orElseThrow();

        String guard = "codexPlainHeader = false;";
        int guardStart = chat.indexOf("        // 只在普通 Codex 会话恢复完整平面顶栏");
        String guardRaw = "";
        if (guardStart >= 0) {
            int end = chat.indexOf(';', guardStart) + 1;
            guardRaw = chat.substring(guardStart, end);
            guard = guardRaw.substring(guardRaw.indexOf("codexPlainHeader ="));
        }
        int headerStart = chat.indexOf("        if (codexPlainHeader) {", chat.indexOf("public View createView(Context context)"));
        if (headerStart < 0) headerStart = chat.indexOf("        actionBar.setupGlass(", chat.indexOf("public View createView(Context context)"));
        int headerEnd = chat.indexOf("        if (chatMode == MODE_PINNED)", headerStart);
        String headerRaw = chat.substring(headerStart, headerEnd);
        String themeRaw = "";
        int themeStart = chat.indexOf("            // 主题刷新只换完整背景色");
        if (themeStart >= 0) {
            int themeEnd = chat.indexOf("            if (sideControlsButtonsLayout != null)", themeStart);
            themeRaw = chat.substring(themeStart, themeEnd);
        }
        String provider = methodSlice(chat, "    public Theme.ResourcesProvider getResourceProvider()");

        // 保留原顶栏三处豁免，只补已审查的标题初始化、preview 接线及方法注释；其他聊天页字节继续冻结。
        String protectedChat = protectedBlueprintChat(chat)
            .replace("        chatActivityEnterView.setCodexSendButtonAppearance(codexPlainHeader);\n", "")
            .replace("        chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader, chatInputViewsContainer);\n", "")
            .replace("            chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader && !value, chatInputViewsContainer);\n", "")
            .replace("                        cell.refreshCodexDeliveryStatus();\n", "")
            .replace("    private boolean codexPlainHeader;\n", "")
            .replace("        avatarContainer.setCodexHeaderAlignment(codexPlainHeader, Theme.getCodexPixelStyle().id);\n", "")
            .replace("            avatarContainer.setCodexHeaderAlignment(codexPlainHeader && !inPreviewMode, Theme.getCodexPixelStyle().id);\n", "")
            .replace("    /** 切换原预览页面；普通 Codex 标题同时退出或恢复图示对齐，不改变预览几何。 */\n", "");
        if (!guardRaw.isEmpty()) protectedChat = protectedChat.replace("\n" + guardRaw + "\n", "");
        if (!themeRaw.isEmpty()) protectedChat = protectedChat.replace(protectedBlueprintChat(themeRaw), "");
        if (headerRaw.startsWith("        if (codexPlainHeader)")) {
            String originalGlass = headerRaw.substring(headerRaw.indexOf("            actionBar.setupGlass("), headerRaw.lastIndexOf("        }"));
            originalGlass = originalGlass.replaceAll("(?m)^    ", "") + "\n";
            protectedChat = protectedChat.replace(headerRaw, originalGlass);
        }
        check(CHAT_SHA.equals(sha(protectedChat)), "原 ChatActivity 标题、搜索/多选、48dp 条、输入、返回或 Insets 字节改变");

        // 只撤销已审查的根页新增分支，仍用原摘要冻结完整的非根页测量、布局与绘制字节。
        String protectedBar = stripRootHeader(protectedBlueprintBar(bar));
        if (!barClass.getMethodsByName("setupPlainChatHeader").isEmpty()) {
            int start = protectedBar.indexOf("    /** 为普通 Codex 会话恢复原平面导航");
            int end = protectedBar.indexOf("    public void setupGlass(", start);
            protectedBar = protectedBar.substring(0, start) + protectedBar.substring(end);
        }
        protectedBar = protectedBar.replace("    private boolean codexPlainChatHeader;\n", "")
            .replace("    /** 恢复原玻璃导航；只撤销本实例此前由 Codex 平面模式安装的多选背景。 */\n", "");
        int reverseStart = protectedBar.indexOf("        if (codexPlainChatHeader) {");
        if (reverseStart >= 0) {
            int reverseEnd = protectedBar.indexOf("        setBackground(null);", reverseStart);
            protectedBar = protectedBar.substring(0, reverseStart) + protectedBar.substring(reverseEnd);
        }
        check(BAR_SHA.equals(sha(protectedBar)), "原 ActionBar 测量、布局、触摸、动画或共享 blur 字节改变");
        String protectedMenu = menu.replace("    private boolean codexPlainHeader;\n", "");
        int menuStart = protectedMenu.indexOf("    /** 切换原玻璃菜单");
        if (menuStart < 0) menuStart = protectedMenu.indexOf("    public void setGlassMode(boolean glassMode)");
        protectedMenu = protectedMenu.substring(0, menuStart) + protectedMenu.substring(protectedMenu.indexOf("    public int getItemsWidth()", menuStart));
        check(MENU_PROTECTED_SHA.equals(sha(protectedMenu)), "原菜单创建、搜索子项、RTL 布局或动作字节改变");

        StringBuilder methods = new StringBuilder();
        ClassOrInterfaceDeclaration rootHeader = barClass.getMembers().stream()
            .filter(member -> member.isClassOrInterfaceDeclaration() && member.asClassOrInterfaceDeclaration().getNameAsString().equals("CodexRootHeader"))
            .map(member -> member.asClassOrInterfaceDeclaration()).findFirst().orElse(null);
        if (rootHeader != null) methods.append(rootHeader).append('\n');
        for (String name : List.of("setupPlainChatHeader", "setCodexPlainChatHeaderDividerVisible", "updateCodexPlainChatHeaderDividerColors", "updateColors", "setupGlass", "setGlassOnlyBack", "setChatAvatarContainer", "setBackgroundColor", "getThemedColor", "getCurrentActionBarHeight", "getHeaderContentHeight", "onMeasure")) {
            for (MethodDeclaration method : barClass.getMethodsByName(name)) methods.append(method).append('\n');
        }
        if (barClass.getMethodsByName("setupPlainChatHeader").isEmpty()) methods.append("public void setupPlainChatHeader(int color) {}\n");
        StringBuilder menuMethods = new StringBuilder();
        for (String name : List.of("setGlassMode", "setCodexPlainHeader", "onMeasure")) {
            for (MethodDeclaration method : menuClass.getMethodsByName(name)) menuMethods.append(method).append('\n');
        }
        if (menuClass.getMethodsByName("setCodexPlainHeader").isEmpty()) menuMethods.append("public void setCodexPlainHeader(boolean value) {}\n");

        Path temp = Files.createTempDirectory("codex-chat-header-");
        try {
            Path source = temp.resolve("ChatHeaderProbe.java");
            Files.writeString(source, FIXTURE.replace("// ROOT_HEADER_FIELD", rootHeader == null ? "" : "CodexRootHeader codexRootHeader;") + methods + "}\n" + MENU_FIXTURE + menuMethods + "}\n" + CHAT_FIXTURE
                + "void saveGate(){" + guard + "}\nvoid attachHeader(){" + headerRaw + "}\nvoid refreshHeader(){" + themeRaw + "}\n" + provider + "}\n" + SCENARIOS
                + "\nclass CodexPixelPalette {static final int STYLE_BLUEPRINT=6;static class Style {int id=1,ink,secondary,accentFill,accentText,onAccentFill,outlineColor;}}\n");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), source.toString()) == 0, "真实顶栏方法 Java 8 夹具编译失败");
            try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.ChatHeaderProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实顶栏方法验证失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    private static String protectedBlueprintBar(String source) {
        if (!source.contains("    private boolean codexPlainChatHeaderDividerVisible;\n")) return source;
        for (String field : new String[]{"    private boolean codexPlainChatHeaderDividerVisible;\n", "    private int codexPlainChatHeaderStyleId;\n", "    private Paint codexPlainChatHeaderDividerPaint;\n"}) source = removeOnce(source, field, "");
        int start = source.indexOf("    /** 仅撤销或恢复已显式启用的普通聊天底边，");
        int end = source.indexOf("    public void setupGlass(", start);
        check(start >= 0 && end >= 0, "底边 helper 边界缺失"); source = source.substring(0, start) + source.substring(end);
        source = removeOnce(source, "        setCodexPlainChatHeaderDividerVisible(true);\n", "");
        source = removeOnce(source, "            setCodexPlainChatHeaderDividerVisible(false);\n", "");
        source = removeOnce(source, "        updateCodexPlainChatHeaderDividerColors();\n", "");
        for (String indent : new String[]{"            ", "                "}) {
            String raw = indent + "if (codexPlainChatHeaderDividerVisible && codexPlainChatHeaderStyleId == com.butang.codextop.CodexPixelPalette.STYLE_BLUEPRINT) {\n" + indent + "    invalidate();\n" + indent + "}\n";
            source = removeOnce(source, raw, "");
        }
        String raw = methodSlice(source, "        if (codexPlainChatHeader && codexPlainChatHeaderDividerVisible"); source = removeOnce(source, raw + "\n", "");
        return source;
    }
    private static String protectedBlueprintChat(String source) {
        return source.replace("            actionBar.setCodexPlainChatHeaderDividerVisible(codexPlainHeader && !value);\n", "")
            .replace("                actionBar.updateColors();\n", "");
    }
    private static String removeOnce(String source, String old, String replacement) {
        int at = source.indexOf(old);
        check(at >= 0 && at == source.lastIndexOf(old), "底边保护边界不唯一 " + old);
        return source.substring(0, at) + replacement + source.substring(at + old.length());
    }

    /** 精确撤销根页新增接线；保留原导航 else 分支的全部源码，原摘要继续生效。 */
    private static String stripRootHeader(String source) {
        if (!source.contains("    private CodexRootHeader codexRootHeader;\n")) return source;
        source = source.replace("import android.graphics.Path;\n", "")
            .replace("    private CodexRootHeader codexRootHeader;\n", "");
        int start = source.indexOf("    /** 仅由三个 Codex 根页显式启用页头；");
        int end = source.indexOf("    public void setGlassOnlyBack()", start);
        check(start >= 0 && end >= 0, "根页 helper 边界缺失");
        source = source.substring(0, start) + source.substring(end);
        for (String comment : List.of(
                "    /** 原导航控件保持其测量；显式 Codex 根页为实际大标题另留足高度。 */\n",
                "    /** 根页标题采用测量过的安全区域，其余标题、返回及菜单沿用原布局。 */\n",
                "    /** 保留原标题容器布局，仅在显式根页绘制缓存的静态装饰。 */\n",
                "    /** 主题切换时刷新根页缓存并重新测量；普通导航继续原换色路径。 */\n")) source = source.replace(comment, "");
        String begin = "        // 原搜索会隐藏标题；保留已测过的根页高度，避免搜索开始时突然缩小安全区。\n        if (codexRootHeader != null && titleTextView[0] != null && titleTextView[0].getVisibility() != GONE) {\n            codexRootHeader.beginMeasure();\n        }\n";
        check(source.contains(begin), "根页 beginMeasure 范围改变");
        source = source.replace(begin, "");
        source = unwrapRootElse(source, "                if (codexRootHeader != null && titleTextView[i] != null && titleTextView[i].getVisibility() != GONE) {");
        source = unwrapRootElse(source, "            if (codexRootHeader != null && child == actionMode) {");
        String finish = "        if (codexRootHeader != null) {\n            codexRootHeader.finishMeasure();\n            setMeasuredDimension(width, getHeaderContentHeight() + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0));\n        }\n\n";
        check(source.contains(finish), "根页 finishMeasure 范围改变");
        source = source.replace(finish, "");
        source = source.replace("                if (codexRootHeader != null) {\n                    textTop = codexRootHeader.titleY(titleTextView[i].getTextHeight());\n                } else if (", "                if (")
            .replace("                int titleLeft = codexRootHeader == null ? textLeft : codexRootHeader.titleX(titleTextView[i].getMeasuredWidth());\n", "")
            .replace("titleTextView[i].layout(titleLeft, additionalTop", "titleTextView[i].layout(textLeft, additionalTop")
            .replace(" - titleTextView[i].getPaddingTop(), titleLeft + titleTextView[i].getMeasuredWidth(),", " - titleTextView[i].getPaddingTop(), textLeft + titleTextView[i].getMeasuredWidth(),");
        String draw = "                /** 静态页头属于原标题容器，随原搜索、多选和可见性动画一起绘制。 */\n                @Override\n                protected void dispatchDraw(Canvas canvas) {\n                    if (codexRootHeader != null) {\n                        codexRootHeader.draw(canvas, occupyStatusBar ? AndroidUtilities.statusBarHeight : 0);\n                    }\n                    super.dispatchDraw(canvas);\n                }\n\n";
        check(source.contains(draw), "根页静态绘制范围改变");
        source = source.replace(draw, "");
        String colors = "        if (codexRootHeader != null) {\n            codexRootHeader.updateStyle(Theme.getCodexPixelStyle());\n            requestLayout();\n        }\n";
        check(source.contains(colors), "根页主题缓存范围改变");
        source = source.replace(colors, "");
        // 原 ActionBar 以无换行的右括号结尾；根页最终保存只增加一个 LF，其他尾部变更仍参加原摘要。
        return source.endsWith("}\n") ? source.substring(0, source.length() - 1) : source;
    }

    /** 仅移除已审查的根页 if，原 else 方法体退一层缩进后仍逐字节参加原摘要。 */
    private static String unwrapRootElse(String source, String prefix) {
        int start = source.indexOf(prefix);
        check(start >= 0, "根页条件范围缺失 " + prefix);
        int thenEnd = start + methodSlice(source, prefix).length();
        check(source.startsWith(" else {", thenEnd), "根页条件没有原 else");
        int elseStart = thenEnd + 1;
        // 对测量标题和 actionMode 分别取当前条件紧邻的 else，避免匹配前面原有分支。
        int open = source.indexOf('{', elseStart), depth = 1, close = open + 1;
        while (depth > 0) { char c = source.charAt(close++); if (c == '{') depth++; else if (c == '}') depth--; }
        String raw = source.substring(open + 1, close - 1);
        check(raw.startsWith("\n") && source.charAt(close) == '\n', "根页 else 换行边界改变");
        String inner = raw.substring(1, raw.lastIndexOf('\n') + 1).replaceAll("(?m)^    ", "");
        return source.substring(0, start) + inner + source.substring(close + 1);
    }

    /** 仅扫描单个短方法，避免为大型 ChatActivity 建立完整语法树。 */
    private static String methodSlice(String source, String signature) {
        int start = source.indexOf(signature), open = source.indexOf('{', start), depth = 1, end = open + 1;
        check(start >= 0 && open >= 0, "未找到真实方法 " + signature);
        while (depth > 0) { char c = source.charAt(end++); if (c == '{') depth++; else if (c == '}') depth--; }
        return source.substring(start, end);
    }

    /** 计算原源码字节摘要，用于保护本片未授权的生产边界。 */
    private static String sha(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString();
    }

    /** 检查合成夹具前提或边界，失败立即阻止交付。 */
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public class ChatHeaderProbe {
            static float density=1;
            static int dp(float value){return (int)Math.ceil(density*value);}
            static class Theme {
                interface ResourcesProvider{}
                static final int key_actionBarDefault=1,key_actionBarActionModeDefault=2;
                static ResourcesProvider last;
                static int getColor(int key,ResourcesProvider provider){last=provider;return ((Provider)provider).color+key;}
                static CodexPixelPalette.Style getCodexPixelStyle(){return new CodexPixelPalette.Style();}
            }
            static class Provider implements Theme.ResourcesProvider {int color;Provider(int value){color=value;}}
            static class AndroidUtilities {
                static int statusBarHeight=24;static boolean tablet;static Point displaySize=new Point(320,640);
                static boolean isTablet(){return tablet;}
                static Object bold(){return null;}
            }
            static class Point {int x,y;Point(int x,int y){this.x=x;this.y=y;}}
            static class Configuration {static final int ORIENTATION_LANDSCAPE=2;int orientation=1;float fontScale=1;}
            static class LocaleController {static boolean isRTL;}
            static class Gravity {static final int LEFT=3,RIGHT=5,CENTER_VERTICAL=16;}
            static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
            static class Paint {
                static final int ANTI_ALIAS_FLAG=1;enum Style {STROKE}Paint(int flags){}
                void setStyle(Style value){}void setTypeface(Object value){}void setColor(int value){}void setStrokeWidth(float value){}void setTextSize(float value){}
                float measureText(String value){return value.length();}float ascent(){return -8;}float descent(){return 2;}
            }
            static class Path {void reset(){}void moveTo(float x,float y){}void lineTo(float x,float y){}void close(){}}
            static class Canvas {
                void save(){}void restore(){}void translate(float x,float y){}void scale(float x,float y){}
                void drawRect(float l,float t,float r,float b,Paint p){}void drawPath(Path path,Paint p){}
                void drawLine(float x,float y,float x2,float y2,Paint p){}void drawText(String text,float x,float y,Paint p){}
            }
            static class Resources {Configuration configuration=new Configuration();Configuration getConfiguration(){return configuration;}}
            static class MeasureSpec {
                static final int EXACTLY=0x40000000,AT_MOST=0x80000000,UNSPECIFIED=0;
                static int getSize(int v){return v&0x3FFFFFFF;}static int getMode(int v){return v&0xC0000000;}
                static int makeMeasureSpec(int size,int mode){return size|mode;}
            }
            static class View {
                static final int VISIBLE=0,GONE=8;int visibility,mw,mh,w=48,h=48,leftMarginTrace,rightMarginTrace,bgWrites;
                float translationX,alpha=1,pivotX=.5f,pivotY=.5f;Object background;int backgroundColor;
                ViewGroup.LayoutParams params=new ViewGroup.MarginLayoutParams();Resources resources=new Resources();
                int getVisibility(){return visibility;}ViewGroup.LayoutParams getLayoutParams(){return params;}
                void setTranslationX(float v){translationX=v;}float getTranslationX(){return translationX;}
                void setBackground(Object value){background=value;}void setBackgroundColor(int value){backgroundColor=value;background=value;bgWrites++;}
                Drawable getDrawable(){return null;}int getMeasuredWidth(){return mw;}int getMeasuredHeight(){return mh;}
                int getWidth(){return mw;}int getHeight(){return mh;}int getPaddingTop(){return 0;}int getPaddingBottom(){return 0;}
                void setPadding(int l,int t,int r,int b){}void setPivotX(float v){pivotX=v;}void setPivotY(float v){pivotY=v;}
                Resources getResources(){return resources;}void measure(int x,int y){onMeasure(x,y);}
                protected void onMeasure(int x,int y){mw=MeasureSpec.getMode(x)==MeasureSpec.EXACTLY?MeasureSpec.getSize(x):Math.min(w,MeasureSpec.getSize(x));mh=MeasureSpec.getMode(y)==MeasureSpec.EXACTLY?MeasureSpec.getSize(y):Math.min(h,MeasureSpec.getSize(y));}
                void setMeasuredDimension(int x,int y){mw=x;mh=y;}
                void invalidate(){}void requestLayout(){}void updateColors(){}
            }
            static class ViewGroup extends View {
                static class LayoutParams{}static class MarginLayoutParams extends LayoutParams {int leftMargin,rightMargin,width=48,height=48;}
                List<View> children=new ArrayList<>();int getChildCount(){return children.size();}View getChildAt(int i){return children.get(i);}void addView(View v){children.add(v);}
            }
            static class FrameLayout extends ViewGroup {
                static class LayoutParams extends MarginLayoutParams{}boolean clipChildren=true;int clips;
                void setClipChildren(boolean value){clipChildren=value;clips++;}
                void measureChildWithMargins(View v,int ws,int wu,int hs,int hu){v.measure(ws,hs);}
            }
            static class LinearLayout extends ViewGroup {
                boolean rtl;int superMeasures;
                protected void onMeasure(int ws,int hs){superMeasures++;int total=0;
                    for(View v:children){if(v.visibility==GONE)continue;v.measure(MeasureSpec.makeMeasureSpec(v.w,MeasureSpec.EXACTLY),hs);
                        int margins=0;if(v.params instanceof MarginLayoutParams){MarginLayoutParams lp=(MarginLayoutParams)v.params;margins=lp.leftMargin+lp.rightMargin;}
                        total+=v.mw+margins;}
                    mw=MeasureSpec.getMode(ws)==MeasureSpec.EXACTLY?MeasureSpec.getSize(ws):Math.min(total,MeasureSpec.getSize(ws));mh=MeasureSpec.getSize(hs);
                }
            }
            static class SimpleTextView extends View {
                void setTextSize(int v){}void setTextSizePx(int v){}void setTextColor(int v){}void setGravity(int v){}
                int getTextWidth(){return w;}int getTextHeight(){return h;}CharSequence getText(){return "真实标题";}TextPaint getTextPaint(){return new TextPaint();}
            }
            static class TextPaint {float measureText(CharSequence t,int s,int e){return e-s;}float measureText(String t){return t.length();}}
            static class Drawable{}
            static class MenuDrawable extends Drawable {void setBackColor(int c){}}
            static class ChatAvatarContainer extends View {void setCodexHeaderAlignment(boolean enabled,int style){}}
            static class BlurredBackgroundColorProvider{}
            static class BlurredBackgroundDrawable {
                BlurredBackgroundDrawable setColorProvider(BlurredBackgroundColorProvider p){return this;}
                BlurredBackgroundDrawable setPadding(int p){return this;}BlurredBackgroundDrawable setRadius(int...r){return this;}void updateColors(){}
            }
            static class BlurredBackgroundDrawableViewFactory {int creates;BlurredBackgroundDrawable create(View v){creates++;return new BlurredBackgroundDrawable();}}
            static class ActionBar extends FrameLayout {
                BlurredBackgroundDrawable glassDrawable,glassDrawableBack,glassDrawableMenu;
                boolean codexPlainChatHeaderDividerVisible;int codexPlainChatHeaderStyleId;Paint codexPlainChatHeaderDividerPaint;
                void adaptive_updateColor(){}
                boolean glassMode,codexPlainChatHeader,glassModeIsForum,glassOnlyBack,blurredBackground,ignoreLayoutRequest,occupyStatusBar=true;
                boolean isSearchFieldVisible,menuOccupyBack,isMenuOffsetSuppressed,fromBottom,overlayTitleAnimation,titleAnimationRunning,centerScale,actionModeVisible;
                int actionBarColor,extraHeight,titleRightMargin;Theme.ResourcesProvider resourcesProvider=new Provider(0x00112200);
                ActionBarMenu menu,actionMode;View backButtonImageView,actionModeTop,avatarSearchImageView,additionalSubTitleOverlayContainer;
                ChatAvatarContainer chatAvatarContainer;SimpleTextView[] titleTextView=new SimpleTextView[2];SimpleTextView subtitleTextView,additionalSubtitleTextView;
                // ROOT_HEADER_FIELD
                float searchFactor=.37f,actionModeFactor=.62f;Object animatorHasMenuItems=new Object(),animatorMenuItemsWidth=new Object(),animatorAvatarContainerWidth=new Object(),contentView=new Object();
        """;

    private static final String MENU_FIXTURE = """
            static class ActionBarMenuItem extends View {Object textView;ActionBarMenuItem(boolean text){if(text)textView=new Object();}}
            static class ActionBarMenu extends LinearLayout {
                boolean glassMode,codexPlainHeader,search;int itemShift=73;boolean isActionMode;
                boolean searchFieldVisible(){return search;}int getItemsMeasuredWidth(boolean v){return 48;}
                void translateXItems(int v){itemShift=v;}
        """;

    private static final String CHAT_FIXTURE = """
            static class ChatObject {static boolean isForum(Object chat){return false;}}
            static class BlurredBackgroundProviderImpl {static BlurredBackgroundColorProvider topPanelChatActivity(Theme.ResourcesProvider provider){return new BlurredBackgroundColorProvider();}}
            static class Chat {
                boolean codexPlainHeader,inPreviewMode,inBubbleMode,isInsideContainer,isComments,report;
                int chatMode,startLoadFromDate;long dialog_id=42;Object currentChat;
                ActionBar actionBar=new ActionBar();Provider themeDelegate=new Provider(0x66113300),resourceProvider=new Provider(0x00779900);
                ChatAvatarContainer avatarContainer;
                BlurredBackgroundDrawableViewFactory glassBackgroundDrawableFactory=new BlurredBackgroundDrawableViewFactory();
                boolean isReport(){return report;}
        """;

    private static final String SCENARIOS = """
            static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
            static void close(float a,float b,String reason){check(Math.abs(a-b)<.001f,reason+": "+a+" vs "+b);}
            static ActionBarMenu menu(){ActionBarMenu m=new ActionBarMenu();ActionBarMenuItem icon=new ActionBarMenuItem(false),text=new ActionBarMenuItem(true);text.w=80;
                View search=new View();search.w=44;ViewGroup.MarginLayoutParams sp=(ViewGroup.MarginLayoutParams)search.params;sp.leftMargin=3;sp.rightMargin=7;
                m.addView(icon);m.addView(text);m.addView(search);return m;}
            static ViewGroup.MarginLayoutParams lp(View v){return (ViewGroup.MarginLayoutParams)v.params;}
            static void measureMenu(ActionBarMenu m){m.measure(MeasureSpec.makeMeasureSpec(dp(320),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(dp(56),MeasureSpec.EXACTLY));}
            static void margins(ActionBarMenu m,int icon,int text){check(lp(m.children.get(0)).leftMargin==icon&&lp(m.children.get(0)).rightMargin==icon,"图标未恢复原边距");
                check(lp(m.children.get(1)).leftMargin==text&&lp(m.children.get(1)).rightMargin==text,"文字未恢复原14dp边距");
                check(lp(m.children.get(2)).leftMargin==3&&lp(m.children.get(2)).rightMargin==7,"非菜单搜索子项边距改变");}
            static void plain(ActionBar b){check(b.codexPlainChatHeader&&!b.glassMode&&!b.glassModeIsForum&&!b.glassOnlyBack,"玻璃模式旗标残留");
                check(b.glassDrawable==null&&b.glassDrawableBack==null&&b.glassDrawableMenu==null,"玻璃绘制对象残留");
                check(b.chatAvatarContainer==null,"复用头像玻璃关联残留");check(!b.clipChildren,"原聊天clipChildren改变");
                if(b.menu!=null){close(b.menu.translationX,0,"主菜单位移未恢复");check(!b.menu.glassMode&&b.menu.codexPlainHeader,"主菜单边距恢复未opt-in");}
                if(b.actionMode!=null){close(b.actionMode.translationX,0,"多选菜单位移未恢复");check(!b.actionMode.glassMode&&b.actionMode.codexPlainHeader,"多选边距恢复未opt-in");
                    check(b.actionMode.backgroundColor==((Provider)b.resourcesProvider).color+Theme.key_actionBarActionModeDefault,"多选原主题背景未恢复");}
                if(b.backButtonImageView!=null)close(b.backButtonImageView.translationX,0,"返回按钮位移未恢复");
            }
            static void freshAndNull(){Chat c=new Chat();c.saveGate();check(c.codexPlainHeader,"普通Codex guard未启用");c.attachHeader();plain(c.actionBar);
                check(c.actionBar.menu==null&&c.actionBar.actionMode==null&&c.actionBar.backButtonImageView==null,"空控件被新增");
                check(c.actionBar.backgroundColor==(c.themeDelegate.color+1|0xFF000000),"完整背景未取themeDelegate/未全不透明");check(Theme.last==c.themeDelegate,"使用旧resourceProvider");
                check(c.actionBar.background instanceof Integer,"原普通背景未安装");check(c.glassBackgroundDrawableFactory.creates==0,"plain仍创建玻璃岛");}
            static void reusedGlass(){Chat c=new Chat();ActionBar b=c.actionBar;b.menu=menu();b.actionMode=menu();b.backButtonImageView=new View();
                b.setupGlass(c.glassBackgroundDrawableFactory,new BlurredBackgroundColorProvider(),true);b.setGlassOnlyBack();b.setChatAvatarContainer(new ChatAvatarContainer());
                measureMenu(b.menu);measureMenu(b.actionMode);margins(b.menu,-dp(5),-dp(5));
                Object aa=b.animatorHasMenuItems,mm=b.animatorMenuItemsWidth,av=b.animatorAvatarContainerWidth,blur=b.contentView;
                b.actionMode.alpha=.4f;b.actionMode.visibility=4;b.actionModeVisible=true;b.menu.pivotX=.77f;b.backButtonImageView.pivotX=.22f;
                c.saveGate();c.attachHeader();plain(b);measureMenu(b.menu);measureMenu(b.actionMode);margins(b.menu,0,dp(14));margins(b.actionMode,0,dp(14));
                check(b.menu.mw==210&&b.actionMode.mw==210,"原icon/text/search宽度与14dp边距未由生产测量恢复");
                close(b.searchFactor,.37f,"搜索因子改变");close(b.actionModeFactor,.62f,"多选因子改变");
                check(aa==b.animatorHasMenuItems&&mm==b.animatorMenuItemsWidth&&av==b.animatorAvatarContainerWidth,"替换原动画对象");
                check(b.actionModeVisible&&b.actionMode.visibility==4&&b.actionMode.alpha==.4f,"重置多选可见性");
                check(b.menu.pivotX==.77f&&b.backButtonImageView.pivotX==.22f,"提前重设pivot");check(blur==b.contentView&&!b.blurredBackground,"共享blur字段改变");}
            static void scope(){for(int which=0;which<12;which++){Chat c=new Chat();CodexRuntime.active=true;CodexRuntime.owned=true;
                    switch(which){case 0:CodexRuntime.active=false;break;case 1:CodexRuntime.owned=false;break;case 2:c.chatMode=1;break;case 3:c.chatMode=2;break;case 4:c.chatMode=3;break;
                        case 5:c.report=true;break;case 6:c.startLoadFromDate=123;break;case 7:c.inPreviewMode=true;break;case 8:c.inBubbleMode=true;break;case 9:c.isInsideContainer=true;break;case 10:c.isComments=true;break;case 11:c.chatMode=99;break;}
                    c.saveGate();check(!c.codexPlainHeader,"排除模式进入plain: "+which);c.attachHeader();check(c.actionBar.glassMode&&c.glassBackgroundDrawableFactory.creates==3,"排除模式未走原setupGlass: "+which);
                    check(c.actionBar.glassDrawable!=null&&c.actionBar.glassDrawableBack!=null&&c.actionBar.glassDrawableMenu!=null,"原三玻璃绘制对象丢失");}
                CodexRuntime.active=CodexRuntime.owned=true;}
            static void plainToGlassAndOrdinary(){ActionBar b=new ActionBar();b.menu=menu();b.actionMode=menu();b.backButtonImageView=new View();b.setupPlainChatHeader(0xFF223344);measureMenu(b.menu);
                b.setupGlass(new BlurredBackgroundDrawableViewFactory(),new BlurredBackgroundColorProvider(),false);
                check(b.glassMode&&b.glassDrawable!=null&&b.glassDrawableBack!=null&&b.glassDrawableMenu!=null,"plain到glass未恢复原绘制");
                check(!b.codexPlainChatHeader&&b.actionMode.background==null,"plain到glass多选背景未恢复透明");
                check(!b.menu.codexPlainHeader&&!b.actionMode.codexPlainHeader,"glass未撤销plain标记");close(b.menu.translationX,-dp(10),"原glass主菜单位移");close(b.backButtonImageView.translationX,dp(2),"原glass返回位移");
                measureMenu(b.menu);margins(b.menu,-dp(5),-dp(5));b.menu.setGlassMode(false);lp(b.menu.children.get(0)).leftMargin=9;lp(b.menu.children.get(0)).rightMargin=11;measureMenu(b.menu);
                check(lp(b.menu.children.get(0)).leftMargin==9&&lp(b.menu.children.get(0)).rightMargin==11,"撤销glass后共享普通菜单被强改边距");
                ActionBarMenu ordinary=menu();lp(ordinary.children.get(0)).leftMargin=6;lp(ordinary.children.get(1)).rightMargin=17;measureMenu(ordinary);
                check(lp(ordinary.children.get(0)).leftMargin==6&&lp(ordinary.children.get(1)).rightMargin==17,"未opt-in普通Telegram菜单被改");
                ActionBar ordinaryBar=new ActionBar();ordinaryBar.actionMode=menu();ordinaryBar.actionMode.setBackgroundColor(19);
                ordinaryBar.setupGlass(new BlurredBackgroundDrawableViewFactory(),new BlurredBackgroundColorProvider(),false);
                check(ordinaryBar.actionMode.backgroundColor==19&&ordinaryBar.actionMode.background.equals(19),"共享原glass路径误清多选背景");
                ActionBar shared=new ActionBar();shared.blurredBackground=true;Object background=new Object();shared.background=background;Object content=shared.contentView;
                shared.setupPlainChatHeader(37);check(shared.blurredBackground&&shared.contentView==content&&shared.background==background&&shared.actionBarColor==37,"平面初始化改共享blur控制");
                density=2;b=new ActionBar();b.menu=menu();b.setupPlainChatHeader(1);measureMenu(b.menu);margins(b.menu,0,28);density=1;}
            static void themeOnly(){Chat c=new Chat();c.actionBar.menu=menu();c.actionBar.actionMode=menu();c.actionBar.backButtonImageView=new View();c.saveGate();c.attachHeader();ActionBar b=c.actionBar;
                b.menu.translationX=13;b.actionMode.translationX=17;b.backButtonImageView.translationX=6;b.menu.codexPlainHeader=false;b.menu.alpha=.23f;b.menu.visibility=4;
                int clips=b.clips,writes=b.bgWrites,modeWrites=b.actionMode.bgWrites;Object aa=b.animatorHasMenuItems;CodexRuntime.forbid=true;
                c.themeDelegate.color=0x44119900;c.refreshHeader();CodexRuntime.forbid=false;
                check(b.bgWrites==writes+1&&b.clips==clips&&b.actionMode.bgWrites==modeWrites,"主题刷新重新调用初始化");
                check(b.backgroundColor==0xFF119901&&Theme.last==c.themeDelegate,"主题刷新未用有效provider更新完整背景");
                check(b.menu.translationX==13&&b.actionMode.translationX==17&&b.backButtonImageView.translationX==6&&!b.menu.codexPlainHeader,"主题刷新重置菜单状态");
                check(b.menu.alpha==.23f&&b.menu.visibility==4&&b.searchFactor==.37f&&b.actionModeFactor==.62f&&b.animatorHasMenuItems==aa,"主题刷新改原搜索/多选进度");
                c.actionBar=null;c.refreshHeader();Chat fallback=new Chat();fallback.codexPlainHeader=false;fallback.actionBar.backgroundColor=7;fallback.refreshHeader();check(fallback.actionBar.backgroundColor==7,"非plain主题刷新覆盖原背景");}
            static void realMeasure(){for(boolean rtl:new boolean[]{false,true}){ActionBar b=new ActionBar();b.menu=menu();b.menu.rtl=rtl;b.backButtonImageView=new View();b.extraHeight=7;b.setupPlainChatHeader(1);
                    int width=MeasureSpec.makeMeasureSpec(320,MeasureSpec.EXACTLY),height=MeasureSpec.makeMeasureSpec(640,MeasureSpec.EXACTLY);b.onMeasure(width,height);
                    check(b.mw==320&&b.mh==87,"原56dp+status+extraHeight测量改变");check(b.backButtonImageView.mw==54&&b.backButtonImageView.mh==56,"原54dp返回触控几何改变");check(b.menu.mw==210&&b.menu.mh==56,"原菜单测量改变");
                    b.occupyStatusBar=false;b.onMeasure(width,height);check(b.mh==63,"原状态栏占位开关丢失");
                    AndroidUtilities.displaySize=new Point(640,320);b.onMeasure(width,height);check(b.mh==55&&b.backButtonImageView.mh==48,"原横屏48dp高度改变");
                    b.isSearchFieldVisible=true;b.menu.search=true;b.onMeasure(width,height);check(b.menu.mw==254&&b.menu.itemShift==0,"原搜索控件测量/菜单因子改变");
                    b.menu.itemShift=31;b.isMenuOffsetSuppressed=true;b.onMeasure(width,height);check(b.menu.itemShift==31,"原搜索位移抑制行为改变");AndroidUtilities.displaySize=new Point(320,640);}}
            public static void main(String[] args){freshAndNull();reusedGlass();scope();plainToGlassAndOrdinary();themeOnly();realMeasure();System.out.println("PASS ChatHeaderPlainSurfaceTest: fresh/null, reused glass, 12 excluded modes, menu icon/text/search, plain-to-glass, theme-only, real 56/48dp+Insets/search measurement; original layout/RTL/touch/input/blur bytes retained");}
        }
        class CodexRuntime {static boolean active=true,owned=true,forbid;static boolean enabled(){if(forbid)throw new AssertionError("theme refresh recalculated guard");return active;}static boolean ownsConversation(long id){if(forbid)throw new AssertionError("theme refresh reread owner");return owned&&id==42;}}
        """;
}
