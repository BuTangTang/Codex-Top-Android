package com.butang.codextop;
import com.github.javaparser.StaticJavaParser;import com.github.javaparser.ParserConfiguration;import java.nio.file.*;
/** 组合实际 ChatActivity 首载/收页/布局入口、MessagesController 两重载及 Runtime/Window/Store。
 * 只替代 Android 几何和事件时序；数据为合成来源，存储使用 Runtime 专项独立临时目录。
 * args[0] 可指定前像，args[1] 可选择同一行为用例，args[2] 可导出实际提取体。 */
public final class HistoryLocalArchiveTest {
 /** 提取真实页面、布局和Controller入口；以合成Android事件驱动原Runtime进行回归。 */
 public static void main(String[] args)throws Exception{
  StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  Path source=Path.of("TMessagesProj/src/main/java");Path chatSource=args.length>0?Path.of(args[0]):source.resolve("org/telegram/ui/ChatActivity.java");var chat=StaticJavaParser.parse(chatSource).getClassByName("ChatActivity").orElseThrow();
  var mc=StaticJavaParser.parse(source.resolve("org/telegram/messenger/MessagesController.java")).getClassByName("MessagesController").orElseThrow();
  StringBuilder extra=new StringBuilder(CELL_BOUNDARY + LOCAL_PLATFORM);
  if(System.getProperty("archive.red", "").equals("button") || System.getProperty("archive.red", "").equals("passive")) {
   var before=StaticJavaParser.parse(Path.of(System.getProperty("archive.before"))).getClassByName("ChatActivity").orElseThrow();
   String method=System.getProperty("archive.red").equals("button")?"bindCodexHistoryLoadingCell":"checkScrollForLoad";
   chat.getMethodsByName(method).forEach(m->m.remove());
   before.getMethodsByName(method).forEach(m->chat.addMember(m.clone()));
  }
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
  for(var field:chat.getFields())if(field.getVariables().stream().anyMatch(v->(v.getNameAsString().startsWith("codexHistoryInitialFill") || v.getNameAsString().equals("codexHistoryOlderContinueAvailable") || v.getNameAsString().startsWith("codexLocalHistory"))))extra.append(field.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  for(String name:new String[]{"cancelCodexHistoryInitialFill","scheduleCodexHistoryInitialFill","canFillCodexHistoryInitialViewport","retryCodexHistoryInitialFillAfterLayout","canBridgeCodexHistoryInitialViewport","codexHistoryInitialFillMessageCount"})
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
  for(String name:new String[]{"resumeCodexHistoryView","firstLoadMessages","isCodexHistoryView","openCodexHistoryView","prepareCodexHistoryPage","isCodexHistoryScrollCurrent","checkScrollForLoad","canRetryCodexHistoryLoadingCell","bindCodexHistoryLoadingCell","retryCodexHistoryFromCell","isCodexHistoryRetryBindingCurrent","isCodexHistoryLoadingCell","lockCodexHistoryReading","pauseCodexHistoryView","requestCodexLatestHistory","hasCodexLocalHistoryBoundary","isCodexLocalHistoryCellCurrent","canBrowseCodexLocalHistory","cancelCodexLocalHistorySelection","isCodexLocalHistoryRequestCurrent","showCodexLocalHistory","codexLocalHistoryTime","showCodexLocalHistoryUnavailable","selectCodexLocalHistory","codexLocalHistoryEpochReset"})for(var method:chat.getMethodsByName(name))extra.append(method.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  // 两个实际adapter边界分支保持原end条件，仅Android其他行省略；不手填可点击位置。
  var rows=chat.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("updateRowsInternal")).findFirst().orElseThrow();
  var nonempty=rows.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(v->v.getCondition().toString().equals("!messages.isEmpty()")).findFirst().orElseThrow();
  extra.append("void updateActualBoundaryRows(){boolean isFiltered=chatAdapter.isFiltered,filteredEndReached=false,hideForwardEndReached=false,DISABLE_PROGRESS_VIEW=false,isComments=false;Object currentUser=null;int rowCount=0,loadingUpRow=-5,loadingDownRow=-5;if(").append(nonempty.getCondition()).append("){ ");
  for(var st:nonempty.getThenStmt().asBlockStmt().getStatements())if(st.isIfStmt()&&(st.asIfStmt().getThenStmt().toString().contains("loadingUpRow = rowCount++")||st.asIfStmt().getThenStmt().toString().contains("loadingDownRow = rowCount++")))extra.append(st);
  extra.append("} chatAdapter.loadingUpRow=loadingUpRow;chatAdapter.loadingDownRow=loadingDownRow;}");
  var receiver=chat.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
  var end=receiver.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(s->s.getCondition().toString().equals("messArr.size() < count && load_type != 3 && load_type != 4")).findFirst().orElseThrow();
  var check=receiver.findAll(com.github.javaparser.ast.stmt.ExpressionStmt.class).stream().filter(s->s.toString().equals("checkScrollForLoad(false);")).findFirst().orElseThrow();
  extra.append(CONSUME);
  // 消费真实等待编号守卫，迟到的同 token 失败不得污染已开始的新一次请求。
  for(var statement:receiver.getBody().orElseThrow().getStatements()){
   String value=statement.toString();
   if(value.startsWith("int queryLoadIndex =")||value.startsWith("boolean doNotRemoveLoadIndex;")||value.startsWith("if (queryLoadIndex < 0)")||value.startsWith("int index = waitingForLoad.indexOf")||value.startsWith("if (index == -1)"))extra.append(statement);
  }
  for(var statement:receiver.getBody().orElseThrow().getStatements())if(statement.isIfStmt()&&(statement.toString().contains("codexHistoryOlderRetryRequired = true") || statement.toString().contains("codexHistoryOlderContinueAvailable = !isEnd") || statement.toString().contains("codexLocalHistoryBoundary = boundary")))extra.append(statement.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  var firstLoading=receiver.getBody().orElseThrow().getStatements().stream().filter(v->v.isIfStmt()&&v.asIfStmt().getCondition().toString().equals("firstLoading")).findFirst().orElseThrow();
  extra.append(firstLoading);
  var firstDone=receiver.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(v->v.getCondition().toString().equals("first && messages.size() > 0")).findFirst().orElseThrow();
  var firstStatement=firstDone.getThenStmt().asBlockStmt().getStatement(0);
  extra.append("messages.addAll(messArr);for(MessageObject m:messArr)if(m.getId()>0)maxMessageId[0]=Math.min(maxMessageId[0],m.getId());if(!messages.isEmpty())messagesByDays.put(\"synthetic\",new Object());").append(end).append("loading=false;if(").append(firstDone.getCondition()).append("){ ").append(firstStatement).append("}").append(check);
  for(var statement:receiver.getBody().orElseThrow().getStatements())
   if(statement.isIfStmt()&&statement.toString().contains("codexHistoryInitialFillToken ="))extra.append(statement.toString().replace("com.butang.codextop.CodexRuntime", "RuntimeLatestSegmentProbe"));
  extra.append("} void actualInfoEntry(){AlertDialog.Builder builder=new AlertDialog.Builder(getParentActivity(),themeDelegate);");
  for(var st:chat.getMethodsByName("showCodexConversationInfo").get(0).getBody().orElseThrow().getStatements())
   if(st.isIfStmt()&&st.toString().contains("builder.setItems"))extra.append(st);
  extra.append("showDialog(builder.create());} void dragHistory(){");
  var drag=chat.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().filter(v->v.getCondition().toString().equals("newState == RecyclerView.SCROLL_STATE_DRAGGING")&&v.toString().contains("lockCodexHistoryReading()")).findFirst().orElseThrow();
  for(var statement:drag.getThenStmt().asBlockStmt().getStatements()){
   if(statement.toString().equals("pollHintCell = null;"))break;extra.append(statement);
  }
  extra.append("}\n}\n").append(CONTROLLER);
  for(var method:mc.getMethodsByName("loadMessages"))if(method.getParameters().size()==16||method.getParameters().size()==20){
   var extracted=method.clone();if(method.getParameters().size()==20)extracted.getBody().orElseThrow().addStatement(0,StaticJavaParser.parseStatement("calls.add(new Object[]{dialogId,count,max_id,classGuid,load_type,loadIndex,mode});"));
   extra.append(extracted.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe"));
  }
  extra.append("}\n").append(Boolean.getBoolean("archive.adjacent") ? adjacentCases("CASES") : CASES).append(Boolean.getBoolean("archive.adjacent") ? adjacentCases("CONTINUE_CASES") : CONTINUE_CASES).append(LOCAL_CASES);
  var runtime=StaticJavaParser.parse(source.resolve("com/butang/codextop/CodexRuntime.java")).getClassByName("CodexRuntime").orElseThrow();
  for(String name:new String[]{"isHistoryViewCurrent","isHistoryBridgeViewCurrent"})for(var method:runtime.getMethodsByName(name))extra.append(method);
  // UI与Controller必须使用真实归属映射，不能用共享夹具的固定 dialog=42 替身掩盖账号删除。
  extra.append(runtime.getMethodsByName("ownsConversation").get(0).clone().setName("actualOwnsConversation"));
  if(args.length>2)Files.writeString(Path.of(args[2]),extra.toString());
  RuntimeLatestSegmentTest.runScenarios(source.resolve("com/butang/codextop/CodexRuntime.java"),extra.toString().replace("RuntimeLatestSegmentProbe.ownsConversation", "RuntimeLatestSegmentProbe.actualOwnsConversation"),args.length>1?args[1]:"localBrowserMain();localBrowserTerminal();localBrowserEmpty();localBrowserPassive();localBrowserStale();localBrowserLatest();localBrowserMenuDismiss();localBrowserCellStale();localBrowserOfflineResume();localBrowserExplicitContinue();localBrowserCompleteArchive();localBrowserQueuedContinueSearch();");
 }
 /** 相邻回归直接复用89原场景字符串，断言和输入不复制改写。 */
 private static String adjacentCases(String name)throws Exception{var field=Class.forName("com.butang.codextop.HistoryContinueBoundaryTest").getDeclaredField(name);field.setAccessible(true);return (String)field.get(null);}
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
  Object themeDelegate;AlertDialog lastDialog;Object getParentActivity(){return new Object();}AlertDialog showDialog(AlertDialog d){return showDialog(d,null);}AlertDialog showDialog(AlertDialog d,AlertDialog.Dismiss listener){lastDialog=d;d.setOnDismissListener(listener);return d;}
  HashMap<Integer,Object>[] messagesDict=new HashMap[]{new HashMap<>(),new HashMap<>()};HashMap<Integer,Object> messagesByDaysSorted=new HashMap<>(),groupedMessagesMap=new HashMap<>();boolean threadMessageAdded;

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
  void restoreCodexHistoryBookmark(){throw new AssertionError("unexpected locked bookmark restoration");}void finishFragment(){}boolean hasTextSelection(){return selected;}void updatePagedownButtonVisibility(boolean x){}void updateCodexHistoryLoadingCells(){}void clearCodexHistoryBookmark(){}void clearChatData(boolean x){messages.clear();messagesByDays.clear();maxMessageId[0]=Integer.MAX_VALUE;applyRealClearLoadState(x);waitingForLoad.clear();}
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
 /** 只驱动真实队列和页面收页，不复制Window或分页算法。 */
 static void consumeSnapshot(Ui target){for(Event e:new ArrayList<Event>(events))target.consume(e);events.clear();ui();}
 static void draw(Ui target){target.chatListView.preDraw();ui();}
 """;

 private static final String CELL_BOUNDARY="""
            static class View {static final int VISIBLE=0,INVISIBLE=4,GONE=8;int position;Object parent;Object getParent(){return parent;}Object getContext(){return new Object();}void addView(View child,Object layout){child.parent=this;}}
            static int dp(int value){return value;}static class Gravity{static final int CENTER=1;}static class LayoutHelper{static final int MATCH_PARENT=-1;static Object createFrame(int...values){return values;}}
            static class Theme{static final int key_chat_serviceText=1,key_chat_serviceBackground=2,key_chat_serviceBackgroundSelector=3;static Object createSimpleSelectorRoundRectDrawable(int r,int c,int p){return new Object();}}
            static class Button extends View{interface Listener{void onClick(View v);}Listener listener;int visibility=VISIBLE;boolean enabled;CharSequence text,description;Button(Object context){}void setAllCaps(boolean v){}void setTextSize(int v){}void setMinWidth(int v){}void setMinHeight(int v){}void setPadding(int a,int b,int c,int d){}void setSingleLine(boolean v){}void setEllipsize(Object v){}void setGravity(int v){}void setOnClickListener(Listener v){listener=v;}void setText(CharSequence v){text=v;}void setContentDescription(CharSequence v){description=v;}void setTextColor(int v){}void setBackground(Object v){}void setEnabled(boolean v){enabled=v;}void setVisibility(int v){visibility=v;}void performClick(){if(enabled&&visibility==VISIBLE&&listener!=null)listener.onClick(this);}}
            static class FrameLayout extends View {int visibility=VISIBLE;/** 记录真实加载cell调用的Android可见性。 */ void setVisibility(int value){visibility=value;}}

 """;
 private static final String CONTINUE_CASES="""
 /** 几何边界只挂载真实cell；绑定文案和listener执行原方法。 */
 static ChatLoadingCell bindBoundary(Ui t){t.updateActualBoundaryRows();ChatLoadingCell c=new ChatLoadingCell();c.parent=t.chatListView;c.position=t.chatAdapter.loadingUpRow;t.bindCodexHistoryLoadingCell(c,c.position);return c;}
 """;
 private static final String LOCAL_PLATFORM="""
 static final class AlertDialog {
  interface Click {void onClick(Object dialog,int which);}interface Dismiss{void onDismiss(Object dialog);}
  CharSequence[] labels;Click items;Dismiss dismiss;String title,message;boolean dismissed;
  void setOnDismissListener(Dismiss l){dismiss=l;}void close(){dismissed=true;if(dismiss!=null)dismiss.onDismiss(this);}
  void tap(int index){items.onClick(this,index);close();}
  static final class Builder{AlertDialog dialog=new AlertDialog();Builder(Object a,Object b){}Builder setTitle(String s){dialog.title=s;return this;}Builder setMessage(String s){dialog.message=s;return this;}Builder setNegativeButton(String s,Click c){return this;}Builder setPositiveButton(String s,Click c){return this;}Builder setItems(CharSequence[] labels,Click c){dialog.labels=labels;dialog.items=c;return this;}AlertDialog create(){return dialog;}}
 }
 """;
 private static final String LOCAL_CASES="""
 /** 真实已保存两段，旧段hasMore=true且短到会触发原receiver尾部；最新端可以已结束。 */
 static Ui localBrowserFixture(boolean complete, boolean empty)throws Exception {
  TranscriptWindow old=cache("A,B");TranscriptWindow root=old.acceptLatest(page(empty?"":"Y,Z",!complete,complete?null:"new-older","latest-tail"));reset(root);
  Ui t=new Ui();t.firstLoadMessages();pump();consumeSnapshot(t);events.clear();current.requests.clear();
  check(historyViews.get(7).window==root,"fixture root mismatch");return t;
 }
 /** 通过合成段条数定位实际生成的短标签，不依赖前缀或时区。 */
 static int localSegmentIndex(AlertDialog dialog){check(dialog!=null&&dialog.labels!=null,"actual segment menu missing");for(int i=0;i<dialog.labels.length;i++)if(dialog.labels[i].toString().endsWith(" · 2条"))return i;throw new AssertionError("actual synthetic segment absent");}
 /** 既有继续和回最新动作以原文案定位，不伪造菜单回调。 */
 static int localMenuIndex(AlertDialog dialog,String prefix){check(dialog!=null&&dialog.labels!=null,"actual menu missing");for(int i=0;i<dialog.labels.length;i++)if(dialog.labels[i].toString().startsWith(prefix))return i;throw new AssertionError("actual menu item missing "+prefix);}
 /** 从真实加载行点击进入真实AlertDialog，再由同一descriptor精确pin/accept和原type3/receiver完成。 */
 static void openLocal(Ui t)throws Exception{ChatLoadingCell cell=bindBoundary(t);check(cell.actionVisible()&&cell.retryButton.text.toString().contains("本地记录"),"retained archive has no actual local browser action");cell.tap();pump();AlertDialog dialog=t.lastDialog;dialog.tap(localSegmentIndex(dialog));pump();consumeSnapshot(t);pump();}
 static void localBrowserMain()throws Exception{try{
  Ui t=localBrowserFixture(false,false);TranscriptWindow root=historyViews.get(7).window;ArrayList<MessageObject> original=new ArrayList<>(t.messages);int calls=t.controller.calls.size();openLocal(t);
  check(ids(historyViews.get(7).window).equals("B,A")&&t.messages.size()==2&&t.messages.get(0).messageOwner.params.get("codexSourceId").equals("B"),"browser selected wrong native-id-colliding segment");
  check(current.requests.isEmpty()&&!t.loading&&!t.loadingForward&&t.codexLocalHistoryAwaitingInput&&t.codexHistoryReadingLocked,"local browser auto-fetched or stuck loading");
  for(int i=0;i<12;i++){t.originalLayoutComplete();t.checkScrollForLoad(true);draw(t);pump();consumeSnapshot(t);}
  check(current.requests.isEmpty()&&t.controller.calls.size()==calls+2,"passive layout/newer changed local around");
  check(root.findSegment("A",null)!=null,"local browse pruned pinned old segment");System.out.println("PASS actual cell -> dialog -> descriptor -> accept -> around + receiver/layout; 0RPC, old identities exact");
 }finally{cleanup();}}
 /** latest已完整依旧能浏览不连续旧段；真正完整单段仍维持89无额外入口行。 */
 static void localBrowserTerminal()throws Exception{try{Ui t=localBrowserFixture(true,false);t.endReached[0]=true;openLocal(t);check(current.requests.isEmpty()&&t.messages.size()==2,"complete latest hid local archive");System.out.println("PASS complete latest retains local archive entry without fake end");}finally{cleanup();}
 try{TranscriptWindow root=new TranscriptWindow();root.prepend(page("A,B",false,null,"tail"));reset(root);Ui t=new Ui();t.firstLoadMessages();pump();consumeSnapshot(t);ChatLoadingCell c=bindBoundary(t);check(!c.actionVisible()&&!t.hasCodexLocalHistoryBoundary(true),"single complete segment manufactured boundary");System.out.println("PASS complete single segment unchanged");}finally{cleanup();}}
 /** 已绑定的空current通过原会话信息item可查看归档，不伪造正文或结束标志。 */
 static void localBrowserEmpty()throws Exception{try{Ui t=localBrowserFixture(true,true);check(t.messages.isEmpty()&&t.codexHistoryEpoch!=null,"empty root not bound");t.actualInfoEntry();t.lastDialog.tap(localMenuIndex(t.lastDialog,"查看本地记录"));pump();t.lastDialog.tap(localSegmentIndex(t.lastDialog));pump();consumeSnapshot(t);pump();check(t.messages.size()==2&&current.requests.isEmpty(),"empty current local entry failed");System.out.println("PASS empty current actual info action -> local archive 0RPC");}finally{cleanup();}}
 /** 原around收页尾在旧段hasMore时不得偷偷联网，也不从loadingDownRow被动补新。 */
 static void localBrowserPassive()throws Exception{try{Ui t=localBrowserFixture(false,false);openLocal(t);check(current.requests.isEmpty(),"local around tail unexpectedly issued older network");int calls=t.controller.calls.size();t.forwardEndReached[0]=false;t.checkScrollForLoad(false);pump();check(t.controller.calls.size()==calls,"local around tail silently queued newer");current.older("old-older",page("Q",false,null,null));t.forwardEndReached[0]=true;t.dragHistory();pump();consumeSnapshot(t);check(current.requests.equals(Collections.singletonList("older:old-older"))&&!t.codexLocalHistoryAwaitingInput,"real dragging did not resume original older path");System.out.println("PASS passive both directions blocked; real dragging retains original network action");}finally{cleanup();}}
 /** UI在pin排队期间变化只取消本次；messages与Runtime.window都保持原对象。 */
 static void localBrowserStale()throws Exception{for(boolean pinned:new boolean[]{false,true})for(String kind:new String[]{"search","selection","pause","destroy","latest","session","account","root","revision","machine"})try{
  Ui t=localBrowserFixture(false,false);TranscriptWindow original=historyViews.get(7).window;MessageObject first=t.messages.get(0);ChatLoadingCell c=bindBoundary(t);c.tap();pump();t.lastDialog.tap(localSegmentIndex(t.lastDialog));
  if(pinned){Utilities.globalQueue.next();check(historyViews.get(7).bookmarkWindow!=null,"fixture did not pin before UI race");}
  if(kind.equals("search"))t.expandSearchUi();else if(kind.equals("selection"))t.selected=true;else if(kind.equals("pause"))t.pauseCodexHistoryView(false);else if(kind.equals("destroy"))t.pauseCodexHistoryView(true);else if(kind.equals("latest"))t.requestCodexLatestHistory();else if(kind.equals("session"))session=new PasswordLogin.Session();else if(kind.equals("account"))accountGeneration++;else if(kind.equals("root"))publishHistoryRoot(42,original.acceptLatest(page("Q",false,null,"newtail")));else if(kind.equals("revision"))t.clearCodexHistoryScrollTargets();else dialogMachines.put(42L,"different");
  pump();check(t.messages.get(0)==first,"stale UI cleared original messages "+kind);HistoryView v=historyViews.get(7);check(v==null||v.window==original,"stale UI switched owner "+kind);check(v==null||v.bookmarkWindow==null,"stale UI left pin "+kind);check(current.requests.isEmpty(),"stale selection network "+kind);System.out.println("PASS queued UI selection canceled pin="+pinned+" "+kind);
 }finally{cleanup();}}
 /** 本地段的原回最新入口释放本地查看门禁，旧目录不能反向再次选段。 */
 static void localBrowserLatest()throws Exception{try{Ui t=localBrowserFixture(true,false);openLocal(t);t.chatAdapter.loadingDownRow=1;ChatLoadingCell c=new ChatLoadingCell();c.parent=t.chatListView;c.position=1;t.bindCodexHistoryLoadingCell(c,1);check(c.actionVisible()&&c.retryButton.text.toString().contains("回到最新"),"local segment missing return");c.tap();pump();consumeSnapshot(t);pump();check(ids(historyViews.get(7).window).equals("Z,Y")&&!t.codexHistoryReadingLocked&&!t.codexLocalHistoryAwaitingInput&&current.requests.isEmpty(),"return latest changed original action");System.out.println("PASS original return latest from local boundary");}finally{cleanup();}}
 /** 系统返回/取消目录无选择、不换段、不留下pin。 */
 static void localBrowserMenuDismiss()throws Exception{try{Ui t=localBrowserFixture(false,false);TranscriptWindow from=historyViews.get(7).window;ChatLoadingCell c=bindBoundary(t);c.tap();pump();t.lastDialog.close();pump();check(t.codexLocalHistoryRequest==null&&historyViews.get(7).window==from&&historyViews.get(7).bookmarkWindow==null&&current.requests.isEmpty(),"menu cancel changed history");System.out.println("PASS menu dismiss preserves original page");}finally{cleanup();}}
 /** 绑定控件回最新后、复用位置后或父视图变化后不得再打开旧目录。 */
 static void localBrowserCellStale()throws Exception{for(String kind:new String[]{"latest","position","parent","epoch"})try{Ui t=localBrowserFixture(false,false);ChatLoadingCell c=bindBoundary(t);if(kind.equals("latest"))t.requestCodexLatestHistory();else if(kind.equals("position"))c.position=-1;else if(kind.equals("parent"))c.parent=null;else t.codexHistoryEpoch="different";c.tap();pump();check(t.lastDialog==null,"stale bound local cell reopened catalog "+kind);System.out.println("PASS stale cell "+kind);}finally{cleanup();}}
 /** 连接缺失时目录/选段/恢复仍本地，暂停不释放等待用户意图的门禁。 */
 static void localBrowserOfflineResume()throws Exception{try{Ui t=localBrowserFixture(false,false);DesktopConnection saved=current;current=null;desktopConnections.clear();openLocal(t);int calls=t.controller.calls.size();t.pauseCodexHistoryView(false);t.resumeCodexHistoryView();pump();t.originalLayoutComplete();t.checkScrollForLoad(false);draw(t);pump();check(t.messages.size()==2&&t.codexLocalHistoryAwaitingInput&&t.controller.calls.size()==calls&&saved.requests.isEmpty(),"offline pause/resume released local gate");current=saved;System.out.println("PASS offline local browse and pause/resume retain 0RPC");}finally{cleanup();}}
 /** 本地菜单保留原继续动作，明确点按才释放门禁且复用原50条旧向准入。 */
 static void localBrowserExplicitContinue()throws Exception{try{Ui t=localBrowserFixture(false,false);openLocal(t);check(t.codexHistoryOlderContinueAvailable,"local around known hasMore missing explicit continuation");ChatLoadingCell c=bindBoundary(t);c.tap();pump();current.older("old-older",page("Q",false,null,null));t.lastDialog.tap(localMenuIndex(t.lastDialog,"继续加载更早消息"));pump();consumeSnapshot(t);check(current.requests.equals(Collections.singletonList("older:old-older"))&&!t.codexLocalHistoryAwaitingInput,"explicit menu continue failed original path");System.out.println("PASS explicit menu continuation releases local gate only on admitted original request");}finally{cleanup();}}
 /** 真实完整旧段虽仍可回最新，不产生hasMore或改变原end状态。 */
 static void localBrowserCompleteArchive()throws Exception{try{TranscriptWindow old=new TranscriptWindow();old.prepend(page("A,B",false,null,"old-tail"));TranscriptWindow root=old.acceptLatest(page("Y,Z",false,null,"new-tail"));reset(root);Ui t=new Ui();t.firstLoadMessages();pump();consumeSnapshot(t);openLocal(t);check(!t.codexHistoryOlderContinueAvailable,"complete archive falsely offers more");ChatLoadingCell c=bindBoundary(t);c.tap();pump();for(CharSequence label:t.lastDialog.labels)check(!label.toString().contains("继续加载"),"complete archive menu offers network continuation");check(current.requests.isEmpty()&&old.complete&&!old.hasMore,"complete archive state changed");System.out.println("PASS real complete archive has no false continuation or mutated end state");}finally{cleanup();}}
 /** 明确继续仍在实际队列执行点裁定，排队后搜索不消费local-only或原继续资格。 */
 static void localBrowserQueuedContinueSearch()throws Exception{try{Ui t=localBrowserFixture(false,false);openLocal(t);ChatLoadingCell c=bindBoundary(t);c.tap();pump();int calls=t.controller.calls.size();t.lastDialog.tap(localMenuIndex(t.lastDialog,"继续加载更早消息"));t.expandSearchUi();ui();check(t.controller.calls.size()==calls&&t.codexLocalHistoryAwaitingInput&&t.codexHistoryOlderContinueAvailable&&current.requests.isEmpty(),"queued search consumed local continue intent");t.collapseSearchUi();t.retryCodexHistoryFromCell(c,0,t.codexHistoryViewToken,t.codexHistoryEpoch,t.codexHistoryViewRevision);current.older("old-older",page("Q",false,null,null));pump();consumeSnapshot(t);check(current.requests.size()==1&&!t.codexLocalHistoryAwaitingInput,"retained local continue failed after search");System.out.println("PASS queued explicit continue canceled by search without releasing local-only");}finally{cleanup();}}
 """;
}
