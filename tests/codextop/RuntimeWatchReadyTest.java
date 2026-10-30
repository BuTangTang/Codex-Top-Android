package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;

/** 真实watch连接等待回归；显式虚拟时钟只驱动原任务，不访问设备或真实账号。 */
public final class RuntimeWatchReadyTest {
    /** 从原夹具取得模板；不复制或手写被测 watch 算法。 */
    static String field(String name)throws Exception{
        Field f=RuntimeLatestSegmentTest.class.getDeclaredField(name);f.setAccessible(true);return (String)f.get(null);
    }
    /** 模板补丁必须恰好一处命中，防止静默改错合成边界。 */
    static String once(String text,String before,String after){
        if(text.indexOf(before)<0||text.indexOf(before)!=text.lastIndexOf(before))throw new AssertionError("boundary not unique");
        return text.replace(before,after);
    }
    /** 提取当前真实方法，原模型与存储按 Java 8 编译，结果写私有目录。 */
    public static void main(String[] args)throws Exception{
        Path runtime=args.length==0?Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"):Path.of(args[0]);
        Path out=Files.createTempDirectory("watch-ready-owner-");String group=args.length>1?args[1]:"all";
        try {
        Method extract=RuntimeLatestSegmentTest.class.getDeclaredMethod("extract",Path.class);extract.setAccessible(true);
        String owner=(String)extract.invoke(null,runtime),fixture=field("FIXTURE");
        fixture=once(fixture,"static DesktopConnection dialogConnection(long id){return current!=null&&current.machineId.equals(dialogMachines.get(id))?current:null;}","");
        fixture=once(fixture,"static void watchStatus(int a,long id,long g){}static void refreshDialogs(int a){}", "static void watchStatus(int a,long id,long g){statusPoll=()->statusRuns++;}static void refreshDialogs(int a){refreshCalls++;}");
        fixture=once(fixture,"static DesktopConnection current;", "static DesktopConnection desktop;static int statusRuns,refreshCalls;static DesktopConnection current;");
        // Handler.removeCallbacks 会移除同一 Runnable 的全部排队项，边界与真实队列一致。
        fixture=once(fixture,"ready.remove(r);delayed.removeIf(d->d.run==r);", "while(ready.remove(r)){}delayed.removeIf(d->d.run==r);");
        var parsed=StaticJavaParser.parse(runtime).getClassByName("CodexRuntime").orElseThrow();
        for(String name:new String[]{"onDesktopConnected","dialogConnection"}){
            var methods=parsed.getMethodsByName(name);if(methods.size()!=1)throw new AssertionError("owner not unique "+name);
            owner+=methods.get(0).toString()+"\n";
        }
        owner+="/** 每例只清新登记字段，不替换被测恢复算法。 */ static void resetReadyWaitFixture(){"+(parsed.getFieldByName("watchConnectionWait").isPresent()?"watchConnectionWait=null;":"")+"}\n";
        Files.createDirectories(out);Path source=out.resolve("RuntimeLatestSegmentProbe.java");
        Files.writeString(source,fixture+owner+field("SCENARIOS")+CASES+"\npublic static void runPrivate()throws Exception{readyRun(\""+group+"\");}\n}");
        Path classes=out.resolve("generated-classes");Files.createDirectories(classes);
        ArrayList<String> cmd=new ArrayList<>(java.util.List.of("--release","8","-cp",System.getProperty("java.class.path"),"-d",classes.toString(),source.toString()));
        for(String type:new String[]{"DesktopAttachment","TranscriptText","TranscriptWindow","TranscriptTailRecovery","TranscriptStore","OutboxStore"})cmd.add("TMessagesProj/src/main/java/com/butang/codextop/"+type+".java");
        String[][] stubs={
            {"Utilities","org.telegram.messenger","public static com.butang.codextop.RuntimeLatestSegmentProbe.Queue globalQueue;"},
            {"MessageObject","org.telegram.messenger","public static final int MESSAGE_SEND_STATE_SENT=0,MESSAGE_SEND_STATE_SENDING=1,MESSAGE_SEND_STATE_SEND_ERROR=2;public com.butang.codextop.RuntimeLatestSegmentProbe.TLRPC.TL_message messageOwner;public boolean wasJustSent,attachPathExists,mediaExists;public MessageObject(int a,com.butang.codextop.RuntimeLatestSegmentProbe.TLRPC.TL_message m,boolean x,boolean y){messageOwner=m;}public int getId(){return messageOwner.id;}public long getDialogId(){return messageOwner.dialog_id;}"},
            {"BuildVars","org.telegram.messenger","public static final boolean DEBUG_VERSION=false;"},
            {"UserConfig","org.telegram.messenger","public static int selectedAccount;public int lastSendMessageId=-1;static final UserConfig value=new UserConfig();public static UserConfig getInstance(int a){return value;}public void saveConfig(boolean x){}"},
            {"Log","android.util","public static int i(String a,String b){return 0;}public static int w(String a,String b){return 0;}"},
            {"Toast","android.widget","public static final int LENGTH_SHORT=0;public static Toast makeText(Object c,String s,int t){return new Toast();}public void show(){com.butang.codextop.RuntimeLatestSegmentProbe.toasts++;}"}
        };
        for(String[] row:stubs){Path file=out.resolve(row[0]+".java");Files.writeString(file,"package "+row[1]+"; public class "+row[0]+" {"+row[2]+"}\n");cmd.add(file.toString());}
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,cmd.toArray(String[]::new))!=0)throw new AssertionError("private owner compile failed");
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},RuntimeWatchReadyTest.class.getClassLoader())){
            loader.loadClass("com.butang.codextop.RuntimeLatestSegmentProbe").getMethod("runPrivate").invoke(null);
        }
        } finally {try(var paths=Files.walk(out)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(file);}}
    }
    private static final String CASES = """
    static int readyGroups;
    /** 推进立即排程，不偷跑未来五秒 watch；STATUS 仅是协议外部消费计数。 */
    static void readyOwners(){int guard=300;while(!Utilities.globalQueue.ready.isEmpty()||!AndroidUtilities.ui.ready.isEmpty()||!statusQueue.ready.isEmpty()){
        check(--guard>0,"immediate owners did not yield");
        if(!Utilities.globalQueue.ready.isEmpty())Utilities.globalQueue.next();else if(!AndroidUtilities.ui.ready.isEmpty())AndroidUtilities.ui.next();else statusQueue.next();
    }}
    /** 只消费已经准入的网络工作；保持每个真实 owner 回包后的原合并路径。 */
    static void readyAll(){int guard=300;while(true){readyOwners();check(--guard>0,"network owners did not yield");
        if(!transcriptQueue.ready.isEmpty())transcriptQueue.next();else if(!historyQueue.ready.isEmpty())historyQueue.next();else if(!prefetchQueue.ready.isEmpty())prefetchQueue.next();else return;
    }}
    /** 虚拟时钟只释放真实 owner 排出的到期任务，不注入额外唤醒。 */
    static void readyAdvance(long time){now=time;for(Queue q:new Queue[]{Utilities.globalQueue,AndroidUtilities.ui,statusQueue,transcriptQueue,historyQueue,prefetchQueue}){
        for(Queue.Delayed delayed:new ArrayList<>(q.delayed))if(delayed.due<=now){q.delayed.remove(delayed);q.ready.add(delayed.run);}
    }}
    /** 缺尾游标缓存经真实load交付；ready只验原latest bootstrap，账号守卫复用公共真实提取体。 */
    static void readySetup(boolean connected)throws Exception{
        TranscriptWindow cached=cache("A,B");cached.tailCursor=null;reset(cached);resetReadyWaitFixture();org.telegram.messenger.UserConfig.selectedAccount=0;desktop=connected?current:null;statusRuns=refreshCalls=0;
        if(!connected)desktopConnections.clear();
        openCached();check(current.requests.isEmpty(),"cache display unexpectedly requested network");
        current.latest=page("A,B,C",false,null,"new-tail");
    }
    /** 模拟原 openDesktop 已发布连接后触发真实 ready owner，不复制其唤醒逻辑。 */
    static void readyConnect(DesktopConnection connection){desktopConnections.put(connection.machineId,connection);desktop=connection;onDesktopConnected(connection);}
    /** 建连等待反例：缓存已交付，但 ready 在到期前不会促发 latest。 */
    static void readyDelay(boolean requireImmediate)throws Exception{
        readySetup(false);try{
            watchConversation(0,42);readyOwners();
            check(Utilities.globalQueue.lastDelay()==5000&&current.requests.isEmpty(),"missing connection did not enter original 5s wait");
            readyAdvance(200);readyConnect(current);readyAll();
            check(statusRuns==1,"ready owner did not wake current STATUS boundary");
            System.out.println("OBS readyAt=200 latestRequests="+current.requests.size()+" statusWakes="+statusRuns+" originalWatchDue=5000");
            if(requireImmediate){
                check(current.requests.size()==1,"connection ready left latest waiting until original 5000ms timer");
                // 保留原两秒健康周期；旧五秒 deadline 不能插入第四次请求。
                current.after.add(page("",false,null,"new-tail"));readyAdvance(2200);readyAll();
                check(current.requests.size()==2,"normal two-second poll was lost or duplicated");
                current.after.add(page("",false,null,"new-tail"));readyAdvance(4200);readyAll();
                check(current.requests.size()==3,"second normal poll was lost or duplicated");
                readyAdvance(4999);readyAll();readyAdvance(5000);readyAll();
                check(current.requests.size()==3,"old deadline duplicated the ready request");return;
            }
            readyAdvance(4999);readyAll();check(current.requests.isEmpty(),"old watch woke before original timer");
            readyAdvance(5000);readyAll();check(current.requests.equals(Collections.singletonList("latest")),"original timer did not deliver exactly one latest");
            check(ids(histories.get(42L)).equals("C,B,A"),"latest response was not merged through actual owner");
            System.out.println("OBS firstLatestAt=5000 requests=1 retainedCache=true");
        }finally{cleanup();}
    }
    /** 重复ready只能消费一次null等待，不加速原健康周期。 */
    static void readyRepeated()throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();readyAdvance(200);for(int n=0;n<5;n++)readyConnect(current);readyAll();
            check(current.requests.size()==1&&Utilities.globalQueue.delayed.size()==1&&Utilities.globalQueue.lastDelay()==2000,"repeated ready duplicated existing watch timer");stopWatching(42);
            readyAdvance(5000);readyAll();check(current.requests.size()==1,"ready burst produced repeated latest");
        }finally{cleanup();}
    }
    /** 已有连接开页立即一次 latest；健康两秒周期不应被连接事件升级为多次请求。 */
    static void readyAlreadyConnected()throws Exception{
        readySetup(true);try{watchConversation(0,42);readyAll();check(current.requests.size()==1&&Utilities.globalQueue.lastDelay()==2000,"connected opening not immediate latest");
            for(int n=0;n<5;n++)onDesktopConnected(current);readyAll();check(current.requests.size()==1,"ready event accelerated healthy polling");
        }finally{cleanup();}
    }
    /** 网络工作已经排队时的 ready 不能复制该请求。 */
    static void readyQueued()throws Exception{
        readySetup(true);try{watchConversation(0,42);readyOwners();check(transcriptQueue.ready.size()==1,"no real transcript queued boundary");
            for(int n=0;n<3;n++)onDesktopConnected(current);readyAll();check(current.requests.size()==1,"queued watch duplicated by ready");
        }finally{cleanup();}
    }
    /** RPC 执行中接到连接事件，原回包只合并保存一次。 */
    static void readyInFlight()throws Exception{
        readySetup(true);try{current.onRequest=()->{for(int n=0;n<3;n++)onDesktopConnected(current);readyOwners();};watchConversation(0,42);readyAll();
            check(current.requests.size()==1&&saves==1,"inflight ready duplicated request or save");
        }finally{cleanup();}
    }
    /** 真实 stop 后的旧 timer 与迟到 ready 均不能重启正文读取。 */
    static void readyStopped()throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();stopWatching(42);readyConnect(current);readyAll();readyAdvance(5000);readyAll();
            check(current.requests.isEmpty()&&events.isEmpty()&&saves==0,"stopped watch still requested or delivered");
        }finally{cleanup();}
    }
    /** ready 已排 UI/status 后换账号或退出，所有迟到通知必须过原 epoch guard。 */
    static void readyAccount(boolean logout)throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();readyConnect(current);accountGeneration++;if(logout){loggingOut=true;session=null;}else session=new PasswordLogin.Session();
            readyAll();readyAdvance(5000);readyAll();check(current.requests.isEmpty()&&events.isEmpty()&&statusRuns==0&&saves==0,"old account ready leaked work");
        }finally{cleanup();}
    }
    /** 原页停止后同对话重开新页，旧五秒任务不得再发一份 latest 或给旧 token 投递。 */
    static void readyReopened()throws Exception{
        readySetup(false);try{long token=historyViews.get(7).token;watchConversation(0,42);readyOwners();stopWatching(42);closeHistoryView(7,token);
            openHistoryView(0,42,8);readyConnect(current);watchConversation(0,42);readyAll();
            check(current.requests.size()==1,"new page did not make sole initial latest");
            for(Event e:events)if(e.type==NotificationCenter.messagesDidLoad)check((Integer)e.args[10]==8,"closed page got latest");
            stopWatching(42);readyAdvance(5000);readyAll();check(current.requests.size()==1,"old page timer recreated latest");
        }finally{cleanup();}
    }
    /** 同机器连接被替换后，旧 ready 和已发旧 RPC 回包不能覆盖新 owner。 */
    static void readyReplacedInFlight()throws Exception{
        readySetup(true);try{DesktopConnection old=current;DesktopConnection replacement=new DesktopConnection();replacement.latest=page("D",false,null,"fresh-tail");
            old.onRequest=()->{desktopConnections.put(old.machineId,replacement);desktop=replacement;onDesktopConnected(old);readyOwners();};
            watchConversation(0,42);readyAll();check(old.requests.size()==1&&replacement.requests.isEmpty()&&events.isEmpty()&&saves==0,"stale connection merged or duplicated RPC");
            check(ids(histories.get(42L)).equals("B,A"),"stale connection changed original cache");
        }finally{cleanup();}
    }
    /** 每项执行真实owner，失败立即终止，不以计划断言代替结果。 */
    interface ReadyCase {void run()throws Exception;}
    static void readyCase(String name,ReadyCase body)throws Exception{body.run();readyGroups++;System.out.println("PASS "+name);}
    /** 同一即时唤醒断言支持原前像RED和候选GREEN，其余组覆盖原控制与竞态。 */
    static void readyRun(String group)throws Exception{
        if(group.equals("red")){readyDelay(true);return;}
        readyCase("ready-cancels-original-5s",()->readyDelay(true));readyCase("repeated-ready",()->readyRepeated());
        readyCase("already-connected",()->readyAlreadyConnected());readyCase("queued",()->readyQueued());readyCase("in-flight",()->readyInFlight());
        readyCase("stopped",()->readyStopped());readyCase("account-changed",()->readyAccount(false));readyCase("logout",()->readyAccount(true));
        readyCase("reopened-page",()->readyReopened());readyCase("replaced-connection",()->readyReplacedInFlight());
        readyCase("timer-before-ready",()->readyTimerRace(true));readyCase("ready-before-timer",()->readyTimerRace(false));
        readyCase("disconnect-before-consume",()->readyDisconnectBeforeConsume());
        readyCase("disconnect-after-consume",()->readyChangedAfterConsume(false));readyCase("swap-after-consume",()->readyChangedAfterConsume(true));
        readyCase("swap-before-consume",()->readySwapBeforeConsume());
        for(String kind:new String[]{"selected","remote","machine","dialog"})readyCase("changed-"+kind,()->readyOwnerChanged(kind));
        readyCase("old-timer-after-reopen",()->readyOldTimerAfterReopen());readyCase("rpc-failure-wait",()->readyFailureWait());
        readyCase("cold-shared",()->readyColdShared());
        System.out.println("RuntimeWatchReady groups="+readyGroups+" failures=0");
    }

    /** 同刻到期与ready两种顺序都只沿一个原任务入口发出请求。 */
    static void readyTimerRace(boolean timerFirst)throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();now=5000;
            if(timerFirst){readyAdvance(5000);readyConnect(current);}else{readyConnect(current);readyAdvance(5000);}
            readyAll();check(current.requests.size()==1&&saves==1,"same-deadline timer/ready duplicated latest");
        }finally{cleanup();}
    }
    /** ready已排队但尚未消费时断线，原五秒任务必须仍在且不能提前请求。 */
    static void readyDisconnectBeforeConsume()throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();readyAdvance(200);readyConnect(current);current.connected=false;readyAll();
            check(current.requests.isEmpty()&&Utilities.globalQueue.lastDelay()==5000,"disconnected ready consumed null wait");
            current.connected=true;readyAdvance(5000);readyAll();check(current.requests.size()==1,"original fallback timer was lost");
        }finally{cleanup();}
    }
    /** 已消费ready到原任务真正执行之间的断线或换连接不能借旧事件提前发RPC。 */
    static void readyChangedAfterConsume(boolean replace)throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();readyAdvance(200);DesktopConnection original=current;
            readyConnect(original);Utilities.globalQueue.next();
            DesktopConnection next=original;if(replace){next=new DesktopConnection();next.latest=page("D",false,null,"new-owner");desktopConnections.put(next.machineId,next);desktop=next;}else original.connected=false;
            readyAll();check(original.requests.isEmpty()&&next.requests.isEmpty(),"revoked ready issued an early RPC");
            check(Utilities.globalQueue.lastDelay()==5000,"revoked ready expanded retry cadence");
            next.connected=true;readyAdvance(5200);readyAll();check(next.requests.size()==1,"revoked ready lost original retry path");
        }finally{cleanup();}
    }
    /** 迟到旧连接ready不能消费新连接尚未通知的等待资格。 */
    static void readySwapBeforeConsume()throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();readyAdvance(200);DesktopConnection old=current;
            readyConnect(old);DesktopConnection next=new DesktopConnection();next.latest=page("D",false,null,"next-owner");desktopConnections.put(next.machineId,next);desktop=next;
            readyAll();check(old.requests.isEmpty()&&next.requests.isEmpty(),"stale ready consumed replacement wait");
            onDesktopConnected(next);readyAll();check(next.requests.size()==1&&old.requests.isEmpty(),"current ready failed or reused old connection");
        }finally{cleanup();}
    }
    /** 选中账号、会话来源或电脑绑定变化后，旧等待不得借ready继续旧正文读取。 */
    static void readyOwnerChanged(String kind)throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();readyConnect(current);
            if(kind.equals("selected"))org.telegram.messenger.UserConfig.selectedAccount=1;
            if(kind.equals("remote"))remoteIds.put(42L,"new-remote");
            if(kind.equals("machine"))dialogMachines.put(42L,"other-machine");
            if(kind.equals("dialog"))watchedDialog=99;
            readyAll();readyAdvance(5000);readyAll();check(current.requests.isEmpty()&&events.isEmpty()&&saves==0,"changed "+kind+" owner leaked RPC");
        }finally{org.telegram.messenger.UserConfig.selectedAccount=0;cleanup();}
    }
    /** 已从队列取出的旧timer迟执行，也不能抹掉后来同页新generation的等待。 */
    static void readyOldTimerAfterReopen()throws Exception{
        readySetup(false);try{watchConversation(0,42);readyOwners();Runnable old=Utilities.globalQueue.delayed.get(0).run;
            stopWatching(42);watchConversation(0,42);readyOwners();old.run();readyConnect(current);readyAll();
            check(current.requests.size()==1,"old timer cleared new generation wait");stopWatching(42);readyAdvance(5000);readyAll();check(current.requests.size()==1,"old timer revived after new stop");
        }finally{cleanup();}
    }
    /** 普通RPC失败产生的五秒等待不是null等待，重连通知不能加速重试。 */
    static void readyFailureWait()throws Exception{
        readySetup(true);try{current.latest=null;watchConversation(0,42);readyAll();check(current.requests.size()==1&&Utilities.globalQueue.lastDelay()==5000,"no original failed RPC wait");
            current.latest=page("A,B,C",false,null,"ok");for(int i=0;i<3;i++)onDesktopConnected(current);readyAll();check(current.requests.size()==1,"ready accelerated ordinary RPC failure");
            readyAdvance(5000);readyAll();check(current.requests.size()==2,"original failed RPC retry lost");
        }finally{cleanup();}
    }
    /** 空缓存等待ready后与原首载共享同一冷请求，不增加第二个latest。 */
    static void readyColdShared()throws Exception{
        reset(new TranscriptWindow());resetReadyWaitFixture();desktop=null;statusRuns=refreshCalls=0;desktopConnections.clear();
        try{openHistoryView(0,42,7);watchConversation(0,42);readyOwners();current.latest=page("A",false,null,"tail-a");
            readyConnect(current);loadMessages(0,42,25,0,7,2,1,0);readyAll();
            check(current.requests.size()==1&&ids(histories.get(42L)).equals("A"),"ready duplicated original cold owner");
        }finally{cleanup();}
    }

    """;
}
