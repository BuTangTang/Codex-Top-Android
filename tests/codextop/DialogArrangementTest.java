package com.butang.codextop;

import com.butang.codextop.DialogArrangement.Category;
import com.butang.codextop.DialogArrangement.Entry;
import com.butang.codextop.DialogArrangement.Row;
import com.butang.codextop.DialogArrangement.RowType;
import com.butang.codextop.DialogArrangement.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** D-83 分组模型：只用合成会话，不读取手机、账号或真实工作区。 */
public final class DialogArrangementTest {
    private static final Settings PROJECT = new Settings(false, true, true, true);

    public static void main(String[] args) {
        categories();
        names();
        singleProject();
        multipleProjectsOrder();
        unlinked();
        allFinishedCollapsed();
        finishedFold();
        pinning();
        byTime();
        sameSecond();
        pendingIdentity();
        machines();
        windowsCase();
        System.out.println("DialogArrangement: 状态映射、项目命名、未关联、折叠、置顶、按时间、同秒毫秒、摘要标识、多电脑及离线、Windows 路径通过");
    }

    private static void categories() {
        check(DialogArrangement.category("current", "running") == Category.RUNNING, "运行");
        check(DialogArrangement.category("current", "needs_input") == Category.PENDING, "待处理");
        check(DialogArrangement.category("current", "failed") == Category.ERROR, "出错");
        check(DialogArrangement.category("current", "completed") == Category.FINISHED, "完成");
        check(DialogArrangement.category("current", "cancelled") == Category.FINISHED, "取消");
        check(DialogArrangement.category("stale", "running") == Category.UNKNOWN, "过期运行不能当运行");
        check(DialogArrangement.category("unavailable", "completed") == Category.UNKNOWN, "离线完成不能当完成");
        check(DialogArrangement.category("current", "mystery") == Category.UNKNOWN, "未知取值");
        check("待审批".equals(DialogArrangement.tag(Category.PENDING, "approval")), "审批标签");
        check("待处理".equals(DialogArrangement.tag(Category.PENDING, "question")), "提问标签");
        check("出错".equals(DialogArrangement.tag(Category.ERROR, "none")), "出错标签");
        check(DialogArrangement.tag(Category.RUNNING, "none") == null, "运行无标签");
    }

    private static void names() {
        Map<String, String> names = DialogArrangement.projectNames(Arrays.asList(
                "/work/a/app", "/work/b/app", "/work/solo", "/x/a/app", "", "/work/solo/"));
        check("solo".equals(names.get("/work/solo")), "唯一名称只取最后一级");
        check("solo".equals(names.get("/work/solo/")), "尾部分隔符同一目录");
        check("b/app".equals(names.get("/work/b/app")), "重名补上级");
        check("work/a/app".equals(names.get("/work/a/app")) && "x/a/app".equals(names.get("/x/a/app")),
                "补一级仍重名继续补");
        check(DialogArrangement.UNLINKED.equals(names.get("")), "空目录未关联");
        check("/".equals(DialogArrangement.projectNames(Arrays.asList("/")).get("/")), "根目录");
    }

    private static void singleProject() {
        List<Row> rows = DialogArrangement.arrange(Arrays.asList(
                entry(1, "/w/app", Category.RUNNING, 300), entry(2, "/w/app", Category.UNKNOWN, 200)), PROJECT, null);
        check(types(rows).equals("PROJECT_HEADER DIALOG DIALOG"), "单项目结构 " + types(rows));
        check("app".equals(rows.get(0).title) && rows.get(0).expanded, "单项目展开");
        check("1 运行 · 1 未知 · 2 会话".equals(rows.get(0).summary), "摘要 " + rows.get(0).summary);
        check(rows.get(1).dialogId == 1 && rows.get(1).context == null, "组内按时间且无前缀");
    }

