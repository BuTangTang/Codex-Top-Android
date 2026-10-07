package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实显示段握手、Window/Store/Outbox；仅使用合成网络与临时目录。 */
public final class RuntimeLatestSegmentTest {
    /** 可指定隔离候选源码；提取的正式方法仍按 Java 8 编译。 */
    public static void main(String[] args) throws Exception {
        Path sourceRoot = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        Path runtime = args.length == 0 ? sourceRoot.resolve("CodexRuntime.java") : Path.of(args[0]);
        String invocation = "main(new String[0]);pinTransferRace(false);pinTransferRace(true);"
                + "System.out.println(\"RuntimeLatestSegment: scenarios=20 failures=0; two actual pin transfer races included\");";
        if (args.length > 1) {
            if (args[1].equals("pin-accept")) invocation = "pinTransferRace(false);";
            else if (args[1].equals("pin-bookmark")) invocation = "pinTransferRace(true);";
            else throw new IllegalArgumentException("unknown synthetic scenario group");
        }
        runScenarios(runtime, "", invocation, true);
    }

    /** 旧行为组复用唯一真实 Runtime 上下文，只注入合成用例与明确的执行入口。 */
    static void runScenarios(Path runtime, String extraMethods, String invocation) throws Exception {
        runScenarios(runtime, extraMethods, invocation, false);
    }

