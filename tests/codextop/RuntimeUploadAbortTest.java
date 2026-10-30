package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;

/** 真实上传finally、Runtime队列与连接RPC入口；仅远端、Android和等待边界使用合成替身。 */
public final class RuntimeUploadAbortTest {
    /** 前像可只运行runtime场景，RED来自原abort等待而非缺少新方法的编译错误。 */
    public static void main(String[] args)throws Exception {
        Path root=Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        Path runtime=args.length>0?Path.of(args[0]):root.resolve("CodexRuntime.java");
        Path connection=args.length>1?Path.of(args[1]):root.resolve("DesktopConnection.java");
        String lane=args.length>2?args[2]:"all";
        if(!lane.equals("connection")) {
            Field f=RuntimeSendQueueTest.class.getDeclaredField("SCENARIOS");f.setAccessible(true);
            String helpers=(String)f.get(null);helpers=helpers.substring(0,helpers.indexOf("/** 图片仍在上传时"));
            RuntimeSendQueueTest.run(runtime,helpers+CASES,RuntimeUploadAbortTest::controlled);
        }
        if(!lane.equals("runtime"))connection(connection);
    }

    /** 仅替换远端清理边界；真实transfer适配器、finally、准备队列和终态方法仍被提取执行。 */
    private static String controlled(String fixture) {
        fixture=remove(fixture,"abortUploadAsync");fixture=remove(fixture,"abortUpload");
        fixture=fixture.replace("static final class DesktopConnection {","static final class DesktopConnection {"+CONTROL);
        fixture=fixture.replace("JsonObject transfer(String method,JsonObject p)throws Exception{",
            "JsonObject transfer(String method,JsonObject p)throws Exception{if(method.endsWith(\"upload.abort\"))return cleanup(p,new Object(),()->true).get(3,java.util.concurrent.TimeUnit.SECONDS);if(method.endsWith(\"upload.chunk\")&&failChunk){failChunk=false;throw new IOException(\"synthetic chunk failure\");}");
        fixture=fixture.replace("closeCalls++;connected=false;","closeCalls++;connected=false;for(java.util.concurrent.CompletableFuture<JsonObject> f:cleanups)f.completeExceptionally(new IOException(\"closed\"));");
        return fixture;
    }

