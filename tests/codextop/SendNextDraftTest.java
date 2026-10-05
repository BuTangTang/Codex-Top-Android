package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实文字分段、发送后清理及动画入口，交错下一草稿与旧回调。 */
public final class SendNextDraftTest {
    /** 允许前像 RED 重放；所有输入、发送接收端和 UI 队列均为合成边界。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0
                ? "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java" : args[0]);
        var unit = StaticJavaParser.parse(source);
        var owner = unit.getClassByName("ChatActivityEnterView").orElseThrow();
        MethodDeclaration sending = owner.getMethodsByName("sendMessageInternal").get(0);
        var capture = sending.findAll(VariableDeclarationExpr.class).stream()
                .filter(value -> value.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals("message")
                        && variable.getInitializer().orElseThrow().toString().contains("getTextToUse")))
                .findFirst().orElseThrow();
        var cleanup = sending.findAll(IfStmt.class).stream()
                .filter(value -> value.getCondition().isMethodCallExpr()
                        && value.getCondition().asMethodCallExpr().getNameAsString().equals("processSendingText"))
                .findFirst().orElseThrow();
        StringBuilder actual = new StringBuilder("void sendCurrent(int scheduleDate) { boolean notify=true;int scheduleRepeatPeriod=0;long payStars=0;");
        actual.append(capture).append(';').append(cleanup).append("}\n");
        for (String name : new String[]{"processSendingText", "startMessageTransition", "canShowMessageTransition"})
            actual.append(owner.getMethodsByName(name).get(0)).append('\n');
        Path temporary = Files.createTempDirectory("codex-next-draft-");
        try {
            Path fixture = temporary.resolve("SendNextDraftProbe.java");
            Files.writeString(fixture, FIXTURE + actual.toString().replace("com.butang.codextop.CodexRuntime", "CodexRuntime") + SCENARIOS + "}\n");
            check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                    "-d", temporary.toString(), fixture.toString()) == 0, "真实发送/动画方法 Java 8 编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("SendNextDraftProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("真实发送后草稿回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 拒绝缺失真实源码或编译失败，不能以替代算法放行。 */
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final String FIXTURE = """
        import java.util.*;
        public final class SendNextDraftProbe {
            static final List<String> events=new ArrayList<>();
            static final class CodexRuntime {
                static boolean active,owns;
                static boolean enabled(){return active;}
                static boolean ownsConversation(long dialog){return owns&&dialog==42;}
            }
            static final class AndroidUtilities {
                static final ArrayList<Runnable> pending=new ArrayList<>();static final Point displaySize=new Point();
                static CharSequence getTrimmedString(CharSequence text){return text.toString().trim();}
                static void runOnUIThread(Runnable task,int delay){if(delay!=200)throw new AssertionError("delay changed");pending.add(task);}
                static void cancelRunOnUIThread(Runnable task){pending.remove(task);}
                static void drain(){int limit=20;while(!pending.isEmpty()){if(--limit==0)throw new AssertionError("queue loop");pending.remove(0).run();}}
            }
            static final class Point {int y;}
            static final class Editor {
                String value="";int clears;
                CharSequence getTextToUse(){return value;}
                void setText(String text){value=text;if(text.isEmpty()){clears++;events.add("clear");}}
                void append(String text){value+=text;}
                void getLocationInWindow(int[] location){}
            }
            static final class Delegate {
                boolean forwarding;int callbacks;final List<String> messages=new ArrayList<>();
                boolean hasForwardingMessages(){return forwarding;}
                void prepareMessageSending(){}
                void onMessageSend(CharSequence message,boolean notify,int date,int repeat,long stars){
                    callbacks++;messages.add(message==null?null:message.toString());events.add("delegate");}
            }
            static final class Emoji {static void parseEmojis(CharSequence text,int[] counts){counts[0]=0;}}
            static final class MessageObject {
                static final class SendAnimationData {boolean fromPreview;int width,height,x,y;}
            }
            static final class TLRPC {
                static class MessageEntity {}static class Chat {}static class WebPage {}
                static class TL_webPagePending extends WebPage {}
                static class TL_messageMediaWebPage {WebPage webpage;boolean force_small_media,force_large_media;}
            }
            static final class ChatObject {static boolean canSendEmbed(TLRPC.Chat chat){return true;}}
            static final class MediaDataController {
                static final MediaDataController instance=new MediaDataController();
                static MediaDataController getInstance(int account){return instance;}
                ArrayList<TLRPC.MessageEntity> getEntities(CharSequence[] text,boolean supports){return new ArrayList<>();}
            }
            static final class SendMessagesHelper {
                static final SendMessagesHelper instance=new SendMessagesHelper();static final List<String> sent=new ArrayList<>();
                static final class SendMessageParams {
                    String message;Object sendMessageChatArguments,suggestionParams;long effect_id,payStars,monoForumPeer;
                    boolean invert_media,searchLinks;TLRPC.TL_messageMediaWebPage mediaWebPage;
                    static SendMessageParams of(String text,Object... rest){SendMessageParams p=new SendMessageParams();p.message=text;return p;}
                }
                static SendMessagesHelper getInstance(int account){return instance;}
                static boolean checkUpdateStickersOrder(CharSequence text){return false;}
                void sendMessage(SendMessageParams params){sent.add(params.message);events.add("send:"+params.message);}
            }
            static final class Controller {int maxLength=4096;int getMaxMessageLength(){return maxLength;}}
            static final class Account {final Controller controller=new Controller();Controller getMessagesController(){return controller;}}
            static final class Quote {boolean outdated;}
            static final class PreviewParams {
                boolean webpageTop,webpageSmall;
                void updateLink(int account,Object link,String text,Object a,Object b,Object c){}
            }
            static final class Parent {
                Object editingMessageObject,foundWebPage;PreviewParams messagePreviewParams;
                boolean isInScheduleMode(){return false;}
                void showQuoteMessageUpdate(){}
                Object getMessageChatSendParams(){return null;}
                TLRPC.Chat getCurrentChat(){return null;}
                void fallbackFieldPanel(){}
            }
            static final class SendButton {void setEffect(long effect){}}
            static final class Preview {boolean isShowing(){return false;}}
            final Editor messageEditText=new Editor();final Delegate delegate=new Delegate();
            final Account accountInstance=new Account();final SendButton sendButton=new SendButton();
            final int[] location=new int[2];final int DEFAULT_HEIGHT=50;int currentAccount,hiddenTop;
            long dialog_id=42,effectId,sentFromPreview=-1000,lastTypingTimeSend;
            boolean scheduleMode,forceShowSendButton,messageTransitionIsRunning;
            Runnable moveToSendStateRunnable;Quote replyingQuote;Parent parentFragment;
            MessageObject replyingMessageObject,replyingTopMessage;TLRPC.WebPage messageWebPage;
            boolean messageWebPageSearch;Preview messageSendPreview;
            boolean isInScheduleMode(){return scheduleMode;}
            void hideTopView(boolean animate){hiddenTop++;}
            boolean supportsSendingNewEntities(){return false;}
            int dp(int value){return value;}
            MessageObject getThreadMessage(){return null;}
            long getSendMonoForumPeerId(){return 0;}
            Object getSendMessageSuggestionParams(){return null;}
            void applyStoryToSendMessageParams(SendMessagesHelper.SendMessageParams params){}
            void setWebPage(Object page,boolean search){}
        """;

