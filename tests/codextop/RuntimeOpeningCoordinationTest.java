package com.butang.codextop;

import java.nio.file.Path;

/** 实际冷首屏三个入口共用请求的交错验证；网络及Android边界合成，Runtime/模型/存储执行正式代码。 */
public final class RuntimeOpeningCoordinationTest {
    /** 可对同一冻结旧源码运行取得有效RED；参数二可仅执行指定行为组。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        String group = args.length > 1 ? args[1] : "all";
        if (!group.matches("[a-z-]+")) throw new IllegalArgumentException("unknown test group");
        RuntimeLatestSegmentTest.runScenarios(runtime, SCENARIOS, "coordinationRun(\"" + group + "\");");
    }

    private static final String SCENARIOS = """
        interface CoordinationCase {void run()throws Exception;}
        static final ArrayList<String> coordinationFailures=new ArrayList<>();static int coordinationGroups;
        /** 每个组独立收集结果，旧实现可以同时显示两个顺序的真实失败。 */
        static void coordinationCase(String selected,String name,CoordinationCase body)throws Exception{
            if(!selected.equals("all")&&!selected.equals(name))return;
            coordinationGroups++;
            try{body.run();System.out.println("PASS coordination "+name);}catch(Exception|AssertionError error){coordinationFailures.add(name+": "+error);System.out.println("FAIL coordination "+name+": "+error);}
        }
        /** 只推进立即的owner/UI边界，未来watch周期必须显式唤醒。 */
        static void coordinationOwners(){int guard=120;while(!Utilities.globalQueue.ready.isEmpty()){check(--guard>0,"owner did not yield");Utilities.globalQueue.next();}ui();}
        /** 源为空但真实缓存文件存在；开页通过正式token，不直接伪造HistoryView。 */
        static long coordinationReset()throws Exception{reset(new TranscriptWindow());histories.clear();return openHistoryView(0,42,7);}
        /** 使用真实候选字段触发原prefetch入口，所有身份都是合成且已绑定的。 */
        static JsonArray coordinationCandidates(){JsonObject row=new JsonObject();row.addProperty("remoteSessionId","synthetic-remote");row.addProperty("updatedAtMs",17);JsonArray rows=new JsonArray();rows.add(row);return rows;}
        /** 首屏通知与增量分别核验，不能拿增量通知冒充完成页面加载。 */
        static Event coordinationPage(){for(int i=events.size()-1;i>=0;i--){Event event=events.get(i);if(event.type==NotificationCenter.messagesDidLoad&&!metadata(event).replaceLatest)return event;}return null;}
        /** 同一已经加载的窗口不得因watch再通知一次相同正文。 */
        static void coordinationNoDuplicateBody(){for(Event event:events)if(event.type==NotificationCenter.didReceiveNewMessages)check(rows(event).isEmpty(),"watch republished shared first body");}
        /** 一次只推进实际已经排队的网络任务，兼容旧两队列与新前台统一队列。 */
        static void coordinationNetwork(boolean watchFirst){
            if(watchFirst&&!transcriptQueue.ready.isEmpty())transcriptQueue.next();
            else if(!historyQueue.ready.isEmpty())historyQueue.next();
            else if(!transcriptQueue.ready.isEmpty())transcriptQueue.next();
            else if(!prefetchQueue.ready.isEmpty())prefetchQueue.next();
            else throw new AssertionError("no network work queued");
        }
        /** 两个参与者在同一回包前登记，mode沿原调用透传。 */
        static void coordinationPrepare(boolean watchFirst,int mode)throws Exception{
            coordinationReset();current.latest=page("Z",false,null,"tail");
            if(watchFirst){watchConversation(0,42);loadMessages(0,42,1,0,7,2,1,mode);}
            else{loadMessages(0,42,1,0,7,2,1,mode);watchConversation(0,42);}
            coordinationOwners();
        }
        /** 无论谁先登记，第一共享回包即交付普通消息页，并且只合入保存一次。 */
        static void coordinationOrder(boolean watchFirst,int mode)throws Exception{
            coordinationPrepare(watchFirst,mode);
            try{
                coordinationNetwork(watchFirst);coordinationOwners();
                Event first=coordinationPage();
                check(first!=null&&rows(first).equals("Z")&&((Integer)first.args[14])==mode,
                        "first shared response did not deliver messagesDidLoad; watchFirst="+watchFirst+" mode="+mode);
                pump();
                check(current.requests.equals(Collections.singletonList("latest"))&&saves==1,
                        "cold opening not one request/merge/save requests="+current.requests+" saves="+saves);
                check(Utilities.globalQueue.lastDelay()==2000,"shared success entered failure watch delay");
                check(ids(histories.get(42L)).equals("Z")&&ids(store.read("synthetic-remote")).equals("Z"),"shared body not retained");
                coordinationNoDuplicateBody();
            }finally{cleanup();}
        }
        /** 定时辅助查询独立空终态；watch仍沿原最新请求保存正文，不能冒充定时消息。 */
        static void coordinationScheduled(boolean watchFirst)throws Exception{
            coordinationPrepare(watchFirst,1);
            try{
                check(historyViews.get(7).initialLoadTicket==null&&historyViews.get(7).initialLoadWaiter==null,"scheduled claimed ordinary initial load");
                pump();int terminals=0;
                for(Event event:events)if(event.type==NotificationCenter.messagesDidLoad&&(Integer)event.args[14]==1){terminals++;check(event.args.length==15&&rows(event).isEmpty()&&(Boolean)event.args[9]&&(Integer)event.args[10]==7&&(Integer)event.args[11]==1,"scheduled not original empty terminal");}
                check(terminals==1,"scheduled completion missing/duplicated");
                check(current.requests.equals(Collections.singletonList("latest"))&&saves==1&&ids(histories.get(42L)).equals("Z")&&ids(store.read("synthetic-remote")).equals("Z"),"standalone watch changed by scheduled probe");
                check(Utilities.globalQueue.lastDelay()==2000,"scheduled changed watch cycle");
            }finally{cleanup();}
        }
        /** 未开始的后台ticket可提升；旧ticket执行不能再发请求，前台不依赖后台队列先清空。 */
        static void coordinationPrefetchQueued()throws Exception{
            coordinationReset();
            try{
                current.latest=page("Z",false,null,"tail");prefetchDialogs(coordinationCandidates(),current);coordinationOwners();
                check(prefetchQueue.ready.size()==1&&current.requests.isEmpty(),"prefetch did not reach queued boundary");
                loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);coordinationOwners();
                check(!historyQueue.ready.isEmpty(),"queued prefetch was not promoted to foreground queue");
                historyQueue.next();coordinationOwners();
                check(coordinationPage()!=null&&rows(coordinationPage()).equals("Z"),"foreground still waits behind untouched prefetch queue");
                pump();check(current.requests.equals(Collections.singletonList("latest"))&&saves==1&&!prefetching.contains(42L),"old prefetch ticket repeated request/save or leaked inflight");
                coordinationNoDuplicateBody();
            }finally{cleanup();}
        }
        /** 在真实网络task已进入后登记load/watch，测试加入STARTED而不是再发前台RPC。 */
        static void coordinationPrefetchStarted()throws Exception{
            coordinationReset();
            try{
                current.latest=page("Z",false,null,"tail");prefetchDialogs(coordinationCandidates(),current);coordinationOwners();
                current.onRequest=()->{loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);coordinationOwners();};
                prefetchQueue.next();coordinationOwners();
                check(coordinationPage()!=null&&rows(coordinationPage()).equals("Z"),"joining started prefetch did not deliver first reply");
                pump();check(current.requests.equals(Collections.singletonList("latest"))&&saves==1&&!prefetching.contains(42L),"started prefetch not shared/cleared");
                check(Utilities.globalQueue.lastDelay()==2000,"started prefetch joining watch used error delay");coordinationNoDuplicateBody();
            }finally{cleanup();}
        }
        /** 单方关闭不会剥夺另一方结果；全失效不落盘、不重启观察也不投递。 */
        static void coordinationCancellation()throws Exception{
            for(String kind:new String[]{"watch","load","all"}){
                coordinationPrepare(kind.equals("watch"),0);
                try{
                    coordinationNetwork(kind.equals("watch"));
                    if(!kind.equals("load"))stopWatching(42);
                    if(!kind.equals("watch")){HistoryView view=historyViews.get(7);closeHistoryView(7,view.token);}
                    coordinationOwners();pump();
                    if(kind.equals("all")){check(saves==0&&ids(histories.get(42L)).isEmpty()&&events.isEmpty()&&Utilities.globalQueue.delayed.isEmpty(),"all cancelled request still applied/published/scheduled");}
                    else{
                        check(saves==1&&ids(histories.get(42L)).equals("Z"),"remaining participant lost valid reply: "+kind);
                        if(kind.equals("watch"))check(rows(normal()).equals("Z")&&Utilities.globalQueue.delayed.isEmpty(),"stopped watch restarted or load lost page");
                        else check(events.isEmpty()&&Utilities.globalQueue.lastDelay()==2000,"closed view got notice or live watch stopped");
                    }
                    check(current.requests.equals(Collections.singletonList("latest")),"cancellation caused duplicate request: "+kind+" "+current.requests);
                }finally{cleanup();}
            }
        }
        /** 未开始就全部取消不发RPC；只取消一侧仍可用另一侧原请求资格。 */
        static void coordinationQueuedCancellation()throws Exception{
            for(String kind:new String[]{"all","watch","load"}){
                coordinationPrepare(false,0);
                try{
                    if(!kind.equals("load"))stopWatching(42);
                    if(!kind.equals("watch")){HistoryView view=historyViews.get(7);closeHistoryView(7,view.token);}
                    pump();
                    if(kind.equals("all"))check(current.requests.isEmpty()&&saves==0&&events.isEmpty(),"all queued participants cancelled but RPC started");
                    else check(current.requests.equals(Collections.singletonList("latest"))&&saves==1&&ids(histories.get(42L)).equals("Z"),"queued cancellation lost survivor or duplicated RPC");
                }finally{cleanup();}
            }
            coordinationReset();
            try{
                current.latest=page("Z",false,null,"tail");prefetchDialogs(coordinationCandidates(),current);coordinationOwners();ApplicationLoader.mainInterfacePaused=true;pump();
                check(current.requests.isEmpty()&&saves==0&&!prefetching.contains(42L),"paused queued prefetch sent or leaked gate");
            }finally{cleanup();}
        }
        /** 先取出旧后台和提升后的前台ticket再同时认领，假队列不被并发消费。 */
        static void coordinationPromotionRace()throws Exception{
            coordinationReset();
            try{
                current.latest=page("Z",false,null,"tail");prefetchDialogs(coordinationCandidates(),current);coordinationOwners();
                Runnable oldTicket=prefetchQueue.ready.remove();loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);coordinationOwners();
                check(historyQueue.ready.size()==1&&transcriptQueue.ready.isEmpty(),"promotion did not create sole foreground ticket");Runnable newTicket=historyQueue.ready.remove();
                java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2),start=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
                Thread[] workers=new Thread[2];Runnable[] tickets={oldTicket,newTicket};
                for(int i=0;i<workers.length;i++){final Runnable ticket=tickets[i];workers[i]=new Thread(()->{try{ready.countDown();start.await();ticket.run();}catch(Throwable error){failure.compareAndSet(null,error);}},"synthetic-ticket-"+i);workers[i].start();}
                boolean bothReady;try{bothReady=ready.await(5,java.util.concurrent.TimeUnit.SECONDS);}finally{start.countDown();}
                for(Thread worker:workers){worker.join(5000);check(!worker.isAlive(),"ticket worker did not finish");}
                check(bothReady&&failure.get()==null,"concurrent ticket failed/not ready: "+failure.get());coordinationOwners();pump();
                check(current.requests.equals(Collections.singletonList("latest"))&&saves==1&&rows(normal()).equals("Z")&&!prefetching.contains(42L),"two actual queue tickets claimed more than one RPC/save");
            }finally{cleanup();}
        }
        /** 回包已排owner后变化账号、连接、root、游标或来源；不允许旧正文进入新上下文。 */
        static void coordinationContextChanges()throws Exception{
            for(String kind:new String[]{"account","connection","root","cursor","remote","machine"}){
                coordinationPrepare(false,0);DesktopConnection requested=current;
                try{
                    coordinationNetwork(false);
                    if(kind.equals("account")){accountGeneration++;histories.clear();session=new PasswordLogin.Session();}
                    else if(kind.equals("connection")){current=new DesktopConnection();desktopConnections.put(current.machineId,current);}
                    else if(kind.equals("root"))histories.put(42L,new TranscriptWindow());
                    else if(kind.equals("cursor"))histories.get(42L).cursor="external-change";
                    else if(kind.equals("remote"))remoteIds.put(42L,"different-remote");
                    else dialogMachines.put(42L,"different-machine");
                    coordinationOwners();
                    check(saves==0,"stale "+kind+" reply saved");
                    for(Event event:events)if(event.type==NotificationCenter.messagesDidLoad||event.type==NotificationCenter.didReceiveNewMessages)check(rows(event).isEmpty(),"stale "+kind+" body delivered");
                    TranscriptWindow root=histories.get(42L);check(root==null||ids(root).isEmpty(),"stale "+kind+" reply merged");
                    check(requested.requests.size()==1,"unexpected pre-reply count");
                }finally{cleanup();}
            }
        }
        /** 老completion最后抵达也不能移除同dialog的新请求；第三参与者仍加入新flight。 */
        static void coordinationOldCompletion()throws Exception{
            coordinationPrepare(false,0);DesktopConnection previous=current;
            try{
                coordinationNetwork(false);check(Utilities.globalQueue.ready.size()==1,"old reply not isolated");Runnable oldComplete=Utilities.globalQueue.ready.remove();
                HistoryView prior=historyViews.get(7);closeHistoryView(7,prior.token);stopWatching(42);
                current=new DesktopConnection();desktopConnections.put(current.machineId,current);histories.put(42L,new TranscriptWindow());openHistoryView(0,42,7);
                current.latest=page("NEW",false,null,"new-tail");loadMessages(0,42,1,0,7,2,2,0);coordinationOwners();
                Utilities.globalQueue.postRunnable(oldComplete);coordinationOwners();watchConversation(0,42);coordinationOwners();
                coordinationNetwork(false);coordinationOwners();pump();
                check(previous.requests.size()==1&&current.requests.equals(Collections.singletonList("latest"))&&saves==1,"old completion removed/repeated new flight old="+previous.requests+" new="+current.requests+" saves="+saves);
                check(rows(normal()).equals("NEW")&&ids(histories.get(42L)).equals("NEW"),"old completion displaced new body");
            }finally{cleanup();}
        }
        /** 实际Store写路径失败仍可展示已接收正文，但不标预取完成、不删除未持久化回显待发。 */
        static void coordinationSaveFailure()throws Exception{
            coordinationReset();
            try{
                current.latest=page("",false,null,"tail");current.latest.getAsJsonArray("items").add(item("Z","echo"));
                outbox.put(new OutboxStore.Item("echo","synthetic-remote","synthetic",-7,1));outbox.markSubmissionUncertain("echo",true);
                prefetchDialogs(coordinationCandidates(),current);coordinationOwners();
                loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);coordinationOwners();
                Path blocked=directory.resolve("blocked");Files.write(blocked,new byte[]{1});store=new TranscriptStore(blocked.toFile(),"s","a","m");
                coordinationNetwork(false);coordinationOwners();pump();
                check(rows(normal()).equals("Z")&&saves==1&&current.requests.size()==1,"failed save prevented display or repeated save");
                check(outbox.get("echo")!=null&&!prefetchedRevisions.containsKey(42L)&&!prefetching.contains(42L),"failed persistence acknowledged durable cache or leaked prefetch");
            }finally{cleanup();}
        }
        /** 唯一兼容例是缺tail的有效load页；watch/prefetch不能把它当bootstrap成功。 */
        static void coordinationMissingTail()throws Exception{
            for(String kind:new String[]{"both","watch","prefetch"}){
                coordinationReset();
                try{
                    current.latest=page("Z",false,null,null);current.latest.remove("tailCursor");
                    if(kind.equals("prefetch"))prefetchDialogs(coordinationCandidates(),current);
                    else{if(kind.equals("both"))loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);}
                    coordinationOwners();coordinationNetwork(false);coordinationOwners();pump();
                    if(kind.equals("both"))check(rows(normal()).equals("Z")&&saves==1&&histories.get(42L).needsTailBootstrap(),"missing-tail load compatibility lost");
                    else check(saves==0&&ids(histories.get(42L)).isEmpty(),"missing-tail non-load accepted bootstrap");
                    check(current.requests.size()==1&&!prefetchedRevisions.containsKey(42L),"missing-tail page duplicated or marked synced");
                    if(!kind.equals("prefetch"))check(Utilities.globalQueue.lastDelay()==5000,"missing-tail watch falsely counted bootstrap success");
                }finally{cleanup();}
            }
        }
        /** 完整性与投影拒收沿真实模型执行；共同错误只生成原失败交付，不保存预览正文。 */
        static void coordinationInvalidPages()throws Exception{
            for(String kind:new String[]{"truncated","page_limit","source_discontinuity","preview","items","more","tail-type","next-missing"}){
                coordinationPrepare(false,0);
                try{
                    if(kind.equals("truncated"))current.latest.addProperty("truncated",true);
                    else if(kind.equals("page_limit")||kind.equals("source_discontinuity"))current.latest.addProperty("truncationReason",kind);
                    else if(kind.equals("preview"))current.latest.addProperty("historyAvailability","preview");
                    else if(kind.equals("items"))current.latest.remove("items");
                    else if(kind.equals("more"))current.latest.addProperty("hasMore","invalid");
                    else if(kind.equals("tail-type"))current.latest.add("tailCursor",new JsonArray());
                    else{current.latest=page("Z",true,null,"tail");}
                    coordinationNetwork(false);coordinationOwners();pump();
                    check(saves==0&&ids(histories.get(42L)).isEmpty()&&current.requests.size()==1,"invalid "+kind+" page accepted or duplicated");
                    for(Event event:events)if(event.type==NotificationCenter.messagesDidLoad||event.type==NotificationCenter.didReceiveNewMessages)check(rows(event).isEmpty(),"invalid "+kind+" body published");
                    check(Utilities.globalQueue.lastDelay()==5000,"invalid page did not retain failure schedule");
                }finally{cleanup();}
            }
        }
        /** 空投影latest及older共享但各入口原四页预算不扩大；找到正文立即交付。 */
        static void coordinationEmptyBudget()throws Exception{
            for(boolean visible:new boolean[]{false,true}){
                coordinationPrepare(false,0);
                try{
                    current.latest=page("",true,"empty-1","tail");JsonObject tool=item("tool",null);tool.getAsJsonObject("raw").getAsJsonObject("content").addProperty("type","tool");current.latest.getAsJsonArray("items").add(tool);
                    for(int i=1;i<=3;i++)current.older("empty-"+i,page(visible&&i==1?"Z":"",true,"empty-"+(i+1),null));
                    pump();
                    check(current.requests.equals(visible?Arrays.asList("latest","older:empty-1"):Arrays.asList("latest","older:empty-1","older:empty-2","older:empty-3")),"empty shared page altered budget: "+current.requests);
                    check(rows(normal()).equals(visible?"Z":"")&&saves==(visible?2:4)&&!((Boolean)normal().args[9]),"empty/first-body delivery or merge count incorrect");
                    check(Utilities.globalQueue.lastDelay()==2000,"empty chain changed original watch budget");
                }finally{cleanup();}
            }
        }
        /** 仅后台预取也保留自己的四页预算与门禁，空正文不能记版本已同步。 */
        static void coordinationPrefetchBudget()throws Exception{
            coordinationReset();
            try{
                current.latest=page("",true,"empty-1","tail");for(int i=1;i<=3;i++)current.older("empty-"+i,page("",true,"empty-"+(i+1),null));
                prefetchDialogs(coordinationCandidates(),current);pump();
                check(current.requests.equals(Arrays.asList("latest","older:empty-1","older:empty-2","older:empty-3"))&&saves==4,"prefetch exceeded original empty budget: "+current.requests);
                check(!prefetchedRevisions.containsKey(42L)&&!prefetching.contains(42L)&&events.isEmpty(),"empty prefetch falsely synced or published body/leaked gate");
            }finally{cleanup();}
        }
        /** 同view仅消费一次cold handoff；新token有正文时改为一次完整tail，不能重用旧handoff。 */
        static void coordinationCompletedBeforeWatch()throws Exception{
            for(boolean reopen:new boolean[]{false,true}){
                long token=coordinationReset();
                try{
                    current.latest=page("Z",false,null,"tail");loadMessages(0,42,1,0,7,2,1,0);pump();check(rows(normal()).equals("Z"),"load setup not complete");
                    if(reopen){pauseHistoryView(7,token);openHistoryView(0,42,7);check(historyViews.get(7).coldHistoryHandoff==null,"新token借用旧handoff");}
                    JsonObject caughtUp=page("",false,"tail",null);caughtUp.addProperty("truncated",false);current.after.add(caughtUp);events.clear();watchConversation(0,42);pump();
                    check(current.requests.equals(Arrays.asList("latest","after:tail")),"completed-before-join handoff stale/missing: "+current.requests);
                    check(Utilities.globalQueue.lastDelay()==2000&&historyViews.get(7).coldHistoryHandoff==null,"handoff未一次消费或watch失败");
                    stopWatching(42);current.after.add(caughtUp);watchConversation(0,42);pump();
                    check(current.requests.equals(Arrays.asList("latest","after:tail","after:tail")),"重开未走一次tail或复用了旧handoff");
                }finally{cleanup();}
            }
        }
        /** 后台tail留在原队列；前台一次探测遇积压后立即latest，不等待后台队列。 */
        static void coordinationWarmTailDoesNotBlockOpening()throws Exception{
            reset(cache("A,B"));
            try{
                openCached();current.latest=page("Y,Z",true,"fresh-older","fresh-tail");
                openingBacklog();current.after.add(page("C",false,"later-old-tail",null));
                prefetchDialogs(coordinationCandidates(),current);coordinationOwners();
                check(prefetchQueue.ready.size()==1&&current.requests.isEmpty(),"warm prefetch did not stay queued");
                watchConversation(0,42);coordinationOwners();
                check(transcriptQueue.ready.size()==1&&prefetchQueue.ready.size()==1,"opening waits for background tail queue");
                transcriptQueue.next();coordinationOwners();
                check(current.requests.equals(Collections.singletonList("after:old-tail"))&&prefetchQueue.ready.size()==1,"探测等待了后台tail");
                transcriptQueue.next();coordinationOwners();
                check(current.requests.equals(Arrays.asList("after:old-tail","latest"))&&rows(replacement()).equals("Z,Y"),"opening tail catchup replaced immediate latest");
                pump();check(ids(histories.get(42L)).equals("Z,Y")&&!prefetching.contains(42L),"late background tail changed latest root or leaked gate");
            }finally{cleanup();}
        }
        /** 磁盘遗留空段的older没有本次latest事实，不能伪造handoff让opening跳过latest。 */
        static void coordinationCachedOlderCannotHandoff()throws Exception{
            reset(cache(""));
            try{
                openHistoryView(0,42,7);current.older("old-older",page("A,B",true,"more-old",null));
                current.after.add(page("",false,"old-tail",null));
                prefetchDialogs(coordinationCandidates(),current);coordinationOwners();
                loadMessages(0,42,1,0,7,2,1,0);coordinationOwners();pump();
                check(rows(normal()).equals("B")&&historyViews.get(7).coldHistoryHandoff==null,"cached older synthesized latest handoff");
                check(current.requests.equals(Arrays.asList("older:old-older","after:old-tail")),"cached empty did not recover older then original tail");
                events.clear();openingBacklog();current.latest=page("Y,Z",true,"fresh-older","fresh-tail");watchConversation(0,42);pump();
                check(current.requests.get(current.requests.size()-1).equals("latest")&&rows(replacement()).equals("Z")&&ids(histories.get(42L)).equals("Z,Y"),"cached older handoff skipped opening latest requests="+current.requests+" rows="+rows(replacement()));
            }finally{cleanup();}
        }
        /** 所有组均从正式公开入口登记，完整列出成功、失效和边界。 */
        static void coordinationRun(String selected)throws Exception{
            coordinationCase(selected,"orders",()->{coordinationOrder(false,0);coordinationOrder(true,0);});
            coordinationCase(selected,"scheduled",()->{coordinationScheduled(false);coordinationScheduled(true);});
            coordinationCase(selected,"prefetch-queued",()->coordinationPrefetchQueued());
            coordinationCase(selected,"prefetch-started",()->coordinationPrefetchStarted());
            coordinationCase(selected,"cancellation",()->coordinationCancellation());
            coordinationCase(selected,"queued-cancellation",()->coordinationQueuedCancellation());
            coordinationCase(selected,"promotion-race",()->coordinationPromotionRace());
            coordinationCase(selected,"context",()->coordinationContextChanges());
            coordinationCase(selected,"old-completion",()->coordinationOldCompletion());
            coordinationCase(selected,"save-failure",()->coordinationSaveFailure());
            coordinationCase(selected,"missing-tail",()->coordinationMissingTail());
            coordinationCase(selected,"invalid",()->coordinationInvalidPages());
            coordinationCase(selected,"empty-budget",()->coordinationEmptyBudget());
            coordinationCase(selected,"prefetch-budget",()->coordinationPrefetchBudget());
            coordinationCase(selected,"completed",()->coordinationCompletedBeforeWatch());
            coordinationCase(selected,"warm-tail-open",()->coordinationWarmTailDoesNotBlockOpening());
            coordinationCase(selected,"cached-older-open",()->coordinationCachedOlderCannotHandoff());
            check(coordinationGroups>0,"no selected group");
            check(coordinationFailures.isEmpty(),"opening coordination failures="+coordinationFailures);
            System.out.println("RuntimeOpeningCoordination: groups="+coordinationGroups+" failures=0");
        }
        """;
}
