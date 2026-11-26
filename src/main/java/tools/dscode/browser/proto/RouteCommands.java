package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.WebSocketRoute;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.RpcChannel;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * E5: network control on every backend. Routes ({@code net-route}, {@code net-unroute}, {@code net-block}), extra
 * headers ({@code net-headers}), credentials ({@code net-auth}), WebSocket mocks ({@code ws-route}), network
 * conditions ({@code emulate --network}), cache ({@code net-cache}), certificate errors ({@code security}) and CSP
 * bypass ({@code csp}). Backend order: Playwright route, BiDi network intercepts, CDP Fetch. Every routed request is a
 * {@code kind=net subkind=routed} row; routes and overrides are removed when the host stops.
 */
final class RouteCommands {
    private RouteCommands() {
    }

    /** One route. {@code times < 0} is unlimited. */
    static final class Rule {
        final String id;
        final String match;
        final Pattern pattern;
        final String action;
        final int status;
        final String body;
        final Map<String, String> headers;
        final long delayMs;
        final AtomicInteger left;
        final AtomicInteger hits = new AtomicInteger();
        volatile String via = "";

        Rule(String id, String match, String action, int status, String body, Map<String, String> headers, long delayMs, int times) {
            this.id = id;
            this.match = match;
            this.pattern = pattern(match);
            this.action = action;
            this.status = status;
            this.body = body;
            this.headers = headers;
            this.delayMs = delayMs;
            this.left = new AtomicInteger(times);
        }

        boolean matches(String url) {
            return left.get() != 0 && pattern.matcher(url == null ? "" : url).find();
        }

        /** Takes one use; false when another request used the last one first. */
        boolean take() {
            while (true) {
                int now = left.get();
                if (now == 0) return false;
                if (now < 0 || left.compareAndSet(now, now - 1)) {
                    hits.incrementAndGet();
                    return true;
                }
            }
        }

        ObjectNode json() {
            ObjectNode o = ProtoJson.obj().put("id", id).put("match", match).put("action", action).put("via", via).put("hits", hits.get());
            o.put("timesLeft", left.get());
            if (status > 0) o.put("status", status);
            if (delayMs > 0) o.put("delayMs", delayMs);
            return o;
        }
    }

