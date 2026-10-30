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

/** 执行真实连接异步核心；仅替换密文、时钟、事件队列和 Socket/ACK Timer 边界。 */
public final class DesktopConnectionAsyncSendTest {
    /** 无真实连接；旧入口由确定 ACK 屏障证明阻塞，新入口逐项执行合成时序。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0
                ? "TMessagesProj/src/main/java/com/butang/codextop/DesktopConnection.java" : args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source);
        boolean async = unit.findAll(MethodDeclaration.class).stream().anyMatch(m -> m.getNameAsString().equals("rpcAsync"));
        var names = Set.of("rpc", "rpcAsync", "awaitRpc", "openConversationAsync", "sendAsync", "base", "userCodexSource",
                "failPending", "clearAttachmentUploadLimit", "close", "retireFailedEmitTimer","whenConnected","completeConnectionWaiters");
        StringBuilder methods = new StringBuilder();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class))
            if (names.contains(method.getNameAsString())) methods.append(method).append('\n');
        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))
            if (field.getVariables().stream().anyMatch(v -> Set.of("connectionWaiters", "pending", "attachmentUploadMaxBytes",
                    "attachmentUploadGeneration").contains(v.getNameAsString()))) methods.append(field).append('\n');
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class))
            if (Set.of("PendingRpc", "RpcNotDispatchedException", "SendRejectedException", "BulkAckTrace").contains(type.getNameAsString()))
                methods.append(type).append('\n');
        Path temporary = Files.createTempDirectory("codex-async-connection-");
        try {
            Path probe = temporary.resolve("AsyncConnectionProbe.java");
            Files.writeString(probe, FIXTURE + methods + (async ? SCENARIOS : LEGACY) + "\n}");
            Path java8 = temporary.resolve("java8");
            Files.createDirectories(java8);
            Path compatibility = java8.resolve("AsyncConnectionProbe.java");
            Files.writeString(compatibility, FIXTURE + methods + "\n}");
            // 仅补新增诊断引用的Android边界；旧场景默认关闭诊断，全部原断言保持。
            Path build = temporary.resolve("BuildConfig.java"), app = temporary.resolve("ApplicationLoader.java");
            Path clock = temporary.resolve("SystemClock.java"), log = temporary.resolve("Log.java");
            Files.writeString(build, "package org.telegram.messenger; public class BuildConfig {public static final boolean DEBUG_VERSION=false;}");
            Files.writeString(app, "package org.telegram.messenger; public class ApplicationLoader {public static Context applicationContext; public static class Context {/** 合成包名。 */ public String getPackageName(){return \"org.telegram.messenger\";}}}");
            Files.writeString(clock, "package android.os; public class SystemClock {/** 默认关闭路径不读诊断时钟。 */ public static long elapsedRealtime(){throw new AssertionError(\"disabled trace clock\");}}");
            Files.writeString(log, "package android.util; public class Log {public static final int DEBUG=3; /** 旧场景保持关闭。 */ public static boolean isLoggable(String t,int l){return false;} /** 不输出真实诊断。 */ public static int i(String t,String v){throw new AssertionError(\"disabled trace log\");}}");
            Path production = Path.of("TMessagesProj/src/main/java/com/butang/codextop/DesktopAttachment.java");
            String[] compile = {"--release", "8", "-cp", System.getProperty("java.class.path"), "-d", java8.toString(),
                    compatibility.toString(), production.toString(), build.toString(), app.toString(), clock.toString(), log.toString()};
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile) != 0)
                throw new AssertionError("真实异步连接方法不兼容 Java8");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", temporary.toString(), probe.toString(), production.toString(),
                    build.toString(), app.toString(), clock.toString(), log.toString()) != 0)
                throw new AssertionError("真实异步连接夹具编译失败");
            ArrayList<URL> classpath = new ArrayList<>();
            classpath.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                classpath.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.AsyncConnectionProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("真实异步连接回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;
        import java.io.*;
        import java.util.*;
        import java.util.concurrent.*;
        import java.util.function.BooleanSupplier;
        import org.json.JSONObject;
        public final class AsyncConnectionProbe implements AutoCloseable {
            volatile boolean closed;String machineId="synthetic-machine";
            final Crypto crypto=new Crypto();final Socket socket=new Socket();
            SocketAckWireTrace wireTrace;
            /** 旧回归只补新观察边界，原传输断言不变；完整观察在专项执行真实helper。 */
            static final class SocketAckWireTrace { /** 原ACK边界空桩。 */ void ackEntered(int key){} /** 原派发代次空桩。 */ int emitEntered(int key){return 0;} /** 原emit返回观察空桩。 */ void emitReturned(int key,int generation){} /** 原超时观察空桩。 */ void timedOut(int key,int generation){} }
            static class System {
                static long nanos;static final java.io.PrintStream out=java.lang.System.out;
                /** 单调时钟边界由场景推进；不改产品超时判断。 */static long nanoTime(){return nanos;}
            }
            static class Crypto {
                int decrypts;Runnable encryptHook;
                /** 密文边界只传合成 JSON。 */String encrypt(String value){if(encryptHook!=null)encryptHook.run();return value;}
                /** 迟到密文不应进入此边界。 */String decrypt(String value){decrypts++;return value;}
                /** 不访问真实密钥。 */void close(){}
            }
            static class EventThread {
                static boolean queued=true;static final Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();
                /** 可保留尚未派发的真实事件体。 */static void exec(Runnable task){if(queued)tasks.add(task);else task.run();}
                /** 按真实单事件线程顺序释放任务。 */static void drain(){Runnable task;while((task=tasks.poll())!=null)task.run();}
            }
            static abstract class AckWithTimeout {
                static final List<AckWithTimeout> all=new ArrayList<>();
                static Runnable onConstruct;
                static class Scheduled {final TimerTask task;final long at;Scheduled(TimerTask t,long a){task=t;at=a;}}
                final long timeout;final List<Scheduled> tasks=new ArrayList<>();boolean canceled;
                /** 一个 ACK 模拟其现有一个 Timer；可在初检与登记之间插入close。 */AckWithTimeout(long value){
                    check(value==25000,"ACK期限改变");timeout=value;all.add(this);
                    Runnable hook=onConstruct;onConstruct=null;if(hook!=null)hook.run();
                }
                /** 按库的同一 Timer 追加任务；已取消后再 schedule 必须失败。 */final void schedule(TimerTask task){
                    if(canceled)throw new IllegalStateException("Timer already cancelled");
                    tasks.add(new Scheduled(task,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout)));
                }
                /** 对应实际 ACK.call 先取消 Timer 再交回成功。 */final void call(Object... values){cancelTimer();onSuccess(values);}
                /** 取消该 Timer 的所有剩余任务。 */final void cancelTimer(){canceled=true;tasks.clear();}
                public abstract void onSuccess(Object... values);public abstract void onTimeout();
                /** 确定性推进已有 TimerTask；响应判定和 deadline 的任务体来自产品。 */static void advance(long ms){
                    System.nanos+=TimeUnit.MILLISECONDS.toNanos(ms);
                    for(;;){AckWithTimeout owner=null;Scheduled next=null;
                        for(AckWithTimeout ack:all)if(!ack.canceled)for(Scheduled task:ack.tasks)
                            if(task.at<=System.nanoTime()&&(next==null||task.at<next.at)){owner=ack;next=task;}
                        if(next==null)return;owner.tasks.remove(next);next.task.run();
                    }
                }
            }
            static class Socket {
                boolean connected=true,throwBeforeRegistration,throwAfterRegistration;
                int emitted;final Map<Integer,AckWithTimeout> acks=new LinkedHashMap<>();
                final List<JSONObject> calls=new ArrayList<>();CountDownLatch captured;
                /** 只读取合成连接状态。 */boolean connected(){return connected;}
                /** 模拟已核实的库顺序：schedule、登记 ACK，再发 packet；不复制产品 RPC 解析。 */
                void emit(String event,Object[] args,AckWithTimeout ack){
                    check("rpc-call".equals(event),"事件名改变");
                    if(throwBeforeRegistration)throw new IllegalStateException("synthetic emit before registration");
                    final int id=++emitted;calls.add((JSONObject)args[0]);
                    ack.schedule(new TimerTask(){public void run(){acks.remove(id);ack.onTimeout();}});
                    acks.put(id,ack);if(captured!=null)captured.countDown();
                    if(throwAfterRegistration)throw new IllegalStateException("synthetic emit after registration");
                }
                /** 原 Socket.onack 移除编号再调用 ACK。 */void reply(int id,String body){
                    AckWithTimeout ack=acks.remove(id);if(ack!=null)ack.call(new JSONObject().put("ok",true).put("result",body));
                }
                /** 关闭先交原 failPending，库随后清 ACK。 */void disconnect(){connected=false;for(AckWithTimeout ack:new ArrayList<>(acks.values()))ack.onTimeout();acks.clear();}
                /** 不操作真实监听。 */void off(){}
            }
            /** 不操作系统网络回调。 */void unwatchNetwork(){}
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static void reset(){System.nanos=0;EventThread.tasks.clear();EventThread.queued=true;AckWithTimeout.all.clear();AckWithTimeout.onConstruct=null;}
            static Throwable failure(CompletableFuture<?> future){
                check(future.isDone(),"请求在该边界仍未终结");
                try{future.join();throw new AssertionError("失败请求被当成功");}
                catch(CompletionException error){return error.getCause();}
                catch(CancellationException error){return error;}
            }
        """;

    private static final String LEGACY = """
            /** 旧真实 rpc 在同一发送工作线程等待 ACK，后续首写任务不能推进。 */
            public static void main(String[] args)throws Exception{
                reset();EventThread.queued=false;AsyncConnectionProbe p=new AsyncConnectionProbe();
                p.socket.captured=new CountDownLatch(1);ExecutorService queue=Executors.newSingleThreadExecutor();
                CompletableFuture<Void> sentinel=new CompletableFuture<>();
                JsonObject input=new JsonObject();input.addProperty("sessionId","synthetic-linked");
                Future<?> first=queue.submit(()->{try{p.rpc("daemon.directSessions.send",input);}catch(Exception e){throw new RuntimeException(e);}});
                check(p.socket.captured.await(2,TimeUnit.SECONDS),"旧实际RPC未到ACK屏障");
                queue.submit(()->sentinel.complete(null));boolean blocked=!sentinel.isDone();
                p.socket.reply(1,"{\\"ok\\":true}");first.get(2,TimeUnit.SECONDS);queue.shutdown();queue.awaitTermination(2,TimeUnit.SECONDS);
                check(blocked,"旧方法观察未复现");throw new AssertionError("旧实际rpc等待ACK，异步连接契约尚缺");
            }
        """;

    private static final String SCENARIOS = """
            static CompletableFuture<JsonObject> send(AsyncConnectionProbe p,Object gate,BooleanSupplier allowed){
                return p.sendAsync("synthetic-linked","synthetic text","synthetic-local",Collections.emptyList(),gate,allowed);
            }
            static void asyncAndIsolation(){
                reset();AsyncConnectionProbe p=new AsyncConnectionProbe();Object gate=new Object();
                CompletableFuture<JsonObject> a=send(p,gate,()->true);
                CompletableFuture<JsonObject> b=p.openConversationAsync("synthetic-remote",gate,()->true);
                check(!a.isDone()&&!b.isDone()&&p.pending.size()==2,"调用仍等ACK或未登记");
                EventThread.drain();check(p.socket.emitted==2,"第二请求未派发");
                JSONObject call=p.socket.calls.get(0);check(call.getInt("timeoutMs")==20000,"线上期限改变");
                check(call.getJSONObject("authorization").getString("kind").equals("session.write"),"发送授权改变");
                JsonObject body=JsonParser.parseString(call.getString("params")).getAsJsonObject();
                check(body.get("localId").getAsString().equals("synthetic-local")&&body.getAsJsonObject("meta").get("desktopTextSendProtocol").getAsString().equals("native-auto-v1"),"发送身份/协议改变");
                p.socket.reply(2,"{\\"ok\\":true,\\"sessionId\\":\\"linked-b\\"}");
                check(b.join().get("sessionId").getAsString().equals("linked-b")&&!a.isDone(),"乱序ACK串到另一请求");
                p.socket.reply(1,"{\\"ok\\":true}");check(a.join().get("ok").getAsBoolean()&&p.pending.isEmpty()&&p.socket.acks.isEmpty(),"成功未清理");
            }
            static void deadlineBeforeDispatch(){
                reset();AsyncConnectionProbe p=new AsyncConnectionProbe();CompletableFuture<JsonObject> f=send(p,new Object(),()->true);
                AckWithTimeout.advance(25000);check(failure(f) instanceof RpcNotDispatchedException&&p.pending.isEmpty(),"admission硬期限未完成/错判");
                EventThread.drain();check(p.socket.emitted==0&&AckWithTimeout.all.get(0).canceled,"超期后仍emit或残留Timer");
                // Timer线程尚未获得执行机会，事件线程已越deadline时仍执行生产单调门，不靠Timer先胜。
                reset();p=new AsyncConnectionProbe();f=send(p,new Object(),()->true);
                System.nanos=TimeUnit.SECONDS.toNanos(25);EventThread.drain();
                check(failure(f) instanceof RpcNotDispatchedException&&p.socket.emitted==0&&p.pending.isEmpty()
                        &&AckWithTimeout.all.get(0).canceled,"压住Timer后晚事件仍emit或残留");
            }
            static void deadlineAfterDispatch(){
                for(boolean lateAck:new boolean[]{false,true}){
                    reset();AsyncConnectionProbe p=new AsyncConnectionProbe();CompletableFuture<JsonObject> f=send(p,new Object(),()->true);
                    AckWithTimeout.advance(10000);EventThread.drain();AckWithTimeout.advance(15000);
                    check(failure(f) instanceof TimeoutException&&p.pending.isEmpty()&&p.socket.acks.size()==1,"已派发硬期限未完成或提前吞Socket清理");
                    if(lateAck)p.socket.reply(1,"{\\"ok\\":true}");else AckWithTimeout.advance(10000);
                    check(p.socket.acks.isEmpty()&&p.socket.emitted==1&&p.crypto.decrypts==0&&AckWithTimeout.all.get(0).canceled,"迟到/库超时未清理或重emit/解密");
                }
            }
            static void gateAndClose(){
                reset();AsyncConnectionProbe p=new AsyncConnectionProbe();java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
                CompletableFuture<JsonObject> f=send(p,active,active::get);synchronized(active){active.set(false);}EventThread.drain();
                check(failure(f) instanceof RpcNotDispatchedException&&p.socket.emitted==0&&p.pending.isEmpty(),"删除门前仍派发");
                reset();p=new AsyncConnectionProbe();f=send(p,new Object(),()->true);p.close();EventThread.drain();
                check(failure(f) instanceof RpcNotDispatchedException&&p.pending.isEmpty()&&p.socket.emitted==0,"close登记后门前不终结");
                reset();p=new AsyncConnectionProbe();AsyncConnectionProbe closing=p;p.crypto.encryptHook=closing::close;
                f=send(p,new Object(),()->true);EventThread.drain();
                check(failure(f) instanceof RpcNotDispatchedException&&p.pending.isEmpty()&&p.socket.emitted==0,"初检/登记窗口漏close");
                reset();p=new AsyncConnectionProbe();AsyncConnectionProbe beforeRegistration=p;AckWithTimeout.onConstruct=beforeRegistration::close;
                f=send(p,new Object(),()->true);EventThread.drain();
                check(failure(f) instanceof RpcNotDispatchedException&&p.pending.isEmpty()&&p.socket.emitted==0
                        &&AckWithTimeout.all.get(0).canceled,"pending登记前close漏终结");
                reset();p=new AsyncConnectionProbe();f=send(p,new Object(),()->true);EventThread.drain();p.close();
                check(failure(f) instanceof IOException&&!(failure(f) instanceof RpcNotDispatchedException)&&p.pending.isEmpty(),"越门close变成可靠未发");
                reset();p=new AsyncConnectionProbe();f=send(p,new Object(),()->true);EventThread.drain();p.socket.reply(1,"{\\"ok\\":true}");p.close();
                check(f.join().get("ok").getAsBoolean()&&p.pending.isEmpty(),"close抹去已完成ACK");
            }
            static void timerAndEmitFailure(){
                for(boolean after:new boolean[]{false,true}){
                    reset();AsyncConnectionProbe p=new AsyncConnectionProbe();p.socket.throwBeforeRegistration=!after;p.socket.throwAfterRegistration=after;
                    CompletableFuture<JsonObject> f=send(p,new Object(),()->true);EventThread.drain();
                    check(failure(f) instanceof IllegalStateException&&p.pending.isEmpty(),"emit抛错未终结");
                    AckWithTimeout.advance(25000);AckWithTimeout.advance(25000);
                    check(p.socket.acks.isEmpty()&&AckWithTimeout.all.get(0).canceled,"emit抛错Timer/map未退役");
                }
                reset();AsyncConnectionProbe p=new AsyncConnectionProbe();CompletableFuture<JsonObject> f=send(p,new Object(),()->true);
                f.cancel(false);EventThread.drain();check(p.pending.isEmpty()&&p.socket.emitted==0&&AckWithTimeout.all.get(0).canceled,"future取消后晚emit/Timer残留");
            }
            public static void main(String[] args){
                int failures=0;
                for(String name:new String[]{"asyncAndIsolation","deadlineBeforeDispatch","deadlineAfterDispatch","gateAndClose","timerAndEmitFailure"}){
                    try{AsyncConnectionProbe.class.getDeclaredMethod(name).invoke(null);System.out.println("PASS "+name);}
                    catch(Exception e){failures++;Throwable t=e instanceof java.lang.reflect.InvocationTargetException?e.getCause():e;System.out.println("FAIL "+name+": "+t);}
                }
                if(failures>0)throw new AssertionError(failures+" 个真实异步连接场景失败");
            }
        """;
}
