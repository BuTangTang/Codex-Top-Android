package com.butang.codextop;
import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import javax.tools.*;
/** 执行真实本地摘要恢复及发布方法，验证首次连接就绪、晚回包与原账号/来源边界。 */
public class RuntimeLocalPreviewReadyTest {
 /** 可选冻结Runtime源用于先在同一断言上获得旧版RED；不访问真实应用缓存。 */
 public static void main(String[] args) throws Exception {
  Path repo=Path.of(".");
  StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  Path source=repo.resolve("TMessagesProj/src/main/java/com/butang/codextop");
  var sourceUnit=StaticJavaParser.parse(args.length==0?source.resolve("CodexRuntime.java"):Path.of(args[0]));
  var fixture=Class.forName("com.butang.codextop.RuntimeDialogPreviewTest").getDeclaredField("FIXTURE");fixture.setAccessible(true);
  String base=(String)fixture.get(null);
  var unit=StaticJavaParser.parse(base+"}");var owner=unit.getClassByName("RuntimeDialogPreviewProbe").orElseThrow();
  owner.getMethodsByName("readHistory").forEach(m->m.remove());
  var names=Set.of("isAccountCurrent","dialogConnection","restoreDialogPreviews","publishHistoryPreview","publishPendingPreview","publishDialogPreview","sameDialogPreview","previewCurrent","publishDialogs","readHistory");
  sourceUnit.getClassByName("CodexRuntime").orElseThrow().getMethods().stream().filter(m->names.contains(m.getNameAsString())).forEach(m->owner.addMember(m.clone()));
  String s=unit.toString();s=s.substring(0,s.lastIndexOf('}'))+EXTRA+"}";
  s=s.replace("static final int UPDATE_MASK_MESSAGE_TEXT = 4;","static final int UPDATE_MASK_MESSAGE_TEXT = 4,UPDATE_MASK_STATUS=8,UPDATE_MASK_NAME=16;");
  s=s.replace("static final int updateInterfaces = 1;","static final int updateInterfaces = 1,dialogsNeedReload=2;");
  s=s.replace("static class TLRPC {","static class TLRPC {static class Dialog {long id;TL_peerUser peer;int last_message_date;}static class TL_dialog extends Dialog{}static class TL_peerUser {long user_id;}");
  s=s.replace("static class MessagesController {","static class MessagesController {boolean dialogsLoaded;final Map<Long,TLRPC.Dialog> dialogs_dict=new HashMap<>();void putDialogsEndReachedAfterRegistration(){}");
  s=s.replace("return new ArrayList<>(rows);","prepared++;outboxLists++;return new ArrayList<>(rows);");
  Path temp=Files.createTempDirectory("local-preview-ready-");
  try {
   Path p=temp.resolve("RuntimeDialogPreviewProbe.java");Files.writeString(p,s);
   Files.writeString(temp.resolve("UserConfig.java"),"package org.telegram.messenger;public class UserConfig {public static int selectedAccount;}");
   var compile=new ArrayList<String>(List.of("-cp",System.getProperty("java.class.path"),"-d",temp.toString(),p.toString(),temp.resolve("UserConfig.java").toString()));
   for(String n:new String[]{"DesktopAttachment","TranscriptText","TranscriptWindow","TranscriptStore"})compile.add(source.resolve(n+".java").toString());
   if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(new String[0]))!=0)throw new AssertionError("compile");
   var urls=new ArrayList<URL>();urls.add(temp.toUri().toURL());for(String cp:System.getProperty("java.class.path").split(java.io.File.pathSeparator))urls.add(Path.of(cp).toUri().toURL());
   try(var loader=new URLClassLoader(urls.toArray(new URL[0]),ClassLoader.getPlatformClassLoader())) {loader.loadClass("com.butang.codextop.RuntimeDialogPreviewProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{temp.resolve("cache").toString()});}
  } finally {try(var walk=Files.walk(temp)){for(Path p:walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
 }
 static final String EXTRA="""
 static class HistoryView{}
 static MessageObject pending(long id,int n,String local,String body,int date){TLRPC.TL_message m=new TLRPC.TL_message();m.id=n;m.dialog_id=id;m.date=date;m.message=body;m.params.put("codexLocalId",local);MessageObject out=new MessageObject(m);pendingMessages.put(local,out);return out;}
 static final ArrayList<TLRPC.Dialog> dialogs=new ArrayList<>();static final Map<Long,String> dialogTitles=new HashMap<>();
 static java.nio.file.Path cacheRoot;static int outboxLists,prepared,cacheReads;static final ArrayList<String> readOrder=new ArrayList<>();
 static final ArrayList<String> tracePhases=new ArrayList<>();
 /** 追踪仅记录诊断；实际读盘和恢复次数从Store及outbox边界观察，不依赖生产是否带计时。 */
 static long beginHistoryTrace(String phase){return System.nanoTime();}static void traceHistoryDuration(String phase,long start){tracePhases.add(phase);}
 static TranscriptStore transcriptStore(long id){cacheReads++;if(readHook!=null){Runnable hook=readHook;readHook=null;hook.run();}readOrder.add(remoteIds.get(id));return new TranscriptStore(cacheRoot.toFile(),"synthetic-server","synthetic-account",dialogMachines.get(id));}
 static String statusPresentation(long id){return "synthetic";}static void publishDialogFacts(long id,String machine,JsonObject c,long start,long received,boolean cached){dialogTitles.put(id,"synthetic");}static boolean publishDialogUser(int a,long id,String title){return false;}
 static JsonObject page(int count,String tag){JsonObject p=new JsonObject();JsonArray items=new JsonArray();for(int i=0;i<count;i++){JsonObject item=new JsonObject(),raw=new JsonObject(),content=new JsonObject();item.addProperty("id",tag+"-"+i);item.addProperty("createdAtMs",1000L+i);raw.addProperty("role","agent");content.addProperty("type","text");content.addProperty("text","synthetic "+"x".repeat(512));raw.add("content",content);item.add("raw",raw);items.add(item);}p.add("items",items);p.addProperty("hasMore",false);p.addProperty("historyAvailability","available");p.addProperty("tailCursor","tail-"+tag);return p;}
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 /** 每例只重置合成队列和数据，原账号、排序、缓存及摘要方法仍原样提取。 */
 static void reset(){Utilities.globalQueue.tasks.clear();AndroidUtilities.ui.tasks.clear();session=new PasswordLogin.Session();accountGeneration++;loggingOut=false;org.telegram.messenger.UserConfig.selectedAccount=0;desktopConnections.clear();dialogMachines.clear();remoteIds.clear();ids.clear();histories.clear();disk.clear();pendingMessages.clear();outbox.rows.clear();MessagesController.instance.dialogMessage.clear();MessagesController.instance.dialogs_dict.clear();MessagesController.instance.dialogsLoaded=false;dialogs.clear();dialogTitles.clear();readOrder.clear();tracePhases.clear();cacheReads=prepared=outboxLists=0;NotificationCenter.instance.previews=NotificationCenter.instance.other=0;nextId=0;}
 /** 写真实临时缓存；所有正文与身份都是独立合成值。 */
 static JsonArray candidates(String machine,int count,boolean tie,boolean reverse)throws Exception{
  Map<String,Long> keys=new HashMap<>();for(int i=0;i<count;i++)keys.put("synthetic-remote-"+i,(long)i);
  ArrayList<String> order=new ArrayList<>(keys.keySet());String newest=order.remove(order.size()-1);order.add(0,newest);
  TranscriptStore store=new TranscriptStore(cacheRoot.toFile(),"synthetic-server","synthetic-account",machine);JsonArray array=new JsonArray();
  for(int i=0;i<order.size();i++){String remote=order.get(i);TranscriptWindow h=new TranscriptWindow();h.prepend(page(4,machine+remote));store.write(remote,h);JsonObject c=new JsonObject();c.addProperty("remoteSessionId",remote);c.addProperty("updatedAtMs",tie?1000999L:1000999L-i);array.add(c);}
  if(reverse){JsonArray result=new JsonArray();for(int i=array.size()-1;i>=0;i--)result.add(array.get(i));return result;}return array;
 }
 static Runnable readHook;static int failures,passed;static DesktopConnection ready;
 /** 就绪事件替代实际传输；仅安装既有同machine连接对象，无RPC、磁盘或队列轮询。 */
 static void installReady(){ready=new DesktopConnection("machine");desktopConnections.put("machine",ready);}
 static long arrange(boolean connected)throws Exception{reset();readHook=null;ready=null;JsonArray rows=candidates("machine",1,false,false);publishDialogs(0,"machine",rows,-1,-1,true);AndroidUtilities.ui.all();long id=dialogs.get(0).id;if(connected)installReady();return id;}
 static MessageObject visible(long id){var rows=MessagesController.instance.dialogMessage.get(id);return rows==null||rows.isEmpty()?null:rows.get(0);}
 static void pump(){Utilities.globalQueue.all();AndroidUtilities.ui.all();check(Utilities.globalQueue.tasks.isEmpty()&&AndroidUtilities.ui.tasks.isEmpty(),"unbounded repeat");for(var c:desktopConnections.values())check(c.calls==0,"added RPC");}
 /** 两个真实窗口分别停在完整Store读取中及原UI队列中；就绪后仍只读一次、不发RPC。 */
 static void nullReady(boolean duringRead)throws Exception{long id=arrange(false);if(duringRead)readHook=()->installReady();Utilities.globalQueue.one();if(!duringRead)installReady();pump();check(visible(id)!=null,"null-to-ready discarded complete local cache duringRead="+duringRead);check(cacheReads==1&&outboxLists==1,"ready caused duplicate disk/outbox work");check(NotificationCenter.instance.previews==1,"not exactly one preview");}
 static void unchanged(boolean connected)throws Exception{long id=arrange(connected);pump();check(visible(id)!=null,"unchanged owner empty");check(cacheReads==1,"duplicate read");}
 /** 身份改变必须废弃旧项；同epoch换Session单列验证引用，不能只靠数字代次。 */
 static void identity(String kind,boolean duringRead)throws Exception{long id=arrange(kind.equals("connection"));Runnable change=()->{switch(kind){case "connection":installReady();break;case "account":org.telegram.messenger.UserConfig.selectedAccount=1;break;case "epoch":accountGeneration++;break;case "logout":loggingOut=true;break;case "nullsession":session=null;break;case "sessionref":session=new PasswordLogin.Session();break;case "machine":dialogMachines.put(id,"another-machine");break;case "remote":remoteIds.put(id,"another-remote");break;case "root":histories.put(id,new TranscriptWindow());break;default:throw new AssertionError(kind);}};if(duringRead)readHook=change;Utilities.globalQueue.one();if(!duringRead)change.run();pump();check(visible(id)==null,"stale "+kind+" published duringRead="+duringRead);}
 static TLRPC.TL_message network(long id){TLRPC.TL_message m=new TLRPC.TL_message();m.id=919;m.date=9;m.dialog_id=id;m.message="synthetic newer network";return m;}
 /** 网络先发布后才消费缓存回调，直接断言真实dialogMessage对象不回退。 */
 static void networkWins(boolean startsConnected)throws Exception{long id=arrange(startsConnected);Utilities.globalQueue.one();if(!startsConnected)installReady();publishDialogPreview(0,accountGeneration,id,remoteIds.get(id),"machine",ready,network(id),null,Set.of());MessageObject newer=visible(id);check(newer!=null,"network preview setup failed");pump();check(visible(id)==newer,"late local cache overwrote already-published network result startsConnected="+startsConnected);}
 static void genericNetworkStrict()throws Exception{long id=arrange(false);Utilities.globalQueue.tasks.clear();TranscriptWindow root=new TranscriptWindow();root.prepend(page(1,"network"));publishHistoryPreview(0,accountGeneration,id,remoteIds.get(id),"machine",null,root);installReady();AndroidUtilities.ui.all();check(visible(id)==null,"generic network publisher null identity was relaxed");}
 static void newerPendingWins()throws Exception{long id=arrange(false);Utilities.globalQueue.one();installReady();MessageObject newer=pending(id,-7,"new-send","synthetic new pending",20);publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(newer)),true);pump();check(visible(id)==newer,"late cache replaced new pending");}
 static void outbox(boolean newest,boolean becomesReady)throws Exception{long id=arrange(false);outbox.rows.add(new OutboxStore.Item("pending","synthetic pending",-7,newest?20:0));Utilities.globalQueue.one();if(becomesReady)installReady();pump();check(visible(id)!=null,"outbox local result discarded at ready");check(visible(id).messageOwner.message.equals(newest?"synthetic pending":"synthetic "+"x".repeat(512)),"pending/history date priority changed");}
 /** 沿真实Store记录回显身份，保留原待发已回显后释放到正文的行为。 */
 static void echoPending(boolean selectedConflict)throws Exception{long id=arrange(false);String remote=remoteIds.get(id);TranscriptWindow h=new TranscriptWindow();JsonObject echoed=page(1,"echo");JsonObject item=echoed.getAsJsonArray("items").get(0).getAsJsonObject();item.addProperty("localId","echoed-send");item.getAsJsonObject("raw").addProperty("role","user");h.prepend(echoed);JsonObject response=page(1,"answer");response.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("createdAtMs",5000);h.append(response);new TranscriptStore(cacheRoot.toFile(),"synthetic-server","synthetic-account","machine").write(remote,h);if(selectedConflict){MessageObject prior=pending(id,-8,"echoed-send","synthetic echoed pending",1);publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(prior)),true);Utilities.globalQueue.tasks.clear();restoreDialogPreviews(0,accountGeneration,"machine",Map.of(remote,id));outbox.rows.add(new OutboxStore.Item("other-batch","synthetic another pending",-9,6));}Utilities.globalQueue.one();if(!selectedConflict){MessageObject prior=pending(id,-8,"echoed-send","synthetic echoed pending",1);publishPendingPreview(0,accountGeneration,id,new ArrayList<>(List.of(prior)),true);}pump();check(visible(id)!=null&&visible(id).messageOwner.id>0,"captured real echo did not release pending selectedConflict="+selectedConflict);}
 static void run(String name,Checked test){try{test.run();passed++;System.out.println("PASS "+name);}catch(Throwable t){failures++;System.out.println("FAIL "+name+": "+t);}}
 interface Checked{void run()throws Exception;}
 public static void main(String[] args)throws Exception{cacheRoot=java.nio.file.Path.of(args[0]);run("null ready during complete Store read",()->nullReady(true));run("null ready during delayed UI",()->nullReady(false));run("offline unchanged",()->unchanged(false));run("connected unchanged",()->unchanged(true));for(String s:new String[]{"connection","account","epoch","logout","nullsession","sessionref","machine","remote"})for(boolean read:new boolean[]{false,true})run(s+" duringRead="+read,()->identity(s,read));run("replacement root delayed UI",()->identity("root",false));run("new network wins null-ready",()->networkWins(false));run("new network wins connected",()->networkWins(true));run("generic network strict",()->genericNetworkStrict());run("new pending wins",()->newerPendingWins());for(boolean newest:new boolean[]{false,true})for(boolean ready:new boolean[]{false,true})run("pending priority newest="+newest+" ready="+ready,()->outbox(newest,ready));run("newly visible pending already echoed",()->echoPending(false));run("other selected batch does not suppress echoed history",()->echoPending(true));System.out.println("TOTAL passed="+passed+" failures="+failures);if(failures>0)throw new AssertionError("failures="+failures);}

 """;
}
