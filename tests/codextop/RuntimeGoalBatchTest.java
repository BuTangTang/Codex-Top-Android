package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import javax.tools.ToolProvider;

/** Exact Runtime methods and real status/cache owners, deterministic queues and synthetic files only. */
public final class RuntimeGoalBatchTest {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]), out=Path.of(args[1]);
    if(Files.exists(out))throw new IllegalArgumentException("output already exists");
    Files.createDirectories(out);
    Path sourceDir=root.resolve("TMessagesProj/src/main/java/com/butang/codextop");
    StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    var owner=StaticJavaParser.parse(sourceDir.resolve("CodexRuntime.java")).getClassByName("CodexRuntime").orElseThrow();
    Set<String> names=Set.of("scheduleGoalRestore","goalDisplayStore","goalCacheIdentityCurrent","isAccountCurrent",
      "publishDialogFacts","candidateDirectory","publishDialogs","publishDialogUser","statusPresentation");
    Set<String> found=new HashSet<>();StringBuilder methods=new StringBuilder();
    for(BodyDeclaration<?> member:owner.getMembers()) {
      if(member instanceof MethodDeclaration m && names.contains(m.getNameAsString())) { methods.append(m).append('\n');found.add(m.getNameAsString()); }
      if(member instanceof ClassOrInterfaceDeclaration c && c.getNameAsString().equals("GoalRestoreBatch"))methods.append(c).append('\n');
    }
    if(!found.equals(names))throw new AssertionError("missing original owner methods");
    Files.writeString(out.resolve("RuntimeGoalBatchProbe.java"), FIXTURE+methods+SCENARIOS+"\n}");
    String cache=Files.readString(sourceDir.resolve("GoalDisplayStore.java"));
    String read="public Loaded read() {";
    if(cache.indexOf(read)!=cache.lastIndexOf(read)||!cache.contains(read))throw new AssertionError("ambiguous read owner");
    cache=cache.replace(read,"static int probeReads;\n    "+read+"\n        probeReads++;");
    Files.writeString(out.resolve("GoalDisplayStore.java"),cache);
    Files.copy(sourceDir.resolve("SessionStatus.java"),out.resolve("SessionStatus.java"));
    Files.copy(sourceDir.resolve("DialogStore.java"),out.resolve("DialogStore.java"));
    Files.writeString(out.resolve("UserConfig.java"),"package org.telegram.messenger; public class UserConfig { public static int selectedAccount; }");
    Files.writeString(out.resolve("TLRPC.java"),MODELS);
    Files.writeString(out.resolve("MessagesController.java"),CONTROLLER);
    Files.writeString(out.resolve("NotificationCenter.java"),"package org.telegram.messenger; public class NotificationCenter { public static final int updateInterfaces=1,dialogsNeedReload=2; public static int posts; public static NotificationCenter getInstance(int a){return new NotificationCenter();} public void postNotificationName(int id,Object... args){posts++;} }");
    ArrayList<String> compile=new ArrayList<>(List.of("-cp",System.getProperty("java.class.path"),"-d",out.toString()));
    try(var files=Files.list(out)){files.filter(p->p.toString().endsWith(".java")).sorted().forEach(p->compile.add(p.toString()));}
    if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))!=0)throw new AssertionError("extracted Runtime compile failed");
    ArrayList<java.net.URL> urls=new ArrayList<>();urls.add(out.toUri().toURL());
    for(String p:System.getProperty("java.class.path").split(java.io.File.pathSeparator))urls.add(Path.of(p).toUri().toURL());
    try(var loader=new URLClassLoader(urls.toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())){
      try{loader.loadClass("com.butang.codextop.RuntimeGoalBatchProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{out.resolve("synthetic-files").toString()});}
      catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("actual owner scenario failed",e.getCause());}
    }
  }

  private static final String MODELS="""
    package org.telegram.tgnet; public class TLRPC {
      public static class User {public long id;public String first_name,last_name;public boolean contact;}
      public static class TL_user extends User{}
      public static class Peer {public long user_id;}
      public static class TL_peerUser extends Peer{}
      public static class Dialog {public long id;public int last_message_date;public Peer peer;}
      public static class TL_dialog extends Dialog{}
    }
    """;
  private static final String CONTROLLER="""
    package org.telegram.messenger;
    import java.util.*;import org.telegram.tgnet.TLRPC;
    public class MessagesController {
      public static final int UPDATE_MASK_STATUS=8,UPDATE_MASK_NAME=16;
      public static MessagesController instance=new MessagesController();
      public final Map<Long,TLRPC.Dialog> dialogs_dict=new HashMap<>();
      public final Map<Long,TLRPC.User> users=new HashMap<>();public boolean dialogsLoaded;
      public static MessagesController getInstance(int account){return instance;}
      public TLRPC.User getUser(long id){return users.get(id);}
      public void putUser(TLRPC.User user,boolean ignored){users.put(user.id,user);}
      public void putDialogsEndReachedAfterRegistration(){}
    }
    """;
  private static final String FIXTURE="""
    package com.butang.codextop;
    import com.google.gson.*;import java.io.File;import java.nio.file.*;import java.util.*;
    import org.telegram.messenger.MessagesController;import org.telegram.messenger.NotificationCenter;import org.telegram.tgnet.TLRPC;
    public final class RuntimeGoalBatchProbe {
      static boolean loggingOut;static PasswordLogin.Session session;static long accountGeneration;
      static SessionStatus.Store statuses;
      static final Map<Long,String> dialogMachines=new HashMap<>(),remoteIds=new HashMap<>(),linkedSessions=new HashMap<>(),dialogTitles=new HashMap<>(),dialogDirectories=new HashMap<>();
      static final List<TLRPC.Dialog> dialogs=new ArrayList<>();static int previews,checks,scenarios;
      static final class Queue {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();void postRunnable(Runnable r){tasks.add(r);}void next(){tasks.remove().run();}void all(){int budget=500;while(!tasks.isEmpty()){if(--budget==0)throw new AssertionError("queue loop");next();}}}
      static final class Utilities {static final Queue globalQueue=new Queue();}
      static final class AndroidUtilities {static final Queue ui=new Queue();static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
      static final class PasswordLogin {static final class Session {final String server="synthetic-server";String accountId="synthetic-account";}}
      static final class DesktopConnection {static String kind="codexHome",home="user";static JsonObject userCodexSource(){JsonObject x=new JsonObject();x.addProperty("kind",kind);x.addProperty("home",home);return x;}}
      static final class ApplicationLoader {static Context applicationContext;}
      static final class Context {final File root;Context(File root){this.root=root;}File getNoBackupFilesDir(){return root;}}
      static int recentDialogLimit(){return 50;}
      static void traceHistoryPoint(String phase,int reason){}
      static SessionStatus.Snapshot status(long id){return statuses.get(id,1000);}
      static long bindDialog(String machine,String remote,PasswordLogin.Session owner,long epoch){if(session!=owner||!isAccountCurrent(epoch))return 0;long id=Long.parseLong(remote.substring(7));dialogMachines.put(id,machine);remoteIds.put(id,remote);return id;}
      static void restoreDialogPreviews(int account,long epoch,String machine,Map<String,Long> ids){Utilities.globalQueue.postRunnable(()->{previews++;check(GoalDisplayStore.probeReads<=1,"preview followed repeated same-file reads");});}
    """;
  private static final String SCENARIOS="""
      static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
      static void reset(Path root,String label)throws Exception{
        scenarios++;Files.createDirectories(root.resolve(label));ApplicationLoader.applicationContext=new Context(root.resolve(label).toFile());
        session=new PasswordLogin.Session();accountGeneration=1;loggingOut=false;statuses=new SessionStatus.Store();SessionStatus.onConfirmedGoal(null);
        dialogMachines.clear();remoteIds.clear();linkedSessions.clear();dialogTitles.clear();dialogDirectories.clear();dialogs.clear();
        Utilities.globalQueue.tasks.clear();AndroidUtilities.ui.tasks.clear();DesktopConnection.kind="codexHome";DesktopConnection.home="user";
        MessagesController.instance=new MessagesController();GoalDisplayStore.probeReads=0;previews=0;
      }
      static JsonObject row(int id){JsonObject x=new JsonObject();x.addProperty("remoteSessionId","remote-"+id);x.addProperty("title","synthetic");x.addProperty("updatedAtMs",id);return x;}
      static void seed(int count)throws Exception{for(int i=1;i<=count;i++)save(i,"old-"+i);GoalDisplayStore.probeReads=0;}
      static void save(int i,String objective)throws Exception{goalDisplayStore(session).save("machine","remote-"+i,"codexHome","user","available",objective,"active",null,1L,2L,3L,null);}
      static void publish(int count){JsonArray x=new JsonArray();for(int i=1;i<=count;i++)x.add(row(i));publishDialogs(0,"machine",x,-1,-1,true);check(AndroidUtilities.ui.tasks.size()==1,"list should be published asynchronously");AndroidUtilities.ui.next();}
      static void drain(){Utilities.globalQueue.all();AndroidUtilities.ui.all();}
      static void goal(int id,String expected){check(expected.equals(statuses.get(id,1000).goal.objective),"wrong goal for "+id+": "+statuses.get(id,1000).goal.objective);}
      static void observe(int id,boolean none,boolean unknown){JsonObject r=new JsonObject();r.addProperty("ok",true);r.addProperty("machineOnline",true);JsonObject g=new JsonObject();
        if(unknown){g.addProperty("availability","unknown");}
        else if(none){g.addProperty("availability","none");g.addProperty("source","desktop");}
        else{g.addProperty("availability","available");g.addProperty("source","desktop");g.addProperty("threadId","remote-"+id);g.addProperty("objective","new-"+id);g.addProperty("status","paused");g.add("tokenBudget",JsonNull.INSTANCE);g.add("tokensUsed",JsonNull.INSTANCE);g.add("timeUsedSeconds",JsonNull.INSTANCE);g.addProperty("updatedAt",5);}
        r.add("goal",g);statuses.observation(id,"machine","remote-"+id,r,unknown?110:100,unknown?111:101);
      }
      static Path goalFile(){return ApplicationLoader.applicationContext.root.toPath().resolve("codex-goals").resolve(TranscriptStore.digest(session.server+"\\n"+session.accountId)+".json");}
      static void mutate(String which){switch(which){
        case "owner":session=new PasswordLogin.Session();break;
        case "epoch":accountGeneration++;break;
        case "logout":loggingOut=true;break;
        case "machine":dialogMachines.put(1L,"different");break;
        case "remote":remoteIds.put(1L,"remote-other");break;
        case "linked":linkedSessions.put(1L,"changed");break;
        case "kind":DesktopConnection.kind="different";break;
        case "home":DesktopConnection.home="different";break;
        default:throw new AssertionError(which);
      }}
      public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);
        reset(root,"read-once");seed(50);publish(50);
        check(Utilities.globalQueue.tasks.size()==51,"per-row tasks and final preview order changed");
        check(GoalDisplayStore.probeReads==0,"UI publication performed disk read");
        Utilities.globalQueue.all();check(GoalDisplayStore.probeReads==1,"cold batch must read once, got "+GoalDisplayStore.probeReads);
        check(AndroidUtilities.ui.tasks.size()==50,"per-row acceptance callbacks changed");AndroidUtilities.ui.all();
        for(int i=1;i<=50;i++){goal(i,"old-"+i);check(!"current".equals(statuses.get(i,1000).goal.validity),"cached fact was promoted");}
        check(previews==1,"preview was dropped");GoalDisplayStore.probeReads=0;publish(50);drain();check(GoalDisplayStore.probeReads==0,"second LIST re-read claimed goals");

        for(String phase:new String[]{"before-bg","before-ui"})for(String change:new String[]{"owner","epoch","logout","machine","remote","linked","kind","home"}){
          reset(root,phase+"-"+change);seed(1);linkedSessions.put(1L,"known");publish(1);
          if(phase.equals("before-ui"))Utilities.globalQueue.next();mutate(change);drain();goal(1,"");
          check(GoalDisplayStore.probeReads==(phase.equals("before-ui")?1:0),"guard failed to suppress disk or callback");
        }
        reset(root,"first-stale-second-valid");seed(2);linkedSessions.put(1L,"known");publish(2);linkedSessions.put(1L,"changed");drain();
        check(GoalDisplayStore.probeReads==1,"first stale row prevented lazy load by next valid row");goal(1,"");goal(2,"old-2");

        reset(root,"source-change-after-shared-read");seed(2);publish(2);Utilities.globalQueue.next();DesktopConnection.home="changed";drain();
        check(GoalDisplayStore.probeReads==1,"changed source caused extra read");goal(1,"");goal(2,"");
        reset(root,"second-binding-change-after-shared-read");seed(2);publish(2);Utilities.globalQueue.next();remoteIds.put(2L,"changed");drain();
        check(GoalDisplayStore.probeReads==1,"changed binding caused extra read");goal(1,"old-1");goal(2,"");

        for(String mode:new String[]{"none","available","none-then-unknown"}){
          reset(root,"new-network-"+mode);seed(2);publish(2);Utilities.globalQueue.next();
          observe(1,!mode.equals("available"),false);observe(2,!mode.equals("available"),false);
          if(mode.equals("none-then-unknown")){observe(1,false,true);observe(2,false,true);}
          drain();check(GoalDisplayStore.probeReads==1,"network interleave broke batch read count");
          for(int i=1;i<=2;i++)goal(i,mode.equals("available")?"new-"+i:"");
        }

        reset(root,"immutable-snapshot");seed(2);publish(2);Utilities.globalQueue.next();int reads=GoalDisplayStore.probeReads;
        save(2,"disk-new-2");GoalDisplayStore.probeReads=reads;drain();goal(2,"old-2");check(GoalDisplayStore.probeReads==1,"batch reread after file replacement");
        statuses=new SessionStatus.Store();GoalDisplayStore.probeReads=0;publish(2);drain();goal(2,"disk-new-2");check(GoalDisplayStore.probeReads==1,"new batch reused prior snapshot");

        reset(root,"single-item-compat");seed(2);
        for(int i=1;i<=2;i++){bindDialog("machine","remote-"+i,session,1);publishDialogFacts(i,"machine",row(i),-1,-1,true);}
        drain();check(GoalDisplayStore.probeReads==2,"non-list single-item entry silently changed scope");goal(1,"old-1");goal(2,"old-2");

        for(String bad:new String[]{"missing","corrupt","oversize","wrong-scope","bad-last-record"}){
          reset(root,"bad-"+bad);seed(3);Path file=goalFile();
          if(bad.equals("missing"))Files.delete(file);
          else if(bad.equals("corrupt"))Files.writeString(file,"{");
          else if(bad.equals("oversize")){try(var f=new java.io.RandomAccessFile(file.toFile(),"rw")){f.setLength(GoalDisplayStore.MAX_BYTES+1L);}}
          else{JsonObject x=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(bad.equals("wrong-scope"))x.addProperty("scope","other");else x.getAsJsonArray("records").get(2).getAsJsonObject().addProperty("status","made-up");Files.writeString(file,x.toString());}
          GoalDisplayStore.probeReads=0;publish(3);drain();check(GoalDisplayStore.probeReads==1,"empty/bad snapshot caused retry per item");for(int i=1;i<=3;i++)goal(i,"");
        }
        System.out.println("PASS "+scenarios+" scenarios / "+checks+" checks; exact Runtime publication/restoration + SessionStatus + GoalDisplayStore owners");
      }
    """;
}
