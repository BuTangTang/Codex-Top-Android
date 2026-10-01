package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 在受控队列执行原预取与摘要发布方法，真实消息及缓存窗口均使用合成来源。 */
public final class RuntimeDialogPreviewTest {
    /** 先验证现预取方法的可观察摘要，再验证真实发布方法的账号、连接及 pending 边界。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source.resolve("CodexRuntime.java"));
        var names = Set.of("prefetchDialogs", "isAccountCurrent", "dialogConnection", "restoreDialogPreviews",
                "publishHistoryPreview", "publishPendingPreview", "publishDialogPreview", "sameDialogPreview", "previewCurrent");
        StringBuilder methods = new StringBuilder();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class))
            if (names.contains(method.getNameAsString())) methods.append(method).append('\n');
        // 基线尚未有发布方法时仍执行原预取；空测试替身只用于观察缺失的数据接线。
        for (String name : new String[]{"restoreDialogPreviews", "publishHistoryPreview", "publishPendingPreview"}) {
            if (unit.findAll(MethodDeclaration.class).stream().noneMatch(value -> value.getNameAsString().equals(name))) {
                if (name.equals("restoreDialogPreviews")) methods.append("static void restoreDialogPreviews(int a,long e,String m,Map<String,Long> ids){}\n");
                else if (name.equals("publishHistoryPreview")) methods.append("static void publishHistoryPreview(int a,long e,long id,String r,String m,DesktopConnection c,TranscriptWindow h){}\n");
                else methods.append("static void publishPendingPreview(int a,long e,long id,ArrayList<MessageObject> ms,boolean fresh){}\n");
            }
        }
        Path temporary = Files.createTempDirectory("codex-dialog-preview-");
        try {
            Path probe = temporary.resolve("RuntimeDialogPreviewProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            Path flags = temporary.resolve("BuildVars.java"), log = temporary.resolve("Log.java"), account = temporary.resolve("UserConfig.java");
            Files.writeString(flags, "package org.telegram.messenger; public class BuildVars { public static final boolean DEBUG_VERSION=false; }");
            Files.writeString(log, "package android.util; public class Log { public static int i(String a,String b){return 0;} }");
            Files.writeString(account, "package org.telegram.messenger; public class UserConfig { public static int selectedAccount; }");
            var compile = new ArrayList<String>();
            compile.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), flags.toString(), log.toString(), account.toString()));
            for (String model : new String[]{"DesktopAttachment", "TranscriptText", "TranscriptWindow"})
                compile.add(source.resolve(model + ".java").toString());
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实摘要发布夹具编译失败");
            var paths = new ArrayList<java.net.URL>();
            paths.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) paths.add(Path.of(entry).toUri().toURL());
            // 独立模型与 Probe 同属一个加载器，主管共同 classpath 不得混入旧包内访问者。
            try (var loader = new URLClassLoader(paths.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimeDialogPreviewProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实摘要发布回归失败", error.getCause()); }
            }
            // 原入口必须调用同一发布方法，不能仅在直接调用的测试中交付摘要。
            for (String name : new String[]{"publishDialogs", "prefetchDialogs", "loadMessages", "watchConversation", "sendBatch"}) {
                boolean wired = unit.findAll(MethodDeclaration.class).stream().filter(value -> value.getNameAsString().equals(name))
                        .anyMatch(value -> value.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                                .anyMatch(call -> Set.of("restoreDialogPreviews", "publishHistoryPreview", "publishPendingPreview").contains(call.getNameAsString())));
                if (!wired) throw new AssertionError("原入口未接入真实摘要: " + name);
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;import java.util.*;import java.io.IOException;
        public final class RuntimeDialogPreviewProbe {
            static class Queue {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();void postRunnable(Runnable r){tasks.add(r);}void one(){tasks.remove().run();}void all(){int n=100;while(!tasks.isEmpty()){if(--n==0)throw new AssertionError("queue loop");one();}}}
            static class Utilities {static final Queue globalQueue=new Queue();}
            static class AndroidUtilities {static final Queue ui=new Queue();static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
            static class ApplicationLoader {static boolean mainInterfacePaused;}
            static class PasswordLogin {static class Session {}}
            static class DesktopConnection {
                String machineId;boolean connected=true;int calls;JsonObject head,tail;
                DesktopConnection(String machine){machineId=machine;}boolean isConnected(){return connected;}
                JsonObject transcript(String remote){calls++;return head;}JsonObject transcript(String remote,String cursor){calls++;return head;}
                JsonObject readAfter(String remote,String cursor){calls++;return tail;}
            }
            static class TLRPC {static class Message {int id,date,send_state;long dialog_id;boolean out;String message,attachPath;Map<String,String> params=new HashMap<>();}static class TL_message extends Message {}}
            static class MessageObject {TLRPC.Message messageOwner;MessageObject(TLRPC.Message m){messageOwner=m;}int getId(){return messageOwner.id;}long getDialogId(){return messageOwner.dialog_id;}}
            static class MessagesController {
                static final int UPDATE_MASK_MESSAGE_TEXT=4;static final MessagesController instance=new MessagesController();
                final Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();
                static MessagesController getInstance(int account){return instance;}
            }
            static class NotificationCenter {static final int updateInterfaces=1;static final NotificationCenter instance=new NotificationCenter();int previews,other;
                static NotificationCenter getInstance(int a){return instance;}void postNotificationName(int event,Object... values){if(event==updateInterfaces&&values.length==1&&values[0].equals(MessagesController.UPDATE_MASK_MESSAGE_TEXT))previews++;else other++;}}
            static PasswordLogin.Session session;static long accountGeneration,watchedDialog,nextId;static boolean loggingOut;
            static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>();
            static final Map<String,Long> ids=new HashMap<>();static final Map<Long,TranscriptWindow> histories=new HashMap<>();
            static final Map<Long,Long> prefetchedRevisions=new HashMap<>();static final Set<Long> prefetching=new HashSet<>();
            static final Map<String,MessageObject> pendingMessages=new HashMap<>();static final Map<Long,TranscriptWindow> disk=new HashMap<>();
            static final Queue prefetchQueue=new Queue();static int reads,saves;
            static int recentDialogLimit(){return 2;}
            static long bindDialog(String machine,String remote,PasswordLogin.Session owner,long epoch){long id=ids.computeIfAbsent(machine+":"+remote,k->++nextId);dialogMachines.put(id,machine);remoteIds.put(id,remote);return id;}
            static TranscriptWindow readHistory(long id,String remote){reads++;return disk.getOrDefault(id,new TranscriptWindow());}
            static boolean saveHistory(long id,String remote,TranscriptWindow history){saves++;return true;}
            static void logTranscriptFailure(String phase,Exception e,JsonObject page){}
            // 仅替代原 Android 消息转换边界，不在测试中实现预取、发布选择或迟到校验。
            static TLRPC.TL_message historyMessage(long id,TranscriptWindow.Entry entry){TLRPC.TL_message m=new TLRPC.TL_message();m.id=entry.id;m.dialog_id=id;m.date=(int)(entry.message.createdAtMs/1000);m.message=entry.message.text;m.out=entry.message.outgoing;if(entry.message.localId!=null)m.params.put("codexLocalId",entry.message.localId);if(!entry.message.attachments.isEmpty())m.params.put("codexAttachment","synthetic media");return m;}
            static MessageObject historyObject(int account,TLRPC.TL_message message){return new MessageObject(message);}
            static class OutboxStore {static class Item {String localId,text;int messageId,date;Item(String local,String body,int id,int date){this.localId=local;this.text=body;this.messageId=id;this.date=date;}}ArrayList<Item> rows=new ArrayList<>();ArrayList<Item> list(String remote)throws IOException{return new ArrayList<>(rows);}}
            static final OutboxStore outbox=new OutboxStore();static OutboxStore outboxStore(long id){return outbox;}
            static boolean batchEchoed(OutboxStore.Item item,Set<String> echoed){return echoed.contains(item.localId);}
            static ArrayList<MessageObject> restoredPending(int account,long id,OutboxStore.Item item,Set<String> echoed){MessageObject object=pendingMessages.get(item.localId);if(object==null)object=pending(id,item.messageId,item.localId,item.text,item.date);return new ArrayList<>(List.of(object));}
        """;

    private static final String SCENARIOS = """
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
            static void reset(){session=new PasswordLogin.Session();accountGeneration++;loggingOut=false;watchedDialog=0;nextId=0;reads=saves=0;desktopConnections.clear();dialogMachines.clear();remoteIds.clear();ids.clear();histories.clear();disk.clear();pendingMessages.clear();outbox.rows.clear();prefetchedRevisions.clear();prefetching.clear();MessagesController.instance.dialogMessage.clear();NotificationCenter.instance.previews=NotificationCenter.instance.other=0;Utilities.globalQueue.tasks.clear();AndroidUtilities.ui.tasks.clear();prefetchQueue.tasks.clear();ApplicationLoader.mainInterfacePaused=false;org.telegram.messenger.UserConfig.selectedAccount=0;}
            static JsonObject page(String source,String local,String role,String body,long time){JsonObject page=new JsonObject(),item=new JsonObject(),raw=new JsonObject(),content=new JsonObject();item.addProperty("id",source);if(local!=null)item.addProperty("localId",local);item.addProperty("createdAtMs",time);raw.addProperty("role",role);content.addProperty("type","text");content.addProperty("text",body);raw.add("content",content);item.add("raw",raw);JsonArray items=new JsonArray();items.add(item);page.add("items",items);page.addProperty("hasMore",false);page.addProperty("historyAvailability","available");page.addProperty("tailCursor","tail");page.addProperty("nextCursor","tail");return page;}
            static TranscriptWindow history(String source,String local,String role,String body,long time)throws Exception{TranscriptWindow h=new TranscriptWindow();h.prepend(page(source,local,role,body,time));return h;}
            static JsonArray candidates(){JsonObject c=new JsonObject();c.addProperty("remoteSessionId","qa");c.addProperty("updatedAtMs",1000);JsonArray cs=new JsonArray();cs.add(c);return cs;}
            static MessageObject visible(long id){ArrayList<MessageObject> list=MessagesController.instance.dialogMessage.get(id);return list==null||list.isEmpty()?null:list.get(0);}
            static void pump(){int n=100;while(!Utilities.globalQueue.tasks.isEmpty()||!AndroidUtilities.ui.tasks.isEmpty()||!prefetchQueue.tasks.isEmpty()){if(--n==0)throw new AssertionError("queue loop");Utilities.globalQueue.all();AndroidUtilities.ui.all();if(!prefetchQueue.tasks.isEmpty())prefetchQueue.one();}}
            static MessageObject pending(long id,int localNumber,String local,String text,int date){TLRPC.TL_message m=new TLRPC.TL_message();m.dialog_id=id;m.id=localNumber;m.out=true;m.date=date;m.message=text;m.params.put("codexLocalId",local);MessageObject object=new MessageObject(m);pendingMessages.put(local,object);return object;}
            static void cacheAndRevision()throws Exception{
                reset();DesktopConnection c=new DesktopConnection("machine");desktopConnections.put(c.machineId,c);long id=bindDialog(c.machineId,"qa",session,accountGeneration);
                histories.put(id,history("cached",null,"agent","synthetic cached answer",1000));prefetchedRevisions.put(id,1000L);
                prefetchDialogs(candidates(),c);pump();
                check(visible(id)!=null&&visible(id).messageOwner.message.equals("synthetic cached answer"),"unchanged revision left native preview empty");
                check(c.calls==0,"cache preview added a network request");int notices=NotificationCenter.instance.previews;MessageObject first=visible(id);
                prefetchDialogs(candidates(),c);pump();check(visible(id)==first&&NotificationCenter.instance.previews==notices,"unchanged real preview rebuilt a message");
                reset();id=bindDialog("machine","qa",session,accountGeneration);disk.put(id,history("offline",null,"agent","synthetic offline answer",1000));
                restoreDialogPreviews(0,accountGeneration,"machine",Map.of("qa",id));pump();
                check(visible(id)!=null&&visible(id).messageOwner.message.equals("synthetic offline answer")&&reads==1,"offline list binding did not recover local real preview");
                check(NotificationCenter.instance.other==0,"local backfill emitted historical chat/notification events");
            }
            static void prefetchAndPending()throws Exception{
                reset();DesktopConnection c=new DesktopConnection("machine");desktopConnections.put(c.machineId,c);c.head=page("first",null,"agent","synthetic first answer",1000);
                prefetchDialogs(candidates(),c);pump();long id=ids.get("machine:qa");check(visible(id)!=null,"prefetch merge did not publish a real preview");
                MessageObject one=pending(id,-7,"local-one","synthetic pending",2),two=pending(id,-8,"local-two","synthetic second attachment",2);
                publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(one,two)),true);check(visible(id)==two,"new native batch did not publish the actual last member");
                publishHistoryPreview(0,accountGeneration,id,"qa","machine",c,histories.get(id));pump();check(visible(id)==two,"older history overwrote an unechoed pending");
                TranscriptWindow echoed=history("echo","local-two","user","synthetic second attachment",2000);echoed.append(page("answer",null,"agent","synthetic response after echo",2100));
                publishHistoryPreview(0,accountGeneration,id,"qa","machine",c,echoed);pump();
                check(visible(id).getId()>0&&visible(id).messageOwner.message.equals("synthetic response after echo"),"real echo did not release pending to the latest actual answer");
                MessageObject newest=pending(id,-1,"local-new","synthetic newest batch",2);publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(newest)),true);
                publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(one,two)),false);check(visible(id)==newest,"retrying an older negative-ID batch replaced the actual newest message");
                pendingMessages.clear();TLRPC.TL_message media=new TLRPC.TL_message();media.id=123;media.dialog_id=id;media.date=3;media.message="synthetic file caption";media.params.put("codexAttachment","synthetic file descriptor");media.attachPath="/synthetic/file-original";
                publishDialogPreview(0,accountGeneration,id,"qa","machine",c,media,null,Set.of());MessageObject originalMedia=visible(id);int notices=NotificationCenter.instance.previews;
                TLRPC.TL_message same=new TLRPC.TL_message();same.id=media.id;same.dialog_id=id;same.date=media.date;same.message=media.message;same.params.putAll(media.params);same.attachPath=media.attachPath;
                publishDialogPreview(0,accountGeneration,id,"qa","machine",c,same,null,Set.of());check(visible(id)==originalMedia&&NotificationCenter.instance.previews==notices,"same real attachment preview rebuilt its message");
                same.attachPath="/synthetic/file-downloaded";publishDialogPreview(0,accountGeneration,id,"qa","machine",c,same,null,Set.of());check(visible(id)!=originalMedia,"real attachment readiness change was dropped");
                check(NotificationCenter.instance.other==0,"preview publisher generated a new history/notification event");
            }
            static void lateOwners()throws Exception{
                for(String change:new String[]{"epoch","account","machine","remote","connection"}){
                    reset();DesktopConnection c=new DesktopConnection("machine");desktopConnections.put(c.machineId,c);long id=bindDialog(c.machineId,"qa",session,accountGeneration);TranscriptWindow h=history("late",null,"agent","synthetic stale preview",1000);
                    publishHistoryPreview(0,accountGeneration,id,"qa","machine",c,h);
                    switch(change){case "epoch":accountGeneration++;break;case "account":org.telegram.messenger.UserConfig.selectedAccount=1;break;case "machine":dialogMachines.put(id,"other");break;case "remote":remoteIds.put(id,"other");break;case "connection":desktopConnections.put(c.machineId,new DesktopConnection(c.machineId));break;}
                    AndroidUtilities.ui.all();check(visible(id)==null,"late "+change+" preview reached the new owner");
                }
                reset();long id=bindDialog("machine","qa",session,accountGeneration);MessageObject wrong=pending(id+1,-2,"wrong","synthetic other dialog",2);
                publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(wrong)),true);check(visible(id)==null,"other-dialog native pending reached this preview owner");
            }
            static void coldPending()throws Exception{
                reset();long id=bindDialog("machine","qa",session,accountGeneration);disk.put(id,history("older",null,"agent","synthetic cached older answer",1000));
                outbox.rows.add(new OutboxStore.Item("old","synthetic old failed",-1,1));outbox.rows.add(new OutboxStore.Item("new","synthetic cold pending",-8,3));
                restoreDialogPreviews(0,accountGeneration,"machine",Map.of("qa",id));pump();
                check(visible(id)!=null&&visible(id).messageOwner.message.equals("synthetic cold pending"),"cold list did not recover the latest actual dated outbox message");
                reset();id=bindDialog("machine","qa",session,accountGeneration);disk.put(id,history("later",null,"agent","synthetic later real answer",5000));
                outbox.rows.add(new OutboxStore.Item("old","synthetic old failed",-99,1));restoreDialogPreviews(0,accountGeneration,"machine",Map.of("qa",id));pump();
                check(visible(id).messageOwner.message.equals("synthetic later real answer"),"older negative-ID failure displaced newer dated real history");
                reset();id=bindDialog("machine","qa",session,accountGeneration);disk.put(id,history("older",null,"agent","synthetic cached older answer",1000));outbox.rows.add(new OutboxStore.Item("old","synthetic cold pending",-8,3));
                restoreDialogPreviews(0,accountGeneration,"machine",Map.of("qa",id));Utilities.globalQueue.all();
                MessageObject fresh=pending(id,-2,"live","synthetic live new pending",3);publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(fresh)),true);AndroidUtilities.ui.all();
                check(visible(id)==fresh,"late outbox restore overwrote a newly created pending");
                check(NotificationCenter.instance.other==0,"cold outbox restore generated chat or old-message notifications");
            }
            public static void main(String[] args)throws Exception{cacheAndRevision();System.out.println("PASS cacheAndRevision");prefetchAndPending();System.out.println("PASS prefetchAndPending");lateOwners();System.out.println("PASS lateOwners");coldPending();System.out.println("PASS coldPending");}
        """;
}
