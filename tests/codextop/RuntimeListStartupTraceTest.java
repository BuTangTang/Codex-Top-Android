package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;

/** 提取真实启动诊断和摘要发布方法；平台时钟、View及消息容器是合成边界，不读取账号。 */
public final class RuntimeListStartupTraceTest {
    /** 默认读仓库两owner；可指定隔离仓库根验证精确提交组合，内层真实方法按Java8编译。 */
    public static void main(String[] args) throws Exception {
        Path repo = args.length == 0 ? Path.of("") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var runtime = StaticJavaParser.parse(repo.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"))
                .getClassByName("CodexRuntime").orElseThrow();
        var cell = StaticJavaParser.parse(repo.resolve("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"))
                .getClassByName("DialogCell").orElseThrow();
        StringBuilder methods = new StringBuilder();
        for (var field : runtime.getFields()) {
            if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().endsWith("TraceEvents")))
                methods.append(field).append('\n');
        }
        for (String name : List.of("beginHistoryTrace", "traceHistoryDuration", "traceHistoryPoint",
                "needsHistoryDialogDrawTrace", "traceHistoryDialogDraw", "publishDialogPreview",
                "sameDialogPreview", "enabled")) methods.append(method(runtime, name)).append('\n');
        methods.append(method(cell, "traceCodexPreviewDraw"));
        // 确认两条真实摘要绘制分支仍接观测；不复制Canvas绘制或业务排程算法。
        long drawHooks = cell.getMethodsByName("onDraw").get(0)
                .findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("traceCodexPreviewDraw")).count();
        if (drawHooks != 2) throw new AssertionError("actual draw hook changed");
        String source = (FIXTURE + methods + SCENARIOS)
                .replace("org.telegram.messenger.BuildVars", "BuildVars")
                .replace("android.util.Log", "Log").replace("android.os.SystemClock", "Clock")
                .replace("com.butang.codextop.CodexRuntime.", "Probe.");
        Path temporary = Files.createTempDirectory("runtime-list-startup-trace-");
        try {
            Path probe = temporary.resolve("Probe.java");
            Files.writeString(probe, source);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                    "-d", temporary.toString(), probe.toString()) != 0)
                throw new AssertionError("actual trace helper compile failed");
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("Probe").getMethod("main", String[].class).invoke(null, (Object) new String[0]); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("actual trace behavior failed", error.getCause()); }
            }
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 方法缺失或重载漂移必须显式失败，不补理想化待测实现。 */
    private static String method(ClassOrInterfaceDeclaration owner, String name) {
        var methods = owner.getMethodsByName(name);
        if (methods.size() != 1) throw new AssertionError("expected one actual method: " + name);
        return methods.get(0).toString();
    }

    private static final String FIXTURE = """
import java.util.*;
public class Probe {
 static final class BuildVars {static boolean DEBUG_VERSION=true;}
 static final class Context {String getPackageName(){return pkg;}}
 static final class ApplicationLoader {static Context applicationContext=new Context();}
 static String pkg="com.butang.codextop.nativepreview.beta";
 static final class Clock {static int calls;static long now=100;static long elapsedRealtime(){calls++;if(fail.equals("clock"))throw new Error("canary");return now++;}}
 static String fail="";
 static final class Log {static final int DEBUG=3;static boolean on=true;static int gates;static List<String> lines=new ArrayList<>();
  static boolean isLoggable(String t,int l){gates++;if(fail.equals("gate"))throw new Error("canary");check(t.equals("CodexHistoryStartup")&&l==DEBUG,"tag");return on;}
  static int d(String t,String s){if(fail.equals("log"))throw new Error("canary");lines.add(s);return 0;}}
 static final class TLRPC {static class Message {long dialog_id;int id,date,send_state;boolean out;String message,attachPath;Map<String,String> params;}static final class TL_message extends Message {}}
 static final class MessageObject {TLRPC.Message messageOwner;MessageObject(TLRPC.Message m){messageOwner=m;}}
 static final class DesktopConnection {}
 static final Map<String,MessageObject> pendingMessages=new HashMap<>();
 static final class MessagesController {static final int UPDATE_MASK_MESSAGE_TEXT=1;static final MessagesController value=new MessagesController();Map<Long,ArrayList<MessageObject>> dialogMessage=new HashMap<>();static MessagesController getInstance(int a){return value;}}
 static final class NotificationCenter {static final int updateInterfaces=2;static int calls;static boolean throwsOriginal;static final NotificationCenter value=new NotificationCenter();static NotificationCenter getInstance(int a){return value;}void postNotificationName(int n,int m){calls++;if(throwsOriginal)throw new IllegalStateException("original");}}
 static boolean current=true;static int built;
 static boolean previewCurrent(int a,long e,long d,String r,String m,DesktopConnection c){return current;}
 static MessageObject historyObject(int a,TLRPC.TL_message m,boolean layout){built++;check(!layout,"preview generated chat layout");return new MessageObject(m);}
 static final class Paint {int alpha=255;int getAlpha(){return alpha;}}
 static final class Layout {String text="synthetic";Paint paint=new Paint();String getText(){return text;}Paint getPaint(){return paint;}}
 Layout messageLayout=new Layout();
 Object message,draftMessage;boolean draftVoice,clearingDialog;String customMessage;boolean isDialogCell=true,attachedToWindow=true;boolean shown=true,codex=true;int visibility;int metrics;static final int VISIBLE=0;
 int getWindowVisibility(){metrics++;return visibility;}boolean isShown(){metrics++;return shown;}boolean hasCodexStatusAvatar(){metrics++;return codex;}
        """;

    private static final String SCENARIOS = """
 static int checks;
 /** 合成平台状态独立重置，不改提取的生产判断。 */
 static void reset()throws Exception{for(java.lang.reflect.Field f:Probe.class.getDeclaredFields())if(f.getName().endsWith("TraceEvents"))f.setInt(null,0);BuildVars.DEBUG_VERSION=true;Log.on=true;Log.lines.clear();Log.gates=0;Clock.calls=0;Clock.now=100;fail="";pkg="com.butang.codextop.nativepreview.beta";ApplicationLoader.applicationContext=new Context();current=true;built=0;pendingMessages.clear();MessagesController.value.dialogMessage.clear();NotificationCenter.calls=0;NotificationCenter.throwsOriginal=false;}
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static TLRPC.TL_message row(int id){TLRPC.TL_message m=new TLRPC.TL_message();m.dialog_id=42;m.id=id;m.message="private-body-canary";return m;}
 static void publish(TLRPC.TL_message m,MessageObject p){publishDialogPreview(0,1,42,"private-id","private-machine",null,m,p,Collections.emptySet());}
 static Probe cell(){Probe c=new Probe();c.message=new Object();return c;}
 /** 所有默认关闭门禁不取钟、不量View、不记录任何输出。 */
 static void gates()throws Exception{for(String gate:Arrays.asList("release","tag","telegram","null")){reset();if(gate.equals("release"))BuildVars.DEBUG_VERSION=false;if(gate.equals("tag"))Log.on=false;if(gate.equals("telegram"))pkg="org.telegram.messenger";if(gate.equals("null"))ApplicationLoader.applicationContext=null;Probe c=cell();c.traceCodexPreviewDraw();traceHistoryPoint("dialogs_ui_enter",1);traceHistoryDuration("dialog_store_read",beginHistoryTrace("dialog_store_read"));publish(row(1),null);check(Clock.calls==0&&c.metrics==0&&Log.lines.isEmpty(),"disabled metrics/log "+gate);check(built==1&&NotificationCenter.calls==1,"disabled changed publish");}}
 /** 真发布方法保留原guard/待发/same/接纳顺序，异常仍从原通知抛出。 */
 static void preview()throws Exception{reset();current=false;publish(row(1),null);check(Log.lines.get(0).endsWith("reason=1")&&built==0,"guard");current=true;TLRPC.TL_message pending=row(-1);pending.params=new HashMap<>();pending.params.put("codexLocalId","private-local");MessageObject p=new MessageObject(pending);pendingMessages.put("private-local",p);MessagesController.value.dialogMessage.put(42L,new ArrayList<>(Arrays.asList(p)));publish(row(2),null);check(Log.lines.get(1).endsWith("reason=2")&&built==0,"pending");pendingMessages.clear();TLRPC.TL_message m=row(2);publish(m,null);check(Log.lines.get(2).endsWith("reason=4")&&built==1&&NotificationCenter.calls==1,"accept");publish(m,null);check(Log.lines.get(3).endsWith("reason=3")&&built==1,"same");NotificationCenter.throwsOriginal=true;try{publish(row(3),null);throw new AssertionError("swallowed");}catch(IllegalStateException e){check(e.getMessage().equals("original"),"changed exception");}check(Log.lines.get(4).endsWith("reason=4"),"map accept before notification failure");}
 /** 原预算与新预算独立；首个有效摘要最多一次，失败及无效行不冒充绘制。 */
 static void bounded()throws Exception{reset();for(int i=0;i<50;i++){traceHistoryPoint("dialogs_ui_enter",1);traceHistoryPoint("preview_ui",3);traceHistoryDuration("dialog_store_read",beginHistoryTrace("dialog_store_read"));}check(Log.lines.size()==96,"phase cap");int clocks=Clock.calls;traceHistoryPoint("preview_ui",4);check(Clock.calls==clocks,"exhausted clock");traceHistoryDuration("open_queue",beginHistoryTrace("open_queue"));check(Log.lines.size()==97,"open reservation");Probe c=cell();c.draftMessage=new Object();c.traceCodexPreviewDraw();check(historyDialogDrawTraceEvents==0,"draft draw");c.draftMessage=null;c.messageLayout.paint.alpha=0;c.traceCodexPreviewDraw();check(historyDialogDrawTraceEvents==0,"transparent draw");c.messageLayout.paint.alpha=255;c.messageLayout.text="";c.traceCodexPreviewDraw();check(historyDialogDrawTraceEvents==0,"empty draw");c.messageLayout.text="synthetic";c.codex=false;c.traceCodexPreviewDraw();check(historyDialogDrawTraceEvents==0,"TG draw");c.codex=true;c.traceCodexPreviewDraw();check(historyDialogDrawTraceEvents==1,"valid draw");clocks=Clock.calls;int metrics=c.metrics;for(int i=0;i<40;i++)c.traceCodexPreviewDraw();check(c.metrics==metrics&&Clock.calls==clocks,"once draw hotpath");for(String line:Log.lines)check(line.matches("history_phase=(dialogs_ui_enter|preview_ui|dialog_store_read|open_queue|dialog_first_draw) (trace_limited=true|elapsedRealtimeMs=[0-9]+ (reason=[0-4]|durationMs=[0-9]+))"),"forbidden value: "+line);}
 /** 诊断平台异常不影响原发布结果或绘制调用者。 */
 static void failures()throws Exception{for(String failure:Arrays.asList("gate","clock","log")){reset();fail=failure;publish(row(1),null);cell().traceCodexPreviewDraw();check(built==1&&NotificationCenter.calls==1,"diagnostic failure changed business");}}
 /** 真实摘要替代内容保留旧MessageObject时，不得抢占唯一首条绘制资格。 */
 static void placeholderGuards()throws Exception{
  int rejected=0;
  for(String kind:Arrays.asList("voice-draft","clearing-placeholder","custom-placeholder")){
   reset();Probe c=cell();
   if(kind.equals("voice-draft")){c.draftVoice=true;c.messageLayout.text="Draft: Audio";}
   if(kind.equals("clearing-placeholder")){c.clearingDialog=true;c.messageLayout.text="History cleared";}
   if(kind.equals("custom-placeholder")){c.customMessage="Synthetic status";c.messageLayout.text=c.customMessage;}
   c.traceCodexPreviewDraw();boolean pass=historyDialogDrawTraceEvents==0&&Log.lines.isEmpty();
   System.out.println(kind+" expected=false actual="+(historyDialogDrawTraceEvents!=0));if(!pass)rejected++;
   // 替代内容退场后，空customMessage同原TextUtils.isEmpty语义，不阻止真正摘要。
   c.draftVoice=false;c.clearingDialog=false;c.customMessage="";c.messageLayout.text="synthetic current message";
   c.traceCodexPreviewDraw();check(historyDialogDrawTraceEvents==1,"true preview did not get first draw after placeholder");
  }
  check(rejected==0,"placeholder consumed first draw: "+rejected);
 }
 public static void main(String[] args)throws Exception{gates();preview();bounded();failures();placeholderGuards();System.out.println("StartupObservationProbe checks="+checks+" failures=0");}
}
        """;
}
