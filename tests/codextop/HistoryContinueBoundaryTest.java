package com.butang.codextop;
import com.github.javaparser.StaticJavaParser;import com.github.javaparser.ParserConfiguration;import java.nio.file.*;
/** 组合实际 ChatActivity 首载/收页/布局入口、MessagesController 两重载及 Runtime/Window/Store。
 * 只替代 Android 几何和事件时序；数据为合成来源，存储使用 Runtime 专项独立临时目录。
 * args[0] 可指定前像，args[1] 可选择同一行为用例，args[2] 可导出实际提取体。 */
public final class HistoryContinueBoundaryTest {
 /** 提取真实页面、布局和Controller入口；以合成Android事件驱动原Runtime进行回归。 */
 public static void main(String[] args)throws Exception{
  StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  Path source=Path.of("TMessagesProj/src/main/java");Path chatSource=args.length>0?Path.of(args[0]):source.resolve("org/telegram/ui/ChatActivity.java");var chat=StaticJavaParser.parse(chatSource).getClassByName("ChatActivity").orElseThrow();
  var mc=StaticJavaParser.parse(source.resolve("org/telegram/messenger/MessagesController.java")).getClassByName("MessagesController").orElseThrow();
  // 本地浏览新增owner由同一真实提取器覆盖；89场景与断言仍只保留在本文件。
  if(!chat.getMethodsByName("showCodexLocalHistory").isEmpty()) {
   String previous=System.getProperty("archive.adjacent");
   try{System.setProperty("archive.adjacent","true");HistoryLocalArchiveTest.main(new String[]{chatSource.toString(),args.length>1?args[1]:"continueBudget();continueStates();continueStale();continueQueuedSearch();continueUiGuards();continueCompleteLatest();continueUnknownEmpty();continueEpochTerminal();continueFactReset();continueQueuedLatest();"});}
   finally{if(previous==null)System.clearProperty("archive.adjacent");else System.setProperty("archive.adjacent",previous);}
   return;
  }
  StringBuilder extra=new StringBuilder(CELL_BOUNDARY);
  var cell=StaticJavaParser.parse(source.resolve("org/telegram/ui/Cells/ChatLoadingCell.java")).getClassByName("ChatLoadingCell").orElseThrow();
  extra.append("static final class ChatLoadingCell extends View {FrameLayout frameLayout=new FrameLayout();Button retryButton;Runnable retryAction;int getThemedColor(int key){return key;}");
  extra.append(cell.getMethodsByName("setProgressVisible").get(0));extra.append(cell.getMethodsByName("setRetryAction").get(0));
  extra.append("boolean shown(){return frameLayout.visibility==VISIBLE;}boolean actionVisible(){return retryButton!=null&&retryButton.visibility==VISIBLE&&retryButton.enabled;}void tap(){if(retryButton!=null)retryButton.performClick();}}\n");
  extra.append(UI);
  // SearchItemListener先发布搜索状态，随后才做动画与可选筛选；提取真实发布语句。
  var searchExpand=chat.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("onSearchExpand")).findFirst().orElseThrow();
  var searchingStatement=searchExpand.getBody().orElseThrow().getStatement(0);
  if(!searchingStatement.toString().equals("searching = true;"))throw new AssertionError("search expansion state boundary changed");
  extra.append("void expandSearchUi(){").append(searchingStatement).append("}");
  var searchCollapse=chat.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("onSearchCollapse")).findFirst().orElseThrow();
  var collapseStatement=searchCollapse.getBody().orElseThrow().getStatement(0);
  if(!collapseStatement.toString().equals("searching = false;"))throw new AssertionError("search collapse state boundary changed");
  extra.append("void collapseSearchUi(){").append(collapseStatement).append("}");
  var searchQueryState=chat.getMethodsByName("hitSearch").get(0).getBody().orElseThrow().getStatements().stream().filter(v->v.toString().startsWith("searchItemVisible = searching =")).findFirst().orElseThrow();
  extra.append("void resetSearchQueryUi(){").append(searchQueryState).append("}");
  for(var field:chat.getFields())if(field.getVariables().stream().anyMatch(v->(v.getNameAsString().startsWith("codexHistoryInitialFill") || v.getNameAsString().equals("codexHistoryOlderContinueAvailable"))))extra.append(field);
  for(String name:new String[]{"cancelCodexHistoryInitialFill","scheduleCodexHistoryInitialFill","canFillCodexHistoryInitialViewport","retryCodexHistoryInitialFillAfterLayout","canBridgeCodexHistoryInitialViewport"})
   for(var method:chat.getMethodsByName(name))extra.append(method.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  var clearFull=chat.getMethodsByName("clearChatData").get(0).getBody().orElseThrow().getStatements().stream().filter(v->v.isIfStmt()&&v.asIfStmt().getCondition().toString().equals("full")).findFirst().orElseThrow();
  extra.append("void applyRealClearLoadState(boolean full){").append(clearFull).append("}");
  extra.append("void resumeUi(){paused=false;");
  for(var statement:chat.getMethodsByName("onResume").get(0).getBody().orElseThrow().getStatements())
   if(statement.isExpressionStmt()&&statement.toString().equals("scheduleCodexHistoryInitialFill();"))extra.append(statement);
  extra.append("} void clearCodexHistoryScrollTargets(){");
  for(var statement:chat.getMethodsByName("clearCodexHistoryScrollTargets").get(0).getBody().orElseThrow().getStatements()){
   if(statement.isIfStmt())break;extra.append(statement);
  }
  extra.append("}");
  // 用真实LayoutManager覆盖体，只将Android父类调用映射到同签名空边界；前像无覆盖时仅父类事件。
  var layout=chat.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("onLayoutCompleted")).findFirst();
  extra.append("void originalLayoutComplete(){");
  if(layout.isPresent())extra.append(layout.get().getBody().orElseThrow().toString().replace("super.onLayoutCompleted(state);", "layoutSuperComplete();"));
  else extra.append("layoutSuperComplete();");
  extra.append("}");
  // 执行原动画完成/强制结束的UI回调体，避免用测试重新实现重验接线。
  for(String name:new String[]{"onAllAnimationsDone","endAnimations"}) {
   var finish=chat.findAll(com.github.javaparser.ast.expr.LambdaExpr.class).stream()
    .filter(v->v.toString().contains("chatItemAnimator enable notifications") && v.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class).map(m->m.getNameAsString().equals(name)).orElse(false)).findFirst().orElseThrow();
   extra.append("void ").append(name.equals("endAnimations")?"originalForcedAnimationEnd":"originalAnimationComplete")
    .append("(){AndroidUtilities.runOnUIThread(").append(finish).append(");}");
  }
  for(String name:new String[]{"resumeCodexHistoryView","firstLoadMessages","isCodexHistoryView","openCodexHistoryView","prepareCodexHistoryPage","isCodexHistoryScrollCurrent","checkScrollForLoad","canRetryCodexHistoryLoadingCell","bindCodexHistoryLoadingCell","retryCodexHistoryFromCell","isCodexHistoryRetryBindingCurrent","isCodexHistoryLoadingCell","lockCodexHistoryReading","pauseCodexHistoryView","requestCodexLatestHistory"})for(var method:chat.getMethodsByName(name))extra.append(method.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  var receiver=chat.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
  var end=receiver.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(s->s.getCondition().toString().equals("messArr.size() < count && load_type != 3 && load_type != 4")).findFirst().orElseThrow();
  var check=receiver.findAll(com.github.javaparser.ast.stmt.ExpressionStmt.class).stream().filter(s->s.toString().equals("checkScrollForLoad(false);")).findFirst().orElseThrow();
  extra.append(CONSUME);
  // 消费真实等待编号守卫，迟到的同 token 失败不得污染已开始的新一次请求。
  for(var statement:receiver.getBody().orElseThrow().getStatements()){
   String value=statement.toString();
   if(value.startsWith("int queryLoadIndex =")||value.startsWith("boolean doNotRemoveLoadIndex;")||value.startsWith("if (queryLoadIndex < 0)")||value.startsWith("int index = waitingForLoad.indexOf")||value.startsWith("if (index == -1)"))extra.append(statement);
  }
  for(var statement:receiver.getBody().orElseThrow().getStatements())if(statement.isIfStmt()&&(statement.toString().contains("codexHistoryOlderRetryRequired = true") || statement.toString().contains("codexHistoryOlderContinueAvailable = !isEnd")))extra.append(statement.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  var firstDone=receiver.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(v->v.getCondition().toString().equals("first && messages.size() > 0")).findFirst().orElseThrow();
  var firstStatement=firstDone.getThenStmt().asBlockStmt().getStatement(0);
  extra.append("messages.addAll(messArr);for(MessageObject m:messArr)if(m.getId()>0)maxMessageId[0]=Math.min(maxMessageId[0],m.getId());if(!messages.isEmpty())messagesByDays.put(\"synthetic\",new Object());").append(end).append("loading=false;if(").append(firstDone.getCondition()).append("){ ").append(firstStatement).append("}").append(check);
  for(var statement:receiver.getBody().orElseThrow().getStatements())
   if(statement.isIfStmt()&&statement.toString().contains("codexHistoryInitialFillToken ="))extra.append(statement.toString().replace("com.butang.codextop.CodexRuntime", "RuntimeLatestSegmentProbe"));
  extra.append("} void dragHistory(){");
  var drag=chat.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(v->v.getCondition().toString().equals("newState == RecyclerView.SCROLL_STATE_DRAGGING")&&v.toString().contains("lockCodexHistoryReading()")).findFirst().orElseThrow();
  for(var statement:drag.getThenStmt().asBlockStmt().getStatements()){
   if(statement.toString().equals("pollHintCell = null;"))break;extra.append(statement);
  }
  extra.append("}\n}\n").append(CONTROLLER);
  for(var method:mc.getMethodsByName("loadMessages"))if(method.getParameters().size()==16||method.getParameters().size()==20){
   var extracted=method.clone();if(method.getParameters().size()==20)extracted.getBody().orElseThrow().addStatement(0,StaticJavaParser.parseStatement("calls.add(new Object[]{dialogId,count,max_id,classGuid,load_type,loadIndex,mode});"));
   extra.append(extracted.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  }
  extra.append("}\n").append(CASES).append(CONTINUE_CASES);
  var runtime=StaticJavaParser.parse(source.resolve("com/butang/codextop/CodexRuntime.java")).getClassByName("CodexRuntime").orElseThrow();
  for(String name:new String[]{"isHistoryViewCurrent","isHistoryBridgeViewCurrent"})for(var method:runtime.getMethodsByName(name))extra.append(method);
  // UI与Controller必须使用真实归属映射，不能用共享夹具的固定 dialog=42 替身掩盖账号删除。
  extra.append(runtime.getMethodsByName("ownsConversation").get(0).clone().setName("actualOwnsConversation"));
  if(args.length>2)Files.writeString(Path.of(args[2]),extra.toString());
  RuntimeLatestSegmentTest.runScenarios(source.resolve("com/butang/codextop/CodexRuntime.java"),extra.toString().replace("RuntimeLatestSegmentProbe.ownsConversation", "RuntimeLatestSegmentProbe.actualOwnsConversation"),args.length>1?args[1]:"continueBudget();continueStates();continueStale();continueQueuedSearch();continueUiGuards();continueCompleteLatest();continueUnknownEmpty();continueEpochTerminal();continueFactReset();continueQueuedLatest();");
 }
 private static final String UI="""
 static boolean enabled(){return true;}static final class MessagesController{static final int LOAD_AROUND_MESSAGE=3;}
 static final class TextUtils{enum TruncateAt{MIDDLE}static boolean isEmpty(String s){return s==null||s.isEmpty();}}
 static final class HashtagSearchController{static HashtagSearchController getInstance(int a){return new HashtagSearchController();}void searchHashtag(Object h,int g,int t,int i){throw new AssertionError("unexpected search");}}
 static final class RecyclerView{static class ItemAnimator{boolean running;boolean isRunning(){return running;}}}
 static final class RecyclerListView{static final int NO_POSITION=-1;int visible=1;int first=0;int height=500;boolean scrollUp,scrollDown,pending,computing,layoutRequested;RecyclerView.ItemAnimator animator;RecyclerView.ItemAnimator getItemAnimator(){return animator;}boolean hasPendingAdapterUpdates(){return pending;}boolean isComputingLayout(){return computing;}boolean isLayoutRequested(){return layoutRequested;}ArrayDeque<Runnable> draw=new ArrayDeque<>();int getHeight(){return height;}boolean canScrollVertically(int direction){return direction<0?scrollUp:scrollDown;}int invalidations;void invalidate(){invalidations++;}void preDraw(){while(!draw.isEmpty())draw.remove().run();}int getChildCount(){return visible;}Object getChildAt(int i){return i;}int getChildAdapterPosition(Object child){return child instanceof ChatLoadingCell?((ChatLoadingCell)child).position:first+(Integer)child;}}
 static final class Adapter{boolean isFrozen,isFiltered;int loadingUpRow=0,loadingDownRow=-5,total=1;int getItemCount(){return total;}}
 static final class Media{void loadMoreSearchMessages(boolean x){throw new AssertionError("unexpected search");}}
 static final class Bar{boolean selected,visible;boolean isActionModeShowed(){return selected;}boolean isSearchFieldVisible(){return visible;}}static final class Config{long getClientUserId(){return 1;}}
 static final class Ui{
  /** 默认关闭诊断边界；真实logger由独立专项执行。 */ void traceCodexHistoryViewport(String p,Object t,boolean e){}
  int layoutCompletions;void layoutSuperComplete(){layoutCompletions++;} Runnable finishRunnable;int scrollAnimationIndex=-1;static final class BuildVars{static boolean LOGS_ENABLED=false;}static final class FileLog{static void d(String s){}}
  static final class AnimationNotifications{void onAnimationFinish(int i){}}AnimationNotifications getNotificationCenter(){return new AnimationNotifications();}
  static final int MODE_DEFAULT=0,MODE_SCHEDULED=1,MODE_SEARCH=7,MODE_SAVED=3;
  int startLoadFromMessageOffset;int chatMode,currentAccount,classGuid=7,initialMessagesSize=15,startLoadFromMessageId,startLoadFromMessageIdSaved,startLoadFromDate,highlightMessageId,searchType,lastLoadIndex=1,replyMaxReadId;
  long dialog_id=42,mergeDialogId,migrated_to,threadMessageId,codexHistoryViewToken,codexHistoryPausedToken,codexHistoryViewRevision;
  String searchingQuery;Object searchingReaction;boolean searchItemVisible;boolean selected,thread,searching;boolean codexHistoryOlderRetryRequired,waitingForReplyMessageLoad;boolean firstLoading;boolean first,scrollToTopOnResume,forceScrollToTop;Object scrollToMessage;boolean firstMessagesLoaded,loadInfo,historyPreloaded,isTopic,codexHistoryReadingLocked,codexHistoryLatestAvailable,paused,waitingForGetDifference,loading=true,loadingForward;
  boolean[] endReached={false,false},cacheEndReached={false,false},forwardEndReached={true,true};int[] maxMessageId={Integer.MAX_VALUE,Integer.MAX_VALUE},minMessageId={0,0},minDate={0,0},maxDate={0,0};
  String codexHistoryEpoch,searchingHashtag;Object chatLayoutManager=new Object(),currentEncryptedChat;Bar actionBar;Adapter chatAdapter=new Adapter();RecyclerListView chatListView=new RecyclerListView();Controller controller=new Controller();
  ArrayList<Integer> waitingForLoad=new ArrayList<>();ArrayList<MessageObject> messages=new ArrayList<>();HashMap<String,Object> messagesByDays=new HashMap<>();
  boolean isThreadChat(){return thread;}long getSavedDialogId(){return 0;}Config getUserConfig(){return new Config();}Controller getMessagesController(){return controller;}Media getMediaDataController(){return new Media();}
  void restoreCodexHistoryBookmark(){throw new AssertionError("unexpected locked bookmark restoration");}void finishFragment(){}boolean hasTextSelection(){return selected;}void updatePagedownButtonVisibility(boolean x){}void updateCodexHistoryLoadingCells(){}void clearCodexHistoryBookmark(){}void clearChatData(boolean x){messages.clear();messagesByDays.clear();maxMessageId[0]=Integer.MAX_VALUE;applyRealClearLoadState(x);}
  static final class AndroidUtilities {static boolean isTablet(){return false;}static void runOnUIThread(Runnable r){RuntimeLatestSegmentProbe.AndroidUtilities.runOnUIThread(r);}static void doOnPreDraw(RecyclerListView view,Runnable r){view.draw.add(r);}}
 """;
 private static final String CONSUME="""
  @SuppressWarnings("unchecked")void consume(Event event){if(event.type!=NotificationCenter.messagesDidLoad)return;Object[] args=prepareCodexHistoryPage(event.args);if(args==null||(Integer)args[10]!=classGuid||(Integer)args[14]!=MODE_DEFAULT)return;
   int count=(Integer)args[1],load_type=(Integer)args[8],loadIndex=0,mode=(Integer)args[14];boolean isCache=(Boolean)args[3],isEnd=(Boolean)args[9];ArrayList<MessageObject> messArr=(ArrayList<MessageObject>)args[2];
 """;
 private static final String CONTROLLER="""
 static final class Controller{int currentAccount;ArrayList<Object[]> calls=new ArrayList<>();void checkSensitive(Object owner,long id,Runnable accept,Runnable reject){accept.run();}void loadMessagesInternal(Object...args){throw new AssertionError("unexpected Telegram network");}
 """;
 private static final String CASES="""

 /** 两条真实路径均先暂停原首屏、恢复显式空latest，再由watch得到首正文。 */
 static Ui resumedEmpty(boolean cold)throws Exception{
  TranscriptWindow empty;
  if(cold)empty=new TranscriptWindow();else {TranscriptWindow old=new TranscriptWindow();old.prepend(page("A,B",false,null,"tail-old"));empty=old.acceptLatest(page("",true,"empty-0","tail-empty"));}
  reset(empty);Ui target=new Ui();target.first=true;
  if(!cold)for(int i=0;i<4;i++)current.older("empty-"+i,page("",true,"empty-"+(i+1),null));
  target.firstLoadMessages();
  if(cold){Utilities.globalQueue.next();check(historyViews.get(7).window==empty&&historyViews.get(7).initialLoadWaiter!=null,"cold original loader did not bind root/waiter");}
  else {pump();consumeSnapshot(target);check(target.codexHistoryEpoch.equals(empty.epoch()),"loaded empty epoch missing");}
  target.paused=true;target.pauseCodexHistoryView(false);pump();events.clear();
  target.resumeCodexHistoryView();pump();check(events.isEmpty()&&historyViews.get(7).initialLoadWaiter==null,"resume did not leave explicit empty latest without waiter");
  current.requests.clear();current.latest=page("Z",true,"gap-0","tail-new");return target;
 }
 /** 首正文交付、真实接纳、短屏/归档准入、四页停止以及后续增量不重开。 */
 static void firstVisible(boolean cold)throws Exception{try{
  Ui target=resumedEmpty(cold);TranscriptWindow root=histories.get(42L);watchConversation(0,42);pump();
  int latestCount=0,incrementCount=0;for(Event e:events){if(e.type==NotificationCenter.messagesDidLoad)latestCount++;if(e.type==NotificationCenter.didReceiveNewMessages)incrementCount++;}
  System.out.println("OBSERVE cold="+cold+" sameRoot="+(histories.get(42L)==root)+" latestEvents="+latestCount+" increments="+incrementCount+" requests="+current.requests);
  check(latestCount==1&&incrementCount==0,"first body must use exactly one real type2, not bypass or duplicate increment");
  check(metadata(replacement()).unbridgedCachedHistory==!cold,"gap fact not matched actual retained archive");
  consumeSnapshot(target);pump();check(target.messages.size()==1&&!target.first&&!target.loading&&target.codexHistoryInitialFillToken==target.codexHistoryViewToken,"real receiver did not finish or arm");
  if(!AndroidUtilities.ui.delayed.isEmpty()){AndroidUtilities.ui.wake();pump();}events.clear();
  for(int i=0;i<4;i++)current.older("gap-"+i,page("",true,"gap-"+(i+1),null));current.requests.clear();target.resumeUi();draw(target);pump();consumeSnapshot(target);
  check(current.requests.equals(Arrays.asList("older:gap-0","older:gap-1","older:gap-2","older:gap-3")),"first body changed original four-page budget: "+current.requests);
  check(root.cursor.equals("gap-4")&&ids(root).equals("Z"),"progressing empty pages did not preserve current segment");
  if(!cold)check(root.findSegment("A",null)!=null,"unproven archive was removed");
  current.latest=page("Z,Q",true,"unused","tail-q");current.requests.clear();watchConversation(0,42);pump();
  int normal=0;for(Event e:events){check(e.type!=NotificationCenter.messagesDidLoad,"ordinary nonempty latest repeated replacement");if(e.type==NotificationCenter.didReceiveNewMessages){check(rows(e).equals("Q"),"ordinary increment redelivered first body");normal++;}}
  check(normal==1,"ordinary same-root new body missing");events.clear();target.originalLayoutComplete();draw(target);pump();check(current.requests.equals(Collections.singletonList("latest")),"nonempty increment re-opened automatic budget");
  System.out.println("PASS cold="+cold+" first-visible -> actual receiver -> <=4 pages -> ordinary increment no second fill");
 }finally{cleanup();}}
 /** 首正文候选沿原页面生命周期失效，不使暂停/账号/新意图获得历史操作。 */
 static void firstVisibleStale(String kind)throws Exception{try{
  Ui target=resumedEmpty(false);watchConversation(0,42);pump();Event offered=replacement();events.clear();
  if(kind.equals("pause"))target.pauseCodexHistoryView(false);else if(kind.equals("account"))accountGeneration++;else if(kind.equals("mapping"))remoteIds.put(42L,"other");else historyViews.get(7).intentRevision++;
  target.consume(offered);ui();target.resumeUi();draw(target);pump();check(target.messages.isEmpty()&&target.codexHistoryInitialFillToken==0&&current.requests.equals(Collections.singletonList("latest")),"stale first-visible bypassed "+kind);
  System.out.println("PASS first-visible stale "+kind);
 }finally{cleanup();}}
 /** 阅读锁仍由真实prepare拒收；明确回最新才接受，不能靠首正文转换清锁。 */
 static void firstVisibleLocked()throws Exception{try{
  Ui target=resumedEmpty(false);target.codexHistoryReadingLocked=true;watchConversation(0,42);pump();consumeSnapshot(target);target.resumeUi();draw(target);pump();
  check(target.codexHistoryReadingLocked&&target.codexHistoryLatestAvailable&&target.messages.isEmpty()&&target.codexHistoryInitialFillToken==0,"first-visible cleared real reading intent");
  check(target.requestCodexLatestHistory(),"original explicit latest rejected");pump();consumeSnapshot(target);check(!target.codexHistoryReadingLocked&&target.messages.size()==1,"explicit latest failed original acceptance");
  System.out.println("PASS first-visible preserves reading lock; original explicit latest accepts");
 }finally{cleanup();}}
 /** 原Outbox仅在真实接受后恢复一次，后续watch增量不重放或重新发送。 */
 static void firstVisiblePending()throws Exception{try{
  Ui target=resumedEmpty(false);outbox.put(new OutboxStore.Item("unknown","synthetic-remote","synthetic",-8,1));outbox.markSubmissionUncertain("unknown",true);
  watchConversation(0,42);pump();for(Event e:events)check(e.type!=NotificationCenter.didReceiveNewMessages,"pending replayed before real latest acceptance");
  consumeSnapshot(target);pump();AndroidUtilities.ui.wake();pump();int pending=0;for(Event e:events)if(e.type==NotificationCenter.didReceiveNewMessages){check(rows(e).equals("unknown"),"first-visible duplicated body as pending");pending++;}check(pending==1&&outbox.get("unknown").submissionUncertain,"accepted first visible lost original uncertain outbox");
  events.clear();current.latest=page("Z,Q",true,"unused","tail-q");watchConversation(0,42);pump();int increment=0;for(Event e:events){check(e.type!=NotificationCenter.messagesDidLoad,"new nonempty latest repeated pending restore");if(e.type==NotificationCenter.didReceiveNewMessages){check(rows(e).equals("Q"),"ordinary increment replays pending");increment++;}}
  check(increment==1&&outbox.get("unknown")!=null,"ordinary watch damaged pending");System.out.println("PASS first-visible pending only after acceptance; ordinary increment no replay");
 }finally{cleanup();}}
 /** 同一Chat真正暂停/恢复换token，同epoch原短屏依旧每view一次；满屏bridge不重新开户。 */
 static void bridgeResumeBudget(boolean shortScreen)throws Exception{try{
  Ui target=shortScreen?initial("A"):bridgePrepared(true);draw(target);pump();consumeSnapshot(target);int before=current.requests.size();long oldToken=target.codexHistoryViewToken;
  check(before==1,"initial failed operation count");target.pauseCodexHistoryView(false);target.resumeCodexHistoryView();pump();consumeSnapshot(target);
  check(target.codexHistoryViewToken!=oldToken&&target.codexHistoryViewToken!=0,"real resume did not issue new view token");draw(target);pump();consumeSnapshot(target);
  check(current.requests.size()==before+(shortScreen?1:0),"same epoch newtoken changed short/bridge budget short="+shortScreen+" requests="+current.requests);
  System.out.println("PASS actual pause/resume same epoch short="+shortScreen+" retains original short allowance and no second full-screen bridge");
 }finally{cleanup();}}
 /** 后台已经保留新根时，首个非replace latest通知也可按同一事实准入。 */
 static void bridgeCachedNewRoot()throws Exception{
  TranscriptWindow old=new TranscriptWindow();old.prepend(page("A,B",false,null,"tail-old"));TranscriptWindow root=old.acceptLatest(page("E,F",true,"gap-0","tail-new"));reset(root);try{
   Ui target=new Ui();target.chatListView.scrollUp=true;target.firstLoadMessages();pump();consumeSnapshot(target);
   current.older("gap-0",page("B,C,D",true,"unused",null));draw(target);pump();consumeSnapshot(target);
   check(current.requests.equals(Collections.singletonList("older:gap-0"))&&ids(root).equals("F,E,D,C,B,A"),"nonreplace cached root did not bridge once");
   System.out.println("PASS cached new root first real latest accepts gap snapshot without replacement event");
  }finally{cleanup();}
 }
 /** 同view短屏已消耗旧epoch不阻塞真正接纳的新epoch，但同epoch不会多开。 */
 static void bridgeAfterShort()throws Exception{try{
  Ui target=initial("A,B");draw(target);pump();consumeSnapshot(target);check(current.requests.size()==1,"ordinary short epoch did not consume one failed load");
  current.latest=page("E,F",true,"new-gap","new-tail");watchConversation(0,42);pump();target.consume(replacement());events.clear();ui();pump();current.requests.clear();target.chatListView.scrollUp=true;
  current.older("new-gap",page("B,C,D",true,"unused",null));current.older("old-older",page("",false,null,null));draw(target);pump();consumeSnapshot(target);
  check(current.requests.equals(Arrays.asList("older:new-gap","older:old-older"))&&ids(histories.get(42L)).equals("F,E,D,C,B,A"),"accepted epoch blocked by previous short view budget");
  System.out.println("PASS short old epoch and bridge new epoch share per-epoch allowance, distinct epoch can run once");
 }finally{cleanup();}}
 /** 已连续、空tail或完整边界不能凭归档存在而开始旧向加载。 */
 static void bridgeNoFalseAdmission()throws Exception{for(String kind:new String[]{"continuous","complete","empty","terminalArchive","containedMismatch"})try{
  TranscriptWindow old=new TranscriptWindow();old.prepend(page("A,B",false,null,"tail-old"));TranscriptWindow root=old;
  if(kind.equals("terminalArchive"))root=old.acceptLatest(page("E,F",false,null,"tail-new"));
  else if(kind.equals("empty"))root=old.acceptLatest(page("",true,"empty-gap","tail-new"));
  else if(kind.equals("containedMismatch")) {root=old.acceptLatest(page("E,F",true,"gap-0","tail-new"));JsonObject known=page("A,B",true,"gap-new",null);known.getAsJsonArray("items").get(1).getAsJsonObject().getAsJsonObject("raw").getAsJsonObject("content").addProperty("text","different");root.prepend(known);}
  else if(kind.equals("continuous"))root=old.acceptLatest(page("A,B,C",true,"unused","tail-new"));
  reset(root);Ui target=new Ui();target.chatListView.scrollUp=true;target.firstLoadMessages();pump();consumeSnapshot(target);int before=current.requests.size();draw(target);pump();
  check(current.requests.size()==before,"false bridge eligibility: "+kind);
  if(kind.equals("containedMismatch")){check(!root.pruneMergedSegments(Collections.emptySet()),"changed-content archive wrongly pruned");check(root.findSegment("B",null)!=null,"cached identity lost");}
  System.out.println("PASS no false automatic bridge "+kind);
 }finally{cleanup();}}

 /** 旧完整缓存与非空 latest 的真实接收先建立新段，布局事件尚未发生。 */
 static Ui bridgePrepared(boolean scrollable)throws Exception{
  TranscriptWindow old=new TranscriptWindow();old.prepend(page("A,B",false,null,"old-tail"));reset(old);
  Ui target=new Ui();target.firstLoadMessages();pump();consumeSnapshot(target);target.chatListView.scrollUp=true;draw(target);
  current.latest=page("E,F",true,"gap-0","new-tail");watchConversation(0,42);pump();target.consume(replacement());events.clear();ui();pump();
  check(ids(histories.get(42L)).equals("F,E")&&histories.get(42L).findSegment("A",null)!=null,"fixture did not retain real archived A/B");
  current.requests.clear();target.chatListView.scrollUp=scrollable;return target;
 }
 /** 正式UI准入发一次旧向load，再由正式Window的末锚证明接回旧缓存。 */
 static void bridgeScrollable(boolean scrollable)throws Exception{try{
  Ui target=bridgePrepared(scrollable);current.older("gap-0",page("B,C,D",true,"unused-after-bridge",null));
  int calls=target.controller.calls.size();draw(target);pump();consumeSnapshot(target);
  check(current.requests.equals(Collections.singletonList("older:gap-0")),"known archived gap did not use exactly one existing older RPC on scrollable="+scrollable+" actual="+current.requests);
  check(ids(histories.get(42L)).equals("F,E,D,C,B,A"),"real bridge lost/reordered messages");
  check(target.controller.calls.size()==calls+1&&!target.codexHistoryReadingLocked,"bridge duplicated load or forged reading intent");
  target.originalLayoutComplete();target.resumeUi();draw(target);pump();consumeSnapshot(target);
  check(target.controller.calls.size()==calls+1,"layout/resume reused same epoch allowance");
  check(target.messages.size()==6,"UI did not deliver bridged history exactly once");
  System.out.println("PASS real scrollable="+scrollable+" latest accepts + once older + proven bridge + no duplicate load");
 }finally{cleanup();}}
 /** 达到原四页空投影预算或真实错误后，后续latest/布局不自动开始第二次旧向操作。 */
 static void bridgeStops(boolean error)throws Exception{try{
  Ui target=bridgePrepared(true);if(!error)for(int i=0;i<4;i++)current.older("gap-"+i,page("",true,"gap-"+(i+1),null));
  int calls=target.controller.calls.size();draw(target);pump();consumeSnapshot(target);
  int expected=error?1:4;check(current.requests.size()==expected,"bridge budget/error wrong: "+current.requests);
  check(ids(histories.get(42L)).equals("F,E")&&histories.get(42L).findSegment("A",null)!=null,"unknown gap was concatenated/deleted");
  check(!target.loading&&target.codexHistoryOlderRetryRequired==error,"end-of-operation loading/failure fact wrong");
  target.waitingForLoad.add(90);loadMessages(0,42,15,0,7,2,90,0);pump();consumeSnapshot(target);target.originalLayoutComplete();draw(target);pump();consumeSnapshot(target);
  check(target.controller.calls.size()==calls+1&&current.requests.size()==expected,"same epoch latest/layout retried budget/failure");
  System.out.println("PASS "+(error?"failure":"four progress-empty pages")+" stops, gap retained, repeated latest no auto retry");
 }finally{cleanup();}}
 /** 已排队的布局工作必须在真实页面、账号和阅读意图改变后失效。 */
 static void bridgeCancel()throws Exception{for(String kind:new String[]{"pause","token","account","root","emptyroot","lock","search","loading","forward","first","resumeScroll","forcedScroll","targetScroll"})try{
  Ui target=bridgePrepared(true);int calls=target.controller.calls.size();
  if(kind.equals("pause"))target.pauseCodexHistoryView(false);else if(kind.equals("token")){pauseHistoryView(7,target.codexHistoryViewToken);openHistoryView(0,42,7);}
  else if(kind.equals("account"))accountGeneration++;else if(kind.equals("root")){current.latest=page("different",true,"other-older","other-tail");watchConversation(0,42);pump();events.clear();current.requests.clear();}
  else if(kind.equals("emptyroot")){current.latest=page("",true,"empty-root-older","empty-root-tail");watchConversation(0,42);pump();events.clear();current.requests.clear();}
  else if(kind.equals("lock"))target.lockCodexHistoryReading();else if(kind.equals("search"))target.expandSearchUi();
  else if(kind.equals("loading"))target.loading=true;else if(kind.equals("forward"))target.loadingForward=true;
  else if(kind.equals("first"))target.first=true;else if(kind.equals("resumeScroll"))target.scrollToTopOnResume=true;
  else if(kind.equals("forcedScroll"))target.forceScrollToTop=true;else target.scrollToMessage=new Object();
  draw(target);pump();check(current.requests.isEmpty()&&target.controller.calls.size()==calls,"stale bridge admitted: "+kind);
  System.out.println("PASS cancelled bridge "+kind);
 }finally{cleanup();}}
 /** 新epoch只有真实latest被前台接收才新准入；旧排队回调和同epochrevision不消费两次。 */
 static void bridgeNextEpoch()throws Exception{try{
  Ui target=bridgePrepared(true);draw(target);pump();consumeSnapshot(target);check(current.requests.size()==1,"first epoch did not consume failed allowance");
  TranscriptWindow old=histories.get(42L);TranscriptWindow next=old.acceptLatest(page("Y,Z",true,"gap-new","tail-new"));histories.put(42L,next);
  requestLatestHistory(0,42,7,target.codexHistoryViewToken);pump();Event proposed=replacement();
  target.originalLayoutComplete();draw(target);pump();check(current.requests.size()==1,"unaccepted new root admitted bridge");
  target.consume(proposed);events.clear();ui();pump();current.older("gap-new",page("F,G",true,"after-new",null));current.older("gap-0",page("B,C,D",true,"unused",null));
  draw(target);pump();consumeSnapshot(target);check(current.requests.size()==3,"accepted new epoch did not get one bounded operation: "+current.requests);
  check(ids(next).equals("Z,Y,G,F,E,D,C,B,A"),"second proven bridge not complete");
  target.waitingForLoad.add(91);loadMessages(0,42,15,0,7,2,91,0);pump();consumeSnapshot(target);draw(target);pump();
  check(current.requests.size()==3,"same accepted epoch latest reopened budget");
  System.out.println("PASS only accepted next epoch renews one budget; stale layout and same epoch stay closed");
 }finally{cleanup();}}
 static void consumeSnapshot(Ui target){for(Event e:new ArrayList<Event>(events))target.consume(e);events.clear();ui();}
 static Ui initial(String ids)throws Exception{reset(cache(ids));Ui target=new Ui();target.firstLoadMessages();pump();consumeSnapshot(target);return target;}
 static void draw(Ui target){target.chatListView.preDraw();ui();}
 static void noLoad(String label){check(Utilities.globalQueue.ready.isEmpty()&&historyQueue.ready.isEmpty()&&current.requests.isEmpty(),label);}
 static void shortCached(int count)throws Exception{try{
  Ui target=initial(count==1?"A":"A,B");
  check(target.messages.size()==count&&!target.endReached[0]&&historyViews.get(7).count==15,"not actual cached short page/count15");
  noLoad("filled before actual layout");draw(target);
  check(target.loading&&!target.codexHistoryReadingLocked&&Utilities.globalQueue.ready.size()==1,"short cached"+count+" did not admit exactly one fill after layout");
  check(target.controller.calls.size()==3,"first/scheduled/one older controller calls");Object[] load=target.controller.calls.get(2);
  check((Integer)load[1]==50&&(Integer)load[2]==target.maxMessageId[0]&&(Integer)load[4]==0&&(Integer)load[6]==0,"automatic fill changed count/anchor/type/mode");
  target.resumeUi();draw(target);check(Utilities.globalQueue.ready.size()==1,"layout/resume admitted twice");
  System.out.println("PASS actual cached"+count+" + prepare/didLoad/layout one fill, count50/anchor/type0, reading lock unchanged");
 }finally{cleanup();}}
 static void shortComplete()throws Exception{TranscriptWindow complete=new TranscriptWindow();complete.prepend(page("A",false,null,"tail"));reset(complete);try{
  Ui target=new Ui();target.firstLoadMessages();pump();
  for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0)check((Boolean)e.args[9],"complete latest fixture not terminal");
  consumeSnapshot(target);draw(target);noLoad("complete latest incorrectly admitted older");check(target.controller.calls.size()==2,"complete latest consumed automatic fill allowance");
  System.out.println("PASS actual complete1 latest notification end=true never admits older despite native endReached semantics");
 }finally{cleanup();}}
 static void shortPendingComplete()throws Exception{try{
  Ui target=initial("A");histories.get(42L).prepend(page("A",false,null,"tail"));
  target.waitingForLoad.add(99);loadMessages(0,42,15,0,7,2,99,0);pump();
  for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad)check((Boolean)e.args[9],"same epoch terminal notification fixture");
  consumeSnapshot(target);draw(target);noLoad("terminal latest retained previously queued fill");
  System.out.println("PASS same-epoch end=true latest cancels earlier unexecuted fill ticket");
 }finally{cleanup();}}
 static void shortReplacement()throws Exception{try{
  Ui target=initial("A,B,C,D");target.chatListView.scrollUp=true;draw(target);noLoad("scrollable cache filled");
  current.latest=page("Z",true,"new-older","new-tail");watchConversation(0,42);pump();Event candidate=replacement();target.consume(candidate);events.clear();ui();pump();
  check(target.messages.size()==1&&histories.get(42L).findSegment("A",null)!=null,"replacement/archive fixture failed");
  check(!isHistoryPageCurrent(metadata(candidate)),"accepted old page should be invalid");current.requests.clear();target.chatListView.scrollUp=false;draw(target);
  check(Utilities.globalQueue.ready.size()==1&&!target.codexHistoryReadingLocked,"accepted short latest did not fill once");
  System.out.println("PASS accepted latest1/archive4 fills using current view identity, archive kept");
 }finally{cleanup();}}
 static void shortPaused()throws Exception{reset(cache("A"));try{
  Ui target=new Ui();target.paused=true;target.firstLoadMessages();pump();consumeSnapshot(target);draw(target);noLoad("paused initial filled");
  target.resumeUi();draw(target);check(Utilities.globalQueue.ready.size()==1,"onResume did not reuse valid initial eligibility");
  System.out.println("PASS initial notification before resume waits for foreground layout");
 }finally{cleanup();}}
 static void shortGuards()throws Exception{
  for(String kind:new String[]{"up","down","noheight","nochildren","end","filtered","frozen","selected","action","topic","thread","mode1","mode2","mode7","unowned","noanchor","pending","date","empty","searching"})try{
   Ui target=initial("A");
   switch(kind){case "up":target.chatListView.scrollUp=true;break;case "down":target.chatListView.scrollDown=true;break;case "noheight":target.chatListView.height=0;break;case "nochildren":target.chatListView.visible=0;break;case "end":target.endReached[0]=true;break;case "filtered":target.chatAdapter.isFiltered=true;break;case "frozen":target.chatAdapter.isFrozen=true;break;case "selected":target.selected=true;break;case "action":target.actionBar=new Bar();target.actionBar.selected=true;break;case "topic":target.isTopic=true;break;case "thread":target.thread=true;break;case "mode1":target.chatMode=1;break;case "mode2":target.chatMode=2;break;case "mode7":target.chatMode=7;break;case "unowned":remoteIds.clear();break;case "noanchor":target.maxMessageId[0]=Integer.MAX_VALUE;break;case "pending":target.messages.get(0).messageOwner.id=-7;break;case "date":target.messages.get(0).messageOwner.params.clear();break;case "empty":target.messages.clear();break;case "searching":target.expandSearchUi();check(target.chatMode==0&&!target.chatAdapter.isFiltered,"search fixture already filtered");break;}
   draw(target);noLoad("guard failed "+kind);
  }finally{cleanup();}
  System.out.println("PASS 20 geometry/mode/selection/end/anchor/source guards including one long scrollable message");
 }
 static void shortVisibleSearch()throws Exception{try{
  Ui target=initial("A");target.actionBar=new Bar();target.actionBar.visible=true;target.expandSearchUi();target.resetSearchQueryUi();
  check(!target.searching&&target.actionBar.isSearchFieldVisible()&&!target.chatAdapter.isFiltered,"empty visible search state fixture");
  draw(target);noLoad("visible search with empty query admitted automatic fill");
  System.out.println("PASS actual empty-query hitSearch state still blocks via visible expanded field");
 }finally{cleanup();}}
 static void shortCancellation()throws Exception{
  for(String kind:new String[]{"pause","destroy","token","account","accountIndex","logout","removed","remote","epoch","revision","drag","latest"})try{
   Ui target=initial("A");target.chatListView.preDraw();
   switch(kind){case "pause":target.pauseCodexHistoryView(false);break;case "destroy":target.pauseCodexHistoryView(true);break;case "token":pauseHistoryView(7,target.codexHistoryViewToken);openHistoryView(0,42,7);break;case "account":accountGeneration++;break;case "accountIndex":org.telegram.messenger.UserConfig.selectedAccount=1;break;case "logout":loggingOut=true;break;case "removed":historyViews.clear();break;case "remote":remoteIds.put(42L,"another");break;case "epoch":target.codexHistoryEpoch="another";break;case "revision":target.clearCodexHistoryScrollTargets();break;case "drag":target.lockCodexHistoryReading();break;case "latest":target.requestCodexLatestHistory();Utilities.globalQueue.ready.clear();break;}
   ui();pump();check(Utilities.globalQueue.ready.isEmpty()&&historyQueue.ready.isEmpty()&&current.requests.isEmpty(),"stale queued fill "+kind);
   if(kind.equals("removed"))check(historyViews.isEmpty(),"readonly guard recreated removed view");
  }finally{org.telegram.messenger.UserConfig.selectedAccount=0;cleanup();}
  System.out.println("PASS 12 queued cancellation boundaries: pause/destroy/token/account/logout/removed/remote/epoch/revision/drag/latest");
 }
 static void shortTerminal(boolean fail)throws Exception{try{
  Ui target=initial("A");
  if(!fail){current.older("old-older",page("",true,"e1",null));for(int i=1;i<4;i++)current.older("e"+i,page("",true,"e"+(i+1),null));}
  draw(target);pump();consumeSnapshot(target);draw(target);pump();
  check(current.requests.size()==(fail?1:4)&&!target.loading&&!target.endReached[0]&&!target.codexHistoryReadingLocked,"terminal budget/error changed or auto looped");
  target.resumeUi();target.checkScrollForLoad(false);draw(target);pump();check(current.requests.size()==(fail?1:4),"terminal callback resumed scans");
  // A fresh latest notification in the same view must not give this automatic fill a second allowance.
  target.waitingForLoad.add(99);loadMessages(0,42,15,0,7,2,99,0);pump();consumeSnapshot(target);draw(target);pump();check(current.requests.size()==(fail?1:4),"later latest reset once-per-view allowance");
  target.dragHistory();ui();check(Utilities.globalQueue.ready.size()==1,"automatic stop disabled later manual drag");
  System.out.println("PASS "+(fail?"network error":"4 empty pages with hasMore")+" stops automatically, same-view latest cannot rearm, manual drag retained");
 }finally{cleanup();}}
 /** 真实 Runtime 失败/原游标拒绝，经等待编号守卫消费后，不得被任何布局回调重发。 */
 static void retryFailure(boolean unchanged)throws Exception{try{
  Ui target=initial("A");if(unchanged)current.older("old-older",page("",true,"old-older",null));
  target.dragHistory();ui();target.dragHistory();ui();check(Utilities.globalQueue.ready.size()==1,"second drag while loading started duplicate older request");pump();Event failed=events.stream().filter(e->e.type==NotificationCenter.messagesDidLoad).findFirst().get();
  check(metadata(failed).loadFailed,"real failure lost local outcome");target.consume(failed);events.clear();ui();
  check(!target.loading&&!target.endReached[0]&&target.codexHistoryReadingLocked,"failure changed reading/end or kept loading");
  check(current.requests.size()==1&&Utilities.globalQueue.ready.isEmpty(),"failure tail restarted without new drag");
  target.lockCodexHistoryReading();target.checkScrollForLoad(false);target.checkScrollForLoad(true);target.resumeUi();draw(target);ui();
  check(current.requests.size()==1&&Utilities.globalQueue.ready.isEmpty(),"programmatic/ongoing drag unlocked retry");
  target.dragHistory();ui();check(Utilities.globalQueue.ready.size()==1,"new real gesture could not retry");
  // 原失败已消费，重复投递同 token/index 不得把新请求的重试资格再次锁住。
  check(!target.codexHistoryOlderRetryRequired&&target.loading,"new gesture state");target.consume(failed);
  check(!target.codexHistoryOlderRetryRequired&&target.loading,"late old failure polluted next request");
  System.out.println("PASS real "+(unchanged?"unchanged cursor":"network failure")+" stops layout/tail, only fresh drag retries; duplicate index ignored");
 }finally{cleanup();}}
 /** 有效空页仍以既有四页预算继续；之后真正失败才停，不掩盖中间已保存的进展。 */
 static void retryProgress()throws Exception{try{
  Ui target=initial("A");current.older("old-older",page("",true,"e1",null));for(int i=1;i<6;i++)current.older("e"+i,page("",true,"e"+(i+1),null));
  target.dragHistory();pump();check(current.requests.size()==4,"first operation budget changed");
  for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad)check(!metadata(e).loadFailed,"valid empty misclassified failure");
  consumeSnapshot(target);check(Utilities.globalQueue.ready.size()==1&&!target.codexHistoryOlderRetryRequired,"valid empty cursor progress blocked");
  pump();check(current.requests.size()==7,"follow-up did not stop at real error after two advances");consumeSnapshot(target);
  check(!target.loading&&target.codexHistoryOlderRetryRequired&&Utilities.globalQueue.ready.isEmpty()&&historyViews.get(7).window.cursor.equals("e6"),"partial progress error restarted or lost cursor");
  System.out.println("PASS valid four empty advances continue; following partial progress failure stops without losing cursor");
 }finally{cleanup();}}
 /** 真实绑定回调到原Controller/Runtime/Window链路，点击只能产生一次已有预算的读取。 */
 static void retryClickRuntime()throws Exception{try{
  Ui target=initial("A");target.dragHistory();pump();consumeSnapshot(target);check(target.codexHistoryOlderRetryRequired,"retry click failure fixture");
  ChatLoadingCell cell=new ChatLoadingCell();cell.parent=target.chatListView;cell.position=target.chatAdapter.loadingUpRow;target.bindCodexHistoryLoadingCell(cell,cell.position);check(cell.retryAction!=null,"failed real Runtime page had no action");
  current.older("old-older",page("B",false,null,null));int calls=target.controller.calls.size();cell.tap();cell.tap();pump();consumeSnapshot(target);
  check(current.requests.size()==2&&target.controller.calls.size()==calls+1&&!target.loading&&!target.codexHistoryOlderRetryRequired,"click did not complete one retry");
  check(historyViews.get(7).window.before(0,10).size()==2,"retry did not keep old and newly loaded body");
  System.out.println("PASS actual bound callback to Controller/Runtime performs one retry, keeps existing and new rows");
 }finally{cleanup();}}
 /** 点击前后账号代次、来源映射和页面绑定变化，都必须由实际现有Runtime guard拦截。 */
 static void retryClickStale()throws Exception{
  for(String kind:new String[]{"generation-before","generation-queued","account-index","remote","paused-view","removed-view","remote-removed-before","remote-removed-queued"})try{
   Ui target=initial("A");target.dragHistory();pump();consumeSnapshot(target);ChatLoadingCell cell=new ChatLoadingCell();cell.parent=target.chatListView;cell.position=target.chatAdapter.loadingUpRow;target.bindCodexHistoryLoadingCell(cell,cell.position);int before=target.controller.calls.size();
   if(kind.endsWith("-queued"))cell.tap();
   if(kind.startsWith("generation"))accountGeneration++;else if(kind.equals("account-index"))org.telegram.messenger.UserConfig.selectedAccount=1;else if(kind.equals("remote"))remoteIds.put(42L,"another");else if(kind.startsWith("remote-removed"))remoteIds.clear();else if(kind.equals("paused-view"))pauseHistoryView(7,target.codexHistoryViewToken);else historyViews.clear();
   if(!kind.endsWith("-queued"))cell.tap();ui();
   check(target.controller.calls.size()==before&&Utilities.globalQueue.ready.isEmpty()&&current.requests.size()==1,"stale retry reached controller "+kind);
   if(kind.equals("removed-view"))check(historyViews.isEmpty(),"stale retry recreated absent view");
  }finally{org.telegram.messenger.UserConfig.selectedAccount=0;cleanup();}
  System.out.println("PASS actual account generation/index/source/view guards before and after queued retry; no recreated view");
 }
 /** 原 Handler 队列唯一执行点才消费资格；搜索取消后同一按钮无须等待新回包或重绑。 */
 static void retryQueuedSearch()throws Exception{
  for(String kind:new String[]{"search-expand","visible-search","search-with-newer"})try{
   Ui target=initial("A");target.dragHistory();pump();consumeSnapshot(target);ChatLoadingCell cell=new ChatLoadingCell();cell.parent=target.chatListView;cell.position=target.chatAdapter.loadingUpRow;target.bindCodexHistoryLoadingCell(cell,cell.position);int before=target.controller.calls.size();
   if(kind.endsWith("newer"))target.forwardEndReached[0]=false;
   cell.tap();if(kind.equals("visible-search")){target.actionBar=new Bar();target.actionBar.visible=true;}else target.expandSearchUi();ui();
   check(target.controller.calls.size()==before&&Utilities.globalQueue.ready.isEmpty()&&!target.loading&&!target.loadingForward,"queued retry dispatched during search "+kind);
   check(target.codexHistoryOlderRetryRequired&&cell.retryAction!=null,"search cancellation consumed same button qualification "+kind);
   target.collapseSearchUi();if(target.actionBar!=null)target.actionBar.visible=false;
   current.older("old-older",page("B",false,null,null));cell.tap();cell.tap();ui();
   check(target.controller.calls.size()==before+1&&target.loading&&!target.loadingForward,"same button did not retry only older once "+kind);
   // 只观察按钮一次准入，不把随后的普通收页尾合法向新检查混入该点击断言。
   target.forwardEndReached[0]=true;pump();consumeSnapshot(target);
   check(current.requests.size()==2&&historyViews.get(7).window.before(0,10).size()==2&&!target.loading&&!target.codexHistoryOlderRetryRequired,"same button recovery lost rows "+kind);
  }finally{cleanup();}
  System.out.println("PASS queued real search/visible search/newer-eligible cancels; same retained button retries after collapse without rebinding");
 }
 /** 原 token/epoch/generation 守卫必须先于失败状态生效。 */
 static void retryStale()throws Exception{
  for(String kind:new String[]{"token","epoch","account","unknown-index"})try{
   Ui target=initial("A");target.dragHistory();pump();Event failed=events.stream().filter(e->e.type==NotificationCenter.messagesDidLoad).findFirst().get();events.clear();
   if(kind.equals("token"))target.codexHistoryViewToken++;else if(kind.equals("epoch"))target.codexHistoryEpoch="other";else if(kind.equals("account"))accountGeneration++;else failed.args[11]=999;
   target.consume(failed);check(!target.codexHistoryOlderRetryRequired&&target.loading,"stale failure mutated live state "+kind);
  }finally{cleanup();}
  System.out.println("PASS stale token/epoch/account/unknown waiting-index failures do not change current gate/loading");
 }
 /** 生命周期换段和显式回最新不会把旧失败许可带到新读取意图。 */
 static void retryLifecycle()throws Exception{
  for(String kind:new String[]{"pause","destroy","clear","latest"})try{
   Ui target=initial("A");target.dragHistory();pump();consumeSnapshot(target);check(target.codexHistoryOlderRetryRequired,"failure setup");
   if(kind.equals("pause"))target.pauseCodexHistoryView(false);else if(kind.equals("destroy"))target.pauseCodexHistoryView(true);else if(kind.equals("clear"))target.clearCodexHistoryScrollTargets();else target.requestCodexLatestHistory();
   check(!target.codexHistoryOlderRetryRequired,"gate survived "+kind);
  }finally{cleanup();}
  System.out.println("PASS pause/destroy/epoch-target clear/explicit latest reset failure gate");
 }
 static void shortInFlight()throws Exception{try{
  Ui target=initial("A");current.older("old-older",page("",true,"e1",null));draw(target);Utilities.globalQueue.next();historyQueue.next();
  target.pauseCodexHistoryView(false);pump();check(current.requests.size()==1&&historyViews.get(7).window.cursor.equals("old-older"),"pause allowed next scan or stale cursor commit");
  System.out.println("PASS pause after first in-flight response cancels remaining original operation");
 }finally{cleanup();}}
 """;

 private static final String CELL_BOUNDARY="""
            static class View {static final int VISIBLE=0,INVISIBLE=4,GONE=8;int position;Object parent;Object getParent(){return parent;}Object getContext(){return new Object();}void addView(View child,Object layout){child.parent=this;}}
            static int dp(int value){return value;}static class Gravity{static final int CENTER=1;}static class LayoutHelper{static final int MATCH_PARENT=-1;static Object createFrame(int...values){return values;}}
            static class Theme{static final int key_chat_serviceText=1,key_chat_serviceBackground=2,key_chat_serviceBackgroundSelector=3;static Object createSimpleSelectorRoundRectDrawable(int r,int c,int p){return new Object();}}
            static class Button extends View{interface Listener{void onClick(View v);}Listener listener;int visibility=VISIBLE;boolean enabled;CharSequence text,description;Button(Object context){}void setAllCaps(boolean v){}void setTextSize(int v){}void setMinWidth(int v){}void setMinHeight(int v){}void setPadding(int a,int b,int c,int d){}void setSingleLine(boolean v){}void setEllipsize(Object v){}void setGravity(int v){}void setOnClickListener(Listener v){listener=v;}void setText(CharSequence v){text=v;}void setContentDescription(CharSequence v){description=v;}void setTextColor(int v){}void setBackground(Object v){}void setEnabled(boolean v){enabled=v;}void setVisibility(int v){visibility=v;}void performClick(){if(enabled&&visibility==VISIBLE&&listener!=null)listener.onClick(this);}}
            static class FrameLayout extends View {int visibility=VISIBLE;/** 记录真实加载cell调用的Android可见性。 */ void setVisibility(int value){visibility=value;}}

 """;
 private static final String CONTINUE_CASES="""
 /** 真实原四页操作停止后绑定原Cell；不把正常进展伪装成失败。 */
 static Ui normalStopped()throws Exception{
  Ui t=initial("A");current.older("old-older",page("",true,"e1",null));for(int i=1;i<4;i++)current.older("e"+i,page("",true,"e"+(i+1),null));
  draw(t);pump();consumeSnapshot(t);draw(t);pump();
  check(current.requests.size()==4&&!t.loading&&!t.endReached[0]&&!t.codexHistoryOlderRetryRequired&&!t.codexHistoryReadingLocked,"normal four-page stop changed");
  check(historyViews.get(7).window.hasMore&&historyViews.get(7).window.cursor.equals("e4"),"normal stop lost remaining cursor");
  return t;
 }
 /** 只替代布局挂载位置，按钮绑定和listener都执行正式owner。 */
 static ChatLoadingCell bindBoundary(Ui t){ChatLoadingCell c=new ChatLoadingCell();c.parent=t.chatListView;c.position=t.chatAdapter.loadingUpRow;t.bindCodexHistoryLoadingCell(c,c.position);return c;}
 /** 自动停止后原按钮明确可继续，但绑定/布局/普通收页均不得自行恢复网络。 */
 static void continueBudget()throws Exception{try{
  Ui t=normalStopped();ChatLoadingCell c=bindBoundary(t);
  check(c.actionVisible()&&!c.shown(),"normal budget stop has no actionable continuation row");
  check("加载更早消息".contentEquals(c.retryButton.text)&&c.retryButton.text.equals(c.retryButton.description),"normal continuation falsely describes failure or lacks accessibility");
  int calls=t.controller.calls.size(),anchor=t.maxMessageId[0];long revision=t.codexHistoryViewRevision;String epoch=t.codexHistoryEpoch;
  for(int i=0;i<10;i++){t.resumeUi();t.originalLayoutComplete();t.checkScrollForLoad(false);draw(t);pump();}
  check(current.requests.size()==4&&t.controller.calls.size()==calls&&!t.codexHistoryReadingLocked,"idle binding/layout woke scans");
  current.older("e4",page("B",false,null,null));c.tap();c.tap();ui();
  check(t.controller.calls.size()==calls+1&&t.loading&&t.codexHistoryReadingLocked,"normal click not admitted exactly once");
  check(t.maxMessageId[0]==anchor&&t.codexHistoryViewRevision==revision&&t.codexHistoryEpoch.equals(epoch),"click reset anchor or page identity");
  pump();consumeSnapshot(t);check(current.requests.size()==5&&ids(historyViews.get(7).window).equals("A,B")&&!t.loading&&t.endReached[0],"continue failed to preserve/complete original history");
  t.bindCodexHistoryLoadingCell(c,c.position);check(!c.actionVisible()&&!c.shown(),"completed row retained action");
  System.out.println("PASS normal four-page stop -> accessible real Cell -> one original Controller admission; no automatic scan or anchor reset");
 }finally{cleanup();}}
 /** 正常加载按钮与失败文案分离，已在读旧仍沿原阅读意图。 */
 static void continueStates()throws Exception{try{
  Ui t=initial("A");ChatLoadingCell c=bindBoundary(t);check(c.actionVisible(),"idle latest missing action");int calls=t.controller.calls.size();ui();pump();check(t.controller.calls.size()==calls&&current.requests.isEmpty()&&!t.codexHistoryReadingLocked,"idle latest auto-dispatched");
  t.loading=true;t.bindCodexHistoryLoadingCell(c,c.position);check(c.shown()&&!c.actionVisible(),"inflight action visible");t.loading=false;
  t.codexHistoryOlderRetryRequired=true;t.bindCodexHistoryLoadingCell(c,c.position);check("未能加载更早消息 · 重试".contentEquals(c.retryButton.text),"failure text regressed");
  t.codexHistoryOlderRetryRequired=false;t.codexHistoryReadingLocked=true;t.bindCodexHistoryLoadingCell(c,c.position);check(c.actionVisible(),"reading intent disabled manual action");
  current.older("old-older",page("B",false,null,null));c.tap();ui();check(t.codexHistoryReadingLocked&&t.controller.calls.size()==calls+1,"existing reading click changed intent");pump();consumeSnapshot(t);
  System.out.println("PASS latest idle has no implicit intent; inflight/failure/reading labels and actions remain distinct");
 }finally{cleanup();}}
 /** 所有点击仍经过真实Runtime归属与代次守卫，不能用页面替身掩盖退出/切账号。 */
 static void continueStale()throws Exception{for(String kind:new String[]{"generation-before","generation-queued","account-index","remote","paused-view","removed-view","remote-removed-before","remote-removed-queued"})try{
  Ui t=normalStopped();ChatLoadingCell c=bindBoundary(t);check(c.actionVisible(),"stale fixture has no continuation");int calls=t.controller.calls.size();
  if(kind.endsWith("-queued"))c.tap();
  if(kind.startsWith("generation"))accountGeneration++;else if(kind.equals("account-index"))org.telegram.messenger.UserConfig.selectedAccount=1;else if(kind.equals("remote"))remoteIds.put(42L,"other");else if(kind.startsWith("remote-removed"))remoteIds.clear();else if(kind.equals("paused-view"))pauseHistoryView(7,t.codexHistoryViewToken);else historyViews.clear();
  if(!kind.endsWith("-queued"))c.tap();ui();check(t.controller.calls.size()==calls&&Utilities.globalQueue.ready.isEmpty()&&current.requests.size()==4,"stale normal continuation dispatched "+kind);
 }finally{org.telegram.messenger.UserConfig.selectedAccount=0;cleanup();}System.out.println("PASS 8 actual Runtime generation/account/source/view guard cases");}
 /** 普通边界同样在队列执行点拒绝搜索；取消后仍可点击原同一绑定。 */
 static void continueQueuedSearch()throws Exception{for(String kind:new String[]{"query","visible","newer"})try{
  Ui t=normalStopped();ChatLoadingCell c=bindBoundary(t);check(c.actionVisible(),"search fixture has no continuation");int calls=t.controller.calls.size();
  if(kind.equals("newer"))t.forwardEndReached[0]=false;c.tap();if(kind.equals("visible")){t.actionBar=new Bar();t.actionBar.visible=true;}else t.expandSearchUi();ui();
  check(t.controller.calls.size()==calls&&!t.loading&&!t.loadingForward&&!t.codexHistoryReadingLocked&&c.actionVisible(),"queued search consumed normal action/intent "+kind);
  t.collapseSearchUi();if(t.actionBar!=null)t.actionBar.visible=false;current.older("e4",page("B",false,null,null));c.tap();c.tap();ui();
  check(t.controller.calls.size()==calls+1&&t.loading&&!t.loadingForward,"same normal action failed after search collapse "+kind);t.forwardEndReached[0]=true;pump();consumeSnapshot(t);
 }finally{cleanup();}System.out.println("PASS 3 queued search cancellation and same-button recovery cases");}
 /** 已捕获按钮不得越过页面代次、方向、冻结、暂停或完整终态。 */
 static void continueUiGuards()throws Exception{for(String kind:new String[]{"pause","token","epoch","revision","account","detach","position","filtered","frozen","end","loading","search","visible-search"})try{
  Ui t=normalStopped();ChatLoadingCell c=bindBoundary(t);check(c.actionVisible(),"UI guard fixture missing action");int calls=t.controller.calls.size();
  if(kind.equals("pause"))t.paused=true;else if(kind.equals("token"))t.codexHistoryViewToken++;else if(kind.equals("epoch"))t.codexHistoryEpoch="other";else if(kind.equals("revision"))t.clearCodexHistoryScrollTargets();else if(kind.equals("account"))t.currentAccount++;else if(kind.equals("detach"))c.parent=null;else if(kind.equals("position"))c.position=-1;else if(kind.equals("filtered"))t.chatAdapter.isFiltered=true;else if(kind.equals("frozen"))t.chatAdapter.isFrozen=true;else if(kind.equals("end"))t.endReached[0]=true;else if(kind.equals("loading"))t.loading=true;else if(kind.equals("search"))t.expandSearchUi();else{t.actionBar=new Bar();t.actionBar.visible=true;}
  c.tap();ui();check(t.controller.calls.size()==calls&&current.requests.size()==4,"stale UI action dispatched "+kind);
 }finally{cleanup();}System.out.println("PASS 13 retained button UI owner/intent/terminal guards");}

 /** 非空latest终态不依赖Telegram endReached假设，不能留下正常继续按钮。 */
 static void continueCompleteLatest()throws Exception{try{
  TranscriptWindow complete=new TranscriptWindow();complete.prepend(page("A",false,null,"tail"));reset(complete);Ui t=new Ui();t.firstLoadMessages();pump();
  for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0)check((Boolean)e.args[9],"terminal fixture not genuine end");
  consumeSnapshot(t);ChatLoadingCell c=bindBoundary(t);check(!c.actionVisible(),"complete nonempty latest incorrectly offers more history");draw(t);pump();check(current.requests.isEmpty(),"complete latest fetched more");
  System.out.println("PASS actual complete nonempty latest hides normal continuation despite native endReached semantics");
 }finally{cleanup();}}
 /** 空且未加载的原Runtime回包不能凭end=false产生普通继续资格。 */
 static void continueUnknownEmpty()throws Exception{try{
  reset(new TranscriptWindow());DesktopConnection saved=current;current=null;Ui t=new Ui();t.firstLoadMessages();pump();
  for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0)check(!(Boolean)e.args[9]&&!metadata(e).loadFailed,"unknown empty fixture changed");
  consumeSnapshot(t);ChatLoadingCell c=bindBoundary(t);check(!c.actionVisible()&&!t.codexHistoryOlderRetryRequired,"unknown empty offered normal continuation");current=saved;
  System.out.println("PASS real unloaded empty/no connection stays unknown with no normal continuation");
 }finally{cleanup();}}
 /** 同epoch明确终态与新epoch接纳都撤销旧事实；旧成功通知不能把按钮重新打开。 */
 static void continueEpochTerminal()throws Exception{for(boolean newEpoch:new boolean[]{false,true})try{
  Ui t=normalStopped();ChatLoadingCell staleCell=bindBoundary(t);check(staleCell.actionVisible(),"epoch fixture has no action");
  t.waitingForLoad.add(91);loadMessages(0,42,15,0,7,2,91,0);pump();Event oldSuccess=events.stream().filter(e->e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0).findFirst().get();events.clear();t.consume(oldSuccess);ui();
  if(newEpoch){current.latest=page("Z",false,null,"tail-complete");watchConversation(0,42);pump();consumeSnapshot(t);pump();}
  else{histories.get(42L).prepend(page("A",false,null,"tail-complete"));t.waitingForLoad.add(92);loadMessages(0,42,15,0,7,2,92,0);pump();consumeSnapshot(t);}
  int rpcBefore=current.requests.size();
  ChatLoadingCell terminal=bindBoundary(t);check(!t.codexHistoryOlderContinueAvailable && (!terminal.actionVisible() || newEpoch && terminal.retryButton.text.toString().contains("本地记录")),"terminal latest kept network continuation newEpoch="+newEpoch);
  // 同一旧请求已完成后重复送达；新epoch时先由真实page身份拒绝。
  t.consume(oldSuccess);t.bindCodexHistoryLoadingCell(terminal,terminal.position);
  check(!t.codexHistoryOlderContinueAvailable && (!terminal.actionVisible() || newEpoch && terminal.retryButton.text.toString().contains("本地记录")),"late old success reopened network continuation newEpoch="+newEpoch);
  pump();check(current.requests.size()==rpcBefore,"terminal local boundary caused RPC");
  int calls=t.controller.calls.size();staleCell.tap();ui();check(t.controller.calls.size()==calls,"stale retained normal button dispatched after terminal");
 }finally{cleanup();}System.out.println("PASS same/new epoch terminal facts replace old availability; duplicate/old success cannot reopen it");}
 /** 生命周期撤销只影响新按钮的事实，不能重开原自动预算或丢失旧记录。 */
 static void continueFactReset()throws Exception{for(String kind:new String[]{"clear","pause","destroy","latest"})try{
  Ui t=normalStopped();ChatLoadingCell c=bindBoundary(t);check(c.actionVisible(),"reset fixture missing action");int rows=historyViews.get(7).window.before(0,50).size();
  if(kind.equals("clear"))t.clearCodexHistoryScrollTargets();else if(kind.equals("pause"))t.pauseCodexHistoryView(false);else if(kind.equals("destroy"))t.pauseCodexHistoryView(true);else {t.requestCodexLatestHistory();pump();consumeSnapshot(t);}
  if(!kind.equals("latest")){t.bindCodexHistoryLoadingCell(c,c.position);check(!c.actionVisible(),"reset retained normal fact "+kind);}
  check(histories.get(42L).before(0,50).size()==rows,"reset changed history data "+kind);
 }finally{cleanup();}System.out.println("PASS clear/pause/destroy/latest keep data and bound action facts");}
 /** 回最新先撤销已排队的旧向按钮，不能在原UI任务执行时重新锁阅读。 */
 static void continueQueuedLatest()throws Exception{try{
  Ui t=normalStopped();ChatLoadingCell c=bindBoundary(t);check(c.actionVisible(),"latest-cancel fixture missing action");int calls=t.controller.calls.size();
  c.tap();t.requestCodexLatestHistory();ui();
  check(t.controller.calls.size()==calls&&!t.loading&&!t.codexHistoryReadingLocked,"queued normal click survived explicit latest and relocked reading");
  check(current.requests.size()==4,"latest cancel directly scanned history");
  pump();consumeSnapshot(t);ChatLoadingCell fresh=bindBoundary(t);check(fresh.actionVisible(),"real accepted latest did not restore known continuation");
  System.out.println("PASS queued old button canceled by explicit latest; only accepted snapshot restores continuation");
 }finally{cleanup();}}
 """;
}
