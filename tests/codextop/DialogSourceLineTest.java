package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** Java 8 实际方法专项；字体、省略及 TalkBack 由平台替身提供，不代替 Android 实拍。 */
public final class DialogSourceLineTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        String cell = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"));
        String adapter = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java"));
        String runtime = Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"));
        String theme = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java"));
        Path temp = Files.createTempDirectory("codex-dialog-source-line-");
        try {
            ArrayList<String> files = new ArrayList<>();
            write(temp, files, "CodexRuntime.java", "package com.butang.codextop; import java.util.*; public class CodexRuntime {"
                    + "public static boolean active=true; public static boolean enabled(){return active;}"
                    + "public static final Map<Long,String> remoteIds=new HashMap<>(),dialogMachines=new HashMap<>(),dialogTitles=new HashMap<>(),dialogDirectories=new HashMap<>();"
                    + "public static final Map<String,String> machineNames=new HashMap<>();"
                    + method(runtime, "    public static boolean ownsConversation(long dialogId)")
                    + method(runtime, "    public static final class ConversationInfo")
                    + method(runtime, "    public static ConversationInfo conversationInfo(long dialogId)") + "}");
            write(temp, files, "CodexPixelPalette.java", """
                    package com.butang.codextop;
                    public class CodexPixelPalette {
                        public static final int STYLE_TERMINAL=1,STYLE_EDITORIAL=2,STYLE_INDUSTRIAL=3,STYLE_LIME=4,STYLE_BLUEPRINT=6;
                        public static class Style {public final int id;Style(int id){this.id=id;}}
                        private static final Style[] styles={new Style(0),new Style(1),new Style(2),new Style(3),new Style(4),new Style(5),new Style(6)};
                        public static Style metadata(int id,boolean dark){return styles[id];}
                    }
                    """);
            write(temp, files, "Theme.java", "package org.telegram.ui.ActionBar; public class Theme {"
                    + "public static int codexPixelLightStyle=3,codexPixelDarkStyle=1;public static ThemeInfo currentTheme;"
                    + "public static class ThemeInfo {public boolean dark;public boolean isDark(){return dark;}}"
                    + method(theme, "    public static com.butang.codextop.CodexPixelPalette.Style getCodexPixelStyle()") + "}");
            write(temp, files, "TextUtils.java", """
                    package android.text;
                    public class TextUtils {
                        public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}
                        public static boolean equals(CharSequence a,CharSequence b){return a==b||(a!=null&&b!=null&&a.toString().equals(b.toString()));}
                        public static int getTrimmedLength(CharSequence s){return s.toString().trim().length();}
                    }
                    """);
            write(temp, files, "SpannableStringBuilder.java", """
                    package android.text;
                    public class SpannableStringBuilder implements CharSequence {
                        private final StringBuilder text;
                        public Object mark;
                        public int markStart,markEnd;
                        public SpannableStringBuilder(CharSequence s){text=new StringBuilder(s);if(s instanceof SpannableStringBuilder){SpannableStringBuilder p=(SpannableStringBuilder)s;mark=p.mark;markStart=p.markStart;markEnd=p.markEnd;}}
                        public SpannableStringBuilder append(CharSequence s){text.append(s);return this;}
                        public int length(){return text.length();}public char charAt(int i){return text.charAt(i);}
                        public CharSequence subSequence(int a,int b){return text.subSequence(a,b);}public String toString(){return text.toString();}
                    }
                    """);
            String sourceMethods = method(cell, "    public static String codexSourceName(long dialogId)")
                    + method(cell, "    private boolean refreshCodexSource()")
                    + method(cell, "    private CharSequence codexSourceLabel(CharSequence nativeLabel)")
                    + method(cell, "    private void appendCodexSourceDescription(StringBuilder description)")
                    + method(cell, "    private int getCollapsedHeight()") + method(cell, "    private int computeHeight()")
                    + method(cell, "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)")
                    + method(cell, "    public void updateColors()");
            String updateEntry = between(cell, "        if (refreshCodexSource()) {", "\n        drawAvatarSelector");
            String updateGate = between(cell, "                boolean continueUpdate =", ";") + ";";
            String buildEntry = between(cell, "        final boolean compactCodexLayout =", "\n        // 保留公开原间距");
            String summaryHeight = between(adapter, "                int cellHeight = AndroidUtilities.dp(SharedConfig.useThreeLinesLayout ? 76 : 70);", "\n                if (onlineContacts != null)");
            String diffEntry = between(adapter, "                ItemInternal oldItem = oldItems.get(oldItemPosition), newItem = newItems.get(newItemPosition);", "\n            }\n        };");
            String infoSnapshot = between(adapter, "            if (dialog != null && com.butang.codextop.CodexRuntime.enabled()) {", "\n            if (dialog != null) {");
            String probe = FIXTURE + sourceMethods
                    + "boolean sourceUpdate(int mask){boolean requestLayout=false,rebuildLayout=false,invalidate=false,codexPreviewChanged=false;"
                    + updateEntry + updateGate + "if(!continueUpdate)return false;return requestLayout;}"
                    + "void buildLayout(){" + buildEntry + "builds++;}"
                    + "static class Adapter {ArrayList<ItemInternal> itemInternals=new ArrayList<>(),oldItems=new ArrayList<>(),newItems=new ArrayList<>();boolean isTransitionSupport,collapsedView;"
                    + method(adapter, "    private int getDialogCellHeight(ItemInternal item)")
                    + method(adapter, "    public int getItemHeight(int position)")
                    + method(adapter, "    public int fixScrollGap(")
                    + "int fillerHeight(){int size=itemInternals.size();" + summaryHeight + "return dialogsHeight;}"
                    + "boolean same(int oldItemPosition,int newItemPosition){" + diffEntry + "}"
                    + "static class ItemInternal {TLRPC.Dialog dialog;boolean isForumCell;int viewType=VIEW_TYPE_DIALOG,codexDate;String codexTitle,codexMachineName;"
                    + "ItemInternal(TLRPC.Dialog dialog){this.dialog=dialog;" + infoSnapshot + "}}}"
                    + CASES + "}";
            write(temp, files, "DialogCell.java", probe);
            ArrayList<String> command = new ArrayList<>();
            command.add("--release"); command.add("8"); command.add("-d"); command.add(temp.toString()); command.addAll(files);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, command.toArray(String[]::new)) != 0)
                throw new AssertionError("真实来源/原测量方法 Java8 编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("org.telegram.ui.Cells.DialogCell").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException e) {throw new AssertionError("来源行行为失败", e.getCause());}
            }
        } finally {
            try (var paths = Files.walk(temp)) {for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);}
        }
    }

    private static void write(Path dir, ArrayList<String> files, String name, String value) throws Exception {
        Path file=dir.resolve(name); Files.writeString(file,value); files.add(file.toString());
    }
    private static String between(String source,String start,String end) {
        int first=source.indexOf(start); if(first<0)throw new AssertionError("缺少真实入口 "+start);
        int last=source.indexOf(end,first+start.length());if(last<0)throw new AssertionError("缺少真实边界 "+end);
        return source.substring(first,last);
    }
    private static String method(String source,String signature) {
        int start=source.indexOf(signature);if(start<0)throw new AssertionError("缺少真实方法 "+signature);
        int open=source.indexOf('{',start),depth=0;boolean quoted=false,escaped=false;
        for(int i=open;i<source.length();i++) {char c=source.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"'){quoted=true;continue;}if(c=='{')depth++;if(c=='}'&&--depth==0)return source.substring(start,i+1);
        }throw new AssertionError("未闭合方法 "+signature);
    }

    private static final String FIXTURE = """
            package org.telegram.ui.Cells;
            import java.util.*;
            import android.text.*;
            import org.telegram.ui.ActionBar.Theme;
            import com.butang.codextop.CodexRuntime;
            public class DialogCell {
                float codexSourceGlyphTextOffsetX;
                boolean isDialogCell=true,isTopic,isTransitionSupport,forum,tagged,collapsed,twoLinesForName,useForceThreeLines,useSeparator,codexNativeForceThreeLines,codexSourceLayoutDirty,codexSourceBelow,codexSourceGlyphWanted,codexSourceGlyphVisible,updateLayout,codexCompactLayout;
                Object customDialog,encryptedChat;
                String codexSourceName;
                long currentDialogId=10;
                int currentDialogFolderId,currentDialogCommunityId,heightDefault=70,heightThreeLines=76,addHeightForTags=3,addForumHeightForTags=11,topClip,bottomClip,width,height,requests,invalidations,builds;
                View emojiStatusView,checkBox;
                Helper updateHelper=new Helper();
                static final int VIEW_TYPE_DIALOG=1,VIEW_TYPE_FLICKER=2;
                static class Helper {boolean update(){return false;}}
                static class MessagesController {static final int UPDATE_MASK_STATUS=1;}
                static class SharedConfig {static boolean useThreeLinesLayout;}
                static class AndroidUtilities {static int dp(int x){return x;}}
                static class MeasureSpec {static final int EXACTLY=1;static int makeMeasureSpec(int size,int mode){return size;}static int getSize(int value){return value;}}
                static class View {void measure(int width,int height){}}
                static class RecyclerListView {int padding;int getPaddingTop(){return padding;}}
                static class TLRPC {static class Dialog {long id;int last_message_date;Dialog(long id){this.id=id;}}static class TL_dialogFolder extends Dialog {TL_dialogFolder(){super(10);}}static class TL_dialogCommunity extends Dialog {TL_dialogCommunity(){super(10);}}}
                int dp(int v){return v;}
                boolean isForumCell(){return forum;}boolean hasTags(){return tagged;}
                boolean shouldUseCompactCodexLayout(){return true;}boolean hasCodexStatusAvatar(){return true;}
                void setMeasuredDimension(int w,int h){width=w;height=h;}int getMeasuredHeight(){return height;}
                void checkTwoLinesForName(){}void requestLayout(){requests++;}void invalidate(){invalidations++;}
                static int checks;
                static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
                static void bind(long id,String machine,String name){CodexRuntime.remoteIds.put(id,"remote"+id);CodexRuntime.dialogMachines.put(id,machine);CodexRuntime.machineNames.put(machine,name);}
            """;

    private static final String CASES = """
                public static void main(String[] args) {
                    String full="研发工作站-杭州-";for(int i=0;i<80;i++)full+="超长真名🙂";
                    bind(10,"opaque-machine-id",full);CodexRuntime.dialogTitles.put(10L,"会话");
                    DialogCell cell=new DialogCell();Adapter adapter=new Adapter();adapter.itemInternals.add(new Adapter.ItemInternal(new TLRPC.Dialog(10)));
                    for(int style:new int[]{1,2,3,4,6}) {
                        Theme.codexPixelLightStyle=style;cell.onMeasure(320,1);
                        boolean shown=true; // 新需求：五个已选样式都显示来源，03/06原期待保持。
                        check(cell.height==(shown?77:71),"原measure高度/主题入出");
                        check(adapter.getItemHeight(0)==cell.height,"Adapter/Cell估高不一致");
                        check(shown?full.equals(cell.codexSourceName):cell.codexSourceName==null,"来源theme门");
                        CharSequence label=cell.codexSourceLabel(null);
                        check(shown?full.contentEquals(label):label==null,"真名称被裁短/假造");
                    }
                    Theme.codexPixelLightStyle=3;cell.onMeasure(320,1);cell.buildLayout();int built=cell.builds;
                    check(!cell.sourceUpdate(4),"相同来源状态反复布局");cell.buildLayout();check(cell.builds==built,"未变来源绕过原build门");
                    CodexRuntime.machineNames.put("opaque-machine-id","第二台真实电脑");
                    check(cell.sourceUpdate(4)&&cell.requests==1,"同标题日期元数据变化被原mask早退");cell.buildLayout();
                    check(cell.builds==built+1&&"第二台真实电脑".equals(cell.codexSourceName),"新來源被原helper早退");
                    CharSequence draft=new SpannableStringBuilder("草稿");Object span=new Object();((SpannableStringBuilder)draft).mark=span;((SpannableStringBuilder)draft).markStart=0;((SpannableStringBuilder)draft).markEnd=2;
                    CharSequence joined=cell.codexSourceLabel(draft);
                    check("草稿 · 第二台真实电脑".contentEquals(joined),"草稿标签被覆盖");check(((SpannableStringBuilder)joined).mark==span&&((SpannableStringBuilder)joined).markEnd==2,"原label spans丢失");check("草稿".contentEquals(draft),"原草稿被原地污染");
                    SpannableStringBuilder equalStyled=new SpannableStringBuilder("第二台真实电脑");
                    Object equalSpan=new Object();equalStyled.mark=equalSpan;equalStyled.markStart=0;equalStyled.markEnd=equalStyled.length();
                    CharSequence equalResult=cell.codexSourceLabel(equalStyled);
                    check(equalResult==equalStyled,"equal styled label lost original CharSequence identity/spans");
                    check(((SpannableStringBuilder)equalResult).mark==equalSpan,"equal styled label lost original span");
                    check("You · 第二台真实电脑".contentEquals(cell.codexSourceLabel("You")),"原sender label丢失");
                    check("第二台真实电脑".contentEquals(cell.codexSourceLabel("第二台真实电脑")),"重复来源label");
                    CodexRuntime.machineNames.put("opaque-machine-id",full);cell.sourceUpdate(4);
                    StringBuilder spoken=new StringBuilder("会话. 状态. ");cell.appendCodexSourceDescription(spoken);
                    check(spoken.toString().equals("会话. 状态. 来源电脑："+full+". "),"TalkBack完整真名丢失");
                    cell.tagged=true;cell.onMeasure(320,1);check(cell.height==77,"tags原高度被扩展");spoken.setLength(0);cell.appendCodexSourceDescription(spoken);check(spoken.toString().contains(full),"原tags门丢TalkBack来源");cell.tagged=false;
                    for(String blank:new String[]{"","  ","\\t\\n"}) {
                        CodexRuntime.machineNames.put("opaque-machine-id",blank);cell.onMeasure(320,1);
                        check(cell.codexSourceName==null&&!cell.useForceThreeLines&&cell.height==71&&adapter.getItemHeight(0)==71,"空来源未恢复native");
                        check(cell.codexSourceLabel(draft)==draft,"空來源污染label");spoken.setLength(0);cell.appendCodexSourceDescription(spoken);check(spoken.length()==0,"伪造电脑名或machineId");
                    }
                    CodexRuntime.machineNames.remove("opaque-machine-id");cell.onMeasure(320,1);check(cell.codexSourceName==null,"缺失来源用machineId假造");
                    CodexRuntime.machineNames.put("opaque-machine-id",full);cell.useForceThreeLines=true;cell.onMeasure(320,1);CodexRuntime.machineNames.remove("opaque-machine-id");cell.onMeasure(320,1);check(cell.useForceThreeLines&&cell.height==77,"原constructor强制三行被退出清除");
                    cell.useForceThreeLines=false;SharedConfig.useThreeLinesLayout=true;CodexRuntime.machineNames.put("opaque-machine-id",full);Theme.codexPixelLightStyle=3;cell.onMeasure(320,1);CodexRuntime.machineNames.remove("opaque-machine-id");cell.onMeasure(320,1);check(!cell.useForceThreeLines&&cell.height==77&&adapter.getItemHeight(0)==77,"原全局三行被覆盖");SharedConfig.useThreeLinesLayout=false;
                    CodexRuntime.machineNames.put("opaque-machine-id",full);Theme.codexPixelLightStyle=3;cell.onMeasure(320,1);Theme.codexPixelLightStyle=2;cell.updateColors();cell.onMeasure(320,1);check(full.equals(cell.codexSourceName)&&cell.codexSourceBelow&&cell.height==77&&cell.requests>1&&cell.invalidations>0,"同名主题updateColors未进入下来源排序");
                    CodexRuntime.machineNames.remove("opaque-machine-id");cell.updateColors();cell.onMeasure(320,1);check(cell.codexSourceName==null&&!cell.codexSourceBelow&&cell.height==71,"真实来源删除后未恢复原measure");
                    CodexRuntime.machineNames.put("opaque-machine-id",full);
                    Theme.codexPixelLightStyle=6;cell.updateColors();cell.onMeasure(320,1);check(cell.height==adapter.getItemHeight(0)&&cell.height==77,"主题updateColors未进入3line");
                    cell.currentDialogId=99;cell.onMeasure(320,1);check(cell.codexSourceName==null&&cell.height==71,"复用普通cell残留来源");cell.currentDialogId=10;
                    for(int mode=0;mode<6;mode++) {cell.isDialogCell=mode!=0;cell.customDialog=mode==1?new Object():null;cell.isTopic=mode==2;cell.isTransitionSupport=mode==3;cell.forum=mode==4;cell.currentDialogFolderId=mode==5?1:0;cell.onMeasure(320,1);check(cell.codexSourceName==null,"原特殊owner被套source");}
                    cell.isDialogCell=true;cell.customDialog=null;cell.isTopic=cell.isTransitionSupport=cell.forum=false;cell.currentDialogFolderId=0;cell.onMeasure(320,1);
                    CodexRuntime.active=false;cell.onMeasure(320,1);check(cell.codexSourceName==null&&cell.height==71&&adapter.getItemHeight(0)==71,"非Codex门未恢复");CodexRuntime.active=true;
                    adapter.itemInternals.add(new Adapter.ItemInternal(new TLRPC.Dialog(99)));check(adapter.fillerHeight()==147,"混合列表filler原高度不一致");
                    RecyclerListView list=new RecyclerListView();list.padding=200;check(adapter.fixScrollGap(list,2,300,false,false,false,false)==148,"来源height未用于scroll gap");
                    CodexRuntime.machineNames.remove("opaque-machine-id");Theme.codexPixelLightStyle=2;check(adapter.fixScrollGap(list,2,300,false,false,false,false)==142,"来源删除后旧gap变化");CodexRuntime.machineNames.put("opaque-machine-id",full);Theme.codexPixelLightStyle=6;
                    Adapter special=new Adapter();Adapter.ItemInternal folder=new Adapter.ItemInternal(new TLRPC.TL_dialogFolder());special.itemInternals.add(folder);check(special.getItemHeight(0)==71,"归档原估高变化");special.itemInternals.set(0,new Adapter.ItemInternal(new TLRPC.TL_dialogCommunity()));check(special.getItemHeight(0)==71,"社区原估高变化");
                    special.itemInternals.set(0,new Adapter.ItemInternal(new TLRPC.Dialog(10)));special.itemInternals.get(0).isForumCell=true;check(special.getItemHeight(0)==92,"论坛原估高变化");special.collapsedView=true;check(special.getItemHeight(0)==71,"折叠论坛原估高变化");special.itemInternals.get(0).isForumCell=false;special.isTransitionSupport=true;check(special.getItemHeight(0)==71,"过渡cell原估高变化");
                    Adapter diff=new Adapter();TLRPC.Dialog reused=new TLRPC.Dialog(10);diff.oldItems.add(new Adapter.ItemInternal(reused));diff.newItems.add(new Adapter.ItemInternal(reused));check(diff.same(0,0),"相同快照误重绑");CodexRuntime.machineNames.put("opaque-machine-id","已改真名");diff.newItems.set(0,new Adapter.ItemInternal(reused));check(!diff.same(0,0),"可变Dialog同标题日期來源改名未重绑");
                    System.out.println("DialogSourceLineTest: "+checks+" actual Java8 method checks PASS");
                }
            """;
}
