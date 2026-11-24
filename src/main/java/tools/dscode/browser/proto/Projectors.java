package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.RpcChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend projectors into {@link BrowserEventHub}. CDP: Network, Runtime, Log and Page events from every attached
 * page session (the same event names dom-engine {@code SessionEvents} projects for {@code observe}, which keeps its
 * own projection and output unchanged). BiDi: {@code log.entryAdded}, {@code network.*} and {@code browsingContext.*}.
 * Extra projections (errors with stacks, WebSocket frames, downloads, audits, workers) are added by later phases
 * through {@link #extraCdp} and {@link #extraBidi}.
 */
public final class Projectors {
    /** One CDP event mapper: returns true when it produced or consumed the event. */
    @FunctionalInterface
    public interface CdpMapper {
        boolean map(String method, JsonNode params, String sessionId, BrowserEventHub hub, ProtoHost host);
    }

    /** One BiDi event mapper. */
    @FunctionalInterface
    public interface BidiMapper {
        boolean map(String method, JsonNode params, BrowserEventHub hub, ProtoHost host);
    }

    public static final List<CdpMapper> extraCdp = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static final List<BidiMapper> extraBidi = new java.util.concurrent.CopyOnWriteArrayList<>();

    static final List<String> BIDI_EVENTS = List.of("log.entryAdded", "network.beforeRequestSent", "network.responseCompleted",
            "network.fetchError", "browsingContext.navigationStarted", "browsingContext.load", "browsingContext.domContentLoaded",
            "browsingContext.fragmentNavigated", "browsingContext.navigationFailed", "browsingContext.userPromptOpened",
            "browsingContext.userPromptClosed", "browsingContext.contextCreated", "browsingContext.contextDestroyed");

    private Projectors() {
    }

    /** Domains to enable on each page session for the subscribed kinds. */
    static List<String> cdpDomains(java.util.Set<String> kinds) {
        List<String> out = new ArrayList<>();
        boolean all = kinds == null;
        if (all || kinds.contains("net") || kinds.contains("ws")) out.add("Network.enable");
        if (all || kinds.contains("console") || kinds.contains("error")) {
            out.add("Runtime.enable");
            out.add("Log.enable");
        }
        if (all || kinds.contains("nav") || kinds.contains("dialog") || kinds.contains("download") || kinds.contains("file-dialog")) out.add("Page.enable");
        return out;
    }

    public static void cdp(ProtoHost host, CdpSession cdp, BrowserEventHub hub) {
        Map<String, Double> started = new ConcurrentHashMap<>();
        Map<String, String> urls = new ConcurrentHashMap<>();
        List<String> domains = cdpDomains(hub.kinds());
        cdp.onTarget(target -> {
            for (String domain : domains) {
                try {
                    cdp.send(target.sessionId, domain, Map.of());
                } catch (RuntimeException ignored) {
                    // Worker targets have no Page domain.
                }
            }
            if (!"page".equals(target.type)) {
                ObjectNode row = hub.row("cdp", "target", "attached");
                row.put("url", target.url).put("text", target.type).put("context", target.targetId);
                hub.add(row);
            }
        });
        cdp.channel().listen((method, params, sessionId) -> {
            for (CdpMapper mapper : extraCdp) if (mapper.map(method, params, sessionId, hub, host)) return;
            CdpSession.Target target = cdp.target(sessionId);
            String context = target == null ? null : target.targetId;
            ObjectNode row = switch (method) {
                case "Network.requestWillBeSent" -> {
                    String id = params.path("requestId").asText();
                    started.put(id, params.path("timestamp").asDouble());
                    urls.put(id, params.path("request").path("url").asText(""));
                    ObjectNode r = hub.row("cdp", "net", "request");
                    r.put("requestId", id).put("url", params.path("request").path("url").asText(""))
                            .put("method", params.path("request").path("method").asText(""))
                            .put("text", params.path("type").asText(""));
                    yield r;
                }
                case "Network.responseReceived" -> {
                    JsonNode response = params.path("response");
                    ObjectNode r = hub.row("cdp", "net", "response");
                    r.put("requestId", params.path("requestId").asText()).put("url", response.path("url").asText(""))
                            .put("status", response.path("status").asInt()).put("text", response.path("mimeType").asText(""));
                    yield r;
                }
                case "Network.loadingFinished" -> {
                    String id = params.path("requestId").asText();
                    ObjectNode r = hub.row("cdp", "net", "finished");
                    r.put("requestId", id).put("url", urls.getOrDefault(id, "")).put("bytes", params.path("encodedDataLength").asLong());
                    Double start = started.remove(id);
                    if (start != null) r.put("durationMs", Math.round((params.path("timestamp").asDouble() - start) * 1000));
                    yield r;
                }
                case "Network.loadingFailed" -> {
                    String id = params.path("requestId").asText();
                    ObjectNode r = hub.row("cdp", "net", "failed");
                    r.put("requestId", id).put("url", urls.getOrDefault(id, "")).put("text", params.path("errorText").asText(""))
                            .put("level", "error");
                    started.remove(id);
                    yield r;
                }
                case "Runtime.consoleAPICalled" -> {
                    ObjectNode r = hub.row("cdp", "console", params.path("type").asText("log"));
                    r.put("level", level(params.path("type").asText("log"))).put("text", consoleText(params.path("args")));
                    JsonNode frame = params.path("stackTrace").path("callFrames").path(0);
                    if (!frame.isMissingNode()) r.put("url", frame.path("url").asText("")).put("line", frame.path("lineNumber").asInt() + 1);
                    yield r;
                }
                case "Log.entryAdded" -> {
                    JsonNode entry = params.path("entry");
                    String source = entry.path("source").asText("other");
                    ObjectNode r = hub.row("cdp", "console", "log." + source);
                    r.put("level", entry.path("level").asText("info")).put("text", entry.path("text").asText(""))
                            .put("url", entry.path("url").asText(""));
                    yield r;
                }
                case "Page.frameNavigated" -> {
                    JsonNode frame = params.path("frame");
                    ObjectNode r = hub.row("cdp", "nav", frame.hasNonNull("parentId") ? "frame-committed" : "committed");
                    r.put("url", frame.path("url").asText("")).put("frame", frame.path("id").asText(""));
                    yield r;
                }
                case "Page.navigatedWithinDocument" -> {
                    ObjectNode r = hub.row("cdp", "nav", "same-document");
                    r.put("url", params.path("url").asText("")).put("frame", params.path("frameId").asText(""));
                    yield r;
                }
                case "Page.javascriptDialogOpening" -> {
                    ObjectNode r = hub.row("cdp", "dialog", "opened");
                    r.put("text", params.path("message").asText("")).put("url", params.path("url").asText(""))
                            .put("method", params.path("type").asText(""));
                    yield r;
                }
                case "Page.javascriptDialogClosed" -> {
                    ObjectNode r = hub.row("cdp", "dialog", "closed");
                    r.put("text", params.path("result").asBoolean() ? "accepted" : "dismissed");
                    yield r;
                }
                default -> null;
            };
            if (row != null) {
                if (context != null && !row.has("context")) row.put("context", context);
                hub.add(row);
            }
        });
    }

    public static void bidi(ProtoHost host, RpcChannel bidi, BrowserEventHub hub) {
        bidi.listen((method, params, ignoredSession) -> {
            for (BidiMapper mapper : extraBidi) if (mapper.map(method, params, hub, host)) return;
            ObjectNode row = switch (method) {
                case "log.entryAdded" -> {
                    boolean js = "javascript".equals(params.path("type").asText());
                    ObjectNode r = hub.row("bidi", js ? "error" : "console", js ? "exception" : params.path("method").asText("log"));
                    r.put("level", params.path("level").asText("info")).put("text", params.path("text").asText(""));
                    JsonNode frame = params.path("stackTrace").path("callFrames").path(0);
                    if (!frame.isMissingNode()) r.put("url", frame.path("url").asText("")).put("line", frame.path("lineNumber").asInt() + 1);
                    if (js && params.has("stackTrace")) r.set("stack", params.get("stackTrace").path("callFrames"));
                    yield r;
                }
                case "network.beforeRequestSent" -> {
                    JsonNode request = params.path("request");
                    ObjectNode r = hub.row("bidi", "net", "request");
                    r.put("requestId", request.path("request").asText()).put("url", request.path("url").asText(""))
                            .put("method", request.path("method").asText(""));
                    yield r;
                }
                case "network.responseCompleted" -> {
                    JsonNode request = params.path("request");
                    JsonNode response = params.path("response");
                    ObjectNode r = hub.row("bidi", "net", "finished");
                    r.put("requestId", request.path("request").asText()).put("url", response.path("url").asText(request.path("url").asText("")))
                            .put("status", response.path("status").asInt()).put("text", response.path("mimeType").asText(""))
                            .put("bytes", response.path("bytesReceived").asLong());
                    JsonNode timings = request.path("timings");
                    if (timings.has("responseEnd") && timings.has("requestTime")) {
                        r.put("durationMs", Math.round(timings.path("responseEnd").asDouble() - timings.path("requestTime").asDouble()));
                    }
                    yield r;
                }
                case "network.fetchError" -> {
                    JsonNode request = params.path("request");
                    ObjectNode r = hub.row("bidi", "net", "failed");
                    r.put("requestId", request.path("request").asText()).put("url", request.path("url").asText(""))
                            .put("text", params.path("errorText").asText("")).put("level", "error");
                    yield r;
                }
                case "browsingContext.navigationStarted" -> navRow(hub, params, "started");
                case "browsingContext.load" -> navRow(hub, params, "load");
                case "browsingContext.domContentLoaded" -> navRow(hub, params, "dom-content-loaded");
                case "browsingContext.fragmentNavigated" -> navRow(hub, params, "same-document");
                case "browsingContext.navigationFailed" -> navRow(hub, params, "failed");
                case "browsingContext.userPromptOpened" -> {
                    ObjectNode r = hub.row("bidi", "dialog", "opened");
                    r.put("text", params.path("message").asText("")).put("method", params.path("type").asText(""))
                            .put("context", params.path("context").asText(""));
                    yield r;
                }
                case "browsingContext.userPromptClosed" -> {
                    ObjectNode r = hub.row("bidi", "dialog", "closed");
                    r.put("text", params.path("accepted").asBoolean() ? "accepted" : "dismissed").put("context", params.path("context").asText(""));
                    yield r;
                }
                case "browsingContext.contextCreated" -> {
                    ObjectNode r = hub.row("bidi", "target", "created");
                    r.put("url", params.path("url").asText("")).put("context", params.path("context").asText(""));
                    yield r;
                }
                case "browsingContext.contextDestroyed" -> {
                    ObjectNode r = hub.row("bidi", "target", "destroyed");
                    r.put("context", params.path("context").asText(""));
                    yield r;
                }
                default -> null;
            };
            if (row != null) hub.add(row);
        });
        List<String> events = new ArrayList<>(BIDI_EVENTS);
        bidi.call("session.subscribe", Map.of("events", events));
    }

    private static ObjectNode navRow(BrowserEventHub hub, JsonNode params, String subkind) {
        ObjectNode r = hub.row("bidi", "nav", subkind);
        r.put("url", params.path("url").asText("")).put("context", params.path("context").asText(""));
        if (params.hasNonNull("navigation")) r.put("requestId", "nav:" + params.path("navigation").asText() + ":" + subkind);
        return r;
    }

    static String level(String type) {
        return switch (type) {
            case "error", "assert" -> "error";
            case "warning" -> "warning";
            case "debug" -> "debug";
            default -> "info";
        };
    }

    static String consoleText(JsonNode args) {
        StringBuilder out = new StringBuilder();
        for (JsonNode arg : args) {
            if (out.length() > 0) out.append(' ');
            if (arg.has("value")) out.append(arg.get("value").isTextual() ? arg.get("value").asText() : arg.get("value").toString());
            else if (arg.has("unserializableValue")) out.append(arg.get("unserializableValue").asText());
            else out.append(arg.path("description").asText(arg.path("type").asText("")));
        }
        return out.toString();
    }
}
