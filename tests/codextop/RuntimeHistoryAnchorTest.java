package com.butang.codextop;

import java.nio.file.Path;

/** 保留原 14 项真实锚点行为，复用唯一 Runtime/Store 上下文，不接触账号或设备。 */
public final class RuntimeHistoryAnchorTest {
    /** 可指定隔离 Runtime 源码；只运行锚点组，不重复最新段 18 项。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        RuntimeLatestSegmentTest.runScenarios(runtime, SCENARIOS, "anchorScenarios();");
    }

    // 此处只有原合成输入与断言；正式加载、编号、队列、通知及保存来自共享夹具。
    private static final String SCENARIOS = """
            /** 保留原数字来源和逐秒日期，不在夹具预测本地编号。 */
            static JsonObject anchorPage(int first,int last,boolean more,String cursor){
                JsonObject p=page("",more,cursor,"synthetic-tail");
                for(int n=first;n<=last;n++){JsonObject member=item(String.valueOf(n),null);member.addProperty("createdAtMs",n*1000L);p.getAsJsonArray("items").add(member);}
                return p;
            }
            /** 窗口仍由正式 prepend 分配编号。 */
            static TranscriptWindow anchorWindow(int first,int last,boolean more)throws Exception{
                TranscriptWindow h=new TranscriptWindow();h.prepend(anchorPage(first,last,more,more?"older-0":null));return h;
            }
            /** 只按合成来源查询已有编号，避免绑定当前编号实现。 */
            static int anchorId(TranscriptWindow h,String source){
                TranscriptWindow.Entry row=h.findSource(source,null);check(row!=null,"missing fixture source");return row.id;
            }
            /** 新 Runtime 要求实际打开阅读 view；本地组仍禁止网络。 */
            static void anchorReset(TranscriptWindow h)throws Exception{reset(h);current=null;openHistoryView(0,42,3);}
            /** 保留原页顺序、数量、结束位、请求锚点和非末页声明断言。 */
            static void anchorExpect(String label,String sources,int anchor,int count,boolean end){
                int notices=0;Event delivery=null;for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad){notices++;delivery=e;}
                check(notices==1&&toasts==0,label+" wrong delivery count");
                @SuppressWarnings("unchecked") ArrayList<MessageObject> delivered=(ArrayList<MessageObject>)delivery.args[2];
                check(sources.equals(rows(delivery))&&delivered.size()<=count&&((Boolean)delivery.args[9])==end&&((Integer)delivery.args[12])==anchor,
                        label+" sources="+rows(delivery)+" end="+delivery.args[9]+" loadedAnchor="+delivery.args[12]);
                if(!end)check(((Integer)delivery.args[1])==delivered.size(),label+" falsely advertised short final page");
                check(isHistoryPageCurrent(metadata(delivery)),label+" stale display segment identity");System.out.println("PASS "+label);
            }
            /** 每个原断言保留独立真实 Store 的创建与回收。 */
            static void anchorLocal(String label,TranscriptWindow h,int count,int anchor,int type,String sources,boolean end)throws Exception{
                anchorReset(h);try{loadMessages(0,42,count,anchor,3,type,0,0);pump();anchorExpect(label,sources,anchor==0?anchorId(h,"9"):anchor,count,end);}finally{cleanup();}
            }
            /** 原居中、首末、缺锚回退、普通三种翻页和四页预算不变。 */
            static void anchorScenarios()throws Exception{
                TranscriptWindow h=anchorWindow(1,9,false);int anchor=anchorId(h,"5");
                anchorLocal("middle includes both sides",h,5,anchor,3,"7,6,5,4,3",false);
                anchorLocal("even page includes anchor",h,4,anchor,3,"7,6,5,4",false);
                anchorLocal("single anchor",h,1,anchor,3,"5",false);
                anchorLocal("oldest fills newer side",h,5,anchorId(h,"1"),3,"5,4,3,2,1",true);
                anchorLocal("newest fills older side",h,5,anchorId(h,"9"),3,"9,8,7,6,5",false);
                TranscriptWindow small=anchorWindow(1,3,false);
                anchorLocal("short complete history",small,5,anchorId(small,"2"),3,"3,2,1",true);
                JsonObject snapshot=small.snapshot();int missing=anchorId(small,"2");snapshot.getAsJsonArray("rows").remove(1);
                anchorLocal("missing inside page remains unconfirmed",TranscriptWindow.restore(snapshot),5,missing,3,"3,1",false);
                anchorLocal("ordinary older unchanged",h,3,anchor,0,"4,3,2",false);
                anchorLocal("ordinary newer unchanged",h,3,anchor,1,"8,7,6",false);
                anchorLocal("ordinary initial anchor unchanged",h,3,0,2,"9,8,7",false);
                anchorReset(new TranscriptWindow());try{loadMessages(0,42,5,123,3,3,0,0);pump();anchorExpect("empty unavailable stays open","",123,5,false);}finally{cleanup();}
                TranscriptWindow older=anchorWindow(6,9,true);int requested=anchorId(older,"6")-3;
                anchorReset(older);try{
                    current=new DesktopConnection();current.older("older-0",anchorPage(1,6,false,null));
                    loadMessages(0,42,5,requested,3,3,0,0);pump();anchorExpect("older missing anchor follows existing cursor","5,4,3,2,1",requested,5,true);
                    check(current.requests.equals(Collections.singletonList("older:older-0"))&&saves==1,"older anchor changed network or save count");
                    check(store.read("synthetic-remote").snapshot().equals(older.snapshot()),"actual older page was not persisted");
                }finally{cleanup();}
                TranscriptWindow limit=anchorWindow(6,9,true);
                anchorReset(limit);try{
                    current=new DesktopConnection();for(int n=1;n<=4;n++)current.older("older-"+(n-1),anchorPage(1,0,true,"older-"+n));
                    int outside=anchorId(limit,"6")-20;loadMessages(0,42,3,outside,3,3,0,0);pump();anchorExpect("four page bound returns local fallback","8,7,6",outside,3,false);
                    check(current.requests.equals(Arrays.asList("older:older-0","older:older-1","older:older-2","older:older-3")),"original four page budget changed");
                }finally{cleanup();}
                anchorReset(h);try{
                    loadMessages(0,42,5,anchor,3,3,0,0);Utilities.globalQueue.next();accountGeneration++;ui();
                    for(Event e:events)check(e.type!=NotificationCenter.messagesDidLoad,"late account delivery escaped");
                    System.out.println("PASS late account delivery rejected");
                }finally{cleanup();}
                System.out.println("RuntimeHistoryAnchor: scenarios=14 failures=0; actual Runtime context / Java8");
            }
        """;
}
