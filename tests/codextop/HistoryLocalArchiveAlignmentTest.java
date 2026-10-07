package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import java.nio.file.Path;

/** 私有几何夹具只替代Android像素与事件边界，定位参数及坐标算法均从真实owner提取。 */
public final class HistoryLocalArchiveAlignmentTest {
    /** 复用原按钮/Runtime专项的实际提取体，再追加真实type3与LLM坐标链；不改原专项。 */
    public static void main(String[] args) throws Exception {
        Path source=Path.of("TMessagesProj/src/main/java");
        Path chatSource=args.length>0?Path.of(args[0]):source.resolve("org/telegram/ui/ChatActivity.java");
        Path exported=java.nio.file.Files.createTempFile("history-local-alignment-", ".java");
        try {
            HistoryLocalArchiveTest.main(new String[]{chatSource.toString(), "", exported.toString()});
            var chat=StaticJavaParser.parse(chatSource).getClassByName("ChatActivity").orElseThrow();
            String extra=install(java.nio.file.Files.readString(exported),chat,source);
            if(args.length>2)java.nio.file.Files.writeString(Path.of(args[2]),extra);
            RuntimeLatestSegmentTest.runScenarios(source.resolve("com/butang/codextop/CodexRuntime.java"),
                extra.replace("RuntimeLatestSegmentProbe.ownsConversation","RuntimeLatestSegmentProbe.actualOwnsConversation"),
                args.length>1?args[1]:"for(int p:new int[]{0,40,90,160,320})for(int q:new int[]{0,200})for(boolean s:new boolean[]{false,true})alignmentCase(p,q,s);for(String c:new String[]{\"clear\",\"pause\",\"destroy\",\"latest\",\"empty-finish\"})alignmentCancellation(c);alignmentConsumedControl();");
        } finally {java.nio.file.Files.deleteIfExists(exported);}
    }

