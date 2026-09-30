package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.IfStmt;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 仅提取原选择器分支和文档准备的两项条件，不复制大小或Codex归属判断。 */
public final class AttachmentUploadLimitUiTest {
    /** 原件和平台返回均为合成样例，验证Codex不弹Premium及普通Telegram保持原判断。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source=Path.of("TMessagesProj/src/main/java/org/telegram");
        String layout=Files.readString(source.resolve("ui/Components/ChatAttachAlertDocumentLayout.java"));
        int clickStart=layout.indexOf("    private boolean onItemClick("),clickEnd=layout.indexOf("\n    public boolean isRingtone(",clickStart);
        if(clickStart<0||clickEnd<0)throw new AssertionError("真实选择入口发生变化，请核对提取边界");
        var click=StaticJavaParser.parseMethodDeclaration(layout.substring(clickStart,clickEnd));
        IfStmt sizeBranch=click.findAll(IfStmt.class).stream()
                .filter(s->s.getCondition().toString().contains("FileLoader.DEFAULT_MAX_FILE_SIZE")).findFirst().orElseThrow();
        if(sizeBranch.getParentNode().orElse(null) instanceof IfStmt parent
                &&parent.getCondition().toString().contains("ownsConversation"))sizeBranch=parent;
        String helper=Files.readString(source.resolve("messenger/SendMessagesHelper.java"));
        int start=helper.indexOf("    private static int prepareSendingDocumentInternal("),end=helper.indexOf("\n    private static boolean checkFileSize(",start);
        if(start<0||end<0)throw new AssertionError("真实文档准备入口发生变化，请核对提取边界");
        // 只解析实际目标方法，避免把整个Telegram巨型类装入32MiB回归堆。
        var prepare=StaticJavaParser.parseMethodDeclaration(helper.substring(start,end));
        String uri=prepare.findAll(IfStmt.class).stream().map(s->s.getCondition().toString())
                .filter(s->s.contains("checkFileSize(accountInstance, uri)")).findFirst().orElseThrow();
        String file=prepare.findAll(IfStmt.class).stream().map(s->s.getCondition().toString())
                .filter(s->s.contains("FileLoader.checkUploadFileSize")).findFirst().orElseThrow();
        Path temp=Files.createTempDirectory("codex-upload-limit-ui");
        try {
            Path probe=temp.resolve("UploadLimitUiProbe.java");
            Files.writeString(probe,FIXTURE+"\n/** 执行选择器原分支。 */ boolean select(File file){ListItem item=new ListItem(file);"
                    +sizeBranch+"return true;}\n/** 执行URI原检查条件。 */ boolean uriBlocked(AccountInstance accountInstance,Uri uri,long dialogId){return "+uri+";}"
                    +"\n/** 执行本地文件原检查条件。 */ boolean fileBlocked(AccountInstance accountInstance,File f,long dialogId){return "+file+";}\n"+SCENARIOS+"}");
            Path runtime=temp.resolve("CodexRuntime.java");
            Files.writeString(runtime,"package com.butang.codextop; public class CodexRuntime {public static boolean owned;public static Long limit;/** 只提供合成对话归属。 */ public static boolean ownsConversation(long d){return owned&&d==1;} /** 只提供既有连接能力边界。 */ public static Long attachmentUploadMaxBytes(long d){return limit;}}");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",temp.toString(),probe.toString(),runtime.toString())!=0)
                throw new AssertionError("真实选择分支夹具编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},null)){
                try{loader.loadClass("com.butang.codextop.UploadLimitUiProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{temp.toString()});}
                catch(java.lang.reflect.InvocationTargetException error){throw new AssertionError("真实选择分支回归失败",error.getCause());}
            }
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    // 替身仅提供平台反馈和元信息；是否拦截、是否提示均来自真实产品分支。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import java.io.*;import java.nio.file.*;
        public final class UploadLimitUiProbe {
            static final class ListItem {File file;/** 合成原列表项。 */ListItem(File f){file=f;}}
            static final class Parent {Object baseFragment;/** 原对话编号边界。 */long getDialogId(){return 1;}/** 合成容器边界。 */Container getContainer(){return new Container();}}
            static final class Container {/** 不创建Android界面。 */Object getContext(){return null;}}
            final Parent parentAlert=new Parent();String shown;
            /** 只记录已有错误框的文字，不创建新提示。 */void showErrorBox(String text){shown=text;}
            static final class AndroidUtilities {/** 可观察实际限额的原格式化边界。 */static String formatFileSize(long size){return "bytes:"+size;}}
            static final class FileLoader {static final long DEFAULT_MAX_FILE_SIZE=2,DEFAULT_MAX_FILE_SIZE_PREMIUM=4;static boolean allowed;static int checks;
                /** Telegram上传门禁是平台边界，只控制既有允许结果。 */static boolean checkUploadFileSize(int account,long length){checks++;return allowed;}}
            static final class UserConfig {static int selectedAccount;static boolean premium;/** 合成平台账号。 */static UserConfig getInstance(int a){return new UserConfig();}/** 合成已有Premium状态。 */boolean isPremium(){return premium;}}
            static final class LimitReachedBottomSheet {static final int TYPE_LARGE_FILE=1;static int shows;
                /** 只记录既有Premium提示。 */LimitReachedBottomSheet(Object f,Object c,int t,int a,Object x){}
                /** 不更改平台状态。 */void setVeryLargeFile(boolean value){}
                /** 记录是否触发原提示。 */void show(){shows++;}}
            static final class AccountInstance {/** 合成账号编号。 */int getCurrentAccount(){return 0;}}
            static final class Uri {boolean large;/** 提供平台URI大小判断结果。 */Uri(boolean v){large=v;}}
            static int uriChecks;
            /** URI元信息和Telegram门禁不在选择器归属判断内，只提供原返回。 */static boolean checkFileSize(AccountInstance account,Uri uri){uriChecks++;return uri.large;}
            /** 所有断言只输出固定文字，不输出消息或真实文件路径。 */static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
        """;
    private static final String SCENARIOS="""
            /** 执行真实条件，未知上限、刚好上限及普通Telegram各保持原分支。 */
            public static void main(String[] args)throws Exception{
                Path path=Path.of(args[0],"synthetic.bin");Files.write(path,new byte[]{1,2,3});File file=path.toFile();
                CodexRuntime.owned=true;CodexRuntime.limit=null;UploadLimitUiProbe p=new UploadLimitUiProbe();
                check(p.select(file)&&p.shown==null&&LimitReachedBottomSheet.shows==0,"Codex未知限额仍弹Telegram Premium");
                CodexRuntime.limit=2L;p=new UploadLimitUiProbe();check(!p.select(file)&&p.shown.contains("bytes:2")&&LimitReachedBottomSheet.shows==0,"已知超限未复用原错误框或未显示实际限额");
                CodexRuntime.limit=3L;p=new UploadLimitUiProbe();check(p.select(file)&&p.shown==null&&LimitReachedBottomSheet.shows==0,"正常文件增加额外提示");
                AccountInstance account=new AccountInstance();FileLoader.allowed=false;
                check(!p.uriBlocked(account,new Uri(true),1)&&!p.fileBlocked(account,file,1)&&FileLoader.checks==0&&uriChecks==0,"Codex文档准备仍调用Telegram大小门禁");
                CodexRuntime.owned=false;UserConfig.premium=false;p=new UploadLimitUiProbe();check(!p.select(file)&&LimitReachedBottomSheet.shows==1&&p.shown==null,"普通Telegram大文件行为改变");
                UserConfig.premium=true;check(p.select(file)&&LimitReachedBottomSheet.shows==1,"普通Premium合法文件被拒绝");Files.write(path,new byte[]{1,2,3,4,5});
                check(!p.select(file)&&LimitReachedBottomSheet.shows==2,"普通Premium上限判断丢失");
                check(p.uriBlocked(account,new Uri(true),1)&&p.fileBlocked(account,file,1)&&FileLoader.checks==1&&uriChecks==1,"普通Telegram文档准备绕过原大小门禁");
                FileLoader.allowed=true;check(!p.uriBlocked(account,new Uri(false),1)&&!p.fileBlocked(account,file,1),"普通Telegram允许结果改变");
                System.out.println("AttachmentUploadLimitUi: 真实选择分支已知/未知/正常提示、Codex无Premium及原Telegram条件通过");
            }
        """;
}
