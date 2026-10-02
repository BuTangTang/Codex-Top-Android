package com.butang.codextop;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import javax.tools.ToolProvider;

/** 执行实际 watch/stopWatching 与原恢复类；只替代网络、Android通知及队列时钟。 */
public final class RuntimeTailRecoveryTest {
    /** 可传旧Runtime源码和 red 参数执行相同缺锚点反例；默认核验当前全部接线。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("TMessagesProj/src/main/java/com/butang/codextop");
        Path runtime = args.length == 0 ? root.resolve("CodexRuntime.java") : Path.of(args[0]);
        byte[] before = Files.readAllBytes(runtime);
        String source = new String(before, java.nio.charset.StandardCharsets.UTF_8);
        String methods = method(source, "watchConversation") + "\n" + method(source, "stopWatching");
        Path temporary = Files.createTempDirectory(Path.of(System.getProperty("codex.tail.scratch", System.getProperty("java.io.tmpdir"))), "runtime-tail-");
        try {
            Path probe = temporary.resolve("RuntimeTailRecoveryProbe.java");
            Files.writeString(probe, FIXTURE + methods + SCENARIOS + "\n}");
            var inputs = new ArrayList<String>();
            inputs.add(probe.toString());
            for (String type : new String[]{"TranscriptText", "DesktopAttachment", "TranscriptWindow", "TranscriptTailRecovery"})
                inputs.add(root.resolve(type + ".java").toString());
            String[][] stubs = {
                {"Utilities", "org.telegram.messenger", "public static com.butang.codextop.RuntimeTailRecoveryProbe.Queue globalQueue;"},
                {"MessageObject", "org.telegram.messenger", "public final String sourceId; /** 保存合成来源编号。 */ public MessageObject(String id){sourceId=id;}"},
                {"BuildVars", "org.telegram.messenger", "public static final boolean DEBUG_VERSION=false;"},
                {"Log", "android.util", "/** 忽略Android日志，不读取设备。 */ public static int i(String a,String b){return 0;} /** 忽略Android日志。 */ public static int w(String a,String b){return 0;}"}
            };
            for (String[] stub : stubs) {
                Path file = temporary.resolve(stub[0] + ".java");
                Files.writeString(file, "package " + stub[1] + "; public class " + stub[0] + " {" + stub[2] + "}");
                inputs.add(file.toString());
            }
            var compile = new ArrayList<String>(java.util.List.of("-cp", System.getProperty("java.class.path"), "-d", temporary.toString()));
            compile.addAll(inputs);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)) != 0)
                throw new AssertionError("实际watch接线夹具编译失败");
            System.out.println("Runtime SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before)));
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, RuntimeTailRecoveryTest.class.getClassLoader())) {
                String[] mode = args.length > 1 ? new String[]{args[1]} : new String[0];
                try { loader.loadClass("com.butang.codextop.RuntimeTailRecoveryProbe").getMethod("main", String[].class).invoke(null, (Object)mode); }
                catch (InvocationTargetException error) { throw new AssertionError("实际watch尾部恢复接线失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
            if (!Arrays.equals(before, Files.readAllBytes(runtime))) throw new AssertionError("运行期间Runtime源码变化");
        }
    }

    /** 词法定位实际方法，字符串与注释的花括号不参与边界，不复制watch算法。 */
    private static String method(String source, String name) {
        var matcher = java.util.regex.Pattern.compile("(?m)^    (?:public|private|protected|static)[^\\n]*\\b" + name + "\\([^\\n]*").matcher(source);
        if (!matcher.find()) throw new AssertionError("实际方法不存在：" + name);
        int start = matcher.start(), open = source.indexOf('{', start), depth = 0;
        boolean string = false, character = false, line = false, block = false, escape = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (string || character) {
                if (escape) escape = false; else if (c == '\\') escape = true;
                else if (string && c == '"') string = false; else if (character && c == '\'') character = false;
                continue;
            }
            if (c == '/' && next == '/') { line = true; i++; } else if (c == '/' && next == '*') { block = true; i++; }
            else if (c == '"') string = true; else if (c == '\'') character = true;
            else if (c == '{') depth++; else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("实际方法未闭合：" + name);
    }

    // 队列只记录实际排程，Socket只按请求游标返回合成页，通知只记录正式方法发布的项。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import com.google.gson.*;
        import java.util.*;
        import java.io.IOException;
        import org.telegram.messenger.Utilities;
        public class RuntimeTailRecoveryProbe {
            static boolean loggingOut; static long accountGeneration=1,watchedDialog,watchGeneration,now;
            static Map<Long,String> remoteIds=new HashMap<>();
            static Map<Long,TranscriptWindow> histories=new HashMap<>();
            static Queue transcriptQueue=new Queue(),statusQueue=new Queue();static Runnable statusPoll;
            static DesktopConnection current;static Runnable watch;static int saves,previews,failures,observations;
            static List<String> delivered=new ArrayList<>(),stages=new ArrayList<>();
            public static class Queue {
                public ArrayDeque<Runnable> ready=new ArrayDeque<>();
                final ArrayList<Delayed> delayed=new ArrayList<>();final ArrayList<Long> delays=new ArrayList<>();
                static class Delayed {long due;Runnable runnable;/** 只保留既有排程时刻。 */Delayed(long d,Runnable r){due=d;runnable=r;}}
                /** 同步排队而不提前执行。 */public void postRunnable(Runnable r){ready.add(r);}
                /** 记录正式方法给出的等待值，不计算业务等待。 */public void postRunnable(Runnable r,long delay){delays.add(delay);if(delay==0)ready.add(r);else delayed.add(new Delayed(now+delay,r));}
                /** 原状态取消入口，只移除指定回调。 */public void cancelRunnable(Runnable r){ready.remove(r);delayed.removeIf(d->d.runnable==r);}
                /** 只执行一项，允许用例在网络与合并之间改变身份。 */void next(){if(ready.isEmpty())throw new AssertionError("合成队列没有待执行项");ready.remove().run();}
                /** 到原方法已有的最早唤醒时刻，不新增业务计时器。 */void wake(){if(!ready.isEmpty())return;if(delayed.isEmpty())throw new AssertionError("没有原延迟唤醒");now=delayed.stream().mapToLong(d->d.due).min().orElseThrow();for(var d:new ArrayList<>(delayed))if(d.due<=now){ready.add(d.runnable);delayed.remove(d);}}
                /** 读取原watch刚安排的延迟。 */long lastDelay(){return delays.get(delays.size()-1);}
            }
            static class AndroidUtilities {static Queue ui=new Queue();/** 仅排入合成UI队列。 */static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
            static class TLRPC {static class TL_message {String sourceId;/** 保存合成消息身份。 */TL_message(String id){sourceId=id;}}}
            static class NotificationCenter {
                static final int didReceiveNewMessages=1;static final NotificationCenter value=new NotificationCenter();
                /** 同一合成账号通知边界。 */static NotificationCenter getInstance(int a){return value;}
                /** 只记录实际watch发布的成员。 */void postNotificationName(int type,long id,ArrayList<org.telegram.messenger.MessageObject> rows,boolean scheduled,int mode){for(var row:rows)delivered.add(row.sourceId);}
            }
            static class DesktopConnection {
                String machineId="synthetic-computer";JsonObject latest;
                final Map<String,ArrayDeque<Object>> older=new HashMap<>();
                final ArrayDeque<Object> after=new ArrayDeque<>();final ArrayList<String> requests=new ArrayList<>();
                /** 最新页保持稳定，故旧版重复latest确实重复同一数据。 */JsonObject transcript(String remote)throws Exception{requests.add("latest");return latest.deepCopy();}
                /** 只对已有旧页游标返回合成来源。 */JsonObject transcript(String remote,String cursor)throws Exception{requests.add("older:"+cursor);var q=older.get(cursor);if(q==null||q.isEmpty())throw new IOException("合成页不存在");Object value=q.size()==1?q.peek():q.remove();if(value instanceof Exception error)throw error;return ((JsonObject)value).deepCopy();}
                /** 原增量入口，记录真正使用的尾游标。 */JsonObject readAfter(String remote,String cursor)throws Exception{requests.add("after:"+cursor);if(after.isEmpty())throw new IOException("合成增量不存在");Object value=after.size()==1?after.peek():after.remove();if(value instanceof Exception error)throw error;return ((JsonObject)value).deepCopy();}
                /** 声明每个真实请求游标的响应，不推进业务游标。 */void older(String cursor,Object... values){older.put(cursor,new ArrayDeque<>(Arrays.asList(values)));}
            }
            /** 合成登录边界。 */static boolean loggedIn(){return true;}
            /** 只认当前合成会话。 */static boolean ownsConversation(long id){return id==42;}
            /** 返回可在请求与回包之间替换的原连接。 */static DesktopConnection dialogConnection(long id){return current;}
            /** 状态轮询不属于本尾部测试范围。 */static void watchStatus(int account,long id,long generation){}
            /** 记录原离线刷新入口而不联网。 */static void refreshDialogs(int a){}
            /** 原状态租约释放只记录调用。 */static void releaseObservation(){observations++;}
            /** 记录实际异常阶段，不输出正文。 */static void logTranscriptFailure(String stage,Exception error,JsonObject page){failures++;stages.add(stage);}
            /** 不写磁盘，只记录何时正式方法允许保存。 */static boolean saveHistory(long id,String remote,TranscriptWindow history){saves++;return true;}
            /** 原摘要发布边界，不代替消息合并。 */static void publishHistoryPreview(int a,long epoch,long id,String remote,String machine,DesktopConnection connection,TranscriptWindow history){previews++;}
            /** 保留真实恢复成员的来源编号。 */static TLRPC.TL_message historyMessage(long id,TranscriptWindow.Entry entry){return new TLRPC.TL_message(entry.message.id);}
            /** 无本机待发记录，所有来源新增均可观察。 */static boolean confirmPendingEcho(int a,long id,TLRPC.TL_message message){return false;}
            /** Android消息对象只封装来源身份。 */static org.telegram.messenger.MessageObject historyObject(int a,TLRPC.TL_message message){return new org.telegram.messenger.MessageObject(message.sourceId);}
        """;

    private static final String SCENARIOS = """
            /** 构造合法的合成文字项。 */static JsonObject item(String id){JsonObject i=new JsonObject();i.addProperty("id",id);i.addProperty("createdAtMs",1);JsonObject raw=new JsonObject(),content=new JsonObject();raw.addProperty("role","agent");content.addProperty("type","text");content.addProperty("text","synthetic");raw.add("content",content);i.add("raw",raw);return i;}
            /** 保留来源明确分页字段，编号与合并仍交原类。 */static JsonObject page(String ids,boolean more,String cursor,String tail){JsonObject p=new JsonObject();JsonArray a=new JsonArray();if(!ids.isEmpty())for(String id:ids.split(","))a.add(item(id));p.add("items",a);p.addProperty("hasMore",more);p.addProperty("historyAvailability","available");p.addProperty("nextCursor",cursor);p.addProperty("tailCursor",tail);return p;}
            /** 从原Window恢复具有A、旧游标、无尾游标的缓存。 */static TranscriptWindow cached()throws Exception{var w=new TranscriptWindow();w.prepend(page("A",true,"old-cache",null));return TranscriptWindow.restore(w.snapshot());}
            /** 每例隔离原队列、账号代、窗口和连接，不读真实数据。 */static void reset(TranscriptWindow w){loggingOut=false;accountGeneration=1;watchedDialog=watchGeneration=now=0;Utilities.globalQueue=new Queue();transcriptQueue=new Queue();statusQueue=new Queue();AndroidUtilities.ui=new Queue();statusPoll=null;remoteIds.clear();remoteIds.put(42L,"synthetic-remote");histories.clear();histories.put(42L,w);current=new DesktopConnection();saves=previews=failures=observations=0;delivered.clear();stages.clear();watch=null;}
            /** 只启动被抽取的真实watch。 */static void start(){watchConversation(0,42);watch=Utilities.globalQueue.ready.peek();}
            /** 执行真实调度、网络回包与合并三阶段，允许原延迟自然到期。 */static void cycle(){Utilities.globalQueue.wake();Utilities.globalQueue.next();transcriptQueue.next();Utilities.globalQueue.next();ui();}
            /** 执行已排入的正式UI发布。 */static void ui(){while(!AndroidUtilities.ui.ready.isEmpty())AndroidUtilities.ui.next();}
            /** 读取实际匿名watch持有的暂态，不设置其内部字段。 */static TranscriptTailRecovery gap()throws Exception{var f=watch.getClass().getDeclaredField("tailGap");f.setAccessible(true);return (TranscriptTailRecovery)f.get(watch);}
            /** 固定文字断言，不输出真实会话身份。 */static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
            /** 比较缓存成员和旧编号，结果完全来自原Window。 */static String ids(TranscriptWindow w){StringJoiner s=new StringJoiner(",");for(var row:w.before(0,100))s.add(row.message.id);return s.toString();}

            /** 第五页才出现A；前四页不能发布，跨轮不重读latest，随后从首次tail取增量。 */
            static void crossesFourPages()throws Exception{
                TranscriptWindow w=cached();JsonObject before=w.snapshot();int stable=w.before(0,1).get(0).id;reset(w);
                current.latest=page("B,C",true,"c1","first-tail");
                current.older("c1",page("X4",true,"c2","ignored-1"));current.older("c2",page("X3",true,"c3","ignored-2"));
                current.older("c3",page("X2",true,"c4","ignored-3"));current.older("c4",page("A",false,null,"ignored-4"));
                current.after.add(page("D",false,"after-tail",null));start();
                for(int n=0;n<4;n++){cycle();check(before.equals(w.snapshot())&&delivered.isEmpty()&&saves==0&&previews==0,"缺页期间提前改写/发布");}
                System.out.println("cross-round requests="+current.requests+" delays="+Utilities.globalQueue.delays);
                check(current.requests.equals(List.of("latest","older:c1","older:c2","older:c3")),"四页没有沿旧游标推进或重复latest");
                check(Utilities.globalQueue.delays.equals(List.of(0L,0L,0L,2000L)),"每轮四页未按原预算让出两秒");
                check(gap()!=null&&gap().retainedPages()==4&&"c4".equals(gap().resumeCursor()),"跨轮未保留旧页与c4");
                cycle();check(gap()==null&&ids(w).equals("C,B,X4,X3,X2,A")&&w.before(0,100).get(5).id==stable,"含A后原恢复顺序/编号错误");
                check(w.cursor.equals("old-cache")&&w.tailCursor.equals("first-tail")&&delivered.equals(List.of("X2","X3","X4","B","C")),"旧cursor/首次tail/实际发布错误");
                cycle();check(current.requests.get(5).equals("after:first-tail")&&w.tailCursor.equals("after-tail")&&ids(w).startsWith("D,"),"恢复期间新消息没有沿首次tail补上");
            }

            /** 暂时网络失败后仍续c1，不能重读latest或提前发缺页成员。 */
            static void retriesSameCursor()throws Exception{
                TranscriptWindow w=cached();JsonObject before=w.snapshot();reset(w);current.latest=page("B,C",true,"c1","first-tail");
                current.older("c1",new IOException("synthetic-network"),page("A",false,null,"ignored"));start();cycle();cycle();
                check(gap()!=null&&gap().retainedPages()==1&&gap().resumeCursor().equals("c1"),"网络失败丢掉原续页游标");
                check(before.equals(w.snapshot())&&delivered.isEmpty()&&Utilities.globalQueue.lastDelay()==5000,"网络失败提前发布或改变等待");
                cycle();check(current.requests.equals(List.of("latest","older:c1","older:c1"))&&ids(w).equals("C,B,A")&&w.tailCursor.equals("first-tail"),"网络失败后未续同一旧游标");
            }

            /** 把真实回包停在网络与合并之间，任一身份变化均丢掉实际暂态。 */
            static void lateIdentity(String kind)throws Exception{
                TranscriptWindow w=cached();reset(w);current.latest=page("B,C",true,"c1","first-tail");current.older("c1",page("A",false,null,"ignored"));start();cycle();
                TranscriptTailRecovery retained=gap();Utilities.globalQueue.next();transcriptQueue.next();
                switch(kind){case "connection":current=new DesktopConnection();break;case "history":histories.put(42L,cached());break;case "generation":watchGeneration++;break;case "tail":w.tailCursor="new-tail";break;case "older":w.cursor="new-older";break;default:throw new AssertionError(kind);}
                JsonObject afterChange=w.snapshot();Utilities.globalQueue.next();ui();
                check(gap()==null&&retained.retainedPages()==0&&delivered.isEmpty()&&saves==0&&afterChange.equals(w.snapshot()),"迟到回包串用或未释放："+kind);
            }

            /** stop不取消正在返回的旧请求；同一旧callback到达后释放且不发布。 */
            static void stoppedCallbackReleases()throws Exception{
                TranscriptWindow w=cached();JsonObject before=w.snapshot();reset(w);current.latest=page("B,C",true,"c1","first-tail");current.older("c1",page("A",false,null,"ignored"));start();cycle();
                TranscriptTailRecovery retained=gap();Utilities.globalQueue.next();transcriptQueue.next();stopWatching(42);
                check(retained.retainedPages()==1,"stop瞬时生命周期被测试错误地要求同步清空");
                Utilities.globalQueue.next();ui();check(gap()==null&&retained.retainedPages()==0&&before.equals(w.snapshot())&&delivered.isEmpty(),"stop后的原callback未释放或发布");
                check(Utilities.globalQueue.ready.isEmpty()&&Utilities.globalQueue.delayed.isEmpty()&&current.requests.equals(List.of("latest","older:c1")),"stop后新增尾部请求");
                System.out.println("stop lifecycle: retained before existing callback; released at generation guard; no publish/request");
            }

            /** 已让出四页的watch切页后，仅等原两秒唤醒释放，不新增请求。 */
            static void stoppedWakeReleases()throws Exception{
                TranscriptWindow w=cached();reset(w);current.latest=page("B,C",true,"c1","first-tail");
                current.older("c1",page("X4",true,"c2","ignored"));current.older("c2",page("X3",true,"c3","ignored"));current.older("c3",page("X2",true,"c4","ignored"));start();
                for(int n=0;n<4;n++)cycle();TranscriptTailRecovery retained=gap();stopWatching(42);check(retained.retainedPages()==4,"切页前桥接夹具无效");
                Utilities.globalQueue.wake();Utilities.globalQueue.next();check(gap()==null&&retained.retainedPages()==0&&current.requests.size()==4&&transcriptQueue.ready.isEmpty(),"切页原唤醒未释放或继续请求");
            }

            /** 错误元数据、断档或循环游标通过真实Runtime catch释放，旧窗口不改。 */
            static void rejectedOlder(String kind)throws Exception{
                TranscriptWindow w=cached();JsonObject before=w.snapshot();reset(w);current.latest=page("B,C",true,"c1","first-tail");
                JsonObject bad=page("X",true,"c2","ignored");
                if(kind.equals("metadata"))bad.add("nextCursor",new JsonObject());
                else if(kind.equals("discontinuity"))bad.addProperty("truncationReason","source_discontinuity");
                else if(kind.equals("cycle"))bad.addProperty("nextCursor","c1");
                else throw new AssertionError(kind);
                current.older("c1",bad);start();cycle();TranscriptTailRecovery retained=gap();cycle();
                check(gap()==null&&retained.retainedPages()==0&&before.equals(w.snapshot())&&delivered.isEmpty()&&saves==0&&failures==1,"局部错误未由原catch完整拒绝："+kind);
                check(stages.equals(List.of("tail_recovery_merge"))&&Utilities.globalQueue.lastDelay()==5000,"局部错误不走原失败间隔："+kind);
            }

            /** 空投影旧页仍向c2前进，跨页重复B仍交原恢复去重。 */
            static void emptyAndDuplicate()throws Exception{
                TranscriptWindow w=cached();reset(w);current.latest=page("B,C",true,"c1","first-tail");current.older("c1",page("",true,"c2","ignored"));current.older("c2",page("A,B",false,null,"ignored"));start();cycle();cycle();
                check(delivered.isEmpty()&&gap().resumeCursor().equals("c2"),"空投影提前发布或没有前进");cycle();
                check(ids(w).equals("C,B,A")&&delivered.equals(List.of("B","C"))&&w.tailCursor.equals("first-tail")&&w.cursor.equals("old-cache"),"跨页重复身份或空投影破坏原恢复");
            }

            /** 已有尾游标只请求原readAfter，不启动最新整页桥接。 */
            static void existingTail()throws Exception{
                TranscriptWindow w=cached();w.recoverTail(page("A,B",false,null,"existing-tail"));int stable=w.before(0,100).get(1).id;reset(w);current.after.add(page("B,C",false,"after-tail",null));start();cycle();
                check(current.requests.equals(List.of("after:existing-tail"))&&ids(w).equals("C,B,A")&&delivered.equals(List.of("C"))&&w.before(0,100).get(2).id==stable&&w.cursor.equals("old-cache"),"已有tail改变原增量/去重/编号");
            }

            /** 执行完整接线，RED模式仍使用同一跨四页反例而非替代算法。 */
            public static void main(String[] args)throws Exception{
                crossesFourPages();
                if(args.length>0&&args[0].equals("red"))return;
                retriesSameCursor();
                for(String kind:List.of("connection","history","generation","tail","older"))lateIdentity(kind);
                stoppedCallbackReleases();stoppedWakeReleases();
                for(String kind:List.of("metadata","discontinuity","cycle"))rejectedOlder(kind);
                emptyAndDuplicate();existingTail();
                System.out.println("RuntimeTailRecovery: 14组实际watch/stop接线通过；不是Android设备验收");
            }
        """;
}
