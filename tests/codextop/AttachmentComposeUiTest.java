package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取输入区真实发送分支、原空输入分支、间距与纸夹触摸/点击；平台动画仅应用最终值。 */
public final class AttachmentComposeUiTest {
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String source = Files.readString(Path.of("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"));
        var method = StaticJavaParser.parseMethodDeclaration(between(source,
                "    public void checkSendButton(boolean animated)", "\n    private void setSlowModeButtonVisible("));
        IfStmt send = method.findAll(IfStmt.class).stream()
                .filter(value -> value.getCondition().toString().startsWith("com.butang.codextop.CodexRuntime.enabled()"))
                .findFirst().orElseThrow();
        // 仅省略慢速模式和贴纸展开入口；本夹具不配置这些平台状态，发送及原空输入分支不改写。
        IfStmt stickers = send.getElseStmt().orElseThrow().asIfStmt();
        IfStmt empty = stickers.getElseStmt().orElseThrow().asIfStmt();
        IfStmt selected = new IfStmt(send.getCondition().clone(), send.getThenStmt().clone(), empty.clone());
        BlockStmt body = new BlockStmt();
        for (var statement : method.getBody().orElseThrow().getStatements()) {
            if (statement.isIfStmt() && statement.asIfStmt().getCondition().toString().startsWith("slowModeTimer > 0")) break;
            body.addStatement(statement.clone());
        }
        body.addStatement(selected);
        method.setBody(body);
        String spacing = StaticJavaParser.parseMethodDeclaration(between(source,
                "    private void updateFieldRight(int attachVisible)", "\n    public void startMessageTransition(")).toString();
        String slow = StaticJavaParser.parseMethodDeclaration(between(source,
                "    private void setSlowModeButtonVisible(boolean visible)", "\n    private int lastAttachVisible;")).toString();
        String touch = StaticJavaParser.parseMethodDeclaration(between(source,
                "                public boolean dispatchTouchEvent(MotionEvent event)", "\n            };" )).toString();
        String click = between(source, "            attachButton.setOnClickListener(v -> {", "\n            attachButton.setContentDescription(");
        String setters = between(source, "    public void setFieldText(CharSequence text)", "\n    public void setVoiceDraft(");
        var watcher = StaticJavaParser.parseMethodDeclaration(between(source,
                "            public void onTextChanged(CharSequence charSequence, int start, int before, int count)",
                "\n            @Override\n            public void afterTextChanged("));
        BlockStmt changed = new BlockStmt();
        // 平台setText通知只执行原watcher中与发送控件相关的两个门禁和真实刷新调用。
        for (var statement : watcher.getBody().orElseThrow().getStatements()) {
            if (statement.isIfStmt() && (statement.asIfStmt().getCondition().toString().equals("ignorePrevTextChange")
                    || statement.asIfStmt().getCondition().toString().equals("innerTextChange == 1"))
                    || statement.toString().equals("checkSendButton(true);")) changed.addStatement(statement.clone());
        }
        if (changed.getStatements().size() != 3) throw new AssertionError("原输入刷新门禁发生变化");
        Path temp = Files.createTempDirectory("codex-attachment-compose-ui-");
        try {
            Path probe = temp.resolve("AttachmentComposeProbe.java");
            Files.writeString(probe, FIXTURE + "static class AttachButton extends View {" + touch + "}"
                    + "void setupAttachClick(){" + click + "}" + "void notifyTextChanged()" + changed
                    + setters + method + spacing + slow + SCENARIOS + "}");
            Path runtime = temp.resolve("CodexRuntime.java");
            Files.writeString(runtime, "package com.butang.codextop; public class CodexRuntime {public static boolean active; public static boolean enabled(){return active;}}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", temp.toString(), probe.toString(), runtime.toString()) != 0)
                throw new AssertionError("真实输入附件分支夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, AttachmentComposeUiTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.AttachmentComposeProbe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实输入附件回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static String between(String source, String start, String end) {
        int first = source.indexOf(start), last = first < 0 ? -1 : source.indexOf(end, first + start.length());
        if (first < 0 || last < 0) throw new AssertionError("原输入区提取边界发生变化: " + start.trim());
        return source.substring(first, last);
    }

