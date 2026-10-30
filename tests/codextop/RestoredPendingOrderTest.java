package com.butang.codextop;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import javax.tools.ToolProvider;

/** 执行原聊天的插入、日期行及跟随分支；恢复标记由Runtime负责，不代替设备验收。 */
public final class RestoredPendingOrderTest {
    /** 可传冻结的ChatActivity源码执行RED；所有日期、消息和视图反馈都是合成输入。 */
    public static void main(String[] args) throws Exception {
        Path sourcePath = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") : Path.of(args[0]);
        byte[] original = Files.readAllBytes(sourcePath);
        String source = new String(original, java.nio.charset.StandardCharsets.UTF_8);
        String process = block(source, "private void processNewMessages(ArrayList<MessageObject> arr, final boolean animatedFromBottom)");
        String placement = block(process, "if (placeToPaste == -1)");
        String outgoing = block(process, "if (obj.isOut() && !(obj.messageOwner.action instanceof TLRPC.TL_messageActionTodoCompletions");
        String date = block(process, "if (dayArray == null)");
        String scroll = block(process, "if (lastVisible == 0 && diff <= AndroidUtilities.dp(5) && !hasDraftsReplaces || hasFromMe)");
        int insertionStart = process.indexOf("messages.add(placeToPaste, obj);");
        int insertionEnd = process.indexOf("if (notPushedSponsoredMessages != null", insertionStart);
        if (insertionStart < 0 || insertionEnd < 0) throw new AssertionError("实际消息插入边界不存在");
        String insertion = process.substring(insertionStart, insertionEnd);
        Path temporary = Files.createTempDirectory("codex-restored-pending-order-");
        try {
            Path probe = temporary.resolve("RestoredPendingOrderProbe.java");
            Files.writeString(probe, FIXTURE + execution(placement, outgoing, date, insertion, scroll) + SCENARIOS);
            Path runtime = temporary.resolve("CodexRuntime.java");
            Files.writeString(runtime, "package com.butang.codextop; public class CodexRuntime {public static boolean owned; /** 返回合成归属，不读取真实账号。 */ public static boolean ownsConversation(long id){return owned && id==42L;}}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temporary.toString(), probe.toString(), runtime.toString()) != 0)
                throw new AssertionError("实际聊天放置分支夹具编译失败");
            System.out.println("ChatActivity SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, null)) {
                try { loader.loadClass("com.butang.codextop.RestoredPendingOrderProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (InvocationTargetException error) { throw new AssertionError("实际恢复消息放置回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
            if (!Arrays.equals(original, Files.readAllBytes(sourcePath))) throw new AssertionError("验收期间ChatActivity源码变化");
        }
    }

    /** 在唯一真实代码块上按词法读取花括号，跳过注释和字符串，不复制产品判定。 */
    private static String block(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0 || source.indexOf(marker, start + marker.length()) >= 0)
            throw new AssertionError("实际分支不存在或不唯一：" + marker);
        int open = source.indexOf('{', start), depth = 0;
        boolean string = false, character = false, line = false, comment = false, escape = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (comment) { if (c == '*' && next == '/') { comment = false; i++; } continue; }
            if (string || character) {
                if (escape) escape = false; else if (c == '\\') escape = true;
                else if (string && c == '"') string = false; else if (character && c == '\'') character = false;
                continue;
            }
            if (c == '/' && next == '/') { line = true; i++; } else if (c == '/' && next == '*') { comment = true; i++; }
            else if (c == '"') string = true; else if (c == '\'') character = true;
            else if (c == '{') depth++; else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("实际分支未闭合：" + marker);
    }

    /** 只接通原分支的输入与反馈，排列和跟随选择始终执行抽取的正式代码。 */
    private static String execution(String placement, String outgoing, String date, String insertion, String scroll) {
        return """
            /** 输入代表已由Runtime恢复并交付的未回显气泡，默认首屏已完成且在尾部。 */
            static void execute(Case c) {
                CodexRuntime.owned=c.codex;
                messages=new ArrayList<>(Arrays.asList(c.history));
                messagesByDays=new HashMap<>();messagesByDaysSorted=new HashMap<>();
                for(MessageObject m:c.history)messagesByDays.computeIfAbsent(m.dateKey,k->new ArrayList<>()).add(m);
                chatMode=c.scheduled ? MODE_SCHEDULED : MODE_DEFAULT;
                boolean hasFromMe=false,needMoveScrollToLastMessage=false;
                boolean firstLoading=false,paused=false,scrollToTopOnResume=false,forceScrollToTop=false;
                int newUnreadMessageCount=0;
                final long dialog_id=42L;
                for(MessageObject obj:c.restored) {
                    int placeToPaste=-1,messageId=obj.getId();
        """ + placement + outgoing + """
                    ArrayList<MessageObject> dayArray=messagesByDays.get(obj.dateKey);
        """ + date + """
                    dayArray.add(0,obj);
        """ + insertion + """
                }
                int lastVisible=c.atBottom ? 0 : 8,diff=0;
                boolean hasDraftsReplaces=false;
        """ + scroll + """
                actual.forced=forceScrollToTop;actual.queued=scrollToTopOnResume;
            }
        """;
    }

    // 平台替身只提供消息字段、日期key、分组及视图反馈；不实现排列算法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        import java.time.*;
        public class RestoredPendingOrderProbe {
            static final int MODE_DEFAULT=0,MODE_SCHEDULED=1;
            static int chatMode,currentAccount;
            static ArrayList<MessageObject> messages;
            static HashMap<String,ArrayList<MessageObject>> messagesByDays;
            static HashMap<Integer,ArrayList<MessageObject>> messagesByDaysSorted;
            static HashMap<Long,MessageObject.GroupedMessages> groupedMessagesMap=new HashMap<>();
            static Adapter chatAdapter;
            static Result actual;
            static class TLRPC {
                static class Message {int id,date;Object action;boolean from_scheduled;Map<String,String> params=new HashMap<>();String message;}
                static class TL_message extends Message {}
                static class TL_messageActionTodoCompletions {}
                static class TL_messageActionTodoAppendTasks {}
            }
            static class MessageObject {
                static final int TYPE_DATE=10;
                TLRPC.Message messageOwner;
                boolean scheduled,wasJustSent,quick;
                int type,contentType,stableId,dateKeyInt;
                boolean isDateObject;
                String dateKey;
                /** 只把合成字段投影为原分支依赖的消息形状。 */
                MessageObject(int account,TLRPC.Message message,boolean generateLayout,boolean checkMedia) {
                    messageOwner=message;dateKeyInt=Math.floorDiv(message.date,86400);dateKey=Integer.toString(dateKeyInt);
                }
                /** 返回原编号形状，不从日期重新编号。 */int getId(){return messageOwner.id;}
                /** 所有测试待发项均为用户方向。 */boolean isOut(){return getId()<0;}
                /** 合成普通消息没有媒体分组。 */long getGroupId(){return 0;}
                /** 原快捷回复资格由用例单独声明。 */boolean isQuickReply(){return quick;}
                /** 合成来源不包含广告。 */boolean isSponsored(){return false;}
                static class GroupedMessages {ArrayList<MessageObject> messages=new ArrayList<>();}
            }
            static class Adapter {
                int messagesStartRow;
                /** 不创建界面，只保留原行更新调用形状。 */void checkRemoveBotForumRowsStartThreadRow(boolean b){}
                /** 不创建界面，只保留原插入通知形状。 */void notifyItemInserted(int row){}
            }
            static class R {static class string {static final int MessageScheduledUntilOnline=1,MessageScheduledOn=2;}}
            static class LocaleController {
                /** 日期文字不属于排列证据。 */static String getString(int key){return "synthetic";}
                /** 日期文字不属于排列证据。 */static String formatString(int key,Object... values){return "synthetic";}
                /** 返回固定日期文字，不接入真实应用语言。 */static String formatDateChat(int date){return "synthetic";}
                /** 保留原排期日期接口。 */static String formatDateChat(int date,boolean b){return "synthetic";}
            }
            static class AndroidUtilities {/** 固定合成密度。 */static int dp(int value){return value;}}
            static class Result {int moves;boolean forced,queued;}
            static class Case {
                final String name;final boolean codex,scheduled,atBottom;final MessageObject[] history,restored;final int[] expected;final int expectedMoves;
                /** 明确输入及期待，所有ID和日期均由本测试声明。 */
                Case(String name,boolean codex,boolean scheduled,boolean bottom,MessageObject[] history,MessageObject[] restored,int[] expected,int moves) {
                    this.name=name;this.codex=codex;this.scheduled=scheduled;atBottom=bottom;this.history=history;this.restored=restored;this.expected=expected;expectedMoves=moves;
                }
            }
            /** 合成普通聊天没有快捷回复日期例外。 */static boolean isQuickRepliesOrWelcomeMessagesMode(){return false;}
            /** 合成日期稳定编号不参与消息插入选择。 */static int getStableIdForDateObject(int date){return date;}
            /** 记录原未读平面调用。 */static void removeUnreadPlane(boolean b){}
            /** 记录原提示隐藏调用。 */static void hideInfoView(){}
            /** 记录原尾部跟随调用。 */static void moveScrollToLastMessage(boolean animated){actual.moves++;}
        """;

    private static final String SCENARIOS = """
            /** 构造2026年10月的合成消息，不读设备或正文。 */
            static MessageObject item(int id,int day,boolean restore,boolean justSent) {
                TLRPC.TL_message owner=new TLRPC.TL_message();owner.id=id;
                owner.date=(int)LocalDate.of(2026,10,day).atTime(12,0).toEpochSecond(ZoneOffset.UTC);
                if(restore)owner.params.put("codexRestoredPending","true");
                MessageObject message=new MessageObject(0,owner,false,false);message.wasJustSent=justSent;return message;
            }
            /** 保留原日期服务项的零编号和当天零点，不以服务项替代聊天消息。 */
            static MessageObject dateService(int day) {
                TLRPC.TL_message owner=new TLRPC.TL_message();owner.date=(int)LocalDate.of(2026,10,day).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
                MessageObject message=new MessageObject(0,owner,false,false);message.type=MessageObject.TYPE_DATE;message.isDateObject=true;return message;
            }
            /** 仅选择真实气泡，日期行另外报告，不参与期待消息顺序。 */
            static int[] ids(){return messages.stream().filter(m->!m.isDateObject).mapToInt(MessageObject::getId).toArray();}
            /** 检查每个日期行前的气泡日期一致，防止日期布局与消息位置割裂。 */
            static boolean dateRowsMatch(){
                for(int i=0;i<messages.size();i++)if(messages.get(i).isDateObject) {
                    if(i==0||!messages.get(i-1).dateKey.equals(messages.get(i).dateKey))return false;
                }
                return true;
            }
            /** 执行所有边界并报告真实输出，失败用于RED而非把编译错误当失败证据。 */
            public static void main(String[] args) {
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
                MessageObject quick=item(-9,1,true,false);quick.quick=true;
                MessageObject scheduled=item(-9,1,true,false);scheduled.scheduled=true;
                Case[] cases={
                    new Case("codex_old_unknown_after_latest",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-11,1,true,false)},new int[]{1003,-11},1),
                    new Case("codex_new_to_old_restore_batch",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-22,2,true,false),item(-11,1,true,false)},new int[]{1003,-22,-11},1),
                    new Case("codex_existing_instance_wasJustSent_true",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-11,1,true,true)},new int[]{1003,-11},1),
                    new Case("codex_id_cannot_fake_newer_date",true,false,true,new MessageObject[]{item(-200,3,false,false),item(1002,2,false,false)},new MessageObject[]{item(-1,1,true,false)},new int[]{-200,1002,-1},1),
                    new Case("codex_restore_newer_than_history",true,false,true,new MessageObject[]{item(1002,2,false,false)},new MessageObject[]{item(-33,3,true,false)},new int[]{-33,1002},1),
                    new Case("codex_same_second_distinct_batches",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-50,2,true,false),item(-30,2,true,false)},new int[]{1003,-50,-30},1),
                    new Case("codex_same_second_attachment_members",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-30,2,true,false),item(-31,2,true,false)},new int[]{1003,-31,-30},1),
                    new Case("codex_same_second_batches_with_attachments",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-50,2,true,false),item(-51,2,true,false),item(-30,2,true,false),item(-31,2,true,false)},new int[]{1003,-51,-50,-31,-30},1),
                    new Case("codex_same_second_positive_original_rule",true,false,true,new MessageObject[]{item(1002,2,false,false)},new MessageObject[]{item(-30,2,true,false)},new int[]{-30,1002},1),
                    new Case("codex_restored_after_date_service",true,false,true,new MessageObject[]{item(1003,3,false,false),dateService(3)},new MessageObject[]{item(-11,1,true,false)},new int[]{1003,-11},1),
                    new Case("codex_native_positive_id_original_priority",true,false,true,new MessageObject[]{item(1002,3,false,false)},new MessageObject[]{item(1003,1,true,false)},new int[]{1003,1002},1),
                    new Case("codex_reading_old_keeps_scroll",true,false,false,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-11,1,true,false)},new int[]{1003,-11},0),
                    new Case("telegram_negative_id_original_priority",false,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-11,1,true,false)},new int[]{-11,1003},1),
                    new Case("codex_fresh_local_original_priority",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-11,1,false,true)},new int[]{-11,1003},1),
                    new Case("codex_unmarked_negative_original_priority",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{item(-11,1,false,false)},new int[]{-11,1003},1),
                    new Case("codex_scheduled_original_date_order",true,true,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{scheduled},new int[]{1003,-9},0),
                    new Case("codex_quick_reply_original_priority",true,false,true,new MessageObject[]{item(1003,3,false,false)},new MessageObject[]{quick},new int[]{-9,1003},1)
                };
                int failures=0;
                for(Case c:cases) {
                    actual=new Result();execute(c);int[] result=ids();
                    boolean pass=Arrays.equals(c.expected,result)&&actual.moves==c.expectedMoves&&dateRowsMatch();
                    if(!pass)failures++;
                    System.out.println((pass?"PASS ":"FAIL ")+c.name+" expected="+Arrays.toString(c.expected)+" actual="+Arrays.toString(result)
                            +" dateRows="+dateRowsMatch()+" moves="+actual.moves);
                }
                System.out.println("RestoredPendingOrder: total="+cases.length+" failures="+failures+"; synthetic branch checks, not Android acceptance");
                if(failures!=0)throw new AssertionError("实际聊天恢复放置失败数="+failures);
            }
        }
        """;
}
