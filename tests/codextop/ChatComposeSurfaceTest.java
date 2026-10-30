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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/** 真实方法 Java 8 合成验收；不运行设备、真实聊天、网络或 Android 构建。 */
public final class ChatComposeSurfaceTest {
    private ChatComposeSurfaceTest() {}

    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path ui = root.resolve("TMessagesProj/src/main/java/org/telegram/ui");
        ClassOrInterfaceDeclaration actual = StaticJavaParser.parse(ui.resolve("Components/chat/ChatInputViewsContainer.java"))
                .getClassByName("ChatInputViewsContainer").orElseThrow();
        StringBuilder fields = new StringBuilder();
        for (String name : new String[]{"INPUT_KEYBOARD_RADIUS", "drawInputBackground", "blurredBackgroundDrawable",
                "underKeyboardBackgroundDrawable", "codexComposeAppearance", "codexComposeAlpha",
                "codexComposeResourcesProvider", "codexComposeFillPaint", "codexComposeStrokePaint", "codexComposeRect",
                "codexComposeGroupingActive", "codexComposeStyle", "codexGroupFillPaint", "codexGroupOuterPaint", "codexGroupStrokePaint",
                "tmpRect", "inputBubbleOffsetLeft", "inputBubbleOffsetRight", "inputBubbleHeightRound",
                "currentBlurredHeight", "imeBottomInset", "needDrawInAppKeyboard", "bubbleInputTranlationY"}) {
            // 仅夹具放宽字段访问以注入合成边界；生产字段声明/初始化仍来自 AST。
            var field = actual.getFieldByName(name).orElseThrow(() -> new AssertionError("缺少真实字段 " + name)).clone();
            field.setPrivate(false);
            fields.append(field).append('\n');
        }
        StringBuilder methods = new StringBuilder();
        for (String name : new String[]{"setCodexComposeAppearance", "updateCodexComposeColors", "updateColors",
                "dispatchDraw", "setInputBubbleOffsets", "setInputBubbleTranslationY", "setInputBubbleAlpha"}) {
            methods.append(method(actual, name)).append('\n');
        }
        MethodDeclaration draw = method(actual, "dispatchDraw");
        if (!draw.findAll(ObjectCreationExpr.class).isEmpty()) throw new AssertionError("draw新增对象分配");
        for (MethodCallExpr call : draw.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.equals("getColor") || name.equals("postInvalidateOnAnimation") || name.startsWith("create")) {
                throw new AssertionError("draw新增颜色查询、动画调度或工厂: " + name);
            }
        }

        // 只解析实际初始化片和实际 getter，避免为一处绑定保留 2.6MiB ChatActivity 的完整 AST。
        String activity = Files.readString(ui.resolve("ChatActivity.java"));
        int start = activity.indexOf("chatInputViewsContainer = new ChatInputViewsContainer(context);");
        int end = activity.indexOf("chatInputViewsContainer.setClipChildren(false);", start);
        if (start < 0 || end < start) throw new AssertionError("缺少原输入容器初始化片");
        String binding = StaticJavaParser.parseBlock("{" + activity.substring(start, end) + "}").toString();
        Matcher getter = Pattern.compile("public Theme\\.ResourcesProvider getResourceProvider\\(\\)\\s*\\{[^{}]*\\}").matcher(activity);
        if (!getter.find()) throw new AssertionError("原主题provider getter变化，需要重审夹具边界");
        String providerGetter = StaticJavaParser.parseMethodDeclaration(getter.group()).toString();

        String production = methods.toString();
        String mutation = args.length > 1 ? args[1] : "";
        if (mutation.equals("hidden")) production = replace(production, "if (drawInputBackground)", "if (true)");
        if (mutation.equals("fallback")) production = replace(production, "blurredBackgroundDrawable.draw(canvas);", "/* removed fallback */");
        if (mutation.equals("provider")) binding = replace(binding, "getResourceProvider()", "resourceProvider");
        if (mutation.equals("translation")) production = replace(production, "tmpRect.offset(0, blurTop + (int) bubbleInputTranlationY);", "tmpRect.offset(0, blurTop);");
        if (mutation.equals("density")) production = replace(production, "codexComposeStrokePaint.setStrokeWidth(dp(1));", "codexComposeStrokePaint.setStrokeWidth(1);");
        if (mutation.equals("alpha")) {
            production = replace(production, "codexComposeFillPaint.setAlpha(codexComposeAlpha);", "/* lost fill alpha on theme update */");
            production = replace(production, "codexComposeStrokePaint.setAlpha(codexComposeAlpha);", "/* lost stroke alpha on theme update */");
        }
        if (!mutation.isEmpty() && !java.util.Arrays.asList("hidden", "fallback", "provider", "translation", "density", "alpha").contains(mutation)) {
            throw new AssertionError("未知负例 " + mutation);
        }

        Path temp = Files.createTempDirectory("codextop-compose-surface-");
        try {
            String code = FIXTURE + "\nclass ChatInputViewsContainer extends PlatformContainer {\n"
                    + fields + "ChatInputViewsContainer(Context ignored){}\nstatic int dp(float value){return PlatformContainer.dp(value);}\n"
                    + production + "\n}\nclass ChatActivityProbe {\n"
                    + "final Context context=new Context();long dialog_id=42;ChatInputViewsContainer chatInputViewsContainer;"
                    + "final Theme.ResourcesProvider resourceProvider=new Colors(0xffee0000,0xff330000);"
                    + "final Colors themeDelegate=new Colors(0x104477bb,0x50778899);"
                    + providerGetter + "void bindCompose()" + binding + "}\n";
            Path source = temp.resolve("org/telegram/ui/Components/chat/ChatComposeProbe.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, code);
            Path runtime = temp.resolve("com/butang/codextop/CodexRuntime.java");
            Files.createDirectories(runtime.getParent());
            Files.writeString(runtime, "package com.butang.codextop;public class CodexRuntime{public static boolean on=true,owns=true;public static boolean enabled(){return on;}public static boolean ownsConversation(long id){return owns&&id==42;}}");
            // 新颜色缓存沿用真实 Palette；平台只提供常量和 SparseIntArray 边界。
            String palette = Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
            Path paletteFile = temp.resolve("com/butang/codextop/CodexPixelPalette.java");
            Files.writeString(paletteFile, palette);
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            Matcher themeKeys = Pattern.compile("Theme\\.(key_[A-Za-z0-9_]+)").matcher(palette);
            while (themeKeys.find()) keys.add(themeKeys.group(1));
            StringBuilder theme = new StringBuilder("package org.telegram.ui.ActionBar;public class Theme {");
            int key = 0; for (String name : keys) theme.append("public static final int ").append(name).append('=').append(++key).append(';');
            theme.append('}');
            Path themeFile = temp.resolve("org/telegram/ui/ActionBar/Theme.java");
            Files.createDirectories(themeFile.getParent()); Files.writeString(themeFile, theme);
            Path sparse = temp.resolve("android/util/SparseIntArray.java");
            Files.createDirectories(sparse.getParent());
            Files.writeString(sparse, "package android.util;public class SparseIntArray{public SparseIntArray(int n){}public void put(int k,int v){}}");
            ArrayList<String> compile = new ArrayList<>();
            compile.add("--release"); compile.add("8"); compile.add("-d"); compile.add(temp.toString());
            compile.add(source.toString()); compile.add(runtime.toString());
            compile.add(paletteFile.toString()); compile.add(themeFile.toString()); compile.add(sparse.toString());
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(new String[0])) != 0) {
                throw new AssertionError("真实输入表面方法 Java 8 夹具编译失败");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("org.telegram.ui.Components.chat.ChatComposeProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实输入表面方法回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    private static MethodDeclaration method(ClassOrInterfaceDeclaration owner, String name) {
        return owner.getMethodsByName(name).stream().findFirst().orElseThrow(() -> new AssertionError("缺少真实方法 " + name));
    }

    private static String replace(String source, String old, String replacement) {
        if (!source.contains(old)) throw new AssertionError("负例未命中 " + old);
        return source.replace(old, replacement);
    }

    private static final String FIXTURE = """
        package org.telegram.ui.Components.chat;
        import java.util.*;import com.butang.codextop.CodexRuntime;
        @interface NonNull {}
        class Context {}
        class Rect {
            int left,top,right,bottom;
            void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
            void inset(int x,int y){left+=x;right-=x;top+=y;bottom-=y;}
            void offset(int x,int y){left+=x;right+=x;top+=y;bottom+=y;}
        }
        class RectF {
            static int allocations;float left,top,right,bottom;
            RectF(){allocations++;}
            void set(Rect rect){left=rect.left;top=rect.top;right=rect.right;bottom=rect.bottom;}
            void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}
        }
        class Paint {
            static final int ANTI_ALIAS_FLAG=1;static int allocations;
            enum Style{FILL,STROKE}Style style=Style.FILL;int color,alpha=255;float strokeWidth;
            Paint(int flags){allocations++;}
            void setColor(int value){color=value;alpha=value>>>24;}
            void setStyle(Style value){style=value;}void setStrokeWidth(float value){strokeWidth=value;}
            float getStrokeWidth(){return strokeWidth;}void setAlpha(int value){alpha=value;}
        }
        class Draw {
            final float left,top,right,bottom,radius,width;final int color,alpha;final Paint.Style style;
            Draw(RectF r,float rx,float ry,Paint p){left=r.left;top=r.top;right=r.right;bottom=r.bottom;radius=rx;width=p.strokeWidth;color=p.color;alpha=p.alpha;style=p.style;if(rx!=ry)throw new AssertionError("圆角轴不一致");}
        }
        class Canvas {
            final List<Draw> shapes=new ArrayList<Draw>();final List<String> events=new ArrayList<String>();
            void drawRoundRect(RectF r,float rx,float ry,Paint p){shapes.add(new Draw(r,rx,ry,p));events.add(p.style==Paint.Style.FILL?"fill":"stroke");}
        }
        class BlurredBackgroundDrawable {
            final Rect bounds=new Rect();final String name;int draws,updates,alpha=255;
            BlurredBackgroundDrawable(String value){name=value;}
            void setBounds(Rect r){setBounds(r.left,r.top,r.right,r.bottom);}
            void setBounds(int l,int t,int r,int b){bounds.set(l,t,r,b);}
            void draw(Canvas canvas){draws++;canvas.events.add(name);}
            void updateColors(){updates++;}void setAlpha(int value){alpha=value;}
        }
        class Colors implements Theme.ResourcesProvider {
            int fill,stroke;Colors(int f,int s){fill=f;stroke=s;}
            public int getColor(int key){return key==Theme.key_chat_messagePanelBackground?fill:stroke;}
        }
        class Theme {
            interface ResourcesProvider{int getColor(int key);}
            static com.butang.codextop.CodexPixelPalette.Style getCodexPixelStyle(){return com.butang.codextop.CodexPixelPalette.metadata(1);}
            static final int key_chat_messagePanelBackground=1,key_actionBarDefaultTitle=2;static int reads;
            static int getColor(int key,ResourcesProvider provider){reads++;return provider==null?(key==1?0xfffafafa:0xff171717):provider.getColor(key);}
        }
        class PlatformContainer {
            static float density=1;int invalidations;int width=360,height=800;
            static int dp(float value){return value==0?0:(int)Math.ceil(density*value);}
            int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}
            void invalidate(){invalidations++;}protected void dispatchDraw(Canvas canvas){canvas.events.add("children");}
        }
        public class ChatComposeProbe {
            static int checks;
            static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
            static void eq(float actual,float expected,String message){check(Math.abs(actual-expected)<0.0001f,message+": "+actual+" != "+expected);}
            static void bounds(Rect actual,int l,int t,int r,int b,String message){check(actual.left==l&&actual.top==t&&actual.right==r&&actual.bottom==b,message+": "+actual.left+","+actual.top+","+actual.right+","+actual.bottom);}
            static void bounds(Draw actual,float l,float t,float r,float b,String message){eq(actual.left,l,message+" left");eq(actual.top,t,message+" top");eq(actual.right,r,message+" right");eq(actual.bottom,b,message+" bottom");}
            static ChatInputViewsContainer prepared(boolean codex){
                ChatInputViewsContainer view=new ChatInputViewsContainer(new Context());
                view.setCodexComposeAppearance(codex,new Colors(0x104477bb,0x50778899));
                view.blurredBackgroundDrawable=new BlurredBackgroundDrawable("blur");
                view.underKeyboardBackgroundDrawable=new BlurredBackgroundDrawable("keyboard");
                view.currentBlurredHeight=80;view.inputBubbleHeightRound=51;view.imeBottomInset=219.9f;
                view.setInputBubbleOffsets(12.4f,18.6f);view.setInputBubbleTranslationY(-12.9f);
                return view;
            }
            static Canvas render(ChatInputViewsContainer view){Canvas canvas=new Canvas();view.dispatchDraw(canvas);return canvas;}
            public static void main(String[] ignored){
                binding();boundaries();visibility();alpha();theme();keyboard();reuse();
                System.out.println("PASS ChatComposeSurfaceTest "+checks+" checks; actual production methods; Java 8 fixture");
            }
            static void binding(){
                PlatformContainer.density=1;
                for(boolean enabled:new boolean[]{false,true})for(boolean owned:new boolean[]{false,true}){
                    CodexRuntime.on=enabled;CodexRuntime.owns=owned;ChatActivityProbe activity=new ChatActivityProbe();activity.bindCompose();
                    ChatInputViewsContainer view=activity.chatInputViewsContainer;
                    check(view!=null,"初始化缺失");
                    // setter runs before the two original drawables are installed; must be safe.
                    check(view.blurredBackgroundDrawable==null&&view.underKeyboardBackgroundDrawable==null,"夹具必须覆盖原初始化顺序");
                    view.blurredBackgroundDrawable=new BlurredBackgroundDrawable("blur");view.underKeyboardBackgroundDrawable=new BlurredBackgroundDrawable("keyboard");
                    view.currentBlurredHeight=80;view.inputBubbleHeightRound=51;
                    Canvas canvas=render(view);
                    if(enabled&&owned){check(canvas.shapes.size()==2,"Codex所属会话未画平面");check(canvas.shapes.get(0).color==0xff4477bb,"绑定旧provider，未使用themeDelegate");}
                    else{check(canvas.shapes.isEmpty(),"非Codex或非所属会话外观被改变");check(view.blurredBackgroundDrawable.draws==1,"非Codex原Blur fallback丢失");}
                }
                CodexRuntime.on=true;CodexRuntime.owns=true;
            }
            static void boundaries(){
                // Fixed external expectations: original 7dp expansion, round offsets, Java int translation,
                // and an inward half-stroke. Values are independent of the production draw code.
                float[][] cases={{1,701,766,1,2},{1.25f,699,768,2,3},{1.5f,697,770,2,3},{2,694,773,2,4},{2.75f,688,779,3,6},{3,687,780,3,6}};
                for(float[] row:cases){
                    PlatformContainer.density=row[0];ChatInputViewsContainer view=prepared(true);Canvas canvas=render(view);
                    bounds(view.blurredBackgroundDrawable.bounds,12,(int)row[1],341,(int)row[2],"原输入岛边界或位移被改变 density="+row[0]);
                    check(canvas.shapes.size()==2,"填充与描边数量不对");float half=row[3]/2;
                    for(Draw draw:canvas.shapes){bounds(draw,12+half,row[1]+half,341-half,row[2]-half,"描边必须向原边界内收");eq(draw.radius,row[4],"2dp圆角密度转换");}
                    check(canvas.shapes.get(0).style==Paint.Style.FILL&&canvas.shapes.get(1).style==Paint.Style.STROKE,"表面层次不对");
                    eq(canvas.shapes.get(1).width,row[3],"1dp描边密度转换");
                    check(view.blurredBackgroundDrawable.draws==0,"Codex平面又画原Blur");
                    check(canvas.events.equals(Arrays.asList("fill","stroke","children")),"原子控件绘制顺序改变");
                }
                PlatformContainer.density=1;ChatInputViewsContainer moved=prepared(true);moved.setInputBubbleTranslationY(12.8f);render(moved);
                bounds(moved.blurredBackgroundDrawable.bounds,12,725,341,790,"正向位移不跟随原setter");
                moved.setInputBubbleOffsets(0.4f,0.6f);moved.setInputBubbleTranslationY(0);render(moved);
                bounds(moved.blurredBackgroundDrawable.bounds,0,713,359,778,"零位移及分数左右边界不跟随原setter");
            }
            static void visibility(){
                for(boolean codex:new boolean[]{false,true}){
                    ChatInputViewsContainer view=prepared(codex);view.drawInputBackground=false;Canvas canvas=render(view);
                    check(canvas.shapes.isEmpty(),"drawInputBackground=false仍画平面");check(view.blurredBackgroundDrawable.draws==0,"drawInputBackground=false仍画原Blur");
                    check(canvas.events.equals(Arrays.asList("children")),"背景关闭影响子控件");
                    view.drawInputBackground=true;canvas=render(view);
                    check(codex?canvas.shapes.size()==2:view.blurredBackgroundDrawable.draws==1,"重新显示未恢复原选择路径");
                }
                ChatInputViewsContainer view=prepared(true);view.setCodexComposeAppearance(false,null);Canvas canvas=render(view);
                check(canvas.shapes.isEmpty()&&view.blurredBackgroundDrawable.draws==1,"关闭平面模式未恢复原Blur");
            }
            static void alpha(){
                PlatformContainer.density=1;ChatInputViewsContainer view=prepared(true);
                for(int value:new int[]{0,128,255}){
                    view.setInputBubbleAlpha(value);check(view.blurredBackgroundDrawable.alpha==value,"原alpha传递丢失");
                    Canvas canvas=render(view);
                    if(value==0)check(canvas.shapes.isEmpty(),"输入alpha=0仍留矩形");
                    else{check(canvas.shapes.size()==2,"输入alpha恢复后形状缺失");for(Draw draw:canvas.shapes)check(draw.alpha==value,"输入Paint未承接alpha");}
                    view.updateColors();canvas=render(view);
                    if(value==0)check(canvas.shapes.isEmpty(),"主题刷新恢复了隐藏输入底板");
                    else for(Draw draw:canvas.shapes)check(draw.alpha==value,"主题刷新丢失输入透明度");
                }
                ChatInputViewsContainer beforeDrawable=new ChatInputViewsContainer(new Context());beforeDrawable.setInputBubbleAlpha(0);
                beforeDrawable.setCodexComposeAppearance(true,new Colors(0xffffffff,0xff000000));
                beforeDrawable.blurredBackgroundDrawable=new BlurredBackgroundDrawable("blur");beforeDrawable.underKeyboardBackgroundDrawable=new BlurredBackgroundDrawable("keyboard");
                check(render(beforeDrawable).shapes.isEmpty(),"先隐藏再启用外观丢失alpha");
                ChatInputViewsContainer original=prepared(false);original.setInputBubbleAlpha(128);render(original);
                check(original.blurredBackgroundDrawable.alpha==128&&original.blurredBackgroundDrawable.draws==1,"原非Codex透明度路径变化");
            }
            static void theme(){
                ChatActivityProbe activity=new ChatActivityProbe();activity.bindCompose();ChatInputViewsContainer view=activity.chatInputViewsContainer;
                view.blurredBackgroundDrawable=new BlurredBackgroundDrawable("blur");view.underKeyboardBackgroundDrawable=new BlurredBackgroundDrawable("keyboard");
                view.inputBubbleHeightRound=51;view.currentBlurredHeight=80;
                activity.themeDelegate.fill=0x30887766;activity.themeDelegate.stroke=0x50112233;view.updateColors();Canvas canvas=render(view);
                check(canvas.shapes.get(0).color==0xff887766&&canvas.shapes.get(1).color==0xff112233,"主题刷新未使用同一可变themeDelegate");
                check(canvas.shapes.get(0).alpha==255&&canvas.shapes.get(1).alpha==255,"普通输入表面不是纯色");
                check(view.blurredBackgroundDrawable.updates==1&&view.underKeyboardBackgroundDrawable.updates==1,"原两份Drawable主题刷新丢失");
                ChatInputViewsContainer original=prepared(false);int reads=Theme.reads;original.updateColors();
                check(Theme.reads==reads,"非Codex新增平面颜色查询");check(original.blurredBackgroundDrawable.updates==1&&original.underKeyboardBackgroundDrawable.updates==1,"非Codex原刷新路径变化");
            }
            static void keyboard(){
                PlatformContainer.density=1;
                for(boolean codex:new boolean[]{false,true})for(boolean input:new boolean[]{false,true}){
                    ChatInputViewsContainer view=prepared(codex);view.needDrawInAppKeyboard=true;view.drawInputBackground=input;Canvas canvas=render(view);
                    check(view.underKeyboardBackgroundDrawable.draws==1,"原应用键盘背景被跳过");bounds(view.underKeyboardBackgroundDrawable.bounds,0,581,360,800,"应用键盘边界变化");
                    check(canvas.events.get(canvas.events.size()-2).equals("keyboard")&&canvas.events.get(canvas.events.size()-1).equals("children"),"键盘/原子控件绘制顺序变化");
                    check(input||canvas.shapes.isEmpty(),"背景false门影响键盘独立绘制");
                }
                ChatInputViewsContainer view=prepared(true);view.imeBottomInset=0;view.needDrawInAppKeyboard=true;render(view);
                bounds(view.underKeyboardBackgroundDrawable.bounds,0,800,360,858,"原无IME键盘圆角延伸变化");
            }
            static void reuse(){
                ChatInputViewsContainer view=prepared(true);int paints=Paint.allocations,rects=RectF.allocations,reads=Theme.reads;
                for(int i=0;i<30;i++)render(view);
                check(Paint.allocations==paints&&RectF.allocations==rects,"draw新增Paint/RectF分配");check(Theme.reads==reads,"draw反复解析颜色");
                check(view.invalidations>0,"外观、位移及主题更新没有请求绘制");
            }
        }
        """;
}
