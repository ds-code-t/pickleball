package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CDP events one {@link BrowserSession} saw on its own socket since it
 * attached or launched: {@code Network.*}, {@code Page.frameNavigated},
 * {@code Runtime.consoleAPICalled}, and {@code Log.entryAdded}. This is the
 * Java form of the Rust composite Session event deque that {@code observe}
 * read. Rows use the Rust projection: {@code method}, {@code kind},
 * {@code url}, {@code text}, {@code level}, {@code at} (unix ms),
 * {@code status}, {@code mime}, {@code httpMethod}, {@code failed}.
 * The buffer is bounded; the oldest rows drop first.
 */
public final class SessionEvents {
    /** Same cap as the Rust Session deque. */
    public static final int CAPACITY = 2000;
    /** Longest {@code --wait-ms} honoured, as in Rust. */
    public static final long MAX_WAIT_MS = 30_000;
    private static final int INFLIGHT_CAP = 4096;

    private final int capacity;
    private final ArrayDeque<ObjectNode> rows = new ArrayDeque<>();
    private final Map<String, String[]> inflight = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > INFLIGHT_CAP;
        }
    };
    private long total;
    private long dropped;

    public SessionEvents() {
        this(CAPACITY);
    }

    public SessionEvents(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** CDP event frame from the session socket. Rows the projection drops are ignored. */
    public void accept(JsonNode message) {
        if (message == null) return;
        ObjectNode row;
        synchronized (this) {
            row = project(message, inflight);
            if (row == null) return;
            rows.addLast(row);
            total++;
            while (rows.size() > capacity) {
                rows.removeFirst();
                dropped++;
            }
            notifyAll();
        }
    }

    public synchronized List<ObjectNode> snapshot() {
        List<ObjectNode> copy = new ArrayList<>(rows.size());
        for (ObjectNode row : rows) copy.add(row.deepCopy());
        return copy;
    }

    public synchronized long total() {
        return total;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized int size() {
        return rows.size();
    }

    /**
     * Filtered rows. A positive {@code waitMs} waits up to that long (capped at
     * {@link #MAX_WAIT_MS}) for at least one row to match; a timeout returns
     * the empty match, which is empty traffic, not a failure.
     */
    public List<ObjectNode> read(Filter filter, long waitMs) {
        long budget = Math.min(Math.max(0, waitMs), MAX_WAIT_MS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
        synchronized (this) {
            while (true) {
                List<ObjectNode> kept = filter(snapshot(), filter);
                if (!kept.isEmpty()) return kept;
                long left = deadline - System.nanoTime();
                if (left <= 0) return kept;
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return kept;
                }
            }
        }
    }

    public record Filter(String kind, String url, Long since, Long until, int limit) {
        public static Filter of(Map<String, String> flags) {
            return new Filter(
                    blank(flags.get("type")),
                    blank(flags.get("url")),
                    longOrNull(flags.get("since")),
                    longOrNull(flags.get("until")),
                    intOr(flags.get("limit"), 100)
            );
        }
    }

    /**
     * Rust {@code observe::filter_events}: kind or CDP method substring
     * (case-insensitive), url-or-text substring, inclusive {@code at} window,
     * newest {@code limit} rows ({@code 0} keeps every match).
     */
    public static List<ObjectNode> filter(List<ObjectNode> events, Filter filter) {
        List<ObjectNode> kept = new ArrayList<>();
        for (ObjectNode event : events) {
            if (!matchesKind(event, filter.kind())) continue;
            if (filter.url() != null && !filter.url().isEmpty()) {
                String hay = (event.path("url").asText("") + " " + event.path("text").asText("")).toLowerCase(Locale.ROOT);
                if (!hay.contains(filter.url().toLowerCase(Locale.ROOT))) continue;
            }
            JsonNode at = event.get("at");
            if (at != null && at.canConvertToLong()) {
                long value = at.asLong();
                if (filter.since() != null && value < filter.since()) continue;
                if (filter.until() != null && value > filter.until()) continue;
            }
            kept.add(event);
        }
        if (filter.limit() > 0 && kept.size() > filter.limit()) {
            return new ArrayList<>(kept.subList(kept.size() - filter.limit(), kept.size()));
        }
        return kept;
    }

    private static boolean matchesKind(JsonNode event, String kind) {
        if (kind == null || kind.isBlank()) return true;
        String needle = kind.trim().toLowerCase(Locale.ROOT);
        String method = event.path("method").asText("").toLowerCase(Locale.ROOT);
        String stored = event.path("kind").asText("");
        return method.contains(needle) || stored.equalsIgnoreCase(needle);
    }

    /** Projection without request bookkeeping; {@code loadingFinished/Failed} keep an empty url. */
    public static ObjectNode project(JsonNode message) {
        return project(message, new LinkedHashMap<>());
    }

    static ObjectNode project(JsonNode message, Map<String, String[]> inflight) {
        String method = message.path("method").asText("");
        JsonNode p = message.path("params");
        long at = wallMs(p);
        switch (method) {
            case "Network.requestWillBeSent" -> {
                String url = p.path("request").path("url").asText("");
                if (ignoredUrl(url)) return null;
                String httpMethod = p.path("request").path("method").asText("GET");
                String id = p.path("requestId").asText("");
                if (!id.isEmpty()) inflight.put(id, new String[]{url, httpMethod});
                ObjectNode row = row(method, "network", url, "", "", at);
                row.put("httpMethod", httpMethod);
                return row;
            }
            case "Network.responseReceived" -> {
                String url = p.path("response").path("url").asText("");
                if (ignoredUrl(url)) return null;
                ObjectNode row = row(method, "network", url, "", "", at);
                JsonNode status = p.path("response").path("status");
                if (status.isNumber()) row.put("status", status.asInt());
                row.put("mime", p.path("response").path("mimeType").asText(""));
                String[] known = inflight.get(p.path("requestId").asText(""));
                if (known != null) row.put("httpMethod", known[1]);
                return row;
            }
            case "Network.loadingFinished", "Network.loadingFailed" -> {
                String id = p.path("requestId").asText("");
                String[] known = inflight.remove(id);
                // Unknown id: a request before attach, or a data:/blob:/chrome: request; url stays empty as in Rust.
                String url = known == null ? "" : known[0];
                boolean failed = "Network.loadingFailed".equals(method);
                String text = failed ? p.path("errorText").asText("") : "";
                ObjectNode row = row(method, "network", url, text, failed ? "error" : "", at);
                row.put("failed", failed);
                if (known != null) row.put("httpMethod", known[1]);
                return row;
            }
            case "Page.frameNavigated" -> {
                JsonNode frame = p.path("frame");
                String url = frame.path("url").asText("") + frame.path("urlFragment").asText("");
                ObjectNode row = row(method, "navigation", url, frame.path("name").asText(""), "", at);
                row.put("mime", frame.path("mimeType").asText(""));
                row.put("top", !frame.hasNonNull("parentId"));
                return row;
            }
            case "Page.navigatedWithinDocument" -> {
                return row(method, "navigation", p.path("url").asText(""), "", "", at);
            }
            case "Runtime.consoleAPICalled" -> {
                List<String> parts = new ArrayList<>();
                for (JsonNode arg : p.path("args")) {
                    JsonNode value = arg.get("value");
                    if (value != null && value.isTextual()) parts.add(value.asText());
                    else if (arg.hasNonNull("description")) parts.add(arg.get("description").asText());
                    else if (value != null) parts.add(value.toString());
                    else parts.add("");
                }
                String url = p.path("stackTrace").path("callFrames").path(0).path("url").asText("");
                return row(method, "console", url, String.join(" ", parts), p.path("type").asText("log"), at);
            }
            case "Log.entryAdded" -> {
                JsonNode entry = p.path("entry");
                return row(method, "log", entry.path("url").asText(""), entry.path("text").asText(""),
                        entry.path("level").asText("info"), at);
            }
            default -> {
                return null;
            }
        }
    }

    public static ArrayNode toArray(List<ObjectNode> rows) {
        ArrayNode array = Jsons.MAPPER.createArrayNode();
        rows.forEach(array::add);
        return array;
    }

    private static ObjectNode row(String method, String kind, String url, String text, String level, long at) {
        ObjectNode row = Jsons.object();
        row.put("method", method);
        row.put("kind", kind);
        row.put("url", url);
        row.put("text", text);
        row.put("level", level);
        row.putNull("status");
        row.put("mime", "");
        row.put("failed", false);
        row.put("at", at);
        return row;
    }

    /** Rust {@code wall_ms}: {@code wallTime} seconds, then an epoch-sized {@code timestamp}, then now. */
    static long wallMs(JsonNode params) {
        JsonNode wall = params.get("wallTime");
        if (wall != null && wall.isNumber()) return (long) (wall.asDouble() * 1000.0);
        JsonNode stamp = params.get("timestamp");
        if (stamp == null) stamp = params.path("entry").get("timestamp");
        if (stamp != null && stamp.isNumber()) {
            double value = stamp.asDouble();
            if (value > 10_000_000_000.0) return (long) value;
            if (value > 1_000_000_000.0) return (long) (value * 1000.0);
        }
        return System.currentTimeMillis();
    }

    /** Rust {@code ignored_url}. */
    static boolean ignoredUrl(String url) {
        return url.startsWith("data:")
                || url.startsWith("blob:")
                || url.startsWith("chrome-extension:")
                || url.startsWith("devtools:")
                || url.startsWith("chrome:");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long longOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException failure) {
            throw new dev.domengine.cdp.CdpException("invalid number: " + value);
        }
    }

    private static int intOr(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new dev.domengine.cdp.CdpException("invalid number: " + value);
        }
    }
}
