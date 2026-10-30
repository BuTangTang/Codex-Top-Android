package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.Path;

/** 执行真实发送方法与Outbox，连接恢复只由可控事件触发，不使用网络或设备。 */
public final class RuntimeSendReadinessTest {
    /** 可传入冻结旧Runtime重放RED；原连接等待方法也从生产源码提取。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        var connection = StaticJavaParser.parse(source.resolve("DesktopConnection.java"));
        StringBuilder readiness = new StringBuilder();
        for (MethodDeclaration method : connection.findAll(MethodDeclaration.class))
            if (method.getNameAsString().equals("whenConnected") || method.getNameAsString().equals("completeConnectionWaiters"))
                readiness.append(method).append('\n');
        if (readiness.length() == 0) readiness.append("void completeConnectionWaiters() {}\n");
        String actualReadiness = readiness.toString();
        String scenarios = args.length > 1 && args[1].equals("attachment") ? SCENARIOS.replace("textRecovery(root);", "") : SCENARIOS;
        RuntimeSendQueueTest.run(args.length == 0 ? source.resolve("CodexRuntime.java") : Path.of(args[0]), scenarios, fixture -> {
            fixture = fixture.replace("java.util.concurrent.CompletableFuture<Void> whenConnected(){return connected?java.util.concurrent.CompletableFuture.completedFuture(null):new java.util.concurrent.CompletableFuture<>();}", "")
                    .replace("import com.google.gson.*;", "import com.google.gson.*;import java.util.concurrent.CompletableFuture;")
                    .replace("static final class DesktopConnection {", "static final class DesktopConnection {" + CONNECTION + actualReadiness)
                    .replace("openCalls++;if", "if(!connected)throw new RpcNotDispatchedException();openCalls++;if")
                    .replace("JsonObject r=new JsonObject();r.addProperty(\"success\",true);", "if(!connected)throw new RpcNotDispatchedException();JsonObject r=new JsonObject();r.addProperty(\"success\",true);")
                    .replace("sendCalls++;if", "if(!connected)throw new RpcNotDispatchedException();sentIds.add(localId);sendCalls++;if")
                    .replace("void close(){closeCalls++;connected=false;}", "void close(){closeCalls++;closed=true;connected=false;completeConnectionWaiters();}")
                    .replace("if(delay<=0)tasks.add(r);", "if(delay<=0)tasks.add(r);else delayed.add(r);")
                    .replace("public void cancelRunnable(Runnable r){tasks.remove(r);}", "public void cancelRunnable(Runnable r){tasks.remove(r);delayed.remove(r);}")
                    .replace("static final class Queue extends org.telegram.messenger.DispatchQueue {", "static final class Queue extends org.telegram.messenger.DispatchQueue {final ArrayDeque<Runnable> delayed=new ArrayDeque<>();")
                    .replace("static void reset(Path root)throws Exception{", "static void reset(Path root)throws Exception{sendQueue.delayed.clear();android.os.SystemClock.offset=0;");
            return fixture;
        });
    }

    private static final String CONNECTION = """
        boolean closed;
        final Set<CompletableFuture<Void>> connectionWaiters=new LinkedHashSet<>();
        final List<String> sentIds=new ArrayList<>();
        final Socket socket=new Socket();
        /** 平台socket只提供连接状态，不复制就绪判断。 */
        final class Socket {/** 返回合成连接位。 */boolean connected(){return connected;}}
        /** 原EVENT_CONNECT的就绪消费点单独由连接专项核验。 */
        void recovered(){connected=true;completeConnectionWaiters();}
        """;

    private static final String SCENARIOS = """
        /** 创建一条独立合成正文并返回原批次编号。 */
        static String text()throws Exception{startText();return newest();}
        /** 只根据负编号选出本轮气泡，不按正文猜身份。 */
        static String newest(){MessageObject last=null;for(MessageObject m:pendingMessages.values())if(last==null||m.getId()<last.getId())last=m;return last.messageOwner.params.get("codexBatchLocalId");}
        /** 两条文字在离线期间先落盘，恢复后原编号各发一次且保持顺序。 */
        static void textRecovery(Path root)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.connected=false;String first=text();drainSend();ui.all();
            check(store.get(first)!=null&&!store.get(first).submissionUncertain&&owner.sendCalls==0,"offline text not durably held before RPC");
            check(pendingMessages.get(first).messageOwner.send_state==1&&!sendJobs.isEmpty(),"offline new text failed before CONNECT");
            String second=text();drainSend();ui.all();check(store.get(second)!=null&&owner.openCalls==0&&owner.initCalls==0,"wait blocked persistence or emitted before CONNECT");
            owner.recovered();drainSend();ui.all();
            check(store.get(first).submissionAccepted&&store.get(second).submissionAccepted&&owner.sentIds.equals(Arrays.asList(first,second)),"CONNECT lost identity or reordered same-dialog texts");
            owner.recovered();drainSend();check(owner.sendCalls==2&&owner.connectionWaiters.isEmpty()&&sendJobs.isEmpty()&&sendQueue.delayed.isEmpty(),"duplicate CONNECT resent or retained waiters/deadline");
            System.out.println("READY_TEXT persistedBeforeConnect=true originalFIFO=true exactlyOnce=true");
        }
        /** 附件与后发文字共用准入等待，恢复后文字不等待原附件队列。 */
        static void attachmentRecovery(Path root)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.connected=false;start();String image=base();String next=text();drainSend();ui.all();
            check(store.get(image).selections.size()==2&&owner.initCalls==0&&owner.openCalls==0,"attachment emitted or selection lost while offline");
            check(pendingMessages.get(image).messageOwner.send_state==1,"offline new attachment failed before CONNECT");
            owner.recovered();sendQueue.all();ui.all();check(store.get(next).submissionAccepted&&owner.initCalls==0,"waiting attachment blocked recovered text");
            uploadQueue.all();sendQueue.all();ui.all();check(store.get(image).submissionAccepted&&owner.initCalls==2&&owner.imageSendCalls==1&&owner.textSendCalls==1,"attachment preparation or send duplicated");
            check(owner.connectionWaiters.isEmpty()&&sendJobs.isEmpty()&&sendQueue.delayed.isEmpty(),"attachment retained readiness state");
            System.out.println("READY_ATTACHMENT originalSelection=true textIndependent=true eachSendOnce=true");
        }
        /** 超时、删除、登出及归属变化结束原等待，迟到CONNECT不重建记录或发出RPC。 */
        static void ended(Path root,String mode)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.connected=false;String id=text();drainSend();ui.all();MessageObject original=pendingMessages.get(id);
            if(mode.equals("timeout")){check(!sendQueue.delayed.isEmpty(),"no finite deadline");sendQueue.delayed.removeFirst().run();}
            else if(mode.equals("delete"))deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(original.getId())));
            else if(mode.equals("account"))accountGeneration++;
            else if(mode.equals("remote"))remoteIds.put(1L,"synthetic-rebound");
            else if(mode.equals("connection"))desktopConnections.put("synthetic-machine",new DesktopConnection());
            else if(mode.equals("close"))owner.close();
            else {logout(()->{},message->{throw new AssertionError("synthetic logout failed");});Utilities.globalQueue.all();dialogQueue.all();sendQueue.all();uploadQueue.all();approvalQueue.all();transcriptQueue.all();historyQueue.all();statusQueue.all();prefetchQueue.all();Utilities.globalQueue.all();ui.all();}
            sendQueue.all();ui.all();if(mode.equals("delete")||mode.equals("timeout")||mode.equals("close")||mode.equals("logout"))check(owner.connectionWaiters.isEmpty(),"terminal did not cancel readiness immediately: "+mode);
            owner.recovered();drainSend();ui.all();check(owner.sendCalls==0&&owner.openCalls==0&&owner.initCalls==0&&owner.connectionWaiters.isEmpty()&&sendQueue.delayed.isEmpty()&&sendJobs.isEmpty(),"late CONNECT dispatched or leaked: "+mode);
            if(mode.equals("delete"))check(store.get(id)==null&&pendingMessages.isEmpty(),"deleted wait resurrected");
            else check(!store.get(id).submissionAccepted&&!store.get(id).submissionUncertain,"unsent wait became accepted/unknown");
            System.out.println("READY_END mode="+mode+" noLateRpc=true cleaned=true");
        }
        /** 等待期间到达的接受或未知事实优先于新就绪事件，不再进入发送。 */
        static void preservedFacts(Path root,boolean accepted)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.connected=false;String id=text();drainSend();
            if(accepted)store.markSubmissionAccepted(id);else store.markSubmissionUncertain(id,true);
            owner.recovered();drainSend();ui.all();check(owner.sendCalls==0&&owner.openCalls==0&&sendJobs.isEmpty(),"CONNECT resubmitted accepted/uncertain identity");
            check(accepted?store.get(id).submissionAccepted:store.get(id).submissionUncertain,"durable delivery fact changed");
            System.out.println("READY_FACT accepted="+accepted+" noResubmit=true");
        }
        /** 新批次恢复后已派发但结果未知，后续CONNECT不能擅自再投。 */
        static void unknown(Path root)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.connected=false;owner.failStage="send";owner.error="synthetic timeout";String id=text();drainSend();owner.recovered();drainSend();ui.all();
            check(store.get(id).submissionUncertain&&owner.sendCalls==1,"dispatched uncertainty changed");owner.recovered();drainSend();check(owner.sendCalls==1&&sendJobs.isEmpty(),"unknown auto resent on CONNECT");
            System.out.println("READY_UNKNOWN originalUncertain=true noAutoRetry=true");
        }
        /** CONNECT已到但发送队列迟处理时仍受绝对期限约束；成功通知后断线也不能派发。 */
        static void delayedContinuation(Path root,boolean expired)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.connected=false;String id=text();drainSend();owner.recovered();
            if(expired)android.os.SystemClock.offset=26000;else owner.close();
            drainSend();ui.all();check(owner.sendCalls==0&&owner.openCalls==0&&pendingMessages.get(id).messageOwner.send_state==2,"late continuation dispatched after deadline/close");
            check(!store.get(id).submissionUncertain&&owner.connectionWaiters.isEmpty()&&sendJobs.isEmpty()&&sendQueue.delayed.isEmpty(),"late continuation retained waiter or invented uncertainty");
            System.out.println("READY_LATE expired="+expired+" noDispatch=true cleaned=true");
        }
        /** 各场景使用独立临时Outbox与固定事件，无真实等待或连接。 */
        public static void main(String[] args)throws Exception{Path root=Paths.get(args[0]);textRecovery(root);attachmentRecovery(root);for(String mode:new String[]{"timeout","delete","account","remote","connection","close","logout"})ended(root,mode);preservedFacts(root,false);preservedFacts(root,true);unknown(root);delayedContinuation(root,true);delayedContinuation(root,false);System.out.println("RuntimeSendReadiness: 14组真实Runtime/Outbox恢复准入与终态边界通过");}
        """;
}
