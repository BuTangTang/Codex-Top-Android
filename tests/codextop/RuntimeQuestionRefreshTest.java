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
            ArrayList<String> compile = new ArrayList<>(java.util.List.of("-cp", System.getProperty("java.class.path"),
                    "-d", temporary.toString(), probe.toString(), prefs.toString(),
                    source.resolve("DesktopQuestion.java").toString(), source.resolve("SessionStatus.java").toString()));
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("真实提问方法夹具编译失败");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, RuntimeQuestionRefreshTest.class.getClassLoader())) {
                Class<?> type = loader.loadClass("com.butang.codextop.QuestionRefreshProbe");
                for (String method : hasRefresh ? new String[]{"main", "refreshScenarios"} : new String[]{"main"}) {
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
                    public boolean commit(){if(!writable)return false;if(remove)values.remove(key);else values.put(key,value);return true;}};}
            }
            static final class Context {final Preferences prefs=new Preferences();android.content.SharedPreferences getSharedPreferences(String n,int mode){return prefs;}}
            static final class ApplicationLoader {static final Context applicationContext=new Context();}
            static final class DesktopConnection {
                final String machineId="machine";int reads,sends;Runnable onRead;String result="unknown";JsonObject snapshot;
                ArrayList<DesktopQuestion> readQuestions(String linked)throws Exception{reads++;if(onRead!=null)onRead.run();return DesktopQuestion.read(snapshot);}
                JsonObject openConversation(String remote){JsonObject j=new JsonObject();j.addProperty("sessionId","linked");return j;}
                JsonObject answerQuestions(String linked,DesktopQuestion request,String operation,Map<String,String> answers)throws Exception{sends++;JsonObject r=new JsonObject();r.addProperty("status",result);return r;}
                static class RpcNotDispatchedException extends Exception {}
            }
            static PasswordLogin.Session session;static boolean loggingOut;static long accountGeneration,watchGeneration,watchedDialog;
            static final Map<String,DesktopConnection> desktopConnections=new HashMap<>();
            static final Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>(),linkedSessions=new HashMap<>();
            static DesktopConnection desktop;static final SessionStatus.Store statuses=new SessionStatus.Store();static long now;
            static SessionStatus.Snapshot status(long id){return statuses.get(id,now);}
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
                now+=10;statuses.observation(1,"machine",response,now,now);
            }
            static void reset(){session=new PasswordLogin.Session();accountGeneration++;watchGeneration++;watchedDialog=1;loggingOut=false;now=0;statuses.clear();
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
        """;
}
