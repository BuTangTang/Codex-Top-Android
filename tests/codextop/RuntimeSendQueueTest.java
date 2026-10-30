package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import javax.tools.ToolProvider;

/** 真实发送/删除/登出方法的队列回归；只有Android与RPC边界被替代，磁盘状态使用真实Outbox。 */
public final class RuntimeSendQueueTest {
    /** 使用Java8编译真实方法，全部屏障都有上限，不接触设备、网络或真实账号。 */
    public static void main(String[] args) throws Exception {
        run(Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"), SCENARIOS, value -> value);
    }

    /** 新专项复用相同真实方法编译边界；只替换合成RPC和场景，不复制产品决策。 */
    static void run(Path runtimeSource, String scenarios, java.util.function.UnaryOperator<String> transform) throws Exception {
        Path source=Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit=StaticJavaParser.parse(runtimeSource);
        var names=new HashSet<>(Arrays.asList("sendBatch","prepareSend","submitPreparedSend","admitPreparedSends","startPreparedSend","linkedPreparedSend","dispatchPreparedSend","completePreparedSend","sendJobCurrent","finishSend","pendingMessage",
                "canRetryMessage","isAccountCurrent","attachmentCurrent","transfer","attachmentProgress","isAttachmentMessage",
                "attachmentUploadFailureCode","dialogConnection","attachmentUploadMaxBytes","attachmentTooLarge","deletePendingMessages","logout","restoredPending","batchEchoed"));
        if(unit.findAll(MethodDeclaration.class).stream().anyMatch(method->method.getNameAsString().equals("prepareWhenConnected")))names.add("prepareWhenConnected");
        boolean asynchronous=unit.findAll(MethodDeclaration.class).stream().anyMatch(method->method.getNameAsString().equals("startPreparedSend"));
        if(!asynchronous)names.removeAll(Arrays.asList("admitPreparedSends","startPreparedSend","linkedPreparedSend","dispatchPreparedSend","completePreparedSend"));
        StringBuilder methods=new StringBuilder();int extracted=0;
        for(MethodDeclaration method:unit.findAll(MethodDeclaration.class))if(names.contains(method.getNameAsString())){
            methods.append(method.toString().replace("org.telegram.messenger.MessageObject","MessageObject").replace("CodexRuntime.class","RuntimeSendQueueProbe.class").replace("CodexRuntime::","RuntimeSendQueueProbe::")).append('\n');extracted++;
        }
        for(ClassOrInterfaceDeclaration type:unit.findAll(ClassOrInterfaceDeclaration.class))if(type.getNameAsString().equals("AttachmentState")||type.getNameAsString().equals("PreparedSend"))methods.append(type).append('\n');
        // 异步清理许可提取实际字段，不能由夹具重置或放宽生产上限。
        for(var field:unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))
            if(field.getVariables().stream().anyMatch(v->v.getNameAsString().equals("uploadAbortInFlight")))methods.append(field).append('\n');
        if(extracted!=names.size()+2)throw new AssertionError("真实队列方法或重载边界变化");
        Field field=RuntimeSendFailureTest.class.getDeclaredField("FIXTURE");field.setAccessible(true);
        String fixture=((String)field.get(null)).replace("RuntimeSendFailureProbe","RuntimeSendQueueProbe");
        // 发送专项不执行历史视图方法，仅为真实logout提供当前字段；不复制历史决策。
        fixture=fixture.replace("Map<Integer,HistoryView> historyViews", "Map<Integer,Object> historyViews")
                .replace("historyQueue=new Queue(),transcriptQueue=new Queue(),prefetchQueue=new Queue()", "historyQueue=new Queue(),transcriptQueue=new Queue()");
        fixture=fixture.replace("static final class Queue {","static final class Queue extends org.telegram.messenger.DispatchQueue {")
                .replace("final ArrayDeque<Runnable> tasks=new ArrayDeque<>();","final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tasks=new java.util.concurrent.ConcurrentLinkedQueue<>();")
                .replace("void postRunnable(","public void postRunnable(")
                .replace("void cancelRunnable(","public void cancelRunnable(")
                .replace("void all(){while(!tasks.isEmpty())tasks.remove().run();}","void all(){for(Runnable r;(r=tasks.poll())!=null;)r.run();} public void cancelRunnable(Runnable r){tasks.remove(r);}");
        fixture=fixture.replace("static final int messagesDidLoad=5;","static final int messagesDidLoad=5,messagesDeleted=6;")
                .replace("static int notices, reconciled, loaded;","static int notices, reconciled, loaded,deleted;")
                .replace("if(event==messagesDidLoad)loaded++;","if(event==messagesDidLoad)loaded++;if(event==messagesDeleted)deleted++;");
        fixture=fixture.replace("static final class MessagesController {static final int UPDATE_MASK_SEND_STATE=1;}","static final class MessagesController {static final int UPDATE_MASK_SEND_STATE=1,UPDATE_MASK_MESSAGE_TEXT=2;static final MessagesController INSTANCE=new MessagesController();final Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();/** 仅返回合成列表边界。 */static MessagesController getInstance(int a){return INSTANCE;}}");
        fixture=fixture.replace("int initCalls,sendCalls,openCalls;","volatile int initCalls,sendCalls,openCalls,textSendCalls,imageSendCalls,chunkCalls,abortCalls,closeCalls;final List<Integer> uploadChunkBytes=new ArrayList<>(),uploadChunkIndexes=new ArrayList<>();Runnable uploadBarrier;String barrierMethod=\"init\";")
                .replace("initCalls++;activeName=","initCalls++;if(uploadBarrier!=null&&barrierMethod.equals(\"init\"))uploadBarrier.run();activeName=")
                .replace("}else if(method.endsWith(\"upload.finalize\")){","}else if(method.endsWith(\"upload.chunk\")){chunkCalls++;uploadChunkBytes.add(java.util.Base64.getDecoder().decode(p.get(\"payloadBase64\").getAsString()).length-29);uploadChunkIndexes.add(p.get(\"index\").getAsInt());if(uploadBarrier!=null&&barrierMethod.equals(\"chunk\"))uploadBarrier.run();}else if(method.endsWith(\"upload.abort\")){abortCalls++;}else if(method.endsWith(\"upload.finalize\")){")
                .replace("sendCalls++;if(onSend","sendCalls++;if(uploaded.isEmpty())textSendCalls++;else imageSendCalls++;if(onSend")
                .replace("static final class RpcNotDispatchedException", "/** 模拟关连接，不自动释放测试屏障，以验证登出确实等待上传队列。 */void close(){closeCalls++;connected=false;} static final class RpcNotDispatchedException");
        fixture=fixture.replace("static Object session;","static FakeSession session;").replace("session=new Object()","session=new FakeSession()");
        fixture=fixture.replace("try(var recipient=BulkTransferCrypto.createRecipient())","try(BulkTransferCrypto.Recipient recipient=BulkTransferCrypto.createRecipient())")
                .replace("var m=OutboxStore.class.getDeclaredMethod","java.lang.reflect.Method m=OutboxStore.class.getDeclaredMethod")
                .replace("Path.of(","Paths.get(").replace("List.of(p)","Arrays.asList(p)")
                .replace("HexFormat.of().formatHex(java.security.MessageDigest.getInstance(\"SHA-256\").digest(bytes))","hex(java.security.MessageDigest.getInstance(\"SHA-256\").digest(bytes))");
        fixture=fixture.replace("static void attachmentProgress(int a,long e,long d,String r,DesktopConnection c,String k,boolean u,long done,long total){}","")
                .replace("static void attachmentProgress(int a,long e,long d,String r,DesktopConnection c,java.util.concurrent.atomic.AtomicBoolean active,String k,boolean u,long done,long total){}","");
        if(!asynchronous)fixture=fixture.replace("static final Map<Long,ArrayDeque<PreparedSend>> preparedSends=new HashMap<>();", "static final Map<Long,Object> preparedSends=new HashMap<>();");
        fixture=transform.apply(fixture+BOUNDARIES);
        Path temporary=Files.createTempDirectory("codex-send-queue-");
        try{
            Path probe=temporary.resolve("RuntimeSendQueueProbe.java");Files.writeString(probe,fixture+methods+scenarios+"\n}");
            var compile=new ArrayList<String>(Arrays.asList("--release","8","-cp",System.getProperty("java.class.path"),"-d",temporary.toString(),probe.toString()));
            for(String model:new String[]{"DesktopAttachment","TranscriptText","TranscriptStore","AttachmentFiles","OutboxStore","BulkTransferCrypto","AttachmentTransfer","TranscriptWindow","TranscriptTailRecovery"})compile.add(source.resolve(model+".java").toString());
            String[] stubs={
                "package org.telegram.messenger;public class UserConfig {public static int selectedAccount;public int lastSendMessageId=-1;private static final UserConfig INSTANCE=new UserConfig();public static UserConfig getInstance(int a){return INSTANCE;}public int getNewMessageId(){return --lastSendMessageId;}public void saveConfig(boolean b){} }",
                "package org.telegram.messenger;public class MediaDataController {public static MediaDataController getInstance(int a){return new MediaDataController();}public void cleanDraft(long d,int m,boolean b){} }",
                "package org.telegram.messenger;public class FileLoader {public static String getDocumentFileName(com.butang.codextop.RuntimeSendQueueProbe.Doc d){return d.name;} }",
                "package android.os;public class SystemClock {public static long offset;public static long elapsedRealtime(){return System.nanoTime()/1000000+offset;} }",
                "package org.telegram.messenger;public class BuildVars {public static final boolean DEBUG_VERSION=false;}",
                "package android.util;public class Log {public static int w(String t,String m){return 0;}public static int i(String t,String m){return 0;} }",
                "package android.widget;public class Toast {public static final int LENGTH_SHORT=0;public static Toast makeText(Object c,CharSequence t,int d){return new Toast();}public void show(){} }",
                "package org.telegram.messenger;public class DispatchQueue {public void postRunnable(Runnable r){}public void postRunnable(Runnable r,long d){}public void cancelRunnable(Runnable r){} }"
            };
            String[] files={"UserConfig","MediaDataController","FileLoader","SystemClock","BuildVars","Log","Toast","DispatchQueue"};
            for(int i=0;i<files.length;i++){Path file=temporary.resolve(files[i]+".java");Files.writeString(file,stubs[i]);compile.add(file.toString());}
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))!=0)throw new AssertionError("真实Java8发送队列夹具编译失败");
            var urls=new ArrayList<java.net.URL>();urls.add(temporary.toUri().toURL());for(String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator))urls.add(Path.of(entry).toUri().toURL());
            try(var loader=new URLClassLoader(urls.toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())){
                try{loader.loadClass("com.butang.codextop.RuntimeSendQueueProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{temporary.toString()});}
                catch(java.lang.reflect.InvocationTargetException error){throw new AssertionError("真实发送队列回归失败",error.getCause());}
            }
        }finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}}
    }

    private static final String BOUNDARIES="""
        /** 这些字段只满足真实logout的合成状态边界，不接触手机账号或数据库。 */
        static final Queue dialogQueue=new Queue(),approvalQueue=new Queue(),statusQueue=new Queue(),prefetchQueue=new Queue();
        static final class FakeSession {boolean closed;/** 只记录合成凭据释放。 */void close(){closed=true;}}
        static final class SessionStore {/** 不持有真实上下文。 */SessionStore(Object context){} /** 不写真实凭据。 */void clear(){}}
        static final class Statuses {/** 合成失败恢复边界。 */void rearmUnconfirmedGoalRestore(){} /** 不读真实状态。 */void clear(){}}
        static final Statuses statuses=new Statuses();static boolean listRefreshScheduled,loading,connectedComputersPrefetching,sessionRestored,cachedDialogsRead;
        static Object desktop,accountUsageMachine,preferredMachine,dialogIdentities,loadError;static long accountUsageRestoredEpoch=-1;static Runnable statusPoll;
        static final Object dialogIdentityLock=new Object();static final List<Object> dialogs=new ArrayList<>();
        static final Map<String,Object> accountUsageSnapshots=new HashMap<>(),accountUsageReads=new HashMap<>(),browseSnapshots=new HashMap<>(),coldHistoryRequests=new HashMap<>(),attachmentGroups=new HashMap<>(),attachmentCallbacks=new HashMap<>(),dialogTitles=new HashMap<>(),machineNames=new HashMap<>(),prefetchedRevisions=new HashMap<>();static final Set<String> prefetching=new HashSet<>();
        /** 合成租约不连接真实机器。 */static void releaseObservation(){}
        /** 开关只由合成归属开启。 */static boolean enabled(){return true;}
        /** 平台摘要恢复边界不重新读取历史。 */static void restoreDialogPreviews(int a,long e,String m,Map<String,Long> p){}
        /** 合成远端摘要采用原SHA256字节编码。 */static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format("%02x",value&255));return b.toString();}
        """;

    private static final String SCENARIOS="""
        /** 每组清空合成队列；不得在仍有工作线程时切换夹具。 */
        static void resetCase(Path root)throws Exception{reset(root);dialogQueue.tasks.clear();approvalQueue.tasks.clear();statusQueue.tasks.clear();prefetchQueue.tasks.clear();NotificationCenter.deleted=0;MessagesController.INSTANCE.dialogMessage.clear();}
        /** 提供独立256KiB图片，真实暂存与传输仍使用流式读取。 */
        static String image()throws Exception{Path file=scenarioRoot.resolve("image.jpg");byte[] b=new byte[256*1024];Arrays.fill(b,(byte)71);Files.write(file,b);files.put("image.jpg",file);SendMessageParams p=new SendMessageParams();p.photo=new Object();p.path=file.toString();p.caption="synthetic image";sendBatch(0,p,new ArrayList<>(Arrays.asList(p)));return newest();}
        /** 真实时钟下不冻结块数；核对RPC实际字节（GCM封套固定29字节）、连续index及既有自适应范围。 */
        static void verifyImageChunks(){
            check(connection.uploadChunkBytes.size()==connection.chunkCalls&&!connection.uploadChunkBytes.isEmpty()&&connection.uploadChunkBytes.get(0)==8192,"first chunk or observed RPC count changed");
            int remaining=256*1024,previous=8192;
            for(int i=0;i<connection.uploadChunkBytes.size();i++){
                int bytes=connection.uploadChunkBytes.get(i);boolean last=i==connection.uploadChunkBytes.size()-1;
                check(connection.uploadChunkIndexes.get(i)==i&&bytes>0&&bytes<=65536&&bytes<=remaining&&bytes<=previous*4L,"chunk sequence, server cap or growth changed");
                if(!last)check(bytes>=1024,"non-final chunk below adaptive floor");remaining-=bytes;previous=bytes;
            }
            check(remaining==0,"actual uploaded chunk bytes did not cover complete original");
        }
        /** 负编号递减，读取本次创建的原气泡身份。 */
        static String newest(){MessageObject last=null;for(MessageObject m:pendingMessages.values())if(last==null||m.getId()<last.getId())last=m;return last.messageOwner.params.get("codexBatchLocalId");}
        /** 有限屏障只替代upload.init远端等待。 */
        static final class Barrier {final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();Thread worker;String stage="init";
            /** 在原RPC边界等待，超时算夹具失败。 */void stop(){entered.countDown();try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
            /** 唯一上传线程执行FIFO；原发送队列仍由合成UI调用方推进。 */void start()throws Exception{connection.uploadBarrier=this::stop;connection.barrierMethod=stage;sendQueue.all();worker=new Thread(()->{try{uploadQueue.all();}catch(Throwable e){failure.set(e);}},"synthetic-upload");worker.start();check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"upload missed barrier");}
            /** 释放本轮并确保所有线程退出后再验收或切场景。 */void finish()throws Exception{release.countDown();worker.join(2000);check(!worker.isAlive()&&failure.get()==null,"worker failed or blocked");sendQueue.all();ui.all();}
        }
        /** 图片仍在上传时文字必须独立落盘和派发；图片失败也不能拖住文字。 */
        static void textDuringImage(Path root,boolean imageFails)throws Exception{resetCase(root);String image=image();if(imageFails)connection.failStage="upload";Barrier b=new Barrier();b.start();startText();String text=newest();sendQueue.all();ui.all();check(store.get(text).submissionAccepted&&connection.textSendCalls==1&&connection.imageSendCalls==0,"image blocked text persistence/send");b.finish();check(connection.textSendCalls==1&&connection.imageSendCalls==(imageFails?0:1)&&sendJobs.isEmpty(),"image/text duplicate or job retained");if(!imageFails){verifyImageChunks();check(store.get(image).submissionAccepted&&connection.openCalls==1,"image identity or session ensure changed");}System.out.println("TEXT_DURING_IMAGE failed="+imageFails+" textBeforeRelease=true eachSendOnce=true");}
        /** 删除可在首写后、init在途或上传完成待派发时执行，不能复活记录或发最终消息。 */
        static void deleteCase(Path root,String stage)throws Exception{resetCase(root);String image=image();int id=pendingMessages.get(image).getId();if(stage.equals("queued")){deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(id)));drainSend();ui.all();}else if((stage.equals("inflight")||stage.equals("chunk"))){Barrier b=new Barrier();if(stage.equals("chunk"))b.stage="chunk";b.start();deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(id)));sendQueue.all();ui.all();b.finish();check(connection.abortCalls==1&&connection.chunkCalls==(stage.equals("chunk")?1:0),"cancelled init lost original abort or continued chunks");}else{sendQueue.all();deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(id)));uploadQueue.all();sendQueue.all();ui.all();}check(store.get(image)==null&&connection.sendCalls==0&&pendingMessages.isEmpty()&&sendingBatches.isEmpty()&&sendJobs.isEmpty()&&NotificationCenter.deleted==1,"deleted batch recreated, dispatched or retained");System.out.println("DELETE stage="+stage+" outboxAbsent=true sendCalls=0");}
        /** 账号、会话或连接在原RPC等待期间变化，旧作业不得落回新归属。 */
        static void replaceCase(Path root,String kind)throws Exception{resetCase(root);String image=image();Barrier b=new Barrier();b.start();if(kind.equals("account"))accountGeneration++;else if(kind.equals("remote"))remoteIds.put(1L,"other-synthetic-thread");else desktopConnections.put("synthetic-machine",new DesktopConnection());b.finish();check(connection.imageSendCalls==0&&store.get(image).uploaded.isEmpty()&&sendJobs.isEmpty(),"old work persisted uploads or sent after replacement");System.out.println("REPLACE kind="+kind+" uploaded=0 sendCalls=0");}
        /** 原失败批次只上传未完成后缀；未知或已接受批次点击重试都不能重传或重投。 */
        static void identityCases(Path root)throws Exception{resetCase(root);connection.failStage="upload";connection.rejectAt=2;start();String id=base();drainSend();ui.all();check(store.get(id).uploaded.size()==1,"uploaded prefix missing");String path=store.get(id).uploaded.get(0).path;int inits=connection.initCalls;connection.failStage=null;retry();drainSend();ui.all();check(connection.initCalls==inits+1&&connection.sendCalls==1&&store.get(id).uploaded.get(0).path.equals(path),"prefix retransmitted or identity changed");retry();drainSend();ui.all();check(connection.sendCalls==1,"accepted batch resent");resetCase(root);connection.failStage="send";id=image();drainSend();ui.all();check(store.get(id).submissionUncertain&&store.get(id).uploaded.size()==1,"unknown upload identity lost");int calls=connection.sendCalls;inits=connection.initCalls;retry();drainSend();ui.all();check(connection.sendCalls==calls&&connection.initCalls==inits,"unknown batch retransmitted or resent");connection.failStage=null;startText();drainSend();ui.all();check(connection.textSendCalls==1&&connection.sendCalls==calls+1,"unknown image blocked new text");System.out.println("IDENTITY prefixReuse=true acceptedNoResend=true unknownNoResend=true newTextIndependent=true");}
        /** 执行真实logout，其他队列均已离开后仍等待唯一上传队列，最后才释放会话并清作业。 */
        static void logoutCase(Path root)throws Exception{resetCase(root);image();Barrier b=new Barrier();b.start();FakeSession original=session;java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();logout(()->done.set(true),s->{throw new AssertionError("logout failed");});Utilities.globalQueue.all();dialogQueue.all();sendQueue.all();approvalQueue.all();transcriptQueue.all();historyQueue.all();statusQueue.all();prefetchQueue.all();Utilities.globalQueue.all();ui.all();check(!done.get()&&!original.closed&&connection.closeCalls==1,"logout did not wait upload execution");b.finish();ui.all();check(done.get()&&original.closed&&session==null&&sendJobs.isEmpty()&&pendingMessages.isEmpty()&&connection.sendCalls==0,"logout finished with old job/session/send");System.out.println("LOGOUT uploadBarrierWaited=true oldSessionClosed=true sendCalls=0 jobs=0");}
        /** 全部数据与身份独立；只输出协议阶段和计数，不记录正文或本机路径。 */
        public static void main(String[] args)throws Exception{Path root=Paths.get(args[0]);textDuringImage(root,false);textDuringImage(root,true);for(String stage:new String[]{"queued","inflight","chunk","ready"})deleteCase(root,stage);for(String kind:new String[]{"account","remote","connection"})replaceCase(root,kind);identityCases(root);logoutCase(root);System.out.println("RuntimeSendQueue: 11组真实队列/取消/归属/未知/登出边界通过");}
        """;
}
