package com.butang.codextop;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取实际聊天段接收、阅读位置和生命周期方法，只替代Android视图、偏好及Runtime边界。 */
public final class HistorySegmentUiTest {
    /** 旧源码red模式执行同一通知前置逻辑；全部消息、身份和书签均为合成数据。 */
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") : Path.of(args[0]);
        String text = Files.readString(source);
        boolean red = args.length > 1 && args[1].equals("red");
        StringBuilder methods = new StringBuilder();
        for (String name : new String[]{"cancelCodexHistoryInitialFill", "isCodexHistoryView", "resetCodexUnscopedInitialAnchor", "openCodexHistoryView", "resumeCodexHistoryView", "pauseCodexHistoryView",
                "clearCodexHistoryScrollTargets", "isCodexHistoryScrollCurrent", "prepareCodexHistoryPage", "acceptsCodexHistoryIncrement", "lockCodexHistoryReading",
                "requestCodexLatestHistory", "codexHistoryBookmarkPrefix", "clearCodexHistoryBookmark", "saveCodexHistoryBookmark",
                "reloadCodexBoundHistory", "restoreCodexHistoryBookmark"}) {
            String value = method(text, name);
            if (value != null) methods.append(value).append('\n');
            else if (!red) throw new AssertionError("缺少实际段方法：" + name);
        }
        String receive = method(text, "didReceivedNotification_messagesDidLoad");
        String prefix = receive.substring(receive.indexOf('{') + 1, receive.indexOf("        int queryLoadIndex"));
        methods.append("void receive(Object... received){")
                .append(prefix.contains("Object[] args =") ? "" : "Object[] args=received;")
                .append(prefix).append("consumed++;receivedArgs=args;}\n");
        if (!red) {
            methods.append(method(text, "updatePagedownButtonVisibility")).append('\n');
            String first = method(text, "firstLoadMessages");
            methods.append("void firstLoadMessages(){").append(first, first.indexOf('{') + 1, first.indexOf("        final Runnable load ="))
                    .append("loads++;}\n");
            int preferencesStart = text.indexOf("                if (startLoadFromMessageId == 0", text.indexOf("getMessagesController().loadPeerSettings(currentUser, currentChat)"));
            int preferencesEnd = text.indexOf("            }\n        }\n\n        loadInfo = false;", preferencesStart);
            methods.append("void legacyPosition(){").append(text, preferencesStart, preferencesEnd).append("}\n");
            String check = method(text, "checkScrollForLoad");
            int queued = check.indexOf("        final long codexQueuedToken"), body = check.indexOf("            if (totalItemCount", queued);
            methods.append("void queueScroll(){").append(check, queued, body).append("queuedReads++;});}\n");
            String resume = method(text, "onResume");
            int resumeGuard = resume.indexOf("        if (!isCodexHistoryScrollCurrent(codexResumeToken"), resumeEnd = resume.indexOf("        if (scrollToTopOnResume)", resumeGuard);
            methods.append("void guardResumeScroll(long codexResumeToken,String codexResumeEpoch,long codexResumeRevision){")
                    .append(resume, resumeGuard, resumeEnd).append("}\n");
            int jumpStart = text.indexOf("    public void scrollToMessageId(int id, int fromMessageId, boolean select, int loadIndex, boolean forceScroll, int forcePinnedMessageId, Integer taskId, byte[] pollOptionId, Runnable inCaseLoading)");
            int jumpBody = text.indexOf('{', jumpStart), jumpEnd = text.indexOf("        if (id == 0 ||", jumpBody);
            methods.append("void jumpWithinBoundHistory(int id){").append(text, jumpBody + 1, jumpEnd).append("}\n");
            require(method(text, "onPause"), "saveCodexHistoryBookmark();", "pauseCodexHistoryView(false);", "super.onPause();");
            require(method(text, "onFragmentDestroy"), "pauseCodexHistoryView(true);", "super.onFragmentDestroy();");
            require(method(text, "onResume"), "final long codexResumeRevision = codexHistoryViewRevision;", "resumeCodexHistoryView();", "CodexRuntime.watchConversation", "if (!isCodexHistoryScrollCurrent(codexResumeToken", "if (scrollToTopOnResume)");
            require(method(text, "onFragmentCreate"), "dialog_id_Long = dialog_id;", "resetCodexUnscopedInitialAnchor();", "firstLoadMessages();");
            if (!text.contains("newState == RecyclerView.SCROLL_STATE_DRAGGING) {\n                        lockCodexHistoryReading();"))
                throw new AssertionError("真实拖动没有锁定阅读段");
            if (!method(text, "onPageDownClicked").contains("if (requestCodexLatestHistory()) return;"))
                throw new AssertionError("回最新没有接入真实点击入口");
            if (!text.contains("if (!acceptsCodexHistoryIncrement(args)) return;")) throw new AssertionError("watch通知没有段门禁");
            if (!method(text, "onPause").contains("!isCodexHistoryView() && !pausedOnLastMessage")) throw new AssertionError("仍写旧整数书签");
        }
        Path temporary = Files.createTempDirectory("codex-history-ui-");
        try {
            Path probe = temporary.resolve("HistorySegmentUiProbe.java"), runtime = temporary.resolve("CodexRuntime.java");
            Files.writeString(probe, FIXTURE + methods + (red ? RED : SCENARIOS) + "\n}");
            Files.writeString(runtime, RUNTIME);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-d", temporary.toString(), probe.toString(), runtime.toString()) != 0)
                throw new AssertionError("实际UI历史段方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, HistorySegmentUiTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.HistorySegmentUiProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("实际UI历史段行为失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** 验证生命周期接线顺序，不把未调用的辅助方法误当成已接入。 */
    private static void require(String source, String... calls) {
        int position = -1;
        for (String call : calls) { position = source.indexOf(call, position + 1); if (position < 0) throw new AssertionError("生命周期接线缺失或顺序错误：" + call); }
    }

    /** 词法提取原方法，字符串及注释内花括号不参与边界；没有第二套产品判断。 */
    private static String method(String source, String name) {
        var matcher = java.util.regex.Pattern.compile("(?m)^    (?:public|private|protected)[^\\n]*\\b" + name + "\\([^\\n]*").matcher(source);
        if (!matcher.find()) return null;
        int start = matcher.start(), open = source.indexOf('{', start), depth = 0;
        boolean string = false, character = false, line = false, block = false, escape = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (string || character) { if (escape) escape = false; else if (c == '\\') escape = true; else if (string && c == '"') string = false; else if (character && c == '\'') character = false; continue; }
            if (c == '/' && next == '/') { line = true; i++; } else if (c == '/' && next == '*') { block = true; i++; }
            else if (c == '"') string = true; else if (c == '\'') character = true;
            else if (c == '{') depth++; else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("实际方法未闭合：" + name);
    }

    private static final String RUNTIME = """
        package com.butang.codextop;
        import java.util.function.Consumer;
        public class CodexRuntime {
            static boolean owns=true,accept=true,current=true;static long nextToken=10;static int opens,pauses,closes,accepted,latest,resolves;
            static String scope="scope-one";static Consumer<HistoryBookmark> callback;
            public static class HistoryPage {public long token;public String fromEpoch,toEpoch;public boolean replaceLatest;public HistoryPage(long t,String f,String n,boolean r){token=t;fromEpoch=f;toEpoch=n;replaceLatest=r;}}
            public static class HistoryBookmark {public int messageId;public String epoch;public HistoryBookmark(int id,String e){messageId=id;epoch=e;}}
            public static boolean ownsConversation(long id){return owns;}
            /** 本专项账号存活为固定边界，账号失效由真实Runtime组合专项覆盖。 */ public static boolean isHistoryViewCurrent(int account,long dialog,int guid,long token,String epoch){return true;}
            public static long openHistoryView(int a,long d,int g){opens++;return ++nextToken;}
            public static void pauseHistoryView(int g,long t){pauses++;}
            public static void closeHistoryView(int g,long t){closes++;}
            public static boolean acceptLatestHistory(HistoryPage page){accepted++;return accept;}
            public static boolean isHistoryPageCurrent(HistoryPage page){return current;}
            public static void requestLatestHistory(int a,long d,int g,long t){latest++;}
            public static String historyBookmarkKey(int a,long d){return scope;}
            public static void resolveHistoryBookmark(int a,long d,int g,long t,String s,String l,Consumer<HistoryBookmark> c){resolves++;callback=c;}
            static void reset(){owns=true;accept=true;current=true;nextToken=10;opens=pauses=closes=accepted=latest=resolves=0;scope="scope-one";callback=null;}
        }
        """;

    // 只记录真实方法对视图、偏好及通知的操作，不模拟分页或Runtime身份算法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public class HistorySegmentUiProbe {
            static final int MODE_DEFAULT=0;
            boolean codexHistoryOlderContinueAvailable;long codexHistoryInitialFillToken;Object codexHistoryInitialFillTicket;
            long dialog_id=42,codexHistoryViewToken=11,codexHistoryPausedToken,codexHistoryViewRevision;int currentAccount,classGuid=7,chatMode;
            String codexHistoryEpoch="old";boolean codexHistoryReadingLocked,codexHistoryLatestAvailable,codexHistoryOlderRetryRequired;
            int consumed,clearCalls,loads,queuedReads,lastLoadIndex=4;Object[] receivedArgs;
            ArrayList<Integer> waitingForLoad=new ArrayList<>();ArrayList<MessageObject> messages=new ArrayList<>();
            ScrollHelper chatScrollHelper=new ScrollHelper();Layout chatLayoutManager=new Layout();ChatList chatListView=new ChatList();
            MessageObject scrollToMessage;int scrollToMessagePosition=-10000,startLoadFromMessageId,startLoadFromMessageIdSaved,startLoadFromMessageOffset=Integer.MAX_VALUE,startLoadFromDate;
            int postponedScrollToLastMessageQueryIndex,postponedScrollMessageId,nextScrollToMessageId,nextScrollFromMessageId,forceNextPinnedMessageId,returnToMessageId,returnToLoadIndex,clearOnLoadAndScrollMessageId=-1,clearOnLoadAndScrollOffset;
            boolean scrollToTopOnResume,scrollToTopUnReadOnResume,postponedScrollIsCanceled,fakePostponedScroll,clearOnLoad,loading,loadingForward,firstMessagesLoaded;
            boolean wasManualScroll,loadingFromOldPosition,showScrollToMessageError,needSelectFromMessageId,waitingForGetDifference;
            static class Adapter {boolean isFiltered;}Adapter chatAdapter=new Adapter();
            /** 渲染边界由加载行专项执行；本专项保留段身份及书签断言。 */ void updateCodexHistoryLoadingCells(){}
            int highlightMessageId,startFromVideoMessageId,startFromVideoTimestamp=-1;boolean historyPreloaded;
            SideControls sideControlsButtonsLayout=new SideControls();InputView chatActivityEnterView=new InputView();MediaController media=new MediaController();
            ActionBar actionBar=new ActionBar();boolean textSelected;static class ActionBar{boolean selection;boolean isActionModeShowed(){return selection;}}
            boolean canShowPagedownButton,isInsideContainer,searching,pagedownButtonShowedByScroll;long openAnimationStartTime;int newUnreadMessageCount;
            static class SideControls {boolean shown;void showButton(int id,boolean show,boolean animated){shown=show;}}
            static class ChatActivitySideControlsButtonsLayout {static int BUTTON_PAGE_DOWN=1;}
            static class InputView {boolean isRecordingAudioVideo(){return false;}}
            static class MediaController {List<Object> searchResultMessages=new ArrayList<>();}
            static class SystemClock {static long elapsedRealtime(){return 1000;}}
            /** 默认关闭诊断边界；真实logger由独立专项执行。 */ void traceCodexHistoryViewport(String p,Object t,boolean e){}
            boolean hasTextSelection(){return textSelected;}MediaController getMediaDataController(){return media;}
            static class TextUtils {static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}
            static class ScrollHelper {int cancels;void cancel(){cancels++;}}
            static class View {int bottom=140;int getBottom(){return bottom;}}
            static class Owner {Map<String,String> params=new HashMap<>();}
            static class MessageObject {Owner messageOwner=new Owner();boolean outgoing;boolean isOut(){return outgoing;}}
            static class ChatMessageCell extends View {MessageObject message;ChatMessageCell(MessageObject m){message=m;}MessageObject getMessageObject(){return message;}}
            static class RecyclerView {static class ViewHolder {View itemView;ViewHolder(View v){itemView=v;}}}
            static class Layout {int position=3;int findFirstVisibleItemPosition(){return position;}}
            static class ChatList {Map<Integer,RecyclerView.ViewHolder> holders=new HashMap<>();RecyclerView.ViewHolder findViewHolderForAdapterPosition(int p){return holders.get(p);}int getMeasuredHeight(){return 200;}}
            static class SharedPreferences {Map<String,Object> values=new HashMap<>();String getString(String k,String d){return (String)values.getOrDefault(k,d);}int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}Editor edit(){return new Editor();}class Editor{Editor putString(String k,String v){if(v==null)values.remove(k);else values.put(k,v);return this;}Editor putInt(String k,int v){values.put(k,v);return this;}Editor remove(String k){values.remove(k);return this;}void apply(){}}}
            static class MessagesController {static SharedPreferences preferences=new SharedPreferences();static SharedPreferences getNotificationsSettings(int a){return preferences;}}
            static class NotificationsController {static String getSharedPrefKey(long d,long t){return Long.toString(d);}}
            static class AndroidUtilities {static ArrayDeque<Runnable> queue=new ArrayDeque<>();static void runOnUIThread(Runnable r){queue.add(r);}}
            long getTopicId(){return 0;}
            void clearChatData(boolean full){clearCalls++;messages.clear();waitingForLoad.clear();loading=full;loadingForward=false;startLoadFromMessageId=0;}
            static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
            static HistorySegmentUiProbe fresh(){CodexRuntime.reset();MessagesController.preferences=new SharedPreferences();AndroidUtilities.queue.clear();return new HistorySegmentUiProbe();}
            static Object[] page(long token,String from,String to,boolean replace){Object[] a={42L,1,new ArrayList<MessageObject>(),false,0,0,0,0,2,true,7,-1,0,0,0,new CodexRuntime.HistoryPage(token,from,to,replace)};return a;}
            static MessageObject message(String source,String epoch,boolean out){MessageObject m=new MessageObject();m.outgoing=out;m.messageOwner.params.put("codexSourceId",source);m.messageOwner.params.put("codexHistoryEpoch",epoch);m.messageOwner.params.put("codexLocalId","synthetic-local");return m;}
        """;

    private static final String RED = """
            public static void main(String[] args){HistorySegmentUiProbe p=fresh();p.receive(page(99,"old","new",true));check(p.consumed==0,"stale history token reached original messagesDidLoad consumption");}
        """;

    private static final String SCENARIOS = """
            public static void main(String[] args){
                HistorySegmentUiProbe p=fresh();p.receive(page(99,"old","new",true));check(p.consumed==0&&p.clearCalls==0,"stale token changed UI");
                p=fresh();p.receive(page(11,"wrong","new",true));check(p.consumed==0&&CodexRuntime.accepted==0,"wrong fromEpoch accepted");
                p=fresh();p.lockCodexHistoryReading();p.receive(page(11,"old","new",true));check(p.consumed==0&&p.clearCalls==0&&CodexRuntime.accepted==0,"active reading was replaced");
                check(p.codexHistoryLatestAvailable&&p.sideControlsButtonsLayout.shown,"old segment at its bottom has no latest button");p.canShowPagedownButton=false;p.updatePagedownButtonVisibility(false);check(p.sideControlsButtonsLayout.shown,"ordinary scroll hid latest candidate");
                p.requestCodexLatestHistory();p.receive(page(11,"old","new",true));p.updatePagedownButtonVisibility(false);check(!p.codexHistoryLatestAvailable&&!p.sideControlsButtonsLayout.shown,"accepted latest left stale available marker");
                p=fresh();p.textSelected=true;p.receive(page(11,"old","new",true));check(p.codexHistoryReadingLocked&&p.consumed==0&&p.codexHistoryLatestAvailable,"text selection was cleared by latest page");p.textSelected=false;p.updatePagedownButtonVisibility(false);check(p.sideControlsButtonsLayout.shown,"leaving text selection lost latest entry");
                p=fresh();p.actionBar.selection=true;p.receive(page(11,"old","new",true));check(p.codexHistoryReadingLocked&&p.consumed==0&&p.codexHistoryLatestAvailable,"message multi-selection was cleared by latest page");
                p=fresh();CodexRuntime.current=false;p.receive(page(11,"old","old",false));check(p.consumed==0,"same-epoch delayed page survived Runtime intent invalidation");check(!p.acceptsCodexHistoryIncrement(new Object[]{42L,List.of(),false,0,new CodexRuntime.HistoryPage(11,"old","old",false)}),"same-epoch delayed increment survived intent invalidation");
                p=fresh();CodexRuntime.accept=false;p.receive(page(11,"old","new",true));check(p.consumed==0&&p.clearCalls==0&&p.codexHistoryEpoch.equals("old"),"failed Runtime acceptance cleared UI");
                p=fresh();p.waitingForLoad.add(2);p.messages.add(message("old-source","old",false));p.scrollToMessage=p.messages.get(0);p.scrollToTopOnResume=true;p.startLoadFromMessageOffset=-45;
                Object[] candidate=page(11,"old","new",true);p.receive(candidate);
                check(p.consumed==1&&p.clearCalls==1&&p.codexHistoryEpoch.equals("new")&&p.scrollToMessage==null&&!p.scrollToTopOnResume,"accepted replacement retained old UI targets");
                check(p.receivedArgs!=candidate&&(Integer)candidate[11]==-1&&(Integer)p.receivedArgs[8]==2&&p.waitingForLoad.equals(List.of((Integer)p.receivedArgs[11])),"replacement mutated shared notification or has no legal load index");
                p.receive(page(11,"old","old",false));check(p.consumed==1,"old page consumed after epoch replacement");
                p=fresh();p.codexHistoryEpoch=null;p.receive(page(11,"seed","seed",false));check(p.consumed==1&&p.codexHistoryEpoch.equals("seed"),"first normal page did not establish epoch");
                p=fresh();p.codexHistoryEpoch=null;p.receive(page(11,"seed","new",true));check(p.consumed==1&&p.codexHistoryEpoch.equals("new"),"first Runtime-accepted replacement refused");
                p=fresh();check(!p.acceptsCodexHistoryIncrement(new Object[]{42L,List.of(),false,0,new CodexRuntime.HistoryPage(11,"other","other",false)}),"stale watch entered view");
                check(p.acceptsCodexHistoryIncrement(new Object[]{42L,List.of(),false,0}),"pending send without metadata was lost");
                check(p.acceptsCodexHistoryIncrement(new Object[]{42L,List.of(),false,0,new CodexRuntime.HistoryPage(11,"old","old",false)}),"same-epoch watch refused");
                p=fresh();p.queueScroll();p.clearCodexHistoryScrollTargets();AndroidUtilities.queue.remove().run();check(p.queuedReads==0,"same-token same-epoch old layout pagination survived reset");
                p.scrollToMessage=message("old-layout","old",false);p.scrollToTopOnResume=p.scrollToTopUnReadOnResume=true;p.guardResumeScroll(11,"old",0);check(p.scrollToMessage==null&&!p.scrollToTopOnResume&&!p.scrollToTopUnReadOnResume,"same-epoch stale resume object survived reset");
                p.scrollToMessage=message("new-layout","old",false);p.scrollToTopOnResume=true;p.guardResumeScroll(11,"old",p.codexHistoryViewRevision);check(p.scrollToMessage!=null&&p.scrollToTopOnResume,"current layout resume target was discarded");
                p=fresh();p.queueScroll();p.codexHistoryEpoch="new";AndroidUtilities.queue.remove().run();check(p.queuedReads==0,"queued old-epoch pagination executed");
                p.queueScroll();p.codexHistoryViewToken=12;AndroidUtilities.queue.remove().run();check(p.queuedReads==0,"queued old-token pagination executed");
                p.queueScroll();AndroidUtilities.queue.remove().run();check(p.queuedReads==1,"current pagination was blocked");
                p=fresh();p.codexHistoryReadingLocked=true;p.messages.add(message("a","old",false));p.firstMessagesLoaded=true;p.pauseCodexHistoryView(false);check(p.codexHistoryViewToken==0&&p.codexHistoryPausedToken==11&&CodexRuntime.pauses==1,"pause did not revoke active token");
                p.resumeCodexHistoryView();check(p.codexHistoryViewToken!=0&&p.codexHistoryReadingLocked&&p.messages.size()==1&&p.clearCalls==0&&CodexRuntime.latest==0,"resume displaced active reading");
                p.pauseCodexHistoryView(false);p.pauseCodexHistoryView(true);check(CodexRuntime.closes==1&&p.codexHistoryPausedToken==0,"destroy after pause leaked binding");
                p=fresh();p.firstMessagesLoaded=true;p.pauseCodexHistoryView(false);p.resumeCodexHistoryView();check(CodexRuntime.latest==1&&!p.codexHistoryReadingLocked,"ordinary resume did not request latest");
                p=fresh();p.codexHistoryReadingLocked=true;p.startLoadFromMessageId=123;p.startLoadFromMessageOffset=-30;p.pauseCodexHistoryView(false);p.resumeCodexHistoryView();check(p.loads==1&&p.startLoadFromMessageId==123&&p.startLoadFromMessageOffset==-30,"unfinished explicit read lost position on resume");
                p=fresh();MessagesController.preferences.values.put("diditem42",123);p.legacyPosition();check(p.startLoadFromMessageId==0&&!p.wasManualScroll&&!p.needSelectFromMessageId,"default opened legacy integer bookmark");
                CodexRuntime.owns=false;p.legacyPosition();check(p.startLoadFromMessageId==123&&p.wasManualScroll,"Telegram legacy bookmark changed");
                p=fresh();p.startLoadFromMessageId=123;p.highlightMessageId=123;p.historyPreloaded=true;p.startLoadFromDate=456;p.resetCodexUnscopedInitialAnchor();check(p.startLoadFromMessageId==0&&p.highlightMessageId==0&&!p.historyPreloaded&&p.startLoadFromDate==456,"unscoped initial integer entered latest epoch or erased date intent");
                p=fresh();CodexRuntime.owns=false;p.startLoadFromMessageId=123;p.resetCodexUnscopedInitialAnchor();check(p.startLoadFromMessageId==123,"Telegram explicit integer anchor changed");
                p=fresh();p.startLoadFromMessageId=123;p.legacyPosition();p.codexHistoryViewToken=0;p.firstLoadMessages();check(p.needSelectFromMessageId&&p.codexHistoryReadingLocked&&CodexRuntime.opens==1&&p.loads==1,"explicit initial anchor not bound before read");
                p=fresh();p.startLoadFromDate=123;p.firstLoadMessages();check(p.codexHistoryReadingLocked,"explicit date did not preserve reading intent");
                p=fresh();p.jumpWithinBoundHistory(123);p.receive(page(11,"old","new",true));check(p.codexHistoryReadingLocked&&p.consumed==0,"explicit in-view jump was displaced by latest candidate");
                p=fresh();p.codexHistoryReadingLocked=true;p.chatLayoutManager.position=0;p.chatListView.holders.put(0,new RecyclerView.ViewHolder(new ChatMessageCell(message("a:attachment:1","old",false))));p.saveCodexHistoryBookmark();
                check(MessagesController.preferences.getString("codexReading:scope-one:source","").equals("a:attachment:1")&&MessagesController.preferences.getInt("codexReading:scope-one:offset",0)==-60&&!MessagesController.preferences.values.containsKey("codexReading:scope-one:local"),"source bookmark/offset or incoming local identity wrong");
                check(p.codexHistoryReadingLocked,"old-segment bottom was mistaken for globally latest");
                p.messages.clear();p.restoreCodexHistoryBookmark();check(CodexRuntime.resolves==1,"active missing view did not resolve source");CodexRuntime.callback.accept(new CodexRuntime.HistoryBookmark(777,"archive"));check(p.startLoadFromMessageId==777&&p.startLoadFromMessageOffset==-60&&p.codexHistoryEpoch.equals("archive")&&p.loads==1,"exact bookmark did not bind resolved epoch/id");
                p=fresh();p.codexHistoryReadingLocked=true;MessagesController.preferences.values.put("codexReading:scope-one:source","a");p.restoreCodexHistoryBookmark();p.requestCodexLatestHistory();int loads=p.loads;CodexRuntime.callback.accept(new CodexRuntime.HistoryBookmark(777,"archive"));check(p.loads==loads&&!p.codexHistoryReadingLocked&&CodexRuntime.latest==1&&!MessagesController.preferences.values.containsKey("codexReading:scope-one:source"),"late bookmark displaced explicit latest");
                p=fresh();p.codexHistoryReadingLocked=true;MessagesController.preferences.values.put("codexReading:scope-one:source","missing");p.restoreCodexHistoryBookmark();CodexRuntime.callback.accept(new CodexRuntime.HistoryBookmark(0,null));check(p.loads==1&&p.startLoadFromMessageId==0&&p.codexHistoryEpoch.equals("old"),"missing bookmark guessed a neighboring integer or left empty view stalled");
                p=fresh();CodexRuntime.owns=false;p.receive(page(99,"wrong","new",true));check(p.consumed==1&&p.clearCalls==0,"Telegram notification path changed");
                System.out.println("HistorySegmentUi: 实际通知前置、段切换、阅读锁、暂停恢复、来源书签及排队滚动通过；不代替Android实机");
            }
        """;
}
