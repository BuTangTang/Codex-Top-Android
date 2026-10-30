package com.butang.codextop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行原表单的选择恢复和选项点击，证明旧isOther=false也可选择自行填写。 */
public final class QuestionCustomInputTest {
    /** 只截取实际表单的无Android决策和点击代码；界面对象仅模拟事件边界。 */
    public static void main(String[] args)throws Exception {
        Path root=Path.of("TMessagesProj/src/main/java");String source=Files.readString(root.resolve("org/telegram/ui/ChatActivity.java"));
        int start=source.indexOf("        String selected = answers.get(question.id);"),end=source.indexOf("        LinearLayout content =",start);
        String selection=source.substring(start,end);
        int field=source.indexOf("        EditTextBoldCursor field =",end),fieldEnd=source.indexOf('\n',field);
        int click=source.indexOf("        for (int i = 0; i < cells.size(); i++) {",fieldEnd),clickEnd=source.indexOf("        String title =",click);
        String body=selection+source.substring(field,fieldEnd)+"\nif(field==null)throw new AssertionError(\"每题必须有自行填写输入框\");\n"
                +"ArrayList<Cell> cells=new ArrayList<>();for(int i=0;i<=customIndex;i++)cells.add(new Cell());\n"
                +source.substring(click,clickEnd)+"return new Form(field,cells,selectedIndex[0],customIndex);";
        if(!source.substring(fieldEnd,click).contains("if (question.isSecret) field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());"))throw new AssertionError("保密输入保护丢失");
        Path temp=Files.createTempDirectory("question-custom-input");
        try {
            Path probe=temp.resolve("QuestionCustomProbe.java");Files.writeString(probe,FIXTURE+"Form open(DesktopQuestion request,DesktopQuestion.Question question){long generation=codexQuestionUiGeneration;"+body+"}\n"+SCENARIOS+"}");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-cp",System.getProperty("java.class.path"),"-d",temp.toString(),probe.toString(),root.resolve("com/butang/codextop/DesktopQuestion.java").toString())!=0)throw new AssertionError("原自填分支编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},QuestionCustomInputTest.class.getClassLoader())){
                try{loader.loadClass("com.butang.codextop.QuestionCustomProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("自行填写回归失败",e.getCause());}
            }
        } finally {try(var files=Files.walk(temp)){for(Path p:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    private static final String FIXTURE="""
        package com.butang.codextop;
        import java.util.*;import com.google.gson.*;
        public class QuestionCustomProbe {
            HashMap<String,String> answers=new HashMap<>();long codexQuestionUiGeneration;boolean paused;
            Object getParentActivity(){return this;}
            static class View{static final int VISIBLE=0,GONE=8;}
            static class EditTextBoldCursor {String text="";int visibility;EditTextBoldCursor(Object a){}String getText(){return text;}void setVisibility(int v){visibility=v;}void requestFocus(){}}
            static class AndroidUtilities {static void showKeyboard(Object f){}static void hideKeyboard(Object f){}}
            static class Cell {java.util.function.Consumer<Object> click;void setChecked(boolean v,boolean a){}void setOnClickListener(java.util.function.Consumer<Object> c){click=c;}}
            record Form(EditTextBoldCursor field,ArrayList<Cell> cells,int selected,int custom){}
            static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
            static DesktopQuestion request(boolean options,boolean other,boolean secret)throws Exception {
                JsonObject r=new JsonObject();r.addProperty("kind","user_input");r.addProperty("requestId","request");r.addProperty("itemId","item");r.addProperty("turnId","turn");r.addProperty("revision","1");r.addProperty("status","pending");r.addProperty("canAnswer",true);
                JsonArray qs=new JsonArray();r.add("questions",qs);JsonObject q=new JsonObject();q.addProperty("id","q");q.addProperty("question","synthetic");q.addProperty("isOther",other);q.addProperty("isSecret",secret);qs.add(q);
                JsonArray os=new JsonArray();q.add("options",os);if(options){JsonObject o=new JsonObject();o.addProperty("label","choice");os.add(o);}
                JsonObject root=new JsonObject();root.addProperty("v",1);JsonArray rs=new JsonArray();rs.add(r);root.add("questions",rs);return DesktopQuestion.read(root).get(0);
            }
        """;
    private static final String SCENARIOS="""
            /** 原选项、自填草稿和无选项首开分别执行；输入不会发送，只有既有提交按钮才提交。 */
            public static void main(String[] ignored)throws Exception {
                for(boolean options:new boolean[]{true,false})for(boolean other:new boolean[]{false,true})for(boolean secret:new boolean[]{false,true}){
                    var r=request(options,other,secret);var p=new QuestionCustomProbe();var f=p.open(r,r.questions.get(0));
                    check(f.selected==(options?-1:f.custom),"首开误选或无选项未直接输入");
                    f.field.text="自填第一行\\n第二行";f.cells.get(f.custom).click.accept(null);
                    check(p.answers.get("q").equals(f.field.text)&&f.field.visibility==View.VISIBLE,"自行填写未原样写入当前题草稿");
                    var restored=p.open(r,r.questions.get(0));check(restored.selected==restored.custom,"自填草稿恢复成原选项");
                    if(options){f.cells.get(0).click.accept(null);check(p.answers.get("q").equals("choice")&&f.field.visibility==View.GONE,"切回原选项失败");check(p.open(r,r.questions.get(0)).selected==0,"原选项草稿未恢复");}
                    String before=p.answers.get("q");p.paused=true;f.field.text="late";f.cells.get(f.custom).click.accept(null);check(before.equals(p.answers.get("q")),"离页点击改写草稿");
                }
                System.out.println("QuestionCustomInput: 有/无选项、isOther两值、保密标记、原样自填/选项切换、草稿恢复与离页守卫通过");
            }
        """;
}
