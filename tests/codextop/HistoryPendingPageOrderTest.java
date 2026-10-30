package com.butang.codextop;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 合成消息执行原恢复插入和原向旧页追加，覆盖两个各自正确分支的组合缺口。 */
public final class HistoryPendingPageOrderTest {
    /** 只提取真实ChatActivity块，不连接Android、网络或真实任务。 */
    public static void main(String[] args) throws Exception {
        Path sourcePath=Path.of(args.length==0?"TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java":args[0]);
        String source=Files.readString(sourcePath);
        Method block=RestoredPendingOrderTest.class.getDeclaredMethod("block",String.class,String.class);block.setAccessible(true);
        String process=(String)block.invoke(null,source,"private void processNewMessages(ArrayList<MessageObject> arr, final boolean animatedFromBottom)");
        String load=(String)block.invoke(null,source,"private void didReceivedNotification_messagesDidLoad(");
        String placement=(String)block.invoke(null,process,"if (placeToPaste == -1)");
        String outgoing=(String)block.invoke(null,process,"if (obj.isOut() && !(obj.messageOwner.action instanceof TLRPC.TL_messageActionTodoCompletions");
        String date=(String)block.invoke(null,process,"if (dayArray == null)");
        String scroll=(String)block.invoke(null,process,"if (lastVisible == 0 && diff <= AndroidUtilities.dp(5) && !hasDraftsReplaces || hasFromMe)");
        int a=process.indexOf("messages.add(placeToPaste, obj);"),b=process.indexOf("if (notPushedSponsoredMessages != null",a);
        Method execution=RestoredPendingOrderTest.class.getDeclaredMethod("execution",String.class,String.class,String.class,String.class,String.class);execution.setAccessible(true);
        String restore=(String)execution.invoke(null,placement,outgoing,date,process.substring(a,b),scroll);
        restore+="/** 执行真实本机新消息资格及阅读中回最新分支，原意图字段直接进入旧页回包。 */ static void localSendIntent(MessageObject obj){boolean hasFromMe=false,needMoveScrollToLastMessage=false,firstLoading=false,paused=false,hasDraftsReplaces=false;int newUnreadMessageCount=0,lastVisible=8,diff=0;final long dialog_id=42L;"+outgoing+scroll+"}";
        var field=RestoredPendingOrderTest.class.getDeclaredField("FIXTURE");field.setAccessible(true);String fixture=(String)field.get(null);
        a=load.indexOf("if (dayArray == null)");b=load.indexOf("if (obj.hasValidGroupId())",a);String loadedDate=load.substring(a,b);
        a=b;b=load.indexOf("            newRowsCount++;\n            dayArray.add(obj);",a);String loadedGroup=load.substring(a,b);
        a=load.indexOf("getMessagesController().getTranslateController().checkTranslation(obj, false);");
        a=load.indexOf("if (load_type == 1)",a);b=load.indexOf("if (obj.messageOwner != null && obj.messageOwner.video_processing_pending",a);String insert=load.substring(a,b);
        boolean revised=source.contains("private CodexPendingHistoryRows takeCodexPendingHistoryRows(");
        String helpers="", take="", finish="";
        if(revised){
            helpers=(String)block.invoke(null,source,"private static final class CodexPendingHistoryRows");
            for(String marker:new String[]{"private boolean isCodexRestoredHistoryPending(","private boolean isCodexHistoryDateHeader(",
                    "private CodexPendingHistoryRows takeCodexPendingHistoryRows(","private void restoreCodexPendingHistoryRows(","private void restoreCodexPendingHistoryAnchor("})
                helpers+=((String)block.invoke(null,source,marker)).replaceFirst("private ","private static ");
            int begin=load.indexOf("CodexPendingHistoryRows codexPendingHistoryRows = takeCodexPendingHistoryRows(");
            int end=load.indexOf("LongSparseArray<MessageObject.GroupedMessages> newGroups = null;",begin);
            take=load.substring(begin,end);
            if(begin>load.indexOf("if (obj.hasValidGroupId())"))throw new AssertionError("暂取晚于原group边界");
            String returnRows="restoreCodexPendingHistoryRows(codexPendingHistoryRows);";
            if(!load.contains(returnRows)||load.indexOf(returnRows)>load.indexOf("checkGroupMessagesOrder();"))throw new AssertionError("未在原组处理前归位");
            finish=returnRows+(String)block.invoke(null,load,"if (chatAdapter != null && universalNotify)")
                    +"if (codexPendingHistoryRows != null && !postponedScroll) restoreCodexPendingHistoryAnchor(codexPendingHistoryRows);";
            if(!load.contains("if (codexPendingHistoryRows != null && !postponedScroll) restoreCodexPendingHistoryAnchor(codexPendingHistoryRows);"))throw new AssertionError("缺原可见对象锚接线");
        }
        fixture=fixture.replace("boolean isDateObject;","boolean isDateObject,isVideoConversionObject;long localGroupId,localSentGroupId;")
                .replace("String message;", "String message;long grouped_id;")
                .replace("long getGroupId(){return 0;}","long getGroupId(){return localGroupId!=0?localGroupId:getGroupIdForUse();} long getGroupIdForUse(){return localSentGroupId!=0?localSentGroupId:messageOwner.grouped_id;} boolean hasValidGroupId(){return getGroupId()!=0;}")
                .replace("static class GroupedMessages {", "static class GroupedMessages {long groupId;boolean reversed;")
                .replace("HashMap<Long,MessageObject.GroupedMessages> groupedMessagesMap=new HashMap<>();", "LongSparseArray<MessageObject.GroupedMessages> groupedMessagesMap=new LongSparseArray<>();")
                .replace("int messagesStartRow;","int messagesStartRow,fullRefresh;boolean isFiltered; /** 合成整刷新计数。 */void notifyDataSetChanged(boolean b){fullRefresh++;} /** 合成行数。 */int getItemCount(){return messages.size()+messagesStartRow;}");
        String append="static int lastStableId; /** 执行真实旧向消息与日期插入，再执行真实混合页恢复/通知接线。 */ static void appendOlder(ArrayList<MessageObject> rows){int load_type=testLoadType,loadIndex=testLoadIndex,newRowsCount=0;boolean universalNotify=false,postponedScroll=testPostponed;LongSparseArray<MessageObject.GroupedMessages> newGroups=null,changedGroups=null;boolean reversed=false,addDateObjects=true,moveCurrentDateObject=false;"+take+"for(MessageObject obj:rows){ArrayList<MessageObject> dayArray=messagesByDays.get(obj.dateKey);"+loadedDate+loadedGroup+"dayArray.add(obj);"+insert+"}"+finish+"}";
        Path temp=Files.createTempDirectory("codex-history-pending-page-");
        try{
            Path probe=temp.resolve("RestoredPendingOrderProbe.java");Files.writeString(probe,fixture+PLATFORM+helpers+restore+append+SCENARIOS);
            Path runtime=temp.resolve("CodexRuntime.java");Files.writeString(runtime,"package com.butang.codextop; public class CodexRuntime {public static boolean owned; public static boolean ownsConversation(long id){return owned&&id==42L;}}");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-d",temp.toString(),probe.toString(),runtime.toString())!=0)throw new AssertionError("实际组合分支无法编译");
            try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},null)){
                try{loader.loadClass("com.butang.codextop.RestoredPendingOrderProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("历史页与旧待发组合排列失败",e.getCause());}
            }
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    private static final String PLATFORM="""
        static int testLoadType,testLoadIndex,clearOnLoadAndScrollMessageId=-1,clearOnLoadAndScrollOffset;static boolean testPostponed,first,scrollToTopOnResume,forceScrollToTop;static MessageObject scrollToMessage;
        static class LongSparseArray<T> extends HashMap<Long,T>{int indexOfKey(long key){return containsKey(key)?0:-1;}}
        static class MessagesController {static final int LOAD_FORWARD=1;}
        static class Utilities {static final Random random=new Random(19);}
        static class View {MessageObject message;int offset;View(MessageObject m,int y){message=m;offset=y;}}
        static class ChatMessageCell extends View {ChatMessageCell(MessageObject m,int y){super(m,y);} MessageObject getMessageObject(){return message;}}
        static class ChatActionCell extends View {ChatActionCell(MessageObject m,int y){super(m,y);} MessageObject getMessageObject(){return message;}}
        static class ListView {ArrayList<View> children=new ArrayList<>();int getChildCount(){return children.size();}View getChildAt(int i){return children.get(i);}}
        static class Layout {int position=-1,offset,calls;void scrollToPositionWithOffset(int p,int y){position=p;offset=y;calls++;}}
        static ListView chatListView;static Layout chatLayoutManager;
        /** 场景声明普通Codex归属与过滤边界。 */static boolean isCodexHistoryView(){return CodexRuntime.owned&&chatMode==MODE_DEFAULT;}
        /** 返回当前同一可见View的原像素偏移。 */static int getScrollingOffsetForView(View view){return view.offset;}
        """;
    private static final String SCENARIOS="""
        static int checks,cases;
        static HashMap<Integer,MessageObject> dictionary;
        /** 每个断言验证可观察的排列、身份或通知，不读取真实会话。 */static void require(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
        /** 日期及编号均为测试现场声明。 */static MessageObject row(int id,int day,int hour,int minute,boolean pending){
            TLRPC.TL_message m=new TLRPC.TL_message();m.id=id;m.date=(int)LocalDate.of(2026,10,day).atTime(hour,minute).toEpochSecond(ZoneOffset.UTC);
            if(pending){m.params.put("codexRestoredPending","true");m.params.put("localId","synthetic-local");m.params.put("state","accepted");}
            MessageObject result=new MessageObject(0,m,false,false);result.stableId=1000+Math.abs(id);return result;
        }
        /** 日期标题保持原零编号和当天零点形状。 */static MessageObject separator(int day){MessageObject m=row(0,day,0,0,false);m.type=10;m.isDateObject=true;return m;}
        /** 初始化真实入口之前的平台状态，日期数组不包含服务头，与正式代码一致。 */static void setup(boolean codex,MessageObject[] history,MessageObject... restored){
            actual=new Result();chatAdapter=new Adapter();chatAdapter.messagesStartRow=2;chatListView=new ListView();chatLayoutManager=new Layout();
            groupedMessagesMap=new LongSparseArray<>();first=false;scrollToTopOnResume=false;forceScrollToTop=false;scrollToMessage=null;testLoadType=0;testLoadIndex=0;testPostponed=false;clearOnLoadAndScrollMessageId=-1;clearOnLoadAndScrollOffset=0;
            execute(new Case("synthetic",codex,false,true,history,restored,new int[0],0));
            for(ArrayList<MessageObject> day:messagesByDays.values())day.removeIf(m->m.isDateObject);
            for(MessageObject m:messages)if(!m.isDateObject)messagesByDaysSorted.put(m.dateKeyInt,messagesByDays.get(m.dateKey));
            dictionary=new HashMap<>();for(MessageObject m:messages)if(!m.isDateObject)dictionary.put(m.getId(),m);
        }
        /** 记录原字典索引，新生产方法不得改变既有发送对象。 */static void load(MessageObject... rows){for(MessageObject m:rows)dictionary.put(m.getId(),m);appendOlder(new ArrayList<>(Arrays.asList(rows)));}
        /** 只统计气泡编号，日期与转换服务项另外校验。 */static int[] ids(){return messages.stream().filter(m->!m.isDateObject&&m.type>=0).mapToInt(MessageObject::getId).toArray();}
        /** 每日仅一个原日期头，日数组与排序索引指向同一列表且跟随真实行顺序。 */static void shape(String label){
            HashSet<MessageObject> unique=new HashSet<>();HashSet<String> headers=new HashSet<>();HashMap<String,ArrayList<MessageObject>> body=new HashMap<>();
            for(int i=0;i<messages.size();i++){
                MessageObject m=messages.get(i);require(unique.add(m),label+" duplicate object");
                if(m.isDateObject&&!m.isVideoConversionObject){require(headers.add(m.dateKey),label+" duplicate day");require(i>0&&messages.get(i-1).dateKey.equals(m.dateKey),label+" date misplaced");}
                else if(!m.isDateObject){body.computeIfAbsent(m.dateKey,k->new ArrayList<>()).add(m);require(dictionary.get(m.getId())==m,label+" dictionary identity");}
            }
            require(headers.equals(body.keySet()),label+" missing day");require(messagesByDays.keySet().equals(body.keySet()),label+" stale day map");
            for(String key:body.keySet()){
                ArrayList<MessageObject> day=messagesByDays.get(key);require(day.equals(body.get(key)),label+" day order");
                require(messagesByDaysSorted.get(day.get(0).dateKeyInt)==day,label+" sorted map identity");
            }
        }
        /** 归位后必须以同一对象及原像素恢复，允许消息索引为零。 */static void anchor(MessageObject object,int offset){require(chatLayoutManager.calls==1,"one anchor");require(chatLayoutManager.position==chatAdapter.messagesStartRow+messages.indexOf(object),"anchor identity/index");require(chatLayoutManager.offset==offset,"anchor pixel");}
        /** 报告仅合成标签和断言计数。 */static void passed(String label){cases++;System.out.println("PASS "+label);}
        /** 实际恢复入口加实际旧页追加复现设备同型排列，再验证边界。 */public static void main(String[] args){
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            MessageObject newest=row(103,6,22,43,false),pending=row(-11,5,16,46,true);
            setup(true,new MessageObject[]{newest,separator(6)},pending);MessageObject pendingDate=messages.get(messages.size()-1);
            chatListView.children.add(new ChatMessageCell(pending,-19));Map<String,String> params=pending.messageOwner.params;int stable=pending.stableId;
            load(row(102,6,22,38,false),row(101,6,20,5,false));
            System.out.println("SYNTHETIC older_page_after_restored_pending ids="+Arrays.toString(ids()));
            require(Arrays.equals(ids(),new int[]{103,102,101,-11}),"真实旧页追加越过旧待发");shape("cross_day");anchor(pending,-19);
            require(chatAdapter.fullRefresh==1,"mixed full refresh");require(messages.contains(pendingDate),"old date object");
            require(pending.stableId==stable&&pending.messageOwner.params==params&&"accepted".equals(params.get("state")),"pending state stable");passed("cross_day_old_accepted_pending");

            newest=row(103,6,14,1,false);pending=row(-11,6,7,21,true);setup(true,new MessageObject[]{newest,separator(6)},pending);
            ArrayList<MessageObject> originalDay=messagesByDays.get(newest.dateKey);chatListView.children.add(new ChatMessageCell(newest,9));
            load(row(102,6,14,0,false));require(Arrays.equals(ids(),new int[]{103,102,-11}),"same day interleaved old pending");shape("same_day");anchor(newest,9);
            require(messagesByDays.get(newest.dateKey)==originalDay,"nonempty day reference");passed("same_day_old_failure_between_user_reply");

            newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);setup(true,new MessageObject[]{newest,separator(6)},pending);pendingDate=messages.get(messages.size()-1);
            chatListView.children.add(new ChatActionCell(pendingDate,-7));load(row(102,5,18,0,false),row(101,4,12,0,false));
            require(Arrays.equals(ids(),new int[]{103,102,-11,101}),"empty day recreated");shape("recreated_day");anchor(pendingDate,-7);require(messages.contains(pendingDate),"same date anchor retained");passed("empty_day_recreated_original_header");

            newest=row(103,6,20,0,false);pending=row(-11,6,22,0,true);setup(true,new MessageObject[]{newest,separator(6)},pending);chatListView.children.add(new ChatMessageCell(pending,14));
            load(row(102,6,19,0,false));require(ids()[0]==-11,"newer pending remains zero");shape("index_zero");anchor(pending,14);passed("negative_anchor_at_index_zero");

            newest=row(103,6,22,43,false);MessageObject p1=row(-51,5,12,0,true),p2=row(-52,5,12,0,true),p3=row(-12,4,12,0,true);
            setup(true,new MessageObject[]{newest,separator(6)},p1,p2,p3);load(row(102,6,20,0,false),row(101,5,13,0,false),row(100,3,12,0,false));
            require(Arrays.equals(ids(),new int[]{103,102,101,-52,-51,-12,100}),"multiple same-second pending");shape("multiple");passed("several_pending_dates_same_second");

            newest=row(103,6,22,43,false);pending=row(-11,6,22,43,true);newest.messageOwner.grouped_id=7;pending.messageOwner.grouped_id=7;
            setup(true,new MessageObject[]{newest,pending,separator(6)});MessageObject.GroupedMessages group=new MessageObject.GroupedMessages();group.groupId=7;group.messages.add(pending);group.messages.add(newest);groupedMessagesMap.put(7L,group);
            MessageObject older=row(102,6,22,42,false);older.messageOwner.grouped_id=7;load(older);
            require(Arrays.equals(ids(),new int[]{103,-11,102}),"mixed existing group neighbors");require(groupedMessagesMap.get(7L)==group,"mixed group map identity");
            require(group.messages.equals(Arrays.asList(older,pending,newest)),"native group append order");require(older.localGroupId==0,"pending not native tail neighbor");shape("mixed_group");passed("actual_native_group_append_mixed_pending");

            newest=row(103,6,22,43,false);p1=row(-12,5,12,0,true);p2=row(-11,5,12,0,true);p1.messageOwner.grouped_id=9;p2.messageOwner.grouped_id=9;
            setup(true,new MessageObject[]{newest,separator(6),p1,p2,separator(5)});group=new MessageObject.GroupedMessages();group.groupId=9;group.messages.add(p2);group.messages.add(p1);groupedMessagesMap.put(9L,group);
            load(row(102,6,20,0,false));require(Arrays.equals(ids(),new int[]{103,102,-12,-11}),"pending-only group block");require(groupedMessagesMap.get(9L)==group&&group.messages.equals(Arrays.asList(p2,p1)),"pending-only group unchanged");shape("pending_group");passed("pending_only_group_identity_order");

            newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);newest.messageOwner.grouped_id=7;newest.localGroupId=70;pending.messageOwner.grouped_id=7;pending.localGroupId=71;
            setup(true,new MessageObject[]{newest,separator(6),pending,separator(5)});load(row(102,6,20,0,false));
            require(Arrays.equals(ids(),new int[]{103,102,-11}),"split local group must not use common origin");require(newest.localGroupId==70&&pending.localGroupId==71,"split group identity unchanged");shape("local_group");passed("same_origin_different_local_groups");

            newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);setup(true,new MessageObject[]{newest,separator(6)},pending);MessageObject conversion=separator(6);conversion.isVideoConversionObject=true;conversion.stableId=77;messages.add(1,conversion);
            load(row(102,6,20,0,false));require(messages.contains(conversion)&&conversion.stableId==77,"conversion row unchanged");shape("conversion");passed("conversion_service_retained");

            newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);setup(true,new MessageObject[]{newest,separator(6)},pending);chatListView.children.add(new ChatMessageCell(pending,-19));clearOnLoadAndScrollMessageId=102;clearOnLoadAndScrollOffset=31;
            load(row(102,6,20,0,false));require(chatLayoutManager.calls==1&&chatLayoutManager.position==3&&chatLayoutManager.offset==31,"explicit clear anchor wins");shape("clear_anchor");passed("explicit_positive_anchor_priority");

            newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);setup(true,new MessageObject[]{newest,separator(6)},pending);chatListView.children.add(new ChatMessageCell(pending,-19));testPostponed=true;
            load(row(102,6,20,0,false));require(chatLayoutManager.calls==0,"postponed anchor unchanged");shape("postponed");passed("postponed_scroll_priority");

            for(int intent=0;intent<4;intent++){
                newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);setup(true,new MessageObject[]{newest,separator(6)},pending);chatListView.children.add(new ChatMessageCell(pending,-19));
                if(intent==0){MessageObject fresh=row(-99,6,23,0,false);fresh.wasJustSent=true;int moves=actual.moves;localSendIntent(fresh);require(forceScrollToTop&&actual.moves==moves+1,"actual local send creates latest intent");}if(intent==1)scrollToTopOnResume=true;if(intent==2)first=true;if(intent==3)scrollToMessage=newest;
                load(row(102,6,20,0,false));require(chatLayoutManager.calls==0,"original scroll intent wins "+intent);shape("scroll_intent");passed("preserve_original_scroll_intent_"+intent);
            }

            for(int mode=0;mode<7;mode++){
                newest=row(103,6,22,43,false);pending=row(-11,5,16,46,true);setup(mode!=0,new MessageObject[]{newest,separator(6),pending,separator(5)});
                if(mode==1)chatAdapter.isFiltered=true;if(mode==2)testLoadType=1;if(mode==3)testLoadType=3;if(mode==4)testLoadIndex=1;if(mode==5)pending.messageOwner.params.remove("codexRestoredPending");if(mode==6){chatMode=MODE_SCHEDULED;pending.scheduled=true;}
                chatListView.children.add(new ChatMessageCell(pending,-19));load(row(102,6,20,0,false));require(chatAdapter.fullRefresh==0&&chatLayoutManager.calls==0,"native gate "+mode);passed("original_scope_"+mode);
            }
            newest=row(103,6,22,43,false);setup(true,new MessageObject[]{newest,separator(6)});load(row(102,6,20,0,false));require(chatAdapter.fullRefresh==0,"no pending original path");passed("no_restored_pending_original_path");
            System.out.println("HistoryPendingPageOrder: cases="+cases+" assertions="+checks+"; actual load/group/restore blocks with synthetic platform, not device acceptance");
        }
    }
    """;
}
