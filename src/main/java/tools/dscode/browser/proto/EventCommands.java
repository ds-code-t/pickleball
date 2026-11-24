package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import tools.dscode.browser.BrowserProtocols;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** E2 host commands: host status/stop, and the unified browser event stream (start, read, context). */
final class EventCommands {
    private EventCommands() {
    }

    static void register() {
        ProtoHost.register("host.status", (host, f) -> {
            ObjectNode out = ProtoJson.obj().put("op", "host").put("session", host.session()).put("run", host.run());
            out.put("protocols", host.protocols().summary()).put("cursor", host.hub().cursor());
            Object source = host.stateMap().get("events.source");
            out.put("events", source == null ? "off" : String.valueOf(source));
            out.put("spill", host.hub().spillFile().toString());
            Object dom = host.stateMap().get("dom.via");
            out.put("dom", dom == null ? "off" : String.valueOf(dom));
            return out;
        });
        ProtoHost.register("host.stop", (host, f) -> {
            Thread stopper = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                host.stop();
            });
            stopper.setDaemon(false);
            stopper.start();
            return ProtoJson.obj().put("op", "host").put("stopped", true);
        });
        ProtoHost.register("events.start", EventCommands::start);
        ProtoHost.register("events.read", EventCommands::read);
        ProtoHost.register("events.context", (host, f) -> {
            host.hub().context(f.get("step"), f.get("scenario"));
            return ProtoJson.obj().put("op", "browser-events").put("step", f.getOrDefault("step", "")).put("scenario", f.getOrDefault("scenario", ""));
        });
    }

    static Set<String> kinds(Map<String, String> f) {
        String raw = f.containsKey("kinds") ? f.get("kinds") : f.get("kind");
        if (raw == null || raw.isBlank()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,\\s]+")) {
            String kind = part.trim().toLowerCase(Locale.ROOT);
            if (kind.isEmpty()) continue;
            if (!BrowserEventHub.KINDS.contains(kind)) {
                throw new IllegalArgumentException("error: unknown event kind " + kind + "\nfix: kinds are " + String.join(",", BrowserEventHub.KINDS));
            }
            out.add(kind);
        }
        return out;
    }

    /** Starts the subscription once on the backend the fallback order picks; later calls return the same source. */
    static synchronized ObjectNode start(ProtoHost host, Map<String, String> f) {
        Object existing = host.stateMap().get("events.source");
        if (existing != null) {
            return ProtoJson.obj().put("op", "browser-events").put("via", String.valueOf(existing)).put("started", false)
                    .put("cursor", host.hub().cursor());
        }
        Set<String> config = host.hub().kinds();
        Set<String> asked = kinds(f);
        if (asked != null) host.hub().kinds(config == null ? asked : intersect(config, asked));
        if (!host.hub().enabled()) {
            return ProtoJson.obj().put("op", "browser-events").put("via", "none").put("started", false)
                    .put("note", "pkb_browser_events=off: no rows are kept or spilled");
        }
        BrowserProtocols.Served<String> served = host.protocols().serve("events", f.get("via"), backend -> {
            switch (backend) {
                case CDP -> Projectors.cdp(host, host.cdp(), host.hub());
                case BIDI -> Projectors.bidi(host, host.bidi(), host.hub());
                case PLAYWRIGHT -> playwright(host);
                default -> throw new tools.dscode.browser.UnsupportedProtocolOperation("events", backend.id(), null);
            }
            return backend.id();
        });
        host.stateMap().put("events.source", served.value());
        host.hub().primary(served.value());
        ObjectNode out = ProtoJson.obj().put("op", "browser-events").put("via", served.value()).put("started", true)
                .put("cursor", host.hub().cursor()).put("spill", host.hub().spillFile().toString());
        Set<String> active = host.hub().kinds();
        out.put("kinds", active == null ? "all" : String.join(",", active));
        return out;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

    private static void playwright(ProtoHost host) {
        BrowserEventHub hub = host.hub();
        PwBridge pw = host.pw();
        pw.call(connection -> {
            for (BrowserContext context : connection.browser().contexts()) {
                for (Page page : context.pages()) listen(page, hub);
                context.onPage(page -> listen(page, hub));
            }
            return null;
        });
        pw.listening(true);
    }

    static void listen(Page page, BrowserEventHub hub) {
        page.onConsoleMessage(message -> {
            ObjectNode r = hub.row("playwright", "console", message.type());
            r.put("level", Projectors.level(message.type())).put("text", message.text()).put("url", message.location());
            hub.add(r);
        });
        page.onPageError(error -> {
            ObjectNode r = hub.row("playwright", "error", "exception");
            r.put("level", "error").put("text", error);
            hub.add(r);
        });
        page.onRequest(request -> {
            ObjectNode r = hub.row("playwright", "net", "request");
            r.put("requestId", "pw:" + System.identityHashCode(request)).put("url", request.url()).put("method", request.method())
                    .put("text", request.resourceType());
            hub.add(r);
        });
        page.onRequestFinished(request -> {
            ObjectNode r = hub.row("playwright", "net", "finished");
            r.put("requestId", "pw:" + System.identityHashCode(request)).put("url", request.url());
            try {
                com.microsoft.playwright.Response response = request.response();
                if (response != null) r.put("status", response.status());
                double end = request.timing().responseEnd;
                if (end > 0) r.put("durationMs", Math.round(end));
            } catch (RuntimeException ignored) {
                // Timing is optional.
            }
            hub.add(r);
        });
        page.onRequestFailed(request -> {
            ObjectNode r = hub.row("playwright", "net", "failed");
            r.put("requestId", "pw:" + System.identityHashCode(request)).put("url", request.url()).put("level", "error")
                    .put("text", String.valueOf(request.failure()));
            hub.add(r);
        });
        page.onFrameNavigated(frame -> {
            ObjectNode r = hub.row("playwright", "nav", frame.parentFrame() == null ? "committed" : "frame-committed");
            r.put("url", frame.url());
            hub.add(r);
        });
        page.onDialog(dialog -> {
            ObjectNode r = hub.row("playwright", "dialog", "opened");
            r.put("text", dialog.message()).put("method", dialog.type());
            hub.add(r);
        });
    }

    static ObjectNode read(ProtoHost host, Map<String, String> f) throws Exception {
        String via = "none";
        if (!host.stateMap().containsKey("events.source")) {
            // An implicit start from a read subscribes to every kind; --kinds on a read only filters what is returned.
            Map<String, String> plain = new java.util.LinkedHashMap<>(f);
            plain.remove("kinds");
            plain.remove("kind");
            via = start(host, plain).path("via").asText();
        }
        else via = String.valueOf(host.stateMap().get("events.source"));
        int limit = parseInt(f.get("limit"), 200);
        long waitMs = Math.min(120_000, parseInt(f.get("wait-ms"), 0));
        List<ObjectNode> rows = host.hub().read(f.get("since"), kinds(f), f.get("match"), f.get("level"), limit, waitMs);
        ObjectNode out = ProtoJson.obj().put("op", "browser-events").put("schema", BrowserEventHub.SCHEMA).put("via", via);
        ArrayNode array = out.putArray("rows");
        rows.forEach(array::add);
        out.put("cursor", rows.isEmpty() ? cursorOf(f.get("since"), host) : rows.get(rows.size() - 1).path("seq").asLong());
        return out;
    }

    private static long cursorOf(String since, ProtoHost host) {
        if (since != null && since.matches("\\d+")) return Long.parseLong(since);
        return host.hub().cursor();
    }

    static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    static List<String> list(String raw) {
        return raw == null || raw.isBlank() ? List.of() : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
