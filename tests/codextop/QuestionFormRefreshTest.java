package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取ChatActivity的实际提问入口及表单方法，只替换原视图、弹窗和网络结果边界。 */
public final class QuestionFormRefreshTest {
    /** 小堆仅解析目标方法，不加载Telegram巨型界面或真实Android账号。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source=Path.of("TMessagesProj/src/main/java");
        String chat=Files.readString(source.resolve("org/telegram/ui/ChatActivity.java"));
        int start=chat.indexOf("    private boolean refreshCodexQuestion()"),end=chat.indexOf("    private void submitCodexQuestions(",start);
        if(start<0||end<0)throw new AssertionError("实际打开表单尚未接入状态核对");
        var methods=StaticJavaParser.parse("class Methods {"+chat.substring(start,end)+"}");
        int showStart=chat.indexOf("    private void showCodexQuestion("),showEnd=chat.indexOf("    /** 只为仍打开",showStart);
        var show=StaticJavaParser.parseMethodDeclaration(chat.substring(showStart,showEnd));
        var positive=show.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(call->call.getNameAsString().equals("setOnClickListener")&&call.getScope().isPresent()
                        &&call.getScope().get().toString().contains("BUTTON_POSITIVE")).findFirst().orElseThrow();
        StringBuilder submitTail=new StringBuilder();boolean capture=false;
        for(var statement:positive.getArgument(0).asLambdaExpr().getBody().asBlockStmt().getStatements()){
            capture|=statement.toString().contains("QuestionReview currentReview =");
            if(capture)submitTail.append(statement).append('\n');
        }
        var dismiss=show.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream()
                .filter(branch->branch.getCondition().toString().equals("codexQuestionDialog == dialog")).findFirst().orElseThrow();
        int pinnedStart=chat.indexOf("    private void updateCodexGoalPinnedView("),pinnedEnd=chat.indexOf("    /** Codex使用同一置顶容器",pinnedStart);
        if(pinnedStart<0||pinnedEnd<0)throw new AssertionError("原48dp提问入口尚未接入");
        String pinned=chat.substring(pinnedStart,pinnedEnd);
        int updateStart=chat.indexOf("    private void updatePinnedMessageView(boolean animated, int animateToNext)"),
                updateEnd=chat.indexOf("        int pinned_msg_id;",updateStart);
        String pinnedGuards=chat.substring(updateStart,updateEnd)+"}\n";
        int clickStart=chat.indexOf("        pinnedMessageView.setOnClickListener(v -> {"),
                clickEnd=chat.indexOf("            wasManualScroll = true;",clickStart);
        String pinnedClick=chat.substring(clickStart,clickEnd).replace("        pinnedMessageView.setOnClickListener(v -> {","void clickPinned(){")+"}\n";
        String entryMethods="";
        for(String signature:new String[]{"    private static boolean hasCodexPendingQuestion(","    private void openCodexPinnedEntry()"}) {
            int methodStart=chat.indexOf(signature);
            if(methodStart>=0) {
                int body=chat.indexOf('{',methodStart),depth=1,pos=body+1;
                for(;depth!=0;pos++){char c=chat.charAt(pos);if(c=='{')depth++;else if(c=='}')depth--;}
                entryMethods+=StaticJavaParser.parseMethodDeclaration(chat.substring(methodStart,pos))+"\n";
            }
        }
        Path temp=Files.createTempDirectory("codex-question-form");
        try{
            Path probe=temp.resolve("QuestionFormProbe.java"),runtime=temp.resolve("CodexRuntime.java");
            StringBuilder real=new StringBuilder();
            for(var method:methods.findAll(com.github.javaparser.ast.body.MethodDeclaration.class))real.append(method).append('\n');
            Files.writeString(probe,FIXTURE+real+pinned+pinnedGuards+pinnedClick+entryMethods+"/** 执行原最终提交与关闭监听，不重抄忙碌判断。 */ void finishSubmit(){AlertDialog dialog=codexQuestionDialog;int questionIndex=codexQuestionIndex;var answers=codexQuestionDrafts.get(codexQuestionRequest.identity());"+submitTail+"}\nvoid fireDismiss(AlertDialog dialog){"+dismiss+"}\n"+SCENARIOS+"}");Files.writeString(runtime,RUNTIME);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-cp",System.getProperty("java.class.path"),"-d",temp.toString(),
                    probe.toString(),runtime.toString(),source.resolve("com/butang/codextop/DesktopQuestion.java").toString(),
                    source.resolve("com/butang/codextop/SessionStatus.java").toString())!=0)
                throw new AssertionError("实际表单刷新方法编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},QuestionFormRefreshTest.class.getClassLoader())){
                try{loader.loadClass("com.butang.codextop.QuestionFormProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("实际表单生命周期回归失败",e.getCause());}
            }
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    // 仅记录原弹窗文字、按钮与关闭；没有替代产品判断或建立另一套表单算法。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import java.util.*;import com.google.gson.*;
        public final class QuestionFormProbe {
            static final class DialogInterface {static final int BUTTON_POSITIVE=1,BUTTON_NEUTRAL=2;}
            static class View {static final int VISIBLE=0,INVISIBLE=4,GONE=8;boolean enabled=true;int visibility;Object tag=1;
                void setEnabled(boolean v){enabled=v;}void setVisibility(int v){visibility=v;}void setTranslationX(float v){}void setTranslationY(float v){}
                Object getTag(){return tag;}void setTag(Object v){tag=v;}}
            static class TextView extends View {String text;void setText(CharSequence value){text=value.toString();}Paint getPaint(){return new Paint();}}
            static final class Paint {Object getFontMetricsInt(){return null;}}
            static final class Emoji {static CharSequence replaceEmoji(CharSequence text,Object metrics,boolean v){return text;}}
            static final class Animation {boolean cancelled;void cancel(){cancelled=true;}}
            static final class PinnedLine extends View {void set(int a,int b,boolean c){}}
            static final class ActionBar {boolean actionMode,search;boolean isActionModeShowed(){return actionMode;}boolean isSearchFieldVisible(){return search;}}
            static final class TopPanel {boolean visible;void setViewVisible(View view,boolean v,boolean animated){visible=v;}}
            static final class AlertDialog {boolean showing=true;String message="synthetic";final TextView next=new TextView(),previous=new TextView();
                boolean isShowing(){return showing;}void dismiss(){showing=false;}void setMessage(CharSequence s){message=s.toString();}View getButton(int id){return id==1?next:previous;}}
            AlertDialog codexQuestionDialog;CodexRuntime.QuestionReview codexQuestionReview;DesktopQuestion codexQuestionRequest;
            int codexQuestionIndex;boolean codexQuestionUsable=true,codexQuestionBusy,paused;long codexQuestionUiGeneration;
            final HashMap<String,LinkedHashMap<String,String>> codexQuestionDrafts=new HashMap<>();int submissions;
            long dialog_id=1;Object currentEncryptedChat;int chatMode;boolean report,setPinnedTextTranslationX;ActionBar actionBar=new ActionBar();
            View pinnedMessageView;View pinnedCounterTextView=new View();View[] pinnedMessageImageView={new View(),new View()},pinnedMessageButton={new View(),new View()};
            TextView[] pinnedNameTextView={new TextView(),new TextView()},pinnedMessageTextView={new TextView(),new TextView()};
            Animation[] pinnedNextAnimation={null,null};PinnedLine pinnedLineView=new PinnedLine();TopPanel topPanelLayout=new TopPanel();
            int created,paddingChecks,questionOpens,infoOpens;boolean isReport(){return report;}
            boolean hidePinnedMessageView(boolean animated){boolean changed=topPanelLayout.visible;topPanelLayout.visible=false;if(pinnedMessageView!=null)pinnedMessageView.setTag(1);return changed;}
            void checkListViewPaddings(){paddingChecks++;}void updatePinnedListButton(boolean v){}
            void createPinnedMessageView(){created++;pinnedMessageView=new View();}
            void showCodexQuestions(){questionOpens++;}void showCodexConversationInfo(){infoOpens++;}
            /** 提交RPC在Runtime真实方法测试覆盖；这里只标记原提交开始。 */
            void submitCodexQuestions(CodexRuntime.QuestionReview r,DesktopQuestion q,LinkedHashMap<String,String> a){submissions++;codexQuestionBusy=true;}
            void showCodexQuestion(CodexRuntime.QuestionReview r,DesktopQuestion q,int index){throw new AssertionError("unexpected next question");}
        """;
    private static final String RUNTIME="""
        package com.butang.codextop;
        import java.util.*;import java.util.function.*;
        public final class CodexRuntime {
            public static final class QuestionReview {public final ArrayList<DesktopQuestion> requests=new ArrayList<>();boolean current=true,issued;
                public boolean current(){return current;}public boolean alreadyIssued(DesktopQuestion r){return issued;}}
            static boolean changed;static int calls;static BiConsumer<QuestionReview,String> callback;
            static SessionStatus.Snapshot status;static boolean owns=true;
            public static SessionStatus.Snapshot status(long dialog){return status;}
            public static boolean ownsConversation(long dialog){return owns;}
            /** 仅提供是否有变化及完成时机，去重和网络行为在Runtime真实方法测试覆盖。 */
            public static boolean refreshQuestions(QuestionReview r,DesktopQuestion q,BiConsumer<QuestionReview,String> done){calls++;if(changed)callback=done;return changed;}
        }
        """;
    private static final String SCENARIOS="""
            /** 全部题目仅为合成样例，私密原答只放模型而不进入输入框。 */
            static DesktopQuestion request(String status,String answered)throws Exception{
                JsonObject root=new JsonObject(),r=new JsonObject();JsonArray items=new JsonArray(),questions=new JsonArray();root.addProperty("v",1);root.add("questions",items);items.add(r);
                r.addProperty("kind","async_questions");r.add("requestId",JsonNull.INSTANCE);r.addProperty("itemId","card");r.addProperty("turnId","turn");r.addProperty("revision","r1");
                r.addProperty("status",status);r.addProperty("canAnswer",status.equals("pending"));r.add("questions",questions);
                for(String id:new String[]{"a","b"}){JsonObject q=new JsonObject();q.addProperty("id",id);q.addProperty("question","synthetic");q.addProperty("isOther",true);q.add("options",new JsonArray());questions.add(q);}
                if(answered!=null){JsonObject answers=new JsonObject();JsonArray a=new JsonArray();a.add("confirmed");answers.add(answered,a);r.add("answers",answers);}
                return DesktopQuestion.read(root).get(0);
            }
            /** 创建原表单的最少字段，草稿与确认答案使用真实DesktopQuestion身份。 */
            static QuestionFormProbe form()throws Exception{CodexRuntime.changed=false;CodexRuntime.calls=0;CodexRuntime.callback=null;
                QuestionFormProbe p=new QuestionFormProbe();p.codexQuestionDialog=new AlertDialog();p.codexQuestionRequest=request("pending",null);
                p.codexQuestionReview=review(p.codexQuestionRequest);LinkedHashMap<String,String> draft=new LinkedHashMap<>();draft.put("a","draft-a");draft.put("b","draft-b");p.codexQuestionDrafts.put(p.codexQuestionRequest.identity(),draft);return p;}
            static CodexRuntime.QuestionReview review(DesktopQuestion q){var r=new CodexRuntime.QuestionReview();if(q!=null)r.requests.add(q);return r;}
            static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
            /** 使用真实STATUS解析与过期规则；绘制只读取既有快照，绝不模拟读题成功。 */
            static SessionStatus.Snapshot status(String state,String kind,boolean goal,boolean stale){
                JsonObject response=new JsonObject(),observation=new JsonObject();response.addProperty("ok",true);response.addProperty("machineOnline",true);response.add("observation",observation);
                observation.addProperty("v",1);observation.addProperty("state",state);observation.addProperty("source","desktop");observation.addProperty("turnId","synthetic-turn");
                if(state.equals("needs_input")){JsonArray requests=new JsonArray();observation.add("requests",requests);
                    if(kind.equals("question")||kind.equals("mixed")){JsonObject q=new JsonObject();q.addProperty("requestId","synthetic-question");q.addProperty("kind","user_action_request");requests.add(q);}
                    if(kind.equals("approval")||kind.equals("mixed")){JsonObject a=new JsonObject();a.addProperty("requestId","synthetic-approval");a.addProperty("kind","permission_request");requests.add(a);}}
                if(goal){JsonObject g=new JsonObject();response.add("goal",g);g.addProperty("availability","available");g.addProperty("source","desktop");g.addProperty("threadId","synthetic-thread");
                    g.addProperty("objective","synthetic goal");g.addProperty("status","active");g.add("tokenBudget",JsonNull.INSTANCE);g.add("tokensUsed",JsonNull.INSTANCE);g.add("timeUsedSeconds",JsonNull.INSTANCE);g.addProperty("updatedAt",100);}
                SessionStatus.Store store=new SessionStatus.Store();store.observation(1,"synthetic-machine","synthetic-thread",response,100,101);return store.get(1,stale?15100:101);
            }
            static QuestionFormProbe pinned(SessionStatus.Snapshot status){CodexRuntime.status=status;CodexRuntime.owns=true;return new QuestionFormProbe();}
            static void pinnedEntryScenarios(){
                int reads=CodexRuntime.calls;
                QuestionFormProbe p=pinned(status("needs_input","question",false,false));p.updatePinnedMessageView(false,0);
                check(p.topPanelLayout.visible&&p.created==1,"current question without goal has no visible entry");
                check(p.pinnedNameTextView[0].text.contains("需要你回答")&&p.pinnedNameTextView[0].text.contains("查看题目")
                        &&p.pinnedMessageTextView[0].text.contains("选项"),"question entry lacks a clear question/options action");
                check(p.questionOpens==0&&p.infoOpens==0&&CodexRuntime.calls==reads,"painting opened or read questions");
                p.clickPinned();check(p.questionOpens==1&&p.infoOpens==0,"pure question did not use original question action");
                p.updatePinnedMessageView(false,0);check(p.created==1,"unchanged question recreated pinned view");
                CodexRuntime.status=status("completed","none",false,false);p.updatePinnedMessageView(true,0);
                check(!p.topPanelLayout.visible,"resolved question left a stale entry visible");
                p=pinned(status("needs_input","mixed",false,false));p.updatePinnedMessageView(true,0);p.clickPinned();
                check(p.topPanelLayout.visible&&p.pinnedMessageTextView[0].text.contains("待批准")&&p.infoOpens==1&&p.questionOpens==0,"mixed pending hid approvals");
                p=pinned(status("needs_input","question",true,false));p.updatePinnedMessageView(false,0);p.clickPinned();
                check(p.topPanelLayout.visible&&p.pinnedNameTextView[0].text.contains("需要你回答")&&p.pinnedMessageTextView[0].text.contains("synthetic goal")
                        &&p.infoOpens==0&&p.questionOpens==1,"pure question with goal did not open its question directly");
                // 混合待办仍从会话信息分流，不能因同时有目标而隐藏审批入口。
                p=pinned(status("needs_input","mixed",true,false));p.updatePinnedMessageView(false,0);p.clickPinned();
                check(p.topPanelLayout.visible&&p.pinnedMessageTextView[0].text.contains("synthetic goal")
                        &&p.infoOpens==1&&p.questionOpens==0,"mixed pending with goal hid approvals");
                p=pinned(status("running","none",true,false));p.updatePinnedMessageView(false,0);p.clickPinned();
                check(p.topPanelLayout.visible&&p.pinnedNameTextView[0].text.equals("目标进行中")&&p.infoOpens==1,"plain goal changed original entry");
                p=pinned(status("needs_input","question",true,true));p.updatePinnedMessageView(false,0);p.clickPinned();
                check(p.topPanelLayout.visible&&p.pinnedNameTextView[0].text.startsWith("上次：")&&!p.pinnedNameTextView[0].text.contains("需要你回答")
                        &&p.infoOpens==1&&p.questionOpens==0,"stale goal created current question or lost readonly access");
                for(String kind:new String[]{"question","mixed","approval","unknown"}){
                    p=pinned(status("needs_input",kind,false,true));p.updatePinnedMessageView(false,0);p.clickPinned();
                    check(!p.topPanelLayout.visible&&p.created==0&&p.infoOpens==1&&p.questionOpens==0,"stale/unknown invented current question");
                }
                p=pinned(status("needs_input","approval",false,false));p.updatePinnedMessageView(false,0);check(!p.topPanelLayout.visible,"plain approval invented question");
                p=pinned(status("needs_input","question",false,false));p.updatePinnedMessageView(false,0);CodexRuntime.status=status("needs_input","question",false,true);p.clickPinned();
                check(p.questionOpens==0&&p.infoOpens==1,"click reused stale visible question without checking current status");
                for(int guard=0;guard<3;guard++){
                    p=pinned(status("needs_input","question",false,false));p.updatePinnedMessageView(false,0);
                    if(guard==0)p.report=true;else if(guard==1)p.actionBar.search=true;else p.actionBar.actionMode=true;
                    p.updatePinnedMessageView(false,0);check(!p.topPanelLayout.visible,"original report/search/actionMode hide guard changed");
                }
                for(int guard=0;guard<3;guard++){
                    p=pinned(status("needs_input","question",false,false));if(guard==0)p.currentEncryptedChat=new Object();else if(guard==1)p.chatMode=1;else CodexRuntime.owns=false;
                    p.updatePinnedMessageView(false,0);check(p.created==0,"original encrypted/chatMode/non-Codex routing changed");
                }
                check(CodexRuntime.calls==reads,"entry refresh introduced question polling");
            }
            /** 实际原表单更新、关闭和迟到守卫覆盖，不通过自制界面伪装实机验收。 */
            public static void main(String[] ignored)throws Exception{
                pinnedEntryScenarios();
                QuestionFormProbe p=form();check(!p.refreshCodexQuestion()&&!p.codexQuestionBusy,"unchanged form blocked");
                p.codexQuestionDialog=null;int calls=CodexRuntime.calls;check(!p.refreshCodexQuestion()&&calls==CodexRuntime.calls,"closed form read questions");
                p=form();p.paused=true;check(!p.refreshCodexQuestion()&&CodexRuntime.calls==0,"paused form read questions");
                p=form();CodexRuntime.changed=true;AlertDialog original=p.codexQuestionDialog;check(p.refreshCodexQuestion()&&!original.next.enabled,"inflight submit remains enabled");
                int before=CodexRuntime.calls;check(p.refreshCodexQuestion()&&CodexRuntime.calls==before,"inflight form duplicated refresh");
                var fresh=review(request("pending","b"));CodexRuntime.callback.accept(fresh,null);
                check(original.showing&&original.next.enabled&&"提交回答".equals(original.next.text)&&p.codexQuestionReview==fresh,"remaining form not updated in place");
                check("draft-a".equals(p.codexQuestionDrafts.get(p.codexQuestionRequest.identity()).get("a")),"unanswered draft lost");
                check("confirmed".equals(p.codexQuestionDrafts.get(p.codexQuestionRequest.identity()).get("b")),"confirmed answer did not replace draft");
                p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();CodexRuntime.callback.accept(review(request("pending","a")),null);
                check(!original.showing&&"draft-b".equals(p.codexQuestionDrafts.get(p.codexQuestionRequest.identity()).get("b")),"answered current question remained or other draft lost");
                check(codexUnansweredQuestionIndex(request("pending","a"),0,1)==1,"answered question not skipped");
                for(String status:new String[]{"answered","expired"}){p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();CodexRuntime.callback.accept(review(request(status,null)),null);check(!original.showing,"resolved form stayed open");}
                p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();CodexRuntime.callback.accept(review(null),null);check(!original.showing,"removed revision stayed open");
                p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();CodexRuntime.callback.accept(null,"暂时无法核对");
                check(original.showing&&!original.next.enabled&&!p.codexQuestionUsable&&original.message.contains("暂时无法核对"),"unknown pretended answered or allowed submit");
                CodexRuntime.changed=false;check(p.refreshCodexQuestion()&&!original.next.enabled,"unknown unchanged allowed next/submit");
                CodexRuntime.changed=true;p.refreshCodexQuestion();CodexRuntime.callback.accept(review(request("pending",null)),null);
                check(original.showing&&original.next.enabled&&p.codexQuestionUsable&&"draft-a".equals(p.codexQuestionDrafts.get(p.codexQuestionRequest.identity()).get("a")),"recovery lost draft or stayed disabled");
                p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();var issued=review(request("pending",null));issued.issued=true;CodexRuntime.callback.accept(issued,null);
                check(!original.showing&&p.codexQuestionDrafts.size()==1,"unknown issued request reopened or cleared draft");
                p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();p.codexQuestionReview.current=false;CodexRuntime.callback.accept(null,"connection changed");check(!original.showing,"old connection form stayed active");
                p=form();original=p.codexQuestionDialog;CodexRuntime.changed=true;p.refreshCodexQuestion();p.codexQuestionUiGeneration++;p.codexQuestionBusy=true;CodexRuntime.callback.accept(review(request("answered",null)),null);
                check(original.showing&&p.codexQuestionBusy,"late page changed new generation");
                p=form();CodexRuntime.changed=true;p.refreshCodexQuestion();var nextDialog=new AlertDialog();p.codexQuestionDialog=nextDialog;CodexRuntime.callback.accept(review(request("answered",null)),null);check(nextDialog.showing,"old callback closed new form");
                p=form();p.codexQuestionRequest=request("pending","b");p.codexQuestionReview=review(p.codexQuestionRequest);original=p.codexQuestionDialog;
                p.finishSubmit();check(p.submissions==1&&p.codexQuestionBusy,"original submission did not start");p.fireDismiss(original);
                check(p.codexQuestionBusy,"late dismiss cleared submission busy");
                p=form();original=p.codexQuestionDialog;p.codexQuestionBusy=true;p.fireDismiss(original);check(!p.codexQuestionBusy&&p.codexQuestionDialog==null,"ordinary close did not release refresh busy");
                System.out.println("QuestionFormRefresh: 实际入口/点击/守卫与原位答案/草稿、未知/失效、迟到回包及关闭不影响提交通过");
            }
        """;
}
