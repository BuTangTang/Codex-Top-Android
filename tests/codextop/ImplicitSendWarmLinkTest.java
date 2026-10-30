package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 真实参数构造/RPC核心及Runtime/Outbox专项；只有Socket、远端ACK和Android边界为合成替身。 */
public final class ImplicitSendWarmLinkTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?".":args[0]);
        String lane=args.length>1?args[1]:"all";
        Path connection=root.resolve("TMessagesProj/src/main/java/com/butang/codextop/DesktopConnection.java");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String source=Files.readString(connection);
        if(!lane.equals("runtime"))connection(source);
        if(!lane.equals("connection"))RuntimeSendQueueTest.run(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"),RUNTIME_CASES,fixture->runtimeFixture(fixture,source));
    }

    private static void connection(String source) throws Exception {
        var unit=StaticJavaParser.parse(source);
        Set<String> names=Set.of("rpc","rpcAsync","awaitRpc","openConversationAsync","linkConversationForSendAsync","sendAsync","base","userCodexSource","failPending","clearAttachmentUploadLimit","close","retireFailedEmitTimer","whenConnected","completeConnectionWaiters");
        StringBuilder methods=new StringBuilder();
        for(MethodDeclaration method:unit.findAll(MethodDeclaration.class))if(names.contains(method.getNameAsString()))methods.append(method).append('\n');
        for(var field:unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))
            if(field.getVariables().stream().anyMatch(v->Set.of("connectionWaiters","pending","attachmentUploadMaxBytes","attachmentUploadGeneration").contains(v.getNameAsString())))methods.append(field).append('\n');
        for(ClassOrInterfaceDeclaration type:unit.findAll(ClassOrInterfaceDeclaration.class))
            if(Set.of("PendingRpc","RpcNotDispatchedException","SendRejectedException","BulkAckTrace").contains(type.getNameAsString()))methods.append(type).append('\n');
        Field field=DesktopConnectionAsyncSendTest.class.getDeclaredField("FIXTURE");field.setAccessible(true);
        String fixture=(String)field.get(null);
        // Baseline still executes its real old API, so missing mode is a behavioral RED, not missing-method compilation.
        String entry=unit.findAll(MethodDeclaration.class).stream().anyMatch(m->m.getNameAsString().equals("linkConversationForSendAsync"))?"linkConversationForSendAsync":"openConversationAsync";
        String cases=CONNECTION_CASES.replace("WARM_ENTRY",entry);
        Path temp=Files.createTempDirectory("codex-implicit-link-connection-");
        try {
            Path probe=temp.resolve("AsyncConnectionProbe.java");Files.writeString(probe,fixture+methods+cases+"\n}");
            String[] command={"--release","8","-cp",System.getProperty("java.class.path"),"-d",temp.toString(),probe.toString(),"TMessagesProj/src/main/java/com/butang/codextop/DesktopAttachment.java"};
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,command)!=0)throw new AssertionError("实际implicit link/RPC方法Java8编译失败");
            ArrayList<URL> urls=new ArrayList<>();urls.add(temp.toUri().toURL());
            for(String entryPath:System.getProperty("java.class.path").split(java.io.File.pathSeparator))urls.add(Path.of(entryPath).toUri().toURL());
            try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
                try{loader.loadClass("com.butang.codextop.AsyncConnectionProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException error){throw new AssertionError("实际implicit link连接行为失败",error.getCause());}
            }
        } finally {try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    private static String runtimeFixture(String fixture,String connection) {
        fixture=fixture.replace("import com.google.gson.*;", "import com.google.gson.*;import java.util.concurrent.CompletableFuture;import java.util.function.BooleanSupplier;");
        // Replace only the connection's RPC boundary method with the actual production parameter constructors.
        String old=method(fixture,"java.util.concurrent.CompletableFuture<JsonObject> openConversationAsync(");
        fixture=fixture.replace(old,"");
        String bridge="java.util.concurrent.CompletableFuture<JsonObject> linkConversationForSendAsync(";
        if(fixture.contains(bridge))fixture=fixture.replace(method(fixture,bridge),"");
        String wire=method(connection,"    public CompletableFuture<JsonObject> openConversationAsync(")
                +method(connection,"    private JsonObject base(")
                +method(connection,"    static JsonObject userCodexSource()");
        String warm="    CompletableFuture<JsonObject> linkConversationForSendAsync(";
        if(connection.contains(warm))wire+=method(connection,warm);
        String boundary="""
                final ArrayList<JsonObject> linkBodies=new ArrayList<>();Object linkGate;BooleanSupplier linkAllowed;
                boolean holdLink;CompletableFuture<JsonObject> heldLink;JsonObject heldLinkValue;
                CompletableFuture<JsonObject> rpcAsync(String method,JsonObject params,Object gate,BooleanSupplier permitted){
                    CompletableFuture<JsonObject> future=new CompletableFuture<>();
                    try{synchronized(gate){if(!permitted.getAsBoolean())throw new RpcNotDispatchedException();}
                        if(!method.equals("daemon.directSessions.link.ensure"))throw new AssertionError("new RPC introduced");
                        linkBodies.add(params);linkGate=gate;linkAllowed=permitted;
                        JsonObject result=openConversation(params.get("remoteSessionId").getAsString());
                        if(holdLink){holdLink=false;heldLink=future;heldLinkValue=result;}else future.complete(result);
                    }catch(Exception failure){future.completeExceptionally(failure);}return future;
                }
                """;
        return fixture.replace("static final class DesktopConnection {","static final class DesktopConnection {"+wire+boundary);
    }
    private static String method(String source,String signature) {
        int a=source.indexOf(signature);if(a<0)throw new AssertionError("missing actual method "+signature);
        int open=source.indexOf('{',a),depth=0;boolean quote=false,escape=false;
        for(int i=open;i<source.length();i++){char c=source.charAt(i);if(quote){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quote=false;continue;}if(c=='"'){quote=true;continue;}if(c=='{')depth++;if(c=='}'&&--depth==0)return source.substring(a,i+1);}
        throw new AssertionError(signature);
    }

    private static final String CONNECTION_CASES="""
            static JsonObject body(AsyncConnectionProbe p,int index){return JsonParser.parseString(p.socket.calls.get(index).getString("params")).getAsJsonObject();}
            public static void main(String[] args)throws Exception{
                reset();AsyncConnectionProbe p=new AsyncConnectionProbe();Object gate=new Object();
                CompletableFuture<JsonObject> implicit=p.WARM_ENTRY("synthetic-remote",gate,()->true);
                CompletableFuture<JsonObject> explicit=p.openConversationAsync("synthetic-explicit",gate,()->true);
                check(implicit instanceof PendingRpc&&!implicit.isDone()&&!explicit.isDone()&&p.pending.size()==2,"original pending future/admission replaced");
                EventThread.drain();check(p.socket.emitted==2,"link emitted duplicate/new RPC");
                JsonObject warm=body(p,0),normal=body(p,1);
                check(warm.get("openExisting").getAsJsonPrimitive().isBoolean()&&warm.get("openExisting").getAsBoolean(),"openExisting compatibility boolean changed");
                check(warm.has("openExistingMode")&&warm.get("openExistingMode").getAsString().equals("if-unloaded"),"implicit-send optional mode missing");
                check(normal.get("openExisting").getAsJsonPrimitive().isBoolean()&&normal.get("openExisting").getAsBoolean()&&!normal.has("openExistingMode"),"explicit open semantics changed");
                check(warm.get("machineId").getAsString().equals("synthetic-machine")&&warm.get("providerId").getAsString().equals("codex")&&warm.get("remoteSessionId").getAsString().equals("synthetic-remote")&&warm.getAsJsonObject("source").get("kind").getAsString().equals("codexHome")&&warm.getAsJsonObject("source").get("home").getAsString().equals("user"),"canonical target/source changed");
                check(p.socket.calls.get(0).getInt("timeoutMs")==20000,"wire deadline extended");
                p.socket.reply(2,"{\\"ok\\":true,\\"sessionId\\":\\"explicit-linked\\"}");
                check(explicit.join().get("sessionId").getAsString().equals("explicit-linked")&&!implicit.isDone(),"mode ACK mismatched another future");
                p.socket.reply(1,"{\\"ok\\":true,\\"sessionId\\":\\"implicit-linked\\"}");
                check(implicit.join().get("sessionId").getAsString().equals("implicit-linked")&&p.pending.isEmpty()&&p.socket.acks.isEmpty(),"original link ACK/cleanup changed");
                reset();p=new AsyncConnectionProbe();java.util.concurrent.atomic.AtomicBoolean allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
                CompletableFuture<JsonObject> canceled=p.WARM_ENTRY("synthetic-remote",allowed,allowed::get);allowed.set(false);EventThread.drain();
                check(failure(canceled) instanceof RpcNotDispatchedException&&p.socket.emitted==0&&p.pending.isEmpty(),"canceled warm link still dispatched");
                System.out.println("PASS actual Java8 link constructor/rpcAsync: optional wire, legacy boolean/explicit open, matching ACK, original cancellation");
            }
            """;

    private static final String RUNTIME_CASES="""
            static String text(long dialog){SendMessageParams p=new SendMessageParams();p.peer=dialog;p.message="synthetic text";sendBatch(0,p,new ArrayList<>(Arrays.asList(p)));MessageObject last=null;for(MessageObject m:pendingMessages.values())if(last==null||m.getId()<last.getId())last=m;return last.messageOwner.params.get("codexBatchLocalId");}
            static void wire(JsonObject params){check(params.get("openExisting").getAsJsonPrimitive().isBoolean()&&params.get("openExisting").getAsBoolean(),"implicit runtime changed boolean");check(params.has("openExistingMode")&&params.get("openExistingMode").getAsString().equals("if-unloaded"),"prepared-send missing-cache branch did not request if-unloaded");check(params.get("machineId").getAsString().equals("synthetic-machine")&&params.get("remoteSessionId").getAsString().equals("synthetic-thread")&&params.getAsJsonObject("source").get("home").getAsString().equals("user"),"prepared-send canonical identity changed");}
            static void coldAndCache(Path root)throws Exception{
                reset(root);DesktopConnection owner=connection;owner.holdLink=true;String first=text(1);drainSend();
                check(owner.heldLink!=null&&owner.linkBodies.size()==1&&owner.openCalls==1&&owner.sendCalls==0&&!store.get(first).submissionUncertain,"link bypassed original ACK/readiness gate");wire(owner.linkBodies.get(0));check(owner.linkGate==sendJobs.get(first)&&owner.linkAllowed.getAsBoolean(),"original cancellation/identity gate replaced");
                String second=text(1);drainSend();check(store.get(second)!=null&&store.get(second).isPrepared()&&owner.linkBodies.size()==1&&owner.sendCalls==0&&preparedInFlight==1,"same-conversation link FIFO lost");
                owner.heldLink.complete(owner.heldLinkValue);sendQueue.all();ui.all();
                check(store.get(first).submissionAccepted&&store.get(second).submissionAccepted&&owner.sendCalls==2&&owner.linkBodies.size()==1&&linkedSessions.get(1L).equals("synthetic-linked"),"link ACK failed to dispatch/cache original SEND");check(!store.get(first).localId.equals(store.get(second).localId)&&preparedInFlight==0&&sendJobs.isEmpty(),"localId/final admission changed");
                String third=text(1);drainSend();ui.all();check(store.get(third).submissionAccepted&&owner.sendCalls==3&&owner.linkBodies.size()==1,"cached link emitted repeated ensure");
                reset(root);linkedSessions.put(1L,"synthetic-preexisting-link");String cached=text(1);drainSend();ui.all();check(store.get(cached).submissionAccepted&&connection.linkBodies.isEmpty()&&connection.openCalls==0&&connection.sendCalls==1,"preexisting cache needlessly opens");
                System.out.println("PASS actual Runtime Java8/Outbox: missing-cache mode, original FIFO link ACK, three SEND identities, cache hits no ensure");
            }
            static void staleLink(Path root,boolean cancel)throws Exception{
                reset(root);DesktopConnection owner=connection;owner.holdLink=true;String id=text(1);drainSend();check(owner.heldLink!=null,"late fixture missed actual link");
                if(cancel)sendJobs.get(id).set(false);else desktopConnections.put("synthetic-machine",new DesktopConnection());
                check(!owner.linkAllowed.getAsBoolean(),"stale link lost original permitted supplier");owner.heldLink.complete(owner.heldLinkValue);sendQueue.all();ui.all();
                check(owner.sendCalls==0&&!store.get(id).submissionAccepted&&!store.get(id).submissionUncertain&&!linkedSessions.containsKey(1L)&&preparedInFlight==0&&sendJobs.isEmpty(),"canceled/replaced connection link ACK dispatched or cached");
                System.out.println("PASS actual Runtime stale link ACK: "+(cancel?"cancel":"connection replacement")+" retained original identity guard");
            }
            public static void main(String[] args)throws Exception{Path root=Paths.get(args[0]);coldAndCache(root);staleLink(root,true);staleLink(root,false);}
            """;
}
