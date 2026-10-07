package com.butang.codextop;
import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import javax.tools.*;
/** 执行真实列表排序、缓存恢复与UI发布，验证可见摘要优先且不改原历史集合。 */
public class RuntimeDialogPreviewOrderTest {
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
  s=s.replace("return 2;","return 20;");
  s=s.replace("static class TLRPC {","static class TLRPC {static class Dialog {long id;TL_peerUser peer;int last_message_date;}static class TL_dialog extends Dialog{}static class TL_peerUser {long user_id;}");
  s=s.replace("static class MessagesController {","static class MessagesController {boolean dialogsLoaded;final Map<Long,TLRPC.Dialog> dialogs_dict=new HashMap<>();void putDialogsEndReachedAfterRegistration(){}");
  s=s.replace("return new ArrayList<>(rows);","prepared++;outboxLists++;return new ArrayList<>(rows);");
  // 使用真实临时目录路径，保留Store对符号链的严格拒绝。
  Path temp=Files.createTempDirectory("preview83-owner-").toRealPath();
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
 /** 继承旧FIXTURE的日志计数叶；不改变排序或缓存业务断言。 */
 static int lazyPreviewFailures;
 /** 原排序专项关闭新增UI诊断；真实logger由独立专项执行。 */
 static void traceHistoryPoint(String phase,int reason){}
 static MessageObject pending(long id,int n,String local,String body,int date){TLRPC.TL_message m=new TLRPC.TL_message();m.id=n;m.dialog_id=id;m.date=date;m.message=body;m.params.put("codexLocalId",local);MessageObject out=new MessageObject(m);pendingMessages.put(local,out);return out;}
 static final ArrayList<TLRPC.Dialog> dialogs=new ArrayList<>();static final Map<Long,String> dialogTitles=new HashMap<>();
 static java.nio.file.Path cacheRoot;static int outboxLists,prepared,cacheReads;static final ArrayList<String> readOrder=new ArrayList<>();
 static final ArrayList<String> tracePhases=new ArrayList<>();
 /** 追踪仅记录诊断；实际读盘和恢复次数从Store及outbox边界观察，不依赖生产是否带计时。 */
 static long beginHistoryTrace(String phase){return System.nanoTime();}static void traceHistoryDuration(String phase,long start){tracePhases.add(phase);}
 static TranscriptStore transcriptStore(long id){cacheReads++;readOrder.add(remoteIds.get(id));return new TranscriptStore(cacheRoot.toFile(),"synthetic-server","synthetic-account",dialogMachines.get(id));}
 static String statusPresentation(long id){return "synthetic";}static void publishDialogFacts(long id,String machine,JsonObject c,long start,long received,boolean cached){dialogTitles.put(id,"synthetic");}static boolean publishDialogUser(int a,long id,String title){return false;}
 static JsonObject page(int count,String tag){JsonObject p=new JsonObject();JsonArray items=new JsonArray();for(int i=0;i<count;i++){JsonObject item=new JsonObject(),raw=new JsonObject(),content=new JsonObject();item.addProperty("id",tag+"-"+i);item.addProperty("createdAtMs",1000L+i);raw.addProperty("role","agent");content.addProperty("type","text");content.addProperty("text","synthetic "+"x".repeat(512));raw.add("content",content);item.add("raw",raw);items.add(item);}p.add("items",items);p.addProperty("hasMore",false);p.addProperty("historyAvailability","available");p.addProperty("tailCursor","tail-"+tag);return p;}
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 /** 每例使用独立合成缓存子目录并重置队列；原账号、排序、缓存及摘要方法仍原样提取。 */
 static void reset()throws Exception{cacheRoot=java.nio.file.Files.createTempDirectory(cacheRoot.getParent(),"case-");Utilities.globalQueue.tasks.clear();AndroidUtilities.ui.tasks.clear();session=new PasswordLogin.Session();accountGeneration++;loggingOut=false;org.telegram.messenger.UserConfig.selectedAccount=0;desktopConnections.clear();dialogMachines.clear();remoteIds.clear();ids.clear();histories.clear();disk.clear();pendingMessages.clear();outbox.rows.clear();MessagesController.instance.dialogMessage.clear();MessagesController.instance.dialogs_dict.clear();MessagesController.instance.dialogsLoaded=false;dialogs.clear();dialogTitles.clear();readOrder.clear();tracePhases.clear();cacheReads=prepared=outboxLists=0;NotificationCenter.instance.previews=NotificationCenter.instance.other=0;nextId=0;}
 /** 写真实临时缓存；所有正文与身份都是独立合成值。 */
 static JsonArray candidates(String machine,int count,boolean tie,boolean reverse)throws Exception{
  Map<String,Long> keys=new HashMap<>();for(int i=0;i<count;i++)keys.put("synthetic-remote-"+i,(long)i);
  ArrayList<String> order=new ArrayList<>(keys.keySet());String newest=order.remove(order.size()-1);order.add(0,newest);
  TranscriptStore store=new TranscriptStore(cacheRoot.toFile(),"synthetic-server","synthetic-account",machine);JsonArray array=new JsonArray();
  for(int i=0;i<order.size();i++){String remote=order.get(i);TranscriptWindow h=new TranscriptWindow();h.prepend(page(4,machine+remote));store.write(remote,h);JsonObject c=new JsonObject();c.addProperty("remoteSessionId",remote);c.addProperty("updatedAtMs",tie?1000999L:1000999L-i);array.add(c);}
  if(reverse){JsonArray result=new JsonArray();for(int i=array.size()-1;i>=0;i--)result.add(array.get(i));return result;}return array;
 }
 /** 同时接受原整批与逐项让出：以实际Store读取和首个UI摘要观察排序，保留全部超限候选。 */
 static void orderCase(boolean tie,boolean reverse)throws Exception{
  reset();JsonArray rows=candidates("machine",24,tie,reverse);publishDialogs(0,"machine",rows,-1,-1,true);AndroidUtilities.ui.all();
  check(dialogs.size()==20,"recent limit changed");ArrayList<Long> visible=new ArrayList<>();for(TLRPC.Dialog d:dialogs)visible.add(d.id);
  if(tie){ArrayList<String> expected=new ArrayList<>(remoteIds.values());Collections.sort(expected);for(int i=0;i<20;i++)check(expected.get(i).equals(remoteIds.get(visible.get(i))),"same millisecond tie not remote sorted");}
  Map<String,Long> originalBindings=new HashMap<>();for(var row:rows){String remote=row.getAsJsonObject().get("remoteSessionId").getAsString();originalBindings.put(remote,ids.get("machine:"+remote));}
  Map<String,Long> expectedRestore=new LinkedHashMap<>();for(long id:visible)expectedRestore.put(remoteIds.get(id),id);for(var binding:originalBindings.entrySet())expectedRestore.putIfAbsent(binding.getKey(),binding.getValue());
  check(!Utilities.globalQueue.tasks.isEmpty(),"restore never queued");
  for(int i=0;i<20;i++){
   if(!Utilities.globalQueue.tasks.isEmpty())Utilities.globalQueue.one();
   if(i==0){check(!readOrder.isEmpty()&&readOrder.get(0).equals(remoteIds.get(visible.get(0))),"first actual restore is not first visible row");check(!AndroidUtilities.ui.tasks.isEmpty(),"first restore did not queue UI");AndroidUtilities.ui.one();check(MessagesController.instance.dialogMessage.size()==1&&MessagesController.instance.dialogMessage.containsKey(visible.get(0)),"first actual UI summary is not first visible row");}
   AndroidUtilities.ui.all();check(MessagesController.instance.dialogMessage.containsKey(visible.get(i)),"visible rank "+i+" has no summary after its restore turn");check(readOrder.get(i).equals(remoteIds.get(visible.get(i))),"cache restore differs from UI rank "+i);
  }
  Utilities.globalQueue.all();AndroidUtilities.ui.all();check(cacheReads==24&&new HashSet<>(readOrder).size()==24&&histories.size()==24,"hidden originals dropped/duplicate reads");check(readOrder.equals(new ArrayList<>(expectedRestore.keySet())),"remaining originals reordered/dropped/duplicated");
  long top=visible.get(0);TranscriptWindow original=histories.get(top);JsonObject snapshot=original.snapshot();int notices=NotificationCenter.instance.previews;
  publishDialogs(0,"machine",rows,1,2,false);AndroidUtilities.ui.all();Utilities.globalQueue.all();AndroidUtilities.ui.all();
  check(cacheReads==24&&prepared==48&&outboxLists==48&&histories.get(top)==original&&snapshot.equals(original.snapshot()),"repeat list reread or changed history");
  check(notices==NotificationCenter.instance.previews,"repeat list rebuilt equal previews");System.out.println("PASS real UI rank -> first 20 cache summaries; 4 hidden retained; repeated list no reread or mutation tie="+tie+" reversed="+reverse);
 }
 /** 重复候选只沿既有Map恢复一次，不在排序补丁引入额外读盘。 */
 static void duplicateCase()throws Exception{reset();JsonArray rows=candidates("machine",3,false,true);rows.add(rows.get(0).deepCopy());publishDialogs(0,"machine",rows,-1,-1,true);AndroidUtilities.ui.all();Utilities.globalQueue.all();AndroidUtilities.ui.all();check(cacheReads==3&&readOrder.size()==3&&new HashSet<>(readOrder).size()==3,"duplicate remote restored twice");LinkedHashSet<String> expected=new LinkedHashSet<>();for(TLRPC.Dialog d:dialogs)expected.add(remoteIds.get(d.id));check(readOrder.equals(new ArrayList<>(expected)),"duplicate batch changed visible recovery order");System.out.println("PASS duplicate binding only one recovery in visible order");}
 /** 账号代次或选中账号变更后，原排队恢复不能继续读盘或发布。 */
 static void staleCase(boolean queuedUi,boolean selected)throws Exception{reset();JsonArray rows=candidates("machine",3,false,false);publishDialogs(0,"machine",rows,-1,-1,true);if(!queuedUi){AndroidUtilities.ui.all();Utilities.globalQueue.one();}int before=cacheReads;if(selected)org.telegram.messenger.UserConfig.selectedAccount=1;else accountGeneration++;AndroidUtilities.ui.all();Utilities.globalQueue.all();AndroidUtilities.ui.all();check(cacheReads==before&&MessagesController.instance.dialogMessage.isEmpty(),"stale account read/published");System.out.println("PASS queued account boundary UI="+queuedUi+" selected="+selected);}
 /** 同remote的两台电脑沿原绑定与缓存目录恢复各自正文，不从当前全局列表重取绑定。 */
 static void machineCase()throws Exception{reset();JsonArray a=candidates("machine-a",3,false,true),b=candidates("machine-b",3,true,false);publishDialogs(0,"machine-a",a,-1,-1,true);publishDialogs(0,"machine-b",b,-1,-1,true);AndroidUtilities.ui.all();Utilities.globalQueue.all();AndroidUtilities.ui.all();check(cacheReads==6&&histories.size()==6&&MessagesController.instance.dialogMessage.size()==6,"two sources merged/dropped");for(var e:histories.entrySet())check(e.getValue().before(0,1).get(0).message.id.startsWith(dialogMachines.get(e.getKey())),"wrong machine cache");System.out.println("PASS same remote two captured machine owners remain distinct");}
 public static void main(String[] args)throws Exception{cacheRoot=java.nio.file.Path.of(args[0]);orderCase(false,false);orderCase(false,true);orderCase(true,true);duplicateCase();staleCase(true,false);staleCase(false,false);staleCase(false,true);machineCase();System.out.println("RuntimeDialogPreviewOrderTest: 8 owner groups PASS");}
 """;
}
