package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实缓存、原两行布局/状态位置、原typing坐标及来源交换；平台字体不是此夹具的验收范围。 */
public final class DialogSourceOrderTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?".":args[0]);
        String cell=Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"));
        Path temp=Files.createTempDirectory("codex-dialog-source-order-");
        try {
            ArrayList<String> files=new ArrayList<>();
            write(temp,files,"CodexRuntime.java","""
                    package com.butang.codextop;
                    public class CodexRuntime {
                        public static boolean active=true,owned=true;public static String name="完整真实电脑名称🙂";
                        public static boolean enabled(){return active;}public static boolean ownsConversation(long id){return owned&&id==10;}
                        public static class ConversationInfo {public final String machineName;ConversationInfo(String name){machineName=name;}}
                        public static ConversationInfo conversationInfo(long id){return new ConversationInfo(name);}
                    }
                    """);
            write(temp,files,"CodexPixelPalette.java","""
                    package com.butang.codextop;
                    public class CodexPixelPalette {public static final int STYLE_TERMINAL=1,STYLE_EDITORIAL=2,STYLE_INDUSTRIAL=3,STYLE_LIME=4,STYLE_BLUEPRINT=6;}
                    """);
            write(temp,files,"Theme.java","""
                    package org.telegram.ui.ActionBar;
                    public class Theme {
                        public static final Object dialogs_messageNamePaint=new Object();public static final Style style=new Style();
                        public static class Style {public int id=3;}public static Style getCodexPixelStyle(){return style;}
                    }
                    """);
            String methods=method(cell,"    public static String codexSourceName(long dialogId)")
                    +method(cell,"    private boolean refreshCodexSource()")
                    +method(cell,"    private CharSequence codexSourceLabel(CharSequence nativeLabel)")
                    +method(cell,"    private void prepareCodexSourceGlyph(CharSequence nativeLabel)")
                    +method(cell,"    private int codexSourceNameLineWidth(int availableWidth)")
                    +method(cell,"    private void applyCodexSourceOrder()")
                    +method(cell,"    private int getCollapsedHeight()")
                    +method(cell,"    public void updateColors()");
            String gate=between(cell,"        final boolean compactCodexLayout =","\n        // 保留公开原间距");
            String vertical=between(cell,"        messageWidth = Math.max(dp(12), messageWidth);","\n        animatedEmojiStack2 = AnimatedEmojiSpan.update");
            // 在真实交换调用前明确注入合成已有小数Y；get/set保持原float绝对坐标语义。
            vertical=vertical.replace("        applyCodexSourceOrder();", "        existingNativeImageInset();\n        applyCodexSourceOrder();");
            String update=between(cell,"        if (refreshCodexSource()) {","\n        drawAvatarSelector");
            String mask=between(cell,"                boolean continueUpdate =",";")+";";
            String summaryTyping=between(cell,"                float top;\n                float typingAnimationOffset = dp(14);","\n                if (updateHelper.typingProgres != 1f)");
            String typing=between(cell,"                if (updateHelper.typingOutToTop) {\n                    top = messageTop + typingAnimationOffset * (1f - updateHelper.typingProgres);","\n                canvas.translate(typingLeft, top);");
            write(temp,files,"SourceOrderProbe.java",FIXTURE+methods
                    +"boolean sourceUpdate(int mask){boolean requestLayout=false,rebuildLayout=false,invalidate=false,codexPreviewChanged=false;"+update+mask+"return continueUpdate&&requestLayout;}"
                    +"void buildLayout(){buildAt(0);}" 
                    +"void buildAt(int offset){"+gate+"builds++;int messageWidth=width;prepareCodexSourceGlyph(nativeLabel);CharSequence messageNameString=codexSourceLabel(nativeLabel);int avatarTop=dp(11);messageNameTop=dp(32)+offset;"+vertical+"lastWidth=messageWidth;}"
                    +"float summaryTypingTop(){"+summaryTyping+"return top;}"
                    +"float typingTop(){float top,typingAnimationOffset=dp(14);"+typing+"return top;}"
                    +CASES+"}");
            ArrayList<String> command=new ArrayList<>();command.add("--release");command.add("8");command.add("-d");command.add(temp.toString());command.addAll(files);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,command.toArray(String[]::new))!=0)throw new AssertionError("原来源顺序方法Java8编译失败");
            try(URLClassLoader loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                try{loader.loadClass("com.butang.codextop.SourceOrderProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("原来源顺序行为失败",e.getCause());}
            }
        } finally {try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    private static void write(Path dir,ArrayList<String> files,String name,String value)throws Exception {Path p=dir.resolve(name);Files.writeString(p,value);files.add(p.toString());}
    private static String between(String s,String start,String end){int a=s.indexOf(start);if(a<0)throw new AssertionError("缺少原入口 "+start);int b=s.indexOf(end,a+start.length());if(b<0)throw new AssertionError("缺少原边界 "+end);return s.substring(a,b);}
    private static String method(String s,String signature){int a=s.indexOf(signature);if(a<0)throw new AssertionError("缺少原方法 "+signature);int open=s.indexOf('{',a),depth=0;boolean quoted=false,escaped=false;for(int i=open;i<s.length();i++){char c=s.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}if(c=='"'){quoted=true;continue;}if(c=='{')depth++;if(c=='}'&&--depth==0)return s.substring(a,i+1);}throw new AssertionError(signature);}
    private static final String FIXTURE="""
            package com.butang.codextop;
            import org.telegram.ui.ActionBar.Theme;
            public class SourceOrderProbe {
                boolean isDialogCell=true,isTopic,isTransitionSupport,forum,tagged,twoLinesForName,nameIsEllipsized,useForceThreeLines,useSeparator,codexNativeForceThreeLines,codexSourceLayoutDirty,codexSourceBelow,codexSourceGlyphWanted,codexSourceGlyphExclusive,codexSourceGlyphVisible,codexCompactLayout,updateLayout;
                Object customDialog,encryptedChat,resourcesProvider,messageNameLayout;
                Message message;
                String codexSourceName;
                CharSequence nativeLabel;
                int codexSourceGlyphReserve;float codexSourceGlyphTextOffsetX;
                long currentDialogId=10;int currentDialogFolderId,currentDialogCommunityId,currentDialogFolderDialogsCount=1,heightDefault=70,heightThreeLines=76,addHeightForTags=3,addForumHeightForTags=11;
                int messageTop,messageNameTop,codexStatusTop,buttonTop,width=180,lastWidth,requests,invalidations,builds;
                int messageLeft=17,messageNameLeft=17,typingLeft=17,timeTop=13,timeLeft=151,codexStatusLeft=130;
                ImageReceiver[] thumbImage={};
                Helper updateHelper=new Helper();
                static float density=1;
                static class Helper {boolean typingOutToTop;float typingProgres;boolean update(){return false;}}
                static class Message {Object highlightedWords;boolean hasHighlightedWords(){return false;}}
                static class SharedConfig {static boolean useThreeLinesLayout;}
                static class MessagesController {static final int UPDATE_MASK_STATUS=1;}
                static class TextUtils {
                    static class TruncateAt {static final Object END=new Object();}
                    static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}
                    static boolean equals(CharSequence a,CharSequence b){return a==b||(a!=null&&b!=null&&a.toString().equals(b.toString()));}
                    static int getTrimmedLength(CharSequence s){return s.toString().trim().length();}
                }
                static class SpannableStringBuilder implements CharSequence {
                    final StringBuilder text;Object span;
                    SpannableStringBuilder(CharSequence s){text=new StringBuilder(s);if(s instanceof SpannableStringBuilder)span=((SpannableStringBuilder)s).span;}
                    SpannableStringBuilder append(CharSequence s){text.append(s);return this;}
                    public int length(){return text.length();}public char charAt(int n){return text.charAt(n);}
                    public CharSequence subSequence(int a,int b){return text.subSequence(a,b);}public String toString(){return text.toString();}
                }
                static class ImageReceiver {float y,offset;int sets;float getImageY(){return y;}void setImageY(float value){y=value;sets++;}}
                static class Layout {static class Alignment {static final Object ALIGN_NORMAL=new Object();}}
                static class StaticLayoutEx {static boolean empty;static Object createStaticLayout(Object... args){return empty?null:args[0];}}
                static class AndroidUtilities {static CharSequence highlightText(Object... args){return null;}}
                static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
                static int dp(float value){return (int)Math.ceil(value*density);}
                boolean isForumCell(){return forum;}boolean hasTags(){return tagged;}boolean shouldUseCompactCodexLayout(){return true;}boolean hasCodexStatusAvatar(){return true;}
                void requestLayout(){requests++;}void invalidate(){invalidations++;}
                static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
                static void near(float a,float b,String why){check(Math.abs(a-b)<.001f,why+" actual="+a+" expected="+b);}
                void existingNativeImageInset(){for(ImageReceiver image:thumbImage){if(image.offset!=0){image.setImageY(image.getImageY()+image.offset);image.offset=0;}}}
                void fresh(){codexSourceLayoutDirty=true;messageNameLayout=null;}
                static void reset(){CodexRuntime.active=CodexRuntime.owned=true;CodexRuntime.name="完整真实电脑名称🙂";Theme.style.id=3;StaticLayoutEx.empty=false;SharedConfig.useThreeLinesLayout=false;}
            """;
    private static final String CASES="""
                public static void main(String[] args){
                    for(float scale:new float[]{1f,1.5f,3f})for(int style:new int[]{1,2,3,4,6})for(int count:new int[]{0,3})for(boolean rtl:new boolean[]{false,true}) {
                        reset();density=scale;Theme.style.id=style;SourceOrderProbe p=new SourceOrderProbe();p.refreshCodexSource();
                        p.thumbImage=new ImageReceiver[count];for(int i=0;i<count;i++){p.thumbImage[i]=new ImageReceiver();p.thumbImage[i].offset=(i+1)*.375f;}
                        int left=rtl?dp(16):dp(10+6);p.messageLeft=p.messageNameLeft=p.typingLeft=left;
                        int timeX=rtl?dp(12):dp(150);p.timeLeft=timeX;int statusX=rtl?dp(16):dp(130);p.codexStatusLeft=statusX;
                        int nativeMessage=dp(51),nativeName=dp(32),nativeThumb=dp(51);boolean below=style==1||style==2||style==4;
                        p.buildLayout();check(p.messageTop==(below?nativeName:nativeMessage)&&p.messageNameTop==(below?nativeMessage:nativeName),"five-style exact source/summary order");
                        check(p.codexStatusTop==nativeName,"right status moved with source row");check(p.lastWidth==180&&p.messageLeft==left&&p.messageNameLeft==left&&p.typingLeft==left&&p.timeLeft==timeX&&p.codexStatusLeft==statusX,"width/RTL/time/status horizontal changed");
                        for(int i=0;i<count;i++)near(p.thumbImage[i].getImageY(),nativeThumb+(i+1)*.375f+(below?nativeName-nativeMessage:0),"original image actual Y delta/float inset lost");
                        check(p.getCollapsedHeight()==dp(76)+1,"native three-line fixed height changed");
                        for(boolean out:new boolean[]{false,true})for(float progress:new float[]{0f,.5f,1f}) {
                            p.updateHelper.typingOutToTop=out;p.updateHelper.typingProgres=progress;
                            near(p.summaryTypingTop(),p.messageTop+(out?-1:1)*dp(14)*progress,"original summary typing coordinate entry/exit");
                            near(p.typingTop(),p.messageTop+(out?1:-1)*dp(14)*(1f-progress),"original typing remains at summary row");
                        }
                    }
                    reset();density=1.5f;Theme.style.id=2;SourceOrderProbe offset=new SourceOrderProbe();offset.refreshCodexSource();offset.thumbImage=new ImageReceiver[]{new ImageReceiver()};offset.thumbImage[0].offset=.625f;offset.buildAt(7);
                    check(offset.messageTop==dp(32)+7&&offset.messageNameTop==dp(51),"source order assumed fixed19dp native offset");near(offset.thumbImage[0].getImageY(),dp(51)+.625f+(dp(32)+7-dp(51)),"thumb delta ignored existing layout offset");check(offset.codexStatusTop==dp(32)+7,"status Y captured after swap");
                    reset();SourceOrderProbe themed=new SourceOrderProbe();density=1;themed.refreshCodexSource();themed.buildLayout();int initial=themed.builds;
                    for(int style:new int[]{2,6,1,3,4,6}) {Theme.style.id=style;check(themed.sourceUpdate(4),"same-name theme order skipped native mask update");themed.buildLayout();boolean below=style==1||style==2||style==4;check(themed.messageTop==(below?32:51)&&themed.messageNameTop==(below?51:32),"same-name theme order skipped build");}
                    check(themed.builds==initial+6,"theme change did not rebuild actual layout");check(!themed.sourceUpdate(4),"same order/meta overinvalidated");themed.buildLayout();check(themed.builds==initial+6,"same order/meta ignored native helper gate");
                    Theme.style.id=2;themed.updateColors();themed.buildLayout();check(themed.messageTop==32&&themed.codexStatusTop==32&&themed.requests>0,"theme colors path failed ordering/status");
                    for(boolean nativeForce:new boolean[]{false,true}) {reset();Theme.style.id=1;SourceOrderProbe p=new SourceOrderProbe();p.useForceThreeLines=nativeForce;p.refreshCodexSource();p.buildLayout();CodexRuntime.name=null;check(p.refreshCodexSource(),"missing source not refreshed");p.buildLayout();check(p.codexSourceName==null&&!p.codexSourceBelow&&p.useForceThreeLines==nativeForce&&p.getCollapsedHeight()==dp(nativeForce?76:70)+1,"missing source/native force restoration");check(p.messageNameTop==32&&p.messageTop==(nativeForce?32:39),"source removal retained swapped native coordinates");}
                    for(String empty:new String[]{""," ","\\n\\t"}) {reset();Theme.style.id=4;SourceOrderProbe p=new SourceOrderProbe();p.refreshCodexSource();p.buildLayout();CodexRuntime.name=empty;p.refreshCodexSource();p.buildLayout();check(p.codexSourceName==null&&!p.codexSourceBelow&&!p.useForceThreeLines&&p.messageTop==39,"blank metadata fabricated source/residual swap");}
                    for(int special=0;special<8;special++){reset();Theme.style.id=2;SourceOrderProbe p=new SourceOrderProbe();p.refreshCodexSource();p.buildLayout();p.isDialogCell=special!=0;p.customDialog=special==1?new Object():null;p.isTopic=special==2;p.isTransitionSupport=special==3;p.forum=special==4;p.currentDialogFolderId=special==5?1:0;p.currentDialogCommunityId=special==6?1:0;CodexRuntime.owned=special!=7;p.refreshCodexSource();check(p.codexSourceName==null&&!p.codexSourceBelow&&!p.useForceThreeLines,"native special owner retained cached source/order");}
                    for(int style:new int[]{0,5}) {reset();SourceOrderProbe p=new SourceOrderProbe();p.refreshCodexSource();Theme.style.id=style;p.refreshCodexSource();check(p.codexSourceName==null&&!p.codexSourceBelow&&!p.useForceThreeLines,"unselected style retains source");}
                    for(boolean forum:new boolean[]{false,true}) {reset();Theme.style.id=2;SourceOrderProbe stale=new SourceOrderProbe();stale.refreshCodexSource();stale.messageNameLayout=new Object();stale.messageTop=63;stale.messageNameTop=37;stale.tagged=!forum;stale.forum=forum;stale.thumbImage=new ImageReceiver[]{new ImageReceiver()};stale.thumbImage[0].y=65.375f;stale.applyCodexSourceOrder();check(stale.messageTop==63&&stale.messageNameTop==37,"native tags/forum actual name-layout guard missing");near(stale.thumbImage[0].getImageY(),65.375f,"native tags/forum thumb changed");}
                    reset();Theme.style.id=1;SourceOrderProbe tagged=new SourceOrderProbe();tagged.tagged=true;tagged.refreshCodexSource();tagged.buildLayout();check(tagged.messageNameLayout==null&&tagged.messageTop==32&&tagged.messageNameTop==32&&tagged.codexStatusTop==32,"native tag layout exchanged");
                    reset();Theme.style.id=2;SourceOrderProbe emptyLayout=new SourceOrderProbe();emptyLayout.refreshCodexSource();StaticLayoutEx.empty=true;emptyLayout.buildLayout();check(emptyLayout.messageNameLayout==null&&emptyLayout.messageTop==51&&emptyLayout.messageNameTop==32&&emptyLayout.codexStatusTop==51,"null actual name layout exchanged");
                    reset();Theme.style.id=4;SourceOrderProbe draft=new SourceOrderProbe();SpannableStringBuilder styled=new SpannableStringBuilder("草稿");Object span=new Object();styled.span=span;draft.nativeLabel=styled;draft.refreshCodexSource();draft.buildLayout();check(draft.messageTop==32&&draft.messageNameTop==51&&draft.codexStatusTop==32,"draft original right status Y changed");check(draft.messageNameLayout instanceof SpannableStringBuilder&&((SpannableStringBuilder)draft.messageNameLayout).span==span&&draft.messageNameLayout.toString().equals("草稿 · 完整真实电脑名称🙂"),"draft spans/source native name layout lost");
                    System.out.println("DialogSourceOrderTest: "+checks+" actual Java8 method checks PASS");
                }
            """;
}
