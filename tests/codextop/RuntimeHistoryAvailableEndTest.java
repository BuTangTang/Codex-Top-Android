package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.IfStmt;
import java.nio.file.Path;

/** 执行真实历史加载、交付及Telegram结束判断，区分可读边界和来源完整性。 */
public final class RuntimeHistoryAvailableEndTest {
    /** 可传入本片前像复现RED；网络、账号和文件只使用共享夹具的合成边界。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java");
        Path runtime = args.length == 0 ? source.resolve("com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var chat = StaticJavaParser.parse(source.resolve("org/telegram/ui/ChatActivity.java"));
        var receiver = chat.getClassByName("ChatActivity").orElseThrow().getMethodsByName("didReceivedNotification_messagesDidLoad").get(0);
        var endings = receiver.findAll(IfStmt.class).stream().filter(statement -> statement.getCondition().toString()
                .equals("messArr.size() < count && load_type != 3 && load_type != 4")).toList();
        if (endings.size() != 1) throw new AssertionError("实际Telegram旧页结束分支不唯一");
        var controller = StaticJavaParser.parse(source.resolve("org/telegram/messenger/MessagesController.java"));
        var routes = controller.getClassByName("MessagesController").orElseThrow().getMethodsByName("loadMessages").stream()
                .filter(method -> method.toString().contains("com.butang.codextop.CodexRuntime.loadMessages(")).toList();
        if (routes.size() != 1) throw new AssertionError("实际历史路由不唯一");
        var chatOwner = chat.getClassByName("ChatActivity").orElseThrow();
        String clear = chatOwner.getMethodsByName("clearChatData").get(0).toString();
        String prepare = chatOwner.getMethodsByName("prepareCodexHistoryPage").get(0).toString();
        if (prepare.indexOf("clearChatData(true)") <= prepare.indexOf("CodexRuntime.acceptLatestHistory(page)"))
            throw new AssertionError("实际latest接收未按接受后清空顺序重置列表");
        // 完整提取原分支；夹具只提供通知参数及普通非加密聊天的已有消息状态。
        String consumer = """
            static class ChatEndProbe{
                ArrayList<Object> messages=new ArrayList<>(),waitingForLoad=new ArrayList<>();
                HashMap<Object,Object> messagesByDays=new HashMap<>(),messagesByDaysSorted=new HashMap<>(),groupedMessagesMap=new HashMap<>();
                HashMap[] messagesDict={new HashMap<>(),new HashMap<>()};
                int[] maxMessageId=new int[2],minMessageId=new int[2],maxDate=new int[2],minDate=new int[2];
                boolean[] endReached={false,false},cacheEndReached={false,false},forwardEndReached={true,true};
                Object currentEncryptedChat,unreadMessageObject;long mergeDialogId;
                boolean threadMessageAdded,first,firstLoading,loading,loadingForward,waitingForReplyMessageLoad,showScrollToMessageError,createUnreadMessageAfterIdLoading,needSelectFromMessageId;
                int startLoadFromMessageId,last_message_id,createUnreadMessageAfterId;
                ChatAdapterStub chatAdapter;ChatListStub chatListView;
                /** 视图边界只承载原clear方法调用，不参与任何结束判断。 */
                static class ChatAdapterStub{int botInfoRow;boolean isFiltered;/** 原方法通知边界。 */void notifyDataSetChanged(boolean value){}}
                /** 原方法列表空视图边界。 */
                static class ChatListStub{/** 记录之外不新增显示行为。 */void setEmptyView(Object value){}}
                /** 原方法全屏加载视图边界，与本测试的列表加载行分离。 */
                void showProgressView(boolean value){}
                /** 加载行渲染由独立专项执行，本夹具只验证历史结束契约。 */
                void updateCodexHistoryLoadingCells(){}
            /** 消费真正Telegram长度/类型分支，不在测试重新实现结束条件。 */
            @SuppressWarnings("unchecked") boolean consume(Event event,boolean existing){
                ArrayList<MessageObject> messArr=(ArrayList<MessageObject>)event.args[2];
                int count=(Integer)event.args[1],load_type=(Integer)event.args[8],loadIndex=0;
                boolean isCache=(Boolean)event.args[3],isEnd=(Boolean)event.args[9];
                if(existing||!messArr.isEmpty())messages.add(new Object());
            """ + endings.get(0) + "\nreturn endReached[0];}\n" + clear + "\n}\n"
                + "/** 独立通知用新聊天状态执行原结束分支。 */ static boolean uiOlderEnd(Event event,boolean existing){return new ChatEndProbe().consume(event,existing);}\n";
        // 只把静态类名绑定到计数边界，完整路由方法的条件、参数与普通回退均原样执行。
        String route = "static class RouteProbe{int currentAccount;/** 记录实际普通Telegram回退，不模拟其加载实现。 */ void loadMessagesInternal(Object...args){RouteBoundary.telegram++;}\n"
                + routes.get(0).toString().replace("com.butang.codextop.CodexRuntime", "RouteBoundary") + "\n}\n";
        RuntimeLatestSegmentTest.runScenarios(runtime, consumer + route + SCENARIOS, "availableEndScenarios();");
    }

    private static final String SCENARIOS = """
        static int availableGroups;
        /** 包边界替身仅计数；普通Telegram分支仍来自完整生产路由。 */
        static class RouteBoundary{
            static boolean enabled,owns;static int nativeLoads,telegram;
            /** 模拟构建是否开启Codex接入，不代替正式路由条件。 */
            static boolean enabled(){return enabled;}
            /** 模拟会话归属，让四种布尔组合都经过正式门禁。 */
            static boolean ownsConversation(long id){return owns;}
            /** 记录生产路由实际选择了Codex入口。 */
            static void loadMessages(Object...args){nativeLoads++;}
        }
        /** 通过真实prepend形成不完整来源，不直接赋值complete或hasMore。 */
        static TranscriptWindow terminalWindow(String ids,int reason)throws Exception{
            JsonObject response=page(ids,false,null,"terminal-tail");
            if(reason==0)response.remove("historyAvailability");
            else if(reason==1)response.addProperty("historyAvailability","unavailable");
            else if(reason==2)response.addProperty("historyAvailability","preview_only");
            else response.addProperty("truncationReason","source_discontinuity");
            TranscriptWindow window=new TranscriptWindow();window.prepend(response);
            check(window.loaded&&!window.hasMore&&!window.complete,"invalid incomplete fixture");return window;
        }
        /** 每次加载使用原通知和当前段，清理上次观察而不改产品状态。 */
        static Event availableLoad(int count,int anchor,int type,int index)throws Exception{
            events.clear();loadMessages(0,42,count,anchor,7,type,index,0);pump();
            Event delivery=normal();int notices=0;for(Event event:events)if(event.type==NotificationCenter.messagesDidLoad)notices++;
            check(notices==1&&isHistoryPageCurrent(metadata(delivery)),"wrong delivery identity or count");
            check((Integer)delivery.args[11]==index&&(Integer)delivery.args[8]==type,"load parameters changed");return delivery;
        }
        /** 判定交付可读边界及实际UI结果，禁止把缓存完整性改为成功。 */
        static void expectAvailable(Event event,String sources,boolean ended,boolean uiEnd){
            check(rows(event).equals(sources),"available page rows="+rows(event));
            check(uiOlderEnd(event,true)==uiEnd,"actual Telegram older end mismatch: count="+event.args[1]+" rows="+rows(event)+" isEnd="+event.args[9]);
            check((Boolean)event.args[9]==ended,"available boundary not delivered: rows="+rows(event)+" count="+event.args[1]+" end="+event.args[9]);
        }
        /** 最新三条先交付，再由原type0请求清理加载行；来源元数据与持久化保持不完整。 */
        static void terminalLatest(int reason,boolean empty)throws Exception{
            TranscriptWindow window=terminalWindow(empty?"":"A,B,C",reason);JsonObject before=window.snapshot();reset(window);
            try{
                openHistoryView(0,42,7);Event first=availableLoad(20,0,2,1);
                check(rows(first).equals(empty?"":"C,B,A"),"terminal initial page rows changed");
                check(uiOlderEnd(first,!empty)==empty,"native initial type2 behavior changed");
                if(!empty)expectAvailable(availableLoad(50,window.findSource("A",null).id,0,2),"",true,true);
                check((Boolean)first.args[9],"terminal initial page did not advertise available boundary");
                check(current.requests.isEmpty()&&saves==0&&!window.complete&&!window.hasMore,"terminal page fetched or claimed complete");
                check(before.equals(window.snapshot())&&before.equals(store.read("synthetic-remote").snapshot()),"terminal metadata changed");
                availableGroups++;System.out.println("PASS terminal reason="+reason+" empty="+empty);
            }finally{cleanup();}
        }
        /** 没有服务端游标仍先读完本地剩余条目；count加一哨兵不能提前结束。 */
        static void remainingLocal()throws Exception{
            TranscriptWindow window=terminalWindow("A,B,C,D,E",1);JsonObject before=window.snapshot();reset(window);
            try{
                openHistoryView(0,42,7);
                expectAvailable(availableLoad(2,0,0,1),"E,D",false,false);
                expectAvailable(availableLoad(2,window.findSource("D",null).id,0,2),"C,B",false,false);
                expectAvailable(availableLoad(2,window.findSource("B",null).id,0,3),"A",true,true);
                check(current.requests.isEmpty()&&before.equals(window.snapshot()),"local tail was changed or fetched");
                availableGroups++;System.out.println("PASS remaining local pages before terminal boundary");
            }finally{cleanup();}
        }
        /** 服务端游标可继续时保留原逐页请求、保存与最终完整声明。 */
        static void serverPages()throws Exception{
            TranscriptWindow window=cache("D,E");reset(window);
            try{
                openHistoryView(0,42,7);expectAvailable(availableLoad(20,0,2,1),"E,D",false,false);
                current.older("old-older",page("B,C",true,"next-older",null));
                expectAvailable(availableLoad(2,window.findSource("D",null).id,0,2),"C,B",false,false);
                check(!window.complete&&window.hasMore,"intermediate page falsely ended");
                current.older("next-older",page("A",false,null,null));
                expectAvailable(availableLoad(2,window.findSource("B",null).id,0,3),"A",true,true);
                check(current.requests.equals(Arrays.asList("older:old-older","older:next-older"))&&saves==2&&window.complete,"server paging changed");
                check(window.snapshot().equals(store.read("synthetic-remote").snapshot()),"server result not saved");
                availableGroups++;System.out.println("PASS server cursor pages and complete final page");
            }finally{cleanup();}
        }
        /** 合法空投影继续原四页预算，只有服务端明确无下一页才结束。 */
        static void projectionBudget(boolean exhausted)throws Exception{
            TranscriptWindow window=cache("A,B");reset(window);
            try{
                openHistoryView(0,42,7);
                for(int n=0;n<4;n++)current.older(n==0?"old-older":"page-"+n,page("",!(exhausted&&n==3),n==3&&exhausted?null:"page-"+(n+1),null));
                expectAvailable(availableLoad(20,window.findSource("A",null).id,0,1),"",exhausted,exhausted);
                check(current.requests.size()==4&&saves==4&&window.complete==exhausted&&window.hasMore!=exhausted,"four page budget or source state changed");
                availableGroups++;System.out.println("PASS empty projection four page budget exhausted="+exhausted);
            }finally{cleanup();}
        }
        /** 缓存桥接沿旧段复用真实hasMore/complete，拼出的本地前缀仍全部可读。 */
        static void incompleteCachedBridge()throws Exception{
            TranscriptWindow old=terminalWindow("A,B,C",2);
            TranscriptWindow latest=old.acceptLatest(page("G,H",true,"gap-older","new-tail"));reset(latest);
            try{
                openHistoryView(0,42,7);expectAvailable(availableLoad(20,0,2,1),"H,G",false,false);
                current.older("gap-older",page("C,D,E,F",true,"unused-cursor",null));
                expectAvailable(availableLoad(50,latest.findSource("G",null).id,0,2),"F,E,D,C,B,A",true,true);
                check(!latest.hasMore&&!latest.complete&&ids(latest).equals("H,G,F,E,D,C,B,A"),"bridge lost incomplete source or prefix");
                check(current.requests.equals(Collections.singletonList("older:gap-older"))&&saves==1,"bridge fetched past available source");
                check(!store.read("synthetic-remote").complete,"saved bridge falsely marked complete");
                availableGroups++;System.out.println("PASS incomplete cached bridge preserves all local rows");
            }finally{cleanup();}
        }
        /** 居中锚点仍要求完整性证明；不借可读边界伪造缺失锚点成功。 */
        static void anchorsUnchanged()throws Exception{
            TranscriptWindow window=terminalWindow("A,B,C",1);reset(window);
            try{
                openHistoryView(0,42,7);int oldest=window.findSource("A",null).id;
                Event found=availableLoad(20,oldest,3,1);check(!(Boolean)found.args[9]&&(Integer)found.args[12]==oldest,"around anchor falsely complete");
                Event missing=availableLoad(20,oldest-2,3,2);check(!(Boolean)missing.args[9]&&(Integer)missing.args[12]==oldest-2,"missing anchor accepted");
                check(current.requests.isEmpty()&&!window.complete,"anchor changed unavailable source");
                availableGroups++;System.out.println("PASS found and missing around anchors unchanged");
            }finally{cleanup();}
        }
        /** 新向页仍只看实际尾游标，不能被旧页无游标误判。 */
        static void newerUnchanged(boolean tail)throws Exception{
            JsonObject response=page("A,B,C",false,null,tail?"tail":null);response.remove("historyAvailability");
            TranscriptWindow window=new TranscriptWindow();window.prepend(response);reset(window);
            try{
                openHistoryView(0,42,7);Event newer=availableLoad(20,window.findSource("A",null).id,1,1);
                check(rows(newer).equals("C,B")&&(Boolean)newer.args[9]==tail&&!window.complete,"newer semantics changed");
                availableGroups++;System.out.println("PASS newer tail proof="+tail);
            }finally{cleanup();}
        }
        /** 从未加载的空窗口仍未知，不能因为没有正文就宣告来源到边界。 */
        static void unloadedUnchanged()throws Exception{
            TranscriptWindow window=new TranscriptWindow();reset(window);
            try{
                current=null;openHistoryView(0,42,7);Event first=availableLoad(20,0,2,1);
                check(!(Boolean)first.args[9]&&!uiOlderEnd(first,false)&&!window.loaded&&!window.complete&&window.hasMore,"unloaded source falsely ended");
                availableGroups++;System.out.println("PASS unloaded source remains unknown");
            }finally{cleanup();}
        }
        /** 结束通知排到UI后，暂停、账号变化和阅读意图更新仍使旧回包失效。 */
        static void lateTerminal(String invalidation)throws Exception{
            TranscriptWindow window=terminalWindow("A,B,C",1);reset(window);
            try{
                long token=openHistoryView(0,42,7);loadMessages(0,42,20,window.findSource("A",null).id,7,0,1,0);
                Utilities.globalQueue.next();check(!AndroidUtilities.ui.ready.isEmpty(),"terminal delivery not queued");
                if(invalidation.equals("pause"))pauseHistoryView(7,token);
                else if(invalidation.equals("account"))accountGeneration++;
                else historyViews.get(7).intentRevision++;
                ui();check(events.isEmpty()&&!window.complete,"stale terminal callback escaped");
                availableGroups++;System.out.println("PASS late terminal rejected="+invalidation);
            }finally{cleanup();}
        }
        /** 新latest段重新有旧游标时，实际clearChatData清除上段结束位，原分页可以恢复。 */
        static void replacementReopens()throws Exception{
            TranscriptWindow old=terminalWindow("A,B,C",1);reset(old);
            try{
                openHistoryView(0,42,7);ChatEndProbe chat=new ChatEndProbe();
                check(chat.consume(availableLoad(50,old.findSource("A",null).id,0,1),true),"old readable boundary did not close");
                events.clear();openingBacklog();current.latest=page("Y,Z",true,"recovered-older","recovered-tail");watchConversation(0,42);pump();
                Event candidate=replacement();TranscriptWindow latest=histories.get(42L);
                check(latest!=old&&latest.hasMore&&!latest.complete&&acceptLatestHistory(metadata(candidate)),"recovered latest not accepted");
                chat.clearChatData(true);check(!chat.endReached[0]&&!chat.endReached[1]&&chat.loading,"real clear did not reopen old paging");
                check(!chat.consume(candidate,false),"replacement immediately closed recovered paging");
                current.older("recovered-older",page("X",false,null,null));
                expectAvailable(availableLoad(50,latest.findSource("Y",null).id,0,2),"X",true,true);
                check(current.requests.equals(Arrays.asList("after:terminal-tail","latest","older:recovered-older"))&&!old.complete,"reopened paging or original source changed");
                availableGroups++;System.out.println("PASS latest replacement reopens actual Telegram end state and older RPC");
            }finally{cleanup();}
        }
        /** 四种启用与归属组合执行完整真实路由，普通Telegram始终走其原内部入口。 */
        static void telegramRouteUnchanged(){
            for(boolean enabled:new boolean[]{false,true})for(boolean owns:new boolean[]{false,true}){
                RouteBoundary.enabled=enabled;RouteBoundary.owns=owns;RouteBoundary.nativeLoads=RouteBoundary.telegram=0;
                new RouteProbe().loadMessages(42,0,false,20,0,0,false,0,7,2,0,0,0,1,0,0,0,false,0,false);
                check(RouteBoundary.nativeLoads==(enabled&&owns?1:0)&&RouteBoundary.telegram==(enabled&&owns?0:1),"native gate changed");
            }
            availableGroups++;System.out.println("PASS ordinary Telegram route all four gates");
        }
        /** 固定合成组覆盖本地边界、服务端分页、缓存桥接及迟到通知；不操作设备。 */
        static void availableEndScenarios()throws Exception{
            for(int reason=0;reason<4;reason++){terminalLatest(reason,false);terminalLatest(reason,true);}
            remainingLocal();serverPages();projectionBudget(false);projectionBudget(true);incompleteCachedBridge();
            anchorsUnchanged();newerUnchanged(false);newerUnchanged(true);unloadedUnchanged();
            lateTerminal("pause");lateTerminal("account");lateTerminal("intent");replacementReopens();telegramRouteUnchanged();
            System.out.println("RuntimeHistoryAvailableEnd: groups="+availableGroups+" failures=0; actual Runtime, Window, Store, Telegram consumer and route / Java8");
        }
        """;
}
