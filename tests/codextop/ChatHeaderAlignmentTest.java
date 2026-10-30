package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import javax.tools.ToolProvider;

/**
 * 执行生产标题对齐、测量、布局与 SimpleTextView 原布局/偏移方法的 Java 8 专项。
 * View、StaticLayout 和字体度量是确定性的合成替身，不代表 Android 字体、Bidi 或实际截图验收。
 * javac -d "$TEST_CLASSES" tests/codextop/ChatHeaderAlignmentTest.java
 * java -Xmx64m -cp "$TEST_CLASSES" com.butang.codextop.ChatHeaderAlignmentTest
 * 可传 ChatActivity、ChatAvatarContainer、SimpleTextView 源路径执行编译后行为负例；自定义输入跳过原字节守卫。
 */
public final class ChatHeaderAlignmentTest {
    private static final String CHAT_PROTECTED_SHA = "5f87c63b86b91623d13d10345219856e847e9c3e9a9749d30c28b583789dba09";
    private static final String CONTAINER_PROTECTED_SHA = "857b7055dea0a0f58383de35c24e7de3367927dabb188fa9a1eb9a42f1b5d0b7";
    private static final String SIMPLE_SHA = "9654e4200e38505394a90811d4be5854e8cdf568589a29dad634fab506cd8d95";

