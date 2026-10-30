package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/** 提取真实完成诊断和发送队列方法，验证门禁、队列边界及原终态；不读取真实账号。 */
public final class RuntimeSendCompletionTraceTest {
    /** 可指定隔离生产前像或变异；模型及Outbox沿原专项真实实现，只有平台与RPC被替代。 */
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java") : Path.of(args[0]);
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        var owner = StaticJavaParser.parse(source).getClassByName("CodexRuntime").orElseThrow();
        var helpers = owner.getMethodsByName("traceSendCompletion");
        if (helpers.size() != 1) throw new AssertionError("实际完成诊断helper不唯一");
        MethodDeclaration helper = helpers.get(0);
        verifyGatedFormatting(helper);
        verifyCompletionBoundary(owner.getMethodsByName("dispatchPreparedSend").get(0));
        List<MethodCallExpr> calls = owner.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("traceSendCompletion")).toList();
        if (calls.size() != 2 || calls.stream().anyMatch(call -> !call.getArgument(0).isStringLiteralExpr()
                || !call.getArgument(1).toString().equals("send.base"))) throw new AssertionError("完成诊断调用数量或身份来源变化");
        String actual = helper.toString().replace("org.telegram.messenger.BuildConfig", "CompletionBuildConfig")
                .replace("android.util.Log", "CompletionLog").replace("android.os.SystemClock", "CompletionClock");
        Method holdRpc = RuntimeSendAsyncQueueTest.class.getDeclaredMethod("holdRpc", String.class);
        holdRpc.setAccessible(true);
        RuntimeSendQueueTest.run(source, BOUNDARIES + SCENARIOS, fixture -> {
            try {
                fixture = (String) holdRpc.invoke(null, fixture);
                fixture = replaceOnce(fixture, "static void traceSendCompletion(String phase,String localId){}", actual);
                fixture = replaceOnce(fixture, "static final class ApplicationLoader {static boolean mainInterfacePaused;static final Object applicationContext=new Object();}",
                        "static final class ApplicationLoader {static boolean mainInterfacePaused;static CompletionContext applicationContext=new CompletionContext();}");
                // 只观察原队列入队边界；仍由原tasks保存并按原FIFO执行同一个Runnable。
                return replaceOnce(fixture, "public void postRunnable(Runnable r){tasks.add(r);}",
                        "public void postRunnable(Runnable r){CompletionEvents.enqueued(this);tasks.add(r);}");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    /** 替身绑定必须精确命中一处，防止夹具漂移后静默跳过真实观察点。 */
    private static String replaceOnce(String source, String before, String after) {
        int first = source.indexOf(before);
        if (first < 0 || source.indexOf(before, first + 1) >= 0) throw new AssertionError("合成边界不存在或不唯一：" + before);
        return source.substring(0, first) + after + source.substring(first + before.length());
    }

    /** 实际AST保证所有字符串连接只属于门后的Log参数，关闭时不会提前求值。 */
    private static void verifyGatedFormatting(MethodDeclaration helper) {
        var statements = helper.getBody().orElseThrow().getStatements();
        if (statements.size() != 2 || !statements.get(0).isIfStmt()
                || !statements.get(0).asIfStmt().getThenStmt().isReturnStmt() || !statements.get(1).isTryStmt())
            throw new AssertionError("完成诊断不再先判断构建开关");
        var guarded = statements.get(1).asTryStmt().getTryBlock().getStatements();
        if (guarded.size() != 2 || !guarded.get(0).isIfStmt() || !guarded.get(0).asIfStmt().getThenStmt().isReturnStmt())
            throw new AssertionError("日志格式化之前缺少完整门禁");
        MethodCallExpr log = guarded.get(1).asExpressionStmt().getExpression().asMethodCallExpr();
        if (!log.getScope().orElseThrow().toString().equals("android.util.Log") || !log.getNameAsString().equals("i"))
            throw new AssertionError("门后唯一语句不再是日志输出");
        long all = helper.findAll(BinaryExpr.class).stream().filter(value -> value.getOperator() == BinaryExpr.Operator.PLUS).count();
        long inside = log.findAll(BinaryExpr.class).stream().filter(value -> value.getOperator() == BinaryExpr.Operator.PLUS).count();
        if (all == 0 || all != inside) throw new AssertionError("门前出现字符串拼接");
        if (!log.getArgument(1).toString().equals("\"send_ack_phase=\" + phase + \" key=\" + localId.hashCode() + \" elapsedRealtimeMs=\" + android.os.SystemClock.elapsedRealtime()"))
            throw new AssertionError("诊断字段不再仅含固定阶段、身份哈希和单调时钟");
        var catches = statements.get(1).asTryStmt().getCatchClauses();
        if (catches.size() != 1 || !catches.get(0).getParameter().getType().toString().equals("Throwable")
                || !catches.get(0).getBody().getStatements().isEmpty()) throw new AssertionError("诊断故障不再被隔离");
    }

    /** 原完成lambda先记录再入队；原入队lambda首句记录，然后才结算同一send/error。 */
    private static void verifyCompletionBoundary(MethodDeclaration dispatch) {
        var completions = dispatch.findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals("whenComplete")).toList();
        if (completions.size() != 1) throw new AssertionError("发送完成入口变化");
        LambdaExpr completion = completions.get(0).getArgument(0).asLambdaExpr();
        var posted = completion.getBody().asBlockStmt().getStatements();
        if (posted.size() != 2 || !posted.get(0).asExpressionStmt().getExpression().toString().equals("traceSendCompletion(\"completion_post\", send.base)"))
            throw new AssertionError("completion_post不在原入队之前");
        MethodCallExpr enqueue = posted.get(1).asExpressionStmt().getExpression().asMethodCallExpr();
        if (!enqueue.getScope().orElseThrow().toString().equals("sendQueue") || !enqueue.getNameAsString().equals("postRunnable"))
            throw new AssertionError("原完成队列被替换");
        var ran = enqueue.getArgument(0).asLambdaExpr().getBody().asBlockStmt().getStatements();
        if (ran.size() != 2 || !ran.get(0).asExpressionStmt().getExpression().toString().equals("traceSendCompletion(\"completion_run\", send.base)")
                || !ran.get(1).asExpressionStmt().getExpression().toString().equals("completePreparedSend(send, error == null, error)"))
            throw new AssertionError("completion_run不是原完成队列首句或终态参数改变");
    }

    private static final String BOUNDARIES = """
        static int completionGroups;
        /** 可变构建开关只用于覆盖门禁组合，不影响其他原发送阶段。 */
        static class CompletionBuildConfig{static boolean DEBUG_VERSION;}
        /** 只替代包名读取，记录门关闭时是否仍读取应用信息。 */
        static class CompletionContext{
            static String packageName;static int reads;
            /** 返回合成包名；故障用于证明诊断异常不影响ACK。 */
            String getPackageName(){reads++;if(CompletionLog.failure.equals("context"))throw new AssertionError("synthetic-context");return packageName;}
        }
        /** 显式推进虚拟单调时钟，不睡眠、不创建新的发送线程。 */
        static class CompletionClock{
            static long now;static int reads;
            /** 记录真实helper何时取钟，不介入原发送期限。 */
            static long elapsedRealtime(){reads++;if(CompletionLog.failure.equals("clock"))throw new AssertionError("synthetic-clock");return now;}
        }
        /** 只接收实际helper的日志；文本格式和字段由原代码生成。 */
        static class CompletionLog{
            static final int DEBUG=3;static boolean loggable;static String failure="";static int gateReads,attempts;
            static final ArrayList<String> messages=new ArrayList<>();
            /** 记录日志开关读取；未明确开启时默认返回false。 */
            static boolean isLoggable(String tag,int level){gateReads++;check(tag.equals("CodexSendAck")&&level==DEBUG,"wrong diagnostic gate");if(failure.equals("gate"))throw new AssertionError("synthetic-gate");return loggable;}
            /** 观察终态尚未落盘时的run点，并保持Log故障为可注入边界。 */
            static int i(String tag,String message){
                attempts++;check(tag.equals("CodexSendAck"),"wrong completion tag");
                if(failure.equals("log"))throw new AssertionError("synthetic-log");
                messages.add(message);
                if(CompletionEvents.capture){
                    CompletionEvents.events.add(message.contains("completion_post")?"post":"run");
                    if(message.contains("completion_run"))try{
                        OutboxStore.Item item=store.get(CompletionEvents.localId);
                        CompletionEvents.runBeforeTerminal=item!=null&&item.submissionUncertain&&!item.submissionAccepted&&preparedInFlight==1;
                    }catch(IOException error){CompletionEvents.runBeforeTerminal=false;}
                }
                return 0;
            }
        }
        /** 仅观察指定原sendQueue，其他队列和所有Runnable保持原样。 */
        static class CompletionEvents{
            static boolean capture,runBeforeTerminal;static String localId;static int enqueues;
            static final ArrayList<String> events=new ArrayList<>();
            /** 入队前记录位置，不自行入队、取消或执行任何任务。 */
            static void enqueued(Queue queue){if(queue==sendQueue){enqueues++;if(capture)events.add("enqueue");}}
        }
        /** 每例清除合成观测；默认采用debug包但日志开关关闭。 */
        static void completionReset(){
            CompletionBuildConfig.DEBUG_VERSION=true;CompletionLog.loggable=false;CompletionLog.failure="";
            CompletionLog.gateReads=CompletionLog.attempts=CompletionClock.reads=CompletionContext.reads=0;
            CompletionLog.messages.clear();CompletionClock.now=1000;CompletionContext.packageName="com.butang.codextop.nativepreview.beta";
            ApplicationLoader.applicationContext=new CompletionContext();CompletionEvents.capture=CompletionEvents.runBeforeTerminal=false;CompletionEvents.localId=null;CompletionEvents.enqueues=0;CompletionEvents.events.clear();
        }
        /** 只允许固定阶段、整数哈希及单调时钟，正文、完整身份和异常内容均不能进入日志。 */
        static void completionGrammar(String localId){
            for(String message:CompletionLog.messages){
                check(message.matches("send_ack_phase=(completion_post|completion_run) key=-?[0-9]+ elapsedRealtimeMs=[0-9]+"),"completion log grammar changed");
                check(!message.contains(localId)&&!message.contains("private-body-canary")&&!message.contains("private-error-canary"),"completion diagnostic leaked content");
            }
        }
        """;

    private static final String SCENARIOS = """
        /** 各个门禁独立关闭时都不取钟或进入格式化；release甚至不访问日志开关。 */
        static void closedGate(String mode){
            completionReset();CompletionLog.loggable=true;
            if(mode.equals("release"))CompletionBuildConfig.DEBUG_VERSION=false;
            else if(mode.equals("log-off"))CompletionLog.loggable=false;
            else if(mode.equals("null-context"))ApplicationLoader.applicationContext=null;
            else CompletionContext.packageName=mode.equals("telegram")?"org.telegram.messenger":"com.butang.codextop.debug";
            traceSendCompletion("completion_post","private-identity-canary");
            check(CompletionClock.reads==0&&CompletionLog.attempts==0&&CompletionLog.messages.isEmpty()&&CompletionEvents.enqueues==0,"closed gate did work");
            if(mode.equals("release"))check(CompletionLog.gateReads==0&&CompletionContext.reads==0,"release read platform state");
            if(mode.equals("log-off")||mode.equals("null-context"))check(CompletionLog.gateReads==1&&CompletionContext.reads==0,"closed gate read package");
            if(mode.equals("telegram")||mode.equals("legacy"))check(CompletionContext.reads==1,"package gate was skipped");
            completionGroups++;System.out.println("PASS completion closed="+mode+" clock=0 format=0 dispatch=0");
        }
        /** 显式开启的两个实际包仅输出一次固定格式，不额外入队或发送。 */
        static void openGate(String packageName){
            completionReset();CompletionLog.loggable=true;CompletionContext.packageName=packageName;String id="private-identity-canary";
            traceSendCompletion("completion_post",id);
            check(CompletionClock.reads==1&&CompletionLog.attempts==1&&CompletionLog.messages.size()==1&&CompletionEvents.enqueues==0,"enabled trace changed work count");
            check(CompletionLog.messages.get(0).equals("send_ack_phase=completion_post key="+id.hashCode()+" elapsedRealtimeMs=1000"),"completion key or clock changed");
            completionGrammar(id);completionGroups++;System.out.println("PASS completion explicit package="+packageName);
        }
        /** 在原future与sendQueue之间停住，验证post先于入队、run先于实际终态，不靠线程时序猜测。 */
        static void terminal(Path root,String outcome,boolean enabled,String failure)throws Exception{
            reset(root);completionReset();CompletionLog.loggable=enabled;CompletionLog.failure=failure;connection.holdSend=true;
            SendMessageParams params=new SendMessageParams();params.message="private-body-canary";sendBatch(0,params,new ArrayList<>(Arrays.asList(params)));
            String id=base();sendQueue.all();ui.all();
            check(connection.heldSend!=null&&store.get(id).submissionUncertain&&!store.get(id).submissionAccepted&&connection.sendCalls==1,"original ACK boundary not held");
            check(CompletionLog.messages.isEmpty()&&CompletionClock.reads==0,"completion observed before ACK");
            sendQueue.postRunnable(()->CompletionEvents.events.add("prior-work"));
            CompletionEvents.capture=true;CompletionEvents.localId=id;int posts=CompletionEvents.enqueues;
            if(outcome.equals("success"))connection.heldSend.complete(new JsonObject());
            else connection.heldSend.completeExceptionally(outcome.equals("rejected")?new DesktopConnection.SendRejectedException("private-error-canary")
                    :new java.util.concurrent.TimeoutException("private-error-canary"));
            boolean visible=enabled&&failure.isEmpty();
            check(CompletionEvents.enqueues==posts+1&&sendQueue.tasks.size()==2&&connection.sendCalls==1,"completion added dispatch or changed queue count");
            check(CompletionEvents.events.equals(visible?Arrays.asList("post","enqueue"):Arrays.asList("enqueue")),"post not before original enqueue");
            check(store.get(id).submissionUncertain&&!store.get(id).submissionAccepted,"ACK mutated terminal before queue");
            sendQueue.tasks.remove().run();check(CompletionEvents.events.get(CompletionEvents.events.size()-1).equals("prior-work"),"FIFO preceding work skipped");
            CompletionClock.now=9000;sendQueue.tasks.remove().run();
            if(visible){
                check(CompletionEvents.events.get(3).equals("run")&&CompletionEvents.runBeforeTerminal,"run was not first completion work before terminal mutation");
                check(CompletionLog.messages.size()==2&&CompletionLog.messages.get(0).endsWith("elapsedRealtimeMs=1000")
                        &&CompletionLog.messages.get(1).endsWith("elapsedRealtimeMs=9000"),"queue elapsed observation inaccurate");
            }
            CompletionEvents.capture=false;sendQueue.all();ui.all();OutboxStore.Item stored=store.get(id);
            check(stored.submissionAccepted==outcome.equals("success")&&stored.submissionUncertain==outcome.equals("timeout"),"original ACK/rejection/timeout state changed");
            check(sendJobs.isEmpty()&&preparedSends.isEmpty()&&preparedDialogs.isEmpty()&&preparedInFlight==0&&connection.sendCalls==1,"completion retained slot or resent");
            if(!enabled)check(CompletionClock.reads==0&&CompletionLog.attempts==0,"default completion path took clock or formatted");
            completionGrammar(id);completionGroups++;System.out.println("PASS completion terminal="+outcome+" enabled="+enabled+" logFailure="+failure+" originalDispatches=1");
        }
        /** 所有输入为合成数据，显式驱动原队列，异常边界不能改变终态或增加派发。 */
        public static void main(String[] args)throws Exception{
            Path root=Paths.get(args[0]);
            for(String gate:new String[]{"release","log-off","null-context","telegram","legacy"})closedGate(gate);
            openGate("com.butang.codextop.nativepreview");openGate("com.butang.codextop.nativepreview.beta");
            for(String outcome:new String[]{"success","rejected","timeout"})for(boolean enabled:new boolean[]{false,true})terminal(root,outcome,enabled,"");
            for(String failure:new String[]{"gate","context","clock","log"})terminal(root,"success",true,failure);
            System.out.println("RuntimeSendCompletionTrace: groups="+completionGroups+" failures=0; actual Java8 Runtime helper and completion queue / fixed fields only");
        }
        """;
}
