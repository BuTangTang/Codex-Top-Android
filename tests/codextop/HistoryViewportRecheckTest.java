package com.butang.codextop;

import java.nio.file.Path;

/** 执行真实首屏owner、LayoutManager完成体和两条动画收尾体；只合成Android事件与几何。 */
public final class HistoryViewportRecheckTest {
    /** 同一无暂态标志反例分别执行旧/新Chat；不读取真实消息或账号。 */
    public static void main(String[] args) throws Exception {
        String source=args.length==0?Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").toString():args[0];
        String setup="""
            Ui t=initial("A,B,C,D");t.chatListView.scrollUp=true;draw(t);
            check(t.codexHistoryInitialFillUsedToken!=t.codexHistoryViewToken,"old stable scrollable cache consumed allowance");
            String old=t.codexHistoryEpoch;current.latest=page("Z",true,"latest-older","latest-tail");watchConversation(0,42);pump();Event e=replacement();t.consume(e);events.clear();ui();pump();
            check(!t.codexHistoryEpoch.equals(old)&&t.messages.size()==1,"real replacement fixture");int calls=t.controller.calls.size();
            """;
        String scenarios="""
            try{
            """+setup+"""
                // accept后的即时归档收尾已由真实队列完成，再隔离观察布局派发的网络工作。
                // 保留原RED的无动画/无pending前提；下一次真实layout完成，而不是凭空多一次preDraw。
                draw(t);check(t.codexHistoryInitialFillTicket==null&&t.codexHistoryInitialFillUsedToken!=t.codexHistoryViewToken,"old geometry consumed");
                t.chatListView.scrollUp=false;t.originalLayoutComplete();draw(t);
                check(t.controller.calls.size()==calls+1&&Utilities.globalQueue.ready.size()==1,"stable short next real layout has no initial-fill recheck");
                for(int i=0;i<10;i++){t.originalLayoutComplete();t.originalAnimationComplete();t.originalForcedAnimationEnd();ui();draw(t);}
                check(t.controller.calls.size()==calls+1&&Utilities.globalQueue.ready.size()==1,"repeated real completion consumed another allowance");
                check(t.layoutCompletions==11,"original super layout contract lost");
                System.out.println("PASS unchanged unmarked scrollable -> real stable layout admits one; ten later completions bounded");
            }finally{cleanup();}
            try{
            """+setup+"""
                draw(t);t.chatListView.scrollUp=false;int invalidations=t.chatListView.invalidations;
                for(int i=0;i<20;i++){draw(t);pump();}
                check(t.controller.calls.size()==calls&&t.chatListView.draw.isEmpty()&&Utilities.globalQueue.ready.isEmpty(),"preDraw/pump self-created work");
                check(t.chatListView.invalidations==invalidations,"preDraw/invalidate self-driven loop");
                for(int i=0;i<10;i++)t.originalLayoutComplete();
                check(t.chatListView.draw.size()==1,"same pending ticket admitted multiple preDraw callbacks");draw(t);
                check(t.controller.calls.size()==calls+1,"real event after idle did not reuse allowance");
                System.out.println("PASS twenty no-layout draws/pumps remain idle; ten actual layouts share one ticket");
            }finally{cleanup();}
            for(boolean forced:new boolean[]{false,true})try{
            """+setup+"""
                t.chatListView.scrollUp=false;t.chatListView.animator=new RecyclerView.ItemAnimator();t.chatListView.animator.running=true;draw(t);
                check(t.controller.calls.size()==calls&&t.codexHistoryInitialFillUsedToken!=t.codexHistoryViewToken,"running animation admitted/consumed");
                t.chatListView.animator.running=false;if(forced)t.originalForcedAnimationEnd();else t.originalAnimationComplete();ui();draw(t);
                check(t.controller.calls.size()==calls+1,"actual animation finish did not recheck");
                System.out.println("PASS actual "+(forced?"forced-end":"normal-complete")+" callback -> stable short one request");
            }finally{cleanup();}
            for(String kind:new String[]{"pending","computing","requested","height","children","still-running","scrollable"})try{
            """+setup+"""
                t.chatListView.scrollUp=false;
                if(kind.equals("pending"))t.chatListView.pending=true;else if(kind.equals("computing"))t.chatListView.computing=true;
                else if(kind.equals("requested"))t.chatListView.layoutRequested=true;else if(kind.equals("height"))t.chatListView.height=0;
                else if(kind.equals("children"))t.chatListView.visible=0;else if(kind.equals("scrollable"))t.chatListView.scrollDown=true;
                else{t.chatListView.animator=new RecyclerView.ItemAnimator();t.chatListView.animator.running=true;}
                for(int i=0;i<3;i++){t.originalLayoutComplete();draw(t);}
                check(t.controller.calls.size()==calls&&t.codexHistoryInitialFillUsedToken!=t.codexHistoryViewToken,"unstable or scrollable consumed "+kind);
                int invalidations=t.chatListView.invalidations;for(int i=0;i<10;i++){draw(t);pump();}
                check(t.chatListView.invalidations==invalidations&&t.controller.calls.size()==calls,"no callback polled "+kind);
                t.chatListView.pending=false;t.chatListView.computing=false;t.chatListView.layoutRequested=false;t.chatListView.height=500;t.chatListView.visible=1;t.chatListView.scrollDown=false;
                if(t.chatListView.animator!=null)t.chatListView.animator.running=false;t.originalLayoutComplete();draw(t);
                check(t.controller.calls.size()==calls+1,"stable actual layout did not resume "+kind);
                System.out.println("PASS "+kind+" waits real stable layout without self-retry or consumed quota");
            }finally{cleanup();}
            for(String kind:new String[]{"pause","destroy","account-index","account-generation","remote","dialog","new-epoch","new-token","drag","search","selection","frozen","loading","new-list","ordinary-telegram"})try{
            """+setup+"""
                draw(t);t.chatListView.scrollUp=false;t.originalLayoutComplete();t.chatListView.preDraw();
                if(kind.equals("pause"))t.pauseCodexHistoryView(false);else if(kind.equals("destroy"))t.pauseCodexHistoryView(true);else if(kind.equals("account-index"))t.currentAccount=1;
                else if(kind.equals("account-generation"))accountGeneration++;else if(kind.equals("remote"))remoteIds.put(42L,"another-synthetic");else if(kind.equals("dialog"))t.dialog_id=99;
                else if(kind.equals("new-epoch"))t.codexHistoryEpoch="another-synthetic";
                else if(kind.equals("new-token")){pauseHistoryView(7,t.codexHistoryViewToken);t.codexHistoryViewToken=openHistoryView(0,42,7);}
                else if(kind.equals("drag"))t.lockCodexHistoryReading();else if(kind.equals("search"))t.searching=true;else if(kind.equals("selection"))t.selected=true;
                else if(kind.equals("frozen"))t.chatAdapter.isFrozen=true;else if(kind.equals("loading"))t.loading=true;else if(kind.equals("ordinary-telegram"))remoteIds.clear();else t.chatListView=new RecyclerListView();
                ui();pump();check(t.controller.calls.size()==calls&&Utilities.globalQueue.ready.isEmpty(),"stale queued draw admitted "+kind);
                // 换list只能由当前新layout另行申请，旧ticket必须先被拒绝；其余失效owner连完成事件也不能放行。
                if(!kind.equals("new-list")){t.originalLayoutComplete();t.originalAnimationComplete();t.originalForcedAnimationEnd();ui();draw(t);check(t.controller.calls.size()==calls,"completion relaxed owner "+kind);}
                System.out.println("PASS current owner rejects queued/completion "+kind);
            }finally{org.telegram.messenger.UserConfig.selectedAccount=0;cleanup();}
            try{
                Ui t=initial("A,B,C,D");t.chatListView.scrollUp=true;t.chatListView.pending=true;draw(t);
                t.chatListView.pending=false;t.originalLayoutComplete();draw(t);
                check(t.codexHistoryInitialFillUsedToken!=t.codexHistoryViewToken,"old cache transient consumed budget");
                String old=t.codexHistoryEpoch;current.latest=page("Z",true,"new-older","new-tail");watchConversation(0,42);pump();t.consume(replacement());events.clear();ui();pump();
                check(!old.equals(t.codexHistoryEpoch),"actual replacement did not change epoch");int calls=t.controller.calls.size();
                t.chatListView.pending=true;t.chatListView.scrollUp=false;draw(t);t.chatListView.pending=false;t.originalLayoutComplete();draw(t);
                check(t.controller.calls.size()==calls+1,"cache transient prevented new latest stable event");
                System.out.println("PASS cache transient leaves original quota for same-token latest replacement and stable layout");
            }finally{cleanup();}
            try{
            """+setup+"""
                draw(t);t.chatListView.scrollUp=false;t.originalLayoutComplete();Runnable oldDraw=t.chatListView.draw.remove();
                current.latest=page("Q",true,"q-older","q-tail");watchConversation(0,42);pump();t.consume(replacement());events.clear();ui();pump();oldDraw.run();ui();
                check(t.controller.calls.size()==calls,"old-epoch queued task dispatched during actual replacement");
                t.originalLayoutComplete();draw(t);check(t.controller.calls.size()==calls+1,"new actual epoch did not retain current allowance");
                System.out.println("PASS real latest replaces queued old epoch; only new current page may dispatch once");
            }finally{cleanup();}
            """;
        HistoryInitialViewportFillTest.main(new String[]{source,scenarios});
    }
}
