package com.butang.codextop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** D-83 会话页分组：只按已有字段排列，不推测项目、不改状态；纯 Java，供界面线程和 JVM 测试共用。 */
public final class DialogArrangement {
    public static final String UNLINKED = "未关联项目";
    public static final String PENDING_TITLE = "需要你处理";

    /** 由 SessionStatus 映射的展示类别；只有当前事实才进入前四类以外的明确状态。 */
    public enum Category { PENDING, ERROR, RUNNING, UNKNOWN, FINISHED }

    public enum RowType { PENDING_HEADER, MACHINE_HEADER, OFFLINE_MACHINE, PROJECT_HEADER, DIALOG, FINISHED_FOLD }

    /** 一条会话的展示事实；目录为空表示没有工作区，时间缺失用 -1。 */
    public static final class Entry {
        public final long dialogId, updatedAtMs, machineLastSeenMs;
        public final String machineId, machineName, directory, source, remoteId;
        public final boolean machineOnline;
        public final Category category;

        public Entry(long dialogId, String machineId, String machineName, boolean machineOnline, long machineLastSeenMs,
                String directory, String source, Category category, long updatedAtMs, String remoteId) {
            this.dialogId = dialogId; this.machineId = text(machineId); this.machineName = text(machineName);
            this.machineOnline = machineOnline; this.machineLastSeenMs = machineLastSeenMs;
            this.directory = text(directory); this.source = text(source); this.category = category;
            this.updatedAtMs = updatedAtMs; this.remoteId = text(remoteId);
        }
    }

    /** “我的 → 会话排列”的四个选项。 */
    public static final class Settings {
        public final boolean byTime, byMachine, collapseFinished, pinPending;

        public Settings(boolean byTime, boolean byMachine, boolean collapseFinished, boolean pinPending) {
            this.byTime = byTime; this.byMachine = byMachine;
            this.collapseFinished = collapseFinished; this.pinPending = pinPending;
        }
    }

    /** 列表的一行；标题行用 key 做稳定身份，会话行另带所属项目／电脑的说明。 */
    public static final class Row {
        public final RowType type;
        public final String key, title, summary, context;
        public final long dialogId, lastSeenMs;
        public final int count;
        public final boolean expanded;

        private Row(RowType type, String key, String title, String summary, String context,
                long dialogId, long lastSeenMs, int count, boolean expanded) {
            this.type = type; this.key = key; this.title = title; this.summary = summary; this.context = context;
            this.dialogId = dialogId; this.lastSeenMs = lastSeenMs; this.count = count; this.expanded = expanded;
        }

        /** 供列表差异比较：同一行的可见内容是否变化。 */
        public String presentation() {
            return type + "|" + key + "|" + title + "|" + summary + "|" + context + "|" + count + "|" + expanded + "|" + lastSeenMs;
        }
    }

    private static final class Project {
        final String key, machineId, machineName;
        String name;
        final List<Entry> all = new ArrayList<>();
        long latest = Long.MIN_VALUE;
        Project(String key, String machineId, String machineName) {
            this.key = key; this.machineId = machineId; this.machineName = machineName;
        }
    }

    private DialogArrangement() { }

    /** 只有 validity=current 才采用 state；其余一律状态未知，不显示成运行或完成。 */
    public static Category category(String validity, String state) {
        if (!"current".equals(validity) || state == null) return Category.UNKNOWN;
        switch (state) {
            case "running": return Category.RUNNING;
            case "needs_input": return Category.PENDING;
            case "failed": return Category.ERROR;
            case "completed": case "cancelled": return Category.FINISHED;
            default: return Category.UNKNOWN;
        }
    }

    /** 时间上方的小标签；运行、未知、已结束不加。 */
    public static String tag(Category category, String pendingKind) {
        if (category == Category.ERROR) return "出错";
        if (category == Category.PENDING) return "approval".equals(pendingKind) ? "待审批" : "待处理";
        return null;
    }

    /** 置顶摘要的有效期标识：同一轮内提问或审批任一编号变化，旧摘要都不能沿用。 */
    public static String pendingIdentity(String turnId, java.util.Set<String> questionIds, java.util.Set<String> approvalIds) {
        return text(turnId) + "\n" + new java.util.TreeSet<>(questionIds) + "\n" + new java.util.TreeSet<>(approvalIds);
    }

    /** 分组键：电脑加规范化目录；没有可用目录的会话归入该电脑的“未关联项目”。 */
    public static String projectKey(String machineId, String directory) {
        String path = ProjectPaths.key(directory);
        return "p:" + text(machineId) + "\n" + (path == null ? "" : path);
    }

