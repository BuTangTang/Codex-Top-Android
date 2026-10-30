package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 执行真实异步ACK代码；复用已有传输桩，只新增可控的Android诊断与解密回调边界。 */
public final class DesktopConnectionAckTraceTest {
    /** 可传入本片前像观察缺少阶段的RED；所有数据均为合成值，不连接设备或网络。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0
                ? "TMessagesProj/src/main/java/com/butang/codextop/DesktopConnection.java" : args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source);
        Set<String> names = Set.of("rpc", "rpcAsync", "awaitRpc", "openConversationAsync", "sendAsync", "base",
                "userCodexSource", "failPending", "clearAttachmentUploadLimit", "close", "retireFailedEmitTimer",
                "whenConnected", "completeConnectionWaiters");
        StringBuilder actual = new StringBuilder();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class))
            if (names.contains(method.getNameAsString())) actual.append(method).append('\n');
        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))
            if (field.getVariables().stream().anyMatch(value -> Set.of("connectionWaiters", "pending",
                    "attachmentUploadMaxBytes", "attachmentUploadGeneration").contains(value.getNameAsString())))
                actual.append(field).append('\n');
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class))
            if (Set.of("PendingRpc", "RpcNotDispatchedException", "SendRejectedException", "BulkAckTrace").contains(type.getNameAsString()))
                actual.append(type).append('\n');
        String methods = actual.toString().replace("org.telegram.messenger.BuildConfig", "TraceBuildConfig")
                .replace("org.telegram.messenger.ApplicationLoader", "TraceApplicationLoader")
                .replace("android.os.SystemClock", "TraceClock").replace("android.util.Log", "TraceLog");
        String fixture = constant("FIXTURE")
                .replace("int decrypts;Runnable encryptHook;", "int decrypts;Runnable encryptHook, decryptHook;")
                .replace("decrypts++;return value;", "decrypts++;if(decryptHook!=null)decryptHook.run();return value;");
        Path temporary = Files.createTempDirectory("codex-send-ack-trace-");
        try {
            Path probe = temporary.resolve("AsyncConnectionProbe.java");
            Files.writeString(probe, fixture + methods + BOUNDARIES + SCENARIOS + "\n}\n");
            Path attachment = Path.of("TMessagesProj/src/main/java/com/butang/codextop/DesktopAttachment.java");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                    "-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), attachment.toString()) != 0)
                throw new AssertionError("真实ACK与合成诊断边界无法按Java8编译");
            ArrayList<URL> classpath = new ArrayList<>();
            classpath.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                classpath.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.AsyncConnectionProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("真实ACK诊断边界失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 复用旧异步测试的原传输桩，避免重写或放宽已有Socket与Timer语义。 */
    private static String constant(String name) throws Exception {
        var field = DesktopConnectionAsyncSendTest.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static final String BOUNDARIES = """
        static final class TraceBuildConfig { static boolean DEBUG_VERSION; }
        static final class TraceApplicationLoader { static TraceContext applicationContext; }
        static final class TraceContext {
            String packageName="com.butang.codextop.nativepreview.beta";boolean fail;int reads;
            /** 只返回合成包名，可证明默认关闭时不查询上下文。 */
            String getPackageName(){reads++;if(fail)throw new AssertionError("synthetic package failure");return packageName;}
        }
        static final class TraceClock {
            static long now;static int reads;static boolean fail;
            /** 单调诊断时钟独立于旧RPC的deadline时钟。 */
            static long elapsedRealtime(){reads++;if(fail)throw new AssertionError("synthetic clock failure");return now;}
        }
        static final class TraceLog {
            static final int DEBUG=3;static boolean enabled,failGate,failWrite;static int queries;
            static final List<String> records=new ArrayList<>();
            /** 显式日志开关只接收固定标签；可注入诊断自身故障。 */
            static boolean isLoggable(String tag,int level){queries++;check("CodexSendAck".equals(tag)&&level==DEBUG,"日志开关改变");
                if(failGate)throw new AssertionError("synthetic gate failure");return enabled;}
            /** 只保存合成诊断，不输出消息正文或原身份。 */
            static int i(String tag,String line){check("CodexSendAck".equals(tag),"日志标签改变");
                if(failWrite)throw new AssertionError("synthetic log failure");records.add(line);return 0;}
        }
        static int traceChecks;
        /** 专项断言与旧传输桩检查分开计数，失败立即终止。 */
        static void expect(boolean value,String reason){traceChecks++;check(value,reason);}
        /** 每组重置合成平台与传输状态，不修改真实应用或系统属性。 */
        static void resetTrace(){reset();TraceBuildConfig.DEBUG_VERSION=true;TraceLog.enabled=true;
            TraceLog.failGate=false;TraceLog.failWrite=false;TraceLog.queries=0;TraceLog.records.clear();
            TraceClock.now=1000;TraceClock.reads=0;TraceClock.fail=false;TraceApplicationLoader.applicationContext=new TraceContext();}
        /** 通过真实发送入口保留原消息编号与授权封套。 */
        static CompletableFuture<JsonObject> sendTrace(AsyncConnectionProbe owner){
            return owner.sendAsync("synthetic-session","synthetic body never logged","synthetic-local",Collections.emptyList(),null,null);}
        /** 仅提取固定阶段名，完整格式另行校验以发现意外正文泄露。 */
        static List<String> phases(){List<String> phases=new ArrayList<>();for(String line:TraceLog.records)
            phases.add(line.substring("send_ack_phase=".length(),line.indexOf(" key=")));return phases;}
        /** 校验全部日志仅有固定阶段、32位摘要和两个单调时钟数字。 */
        static void grammar(){for(String line:TraceLog.records)expect(line.matches(
            "send_ack_phase=(ack_enter|ack_decoded|ack_complete_begin|ack_complete_end) key=-?[0-9]+ elapsedMs=[0-9]+ elapsedRealtimeMs=[0-9]+"),"诊断包含未批准字段");}
        """;

    private static final String SCENARIOS = """
        /** 默认关闭、普通Telegram、旧自研包、无上下文及非SEND均不得新增时钟读取或日志。 */
        static void disabledGates(){
            for(int mode=0;mode<7;mode++){
                resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();
                if(mode==0)TraceBuildConfig.DEBUG_VERSION=false;
                if(mode==1)TraceLog.enabled=false;
                if(mode==2)TraceApplicationLoader.applicationContext.packageName="org.telegram.messenger";
                if(mode==3)TraceApplicationLoader.applicationContext.packageName="com.butang.codextop.debug";
                if(mode==4)TraceApplicationLoader.applicationContext=null;
                if(mode==5)TraceApplicationLoader.applicationContext.packageName="other.com.butang.codextop.nativepreview";
                CompletableFuture<JsonObject> future=mode==6?owner.openConversationAsync("synthetic-remote",null,null):sendTrace(owner);
                EventThread.drain();owner.socket.reply(1,"{\\"ok\\":true}");
                expect(future.join().get("ok").getAsBoolean(),"关闭诊断改变成功响应");
                expect(TraceLog.records.isEmpty()&&TraceClock.reads==0,"关闭诊断仍记录或分配诊断状态");
                if(mode==0||mode==6)expect(TraceLog.queries==0,"DEBUG或SEND门未短路标签查询");
                if(mode==1)expect(TraceApplicationLoader.applicationContext.reads==0,"默认关闭仍查询包名");
            }
        }
        /** 用真实complete及同步依赖链分开入口、解密和完成耗时，并验证重复ACK有限。 */
        static void successOrder(){
            resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();CompletableFuture<JsonObject> future=sendTrace(owner);
            EventThread.drain();AckWithTimeout ack=owner.socket.acks.get(1);
            owner.crypto.decryptHook=()->{expect(phases().equals(Arrays.asList("ack_enter")),"解密前阶段错序");TraceClock.now=1120;};
            CompletableFuture<JsonObject> dependent=future.whenComplete((value,error)->{expect(phases().equals(Arrays.asList("ack_enter","ack_decoded","ack_complete_begin")),
                    "complete前后未包围同步回调");TraceClock.now=1195;});
            TraceClock.now=1100;owner.socket.reply(1,"{\\"ok\\":true}");
            dependent.join();
            expect(future.join().get("ok").getAsBoolean(),"诊断改变成功结果");
            expect(phases().equals(Arrays.asList("ack_enter","ack_decoded","ack_complete_begin","ack_complete_end")),"四阶段缺失或错序");
            expect(TraceLog.records.get(0).endsWith("elapsedMs=100 elapsedRealtimeMs=1100"),"入口时钟起点改变");
            expect(TraceLog.records.get(1).endsWith("elapsedMs=120 elapsedRealtimeMs=1120"),"解密计时边界错误");
            expect(TraceLog.records.get(3).endsWith("elapsedMs=195 elapsedRealtimeMs=1195"),"未包含同步完成回调");
            expect(TraceLog.records.get(0).contains(" key="+"synthetic-local".hashCode()+" "),"与现有发送摘要无法关联");
            int reads=TraceClock.reads;ack.onSuccess(new JSONObject().put("ok",true).put("result","{\\"ok\\":true}"));
            expect(TraceLog.records.size()==4&&TraceClock.reads==reads&&owner.crypto.decrypts==1,"重复ACK不限量或重新解密");grammar();
        }
        /** 畸形封套及解析失败不记decoded；合法布尔拒绝保持原异常类型且可观察完成。 */
        static void failures(){
            String[] bodies={"{}","{\\"ok\\":\\"true\\"}","[]","{","{\\"ok\\":false}",
                    "{\\"ok\\":false,\\"errorCode\\":\\"synthetic_rejected\\"}",
                    "{\\"ok\\":false,\\"errorCode\\":\\"delivery_outcome_unknown\\"}"};
            String[] errors={"IOException","IOException","IllegalStateException","JsonSyntaxException","IOException","SendRejectedException","IOException"};
            for(int i=0;i<bodies.length;i++){
                resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();CompletableFuture<JsonObject> future=sendTrace(owner);
                EventThread.drain();owner.socket.reply(1,bodies[i]);Throwable error=failure(future);
                expect(error.getClass().getSimpleName().equals(errors[i]),"响应异常类型改变");
                expect(phases().equals(i>=4?Arrays.asList("ack_enter","ack_decoded","ack_complete_begin","ack_complete_end")
                        :Arrays.asList("ack_enter","ack_complete_begin","ack_complete_end")),"失败阶段含义错误");grammar();
            }
            for(int mode=0;mode<3;mode++){
                resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();CompletableFuture<JsonObject> future=sendTrace(owner);
                EventThread.drain();AckWithTimeout ack=owner.socket.acks.get(1);
                if(mode==0)ack.onSuccess();
                if(mode==1)ack.onSuccess(new JSONObject().put("ok",false));
                if(mode==2){owner.crypto.decryptHook=()->{throw new IllegalArgumentException("synthetic decrypt failure");};owner.socket.reply(1,"{}");}
                expect(mode==2?failure(future) instanceof IllegalArgumentException:failure(future) instanceof IOException,"解密前失败语义改变");
                expect(phases().equals(Arrays.asList("ack_enter","ack_complete_begin","ack_complete_end")),"未解密的ACK误记decoded");
            }
        }
        /** 超时、取消及关闭后的迟ACK只记入口，仍不解密、不覆盖原终态。 */
        static void lateAndRacing(){
            for(int mode=0;mode<3;mode++){
                resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();CompletableFuture<JsonObject> future=sendTrace(owner);
                EventThread.drain();AckWithTimeout ack=owner.socket.acks.get(1);
                if(mode==0)future.cancel(false);if(mode==1)AckWithTimeout.advance(25000);if(mode==2)owner.close();
                Throwable original=failure(future);ack.onSuccess(new JSONObject().put("ok",true).put("result","{\\"ok\\":true}"));
                expect(failure(future)==original&&owner.crypto.decrypts==0,"迟ACK覆盖终态或进入解密");
                expect(phases().equals(Arrays.asList("ack_enter")),"迟ACK被误记解密或完成");
            }
            resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();CompletableFuture<JsonObject> future=sendTrace(owner);
            EventThread.drain();owner.crypto.decryptHook=()->future.cancel(false);owner.socket.reply(1,"{\\"ok\\":true}");
            expect(future.isCancelled()&&phases().size()==4,"解密期间竞态被诊断覆写");
        }
        /** 日志、开关、包名和时钟自身失败不得进入原RPC失败语义。 */
        static void diagnosticFailures(){
            for(int mode=0;mode<5;mode++){
                resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();
                if(mode==0)TraceLog.failGate=true;if(mode==1)TraceApplicationLoader.applicationContext.fail=true;
                if(mode==2)TraceClock.fail=true;
                CompletableFuture<JsonObject> future=sendTrace(owner);EventThread.drain();
                if(mode==3)TraceLog.failWrite=true;if(mode==4)TraceClock.fail=true;
                owner.socket.reply(1,"{\\"ok\\":true}");
                expect(future.join().get("ok").getAsBoolean()&&owner.crypto.decrypts==1,"诊断故障改变成功响应");
                expect(TraceLog.records.isEmpty(),"故障路径意外记录数据");
            }
        }
        /** 开关按请求固定；普通读取及非上传传输不因消息参数碰巧存在而记录。 */
        static void requestIsolation(){
            for(String method:new String[]{"daemon.directSessions.control.read","daemon.bulkTransfer.download.chunk"}){
                resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();JsonObject params=new JsonObject();
                params.addProperty("localId","synthetic-local");params.addProperty("sessionId","synthetic-session");
                CompletableFuture<JsonObject> future=owner.rpcAsync(method,params,null,null);EventThread.drain();
                owner.socket.reply(1,method.contains("bulkTransfer")?"{\\"success\\":true}":"{\\"ok\\":true}");
                expect(future.isDone()&&!future.isCompletedExceptionally()&&TraceLog.records.isEmpty()
                        &&TraceClock.reads==0&&TraceLog.queries==0,"非SEND且非上传误触诊断");
            }
            resetTrace();TraceLog.enabled=false;AsyncConnectionProbe owner=new AsyncConnectionProbe();
            CompletableFuture<JsonObject> future=sendTrace(owner);TraceLog.enabled=true;EventThread.drain();owner.socket.reply(1,"{\\"ok\\":true}");
            expect(future.isDone()&&TraceLog.records.isEmpty()&&TraceClock.reads==0,"请求途中开关改变本次诊断归属");
        }
        /** 两个启用诊断的SEND乱序回包，各自保留摘要、起点与四阶段上限，不串到另一请求。 */
        static void interleavedSends(){
            resetTrace();AsyncConnectionProbe owner=new AsyncConnectionProbe();
            CompletableFuture<JsonObject> first=owner.sendAsync("synthetic-session","first","synthetic-first",Collections.emptyList(),null,null);
            TraceClock.now=1020;
            CompletableFuture<JsonObject> second=owner.sendAsync("synthetic-session","second","synthetic-second",Collections.emptyList(),null,null);
            EventThread.drain();TraceClock.now=1100;owner.socket.reply(2,"{\\"ok\\":true}");
            expect(second.isDone()&&!first.isDone()&&TraceLog.records.size()==4,"乱序ACK改变请求归属");
            for(String line:TraceLog.records)expect(line.contains(" key="+"synthetic-second".hashCode()+" elapsedMs=80 "),"第二请求摘要或起点被串用");
            TraceClock.now=1130;owner.socket.reply(1,"{\\"ok\\":true}");
            expect(first.isDone()&&TraceLog.records.size()==8,"第一请求丢失阶段或重复记录");
            for(String line:TraceLog.records.subList(4,8))expect(line.contains(" key="+"synthetic-first".hashCode()+" elapsedMs=130 "),"第一请求摘要或起点被串用");
            grammar();
        }
        /** 执行各项真实ACK边界；不改变Socket、Timer或发送队列实现。 */
        public static void main(String[] args){disabledGates();successOrder();failures();lateAndRacing();diagnosticFailures();requestIsolation();interleavedSends();
            System.out.println("PASS real SEND ACK trace: "+traceChecks+" checks");}
        """;
}
