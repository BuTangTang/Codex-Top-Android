package com.butang.codextop;

import java.nio.file.Path;

/** 用 D44 最新段契约复测实际 watch/stop，复用唯一源码夹具和真实 Store。 */
public final class RuntimeTailRecoveryTest {
    /** 可指定隔离 Runtime；已移除被 D44 取代的“跨旧页桥接才显示”期望。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        RuntimeLatestSegmentTest.runScenarios(runtime, SCENARIOS, "tailScenarios();");
    }

    // 只定义合成输入、排程暂停点和断言；恢复/保存/发送去重不再有第二套夹具。
    private static final String SCENARIOS = """
            /** 每轮执行原准备、网络、合并和UI四个边界，不提前消耗后续追页。 */
            static void tailCycle(){Utilities.globalQueue.wake();Utilities.globalQueue.next();transcriptQueue.next();Utilities.globalQueue.next();ui();}
            /** 明确warm积压后转latest；后续各例只检验原稳定tail生命周期。 */
            static TranscriptWindow tailReady()throws Exception{
                TranscriptWindow h=cache("A,B");reset(h);openCached();current.latest=page("A,B",false,null,"live-tail");
                openingBacklog();watchConversation(0,42);pump();check(current.requests.equals(Arrays.asList("after:old-tail","latest")),"积压未转latest");
                check(histories.get(42L)==h&&historyViews.get(7).window==h&&"live-tail".equals(h.tailCursor),"连续首屏改owner或显示段");
                current.requests.clear();events.clear();saves=previews=failures=0;Utilities.globalQueue.delays.clear();return h;
            }
            /** 一页探测确认积压后latest仍保持连续去重、原编号与旧页游标。 */
            static void tailContinuousOpening()throws Exception{
                TranscriptWindow h=cache("A,B");int a=h.findSource("A",null).id,b=h.findSource("B",null).id;reset(h);
                try{openCached();current.latest=page("A,B,C",false,null,"fresh-tail");openingBacklog();watchConversation(0,42);pump();
                    check(current.requests.equals(Arrays.asList("after:old-tail","latest"))&&histories.get(42L)==h&&ids(h).equals("C,B,A"),"已有tail首轮没latest或未连续去重");
                    check(h.findSource("A",null).id==a&&h.findSource("B",null).id==b&&"old-older".equals(h.cursor)&&"fresh-tail".equals(h.tailCursor),"连续latest改稳定编号/旧游标");
                    int incoming=0;for(Event e:events)if(e.type==NotificationCenter.didReceiveNewMessages){check(rows(e).equals("C")&&isHistoryPageCurrent((HistoryPage)e.args[4]),"连续增量不是只有C");incoming++;}
                    check(incoming==1&&saves==1&&failures==0&&store.read("synthetic-remote").snapshot().equals(h.snapshot()),"连续首屏未真实保存/错误发布");
                    System.out.println("PASS existing tail opens latest and retains contiguous IDs");
                }finally{cleanup();}
            }
            /** 最新请求暂时失败只沿原五秒重试，不追旧tail或中间页。 */
            static void tailLatestRetry()throws Exception{
                TranscriptWindow h=cache("A,B");JsonObject before=h.snapshot();reset(h);
                try{openCached();current.latest=null;openingBacklog();watchConversation(0,42);pump();
                    check(current.requests.equals(Arrays.asList("after:old-tail","latest"))&&before.equals(h.snapshot())&&events.isEmpty()&&saves==0&&failures==1&&Utilities.globalQueue.lastDelay()==5000,"初次失败污染旧段或改变原等待");
                    current.latest=page("Y,Z",true,"fresh-older","fresh-tail");tailCycle();
                    check(current.requests.equals(Arrays.asList("after:old-tail","latest","latest"))&&rows(replacement()).equals("Z,Y")&&historyViews.get(7).window==h,"重试沿旧tail或候选提前换view");
                    check(histories.get(42L).containsSegment(h)&&store.read("synthetic-remote").findSegment("A",null)!=null,"重试丢旧缓存");
                    System.out.println("PASS latest request retries original delay without old gap download");
                }finally{cleanup();}
            }
            /** 停在实际网络与合并之间，原连接/缓存/代次/游标/账户守卫拒绝迟到首屏。 */
            static void tailLateLatest(String kind)throws Exception{
                TranscriptWindow h=cache("A,B");reset(h);
                try{openCached();saves=previews=failures=0;current.latest=page("Y,Z",true,"fresh-older","fresh-tail");DesktopConnection requested=current;openingBacklog();watchConversation(0,42);Utilities.globalQueue.next();transcriptQueue.next();Utilities.globalQueue.next();Utilities.globalQueue.next();transcriptQueue.next();
                    if(kind.equals("connection"))current=new DesktopConnection();else if(kind.equals("history"))histories.put(42L,cache("later"));
                    else if(kind.equals("generation"))watchGeneration++;else if(kind.equals("tail"))h.tailCursor="later-tail";
                    else if(kind.equals("older"))h.cursor="later-older";else if(kind.equals("account"))accountGeneration++;else if(kind.equals("stop"))stopWatching(42);else throw new AssertionError(kind);
                    JsonObject expected=h.snapshot(),root=histories.get(42L).snapshot();Utilities.globalQueue.next();ui();
                    check(expected.equals(h.snapshot())&&root.equals(histories.get(42L).snapshot())&&events.isEmpty()&&saves==0&&previews==0&&failures==0&&requested.requests.equals(Arrays.asList("after:old-tail","latest")),"迟到latest串用/发布："+kind+" oldSame="+expected.equals(h.snapshot())+" rootSame="+root.equals(histories.get(42L).snapshot())+" events="+events.size()+" saves="+saves+" previews="+previews+" failures="+failures+" requests="+requested.requests);
                    System.out.println("PASS late latest guard "+kind);
                }finally{cleanup();}
            }
            /** 已拒绝响应也必须经过原身份守卫，不能清后来者的tail或保存旧窗口。 */
            static void tailLateRejected(String kind)throws Exception{
                TranscriptWindow h=tailReady();
                try{JsonObject rejected=page("untrusted",false,null,null);rejected.addProperty("truncationReason","source_discontinuity");current.after.add(rejected);
                    Utilities.globalQueue.wake();Utilities.globalQueue.next();transcriptQueue.next();
                    if(kind.equals("connection"))current=new DesktopConnection();else if(kind.equals("history"))histories.put(42L,cache("later"));
                    else if(kind.equals("generation"))watchGeneration++;else if(kind.equals("tail"))h.tailCursor="later-tail";
                    else if(kind.equals("account"))accountGeneration++;else if(kind.equals("stop"))stopWatching(42);else throw new AssertionError(kind);
                    JsonObject expected=h.snapshot(),root=histories.get(42L).snapshot();Utilities.globalQueue.next();ui();
                    check(expected.equals(h.snapshot())&&root.equals(histories.get(42L).snapshot())&&events.isEmpty()&&saves==0&&previews==0&&failures==0,"迟到拒绝响应清tail/污染："+kind);
                    System.out.println("PASS late rejected tail guard "+kind);
                }finally{cleanup();}
            }
            /** stop即时清理消费完后，原已返回callback仍须不合并、不发布、不续请求。 */
            static void tailStoppedCallback()throws Exception{
                TranscriptWindow h=tailReady();JsonObject before=h.snapshot();
                try{DesktopConnection requested=current;current.after.add(page("N",false,"next-tail",null));Utilities.globalQueue.wake();Utilities.globalQueue.next();transcriptQueue.next();stopWatching(42);
                    Utilities.globalQueue.next();ui();pump();check(before.equals(h.snapshot())&&events.isEmpty()&&saves==0&&Utilities.globalQueue.ready.isEmpty()&&Utilities.globalQueue.delayed.isEmpty()&&requested.requests.equals(Collections.singletonList("after:live-tail")),"stop回包仍改写/续请求");
                    statusQueue.next();statusQueue.wake();statusQueue.next();check(statusPoll==null&&observations==1,"原stop释放生命周期丢失");
                    System.out.println("PASS stop rejects in-flight callback and releases original lease");
                }finally{cleanup();}
            }
            /** 先消费stop即时清理，再等原已安排唤醒结束，不新建请求或轮询器。 */
            static void tailStoppedWake()throws Exception{
                TranscriptWindow h=tailReady();JsonObject before=h.snapshot();
                try{stopWatching(42);pump();Utilities.globalQueue.wake();Utilities.globalQueue.next();
                    check(before.equals(h.snapshot())&&current.requests.isEmpty()&&transcriptQueue.ready.isEmpty()&&Utilities.globalQueue.ready.isEmpty()&&Utilities.globalQueue.delayed.isEmpty()&&events.isEmpty(),"stop后的原唤醒继续请求");
                    System.out.println("PASS stop ends scheduled original wake");
                }finally{cleanup();}
            }
            /** 原延迟释放不能释放随后重开的watch租约。 */
            static void tailStoppedLeaseReopen()throws Exception{
                tailReady();try{stopWatching(42);watchConversation(0,42);statusQueue.next();statusQueue.wake();statusQueue.next();check(observations==0&&watchedDialog==42,"旧stop释放新watch租约");System.out.println("PASS stopped lease cannot release reopened watch");}finally{cleanup();}
            }
            /** 网络或非字符串拒绝理由不提供失效事实，保留同一tail和原重试间隔。 */
            static void tailUnconfirmedFailure(String kind)throws Exception{
                TranscriptWindow h=tailReady();JsonObject before=h.snapshot();
                try{JsonObject bad=page("untrusted",false,"untrusted-next",null);Object response;
                    if(kind.equals("network"))response=new IOException("synthetic-network");else{
                        if(kind.equals("metadata"))bad.remove("items");else if(kind.equals("reason-object"))bad.add("truncationReason",new JsonObject());
                        else if(kind.equals("reason-array")){JsonArray reason=new JsonArray();reason.add("source_discontinuity");bad.add("truncationReason",reason);}else throw new AssertionError(kind);response=bad;}
                    current.after.add(response);current.after.add(response);tailCycle();tailCycle();
                    check(current.requests.equals(Arrays.asList("after:live-tail","after:live-tail"))&&before.equals(h.snapshot())&&events.isEmpty()&&saves==0&&previews==0&&failures==2&&Utilities.globalQueue.lastDelay()==5000,"普通失败清tail/改窗口/等待："+kind);
                    System.out.println("PASS unconfirmed tail failure "+kind);
                }finally{cleanup();}
            }
            /** page_limit仍是真实连续增量，四页预算和原即时/两秒调度保持。 */
            static void tailPageLimitBudget()throws Exception{
                TranscriptWindow h=tailReady();
                try{for(int n=0;n<4;n++){JsonObject limited=page(String.valueOf((char)('C'+n)),false,"tail-"+n,null);limited.addProperty("truncated",true);limited.addProperty("truncationReason","page_limit");current.after.add(limited);tailCycle();}
                    check(current.requests.equals(Arrays.asList("after:live-tail","after:tail-0","after:tail-1","after:tail-2"))&&Utilities.globalQueue.delays.equals(Arrays.asList(0L,0L,0L,2000L)),"page_limit四页预算/调度变更");
                    check(ids(h).equals("F,E,D,C,B,A")&&"tail-3".equals(h.tailCursor)&&saves==4&&failures==0&&store.read("synthetic-remote").snapshot().equals(h.snapshot()),"page_limit正文/保存/实际tail错误");
                    System.out.println("PASS contiguous page limit retains four page yield budget");
                }finally{cleanup();}
            }
            /** 真实Store保存拒绝事实；写失败不损坏旧文件，之后仍从latest恢复离散段。 */
            static void tailRejectedPersistence(boolean failWrite)throws Exception{
                TranscriptWindow h=tailReady();JsonObject original=h.snapshot();TranscriptStore persisted=store;
                try{if(failWrite)store=new TranscriptStore(directory.resolve("history").toFile(),"synthetic-server","synthetic-account","synthetic-machine",1);
                    JsonObject rejected=page("untrusted",false,null,null);rejected.addProperty("truncationReason","source_discontinuity");current.after.add(rejected);tailCycle();
                    JsonObject invalidated=original.deepCopy();invalidated.add("tailCursor",JsonNull.INSTANCE);
                    check(h.snapshot().equals(invalidated)&&events.isEmpty()&&saves==1&&previews==0&&failures==1&&Utilities.globalQueue.lastDelay()==5000,"拒绝事实污染正文/编号或调度");
                    TranscriptWindow restored=persisted.read("synthetic-remote");check(restored.snapshot().equals(failWrite?original:invalidated),"真实拒绝保存失败破坏旧文件");
                    store=persisted;events.clear();current.requests.clear();current.latest=page("Y,Z",true,"fresh-older","fresh-tail");
                    if(failWrite)tailCycle();else{stopWatching(42);historyViews.clear();histories.put(42L,restored);openHistoryView(0,42,7);watchConversation(0,42);pump();}
                    TranscriptWindow root=histories.get(42L);
                    check(current.requests.equals(Collections.singletonList("latest"))&&rows(replacement()).equals("Z,Y")&&root.findSegment("A",null)!=null&&"fresh-tail".equals(root.tailCursor),"重开/写失败恢复仍追坏tail或丢旧段");
                    TranscriptWindow saved=persisted.read("synthetic-remote");check(ids(saved).equals("Z,Y")&&saved.findSegment("A",null)!=null&&"fresh-tail".equals(saved.tailCursor),"恢复最新段未真实保存");
                    System.out.println("PASS rejected tail persistence failWrite="+failWrite);
                }finally{cleanup();}
            }
            /** 坏最新页仍整页拒绝，不能为latest-first绕过真实校验。 */
            static void tailBadLatest(String kind)throws Exception{
                TranscriptWindow h=cache("A,B");JsonObject original=h.snapshot();reset(h);
                try{openCached();saves=previews=failures=0;current.latest=page("Y,Z",true,"fresh-older","fresh-tail");
                    if(kind.equals("metadata"))current.latest.remove("items");else current.latest.addProperty("truncationReason","source_discontinuity");
                    openingBacklog();watchConversation(0,42);pump();check(original.equals(h.snapshot())&&events.isEmpty()&&saves==0&&previews==0&&failures==1&&Utilities.globalQueue.lastDelay()==5000&&store.read("synthetic-remote").snapshot().equals(original),"坏latest被展示/部分保存："+kind);
                    System.out.println("PASS bad latest rejected "+kind);
                }finally{cleanup();}
            }
            /** 新D44组保留停止、断线、账户和迟到守卫，旧桥接等待不再作为成功前提。 */
            static void tailScenarios()throws Exception{
                latestOfferAndOldView();followsNewTail();rejectedNewTailRecoversLatest();emptyLatestKeepsView();tailContinuousOpening();tailLatestRetry();
                for(String kind:new String[]{"connection","history","generation","tail","older","account","stop"})tailLateLatest(kind);
                for(String kind:new String[]{"connection","history","generation","tail","account","stop"})tailLateRejected(kind);
                tailStoppedCallback();tailStoppedWake();tailStoppedLeaseReopen();
                for(String kind:new String[]{"network","metadata","reason-object","reason-array"})tailUnconfirmedFailure(kind);
                tailPageLimitBudget();tailRejectedPersistence(false);tailRejectedPersistence(true);tailBadLatest("metadata");tailBadLatest("discontinuity");
                System.out.println("RuntimeTailRecovery: scenarios=31 failures=0; actual D44 watch/stop / Java8; synthetic only");
            }
        """;
}
