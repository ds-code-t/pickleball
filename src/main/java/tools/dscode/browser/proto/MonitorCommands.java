package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E10: security state and certificate errors ({@code kind=security}), DevTools issues ({@code kind=audit},
 * {@code audits}), service workers ({@code kind=sw}, {@code sw}), storage changes ({@code kind=storage}, keys only),
 * navigation and target lifecycle ({@code kind=nav}, {@code kind=target}), and worker targets joining the stream
 * with a {@code target} field. Wired into the event stream when it starts.
 */
public final class MonitorCommands {
    static final List<String> PAGE_DOMAINS = List.of("Security.enable", "Audits.enable", "ServiceWorker.enable", "DOMStorage.enable");
    static final List<String> BIDI_EVENTS = List.of("script.realmCreated", "script.realmDestroyed", "browsingContext.historyUpdated",
            "browsingContext.navigationAborted", "browsingContext.navigationCommitted");

    /** Per-host state: service worker registrations and versions, crashed targets, revealed storage values (memory only). */
    static final class State {
        final Map<String, ObjectNode> registrations = new ConcurrentHashMap<>();
        final Map<String, ObjectNode> versions = new ConcurrentHashMap<>();
        final AtomicInteger crashed = new AtomicInteger();
        /** Storage values by row seq; never written to the ring, the spill or a log. */
        final Map<Long, String> values = new ConcurrentHashMap<>();
        /** Pages attached blank after the stream started, waiting for their first URL. */
        final java.util.Set<String> newPages = ConcurrentHashMap.newKeySet();
    }

    private MonitorCommands() {
    }

    static State state(ProtoHost host) {
        return (State) host.stateMap().computeIfAbsent("monitor.state", k -> new State());
    }

    static void register() {
        ProtoHost.register("audits", MonitorCommands::audits);
        ProtoHost.register("sw", MonitorCommands::sw);
    }

    /** Called once when the event stream starts: extra CDP domains and mappers, worker attach, BiDi lifecycle events. */
    static void attach(ProtoHost host, String source) {
        if (host.stateMap().putIfAbsent("monitor.attached", true) != null) return;
        State state = state(host);
        try {
            CdpSession cdp = host.cdp();
            cdp.attachWorkers();
            cdp.autoAttachChildren();
            java.util.Set<String> known = java.util.concurrent.ConcurrentHashMap.newKeySet();
            for (CdpSession.Target t : cdp.pages()) known.add(t.targetId);
            cdp.onTarget(target -> {
                if (!"page".equals(target.type)) return;
                if (known.add(target.targetId)) {
                    // A page opened after the stream started (window.open, tab-new): its first commit may precede Page.enable,
                    // so the row carries the page's first real URL (from Target.targetInfoChanged when it attached blank).
                    if (blank(target.url)) state.newPages.add(target.targetId);
                    else host.hub().add(pageAttached(host.hub(), target.targetId, target.url));
                }
                for (String domain : PAGE_DOMAINS) {
                    try {
                        cdp.send(target.sessionId, domain, Map.of());
                    } catch (RuntimeException unsupported) {
                        // A domain this browser lacks.
                    }
                }
                try {
                    cdp.send(target.sessionId, "Page.enable", Map.of());
                } catch (RuntimeException ignored) {
                    // Closed.
                }
            });
            Projectors.extraCdp.add((method, params, sessionId, hub, h) -> h == host && cdp(host, state, method, params, sessionId, hub));
        } catch (RuntimeException noCdp) {
            // No CDP endpoint (BiDi-only browser): the BiDi part below still runs.
        }
        if ("bidi".equals(source)) {
            try {
                Projectors.extraBidi.add((method, params, hub, h) -> h == host && bidi(method, params, hub));
                host.bidi().call("session.subscribe", Map.of("events", BIDI_EVENTS));
            } catch (RuntimeException unsupported) {
                // An older BiDi without these events.
            }
        }
    }

    static boolean blank(String url) {
        return url == null || url.isBlank() || "about:blank".equals(url);
    }

    static ObjectNode pageAttached(BrowserEventHub hub, String targetId, String url) {
        return hub.row("cdp", "target", "page-attached").put("url", url).put("context", targetId);
    }

