package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;

/**
 * 执行原搜索绘制、主题刷新、blur setter 和首页条件；平台替身只记录绘制参数。
 * javac -cp "$PARSER_JAR" -d "$TEST_CLASSES" tests/codextop/FragmentSearchSurfaceTest.java
 * java -Xmx64m -cp "$TEST_CLASSES:$PARSER_JAR" com.butang.codextop.FragmentSearchSurfaceTest
 * 可传原搜索源及 Dialogs 源路径做旧 20dp / 故障注入负例。生成的真实方法按 Java 8 编译。
 */
public final class FragmentSearchSurfaceTest {
    private static final String PROTECTED_SHA = "4b61205cbba5acc040c8ff7441c687b18489343c9892c38847106413dce53b63";
    private static final String INITIALIZATION_SHA = "23eba354194aa4b6feb6a8944844d248a93d91f1cfe587cd7b7611513d386a0d";

    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path fieldPath = Path.of(args.length > 0 ? args[0] : "TMessagesProj/src/main/java/org/telegram/ui/Components/FragmentSearchField.java");
        Path dialogsPath = Path.of(args.length > 1 ? args[1] : "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java");
        ClassOrInterfaceDeclaration field = StaticJavaParser.parse(fieldPath).getClassByName("FragmentSearchField").orElseThrow();
        StringBuilder protectedMembers = new StringBuilder();
        for (var member : field.getMembers()) {
            if (member.isMethodDeclaration() && List.of("dispatchDraw", "updateColors", "setCodexPageChrome").contains(member.asMethodDeclaration().getNameAsString())) continue;
            if (member.isFieldDeclaration() && member.asFieldDeclaration().getVariables().stream().anyMatch(v -> v.getNameAsString().startsWith("codexSearch") || v.getNameAsString().equals("codexPageChrome"))) continue;
            protectedMembers.append(member.getTokenRange().orElseThrow()).append('\n');
        }
        String digest = sha(protectedMembers.toString());
        check(PROTECTED_SHA.equals(digest), "原测量、RTL、filters、动作或 blur setter 字节发生变化");
        MethodDeclaration draw = field.getMethodsByName("dispatchDraw").get(0);
        check(draw.findAll(ObjectCreationExpr.class).isEmpty(), "绘制中分配了新对象");
        check(draw.findAll(MethodCallExpr.class).stream().noneMatch(m -> m.getScope().map(s -> s.toString().contains("Theme") || s.toString().contains("CodexRuntime")).orElse(false)), "绘制中查询了主题或 Runtime");

        String dialogs = Files.readString(dialogsPath);
        int start = dialogs.indexOf("        fragmentSearchField = new FragmentSearchField(context, resourceProvider) {");
        int end = dialogs.indexOf("        fragmentSearchField.setCloseButtonOnClickListener(", start);
        String initialization = dialogs.substring(start, end);
        String gate = "false";
        var calls = StaticJavaParser.parse("class InitProbe {void init(){" + initialization + "}}").findAll(MethodCallExpr.class).stream().filter(m -> m.getNameAsString().equals("setCodexPageChrome") && m.getScope().map(s -> s.toString().equals("fragmentSearchField")).orElse(false)).toList();
        check(calls.size() <= 1, "首页视觉入口不唯一");
        if (!calls.isEmpty()) {
            gate = calls.get(0).getArgument(0).toString();
            int callStart = initialization.indexOf("        fragmentSearchField.setCodexPageChrome(");
            int callEnd = initialization.indexOf(";", callStart) + 1;
            initialization = initialization.substring(0, callStart) + initialization.substring(callEnd + 1);
        }
        check(INITIALIZATION_SHA.equals(sha(initialization)), "原搜索初始化、padding、pivot 或 alpha 接管发生变化");

