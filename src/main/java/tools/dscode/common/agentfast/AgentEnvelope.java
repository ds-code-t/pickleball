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
        copy.put("items", items(copy));
        Object priorNext = copy.get("next");
        if (priorNext instanceof Number number) copy.put("seq", number.intValue());
        if (copy.get("chatNext") == null) {
            Object messages = copy.get("messages");
            if (messages instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map<?, ?> last
                    && last.get("id") != null) {
                copy.put("chatNext", String.valueOf(last.get("id")));
            }
        }
        copy.put("next", AgentCursor.stamp(runId, agentId, copy));
        copy.put("trunc", trunc(copy));
        copy.put("err", ok ? null : error(copy, verb));
        return copy;
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

    private static List<Object> items(Map<String, Object> body) {
        for (String key : List.of("lines", "messages", "events", "chat", "hits", "ops")) {
            Object value = body.get(key);
            if (value instanceof List<?> list) return new ArrayList<>(list);
        }
        return List.of();
    }

    private static Map<String, Object> trunc(Map<String, Object> body) {
        Map<String, Object> trunc = new LinkedHashMap<>();
        boolean on = Boolean.TRUE.equals(body.get("truncated"));
        trunc.put("on", on);
        if (body.get("full") != null) trunc.put("full", body.get("full"));
        Object lines = body.get("lines");
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