    /** 从限定源码提取原方法，独立编译执行并清理全部合成产物。 */
    public static void main(String[] args) throws Exception {
        String prefix = "TMessagesProj/src/main/java/org/telegram/ui/";
        String chat = Files.readString(Path.of(args.length > 0 ? args[0] : prefix + "ChatActivity.java"));
        String container = Files.readString(Path.of(args.length > 1 ? args[1] : prefix + "Components/ChatAvatarContainer.java"));
        String simple = Files.readString(Path.of(args.length > 2 ? args[2] : prefix + "ActionBar/SimpleTextView.java"));
        if (args.length == 0) {
            check(CHAT_PROTECTED_SHA.equals(sha(protectedChat(chat))), "原 ChatActivity 状态/来源/多选/预览或其余字节改变");
            check(CONTAINER_PROTECTED_SHA.equals(sha(protectedContainer(container))), "原容器状态/来源/头像/350ms普通副本或其余字节改变");
            check(SIMPLE_SHA.equals(sha(simple)), "共享 SimpleTextView 字节改变");
        }
        StringBuilder source = new StringBuilder(FIXTURE);
        for (String signature : new String[]{"    protected boolean createLayout(int width)", "    private void calcOffset(int width)",
                "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)", "    public int getTextWidth()",
                "    public int getSideDrawablesSize()", "    public int getTextStartX()", "    public void setGravity(int value)",
                "    private Layout.Alignment getAlignment()"}) {
            source.append(methodSlice(simple, signature)).append('\n');
        }
        source.append("}\nstatic class ChatAvatarContainer extends FrameLayout {\n").append(CONTAINER_FIXTURE);
        for (String signature : new String[]{"    public void setCodexHeaderAlignment(boolean enabled, int styleId)",
                "    private void updateCodexTitleArea(int width)", "    private void measureCodexCenteredText(SimpleTextView view, int height)",
                "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)",
                "    protected void onLayout(boolean changed, int left, int top, int right, int bottom)"}) {
            source.append(methodSlice(container, signature)).append('\n');
        }
        String guard = statement(chat, "        codexPlainHeader = com.butang.codextop.CodexRuntime.enabled()");
        String init = statement(chat, "        avatarContainer.setCodexHeaderAlignment(codexPlainHeader,");
        String preview = statement(chat, "            avatarContainer.setCodexHeaderAlignment(codexPlainHeader");
        String theme = block(chat, "            if (codexPlainHeader && avatarContainer != null) {");
        source.append("}\nstatic class Chat {\n").append(CHAT_FIXTURE)
                .append("void saveGate(){").append(guard).append("}\n")
                .append("void init(){").append(init).append("}\n")
                .append("void preview(boolean value){inPreviewMode=value;if(avatarContainer!=null){").append(preview).append("}}\n")
                .append("void theme(){").append(theme).append("}\n}\n").append(SCENARIOS).append("}\n")
                .append("class CodexRuntime { static boolean enabled(){return AlignmentProbe.CodexRuntime.enabled();} static boolean ownsConversation(long id){return AlignmentProbe.CodexRuntime.ownsConversation(id);} }\n");
        Path temp = Files.createTempDirectory("codex-chat-alignment-methods-");
        try {
            Path java = temp.resolve("AlignmentProbe.java");
            Files.writeString(java, source);
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), java.toString()) == 0,
                    "生产标题/原文本方法 Java 8 编译失败");
            try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("com.butang.codextop.AlignmentProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("生产标题方法行为失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 剥离本片新增接线，冻结其余大型聊天页字节，不构建整份语法树。 */
    private static String protectedChat(String source) {
        source = source.replace("            actionBar.setCodexPlainChatHeaderDividerVisible(codexPlainHeader && !value);\n", "")
                .replace("                actionBar.updateColors();\n", "")
                .replace("        chatActivityEnterView.setCodexSendButtonAppearance(codexPlainHeader);\n", "")
                .replace("                        cell.refreshCodexDeliveryStatus();\n", "")
                .replace("        chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader, chatInputViewsContainer);\n", "")
                .replace("            chatActivityEnterView.setCodexComposeGrouping(codexPlainHeader && !value, chatInputViewsContainer);\n", "")
                .replace("        avatarContainer.setCodexHeaderAlignment(codexPlainHeader, Theme.getCodexPixelStyle().id);\n", "")
                .replace("            avatarContainer.setCodexHeaderAlignment(codexPlainHeader && !inPreviewMode, Theme.getCodexPixelStyle().id);\n", "")
                .replace("    /** 切换原预览页面；普通 Codex 标题同时退出或恢复图示对齐，不改变预览几何。 */\n", "");
        int start = source.indexOf("            // 原主题回调只更新缓存对齐；");
        if (start >= 0) {
            int end = source.indexOf("            if (sideControlsButtonsLayout != null)", start);
            source = source.substring(0, start) + source.substring(end);
        }
        return source;
    }

    /** 只豁免本片的缓存字段与测量/布局方法，冻结真实状态和普通用途副本。 */
    private static String protectedContainer(String source) {
        for (String field : new String[]{"    private boolean codexPlainTitleLayout;\n", "    private boolean codexCenteredTitle;\n",
                "    private int codexTitleAreaLeft;\n", "    private int codexTitleAreaWidth;\n"}) source = source.replace(field, "");
        int start = source.indexOf("    /** 只缓存普通 Codex 顶栏的图示对齐，");
        if (start >= 0) {
            int end = source.indexOf("    /** 保留原高度和共享测量，", start);
            source = source.substring(0, start) + source.substring(end);
        }
        source = source.replace("    /** 保留原高度和共享测量，仅普通 Codex 居中短行收紧宽度并绕过旧缩宽副本。 */\n", "")
                .replace("    /** 沿原纵向位置排版；图示居中移动整个文字控件，避免长行的内部居中裁掉头部。 */\n", "");
        for (String signature : new String[]{"    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)",
                "    protected void onLayout(boolean changed, int left, int top, int right, int bottom)"}) {
            source = source.replace(methodSlice(source, signature), "");
        }
        return source;
    }

    /** 提取已知短方法的原源码；本专项所选方法没有包含花括号的字符串。 */
    private static String methodSlice(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "未找到生产方法 " + signature);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        while (depth > 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return source.substring(start, end);
    }

    /** 提取真实单条接线或多行布尔守卫，不在测试内复制实现。 */
    private static String statement(String source, String prefix) {
        int start = source.indexOf(prefix);
        check(start >= 0, "缺失接线 " + prefix);
        return source.substring(start, source.indexOf(';', start) + 1);
    }

    /** 提取原主题回调里本片单一条件块，验证实时主题切换。 */
    private static String block(String source, String prefix) {
        return methodSlice(source, prefix);
    }

    /** 为未授权源码边界计算可重复摘要。 */
    private static String sha(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }

    /** 遇到缺失前提或不符合行为即终止专项。 */
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        import java.util.concurrent.atomic.AtomicReference;
        public class AlignmentProbe {
            static float density=1;
            static int dp(float value){return (int)Math.ceil(density*value);}
            static class Gravity {static final int LEFT=3,CENTER_HORIZONTAL=1,RIGHT=5,HORIZONTAL_GRAVITY_MASK=7,CENTER_VERTICAL=16,VERTICAL_GRAVITY_MASK=112;}
            static class MeasureSpec {
                static final int EXACTLY=0x40000000,AT_MOST=0x80000000;
                static int makeMeasureSpec(int size,int mode){if(size<0)throw new AssertionError("负测量宽");return size|mode;}
                static int getSize(int spec){return spec&0x3fffffff;}static int getMode(int spec){return spec&0xc0000000;}
            }
            static class Animator {int cancellations;void cancel(){cancellations++;}}
            static class View {
                static final int VISIBLE=0,GONE=8;int mw,mh,x,y,r,b,visibility=VISIBLE,pl,pt,pr,pb,requests,measures;
                float alpha=.37f,scaleX=.95f,scaleY=.95f;Object parent,params;Animator animator=new Animator();
                void measure(int w,int h){measures++;onMeasure(w,h);}protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h));}
                void setMeasuredDimension(int w,int h){mw=w;mh=h;}int getMeasuredWidth(){return mw;}int getMeasuredHeight(){return mh;}
                void layout(int l,int t,int right,int bottom){x=l;y=t;r=right;b=bottom;onLayout(true,l,t,right,bottom);}
                protected void onLayout(boolean c,int l,int t,int r,int b){}int getVisibility(){return visibility;}
                void setPadding(int l,int t,int r,int b){pl=l;pt=t;pr=r;pb=b;}int getPaddingLeft(){return pl;}int getPaddingRight(){return pr;}
                int getPaddingTop(){return pt;}int getPaddingBottom(){return pb;}Object getLayoutParams(){return params;}Object getParent(){return parent;}
                void requestLayout(){requests++;}void invalidate(){}float getX(){return x;}Animator animate(){return animator;}
            }
            static class FrameLayout extends View {
                boolean clipChildren=false;List<View> removed=new ArrayList<>();
                static class LayoutParams {int leftMargin,rightMargin;LayoutParams(int l,int r){leftMargin=l;rightMargin=r;}}
                void removeView(View view){removed.add(view);}
            }
            static class Drawable {int width;Drawable(int value){width=value;}int getIntrinsicWidth(){return width;}}
            static class TextPaint {
                float fontScale=1;float width(CharSequence text){float result=0;for(int i=0;i<text.length();){int c=Character.codePointAt(text,i);i+=Character.charCount(c);result+=dp(c>0xffff?17:c>=0x4e00?10:c>=0x600&&c<=0x6ff?8:7)*fontScale;}return result;}
            }
            static class Layout {
                enum Alignment {ALIGN_NORMAL}CharSequence text;float width,left;int height;
                int getLineCount(){return text.length()==0?0:1;}float getLineWidth(int line){return width;}float getLineLeft(int line){return left;}
                float getLineRight(int line){return left+width;}int getLineBottom(int line){return height;}float getPrimaryHorizontal(int i){return left;}
                int getLineEnd(int line){return text.length();}int getLineStart(int line){return 0;}CharSequence getText(){return text;}
            }
            static class StaticLayout extends Layout {
                StaticLayout(CharSequence value,int start,int end,TextPaint paint,int available,Alignment alignment,float spacing,float add,boolean pad){
                    text=value.subSequence(start,end);width=paint.width(text);height=dp(18)*Math.max(1,(int)Math.ceil(paint.fontScale));
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
            static class TextUtils {enum TruncateAt {END}static CharSequence ellipsize(CharSequence value,TextPaint paint,int width,TruncateAt type){return paint.width(value)<=width?value:"…";}}
            static class SpoilerEffect {static void addSpoilers(Object... args){}}
            static class AnimatedEmojiSpan {static void release(Object... args){}static Object update(Object... args){return new Object();}}
            static class Point {int x=320;}static class AndroidUtilities {static int statusBarHeight=24;static Point displaySize=new Point();}
            static class ActionBar {static int height=56;static int getCurrentActionBarHeight(){return dp(height);}}
            static class Theme {static class Style {int id=1;}static Style style=new Style();static int queries;static Style getCodexPixelStyle(){queries++;return style;}}
            static class CodexRuntime {static boolean enabled=true,owned=true,forbid;static boolean enabled(){if(forbid)throw new AssertionError("主题回调查询Runtime");return enabled;}
                static boolean ownsConversation(long id){if(forbid)throw new AssertionError("主题回调查询来源");return owned&&id==42;}}
            static class SimpleTextView extends View {
                TextPaint textPaint=new TextPaint();CharSequence text="手机体验优化";Drawable leftDrawable,rightDrawable,rightDrawable2,replacedDrawable;
                int drawablePadding=dp(4),gravity=Gravity.LEFT,minusWidth,lastWidth,offsetX,offsetY,textWidth,textHeight,paddingRight,maxLines=1,fullTextMaxLines=3;
                int replacingDrawableTextIndex=-1,fullLayoutAdditionalWidth,emojiCacheType,currentScrollDelay;
                float rightDrawableScale=1,scrollingOffset,fullLayoutLeftCharactersOffset,replacingDrawableTextOffset;boolean rightDrawableInside,leftDrawableOutside,rightDrawableOutside,widthWrapContent;
                boolean rightDrawableHidden,canHideRightDrawable,buildFullLayout,scrollNonFitText,ellipsizeByGradient=true,textDoesNotFit,attachedToWindow;
                String replacedText;Layout layout,fullLayout,firstLineLayout,partLayout;Object emojiStack;
                List<Object> spoilers=new ArrayList<>(),spoilersPool=new ArrayList<>();Layout.Alignment mAlignment=Layout.Alignment.ALIGN_NORMAL;
                static final int SCROLL_DELAY_MS=500;void checkUi_layerType(){}int getTextHeight(){return textHeight;}
        """;

    private static final String CONTAINER_FIXTURE = """
            boolean codexPlainTitleLayout,codexCenteredTitle,occupyStatusBar=true,glassMode=true,allowDrawStories;
            int codexTitleAreaLeft,codexTitleAreaWidth,avatarSizeInDp=42,leftPadding=dp(8),rightAvatarPadding,lastWidth=-1,largerWidth=-1,fades;
            View avatarImageView=new View(),communityItem,timeItem,starBgItem,starFgItem;
            SimpleTextView animatedSubtitleTextView;
            SimpleTextView titleTextView=new SimpleTextView(),subtitleTextView=new SimpleTextView();
            AtomicReference<SimpleTextView> titleTextLargerCopyView=new AtomicReference<>(),subtitleTextLargerCopyView=new AtomicReference<>();
            ChatAvatarContainer(){avatarImageView.visibility=GONE;titleTextView.setPadding(0,dp(6),0,dp(12));titleTextView.rightDrawableOutside=true;
                subtitleTextView.text="运行中 · MacBook";subtitleTextView.setPadding(0,0,dp(10),0);}
            View getSubtitleTextView(){return subtitleTextView!=null?subtitleTextView:animatedSubtitleTextView;}
            void fadeOutToLessWidth(int width){fades++;largerWidth=width;titleTextLargerCopyView.set(new SimpleTextView());subtitleTextLargerCopyView.set(new SimpleTextView());}
        """;

    private static final String CHAT_FIXTURE = """
            boolean codexPlainHeader,inPreviewMode,inBubbleMode,isInsideContainer,isComments,report;int chatMode,startLoadFromDate;long dialog_id=42;
            ChatAvatarContainer avatarContainer=new ChatAvatarContainer();boolean isReport(){return report;}
        """;

    private static final String SCENARIOS = """
            static int assertions;
            static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
            static void close(float actual,float expected,String why){check(Math.abs(actual-expected)<=1,why+" actual="+actual+" expected="+expected);}
            static ChatAvatarContainer header(int width,int right,int style){ChatAvatarContainer c=new ChatAvatarContainer();View parent=new View();parent.mw=dp(width);
                c.parent=parent;c.params=new FrameLayout.LayoutParams(dp(52),dp(right));c.setCodexHeaderAlignment(true,style);return c;}
            static void layout(ChatAvatarContainer c,int width,int right){((View)c.parent).mw=dp(width);
                c.measure(MeasureSpec.makeMeasureSpec(dp(width)-dp(52)-dp(right),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(dp(ActionBar.height)+(c.occupyStatusBar?AndroidUtilities.statusBarHeight:0),MeasureSpec.EXACTLY));
                c.layout(dp(52),0,dp(width-right),c.mh);}
            static float groupWidth(SimpleTextView v){return v.getTextWidth()+v.getSideDrawablesSize();}
            static float groupLeft(SimpleTextView v){return v.leftDrawable!=null?v.x:v.getTextStartX()+(v.layout==null?0:v.layout.getLineLeft(0));}
            static String longText(String value){return value+value+value;}
            static void centered(ChatAvatarContainer c,int width){for(SimpleTextView v:new SimpleTextView[]{c.titleTextView,c.subtitleTextView}){
                close(dp(52)+groupLeft(v)+groupWidth(v)/2,dp(width)/2f,"短文本/侧图形未对齐真实父中线");
                check(v.x>=c.codexTitleAreaLeft&&v.r<=c.codexTitleAreaLeft+c.codexTitleAreaWidth,"文字框越过安全区");}}
            static void shortAndLong(){for(float d:new float[]{1,2,2.625f})for(int style:new int[]{1,2,4})for(int width:new int[]{240,320,641})for(int right:new int[]{52,92}){
                density=d;ChatAvatarContainer c=header(width,right,style);c.titleTextView.text="中A😀";c.subtitleTextView.text="Mac";layout(c,width,right);centered(c,width);
                check(c.subtitleTextView.pr==0,"旧副标题padding残留");
                check(c.titleTextView.pt==dp(6)&&c.titleTextView.pb==dp(12),"标题原纵向padding改变");
                c.titleTextView.text=longText("很长的中文标题ABCDEFGHIJKLMNOPQRSTUVWXYZ😀继续保留头部");c.subtitleTextView.text=longText("未知 · 非常长的实际来源设备名称abcdefghijk");layout(c,width,right);
                for(SimpleTextView v:new SimpleTextView[]{c.titleTextView,c.subtitleTextView}){
                    check(v.mw==c.codexTitleAreaWidth,"长行未限制到安全宽");
                    close(v.getTextStartX()+v.layout.getLineLeft(0),v.x,"长文本头部被内部居中裁切");
                    check(v.ellipsizeByGradient&&v.textDoesNotFit,"长文本原渐隐丢失");
                }
                check(c.fades==0&&c.titleTextLargerCopyView.get()==null&&c.subtitleTextLargerCopyView.get()==null,"Codex创建旧缩宽副本");
            }density=1;}
            static void drawablesAndRtl(){for(float scale:new float[]{1,1.5f,2})for(String text:new String[]{"来源","Mac","مرحبا"}){
                ChatAvatarContainer c=header(360,92,4);c.titleTextView.text=text;c.subtitleTextView.text=text;c.subtitleTextView.leftDrawable=new Drawable(dp(14));
                c.titleTextView.textPaint.fontScale=c.subtitleTextView.textPaint.fontScale=scale;layout(c,360,92);centered(c,360);
                check(c.subtitleTextView.mw>=c.subtitleTextView.getTextWidth()+dp(18),"运行圆环/间距未计入真实测量");
                close(c.subtitleTextView.getTextStartX()+c.subtitleTextView.layout.getLineLeft(0),c.subtitleTextView.x+dp(18),"RTL/圆环文字起点错误");
                c.subtitleTextView.text="مرحبابكمرحبابكمرحبابكمرحبابكمرحبابكمرحبابك";layout(c,360,92);
                close(c.subtitleTextView.getTextStartX()+c.subtitleTextView.layout.getLineLeft(0),c.subtitleTextView.x+dp(18),"RTL长文本头部被裁");
            }}
            static void leftAndRestore(){ChatAvatarContainer c=header(320,52,1);layout(c,320,52);int titleY=c.titleTextView.y,subtitleY=c.subtitleTextView.y;
                int titleHeight=c.titleTextView.b-c.titleTextView.y,subtitleHeight=c.subtitleTextView.b-c.subtitleTextView.y;
                layout(c,240,52);check(c.fades==0&&c.titleTextLargerCopyView.get()==null&&c.subtitleTextLargerCopyView.get()==null,"普通Codex缩宽仍创建350ms旧副本");layout(c,320,52);
                for(int style:new int[]{3,6}){c.setCodexHeaderAlignment(true,style);layout(c,320,52);
                    check(!c.codexCenteredTitle&&c.codexPlainTitleLayout&&c.titleTextView.x==dp(8)+dp(13)&&c.subtitleTextView.x==c.titleTextView.x,"03/06原左位置改变");
                    check(c.subtitleTextView.pr==dp(10)&&c.titleTextView.y==titleY&&c.subtitleTextView.y==subtitleY,"左对齐未恢复padding/纵向位置");
                }
                c.setCodexHeaderAlignment(false,1);layout(c,320,52);check(!c.codexPlainTitleLayout&&c.subtitleTextView.pr==dp(10),"退出未恢复普通布局");
                layout(c,240,52);check(c.fades==1,"普通/preview缩宽350ms原入口丢失");
                SimpleTextView titleCopy=c.titleTextLargerCopyView.get(),subtitleCopy=c.subtitleTextLargerCopyView.get();boolean clip=c.clipChildren;
                c.setCodexHeaderAlignment(true,2);check(titleCopy.animator.cancellations==1&&subtitleCopy.animator.cancellations==1,"旧副本动画未取消");
                check(c.removed.contains(titleCopy)&&c.removed.contains(subtitleCopy)&&c.titleTextLargerCopyView.get()==null&&c.subtitleTextLargerCopyView.get()==null,"旧副本未清理");
                check(c.clipChildren==clip,"切模式改变原clipChildren");int requests=c.requests;c.setCodexHeaderAlignment(true,4);check(c.requests==requests,"相同对齐反复requestLayout");
                ((View)c.parent).mw=dp(240);layout(c,240,52);check(c.fades==1,"重新进入Codex后缩宽产生副本");
                check(c.titleTextView.b-c.titleTextView.y==titleHeight&&c.subtitleTextView.b-c.subtitleTextView.y==subtitleHeight,"标题原高度改变");
            }
            static void hooksAndScope(){for(int excluded=0;excluded<11;excluded++){Chat chat=new Chat();CodexRuntime.enabled=CodexRuntime.owned=true;
                switch(excluded){case 0:CodexRuntime.enabled=false;break;case 1:CodexRuntime.owned=false;break;case 2:chat.chatMode=1;break;
                    case 3:chat.report=true;break;case 4:chat.startLoadFromDate=123;break;case 5:chat.inPreviewMode=true;break;case 6:chat.inBubbleMode=true;break;
                    case 7:chat.isInsideContainer=true;break;case 8:chat.isComments=true;break;case 9:chat.chatMode=99;break;case 10:chat.dialog_id=43;break;}
                chat.saveGate();chat.init();check(!chat.avatarContainer.codexPlainTitleLayout,"排除模式获得标题opt-in "+excluded);
            }CodexRuntime.enabled=CodexRuntime.owned=true;Chat c=new Chat();c.saveGate();Theme.style.id=1;c.init();check(c.avatarContainer.codexCenteredTitle,"正常创建未居中");
                float alpha=c.avatarContainer.alpha,scale=c.avatarContainer.scaleX;int visibility=c.avatarContainer.visibility;
                CodexRuntime.forbid=true;Theme.style.id=3;c.theme();check(!c.avatarContainer.codexCenteredTitle&&c.avatarContainer.codexPlainTitleLayout,"主题01到03未恢复左对齐");
                Theme.style.id=4;c.theme();check(c.avatarContainer.codexCenteredTitle,"主题03到04未恢复居中");CodexRuntime.forbid=false;
                c.preview(true);check(!c.avatarContainer.codexPlainTitleLayout&&c.avatarContainer.subtitleTextView.pr==dp(10),"preview未退出图示对齐");
                c.preview(false);check(c.avatarContainer.codexCenteredTitle,"preview返回未恢复图示对齐");
                check(c.avatarContainer.alpha==alpha&&c.avatarContainer.scaleX==scale&&c.avatarContainer.visibility==visibility,"换色/preview改变多选隐藏状态");
                c.avatarContainer=null;c.theme();c.preview(true);
            }
            static void landscapeAndHidden(){ActionBar.height=48;ChatAvatarContainer c=header(640,92,2);c.occupyStatusBar=false;c.subtitleTextView.visibility=View.GONE;
                layout(c,640,92);check(c.mh==dp(48),"原横屏高度传递改变");int y=c.titleTextView.y;c.setCodexHeaderAlignment(false,2);layout(c,640,92);
                check(c.titleTextView.y==y,"无副标题时原纵向位置改变");ActionBar.height=56;
            }
            public static void main(String[] args){shortAndLong();drawablesAndRtl();leftAndRestore();hooksAndScope();landscapeAndHidden();
                System.out.println("PASS ChatHeaderAlignmentTest: "+assertions+" assertions; production measure/layout/createLayout/calcOffset + actual hooks; 5 styles, 240/320/641dp, 52/92dp margins, density/fontScale, Chinese/Latin/emoji/synthetic RTL, running drawable, long-head/gradient, preview/theme/scope/copy restoration. Android font/Bidi/screenshots unverified.");}
        """;
}
