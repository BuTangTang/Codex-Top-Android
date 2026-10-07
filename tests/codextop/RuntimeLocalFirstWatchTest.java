package com.butang.codextop;

import java.nio.file.Path;

/** 用真实Runtime/Window/Store与Chat收页入口保护本地优先重开；不读正式账号或验证Android像素。 */
public final class RuntimeLocalFirstWatchTest {
    /** 默认执行11个有界场景；参数一可指定Chat前像，参数二可单跑small取得同一断言RED。 */
    public static void main(String[] args) throws Exception {
        String chat=args.length>0?args[0]:Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").toString();
        String selected=args.length>1?args[1]:"all";
        if(!java.util.Set.of("all","small","page-limit","empty-truncated","discontinuity","missing-tail","unavailable","stop-late","tail-race","budget","discontinuity-budget","empty-complete").contains(selected))
            throw new IllegalArgumentException("未知本地优先场景");
        HistoryLocalArchiveTest.main(new String[]{chat,"String selected=\""+selected+"\";"+CASES});
    }

    // 复用既有真实owner提取器，只配置合成网络响应与队列边界；不复制恢复算法。
    private static final String CASES="""
    for(String scenario:new String[]{"small","page-limit","empty-truncated","discontinuity","missing-tail","unavailable","stop-late","tail-race","budget","discontinuity-budget"}){
     if(!selected.equals("all")&&!selected.equals(scenario))continue;

 StringJoiner initial=new StringJoiner(",");for(int i=0;i<2292;i++)initial.add("S"+i);
 TranscriptWindow old=cache(initial.toString());reset(old);
 try {
  Ui t=new Ui();t.firstLoadMessages();pump();consumeSnapshot(t);pump();
  check(t.messages.size()==30 && historyViews.get(7).count==30,"initial cache/count not 30");
  check(current.requests.isEmpty(),"cached first load issued network");
  t.requestCodexLatestHistory();pump();consumeSnapshot(t);pump();
  check(t.messages.size()==30 && histories.get(42L)==old && current.requests.isEmpty(),"local return did not preserve 30 without RPC");
  current.latest=page("NEW",true,"new-older","new-tail");
  JsonObject delta=page("NEW",false,"new-tail",null);delta.addProperty("truncated",false);current.after.add(delta);
  if(scenario.equals("page-limit")||scenario.equals("empty-truncated")){current.after.clear();JsonObject partial=page(scenario.equals("page-limit")?"INTERMEDIATE":"",false,"partial-tail",null);partial.addProperty("truncated",true);partial.addProperty("truncationReason","page_limit");current.after.add(partial);}
  if(scenario.equals("discontinuity")){current.after.clear();JsonObject broken=page("",false,"replacement-tail",null);broken.addProperty("truncated",true);broken.addProperty("truncationReason","source_discontinuity");current.after.add(broken);}
  if(scenario.equals("unavailable")){current.after.clear();JsonObject unavailable=page("",false,"old-tail",null);unavailable.addProperty("truncated",false);unavailable.addProperty("historyAvailability","unavailable");current.after.add(unavailable);}
  if(scenario.equals("missing-tail"))old.tailCursor=null;
  if(scenario.equals("budget")||scenario.equals("discontinuity-budget")){
   current.after.clear();JsonObject partial=page("",false,"partial-tail",null);partial.addProperty("truncated",true);partial.addProperty("truncationReason",scenario.equals("budget")?"page_limit":"source_discontinuity");current.after.add(partial);
   current.latest=page("",true,"empty-1","new-tail");current.older("empty-1",page("",true,"empty-2",null));current.older("empty-2",page("",true,"empty-3",null));current.older("empty-3",page("OLD",true,"empty-4",null));
  }
  events.clear();watchConversation(0,42);
  if(scenario.equals("stop-late")){Utilities.globalQueue.next();transcriptQueue.next();stopWatching(42);}
  if(scenario.equals("tail-race")){Utilities.globalQueue.next();transcriptQueue.next();JsonObject concurrent=page("CONCURRENT",false,"concurrent-tail",null);concurrent.addProperty("truncated",false);old.append(concurrent);}
  pump();
  boolean emittedNew=false,emittedPartial=false;for(Event event:events)if(event.type==NotificationCenter.didReceiveNewMessages){String values=rows(event);emittedNew|=values.equals("NEW");emittedPartial|=values.contains("INTERMEDIATE");}

  int replacementRows=-1;for(Event event:events)if(event.type==NotificationCenter.messagesDidLoad && metadata(event).replaceLatest)replacementRows=((ArrayList<?>)event.args[2]).size();
  consumeSnapshot(t);pump();
  TranscriptWindow root=histories.get(42L);int total=root.before(0,Integer.MAX_VALUE).size();
  boolean oldRetained=root.findSegment("S0",null)!=null && root.findSegment("S2291",null)!=null;
  System.out.println("RESULT localRows=30 localRequests=0 watchRequests="+current.requests+" replacementRows="+replacementRows+" displayRows="+t.messages.size()+" currentRows="+total+" sameRoot="+(root==old)+" oldRetained="+oldRetained);
  check(oldRetained,"old cache identity lost");
  if(scenario.equals("small")){
   check(current.requests.equals(Collections.singletonList("after:old-tail"))&&emittedNew,"small delta did not deliver exact new-message notification");
  }
  if(scenario.equals("page-limit")||scenario.equals("empty-truncated")||scenario.equals("discontinuity")||scenario.equals("unavailable")){
   check(current.requests.equals(Arrays.asList("after:old-tail","latest")),"opening did not immediately fall back after one tail page");
   check(root!=old&&total==1&&t.messages.size()==1&&!emittedPartial,"fallback changed latest delivery or leaked partial tail");
   check(old.before(0,Integer.MAX_VALUE).size()==2292,"fallback appended incomplete tail into old segment");
   check(scenario.equals("discontinuity")?old.tailCursor==null:"old-tail".equals(old.tailCursor),"fallback changed retained cursor outside explicit discontinuity");
  }
  if(scenario.equals("budget")||scenario.equals("discontinuity-budget"))check(current.requests.equals(Arrays.asList("after:old-tail","latest","older:empty-1","older:empty-2")),"opening probe enlarged original four immediate RPC budget");
  if(scenario.equals("missing-tail"))check(current.requests.equals(Collections.singletonList("latest"))&&total==1,"missing tail was probed instead of bootstrapped");
  if(scenario.equals("stop-late")||scenario.equals("tail-race"))check(current.requests.equals(Collections.singletonList("after:old-tail"))&&root==old&&replacementRows==-1&&t.messages.size()==30,"late tail altered current view or triggered immediate fallback");


 if(scenario.equals("small")){
  check(root==old && total==2293 && replacementRows==-1 && t.messages.size()==30,"opening watch replaced 2292-row local current with short latest");
  t.requestCodexLatestHistory();pump();consumeSnapshot(t);pump();
  check(t.messages.size()==30 && "NEW".equals(t.messages.get(0).messageOwner.params.get("codexSourceId")),"return latest did not expose cached new message alongside retained local rows");
 }
 System.out.println("PASS local-first "+scenario);
 }finally{cleanup();}
 }
if(selected.equals("all")||selected.equals("empty-complete")){

 TranscriptWindow root=new TranscriptWindow();root.prepend(page("",false,null,"empty-tail"));reset(root);
 try{Ui t=new Ui();t.firstLoadMessages();pump();consumeSnapshot(t);pump();check(t.messages.isEmpty()&&current.requests.isEmpty(),"empty complete cache setup");
 current.latest=page("NEW",false,null,"new-tail");watchConversation(0,42);pump();consumeSnapshot(t);pump();
 check(current.requests.equals(Collections.singletonList("latest"))&&t.messages.size()==1,"empty complete root must retain original latest/first-visible route");System.out.println("PASS empty complete root original latest and first visible");}finally{cleanup();}
  }
    """;
}