    private static void multipleProjectsOrder() {
        List<Row> rows = DialogArrangement.arrange(Arrays.asList(
                entry(1, "/w/old", Category.RUNNING, 100), entry(2, "/w/new", Category.RUNNING, 500),
                entry(3, "/w/old", Category.RUNNING, 400)), PROJECT, null);
        check("new".equals(rows.get(0).title) && "old".equals(rows.get(2).title), "项目按最近活动");
        check(rows.get(3).dialogId == 3 && rows.get(4).dialogId == 1, "组内按时间");
        List<Row> tie = DialogArrangement.arrange(Arrays.asList(
                entry(5, "/w/p", Category.RUNNING, 100, "b"), entry(6, "/w/p", Category.RUNNING, 100, "a")), PROJECT, null);
        check(tie.get(1).dialogId == 6, "同时间按远端身份稳定");
    }

    private static void unlinked() {
        List<Row> rows = DialogArrangement.arrange(Arrays.asList(
                entry(1, "", Category.RUNNING, 100), entry(2, null, Category.RUNNING, 90),
                entry(3, "relative/path", Category.RUNNING, 80)), PROJECT, null);
        check(types(rows).equals("PROJECT_HEADER DIALOG DIALOG DIALOG"), "无效目录合并为一组");
        check(DialogArrangement.UNLINKED.equals(rows.get(0).title), "未关联项目名称");
    }

    private static void allFinishedCollapsed() {
        List<Entry> entries = Arrays.asList(entry(1, "/w/done", Category.FINISHED, 100),
                entry(2, "/w/done", Category.FINISHED, 90));
        List<Row> rows = DialogArrangement.arrange(entries, PROJECT, null);
        check(types(rows).equals("PROJECT_HEADER") && !rows.get(0).expanded, "全结束整组折叠");
        check("已结束 2".equals(rows.get(0).summary), "全结束摘要 " + rows.get(0).summary);
        Map<String, Boolean> open = new HashMap<>();
        open.put(rows.get(0).key, true);
        rows = DialogArrangement.arrange(entries, PROJECT, open);
        check(types(rows).equals("PROJECT_HEADER DIALOG DIALOG"), "全结束展开后直接列出 " + types(rows));
    }

    private static void finishedFold() {
        List<Entry> entries = Arrays.asList(entry(1, "/w/p", Category.FINISHED, 300),
                entry(2, "/w/p", Category.RUNNING, 200), entry(3, "/w/p", Category.FINISHED, 100));
        List<Row> rows = DialogArrangement.arrange(entries, PROJECT, null);
        check(types(rows).equals("PROJECT_HEADER DIALOG FINISHED_FOLD"), "已结束折叠 " + types(rows));
        check("已结束 2".equals(rows.get(2).title) && !rows.get(2).expanded, "折叠行计数");
        Map<String, Boolean> open = new HashMap<>();
        open.put(rows.get(2).key, true);
        rows = DialogArrangement.arrange(entries, PROJECT, open);
        check(types(rows).equals("PROJECT_HEADER DIALOG FINISHED_FOLD DIALOG DIALOG"), "折叠行展开");
        rows = DialogArrangement.arrange(entries, new Settings(false, true, false, true), null);
        check(types(rows).equals("PROJECT_HEADER DIALOG DIALOG DIALOG") && rows.get(1).dialogId == 1,
                "关闭折叠后按时间混排");
        Map<String, Boolean> closed = new HashMap<>();
        closed.put(rows.get(0).key, false);
        rows = DialogArrangement.arrange(entries, PROJECT, closed);
        check(types(rows).equals("PROJECT_HEADER"), "手动折叠有运行的组");
    }

