package com.butang.codextop;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 独立抽取正式放置分支，复核日期服务项、同秒负编号及原生正编号优先边界。 */
public final class PendingPlacementReviewTest {
    /** 只在临时目录编译合成适配器；可传源码路径复核修改前的同一行为反例。 */
    public static void main(String[] args) throws Exception {
        Path sourcePath = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") : Path.of(args[0]);
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        String process = block(source, "private void processNewMessages(ArrayList<MessageObject> arr, final boolean animatedFromBottom)");
        String placement = block(process, "if (placeToPaste == -1)");
        Path scratch = Files.createTempDirectory("codex-pending-placement-review-");
        try {
            Path probe = scratch.resolve("PendingPlacementReviewProbe.java");
            Files.writeString(probe, FIXTURE + "\nstatic int place(MessageObject obj) { int placeToPaste=-1;\n"
                    + placement + "\nreturn placeToPaste;}\n" + SCENARIOS);
            Path runtime = scratch.resolve("CodexRuntime.java");
            Files.writeString(runtime, "package com.butang.codextop; public class CodexRuntime { public static boolean owned=true;"
                    + "/** 仅提供合成归属。 */ public static boolean ownsConversation(long dialog){return owned&&dialog==42L;} }");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", scratch.toString(),
                    probe.toString(), runtime.toString()) != 0) throw new AssertionError("正式放置分支合成编译失败");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{scratch.toUri().toURL()}, null)) {
                try { loader.loadClass("com.butang.codextop.PendingPlacementReviewProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]); }
                catch (InvocationTargetException error) { throw new AssertionError("正式放置分支复核失败", error.getCause()); }
            }
        } finally {
            try (var files = Files.walk(scratch)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
            if (!source.equals(Files.readString(sourcePath, StandardCharsets.UTF_8)))
                throw new AssertionError("合成复核期间生产源码已改变，需在冻结片上重跑");
        }
    }

    /** 词法提取唯一正式代码块，字符串和注释中的括号不影响边界。 */
    private static String block(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0 || source.indexOf(marker, start + marker.length()) >= 0) throw new AssertionError("正式分支不唯一：" + marker);
        int opening = source.indexOf('{', start), depth = 0;
        boolean string=false, character=false, line=false, comment=false, escape=false;
        for (int i=opening;i<source.length();i++) {
            char value=source.charAt(i), next=i+1<source.length()?source.charAt(i+1):0;
            if (line) {if(value=='\n')line=false;continue;}
            if (comment) {if(value=='*'&&next=='/'){comment=false;i++;}continue;}
            if (string||character) {
                if(escape)escape=false;else if(value=='\\')escape=true;
                else if(string&&value=='"')string=false;else if(character&&value=='\'')character=false;
                continue;
            }
            if(value=='/'&&next=='/'){line=true;i++;}else if(value=='/'&&next=='*'){comment=true;i++;}
            else if(value=='"')string=true;else if(value=='\'')character=true;
            else if(value=='{')depth++;else if(value=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("正式分支未闭合");
    }

    // 替身只承载消息字段和分组，放置算法全部来自正式源码。
    private static final String FIXTURE = """
        package com.butang.codextop;
        import java.util.*;
        public final class PendingPlacementReviewProbe {
            static final int MODE_DEFAULT=0,MODE_SCHEDULED=1;
            static int chatMode=MODE_DEFAULT,assertions,failures;
            static final long dialog_id=42L;
            static ArrayList<MessageObject> messages=new ArrayList<>();
            static Map<Long,MessageObject.GroupedMessages> groupedMessagesMap=new HashMap<>();
            static class Owner {int id,date;Map<String,String> params=new HashMap<>();}
            static class MessageObject {
                final Owner messageOwner=new Owner();
                boolean scheduled,quick,isDateObject;int type;long group;
                /** 只提供显式合成编号和时间。 */
                MessageObject(int id,int date,boolean restored){messageOwner.id=id;messageOwner.date=date;
                    if(restored)messageOwner.params.put("codexRestoredPending","true");}
                /** 返回用例声明的快捷回复属性。 */boolean isQuickReply(){return quick;}
                /** 返回用例声明的原分组身份。 */long getGroupId(){return group;}
                static class GroupedMessages {final ArrayList<MessageObject> messages=new ArrayList<>();}
            }
            /** 独立构造合成消息，不依赖任何设备、账号或正文。 */
            static MessageObject row(int id,int date,boolean restored){return new MessageObject(id,date,restored);}
            /** 日期服务项保持原type10、id0、当天00:00形状。 */
            static MessageObject separator(int midnight){MessageObject m=row(0,midnight,false);m.type=10;m.isDateObject=true;return m;}
            /** 显式记录各场景期待的下标，不重新计算排序条件。 */
            static void expect(String name,MessageObject incoming,int expected,MessageObject... current){
                assertions++;messages=new ArrayList<>(Arrays.asList(current));int actual=place(incoming);
                if(actual!=expected){failures++;System.out.println("FAIL "+name+" expected="+expected+" actual="+actual);}
                else System.out.println("PASS "+name+" index="+actual);
            }
        """;

    private static final String SCENARIOS = """
            /** 复核原生路径和恢复路径相邻边界，不将静态下标结果当作设备UI验收。 */
            public static void main(String[] args){
                MessageObject today=row(103,300,false), yesterday=row(102,200,false);
                expect("restored_old_after_latest",row(-10,100,true),2,today,yesterday);
                expect("restored_newer_can_lead",row(-30,400,true),0,today,yesterday);
                expect("restored_same_day_before_date_service",row(-10,280,true),1,today,separator(250),yesterday);
                expect("restored_at_midnight_before_date_service",row(-10,250,true),1,today,separator(250),yesterday);
                MessageObject ignored=row(0,200,false);ignored.type=-1;
                expect("negative_type_service_is_not_date_anchor",row(-10,150,true),1,ignored,row(101,100,false));
                expect("positive_id_native_priority_even_with_older_date",row(104,100,true),0,today,yesterday);
                expect("positive_same_second_native_insert_before",row(101,300,false),0,today,yesterday);
                expect("restored_same_second_older_negative_after",row(-10,300,true),1,row(-30,300,true),separator(250));
                expect("restored_same_second_newer_negative_before",row(-40,300,true),0,row(-30,300,true),separator(250));
                expect("restored_equal_positive_retains_native_tie",row(-10,300,true),0,today,separator(250));
                expect("fresh_negative_keeps_native_fast_path",row(-10,100,false),0,today,yesterday);
                CodexRuntime.owned=false;
                expect("telegram_marker_cannot_activate_codex_path",row(-10,100,true),0,today,yesterday);
                CodexRuntime.owned=true;
                MessageObject grouped1=row(100,100,false),grouped2=row(101,100,false);
                grouped1.group=grouped2.group=77;
                MessageObject.GroupedMessages group=new MessageObject.GroupedMessages();
                group.messages.add(grouped1);group.messages.add(grouped2);groupedMessagesMap.put(77L,group);
                expect("native_group_boundary_not_split",row(-10,150,true),1,grouped1,grouped2,separator(50));
                System.out.println("PendingPlacementReview: total="+assertions+" failures="+failures+"; synthetic source-branch checks only");
                if(failures!=0)throw new AssertionError("独立正式分支失败="+failures);
            }
        }
        """;
}