    // 替身只提供平台属性、动画完成、布局和点击边界，不重写任何产品可见性判断。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        import java.util.function.Consumer;
        public final class AttachmentComposeProbe {
            static final int VISIBLE=0,GONE=8,DEFAULT_HEIGHT=48;
            static final Object ATTACH_LAYOUT_ALPHA="alpha";
            static final class MotionEvent {}
            static class View {
                static final int VISIBLE=0,GONE=8;
                static final String ALPHA="alpha",SCALE_X="scaleX",SCALE_Y="scaleY";
                float alpha=1,scaleX=1,scaleY=1,translationX;int visibility=VISIBLE,touches;
                Object tag;Consumer<View> click;
                float getAlpha(){return alpha;}void setAlpha(float value){alpha=value;}
                void setScaleX(float value){scaleX=value;}void setScaleY(float value){scaleY=value;}
                int getVisibility(){return visibility;}void setVisibility(int value){visibility=value;}
                void setTranslationX(float value){translationX=value;}void setPivotX(float value){}
                Object getTag(){return tag;}void setTag(Object value){tag=value;}Object getBackground(){return null;}
                int width(){return DEFAULT_HEIGHT;}boolean dispatchTouchEvent(MotionEvent event){touches++;return true;}
                Object getLayoutParams(){return new FrameLayout.LayoutParams();}void setLayoutParams(FrameLayout.LayoutParams value){}
                void setOnClickListener(Consumer<View> value){click=value;}
                void tap(){if(dispatchTouchEvent(new MotionEvent())&&click!=null)click.accept(this);}
                ViewPropertyAnimator animate(){return new ViewPropertyAnimator(this);}
            }
            static final class FrameLayout {static final class LayoutParams {int rightMargin;}}
            static class Edit extends View {
                String text="",caption;boolean nearRight;int padding;Runnable changed;
                FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams();
                CharSequence getTextToUse(){return text;}String getCaption(){return caption;}
                CharSequence getText(){return text;}void setText(CharSequence value){text=value==null?"":value.toString();if(changed!=null)changed.run();}
                void invalidateQuotes(boolean value){}void setSelection(int value){}
                boolean isNearRightCaption(int value){return nearRight;}
                Object getLayoutParams(){return layout;}void setLayoutParams(FrameLayout.LayoutParams value){layout=value;}
                int getPaddingRight(){return padding;}void setPadding(int a,int b,int c,int d){padding=c;}
            }
            static class Animator {void apply(){}void cancel(){}}
            static final class ObjectAnimator extends Animator {
                View target;Object property;float value;
                static ObjectAnimator ofFloat(View target,Object property,float value){
                    ObjectAnimator result=new ObjectAnimator();result.target=target;result.property=property;result.value=value;return result;}
                void apply(){if(property.equals("alpha"))target.setAlpha(value);else if(property.equals("scaleX"))target.setScaleX(value);else if(property.equals("scaleY"))target.setScaleY(value);}
            }
            static class AnimatorListenerAdapter {public void onAnimationEnd(Animator value){}public void onAnimationCancel(Animator value){}}
            static final class AnimatorSet extends Animator {
                static boolean defer;static List<AnimatorSet> pending=new ArrayList<>();boolean canceled;
                List<Animator> values=new ArrayList<>();AnimatorListenerAdapter listener;
                void playTogether(Collection<Animator> value){values.addAll(value);}void setDuration(int value){}void setInterpolator(Object value){}
                void addListener(AnimatorListenerAdapter value){listener=value;}
                void start(){if(defer)pending.add(this);else finish();}
                void finish(){if(canceled)return;for(Animator value:values)value.apply();if(listener!=null)listener.onAnimationEnd(this);}
                void cancel(){canceled=true;if(listener!=null)listener.onAnimationCancel(this);}
                static void finishPending(){var copy=new ArrayList<>(pending);pending.clear();for(var animation:copy)animation.finish();}
            }
            static final class ViewPropertyAnimator extends Animator {
                View target;float alpha,scaleX,scaleY;boolean started;
                ViewPropertyAnimator(View target){this.target=target;alpha=target.alpha;scaleX=target.scaleX;scaleY=target.scaleY;}
                ViewPropertyAnimator alpha(float value){alpha=value;return this;}ViewPropertyAnimator scaleX(float value){scaleX=value;return this;}
                ViewPropertyAnimator scaleY(float value){scaleY=value;return this;}ViewPropertyAnimator setInterpolator(Object value){return this;}
                ViewPropertyAnimator setDuration(int value){return this;}void start(){started=true;target.alpha=alpha;target.scaleX=scaleX;target.scaleY=scaleY;}
            }
            static final class CubicBezierInterpolator {static final Object EASE_OUT_QUINT=new Object();}
            static final class Theme {static final int key_glass_defaultIcon=1,key_chat_messagePanelSend=2;static void setSelectorDrawableColor(Object a,int b,boolean c){}}
            static final class Color {static int argb(int a,int r,int g,int b){return a;}static int red(int value){return 0;}static int green(int value){return 0;}static int blue(int value){return 0;}}
            static final class TextUtils {static boolean isEmpty(CharSequence value){return value==null||value.length()==0;}}
            static final class LocaleController {static boolean isRTL;}
            static final class AndroidUtilities {static CharSequence getTrimmedString(CharSequence value){return value.toString().trim();}}
            static final class Toggle {boolean value;boolean getValue(){return value;}}
            static final class ChatActivitySideControlsButtonsLayout {
                static final int BUTTON_ATTACH=1;boolean attachVisible;
                void showButton(int button,boolean visible,boolean animated){attachVisible=visible;}
            }
            static final class Delegate {int attaches,hidden,shown;boolean hasScheduledMessages(){return false;}
                void didPressAttachButton(){attaches++;}void onAttachButtonHidden(){hidden++;}void onAttachButtonShow(){shown++;}void onTextChanged(CharSequence value,boolean big,boolean draft){}}
            static final class Pan {boolean animationInProgress(){return false;}}
            static final class Message {boolean needResendWhenEdit(){return false;}}
            static final class TLRPC {static class Chat {}static class UserFull {boolean voice_messages_forbidden;}}
            static final class Parent {TLRPC.Chat getCurrentChat(){return null;}TLRPC.UserFull getCurrentUserInfo(){return null;}}
            static final class ChatObject {static boolean canSendVoice(TLRPC.Chat value){return true;}static boolean canSendRoundVideo(TLRPC.Chat value){return true;}}
            static final class SlowButton extends View {boolean isPremiumMode;}
            Edit messageEditText=new Edit();Message editingMessageObject;Parent parentFragment;TLRPC.UserFull userInfo;
            View attachLayout=new View(),audioVideoButtonContainer=new View(),audioVideoSendButton=new View(),sendButton=new View(),cancelBotButton=new View();
            AttachButton attachButton=new AttachButton();View expandStickersButton,scheduledButton,notifyButton,botButton,doneButton,recordedAudioPanel;
            SlowButton slowModeButton=new SlowButton();ChatActivitySideControlsButtonsLayout sideButtons;Delegate delegate=new Delegate();Pan adjustPanLayoutHelper;
            Toggle animatorIsBlockedByStreaming=new Toggle();AnimatorSet runningAnimation,runningAnimation2;ViewPropertyAnimator attachButtonAnimator;
            int runningAnimationType,slowModeTimer,sendButtonBackgroundColor,lastAttachVisible,innerTextChange;float attachButtonAlpha=1,attachLayoutAlpha=1,attachLayoutPaddingAlpha=1;
            boolean recordingAudioVideo,isPaused,forceShowSendButton,richDraftActive,isLiveComment,isStories,suggestButtonVisible,scheduleButtonHidden;
            boolean ignoreTextChange,ignorePrevTextChange;
            Object audioToSend,videoToSendMessageObject;
            void updateSendButtonPaid(){}boolean isSlowModeIgnored(){return false;}int getStarsPrice(){return 0;}int getThemedColor(int value){return value;}
            View getSendButtonInternal(){return sendButton;}int getVisibility(){return VISIBLE;}static int dp(int value){return value;}
            Animator animateSendButton(boolean visible){return ObjectAnimator.ofFloat(sendButton,View.ALPHA,visible?1:0);}
            Animator animateScheduledTranslationX(int value){return new Animator();}void createScheduledButton(){}void updateAttachLayoutParams(){}
            AttachmentComposeProbe(){sendButton.visibility=GONE;cancelBotButton.visibility=GONE;slowModeButton.visibility=GONE;messageEditText.changed=this::notifyTextChanged;setupAttachClick();}
            static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
        """;

    private static final String SCENARIOS = """
            static void verify(AttachmentComposeProbe probe,boolean visible,String label){
                check(probe.attachButton.alpha==(visible?1f:0f),label+": paperclip alpha");
                check(probe.attachButton.scaleX==(visible?1f:.5f)&&probe.attachButton.scaleY==(visible?1f:.5f),label+": paperclip scale");
                check(probe.messageEditText.layout.rightMargin==(visible?50:2),label+": original field margin");
                int before=probe.delegate.attaches;probe.attachButton.tap();
                check(probe.delegate.attaches==before+(visible?1:0),label+": original paperclip click");
                check(probe.audioVideoButtonContainer.visibility==GONE,label+": recording remains hidden");
                check(probe.sendButton.visibility==VISIBLE,label+": send arrow retained");
            }
            static void transitions(boolean animated){
                CodexRuntime.active=true;AttachmentComposeProbe probe=new AttachmentComposeProbe();
                probe.checkSendButton(animated);verify(probe,true,"initial empty "+animated);
                probe.messageEditText.text="synthetic input";probe.checkSendButton(animated);verify(probe,false,"already-send text "+animated);
                probe.setFieldText("");verify(probe,true,"original setFieldText cleared "+animated);
                probe.messageEditText.text="attachment caption";probe.checkSendButton(animated);verify(probe,false,"attachment caption "+animated);
                probe.setFieldText("");verify(probe,true,"attachment send cleared editor "+animated);
                probe.messageEditText.text="  ";probe.checkSendButton(animated);verify(probe,true,"trimmed empty "+animated);
                probe.richDraftActive=true;probe.checkSendButton(animated);verify(probe,false,"rich draft "+animated);
                probe.richDraftActive=false;probe.checkSendButton(animated);verify(probe,true,"rich draft cleared "+animated);
                probe.audioToSend=new Object();probe.checkSendButton(animated);verify(probe,false,"audio pending "+animated);
                probe.audioToSend=null;probe.videoToSendMessageObject=new Object();probe.checkSendButton(animated);verify(probe,false,"video pending "+animated);
                probe.videoToSendMessageObject=null;probe.checkSendButton(animated);verify(probe,true,"media cleared "+animated);
                probe.forceShowSendButton=true;probe.checkSendButton(animated);verify(probe,false,"forced send "+animated);
                probe.forceShowSendButton=false;probe.checkSendButton(animated);verify(probe,true,"forced send cleared "+animated);
                probe.slowModeTimer=Integer.MAX_VALUE;probe.checkSendButton(animated);verify(probe,false,"slow mode send "+animated);
                probe.slowModeTimer=0;probe.animatorIsBlockedByStreaming.value=true;probe.checkSendButton(animated);verify(probe,false,"streaming blocked "+animated);
                probe.animatorIsBlockedByStreaming.value=false;LocaleController.isRTL=true;probe.checkSendButton(animated);verify(probe,true,"RTL empty "+animated);
                LocaleController.isRTL=false;
            }
            static void telegram(boolean animated){
                CodexRuntime.active=false;AttachmentComposeProbe probe=new AttachmentComposeProbe();
                probe.messageEditText.text="synthetic input";probe.checkSendButton(animated);verify(probe,false,"Telegram text "+animated);
                probe.messageEditText.text="";probe.checkSendButton(animated);
                check(probe.attachButton.alpha==1&&probe.messageEditText.layout.rightMargin==50,"Telegram empty paperclip "+animated);
                check(probe.audioVideoButtonContainer.visibility==VISIBLE&&probe.sendButton.alpha==0,"Telegram original recording "+animated);
                for(boolean near:new boolean[]{false,true}){
                    probe=new AttachmentComposeProbe();probe.sideButtons=new ChatActivitySideControlsButtonsLayout();probe.messageEditText.text="synthetic input";probe.messageEditText.nearRight=near;
                    probe.checkSendButton(animated);check(probe.sideButtons.attachVisible==near&&probe.attachButton.alpha==(near?0:1),"original side controls "+animated+" "+near);
                }
            }
            static void duringSendAnimation(boolean startsEmpty,boolean paused){
                CodexRuntime.active=true;AttachmentComposeProbe probe=new AttachmentComposeProbe();AnimatorSet.defer=true;
                probe.messageEditText.text=startsEmpty?"":"synthetic input";probe.checkSendButton(true);
                probe.isPaused=paused;
                probe.setFieldText(startsEmpty?"synthetic input":"");
                AnimatorSet.finishPending();AnimatorSet.defer=false;
                verify(probe,!startsEmpty,"input changed during initial animation "+startsEmpty+" paused "+paused);
            }
            public static void main(String[] args){
                transitions(false);transitions(true);telegram(false);telegram(true);
                duringSendAnimation(true,false);duringSendAnimation(false,false);
                duringSendAnimation(true,true);duringSendAnimation(false,true);
                CodexRuntime.active=true;AttachmentComposeProbe probe=new AttachmentComposeProbe();probe.messageEditText.caption="synthetic bot caption";probe.checkSendButton(false);
                check(probe.attachButton.alpha==0&&probe.cancelBotButton.visibility==VISIBLE,"original caption button");
                System.out.println("AttachmentComposeUi PASS: real send/empty branches, animated/direct transitions, original paperclip click/margin, no Codex recording and unchanged Telegram/side controls");
            }
        """;
}
