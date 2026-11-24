package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.BrowserProtocols;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One per live session: a bounded ring of {@code urn:pickleball:browser-event:1} rows with a monotonic {@code seq}
 * cursor. Backend projectors call {@link #add}; rows are redacted before they land, deduped across backends, and
 * spilled to {@code .pickleball/runs/<run>/browser-events-YYYY-MM-DD.ndjson} (size-capped, one rotation).
 * {@code pkb_browser_events=off} stops ring and spill; {@code pkb_browser_events_kinds=} keeps the listed kinds.
 */
public final class BrowserEventHub {
    public static final String SCHEMA = "urn:pickleball:browser-event:1";
    public static final List<String> KINDS = List.of("net", "console", "error", "ws", "dialog", "download", "file-dialog",
            "nav", "target", "perf", "security", "audit", "sw", "storage", "permission", "input", "dom");
    static final int TEXT_MAX = 2000;

    private final Path project;
    private final String session;
    private final String run;
    private final int capacity;
    private final long maxBytes;
    private final Deque<ObjectNode> ring = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Long> recent = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 2048;
        }
    };
    private final List<Consumer<ObjectNode>> taps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Set<String> kinds;
    private volatile boolean enabled;
    private volatile String primary;
    private volatile String step;
    private volatile String scenario;

    public BrowserEventHub(Path project, String session, String run) {
        this.project = project;
        this.session = session;
        this.run = run == null || run.isBlank() ? "live" : run;
        this.capacity = intSetting("pkb_browser_events_ring", 5000);
        this.maxBytes = intSetting("pkb_browser_events_max_kb", 8192) * 1024L;
        String toggle = BrowserProtocols.setting("pkb_browser_events");
        this.enabled = toggle == null || !Set.of("off", "false", "no", "0").contains(toggle.toLowerCase(Locale.ROOT));
        this.kinds = parseKinds(BrowserProtocols.setting("pkb_browser_events_kinds"));
    }

    static int intSetting(String key, int fallback) {
        try {
            String value = BrowserProtocols.setting(key);
            return value == null ? fallback : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    static Set<String> parseKinds(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) return null;
        return new LinkedHashSet<>(Arrays.stream(raw.split("[,\\s]+")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList());
    }

    public Path project() {
        return project;
    }

    public String session() {
        return session;
    }

    public String run() {
        return run;
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<String> kinds() {
        return kinds;
    }

    /** Subscribed kinds (null = all). Called when browser-events --start names kinds. */
    public void kinds(Set<String> next) {
        kinds = next == null || next.isEmpty() ? null : new LinkedHashSet<>(next);
    }

    public void enabled(boolean on) {
        enabled = on;
    }

    /** The backend that served the subscription; its rows win a dedupe. */
    public void primary(String source) {
        primary = source;
    }

    public void context(String nextStep, String nextScenario) {
        step = nextStep;
        scenario = nextScenario;
    }

    public void tap(Consumer<ObjectNode> listener) {
        taps.add(listener);
    }

    public void untap(Consumer<ObjectNode> listener) {
        taps.remove(listener);
    }

    public long cursor() {
        return seq.get();
    }

    /** A fresh row with the common fields; callers fill the rest and pass it to {@link #add}. */
    public ObjectNode row(String source, String kind, String subkind) {
        ObjectNode row = ProtoJson.obj();
        row.put("source", source);
        row.put("kind", kind);
        if (subkind != null) row.put("subkind", subkind);
        return row;
    }

    /** Adds a row unless off, filtered, or a duplicate. Returns the stored row (with seq) or null. */
    public synchronized ObjectNode add(ObjectNode row) {
        if (!enabled || row == null) return null;
        String kind = row.path("kind").asText("");
        if (kinds != null && !kinds.contains(kind)) return null;
        String key = dedupeKey(row);
        long now = System.currentTimeMillis();
        if (key != null) {
            Long seen = recent.get(key);
            boolean textKey = key.startsWith("t|");
            if (seen != null && (!textKey || now - seen <= 50)) {
                String source = row.path("source").asText("");
                if (primary == null || !primary.equals(source)) return null;
            }
            recent.put(key, now);
        }
        ObjectNode stored = ProtoJson.obj();
        stored.put("seq", seq.incrementAndGet());
        stored.put("at", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        stored.put("session", session);
        stored.put("run", run);
        if (step != null) stored.put("step", step);
        if (scenario != null) stored.put("scenario", scenario);
        stored.setAll(row);
        String text = stored.path("text").asText(null);
        if (text != null) {
            String scrubbed = ProtoRedact.text(text);
            if (!scrubbed.equals(text)) redacted(stored).add("text");
            stored.put("text", scrubbed.length() > TEXT_MAX ? scrubbed.substring(0, TEXT_MAX) + "…" : scrubbed);
        }
        if (stored.hasNonNull("url")) stored.put("url", ProtoRedact.url(project, stored.get("url").asText(), redacted(stored)));
        for (String headers : new String[]{"requestHeaders", "responseHeaders"}) {
            JsonNode raw = stored.get(headers);
            if (raw == null) continue;
            if (raw.isMissingNode() || raw.isNull() || raw.isEmpty()) stored.remove(headers);
            else stored.set(headers, ProtoRedact.headers(raw, redacted(stored), false));
        }
        if (stored.has("redacted") && stored.get("redacted").isEmpty()) stored.remove("redacted");
        ring.addLast(stored);
        while (ring.size() > capacity) ring.removeFirst();
        spill(stored);
        for (Consumer<ObjectNode> tap : taps) {
            try {
                tap.accept(stored);
            } catch (RuntimeException ignored) {
                // A tap never stops the stream.
            }
        }
        notifyAll();
        return stored;
    }

    static ArrayNode redacted(ObjectNode row) {
        JsonNode existing = row.get("redacted");
        if (existing instanceof ArrayNode array) return array;
        return row.putArray("redacted");
    }

    private static String dedupeKey(ObjectNode row) {
        String requestId = row.path("requestId").asText("");
        String kind = row.path("kind").asText("");
        String subkind = row.path("subkind").asText("");
        if (!requestId.isBlank()) return "r|" + kind + "|" + subkind + "|" + requestId;
        String text = row.path("text").asText("");
        if (!text.isBlank() && ("console".equals(kind) || "error".equals(kind))) return "t|" + kind + "|" + text;
        return null;
    }

    /** Rows after {@code since} (a seq number or an ISO time), filtered; waits up to waitMs for the first one. */
    public synchronized List<ObjectNode> read(String since, Set<String> onlyKinds, String match, String level, int limit, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        List<ObjectNode> out = select(since, onlyKinds, match, level, limit);
        while (out.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                wait(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            out = select(since, onlyKinds, match, level, limit);
        }
        return out;
    }

    private List<ObjectNode> select(String since, Set<String> onlyKinds, String match, String level, int limit) {
        long afterSeq = 0;
        OffsetDateTime afterTime = null;
        if (since != null && !since.isBlank()) {
            String s = since.trim();
            if (s.matches("\\d+")) afterSeq = Long.parseLong(s);
            else afterTime = OffsetDateTime.parse(s);
        }
        Pattern regex = match != null && match.startsWith("re:") ? Pattern.compile(match.substring(3)) : null;
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode row : ring) {
            if (row.path("seq").asLong() <= afterSeq) continue;
            if (afterTime != null && !OffsetDateTime.parse(row.path("at").asText()).isAfter(afterTime)) continue;
            if (onlyKinds != null && !onlyKinds.contains(row.path("kind").asText())) continue;
            if (level != null && !level.isBlank() && !level.equalsIgnoreCase(row.path("level").asText(""))) continue;
            if (match != null && !match.isBlank()) {
                String hay = row.path("text").asText("") + " " + row.path("url").asText("") + " " + row.path("subkind").asText("");
                if (regex != null ? !regex.matcher(hay).find() : !hay.contains(match)) continue;
            }
            out.add(row);
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    public Path spillFile() {
        return project.resolve(".pickleball").resolve("runs").resolve(run).resolve("browser-events-" + LocalDate.now() + ".ndjson");
    }

    private void spill(ObjectNode row) {
        Path file = spillFile();
        try {
            Files.createDirectories(file.getParent());
            byte[] bytes = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            long size = Files.isRegularFile(file) ? Files.size(file) : 0;
            if (size + bytes.length > maxBytes) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The ring still has the row.
        }
    }
}
