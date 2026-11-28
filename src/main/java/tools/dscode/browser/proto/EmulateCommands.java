package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.ForcedColors;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.ReducedMotion;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E6: {@code emulate} across backends (per option, in fallback order; an option no backend can apply is reported as
 * unsupported, never dropped), {@code clock} (Playwright clock, CDP virtual time) and {@code permissions}.
 */
final class EmulateCommands {
    static final List<String> OPTIONS = List.of("locale", "timezone", "geo", "ua", "media", "forced-colors", "reduced-motion", "touch",
            "orientation", "cpu", "vision", "no-js", "viewport");
    static final List<String> VISION = List.of("none", "deuteranopia", "protanopia", "tritanopia", "achromatopsia", "blurredVision");

    private EmulateCommands() {
    }

    static void register() {
        ProtoHost.register("emulate", EmulateCommands::emulate);
        ProtoHost.register("clock", EmulateCommands::clock);
        ProtoHost.register("permissions", EmulateCommands::permissions);
    }

    /** Active overrides: option to {value, via}. */
    static Map<String, String[]> active(ProtoHost host) {
        return host.state("emulate.active", () -> {
            Map<String, String[]> m = new ConcurrentHashMap<>();
            host.onStop(() -> {
                try {
                    reset(host, new LinkedHashMap<>(m));
                } catch (RuntimeException ignored) {
                    // The browser is gone.
                }
            });
            return m;
        });
    }

    static ObjectNode emulate(ProtoHost host, Map<String, String> f) {
        if (f.containsKey("network") && OPTIONS.stream().noneMatch(f::containsKey) && !f.containsKey("reset") && !f.containsKey("show")) {
            return RouteCommands.conditions(host, f);
        }
        Map<String, String[]> active = active(host);
        if (f.containsKey("show")) return show(host);
        if (f.containsKey("reset")) {
            Map<String, String[]> was = new LinkedHashMap<>(active);
            ObjectNode out = reset(host, was);
            active.clear();
            if (host.stateMap().containsKey("emulate.network") && !"online".equals(host.stateMap().get("emulate.network"))) {
                RouteCommands.conditions(host, Map.of("network", "online"));
                out.withArray("cleared").add("network");
            }
            return out;
        }
        ObjectNode out = ProtoJson.obj().put("op", "emulate");
        ObjectNode applied = out.putObject("applied");
        ObjectNode unsupported = out.putObject("unsupported");
        List<Backend> order = host.protocols().order("emulate", f.get("via"));
        if (order.isEmpty()) throw host.protocols().unsupported("emulate", f.get("via"));
        String lastVia = "";
        if (f.containsKey("ua-ch")) {
            try {
                String raw = f.get("ua-ch");
                if (raw.startsWith("@")) raw = java.nio.file.Files.readString(host.project().resolve(raw.substring(1)).normalize());
                host.stateMap().put("emulate.ua-ch", ProtoJson.MAPPER.readValue(raw, Map.class));
            } catch (java.io.IOException bad) {
                throw new IllegalArgumentException("error: --ua-ch is JSON (CDP UserAgentMetadata)\nfix: --ua-ch='{\"platform\":\"Linux\",\"platformVersion\":\"\",\"architecture\":\"x86\",\"model\":\"\",\"mobile\":false}'");
            }
        }
        for (String option : OPTIONS) {
            if (!f.containsKey(option)) continue;
            String value = f.get(option);
            validate(option, value);
            List<String> reasons = new ArrayList<>();
            boolean done = false;
            for (Backend backend : order) {
                try {
                    apply(host, backend, option, value);
                    applied.put(option, backend.id());
                    active.put(option, new String[]{value, backend.id()});
                    lastVia = backend.id();
                    done = true;
                    break;
                } catch (UnsupportedProtocolOperation | IllegalStateException no) {
                    reasons.add(backend.id() + ": " + ProtoHost.firstLine(no.getMessage()).replaceFirst("^error: ", ""));
                }
            }
            if (!done) unsupported.put(option, String.join("; ", reasons));
        }
        if (f.containsKey("network")) {
            ObjectNode net = RouteCommands.conditions(host, f);
            applied.put("network", net.path("via").asText());
        }
        if (applied.isEmpty() && unsupported.isEmpty()) {
            throw new IllegalArgumentException("error: emulate needs an option\nfix: emulate --locale=de-DE --timezone=Europe/Berlin (or --show, --reset)");
        }
        if (applied.isEmpty()) {
            throw new IllegalStateException("error: unsupported: " + String.join(", ", iterable(unsupported)) + " on " + order.stream().map(Backend::id).toList()
                    + "\nfix: " + unsupported.toString());
        }
        host.log("emulate " + applied + (unsupported.isEmpty() ? "" : " unsupported=" + iterable(unsupported)));
        return out.put("via", lastVia);
    }

