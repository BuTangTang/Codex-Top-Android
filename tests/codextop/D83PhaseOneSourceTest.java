package com.butang.codextop;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * D-83 一期修正的源码核对：只读生产源码文本，不依赖 javaparser／gson。
 * javac -d "$TEST_CLASSES" tests/codextop/D83PhaseOneSourceTest.java
 * java -cp "$TEST_CLASSES" com.butang.codextop.D83PhaseOneSourceTest
 */
public final class D83PhaseOneSourceTest {
    private static final String UI = "TMessagesProj/src/main/java/org/telegram/ui/";
    private static final String CODEX = "TMessagesProj/src/main/java/com/butang/codextop/";

    public static void main(String[] args) throws Exception {
        String header = read(UI + "Components/ChatAvatarContainer.java");
        check(header.contains("setSubtitle(status.label + \" · \" + (TextUtils.isEmpty(source.machineName) ? \"电脑未知\" : source.machineName));"),
                "T15 聊天页状态行用 status.label 原文与电脑名");

        String cell = read(UI + "Cells/DialogCell.java");
        String event = between(cell, "public void onPopulateAccessibilityEvent(", "event.setContentDescription(sb);");
        check(event.contains("sb.append(com.butang.codextop.CodexRuntime.status(currentDialogId).label)"),
                "T14 会话行读屏描述含 status.label");

        String settings = read(UI + "SettingsActivity.java");
        check(!settings.contains("UItem.asCheck(52,"), "T16 一期不显示“多台电脑先分电脑”");
        check(settings.contains("item.id == 52 ? !a.byMachine : a.byMachine"), "T16 其它开关保存时原值照传");
        check(settings.contains("(offline(row) ? \"上次 · 最近 \" : \"最近 \")"), "T17 电脑卡计数前加“最近”");

        String adapter = read(UI + "Adapters/DialogsAdapter.java");
        String arranged = between(adapter, "if (!stopUpdate && codexArranged()) {", "} else if (!stopUpdate) {");
        String empty = between(arranged, "if (dialogsCount == 0) {", "} else {");
        check(arranged.contains("\"显示每台电脑最近 \" + com.butang.codextop.CodexRuntime.recentDialogLimit() + \" 条会话\""),
                "T17 列表末尾说明行取 recentDialogLimit");
        check(!empty.contains("VIEW_TYPE_CODEX_FOOTER"), "T17 空列表不加说明行");

        String runtime = read(CODEX + "CodexRuntime.java");
        check(runtime.contains("DialogArrangement.pendingIdentity(value.turnId, value.questionIds, value.approvalIds)"),
                "T12 摘要标识含审批编号");
        String pinned = between(runtime, "public static String pinnedPendingText(", "if (!arrangementSettings().pinPending)");
        check(pinned.contains("pendingSummaries.remove(dialogId);"), "T12 离开待处理清掉摘要");
        String entries = between(runtime, "private static ArrayList<DialogArrangement.Entry> arrangementEntries()", "return entries;");
        check(entries.contains("dialogUpdatedAtMs.getOrDefault(dialog.id"), "T13 分组用毫秒时间");
        System.out.println("D83PhaseOneSource: 状态行、读屏描述、隐藏开关、最近说明、摘要标识、毫秒时间通过");
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
