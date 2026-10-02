package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 直接提取真实气泡缓存、描边门控和透明度方法，注入平台 Bitmap 失败并核对日夜和密度边界。 */
public final class MessageDrawablePixelOutlineTest {
    /** 仅提供独立 JVM 合成入口。 */
    private MessageDrawablePixelOutlineTest() {}

    /** 在隔离目录编译生产方法和真实壁纸类，验证缓存失败重试、透明度、描边与方点的 dp 尺寸。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        CompilationUnit bubble = StaticJavaParser.parse(Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/MessageDrawable.java")));
        MethodDeclaration cache = method(bubble, "getBackgroundDrawable", 0);
        MethodDeclaration gate = method(bubble, "textOutlineColor", 0);
        MethodDeclaration alpha = method(bubble, "setAlpha", 1);
        MethodDeclaration genericDraw = method(bubble, "draw", 2);
        if (genericDraw.toString().contains("Style.STROKE") || genericDraw.toString().contains("textOutlineColor")) {
            throw new AssertionError("描边进入了通用 draw，可能改变阴影或自定义画笔");
        }
        Path wallpaper = root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelWallpaper.java");
        MethodDeclaration wallpaperDraw = method(StaticJavaParser.parse(Files.readString(wallpaper)), "draw", 1);
        if (!wallpaperDraw.findAll(ObjectCreationExpr.class).isEmpty()) throw new AssertionError("壁纸 draw 每帧创建对象");
        Path temp = Files.createTempDirectory("codex-pixel-outline-");
        try {
            writeFixtures(temp);
            put(temp, "org/telegram/ui/ActionBar/ProbeMessageDrawable.java", SCAFFOLD + cache + "\n" + gate + "\n" + alpha + "\n}");
            ArrayList<String> compiler = new ArrayList<>();
            compiler.add("-d");
            compiler.add(temp.toString());
            try (var paths = Files.walk(temp)) {
                paths.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> compiler.add(path.toString()));
            }
            compiler.add(wallpaper.toString());
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)) != 0) {
                throw new AssertionError("描边平台夹具编译失败");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("org.telegram.ui.ActionBar.IndependentDrawableProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new AssertionError("真实描边缓存合成回归失败", error.getCause());
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    /** 按名称和参数数目选真实方法，签名变更时失败，不改测夹具自己的副本。 */
    private static MethodDeclaration method(CompilationUnit unit, String name, int parameters) {
        return unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals(name) && method.getParameters().size() == parameters)
                .findFirst().orElseThrow(() -> new AssertionError("缺少生产方法 " + name));
    }

    /** 只在独立临时目录创建平台记录器和合成探针。 */
    private static void put(Path temp, String name, String source) throws Exception {
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /** 平台桩只记录绘图与分配；生产缓存、门控和 alpha 决策由 main 原样提取。 */
    private static void writeFixtures(Path temp) throws Exception {
        put(temp, "android/graphics/Bitmap.java", """
package android.graphics; import java.util.*; public class Bitmap { public enum Config{ARGB_8888} public static int attempts; public static boolean failNext; public final int w,h; public final List<Paint> ops=new ArrayList<>(); public final List<float[]> rects=new ArrayList<>(); Bitmap(int w,int h){this.w=w;this.h=h;} public static Bitmap createBitmap(int w,int h,Config c){attempts++;if(failNext){failNext=false;throw new RuntimeException("synthetic bitmap failure");}return new Bitmap(w,h);} public int getWidth(){return w;}public int getHeight(){return h;} }
""");
        put(temp, "android/graphics/BitmapShader.java", """
package android.graphics;public class BitmapShader extends Shader{public final Bitmap bitmap;public final TileMode x,y;public BitmapShader(Bitmap b,TileMode x,TileMode y){bitmap=b;this.x=x;this.y=y;}}
""");
        put(temp, "android/graphics/Canvas.java", """
package android.graphics;public class Canvas {public final Bitmap b;public Paint lastPaint;public Canvas(Bitmap b){this.b=b;}public void record(Paint p){b.ops.add(new Paint(p));lastPaint=new Paint(p);}public void drawRect(float l,float t,float r,float bottom,Paint p){b.rects.add(new float[]{l,t,r,bottom});record(p);}public void drawRect(Rect r,Paint p){record(p);}}
""");
        put(temp, "android/graphics/Color.java", """
package android.graphics;public class Color{public static int alpha(int c){return c>>>24;}}
""");
        put(temp, "android/graphics/LinearGradient.java", """
package android.graphics;public class LinearGradient extends Shader { public LinearGradient(int a,int b,int c,int d,int[] e,Object f,TileMode g){} }
""");
        put(temp, "android/graphics/Paint.java", """
package android.graphics;public class Paint { public static int ANTI_ALIAS_FLAG=1;public enum Style{FILL,STROKE} public Style style=Style.FILL;public float width=1;public int color,alpha=255;public Object shader,filter,xfer; public Paint(){} public Paint(int f){} public Paint(Paint p){style=p.style;width=p.width;color=p.color;alpha=p.alpha;} public void setColor(int c){color=c;}public void setStyle(Style s){style=s;}public void setStrokeWidth(float w){width=w;}public void setShader(Object s){shader=s;}public void setColorFilter(Object f){filter=f;}public void setShadowLayer(float a,float b,float c,int d){} public void setXfermode(Object x){xfer=x;}public int getAlpha(){return alpha;}public void setAlpha(int a){alpha=a;} }
""");
        put(temp, "android/graphics/PorterDuff.java", """
package android.graphics;public class PorterDuff { public enum Mode{MULTIPLY,CLEAR} }
""");
        put(temp, "android/graphics/PorterDuffColorFilter.java", """
package android.graphics;public class PorterDuffColorFilter{public PorterDuffColorFilter(int c,PorterDuff.Mode m){} }
""");
        put(temp, "android/graphics/PorterDuffXfermode.java", """
package android.graphics;public class PorterDuffXfermode{public PorterDuffXfermode(PorterDuff.Mode m){} }
""");
        put(temp, "android/graphics/Rect.java", """
package android.graphics;public class Rect { public int l,t,r,b;public Rect(){}public void set(Rect x){l=x.l;t=x.t;r=x.r;b=x.b;}public void set(int a,int c,int d,int e){l=a;t=c;r=d;b=e;} }
""");
        put(temp, "android/graphics/Shader.java", """
package android.graphics;public class Shader { public enum TileMode{CLAMP,REPEAT} }
""");
        put(temp, "android/graphics/drawable/ColorDrawable.java", """
package android.graphics.drawable;public class ColorDrawable extends Drawable{private final int color;public ColorDrawable(int c){color=c;}public int getColor(){return color;}}
""");
        put(temp, "android/graphics/drawable/Drawable.java", """
package android.graphics.drawable;import android.graphics.Rect;public class Drawable { final Rect bounds=new Rect();int alpha=255;public Rect getBounds(){return bounds;}public void setBounds(int l,int t,int r,int b){bounds.set(l,t,r,b);}public void setBounds(Rect r){bounds.set(r);}public void draw(android.graphics.Canvas c){}public int getAlpha(){return alpha;}public void setAlpha(int a){alpha=a;} }
""");
        put(temp, "android/graphics/drawable/NinePatchDrawable.java", """
package android.graphics.drawable;import android.graphics.*;public class NinePatchDrawable extends Drawable{public final Bitmap bitmap;public NinePatchDrawable(Bitmap b,byte[] c,Rect r,Object n){bitmap=b;}public Paint stroke(){return bitmap.ops.stream().filter(p->p.style==Paint.Style.STROKE).findFirst().orElse(null);}}
""");
        put(temp, "com/butang/codextop/CodexRuntime.java", """
package com.butang.codextop;public class CodexRuntime{public static boolean on=true;public static boolean enabled(){return on;}}
""");
        put(temp, "org/telegram/messenger/AndroidUtilities.java", """
package org.telegram.messenger;public class AndroidUtilities{public static float density=1;public static int dp(float v){return (int)Math.ceil(density*v);}public static float dpf2(float v){return density*v;}}
""");
        put(temp, "org/telegram/messenger/SharedConfig.java", """
package org.telegram.messenger;public class SharedConfig{public static int bubbleRadius=12;}
""");
        put(temp, "org/telegram/ui/ActionBar/IndependentDrawableProbe.java", """
package org.telegram.ui.ActionBar;import android.graphics.*;import android.graphics.drawable.*;import org.telegram.messenger.*;import com.butang.codextop.CodexRuntime;public class IndependentDrawableProbe {
 static int checks=0,failures=0;static void check(boolean x,String s){checks++;if(!x)throw new AssertionError(s);}interface Checked{void run();}static void run(String name,Checked r){try{r.run();System.out.println("PASS "+name);}catch(Throwable e){failures++;System.out.println("RED "+name+": "+e.getMessage());}}
 static void reset(){AndroidUtilities.density=1;Theme.active=new Theme.ThemeInfo("Day");CodexRuntime.on=true;Bitmap.failNext=false;}
 public static void main(String[] args) throws Exception {
 run("normal_selected_cache_and_alpha",()->{reset();for(boolean out:new boolean[]{false,true})for(boolean selected:new boolean[]{false,true}){ProbeMessageDrawable p=new ProbeMessageDrawable(0,out,selected);p.setAlpha(128);NinePatchDrawable a=(NinePatchDrawable)p.getBackgroundDrawable();check(a.getAlpha()==128,"new patch alpha");check(a.stroke()!=null,"text border missing");int before=Bitmap.attempts;check(p.getBackgroundDrawable()==a,"cache identity");p.setAlpha(64);check(Bitmap.attempts==before,"alpha allocates bitmap");check(a.getAlpha()==64,"whole patch alpha");for(boolean top:new boolean[]{false,true})for(boolean bottom:new boolean[]{false,true})for(boolean buttons:new boolean[]{false,true}){p.isTopNear=top;p.isBottomNear=bottom;p.botButtonsBottom=buttons;Drawable d=p.getBackgroundDrawable();check(d.getAlpha()==64,"new indexed patch alpha");check(p.getBackgroundDrawable()==d,"indexed cache identity");}}});
 run("scope_and_same_fill_gate_invalidation",()->{reset();ProbeMessageDrawable p=new ProbeMessageDrawable(0,true,false);NinePatchDrawable day=(NinePatchDrawable)p.getBackgroundDrawable();Theme.active.name="Blue";NinePatchDrawable blue=(NinePatchDrawable)p.getBackgroundDrawable();check(blue!=day&&blue.stroke()==null,"same-fill Day to Blue leaves border");Theme.active.name="Night";NinePatchDrawable night=(NinePatchDrawable)p.getBackgroundDrawable();check(night!=blue&&night.stroke()!=null,"Night border missing");CodexRuntime.on=false;check(((NinePatchDrawable)p.getBackgroundDrawable()).stroke()==null,"non Codex border");reset();for(int t:new int[]{1,2})check(((NinePatchDrawable)new ProbeMessageDrawable(t,true,false).getBackgroundDrawable()).stroke()==null,"media/preview border");});
 run("outline_cache_retries_after_failed_rebuild",()->{reset();ProbeMessageDrawable p=new ProbeMessageDrawable(0,true,false);NinePatchDrawable day=(NinePatchDrawable)p.getBackgroundDrawable();Theme.active.name="Blue";Bitmap.failNext=true;check(p.getBackgroundDrawable()==day,"failure did not retain previous patch");int afterFail=Bitmap.attempts;NinePatchDrawable retried=(NinePatchDrawable)p.getBackgroundDrawable();check(Bitmap.attempts==afterFail+1&&retried!=day&&retried.stroke()==null,"failed rebuild stamped outlineColor and suppressed next retry; old border persists");});
 run("stroke_width_scales_with_density",()->{reset();AndroidUtilities.density=3;for(boolean out:new boolean[]{false,true}){NinePatchDrawable p=(NinePatchDrawable)new ProbeMessageDrawable(0,out,false).getBackgroundDrawable();float expected=AndroidUtilities.dpf2(out?1.5f:1f);check(Math.abs(p.stroke().width-expected)<0.001f,"actual stroke="+p.stroke().width+" px expected="+expected+" px at density 3");}});
 run("gradient_alpha_flat_crossfade_and_missing_theme",()->{reset();ProbeMessageDrawable gradient=new ProbeMessageDrawable(0,true,false);gradient.gradientShader=new Shader();int before=Bitmap.attempts;gradient.setAlpha(96);check(Bitmap.attempts==before,"gradient alpha entered NinePatch cache");check(gradient.paint.getAlpha()==96,"gradient paint alpha changed");ProbeMessageDrawable crossfade=new ProbeMessageDrawable(0,true,false);crossfade.isCrossfadeBackground=true;crossfade.setAlpha(64);NinePatchDrawable patch=(NinePatchDrawable)crossfade.getBackgroundDrawable();check(patch.getAlpha()==64&&patch.stroke()!=null,"flat crossfade border alpha");check(patch.bitmap.ops.size()==2,"flat crossfade added a shadow pass");Theme.active=null;check(((NinePatchDrawable)new ProbeMessageDrawable(0,true,false).getBackgroundDrawable()).stroke()==null,"missing active theme still drew border");});

 run("wallpaper_dp_scale_base_color_alpha_and_draw_reuse",()->{reset();try{for(float density:new float[]{2.625f,3f})for(int color:new int[]{0xfff4f7fb,0xff121820}){com.butang.codextop.CodexPixelWallpaper w=new com.butang.codextop.CodexPixelWallpaper(color,density);check(w instanceof ColorDrawable&&w.getColor()==color,"wallpaper base-color sampling changed");java.lang.reflect.Field f=w.getClass().getDeclaredField("shader");f.setAccessible(true);BitmapShader s=(BitmapShader)f.get(w);int size=(int)Math.ceil(16*density);check(s.bitmap.getWidth()==size&&s.bitmap.getHeight()==size,"16 dp tile size");check(s.x==Shader.TileMode.REPEAT&&s.y==Shader.TileMode.REPEAT,"repeat shader mode");float[] dot=s.bitmap.rects.get(0);check(Math.abs((dot[2]-dot[0])-.75f*density)<.001f,"0.75 dp dot size");Canvas c=new Canvas(Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888));int before=Bitmap.attempts;w.setAlpha(96);for(int i=0;i<100;i++)w.draw(c);check(Bitmap.attempts==before,"wallpaper draw allocated bitmap");check(c.lastPaint.getAlpha()==96,"wallpaper dot alpha does not follow drawable");System.out.println("wallpaper density="+density+" tile="+size+"px dot="+(.75f*density)+"px");}}catch(ReflectiveOperationException e){throw new AssertionError(e);}});
 System.out.println("checks="+checks+" failures="+failures);if(failures!=0)throw new AssertionError("synthetic drawable failures="+failures);
 }}
""");
        put(temp, "org/telegram/ui/ActionBar/Theme.java", """
package org.telegram.ui.ActionBar;public class Theme{public static final int key_chat_outBubbleSelected=1,key_chat_inBubbleSelected=2,key_chat_outBubble=3,key_chat_inBubble=4,key_chat_outBubbleShadow=5,key_chat_inBubbleShadow=6,key_chat_outBubbleGradientSelectedOverlay=7;public static class ThemeInfo{public String name;ThemeInfo(String n){name=n;}}public static ThemeInfo active=new ThemeInfo("Day");public static ThemeInfo getActiveTheme(){return active;}}
""");
    }

    private static final String SCAFFOLD = """
package org.telegram.ui.ActionBar;import android.graphics.*;import android.graphics.drawable.*;import java.nio.*;import org.telegram.messenger.*;public class ProbeMessageDrawable extends Drawable {int currentType;final boolean isOut;public boolean isSelected,isTopNear,isBottomNear,botButtonsBottom,isCrossfadeBackground,lastDrawWithShadow;int overrideRoundRadius;float overrideRounding;Shader gradientShader;int alpha=255;Paint paint=new Paint(1),selectedPaint=new Paint(1);Rect backupRect=new Rect();int[][] currentBackgroundDrawableRadius={{-1,-1,-1,-1},{-1,-1,-1,-1},{-1,-1,-1,-1},{-1,-1,-1,-1}};int[] shadowDrawableColor={-1,-1,-1,-1};Drawable[][] backgroundDrawable=new Drawable[4][4];int[][] backgroundDrawableColor={{-1,-1,-1,-1},{-1,-1,-1,-1},{-1,-1,-1,-1},{-1,-1,-1,-1}},outlineColor=new int[4][4];static final int TYPE_TEXT=0,TYPE_MEDIA=1,TYPE_PREVIEW=2;public ProbeMessageDrawable(int t,boolean out,boolean selected){currentType=t;isOut=out;isSelected=selected;setBounds(0,0,100,60);}int dp(float v){return AndroidUtilities.dp(v);}int getColor(int k){return (k==5||k==6)?0x77000000:k==7?0x3364B5EF:0xffeeeeee;}void draw(Canvas c,Paint p){c.record(p);}static ByteBuffer getByteBuffer(int x,int y,int z,int q,int color){return ByteBuffer.allocate(8);}

""";
}
