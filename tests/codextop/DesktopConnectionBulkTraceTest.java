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

/** 执行真实上传RPC及等待方法；诊断、传输、计时平台均为合成边界。 */
public final class DesktopConnectionBulkTraceTest {
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
        String methods = actual.toString().replace("extends CompletableFuture<JsonObject>", "extends TimedFuture<JsonObject>").replace("org.telegram.messenger.BuildConfig", "TraceBuildConfig")
                .replace("org.telegram.messenger.ApplicationLoader", "TraceApplicationLoader")
                .replace("android.os.SystemClock", "TraceClock").replace("android.util.Log", "TraceLog");
        String fixture = constant("FIXTURE")
                .replace("int decrypts;Runnable encryptHook;", "int decrypts;Runnable encryptHook, decryptHook;")
                .replace("decrypts++;return value;", "decrypts++;if(decryptHook!=null)decryptHook.run();return value;")
                .replace("void ackEntered(int key){}", "int emittedKey,ackKey;boolean fail; void ackEntered(int key){ackKey=key;if(fail)throw new AssertionError();}")
                .replace("int emitEntered(int key){return 0;}", "int emitEntered(int key){emittedKey=key;if(fail)throw new AssertionError();return 0;}");
        Path temporary = Files.createTempDirectory("codex-bulk-ack-trace-");
        try {
            Path probe = temporary.resolve("AsyncConnectionProbe.java");
            Files.writeString(probe, fixture + methods + boundaries() + BOUNDARIES + SCENARIOS + "\n}\n");
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
                    throw new AssertionError("真实bulk诊断边界失败", error.getCause());
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

    /** 复用SEND专项的平台边界；仅固定阶段解析另由本专项定义。 */
    private static String boundaries() throws Exception {
        var field = DesktopConnectionAckTraceTest.class.getDeclaredField("BOUNDARIES");
        field.setAccessible(true);
        String value = (String) field.get(null);
        return value.substring(0, value.indexOf("/** 通过真实发送入口"));
    }

    private static final String BOUNDARIES = """
        static boolean forceWaitTimeout;
        /** 仅替换future的等待边界；执行真实awaitRpc的25秒参数、catch和finally而不实等25秒。 */
        static class TimedFuture<T> extends CompletableFuture<T> {
            @Override public T get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException {
                expect(timeout==25&&unit==TimeUnit.SECONDS,"等待期限改变");
                if(forceWaitTimeout)throw new TimeoutException("NEVER_LOG_WAIT");return super.get(timeout,unit);
            }
        }
        /** 重置仅限当前合成进程中的额度，不影响任何生产进程。 */
        static void fresh()throws Exception {resetTrace();forceWaitTimeout=false;
            for(Class<?> c:AsyncConnectionProbe.class.getDeclaredClasses())if(c.getSimpleName().equals("BulkAckTrace")){
                java.lang.reflect.Field f=c.getDeclaredField("nextRequest");f.setAccessible(true);f.setInt(null,0);}
        }
        /** 关闭路径允许前像尚无诊断字段，以真实缺少阶段而非编译错误作为RED。 */
        static Object trace(CompletableFuture<?> future)throws Exception {
            try{java.lang.reflect.Field f=future.getClass().getDeclaredField("bulkAckTrace");f.setAccessible(true);return f.get(future);}
            catch(NoSuchFieldException absent){return null;}
        }
        /** 参数带合成敏感哨兵，日志必须仅含固定枚举。 */
        static CompletableFuture<JsonObject> upload(AsyncConnectionProbe owner,String op){JsonObject params=new JsonObject();
            params.addProperty("uploadId","NEVER_LOG_UPLOAD");params.addProperty("data","NEVER_LOG_BODY");
            return owner.rpcAsync("daemon.bulkTransfer."+op,params,null,null);}
        /** 只解析本片固定元数据阶段。 */
        static List<String> bulkPhases(){List<String> phases=new ArrayList<>();for(String line:TraceLog.records)
            phases.add(line.substring("bulk_ack_phase=".length(),line.indexOf(" seq=")));return phases;}
        /** 固定字段语法排除正文、身份、任意错误文本及动态方法名。 */
        static void grammar(){for(String line:TraceLog.records)expect(line.matches(
            "bulk_ack_phase=(rpc_start|emit_enter|ack_enter|response|exception|timeout|terminal|wait_timeout) seq=[0-9]+ method=upload[.](init|chunk|finalize|abort) valid=(-1|0|1) success=(-1|0|1) error=(none|not_dispatched|timeout|cancelled|interrupted|io|json|crypto|state|other) elapsedMs=[0-9]+ elapsedRealtimeMs=[0-9]+"),"日志字段超出白名单");}
        """;

    private static final String SCENARIOS = """
        /** 四方法以外及任一门关闭均不分配诊断对象、不读取诊断时钟。 */
        static void disabled()throws Exception {
            for(int mode=0;mode<9;mode++){fresh();AsyncConnectionProbe p=new AsyncConnectionProbe();
                if(mode==0)TraceBuildConfig.DEBUG_VERSION=false;if(mode==1)TraceLog.enabled=false;
                if(mode==2)TraceApplicationLoader.applicationContext.packageName="org.telegram.messenger";
                if(mode==3)TraceApplicationLoader.applicationContext=null;
                if(mode==4)TraceApplicationLoader.applicationContext.packageName="com.butang.codextop.debug";
                if(mode==5)TraceApplicationLoader.applicationContext.packageName="other.com.butang.codextop.nativepreview";
                if(mode==6)TraceLog.failGate=true;
                CompletableFuture<JsonObject> f=upload(p,mode==7?"download.chunk":mode==8?"upload.other":"upload.chunk");
                EventThread.drain();p.socket.reply(1,"{\\\"success\\\":true}");
                expect(f.join().get("success").getAsBoolean(),"关门改变原响应");
                expect(trace(f)==null&&TraceClock.reads==0&&TraceLog.records.isEmpty(),"关门仍创建/读钟/记录");
                if(mode==0||mode>=7)expect(TraceLog.queries==0,"构建/方法门未短路");
                if(mode==1)expect(TraceApplicationLoader.applicationContext.reads==0,"关标签仍访问包名");
            }
        }
        /** 实际加密、emit、解密与完成之间的固定阶段；四种上传方法不会带入参数。 */
        static void success()throws Exception {fresh();int index=0;
            for(String op:new String[]{"upload.init","upload.chunk","upload.finalize","upload.abort"}){
                TraceLog.records.clear();AsyncConnectionProbe p=new AsyncConnectionProbe();p.wireTrace=new SocketAckWireTrace();
                p.crypto.encryptHook=()->expect(bulkPhases().equals(Arrays.asList("rpc_start")),"rpc_start未早于加密");
                CompletableFuture<JsonObject> f=upload(p,op);expect(trace(f)!=null,"旧源码缺少bulk诊断");
                expect(bulkPhases().equals(Arrays.asList("rpc_start")),"派发前阶段错误");EventThread.drain();
                expect(bulkPhases().equals(Arrays.asList("rpc_start","emit_enter")),"emit位置错误");
                p.crypto.decryptHook=()->expect(bulkPhases().equals(Arrays.asList("rpc_start","emit_enter","ack_enter")),"解密前未记ACK入口");
                TraceClock.now+=25;p.socket.reply(1,"{\\\"success\\\":true,\\\"data\\\":\\\"NEVER_LOG_RETURN\\\"}");
                expect(f.join().get("success").getAsBoolean(),"成功返回改变");
                expect(bulkPhases().equals(Arrays.asList("rpc_start","emit_enter","ack_enter","response","terminal")),"成功阶段缺失或错序");
                expect(TraceLog.records.get(3).contains(" valid=1 success=1 error=none "),"未记录合法成功布尔");
                int seq=++index;for(String line:TraceLog.records)expect(line.contains(" seq="+seq+" method="+op+" "),"序号或方法串联");
                expect(p.wireTrace.ackKey==seq&&p.wireTrace.emittedKey==seq,"wire序号未关联");
                expect(p.pending.isEmpty()&&p.socket.acks.isEmpty(),"原ACK或future清理改变");grammar();
            }
        }
        /** bulk合法失败封套仍原样返回；其他异常沿原类型，不记录错误文本。 */
        static void responses()throws Exception {
            String[] bodies={"{\\\"success\\\":false,\\\"error\\\":\\\"NEVER_LOG_ERROR\\\"}","{\\\"success\\\":false}","{}","{\\\"success\\\":\\\"true\\\"}","[]","{"};
            String[] types={"returned","IOException","IOException","IOException","IllegalStateException","JsonSyntaxException"};
            for(int i=0;i<bodies.length;i++){fresh();AsyncConnectionProbe p=new AsyncConnectionProbe();CompletableFuture<JsonObject> f=upload(p,"upload.chunk");
                EventThread.drain();p.socket.reply(1,bodies[i]);
                if(i==0){expect(!f.join().get("success").getAsBoolean()&&f.join().get("error").getAsString().equals("NEVER_LOG_ERROR"),"合法失败封套被改写");
                    expect(!bulkPhases().contains("exception"),"合法失败被诊断当异常");}
                else expect(failure(f).getClass().getSimpleName().equals(types[i]),"原异常类型改变");
                final int scenario=i;
                if(i<4)expect(TraceLog.records.stream().anyMatch(x->x.contains("bulk_ack_phase=response ")&&x.contains(scenario<2?" valid=1 success=0 ":" valid=0 success=-1 ")),"布尔结果类型诊断不准确");
                expect(bulkPhases().get(bulkPhases().size()-1).equals("terminal"),"缺少终态");grammar();
            }
            for(int mode=0;mode<3;mode++){fresh();AsyncConnectionProbe p=new AsyncConnectionProbe();CompletableFuture<JsonObject> f=upload(p,"upload.chunk");
                EventThread.drain();AckWithTimeout a=p.socket.acks.remove(1);
                if(mode==0)a.call();if(mode==1)a.call(new JSONObject().put("ok",false).put("error","NEVER_LOG_ERROR"));
                if(mode==2){p.crypto.decryptHook=()->{throw new IllegalArgumentException("NEVER_LOG_CRYPTO");};a.call(new JSONObject().put("ok",true).put("result","{}"));}
                expect(failure(f) instanceof Exception&&!bulkPhases().contains("response"),"外层/解密异常被当合法响应");grammar();
            }
        }
        /** 取消、close与到期的原终态保持；迟ACK只观察入口，不重新解析或改写结果。 */
        static void terminalAndLate()throws Exception {
            for(int mode=0;mode<6;mode++){fresh();AsyncConnectionProbe p=new AsyncConnectionProbe();
                if(mode==0)p.closed=true;CompletableFuture<JsonObject> f=upload(p,"upload.chunk");
                if(mode==0){expect(failure(f) instanceof RpcNotDispatchedException&&AckWithTimeout.all.isEmpty(),"初始拒绝改变");grammar();continue;}
                AckWithTimeout a=AckWithTimeout.all.get(0);
                if(mode>=3)EventThread.drain();
                if(mode==1||mode==3)f.cancel(false);else if(mode==2||mode==4)AckWithTimeout.advance(25000);else p.close();
                Throwable failure=failure(f);int records=TraceLog.records.size();
                a.onSuccess(new JSONObject().put("ok",true).put("result","{\\\"success\\\":true}"));
                expect(p.crypto.decrypts==0&&failure(f)==failure,"迟ACK改变终态或重新解密");
                expect(TraceLog.records.size()==records+1&&bulkPhases().get(records).equals("ack_enter"),"完成后ACK入口缺失");
                int reads=TraceClock.reads;a.onSuccess();a.onTimeout();
                expect(TraceLog.records.size()==records+1&&TraceClock.reads==reads,"重复ACK/超时无界");
                EventThread.drain();expect(p.socket.emitted==(mode>=3?1:0),"取消/门前超时仍派发");grammar();
            }
        }
        /** get先超时的原catch和finally仍抛同一Timeout并取消；与ACK Timer源可区分。 */
        static void waitTimeout()throws Exception {fresh();AsyncConnectionProbe p=new AsyncConnectionProbe();
            CompletableFuture<JsonObject> f=upload(p,"upload.chunk");EventThread.drain();forceWaitTimeout=true;
            try{p.awaitRpc(f);throw new AssertionError("等待超时被吞");}catch(TimeoutException expected){}
            expect(f.isCancelled()&&bulkPhases().contains("wait_timeout")&&bulkPhases().contains("terminal"),"等待到期来源或原finally取消丢失");
            expect(!bulkPhases().contains("timeout"),"等待方超时被混为ACK Timer");grammar();}
        /** 诊断开关/时钟/日志/wire异常不可改变业务结果，途中关闭立即静默。 */
        static void isolation()throws Exception {
            for(int mode=0;mode<5;mode++){fresh();AsyncConnectionProbe p=new AsyncConnectionProbe();p.wireTrace=new SocketAckWireTrace();
                if(mode==0)TraceClock.fail=true;if(mode==1)TraceLog.failWrite=true;if(mode==2)p.wireTrace.fail=true;
                CompletableFuture<JsonObject> f=upload(p,"upload.chunk");
                if(mode==3)TraceLog.enabled=false;if(mode==4)TraceClock.fail=true;
                int reads=TraceClock.reads;EventThread.drain();p.socket.reply(1,"{\\\"success\\\":true}");
                expect(f.join().get("success").getAsBoolean()&&p.pending.isEmpty(),"诊断异常影响响应/清理");
                if(mode==3)expect(TraceClock.reads==reads&&TraceLog.records.size()==1,"关标签后仍读钟记录");grammar();
            }
        }
        /** 同一进程的不同连接共享32次上限；额度耗尽不读钟、不分配诊断实例。 */
        static void budget()throws Exception {fresh();for(int i=1;i<=40;i++){
            AsyncConnectionProbe p=new AsyncConnectionProbe();int reads=TraceClock.reads,count=TraceLog.records.size();
            CompletableFuture<JsonObject> f=upload(p,"upload.abort");EventThread.drain();p.socket.reply(1,"{\\\"success\\\":true}");
            expect(f.join().get("success").getAsBoolean(),"额度影响业务");
            if(i<=32)expect(trace(f)!=null&&TraceLog.records.get(count).contains(" seq="+i+" "),"跨连接序号重复或提前耗尽");
            else expect(trace(f)==null&&TraceClock.reads==reads&&TraceLog.records.size()==count,"额度耗尽仍分配/读钟/记录");
        }expect(TraceLog.records.size()==160&&TraceLog.records.size()<=256,"有限记录失效");grammar();}
        /** 顺序执行真实方法边界，不连接设备或服务器。 */
        public static void main(String[] args)throws Exception {disabled();success();responses();terminalAndLate();waitTimeout();isolation();budget();
            System.out.println("PASS actual bulk ACK trace: "+traceChecks+" checks; Java8=true; syntheticTransport=true");}
        """;
}
