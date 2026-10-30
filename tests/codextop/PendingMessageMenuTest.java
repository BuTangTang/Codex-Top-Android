package com.butang.codextop;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取正式菜单、点击处理及输入门禁；视图使用替身，删除交错另调用真实Runtime与Outbox。 */
public final class PendingMessageMenuTest {
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java");
        boolean red = args.length > 0;
        Path baseline = red ? Path.of(args[0]) : null;
        String chat = Files.readString(red ? baseline.resolve("ChatActivity.java") : source.resolve("org/telegram/ui/ChatActivity.java"));
        String runtime = Files.readString(red ? baseline.resolve("CodexRuntime.java") : source.resolve("com/butang/codextop/CodexRuntime.java"));
        String enter = Files.readString(red ? baseline.resolve("ChatActivityEnterView.java") : source.resolve("org/telegram/ui/Components/ChatActivityEnterView.java"));
        StringBuilder runtimeMethods = new StringBuilder();
        for (String name : new String[]{"isAccountCurrent", "isMessageDeliveryUncertain", "canRetryMessage", "isAttachmentMessage", "pendingMessageMenu", "ownsPendingMessage"}) {
            String method = method(runtime, name); if (method != null) runtimeMethods.append(method).append('\n');
        }
        if (!red) runtimeMethods.append(block(runtime, "    public static final class PendingMessageMenu"));
        String fill = method(chat, "fillMessageMenu");
        fill = fill.substring(0, fill.indexOf("        final MessageObject.GroupedMessages")) + "}\n";
        StringBuilder ui = new StringBuilder(fill);
        if (!red) {
            ui.append(method(chat, "canRestoreCodexPendingText"));
            String handler = method(chat, "processSelectedOption");
            ui.append("void processSelectedOption(int option){").append(handler, handler.indexOf('{') + 1, handler.indexOf("        switch (option)"));
            ui.append("switch(option){").append(block(handler, "            case OPTION_CODEX_RESTORE_DRAFT:"))
                    .append(block(handler, "            case OPTION_CODEX_REMOVE_PENDING:")).append("}\n")
                    .append(handler.substring(handler.lastIndexOf("        selectedObject = null;")));
            if (!fill.contains("options.add(OPTION_RETRY)") || !fill.contains("options.add(OPTION_COPY)")
                    || !fill.contains("options.add(OPTION_SAVE_TO_GALLERY)") || !fill.contains("options.add(OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC)"))
                throw new AssertionError("原retry/copy/附件入口丢失");
            String additions = block(handler, "            case OPTION_CODEX_RESTORE_DRAFT:") + block(handler, "            case OPTION_CODEX_REMOVE_PENDING:");
            for (String forbidden : new String[]{"OPTION_EDIT", "createDeleteMessagesAlert", "getSendMessagesHelper", "retrySendMessage", "getMessagesController", "TL_messages"})
                if (additions.contains(forbidden)) throw new AssertionError("本地菜单误入云操作或重试");
        }
        String enterMethods = red ? "" : method(enter, "canRestorePlainTextDraft") + method(enter, "isRecordingAudioVideo")
                + block(enter, "    public void setFieldText(CharSequence text) {")
                + block(enter, "    public void setFieldText(CharSequence text, boolean ignoreChange, boolean fromDraft) {");
        String delegate = "";
        if (!red) {
            int start = chat.indexOf("            if (!fromDraft && com.butang.codextop.CodexRuntime.ownsConversation(dialog_id))");
            delegate = block(chat, chat.substring(start, chat.indexOf('{', start))).replace("ChatActivity.this.saveDraft();", "saveDraft();");
        }
        Path temp = Files.createTempDirectory("codex-pending-menu-");
        try {
            ArrayList<String> compile = new ArrayList<>();
            compile.addAll(java.util.List.of("--release", "8", "-encoding", "UTF-8", "-d", temp.toString()));
            String[] names = {"CodexRuntime.java", "PendingMenuProbe.java", "MessageObject.java", "UserConfig.java", "TLRPC.java"};
            String[] bodies = {RUNTIME + runtimeMethods + "\n}", UI + ui + "\n static class EnterView {" + ENTER
                    + enterMethods + "void onTextChanged(CharSequence text,boolean bigChange,boolean fromDraft){" + delegate + "}\n}\n" + (red ? RED : SCENARIOS) + "\n}", MESSAGE, USER, TL};
            for (int i = 0; i < names.length; i++) { Path file = temp.resolve(names[i]); Files.writeString(file, bodies[i]); compile.add(file.toString()); }
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(new String[0])) != 0)
                throw new AssertionError("实际菜单方法Java8夹具编译失败");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, null)) {
                try { loader.loadClass("com.butang.codextop.PendingMenuProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("实际待发菜单行为失败", error.getCause()); }
            }
        } finally { try (var paths = Files.walk(temp)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file); } }
        if (!red) verifyRemovalQueue(source.resolve("com/butang/codextop/CodexRuntime.java"));
    }

    /** 复用原真实发送/删除方法夹具；仅控制UI通知何时交付，不替换Outbox或发送决策。 */
    private static void verifyRemovalQueue(Path source) throws Exception {
        RuntimeSendQueueTest.run(source, REMOVAL_SCENARIOS, fixture -> fixture
                .replace("static int notices, reconciled, loaded,deleted;", "static int notices, reconciled, loaded,deleted;static ArrayList<Integer> lastDeleted;")
                .replace("if(event==messagesDeleted)deleted++;", "if(event==messagesDeleted){deleted++;lastDeleted=new ArrayList<>((ArrayList<Integer>)args[0]);}"));
    }

    /** 提取原方法/块，忽略字符串和注释中的括号，不复制产品资格逻辑。 */
    private static String method(String source, String name) {
        var matcher = java.util.regex.Pattern.compile("(?m)^    (?:public|private|protected)[^\\n]*\\b" + name + "\\(").matcher(source);
        return matcher.find() ? block(source, source.substring(matcher.start(), source.indexOf('(', matcher.start()) + 1)) : null;
    }
    private static String block(String source, String marker) {
        int start=source.indexOf(marker),open=source.indexOf('{',start),depth=0;
        if(start<0||open<0)throw new AssertionError("实际源码块缺失："+marker);
        boolean string=false,character=false,line=false,comment=false,escape=false;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i),next=i+1<source.length()?source.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}if(comment){if(c=='*'&&next=='/'){comment=false;i++;}continue;}
            if(string||character){if(escape)escape=false;else if(c=='\\')escape=true;else if(string&&c=='"')string=false;else if(character&&c=='\'')character=false;continue;}
            if(c=='/'&&next=='/'){line=true;i++;}else if(c=='/'&&next=='*'){comment=true;i++;}else if(c=='"')string=true;else if(c=='\'')character=true;
            else if(c=='{')depth++;else if(c=='}'&&--depth==0)return source.substring(start,i+1);
        }throw new AssertionError("实际源码块未闭合");
    }

    private static final String USER = "package org.telegram.messenger; public class UserConfig {public static int selectedAccount;}";
    private static final String TL = """
        package org.telegram.tgnet;import java.util.*;public class TLRPC {
          public static class TL_messageMediaEmpty {} public static class Message {public int id;public long dialog_id;public boolean out=true;public String message="original\\n合成正文";public Map<String,String> params=new HashMap<>();public Object media=new TL_messageMediaEmpty();public ArrayList<Object> entities=new ArrayList<>();}
          public static class DraftMessage {public String message;public Object rich_message,reply_to;public ArrayList<Object> entities=new ArrayList<>();}
        }
        """;
    private static final String MESSAGE = """
        package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class MessageObject {public int currentAccount;public TLRPC.Message messageOwner=new TLRPC.Message();public boolean error=true,photo;public int getId(){return messageOwner.id;}public long getDialogId(){return messageOwner.dialog_id;}public boolean isOut(){return messageOwner.out;}public boolean isSendError(){return error;}public boolean isPhoto(){return photo;}}
        """;
    private static final String RUNTIME = """
        package com.butang.codextop;import java.util.*;import org.telegram.messenger.MessageObject;import org.telegram.tgnet.TLRPC;
        public class CodexRuntime {static Object session=new Object();static long accountGeneration=1;static boolean loggingOut,enabled=true;static Map<String,MessageObject> pendingMessages=new HashMap<>();static Set<String> sendingBatches=new HashSet<>();static Map<String,java.util.concurrent.atomic.AtomicBoolean> sendJobs=new HashMap<>();static int localDeletes;static ArrayList<Integer> deleted;static int deleteAccount;static long deleteDialog;
          public static boolean ownsConversation(long id){return id==42;}public static boolean enabled(){return enabled;}
          public static Object attachmentFile(MessageObject m){return isAttachmentMessage(m)?new Object():null;}
          public static boolean deletePendingMessages(int a,long d,ArrayList<Integer> ids){localDeletes++;deleteAccount=a;deleteDialog=d;deleted=new ArrayList<>(ids);return true;}
          static void reset(){session=new Object();accountGeneration=1;loggingOut=false;enabled=true;pendingMessages.clear();sendingBatches.clear();sendJobs.clear();localDeletes=0;deleted=null;org.telegram.messenger.UserConfig.selectedAccount=0;}
        """;
    private static final String UI = """
        package com.butang.codextop;import java.util.*;import org.telegram.messenger.MessageObject;import org.telegram.tgnet.TLRPC;
        public class PendingMenuProbe {
          static final int OPTION_RETRY=0,OPTION_COPY=3,OPTION_SAVE_TO_GALLERY=4,OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC=10,OPTION_CODEX_RESTORE_DRAFT=117,OPTION_CODEX_REMOVE_PENDING=118;
          int currentAccount;long dialog_id=42;MessageObject selectedObject,selectedObjectToEditCaption,editingMessageObject;Object selectedObjectGroup;EnterView chatActivityEnterView=new EnterView();Media media=new Media();int closes;boolean activity=true;
          static class R {static class string{static int Retry=1,Copy=2,SaveToGallery=3,SaveToDownloads=4;}static class drawable{static int msg_retry=1,msg_copy=2,msg_gallery=3,msg_download=4,msg_edit=5,msg_delete=6;}}
          static class LocaleController{static String getString(int id){return "original-"+id;}}static class TextUtils{static boolean isEmpty(CharSequence v){return v==null||v.length()==0;}}
          static class Media{Object voice;TLRPC.DraftMessage draft;Object getDraftVoice(long d,long t){return voice;}TLRPC.DraftMessage getDraft(long d,long t){return draft;}}
          Media getMediaDataController(){return media;}long getTopicId(){return 0;}long getDraftThreadId(){return 0;}Object getParentActivity(){return activity?this:null;}void closeMenu(boolean dim){closes++;}
          static class AndroidUtilities{static ArrayDeque<Runnable> queue=new ArrayDeque<>();static void cancelRunOnUIThread(Runnable r){queue.remove(r);}static void runOnUIThread(Runnable r,int delay){queue.add(r);}}
          static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
          static PendingMenuProbe fresh(){CodexRuntime.reset();AndroidUtilities.queue.clear();PendingMenuProbe p=new PendingMenuProbe();p.selectedObject=pending("local", "local", -1);return p;}
          static MessageObject pending(String local,String batch,int id){MessageObject m=new MessageObject();m.messageOwner.id=id;m.messageOwner.dialog_id=42;m.messageOwner.params.put("codexLocalId",local);m.messageOwner.params.put("codexBatchLocalId",batch);m.messageOwner.params.put("codexAttachmentIndex","0");CodexRuntime.pendingMessages.put(local,m);return m;}
          ArrayList<Integer> menu(){ArrayList<Integer> options=new ArrayList<>();fillMessageMenu(selectedObject,new ArrayList<Integer>(),new ArrayList<CharSequence>(),options);return options;}
          ArrayList<CharSequence> labels(){ArrayList<CharSequence> items=new ArrayList<>();fillMessageMenu(selectedObject,new ArrayList<Integer>(),items,new ArrayList<Integer>());return items;}
        """;
    private static final String ENTER = """
          static final int VISIBLE=0;long dialog_id=42;int recordInterfaceState,draftSaves,keyboards;boolean richDraftActive,forceShowSendButton,recordingAudioVideo,recordIsCanceled,recordAudioVideoRunnableStarted,ignoreTextChange;Object editingMessageObject,replyingMessageObject,replyingQuote,richDraftMessage,audioToSend,audioToSendMessageObject,videoToSendMessageObject;Runnable moveToSendStateRunnable,codexDraftSaveRunnable;Panel recordedAudioPanel;Animation runningAnimationAudio;EditField messageEditText=new EditField();EnterView delegate=this;
          static class Panel{int visibility;int getVisibility(){return visibility;}}static class Animation{boolean running;boolean isRunning(){return running;}}
          static class EditField{String text="";Object caption;int length(){return text.length();}Object getCaption(){return caption;}void setText(CharSequence text){this.text=text.toString();}void invalidateQuotes(boolean value){}void setSelection(int value){}String getText(){return text;}}
          void openKeyboard(){keyboards++;}void saveDraft(){draftSaves++;}
        """;
    private static final String RED = """
          public static void main(String[] args){PendingMenuProbe p=fresh();check(p.labels().contains("放入输入框"),"confirmed failed plain text has no restore-to-input menu entry");}
        """;
    private static final String REMOVAL_SCENARIOS = """
          /** 每轮真实失败发送留下同一气泡与磁盘记录，随后控制UI删除回调窗口。 */
          static MessageObject failed(Path root)throws Exception{reset(root);connection.failStage="reject-send";startText();drainSend();ui.all();NotificationCenter.deleted=0;return pendingMessages.values().iterator().next();}
          public static void main(String[] args)throws Exception{
            Path root=Paths.get(args[0]);MessageObject original=failed(root);String id=base();int before=connection.sendCalls;
            check(deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(original.getId()))),"local remove was rejected");
            check(!canRetryMessage(original)&&sendingBatches.contains(id),"delete did not synchronously reserve original batch");
            sendQueue.all();check(store.get(id)==null&&pendingMessages.get(id)==original,"delete/UI notification gap missing");
            connection.failStage=null;retry();sendQueue.all();check(store.get(id)==null&&connection.sendCalls==before,"deleted pending resurrected or resent before UI callback");
            ui.all();check(pendingMessages.isEmpty()&&sendingBatches.isEmpty()&&NotificationCenter.deleted==1,"successful remove lost UI cleanup or retained gate");

            original=failed(root);id=base();Path file=record(id);byte[] saved=Files.readAllBytes(file);
            check(deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(original.getId()))),"failure fixture remove rejected");
            // 不损坏原记录；额外不可读JSON让真实list失败，持久化异常仍走原catch。
            Path broken=file.getParent().resolve("broken.json");Files.createDirectory(broken);Files.write(broken.resolve("child"),new byte[]{1});
            sendingBatches.add("unrelated-batch");sendQueue.all();check(!canRetryMessage(original),"failure released gate outside UI owner");ui.all();
            check(canRetryMessage(original)&&pendingMessages.get(id)==original&&Arrays.equals(saved,Files.readAllBytes(file))&&NotificationCenter.deleted==0,"failure lost bubble, disk record or retry");
            check(sendingBatches.contains("unrelated-batch"),"failure released a gate it did not acquire");

            original=failed(root);id=base();deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(original.getId())));sendQueue.all();
            MessageObject replacement=new MessageObject(0,pendingMessage(1,original.getId(),1000,"replacement",id,0),true,false);pendingMessages.put(id,replacement);ui.all();
            check(pendingMessages.get(id)==replacement&&!NotificationCenter.lastDeleted.contains(replacement.getId()),"old delete removed replacement object or sent its id to UI");

            original=failed(root);id=base();deletePendingMessages(0,1,new ArrayList<>(Arrays.asList(original.getId())));sendQueue.all();accountGeneration++;
            replacement=new MessageObject(0,pendingMessage(1,original.getId(),1000,"replacement",id,0),true,false);pendingMessages.put(id,replacement);ui.all();
            check(pendingMessages.get(id)==replacement&&sendingBatches.contains(id)&&NotificationCenter.deleted==0,"old account callback changed new owner");
            System.out.println("PendingMessageMenu: 4 real Runtime/Outbox removal, failure and late-owner scenarios PASS");
          }
        """;
    private static final String SCENARIOS = """
          static void forbidden(PendingMenuProbe p,String reason){check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT)&&!p.menu().contains(OPTION_CODEX_REMOVE_PENDING),reason);}
          public static void main(String[] args){
            PendingMenuProbe p=fresh();MessageObject original=p.selectedObject;String body=original.messageOwner.message;
            check(p.labels().contains("放入输入框")&&p.labels().contains("从手机移除")&&p.menu().contains(OPTION_RETRY)&&p.menu().contains(OPTION_COPY),"failed text menu missing");
            check(p.menu().equals(Arrays.asList(OPTION_RETRY,OPTION_CODEX_RESTORE_DRAFT,OPTION_COPY,OPTION_CODEX_REMOVE_PENDING)),"retry must lead and local removal must be last");
            p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check(body.equals(p.chatActivityEnterView.messageEditText.text)&&p.chatActivityEnterView.keyboards==1&&CodexRuntime.localDeletes==0&&CodexRuntime.pendingMessages.get("local")==original&&original.error,"restore changed original bubble or did not use input path");
            check(AndroidUtilities.queue.size()==1,"original delayed draft-save path was bypassed");AndroidUtilities.queue.remove().run();check(p.chatActivityEnterView.draftSaves==1,"original draft save not called");
            p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check(p.chatActivityEnterView.keyboards==1,"double click restored twice");
            p.selectedObject=original;check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT),"nonempty restored text can be overwritten");
            p=fresh();p.selectedObject.messageOwner.params.put("codexSendUncertain","true");p.selectedObject.error=false;check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT)&&!p.menu().contains(OPTION_RETRY)&&p.menu().contains(OPTION_CODEX_REMOVE_PENDING),"unknown permits retry/restore or disallows local removal");
            p=fresh();p.selectedObject.messageOwner.params.put("codexSendAccepted","true");forbidden(p,"accepted shows local menu");
            p=fresh();p.selectedObject.error=false;forbidden(p,"real sending shows local menu");
            p=fresh();CodexRuntime.sendingBatches.add("local");forbidden(p,"sending batch shows local menu");
            p=fresh();CodexRuntime.sendJobs.put("local",new java.util.concurrent.atomic.AtomicBoolean(true));forbidden(p,"active send job shows local menu");
            p=fresh();p.currentAccount=1;forbidden(p,"wrong account accepted");
            p=fresh();org.telegram.messenger.UserConfig.selectedAccount=1;forbidden(p,"old selected account accepted");
            p=fresh();p.selectedObject.currentAccount=1;forbidden(p,"message from other account accepted");
            p=fresh();p.selectedObject.messageOwner.dialog_id=99;forbidden(p,"wrong message dialog accepted");
            p=fresh();p.selectedObject.messageOwner.id=1;forbidden(p,"positive history id accepted");
            p=fresh();p.selectedObject.messageOwner.out=false;forbidden(p,"incoming message accepted");
            p=fresh();p.selectedObject.messageOwner.params.remove("codexLocalId");forbidden(p,"missing local identity accepted");
            p=fresh();pending("local","local",-1);forbidden(p,"stale object with same localId/id accepted");
            p=fresh();CodexRuntime.loggingOut=true;forbidden(p,"logout accepted");p=fresh();CodexRuntime.enabled=false;forbidden(p,"nonproduct accepted");
            p=fresh();p.selectedObject.messageOwner.entities.add(new Object());check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT),"nonplain entities restored");
            p=fresh();p.selectedObject.messageOwner.media=new Object();check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT),"nonplain media restored");
            for(int i=0;i<16;i++){p=fresh();EnterView e=p.chatActivityEnterView;switch(i){case 0:e.messageEditText.text=" ";break;case 1:e.editingMessageObject=new Object();break;case 2:e.richDraftActive=true;break;case 3:e.richDraftMessage=new Object();break;case 4:e.recordingAudioVideo=true;break;case 5:e.recordInterfaceState=1;break;case 6:e.audioToSend=new Object();break;case 7:e.audioToSendMessageObject=new Object();break;case 8:e.videoToSendMessageObject=new Object();break;case 9:e.recordedAudioPanel=new EnterView.Panel();break;case 10:e.messageEditText.caption="inline";break;case 11:e.forceShowSendButton=true;break;case 12:e.replyingMessageObject=new Object();break;case 13:e.replyingQuote=new Object();break;case 14:e.moveToSendStateRunnable=()->{};break;case 15:e.runningAnimationAudio=new EnterView.Animation();e.runningAnimationAudio.running=true;break;}
              check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT)&&p.menu().contains(OPTION_CODEX_REMOVE_PENDING),"composer content/state overwritten: "+i);p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check(e.keyboards==0,"click bypassed composer state: "+i);}
            p=fresh();p.chatActivityEnterView.recordAudioVideoRunnableStarted=true;check(p.menu().contains(OPTION_CODEX_RESTORE_DRAFT),"stale cancelled recording flag falsely blocks empty input");
            p=fresh();p.media.voice=new Object();check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT),"saved voice draft overwritten");
            for(int i=0;i<4;i++){p=fresh();p.media.draft=new TLRPC.DraftMessage();switch(i){case 0:p.media.draft.message="saved draft";break;case 1:p.media.draft.rich_message=new Object();break;case 2:p.media.draft.reply_to=new Object();break;case 3:p.media.draft.entities.add(new Object());break;}check(!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT),"persisted draft overwritten: "+i);}
            p=fresh();p.menu();p.chatActivityEnterView.messageEditText.text="new draft";p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check("new draft".equals(p.chatActivityEnterView.messageEditText.text)&&p.chatActivityEnterView.keyboards==0,"click did not recheck newly entered draft");
            p=fresh();p.menu();p.selectedObject.messageOwner.params.put("codexSendUncertain","true");p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check(p.chatActivityEnterView.keyboards==0,"late unknown restored as confirmed failure");
            p=fresh();p.menu();CodexRuntime.sendJobs.put("local",new java.util.concurrent.atomic.AtomicBoolean(true));p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check(p.chatActivityEnterView.keyboards==0,"late active send restored");
            p=fresh();p.menu();pending("local","local",-1);p.processSelectedOption(OPTION_CODEX_RESTORE_DRAFT);check(p.chatActivityEnterView.keyboards==0,"late replaced pending object restored");
            p=fresh();p.menu();p.selectedObject.messageOwner.params.put("codexSendAccepted","true");p.processSelectedOption(OPTION_CODEX_REMOVE_PENDING);check(CodexRuntime.localDeletes==0,"late accepted message removed");
            p=fresh();p.menu();CodexRuntime.sendingBatches.add("local");p.processSelectedOption(OPTION_CODEX_REMOVE_PENDING);check(CodexRuntime.localDeletes==0,"late retry batch removed");
            p=fresh();p.selectedObject.messageOwner.params.put("codexSelectedFile","synthetic");p.selectedObject.photo=true;MessageObject second=pending("local:attachment:1","local",-2);second.messageOwner.params.put("codexAttachmentIndex","1");second.messageOwner.params.put("codexPendingFile","synthetic");
            check(p.labels().contains("从手机移除此组")&&!p.menu().contains(OPTION_CODEX_RESTORE_DRAFT)&&p.menu().contains(OPTION_SAVE_TO_GALLERY),"attachment group label/save/restore wrong");p.selectedObject=second;p.processSelectedOption(OPTION_CODEX_REMOVE_PENDING);
            check(CodexRuntime.localDeletes==1&&CodexRuntime.deleted.equals(Arrays.asList(-2))&&CodexRuntime.deleteAccount==0&&CodexRuntime.deleteDialog==42,"remove did not call sole local owner with selected id");p.processSelectedOption(OPTION_CODEX_REMOVE_PENDING);check(CodexRuntime.localDeletes==1,"same menu click deleted twice");
            p=fresh();second=pending("local:attachment:1","local",-2);second.messageOwner.params.put("codexAttachmentIndex","1");second.messageOwner.params.put("codexSendAccepted","true");forbidden(p,"group with accepted sibling is deletable");
            p=fresh();CodexRuntime.pendingMessages.clear();p.selectedObject=pending("local:attachment:1","local",-2);p.selectedObject.messageOwner.params.put("codexAttachmentIndex","1");check(p.labels().contains("从手机移除此组"),"last retained later attachment hid batch scope");
            p=fresh();p.chatActivityEnterView.messageEditText.text="keep input";p.processSelectedOption(OPTION_CODEX_REMOVE_PENDING);check("keep input".equals(p.chatActivityEnterView.messageEditText.text)&&CodexRuntime.localDeletes==1,"local removal touched input");
            System.out.println("PendingMessageMenu: actual menu/handler/ownership/input guard Java8 scenarios PASS; no Android/real deletion acceptance");
          }
        """;
}
