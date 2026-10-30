package com.butang.codextop;

import java.nio.file.Path;

/** 提取真实Runtime发送方法，阻住合成ACK而保留真实Outbox与准备完成顺序。 */
public final class RuntimeSendAsyncQueueTest {
    /** 可传私有baseline源文件重放RED；不连接设备、桌面或网络。 */
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        String scenarios = java.nio.file.Files.readString(source).contains("startPreparedSend(") ? SCENARIOS
                : SCENARIOS.substring(0, SCENARIOS.indexOf("/** 关联在途"))
                        + "public static void main(String[] args)throws Exception{blockedAck(Paths.get(args[0]));}\n";
        RuntimeSendQueueTest.run(source, scenarios, RuntimeSendAsyncQueueTest::holdRpc);
    }

    /** 仅远端回执替身可被屏障阻住；产品方法仍按原队列执行。 */
    private static String holdRpc(String fixture) {
        fixture = fixture.replace("String activeName,publicKey;", "volatile boolean holdSend,holdLink;volatile java.util.concurrent.CompletableFuture<JsonObject> heldSend,heldLink;JsonObject heldLinkValue;final java.util.concurrent.CountDownLatch rpcEntered=new java.util.concurrent.CountDownLatch(1),rpcRelease=new java.util.concurrent.CountDownLatch(1);String activeName,publicKey;");
        fixture = fixture.replace("sendCalls++;if(uploaded.isEmpty())", "sendCalls++;if(holdSend){holdSend=false;rpcEntered.countDown();if(!rpcRelease.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError(\"synthetic ACK barrier timed out\");}if(uploaded.isEmpty())");
        fixture = fixture.replace("result.complete(openConversation(remote));", "JsonObject value=openConversation(remote);if(holdLink){holdLink=false;heldLinkValue=value;heldLink=result;rpcEntered.countDown();}else result.complete(value);");
        fixture = fixture.replace("send(linked,text,localId,uploaded);result.complete(new JsonObject());", "boolean hold=holdSend;holdSend=false;send(linked,text,localId,uploaded);if(hold){heldSend=result;rpcEntered.countDown();}else result.complete(new JsonObject());");
        return fixture;
    }

    private static final String SCENARIOS = """
        /** 真实sendQueue只有一个执行者；有限屏障不会并行调用同一队列。 */
        static final class Worker implements AutoCloseable {
            volatile boolean stop;final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();final DesktopConnection owner=connection;
            final java.util.concurrent.atomic.AtomicReference<Runnable> afterDrain=new java.util.concurrent.atomic.AtomicReference<>();
            final Thread thread=new Thread(()->{try{while(!stop){sendQueue.all();Runnable observation=afterDrain.getAndSet(null);if(observation!=null){sendQueue.postRunnable(observation);sendQueue.all();}Thread.sleep(1);}}catch(Throwable e){failure.set(e);}},"synthetic-original-send-queue");
            Worker(){thread.start();}
            /** 首写不等于准备完成；原队列排空后再入队读取ready集合，只通过锁存发布结果。 */
            void awaitPrepared(String next,String third)throws Exception{
                java.util.concurrent.CountDownLatch observed=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
                check(afterDrain.compareAndSet(null,()->{
                    ArrayDeque<PreparedSend> first=preparedSends.get(1L),waiting=preparedSends.get(3L);
                    ready.set(first!=null&&first.size()==2&&first.peekLast().base.equals(next)
                            &&waiting!=null&&waiting.size()==1&&waiting.peekFirst().base.equals(third)
                            &&preparedDialogs.contains(3L)&&preparedInFlight==2);
                    observed.countDown();
                }),"prepared observation already scheduled");
                check(observed.await(1,java.util.concurrent.TimeUnit.SECONDS)&&ready.get(),"next and third dialog did not reach actual prepared queue");
            }
            public void close()throws Exception{owner.rpcRelease.countDown();if(owner.heldSend!=null)owner.heldSend.complete(new JsonObject());if(owner.heldLink!=null)owner.heldLink.complete(owner.heldLinkValue);stop=true;thread.join(2000);check(!thread.isAlive()&&failure.get()==null,"queue worker retained or failed");sendQueue.all();ui.all();}
        }
        /** 所有等待有限；仅观察真实持久化结果及RPC计数。 */
        static boolean await(java.util.concurrent.Callable<Boolean> ready,long millis)throws Exception{long end=System.nanoTime()+millis*1000000;while(System.nanoTime()<end){if(ready.call())return true;Thread.sleep(2);}return ready.call();}
        static String latest(){MessageObject last=null;for(MessageObject m:pendingMessages.values())if(last==null||m.getId()<last.getId())last=m;return last.messageOwner.params.get("codexBatchLocalId");}
        static String text(long dialog){SendMessageParams p=new SendMessageParams();p.peer=dialog;p.message="synthetic send";sendBatch(0,p,new ArrayList<>(Arrays.asList(p)));return latest();}
        static void another(){remoteIds.put(2L,"synthetic-other-thread");dialogDirectories.put(2L,"/synthetic/workspace");dialogMachines.put(2L,"synthetic-machine");}
        /** 阻ACK时同会话后发文字先落盘，独立会话可取得ACK；本会话仍保留一次在途。 */
        static void blockedAck(Path root)throws Exception{
            reset(root);another();DesktopConnection owner=connection;owner.holdSend=true;String first=text(1);boolean persisted,independent,ordered;
            try(Worker worker=new Worker()){
                check(owner.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"actual submit missed ACK barrier");
                String second=text(1),other=text(2);
                persisted=await(()->store.get(second)!=null,400);
                independent=await(()->store.get(other)!=null&&store.get(other).submissionAccepted,400);
                ordered=owner.sendCalls==2&&store.get(first).submissionUncertain&&store.get(second)!=null&&!store.get(second).submissionAccepted;
                owner.rpcRelease.countDown();if(owner.heldSend!=null)owner.heldSend.complete(new JsonObject());
                check(await(()->store.get(first).submissionAccepted&&store.get(second)!=null&&store.get(second).submissionAccepted,1000),"ACK did not advance same conversation");
            }
            System.out.println("BLOCKED_ACK secondPersistedBeforeAck="+persisted+" independentConversationBeforeAck="+independent+" sameConversationSingleInflight="+ordered);
            check(persisted&&independent&&ordered,"RPC ACK blocked first persist or independent conversation");check(sendJobs.isEmpty()&&preparedSends.isEmpty()&&owner.sendCalls==3,"terminal ACK retained job or duplicated dispatch");
        }
        /** 关联在途仍允许首写和其他会话；同会话只发一次link.ensure并按准备完成顺序提交。 */
        static void blockedLink(Path root)throws Exception{
            reset(root);another();DesktopConnection owner=connection;owner.holdLink=true;String first=text(1);
            try(Worker worker=new Worker()){
                check(owner.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"actual link missed barrier");String second=text(1),other=text(2);
                check(await(()->store.get(second)!=null&&store.get(other)!=null&&store.get(other).submissionAccepted,800),"link blocked persistence or independent session");
                check(owner.openCalls==2&&owner.sendCalls==1&&!store.get(first).submissionUncertain&&!store.get(second).submissionUncertain,"same session duplicated link or dispatched before ready");
                owner.heldLink.complete(owner.heldLinkValue);check(await(()->store.get(first).submissionAccepted&&store.get(second).submissionAccepted,1000),"link completion did not advance ready FIFO");
            }
            check(owner.openCalls==2&&owner.sendCalls==3&&sendJobs.isEmpty(),"session association duplicated or retained");System.out.println("BLOCKED_LINK firstPersistIndependent=true sameSessionEnsureOnce=true readyFIFO=true");
        }
        /** 未知和明确拒绝都结束当前RPC门；旧unknown不重投，新localId无需等权威回显。 */
        static void terminal(Path root,boolean rejected)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.holdSend=true;String first=text(1);MessageObject original=pendingMessages.get(first);
            try(Worker worker=new Worker()){
                check(owner.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"actual send missed terminal barrier");String next=text(1);check(await(()->store.get(next)!=null,400),"next text not persisted");
                owner.heldSend.completeExceptionally(rejected?new DesktopConnection.SendRejectedException("synthetic reject"):new IOException("synthetic disconnect"));
                check(await(()->store.get(next).submissionAccepted,1000),"RPC terminal kept conversation locked until authority echo");
                check(store.get(first).submissionUncertain!=rejected&&!store.get(first).submissionAccepted&&owner.sendCalls==2,"original terminal uncertainty changed");
            }
            int calls=owner.sendCalls;SendMessageParams p=new SendMessageParams();p.retryMessageObject=original;sendBatch(0,p,new ArrayList<>());drainSend();ui.all();
            check(owner.sendCalls==calls+(rejected?1:0),"unknown resent or explicit rejection cannot retry");check(sendJobs.isEmpty()&&preparedSends.isEmpty(),"terminal retained conversation gate");System.out.println("TERMINAL rejected="+rejected+" newLocalIdAdvanced=true unknownNoResend="+!rejected);
        }
        /** 第三个待位会话先完成首写，RPC终态释放后公平轮转，不让原会话长队列抢位。 */
        static void boundedFair(Path root)throws Exception{
            reset(root);another();remoteIds.put(3L,"synthetic-third-thread");dialogDirectories.put(3L,"/synthetic/workspace");dialogMachines.put(2L,"machine-two");dialogMachines.put(3L,"machine-three");
            DesktopConnection one=connection,two=new DesktopConnection(),three=new DesktopConnection();desktopConnections.put("machine-two",two);desktopConnections.put("machine-three",three);one.holdSend=two.holdSend=three.holdSend=true;
            String a=text(1),b=text(2);
            try(Worker worker=new Worker()){
                check(one.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS)&&two.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"first two chains did not enter ACK boundary");
                String next=text(1),c=text(3);check(await(()->store.get(next)!=null&&store.get(c)!=null,500),"bounded admission blocked local first persist");
                check(one.sendCalls==1&&two.sendCalls==1&&three.sendCalls==0&&!store.get(c).submissionUncertain,"third RPC exceeded global two-chain bound");
                worker.awaitPrepared(next,c);
                one.heldSend.completeExceptionally(new IOException("synthetic timeout"));check(three.rpcEntered.await(1,java.util.concurrent.TimeUnit.SECONDS),"third ready dialog starved behind first dialog backlog");
                check(store.get(a).submissionUncertain&&one.sendCalls==1&&three.sendCalls==1&&!store.get(next).submissionUncertain,"unknown held slot or same dialog stole fair admission");
                two.heldSend.complete(new JsonObject());check(await(()->store.get(next).submissionAccepted,800),"second terminal did not release admission");
                three.heldSend.complete(new JsonObject());check(await(()->store.get(c).submissionAccepted&&store.get(b).submissionAccepted,800),"remaining admitted ACK missing");
            }finally{two.rpcRelease.countDown();three.rpcRelease.countDown();if(two.heldSend!=null)two.heldSend.complete(new JsonObject());if(three.heldSend!=null)three.heldSend.complete(new JsonObject());}
            check(sendJobs.isEmpty()&&preparedSends.isEmpty()&&preparedDialogs.isEmpty()&&preparedInFlight==0,"fair terminal left admission state");System.out.println("BOUNDED_FAIR maxActiveChains=2 thirdPersistedBeforeSlot=true readyDialogFIFO=true unknownReleasedSlot=true");
        }

        /** 已接受事实不被迟失败降级；重复旧终态不得释放随后在途的名额。 */
        static void acceptedAndDuplicate(Path root)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.holdSend=true;String id=text(1);PreparedSend old;
            try(Worker worker=new Worker()){
                check(owner.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"accepted fixture missed ACK boundary");old=preparedSends.get(1L).peekFirst();
                // 合成已到达的接受事实，持久化仍调用真实Outbox，不修改产品完成算法。
                store.markSubmissionAccepted(id);owner.holdSend=true;String next=text(1);java.util.concurrent.CompletableFuture<JsonObject> firstFuture=owner.heldSend;
                firstFuture.completeExceptionally(new DesktopConnection.RpcNotDispatchedException());check(await(()->owner.sendCalls==2&&owner.heldSend!=firstFuture,800),"accepted terminal failed to advance next identity");
                sendQueue.postRunnable(()->completePreparedSend(old,false,new IOException("synthetic duplicate late callback")));
                check(await(()->sendQueue.tasks.isEmpty(),300),"duplicate callback not observed");check(preparedInFlight==1&&preparedSends.get(1L).peekFirst()!=old&&store.get(id).submissionAccepted&&!store.get(id).submissionUncertain,"late failure downgraded accepted or released current slot");
                owner.heldSend.complete(new JsonObject());check(await(()->store.get(next).submissionAccepted,800),"current callback lost after old duplicate");
            }
            check(pendingMessages.get(id).messageOwner.send_state==0&&preparedInFlight==0&&sendJobs.isEmpty(),"accepted original bubble or terminal cleanup changed");System.out.println("ACCEPTED_DUPLICATE acceptedNoDowngrade=true oldCallbackDidNotReleaseNewSlot=true");
        }

        /** 删除、账号与会话切换的迟到回执不能改盘；同账号换连接仍接受原成功ACK。 */
        static void late(Path root,String mode)throws Exception{
            reset(root);DesktopConnection owner=connection;owner.holdSend=true;String id=text(1);MessageObject original=pendingMessages.get(id);
            try(Worker worker=new Worker()){
                check(owner.rpcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"actual send missed late barrier");
                if(mode.equals("delete")){deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(original.getId())));check(await(()->store.get(id)==null,600),"delete blocked behind ACK");}
                else if(mode.equals("logout")){
                    java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();logout(()->done.set(true),message->{throw new AssertionError("synthetic logout failed");});
                    Utilities.globalQueue.all();dialogQueue.all();uploadQueue.all();approvalQueue.all();transcriptQueue.all();historyQueue.all();statusQueue.all();prefetchQueue.all();Utilities.globalQueue.all();
                    check(await(()->{ui.all();return done.get();},800)&&session==null&&sendJobs.isEmpty(),"logout did not release original account");
                }else if(mode.equals("account"))accountGeneration++;else if(mode.equals("remote"))remoteIds.put(1L,"synthetic-rebound");else desktopConnections.put("synthetic-machine",new DesktopConnection());
                owner.heldSend.complete(new JsonObject());check(await(()->sendJobs.isEmpty(),800),"late callback retained send job");
            }
            if(mode.equals("delete"))check(store.get(id)==null&&pendingMessages.isEmpty(),"late ACK resurrected deleted identity");
            else if(mode.equals("logout"))check(store.get(id).submissionUncertain&&session==null&&preparedInFlight==0&&preparedDialogs.isEmpty(),"late old ACK changed logout state");
            else if(mode.equals("connection"))check(store.get(id).submissionAccepted&&original.messageOwner.send_state==0,"connection replacement reversed successful ACK");
            else check(store.get(id).submissionUncertain&&!store.get(id).submissionAccepted&&original.messageOwner.send_state==1,"late old ownership ACK altered current data");
            check(preparedSends.isEmpty(),"late callback retained ready gate");System.out.println("LATE mode="+mode+" originalIdentityGuarded=true");
        }
        public static void main(String[] args)throws Exception{Path root=Paths.get(args[0]);blockedAck(root);blockedLink(root);terminal(root,false);terminal(root,true);boundedFair(root);acceptedAndDuplicate(root);for(String mode:new String[]{"delete","account","remote","connection","logout"})late(root,mode);System.out.println("RuntimeSendAsyncQueue: 11组真实Java8方法异步首写/会话顺序/RPC终态/迟到守卫通过");}
        """;
}