    /** 删除原合成连接方法而不触及产品方法；保持既有测试中的其他替身原样。 */
    private static String remove(String source,String name) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit=StaticJavaParser.parse(source+"\n}");
        var method=unit.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals(name)
            &&m.findAncestor(ClassOrInterfaceDeclaration.class).map(t->t.getNameAsString().equals("DesktopConnection")).orElse(false)).findFirst();
        if(method.isEmpty())return source;
        var range=method.get().getRange().orElseThrow();String[] lines=source.split("\n",-1);int start=range.begin.column-1,end=range.end.column;
        for(int i=0;i<range.begin.line-1;i++)start+=lines[i].length()+1;
        for(int i=0;i<range.end.line-1;i++)end+=lines[i].length()+1;
        return source.substring(0,start)+source.substring(end);
    }

    private static final String CONTROL="""
        boolean failChunk,autoCleanup,deferCleanup,throwCleanup;
        int cleanupStarted,cleanupSync;final java.util.List<java.util.concurrent.CompletableFuture<JsonObject>> cleanups=new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<Runnable> cleanupDispatch=new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch cleanupEntered=new java.util.concurrent.CountDownLatch(1);
        /** 可挂住原ACK，仅计原连接上的清理请求，不触及真实账号或网络。 */
        java.util.concurrent.CompletableFuture<JsonObject> cleanup(JsonObject p,Object gate,java.util.function.BooleanSupplier permitted)throws IOException {
            if(throwCleanup)throw new IOException("synthetic cleanup setup failure");
            java.util.concurrent.CompletableFuture<JsonObject> future=new java.util.concurrent.CompletableFuture<>();cleanups.add(future);
            Runnable dispatch=()->{synchronized(gate){if(!connected||!permitted.getAsBoolean()){future.completeExceptionally(new RpcNotDispatchedException());return;}}
                cleanupStarted++;cleanupEntered.countDown();if(autoCleanup){JsonObject value=new JsonObject();value.addProperty("success",true);future.complete(value);}};
            if(deferCleanup)cleanupDispatch.add(dispatch);else dispatch.run();return future;
        }
        /** 异步清理边界由专项控制；生产RPC实现另在connection场景执行。 */
        java.util.concurrent.CompletableFuture<JsonObject> abortUploadAsync(JsonObject p,Object gate,java.util.function.BooleanSupplier permitted)throws IOException{return cleanup(p,gate,permitted);}
        /** 满额回退真实Runtime决策，但等待ACK由合成future限时三秒。 */
        JsonObject abortUpload(JsonObject p,Object gate,java.util.function.BooleanSupplier permitted)throws Exception{cleanupSync++;return cleanup(p,gate,permitted).get(3,java.util.concurrent.TimeUnit.SECONDS);}
        """;

    private static final String CASES="""
        /** 只推进已有队列，不另起产品轮询器；测试等待以明确屏障或有限join结束。 */
        static final class UploadWorker implements AutoCloseable {
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch returned=new java.util.concurrent.CountDownLatch(1);final DesktopConnection owner=connection;
            final Thread worker=new Thread(()->{try{uploadQueue.all();}catch(Throwable t){failure.set(t);}finally{returned.countDown();}},"synthetic-upload-owner");
            /** 启动唯一合成上传队列执行者。 */UploadWorker(){worker.start();}
            /** 不遗留测试线程；所有挂住的合成ACK均显式释放。 */public void close()throws Exception{
                for(java.util.concurrent.CompletableFuture<JsonObject> f:owner.cleanups)f.complete(new JsonObject());
                worker.join(3500);check(!worker.isAlive()&&failure.get()==null,"upload worker leaked/failed");sendQueue.all();ui.all();}
        }
        /** 每组必须先释放上一轮真实许可；不通过重置许可掩盖泄漏。 */
        static void fresh(Path root)throws Exception{resetCase(root);
            try{java.lang.reflect.Field f=RuntimeSendQueueProbe.class.getDeclaredField("uploadAbortInFlight");f.setAccessible(true);
                check(!((java.util.concurrent.atomic.AtomicBoolean)f.get(null)).get(),"cleanup permit leaked");}catch(NoSuchFieldException old){} }
        /** 首件真实chunk失败后挂住abort，气泡和第二附件必须在ACK前推进。 */
        static void promptFailure(Path root)throws Exception{fresh(root);connection.failChunk=true;String first=image();sendQueue.all();
            boolean returned;try(UploadWorker w=new UploadWorker()){
                check(connection.cleanupEntered.await(1,java.util.concurrent.TimeUnit.SECONDS),"cleanup never dispatched");
                returned=w.returned.await(500,java.util.concurrent.TimeUnit.MILLISECONDS);
                if(returned){sendQueue.all();ui.all();check(pendingMessages.get(first).messageOwner.send_state==MessageObject.MESSAGE_SEND_STATE_SEND_ERROR,"failed bubble still waiting abort");
                    String second=image();sendQueue.all();uploadQueue.all();sendQueue.all();ui.all();
                    check(store.get(second).submissionAccepted&&connection.imageSendCalls==1&&!store.get(first).submissionAccepted,"second attachment blocked/first resent");
                    check(!connection.cleanups.get(0).isDone(),"held cleanup unexpectedly completed");}
            }
            check(returned,"original synchronous abort held uploadQueue until cleanup ACK");
            check(pendingMessages.get(first).messageOwner.send_state==MessageObject.MESSAGE_SEND_STATE_SEND_ERROR&&connection.imageSendCalls==1,"late cleanup changed failed bubble");
            System.out.println("PROMPT_FAILURE errorBeforeAbort=true secondAttachmentBeforeAbort=true");}
        /** 两次快速失败最多一个异步清理加一个原同步回退，第三件仍在原FIFO排队。 */
        static void boundedFallback(Path root)throws Exception{fresh(root);connection.failChunk=true;String first=image();sendQueue.all();uploadQueue.all();ui.all();
            check(connection.cleanups.size()==1&&!connection.cleanups.get(0).isDone(),"first cleanup not held");
            connection.failChunk=true;String second=image();sendQueue.all();String third=image();sendQueue.all();
            try(UploadWorker w=new UploadWorker()){
                long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
                while(connection.cleanups.size()<2&&System.nanoTime()<end)Thread.sleep(1);
                check(connection.cleanups.size()==2&&connection.cleanupSync==1&&!w.returned.await(100,java.util.concurrent.TimeUnit.MILLISECONDS),"second failure not bounded synchronous fallback");
                check(connection.initCalls==2,"third attachment bypassed original upload FIFO");
                JsonObject rejected=new JsonObject();rejected.addProperty("success",false);rejected.addProperty("error","synthetic cleanup refusal");
                connection.cleanups.get(1).complete(rejected);check(w.returned.await(1,java.util.concurrent.TimeUnit.SECONDS),"fallback never released");
                sendQueue.all();ui.all();check(store.get(third).submissionAccepted&&!store.get(first).submissionAccepted&&!store.get(second).submissionAccepted,"fallback changed original outcomes");
                check(connection.cleanups.size()==2&&!connection.cleanups.get(0).isDone(),"unexpected cleanup retry/queue");
            }System.out.println("BOUNDED_CLEANUP async=1 synchronousFallback=1 queuedThird=true");}
        /** 所有future终态及派发前异常都释放许可；新上传不会永久退化同步清理。 */
        static void releases(Path root)throws Exception{for(int mode=0;mode<7;mode++){fresh(root);connection.failChunk=true;connection.throwCleanup=mode==5;
            image();sendQueue.all();uploadQueue.all();ui.all();
            if(mode!=5){java.util.concurrent.CompletableFuture<JsonObject> f=connection.cleanups.get(0);
                if(mode==0){JsonObject r=new JsonObject();r.addProperty("success",true);f.complete(r);}
                if(mode==1){JsonObject r=new JsonObject();r.addProperty("success",false);f.complete(r);}
                if(mode==2)f.completeExceptionally(new java.util.concurrent.TimeoutException());if(mode==3)f.cancel(false);if(mode==4)connection.close();
                if(mode==6)f.completeExceptionally(new IOException("synthetic cleanup failure"));}
            connection.connected=true;connection.throwCleanup=false;connection.failChunk=true;connection.autoCleanup=true;
            image();sendQueue.all();uploadQueue.all();sendQueue.all();ui.all();check(connection.cleanupSync==0,"terminal retained asynchronous permit");
        }System.out.println("CLEANUP_RELEASE success=false=true timeout cancel close setupFailure=true");}
        /** 两台电脑仍共用同一全局许可；第二连接不能自行多开一个脱离队列的cleanup。 */
        static void globalBound(Path root)throws Exception{fresh(root);connection.failChunk=true;image();sendQueue.all();uploadQueue.all();
            DesktopConnection other=new DesktopConnection();other.publicKey=connection.publicKey;other.failChunk=true;
            desktopConnections.put("other-machine",other);dialogMachines.put(2L,"other-machine");remoteIds.put(2L,"other-thread");dialogDirectories.put(2L,"/synthetic/workspace");
            Path file=scenarioRoot.resolve("other.jpg");Files.write(file,new byte[8192]);files.put("other.jpg",file);
            SendMessageParams params=new SendMessageParams();params.peer=2;params.photo=new Object();params.path=file.toString();params.caption="synthetic other";
            sendBatch(0,params,new ArrayList<>(Arrays.asList(params)));sendQueue.all();
            try(UploadWorker w=new UploadWorker()){
                check(other.cleanupEntered.await(1,java.util.concurrent.TimeUnit.SECONDS),"second connection cleanup absent");
                check(other.cleanupSync==1&&!w.returned.await(100,java.util.concurrent.TimeUnit.MILLISECONDS),"global bound became per-connection bound");
                other.cleanups.get(0).complete(new JsonObject());check(w.returned.await(1,java.util.concurrent.TimeUnit.SECONDS),"second connection fallback not released");
            }finally{for(java.util.concurrent.CompletableFuture<JsonObject> f:other.cleanups)f.complete(new JsonObject());}
            System.out.println("GLOBAL_CLEANUP_BOUND twoConnections=true secondSynchronous=true");}
        /** 账号、原会话、电脑连接改变发生在排队后；真正派发前的许可检查必须阻止旧清理。 */
        static void ownership(Path root)throws Exception{for(int mode=0;mode<3;mode++){fresh(root);connection.failChunk=true;connection.deferCleanup=true;
            image();sendQueue.all();uploadQueue.all();DesktopConnection old=connection;
            if(mode==0)accountGeneration++;if(mode==1)remoteIds.put(1L,"synthetic-rebound");if(mode==2)desktopConnections.put("synthetic-machine",new DesktopConnection());
            for(Runnable dispatch:old.cleanupDispatch)dispatch.run();
            check(old.cleanupStarted==0&&old.cleanups.get(0).isCompletedExceptionally(),"old cleanup crossed account/remote/connection gate");
        }System.out.println("CLEANUP_OWNER queuedAccountRemoteConnectionRejected=true");}
        /** init回执时取消仍取得uploadId并清理；删除不复活消息、不继续chunk或SEND。 */
        static void cancelAfterInit(Path root)throws Exception{fresh(root);String id=image();int mid=pendingMessages.get(id).getId();
            connection.uploadBarrier=()->{deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(mid)));sendQueue.all();ui.all();};connection.barrierMethod="init";
            sendQueue.all();uploadQueue.all();sendQueue.all();ui.all();
            check(connection.cleanupStarted==1&&connection.chunkCalls==0&&connection.imageSendCalls==0&&store.get(id)==null,"cancel blocked cleanup/continued upload/recreated outbox");
            connection.cleanups.get(0).complete(new JsonObject());ui.all();check(pendingMessages.isEmpty(),"cleanup revived deleted message");
            System.out.println("CANCEL_INIT cleanupOnce=true noChunkNoSendNoResurrection=true");}
        /** 清理创建本身失败仍保留原chunk异常；执行真实AttachmentTransfer的catch和finally。 */
        static void originalFailure(Path root)throws Exception{fresh(root);Path file=scenarioRoot.resolve("primary.bin");Files.write(file,new byte[16]);
            connection.failChunk=true;connection.throwCleanup=true;
            try{transfer(accountGeneration,1L,remoteIds.get(1L),connection,new java.util.concurrent.atomic.AtomicBoolean(true),null)
                    .upload(file.toFile(),"file","synthetic-primary","/synthetic/workspace");throw new AssertionError("chunk failure lost");}
            catch(IOException error){check("synthetic chunk failure".equals(error.getMessage()),"cleanup replaced original upload error");}
            System.out.println("ORIGINAL_FAILURE cleanupSetupErrorDidNotReplaceChunk=true");}
        /** 执行真实Runtime和流式附件行为；前像第一组以有界等待确认阻塞。 */
        public static void main(String[] args)throws Exception{Path root=Paths.get(args[0]);promptFailure(root);boundedFallback(root);globalBound(root);releases(root);ownership(root);cancelAfterInit(root);originalFailure(root);fresh(root);}
        """;

    /** 完整提取固定abort入口、原RPC、Timer和关闭方法；只替换已有Socket/Android边界。 */
    private static void connection(Path source)throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit=StaticJavaParser.parse(source);StringBuilder actual=new StringBuilder();
        Set<String> names=Set.of("abortUpload","abortUploadAsync","rpc","rpcAsync","awaitRpc","failPending","close","retireFailedEmitTimer","whenConnected","completeConnectionWaiters","clearAttachmentUploadLimit");
        for(var m:unit.findAll(MethodDeclaration.class))if(names.contains(m.getNameAsString()))actual.append(m).append('\n');
        for(var f:unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class))if(f.getVariables().stream().anyMatch(v->Set.of("pending","connectionWaiters","attachmentUploadMaxBytes","attachmentUploadGeneration").contains(v.getNameAsString())))actual.append(f).append('\n');
        for(var c:unit.findAll(ClassOrInterfaceDeclaration.class))if(Set.of("PendingRpc","RpcNotDispatchedException","SendRejectedException","BulkAckTrace").contains(c.getNameAsString()))actual.append(c).append('\n');
        Field f=DesktopConnectionAsyncSendTest.class.getDeclaredField("FIXTURE");f.setAccessible(true);String fixture=(String)f.get(null);
        Path temp=Files.createTempDirectory("codex-abort-rpc-");
        try{
            Path probe=temp.resolve("AsyncConnectionProbe.java");Files.writeString(probe,fixture+actual+CONNECTION_CASES+"\n}");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-cp",System.getProperty("java.class.path"),"-d",temp.toString(),probe.toString())!=0)throw new AssertionError("真实abort RPC Java8编译失败");
            ArrayList<java.net.URL> urls=new ArrayList<>();urls.add(temp.toUri().toURL());for(String x:System.getProperty("java.class.path").split(java.io.File.pathSeparator))urls.add(Path.of(x).toUri().toURL());
            try(URLClassLoader loader=new URLClassLoader(urls.toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())){
                try{loader.loadClass("com.butang.codextop.AsyncConnectionProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("真实abort RPC行为失败",e.getCause());}
            }
        }finally{try(var files=Files.walk(temp)){for(Path x:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(x);}}
    }
    private static final String CONNECTION_CASES="""
        /** 固定方法封套、原归属许可、25秒超时、关闭和迟ACK均执行当前真实RPC。 */
        public static void main(String[] args)throws Exception{
            for(int mode=0;mode<6;mode++){reset();AsyncConnectionProbe p=new AsyncConnectionProbe();Object gate=new Object();final boolean[] permitted={true};JsonObject params=new JsonObject();params.addProperty("uploadId","synthetic-only");
                CompletableFuture<JsonObject> f=p.abortUploadAsync(params,gate,()->permitted[0]);check(!f.isDone()&&p.socket.emitted==0,"async abort waited/dispatched inline");
                if(mode==0)permitted[0]=false;if(mode==1)p.close();EventThread.drain();
                if(mode<2){check(failure(f) instanceof RpcNotDispatchedException&&p.socket.emitted==0,"queued owner/close still emitted");continue;}
                check(p.socket.calls.get(0).getString("method").equals("synthetic-machine:daemon.bulkTransfer.upload.abort")&&p.socket.calls.get(0).getLong("timeoutMs")==20000,"abort changed method/deadline envelope");
                AckWithTimeout ack=p.socket.acks.get(1);
                if(mode==2)p.socket.reply(1,"{\\"success\\":true}");
                if(mode==3)p.socket.reply(1,"{\\"success\\":false,\\"error\\":\\"synthetic refusal\\"}");
                if(mode==4)AckWithTimeout.advance(25000);if(mode==5)p.close();
                if(mode<4)check(f.join().get("success").getAsBoolean()==(mode==2),"abort altered valid result");
                if(mode==4)check(failure(f) instanceof TimeoutException,"abort changed original timeout category");
                if(mode==5)check(failure(f) instanceof IOException&&!(failure(f) instanceof RpcNotDispatchedException),"dispatched close lost original uncertainty");
                int decrypts=p.crypto.decrypts;ack.onSuccess(new org.json.JSONObject().put("ok",true).put("result","{}"));check(p.crypto.decrypts==decrypts&&p.pending.isEmpty(),"late cleanup ACK reparsed/retained");
            }
            reset();AsyncConnectionProbe p=new AsyncConnectionProbe();EventThread.queued=false;p.socket.captured=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<JsonObject> value=new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker=new Thread(()->{try{value.set(p.abortUpload(new JsonObject(),new Object(),()->true));}catch(Throwable t){error.set(t);}},"synthetic-sync-fallback");worker.start();
            check(p.socket.captured.await(1,TimeUnit.SECONDS)&&worker.isAlive(),"sync fallback stopped waiting original ACK");p.socket.reply(1,"{\\"success\\":true}");worker.join(2000);
            check(!worker.isAlive()&&error.get()==null&&value.get().get("success").getAsBoolean(),"sync fallback altered response/error");
            java.lang.System.out.println("ABORT_RPC actualJava8=true original25s=true queuedGate=true lateAckIgnored=true synchronousFallback=true");
        }
        """;
}
