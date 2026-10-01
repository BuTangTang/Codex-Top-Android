package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取实际ChatActivity目标栏、原隐藏方法及pin交互前置分支；不代替Android渲染和手势实拍。 */
public final class GoalPinnedUiTest {
    /** 基线模式执行原更新入口前段，缺目标接线必须由可观察容器行为给出RED。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        String source=Files.readString(args.length==0?Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"):Path.of(args[0]));
        StringBuilder methods=new StringBuilder();
        for(String name:new String[]{"updateCodexGoalPinnedView","hidePinnedMessageView","codexGoalInfo"}) {
            String actual=RuntimeGoalStatusTest.method(source,name);
            if(actual!=null)methods.append(StaticJavaParser.parseMethodDeclaration(actual)).append('\n');
            else if(name.equals("updateCodexGoalPinnedView"))methods.append("void updateCodexGoalPinnedView(boolean a){}\n");
            else if(name.equals("codexGoalInfo"))methods.append("static String codexGoalInfo(SessionStatus.Goal g){return \"\";}\n");
        }
        // 只替代后续真实Telegram pin操作；Codex分支和模式门禁完整来自实际入口。
        int start=source.indexOf("    private void updatePinnedMessageView(boolean animated, int animateToNext)");
        int nativeStart=source.indexOf("        int pinned_msg_id;",start);
        methods.append(source,start,nativeStart).append("nativePinCalls++;}\n");
        String listMethod=RuntimeGoalStatusTest.method(source,"updatePinnedListButton");
        int listNative=listMethod.indexOf("        if ((isThreadChat()");
        methods.append(listMethod,0,listNative).append("nativeButtonCalls++;}\n");
        String openMethod=RuntimeGoalStatusTest.method(source,"openPinnedMessagesList");
        int openNative=openMethod.indexOf("        if (getParentActivity()");
        methods.append(openMethod,0,openNative).append("nativeListCalls++;}\n");
        var create=StaticJavaParser.parseMethodDeclaration(RuntimeGoalStatusTest.method(source,"createPinnedMessageView"));
        for(MethodCallExpr call:create.findAll(MethodCallExpr.class)) {
            if(!call.getNameAsString().equals("setOnClickListener")||call.getArguments().isEmpty())continue;
            String scope=call.getScope().map(Object::toString).orElse("");
            if(!java.util.Set.of("pinnedMessageView","closePinned","pinnedListButton").contains(scope))continue;
            var statement=call.getArgument(0).asLambdaExpr().getBody();
            var body=statement.isBlockStmt()?statement.asBlockStmt():new com.github.javaparser.ast.stmt.BlockStmt().addStatement(statement.clone());
            // 真实Codex早返回之后的pin行为以计数替身结束，不读取真实消息对象。
            String first=body.getStatements().get(0).toString();
            if(scope.equals("pinnedListButton"))methods.append("void listClick()").append(body);
            else methods.append("void ").append(scope.equals("pinnedMessageView")?"barClick":"closeClick").append("(){")
                    .append(first.contains("CodexRuntime")?first:"").append("nativePinCalls++;}\n");
        }
        var longClick=create.findAll(MethodCallExpr.class).stream().filter(c->c.getNameAsString().equals("setOnLongClickListener")).findFirst().orElseThrow();
        String firstLong=longClick.getArgument(0).asLambdaExpr().getBody().asBlockStmt().getStatement(0).toString();
        methods.append("boolean barLongClick(){").append(firstLong.contains("CodexRuntime")?firstLong:"").append("nativePinCalls++;return true;}\n");
        var touch=create.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("onTouchEvent")).findFirst().orElseThrow();
        methods.append("/** 执行实际匿名容器触摸方法，父View只记录原click派发。 */ class Touch extends TouchBase {float lastY,startY;")
                .append(touch).append("}\n");
        Path temporary=Files.createTempDirectory("codex-goal-pinned-");
        try {
            Path probe=temporary.resolve("GoalPinnedProbe.java");Files.writeString(probe,FIXTURE+methods+SCENARIOS+"\n}");
            Path runtime=temporary.resolve("CodexRuntime.java");Files.writeString(runtime,"package com.butang.codextop; public class CodexRuntime {public static boolean owned=true;public static SessionStatus.Snapshot value;public static boolean ownsConversation(long id){return owned;}public static SessionStatus.Snapshot status(long id){return value;}}");
            var compile=new ArrayList<String>(java.util.List.of("-cp",System.getProperty("java.class.path"),"-d",temporary.toString(),probe.toString(),runtime.toString()));
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))!=0)throw new AssertionError("原目标栏夹具编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},GoalPinnedUiTest.class.getClassLoader())) {
                try{loader.loadClass("com.butang.codextop.GoalPinnedProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("实际原目标栏回归失败",e.getCause());}
            }
            // 原48dp容器、已有信息按钮与STATUS通知接线仍必须存在，绝不创建目标MessageObject。
            if(!source.contains("topPanelLayout.addView(pinnedMessageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))"))throw new AssertionError("改动原48dp布局");
            String info=RuntimeGoalStatusTest.method(source,"showCodexConversationInfo");
            if(!info.contains("codexGoalInfo(status.goal)"))throw new AssertionError("原会话信息没接完整目标");
            if(!source.contains("if(com.butang.codextop.CodexRuntime.ownsConversation(dialog_id))updatePinnedMessageView(true);"))throw new AssertionError("原STATUS通知未接目标更新");
        } finally {try(var paths=Files.walk(temporary)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    // 原控件替身只记录文本、可见性和padding；不存在网络、真实历史或产品状态算法。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import com.google.gson.*;
        public class GoalPinnedProbe {
            long dialog_id=7;int chatMode,nativePinCalls,nativeButtonCalls,nativeListCalls,paddingCalls,infoCalls,previewCalls;
            Object currentEncryptedChat;boolean report,setPinnedTextTranslationX;Bar actionBar=new Bar();
            View pinnedMessageView;View pinnedListButton=new View(),closePinned=new View(),pinnedProgress=new View(),pinnedCounterTextView=new View(),pinnedLineView=new View();
            View[] pinnedMessageImageView={new View(),new View()},pinnedMessageButton={new View(),new View()},pinnedNameTextView={new View(),new View()},pinnedMessageTextView={new View(),new View()};
            Animation[] pinnedNextAnimation=new Animation[2];Animation pinnedListAnimator;Panel topPanelLayout=new Panel();
            static class View {static final int VISIBLE=0,INVISIBLE=4,GONE=8;int visibility;Object tag;String text="";float x,y;void setVisibility(int v){visibility=v;}Object getTag(){return tag;}void setTag(Object t){tag=t;}void setText(CharSequence s){text=s.toString();}void setTranslationX(float v){x=v;}void setTranslationY(float v){y=v;}Paint getPaint(){return new Paint();}void set(int a,int b,boolean c){} }
            static class Paint {Object getFontMetricsInt(){return null;}}
            static class Emoji {static CharSequence replaceEmoji(CharSequence s,Object metrics,boolean flag){return s;}}
            static class Animation {boolean cancelled;void cancel(){cancelled=true;}}
            static class Panel {boolean visible;int transitions;void setViewVisible(View v,boolean on,boolean animated){visible=on;transitions++;}}
            static class Bar {boolean selecting,search;boolean isActionModeShowed(){return selecting;}boolean isSearchFieldVisible(){return search;}}
            static class MotionEvent {static final int ACTION_UP=1,ACTION_MOVE=2;int action;MotionEvent(int a){action=a;}int getAction(){return action;}float getY(){return 4;}}
            class TouchBase {public boolean onTouchEvent(MotionEvent e){return true;}}
            boolean isReport(){return report;}void createPinnedMessageView(){pinnedMessageView=new View();pinnedMessageView.setTag(1);}
            void checkListViewPaddings(){paddingCalls++;}void showCodexConversationInfo(){infoCalls++;}
            void finishPreviewFragment(){previewCalls++;}void movePreviewFragment(float dy){previewCalls++;}
            static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
            static SessionStatus.Store store=new SessionStatus.Store();
            static void goal(String availability,long started,long now) {
                JsonObject response=JsonParser.parseString("{\\"ok\\":true,\\"machineOnline\\":true,\\"observation\\":{\\"v\\":1,\\"state\\":\\"unknown\\",\\"reason\\":\\"missing_turn_id\\"},\\"goal\\":"+availability+"}").getAsJsonObject();
                store.observation(7,"machine","remote",response,started,started+1);CodexRuntime.value=store.get(7,now);
            }
        """;

    private static final String SCENARIOS="""
            /** 真实目标状态、失效、null/0、原多选搜索及普通Telegram分支均执行实际方法。 */
            public static void main(String[] args) {
                final String available="{\\"availability\\":\\"available\\",\\"source\\":\\"desktop\\",\\"threadId\\":\\"remote\\",\\"objective\\":\\"目标👋下一行\\n保持原文\\",\\"status\\":\\"active\\",\\"tokenBudget\\":0,\\"tokensUsed\\":null,\\"timeUsedSeconds\\":0,\\"updatedAt\\":0}";
                goal(available,100,101);GoalPinnedProbe p=new GoalPinnedProbe();p.pinnedNextAnimation[0]=new Animation();Animation old=p.pinnedNextAnimation[0];
                p.updatePinnedMessageView(true,0);check(p.topPanelLayout.visible&&p.pinnedMessageView!=null,"原聊天更新没有显示目标栏");
                check(p.pinnedNameTextView[0].text.equals("目标进行中")&&p.pinnedMessageTextView[0].text.equals("目标👋下一行\\n保持原文"),"目标状态或objective被伪造/截原文");
                check(old.cancelled&&p.pinnedNextAnimation[0]==null&&p.paddingCalls==1,"残留pin动画或没沿原padding路径");
                check(p.pinnedListButton.visibility==View.GONE&&p.closePinned.visibility==View.GONE&&p.pinnedProgress.visibility==View.GONE&&p.pinnedMessageButton[0].visibility==View.GONE,"目标误带pin操作");
                String info=codexGoalInfo(CodexRuntime.value.goal);check(info.contains("Token预算：0")&&info.contains("已用Token：未知")&&info.contains("已用时间（秒）：0")&&info.contains("来源更新时间：0"),"null/零/秒/来源时刻展示失真");
                p.barClick();p.barLongClick();p.closeClick();p.listClick();p.openPinnedMessagesList(false);p.new Touch().onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));p.new Touch().onTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE));
                check(p.infoCalls==1&&p.nativePinCalls==0&&p.nativeListCalls==0&&p.previewCalls==0,"Codex进入真实pin或预览返回路径");
                p.actionBar.selecting=true;p.updatePinnedMessageView(true,0);check(!p.topPanelLayout.visible&&p.paddingCalls==2,"原多选没有隐藏目标栏");
                p.actionBar.selecting=false;p.updatePinnedMessageView(true,0);check(p.topPanelLayout.visible&&p.paddingCalls==3,"取消多选未恢复目标栏");
                p.actionBar.search=true;p.updatePinnedMessageView(true,0);check(!p.topPanelLayout.visible,"搜索未隐藏目标栏");p.actionBar.search=false;
                CodexRuntime.value=store.get(7,15100);p.updatePinnedMessageView(true,0);check(p.topPanelLayout.visible&&p.pinnedNameTextView[0].text.startsWith("上次："),"旧目标伪当前或无法查看");
                goal("{\\"availability\\":\\"unknown\\"}",16000,16001);p.updatePinnedMessageView(true,0);check(p.pinnedNameTextView[0].text.contains("目标未知")&&p.pinnedNameTextView[0].text.startsWith("上次："),"unknown目标没有上次标记");
                goal("{\\"availability\\":\\"none\\",\\"source\\":\\"desktop\\"}",17000,17001);p.updatePinnedMessageView(true,0);check(!p.topPanelLayout.visible&&!codexGoalInfo(CodexRuntime.value.goal).contains("预算"),"明确无目标残留目标或预算");
                store.clear();goal("{\\"availability\\":\\"unknown\\"}",18000,18001);p.updatePinnedMessageView(true,0);check(!p.topPanelLayout.visible,"无历史目标时补造目标栏");
                CodexRuntime.owned=false;p.updatePinnedMessageView(false,0);p.updatePinnedListButton(false);p.barClick();p.barLongClick();p.closeClick();p.listClick();p.openPinnedMessagesList(false);p.new Touch().onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
                check(p.nativePinCalls==4&&p.nativeButtonCalls==1&&p.nativeListCalls==2&&p.previewCalls==1,"普通Telegram pin分支改变");
                p.currentEncryptedChat=new Object();p.updatePinnedMessageView(false,0);check(p.nativePinCalls==4,"原模式门禁改变");
                System.out.println("GoalPinnedUi: 实际48dp入口/状态与原值/多选搜索/失效/点击长按close列表触摸隔离通过；未代替Android实拍");
            }
        """;
}
