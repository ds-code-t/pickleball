package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.HarContentPolicy;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * E4: response bodies on demand ({@code net --capture-bodies}, {@code net-body}), WebSocket / EventSource rows, and
 * HAR 1.2 ({@code har --start/--stop}). Body sources in order: BiDi {@code network.addDataCollector}/{@code getData},
 * CDP {@code Network.getResponseBody}, Playwright {@code response.body()}. HAR: Playwright {@code Tracing.startHar}
 * when Playwright serves the session, else {@link HarWriter} fed from the event rows.
 */
final class NetCommands {
    static final int BODY_KEEP = 200;

    private NetCommands() {
    }

    /** Capture settings and the bounded body store, per host. */
    static final class Bodies {
        volatile String mode = "none";
        volatile int maxKb = 256;
        final Map<String, String[]> meta = new ConcurrentHashMap<>();
        final Map<String, ObjectNode> store = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
                return size() > BODY_KEEP;
            }
        });

        boolean wants(String type, String mime) {
            if ("all".equals(mode)) return true;
            if (!"api".equals(mode)) return false;
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
            boolean api = t.equals("xhr") || t.equals("fetch");
            return api && (m.contains("json") || m.startsWith("text/") || m.contains("xml"));
        }
    }

    static Bodies bodies(ProtoHost host) {
        return host.state("net.bodies", Bodies::new);
    }

    static void register() {
        Projectors.extraCdp.add(NetCommands::cdp);
        ProtoHost.register("net", NetCommands::net);
        ProtoHost.register("net-body", NetCommands::netBody);
        ProtoHost.register("har", NetCommands::har);
    }

    /** Body capture bookkeeping and WebSocket / EventSource rows. Returns true only for events it fully owns. */
    static boolean cdp(String method, JsonNode params, String sessionId, BrowserEventHub hub, ProtoHost host) {
        Bodies b = bodies(host);
        String id = params.path("requestId").asText("");
        switch (method) {
            case "Network.requestWillBeSent" -> {
                b.meta.put(id, new String[]{params.path("type").asText(""), "", sessionId});
                return false;
            }
            case "Network.responseReceived" -> {
                String[] m = b.meta.get(id);
                if (m != null) m[1] = params.path("response").path("mimeType").asText("");
                return false;
            }
            case "Network.loadingFinished" -> {
                String[] m = b.meta.remove(id);
                if (m != null && b.wants(m[0], m[1])) {
                    try {
                        JsonNode body = host.cdp().send(m[2], "Network.getResponseBody", Map.of("requestId", id));
                        keep(host, id, body.path("body").asText(""), body.path("base64Encoded").asBoolean(), m[1], "cdp");
                    } catch (RuntimeException gone) {
                        // Evicted or a redirect without a body.
                    }
                }
                return false;
            }
            case "Network.webSocketCreated" -> ws(hub, host, sessionId, "created", id, params.path("url").asText(""), null, null);
            case "Network.webSocketHandshakeResponseReceived" -> {
                ObjectNode r = ws(hub, host, sessionId, "handshake", id, null, null, null);
                if (r != null) r.put("status", params.path("response").path("status").asInt());
            }
            case "Network.webSocketFrameSent", "Network.webSocketFrameReceived" -> {
                JsonNode frame = params.path("response");
                ws(hub, host, sessionId, method.endsWith("Sent") ? "frame-sent" : "frame-received", id, null,
                        frame.path("payloadData").asText(""), frame.path("opcode").asInt());
            }
            case "Network.webSocketClosed" -> ws(hub, host, sessionId, "closed", id, null, null, null);
            case "Network.webSocketFrameError" -> ws(hub, host, sessionId, "error", id, null, params.path("errorMessage").asText(""), null);
            case "Network.eventSourceMessageReceived" -> ws(hub, host, sessionId, "eventsource-message", id, null,
                    params.path("data").asText(""), null);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static final Map<String, String> WS_URLS = new ConcurrentHashMap<>();

    private static ObjectNode ws(BrowserEventHub hub, ProtoHost host, String sessionId, String subkind, String id, String url,
                                 String payload, Integer opcode) {
        if (url != null) WS_URLS.put(id, url);
        ObjectNode r = hub.row("cdp", "ws", subkind);
        r.put("requestId", id).put("url", WS_URLS.getOrDefault(id, ""));
        if (payload != null) {
            r.put("bytes", payload.length());
            r.put("text", payload.length() > 200 ? payload.substring(0, 200) : payload);
        }
        if (opcode != null) r.put("method", "opcode-" + opcode);
        CdpSession.Target t = host.cdp().target(sessionId);
        if (t != null) r.put("context", t.targetId);
        if ("closed".equals(subkind)) WS_URLS.remove(id);
        return hub.add(r) == null ? null : r;
    }

    static void keep(ProtoHost host, String id, String body, boolean base64, String mime, String source) {
        Bodies b = bodies(host);
        int cap = Math.max(1, b.maxKb) * 1024;
        ObjectNode stored = ProtoJson.obj().put("requestId", id).put("mime", mime == null ? "" : mime).put("source", source).put("base64", base64);
        if (base64) {
            byte[] bytes = Base64.getDecoder().decode(body);
            stored.put("bytes", bytes.length).put("truncated", bytes.length > cap);
            byte[] kept = bytes.length > cap ? java.util.Arrays.copyOf(bytes, cap) : bytes;
            stored.put("body", Base64.getEncoder().encodeToString(kept));
        } else {
            var redacted = ProtoJson.MAPPER.createArrayNode();
            String text = mime != null && mime.contains("json") ? ProtoRedact.body(host.project(), body, redacted) : ProtoRedact.text(body);
            int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            stored.put("bytes", bytes).put("truncated", bytes > cap);
            stored.put("body", bytes > cap ? new String(java.util.Arrays.copyOf(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), cap),
                    java.nio.charset.StandardCharsets.UTF_8) : text);
            if (!redacted.isEmpty()) stored.set("redacted", redacted);
        }
        b.store.put(id, stored);
    }

    /** {@code net --capture-bodies=none|api|all [--max-kb=256]}. */
    static ObjectNode net(ProtoHost host, Map<String, String> f) {
        Bodies b = bodies(host);
        if (!host.stateMap().containsKey("events.source")) EventCommands.start(host, Map.of());
        String mode = f.getOrDefault("capture-bodies", b.mode).toLowerCase(Locale.ROOT);
        if (!List.of("none", "api", "all").contains(mode)) {
            throw new IllegalArgumentException("error: --capture-bodies is none, api or all\nfix: net --capture-bodies=api --max-kb=256");
        }
        b.mode = mode;
        b.maxKb = EventCommands.parseInt(f.get("max-kb"), b.maxKb);
        String via = String.valueOf(host.stateMap().get("events.source"));
        if (!"none".equals(mode) && "bidi".equals(via) && host.stateMap().putIfAbsent("net.collector", true) == null) {
            JsonNode c = host.bidiModules().addDataCollector(List.of("response"), b.maxKb * 1024);
            host.stateMap().put("net.collector.id", c.path("collector").asText(""));
        }
        if (!"none".equals(mode) && "playwright".equals(via) && host.stateMap().putIfAbsent("net.pwbodies", true) == null) {
            host.pw().call(connection -> {
                for (BrowserContext context : connection.browser().contexts()) {
                    Consumer<com.microsoft.playwright.Request> grab = request -> {
                        try {
                            com.microsoft.playwright.Response response = request.response();
                            if (response == null) return;
                            String mime = response.headerValue("content-type");
                            if (!bodies(host).wants(request.resourceType(), mime)) return;
                            byte[] raw = response.body();
                            boolean text = mime != null && (mime.contains("json") || mime.startsWith("text/") || mime.contains("xml"));
                            keep(host, "pw:" + System.identityHashCode(request),
                                    text ? new String(raw, java.nio.charset.StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(raw),
                                    !text, mime, "playwright");
                        } catch (RuntimeException ignored) {
                            // The body is gone.
                        }
                    };
                    context.onRequestFinished(grab);
                }
                return null;
            });
        }
        return ProtoJson.obj().put("op", "net").put("via", via).put("captureBodies", b.mode).put("maxKb", b.maxKb).put("stored", b.store.size());
    }

    /** {@code net-body --request-id=<id> [--out=<file>]}. Binary bodies are written only to a file. */
    static ObjectNode netBody(ProtoHost host, Map<String, String> f) throws Exception {
        String id = f.getOrDefault("request-id", "");
        if (id.isBlank()) throw new IllegalArgumentException("error: net-body needs --request-id=<id>\nfix: take requestId from a net row");
        ObjectNode stored = bodies(host).store.get(id);
        if (stored == null && host.stateMap().containsKey("net.collector")) {
            JsonNode data = host.bidiModules().getData(id, "response").path("bytes");
            boolean b64 = "base64".equals(data.path("type").asText());
            keep(host, id, data.path("value").asText(""), b64, "", "bidi");
            stored = bodies(host).store.get(id);
        }
        if (stored == null) {
            throw new IllegalArgumentException("error: no captured body for " + id
                    + "\nfix: net --capture-bodies=api (or all) before the request, then net-body again");
        }
        ObjectNode out = ProtoJson.obj().put("op", "net-body").put("requestId", id).put("source", stored.path("source").asText())
                .put("mime", stored.path("mime").asText()).put("bytes", stored.path("bytes").asLong()).put("truncated", stored.path("truncated").asBoolean());
        if (stored.has("redacted")) out.set("redacted", stored.get("redacted"));
        String file = f.get("out");
        boolean binary = stored.path("base64").asBoolean();
        if (file != null && !file.isBlank()) {
            Path path = host.project().resolve(file).normalize();
            Files.createDirectories(path.toAbsolutePath().getParent());
            byte[] bytes = binary ? Base64.getDecoder().decode(stored.path("body").asText())
                    : stored.path("body").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return out.put("out", path.toString());
        }
        if (binary) throw new IllegalArgumentException("error: the body of " + id + " is binary\nfix: net-body --request-id=" + id + " --out=<file>");
        return out.put("body", stored.path("body").asText());
    }

    /** {@code har --start [--content=omit|embed] [--url-filter=<glob|re:>] | --stop --out=<file> | --status}. */
    static synchronized ObjectNode har(ProtoHost host, Map<String, String> f) throws Exception {
        Map<String, Object> state = host.stateMap();
        if (f.containsKey("start")) {
            if (state.containsKey("har.mode")) throw new IllegalArgumentException("error: a HAR is already recording\nfix: har --stop --out=<file>");
            if (!state.containsKey("events.source")) EventCommands.start(host, Map.of());
            String content = f.getOrDefault("content", "omit");
            if (!List.of("omit", "embed", "attach").contains(content)) throw new IllegalArgumentException("error: --content is omit, embed or attach\nfix: har --start --content=embed");
            String filter = f.get("url-filter");
            BrowserProtocols.Served<String> served = host.protocols().serve("har", f.get("via"), backend -> switch (backend) {
                case PLAYWRIGHT -> {
                    Path temp = ProtoHost.dir(host.project()).resolve("har-" + System.nanoTime() + ".har");
                    host.pw().call(connection -> {
                        BrowserContext context = connection.browser().contexts().get(0);
                        Tracing.StartHarOptions options = new Tracing.StartHarOptions()
                                .setContent("embed".equals(content) ? HarContentPolicy.EMBED : "attach".equals(content) ? HarContentPolicy.ATTACH : HarContentPolicy.OMIT);
                        if (filter != null && !filter.isBlank()) options.setUrlFilter(filter);
                        context.tracing().startHar(temp, options);
                        return null;
                    });
                    state.put("har.file", temp);
                    yield "playwright";
                }
                case BIDI, CDP -> {
                    List<JsonNode> rows = java.util.Collections.synchronizedList(new ArrayList<>());
                    Consumer<ObjectNode> tap = row -> {
                        if ("net".equals(row.path("kind").asText())) rows.add(row);
                    };
                    host.hub().tap(tap);
                    state.put("har.rows", rows);
                    state.put("har.tap", tap);
                    if ("embed".equals(content)) bodies(host).mode = bodies(host).mode.equals("none") ? "all" : bodies(host).mode;
                    yield backend.id();
                }
                default -> throw new UnsupportedProtocolOperation("har", backend.id(), null);
            });
            state.put("har.mode", served.value());
            state.put("har.content", content);
            if (filter != null) state.put("har.filter", filter);
            return ProtoJson.obj().put("op", "har").put("via", served.value()).put("recording", true).put("content", content);
        }
        if (f.containsKey("stop")) {
            Object mode = state.remove("har.mode");
            if (mode == null) throw new IllegalArgumentException("error: no HAR is recording\nfix: har --start first");
            String file = f.getOrDefault("out", "");
            if (file.isBlank()) file = ".pickleball/runs/" + host.run() + "/session.har";
            Path out = host.project().resolve(file).normalize();
            Files.createDirectories(out.toAbsolutePath().getParent());
            int entries;
            if ("playwright".equals(mode)) {
                Path temp = (Path) state.remove("har.file");
                host.pw().call(connection -> {
                    connection.browser().contexts().get(0).tracing().stopHar();
                    return null;
                });
                Files.move(temp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                entries = ProtoJson.MAPPER.readTree(out.toFile()).path("log").path("entries").size();
            } else {
                @SuppressWarnings("unchecked")
                Consumer<ObjectNode> tap = (Consumer<ObjectNode>) state.remove("har.tap");
                host.hub().untap(tap);
                @SuppressWarnings("unchecked")
                List<JsonNode> rows = (List<JsonNode>) state.remove("har.rows");
                List<JsonNode> copy;
                synchronized (rows) {
                    copy = new ArrayList<>(rows);
                }
                ObjectNode har = HarWriter.build(copy, String.valueOf(state.get("har.content")), (String) state.remove("har.filter"),
                        id -> bodies(host).store.get(id));
                ProtoJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), har);
                entries = har.path("log").path("entries").size();
            }
            ProtoHost.ownerOnly(out);
            state.remove("har.content");
            return ProtoJson.obj().put("op", "har").put("via", String.valueOf(mode)).put("out", out.toString()).put("entries", entries);
        }
        Object mode = state.get("har.mode");
        return ProtoJson.obj().put("op", "har").put("recording", mode != null).put("via", mode == null ? "none" : String.valueOf(mode));
    }

    /** Playwright WebSocket rows (E2's listen() handles the rest of a page). */
    static void playwrightWs(Page page, BrowserEventHub hub) {
        page.onWebSocket(socket -> {
            String id = "pw-ws:" + System.identityHashCode(socket);
            hub.add(hub.row("playwright", "ws", "created").put("requestId", id).put("url", socket.url()));
            socket.onFrameSent(frame -> hub.add(frame(hub, "frame-sent", id, socket.url(), frame.text())));
            socket.onFrameReceived(frame -> hub.add(frame(hub, "frame-received", id, socket.url(), frame.text())));
            socket.onClose(s -> hub.add(hub.row("playwright", "ws", "closed").put("requestId", id).put("url", s.url())));
            socket.onSocketError(error -> hub.add(hub.row("playwright", "ws", "error").put("requestId", id).put("url", socket.url()).put("text", error)));
        });
    }

    private static ObjectNode frame(BrowserEventHub hub, String subkind, String id, String url, String text) {
        String t = text == null ? "" : text;
        return hub.row("playwright", "ws", subkind).put("requestId", id).put("url", url).put("bytes", t.length())
                .put("text", t.length() > 200 ? t.substring(0, 200) : t);
    }
}
