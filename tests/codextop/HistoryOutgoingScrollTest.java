package com.butang.codextop;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import javax.tools.ToolProvider;

/** 抽取实际新消息出站资格、非尾部本机发送和滚动分支；不代替安卓窗口及真实同步验收。 */
public final class HistoryOutgoingScrollTest {
    /** 可传冻结基线源码执行 RED；当前源码执行 GREEN，所有消息与视图反馈均为合成样例。 */
    public static void main(String[] args) throws Exception {
        Path sourcePath = args.length == 0
                ? Path.of("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") : Path.of(args[0]);
        byte[] original = Files.readAllBytes(sourcePath);
        String source = new String(original, java.nio.charset.StandardCharsets.UTF_8);
        String outgoing = branch(source, "if (obj.isOut() && !(obj.messageOwner.action instanceof TLRPC.TL_messageActionTodoCompletions");
        String early = branch(source, "if (obj.isOut() && obj.wasJustSent) {");
        String scroll = branch(source, "if (lastVisible == 0 && diff <= AndroidUtilities.dp(5) && !hasDraftsReplaces || hasFromMe) {");
        Path temporary = Files.createTempDirectory("codex-history-outgoing-scroll-");
        try {
            Path probe = temporary.resolve("OutgoingScrollProbe.java");
            Files.writeString(probe, FIXTURE + execution(outgoing, early, scroll) + SCENARIOS);
            Path runtime = temporary.resolve("CodexRuntime.java");
            Files.writeString(runtime, "package com.butang.codextop; public class CodexRuntime {public static boolean owned; /** 仅表示合成会话归属，不读取账号。 */ public static boolean ownsConversation(long id){return owned && id == 42L;}}");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temporary.toString(), probe.toString(), runtime.toString()) != 0)
                throw new AssertionError("实际滚动分支夹具编译失败");
            System.out.println("ChatActivity SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, null)) {
                try { loader.loadClass("com.butang.codextop.OutgoingScrollProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (InvocationTargetException error) { throw new AssertionError("实际历史出站滚动分支回归失败", error.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
            if (!Arrays.equals(original, Files.readAllBytes(sourcePath))) throw new AssertionError("验收期间生产源码发生变化");
        }
    }

    /** 只定位唯一实际分支；词法状态跳过字符串及注释，不复制业务条件。 */
    private static String branch(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0 || source.indexOf(marker, start + marker.length()) >= 0)
            throw new AssertionError("实际分支不存在或不唯一，请重新核对边界");
        int open = source.indexOf('{', start), depth = 0;
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
            if (c == '/' && next == '/') { line = true; i++; }
            else if (c == '/' && next == '*') { block = true; i++; }
            else if (c == '"') string = true; else if (c == '\'') character = true;
            else if (c == '{') depth++; else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("实际分支未闭合");
    }

    /** 替身只提供队列批次、消息属性和滚动反馈，三个判定及原调用直接来自正式源码。 */
    private static String execution(String outgoing, String early, String scroll) {
        return """
            /** 执行实际资格和滚动分支，批内消息均代表已通过原去重的新入列项。 */
            static void execute(Case c) {
                com.butang.codextop.CodexRuntime.owned = c.codex;
                final long dialog_id = 42L;
                boolean hasFromMe = false, firstLoading = c.firstLoading, paused = c.paused;
                boolean scrollToTopOnResume = false, forceScrollToTop = false;
                int newUnreadMessageCount = 1, chatMode = c.chatMode;
                int lastVisible = c.atBottom ? 0 : 8, diff = 0;
                boolean hasDraftsReplaces = false;
                if (!c.forwardEndReached) {
                    for (Message obj : c.messages) {
            """ + early + """
                    }
                } else {
                    for (Message obj : c.messages) {
            """ + outgoing + """
                    }
                    if (!c.isAd) {
            """ + scroll + """
                    }
                }
                actual.queued = scrollToTopOnResume;
                actual.forced = forceScrollToTop;
            }
            """;
    }

