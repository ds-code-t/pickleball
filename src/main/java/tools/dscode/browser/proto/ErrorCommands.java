package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E3: page errors with message, stack and location (CDP {@code Runtime.exceptionThrown}, BiDi {@code log.entryAdded}
 * type javascript, Playwright {@code pageError}), source-mapped when the map is reachable over the page's own network,
 * and logpoints over CDP {@code Debugger.setBreakpointByUrl} with a condition that logs and returns false.
 */
final class ErrorCommands {
    private static final Pattern AT = Pattern.compile("^(.*?):(\\d+)(?::(\\d+))?$");

    private ErrorCommands() {
    }

    static void register() {
        Projectors.extraCdp.add(ErrorCommands::cdpError);
        Projectors.extraBidi.add(ErrorCommands::bidiError);
        ProtoHost.register("logpoint", ErrorCommands::logpoint);
    }

    private static SourceMaps cdpMaps(ProtoHost host, String sessionId) {
        Map<String, SourceMaps> maps = host.state("errors.cdpMaps", ConcurrentHashMap::new);
        return maps.computeIfAbsent(sessionId == null ? "" : sessionId, s -> new SourceMaps(url -> {
            JsonNode reply = host.cdp().send(s, "Runtime.evaluate", Map.of(
                    "expression", "fetch(" + ProtoJson.MAPPER.valueToTree(url) + ").then(r=>r.ok?r.text():null).catch(()=>null)",
                    "awaitPromise", true, "returnByValue", true));
            JsonNode value = reply.path("result").path("value");
            return value.isTextual() ? value.asText() : null;
        }));
    }

    private static SourceMaps bidiMaps(ProtoHost host, String context) {
        Map<String, SourceMaps> maps = host.state("errors.bidiMaps", ConcurrentHashMap::new);
        return maps.computeIfAbsent(context == null ? "" : context, c -> new SourceMaps(url -> {
            JsonNode reply = host.bidiModules().evaluate(c,
                    "fetch(" + ProtoJson.MAPPER.valueToTree(url) + ").then(r=>r.ok?r.text():null).catch(()=>null)", null, true);
            JsonNode value = reply.path("result").path("value");
            return value.isTextual() ? value.asText() : null;
        }));
    }

    static boolean cdpError(String method, JsonNode params, String sessionId, BrowserEventHub hub, ProtoHost host) {
        if (!"Runtime.exceptionThrown".equals(method)) return false;
        JsonNode details = params.path("exceptionDetails");
        String description = details.path("exception").path("description").asText("");
        String text = description.isBlank() ? details.path("text").asText("") : description;
        String value = details.path("exception").path("value").asText("");
        if (description.isBlank() && !value.isBlank()) text = details.path("text").asText("") + " " + value;
        boolean rejection = details.path("text").asText("").contains("(in promise)");
        ObjectNode row = hub.row("cdp", "error", rejection ? "unhandled-rejection" : "exception");
        row.put("level", "error").put("text", firstLine(text));
        JsonNode frames = details.path("stackTrace").path("callFrames");
        String url = details.path("url").asText(frames.path(0).path("url").asText(""));
        int line = details.path("lineNumber").asInt(frames.path(0).path("lineNumber").asInt()) + 1;
        int column = details.path("columnNumber").asInt(frames.path(0).path("columnNumber").asInt()) + 1;
        row.put("url", url).put("line", line).put("column", column);
        row.set("stack", stack(frames));
        CdpSession.Target target = host.cdp().target(sessionId);
        if (target != null) {
            row.put("context", target.targetId);
            if (!"page".equals(target.type)) row.put("worker", target.type);
        }
        original(row, url, line, column, () -> cdpMaps(host, sessionId));
        hub.add(row);
        return true;
    }

    static boolean bidiError(String method, JsonNode params, BrowserEventHub hub, ProtoHost host) {
        if (!"log.entryAdded".equals(method) || !"javascript".equals(params.path("type").asText())) return false;
        String text = params.path("text").asText("");
        ObjectNode row = hub.row("bidi", "error", text.contains("(in promise)") ? "unhandled-rejection" : "exception");
        row.put("level", "error").put("text", firstLine(text));
        JsonNode frames = params.path("stackTrace").path("callFrames");
        String url = frames.path(0).path("url").asText("");
        int line = frames.path(0).path("lineNumber").asInt() + 1;
        int column = frames.path(0).path("columnNumber").asInt() + 1;
        if (!url.isBlank()) row.put("url", url).put("line", line).put("column", column);
        row.set("stack", stack(frames));
        String context = params.path("source").path("context").asText(null);
        if (context != null) row.put("context", context);
        if (!url.isBlank() && context != null) original(row, url, line, column, () -> bidiMaps(host, context));
        hub.add(row);
        return true;
    }