    static boolean cdp(ProtoHost host, State state, String method, JsonNode params, String sessionId, BrowserEventHub hub) {
        CdpSession.Target target = host.cdp().target(sessionId);
        String context = target == null ? null : target.targetId;
        ObjectNode row;
        switch (method) {
            case "Target.targetInfoChanged" -> {
                JsonNode info = params.path("targetInfo");
                String url = info.path("url").asText("");
                if (!blank(url) && state.newPages.remove(info.path("targetId").asText())) hub.add(pageAttached(hub, info.path("targetId").asText(), url));
                return false;
            }
            case "Security.visibleSecurityStateChanged" -> {
                JsonNode s = params.path("visibleSecurityState");
                String level = s.path("securityState").asText("unknown");
                row = hub.row("cdp", "security", "state").put("text", level)
                        .put("level", "insecure".equals(level) ? "warning" : "info");
                JsonNode cert = s.path("certificateSecurityState");
                if (!cert.isMissingNode()) {
                    row.put("protocol", cert.path("protocol").asText("")).put("issuer", cert.path("issuer").asText(""))
                            .put("subject", cert.path("subjectName").asText(""));
                    if (cert.hasNonNull("certificateNetworkError")) row.put("error", cert.path("certificateNetworkError").asText());
                }
            }
            case "Network.loadingFailed" -> {
                String error = params.path("errorText").asText("");
                if (!error.contains("ERR_CERT") && !error.contains("ERR_SSL")) return false;
                row = hub.row("cdp", "security", "certificate-error").put("text", error).put("level", "error")
                        .put("requestId", params.path("requestId").asText());
                if (context != null) row.put("context", context);
                hub.add(row);
                return false;
            }
            case "Audits.issueAdded" -> {
                JsonNode issue = params.path("issue");
                String code = issue.path("code").asText("Issue");
                row = hub.row("cdp", "audit", code.replace("Issue", "").toLowerCase(java.util.Locale.ROOT).isBlank() ? "issue"
                        : code.replace("Issue", "").replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT));
                row.put("method", code).put("level", "warning").put("text", issueText(issue.path("details")));
                String url = firstUrl(issue.path("details"));
                if (url != null) row.put("url", url);
            }
            case "ServiceWorker.workerRegistrationUpdated" -> {
                for (JsonNode r : params.path("registrations")) {
                    ObjectNode one = hub.row("cdp", "sw", r.path("isDeleted").asBoolean() ? "unregistered" : "registration")
                            .put("url", r.path("scopeURL").asText()).put("text", r.path("registrationId").asText());
                    if (r.path("isDeleted").asBoolean()) state.registrations.remove(r.path("registrationId").asText());
                    else state.registrations.put(r.path("registrationId").asText(), (ObjectNode) r.deepCopy());
                    hub.add(one);
                }
                return true;
            }
            case "ServiceWorker.workerVersionUpdated" -> {
                for (JsonNode v : params.path("versions")) {
                    state.versions.put(v.path("versionId").asText(), (ObjectNode) v.deepCopy());
                    hub.add(hub.row("cdp", "sw", "version").put("url", v.path("scriptURL").asText())
                            .put("text", v.path("status").asText() + "/" + v.path("runningStatus").asText())
                            .put("method", v.path("registrationId").asText()));
                }
                return true;
            }
            case "ServiceWorker.workerErrorReported" -> {
                JsonNode e = params.path("errorMessage");
                row = hub.row("cdp", "sw", "error").put("text", e.path("errorMessage").asText()).put("url", e.path("sourceURL").asText())
                        .put("line", e.path("lineNumber").asInt()).put("level", "error");
            }
            case "DOMStorage.domStorageItemAdded", "DOMStorage.domStorageItemUpdated", "DOMStorage.domStorageItemRemoved", "DOMStorage.domStorageItemsCleared" -> {
                String sub = method.substring("DOMStorage.domStorageItem".length()).toLowerCase(java.util.Locale.ROOT);
                if (method.endsWith("ItemsCleared")) sub = "cleared";
                JsonNode id = params.path("storageId");
                row = hub.row("cdp", "storage", sub).put("method", id.path("isLocalStorage").asBoolean(true) ? "local" : "session")
                        .put("url", id.path("securityOrigin").asText(id.path("storageKey").asText("")));
                if (params.has("key")) row.put("key", params.path("key").asText());
                String value = params.hasNonNull("newValue") ? params.path("newValue").asText() : null;
                if (value != null) row.put("value", ContextCommands.REDACTED).put("valueLength", value.length());
                if (context != null) row.put("context", context);
                ObjectNode stored = hub.add(row);
                if (stored != null && value != null) state.values.put(stored.path("seq").asLong(), value);
                return true;
            }
            case "Page.frameStartedNavigating" -> row = hub.row("cdp", "nav", "started").put("url", params.path("url").asText(""))
                    .put("frame", params.path("frameId").asText("")).put("method", params.path("navigationType").asText(""));
            case "Page.backForwardCacheNotUsed" -> {
                StringBuilder reasons = new StringBuilder();
                for (JsonNode r : params.path("notRestoredExplanations")) {
                    if (reasons.length() > 0) reasons.append(',');
                    reasons.append(r.path("reason").asText());
                }
                row = hub.row("cdp", "nav", "bfcache-not-used").put("text", reasons.toString()).put("frame", params.path("frameId").asText(""));
            }
            case "Page.windowOpen" -> row = hub.row("cdp", "target", "window-open").put("url", params.path("url").asText(""))
                    .put("text", params.path("windowName").asText(""));
            case "Target.targetCrashed", "Inspector.targetCrashed" -> {
                state.crashed.incrementAndGet();
                row = hub.row("cdp", "target", "crashed").put("level", "error").put("text", params.path("status").asText("crashed"));
                if (params.has("targetId")) row.put("context", params.path("targetId").asText());
            }
            default -> {
                return false;
            }
        }
        if (context != null && !row.has("context")) row.put("context", context);
        hub.add(row);
        return true;
    }

    static boolean bidi(String method, JsonNode params, BrowserEventHub hub) {
        ObjectNode row = switch (method) {
            case "script.realmCreated" -> {
                String type = params.path("type").asText("");
                if ("window".equals(type)) yield null;
                yield hub.row("bidi", "target", "realm-created").put("text", type).put("url", params.path("origin").asText(""))
                        .put("context", params.path("realm").asText()).put("target", type);
            }
            case "script.realmDestroyed" -> hub.row("bidi", "target", "realm-destroyed").put("context", params.path("realm").asText());
            case "browsingContext.historyUpdated" -> hub.row("bidi", "nav", "history-updated").put("url", params.path("url").asText(""))
                    .put("context", params.path("context").asText());
            case "browsingContext.navigationAborted" -> hub.row("bidi", "nav", "aborted").put("url", params.path("url").asText(""))
                    .put("context", params.path("context").asText());
            case "browsingContext.navigationCommitted" -> hub.row("bidi", "nav", "committed").put("url", params.path("url").asText(""))
                    .put("context", params.path("context").asText());
            default -> null;
        };
        if (row == null) return BIDI_EVENTS.contains(method);
        hub.add(row);
        return true;
    }

    static String issueText(JsonNode details) {
        for (var it = details.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode d = e.getValue();
            for (String key : List.of("violatedDirective", "mixedContentType", "cookieWarningReasons", "cookieExclusionReasons", "type", "corsErrorStatus", "errorType")) {
                if (d.has(key)) return e.getKey().replace("IssueDetails", "") + ": " + (d.get(key).isTextual() ? d.get(key).asText() : d.get(key).toString());
            }
            return e.getKey().replace("IssueDetails", "");
        }
        return "";
    }

    static String firstUrl(JsonNode node) {
        if (node.isObject()) {
            for (String key : List.of("blockedURL", "insecureURL", "url", "documentURL", "sourceCodeLocation")) {
                JsonNode v = node.get(key);
                if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
                if (v != null && v.isObject() && v.has("url")) return v.get("url").asText();
            }
            for (var it = node.fields(); it.hasNext(); ) {
                String found = firstUrl(it.next().getValue());
                if (found != null) return found;
            }
        }
        return null;
    }

    /** browser-events --reveal: storage rows get their values back from memory (never from the spill). */
    static void reveal(ProtoHost host, List<ObjectNode> rows) {
        State state = state(host);
        for (ObjectNode row : rows) {
            if (!"storage".equals(row.path("kind").asText())) continue;
            String value = state.values.get(row.path("seq").asLong());
            if (value != null) row.put("value", value).put("revealed", true);
        }
    }

    // ---- audits ----

    static ObjectNode audits(ProtoHost host, Map<String, String> f) throws Exception {
        if (!host.stateMap().containsKey("events.source")) EventCommands.start(host, Map.of());
        if (f.containsKey("check-forms")) {
            CdpSession.Target t = ContextCommands.target(host, f);
            JsonNode issues = host.cdp().send(t.sessionId, "Audits.checkFormsIssues", Map.of()).path("formIssues");
            ArrayNode out = ProtoJson.MAPPER.createArrayNode();
            for (JsonNode issue : issues) {
                ObjectNode row = host.hub().row("cdp", "audit", "form").put("method", "GenericIssue").put("level", "warning")
                        .put("text", issue.path("errorType").asText(issue.path("violatingNodeAttribute").asText("form issue")));
                if (issue.has("frameId")) row.put("frame", issue.path("frameId").asText());
                ObjectNode stored = host.hub().add(row);
                if (stored != null) out.add(stored);
            }
            ObjectNode reply = ProtoJson.obj().put("op", "audits").put("mode", "check-forms").put("via", "cdp").put("count", out.size());
            reply.set("issues", out);
            return reply;
        }
        List<ObjectNode> rows = host.hub().read(f.getOrDefault("since", "0"), java.util.Set.of("audit"), f.get("match"), null,
                EventCommands.parseInt(f.get("limit"), 200), Math.min(30_000, EventCommands.parseInt(f.get("wait-ms"), 0)));
        ArrayNode array = ProtoJson.MAPPER.createArrayNode();
        rows.forEach(array::add);
        ObjectNode reply = ProtoJson.obj().put("op", "audits").put("mode", "list").put("count", array.size());
        reply.set("issues", array);
        return reply;
    }

    // ---- service workers ----

    static ObjectNode sw(ProtoHost host, Map<String, String> f) {
        if (!host.stateMap().containsKey("events.source")) EventCommands.start(host, Map.of());
        State state = state(host);
        if (f.containsKey("bypass")) {
            boolean on = !"false".equals(f.get("bypass"));
            BrowserProtocols.Served<String> served = host.protocols().serve("sw", f.get("via"), backend -> {
                if (backend != Backend.CDP) throw new UnsupportedProtocolOperation("sw", backend.id(), "service worker bypass is CDP Network.setBypassServiceWorker");
                for (CdpSession.Target t : RouteCommands.pages(host)) host.cdp().send(t.sessionId, "Network.setBypassServiceWorker", Map.of("bypass", on));
                return backend.id();
            });
            host.log("sw bypass=" + on);
            if (on) host.onStop(() -> {
                try {
                    for (CdpSession.Target t : host.cdp().pages()) host.cdp().send(t.sessionId, "Network.setBypassServiceWorker", Map.of("bypass", false));
                } catch (RuntimeException gone) {
                    // Browser gone.
                }
            });
            return ProtoJson.obj().put("op", "sw").put("mode", "bypass").put("bypass", on).put("via", served.value());
        }
        if (f.containsKey("unregister")) {
            String scope = f.get("unregister");
            CdpSession.Target t = ContextCommands.target(host, f);
            host.cdp().send(t.sessionId, "ServiceWorker.unregister", Map.of("scopeURL", scope));
            host.log("sw unregister scope=" + scope);
            return ProtoJson.obj().put("op", "sw").put("mode", "unregister").put("scope", scope).put("via", "cdp");
        }
        long deadline = System.currentTimeMillis() + EventCommands.parseInt(f.get("wait-ms"), 0);
        while (System.currentTimeMillis() < deadline && state.registrations.values().stream()
                .noneMatch(r -> f.containsKey("all") || !r.path("scopeURL").asText().startsWith("chrome-extension://"))) ContextCommands.sleep(100);
        ArrayNode list = ProtoJson.MAPPER.createArrayNode();
        boolean all = f.containsKey("all");
        for (ObjectNode r : state.registrations.values()) {
            // The browser's own extension workers are listed only with --all.
            if (!all && r.path("scopeURL").asText().startsWith("chrome-extension://")) continue;
            ObjectNode one = list.addObject().put("id", r.path("registrationId").asText()).put("scope", r.path("scopeURL").asText());
            ArrayNode versions = one.putArray("versions");
            for (ObjectNode v : state.versions.values()) {
                if (v.path("registrationId").asText().equals(r.path("registrationId").asText())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    versions.addObject().put("id", v.path("versionId").asText()).put("script", v.path("scriptURL").asText())
                            .put("status", v.path("status").asText()).put("running", v.path("runningStatus").asText());
                }
            }
        }
        ObjectNode reply = ProtoJson.obj().put("op", "sw").put("mode", "list").put("count", list.size()).put("via", "cdp");
        reply.set("registrations", list);
        return reply;
    }

    /** For doctor and host.status. */
    static int crashed(ProtoHost host) {
        Object s = host.stateMap().get("monitor.state");
        return s == null ? 0 : ((State) s).crashed.get();
    }
}
