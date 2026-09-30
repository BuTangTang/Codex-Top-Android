package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/** 保留原桌面题目及请求身份；回答走结构化动作，不转成普通聊天消息。 */
public final class DesktopQuestion {
    public final String kind, itemId, turnId, revision, status;
    public final JsonElement requestId;
    public final boolean canAnswer;
    public final ArrayList<Question> questions;
    public final Map<String, String> answers;

    /** 原题选项文字与说明不经手机重新概括。 */
    public static final class Option {
        public final String label, description;
        /** 保存桌面原选项。 */
        private Option(String label, String description) { this.label = label; this.description = description; }
    }

    /** 每题只选一个答案；缺失的历史能力标记不会授予自填或显示保密答案。 */
    public static final class Question {
        public final String id, header, question;
        public final boolean isOther, isSecret;
        public final ArrayList<Option> options;
        /** 保留原题字段及其允许的输入方式。 */
        private Question(JsonObject value) throws IOException {
            id = required(value, "id"); header = optional(value, "header"); question = required(value, "question");
            isOther = flag(value, "isOther"); isSecret = flag(value, "isSecret");
            options = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray("options")) {
                JsonObject option = element.getAsJsonObject();
                options.add(new Option(required(option, "label"), optional(option, "description")));
            }
        }
        /** 只有原题允许时接受自填；选项和无选项文本均按单答案提交。 */
        public boolean accepts(String answer) {
            if (answer == null || answer.trim().isEmpty()) return false;
            if (options.isEmpty() || isOther) return true;
            for (Option option : options) if (option.label.equals(answer)) return true;
            return false;
        }
    }

    /** 固定一组题目的修订和轮次，展示期间不会被后台新请求替换。 */
    private DesktopQuestion(JsonObject value) throws IOException {
        kind = required(value, "kind"); itemId = required(value, "itemId"); turnId = required(value, "turnId");
        revision = required(value, "revision"); status = required(value, "status");
        requestId = value.get("requestId");
        if (requestId == null || !("async_questions".equals(kind) && requestId.isJsonNull()
                || "user_input".equals(kind) && requestId.isJsonPrimitive()
                && (requestId.getAsJsonPrimitive().isString() || requestId.getAsJsonPrimitive().isNumber())))
            throw new IOException("问题请求身份无效");
        questions = new ArrayList<>();
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (JsonElement element : value.getAsJsonArray("questions")) {
            Question question = new Question(element.getAsJsonObject());
            if (!ids.add(question.id)) throw new IOException("题目身份重复");
            questions.add(question);
        }
        answers = new java.util.LinkedHashMap<>();
        JsonObject confirmed = value.has("answers") ? value.getAsJsonObject("answers") : null;
        if (confirmed != null) for (Question question : questions) {
            JsonElement answer = confirmed.get(question.id);
            if (answer == null) continue;
            if (!answer.isJsonArray() || answer.getAsJsonArray().size() != 1
                    || !answer.getAsJsonArray().get(0).isJsonPrimitive()
                    || !answer.getAsJsonArray().get(0).getAsJsonPrimitive().isString())
                throw new IOException("已回答题目信息无效");
            answers.put(question.id, answer.getAsJsonArray().get(0).getAsString());
        }
        canAnswer = "pending".equals(status) && flag(value, "canAnswer") && !questions.isEmpty();
    }

    /** 仅读取明确支持的控制快照；旧服务没有问题字段时不编造空成功。 */
    public static ArrayList<DesktopQuestion> read(JsonObject snapshot) throws IOException {
        try {
            JsonElement version = snapshot.get("v");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0)
                throw new IOException("问题读取版本不支持");
            if (!snapshot.has("questions")) throw new IOException("当前电脑尚未提供问题读取能力");
            ArrayList<DesktopQuestion> result = new ArrayList<>();
            for (JsonElement value : snapshot.getAsJsonArray("questions")) result.add(new DesktopQuestion(value.getAsJsonObject()));
            return result;
        } catch (RuntimeException error) { throw new IOException("问题详情无法读取", error); }
    }

    /** 草稿和提交意图均绑定同一原题修订，不能复用到另一轮的问题。 */
    public String identity() {
        return turnId + "\n" + kind + "\n" + requestId + "\n" + itemId + "\n" + revision;
    }

    /** 按原题顺序构造完整回答，保留原 requestId 的数字、字符串或空类型。 */
    public JsonObject action(String machine, String session, String operationId, Map<String, String> answers) {
        if (!canAnswer || answers.size() != questions.size()) throw new IllegalStateException("问题尚未回答完整");
        JsonObject values = new JsonObject();
        for (Question question : questions) {
            String answer = answers.get(question.id);
            if (!question.accepts(answer) || this.answers.containsKey(question.id)
                    && !this.answers.get(question.id).equals(answer))
                throw new IllegalStateException("问题尚未回答完整或已有回答发生变化");
            JsonArray one = new JsonArray(); one.add(answer); values.add(question.id, one);
        }
        JsonObject action = new JsonObject();
        action.addProperty("machineId", machine); action.addProperty("sessionId", session);
        action.addProperty("kind", "answer"); action.addProperty("operationId", operationId);
        action.addProperty("expectedTurnId", turnId); action.addProperty("requestKind", kind);
        action.add("requestId", requestId.deepCopy()); action.addProperty("itemId", itemId);
        action.addProperty("revision", revision); action.add("answers", values);
        return action;
    }

    /** 身份和正文字段必须来自原字符串，不将任意类型强制显示成题目。 */
    private static String required(JsonObject value, String key) throws IOException {
        String result = optional(value, key);
        if (result.trim().isEmpty()) throw new IOException("题目信息缺失");
        return result;
    }

    /** 原题可选文字为空时保持为空。 */
    private static String optional(JsonObject value, String key) {
        JsonElement item = value.get(key);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : "";
    }

    /** 只有明确布尔字段才开放原题能力。 */
    private static boolean flag(JsonObject value, String key) {
        JsonElement item = value.get(key);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean() && item.getAsBoolean();
    }
}
