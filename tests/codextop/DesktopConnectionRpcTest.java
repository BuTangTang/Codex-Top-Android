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
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (method.getNameAsString().equals("rpc")) { methods.append(method).append('\n'); extracted++; }
        }
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getNameAsString().equals("RpcNotDispatchedException")
                    || type.getNameAsString().equals("SendRejectedException")) {
                methods.append(type).append('\n'); extracted++;
            }
        }
        if (extracted != 3) throw new AssertionError("真实RPC入口发生变化，请核对夹具边界");
        Path temporary = Files.createTempDirectory("codex-desktop-rpc");
        try {
            Path probe = temporary.resolve("DesktopRpcProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            var compile = new ArrayList<String>();
            compile.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), source.resolve("AttachmentTransfer.java").toString(),
                    source.resolve("BulkTransferCrypto.java").toString()));
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
        import org.json.JSONObject;
        public final class DesktopRpcProbe {
            boolean closed;
            final String machineId="synthetic-machine";
            final Set<CompletableFuture<JsonObject>> pending=new HashSet<>();
            final Crypto crypto=new Crypto();
            final Socket socket=new Socket();
            static final class Crypto {
                static final String PREFIX="synthetic-cipher:";
                int decryptCalls;
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
                boolean connected=true,timeout;
                int emitted;
                Object[] reply;
                Object[] lateReply;
                JSONObject request;
                /** 只读取合成连接状态。 */
                boolean connected(){return connected;}
                /** 每个emit只调用它自己的ACK，额外响应用于验证完成后不得覆盖。 */
                void emit(String event,Object[] args,AckWithTimeout ack){
                    if(!"rpc-call".equals(event)||args.length!=1)throw new AssertionError("RPC传输入口变化");
                    emitted++;request=(JSONObject)args[0];
                    if(timeout)ack.onTimeout();else ack.onSuccess(reply);
                    if(lateReply!=null)ack.onSuccess(lateReply);
                }
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
        """;

    // 场景描述输入与可观察结果；不在测试里重新实现响应判定算法。
    private static final String SCENARIOS = """
            /** 覆盖真实RPC与专用附件调用者之间的拒绝传递及原发送边界。 */
            public static void main(String[] args)throws Exception {
                final String bulk="daemon.bulkTransfer.upload.init";
                final String send="daemon.directSessions.send";
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
                // 本片只修bulk，锁住既有send字段畸形时的errorCode判断，不扩大普通发送改动。
                check(fails(probe("{\\"ok\\":\\"false\\",\\"errorCode\\":\\"synthetic_rejected\\"}"),send) instanceof SendRejectedException,"本片改变了既有send拒绝判断");
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
                System.out.println("DesktopConnectionRpc: 真实方法bulk失败传递、专用超限原因、畸形封套、解密、超时、原send及未派发语义通过");
            }
        """;
}
