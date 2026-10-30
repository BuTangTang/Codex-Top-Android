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
 * Host JDK17; extracted production layout/draw/measure/click methods compile with --release 8.
 * Rich/live methods are complete; checkSendButton uses its original early guard and attachment
 * target statements, while the unchanged remainder is protected by inverse byte SHA checks.
 * Android frames, density and animation queries are deterministic synthetic boundaries.
 * This verifies actor grouping and restoration, not packaged-device font or touch acceptance.
 * javac -d <temp>/classes tests/codextop/ComposeGroupingTest.java
 * java -Xmx64m -cp <temp>/classes com.butang.codextop.ComposeGroupingTest [repo] [mutation]
 */
public class ComposeGroupingTest {
    private static final String ENTER_SHA = "931222e969009dc9342f3e007f09369f7a96690994400780fc8ac704251960ed";
    private static final String CONTAINER_SHA = "187235e9242d1523fee301d1ca1b46b9321aa2043a933948168880d96f9d950a";
    private static final String CHAT_SHA = "a92cbd6f26c38bc9661d490560879e8e07e50ac6075501db4108b71c8b55b9ec";
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path ui = root.resolve("TMessagesProj/src/main/java/org/telegram/ui");
        String enter = Files.readString(ui.resolve("Components/ChatActivityEnterView.java"));
        String container = Files.readString(ui.resolve("Components/chat/ChatInputViewsContainer.java"));
        String chat = Files.readString(ui.resolve("ChatActivity.java"));
        protectOriginal(enter, container, chat);
        String grouping = section(enter, "    private boolean codexComposeGroupingEnabled;", "    /** 只给普通 Codex 会话的原发送按钮启用外观");
        String containerFields = section(container, "    private boolean codexComposeAppearance;", "    /** 只替换输入岛表面");
        String groupContainer = section(container, "    private boolean codexComposeGroupingActive;", "    public void setInputIslandBubbleDrawable");
        String refresh = method(container, "    private void updateCodexComposeColors()");
        String right = method(enter, "    private void updateFieldRight(int attachVisible)");
        String translation = method(enter, "    private void updateAttachButtonTranslationX()");
        String measure = method(enter.substring(enter.indexOf("    int botCommandLastPosition = -1;")), "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)");
        String nativeHostDraw = method(enter.substring(enter.indexOf("        textFieldContainer = new FrameLayout(context) {")), "            protected void dispatchDraw(Canvas canvas)");
        String clipClick = method(enter.substring(enter.indexOf("            attachButton.setOnClickListener(v -> {")), "            attachButton.setOnClickListener(v ->");
        clipClick += ");";
        String clipTouch = method(enter.substring(enter.indexOf("            attachButton = new ImageView(context) {")), "                public boolean dispatchTouchEvent(MotionEvent event)");
        String send = method(enter, "    public void checkSendButton(boolean animated)");
        String sendEarly = send.substring(send.indexOf('{') + 1, send.indexOf("        if (isPaused)"));
        String attachmentSet = method(send.substring(send.indexOf("            final boolean codexEmptyInput =")), "                    if (attachLayout != null) {\n                        runningAnimation2 = new AnimatorSet();");
        String edit = method(enter, "    public void setEditingMessageObject(MessageObject messageObject, MessageObject.GroupedMessages groupedMessages, boolean caption)");
        String editingClip = method(edit, "            if (attachButton != null) {\n                attachButton.setAlpha(attachButtonAlpha = 0.0f);");
        String empty = statement(send, "            final boolean codexEmptyInput =");
        String attachTarget = statement(send, "                    final boolean hideAttachButton =");
        int animStart = send.indexOf("                        attachButtonAnimator = attachButton.animate()");
        int animEnd = send.indexOf("                        attachButtonAnimator.start();", animStart);
        check(animStart >= 0 && animEnd > animStart, "原附件动画目标不存在");
        String animate = send.substring(animStart, animEnd) + "                        attachButtonAnimator.start();";
        String mutation = args.length > 1 ? args[1] : "";
        switch (mutation) {
            case "": break;
            case "measure": measure = change(measure, "        applyCodexComposeLayout(true);\n", ""); break;
            case "typed": empty = change(empty, "codexComposeGroupingActive || ", ""); break;
            case "restore": grouping = change(grouping, "grouped ? Gravity.BOTTOM | Gravity.LEFT : codexAttachGravity", "Gravity.BOTTOM | Gravity.LEFT"); break;
            case "rtl": grouping = change(grouping, "grouped ? Gravity.BOTTOM | Gravity.LEFT : codexAttachGravity", "grouped ? Gravity.BOTTOM | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) : codexAttachGravity"); break;
            case "blueprint": groupContainer = change(groupContainer, "sharedSend ? sendRight : fieldRight", "fieldRight"); break;
            case "lime": groupContainer = change(groupContainer, "sharedClip ? clipLeft : fieldLeft", "fieldLeft"); break;
            case "click": clipClick = change(clipClick, "delegate.didPressAttachButton();", "delegate.didPressEmojiButton();"); break;
            case "animation": grouping = change(grouping, "            if (runningAnimation2 != null) {\n                runningAnimation2.cancel();\n                runningAnimation2 = null;\n            }\n", ""); break;
            case "property-animation": grouping = change(grouping, "            if (attachButtonAnimator != null) {\n                attachButtonAnimator.cancel();\n                attachButtonAnimator = null;\n            }\n", ""); break;
            case "price": grouping = change(grouping, "getStarsPrice() == 0 && ", ""); break;
            case "draw-cache": groupContainer = change(groupContainer, "final float halfStroke =", "Theme.getCodexPixelStyle(); final float halfStroke ="); break;
            default: throw new AssertionError("未知负例 " + mutation);
        }
        String owner = grouping.replace("org.telegram.ui.Components.chat.ChatInputViewsContainer", "ChatInputViewsContainer") + right + translation + measure;
        String palette = Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexPixelPalette.java"));
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        var matches = Pattern.compile("Theme\\.(key_[A-Za-z0-9_]+)").matcher(palette + container);
        while (matches.find()) keys.add(matches.group(1));
        StringBuilder theme = new StringBuilder("package org.telegram.ui.ActionBar;import com.butang.codextop.CodexPixelPalette;public class Theme {public interface ResourcesProvider{}public static int reads,styleReads,styleId=1;");
        int key = 0; for (String name : keys) theme.append("public static final int ").append(name).append('=').append(++key).append(';');
        theme.append("public static int getColor(int key,ResourcesProvider p){reads++;return 0xff202830;}public static CodexPixelPalette.Style getCodexPixelStyle(){styleReads++;return CodexPixelPalette.metadata(styleId);}}");
        String bindGate = statement(chat, "        codexPlainHeader = com.butang.codextop.CodexRuntime.enabled()");
        String bindHook = "        chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader, chatInputViewsContainer);";
        check(chat.contains(bindHook), "普通实例未绑定");
        String previewHook = "            chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader && !value, chatInputViewsContainer);";
        check(method(chat, "    public void setInPreviewMode(boolean value)").contains(previewHook), "动态页面预览未恢复");
        String source = FIXTURE + "static class ChatInputViewsContainer extends Base {" + CONTAINER_BOUNDARY + containerFields + groupContainer
            + method(container, "    public void setCodexComposeAppearance(boolean enabled, Theme.ResourcesProvider provider)")
            + refresh + method(container, "    public void updateColors()") + method(container, "    public void setInputBubbleAlpha(int alpha)")
            + method(container.substring(container.indexOf("    /* Render */")), "    protected void dispatchDraw(@NonNull Canvas canvas)") + "}\n"
            + "static class Owner extends Base {" + OWNER_BOUNDARY + owner
            + method(enter, "    private void updateRichDraftPreview()") + method(enter, "    public void setLiveComment(boolean isLiveComment, boolean isAdmin)")
            + "void setNativeClick(){" + clipClick + "}\n"
            + "class Clip extends View {" + clipTouch + "}\n"
            + "void checkSendButton(boolean animated){checkCalls++;" + sendEarly + "CharSequence message=messageEditText.text;String caption=messageEditText.getCaption();"
            + empty + statement(send, "                        updateFieldRight(codexEmptyInput ? 1 : 0)") + "boolean captionNearAttach=LocaleController.isRTL;" + attachTarget + animate + "}\n"
            + "void startNativeAttachmentAnimation(){CharSequence message=messageEditText.text;String caption=messageEditText.getCaption();" + empty + "boolean captionNearAttach=LocaleController.isRTL;" + attachmentSet + "}\n"
            + "void nativeEditingVisibility(){" + editingClip + "}\n"
            + "class InputHost extends Base {" + nativeHostDraw + "}\n}"
            + "static class Chat {" + CHAT_BOUNDARY + "void bind(){" + bindGate + bindHook + "}void preview(boolean value){" + previewHook + "}}\n"
            + SCENARIOS + "}\n";
        Path temp = Files.createTempDirectory("codex-compose-group-methods-");
        try {
            Path probe = temp.resolve("ComposeGroupingProbe.java"); Files.writeString(probe, source);
            Path themeFile = temp.resolve("org/telegram/ui/ActionBar/Theme.java"); Files.createDirectories(themeFile.getParent()); Files.writeString(themeFile, theme);
            Path sparse = temp.resolve("android/util/SparseIntArray.java"); Files.createDirectories(sparse.getParent()); Files.writeString(sparse,"package android.util;public class SparseIntArray{public SparseIntArray(int n){}public void put(int k,int v){}}");
            Path runtime = temp.resolve("com/butang/codextop/CodexRuntime.java"); Files.createDirectories(runtime.getParent()); Files.writeString(runtime,"package com.butang.codextop;public class CodexRuntime{public static boolean on=true,owns=true;public static boolean enabled(){return on;}public static boolean ownsConversation(long id){return owns&&id==42;}}");
            Path paletteFile = temp.resolve("com/butang/codextop/CodexPixelPalette.java"); Files.writeString(paletteFile,palette);
            check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-d",temp.toString(),probe.toString(),themeFile.toString(),sparse.toString(),runtime.toString(),paletteFile.toString())==0,"真实原方法 Java8 编译失败: " + temp);
            try(URLClassLoader loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                try {loader.loadClass("com.butang.codextop.ComposeGroupingProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("真实原方法行为失败",e.getCause());}
            }
        } finally {try(var paths=Files.walk(temp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}}
    }

    private static void protectOriginal(String enter,String container,String chat) throws Exception {
        String protectedEnter = enter.replace(section(enter,"    private boolean codexComposeGroupingEnabled;","    /** 只给普通 Codex 会话的原发送按钮启用外观"),"");
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
        check(ENTER_SHA.equals(sha(protectedEnter)),"三文件前片保护失败 EnterView: " + sha(protectedEnter));
        String protectedContainer = container.replace(section(container,"    private boolean codexComposeGroupingActive;","    public void setInputIslandBubbleDrawable"),"");
        int colorStart=protectedContainer.indexOf("        final com.butang.codextop.CodexPixelPalette.Style style = Theme.getCodexPixelStyle();");
        int colorEnd=protectedContainer.indexOf("        codexGroupStrokePaint.setAlpha(codexComposeAlpha);",colorStart)+"        codexGroupStrokePaint.setAlpha(codexComposeAlpha);\n".length();
        protectedContainer=protectedContainer.substring(0,colorStart)+protectedContainer.substring(colorEnd);
        protectedContainer=protectedContainer.replace(" && !codexComposeGroupingActive","")
            .replace("            codexGroupFillPaint.setAlpha(alpha);\n            codexGroupOuterPaint.setAlpha(alpha);\n            codexGroupStrokePaint.setAlpha(alpha);\n","");
        check(CONTAINER_SHA.equals(sha(protectedContainer)),"三文件前片保护失败 Container");
        String protectedChat=chat.replace("        chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader, chatInputViewsContainer);\n","")
            .replace("            chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader && !value, chatInputViewsContainer);\n","")
            .replace("            actionBar.setCodexPlainChatHeaderDividerVisible(codexPlainHeader && !value);\n", "")
            .replace("                actionBar.updateColors();\n", "");
        check(CHAT_SHA.equals(sha(protectedChat)),"三文件前片保护失败 ChatActivity");
    }
    private static String section(String s,String begin,String end){int a=s.indexOf(begin),b=s.indexOf(end,a);check(a>=0&&b>a,"缺少片段 "+begin);return s.substring(a,b);}
    private static String method(String s,String signature){int start=s.indexOf(signature);check(start>=0,"缺少原方法 "+signature);int open=s.indexOf('{',start),depth=0;for(int i=open;i<s.length();i++){char c=s.charAt(i);if(c=='{')depth++;if(c=='}'&&--depth==0)return s.substring(start,i+1);}throw new AssertionError("原方法边界 "+signature);}
    private static String statement(String s,String begin){int a=s.indexOf(begin);check(a>=0,"缺少原语句 "+begin);return s.substring(a,s.indexOf(';',a)+1);}
    private static String change(String s,String old,String replacement){check(s.contains(old),"负例未命中 "+old);return s.replace(old,replacement);}
    private static String sha(String s)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;import org.telegram.ui.ActionBar.Theme;import com.butang.codextop.CodexPixelPalette;
        public class ComposeGroupingProbe {
        @interface NonNull {}
        static float density=1;static final int VISIBLE=0,INVISIBLE=4,GONE=8,DEFAULT_HEIGHT=44;static int checks;
        static int dp(float v){return (int)Math.ceil(v*density);}static class LocaleController{static boolean isRTL;}
        static class TextUtils{static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}
        static class AndroidUtilities{static CharSequence getTrimmedString(CharSequence s){return s.toString().trim();}static void removeFromParent(View v){}}
        static class Gravity{static final int LEFT=3,RIGHT=5,BOTTOM=80;}
        static class FrameLayout{static class LayoutParams{int gravity=Gravity.BOTTOM|Gravity.RIGHT,leftMargin,rightMargin,width=44,height=44;}}
        static class MarginLayoutParams extends FrameLayout.LayoutParams{}
        static class MotionEvent{}
        interface Click{void onClick(View v);}
        static class View {
          static final int VISIBLE=0,GONE=8;static final String ALPHA="alpha",SCALE_X="scaleX",SCALE_Y="scaleY";protected void dispatchDraw(Canvas c){}
          MarginLayoutParams lp=new MarginLayoutParams();int visibility=VISIBLE,layoutRequests,invalidates;float alpha=1,sx=1,sy=1,tx,ty;int left,top,width=44,height=44;Object tag;Click click;
          MarginLayoutParams getLayoutParams(){return lp;}void setLayoutParams(Object p){lp=(MarginLayoutParams)p;layoutRequests++;}void requestLayout(){layoutRequests++;}void invalidate(){invalidates++;}
          void setVisibility(int v){visibility=v;}int getVisibility(){return visibility;}void setAlpha(float v){alpha=v;}float getAlpha(){return alpha;}void setScaleX(float v){sx=v;}void setScaleY(float v){sy=v;}float getScaleX(){return sx;}float getScaleY(){return sy;}
          void setTranslationX(float v){tx=v;}float getTranslationX(){return tx;}void setTranslationY(float v){ty=v;}float getTranslationY(){return ty;}float getX(){return left+tx;}float getY(){return top+ty;}
          int getLeft(){return left;}int getTop(){return top;}int getBottom(){return top+height;}int getWidth(){return width;}int getHeight(){return height;}int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}
          Object getTag(){return tag;}void setTag(Object t){tag=t;}void setMeasuredButtonWidth(int v){}void setResourcesProvider(Theme.ResourcesProvider r){}void set(Object value){}void measure(int w,int h){width=w;}void setOnClickListener(Click c){click=c;}boolean dispatchTouchEvent(MotionEvent e){if(click!=null)click.onClick(this);return true;}Animator animate(){return new Animator(this);}
        }
        static class Base extends View{protected void dispatchDraw(Canvas c){}protected void onMeasure(int w,int h){}void measureChild(View v,int w,int h){v.measure(w,h);}}
        // Deferred native animation boundary: cancel fires cancel then end; stopped targets cannot finish.
        static class Animator{View v;boolean cancelled;Float alpha,sx,sy;List<AnimatorListenerAdapter> listeners=new ArrayList<>();Animator(View v){this.v=v;}void cancel(){cancelled=true;for(AnimatorListenerAdapter l:listeners)l.onAnimationCancel(this);for(AnimatorListenerAdapter l:listeners)l.onAnimationEnd(this);}Animator alpha(float x){alpha=x;return this;}Animator scaleX(float x){sx=x;return this;}Animator scaleY(float x){sy=x;return this;}Animator translationY(float x){if(v!=null)v.setTranslationY(x);return this;}Animator setInterpolator(Object x){return this;}Animator setDuration(int d){return this;}void start(){}void finish(){if(cancelled)return;if(v!=null){if(alpha!=null)v.setAlpha(alpha);if(sx!=null)v.setScaleX(sx);if(sy!=null)v.setScaleY(sy);}for(AnimatorListenerAdapter l:listeners)l.onAnimationEnd(this);}}
        static class AnimatorListenerAdapter{void onAnimationEnd(Animator a){}void onAnimationCancel(Animator a){}}
        static class AnimatorSet extends Animator{List<Animator> children=new ArrayList<>();AnimatorSet(){super(null);}void playTogether(List<Animator> a){children.addAll(a);}void addListener(AnimatorListenerAdapter l){listeners.add(l);}@Override void cancel(){for(Animator a:children)a.cancel();super.cancel();}@Override void finish(){if(cancelled)return;for(Animator a:children)a.finish();super.finish();}}
        static class ObjectAnimator{static Animator ofFloat(View v,String property,float target){Animator a=new Animator(v);if(property.equals(View.ALPHA)||property.equals("attachAlpha"))a.alpha(target);else if(property.equals(View.SCALE_X))a.scaleX(target);else if(property.equals(View.SCALE_Y))a.scaleY(target);return a;}}
        static class CubicBezierInterpolator{static Object EASE_OUT_QUINT=new Object();}static class MeasureSpec{static int EXACTLY=1;static int makeMeasureSpec(int w,int type){return w;}}
        static class Paint{static int allocations;static final int ANTI_ALIAS_FLAG=1;enum Style{FILL,STROKE}int color,alpha=255;float stroke;Style style=Style.FILL;Paint(int f){allocations++;}void setColor(int c){color=c;}void setAlpha(int a){alpha=a;}void setStyle(Style s){style=s;}void setStrokeWidth(float w){stroke=w;}float getStrokeWidth(){return stroke;}}
        static class Rect{float l,t,r,b;void set(float l,float t,float r,float b){this.l=l;this.t=t;this.r=r;this.b=b;}void inset(float x,float y){l+=x;r-=x;t+=y;b-=y;}void offset(float x,float y){l+=x;r+=x;t+=y;b+=y;}}
        static class RectF extends Rect{void set(Rect r){set(r.l,r.t,r.r,r.b);}}
        static class Draw{float l,t,r,b,radius;Paint.Style style;int color,alpha;boolean line;Draw(Rect r,float radius,Paint p){l=r.l;t=r.t;this.r=r.r;b=r.b;this.radius=radius;style=p.style;color=p.color;alpha=p.alpha;}}
        static class Canvas{List<Draw> draws=new ArrayList<>();void drawRoundRect(RectF r,float x,float y,Paint p){draws.add(new Draw(r,x,p));}void drawLine(float a,float b,float c,float d,Paint p){Rect r=new Rect();r.set(a,b,c,d);Draw draw=new Draw(r,0,p);draw.line=true;draws.add(draw);}}
        static class Drawable{int draws,alpha=255;void setBounds(int l,int t,int r,int b){}void setBounds(Rect r){}void draw(Canvas c){draws++;}void setAlpha(int a){alpha=a;}void updateColors(){}}
        static class A{float v;boolean b;float get(){return v;}float getFloatValue(){return v;}boolean getValue(){return b;}}
        static class Emoji{Object current;float residual;Object getDrawable(){return current;}float isNotEmpty(){return residual;}}
        static class Count{CharSequence text="";float residual;CharSequence getText(){return text;}float isNotEmpty(){return residual;}}
        static class Send extends View{long starsPrice;boolean opened,locked,loadingShown;float ephemeralFactor,sameWidthFactor;A animatedPriceVisible=new A(),open=new A(),loadingAnimatedShown=new A();Emoji emojiDrawable=new Emoji();Count count=new Count();boolean isOpen(){return opened;}int width(){return width;}void setLocked(boolean b){locked=b;}}
        static class UserConfig{static UserConfig getInstance(int a){return new UserConfig();}boolean isPremium(){return true;}}
        static class MessageObject{boolean needResendWhenEdit(){return false;}}
        static class Text extends View{String text="",caption;String getCaption(){return caption;}}
        static class Delegate{boolean hasScheduledMessages(){return false;}void onAttachButtonHidden(){}int attach,emoji;void didPressAttachButton(){attach++;}void didPressEmojiButton(){emoji++;}}
        static class Pan{boolean progress;boolean animationInProgress(){return progress;}}
        static class ChatActivitySideControlsButtonsLayout{static final int BUTTON_ATTACH=1;void showButton(int button,boolean hidden,boolean animated){}}
        """;
    private static final String CONTAINER_BOUNDARY = """
        static final int INPUT_KEYBOARD_RADIUS=29;
        boolean drawInputBackground=true,needDrawInAppKeyboard;float imeBottomInset,inputBubbleOffsetLeft,inputBubbleOffsetRight,bubbleInputTranlationY;int currentBlurredHeight,inputBubbleHeightRound=44;
        Drawable blurredBackgroundDrawable=new Drawable(),underKeyboardBackgroundDrawable=new Drawable();Rect tmpRect=new Rect();
        """;
    private static final String OWNER_BOUNDARY = """
        Clip attachButton=new Clip();Text messageEditText=new Text();View emojiButton=new View(),messageEditTextContainer=new View(),textFieldContainer=new InputHost(),sendButtonContainer=new View();Send sendButton=new Send(),doneButton;
        View recordedAudioPanel,botCommandsMenuButton,senderSelectView,botButton,notifyButton,scheduledButton,deleteRichDraftButton,richDraftPreview,botWebViewButton,aiHint;
        View aiButton=new View(),richButton=new View(),audioVideoSendButton=new View(),captionLimitView;int currentLimit,currentAccount;Object richDraftMessage;Theme.ResourcesProvider resourcesProvider;MessageObject editingMessageObject;Object audioToSend,videoToSendMessageObject,messageSendPreview;ChatActivitySideControlsButtonsLayout sideButtons;
        boolean recordingAudioVideo,isStories,isLiveComment,forceShowSendButton,richDraftActive,emojiViewVisible,stickersExpanded,suggestButtonVisible,schedule;
        int recordInterfaceState,slowModeTimer,lastAttachVisible,checkCalls;long starsPrice;float attachButtonAlpha=1,attachLayoutPaddingAlpha=1,attachLayoutPaddingTranslationX,attachLayoutTranslationX;
        A animatorIsBlockedByStreaming=new A(),animatorEphemeralMessageVisibility=new A();AnimatorSet runningAnimation2;View attachLayout=new View();boolean scheduleButtonHidden;static final String ATTACH_LAYOUT_ALPHA="attachAlpha";Animator attachButtonAnimator;Delegate delegate=new Delegate();Pan adjustPanLayoutHelper=new Pan();
        Animator animateScheduledTranslationX(float x){return new Animator(scheduledButton);}boolean isInScheduleMode(){return schedule;}long getStarsPrice(){return starsPrice;}boolean isSlowModeIgnored(){return schedule;}
        void reset(){}void updateButtons(){}void updateBotCommandsMenuContainerTopPadding(){}void checkUi_IslandTotalHeight(){}void checkUi_TopViewVisibility(){}
        Owner(){richDraftPreview=new View();richDraftPreview.visibility=GONE;deleteRichDraftButton=new View();deleteRichDraftButton.visibility=GONE;messageEditTextContainer.width=dp(320-44);messageEditTextContainer.height=dp(44);messageEditText.lp.leftMargin=dp(52);messageEditText.lp.rightMargin=dp(50);attachButton.lp.width=attachButton.lp.height=dp(44);attachButton.width=attachButton.height=dp(44);sendButton.width=sendButton.height=dp(44);sendButtonContainer.left=dp(320-100);sendButton.left=dp(100-44);emojiButton.setTranslationX(0);setNativeClick();}
        void layout(){int w=messageEditTextContainer.width,h=messageEditTextContainer.height;attachButton.left=(attachButton.lp.gravity&7)==Gravity.LEFT?attachButton.lp.leftMargin:w-attachButton.width-attachButton.lp.rightMargin;attachButton.top=h-attachButton.height;messageEditText.left=messageEditText.lp.leftMargin;messageEditText.width=w-messageEditText.lp.leftMargin-messageEditText.lp.rightMargin;messageEditText.height=h;sendButton.top=h-sendButton.height;}
        """;
    private static final String CHAT_BOUNDARY = """
        boolean codexPlainHeader,inPreviewMode,inBubbleMode,isInsideContainer,isComments,report;int chatMode,startLoadFromDate;long dialog_id=42;Owner chatActivityEnterView=new Owner();ChatInputViewsContainer chatInputViewsContainer=new ChatInputViewsContainer();boolean isReport(){return report;}
        """;
    private static final String SCENARIOS = """
        static void ck(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}static void eq(float a,float b,String s){ck(Math.abs(a-b)<0.02f,s+" actual="+a+" expected="+b);}
        static Owner normal(int style){Theme.styleId=style;ChatInputViewsContainer container=new ChatInputViewsContainer();container.setCodexComposeAppearance(true,null);Owner owner=new Owner();owner.setCodexComposeGrouping(true,container);owner.onMeasure(dp(320),dp(44));owner.layout();return owner;}
        static List<Draw> strokes(Canvas c){List<Draw> out=new ArrayList<>();for(Draw d:c.draws)if(d.style==Paint.Style.STROKE&&!d.line)out.add(d);return out;}
        static void geometry(int style){Owner o=normal(style);Canvas c=new Canvas();int reads=Theme.reads,styles=Theme.styleReads,allocations=Paint.allocations;o.textFieldContainer.dispatchDraw(c);ck(Paint.allocations==allocations,"draw allocates paints");ck(Theme.reads==reads&&Theme.styleReads==styles,"draw reads theme "+style);List<Draw> s=strokes(c);ck(s.size()==(style==1||style==3||style==6?2:1),"five group count "+style);float half=dp(1)/2f;Draw input=s.get(s.size()-1);float clipLeft=o.attachButton.getX()+dp(3),clipRight=clipLeft+dp(44)-dp(6),fieldLeft=o.messageEditText.getX()-dp(6),fieldRight=fieldLeft+o.messageEditText.getWidth()+dp(12),sendRight=o.sendButtonContainer.getX()+o.sendButton.getX()+o.sendButton.getWidth()-dp(3);
          eq(input.l,(style==4?clipLeft:fieldLeft)+half,"input left "+style);eq(input.r,(style==6?sendRight:fieldRight)-half,"input right "+style);ck(style==6||input.r<o.sendButtonContainer.getX()+o.sendButton.getX()+dp(3),"independent send gap "+style);
          if(style==1||style==6){Draw clip=s.get(0);eq(clip.l,clipLeft+half,"independent clip "+style);eq(clip.r,clipRight-half,"clip width "+style);ck(clip.r<input.l,"clip/input gap "+style);}
          if(style==3){Draw outer=s.get(0);eq(outer.l,clipLeft-dp(3)+half,"industrial outer left");eq(outer.r,sendRight+dp(3)-half,"industrial wraps send");ck(outer.t<input.t&&outer.b>input.b,"industrial vertical inset");}
          int lines=0;for(Draw d:c.draws)if(d.line){lines++;eq(d.l,clipRight+dp(3),"lime divider");}ck(lines==(style==4?1:0),"divider only lime");
          ck(o.emojiButton.visibility==GONE,"normal hides actual emoji");ck((o.attachButton.lp.gravity&7)==Gravity.LEFT,"physical left clip");eq(o.attachButton.width,dp(44),"native hit width");eq(o.messageEditText.lp.leftMargin,dp(56),"measure keeps group margin");eq(o.messageEditText.lp.rightMargin,dp(8),"inner right margin");int requests=o.messageEditText.layoutRequests+o.attachButton.layoutRequests;o.onMeasure(dp(320),dp(44));ck(o.messageEditText.layoutRequests+o.attachButton.layoutRequests==requests,"measure re-request loop");
          o.messageEditText.text="typed text";o.checkSendButton(true);o.attachButtonAnimator.finish();o.layout();ck(o.attachButton.visibility==VISIBLE&&o.attachButton.alpha==1&&o.attachButton.sx==1,"typed clip target");o.attachButton.dispatchTouchEvent(new MotionEvent());ck(o.delegate.attach==1&&o.delegate.emoji==0,"native paperclip callback typed");o.messageEditText.text="";o.checkSendButton(true);o.attachButton.dispatchTouchEvent(new MotionEvent());ck(o.delegate.attach==2,"empty native callback");
          o.adjustPanLayoutHelper.progress=true;o.attachButton.dispatchTouchEvent(new MotionEvent());ck(o.delegate.attach==2,"native pan gate");o.adjustPanLayoutHelper.progress=false;o.attachLayoutPaddingAlpha=0;o.attachButton.dispatchTouchEvent(new MotionEvent());ck(o.delegate.attach==2,"native padding gate");o.attachLayoutPaddingAlpha=1;
          o.attachLayoutPaddingTranslationX=dp(31);o.attachLayoutTranslationX=dp(9);o.emojiButton.setTranslationX(-dp(17));o.updateAttachButtonTranslationX();eq(o.attachButton.tx,-dp(17),"left follows native padding");
          o.messageEditTextContainer.height=dp(112);o.layout();Canvas multi=new Canvas();o.textFieldContainer.dispatchDraw(multi);Draw taller=strokes(multi).get(s.size()-1);eq(taller.b,dp(112)-dp(3)-half,"multiline background");
          o.codexComposeContainer.setInputBubbleAlpha(80);Canvas faded=new Canvas();o.textFieldContainer.dispatchDraw(faded);for(Draw d:faded.draws)ck(d.alpha==80,"original bubble alpha");o.codexComposeContainer.drawInputBackground=false;Canvas hidden=new Canvas();o.textFieldContainer.dispatchDraw(hidden);ck(hidden.draws.isEmpty(),"original drawInputBackground");o.codexComposeContainer.drawInputBackground=true;o.codexComposeContainer.setInputBubbleAlpha(0);hidden=new Canvas();o.textFieldContainer.dispatchDraw(hidden);ck(hidden.draws.isEmpty(),"zero bubble alpha");
        }
        interface Scenario{void set(Owner o);}static void fallback(String name,Scenario f){Owner o=normal(1);int rightGravity=Gravity.BOTTOM|Gravity.RIGHT;f.set(o);o.applyCodexComposeLayout(false);ck(!o.codexComposeGroupingActive,"special gate "+name);ck(o.attachButton.lp.gravity==rightGravity&&o.attachButton.lp.leftMargin==0&&o.attachButton.lp.rightMargin==0,"special clip restore "+name);ck(o.emojiButton.visibility==VISIBLE,"special emoji restore "+name);int nativeRight=(o.botButton!=null&&o.botButton.visibility==VISIBLE||o.notifyButton!=null&&o.notifyButton.visibility==VISIBLE||o.scheduledButton!=null&&o.scheduledButton.tag!=null)?98:50;ck(o.messageEditText.lp.rightMargin==dp(nativeRight),"special input right restore "+name);float original= o.attachLayoutPaddingTranslationX+o.attachLayoutTranslationX-Math.max(0,o.sendButton.width()-dp(DEFAULT_HEIGHT+12))*o.sendButton.alpha;eq(o.attachButton.tx,original,"special original right displacement "+name);Canvas c=new Canvas();o.textFieldContainer.dispatchDraw(c);ck(c.draws.isEmpty(),"no group special "+name);}
        static Owner groupedFromTyped(){Theme.styleId=1;ChatInputViewsContainer c=new ChatInputViewsContainer();c.setCodexComposeAppearance(true,null);Owner o=new Owner();o.messageEditText.text="synthetic typed";o.attachButtonAlpha=0;o.attachButton.setAlpha(0);o.attachButton.setScaleX(.5f);o.attachButton.setScaleY(.5f);o.setCodexComposeGrouping(true,c);o.onMeasure(dp(320),dp(44));o.layout();return o;}
        static void animatedAttachmentTargets(){
          Owner o=groupedFromTyped();o.startNativeAttachmentAnimation();AnimatorSet old=o.runningAnimation2;ck(old!=null&&!old.cancelled,"actual attachment set started");o.applyCodexComposeLayout(false);ck(!old.cancelled&&o.runningAnimation2==old,"unchanged grouping preserves valid animation");o.editingMessageObject=new MessageObject();o.checkSendButton(false);o.nativeEditingVisibility();eq(o.attachButton.alpha,0,"editing immediate original hide");eq(o.attachButton.sx,.5f,"editing immediate original scale");old.finish();eq(o.attachButton.alpha,0,"late set cannot override original editing hide");eq(o.attachButton.sx,.5f,"late set cannot override original editing scale");ck(old.cancelled&&o.runningAnimation2==null,"editing early return cancels original set");
          o=groupedFromTyped();o.startNativeAttachmentAnimation();old=o.runningAnimation2;o.recordingAudioVideo=true;o.checkSendButton(false);ck(old.cancelled&&o.runningAnimation2==null,"recording early return cancels set");old.finish();eq(o.attachButton.alpha,0,"recording keeps restored native alpha");eq(o.attachButton.sx,.5f,"recording keeps restored native scale");
          Theme.styleId=1;ChatInputViewsContainer c=new ChatInputViewsContainer();c.setCodexComposeAppearance(true,null);o=new Owner();o.messageEditText.text="synthetic typed";o.startNativeAttachmentAnimation();old=o.runningAnimation2;o.setCodexComposeGrouping(true,c);ck(old.cancelled&&o.runningAnimation2==null,"enter grouping cancels old right-slot target");ck(o.attachLayout.visibility==VISIBLE,"cancel identity guard prevents old end hiding new state");old.finish();eq(o.attachButton.alpha,1,"late native hide cannot override group alpha");eq(o.attachButton.sx,1,"late native hide cannot override group scale");
          o=groupedFromTyped();o.startNativeAttachmentAnimation();old=o.runningAnimation2;o.setCodexComposeGrouping(false,o.codexComposeContainer);ck(old.cancelled&&!o.codexComposeGroupingActive,"disable cancels grouped set");old.finish();o.attachButtonAnimator.finish();eq(o.attachButton.alpha,0,"disable native typed alpha remains");eq(o.attachButton.sx,.5f,"disable native typed scale remains");
          c=new ChatInputViewsContainer();c.setCodexComposeAppearance(true,null);o=new Owner();o.messageEditText.text="synthetic typed";o.checkSendButton(false);Animator property=o.attachButtonAnimator;o.setCodexComposeGrouping(true,c);property.finish();eq(o.attachButton.alpha,1,"late property hide cannot override group");ck(property.cancelled,"enter grouping cancels native property animator");
          o=groupedFromTyped();property=o.attachButtonAnimator;o.editingMessageObject=new MessageObject();o.checkSendButton(false);o.nativeEditingVisibility();property.finish();eq(o.attachButton.alpha,0,"late property show cannot override original edit hide");eq(o.attachButton.sx,.5f,"late property show cannot override original edit scale");ck(property.cancelled,"editing cancels grouped property animator");
        }
        public static void main(String[] args){for(float scale:new float[]{1,1.5f,2,3}){density=scale;for(boolean rtl:new boolean[]{false,true}){LocaleController.isRTL=rtl;for(int style:new int[]{1,2,3,4,6})geometry(style);}}
          density=1;LocaleController.isRTL=false;animatedAttachmentTargets();
          fallback("disabled",o->o.codexComposeGroupingEnabled=false);fallback("side",o->o.sideButtons=new ChatActivitySideControlsButtonsLayout());fallback("edit",o->o.editingMessageObject=new MessageObject());fallback("record",o->o.recordingAudioVideo=true);fallback("record UI",o->o.recordInterfaceState=1);fallback("audio",o->o.audioToSend=new Object());fallback("video",o->o.videoToSendMessageObject=new Object());fallback("stories",o->o.isStories=true);fallback("live",o->o.isLiveComment=true);fallback("schedule",o->o.schedule=true);fallback("slow",o->o.slowModeTimer=5);fallback("slow upload",o->o.slowModeTimer=Integer.MAX_VALUE);fallback("force",o->o.forceShowSendButton=true);fallback("rich",o->o.richDraftActive=true);fallback("caption",o->o.messageEditText.caption="bot");fallback("emoji",o->o.emojiViewVisible=true);fallback("stickers",o->o.stickersExpanded=true);fallback("audio panel",o->o.recordedAudioPanel=new View());fallback("bot",o->{o.botCommandsMenuButton=new View();o.botCommandsMenuButton.tag=1;});fallback("send as",o->o.senderSelectView=new View());fallback("bot helper",o->o.botButton=new View());fallback("notify helper",o->o.notifyButton=new View());fallback("schedule helper",o->{o.scheduledButton=new View();o.scheduledButton.visibility=GONE;o.scheduledButton.tag=1;});fallback("schedule fade",o->o.scheduledButton=new View());fallback("stream",o->o.animatorIsBlockedByStreaming.b=true);fallback("stream exit",o->o.animatorIsBlockedByStreaming.v=.5f);fallback("ephemeral",o->o.animatorEphemeralMessageVisibility.b=true);fallback("ephemeral exit",o->o.animatorEphemeralMessageVisibility.v=.5f);fallback("price",o->o.starsPrice=4);fallback("button price",o->o.sendButton.starsPrice=4);fallback("price exit",o->o.sendButton.animatedPriceVisible.v=.5f);fallback("preview",o->o.sendButton.opened=true);fallback("preview exit",o->o.sendButton.open.v=.5f);fallback("preview object",o->o.messageSendPreview=new Object());fallback("locked",o->o.sendButton.locked=true);fallback("loading",o->o.sendButton.loadingShown=true);fallback("loading exit",o->o.sendButton.loadingAnimatedShown.v=.5f);fallback("same width",o->o.sendButton.sameWidthFactor=.5f);fallback("ephemeral button",o->o.sendButton.ephemeralFactor=.5f);fallback("effect enter",o->o.sendButton.emojiDrawable.current=new Object());fallback("effect exit",o->o.sendButton.emojiDrawable.residual=.5f);fallback("count enter",o->o.sendButton.count.text="2");fallback("count exit",o->o.sendButton.count.residual=.5f);
          Owner o=normal(1);o.senderSelectView=new View();o.senderSelectView.lp.width=dp(26);o.onMeasure(dp(320),dp(44));eq(o.messageEditText.lp.leftMargin,dp(54)+dp(26),"native special measure rewrite preserved");ck(!o.codexComposeGroupingActive,"measure restores special");o.senderSelectView.visibility=GONE;o.onMeasure(dp(320),dp(44));ck(o.codexComposeGroupingActive&&o.emojiButton.visibility==GONE,"special exit resumes normal");
          o=normal(1);o.richDraftMessage=new Object();o.updateRichDraftPreview();ck(!o.codexComposeGroupingActive&&o.emojiButton.visibility==GONE&&o.messageEditText.visibility==GONE,"actual rich draft enter");o.richDraftMessage=null;o.updateRichDraftPreview();ck(o.codexComposeGroupingActive&&o.emojiButton.visibility==GONE&&o.messageEditText.visibility==VISIBLE,"actual rich draft exit");o.setCodexComposeGrouping(false,o.codexComposeContainer);ck(o.emojiButton.visibility==VISIBLE&&o.attachButton.visibility==VISIBLE,"rich exit later restores native visibility");
          o=normal(1);o.setLiveComment(true,false);ck(!o.codexComposeGroupingActive&&o.attachButton.visibility==GONE&&o.audioVideoSendButton.visibility==GONE,"actual live enter keeps original visibility");o.setLiveComment(false,false);ck(o.codexComposeGroupingActive&&o.attachButton.visibility==VISIBLE,"actual live exit resumes group");o.setCodexComposeGrouping(false,o.codexComposeContainer);ck(o.attachButton.visibility==VISIBLE,"live exit later restores native clip");
          o=normal(1);o.editingMessageObject=new MessageObject();o.checkSendButton(false);ck(!o.codexComposeGroupingActive&&o.emojiButton.visibility==VISIBLE&&(o.attachButton.lp.gravity&7)==Gravity.RIGHT,"actual editing early return restores");o=normal(1);o.recordingAudioVideo=true;o.checkSendButton(false);ck(!o.codexComposeGroupingActive&&(o.attachButton.lp.gravity&7)==Gravity.RIGHT,"actual recording early return restores");
          o=normal(1);o.emojiButton.visibility=VISIBLE;o.applyCodexComposeLayout(false);ck(o.emojiButton.visibility==GONE,"rich draft exit re-hides emoji even active");
          o=normal(1);o.codexComposeContainer.setInputBubbleAlpha(91);Theme.styleId=6;o.codexComposeContainer.updateColors();Canvas c=new Canvas();o.textFieldContainer.dispatchDraw(c);Draw in=strokes(c).get(1);eq(in.r,o.sendButtonContainer.getX()+o.sendButton.getX()+o.sendButton.getWidth()-dp(3)-dp(1)/2f,"theme switch geometry");for(Draw d:c.draws)ck(d.alpha==91,"theme refresh keeps alpha");
          ChatInputViewsContainer container=o.codexComposeContainer;container.width=320;container.height=70;container.currentBlurredHeight=55;container.inputBubbleHeightRound=44;c=new Canvas();container.dispatchDraw(c);ck(c.draws.isEmpty(),"group suppresses original all-frame");container.setCodexComposeGroupingActive(false);container.dispatchDraw(c);ck(c.draws.size()==2,"special restores original surface");container.setCodexComposeAppearance(false,null);int before=container.blurredBackgroundDrawable.draws;container.dispatchDraw(new Canvas());ck(container.blurredBackgroundDrawable.draws==before+1,"nonCodex native blur restored");
          CodexRuntime.on=true;CodexRuntime.owns=true;Chat chat=new Chat();chat.chatInputViewsContainer.setCodexComposeAppearance(true,null);chat.bind();ck(chat.chatActivityEnterView.codexComposeGroupingActive,"strict ordinary bind");chat.preview(true);ck(!chat.chatActivityEnterView.codexComposeGroupingActive,"page preview off");chat.preview(false);ck(chat.chatActivityEnterView.codexComposeGroupingActive,"page preview restore");
          for(int special=0;special<7;special++){chat=new Chat();switch(special){case 0:chat.inPreviewMode=true;break;case 1:chat.inBubbleMode=true;break;case 2:chat.isComments=true;break;case 3:chat.chatMode=1;break;case 4:chat.isInsideContainer=true;break;case 5:chat.startLoadFromDate=1;break;case 6:chat.report=true;break;}chat.bind();ck(!chat.chatActivityEnterView.codexComposeGroupingActive,"strict scope "+special);}
          CodexRuntime.on=false;chat=new Chat();chat.bind();ck(!chat.chatActivityEnterView.codexComposeGroupingActive,"Telegram scope");ck(chat.chatActivityEnterView.checkCalls==0,"disabled binding does not re-run native send update");CodexRuntime.on=true;CodexRuntime.owns=false;chat=new Chat();chat.bind();ck(!chat.chatActivityEnterView.codexComposeGroupingActive,"other conversation scope");
          System.out.println("ComposeGroupingTest: "+checks+" assertions passed; production methods compiled Java8; synthetic Android boundaries.");
        }
        """;
}
