package com.butang.codextop;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 提取真实目标详情方法，使用真实SessionStatus和日期格式器；不读取手机缓存或启动Android。 */
public final class GoalInfoFormattingTest {
    /** 可传入冻结源码核验旧实现RED；生产片段按Java 8编译，临时目录退出即清理。 */
    public static void main(String[] args) throws Exception {
        Path sourcePath=args.length==0?Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"):Path.of(args[0]);
        String source=Files.readString(sourcePath),methods="";
        for(String name:new String[]{"codexGoalInfo","codexGoalDuration","codexGoalUpdatedAt"}) {
            String method=RuntimeGoalStatusTest.method(source,name);
            if(method!=null)methods+=method+"\n";
        }
        Path temporary=Files.createTempDirectory("codex-goal-info-");
        try {
            Path probe=temporary.resolve("GoalInfoFormattingProbe.java");
            Files.writeString(probe,FIXTURE+methods+SCENARIOS+"\n}");
            Path locale=temporary.resolve("LocaleController.java");
            Files.writeString(locale,LOCALE);
            var compile=new ArrayList<String>(java.util.List.of("--release","8","-cp",System.getProperty("java.class.path"),"-d",temporary.toString(),probe.toString(),locale.toString(),"TMessagesProj/src/main/java/com/butang/codextop/SessionStatus.java"));
            for(String name:new String[]{"DateParser","DatePrinter","FastDateFormat","FastDateParser","FastDatePrinter","FormatCache"})
                compile.add("TMessagesProj/src/main/java/org/telegram/messenger/time/"+name+".java");
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))!=0)
                throw new AssertionError("真实目标详情Java 8片段编译失败");
            try(var loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},GoalInfoFormattingTest.class.getClassLoader())) {
                try{loader.loadClass("com.butang.codextop.GoalInfoFormattingProbe").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("实际目标详情格式回归失败",e.getCause());}
            }
        } finally {try(var paths=Files.walk(temporary)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }

    // 只替代Android locale配置，格式化仍执行仓库实际FastDateFormat。
    private static final String LOCALE="""
        package org.telegram.messenger;
        import java.util.*;
        import org.telegram.messenger.time.FastDateFormat;
        public final class LocaleController {
            private static final LocaleController INSTANCE=new LocaleController();
            public static boolean broken;public static FastDateFormat formatter;
            public static LocaleController getInstance(){return INSTANCE;}
            public FastDateFormat getFormatterStats(){if(broken)throw new IllegalStateException("合成格式器异常");return formatter;}
            public static void configure(String zone,boolean twelveHour){broken=false;formatter=FastDateFormat.getInstance(twelveHour?"MMM dd yyyy, h:mm a":"MMM dd yyyy, HH:mm",TimeZone.getTimeZone(zone),Locale.US);}
        }
        """;

    // STATUS输入与目标正文全为合成，状态解析和失效沿真实owner。
    private static final String FIXTURE="""
        package com.butang.codextop;
        import com.google.gson.*;
        import java.util.Calendar;
        import org.telegram.messenger.LocaleController;
        public final class GoalInfoFormattingProbe {
            static int passed,failed;
            static void scenario(String name,Runnable action){try{action.run();passed++;}catch(Throwable failure){failed++;System.out.println("RED "+name+": "+failure.getMessage());}}
            static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
            static JsonObject response(Long budget,Long used,Long seconds,long updatedAt) {
                JsonObject goal=new JsonObject();goal.addProperty("availability","available");goal.addProperty("source","desktop");goal.addProperty("threadId","remote");goal.addProperty("objective","合成目标👋\\n保持原文");goal.addProperty("status","active");goal.add("tokenBudget",budget==null?JsonNull.INSTANCE:new JsonPrimitive(budget));goal.add("tokensUsed",used==null?JsonNull.INSTANCE:new JsonPrimitive(used));goal.add("timeUsedSeconds",seconds==null?JsonNull.INSTANCE:new JsonPrimitive(seconds));goal.addProperty("updatedAt",updatedAt);
                JsonObject response=JsonParser.parseString("{\\"ok\\":true,\\"machineOnline\\":true,\\"observation\\":{\\"v\\":1,\\"state\\":\\"unknown\\",\\"reason\\":\\"missing_turn_id\\"}}").getAsJsonObject();response.add("goal",goal);return response;
            }
            static SessionStatus.Store store(Long budget,Long used,Long seconds,long updatedAt){SessionStatus.Store store=new SessionStatus.Store();store.observation(7,"machine","remote",response(budget,used,seconds,updatedAt),100,101);return store;}
            static String info(Long budget,Long used,Long seconds,long updatedAt){return codexGoalInfo(store(budget,used,seconds,updatedAt).get(7,101).goal);}
            static String duration(Long value){try{return (String)GoalInfoFormattingProbe.class.getDeclaredMethod("codexGoalDuration",Long.class).invoke(null,value);}catch(ReflectiveOperationException failure){throw new AssertionError("缺少可执行时长helper",failure);}}
            static String updatedAt(long value){try{return (String)GoalInfoFormattingProbe.class.getDeclaredMethod("codexGoalUpdatedAt",long.class).invoke(null,value);}catch(ReflectiveOperationException failure){throw new AssertionError("缺少可执行日期helper",failure);}}
        """;

    private static final String SCENARIOS="""
            /** 固定时区/格式和来源秒数，防止测试被当前墙钟或用户手机语言影响。 */
            public static void main(String[] args) {
                LocaleController.configure("Asia/Shanghai",false);
                scenario("来源epoch秒转本地日期",()->check(info(null,9328441L,37766L,1790919932L).contains("来源更新时间：Oct 02 2026, 13:45"),"仍是原始秒数或时区错误"));
                scenario("小时分钟秒",()->check(info(null,9328441L,37766L,1790919932L).contains("已用时间：10小时29分钟26秒"),"仍是原始秒数或组件错误"));
                scenario("空预算不推断无限",()->{String info=info(null,null,null,0);check(info.contains("Token预算：未知")&&info.contains("已用Token：未知")&&info.contains("已用时间：未知")&&!info.contains("无限"),"空值被制造成数值或无限");});
                scenario("零值独立于未知",()->{String info=info(0L,0L,0L,0);check(info.contains("Token预算：0")&&info.contains("已用Token：0")&&info.contains("已用时间：0秒")&&info.contains("来源更新时间：Jan 01 1970, 08:00"),"零值被吞或epoch零失真");});
                scenario("current不标上次",()->check(!info(0L,0L,0L,0).contains("目标信息：上次同步"),"当前值错误标记上次同步"));
                scenario("stale明确来源",()->{SessionStatus.Goal goal=store(null,9328441L,37766L,1790919932L).get(7,15100).goal;String info=codexGoalInfo(goal);check(info.contains("目标信息：上次同步")&&info.contains(goal.label)&&info.contains("已用Token：9328441"),"陈旧目标来源不清或丢失原值");});
                scenario("unknown保留上次目标",()->{SessionStatus.Store store=store(null,9328441L,37766L,1790919932L);JsonObject response=JsonParser.parseString("{\\"ok\\":true,\\"machineOnline\\":true,\\"goal\\":{\\"availability\\":\\"unknown\\"}}").getAsJsonObject();store.observation(7,"machine","remote",response,200,201);String info=codexGoalInfo(store.get(7,201).goal);check(info.contains("目标状态：上次：目标进行中 · 目标未知")&&info.contains("目标信息：上次同步")&&info.contains("目标：合成目标👋\\n保持原文"),"上次值被当成当前或原正文改变");});
                scenario("无目标不补计数",()->{SessionStatus.Store store=new SessionStatus.Store();String info=codexGoalInfo(store.get(7,101).goal);check(!info.contains("Token")&&!info.contains("已用时间")&&!info.contains("来源更新时间"),"无值时伪造详情");});
                scenario("UTC沿本地格式器",()->{LocaleController.configure("UTC",false);check(updatedAt(1790919932L).equals("Oct 02 2026, 05:45"),"未沿格式器时区");});
                scenario("12小时沿既有偏好",()->{LocaleController.configure("America/Los_Angeles",true);check(updatedAt(1790919932L).equals("Oct 01 2026, 10:45 PM"),"忽略日期/12小时偏好");});
                scenario("分钟边界",()->check(duration(60L).equals("1分钟")&&duration(61L).equals("1分钟1秒")&&duration(3599L).equals("59分钟59秒"),"分钟余数错误"));
                scenario("小时边界",()->check(duration(3600L).equals("1小时")&&duration(3601L).equals("1小时1秒")&&duration(3660L).equals("1小时1分钟"),"小时余数错误"));
                scenario("超int不截断",()->check(duration(2147483648L).equals("596523小时14分钟8秒"),"经过int截断"));
                scenario("long最大不溢出",()->check(duration(Long.MAX_VALUE).equals("2562047788015215小时30分钟7秒"),"long时长溢出"));
                scenario("无效时长保护",()->check(duration(null).equals("未知")&&duration(-1L).equals("未知"),"无效时长未保护"));
                scenario("epoch秒乘法溢出保护",()->check(updatedAt(Long.MAX_VALUE).equals("未知")&&updatedAt(9007199254740991L).equals("未知"),"异常epoch溢出或输出怪日期"));
                scenario("日期范围保护",()->{LocaleController.configure("UTC",false);check(updatedAt(-1).equals("未知")&&updatedAt(253402300800L).equals("未知")&&updatedAt(253402300799L).equals("Dec 31 9999, 23:59"),"日期边界错误");});
                scenario("本地年越界保护",()->{LocaleController.configure("Pacific/Kiritimati",false);check(updatedAt(253402300799L).equals("未知"),"本地日期超过四位年");});
                scenario("日期格式器异常保护",()->{LocaleController.broken=true;check(updatedAt(1790919932L).equals("未知"),"格式器异常泄漏到详情");});
                System.out.println("GoalInfoFormatting: "+passed+" passed, "+failed+" failed；真实详情/SessionStatus/FastDateFormat，未代替Android实拍");
                if(failed!=0)throw new AssertionError("目标详情格式有"+failed+"例失败");
            }
        """;
}
