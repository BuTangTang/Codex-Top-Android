package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/**
 * 编译并执行原 SendButton.onDraw/updateColors、原边界、copyTo 与实例接线。
 * 颜色来自真实 CodexPixelPalette；Canvas/Drawable/动画为确定性的合成替身，不代表 Android 截图。
 * javac -d "$TEST_CLASSES" tests/codextop/SendButtonCodexAppearanceTest.java
 * java -Xmx64m -cp "$TEST_CLASSES" com.butang.codextop.SendButtonCodexAppearanceTest
 * 第二参数可选 shape/arrow/preview/cache/restore/effect-entry/count-entry，变异生产方法后编译执行，必须行为 RED。
 */
public final class SendButtonCodexAppearanceTest {
    private static final String CHAT_SHA = "ca3d40a49e1cab0c761709ed843d6b73f543bb64405f568f841d4786ccd6faf1";
    private static final String ENTER_SHA = "9e2cdce1e190c6be3345b88b2078d78832b7fc0b56ec759c1e3b51949628d565";
    private static final String HOOK = "        chatActivityEnterView.setCodexSendButtonAppearance(codexPlainHeader);\n";
    private static final String ORIGINAL_BACKGROUND = "            updateColors();\n            checkBackgroundRect();\n            if (isNewDesignSendButton) {\n                canvas.drawRoundRect(backgroundRect, dp(RADIUS), dp(RADIUS), backgroundPaint);\n            }\n\n";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path ui = root.resolve("TMessagesProj/src/main/java/org/telegram/ui");
        String enter = Files.readString(ui.resolve("Components/ChatActivityEnterView.java"));
        String chat = Files.readString(ui.resolve("ChatActivity.java"));
        chat = chat.replace("                        cell.refreshCodexDeliveryStatus();\n", "")
                .replace("        chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader, chatInputViewsContainer);\n", "")
                .replace("            chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader && !value, chatInputViewsContainer);\n", "")
                .replace("            actionBar.setCodexPlainChatHeaderDividerVisible(codexPlainHeader && !value);\n", "")
                .replace("                actionBar.updateColors();\n", "");
        String button = enter.substring(enter.indexOf("    public static class SendButton extends View"));
        int cacheStart = button.indexOf("        private boolean codexSendAppearance;");
        int cacheEnd = button.indexOf("        public SendButton(Context context, int resId, Theme.ResourcesProvider resourcesProvider)", cacheStart);
        String cache = button.substring(cacheStart, cacheEnd);
        String draw = method(button, "        protected void onDraw(@NonNull Canvas canvas)");
        String restore = method(button, "        public void updateColors()");
        String ownerSetter = method(enter, "    public void setCodexSendButtonAppearance(boolean enabled)");
        String refreshHook = "        sendButton.updateCodexSendColors();\n";
        check(method(enter.substring(enter.indexOf("    public void setCodexSendButtonAppearance(boolean enabled)")), "    public void updateColors()").contains(refreshHook), "原主题委托未刷新按钮缓存");
        check(chat.indexOf(HOOK) == chat.lastIndexOf(HOOK) && chat.contains(HOOK), "实例接线缺失或不唯一");
        check(CHAT_SHA.equals(sha(chat.replace(HOOK, ""))), "前片 ChatActivity 字节改变");
        String protectedEnter = protectedGroupingEnter(enter).replace("    /** 只给普通 Codex 会话的原发送按钮启用外观，编辑完成与共享预览按钮保持原样。 */\n" + ownerSetter + "\n\n", "")
                .replace(refreshHook, "").replace(cache, "");
        int newStart = protectedEnter.indexOf("            // 原预览、付费及其退出动画、调度、锁定、临时与 loading 状态完整走原绘制。");
        int newEnd = protectedEnter.indexOf("            final boolean inactive = isInactive();", newStart);
        protectedEnter = protectedEnter.substring(0, newStart) + ORIGINAL_BACKGROUND + protectedEnter.substring(newEnd);
        protectedEnter = protectedEnter.replace("if (color != drawableColor || codexSendColorsApplied)", "if (color != drawableColor)")
                .replace("                codexSendColorsApplied = false;\n", "");
        check(ENTER_SHA.equals(sha(protectedEnter)), "发送/附件/emoji/原动画/测量等前片字节改变");

