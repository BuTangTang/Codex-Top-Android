package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实分页、绑定及加载行方法；通知只提取身份前置和实际结束语句，不重写结束判断。 */
public final class HistoryLoadingCellTest {
    /** 前像可分别执行空闲、开始和完成反例；所有消息和身份均为合成数据。 */
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") : Path.of(args[0]);
        String original = Files.readString(source);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var owner = StaticJavaParser.parse(original).getClassByName("ChatActivity").orElseThrow();
        StringBuilder methods = new StringBuilder();
        for (String name : new String[]{"cancelCodexHistoryInitialFill", "isCodexHistoryView", "isCodexHistoryScrollCurrent", "openCodexHistoryView", "pauseCodexHistoryView", "resumeCodexHistoryView",
                "prepareCodexHistoryPage", "lockCodexHistoryReading", "requestCodexLatestHistory", "checkScrollForLoad", "clearChatData"}) for(var method:owner.getMethodsByName(name))methods.append(method).append('\n');
        for (String name : new String[]{"isCodexHistoryLoadingCell", "updateCodexHistoryLoadingCells", "finishCodexHistoryLoading", "canRetryCodexHistoryLoadingCell", "bindCodexHistoryLoadingCell", "retryCodexHistoryFromCell","isCodexHistoryRetryBindingCurrent"})
            if (!owner.getMethodsByName(name).isEmpty()) for(var method:owner.getMethodsByName(name))methods.append(method).append('\n');
        var adapter = owner.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("ChatActivityAdapter")).findFirst().orElseThrow();
        var bind = adapter.getMethodsByName("onBindViewHolder").get(0).findAll(IfStmt.class).stream()
                .filter(statement -> statement.getCondition().toString().equals("position == loadingDownRow || position == loadingUpRow")).findFirst().orElseThrow();
        methods.append("class Adapter {boolean isFrozen,isFiltered;int count=3,loadingUpRow=2,loadingDownRow=0,botInfoRow=-1; /** 返回合成行数。 */ int getItemCount(){return count;} /** 不自动重新绑定，以验证现有cell的直接更新。 */ void notifyDataSetChanged(boolean animated){} /** 完整执行真实加载行绑定分支。 */ void bind(RecyclerView.ViewHolder holder,int position)")
                .append(bind.getThenStmt()).append("}\n");
        var receive = owner.getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
        methods.append("/** 实际身份、请求编号及模式守卫通过后，执行实际方向结束语句。 */ void receive(Object... received){");
        for (Statement statement : receive.getBody().orElseThrow().getStatements()) {
            if (statement.toString().startsWith("boolean universalNotify")) break;
            methods.append(statement);
        }
        for (IfStmt statement : receive.getBody().orElseThrow().getStatements().stream().filter(Statement::isIfStmt).map(Statement::asIfStmt).toList()) {
            if (statement.getCondition().toString().equals("index == -1") || statement.getCondition().toString().startsWith("chatMode != mode")) methods.append(statement);
        }
        methods.append("int load_type=(Integer)args[8];");
        for(Statement statement:receive.getBody().orElseThrow().getStatements())if(statement.isIfStmt()&&statement.toString().contains("codexHistoryOlderRetryRequired = true"))methods.append(statement);
        methods.append("if(load_type==1){").append(finishStatements(receive, "loadingForward"))
                .append("}else{").append(finishStatements(receive, "loading")).append("}");
        Statement tailCheck = receive.getBody().orElseThrow().getStatements().stream()
                .filter(statement -> statement.toString().equals("checkScrollForLoad(false);")).findFirst().orElseThrow();
        // 单独观察完成瞬间，再以真实尾部检查验证原有续页行为；不把续页误报为空闲。
        methods.append("if(followOriginalTail){").append(tailCheck).append("}}\n");
        var resume = owner.getMethodsByName("onResume").get(0).getBody().orElseThrow().getStatements();
        methods.append("/** 执行实际恢复入口及 paused 切换相邻语句，不通过重绑定补足首载指示。 */ void resumeVisibility(){");
        methods.append(resume.stream().filter(statement -> statement.toString().equals("resumeCodexHistoryView();")).findFirst().orElseThrow());
        for (int i = 0; i < resume.size(); i++) if (resume.get(i).toString().equals("paused = false;")) {
            methods.append(resume.get(i));
            if (i + 1 < resume.size() && resume.get(i + 1).isExpressionStmt()
                    && resume.get(i + 1).asExpressionStmt().getExpression().toString().equals("updateCodexHistoryLoadingCells()")) methods.append(resume.get(i + 1));
        }
        methods.append("}\n");
        var missing = receive.findAll(IfStmt.class).stream().filter(statement -> statement.getCondition().toString().equals("!found")
                && statement.getThenStmt().toString().contains("startLoadFromMessageId = 0;")).findFirst().orElseThrow();
        methods.append("/** 实际定位未命中提前返回分支。 */ void missingAnchor(){Object[] args=page(0);boolean found=false;").append(missing).append("}\n");
        var canceled = receive.findAll(IfStmt.class).stream().filter(statement -> statement.getCondition().toString().equals("postponedScrollIsCanceled")).findFirst().orElseThrow();
        methods.append("/** 实际定位取消提前返回分支。 */ void canceledAnchor(){int load_type=0;boolean postponedScrollIsCanceled=true;").append(canceled).append("}\n");
        var cell = StaticJavaParser.parse(Files.readString(args.length>2?Path.of(args[2]):Path.of("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatLoadingCell.java"))).getClassByName("ChatLoadingCell").orElseThrow();
        methods.append("static class ChatLoadingCell extends View {FrameLayout frameLayout=new FrameLayout();Button retryButton;Runnable retryAction;int getThemedColor(int key){return key;}").append(cell.getMethodsByName("setProgressVisible").get(0));
        for(var method:cell.getMethodsByName("setRetryAction"))methods.append(method);
        methods.append("/** 观察实际视图状态及真实按钮listener，不在夹具实现重试策略。 */ boolean shown(){return frameLayout.visibility==VISIBLE;}boolean retryShown(){return retryButton!=null&&retryButton.visibility==VISIBLE&&retryButton.enabled;}void tap(){if(retryButton!=null)retryButton.performClick();}}\n");
        Path temporary = Files.createTempDirectory("codex-loading-cell-");
        try {
            Path probe = temporary.resolve("HistoryLoadingCellProbe.java"), runtime = temporary.resolve("CodexRuntime.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            Files.writeString(runtime, RUNTIME);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temporary.toString(), probe.toString(), runtime.toString()) != 0)
                throw new AssertionError("实际加载圈方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.HistoryLoadingCellProbe").getMethod("main", String[].class).invoke(null, (Object)new String[]{args.length > 1 ? args[1] : "all"}); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("实际加载圈生命周期失败", error.getCause()); }
            }
            if (!Files.readString(source).equals(original)) throw new AssertionError("被测源码在验证期间变化");
        } finally {
            try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** 提取实际赋值和紧邻的同步调用，缺失同步调用的前像必须保留原失败行为。 */
    private static String finishStatements(MethodDeclaration receive, String name) {
        var assignments = receive.findAll(AssignExpr.class).stream().filter(assignment -> assignment.getTarget().toString().equals(name)
                && assignment.getValue().toString().equals("false")).toList();
        if (assignments.size() != 1) throw new AssertionError("实际结束赋值不唯一：" + name);
        Statement statement = assignments.get(0).findAncestor(Statement.class).orElseThrow();
        BlockStmt block = statement.findAncestor(BlockStmt.class).orElseThrow();
        int index = block.getStatements().indexOf(statement);
        String result = statement.toString();
        if (index + 1 < block.getStatements().size() && block.getStatement(index + 1).toString().equals("updateCodexHistoryLoadingCells();")) result += block.getStatement(index + 1);
        return result;
    }

    private static final String RUNTIME = """
        package com.butang.codextop;
        public class CodexRuntime {
            static boolean owns=true,current=true,accept=true;static int latest;static long next=20;
            static class HistoryPage {long token;String fromEpoch,toEpoch;boolean replaceLatest,loadFailed;/** 构造合成页面身份。 */ HistoryPage(long t,String f,String to,boolean r){token=t;fromEpoch=f;toEpoch=to;replaceLatest=r;}}
            /** 替代会话归属。 */ public static boolean ownsConversation(long dialog){return owns;}
            /** 替代运行时身份检验。 */ public static boolean isHistoryPageCurrent(HistoryPage page){return current;}
            /** 只替代账号/页面存活；组合专项使用真实 Runtime guard。 */ public static boolean isHistoryViewCurrent(int account,long dialog,int guid,long token,String epoch){return current;}
            /** 替代运行时接受候选。 */ public static boolean acceptLatestHistory(HistoryPage page){return accept;}
            /** 合成新页面令牌。 */ public static long openHistoryView(int account,long dialog,int guid){return ++next;}
            /** 不触碰真实会话。 */ public static void pauseHistoryView(int guid,long token){}
            /** 不触碰真实会话。 */ public static void closeHistoryView(int guid,long token){}
            /** 记录已有最新段请求。 */ public static void requestLatestHistory(int account,long dialog,int guid,long token){latest++;}
        }
        """;

    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public class HistoryLoadingCellProbe {
            static final int MODE_DEFAULT=0,MODE_SEARCH=1,MODE_SCHEDULED=2,MODE_SAVED=3,MODE_QUICK_REPLIES=4;static int groups;
            long codexHistoryInitialFillToken;Object codexHistoryInitialFillTicket;
            long dialog_id=42,mergeDialogId,threadMessageId,codexHistoryViewToken=7,codexHistoryPausedToken,codexHistoryViewRevision=1;
            String codexHistoryEpoch="segment",searchingHashtag="synthetic";int currentAccount,classGuid=3,chatMode,lastLoadIndex=10,replyMaxReadId,searchType,loadsCount=3;
            boolean searching;boolean codexHistoryOlderContinueAvailable;boolean codexHistoryOlderRetryRequired;boolean paused,waitingForGetDifference,loading,loadingForward,codexHistoryReadingLocked,codexHistoryLatestAvailable,firstMessagesLoaded,isTopic,followOriginalTail,fragmentBeginToShow=true;
            int transitionAnimationIndex,postponedScrollToLastMessageQueryIndex,startLoadFromMessageId,startLoadFromMessageOffset,startLoadFromDate,scheduledMessagesCount;
            boolean fakePostponedScroll,threadMessageAdded,first,firstLoading,waitingForReplyMessageLoad,showScrollToMessageError,createUnreadMessageAfterIdLoading,needSelectFromMessageId,textSelected;
            int last_message_id,createUnreadMessageAfterId;Object unreadMessageObject,currentEncryptedChat;ActionBar actionBar=new ActionBar();
            boolean[] endReached={false,true},cacheEndReached={false,false},forwardEndReached={true,true};
            int[] maxMessageId={900,800},minMessageId={1000,1100},minDate={1,1},maxDate={2,2};
            ArrayList<Integer> waitingForLoad=new ArrayList<>();ArrayList<MessageObject> messages=new ArrayList<>();
            HashMap<String,Object> messagesByDays=new HashMap<>(),messagesByDaysSorted=new HashMap<>(),groupedMessagesMap=new HashMap<>();
            HashMap[] messagesDict={new HashMap<>(),new HashMap<>()};Object chatLayoutManager=new Object();
            ChatList chatListView=new ChatList();Adapter chatAdapter=new Adapter();Controller controller=new Controller();Media media=new Media();
            static class View {static final int VISIBLE=0,INVISIBLE=4,GONE=8;int position;Object parent;Object getParent(){return parent;}Object getContext(){return new Object();}void addView(View child,Object layout){child.parent=this;}}
            static int dp(int value){return value;}static class Gravity{static final int CENTER=1;}static class LayoutHelper{static final int MATCH_PARENT=-1;static Object createFrame(int...values){return values;}}
            static class Theme{static final int key_chat_serviceText=1,key_chat_serviceBackground=2,key_chat_serviceBackgroundSelector=3;static Object createSimpleSelectorRoundRectDrawable(int r,int c,int p){return new Object();}}
            static class Button extends View{interface Listener{void onClick(View v);}Listener listener;int visibility=VISIBLE;boolean enabled;CharSequence text,description;Button(Object context){}void setAllCaps(boolean v){}void setTextSize(int v){}void setMinWidth(int v){}void setMinHeight(int v){}void setPadding(int a,int b,int c,int d){}void setSingleLine(boolean v){}void setEllipsize(Object v){}void setGravity(int v){}void setOnClickListener(Listener v){listener=v;}void setText(CharSequence v){text=v;}void setContentDescription(CharSequence v){description=v;}void setTextColor(int v){}void setBackground(Object v){}void setEnabled(boolean v){enabled=v;}void setVisibility(int v){visibility=v;}void performClick(){if(enabled&&visibility==VISIBLE&&listener!=null)listener.onClick(this);}}
            static class FrameLayout extends View {int visibility=VISIBLE;/** 记录真实加载cell调用的Android可见性。 */ void setVisibility(int value){visibility=value;}}
            static class RecyclerView {static class ViewHolder {View itemView;/** 构造已有绑定对象。 */ ViewHolder(View view){itemView=view;}}}
            static class RecyclerListView {static final int NO_POSITION=-1;}
            static class ChatList {
                ArrayList<View> children=new ArrayList<>();
                /** 返回实际夹具已有子视图。 */ int getChildCount(){return children.size();}
                /** 不创建新cell。 */ View getChildAt(int i){return children.get(i);}
                /** 返回合成绑定位置。 */ int getChildAdapterPosition(View view){return view.position;}
                /** 保留清屏入口的空视图调用。 */ void setEmptyView(Object view){}
            }
            static class Controller {ArrayList<Object[]> calls=new ArrayList<>();/** 记录真实请求，不派发网络。 */ void loadMessages(Object...args){calls.add(args);}}
            static class Media {int searches;/** 记录过滤搜索。 */ void loadMoreSearchMessages(boolean forward){searches++;}}
            static class HashtagSearchController {/** 合成搜索边界。 */ static HashtagSearchController getInstance(int account){return new HashtagSearchController();}/** 不发网络。 */ void searchHashtag(Object...args){}}
            static class ActionBar {boolean selection,visible;boolean isSearchFieldVisible(){return visible;}/** 返回合成多选状态。 */ boolean isActionModeShowed(){return selection;}}
            static class MessageObject {}
            static class TextUtils {enum TruncateAt{MIDDLE}/** 保留真实空判断语义。 */ static boolean isEmpty(CharSequence value){return value==null||value.length()==0;}}
            static class UserConfig {/** 固定合成账号。 */ long getClientUserId(){return 99;}}
            static class ForumUtilities {/** 本专项不构造主题消息。 */ static void filterMessagesByTopic(long thread,ArrayList<MessageObject> messages){}}
            static class NotificationCenter {
                static final int messagesDidLoad=1,chatInfoDidLoad=2,groupCallUpdated=3,dialogsNeedReload=4,scheduledMessagesUpdated=5,closeChats=6,botKeyboardDidLoad=7,userInfoDidLoad=8,pinnedInfoDidLoad=9,needDeleteDialog=10;
                /** 不调度真实动画。 */ int setAnimationInProgress(int index,int[] ids){return 1;}
                /** 不调度真实动画。 */ void onAnimationFinish(int index){}
                /** 不调度真实动画。 */ void updateAllowedNotifications(int index,int[] ids){}
            }
            static class AndroidUtilities {
                static ArrayDeque<Runnable> queue=new ArrayDeque<>();
                /** 固定手机布局。 */ static boolean isTablet(){return false;}
                /** 保留实际UI队列时序。 */ static void runOnUIThread(Runnable run){queue.add(run);}
                /** 不等待真实时间。 */ static void runOnUIThread(Runnable run,long delay){queue.add(run);}
                /** 有限执行队列。 */ static void drain(){int remaining=20;while(!queue.isEmpty()){if(--remaining==0)throw new AssertionError("loop");queue.remove().run();}}
            }
            /** 默认关闭诊断边界；不改变原加载行场景。 */ void traceCodexHistoryViewport(String p,Object t,boolean e){}
            /** 返回请求记录边界。 */ Controller getMessagesController(){return controller;}
            /** 返回搜索记录边界。 */ Media getMediaDataController(){return media;}
            /** 返回无真实账号的边界。 */ UserConfig getUserConfig(){return new UserConfig();}
            /** 返回无外部通知的边界。 */ NotificationCenter getNotificationCenter(){return new NotificationCenter();}
            /** 普通合成聊天不属于收藏。 */ long getSavedDialogId(){return 0;}
            /** 保留非普通模式的原接口。 */ void updateScheduledInterface(boolean animated){}
            /** 不重新绑定cell。 */ void showProgressView(boolean visible){}
            /** 观察范围不含书签存储。 */ void clearCodexHistoryBookmark(){}
            /** 原辅助方法只提供布局代次副作用，此方法不是本片修改对象。 */ void clearCodexHistoryScrollTargets(){codexHistoryViewRevision++;}
            /** 恢复分支本专项只使用已有消息，不伪造首次加载。 */ void firstLoadMessages(){throw new AssertionError("unexpected first load");}
            /** 恢复分支本专项只使用已有消息，不伪造书签读取。 */ void restoreCodexHistoryBookmark(){throw new AssertionError("unexpected bookmark restore");}
            /** 返回合成选区。 */ boolean hasTextSelection(){return textSelected;}
            /** 不伪造历史请求。 */ void updatePagedownButtonVisibility(boolean animated){}
            /** 每个场景重置边界并放置已有两方向cell，后续断言均观察同一对象。 */
            static HistoryLoadingCellProbe fresh(){CodexRuntime.owns=true;CodexRuntime.current=true;CodexRuntime.accept=true;CodexRuntime.latest=0;AndroidUtilities.queue.clear();HistoryLoadingCellProbe p=new HistoryLoadingCellProbe();p.messages.add(new MessageObject());p.messagesByDays.put("synthetic",new Object());ChatLoadingCell down=new ChatLoadingCell(),up=new ChatLoadingCell();down.position=0;up.position=2;down.parent=p.chatListView;up.parent=p.chatListView;View body=new View();body.position=1;p.chatListView.children.add(down);p.chatListView.children.add(body);p.chatListView.children.add(up);return p;}
            /** 读取既有方向cell。 */ ChatLoadingCell cell(boolean forward){return (ChatLoadingCell)chatListView.children.get(forward?0:2);}
            /** 执行真实Adapter加载行绑定。 */ void bind(){for(boolean forward:new boolean[]{false,true}){ChatLoadingCell cell=cell(forward);chatAdapter.bind(new RecyclerView.ViewHolder(cell),cell.position);}}
            /** 构造Runtime实际成功/失败共享的通知格式，失败为空数组且reported count为0。 */ Object[] page(int type){return new Object[]{dialog_id,0,new ArrayList<MessageObject>(),false,0,0,0,0,type,false,classGuid,10,0,0,MODE_DEFAULT,new CodexRuntime.HistoryPage(codexHistoryViewToken,codexHistoryEpoch,codexHistoryEpoch,false)};}
            /** 观察已绑定对象和原结束标记，不以重绑定补足实现缺口。 */ static void expect(HistoryLoadingCellProbe p,boolean up,boolean down,String name){if(p.cell(false).shown()!=up||p.cell(true).shown()!=down)throw new AssertionError(name+" up="+p.cell(false).shown()+" down="+p.cell(true).shown());groups++;System.out.println("PASS "+name);}
            /** 通用断言。 */ static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
        """;

    private static final String SCENARIOS = """
            /** 覆盖同一已绑定cell完整变化和合法/旧代回包边界，绝不通过重绑制造通过。 */
            public static void main(String[] args){String only=args[0];HistoryLoadingCellProbe p;
                if(only.equals("all")||only.equals("idle")){p=fresh();p.bind();expect(p,false,false,"idle_bound_hidden");if(!only.equals("all"))return;}
                if(only.equals("all")||only.equals("start")){p=fresh();p.loadsCount=0;p.bind();p.codexHistoryReadingLocked=true;p.checkScrollForLoad(false);AndroidUtilities.drain();expect(p,true,false,"existing_cell_start_older");check(p.controller.calls.size()==1,"older dispatch changed");p.receive(p.page(0));expect(p,false,false,"existing_cell_older_finished");if(!only.equals("all"))return;}
                if(only.equals("all")||only.equals("finish")){p=fresh();p.loading=true;p.waitingForLoad.add(10);p.bind();check(p.cell(false).shown(),"failure request was not visible initially");p.receive(p.page(0));expect(p,false,false,"existing_cell_failure_finished");check(!p.endReached[0]&&!p.cacheEndReached[0],"spinner changed history end");if(!only.equals("all"))return;}
                if(only.equals("all")||only.equals("resume")){p=fresh();p.loadsCount=0;p.loading=true;p.paused=true;p.bind();p.resumeVisibility();expect(p,true,false,"first_resume_restores_pending_initial_load");check(p.loading&&p.controller.calls.isEmpty(),"resume replaced initial request");if(!only.equals("all"))return;}
                p=fresh();p.bind();p.endReached[0]=true;p.forwardEndReached[0]=false;p.checkScrollForLoad(false);AndroidUtilities.drain();expect(p,false,true,"existing_cell_start_newer");p.receive(p.page(1));expect(p,false,false,"existing_cell_newer_finished");
                p=fresh();p.bind();p.codexHistoryReadingLocked=true;p.forwardEndReached[0]=false;p.checkScrollForLoad(false);AndroidUtilities.drain();expect(p,true,true,"independent_directions_started");p.receive(p.page(0));expect(p,false,true,"older_finish_keeps_newer");Object[] forward=p.page(1);forward[11]=11;p.receive(forward);expect(p,false,false,"both_finished");
                p=fresh();p.loading=true;p.loadingForward=true;p.bind();p.pauseCodexHistoryView(false);expect(p,false,false,"pause_hides_old_token");p.codexHistoryReadingLocked=true;p.resumeCodexHistoryView();expect(p,false,false,"resume_has_no_old_spinner");
                p=fresh();p.loading=true;p.loadingForward=true;p.bind();p.requestCodexLatestHistory();expect(p,false,false,"explicit_latest_cancels_old_request");check(!p.loading&&!p.loadingForward&&CodexRuntime.latest==1,"latest cancellation flags");
                p=fresh();p.loading=true;p.waitingForLoad.add(10);p.bind();Object[] stale=p.page(0);((CodexRuntime.HistoryPage)stale[15]).token++;p.receive(stale);expect(p,true,false,"stale_token_keeps_current_request");
                p=fresh();p.loading=true;p.waitingForLoad.add(10);p.bind();stale=p.page(0);((CodexRuntime.HistoryPage)stale[15]).fromEpoch="stale";p.receive(stale);expect(p,true,false,"stale_epoch_keeps_current_request");
                p=fresh();p.loading=true;p.waitingForLoad.add(10);p.bind();CodexRuntime.current=false;p.receive(p.page(0));expect(p,true,false,"stale_runtime_intent_keeps_current_request");
                p=fresh();p.loading=true;p.waitingForLoad.add(10);p.bind();stale=p.page(0);stale[11]=999;p.receive(stale);expect(p,true,false,"unknown_index_keeps_current_request");
                p=fresh();p.loading=true;p.loadingForward=true;p.bind();Object[] replacement=p.page(2);((CodexRuntime.HistoryPage)replacement[15]).replaceLatest=true;((CodexRuntime.HistoryPage)replacement[15]).toEpoch="next";replacement[11]=-1;p.receive(replacement);expect(p,false,false,"accepted_replacement_clears_old_directions");check("next".equals(p.codexHistoryEpoch),"replacement not exercised");
                p=fresh();p.loading=true;p.codexHistoryReadingLocked=true;p.bind();replacement=p.page(2);((CodexRuntime.HistoryPage)replacement[15]).replaceLatest=true;((CodexRuntime.HistoryPage)replacement[15]).toEpoch="next";p.receive(replacement);expect(p,true,false,"rejected_replacement_preserves_current_request");
                p=fresh();p.loading=true;p.bind();p.missingAnchor();expect(p,false,false,"missing_anchor_finishes_request");
                p=fresh();p.loading=true;p.bind();p.canceledAnchor();expect(p,false,false,"canceled_anchor_finishes_request");
                p=fresh();p.bind();p.codexHistoryReadingLocked=true;p.checkScrollForLoad(false);AndroidUtilities.drain();p.followOriginalTail=true;p.receive(p.page(0));expect(p,false,false,"valid_empty_finished_before_tail_queue");AndroidUtilities.drain();expect(p,true,false,"original_tail_started_real_next_request");check(p.controller.calls.size()==2,"original tail continuation was changed");p.pauseCodexHistoryView(false);expect(p,false,false,"pause_clears_original_tail_request");
                p=fresh();p.bind();p.codexHistoryReadingLocked=true;p.checkScrollForLoad(false);AndroidUtilities.drain();p.followOriginalTail=true;Object[] failure=p.page(0);((CodexRuntime.HistoryPage)failure[15]).loadFailed=true;p.receive(failure);expect(p,false,false,"failure_finishes_loading");AndroidUtilities.drain();expect(p,false,false,"failure_tail_stays_idle");check(p.controller.calls.size()==1&&p.codexHistoryOlderRetryRequired,"failure automatically retried");
                p=fresh();p.loadingForward=true;p.waitingForLoad.add(10);p.bind();failure=p.page(1);((CodexRuntime.HistoryPage)failure[15]).loadFailed=true;p.receive(failure);expect(p,false,false,"newer_failure_finishes_own_direction");check(!p.codexHistoryOlderRetryRequired,"newer failure incorrectly locked older");
                p=fresh();p.loading=true;p.waitingForLoad.add(10);p.bind();failure=p.page(2);((CodexRuntime.HistoryPage)failure[15]).loadFailed=true;p.receive(failure);expect(p,false,false,"latest_failure_finishes_own_direction");check(!p.codexHistoryOlderRetryRequired,"latest failure incorrectly locked older");
                p=fresh();CodexRuntime.owns=false;p.bind();expect(p,true,true,"telegram_original_loads_count");p.loading=false;p.waitingForLoad.add(10);p.receive(p.page(0));expect(p,true,true,"telegram_completion_binding_unchanged");
                p=fresh();p.chatAdapter.isFiltered=true;p.bind();expect(p,true,true,"filtered_original_loads_count");p.loading=true;p.requestCodexLatestHistory();check(p.loading,"filtered loading flag changed");expect(p,true,true,"filtered_cells_not_updated");
                p=fresh();p.chatMode=MODE_SEARCH;p.bind();expect(p,true,true,"search_original_loads_count");
                p=fresh();p.loadsCount=0;CodexRuntime.owns=false;p.bind();expect(p,false,false,"telegram_original_first_load_hidden");
                p=fresh();p.loading=true;p.paused=true;p.bind();expect(p,false,false,"paused_bind_hidden");
                p=fresh();p.loading=true;p.codexHistoryViewToken=0;p.bind();expect(p,false,false,"unbound_bind_hidden");
                p=fresh();p.loading=true;p.bind();p.cell(false).position=-1;p.waitingForLoad.add(10);p.receive(p.page(0));expect(p,false,false,"detached_position_hides_stale_cell");
                // 实际失败接收后直接更新已有cell，真实Button listener须提供重试且不能重复派发。
                p=fresh();p.bind();p.loading=true;p.waitingForLoad.add(10);failure=p.page(0);((CodexRuntime.HistoryPage)failure[15]).loadFailed=true;p.receive(failure);check(p.cell(false).retryShown()&&!p.cell(true).retryShown(),"failed older row has no retry action");check("未能加载更早消息 · 重试".contentEquals(p.cell(false).retryButton.text)&&p.cell(false).retryButton.text.equals(p.cell(false).retryButton.description),"retry text/accessibility not explicit");
                p.cell(false).tap();p.cell(false).tap();AndroidUtilities.drain();check(p.controller.calls.size()==1&&p.loading&&p.cell(false).shown()&&!p.cell(false).retryShown(),"retry click did not enter exactly one original request/spinner");groups++;
                // 复用成另一方向、普通Telegram或成功空页时不遗留按钮/点击资格。
                p=fresh();p.codexHistoryOlderRetryRequired=true;p.bind();ChatLoadingCell recycled=p.cell(false);check(recycled.retryShown(),"retry fixture");recycled.position=0;p.chatAdapter.bind(new RecyclerView.ViewHolder(recycled),0);check(!recycled.retryShown(),"newer recycled stale action");recycled.tap();AndroidUtilities.drain();check(p.controller.calls.isEmpty(),"recycled row sent request");
                recycled.position=2;CodexRuntime.owns=false;p.chatAdapter.bind(new RecyclerView.ViewHolder(recycled),2);check(!recycled.retryShown(),"Telegram recycled retry action");groups++;
                for(String guard:new String[]{"pause","token","epoch","revision","account","runtime","detach","position","filtered","frozen","end","loading","searching","visible-search"}){
                    p=fresh();p.codexHistoryOlderRetryRequired=true;p.bind();ChatLoadingCell retained=p.cell(false);
                    if(guard.equals("pause"))p.paused=true;else if(guard.equals("token"))p.codexHistoryViewToken++;else if(guard.equals("epoch"))p.codexHistoryEpoch="other";else if(guard.equals("revision"))p.codexHistoryViewRevision++;else if(guard.equals("account"))p.currentAccount++;else if(guard.equals("runtime"))CodexRuntime.current=false;else if(guard.equals("detach"))retained.parent=null;else if(guard.equals("position"))retained.position=RecyclerListView.NO_POSITION;else if(guard.equals("filtered"))p.chatAdapter.isFiltered=true;else if(guard.equals("frozen"))p.chatAdapter.isFrozen=true;else if(guard.equals("end"))p.endReached[0]=true;else if(guard.equals("searching"))p.searching=true;else if(guard.equals("visible-search"))p.actionBar.visible=true;else p.loading=true;
                    retained.tap();AndroidUtilities.drain();check(p.controller.calls.isEmpty(),"stale retry sent request "+guard);groups++;
                }
                p=fresh();p.codexHistoryOlderRetryRequired=true;p.bind();p.cell(false).tap();CodexRuntime.current=false;AndroidUtilities.drain();check(p.controller.calls.isEmpty(),"account invalid after click still sent");groups++;
                // 实际Button listener入队后展开搜索，必须保留当前按钮与资格，不依赖新的绑定调用。
                for(String state:new String[]{"searching","visible-search","search-with-newer"}){
                    p=fresh();p.codexHistoryOlderRetryRequired=true;p.bind();ChatLoadingCell buttonCell=p.cell(false);if(state.endsWith("newer"))p.forwardEndReached[0]=false;
                    buttonCell.tap();if(state.equals("visible-search"))p.actionBar.visible=true;else p.searching=true;AndroidUtilities.drain();
                    check(p.controller.calls.isEmpty()&&p.codexHistoryOlderRetryRequired&&buttonCell.retryShown()&&!p.loading&&!p.loadingForward,"queued search consumed button or dispatched "+state);
                    p.searching=false;p.actionBar.visible=false;buttonCell.tap();buttonCell.tap();AndroidUtilities.drain();
                    check(p.controller.calls.size()==1&&(Integer)p.controller.calls.get(0)[9]==0&&p.loading&&!p.loadingForward&&!p.codexHistoryOlderRetryRequired&&buttonCell.shown()&&!buttonCell.retryShown(),"retained Button listener failed after collapse "+state);groups++;
                }
                System.out.println("HistoryLoadingCellTest groups="+groups+" failures=0 actual_cell_method=true notification_rendering_stubbed=true");
            }
        """;
}
