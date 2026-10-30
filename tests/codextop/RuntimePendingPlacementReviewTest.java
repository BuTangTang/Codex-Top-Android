package com.butang.codextop;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 独立执行初始恢复通知块及真实恢复方法，不读真实Outbox、账号或设备。 */
public final class RuntimePendingPlacementReviewTest {
    /** 所有输入在独立临时目录中构造，正式恢复块按Java8编译执行。 */
    public static void main(String[] args) throws Exception {
        Path runtime = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        String source = Files.readString(runtime);
        String load = block(source, "public static void loadMessages(");
        if (!load.contains("ArrayList<OutboxStore.Item> restored = maxId == 0 && loadType == 2"))
            throw new AssertionError("初始恢复选择边界已变化");
        String restore = block(source, "private static ArrayList<MessageObject> restoredPending(");
        String create = block(source, "private static TLRPC.TL_message pendingMessage(");
        String publish = block(load, "if (!restored.isEmpty())").replace("org.telegram.messenger.MessageObject", "MessageObject");
        Path scratch = Files.createTempDirectory("runtime-pending-placement-review-");
        try {
            Path probe = scratch.resolve("RuntimePendingReviewProbe.java");
            Files.writeString(probe, FIXTURE + restore + create
                    + "\n/** 执行正式初始恢复通知块。 */ static void publish(ArrayList<OutboxStore.Item> restored,Set<String> echoed){int account=0;long dialogId=42;\n"
                    + publish + "\n}\n" + SCENARIOS);
            Path account = scratch.resolve("UserConfig.java");
            Files.writeString(account, "package org.telegram.messenger; public class UserConfig {public int lastSendMessageId=-1;"
                    + "static final UserConfig account=new UserConfig(); /** 只返回合成账号。 */ public static UserConfig getInstance(int a){return account;}"
                    + "/** 不写真实配置。 */ public void saveConfig(boolean b){} }");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", scratch.toString(),
                    probe.toString(), account.toString()) != 0) throw new AssertionError("正式恢复块合成编译失败");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{scratch.toUri().toURL()}, null)) {
                try { loader.loadClass("com.butang.codextop.RuntimePendingReviewProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (InvocationTargetException error) { throw new AssertionError("正式恢复链复核失败", error.getCause()); }
            }
        } finally {
            try (var files = Files.walk(scratch)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
            if (!source.equals(Files.readString(runtime))) throw new AssertionError("复核期间Runtime源码改变");
        }
    }

    /** 词法提取唯一正式代码块，跳过字符串和注释，保留原恢复算法。 */
    private static String block(String source, String marker) {
        int start=source.indexOf(marker);
        if(start<0||source.indexOf(marker,start+marker.length())>=0)throw new AssertionError("正式分支不唯一："+marker);
        int open=source.indexOf('{',start),depth=0;boolean string=false,character=false,line=false,comment=false,escape=false;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i),next=i+1<source.length()?source.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}if(comment){if(c=='*'&&next=='/'){comment=false;i++;}continue;}
            if(string||character){if(escape)escape=false;else if(c=='\\')escape=true;
                else if(string&&c=='"')string=false;else if(character&&c=='\'')character=false;continue;}
            if(c=='/'&&next=='/'){line=true;i++;}else if(c=='/'&&next=='*'){comment=true;i++;}
            else if(c=='"')string=true;else if(c=='\'')character=true;
            else if(c=='{')depth++;else if(c=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("正式块未闭合");
    }

    // 替身仅承载字段和通知反馈；日期、编号、状态、对象复用及标记来自正式方法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public class RuntimePendingReviewProbe {
            static final Map<String,MessageObject> pendingMessages=new HashMap<>();
            static class TLRPC {
                static class TL_peerUser {long user_id;}
                static class TL_messageMediaEmpty {}
                static class TL_message {int id,local_id,date,flags,send_state;long dialog_id;String message;boolean out,unread;
                    TL_peerUser peer_id,from_id;Object media;Map<String,String> params;}
            }
            static class MessageObject {
                static final int MESSAGE_SEND_STATE_SENT=0,MESSAGE_SEND_STATE_SENDING=1,MESSAGE_SEND_STATE_SEND_ERROR=2;
                final TLRPC.TL_message messageOwner;boolean wasJustSent,attachPathExists,mediaExists;
                /** 只接收合成原消息字段。 */MessageObject(int a,TLRPC.TL_message m,boolean x,boolean y){messageOwner=m;}
            }
            static class OutboxStore {
                static final String FAILURE_FILE_TOO_LARGE="file_too_large";
                static class Item {String localId,text="synthetic",failureCode;int messageId,date;boolean submissionAccepted,submissionUncertain;
                    final List<Object> attachments=new ArrayList<>(),selections=new ArrayList<>();
                    /** 显式声明原负编号和发送时间。 */Item(String local,int id,int date){localId=local;messageId=id;this.date=date;}
                    /** 只投影合成附件数量。 */int attachmentCount(){return attachments.size();}
                }
            }
            static class AttachmentMessages {
                /** 本片只检查气泡身份，不接触文件。 */static void applyPending(TLRPC.TL_message m,Object a){}
                /** 本片只检查气泡身份，不接触文件。 */static void applySelected(TLRPC.TL_message m,Object a){}
            }
            static class TranscriptText {
                /** 仅提供既有成员身份形状，不改变正式气泡恢复遍历。 */static String attachmentIdentity(String id,int i){return i==0?id:id+":attachment:"+i;}
            }
            static class NotificationCenter {
                static final int didReceiveNewMessages=1;static final NotificationCenter center=new NotificationCenter();
                static int notices;static ArrayList<MessageObject> delivered;
                /** 返回合成通知接收端。 */static NotificationCenter getInstance(int a){return center;}
                /** 记录正式恢复块实际交付的原对象。 */
                @SuppressWarnings("unchecked") void postNotificationName(int event,Object...values){notices++;delivered=(ArrayList<MessageObject>)values[1];}
            }
            /** 本片不声明附件就绪，不访问本机文件。 */static Object attachmentFile(MessageObject m){return null;}
            /** 每个场景清空独立内存替身。 */static void reset(){pendingMessages.clear();NotificationCenter.notices=0;NotificationCenter.delivered=null;}
            /** 输入记录仅来自本测试，不读取真实持久化内容。 */static ArrayList<OutboxStore.Item> items(OutboxStore.Item...items){return new ArrayList<>(Arrays.asList(items));}
            /** 明确检查原字段和恢复标记，不重新计算正式恢复状态。 */
            static void expected(String name,MessageObject m,int id,int date,int state){
                if(m.messageOwner.id!=id||m.messageOwner.date!=date||m.messageOwner.send_state!=state||m.wasJustSent
                    ||!"true".equals(m.messageOwner.params.get("codexRestoredPending")))throw new AssertionError(name);
                System.out.println("PASS "+name+" originalIdentityDateState=true restoredNotFresh=true");
            }
        """;

    private static final String SCENARIOS = """
            /** 六个合成场景核验恢复资格，不启动发送、网络、设备或应用。 */
            public static void main(String[] args){
                Set<String> none=new HashSet<>();reset();
                OutboxStore.Item failed=new OutboxStore.Item("failed",-10,100);publish(items(failed),none);
                expected("cold_failed",NotificationCenter.delivered.get(0),-10,100,2);
                reset();OutboxStore.Item accepted=new OutboxStore.Item("accepted",-20,200);accepted.submissionAccepted=true;
                publish(items(accepted),none);expected("cold_accepted",NotificationCenter.delivered.get(0),-20,200,0);
                reset();OutboxStore.Item unknown=new OutboxStore.Item("unknown",-30,300);unknown.submissionUncertain=true;
                publish(items(unknown),none);expected("cold_unknown",NotificationCenter.delivered.get(0),-30,300,1);
                reset();MessageObject existing=new MessageObject(0,pendingMessage(42,-7,150,"synthetic","existing",0),true,false);
                existing.wasJustSent=true;existing.messageOwner.send_state=0;pendingMessages.put("existing",existing);
                publish(items(new OutboxStore.Item("existing",-99,99)),none);
                if(NotificationCenter.delivered.get(0)!=existing)throw new AssertionError("existing identity replaced");
                expected("existing_reference_keeps_live_fields",existing,-7,150,0);
                reset();OutboxStore.Item multiple=new OutboxStore.Item("multi",-40,400);
                multiple.attachments.add(new Object());multiple.attachments.add(new Object());multiple.attachments.add(new Object());
                Set<String> echoed=new HashSet<>();echoed.add("multi");publish(items(multiple),echoed);
                if(NotificationCenter.delivered.size()!=2||pendingMessages.containsKey("multi"))throw new AssertionError("echoed member restored");
                expected("partial_multi_attachment_first",NotificationCenter.delivered.get(0),-41,400,2);
                expected("partial_multi_attachment_second",NotificationCenter.delivered.get(1),-42,400,2);
                reset();MessageObject fresh=new MessageObject(0,pendingMessage(42,-50,500,"synthetic","fresh",0),true,false);
                fresh.wasJustSent=true;pendingMessages.put("fresh",fresh);publish(items(),none);
                if(!fresh.wasJustSent||fresh.messageOwner.params.containsKey("codexRestoredPending")||NotificationCenter.notices!=0)
                    throw new AssertionError("empty restore altered fresh message");
                System.out.println("PASS empty_restore_keeps_distinct_fresh=true noPendingEvent=true");
                System.out.println("RuntimePendingPlacementReview: scenarios=6 failures=0; synthetic actual source blocks only");
            }
        }
        """;
}