    /** 同一台电脑上为每个目录取最短可区分的名称：先取最后一级，重名再逐级补上级。 */
    public static Map<String, String> projectNames(List<String> directories) {
        Map<String, List<String>> segments = new LinkedHashMap<>();
        Map<String, String> result = new HashMap<>();
        for (String directory : directories) {
            String key = ProjectPaths.key(directory);
            if (key == null) { result.put(directory, UNLINKED); continue; }
            if (segments.containsKey(key)) continue;
            String normalized = ProjectPaths.normalize(directory);
            List<String> parts = new ArrayList<>();
            for (String part : normalized.split("/")) if (!part.isEmpty()) parts.add(part);
            if (parts.isEmpty()) parts.add(normalized);
            segments.put(key, parts);
        }
        Map<String, Integer> levels = new HashMap<>();
        for (String key : segments.keySet()) levels.put(key, 1);
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, List<String>> byName = new HashMap<>();
            for (String key : segments.keySet())
                byName.computeIfAbsent(nameAt(segments.get(key), levels.get(key)).toLowerCase(java.util.Locale.ROOT),
                        ignored -> new ArrayList<>()).add(key);
            for (List<String> same : byName.values()) {
                if (same.size() < 2) continue;
                for (String key : same) {
                    if (levels.get(key) < segments.get(key).size()) { levels.put(key, levels.get(key) + 1); changed = true; }
                }
            }
        }
        for (String directory : directories) {
            String key = ProjectPaths.key(directory);
            if (key != null) result.put(directory, nameAt(segments.get(key), levels.get(key)));
        }
        return result;
    }

    /** 生成整页行；overrides 只保存用户手动展开或折叠过的键。 */
    public static List<Row> arrange(List<Entry> input, Settings settings, Map<String, Boolean> overrides) {
        List<Entry> entries = new ArrayList<>(input);
        entries.sort(BY_TIME);
        Map<String, Boolean> manual = overrides == null ? Collections.emptyMap() : overrides;
        Map<String, Project> projects = projects(entries);
        Map<String, String> machineNames = new LinkedHashMap<>();
        for (Entry entry : entries) machineNames.putIfAbsent(entry.machineId, entry.machineName);
        boolean multiMachine = machineNames.size() > 1;

        List<Row> rows = new ArrayList<>();
        List<Entry> pinned = new ArrayList<>();
        if (settings.pinPending) for (Entry entry : entries)
            if (entry.category == Category.PENDING || entry.category == Category.ERROR) pinned.add(entry);
        if (!pinned.isEmpty()) {
            rows.add(new Row(RowType.PENDING_HEADER, "h:pending", PENDING_TITLE, null, null, 0, -1, pinned.size(), true));
            for (Entry entry : pinned) rows.add(dialog(entry, context(projects, entry, multiMachine, true)));
        }
        if (settings.byTime) {
            for (Entry entry : entries)
                if (!pinned.contains(entry)) rows.add(dialog(entry, context(projects, entry, multiMachine, true)));
            return rows;
        }

        List<Project> ordered = new ArrayList<>(projects.values());
        ordered.sort(BY_ACTIVITY);
        if (multiMachine && settings.byMachine) {
            for (String machine : machineOrder(entries)) {
                Entry sample = firstOf(entries, machine);
                List<Entry> ofMachine = new ArrayList<>();
                for (Entry entry : entries) if (entry.machineId.equals(machine)) ofMachine.add(entry);
                if (!sample.machineOnline) {
                    rows.add(new Row(RowType.OFFLINE_MACHINE, "m:" + machine, sample.machineName, null, null,
                            0, sample.machineLastSeenMs, ofMachine.size(), false));
                    continue;
                }
                rows.add(new Row(RowType.MACHINE_HEADER, "m:" + machine, sample.machineName, null, null,
                        0, -1, ofMachine.size(), true));
                for (Project project : ordered)
                    if (project.machineId.equals(machine)) addProject(rows, project, pinned, settings, manual, false);
            }
        } else {
            for (Project project : ordered) addProject(rows, project, pinned, settings, manual, multiMachine);
        }
        return rows;
    }

    /** 项目组：组内无未结束会话时默认折叠；已结束在展开后再合并成一行。 */
    private static void addProject(List<Row> rows, Project project, List<Entry> pinned, Settings settings,
            Map<String, Boolean> manual, boolean withMachine) {
        List<Entry> open = new ArrayList<>(), finished = new ArrayList<>();
        for (Entry entry : project.all) {
            if (pinned.contains(entry)) continue;
            (entry.category == Category.FINISHED ? finished : open).add(entry);
        }
        if (open.isEmpty() && finished.isEmpty()) return;
        Boolean override = manual.get(project.key);
        boolean expanded = override != null ? override : !open.isEmpty();
        String title = withMachine && !project.machineName.isEmpty() ? project.name + " · " + project.machineName : project.name;
        rows.add(new Row(RowType.PROJECT_HEADER, project.key, title, summary(project.all), null,
                0, -1, project.all.size(), expanded));
        if (!expanded) return;
        if (!settings.collapseFinished || open.isEmpty() || finished.isEmpty()) {
            List<Entry> visible = new ArrayList<>(open);
            visible.addAll(finished);
            visible.sort(BY_TIME);
            for (Entry entry : visible) rows.add(dialog(entry, null));
            return;
        }
        for (Entry entry : open) rows.add(dialog(entry, null));
        String foldKey = "f:" + project.key;
        Boolean foldOverride = manual.get(foldKey);
        boolean foldExpanded = foldOverride != null && foldOverride;
        rows.add(new Row(RowType.FINISHED_FOLD, foldKey, "已结束 " + finished.size(), null, null,
                0, -1, finished.size(), foldExpanded));
        if (foldExpanded) for (Entry entry : finished) rows.add(dialog(entry, null));
    }

    /** 摘要统计整个项目（含已置顶），只列非零项，最后是会话数；全部结束时写“已结束 N”。 */
    static String summary(List<Entry> all) {
        int pending = 0, error = 0, running = 0, unknown = 0;
        for (Entry entry : all) {
            switch (entry.category) {
                case PENDING: pending++; break;
                case ERROR: error++; break;
                case RUNNING: running++; break;
                case UNKNOWN: unknown++; break;
                default: break;
            }
        }
        // 全部结束的项目只写“已结束 N”，与设计图一致。
        if (pending + error + running + unknown == 0) return "已结束 " + all.size();
        StringBuilder text = new StringBuilder();
        if (pending > 0) text.append(pending).append(" 待处理 · ");
        if (error > 0) text.append(error).append(" 出错 · ");
        if (running > 0) text.append(running).append(" 运行 · ");
        if (unknown > 0) text.append(unknown).append(" 未知 · ");
        return text.append(all.size()).append(" 会话").toString();
    }

    private static Map<String, Project> projects(List<Entry> entries) {
        Map<String, Project> projects = new LinkedHashMap<>();
        Map<String, List<String>> directoriesByMachine = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String key = projectKey(entry.machineId, entry.directory);
            Project project = projects.computeIfAbsent(key, ignored -> new Project(key, entry.machineId, entry.machineName));
            project.all.add(entry);
            project.latest = Math.max(project.latest, entry.updatedAtMs);
            directoriesByMachine.computeIfAbsent(entry.machineId, ignored -> new ArrayList<>()).add(entry.directory);
        }
        Map<String, Map<String, String>> names = new HashMap<>();
        for (Map.Entry<String, List<String>> machine : directoriesByMachine.entrySet())
            names.put(machine.getKey(), projectNames(machine.getValue()));
        for (Project project : projects.values()) {
            Entry sample = project.all.get(0);
            String name = names.get(project.machineId).get(sample.directory);
            project.name = name == null ? UNLINKED : name;
        }
        return projects;
    }

    private static String context(Map<String, Project> projects, Entry entry, boolean multiMachine, boolean wanted) {
        if (!wanted) return null;
        String name = projects.get(projectKey(entry.machineId, entry.directory)).name;
        return multiMachine && !entry.machineName.isEmpty() ? name + " · " + entry.machineName : name;
    }

    private static Row dialog(Entry entry, String context) {
        return new Row(RowType.DIALOG, "d:" + entry.dialogId, null, null, context, entry.dialogId, -1, 0, true);
    }

    /** 在线电脑在前，各自按最近活动排序。 */
    private static List<String> machineOrder(List<Entry> entries) {
        Map<String, Entry> latest = new LinkedHashMap<>();
        for (Entry entry : entries) latest.putIfAbsent(entry.machineId, entry);
        List<Entry> samples = new ArrayList<>(latest.values());
        samples.sort((a, b) -> {
            if (a.machineOnline != b.machineOnline) return a.machineOnline ? -1 : 1;
            int time = Long.compare(b.updatedAtMs, a.updatedAtMs);
            return time != 0 ? time : a.machineId.compareTo(b.machineId);
        });
        List<String> order = new ArrayList<>();
        for (Entry entry : samples) order.add(entry.machineId);
        return order;
    }

    private static Entry firstOf(List<Entry> entries, String machine) {
        for (Entry entry : entries) if (entry.machineId.equals(machine)) return entry;
        throw new IllegalStateException();
    }

    private static String nameAt(List<String> parts, int level) {
        int from = Math.max(0, parts.size() - level);
        return String.join("/", parts.subList(from, parts.size()));
    }

    private static String text(String value) { return value == null ? "" : value; }

    /** 最近活动在上；时间相同时用远端身份稳定排序，避免行来回跳。 */
    private static final Comparator<Entry> BY_TIME = (a, b) -> {
        int time = Long.compare(b.updatedAtMs, a.updatedAtMs);
        return time != 0 ? time : a.remoteId.compareTo(b.remoteId);
    };

    private static final Comparator<Project> BY_ACTIVITY = (a, b) -> {
        int time = Long.compare(b.latest, a.latest);
        return time != 0 ? time : a.key.compareTo(b.key);
    };
}
