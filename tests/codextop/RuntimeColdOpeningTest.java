package com.butang.codextop;

import java.nio.file.Path;

/** 使用原真实Runtime夹具核验冷首屏首次可见正文、旧分页及失败资格；不计设备耗时。 */
public final class RuntimeColdOpeningTest {
    /** 可指定旧Runtime取得相同断言的RED，模型/Store/Outbox仍沿既有真实提取器。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        RuntimeLatestSegmentTest.runScenarios(runtime, SCENARIOS,
                "firstVisibleThenOlder();emptyProjectionAndPageBudget();zeroAndOtherModes();invalidAndLateReplies();"
                + "System.out.println(\"RuntimeColdOpening: groups=4 failures=0\");");
    }

    private static final String SCENARIOS = """
        /** 消费首个真实响应及仍存在的续做；最新正文已直接交付时不要求再排一个空回合。 */
        static void firstNetworkPage(){Utilities.globalQueue.next();historyQueue.next();Utilities.globalQueue.next();if(!Utilities.globalQueue.ready.isEmpty())Utilities.globalQueue.next();ui();}
        /** 只挑最后一个真实普通页；首次未发布时返回null供RED精确说明等待点。 */
        static Event loadedPage(){for(int i=events.size()-1;i>=0;i--){Event e=events.get(i);if(e.type==NotificationCenter.messagesDidLoad&&!metadata(e).replaceLatest)return e;}return null;}
        /** 新缓存从空Store恢复，避免热内存掩盖普通首次开页。 */
        static void coldReset()throws Exception{reset(new TranscriptWindow());histories.clear();openHistoryView(0,42,7);}
        /** 最新一条可见正文即交付；用户随后上翻仍能沿同一个真实旧游标补齐。 */
        static void firstVisibleThenOlder()throws Exception{
            coldReset();
            try{
                current.latest=page("Z",true,"older-1","latest-tail");
                current.older("older-1",page("Y",true,"older-2",null));
                current.older("older-2",page("X",false,null,null));
                loadMessages(0,42,3,0,7,2,1,0);firstNetworkPage();
                Event first=loadedPage();
                check(first!=null&&rows(first).equals("Z")&&historyQueue.ready.isEmpty()
                        &&current.requests.equals(Collections.singletonList("latest")),
                        "cold first visible latest page still waits for older pages requests="+current.requests+" olderQueued="+historyQueue.ready.size());
                check(!((Boolean)first.args[9])&&saves==1,"partial visible latest falsely ended history or skipped durable save");
                TranscriptWindow shown=histories.get(42L);int anchor=shown.findSource("Z",null).id;events.clear();
                loadMessages(0,42,3,anchor,7,0,2,0);pump();
                check(rows(normal()).equals("Y,X")&&((Boolean)normal().args[9])
                        &&current.requests.equals(Arrays.asList("latest","older:older-1","older:older-2")),
                        "first visible optimization lost explicit older pagination");
                check(store.read("synthetic-remote").findSource("X",null)!=null,"older request did not persist original history");
                System.out.println("PASS cold first visible latest delivered before older; later upward pagination retained");
            }finally{cleanup();}
        }
        /** 纯工具投影不能当可见正文；继续原四页预算找到首条正文即停，不为了条数多取。 */
        static void emptyProjectionAndPageBudget()throws Exception{
            coldReset();
            try{
                current.latest=page("",true,"empty-older","latest-tail");
                JsonObject tool=item("only-tool",null);tool.getAsJsonObject("raw").getAsJsonObject("content").addProperty("type","tool");
                current.latest.getAsJsonArray("items").add(tool);
                current.older("empty-older",page("Z",true,"not-needed",null));
                loadMessages(0,42,30,0,7,2,1,0);firstNetworkPage();
                check(loadedPage()==null&&historyQueue.ready.size()==1,"empty latest was treated as a visible page");
                pump();
                check(rows(normal()).equals("Z")&&!((Boolean)normal().args[9])
                        &&current.requests.equals(Arrays.asList("latest","older:empty-older")),"visible body after empty projection still filled old pages");
            }finally{cleanup();}
            coldReset();
            try{
                current.latest=page("",true,"empty-1","latest-tail");
                for(int i=1;i<=3;i++)current.older("empty-"+i,page("",true,"empty-"+(i+1),null));
                loadMessages(0,42,30,0,7,2,1,0);pump();
                check(current.requests.equals(Arrays.asList("latest","older:empty-1","older:empty-2","older:empty-3"))
                        &&rows(normal()).isEmpty()&&!((Boolean)normal().args[9]),"empty projection changed original four-page bound or falsely ended history");
                System.out.println("PASS empty projection reaches first body; all-empty response keeps four-page budget");
            }finally{cleanup();}
        }
        /** 零count不发请求；未隔离的mode2、向旧入口和非零锚点仍沿原补满规则，mode1空终态由定时专项覆盖。 */
        static void zeroAndOtherModes()throws Exception{
            coldReset();
            try{
                loadMessages(0,42,0,0,7,2,1,0);pump();
                check(current.requests.isEmpty()&&rows(normal()).isEmpty()&&saves==0,"zero count started network or save");
            }finally{cleanup();}
            for(String kind:new String[]{"other-mode","older-type","nonzero-anchor","around-type"}){
                coldReset();
                try{
                    current.latest=page("Z",true,"older-1","latest-tail");
                    current.older("older-1",page("Y",true,"older-2",null));
                    current.older("older-2",page("X",false,null,null));
                    int type=kind.equals("older-type")?0:kind.equals("around-type")?3:2;
                    int max=kind.equals("nonzero-anchor")?Integer.MAX_VALUE:kind.equals("around-type")?999999999:0;
                    int mode=kind.equals("other-mode")?2:0;
                    loadMessages(0,42,3,max,7,type,1,mode);firstNetworkPage();
                    check(loadedPage()==null&&historyQueue.ready.size()==1,"non-cold-default path changed: "+kind);
                    pump();check(rows(normal()).equals("Z,Y,X")&&current.requests.size()==3,"original fill no longer retained: "+kind);
                }finally{cleanup();}
            }
            System.out.println("PASS zero count and other mode/type/anchor behavior retained");
        }
        /** 不完整页保持拒收；已排队请求连接被替换后不得合入或用旧正文冒充首屏。 */
        static void invalidAndLateReplies()throws Exception{
            for(String reason:new String[]{"page_limit","source_discontinuity"}){
                coldReset();
                try{
                    current.latest=page("untrusted",true,"next","tail");current.latest.addProperty("truncated",true);current.latest.addProperty("truncationReason",reason);
                    loadMessages(0,42,3,0,7,2,1,0);pump();
                    check(ids(histories.get(42L)).isEmpty()&&rows(normal()).isEmpty()&&!((Boolean)normal().args[9])
                            &&saves==0&&current.requests.size()==1,"incomplete "+reason+" page became visible or cached");
                }finally{cleanup();}
            }
            coldReset();
            try{
                current.latest=page("stale",true,"next","tail");
                loadMessages(0,42,3,0,7,2,1,0);Utilities.globalQueue.next();historyQueue.next();
                current=new DesktopConnection();pump();
                check(ids(histories.get(42L)).isEmpty()&&rows(normal()).isEmpty()&&saves==0,"late replaced connection delivered or saved stale body");
                System.out.println("PASS incomplete latest rejects page_limit/discontinuity and late connection response");
            }finally{cleanup();}
        }
        """;
}
