package com.butang.codextop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/** 列表候选和当前聊天共用的展示事实 owner；不授予发送、提问或审批能力。 */
public final class SessionStatus {
    // 沿用原聊天状态的15秒有效期；不随列表周期延长，慢请求也消耗该窗口。
    public static final long FRESHNESS_MS = 15000;

    /** 原STATUS目标事实，空值与零值分开；来源时间只展示，手机有效期使用原请求起点。 */
    public static final class Goal {
        public final String availability, validity, source, threadId, objective, status, label;
        public final Long tokenBudget, tokensUsed, timeUsedSeconds;
        public final long updatedAt, startedAtElapsedMs;

        /** 保存协议原值及该值的请求起点，不制造进度、无限预算或本机计时。 */
        private Goal(String availability, String validity, String source, String threadId, String objective,
                String status, Long tokenBudget, Long tokensUsed, Long timeUsedSeconds, long updatedAt,
                long startedAtElapsedMs, String label) {
            this.availability=availability; this.validity=validity; this.source=source; this.threadId=threadId;
            this.objective=objective; this.status=status; this.tokenBudget=tokenBudget; this.tokensUsed=tokensUsed;
            this.timeUsedSeconds=timeUsedSeconds; this.updatedAt=updatedAt; this.startedAtElapsedMs=startedAtElapsedMs;
            this.label=label;
        }

        /** 上次目标仍可只读查看，但必须同时显示失效原因；不会刷新原目标时刻。 */
        private Goal invalid(String availability, String validity, String reason) {
            String previous=goalLabel(status);
            return new Goal(availability,validity,source,threadId,objective,status,tokenBudget,tokensUsed,
                    timeUsedSeconds,updatedAt,startedAtElapsedMs,previous==null?reason:"上次："+previous+" · "+reason);
        }

        /** 是否实际保留来源目标，与生命周期是否正在运行无关。 */
        public boolean hasValue() { return !objective.isEmpty(); }
    }

    /** UI 只读快照；只有 validity=current 才能把 state 当作当前事实。时间缺失使用 -1。 */
    public static final class Snapshot {
        public final String state, pendingKind, validity, source, turnId, label;
        public final long eventAtMs, checkedAtMs, observedAtElapsedMs;
        public final java.util.Set<String> questionIds;
        public final Goal goal;

        /** 保留来源事实与本机接收时刻，来源时钟不与手机墙钟比较。 */
        private Snapshot(String state, String pendingKind, String validity, String source, String turnId,
                long eventAtMs, long checkedAtMs, long observedAtElapsedMs, String label, java.util.Set<String> questionIds) {
            this(state,pendingKind,validity,source,turnId,eventAtMs,checkedAtMs,observedAtElapsedMs,label,questionIds,
                    emptyGoal("unsupported","unsupported","目标信息暂不可用"));
        }

        /** 生命周期和目标分别失效，LIST不会借自己的请求起点延长目标。 */
        private Snapshot(String state, String pendingKind, String validity, String source, String turnId,
                long eventAtMs, long checkedAtMs, long observedAtElapsedMs, String label,
                java.util.Set<String> questionIds, Goal goal) {
            this.goal=goal;
            this.state = state; this.pendingKind = pendingKind; this.validity = validity;
            this.source = source; this.turnId = turnId; this.label = label;
            this.eventAtMs = eventAtMs; this.checkedAtMs = checkedAtMs;
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.questionIds = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(questionIds));
        }

