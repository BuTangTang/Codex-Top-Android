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

/** 真实helper、真实RPC方法及原版本Socket.IO库；仅WebSocket和Android平台使用合成边界。 */
public final class SocketAckWireTraceTest {
    /** 不启动Android或网络；可传前像证明新派发/ACK关联在旧源码缺失。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        Path source = args.length == 0 ? root.resolve("DesktopConnection.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source);
        Set<String> names = Set.of("rpcAsync", "awaitRpc", "sendAsync", "base", "userCodexSource", "failPending",
                "clearAttachmentUploadLimit", "retireFailedEmitTimer", "whenConnected", "completeConnectionWaiters", "close");
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
        Path temporary = Files.createTempDirectory("codex-wire-trace-");
        try {
            ArrayList<String> compile = new ArrayList<>(java.util.List.of("--release", "8", "-encoding", "UTF-8",
                    "-cp", System.getProperty("java.class.path"), "-d", temporary.toString()));
            Path probe = temporary.resolve("WireTraceProbe.java");
            Files.writeString(probe, FIXTURE + actual.toString().replace("extends CompletableFuture<JsonObject>", "extends TimedFuture<JsonObject>") + SCENARIOS + "\n}\n");
            compile.add(probe.toString());
            compile.add(args.length > 1 ? args[1] : root.resolve("SocketAckWireTrace.java").toString());
            compile.add(root.resolve("DesktopAttachment.java").toString());
            String[][] stubs = {
                {"BuildConfig.java", "package org.telegram.messenger; public class BuildConfig {public static boolean DEBUG_VERSION=true;}"},
                {"ApplicationLoader.java", "package org.telegram.messenger; public class ApplicationLoader {public static Context applicationContext=new Context(); public static class Context {public String name=\"com.butang.codextop.nativepreview.beta\"; public boolean fail; /** 合成包名边界。 */ public String getPackageName(){if(fail)throw new AssertionError();return name;}}}"},
                {"SystemClock.java", "package android.os; public class SystemClock {public static int reads;public static boolean fail; /** 合成单调时钟，不读取主机时钟。 */ public static long elapsedRealtime(){reads++;if(fail)throw new AssertionError();return reads;}}"},
                {"Log.java", "package android.util; public class Log {public static final int DEBUG=3;public static boolean enabled=true,failGate,failWrite;public static final java.util.List<String> lines=new java.util.concurrent.CopyOnWriteArrayList<>(); /** 可控标签。 */ public static boolean isLoggable(String t,int l){if(failGate)throw new AssertionError();return enabled;} /** 仅保存在内存的合成元数据。 */ public static int i(String t,String s){if(failWrite)throw new AssertionError();lines.add(s);return 0;}}"}
            };
            for (String[] stub : stubs) {
                Path file = temporary.resolve(stub[0]); Files.writeString(file, stub[1]); compile.add(file.toString());
            }
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实传输诊断/原RPC无法按Java8编译");
            ArrayList<URL> classpath = new ArrayList<>(); classpath.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                classpath.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.WireTraceProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实wire边界失败", error.getCause()); }
            }
            String text = Files.readString(source);
            if (!text.contains("wireTrace = SocketAckWireTrace.install(socket);")
                    || text.indexOf("SocketAckWireTrace.install(socket)") > text.indexOf("socket.connect();"))
                throw new AssertionError("原构造入口未在connect前安装诊断");
            System.out.println("WIRE_CONSTRUCTOR_ORDER=true");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;import java.io.*;import java.net.URI;import java.lang.reflect.*;
        import java.util.*;import java.util.concurrent.*;import java.util.function.BooleanSupplier;
        import io.socket.client.*;import io.socket.thread.EventThread;import io.socket.engineio.client.Transport;
        import org.json.JSONObject;import okhttp3.*;import okio.ByteString;
        public final class WireTraceProbe implements AutoCloseable {
            final Socket socket;final Crypto crypto=new Crypto();SocketAckWireTrace wireTrace;
            boolean closed;String machineId="synthetic-machine";static int checks;
            /** 合成连接只替换WebSocket工厂，Socket.IO事件、ACK表与Timer均使用真实库。 */
            WireTraceProbe(Socket socket){this.socket=socket;wireTrace=SocketAckWireTrace.install(socket);}
            /** 原Android网络回调在此专项不存在。 */void unwatchNetwork(){}
            static final class Crypto {
                int decrypts;
                /** 合成响应无需真实密钥。 */String encrypt(String s){return s;}
                /** 只计原RPC是否解密，迟ACK不得进入。 */String decrypt(String s){decrypts++;return s;}
                /** 无真实凭据。 */void close(){}
            }
            static boolean forceWaitTimeout;
            /** 只替换等待边界以确定性执行真实awaitRpc，不延长生产期限或实等25秒。 */
            static class TimedFuture<T> extends CompletableFuture<T> {
                @Override public T get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException {
                    if(forceWaitTimeout){check(timeout==25&&unit==TimeUnit.SECONDS,"原等待期限改变");throw new TimeoutException("NEVER_LOG_WAIT");}
                    return super.get(timeout,unit);
                }
            }
            static final class FakeSocket implements WebSocket {
                Request request;final List<String> sent=new CopyOnWriteArrayList<>();int closes,cancels,queueReads;long queued;boolean failQueue;Runnable beforeSend;
                /** 返回同一请求，不输出地址。 */public Request request(){return request;}
                /** 合成可控读取异常；真实非零队列另由原OkHttp实现验证。 */public long queueSize(){queueReads++;if(failQueue)throw new AssertionError("NEVER_LOG_QUEUE");return queued;}
                /** 保存合成协议用于核对ACK编号，不联网。 */public boolean send(String s){if(beforeSend!=null)beforeSend.run();sent.add(s);queued+=s.length();return true;}
                /** 合成二进制派发只返回原布尔。 */public boolean send(ByteString s){return true;}
                /** 只记关闭，原心跳由Socket.disconnect停止。 */public boolean close(int c,String r){closes++;return true;}
                /** 只记取消。 */public void cancel(){cancels++;}
            }
            static final class Factory implements WebSocket.Factory {
                final FakeSocket ws=new FakeSocket();WebSocketListener listener;int calls;RuntimeException failure;
                /** 唯一创建边界：所有回调由场景驱动，不访问网络。 */public WebSocket newWebSocket(Request r,WebSocketListener l){
                    calls++;ws.request=r;listener=l;if(failure!=null)throw failure;return ws;}
            }
            /** 通过真实事件线程屏障，不用sleep推测排队完成。 */
            static void event(Runnable r)throws Exception{CompletableFuture<Void> done=new CompletableFuture<>();EventThread.exec(()->{
                try{r.run();done.complete(null);}catch(Throwable t){done.completeExceptionally(t);}});done.get(3,TimeUnit.SECONDS);}
            /** 普通断言同时累计本专项证据。 */static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
            /** 重置合成Android开关与时钟，不触碰设备或真实日志。 */static void reset(){
                org.telegram.messenger.BuildConfig.DEBUG_VERSION=true;android.util.Log.enabled=true;android.util.Log.failGate=false;
                android.util.Log.failWrite=false;android.util.Log.lines.clear();android.os.SystemClock.reads=0;android.os.SystemClock.fail=false;
                org.telegram.messenger.ApplicationLoader.applicationContext=new org.telegram.messenger.ApplicationLoader.Context();}
            /** 创建forceNew原Socket，保持websocket及原定时器语义。 */static Socket socket(Factory factory)throws Exception{
                IO.Options o=new IO.Options();o.forceNew=true;o.multiplex=false;o.reconnection=false;o.transports=new String[]{"websocket"};
                o.webSocketFactory=factory;return IO.socket(URI.create("https://example.invalid"),o);}
            /** 通过合成握手驱动实际Engine.IO与Socket.IO，不调用远端。 */static void connect(Socket s,Factory f)throws Exception{
                s.connect();event(()->{});check(f.calls==1&&f.listener!=null,"原factory未被恰好调用一次");
                Response response=new Response.Builder().request(f.ws.request).protocol(Protocol.HTTP_1_1).code(101).message("synthetic").build();
                f.listener.onOpen(f.ws,response);event(()->{});
                f.listener.onMessage(f.ws,"0{\\"sid\\":\\"synthetic\\",\\"upgrades\\":[],\\"pingInterval\\":600000,\\"pingTimeout\\":600000}");event(()->{});
                f.listener.onMessage(f.ws,"40{\\"sid\\":\\"synthetic\\"}");event(()->{});check(s.connected(),"合成原库握手失败");
            }
            /** 返回原transport中实际保存的factory；只供测试核对引用恒等。 */static WebSocket.Factory factory(Transport t)throws Exception{
                Field f=Transport.class.getDeclaredField("webSocketFactory");f.setAccessible(true);return (WebSocket.Factory)f.get(t);}
            /** 对照输出固定阶段，不依赖正文或地址。 */static long count(String phase){return android.util.Log.lines.stream().filter(x->x.startsWith("send_wire_phase="+phase+" ")).count();}
            /** 构造合成ACK封套，远端结果仍由提取的真实RPC验证。 */static String ack(int id){return "43"+id+"[{\\"ok\\":true,\\"result\\":\\"{\\\\\\"ok\\\\\\":true}\\"}]";}
        """;

    private static final String SCENARIOS = """
            /** 通过反射只调用新增观察入口，使旧源码也能编译并先得到实际行为RED。 */static void queueCall(SocketAckWireTrace trace,String method,int key,int generation)throws Exception{
                Method m=SocketAckWireTrace.class.getDeclaredMethod(method,int.class,int.class);m.setAccessible(true);m.invoke(trace,key,generation);
            }
            /** 快照只含固定元数据；未发现符合阶段/代次/队列值的记录即失败。 */static boolean queueLine(String phase,int generation,long bytes,String state){
                return android.util.Log.lines.stream().anyMatch(x->x.startsWith("send_wire_phase="+phase+" ")&&x.contains(" transport="+generation+" ")
                    &&x.contains(" queueBytes="+bytes+" queueState="+state+" "));
            }
            /** 原OkHttp的未启动writer队列是实际非零积压，不以合成queueSize代替此核心断言。 */static void queueSnapshots()throws Exception{
                reset();Request request=new Request.Builder().url("https://example.invalid").build();
                okhttp3.internal.ws.RealWebSocket real=new okhttp3.internal.ws.RealWebSocket(request,new WebSocketListener(){},new Random(7),0);
                check(real.send("synthetic-queued"),"真实OkHttp未接收合成帧");check(real.queueSize()==16,"真实OkHttp队列字节不符");
                Factory f=new Factory();Socket s=socket(f);SocketAckWireTrace trace=SocketAckWireTrace.install(s);
                final WebSocketListener[] observer={null};final int[] opens={0};
                Transport.Options o=new Transport.Options();o.webSocketFactory=(r,l)->{observer[0]=l;check(r==request,"工厂请求引用改变");return real;};
                Transport first=new io.socket.engineio.client.transports.WebSocket(o);s.io().emit(Manager.EVENT_TRANSPORT,first);
                Response response=new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("synthetic").build();
                WebSocketListener original=new WebSocketListener(){/** 验证原握手引用与次数。 */public void onOpen(WebSocket w,Response r){check(w==real&&r==response,"原onOpen引用变化");opens[0]++;}};
                check(factory(first).newWebSocket(request,original)==real,"观察替换了原RealWebSocket");
                trace.emitEntered(11);boolean unopened=queueLine("emit_enter",1,-1,"unavailable");
                observer[0].onOpen(real,response);trace.emitEntered(12);
                check(queueLine("emit_enter",1,16,"available"),"旧源码缺少真实OkHttp未写队列快照");
                check(unopened,"握手前队列必须未知");check(opens[0]==1,"原onOpen未恰好转发一次");
                WebSocketListener old=observer[0];Transport.Options nextOptions=new Transport.Options();nextOptions.webSocketFactory=f;
                Transport next=new io.socket.engineio.client.transports.WebSocket(nextOptions);s.io().emit(Manager.EVENT_TRANSPORT,next);
                factory(next).newWebSocket(request,new WebSocketListener(){});f.ws.queued=77;f.listener.onOpen(f.ws,response);
                old.onOpen(real,response);old.onFailure(real,new IOException("NEVER_LOG_OLD"),response);old.onClosed(real,1006,"NEVER_LOG_CLOSE");
                trace.emitEntered(13);check(queueLine("emit_enter",2,77,"available"),"旧代次onOpen/终态覆盖了新ws");
                int reads=f.ws.queueReads;queueCall(trace,"emitReturned",14,1);check(queueLine("emit_return",1,-1,"stale")&&reads==f.ws.queueReads,"旧请求采样了新ws");
                f.ws.failQueue=true;trace.emitEntered(15);check(queueLine("emit_enter",2,-1,"error"),"queueSize异常未隔离");f.ws.failQueue=false;
                f.listener.onClosed(f.ws,1000,"NEVER_LOG_CLOSE");trace.emitEntered(16);check(queueLine("emit_enter",2,-1,"retired"),"已关闭ws仍被当可观察");
                f.listener.onOpen(f.ws,response);trace.emitEntered(17);check(f.ws.queueReads==reads+1,"关闭后晚onOpen复活观察");
                grammar();
            }
            /** 已安装观察在关标签重连时也必须失效旧代次，不能重开标签后读取尚未终态的旧ws。 */static void queueGateReconnect()throws Exception{
                for(int mode=0;mode<2;mode++){
                    reset();Factory first=new Factory();Socket s=socket(first);SocketAckWireTrace trace=SocketAckWireTrace.install(s);
                    Transport.Options options=new Transport.Options();options.webSocketFactory=first;
                    Transport old=new io.socket.engineio.client.transports.WebSocket(options);event(()->s.io().emit(Manager.EVENT_TRANSPORT,old));
                    Request request=new Request.Builder().url("https://example.invalid").build();factory(old).newWebSocket(request,new WebSocketListener(){});
                    first.listener.onOpen(first.ws,null);first.ws.queued=1234;trace.emitEntered(20);
                    Field captured=SocketAckWireTrace.class.getDeclaredField("observedSocket");captured.setAccessible(true);
                    Field generation=SocketAckWireTrace.class.getDeclaredField("generation");generation.setAccessible(true);
                    if(mode==0)android.util.Log.enabled=false;else for(int i=0;i<600;i++)trace.emitEntered(i);
                    int reads=first.ws.queueReads,clocks=android.os.SystemClock.reads,size=android.util.Log.lines.size();
                    // 原Engine.IO关闭仅提出close；故意不送终态，覆盖旧WebSocket仍等待关闭握手的窗口。
                    first.ws.close(1000,"");Factory next=new Factory();Transport.Options newer=new Transport.Options();newer.webSocketFactory=next;
                    Transport replacement=new io.socket.engineio.client.transports.WebSocket(newer);event(()->s.io().emit(Manager.EVENT_TRANSPORT,replacement));
                    check(captured.get(trace)==null&&generation.getInt(trace)==2,"关标签/封顶后新transport未失效旧观察");
                    check(factory(replacement)==next,"关标签/封顶后仍反射替换新工厂");
                    factory(replacement).newWebSocket(request,new WebSocketListener(){});next.listener.onOpen(next.ws,null);next.ws.queued=88;
                    check(first.ws.queueReads==reads&&android.os.SystemClock.reads==clocks&&android.util.Log.lines.size()==size,"失效旧观察进行了诊断读写");
                    android.util.Log.enabled=true;trace.emitEntered(21);queueCall(trace,"timedOut",20,1);
                    check(first.ws.queueReads==reads&&next.ws.queueReads==0,"重开标签读取了旧代次或未观察的新ws");
                    if(mode==0){
                        check(queueLine("emit_enter",2,-1,"unavailable")&&queueLine("terminal_timeout",1,-1,"stale"),"关门期间的新代次被当作旧连接可用");
                        first.listener.onOpen(first.ws,null);first.listener.onClosed(first.ws,1000,"");
                        check(captured.get(trace)==null&&generation.getInt(trace)==2,"旧握手/终态改变了关门期间的新代次");
                    }else check(android.util.Log.lines.size()==512,"重连重置了共用512额度");
                    grammar();
                }
            }
            /** 真实Socket.IO派发同线程返回后才读入队值，终态超时一次且迟ACK不追加超时/重发。 */static void bulkQueue()throws Exception{
                reset();Factory f=new Factory();WireTraceProbe p=new WireTraceProbe(socket(f));try{
                    connect(p.socket,f);f.ws.queued=17;android.util.Log.lines.clear();
                    CompletableFuture<JsonObject> result=p.rpcAsync("daemon.bulkTransfer.upload.chunk",new JsonObject(),null,null);event(()->{});
                    check(queueLine("emit_enter",1,17,"available"),"emit前未采样既有队列");
                    check(queueLine("emit_return",1,f.ws.queued,"available")&&f.ws.queued>17,"emit返回未观察实际库已调用send后的队列");
                    check(count("emit_return")==1,"emit_return重复或丢失");
                    final AckWithTimeout[] retired={null};event(()->{try{Field field=Socket.class.getDeclaredField("acks");field.setAccessible(true);
                        retired[0]=(AckWithTimeout)((Map<?,?>)field.get(p.socket)).remove(0);retired[0].onTimeout();
                    }catch(Exception e){throw new RuntimeException(e);}});
                    check(result.isCompletedExceptionally()&&queueLine("terminal_timeout",1,f.ws.queued,"available"),"原超时终态缺少队列快照");
                    int before=f.ws.queueReads;retired[0].onSuccess(new JSONObject().put("ok",true).put("result","{\\"success\\":true}"));
                    check(count("terminal_timeout")==1&&f.ws.queueReads==before&&p.crypto.decrypts==0,"迟ACK重复超时或继续读取/解密");
                    check(f.ws.sent.size()==2,"队列诊断新增RPC");grammar();
                }finally{p.close();event(()->{});}
            }
            /** 等待方先于future到期时沿原cancel，仅共用一次快照；成功和读取故障不改变原RPC终态。 */static void waitTimeoutQueue()throws Exception{
                reset();Factory f=new Factory();WireTraceProbe p=new WireTraceProbe(socket(f));try{
                    connect(p.socket,f);android.util.Log.lines.clear();
                    CompletableFuture<JsonObject> result=p.rpcAsync("daemon.bulkTransfer.upload.chunk",new JsonObject(),null,null);event(()->{});
                    forceWaitTimeout=true;try{p.awaitRpc(result);throw new AssertionError("原等待未超时");}catch(TimeoutException expected){}finally{forceWaitTimeout=false;}
                    check(result.isCancelled()&&queueLine("terminal_timeout",1,f.ws.queued,"available"),"get先到期丢失原队列或取消语义");
                    event(()->{try{Field field=Socket.class.getDeclaredField("acks");field.setAccessible(true);
                        AckWithTimeout retired=(AckWithTimeout)((Map<?,?>)field.get(p.socket)).remove(0);retired.onTimeout();
                    }catch(Exception e){throw new RuntimeException(e);}});
                    check(count("terminal_timeout")==1,"get与future重复终态快照");
                    android.util.Log.lines.clear();f.ws.failQueue=true;
                    CompletableFuture<JsonObject> success=p.rpcAsync("daemon.bulkTransfer.upload.finalize",new JsonObject(),null,null);event(()->{});
                    check(queueLine("emit_enter",1,-1,"error")&&queueLine("emit_return",1,-1,"error"),"原派发两端未隔离队列异常");
                    f.listener.onMessage(f.ws,"431"+new org.json.JSONArray().put(new JSONObject().put("ok",true).put("result",new JSONObject().put("success",true).toString())).toString());event(()->{});
                    check(success.get(3,TimeUnit.SECONDS).get("success").getAsBoolean()&&count("terminal_timeout")==0,"观察异常改变成功响应或生成超时");
                    check(f.ws.sent.size()==3,"等待/诊断新增派发");grammar();
                }finally{forceWaitTimeout=false;p.close();event(()->{});}
            }
            /** 生命周期终态即使关标签/封顶也释放引用，且不额外读取任何诊断数据。 */static void queueRelease()throws Exception{
                for(int mode=0;mode<2;mode++){
                    reset();Factory f=new Factory();Socket s=socket(f);SocketAckWireTrace trace=SocketAckWireTrace.install(s);
                    Transport.Options options=new Transport.Options();options.webSocketFactory=f;
                    Transport transport=new io.socket.engineio.client.transports.WebSocket(options);s.io().emit(Manager.EVENT_TRANSPORT,transport);
                    Request request=new Request.Builder().url("https://example.invalid").build();final int[] forwarded={0};
                    factory(transport).newWebSocket(request,new WebSocketListener(){
                        /** 原终态监听仍收到原socket一次。 */public void onFailure(WebSocket w,Throwable e,Response r){check(w==f.ws,"failure引用改变");forwarded[0]++;}
                        /** 原终态监听仍收到原socket一次。 */public void onClosed(WebSocket w,int code,String reason){check(w==f.ws,"closed引用改变");forwarded[0]++;}
                    });
                    f.listener.onOpen(f.ws,null);Field captured=SocketAckWireTrace.class.getDeclaredField("observedSocket");captured.setAccessible(true);
                    check(captured.get(trace)==f.ws,"未捕获原ws");
                    if(mode==0)android.util.Log.enabled=false;else for(int i=0;i<600;i++)trace.emitEntered(i);
                    int reads=f.ws.queueReads,clocks=android.os.SystemClock.reads,size=android.util.Log.lines.size();
                    if(mode==0)f.listener.onFailure(f.ws,new IOException("NEVER_LOG_END"),null);else f.listener.onClosed(f.ws,1000,"NEVER_LOG_END");
                    check(captured.get(trace)==null&&forwarded[0]==1,"关闭/封顶后的终态仍持有旧ws或未透传");
                    check(f.ws.queueReads==reads&&android.os.SystemClock.reads==clocks&&android.util.Log.lines.size()==size,"无条件引用清理进行了诊断读写");
                }
            }
            /** 已安装观察也受三门和共用额度约束；关闭/耗尽不读取queueSize、时钟或记录。 */static void queueGates()throws Exception{
                reset();Factory f=new Factory();WireTraceProbe p=new WireTraceProbe(socket(f));try{
                    connect(p.socket,f);SocketAckWireTrace trace=p.wireTrace;
                    for(int mode=0;mode<6;mode++){
                        int reads=f.ws.queueReads,clocks=android.os.SystemClock.reads,size=android.util.Log.lines.size();
                        if(mode==0)org.telegram.messenger.BuildConfig.DEBUG_VERSION=false;if(mode==1)android.util.Log.enabled=false;
                        if(mode==2)org.telegram.messenger.ApplicationLoader.applicationContext.name="org.telegram.messenger";
                        if(mode==3)org.telegram.messenger.ApplicationLoader.applicationContext=null;if(mode==4)android.util.Log.failGate=true;
                        if(mode==5)org.telegram.messenger.ApplicationLoader.applicationContext.fail=true;
                        trace.emitEntered(1);queueCall(trace,"emitReturned",1,1);queueCall(trace,"timedOut",1,1);
                        check(f.ws.queueReads==reads&&android.os.SystemClock.reads==clocks&&android.util.Log.lines.size()==size,"已装观察关门仍读队列/时钟");
                        org.telegram.messenger.BuildConfig.DEBUG_VERSION=true;android.util.Log.enabled=true;android.util.Log.failGate=false;
                        org.telegram.messenger.ApplicationLoader.applicationContext=new org.telegram.messenger.ApplicationLoader.Context();
                    }
                    f.ws.queued=0;trace.emitEntered(2);check(queueLine("emit_enter",1,0,"available"),"零队列未按原数值记录");
                    for(int i=0;i<600;i++)trace.emitEntered(i);
                    int reads=f.ws.queueReads,clocks=android.os.SystemClock.reads,size=android.util.Log.lines.size();
                    queueCall(trace,"emitReturned",3,1);queueCall(trace,"timedOut",3,1);trace.emitEntered(3);
                    check(size==512&&f.ws.queueReads==reads&&android.os.SystemClock.reads==clocks&&android.util.Log.lines.size()==size,"新快照绕过共用512额度");grammar();
                }finally{p.close();event(()->{});}
            }
            /** 实际原RPC派发、最早ACK、库退役后的迟ACK及关联全部走真实Socket库。 */static void rpcChain()throws Exception{
                reset();Factory f=new Factory();WireTraceProbe p=new WireTraceProbe(socket(f));
                try{
                    connect(p.socket,f);check(count("raw_text")==2,"未观察最早Engine/Socket握手帧");android.util.Log.lines.clear();
                    f.ws.beforeSend=()->check(count("emit_enter")==1,"emit日志不在真实派发前");
                    CompletableFuture<JsonObject> result=p.sendAsync("synthetic-session","NEVER_LOG_BODY","synthetic-id",Collections.emptyList(),null,null);
                    event(()->{});f.ws.beforeSend=null;check(count("emit_enter")==1,"旧源码缺少实际SEND emit入口");
                    check(f.ws.sent.stream().anyMatch(x->x.startsWith("420[")),"真实SEND未使用预期ACK编号");
                    CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
                    EventThread.exec(()->{occupied.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("测试屏障未释放");}catch(InterruptedException e){throw new RuntimeException(e);}});
                    check(occupied.await(3,TimeUnit.SECONDS),"原事件线程屏障未进入");
                    try{f.listener.onMessage(f.ws,ack(0));check(count("raw_text")==1&&count("packet")==0&&!result.isDone(),"raw探针实际位于EventThread之后");}
                    finally{release.countDown();}
                    event(()->{});check(result.get(3,TimeUnit.SECONDS).get("ok").getAsBoolean(),"ACK成功结果改变");
                    List<String> lines=android.util.Log.lines;int raw=-1,packet=-1,entry=-1;
                    for(int i=0;i<lines.size();i++){String x=lines.get(i);if(x.startsWith("send_wire_phase=raw_text ")&&x.contains(" ackId=0 "))raw=i;
                        if(x.startsWith("send_wire_phase=packet ")&&x.contains(" ackId=0 "))packet=i;
                        if(x.startsWith("send_wire_phase=ack_enter ")&&x.contains(" ackId=0 "))entry=i;}
                    check(raw>=0&&raw<packet&&packet<entry,"raw、packet、原ACK入口顺序/编号错误");
                    check(lines.get(entry).contains(" key="+"synthetic-id".hashCode()+" "),"ACK未关联现有消息摘要");
                    CompletableFuture<JsonObject> late=p.sendAsync("synthetic-session","NEVER_LOG_BODY","late-id",Collections.emptyList(),null,null);event(()->{});
                    event(()->{try{Field field=Socket.class.getDeclaredField("acks");field.setAccessible(true);
                        AckWithTimeout retired=(AckWithTimeout)((Map<?,?>)field.get(p.socket)).remove(1);retired.onTimeout();
                    }catch(Exception e){throw new RuntimeException(e);}});
                    check(late.isCompletedExceptionally(),"退役ACK未沿原超时终态");int decrypts=p.crypto.decrypts;long entered=count("ack_enter");
                    f.listener.onMessage(f.ws,ack(1));event(()->{});
                    check(count("packet")==2&&count("ack_enter")==entered&&p.crypto.decrypts==decrypts,"退役ACK丢观察或重新解密");
                    check(f.ws.sent.size()==3,"诊断新增或重投RPC");
                    grammar();
                }finally{p.close();event(()->{});}
            }
            /** 所有原监听方法、参数、WebSocket返回及异常保持；观察失败也必须继续转发。 */static void forwarding()throws Exception{
                reset();Factory original=new Factory();Socket s=socket(original);SocketAckWireTrace trace=SocketAckWireTrace.install(s);
                Transport.Options o=new Transport.Options();o.webSocketFactory=original;
                Transport t=new io.socket.engineio.client.transports.WebSocket(o);s.io().emit(Manager.EVENT_TRANSPORT,t);
                WebSocket.Factory wrapped=factory(t);check(wrapped!=original,"未包原工厂");
                Request request=new Request.Builder().url("https://example.invalid").build();
                Response response=new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("synthetic").build();
                String body="43"+7+"[NEVER_LOG_BODY]",reason="NEVER_LOG_REASON";ByteString bytes=ByteString.encodeUtf8("NEVER_LOG_BINARY");
                Throwable failure=new IOException("NEVER_LOG_ERROR");int[] calls=new int[6];RuntimeException own=new RuntimeException("original");boolean[] throwsOwn={false};
                WebSocketListener downstream=new WebSocketListener(){
                    /** 核对原握手引用。 */public void onOpen(WebSocket w,Response r){check(w==original.ws&&r==response,"onOpen参数变化");calls[0]++;}
                    /** 核对原文本引用及原异常。 */public void onMessage(WebSocket w,String x){check(w==original.ws&&x==body,"text参数变化");calls[1]++;if(throwsOwn[0])throw own;}
                    /** 核对原二进制引用。 */public void onMessage(WebSocket w,ByteString x){check(w==original.ws&&x==bytes,"bytes参数变化");calls[2]++;}
                    /** 核对关闭中原参。 */public void onClosing(WebSocket w,int c,String r){check(w==original.ws&&c==1000&&r==reason,"closing参数变化");calls[3]++;}
                    /** 核对关闭原参。 */public void onClosed(WebSocket w,int c,String r){check(w==original.ws&&c==1000&&r==reason,"closed参数变化");calls[4]++;}
                    /** 核对失败原参。 */public void onFailure(WebSocket w,Throwable e,Response r){check(w==original.ws&&e==failure&&r==response,"failure参数变化");calls[5]++;}
                };
                check(wrapped.newWebSocket(request,downstream)==original.ws&&original.calls==1&&original.ws.request==request,"替换了请求、WebSocket或重复调用factory");
                WebSocketListener listener=original.listener;listener.onOpen(original.ws,response);listener.onMessage(original.ws,body);
                listener.onMessage(original.ws,bytes);listener.onClosing(original.ws,1000,reason);listener.onClosed(original.ws,1000,reason);listener.onFailure(original.ws,failure,response);
                for(int c:calls)check(c==1,"某监听事件未恰好转发一次");check(original.ws.closes==0&&original.ws.cancels==0,"观察擅自关闭了WebSocket");
                android.os.SystemClock.fail=true;listener.onMessage(original.ws,body);android.os.SystemClock.fail=false;
                android.util.Log.failWrite=true;listener.onMessage(original.ws,body);android.util.Log.failWrite=false;
                android.util.Log.failGate=true;listener.onMessage(original.ws,body);android.util.Log.failGate=false;
                check(calls[1]==4,"观察异常阻断了原消息");throwsOwn[0]=true;
                try{listener.onMessage(original.ws,body);throw new AssertionError("吞掉原listener异常");}catch(RuntimeException e){check(e==own,"原listener异常被替换");}
                original.failure=own;int before=original.calls;try{wrapped.newWebSocket(request,downstream);throw new AssertionError("吞掉原factory异常");}
                catch(RuntimeException e){check(e==own&&original.calls==before+1,"原factory失败被替换或重试");}grammar();
            }
            /** 真实库Manager ACK编号与bulk进程序号通过同一wire入口相连，不把packet当业务成功。 */static void bulkChain()throws Exception{
                reset();Factory f=new Factory();WireTraceProbe p=new WireTraceProbe(socket(f));try{
                    connect(p.socket,f);android.util.Log.lines.clear();
                    CompletableFuture<JsonObject> result=p.rpcAsync("daemon.bulkTransfer.upload.chunk",new JsonObject(),null,null);event(()->{});
                    String frame="430"+new org.json.JSONArray().put(new JSONObject().put("ok",true).put("result",new JSONObject().put("success",false).put("error","NEVER_LOG_ERROR").toString())).toString();
                    f.listener.onMessage(f.ws,frame);event(()->{});
                    check(!result.get(3,TimeUnit.SECONDS).get("success").getAsBoolean(),"bulk失败封套被改变");
                    String start=android.util.Log.lines.stream().filter(x->x.startsWith("bulk_ack_phase=rpc_start ")).findFirst().orElseThrow(AssertionError::new);
                    String seq=start.substring(start.indexOf(" seq=")+5,start.indexOf(" method="));
                    check(android.util.Log.lines.stream().anyMatch(x->x.startsWith("send_wire_phase=ack_enter ")&&x.contains(" ackId=0 ")&&x.contains(" key="+seq+" ")),"bulk未关联实际Manager ACK编号");
                    check(android.util.Log.lines.stream().anyMatch(x->x.startsWith("bulk_ack_phase=response ")&&x.contains(" valid=1 success=0 ")),"合法失败未被区分");grammar();
                }finally{p.close();event(()->{});}
            }
            /** 退役代次的失败/关闭仍归原transport；每个回调原参一次，固定分类不读取异常文本。 */static void transportEnd()throws Exception{
                reset();Factory f=new Factory();Socket s=socket(f);SocketAckWireTrace trace=SocketAckWireTrace.install(s);
                Transport.Options o=new Transport.Options();o.webSocketFactory=f;Transport old=new io.socket.engineio.client.transports.WebSocket(o);
                s.io().emit(Manager.EVENT_TRANSPORT,old);final Throwable[] expected={null};final int[] counts={0,0};final boolean[] own={false};
                RuntimeException originalError=new RuntimeException("NEVER_LOG_ORIGINAL");
                WebSocketListener downstream=new WebSocketListener(){
                    /** 核验Throwable/Response引用且允许原监听主动抛错。 */public void onFailure(WebSocket w,Throwable e,Response r){
                        check(w==f.ws&&e==expected[0]&&r==null,"失败回调原参改变");counts[0]++;if(own[0])throw originalError;}
                    /** 核验原关闭码和原因引用。 */public void onClosed(WebSocket w,int code,String reason){
                        check(w==f.ws&&code==1006&&reason.equals("NEVER_LOG_CLOSE"),"关闭回调原参改变");counts[1]++;if(own[0])throw originalError;}
                };
                factory(old).newWebSocket(new Request.Builder().url("https://example.invalid").build(),downstream);
                WebSocketListener observer=f.listener;Transport newer=new io.socket.engineio.client.transports.WebSocket(o);s.io().emit(Manager.EVENT_TRANSPORT,newer);
                Throwable[] failures={new java.net.SocketTimeoutException("NEVER_LOG_TIMEOUT"),new java.util.concurrent.TimeoutException("NEVER_LOG_TIMEOUT"),
                    new IOException("NEVER_LOG_IO"),new RuntimeException("NEVER_LOG_OTHER"),null};
                for(Throwable failure:failures){expected[0]=failure;observer.onFailure(f.ws,failure,null);}
                observer.onClosed(f.ws,1006,"NEVER_LOG_CLOSE");
                check(count("failure_timeout")==2&&count("failure_io")==1&&count("failure_other")==2&&count("closed")==1,"原失败固定分类或关闭码缺失");
                for(String line:android.util.Log.lines)check(line.contains(" transport=1 "),"退役传输错误归入新代次");
                check(android.util.Log.lines.get(5).contains(" closeCode=1006 "),"未保留数字关闭码");
                for(int mode=0;mode<4;mode++){
                    if(mode==0)android.util.Log.enabled=false;if(mode==1)android.util.Log.failGate=true;
                    if(mode==2)android.os.SystemClock.fail=true;if(mode==3)android.util.Log.failWrite=true;
                    observer.onFailure(f.ws,expected[0],null);observer.onClosed(f.ws,1006,"NEVER_LOG_CLOSE");
                    android.util.Log.enabled=true;android.util.Log.failGate=false;android.os.SystemClock.fail=false;android.util.Log.failWrite=false;
                }
                check(counts[0]==9&&counts[1]==5,"诊断故障阻断或重复原回调");
                own[0]=true;try{observer.onFailure(f.ws,expected[0],null);throw new AssertionError("吞掉原failure异常");}catch(RuntimeException e){check(e==originalError,"替换原failure异常");}
                try{observer.onClosed(f.ws,1006,"NEVER_LOG_CLOSE");throw new AssertionError("吞掉原closed异常");}catch(RuntimeException e){check(e==originalError,"替换原closed异常");}
                for(int i=0;i<600;i++)trace.emitEntered(i);int reads=android.os.SystemClock.reads,size=android.util.Log.lines.size();own[0]=false;
                observer.onFailure(f.ws,expected[0],null);observer.onClosed(f.ws,1006,"NEVER_LOG_CLOSE");
                check(android.os.SystemClock.reads==reads&&android.util.Log.lines.size()==size,"退役观察绕过原512上限");grammar();
            }
            /** 关闭三门时零诊断时钟、零工厂反射；安装后关标签也不再记录。 */static void disabled()throws Exception{
                for(int mode=0;mode<6;mode++){reset();
                    if(mode==0)org.telegram.messenger.BuildConfig.DEBUG_VERSION=false;
                    if(mode==1)android.util.Log.enabled=false;
                    if(mode==2)org.telegram.messenger.ApplicationLoader.applicationContext.name="org.telegram.messenger";
                    if(mode==3)org.telegram.messenger.ApplicationLoader.applicationContext=null;
                    if(mode==4)android.util.Log.failGate=true;
                    if(mode==5)org.telegram.messenger.ApplicationLoader.applicationContext.fail=true;
                    check(SocketAckWireTrace.install(socket(new Factory()))==null,"关闭门仍安装观察");
                    check(android.os.SystemClock.reads==0&&android.util.Log.lines.isEmpty(),"关闭门仍读钟或记录");}
                reset();Factory f=new Factory();Socket s=socket(f);SocketAckWireTrace trace=SocketAckWireTrace.install(s);
                Transport.Options o=new Transport.Options();o.webSocketFactory=f;Transport t=new io.socket.engineio.client.transports.WebSocket(o);
                android.util.Log.enabled=false;s.io().emit(Manager.EVENT_TRANSPORT,t);trace.emitEntered(7);trace.ackEntered(7);
                check(factory(t)==f&&android.os.SystemClock.reads==0,"安装后关门仍换工厂或读钟");
            }
            /** 禁止反射的环境保留原工厂与事件链；安全管理器只在当前合成测试进程生效。 */static void reflectionFailure()throws Exception{
                reset();Factory f=new Factory();Socket s=socket(f);SocketAckWireTrace.install(s);Transport.Options o=new Transport.Options();o.webSocketFactory=f;
                Transport t=new io.socket.engineio.client.transports.WebSocket(o);int[] attempts={0};
                SecurityManager old=System.getSecurityManager();
                try{System.setSecurityManager(new SecurityManager(){
                    /** 仅阻断测试中的工厂反射，其余权限沿原测试进程。 */public void checkPermission(java.security.Permission p){
                        if(p instanceof java.lang.reflect.ReflectPermission){attempts[0]++;throw new SecurityException("synthetic");}}
                });
                    android.util.Log.enabled=false;SocketAckWireTrace.install(null);s.io().emit(Manager.EVENT_TRANSPORT,t);
                    check(attempts[0]==0,"默认关闭仍反射");android.util.Log.enabled=true;s.io().emit(Manager.EVENT_TRANSPORT,t);
                }finally{System.setSecurityManager(old);}
                check(attempts[0]==1&&factory(t)==f&&count("unavailable")==1,"反射失败替换原工厂或阻断回调");
            }
            /** 坏前缀、溢出和超长输入都有限解析，不调用subSequence或扫描正文。 */static void prefixes(){
                reset();String[] inputs={"430[]","432147483647[]","43/ns,25[]","461-9[]","46/ns,9[]","432147483648[]","43-1[]","42[]","43[]","43/"+String.join("",Collections.nCopies(200,"x"))+",1[]","43","",null};
                int[] expected={0,Integer.MAX_VALUE,25,9,-1,-1,-1,-1,-1,-1,-1,-1,-1};
                for(int i=0;i<inputs.length;i++)check(SocketAckWireTrace.ackId(inputs[i])==expected[i],"坏前缀或编号误判"+i);
                final int[] max={0};CharSequence huge=new CharSequence(){
                    /** 模拟大包，不实际分配正文。 */public int length(){return Integer.MAX_VALUE;}
                    /** 超过前缀边界就失败。 */public char charAt(int i){max[0]=Math.max(max[0],i);if(i>=64)throw new AssertionError("扫描了大包");return i==0?'4':i==1?'3':i==2?'/':'x';}
                    /** 产品不得复制输入子串。 */public CharSequence subSequence(int a,int b){throw new AssertionError("复制正文");}
                };check(SocketAckWireTrace.ackId(huge)==-1&&max[0]<64,"大包前缀不受限");
            }
            /** 有限额度在大量重复/恶意帧下也不继续读取时钟或保留正文。 */static void budget()throws Exception{
                reset();Factory f=new Factory();SocketAckWireTrace trace=SocketAckWireTrace.install(socket(f));
                for(int i=0;i<1000;i++)trace.emitEntered(i);check(android.util.Log.lines.size()==512&&android.os.SystemClock.reads==512,"诊断无界");
                int reads=android.os.SystemClock.reads;trace.ackEntered(1);check(reads==android.os.SystemClock.reads,"额度用完仍读钟");grammar();
            }
            /** 同一事件线程的两台电脑连接不能把相同ACK编号混为一条。 */static void connections()throws Exception{
                reset();Socket one=socket(new Factory()),two=socket(new Factory());
                SocketAckWireTrace a=SocketAckWireTrace.install(one),b=SocketAckWireTrace.install(two);
                io.socket.parser.Packet<org.json.JSONArray> packet=new io.socket.parser.Packet<>(io.socket.parser.Parser.ACK,new org.json.JSONArray());packet.id=7;
                one.io().emit(Manager.EVENT_PACKET,packet);two.io().emit(Manager.EVENT_PACKET,packet);
                String first=android.util.Log.lines.get(0),second=android.util.Log.lines.get(1);
                check(first.contains(" ackId=7 ")&&second.contains(" ackId=7 "),"未覆盖两连接相同ACK编号");
                check(!first.substring(first.indexOf(" connection="),first.indexOf(" transport=")).equals(
                    second.substring(second.indexOf(" connection="),second.indexOf(" transport="))),"多连接编号相撞");grammar();
            }
            /** 日志只允许固定词及数字，包含合成敏感文本即失败。 */static void grammar(){
                for(String line:android.util.Log.lines){check(!line.contains("NEVER_LOG")&&!line.contains("example.invalid")&&!line.contains("synthetic-session"),"泄漏正文/地址/会话");
                    if(line.startsWith("send_wire_phase="))check(line.matches("send_wire_phase=(raw_text|raw_binary|packet|ack_enter|emit_enter|emit_return|terminal_timeout|unavailable|closed|failure_timeout|failure_io|failure_other) connection=[0-9]+ transport=-?[0-9]+ engineType=-?[0-9]+ socketType=-?[0-9]+ ackId=-?[0-9]+ length=-?[0-9]+ key=-?[0-9]+ closeCode=-?[0-9]+( queueBytes=-?[0-9]+ queueState=(available|unavailable|retired|stale|error))? elapsedRealtimeMs=[0-9]+"),"额外未批准字段");}
            }
            /** 顺序执行真正收包链和透明边界，所有计时器均由原close或ACK退役。 */public static void main(String[] args)throws Exception{
                queueSnapshots();queueGateReconnect();bulkQueue();waitTimeoutQueue();queueGates();queueRelease();transportEnd();rpcChain();forwarding();bulkChain();disabled();reflectionFailure();prefixes();budget();connections();System.out.println("WIRE_TRACE assertions="+checks+" realSocketIO=true noNetwork=true");}
        """;
}
