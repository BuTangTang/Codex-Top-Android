package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 使用真实Java8发送方法与原上传/ACK屏障，阶段观测不改排队和提交事实。 */
public final class RuntimeSendPhaseTraceTest {
    /** 可指定私有前像重放缺阶段的RED，不连接真实账号、设备或网络。 */
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source);
        MethodDeclaration trace = unit.findAll(MethodDeclaration.class).stream()
                .filter(value -> value.getNameAsString().equals("traceSend")).findFirst().orElseThrow();
        String actualTrace = trace.toString().replace("org.telegram.messenger.BuildVars", "TraceBuildVars")
                .replace("android.util.Log", "TraceLog");
        Method holdRpc = RuntimeSendAsyncQueueTest.class.getDeclaredMethod("holdRpc", String.class);
        holdRpc.setAccessible(true);
        String async = scenarios(RuntimeSendAsyncQueueTest.class);
        String upload = scenarios(RuntimeSendQueueTest.class);
        String helpers = async.substring(0, async.indexOf("/** 阻ACK"))
                + upload.substring(upload.indexOf("/** 提供独立256KiB图片"), upload.indexOf("/** 图片仍在上传"));
        RuntimeSendQueueTest.run(source, helpers + SCENARIOS, fixture -> {
            try {
                fixture = (String) holdRpc.invoke(null, fixture);
                String emptyTrace = "static void traceSend(String p,String id,long started){}";
                if (!fixture.contains(emptyTrace)) throw new AssertionError("原阶段夹具边界变化");
                return fixture.replace(emptyTrace, actualTrace + TRACE_BOUNDARY)
                        .replace("static void reset(Path root)throws Exception{",
                                "static void reset(Path root)throws Exception{TraceLog.messages.clear();TraceBuildVars.DEBUG_VERSION=true;");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        Set<String> expected = new HashSet<>(Arrays.asList("queue", "persisted", "prepared", "admitted", "link_ack", "linked", "ack", "wait_ready", "ready"));
        Set<String> actual = new HashSet<>();
        int calls = 0;
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("traceSend")) continue;
            if (!call.getArgument(0).isStringLiteralExpr()) throw new AssertionError("发送阶段必须为固定字面量");
            if (!actual.add(call.getArgument(0).asStringLiteralExpr().asString())) throw new AssertionError("阶段重复埋点");
            calls++;
        }
        if (calls != 9 || !actual.equals(expected)) throw new AssertionError("原发送阶段或连接等待阶段丢失");
        System.out.println("RuntimeSendPhaseTrace: 6组真实Java8方法阶段/上传/ACK/关联/归属/DEBUG及9个固定埋点通过");
    }

    /** 复用原测试屏障文本，不改原测试或复制产品决策。 */
    private static String scenarios(Class<?> owner) throws Exception {
        Field field = owner.getDeclaredField("SCENARIOS");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static final String TRACE_BOUNDARY = """
        /** 只替代Android DEBUG开关和Log接收端，真实traceSend方法不改格式。 */
        static final class TraceBuildVars {static boolean DEBUG_VERSION=true;}
        static final class TraceLog {
            static final java.util.concurrent.ConcurrentLinkedQueue<String> messages=new java.util.concurrent.ConcurrentLinkedQueue<>();
            static int i(String tag,String message){check(tag.equals("CodexBridge"),"trace tag changed");messages.add(message);return 0;}
        }
        static int phases(String phase,String id){int result=0;String prefix="send_phase="+phase+" key="+id.hashCode()+" elapsedMs=";for(String value:TraceLog.messages)if(value.startsWith(prefix))result++;return result;}
        static void phase(String name,String id,int count){check(phases(name,id)==count,"incorrect actual phase "+name+" count");}
        static void grammar(){for(String value:TraceLog.messages)check(value.matches("send_phase=(queue|persisted|prepared|admitted|link_ack|linked|ack|wait_ready|ready) key=-?[0-9]+ elapsedMs=[0-9]+"),"trace exposed content or changed fixed fields");}
        """;

    private static final String SCENARIOS = """
        /** 图片被原上传屏障阻住时，文字已有首写、准备和ACK；图片不能提前记prepared。 */
        static void uploadPhases(Path root)throws Exception {
            reset(root);String image=image();Barrier barrier=new Barrier();barrier.start();
            try {
                phase("queue",image,1);phase("persisted",image,1);phase("prepared",image,0);phase("admitted",image,0);
                String text=text(1);sendQueue.all();ui.all();
                check(store.get(text).submissionAccepted&&connection.textSendCalls==1&&connection.imageSendCalls==0,"image prevented text persist/prepare/send");
                for(String name:new String[]{"queue","persisted","prepared","admitted","link_ack","linked","ack"})phase(name,text,1);
                phase("prepared",image,0);phase("admitted",image,0);
            } finally {barrier.finish();}
            check(store.get(image).isPrepared()&&store.get(image).uploaded.size()==1&&store.get(image).submissionAccepted,"image prepared mark preceded actual upload/outbox facts");
            for(String name:new String[]{"prepared","admitted","linked","ack"})phase(name,image,1);
            phase("link_ack",image,0);
            // 自适应上传用真实RPC字节校验，原阶段总数及缓存关联语义仍严格保持。
            verifyImageChunks();check(connection.openCalls==1&&TraceLog.messages.size()==13,"per-chunk trace or cached link mislabeled ACK");grammar();
            System.out.println("PHASE_UPLOAD textPreparedBeforeImage=true imagePreparedAfterUpload=true cachedLinkAckAbsent=true");
        }
        /** 图片已有发送RPC但ACK未到，文字prepared不能伪称admitted；原终态后才准入。 */
        static void ackPhases(Path root)throws Exception {
            reset(root);DesktopConnection owner=connection;owner.holdSend=true;String image=image();sendQueue.all();uploadQueue.all();sendQueue.all();ui.all();
            check(owner.heldSend!=null&&store.get(image).submissionUncertain,"image missed actual held ACK");
            phase("prepared",image,1);phase("admitted",image,1);phase("ack",image,0);
            String next=text(1);sendQueue.all();ui.all();
            check(store.get(next)!=null&&store.get(next).isPrepared()&&!store.get(next).submissionUncertain&&owner.sendCalls==1,"next text not ready or same conversation dispatched concurrently");
            phase("persisted",next,1);phase("prepared",next,1);phase("admitted",next,0);phase("linked",next,0);
            owner.heldSend.completeExceptionally(new java.util.concurrent.TimeoutException());sendQueue.all();ui.all();
            check(store.get(image).submissionUncertain&&!store.get(image).submissionAccepted&&store.get(next).submissionAccepted&&owner.sendCalls==2,"terminal changed unknown fact or did not release original slot");
            phase("ack",image,0);phase("admitted",next,1);phase("link_ack",next,0);phase("ack",next,1);
            int calls=owner.sendCalls;SendMessageParams retry=new SendMessageParams();retry.retryMessageObject=pendingMessages.get(image);sendBatch(0,retry,new ArrayList<>());drainSend();ui.all();
            check(owner.sendCalls==calls&&preparedInFlight==0&&sendJobs.isEmpty(),"trace changed unknown no-resend or retained resources");grammar();
            System.out.println("PHASE_ACK nextPreparedWithoutAdmission=true terminalReleasedSlot=true unknownNotResent=true");
        }
        /** 真实link future保持未完成，只有它成功及原归属复核后才记link_ack。 */
        static void linkPhases(Path root)throws Exception {
            reset(root);DesktopConnection owner=connection;owner.holdLink=true;String first=text(1);sendQueue.all();ui.all();
            check(owner.heldLink!=null&&owner.openCalls==1&&owner.sendCalls==0,"original link barrier missed");
            phase("admitted",first,1);phase("link_ack",first,0);phase("linked",first,0);
            String next=text(1);sendQueue.all();ui.all();phase("prepared",next,1);phase("admitted",next,0);
            owner.heldLink.complete(owner.heldLinkValue);sendQueue.all();ui.all();
            check(store.get(first).submissionAccepted&&store.get(next).submissionAccepted&&owner.openCalls==1&&owner.sendCalls==2,"link completion changed cached association or FIFO");
            phase("link_ack",first,1);phase("linked",first,1);phase("link_ack",next,0);phase("admitted",next,1);grammar();
            System.out.println("PHASE_LINK delayedLinkAckAbsent=true successAfterOwnershipCheck=true cachedLinkNotAck=true");
        }
        /** 成功future回到旧账号或旧会话时，不伪记本轮link_ack。 */
        static void staleLink(Path root,boolean account)throws Exception {
            reset(root);DesktopConnection owner=connection;owner.holdLink=true;String first=text(1);sendQueue.all();
            if(account)accountGeneration++;else remoteIds.put(1L,"synthetic-rebound");
            owner.heldLink.complete(owner.heldLinkValue);sendQueue.all();ui.all();
            phase("link_ack",first,0);phase("linked",first,0);check(owner.sendCalls==0&&preparedInFlight==0&&sendJobs.isEmpty(),"stale link trace advanced original owner");grammar();
            System.out.println("PHASE_STALE account="+account+" linkAckAbsent=true");
        }
        /** DEBUG关闭完成相同发送却无输出；开时仅摘要和耗时，不带原身份正文路径。 */
        static void debugPhases(Path root)throws Exception {
            reset(root);TraceBuildVars.DEBUG_VERSION=false;String id=text(1);sendQueue.all();ui.all();
            check(store.get(id).submissionAccepted&&connection.sendCalls==1&&TraceLog.messages.isEmpty(),"DEBUG off emitted trace or changed send");
            String secret="/synthetic/private-path\\nunpublished synthetic body";traceSend("prepared",secret,android.os.SystemClock.elapsedRealtime());
            check(TraceLog.messages.isEmpty(),"DEBUG off direct trace emitted");TraceBuildVars.DEBUG_VERSION=true;
            traceSend("prepared",secret,android.os.SystemClock.elapsedRealtime());phase("prepared",secret,1);
            check(TraceLog.messages.size()==1&&!TraceLog.messages.peek().contains(secret)&&!TraceLog.messages.peek().contains("private-path")&&!TraceLog.messages.peek().contains("body"),"trace exposed synthetic content");grammar();
            System.out.println("PHASE_DEBUG offSilent=true onOnlyFixedPhaseHashElapsed=true");
        }
        public static void main(String[] args)throws Exception {Path root=Paths.get(args[0]);uploadPhases(root);ackPhases(root);linkPhases(root);staleLink(root,true);staleLink(root,false);debugPhases(root);}
        """;
}
