package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import javax.tools.ToolProvider;

/** 提取真实读题、状态变更与提交方法；只替换Android队列、账号和RPC边界。 */
public final class RuntimeQuestionRefreshTest {
    /** 用现成JDK及缓存依赖运行合成状态转换，不读取真实账号或对话。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var unit = StaticJavaParser.parse(source.resolve("CodexRuntime.java"));
        var names = Set.of("readQuestions", "answerQuestions", "refreshQuestions", "isAccountCurrent", "dialogConnection");
        StringBuilder methods = new StringBuilder();
        boolean hasRefresh = false;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (names.contains(method.getNameAsString())) {
                methods.append(method).append('\n');
                hasRefresh |= method.getNameAsString().equals("refreshQuestions");
            }
        }
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class))
            if (type.getNameAsString().equals("QuestionReview")) methods.append(type).append('\n');
        Path temporary = Files.createTempDirectory("codex-question-refresh");
        try {
            Path probe = temporary.resolve("QuestionRefreshProbe.java"), prefs = temporary.resolve("SharedPreferences.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + (hasRefresh ? REFRESH_SCENARIOS : "") + "\n}");
            Files.writeString(prefs, "package android.content; public interface SharedPreferences {boolean contains(String k);String getString(String k,String d);Editor edit();interface Editor {Editor putString(String k,String v);Editor remove(String k);boolean commit();}}");
            Files.writeString(temporary.resolve("UserConfig.java"), "package org.telegram.messenger; public class UserConfig { public static int selectedAccount; }");
            Files.writeString(temporary.resolve("SystemClock.java"), "package android.os; public final class SystemClock { public static long elapsed; private SystemClock() {} public static long elapsedRealtime() { return elapsed; } }");
            ArrayList<String> compile = new ArrayList<>(java.util.List.of("-cp", System.getProperty("java.class.path"),
                    "-d", temporary.toString(), probe.toString(), prefs.toString(),
                    temporary.resolve("UserConfig.java").toString(), temporary.resolve("SystemClock.java").toString(),
                    source.resolve("DesktopQuestion.java").toString(), source.resolve("SessionStatus.java").toString()));
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实提问方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, RuntimeQuestionRefreshTest.class.getClassLoader())) {
                Class<?> type = loader.loadClass("com.butang.codextop.QuestionRefreshProbe");
                for (String method : hasRefresh ? new String[]{"main", "refreshScenarios", "staleScenarios", "linkedScenarios",
                        "readIdentityScenarios", "identityScenarios", "openBootstrapScenarios", "deadlineScenarios",
                        "knownLinkedScenarios", "independentReadScenarios", "returnedKeyScenarios"}
                        : new String[]{"main"}) {
                    try { type.getMethod(method, String[].class).invoke(null, (Object) new String[0]); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("真实提问生命周期回归失败", error.getCause()); }
                }
                if (!hasRefresh) throw new AssertionError("原STATUS尚未接入已打开问题的去重读取");
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    // 只提供平台边界和可插入迟到回包的队列，产品判断来自上面提取的方法。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;import com.google.gson.*;
        public final class QuestionRefreshProbe {
            static final class Queue {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();void postRunnable(Runnable r){tasks.add(r);}void all(){int n=50;while(!tasks.isEmpty()){if(--n==0)throw new AssertionError("queue loop");tasks.remove().run();}}}
            static final Queue approvalQueue=new Queue(),ui=new Queue();
            static final class AndroidUtilities {static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
            static final class PasswordLogin {static final class Session {String server="synthetic",accountId="account";}}
            static final class TranscriptStore {static String digest(String s){return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
            static final class Preferences implements android.content.SharedPreferences {
                final Map<String,String> values=new HashMap<>();boolean writable=true;
                public boolean contains(String k){return values.containsKey(k);}public String getString(String k,String d){return values.getOrDefault(k,d);}
                public Editor edit(){return new Editor(){String key,value;boolean remove;
                    public Editor putString(String k,String v){key=k;value=v;return this;}public Editor remove(String k){key=k;remove=true;return this;}
                    public boolean commit(){if(!writable)return false;if(remove)values.remove(key);else values.put(key,value);if(onCommit!=null)onCommit.run();return true;}};}
            }
            static final class Context {final Preferences prefs=new Preferences();android.content.SharedPreferences getSharedPreferences(String n,int mode){return prefs;}}
            static final class ApplicationLoader {static final Context applicationContext=new Context();}
            static final class DesktopConnection {
                final String machineId="machine";int reads,sends,opens;Runnable onRead,onOpen;String result="unknown",openedId,lastReadLinked;JsonObject snapshot;
                static String sourceKind="codexHome",sourceHome="user";
                ArrayList<DesktopQuestion> readQuestions(String linked)throws Exception{reads++;lastReadLinked=linked;if(onRead!=null)onRead.run();return DesktopQuestion.read(snapshot);}
                JsonObject openConversation(String remote){opens++;if(onOpen!=null)onOpen.run();JsonObject j=new JsonObject();j.addProperty("sessionId",openedId==null?"linked":openedId);return j;}
                JsonObject answerQuestions(String linked,DesktopQuestion request,String operation,Map<String,String> answers)throws Exception{sends++;JsonObject r=new JsonObject();r.addProperty("status",result);return r;}
                static JsonObject userCodexSource(){JsonObject s=new JsonObject();s.addProperty("kind",sourceKind);s.addProperty("home",sourceHome);return s;}
                static class RpcNotDispatchedException extends Exception {}
            }
            static PasswordLogin.Session session;static boolean loggingOut;static long accountGeneration,watchGeneration,watchedDialog;
            static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>(),linkedSessions=new HashMap<>();
            static DesktopConnection desktop;static final SessionStatus.Store statuses=new SessionStatus.Store();static long now;static Runnable onCommit;
            static SessionStatus.Snapshot status(long id){return statuses.get(id,android.os.SystemClock.elapsedRealtime());}
        """;

    private static final String SCENARIOS = """
            static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
            static void pump(){approvalQueue.all();ui.all();}
            static JsonObject snapshot(String status,boolean partial){
                JsonObject root=new JsonObject(),r=new JsonObject();JsonArray items=new JsonArray(),questions=new JsonArray();
                root.addProperty("v",1);root.add("questions",items);items.add(r);
                r.addProperty("kind","async_questions");r.add("requestId",JsonNull.INSTANCE);r.addProperty("itemId","card");r.addProperty("turnId","turn");
                r.addProperty("revision","r1");r.addProperty("status",status);r.addProperty("canAnswer",status.equals("pending"));r.add("questions",questions);
                for(String id:new String[]{"a","b"}){JsonObject q=new JsonObject();q.addProperty("id",id);q.addProperty("question","synthetic");q.addProperty("isOther",true);q.add("options",new JsonArray());questions.add(q);}
                if(partial){JsonObject answers=new JsonObject();JsonArray a=new JsonArray();a.add("desktop");answers.add("a",a);r.add("answers",answers);}return root;
            }
            static void observation(String state,String turn,String... ids){
                JsonObject response=new JsonObject(),o=new JsonObject();response.addProperty("ok",true);response.addProperty("machineOnline",true);response.add("observation",o);
                o.addProperty("v",1);o.addProperty("source","desktop");o.addProperty("state",state);o.addProperty("turnId",turn);
                JsonArray requests=new JsonArray();for(String id:ids){JsonObject r=new JsonObject();r.addProperty("requestId",id);r.addProperty("kind","user_action_request");requests.add(r);}o.add("requests",requests);
                now+=10;android.os.SystemClock.elapsed=now;statuses.observation(1,"machine",response,now,now);
            }
            static void observeAt(long started,long received,String state,String turn,String... ids){
                JsonObject response=new JsonObject(),o=new JsonObject();response.addProperty("ok",true);response.addProperty("machineOnline",true);response.add("observation",o);
                o.addProperty("v",1);o.addProperty("source","desktop");o.addProperty("state",state);o.addProperty("turnId",turn);
                JsonArray requests=new JsonArray();for(String id:ids){JsonObject r=new JsonObject();r.addProperty("requestId",id);r.addProperty("kind","user_action_request");requests.add(r);}o.add("requests",requests);
                now=received;android.os.SystemClock.elapsed=received;statuses.observation(1,"machine",response,started,received);
            }
            static void reset(){session=new PasswordLogin.Session();accountGeneration++;watchGeneration++;watchedDialog=1;loggingOut=false;now=0;android.os.SystemClock.elapsed=0;onCommit=null;org.telegram.messenger.UserConfig.selectedAccount=0;DesktopConnection.sourceKind="codexHome";DesktopConnection.sourceHome="user";statuses.clear();
                approvalQueue.tasks.clear();ui.tasks.clear();desktopConnections.clear();dialogMachines.clear();remoteIds.clear();linkedSessions.clear();
                ApplicationLoader.applicationContext.prefs.values.clear();desktop=new DesktopConnection();desktop.snapshot=snapshot("pending",false);
                desktopConnections.put("machine",desktop);dialogMachines.put(1L,"machine");remoteIds.put(1L,"remote");linkedSessions.put(1L,"linked");observation("needs_input","turn","a","b");}
            static QuestionReview read(){QuestionReview[] result={null};readQuestions(1,(r,e)->result[0]=r);pump();check(result[0]!=null,"initial question missing");return result[0];}
            public static void main(String[] ignored)throws Exception{
                reset();DesktopConnection original=desktop;QuestionReview[] late={null};int[] callbacks={0};
                original.onRead=()->desktopConnections.put("machine",new DesktopConnection());
                readQuestions(1,(r,e)->{late[0]=r;callbacks[0]++;});pump();
                check(late[0]==null,"connection replacement published old question snapshot");
                reset();callbacks[0]=0;readQuestions(1,(r,e)->callbacks[0]++);approvalQueue.all();accountGeneration++;ui.all();check(callbacks[0]==0,"late account callback escaped");
                reset();callbacks[0]=0;readQuestions(1,(r,e)->callbacks[0]++);approvalQueue.all();watchGeneration++;ui.all();check(callbacks[0]==0,"late page callback escaped");
                reset();QuestionReview review=read();DesktopQuestion request=review.requests.get(0);Map<String,String> answers=Map.of("a","one","b","two");
                answerQuestions(review,request,answers,(s,m)->{});pump();answerQuestions(review,request,answers,(s,m)->{});pump();
                check(desktop.sends==1&&review.alreadyIssued(request),"unknown answer automatically or repeatedly dispatched");
                System.out.println("QuestionRefresh: 真实读题连接/账号/页面迟到与未知提交保护通过");
            }
        """;

    private static final String REFRESH_SCENARIOS = """
            public static void refreshScenarios(String[] ignored)throws Exception{
                reset();QuestionReview review=read();DesktopQuestion request=review.requests.get(0);int[] callbacks={0};QuestionReview[] fresh={null};
                for(int n=0;n<3;n++){observation("needs_input","turn","b","a","unrelated");refreshQuestions(review,request,(r,e)->callbacks[0]++);}pump();
                check(desktop.reads==1&&callbacks[0]==0,"unchanged observation added question reads");
                observation("needs_input","turn","b");desktop.snapshot=snapshot("pending",true);
                refreshQuestions(review,request,(r,e)->{fresh[0]=r;callbacks[0]++;});refreshQuestions(review,request,(r,e)->callbacks[0]++);pump();
                check(desktop.reads==2&&callbacks[0]==1&&"desktop".equals(fresh[0].requests.get(0).answers.get("a")),"partial answer not refreshed once");
                review=fresh[0];request=review.requests.get(0);observation("running","turn");desktop.snapshot=snapshot("answered",true);
                refreshQuestions(review,request,(r,e)->fresh[0]=r);pump();check(!fresh[0].requests.get(0).canAnswer,"answered form remained editable");
                reset();review=read();request=review.requests.get(0);observation("completed","turn");desktop.snapshot=snapshot("expired",false);
                refreshQuestions(review,request,(r,e)->fresh[0]=r);pump();check("expired".equals(fresh[0].requests.get(0).status),"expired request not refreshed");
                reset();review=read();request=review.requests.get(0);observation("unknown","turn");callbacks[0]=0;fresh[0]=review;
                refreshQuestions(review,request,(r,e)->{fresh[0]=r;callbacks[0]++;});pump();
                check(desktop.reads==1&&callbacks[0]==1&&fresh[0]==null,"unknown observation read or pretended answered");
                observation("needs_input","turn","a","b");refreshQuestions(review,request,(r,e)->fresh[0]=r);pump();check(desktop.reads==2&&fresh[0]!=null,"recovery did not recheck pending form");
                reset();review=read();request=review.requests.get(0);observation("running","turn");callbacks[0]=0;
                refreshQuestions(review,request,(r,e)->callbacks[0]++);approvalQueue.all();watchGeneration++;ui.all();check(callbacks[0]==0,"refresh reached another page");
                reset();review=read();request=review.requests.get(0);observation("running","turn");fresh[0]=null;
                desktop.onRead=()->desktopConnections.put("machine",new DesktopConnection());
                refreshQuestions(review,request,(r,e)->fresh[0]=r);pump();check(fresh[0]==null,"refresh published replaced connection");
                reset();review=read();request=review.requests.get(0);observation("needs_input","turn","b");desktop.snapshot=snapshot("pending",true);fresh[0]=review;
                refreshQuestions(review,request,(r,e)->fresh[0]=r);approvalQueue.all();observation("unknown","turn");ui.all();
                check(fresh[0]==null&&desktop.reads==2,"inflight old read re-enabled unknown form");
                callbacks[0]=0;refreshQuestions(review,request,(r,e)->callbacks[0]++);pump();check(callbacks[0]==0&&desktop.reads==2,"unchanged unknown repeated refresh");
                reset();review=read();request=review.requests.get(0);observation("needs_input","turn","b");desktop.snapshot=snapshot("pending",true);fresh[0]=null;
                refreshQuestions(review,request,(r,e)->fresh[0]=r);approvalQueue.all();observation("running","turn");desktop.snapshot=snapshot("answered",true);ui.all();
                check(fresh[0]==null,"stale pending published before latest status recheck");pump();check(desktop.reads==3&&"answered".equals(fresh[0].requests.get(0).status),"latest inflight state not rechecked once");
                reset();review=read();request=review.requests.get(0);observation("needs_input","another-turn","a","b");desktop.snapshot=snapshot("expired",false);
                refreshQuestions(review,request,(r,e)->fresh[0]=r);pump();check(desktop.reads==2&&"expired".equals(fresh[0].requests.get(0).status),"turn change did not recheck");
                reset();review=read();request=review.requests.get(0);observation("running","turn");callbacks[0]=0;
                refreshQuestions(review,request,(r,e)->callbacks[0]++);approvalQueue.all();accountGeneration++;ui.all();check(callbacks[0]==0,"refresh crossed account generation");
                System.out.println("QuestionRefresh: 同状态零请求、部分/全答/失效、未知恢复、读途中变化与刷新迟到通过");
            }
            public static void staleScenarios(String[] ignored)throws Exception{
                reset();now=16000;android.os.SystemClock.elapsed=16000;QuestionReview review=read();DesktopQuestion request=review.requests.get(0);
                check("stale".equals(status(1).validity),"合成状态没有过期");
                boolean refresh=refreshQuestions(review,request,(r,e)->{});
                answerQuestions(review,request,Map.of("a","one","b","two"),(outcome,message)->{});pump();
                check(refresh&&desktop.sends==0,"RED：过期STATUS初读表单refresh=false，仍实际派发回答");
            }
            public static void linkedScenarios(String[] ignored)throws Exception{
                reset();QuestionReview review=read();DesktopQuestion request=review.requests.get(0);
                linkedSessions.put(1L,"new-linked");
                answerQuestions(review,request,Map.of("a","one","b","two"),(outcome,message)->{});pump();
                check(desktop.sends==0,"RED：同连接linked更换后原表单仍实际派发到旧linked");
            }
            public static void readIdentityScenarios(String[] ignored)throws Exception{
                reset();QuestionReview[] result={null};
                desktop.onRead=()->remoteIds.put(1L,"other-remote");
                readQuestions(1,(r,e)->result[0]=r);pump();
                check(result[0]==null,"RED：原读题回包在同连接remote更换后仍进入当前表单");
            }
            public static void identityScenarios(String[] ignored)throws Exception{
                reset();QuestionReview review=read();DesktopQuestion request=review.requests.get(0);
                desktopConnections.put("other",desktop);dialogMachines.put(1L,"other");
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"换电脑后仍派发到原连接对象");
                reset();review=read();request=review.requests.get(0);remoteIds.put(1L,"other-remote");
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"换remote后仍派发");
                reset();review=read();request=review.requests.get(0);DesktopConnection.sourceKind="other";
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"换来源kind后仍派发");
                reset();review=read();request=review.requests.get(0);DesktopConnection.sourceHome="other";
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"换来源home后仍派发");
                reset();review=read();request=review.requests.get(0);org.telegram.messenger.UserConfig.selectedAccount=1;
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"换selectedAccount后仍派发");
                reset();review=read();request=review.requests.get(0);session=new PasswordLogin.Session();
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"换账号对象后仍派发");
            }
            public static void openBootstrapScenarios(String[] ignored)throws Exception{
                reset();linkedSessions.remove(1L);desktop.openedId="opened-original";
                QuestionReview review=read();
                check(desktop.opens==1&&"opened-original".equals(linkedSessions.get(1L))&&"opened-original".equals(desktop.lastReadLinked)&&review.current(),"原null bootstrap没有按open结果读题");
                reset();linkedSessions.remove(1L);desktop.openedId="opened-original";
                desktop.onOpen=()->linkedSessions.put(1L,"raced-other");
                QuestionReview[] raced={null};int reads=desktop.reads;
                readQuestions(1,(r,e)->raced[0]=r);pump();
                check(raced[0]==null&&desktop.reads==reads&&"raced-other".equals(linkedSessions.get(1L)),"RED bootstrap: read adopted raced-other despite original open returned opened-original");
                reset();linkedSessions.remove(1L);desktop.openedId="same-linked";
                desktop.onOpen=()->linkedSessions.put(1L,"same-linked");
                review=read();
                check(review!=null&&review.current()&&"same-linked".equals(desktop.lastReadLinked),"竞态写入与open相同的linked没有完成读题");
                reset();linkedSessions.remove(1L);
                QuestionReview[] migrated={null};reads=desktop.reads;
                readQuestions(1,(r,e)->migrated[0]=r);linkedSessions.put(1L,"new-linked");pump();
                check(migrated[0]==null&&desktop.reads==reads&&desktop.opens==0,"入队前出现的新linked被bootstrap迁移");
            }
            public static void knownLinkedScenarios(String[] ignored)throws Exception{
                reset();QuestionReview known=read();
                check(known.current()&&"linked".equals(desktop.lastReadLinked),"已知非空linked没有按原值读题");
                reset();QuestionReview[] changed={null};int reads=desktop.reads;
                readQuestions(1,(r,e)->changed[0]=r);linkedSessions.put(1L,"new-linked");pump();
                check(changed[0]==null&&(desktop.reads==reads||!"new-linked".equals(desktop.lastReadLinked)),"RED knownlinked: read-entry linked changed before queue; snapshot published from new-linked");
                reset();linkedSessions.put(1L,"");reads=desktop.reads;QuestionReview[] empty={null};
                readQuestions(1,(r,e)->empty[0]=r);pump();
                check(desktop.reads==reads&&(empty[0]==null||!empty[0].current()),"RED emptylinked: empty known linked read and authorized review.current");
            }
            public static void deadlineScenarios(String[] ignored)throws Exception{
                reset();statuses.clear();observeAt(100,14999,"needs_input","turn","a","b");
                QuestionReview review=read();DesktopQuestion request=review.requests.get(0);
                android.os.SystemClock.elapsed=15100;
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0,"队列执行时已过请求起点期限仍派发");
                reset();statuses.clear();observeAt(100,100,"needs_input","turn","a","b");
                review=read();request=review.requests.get(0);
                onCommit=()->android.os.SystemClock.elapsed=15100;
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(desktop.sends==0&&!review.alreadyIssued(request),"写意图后跨过期限仍派发或留下意图");
                observeAt(200,200,"needs_input","turn","a","b");
                int reads=desktop.reads;
                boolean same=refreshQuestions(review,request,(r,e)->{});
                android.os.SystemClock.elapsed=15100;
                answerQuestions(review,request,Map.of("a","one","b","two"),(s,m)->{});pump();
                check(!same&&desktop.reads==reads&&desktop.sends==1,"相同current没有零RPC更新期限");
            }
            public static void independentReadScenarios(String[] ignored)throws Exception{
                reset();now=16000;android.os.SystemClock.elapsed=16000;
                QuestionReview initial=read();DesktopQuestion request=initial.requests.get(0);
                check(desktop.reads==1&&"stale".equals(status(1).validity),"过期初读不是一次");
                int[] errors={0},success={0};
                check(refreshQuestions(initial,request,(r,e)->{if(r==null)errors[0]++;}),"第一次过期刷新没有通知原表单");
                for(int n=0;n<3;n++)check(!refreshQuestions(initial,request,(r,e)->errors[0]++),"相同过期重复回调");
                pump();check(desktop.reads==1&&errors[0]==1,"相同过期又发起了读取");
                observation("needs_input","turn","a","b");QuestionReview[] fresh={null};
                check(refreshQuestions(initial,request,(r,e)->{fresh[0]=r;if(r!=null)success[0]++;}),"过期恢复没有读题");
                pump();check(desktop.reads==2&&success[0]==1&&fresh[0]!=null&&fresh[0].requests.get(0).canAnswer,"恢复后的题目不可回答");
                QuestionReview loaded=fresh[0];DesktopQuestion current=loaded.requests.get(0);long expiry=loaded.expiry;
                for(int n=0;n<3;n++){observation("needs_input","turn","a","b");check(!refreshQuestions(loaded,current,(r,e)->success[0]++),"相同current重复回调");}
                pump();check(desktop.reads==2&&loaded.expiry>expiry,"相同current没有零RPC更新期限");
                reset();now=16000;android.os.SystemClock.elapsed=16000;QuestionReview failed=read();DesktopQuestion failedRequest=failed.requests.get(0);
                refreshQuestions(failed,failedRequest,(r,e)->{});observation("needs_input","turn","a","b");
                desktop.onRead=()->{throw new RuntimeException("synthetic one-shot control read failure");};
                final int[] refreshed={0},failedCallbacks={0};
                refreshQuestions(failed,failedRequest,(r,e)->{if(r==null)failedCallbacks[0]++;else refreshed[0]++;});
                check(!refreshQuestions(failed,failedRequest,(r,e)->refreshed[0]++),"在途失败又发起了第二次读取");
                pump();
                check(desktop.reads==2&&failedCallbacks[0]==1&&failed.current()&&refreshed[0]==0,"一次失败改变了身份或启用了旧回答");
                desktop.onRead=null;
                for(int n=0;n<10;n++){now+=5000;observation("needs_input","turn","a","b");refreshQuestions(failed,failedRequest,(r,e)->{if(r!=null)refreshed[0]++;});pump();}
                check(desktop.reads==3&&refreshed[0]==1,"一次失败的读题后，相同current状态没有再试一次");
                for(int n=0;n<3;n++){observation("needs_input","turn","a","b");check(!refreshQuestions(failed,failedRequest,(r,e)->refreshed[0]++),"成功后相同current又读题");}
                pump();check(desktop.reads==3&&refreshed[0]==1,"成功后的相同current不是零RPC");
                desktop.onRead=()->{throw new RuntimeException("synthetic repeated control read failure");};
                int bounded=desktop.reads;
                for(int n=0;n<4;n++){
                    observation("running","turn");
                    int before=desktop.reads;
                    refreshQuestions(failed,failedRequest,(r,e)->{});
                    refreshQuestions(failed,failedRequest,(r,e)->{});
                    pump();
                    check(desktop.reads==before+1,"同一次状态跳动发起了多次失败读取");
                }
                check(desktop.reads==bounded+4,"重复失败没有按每次状态跳动只读一次");
                remoteIds.put(1L,"other-remote");
                int frozen=desktop.reads;
                observation("running","turn");
                QuestionReview[] late={failed};
                refreshQuestions(failed,failedRequest,(r,e)->late[0]=r);pump();
                check(desktop.reads==frozen&&(late[0]==null||!failed.current()),"身份变化后失败重试仍在读取或保持提交许可");
            }
            public static void returnedKeyScenarios(String[] ignored)throws Exception{
                reset();QuestionReview review=read();DesktopQuestion request=review.requests.get(0);
                check(review.expiry>0&&desktop.reads==1,"原始current读题没有期限");
                JsonObject rollout=new JsonObject(),body=new JsonObject();rollout.addProperty("ok",true);rollout.addProperty("machineOnline",true);rollout.add("observation",body);
                body.addProperty("v",1);body.addProperty("source","rollout");body.addProperty("turnId","turn");body.addProperty("state","needs_input");
                JsonArray ids=new JsonArray();for(String id:new String[]{"a","b"}){JsonObject item=new JsonObject();item.addProperty("requestId",id);item.addProperty("kind","user_action_request");ids.add(item);}body.add("requests",ids);
                now=20;android.os.SystemClock.elapsed=now;statuses.observation(1,"machine",rollout,now,now);
                desktop.onRead=()->{throw new RuntimeException("synthetic control failure");};
                int[] failures={0},success={0};
                refreshQuestions(review,request,(loaded,error)->{if(loaded==null)failures[0]++;else success[0]++;});
                check(!refreshQuestions(review,request,(loaded,error)->success[0]++),"失败读取在途又发起一次");
                pump();
                check(desktop.reads==2&&failures[0]==1&&success[0]==0&&review.expiry==-1&&review.current(),"失败读题没有停用期限或改变了身份");
                desktop.onRead=null;
                for(int n=0;n<6;n++){now+=5000;observation("needs_input","turn","a","b");refreshQuestions(review,request,(loaded,error)->{if(loaded!=null)success[0]++;});pump();}
                System.out.println("RETURNED_ORIGINAL_KEY reads="+desktop.reads+" success="+success[0]+" identityCurrent="+review.current()+" expiry="+review.expiry);
                check(desktop.reads==3&&success[0]==1,"failure then original current key remains disabled without a control reread");
                int settled=desktop.reads;
                for(int n=0;n<3;n++){observation("needs_input","turn","a","b");check(!refreshQuestions(review,request,(loaded,error)->success[0]++),"成功后回到原键仍在读题");}
                pump();check(desktop.reads==settled&&success[0]==1,"成功后的原键不是零RPC");
                desktop.onRead=()->{throw new RuntimeException("synthetic transition failure");};
                observation("needs_input","other-turn","a","b");
                refreshQuestions(review,request,(loaded,error)->{});
                refreshQuestions(review,request,(loaded,error)->{});
                pump();
                check(desktop.reads==settled+1,"失败后的轮次变化没有只读一次");
                desktop.onRead=null;
                observation("needs_input","other-turn","a");
                int[] transition={0};
                refreshQuestions(review,request,(loaded,error)->{if(loaded!=null)transition[0]++;});pump();
                check(desktop.reads==settled+2&&transition[0]==1,"失败后的题目集合变化没有完成一次核对");
                observation("needs_input","other-turn","a");
                check(!refreshQuestions(review,request,(loaded,error)->transition[0]++),"题目集合核对成功后又读了一次");
                desktop.onRead=()->{throw new RuntimeException("synthetic guard failure");};
                observation("needs_input","guard-turn","a");
                int[] disabled={0};int beforeGuard=desktop.reads;
                refreshQuestions(review,request,(loaded,error)->{if(loaded==null)disabled[0]++;});
                refreshQuestions(review,request,(loaded,error)->disabled[0]++);
                pump();
                check(desktop.reads==beforeGuard+1&&disabled[0]==1&&review.expiry==-1,"失败后的再次核对没有停在单次在途");
                desktop.onRead=null;int frozen=desktop.reads;
                android.os.SystemClock.elapsed=now+16000;
                int[] staleNotes={0};
                refreshQuestions(review,request,(loaded,error)->{staleNotes[0]++;if(loaded!=null)throw new AssertionError("过期仍交付题目");});
                answerQuestions(review,request,Map.of("a","kept"),(outcome,message)->{});pump();
                check(desktop.reads==frozen&&staleNotes[0]==1&&desktop.sends==0,"过期状态在失败后仍读取或派发草稿");
                JsonObject unknown=new JsonObject(),fact=new JsonObject();unknown.addProperty("ok",true);unknown.addProperty("machineOnline",true);unknown.add("observation",fact);
                fact.addProperty("v",1);fact.addProperty("state","unknown");fact.addProperty("reason","not_observed");
                now+=17000;android.os.SystemClock.elapsed=now;statuses.observation(1,"machine",unknown,now,now);
                int[] unknownNotes={0};
                refreshQuestions(review,request,(loaded,error)->{unknownNotes[0]++;if(loaded!=null)throw new AssertionError("未知仍交付题目");});
                answerQuestions(review,request,Map.of("a","kept"),(outcome,message)->{});pump();
                check(desktop.reads==frozen&&unknownNotes[0]==1&&desktop.sends==0,"未知状态在失败后仍读取或派发草稿");
                desktop.snapshot=snapshot("expired",false);
                observation("needs_input","guard-turn","a");
                QuestionReview[] expired={null};int beforeExpired=desktop.reads;
                refreshQuestions(review,request,(loaded,error)->expired[0]=loaded);pump();
                check(desktop.reads==beforeExpired+1&&expired[0]!=null&&!expired[0].requests.get(0).canAnswer,"失效题目在失败后没有核对或仍可提交");
                answerQuestions(expired[0],expired[0].requests.get(0),Map.of("a","kept"),(outcome,message)->{});pump();
                check(desktop.sends==0,"失效题目核对后仍派发草稿");
                remoteIds.put(1L,"other-remote");int identityReads=desktop.reads;
                observation("needs_input","guard-turn","a");
                QuestionReview[] late={review};
                refreshQuestions(review,request,(loaded,error)->late[0]=loaded);pump();
                check(desktop.reads==identityReads&&late[0]==null&&desktop.sends==0,"身份变化后仍读取或派发草稿");
            }
        """;
}