    // 仅替代消息属性和视图反馈；没有账号、网络、真实正文或第二套滚动资格算法。
    private static final String FIXTURE = """

package com.butang.codextop;
import java.util.*;

/** 本夹具只执行抽取的原滚动分支；安卓视图和消息属性以合成适配器表示。 */
public class OutgoingScrollProbe {
    static final int MODE_DEFAULT = 0, MODE_SCHEDULED = 1;
    static Result actual;
    static class TLRPC {
        static class TL_messageActionTodoCompletions {}
        static class TL_messageActionTodoAppendTasks {}
    }
    static class AndroidUtilities { /** 固定密度，隔离平台尺寸。 */ static int dp(int n) { return n; } }
    static class Owner {
        Object action;
        boolean from_scheduled;
        /** 提供原排期和待办属性。 */
        Owner(Object action, boolean scheduled) { this.action = action; this.from_scheduled = scheduled; }
    }
    static class Message {
        final boolean outgoing, wasJustSent;
        final Owner messageOwner;
        /** 构造独立合成消息，不读取正文或账号。 */
        Message(boolean outgoing, boolean justSent, boolean scheduled, Object action) {
            this.outgoing = outgoing; this.wasJustSent = justSent;
            this.messageOwner = new Owner(action, scheduled);
        }
        /** 返回合成发送方向。 */
        boolean isOut() { return outgoing; }
    }
    static class Result {
        int moves, early;
        boolean queued, unread, info, forced;
        /** 初始化可观察的原调用反馈。 */
        Result() {}
        /** 明确每项用例期待的反馈，不复制业务判断。 */
        Result(int moves, int early, boolean queued, boolean cleared, boolean forced) {
            this.moves = moves; this.early = early; this.queued = queued;
            this.unread = cleared; this.info = cleared; this.forced = forced;
        }
        /** 只输出固定反馈字段，不输出消息内容。 */
        public String toString() {
            return "move=" + moves + " early=" + early + " queued=" + queued
                + " unread=" + unread + " info=" + info + " force=" + forced;
        }
    }
    static class Case {
        String name; boolean codex, atBottom;
        Message[] messages;
        Result expected;
        boolean forwardEndReached = true, firstLoading, paused, isAd;
        int chatMode = MODE_DEFAULT;
        /** 声明归属、批次、阅读位置与期待结果。 */
        Case(String name, boolean codex, Message[] messages, boolean bottom, Result expected) {
            this.name = name; this.codex = codex; this.messages = messages;
            this.atBottom = bottom; this.expected = expected;
        }
        /** 模拟原聊天尚未加载至尾部。 */
        Case notAtTail() { forwardEndReached = false; return this; }
        /** 模拟首屏仍在加载。 */
        Case loading() { firstLoading = true; return this; }
        /** 模拟页面暂停后的原延后滚动。 */
        Case paused() { paused = true; return this; }
        /** 保留原广告分支的滚动守卫。 */
        Case ad() { isAd = true; return this; }
        /** 切到原排期消息页面。 */
        Case scheduledMode() { chatMode = MODE_SCHEDULED; return this; }
    }
    /** 提供历史或本机刚发送的本人消息。 */
    static Message out(boolean sent) { return new Message(true, sent, false, null); }
    /** 提供新入站消息。 */
    static Message incoming() { return new Message(false, false, false, null); }
    /** 提供已排期发送来源属性。 */
    static Message scheduled() { return new Message(true, true, true, null); }
    /** 提供原待办完成动作。 */
    static Message todoComplete() { return new Message(true, true, false, new TLRPC.TL_messageActionTodoCompletions()); }
    /** 提供原待办追加动作。 */
    static Message todoAppend() { return new Message(true, true, false, new TLRPC.TL_messageActionTodoAppendTasks()); }
    /** 保留真实批次顺序，核验累计资格。 */
    static Message[] batch(Message... messages) { return messages; }
    /** 用例显式指定期待的原调用反馈。 */
    static Result E(int moves, int early, boolean queued, boolean cleared, boolean forced) {
        return new Result(moves, early, queued, cleared, forced);
    }
    /** 记录原未读平面处理，没有真实视图。 */
    static void removeUnreadPlane(boolean scrollToEnd) { actual.unread = true; }
    /** 记录原提示隐藏调用。 */
    static void hideInfoView() { actual.info = true; }
    /** 记录原尾部滚动调用。 */
    static void moveScrollToLastMessage(boolean animated) { actual.moves++; }
    /** 记录本机刚发送的原非尾部早分支。 */
    static void scrollToLastMessage(boolean skipSponsored, boolean top) { actual.early++; }

        """;
    private static final String SCENARIOS = """

    /** 执行所有边界并报告全部失败，不能把编译失败当作 RED。 */
    public static void main(String[] args) {
        List<Case> cases = List.of(
            new Case("codex_old_outgoing_reading_away", true, batch(out(false)), false, E(0,0,false,true,false)),
            new Case("codex_catchup_outgoing_plus_new_incoming", true, batch(out(false), incoming()), false, E(0,0,false,true,false)),
            new Case("codex_new_incoming_reading_away", true, batch(incoming()), false, E(0,0,false,false,false)),
            new Case("codex_new_incoming_follow_at_bottom", true, batch(incoming()), true, E(1,0,false,false,true)),
            new Case("codex_local_just_sent_at_tail", true, batch(out(true)), false, E(1,0,false,true,true)),
            new Case("codex_local_send_then_old_outgoing_keep_batch_scroll", true, batch(out(true), out(false)), false, E(1,0,false,true,true)),
            new Case("codex_old_outgoing_then_local_send_keep_batch_scroll", true, batch(out(false), out(true)), false, E(1,0,false,true,true)),
            new Case("codex_local_just_sent_not_at_tail", true, batch(out(true)), false, E(0,1,false,false,false)).notAtTail(),
            new Case("telegram_old_outgoing_original_scroll", false, batch(out(false)), false, E(1,0,false,true,true)),
            new Case("telegram_new_incoming_reading_away", false, batch(incoming()), false, E(0,0,false,false,false)),
            new Case("telegram_local_just_sent_not_at_tail", false, batch(out(true)), false, E(0,1,false,false,false)).notAtTail(),
            new Case("codex_from_scheduled_guard", true, batch(scheduled()), false, E(0,0,false,false,false)),
            new Case("telegram_from_scheduled_guard", false, batch(scheduled()), false, E(0,0,false,false,false)),
            new Case("codex_todo_complete_guard", true, batch(todoComplete()), false, E(0,0,false,false,false)),
            new Case("codex_todo_append_guard", true, batch(todoAppend()), false, E(0,0,false,false,false)),
            new Case("telegram_todo_complete_guard", false, batch(todoComplete()), false, E(0,0,false,false,false)),
            new Case("telegram_todo_append_guard", false, batch(todoAppend()), false, E(0,0,false,false,false)),
            new Case("codex_scheduled_chat_mode_guard", true, batch(out(true)), false, E(0,0,false,true,false)).scheduledMode(),
            new Case("codex_initial_loading_guard", true, batch(out(true)), false, E(0,0,false,true,false)).loading(),
            new Case("codex_paused_local_send_deferred_scroll", true, batch(out(true)), false, E(0,0,true,true,false)).paused(),
            new Case("codex_ad_scroll_guard", true, batch(out(true)), false, E(0,0,false,true,false)).ad(),
            new Case("codex_catchup_at_bottom_keep_normal_follow", true, batch(out(false), incoming()), true, E(1,0,false,true,true))
        );
        int failures = 0;
        for (Case c : cases) {
            actual = new Result();
            execute(c);
            boolean pass = actual.toString().equals(c.expected.toString());
            if (!pass) failures++;
            System.out.println((pass ? "PASS " : "FAIL ") + c.name
                + " expected=[" + c.expected + "] actual=[" + actual + "]");
        }
        System.out.println("HistoryOutgoingScroll: total=" + cases.size() + " failures=" + failures);
        if (failures != 0) throw new AssertionError("实际原分支失败数=" + failures);
    }
}

        """;
}
