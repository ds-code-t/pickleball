package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.LiveHostFile;
import tools.dscode.control.protocol.speed.ProcessTree;
import tools.dscode.store.StoreEntities;
import tools.dscode.store.StoreHistory;
import tools.dscode.store.StorePaths;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase B1 verbs. Page commands with no {@code --run-id} use the session
 * {@code start} wrote. Logging is {@code .pickleball/logs/consumer-cli.log}
 * (off when {@code pkb_consumer_log=off}), one date-stamped line per entity, capped at 1 MiB.
 */
final class ConsumerReadyCommands {
    static final String SESSION_NAME = "consumer-session.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long LOG_CAP = 1_048_576L;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    /** Covers Chrome's DevToolsActivePort wait. A slow first start can still become healthy. */
    static final int HEALTH_WAIT_SECONDS = 50;
    /** Class-file entry the live-browser child must be able to load. */
    static final String HOST_CLASS_ENTRY = "tools/dscode/common/agentfast/LiveBrowserHost.class";
    private static final Pattern CACHE_VERSION = Pattern.compile("\"pickleballVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CACHE_COMPLETE = Pattern.compile("\"complete\"\\s*:\\s*(true|false)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build();

    private ConsumerReadyCommands() {
    }

    static int start(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        Path project = parsed.project();
        if (hasToken(parsed, "--stop")) {
            StopOutcome outcome = stopOwned(project);
            if (hasToken(parsed, "--all")) {
                out.println("orphans=" + killOrphans(project));
                stopController(project);
                out.println("controller=stopped");
            }
            if (outcome.stopped()) err.println("notice: live-stop stops the start host");
            else if (outcome.unrecorded()) {
                err.println("notice: live-stop deleted a state file with no recorded start times");
            }
            out.println(outcome.stopped() ? "stopped=true" : "stopped=false");
            return 0;
        }
        String engine = flag(parsed, "--engine");
        if (engine != null && !engine.isBlank()) {
            err.println("error: --engine=" + engine.trim() + " is not a browser engine");
            err.println("fix: the page engine is Chrome DevTools; use --browser=chrome|edge|chrome-headless");
            return 2;
        }
        String url = flag(parsed, "--url");
        String urlKey = flag(parsed, "--url-key");
        if ((url == null || url.isBlank()) && urlKey != null && !urlKey.isBlank()) {
            url = UrlKeys.resolve(project, urlKey);
            if (url == null || url.isBlank()) {
                err.println("error: --url-key=" + urlKey.trim() + " did not resolve");
                err.println("fix: pass --url= or a URL.* key in the project configs");
                return 2;
            }
        }
        if (url != null && tools.dscode.common.agentfast.FixturePages.isFixture(url)) {
            url = tools.dscode.common.agentfast.FixturePages.resolve(url);
        }
        String browser = flag(parsed, "--browser");
        if (browser != null && browser.toLowerCase(Locale.ROOT).contains("selenium")) {
            err.println("error: --browser=selenium is not supported");
            err.println("fix: the page engine is Chrome DevTools; use --browser=chrome|edge|chrome-headless");
            return 2;
        }
        if (url == null || url.isBlank() || browser == null || browser.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("start requires --url and --browser", "start"));
            return 2;
        }
        String binary = resolveBrowser(browser.trim());
        if (binary == null) {
            err.println("error: unsupported --browser=" + browser.trim());
            return 2;
        }
        String size = flag(parsed, "--size");
        if (size != null && !size.isBlank() && !size.trim().matches("\\d+[xX,]\\d+")) {
            err.print(WorkbenchCliHelp.usageError("start --size is WxH, for example 1600x1000", "start"));
            return 2;
        }
        Optional<LiveBridgeFile.Bridge> worker = LiveBridgeFile.read(project);
        if (worker.isPresent() && alive(worker.get().pid())) {
            err.println("error: a Workbench worker is live");
            err.println("fix: use the worker's browser or stop it");
            return 2;
        }
        stopQuiet(project);
        String hold = flag(parsed, "--hold");
        if (hold != null && !hold.isBlank()) {
            try {
                LeaseHold.arm(project, hold);
            } catch (RuntimeException | IOException failure) {
                err.println("error: could not hold the lease\nfix: " + failure.getMessage() + "\n");
                return 1;
            }
        }
        Map<String, String> env = env(parsed);
        // Owner decision 2026-11-28 (HCR-28): start --run-id=X names the live session X; an id is made only when none is given.
        String id = liveId(parsed.coordination() == null ? null : parsed.coordination().runId());
        if (id == null) {
            err.print(WorkbenchCliHelp.usageError("start --run-id is letters, digits, dot, dash or underscore (1 to 64)", "start"));
            return 2;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        boolean headed = headed(parsed, browser);
        Path logFile = project.resolve(".pickleball").resolve("logs").resolve("live-browser.log");
        Process process;
        try {
            process = spawnHost(project, id, url.trim(), browser.trim(), token, headed, binary, logFile, size);
        } catch (IOException failure) {
            err.println("error: could not start the live browser\nfix: " + failure.getMessage() + "\n");
            return 1;
        }
        try {
            if (!awaitHealthy(process, project, id, logFile, err)) {
                destroyHost(process);
                stopQuiet(project);
                return 1;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("url", url.trim());
            body.put("browser", browser.trim());
            body.put("env", env);
            body.put("live", true);
            body.put("headed", headed);
            if (size != null && !size.isBlank()) body.put("size", size.trim());
            Path file = sessionFile(project);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body) + "\n", StandardCharsets.UTF_8);
            AgentControl.armChatCursor(project, id);
        } catch (IOException failure) {
            destroyHost(process);
            stopQuiet(project);
            err.println("error: could not publish the live session\nfix: " + failure.getMessage() + "\n");
            return 1;
        }
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        boolean navigated = host.map(LiveHostFile.Host::navigated).orElse(true);
        String navError = host.map(LiveHostFile.Host::navError).orElse("");
        boolean panel = hasToken(parsed, "--panel");
        boolean panelOpen = false;
        if (panel) {
            java.io.ByteArrayOutputStream panelOut = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream panelErr = new java.io.ByteArrayOutputStream();
            int panelCode = WorkbenchAgentCommands.run(
                    new String[]{"panel-open", "--target=page", project.toString()},
                    new java.io.PrintStream(panelOut, true, StandardCharsets.UTF_8),
                    new java.io.PrintStream(panelErr, true, StandardCharsets.UTF_8)
            );
            panelOpen = panelCode == 0;
            if (!panelOpen) err.print(panelErr.toString(StandardCharsets.UTF_8));
        }
        out.println("session=" + id);
        out.println("url=" + url.trim());
        out.println("browser=" + browser.trim());
        if (!env.isEmpty()) out.println("env=" + envText(env));
        out.println("live=true");
        out.println("headed=" + headed);
        out.println("navigated=" + navigated);
        if (size != null && !size.isBlank()) out.println("size=" + size.trim());
        if (panel) out.println("panel=" + (panelOpen ? "open" : "closed"));
        if (!navigated) {
            out.println("error: navigation failed" + (navError == null || navError.isBlank() ? "" : ": " + navError));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", navigated && (!panel || panelOpen));
        envelope.put("session", id);
        envelope.put("runId", id);
        envelope.put("url", url.trim());
        envelope.put("browser", browser.trim());
        envelope.put("live", true);
        envelope.put("headed", headed);
        envelope.put("navigated", navigated);
        envelope.put("ready", navigated && (!panel || panelOpen));
        envelope.put("frame", "top");
        if (size != null && !size.isBlank()) envelope.put("size", size.trim());
        if (panel) envelope.put("panel", panelOpen ? "open" : "closed");
        envelope.put("sentence", panelOpen ? "live browser ready, panel open" : "live browser ready");
        if (!navigated) envelope.put("error", "navigation failed");
        else if (panel && !panelOpen) envelope.put("error", "panel did not open");
        try {
            Map<String, Object> stamped = tools.dscode.common.agentfast.AgentEnvelope.apply(
                    envelope, navigated && (!panel || panelOpen) ? 0 : 1, "start", project, id);
            out.println(QuietWarnings.dropNoise(JSON.writeValueAsString(stamped)));
        } catch (IOException ignored) {
            // The line fields above are already the session id.
        }
        log(project, id, "start url=" + url.trim() + " browser=" + browser.trim() + " headed=" + headed);
        if (!navigated) return 0;
        return panel && !panelOpen ? 1 : 0;
    }

    private static boolean headed(WorkbenchCommandLine.Parsed parsed, String browser) {
        if (hasToken(parsed, "--headed")) return headedRequest(parsed) && !GraphicsEnvironment.isHeadless();
        return showHeaded(browser);
    }

    /**
     * The {@code --headed} request, before the headless-display check.
     * A bare {@code --headed} is true, including when the next token is another flag.
     */
    static boolean headedRequest(WorkbenchCommandLine.Parsed parsed) {
        if (!hasToken(parsed, "--headed")) return false;
        String value = flag(parsed, "--headed");
        // start is not a forwarded command, so the project token (".") sits after a bare --headed.
        if (projectToken(parsed, value)) value = "";
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return value == null || value.isBlank() || "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /** A project directory taken by the parser is not a value of a bare boolean flag. */
    private static boolean projectToken(WorkbenchCommandLine.Parsed parsed, String value) {
        if (value == null || value.isBlank()) return false;
        if (".".equals(value) || "..".equals(value)) return true;
        if (parsed.project() == null) return false;
        try {
            java.nio.file.Path token = java.nio.file.Path.of(value);
            java.nio.file.Path project = parsed.project().toAbsolutePath().normalize();
            java.nio.file.Path resolved = token.isAbsolute() ? token.normalize() : project.getParent() == null
                    ? token.normalize() : project.resolveSibling(value).normalize();
            return resolved.equals(project);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Chrome, Chromium, or Edge. Null means the name is unsupported or the binary is missing. */
    static String resolveBrowser(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.contains("selenium")) return null;
        if (lower.contains("edge")) return BrowserSession.findEdge();
        if (lower.contains("firefox")) return null;
        if (lower.contains("chrome") || lower.contains("chromium")) return BrowserSession.findChrome();
        return null;
    }

    /** Stops the host {@code start} recorded. A Workbench worker bridge is left running. */
    static boolean stopQuiet(Path project) {
        return stopOwned(project).stopped();
    }

    /**
     * {@code stopped} is true only when an owned pid was alive and this call stopped it.
     * A start time of 0 was not recorded. {@link ProcessTree#sameStart} treats that as a
     * match, so this method must not call it for those pids.
     */
    private record StopOutcome(boolean stopped, boolean unrecorded) {
    }

    private static StopOutcome stopOwned(Path project) {
        if (project == null) return new StopOutcome(false, false);
        try {
            return stopOwnedNow(project);
        } finally {
            killProjectProcesses(project);
            releaseBrowserLog(project);
        }
    }

    /**
     * A host that exits can leave Chrome holding the browser log.
     * Match the live-browser host and a browser whose profile is this project.
     * A command line that only names the project, such as an IDE, is left alone.
     */
    private static void killProjectProcesses(Path project) {
        String token = project.toAbsolutePath().normalize().toString();
        if (token.isBlank()) return;
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().forEach(handle -> {
            if (handle.pid() == self) return;
            String line = handle.info().commandLine().orElse("");
            if (ownedBrowserProcess(line, token)) {
                tools.dscode.control.protocol.speed.ProcessTree.kill(handle.pid());
            }
        });
    }

    /** The live-browser JVM, or Chrome or Edge launched with this project's user-data-dir. */
    private static boolean ownedBrowserProcess(String line, String token) {
        if (line == null || line.isBlank() || token == null || token.isBlank() || !line.contains(token)) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("java") && lower.contains("live-browser")) return true;
        boolean browser = lower.contains("chrome") || lower.contains("msedge") || lower.contains("chromium");
        return browser && lower.contains("user-data-dir");
    }

    private static StopOutcome stopOwnedNow(Path project) {
        deleteSession(project);
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        if (host.isEmpty()) return new StopOutcome(false, false);
        LiveHostFile.Host found = host.get();
        boolean hostRecorded = found.pidStarted() > 0;
        boolean chromeRecorded = found.chromeStarted() > 0;
        if (!hostRecorded && !chromeRecorded) {
            LiveHostFile.delete(project);
            tools.dscode.common.agentfast.LiveBrowserHost.pruneProfiles(project, null);
            return new StopOutcome(false, true);
        }
        boolean stopped = false;
        if (hostRecorded) {
            ProcessHandle handle = ProcessHandle.of(found.pid()).orElse(null);
            if (handle != null && handle.isAlive() && ProcessTree.sameStart(handle, found.pidStarted())) {
                postStop(found.url(), found.token());
                long pid = found.pid();
                try {
                    handle.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    ProcessTree.kill(pid);
                }
                if (handle.isAlive() && ProcessTree.sameStart(handle, found.pidStarted())) ProcessTree.kill(pid);
                stopped = true;
            }
        }
        if (chromeRecorded && found.chromePid() > 0
                && ProcessTree.killIfStarted(found.chromePid(), found.chromeStarted())) {
            stopped = true;
        }
        LiveHostFile.delete(project);
        tools.dscode.common.agentfast.LiveBrowserHost.pruneProfiles(project, null);
        return new StopOutcome(stopped, false);
    }

    /** Windows refuses to delete a log another process still has open. */
    private static void releaseBrowserLog(Path project) {
        Path log = project.resolve(".pickleball").resolve("logs").resolve("live-browser.log");
        if (!Files.isRegularFile(log)) return;
        Path probe = log.resolveSibling("live-browser.log.probe");
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.move(log, probe);
                Files.move(probe, log);
                return;
            } catch (IOException locked) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Project-scoped Chrome that is not the recorded host. A Workbench worker is not Chrome. */
    private static String killOrphans(Path project) {
        String listed = orphans(project, 0L);
        if (listed == null || listed.isBlank() || "none".equals(listed)) return "none";
        for (String piece : listed.split(",")) {
            try {
                ProcessTree.kill(Long.parseLong(piece.trim()));
            } catch (NumberFormatException ignored) {
                // orphans() only emits pids.
            }
        }
        return listed;
    }

    private static void stopController(Path project) {
        if (Boolean.getBoolean(AgentHop.INSIDE)) {
            try {
                Files.deleteIfExists(PkbMain.handleFile(project));
            } catch (IOException ignored) {
                // The controller removes the handle again when it exits.
            }
            AgentController.requestStopAfterReply();
            return;
        }
        java.io.PrintStream previousOut = System.out;
        java.io.PrintStream previousErr = System.err;
        try {
            System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            PkbMain.run(new String[]{"controller-stop", project == null ? "" : project.toString()});
        } catch (Exception ignored) {
            // No controller is already stopped.
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    static int doctor(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        Path project = parsed.project();
        String[] pruneTokens = parsed.forwarded();
        if (pruneTokens != null && java.util.Arrays.asList(pruneTokens).contains("--prune")) {
            List<String> removed = prune(project);
            for (String line : removed) out.println("removed=" + line);
            out.println("pruned=" + removed.size());
            return 0;
        }
        String chrome = tools.dscode.control.protocol.BrowserPresence.chromeUp(project) ? "present" : "absent";
        String session = sessionId(project);
        if (session == null || session.isBlank()) session = "absent";
        CliHealth health = readCliHealth(project);
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        boolean bridge = host.isPresent() && alive(host.get().pid());
        if (!bridge) bridge = legacyHostAlive(project);
        Optional<LiveBridgeFile.Bridge> workerBridge = LiveBridgeFile.read(project);
        boolean worker = workerBridge.isPresent() && alive(workerBridge.get().pid());
        boolean lease = worker || tools.dscode.control.protocol.LeaseClock.fresh(project);
        boolean page = "attached".equals(health.page());
        out.println("chrome=" + chrome);
        out.println("session=" + session);
        out.println("health=" + health.status());
        out.println("headed=" + health.headed());
        out.println("pid=" + health.pid());
        out.println("bridge=" + (bridge ? "present" : "absent"));
        out.println("worker=" + (worker ? "present" : "absent"));
        out.println("lease=" + (lease ? "held" : "open"));
        out.println("page=" + (page ? "attached" : "absent"));
        out.println("leaseLeft=" + LeaseHold.left(project));
        long chromePid = host.map(LiveHostFile.Host::chromePid).orElse(0L);
        out.println("orphans=" + orphans(project, chromePid));
        JsonNode healthBody = healthBody(project);
        String frame = healthBody == null ? "top" : healthBody.path("frame").asText("top");
        if (frame == null || frame.isBlank()) frame = "top";
        int placed = healthBody == null ? 0 : healthBody.path("placed").asInt(0);
        out.println("frame=" + frame);
        out.println("placed=" + placed);
        int waits = healthBody == null ? 0 : healthBody.path("waits").asInt(0);
        out.println("waits=" + waits);
        String desktopFamily = dev.domengine.desktop.NativeDesktop.family();
        String[] tokens = parsed.forwarded();
        boolean desktop = tokens != null && java.util.Arrays.asList(tokens).contains("--desktop");
        if (desktop) {
            // doctor --desktop: each opt-in Grid desktop driver for this OS, running or not, and what is missing.
            com.fasterxml.jackson.databind.node.ObjectNode drivers = dev.domengine.desktop.GridDrivers.report(System.getenv(), desktopFamily,
                    dev.domengine.desktop.GridDrivers.httpProbe(), ConsumerReadyCommands::onPath);
            out.println("desktopDriver=" + drivers.path("selected").asText("native"));
            out.println("desktop=" + drivers);
        }
        // E12: opt-in OSS add-ons (axe, lighthouse, devtools-mcp): installed or not; installing downloads, nothing here does.
        out.println(tools.dscode.optional.OptionalAddons.doctorLine());
        // D11: the opt-in ONNX OCR runtime and model (setup ocr --runtime=onnx --confirm); nothing is loaded here.
        java.nio.file.Path ocrDir = dev.domengine.query.OnnxOcrRuntime.dir();
        java.util.List<String> ocrMissing = dev.domengine.query.OnnxOcrRuntime.missing(ocrDir);
        out.println("ocrRuntime=" + (ocrMissing.isEmpty() ? "onnx installed (" + ocrDir + ")"
                : "not installed (missing " + String.join(",", ocrMissing) + " in " + ocrDir + "; fix: setup ocr --runtime=onnx --confirm)"));
        // D13: which attach routes are available right now. Plain doctor reads files only; --attach also probes 127.0.0.1.
        boolean attach = tokens != null && java.util.Arrays.asList(tokens).contains("--attach");
        com.fasterxml.jackson.databind.JsonNode routes = BrowserAttach.routesJson(attach);
        StringBuilder summary = new StringBuilder();
        BrowserAttach.summary(routes).forEach((route, ok) -> summary.append(summary.length() == 0 ? "" : ",").append(route).append(':').append(ok));
        out.println("attachRoutes=" + summary + (attach ? "" : " (doctor --attach for discovery and fixes)"));
        if (attach) out.println("attach=" + routes);
        // E1: live protocol backends (Playwright, BiDi, CDP, classic) and which one serves each capability.
        protocolsReport(project, tokens, out);
        log(project, session, "doctor health=" + health.status());
        return 0;
    }

    /**
     * {@code protocols=<browser>:<backends>} always; {@code doctor --protocols} adds one line per backend and per
     * capability. Endpoints: {@code --cdp-endpoint=}/{@code --bidi-endpoint=}, then {@code pkb_cdp_endpoint}/
     * {@code pkb_bidi_endpoint}, then this project's live browser. Localhost probes only, never a download.
     */
    static void protocolsReport(Path project, String[] tokens, java.io.PrintStream out) {
        java.util.List<String> list = tokens == null ? java.util.List.of() : java.util.Arrays.asList(tokens);
        boolean full = list.contains("--protocols");
        String cdp = tokenValue(list, "--cdp-endpoint=");
        String bidi = tokenValue(list, "--bidi-endpoint=");
        try {
            if (cdp != null) cdp = PwCommand.endpoint(project, java.util.Map.of("cdp-endpoint", cdp));
        } catch (RuntimeException ignored) {
            cdp = null;
        }
        if (cdp == null) cdp = tools.dscode.browser.BrowserProtocols.setting("pkb_cdp_endpoint");
        if (cdp == null) cdp = PwCommand.liveEndpoint(project);
        if (bidi == null) bidi = tools.dscode.browser.BrowserProtocols.setting("pkb_bidi_endpoint");
        if (cdp == null && bidi == null) {
            out.println("protocols=none (no browser attached; fix: start or attach a browser, or pass --cdp-endpoint= / --bidi-endpoint=)");
            out.println("protocols.start=none");
            printWorkerProtocols(project, out);
            return;
        }
        tools.dscode.browser.BrowserProtocols protocols = tools.dscode.browser.BrowserProtocols.probe(cdp, bidi);
        out.println("protocols=" + protocols.summary() + (full ? "" : " (doctor --protocols for the capability table)"));
        out.println("protocols.start=" + protocols.summary());
        printWorkerProtocols(project, out);
        if (full) protocols.table().forEach(line -> out.println("protocol." + line));
        if (full) hostRoutes(project, out);
    }

    /** The isolate worker's Selenium debugger, labelled separately from the start host. */
    private static void printWorkerProtocols(Path project, java.io.PrintStream out) {
        java.util.Optional<tools.dscode.control.protocol.LiveBridgeFile.Bridge> bridge =
                tools.dscode.control.protocol.LiveBridgeFile.read(project);
        if (bridge.isEmpty()) return;
        String address = tools.dscode.control.protocol.SessionDescriptorWriter.read(project, bridge.get().sessionId())
                .map(tools.dscode.control.protocol.SessionDescriptor::cdpDebuggerAddress)
                .orElse(null);
        String summary = "unknown:classic";
        if (address != null && !address.isBlank()) {
            String probe = address.startsWith("http") || address.startsWith("ws") ? address : "http://" + address;
            tools.dscode.browser.BrowserProtocols worker = tools.dscode.browser.BrowserProtocols.probe(probe, null);
            worker.live(tools.dscode.browser.BrowserProtocols.Backend.CLASSIC, "selenium", address);
            summary = worker.summary();
        }
        out.println("protocols.worker=" + summary);
    }

    /** E5: routes, mocks and overrides held by this project's protocol host (none when no host runs). */
    static void hostRoutes(Path project, java.io.PrintStream out) {
        if (tools.dscode.browser.proto.ProtoClient.running(project) == null) {
            out.println("routes=0 (no protocol host)");
            return;
        }
        com.fasterxml.jackson.databind.JsonNode reply = tools.dscode.browser.proto.ProtoClient.call(project, null, null, null, "net-routes", java.util.Map.of());
        com.fasterxml.jackson.databind.JsonNode routes = reply.path("routes");
        out.println("routes=" + routes.size() + " extraHeaders=" + reply.path("extraHeaders").size() + " auth=" + reply.path("auth").asBoolean()
                + " wsRoutes=" + reply.path("wsRoutes").asInt());
        for (com.fasterxml.jackson.databind.JsonNode r : routes) {
            out.println("route." + r.path("id").asText() + "=" + r.path("action").asText() + " " + r.path("match").asText() + " via="
                    + r.path("via").asText() + " hits=" + r.path("hits").asInt() + " timesLeft=" + r.path("timesLeft").asInt());
        }
        // E10: a crashed page or worker target is a doctor warning.
        int crashed = tools.dscode.browser.proto.ProtoClient.call(project, null, null, null, "host.status", java.util.Map.of()).path("crashedTargets").asInt();
        if (crashed > 0) out.println("warning: " + crashed + " browser target(s) crashed this session; see browser-events --kinds=target");
    }

    private static String tokenValue(java.util.List<String> tokens, String prefix) {
        for (String token : tokens) if (token != null && token.startsWith(prefix)) return token.substring(prefix.length());
        return null;
    }

    /** True when an executable of that name is on PATH (no process is started). */
    static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String suffix : new String[]{"", ".cmd", ".exe"}) {
                if (Files.isExecutable(Path.of(dir, name + suffix))) return true;
            }
        }
        return false;
    }

    /**
     * A Chrome or Chromium process whose command line names this project.
     * A Java protocol host is never an orphan, even when its command line mentions chrome and pickleball.
     */
    static boolean listsOrphan(String command, String needle) {
        if (command == null || needle == null || needle.isBlank()) return false;
        String lower = command.toLowerCase(Locale.ROOT);
        if (!lower.contains("chrome") && !lower.contains("chromium")) return false;
        if (!command.contains(needle) || !lower.contains("pickleball")) return false;
        if (command.contains(" --type=")) return false;
        if (javaHost(lower)) return false;
        return !lower.contains("protohost") && !lower.contains("protocol-host");
    }

    /** The executable is java or javaw. A path segment such as Program Files is not the token. */
    private static boolean javaHost(String lower) {
        if (lower.startsWith("java ") || lower.startsWith("javaw ")) return true;
        if (lower.contains("/java ") || lower.contains("\\java ") || lower.contains("/javaw ") || lower.contains("\\javaw ")) return true;
        for (String token : lower.split("\\s+")) {
            if (token.equals("java") || token.equals("javaw") || token.endsWith("/java") || token.endsWith("\\java")
                    || token.endsWith("/javaw") || token.endsWith("\\javaw")
                    || token.endsWith("java.exe") || token.endsWith("javaw.exe")) return true;
        }
        return false;
    }

    /** Chrome or Chromium processes whose command line names this project and is not the live host. */
    private static String orphans(Path project, long chromePid) {
        Path proc = Path.of("/proc");
        if (project == null || !Files.isDirectory(proc)) return "none";
        String needle = project.toAbsolutePath().normalize().toString();
        List<String> found = new ArrayList<>();
        java.util.Set<Long> live = new java.util.HashSet<>();
        if (chromePid > 0) ProcessHandle.of(chromePid).ifPresent(main -> main.descendants().forEach(one -> live.add(one.pid())));
        try (var children = Files.list(proc)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.isEmpty() || !name.chars().allMatch(Character::isDigit)) continue;
                long pid;
                try {
                    pid = Long.parseLong(name);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (pid == chromePid || pid == ProcessHandle.current().pid() || live.contains(pid)) continue;
                String command;
                try {
                    command = Files.readString(child.resolve("cmdline")).replace('\0', ' ');
                } catch (IOException ignored) {
                    continue;
                }
                if (!listsOrphan(command, needle)) continue;
                found.add(Long.toString(pid));
            }
        } catch (IOException ignored) {
            return "none";
        }
        return found.isEmpty() ? "none" : String.join(",", found);
    }