    private static void pinning() {
        List<Entry> entries = Arrays.asList(entry(1, "/w/a", Category.PENDING, 100),
                entry(2, "/w/b", Category.ERROR, 300), entry(3, "/w/a", Category.FINISHED, 200),
                entry(4, "/w/c", Category.PENDING, 50));
        List<Row> rows = DialogArrangement.arrange(entries, PROJECT, null);
        check(types(rows).startsWith("PENDING_HEADER DIALOG DIALOG DIALOG PROJECT_HEADER"), "置顶区 " + types(rows));
        check(rows.get(1).dialogId == 2 && "b".equals(rows.get(1).context), "置顶按时间并写项目名");
        check(rows.get(0).count == 3, "置顶计数");
        check(types(rows).equals("PENDING_HEADER DIALOG DIALOG DIALOG PROJECT_HEADER"), "全被置顶的项目不留空组");
        check("a".equals(rows.get(4).title) && !rows.get(4).expanded, "剩余全结束默认折叠");
        check("1 待处理 · 2 会话".equals(rows.get(4).summary), "摘要含置顶会话 " + rows.get(4).summary);
        rows = DialogArrangement.arrange(entries, new Settings(false, true, true, false), null);
        check(!types(rows).contains("PENDING_HEADER"), "关闭置顶");
        check(rows.get(0).expanded, "待处理留在组内时展开");
    }

    private static void byTime() {
        List<Entry> entries = Arrays.asList(entry(1, "/w/a", Category.FINISHED, 100),
                entry(2, "/w/b", Category.RUNNING, 300), entry(3, "/w/a", Category.PENDING, 200));
        List<Row> rows = DialogArrangement.arrange(entries, new Settings(true, true, true, true), null);
        check(types(rows).equals("PENDING_HEADER DIALOG DIALOG DIALOG"), "按时间结构 " + types(rows));
        check(rows.get(2).dialogId == 2 && rows.get(3).dialogId == 1, "按时间排序");
        check("b".equals(rows.get(2).context), "按时间第二行带项目名");
        rows = DialogArrangement.arrange(entries, new Settings(true, true, true, false), null);
        check(types(rows).equals("DIALOG DIALOG DIALOG") && rows.get(1).dialogId == 3, "按时间不置顶");
    }

    /** R4：同一秒内按毫秒先后，远端编号只在毫秒也相同时决定顺序。 */
    private static void sameSecond() {
        List<Entry> entries = Arrays.asList(entry(1, "/w/a", Category.RUNNING, 1_000_100, "a"),
                entry(2, "/w/b", Category.RUNNING, 1_000_900, "z"));
        List<Row> rows = DialogArrangement.arrange(entries, new Settings(true, true, true, true), null);
        check(rows.get(0).dialogId == 2 && rows.get(1).dialogId == 1, "同秒较新的在前");
        entries = Arrays.asList(entry(1, "/w/a", Category.RUNNING, 1_000_500, "z"),
                entry(2, "/w/b", Category.RUNNING, 1_000_500, "a"));
        rows = DialogArrangement.arrange(entries, new Settings(true, true, true, true), null);
        check(rows.get(0).dialogId == 2, "毫秒相同按远端编号");
    }

    /** B1：同一轮换了审批或题目，摘要标识都要变；同一请求不变。 */
    private static void pendingIdentity() {
        java.util.Set<String> none = java.util.Collections.emptySet();
        String first = DialogArrangement.pendingIdentity("t", none, java.util.Set.of("a1"));
        check(!first.equals(DialogArrangement.pendingIdentity("t", none, java.util.Set.of("a2"))), "同轮换审批失效");
        check(first.equals(DialogArrangement.pendingIdentity("t", none, new java.util.HashSet<>(java.util.Set.of("a1")))), "同一审批不失效");
        check(!DialogArrangement.pendingIdentity("t", java.util.Set.of("q1"), none)
                .equals(DialogArrangement.pendingIdentity("t", java.util.Set.of("q2"), none)), "换题失效");
        check(!DialogArrangement.pendingIdentity("t", java.util.Set.of("x"), none)
                .equals(DialogArrangement.pendingIdentity("t", none, java.util.Set.of("x"))), "提问与审批同编号不混淆");
        check(DialogArrangement.pendingIdentity("t", java.util.Set.of("q2", "q1"), java.util.Set.of("b", "a"))
                .equals(DialogArrangement.pendingIdentity("t", java.util.Set.of("q1", "q2"), java.util.Set.of("a", "b"))), "顺序无关");
    }