    /** 在既有完整Runtime/Window/按钮组合上增加原type3匹配和首次定位的真实代码。 */
    public static String install(String extra, ClassOrInterfaceDeclaration chat, Path source) throws Exception {
        var host = StaticJavaParser.parse("class Host {" + extra + "}").getClassByName("Host").orElseThrow();
        var ui = host.getMembers().stream().filter(m -> m.isClassOrInterfaceDeclaration() && m.asClassOrInterfaceDeclaration().getNameAsString().equals("Ui")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();
        ui.getFieldByName("scrollToMessage").orElseThrow().getVariable(0).setType("MessageObject");
        members(ui, "int scrollToMessagePosition=-10000; boolean postponedScroll,fakePostponedScroll,needSelectFromMessageId,showScrollToMessageError,isFirstLoading,scrolledToUnread,canShowPagedownButton; int postponedScrollMessageId,newUnreadMessageCount,prevSetUnreadCount; Integer highlightTaskId;byte[] highlightPollOptionId;String highlightMessageQuote;int highlightMessageQuoteOffset;int quoteBoundaryOffset,quoteCalls; Object sideControlsButtonsLayout; GeometryLayoutManager geometry=new GeometryLayoutManager();");
        members(ui, "void unexpectedUnreadBadge(){throw new AssertionError();}void addSponsoredMessages(boolean b){} int getScrollOffsetForMessage(MessageObject m){return 120;} int scrollOffsetForQuote(MessageObject m){quoteCalls++;return highlightMessageQuote==null?0:quoteBoundaryOffset;} ");
        members(ui,"Runnable unselectRunnable;boolean highlightMessageQuoteFirst;long highlightMessageQuoteFirstTime;");
        ui.addMember(chat.getMethodsByName("removeSelectedMessageHighlight").get(0).clone());
        ui.addMember(chat.getMethodsByName("finishCodexHistoryLoading").get(0).clone());
        // Android布局辅助不计算目标坐标；真实LLM在稍后layout才读取当前padding。
        var receiver = chat.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
        IfStmt around = receiver.findAll(IfStmt.class).stream().filter(s -> s.getCondition().toString().startsWith("(load_type == 3 || load_type == 4)") && s.getThenStmt().toString().contains("scrollToMessage = obj;")).findFirst().orElseThrow();
        ui.addMember(StaticJavaParser.parseBodyDeclaration("void actualAroundMatch(MessageObject obj,int load_type){int messageId=obj.getId();"+around+"}"));
        IfStmt position = receiver.findAll(IfStmt.class).stream().filter(s -> s.getCondition().toString().equals("scrollToMessage != null") && s.getThenStmt().toString().contains("yOffset = -startLoadFromMessageOffset")).findFirst().orElseThrow();
        String body = position.getThenStmt().toString();
        body = body.replace("chatLayoutManager.scrollToPositionWithOffset", "geometry.scrollToPositionWithOffset");
        // 侧栏未读徽标只记录UI显示；本fixture unread_to_load固定0，不替换坐标/目标/状态分支。
        body = body.replace("sideControlsButtonsLayout.setButtonCount(ChatActivitySideControlsButtonsLayout.BUTTON_PAGE_DOWN, newUnreadMessageCount = unread_to_load, openAnimationEnded);", "unexpectedUnreadBadge();");
        var firstGate=receiver.findAll(IfStmt.class).stream().filter(i->i.getCondition().toString().equals("first || scrollToTopOnResume || forceScrollToTop")).findFirst().orElseThrow();
        var firstDone=receiver.findAll(IfStmt.class).stream().filter(i->i.getCondition().toString().equals("first && messages.size() > 0")).findFirst().orElseThrow();
        ui.addMember(StaticJavaParser.parseBodyDeclaration("void actualPosition(int load_type){int unread_to_load=0;boolean openAnimationEnded=true;if("+firstGate.getCondition()+"){if(scrollToMessage!=null)"+body+"}if("+firstDone.getCondition()+"){"+firstDone.getThenStmt().asBlockStmt().getStatement(0)+"}}"));
        var list=host.getMembers().stream().filter(m->m.isClassOrInterfaceDeclaration()&&m.asClassOrInterfaceDeclaration().getNameAsString().equals("RecyclerListView")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();
        members(list, "int paddingBottom=90;int getPaddingBottom(){return paddingBottom;}");
        var adapter=host.getMembers().stream().filter(m->m.isClassOrInterfaceDeclaration()&&m.asClassOrInterfaceDeclaration().getNameAsString().equals("Adapter")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();
        members(adapter, "int messagesStartRow=1;void updateRowsSafe(){}");
        var au=ui.getMembers().stream().filter(m->m.isClassOrInterfaceDeclaration()&&m.asClassOrInterfaceDeclaration().getNameAsString().equals("AndroidUtilities")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();
        au.addMember(StaticJavaParser.parseBodyDeclaration("static int dp(int n){return n;}"));au.addMember(StaticJavaParser.parseBodyDeclaration("static void cancelRunOnUIThread(Runnable r){if(r!=null)throw new AssertionError(\"unexpected pending highlight timer\");}"));
        var rows=chat.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("updateRowsInternal")).findFirst().orElseThrow();
        var nonempty=rows.findAll(IfStmt.class).stream().filter(i->i.getCondition().toString().equals("!messages.isEmpty()")).findFirst().orElseThrow();
        StringBuilder rb=new StringBuilder("{boolean isFiltered=chatAdapter.isFiltered,filteredEndReached=false,hideForwardEndReached=false,DISABLE_PROGRESS_VIEW=false,isComments=false;Object currentUser=null;int rowCount=0,loadingUpRow=-5,loadingDownRow=-5,messagesStartRow=0,messagesEndRow=0;if(!messages.isEmpty()){");
        for(var st:nonempty.getThenStmt().asBlockStmt().getStatements()) {
            String x=st.toString();if(st.isIfStmt()&&(x.contains("loadingUpRow = rowCount++")||x.contains("loadingDownRow = rowCount++"))||x.equals("messagesStartRow = rowCount;")||x.equals("rowCount += messages.size();")||x.equals("messagesEndRow = rowCount;"))rb.append(st);
        }
        rb.append("}chatAdapter.loadingUpRow=loadingUpRow;chatAdapter.loadingDownRow=loadingDownRow;chatAdapter.messagesStartRow=messagesStartRow;}");
        ui.getMethodsByName("updateActualBoundaryRows").get(0).setBody(StaticJavaParser.parseBlock(rb.toString()));
        var llm=StaticJavaParser.parse(source.resolve("androidx/recyclerview/widget/LinearLayoutManager.java")).getClassByName("LinearLayoutManager").orElseThrow();
        MethodDeclaration set=llm.getMethodsByName("scrollToPositionWithOffset").stream().filter(m->m.getParameters().size()==3).findFirst().orElseThrow();
        var anchor=llm.getMethodsByName("updateAnchorFromPendingData").get(0);
        var statements=anchor.getBody().orElseThrow().getStatements();
        int start=-1;for(int i=0;i<statements.size();i++)if(statements.get(i).isExpressionStmt() && statements.get(i).asExpressionStmt().getExpression().toString().equals("anchorInfo.mLayoutFromEnd = mPendingScrollPositionBottom"))start=i;
        if(start<0)throw new AssertionError("actual anchor boundary absent");
        StringBuilder anchorCode=new StringBuilder();for(int i=start;i<statements.size();i++)anchorCode.append(statements.get(i));
        var geometry=StaticJavaParser.parseBodyDeclaration("static class GeometryLayoutManager {int mPendingScrollPosition=-1,mPendingScrollPositionOffset;boolean mPendingScrollPositionBottom;SavedState mPendingSavedState;int requests;Orientation mOrientationHelper=new Orientation();Anchor anchorInfo=new Anchor();void requestLayout(){requests++;}static class SavedState{void invalidateAnchor(){}}static class Anchor{boolean mLayoutFromEnd;int mCoordinate;}static class Orientation{int height=800,padding=90;int getEndAfterPadding(){return height-padding;}int getStartAfterPadding(){return 0;}}}").asClassOrInterfaceDeclaration();
        var orientationSource=StaticJavaParser.parse(source.resolve("androidx/recyclerview/widget/OrientationHelper.java"));
        var vertical=orientationSource.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("createVerticalHelper")).findFirst().orElseThrow();
        var endPadding=vertical.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("getEndAfterPadding")).findFirst().orElseThrow().clone();endPadding.getAnnotations().clear();
        var orientation=geometry.getMembers().stream().filter(m->m.isClassOrInterfaceDeclaration()&&m.asClassOrInterfaceDeclaration().getNameAsString().equals("Orientation")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();
        orientation.getMethodsByName("getEndAfterPadding").get(0).remove();orientation.addMember(endPadding);members(orientation,"GeometryLayoutManager mLayoutManager;");
        members(geometry,"int getHeight(){return mOrientationHelper.height;}int getPaddingBottom(){return mOrientationHelper.padding;}GeometryLayoutManager(){mOrientationHelper.mLayoutManager=this;}");
        geometry.addMember(set.clone());geometry.addMember(StaticJavaParser.parseBodyDeclaration("boolean actualAnchor(){"+anchorCode+"}"));host.addMember(geometry);
        members(host,"static class R{static class string{static int MessageNotFound;}}static class LocaleController{static String getString(int k){return \"error\";}}static class BulletinFactory{static BulletinFactory of(Object o){return new BulletinFactory();}BulletinFactory createErrorBulletin(String s,Object p){return this;}void show(){throw new AssertionError(\"unexpected message error\");}}");
        StringBuilder result=new StringBuilder();for(var m:host.getMembers())result.append(m);
        result.append(CASES);return result.toString();
    }

    /** 只拼装有限Android边界声明，不复制定位算法。 */
    private static void members(ClassOrInterfaceDeclaration c,String s){for(var m:StaticJavaParser.parse("class X{"+s+"}").getClassByName("X").orElseThrow().getMembers())c.addMember(m);}

    private static final String CASES="""
    /** 用反射读取增量字段，让同一夹具仍能编译并执行没有新字段的90前像。 */
    static int initialAnchor(Ui t)throws Exception {try{java.lang.reflect.Field f=Ui.class.getDeclaredField("codexLocalHistoryInitialAnchorId");f.setAccessible(true);return f.getInt(t);}catch(NoSuchFieldException e){return 0;}}
    /** 只取消这次初始位置；原返回/暂停/换页身份入口不被几何路径取代。 */
    static void alignmentCancellation(String kind)throws Exception {try{
      Ui t=localBrowserFixture(false,false);openLocal(t);check(initialAnchor(t)!=0,"local anchor absent before cancel");
      if(kind.equals("clear"))t.clearCodexHistoryScrollTargets();else if(kind.equals("pause"))t.pauseCodexHistoryView(false);else if(kind.equals("destroy"))t.pauseCodexHistoryView(true);else if(kind.equals("empty-finish"))t.finishCodexHistoryLoading(3);else t.requestCodexLatestHistory();
      check(initialAnchor(t)==0,"old local alignment survived "+kind);check(t.geometry.requests==0,"cancel forced positioning");System.out.println("PASS alignment cancellation "+kind);
    }finally{cleanup();}}
    /** 已消费资格后原精确定位仍走原正文分支，不再次对齐本地返回行。 */
    static void alignmentConsumedControl()throws Exception {try{
      Ui t=localBrowserFixture(false,false);openLocal(t);MessageObject target=t.messages.get(0);t.messages.add(new MessageObject(0,pendingMessage(42,-123,3,"date","date",0),true,false));t.updateActualBoundaryRows();
      t.first=true;t.actualAroundMatch(target,3);t.actualPosition(3);check(initialAnchor(t)==0,"anchor retained");
      t.first=true;t.scrollToMessage=target;t.scrollToMessagePosition=7;t.startLoadFromMessageOffset=Integer.MAX_VALUE;t.quoteBoundaryOffset=12;t.highlightMessageQuote="synthetic";t.actualPosition(3);
      check(t.geometry.mPendingScrollPosition==t.chatAdapter.messagesStartRow,"subsequent around forced local boundary");check(t.geometry.mPendingScrollPositionOffset==45,"ordinary explicit position or quote changed");check(t.quoteCalls==1,"ordinary quote path bypassed");System.out.println("PASS consumed local anchor preserves later ordinary around");
    }finally{cleanup();}}
    /** 真实按钮/descriptor/accept/around之后，执行真实消息匹配与yOffset，再让LLM读取动态padding。 */
    static void alignmentCase(int padding,int quote,boolean single)throws Exception {try{
      Ui t;
      if(single){TranscriptWindow root=cache("A").acceptLatest(page("Y,Z",true,"older","tail"));reset(root);t=new Ui();t.firstLoadMessages();pump();consumeSnapshot(t);events.clear();current.requests.clear();ChatLoadingCell c=bindBoundary(t);c.tap();pump();int item=-1;for(int i=0;i<t.lastDialog.labels.length;i++)if(t.lastDialog.labels[i].toString().endsWith(" · 1条"))item=i;check(item>=0,"actual one-row descriptor missing");t.lastDialog.tap(item);pump();consumeSnapshot(t);pump();}
      else{t=localBrowserFixture(false,false);openLocal(t);}MessageObject target=t.messages.get(0);
      if(single){t.messages.clear();t.messages.add(target);}t.messages.add(new MessageObject(0,pendingMessage(42,-123,3,"date","date",0),true,false));t.updateActualBoundaryRows();
      t.chatListView.paddingBottom=90;t.highlightMessageQuote=quote==0?null:"synthetic";t.quoteBoundaryOffset=quote;
      t.first=true;t.actualAroundMatch(target,3);t.actualPosition(3);
      t.geometry.mOrientationHelper.padding=padding;t.geometry.actualAnchor();
      int coordinate=t.geometry.anchorInfo.mCoordinate;
      int downBottom=coordinate+(t.geometry.mPendingScrollPosition==t.chatAdapter.loadingDownRow?0:t.geometry.mPendingScrollPosition==t.chatAdapter.loadingUpRow?740:44);
      check(downBottom<=800-padding,"initial local boundary overlaps input: bottom="+downBottom+" safe="+(800-padding)+" target="+t.geometry.mPendingScrollPosition);
      check(t.scrollToMessagePosition==-10000&&t.scrollToMessage==null,"initial position not consumed");check(initialAnchor(t)==0,"local anchor not consumed");check(t.quoteCalls==0,"local initial alignment queried old quote geometry");
      int requests=t.geometry.requests;t.scrollToMessage=target;t.actualPosition(0);check(requests==t.geometry.requests,"later reading relaid anchor");
      check(current.requests.isEmpty(),"position unexpectedly made RPC");
      System.out.println("PASS local alignment padding="+padding+" quote="+quote+" single="+single);
    }finally{cleanup();}}
    """;
}