    /** Full GET /health body, including frame and placed when the host sends them. */
    private static JsonNode healthBody(Path project) {
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        if (host.isEmpty() || host.get().url() == null || host.get().url().isBlank()) return null;
        try {
            String base = host.get().url();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + host.get().token())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) return null;
            return JSON.readTree(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean showHeaded(String browser) {
        if (GraphicsEnvironment.isHeadless()) return false;
        return browser == null || !browser.toLowerCase(Locale.ROOT).contains("headless");
    }

    /**
     * Classpath for the {@code LiveBrowserHost} child. This JVM's {@code java.class.path}
     * is used when it already contains that class. A Maven {@code exec:java} path that is
     * only the classworlds launcher is not used. Next is the versioned or
     * {@code .pickleball/workbench/classpath.cache} when that file contains the class,
     * then the code source and loader URLs. None of those is an error and the child is not started.
     */
    static String hostClasspath(Path project, String javaClassPath) throws IOException {
        if (classpathHasHost(javaClassPath)) return javaClassPath;
        String cached = classpathFromHostCache(project);
        if (classpathHasHost(cached)) return cached;
        String loaded = classpathFromHostLoaders();
        if (classpathHasHost(loaded)) return loaded;
        throw new IOException("LiveBrowserHost is not on java.class.path, classpath.cache, or the classes that loaded this program. "
                + "Run the project wrapper once so .pickleball/workbench/classpath.cache contains "
                + "tools.dscode.common.agentfast.LiveBrowserHost. A classworlds-only path is not used.");
    }

    static boolean classpathHasHost(String classpath) {
        if (classpath == null || classpath.isBlank()) return false;
        for (String raw : classpath.split(java.io.File.pathSeparator, -1)) {
            String entry = unquoteClasspathEntry(raw.trim());
            if (entry.isEmpty()) continue;
            try {
                if (entryHasHost(Path.of(entry))) return true;
            } catch (RuntimeException ignored) {
                // A wrapper token that is not a path does not contain the host.
            }
        }
        return false;
    }

    private static boolean entryHasHost(Path path) {
        if (path == null) return false;
        try {
            if (Files.isDirectory(path)) return Files.isRegularFile(path.resolve(HOST_CLASS_ENTRY));
            if (!Files.isRegularFile(path)) return false;
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) return false;
            try (JarFile jar = new JarFile(path.toFile())) {
                return jar.getEntry(HOST_CLASS_ENTRY) != null;
            }
        } catch (Exception failure) {
            return false;
        }
    }

