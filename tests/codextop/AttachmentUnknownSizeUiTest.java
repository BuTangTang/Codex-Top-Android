package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取原文件副标题、测宽和无障碍分支，不复制Codex大小显示判断。 */
public final class AttachmentUnknownSizeUiTest {
    /** 缺少大小、真实零字节与普通Telegram均执行实际产品语句。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String source = Files.readString(Path.of("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java"));
        int start = source.indexOf("    private int createDocumentLayout("), end = source.indexOf("\n    private void calcBackgroundWidth(", start);
        if (start < 0 || end < 0) throw new AssertionError("原文件布局提取边界发生变化");
        var layout = StaticJavaParser.parseMethodDeclaration(source.substring(start, end));
        VariableDeclarator subtitle = layout.findAll(VariableDeclarator.class).stream()
                .filter(value -> value.getNameAsString().equals("str") && value.getInitializer().map(Object::toString).orElse("")
                        .contains("FileLoader.getDocumentExtension(documentAttach)"))
                .findFirst().orElseThrow();
        ExpressionStmt line = subtitle.findAncestor(ExpressionStmt.class).orElseThrow();
        var statements = line.findAncestor(BlockStmt.class).orElseThrow().getStatements();
        int index = statements.indexOf(line);
        String caption = "";
        if (index > 0 && statements.get(index - 1).toString().contains("documentSizeText("))
            caption += statements.get(index - 1);
        caption += line.toString() + statements.get(index + 1);
        if (!statements.get(index + 1).toString().startsWith("infoWidth ="))
            throw new AssertionError("原文件测宽语句发生变化");

        int accessStart = source.indexOf("                        if (buttonState == 0 || documentAttachType == DOCUMENT_ATTACH_TYPE_DOCUMENT) {");
        int accessEnd = source.indexOf("\n                        }", accessStart);
        if (accessStart < 0 || accessEnd < 0) throw new AssertionError("原文件无障碍分支发生变化");
        String accessibility = StaticJavaParser.parseStatement(source.substring(accessStart, accessEnd + "\n                        }".length())).toString();
        String formatter = "";
        int formatterStart = source.indexOf("    private static String documentSizeText(");
        if (formatterStart >= 0) {
            int formatterEnd = source.indexOf("\n    private int createDocumentLayout(", formatterStart);
            formatter = StaticJavaParser.parseMethodDeclaration(source.substring(formatterStart, formatterEnd)).toString();
        }
        Path temp = Files.createTempDirectory("codex-attachment-size-ui-");
        try {
            Path probe = temp.resolve("AttachmentSizeUiProbe.java");
            Files.writeString(probe, FIXTURE + formatter
                    + "/** 原布局语句直接形成副标题与测宽。 */ String caption(MessageObject messageObject){"
                    + caption + "return str;}"
                    + "/** 原无障碍分支直接形成描述。 */ String accessibility(){StringBuilder sb=new StringBuilder();"
                    + accessibility + "return sb.toString();}" + SCENARIOS + "}");
            Path runtime = temp.resolve("CodexRuntime.java");
            Files.writeString(runtime, "package com.butang.codextop; import org.telegram.messenger.MessageObject;"
                    + "public class CodexRuntime {/** 只提供现有描述解析边界，不复制大小显示逻辑。 */"
                    + "public static DesktopAttachment attachment(MessageObject message){return message!=null&&message.codex?message.attachment:null;}}");
            Path message = temp.resolve("MessageObject.java");
            Files.writeString(message, "package org.telegram.messenger; import com.butang.codextop.DesktopAttachment;"
                    + "public class MessageObject {public boolean codex;public DesktopAttachment attachment;"
                    + "public Owner messageOwner=new Owner();public static class Owner {public java.util.Map<String,String> params;}}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", temp.toString(), probe.toString(), runtime.toString(), message.toString()) != 0)
                throw new AssertionError("真实文件大小显示夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, AttachmentUnknownSizeUiTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.AttachmentSizeUiProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实文件大小显示回归失败", error.getCause()); }
            }
            verifyLocalFileProjection(temp.resolve("local-size"), formatter, caption, accessibility);
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 联合执行真实附件映射、缓存归属和气泡格式化；平台替身不代替文件选择或大小判断。 */
    private static void verifyLocalFileProjection(Path temp, String formatter, String caption, String accessibility) throws Exception {
        Files.createDirectories(temp);
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        var runtime = StaticJavaParser.parse(source.resolve("CodexRuntime.java"));
        var names = java.util.Set.of("attachmentDirectory", "cachedAttachment", "isAttachmentMessage", "attachment");
        StringBuilder methods = new StringBuilder();
        int extracted = 0;
        for (var method : runtime.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
            if (names.contains(method.getNameAsString())) { methods.append(method).append('\n'); extracted++; }
        }
        if (extracted != names.size()) throw new AssertionError("真实缓存方法提取边界变化");
        var digest = StaticJavaParser.parse(source.resolve("TranscriptStore.java"))
                .findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("digest")).findFirst().orElseThrow();
        Files.writeString(temp.resolve("CodexRuntime.java"), LOCAL_RUNTIME + methods
                + "static class TranscriptStore {" + digest + "}}\n");
        Files.writeString(temp.resolve("TLRPC.java"), LOCAL_TLRPC);
        Files.writeString(temp.resolve("BitmapFactory.java"), "package android.graphics;public class BitmapFactory {"
                + "public static class Options {public boolean inJustDecodeBounds;public int outWidth,outHeight;}"
                + "public static Object decodeFile(String path,Options options){return null;}}");
        Files.writeString(temp.resolve("OutboxStore.java"), "package com.butang.codextop;public class OutboxStore {"
                + "public static class Selection {public String localPath,name,kind,mimeType;}}");
        Files.writeString(temp.resolve("MessageObject.java"), "package org.telegram.messenger;import org.telegram.tgnet.TLRPC;"
                + "public class MessageObject {public TLRPC.TL_message messageOwner;"
                + "public long getDialogId(){return messageOwner.dialog_id;}}");
        Files.writeString(temp.resolve("AttachmentLocalSizeProbe.java"), LOCAL_PROBE + formatter
                + "String caption(MessageObject messageObject){" + caption + "return str;}"
                + "String accessibility(){StringBuilder sb=new StringBuilder();" + accessibility + "return sb.toString();}"
                + LOCAL_SCENARIOS + "}\n");
        java.util.ArrayList<String> compile = new java.util.ArrayList<>(java.util.List.of(
                "-cp", System.getProperty("java.class.path"), "-d", temp.toString(),
                source.resolve("AttachmentMessages.java").toString(), source.resolve("AttachmentFiles.java").toString()));
        try (var files = Files.list(temp)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> compile.add(path.toString()));
        }
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(new String[0])) != 0)
            throw new AssertionError("真实附件映射和缓存联测编译失败");
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, AttachmentUnknownSizeUiTest.class.getClassLoader())) {
            try { loader.loadClass("com.butang.codextop.AttachmentLocalSizeProbe").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{temp.resolve("files").toString()}); }
            catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实附件大小联测失败", error.getCause()); }
        }
    }

    // 仅替换平台账号和目录入口；缓存路径、描述解析与散列均从当前产品源码提取。
    private static final String LOCAL_RUNTIME = """
        package com.butang.codextop;
        import java.io.*;import java.util.*;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;
        import com.google.gson.*;import org.telegram.messenger.MessageObject;
        public class CodexRuntime {
            static final Map<Long,String> remoteIds=new HashMap<>();
            static PasswordLogin.Session session=new PasswordLogin.Session();
            static String machine="machine-a";static boolean owned=true;
            static class PasswordLogin {static class Session {String server="server-a",accountId="account-a";}}
            static class ApplicationLoader {static Context applicationContext=new Context();}
            static class Context {File root;File getExternalFilesDir(String name){return root;}File getNoBackupFilesDir(){return root;}}
            static String dialogMachine(long id){return machine;}
            static boolean ownsConversation(long id){return owned&&remoteIds.containsKey(id);}
            static File directory(){return attachmentDirectory(1);}
            static File cached(String localId,DesktopAttachment value){return cachedAttachment(directory(),localId,value);}
        """;

    // 平台模型只提供真实 AttachmentMessages 所写入的字段，不实现任何显示或缓存逻辑。
    private static final String LOCAL_TLRPC = """
        package org.telegram.tgnet;import java.util.*;
        public class TLRPC {
            public static class TL_message {public HashMap<String,String> params;public String attachPath;public int flags,id,date;public long dialog_id;public MessageMedia media;}
            public static class MessageMedia {public int flags;public TL_document document;public TL_photo photo;}
            public static class TL_messageMediaPhoto extends MessageMedia {}
            public static class TL_messageMediaDocument extends MessageMedia {}
            public static class TL_photo {public long id;public int date;public byte[] file_reference;public ArrayList<TL_photoSize> sizes=new ArrayList<>();}
            public static class TL_photoSize {public String type;public int w,h,size;public TL_fileLocationUnavailable location;}
            public static class TL_fileLocationUnavailable {public long volume_id;public int local_id;public byte[] file_reference;}
            public static class TL_document {public long id,size;public int date;public byte[] file_reference;public String mime_type;public ArrayList<TL_documentAttributeFilename> attributes=new ArrayList<>();}
            public static class TL_documentAttributeFilename {public String file_name;}
        }
        """;

    private static final String LOCAL_PROBE = """
        package com.butang.codextop;
        import java.io.*;import java.nio.file.*;import org.telegram.messenger.MessageObject;import org.telegram.tgnet.TLRPC;
        public class AttachmentLocalSizeProbe {
            static int checks;
            static final int DOCUMENT_ATTACH_TYPE_DOCUMENT=1;
            static class AndroidUtilities {static String formatFileSize(long n){return n==0?"0 KB":n+" B";}}
            static class FileLoader {static String getDocumentExtension(TLRPC.TL_document d){return "BIN";}}
            static class Paint {String measured;float measureText(String s){measured=s;return s.length();}}
            static class Theme {static final Paint chat_infoPaint=new Paint();}
            TLRPC.TL_document documentAttach;MessageObject currentMessageObject;
            int documentAttachType=1,buttonState=-1,infoWidth,maxWidth=1000;
            static int dp(int n){return n;}
            static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
        """;

    private static final String LOCAL_SCENARIOS = """
            static DesktopAttachment description(Long size,String availability){
                return new DesktopAttachment("same.bin","file",availability==null?"/synthetic/remote.bin":null,null,size,null,availability,null);
            }
            static TLRPC.TL_message message(){TLRPC.TL_message m=new TLRPC.TL_message();m.dialog_id=1;m.id=100;m.date=1;return m;}
            static void verify(TLRPC.TL_message m,String expected,String label){
                AttachmentLocalSizeProbe p=new AttachmentLocalSizeProbe();MessageObject object=new MessageObject();object.messageOwner=m;
                p.currentMessageObject=object;p.documentAttach=m.media.document;
                check(p.caption(object).equals(expected+" BIN"),label+": subtitle");
                check(Theme.chat_infoPaint.measured.equals("000.0 mm / "+expected),label+": measurement");
                check(p.accessibility().equals(", "+expected),label+": accessibility");
            }
            static TLRPC.TL_message echo(String id,DesktopAttachment value,String expected,String label){
                File cached=CodexRuntime.cached(id,value);TLRPC.TL_message m=message();AttachmentMessages.apply(m,value,cached);
                check(m.params.get("codexAttachment").equals(value.toJson().toString()),label+": authoritative JSON unchanged");
                verify(m,expected,label);return m;
            }
            public static void main(String[] args)throws Exception{
                Path root=Path.of(args[0]);Files.createDirectories(root);
                CodexRuntime.ApplicationLoader.applicationContext.root=root.resolve("cache").toFile();CodexRuntime.remoteIds.put(1L,"remote-a");
                Path source=root.resolve("source.bin");Files.write(source,new byte[17]);
                DesktopAttachment.Pending staged=AttachmentFiles.stage(source.toFile(),"same.bin","file",null,CodexRuntime.directory(),"send\\noriginal");
                DesktopAttachment unknown=description(null,null);
                check(new File(staged.localPath).length()==17,"real stage size");
                TLRPC.TL_message local=echo("original",unknown,"17 B","cached unknown");
                check(local.media.document.size==17,"media uses actual local size");
                echo("original",unknown,"17 B","recreated without Outbox");
                echo("different",unknown,"大小未知","other localId same filename");
                echo("original:attachment:1",unknown,"大小未知","other attachment index");
                for(int scope=0;scope<4;scope++){
                    if(scope==0)CodexRuntime.session.server="other";
                    if(scope==1)CodexRuntime.session.accountId="other";
                    if(scope==2)CodexRuntime.machine="other";
                    if(scope==3)CodexRuntime.remoteIds.put(1L,"other");
                    check(CodexRuntime.cached("original",unknown)==null,"scope isolation "+scope);
                    echo("original",unknown,"大小未知","different scope "+scope);
                    CodexRuntime.session.server="server-a";CodexRuntime.session.accountId="account-a";CodexRuntime.machine="machine-a";CodexRuntime.remoteIds.put(1L,"remote-a");
                }
                DesktopAttachment conflict=description(3L,null);
                check(CodexRuntime.cached("original",conflict)==null,"known size conflict rejects original cache");
                echo("original",conflict,"3 B","authoritative size wins");
                echo("different",description(0L,null),"0 KB","known remote zero");
                echo("original",description(null,"unavailable"),"大小未知","unavailable rejects cache");
                Path empty=root.resolve("empty.bin");Files.write(empty,new byte[0]);
                AttachmentFiles.stage(empty.toFile(),"same.bin","file",null,CodexRuntime.directory(),"send\\nempty");
                echo("empty",unknown,"0 KB","actual empty local file");
                // 同一对象从本地已知改为无缓存时必须清理派生事实；引用JSON仍缺大小。
                AttachmentMessages.apply(local,unknown,null);verify(local,"大小未知","stale local marker cleared");
                AttachmentMessages.apply(local,description(3L,null),new File(staged.localPath));verify(local,"3 B","provided authoritative value wins");
                AttachmentMessages.apply(local,unknown,null);verify(local,"大小未知","known-to-unknown clears marker");
                // 格式化只读已建立模型；删除文件不影响本次格式化，重建则重新判定为未知。
                TLRPC.TL_message beforeDeletion=echo("original",unknown,"17 B","before deletion");
                Files.delete(Path.of(staged.localPath));verify(beforeDeletion,"17 B","formatter performs no file lookup");
                echo("original",unknown,"大小未知","recreate after cache removal");
                TLRPC.TL_message telegram=message();AttachmentMessages.apply(telegram,unknown,null);telegram.media.document.size=23;
                CodexRuntime.owned=false;verify(telegram,"23 B","ordinary Telegram formatter unchanged");CodexRuntime.owned=true;
                // 选中或待发投影复用同一media入口，之后权威无缓存回显不能继承其标记。
                TLRPC.TL_message selected=message();AttachmentMessages.applySelected(selected,empty.toFile(),"same.bin","file");
                AttachmentMessages.apply(selected,unknown,null);verify(selected,"大小未知","selected-to-echo reset");
                AttachmentMessages.applyPending(selected,DesktopAttachment.Pending.read(AttachmentFiles.stage(empty.toFile(),"same.bin","file",null,CodexRuntime.directory(),"send\\npending").toJson()));
                verify(selected,"0 KB","pending zero unchanged");AttachmentMessages.apply(selected,unknown,null);verify(selected,"大小未知","pending-to-echo reset");
                System.out.println("AttachmentLocalSize PASS: "+checks+" assertions; real apply, cache scope, subtitle, measurement and accessibility");
            }
        """;

    // 替身只提供原平台格式化、绘制测宽与已解析描述；显示决策来自真实提取语句。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import org.telegram.messenger.MessageObject;
        public final class AttachmentSizeUiProbe {
            static final int DOCUMENT_ATTACH_TYPE_DOCUMENT=1;
            static final class Document {long size;}
            static final class AndroidUtilities {
                static int calls;
                /** 保留本次0字节和小文件的原格式化边界。 */
                static String formatFileSize(long size){calls++;return size==0?"0 KB":size+" B";}
            }
            static final class FileLoader {/** 扩展名由平台元信息提供。 */static String getDocumentExtension(Document document){return "TXT";}}
            static final class Paint {String measured;/** 只记录原测宽使用的文字。 */float measureText(String value){measured=value;return value.length();}}
            static final class Theme {static final Paint chat_infoPaint=new Paint();}
            final Document documentAttach=new Document();
            MessageObject currentMessageObject;
            int documentAttachType=DOCUMENT_ATTACH_TYPE_DOCUMENT,buttonState=-1,infoWidth,maxWidth=1000;
            /** 平台密度边界不参与文字判断。 */static int dp(int value){return value;}
            /** 失败只输出固定标签，不读取或输出真实消息。 */static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
        """;
    private static final String SCENARIOS = """
            /** 各场景共用实际副标题和无障碍调用，不复制判断。 */
            static void verify(boolean codex,DesktopAttachment attachment,long size,String expected,String label){
                AttachmentSizeUiProbe probe=new AttachmentSizeUiProbe();
                MessageObject message=new MessageObject();message.codex=codex;message.attachment=attachment;
                probe.currentMessageObject=message;probe.documentAttach.size=size;AndroidUtilities.calls=0;
                check(probe.caption(message).equals(expected+" TXT"),label+": subtitle");
                check(Theme.chat_infoPaint.measured.equals("000.0 mm / "+expected),label+": measurement");
                check(probe.accessibility().equals(", "+expected),label+": accessibility");
                check(expected.equals("大小未知")?AndroidUtilities.calls==0:AndroidUtilities.calls>0,label+": formatter boundary");
                probe.buttonState=2;probe.documentAttachType=2;
                check(probe.accessibility().isEmpty(),label+": original hidden branch");
            }
            /** 云文件无path、已知零字节和旧Telegram路径分别保持真实含义。 */
            public static void main(String[] args){
                verify(true,new DesktopAttachment("cloud.txt","file",null,null,null,null,"unavailable","unsupported_reference"),0,"大小未知","unavailable unknown");
                verify(true,new DesktopAttachment("local.txt","file","/synthetic/local.txt",null,null,null,null,null),0,"大小未知","available unknown");
                verify(true,new DesktopAttachment("empty.txt","file",null,null,0L,null,"unavailable","unsupported_reference"),0,"0 KB","known zero");
                verify(true,new DesktopAttachment("small.txt","file","/synthetic/small.txt",null,11L,null,null,null),11,"11 B","known nonzero");
                verify(true,null,0,"0 KB","missing descriptor");
                verify(false,new DesktopAttachment("cloud.txt","file",null,null,null,null,"unavailable","unsupported_reference"),0,"0 KB","Telegram unchanged zero");
                verify(false,null,11,"11 B","Telegram unchanged nonzero");
                System.out.println("AttachmentUnknownSizeUi PASS: real subtitle/measurement/accessibility; unknown, known zero, positive and Telegram unchanged");
            }
        """;
}
