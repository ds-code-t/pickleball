package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.control.protocol.SecretRedactor;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * D13: attach to a browser that is already running. One {@code attach} entry point; the existing
 * {@code attach --target=browser:cdp=...} join is unchanged and is what every CDP route ends in, so the attached
 * browser is a normal live target for snap, find, act, panel, and the collab verbs.
 *
 * <p>Routes, in order: (1) channel ({@code --cdp-endpoint=chrome|chrome-beta|...|msedge-canary}: the browser's
 * {@code DevToolsActivePort} after "Allow remote debugging for this browser instance" at
 * chrome://inspect/#remote-debugging); (2) the official Playwright Extension ({@code --extension[=chrome|msedge]},
 * run with the Node inside Playwright's driver-bundle; token from {@code PLAYWRIGHT_MCP_EXTENSION_TOKEN}, never
 * printed); (3) explicit endpoints ({@code --cdp-endpoint=<http/ws url>}, {@code --endpoint=<ws>} for a Playwright
 * server, {@code --selenium=<debuggerAddress>}), and {@code --discover} / {@code --candidate=<n>}; (4)
 * {@code relaunch-with-debug --browser=chrome|msedge --confirm}.
 *
 * <p>Safety: discovery reads only {@code DevToolsActivePort} in the current user's own browser data dirs and
 * probes 127.0.0.1; a non-local host is used only when given explicitly. Nothing is injected into another process.
 * Every attach is logged to {@code .pickleball/logs/attach.log} with endpoints redacted.
 */
final class BrowserAttach {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN_ENV = "PLAYWRIGHT_MCP_EXTENSION_TOKEN";
    static final String EXTENSION_ID = "mmlmfjhmonkocbjadbfplnigmagldckm";
    static final List<String> CHANNELS = List.of("chrome", "chrome-beta", "chrome-dev", "chrome-canary",
            "msedge", "msedge-beta", "msedge-dev", "msedge-canary");
    static final List<Integer> PORTS = List.of(9222, 9223, 9224, 9229, 9333);
    static final String CHANNEL_FIX = "open chrome://inspect/#remote-debugging in that browser and enable \"Allow remote debugging for this browser instance\"";
    static final Set<String> ROUTE_FLAGS = Set.of("--cdp-endpoint", "--endpoint", "--discover", "--candidate", "--selenium",
            "--extension", "--profile-dir-name");

    /** Everything that touches the machine, so tests can simulate it. */
    interface Probes {
        String os();

        Path home();

        String env(String name);

        boolean exists(Path path);

        Optional<String> read(Path path);

        boolean portOpen(String host, int port);

        /** webSocketDebuggerUrl from {@code <base>/json/version}, or null. */
        String jsonVersion(String httpBase);
    }

    static final Probes LIVE = new Probes() {
        @Override
        public String os() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return name.contains("win") ? "win32" : name.contains("mac") ? "darwin" : "linux";
        }

        @Override
        public Path home() {
            return Path.of(System.getProperty("user.home"));
        }