        /** 失效时明确标注已验证的上次事实和本次原因，不续有效期或重复叠加前缀。 */
        private Snapshot invalid(String validity, String label) {
            String previous = knownLabel(state, pendingKind);
            String display = previous == null ? label : "上次：" + previous + " · " + label;
            return new Snapshot(state, pendingKind, validity, source, turnId, eventAtMs, checkedAtMs, observedAtElapsedMs, display, questionIds, goal);
        }
        /** 合并同一owner的目标投影，不改变原生命周期、来源和待办身份。 */
        private Snapshot withGoal(Goal goal) {
            return new Snapshot(state,pendingKind,validity,source,turnId,eventAtMs,checkedAtMs,observedAtElapsedMs,label,questionIds,goal);
        }
    }

    /** 当前 available 或 none 被接受后通知原运行时入队，不在这里保存第二份事实。 */
    public interface ConfirmedGoal {
        void accepted(long dialogId, String machine, String remote, Goal goal);
    }

    private static volatile ConfirmedGoal confirmedGoal;

    /** 由 CodexRuntime 安装一次。测试可换成同一个入队方法。 */
    public static void onConfirmedGoal(ConfirmedGoal listener) { confirmedGoal = listener; }

    /** 仅由界面线程读写；本机 dialogId 已由账号及电脑身份 owner 隔离。 */
    public static final class Store {
        private final Map<Long, Entry> entries = new HashMap<>();
        private final Map<String, Long> unavailableAt = new HashMap<>();

        /** 保存每条事实的原请求时刻，旧慢响应不能覆盖较新的观察或断连。 */
        private static final class Entry {
            final String machine;
            final long startedAt, goalRequestedAt;
            final boolean confirmedGoal, goalRestoreAttempted;
            final Snapshot snapshot;
            /** 合并本机请求顺序与来源归属，不存消息正文。 */
            Entry(String machine, long startedAt, Snapshot snapshot) {
                this(machine,startedAt,-1,false,false,snapshot);
            }
            /** 原Entry同时保留目标响应排序边界，不另建缓存或轮询owner。 */
            Entry(String machine, long startedAt, long goalRequestedAt, Snapshot snapshot) {
                this(machine,startedAt,goalRequestedAt,false,false,snapshot);
            }
            /** 确认过当前目标后，迟到磁盘不能再填这个槽。冷恢复只认领一次。 */
            Entry(String machine, long startedAt, long goalRequestedAt, boolean confirmedGoal, boolean goalRestoreAttempted, Snapshot snapshot) {
                this.machine=machine; this.startedAt=startedAt; this.goalRequestedAt=goalRequestedAt;
                this.confirmedGoal=confirmedGoal; this.goalRestoreAttempted=goalRestoreAttempted; this.snapshot=snapshot;
            }
        }

        /** 消费现有批量 LIST 的一行；磁盘缓存只能作为未更新事实，不能获得新的有效期。 */
        public void candidate(long dialogId, String machine, JsonObject row, long startedAt, long receivedAt, boolean cached) {
            Snapshot snapshot = candidateSnapshot(row, cached ? -1 : receivedAt);
            if (cached) {
                if (!entries.containsKey(dialogId)) entries.put(dialogId,
                        new Entry(machine, -1, "current".equals(snapshot.validity)
                                ? snapshot.invalid("stale", "状态未更新") : snapshot));
                return;
            }
            put(dialogId, machine, startedAt, snapshot);
        }

        /** 原生命周期调用兼容旧协议；未绑定remote时不接受可用目标。 */
        public void observation(long dialogId, String machine, JsonObject response, long startedAt, long receivedAt) {
            observation(dialogId,machine,"",response,startedAt,receivedAt);
        }

        /** 同次STATUS分别合并两种事实；较新的LIST不能挡住较新的目标，也不能为旧目标续期。 */
        public void observation(long dialogId, String machine, String remote, JsonObject response, long startedAt, long receivedAt) {
            Long disconnected=unavailableAt.get(machine);
            if(disconnected!=null&&startedAt<=disconnected)return;
            Entry previous=entries.get(dialogId);
            if(previous!=null&&!previous.machine.equals(machine))previous=null;
            Snapshot snapshot=previous!=null&&startedAt<previous.startedAt?previous.snapshot:responseSnapshot(response,receivedAt);
            long stateStartedAt=previous!=null&&startedAt<previous.startedAt?previous.startedAt:startedAt;
            long goalRequestedAt=previous==null?-1:previous.goalRequestedAt;
            boolean confirmed=previous!=null&&previous.confirmedGoal;
            boolean goalRestoreAttempted=previous!=null&&previous.goalRestoreAttempted;
            Goal goal=previous==null?snapshot.goal:previous.snapshot.goal;
            Goal accepted=null;
            if(startedAt>=goalRequestedAt) {
                Goal observed=goalSnapshot(response,remote,startedAt);
                boolean currentFact="current".equals(observed.validity)
                        &&("available".equals(observed.availability)||"none".equals(observed.availability));
                if(!"current".equals(observed.validity)&&goal.hasValue())
                    observed=goal.invalid(observed.availability,observed.validity,observed.label);
                goal=observed; goalRequestedAt=startedAt;
                if(currentFact) { confirmed=true; accepted=goal; }
            }
            entries.put(dialogId,new Entry(machine,stateStartedAt,goalRequestedAt,confirmed,goalRestoreAttempted,snapshot.withGoal(goal)));
            if(accepted!=null&&SessionStatus.confirmedGoal!=null)
                SessionStatus.confirmedGoal.accepted(dialogId,machine,remote,accepted);
        }

        /**
         * 只把上次目标放进还没有当前 available/none 的槽。
         * 不改请求顺序、提问身份和生命周期；新的当前事实之后返回 false。
         */
        public boolean offerCachedGoal(long dialogId, String machine, String availability, String objective, String status,
                Long tokenBudget, Long tokensUsed, Long timeUsedSeconds, long updatedAt) {
            Entry entry=entries.get(dialogId);
            if(entry==null||!entry.machine.equals(machine)||entry.confirmedGoal) return false;
            Goal current=entry.snapshot.goal;
            boolean untouched=!current.hasValue()&&("unsupported".equals(current.availability)
                    ||"unknown".equals(current.availability)||"unavailable".equals(current.availability));
            if(!untouched) return false;
            Goal remembered=remembered(availability,objective,status,tokenBudget,tokensUsed,timeUsedSeconds,updatedAt,current.label);
            if(remembered==null) return false;
            entries.put(dialogId,new Entry(entry.machine,entry.startedAt,entry.goalRequestedAt,false,entry.goalRestoreAttempted,
                    entry.snapshot.withGoal(remembered)));
            return true;
        }

        /**
         * 只为绑定后的第一个未确认槽认领一次冷恢复。
         * 认领记在原条目上，随后的合并保留它；换电脑或清空账号才会重新开始。
         */
        public boolean claimGoalRestore(long dialogId, String machine) {
            Entry entry=entries.get(dialogId);
            if(entry==null||!entry.machine.equals(machine)||entry.goalRestoreAttempted||entry.confirmedGoal) return false;
            Goal current=entry.snapshot.goal;
            boolean untouched=!current.hasValue()&&("unsupported".equals(current.availability)
                    ||"unknown".equals(current.availability)||"unavailable".equals(current.availability));
            if(!untouched) return false;
            entries.put(dialogId,new Entry(entry.machine,entry.startedAt,entry.goalRequestedAt,entry.confirmedGoal,true,entry.snapshot));
            return true;
        }

        /**
         * 退出清凭据失败时，只放开尚未确认、没有正文、仍可冷恢复的一次认领。
         * 不改快照、请求顺序、已确认目标、明确无目标或已经恢复的正文。
         */
        public void rearmUnconfirmedGoalRestore() {
            for (Map.Entry<Long, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                if (!entry.goalRestoreAttempted || entry.confirmedGoal) continue;
                Goal goal = entry.snapshot.goal;
                boolean eligible = !goal.hasValue() && ("unsupported".equals(goal.availability)
                        || "unknown".equals(goal.availability) || "unavailable".equals(goal.availability));
                if (!eligible) continue;
                item.setValue(new Entry(entry.machine, entry.startedAt, entry.goalRequestedAt, entry.confirmedGoal, false, entry.snapshot));
            }
        }

        /** 合并规则只使用同一手机的请求顺序，不比较跨端墙钟。 */
        private void put(long dialogId, String machine, long startedAt, Snapshot snapshot) {
            Long disconnected = unavailableAt.get(machine);
            Entry previous = entries.get(dialogId);
            if (disconnected != null && startedAt <= disconnected) return;
            if (previous != null && startedAt < previous.startedAt) return;
            boolean same=previous!=null&&previous.machine.equals(machine);
            if(same) snapshot=snapshot.withGoal(previous.snapshot.goal);
            entries.put(dialogId, new Entry(machine, startedAt, same?previous.goalRequestedAt:-1,
                    same&&previous.confirmedGoal, same&&previous.goalRestoreAttempted, snapshot));
        }

        /** LIST 失败只失效尚未被更新观察替代的条目，不建立整机断连边界。 */
        public void listFailed(String machine, long startedAt) {
            for (Map.Entry<Long, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                if (entry.machine.equals(machine) && entry.startedAt <= startedAt)
                    item.setValue(new Entry(machine, startedAt, entry.goalRequestedAt, entry.confirmedGoal, entry.goalRestoreAttempted,
                            entry.snapshot.invalid("unavailable", "状态暂不可用")));
            }
        }

        /** 真实连接断开使对应电脑失效；该时刻之前发出的回包不能恢复它。 */
        public void unavailable(String machine, long now) {
            unavailableAt.put(machine, Math.max(now, unavailableAt.getOrDefault(machine, -1L)));
            for (Map.Entry<Long, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                if (entry.machine.equals(machine)) item.setValue(new Entry(machine, entry.startedAt, entry.goalRequestedAt, entry.confirmedGoal, entry.goalRestoreAttempted,
                        entry.snapshot.invalid("unavailable", "连接暂不可用").withGoal(
                                entry.snapshot.goal.invalid(entry.snapshot.goal.availability,"unavailable","连接暂不可用"))));
            }
        }

        /**
         * 只读原请求起点加 15 秒的到期时刻，供界面交给提问队列。
         * 缺失、缓存、未知、离线、错电脑、时钟倒退、到期或相加溢出都返回 -1；不改条目，也不用接收时刻续期。
         */
        public long currentExpiry(long dialogId, String machine, long now) {
            Entry entry = entries.get(dialogId);
            if (entry == null || machine == null || !machine.equals(entry.machine)) return -1;
            if (entry.startedAt < 0 || now < entry.startedAt) return -1;
            if (!"current".equals(entry.snapshot.validity)) return -1;
            if (entry.startedAt > Long.MAX_VALUE - FRESHNESS_MS) return -1;
            long expiry = entry.startedAt + FRESHNESS_MS;
            if (now >= expiry) return -1;
            return expiry;
        }

        /** 有效期从请求开始计时；列表恰好15秒刷新或后台深睡都不能延长旧事实。 */
        public Snapshot get(long dialogId, long now) {
            Entry entry = entries.get(dialogId);
            if (entry == null) return unknown("syncing", "同步中", -1);
            Snapshot snapshot=entry.snapshot;
            if ("current".equals(snapshot.validity)
                    && (now < entry.startedAt || now - entry.startedAt >= FRESHNESS_MS))
                snapshot=snapshot.invalid("stale", "状态已过期");
            Goal goal=snapshot.goal;
            if("current".equals(goal.validity)&&(now<goal.startedAtElapsedMs||now-goal.startedAtElapsedMs>=FRESHNESS_MS))
                snapshot=snapshot.withGoal(goal.invalid(goal.availability,"stale","目标已过期"));
            return snapshot;
        }

        /** 账号退出清空展示事实和断连边界，不让下一账号继承状态。 */
        public void clear() { entries.clear(); unavailableAt.clear(); }
    }

    /** 磁盘目标永远不是当前事实，时钟用 -1，文案同时带上次状态和这次不可用原因。 */
    private static Goal remembered(String availability, String objective, String status, Long tokenBudget, Long tokensUsed,
            Long timeUsedSeconds, long updatedAt, String reason) {
        String known="none".equals(availability)?"没有目标":goalLabel(status);
        if(known==null||reason==null||reason.isEmpty()) return null;
        if("available".equals(availability)) {
            if(objective==null||objective.isEmpty()) return null;
        } else if(!"none".equals(availability)||objective!=null&&!objective.isEmpty()) return null;
        return new Goal(availability,"stale","desktop","",objective==null?"":objective,status==null?"":status,
                tokenBudget,tokensUsed,timeUsedSeconds,updatedAt,-1,"上次："+known+" · "+reason);
    }

    /** 无事实、明确没有目标及旧daemon缺字段保持不同，不把解析失败伪装成none。 */
    private static Goal emptyGoal(String availability,String validity,String label) {
        return new Goal(availability,validity,"","","","",null,null,null,-1,-1,label);
    }

    /** 原STATUS独立目标契约；不依赖生命周期turn或运行状态，身份绑定真实remote。 */
    private static Goal goalSnapshot(JsonObject response,String remote,long startedAt) {
        if(!flag(response,"ok")||!flag(response,"machineOnline"))return emptyGoal("unknown","unavailable","目标暂不可用");
        if(!response.has("goal"))return emptyGoal("unsupported","unsupported","目标信息暂不可用");
        JsonObject goal=object(response,"goal");
        String availability=text(goal,"availability");
        if(goal!=null&&"unknown".equals(availability)&&keys(goal,"availability"))
            return emptyGoal("unknown","unknown","目标未知");
        if(goal!=null&&"none".equals(availability)&&"desktop".equals(text(goal,"source"))&&keys(goal,"availability","source"))
            return new Goal("none","current","desktop","","","",null,null,null,-1,startedAt,"没有目标");
        try {
            if(!"available".equals(availability)||!keys(goal,"availability","source","threadId","objective","status","tokenBudget","tokensUsed","timeUsedSeconds","updatedAt")
                    ||!"desktop".equals(text(goal,"source"))||remote.isEmpty()||!remote.equals(text(goal,"threadId"))
                    ||text(goal,"objective").trim().isEmpty()||goalLabel(text(goal,"status"))==null)
                return emptyGoal("unknown","unknown","目标未知");
            Long budget=goalNumber(goal,"tokenBudget",true),tokens=goalNumber(goal,"tokensUsed",true),seconds=goalNumber(goal,"timeUsedSeconds",true);
            long updated=goalNumber(goal,"updatedAt",false);
            return new Goal("available","current","desktop",remote,text(goal,"objective"),text(goal,"status"),
                    budget,tokens,seconds,updated,startedAt,goalLabel(text(goal,"status")));
        } catch(RuntimeException error) {return emptyGoal("unknown","unknown","目标未知");}
    }

    /** 只接受协议布尔值，错误封套不授予目标当前性。 */
    private static boolean flag(JsonObject object,String key) {
        JsonElement value=object==null?null:object.get(key);
        return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()&&value.getAsBoolean();
    }

    /** 严格字段集与冻结协议一致，未知扩展不能悄悄获得可用性。 */
    private static boolean keys(JsonObject object,String...expected) {
        return object!=null&&object.keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList(expected)));
    }

    /** 安全整数及null原样保留；JS安全边界之外、字符串、负数和小数均未知。 */
    private static Long goalNumber(JsonObject object,String key,boolean nullable) {
        JsonElement value=object.get(key);
        if(value==null)throw new IllegalArgumentException("missing");
        if(nullable&&value.isJsonNull())return null;
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("type");
        long number=value.getAsBigDecimal().longValueExact();
        if(number<0||number>9007199254740991L)throw new IllegalArgumentException("range");
        return number;
    }

    /** 仅映射协议的真实目标状态，不与聊天完成状态混为一谈。 */
    private static String goalLabel(String status) {
        switch(status) {
            case "active":return "目标进行中";
            case "paused":return "目标已暂停";
            case "blocked":return "目标受阻";
            case "usageLimited":return "目标额度受限";
            case "budgetLimited":return "目标预算已达上限";
            case "complete":return "目标已完成";
            default:return null;
        }
    }

    /** 兼容原展示入口；候选与 STATUS 都委托同一事实解析，不读取网络或磁盘。 */
    public static String label(JsonObject response) {
        return (response != null && response.has("details") ? candidateSnapshot(response, -1) : responseSnapshot(response, -1)).label;
    }

    /** 创建无可用事实的明确状态，不从活动时间、未读或连接成功猜测生命周期。 */
    private static Snapshot unknown(String validity, String label, long observedAt) {
        return new Snapshot("unknown", "unknown", validity, "", "", -1, -1, observedAt, label, java.util.Collections.emptySet());
    }

    /** 安全收窄可选 JSON 对象，格式错误由对应事实入口降级。 */
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** 只接受协议字符串，不把数值或布尔值强制转成有效身份。 */
    private static String text(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    /** 来源时间必须是非负整数；缺失、非整数和类型错误都不能获得有效期。 */
    private static long time(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try { long result = value.getAsBigDecimal().longValueExact(); return result >= 0 ? result : -1; }
        catch (RuntimeException error) { return -1; }
    }

    /** 验证原观察身份并仅按真实请求类型区分提问、审批或两者同时存在。 */
    private static Snapshot observationSnapshot(JsonObject observation, long receivedAt) {
        if (time(observation, "v") != 1) return unknown("unknown", "状态未知", receivedAt);
        String state = text(observation, "state");
        if ("unknown".equals(state)) return "not_observed".equals(text(observation, "reason"))
                ? unknown("syncing", "同步中", receivedAt) : unknown("unknown", "状态未知", receivedAt);
        String source = text(observation, "source"), turnId = text(observation, "turnId");
        if ((!"desktop".equals(source) && !"rollout".equals(source)) || turnId.trim().isEmpty())
            return unknown("unknown", "状态未知", receivedAt);
        String pending = "none";
        java.util.Set<String> questionIds = new java.util.HashSet<>();
        if ("needs_input".equals(state)) {
            JsonElement requests = observation.get("requests");
            if (requests == null || !requests.isJsonArray() || requests.getAsJsonArray().size() == 0)
                return unknown("unknown", "状态未知", receivedAt);
            boolean question = false, approval = false;
            for (JsonElement value : requests.getAsJsonArray()) {
                if (!value.isJsonObject()) return unknown("unknown", "状态未知", receivedAt);
                JsonObject request = value.getAsJsonObject();
                if (text(request, "requestId").trim().isEmpty()) return unknown("unknown", "状态未知", receivedAt);
                String kind = text(request, "kind");
                if ("permission_request".equals(kind)) approval = true;
                else if ("user_action_request".equals(kind)) {
                    question = true;
                    // 原STATUS已有未答题身份；只供打开的表单判断变化，不授予提交能力。
                    questionIds.add(text(request, "requestId"));
                }
                else return unknown("unknown", "状态未知", receivedAt);
            }
            pending = question && approval ? "mixed" : question ? "question" : "approval";
        }
        return known(state, pending, source, turnId, -1, -1, receivedAt, questionIds);
    }

    /** STATUS 的在线和错误信息优先；runnerActive/activity 都不进入生命周期判断。 */
    private static Snapshot responseSnapshot(JsonObject response, long receivedAt) {
        try {
            if (response == null || !response.get("ok").getAsBoolean()) return unknown("unavailable", "状态暂不可用", receivedAt);
            if (!response.get("machineOnline").getAsBoolean()) return unknown("unavailable", "电脑离线", receivedAt);
            return observationSnapshot(object(response, "observation"), receivedAt);
        } catch (RuntimeException error) { return unknown("unknown", "状态未知", receivedAt); }
    }

    /** 批量候选只接受正式生命周期字段；新待办投影缺失时保留旧协议的笼统待处理。 */
    private static Snapshot candidateSnapshot(JsonObject candidate, long receivedAt) {
        JsonObject details = object(candidate, "details"), fact = object(details, "codexLifecycle");
        long checkedAt = time(fact, "checkedAtMs"), eventAt = time(fact, "eventAtMs");
        if (time(fact, "v") != 1 || checkedAt < 0) return unknown("unknown", "状态未知", receivedAt);
        String state = text(fact, "state");
        if ("unknown".equals(state) || eventAt < 0 || eventAt > checkedAt) return unknown("unknown", "状态未知", receivedAt);
        String pending = "needs_input".equals(state) ? "unknown" : "none";
        String source = "rollout", turnId = "";
        java.util.Set<String> questionIds = java.util.Collections.emptySet();
        if (details.has("codexObservation")) {
            Snapshot observation = observationSnapshot(object(details, "codexObservation"), receivedAt);
            if (!"current".equals(observation.validity) || !state.equals(observation.state)) return unknown("unknown", "状态未知", receivedAt);
            pending = observation.pendingKind; source = observation.source; turnId = observation.turnId;
            questionIds = observation.questionIds;
        }
        return known(state, pending, source, turnId, eventAt, checkedAt, receivedAt, questionIds);
    }

    /** 两种来源只在此处创建当前事实；历史说明复用文字映射，不授予当前有效性。 */
    private static Snapshot known(String state, String pending, String source, String turnId,
            long eventAt, long checkedAt, long receivedAt, java.util.Set<String> questionIds) {
        String label = knownLabel(state, pending);
        if (label == null) return unknown("unknown", "状态未知", receivedAt);
        return new Snapshot(state, pending, "current", source, turnId, eventAt, checkedAt, receivedAt, label, questionIds);
    }

    /** 仅映射既有明确状态；当前和上次文案共用，不从显示文字恢复事实。 */
    private static String knownLabel(String state, String pending) {
        switch (state) {
            case "running": return "运行中";
            case "completed": return "已完成";
            case "failed": return "执行失败";
            case "cancelled": return "已取消";
            case "needs_input": return "question".equals(pending) ? "待你回复" : "approval".equals(pending)
                    ? "待批准" : "mixed".equals(pending) ? "待你回复／批准" : "待处理";
            default: return null;
        }
    }
}