    private static void original(ObjectNode row, String url, int line, int column, java.util.function.Supplier<SourceMaps> maps) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            SourceMaps.Position position = maps.get().resolve(url, line, column);
            if (position == null) return;
            ObjectNode original = row.putObject("original");
            original.put("source", position.source()).put("line", position.line()).put("column", position.column());
            if (position.name() != null) original.put("name", position.name());
        } catch (RuntimeException ignored) {
            // Keep the generated location.
        }
    }

    static ArrayNode stack(JsonNode frames) {
        ArrayNode out = ProtoJson.MAPPER.createArrayNode();
        for (JsonNode frame : frames) {
            String fn = frame.path("functionName").asText("");
            out.add((fn.isBlank() ? "<anonymous>" : fn) + " (" + frame.path("url").asText("") + ":" + (frame.path("lineNumber").asInt() + 1)
                    + ":" + (frame.path("columnNumber").asInt() + 1) + ")");
            if (out.size() >= 20) break;
        }
        return out;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** {@code logpoint --at=<url|path>:<line>[:<col>] --expr=<js> | --list | --clear}. Chromium only. */
    static ObjectNode logpoint(ProtoHost host, Map<String, String> f) {
        Map<String, List<String[]>> points = host.state("logpoints", ConcurrentHashMap::new);
        if (f.containsKey("list")) return list(points);
        host.protocols().serve("logpoint", f.get("via"), backend -> {
            if (backend != tools.dscode.browser.BrowserProtocols.Backend.CDP) throw new UnsupportedProtocolOperation("logpoint", backend.id(), null);
            return backend.id();
        });
        CdpSession cdp = host.cdp();
        if (f.containsKey("clear")) {
            int removed = 0;
            for (Map.Entry<String, List<String[]>> e : points.entrySet()) {
                for (String[] p : e.getValue()) {
                    for (CdpSession.Target t : cdp.pages()) {
                        try {
                            cdp.send(t.sessionId, "Debugger.removeBreakpoint", Map.of("breakpointId", p[1]));
                        } catch (RuntimeException ignored) {
                            // Not set on this page.
                        }
                    }
                    removed++;
                }
            }
            points.clear();
            return ProtoJson.obj().put("op", "logpoint").put("via", "cdp").put("cleared", removed);
        }
        String at = f.getOrDefault("at", "");
        String expr = f.getOrDefault("expr", "");
        Matcher m = AT.matcher(at);
        if (!m.matches() || expr.isBlank()) {
            throw new IllegalArgumentException("error: logpoint needs --at=<url|path>:<line>[:<col>] and --expr=<js>\nfix: logpoint --at=app.js:12 --expr=user.id");
        }
        String where = m.group(1);
        int line = Integer.parseInt(m.group(2));
        Integer column = m.group(3) == null ? null : Integer.parseInt(m.group(3)) - 1;
        String id = "lp" + (points.size() + 1);
        String condition = "(console.log('[logpoint " + id + "]', (function(){try{return (" + expr + ");}catch(e){return 'logpoint error: '+e;}})()), false)";
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("lineNumber", line - 1);
        if (where.matches("^[a-z]+://.*")) params.put("url", where);
        else params.put("urlRegex", ".*" + where.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0") + "$");
        if (column != null) params.put("columnNumber", column);
        params.put("condition", condition);
        List<String[]> set = new ArrayList<>();
        for (CdpSession.Target t : cdp.pages()) {
            cdp.send(t.sessionId, "Debugger.enable", Map.of());
            JsonNode reply = cdp.send(t.sessionId, "Debugger.setBreakpointByUrl", params);
            set.add(new String[]{id, reply.path("breakpointId").asText(), at, expr, Integer.toString(reply.path("locations").size())});
        }
        points.computeIfAbsent(id, k -> new ArrayList<>()).addAll(set);
        host.log("logpoint " + id + " at=" + at);
        int resolved = set.stream().mapToInt(s -> Integer.parseInt(s[4])).sum();
        return ProtoJson.obj().put("op", "logpoint").put("via", "cdp").put("id", id).put("at", at).put("pages", set.size())
                .put("resolved", resolved).put("note", "output arrives as console rows starting [logpoint " + id + "]");
    }

    private static ObjectNode list(Map<String, List<String[]>> points) {
        ObjectNode out = ProtoJson.obj().put("op", "logpoint");
        ArrayNode rows = out.putArray("logpoints");
        for (Map.Entry<String, List<String[]>> e : points.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String[] p = e.getValue().get(0);
            rows.addObject().put("id", e.getKey()).put("at", p[2]).put("expr", p[3]).put("pages", e.getValue().size());
        }
        return out;
    }
}
