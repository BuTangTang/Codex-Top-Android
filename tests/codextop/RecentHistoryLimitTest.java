package com.butang.codextop;
import java.nio.file.Path;
/** 真实首载、收页、布局和原Runtime分页链验证最近30条；只替代Android事件和合成网络边界。 */
public final class RecentHistoryLimitTest {
 /** 可指定Chat前像与单个场景，默认覆盖30条预算、阅读意图、增量和冷空页。 */
 public static void main(String[] args)throws Exception {
  String chat=args.length>0?args[0]:Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").toString();
  String selected=args.length>1?args[1]:"all";
  HistoryLocalArchiveTest.main(new String[]{chat,"String selected=\""+selected+"\";"+CASES});
 }
 private static final String CASES="""
 for(String scenario:new String[]{"first30","enough-gap","deficit","long29","date-pending","queued-increment","four-empty","failure","manual-return","reading-lock","pause","search","selected","cold-empty","same-view-new-epoch","telegram","around","date","queued-one-increment"}) {
  if(!selected.equals("all")&&!selected.equals(scenario))continue;
  int initial=scenario.equals("first30")||scenario.equals("manual-return")?100:scenario.equals("enough-gap")?30:scenario.equals("long29")?29:3;
  if(Arrays.asList("telegram","around","date").contains(scenario))initial=100;
  StringJoiner values=new StringJoiner(",");for(int i=0;i<initial;i++)values.add("S"+i);
  TranscriptWindow history=cache(values.toString());
  if(scenario.equals("enough-gap"))history=cache("ARCH").acceptLatest(page(values.toString(),true,"old-older","old-tail"));
  if(scenario.equals("cold-empty"))history=new TranscriptWindow();
  reset(history);
  try {
   if(scenario.equals("cold-empty")){current.latest=page("",true,"c1","t1");current.older("c1",page("",true,"c2",null));current.older("c2",page("BODY",true,"c3",null));}
   Ui t=new Ui();if(scenario.equals("enough-gap"))t.initialMessagesSize=30;
   if(scenario.equals("telegram"))remoteIds.clear();
   if(scenario.equals("around"))t.startLoadFromMessageId=history.before(0,50).get(20).id;
   if(scenario.equals("date"))t.startLoadFromDate=1;
   if(scenario.equals("telegram")){
    try{t.firstLoadMessages();throw new AssertionError("Telegram boundary not reached");}catch(AssertionError expected){check("unexpected Telegram network".equals(expected.getMessage()),"wrong Telegram boundary error");}
    check(t.controller.calls.size()==1&&(Integer)t.controller.calls.get(0)[1]==15&&(Integer)t.controller.calls.get(0)[4]==2,"Telegram initial count changed");System.out.println("PASS "+scenario);continue;
   }
   t.firstLoadMessages();pump();consumeSnapshot(t);pump();
   if(scenario.equals("around")||scenario.equals("date")){
    Object[] call=t.controller.calls.get(0);check((Integer)call[1]==(scenario.equals("around")?15:30)&&(Integer)call[4]==(scenario.equals("around")?3:4)&&t.codexHistoryReadingLocked,"anchored first load changed original count or intent");check(current.requests.isEmpty(),"cached anchored load performed network");System.out.println("PASS "+scenario);continue;
   }
   if(scenario.equals("first30")){check(t.messages.size()==30&&historyViews.get(7).count==30,"default latest must show 30 source rows");check(current.requests.isEmpty(),"cached first page performed RPC");System.out.println("PASS "+scenario);continue;}
   if(scenario.equals("cold-empty")){check(t.messages.size()==1&&current.requests.equals(Arrays.asList("latest","older:c1","older:c2")),"cold empty must retain bounded search for first body");System.out.println("PASS "+scenario);continue;}
   if(scenario.equals("manual-return")){
    int before=t.controller.calls.size();t.dragHistory();pump();consumeSnapshot(t);pump();
    check(t.codexHistoryReadingLocked&&t.controller.calls.size()>before,"real drag did not retain reading lock and original load");
    Object[] call=t.controller.calls.get(before);check((Integer)call[1]==50&&(Integer)call[4]==0,"manual older load no longer 50");
    check(historyViews.get(7).count==50,"real older view count was not 50");
    t.requestCodexLatestHistory();pump();consumeSnapshot(t);pump();
    check(t.messages.size()==30&&!t.codexHistoryReadingLocked,"return latest inherited older count50 instead of 30");check(history.before(0,Integer.MAX_VALUE).size()==100,"history cache trimmed");
    System.out.println("PASS "+scenario);continue;
   }
   if(scenario.equals("enough-gap")){
    int before=t.controller.calls.size();draw(t);pump();consumeSnapshot(t);pump();
    check(t.messages.size()==30&&t.controller.calls.size()==before&&current.requests.isEmpty(),"30 full rows plus archive must not auto-load older");
    System.out.println("PASS "+scenario);continue;
   }
   StringJoiner older=new StringJoiner(",");for(int i=0;i<100;i++)older.add("O"+i);
   current.older("old-older",page(older.toString(),true,"next-older",null));
   if(scenario.equals("long29"))t.chatListView.scrollUp=true;
   if(scenario.equals("date-pending")){
    TLRPC.TL_message date=new TLRPC.TL_message();date.id=0;t.messages.add(new MessageObject(0,date,false,false));
    TLRPC.TL_message pending=new TLRPC.TL_message();pending.id=-7;pending.params=new HashMap<>();pending.params.put("codexSourceId","pending");t.messages.add(new MessageObject(0,pending,false,false));
   }
   if(scenario.equals("queued-increment")){
    JsonObject incoming=page(older.toString(),false,"tail2",null);incoming.addProperty("truncated",false);history.append(incoming);
    for(TranscriptWindow.Entry e:history.before(0,27))t.messages.add(new MessageObject(0,historyMessage(42,e),false,false));
   }
   if(scenario.equals("queued-one-increment")){
    JsonObject incoming=page("INC",false,"tail2",null);incoming.addProperty("truncated",false);history.append(incoming);t.messages.add(new MessageObject(0,historyMessage(42,history.before(0,1).get(0)),false,false));
   }
   if(scenario.equals("four-empty")){current.older("old-older",page("",true,"e1",null));current.older("e1",page("",true,"e2",null));current.older("e2",page("",true,"e3",null));current.older("e3",page("",true,"e4",null));}
   if(scenario.equals("failure"))current.older.clear();
   if(scenario.equals("reading-lock"))t.lockCodexHistoryReading();
   if(scenario.equals("pause"))t.pauseCodexHistoryView(false);
   if(scenario.equals("search"))t.expandSearchUi();
   if(scenario.equals("selected"))t.selected=true;
   int before=t.controller.calls.size();draw(t);pump();consumeSnapshot(t);pump();
   if(Arrays.asList("reading-lock","pause","search","selected","queued-increment").contains(scenario)){
    check(current.requests.isEmpty()&&t.controller.calls.size()==before,"queued fill ignored intent/current row guard "+scenario);
   } else if(scenario.equals("four-empty")){
    check(current.requests.size()==4&&t.messages.size()==3,"one bounded fill did not stop after four advancing empty pages");
   } else if(scenario.equals("failure")){
    check(current.requests.size()==1&&!t.loading&&t.codexHistoryOlderRetryRequired,"failed auto fill did not stop with original retry fact");
   } else {
    int expected=scenario.equals("long29")?1:scenario.equals("queued-one-increment")?26:27;
    check(t.controller.calls.size()==before+1&&(Integer)t.controller.calls.get(before)[1]==expected,"fill count must be fresh deficit "+scenario);
    check(t.messages.size()==(scenario.equals("date-pending")?32:30),"automatic fill must stop at 30 real source rows "+scenario);
    check(current.requests.size()==1&&history.before(0,Integer.MAX_VALUE).size()==initial+100+(scenario.equals("queued-one-increment")?1:0),"network page/cache projection changed "+scenario);
   }
   int requests=current.requests.size(),calls=t.controller.calls.size();
   for(int i=0;i<5;i++){t.originalLayoutComplete();draw(t);pump();consumeSnapshot(t);pump();}
   check(current.requests.size()==requests&&t.controller.calls.size()==calls,"layout reopened spent automatic budget "+scenario);
   if(scenario.equals("same-view-new-epoch")){
    TranscriptWindow next=history.acceptLatest(page("LATEST",true,"fresh-cursor","fresh-tail"));publishHistoryRoot(42,next);offerLatestHistory(42,next);pump();consumeSnapshot(t);pump();draw(t);pump();
    check(current.requests.size()==requests,"archive/new epoch reopened same-view auto older budget");
   }
   System.out.println("PASS "+scenario);
  }finally{cleanup();}
 }
 """;
}
