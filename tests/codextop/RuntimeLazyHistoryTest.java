package com.butang.codextop;

import java.nio.file.Path;

/** 原Runtime调用惰性Window，验证旧正文不被元数据判断加载及IO失败不伪装终态。 */
public final class RuntimeLazyHistoryTest {
    /** 复用真实owner提取器，仅新增合成正文来源与明确队列碰撞，不复制业务实现。 */
    public static void main(String[] args) throws Exception {
        Path runtime = Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java");
        RuntimeLatestSegmentTest.runScenarios(runtime, CASES,
                "lazyRecent();lazyBookmark();lazyLocalSelection();lazyBoundaryOnly();lazyStale(0);lazyStale(1);lazyStale(2);lazyLateUi();lazyAcceptedFailure();"
                + "System.out.println(\"RuntimeLazyHistory: scenarios=9 failures=0\");");
    }

    private static final String CASES = """
        /** 完整索引保留，实际Content按原item投影；故障只模拟文件读取边界。 */
        static final class LazyRows {
            TranscriptWindow root;int reads;Set<String> broken=new HashSet<>();
        }
        static LazyRows lazyRows(TranscriptWindow source)throws Exception {
            final LazyRows result=new LazyRows();ArrayList<TranscriptWindow.IndexedSegment> all=new ArrayList<>();
            for(TranscriptWindow.IndexedSegment segment:source.exportIndexedSegments()){
                ArrayList<TranscriptWindow.RowRef> rows=new ArrayList<>();
                for(TranscriptWindow.RowRef row:segment.rows){
                    final String sourceId=row.sourceId,encoded=TranscriptWindow.bodySnapshot(row.body.resident()!=null?row.body.resident():row.body.content().read()).toString();
                    TranscriptWindow.Content content=()->{result.reads++;if(result.broken.contains(sourceId))throw new IOException("synthetic body IO");JsonArray a=new JsonArray();a.add(JsonParser.parseString(encoded));ArrayList<TranscriptText> values=TranscriptText.read(a);if(values.size()!=1)throw new IOException("synthetic projection");return values.get(0);};
                    rows.add(new TranscriptWindow.RowRef(row.id,row.sourceId,row.localId,row.outgoing,row.createdAtMs,new TranscriptWindow.Body(content)));
                }
                all.add(new TranscriptWindow.IndexedSegment(segment.epoch,segment.oldest,segment.cursor,segment.tailCursor,segment.hasMore,segment.loaded,segment.complete,rows,segment.sourceNumbers));
            }
            result.root=TranscriptWindow.restoreIndexed(all);return result;
        }
        /** 从真实prepend产生编号和原状态，再以索引恢复；不手填窗口内部编号。 */
        static TranscriptWindow many()throws Exception {StringJoiner ids=new StringJoiner(",");for(int i=0;i<2000;i++)ids.add("R"+i);return cache(ids.toString());}
        /** 最近30的原count+1边界仅读31行，远处坏块既不触读也不令首屏失败。 */
        static void lazyRecent()throws Exception {
            TranscriptWindow original=many();reset(original);try{
                LazyRows lazy=lazyRows(original);lazy.broken.add("R0");histories.put(42L,lazy.root);
                long token=openHistoryView(0,42,7);loadMessages(0,42,30,0,7,2,1,0);pump();
                Event e=normal();check(!metadata(e).loadFailed&&((ArrayList<?>)e.args[2]).size()==30,"recent first page was failure or changed original 30 visible delivery");
                check(rows(e).startsWith("R1999,R1998")&&lazy.reads==31,"recent decoded non-window bodies reads="+lazy.reads);
                check(current.requests.isEmpty()&&lazy.root.size()==2000,"local current page fetched network or truncated root");
                events.clear();requestLatestHistory(0,42,7,token);pump();
                check(rows(replacement()).startsWith("R1999,R1998")&&!metadata(replacement()).loadFailed,"return latest failed on old unread body");
                check(lazy.root.containsNumber(lazy.root.sourceNumber("R0",null))&&lazy.root.hasBefore(lazy.root.latestNumber()),"metadata helpers lost old rows");
                System.out.println("PASS recent page and explicit latest preserve all identities without old body reads");
            }finally{cleanup();}
        }
        /** 书签只定位不预读坏正文；真正around由原loadFailed通知结束原loadIndex。 */
        static void lazyBookmark()throws Exception {
            TranscriptWindow original=many();reset(original);try{
                LazyRows lazy=lazyRows(original);lazy.broken.add("R0");histories.put(42L,lazy.root);
                long token=openHistoryView(0,42,7);loadMessages(0,42,30,0,7,2,1,0);pump();events.clear();int before=lazy.reads;
                final HistoryBookmark[] result={null};resolveHistoryBookmark(0,42,7,token,"R0",null,b->result[0]=b);pump();
                check(result[0]!=null&&result[0].messageId==lazy.root.sourceNumber("R0",null)&&lazy.reads==before,"bookmark read body or lost precise identity");
                loadMessages(0,42,1,result[0].messageId,7,3,8,0);pump();Event e=normal();
                check(metadata(e).loadFailed&&e.args[11].equals(8)&&!((Boolean)e.args[9]),"bad body became successful empty/end or wrong loadIndex");
                check(historyViews.get(7).window==lazy.root&&histories.get(42L)==lazy.root&&lazy.root.size()==2000&&current.requests.isEmpty(),"bad body destroyed root or requested remote fallback");
                System.out.println("PASS metadata bookmark then actual failed around terminal");
            }finally{cleanup();}
        }
        /** 本地目录与精确选段只查索引并pin；坏归档正文不会阻碍目录或取消。 */
        static void lazyLocalSelection()throws Exception {
            TranscriptWindow old=cache("A,B");TranscriptWindow original=old.acceptLatest(page("X,Y",true,"new-older","new-tail"));reset(original);try{
                LazyRows lazy=lazyRows(original);lazy.broken.add("A");lazy.broken.add("B");histories.put(42L,lazy.root);
                long token=openHistoryView(0,42,7);loadMessages(0,42,2,0,7,2,1,0);pump();int before=lazy.reads;
                final LocalHistoryCatalog[] catalog={null};requestLocalHistorySegments(0,42,7,token,lazy.root.epoch(),v->catalog[0]=v);pump();
                check(catalog[0]!=null&&catalog[0].segments.size()==1&&lazy.reads==before,"local catalog loaded archive bodies");
                final LocalHistorySelection[] ready={null};LocalHistorySelection selected=resolveLocalHistorySegment(catalog[0],catalog[0].segments.get(0),v->ready[0]=v);pump();
                HistoryView view=historyViews.get(7);check(selected!=null&&ready[0]==selected&&view.bookmarkWindow!=null&&view.window==lazy.root&&lazy.reads==before,"local selection read bad anchor or changed page before accept");
                cancelLocalHistorySelection(selected);pump();
                check(view.bookmarkWindow==null&&view.window==lazy.root&&lazy.reads==before&&current.requests.isEmpty(),"selection cancellation lost page, read archive, or fetched remote");
                System.out.println("PASS local directory and exact selection pin do not read bad archive bodies");
            }finally{cleanup();}
        }
        /** 任意旧向/锚点存在性不读正文；maxId=0与负号保留原before边界。 */
        static void lazyBoundaryOnly()throws Exception {
            TranscriptWindow source=cache("A,B,C");LazyRows lazy=lazyRows(source);lazy.broken.addAll(Arrays.asList("A","B","C"));
            int a=lazy.root.sourceNumber("A",null),b=lazy.root.sourceNumber("B",null),c=lazy.root.sourceNumber("C",null);
            check(lazy.root.containsNumber(a)&&!lazy.root.containsNumber(a-1),"exact number membership guessed");
            check(lazy.root.hasBefore(0)&&!lazy.root.hasBefore(-1)&&!lazy.root.hasBefore(a)&&lazy.root.hasBefore(b)&&lazy.root.hasBefore(c+1),"hasBefore boundary differs from original");
            check(!new TranscriptWindow().hasBefore(0)&&lazy.reads==0,"pure boundary query read body");
            System.out.println("PASS metadata boundary does not load a broken body");
        }
        /** 正式账号/归属守卫先于读盘，旧任务不消费新账号的正文或发终态。 */
        static void lazyStale(int kind)throws Exception {
            TranscriptWindow original=cache("A,B");reset(original);try{
                LazyRows lazy=lazyRows(original);histories.put(42L,lazy.root);openHistoryView(0,42,7);loadMessages(0,42,2,0,7,2,1,0);
                if(kind==0)session=null;else if(kind==1)accountGeneration++;else remoteIds.put(42L,"changed");
                pump();check(lazy.reads==0&&events.isEmpty()&&current.requests.isEmpty(),"stale account/owner touched bodies: "+kind);
                System.out.println("PASS stale owner before IO "+kind);
            }finally{cleanup();}
        }
        /** 真正完成正文读取之后账号变化，已排UI仍沿原guard拒绝。 */
        static void lazyLateUi()throws Exception {
            TranscriptWindow original=cache("A,B");reset(original);try{
                LazyRows lazy=lazyRows(original);histories.put(42L,lazy.root);openHistoryView(0,42,7);loadMessages(0,42,2,0,7,2,1,0);
                Utilities.globalQueue.next();check(lazy.reads==2&&!AndroidUtilities.ui.ready.isEmpty(),"did not reach real UI boundary");
                accountGeneration++;ui();check(events.isEmpty(),"late lazy page published to other account");
                System.out.println("PASS late lazy result rejected at original UI boundary");
            }finally{cleanup();}
        }
        /** 替换已经接纳后遇坏新增尾部，保留可见页且不把空extra视为已确认回显。 */
        static void lazyAcceptedFailure()throws Exception {
            TranscriptWindow original=cache("A,B");reset(original);try{
                LazyRows lazy=lazyRows(original);histories.put(42L,lazy.root);long token=openHistoryView(0,42,7);loadMessages(0,42,1,0,7,2,1,0);pump();
                events.clear();requestLatestHistory(0,42,7,token);pump();HistoryPage accepted=metadata(replacement());check(acceptLatestHistory(accepted),"latest candidate not accepted");
                pump(); // accept自身即时prune先执行，与延后正文读取分开。
                lazy.broken.add("B");lazy.broken.add("A");
                // 使用独立restore清掉弱Entry缓存，再沿当前owner交付故障路径，避免强引用掩盖IO。
                TranscriptWindow currentWindow=TranscriptWindow.restoreIndexed(lazy.root.exportIndexedSegments());histories.put(42L,currentWindow);HistoryView v=historyViews.get(7);v.window=currentWindow;v.latestRoot=currentWindow;
                HistoryPage p=new HistoryPage(v,currentWindow,currentWindow,false);p.acceptedRevision=v.intentRevision;
                int failed=failures;events.clear();finishAcceptedHistory(p,new ArrayList<>(),0);pump();
                check(failures==failed+1&&events.isEmpty()&&v.window==currentWindow&&currentWindow.size()==2,"accepted IO failure escaped or delivered empty success");
                System.out.println("PASS accepted tail body failure retains page and reports failure");
            }finally{cleanup();}
        }
    """;
}
