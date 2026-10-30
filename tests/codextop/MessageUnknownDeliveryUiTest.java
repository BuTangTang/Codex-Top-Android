import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 从实际 Java 方法和原绑定／通知语句提取行为；Android 字体与真实绘制留平台验收。 */
public final class MessageUnknownDeliveryUiTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath();
        Path before = args.length > 1 && !args[1].startsWith("--") ? Paths.get(args[1]) : null;
        String runtime = read(root, before, "CodexRuntime.java", "com/butang/codextop/");
        String cell = read(root, before, "ChatMessageCell.java", "org/telegram/ui/Cells/");
        String activity = read(root, before, "ChatActivity.java", "org/telegram/ui/");
        String generated = fixture(runtime, cell, activity);
        for (String arg : args) {
            if (arg.equals("--mutant=accepted")) {
                generated = generated.replace("&& !\"true\".equals(message.messageOwner.params.get(\"codexSendAccepted\"))", "");
            } else if (arg.equals("--mutant=notify")) {
                generated = generated.replace("cell.refreshCodexDeliveryStatus();", "");
            } else if (arg.equals("--mutant=cache")) {
                generated = generated.replace("lastCodexDeliveryUncertain != CodexRuntime.isMessageDeliveryUncertain(currentMessageObject)", "false");
            } else if (arg.equals("--mutant=time")) {
                generated = generated.replace("currentTimeString = \"无法确认送达\";", "currentTimeString = timeString;");
            } else if (arg.equals("--mutant=a11y")) {
                generated = generated.replace("accessibilityTextCodexDeliveryUncertain != deliveryUncertain", "false");
            } else if (arg.equals("--mutant=upload-a11y")) {
                generated = generated.replace("!deliveryUncertain && buttonState == 1", "buttonState == 1");
            }
        }
        Path temporary = Files.createTempDirectory("codex-unknown-ui-methods-");
        try {
            Path source = temporary.resolve("UnknownFixture.java");
            Files.write(source, generated.getBytes(StandardCharsets.UTF_8));
            int compilation = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "8", "-encoding", "UTF-8", "-d", temporary.toString(), source.toString());
            if (compilation != 0) throw new AssertionError("真实方法 Java 8 编译失败：" + source);
            Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx64m", "-cp", temporary.toString(), "UnknownFixture").inheritIO().start();
            if (process.waitFor() != 0) throw new AssertionError("真实方法行为未通过");
        } finally {
            try (java.util.stream.Stream<Path> entries = Files.walk(temporary)) {
                entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (IOException error) { throw new RuntimeException(error); }
                });
            }
        }
    }

    private static String read(Path root, Path before, String name, String directory) throws IOException {
        Path file = before == null ? root.resolve("TMessagesProj/src/main/java/" + directory + name) : before.resolve(name);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 扫描真实语法边界，忽略注释和字符串中的括号；不以源码字符串作为通过断言。 */
    private static int close(String source, int opening, char left, char right) {
        int depth = 0;
        boolean quoted = false, character = false, line = false, block = false, escape = false;
        for (int i = opening; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (quoted || character) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (quoted && c == '"') quoted = false;
                else if (character && c == '\'') character = false;
                continue;
            }
            if (c == '/' && next == '/') { line = true; i++; continue; }
            if (c == '/' && next == '*') { block = true; i++; continue; }
            if (c == '"') { quoted = true; continue; }
            if (c == '\'') { character = true; continue; }
            if (c == left) depth++;
            else if (c == right && --depth == 0) return i;
        }
        throw new IllegalArgumentException("未找到真实语法闭合边界");
    }

    private static String method(String source, String declaration) {
        int start = source.indexOf(declaration);
        if (start < 0) throw new IllegalArgumentException("找不到原方法：" + declaration);
        int opening = source.indexOf('{', start);
        return source.substring(start, close(source, opening, '{', '}') + 1);
    }

    private static String optionalMethod(String source, String declaration, String oldBehavior) {
        return source.contains(declaration) ? method(source, declaration) : oldBehavior;
    }

    private static String statement(String source, String beginning) {
        int start = source.indexOf(beginning);
        if (start < 0) throw new IllegalArgumentException("找不到原语句：" + beginning);
        return source.substring(start, source.indexOf(';', start) + 1);
    }

    private static String block(String source, String beginning) {
        int start = source.indexOf(beginning);
        if (start < 0) throw new IllegalArgumentException("找不到原语句块：" + beginning);
        int opening = source.indexOf('{', start);
        return source.substring(start, close(source, opening, '{', '}') + 1);
    }

    private static String condition(String source, String beginning) {
        int start = source.indexOf(beginning);
        int opening = source.indexOf('(', start);
        return source.substring(opening + 1, close(source, opening, '(', ')'));
    }

    private static String fixture(String runtime, String cell, String activity) {
        String bind = method(cell, "private void setMessageContent(");
        String accessibility = cell.substring(cell.indexOf("public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId)"));
        int out = accessibility.indexOf("if (currentMessageObject.isOut()) {");
        int outEnd = close(accessibility, accessibility.indexOf('{', out), '{', '}');
        int elseStart = accessibility.indexOf("else {", outEnd);
        String outgoingDescription = accessibility.substring(out, close(accessibility, accessibility.indexOf('{', elseStart), '{', '}') + 1);
        String cacheAssignments = statement(accessibility, "accessibilityText = sb;") + statement(accessibility, "accessibilityTextUnread = unread;")
                + statement(accessibility, "accessibilityTextContentUnread = contentUnread;") + statement(accessibility, "accessibilityTextFileSize = fileSize;");
        if (accessibility.contains("accessibilityTextCodexDeliveryUncertain = deliveryUncertain;")) {
            cacheAssignments += statement(accessibility, "accessibilityTextCodexDeliveryUncertain = deliveryUncertain;");
        }
        String cacheUnknownDeclaration = accessibility.contains("final boolean deliveryUncertain =")
                ? statement(accessibility, "final boolean deliveryUncertain =") : "final boolean deliveryUncertain = false;";
        String layout = method(cell, "protected void onLayout(");
        layout = layout.substring(layout.indexOf('{') + 1, layout.indexOf("if (currentMessageObject.type == MessageObject.TYPE_TEXT"));
        String statusConsumption = block(cell, "if (currentMessageObject.isOutOwner()) {\n            int currentStatus = transitionParams.createStatusDrawableParams();");
        String refresh = optionalMethod(cell, "public void refreshCodexDeliveryStatus()", "public void refreshCodexDeliveryStatus() {} ");
        String query = optionalMethod(runtime, "public static boolean isMessageDeliveryUncertain(",
                "public static boolean isMessageDeliveryUncertain(MessageObject message) { return false; }");
        String bindCache = statement(bind, "lastSendState = messageObject.messageOwner.send_state;");
        if (bind.contains("lastCodexDeliveryUncertain =")) bindCache += statement(bind, "lastCodexDeliveryUncertain =");
        String actualSet = method(cell, "public void setMessageObject(MessageObject messageObject,\n                                 MessageObject.GroupedMessages groupedMessages,\n                                 boolean bottomNear,\n                                 boolean topNear,\n                                 boolean firstInChat,\n                                 boolean lastInChatList)");
        String generated = "import java.util.*;\n" + SUPPORT + "\nclass CodexRuntime {\n" + query
                + "\nstatic boolean ownsConversation(long id){return id == 77;} static boolean isAttachmentMessage(MessageObject m){return m != null && m.attachment;} }\n"
                + "class ChatMessageCell extends View {\n" + CELL_BOUNDARY
                + method(cell, "private boolean isUserDataChanged()")
                + method(cell, "private void measureTime(")
                + method(cell, "private void calcBackgroundWidth(")
                + actualSet + refresh
                + method(cell, "private void createStatusDrawableAnimator(")
                + "class TransitionParams { boolean messageEntering; int lastStatusDrawableParams = -1;\n"
                + method(cell, "public int createStatusDrawableParams()") + "}\n"
                + "void setMessageContent(MessageObject messageObject, MessageObject.GroupedMessages groupedMessages, boolean bottomNear, boolean topNear, boolean firstInChat, boolean lastInChatList) {\n"
                + "boolean photoNotSet=false, wasPinned=false; TLRPC.Message newReply=null; boolean isPinned=false;\n"
                + statement(bind, "boolean dataChanged = currentMessageObject")
                + statement(bind, "accessibilityText = null;")
                + "if (currentMessageObject != messageObject || dataChanged) { bindCount++; currentMessageObject=messageObject; currentMessagesGroup=groupedMessages; this.pinnedBottom=bottomNear; this.pinnedTop=topNear; this.firstInChat=firstInChat; this.lastInChatList=lastInChatList;\n"
                + bindCache + statement(bind, "wasLayout = false;")
                + "measureTime(messageObject); } }\n"
                + "void layout() {\n" + layout + "}\n"
                + "String accessible() { final boolean unread=currentMessageObject!=null && currentMessageObject.isOut() && !currentMessageObject.scheduled && currentMessageObject.isUnread(); final boolean contentUnread=currentMessageObject!=null && currentMessageObject.isContentUnread(); final long fileSize=currentMessageObject!=null ? currentMessageObject.loadedFileSize : 0; "
                + cacheUnknownDeclaration + "if (" + condition(accessibility, "if (accessibilityText == null ||") + ") { a11yCount++; SpannableStringBuilder sb = new SpannableStringBuilder();\n"
                + block(accessibility, "if (documentAttach != null && (documentAttachType == DOCUMENT_ATTACH_TYPE_DOCUMENT")
                + outgoingDescription + cacheAssignments + "} return accessibilityText.toString(); }\n"
                + "void drawStatusFrame() { Canvas canvas = new Canvas(); float alpha=1, timeYOffset=0, layoutHeight=100; boolean fromParent=false,bigRadius=false,drawSelectionBackground=false;\n"
                + statusConsumption + "}\n"
                + "int photoWidth(int w) { MessageObject messageObject=currentMessageObject;\n"
                + cell.substring(cell.indexOf("int timeWidthTotal = timeWidth + dp((SharedConfig.bubbleRadius >= 10 ? 22 : 18) + (messageObject.isOutOwner() ? 20 : 0));"),
                         cell.indexOf("if (messageObject.isRoundVideo()) {", cell.indexOf("int timeWidthTotal = timeWidth + dp((SharedConfig.bubbleRadius >= 10 ? 22 : 18) + (messageObject.isOutOwner() ? 20 : 0));")))
                + "return w; }\n"
                + "void textWidth(int maxWidth, int maxChildWidth) {MessageObject messageObject=currentMessageObject;\n"
                + cell.substring(cell.indexOf("int timeMore = timeWidth + dp(6);"), cell.indexOf("hasGamePreview =", cell.indexOf("int timeMore = timeWidth + dp(6);")))
                + "calcBackgroundWidth(maxWidth,timeMore,maxChildWidth); }\n}\n"
                + "class Activity {long dialog_id=77; ChildList chatListView=new ChildList(); void notifyState(int updateMask) {\n"
                + block(activity, "if ((updateMask & MessagesController.UPDATE_MASK_SEND_STATE) != 0") + "} }\n"
                + CHECKS;
        return generated.replace("com.butang.codextop.CodexRuntime", "CodexRuntime");
    }

    private static final String SUPPORT = """
        class TLRPC {
          static final int MESSAGE_FLAG_EDITED=32768, MESSAGE_FLAG_HAS_VIEWS=1024;
          static class Message { Map<String,String> params=new HashMap<>(); int send_state=1,date=1,edit_date,flags,views,schedule_repeat_period; long via_business_bot_id,destroyTime; boolean post,edit_hide,video_processing_pending; String post_author; Forward fwd_from; Peer peer_id=new Peer(); Media media; Object reactions; }
          static class Peer {long channel_id;}
          static class Forward {String post_author; boolean imported; int date,saved_date,channel_post;}
          static class FileLocation {int local_id; long volume_id;}
          static class PhotoSize {}
          static class Document {}
          static class ProfilePhoto {FileLocation photo_small;}
          static class User {String first_name="",last_name=""; ProfilePhoto photo;}
          static class Chat {boolean signature_profiles; ProfilePhoto photo;}
          static class Media {Object webpage; long total_amount; String currency; List<Object> extended_media=new ArrayList<>();}
          static class TL_webPage {}
          static class TL_messageExtendedMediaPreview {int video_duration;}
          static class TL_availableEffect {long id,static_icon_id; int flags; String emoticon;}
        }
        class MessageObject {
          static final int TYPE_TEXT=0,TYPE_PHOTO=1,TYPE_VIDEO=3,TYPE_ROUND_VIDEO=5,TYPE_STICKER=13,TYPE_EXTENDED_MEDIA_PREVIEW=20, MESSAGE_SEND_STATE_EDITING=3;
          TLRPC.Message messageOwner=new TLRPC.Message(); int currentAccount,type,realDate,stableId=1,lastLineWidth=80; long loadedFileSize;
          boolean scheduled,notime,isRepostPreview,isSaved,translated,hasRtl,hasCodeAtBottom,hasQuoteAtBottom,attachment,quickReply,sponsored,welcome,preview,sendPreview;
          MessageObject replyMessageObject; Object photoThumbs; RichLayout richLayout;
          boolean isOutOwner(){return true;} boolean isOut(){return true;} boolean isSent(){return messageOwner.send_state==0;} boolean isSending(){return messageOwner.send_state==1;} boolean isSendError(){return messageOwner.send_state==2;} boolean isEditing(){return messageOwner.send_state==3;}
          boolean isUnread(){return true;} boolean isContentUnread(){return false;} boolean isFromUser(){return false;} int getLastLineWidth(){return lastLineWidth;} boolean isAnyKindOfSticker(){return type==TYPE_STICKER;} boolean needDrawForwarded(){return false;} String getForwardedName(){return "";}
          boolean isWelcomeMessage(){return welcome;} boolean isSponsored(){return sponsored;} boolean isQuickReply(){return quickReply;} boolean isStakedDice(){return false;} long getStakedDiceAmount(){return 0;} boolean hasReplies(){return false;} boolean isSupergroup(){return false;} boolean shouldDrawReactions(){return false;} boolean isUnsupported(){return false;} boolean isGiveawayOrGiveawayResults(){return false;}
          long getFromChatId(){return 0;} long getDialogId(){return 77;} int getId(){return stableId;} static TLRPC.Media getMedia(TLRPC.Message m){return m.media;}
          static class GroupedMessages {List<MessageObject> messages=new ArrayList<>(); int getMaxEditDate(){return 0;}}
          static class GroupedMessagePosition {int leftSpanOffset;}
        }
        class RichLayout {boolean forceNewLineForTime(){return false;}}
        class MessagesController {static final int UPDATE_MASK_SEND_STATE=8; static MessagesController getInstance(int i){return new MessagesController();} TLRPC.User getUser(long id){return null;} TLRPC.Chat getChat(long id){return null;} }
        class FileLoader {static TLRPC.PhotoSize getClosestPhotoSizeWithSize(Object sizes,int size){return null;}}
        class UserObject {static String getUserName(TLRPC.User u){return "bot";}}
        class ContactsController {static String formatName(String a,String b){return a+" "+b;}}
        class Drawable {int getIntrinsicWidth(){return 10;} int getIntrinsicHeight(){return 10;}}
        class Paint {float size=12; float measureText(CharSequence s,int a,int b){return (b-a)*size*.75f;} float measureText(CharSequence s){return measureText(s,0,s.length());} float getTextSize(){return size;} Object getFontMetricsInt(){return null;}}
        class Theme {static Paint chat_timePaint=new Paint(),chat_unlockExtendedMediaTextPaint=new Paint(),chat_durationPaint=new Paint(); static Drawable chat_msgInViewsDrawable=new Drawable(),chat_msgInRepliesDrawable=new Drawable(),chat_msgInPinnedDrawable=new Drawable();}
        class SharedConfig {static int bubbleRadius=12;}
        class LocaleController {
          static LocaleController getInstance(){return new LocaleController();} Formatter getFormatterDay(){return new Formatter();} static String formatImportedDate(int d){return "imported";} static String formatSmallDateChat(int d){return "day";} static String formatPmEditedDate(int d){return "edited";} static String formatSeenDate(int d){return "seen";} static String formatShortNumber(int n,Object o){return ""+n;} String formatCurrencyString(long n,String currency){return "pay";}
          static class Formatter {String format(long date){return "12:00";}}
        }
        class AppGlobalConfig {static AppGlobalConfig getInstance(int a){return new AppGlobalConfig();} Flag messagePrimaryEditedDate=new Flag(); static class Flag {boolean get(){return false;}}}
        class StarsIntroActivity {static String formatTON(long n){return "0";} static CharSequence replaceDiamond(CharSequence s,float f,Object o,int i,int j,float g){return s;} static CharSequence replaceStars(CharSequence s,float f,Object o,int i,int j,float g){return s;}}
        class TextUtils {enum TruncateAt {END} static boolean isEmpty(CharSequence s){return s==null||s.length()==0;} static CharSequence concat(CharSequence... entries){StringBuilder b=new StringBuilder(); for(CharSequence e:entries)b.append(e); return b;} static CharSequence ellipsize(CharSequence s,Paint p,int n,TruncateAt t){return s;} }
        class SpannableStringBuilder implements CharSequence {StringBuilder b=new StringBuilder(); SpannableStringBuilder append(CharSequence s){b.append(s);return this;} SpannableStringBuilder append(char c){b.append(c);return this;} public int length(){return b.length();} public char charAt(int i){return b.charAt(i);} public CharSequence subSequence(int a,int c){return b.subSequence(a,c);} public String toString(){return b.toString();}}
        class AndroidUtilities {static String formatWholeNumber(int n,int d){return ""+n;} static String formatDuration(int d,boolean b){return "00:00";} static String formatFileSize(long bytes){return ""+bytes;}}
        class Gravity {static int LEFT=0;}
        class ChatActivity {static final int MODE_WELCOME_MESSAGES=1,SIDE_MENU_WIDTH=50;}
        class AnimatedEmojiDrawable {static final int CACHE_TYPE_MESSAGE_EFFECT_MINI=1; static class SwapAnimatedEmojiDrawable {SwapAnimatedEmojiDrawable(Object v,boolean b,int d,int c){} void set(Object d,boolean b){} void set(long d,boolean b){}}}
        class Emoji {static Object getEmojiDrawable(String s){return null;} static CharSequence replaceEmoji(CharSequence s,Object metrics,boolean b){return s;}}
        class R {static class string {static int ImportedMessage=1,EditedMessage=2,ScheduledTimeApprox=3,MessageScheduledRepeatYearly=4,MessageScheduledRepeatMonthly=5,MessageScheduledRepeatBiweekly=6,MessageScheduledRepeatWeekly=7,MessageScheduledRepeatDaily=8,MessageScheduledRepeatSeconds=9,PaymentCheckoutPay=10,AccDescrScheduledDate=11,AccDescrSentDate=12,TodayAt=13,AccDescrMsgUnread=14,AccDescrMsgRead=15,AccDescrMsgSending=16,AccDescrMsgSendingError=17,AccDescrReceivedDate=18,AccDescrUploadProgress=19,AccDescrDownloadProgress=20;}}
        class ReactionLayout {boolean isEmpty=true,isSmall; int width,lastLineX; void measure(int w,int gravity){} }
        class Delegate {int getChatMode(){return 0;} boolean canDrawOutboundsContent(){return false;}}
        class Radial {float getProgress(){return .42f;}}
        class Layout {enum Alignment {ALIGN_NORMAL,ALIGN_CENTER}}
        class StaticLayout {CharSequence text; int width; StaticLayout(CharSequence s,Paint p,int w,Layout.Alignment a,float f,float g,boolean b){text=s;width=w;}}
        class AvatarImage {float getImageY(){return 0;} void setImageCoords(int x,float y,int w,int h){} }
        class Canvas {void save(){} void restore(){} void translate(float x,float y){} }
        class View {int invalidations; void invalidate(){invalidations++;} Object getParent(){return null;} }
        class Animator {}
        class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){} }
        class CubicBezierInterpolator {static Object DEFAULT=new Object();}
        class ValueAnimator extends Animator {
          interface Update {void accept(ValueAnimator a);} float value; long duration; Update update; AnimatorListenerAdapter listener;
          static ValueAnimator ofFloat(float a,float b){return new ValueAnimator();} void setDuration(long d){duration=d;} void setInterpolator(Object o){} void addUpdateListener(Update u){update=u;} void addListener(AnimatorListenerAdapter l){listener=l;} Object getAnimatedValue(){return value;} void start(){} void finish(){value=1; update.accept(this);listener.onAnimationEnd(this);}
        }
        class ChildList {List<View> children=new ArrayList<>(); int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}}
        """;

    private static final String CELL_BOUNDARY = """
        MessageObject currentMessageObject, messageObjectToSet;
        MessageObject.GroupedMessages currentMessagesGroup,groupedMessagesToSet;
        MessageObject.GroupedMessagePosition currentPosition;
        boolean hasLinkPreview,isAvatarVisible,isThreadChat,drawForwardedName,isMegagroup,isChat,isMonoForum,isPinned,edited,hasInvoicePreview,linkPreviewAbove,hasFactCheck,hasOldCaptionPreview,hasGamePreview,hasNewLineForTime,mediaBackground,pinnedBottom,pinnedTop,firstInChat,lastInChatList,attachedToWindow=true,frozen;
        boolean bottomNearToSet,topNearToSet,firstInChatToSet,lastInChatListToSet,lastTranslated,wasLayout,forcedLayout;
        TLRPC.User currentUser; TLRPC.Chat currentChat;
        int currentAccount,lastSendState=1,lastViewsCount,lastRepliesCount,timeTextWidth,timeWidth,availableTimeWidth,viewsTextWidth,repliesTextWidth,unlockTextWidth,signWidth,totalHeight,backgroundWidth=100,layoutWidth=320,layoutHeight,lastSize,substractBackgroundHeight,additionalPaddingHeight,documentAttachType,effectMessageId,durationWidth,bindCount,layoutRequests,a11yCount,attachmentButtonUpdates;
        long lastDeleteDate,effectId; Object lastReactions,replyTextLayout,replyNameLayout,lastReplyMessage; String currentNameString,currentForwardNameString,lastPostAuthor; TLRPC.FileLocation currentPhoto; TLRPC.PhotoSize currentReplyPhoto;
        CharSequence currentTimeString; String currentViewsString,currentRepliesString,currentUnlockString;
        boolean lastCodexDeliveryUncertain,accessibilityTextUnread,accessibilityTextContentUnread,accessibilityTextCodexDeliveryUncertain;
        long accessibilityTextFileSize; CharSequence accessibilityText;
        ReactionLayout reactionsLayoutInBubble=new ReactionLayout(); Delegate delegate; AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable effectDrawable;
        StaticLayout timeLayout,viewsLayout,repliesLayout,unlockLayout,videoInfoLayout,captionLayout,loadingProgressLayout;
        TLRPC.Document documentAttach; int buttonState; long lastLoadingSizeTotal;
        AvatarImage avatarImage=new AvatarImage(); float timeX;
        static final int DOCUMENT_ATTACH_TYPE_DOCUMENT=1,DOCUMENT_ATTACH_TYPE_GIF=2,DOCUMENT_ATTACH_TYPE_VIDEO=4,DOCUMENT_ATTACH_TYPE_ROUND=5,DOCUMENT_ATTACH_TYPE_STICKER=6;
        ValueAnimator statusDrawableAnimator; TransitionParams transitionParams=new TransitionParams(); boolean statusDrawableAnimationInProgress; int animateFromStatusDrawableParams,animateToStatusDrawableParams,renderedStatusBits; float statusDrawableProgress,transitionYOffsetForDrawables; Radial radialProgress=new Radial();
        MessageObject getPrimaryMessageObject(){return currentMessageObject;} long getStarsPrice(){return 0;} TLRPC.TL_availableEffect getEffect(){return null;} int getRepliesCount(){return 0;}
        int dp(float v){return (int)Math.ceil(v);} int getExtraTextX(){return 0;} int getExtraTimeX(){return 0;}
        boolean isNeedAuthorName(){return false;} String getAuthorName(){return null;} void updateCurrentUserAndChat(){}
        boolean isSideMenuLeftMargin(){return false;} boolean isWidthAdaptive(){return false;} int getGroupPhotosWidth(){return 320;} boolean shouldDrawTimeOnMedia(){return mediaBackground;}
        int getMeasuredWidth(){return 320;} int getMeasuredHeight(){return 200;} int getPaddingTop(){return 0;} int getPaddingBottom(){return 0;}
        void requestLayout(){layoutRequests++;} boolean userDataChanged(){return isUserDataChanged();} MessageObject getMessageObject(){return currentMessageObject;} void updateButtonState(boolean a,boolean b,boolean c){attachmentButtonUpdates++;}
        String getString(int id){return "r"+id;} String getString(String key,int id){return key;} String formatString(int id,Object... args){return "r"+id+Arrays.toString(args);} String formatString(String key,int id,Object... args){return key+Arrays.toString(args);} String formatPluralString(String key,int n){return key+n;}
        void drawStatusDrawable(Canvas c,boolean check1,boolean check2,boolean clock,boolean error,float a,boolean b,float y,float h,float progress,boolean moved,boolean selected){if(progress>0) renderedStatusBits|=(check1?1:0)|(check2?2:0)|(clock?4:0)|(error?8:0);}
        """;

    private static final String CHECKS = """
        public class UnknownFixture {
          static int count;
          static void check(boolean result,String what){count++;if(!result)throw new AssertionError(what);}
          static MessageObject pending(int type){MessageObject m=new MessageObject();m.type=type;m.messageOwner.params.put("codexLocalId","synthetic-local-1");m.attachment=type!=0;return m;}
          static ChatMessageCell bind(MessageObject m){ChatMessageCell c=new ChatMessageCell();c.setMessageObject(m,null,true,false,true,true);c.layout();return c;}
          static void unknown(MessageObject m){m.messageOwner.params.put("codexSendUncertain","true");}
          public static void main(String[] args){
            // 第一个断言在旧源码上编译成功但文案行为 RED，避免以新方法缺失代替回归。
            MessageObject cold=pending(0);unknown(cold);ChatMessageCell c=bind(cold);
            check("无法确认送达".contentEquals(c.currentTimeString),"冷恢复 unknown 应沿原时间区域显示静态说明");
            check(c.timeWidth==54 && c.timeLayout.text.toString().equals("无法确认送达"),"说明必须先测量再进入原 StaticLayout");
            check(c.transitionParams.createStatusDrawableParams()==0,"unknown 不能显示 clock/check/error");
            check(c.accessible().equals("\\n无法确认送达"),"unknown 读屏不附发送百分比");
            check(!CodexRuntime.isMessageDeliveryUncertain(null),"空消息安全");
            MessageObject normal=new MessageObject();unknown(normal);check(!CodexRuntime.isMessageDeliveryUncertain(normal),"普通 Telegram 缺 Codex 身份保持原行为");
            normal.messageOwner.params=null;check(!CodexRuntime.isMessageDeliveryUncertain(normal),"空参数安全");
            cold.messageOwner.params.put("codexSendAccepted","true");check(!CodexRuntime.isMessageDeliveryUncertain(cold),"accepted 参数优先于残留 uncertain");
            for(int type:new int[]{0,1,3,5,13}){
              Theme.chat_timePaint.size=12;MessageObject m=pending(type);ChatMessageCell live=bind(m);Activity activity=new Activity();activity.chatListView.children.add(live);activity.chatListView.children.add(new View());
              check(live.transitionParams.createStatusDrawableParams()==4,"在途保持原 clock type="+type);
              check(live.accessible().contains("AccDescrMsgSending") && live.accessible().contains("42%"),"在途读屏保持 type="+type);
              String old=live.timeLayout.text.toString();int binds=live.bindCount,layoutRequests=live.layoutRequests;
              unknown(m);
              // 当前用户/聊天均为空、同一对象且 send_state 未变，是实际缓存门的关键边界。
              check(live.userDataChanged(),"原地 unknown 必须在空用户提前 return 前使绑定缓存失效 type="+type);
              check(live.accessible().equals("\\n无法确认送达"),"读屏先于可见刷新也应使缓存失效 type="+type);
              activity.notifyState(MessagesController.UPDATE_MASK_SEND_STATE);
              check(live.bindCount==binds+1 && live.layoutRequests==layoutRequests+1 && !live.wasLayout,"原通知定点重绑并使相同尺寸的布局失效 type="+type);
              check(live.lastInChatList && live.firstInChat && live.pinnedBottom && !live.pinnedTop,"实际六参绑定保留原参数 type="+type);
              live.layout();check(!old.equals(live.timeLayout.text.toString()) && live.timeLayout.text.toString().equals("无法确认送达"),"同尺寸布局重测静态文案 type="+type);
              check(live.lastSendState==1 && live.lastCodexDeliveryUncertain,"仅更新渲染快照，传输状态保持 type="+type);
              check(live.transitionParams.createStatusDrawableParams()==0,"未知状态图标互斥 type="+type);
              int unchangedBinds=live.bindCount;activity.notifyState(MessagesController.UPDATE_MASK_SEND_STATE);check(live.bindCount==unchangedBinds,"相同状态不反复重绑 type="+type);
              check(live.attachmentButtonUpdates==(type==0?0:2),"原附件按钮通知接线保持 type="+type);
              live.mediaBackground=type!=0;live.documentAttachType=type==13?6:type==5?5:0;live.wasLayout=false;live.layout();
              check(live.timeX>=0 && live.timeX+live.timeTextWidth<=320,"原文字/媒体时间锚在可见 Cell 内 type="+type);
              m.messageOwner.params.put("codexSendAccepted","true");m.messageOwner.send_state=0;activity.notifyState(MessagesController.UPDATE_MASK_SEND_STATE);live.layout();
              check(!live.lastCodexDeliveryUncertain && live.currentTimeString.toString().equals("12:00"),"accepted 恢复原时间 type="+type);
              check(live.transitionParams.createStatusDrawableParams()==2 && live.accessible().contains("AccDescrSentDate") && !live.accessible().contains("无法确认送达"),"accepted 恢复原成功图标与读屏 type="+type);
            }
            // 原文本宽度／媒体最小宽度使用同一 measured timeWidth，字体仅为合成边界桩。
            for(int font:new int[]{12,24}){Theme.chat_timePaint.size=font;
              MessageObject m=pending(0);unknown(m);ChatMessageCell text=bind(m);text.textWidth(100,80);
              check(text.hasNewLineForTime && text.backgroundWidth>=text.timeWidth+48,"文本按实际时间宽度扩泡／加行 font="+font);
              for(int type:new int[]{1,3,5,13}){m=pending(type);unknown(m);ChatMessageCell media=bind(m);media.mediaBackground=true;media.documentAttachType=type==13?6:type==5?5:0;media.wasLayout=false;media.layout();
                check(media.timeTextWidth==Math.ceil(6*font*.75f) && media.timeX>=0,"媒体时间使用相同测量而非固定图标宽度 type="+type+" font="+font);
                if(type==1||type==3)check(media.photoWidth(40)>=media.timeWidth+42,"单图/视频原最小宽度扩展 type="+type+" font="+font);
              }
            }
            Theme.chat_timePaint.size=12;
            // 缓存的附件按钮和文件大小不变，读屏可早于可见按钮刷新。
            MessageObject stale=pending(3);ChatMessageCell upload=bind(stale);upload.documentAttach=new TLRPC.Document();upload.documentAttachType=4;upload.buttonState=1;upload.loadingProgressLayout=upload.timeLayout;stale.loadedFileSize=100;upload.lastLoadingSizeTotal=200;
            check(upload.accessible().contains("AccDescrUploadProgress") && upload.accessible().contains("42%"),"在途附件保留原上传说明");
            unknown(stale);check(upload.accessible().equals("\\n无法确认送达"),"原地未知不复述旧上传说明或百分比");
            stale.messageOwner.params.put("codexSendAccepted","true");stale.messageOwner.send_state=0;
            check(upload.accessible().contains("AccDescrDownloadProgress") && !upload.accessible().contains("无法确认送达"),"accepted/helper false 恢复原媒体读屏分支");
            MessageObject failed=pending(0);unknown(failed);ChatMessageCell terminal=bind(failed);failed.messageOwner.params.remove("codexSendUncertain");failed.messageOwner.send_state=2;terminal.refreshCodexDeliveryStatus();terminal.layout();
            check(terminal.transitionParams.createStatusDrawableParams()==8 && terminal.currentTimeString.toString().equals("12:00") && terminal.accessible().contains("AccDescrMsgSendingError"),"unknown 退出至明确失败恢复原错误说明");
            MessageObject echoed=pending(0);unknown(echoed);terminal=bind(echoed);MessageObject authoritative=pending(0);authoritative.messageOwner.send_state=0;terminal.setMessageObject(authoritative,null,true,false,true,true);terminal.layout();
            check(!terminal.lastCodexDeliveryUncertain && terminal.timeLayout.text.toString().equals("12:00") && terminal.transitionParams.createStatusDrawableParams()==2,"同身份回显替换未知实体恢复原时间／成功");
            MessageObject m=pending(0);unknown(m);m.notime=true;c=bind(m);check(c.currentTimeString.length()==0,"原 notime 门保持");
            m=pending(0);unknown(m);m.quickReply=true;c=bind(m);check(c.currentTimeString.length()==0 && c.transitionParams.createStatusDrawableParams()==0,"原 quick reply 门保持");
            m=pending(0);unknown(m);m.sponsored=true;c=bind(m);check(c.currentTimeString.length()==0,"原 sponsored 门保持");
            m=pending(0);unknown(m);m.welcome=true;c=bind(m);check(c.currentTimeString.length()==0,"原 welcome 门保持");
            m=pending(0);unknown(m);m.scheduled=true;m.messageOwner.date=0x7FFFFFFE;c=bind(m);check(c.currentTimeString.length()==0,"原隐藏 scheduled time 门保持");
            m=pending(0);unknown(m);m.preview=true;c=bind(m);check(!c.currentTimeString.toString().equals("无法确认送达"),"原 preview 门保持");
            m=pending(0);unknown(m);m.isRepostPreview=true;c=bind(m);check(!c.currentTimeString.toString().equals("无法确认送达"),"原 repost preview 时间保持");
            m=pending(0);unknown(m);m.sendPreview=true;c=bind(m);check(!c.currentTimeString.toString().equals("无法确认送达"),"原 send preview 门保持");
            m=new MessageObject();c=bind(m);check(c.currentTimeString.toString().equals("12:00") && c.transitionParams.createStatusDrawableParams()==4,"普通 Telegram 在途保持");
            m.messageOwner.send_state=2;c.setMessageObject(m,null,true,false,true,true);check(c.transitionParams.createStatusDrawableParams()==8 && c.accessible().contains("AccDescrMsgSendingError"),"明确失败保持原错误图标与读屏");
            MessageObject echo=pending(0);echo.messageOwner.send_state=0;c.setMessageObject(echo,null,true,false,true,true);check(c.transitionParams.createStatusDrawableParams()==2 && !c.currentTimeString.toString().equals("无法确认送达"),"同身份回显的新消息恢复原实体显示");
            // 实际原动画方法和 outgoing 状态消费块：旧 clock 有限退场后绝不再循环。
            m=pending(0);c=bind(m);c.drawStatusFrame();unknown(m);c.refreshCodexDeliveryStatus();c.drawStatusFrame();
            check(c.statusDrawableAnimationInProgress && c.statusDrawableAnimator.duration==150 && c.animateFromStatusDrawableParams==4 && c.animateToStatusDrawableParams==0,"原 4→0 只复用有限 150ms 动画");
            c.statusDrawableAnimator.finish();int before=c.invalidations;c.renderedStatusBits=0;c.drawStatusFrame();c.drawStatusFrame();
            check(!c.statusDrawableAnimationInProgress && c.transitionParams.lastStatusDrawableParams==0 && c.renderedStatusBits==0 && c.invalidations==before,"退场后没有 clock／失败／成功或重复帧刷新");
            // 原通知的 ownsConversation 门、非发送掩码门及无关可见消息保持。
            m=pending(0);c=bind(m);unknown(m);Activity a=new Activity();a.chatListView.children.add(c);a.dialog_id=88;int original=c.bindCount;a.notifyState(8);check(c.bindCount==original,"其他会话不触发本片重绑");
            a.dialog_id=77;a.notifyState(0);check(c.bindCount==original,"非发送通知不触发重绑");
            ChatMessageCell other=bind(new MessageObject());a.chatListView.children.add(other);a.notifyState(8);check(other.bindCount==1,"普通可见 Telegram 消息不重绑");
            // frozen 原 setMessageObject 边界仍使用同一延后绑定槽，不新增状态 owner。
            m=pending(0);c=bind(m);c.frozen=true;unknown(m);c.refreshCodexDeliveryStatus();check(c.messageObjectToSet==m && c.lastInChatListToSet && c.bindCount==1,"原冻结延后绑定保留六参");
            System.out.println("GREEN: "+count+" actual Java 8 unknown delivery UI checks");
          }
        }
        """;
}
