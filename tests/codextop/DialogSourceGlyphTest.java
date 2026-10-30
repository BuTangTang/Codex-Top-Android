package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 真实Java8缓存/名字宽/原Bidi补偿/图形绘制专项；字体与Canvas为替身，不替代Android截图。 */
public final class DialogSourceGlyphTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        String cell = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"));
        Path temp = Files.createTempDirectory("codex-dialog-source-glyph-");
        try {
            ArrayList<String> files = new ArrayList<>();
            write(temp, files, "CodexRuntime.java", """
                    package com.butang.codextop;
                    public class CodexRuntime {
                        public static boolean active=true,owned=true;
                        public static String name="Desktop"; public static int queries;
                        public static boolean enabled(){return active;}
                        public static boolean ownsConversation(long id){return owned&&id==10;}
                        public static class ConversationInfo {public final String machineName;ConversationInfo(String n){machineName=n;}}
                        public static ConversationInfo conversationInfo(long id){queries++;return new ConversationInfo(name);}
                    }
                    """);
            write(temp, files, "CodexPixelPalette.java", """
                    package com.butang.codextop;
                    public class CodexPixelPalette {public static final int STYLE_TERMINAL=1,STYLE_EDITORIAL=2,STYLE_INDUSTRIAL=3,STYLE_LIME=4,STYLE_BLUEPRINT=6;}
                    """);
            write(temp, files, "Theme.java", """
                    package org.telegram.ui.ActionBar;
                    import com.butang.codextop.SourceGlyphProbe.Paint;
                    public class Theme {
                        public static final Paint dialogs_messageNamePaint=new Paint(1);
                        public static final Style style=new Style();public static int queries;
                        public static class Style {public int id=1;}
                        public static Style getCodexPixelStyle(){queries++;return style;}
                    }
                    """);
            String methods = method(cell, "    public static String codexSourceName(long dialogId)")
                    + method(cell, "    private boolean refreshCodexSource()")
                    + method(cell, "    private CharSequence codexSourceLabel(CharSequence nativeLabel)")
                    + method(cell, "    private void prepareCodexSourceGlyph(CharSequence nativeLabel)")
                    + method(cell, "    private int codexSourceNameLineWidth(int availableWidth)")
                    + method(cell, "    private void updateCodexSourceGlyphLayout()")
                    + method(cell, "    private void drawCodexSourceGlyph(Canvas canvas)")
                    + method(cell, "    private void applyCodexSourceOrder()")
                    + method(cell, "    private void appendCodexSourceDescription(StringBuilder description)");
            String fields = between(cell, "    private String codexSourceName;", "\n    /** 五样式");
            String label = between(cell, "        prepareCodexSourceGlyph(messageNameString);", "\n\n        // 原计数");
            String vertical = between(cell, "        messageWidth = Math.max(dp(12), messageWidth);", "\n        animatedEmojiStack2 = AnimatedEmojiSpan.update");
            String rtl = between(cell, "            if (messageNameLayout != null && messageNameLayout.getLineCount() > 0) {", "\n            if (buttonLayout != null)");
            String ltrStart = "            if (messageNameLayout != null && messageNameLayout.getLineCount() > 0) {\n                messageNameLeft -= ";
            String ltr = between(cell, ltrStart, "\n        }\n        if (typingLayout != null");
            String layout = between(cell, "        updateCodexSourceGlyphLayout();", "\n        updateThumbsPosition();");
            String draw = between(cell, "                canvas.translate(messageNameLeft, messageNameTop);", "\n                try {");
            write(temp, files, "SourceGlyphProbe.java", FIXTURE + fields + methods
                    + "void build(int width,CharSequence nativeLabel,boolean localeRTL){builds++;int messageWidth=width;CharSequence messageNameString=nativeLabel;"
                    + label + "int avatarTop=dp(11);messageNameLeft=messageLeft=typingLeft=dp(16);messageNameTop=dp(32);"
                    + vertical + "double widthpx;float left;if(localeRTL){" + rtl + "}else{" + ltr + "}"
                    + layout + "lastSharedWidth=messageWidth;}"
                    + "void draw(Canvas canvas){if(messageNameLayout!=null&&!isForumCell()){canvas.save();" + draw
                    + "canvas.text(messageNameLayout);canvas.restore();}}"
                    + CASES + "}");
            ArrayList<String> command = new ArrayList<>();
            command.add("--release");command.add("8");command.add("-encoding");command.add("UTF-8");
            command.add("-d");command.add(temp.toString());command.addAll(files);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, command.toArray(String[]::new)) != 0)
                throw new AssertionError("真实来源图形方法Java8编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, null)) {
                loader.loadClass("com.butang.codextop.SourceGlyphProbe").getMethod("run").invoke(null);
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void write(Path dir, ArrayList<String> files, String name, String value) throws Exception {
        Path path = dir.resolve(name);Files.writeString(path,value);files.add(path.toString());
    }
    private static String between(String s,String start,String end) {
        int a=s.indexOf(start),b=a<0?-1:s.indexOf(end,a+start.length());
        if(a<0||b<0)throw new AssertionError("缺少真实入口/边界 "+start);
        return s.substring(a,b);
    }
    private static String method(String s,String signature) {
        int a=s.indexOf(signature);if(a<0)throw new AssertionError("缺少真实方法 "+signature);
        int open=s.indexOf('{',a),depth=0;boolean quoted=false,escaped=false;
        for(int i=open;i<s.length();i++){char c=s.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}if(c=='"'){quoted=true;continue;}if(c=='{')depth++;if(c=='}'&&--depth==0)return s.substring(a,i+1);}
        throw new AssertionError(signature);
    }

    private static final String FIXTURE = """
            package com.butang.codextop;
            import java.text.Bidi;
            import java.util.ArrayList;
            import org.telegram.ui.ActionBar.Theme;
            public class SourceGlyphProbe {
                boolean isDialogCell=true,isTopic,isTransitionSupport,forum,tagged,useForceThreeLines,updateLayout,twoLinesForName,nameIsEllipsized;
                Object customDialog,resourcesProvider;Message message;
                long currentDialogId=10;int currentDialogFolderId,currentDialogCommunityId,currentDialogFolderDialogsCount=1;
                int messageTop,messageNameTop,messageNameLeft,messageLeft,typingLeft,codexStatusTop,buttonTop,builds,lastSharedWidth;
                StaticLayout messageNameLayout;ImageReceiver[] thumbImage={};
                static float density=1;static int checks;
                static class Message {Object highlightedWords;boolean hasHighlightedWords(){return false;}}
                static class SharedConfig {static boolean useThreeLinesLayout;}
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
                public static class Paint {
                    public static final int ANTI_ALIAS_FLAG=1;public enum Style{FILL,STROKE}
                    static int allocations;int color=0xff448866,alpha=173;float stroke=1;Style style=Style.FILL;
                    public Paint(int flags){allocations++;}
                    public int getColor(){return color;}public void setColor(int c){color=c;}
                    public int getAlpha(){return alpha;}public void setAlpha(int a){alpha=a;}
                    public void setStyle(Style s){style=s;}public void setStrokeWidth(float s){stroke=s;}
                    public float getStrokeWidth(){return stroke;}public float ascent(){return -dp(10);}public float descent(){return dp(2);}
                }
                static class StaticLayout {
                    final CharSequence text;final int width,direction;final float textWidth;
                    StaticLayout(CharSequence text,int width){this.text=text;this.width=width;direction=new Bidi(text.toString(),Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()?1:-1;textWidth=Math.min(width,text.length()*dp(4));}
                    int getLineCount(){return 1;}float getLineWidth(int n){return textWidth;}
                    float getLineLeft(int n){return direction<0?width-textWidth:0;}
                    float getLineRight(int n){return getLineLeft(n)+textWidth;}
                    int getParagraphDirection(int n){return direction;}int getLineBaseline(int n){return dp(12);}
                }
                static class Layout {static class Alignment{static final Object ALIGN_NORMAL=new Object();}}
                static class StaticLayoutEx {static boolean empty;static StaticLayout createStaticLayout(CharSequence text,Object paint,int width,Object alignment,float a,int b,boolean c,Object truncate,int ellipsis,int lines){if(width!=ellipsis)throw new AssertionError("name ellipsis width differs");return empty?null:new StaticLayout(text,width);}}
                static class AndroidUtilities {static CharSequence highlightText(Object... args){return null;}}
                static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
                static class ImageReceiver {float y;int sets;float getImageY(){return y;}void setImageY(float value){y=value;sets++;}}
                static class Canvas {
                    float x,y,oldX,oldY,textX,textY;int rects,lines;float glyphLeft,glyphRight,glyphTop,glyphBottom;int color,alpha;
                    void save(){oldX=x;oldY=y;}void restore(){x=oldX;y=oldY;}void translate(float a,float b){x+=a;y+=b;}
                    void drawRect(float l,float t,float r,float b,Paint p){rects++;glyphLeft=x+l-p.stroke/2;glyphRight=x+r+p.stroke/2;glyphTop=y+t-p.stroke/2;color=p.color;alpha=p.alpha;}
                    void drawLine(float a,float b,float c,float d,Paint p){lines++;glyphBottom=Math.max(glyphBottom,y+Math.max(b,d)+p.stroke/2);}
                    void text(StaticLayout layout){textX=x+layout.getLineLeft(0);textY=y;}
                }
                static int dp(float v){return (int)Math.ceil(v*density);}
                boolean isForumCell(){return forum;}boolean hasTags(){return tagged;}
                static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
                static void near(float a,float b,String s){check(Math.abs(a-b)<.001f,s+" actual="+a+" expected="+b);}
                static void reset(){density=1;CodexRuntime.active=CodexRuntime.owned=true;CodexRuntime.name="Desktop";Theme.style.id=1;StaticLayoutEx.empty=false;SharedConfig.useThreeLinesLayout=false;}
                static SourceGlyphProbe fresh(){SourceGlyphProbe p=new SourceGlyphProbe();check(p.refreshCodexSource(),"new source not cached");return p;}
            """;

    private static final String CASES = """
                public static void run(){
                    // Every selected style retains source/order; only01/06 get the decorative glyph.
                    for(int style:new int[]{1,2,3,4,6}){
                        reset();Theme.style.id=style;SourceGlyphProbe p=fresh();p.build(180,null,false);
                        boolean wanted=style==1||style==6,below=style==1||style==2||style==4;
                        check(p.codexSourceGlyphWanted==wanted&&p.codexSourceGlyphVisible==wanted,"glyph style/source gate");
                        check(p.messageNameLayout.width==(wanted?166:180)&&p.lastSharedWidth==180,"glyph modified shared/native name width");
                        check(p.messageNameTop==dp(below?51:32)&&p.messageTop==dp(below?32:51)&&p.codexStatusTop==dp(32),"glyph moved source/summary/right status rows");
                        Canvas c=new Canvas();p.draw(c);check(c.rects==(wanted?1:0)&&c.lines==(wanted?2:0),"draw did not use cached wanted gate");
                        near(c.textX,dp(16)+(wanted?dp(14):0),"name text reservation");near(c.textY,p.messageNameTop,"glyph/text wrong row after source order");
                        StringBuilder description=new StringBuilder();p.appendCodexSourceDescription(description);check(description.toString().equals("来源电脑：Desktop. "),"glyph entered accessibility text");
                    }
                    // Same-name transitions must dirty even when the source/order are unchanged.
                    for(int[] transition:new int[][]{{1,2},{2,1},{3,6},{6,3}}){reset();Theme.style.id=transition[0];SourceGlyphProbe p=fresh();p.build(180,null,false);p.codexSourceLayoutDirty=p.updateLayout=false;Theme.style.id=transition[1];check(p.refreshCodexSource()&&p.codexSourceLayoutDirty&&p.updateLayout,"same-name glyph-only theme change skipped");p.build(180,null,false);check(p.codexSourceGlyphVisible==(transition[1]==1||transition[1]==6),"theme glyph not rebuilt");check(!p.refreshCodexSource(),"unchanged source causes continual rebinding");}
                    for(boolean force:new boolean[]{false,true}){reset();SourceGlyphProbe p=new SourceGlyphProbe();p.useForceThreeLines=force;p.refreshCodexSource();p.build(180,null,false);Theme.style.id=2;p.refreshCodexSource();Theme.style.id=1;p.refreshCodexSource();CodexRuntime.name=null;check(p.refreshCodexSource(),"source removal skipped");p.build(180,null,false);check(p.useForceThreeLines==force&&!p.codexSourceGlyphVisible&&p.codexSourceGlyphTextOffsetX==0&&p.codexSourceName==null,"native force/glyph not restored after theme/removal");}
                    // Original labels are never decorated, including same-text styled originals.
                    for(CharSequence nativeLabel:new CharSequence[]{"草稿","发送者","Desktop",new SpannableStringBuilder("草稿"),new SpannableStringBuilder("Desktop")}){reset();SourceGlyphProbe p=fresh();Object span=new Object();if(nativeLabel instanceof SpannableStringBuilder)((SpannableStringBuilder)nativeLabel).span=span;p.build(180,nativeLabel,false);check(!p.codexSourceGlyphVisible&&p.codexSourceGlyphReserve==0&&p.messageNameLayout.width==180,"native label decorated or width reserved");CharSequence label=p.messageNameLayout.text;if(nativeLabel.toString().equals("Desktop"))check(label==nativeLabel,"equal native label object/spans replaced");else check(label.toString().equals(nativeLabel+" · Desktop"),"native label/source semantic change");if(nativeLabel instanceof SpannableStringBuilder)check(((SpannableStringBuilder)label).span==span,"native spans lost");}
                    // Native RTL compensation and actual paragraph direction are separate inputs.
                    for(float d:new float[]{1f,1.01f,1.5f,1.6f,3f})for(boolean localeRTL:new boolean[]{false,true})for(String source:new String[]{"Desk","حاسوب"}){
                        reset();density=d;CodexRuntime.name=source;SourceGlyphProbe p=fresh();int width=dp(180);p.thumbImage=new ImageReceiver[]{new ImageReceiver(),new ImageReceiver(),new ImageReceiver()};p.build(width,null,localeRTL);
                        int reserve=dp(14);StaticLayout l=p.messageNameLayout;boolean paragraphRTL=l.direction<0;float nativeLeft=dp(16);
                        if(localeRTL&&l.getLineLeft(0)==0)nativeLeft+=width-reserve-Math.ceil(l.textWidth);
                        if(!localeRTL)nativeLeft-=l.getLineLeft(0);
                        near(p.messageNameLeft,nativeLeft,"native RTL compensation did not use exclusive name width");
                        Canvas c=new Canvas();p.draw(c);float textLeft=nativeLeft+l.getLineLeft(0)+(paragraphRTL?0:reserve);
                        float expectedGlyphLeft=paragraphRTL?nativeLeft+l.getLineRight(0)+reserve-dp(10):nativeLeft+l.getLineLeft(0);
                        near(c.textX,textLeft,"actual paragraph text side");near(c.glyphLeft,expectedGlyphLeft,"actual paragraph glyph side");near(c.glyphRight,expectedGlyphLeft+dp(10),"10dp glyph bounds");
                        if(paragraphRTL)near(c.glyphLeft-(c.textX+l.textWidth),reserve-dp(10),"RTL reserved gap");else near(c.textX-c.glyphRight,reserve-dp(10),"LTR reserved gap");
                        check(p.lastSharedWidth==width&&p.messageLeft==dp(16)&&p.typingLeft==dp(16)&&p.codexStatusTop==dp(32),"glyph moved shared/typing/status geometry");
                        for(ImageReceiver image:p.thumbImage)near(image.y,dp(11)+dp(40)+dp(32)-dp(51),"glyph moved native/order thumbnail Y");
                        near(c.glyphTop,p.messageNameTop+l.getLineBaseline(0)+(Theme.dialogs_messageNamePaint.ascent()+Theme.dialogs_messageNamePaint.descent())/2f-dp(10)/2f,"glyph baseline/cache row");
                        int threshold=reserve+dp(12);p.build(threshold-1,null,localeRTL);check(!p.codexSourceGlyphVisible&&p.codexSourceGlyphReserve==0&&p.messageNameLayout.width==threshold-1,"narrow line changed native width");p.build(threshold,null,localeRTL);check(p.codexSourceGlyphVisible&&p.messageNameLayout.width==dp(12),"minimum name width/reservation threshold");
                    }
                    // A long actual name is only visually ellipsized; the complete source remains accessible.
                    reset();CodexRuntime.name="Long real computer name 🙂 preserved without artificial shortening "+"完整电脑名";SourceGlyphProbe longName=fresh();longName.build(80,null,true);check(longName.messageNameLayout.text.equals(CodexRuntime.name),"source truncated before native layout");StringBuilder description=new StringBuilder();longName.appendCodexSourceDescription(description);check(description.toString().contains(CodexRuntime.name),"a11y source truncated");
                    // No native special/empty slot receives glyph or text offset, including recycled cells.
                    for(int special=0;special<10;special++){reset();SourceGlyphProbe p=fresh();p.build(180,null,false);if(special==0)p.tagged=true;if(special==1)p.forum=true;if(special==2)p.isTopic=true;if(special==3)p.isTransitionSupport=true;if(special==4)p.customDialog=new Object();if(special==5)p.isDialogCell=false;if(special==6)p.currentDialogFolderId=1;if(special==7)p.currentDialogCommunityId=1;if(special==8)CodexRuntime.owned=false;if(special==9)StaticLayoutEx.empty=true;p.refreshCodexSource();p.build(180,null,false);check(!p.codexSourceGlyphVisible&&p.codexSourceGlyphTextOffsetX==0,"recycled native special/empty layout retained glyph");}
                    for(String missing:new String[]{null,""," ","\\n\\t"}){reset();SourceGlyphProbe p=fresh();p.build(180,null,false);CodexRuntime.name=missing;p.refreshCodexSource();p.build(180,null,false);check(p.codexSourceName==null&&!p.codexSourceGlyphVisible&&p.codexSourceGlyphReserve==0,"empty source glyph fabricated");}
                    // Draw reads only geometry/Paint caches; native name Paint style is never modified.
                    reset();SourceGlyphProbe p=fresh();p.build(180,null,false);int query=CodexRuntime.queries,themeQuery=Theme.queries,allocation=Paint.allocations;Paint nativePaint=Theme.dialogs_messageNamePaint;Paint.Style originalStyle=nativePaint.style;
                    for(int alpha:new int[]{0,31,173,255}){nativePaint.setColor(0xff13579b+alpha);nativePaint.setAlpha(alpha);Canvas canvas=new Canvas();for(int n=0;n<20;n++)p.draw(canvas);check(canvas.color==nativePaint.getColor()&&canvas.alpha==alpha,"native theme/alpha not propagated");check(nativePaint.style==originalStyle&&p.codexSourceGlyphPaint!=nativePaint,"shared native Paint mutated");}
                    check(CodexRuntime.queries==query&&Theme.queries==themeQuery&&Paint.allocations==allocation,"draw allocated Paint or queried metadata/style");
                    System.out.println("GREEN DialogSourceGlyphTest "+checks+" actual Java8 assertions; Android font/Canvas/TalkBack remain platform boundaries");
                }
            """;
}
