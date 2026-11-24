package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domcomposite.Client;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads CDP events the engine Session already buffered. A missing binary
 * or a binary that cannot observe is a failure, not an empty buffer.
 * {@code --events} is a recorded snapshot of that same shape, used by tests
 * and capture. An empty snapshot after a successful read is empty traffic.
 */
public final class ObserveEvents {
    private ObserveEvents() {
    }

    public record Event(
            String method,
            String kind,
            String url,
            String text,
            String level,
            Long at,
            Integer status,
            String mime,
            String httpMethod,
            boolean failed
    ) {
    }

    public static List<Event> read(Path project, String runId, Map<String, String> args) throws IOException {
        List<Event> events = new ArrayList<>();
        String file = args.get("events");
        String har = args.get("har");
        if (har != null && !har.isBlank()) {
            // E4: capture-scenario --har=<file> reads HAR 1.2 entries as request events.
            Path path = Path.of(har);
            if (!path.isAbsolute() && project != null) path = project.resolve(har);
            events.addAll(fromHar(Files.readString(path, StandardCharsets.UTF_8)));
        }
        if (har != null && !har.isBlank() && (file == null || file.isBlank())) {
            // HAR only.
        } else if (file != null && !file.isBlank()) {
            Path path = Path.of(file);
            if (!path.isAbsolute() && project != null) path = project.resolve(file);
            events.addAll(parse(Files.readString(path, StandardCharsets.UTF_8)));
        } else {
            dev.domengine.session.SessionEvents buffer = tools.dscode.common.agentfast.LiveBrowserHost.events();
            if (buffer != null) {
                Map<String, String> bufferFlags = args;
                if (present(args.get("request")) || present(args.get("response"))) {
                    bufferFlags = new java.util.LinkedHashMap<>(args);
                    bufferFlags.put("limit", "0");
                }
                events.addAll(fromBuffer(buffer.read(dev.domengine.session.SessionEvents.Filter.of(bufferFlags), waitMs(args))));
            } else {
                events.addAll(fromEngine(args));
            }
        }
        List<Event> filtered = filter(events, args);
        if (filtered.isEmpty()) {
            CompositeLog.info("observe", "empty type=" + text(args.get("type"))
                    + " url=" + text(args.get("url"))
                    + " count=0");
        } else {
            CompositeLog.info("observe", "filter type=" + text(args.get("type"))
                    + " url=" + text(args.get("url"))
                    + " count=" + filtered.size());
        }
        Path out = AgentStore.root(project, runId).resolve("observe.json");
        AgentStore.ensure(out.getParent());
        AgentStore.JSON.writeValue(out.toFile(), filtered.stream().map(ObserveEvents::toMap).toList());
        return filtered;
    }

    /**
     * Rows already on the start host's {@code BrowserSession} buffer. An empty
     * list is empty traffic, not a missing session.
     */
    static List<Event> fromBuffer(List<? extends JsonNode> rows) throws IOException {
        if (rows == null || rows.isEmpty()) return List.of();
        return parse(AgentStore.JSON.writeValueAsString(rows));
    }

