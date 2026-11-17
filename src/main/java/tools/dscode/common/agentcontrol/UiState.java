package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.composite.CompositeLog;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the agent shows on a live page (placements, guides with progress, the panel), kept in
 * {@code ui-state.json} and re-registered as a {@code pkb-surface} preload after every change, so a
 * reload, back/forward, or SPA route change brings it back. Scopes: {@code page} (origin, path and
 * query, the default), {@code origin}, {@code run}. {@code ttl} (seconds) expires the state.
 */
final class UiState {
    static final String FILE = "ui-state.json";
    static final String PAGE_STATE_KEY = "pkb_ui_page_state";
    static final Set<String> SCOPES = Set.of("page", "origin", "run");
    /** Commands after which the page state is snapshotted again. */
    static final Set<String> TRACKED = Set.of("overlay", "overlay-remove", "label", "border", "arrow", "step-badge",
            "magnifier", "spotlight", "unlabel", "unborder", "unarrow", "unstep-badge", "unmagnifier", "unspotlight",
            "panel-open", "panel-close", "guide", "guide-next", "guide-back", "guide-done", "overlay-poll", "chat-poll");
    private static final Map<Path, String> PRELOADS = new ConcurrentHashMap<>();

    private UiState() {
    }

    static String scope(String raw) {
        String scope = raw == null || raw.isBlank() ? "page" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SCOPES.contains(scope)) throw new IllegalArgumentException("--scope must be page, origin, or run");
        return scope;
    }

    static long expires(String ttl, long now) {
        if (ttl == null || ttl.isBlank()) return 0L;
        long seconds;
        try {
            seconds = Long.parseLong(ttl.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("--ttl must be whole seconds");
        }
        if (seconds <= 0) throw new IllegalArgumentException("--ttl must be more than 0 seconds");
        return now + seconds * 1000L;
    }

    /** Same rule as the page runtime's {@code inScope}. */
    static boolean inScope(String scope, String savedUrl, String currentUrl, long expires, long now) {
        if (expires > 0 && now > expires) return false;
        if ("run".equals(scope)) return true;
        URI saved;
        URI current;
        try {
            saved = URI.create(savedUrl);
            current = URI.create(currentUrl);
        } catch (RuntimeException failure) {
            return false;
        }
        boolean sameOrigin = String.valueOf(saved.getScheme()).equals(String.valueOf(current.getScheme()))
                && String.valueOf(saved.getHost()).equals(String.valueOf(current.getHost()))
                && saved.getPort() == current.getPort();
        if ("origin".equals(scope)) return sameOrigin;
        String a = (saved.getRawPath() == null || saved.getRawPath().isEmpty() ? "/" : saved.getRawPath()) + "?" + saved.getRawQuery();
        String b = (current.getRawPath() == null || current.getRawPath().isEmpty() ? "/" : current.getRawPath()) + "?" + current.getRawQuery();
        return sameOrigin && a.equals(b);
    }

    static boolean empty(JsonNode snap) {
        return snap == null || snap.isNull()
                || (snap.path("items").size() == 0 && snap.path("guides").size() == 0
                && (snap.path("panel").isMissingNode() || snap.path("panel").isNull()));
    }

    static boolean pageStateOptIn() {
        String value = System.getProperty(PAGE_STATE_KEY, System.getenv("PKB_UI_PAGE_STATE"));
        return value != null && "session".equalsIgnoreCase(value.trim());
    }

    /** The state the preload replays: scope, expiry, opt-in storage flag, and the snapshot. */
    static ObjectNode state(JsonNode snap, String scope, long expires, boolean pageState) {
        ObjectNode state = AgentStore.JSON.createObjectNode();
        state.put("v", 1);
        state.put("scope", scope);
        state.put("expires", expires);
        state.put("pageState", pageState);
        state.set("snap", snap);
        return state;
    }

    static String preloadScript(JsonNode state) throws IOException {
        String json = AgentStore.JSON.writeValueAsString(state).replace("</", "<\\/");
        return "(function(){ var STATE = " + json + "; var run = function(){\n" + PageSurfaces.runtimeSource()
                + "\n;if (window.__pkbUi && window.__pkbUi.restoreWhenReady) window.__pkbUi.restoreWhenReady(STATE); };"
                + " if (document.documentElement) run(); else document.addEventListener('DOMContentLoaded', run, {once:true}); })();";
    }

    /** Snapshots the focused page and re-registers the preload. Best effort: a failure is logged, never thrown. */
    static void sync(Path project, String runId, Map<String, String> args) {
        if (!PageSurfaces.hasLivePage()) return;
        Path file = AgentStore.root(project, runId).resolve(FILE);
        try {
            JsonNode previous = read(file);
            String scope = args != null && args.get("scope") != null && !args.get("scope").isBlank()
                    ? scope(args.get("scope")) : previous.path("scope").asText("page");
            long expires = args != null && args.get("ttl") != null && !args.get("ttl").isBlank()
                    ? expires(args.get("ttl"), System.currentTimeMillis()) : previous.path("expires").asLong(0L);
            String raw = PageSurfaces.runtimeCall("snapshot", null, false);
            if (raw == null) return;
            JsonNode snap = AgentStore.JSON.readTree(raw);
            JsonNode kept = previous.path("snap");
            if (empty(snap) && !empty(kept) && !inScope(scope, kept.path("url").asText(""), snap.path("url").asText(""), 0L, 0L)) {
                // Nothing is shown here because this page is out of the saved scope; keep the saved state.
                return;
            }
            String old = PRELOADS.remove(file);
            if (old == null) old = previous.path("preload").asText("");
            PageSurfaces.removeStatePreload(old);
            if (empty(snap)) {
                Files.deleteIfExists(file);
                return;
            }
            ObjectNode state = state(snap, scope, expires, pageStateOptIn());
            String id = PageSurfaces.addStatePreload(preloadScript(state));
            // The open document watches its own route changes with the same state the preload replays.
            PageSurfaces.runtimeCall("restoreWhenReady", state, false);
            ObjectNode stored = state.deepCopy();
            stored.put("preload", id == null ? "" : id);
            if (id != null && !id.isBlank()) PRELOADS.put(file, id);
            write(file, stored);
        } catch (Exception failure) {
            CompositeLog.info("ui", "state sync skipped " + failure.getMessage());
        }
    }

    /** Removes the stored state, its preload, and what is shown, including the session copy. */
    static void clear(Path project, String runId) {
        Path file = AgentStore.root(project, runId).resolve(FILE);
        try {
            String id = PRELOADS.remove(file);
            if (id == null) id = read(file).path("preload").asText("");
            PageSurfaces.removeStatePreload(id);
            Files.deleteIfExists(file);
            if (PageSurfaces.hasLivePage()) PageSurfaces.runtimeCall("forget", null, false);
        } catch (Exception failure) {
            CompositeLog.info("ui", "state clear skipped " + failure.getMessage());
        }
    }

    /** Scenario end: every preload this process registered is removed. */
    static void clearAll() {
        for (Map.Entry<Path, String> entry : PRELOADS.entrySet()) {
            try {
                PageSurfaces.removeStatePreload(entry.getValue());
                Files.deleteIfExists(entry.getKey());
            } catch (Exception failure) {
                CompositeLog.info("ui", "state clear skipped " + failure.getMessage());
            }
        }
        PRELOADS.clear();
    }

    static JsonNode read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return AgentStore.JSON.createObjectNode();
        return AgentStore.JSON.readTree(Files.readString(file));
    }

    private static void write(Path file, JsonNode state) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE + ".tmp");
        Files.writeString(temp, AgentStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
