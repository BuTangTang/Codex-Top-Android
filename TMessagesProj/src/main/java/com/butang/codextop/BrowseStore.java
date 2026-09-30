package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

/** 只保存电脑、项目和已浏览分页的元数据；不保存消息或认证材料，不授予在线有效期。 */
public final class BrowseStore {
    private final File directory;

    /** 沿用最近列表的服务及账号分区，浏览范围再单独散列。 */
    public BrowseStore(File root, String server, String account) {
        directory = new File(root, TranscriptStore.digest(server + "\n" + account));
    }

    /** 根目录集合排序去重，集合顺序变化不产生另一份项目缓存。 */
    public static String scope(String kind, String machine, List<String> roots) {
        JsonArray key = new JsonArray();
        key.add(kind); key.add(machine);
        if (roots == null) key.add((String) null);
        else {
            JsonArray sorted = new JsonArray();
            for (String root : new TreeSet<>(roots)) sorted.add(root);
            key.add(sorted);
        }
        return key.toString();
    }

    /** 实体身份不包含名称、在线标记或行号，供分页去重及原版列表差异更新。 */
    public static String identity(String kind, JsonObject row) {
        if ("conversations".equals(kind)) return row.get("remoteSessionId").getAsString();
        return row.get("id").getAsString();
    }

    /** 读取同一范围的完整缓存；损坏、版本或归属不符时交调用者按无缓存处理。 */
    public JsonObject read(String kind, String machine, List<String> roots) throws IOException {
        String scope = scope(kind, machine, roots);
        JsonObject envelope = DialogStore.readJson(new File(directory, TranscriptStore.digest(scope) + ".json"));
        if (envelope == null) return null;
        try {
            if (envelope.get("v").getAsInt() != 1 || !scope.equals(envelope.get("scope").getAsString()))
                throw new IOException("浏览缓存归属无效");
            return merge(kind, null, envelope.getAsJsonObject("snapshot"), false);
        } catch (RuntimeException error) { throw new IOException("浏览缓存格式无效", error); }
    }

    /** 只持久化白名单元数据；写失败由上层保留刚取得的内存结果。 */
    public void write(String kind, String machine, List<String> roots, JsonObject snapshot) throws IOException {
        String scope = scope(kind, machine, roots);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 1); envelope.addProperty("scope", scope);
        envelope.add("snapshot", merge(kind, null, snapshot, false));
        DialogStore.writeJson(new File(directory, TranscriptStore.digest(scope) + ".json"), envelope);
    }

    /** 合并已加载页；只有完整且无后续的首屏才能证明旧行已移除，失败无需调用此方法。 */
    public static JsonObject merge(String kind, JsonObject previous, JsonObject page, boolean append) throws IOException {
        try {
            boolean incomplete = page.has("searchIncomplete") && page.get("searchIncomplete").getAsBoolean();
            String next = page.has("nextCursor") && !page.get("nextCursor").isJsonNull()
                    ? page.get("nextCursor").getAsString() : null;
            LinkedHashMap<String, JsonObject> rows = new LinkedHashMap<>();
            for (JsonElement value : page.getAsJsonArray("rows")) {
                JsonObject row = cleanRow(kind, value.getAsJsonObject());
                String id = identity(kind, row);
                if (id.isEmpty() || rows.put(id, row) != null) throw new IOException("浏览行身份重复或缺失");
            }
            if (previous != null && (append || incomplete || next != null)) {
                for (JsonElement value : previous.getAsJsonArray("rows")) {
                    JsonObject row = cleanRow(kind, value.getAsJsonObject());
                    rows.putIfAbsent(identity(kind, row), row);
                }
            }
            ArrayList<JsonObject> ordered = new ArrayList<>(rows.values());
            if ("conversations".equals(kind)) ordered.sort((a, b) -> {
                int time = Long.compare(b.get("updatedAtMs").getAsLong(), a.get("updatedAtMs").getAsLong());
                return time != 0 ? time : identity(kind, a).compareTo(identity(kind, b));
            });
            JsonArray resultRows = new JsonArray();
            for (JsonObject row : ordered) resultRows.add(row);
            JsonObject result = new JsonObject();
            result.add("rows", resultRows); result.addProperty("nextCursor", next);
            result.addProperty("searchIncomplete", incomplete);
            return result;
        } catch (RuntimeException error) { throw new IOException("浏览列表格式无效", error); }
    }

    /** 缓存仅保留现有页面使用的显示字段及状态事实，排除未知字段和临时本机编号。 */
    private static JsonObject cleanRow(String kind, JsonObject row) throws IOException {
        JsonObject clean = new JsonObject();
        if ("conversations".equals(kind)) {
            copyString(row, clean, "remoteSessionId", true);
            long updatedAt = row.get("updatedAtMs").getAsBigDecimal().longValueExact();
            if (updatedAt < 0) throw new IOException("会话时间无效");
            clean.addProperty("updatedAtMs", updatedAt);
            copyString(row, clean, "title", false);
            if (row.has("details") && row.get("details").isJsonObject()) {
                JsonObject details = new JsonObject(), input = row.getAsJsonObject("details");
                copyString(input, details, "cwd", false); copyString(input, details, "path", false);
                for (String field : new String[]{"codexLifecycle", "codexObservation"})
                    if (input.has(field) && input.get(field).isJsonObject()) details.add(field, input.get(field).deepCopy());
                clean.add("details", details);
            }
        } else {
            copyString(row, clean, "name", false);
            if (!clean.has("name")) throw new IOException("浏览名称缺失");
            copyString(row, clean, "id", true);
            String flag = "computers".equals(kind) ? "active" : "available";
            if (!row.get(flag).isJsonPrimitive() || !row.getAsJsonPrimitive(flag).isBoolean())
                throw new IOException("浏览状态格式无效");
            clean.addProperty(flag, row.get(flag).getAsBoolean());
            if (!"computers".equals(kind)) {
                JsonArray roots = new JsonArray();
                for (JsonElement value : row.getAsJsonArray("rootPaths")) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty())
                        throw new IOException("项目目录缺失");
                    roots.add(value.getAsString());
                }
                clean.add("rootPaths", roots);
            }
        }
        return clean;
    }

    /** 字符串类型严格验证，缺失可选字段不生成展示猜测。 */
    private static void copyString(JsonObject input, JsonObject output, String field, boolean required) throws IOException {
        if (!input.has(field) || input.get(field).isJsonNull()) {
            if (required) throw new IOException("浏览身份缺失");
            return;
        }
        if (!input.get(field).isJsonPrimitive() || !input.getAsJsonPrimitive(field).isString()
                || (required && input.get(field).getAsString().isEmpty())) throw new IOException("浏览字段无效");
        output.add(field, input.get(field).deepCopy());
    }
}