    private static List<String> iterable(ObjectNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    static void validate(String option, String value) {
        String v = value == null ? "" : value;
        switch (option) {
            case "geo" -> {
                String[] p = v.split(",");
                if (p.length < 2) throw new IllegalArgumentException("error: --geo is lat,lon[,accuracy]\nfix: emulate --geo=52.52,13.40");
            }
            case "forced-colors" -> {
                if (!List.of("active", "none").contains(v)) throw new IllegalArgumentException("error: --forced-colors is active or none\nfix: emulate --forced-colors=active");
            }
            case "orientation" -> {
                if (!List.of("portrait", "landscape").contains(v)) throw new IllegalArgumentException("error: --orientation is portrait or landscape\nfix: emulate --orientation=landscape");
            }
            case "vision" -> {
                if (!VISION.contains(v)) throw new IllegalArgumentException("error: --vision is one of " + String.join("|", VISION) + "\nfix: emulate --vision=deuteranopia");
            }
            case "cpu" -> {
                if (!v.matches("\\d+(\\.\\d+)?x?")) throw new IllegalArgumentException("error: --cpu is a slowdown rate like 4x\nfix: emulate --cpu=4x");
            }
            case "viewport" -> {
                if (!v.matches("\\d+x\\d+(@\\d+(\\.\\d+)?)?")) throw new IllegalArgumentException("error: --viewport is WxH[@dpr]\nfix: emulate --viewport=390x844@3");
            }
            default -> {
            }
        }
    }

    private static boolean off(String value) {
        return "false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value);
    }

    /** media features from --media=name:value,..., --forced-colors and --reduced-motion together. */
    static Map<String, String> features(ProtoHost host, String option, String value) {
        Map<String, String> media = host.state("emulate.media", ConcurrentHashMap::new);
        switch (option) {
            case "media" -> {
                for (String part : value.split(",")) {
                    int colon = part.indexOf(':');
                    if (colon < 0) throw new IllegalArgumentException("error: --media is feature:value,...\nfix: emulate --media=prefers-color-scheme:dark");
                    media.put(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
                }
            }
            case "forced-colors" -> media.put("forced-colors", value);
            case "reduced-motion" -> media.put("prefers-reduced-motion", off(value) ? "no-preference" : "reduce");
            default -> {
            }
        }
        return media;
    }

    static void apply(ProtoHost host, Backend backend, String option, String value) {
        switch (backend) {
            case PLAYWRIGHT -> playwright(host, option, value);
            case BIDI -> bidi(host, option, value);
            case CDP -> cdp(host, option, value);
            default -> throw new UnsupportedProtocolOperation("emulate --" + option, backend.id(), null);
        }
    }

    private static void playwright(ProtoHost host, String option, String value) {
        switch (option) {
            case "media", "forced-colors", "reduced-motion" -> {
                Map<String, String> media = features(host, option, value);
                for (String name : media.keySet()) {
                    if (!List.of("prefers-color-scheme", "forced-colors", "prefers-reduced-motion").contains(name)) {
                        throw new UnsupportedProtocolOperation("emulate --media=" + name, "playwright", null);
                    }
                }
                host.pw().call(c -> {
                    for (Page page : c.pages()) {
                        Page.EmulateMediaOptions o = new Page.EmulateMediaOptions();
                        if (media.containsKey("prefers-color-scheme")) o.setColorScheme(ColorScheme.valueOf(media.get("prefers-color-scheme").toUpperCase(Locale.ROOT).replace('-', '_')));
                        if (media.containsKey("forced-colors")) o.setForcedColors(ForcedColors.valueOf(media.get("forced-colors").toUpperCase(Locale.ROOT)));
                        if (media.containsKey("prefers-reduced-motion")) o.setReducedMotion(ReducedMotion.valueOf(media.get("prefers-reduced-motion").toUpperCase(Locale.ROOT).replace('-', '_')));
                        page.emulateMedia(o);
                    }
                    return null;
                });
            }
            case "geo" -> {
                String[] p = value.split(",");
                host.pw().call(c -> {
                    for (BrowserContext context : c.browser().contexts()) {
                        context.setGeolocation(new Geolocation(Double.parseDouble(p[0]), Double.parseDouble(p[1]))
                                .setAccuracy(p.length > 2 ? Double.parseDouble(p[2]) : 1));
                    }
                    return null;
                });
            }
            case "viewport" -> {
                String[] wh = value.split("@")[0].split("x");
                if (value.contains("@")) throw new UnsupportedProtocolOperation("emulate --viewport @dpr", "playwright", null);
                host.pw().call(c -> {
                    for (Page page : c.pages()) page.setViewportSize(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
                    return null;
                });
            }
            // locale, timezone, ua, touch, orientation, cpu, vision, no-js: Playwright sets these only on a new context.
            default -> throw new UnsupportedProtocolOperation("emulate --" + option, "playwright", "set only when a context is created");
        }
    }

    private static List<String> contexts(ProtoHost host) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : host.bidiModules().getTree().path("contexts")) out.add(c.path("context").asText());
        return out;
    }

    private static void bidi(ProtoHost host, String option, String value) {
        BidiModules b = host.bidiModules();
        List<String> ctx = contexts(host);
        boolean reset = value == null;
        switch (option) {
            case "locale" -> b.send("emulation.setLocaleOverride", BidiModules.pn("locale", reset ? null : value, "contexts", ctx));
            case "timezone" -> b.send("emulation.setTimezoneOverride", BidiModules.pn("timezone", reset ? null : value, "contexts", ctx));
            case "geo" -> {
                if (reset) b.send("emulation.setGeolocationOverride", BidiModules.pn("coordinates", null, "contexts", ctx));
                else {
                    String[] p = value.split(",");
                    b.setGeolocationOverride(ctx, Double.parseDouble(p[0]), Double.parseDouble(p[1]), p.length > 2 ? Double.parseDouble(p[2]) : 1.0);
                }
            }
            case "ua" -> b.send("emulation.setUserAgentOverride", BidiModules.pn("userAgent", reset ? null : value, "contexts", ctx));
            case "forced-colors" -> {
                // BiDi forces colors with a theme; "active" uses the dark theme.
                b.send("emulation.setForcedColorsModeThemeOverride", BidiModules.pn("theme", reset || "none".equals(value) ? null : "dark", "contexts", ctx));
            }
            case "no-js" -> b.send("emulation.setScriptingEnabled", BidiModules.pn("enabled", reset || off(value) ? null : Boolean.FALSE, "contexts", ctx));
            case "touch" -> b.send("emulation.setTouchOverride", BidiModules.pn("maxTouchPoints", reset || off(value) ? null : 5, "contexts", ctx));
            case "orientation" -> b.send("emulation.setScreenOrientationOverride", BidiModules.pn("screenOrientation", reset ? null
                    : BidiModules.p("natural", "portrait", "type", "portrait".equals(value) ? "portrait-primary" : "landscape-primary"), "contexts", ctx));
            case "viewport" -> {
                for (String context : ctx) {
                    if (reset) b.send("browsingContext.setViewport", BidiModules.pn("viewport", null, "context", context, "devicePixelRatio", null));
                    else {
                        String[] parts = value.split("@");
                        String[] wh = parts[0].split("x");
                        b.setViewport(context, Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), parts.length > 1 ? Double.parseDouble(parts[1]) : null);
                    }
                }
            }
            // media queries other than forced colors, reduced motion, CPU and vision have no BiDi command.
            default -> throw new UnsupportedProtocolOperation("emulate --" + option, "bidi", "no BiDi command");
        }
    }

