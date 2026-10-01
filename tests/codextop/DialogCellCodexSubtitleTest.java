package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import javax.tools.ToolProvider;

/** 执行原摘要、右侧状态列及消息重读门禁；Android 排版与 View 由最小平台替身提供。 */
public final class DialogCellCodexSubtitleTest {
    /** 提取原分支与方法，核对真实摘要优先级、状态列边界、消息重绘和原多选路径。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path sourcePath = Path.of(args.length == 0
                ? "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java" : args[0]);
        String source = Files.readString(sourcePath);
        // 只解析相关原分支，避免在32 MiB下为整个大型Android View建立AST。
        String emptySource = between(source, "                    } else if (message == null) {",
                "\n                    } else {\n                        String restrictionReason");
        IfStmt empty = StaticJavaParser.parseStatement(emptySource.substring(emptySource.indexOf("if ("))
                + "} else {messageString = message;}").asIfStmt();
        String clearing = between(source, "                    if (clearingDialog) {",
                "\n                    } else if (message == null) {") + "} else " + empty;
        String draftCondition = between(source, "                if (draftVoice || draftMessage != null)", " {").trim();
        // 草稿和已有摘要的格式生产者由替身返回；优先级条件与空消息/清理历史分支执行产品原代码。
        IfStmt preview = StaticJavaParser.parseStatement(draftCondition
                + "{messageString = draftVoice ? \"voice draft\" : draftMessage;} else {" + clearing + "}").asIfStmt();
        var masked = StaticJavaParser.parseStatement(between(source, "            if (mask != 0) {",
                "\n                if (user != null") + "}").asIfStmt();
        Statement gate = masked.getThenStmt().asBlockStmt().getStatements().stream()
                .filter(value -> value.findAll(VariableDeclarationExpr.class).stream()
                        .anyMatch(declaration -> declaration.getVariables().stream()
                                .anyMatch(variable -> variable.getNameAsString().equals("continueUpdate"))))
                .findFirst().orElseThrow();
        IfStmt earlyExit = StaticJavaParser.parseStatement(between(source, "                if (!continueUpdate) {\n                    //if",
                "\n            }\n\n            user = null;")).asIfStmt();
        IfStmt measured = StaticJavaParser.parseStatement(between(source,
                "        if (!isTopic && (getMeasuredWidth() != 0 || getMeasuredHeight() != 0)) {",
                "\n        if (!invalidate)")).asIfStmt();
        IfStmt rebuild = StaticJavaParser.parseStatement(between(source, "        if (rebuildLayout) {",
                "\n        updatePremiumBlocked")).asIfStmt();
        var statusKey = StaticJavaParser.parseStatement(between(source, "                codexStatus = status.validity", "\n").trim());
        var statusLabel = StaticJavaParser.parseStatement(between(source, "        codexStatusLabel = hasCodexStatusAvatar()",
                ";") + ";");
        var previewChanged = StaticJavaParser.parseStatement(between(source,
                "        boolean codexPreviewChanged = updateCodexPreview(mask);", "\n").trim());
        var nativeTextGate = StaticJavaParser.parseStatement(between(source,
                "                if (!continueUpdate && (mask & MessagesController.UPDATE_MASK_MESSAGE_TEXT) != 0) {",
                "\n                if (!continueUpdate && (mask & MessagesController.UPDATE_MASK_CHAT)").trim());
        var compactMode = StaticJavaParser.parseMethodDeclaration(between(source,
                "    private boolean shouldUseCompactCodexLayout() {", "\n    /** Codex 浏览时"));
        var contentPadding = StaticJavaParser.parseMethodDeclaration(between(source,
                "    private int getContentPaddingStart() {", "\n    /** 状态列最多"));
        var statusWidth = StaticJavaParser.parseMethodDeclaration(between(source,
                "    private int codexStatusColumnWidth(int availableWidth, int desiredWidth) {", "\n    /** 只消费已有真实"));
        var previewUpdate = StaticJavaParser.parseMethodDeclaration(between(source,
                "    private boolean updateCodexPreview(int mask) {", "\n    /** 只画 14dp 蓝环"));
        String statusColumnSource = between(source,
                "        // 原计数、失败及标记槽已从 messageWidth 扣除，状态只占剩余文字区域，不能盖住它们。",
                "\n        if (checkMessage)");
        var statusColumn = StaticJavaParser.parseStatement(statusColumnSource.substring(statusColumnSource.indexOf("        if (")).trim());
        var avatarHit = StaticJavaParser.parseMethodDeclaration(between(source,
                "    public boolean isPointInsideAvatar(float x, float y) {", "\n    public void setDialogSelected"));
        String buildPrefix = between(source, "        final boolean compactCodexLayout = shouldUseCompactCodexLayout();",
                "\n        // 保留公开原间距");
        var drawModeGate = StaticJavaParser.parseStatement(between(source,
                "        if (hasCodexStatusAvatar() && (codexCompactLayout != shouldUseCompactCodexLayout()",
                "\n\n        boolean needInvalidate").trim());

        LinkedHashSet<String> resources = new LinkedHashSet<>();
        preview.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                .filter(value -> value.getScope().toString().equals("R.string"))
                .forEach(value -> resources.add(value.getNameAsString()));
        StringBuilder resourceStub = new StringBuilder("static class R {static class string {");
        for (String resource : resources) resourceStub.append("static final String ").append(resource)
                .append("=\"").append(resource).append("\";");
        resourceStub.append("}}");
        Path temp = Files.createTempDirectory("codex-dialog-subtitle-");
        try {
            Path probe = temp.resolve("DialogSubtitleProbe.java");
            Files.writeString(probe, FIXTURE + resourceStub
                    + "CharSequence preview(){CharSequence messageString=\"\";" + preview + "return messageString;}"
                    + "boolean update(int mask){boolean requestLayout=false,rebuildLayout=false;"
                    + previewChanged + gate + nativeTextGate + earlyExit + measured + rebuild + "return requestLayout;}"
                    + "String statusKey(){var status=CodexRuntime.status(currentDialogId);String codexStatus=null;"
                    + statusKey + "return codexStatus;}"
                    + compactMode + contentPadding + avatarHit + statusWidth + previewUpdate
                    + "void buildLayout(){" + buildPrefix + statusLabel + "lastMessageString=message!=null?message.messageText:null;builds++;}"
                    + "String rightStatus(){" + statusLabel + "return codexStatusLabel;}"
                    + "void allocateStatus(){" + statusLabel + "codexStatusLayout=null;" + statusColumn + "}"
                    + "void drawModeProbe(){" + drawModeGate + "}" + SCENARIOS + "}");
            Path runtime = temp.resolve("CodexRuntime.java");
            Files.writeString(runtime, "package com.butang.codextop; public final class CodexRuntime {"
                    + "public static SessionStatus.Store store=new SessionStatus.Store();public static long now;"
                    + "public static SessionStatus.Snapshot status(long id){return store.get(id,now);}}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", temp.toString(), probe.toString(), runtime.toString(),
                    "TMessagesProj/src/main/java/com/butang/codextop/SessionStatus.java") != 0)
                throw new AssertionError("原列表分支夹具编译失败");
            ArrayList<URL> classpath = new ArrayList<>();
            classpath.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                classpath.add(Path.of(entry).toUri().toURL());
            // Probe 与实际状态模型共用子加载器，兼容主管已加载产品模型的共同 classpath。
            try (var loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.DialogSubtitleProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("原列表副标题回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 提取已知代码边界；原结构改变时明确失败，避免测试静默漏验。 */
    private static String between(String source, String start, String end) {
        int first = source.indexOf(start), last = first < 0 ? -1 : source.indexOf(end, first + start.length());
        if (first < 0 || last < 0) throw new AssertionError("原列表分支提取边界改变: " + start.trim());
        return source.substring(first, last);
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;
        import java.util.*;
        public final class DialogSubtitleProbe {
            boolean codex=true,draftVoice,clearingDialog,isTopic,attachedToWindow=true,updateLayout;
            int currentDialogCommunityId,currentDialogFolderId,dialogsType,paintIndex,builds,invalidates;
            long currentDialogId=1;TLRPC.EncryptedChat encryptedChat;Object user;String draftMessage;
            MessageObject message;ArrayList<MessageObject> groupMessages;CharSequence lastMessageString;
            int currentEditDate,lastSendState;boolean lastUnreadState;
            Object currentMessagePaint;Parent parentFragment;
            boolean isDialogCell,codexCompactLayout,useForceThreeLines;
            int messagePaddingStart=72;CheckBox checkBox;UpdateHelper updateHelper=new UpdateHelper();
            String codexStatusLabel;StaticLayout codexStatusLayout;
            int messageWidth,messageLeft,typingLeft,messageNameLeft,buttonLeft,codexStatusLeft,codexStatusRingLeft;
            int thumbsCount,thumbSize=19;String messageNameString;boolean tags;
            static class UpdateHelper {boolean changed;boolean update(){boolean result=changed;changed=false;return result;}}
            static class CheckBox {boolean checked;float progress;boolean isChecked(){return checked;}float getProgress(){return progress;}}
            static class Parent {boolean isQuote;ActionBar actionBar=new ActionBar();ActionBar getActionBar(){return actionBar;}}
            static class ActionBar {boolean selection;boolean isActionModeShowed(){return selection;}}
            static class SharedConfig {static boolean useThreeLinesLayout;}
            boolean showChecks=true,drawTime=true;
            boolean hasCodexStatusAvatar(){return codex&&currentDialogFolderId==0;}
            int getMeasuredWidth(){return 400;}int getMeasuredHeight(){return 72;}
            int dp(int value){return value;}void invalidate(){invalidates++;}
            boolean hasTags(){return tags;}boolean isForumCell(){return false;}
            TextPaint getTimeTextPaint(){return new TextPaint();}
            static class TextPaint {TextPaint(){}TextPaint(TextPaint paint){}float measureText(String text){return text.length()*7f;}}
            static class Layout {enum Alignment {ALIGN_NORMAL}}
            static class StaticLayout {
                final CharSequence text;final int width;
                StaticLayout(CharSequence text,TextPaint paint,int width,Layout.Alignment alignment,float spacing,float padding,boolean includePadding){this.text=text;this.width=width;}
            }
            static class TextUtils {
                enum TruncateAt {END}
                static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a==null?null:a.toString(),b==null?null:b.toString());}
                static CharSequence ellipsize(CharSequence text,TextPaint paint,int width,TruncateAt at){int count=Math.min(text.length(),width/7);return text.subSequence(0,count);}
            }
            static class MessageObject implements CharSequence {
                String messageText;boolean unread;TLRPC.Message messageOwner=new TLRPC.Message();
                MessageObject(String text){messageText=text;}
                boolean isUnread(){return unread;}public int length(){return messageText.length();}
                public char charAt(int index){return messageText.charAt(index);}
                public CharSequence subSequence(int start,int end){return messageText.subSequence(start,end);}
                public String toString(){return messageText;}
            }
            String formatCommunityDialogNames(){return "community";}
            String formatArchivedDialogNames(){return "archive";}
            static String getString(String value){return value;}
            static class MessagesController {
                static final int UPDATE_MASK_STATUS=1,UPDATE_MASK_AVATAR=2,UPDATE_MASK_MESSAGE_TEXT=4;
                static final MessagesController instance=new MessagesController();
                final Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();
                static MessagesController getInstance(int account){return instance;}
            }
            static class Theme {static Object[] dialogs_messagePrintingPaint={new Object()};}
            static class TLRPC {
                static class Message {int edit_date,send_state;}
                static class EncryptedChat {long admin_id;}
                static class TL_encryptedChatRequested extends EncryptedChat{}static class TL_encryptedChatWaiting extends EncryptedChat{}
                static class TL_encryptedChatDiscarded extends EncryptedChat{}static class TL_encryptedChat extends EncryptedChat{}
            }
            static class UserObject {static String getFirstName(Object user){return "user";}static boolean isUserSelf(Object user){return true;}}
            static class LocaleController {static boolean isRTL;static String formatString(String resource,String name){return resource;}}
            static class UserConfig {static UserConfig getInstance(int account){return new UserConfig();}long getClientUserId(){return 0;}}
            static class DialogsActivity {static final int DIALOGS_TYPE_FORWARD=99;}
            int currentAccount;
        """;

    private static final String SCENARIOS = """
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static JsonObject candidate(String state){return JsonParser.parseString("{\\"details\\":{\\"codexLifecycle\\":{\\"v\\":1,\\"state\\":\\""+state+"\\",\\"eventAtMs\\":1000,\\"checkedAtMs\\":2000}}}").getAsJsonObject();}
            static void reset(){CodexRuntime.store=new SessionStatus.Store();CodexRuntime.now=111;CodexRuntime.store.candidate(1,"machine",candidate("running"),100,110,false);MessagesController.instance.dialogMessage.clear();SharedConfig.useThreeLinesLayout=false;LocaleController.isRTL=false;}
            static void emptyAndPriority(){
                reset();DialogSubtitleProbe cell=new DialogSubtitleProbe();
                check(cell.preview().length()==0,"空摘要用状态冒充了最近真实消息");
                check("运行中".equals(cell.rightStatus()),"右侧没有当前运行标签");
                cell.draftMessage="synthetic draft";check("synthetic draft".contentEquals(cell.preview()),"草稿被状态覆盖");
                cell.draftMessage=null;cell.draftVoice=true;check("voice draft".contentEquals(cell.preview()),"语音草稿被状态覆盖");
                cell.draftVoice=false;cell.message=new MessageObject("synthetic existing preview");
                check("synthetic existing preview".contentEquals(cell.preview()),"实际摘要被状态覆盖");
                cell.draftMessage="new synthetic draft";
                check("new synthetic draft".contentEquals(cell.preview()),"已有真实摘要抢占原草稿优先级");
                cell.draftMessage=null;
                cell.message=null;cell.clearingDialog=true;check("HistoryCleared".contentEquals(cell.preview()),"清理历史提示被状态覆盖");
                cell.clearingDialog=false;cell.codex=false;check(cell.preview().length()==0,"普通 Telegram 空消息改变");
                check(cell.rightStatus()==null,"普通 Telegram 行新增了 Codex 状态");
                cell.codex=true;cell.currentDialogFolderId=1;check("archive".contentEquals(cell.preview()),"原文件夹摘要改变");
                cell.currentDialogFolderId=0;CodexRuntime.now=15100;
                check("上次：运行中 · 状态已过期".equals(cell.rightStatus()),"右侧过期状态未保留上次事实或冒充当前运行");
                reset();CodexRuntime.store.unavailable("machine",120);CodexRuntime.now=121;
                check("上次：运行中 · 连接暂不可用".equals(cell.rightStatus()),"右侧断连标签未保留上次事实与不可用说明");
                CodexRuntime.store=new SessionStatus.Store();check("同步中".equals(cell.rightStatus()),"右侧缺失事实没有明确显示");
                CodexRuntime.store.candidate(1,"machine",new JsonObject(),100,110,false);
                check("状态未知".equals(cell.rightStatus()),"右侧未知事实没有明确显示");
                CodexRuntime.store=new SessionStatus.Store();CodexRuntime.store.candidate(1,"machine",candidate("running"),100,110,true);
                check("上次：运行中 · 状态未更新".equals(cell.rightStatus()),"右侧磁盘缓存未标注上次事实或获得当前运行文字");
                check(cell.preview().length()==0,"无真实消息时摘要仍出现状态文字");
            }
            static void statusRebuild(){
                reset();DialogSubtitleProbe cell=new DialogSubtitleProbe();
                cell.update(MessagesController.UPDATE_MASK_STATUS);check(cell.builds==1,"STATUS 提前返回，右侧没有重建");
                CodexRuntime.store.candidate(1,"machine",candidate("completed"),120,130,false);CodexRuntime.now=131;
                cell.update(MessagesController.UPDATE_MASK_STATUS);check(cell.builds==2,"运行到完成没有重建");
                check("已完成".equals(cell.rightStatus())&&cell.preview().length()==0,"完成状态未在右侧显示或污染了摘要");
                cell.update(MessagesController.UPDATE_MASK_AVATAR);check(cell.builds==2,"不相关 mask 无故重建");
                cell.codex=false;cell.update(MessagesController.UPDATE_MASK_STATUS);check(cell.builds==2,"普通 Telegram STATUS 改变");
                cell.codex=true;cell.attachedToWindow=false;cell.update(MessagesController.UPDATE_MASK_STATUS);
                check(cell.builds==2&&cell.updateLayout,"离屏列表没有沿用原延迟布局");
            }
            static void labelFingerprint(){
                reset();DialogSubtitleProbe cell=new DialogSubtitleProbe();
                CodexRuntime.store.listFailed("machine",120);CodexRuntime.now=121;String failed=cell.statusKey();
                CodexRuntime.store.unavailable("machine",130);CodexRuntime.now=131;String offline=cell.statusKey();
                check(!failed.equals(offline),"同状态类型的标签变化被布局指纹遗漏");
                reset();cell=new DialogSubtitleProbe();cell.isDialogCell=true;cell.buildLayout();
                // 原更新助手的指纹已由上方原 statusKey 验证，此处只模拟它确有变化。
                CodexRuntime.now=15100;cell.updateHelper.changed=true;cell.drawModeProbe();
                check(cell.builds==2&&"上次：运行中 · 状态已过期".equals(cell.codexStatusLabel),"运行失效后的重绘没有更新右侧语义");
            }
            static void realMessageMapping(){
                reset();DialogSubtitleProbe cell=new DialogSubtitleProbe();cell.isDialogCell=true;
                cell.buildLayout();MessageObject first=new MessageObject("synthetic latest message");
                first.unread=true;first.messageOwner.edit_date=17;first.messageOwner.send_state=1;
                MessagesController.instance.dialogMessage.put(1L,new ArrayList<>(List.of(first)));
                cell.updateHelper.changed=true;cell.update(MessagesController.UPDATE_MASK_MESSAGE_TEXT);
                check(cell.message==first&&cell.builds==2,"首个已发布真实摘要被日期或空摘要门禁丢弃");
                check(cell.currentEditDate==17&&cell.lastUnreadState&&cell.lastSendState==1,"真实摘要的原发送/已读/编辑语义未同步");
                check("synthetic latest message".contentEquals(cell.preview()),"摘要没有消费发布的真实正文");
                MessageObject second=new MessageObject("synthetic same-second next message");
                MessagesController.instance.dialogMessage.put(1L,new ArrayList<>(List.of(second)));
                cell.updateHelper.changed=true;cell.update(MessagesController.UPDATE_MASK_MESSAGE_TEXT);
                check(cell.message==second&&cell.builds==3,"同一秒的新消息被旧摘要门禁遗漏");
                cell.update(MessagesController.UPDATE_MASK_MESSAGE_TEXT);check(cell.builds==3,"同一真实对象的重复事件无故重建");
                second.unread=true;second.messageOwner.edit_date=23;second.messageOwner.send_state=2;
                check(!cell.updateCodexPreview(MessagesController.UPDATE_MASK_MESSAGE_TEXT)
                        &&!cell.lastUnreadState&&cell.currentEditDate==0&&cell.lastSendState==0,
                        "同对象的原已读/发送比较依据被提前覆盖");
                second.messageText="synthetic edited text";cell.updateHelper.changed=true;
                cell.update(MessagesController.UPDATE_MASK_MESSAGE_TEXT);check(cell.builds==4,"原 MESSAGE_TEXT 的同对象编辑门禁丢失");
                MessagesController.instance.dialogMessage.remove(1L);cell.updateHelper.changed=true;
                cell.update(MessagesController.UPDATE_MASK_MESSAGE_TEXT);check(cell.message==null&&cell.builds==5,"已删除的真实摘要仍残留");
                check(cell.preview().length()==0,"删除真实摘要后用状态补了假摘要");
                for(int mask:new int[]{0,MessagesController.UPDATE_MASK_STATUS,MessagesController.UPDATE_MASK_AVATAR}){
                    MessagesController.instance.dialogMessage.put(1L,new ArrayList<>(List.of(first)));
                    check(!cell.updateCodexPreview(mask)&&cell.message==null,"非正文事件意外重读消息映射");
                }
                cell.codex=false;check(!cell.updateCodexPreview(MessagesController.UPDATE_MASK_MESSAGE_TEXT),"普通 Telegram 摘要映射改变");
                cell.codex=true;cell.currentDialogFolderId=1;check(!cell.updateCodexPreview(MessagesController.UPDATE_MASK_MESSAGE_TEXT),"归档摘要映射改变");
                cell.currentDialogFolderId=0;cell.isDialogCell=false;check(!cell.updateCodexPreview(MessagesController.UPDATE_MASK_MESSAGE_TEXT),"非列表行摘要映射改变");
            }
            // 执行原状态列分配，检验已有未读预留、缩略图回扩和 RTL 方向下的文字边界。
            static void statusColumnBoundary(){
                reset();
                for(boolean rtl:new boolean[]{false,true})for(boolean three:new boolean[]{false,true})
                for(int thumbs:new int[]{0,1,3})for(int available:new int[]{24,80,160,320})for(int reserved:new int[]{0,29,70}){
                    DialogSubtitleProbe cell=new DialogSubtitleProbe();LocaleController.isRTL=rtl;SharedConfig.useThreeLinesLayout=three;
                    cell.thumbsCount=thumbs;cell.messageNameString=three?"synthetic sender":null;
                    int start=16+(rtl?reserved:0);cell.messageWidth=available;cell.messageLeft=cell.typingLeft=cell.messageNameLeft=cell.buttonLeft=start;
                    cell.allocateStatus();
                    if(cell.codexStatusLayout==null){check(cell.messageWidth==available&&cell.messageLeft==start,"无可用状态列时破坏了原消息区");continue;}
                    int statusStart=rtl?cell.codexStatusLeft:cell.codexStatusRingLeft;
                    int statusEnd=cell.codexStatusLeft+cell.codexStatusLayout.width+(rtl?20:0);
                    check(statusStart>=start&&statusEnd<=start+available,"状态覆盖原未读/失败预留边界");
                    check(cell.messageWidth>=12,"状态挤没了原最小消息宽度");
                    int expansion=thumbs>0?(!three?(thumbs*(cell.thumbSize+2)-2+5):5):0;
                    if(rtl){int nativeLeft=cell.messageLeft-(!three?expansion:0);check(nativeLeft>=statusEnd+8,"RTL 缩略图左移覆盖状态");}
                    else {int nativeRight=cell.messageLeft+cell.messageWidth+expansion-12;check(nativeRight<=statusStart-8,"缩略图回扩覆盖右侧状态");}
                }
                LocaleController.isRTL=false;SharedConfig.useThreeLinesLayout=false;
                DialogSubtitleProbe cell=new DialogSubtitleProbe();cell.messageWidth=320;cell.messageLeft=16;
                CodexRuntime.now=15100;cell.allocateStatus();
                check(cell.codexStatusLayout.text.toString().startsWith("上次："),"右侧省略先删除了过期前缀");
                int staleWidth=cell.codexStatusLayout.width;cell.codex=false;cell.allocateStatus();
                check(cell.codexStatusLayout==null&&staleWidth>0,"普通 Telegram 行仍分配 Codex 状态列");
            }
            // 执行原多选布局门禁和左右头像命中，未运行 Android 实际绘制。
            static void selectionAndAvatarHit(){
                reset();
                DialogSubtitleProbe cell=new DialogSubtitleProbe();cell.isDialogCell=true;
                cell.buildLayout();check(cell.builds==1&&cell.codexCompactLayout,"Codex 浏览没有回收头像槽");
                cell.drawModeProbe();check(cell.builds==1,"未切换模式却重复重建");
                cell.parentFragment=new Parent();cell.parentFragment.actionBar.selection=true;
                cell.drawModeProbe();check(cell.builds==2&&!cell.codexCompactLayout,"进入原生多选未重建");
                check(cell.getContentPaddingStart()==cell.messagePaddingStart,"多选没有保留原勾选槽");
                cell.parentFragment.actionBar.selection=false;cell.checkBox=new CheckBox();cell.checkBox.checked=true;
                cell.drawModeProbe();check(cell.builds==2,"原勾选仍显示时提前回收了槽位");
                cell.checkBox.checked=false;cell.checkBox.progress=.5f;
                cell.drawModeProbe();check(cell.builds==2,"勾选退场未结束就回收，文字会覆盖勾选框");
                cell.checkBox.progress=0;cell.drawModeProbe();
                check(cell.builds==3&&cell.codexCompactLayout,"取消多选后未重建紧凑布局");
                for(boolean rtl:new boolean[]{false,true}){
                    LocaleController.isRTL=rtl;check(!cell.isPointInsideAvatar(rtl?390:10,35),"无头像文字触发了头像预览");
                    cell.codex=false;check(cell.getContentPaddingStart()==cell.messagePaddingStart,"普通 Telegram 行间距改变");
                    check(cell.isPointInsideAvatar(rtl?390:10,35),"普通 Telegram 头像热点丢失");
                    check(!cell.isPointInsideAvatar(rtl?10:390,35),"普通 Telegram 头像热点方向改变");cell.codex=true;
                }
                LocaleController.isRTL=false;
            }
            public static void main(String[] args){
                int failures=0;
                for(String name:new String[]{"emptyAndPriority","statusRebuild","labelFingerprint","realMessageMapping","statusColumnBoundary","selectionAndAvatarHit"}){
                    try{DialogSubtitleProbe.class.getDeclaredMethod(name).invoke(null);System.out.println("PASS "+name);}
                    catch(Exception error){failures++;Throwable cause=error instanceof java.lang.reflect.InvocationTargetException?error.getCause():error;System.out.println("FAIL "+name+": "+cause.getMessage());}
                }
                if(failures>0)throw new AssertionError(failures+" 个原列表副标题场景失败");
            }
        """;
}
