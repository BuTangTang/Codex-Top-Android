package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;

/** 抽取真实批量历史入口，以合成磁盘与FIFO队列验证前台可插入和原结果资格；不计手机耗时。 */
public final class HistoryOpenYieldTest {
    /** 可指定冻结的旧Runtime执行同一断言，生产方法与模型均独立编译，不替代待测调度逻辑。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        Path runtime = args.length == 0 ? source.resolve("CodexRuntime.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(runtime);
        StringBuilder methods = new StringBuilder();
        Set<String> selected = Set.of("restoreDialogPreviews", "isAccountCurrent", "dialogConnection", "previewCurrent");
        int count = 0;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (selected.contains(method.getNameAsString())
                    || method.getNameAsString().equals("prefetchDialogs") && method.getParameters().size() == 3) {
                methods.append(method).append('\n'); count++;
            }
        }
        if (count != 5) throw new AssertionError("未完整抽取两个入口及三个原资格检查");
        // 冷空页协调执行真实类型、请求表和helper；不在夹具复制调度或合入算法。
        var owner = unit.getClassByName("CodexRuntime").orElseThrow();
        for (var member : owner.getMembers()) {
            if (member.isClassOrInterfaceDeclaration() && (member.asClassOrInterfaceDeclaration().getNameAsString().startsWith("ColdHistory")
                    || member.asClassOrInterfaceDeclaration().getNameAsString().equals("HistoryView"))) methods.append(member).append('\n');
            else if (member.isMethodDeclaration() && (member.asMethodDeclaration().getNameAsString().contains("ColdHistory")
                    || member.asMethodDeclaration().getNameAsString().equals("historyViewCurrent"))) methods.append(member).append('\n');
            else if (member.isFieldDeclaration() && member.asFieldDeclaration().getVariables().stream().anyMatch(v -> v.getNameAsString().equals("coldHistoryRequests")))
                methods.append(member).append('\n');
        }
        methods.append("/** 每例只清实际在途表，避免测试间共享请求。 */ static void resetColdHistoryFixture(){");
        if (owner.getFieldByName("coldHistoryRequests").isPresent()) methods.append("coldHistoryRequests.clear();");
        methods.append("}\n");
        Path temporary = Files.createTempDirectory("codex-history-yield-");
        try {
            Path probe = temporary.resolve("HistoryOpenYieldProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            Path flags = temporary.resolve("BuildVars.java"), log = temporary.resolve("Log.java"), account = temporary.resolve("UserConfig.java");
            Files.writeString(flags, "package org.telegram.messenger; public class BuildVars { public static final boolean DEBUG_VERSION=false; }");
            Files.writeString(log, "package android.util; public class Log { public static int i(String a,String b){return 0;} }");
            Files.writeString(account, "package org.telegram.messenger; public class UserConfig { public static int selectedAccount; }");
            var compile = new ArrayList<String>();
            compile.addAll(List.of("--release", "8", "-encoding", "UTF-8", "-cp", System.getProperty("java.class.path"), "-d", temporary.toString(),
                    probe.toString(), flags.toString(), log.toString(), account.toString()));
            for (String model : new String[]{"DesktopAttachment", "TranscriptText", "TranscriptWindow", "TranscriptStore", "OutboxStore"})
                compile.add(source.resolve(model + ".java").toString());
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实历史队列夹具未通过Java8编译");
            var paths = new ArrayList<java.net.URL>();
            paths.add(temporary.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) paths.add(Path.of(entry).toUri().toURL());
            try (var loader = new URLClassLoader(paths.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("com.butang.codextop.HistoryOpenYieldProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{temporary.toString()}); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实历史队列回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;import java.util.*;import java.nio.file.*;import java.io.*;
        public final class HistoryOpenYieldProbe {
            static long accountGeneration=1,watchedDialog,rejectedBinding;static int limit,reads,saves,pendingPublishes;
            static boolean loggingOut,markers;static PasswordLogin.Session session;
            static Path fixtureRoot;static TranscriptStore store;static DesktopConnection connection;
            static final Map<Long,TranscriptWindow> histories=new HashMap<>();
            static final Map<Integer,HistoryView> historyViews=new HashMap<>();
            static final java.util.concurrent.atomic.AtomicLong historyTokens=new java.util.concurrent.atomic.AtomicLong();

            static final Map<Long,Long> prefetchedRevisions=new HashMap<>();static final Set<Long> prefetching=new HashSet<>();
            static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>();
            static final Map<String,MessageObject> pendingMessages=new HashMap<>();
            static final Queue prefetchQueue=new Queue(),historyQueue=new Queue(),uiQueue=new Queue();static final List<String> events=new ArrayList<>();
            /** 队列仅执行显式推进，保留每次post的FIFO边界，不引入延时或并行线程。 */
            static class Queue {
                final ArrayDeque<Runnable> jobs=new ArrayDeque<>();int posts,maxSize;
                void postRunnable(Runnable r){jobs.add(r);posts++;maxSize=Math.max(maxSize,jobs.size());}
                int size(){return jobs.size();}void one(){jobs.remove().run();}
                void all(){int budget=200;while(!jobs.isEmpty()){if(--budget==0)throw new AssertionError("queue loop");one();}}
                void clear(){jobs.clear();}void reset(){clear();posts=maxSize=0;}
            }
            static class Utilities {static final Queue globalQueue=new Queue();}
            static class AndroidUtilities {static void runOnUIThread(Runnable r){uiQueue.postRunnable(r);}}
            static class ApplicationLoader {static boolean mainInterfacePaused;}
            static class PasswordLogin {static class Session {}}
            static class DesktopConnection {
                final String machineId="synthetic-machine";boolean connected=true;final List<String> calls=new ArrayList<>();JsonObject response;
                boolean isConnected(){return connected;}
                JsonObject transcript(String remote){calls.add(remote);return response;}
                JsonObject transcript(String remote,String cursor){calls.add(remote+":"+cursor);return response;}
                JsonObject readAfter(String remote,String cursor){calls.add(remote+":"+cursor);return response;}
            }
            static class MessageObject {final Owner messageOwner=new Owner();}
            static class Owner {Map<String,String> params=new HashMap<>();}
            static class MessagesController {
                static final MessagesController instance=new MessagesController();final Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();
                static MessagesController getInstance(int account){return instance;}
            }
            static int recentDialogLimit(){return limit;}
            /** 仅替代既有绑定边界，账号资格仍由抽取的原方法核查。 */
            static long bindDialog(String machine,String remote,PasswordLogin.Session owner,long epoch){
                if(owner!=session||!isAccountCurrent(epoch))return 0;
                long id=Long.parseLong(remote.substring("fixture-".length()));if(id==rejectedBinding)return 0;
                dialogMachines.put(id,machine);remoteIds.put(id,remote);return id;
            }
            /** 每次冷读确实走生产Store.read；首次读取内排入的marker代表同队列前台请求与回包。 */
            static TranscriptWindow readHistory(long id,String remote){
                events.add("read-"+id);reads++;
                if(markers&&reads==1){Utilities.globalQueue.postRunnable(()->events.add("front-open"));Utilities.globalQueue.postRunnable(()->events.add("front-delivery"));}
                try{return store.read(remote);}catch(IOException error){throw new AssertionError(error);}
            }
            static OutboxStore outboxStore(long id){return new OutboxStore(fixtureRoot.resolve("outbox").toFile(),"server","account",connection.machineId);}
            static boolean batchEchoed(OutboxStore.Item item,Set<String> echoed){return echoed.contains(item.localId);}
            static ArrayList<MessageObject> restoredPending(int account,long id,OutboxStore.Item item,Set<String> echoed){
                MessageObject message=new MessageObject();message.messageOwner.params.put("codexLocalId",item.localId);
                return new ArrayList<>(Arrays.asList(message));
            }
            /** 测试只观察发布边界；旧对象保护逻辑位于抽取的restore真实UI回调中。 */
            static void publishPendingPreview(int account,long epoch,long id,ArrayList<MessageObject> rows,boolean fresh){
                pendingPublishes++;MessagesController.instance.dialogMessage.put(id,rows);
            }
            static void publishHistoryPreview(int account,long epoch,long id,String remote,String machine,DesktopConnection c,TranscriptWindow window){
                check(window.findSource("source-"+id,null)!=null,"did not read real synthetic cache");events.add("preview-"+id);
            }
            static boolean saveHistory(long id,String remote,TranscriptWindow window){saves++;return true;}
            static void offerLatestHistory(long id,TranscriptWindow window){}
            /** 本组不打开聊天页；意外进入UI候选直接失败，不能用空替身掩盖越界。 */
            static void offerLatestHistory(HistoryView view,TranscriptWindow history,boolean explicit){throw new AssertionError("unexpected opened history view");}

            static void logTranscriptFailure(String stage,Exception error,JsonObject page){
                if(!stage.equals("prefetch_candidate"))throw new AssertionError(stage,error);
                events.add("bad-candidate");
            }
        """;

    private static final String SCENARIOS = """
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason+" events="+events);}
            /** 各场景使用新的独立缓存目录；没有读取真实账号或历史。 */
            static void reset()throws Exception{
                historyViews.clear();historyQueue.reset();resetColdHistoryFixture();
                fixtureRoot=Files.createTempDirectory(fixtureRoot.getParent(),"case-");
                store=new TranscriptStore(fixtureRoot.resolve("cache").toFile(),"server","account","synthetic-machine");
                session=new PasswordLogin.Session();accountGeneration++;loggingOut=false;watchedDialog=rejectedBinding=0;limit=3;reads=saves=pendingPublishes=0;markers=false;
                org.telegram.messenger.UserConfig.selectedAccount=0;ApplicationLoader.mainInterfacePaused=false;
                histories.clear();events.clear();prefetching.clear();prefetchedRevisions.clear();pendingMessages.clear();
                desktopConnections.clear();dialogMachines.clear();remoteIds.clear();MessagesController.instance.dialogMessage.clear();
                Utilities.globalQueue.reset();prefetchQueue.reset();uiQueue.reset();connection=new DesktopConnection();desktopConnections.put(connection.machineId,connection);
                for(int i=1;i<=3;i++){
                    TranscriptWindow window=new TranscriptWindow();window.prepend(page(i));store.write("fixture-"+i,window);
                    bindDialog(connection.machineId,"fixture-"+i,session,accountGeneration);
                }
            }
            static JsonObject page(int id){
                JsonObject p=new JsonObject(),item=new JsonObject(),raw=new JsonObject(),content=new JsonObject();
                item.addProperty("id","source-"+id);item.addProperty("createdAtMs",1000);raw.addProperty("role","agent");
                content.addProperty("type","text");content.addProperty("text","synthetic");raw.add("content",content);item.add("raw",raw);
                JsonArray items=new JsonArray();items.add(item);p.add("items",items);p.addProperty("hasMore",false);
                p.addProperty("historyAvailability","available");p.addProperty("tailCursor","tail-"+id);return p;
            }
            static Map<String,Long> bindings(){Map<String,Long> ids=new LinkedHashMap<>();for(int i=1;i<=3;i++)ids.put("fixture-"+i,(long)i);return ids;}
            static JsonArray candidates(){JsonArray rows=new JsonArray();for(int i=1;i<=3;i++){JsonObject row=new JsonObject();row.addProperty("remoteSessionId","fixture-"+i);row.addProperty("updatedAtMs",i);rows.add(row);}return rows;}
            static JsonArray oneCandidate(){JsonArray rows=new JsonArray();rows.add(candidates().get(0));return rows;}
            static void start(boolean prefetch){if(prefetch)prefetchDialogs(candidates(),connection,1);else restoreDialogPreviews(0,accountGeneration,connection.machineId,bindings());}
            static void readOrder(String... expected){List<String> actual=new ArrayList<>();for(String event:events)if(event.startsWith("read-"))actual.add(event);check(actual.equals(Arrays.asList(expected)),"wrong cold-read order "+actual);}
            static void fairness()throws Exception{
                List<String> failures=new ArrayList<>();
                for(boolean prefetch:new boolean[]{false,true}){
                    try{
                    reset();markers=true;start(prefetch);check(Utilities.globalQueue.size()==1,"start enqueued more than one task");Utilities.globalQueue.one();
                    check(reads==1,"one turn blocked foreground by reading an entire batch");
                    check(Utilities.globalQueue.size()==3,"expected two foreground markers and exactly one continuation");
                    check(prefetchQueue.size()==(prefetch?1:0),"eagerly prepared more than one network job");
                    Utilities.globalQueue.one();Utilities.globalQueue.one();check(reads==1,"continuation overtook foreground");
                    check(events.equals(Arrays.asList("read-1","preview-1","front-open","front-delivery")),"foreground FIFO order");
                    Utilities.globalQueue.one();check(reads==2&&Utilities.globalQueue.size()==1,"middle turn must leave one continuation");
                    Utilities.globalQueue.one();check(reads==3&&Utilities.globalQueue.size()==0,"last turn must stop");
                    check(Utilities.globalQueue.posts==5,"unexpected extra continuation");readOrder("read-1","read-2","read-3");
                    }catch(AssertionError error){failures.add((prefetch?"prefetch: ":"restore: ")+error.getMessage());}
                }
                check(failures.isEmpty(),"foreground blocked: "+failures);
            }
            static void snapshotsAndBudget()throws Exception{
                reset();Map<String,Long> ids=bindings();restoreDialogPreviews(0,accountGeneration,connection.machineId,ids);ids.clear();Utilities.globalQueue.all();readOrder("read-1","read-2","read-3");
                reset();JsonArray rows=candidates();prefetchDialogs(rows,connection,1);rows.get(0).getAsJsonObject().addProperty("remoteSessionId","fixture-99");rows.remove(1);Utilities.globalQueue.all();readOrder("read-1","read-2","read-3");
                for(String skip:new String[]{"watched","inflight","invalid"}){
                    reset();rows=new JsonArray();rows.add("malformed");for(JsonElement row:candidates())rows.add(row);
                    if(skip.equals("watched"))watchedDialog=1;else if(skip.equals("inflight"))prefetching.add(1L);else rejectedBinding=1;
                    prefetchDialogs(rows,connection,1);limit=2;Utilities.globalQueue.one();limit=3;Utilities.globalQueue.all();
                    readOrder("read-2");check(events.contains("bad-candidate")&&prefetchQueue.size()==1,"bad row used budget or "+skip+" changed batch budget");
                    check(Utilities.globalQueue.maxSize==1&&Utilities.globalQueue.posts==3,"bad or skipped rows queued a whole batch");
                }
            }
            static void cancellationAndRebinding()throws Exception{
                for(boolean prefetch:new boolean[]{false,true}){
                    reset();start(prefetch);Utilities.globalQueue.one();accountGeneration++;histories.clear();Utilities.globalQueue.all();prefetchQueue.all();
                    check(reads==1&&histories.isEmpty()&&connection.calls.isEmpty(),"old account rehydrated after clear");
                    reset();start(prefetch);Utilities.globalQueue.one();Utilities.globalQueue.clear();check(reads==1&&Utilities.globalQueue.size()==0,"clear left hidden continuation");
                }
                reset();start(false);Utilities.globalQueue.one();remoteIds.put(2L,"rebound");Utilities.globalQueue.all();readOrder("read-1","read-3");
                reset();start(false);Utilities.globalQueue.one();org.telegram.messenger.UserConfig.selectedAccount=1;Utilities.globalQueue.all();check(reads==1,"new Telegram account read old previews");
                for(boolean disconnect:new boolean[]{false,true}){
                    reset();start(true);Utilities.globalQueue.one();DesktopConnection old=connection;
                    if(disconnect)connection.connected=false;else desktopConnections.put(connection.machineId,new DesktopConnection());
                    Utilities.globalQueue.all();prefetchQueue.all();Utilities.globalQueue.all();
                    check(reads==1&&old.calls.isEmpty()&&prefetching.isEmpty(),"old connection continued or leaked existing single-flight gate");
                }
                reset();start(true);Utilities.globalQueue.one();watchedDialog=2;Utilities.globalQueue.all();readOrder("read-1","read-3");
            }
            static void revisionsAndOverlap()throws Exception{
                reset();for(long i=1;i<=3;i++)prefetchedRevisions.put(i,i);start(true);Utilities.globalQueue.all();
                check(reads==3&&prefetchQueue.size()==0&&events.size()==6,"unchanged revision changed preview or request behavior");
                reset();start(true);Utilities.globalQueue.one();start(true);Utilities.globalQueue.all();
                check(reads==3&&prefetchQueue.size()==3&&prefetching.size()==3,"same-source interleaving duplicated requests");
                readOrder("read-1","read-2","read-3");
            }
            static void lateReplies()throws Exception{
                for(String change:new String[]{"epoch","connection","history","loaded","tail","watch"}){
                    reset();connection.response=page(1);prefetchDialogs(oneCandidate(),connection,1);Utilities.globalQueue.all();prefetchQueue.one();
                    switch(change){
                        case "epoch":accountGeneration++;histories.clear();prefetching.clear();break;
                        case "connection":desktopConnections.put(connection.machineId,new DesktopConnection());break;
                        case "history":histories.put(1L,new TranscriptWindow());break;
                        case "loaded":histories.get(1L).loaded=false;break;
                        case "tail":histories.get(1L).tailCursor="changed";break;
                        case "watch":watchedDialog=1;break;
                    }
                    Utilities.globalQueue.all();check(saves==0&&!prefetchedRevisions.containsKey(1L),"late "+change+" callback wrote a new owner");
                    check(prefetching.isEmpty(),"late response retained gate "+change);
                }
                reset();connection.response=page(1);prefetchDialogs(oneCandidate(),connection,1);Utilities.globalQueue.all();prefetchQueue.all();Utilities.globalQueue.all();
                check(saves==1&&Long.valueOf(1).equals(prefetchedRevisions.get(1L)),"valid response did not preserve merge/save/revision");
            }
            static void pendingUiIdentity()throws Exception{
                for(String change:new String[]{"unchanged","new-preview","rebind","epoch","connection"}){
                    reset();outboxStore(1).put(new OutboxStore.Item("local-pending","fixture-1","synthetic pending",-3,3));
                    MessageObject previous=new MessageObject();MessagesController.instance.dialogMessage.put(1L,new ArrayList<>(Arrays.asList(previous)));
                    start(false);Utilities.globalQueue.one();check(uiQueue.size()==1,"pending restore missing original UI callback");
                    MessageObject fresh=new MessageObject();
                    switch(change){
                        case "new-preview":MessagesController.instance.dialogMessage.put(1L,new ArrayList<>(Arrays.asList(fresh)));break;
                        case "rebind":remoteIds.put(1L,"other");break;
                        case "epoch":accountGeneration++;break;
                        case "connection":desktopConnections.put(connection.machineId,new DesktopConnection());break;
                    }
                    uiQueue.all();check(pendingPublishes==(change.equals("unchanged")?1:0),"late pending UI replaced "+change);
                    if(change.equals("new-preview"))check(MessagesController.instance.dialogMessage.get(1L).get(0)==fresh,"fresh preview overwritten");
                }
            }
            public static void main(String[] args)throws Exception{
                fixtureRoot=Paths.get(args[0]).resolve("initial");
                fairness();System.out.println("PASS fairness restore/prefetch, single continuation and foreground FIFO");
                snapshotsAndBudget();System.out.println("PASS input snapshots and original malformed/watched candidate budget");
                cancellationAndRebinding();System.out.println("PASS epoch/clear/account/binding/connection/watch changes");
                revisionsAndOverlap();System.out.println("PASS unchanged revisions and same-source single-flight overlap");
                lateReplies();System.out.println("PASS delayed reply identity guards and successful save");
                pendingUiIdentity();System.out.println("PASS pending-preview UI identity guards");
            }
        """;
}