    private static void cdpAll(ProtoHost host, String method, Map<String, ?> params) {
        for (CdpSession.Target t : RouteCommands.pages(host)) host.cdp().send(t.sessionId, method, params);
        String key = "emulate.cdp.hook." + method;
        Map<String, Object> latest = host.state("emulate.cdp.latest", ConcurrentHashMap::new);
        latest.put(method, params);
        if (host.stateMap().putIfAbsent(key, true) == null) {
            host.cdp().onTarget(t -> {
                if (!"page".equals(t.type)) return;
                Object p = latest.get(method);
                if (p instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, ?> typed = (Map<String, ?>) m;
                    host.cdp().send(t.sessionId, method, typed);
                }
            });
        }
    }

    private static void cdp(ProtoHost host, String option, String value) {
        boolean reset = value == null;
        switch (option) {
            case "locale" -> {
                cdpAll(host, "Emulation.setLocaleOverride", reset ? Map.of() : Map.of("locale", value));
                // navigator.language and Accept-Language follow the UA override's acceptLanguage (as Playwright does).
                host.stateMap().put("emulate.cdp.locale", reset ? "" : value);
                userAgent(host, null);
            }
            case "timezone" -> cdpAll(host, "Emulation.setTimezoneOverride", Map.of("timezoneId", reset ? "" : value));
            case "geo" -> {
                if (reset) cdpAll(host, "Emulation.clearGeolocationOverride", Map.of());
                else {
                    String[] p = value.split(",");
                    cdpAll(host, "Emulation.setGeolocationOverride", Map.of("latitude", Double.parseDouble(p[0]), "longitude", Double.parseDouble(p[1]),
                            "accuracy", p.length > 2 ? Double.parseDouble(p[2]) : 1.0));
                }
            }
            case "ua" -> {
                host.stateMap().put("emulate.cdp.ua", reset ? "" : value);
                if (reset) host.stateMap().remove("emulate.ua-ch");
                userAgent(host, null);
            }
            case "media", "forced-colors", "reduced-motion" -> {
                List<Map<String, String>> list = new ArrayList<>();
                if (!reset) features(host, option, value).forEach((k, v) -> list.add(Map.of("name", k, "value", v)));
                else host.state("emulate.media", ConcurrentHashMap::new).clear();
                cdpAll(host, "Emulation.setEmulatedMedia", Map.of("features", list));
            }
            case "touch" -> cdpAll(host, "Emulation.setTouchEmulationEnabled", reset || off(value) ? Map.of("enabled", false)
                    : Map.of("enabled", true, "maxTouchPoints", 5));
            case "cpu" -> cdpAll(host, "Emulation.setCPUThrottlingRate", Map.of("rate", reset ? 1.0 : Double.parseDouble(value.replace("x", ""))));
            case "vision" -> cdpAll(host, "Emulation.setEmulatedVisionDeficiency", Map.of("type", reset ? "none" : value));
            case "no-js" -> cdpAll(host, "Emulation.setScriptExecutionDisabled", Map.of("value", !reset && !off(value)));
            case "viewport", "orientation" -> {
                Map<String, String[]> active = active(host);
                String viewport = "viewport".equals(option) ? value : active.containsKey("viewport") ? active.get("viewport")[0] : null;
                String orientation = "orientation".equals(option) ? value : active.containsKey("orientation") ? active.get("orientation")[0] : null;
                if (viewport == null && orientation == null) {
                    cdpAll(host, "Emulation.clearDeviceMetricsOverride", Map.of());
                    return;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                int w = 0;
                int h = 0;
                double dpr = 0;
                if (viewport != null) {
                    String[] parts = viewport.split("@");
                    String[] wh = parts[0].split("x");
                    w = Integer.parseInt(wh[0]);
                    h = Integer.parseInt(wh[1]);
                    dpr = parts.length > 1 ? Double.parseDouble(parts[1]) : 0;
                }
                p.put("width", w);
                p.put("height", h);
                p.put("deviceScaleFactor", dpr);
                p.put("mobile", false);
                if (orientation != null) p.put("screenOrientation", Map.of("type", "portrait".equals(orientation) ? "portraitPrimary" : "landscapePrimary",
                        "angle", "portrait".equals(orientation) ? 0 : 90));
                cdpAll(host, "Emulation.setDeviceMetricsOverride", p);
            }
            default -> throw new UnsupportedProtocolOperation("emulate --" + option, "cdp", null);
        }
    }

    /** One UA override carrying the --ua value (or the browser's own) and the --locale as acceptLanguage. */
    private static void userAgent(ProtoHost host, String ignored) {
        String ua = String.valueOf(host.stateMap().getOrDefault("emulate.cdp.ua", ""));
        String locale = String.valueOf(host.stateMap().getOrDefault("emulate.cdp.locale", ""));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userAgent", ua.isEmpty() ? original(host) : ua);
        if (!locale.isEmpty()) p.put("acceptLanguage", locale);
        Object ch = host.stateMap().get("emulate.ua-ch");
        if (!ua.isEmpty() && ch != null) p.put("userAgentMetadata", ch);
        cdpAll(host, "Emulation.setUserAgentOverride", p);
    }

    private static String original(ProtoHost host) {
        return host.cdp().browser("Browser.getVersion", Map.of()).path("userAgent").asText("");
    }

    static ObjectNode show(ProtoHost host) {
        ObjectNode out = ProtoJson.obj().put("op", "emulate");
        ObjectNode active = out.putObject("active");
        active(host).forEach((k, v) -> active.putObject(k).put("value", v[0]).put("via", v[1]));
        Object network = host.stateMap().get("emulate.network");
        if (network != null && !"online".equals(String.valueOf(network))) active.putObject("network").put("value", String.valueOf(network));
        Object clock = host.stateMap().get("clock.state");
        if (clock != null) active.putObject("clock").put("value", String.valueOf(clock));
        return out;
    }

    static ObjectNode reset(ProtoHost host, Map<String, String[]> was) {
        ObjectNode out = ProtoJson.obj().put("op", "emulate").put("reset", true);
        ArrayNode cleared = out.putArray("cleared");
        for (Map.Entry<String, String[]> e : was.entrySet()) {
            String option = e.getKey();
            Backend backend = Backend.of(e.getValue()[1]);
            try {
                if (backend == Backend.PLAYWRIGHT) resetPlaywright(host, option);
                else apply(host, backend, option, null);
                cleared.add(option);
            } catch (RuntimeException failed) {
                out.with("failed").put(option, ProtoHost.firstLine(failed.getMessage()));
            }
        }
        return out;
    }

    private static void resetPlaywright(ProtoHost host, String option) {
        host.pw().call(c -> {
            switch (option) {
                case "media", "forced-colors", "reduced-motion" -> {
                    host.state("emulate.media", ConcurrentHashMap::new).clear();
                    for (Page page : c.pages()) page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(null).setForcedColors(null).setReducedMotion(null));
                }
                case "geo" -> {
                    for (BrowserContext context : c.browser().contexts()) context.setGeolocation(null);
                }
                default -> {
                }
            }
            return null;
        });
    }

