package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实发送、失败与恢复方法，使用真实Outbox和附件传输，仅替代Android及远端边界。 */
public final class RuntimeSendFailureTest {
    /** 所有消息、文件、账号和RPC均为合成样例；调用方提供现成Gson、JavaParser及NaCl依赖。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source.resolve("CodexRuntime.java"));
        var names = Set.of("sendBatch", "finishSend", "restoredPending", "pendingMessage", "canRetryMessage",
                "isAccountCurrent", "attachmentCurrent", "transfer", "isAttachmentMessage", "attachmentUploadFailureCode",
                "dialogConnection", "attachmentUploadMaxBytes", "attachmentTooLarge", "loadMessages", "watchConversation",
                "historyMessage", "historyObject", "saveHistory", "batchEchoed", "readHistory", "confirmPendingEcho");
        StringBuilder methods = new StringBuilder();
        int extracted = 0;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (names.contains(method.getNameAsString())) { methods.append(method.toString().replace("org.telegram.messenger.MessageObject", "MessageObject")).append('\n'); extracted++; }
        }
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getNameAsString().equals("AttachmentState")) methods.append(type).append('\n');
        }
        if (extracted != names.size()) throw new AssertionError("真实发送入口发生变化，请核对夹具边界");
        Path temporary = Files.createTempDirectory("codex-send-confirm-");
        try {
            Path probe = temporary.resolve("RuntimeSendFailureProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            var compile = new ArrayList<String>();
            compile.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString(), probe.toString()));
            for (String model : new String[]{"DesktopAttachment", "TranscriptText", "TranscriptStore", "AttachmentFiles",
                    "OutboxStore", "BulkTransferCrypto", "AttachmentTransfer", "TranscriptWindow"})
                compile.add(source.resolve(model + ".java").toString());
            String[] stubs = {
                "package org.telegram.messenger; public class UserConfig {public int lastSendMessageId=-1; private static final UserConfig INSTANCE=new UserConfig(); /** 只返回合成账号。 */ public static UserConfig getInstance(int a){return INSTANCE;} /** 分配合成负编号。 */ public int getNewMessageId(){return --lastSendMessageId;} /** 不写真实账号。 */ public void saveConfig(boolean b){} }",
                "package org.telegram.messenger; public class MediaDataController {/** 只返回平台替身。 */ public static MediaDataController getInstance(int a){return new MediaDataController();} /** 不接触真实草稿。 */ public void cleanDraft(long d,int m,boolean b){} }",
                "package org.telegram.messenger; public class FileLoader {/** 读取合成文件名。 */ public static String getDocumentFileName(com.butang.codextop.RuntimeSendFailureProbe.Doc d){return d.name;} }",
                "package android.os; public class SystemClock {/** 使用本机单调时钟替代Android计时。 */ public static long elapsedRealtime(){return System.nanoTime()/1000000;} }"
            };
            String[] files = {"UserConfig", "MediaDataController", "FileLoader", "SystemClock"};
            for (int i = 0; i < files.length; i++) {
                Path file = temporary.resolve(files[i] + ".java"); Files.writeString(file, stubs[i]); compile.add(file.toString());
            }
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实发送夹具编译失败");
            var urls = new ArrayList<java.net.URL>(); urls.add(temporary.toUri().toURL());
            for(String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimeSendFailureProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{temporary.toString()}); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实发送失败回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    // 夹具只提供平台模型、队列及合成远端；发送与落盘算法来自当前生产代码。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;import java.io.*;import java.nio.file.*;import java.util.*;
        public final class RuntimeSendFailureProbe {
            static final class Queue {
                final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
                /** 保留异步顺序以插入晚回执与账号切换。 */
                void postRunnable(Runnable r){tasks.add(r);}
                /** 只执行本次观察，保留下一轮延时边界但不制造轮询。 */void postRunnable(Runnable r,long delay){if(delay<=0)tasks.add(r);}
                /** 执行已排队的本地回调，不使用真实主线程。 */
                void all(){while(!tasks.isEmpty())tasks.remove().run();}
            }
            static final Queue sendQueue=new Queue(),ui=new Queue();static Runnable onUiEnqueue;
            static final class AndroidUtilities {/** 只排入合成界面队列，可在原异步边界注入写失败。 */static void runOnUIThread(Runnable r){if(onUiEnqueue!=null)onUiEnqueue.run();ui.postRunnable(r);}}
            static final class NotificationCenter {
                static final int didReceiveNewMessages=1,updateInterfaces=2,messageReceivedByServer=3,messageSendError=4;
                static final int messagesDidLoad=5; static int notices, reconciled, loaded;
                /** 合成通知不接触真实账号或界面。 */static NotificationCenter getInstance(int a){return new NotificationCenter();}
                /** 只记录可观察的通知次数。 */void postNotificationName(int event,Object...args){notices++;if(event==messageReceivedByServer && !args[0].equals(args[1]))reconciled++;if(event==messagesDidLoad)loaded++;}
            }
            static final class MessagesController {static final int UPDATE_MASK_SEND_STATE=1;}
            static final class TLRPC {
                static class TL_peerUser {long user_id;}
                static class TL_messageMediaEmpty {}
                static class TL_message {int id,local_id,date,flags,send_state;long dialog_id;String message;boolean out,unread;TL_peerUser peer_id,from_id;Object media;HashMap<String,String> params;}
            }
            static final class MessageObject {
                static final int MESSAGE_SEND_STATE_SENDING=1,MESSAGE_SEND_STATE_SENT=0,MESSAGE_SEND_STATE_SEND_ERROR=2;
                final TLRPC.TL_message messageOwner;boolean wasJustSent,attachPathExists,mediaExists;
                /** 保留同一原消息对象以验证晚回执身份守卫。 */MessageObject(int a,TLRPC.TL_message m,boolean x,boolean y){messageOwner=m;}
                /** 返回合成原消息编号。 */int getId(){return messageOwner.id;}
                /** 返回合成原对话编号。 */long getDialogId(){return messageOwner.dialog_id;}
                /** 不处理真实图片，仅满足平台边界。 */void generateThumbs(boolean b){}
            }
            public static final class Doc {public String name,mime_type;}
            static final class SendMessageParams {long peer=1;String path,caption,message;Object photo;Doc document;MessageObject retryMessageObject,replyToTopMsg;}
            static final class AttachmentMessages {
                static void apply(TLRPC.TL_message m,DesktopAttachment a,File f){}
                /** 平台投影只保留真实Pending描述，不改变失败判断。 */static void applyPending(TLRPC.TL_message m,DesktopAttachment.Pending p){m.params.put("codexPendingFile",p.toJson().toString());}
                /** 合成原选择保留附件标识。 */static void applySelected(TLRPC.TL_message m,File f,String n,String k){m.params.put("codexSelectedFile",f==null?"":f.getPath());}
                /** 恢复原选择仅设置附件标识。 */static void applySelected(TLRPC.TL_message m,OutboxStore.Selection s){m.params.put("codexSelectedFile",s.localPath==null?"":s.localPath);}
            }
            static final class DesktopConnection {
                int initCalls,sendCalls,openCalls;int rejectAt=1;String error="File exceeds upload size limit",failStage;Long uploadLimit;boolean connected=true;
                String activeName,publicKey;Runnable onReject,onSend,onLimit;JsonObject transcriptPage,tailPage;
                JsonObject transcript(String remote,String cursor){return transcriptPage;}
                JsonObject transcript(String remote){return transcriptPage;}
                JsonObject readAfter(String remote,String cursor){return tailPage;}
                /** 合成原会话关联，允许验证同字错误不能误标上传失败。 */JsonObject openConversation(String remote)throws IOException{
                    openCalls++;if("open".equals(failStage))throw new IOException(error);JsonObject r=new JsonObject();r.addProperty("sessionId","synthetic-linked");return r;
                }
                /** 只替代连接已读能力，不生成默认值或发出新请求。 */Long attachmentUploadMaxBytes(){if(onLimit!=null){Runnable r=onLimit;onLimit=null;r.run();}return connected?uploadLimit:null;}
                /** 合成远端只提供既有bulk响应，实际AttachmentTransfer负责上传与拒绝。 */JsonObject transfer(String method,JsonObject p)throws Exception{
                    JsonObject r=new JsonObject();r.addProperty("success",true);
                    if(method.endsWith("upload.init")){
                        initCalls++;activeName=p.get("fileName").getAsString();
                        if("upload".equals(failStage)&&initCalls==rejectAt){if(onReject!=null)onReject.run();r.addProperty("success",false);r.addProperty("error",error);return r;}
                        r.addProperty("uploadId","synthetic-upload-"+initCalls);r.addProperty("chunkSizeBytes",65536);r.addProperty("recipientPublicKeyBase64",publicKey);
                    }else if(method.endsWith("upload.finalize")){
                        byte[] bytes=Files.readAllBytes(files.get(activeName));r.addProperty("path","/synthetic/uploaded/"+activeName);r.addProperty("sizeBytes",bytes.length);
                        r.addProperty("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                    }
                    return r;
                }
                /** 本次桌面提交只计次数，未知与明确拒绝保持原异常分支。 */void send(String linked,String text,String localId,List<DesktopAttachment> uploaded)throws Exception{
                    sendCalls++;if(onSend!=null)onSend.run();if("send".equals(failStage))throw new IOException(error);
                    if("reject-send".equals(failStage))throw new SendRejectedException(error);
                }
                static final class RpcNotDispatchedException extends IOException {}
                static final class SendRejectedException extends IOException {/** 合成同字拒绝检查阶段隔离。 */SendRejectedException(String s){super(s);}}
            }
            static long accountGeneration;static Object session;static boolean loggingOut;
            static Path scenarioRoot;static OutboxStore store;static DesktopConnection connection;
            static final Map<String,Path> files=new HashMap<>();
            static final Queue historyQueue=new Queue(),transcriptQueue=new Queue();
            static final class Utilities {static final Queue globalQueue=new Queue();}
            static final class ApplicationLoader {static final Object applicationContext=new Object();}
            static final Map<Long,TranscriptWindow> histories=new HashMap<>();
            static long watchedDialog,watchGeneration;
            static boolean loggedIn(){return session!=null;}
            static void watchStatus(int a,long d,long g){}
            static void refreshDialogs(int a){}
            static void logTranscriptFailure(String phase,Exception e,JsonObject page){throw new AssertionError("unexpected transcript failure at "+phase,e);}
            static TranscriptStore transcriptStore(long dialogId){return new TranscriptStore(scenarioRoot.resolve("history").toFile(),"server","account","machine");}
            static File cachedAttachment(File directory,String id,DesktopAttachment attachment){return null;}
            /** 驱动真实队列的一轮，无网络或真实账号。 */static void flush(){for(int i=0;i<20;i++){Utilities.globalQueue.all();historyQueue.all();transcriptQueue.all();ui.all();if(Utilities.globalQueue.tasks.isEmpty()&&historyQueue.tasks.isEmpty()&&transcriptQueue.tasks.isEmpty()&&ui.tasks.isEmpty())return;}throw new AssertionError("fixture queue loop");}
            static JsonObject page(String id,String localId){JsonObject p=new JsonObject();JsonArray items=new JsonArray();if(id!=null){JsonObject item=new JsonObject();item.addProperty("id",id);item.addProperty("localId",localId);item.addProperty("createdAtMs",1000000);JsonObject raw=new JsonObject(),content=new JsonObject();raw.addProperty("role","user");content.addProperty("type","text");content.addProperty("text","synthetic send");raw.add("content",content);item.add("raw",raw);items.add(item);}p.add("items",items);p.addProperty("hasMore",false);p.addProperty("historyAvailability","available");p.addProperty("tailCursor","synthetic-tail");p.addProperty("nextCursor","synthetic-tail");return p;}
            static void startText(){SendMessageParams p=new SendMessageParams();p.message="synthetic send";sendBatch(0,p,new ArrayList<>(List.of(p)));}

            static final Map<Long,String> remoteIds=new HashMap<>(),dialogDirectories=new HashMap<>(),linkedSessions=new HashMap<>();
            static final Map<Long,String> dialogMachines=new HashMap<>();static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final Map<String,MessageObject> pendingMessages=new HashMap<>();
            static final Set<String> sendingBatches=new HashSet<>();static final Map<String,AttachmentState> attachmentStates=new HashMap<>();
            /** 只为已有合成会话开放归属。 */static boolean ownsConversation(long d){return remoteIds.containsKey(d);}
            /** 真实附件暂存使用独立目录。 */static File attachmentDirectory(long d){return scenarioRoot.resolve("attachments").toFile();}
            /** 返回真实同账号电脑Outbox，避免镜像持久化算法。 */static OutboxStore outboxStore(long d){return store;}
            /** 选择边界提供合成文件，不读取真实手机数据。 */static File selectedAttachment(int a,SendMessageParams p){return p.path==null?null:new File(p.path);}
            /** 此片不测试平台预览，原件存在性由真实暂存模型测试。 */static File attachmentFile(MessageObject m){return null;}
            /** 合成进度不改变失败判定。 */static void attachmentProgress(int a,long e,long d,String r,DesktopConnection c,String k,boolean u,long done,long total){}
            /** 只提供本批气泡的进度关联键。 */static String attachmentKey(MessageObject m){return m.messageOwner.params.get("codexLocalId");}
            /** 不输出正文、路径或服务器错误。 */static void traceSend(String p,String id,long started){}
            /** 每组真实状态使用独立目录及合成账号。 */static void reset(Path root)throws Exception{
                histories.clear();historyQueue.tasks.clear();transcriptQueue.tasks.clear();Utilities.globalQueue.tasks.clear();watchGeneration++;watchedDialog=0;NotificationCenter.reconciled=0;NotificationCenter.loaded=0;
                scenarioRoot=Files.createTempDirectory(root,"case-");accountGeneration++;session=new Object();loggingOut=false;onUiEnqueue=null;
                files.clear();pendingMessages.clear();sendingBatches.clear();attachmentStates.clear();remoteIds.clear();dialogDirectories.clear();linkedSessions.clear();dialogMachines.clear();desktopConnections.clear();ui.tasks.clear();sendQueue.tasks.clear();
                remoteIds.put(1L,"synthetic-thread");dialogDirectories.put(1L,"/synthetic/workspace");store=new OutboxStore(scenarioRoot.resolve("outbox").toFile(),"server","account","machine");connection=new DesktopConnection();
                dialogMachines.put(1L,"synthetic-machine");desktopConnections.put("synthetic-machine",connection);
                try(var recipient=BulkTransferCrypto.createRecipient()){connection.publicKey=recipient.publicKeyBase64;}
            }
            /** 从原选择回调准备两件真实小文件。 */static void start()throws Exception{
                ArrayList<SendMessageParams> selected=new ArrayList<>();for(String name:new String[]{"first.bin","second.bin"}){
                    Path path=scenarioRoot.resolve(name);Files.write(path,new byte[]{1,2,3});files.put(name,path);
                    SendMessageParams p=new SendMessageParams();p.path=path.toString();p.document=new Doc();p.document.name=name;p.document.mime_type="application/octet-stream";selected.add(p);
                }sendBatch(0,selected.get(0),selected);
            }
            /** 取原批次身份，不生成第二个发送编号。 */static String base(){return pendingMessages.values().iterator().next().messageOwner.params.get("codexBatchLocalId");}
            /** 使用实际私有文件定位方法注入写入失败，不仿写Outbox格式。 */static Path record(String id)throws Exception{
                var m=OutboxStore.class.getDeclaredMethod("file",String.class);m.setAccessible(true);return ((File)m.invoke(store,id)).toPath();
            }
            /** 非空临时目录使真实原子write失败，原有效JSON仍保留。 */static void blockWrite(String id)throws Exception{Path block=Path.of(record(id)+".tmp");Files.createDirectories(block);Files.write(block.resolve("block"),new byte[]{1});}
            /** 场景清理写入阻挡后可继续原身份重试。 */static void unblock(String id)throws Exception{Path block=Path.of(record(id)+".tmp");Files.deleteIfExists(block.resolve("block"));Files.deleteIfExists(block);}
            /** 允许修复前读取缺失字段为null，以真实行为断言保存RED。 */static String code(OutboxStore.Item item)throws Exception{
                try{return (String)OutboxStore.Item.class.getField("failureCode").get(item);}catch(NoSuchFieldException absent){return null;}
            }
            /** 只判断结果，不打印任意错误正文。 */static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
            /** 原批次全部失败气泡必须得到同一固定内部code。 */static void expectCode(String expected){for(MessageObject m:pendingMessages.values())check(Objects.equals(expected,m.messageOwner.params.get("codexSendFailure")),"原失败气泡丢失或误标固定原因");}
            /** 只触发原手动重试入口。 */static void retry(){SendMessageParams p=new SendMessageParams();p.retryMessageObject=pendingMessages.values().iterator().next();sendBatch(0,p,new ArrayList<>());}
        """;

    private static final String SCENARIOS = """
            /** 覆盖实际发送、落盘和迟到回调，不重写产品阶段判断。 */
            private static void runFailureCases(Path root)throws Exception{
                String expected="file_too_large";
                reset(root);connection.uploadLimit=2L;start();String oversized=base();sendQueue.all();ui.all();expectCode(expected);
                check(connection.openCalls==0&&connection.initCalls==0&&connection.sendCalls==0&&store.get(oversized).attachments.isEmpty()
                        &&store.get(oversized).selections.size()==2&&expected.equals(code(store.get(oversized))),"已知超限仍暂存、联网或丢原选择");
                for(Long limit:new Long[]{null,3L,4L}){
                    reset(root);connection.uploadLimit=limit;DesktopConnection other=new DesktopConnection();other.uploadLimit=1L;desktopConnections.put("other-machine",other);
                    start();sendQueue.all();ui.all();expectCode(null);check(connection.sendCalls==1&&connection.initCalls==2&&other.openCalls==0&&other.initCalls==0,"旧机未知、小文件或所属电脑绑定改变原发送");
                }
                for(String reason:new String[]{"File exceeds upload size limit","File exceeds the server-routed transfer size limit"}){
                    reset(root);connection.failStage="upload";connection.error=reason;connection.rejectAt=2;start();String id=base();sendQueue.all();ui.all();expectCode(expected);
                    OutboxStore.Item saved=store.get(id);check(expected.equals(code(saved))&&saved.uploaded.size()==1&&!saved.submissionUncertain&&connection.sendCalls==0,"第二件超限丢失原因、上传前缀或进入桌面发送");
                    pendingMessages.clear();ArrayList<MessageObject> restored=restoredPending(0,1,saved,Set.of());check(restored.size()==2,"重开丢附件气泡");expectCode(expected);
                    MessageObject active=restored.get(0);active.messageOwner.params.remove("codexSendFailure");active.messageOwner.send_state=MessageObject.MESSAGE_SEND_STATE_SENDING;
                    restoredPending(0,1,saved,Set.of());check(!active.messageOwner.params.containsKey("codexSendFailure"),"磁盘旧原因覆盖活跃气泡");
                    pendingMessages.clear();check(restoredPending(0,1,saved,Set.of(TranscriptText.attachmentIdentity(id,0),TranscriptText.attachmentIdentity(id,1))).isEmpty(),"已回显批次被恢复");
                }
                reset(root);connection.failStage="upload";connection.rejectAt=2;start();String prefixId=base();sendQueue.all();ui.all();
                connection.uploadLimit=2L;int prefixInit=connection.initCalls;retry();sendQueue.all();ui.all();expectCode(expected);
                check(connection.initCalls==prefixInit&&store.get(prefixId).uploaded.size()==1&&store.get(prefixId).attachments.size()==2,"新限额导致已上传前缀重传或丢失");
                Files.write(Path.of(store.get(prefixId).attachments.get(1).localPath),new byte[]{1});connection.uploadLimit=1L;retry();sendQueue.all();ui.all();
                check(connection.initCalls==prefixInit+1&&connection.sendCalls==0,"已上传超新限额的前缀未跳过或改变文件绕过原摘要校验");
                reset(root);connection.failStage="upload";connection.rejectAt=2;start();Files.write(files.get("second.bin"),new byte[]{1});prefixId=base();sendQueue.all();ui.all();
                String priorPath=store.get(prefixId).uploaded.get(0).path;connection.uploadLimit=1L;connection.failStage=null;prefixInit=connection.initCalls;retry();sendQueue.all();ui.all();expectCode(null);
                check(connection.initCalls==prefixInit+1&&connection.sendCalls==1&&store.get(prefixId).uploaded.size()==2&&store.get(prefixId).uploaded.get(0).path.equals(priorPath),"新小限额拒绝已上传大前缀或替换其路径");
                reset(root);connection.uploadLimit=3L;start();String changed=base();
                onUiEnqueue=()->{onUiEnqueue=null;try{Files.write(files.get("second.bin"),new byte[10]);}catch(IOException e){throw new RuntimeException(e);}};
                sendQueue.all();ui.all();expectCode(expected);check(connection.initCalls==1&&connection.sendCalls==0&&store.get(changed).uploaded.size()==1&&store.get(changed).attachments.get(1).sizeBytes==10,"选择后增长未按最终实际大小拒绝或丢前缀");
                reset(root);connection.uploadLimit=2L;start();String stale=base();connection.onLimit=()->accountGeneration++;sendQueue.all();ui.all();expectCode(null);
                check(code(store.get(stale))==null&&connection.initCalls==0&&connection.sendCalls==0,"旧账号预检写回原因或继续上传");
                for(String stage:new String[]{"upload","open","send","reject-send"}){
                    reset(root);connection.failStage=stage;connection.error=stage.equals("upload")?"synthetic generic error":"File exceeds upload size limit";start();String id=base();sendQueue.all();ui.all();expectCode(null);
                    check(code(store.get(id))==null,"泛错或非上传阶段错误误记大小原因");
                }
                reset(root);connection.failStage="upload";start();String id=base();sendQueue.all();ui.all();expectCode(expected);
                connection.failStage=null;int earlier=connection.initCalls;retry();expectCode(null);sendQueue.all();ui.all();expectCode(null);
                check(connection.initCalls==earlier+2&&connection.sendCalls==1&&code(store.get(id))==null&&store.get(id).submissionAccepted&&!store.get(id).submissionUncertain,"原身份重试未清旧原因或错误提交次数");
                pendingMessages.clear();restoredPending(0,1,store.get(id),Set.of());expectCode(null);int calls=connection.sendCalls;retry();sendQueue.all();ui.all();check(connection.sendCalls==calls,"已接受结果重开后被再次投递");

                reset(root);connection.failStage="upload";start();id=base();sendQueue.all();ui.all();final String clearing=id;blockWrite(id);retry();int before=connection.initCalls;sendQueue.all();ui.all();expectCode(null);
                check(connection.initCalls==before&&connection.sendCalls==0&&expected.equals(code(store.get(id))),"清旧原因写失败仍远端工作或损坏原记录");unblock(clearing);
                reset(root);connection.failStage="upload";start();id=base();final String persisting=id;
                connection.onReject=()->{try{blockWrite(persisting);}catch(Exception e){throw new RuntimeException(e);}};sendQueue.all();ui.all();expectCode(expected);
                check(code(store.get(id))==null&&store.get(id).attachments.size()==2&&connection.sendCalls==0,"记原因写失败损坏原批次或伪装已持久化");unblock(id);

                reset(root);start();id=base();final String beforeSubmit=id;
                onUiEnqueue=()->{try{if(store.get(beforeSubmit).uploaded.size()==2){onUiEnqueue=null;blockWrite(beforeSubmit);}}catch(Exception e){throw new RuntimeException(e);}};
                sendQueue.all();ui.all();expectCode(null);check(connection.sendCalls==0&&!store.get(id).submissionUncertain&&store.get(id).uploaded.size()==2,"未知状态首写失败仍桌面发送或丢上传前缀");unblock(id);
                reset(root);connection.failStage="reject-send";start();id=base();final String rejected=id;
                connection.onSend=()->{try{blockWrite(rejected);}catch(Exception e){throw new RuntimeException(e);}};sendQueue.all();ui.all();expectCode(null);
                check(connection.sendCalls==1&&store.get(id).submissionUncertain,"可靠拒绝后落盘失败误允许重送");
                for(MessageObject m:pendingMessages.values())check("true".equals(m.messageOwner.params.get("codexSendUncertain")),"未知持久化失败丢原界面语义");unblock(id);

                reset(root);connection.failStage="upload";start();id=base();sendQueue.all();int notices=NotificationCenter.notices;accountGeneration++;ui.all();expectCode(null);check(NotificationCenter.notices==notices,"旧账号晚失败回调通知新账号");
                for(boolean replace:new boolean[]{false,true}){
                    reset(root);connection.failStage="upload";start();id=base();sendQueue.all();Map<String,MessageObject> old=new HashMap<>(pendingMessages);pendingMessages.clear();
                    if(replace)for(var entry:old.entrySet())pendingMessages.put(entry.getKey(),new MessageObject(0,pendingMessage(1,-99,1000,"synthetic",id,0),true,false));
                    ui.all();for(MessageObject m:old.values())check(!m.messageOwner.params.containsKey("codexSendFailure"),"晚失败回执改写已移除或替换的原对象");expectCode(null);
                }
                reset(root);connection.failStage="upload";start();id=base();final String late=id;connection.onReject=()->{try{store.remove(late);}catch(Exception e){throw new RuntimeException(e);}};sendQueue.all();ui.all();check(store.get(id)==null,"原因写入复活已回显清理的Outbox");
                reset(root);connection.failStage="upload";start();id=base();connection.onReject=()->accountGeneration++;sendQueue.all();ui.all();expectCode(null);check(code(store.get(id))==null,"旧账号上传结果持久化失败原因");
                var mapper=RuntimeSendFailureProbe.class.getDeclaredMethod("attachmentUploadFailureCode",IOException.class);mapper.setAccessible(true);
                for(IOException error:new IOException[]{new IOException(),new IOException("prefix File exceeds upload size limit"),new IOException("File exceeds upload size limit "),
                        new IOException("synthetic generic error",new IOException("File exceeds upload size limit"))})
                    check(mapper.invoke(null,error)==null,"映射扩大为模糊匹配或递归异常原因");
                System.out.println("RuntimeSendFailure: 真实发送/恢复方法两大小错误、泛错、第二件、重试未知、写盘失败及晚回执账号守卫通过");
            }
            /** 断言只调用真实产品方法，不复制发送、恢复或回显合并决策。 */
            private static void runConfirmationCase(Path root,String test)throws Exception{
                reset(root);
                if("ack-cold".equals(test)){
                    startText();String id=base();sendQueue.all();ui.all();
                    MessageObject accepted=pendingMessages.get(id);check(connection.sendCalls==1&&accepted.messageOwner.send_state==MessageObject.MESSAGE_SEND_STATE_SENT,"fixture: successful ACK did not mark original bubble sent");
                    OutboxStore.Item saved=new OutboxStore(scenarioRoot.resolve("outbox").toFile(),"server","account","machine").get(id);
                    check(saved!=null,"fixture: outbox not retained until echo");
                    // 冷恢复仅丢弃内存，真实待发记录重读后交原恢复方法。
                    pendingMessages.clear();ArrayList<MessageObject> restored=restoredPending(0,1,saved,Set.of());
                    System.out.println("ACK_COLD sendCalls="+connection.sendCalls+" priorSent="+(accepted.messageOwner.send_state==0)+" restoredCount="+restored.size()+" restoredError="+(restored.get(0).messageOwner.send_state==2));
                    check(saved.submissionAccepted&&restored.size()==1&&restored.get(0).messageOwner.send_state==MessageObject.MESSAGE_SEND_STATE_SENT&&!canRetryMessage(restored.get(0)),"ACK success lost across cold restore: original accepted message became SEND_ERROR");
                }else if("unknown-cold".equals(test)){
                    connection.failStage="send";startText();String id=base();sendQueue.all();ui.all();
                    MessageObject unknown=pendingMessages.get(id);check(unknown.messageOwner.send_state==1&&!canRetryMessage(unknown),"unknown send shown as definite error or permitted retry");
                    OutboxStore.Item saved=store.get(id);pendingMessages.clear();MessageObject restored=restoredPending(0,1,saved,Set.of()).get(0);
                    check(restored.messageOwner.send_state==1&&!canRetryMessage(restored),"unknown cold restore lost pending clock");
                    int calls=connection.sendCalls;retry();sendQueue.all();ui.all();check(connection.sendCalls==calls,"unknown cold restore resent same request");
                }else if("ack-connection".equals(test)){
                    startText();String id=base();sendQueue.all();desktopConnections.put("synthetic-machine",new DesktopConnection());ui.all();
                    check(pendingMessages.get(id).messageOwner.send_state==0,"successful ACK reversed by same-account connection replacement");
                }else if("rejected-retry".equals(test)){
                    connection.failStage="reject-send";startText();String id=base();sendQueue.all();ui.all();MessageObject rejected=pendingMessages.get(id);
                    check(rejected.messageOwner.send_state==2&&canRetryMessage(rejected)&&!store.get(id).submissionUncertain,"explicit rejection lost failure/retry");
                    connection.failStage=null;retry();sendQueue.all();ui.all();check(connection.sendCalls==2&&pendingMessages.get(id)==rejected&&rejected.messageOwner.send_state==0,"manual retry changed original identity or failed ACK");
                }else if("ack-late-account".equals(test)){
                    startText();String id=base();sendQueue.all();MessageObject original=pendingMessages.get(id);accountGeneration++;ui.all();
                    check(original.messageOwner.send_state==1,"old account ACK changed visible message");
                }else if("ack-late-remote".equals(test)){
                    startText();String id=base();sendQueue.all();MessageObject original=pendingMessages.get(id);remoteIds.put(1L,"other-synthetic-thread");ui.all();
                    check(original.messageOwner.send_state==1,"old remote ACK changed newly bound conversation");
                }else if("ack-write-failure".equals(test)){
                    startText();String id=base();connection.onSend=()->{try{blockWrite(id);}catch(Exception e){throw new RuntimeException(e);}};
                    sendQueue.all();ui.all();MessageObject acknowledged=pendingMessages.get(id);
                    check(acknowledged.messageOwner.send_state==0&&!canRetryMessage(acknowledged),"ACK persistence failure falsely changed accepted memory state");
                    unblock(id);check(store.get(id).submissionUncertain,"write failure must retain original disk uncertainty");
                    pendingMessages.clear();MessageObject restored=restoredPending(0,1,store.get(id),Set.of()).get(0);
                    check(restored.messageOwner.send_state==1&&!canRetryMessage(restored),"failed persistence cold restore fabricated known failure");
                }else if("partial-attachments".equals(test)){
                    connection.failStage="send";start();String id=base();sendQueue.all();ui.all();OutboxStore.Item saved=store.get(id);
                    String second=TranscriptText.attachmentIdentity(id,1);MessageObject remaining=pendingMessages.get(second);
                    connection.transcriptPage=page("attachment-source",id);loadMessages(0,1,50,0,1,2,0,0);flush();
                    check(store.get(id)!=null&&store.get(id).uploaded.size()==2&&!pendingMessages.containsKey(id)&&pendingMessages.get(second)==remaining&&NotificationCenter.reconciled==1,"first attachment echo lost remaining member or original batch");
                    int firstSourceId=histories.get(1L).before(0,10).get(0).id;
                    connection.tailPage=page("attachment-source:attachment:1",second);watchConversation(0,1);flush();
                    check(store.get(id)==null&&pendingMessages.isEmpty()&&NotificationCenter.reconciled==2&&connection.sendCalls==1,"remaining attachment echo did not complete original batch exactly once");
                    check(histories.get(1L).before(0,10).stream().anyMatch(row->row.id==firstSourceId&&id.equals(row.message.localId)),"echo migration changed original cached source ID");
                }else if("full-attachment-echo".equals(test)){
                    start();String id=base();sendQueue.all();ui.all();OutboxStore.Item saved=store.get(id);
                    JsonObject echo=page("batch-source",id),meta=new JsonObject(),happier=new JsonObject(),payload=new JsonObject();JsonArray attachments=new JsonArray();
                    for(DesktopAttachment attachment:saved.uploaded)attachments.add(attachment.toJson());payload.add("attachments",attachments);
                    happier.addProperty("kind","attachments.v1");happier.add("payload",payload);meta.add("happier",happier);
                    echo.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("raw").add("meta",meta);
                    connection.transcriptPage=echo;loadMessages(0,1,50,0,1,2,0,0);flush();
                    check(store.get(id)==null&&pendingMessages.isEmpty()&&NotificationCenter.reconciled==2&&histories.get(1L).before(0,10).size()==2,"original attachment envelope did not migrate both expanded bubbles");
                }else if("late-ack-after-echo".equals(test)){
                    startText();String id=base();MessageObject original=pendingMessages.get(id);connection.onSend=()->{try{
                        connection.transcriptPage=page("early-echo",id);loadMessages(0,1,50,0,1,2,0,0);flush();
                        check(store.get(id)==null&&!pendingMessages.containsKey(id)&&NotificationCenter.reconciled==1,"fixture: echo failed to precede ACK");
                    }catch(Exception error){throw new RuntimeException(error);}};
                    sendQueue.all();ui.all();check(store.get(id)==null&&!pendingMessages.containsKey(id)&&NotificationCenter.reconciled==1&&connection.sendCalls==1,"late ACK resurrected echoed outbox or negative bubble");
                    check(!original.messageOwner.params.containsKey("codexSendAccepted"),"late ACK modified already migrated original object");
                }else if("echo-isolation".equals(test)){
                    connection.failStage="send";startText();String id=base();sendQueue.all();ui.all();MessageObject original=pendingMessages.get(id);
                    JsonObject agent=page("synthetic-agent",id);agent.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("raw").addProperty("role","agent");
                    connection.transcriptPage=agent;loadMessages(0,1,50,0,1,2,0,0);flush();
                    check(pendingMessages.get(id)==original&&store.get(id)!=null&&NotificationCenter.reconciled==0,"assistant localId falsely confirmed outgoing send");
                    TLRPC.TL_message other=pendingMessage(2,50,1000,"synthetic send",id,0);other.out=true;
                    check(!confirmPendingEcho(0,2,other)&&pendingMessages.get(id)==original,"other dialog migrated original pending bubble");
                }else if("accepted-no-downgrade".equals(test)){
                    startText();String id=base();sendQueue.all();ui.all();MessageObject original=pendingMessages.get(id);
                    finishSend(0,accountGeneration,id,new ArrayList<>(List.of(original)),"synthetic-thread",connection,false,true,null);ui.all();
                    check(original.messageOwner.send_state==0&&!canRetryMessage(original)&&!original.messageOwner.params.containsKey("codexSendUncertain"),"late unknown overwrote accepted state");
                }else{
                    // 已发但ACK未取得，构造原未知失败；随后仅用真实历史／增量方法读同localId回显。
                    connection.failStage="send";startText();String id=base();sendQueue.all();ui.all();
                    MessageObject pending=pendingMessages.get(id);check(pending.messageOwner.send_state!=0,"fixture: unknown send falsely marked accepted");
                    connection.transcriptPage="history-first".equals(test)?page("synthetic-source",id):page(null,null);
                    connection.tailPage=page("synthetic-source",id);
                    loadMessages(0,1,50,0,1,2,0,0);flush();
                    check(NotificationCenter.loaded==1,"fixture: actual history load not delivered");
                    watchConversation(0,1);flush();
                    boolean echoSaved=histories.get(1L).before(0,100).stream().anyMatch(row->id.equals(row.message.localId));
                    System.out.println("ECHO case="+test+" matchingEchoInHistory="+echoSaved+" outboxRemoved="+(store.get(id)==null)+" pendingRemoved="+!pendingMessages.containsKey(id)+" migrationNotifications="+NotificationCenter.reconciled);
                    check(echoSaved&&store.get(id)==null,"fixture: real transcript/outbox did not confirm echo");
                    check(!pendingMessages.containsKey(id)&&NotificationCenter.reconciled==1,"history-first echo never migrated original pending bubble after watch deduplication");
                }
            }
            /** 相邻失败流程和回显确认均运行真实方法；每个场景单独隔离磁盘与账号。 */
            public static void main(String[] args)throws Exception{
                Path root=Path.of(args[0]);runFailureCases(root);
                for(String test:new String[]{"watch-first-control","ack-cold","history-first","unknown-cold","ack-connection","rejected-retry","ack-late-account","ack-late-remote","ack-write-failure","partial-attachments","full-attachment-echo","late-ack-after-echo","echo-isolation","accepted-no-downgrade"})runConfirmationCase(root,test);
                System.out.println("RuntimeSendConfirmation: 14组真实ACK/未知/历史回显/部分附件/迟到隔离通过");
            }
        """;
}
