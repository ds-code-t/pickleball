package dev.domengine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.session.BrowserSession;
import dev.domengine.tree.DomNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Poll a query until it matches or the deadline passes. {@code timeout-ms} 0 fails at once. */
public final class Observe {
    private Observe() {
    }

    public static ObjectNode waitFor(DomNode root, Map<String, String> flags) {
        long timeout = longFlag(flags, "timeout-ms", 1000);
        long poll = longFlag(flags, "poll-ms", 50);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        JsonNode last = Query.run(root, flags);
        while (empty(last) && System.nanoTime() < deadline) {
            sleep(poll);
            last = Query.run(root, flags);
        }
        if (empty(last)) throw new CdpException("timeout");
        EngineLog.info("wait", "poll_ms=" + poll + " matched");
        ObjectNode result = (ObjectNode) last;
        result.put("waited", true);
        return result;
    }

    public static ObjectNode waitSession(BrowserSession session, Map<String, String> flags) {
        DomNode root = session.tree().root;
        root.linkParents();
        return waitFor(root, flags);
    }

    public static ObjectNode screenshot(BrowserSession session, Map<String, String> flags) {
        com.fasterxml.jackson.databind.node.ObjectNode params = Jsons.object();
        params.put("format", "png");
        JsonNode shot = session.connection().call("Page.captureScreenshot", params, java.time.Duration.ofSeconds(10));
        String data = shot.path("data").asText("");
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("format", "png");
        result.put("bytes", data.length());
        String out = flags.get("out");
        if (out != null && !out.isBlank()) {
            try {
                Files.write(Path.of(out), java.util.Base64.getDecoder().decode(data));
                result.put("path", out);
            } catch (Exception failure) {
                throw new CdpException("screenshot: " + failure.getMessage());
            }
        }
        EngineLog.info("action", "screenshot bytes=" + data.length());
        return result;
    }

    public static DomNode rootFrom(Map<String, String> flags, BrowserSession session) {
        if (flags.containsKey("file")) {
            try {
                return HtmlTree.parse(Files.readString(Path.of(flags.get("file"))));
            } catch (Exception failure) {
                throw new CdpException("observe file: " + failure.getMessage());
            }
        }
        if (session != null) {
            DomNode root = session.tree().root;
            root.linkParents();
            return root;
        }
        throw new CdpException("observe requires --file or --session");
    }

    private static boolean empty(JsonNode node) {
        JsonNode nodes = node.get("nodes");
        return nodes == null || nodes.isEmpty();
    }

    private static long longFlag(Map<String, String> flags, String name, long fallback) {
        String raw = flags.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        return Long.parseLong(raw.trim());
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CdpException("timeout");
        }
    }
}
