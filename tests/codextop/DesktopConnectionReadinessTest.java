package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 真实连接等待方法和原CONNECT/close事件的合成竞态，不连接网络或设备。 */
public final class DesktopConnectionReadinessTest {
    /** 用Java8编译原方法，Socket仅提供可控连接位及事件边界。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(Path.of("TMessagesProj/src/main/java/com/butang/codextop/DesktopConnection.java"));
        StringBuilder actual = new StringBuilder();
        Set<String> names = Set.of("whenConnected", "completeConnectionWaiters", "isConnected", "close");
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) if (names.contains(method.getNameAsString())) actual.append(method).append('\n');
        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))
            if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().equals("connectionWaiters"))) actual.append(field).append('\n');
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class))
            if (type.getNameAsString().equals("RpcNotDispatchedException")) actual.append(type).append('\n');
        var event = unit.findAll(MethodCallExpr.class).stream().filter(m -> m.getNameAsString().equals("on")
                && m.getArguments().size() == 2 && m.getArgument(0).toString().equals("Socket.EVENT_CONNECT")).findFirst().orElseThrow();
        actual.append("/** 执行生产注册的CONNECT回调体。 */ void connectEvent()")
                .append(event.getArgument(1).asLambdaExpr().getBody().toString()
                        .replace("org.telegram.messenger.BuildVars.DEBUG_VERSION", "false").replace("android.util.Log.i", "log")).append('\n');
        Path temp = Files.createTempDirectory("codex-connection-ready-");
        try {
            Path probe = temp.resolve("ConnectionReadyProbe.java");
            Files.writeString(probe, FIXTURE + actual + SCENARIOS + "\n}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), probe.toString()) != 0)
                throw new AssertionError("真实Java8连接就绪方法编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()})) {
                try { loader.loadClass("ConnectionReadyProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("真实连接就绪边界失败", failure.getCause()); }
            }
        } finally { try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); } }
    }

    private static final String FIXTURE = """
        import java.io.IOException;import java.util.*;import java.util.concurrent.*;
        public final class ConnectionReadyProbe implements AutoCloseable {
            volatile boolean closed;boolean hasConnected;final Socket socket=new Socket();final Crypto crypto=new Crypto();
            /** 仅替换原Socket状态、off/disconnect，不提供connect或RPC发送能力。 */
            static final class Socket {volatile boolean connected;Runnable onRead;/** 读取状态时允许注入恰好到达的事件。 */boolean connected(){if(onRead!=null){Runnable r=onRead;onRead=null;r.run();}return connected;} /** 合成事件解绑边界。 */void off(){} /** 关闭合成传输位。 */void disconnect(){connected=false;}}
            /** 密钥释放替身不持有任何账号资料。 */static final class Crypto {/** 不访问真实密钥。 */void close(){}}
            /** 连接上层回调只记事件，本专项不运行Runtime状态轮询。 */static final class CodexRuntime {/** 不触发真实状态请求。 */static void onDesktopConnected(ConnectionReadyProbe p){}}
            /** 容量失效由原RPC专项覆盖。 */void clearAttachmentUploadLimit(){} /** 合成网络回调释放边界。 */void unwatchNetwork(){} /** 原RPC终态由相邻专项覆盖。 */void failPending(){} /** 不输出实际诊断。 */static void log(String a,String b){}
            /** 合成断言不依赖JUnit或真实Android。 */static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
        """;