    // ---- clock ----

    static Duration duration(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.matches("\\d+")) return Duration.ofMillis(Long.parseLong(v));
        if (v.matches("\\d+ms")) return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
        if (v.matches("\\d+s")) return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
        if (v.matches("\\d+m")) return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
        if (v.matches("\\d+h")) return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
        if (v.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
            String[] p = v.split(":");
            long s = p.length == 3 ? Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2]) : Long.parseLong(p[0]) * 60 + Long.parseLong(p[1]);
            return Duration.ofSeconds(s);
        }
        throw new IllegalArgumentException("error: bad duration " + raw + "\nfix: use 500ms, 30s, 5m, 1h or mm:ss");
    }

    static long instant(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException notIso) {
            try {
                return Long.parseLong(iso);
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("error: bad time " + iso + "\nfix: use an ISO-8601 instant like 2026-01-01T09:00:00Z or epoch ms");
            }
        }
    }

    static ObjectNode clock(ProtoHost host, Map<String, String> f) {
        List<String> actions = List.of("install", "pause-at", "fast-forward", "run-for", "set-fixed", "resume");
        String action = actions.stream().filter(f::containsKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("error: clock needs one of --install[=<iso>] --pause-at --fast-forward --run-for --set-fixed --resume\nfix: clock --install=2026-01-01T09:00:00Z"));
        String value = f.get(action);
        // Playwright's clock is preferred whenever Playwright can reach the browser; CDP virtual time is the fallback.
        String via = f.get("via");
        if (via == null && !host.protocols().order("clock", "playwright").isEmpty()) via = "playwright";
        BrowserProtocols.Served<String> served = host.protocols().serve("clock", via, backend -> {
            switch (backend) {
                case PLAYWRIGHT -> host.pw().call(c -> {
                    for (Page page : c.pages()) {
                        Clock clock = page.clock();
                        switch (action) {
                            case "install" -> {
                                Clock.InstallOptions o = new Clock.InstallOptions();
                                if (value != null && !"true".equals(value)) o.setTime(instant(value));
                                clock.install(o);
                            }
                            case "pause-at" -> clock.pauseAt(instant(value));
                            case "fast-forward" -> clock.fastForward(duration(value).toMillis());
                            case "run-for" -> clock.runFor(duration(value).toMillis());
                            case "set-fixed" -> clock.setFixedTime(instant(value));
                            default -> clock.resume();
                        }
                        // One page's clock is enough for a context; Playwright installs it context-wide.
                        break;
                    }
                    return null;
                });
                case CDP -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    switch (action) {
                        case "pause-at" -> {
                            p.put("policy", "pause");
                            p.put("initialVirtualTime", instant(value) / 1000.0);
                        }
                        case "run-for", "fast-forward" -> {
                            p.put("policy", "advance");
                            p.put("budget", (double) duration(value).toMillis());
                        }
                        case "resume" -> p.put("policy", "advance");
                        default -> throw new IllegalStateException("error: unsupported: clock --" + action + " on cdp (virtual time pauses and advances only)\nfix: use --via=playwright");
                    }
                    for (CdpSession.Target t : RouteCommands.pages(host)) host.cdp().send(t.sessionId, "Emulation.setVirtualTimePolicy", p);
                }
                default -> throw new UnsupportedProtocolOperation("clock", backend.id(), null);
            }
            return backend.id();
        });
        host.stateMap().put("clock.state", action + (value == null || "true".equals(value) ? "" : "=" + value) + " via=" + served.value());
        host.log("clock " + action + " via=" + served.value());
        return ProtoJson.obj().put("op", "clock").put("via", served.value()).put("action", action);
    }

    // ---- permissions ----

    static ObjectNode permissions(ProtoHost host, Map<String, String> f) {
        String mode = f.containsKey("grant") ? "grant" : f.containsKey("deny") ? "deny" : f.containsKey("reset") ? "reset" : null;
        if (mode == null) throw new IllegalArgumentException("error: permissions needs --grant=<list>, --deny=<list> or --reset\nfix: permissions --grant=geolocation --origin=https://example.com");
        List<String> names = "reset".equals(mode) ? List.of() : EventCommands.list(f.get(mode));
        if (!"reset".equals(mode) && names.isEmpty()) throw new IllegalArgumentException("error: --" + mode + " needs permission names\nfix: permissions --" + mode + "=geolocation,notifications");
        String origin = f.get("origin");
        String via = "deny".equals(mode) ? RouteCommands.skip(host, "permissions", f.get("via"), Backend.PLAYWRIGHT) : f.get("via");
        if (origin == null && (via == null || "auto".equals(via))) via = RouteCommands.skip(host, "permissions", via, Backend.BIDI);
        BrowserProtocols.Served<String> served = host.protocols().serve("permissions", via, backend -> {
            switch (backend) {
                case PLAYWRIGHT -> {
                    if ("deny".equals(mode)) throw new IllegalStateException("error: unsupported: permissions --deny on playwright (grant and clear only)\nfix: use --via=bidi or --via=cdp");
                    host.pw().call(c -> {
                        for (BrowserContext context : c.browser().contexts()) {
                            if ("reset".equals(mode)) context.clearPermissions();
                            else context.grantPermissions(names, origin == null ? null : new BrowserContext.GrantPermissionsOptions().setOrigin(origin));
                        }
                        return null;
                    });
                }
                case BIDI -> {
                    if (origin == null) throw new IllegalStateException("error: permissions over bidi needs --origin\nfix: permissions --grant=geolocation --origin=https://example.com");
                    List<String> list = "reset".equals(mode) ? List.copyOf(host.state("permissions.names", java.util.concurrent.CopyOnWriteArrayList<String>::new)) : names;
                    for (String name : list) host.bidiModules().setPermission(name, "grant".equals(mode) ? "granted" : "deny".equals(mode) ? "denied" : "prompt", origin);
                }
                case CDP -> {
                    if ("reset".equals(mode)) host.cdp().browser("Browser.resetPermissions", Map.of());
                    else {
                        for (String name : names) {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("permission", Map.of("name", name));
                            p.put("setting", "grant".equals(mode) ? "granted" : "denied");
                            if (origin != null) p.put("origin", origin);
                            host.cdp().browser("Browser.setPermission", p);
                        }
                    }
                }
                default -> throw new UnsupportedProtocolOperation("permissions", backend.id(), null);
            }
            return backend.id();
        });
        if (!"reset".equals(mode)) host.state("permissions.names", java.util.concurrent.CopyOnWriteArrayList<String>::new).addAll(names);
        if (host.stateMap().putIfAbsent("permissions.cleanup", true) == null) {
            host.onStop(() -> {
                try {
                    permissions(host, Map.of("reset", "true", "via", served.value(), "origin", origin == null ? "" : origin));
                } catch (RuntimeException ignored) {
                    // The browser is gone.
                }
            });
        }
        host.log("permissions " + mode + " " + names + " via=" + served.value());
        ObjectNode out = ProtoJson.obj().put("op", "permissions").put("via", served.value()).put("mode", mode);
        ArrayNode list = out.putArray("names");
        names.forEach(list::add);
        return out;
    }
}