    /** 并发组可观察临时真实模型的 epoch 读取；生产文件和模型算法均不改。 */
    private static void runScenarios(Path runtime, String extraMethods, String invocation, boolean observeEpoch) throws Exception {
        Path sourceRoot = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        String method = extract(runtime);
        String originalWindow = Files.readString(sourceRoot.resolve("TranscriptWindow.java"));
        Path scratch = Files.createTempDirectory("runtime-latest-segment-");
        try {
            Path probe = scratch.resolve("RuntimeLatestSegmentProbe.java");
            Files.writeString(probe, FIXTURE + method + SCENARIOS + extraMethods
                    + "\npublic static void runExtraScenarios() throws Exception {" + invocation + "}\n}\n");
            var compile = new ArrayList<String>(java.util.List.of("--release", "8", "-cp",
                    System.getProperty("java.class.path"), "-d", scratch.toString(), probe.toString()));
            for (String type : new String[]{"DesktopAttachment", "TranscriptText", "TranscriptWindow", "TranscriptTailRecovery", "TranscriptStore", "OutboxStore", "IndexedTranscriptStore", "TranscriptBodyStore", "TranscriptCacheBudget", "TranscriptPersistenceToken"}) {
                Path input = sourceRoot.resolve(type + ".java");
                if (observeEpoch && type.equals("TranscriptWindow")) {
                    input = scratch.resolve("TranscriptWindow.java");
                    Files.writeString(input, observedWindow(originalWindow));
                }
                compile.add(input.toString());
            }
            String[][] stubs = {
                {"Utilities", "org.telegram.messenger", "public static com.butang.codextop.RuntimeLatestSegmentProbe.Queue globalQueue;"},
                {"MessageObject", "org.telegram.messenger", "public static final int MESSAGE_SEND_STATE_SENT=0,MESSAGE_SEND_STATE_SENDING=1,MESSAGE_SEND_STATE_SEND_ERROR=2;public com.butang.codextop.RuntimeLatestSegmentProbe.TLRPC.TL_message messageOwner;public boolean wasJustSent,attachPathExists,mediaExists;public MessageObject(int a,com.butang.codextop.RuntimeLatestSegmentProbe.TLRPC.TL_message m,boolean x,boolean y){messageOwner=m;}public int getId(){return messageOwner.id;}public long getDialogId(){return messageOwner.dialog_id;}"},
                {"BuildVars", "org.telegram.messenger", "public static final boolean DEBUG_VERSION=false;"},
                {"UserConfig", "org.telegram.messenger", "public static int selectedAccount;public int lastSendMessageId=-1;static final UserConfig value=new UserConfig();public static UserConfig getInstance(int a){return value;}public void saveConfig(boolean x){}"},
                {"Log", "android.util", "public static int i(String a,String b){return 0;}public static int w(String a,String b){return 0;}"},
                {"Toast", "android.widget", "public static final int LENGTH_SHORT=0;public static Toast makeText(Object c,String s,int t){return new Toast();}public void show(){com.butang.codextop.RuntimeLatestSegmentProbe.toasts++;}"}
            };
            for (String[] stub : stubs) {
                Path file = scratch.resolve(stub[0] + ".java");
                Files.writeString(file, "package " + stub[1] + "; public class " + stub[0] + " {" + stub[2] + "}\n");
                compile.add(file.toString());
            }
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实最新段Runtime夹具编译失败");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{scratch.toUri().toURL()}, RuntimeLatestSegmentTest.class.getClassLoader())) {
                try { loader.loadClass("com.butang.codextop.RuntimeLatestSegmentProbe").getMethod("runExtraScenarios").invoke(null); }
                catch (InvocationTargetException error) { throw new AssertionError("真实最新段Runtime回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
            // 只冻结本测试实际执行的 Runtime 切片，不比较无关方法。
            if (!method.equals(extract(runtime))) throw new AssertionError("测试期间 执行的Runtime方法已改变");
            if (!originalWindow.equals(Files.readString(sourceRoot.resolve("TranscriptWindow.java"))))
                throw new AssertionError("测试期间真实 Window 源码已改变");
        }
    }

    /** 只在临时模型插入一个观察调用；去掉该调用后的完整 AST 必须和实际源码相同。 */
    private static String observedWindow(String original) {
        var parsed = StaticJavaParser.parse(original);
        var epoch = parsed.getClassByName("TranscriptWindow").orElseThrow().getMethodsByName("epoch");
        if (epoch.size() != 1) throw new AssertionError("真实 epoch 方法不唯一");
        epoch.get(0).getBody().orElseThrow().addStatement(0,
                StaticJavaParser.parseStatement("RuntimeLatestSegmentProbe.observeEpoch(this);"));
        String instrumented = parsed.toString();
        var checked = StaticJavaParser.parse(instrumented);
        checked.getClassByName("TranscriptWindow").orElseThrow().getMethodsByName("epoch").get(0)
                .getBody().orElseThrow().getStatements().remove(0);
        if (!checked.toString().equals(StaticJavaParser.parse(original).toString()))
            throw new AssertionError("测试 epoch 观察器改变了模型其他语义");
        System.out.println("modelInstrumentation=temporary epoch observer only; full remaining AST unchanged");
        return instrumented;
    }

    /** 提取唯一正式类型和实际方法，其他代理可修改无关方法而不污染本专项。 */
    private static String extract(Path path) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var owner = StaticJavaParser.parse(path).getClassByName("CodexRuntime").orElseThrow();
        StringBuilder result = new StringBuilder();
        for (var method : owner.getMethodsByName("isAccountCurrent")) result.append(method).append("\n");
        for (var method : owner.getMethodsByName("publishHistoryRoot")) result.append(method).append("\n");
        for (String name : new String[]{"HistoryView", "HistoryPage", "HistoryBookmark"}) {
            var types=owner.getMembers().stream().filter(n->n instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)n).getNameAsString().equals(name)).toList();
            if(types.size()!=1)throw new AssertionError("正式类型不存在或不唯一："+name);
            result.append(types.get(0)).append('\n');
        }
        for (String name : new String[]{"openHistoryView","pauseHistoryView","closeHistoryView","historyViewCurrent","isHistoryPageCurrent","acceptLatestHistory","resolveHistoryBookmark","requestLatestHistory","offerLatestHistory","deliverHistoryPage","finishAcceptedHistory","loadMessages","watchConversation","stopWatching","prefetchDialogs","saveHistory","batchEchoed","pendingMessage","restoredPending","confirmPendingEcho","historyMessage","historyObject"}) {
            var methods=owner.getMethodsByName(name);if(methods.isEmpty())throw new AssertionError("正式方法不存在："+name);
            for(var method:methods)result.append(method).append('\n');
        }
        // 新协调器完整提取真实字段、类型与方法；旧基线没有该片时仍能运行相同行为断言。
        for (var member : owner.getMembers()) {
            if (member.isClassOrInterfaceDeclaration() && member.asClassOrInterfaceDeclaration().getNameAsString().startsWith("LocalHistory"))
                result.append(member).append('\n');
            if (member.isMethodDeclaration() && (member.asMethodDeclaration().getNameAsString().contains("LocalHistory")
                    || member.asMethodDeclaration().getNameAsString().startsWith("localHistory")))
                result.append(member).append('\n');
            // 仅提取存在的真实归档收尾入口，旧前像不补清理算法替身。
            if (member.isMethodDeclaration() && member.asMethodDeclaration().getNameAsString().equals("scheduleHistoryArchivePrune"))
                result.append(member).append('\n');
            // 连接等待专项新增的真实登记也随watch提取，旧基线没有时不补业务替身。
            if (member.isClassOrInterfaceDeclaration() && member.asClassOrInterfaceDeclaration().getNameAsString().equals("WatchConnectionWait"))
                result.append(member).append('\n');
            else if (member.isFieldDeclaration() && member.asFieldDeclaration().getVariables().stream().anyMatch(v -> v.getNameAsString().equals("watchConnectionWait")))
                result.append(member).append('\n');
            if (member.isClassOrInterfaceDeclaration() && (member.asClassOrInterfaceDeclaration().getNameAsString().startsWith("ColdHistory")
                    || member.asClassOrInterfaceDeclaration().getNameAsString().equals("InitialLoadWaiter")))
                result.append(member).append('\n');
            else if (member.isMethodDeclaration() && (member.asMethodDeclaration().getNameAsString().contains("ColdHistory")
                    || member.asMethodDeclaration().getNameAsString().equals("deliverWatchedHistory")
                    || member.asMethodDeclaration().getNameAsString().equals("completeInitialLoadsFromLatest")
                    || member.asMethodDeclaration().getNameAsString().equals("offerFirstVisibleHistory")))
                result.append(member).append('\n');
            else if (member.isFieldDeclaration() && member.asFieldDeclaration().getVariables().stream().anyMatch(v -> v.getNameAsString().equals("coldHistoryRequests")))
                result.append(member).append('\n');
        }
        result.append("/** 每例仅清理协调器实例表，不替代正式算法。 */ static void resetColdHistoryFixture(){");
        if (owner.getFieldByName("coldHistoryRequests").isPresent()) result.append("coldHistoryRequests.clear();");
        result.append("}\n");
        return result.toString();
    }

    // 替身只承载排程、网络及Android字段；选段/接收/回显清理/持久化执行正式代码。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        import java.io.*;
        import java.nio.file.*;
        import com.google.gson.*;
        import org.telegram.messenger.Utilities;
        import org.telegram.messenger.MessageObject;
        public final class RuntimeLatestSegmentProbe {
            static boolean loggingOut;static long accountGeneration=1,watchedDialog,watchGeneration,now;
            static final Map<Integer,HistoryView> historyViews=new java.util.concurrent.ConcurrentHashMap<>();
            static final java.util.concurrent.atomic.AtomicLong historyTokens=new java.util.concurrent.atomic.AtomicLong();
            static final Map<Long,String> remoteIds=new HashMap<>(),dialogMachines=new HashMap<>();
            static final Map<Long,TranscriptWindow> histories=new HashMap<>();
            static final Map<String,MessageObject> pendingMessages=new HashMap<>();
            static Queue transcriptQueue=new Queue(),historyQueue=new Queue(),prefetchQueue=new Queue(),statusQueue=new Queue();static Runnable statusPoll;
            static final Map<Long,Long> prefetchedRevisions=new HashMap<>();static final Set<Long> prefetching=new HashSet<>();
            static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final class PasswordLogin {static final class Session {}}
            static PasswordLogin.Session session=new PasswordLogin.Session();
            static DesktopConnection current;static Path directory;static TranscriptStore store;static OutboxStore outbox;
            static int previews,failures,observations,saves;public static int toasts;
            static volatile java.util.function.BiConsumer<TranscriptWindow,Thread> epochObserver;
            /** 临时模型仅观察读取边界，未启用时和真实 epoch 返回完全相同。 */
            static void observeEpoch(TranscriptWindow window){java.util.function.BiConsumer<TranscriptWindow,Thread> observer=epochObserver;if(observer!=null)observer.accept(window,Thread.currentThread());}
            static final List<Event> events=new ArrayList<>();
            public static final class Queue {
                final ArrayDeque<Runnable> ready=new ArrayDeque<>();final ArrayList<Delayed> delayed=new ArrayList<>();final ArrayList<Long> delays=new ArrayList<>();
                static final class Delayed {long due;Runnable run;Delayed(long d,Runnable r){due=d;run=r;}}
                /** 按原入队顺序执行，不提前越过网络或UI边界。 */public synchronized void postRunnable(Runnable r){ready.add(r);}
                /** 原延迟只进入虚拟时钟，不增加真实计时器。 */public synchronized void postRunnable(Runnable r,long delay){delays.add(delay);if(delay==0)ready.add(r);else delayed.add(new Delayed(now+delay,r));}
                public void cancelRunnable(Runnable r){ready.remove(r);delayed.removeIf(d->d.run==r);}
                /** 每次只执行一个真实排程边界供迟到回包用例暂停。 */void next(){check(!ready.isEmpty(),"没有待执行边界");ready.remove().run();}
                /** 显式唤醒原方法的下一轮。 */void wake(){if(!ready.isEmpty())return;check(!delayed.isEmpty(),"没有原watch唤醒");long due=Long.MAX_VALUE;for(Delayed d:delayed)due=Math.min(due,d.due);now=due;for(Delayed d:new ArrayList<>(delayed))if(d.due<=now){ready.add(d.run);delayed.remove(d);}}
                /** 只读正式方法最近安排的延迟，供原失败与停止边界断言。 */long lastDelay(){check(!delays.isEmpty(),"没有原调度延迟");return delays.get(delays.size()-1);}
            }
            static final class AndroidUtilities {static Queue ui=new Queue();static void runOnUIThread(Runnable r){ui.postRunnable(r);}static void runOnUIThread(Runnable r,long d){ui.postRunnable(r,d);}}
            static final class ApplicationLoader {static final Object applicationContext=new Object();static boolean mainInterfacePaused;}
            public static final class TLRPC {
                public static final class TL_message {public int id,local_id,date,flags,send_state;public long dialog_id;public String message;public boolean out,unread;public TL_peerUser peer_id,from_id;public TL_messageMediaEmpty media;public Map<String,String> params;}
                public static final class TL_peerUser {public long user_id;}
                public static final class TL_messageMediaEmpty {}
            }
            static final class Event {final int type;final Object[] args;Event(int t,Object[] a){type=t;args=a;}}
            static final class NotificationCenter {
                static final int messagesDidLoad=1,didReceiveNewMessages=2,messageReceivedByServer=3;static final NotificationCenter value=new NotificationCenter();
                static NotificationCenter getInstance(int a){return value;}
                /** 包括空页在内，原通知末尾元数据完整记录。 */void postNotificationName(int type,Object...args){events.add(new Event(type,args));}
            }
            static final class DesktopConnection {
                final String machineId="synthetic-machine";JsonObject latest;Runnable onRequest;boolean connected=true;
                final Map<String,ArrayDeque<Object>> older=new HashMap<>();final ArrayDeque<Object> after=new ArrayDeque<>();final ArrayList<String> requests=new ArrayList<>();
                /** 最新页不替Runtime自动遍历中间历史。 */JsonObject transcript(String remote)throws Exception{recordRequest("latest");if(onRequest!=null){Runnable action=onRequest;onRequest=null;action.run();}if(latest==null)throw new IOException("synthetic latest unavailable");return latest.deepCopy();}
                JsonObject transcript(String remote,String cursor)throws Exception{if(cursor==null)return transcript(remote);recordRequest("older:"+cursor);return answer(older.get(cursor));}
                /** 保持连接能力为显式外部事实；不会改变实际调度算法。 */boolean isConnected(){return connected;}
                /** 双ticket测试只并发调用外部RPC记录，记录本身保持线程安全。 */synchronized void recordRequest(String value){requests.add(value);}
                JsonObject readAfter(String remote,String cursor)throws Exception{recordRequest("after:"+cursor);return answer(after);}
                JsonObject answer(ArrayDeque<Object> q)throws Exception{if(q==null||q.isEmpty())throw new IOException("synthetic page unavailable");Object v=q.remove();if(v instanceof Exception)throw (Exception)v;return ((JsonObject)v).deepCopy();}
                void older(String cursor,JsonObject p){older.put(cursor,new ArrayDeque<>(Collections.singletonList(p)));}
            }
            static final class AttachmentMessages {
                /** 本片输入都是文字，任何附件调用都必须显式失败。 */static void apply(TLRPC.TL_message m,DesktopAttachment a,File f){throw new AssertionError("unexpected attachment");}
                static void applyPending(TLRPC.TL_message m,DesktopAttachment.Pending a){throw new AssertionError("unexpected pending attachment");}
                static void applySelected(TLRPC.TL_message m,OutboxStore.Selection a){throw new AssertionError("unexpected selected attachment");}
            }
            /** 原回归只保留默认关闭边界；诊断helper由独立真实方法专项执行。 */
            static long beginHistoryTrace(String phase){return -1;}
            /** 默认关闭不读钟或改变原队列，日志断言不在此原业务夹具内。 */
            static void traceHistoryDuration(String phase,long startedAt){}
            static boolean loggedIn(){return true;}static boolean ownsConversation(long id){return id==42;}

            /** 连接替身沿正式machine归属边界，不让错误机器映射仍返回旧连接。 */static DesktopConnection dialogConnection(long id){return current!=null&&current.machineId.equals(dialogMachines.get(id))?current:null;}
            /** 合成候选仅映射已知对话，账号或来源不匹配不能绑定。 */static long bindDialog(String machine,String remote,PasswordLogin.Session owner,long epoch){return owner==session&&isAccountCurrent(epoch)&&machine.equals(dialogMachines.get(42L))&&remote.equals(remoteIds.get(42L))?42:0;}
            /** 预取预算固定为原合成场景所需两项，不参与产品计算。 */static int recentDialogLimit(){return 2;}
            static void watchStatus(int a,long id,long g){}static void refreshDialogs(int a){}static void releaseObservation(){observations++;}
            static void logTranscriptFailure(String stage,Exception error,JsonObject p){failures++;}
            static void publishHistoryPreview(int a,long epoch,long id,String r,String m,DesktopConnection c,TranscriptWindow h){previews++;}
            /** 只观察实际保存所取的文件边界，仍返回真实 Store 执行读写。 */static TranscriptStore transcriptStore(long id){saves++;return store;}
            static TranscriptWindow readHistory(long id,String r){try{return store.read(r);}catch(IOException e){throw new AssertionError(e);}}
            static OutboxStore outboxStore(long id){return outbox;}
            static boolean isAttachmentMessage(MessageObject m){return false;}static File attachmentFile(MessageObject m){return null;}
            static File attachmentDirectory(long id){throw new AssertionError("unexpected attachment directory");}
            static File cachedAttachment(File d,String id,DesktopAttachment a){throw new AssertionError("unexpected attachment cache");}
        """;

    private static final String SCENARIOS = """
            /** 来源成员只含合成文字，原用户回显保留真正localId形状。 */static JsonObject item(String id,String local){JsonObject i=new JsonObject(),raw=new JsonObject(),body=new JsonObject();i.addProperty("id",id);i.addProperty("createdAtMs",1000);if(local!=null)i.addProperty("localId",local);raw.addProperty("role",local==null?"agent":"user");body.addProperty("type","text");body.addProperty("text","synthetic");raw.add("content",body);i.add("raw",raw);return i;}
            /** 协议仍是旧到新，编号和epoch不由夹具计算。 */static JsonObject page(String ids,boolean more,String cursor,String tail){JsonObject p=new JsonObject();JsonArray a=new JsonArray();if(!ids.isEmpty())for(String id:ids.split(","))a.add(item(id,null));p.add("items",a);p.addProperty("hasMore",more);p.addProperty("historyAvailability","available");p.addProperty("nextCursor",cursor);p.addProperty("tailCursor",tail);return p;}
            static TranscriptWindow cache(String ids)throws Exception{TranscriptWindow w=new TranscriptWindow();w.prepend(page(ids,true,"old-older","old-tail"));return w;}
            /** 每例隔离队列、缓存与临时文件，绝不读正式账号目录。 */static void reset(TranscriptWindow w)throws Exception{loggingOut=false;accountGeneration=1;watchedDialog=watchGeneration=now=0;historyViews.clear();histories.clear();histories.put(42L,w);remoteIds.clear();remoteIds.put(42L,"synthetic-remote");dialogMachines.clear();dialogMachines.put(42L,"synthetic-machine");pendingMessages.clear();prefetchedRevisions.clear();prefetching.clear();desktopConnections.clear();resetColdHistoryFixture();session=new PasswordLogin.Session();ApplicationLoader.mainInterfacePaused=false;events.clear();previews=failures=observations=saves=toasts=0;Utilities.globalQueue=new Queue();transcriptQueue=new Queue();historyQueue=new Queue();prefetchQueue=new Queue();statusQueue=new Queue();AndroidUtilities.ui=new Queue();statusPoll=null;current=new DesktopConnection();desktopConnections.put(current.machineId,current);directory=Files.createTempDirectory("latest-runtime-data-").toRealPath();store=new TranscriptStore(directory.resolve("history").toFile(),"synthetic-server","synthetic-account","synthetic-machine");outbox=new OutboxStore(directory.resolve("outbox").toFile(),"synthetic-server","synthetic-account","synthetic-machine");store.write("synthetic-remote",w);}
            static void cleanup()throws Exception{if(directory!=null)try(java.util.stream.Stream<Path> paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}directory=null;}
            /** 只消费立即工作，周期下一轮须场景显式唤醒。 */static void pump(){int guard=100;while(!Utilities.globalQueue.ready.isEmpty()||!transcriptQueue.ready.isEmpty()||!historyQueue.ready.isEmpty()||!prefetchQueue.ready.isEmpty()||!AndroidUtilities.ui.ready.isEmpty()){check(--guard>0,"排程没有让出");if(!Utilities.globalQueue.ready.isEmpty())Utilities.globalQueue.next();else if(!transcriptQueue.ready.isEmpty())transcriptQueue.next();else if(!historyQueue.ready.isEmpty())historyQueue.next();else if(!prefetchQueue.ready.isEmpty())prefetchQueue.next();else AndroidUtilities.ui.next();}}
            static void ui(){while(!AndroidUtilities.ui.ready.isEmpty())AndroidUtilities.ui.next();}
            static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
            static String ids(TranscriptWindow w)throws IOException{StringJoiner s=new StringJoiner(",");for(TranscriptWindow.Entry r:w.before(0,100))s.add(r.message.id);return s.toString();}
            @SuppressWarnings("unchecked") static String rows(Event e){StringJoiner s=new StringJoiner(",");for(MessageObject r:(ArrayList<MessageObject>)e.args[e.type==NotificationCenter.messagesDidLoad?2:1])s.add(r.messageOwner.params.getOrDefault("codexSourceId",r.messageOwner.params.get("codexLocalId")));return s.toString();}
            static HistoryPage metadata(Event e){check(e.type==NotificationCenter.messagesDidLoad&&e.args.length>15&&e.args[15] instanceof HistoryPage,"原通知缺epoch身份");return (HistoryPage)e.args[15];}
            static Event replacement(){for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad&&metadata(e).replaceLatest)return e;throw new AssertionError("最新页没有替换候选");}
            static Event normal(){for(int i=events.size()-1;i>=0;i--){Event e=events.get(i);if(e.type==NotificationCenter.messagesDidLoad&&!metadata(e).replaceLatest)return e;}throw new AssertionError("没有普通历史页");}
            /** 经过真正初始加载绑定旧缓存，不直接设置view内部窗口。 */static long openCached()throws Exception{long t=openHistoryView(0,42,7);loadMessages(0,42,3,0,7,2,1,0);pump();check(rows(normal()).equals("B,A"),"旧缓存初始页错误 actual="+rows(normal())+" requests="+current.requests+" failures="+failures+" notices="+events.size());events.clear();return t;}
            /** 明确积压使一次warm探测转原latest；不全局替换readAfter默认行为。 */
            static void openingBacklog(){JsonObject p=page("probe-uncommitted",false,"probe-next",null);p.addProperty("truncated",true);p.addProperty("truncationReason","page_limit");current.after.add(p);}
            /** 先确认积压，再沿原latest生成待接收的离散段。 */
            static HistoryPage offer()throws Exception{openingBacklog();current.latest=page("Y,Z",true,"latest-older","latest-tail");watchConversation(0,42);pump();Event e=replacement();check(rows(e).equals("Z,Y"),"候选不是最新一页");check(histories.get(42L).findSegment("probe-uncommitted",null)==null,"探测的未完整增量被合入");return metadata(e);}
            /** 离散最新立即可交付，旧view拒收后仍使用自己的旧游标。 */static void latestOfferAndOldView()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{long t=openCached();HistoryPage p=offer();check(current.requests.equals(Arrays.asList("after:old-tail","latest")),"未在一次探测后直接latest");check(p.token==t&&!p.fromEpoch.equals(p.toEpoch)&&historyViews.get(7).window==old,"候选身份错或未接受已切view");events.clear();current.older("old-older",page("older",false,null,null));loadMessages(0,42,3,old.before(0,2).get(1).id,7,0,2,0);pump();check(rows(normal()).equals("older")&&metadata(normal()).fromEpoch.equals(old.epoch()),"旧view不能沿自己epoch分页");check(current.requests.equals(Arrays.asList("after:old-tail","latest","older:old-older")),"旧view用了新cursor");check(store.read("synthetic-remote").findSegment("A",null)!=null,"旧段没有落盘");check(acceptLatestHistory(p)&&historyViews.get(7).window==histories.get(42L),"接受未切唯一正式owner");events.clear();loadMessages(0,42,3,0,7,2,3,0);pump();check(rows(normal()).equals("Z,Y")&&metadata(normal()).fromEpoch.equals(p.toEpoch),"接收后还读旧段");System.out.println("PASS latest immediate / old view pagination / accept");}finally{cleanup();}}
            /** 接收新段后，已排队UI交付须被真实窗口守卫拦住。 */static void queuedUiAfterAccept()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{openCached();HistoryPage p=offer();events.clear();loadMessages(0,42,2,0,7,2,2,0);Utilities.globalQueue.next();check(!AndroidUtilities.ui.ready.isEmpty(),"没有到UI边界");check(acceptLatestHistory(p),"候选不可接收");ui();check(events.isEmpty(),"旧epoch UI仍发布");System.out.println("PASS queued old UI rejected");}finally{cleanup();}}
            /** 停在真实网络/合并之间，接收后不能污染任何窗口或发错误页。 */static void queuedNetworkAfterAccept()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{openCached();HistoryPage p=offer();events.clear();current.older("old-older",page("older",false,null,null));loadMessages(0,42,3,old.before(0,2).get(1).id,7,0,2,0);Utilities.globalQueue.next();historyQueue.next();JsonObject before=old.snapshot();check(acceptLatestHistory(p),"候选不可接收");pump();check(events.isEmpty()&&before.equals(old.snapshot())&&ids(histories.get(42L)).equals("Z,Y"),"旧网络页回写或发布");System.out.println("PASS queued old network rejected");}finally{cleanup();}}
            /** 暂停重开新token隔离旧候选、旧交付及旧close。 */static void pauseAndReopen()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{long first=openCached();HistoryPage stale=offer();events.clear();loadMessages(0,42,2,0,7,2,2,0);Utilities.globalQueue.next();pauseHistoryView(7,first);long second=openHistoryView(0,42,7);check(first!=second&&!acceptLatestHistory(stale),"重开接受旧token");closeHistoryView(7,first);ui();check(events.isEmpty()&&historyViews.get(7).token==second,"旧回调或close影响新view");loadMessages(0,42,2,0,7,2,3,0);pump();check(rows(normal()).equals("B,A")&&metadata(normal()).token==second,"恢复丢旧阅读段");System.out.println("PASS pause/reopen identity");}finally{cleanup();}}
            /** 空最新页不通过空候选清掉旧UI。 */static void emptyLatestKeepsView()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{openCached();openingBacklog();current.latest=page("",true,"empty-older","empty-tail");watchConversation(0,42);pump();for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad)check(!metadata(e).replaceLatest,"空latest替换旧UI");check(historyViews.get(7).window==old&&ids(old).equals("B,A"),"空latest清旧段");check(current.requests.equals(Arrays.asList("after:old-tail","latest","older:empty-older")),"空页没有保留一次探测及原older入口");System.out.println("PASS empty latest preserves view");}finally{cleanup();}}
            /** 真实Outbox/Store确认跨段旧回显不重放，无回显unknown仍保留。 */static void echoedOutboxAcrossSegments()throws Exception{TranscriptWindow old=new TranscriptWindow();JsonObject initial=page("",true,"old-older","old-tail");initial.getAsJsonArray("items").add(item("A","echoed"));initial.getAsJsonArray("items").add(item("B",null));old.prepend(initial);reset(old);try{outbox.put(new OutboxStore.Item("echoed","synthetic-remote","synthetic",-7,1));outbox.markSubmissionUncertain("echoed",true);outbox.put(new OutboxStore.Item("still-unknown","synthetic-remote","synthetic",-8,1));outbox.markSubmissionUncertain("still-unknown",true);openCached();HistoryPage p=offer();check(acceptLatestHistory(p),"候选不可接收");check(outbox.get("echoed")==null&&outbox.get("still-unknown")!=null&&outbox.get("still-unknown").submissionUncertain,"旧回显未清或无回显unknown误删");check(store.read("synthetic-remote").allOutgoingLocalIds().contains("echoed"),"删Outbox之前未保存旧回显");events.clear();loadMessages(0,42,3,0,7,2,3,0);pump();for(Event e:events)if(e.type==NotificationCenter.didReceiveNewMessages)check(!rows(e).contains("echoed"),"旧回显重新恢复为unknown气泡");System.out.println("PASS cross segment echo / unknown retained");}finally{cleanup();}}
            /** 只按来源找原段，绝不按另一段相同native整数代替书签。 */static void sourceBookmarkKeepsOriginalSegment()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{long t=openCached();HistoryPage p=offer();check(acceptLatestHistory(p),"候选不可接收");final HistoryBookmark[] value={null};resolveHistoryBookmark(0,42,7,t,"A",null,b->value[0]=b);pump();check(value[0]!=null&&value[0].messageId==old.findSource("A",null).id&&value[0].epoch.equals(old.epoch())&&historyViews.get(7).window==old,"来源书签误指新段同编号");System.out.println("PASS source bookmark original epoch");}finally{cleanup();}}
            /** 通知中心只登记候选而延后UI消费；拒收不确认原负编号，真正接受后才补回pending。 */static void deferredAcceptanceRestoresPending()throws Exception{TranscriptWindow old=cache("A,B");reset(old);try{outbox.put(new OutboxStore.Item("new-echo","synthetic-remote","synthetic",-7,1));outbox.markSubmissionUncertain("new-echo",true);outbox.put(new OutboxStore.Item("unknown","synthetic-remote","synthetic",-8,1));outbox.markSubmissionUncertain("unknown",true);openCached();MessageObject original=pendingMessages.get("new-echo");check(original!=null&&original.getId()==-7,"初始pending没真实恢复");current.latest=page("Y",true,"latest-older","latest-tail");current.latest.getAsJsonArray("items").add(item("Z","new-echo"));openingBacklog();watchConversation(0,42);pump();HistoryPage p=metadata(replacement());for(Event e:events)check(e.type!=NotificationCenter.messageReceivedByServer&&e.type!=NotificationCenter.didReceiveNewMessages,"未接收候选就确认或重放pending");check(pendingMessages.get("new-echo")==original,"拒收候选提前删除旧pending对象");check(acceptLatestHistory(p),"延迟候选不能接受");check(pendingMessages.get("new-echo")==original,"接收过程未清屏前就确认pending");AndroidUtilities.ui.wake();pump();boolean confirmed=false,restored=false;for(Event e:events){if(e.type==NotificationCenter.messageReceivedByServer){check(((Integer)e.args[0])==-7,"确认改了原负编号");confirmed=true;}if(e.type==NotificationCenter.didReceiveNewMessages){check(rows(e).equals("unknown"),"延后恢复丢unknown或重放已回显成员");@SuppressWarnings("unchecked") ArrayList<MessageObject> delivered=(ArrayList<MessageObject>)e.args[1];check(!delivered.get(0).wasJustSent&&"true".equals(delivered.get(0).messageOwner.params.get("codexSendUncertain"))&&e.args.length>4&&isHistoryPageCurrent((HistoryPage)e.args[4]),"恢复pending丢状态或缺当前段身份");restored=true;}}check(confirmed&&restored&&!pendingMessages.containsKey("new-echo")&&outbox.get("unknown").submissionUncertain,"延后接受未确认或未恢复pending");System.out.println("PASS deferred acceptance / rejected candidate / pending restore");}finally{cleanup();}}
            /** 同段显式回最新也作废旧页、解析回调和已经通知的元数据。 */
            static void sameEpochLatestIntent()throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    long token=openCached();loadMessages(0,42,2,0,7,2,2,0);pump();HistoryPage published=metadata(normal());
                    check(isHistoryPageCurrent(published),"原同段通知不当前");events.clear();
                    loadMessages(0,42,2,0,7,2,3,0);Utilities.globalQueue.next();
                    final HistoryBookmark[] bookmark={null};resolveHistoryBookmark(0,42,7,token,"A",null,b->bookmark[0]=b);Utilities.globalQueue.next();
                    requestLatestHistory(0,42,7,token);check(!isHistoryPageCurrent(published),"同段回最新未作废旧meta");
                    ui();check(events.isEmpty()&&bookmark[0]==null,"同段回最新仍交付旧页或resolver");pump();
                    HistoryPage latest=metadata(replacement());check(latest.fromEpoch.equals(latest.toEpoch)&&acceptLatestHistory(latest),"同段显式最新不能接受");
                    System.out.println("PASS same epoch intent invalidates old page/resolver");
                }finally{cleanup();}
            }
            /** 先消费accept即时工作再唤原定时；新首屏只沿真实新tail续读且保留显示段身份。 */
            static void followsNewTail()throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    openCached();check(acceptLatestHistory(offer()),"候选不可接收");pump();events.clear();
                    current.after.add(page("N",false,"next-tail",null));Utilities.globalQueue.wake();pump();
                    check(current.requests.equals(Arrays.asList("after:old-tail","latest","after:latest-tail"))&&ids(histories.get(42L)).equals("N,Z,Y")&&histories.get(42L).tailCursor.equals("next-tail"),"首屏后没有沿新tail增量");
                    boolean delivered=false;for(Event e:events)if(e.type==NotificationCenter.didReceiveNewMessages){check(rows(e).equals("N")&&e.args.length>4&&isHistoryPageCurrent((HistoryPage)e.args[4]),"增量身份或顺序错误");delivered=true;}
                    check(delivered,"真实新增没有通知");System.out.println("PASS latest followed by genuine tail increment");
                }finally{cleanup();}
            }
            /** accept即时工作完成后注入精确拒绝；只失效tail，下轮latest不提前改显示段。 */
            static void rejectedNewTailRecoversLatest()throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    openCached();check(acceptLatestHistory(offer()),"候选不可接收");pump();TranscriptWindow shown=historyViews.get(7).window;events.clear();
                    JsonObject rejected=page("untrusted",false,"bad-next",null);rejected.addProperty("truncationReason","source_discontinuity");current.after.add(rejected);Utilities.globalQueue.wake();pump();
                    check(shown.tailCursor==null&&ids(shown).equals("Z,Y")&&events.isEmpty()&&store.read("synthetic-remote").tailCursor==null,"拒绝响应发布、改正文或未保存失效");
                    current.latest=page("S,T",true,"second-older","second-tail");Utilities.globalQueue.wake();pump();
                    check(current.requests.equals(Arrays.asList("after:old-tail","latest","after:latest-tail","latest"))&&rows(replacement()).equals("T,S")&&historyViews.get(7).window==shown,"拒绝后没用latest或未接受已切UI");
                    System.out.println("PASS rejected tail recovers latest and retains shown segment");
                }finally{cleanup();}
            }
            /** 后台解析后实际桥接旧页并保存，prune不能删除尚未提交的原段。 */
            static void bookmarkPinnedAcrossPrune()throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    long token=openCached();check(acceptLatestHistory(offer()),"候选不可接收");
                    if(!AndroidUtilities.ui.delayed.isEmpty()){AndroidUtilities.ui.wake();pump();}events.clear();TranscriptWindow root=histories.get(42L);
                    final HistoryBookmark[] bookmark={null};resolveHistoryBookmark(0,42,7,token,"A",null,b->bookmark[0]=b);Utilities.globalQueue.next();
                    check(historyViews.get(7).bookmarkWindow==old,"后台resolver没有pin原段");
                    current.older("latest-older",page("B,X",true,"bridge-next",null));loadMessages(0,42,3,root.before(0,2).get(1).id,7,0,2,0);
                    Utilities.globalQueue.next();historyQueue.next();Utilities.globalQueue.next();
                    check(root.containsSegment(old)&&store.read("synthetic-remote").segment(old.epoch())!=null,"save/prune删待提交书签段");
                    Utilities.globalQueue.next();ui();
                    check(bookmark[0]!=null&&bookmark[0].epoch.equals(old.epoch())&&historyViews.get(7).window==old&&historyViews.get(7).bookmarkWindow==null,"resolver提交丢段或未释放pin");
                    events.clear();loadMessages(0,42,1,bookmark[0].messageId,7,3,3,0);pump();
                    check(rows(normal()).equals("A")&&metadata(normal()).fromEpoch.equals(old.epoch()),"pin提交后精确load读不到原消息");
                    System.out.println("PASS resolver pin across actual bridge/save/prune");
                }finally{cleanup();}
            }
            /** 身份或显示段取消后必须释放未提交书签pin，并拒绝原解析回调。 */
            static void cancelledBookmarkPin(String kind)throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    long token=openCached();HistoryPage p=offer();final HistoryBookmark[] bookmark={null};
                    resolveHistoryBookmark(0,42,7,token,"A",null,b->bookmark[0]=b);Utilities.globalQueue.next();
                    check(historyViews.get(7).bookmarkWindow==old,"取消前没有pin");
                    if(kind.equals("pause"))pauseHistoryView(7,token);else if(kind.equals("latest"))requestLatestHistory(0,42,7,token);else check(acceptLatestHistory(p),"取消测试候选不能接受");
                    check(historyViews.get(7).bookmarkWindow==null,"取消未释放pin: "+kind);ui();check(bookmark[0]==null,"取消后resolver仍提交: "+kind);
                    System.out.println("PASS cancelled bookmark pin "+kind);
                }finally{cleanup();}
            }
            /** 同阅读代次并发解析只允许最后请求提交，前者不清掉后者pin。 */
            static void concurrentBookmarks()throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    long token=openCached();HistoryPage p=offer();check(acceptLatestHistory(p),"候选不可接收");if(!AndroidUtilities.ui.delayed.isEmpty()){AndroidUtilities.ui.wake();pump();}
                    final HistoryBookmark[] first={null},second={null};resolveHistoryBookmark(0,42,7,token,"A",null,b->first[0]=b);Utilities.globalQueue.next();
                    resolveHistoryBookmark(0,42,7,token,"Y",null,b->second[0]=b);Utilities.globalQueue.next();ui();
                    check(first[0]==null&&second[0]!=null&&second[0].epoch.equals(p.toEpoch)&&historyViews.get(7).window==histories.get(42L)&&historyViews.get(7).bookmarkWindow==null,"并发resolver迟到回写或pin未释放");
                    System.out.println("PASS concurrent bookmark last request only");
                }finally{cleanup();}
            }
            /** 同一个空投影root补出第一条正文时也交付替换，不能被view==root短路。 */
            static void emptyRootBecomesVisible()throws Exception{
                TranscriptWindow root=new TranscriptWindow();root.prepend(page("",true,"cache-older","cache-tail"));reset(root);
                try{
                    current=null;long token=openHistoryView(0,42,7);loadMessages(0,42,3,0,7,2,1,0);pump();
                    check(rows(normal()).isEmpty()&&historyViews.get(7).window==root,"空root初始绑定错误");events.clear();
                    current=new DesktopConnection();current.latest=page("",true,"visible-older","visible-tail");current.older("visible-older",page("Y",false,null,null));
                    watchConversation(0,42);pump();HistoryPage p=metadata(replacement());
                    check(current.requests.equals(Arrays.asList("latest","older:visible-older"))&&rows(replacement()).equals("Y")&&p.token==token&&p.fromEpoch.equals(p.toEpoch)&&histories.get(42L)==root,"同root首条正文未触发可显示替换");
                    check(acceptLatestHistory(p),"同root可见首屏不能接受");System.out.println("PASS empty same root first visible body replacement");
                }finally{cleanup();}
            }
            /** 接收即时收尾后再推进延后通知边界；新增正文与待发仍从本地补齐且不加网络。 */
            static void deferredNewTailAndOutbox()throws Exception{
                TranscriptWindow old=cache("A,B");reset(old);
                try{
                    openCached();HistoryPage candidate=offer();
                    current.after.add(page("N",false,"newer-tail",null));Utilities.globalQueue.wake();pump();
                    check(rows(replacement()).equals("Z,Y")&&ids(histories.get(42L)).equals("N,Z,Y"),"候选快照与后续本地增量没有分开");
                    outbox.put(new OutboxStore.Item("late-pending","synthetic-remote","synthetic",-11,2));outbox.markSubmissionUncertain("late-pending",true);
                    int requests=current.requests.size();check(acceptLatestHistory(candidate),"延后候选不可接受");pump();AndroidUtilities.ui.wake();AndroidUtilities.ui.next();Utilities.globalQueue.next();
                    // 正式完成器读取落盘快照后、UI补齐前又新建原待发对象，必须同批复用而不丢失。
                    MessageObject newest=new MessageObject(0,pendingMessage(42,-12,3,"synthetic","ui-new",0),true,false);newest.wasJustSent=true;newest.messageOwner.send_state=MessageObject.MESSAGE_SEND_STATE_SENDING;pendingMessages.put("ui-new",newest);ui();
                    boolean tail=false,pending=false,uiPending=false;
                    for(Event e:events)if(e.type==NotificationCenter.didReceiveNewMessages){
                        check(e.args.length>4&&isHistoryPageCurrent((HistoryPage)e.args[4]),"补齐通知不是已接受段身份");
                        @SuppressWarnings("unchecked") ArrayList<MessageObject> delivered=(ArrayList<MessageObject>)e.args[1];
                        for(MessageObject m:delivered){
                            if("N".equals(m.messageOwner.params.get("codexSourceId")))tail=true;
                            if("late-pending".equals(m.messageOwner.params.get("codexLocalId"))){check(m.getId()==-11&&m.messageOwner.date==2&&!m.wasJustSent&&"true".equals(m.messageOwner.params.get("codexSendUncertain")),"新待发恢复改编号/时间/unknown事实");pending=true;}
                            if("ui-new".equals(m.messageOwner.params.get("codexLocalId"))){check(m==newest&&m.getId()==-12&&m.messageOwner.date==3&&m.messageOwner.send_state==MessageObject.MESSAGE_SEND_STATE_SENDING&&!m.wasJustSent,"晚UI新待发未复用原对象/原状态");uiPending=true;}
                        }
                    }
                    check(tail&&pending&&uiPending&&current.requests.size()==requests&&outbox.get("late-pending").submissionUncertain,"延后接受漏本地新尾或新待发，或另发网络");
                    System.out.println("PASS deferred candidate includes newer tail and new outbox without network");
                }finally{cleanup();}
            }
            /** 有界等待实际线程边界，不靠睡眠猜测保存/接受先后。 */
            static void pinAwait(java.util.concurrent.CountDownLatch latch){
                try{check(latch.await(5,java.util.concurrent.TimeUnit.SECONDS),"真实pin边界超时");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            }
            /** 两个真实转移入口分别与保存并发：旧代码漏pin，新代码短锁保证同一快照。 */
            static void pinTransferRace(boolean bookmark)throws Exception{
                TranscriptWindow a=cache("A");reset(a);Thread saver=null,receiver=null;
                java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1),completed=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
                try{
                    current=null;long token=openHistoryView(0,42,7);loadMessages(0,42,3,0,7,2,1,0);pump();events.clear();
                    HistoryView view=historyViews.get(7);check(view.window==a,"race没有实际绑定旧显示段");
                    TranscriptWindow b=a.acceptLatest(page("B",true,"b-older","b-tail"));histories.put(42L,b);
                    HistoryPage candidate=null;
                    if(!bookmark){offerLatestHistory(view,b,true);pump();candidate=metadata(replacement());check(view.proposedWindow==b,"race候选没有真实交付");}
                    TranscriptWindow root=b.acceptLatest(page("C",true,"c-older","c-tail"));histories.put(42L,root);
                    String bEpoch=b.epoch();int bId=b.findSource("B",null).id;
                    check(root.containsSegment(a)&&root.containsSegment(b)&&root.findSource("B",null)==null,"race未形成来源仍在旧段的缺口");
                    final Runnable receive;
                    if(bookmark){
                        final HistoryBookmark[] found={null};resolveHistoryBookmark(0,42,7,token,"B",null,value->found[0]=value);Utilities.globalQueue.next();
                        check(view.bookmarkWindow==b&&AndroidUtilities.ui.ready.size()==1,"race书签没有pin或实际UI边界");Runnable original=AndroidUtilities.ui.ready.remove();
                        receive=()->{original.run();check(found[0]!=null&&found[0].messageId==bId&&bEpoch.equals(found[0].epoch),"race真实书签未完成");};
                    }else{final HistoryPage accepted=candidate;receive=()->check(acceptLatestHistory(accepted),"race实际候选接受失败");}
                    // resolver先找到原归档，之后真实旧页桥接才把正文并入根；UI提交仍须保留原epoch。
                    root.prependWithCachedBridge(page("A,B,C",false,null,null));check(root.findSource("B",null)!=null,"race真实桥接未并入正文");
                    TranscriptWindow unpinned=TranscriptWindow.restore(root.snapshot());unpinned.pruneMergedSegments(Collections.emptySet());check(unpinned.segment(bEpoch)==null,"race归档不能真实裁剪，夹具无效");
                    final Thread[] saving={null};java.util.concurrent.atomic.AtomicBoolean observed=new java.util.concurrent.atomic.AtomicBoolean();
                    epochObserver=(window,thread)->{if(window==a&&thread==saving[0]&&observed.compareAndSet(false,true)){reading.countDown();pinAwait(resume);}};
                    saver=new Thread(()->{try{check(saveHistory(42,"synthetic-remote",root),"race真实Store写入失败");}catch(Throwable error){failure.compareAndSet(null,error);}},"actual-history-save");saving[0]=saver;saver.start();pinAwait(reading);
                    receiver=new Thread(()->{try{receive.run();}catch(Throwable error){failure.compareAndSet(null,error);}finally{completed.countDown();}},"actual-history-ui");receiver.start();
                    // 旧代码UI会真实完成；新代码在实际view监视器阻塞。两者都由可观察线程边界放行。
                    boolean blocked=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while(completed.getCount()!=0){
                        java.lang.management.ThreadInfo info=java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(receiver.getId());
                        if(info!=null&&info.getThreadState()==Thread.State.BLOCKED&&info.getLockOwnerId()==saver.getId()){
                            check(info.getLockInfo()!=null&&info.getLockInfo().getIdentityHashCode()==System.identityHashCode(view),"阻塞不是实际view所有者");blocked=true;break;
                        }
                        check(System.nanoTime()<deadline,"race UI没有进入真实转移边界");completed.await(1,java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                    resume.countDown();saver.join(5000);receiver.join(5000);check(!saver.isAlive()&&!receiver.isAlive(),"race线程没有结束");epochObserver=null;
                    if(failure.get()!=null)throw new AssertionError("race真实方法异常",failure.get());
                    TranscriptWindow saved=store.read("synthetic-remote");TranscriptWindow retained=saved.segment(bEpoch);
                    check(observed.get()&&view.window==b&&root.containsSegment(b)&&retained!=null&&retained.findNumber(bId)!=null&&retained.findNumber(bId).message.id.equals("B"),
                            "pin transfer lost displayed archive: kind="+(bookmark?"bookmark":"accept")+" blocked="+blocked+" current="+(view.window==b)+" memory="+root.containsSegment(b)+" disk="+(retained!=null));
                    System.out.println("PASS atomic pin transfer "+(bookmark?"bookmark":"accept")+"; actual view lock blocked="+blocked+"; memory/disk exact epoch retained");
                }finally{
                    resume.countDown();if(saver!=null)saver.join(5000);if(receiver!=null)receiver.join(5000);epochObserver=null;cleanup();
                }
            }
            public static void main(String[] args)throws Exception{latestOfferAndOldView();queuedUiAfterAccept();queuedNetworkAfterAccept();pauseAndReopen();emptyLatestKeepsView();echoedOutboxAcrossSegments();sourceBookmarkKeepsOriginalSegment();deferredAcceptanceRestoresPending();sameEpochLatestIntent();followsNewTail();rejectedNewTailRecoversLatest();bookmarkPinnedAcrossPrune();for(String kind:new String[]{"pause","latest","accept"})cancelledBookmarkPin(kind);concurrentBookmarks();emptyRootBecomesVisible();deferredNewTailAndOutbox();System.out.println("RuntimeLatestSegment: scenarios=18 failures=0; actual source / Java8 / synthetic only");}
        """;
}
