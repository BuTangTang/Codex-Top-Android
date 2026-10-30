package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实滚动状态入口与旧页检查，验证零位移拖动、最新跟随和排队期间意图变化；全部边界数据均为合成数据。 */
public final class HistoryScrollIntentTest {
    /** 可传入本片前像执行同一行为断言，避免把候选算法写进测试夹具。 */
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") : Path.of(args[0]);
        String original = Files.readString(source);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var owner = StaticJavaParser.parse(original).getClassByName("ChatActivity").orElseThrow();
        for (String methodName : new String[]{"onScrolled", "didReceivedNotification_messagesDidLoad", "onResume"}) {
            boolean direct = false;
            for (MethodDeclaration method : owner.findAll(MethodDeclaration.class)) {
                if (!method.getNameAsString().equals(methodName) || method.getBody().isEmpty()) continue;
                for (var statement : method.getBody().get().getStatements()) {
                    if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isMethodCallExpr()
                            && statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("checkScrollForLoad")) direct = true;
                }
            }
            if (!direct) throw new AssertionError("实际布局或生命周期加载检查入口缺失：" + methodName);
        }
        StringBuilder methods = new StringBuilder();
        for (String name : new String[]{"cancelCodexHistoryInitialFill", "isCodexHistoryView", "isCodexHistoryScrollCurrent", "lockCodexHistoryReading", "requestCodexLatestHistory", "checkScrollForLoad"}) {
            for(var method:owner.getMethodsByName(name))methods.append(method).append('\n');
        }
        var stateEntries = owner.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("onScrollStateChanged")
                        && method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                        .anyMatch(call -> call.getNameAsString().equals("lockCodexHistoryReading"))).toList();
        if (stateEntries.size() != 1) throw new AssertionError("真实阅读意图滚动入口不唯一");
        // 只去除匿名监听器的 Override 注解，方法体逐句保留。
        var stateEntry = stateEntries.get(0).clone();
        stateEntry.getAnnotations().clear();
        methods.append(stateEntry).append('\n');
        methods.append("/** 提取首次加载调度前的实际入口，验证消息和日期定位能开启阅读意图。 */ void firstLoadIntent(){");
        boolean foundLoad = false;
        for (var statement : owner.getMethodsByName("firstLoadMessages").get(0).getBody().orElseThrow().getStatements()) {
            if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isVariableDeclarationExpr()
                    && statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().stream()
                    .anyMatch(variable -> variable.getNameAsString().equals("load"))) { foundLoad = true; break; }
            methods.append(statement);
        }
        if (!foundLoad) throw new AssertionError("实际首次加载调度边界缺失");
        methods.append("}\n");
        Path temporary = Files.createTempDirectory("codex-history-scroll-intent-");
        try {
            Path probe = temporary.resolve("HistoryScrollIntentProbe.java"), runtime = temporary.resolve("CodexRuntime.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            Files.writeString(runtime, RUNTIME);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8", "-d", temporary.toString(), probe.toString(), runtime.toString()) != 0)
                throw new AssertionError("实际滚动方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.HistoryScrollIntentProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("实际滚动方法行为失败", error.getCause()); }
            }
            if (!Files.readString(source).equals(original)) throw new AssertionError("测试期间被测源码发生变化");
        } finally {
            try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    private static final String RUNTIME = """
        package com.butang.codextop;
        public class CodexRuntime {
            static boolean owns=true;static int latest;
            /** 只模拟会话归属，不读取账号或真实任务。 */
            public static boolean ownsConversation(long id){return owns;}
            /** 本专项账号存活为固定边界，账号失效由真实Runtime组合专项覆盖。 */ public static boolean isHistoryViewCurrent(int account,long dialog,int guid,long token,String epoch){return true;}
            /** 记录回到最新的调用，不执行网络请求。 */
            public static void requestLatestHistory(int account,long dialog,int guid,long token){latest++;}
        }
        """;

    // 只替代视图位置和控制器副作用；加载方向、条件及代次判断来自完整生产方法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public class HistoryScrollIntentProbe {
            static final int MODE_DEFAULT=0,MODE_SEARCH=1;static int groups;
            Object chatLayoutManager=new Object();boolean codexHistoryOlderContinueAvailable;boolean codexHistoryOlderRetryRequired;boolean paused,waitingForGetDifference,loading,loadingForward,wasManualScroll,codexHistoryReadingLocked,firstMessagesLoaded,isTopic;
            long codexHistoryInitialFillToken;Object codexHistoryInitialFillTicket;
            long codexHistoryViewToken=7,codexHistoryViewRevision=1,dialog_id=42,mergeDialogId,threadMessageId;
            String codexHistoryEpoch="segment",searchingHashtag="synthetic";int currentAccount,classGuid=3,chatMode,lastLoadIndex=10,replyMaxReadId,searchType,startLoadFromMessageId,startLoadFromDate;
            boolean[] endReached={false,true},cacheEndReached={false,false},forwardEndReached={true,true};
            int[] maxMessageId={900,800},minMessageId={1000,1100},minDate={1,1},maxDate={2,2};
            ArrayList<Integer> waitingForLoad=new ArrayList<>();Map<String,Object> messagesByDays=new HashMap<>();
            Adapter chatAdapter=new Adapter();ChatList chatListView=new ChatList();Controller controller=new Controller();Media media=new Media();
            boolean scrollingFloatingDate,scrollingFloatingTopic,scrollingChatListView,checkTextureViewPosition,scrolling,scrollByTouch;
            Object pollHintCell;int pollHintX,pollHintY;Hint pollHintView=new Hint(),groupEmojiPackHint;
            Button searchOtherButton;Selection textSelectionHelper=new Selection();Overlay selectionReactionsOverlay;
            static final int BLUR_INVALIDATE_FLAG_SCROLL=1;
            static class RecyclerView {static final int SCROLL_STATE_IDLE=0,SCROLL_STATE_DRAGGING=1,SCROLL_STATE_SETTLING=2,OVER_SCROLL_ALWAYS=0;}
            static class View {static final int VISIBLE=0;}
            static class Hint {
                /** 合成提示固定为不可见，不影响分页。 */ boolean shown(){return false;}
                /** 原入口隐藏提示的无状态边界。 */ void hide(){}
                /** 原空闲入口显示提示的无状态边界。 */ void showForMessageCell(Object cell,int index,int x,int y,boolean animated){}
            }
            static class Button {
                /** 固定为不可见，避免引入键盘实现。 */ int getVisibility(){return 1;}
            }
            static class Activity {
                /** 返回合成焦点。 */ Object getCurrentFocus(){return null;}
            }
            static class Selection {
                /** 保留原空闲分支的选择停止调用。 */ void stopScrolling(){}
            }
            static class Overlay {
                /** 合成反应浮层固定隐藏。 */ boolean isVisible(){return false;}
                /** 保留原拖动分支的浮层隐藏调用。 */ void setHiddenByScroll(boolean hidden){}
            }
            static class SharedConfig {
                static final int PERFORMANCE_CLASS_LOW=0;
                /** 固定中档设备，不引入性能状态。 */ static int getDevicePerformanceClass(){return 1;}
            }
            static class NotificationCenter {
                static final int startAllHeavyOperations=1,startSpoilers=2,stopAllHeavyOperations=3,stopSpoilers=4;
                /** 返回无外部状态的通知边界。 */ static NotificationCenter getGlobalInstance(){return new NotificationCenter();}
                /** 保留原通知调用，不派发任何外部事件。 */ void postNotificationName(int event,Object...args){}
            }
            /** 日期浮层不参与分页判断。 */ void hideFloatingDateView(boolean animated){}
            /** 主题浮层不参与分页判断。 */ void hideFloatingTopicView(boolean animated){}
            /** 可见行刷新不合成人工滚动回调。 */ void updateVisibleRows(){}
            /** 模糊刷新不合成人工滚动回调。 */ void invalidateMergedVisibleBlurredPositionsAndSources(int flags){}
            /** 合成键盘固定关闭。 */ boolean isKeyboardVisible(){return false;}
            /** 返回无真实窗口的活动边界。 */ Activity getParentActivity(){return new Activity();}
            static class RecyclerListView {static final int NO_POSITION=-1;}
            static class Adapter {
                boolean isFrozen,isFiltered;int count=5,loadingUpRow=4;
                /** 返回合成列表行数。 */ int getItemCount(){return count;}
            }
            static class ChatList {
                int[] positions={0,1,2,3,4};
                /** 保留原空闲分支的过度滚动设置。 */ void setOverScrollMode(int mode){}
                /** 返回合成可见行数。 */ int getChildCount(){return positions.length;}
                /** 只以位置替代视图对象。 */ Object getChildAt(int i){return positions[i];}
                /** 返回合成视图位置。 */ int getChildAdapterPosition(Object value){return (Integer)value;}
            }
            static class Controller {
                ArrayList<Object[]> loads=new ArrayList<>();
                /** 记录真实方法派发的参数，不模拟加载算法。 */ void loadMessages(Object...args){loads.add(args);}
            }
            static class Media {
                int searches;
                /** 记录过滤搜索路径。 */ void loadMoreSearchMessages(boolean forward){searches++;}
            }
            static class HashtagSearchController {
                static int searches;
                /** 返回无账号状态的合成边界。 */ static HashtagSearchController getInstance(int account){return new HashtagSearchController();}
                /** 记录标签搜索路径。 */ void searchHashtag(Object...args){searches++;}
            }
            static class AndroidUtilities {
                static ArrayDeque<Runnable> queued=new ArrayDeque<>();
                /** 保留原状态入口的键盘隐藏边界。 */ static void hideKeyboard(Object focus){}
                /** 延后执行，以便真实方法重新读取最新阅读意图。 */ static void runOnUIThread(Runnable run){queued.add(run);}
                /** 有限清空队列，不使用线程或时间等待。 */ static void drain(){int limit=10;while(!queued.isEmpty()){if(--limit==0)throw new AssertionError("unexpected loop");queued.remove().run();}}
            }
            /** 暴露只记录调用的加载控制器。 */ Controller getMessagesController(){return controller;}
            /** 暴露只记录调用的搜索控制器。 */ Media getMediaDataController(){return media;}
            /** 本专项不访问偏好存储。 */ void clearCodexHistoryBookmark(){}
            /** 渲染边界由加载行专项执行；本专项保留原滚动及请求断言。 */ void updateCodexHistoryLoadingCells(){}
            /** 本专项只替代外部会话令牌分配。 */ void openCodexHistoryView(){if(codexHistoryViewToken==0)codexHistoryViewToken=7;}
            /** 每组重置全部合成边界，避免残留调用影响结论。 */
            static HistoryScrollIntentProbe fresh(){CodexRuntime.owns=true;CodexRuntime.latest=0;HashtagSearchController.searches=0;AndroidUtilities.queued.clear();HistoryScrollIntentProbe p=new HistoryScrollIntentProbe();p.messagesByDays.put("synthetic",new Object());return p;}
            /** 统一行为断言。 */ static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
            /** 同时验证派发方向、预算、等待状态，并确认门禁不篡改历史结束标记。 */
            static void expect(HistoryScrollIntentProbe p,int count,int type,String name){
                boolean[] beforeEnd=p.endReached.clone(),beforeCache=p.cacheEndReached.clone(),beforeForward=p.forwardEndReached.clone();
                AndroidUtilities.drain();require(p.controller.loads.size()==count,name+" calls="+p.controller.loads.size());
                for(Object[] call:p.controller.loads){require((Integer)call[9]==type,name+" wrong type");require((Integer)call[3]==50,name+" wrong budget");}
                require(Arrays.equals(beforeEnd,p.endReached)&&Arrays.equals(beforeCache,p.cacheEndReached)&&Arrays.equals(beforeForward,p.forwardEndReached),name+" changed end flags");
                int searchCalls=p.media.searches+HashtagSearchController.searches;
                if(count==0&&searchCalls==0)require(!p.loading&&!p.loadingForward&&p.waitingForLoad.isEmpty()&&p.lastLoadIndex==10,name+" left pending state");
                if(count>0)require(p.waitingForLoad.size()==count&&p.lastLoadIndex==10+count&&(type==0?p.loading:p.loadingForward),name+" lost pending state");
                groups++;System.out.println("PASS "+name);
            }
        """;

    private static final String SCENARIOS = """
            /** 首次布局及回最新不拉旧页；阅读、定位、搜索、普通Telegram和向新路径保留。 */
            public static void main(String[] args){HistoryScrollIntentProbe p;
                p=fresh();p.checkScrollForLoad(false);expect(p,0,0,"initial_load_callback");
                p=fresh();p.checkScrollForLoad(true);expect(p,0,0,"layout_onScrolled_zero_dy");
                p=fresh();p.wasManualScroll=true;p.checkScrollForLoad(true);expect(p,0,0,"programmatic_settling_sticky_manual");
                p=fresh();p.chatAdapter.count=0;p.chatListView.positions=new int[0];p.checkScrollForLoad(true);expect(p,0,0,"layout_no_children");
                p=fresh();p.codexHistoryReadingLocked=true;p.codexHistoryOlderRetryRequired=true;p.wasManualScroll=true;p.lockCodexHistoryReading();p.checkScrollForLoad(false);p.checkScrollForLoad(true);expect(p,0,0,"failed_layout_and_programmatic_scroll_stay_idle");require(p.codexHistoryOlderRetryRequired,"generic lock cleared retry gate");p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,1,0,"fresh_drag_retries_failure");require(!p.codexHistoryOlderRetryRequired,"fresh drag did not reopen gate");
                p=fresh();CodexRuntime.owns=false;p.codexHistoryOlderRetryRequired=true;p.checkScrollForLoad(false);expect(p,1,0,"telegram_ignores_codex_retry_gate");
                // 零消耗场景只调用真实状态入口，绝不手工补 onScrolled 或 checkScrollForLoad。
                p=fresh();p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,1,0,"dragging_zero_consumed");require(p.codexHistoryReadingLocked&&p.wasManualScroll,"dragging did not retain original intent flags");
                p=fresh();p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_SETTLING);expect(p,0,0,"settling_no_new_check");require(p.wasManualScroll&&!p.codexHistoryReadingLocked,"settling semantic intent changed");
                p=fresh();p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_IDLE);expect(p,0,0,"idle_no_new_check");
                p=fresh();CodexRuntime.owns=false;p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,0,0,"telegram_dragging_no_new_check");require(!p.codexHistoryReadingLocked&&p.wasManualScroll,"ordinary Telegram flags changed");
                p=fresh();p.chatMode=2;p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,0,0,"other_mode_dragging_no_new_check");
                p=fresh();p.chatMode=MODE_SEARCH;p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,0,0,"search_dragging_no_new_check");require(HashtagSearchController.searches==0,"dragging added hashtag search");
                p=fresh();p.chatAdapter.isFiltered=true;p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,0,0,"filtered_dragging_no_new_check");require(p.media.searches==0,"dragging added filtered search");
                p=fresh();p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);p.requestCodexLatestHistory();expect(p,0,0,"dragging_queue_then_latest");
                p=fresh();p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,1,0,"repeated_dragging_no_duplicate_load");
                p=fresh();p.codexHistoryReadingLocked=true;p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,1,0,"existing_reading_short_list_dragging");
                p=fresh();p.chatAdapter.count=100;p.onScrollStateChanged(null,RecyclerView.SCROLL_STATE_DRAGGING);expect(p,0,0,"dragging_far_from_older_edge");
                p=fresh();p.lockCodexHistoryReading();p.wasManualScroll=true;p.checkScrollForLoad(false);p.requestCodexLatestHistory();require(CodexRuntime.latest==1&&!p.codexHistoryReadingLocked&&p.wasManualScroll,"latest did not clear semantic lock only");expect(p,0,0,"queued_old_check_then_user_latest");
                p=fresh();p.checkScrollForLoad(false);p.lockCodexHistoryReading();expect(p,1,0,"queued_layout_then_user_reading");
                p=fresh();p.lockCodexHistoryReading();p.checkScrollForLoad(false);expect(p,1,0,"actual_reading_lock_preserved");
                p=fresh();p.startLoadFromMessageId=77;p.firstLoadIntent();require(p.codexHistoryReadingLocked,"anchor did not lock");p.checkScrollForLoad(false);expect(p,1,0,"anchor_load_preserved");
                p=fresh();p.startLoadFromDate=100;p.firstLoadIntent();require(p.codexHistoryReadingLocked,"date did not lock");p.checkScrollForLoad(false);expect(p,1,0,"date_load_preserved");
                p=fresh();p.codexHistoryReadingLocked=true;p.checkScrollForLoad(false);expect(p,1,0,"resumed_bookmark_reading_preserved");
                p=fresh();CodexRuntime.owns=false;p.checkScrollForLoad(false);expect(p,1,0,"ordinary_telegram_preserved");
                p=fresh();p.chatMode=2;p.checkScrollForLoad(false);expect(p,1,0,"other_mode_preserved");
                p=fresh();p.chatAdapter.isFiltered=true;p.checkScrollForLoad(false);expect(p,0,0,"filtered_search_no_history");require(p.media.searches==1,"filtered search changed");
                p=fresh();p.chatMode=MODE_SEARCH;p.checkScrollForLoad(false);expect(p,0,0,"hashtag_search_no_history");require(HashtagSearchController.searches==1,"hashtag search changed");
                p=fresh();p.endReached[0]=true;p.forwardEndReached[0]=false;p.checkScrollForLoad(false);expect(p,1,1,"newer_load_preserved");
                p=fresh();CodexRuntime.owns=false;p.endReached[0]=true;p.endReached[1]=false;p.mergeDialogId=99;p.checkScrollForLoad(false);expect(p,1,0,"telegram_merged_history_preserved");
                p=fresh();p.lockCodexHistoryReading();p.checkScrollForLoad(false);p.codexHistoryViewRevision++;expect(p,0,0,"stale_layout_dropped");
                p=fresh();p.lockCodexHistoryReading();p.checkScrollForLoad(false);p.codexHistoryEpoch="next";expect(p,0,0,"stale_epoch_dropped");
                p=fresh();p.lockCodexHistoryReading();p.checkScrollForLoad(false);p.codexHistoryViewToken++;expect(p,0,0,"stale_token_dropped");
                p=fresh();p.paused=true;p.lockCodexHistoryReading();p.checkScrollForLoad(false);expect(p,0,0,"paused_preserved");
                p=fresh();p.codexHistoryViewToken=0;p.checkScrollForLoad(false);expect(p,0,0,"unbound_preserved");
                p=fresh();p.chatAdapter.isFrozen=true;p.checkScrollForLoad(false);expect(p,0,0,"frozen_preserved");
                System.out.println("HistoryScrollIntentTest groups="+groups+" failures=0 actual_complete_check=true actual_complete_state=true no_network=true");
            }
        """;
}
