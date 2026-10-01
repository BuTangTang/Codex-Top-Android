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

/** 执行原空消息分支、STATUS 布局门禁与状态布局指纹；文字排版和 View 由最小平台替身提供。 */
public final class DialogCellCodexSubtitleTest {
    /** 提取并运行原分支，分别核对空摘要、STATUS 重绘和状态布局指纹。 */
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
                    + gate + earlyExit + measured + rebuild + "return requestLayout;}"
                    + "String statusKey(){var status=CodexRuntime.status(currentDialogId);String codexStatus=null;"
                    + statusKey + "return codexStatus;}" + SCENARIOS + "}");
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
            long currentDialogId=1;TLRPC.EncryptedChat encryptedChat;Object user;String draftMessage,message;
            Object currentMessagePaint;Parent parentFragment;
            static class Parent {boolean isQuote;}
            boolean showChecks=true,drawTime=true;
            boolean hasCodexStatusAvatar(){return codex&&currentDialogFolderId==0;}
            int getMeasuredWidth(){return 400;}int getMeasuredHeight(){return 72;}
            void invalidate(){invalidates++;}void buildLayout(){builds++;}
            String formatCommunityDialogNames(){return "community";}
            String formatArchivedDialogNames(){return "archive";}
            static String getString(String value){return value;}
            static class MessagesController {static final int UPDATE_MASK_STATUS=1,UPDATE_MASK_AVATAR=2;}
            static class Theme {static Object[] dialogs_messagePrintingPaint={new Object()};}
            static class TLRPC {
                static class EncryptedChat {long admin_id;}
                static class TL_encryptedChatRequested extends EncryptedChat{}static class TL_encryptedChatWaiting extends EncryptedChat{}
                static class TL_encryptedChatDiscarded extends EncryptedChat{}static class TL_encryptedChat extends EncryptedChat{}
            }
            static class UserObject {static String getFirstName(Object user){return "user";}static boolean isUserSelf(Object user){return true;}}
            static class LocaleController {static String formatString(String resource,String name){return resource;}}
            static class UserConfig {static UserConfig getInstance(int account){return new UserConfig();}long getClientUserId(){return 0;}}
            static class DialogsActivity {static final int DIALOGS_TYPE_FORWARD=99;}
            int currentAccount;
        """;

    private static final String SCENARIOS = """
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static JsonObject candidate(String state){return JsonParser.parseString("{\\"details\\":{\\"codexLifecycle\\":{\\"v\\":1,\\"state\\":\\""+state+"\\",\\"eventAtMs\\":1000,\\"checkedAtMs\\":2000}}}").getAsJsonObject();}
            static void reset(){CodexRuntime.store=new SessionStatus.Store();CodexRuntime.now=111;CodexRuntime.store.candidate(1,"machine",candidate("running"),100,110,false);}
            static void emptyAndPriority(){
                reset();DialogSubtitleProbe cell=new DialogSubtitleProbe();
                check("运行中".contentEquals(cell.preview()),"空摘要没有原运行标签");
                cell.draftMessage="synthetic draft";check("synthetic draft".contentEquals(cell.preview()),"草稿被状态覆盖");
                cell.draftMessage=null;cell.draftVoice=true;check("voice draft".contentEquals(cell.preview()),"语音草稿被状态覆盖");
                cell.draftVoice=false;cell.message="synthetic existing preview";
                check("synthetic existing preview".contentEquals(cell.preview()),"实际摘要被状态覆盖");
                cell.message=null;cell.clearingDialog=true;check("HistoryCleared".contentEquals(cell.preview()),"清理历史提示被状态覆盖");
                cell.clearingDialog=false;cell.codex=false;check(cell.preview().length()==0,"普通 Telegram 空消息改变");
                cell.codex=true;cell.currentDialogFolderId=1;check("archive".contentEquals(cell.preview()),"原文件夹摘要改变");
                cell.currentDialogFolderId=0;CodexRuntime.now=15100;
                check("状态已过期".contentEquals(cell.preview()),"过期状态冒充运行");
                reset();CodexRuntime.store.unavailable("machine",120);CodexRuntime.now=121;
                check("连接暂不可用".contentEquals(cell.preview()),"断连标签没有沿用");
                CodexRuntime.store=new SessionStatus.Store();check("同步中".contentEquals(cell.preview()),"缺失事实没有明确显示");
                CodexRuntime.store.candidate(1,"machine",new JsonObject(),100,110,false);
                check("状态未知".contentEquals(cell.preview()),"未知事实没有明确显示");
                CodexRuntime.store=new SessionStatus.Store();CodexRuntime.store.candidate(1,"machine",candidate("running"),100,110,true);
                check("状态未更新".contentEquals(cell.preview()),"磁盘缓存获得当前运行文字");
            }
            static void statusRebuild(){
                reset();DialogSubtitleProbe cell=new DialogSubtitleProbe();
                cell.update(MessagesController.UPDATE_MASK_STATUS);check(cell.builds==1,"STATUS 提前返回，第二行没有重建");
                CodexRuntime.store.candidate(1,"machine",candidate("completed"),120,130,false);CodexRuntime.now=131;
                cell.update(MessagesController.UPDATE_MASK_STATUS);check(cell.builds==2,"运行到完成没有重建");
                check("已完成".contentEquals(cell.preview()),"完成状态仍显示运行");
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
            }
            public static void main(String[] args){
                int failures=0;
                for(String name:new String[]{"emptyAndPriority","statusRebuild","labelFingerprint"}){
                    try{DialogSubtitleProbe.class.getDeclaredMethod(name).invoke(null);System.out.println("PASS "+name);}
                    catch(Exception error){failures++;Throwable cause=error instanceof java.lang.reflect.InvocationTargetException?error.getCause():error;System.out.println("FAIL "+name+": "+cause.getMessage());}
                }
                if(failures>0)throw new AssertionError(failures+" 个原列表副标题场景失败");
            }
        """;
}