    private static final String SCENARIOS = """
            static final List<String> failures=new ArrayList<>();static int checks;
            static void expect(boolean value,String reason){checks++;if(!value)failures.add(reason);}
            static SendNextDraftProbe reset(boolean active,boolean owns,String message){
                AndroidUtilities.pending.clear();SendMessagesHelper.sent.clear();events.clear();
                CodexRuntime.active=active;CodexRuntime.owns=owns;
                SendNextDraftProbe probe=new SendNextDraftProbe();probe.messageEditText.value=message;return probe;
            }
            /** 逐个交错位置重放字符输入；计时与动画触发必须都不清下一草稿。 */
            static void race(int split,boolean animation){
                String original="Reply-with-synthetic",draft="draft202610060003";
                SendNextDraftProbe probe=reset(true,true,original);probe.sendCurrent(0);
                expect(probe.messageEditText.value.isEmpty(),"Codex send did not clear synchronously");
                expect(probe.delegate.callbacks==1,"Codex completion not synchronous");
                probe.messageEditText.append(draft.substring(0,split));
                if(animation)probe.startMessageTransition();AndroidUtilities.drain();
                probe.messageEditText.append(draft.substring(split));
                expect(probe.messageEditText.value.equals(draft),"next draft lost at "+split+" animation="+animation);
                expect(SendMessagesHelper.sent.equals(Arrays.asList(original)),"sent text changed");
                expect(probe.delegate.callbacks==1&&probe.messageEditText.clears==1,"send completion repeated");
            }
            /** 普通包、未归属会话分别执行原计时和动画提前完成路径。 */
            static void ordinary(boolean active,boolean owns,boolean animation){
                SendNextDraftProbe probe=reset(active,owns,"ordinary");probe.sendCurrent(0);
                expect(probe.messageEditText.value.equals("ordinary")&&probe.delegate.callbacks==0,"ordinary text cleared early");
                expect(probe.canShowMessageTransition()&&AndroidUtilities.pending.size()==1,"ordinary animation missing");
                if(animation)probe.startMessageTransition();AndroidUtilities.drain();
                expect(probe.messageEditText.value.isEmpty()&&probe.delegate.callbacks==1&&probe.hiddenTop==1,"ordinary completion changed");
                expect(probe.messageTransitionIsRunning==animation,"ordinary transition state changed");
            }
            public static void main(String[] args){
                for(int split:new int[]{0,5,14,16})for(boolean animation:new boolean[]{false,true})race(split,animation);
                for(boolean animation:new boolean[]{false,true}){ordinary(false,true,animation);ordinary(true,false,animation);ordinary(false,false,animation);}
                SendNextDraftProbe probe=reset(true,true,"same");probe.sendCurrent(0);probe.messageEditText.append("same");
                probe.startMessageTransition();AndroidUtilities.drain();expect(probe.messageEditText.value.equals("same"),"same-text next draft lost");
                probe=reset(true,true,"first");probe.sendCurrent(0);probe.messageEditText.append("second");probe.sendCurrent(0);
                probe.messageEditText.append("third");probe.startMessageTransition();AndroidUtilities.drain();
                expect(SendMessagesHelper.sent.equals(Arrays.asList("first","second")),"rapid sends mixed text");
                expect(probe.messageEditText.value.equals("third")&&probe.delegate.callbacks==2&&probe.messageEditText.clears==2,"rapid sends cleared next draft");
                probe=reset(true,true,"alpha beta gamma delta");probe.accountInstance.controller.maxLength=11;probe.sendCurrent(0);
                expect(SendMessagesHelper.sent.equals(Arrays.asList("alpha beta","gamma delta")),"actual multipart text changed");
                expect(events.equals(Arrays.asList("send:alpha beta","send:gamma delta","clear","delegate")),"multipart cleared before all parts sent");
                probe=reset(true,true,"   ");probe.sendCurrent(0);AndroidUtilities.drain();
                expect(probe.messageEditText.value.equals("   ")&&probe.messageEditText.clears==0&&probe.delegate.callbacks==0,"rejected empty text lost");
                probe=reset(true,true,"quote");probe.parentFragment=new Parent();probe.replyingQuote=new Quote();probe.replyingQuote.outdated=true;
                probe.sendCurrent(0);AndroidUtilities.drain();expect(probe.messageEditText.value.equals("quote")&&SendMessagesHelper.sent.isEmpty(),"rejected quote lost");
                for(int mode=0;mode<3;mode++){
                    probe=reset(false,false,"original-sync");probe.delegate.forwarding=mode==0;probe.scheduleMode=mode==1;
                    probe.sendCurrent(mode==2?123:0);
                    expect(probe.messageEditText.value.isEmpty()&&probe.delegate.callbacks==1&&AndroidUtilities.pending.isEmpty(),"original sync mode changed "+mode);
                }
                if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
                System.out.println("SendNextDraft: "+checks+" checks passed using actual text splitting, completion and transition methods");
            }
        """;
}