    private static String classpathFromHostCache(Path project) {
        if (project == null) return "";
        for (Path file : hostCacheFiles(project)) {
            if (!Files.isRegularFile(file)) continue;
            try {
                String joined = readHostCache(file);
                if (classpathHasHost(joined)) return joined;
            } catch (Exception ignored) {
                // The next candidate, or the loader classpath, is the fallback.
            }
        }
        return "";
    }

    private static List<Path> hostCacheFiles(Path project) {
        List<Path> files = new ArrayList<>();
        Path root = project.resolve(".pickleball");
        String version = hostCacheVersion(root.resolve("current.json"));
        if (version != null) {
            files.add(root.resolve("v").resolve(version).resolve("workbench").resolve("classpath.cache"));
        }
        files.add(root.resolve("workbench").resolve("classpath.cache"));
        return files;
    }

    private static String hostCacheVersion(Path current) {
        if (!Files.isRegularFile(current)) return null;
        try {
            String json = Files.readString(current);
            Matcher version = CACHE_VERSION.matcher(json);
            if (!version.find()) return null;
            String value = version.group(1).trim();
            if (!safeCacheVersion(value)) return null;
            Matcher complete = CACHE_COMPLETE.matcher(json);
            if (complete.find() && !Boolean.parseBoolean(complete.group(1))) return null;
            return value;
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean safeCacheVersion(String value) {
        if (value.isBlank()) return false;
        if (value.contains("/") || value.contains("\\") || value.contains("..")) return false;
        if (value.contains(":") || value.startsWith(".")) return false;
        return !value.equals(".");
    }

    private static String readHostCache(Path file) throws IOException {
        String text = Files.readString(file).trim();
        if (text.isEmpty()) return "";
        String first = text.lines().findFirst().orElse("");
        return unquoteClasspathEntry(first.trim());
    }

    private static String classpathFromHostLoaders() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        addHostCodeSource(tools.dscode.common.agentfast.LiveBrowserHost.class, entries);
        addHostCodeSource(ConsumerReadyCommands.class, entries);
        addHostLoaderUrls(tools.dscode.common.agentfast.LiveBrowserHost.class.getClassLoader(), entries);
        addHostLoaderUrls(Thread.currentThread().getContextClassLoader(), entries);
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void addHostCodeSource(Class<?> type, LinkedHashSet<String> entries) {
        try {
            if (type.getProtectionDomain().getCodeSource() == null
                    || type.getProtectionDomain().getCodeSource().getLocation() == null) return;
            Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (Files.exists(path)) entries.add(path.toString());
        } catch (Exception ignored) {
            // A missing code source is not a classpath entry.
        }
    }

    private static void addHostLoaderUrls(ClassLoader loader, LinkedHashSet<String> entries) {
        ClassLoader current = loader;
        for (int guard = 0; current != null && guard < 8; guard++) {
            if (current instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    try {
                        Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
                        if (Files.exists(path)) entries.add(path.toString());
                    } catch (Exception ignored) {
                        // Skip a URL that is not a file.
                    }
                }
            }
            current = current.getParent();
        }
    }

    /** Strips one pair of Java argument-file quotes. A doubled backslash is one backslash. */
    private static String unquoteClasspathEntry(String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() < 2 || !body.startsWith("\"") || !body.endsWith("\"")) return body;
        String inner = body.substring(1, body.length() - 1);
        StringBuilder out = new StringBuilder(inner.length());
        for (int index = 0; index < inner.length(); index++) {
            char current = inner.charAt(index);
            if (current == '\\' && index + 1 < inner.length()) out.append(inner.charAt(++index));
            else out.append(current);
        }
        return out.toString();
    }

