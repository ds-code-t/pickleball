package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent reply envelope. Existing fields stay so older callers keep working.
 * {@code v}, {@code ok}, {@code op}, {@code ms}, {@code state}, {@code sum},
 * {@code items}, {@code next}, {@code trunc}, and {@code err} are always present.
 */
public final class AgentEnvelope {
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentEnvelope() {
    }

    public static Map<String, Object> apply(Map<String, Object> body, int exit, String op, Path project, String runId) {
        return apply(body, exit, op, project, runId, null);
    }

    public static Map<String, Object> apply(
            Map<String, Object> body,
            int exit,
            String op,
            Path project,
            String runId,
            String agentId
    ) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (body != null) copy.putAll(body);
        if (copy.get("v") instanceof Number number && number.intValue() == VERSION && copy.get("state") instanceof Map) {
            long ms = longValue(copy.get("elapsedMs"), longValue(copy.get("ms"), 0));
            copy.put("ms", ms);
            copy.put("state", state(copy, project, runId));
            return copy;
        }
        String verb = op == null || op.isBlank() ? String.valueOf(copy.getOrDefault("op", "")) : op;
        boolean ok = exit == 0 && !Boolean.FALSE.equals(copy.get("ok"));
        long ms = longValue(copy.get("elapsedMs"), longValue(copy.get("ms"), 0));
        copy.put("v", VERSION);
        copy.put("ok", ok);
        copy.put("op", verb);
        copy.put("ms", ms);
        copy.put("state", state(copy, project, runId));
        copy.put("sum", sum(copy, verb, ok));
        if (copy.get("chatNext") == null) {
            Object messages = copy.get("messages");
            if (messages instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map<?, ?> last
                    && last.get("id") != null) {
                copy.put("chatNext", String.valueOf(last.get("id")));
            }
        }
        copy.put("items", takeItems(copy));
        Object priorNext = copy.get("next");
        if (priorNext instanceof Number number) copy.put("seq", number.intValue());
        copy.put("next", AgentCursor.stamp(runId, agentId, copy));
        compactVersions(copy);
        copy.put("err", ok ? null : error(copy, verb));
        copy.put("trunc", trunc(copy));
        int cap = replyCap(copy, verb);
        if (cap > 0 && SnapView.fitJson(copy, cap)) {
            for (int spin = 0; spin < 8; spin++) {
                markTruncated(copy);
                if (!SnapView.fitJson(copy, cap)) break;
            }
            markTruncated(copy);
        }
        return copy;
    }

    /** Records that the reply was cut. Line count is refreshed after the rows are dropped. */
    private static void markTruncated(Map<String, Object> copy) {
        Object trunc = copy.get("trunc");
        if (trunc instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> updated = (Map<String, Object>) map;
            updated.put("on", true);
            Object items = copy.get("items");
            if (items instanceof List<?> list) updated.put("lines", list.size());
        }
        copy.put("truncated", true);
    }

    /** Ping body a warm controller returns without running a verb. */
    public static String ping(Path project, String op, long startedNs) {
        long ms = Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", op == null ? "ping" : op);
        body.put("elapsedMs", ms);
        body.put("sentence", "controller is up");
        try {
            return JSON.writeValueAsString(apply(body, 0, "ping", project, "", null)) + "\n";
        } catch (Exception failure) {
            return "{\"v\":1,\"ok\":true,\"op\":\"ping\",\"ms\":" + ms + "}\n";
        }
    }

    /** Adds the envelope when a printed JSON object does not already have one. Refreshes {@code ms}. */
    public static String ensure(String json, String op, long ms, Path project, String runId) {
        String raw = json == null || json.isBlank() ? "{}" : json.trim();
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isObject()) return raw;
            ObjectNode object = (ObjectNode) node;
            if (object.has("v") && object.has("state")) {
                if (ms > 0) object.put("ms", Math.max(0L, ms));
                return JSON.writeValueAsString(object);
            }
            Map<String, Object> body = JSON.convertValue(object, Map.class);
            body.put("elapsedMs", Math.max(0L, ms));
            return JSON.writeValueAsString(apply(body, object.path("ok").asBoolean(true) ? 0 : 1, op, project, runId));
        } catch (Exception failure) {
            return raw;
        }
    }

    /** Line rendering of an envelope. Smaller than the JSON object. */
    public static String renderLines(String json) {
        try {
            JsonNode node = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
            StringBuilder text = new StringBuilder();
            text.append("v=").append(node.path("v").asInt(VERSION));
            text.append(" ok=").append(node.path("ok").asBoolean(false));
            text.append(" op=").append(LineView.token(node.path("op").asText("")));
            text.append(" ms=").append(node.path("ms").asLong(node.path("elapsedMs").asLong(0)));
            text.append('\n');
            JsonNode state = node.path("state");
            text.append("state.session=").append(LineView.token(state.path("session").asText("")));
            text.append(" state.browser=").append(LineView.token(state.path("browser").asText("")));
            text.append(" state.frame=").append(LineView.token(state.path("frame").asText("")));
            text.append(" state.lease=").append(LineView.token(state.path("lease").asText("")));
            text.append('\n');
            if (node.has("sum")) {
                text.append("sum=").append(LineView.token(node.path("sum").asText(""))).append('\n');
            }
            JsonNode items = node.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (item.isTextual()) text.append(item.asText()).append('\n');
                    else text.append(item.toString()).append('\n');
                }
            }
            text.append("next=").append(LineView.token(node.path("next").asText(""))).append('\n');
            text.append("trunc.on=").append(node.path("trunc").path("on").asBoolean(false)).append('\n');
            if (!node.path("err").isMissingNode() && !node.path("err").isNull()) {
                text.append("err.code=").append(LineView.token(node.path("err").path("code").asText(""))).append('\n');
            }
            return text.toString().stripTrailing();
        } catch (Exception failure) {
            return json == null ? "" : json;
        }
    }

    private static Map<String, Object> state(Map<String, Object> body, Path project, String runId) {
        Map<String, Object> state = new LinkedHashMap<>();
        Object session = body.get("session");
        state.put("session", session == null || String.valueOf(session).isBlank()
                ? (runId == null ? "" : runId) : String.valueOf(session));
        boolean worker = tools.dscode.control.protocol.BrowserPresence.workerUp(project);
        boolean up = tools.dscode.control.protocol.BrowserPresence.browserUp(project);
        boolean held = worker || tools.dscode.control.protocol.LeaseClock.fresh(project);
        state.put("browser", up ? "up" : "down");
        Object frame = body.get("frame");
        state.put("frame", frame == null || String.valueOf(frame).isBlank() ? "top" : String.valueOf(frame));
        state.put("lease", held ? "held" : "none");
        return state;
    }

    private static String sum(Map<String, Object> body, String op, boolean ok) {
        Object sentence = body.get("sentence");
        if (sentence != null && !String.valueOf(sentence).isBlank()) return String.valueOf(sentence);
        Object error = body.get("error");
        if (!ok && error != null) return op + " " + error;
        return op + (ok ? " ok" : " failed");
    }

    private static final List<String> ITEM_SOURCES = List.of(
            "lines", "messages", "events", "chat", "hits", "ops", "placements"
    );

    /**
     * One list. The first of {@code lines}, {@code messages}, {@code events}, {@code chat},
     * {@code hits}, {@code ops}, or {@code placements} becomes {@code items}. That source, and any
     * other source that is the same list, is removed so a row is not sent twice.
     */
    private static List<Object> takeItems(Map<String, Object> body) {
        String source = null;
        List<?> taken = null;
        for (String key : ITEM_SOURCES) {
            Object value = body.get(key);
            if (value instanceof List<?> list) {
                source = key;
                taken = list;
                break;
            }
        }
        if (taken == null) return List.of();
        List<Object> items = new ArrayList<>(taken);
        for (String key : ITEM_SOURCES) {
            Object value = body.get(key);
            if (value instanceof List<?> list && (key.equals(source) || list.equals(taken))) {
                body.remove(key);
            }
        }
        return items;
    }

    /** Reply doc versions stay short. Desktop versions and an already-short URL are unchanged. */
    private static void compactVersions(Map<String, Object> body) {
        rewriteVersion(body, "docVersion");
        rewriteVersion(body, "doc_version");
        Object snap = body.get("snap");
        if (snap instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map;
            rewriteVersion(nested, "docVersion");
        }
        Object diff = body.get("diff");
        if (diff instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map;
            rewriteVersion(nested, "docVersion");
        }
    }

    private static void rewriteVersion(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof String text) body.put(key, TargetVerb.shownVersion(text));
    }

    /** Snap and find stay inside the documented cap. An explicit {@code replyBudget} wins when smaller. */
    private static int replyCap(Map<String, Object> body, String verb) {
        int cap = 0;
        Object mark = body.remove("replyBudget");
        if (mark instanceof Number number && number.intValue() > 0) cap = number.intValue();
        else if ("snap".equals(verb) || "find".equals(verb)) cap = SnapView.REPLY_CAP;
        if (cap <= 0) return 0;
        return Math.min(SnapView.REPLY_CAP, cap);
    }

    private static Map<String, Object> trunc(Map<String, Object> body) {
        Map<String, Object> trunc = new LinkedHashMap<>();
        boolean on = Boolean.TRUE.equals(body.get("truncated"));
        trunc.put("on", on);
        if (body.get("full") != null) trunc.put("full", body.get("full"));
        Object lines = body.get("items");
        if (!(lines instanceof List<?>)) lines = body.get("lines");
        if (lines instanceof List<?> list) trunc.put("lines", list.size());
        return trunc;
    }

    private static Map<String, Object> error(Map<String, Object> body, String op) {
        Map<String, Object> err = new LinkedHashMap<>();
        Object code = body.get("error");
        String text = code == null ? "failed" : String.valueOf(code);
        err.put("code", text);
        err.put("msg", text);
        Object fix = body.get("fix");
        err.put("fix", fix == null || String.valueOf(fix).isBlank() ? fixFor(text, op) : String.valueOf(fix));
        if (body.get("candidates") != null) err.put("candidates", body.get("candidates"));
        return err;
    }

    private static String fixFor(String code, String op) {
        if ("stale_ref".equals(code)) return "run snap (or find) again for a fresh ref";
        if ("ambiguous".equals(code)) return "pass --ref or a tighter --query";
        if (code != null && code.contains("no live browser")) return "run start --url= --browser=";
        return "help " + (op == null || op.isBlank() ? "snap" : op);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        return fallback;
    }
}