    private static final String SCENARIOS = """
        /** 已连接立即完成；离线登记只等原CONNECT，重复事件不保留或重复回调。 */
        static void lifecycle()throws Exception{
            ConnectionReadyProbe p=new ConnectionReadyProbe();p.socket.connected=true;check(p.whenConnected().isDone()&&p.connectionWaiters.isEmpty(),"ready fast path leaked");
            p.socket.connected=false;CompletableFuture<Void> a=p.whenConnected(),b=p.whenConnected();List<Integer> order=new ArrayList<>();a.thenRun(()->order.add(1));b.thenRun(()->order.add(2));
            check(!a.isDone()&&!b.isDone()&&p.connectionWaiters.size()==2,"offline future finished early");p.socket.connected=true;p.connectEvent();p.connectEvent();check(order.equals(Arrays.asList(1,2))&&p.connectionWaiters.isEmpty(),"CONNECT wake order or cleanup");
            p.socket.connected=false;CompletableFuture<Void> canceled=p.whenConnected();canceled.cancel(false);check(p.connectionWaiters.isEmpty(),"cancel retained future");
            CompletableFuture<Void> closing=p.whenConnected();p.close();check(closing.isCompletedExceptionally()&&p.connectionWaiters.isEmpty(),"close did not fail wait");
            try{closing.join();throw new AssertionError("closed ready succeeded");}catch(CompletionException e){check(e.getCause() instanceof RpcNotDispatchedException,"close lost unsent proof");}
            p.socket.connected=true;p.connectEvent();check(p.whenConnected().isCompletedExceptionally()&&p.connectionWaiters.isEmpty(),"closed connection accepted new waiter");
            System.out.println("CONNECTION_READY immediate=true eventFIFO=true cancel=true close=true noReconnectOrRpc=true");
        }
        /** CONNECT恰在登记后状态读取时到达，真实双检仍完成原future。 */
        static void registrationRace()throws Exception{
            ConnectionReadyProbe p=new ConnectionReadyProbe();p.socket.onRead=()->{p.socket.connected=true;p.connectEvent();};
            check(p.whenConnected().isDone()&&p.connectionWaiters.isEmpty(),"CONNECT between registration and state check lost");
            for(int i=0;i<200;i++){
                ConnectionReadyProbe value=new ConnectionReadyProbe();CountDownLatch start=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> future=new java.util.concurrent.atomic.AtomicReference<>();
                Thread register=new Thread(()->{try{start.await();future.set(value.whenConnected());}catch(InterruptedException e){throw new AssertionError(e);}});
                Thread event=new Thread(()->{try{start.await();value.socket.connected=true;value.connectEvent();}catch(InterruptedException e){throw new AssertionError(e);}});
                register.start();event.start();start.countDown();register.join(2000);event.join(2000);check(!register.isAlive()&&!event.isAlive(),"readiness race deadlocked");
                check(future.get()!=null&&future.get().isDone()&&value.connectionWaiters.isEmpty(),"concurrent CONNECT lost or retained waiter");
            }
            System.out.println("CONNECTION_READY_RACE attempts=201 noLostWake=true noRetainedFuture=true");
        }
        /** 关闭与登记并发时原future必须结束，迟到事件不使closed实例可用。 */
        static void closeRace()throws Exception{
            for(int i=0;i<100;i++){
                ConnectionReadyProbe p=new ConnectionReadyProbe();CountDownLatch start=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> future=new java.util.concurrent.atomic.AtomicReference<>();
                Thread register=new Thread(()->{try{start.await();future.set(p.whenConnected());}catch(InterruptedException e){throw new AssertionError(e);}});
                Thread close=new Thread(()->{try{start.await();p.close();}catch(InterruptedException e){throw new AssertionError(e);}});
                register.start();close.start();start.countDown();register.join(2000);close.join(2000);check(!register.isAlive()&&!close.isAlive(),"close race deadlocked");
                check(future.get()!=null&&future.get().isCompletedExceptionally()&&p.connectionWaiters.isEmpty(),"close/register retained or accepted wait");
            }
            System.out.println("CONNECTION_READY_CLOSE_RACE attempts=100 allUnsent=true noRetainedFuture=true");
        }
        /** 执行实际生命周期与有限双线程竞态，不产生常驻线程。 */
        public static void main(String[] args)throws Exception{lifecycle();registrationRace();closeRace();}
        """;
}