    private static long waitMs(Map<String, String> args) {
        String wait = args.get("wait-ms");
        if (wait == null || wait.isBlank()) wait = args.get("waitMs");
        if (wait == null || wait.isBlank()) return 0L;
        try {
            return Long.parseLong(wait.trim());
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }

    static List<Event> fromEngine(Map<String, String> args) throws IOException {
        Client client = CompositeRuntime.client();
        if (client == null) {
            throw new IOException("observe failed: no engine CDP Session is attached");
        }
        if (!engineCanObserve(client)) {
            throw new IOException("observe failed: this engine binary does not expose the CDP Session buffer");
        }
        List<String> argv = new ArrayList<>();
        argv.add("observe");
        String cdp = args.get("cdp");
        if (cdp != null && !cdp.isBlank()) {
            argv.add("--cdp");
            argv.add(cdp);
        }
        String session = args.get("session");
        if ((session == null || session.isBlank()) && (cdp == null || cdp.isBlank())) {
            session = CompositeRuntime.focusedSessionId();
        }
        if (session != null && !session.isBlank()) {
            argv.add("--session");
            argv.add(session);
        }
        put(argv, "--type", args.get("type"));
        put(argv, "--url", args.get("url"));
        put(argv, "--request", args.get("request"));
        put(argv, "--response", args.get("response"));
        put(argv, "--since", args.get("since"));
        put(argv, "--until", args.get("until"));
        if ((present(args.get("request")) || present(args.get("response"))) && !present(args.get("limit"))) {
            put(argv, "--limit", "0");
        } else {
            put(argv, "--limit", args.get("limit"));
        }
        String wait = args.get("wait-ms");
        if (wait == null || wait.isBlank()) wait = args.get("waitMs");
        put(argv, "--wait-ms", wait);
        JsonNode node;
        try {
            node = client.call(argv);
        } catch (RuntimeException failure) {
            throw new IOException("observe failed: " + failure.getMessage(), failure);
        }
        return requireBuffer(node);
    }

    /**
     * A failed engine observe is not zero events. An {@code ok} buffer with no
     * rows is empty traffic.
     */
    static List<Event> requireBuffer(JsonNode node) throws IOException {
        if (node != null && node.path("ok").isBoolean() && !node.path("ok").asBoolean()) {
            String error = node.path("error").asText("engine rejected observe");
            throw new IOException("observe failed: " + error);
        }
        if (node == null || (!node.isArray() && !node.has("events"))) {
            throw new IOException("observe failed: engine returned no event buffer");
        }
        return parse(node.toString());
    }

    private static boolean engineCanObserve(Client client) throws IOException {
        JsonNode caps;
        try {
            caps = client.capabilities();
        } catch (RuntimeException failure) {
            throw new IOException("observe failed: " + failure.getMessage(), failure);
        }
        JsonNode commands = caps == null ? null : caps.get("commands");
        if (commands == null || !commands.isArray()) {
            throw new IOException("observe failed: engine capabilities do not list commands");
        }
        for (JsonNode command : commands) {
            if ("observe".equals(command.asText())) return true;
        }
        return false;
    }

    private static void put(List<String> argv, String flag, String value) {
        if (value != null && !value.isBlank()) {
            argv.add(flag);
            argv.add(value);
        }
    }

    public static List<Event> filter(List<Event> events, Map<String, String> args) {
        String type = blank(args.get("type"));
        String url = blank(args.get("url"));
        Long since = longOrNull(args.get("since"));
        Long until = longOrNull(args.get("until"));
        int limit = intOr(args.get("limit"), 100);
        List<Event> kept = new ArrayList<>();
        String request = blank(args.get("request"));
        String response = blank(args.get("response"));
        for (Event event : events) {
            if (type != null && !matchesType(event, type)) continue;
            if (url != null) {
                String hay = (event.url() + " " + event.text()).toLowerCase(Locale.ROOT);
                if (!hay.contains(url.toLowerCase(Locale.ROOT))) continue;
            }
            if (!matchesTraffic(event, request, response)) continue;
            if (event.at() != null && since != null && event.at() < since) continue;
            if (event.at() != null && until != null && event.at() > until) continue;
            kept.add(event);
        }
        if (limit > 0 && kept.size() > limit) {
            return List.copyOf(kept.subList(kept.size() - limit, kept.size()));
        }
        return List.copyOf(kept);
    }

    private static boolean matchesType(Event event, String type) {
        String needle = type.toLowerCase(Locale.ROOT);
        String method = event.method() == null ? "" : event.method().toLowerCase(Locale.ROOT);
        String kind = event.kind() == null ? "" : event.kind().toLowerCase(Locale.ROOT);
        return method.contains(needle) || kind.equals(needle);
    }

    /**
     * {@code --request} keeps {@code Network.requestWillBeSent}. {@code --response}
     * keeps response and loading rows. A blank value, {@code *}, {@code true}, or
     * {@code 1} keeps every row of that kind. Anything else is a substring of
     * the url, text, HTTP method, CDP method, or status. Both flags together
     * keep a row that matches either one.
     */
    static boolean matchesTraffic(Event event, String request, String response) {
        boolean wantRequest = request != null;
        boolean wantResponse = response != null;
        if (!wantRequest && !wantResponse) return true;
        boolean requestRow = isRequest(event);
        boolean responseRow = isResponse(event);
        if (wantRequest && requestRow && trafficNeedle(event, request)) return true;
        if (wantResponse && responseRow && trafficNeedle(event, response)) return true;
        return false;
    }

    private static boolean isRequest(Event event) {
        String method = event.method() == null ? "" : event.method();
        return method.contains("requestWillBeSent") || "request".equalsIgnoreCase(event.kind());
    }

    private static boolean isResponse(Event event) {
        String method = event.method() == null ? "" : event.method();
        return method.contains("responseReceived")
                || method.contains("loadingFinished")
                || method.contains("loadingFailed")
                || "response".equalsIgnoreCase(event.kind());
    }

    private static boolean trafficNeedle(Event event, String needle) {
        if (needle == null || needle.isBlank() || "*".equals(needle) || "true".equalsIgnoreCase(needle) || "1".equals(needle)) {
            return true;
        }
        String hay = (event.url() + " " + event.text() + " " + event.httpMethod() + " " + event.method()
                + " " + (event.status() == null ? "" : event.status())).toLowerCase(Locale.ROOT);
        return hay.contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** HAR 1.2 entries as {@code Network.requestWillBeSent} events, in entry order. */
    public static List<Event> fromHar(String json) throws IOException {
        JsonNode entries = AgentStore.JSON.readTree(json).path("log").path("entries");
        List<Event> events = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode request = entry.path("request");
            JsonNode response = entry.path("response");
            Long at = null;
            try {
                at = java.time.Instant.parse(entry.path("startedDateTime").asText()).toEpochMilli();
            } catch (RuntimeException ignored) {
                at = null;
            }
            events.add(new Event("Network.requestWillBeSent", "network", request.path("url").asText(""), "", "", at,
                    response.path("status").isNumber() ? response.path("status").asInt() : null,
                    response.path("content").path("mimeType").asText(""), request.path("method").asText("GET"),
                    entry.has("_error")));
        }
        return events;
    }

    public static List<Event> parse(String json) throws IOException {
        JsonNode root = AgentStore.JSON.readTree(json == null || json.isBlank() ? "[]" : json);
        JsonNode array = root.isArray() ? root : root.path("events");
        List<Event> events = new ArrayList<>();
        if (!array.isArray()) return events;
        for (JsonNode node : array) {
            events.add(new Event(
                    text(node.path("method").asText(null)),
                    text(node.path("kind").asText(null)),
                    text(node.path("url").asText("")),
                    text(node.path("text").asText("")),
                    text(node.path("level").asText("")),
                    node.path("at").isNumber() ? Long.valueOf(node.path("at").asLong()) : longOrNull(node.path("at").asText(null)),
                    node.path("status").isNumber() ? node.path("status").asInt() : null,
                    text(node.path("mime").asText("")),
                    text(node.path("httpMethod").asText(node.path("methodName").asText(""))),
                    node.path("failed").asBoolean(false)
            ));
        }
        return events;
    }

    public static Map<String, Object> toMap(Event event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", event.method());
        map.put("kind", event.kind());
        map.put("url", event.url());
        map.put("text", event.text());
        map.put("level", event.level());
        map.put("at", event.at());
        map.put("status", event.status());
        map.put("mime", event.mime());
        map.put("httpMethod", event.httpMethod());
        map.put("failed", event.failed());
        return map;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long longOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static int intOr(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }
}
