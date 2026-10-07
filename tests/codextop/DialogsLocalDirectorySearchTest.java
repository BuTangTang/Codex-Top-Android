package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import java.net.*;
/** 从实际搜索 owner 提取完整查询、结果索引和页目录方法；平台叶仅记录请求/队列。 */
public final class DialogsLocalDirectorySearchTest {
 /** 默认从仓库根读取真实 owner；可传独立源码根与 async 场景名重放旧反例，临时文件始终清理。 */
 public static void main(String[] args)throws Exception{
  Path root=(args.length==0?Path.of("."):Path.of(args[0])).toAbsolutePath();
  Path out=Files.createTempDirectory("codextop-directory-search-");
  try { run(root,out,args.length>1?args[1]:"all"); }
  finally { try(var files=Files.walk(out)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);} }
 }
 /** 提取实际搜索、代次与页目录方法；只将 Android 控件、请求和数据库叶替换成合成边界。 */
 private static void run(Path root,Path out,String scenario)throws Exception{
  StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  Path base=root.resolve("TMessagesProj/src/main/java/");
  var d=StaticJavaParser.parse(base.resolve("org/telegram/ui/DialogsActivity.java")).getClassByName("DialogsActivity").orElseThrow();
  var a=StaticJavaParser.parse(base.resolve("org/telegram/ui/Adapters/DialogsSearchAdapter.java")).getClassByName("DialogsSearchAdapter").orElseThrow();
  var pager=StaticJavaParser.parse(base.resolve("org/telegram/ui/Components/SearchViewPager.java")).getClassByName("SearchViewPager").orElseThrow();
  String runtimeText=Files.readString(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"));var runtime=StaticJavaParser.parse(runtimeText).getClassByName("CodexRuntime").orElseThrow();
  String rt=RT;
  for(String n:List.of("dialogs","ownsConversation","conversationInfo","loggedIn"))rt+=runtime.getMethodsByName(n).get(0)+"\n";
  rt+=runtime.getMembers().stream().filter(x->x.isClassOrInterfaceDeclaration()&&x.asClassOrInterfaceDeclaration().getNameAsString().equals("ConversationInfo")).findFirst().orElseThrow()+"\n}";
  Path src=out.resolve("src"),cls=out.resolve("classes");Files.createDirectories(src.resolve("com/butang/codextop"));Files.createDirectories(src.resolve("org/telegram/tgnet"));Files.createDirectories(src.resolve("org/telegram/ui"));Files.createDirectories(cls);
  Files.writeString(src.resolve("com/butang/codextop/CodexRuntime.java"),rt);Files.writeString(src.resolve("org/telegram/tgnet/TLRPC.java"),MODELS);
  var helper=StaticJavaParser.parse(root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java").getParent().getParent().getParent().getParent().resolve("org/telegram/ui/Adapters/SearchAdapterHelper.java")).getClassByName("SearchAdapterHelper").orElseThrow();
  var helperMethod=helper.getMethodsByName("queryServerSearch").stream().filter(x->x.getParameters().size()==12).findFirst().orElseThrow();
  String nullQuery=helperMethod.getBody().orElseThrow().getStatements().subList(0,3).stream().map(Object::toString).reduce("",(x,y)->x+y);
  String code="package org.telegram.ui;import java.util.*;import org.telegram.tgnet.TLRPC;import org.telegram.tgnet.TLRPC.TLObject;\n"+LEAVES.replace("__ACTUAL_NULL_QUERY__",nullQuery)+"\nclass DialogsActivity {\n"+DIALOG;
  code+=d.getMethodsByName("shouldDeferCodexSearchPreload").get(0)+"\n";
  code+=d.getMethodsByName("isCodexDirectorySearch").isEmpty()?"public boolean isCodexDirectorySearch(){return false;}\n":d.getMethodsByName("isCodexDirectorySearch").get(0)+"\n";

  if(d.getMethodsByName("updateCodexSearchIntent").isEmpty())code+="void updateCodexSearchIntent(boolean s, boolean dl){}\n";
  for(String n:List.of("isCodexSearchRoot","updateCodexSearchIntent","setShowSearch")){ if(!d.getMethodsByName(n).isEmpty()) code+=d.getMethodsByName(n).get(0)+"\n"; }
  code+="}\nclass Adapter {\n"+ADAPTER;
  for(String n:List.of("searchDialogs","searchCodexDirectory","resetCodexSearch","updateSearchResults","setRecentSearch","isSearching","getRecentItemsCount","isRecentSearchDisplayed","recentSearchAvailable","hasRecentSearch","hasHints","getItemCount","getItem","isGlobalSearch","loadMoreSearchMessages")){
   if(a.getMethodsByName(n).isEmpty())continue;var method=a.getMethodsByName(n).get(0).clone();method.getAnnotations().clear();method.setPrivate(false);code+=method+"\n";
  }
  var ctor=a.getConstructors().get(0);var init=new StringBuilder("void initRecent(){");
  var statements=ctor.getBody().getStatements();for(int i=0;i<statements.size();i++){var st=statements.get(i);if(st.toString().contains("loadRecentSearch();")||st.toString().contains("loadHints(true)"))init.append(st);}
  code+=init+"}\n";
  var ctorMethods=ctor.findAll(MethodDeclaration.class);
  for(String n:List.of("onDataSetChanged","onSetHashtags","canApplySearchResults")) {
   var m=ctorMethods.stream().filter(x->x.getNameAsString().equals(n)).findFirst().orElseThrow().clone();m.getAnnotations().clear();code+=m+"\n";
  }
  var sponsored=a.getMethodsByName("searchDialogs").get(0).findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream().filter(x->x.getTarget().toString().equals("sponsoredReqId")&&x.getValue().toString().contains("sendRequest")).findFirst().orElseThrow();
  var sponsoredLambda=sponsored.findAll(com.github.javaparser.ast.expr.LambdaExpr.class).stream().filter(x->x.getParameters().isEmpty()).findFirst().orElseThrow();
  code+="void sponsoredComplete(TLObject res, int sponsoredSearchId) "+sponsoredLambda.getBody()+"\n";

  var filterLambda=a.getMethodsByName("searchDialogsInternal").get(0).findAll(com.github.javaparser.ast.expr.LambdaExpr.class).stream().filter(x->x.getBody().toString().contains("filtersDelegate.updateFiltersView")&&x.findAll(com.github.javaparser.ast.expr.LambdaExpr.class).size()==1).findFirst().orElseThrow();
  code+="void filtersComplete(int searchId) "+filterLambda.getBody()+"\n";
  // 抽取真实空查询重置编号的赋值，不手写代次策略。
  for(String[] pair:new String[][]{{"searchMessagesInternal","lastReqId"},{"searchForumMessagesInternal","lastForumReqId"},{"searchDialogs","lastSearchId"}}){
   var assignment=a.getMethodsByName(pair[0]).get(0).findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream().filter(x->x.getTarget().toString().equals(pair[1])).findFirst().orElseThrow();code+="void clear_"+pair[1]+"(){"+assignment+";}\n";
  }
  code+="}\nclass Pager {\n"+PAGER;
  for(String n:List.of("resetCodexSearchMode","showDownloads","getPositionForType")){ if(!pager.getMethodsByName(n).isEmpty())code+=pager.getMethodsByName(n).get(0)+"\n"; }

  var adapter=pager.getMembers().stream().filter(x->x.isClassOrInterfaceDeclaration()&&x.asClassOrInterfaceDeclaration().getNameAsString().equals("ViewPagerAdapter")).findFirst().orElseThrow().asClassOrInterfaceDeclaration();code+=adapter.getMethodsByName("updateItems").get(0)+"\n}\n"+CASES;
  Files.writeString(src.resolve("org/telegram/ui/Probe.java"),code);
  List<String> opts=new ArrayList<>(List.of("--release","8","-d",cls.toString()));try(var files=Files.walk(src)){files.filter(x->x.toString().endsWith(".java")).forEach(x->opts.add(x.toString()));}
  if(ToolProvider.getSystemJavaCompiler().run(null,null,null,opts.toArray(String[]::new))!=0)throw new AssertionError("actual owner fixture compile");
  try(var loader=new URLClassLoader(new URL[]{cls.toUri().toURL()},ClassLoader.getPlatformClassLoader())){try{loader.loadClass("org.telegram.ui.Probe").getMethod("main",String[].class).invoke(null,(Object)new String[]{scenario});}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("actual search owner",e.getCause());}}
 }
 static final String DIALOG="""
 static final int DIALOGS_TYPE_DEFAULT=0,DIALOGS_TYPE_BOT_REQUEST_PEER=1,DIALOGS_TYPE_IMPORT_HISTORY_GROUPS=2,DIALOGS_TYPE_ADD_USERS_TO=3,DIALOGS_TYPE_GROUPS_ONLY=4,DIALOGS_TYPE_CHANNELS_ONLY=5,DIALOGS_TYPE_USERS_ONLY=6,DIALOGS_TYPE_BOT_SHARE=7,DIALOGS_TYPE_START_ATTACH_BOT=8;
 boolean codexSpecialSearchIntent,searchIsShowed,searching;int initialSearchType=-1; Pager searchViewPager; Field fragmentSearchField=new Field();
 void intent(boolean show,boolean downloads){updateCodexSearchIntent(show,downloads);}
 void showSearch(boolean show,boolean downloads,boolean animated,boolean force){updateCodexSearchIntent(show,downloads);searchIsShowed=show;}
 static class Field{Edit editText=new Edit();}static class Edit{void setText(String s){}void setSelection(int i){}}
 boolean hasMainTabs=true,onlySelect,inPreviewMode,allowUsers=true;int initialDialogsType,folderId;long communityId;String searchString;
 """;
 static final String ADAPTER="""
 DialogsActivity dialogsActivity=new DialogsActivity();int currentAccount=0,dialogsType=0,needMessagesSearch=1;
 String lastSearchText,lastMessagesSearchString,sponsoredQuery,filteredRecentQuery,publicPostsHashtag;int lastReqId,lastForumReqId,lastLocalSearchId,lastGlobalSearchId,folderId,lastSearchId,lastMessagesSearchId,waitingResponseCount,sponsoredReqId,searchHashtagRequest=-1,publicPostsTotalCount,publicPostsLastRate,reqForumId,reqId,currentItemCount,localMessagesLoadingRow;
 Runnable searchRunnable,searchRunnable2,searchHashtagRunnable;boolean searchWas,messagesSearchEndReached,localMessagesSearchEndReached,globalSearchCollapsed,phoneCollapsed,localTipArchive,forceLoadingMessages;
 ArrayList<Object> searchResult=new ArrayList<>(),searchContacts=new ArrayList<>();ArrayList<CharSequence> searchResultNames=new ArrayList<>();ArrayList<MessageObject> searchResultMessages=new ArrayList<>(),searchForumResultMessages=new ArrayList<>(),publicPosts=new ArrayList<>();ArrayList<String> searchResultHashtags=new ArrayList<>();ArrayList<TLRPC.TL_sponsoredPeer>sponsoredPeers=new ArrayList<>();ArrayList<TLRPC.TL_forumTopic>searchTopics=new ArrayList<>();ArrayList<Object>localTipDates=new ArrayList<>();ArrayList<RecentSearchObject>filtered2RecentSearchObjects=new ArrayList<>(),filteredRecentSearchObjects=new ArrayList<>();
 SearchAdapterHelper searchAdapterHelper=new SearchAdapterHelper(this);Delegate delegate=new Delegate();FilterDelegate filtersDelegate;enum Filter{All,Other} Filter currentMessagesFilter=Filter.All;
 int notifications;void notifyDataSetChanged(){notifications++;}void filterRecent(String q){}void searchDialogsInternal(String q,int id){Counts.db++;}void searchTopics(String q){}void searchMessagesInternal(String q,int id){Counts.telegram++;}void searchForumMessagesInternal(String q,int id){}void loadRecentSearch(){Counts.db++;}
 boolean filter(Object o){return true;}
 ArrayList<RecentSearchObject>recentSearchObjects=new ArrayList<>();LongSparseArray<RecentSearchObject>recentSearchObjectsById=new LongSparseArray<>();
 static class RecentSearchObject{TLObject object;long did;}
 """;
 static final String PAGER="""
 DialogsActivity parent;Adapter dialogsSearchAdapter; Pager viewPagerAdapter=this;int selected;String lastSearchString;ArrayList<Object>currentSearchFilters=new ArrayList<>();
 void setPosition(int p){selected=p;}void updateTabs(){updateItems();}Tabs tabs=new Tabs();Tabs getTabsView(){return tabs;}
 static class Tabs{int current;int getCurrentTabId(){return current;}void scrollToTab(int id,int p){current=p;}}
 ArrayList<Item>items=new ArrayList<>();long communityId;boolean expandedPublicPosts,showOnlyDialogsAdapter;boolean includeDownloads(){return true;}
 static class ViewPagerAdapter{static final int FILTER_TYPE=6;}
 static final int DIALOGS_TYPE=1,PUBLIC_POSTS_TYPE=2,CHANNELS_TYPE=3,BOTS_TYPE=4,POSTS_TYPE=5,FILTER_TYPE=6,DOWNLOADS_TYPE=7;
 static class Item{int type,filterIndex;Item(int t){type=t;}}
 """;
 static final String RT="""
 package com.butang.codextop;import java.util.*;import org.telegram.tgnet.TLRPC;
 public class CodexRuntime {
 public static boolean on=true,sessionRestored=true,loggingOut;public static Object session=new Object();public static int restores;
 public static boolean enabled(){return on;}static void restoreSession(){restores++;}
 public static final ArrayList<TLRPC.Dialog>dialogs=new ArrayList<>();public static final Map<Long,String>remoteIds=new HashMap<>(),dialogMachines=new HashMap<>(),dialogTitles=new HashMap<>(),dialogDirectories=new HashMap<>();public static final Map<String,String>machineNames=new HashMap<>();
 """;
 static final String MODELS="""
 package org.telegram.tgnet;import java.util.*;public class TLRPC {
 public static class TLObject{}public static class Dialog{public int flags,folder_id;public long id;public Dialog(long n){id=n;}}public static class TL_dialog extends Dialog{public TL_dialog(){super(0);}}
 public static class User extends TLObject{public long id;public String first_name;public User(long n,String s){id=n;first_name=s;}}
 public static class Chat extends TLObject{public long id;}public static class EncryptedChat extends TLObject{public long id;}
 public static class TL_forumTopic extends TLObject{}public static class TL_sponsoredPeer extends TLObject{}
 public static class Message extends TLObject{}
 public static class TL_contacts_getSponsoredPeers extends TLObject{public String q;}
 public static class TL_contacts_sponsoredPeersEmpty extends TLObject{}
 public static class TL_contacts_sponsoredPeers extends TLObject{public ArrayList<User>users=new ArrayList<>();public ArrayList<Chat>chats=new ArrayList<>();public ArrayList<TL_sponsoredPeer>peers=new ArrayList<>();}
 public static class TL_inputPeerEmpty extends TLObject{}
 public static class TL_channels_searchPosts extends TLObject{public int flags,limit;public String hashtag;public TL_inputPeerEmpty offset_peer;}
 public static class messages_Messages extends TLObject{public ArrayList<User>users=new ArrayList<>();public ArrayList<Chat>chats=new ArrayList<>();public ArrayList<Message>messages=new ArrayList<>();public int next_rate;}
 public static class TL_messages_messages extends messages_Messages{}public static class TL_messages_messagesSlice extends messages_Messages{public int count;}
 }
 """;
 static final String LEAVES="""
 class Counts{static int telegram,db,queue;static void reset(){telegram=db=queue=0;}}
 class UserConfig{static int selectedAccount;static UserConfig getInstance(int i){return new UserConfig();}boolean isPremium(){return false;}}
 class MessagesController{static final MessagesController instance=new MessagesController();final Map<Long,TLRPC.User>users=new HashMap<>();static MessagesController getInstance(int i){return instance;}TLRPC.User getUser(long id){return users.get(id);}TLRPC.Chat getChat(long id){return null;}boolean isSponsoredDisabled(){return false;}void putUsers(Object o,boolean b){}void putChats(Object o,boolean b){}void putUser(TLRPC.User u,boolean b){}void putChat(TLRPC.Chat c,boolean b){}void putEncryptedChat(TLRPC.EncryptedChat c,boolean b){}LongSparseArray<TLRPC.Dialog>dialogs_dict=new LongSparseArray<>();ArrayList<TLRPC.Dialog>all=new ArrayList<>();ArrayList<TLRPC.Dialog>getAllDialogs(){return all;}void sortDialogs(Object o){}}
 class MediaDataController{static final MediaDataController instance=new MediaDataController();final ArrayList<Object>hints=new ArrayList<>();static MediaDataController getInstance(int i){return instance;}void loadHints(boolean b){Counts.db++;}}
 class TextUtils{static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
 class Utilities{static final Queue searchQueue=new Queue();}class Queue{final ArrayList<Runnable>list=new ArrayList<>();void postRunnable(Runnable r,int n){Counts.queue++;list.add(r);}void cancelRunnable(Runnable r){list.remove(r);}}
 class AndroidUtilities{static final ArrayList<Runnable> pending=new ArrayList<>();static void runOnUIThread(Runnable r){Counts.queue++;pending.add(r);}static void runOnUIThread(Runnable r,int n){Counts.queue++;pending.add(r);}static void cancelRunOnUIThread(Runnable r){pending.removeIf(x->x==r);}}
 class ConnectionsManager{static int cancelled;static final ConnectionsManager one=new ConnectionsManager();static ConnectionsManager getInstance(int i){return one;}void cancelRequest(int n,boolean b){cancelled++;}int sendRequest(TLObject o,java.util.function.BiConsumer<TLObject,Object>c){Counts.telegram++;return 1;}}
 class SearchAdapterHelper{final Adapter delegate;SearchAdapterHelper(Adapter d){delegate=d;}final ArrayList<Integer>pendingRequestIds=new ArrayList<>();int currentAccount;final ArrayList<Object>groupSearch=new ArrayList<>();final Map<Object,Object>groupSearchMap=new HashMap<>(),globalSearchMap=new HashMap<>(),phoneSearchMap=new HashMap<>();ArrayList<TLObject>globalAlias(){return global;}ArrayList<TLObject>localAlias(){return local;}ArrayList<Object>phoneAlias(){return phone;}final ArrayList<TLObject>global=new ArrayList<>(),local=new ArrayList<>();final ArrayList<Object>phone=new ArrayList<>();final ArrayList<TLObject>globalSearch=global,localServerSearch=local;final ArrayList<Object>phonesSearch=phone;void clear(){global.clear();local.clear();}ArrayList<TLObject>getGlobalSearch(){return global;}ArrayList<TLObject>getLocalServerSearch(){return local;}ArrayList<Object>getPhoneSearch(){return phone;}void unloadRecentHashtags(){}void mergeResults(Object a,Object b){}void queryServerSearch(Object...a){String query=(String)a[0];int searchId=(Integer)a[9];__ACTUAL_NULL_QUERY__ Counts.telegram++;}boolean loadRecentHashtags(){Counts.db++;return false;}static class HashtagObject{String hashtag;}ArrayList<HashtagObject>getHashtags(){return new ArrayList<>();}}
 class LongSparseArray<T>{final Map<Long,T> map=new HashMap<>();T get(long id){return map.get(id);}void put(long id,T t){map.put(id,t);}}
 class ChatObject{static boolean isChannel(TLRPC.Chat c){return false;}}class ContactsController{static class Contact{}}
 class MessagesStorage{static MessagesStorage getInstance(int i){return new MessagesStorage();}void getDialogFolderId(long id,java.util.function.IntConsumer c){}}
 class MessageObject{MessageObject(int a,TLRPC.Message m,boolean b,boolean c){}}
 class Delegate{int states,animations;boolean loading;long getSearchForumDialogId(){return 0;}void searchStateChanged(boolean b,boolean a){states++;loading=b;}void runResultsEnterAnimation(){animations++;}}
 class FilterDelegate{int updates;void updateFiltersView(boolean b,Object a,Object c,boolean d){updates++;}}
 """;
 static final String CASES="""
 public class Probe {
 static int checks,cases;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void add(long id,String title,String machine,String directory){com.butang.codextop.CodexRuntime.dialogs.add(new TLRPC.Dialog(id));com.butang.codextop.CodexRuntime.remoteIds.put(id,"remote"+id);com.butang.codextop.CodexRuntime.dialogMachines.put(id,machine);com.butang.codextop.CodexRuntime.machineNames.put(machine,machine);com.butang.codextop.CodexRuntime.dialogTitles.put(id,title);com.butang.codextop.CodexRuntime.dialogDirectories.put(id,directory);MessagesController.instance.users.put(id,new TLRPC.User(id,title));}
 static void query(Adapter a,String q,long...ids){Counts.reset();a.searchDialogs(q,0,true);check(Counts.telegram==0&&Counts.queue==0&&Counts.db==0,"Codex query escaped to Telegram/queue/db");check(!a.isSearching()&&!a.delegate.loading,"local completion stays loading");check(a.getItemCount()==ids.length,"local result row count");for(int i=0;i<ids.length;i++){check(a.getItem(i)==MessagesController.instance.getUser(ids[i]),"original click identity lost");check(!a.isGlobalSearch(i),"local row classified global");}a.loadMoreSearchMessages();check(Counts.telegram==0&&Counts.queue==0,"scroll triggered Telegram");cases++;}
 public static void main(String[]args){
  if(args.length>0&&args[0].equals("async")){
   add(101,"Codex project","Desk-A","/synthetic/alpha");Adapter late=new Adapter();query(late,"Codex",101);int count=late.notifications;int wait=late.waitingResponseCount;late.onDataSetChanged(late.lastSearchId-1);check(late.getItemCount()==1&&late.getItem(0)==MessagesController.instance.getUser(101)&&late.notifications==count&&late.waitingResponseCount==wait,"old helper completion overwrote directory");System.out.println("PASS actual late-helper owner");return;
  }
  add(101,"Codex 项目","Desk-A","/synthetic/alpha");add(102,"中文会话","Work-Mac","/synthetic/beta");add(103,"codex followup","Desk-A","/synthetic/gamma");
  Adapter a=new Adapter();query(a,"Codex",101,103);query(a,"  cOdEx  ",101,103);query(a,"中文",102);query(a,"work-mac",102);query(a,"BETA",102);query(a,"nothing");query(a,"");query(a,null);query(a,"#tag");
  Counts.reset();a.initRecent();check(Counts.db==0,"root constructor started Telegram recent/hints");
  MediaDataController.instance.hints.add(new Object());a.filteredRecentSearchObjects.add(new Adapter.RecentSearchObject());a.filtered2RecentSearchObjects.add(new Adapter.RecentSearchObject());query(a,"codex",101,103);query(a,"");
  Pager pager=new Pager();pager.parent=a.dialogsActivity;pager.expandedPublicPosts=true;pager.updateItems();check(pager.items.size()==1&&pager.items.get(0).type==Pager.DIALOGS_TYPE,"Codex search exposes Telegram tabs");
  MessagesController.instance.users.remove(103L);query(a,"codex",101);com.butang.codextop.CodexRuntime.remoteIds.remove(101L);query(a,"codex");
  UserConfig.selectedAccount=1;query(a,"中文");UserConfig.selectedAccount=0;com.butang.codextop.CodexRuntime.loggingOut=true;query(a,"中文");com.butang.codextop.CodexRuntime.loggingOut=false;com.butang.codextop.CodexRuntime.session=null;query(a,"中文");com.butang.codextop.CodexRuntime.session=new Object();check(com.butang.codextop.CodexRuntime.restores==0,"query read account store");
  for(int mode=0;mode<8;mode++){
   Adapter t=new Adapter();switch(mode){case 0:com.butang.codextop.CodexRuntime.on=false;break;case 1:t.dialogsActivity.hasMainTabs=false;break;case 2:t.dialogsActivity.onlySelect=true;break;case 3:t.dialogsActivity.folderId=1;break;case 4:t.dialogsActivity.communityId=1;break;case 5:t.dialogsActivity.initialDialogsType=2;break;case 6:t.dialogsActivity.searchString="preset";break;case 7:t.dialogsActivity.inPreviewMode=true;break;}
   Counts.reset();t.searchDialogs("Codex",0,true);check(Counts.telegram>0&&Counts.queue>0&&t.isSearching(),"special/Telegram query changed");t.initRecent();check(Counts.db==2,"special/Telegram recent changed");Pager g=new Pager();g.parent=t.dialogsActivity;g.updateItems();check(g.items.size()>1,"special/Telegram tabs changed");com.butang.codextop.CodexRuntime.on=true;cases++;
  }

  // 真实意图 helper + 原媒体 setter + 原页目录定位，共用同一 adapter，不另建目录副本。
  Adapter x=new Adapter();DialogsActivity d=x.dialogsActivity;Pager pp=new Pager();pp.parent=d;pp.dialogsSearchAdapter=x;d.searchViewPager=pp;pp.updateItems();
  d.intent(true,true);pp.updateItems();pp.showDownloads();check(!d.isCodexDirectorySearch()&&pp.items.size()>pp.selected,"downloads intent lost original target");cases++;
  d.searchIsShowed=true;d.intent(true,false);check(!d.isCodexDirectorySearch(),"repeated expand reset downloads");cases++;
  d.intent(false,false);d.searchIsShowed=false;d.initialSearchType=-1;check(d.isCodexDirectorySearch()&&pp.items.size()==1,"close did not restore directory");cases++;
  d.searching=true;d.setShowSearch("music",2);pp.updateItems();check(!d.isCodexDirectorySearch()&&pp.getPositionForType(2)>=0,"already-open music intent lost");cases++;
  d.intent(false,false);d.initialSearchType=-1;d.searchIsShowed=false;
  query(x,"中文",102);
  int stable=x.notifications;int id=x.lastSearchId;int waiting=x.waitingResponseCount;
  x.onDataSetChanged(id-1);check(x.getItemCount()==1&&x.getItem(0)==MessagesController.instance.getUser(102)&&x.waitingResponseCount==waiting&&x.notifications==stable,"old helper completion overwrote directory");cases++;
  ArrayList<SearchAdapterHelper.HashtagObject> tags=new ArrayList<>();SearchAdapterHelper.HashtagObject tag=new SearchAdapterHelper.HashtagObject();tag.hashtag="#old";tags.add(tag);x.onSetHashtags(tags,new HashMap<>());check(x.searchResultHashtags.isEmpty()&&x.notifications==stable,"old hashtags overwrote directory");cases++;
  TLRPC.TL_contacts_sponsoredPeers ads=new TLRPC.TL_contacts_sponsoredPeers();ads.peers.add(new TLRPC.TL_sponsoredPeer());x.sponsoredComplete(ads,id-1);check(x.sponsoredPeers.isEmpty()&&x.notifications==stable,"old sponsored completion overwrote directory");cases++;
  check(!x.canApplySearchResults(id),"helper accepted local-mode reply");cases++;
  x.filtersDelegate=new FilterDelegate();x.filtersComplete(id-1);check(x.filtersDelegate.updates==0,"late storage filters reached local UI");cases++;
  int beforeCallbacks=x.notifications;int beforeWaiting=x.waitingResponseCount;ArrayList<Object>oldRows=new ArrayList<>();oldRows.add(new TLRPC.User(999,"obsolete"));ArrayList<CharSequence>oldNames=new ArrayList<>();oldNames.add("obsolete");AndroidUtilities.pending.clear();x.updateSearchResults(oldRows,oldNames,new ArrayList<>(),new ArrayList<>(),id-1);for(Runnable r:new ArrayList<>(AndroidUtilities.pending))r.run();AndroidUtilities.pending.clear();check(x.getItemCount()==1&&x.waitingResponseCount==beforeWaiting&&x.notifications==beforeCallbacks,"late storage results reached local UI");cases++;
  ArrayList<Adapter.RecentSearchObject>oldRecent=new ArrayList<>();oldRecent.add(new Adapter.RecentSearchObject());x.setRecentSearch(oldRecent,new LongSparseArray<>());check(x.recentSearchObjects!=oldRecent&&x.notifications==beforeCallbacks,"late recent result accepted locally");cases++;
  int oldQueryId=x.lastSearchId;x.clear_lastSearchId();check(x.lastSearchId>oldQueryId,"root empty query reused old id");int oldReq=x.lastReqId;x.clear_lastReqId();check(x.lastReqId>oldReq,"root message clear reused old id");int oldForum=x.lastForumReqId;x.clear_lastForumReqId();check(x.lastForumReqId>oldForum,"root forum clear reused old id");cases++;

  Runnable q=()->{};x.searchRunnable=q;Utilities.searchQueue.list.add(q);x.searchRunnable2=q;x.searchHashtagRunnable=q;AndroidUtilities.pending.add(q);x.reqId=1;x.reqForumId=2;x.sponsoredReqId=3;x.searchHashtagRequest=4;int cancel=ConnectionsManager.cancelled;int req=x.lastReqId,forum=x.lastForumReqId;
  query(x,"中文",102);check(!Utilities.searchQueue.list.contains(q)&&!AndroidUtilities.pending.contains(q),"old queue work not cancelled");check(ConnectionsManager.cancelled-cancel==4&&x.lastReqId>req&&x.lastForumReqId>forum,"requests not invalidated");cases++;
  for(int n=0;n<5;n++){d.intent(false,false);d.initialSearchType=-1;d.searchIsShowed=false;d.intent(true,false);query(x,"",new long[0]);query(x,"中文",102);}cases++;
  System.out.println("PASS cases="+cases+" checks="+checks);
 }
 }
 """;
}
