package com.butang.codextop;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import java.nio.file.*;

/** 定时消息辅助查询不得修改普通首屏；执行真实UI请求、Controller、Runtime及接收门，网络和布局边界合成。 */
public final class RuntimeScheduledHistoryIsolationTest {
 /** 可传入冻结旧Runtime取得RED；真实UI与Controller始终从当前源码提取。 */
 public static void main(String[] args)throws Exception {
  StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  Path root=Path.of("TMessagesProj/src/main/java");
  var chat=StaticJavaParser.parse(root.resolve("org/telegram/ui/ChatActivity.java")).getClassByName("ChatActivity").orElseThrow();
  var mc=StaticJavaParser.parse(root.resolve("org/telegram/messenger/MessagesController.java")).getClassByName("MessagesController").orElseThrow();
  StringBuilder ui=new StringBuilder(UI);
  for(String n:new String[]{"firstLoadMessages","isCodexHistoryView","openCodexHistoryView","prepareCodexHistoryPage"}) {
   var methods=chat.getMethodsByName(n);if(methods.size()!=1)throw new AssertionError(n);
   ui.append(methods.get(0).toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe")).append('\n');
  }
  var receiver=chat.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
  var scheduled=receiver.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(n->n.getCondition().toString().startsWith("chatMode != mode")).findFirst().orElseThrow();
  var waiting=receiver.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(n->n.getCondition().toString().equals("index == -1")).findFirst().orElseThrow();
  ui.append("@SuppressWarnings(\"unchecked\") void consume(Event e){if(e.type!=NotificationCenter.messagesDidLoad)return; Object[] args=prepareCodexHistoryPage(e.args);if(args==null)return;int guid=(Integer)args[10];if(guid!=classGuid)return;int queryLoadIndex=(Integer)args[11];boolean doNotRemoveLoadIndex=queryLoadIndex<0;if(doNotRemoveLoadIndex)queryLoadIndex=-queryLoadIndex;int index=waitingForLoad.indexOf(queryLoadIndex);int mode=(Integer)args[14];boolean isCache=(Boolean)args[3];ArrayList<MessageObject> messArr=(ArrayList<MessageObject>)args[2];").append(waiting).append(scheduled).append("messages.addAll(messArr); }\n");
  ui.append("}\n").append(CONTROLLER);
  for(var m:mc.getMethodsByName("loadMessages")) {
   if(m.getParameters().size()==16 || m.getParameters().size()==20)
    ui.append(m.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe")).append('\n');
  }
  ui.append("}\n").append(SCENARIOS);
  Path runtime=args.length==0?root.resolve("com/butang/codextop/CodexRuntime.java"):Path.of(args[0]);
  RuntimeLatestSegmentTest.runScenarios(runtime,ui.toString(),"scheduledRun();");
 }
 private static final String UI="""
 static boolean enabled(){return true;} static final class MessagesController {static final int LOAD_AROUND_MESSAGE=3;}
 static final class TextUtils {static boolean isEmpty(String v){return v==null||v.isEmpty();}}
 static final class HashtagSearchController {static HashtagSearchController getInstance(int a){return new HashtagSearchController();} void searchHashtag(Object h,int g,int t,int i){throw new AssertionError("unexpected hashtag");}}
 static final class ForumUtilities {static void filterMessagesByTopic(long id,ArrayList<MessageObject> rows){throw new AssertionError("unexpected topic");}}
 static final class Bar {boolean isActionModeShowed(){return false;}}
 static final class User {long getClientUserId(){return 1;}}
 static final class Ui {
  /** 默认关闭诊断边界；不参与辅助查询语义。 */ void traceCodexHistoryViewport(String p,Object t,boolean e){}
  static final class AndroidUtilities {static boolean isTablet(){return false;}}
  static final int MODE_DEFAULT=0,MODE_SCHEDULED=1,MODE_SEARCH=3,MODE_SAVED=6,MODE_QUICK_REPLIES=9;
  int chatMode=0,currentAccount=0,classGuid=7,initialMessagesSize=15,startLoadFromMessageId,startLoadFromMessageIdSaved,startLoadFromDate,highlightMessageId,searchType,lastLoadIndex=1,replyMaxReadId,clearCalls,scheduledMessagesCount;
  long dialog_id=42,mergeDialogId,migrated_to,threadMessageId,codexHistoryViewToken,codexHistoryPausedToken;
  boolean waitingForReplyMessageLoad,firstMessagesLoaded,loadInfo,historyPreloaded,isTopic,codexHistoryReadingLocked,codexHistoryLatestAvailable;
  String codexHistoryEpoch,searchingHashtag;Bar actionBar;Controller controller=new Controller();
  java.util.ArrayList<Integer> waitingForLoad=new java.util.ArrayList<>();java.util.ArrayList<MessageObject> messages=new java.util.ArrayList<>();
  boolean isThreadChat(){return false;}long getSavedDialogId(){return 0;}User getUserConfig(){return new User();}
  Controller getMessagesController(){return controller;}void finishFragment(){}boolean hasTextSelection(){return false;}
  /** 本地目录不参与定时隔离；只补当前prepare接线所需的默认状态边界。 */
  boolean codexLocalHistoryAwaitingInput;void codexLocalHistoryEpochReset(String epoch){}
  void updateScheduledInterface(boolean b){}void updatePagedownButtonVisibility(boolean b){}void clearCodexHistoryScrollTargets(){}
  /** Android容器边界：记录真实prepare方法何时要求清屏；不声称RecyclerView布局已测。 */
  void clearChatData(boolean b){clearCalls++;messages.clear();}
 """;
 private static final String CONTROLLER="""
 static final class Controller {
  int currentAccount=0;
  void checkSensitive(Object owner,long id,Runnable accepted,Runnable denied){accepted.run();}
  void loadMessagesInternal(Object...args){throw new AssertionError("unexpected Telegram network");}
 """;
 private static final String SCENARIOS="""
 interface ScheduledCase {void run()throws Exception;}
 static ArrayList<String> scheduledFailures=new ArrayList<>();static int scheduledGroups;
 /** 汇总真实行为失败，使旧源码一次显示所有契约缺口。 */
 static void scheduledCase(String name,ScheduledCase body)throws Exception{scheduledGroups++;try{body.run();System.out.println("PASS scheduled "+name);}catch(Exception|AssertionError e){scheduledFailures.add(name+": "+e);System.out.println("FAIL scheduled "+name+": "+e);}}
 /** 每组316条合成缓存，实际保存与窗口编号由正式模型生成。 */
 static TranscriptWindow scheduledCache()throws Exception{StringJoiner ids=new StringJoiner(",");for(int i=1;i<=316;i++)ids.add("m"+i);return cache(ids.toString());}
 /** 获取独立空终态，不将它当成有HistoryPage身份的普通历史。 */
 static Event scheduledEvent(){Event found=null;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==1){check(found==null,"duplicate scheduled terminal");found=e;}check(found!=null,"missing scheduled terminal");return found;}
 /** 原15参数签名透传请求；Codex没有定时正文，必须空终态且不携带普通页身份。 */
 static void scheduledContract(Event e,int count,int guid,int index,int maxId,int loadType){check(e.args.length==15,"scheduled has ordinary page identity");check(e.args[0].equals(42L)&&(Integer)e.args[1]==count&&rows(e).isEmpty(),"scheduled count/dialog/body mismatch");check(!(Boolean)e.args[3]&&(Boolean)e.args[9],"scheduled not empty terminal");for(int i:new int[]{4,5,6,7,13})check((Integer)e.args[i]==0,"nonzero synthetic history metadata");check((Integer)e.args[8]==loadType&&(Integer)e.args[10]==guid&&(Integer)e.args[11]==index&&(Integer)e.args[12]==maxId&&(Integer)e.args[14]==1,"scheduled routing mismatch");}
 /** 实际首屏15/25与同guid定时探测，随后真正watch latest3不能再被压成1。 */
 static void scheduledWarm(int size,boolean probe)throws Exception{
  reset(scheduledCache());try{Ui ui=new Ui();ui.initialMessagesSize=size;
   if(probe)ui.firstLoadMessages();else{ui.openCodexHistoryView();ui.waitingForLoad.add(1);loadMessages(0,42,size,0,7,2,1,0);}
   pump();for(Event e:events)ui.consume(e);
   check(ui.messages.size()==size,"initial normal page lost");check(ui.waitingForLoad.isEmpty(),"initial waiting indices leaked");
   check(historyViews.get(7).count==size,"scheduled changed ordinary count");
   if(probe){scheduledContract(scheduledEvent(),1,7,2,0,2);check(ui.scheduledMessagesCount==0,"ordinary body used as scheduled count");}
   events.clear();openingBacklog();current.latest=page("new1,new2,new3",true,"new-older","new-tail");watchConversation(0,42);pump();
   Event replacement=replacement();check(histories.get(42L).before(0,400).size()==3&&histories.get(42L).findSegment("m1",null)!=null,"latest3/archive316 boundary changed");
   ui.consume(replacement);check(ui.messages.size()==3&&ui.clearCalls==1&&ui.waitingForLoad.isEmpty(),"available latest3 not fully consumed/finished");
  }finally{cleanup();}
 }
 /** 辅助查询先到或独立guid不得新建普通view、排读盘/网络任务。 */
 static void scheduledStandalone(boolean beforeNormal)throws Exception{
  reset(scheduledCache());try{loadMessages(0,42,4,9,8,3,17,1);
   check(historyViews.isEmpty()&&Utilities.globalQueue.ready.isEmpty()&&historyQueue.ready.isEmpty()&&transcriptQueue.ready.isEmpty()&&prefetchQueue.ready.isEmpty(),"scheduled created ordinary owner/work");
   if(beforeNormal){Ui ui=new Ui();ui.firstLoadMessages();pump();for(Event e:events)ui.consume(e);check(ui.messages.size()==15&&historyViews.size()==1&&historyViews.get(7).count==15&&ui.waitingForLoad.isEmpty(),"scheduled-first polluted normal entry");int terminals=0;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==1){terminals++;scheduledContract(e,(Integer)e.args[10]==8?4:1,(Integer)e.args[10],(Integer)e.args[10]==8?17:2,(Integer)e.args[10]==8?9:0,(Integer)e.args[10]==8?3:2);}check(terminals==2,"scheduled-first missing exact terminal");}
   else{pump();scheduledContract(scheduledEvent(),4,8,17,9,3);check(histories.get(42L).before(0,400).size()==316&&saves==0&&current.requests.isEmpty(),"standalone changed cache/network");}
  }finally{cleanup();}
 }
 /** 已有普通页与不同guid探测互不建替代view；空回包不能被普通页面误消费。 */
 static void scheduledDifferentGuid()throws Exception{
  reset(scheduledCache());try{Ui ui=new Ui();ui.openCodexHistoryView();ui.waitingForLoad.add(1);loadMessages(0,42,15,0,7,2,1,0);loadMessages(0,42,1,0,8,2,3,1);pump();for(Event e:events)ui.consume(e);
   check(historyViews.size()==1&&historyViews.get(7).count==15&&ui.messages.size()==15&&ui.waitingForLoad.isEmpty(),"different guid created/polluted ordinary view");scheduledContract(scheduledEvent(),1,8,3,0,2);
  }finally{cleanup();}
 }
 /** 普通冷首屏入队和已登记waiter两个边界，定时探测均不能换ticket或清waiter。 */
 static void scheduledCold(boolean registered)throws Exception{
  reset(new TranscriptWindow());try{Ui ui=new Ui();ui.openCodexHistoryView();ui.waitingForLoad.add(1);loadMessages(0,42,15,0,7,2,1,0);HistoryView view=historyViews.get(7);Object ticket=view.initialLoadTicket;
   if(registered){Utilities.globalQueue.next();check(view.initialLoadWaiter!=null,"normal cold waiter not registered");}
   InitialLoadWaiter waiter=view.initialLoadWaiter;ui.waitingForLoad.add(2);loadMessages(0,42,1,0,7,2,2,1);
   check(view.initialLoadTicket==ticket&&view.initialLoadWaiter==waiter,"scheduled replaced ordinary ticket/waiter");
   current.latest=page("new1,new2,new3",false,null,"tail");watchConversation(0,42);pump();for(Event e:events)ui.consume(e);
   check(ui.messages.size()==3&&ui.waitingForLoad.isEmpty()&&view.count==15,"ordinary cold completion lost");check(current.requests.equals(Collections.singletonList("latest")),"scheduled duplicated normal network");scheduledContract(scheduledEvent(),1,7,2,0,2);
  }finally{cleanup();}
 }
 /** 已排UI的旧账号空通知必须丢弃，退出与账号代次变化分别覆盖。 */
 static void scheduledStale(boolean logout)throws Exception{reset(scheduledCache());try{loadMessages(0,42,1,0,7,2,1,1);if(logout)loggingOut=true;else accountGeneration++;pump();check(events.isEmpty()&&historyViews.isEmpty()&&current.requests.isEmpty(),"stale scheduled result escaped");}finally{cleanup();}}
 /** 执行原waitingForLoad分支；匹配索引只完成一次，负索引保留原约定，不接管其他模式。 */
 static void scheduledWaiting()throws Exception{reset(scheduledCache());try{Ui ui=new Ui();ui.waitingForLoad.add(9);loadMessages(0,42,1,0,7,2,9,1);pump();Event e=scheduledEvent();ui.consume(e);check(ui.waitingForLoad.isEmpty()&&ui.messages.isEmpty()&&ui.scheduledMessagesCount==0,"scheduled wait not completed");ui.consume(e);check(ui.waitingForLoad.isEmpty()&&current.requests.isEmpty(),"duplicate terminal restarted normal load");events.clear();ui.waitingForLoad.add(10);loadMessages(0,42,1,0,7,2,-10,1);pump();ui.consume(scheduledEvent());check(ui.waitingForLoad.equals(Collections.singletonList(10)),"negative-index original semantics changed");}finally{cleanup();}}
 /** 同段显式回最新仍使用15预算；既有316缓存与本地旧向50条不受辅助空页影响。 */
 static void scheduledSameSegment()throws Exception{reset(scheduledCache());try{Ui ui=new Ui();ui.firstLoadMessages();pump();for(Event e:events)ui.consume(e);events.clear();requestLatestHistory(0,42,7,ui.codexHistoryViewToken);pump();ui.consume(replacement());check(ui.messages.size()==15&&histories.get(42L).before(0,400).size()==316,"same segment replacement lost budget/cache");events.clear();int anchor=histories.get(42L).before(0,1).get(0).id;loadMessages(0,42,50,anchor,7,0,3,0);pump();check(((ArrayList)normal().args[2]).size()==50&&current.requests.isEmpty(),"local older path changed");}finally{cleanup();}}
 /** 所有测试内容合成；实际网络、RecyclerView绘制与设备可见时延由主管另验。 */
 static void scheduledRun()throws Exception{scheduledCase("normal15-scheduled1-latest3",()->scheduledWarm(15,true));scheduledCase("normal25-scheduled1-latest3",()->scheduledWarm(25,true));scheduledCase("normal-control",()->scheduledWarm(15,false));scheduledCase("standalone",()->scheduledStandalone(false));scheduledCase("scheduled-first",()->scheduledStandalone(true));scheduledCase("different-guid",()->scheduledDifferentGuid());scheduledCase("queued-cold",()->scheduledCold(false));scheduledCase("registered-waiter",()->scheduledCold(true));scheduledCase("account-change",()->scheduledStale(false));scheduledCase("logout",()->scheduledStale(true));scheduledCase("waiting-index",()->scheduledWaiting());scheduledCase("same-segment",()->scheduledSameSegment());check(scheduledFailures.isEmpty(),"scheduled isolation failures="+scheduledFailures);System.out.println("RuntimeScheduledHistoryIsolation: groups="+scheduledGroups+" failures=0");}
 """;
}
