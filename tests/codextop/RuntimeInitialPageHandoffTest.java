package com.butang.codextop;

import java.nio.file.Path;

/** 执行真实 Runtime 首屏交接；只控制外部队列、网络和通知消费边界，不测手机耗时。 */
public final class RuntimeInitialPageHandoffTest {
    /** 同一组可指定旧冻结源码取得有效 RED，模型及交付仍复用唯一真实源码夹具。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        String group = args.length > 1 ? args[1] : "all";
        if (!group.equals("all") && !group.equals("legacy") && !group.equals("save")) throw new IllegalArgumentException("未知首屏测试组");
        if (!group.equals("save")) RuntimeLatestSegmentTest.runScenarios(runtime, SCENARIOS,
                "initialLatestBeforeQueuedOlder();initialLatestBeforeStartedOlder();initialNewLoadCancelsDelayedPage();initialLifecycleCancels();"
                + "System.out.println(\"RuntimeInitialPageHandoff: groups=4 failures=0\");");
        if (!group.equals("legacy")) runSaveScenarios(runtime);
    }

    /** 复用唯一真实方法夹具及已有临时epoch观察器；只阻塞saveHistory取pin，不替换保存实现。 */
    private static void runSaveScenarios(Path runtime) throws Exception {
        var runner = RuntimeLatestSegmentTest.class.getDeclaredMethod("runScenarios", Path.class, String.class, String.class, boolean.class);
        runner.setAccessible(true);
        try { runner.invoke(null, runtime, SCENARIOS, "initialSaveRun();", true); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Error) throw (Error) error.getCause();
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private static final String SCENARIOS = """
        /** 原入口均停在真实saveHistory取pin处；watch明确缺尾游标以测latest保存，接收在放行后验证。 */
        static void initialBeforeSave(String path,String invalidation,boolean failWrite)throws Exception{
            Thread worker=null;final Throwable[] workerFailure={null};
            java.util.concurrent.CountDownLatch saving=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean gated=new java.util.concurrent.atomic.AtomicBoolean();
            final long token;
            if(path.equals("handoff"))token=initialPrepare();
            else if(path.equals("watch")){TranscriptWindow cached=cache("A,B");cached.tailCursor=null;reset(cached);token=openCached();}
            else if(path.equals("prefetch-direct")){
                TranscriptWindow empty=new TranscriptWindow();empty.prepend(page("",true,"older",null));
                reset(empty);token=openHistoryView(0,42,7);
            }else{reset(new TranscriptWindow());token=openHistoryView(0,42,7);}
            try{
                current.latest=page("",false,null,"new-tail");current.latest.getAsJsonArray("items").add(item("Z","saved-echo"));
                outbox.put(new OutboxStore.Item("saved-echo","synthetic-remote","synthetic",-7,1));
                outbox.markSubmissionUncertain("saved-echo",true);
                TranscriptStore persisted=store;JsonObject before=persisted.read("synthetic-remote").snapshot();
                if(path.startsWith("prefetch-")){
                    JsonObject candidate=new JsonObject();candidate.addProperty("remoteSessionId","synthetic-remote");candidate.addProperty("updatedAtMs",17);
                    JsonArray candidates=new JsonArray();candidates.add(candidate);prefetchDialogs(candidates,current);initialOwners(true);
                }
                if(!path.equals("handoff")){
                    if(!path.equals("watch"))loadMessages(0,42,1,0,7,2,1,0);
                    if(path.equals("watch")||path.equals("shared"))watchConversation(0,42);
                    initialOwners(true);
                }
                events.clear();
                if(path.equals("watch")||path.equals("handoff"))transcriptQueue.next();
                else if(path.equals("prefetch-direct"))prefetchQueue.next();else historyQueue.next();
                if(failWrite)store=new TranscriptStore(directory.resolve("history").toFile(),"synthetic-server","synthetic-account","synthetic-machine",1);
                epochObserver=(window,thread)->{
                    if(!thread.getName().equals("initial-save-owner"))return;
                    boolean inSave=false;for(StackTraceElement frame:thread.getStackTrace())if(frame.getMethodName().equals("saveHistory"))inSave=true;
                    if(inSave&&gated.compareAndSet(false,true)){saving.countDown();try{
                        check(release.await(5,java.util.concurrent.TimeUnit.SECONDS),"save gate timed out");
                    }catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
                };
                worker=new Thread(()->{try{Utilities.globalQueue.next();}catch(Throwable error){workerFailure[0]=error;}},"initial-save-owner");
                worker.start();check(saving.await(5,java.util.concurrent.TimeUnit.SECONDS),"real saveHistory did not reach pin boundary");
                check(saves==0&&persisted.read("synthetic-remote").snapshot().equals(before)&&outbox.get("saved-echo")!=null,
                        "save gate already persisted or deleted outbox");
                if(invalidation.equals("ticket"))loadMessages(0,42,1,histories.get(42L).findSource("Z",null).id,7,3,2,0);
                else if(invalidation.equals("epoch"))accountGeneration++;
                else if(invalidation.equals("pause"))pauseHistoryView(7,token);
                ui();
                if(invalidation.isEmpty()){
                    Event visible=path.equals("watch")?replacement():normal();
                    check(rows(visible).equals("Z")&&isHistoryPageCurrent(metadata(visible)),"latest UI did not precede blocked save: "+path);
                    check(outbox.get("saved-echo")!=null&&!prefetchedRevisions.containsKey(42L),"visible page claimed durable save");
                }else check(events.isEmpty(),"invalidated latest UI survived blocked save: "+invalidation);
                release.countDown();worker.join(5000);check(!worker.isAlive()&&workerFailure[0]==null,"save worker failed: "+workerFailure[0]);epochObserver=null;
                check(saves==1,"latest save skipped or repeated");
                TranscriptWindow disk=persisted.read("synthetic-remote");
                if(failWrite)check(disk.snapshot().equals(before)&&outbox.get("saved-echo")!=null&&!prefetchedRevisions.containsKey(42L),
                        "failed write lost prior cache/outbox or advanced durable revision");
                else check(disk.findSource("Z",null)!=null&&outbox.get("saved-echo")==null,"successful save failed echo-before-outbox contract");
                if(path.equals("watch")&&invalidation.isEmpty())check(acceptLatestHistory(metadata(replacement())),"latest replacement cannot be accepted after save");
                if(path.startsWith("prefetch-"))check(failWrite?!prefetchedRevisions.containsKey(42L):Long.valueOf(17).equals(prefetchedRevisions.get(42L)),"prefetch revision ignored actual save result");
                if(invalidation.isEmpty()){
                    int pages=initialPageCount(1);pump();check(initialPageCount(1)==pages,"post-save callback repeated initial page");
                }
            }finally{release.countDown();if(worker!=null)worker.join(5000);epochObserver=null;cleanup();}
        }
        /** 每组分别报告，旧源的保存前等待必须形成行为RED而非编译错误。 */
        static void initialSaveRun()throws Exception{
            ArrayList<String> failed=new ArrayList<>();int groups=0;
            String[][] cases={{"handoff","","false"},{"shared","","false"},{"load","","false"},{"watch","","false"},
                    {"prefetch-load","","false"},{"prefetch-direct","","false"},
                    {"handoff","","true"},{"prefetch-load","","true"},{"prefetch-direct","","true"},
                    {"handoff","ticket","false"},{"shared","ticket","false"},
                    {"shared","epoch","false"},{"watch","pause","false"}};
            for(String[] value:cases){groups++;String name=value[0]+"/"+value[1]+"/failure="+value[2];try{
                initialBeforeSave(value[0],value[1],Boolean.parseBoolean(value[2]));System.out.println("PASS save-order "+name);
            }catch(Exception|AssertionError error){failed.add(name+": "+error);System.out.println("FAIL save-order "+name+": "+error);}}
            for(boolean shared:new boolean[]{false,true}){groups++;try{initialDeliveryFailureSaves(shared);System.out.println("PASS delivery failure still saves shared="+shared);}
                catch(Exception|AssertionError error){failed.add("delivery-failure/"+shared+": "+error);System.out.println("FAIL delivery failure "+shared+": "+error);}}
            check(failed.isEmpty(),"save-order failures="+failed);System.out.println("RuntimeInitialPageSave: groups="+groups+" failures=0; synthetic notification boundary only");
        }
        /** 原Outbox读取真实IO失败使首屏准备失败，已合入合法最新页仍须落盘，坏条目及回显待发保留。 */
        static void initialDeliveryFailureSaves(boolean shared)throws Exception{
            if(shared){reset(new TranscriptWindow());openHistoryView(0,42,7);}
            else initialPrepare();
            try{
                current.latest=page("",false,null,"new-tail");current.latest.getAsJsonArray("items").add(item("Z","persist-after-error"));
                outbox.put(new OutboxStore.Item("persist-after-error","synthetic-remote","synthetic",-7,1));
                Path broken=directory.resolve("outbox").resolve(TranscriptStore.digest("synthetic-server\\nsynthetic-account\\nsynthetic-machine")).resolve("broken.json");
                Files.createDirectories(broken);
                if(shared){loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);initialOwners(true);historyQueue.next();}
                else transcriptQueue.next();
                initialOwners(true);pump();
                check(saves==1&&store.read("synthetic-remote").findSource("Z",null)!=null,"UI preparation failure skipped legitimate save");
                check(outbox.get("persist-after-error")!=null&&Files.isDirectory(broken)&&!prefetchedRevisions.containsKey(42L),"failed Outbox read deleted pending or claimed persistence");
                check(initialPageCount(1)==1&&rows(normal()).isEmpty(),"failed preparation did not preserve original empty error page");
            }finally{cleanup();}
        }
        /** 只执行现有 owner 队列；UI 可单独延迟，不能把增量通知当首次完整页。 */
        static void initialOwners(boolean consumeUi){
            int bound=100;while(!Utilities.globalQueue.ready.isEmpty()){check(--bound>0,"initial owner loop");Utilities.globalQueue.next();}
            if(consumeUi)ui();
        }
        /** 真实模型先持久化已加载的空投影，最新与向旧是两个合法不同 query。 */
        static long initialPrepare()throws Exception{
            TranscriptWindow empty=new TranscriptWindow();empty.prepend(page("",true,"older","old-tail"));reset(empty);
            long token=openHistoryView(0,42,7);
            current.latest=page("Z",true,"latest-older","new-tail");
            current.older("older",page("old-response",false,null,null));
            current.older("latest-older",page("Y",false,null,null));
            loadMessages(0,42,1,0,7,2,1,0);watchConversation(0,42);initialOwners(true);
            check(historyQueue.ready.size()==1&&transcriptQueue.ready.size()==1&&events.isEmpty(),
                    "fixture did not reach loaded-empty different query boundary");
            return token;
        }
        /** 按通知原 loadIndex 计完整页，替换候选和增量均不冒充本次初始交付。 */
        static int initialPageCount(int index){int n=0;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad
                &&((Integer)e.args[11])==index&&!metadata(e).replaceLatest)n++;return n;}
        /** 最新成功的第一次交付保留原模式/参数及实际接收守卫，不要求下载中间历史。 */
        static Event initialAssertPage(){
            Event result=null;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&((Integer)e.args[11])==1)result=e;
            check(result!=null&&initialPageCount(1)==1&&rows(result).equals("Z")&&((Integer)result.args[8])==2
                    &&((Integer)result.args[14])==0&&!((Boolean)result.args[9])&&isHistoryPageCurrent(metadata(result)),
                    "latest body already merged but initial full page still waits for older query");
            for(Event e:events)if(e.type==NotificationCenter.didReceiveNewMessages)
                check(!rows(e).contains("Z"),"same latest body also delivered as duplicate watch increment");
            return result;
        }
        /** latest 先到时旧 RPC 尚未开始；首屏立即发出，旧 ticket 失效，之后主动上翻仍取真实新游标。 */
        static void initialLatestBeforeQueuedOlder()throws Exception{
            initialPrepare();
            try{
                transcriptQueue.next();initialOwners(true);initialAssertPage();
                check(current.requests.equals(java.util.Collections.singletonList("latest"))&&saves==1
                        &&store.read("synthetic-remote").findSource("Z",null)!=null,
                        "initial latest was not merged and saved exactly once before older starts");
                pump();check(initialPageCount(1)==1&&current.requests.equals(java.util.Collections.singletonList("latest")),
                        "queued obsolete older query still ran or repeated initial page");
                TranscriptWindow root=histories.get(42L);int anchor=root.findSource("Z",null).id;
                loadMessages(0,42,1,anchor,7,0,2,0);pump();
                check(initialPageCount(1)==1&&initialPageCount(2)==1&&rows(normal()).equals("Y")
                        &&current.requests.equals(java.util.Arrays.asList("latest","older:latest-older"))
                        &&store.read("synthetic-remote").findSource("Y",null)!=null,
                        "initial handoff broke later explicit upward pagination or persisted old history");
                System.out.println("PASS latest immediately completes initial page; obsolete queued older skipped; later upward page retained");
            }finally{cleanup();}
        }
        /** 只在真实 RPC 的外部响应读取边界阻塞，生产请求认领/合入/保存均不替换。 */
        static void initialLatestBeforeStartedOlder()throws Exception{
            initialPrepare();
            java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            final Throwable[] workerFailure={null};
            java.util.ArrayDeque<Object> response=new java.util.ArrayDeque<Object>(){
                @Override public Object remove(){started.countDown();try{
                    check(release.await(5,java.util.concurrent.TimeUnit.SECONDS),"older response gate timed out");
                }catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}return super.remove();}
            };
            response.add(page("old-response",false,null,null));current.older.put("older",response);
            Thread worker=new Thread(()->{try{historyQueue.next();}catch(Throwable error){workerFailure[0]=error;}},"synthetic-older-rpc");
            try{
                worker.start();check(started.await(5,java.util.concurrent.TimeUnit.SECONDS),"older RPC did not start");
                transcriptQueue.next();initialOwners(true);initialAssertPage();
                check(current.requests.equals(java.util.Arrays.asList("older:older","latest"))&&saves==1,
                        "started older still blocks latest first page");
                release.countDown();worker.join(5000);check(!worker.isAlive()&&workerFailure[0]==null,"older external boundary failed");
                pump();
                check(initialPageCount(1)==1&&ids(histories.get(42L)).equals("Z")&&saves==1
                        &&store.read("synthetic-remote").findSource("old-response",null)==null,
                        "late started older repeated page or overwrote latest model");
                System.out.println("PASS started older RPC does not block latest page; late response cannot repeat or overwrite");
            }finally{release.countDown();worker.join(5000);cleanup();}
        }
        /** UI runnable 尚未执行和通知中心已记录但尚未消费，都须被同段新定位的 ticket 失效。 */
        static void initialNewLoadCancelsDelayedPage()throws Exception{
            initialPrepare();
            try{
                loadMessages(0,42,1,0,7,2,2,0);initialOwners(true);
                transcriptQueue.next();initialOwners(true);
                check(initialPageCount(1)==0&&initialPageCount(2)==1&&rows(normal()).equals("Z"),
                        "new initial query did not own first latest page");
                pump();
                check(initialPageCount(1)==0&&initialPageCount(2)==1
                        &&current.requests.equals(java.util.Collections.singletonList("latest")),
                        "new load before latest still allowed superseded initial load to repeat page or request older");
            }finally{cleanup();}
            for(boolean posted:new boolean[]{false,true}){
                initialPrepare();
                try{
                    MessageObject original=null;
                    if(!posted){
                        outbox.put(new OutboxStore.Item("early-echo","synthetic-remote","synthetic",-7,1));
                        outbox.put(new OutboxStore.Item("still-unknown","synthetic-remote","synthetic",-8,1));
                        outbox.markSubmissionUncertain("still-unknown",true);
                        original=new MessageObject(0,pendingMessage(42,-7,1,"synthetic","early-echo",0),true,false);
                        pendingMessages.put("early-echo",original);
                        current.latest=page("",true,"latest-older","new-tail");
                        current.latest.getAsJsonArray("items").add(item("Z","early-echo"));
                    }
                    transcriptQueue.next();initialOwners(posted);
                    Event old=posted?initialAssertPage():null;
                    int anchor=histories.get(42L).findSource("Z",null).id;
                    loadMessages(0,42,1,anchor,7,3,2,0);
                    if(posted)check(!isHistoryPageCurrent(metadata(old)),"deferred old first page survived explicit new load intent");
                    else{
                        // saveHistory仍先持久化真实回显再清Outbox；取消的是旧UI确认/恢复资格。
                        ui();check(initialPageCount(1)==0&&events.isEmpty()&&outbox.get("early-echo")==null
                                &&outbox.get("still-unknown").submissionUncertain&&pendingMessages.get("early-echo")==original
                                &&store.read("synthetic-remote").allOutgoingLocalIds().contains("early-echo"),
                                "cancelled old first-page UI confirmed or restored pending before new load");
                    }
                    initialOwners(true);pump();
                    check(initialPageCount(1)==(posted?1:0)&&initialPageCount(2)==1&&rows(normal()).equals("Z")
                            &&((Integer)normal().args[8])==3&&isHistoryPageCurrent(metadata(normal())),
                            "new exact anchor page was lost or old initial page repeated after UI delay");
                }finally{cleanup();}
            }
            System.out.println("PASS new load cancels prior waiting initial, pending UI and deferred metadata; exact anchor still delivers");
        }
        /** 分别在 latest 回包合入前暂停、换账号和换连接；原首屏资格不能由迟到回包续期。 */
        static void initialLifecycleCancels()throws Exception{
            for(String change:new String[]{"pause","account","connection"}){
                long token=initialPrepare();
                try{
                    transcriptQueue.next();
                    if(change.equals("pause"))pauseHistoryView(7,token);
                    else if(change.equals("account"))accountGeneration++;
                    else{current=new DesktopConnection();desktopConnections.put(current.machineId,current);}
                    initialOwners(true);
                    check(initialPageCount(1)==0,"late latest completed cancelled initial page: "+change);
                    pump();
                    // 换连接后的原向旧请求仍允许原空错误出口；它不能交付失效 latest 或旧正文。
                    if(change.equals("connection")){
                        check(initialPageCount(1)==1&&rows(normal()).isEmpty(),"wrong connection changed original empty failure page");
                    }else check(initialPageCount(1)==0,"cancelled initial request revived after old queue resumed: "+change);
                    check(change.equals("pause")?saves==1&&ids(histories.get(42L)).equals("Z")
                            :saves==0&&ids(histories.get(42L)).isEmpty(),
                            "lifecycle guard changed legitimate watch save or accepted wrong-source data: "+change);
                    if(change.equals("pause")){
                        long reopened=openHistoryView(0,42,7);check(reopened!=token,"paused view reused original token");
                        loadMessages(0,42,1,0,7,2,2,0);pump();
                        check(initialPageCount(1)==0&&initialPageCount(2)==1&&rows(normal()).equals("Z"),
                                "reopened view cannot read current latest or old callback revived");
                    }
                }finally{cleanup();}
            }
            System.out.println("PASS pause/reopen, account and connection changes reject original first-page callback");
        }
        """;
}
