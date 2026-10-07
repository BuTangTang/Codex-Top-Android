package com.butang.codextop;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.ToolProvider;
/** 实际搜索单元格方法的合成边界测试；Android 绘图和 Runtime 读取是有计数的叶桩。 */
public final class ProfileSearchCodexTest {
    /** 从指定源码树提取真实方法，编译运行，不存放生产源码副本。 */
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath();
        var cell=StaticJavaParser.parse(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Cells/ProfileSearchCell.java"));
        var adapter=StaticJavaParser.parse(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsSearchAdapter.java"));
        StringBuilder methods=new StringBuilder();
        Set<String> names=Set.of("setData","buildLayout","update","updateStatus","onLayout","setChecked","onInitializeAccessibilityNodeInfo","onTouchEvent","getDialogId");
        for(var m:cell.findAll(MethodDeclaration.class)) if(names.contains(m.getNameAsString())) {
            var copy=m.clone(); copy.getAnnotations().clear(); methods.append(copy.toString()).append('\n');
        }
        // 执行真实 onDraw 的头像、分隔线和付费角标门禁；其余 Canvas API 属于平台叶。
        var draw=cell.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("onDraw")).findFirst().orElseThrow();
        methods.append("void draw(Canvas canvas) {");
        for(var st:draw.getBody().orElseThrow().getStatements()) if(st.isIfStmt()) {
            String c=st.asIfStmt().getCondition().toString();
            if(c.equals("useSeparator")||c.contains("dontDrawAvatar")) methods.append(st);
            if(c.contains("lockT > 0")) methods.append("float lockT=1f; if(").append(c).append(") lockDraws++;");
        }
        methods.append("}");
        var bind=adapter.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("onBindViewHolder")&&m.toString().contains("savedMessages")).findFirst().orElseThrow();
        StringBuilder binding=new StringBuilder("void bind(boolean directory, TLRPC.User user) { TestCell cell=this; TLRPC.Chat chat=null; TLRPC.EncryptedChat encryptedChat=null; CharSequence name=user.first_name,username=null; boolean savedMessages=false; dialogsActivity.local=directory;");
        for(var st:bind.findAll(ExpressionStmt.class)) if(st.toString().startsWith("boolean codexResult =")||st.toString().startsWith("cell.setData(user != null ? user : chat,")) binding.append(st);
        binding.append("}");
        String body=(methods.toString()+binding).replace("com.butang.codextop.CodexRuntime","CodexRuntime");
        Path temp=Files.createTempDirectory("profile-search-codex-");
        try {
            Files.writeString(temp.resolve("TestCell.java"), PREFIX+body+CASES+"}\n"+LEAVES,StandardCharsets.UTF_8);
            int compile=ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-d",temp.toString(),temp.resolve("TestCell.java").toString());
            if(compile!=0) throw new AssertionError("actual-owner compile failed");
            try(var loader=new java.net.URLClassLoader(new java.net.URL[]{temp.toUri().toURL()})) {
                var type=Class.forName("TestCell",true,loader); type.getMethod("run",boolean.class).invoke(null,args.length>1&&args[1].equals("red"));
            }
        } finally {try(var walk=Files.walk(temp)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static final String PREFIX="""
import java.util.*;
public class TestCell extends Base {
 boolean codexDirectoryResult; int currentAccount; TLRPC.User user; TLRPC.Chat chat; TLRPC.EncryptedChat encryptedChat; ContactsController.Contact contact;
 CharSequence currentName,subLabel; boolean drawCount,savedMessages,showPremiumBlocked=true,allowBotOpenButton=true,premiumBlocked,openBot,customPaints,callCellStyle,rectangularAvatar,drawNameLock,drawCheck,drawPremium,allowEmojiStatus=true,useSeparator=true,dontDrawAvatar;
 long starsPriceBlocked,dialog_id; String lastName=""; int lastStatus,lastUnreadCount,nameLeft,statusLeft,nameTop,nameLockLeft,nameLockTop,nameWidth,countWidth,countLeft,actionLeft,countTop,sublabelOffsetX,sublabelOffsetY;
 TLRPC.FileLocation lastAvatar; Theme.ResourcesProvider resourcesProvider; Object ad; Text adText; Paint adBackgroundPaint; TextPaint namePaint,statusPaint; boolean[] isOnline; CanvasButton actionButton; StaticLayout nameLayout,statusLayout,countLayout,actionLayout;
 ImageReceiver avatarImage=new ImageReceiver(); AvatarDrawable avatarDrawable=new AvatarDrawable(); StoryParams avatarStoryParams=new StoryParams(); CheckBox2 checkBox=new CheckBox2(); Swap statusDrawable=new Swap(),botVerificationDrawable=new Swap(); PhotoBubbleClip bubbleClip;
 Consumer onOpenButtonClick; Sponsor onSponsoredOptionsClick; Rect openButtonRect=new Rect(),adBounds=new Rect(); Bounce openButtonBounce=new Bounce(),adBounce=new Bounce(); int lockDraws; DialogsActivity dialogsActivity=new DialogsActivity();
 void setOpenBotButton(boolean v){openBot=v;} static int dp(float v){return (int)v;} static String getString(int v){return "telegram";} static String getString(String x){return x;}
""";
    static final String CASES="""
 static int checks; static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static TLRPC.User user(long id){TLRPC.User u=new TLRPC.User();u.id=id;u.first_name="Task";u.last_name="";u.status=new TLRPC.Status();u.verified=true;u.bot_has_main_app=true;return u;}
 public static void run(boolean red){
  TestCell c=new TestCell(); TLRPC.User u=user(7); CodexRuntime.owned=true; CodexRuntime.label="上次：已完成 · 状态已过期";CodexRuntime.machine="Workstation";
  c.bind(true,u); c.onLayout(true,0,0,320,64);c.draw(new Canvas());
  check(c.avatarImage.requests==0,"local result must not request avatar");
  check(c.avatarImage.clears>0,"local clears old image/backup");check(c.avatarStoryParams.resets==1,"old story reset");check(StoriesUtilities.draws==0,"local no avatar/story draw");
  check(c.dialog_id==7,"original dialog id");check(c.nameLeft==16&&c.statusLeft==16,"LTR no avatar gutter");check(c.nameWidth==288,"full text width");
  check(c.statusLayout.getText().toString().equals("上次：已完成 · 状态已过期 · Workstation"),"real status/source");check(!c.drawCheck&&!c.drawPremium&&c.statusDrawable.empty&&c.botVerificationDrawable.empty,"no contact badges");check(!c.openBot&&!c.premiumBlocked,"no contact actions");
  check(c.lockDraws==0,"no stale premium animation");check(Canvas.x1==16,"divider shares text start");check(!c.onTouchEvent(new MotionEvent()),"touch falls to list");check(c.avatarStoryParams.touches==0,"story touch off");
  AccessibilityNodeInfo info=new AccessibilityNodeInfo();c.onInitializeAccessibilityNodeInfo(info);check(info.text.toString().contains(CodexRuntime.label)&&!info.text.toString().contains("last seen"),"a11y true subtitle");
  CodexRuntime.label="等待回复";c.update(MessagesController.UPDATE_MASK_STATUS);check(c.statusLayout.getText().toString().equals("等待回复 · Workstation"),"TL expires unchanged must refresh");
  CodexRuntime.machine="";c.update(MessagesController.UPDATE_MASK_STATUS);check(c.statusLayout.getText().toString().equals("等待回复"),"missing source omitted");
  UserConfig.selectedAccount=1;c.update(MessagesController.UPDATE_MASK_STATUS);check(c.statusLayout.getText().toString().equals("状态暂不可用"),"account change no borrowed fact");UserConfig.selectedAccount=0;
  CodexRuntime.owned=false;c.update(0);check(c.statusLayout.getText().toString().equals("状态暂不可用"),"ownership lost unknown");CodexRuntime.owned=true;
  for(boolean rtl:new boolean[]{false,true}){LocaleController.isRTL=rtl;c.bind(true,u);c.setChecked(true,true);c.onLayout(true,0,0,320,64);check(c.checkBox.checked,"selection preserved");check(c.nameWidth==256,"selection reserves own space");check(c.checkBox.x==(rtl?14:282)&&c.checkBox.y==20,"selection trailing vertically centered");check(c.nameLeft==(rtl?48:16),"selection does not overlap text");info=new AccessibilityNodeInfo();c.onInitializeAccessibilityNodeInfo(info);check(info.checked,"selection a11y");c.setChecked(false,true);check(c.nameWidth==288,"selection released");}
  LocaleController.isRTL=false;c.setData(u,null,"TG",null,true,false);c.onLayout(true,0,0,320,64);c.draw(new Canvas());check(!c.codexDirectoryResult,"six-arg reuse resets");check(c.avatarImage.requests>0&&StoriesUtilities.draws>0,"TG avatar restored");check(c.nameLeft==72&&c.checkBox.x==42&&c.checkBox.y==36,"TG layout restored");check(c.statusLayout.getText().toString().equals("last seen a long time ago"),"TG status untouched");check(c.onTouchEvent(new MotionEvent()),"TG story restored");
  int n=c.avatarImage.requests;c.bind(true,u);check(c.avatarImage.requests==n,"TG to local no new request");check(c.avatarImage.backup==null,"TG backup cleared");c.bind(false,u);check(!c.codexDirectoryResult&&c.avatarImage.requests>n,"special search keeps TG");
  UserConfig.selectedAccount=1;c.bind(true,u);check(!c.codexDirectoryResult,"wrong adapter account no local mode");UserConfig.selectedAccount=0;CodexRuntime.owned=false;c.bind(true,u);check(!c.codexDirectoryResult,"unowned user not local");
  CodexRuntime.owned=true;c.bind(true,u);c.setChecked(true,false);c.onLayout(true,0,0,320,64);int request=c.layoutRequests;c.setData(u,null,"TG",null,true,false);c.setChecked(true,false);check(c.layoutRequests>request,"same selected local to TG schedules layout");c.onLayout(false,0,0,320,64);check(c.checkBox.x==42,"TG checkbox reset after same-size layout");
  request=c.layoutRequests;c.bind(true,u);c.setChecked(true,false);check(c.layoutRequests>request,"same selected TG to local schedules layout");c.onLayout(false,0,0,320,64);check(c.checkBox.x==282,"local checkbox resets after same-size layout");
  ContactsController.Contact contact=new ContactsController.Contact();contact.user=u;contact.first_name="Contact";c.setData(contact,null,"Contact",null,false,false);check(c.actionButton!=null&&c.actionLayout!=null,"real contact action exists");c.bind(true,u);check(c.actionButton==null&&c.actionLayout==null,"contact action both cleared");
  TestCell unmeasured=new TestCell();unmeasured.width=unmeasured.height=0;unmeasured.bind(true,u);unmeasured.setChecked(true,true);check(unmeasured.nameLayout==null&&unmeasured.layoutRequests>0,"unmeasured selection only requests layout");
  System.out.println("ProfileSearchCodexTest PASS assertions="+checks);
 }
""";
    static final String LEAVES="""
class Base { int width=320,height=64; int getMeasuredWidth(){return width;} int getMeasuredHeight(){return height;} int getPaddingLeft(){return 0;} int getPaddingRight(){return 0;} int layoutRequests; void requestLayout(){layoutRequests++;} void postInvalidate(){} Object getParent(){return null;} boolean callOnClick(){return true;} void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo i){} boolean onTouchEvent(MotionEvent e){return false;} }
class CodexRuntime {static boolean owned=true;static String label="同步中",machine="PC";static boolean ownsConversation(long id){return owned;} static String statusText(long id){return label;} static ConversationInfo conversationInfo(long id){return new ConversationInfo();}static class ConversationInfo{String machineName=machine;} }
class DialogsActivity{boolean local;boolean isCodexDirectorySearch(){return local;}}
class TLRPC {static class User{long id;String first_name,last_name,phone;boolean bot,verified,bot_has_main_app;int bot_active_users;Status status;Photo photo;Object emoji_status;}static class Chat{long id,linked_monoforum_id;String title;boolean verified,monoforum,forum,megagroup,has_geo;int participants_count;Photo photo;Object emoji_status;}static class EncryptedChat{long id;}static class Status{int expires;}static class Photo{FileLocation photo_small;Drawable strippedBitmap;}static class FileLocation{long volume_id;int local_id;}static class Dialog{}}
class TL_account{static class RequirementToContact{}}
class ContactsController{static class Contact{String first_name,last_name;TLRPC.User user;}}
class DialogObject{static long makeEncryptedDialogId(long id){return id;}static boolean isPremiumBlocked(Object r){return r!=null;}static long getMessagesStarsPrice(Object r){return 0;}static long getEmojiStatusDocumentId(Object o){return 0;}static long getBotVerificationIcon(Object o){return 0;}}
class MessagesController{static final int UPDATE_MASK_AVATAR=1,UPDATE_MASK_CHAT_AVATAR=2,UPDATE_MASK_STATUS=4,UPDATE_MASK_EMOJI_STATUS=8,UPDATE_MASK_NAME=16,UPDATE_MASK_CHAT_NAME=32,UPDATE_MASK_READ_DIALOG_MESSAGE=64; static MessagesController instance=new MessagesController();static MessagesController getInstance(int a){return instance;}Map<Long,TLRPC.Dialog> dialogs_dict=new HashMap<>();TL_account.RequirementToContact isUserContactBlocked(long id){return new TL_account.RequirementToContact();}boolean isPremiumUser(TLRPC.User u){return true;}TLRPC.Chat getChat(long id){return null;}int getDialogUnreadCount(TLRPC.Dialog d){return 0;}static boolean isSupportUser(TLRPC.User u){return false;}}
class ImageReceiver{int requests,clears;Object backup;void setImage(Object...args){requests++;backup=args;}void setImageBitmap(Drawable d){clears++;backup=null;}void setRoundRadius(int r){}void setImageCoords(Rect r){}void draw(Canvas c){StoriesUtilities.draws++;}}
class AvatarDrawable extends Drawable{static final int AVATAR_TYPE_REPLIES=1,AVATAR_TYPE_SAVED=2;void setInfo(Object...x){}void setAvatarType(int i){}}
class ImageLocation{static int TYPE_SMALL=1,TYPE_STRIPPED=2;static Object getForUserOrChat(Object...x){return new Object();}}
class UserConfig{static int selectedAccount;static UserConfig getInstance(int a){return new UserConfig();}long getClientUserId(){return 999;}}
class UserObject{static long VERIFY=999;static boolean isReplyUser(TLRPC.User u){return false;}static boolean isService(long l){return false;}static String getUserName(TLRPC.User u){return u.first_name;}}
class ChatObject{static boolean isCommunity(TLRPC.Chat c){return false;}static TL_account.RequirementToContact getRequirementToContact(TLRPC.Chat c){return null;}static boolean isChannelAndNotMegaGroup(TLRPC.Chat c){return false;}static boolean isPublic(TLRPC.Chat c){return false;}static boolean isMonoForum(TLRPC.Chat c){return false;}}
class ForumUtilities{static void setMonoForumAvatar(Object...x){}static String getMonoForumTitle(Object c,TLRPC.Chat chat){return chat.title;}}
class AndroidUtilities{static int leftBaseline=72;static String removeRTL(String s){return s;}static String removeDiacritics(String s){return s;}static CharSequence replaceNewLines(CharSequence s){return s;}static String escape(String s){return s;}static Object bold(){return null;}}
class DrawableUtils{static int getCommunityCardDrawableRadius(int n){return n;}static void drawCommunityCardDrawable(Object...a){}}
class LocaleController{static boolean isRTL;static String formatPluralStringSpaced(String s,int n){return s;}static String formatPluralStringComma(String s,int n){return s;}static String formatUserStatus(int a,TLRPC.User u,boolean[] o){return "last seen a long time ago";}}
class Theme{static class ResourcesProvider{Paint getPaint(int i){return new Paint();}}static int key_chats_secretName,key_chats_name,key_chats_verifiedBackground,key_windowBackgroundWhiteGrayText3,key_windowBackgroundWhiteBlueText3,key_windowBackgroundWhiteGrayText,key_paint_divider;static Drawable dialogs_lockDrawable=new Drawable(),dialogs_verifiedDrawable=new Drawable(),dialogs_verifiedCheckDrawable=new Drawable(),dialogs_communityCardsDrawable=new Drawable();static TextPaint dialogs_searchNameEncryptedPaint=new TextPaint(),dialogs_searchNamePaint=new TextPaint(),dialogs_countTextPaint=new TextPaint(),dialogs_offlinePaint=new TextPaint(),dialogs_onlinePaint=new TextPaint();static Paint dividerPaint=new Paint();static int getColor(int k,Object r){return 0;}}
class Paint{static int ANTI_ALIAS_FLAG=1;Paint(){}Paint(int n){}}
class TextPaint extends Paint{TextPaint(){}TextPaint(int n){}void setTypeface(Object o){}void setTextSize(int n){}void setColor(int n){}float measureText(String s){return s.length();}Object getFontMetricsInt(){return null;}}
class StaticLayout{CharSequence text;int width;StaticLayout(CharSequence t,TextPaint p,int w,Object a,float x,float y,boolean b){text=t;width=w;}CharSequence getText(){return text;}int getLineCount(){return 1;}float getLineLeft(int n){return 1;}float getLineRight(int n){return width-1;}float getLineWidth(int n){return width-2;}int getHeight(){return 18;}void draw(Canvas c){}}
class Layout{static class Alignment{static Object ALIGN_NORMAL=new Object(),ALIGN_CENTER=new Object();}}
class TextUtils{static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a==null?null:a.toString(),b==null?null:b.toString());}static CharSequence concat(CharSequence...x){return String.join("",x);}static CharSequence ellipsize(CharSequence s,TextPaint p,int w,Object o){return s;}static class TruncateAt{static Object END=new Object();}}
class Emoji{static CharSequence replaceEmoji(CharSequence s,Object f,boolean b){return s;}}
class Rect{void set(int a,int b,int c,int d){}float centerX(){return 20;}float centerY(){return 20;}float width(){return 46;}boolean contains(float x,float y){return false;}}
class StoryParams{Rect originalAvatarRect=new Rect();int resets,touches;void reset(){resets++;}boolean checkOnTouchEvent(MotionEvent e,Object o){touches++;return true;}}
class StoriesUtilities{static int draws;static void drawAvatarWithStory(long id,Canvas c,ImageReceiver i,StoryParams p){draws++;}}
class CheckBox2{boolean checked;int x,y;int getMeasuredWidth(){return 24;}int getMeasuredHeight(){return 24;}void layout(int a,int b,int c,int d){x=a;y=b;}boolean isChecked(){return checked;}void setChecked(boolean b,boolean a){checked=b;}}
class Swap extends Drawable{boolean center,empty;void set(Object o,boolean a){empty=o==null;}void set(long id,boolean a){empty=false;}void setColor(Object o){}boolean isEmpty(){return empty;}}
class Drawable{int getIntrinsicWidth(){return 18;}int getIntrinsicHeight(){return 18;}}
class CombinedDrawable extends Drawable{CombinedDrawable(Object...a){}}
class PremiumGradient{Drawable premiumStarDrawableMini=new Drawable();static PremiumGradient getInstance(){return new PremiumGradient();}}
class CanvasButton{CanvasButton(Object o){}void setDelegate(Runnable r){}boolean checkTouchEvent(MotionEvent e){return false;}}
class RecyclerListView{Click getOnItemClickListener(){return new Click();}int getChildAdapterPosition(Object o){return 0;}static class Click{void onItemClick(Object o,int p){}}}
class SpannableStringBuilder implements CharSequence{StringBuilder b=new StringBuilder();SpannableStringBuilder(CharSequence s){b.append(s);}SpannableStringBuilder append(CharSequence s){b.append(s);return this;}public int length(){return b.length();}public char charAt(int n){return b.charAt(n);}public CharSequence subSequence(int a,int z){return b.substring(a,z);}public String toString(){return b.toString();}void setSpan(Object s,int a,int z,int f){}}
class Spanned{static int SPAN_EXCLUSIVE_EXCLUSIVE=1;}class ColoredImageSpan{ColoredImageSpan(int n){}float spaceScaleX;void setScale(float... f){}void translate(int x,int y){}}
class FilterCreateActivity{static class TextSpan{TextSpan(Object...a){}}}
class Text{Text(CharSequence s,int n){}float getCurrentWidth(){return 10;}}
class PhoneFormat{static PhoneFormat getInstance(){return new PhoneFormat();}String format(String s){return s;}}
class ConnectionsManager{static ConnectionsManager getInstance(int n){return new ConnectionsManager();}int getCurrentTime(){return 1;}}
class Canvas{static float x1;void drawLine(float a,float b,float c,float d,Paint p){x1=LocaleController.isRTL?320-c:a;}void save(){}void restore(){}void clipPath(Object p){}}
class PhotoBubbleClip{void setBounds(int x,int y,int r){}}
class AccessibilityNodeInfo{CharSequence text;boolean checked;void setText(CharSequence t){text=t;}void setCheckable(boolean b){}void setChecked(boolean b){checked=b;}void setClassName(String n){}}
class MotionEvent{static int ACTION_DOWN=0,ACTION_MOVE=2,ACTION_UP=1,ACTION_CANCEL=3;int getAction(){return ACTION_DOWN;}float getX(){return 0;}float getY(){return 0;}}
class Bounce{boolean isPressed(){return false;}void setPressed(boolean b){}}
interface Consumer{void run(TLRPC.User u);}interface Sponsor{void run(Object a,Object b);}
class R{static class string{static int SearchAd,HiddenName,Invite,SupportStatus,Bot,VerifyCodesNotifications,ServiceNotifications,Online,Community,ChannelPrivate,ChannelPublic,MegaLocation,MonoforumMessages,MegaPrivate,MegaPublic,AccDescrVerified,MonoforumSpan;}static class drawable{static int ic_ab_other;}}
""";
}
