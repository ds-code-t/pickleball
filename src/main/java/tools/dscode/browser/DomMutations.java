package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * E2b push-based DOM change listener. One page script ({@code dom-mutations.js}) runs a MutationObserver and reports
 * debounced, batched, scoped deltas through whichever push channel the backend has: a CDP {@code Runtime.addBinding}
 * binding, a BiDi {@code script.message} channel, or a Playwright exposed binding. Pickleball's own surfaces
 * ({@code data-pkb-*}) are excluded unless {@link Options#own()} is set.
 */
public final class DomMutations {
    public static final List<String> KINDS = List.of("childList", "attributes", "text");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong();
    private static volatile String script;

    private DomMutations() {
    }

    /** What to observe. {@code selector} blank means the whole document. */
    public record Options(String selector, List<String> kinds, List<String> attributes, String text, long debounceMs, boolean own) {
        public Options {
            selector = selector == null ? "" : selector.trim();
            kinds = kinds == null || kinds.isEmpty() ? KINDS : List.copyOf(kinds);
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            text = text == null ? "" : text;
            debounceMs = Math.max(0, Math.min(debounceMs, 10_000));
        }

        public static Options defaults() {
            return new Options("", KINDS, List.of(), "", 50, false);
        }

        /** {@code --selector= --kinds= --attributes= --text= --debounce= --own}. Unknown kinds are an error. */
        public static Options of(Map<String, String> args) {
            Map<String, String> a = args == null ? Map.of() : args;
            List<String> kinds = new ArrayList<>();
            String raw = a.getOrDefault("kinds", "");
            for (String part : raw.split("[,\\s]+")) {
                String kind = part.trim();
                if (kind.isEmpty()) continue;
                String canonical = canonicalKind(kind);
                if (canonical == null) {
                    throw new IllegalArgumentException("error: unknown DOM change kind " + kind
                            + "\nfix: kinds are childList,attributes,text");
                }
                if (!kinds.contains(canonical)) kinds.add(canonical);
            }
            List<String> attrs = new ArrayList<>();
            for (String part : a.getOrDefault("attributes", "").split(",")) if (!part.isBlank()) attrs.add(part.trim());
            long debounce = 50;
            String d = a.get("debounce");
            if (d != null && !d.isBlank()) {
                try {
                    debounce = Long.parseLong(d.trim().replaceAll("ms$", ""));
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException("error: --debounce is milliseconds\nfix: --debounce=100");
                }
            }
            boolean own = "true".equalsIgnoreCase(a.get("own")) || "1".equals(a.get("own"));
            return new Options(a.getOrDefault("selector", ""), kinds, attrs, a.getOrDefault("text", ""), debounce, own);
        }
    }

    /** childList, attributes or text; {@code characterData} and {@code child-list} are accepted aliases. */
    public static String canonicalKind(String kind) {
        String k = kind.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (k) {
            case "childlist", "children", "child" -> "childList";
            case "attributes", "attribute", "attr", "attrs" -> "attributes";
            case "text", "characterdata", "chardata" -> "text";
            default -> null;
        };
    }

    /** True when a {@code --kinds} value names only DOM change kinds (so watch uses the DOM listener). */
    public static boolean domKinds(String raw) {
        if (raw == null || raw.isBlank()) return false;
        for (String part : raw.split("[,\\s]+")) if (!part.isBlank() && canonicalKind(part.trim()) == null) return false;
        return true;
    }

    /** The page function source, called as {@code (send, cfg)}. */
    public static String function() {
        String s = script;
        if (s != null) return s;
        try (InputStream in = DomMutations.class.getResourceAsStream("/tools/dscode/browser/dom-mutations.js")) {
            if (in == null) throw new IllegalStateException("dom-mutations.js is missing from the jar");
            s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        script = s;
        return s;
    }

    public static String nextId() {
        return "w" + ProcessHandle.current().pid() + "_" + IDS.incrementAndGet();
    }

    /** Config object for the page function. */
    public static String config(Options o, String id) {
        ObjectNode cfg = JSON.createObjectNode();
        cfg.put("id", id).put("selector", o.selector()).put("text", o.text()).put("debounce", o.debounceMs()).put("own", o.own());
        cfg.set("kinds", JSON.valueToTree(o.kinds()));
        cfg.set("attributes", JSON.valueToTree(o.attributes()));
        return cfg.toString();
    }

    /** Expression that installs the observer and sends through {@code window[binding]}. */
    public static String bindingExpression(Options o, String id, String binding) {
        return "(" + function() + ")(function(s){window['" + binding + "'](s);}," + config(o, id) + ")";
    }

    /** Expression that disconnects watch {@code id}. */
    public static String disconnectExpression(String id) {
        return "(function(){var w=window['__pkbDomWatch_" + id + "'];if(w)w.disconnect();return true;})()";
    }

    /** A parsed batch, or null when the payload is not one. */
    public static ObjectNode parse(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            return node instanceof ObjectNode object ? object : null;
        } catch (IOException bad) {
            return null;
        }
    }

    /** Short text for a batch row: counts and the first targets. */
    public static String summary(JsonNode batch) {
        StringBuilder out = new StringBuilder();
        for (String kind : KINDS) {
            int n = batch.path(kind).asInt(0);
            if (n > 0) out.append(out.isEmpty() ? "" : " ").append(kind).append('=').append(n);
        }
        if (batch.path("added").asInt(0) > 0) out.append(" added=").append(batch.path("added").asInt());
        if (batch.path("removed").asInt(0) > 0) out.append(" removed=").append(batch.path("removed").asInt());
        Set<String> targets = new LinkedHashSet<>();
        batch.path("targets").forEach(t -> targets.add(t.asText()));
        if (!targets.isEmpty()) out.append(" in ").append(String.join(",", targets));
        return out.toString();
    }

    /** Flat map view of a batch for replies. */
    public static Map<String, Object> view(JsonNode batch) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "dom");
        row.put("subkind", "mutation");
        for (String key : List.of("seq", "records", "childList", "attributes", "text", "added", "removed")) {
            if (batch.has(key)) row.put(key, batch.path(key).asLong());
        }
        List<String> targets = new ArrayList<>();
        batch.path("targets").forEach(t -> targets.add(t.asText()));
        row.put("targets", targets);
        List<String> names = new ArrayList<>();
        batch.path("attributeNames").forEach(t -> names.add(t.asText()));
        if (!names.isEmpty()) row.put("attributeNames", names);
        String sample = batch.path("sample").asText("");
        if (!sample.isBlank()) row.put("sample", sample);
        row.put("summary", summary(batch));
        if (batch.has("receivedAt")) row.put("receivedAt", batch.path("receivedAt").asLong());
        if (batch.has("receivedNanos")) {
            row.put("pushLagMs", Math.max(0, batch.path("at").asLong() - batch.path("firstAt").asLong()));
        }
        return row;
    }

    /**
     * A watch over one page's CDP socket (dom-engine connection): {@code Runtime.addBinding} plus the observer.
     * {@link #next} blocks until a batch is pushed; nothing is polled.
     */
    public static final class CdpWatch implements AutoCloseable {
        private final CdpConnection cdp;
        private final String id;
        private final String binding;
        private final LinkedBlockingQueue<ObjectNode> batches = new LinkedBlockingQueue<>();
        private final Consumer<JsonNode> listener;
        private volatile boolean closed;

        private CdpWatch(CdpConnection cdp, String id, String binding) {
            this.cdp = cdp;
            this.id = id;
            this.binding = binding;
            this.listener = message -> {
                if (!"Runtime.bindingCalled".equals(message.path("method").asText())) return;
                JsonNode params = message.path("params");
                if (!binding.equals(params.path("name").asText())) return;
                ObjectNode batch = parse(params.path("payload").asText(""));
                if (batch == null || !id.equals(batch.path("id").asText())) return;
                batch.put("receivedNanos", System.nanoTime());
                batch.put("receivedAt", System.currentTimeMillis());
                batches.add(batch);
            };
        }

        /** Installs the binding and the observer. Throws {@link UnsupportedProtocolOperation} when the page refuses. */
        public static CdpWatch start(CdpConnection cdp, Options options) {
            String id = nextId();
            String binding = "pkbDomWatch_" + id;
            CdpWatch watch = new CdpWatch(cdp, id, binding);
            cdp.addEventListener(watch.listener);
            Duration t = Duration.ofSeconds(5);
            try {
                cdp.call("Runtime.enable", JSON.createObjectNode(), t);
                cdp.call("Runtime.addBinding", JSON.createObjectNode().put("name", binding), t);
                ObjectNode eval = JSON.createObjectNode().put("expression", bindingExpression(options, id, binding)).put("returnByValue", true);
                JsonNode reply = cdp.call("Runtime.evaluate", eval, t);
                String value = reply.path("result").path("value").asText("");
                if ("no-root".equals(value)) {
                    watch.close();
                    throw new IllegalArgumentException("error: no element matches --selector=" + options.selector()
                            + "\nfix: snap, then pass a selector that is on the page.");
                }
                if (!"ok".equals(value)) throw new UnsupportedProtocolOperation("dom-watch", "cdp", "observer did not start: " + reply);
            } catch (IllegalArgumentException | UnsupportedProtocolOperation failure) {
                watch.close();
                throw failure;
            } catch (RuntimeException failure) {
                watch.close();
                throw new UnsupportedProtocolOperation("dom-watch", "cdp", String.valueOf(failure.getMessage()));
            }
            return watch;
        }

        public String id() {
            return id;
        }

        /** The next pushed batch, or null after {@code waitMs}. */
        public ObjectNode next(long waitMs) {
            try {
                return batches.poll(Math.max(0, waitMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        /** Batches already pushed, without waiting. */
        public List<ObjectNode> drain() {
            List<ObjectNode> out = new ArrayList<>();
            batches.drainTo(out);
            return out;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            cdp.removeEventListener(listener);
            if (cdp.isClosed()) return;
            Duration t = Duration.ofSeconds(2);
            try {
                cdp.call("Runtime.evaluate", JSON.createObjectNode().put("expression", disconnectExpression(id)), t);
            } catch (RuntimeException ignored) {
                // The page may have navigated away; the observer went with it.
            }
            try {
                cdp.call("Runtime.removeBinding", JSON.createObjectNode().put("name", binding), t);
            } catch (RuntimeException ignored) {
                // Older Chromium without removeBinding keeps an idle binding.
            }
        }
    }
}
