package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实摘要发布和附件归属方法；只合成 Android 构造、通知及目录边界，不依赖旧摘要夹具。 */
public final class RuntimePreviewLayoutTest {
    /** 默认读取仓库 owner，也可用冻结旧 Runtime 在同一业务断言上复现 RED。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var owner = StaticJavaParser.parse(args.length == 0 ? source.resolve("CodexRuntime.java") : Path.of(args[0]))
                .getClassByName("CodexRuntime").orElseThrow();
        var names = Set.of("historyObject", "publishDialogPreview", "sameDialogPreview", "previewCurrent",
                "isAccountCurrent", "ownsConversation", "isAttachmentMessage", "dialogConnection", "dialogMachine",
                "attachment", "attachmentDirectory", "cachedAttachment", "attachmentFile");
        StringBuilder methods = new StringBuilder();
        Set<String> found = new java.util.HashSet<>();
        for (MethodDeclaration method : owner.getMethods()) if (names.contains(method.getNameAsString())) {
            methods.append(method).append('\n');
            found.add(method.getNameAsString());
        }
        if (!found.equals(names)) throw new AssertionError("missing actual preview owner");
        var digest = StaticJavaParser.parse(source.resolve("TranscriptStore.java")).getClassByName("TranscriptStore")
                .orElseThrow().getMethodsByName("digest").get(0);
        methods.append("static class TranscriptStore {\n").append(digest).append("\n}\n");
        Path temporary = Files.createTempDirectory("runtime-preview-layout-");
        try {
            Path probe = temporary.resolve("RuntimePreviewLayoutProbe.java");
            Files.writeString(probe, FIXTURE + methods + CASES + "\n}");
            Path account = temporary.resolve("UserConfig.java");
            Files.writeString(account, "package org.telegram.messenger;public class UserConfig {public static int selectedAccount;}");
            var compile = new ArrayList<String>(List.of("--release", "8", "-cp", System.getProperty("java.class.path"),
                    "-d", temporary.toString(), probe.toString(), account.toString(),
                    source.resolve("DesktopAttachment.java").toString(), source.resolve("AttachmentFiles.java").toString()));
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(new String[0])) != 0)
                throw new AssertionError("actual preview owner compile failed");
            var urls = new ArrayList<URL>();
            urls.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                urls.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("com.butang.codextop.RuntimePreviewLayoutProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{temporary.resolve("files").toString()});
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static final String FIXTURE = """
            package com.butang.codextop;
            import java.io.*;
            import java.util.*;
            import java.nio.charset.StandardCharsets;
            import java.security.MessageDigest;
            import com.google.gson.*;
            public final class RuntimePreviewLayoutProbe {
                static PasswordLogin.Session session = new PasswordLogin.Session();
                static long accountGeneration = 7;
                static boolean loggingOut;
                static final Map<Long,String> remoteIds = new HashMap<>(), dialogMachines = new HashMap<>();
                static final Map<String,DesktopConnection> desktopConnections = new HashMap<>();
                static final Map<String,MessageObject> pendingMessages = new HashMap<>();
                static class PasswordLogin { static class Session { String server="synthetic-server", accountId="synthetic-account"; } }
                static class DesktopConnection { final String machineId; DesktopConnection(String id){machineId=id;} }
                static class TLRPC {
                    static class Message { int id,date,send_state; long dialog_id; boolean out; String message,attachPath; Map<String,String> params=new HashMap<>(); }
                    static class TL_message extends Message {}
                }
                /** Android 构造叶仅记录原参数和调用次数，不模拟排版或 emoji 像素。 */
                static class MessageObject {
                    static int constructions;
                    final TLRPC.Message messageOwner; final boolean layoutCreated;
                    boolean attachPathExists,mediaExists;
                    MessageObject(int account,TLRPC.Message message,boolean layout,boolean media){constructions++;messageOwner=message;layoutCreated=layout;}
                    long getDialogId(){return messageOwner.dialog_id;}
                }
                static class MessagesController {
                    static final int UPDATE_MASK_MESSAGE_TEXT=4;
                    static final MessagesController instance=new MessagesController();
                    final Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();
                    static MessagesController getInstance(int account){return instance;}
                }
                static class NotificationCenter {
                    static final int updateInterfaces=1; static final NotificationCenter instance=new NotificationCenter();
                    int notices;
                    static NotificationCenter getInstance(int account){return instance;}
                    void postNotificationName(int name,int mask){if(name!=updateInterfaces||mask!=4)throw new AssertionError("unexpected notification");notices++;}
                }
                /** Android 私有目录边界指向本次合成临时目录；摘要、归属和文件检查使用原实现。 */
                static class ApplicationLoader {
                    static final Context applicationContext=new Context();
                    static class Context { File root; File getExternalFilesDir(String kind){return root;} File getNoBackupFilesDir(){return root;} }
                }
            """;

    private static final String CASES = """
                static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
                static TLRPC.TL_message message(long id,int number,String body){TLRPC.TL_message message=new TLRPC.TL_message();message.dialog_id=id;message.id=number;message.date=number;message.message=body;message.params.put("codexSourceId","synthetic-"+number);return message;}
                static MessageObject visible(long id){ArrayList<MessageObject> rows=MessagesController.instance.dialogMessage.get(id);return rows==null?null:rows.get(0);}
                static void publish(long id,TLRPC.TL_message message,MessageObject pending){publishDialogPreview(0,accountGeneration,id,"remote","machine",desktopConnections.get("machine"),message,pending,Collections.emptySet());}
                /** 每个场景只重置合成界面映射，原 owner 方法和缓存归属保持不变。 */
                static void reset(){MessagesController.instance.dialogMessage.clear();pendingMessages.clear();MessageObject.constructions=0;NotificationCenter.instance.notices=0;loggingOut=false;org.telegram.messenger.UserConfig.selectedAccount=0;}
                /** 摘要不预建聊天布局；聊天仍完整构造，原正文、身份及同摘要复用保持。 */
                static void textCase(String body){
                    reset();TLRPC.TL_message message=message(1,10,body);publish(1,message,null);
                    MessageObject preview=visible(1);check(preview!=null&&!preview.layoutCreated,"preview generated chat layout");
                    MessageObject chat=historyObject(0,message);check(chat.layoutCreated,"chat omitted layout");
                    check(preview.messageOwner==message&&chat.messageOwner==message&&body.equals(message.message),"changed message/text identity");
                    int count=MessageObject.constructions;publish(1,message,null);check(visible(1)==preview&&MessageObject.constructions==count,"rebuilt identical preview");
                }
                /** 已有待发直接复用原完整对象；晚到的未回显历史不能替换它。 */
                static void pendingCase(){
                    reset();TLRPC.TL_message message=message(1,-5,"pending 😀");message.params.put("codexLocalId","pending");
                    MessageObject pending=historyObject(0,message);pendingMessages.put("pending",pending);int count=MessageObject.constructions;
                    publish(1,message,pending);publish(1,message(1,20,"late history"),null);
                    check(visible(1)==pending&&pending.layoutCreated&&MessageObject.constructions==count,"pending object replaced");
                }
                /** 使用真实附件模型、目标路径和文件检查验证就绪，不把不存在的本地文件伪装为可用。 */
                static void attachmentCase(boolean present)throws Exception{
                    reset();TLRPC.TL_message message=message(1,present?31:30,"caption 😀");
                    DesktopAttachment attachment=new DesktopAttachment("sample.bin","file","synthetic-remote-path","application/octet-stream",3L,null,null,null);
                    message.params.put("codexAttachment",attachment.toJson().toString());message.params.put("codexLocalId",present?"present":"missing");
                    if(present){File target=AttachmentFiles.target(attachmentDirectory(1),"send\\n"+message.params.get("codexLocalId"),attachment.name);java.nio.file.Files.createDirectories(target.toPath().getParent());java.nio.file.Files.write(target.toPath(),new byte[]{1,2,3});}
                    publish(1,message,null);MessageObject preview=visible(1),chat=historyObject(0,message);
                    check(!preview.layoutCreated&&chat.layoutCreated,"attachment layout direction changed");
                    check(preview.attachPathExists==present&&preview.mediaExists==present&&chat.attachPathExists==present&&chat.mediaExists==present,"attachment readiness changed");
                    check(preview.messageOwner==message&&"caption 😀".equals(message.message),"attachment caption changed");
                }
                /** 账号代次和旧连接拒绝仍在真实发布入口，不能为省布局放宽归属。 */
                static void staleCase(){
                    reset();TLRPC.TL_message message=message(1,40,"stale");DesktopConnection old=desktopConnections.get("machine");
                    publishDialogPreview(0,accountGeneration-1,1,"remote","machine",old,message,null,Collections.emptySet());
                    desktopConnections.put("machine",new DesktopConnection("machine"));
                    publishDialogPreview(0,accountGeneration,1,"remote","machine",old,message,null,Collections.emptySet());
                    check(visible(1)==null&&MessageObject.constructions==0,"stale owner constructed preview");
                }
                /** 所有输入均为合成值；八组只检查本次布局边界及其直接保真条件。 */
                public static void main(String[] args)throws Exception{
                    ApplicationLoader.applicationContext.root=new File(args[0]);remoteIds.put(1L,"remote");dialogMachines.put(1L,"machine");desktopConnections.put("machine",new DesktopConnection("machine"));
                    for(String body:new String[]{"plain text","😀","👨‍👩‍👧‍👦 café 中文","caption 😀"})textCase(body);
                    pendingCase();attachmentCase(false);attachmentCase(true);staleCase();System.out.println("RuntimePreviewLayoutTest: 8 groups PASS");
                }
            """;
}