    /** Routes, extra headers and credentials for one host. */
    static final class State {
        final List<Rule> rules = new CopyOnWriteArrayList<>();
        final Map<String, String> extraHeaders = new java.util.concurrent.ConcurrentHashMap<>();
        volatile String[] credential;
        final AtomicInteger next = new AtomicInteger();
        final java.util.Set<String> cdpFetch = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean cdpHooked;
        volatile String bidiIntercept;
        volatile boolean bidiHooked;
        volatile boolean pwHooked;
        final List<Object> wsRoutes = new CopyOnWriteArrayList<>();
        final ScheduledExecutorService delays = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pkb-route-delay");
            t.setDaemon(true);
            return t;
        });
    }

    static State state(ProtoHost host) {
        return host.state("route.state", () -> {
            State s = new State();
            host.onStop(() -> clearAll(host, s));
            return s;
        });
    }

    static void register() {
        ProtoHost.register("net-route", RouteCommands::route);
        ProtoHost.register("net-unroute", RouteCommands::unroute);
        ProtoHost.register("net-block", (host, f) -> {
            Map<String, String> g = new LinkedHashMap<>(f);
            g.put("action", "abort");
            g.putIfAbsent("id", "block-" + state(host).next.incrementAndGet());
            ObjectNode out = route(host, g);
            return out.put("op", "net-block");
        });
        ProtoHost.register("net-headers", RouteCommands::headers);
        ProtoHost.register("net-auth", RouteCommands::auth);
        ProtoHost.register("ws-route", RouteCommands::wsRoute);
        ProtoHost.register("net-cache", RouteCommands::cache);
        ProtoHost.register("net-conditions", RouteCommands::conditions);
        ProtoHost.register("security", RouteCommands::security);
        ProtoHost.register("csp", RouteCommands::csp);
        ProtoHost.register("net-routes", (host, f) -> list(host));
    }

    /** {@code glob} matches the whole URL ({@code *} any run, {@code ?} one character); {@code re:} is a regex find; text without wildcards is a substring. */
    static Pattern pattern(String match) {
        if (match == null || match.isBlank()) return Pattern.compile("");
        if (match.startsWith("re:")) return Pattern.compile(match.substring(3));
        if (!match.contains("*") && !match.contains("?")) return Pattern.compile(Pattern.quote(match));
        StringBuilder regex = new StringBuilder("^");
        for (char c : match.toCharArray()) {
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString());
    }

    static ObjectNode list(ProtoHost host) {
        State s = state(host);
        ObjectNode out = ProtoJson.obj().put("op", "net-routes");
        ArrayNode rules = out.putArray("routes");
        for (Rule rule : s.rules) rules.add(rule.json());
        ObjectNode headers = out.putObject("extraHeaders");
        s.extraHeaders.keySet().forEach(name -> headers.put(name, "[redacted]"));
        out.put("auth", s.credential != null);
        out.put("wsRoutes", s.wsRoutes.size());
        return out;
    }

    private static String text(ProtoHost host, String value) throws Exception {
        if (value == null) return null;
        if (value.startsWith("@")) return Files.readString(host.project().resolve(value.substring(1)).normalize(), StandardCharsets.UTF_8);
        return value;
    }

    static Map<String, String> headerMap(ProtoHost host, String value) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = text(host, value);
        if (raw == null || raw.isBlank()) return out;
        JsonNode json = ProtoJson.MAPPER.readTree(raw);
        if (!json.isObject()) throw new IllegalArgumentException("error: headers must be a JSON object\nfix: --headers='{\"X-Test\":\"1\"}' or --headers=@headers.json");
        json.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    static ObjectNode route(ProtoHost host, Map<String, String> f) throws Exception {
        String match = f.getOrDefault("match", "");
        if (match.isBlank()) throw new IllegalArgumentException("error: net-route needs --match=<glob|re:regex>\nfix: net-route --match='*/api/*' --action=fulfill --body='{}'");
        String action = f.getOrDefault("action", "fulfill").toLowerCase(Locale.ROOT);
        if (!List.of("fulfill", "abort", "continue", "modify").contains(action)) {
            throw new IllegalArgumentException("error: --action is fulfill, abort, continue or modify\nfix: net-route --match=<glob> --action=fulfill");
        }
        State s = state(host);
        String id = f.getOrDefault("id", "r" + s.next.incrementAndGet());
        for (Rule old : s.rules) if (old.id.equals(id)) s.rules.remove(old);
        int status = EventCommands.parseInt(f.get("status"), "fulfill".equals(action) ? 200 : 0);
        Rule rule = new Rule(id, match, action, status, text(host, f.get("body")), headerMap(host, f.get("headers")),
                EventCommands.parseInt(f.get("delay-ms"), 0), EventCommands.parseInt(f.get("times"), -1));
        BrowserProtocols.Served<String> served = host.protocols().serve("net-route", f.get("via"), backend -> {
            hook(host, s, backend);
            return backend.id();
        });
        rule.via = served.value();
        s.rules.add(0, rule);
        host.log("net-route " + id + " " + action + " " + match + " via=" + rule.via);
        return ProtoJson.obj().put("op", "net-route").put("via", rule.via).put("id", id).set("route", rule.json());
    }

    static ObjectNode unroute(ProtoHost host, Map<String, String> f) {
        State s = state(host);
        List<String> removed = new ArrayList<>();
        if (f.containsKey("all")) {
            for (Rule rule : s.rules) removed.add(rule.id);
            s.rules.clear();
        } else {
            String id = f.getOrDefault("id", "");
            if (id.isBlank()) throw new IllegalArgumentException("error: net-unroute needs --id=<id> or --all\nfix: net-routes lists the ids");
            for (Rule rule : s.rules) if (rule.id.equals(id)) {
                s.rules.remove(rule);
                removed.add(id);
            }
            if (removed.isEmpty()) throw new IllegalArgumentException("error: no route " + id + "\nfix: doctor --protocols lists the routes");
        }
        if (s.rules.isEmpty() && s.extraHeaders.isEmpty() && s.credential == null) unhook(host, s);
        ObjectNode out = ProtoJson.obj().put("op", "net-unroute");
        ArrayNode ids = out.putArray("removed");
        removed.forEach(ids::add);
        return out.put("left", s.rules.size());
    }

    static ObjectNode headers(ProtoHost host, Map<String, String> f) throws Exception {
        State s = state(host);
        if (f.containsKey("clear")) s.extraHeaders.clear();
        else {
            Map<String, String> set = headerMap(host, f.get("set"));
            if (set.isEmpty()) throw new IllegalArgumentException("error: net-headers needs --set=<json|@file> or --clear\nfix: net-headers --set='{\"X-Test\":\"1\"}'");
            s.extraHeaders.putAll(set);
        }
        BrowserProtocols.Served<String> served = host.protocols().serve("net-headers", f.get("via"), backend -> {
            switch (backend) {
                case PLAYWRIGHT -> host.pw().call(c -> {
                    for (BrowserContext context : c.browser().contexts()) context.setExtraHTTPHeaders(new LinkedHashMap<>(s.extraHeaders));
                    return null;
                });
                case BIDI -> {
                    List<Map<String, ?>> list = new ArrayList<>();
                    s.extraHeaders.forEach((k, v) -> list.add(BidiHeaders.header(k, v)));
                    try {
                        host.bidiModules().setExtraHeaders(list, null);
                    } catch (RuntimeException unknown) {
                        // Older BiDi without network.setExtraHeaders: the intercept adds them instead.
                        hook(host, s, backend);
                    }
                }
                case CDP -> {
                    for (CdpSession.Target t : pages(host)) {
                        host.cdp().send(t.sessionId, "Network.enable", Map.of());
                        host.cdp().send(t.sessionId, "Network.setExtraHTTPHeaders", Map.of("headers", new LinkedHashMap<>(s.extraHeaders)));
                    }
                    if (host.stateMap().putIfAbsent("route.cdp.headersHook", true) == null) {
                        host.cdp().onTarget(t -> {
                            if (!"page".equals(t.type) || s.extraHeaders.isEmpty()) return;
                            host.cdp().send(t.sessionId, "Network.enable", Map.of());
                            host.cdp().send(t.sessionId, "Network.setExtraHTTPHeaders", Map.of("headers", new LinkedHashMap<>(s.extraHeaders)));
                        });
                    }
                }
                default -> throw new UnsupportedProtocolOperation("net-headers", backend.id(), null);
            }
            return backend.id();
        });
        host.log("net-headers " + (f.containsKey("clear") ? "clear" : "set " + s.extraHeaders.keySet()) + " via=" + served.value());
        ObjectNode out = ProtoJson.obj().put("op", "net-headers").put("via", served.value());
        ArrayNode names = out.putArray("names");
        s.extraHeaders.keySet().forEach(names::add);
        return out;
    }

    /** {@code _credential} is resolved by the CLI from the secret ref; it is never logged or echoed. */
    static ObjectNode auth(ProtoHost host, Map<String, String> f) {
        State s = state(host);
        if (f.containsKey("clear")) s.credential = null;
        else {
            String value = f.getOrDefault("_credential", "");
            int colon = value.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("error: the credential must resolve to user:password\nfix: net-auth --credential=env:SITE_LOGIN where SITE_LOGIN=user:password");
            }
            s.credential = new String[]{value.substring(0, colon), value.substring(colon + 1)};
        }
        String via = skip(host, "net-auth", f.get("via"), BrowserProtocols.Backend.PLAYWRIGHT);
        BrowserProtocols.Served<String> served = host.protocols().serve("net-auth", via, backend -> {
            if (backend == BrowserProtocols.Backend.PLAYWRIGHT) {
                // Playwright sets HTTP credentials only when a context is created.
                throw new IllegalStateException("error: unsupported: net-auth on a live Playwright context\nfix: use --via=bidi or --via=cdp");
            }
            hook(host, s, backend);
            return backend.id();
        });
        host.log("net-auth " + (s.credential == null ? "cleared" : "set ref=" + f.getOrDefault("credential", "")) + " via=" + served.value());
        return ProtoJson.obj().put("op", "net-auth").put("via", served.value()).put("credential", s.credential == null ? "none" : "[redacted]");
    }

    static ObjectNode wsRoute(ProtoHost host, Map<String, String> f) throws Exception {
        String match = f.getOrDefault("match", "");
        String raw = text(host, f.get("rules"));
        if (match.isBlank() || raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("error: ws-route needs --match=<glob> and --rules=<json|@file>\nfix: ws-route --match='*/socket' --rules='[{\"on\":\"ping\",\"reply\":\"pong\"}]'");
        }
        JsonNode rules = ProtoJson.MAPPER.readTree(raw);
        if (!rules.isArray()) throw new IllegalArgumentException("error: --rules is a JSON array of {on, reply, close}\nfix: --rules='[{\"on\":\"ping\",\"reply\":\"pong\"}]'");
        BrowserProtocols.Backend backend = host.protocols().resolve("ws-route", f.get("via") == null ? "playwright" : f.get("via"));
        if (backend != BrowserProtocols.Backend.PLAYWRIGHT) throw host.protocols().unsupported("ws-route", f.get("via"));
        State s = state(host);
        Pattern pattern = pattern(match);
        Predicate<String> urls = url -> pattern.matcher(url).find();
        Consumer<WebSocketRoute> handler = ws -> ws.onMessage(message -> {
            String text = message.text() != null ? message.text() : new String(message.binary(), StandardCharsets.UTF_8);
            for (JsonNode rule : rules) {
                String on = rule.path("on").asText("");
                boolean hit = on.startsWith("re:") ? Pattern.compile(on.substring(3)).matcher(text).find() : on.isEmpty() || text.contains(on);
                if (!hit) continue;
                host.hub().add(host.hub().row("playwright", "net", "routed").put("url", ws.url()).put("method", "ws-route").put("text", on));
                if (rule.has("reply")) ws.send(rule.path("reply").asText());
                if (rule.path("close").asBoolean(false) || rule.has("closeCode")) ws.close();
                return;
            }
        });
        host.pw().call(c -> {
            for (BrowserContext context : c.browser().contexts()) context.routeWebSocket(urls, handler);
            return null;
        });
        host.pw().listening(true);
        s.wsRoutes.add(handler);
        host.log("ws-route " + match + " rules=" + rules.size() + " via=playwright");
        return ProtoJson.obj().put("op", "ws-route").put("via", "playwright").put("match", match).put("rules", rules.size());
    }

    static ObjectNode cache(ProtoHost host, Map<String, String> f) {
        boolean disable = f.containsKey("disable") && !"false".equals(f.get("disable"));
        if (!disable && !f.containsKey("enable")) throw new IllegalArgumentException("error: net-cache needs --disable or --enable\nfix: net-cache --disable");
        BrowserProtocols.Served<String> served = host.protocols().serve("net-cache", f.get("via"), backend -> {
            switch (backend) {
                case BIDI -> host.bidiModules().setCacheBehavior(disable);
                case CDP -> {
                    for (CdpSession.Target t : pages(host)) {
                        host.cdp().send(t.sessionId, "Network.enable", Map.of());
                        host.cdp().send(t.sessionId, "Network.setCacheDisabled", Map.of("cacheDisabled", disable));
                    }
                }
                default -> throw new UnsupportedProtocolOperation("net-cache", backend.id(), null);
            }
            return backend.id();
        });
        host.stateMap().put("route.cache", disable ? "disabled" : "enabled");
        if (disable) host.onStop(() -> {
            try {
                cache(host, Map.of("enable", "true", "via", served.value()));
            } catch (RuntimeException ignored) {
                // The browser is gone.
            }
        });
        return ProtoJson.obj().put("op", "net-cache").put("via", served.value()).put("cache", disable ? "disabled" : "enabled");
    }

    /** {@code emulate --network=offline|online|slow-3g|fast-3g|<downKbps,upKbps,latencyMs>}. */
    static ObjectNode conditions(ProtoHost host, Map<String, String> f) {
        String raw = f.getOrDefault("network", "").trim().toLowerCase(Locale.ROOT);
        double down;
        double up;
        double latency;
        boolean offline = "offline".equals(raw);
        switch (raw) {
            case "offline", "online", "none", "reset" -> {
                down = -1;
                up = -1;
                latency = 0;
            }
            case "slow-3g" -> {
                down = 400;
                up = 400;
                latency = 2000;
            }
            case "fast-3g" -> {
                down = 1600;
                up = 750;
                latency = 562.5;
            }
            default -> {
                String[] parts = raw.split(",");
                if (parts.length != 3) {
                    throw new IllegalArgumentException("error: --network is offline, online, slow-3g, fast-3g or <downKbps,upKbps,latencyMs>\nfix: emulate --network=offline");
                }
                try {
                    down = Double.parseDouble(parts[0]);
                    up = Double.parseDouble(parts[1]);
                    latency = Double.parseDouble(parts[2]);
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException("error: --network numbers are kbps,kbps,ms\nfix: emulate --network=1600,750,150");
                }
            }
        }
        boolean throttled = down >= 0;
        String via = throttled ? skip(host, "net-conditions", f.get("via"), BrowserProtocols.Backend.PLAYWRIGHT, BrowserProtocols.Backend.BIDI) : f.get("via");
        BrowserProtocols.Served<String> served = host.protocols().serve("net-conditions", via, backend -> {
            switch (backend) {
                case PLAYWRIGHT -> {
                    if (throttled) throw new IllegalStateException("error: unsupported: throttling on playwright (offline only)\nfix: use --via=cdp");
                    host.pw().call(c -> {
                        for (BrowserContext context : c.browser().contexts()) context.setOffline(offline);
                        return null;
                    });
                }
                case BIDI -> {
                    if (throttled) throw new IllegalStateException("error: unsupported: throttling on bidi (offline only)\nfix: use --via=cdp");
                    host.bidiModules().setNetworkConditions(null, offline);
                }
                case CDP -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("offline", offline);
                    p.put("latency", latency);
                    p.put("downloadThroughput", throttled ? down * 1024 / 8 : -1);
                    p.put("uploadThroughput", throttled ? up * 1024 / 8 : -1);
                    for (CdpSession.Target t : pages(host)) {
                        host.cdp().send(t.sessionId, "Network.enable", Map.of());
                        try {
                            host.cdp().send(t.sessionId, "Network.emulateNetworkConditions", p);
                        } catch (RuntimeException removed) {
                            // emulateNetworkConditions is deprecated; overrideNetworkState is its replacement.
                            p.keySet().retainAll(List.of("offline", "latency", "downloadThroughput", "uploadThroughput"));
                            host.cdp().send(t.sessionId, "Network.overrideNetworkState", p);
                        }
                    }
                }
                default -> throw new UnsupportedProtocolOperation("net-conditions", backend.id(), null);
            }
            return backend.id();
        });
        String applied = raw.isEmpty() || "none".equals(raw) || "reset".equals(raw) ? "online" : raw;
        host.stateMap().put("emulate.network", applied);
        if (!"online".equals(applied) && host.stateMap().putIfAbsent("route.conditions.cleanup", true) == null) {
            host.onStop(() -> {
                try {
                    conditions(host, Map.of("network", "online", "via", served.value()));
                } catch (RuntimeException ignored) {
                    // The browser is gone.
                }
            });
        }
        host.log("emulate --network=" + applied + " via=" + served.value());
        return ProtoJson.obj().put("op", "emulate").put("via", served.value()).put("network", applied);
    }

    static ObjectNode security(ProtoHost host, Map<String, String> f) {
        if (!f.containsKey("ignore-cert-errors")) throw new IllegalArgumentException("error: security needs --ignore-cert-errors[=false]\nfix: security --ignore-cert-errors");
        boolean ignore = !"false".equalsIgnoreCase(f.get("ignore-cert-errors"));
        BrowserProtocols.Served<String> served = host.protocols().serve("security", f.get("via"), backend -> {
            if (backend != BrowserProtocols.Backend.CDP) throw new UnsupportedProtocolOperation("security", backend.id(), null);
            host.cdp().browser("Security.setIgnoreCertificateErrors", Map.of("ignore", ignore));
            return backend.id();
        });
        host.log("security ignore-cert-errors=" + ignore + " via=" + served.value());
        if (ignore) host.onStop(() -> {
            try {
                host.cdp().browser("Security.setIgnoreCertificateErrors", Map.of("ignore", false));
            } catch (RuntimeException ignored) {
                // The browser is gone.
            }
        });
        return ProtoJson.obj().put("op", "security").put("via", served.value()).put("ignoreCertErrors", ignore)
                .put("note", "BiDi sets acceptInsecureCerts only when a session is created; this live switch is CDP Security.setIgnoreCertificateErrors");
    }

    static ObjectNode csp(ProtoHost host, Map<String, String> f) {
        if (!f.containsKey("bypass")) throw new IllegalArgumentException("error: csp needs --bypass[=false]\nfix: csp --bypass, then reload the page");
        boolean bypass = !"false".equalsIgnoreCase(f.get("bypass"));
        BrowserProtocols.Served<String> served = host.protocols().serve("csp", f.get("via"), backend -> {
            switch (backend) {
                case BIDI -> {
                    JsonNode tree = host.bidiModules().getTree();
                    for (JsonNode context : tree.path("contexts")) host.bidiModules().setBypassCSP(context.path("context").asText(), bypass);
                }
                case CDP -> {
                    for (CdpSession.Target t : pages(host)) host.cdp().send(t.sessionId, "Page.setBypassCSP", Map.of("enabled", bypass));
                }
                default -> throw new UnsupportedProtocolOperation("csp", backend.id(), null);
            }
            return backend.id();
        });
        host.log("csp bypass=" + bypass + " via=" + served.value());
        return ProtoJson.obj().put("op", "csp").put("via", served.value()).put("bypass", bypass).put("note", "applies from the next navigation");
    }

    // ---- backends ----

    static void hook(ProtoHost host, State s, BrowserProtocols.Backend backend) {
        switch (backend) {
            case PLAYWRIGHT -> hookPlaywright(host, s);
            case BIDI -> hookBidi(host, s);
            case CDP -> hookCdp(host, s);
            default -> throw new UnsupportedProtocolOperation("net-route", backend.id(), null);
        }
    }

    private static Rule pick(State s, String url, String via) {
        for (Rule rule : s.rules) {
            if (rule.via.equals(via) && rule.matches(url) && rule.take()) {
                // An expired --times route is dropped from the list (its routed rows stay in the stream).
                if (rule.left.get() == 0) s.rules.remove(rule);
                return rule;
            }
        }
        return null;
    }

    private static void routed(ProtoHost host, String source, Rule rule, String url, String requestId) {
        ObjectNode row = host.hub().row(source, "net", "routed").put("url", url).put("method", rule.action).put("text", rule.id);
        if (requestId != null) row.put("requestId", requestId);
        host.hub().add(row);
    }

    private static void later(State s, Rule rule, Runnable work) {
        if (rule == null || rule.delayMs <= 0) work.run();
        else s.delays.schedule(work, rule.delayMs, TimeUnit.MILLISECONDS);
    }

    private static void hookPlaywright(ProtoHost host, State s) {
        if (s.pwHooked) return;
        s.pwHooked = true;
        Consumer<Route> handler = route -> {
            String url = route.request().url();
            Rule rule = pick(s, url, "playwright");
            if (rule == null) {
                route.fallback();
                return;
            }
            routed(host, "playwright", rule, url, "pw:" + System.identityHashCode(route.request()));
            if (rule.delayMs > 0) {
                try {
                    Thread.sleep(rule.delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            switch (rule.action) {
                case "fulfill" -> {
                    Route.FulfillOptions o = new Route.FulfillOptions().setStatus(rule.status).setBody(rule.body == null ? "" : rule.body);
                    if (!rule.headers.isEmpty()) o.setHeaders(rule.headers);
                    route.fulfill(o);
                }
                case "abort" -> route.abort();
                case "modify" -> route.resume(playwrightResume(route.request().headers(), rule));
                default -> route.resume();
            }
        };
        Predicate<String> any = url -> true;
        host.pw().call(c -> {
            for (BrowserContext context : c.browser().contexts()) context.route(any, handler);
            return null;
        });
        host.pw().listening(true);
        host.stateMap().put("route.pw.handler", handler);
    }

    /**
     * Playwright continue for {@code modify}. Headers stay merged. {@code rule.body} is post data
     * when it is not null, including an empty string. A null body stays header-only.
     */
    static Route.ResumeOptions playwrightResume(Map<String, String> requestHeaders, Rule rule) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (requestHeaders != null) merged.putAll(requestHeaders);
        if (rule.headers != null) merged.putAll(rule.headers);
        Route.ResumeOptions options = new Route.ResumeOptions().setHeaders(merged);
        if (rule.body != null) {
            options.setPostData(rule.body);
            logModifyBody(rule.body);
        }
        return options;
    }

    /**
     * CDP {@code Fetch.continueRequest} for {@code modify}. Header names merge without case.
     * {@code postData} is the plain request body, not the base64 response body {@code fulfill} sends.
     */
    static Map<String, Object> cdpContinue(String requestId, Map<String, String> requestHeaders, Rule rule) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (requestHeaders != null) merged.putAll(requestHeaders);
        if (rule.headers != null) {
            rule.headers.forEach((key, value) -> {
                merged.keySet().removeIf(name -> name.equalsIgnoreCase(key));
                merged.put(key, value);
            });
        }
        List<Map<String, String>> list = new ArrayList<>();
        merged.forEach((key, value) -> list.add(Map.of("name", key, "value", value)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("headers", list);
        if (rule.body != null) {
            payload.put("postData", rule.body);
            logModifyBody(rule.body);
        }
        return payload;
    }

    /**
     * BiDi {@code network.continueRequest} overrides for {@code modify}. The request id is added by
     * {@link BidiModules#continueRequest}. The body is {@code {type:string, value}} when present.
     */
    static Map<String, Object> bidiContinue(JsonNode requestHeaders, Map<String, String> extraHeaders, Rule rule) {
        Map<String, String> extra = extraHeaders == null ? Map.of() : extraHeaders;
        if (rule.headers != null && !rule.headers.isEmpty()) extra = merge(extra, rule.headers);
        JsonNode headers = requestHeaders != null && requestHeaders.isArray()
                ? requestHeaders
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headers", bidiHeaders(headers, extra));
        if (rule.body != null) {
            payload.put("body", Map.of("type", "string", "value", rule.body));
            logModifyBody(rule.body);
        }
        return payload;
    }

    /** Byte length only. The body itself is not written. */
    private static void logModifyBody(String body) {
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        BrowserLog.info("playwright", "modify body-bytes=" + bytes);
    }

    static List<Map<String, ?>> bidiHeaders(JsonNode headers, Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (JsonNode h : headers) merged.put(h.path("name").asText(), h.path("value").path("value").asText());
        extra.forEach((k, v) -> {
            merged.keySet().removeIf(name -> name.equalsIgnoreCase(k));
            merged.put(k, v);
        });
        List<Map<String, ?>> out = new ArrayList<>();
        merged.forEach((k, v) -> out.add(BidiHeaders.header(k, v)));
        return out;
    }

    /** BiDi header helpers. */
    static final class BidiHeaders {
        static Map<String, ?> header(String name, String value) {
            return Map.of("name", name, "value", Map.of("type", "string", "value", value));
        }
    }

    private static void hookBidi(ProtoHost host, State s) {
        if (s.bidiHooked) return;
        s.bidiHooked = true;
        RpcChannel bidi = host.bidi();
        RpcChannel.Listener listener = (method, params, ignored) -> {
            if (!params.path("isBlocked").asBoolean(false)) return;
            String request = params.path("request").path("request").asText();
            String url = params.path("request").path("url").asText();
            try {
                if ("network.authRequired".equals(method)) {
                    String[] cred = s.credential;
                    if (cred != null) host.bidiModules().continueWithAuth(request, cred[0], cred[1]);
                    else host.bidiModules().send("network.continueWithAuth", BidiModules.p("request", request, "action", "default"));
                    return;
                }
                if (!"network.beforeRequestSent".equals(method)) return;
                Rule rule = pick(s, url, "bidi");
                if (rule == null) {
                    if (s.extraHeaders.isEmpty()) host.bidiModules().continueRequest(request, null);
                    else host.bidiModules().continueRequest(request, Map.of("headers", bidiHeaders(params.path("request").path("headers"), s.extraHeaders)));
                    return;
                }
                routed(host, "bidi", rule, url, request);
                later(s, rule, () -> {
                    switch (rule.action) {
                        case "fulfill" -> {
                            List<Map<String, ?>> headers = new ArrayList<>();
                            rule.headers.forEach((k, v) -> headers.add(BidiHeaders.header(k, v)));
                            host.bidiModules().provideResponse(request, rule.status, headers, rule.body == null ? "" : rule.body);
                        }
                        case "abort" -> host.bidiModules().failRequest(request);
                        case "modify" -> host.bidiModules().continueRequest(request,
                                bidiContinue(params.path("request").path("headers"), s.extraHeaders, rule));
                        default -> host.bidiModules().continueRequest(request, null);
                    }
                });
            } catch (RuntimeException gone) {
                // The request finished or the page closed.
            }
        };
        bidi.listen(listener);
        host.bidiModules().subscribe(List.of("network.beforeRequestSent", "network.authRequired"), null);
        JsonNode intercept = host.bidiModules().addIntercept(List.of("beforeRequestSent", "authRequired"), null, null);
        s.bidiIntercept = intercept.path("intercept").asText(null);
        host.stateMap().put("route.bidi.listener", listener);
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> out = new LinkedHashMap<>(a);
        out.putAll(b);
        return out;
    }

    private static void enableFetch(ProtoHost host, State s, CdpSession.Target t) {
        if (!"page".equals(t.type) || !s.cdpFetch.add(t.sessionId)) return;
        host.cdp().send(t.sessionId, "Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request")),
                "handleAuthRequests", true));
    }

    private static void hookCdp(ProtoHost host, State s) {
        if (s.cdpHooked) {
            for (CdpSession.Target t : pages(host)) enableFetch(host, s, t);
            return;
        }
        s.cdpHooked = true;
        CdpSession cdp = host.cdp();
        RpcChannel.Listener listener = (method, params, sessionId) -> {
            if (!method.startsWith("Fetch.") || sessionId == null || !s.cdpFetch.contains(sessionId)) return;
            String id = params.path("requestId").asText();
            try {
                if ("Fetch.authRequired".equals(method)) {
                    String[] cred = s.credential;
                    Map<String, Object> response = new LinkedHashMap<>();
                    if (cred != null) {
                        response.put("response", "ProvideCredentials");
                        response.put("username", cred[0]);
                        response.put("password", cred[1]);
                    } else response.put("response", "Default");
                    cdp.send(sessionId, "Fetch.continueWithAuth", Map.of("requestId", id, "authChallengeResponse", response));
                    return;
                }
                if (!"Fetch.requestPaused".equals(method)) return;
                String url = params.path("request").path("url").asText();
                Rule rule = pick(s, url, "cdp");
                if (rule == null) {
                    cdp.send(sessionId, "Fetch.continueRequest", Map.of("requestId", id));
                    return;
                }
                routed(host, "cdp", rule, url, params.path("networkId").asText(id));
                later(s, rule, () -> {
                    try {
                        switch (rule.action) {
                            case "fulfill" -> {
                                List<Map<String, String>> headers = new ArrayList<>();
                                rule.headers.forEach((k, v) -> headers.add(Map.of("name", k, "value", v)));
                                if (rule.headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
                                    headers.add(Map.of("name", "Content-Type", "value", guessType(rule.body)));
                                }
                                cdp.send(sessionId, "Fetch.fulfillRequest", Map.of("requestId", id, "responseCode", rule.status,
                                        "responseHeaders", headers, "body",
                                        Base64.getEncoder().encodeToString((rule.body == null ? "" : rule.body).getBytes(StandardCharsets.UTF_8))));
                            }
                            case "abort" -> cdp.send(sessionId, "Fetch.failRequest", Map.of("requestId", id, "errorReason", "BlockedByClient"));
                            case "modify" -> {
                                Map<String, String> requestHeaders = new LinkedHashMap<>();
                                params.path("request").path("headers").fields().forEachRemaining(e -> requestHeaders.put(e.getKey(), e.getValue().asText()));
                                cdp.send(sessionId, "Fetch.continueRequest", cdpContinue(id, requestHeaders, rule));
                            }
                            default -> cdp.send(sessionId, "Fetch.continueRequest", Map.of("requestId", id));
                        }
                    } catch (RuntimeException gone) {
                        // The request finished or the page closed.
                    }
                });
            } catch (RuntimeException gone) {
                // Same.
            }
        };
        cdp.channel().listen(listener);
        host.stateMap().put("route.cdp.listener", listener);
        cdp.onTarget(t -> enableFetch(host, s, t));
    }

    /**
     * For a request one backend cannot serve this time (not ever): with --via unset, the first other backend in order,
     * so the fallback does not mark the skipped backend unavailable for the whole capability.
     */
    static String skip(ProtoHost host, String capability, String via, BrowserProtocols.Backend... skipped) {
        if (via != null && !via.isBlank() && !"auto".equalsIgnoreCase(via)) return via;
        for (BrowserProtocols.Backend backend : host.protocols().order(capability, null)) {
            if (!List.of(skipped).contains(backend)) return backend.id();
        }
        return via;
    }

    /** Attached page targets, waiting briefly for the first one on a host that just connected. */
    static List<CdpSession.Target> pages(ProtoHost host) {
        List<CdpSession.Target> pages = host.cdp().pages();
        if (!pages.isEmpty()) return pages;
        host.cdp().page(null);
        return host.cdp().pages();
    }

    private static String guessType(String body) {
        String b = body == null ? "" : body.trim();
        if (b.startsWith("{") || b.startsWith("[")) return "application/json";
        if (b.startsWith("<")) return "text/html";
        return "text/plain";
    }

    /** Leaves interception on while any route or credential needs it; otherwise turns it off. */
    private static void unhook(ProtoHost host, State s) {
        if (s.cdpHooked) {
            for (String session : new ArrayList<>(s.cdpFetch)) {
                try {
                    host.cdp().send(session, "Fetch.disable", Map.of());
                } catch (RuntimeException ignored) {
                    // Gone.
                }
            }
            s.cdpFetch.clear();
            Object listener = host.stateMap().remove("route.cdp.listener");
            if (listener instanceof RpcChannel.Listener l) host.cdp().channel().unlisten(l);
            s.cdpHooked = false;
        }
        if (s.bidiHooked) {
            try {
                if (s.bidiIntercept != null) host.bidiModules().removeIntercept(s.bidiIntercept);
            } catch (RuntimeException ignored) {
                // Gone.
            }
            Object listener = host.stateMap().remove("route.bidi.listener");
            if (listener instanceof RpcChannel.Listener l) host.bidi().unlisten(l);
            s.bidiIntercept = null;
            s.bidiHooked = false;
        }
        if (s.pwHooked) {
            Object handler = host.stateMap().remove("route.pw.handler");
            try {
                host.pw().call(c -> {
                    for (BrowserContext context : c.browser().contexts()) context.unrouteAll();
                    return null;
                });
            } catch (RuntimeException ignored) {
                // Gone.
            }
            if (handler != null) host.pw().listening(false);
            s.pwHooked = false;
        }
    }

    static void clearAll(ProtoHost host, State s) {
        s.rules.clear();
        s.credential = null;
        boolean hadHeaders = !s.extraHeaders.isEmpty();
        s.extraHeaders.clear();
        unhook(host, s);
        if (hadHeaders && host.hasCdp()) {
            try {
                for (CdpSession.Target t : pages(host)) host.cdp().send(t.sessionId, "Network.setExtraHTTPHeaders", Map.of("headers", Map.of()));
            } catch (RuntimeException ignored) {
                // Gone.
            }
        }
        s.delays.shutdownNow();
    }

    /** For doctor and status: the routes as one line each. */
    static List<String> describe(ProtoHost host) {
        List<String> out = new ArrayList<>();
        for (Rule rule : state(host).rules) out.add(rule.id + " " + rule.action + " " + rule.match + " via=" + rule.via + " hits=" + rule.hits.get());
        return out;
    }
}