    private static Process spawnHost(
            Path project,
            String id,
            String url,
            String browser,
            String token,
            boolean headed,
            String binary,
            Path logFile,
            String size
    ) throws IOException {
        Path argFile = project.resolve(".pickleball").resolve("live-browser").resolve(id).resolve("args.txt");
        Files.createDirectories(argFile.getParent());
        Files.createDirectories(logFile.getParent());
        capLog(logFile);
        Files.writeString(
                logFile,
                Instant.now() + " live-browser start id=" + id + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        String classpath = hostClasspath(project, System.getProperty("java.class.path", ""));
        String cdpInput = System.getProperty("pkb_cdp_input");
        String inputFlag = cdpInput == null || cdpInput.isBlank() ? "" : "-Dpkb_cdp_input=" + quoteArg(cdpInput) + "\n";
        String body = inputFlag + String.join("\n",
                "-cp",
                quoteArg(classpath),
                "tools.dscode.common.agentfast.LiveBrowserHost",
                quoteArg(project.toAbsolutePath().normalize().toString()),
                quoteArg(id),
                quoteArg(url),
                quoteArg(browser),
                quoteArg(token),
                headed ? "true" : "false",
                quoteArg(binary),
                quoteArg(size == null ? "" : size.trim())
        ) + "\n";
        Files.writeString(argFile, body, StandardCharsets.UTF_8);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(javaBin.toString(), "@" + argFile.toAbsolutePath());
        builder.directory(project.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static String quoteArg(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        boolean simple = true;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch <= ' ' || ch == '"' || ch == '\\' || ch == '#') {
                simple = false;
                break;
            }
        }
        if (simple) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean awaitHealthy(Process process, Path project, String id, Path logFile, java.io.PrintStream err) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HEALTH_WAIT_SECONDS);
        try {
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    err.print(unhealthyMessage(readStage(project, id), tail(logFile), logFile, true));
                    return false;
                }
                Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
                if (host.isPresent() && "ok".equals(healthStatus(host.get()))) return true;
                Thread.sleep(100L);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("error: live browser host wait was interrupted\nfix: retry start\n");
            return false;
        }
        err.print(unhealthyMessage(readStage(project, id), tail(logFile), logFile, false));
        return false;
    }

    static String unhealthyMessage(String stage, String logTail, Path logFile) {
        return unhealthyMessage(stage, logTail, logFile, false);
    }

    private static String unhealthyMessage(String stage, String logTail, Path logFile, boolean exited) {
        StringBuilder text = new StringBuilder();
        if (exited) {
            text.append("error: live browser host exited");
        } else {
            text.append("error: live browser host did not become healthy within ").append(HEALTH_WAIT_SECONDS).append('s');
        }
        if (stage != null && !stage.isBlank()) text.append(" (stage=").append(stage.trim()).append(')');
        String error = firstErrorLine(logTail);
        if (!error.isBlank()) text.append(": ").append(error);
        text.append("\nfix: the partial Chrome process was stopped. Retry start. See ").append(logFile);
        if (logTail != null && !logTail.isBlank()) text.append('\n').append(logTail);
        if (text.charAt(text.length() - 1) != '\n') text.append('\n');
        return text.toString();
    }

    private static String firstErrorLine(String logTail) {
        if (logTail == null || logTail.isBlank()) return "";
        for (String line : logTail.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("error:")) return trimmed;
        }
        return "";
    }

    private static String readStage(Path project, String id) {
        if (project == null || id == null || id.isBlank()) return "";
        Path file = project.resolve(".pickleball").resolve("live-browser").resolve(id).resolve("stage.txt");
        try {
            return Files.readString(file).trim();
        } catch (IOException failure) {
            return "";
        }
    }

    private record HealthPayload(String status, String page) {
    }

    private static String healthStatus(LiveHostFile.Host host) {
        HealthPayload payload = fetchHealth(host);
        return payload == null || payload.status().isBlank() ? "down" : payload.status();
    }

    /** GET /health while the host pid is alive, including after Chrome itself has died. */
    private static HealthPayload fetchHealth(LiveHostFile.Host host) {
        if (host == null || host.url() == null || host.url().isBlank()) return null;
        try {
            String base = host.url().endsWith("/") ? host.url().substring(0, host.url().length() - 1) : host.url();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + host.token())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new HealthPayload("down", "absent");
            JsonNode node = JSON.readTree(response.body() == null ? "" : response.body());
            return new HealthPayload(node.path("status").asText(""), node.path("page").asText(""));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new HealthPayload("down", "absent");
        } catch (Exception failure) {
            return new HealthPayload("down", "absent");
        }
    }

    private static void postStop(String url, String token) {
        if (url == null || url.isBlank() || token == null || token.isBlank()) return;
        try {
            String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/stop"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The process kill below is the stop that still runs.
        }
    }

    private static void capLog(Path logFile) throws IOException {
        if (logFile == null || !Files.isRegularFile(logFile) || Files.size(logFile) <= LOG_CAP) return;
        byte[] all = Files.readAllBytes(logFile);
        int keep = 256 * 1024;
        int start = Math.max(0, all.length - keep);
        while (start < all.length && all[start] != '\n') start++;
        if (start < all.length) start++;
        Files.write(logFile, java.util.Arrays.copyOfRange(all, start, all.length));
    }

    /**
     * Owner decision, Dan 2026-11-28 (t1239u): {@code doctor --prune} removes only session records whose process is
     * gone and returns what it removed. A record whose pid is alive is never touched; spine sessions carry no pid
     * and are not pruned.
     */
    static List<String> prune(Path project) {
        List<String> removed = new java.util.ArrayList<>();
        Path root = project.toAbsolutePath().normalize();
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        Path session = sessionFile(project);
        if (host.isPresent() && !alive(host.get().pid())) {
            String why = " (live start host pid " + host.get().pid() + " gone)";
            if (deleteRecord(LiveHostFile.file(project))) removed.add(relative(root, LiveHostFile.file(project)) + why);
            if (deleteRecord(session)) removed.add(relative(root, session) + why);
        } else if (host.isEmpty() && Files.isRegularFile(session)) {
            if (deleteRecord(session)) removed.add(relative(root, session) + " (no live start host)");
        }
        Optional<LiveBridgeFile.Bridge> bridge = LiveBridgeFile.read(project);
        if (bridge.isPresent() && !alive(bridge.get().pid())) {
            Path file = LiveBridgeFile.file(project);
            if (deleteRecord(file)) removed.add(relative(root, file) + " (worker pid " + bridge.get().pid() + " gone)");
        }
        Path legacy = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        long legacyPid = pidIn(legacy);
        if (legacyPid > 0 && !alive(legacyPid) && deleteRecord(legacy)) {
            removed.add(relative(root, legacy) + " (pid " + legacyPid + " gone)");
        }
        return removed;
    }

    private static long pidIn(Path file) {
        if (!Files.isRegularFile(file)) return 0L;
        try {
            return JSON.readTree(file.toFile()).path("pid").asLong(0L);
        } catch (IOException | RuntimeException failure) {
            return 0L;
        }
    }

    private static boolean deleteRecord(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException failure) {
            return false;
        }
    }

    private static String relative(Path root, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute).toString().replace('\\', '/');
    }

    private static boolean alive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean legacyHostAlive(Path project) {
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonNode node = JSON.readTree(file.toFile());
            return alive(node.path("pid").asLong(0L));
        } catch (IOException failure) {
            return false;
        }
    }

    private static void destroyHost(Process process) {
        if (process == null || !process.isAlive()) return;
        ProcessTree.kill(process.toHandle());
    }

    private record CliHealth(String status, String headed, String pid, String page) {
        private static CliHealth absent() {
            return new CliHealth("absent", "absent", "absent", "absent");
        }
    }

    private static CliHealth readCliHealth(Path project) {
        Optional<LiveHostFile.Host> host = LiveHostFile.read(project);
        if (host.isPresent()) return healthOfHost(host.get());
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        if (!Files.isRegularFile(file)) return CliHealth.absent();
        try {
            JsonNode node = JSON.readTree(file.toFile());
            long pid = node.path("pid").asLong(0L);
            String headed = node.has("headed") ? Boolean.toString(node.path("headed").asBoolean(false)) : "absent";
            String pidText = pid > 0 ? Long.toString(pid) : "absent";
            boolean alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) return new CliHealth("down", headed, pidText, "absent");
            String url = node.path("url").asText("");
            String token = node.path("token").asText("");
            if (url.isBlank() || token.isBlank()) return new CliHealth("down", headed, pidText, "absent");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.endsWith("/") ? url + "health" : url + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new CliHealth("down", headed, pidText, "absent");
            JsonNode body = JSON.readTree(response.body() == null ? "" : response.body());
            if (!"ok".equals(body.path("status").asText(""))) return new CliHealth("down", headed, pidText, "absent");
            if (body.has("headed")) headed = Boolean.toString(body.path("headed").asBoolean(false));
            String page = "attached".equals(body.path("page").asText("")) ? "attached" : "absent";
            return new CliHealth("ok", headed, pidText, page);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new CliHealth("down", "absent", "absent", "absent");
        } catch (Exception failure) {
            return new CliHealth("down", "absent", "absent", "absent");
        }
    }

    private static CliHealth healthOfHost(LiveHostFile.Host host) {
        String headed = Boolean.toString(host.headed());
        String pidText = host.pid() > 0 ? Long.toString(host.pid()) : "absent";
        if (!alive(host.pid())) return new CliHealth("down", headed, pidText, "absent");
        HealthPayload payload = fetchHealth(host);
        if (payload == null || !"ok".equals(payload.status())) {
            return new CliHealth("down", headed, pidText, "absent");
        }
        String page = "attached".equals(payload.page()) ? "attached" : "absent";
        return new CliHealth("ok", headed, pidText, page);
    }

    private static String tail(Path logFile) {
        if (logFile == null || !Files.isRegularFile(logFile)) return "";
        try {
            String text = Files.readString(logFile);
            int start = Math.max(0, text.length() - 1200);
            return text.substring(start);
        } catch (IOException failure) {
            return "";
        }
    }

    private static void deleteSession(Path project) {
        try {
            Files.deleteIfExists(sessionFile(project));
        } catch (IOException ignored) {
            // A missing session is the clean doctor state.
        }
    }

    /** Blank when {@code start} has not published a session. */
    static String sessionId(Path project) {
        Map<String, Object> body = readSession(project);
        Object id = body.get("id");
        return id == null ? null : id.toString();
    }

    static int chatWait(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) runId = sessionId(parsed.project());
        if (runId == null || runId.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("start a session first", "chat_wait"));
            return 2;
        }
        long timeoutMillis = timeoutMillis(parsed, err);
        if (timeoutMillis < 0) return 2;
        String from = parsed.coordination().inboxFrom();
        if (from == null || from.isBlank()) from = "human";
        Map<String, String> pollArgs = new LinkedHashMap<>();
        if (!"any".equalsIgnoreCase(from) && !"all".equalsIgnoreCase(from)) pollArgs.put("from", from);
        String channel = flag(parsed, "--channel");
        if (channel != null && !channel.isBlank()) pollArgs.put("channel", channel);
        long startedNs = System.nanoTime();
        long deadline = startedNs + timeoutMillis * 1_000_000L;
        try {
            while (true) {
                AgentControl.Result polled = AgentControl.execute(
                        parsed.project(), runId, "chat-poll", pollArgs, List.of(), parsed.coordination().agentId());
                int count = count(polled.body());
                if (count > 0) {
                    out.println(JSON.writeValueAsString(polled.body()));
                    log(parsed.project(), runId, "chat_wait count=" + count);
                    return 0;
                }
                if (System.nanoTime() >= deadline) {
                    long elapsed = Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L);
                    out.println(tools.dscode.common.agentfast.ReplyFields.restamp(
                            JSON.writeValueAsString(polled.body()), elapsed));
                    err.println("error: chat_wait timed out\nfix: chat-send, then chat_wait again.\n");
                    log(parsed.project(), runId, "chat_wait timeout");
                    return 1;
                }
                Thread.sleep(50L);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("error: chat_wait was interrupted\nfix: retry chat_wait\n");
            return 1;
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: retry chat_wait\n");
            return 1;
        }
    }

    static int historySummary(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        Map<String, String> status = new LinkedHashMap<>();
        Path runs = parsed.project().resolve(".pickleball").resolve("runs");
        if (Files.isDirectory(runs)) {
            try (var children = Files.list(runs)) {
                for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                    Path record = child.resolve("record.json");
                    if (!Files.isRegularFile(record)) continue;
                    status.put(child.getFileName().toString(), statusOf(record));
                }
            } catch (IOException failure) {
                err.println("error: " + failure.getMessage() + "\nfix: check .pickleball/runs\n");
                return 1;
            }
        }
        if (StorePaths.ready(parsed.project())) {
            try {
                for (StoreHistory.Run run : StoreHistory.list(parsed.project())) {
                    if (run.id() != null) status.put(run.id(), run.status() == null ? "" : run.status());
                }
            } catch (RuntimeException | java.sql.SQLException failure) {
                err.println("error: " + failure.getMessage() + "\nfix: store run history could not be read\n");
                return 1;
            }
        }
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int other = 0;
        for (String value : status.values()) {
            String token = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
            switch (token) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                default -> other++;
            }
        }
        out.println("runs=" + status.size());
        out.println("passed=" + passed);
        out.println("failed=" + failed);
        out.println("skipped=" + skipped);
        out.println("other=" + other);
        log(parsed.project(), "history", "summary runs=" + status.size());
        return 0;
    }

    static int entityLookup(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String ref = flag(parsed, "--ref");
        if (ref == null || ref.isBlank()) ref = positional(parsed);
        if (ref == null || ref.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("entity-lookup requires --ref", "entity-lookup"));
            return 2;
        }
        String source = ref.trim();
        if (source.startsWith("<") && source.endsWith(">") && source.length() > 2) {
            source = source.substring(1, source.length() - 1).trim();
        }
        String previous = System.getProperty("pkb_store_project");
        System.setProperty("pkb_store_project", parsed.project().toAbsolutePath().normalize().toString());
        try {
            Object value = StoreEntities.lookup(source);
            if (value == null) {
                out.println("miss");
                err.println("error: no entity for " + source + "\nfix: store entity put, or pass entity:Type#id.field\n");
                log(parsed.project(), source, "entity-lookup miss");
                return 1;
            }
            String printed = value instanceof String text ? text : JSON.writeValueAsString(value);
            out.println(printed);
            log(parsed.project(), source, "entity-lookup hit");
            return 0;
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: retry entity-lookup\n");
            return 1;
        } finally {
            if (previous == null) System.clearProperty("pkb_store_project");
            else System.setProperty("pkb_store_project", previous);
        }
    }

    static int openRun(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("open-run requires --run-id", "open-run"));
            return 2;
        }
        String tab = flag(parsed, "--tab");
        if (tab == null || tab.isBlank()) tab = "explorer";
        tab = tab.trim().toLowerCase(java.util.Locale.ROOT);
        if (!tab.equals("explorer") && !tab.equals("tracking")) {
            err.print(WorkbenchCliHelp.usageError("tab is explorer or tracking", "open-run"));
            return 2;
        }
        Path record = parsed.project().resolve(".pickleball").resolve("runs").resolve(runId).resolve("record.json");
        if (!Files.isRegularFile(record)) {
            err.println("error: run " + runId + " has no record\nfix: pass a run id that already finished or is in progress\n");
            return 1;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("tab", tab);
        try {
            Path file = parsed.project().resolve(".pickleball").resolve("workbench").resolve("open-run.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: check .pickleball/workbench\n");
            return 1;
        }
        out.println("tab=" + tab);
        out.println("run-id=" + runId);
        log(parsed.project(), runId, "open-run tab=" + tab);
        return 0;
    }

    static int bridgeHealth(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String url = flag(parsed, "--url");
        if (url == null || url.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("bridge-health requires --url", "bridge-health"));
            return 2;
        }
        String base = url.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri;
        try {
            uri = URI.create(base + "/v1/health");
        } catch (IllegalArgumentException failure) {
            err.print(WorkbenchCliHelp.usageError("bridge-health --url is not a URL", "bridge-health"));
            return 2;
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(HEALTH_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();
            out.println("status=" + response.statusCode());
            out.println(responseBody);
            log(parsed.project(), "bridge", "health " + response.statusCode());
            return response.statusCode() == 200 ? 0 : 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("error: bridge-health was interrupted\nfix: retry bridge-health\n");
            return 1;
        } catch (IOException | RuntimeException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: the bridge did not answer GET /v1/health within 2s\n");
            log(parsed.project(), "bridge", "health down");
            return 1;
        }
    }

    private static Map<String, Object> readSession(Path project) {
        Path file = sessionFile(project);
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(Files.readString(file), Map.class);
            return body == null ? Map.of() : body;
        } catch (IOException | RuntimeException failure) {
            return Map.of();
        }
    }

    /** The run id given to start, or a generated {@code live<12 hex>} when none; null when the given id is unsafe. */
    static String liveId(String runId) {
        if (runId == null || runId.isBlank()) return "live" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String id = runId.trim();
        return id.matches("[A-Za-z0-9._-]{1,64}") ? id : null;
    }

    /** The id of the live start session in {@code project}, or null. */
    static String liveSessionId(Path project) {
        try {
            Path file = sessionFile(project);
            if (!Files.isRegularFile(file)) return null;
            String id = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("id").asText("");
            return id.isBlank() ? null : id;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static Path sessionFile(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        return root.resolve(".pickleball").resolve(SESSION_NAME);
    }

    private static String statusOf(Path record) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(Files.readString(record), Map.class);
            Object status = body == null ? null : body.get("status");
            return status == null ? "" : status.toString();
        } catch (IOException | RuntimeException failure) {
            return "";
        }
    }

    private static int count(Map<String, Object> body) {
        if (body == null) return 0;
        Object count = body.get("count");
        if (count instanceof Number number) return number.intValue();
        Object messages = body.get("messages");
        if (messages instanceof List<?> list) return list.size();
        return 0;
    }

    private static long timeoutMillis(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream err) {
        String raw = flag(parsed, "--timeout");
        if (raw == null || raw.isBlank()) return 30_000L;
        try {
            double seconds = Double.parseDouble(raw.trim());
            if (seconds < 0 || seconds > 120) {
                err.print(WorkbenchCliHelp.usageError("chat_wait --timeout is seconds from 0 to 120", "chat_wait"));
                return -1;
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException failure) {
            err.print(WorkbenchCliHelp.usageError("chat_wait --timeout is seconds from 0 to 120", "chat_wait"));
            return -1;
        }
    }

    private static Map<String, String> env(WorkbenchCommandLine.Parsed parsed) {
        Map<String, String> env = new LinkedHashMap<>();
        String[] args = parsed.forwarded();
        if (args == null) return env;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            String value = null;
            if (token.startsWith("--env=")) value = token.substring("--env=".length());
            else if ("--env".equals(token) && index + 1 < args.length) value = args[++index];
            if (value == null || value.isBlank()) continue;
            for (String piece : value.split(",")) {
                int equals = piece.indexOf('=');
                if (equals <= 0) continue;
                env.put(piece.substring(0, equals).trim(), piece.substring(equals + 1));
            }
        }
        return env;
    }

    private static String envText(Map<String, String> env) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (!text.isEmpty()) text.append(',');
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    private static String positional(WorkbenchCommandLine.Parsed parsed) {
        String[] args = parsed.forwarded();
        if (args == null) return null;
        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if (token == null || token.startsWith("-")) continue;
            if (token.contains("#") || token.startsWith("entity:") || token.startsWith("<entity:")) return token;
        }
        return null;
    }

    private static boolean hasToken(WorkbenchCommandLine.Parsed parsed, String name) {
        String[] args = parsed.forwarded();
        if (args == null || name == null) return false;
        for (String token : args) {
            if (name.equals(token) || (token != null && token.startsWith(name + "="))) return true;
        }
        return false;
    }

    private static String flag(WorkbenchCommandLine.Parsed parsed, String name) {
        String[] args = parsed.forwarded();
        if (args == null) return null;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
            if (name.equals(token)) {
                if (index + 1 < args.length && args[index + 1] != null && !args[index + 1].startsWith("--")) return args[index + 1];
                return "";
            }
        }
        return null;
    }

    private static void log(Path project, String entity, String message) {
        if (project == null) return;
        if ("off".equalsIgnoreCase(System.getProperty("pkb_consumer_log", "on"))) return;
        try {
            Path file = project.toAbsolutePath().normalize()
                    .resolve(".pickleball").resolve("logs").resolve("consumer-cli.log");
            Files.createDirectories(file.getParent());
            String line = Instant.now() + "\t" + (entity == null ? "-" : entity) + "\t" + message + "\n";
            if (Files.isRegularFile(file) && Files.size(file) > LOG_CAP) {
                Files.writeString(file, line, StandardCharsets.UTF_8);
            } else {
                Files.writeString(
                        file,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException ignored) {
            // A log line must not change the command result.
        }
    }
}