    private static void machines() {
        List<Entry> entries = new ArrayList<>(Arrays.asList(
                new Entry(1, "m1", "Mac", true, -1, "/w/app", "codex", Category.RUNNING, 100, "r1"),
                new Entry(2, "m2", "PC", true, -1, "C:/w/app", "codex", Category.RUNNING, 300, "r2"),
                new Entry(3, "m3", "Old", false, 4242, "/w/x", "codex", Category.UNKNOWN, 900, "r3"),
                new Entry(4, "m3", "Old", false, 4242, "/w/y", "codex", Category.UNKNOWN, 800, "r4")));
        List<Row> rows = DialogArrangement.arrange(entries, PROJECT, null);
        check(types(rows).equals("MACHINE_HEADER PROJECT_HEADER DIALOG MACHINE_HEADER PROJECT_HEADER DIALOG OFFLINE_MACHINE"),
                "先分电脑、离线在后 " + types(rows));
        check("PC".equals(rows.get(0).title) && "app".equals(rows.get(1).title), "同名项目在不同电脑分开且不补上级");
        Row offline = rows.get(6);
        check("Old".equals(offline.title) && offline.count == 2 && offline.lastSeenMs == 4242, "离线电脑行");
        rows = DialogArrangement.arrange(entries, new Settings(false, false, true, true), null);
        check("x · Old".equals(rows.get(0).title) && titles(rows).contains("app · PC") && titles(rows).contains("app · Mac"),
                "不分电脑时项目按活动合排、组名补电脑名 " + titles(rows));
        rows = DialogArrangement.arrange(entries, new Settings(true, true, true, true), null);
        check("x · Old".equals(rows.get(0).context), "按时间多电脑前缀");
        List<Row> single = DialogArrangement.arrange(Arrays.asList(
                new Entry(5, "m3", "Old", false, 1, "/w/x", "codex", Category.UNKNOWN, 1, "r5")), PROJECT, null);
        check(types(single).equals("PROJECT_HEADER DIALOG"), "只有一台电脑时不加电脑层，离线也列出缓存");
    }

    private static void windowsCase() {
        List<Row> rows = DialogArrangement.arrange(Arrays.asList(
                entry(1, "C:\\Work\\App", Category.RUNNING, 100), entry(2, "c:/work/app/", Category.RUNNING, 90)), PROJECT, null);
        check(types(rows).equals("PROJECT_HEADER DIALOG DIALOG") && "App".equals(rows.get(0).title), "Windows 同目录合组并保留原大小写");
        List<Row> posix = DialogArrangement.arrange(Arrays.asList(
                entry(1, "/w/App", Category.RUNNING, 100), entry(2, "/w/app", Category.RUNNING, 90)), PROJECT, null);
        check(types(posix).equals("PROJECT_HEADER DIALOG PROJECT_HEADER DIALOG"), "POSIX 大小写不同是不同目录");
        check(!posix.get(0).title.equals(posix.get(2).title), "大小写不同的名称仍可区分");
    }

    private static Entry entry(long id, String directory, Category category, long time) {
        return entry(id, directory, category, time, "r" + id);
    }

    private static Entry entry(long id, String directory, Category category, long time, String remote) {
        return new Entry(id, "m1", "Mac", true, -1, directory, "codex", category, time, remote);
    }

    private static List<String> titles(List<Row> rows) {
        List<String> titles = new ArrayList<>();
        for (Row row : rows) if (row.type == RowType.PROJECT_HEADER) titles.add(row.title);
        return titles;
    }

    private static String types(List<Row> rows) {
        StringBuilder text = new StringBuilder();
        for (Row row : rows) text.append(text.length() == 0 ? "" : " ").append(row.type);
        return text.toString();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
