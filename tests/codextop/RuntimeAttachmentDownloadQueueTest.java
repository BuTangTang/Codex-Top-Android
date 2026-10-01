package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实下载及旧页请求；仅以合成平台和远端替代 Android、账号及 RPC。 */
public final class RuntimeAttachmentDownloadQueueTest {
    /** 在独立目录验证调度、真实加密传输及文件发布，不连接 daemon 或真实设备。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source.resolve("CodexRuntime.java"));
        var names = Set.of("downloadAttachment", "transfer", "attachmentProgress", "attachmentCurrent",
                "isAccountCurrent", "dialogConnection", "cachedAttachment", "attachment", "attachmentKey", "isAttachmentMessage");
        StringBuilder methods = new StringBuilder();
        int extracted = 0;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (names.contains(method.getNameAsString())) { methods.append(method).append('\n'); extracted++; }
        }
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getNameAsString().equals("AttachmentState")) methods.append(type).append('\n');
        }
        if (extracted != names.size()) throw new AssertionError("下载入口变化，请核对真实方法夹具");
        var load = unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("loadMessages")).findFirst().orElseThrow();
        // 保留生产代码真正的旧页网络 Runnable，不在测试中复制分页算法。
        var oldPage = load.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("postRunnable")
                        && call.getScope().map(Object::toString).orElse("").equals("historyQueue"))
                .map(call -> call.getArgument(0).asLambdaExpr()).findFirst().orElseThrow();
        methods.append("static void startOlderPage() {\n")
                .append("final long accountEpoch=accountGeneration,dialogId=1; final String remote=remoteIds.get(dialogId);\n")
                .append("final DesktopConnection connection=dialogConnection(dialogId); final TranscriptWindow history=new TranscriptWindow();\n")
                .append("histories.put(dialogId,history); final String cursor=history.cursor; final boolean loaded=history.loaded;\n")
                .append("final Runnable resume=()->{}; historyQueue.postRunnable(").append(oldPage).append("); }\n");
        Path temporary = Files.createTempDirectory("codex-download-queue-");
        try {
            Path probe = temporary.resolve("RuntimeAttachmentDownloadQueueProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}\n");
            var compile = new ArrayList<String>();
            compile.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString(), probe.toString()));
            for (String model : new String[]{"DesktopAttachment", "AttachmentFiles", "AttachmentTransfer", "BulkTransferCrypto",
                    "TranscriptStore", "TranscriptText", "TranscriptWindow"}) compile.add(source.resolve(model + ".java").toString());
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实下载方法夹具编译失败");
            // Probe 与产品模型必须同属一个加载器，父 classpath 已有模型时也不能拆开 package-private 访问。
            var isolatedPaths = new ArrayList<java.net.URL>();
            isolatedPaths.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                isolatedPaths.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(isolatedPaths.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimeAttachmentDownloadQueueProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{temporary.toString()}); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实下载队列回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;
        import java.util.concurrent.*;import java.util.concurrent.atomic.*;
        public final class RuntimeAttachmentDownloadQueueProbe {
            // 平台边界为真正的串行工作线程；生产方法选择哪条队列由被提取代码决定。
            static final class Queue implements AutoCloseable {
                final ExecutorService executor; final AtomicReference<Throwable> failure=new AtomicReference<>();
                Queue(String name){executor=Executors.newSingleThreadExecutor(r->new Thread(r,name));}
                void postRunnable(Runnable r){executor.execute(()->{try{r.run();}catch(Throwable e){failure.compareAndSet(null,e);}});}
                void idle() throws Exception {executor.submit(()->{}).get(3,TimeUnit.SECONDS);if(failure.get()!=null)throw new AssertionError(failure.get());}
                public void close() throws Exception {executor.shutdownNow();check(executor.awaitTermination(3,TimeUnit.SECONDS),"合成队列未退出");}
            }
            static final Queue historyQueue=new Queue("history"),sendQueue=new Queue("send"),statusQueue=new Queue("status");
            static final class Utilities {static final Queue externalNetworkQueue=new Queue("external-network"),globalQueue=new Queue("local-history");}
            static final ConcurrentLinkedQueue<Runnable> ui=new ConcurrentLinkedQueue<>();
            static final class AndroidUtilities {static void runOnUIThread(Runnable r){ui.add(r);}}
            static final class NotificationCenter {
                static final int updateInterfaces=1;static NotificationCenter getInstance(int account){return new NotificationCenter();}
                void postNotificationName(int event,Object... values){}
            }
            static final class MessagesController {static final int UPDATE_MASK_SEND_STATE=1;}
            static final class TLRPC {
                static class Message {HashMap<String,String> params=new HashMap<>();long dialog_id=1;int id=1;}
                static class TL_message extends Message {File applied;}
            }
            static final class MessageObject {
                final TLRPC.Message messageOwner;boolean attachPathExists,mediaExists;int thumbs;
                MessageObject(TLRPC.Message owner){messageOwner=owner;}
                long getDialogId(){return messageOwner.dialog_id;}int getId(){return messageOwner.id;}
                void generateThumbs(boolean unused){thumbs++;}
            }
            static final class AttachmentMessages {
                static void apply(TLRPC.TL_message message,DesktopAttachment value,File local){check(local.isFile(),"发布了不存在的文件");message.applied=local;}
            }
            static volatile long accountGeneration=1;static volatile boolean loggingOut;static Object session=new Object();
            static final Map<Long,String> remoteIds=new ConcurrentHashMap<>(),dialogMachines=new ConcurrentHashMap<>();
            static final Map<String,DesktopConnection> desktopConnections=new ConcurrentHashMap<>();
            static final Map<Long,TranscriptWindow> histories=new ConcurrentHashMap<>();
            static final Map<String,AttachmentState> attachmentStates=new HashMap<>();
            static final Map<String,ArrayList<Runnable>> attachmentCallbacks=new HashMap<>();
            static Exception fetchError;static File directory;static int scenario;
            static boolean ownsConversation(long dialogId){return remoteIds.containsKey(dialogId);}
            static File attachmentDirectory(long dialogId){return directory;}
            static File attachmentFile(MessageObject message){return cachedAttachment(directory,null,attachment(message));}
            static boolean saveHistory(long dialogId,String remote,TranscriptWindow history){return true;}
            static final byte[] bytes=new byte[]{4,8,15,16,23,42};
            static final class DesktopConnection {
                final CountDownLatch oldEntered=new CountDownLatch(1),oldRelease=new CountDownLatch(1),initEntered=new CountDownLatch(1);
                final CountDownLatch finalizeEntered=new CountDownLatch(1);CountDownLatch initRelease,finalizeRelease;
                final AtomicInteger initCalls=new AtomicInteger(),abortCalls=new AtomicInteger(),finalizeCalls=new AtomicInteger();
                volatile boolean corrupt;String recipient;
                JsonObject transcript(String remote,String cursor) throws Exception {
                    oldEntered.countDown();check(oldRelease.await(3,TimeUnit.SECONDS),"合成旧页未释放");
                    JsonObject page=new JsonObject();page.add("items",new JsonArray());page.addProperty("hasMore",false);return page;
                }
                JsonObject transfer(String method,JsonObject params) throws Exception {
                    JsonObject value=new JsonObject();value.addProperty("success",true);
                    if(method.endsWith("download.init")){
                        initCalls.incrementAndGet();initEntered.countDown();if(initRelease!=null)check(initRelease.await(3,TimeUnit.SECONDS),"合成下载未释放");
                        recipient=params.get("recipientPublicKeyBase64").getAsString();value.addProperty("downloadId","synthetic-download");
                        value.addProperty("chunkSizeBytes",64);value.addProperty("sizeBytes",bytes.length);value.addProperty("name","sample.bin");
                    }else if(method.endsWith("download.chunk")){
                        byte[] content=bytes.clone();if(corrupt)content[0]++;
                        value=BulkTransferCrypto.encrypt("synthetic-download",params.get("index").getAsInt(),content,content.length,recipient);
                        value.addProperty("success",true);value.addProperty("isLast",true);
                    }else if(method.endsWith("download.finalize")){
                        finalizeCalls.incrementAndGet();finalizeEntered.countDown();if(finalizeRelease!=null)check(finalizeRelease.await(3,TimeUnit.SECONDS),"合成完成未释放");
                    }else if(method.endsWith("download.abort"))abortCalls.incrementAndGet();
                    else throw new AssertionError("出现非下载RPC");return value;
                }
            }
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static void uiAll(){Runnable r;while((r=ui.poll())!=null)r.run();}
            static DesktopConnection reset(File root) throws Exception {
                historyQueue.idle();Utilities.externalNetworkQueue.idle();Utilities.globalQueue.idle();uiAll();
                accountGeneration++;loggingOut=false;session=new Object();remoteIds.clear();dialogMachines.clear();desktopConnections.clear();histories.clear();
                attachmentStates.clear();attachmentCallbacks.clear();fetchError=null;directory=new File(root,"case-"+(++scenario));
                Files.createDirectories(directory.toPath());remoteIds.put(1L,"synthetic-session");dialogMachines.put(1L,"synthetic-machine");
                DesktopConnection connection=new DesktopConnection();desktopConnections.put("synthetic-machine",connection);return connection;
            }
            static MessageObject message() throws Exception {
                StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));
                DesktopAttachment value=new DesktopAttachment("sample.bin","file","/synthetic/sample.bin","application/octet-stream",(long)bytes.length,hash.toString(),null,null);
                TLRPC.TL_message owner=new TLRPC.TL_message();owner.params.put("codexAttachment",value.toJson().toString());return new MessageObject(owner);
            }
            static void finishDownload() throws Exception {Utilities.externalNetworkQueue.idle();historyQueue.idle();uiAll();}
            static void noPartial() throws Exception {
                try(var paths=Files.walk(directory.toPath())){check(paths.noneMatch(p->p.getFileName().toString().endsWith(".part")),"残留下载临时文件");}
            }
        """;

    private static final String SCENARIOS = """
            static void queueIsolation(File root) throws Exception {
                DesktopConnection connection=reset(root);MessageObject message=message();AtomicInteger callbacks=new AtomicInteger();
                connection.initRelease=new CountDownLatch(1);startOlderPage();
                try {
                    check(connection.oldEntered.await(1,TimeUnit.SECONDS),"真实旧页请求未进入");
                    downloadAttachment(0,message,callbacks::incrementAndGet);
                    check(connection.initEntered.await(500,TimeUnit.MILLISECONDS),"旧页未返回时下载init被historyQueue阻塞");
                    CountDownLatch send=new CountDownLatch(1),status=new CountDownLatch(1);sendQueue.postRunnable(send::countDown);statusQueue.postRunnable(status::countDown);
                    check(send.await(1,TimeUnit.SECONDS)&&status.await(1,TimeUnit.SECONDS),"附件占用了发送或状态队列");
                    connection.initRelease.countDown();Utilities.externalNetworkQueue.idle();uiAll();
                    check(callbacks.get()==1&&message.mediaExists&&message.attachPathExists&&message.thumbs==1,"下载未交回原消息");
                    check(Arrays.equals(Files.readAllBytes(attachmentFile(message).toPath()),bytes),"真实下载字节不一致");
                    check(connection.oldRelease.getCount()==1,"下载必须等旧页返回");noPartial();
                } finally {connection.initRelease.countDown();connection.oldRelease.countDown();finishDownload();Utilities.globalQueue.idle();}
            }
            static void sameKeyAndReady(File root) throws Exception {
                DesktopConnection connection=reset(root);MessageObject message=message();AtomicInteger callbacks=new AtomicInteger();connection.initRelease=new CountDownLatch(1);
                downloadAttachment(0,message,callbacks::incrementAndGet);
                try {
                    check(connection.initEntered.await(1,TimeUnit.SECONDS),"下载未开始");downloadAttachment(0,message,callbacks::incrementAndGet);
                    check(connection.initCalls.get()==1&&attachmentCallbacks.get(attachmentKey(message)).size()==2,"同键启动了两次传输");
                } finally {connection.initRelease.countDown();finishDownload();}
                check(callbacks.get()==2&&connection.finalizeCalls.get()==1,"同键回调未复用");
                downloadAttachment(0,message,callbacks::incrementAndGet);uiAll();
                check(callbacks.get()==3&&connection.initCalls.get()==1,"已就绪文件重复下载");
            }
            static void integrityFailureRetry(File root) throws Exception {
                DesktopConnection connection=reset(root);MessageObject message=message();AtomicInteger callbacks=new AtomicInteger();connection.corrupt=true;
                downloadAttachment(0,message,callbacks::incrementAndGet);finishDownload();
                check(callbacks.get()==1&&attachmentStates.get(attachmentKey(message)).failed&&!message.mediaExists&&attachmentFile(message)==null,"错误SHA被发布为可用附件");
                check(connection.abortCalls.get()==1&&connection.finalizeCalls.get()==0,"校验失败未走原abort");noPartial();
                connection.corrupt=false;downloadAttachment(0,message,callbacks::incrementAndGet);finishDownload();
                check(callbacks.get()==2&&connection.initCalls.get()==2&&message.mediaExists&&!attachmentStates.get(attachmentKey(message)).failed,"失败不可再次点击重试");
                check(Arrays.equals(Files.readAllBytes(attachmentFile(message).toPath()),bytes),"重试字节不同");noPartial();
            }
            static void lateAccount(File root) throws Exception {
                DesktopConnection connection=reset(root);MessageObject message=message();AtomicInteger callbacks=new AtomicInteger();connection.finalizeRelease=new CountDownLatch(1);
                downloadAttachment(0,message,callbacks::incrementAndGet);
                try {
                    check(connection.finalizeEntered.await(1,TimeUnit.SECONDS),"未进入真实finalize边界");
                    accountGeneration++;loggingOut=true;session=null;attachmentStates.clear();attachmentCallbacks.clear();
                } finally {connection.finalizeRelease.countDown();finishDownload();}
                check(callbacks.get()==0&&!message.mediaExists&&attachmentStates.isEmpty()&&attachmentFile(message)==null,"旧账号迟到结果污染新归属");noPartial();
            }
            static void lateConnection(File root) throws Exception {
                DesktopConnection connection=reset(root);MessageObject message=message();AtomicInteger callbacks=new AtomicInteger();connection.finalizeRelease=new CountDownLatch(1);
                downloadAttachment(0,message,callbacks::incrementAndGet);
                try {
                    check(connection.finalizeEntered.await(1,TimeUnit.SECONDS),"未进入真实finalize边界");
                    desktopConnections.put("synthetic-machine",new DesktopConnection());
                } finally {connection.finalizeRelease.countDown();finishDownload();}
                check(callbacks.get()==1&&!message.mediaExists&&attachmentFile(message)==null&&attachmentStates.get(attachmentKey(message)).failed,"旧连接迟到结果发布为可用");noPartial();
            }
            public static void main(String[] args) throws Exception {
                try {
                    File root=new File(args[0]);queueIsolation(root);sameKeyAndReady(root);integrityFailureRetry(root);lateAccount(root);lateConnection(root);
                    System.out.println("RuntimeAttachmentDownloadQueue: 真实旧页阻塞时下载先行、发送/状态隔离、同键复用、已就绪复用、真实SHA失败重试、账号/连接迟到隔离通过");
                } finally {historyQueue.close();sendQueue.close();statusQueue.close();Utilities.externalNetworkQueue.close();Utilities.globalQueue.close();}
            }
        """;
}
