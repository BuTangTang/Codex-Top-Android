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
                    + "public class MessageObject {public boolean codex;public DesktopAttachment attachment;}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", temp.toString(), probe.toString(), runtime.toString(), message.toString()) != 0)
                throw new AssertionError("真实文件大小显示夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, AttachmentUnknownSizeUiTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.AttachmentSizeUiProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实文件大小显示回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

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