        @Override
        public String env(String name) {
            return System.getenv(name);
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path);
        }

        @Override
        public Optional<String> read(Path path) {
            try {
                return Files.isRegularFile(path) ? Optional.of(Files.readString(path)) : Optional.empty();
            } catch (IOException failure) {
                return Optional.empty();
            }
        }

        @Override
        public boolean portOpen(String host, int port) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 250);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }

        @Override
        public String jsonVersion(String httpBase) {
            try {
                String base = httpBase.endsWith("/") ? httpBase : httpBase + "/";
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "json/version"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return null;
                String url = JSON.readTree(response.body()).path("webSocketDebuggerUrl").asText("");
                return url.startsWith("ws://") || url.startsWith("wss://") ? url : null;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        }
    };

    static volatile Probes probes = LIVE;

    private BrowserAttach() {
    }

    static boolean handles(String[] args) {
        if (args == null) return false;
        for (String token : args) if (token != null && ROUTE_FLAGS.contains(token.split("=", 2)[0])) return true;
        return false;
    }

    /** The user data dir of a channel for this OS, as Playwright resolves it; null when unknown. */
    static Path userDataDir(String channel, Probes p) {
        String os = p.os();
        Path home = p.home();
        String local = p.env("LOCALAPPDATA");
        Path appData = local == null || local.isBlank() ? home.resolve("AppData").resolve("Local") : Path.of(local);
        String configHome = firstNonBlank(p.env("CHROME_CONFIG_HOME"), p.env("XDG_CONFIG_HOME"));
        Path linuxBase = configHome == null ? home.resolve(".config") : Path.of(configHome);
        String override = p.env("CHROME_USER_DATA_DIR");
        Path mac = home.resolve("Library").resolve("Application Support");
        record Dirs(String linux, String[] mac, String[] win) {
        }
        Dirs dirs = switch (channel) {
            case "chrome" -> new Dirs("google-chrome", new String[]{"Google", "Chrome"}, new String[]{"Google", "Chrome", "User Data"});
            case "chrome-beta" -> new Dirs("google-chrome-beta", new String[]{"Google", "Chrome Beta"}, new String[]{"Google", "Chrome Beta", "User Data"});
            case "chrome-dev" -> new Dirs("google-chrome-unstable", new String[]{"Google", "Chrome Dev"}, new String[]{"Google", "Chrome Dev", "User Data"});
            case "chrome-canary" -> new Dirs("google-chrome-canary", new String[]{"Google", "Chrome Canary"}, new String[]{"Google", "Chrome SxS", "User Data"});
            case "msedge" -> new Dirs("microsoft-edge", new String[]{"Microsoft Edge"}, new String[]{"Microsoft", "Edge", "User Data"});
            case "msedge-beta" -> new Dirs("microsoft-edge-beta", new String[]{"Microsoft Edge Beta"}, new String[]{"Microsoft", "Edge Beta", "User Data"});
            case "msedge-dev" -> new Dirs("microsoft-edge-dev", new String[]{"Microsoft Edge Dev"}, new String[]{"Microsoft", "Edge Dev", "User Data"});
            case "msedge-canary" -> new Dirs("microsoft-edge-canary", new String[]{"Microsoft Edge Canary"}, new String[]{"Microsoft", "Edge SxS", "User Data"});
            default -> null;
        };
        if (dirs == null) return null;
        return switch (os) {
            case "darwin" -> join(mac, dirs.mac());
            case "win32" -> join(appData, dirs.win());
            default -> override != null && !override.isBlank() ? Path.of(override) : linuxBase.resolve(dirs.linux());
        };
    }

    private static Path join(Path base, String[] parts) {
        Path out = base;
        for (String part : parts) out = out.resolve(part);
        return out;
    }

    /** ws://127.0.0.1:port/path from a DevToolsActivePort file (port, then the browser path); null when unusable. */
    static String activePort(Path userDataDir, Probes p) {
        if (userDataDir == null) return null;
        Optional<String> text = p.read(userDataDir.resolve("DevToolsActivePort"));
        if (text.isEmpty()) return null;
        String[] lines = text.get().trim().split("\\R");
        int port;
        try {
            port = Integer.parseInt(lines[0].trim());
        } catch (NumberFormatException failure) {
            return null;
        }
        if (port <= 0 || port > 65535 || !p.portOpen("127.0.0.1", port)) return null;
        String path = lines.length > 1 && !lines[1].isBlank() ? lines[1].trim() : "/devtools/browser";
        return "ws://127.0.0.1:" + port + path;
    }

    /** Resolves an explicit http(s), ws(s), or host:port CDP endpoint to a browser websocket. */
    static String resolveExplicit(String endpoint, Probes p) {
        String value = endpoint.trim();
        if (value.startsWith("ws://") || value.startsWith("wss://")) return value;
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return p.jsonVersion(value);
    }

    /** Candidates from the current user's own channel dirs and the localhost ports, de-duplicated. */
    static List<ObjectNode> discover(Probes p) {
        List<ObjectNode> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String channel : CHANNELS) {
            String ws = activePort(userDataDir(channel, p), p);
            if (ws != null && seen.add(port(ws))) out.add(JSON.createObjectNode().put("source", "channel:" + channel).put("endpoint", ws));
        }
        for (int port : PORTS) {
            if (seen.contains(String.valueOf(port)) || !p.portOpen("127.0.0.1", port)) continue;
            String ws = p.jsonVersion("http://127.0.0.1:" + port);
            if (ws != null && seen.add(String.valueOf(port))) out.add(JSON.createObjectNode().put("source", "port:" + port).put("endpoint", ws));
        }
        for (int i = 0; i < out.size(); i++) out.get(i).put("pick", i + 1);
        return out;
    }

    private static String port(String ws) {
        try {
            return String.valueOf(URI.create(ws).getPort());
        } catch (RuntimeException failure) {
            return ws;
        }
    }

    /** Profiles (dir names) under a channel dir that have the Playwright Extension installed; metadata only. */
    static List<String> extensionProfiles(Path userDataDir, Probes p) {
        List<String> out = new ArrayList<>();
        if (userDataDir == null || !p.exists(userDataDir)) return out;
        for (String profile : List.of("Default", "Profile 1", "Profile 2", "Profile 3")) {
            if (p.exists(userDataDir.resolve(profile).resolve("Extensions").resolve(EXTENSION_ID))) out.add(profile);
        }
        return out;
    }

    /** The five routes, in order, each with whether it is available now and the exact fix when not. */
    static ArrayNode routes(Probes p, boolean network) {
        ArrayNode routes = JSON.createArrayNode();
        ObjectNode channel = routes.addObject().put("route", "channel").put("order", 1);
        ArrayNode live = channel.putArray("available");
        for (String name : CHANNELS) {
            Path dir = userDataDir(name, p);
            if (dir != null && p.exists(dir.resolve("DevToolsActivePort")) && (!network || activePort(dir, p) != null)) live.add(name);
        }
        channel.put("ok", !live.isEmpty());
        if (live.isEmpty()) channel.put("fix", CHANNEL_FIX + ", then run attach --cdp-endpoint=chrome (or msedge)");
        else channel.put("use", "attach --cdp-endpoint=" + live.get(0).asText());

        ObjectNode extension = routes.addObject().put("route", "extension").put("order", 2);
        boolean token = p.env(TOKEN_ENV) != null && !p.env(TOKEN_ENV).isBlank();
        extension.put("token", token ? "present" : "absent");
        ArrayNode installed = extension.putArray("installed");
        for (String name : List.of("chrome", "msedge")) {
            for (String profile : extensionProfiles(userDataDir(name, p), p)) installed.add(name + "/" + profile);
        }
        extension.put("node", "bundled in com.microsoft.playwright:driver-bundle");
        extension.put("ok", !installed.isEmpty());
        if (installed.isEmpty()) {
            extension.put("fix", "install the Playwright Extension from the Chrome Web Store in your browser (a user action), then run attach --extension=chrome");
        } else if (!token) {
            extension.put("note", "no " + TOKEN_ENV + ": approve the connection in the browser, or copy the token from the extension's Manage clients page into that env var");
        }

        ObjectNode endpoints = routes.addObject().put("route", "endpoints").put("order", 3);
        if (network) {
            ArrayNode found = endpoints.putArray("discovered");
            for (ObjectNode candidate : discover(p)) found.add(candidate);
            endpoints.put("ok", !found.isEmpty());
        } else {
            endpoints.put("ok", true).put("note", "run doctor --attach or attach --discover to probe 127.0.0.1");
        }
        endpoints.put("use", "attach --cdp-endpoint=http://127.0.0.1:<port> | --endpoint=ws://<playwright-server> | --discover [--candidate=<n>]");
        if (network && !endpoints.path("ok").asBoolean()) endpoints.put("fix", "start Chromium, Edge, or an Electron app with --remote-debugging-port=9222, or pass an explicit endpoint");

        ObjectNode selenium = routes.addObject().put("route", "selenium").put("order", 4).put("ok", true);
        selenium.put("use", "attach --selenium=<goog:chromeOptions.debuggerAddress host:port or se:cdp url>");

        ObjectNode relaunch = routes.addObject().put("route", "relaunch").put("order", 5);
        relaunch.put("ok", dev.domengine.session.BrowserSession.findChrome() != null || dev.domengine.session.BrowserSession.findEdge() != null);
        relaunch.put("use", "relaunch-with-debug --browser=chrome|msedge --confirm (closes and reopens the browser; unsaved tab and form state is lost)");

        routes.addObject().put("route", "firefox").put("ok", false)
                .put("note", "Firefox attaches only over WebDriver BiDi when started with its remote agent (--remote-debugging-port); there is no channel or extension route. Use a Selenium/BiDi session.");
        routes.addObject().put("route", "webkit").put("ok", false)
                .put("note", "WebKit has no generic attach; start it through Playwright or Safari's WebDriver yourself.");
        return routes;
    }

    /**
     * {@code attach} with a route flag. CDP routes resolve to a browser websocket and re-run the existing
     * {@code attach --target=browser:cdp=<ws>} join through {@code delegate}.
     */
    static int run(String[] args, Path project, PrintStream out, PrintStream err, Function<String[], Integer> delegate) {
        Probes p = probes;
        String token = p.env(TOKEN_ENV);
        if (token != null && !token.isBlank()) SecretRedactor.note(TOKEN_ENV, token);
        String cdp = SetupCommand.value(args, "--cdp-endpoint");
        String server = SetupCommand.value(args, "--endpoint");
        String selenium = SetupCommand.value(args, "--selenium");
        String pick = SetupCommand.value(args, "--candidate");
        boolean discover = has(args, "--discover") || pick != null;
        String extension = extensionChannel(args);
        try {
            if (cdp != null && CHANNELS.contains(cdp.trim().toLowerCase(Locale.ROOT))) {
                String channel = cdp.trim().toLowerCase(Locale.ROOT);
                Path dir = userDataDir(channel, p);
                String ws = activePort(dir, p);
                if (ws == null) {
                    return fail(err, "no " + channel + " with remote debugging allowed (no live DevToolsActivePort in " + dir + ")", CHANNEL_FIX + ", then re-run attach --cdp-endpoint=" + channel);
                }
                return join(project, "channel:" + channel, ws, args, out, err, delegate);
            }
            if (cdp != null) {
                String ws = resolveExplicit(cdp, p);
                if (ws == null) return fail(err, "no DevTools endpoint at " + redact(cdp), "start the browser with --remote-debugging-port=<port> and pass http://127.0.0.1:<port>, or a ws:// browser URL");
                return join(project, "cdp-endpoint", ws, args, out, err, delegate);
            }
            if (selenium != null) {
                String ws = resolveExplicit(selenium, p);
                if (ws == null) return fail(err, "no DevTools endpoint at Selenium debuggerAddress " + redact(selenium), "pass goog:chromeOptions.debuggerAddress (host:port) or the se:cdp websocket of a live Chromium session");
                return join(project, "selenium", ws, args, out, err, delegate);
            }
            if (server != null) return playwrightServer(project, server, out, err);
            if (discover) {
                List<ObjectNode> found = discover(p);
                if (pick == null) {
                    ObjectNode body = JSON.createObjectNode().put("ok", true).put("op", "attach").put("route", "discover");
                    ArrayNode list = body.putArray("candidates");
                    found.forEach(list::add);
                    body.put("next", found.isEmpty() ? "nothing found on 127.0.0.1; see doctor --attach" : "attach --discover --candidate=<n>");
                    out.println(SecretRedactor.scrub(body.toString()));
                    return 0;
                }
                int index;
                try {
                    index = Integer.parseInt(pick.trim());
                } catch (NumberFormatException failure) {
                    return fail(err, "--candidate must be a candidate number", "run attach --discover to list candidates");
                }
                if (index < 1 || index > found.size()) return fail(err, "no candidate " + index + " (" + found.size() + " found)", "run attach --discover to list candidates");
                ObjectNode chosen = found.get(index - 1);
                return join(project, "discover:" + chosen.path("source").asText(), chosen.path("endpoint").asText(), args, out, err, delegate);
            }
            if (extension != null) return extension(project, extension, SetupCommand.value(args, "--profile-dir-name"), token, out, err);
            return fail(err, "attach needs a route", "attach --cdp-endpoint=chrome|<url>, --extension, --endpoint=<ws>, --selenium=<host:port>, --discover, or --target=browser:cdp=<ws>");
        } finally {
            // SecretRedactor.clear() runs when the command ends.
        }
    }

    private static int join(Path project, String route, String ws, String[] args, PrintStream out, PrintStream err,
                            Function<String[], Integer> delegate) {
        log(project, route, ws);
        List<String> next = new ArrayList<>();
        for (String token : args) {
            if (token != null && ROUTE_FLAGS.contains(token.split("=", 2)[0])) continue;
            next.add(token);
        }
        next.add("--target=browser:cdp=" + ws);
        err.println("attach: route=" + route + " endpoint=" + redact(ws));
        return delegate.apply(next.toArray(String[]::new));
    }

    private static int playwrightServer(Path project, String server, PrintStream out, PrintStream err) {
        if (!server.startsWith("ws://") && !server.startsWith("wss://")) return fail(err, "--endpoint must be a ws:// Playwright server URL", "pass --endpoint=ws://127.0.0.1:<port>/ or use --cdp-endpoint for a CDP browser");
        log(project, "playwright-server", server);
        try (com.microsoft.playwright.Playwright playwright = com.microsoft.playwright.Playwright.create(
                new com.microsoft.playwright.Playwright.CreateOptions().setEnv(tools.dscode.browser.PlaywrightConnection.DRIVER_ENV))) {
            com.microsoft.playwright.Browser browser = playwright.chromium().connect(server, new com.microsoft.playwright.BrowserType.ConnectOptions().setTimeout(15_000));
            ObjectNode body = JSON.createObjectNode().put("ok", true).put("op", "attach").put("route", "playwright-server")
                    .put("endpoint", redact(server)).put("version", browser.version()).put("contexts", browser.contexts().size());
            body.put("note", "a Playwright server speaks the Playwright protocol, not CDP: the pw verbs use it; snap/find/act need a CDP route");
            out.println(SecretRedactor.scrub(body.toString()));
            return 0;
        } catch (RuntimeException failure) {
            return fail(err, "Playwright server attach failed at " + redact(server) + ": " + firstLine(failure.getMessage()), "check the server is running (launchServer / run-server) and the URL is right");
        }
    }

    private static int extension(Path project, String channel, String profile, String token, PrintStream out, PrintStream err) {
        Optional<Path> node = PlaywrightNode.locate();
        if (node.isEmpty()) return fail(err, "the Node inside Playwright's driver-bundle was not found", "check com.microsoft.playwright:driver-bundle is on the classpath; pickleball does not install Node");
        List<String> command = PlaywrightNode.command(node.get(), List.of("cli", "-s=pkb-extension", "attach", "--extension=" + channel));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(tools.dscode.browser.PlaywrightConnection.DRIVER_ENV);
        if (profile != null && !profile.isBlank()) builder.environment().put("PLAYWRIGHT_MCP_PROFILE_DIR_NAME", profile);
        if (token != null && !token.isBlank()) builder.environment().put(TOKEN_ENV, token);
        log(project, "extension:" + channel + (profile == null ? "" : "/" + profile), "extension://" + channel + (token == null || token.isBlank() ? " (approval)" : " (token <redacted>)"));
        try {
            Process process = builder.start();
            boolean done = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!done) process.destroyForcibly();
            String scrubbed = SecretRedactor.scrub(text).replace(token == null || token.isBlank() ? "\u0000" : token, SecretRedactor.REDACTED);
            if (!done || process.exitValue() != 0) {
                return fail(err, "Playwright Extension attach did not complete: " + firstLine(scrubbed.strip()),
                        "install the Playwright Extension in " + channel + " (user action), approve the connect page, or set " + TOKEN_ENV + " from the extension's Manage clients page");
            }
            ObjectNode body = JSON.createObjectNode().put("ok", true).put("op", "attach").put("route", "extension").put("channel", channel)
                    .put("session", "pkb-extension").put("output", scrubbed.strip());
            body.put("note", "the extension session is driven through the bundled Playwright CLI (pw-cli --session=pkb-extension <command>); it has no CDP endpoint for snap/find/act");
            out.println(body);
            return 0;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return fail(err, "could not start the bundled Playwright CLI: " + failure.getMessage(), "check the driver-bundle jar is readable");
        }
    }

    static String extensionChannel(String[] args) {
        if (args == null) return null;
        for (String token : args) {
            if ("--extension".equals(token)) return "chrome";
            if (token != null && token.startsWith("--extension=")) {
                String value = token.substring("--extension=".length()).trim().toLowerCase(Locale.ROOT);
                return value.isBlank() ? "chrome" : value;
            }
        }
        return null;
    }

    private static boolean has(String[] args, String flag) {
        for (String token : args) if (flag.equals(token) || (token != null && token.startsWith(flag + "="))) return true;
        return false;
    }

    static String redact(String endpoint) {
        return SecretRedactor.scrub(tools.dscode.browser.PlaywrightConnection.redact(endpoint));
    }

    private static int fail(PrintStream err, String error, String fix) {
        err.println(SecretRedactor.scrub(WorkbenchCliHelp.structured(error, fix).stripTrailing()));
        return 1;
    }

    /** One line per attach, endpoint redacted, in the project's .pickleball/logs/attach.log. */
    static void log(Path project, String route, String endpoint) {
        if (project == null) return;
        try {
            Path file = project.toAbsolutePath().normalize().resolve(".pickleball").resolve("logs").resolve("attach.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + "\t" + route + "\t" + redact(endpoint) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging never blocks an attach.
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    static JsonNode routesJson(boolean network) {
        return routes(probes, network);
    }

    static Map<String, String> summary(JsonNode routes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode route : routes) out.put(route.path("route").asText(), route.path("ok").asBoolean() ? "yes" : "no");
        return out;
    }
}
