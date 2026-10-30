package com.butang.codextop;

import java.nio.file.Path;

/** 通过真实页面握手、归档比较与文件缓存验证释放阅读引用后的有限收尾。 */
public final class RuntimeArchivePruneTest {
    /** 可指定完整前像执行同一业务反例；不替换归档或持久化算法。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        String invocation = args.length > 1 ? "archiveAccept();" : "archiveScenarios();";
        RuntimeLatestSegmentTest.runScenarios(runtime, SCENARIOS, invocation);
    }

    private static final String SCENARIOS = """
        /** 只读真实v2快照中归档数量，不把身份重合当作删除条件。 */
        static int archiveCount(TranscriptWindow window){return window.snapshot().getAsJsonArray("archive").size();}
        /** 从真实Store回读，避免只验证内存的副本已移除。 */
        static int persistedArchiveCount()throws Exception{return archiveCount(store.read("synthetic-remote"));}
        /** 真实空latest与旧向桥接复现现场重复段，UI尚未接受候选，因此旧window被pin。 */
        static TranscriptWindow archiveDuplicate()throws Exception{
            TranscriptWindow old=cache("A,B");reset(old);openCached();
            current.latest=page("",true,"bridge-older","fresh-tail");
            current.older("bridge-older",page("B",true,"unused-older",null));
            watchConversation(0,42);pump();
            TranscriptWindow root=histories.get(42L);
            check(root!=old&&ids(root).equals("B,A")&&archiveCount(root)==1&&persistedArchiveCount()==1,
                    "实际watch未产生已桥接但旧view仍引用的副本");
            return root;
        }
        /** 真实accept必须主动收尾，不等下一条消息或尾游标变化。 */
        static void archiveAccept()throws Exception{
            TranscriptWindow root=archiveDuplicate();
            try{
                int before=saves,requests=current.requests.size();HistoryPage candidate=metadata(replacement());
                check(acceptLatestHistory(candidate),"最新候选未被实际接受");pump();
                check(archiveCount(root)==0&&persistedArchiveCount()==0,"释放最后pin后已完整包含的归档仍在磁盘");
                check(saves==before+1&&current.requests.size()==requests,"收尾重复写盘或增加网络");
                check(ids(root).equals("B,A")&&"old-older".equals(root.cursor)&&"fresh-tail".equals(root.tailCursor),"收尾改正文或前后游标");
                check(!acceptLatestHistory(candidate),"迟到候选仍可接受");pump();check(saves==before+1,"重复accept再次写盘");
                System.out.println("PASS accept prunes once without new messages/network");
            }finally{cleanup();}
        }
        /** 真正关闭最后读者同样收尾；重复关闭不产生第二次保存。 */
        static void archiveClose()throws Exception{
            TranscriptWindow root=archiveDuplicate();
            try{long token=historyViews.get(7).token;int before=saves,requests=current.requests.size();
                closeHistoryView(7,token);pump();check(archiveCount(root)==0&&persistedArchiveCount()==0&&saves==before+1,"关闭未收尾");
                closeHistoryView(7,token);pump();check(saves==before+1&&current.requests.size()==requests,"重复close发请求或写盘");
                System.out.println("PASS close prunes once; late close is inert");
            }finally{cleanup();}
        }
        /** 暂停还要恢复原阅读段；另一个窗口关闭不得删除仍被暂停页引用的副本。 */
        static void archivePausedAndNewPin()throws Exception{
            TranscriptWindow root=archiveDuplicate();
            try{long old=historyViews.get(7).token;pauseHistoryView(7,old);
                long other=openHistoryView(0,42,8);loadMessages(0,42,3,0,8,2,2,0);pump();int before=saves;
                closeHistoryView(8,other);
                long resumed=openHistoryView(0,42,7);check(resumed!=old,"恢复仍用旧token");
                closeHistoryView(7,old);pump();
                check(archiveCount(root)==1&&persistedArchiveCount()==1&&saves==before&&historyViews.get(7).token==resumed,
                        "排队清理或迟到close越过后来恢复的pin");
                closeHistoryView(7,resumed);pump();check(archiveCount(root)==0&&persistedArchiveCount()==0&&saves==before+1,"最终关闭未释放副本");
                System.out.println("PASS paused reader/new token pin retained until actual close");
            }finally{cleanup();}
        }
        /** 无副本或未证明桥接的归档都不增加磁盘写入。 */
        static void archiveNoChange(boolean gap)throws Exception{
            TranscriptWindow old=cache("A,B");reset(old);
            try{long token=openCached();if(gap){check(acceptLatestHistory(offer()),"缺口候选不能接受");pump();}
                int before=saves,requests=current.requests.size();JsonObject snapshot=histories.get(42L).snapshot();
                closeHistoryView(7,token);pump();check(saves==before&&snapshot.equals(histories.get(42L).snapshot())
                    &&current.requests.size()==requests,"无变化仍写盘/补网络或删除缺口");
                System.out.println("PASS no-change no-write gap="+gap);
            }finally{cleanup();}
        }
        /** 清理排队后账号、来源或根窗口变化，旧释放事件不得写当前缓存。 */
        static void archiveStale(String kind)throws Exception{
            TranscriptWindow root=archiveDuplicate();
            try{int before=saves;closeHistoryView(7,historyViews.get(7).token);
                if(kind.equals("account"))accountGeneration++;
                else if(kind.equals("session"))session=new PasswordLogin.Session();
                else if(kind.equals("selected"))org.telegram.messenger.UserConfig.selectedAccount=1;
                else if(kind.equals("remote"))remoteIds.put(42L,"replacement-remote");
                else if(kind.equals("machine"))dialogMachines.put(42L,"replacement-machine");
                else if(kind.equals("root"))histories.put(42L,cache("later"));
                else throw new AssertionError(kind);
                pump();check(saves==before&&archiveCount(root)==1&&persistedArchiveCount()==1,"旧清理串写: "+kind);
                System.out.println("PASS queued release identity "+kind);
            }finally{org.telegram.messenger.UserConfig.selectedAccount=0;cleanup();}
        }
        /** 原原子写失败保留磁盘全部旧数据，不清回显待发，也不新建循环重试。 */
        static void archiveWriteFailure()throws Exception{
            TranscriptWindow root=archiveDuplicate();
            try{TranscriptStore good=store;JsonObject saved=good.read("synthetic-remote").snapshot();
                outbox.put(new OutboxStore.Item("unsent","synthetic-remote","synthetic",-7,1));
                store=new TranscriptStore(directory.resolve("history").toFile(),"synthetic-server","synthetic-account","synthetic-machine",1);
                int before=saves,requests=current.requests.size(),delays=Utilities.globalQueue.delayed.size();
                closeHistoryView(7,historyViews.get(7).token);pump();
                check(saves==before+1&&saved.equals(good.read("synthetic-remote").snapshot())&&ids(root).equals("B,A")
                    &&outbox.get("unsent")!=null&&current.requests.size()==requests&&Utilities.globalQueue.delayed.size()==delays,
                    "写失败损坏原文件、正文、待发或自行循环");
                store=good;check(saveHistory(42,"synthetic-remote",root)&&persistedArchiveCount()==0,"下一次原保存不能恢复");
                System.out.println("PASS failed atomic write keeps disk/outbox; next original save recovers");
            }finally{cleanup();}
        }
        /** 仅减少冗余文件不能顺带改变待发确认；原有accept完成器继续拥有回显迁移。 */
        static void archiveOutboxUnchanged()throws Exception{
            TranscriptWindow old=new TranscriptWindow();JsonObject initial=page("A",true,"old-older","old-tail");
            initial.getAsJsonArray("items").add(item("B","already-echoed"));old.prepend(initial);reset(old);
            try{long token=openCached();current.latest=page("",true,"bridge-older","fresh-tail");
                current.older("bridge-older",page("B",true,"unused-older",null));watchConversation(0,42);pump();
                check(archiveCount(histories.get(42L))==1,"没有待清理副本");
                outbox.put(new OutboxStore.Item("already-echoed","synthetic-remote","synthetic",-17,1));
                closeHistoryView(7,token);pump();
                check(persistedArchiveCount()==0&&outbox.get("already-echoed")!=null,"仅清理副本却额外确认了待发记录");
                System.out.println("PASS prune-only leaves outbox ownership unchanged");
            }finally{cleanup();}
        }
        /** 正文、时间、附件或顺序不完全一致时，关闭读者也不得按ID重合强删。 */
        static void archiveDifferent(String kind)throws Exception{
            TranscriptWindow old=cache("A,B");reset(old);
            try{long token=openCached();TranscriptWindow root=old.acceptLatest(page("X",true,"next","tail"));
                JsonObject copy=page("A,B",false,null,null);
                JsonObject first=copy.getAsJsonArray("items").get(0).getAsJsonObject();
                if(kind.equals("text"))first.getAsJsonObject("raw").getAsJsonObject("content").addProperty("text","different");
                else if(kind.equals("time"))first.addProperty("createdAtMs",2000);
                else if(kind.equals("order"))copy=page("B,A",false,null,null);
                else if(kind.equals("attachment")){
                    JsonArray parts=new JsonArray();parts.add(first.getAsJsonObject("raw").get("content"));
                    JsonObject file=new JsonObject();file.addProperty("type","image");file.addProperty("url","https://synthetic.invalid/image.png");parts.add(file);
                    first.getAsJsonObject("raw").add("content",parts);
                }else throw new AssertionError(kind);
                root.prepend(copy);histories.put(42L,root);store.write("synthetic-remote",root);
                int before=saves;closeHistoryView(7,token);pump();
                check(archiveCount(root)==1&&persistedArchiveCount()==1&&saves==before,"不完整相同仍删归档: "+kind);
                System.out.println("PASS preserve differing "+kind);
            }finally{cleanup();}
        }
        /** 每例独立临时目录；除原业务队列外没有计时器、网络或后台线程。 */
        static void archiveScenarios()throws Exception{
            archiveAccept();archiveClose();archivePausedAndNewPin();archiveNoChange(false);archiveNoChange(true);
            for(String kind:new String[]{"account","session","selected","remote","machine","root"})archiveStale(kind);
            archiveWriteFailure();archiveOutboxUnchanged();for(String kind:new String[]{"text","time","order","attachment"})archiveDifferent(kind);
            bookmarkPinnedAcrossPrune();System.out.println("RuntimeArchivePrune: scenarios=18 failures=0");
        }
        """;
}
