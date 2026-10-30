package com.butang.codextop;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * D-83 三期 A：来源身份与只读路由。前半段执行 SessionSources，后半段只读生产源码核对接入点，
 * 不依赖 gson／javaparser。
 * javac -d "$TEST_CLASSES" TMessagesProj/src/main/java/com/butang/codextop/SessionSources.java tests/codextop/SessionSourcesTest.java
 * java -cp "$TEST_CLASSES" com.butang.codextop.SessionSourcesTest
 */
public final class SessionSourcesTest {
    private static final String UI = "TMessagesProj/src/main/java/org/telegram/ui/";
    private static final String CODEX = "TMessagesProj/src/main/java/com/butang/codextop/";

    public static void main(String[] args) throws Exception {
        identities();
        wiring();
        System.out.println("SessionSources: 来源身份、请求路由、只读入口、列表合并、状态说明通过");
    }

    private static void identities() {
        check("abc".equals(SessionSources.localRemote("codex", "abc")), "Codex 身份不变");
        String claude = SessionSources.localRemote("claude", "abc");
        check(!claude.equals("abc"), "同编号的 Claude 会话与 Codex 会话身份不同");
        check(SessionSources.isClaude(claude) && !SessionSources.isClaude("abc"), "来源识别");
        check("abc".equals(SessionSources.nativeRemote(claude)) && "abc".equals(SessionSources.nativeRemote("abc")), "请求时还原原编号");
        check("claude".equals(SessionSources.provider(claude)) && "codex".equals(SessionSources.provider("abc")), "按来源填 providerId");
        check(!SessionSources.isClaude("claude:") && !SessionSources.isClaude(null), "空编号不算 Claude");
        check("CC".equals(SessionSources.mark(claude)) && "CX".equals(SessionSources.mark("abc")), "来源标记");
    }

    private static void wiring() throws Exception {
        String connection = read(CODEX + "DesktopConnection.java");
        check(connection.contains("return base(SessionSources.provider(remoteSessionId), remoteSessionId);"), "正文请求按来源路由");
        check(connection.contains("params.addProperty(\"remoteSessionId\", SessionSources.nativeRemote(remoteSessionId))"), "请求里写原编号");
        check(connection.contains("source.addProperty(\"kind\", \"claudeConfig\")") && !connection.contains("\"configDir\""),
                "Claude 来源只写类型，不指定电脑路径");

        String runtime = read(CODEX + "CodexRuntime.java");
        check(runtime.contains("if (!SessionSources.isClaude(remoteIds.get(dialogId))) watchStatus(account, dialogId, generation);"),
                "Claude 会话不关联、不订阅状态");
        check(runtime.contains("|| readOnlyConversation(params.peer)) return;"), "Claude 会话不发送");
        check(count(runtime, "if (readOnlyConversation(dialogId)) { callback.accept(null, SessionSources.CLAUDE_READ_ONLY); return; }") == 2,
                "Claude 会话不读提问与审批");
        check(count(runtime, "appendClaudeCandidates(") == 3, "当前电脑与其它电脑都并入 Claude 会话");
        String append = between(runtime, "private static void appendClaudeCandidates(", "\n    }\n");
        check(append.contains("catch (Exception error)"), "Claude 列表失败不影响 Codex 列表");

        String status = read(CODEX + "SessionStatus.java");
        String candidate = between(status, "private static Snapshot candidateSnapshot(", "JsonObject details");
        check(candidate.contains("unknown(\"unsupported\", SessionSources.CLAUDE_STATUS_UNSUPPORTED"), "Claude 状态未接通时明确未知");

        String chat = read(UI + "ChatActivity.java");
        String overlay = between(chat, "public void updateBottomOverlay(boolean animated) {", "getMessagesController().isFrozen()");
        check(overlay.contains("CodexRuntime.readOnlyConversation(dialog_id)") && overlay.contains("SessionSources.CLAUDE_READ_ONLY")
                && overlay.contains("bottomOverlayLinks = true;"), "聊天页输入框换成只读说明");

        String settings = read(UI + "SettingsActivity.java");
        check(settings.contains("\"Claude Code 额度\", \"暂无法读取\""), "Claude 额度写暂无法读取");
        check(settings.contains("至少保留一种来源"), "显示来源至少保留一种");
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        check(from >= 0, "找不到 " + start);
        int to = text.indexOf(end, from + start.length());
        check(to >= 0, "找不到 " + end);
        return text.substring(from, to);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