        StringBuilder actual = new StringBuilder();
        for (var member : field.getFields()) {
            if (member.getVariables().stream().anyMatch(v -> v.getNameAsString().equals("bg") || v.getNameAsString().equals("isSectionBackground") || v.getNameAsString().equals("isWhiteBackground") || v.getNameAsString().equals("codexPageChrome") || v.getNameAsString().startsWith("codexSearch"))) actual.append(member).append('\n');
        }
        for (String name : List.of("dispatchDraw", "updateColors", "setupBlurredBackground", "setBlurredBackgroundVisibility", "setSectionBackground", "setWhiteBackground", "getThemedColor", "setCodexPageChrome")) {
            for (var method : field.getMethodsByName(name)) actual.append(method).append('\n');
        }
        if (field.getMethodsByName("setCodexPageChrome").isEmpty()) actual.append("public void setCodexPageChrome(boolean enabled) {}\n");
        Path temp = Files.createTempDirectory("codex-search-surface-");
        try {
            Path probe = temp.resolve("FragmentSearchSurfaceProbe.java");
            Path paint = temp.resolve("Paint.java"), rect = temp.resolve("RectF.java"), style = temp.resolve("CodexPixelPalette.java"), runtime = temp.resolve("CodexRuntime.java");
            Files.writeString(probe, FIXTURE + actual + "static final class Home {static final int DIALOGS_TYPE_DEFAULT=0;boolean hasMainTabs=true,onlySelect,inPreviewMode;int initialDialogsType,folderId,communityId;String searchString;boolean enabled(){return " + gate + ";}}\n" + SCENARIOS);
            Files.writeString(paint, PAINT); Files.writeString(rect, RECT);
            Files.writeString(style, "package com.butang.codextop;public class CodexPixelPalette {public static final class Style {public final int id,accentText;public Style(int id,int accentText){this.id=id;this.accentText=accentText;}}}");
            Files.writeString(runtime, "package com.butang.codextop;public class CodexRuntime {public static boolean active=true;public static boolean enabled(){return active;}}");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), probe.toString(), paint.toString(), rect.toString(), style.toString(), runtime.toString()) == 0, "真实搜索方法 Java 8 夹具编译失败");
            try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.FragmentSearchSurfaceProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实搜索方法验证失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    private static String sha(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString();
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }

    private static final String PAINT = """
        package android.graphics;
        public class Paint {
            public static final int ANTI_ALIAS_FLAG=1;public enum Style{FILL,STROKE}
            public int color;public Style style=Style.FILL;public float width;public Paint(int flags){}
            public void setColor(int v){color=v;}public void setStyle(Style v){style=v;}
            public void setStrokeWidth(float v){width=v;}public float getStrokeWidth(){return width;}
        }
        """;
    private static final String RECT = """
        package android.graphics;
        public class RectF {
            public float left,top,right,bottom;
            public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
            public void set(RectF v){set(v.left,v.top,v.right,v.bottom);}
            public void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}
        }
        """;
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;import android.graphics.*;
        public final class FragmentSearchSurfaceProbe extends FrameLayout implements ThemeColorable {
            @interface NonNull{}
            Theme.ResourcesProvider resourcesProvider;
            BlurredBackgroundDrawable blurredBackgroundDrawable;
            final Control searchIcon=new Control(),closeIcon=new Control(),editText=new Control();
            final Group additionalIconsLayout=new Group(),searchFilterLayout=new Group();
            static float density=1;static int dp(float value){return (int)Math.ceil(value*density);}
            static class PorterDuff{enum Mode{MULTIPLY}}
            static class Control extends View {
                void setColorFilter(int c,PorterDuff.Mode m){}void setBackground(Object o){}
                void setHintTextColor(int c){}void setTextColor(int c){}void setCursorColor(int c){}
            }
            static class Group {final List<View> children=new ArrayList<>();int getChildCount(){return children.size();}View getChildAt(int i){return children.get(i);}}
            static class View {void setBackground(Object o){}}
            static class ActionBarMenuItem extends View {Control getIconView(){return null;}static class SearchFilterView extends View {void updateColors(){}}}
            static class Drawable {
                int alpha=255,color,radius,l,t,r,b;boolean shadow;
                void setBounds(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}
                int getAlpha(){return alpha;}void setAlpha(int a){alpha=a;}void draw(Canvas c){c.drawables.add(this);c.events.add("drawable");}
            }
            static class BlurredBackgroundDrawable extends Drawable {int padding,updates;void setRadius(int r){radius=r;}void setPadding(int p){padding=p;}void updateColors(){updates++;}}
            static class Canvas {
                final List<Draw> shapes=new ArrayList<>();final List<Drawable> drawables=new ArrayList<>();final List<String> events=new ArrayList<>();
                void save(){events.add("save");}void restore(){events.add("restore");}
                void drawRoundRect(RectF r,float x,float y,Paint p){shapes.add(new Draw(r,x,y,p));events.add(p.style==Paint.Style.FILL?"fill":"stroke");}
            }
            static class Draw {final float l,t,r,b,x,y,width;final int color;final Paint.Style style;
                Draw(RectF q,float x,float y,Paint p){l=q.left;t=q.top;r=q.right;b=q.bottom;this.x=x;this.y=y;width=p.width;color=p.color;style=p.style;}}
            static class Theme {
                interface ResourcesProvider{boolean isDark();}
                static final int key_windowBackgroundWhite=1,key_windowBackgroundWhiteBlackText=2,key_dialogSearchBackground=3,key_divider=4,key_listSelector=5,key_groupcreate_cursor=6;
                static int id=1,generation;static boolean dark=true,forbid;static int styleReads;
                static boolean isCurrentThemeDark(){guard();return dark;}
                static CodexPixelPalette.Style getCodexPixelStyle(){guard();styleReads++;return new CodexPixelPalette.Style(id,0xFF51AACC+generation);}
                static int getColor(int k,ResourcesProvider p){guard();return 0xFF110000+k+generation;}
                static int multAlpha(int color,float a){return (color&0xFFFFFF)|(((int)(255*a))<<24);}
                static void guard(){if(forbid)throw new AssertionError("draw read Theme");}
                static Drawable createRoundRectDrawable(int radius,int color){Drawable d=new Drawable();d.radius=radius;d.color=color;return d;}
                static Drawable createRoundRectDrawableShadowed(int radius,int color){Drawable d=createRoundRectDrawable(radius,color);d.shadow=true;return d;}
                static Object createSelectorDrawable(int color,int kind,int radius){return null;}
            }
        """;
    private static final String SCENARIOS = """
            static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
            static void close(float a,float b,String why){check(Math.abs(a-b)<0.001f,why+": "+a+" vs "+b);}
            static FragmentSearchSurfaceProbe page(){FragmentSearchSurfaceProbe p=new FragmentSearchSurfaceProbe();p.setPadding(dp(4),dp(4),dp(4),dp(4));p.width=dp(320);p.height=dp(56);p.updateColors();return p;}
            static Canvas draw(FragmentSearchSurfaceProbe p){Canvas c=new Canvas();Theme.forbid=true;try{p.dispatchDraw(c);}finally{Theme.forbid=false;}return c;}
            static void surface(FragmentSearchSurfaceProbe p,float radius,int fill,int outline){
                Canvas c=draw(p);check(c.shapes.size()==2&&c.drawables.isEmpty(),"矩形未替代旧20dp胶囊/blur");
                check(c.events.equals(Arrays.asList("save","fill","stroke","children","restore")),"原children顺序或save/restore改变");
                Draw f=c.shapes.get(0),s=c.shapes.get(1);check(f.style==Paint.Style.FILL&&s.style==Paint.Style.STROKE,"缺少填充或描边");
                check(f.color==fill&&s.color==outline,"未使用有效主题颜色");close(f.x,dp(radius),"主题矩形半径");close(f.y,f.x,"两个圆角半径");close(s.width,dp(1),"描边未按密度缩放");
                close(f.l,p.getPaddingLeft(),"填充左界");close(f.t,p.getPaddingTop(),"填充上界");close(f.r,p.width-p.getPaddingRight(),"填充右界");close(f.b,p.height-p.getPaddingBottom(),"填充下界");
                close(s.l-s.width/2,f.l,"左描边越过原界");close(s.t-s.width/2,f.t,"上描边越过原界");close(s.r+s.width/2,f.r,"右描边越过原界");close(s.b+s.width/2,f.b,"下描边越过原界");close(s.x+s.width/2,f.x,"描边外圆角不贴合填充");
            }
            static void themesAndGeometry(){
                int[] ids={1,2,3,4,6};float[] radii={6,2,8,6,2};
                for(int i=0;i<ids.length;i++){Theme.id=ids[i];FragmentSearchSurfaceProbe p=page();p.setCodexPageChrome(true);surface(p,radii[i],0xFF110003,ids[i]==6?0xFF51AACC:ids[i]==4?0xFF110002:0xFF110004);}
                Theme.id=2;Theme.dark=false;FragmentSearchSurfaceProbe p=page();p.setCodexPageChrome(true);
                Theme.id=4;Theme.generation=20;p.alpha=.37f;p.visibility=4;p.updateColors();surface(p,6,0xFF110017,0xFF110016);check(p.alpha==.37f&&p.visibility==4,"原View整体alpha/visibility被改写");
                density=2;p=page();p.setCodexPageChrome(true);surface(p,6,0xFF110017,0xFF110016);density=1;Theme.generation=0;
            }
            static void blurAndFallback(){
                Theme.id=1;FragmentSearchSurfaceProbe p=page();p.setCodexPageChrome(true);
                BlurredBackgroundDrawable blur=new BlurredBackgroundDrawable();p.setupBlurredBackground(blur);check(blur.radius==20&&blur.padding==4,"后挂blur原配置副作用改变");
                for(float alpha:new float[]{0,.5f,1}){p.setBlurredBackgroundVisibility(alpha);int expected=(int)(255*alpha);check(blur.alpha==expected&&p.bg.alpha==255-expected,"原两层alpha分配改变");surface(p,6,0xFF110003,0xFF110004);}
                int before=blur.updates;p.updateColors();check(blur.updates==before+1,"主题刷新未传给原blur");surface(p,6,0xFF110003,0xFF110004);
                p.setCodexPageChrome(false);p.setBlurredBackgroundVisibility(.5f);Canvas c=draw(p);check(c.shapes.isEmpty()&&c.drawables.size()==2,"非Codex未保留原bg+blur");
                check(c.drawables.get(0)==p.bg&&c.drawables.get(1)==blur&&p.bg.radius==20,"原背景身份/20dp半径/顺序改变");check(p.bg.alpha==128&&blur.alpha==127,"fallback alpha副作用丢失");check(blur.l==0&&blur.t==0&&blur.r==320&&blur.b==56,"原blur扩展bounds改变");
                FragmentSearchSurfaceProbe ordinary=page();check(draw(ordinary).drawables.size()==1,"默认false未保留原背景");
            }
            static void sectionAndWhite(){
                Theme.id=2;FragmentSearchSurfaceProbe p=page();p.setCodexPageChrome(true);p.setSectionBackground();surface(p,2,0xFF110001,0xFF110004);check(p.getPaddingLeft()==3,"section原padding改变");p.setCodexPageChrome(false);check(draw(p).drawables.get(0).shadow,"section原阴影fallback丢失");
                p=page();p.setWhiteBackground();p.setCodexPageChrome(true);surface(p,2,0xFF110001,0xFF110004);p.setCodexPageChrome(false);check(draw(p).drawables.get(0).color==0xFF110001,"white原填色fallback丢失");
            }
            static void homeGate(){
                Home h=new Home();check(h.enabled(),"Codex默认HOME未启用");CodexRuntime.active=false;check(!h.enabled(),"非Codex进入新表面");CodexRuntime.active=true;
                h.hasMainTabs=false;check(!h.enabled(),"无主标签页进入新表面");h.hasMainTabs=true;h.initialDialogsType=1;check(!h.enabled(),"非默认会话页进入新表面");h.initialDialogsType=0;
                h.folderId=1;check(!h.enabled(),"文件夹进入新表面");h.folderId=0;h.communityId=1;check(!h.enabled(),"community进入新表面");h.communityId=0;h.onlySelect=true;check(!h.enabled(),"选择页进入新表面");h.onlySelect=false;
                h.searchString="";check(!h.enabled(),"预置搜索页进入新表面");h.searchString=null;h.inPreviewMode=true;check(!h.enabled(),"预览页进入新表面");
            }
            public static void main(String[] args){themesAndGeometry();blurAndFallback();sectionAndWhite();homeGate();System.out.println("PASS FragmentSearchSurfaceTest: geometry, theme refresh, blur side effects, fallback, section/white, home scope; original layout bytes retained");}
        }
        class FrameLayout {
            int width,height,left,top,right,bottom,visibility;float alpha=1;
            int getWidth(){return width;}int getHeight(){return height;}int getPaddingLeft(){return left;}int getPaddingTop(){return top;}int getPaddingRight(){return right;}int getPaddingBottom(){return bottom;}
            void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}void invalidate(){}
            protected void dispatchDraw(FragmentSearchSurfaceProbe.Canvas c){c.events.add("children");}
        }
        interface ThemeColorable {void updateColors();}
        """;
}
