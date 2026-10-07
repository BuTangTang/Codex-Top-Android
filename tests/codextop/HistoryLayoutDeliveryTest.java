package com.butang.codextop;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import java.nio.file.*;
/** 实际收页前缀、Runtime段握手、RecyclerView通知守卫；只替代Android绘制和消息循环。 */
public final class HistoryLayoutDeliveryTest {
 public static void main(String[] args)throws Exception{
  Path src=Path.of("TMessagesProj/src/main/java"),chatPath=args.length>0?Path.of(args[0]):src.resolve("org/telegram/ui/ChatActivity.java"),export=Files.createTempFile("layout-delivery-", ".java");
  try{
   HistoryLocalArchiveTest.main(new String[]{chatPath.toString(),"",export.toString()});
   String base=Files.readString(export).replace("RuntimeLatestSegmentProbe.ownsConversation","RuntimeLatestSegmentProbe.actualOwnsConversation");
   var host=StaticJavaParser.parse("class Host {"+base+"}").getClassByName("Host").orElseThrow();
   var chat=StaticJavaParser.parse(chatPath).getClassByName("ChatActivity").orElseThrow();
   var ui=type(host,"Ui");
   var actualUtils=StaticJavaParser.parse(src.resolve("org/telegram/messenger/AndroidUtilities.java")).getClassByName("AndroidUtilities").orElseThrow();
   var uiUtils=type(ui,"AndroidUtilities");uiUtils.getMethodsByName("runOnUIThread").forEach(m->m.remove());
   for(var m:actualUtils.getMethodsByName("runOnUIThread"))uiUtils.addMember(m.clone());
   uiUtils.addMember(StaticJavaParser.parseBodyDeclaration("static class ApplicationLoader{static final Handler applicationHandler=new Handler();} "));
   uiUtils.addMember(StaticJavaParser.parseBodyDeclaration("static class Handler{void post(Runnable r){RuntimeLatestSegmentProbe.AndroidUtilities.ui.postRunnable(r);}void postDelayed(Runnable r,long d){RuntimeLatestSegmentProbe.AndroidUtilities.ui.postRunnable(r,d);}}"));
   var consume=ui.getMethodsByName("consume").get(0);
   consume.setName("didReceivedNotification_messagesDidLoad");consume.getParameters().clear();consume.addParameter("int","id");consume.addParameter("int","account");consume.addParameter(new com.github.javaparser.ast.body.Parameter(StaticJavaParser.parseType("Object"),"received").setVarArgs(true).setFinal(true));
   consume.setBody(StaticJavaParser.parseBlock(consume.getBody().orElseThrow().toString().replace("event.type","id").replace("event.args","received")));
   var actual=chat.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);int pos=0;
   for(var st:actual.getBody().orElseThrow().getStatements()){
    if(st.toString().startsWith("Object[] args = prepareCodexHistoryPage"))break;
    consume.getBody().orElseThrow().addStatement(pos++,StaticJavaParser.parseStatement(st.toString().replace("com.butang.codextop.CodexRuntime","RuntimeLatestSegmentProbe")));
   }
   ui.addMember(StaticJavaParser.parseBodyDeclaration("void consume(Event e){didReceivedNotification_messagesDidLoad(e.type,currentAccount,e.args);}"));
   ui.getMethodsByName("clearChatData").get(0).getBody().orElseThrow().addStatement("chatAdapter.notifyDataSetChanged();");
   var cbody=consume.getBody().orElseThrow();int checkIndex=-1;
   for(int i=0;i<cbody.getStatements().size();i++)if(cbody.getStatement(i).toString().equals("checkScrollForLoad(false);")){checkIndex=i;break;}
   if(checkIndex<0)throw new AssertionError("actual end guard not found");cbody.addStatement(checkIndex,StaticJavaParser.parseStatement("chatAdapter.notifyDataSetChanged();"));
   type(ui,"FileLog").addMember(StaticJavaParser.parseBodyDeclaration("static void e(Exception e){layoutErrors++;}"));
   // 实际Adapter只在super通知处捕获异常；由真实RecyclerView观察者拒绝布局期间刷新。
   host.addMember(StaticJavaParser.parseBodyDeclaration("static class AdapterBase{Ui owner;void notifyDataSetChanged(){if(owner==null)return;owner.chatListView.owner=owner;owner.chatListView.onChanged();}}"));
   var adapter=type(host,"Adapter");adapter.addExtendedType("AdapterBase");
   var notify=chat.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("notifyDataSetChanged")&&m.getParameters().size()==1).filter(m->m.toString().contains("super.notifyDataSetChanged()")).findFirst().orElseThrow();
   var attempt=notify.findAll(TryStmt.class).stream().filter(v->v.getTryBlock().toString().contains("super.notifyDataSetChanged()")).findFirst().orElseThrow();
   adapter.addMember(StaticJavaParser.parseBodyDeclaration("void notifyDataSetChanged(){"+attempt.toString().replace("FileLog.e(e)","Ui.FileLog.e(e)")+"}"));
   var rvSource=StaticJavaParser.parse(src.resolve("androidx/recyclerview/widget/RecyclerView.java")).getClassByName("RecyclerView").orElseThrow();var rv=type(host,"RecyclerListView");
   rv.addMember(rvSource.getMethodsByName("assertNotInLayoutOrScroll").get(0).clone());
   var observer=rvSource.getMembers().stream().filter(m->m.isClassOrInterfaceDeclaration()&&m.asClassOrInterfaceDeclaration().getNameAsString().equals("RecyclerViewDataObserver")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();var onChanged=observer.getMethodsByName("onChanged").get(0).clone();onChanged.getAnnotations().clear();rv.addMember(onChanged);
   for(var d:StaticJavaParser.parse("class Extra {"+RV_STUBS+"}").getType(0).getMembers())rv.addMember(d.clone());
   host.addMember(StaticJavaParser.parseBodyDeclaration("static int layoutErrors;"));host.addMember(StaticJavaParser.parseBodyDeclaration("static class BuildVars{static boolean DEBUG_VERSION=false;}"));host.addMember(StaticJavaParser.parseBodyDeclaration("static class Log{static void w(String a,String b,Exception e){}}"));
   String methods=host.getMembers().stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
   RuntimeLatestSegmentTest.runScenarios(src.resolve("com/butang/codextop/CodexRuntime.java"),methods+CASES,args.length>1?args[1]:"layoutReturn(\"layout\");layoutReturn(\"plain\");for(String x:new String[]{\"pause\",\"token\",\"account\",\"generation\",\"session\",\"remote\",\"list\",\"selection\",\"multiselect\",\"intent\"})layoutReturn(x);layoutNormalPage(false);layoutNormalPage(true);layoutAround();for(String x:new String[]{\"guid\",\"mode\",\"telegram\",\"raw\"})layoutControl(x);");
  }finally{Files.deleteIfExists(export);}
 }
 static ClassOrInterfaceDeclaration type(ClassOrInterfaceDeclaration parent,String name){return parent.getMembers().stream().filter(x->x.isClassOrInterfaceDeclaration()&&x.asClassOrInterfaceDeclaration().getNameAsString().equals(name)).findFirst().orElseThrow().asClassOrInterfaceDeclaration();}
 static final String RV_STUBS="""
 Ui owner;int mDispatchScrollCounter;static final String TAG="synthetic";String exceptionLabel(){return "";}State mState=new State();Helper mAdapterHelper=new Helper();static class State{boolean mStructureChanged;}static class Helper{boolean hasPendingUpdates(){return false;}void logNotify(String s){}}ArrayList<MessageObject> drawn=new ArrayList<>();void processDataSetCompletelyChanged(boolean x){drawn.clear();drawn.addAll(owner.messages);}void requestLayout(){}
 """;
 static final String CASES="""
 static Event actualDelivery(int type){Event found=null;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0&&(Integer)e.args[8]==type)found=e;check(found!=null,"missing actual type "+type);events.clear();return found;}
 static void layoutNormalPage(boolean older)throws Exception{try{
  reset(cache(numbered("P",35)));layoutErrors=0;Ui t=new Ui();t.chatAdapter.owner=t;t.firstLoadMessages();pump();Event page=actualDelivery(2);
  if(older){t.consume(page);pump();events.clear();t.dragHistory();pump();page=actualDelivery(0);}
  int prior=t.messages.size(),waiting=t.waitingForLoad.size();t.chatListView.computing=true;t.consume(page);check(t.messages.size()==prior&&t.waitingForLoad.size()==waiting&&layoutErrors==0,"ordinary delivery consumed in layout");check(AndroidUtilities.ui.ready.size()==1,"ordinary delivery did not queue once");t.chatListView.computing=false;ui();check(t.messages.size()>prior&&t.chatListView.drawn.size()==t.messages.size()&&layoutErrors==0,"ordinary page failed");System.out.println("PASS layout ordinary type="+(older?0:2));
 }finally{cleanup();}}
 static void layoutAround()throws Exception{try{
  TranscriptWindow old=cache(numbered("O",44)),root=old.acceptLatest(page(numbered("N",6),true,"new-older","tail"));reset(root);layoutErrors=0;Ui t=new Ui();t.chatAdapter.owner=t;t.firstLoadMessages();pump();consumeSnapshot(t);events.clear();current.requests.clear();
  ChatLoadingCell up=bindBoundary(t);up.tap();pump();int pick=-1;for(int i=0;i<t.lastDialog.labels.length;i++)if(t.lastDialog.labels[i].toString().endsWith(" · 44条"))pick=i;check(pick>=0,"no old segment");t.lastDialog.tap(pick);pump();Event page=actualDelivery(3);int waiting=t.waitingForLoad.size();t.chatListView.computing=true;t.consume(page);check(t.messages.isEmpty()&&t.waitingForLoad.size()==waiting&&layoutErrors==0,"around delivered within layout");t.chatListView.computing=false;ui();check(!t.messages.isEmpty()&&t.chatListView.drawn.size()==t.messages.size()&&layoutErrors==0,"around not drawn");check(current.requests.isEmpty(),"around added network");System.out.println("PASS layout around type=3");
 }finally{cleanup();}}
 static void layoutControl(String kind)throws Exception{try{
  reset(cache("A,B"));layoutErrors=0;Ui t=new Ui();t.chatAdapter.owner=t;t.firstLoadMessages();pump();Event page=actualDelivery(2);Object[] args=page.args.clone();if(kind.equals("guid"))args[10]=99;if(kind.equals("mode"))args[14]=1;if(kind.equals("telegram"))remoteIds.clear();if(kind.equals("raw"))args=java.util.Arrays.copyOf(args,15);t.chatListView.computing=true;t.consume(new Event(page.type,args));if(kind.equals("telegram"))check(!t.messages.isEmpty()&&layoutErrors==1,"ordinary Telegram immediate notify semantics changed");else check(AndroidUtilities.ui.ready.isEmpty(),"non-Codex notification gained queue "+kind);System.out.println("PASS layout scope "+kind+" originalCaught="+layoutErrors);
 }finally{cleanup();}}
 static String numbered(String prefix,int n){StringBuilder s=new StringBuilder();for(int i=1;i<=n;i++){if(i>1)s.append(',');s.append(prefix).append(i);}return s.toString();}
 static void layoutReturn(String kind)throws Exception{DesktopConnection saved=null;try{
  TranscriptWindow old=cache(numbered("O",44)),root=old.acceptLatest(page(numbered("N",6),true,"new-older","latest-tail"));reset(root);layoutErrors=0;Ui t=new Ui();t.chatAdapter.owner=t;t.firstLoadMessages();pump();consumeSnapshot(t);events.clear();current.requests.clear();saved=current;current=null;desktopConnections.clear();
  ChatLoadingCell up=bindBoundary(t);up.tap();pump();int pick=-1;for(int i=0;i<t.lastDialog.labels.length;i++)if(t.lastDialog.labels[i].toString().endsWith(" · 44条"))pick=i;check(pick>=0,"old44 missing");t.lastDialog.tap(pick);pump();consumeSnapshot(t);pump();check(historyViews.get(7).window==old,"not selected44");int oldShown=t.messages.size();check(oldShown>6&&t.chatListView.drawn.size()==oldShown,"old not rendered");
  t.requestCodexLatestHistory();pump();Event found=null;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0&&(Integer)e.args[8]==2)found=e;check(found!=null,"latest not offered");events.clear();Object[] received=found.args;Object[] before=received.clone();int waiting=t.waitingForLoad.size();RecyclerListView original=t.chatListView;
  original.computing=!kind.equals("plain");t.consume(found);
  if(kind.equals("layout")){System.out.println("layoutObserved model="+t.messages.size()+" drawn="+original.drawn.size()+" caught="+layoutErrors+" posted="+AndroidUtilities.ui.ready.size());check(historyViews.get(7).window==old&&t.messages.size()==oldShown,"layout delivery accepted before safe event");check(original.drawn.size()==oldShown&&layoutErrors==0,"layout attempted a notification");check(t.waitingForLoad.size()==waiting,"waiting consumed early");check(AndroidUtilities.ui.ready.size()==1,"expected exactly one queued delivery");}
  if(kind.equals("pause"))t.pauseCodexHistoryView(false);if(kind.equals("token")){t.pauseCodexHistoryView(false);t.resumeCodexHistoryView();}if(kind.equals("account"))t.currentAccount=1;if(kind.equals("generation"))accountGeneration++;if(kind.equals("session"))session=null;if(kind.equals("remote"))remoteIds.remove(42L);if(kind.equals("list"))t.chatListView=new RecyclerListView();if(kind.equals("selection"))t.selected=true;if(kind.equals("multiselect")){t.actionBar=new Bar();t.actionBar.selected=true;}if(kind.equals("intent"))requestLatestHistory(0,42,7,t.codexHistoryViewToken);
  original.computing=false;pump();check(java.util.Arrays.equals(received,before),"received mutated");
  if(kind.equals("layout")||kind.equals("plain"))check(historyViews.get(7).window==root&&t.messages.size()==6&&original.drawn.size()==6,"latest model/visible snapshot should be root6");
  else check(original.drawn.size()==oldShown,"cancelled delivery changed visible page "+kind);
  check(layoutErrors==0,"layout guard threw "+kind);check(saved.requests.isEmpty(),"return added network");System.out.println("PASS layout-delivery "+kind+" drawn="+original.drawn.size()+" errors="+layoutErrors);
 }finally{current=saved;cleanup();}}
 """;
}
