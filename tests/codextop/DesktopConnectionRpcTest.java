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

/** 提取真实RPC方法，替代Socket、事件线程及密文边界，不复制产品响应判断。 */
public final class DesktopConnectionRpcTest {
    /** 使用现成JDK、Gson、JavaParser、org.json及NaCl依赖；全部数据均为合成样例。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source.resolve("DesktopConnection.java"));
        StringBuilder methods = new StringBuilder();
        int extracted = 0;
        var names=Set.of("rpc","rpcAsync","awaitRpc","retireFailedEmitTimer","whenConnected","completeConnectionWaiters","candidates","base","isConnected","failPending","close",
                "attachmentUploadMaxBytes","clearAttachmentUploadLimit","status","userCodexSource");
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (names.contains(method.getNameAsString())) { methods.append(method).append('\n'); extracted++; }
        }
        for(var field:unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))
            if(field.getVariables().stream().anyMatch(v->v.getNameAsString().equals("attachmentUploadMaxBytes")
                    ||v.getNameAsString().equals("attachmentUploadGeneration")
                    ||v.getNameAsString().equals("connectionWaiters") || v.getNameAsString().equals("pending")))methods.append(field).append('\n');
        var connected=unit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(m->m.getNameAsString().equals("on")&&m.getArguments().size()==2&&m.getArgument(0).toString().equals("Socket.EVENT_CONNECT"))
                .findFirst().orElseThrow();
        methods.append("/** 提取原重连事件体，不复制失效逻辑。 */ void connectEvent()")
                .append(connected.getArgument(1).asLambdaExpr().getBody()).append('\n');
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getNameAsString().equals("RpcNotDispatchedException")
                    || type.getNameAsString().equals("SendRejectedException")
                    || type.getNameAsString().equals("PendingRpc")
                    || type.getNameAsString().equals("BulkAckTrace")) {
                methods.append(type).append('\n'); extracted++;
            }
        }
        if (extracted < 9) throw new AssertionError("真实RPC入口发生变化，请核对夹具边界");
        Path temporary = Files.createTempDirectory("codex-desktop-rpc");
        try {
            Path probe = temporary.resolve("DesktopRpcProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            var compile = new ArrayList<String>();
            compile.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), source.resolve("AttachmentTransfer.java").toString(),
                    source.resolve("BulkTransferCrypto.java").toString()));
            Path build=temporary.resolve("BuildVars.java"),log=temporary.resolve("Log.java");
            Files.writeString(build,"package org.telegram.messenger; public class BuildVars {public static final boolean DEBUG_VERSION=false;}");
            Files.writeString(log,"package android.util; public class Log {public static final int DEBUG=3; /** 旧场景保持关闭。 */ public static boolean isLoggable(String t,int l){return false;} /** 不输出真实诊断。 */ public static int i(String a,String b){return 0;}}");
            compile.add(build.toString());compile.add(log.toString());
            // 仅补SEND诊断新增的平台类型，既有RPC场景及断言不变。
            Path config=temporary.resolve("BuildConfig.java"),app=temporary.resolve("ApplicationLoader.java"),clock=temporary.resolve("SystemClock.java");
            Files.writeString(config,"package org.telegram.messenger; public class BuildConfig {public static final boolean DEBUG_VERSION=false;}");
            Files.writeString(app,"package org.telegram.messenger; public class ApplicationLoader {public static Context applicationContext; public static class Context {/** 合成包名。 */ public String getPackageName(){return \"org.telegram.messenger\";}}}");
            Files.writeString(clock,"package android.os; public class SystemClock {/** 默认关闭路径不读诊断时钟。 */ public static long elapsedRealtime(){throw new AssertionError(\"disabled trace clock\");}}");
            compile.add(config.toString());compile.add(app.toString());compile.add(clock.toString());
            // 平台替身与实际生产方法单独用Java 8编译，测试场景自身可使用宿主JDK。
            Path compatibility=temporary.resolve("java8");Files.createDirectories(compatibility);
            Path compatibilityProbe=compatibility.resolve("DesktopRpcProbe.java");
            Files.writeString(compatibilityProbe,FIXTURE+methods+"\n}");
            var compatibleCompile=new ArrayList<>(compile);
            compatibleCompile.set(3,compatibility.toString());compatibleCompile.set(4,compatibilityProbe.toString());
            compatibleCompile.add(0,"8");compatibleCompile.add(0,"--release");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compatibleCompile.toArray(String[]::new))!=0)
                throw new AssertionError("真实RPC生产方法不兼容Java 8");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实RPC夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, DesktopConnectionRpcTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.DesktopRpcProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{temporary.toString()}); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("真实RPC边界回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    // 仅模拟传输边界；成功、拒绝、类型检查和未知结果均执行当前生产方法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;
        import java.io.*;
        import java.nio.file.*;
        import java.util.*;
        import java.util.concurrent.*;
        import java.util.function.BooleanSupplier;
        import org.json.JSONObject;
        public final class DesktopRpcProbe implements AutoCloseable {
            volatile boolean closed;
            boolean hasConnected;
            String machineId="synthetic-machine";
            final Crypto crypto=new Crypto();
            final Socket socket=new Socket();
            SocketAckWireTrace wireTrace;
            /** 旧回归只补新观察边界，原传输断言不变；完整观察在专项执行真实helper。 */
            static final class SocketAckWireTrace { /** 原ACK边界空桩。 */ void ackEntered(int key){} /** 原派发代次空桩。 */ int emitEntered(int key){return 0;} /** 原emit返回观察空桩。 */ void emitReturned(int key,int generation){} /** 原超时观察空桩。 */ void timedOut(int key,int generation){} }
            static final class Crypto {
                static final String PREFIX="synthetic-cipher:";
                int decryptCalls;
                /** 密钥释放仅计合成边界，不处理真实密钥。 */void close(){}
                /** 标识请求已进入合成加密边界，不调用真实凭据。 */
                String encrypt(String plain){return PREFIX+plain;}
                /** 模拟成功解密及解密拒绝，产品内的响应解析保持原代码。 */
                String decrypt(String cipher)throws IOException{
                    decryptCalls++;
                    if(!cipher.startsWith(PREFIX))throw new IOException("合成密文无效");
                    return cipher.substring(PREFIX.length());
                }
            }
            static abstract class AckWithTimeout {
                /** 不执行真实计时器，超时由场景明确触发。 */
                AckWithTimeout(int timeout){if(timeout!=25000)throw new AssertionError("ACK超时契约变化");}
                /** 不跑真实时钟；本片异步硬期限另由专项明确推进。 */final void schedule(TimerTask task){}
                /** 不创建真实Timer；保留产品清理调用。 */final void cancelTimer(){}
                /** 向本次请求交付合成响应。 */
                public abstract void onSuccess(Object... args);
                /** 向本次请求交付合成超时。 */
                public abstract void onTimeout();
            }
            static final class EventThread {
                static Runnable beforeRun;
                /** 在真实第二门禁之前插入关闭或断线事件。 */
                static void exec(Runnable task){Runnable before=beforeRun;beforeRun=null;if(before!=null)before.run();task.run();}
            }
            static final class Socket {
                volatile boolean connected=true;
                boolean timeout,noAck;
                int emitted;
                Object[] reply;
                Object[] lateReply;
                Runnable afterReply;
                Runnable onEmit;
                JSONObject request;
                /** 只读取合成连接状态。 */
                boolean connected(){return connected;}
                /** 每个emit只调用它自己的ACK，额外响应用于验证完成后不得覆盖。 */
                void emit(String event,Object[] args,AckWithTimeout ack){
                    if(!"rpc-call".equals(event)||args.length!=1)throw new AssertionError("RPC传输入口变化");
                    emitted++;request=(JSONObject)args[0];
                    if(onEmit!=null)onEmit.run();
                    if(noAck)return;
                    if(timeout)ack.onTimeout();else ack.onSuccess(reply);
                    if(lateReply!=null)ack.onSuccess(lateReply);
                    if(afterReply!=null)afterReply.run();
                }
                /** 不操作真实Socket，仅模拟断线。 */void disconnect(){connected=false;}
                /** 不保存真实事件监听。 */void off(){}
            }
            static final class CodexRuntime {/** 不触发真实状态刷新。 */static void onDesktopConnected(Object c){}}
            /** 平台网络监听释放不参与本片容量判断。 */void unwatchNetwork(){}
            /** 修复前允许不存在的getter得到null，以实际候选行为断言RED。 */Long known()throws Exception{
                try{return (Long)getClass().getDeclaredMethod("attachmentUploadMaxBytes").invoke(this);}catch(NoSuchMethodException absent){return null;}
            }
            /** 构造已成功通过外层确认的合成密文封套。 */
            static JSONObject envelope(String body){return new JSONObject().put("ok",true).put("result",Crypto.PREFIX+body);}
            /** 每组独立连接避免迟到ACK或事件注入串到下一场景。 */
            static DesktopRpcProbe probe(String body){EventThread.beforeRun=null;DesktopRpcProbe p=new DesktopRpcProbe();p.socket.reply=new Object[]{envelope(body)};return p;}
            /** 所有普通发送授权使用合成原会话身份。 */
            static JsonObject params(){JsonObject p=new JsonObject();p.addProperty("sessionId","synthetic-session");return p;}
            /** 只检查结果与状态，不输出服务器错误正文。 */
            static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
            /** 失败场景保留原异常类别，且完成后必须释放在途future。 */
            static Exception fails(DesktopRpcProbe p,String method)throws Exception{
                try{p.rpc(method,params());throw new AssertionError("失败响应被当成成功");}
                catch(Exception error){check(p.pending.isEmpty(),"失败残留在途RPC");return error;}
            }
            /** 非可靠拒绝继续为包装后的未知结果，不自动转换成可重试发送。 */
            static void unknown(Exception error){check(error instanceof ExecutionException,"未知结果被错误解包");}
            /** 在不同线程的确定闸门处交错断线与派发，不靠睡眠猜顺序。 */
            static void await(CountDownLatch latch){
                try{check(latch.await(5,TimeUnit.SECONDS),"合成并发闸门超时");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            }
            /** 清理合成断线线程，不能让前一场景干扰下一请求。 */
            static void join(Thread thread)throws Exception{thread.join(5000);check(!thread.isAlive(),"合成断线线程未结束");}
        """;

    // 场景描述输入与可观察结果；不在测试里重新实现响应判定算法。
    private static final String SCENARIOS = """
            /** 覆盖真实RPC与专用附件调用者之间的拒绝传递及原发送边界。 */
            public static void main(String[] args)throws Exception {
                // 执行真实STATUS入口：旧调用省略opt-in，当前聊天显式请求且只发送一次。
                DesktopRpcProbe goalProbe=probe("{\\"ok\\":true,\\"machineOnline\\":true,\\"goal\\":{\\"availability\\":\\"none\\",\\"source\\":\\"desktop\\"}}");
                goalProbe.status("synthetic-remote","synthetic-linked");
                JsonObject legacyParams=JsonParser.parseString(goalProbe.socket.request.getString("params").substring(Crypto.PREFIX.length())).getAsJsonObject();
                check(!legacyParams.has("includeGoal"),"旧STATUS额外请求目标");
                JsonObject goalResponse;
                try{goalResponse=(JsonObject)goalProbe.getClass().getMethod("status",String.class,String.class,boolean.class).invoke(goalProbe,"synthetic-remote","synthetic-linked",true);}
                catch(NoSuchMethodException absent){goalResponse=goalProbe.status("synthetic-remote","synthetic-linked");}
                JsonObject goalParams=JsonParser.parseString(goalProbe.socket.request.getString("params").substring(Crypto.PREFIX.length())).getAsJsonObject();
                JsonObject descriptor=userCodexSource();
                check(goalParams.getAsJsonObject("source").equals(descriptor)&&descriptor.size()==2
                        &&"codexHome".equals(descriptor.get("kind").getAsString())&&"user".equals(descriptor.get("home").getAsString()),
                        "STATUS与缓存没有共用原来源描述");
                check(goalParams.has("includeGoal")&&goalParams.get("includeGoal").getAsBoolean(),"当前STATUS没有显式目标opt-in");
                check(goalProbe.socket.emitted==2&&"synthetic-remote".equals(goalParams.get("remoteSessionId").getAsString())
                        &&"synthetic-linked".equals(goalParams.get("sessionId").getAsString())&&goalParams.getAsJsonObject("source").get("kind").getAsString().equals("codexHome"),"目标读取增加RPC或丢失原身份");
                check(goalResponse.getAsJsonObject("goal").get("availability").getAsString().equals("none"),"目标原响应被改写");
                final String bulk="daemon.bulkTransfer.upload.init";
                final String send="daemon.directSessions.send";
                DesktopRpcProbe capacity=probe("{\\"ok\\":true,\\"candidates\\":[],\\"capabilities\\":{\\"attachmentUploadMaxBytes\\":4096}}");
                // 原once先唤醒构造调用方，此时后续CONNECT监听可能尚未执行。
                EventThread.beforeRun=capacity::connectEvent;
                capacity.candidates(100);check(Long.valueOf(4096).equals(capacity.known())&&capacity.socket.emitted==1,"初连监听把当前首次列表上限当作迟到响应丢弃");
                JsonObject sourceParams=JsonParser.parseString(capacity.socket.request.getString("params").substring(Crypto.PREFIX.length())).getAsJsonObject();
                check("codex".equals(sourceParams.get("providerId").getAsString())&&"codexHome".equals(sourceParams.getAsJsonObject("source").get("kind").getAsString())
                        &&capacity.machineId.equals(sourceParams.get("machineId").getAsString()),"限额读取绕过原来源或所属电脑");
                for(long maximum:new long[]{1,2147483648L,9007199254740991L}){
                    capacity.socket.reply=new Object[]{envelope("{\\"ok\\":true,\\"candidates\\":[],\\"capabilities\\":{\\"attachmentUploadMaxBytes\\":"+maximum+"}}")};
                    capacity.candidates(100);check(Long.valueOf(maximum).equals(capacity.known()),"实际正安全上限被截断或省略");
                }
                for(String raw:new String[]{"null","0","-1","1.5","1e100","9007199254740992","\\"4096\\"","true","{}","[]"}){
                    capacity.socket.reply=new Object[]{envelope("{\\"ok\\":true,\\"candidates\\":[],\\"capabilities\\":{\\"attachmentUploadMaxBytes\\":"+raw+"}}")};
                    capacity.candidates(100);check(capacity.known()==null,"坏上限被接受或旧上限未清除");
                }
                capacity.socket.reply=new Object[]{envelope("{\\"ok\\":true,\\"candidates\\":[]}")};capacity.candidates(100);check(capacity.known()==null,"旧机缺字段补造默认值");
                capacity.socket.reply=new Object[]{envelope("{\\"ok\\":true,\\"candidates\\":[],\\"capabilities\\":{\\"attachmentUploadMaxBytes\\":4096}}")};
                capacity.candidates(100);capacity.failPending();check(capacity.known()==null,"断开没有清原限额");
                capacity.candidates(100);capacity.connectEvent();check(capacity.known()==null,"重连没有清原限额");
                final DesktopRpcProbe reconnect=capacity;capacity.socket.afterReply=()->{reconnect.socket.connected=false;reconnect.failPending();reconnect.socket.connected=true;reconnect.connectEvent();};
                capacity.candidates(100);check(capacity.known()==null,"断开重连后迟到旧列表写回原限额");capacity.socket.afterReply=null;
                capacity.candidates(100);DesktopRpcProbe otherMachine=probe("{\\"ok\\":true,\\"candidates\\":[]}");otherMachine.machineId="other-machine";otherMachine.candidates(100);
                check(otherMachine.known()==null&&Long.valueOf(4096).equals(capacity.known()),"不同电脑限额串用");capacity.close();check(capacity.known()==null,"关闭后保留上限");
                String rejected="{\\"success\\":false,\\"error\\":\\"File exceeds upload size limit\\",\\"detail\\":{\\"synthetic\\":true}}";
                DesktopRpcProbe p=probe(rejected);
                JsonObject returned=p.rpc(bulk,params());
                check(returned.equals(JsonParser.parseString(rejected)),"合法bulk失败封套被吞并或字段改变");
                check(p.pending.isEmpty()&&p.socket.emitted==1,"bulk失败被重试或残留future");
                check((p.machineId+":"+bulk).equals(p.socket.request.get("method")),"请求没有绑定原机器");
                check((Crypto.PREFIX+params()).equals(p.socket.request.get("params")),"请求绕过原加密边界");
                check(!p.socket.request.has("authorization"),"bulk增加了普通发送授权");

                File original=new File(args[0],"synthetic.bin");Files.write(original.toPath(),new byte[]{1});
                DesktopRpcProbe upload=probe(rejected);
                AttachmentTransfer transfer=new AttachmentTransfer((method,input)->upload.rpc(method,input),null);
                try{transfer.upload(original,"file","synthetic-send","/synthetic/workspace");throw new AssertionError("上传拒绝被接受");}
                catch(IOException error){check("File exceeds upload size limit".equals(error.getMessage()),"专用传输调用者没有得到原超限原因");}
                check(upload.socket.emitted==1&&original.length()==1,"init拒绝后继续传输或破坏原件");

                String arbitrary="{\\"success\\":false,\\"error\\":\\"synthetic arbitrary reason\\",\\"extra\\":[1,2]}";
                check(probe(arbitrary).rpc(bulk,params()).equals(JsonParser.parseString(arbitrary)),"合法原失败字段未交还专用调用者");
                for(String direction:new String[]{"upload","download"}) for(String operation:new String[]{"init","chunk","finalize","abort"}) {
                    DesktopRpcProbe rejectedOperation=probe(arbitrary);
                    check(rejectedOperation.rpc("daemon.bulkTransfer."+direction+"."+operation,params()).equals(JsonParser.parseString(arbitrary))
                            &&rejectedOperation.pending.isEmpty()&&rejectedOperation.socket.emitted==1,"同域bulk拒绝未保留原响应或重复派发");
                }
                String successful="{\\"success\\":true,\\"error\\":\\"synthetic ignored field\\",\\"extra\\":[1,2]}";
                check(probe(successful).rpc(bulk,params()).equals(JsonParser.parseString(successful)),"原bulk成功封套改变");

                for(String body:new String[]{"{}","{\\"ok\\":true}","{\\"success\\":null}","{\\"success\\":\\"false\\"}",
                        "{\\"success\\":0}","{\\"success\\":{}}","{\\"success\\":false}",
                        "{\\"success\\":false,\\"error\\":null}","{\\"success\\":false,\\"error\\":1}",
                        "{\\"success\\":false,\\"error\\":{}}","{\\"success\\":false,\\"error\\":[]}",
                        "{\\"success\\":false,\\"error\\":true}"}) {
                    Exception error=fails(probe(body),bulk);unknown(error);
                    check("电脑未能完成此请求".equals(error.getCause().getMessage()),"畸形bulk响应改变原拒绝语义");
                }
                p=probe("{\\"ok\\":false,\\"error\\":\\"synthetic private reason\\"}");
                Exception generic=fails(p,"daemon.directSessions.control.read");unknown(generic);
                check("电脑未能完成此请求".equals(generic.getCause().getMessage()),"通用RPC泄漏服务器原始原因");
                unknown(fails(probe("{\\"success\\":true}"),"daemon.directSessions.control.read"));

                p=probe("{\\"ok\\":false,\\"errorCode\\":\\"synthetic_rejected\\"}");
                Exception denied=fails(p,send);
                check(denied instanceof SendRejectedException&&"synthetic_rejected".equals(((SendRejectedException)denied).reason),"原send明确拒绝被改变");
                JSONObject authorization=(JSONObject)p.socket.request.get("authorization");
                check("session.write".equals(authorization.get("kind"))&&"synthetic-session".equals(authorization.get("sessionId")),"原send会话授权改变");
                unknown(fails(probe("{\\"ok\\":false,\\"errorCode\\":\\"delivery_outcome_unknown\\"}"),send));
                // 缺失或畸形的 ok 不能证明没有提交，errorCode 不能单独解锁重试。
                for(String body:new String[]{"{\\"errorCode\\":\\"timeout\\"}",
                        "{\\"ok\\":\\"false\\",\\"errorCode\\":\\"synthetic_rejected\\"}",
                        "{\\"ok\\":null,\\"errorCode\\":\\"synthetic_rejected\\"}",
                        "{\\"ok\\":0,\\"errorCode\\":\\"synthetic_rejected\\"}",
                        "{\\"ok\\":{},\\"errorCode\\":\\"synthetic_rejected\\"}",
                        "{\\"ok\\":[],\\"errorCode\\":\\"synthetic_rejected\\"}"}) unknown(fails(probe(body),send));
                check(probe("{\\"ok\\":true,\\"extra\\":1}").rpc(send,params()).get("extra").getAsInt()==1,"原send成功字段改变");

                for(Object[] reply:new Object[][]{new Object[]{},new Object[]{"invalid-envelope"},
                        new Object[]{new JSONObject().put("ok",false)},new Object[]{new JSONObject().put("ok",true)},
                        new Object[]{new JSONObject().put("ok",true).put("result","invalid-cipher")},
                        new Object[]{envelope("[]")},new Object[]{envelope("not-json")}}) {
                    p=probe("{}");p.socket.reply=reply;unknown(fails(p,bulk));
                }
                p=probe("{\\"success\\":true}");p.socket.timeout=true;
                p.socket.lateReply=new Object[]{envelope("{\\"success\\":true}")};
                Exception timedOut=fails(p,bulk);unknown(timedOut);
                check(timedOut.getCause() instanceof TimeoutException&&p.socket.emitted==1&&p.crypto.decryptCalls==0,"ACK超时变成功、重复emit或仍处理迟到密文");
                p=probe(successful);p.socket.lateReply=new Object[]{envelope(rejected)};
                check(p.rpc(bulk,params()).equals(JsonParser.parseString(successful))&&p.crypto.decryptCalls==1
                        &&p.pending.isEmpty()&&p.socket.emitted==1,"迟到ACK覆盖或重新解密已完成请求");

                p=probe("{}");p.socket.connected=false;
                check(fails(p,bulk) instanceof RpcNotDispatchedException&&p.socket.emitted==0,"初始断线仍emit");
                for(boolean close:new boolean[]{false,true}) {
                    DesktopRpcProbe beforeEmit=probe("{}");
                    EventThread.beforeRun=()->{if(close)beforeEmit.closed=true;else beforeEmit.socket.connected=false;};
                    check(fails(beforeEmit,bulk) instanceof RpcNotDispatchedException&&beforeEmit.socket.emitted==0,"事件队列第二门禁失效");
                }
                // 原断线/关闭事件确实调用failPending，不能只改变connected绕过在途完成竞态。
                for(boolean close:new boolean[]{false,true}) {
                    DesktopRpcProbe beforeEmit=probe("{}");
                    EventThread.beforeRun=()->{if(close)beforeEmit.close();else{beforeEmit.socket.connected=false;beforeEmit.failPending();}};
                    check(fails(beforeEmit,send) instanceof RpcNotDispatchedException&&beforeEmit.socket.emitted==0,"原断线入口在emit前误标未知结果");
                    DesktopRpcProbe afterEmit=probe("{}");
                    afterEmit.socket.onEmit=()->{if(close)afterEmit.close();else{afterEmit.socket.connected=false;afterEmit.failPending();}};
                    Exception dispatched=fails(afterEmit,send);unknown(dispatched);
                    check(dispatched.getCause() instanceof IOException&&afterEmit.socket.emitted==1,"emit后断线错误解锁重试");
                    DesktopRpcProbe afterAck=probe("{\\"ok\\":true}");
                    afterAck.socket.afterReply=()->{if(close)afterAck.close();else afterAck.failPending();};
                    check(afterAck.rpc(send,params()).get("ok").getAsBoolean()&&afterAck.socket.emitted==1&&afterAck.pending.isEmpty(),"已完成ACK被断线覆盖");
                }
                // 等待方已取消的future尚未进入事件线程时，晚执行的派发门必须拒绝emit。
                DesktopRpcProbe canceled=probe("{}");
                EventThread.beforeRun=()->canceled.pending.iterator().next().cancel(false);
                check(fails(canceled,send) instanceof CancellationException&&canceled.socket.emitted==0,"已取消请求晚执行仍emit");
                // 双线程分别强制断线先胜和派发先胜，两个方向都使用生产future与同步状态。
                for(boolean close:new boolean[]{false,true}) {
                    DesktopRpcProbe beforeGate=probe("{}");Thread[] earlier=new Thread[1];CountDownLatch stopped=new CountDownLatch(1);
                    EventThread.beforeRun=()->{
                        earlier[0]=new Thread(()->{if(close)beforeGate.close();else beforeGate.failPending();stopped.countDown();},"synthetic-before-dispatch");
                        earlier[0].start();await(stopped);
                    };
                    Exception before=fails(beforeGate,send);join(earlier[0]);
                    check(before instanceof RpcNotDispatchedException&&beforeGate.socket.emitted==0,"另一线程断线先胜后仍emit");
                    DesktopRpcProbe pastGate=probe("{}");Thread[] later=new Thread[1];CountDownLatch failed=new CountDownLatch(1);
                    pastGate.socket.onEmit=()->{
                        later[0]=new Thread(()->{if(close)pastGate.close();else pastGate.failPending();failed.countDown();},"synthetic-after-dispatch");
                        later[0].start();await(failed);
                    };
                    Exception after=fails(pastGate,send);join(later[0]);unknown(after);
                    check(after.getCause() instanceof IOException&&pastGate.socket.emitted==1,"派发先胜后被另一线程错误解锁");
                    System.out.println("RPC_THREAD_ORDER close="+close+" beforeEmitted="+beforeGate.socket.emitted+" before="+before.getClass().getSimpleName()
                            +" afterEmitted="+pastGate.socket.emitted+" after="+after.getClass().getSimpleName());
                }
                // 断线线程与派发线程竞争同一实际门锁；未发出只可为notDispatched，已发出只可为unknown。
                int notDispatched=0,dispatched=0;
                for(int attempt=0;attempt<200;attempt++) {
                    DesktopRpcProbe concurrent=probe("{}");concurrent.socket.noAck=true;
                    CountDownLatch start=new CountDownLatch(1);Thread[] disconnect=new Thread[1];
                    EventThread.beforeRun=()->{
                        disconnect[0]=new Thread(()->{await(start);concurrent.failPending();concurrent.socket.connected=false;},"synthetic-rpc-disconnect");
                        disconnect[0].start();start.countDown();
                    };
                    Exception result=fails(concurrent,send);join(disconnect[0]);
                    if(concurrent.socket.emitted==0){notDispatched++;check(result instanceof RpcNotDispatchedException,"未派发并发分支仍标未知");}
                    else{dispatched++;unknown(result);check(result.getCause() instanceof IOException&&concurrent.socket.emitted==1,"已派发并发分支错误解锁");}
                }
                System.out.println("RPC_DISPATCH_RACE attempts=200 notDispatched="+notDispatched+" dispatched="+dispatched+" java8=true");
                System.out.println("DesktopConnectionRpc: 真实列表限额、坏值与机器来源、断开重连迟到清理、bulk失败、超时、原send及未派发语义通过");
            }
        """;
}
