package com.butang.codextop;
import com.github.javaparser.StaticJavaParser;import com.github.javaparser.ast.body.*;import com.github.javaparser.ast.stmt.*;import java.nio.file.*;
/** 真实本地选段、Runtime页交付、终态删行、Adapter行计算及Recycler偏移校验的合成组合。 */
public final class HistoryLocalBoundaryNotificationTest {
 public static void main(String[] args)throws Exception{
  Path src=Path.of("TMessagesProj/src/main/java"),chatPath=args.length>0?Path.of(args[0]):src.resolve("org/telegram/ui/ChatActivity.java"),export=Files.createTempFile("boundary-notify-", ".java");
  try{
   HistoryLocalArchiveTest.main(new String[]{chatPath.toString(),"",export.toString()});String base=Files.readString(export).replace("RuntimeLatestSegmentProbe.ownsConversation","RuntimeLatestSegmentProbe.actualOwnsConversation");var host=StaticJavaParser.parse("class Host{"+base+"}").getClassByName("Host").orElseThrow();var chat=StaticJavaParser.parse(chatPath).getClassByName("ChatActivity").orElseThrow();var ui=type(host,"Ui");var receiver=chat.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
   // 原夹具无原生增量通知；在真实消费路径补入本次需要的完整终态删除分支。
   var type1=receiver.getBody().orElseThrow().getStatements().stream().filter(x->x.isIfStmt()&&x.asIfStmt().getCondition().toString().equals("load_type == 1")&&x.toString().contains("loadingForward = false")).findFirst().orElseThrow().asIfStmt();
   var consume=ui.getMethodsByName("consume").get(0);var cb=consume.getBody().orElseThrow();
   cb.addStatement(0,StaticJavaParser.parseStatement("boolean chatWasReset=false,universalNotify=false;"));cb.addStatement(1,StaticJavaParser.parseStatement("int first_unread_id=0,last_message_id=0,createUnreadMessageAfterId=0;"));
   int ix=0;for(int i=0;i<cb.getStatements().size();i++)if(cb.getStatement(i).toString().equals("loading = false;")){ix=i;break;}
   cb.addStatement(ix,StaticJavaParser.parseStatement("if(load_type==1){"+type1.getThenStmt().asBlockStmt().getStatement(0)+"loadingForward=false;updateCodexHistoryLoadingCells();}"));
   // type1在真实receiver不进入旧向else；补齐原夹具遗漏的方向外层，避免测试把新向误设endReached。
   for(var st:new java.util.ArrayList<>(cb.getStatements())){
    if(st.isIfStmt()&&st.asIfStmt().getCondition().toString().equals("messArr.size() < count && load_type != 3 && load_type != 4"))st.replace(StaticJavaParser.parseStatement("if(load_type!=1){"+st+"}"));
    else if(st.toString().equals("loading = false;"))st.replace(StaticJavaParser.parseStatement("if(load_type!=1){loading=false;updateCodexHistoryLoadingCells();}"));
   }
   var emptyUp=receiver.findAll(IfStmt.class).stream().filter(x->x.getCondition().toString().startsWith("chatAdapter.loadingUpRow >= 0 && endReached[loadIndex]")).findFirst().orElseThrow().clone();
   // 本例没有新正文，所以真实上行分支的用户信息锚可保留为无进入的平台边界。
   cb.addStatement(ix+1,StaticJavaParser.parseStatement("if(load_type!=1){"+emptyUp+"}"));
   ui.addMember(StaticJavaParser.parseBodyDeclaration("int loadsCount;"));ui.addMember(StaticJavaParser.parseBodyDeclaration("Object unreadMessageObject;"));
   for(var field:ui.getFields())for(var v:new java.util.ArrayList<>(field.getVariables()))if(v.getNameAsString().equals("chatLayoutManager"))v.remove();
   ui.addMember(StaticJavaParser.parseBodyDeclaration("LayoutManager chatLayoutManager=new LayoutManager();"));
   host.addMember(StaticJavaParser.parseBodyDeclaration("static class LayoutManager{void scrollToPositionWithOffset(int a,int b,boolean c){}}"));
   // 复用真实updateRowsInternal普通消息区的首尾边界及消息计数，不以UI end位推断行消失。
   var rows=chat.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("updateRowsInternal")).findFirst().orElseThrow();var nonempty=rows.findAll(IfStmt.class).stream().filter(x->x.getCondition().toString().equals("!messages.isEmpty()")).findFirst().orElseThrow();
   String rowBody="boolean isFiltered=chatAdapter.isFiltered,filteredEndReached=false,hideForwardEndReached=false,DISABLE_PROGRESS_VIEW=false,isComments=false;Object currentUser=null;int rowCount=0,loadingUpRow=-5,loadingDownRow=-5,messagesStartRow=0,messagesEndRow=0;if(!messages.isEmpty()){";
   for(var st:nonempty.getThenStmt().asBlockStmt().getStatements())if(st.toString().startsWith("messagesStartRow =")||st.toString().startsWith("messagesEndRow =")||st.toString().startsWith("rowCount += messages.size()")||st.isIfStmt()&&(st.asIfStmt().getThenStmt().toString().contains("loadingUpRow = rowCount++")||st.asIfStmt().getThenStmt().toString().contains("loadingDownRow = rowCount++")))rowBody+=st;
   rowBody+="}chatAdapter.loadingUpRow=loadingUpRow;chatAdapter.loadingDownRow=loadingDownRow;chatAdapter.total=rowCount;chatAdapter.messagesStartRow=messagesStartRow;chatAdapter.messagesEndRow=messagesEndRow;";ui.getMethodsByName("updateActualBoundaryRows").get(0).setBody(StaticJavaParser.parseBlock("{"+rowBody+"}"));
   ui.getMethodsByName("updateCodexHistoryLoadingCells").get(0).remove();ui.addMember(StaticJavaParser.parseBodyDeclaration(chat.getMethodsByName("updateCodexHistoryLoadingCells").get(0).toString().replace("View child =", "Object child =")));
   var list=type(host,"RecyclerListView");list.addMember(StaticJavaParser.parseBodyDeclaration("java.util.ArrayList<Object> children=new java.util.ArrayList<>();"));list.getMethodsByName("getChildCount").get(0).setBody(StaticJavaParser.parseBlock("{return children.isEmpty()?visible:children.size();}"));list.getMethodsByName("getChildAt").get(0).setBody(StaticJavaParser.parseBlock("{return children.isEmpty()?i:children.get(i);}"));
   var adapter=type(host,"Adapter");adapter.addExtendedType("AdapterBase");adapter.addMember(StaticJavaParser.parseBodyDeclaration("int messagesStartRow,messagesEndRow,userInfoRow=-5;"));adapter.addMember(StaticJavaParser.parseBodyDeclaration("void updateRowsInternal(){owner.updateActualBoundaryRows();}"));adapter.addMember(StaticJavaParser.parseBodyDeclaration("void notifyDataSetChanged(boolean b){updateRowsInternal();helper.mPostponedList.clear();}"));
   var notify=chat.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("notifyItemRemoved")&&m.getParameters().size()==1).findFirst().orElseThrow();var notifyBody=notify.getBody().orElseThrow();String method="void notifyItemRemoved(int position){";for(var st:notifyBody.getStatements())if(st.toString().equals("updateRowsInternal();")||st.isTryStmt())method+=st;adapter.addMember(StaticJavaParser.parseBodyDeclaration(method+"}"));
   host.addMember(StaticJavaParser.parseBodyDeclaration("static class FileLog{static void e(Exception e){throw new AssertionError(e);}}"));
   // 单一通知边界登记实际remove事件；偏移算法和非法位置异常条件均从Recycler源码提取。
   host.addMember(StaticJavaParser.parseBodyDeclaration("static class AdapterBase{Ui owner;Helper helper=new Helper();void notifyItemRemoved(int position){helper.mPostponedList.add(new Helper.UpdateOp(Helper.UpdateOp.REMOVE,position,1,null));}}"));
   var helper=StaticJavaParser.parse(src.resolve("androidx/recyclerview/widget/AdapterHelper.java")).getClassByName("AdapterHelper").orElseThrow();var h=StaticJavaParser.parseBodyDeclaration("static class Helper{java.util.ArrayList<UpdateOp> mPostponedList=new java.util.ArrayList<>();}").asClassOrInterfaceDeclaration();for(var m:helper.getMethodsByName("findPositionOffset"))h.addMember(m.clone());h.addMember(type(helper,"UpdateOp").clone());host.addMember(h);
   var rv=StaticJavaParser.parse(src.resolve("androidx/recyclerview/widget/RecyclerView.java")).getClassByName("RecyclerView").orElseThrow();var guard=rv.findAll(IfStmt.class).stream().filter(x->x.getCondition().toString().equals("offsetPosition < 0 || offsetPosition >= mAdapter.getItemCount()")).findFirst().orElseThrow();
   host.addMember(StaticJavaParser.parseBodyDeclaration("static void verifyPosition(Adapter mAdapter,int position,int initialCount){State mState=new State(initialCount);int offsetPosition=mAdapter.helper.findPositionOffset(position);"+guard+"}"));host.addMember(StaticJavaParser.parseBodyDeclaration("static class State{int count;State(int n){count=n;}int getItemCount(){return count;}}"));host.addMember(StaticJavaParser.parseBodyDeclaration("static String exceptionLabel(){return \"\";}"));
   String methods=host.getMembers().stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));RuntimeLatestSegmentTest.runScenarios(src.resolve("com/butang/codextop/CodexRuntime.java"),methods+CASES,args.length>1?args[1]:"boundaryNotify(false,true);boundaryNotify(true,true);boundaryNotify(false,false);boundaryNotify(true,false);boundaryNotify(false,false,true);boundaryNotify(true,false,true);");
  }finally{Files.deleteIfExists(export);}
 }
 static ClassOrInterfaceDeclaration type(ClassOrInterfaceDeclaration owner,String name){return owner.getMembers().stream().filter(x->x.isClassOrInterfaceDeclaration()&&x.asClassOrInterfaceDeclaration().getNameAsString().equals(name)).findFirst().orElseThrow().asClassOrInterfaceDeclaration();}
 static final String CASES="""
 static String ids(String prefix,int n){StringBuilder s=new StringBuilder();for(int i=1;i<=n;i++){if(i>1)s.append(',');s.append(prefix).append(i);}return s.toString();}
 static Event actualPage(int type){Event found=null;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&(Integer)e.args[14]==0&&(Integer)e.args[8]==type)found=e;check(found!=null,"actual page missing "+type);events.clear();return found;}
 static void boundaryNotify(boolean older,boolean local)throws Exception{boundaryNotify(older,local,false);}
 static void boundaryNotify(boolean older,boolean local,boolean telegram)throws Exception{try{
  TranscriptWindow old=new TranscriptWindow();old.prepend(page(ids("O",44),false,null,"old-tail"));TranscriptWindow root=local?old.acceptLatest(page(ids("N",6),true,"new-older","tail")):old;reset(root);Ui t=new Ui();t.chatAdapter.owner=t;t.firstLoadMessages();pump();consumeSnapshot(t);events.clear();current.requests.clear();
  if(local){ChatLoadingCell up=bindBoundary(t);up.tap();pump();int pick=-1;for(int i=0;i<t.lastDialog.labels.length;i++)if(t.lastDialog.labels[i].toString().endsWith(" · 44条"))pick=i;check(pick>=0,"missing local option");t.lastDialog.tap(pick);pump();consumeSnapshot(t);pump();check(historyViews.get(7).window==old,"old selection failed");}
  // 用原loadMessages按本地已知首／末整数锚获取真实空页终态，不构造loadFailed或假HistoryPage。
  java.util.ArrayList<TranscriptWindow.Entry> all=old.before(0,100);int anchor=older?all.get(all.size()-1).id:all.get(0).id;
  t.forwardEndReached[0]=false;t.endReached[0]=false;t.updateActualBoundaryRows();int before=t.chatAdapter.total;int affected=older?t.chatAdapter.loadingUpRow:t.chatAdapter.loadingDownRow;int query=t.lastLoadIndex++;t.waitingForLoad.add(query);ChatLoadingCell mounted=new ChatLoadingCell();mounted.parent=t.chatListView;mounted.position=affected;t.chatListView.children.add(mounted);if(older)t.loading=true;else t.loadingForward=true;t.updateCodexHistoryLoadingCells();check(mounted.shown(),"request spinner not bound");
  loadMessages(0,42,50,anchor,7,older?0:1,query,0);pump();Event e=actualPage(older?0:1);check(((java.util.ArrayList<?>)e.args[2]).isEmpty(),"expected actual terminal empty");check((Boolean)e.args[9],"expected terminal flag");if(telegram){remoteIds.clear();e=new Event(e.type,java.util.Arrays.copyOf(e.args,15));}t.consume(e);
  int after=t.chatAdapter.total;boolean retained=older?t.chatAdapter.loadingUpRow>=0:t.chatAdapter.loadingDownRow>=0;int shifted=t.chatAdapter.helper.findPositionOffset(affected);
  System.out.println("boundary older="+older+" local="+local+" telegram="+telegram+" before="+before+" after="+after+" retained="+retained+" removes="+t.chatAdapter.helper.mPostponedList.size()+" offset="+shifted);
  if(local){check(retained,"local action lost");verifyPosition(t.chatAdapter,affected,before);check(before==after&&t.chatAdapter.helper.mPostponedList.isEmpty(),"retained boundary announced removed");check(!mounted.shown()&&mounted.actionVisible(),"retained boundary kept spinner/lost action");}
  else {check(!retained&&after==before-1&&t.chatAdapter.helper.mPostponedList.size()==1,"original terminal removal changed");}
  System.out.println("PASS local boundary notification older="+older+" local="+local+" telegram="+telegram);
 }finally{cleanup();}}
 """;
}