        String mutation = args.length > 1 ? args[1] : "";
        switch (mutation) {
            case "": break;
            case "shape": draw = change(draw, "canvas.drawRoundRect(backgroundRect, codexSendRadius, codexSendRadius, codexSendFillPaint);", "canvas.drawRoundRect(backgroundRect, dp(RADIUS), dp(RADIUS), codexSendFillPaint);"); break;
            case "arrow": cache = change(cache, "filled ? style.onAccentFill : style.accentText", "Color.WHITE"); break;
            case "preview": draw = change(draw, "&& !isOpen() && open.get() == 0f", ""); break;
            case "cache": refreshHook = ""; break;
            case "restore": restore = change(restore, " || codexSendColorsApplied", ""); break;
            case "effect-entry": draw = change(draw, "emojiDrawable.getDrawable() == null && ", ""); break;
            case "count-entry": draw = change(draw, "TextUtils.isEmpty(count.getText()) && ", ""); break;
            default: throw new AssertionError("未知负例 " + mutation);
        }
        String animatedText = Files.readString(ui.resolve("Components/AnimatedTextView.java"));
        String animatedEmoji = Files.readString(ui.resolve("Components/AnimatedEmojiDrawable.java"));
        animatedEmoji = animatedEmoji.substring(animatedEmoji.indexOf("    public static class SwapAnimatedEmojiDrawable"));
        String naturalGetters = method(animatedText, "        public CharSequence getText()") + method(animatedText, "        public float isNotEmpty()")
                + "}static class Emoji {Drawable[] drawables=new Drawable[2];AnimatedFloat changeProgress=new AnimatedFloat(1);"
                + "void set(Drawable d,boolean a){drawables[0]=d;}void setBounds(int l,int t,int r,int b){}void setAlpha(int a){}"
                + "void draw(Canvas c){if(isNotEmpty()>0)c.effects++;}"
                + method(animatedEmoji, "        public Drawable getDrawable()") + method(animatedEmoji, "        public float isNotEmpty()") + "}";
        StringBuilder actual = new StringBuilder(cache).append(draw).append('\n').append(restore).append('\n');
        for (String signature : new String[]{"        public int getCircleWidth()", "        public int getCircleHeight()",
                "        private void checkBackgroundRect()", "        public boolean isOpen()", "        public int getFillColor()",
                "        public int width()", "        public int width(int h)", "        public int height()", "        public int height(int h)",
                "        public boolean onTouchEvent(MotionEvent event)", "        public void copyTo(SendButton btn)"}) {
            actual.append(method(button, signature)).append('\n');
        }
        String liveButton = enter.substring(enter.indexOf("        sendButton = new SendButton(context,"));
        String previewOpen = method(liveButton, "            public boolean isOpen()");
        String gate = statement(chat, "        codexPlainHeader = com.butang.codextop.CodexRuntime.enabled()");
        String palette = Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        var match = Pattern.compile("Theme\\.(key_[A-Za-z0-9_]+)").matcher(palette + button);
        while (match.find()) keys.add(match.group(1));
        StringBuilder theme = new StringBuilder("package org.telegram.ui.ActionBar;import com.butang.codextop.CodexPixelPalette;public class Theme {public interface ResourcesProvider {}public static int reads,styleReads,styleId=1;");
        int key = 0; for (String name : keys) theme.append("public static final int ").append(name).append('=').append(++key).append(';');
        theme.append("public static final com.butang.codextop.SendButtonProbe.Paint PAINT_CLEAR=new com.butang.codextop.SendButtonProbe.Paint(1);public static int getColor(int key,ResourcesProvider provider){reads++;return key==key_chat_messagePanelSend?0xff4455bb:key==key_glass_defaultIcon?0xff667788:0xff99aabb;}public static CodexPixelPalette.Style getCodexPixelStyle(){styleReads++;return CodexPixelPalette.metadata(styleId);}}");
        Path temp = Files.createTempDirectory("codex-send-button-methods-");
        try {
            Path source = temp.resolve("SendButtonProbe.java");
            Files.writeString(source, FIXTURE + naturalGetters + "static class SendButton extends View {" + BUTTON_FIELDS + actual + "}\nstatic class Owner {SendButton sendButton=new SendButton();" + ownerSetter
                    + "void updateColors(){" + refreshHook + "}}\nstatic class Chat {boolean codexPlainHeader,inPreviewMode,inBubbleMode,isInsideContainer,isComments,report;int chatMode,startLoadFromDate;long dialog_id=42;Owner chatActivityEnterView=new Owner();boolean isReport(){return report;}void bind(){" + gate + HOOK + "}}\n" + "static class Preview {boolean showing;boolean isShowing(){return showing;}}static class NormalSendButton extends SendButton {Preview messageSendPreview;" + previewOpen + "}\n" + SCENARIOS + "}\n");
            Path themeFile = temp.resolve("org/telegram/ui/ActionBar/Theme.java"); Files.createDirectories(themeFile.getParent()); Files.writeString(themeFile, theme);
            Path sparse = temp.resolve("android/util/SparseIntArray.java"); Files.createDirectories(sparse.getParent()); Files.writeString(sparse, "package android.util;public class SparseIntArray {public SparseIntArray(int size){}public void put(int key,int value){}}");
            Path runtime = temp.resolve("com/butang/codextop/CodexRuntime.java"); Files.createDirectories(runtime.getParent()); Files.writeString(runtime, "package com.butang.codextop;public class CodexRuntime{public static boolean on=true,owns=true;public static boolean enabled(){return on;}public static boolean ownsConversation(long id){return owns&&id==42;}}");
            Path paletteFile = temp.resolve("com/butang/codextop/CodexPixelPalette.java"); Files.writeString(paletteFile, palette);
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), source.toString(), themeFile.toString(), sparse.toString(), runtime.toString(), paletteFile.toString()) == 0, "真实按钮方法 Java 8 编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try {loader.loadClass("com.butang.codextop.SendButtonProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);}
                catch (java.lang.reflect.InvocationTargetException error) {throw new AssertionError("真实按钮行为失败", error.getCause());}
            }
        } finally {
            try (var paths = Files.walk(temp)) {for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);}
        }
    }

    /** 只在保护副本精确逆本片输入分组；行为方法仍抽取当前生产字节。 */
    private static String protectedGroupingEnter(String source) {
        String protectedEnter = source.replace(section(source,"    private boolean codexComposeGroupingEnabled;","    /** 只给普通 Codex 会话的原发送按钮启用外观"),"");
        protectedEnter = protectedEnter.replace("            @Override\n            protected void dispatchDraw(Canvas canvas) {\n                drawCodexComposeGrouping(canvas);\n                super.dispatchDraw(canvas);\n            }\n\n","")
            .replace("        if (editingMessageObject != null) applyCodexComposeLayout(false);\n","")
            .replace("        if (richDraftActive) applyCodexComposeLayout(false);\n","")
            .replace("        if (isLiveComment) applyCodexComposeLayout(false);\n","")
            .replace("            applyCodexComposeLayout(false);\n","")
            .replace("        applyCodexComposeLayout(false);\n","").replace("        applyCodexComposeLayout(true);\n","")
            .replace("        if (codexComposeGroupingActive) textFieldContainer.invalidate();\n","")
            .replace("codexComposeGroupingActive || com.butang.codextop.CodexRuntime.enabled()","com.butang.codextop.CodexRuntime.enabled()")
            .replace("        if (codexComposeGroupingActive) {\n            layoutParams.rightMargin = dp(8);\n        } else if (isStories && isLiveComment)","        if (isStories && isLiveComment)")
            .replace("        if (codexComposeGroupingActive) {\n            attachButton.setTranslationX(emojiButton.getTranslationX());\n            return;\n        }\n","");
        return protectedEnter;
    }

    private static String section(String source, String begin, String end) {
        int start = source.indexOf(begin), finish = source.indexOf(end, start);
        check(start >= 0 && finish > start, "缺少分组保护片段");
        return source.substring(start, finish);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature); check(start >= 0, "缺少原方法 " + signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int i = open; i < source.length(); i++) {char c = source.charAt(i); if (c == '{') depth++; if (c == '}' && --depth == 0) return source.substring(start, i + 1);}
        throw new AssertionError("方法边界错误 " + signature);
    }
    private static String statement(String source, String start) {int i = source.indexOf(start); check(i >= 0, "缺少原接线"); return source.substring(i, source.indexOf(';', i) + 1);}
    private static String change(String source, String old, String value) {check(source.contains(old), "负例未命中 " + old); return source.replace(old, value);}
    private static String sha(String value) throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b));return result.toString();}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;import org.telegram.ui.ActionBar.Theme;
        public class SendButtonProbe {
        @interface NonNull{}
        static float density=1;static int dp(float v){return (int)Math.ceil(v*density);}static float dpf2(float v){return v*density;}static float lerp(float a,float b,float t){return a+(b-a)*t;}
        static class Color {static final int WHITE=0xffffffff;static int alpha(int c){return c>>>24;}static int red(int c){return(c>>16)&255;}static int green(int c){return(c>>8)&255;}static int blue(int c){return c&255;}static int argb(int a,int r,int g,int b){return(a<<24)|(r<<16)|(g<<8)|b;}}
        static class ColorUtils {static int setAlphaComponent(int c,int a){return(c&0xffffff)|(a<<24);}}
        static class PorterDuff {enum Mode{SRC_IN,MULTIPLY}}
        static class PorterDuffColorFilter {static int allocations;int color;PorterDuffColorFilter(int c,PorterDuff.Mode m){color=c;allocations++;}}
        static class RectF {static int allocations;float left,top,right,bottom;RectF(){allocations++;}void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}void set(RectF r){set(r.left,r.top,r.right,r.bottom);}void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}float height(){return bottom-top;}float centerX(){return(left+right)/2;}float centerY(){return(top+bottom)/2;}void round(Rect r){r.left=Math.round(left);r.top=Math.round(top);r.right=Math.round(right);r.bottom=Math.round(bottom);}}
        static class Rect {int left,top,right,bottom;void inset(int x,int y){left+=x;right-=x;top+=y;bottom-=y;}}
        public static class Paint {static final int ANTI_ALIAS_FLAG=1;static int allocations;enum Style{FILL,STROKE}enum Join{ROUND}enum Cap{ROUND}int color,alpha=255;float width;Style style=Style.FILL;public Paint(int f){allocations++;}void setColor(int c){color=c;}int getColor(){return color;}void setStyle(Style s){style=s;}void setStrokeWidth(float w){width=w;}float getStrokeWidth(){return width;}void setAlpha(int a){alpha=a;}}
        static class Shape {float l,t,r,b,radius,width;int color;Paint.Style style;Shape(RectF f,float radius,Paint p){l=f.left;t=f.top;r=f.right;b=f.bottom;this.radius=radius;color=p.color;style=p.style;width=p.width;}}
        static class Canvas {static final int ALL_SAVE_FLAG=31;List<Shape> shapes=new ArrayList<Shape>();List<Integer> icons=new ArrayList<Integer>();int clips,blur,texts,effects;int save(){return 1;}void restore(){}void restoreToCount(int n){}void saveLayerAlpha(float l,float t,float r,float b,int a,int f){}void drawRoundRect(RectF r,float x,float y,Paint p){shapes.add(new Shape(r,x,p));}void drawCircle(float x,float y,float r,Paint p){}void drawPath(Path p,Paint paint){}void clipPath(Path p){clips++;}void drawArc(RectF f,float a,float b,boolean center,Paint p){}void translate(float x,float y){}void scale(float x,float y,float cx,float cy){}void rotate(float r,float x,float y){}}
        static class Path {enum Direction{CW}void rewind(){}void addRoundRect(RectF r,float x,float y,Direction d){}}
        static class Drawable {int color,alpha=255,l,t,r,b;int getIntrinsicWidth(){return dp(24);}int getIntrinsicHeight(){return dp(24);}void setColorFilter(PorterDuffColorFilter f){color=f==null?0:f.color;}void setColorFilter(int c,PorterDuff.Mode m){color=c;}void setAlpha(int a){alpha=a;}void setBounds(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}void draw(Canvas c){if(alpha>0)c.icons.add(color);}}
        static class AnimatedFloat {float value;AnimatedFloat(float v){value=v;}float get(){return value;}float set(boolean v){value=v?1:0;return value;}float set(float v){value=v;return value;}void force(float v){value=v;}}
        static class Interpolator {float getInterpolation(float x){return Math.max(0,Math.min(1,x));}}
        static class BlurredBackgroundDrawable {void setBounds(Rect r){}void draw(Canvas c){c.blur++;}}
        static class MotionEvent {static final int ACTION_DOWN=0;int action;float x,y;int getAction(){return action;}float getX(){return x;}float getY(){return y;}}
        static class Resources {Drawable getDrawable(int id){return new Drawable();}}static class Context {Resources getResources(){return new Resources();}}
        static class View {Context getContext(){return new Context();}int width=100,height=44,invalidations;float alpha=1,pivotX,pivotY;int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}int getWidth(){return width;}int getHeight(){return height;}float getAlpha(){return alpha;}void setPivotX(float v){pivotX=v;}void setPivotY(float v){pivotY=v;}void invalidate(){invalidations++;}protected void onDraw(Canvas c){}public boolean onTouchEvent(MotionEvent e){return true;}}
        static class TextUtils {static boolean isEmpty(CharSequence t){return t==null||t.length()==0;}}
        static class AndroidUtilities {static final RectF rectTmp=new RectF();static final Rect rectTmp2=new Rect();static float lerp(float a,float b,float t){return SendButtonProbe.lerp(a,b,t);}}
        static class DrawableUtils {static void setBounds(Drawable d,float x,float y,int g){}static void drawWithScale(Canvas c,Drawable d,float s){d.draw(c);}}
        static class Gravity {static final int CENTER=17;}
        static class R {static class drawable {static final int send_plane_24=1,input_schedule=2,input_done=3,send_outline=4;}}
        static class Text {CharSequence oldText,currentText;float t=1,width;void setText(CharSequence text,boolean a){currentText=text;}float getCurrentWidth(){return width;}float getAnimateToWidth(){return width;}void setBounds(float l,float t,float r,float b){}void setAlpha(int a){}void draw(Canvas c){c.texts++;}
        """;

    private static final String BUTTON_FIELDS = """
            final Theme.ResourcesProvider resourcesProvider=null;int resId=R.drawable.send_plane_24;boolean isNewDesignSendButton=true;
            Drawable drawable=new Drawable(),inactiveDrawable=new Drawable(),drawableInverse=new Drawable(),ephemeralOutlineDrawable;
            final Emoji emojiDrawable=new Emoji();float ephemeralFactor,sameWidthFactor;long starsPrice;int messagesCount;boolean hidePrice,locked,center,preview,schedule,inactive,newCounterPos;
            final Text priceText=new Text(),count=new Text();final AnimatedFloat animatedPriceVisible=new AnimatedFloat(0),open=new AnimatedFloat(0),appear=new AnimatedFloat(1),loadingAnimatedShown=new AnimatedFloat(0),loadingAnimatedProgress=new AnimatedFloat(0);
            final Paint backgroundPaint=new Paint(1),loadingPaint=new Paint(1);final Path path=new Path();final RectF backgroundRect=new RectF();static final int RADIUS=19;
            int circleWidth=-1,circleHeight=-1,drawableColor;float circlePadX,circlePadY,countBounceScale=1;boolean loadingShown,infiniteLoading;float loadingProgress;Interpolator loadingInterpolator=new Interpolator();BlurredBackgroundDrawable blurredBackgroundDrawable=new BlurredBackgroundDrawable();
            boolean isInScheduleMode(){return schedule;}boolean isInactive(){return inactive;}boolean shouldDrawBackground(){return false;}void setCircleSize(int w,int h){circleWidth=w;circleHeight=h;}void setCirclePadding(float x,float y){circlePadX=x;circlePadY=y;}void setEmoji(Drawable d){emojiDrawable.set(d,true);}void setStarsPrice(long p,int n){starsPrice=p;messagesCount=n;}
        """;

    private static final String SCENARIOS = """
        static int checks;static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}static void eq(float a,float b,String message){check(Math.abs(a-b)<0.0001f,message+": "+a+" != "+b);}
        static Canvas render(SendButton b){Canvas c=new Canvas();b.onDraw(c);return c;}
        static void original(SendButton b,String reason){Canvas c=render(b);check(c.shapes.size()==1,reason+" original background");eq(c.shapes.get(0).radius,dp(19),reason+" original radius");check(c.shapes.get(0).color==0xff4455bb,reason+" original fill");}
        public static void main(String[] ignored){gates();styles();special();cacheAndTransitions();copiesAndTouch();System.out.println("PASS SendButtonCodexAppearanceTest "+checks+" checks; actual onDraw/colors/geometry/copyTo + strict instance/refresh hooks; Java 8; Android screenshots unverified");}
        static void gates(){for(int k=0;k<11;k++){CodexRuntime.on=true;CodexRuntime.owns=true;Chat c=new Chat();if(k==1)CodexRuntime.on=false;if(k==2)CodexRuntime.owns=false;if(k==3)c.dialog_id=7;if(k==4)c.chatMode=1;if(k==5)c.startLoadFromDate=1;if(k==6)c.inPreviewMode=true;if(k==7)c.inBubbleMode=true;if(k==8)c.isInsideContainer=true;if(k==9)c.isComments=true;if(k==10)c.report=true;c.bind();check(c.chatActivityEnterView.sendButton.codexSendAppearance==(k==0),"ordinary instance gate "+k);}CodexRuntime.on=true;CodexRuntime.owns=true;}
        static void styles(){for(float d:new float[]{1,2.75f})for(int id:new int[]{1,2,3,4,6}){density=d;Theme.styleId=id;Owner o=new Owner();o.setCodexSendButtonAppearance(true);SendButton b=o.sendButton;b.width=dp(100);b.height=dp(44);Canvas c=render(b);CodexPixelPalette.Style s=CodexPixelPalette.metadata(id);int arrow=id==2||id==4?s.onAccentFill:s.accentText;check(c.icons.size()==1&&c.icons.get(0)==arrow,"arrow color "+id);check(c.shapes.size()==(id==6?0:id==1||id==4?2:1),"shape count "+id);if(id!=6){Shape f=c.shapes.get(0);eq(f.l,b.width-dpf2(38)-dpf2(3),"left");eq(f.t,b.height-dpf2(38)-dpf2(3),"top");eq(f.r,b.width-dpf2(3),"right");eq(f.b,b.height-dpf2(3),"bottom");eq(f.radius,dp(id==4?2:id==2?3:4),"radius "+id);check(f.color==(id==2||id==4?s.accentFill:s.surface),"fill "+id);if(c.shapes.size()==2){Shape outline=c.shapes.get(1);check(outline.style==Paint.Style.STROKE&&outline.color==s.outlineColor,"stroke "+id);eq(outline.width,dp(1),"density stroke");eq(outline.l,f.l+dp(1)/2f,"stroke inset");eq(outline.r,f.r-dp(1)/2f,"stroke within bounds");}}}density=1;}
        static void special(){for(int id:new int[]{1,2,3,4,6})for(int k=0;k<17;k++){Theme.styleId=id;Owner o=new Owner();o.setCodexSendButtonAppearance(true);SendButton b=o.sendButton;render(b);if(k==0)b.starsPrice=5;if(k==1)b.open.value=.4f;if(k==2)b.animatedPriceVisible.value=.4f;if(k==3)b.schedule=true;if(k==4)b.inactive=true;if(k==5)b.locked=true;if(k==6)b.ephemeralFactor=.3f;if(k==7)b.sameWidthFactor=.3f;if(k==8)b.loadingShown=true;if(k==9)b.loadingAnimatedShown.value=.3f;if(k==10)b.resId=R.drawable.input_done;if(k==11)b.emojiDrawable.drawables[0]=new Drawable();if(k==12)b.count.currentText="2";if(k==13){b.count.currentText="";b.count.oldText="2";b.count.t=.4f;}if(k==14){b.emojiDrawable.drawables[1]=new Drawable();b.emojiDrawable.changeProgress.value=.4f;}if(k==15){b.emojiDrawable.drawables[0]=new Drawable();b.emojiDrawable.changeProgress.value=0;}if(k==16){b.count.currentText="2";b.count.oldText="";b.count.t=0;}original(b,"special "+id+"/"+k);check(b.drawable.color==Color.WHITE,"special restored primary icon");if(k==0){Canvas c=render(b);check(c.blur==1&&c.clips==1&&c.texts==1,"paid original blur/clip/price");}if(k==13){Canvas c=render(b);check(c.texts==1,"old count still drawn during empty-current exit");b.count.t=1;check(render(b).shapes.size()==(id==6?0:id==1||id==4?2:1),"count exit settled");}if(k==14){Canvas c=render(b);check(c.effects==1,"old effect still drawn during null-current exit");b.emojiDrawable.changeProgress.value=1;check(render(b).shapes.size()==(id==6?0:id==1||id==4?2:1),"effect exit settled");}}
            // 使用原普通按钮匿名类的 isOpen 预览语义；不能只凭 starsPrice 排除。
            Theme.styleId=4;NormalSendButton normal=new NormalSendButton();SendButton b=normal;b.setCodexSendAppearance(true);render(b);normal.messageSendPreview=new Preview();normal.messageSendPreview.showing=true;original(b,"send preview");check(b.drawable.color==Color.WHITE,"preview restores arrow");normal.messageSendPreview.showing=false;original(b,"preview exit animation");check(render(b).shapes.get(0).color==CodexPixelPalette.metadata(4).accentFill,"preview settled returns ordinary");}
        static void cacheAndTransitions(){Theme.styleId=5;Owner missing=new Owner();missing.setCodexSendButtonAppearance(true);check(missing.sendButton.codexSendStyle==2,"cancelled 05 retained as style");check(render(missing.sendButton).shapes.get(0).color==CodexPixelPalette.metadata(2).accentFill,"cancelled style did not fall back");Theme.styleId=1;Owner o=new Owner();o.setCodexSendButtonAppearance(true);SendButton b=o.sendButton;render(b);int reads=Theme.reads,styles=Theme.styleReads,paints=Paint.allocations,rects=RectF.allocations,filters=PorterDuffColorFilter.allocations;for(int n=0;n<40;n++)render(b);check(Theme.reads==reads&&Theme.styleReads==styles,"ordinary draw queried theme/style");check(Paint.allocations==paints&&RectF.allocations==rects&&PorterDuffColorFilter.allocations==filters,"ordinary draw allocated caches");for(int id:new int[]{2,3,4,6,1}){Theme.styleId=id;int invalidations=b.invalidations;o.updateColors();check(b.invalidations>invalidations,"theme refresh not invalidated");Canvas c=render(b);check(b.codexSendStyle==id,"stale style cache");check(c.icons.get(0)==(id==2||id==4?CodexPixelPalette.metadata(id).onAccentFill:CodexPixelPalette.metadata(id).accentText),"stale arrow after refresh");}b.schedule=true;original(b,"first fallback");b.schedule=false;render(b);b.schedule=true;original(b,"second fallback");check(b.drawable.color==Color.WHITE,"white arrow not restored on same original color");b.schedule=false;o.setCodexSendButtonAppearance(false);original(b,"disabled");check(!b.codexSendColorsApplied,"disabled colors retained");}
        static void copiesAndTouch(){Theme.styleId=4;Owner o=new Owner();o.setCodexSendButtonAppearance(true);SendButton b=o.sendButton,copy=new SendButton();b.copyTo(copy);check(!copy.codexSendAppearance&&copy.codexSendFillPaint==null,"appearance propagated to shared preview");original(copy,"shared copy");SendButton done=new SendButton();done.resId=R.drawable.input_done;original(done,"untouched done");float width=b.width(),height=b.height();MotionEvent e=new MotionEvent();e.x=99;e.y=43;check(b.onTouchEvent(e),"original hit refused");e.x=0;check(!b.onTouchEvent(e),"outside hit accepted");b.alpha=0;e.x=99;check(!b.onTouchEvent(e),"hidden accessibility hit");b.alpha=1;render(b);eq(b.width(),width,"width changed");eq(b.height(),height,"height changed");}
        """;
}
