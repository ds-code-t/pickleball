package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.RunView;
import tools.dscode.control.protocol.WindowDriver;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One-shot consumer-JVM client for a long-lived headless Workbench CLI session.
 * Maven exec always exits; the controller {@code session} process stays up.
 */
public final class WorkbenchSessionCommands {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);

    private WorkbenchSessionCommands() {
    }

    record SessionLaunch(
            Path project,
            String tags,
            String name,
            String example,
            Path logFile,
            Path sessionFile,
            String runId,
            String agentId
    ) {
    }

    @FunctionalInterface
    interface DetachedStarter {
        Process start(SessionLaunch launch) throws IOException;
    }

    @FunctionalInterface
    interface UiStarter {
        Process start(Path project, String runId, String agentId) throws IOException;
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, WorkbenchSessionCommands::startControllerSession, defaultHttp());
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter
    ) {
        return run(args, out, err, starter, defaultHttp());
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter,
            HttpClient http
    ) {
        return run(args, out, err, starter, http, WorkbenchSessionCommands::startUi);
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter,
            UiStarter uiStarter
    ) {
        return run(args, out, err, starter, defaultHttp(), uiStarter);
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter,
            HttpClient http,
            UiStarter uiStarter
    ) {
        WorkbenchCommandLine.Parsed parsed;
        try {
            parsed = WorkbenchCommandLine.parse(args);
        } catch (IllegalArgumentException failure) {
            err.println(failure.getMessage());
            return 2;
        }
        SessionFlags flags = SessionFlags.parse(args);
        try {
            return switch (parsed.command()) {
                case "isolate", "session-start" -> sessionStart(parsed, out, err, starter, http);
                case "execute-step" -> executeStep(parsed, flags, out, err, http);
                case "insert-step", "update-step" -> textCommand(parsed, flags, out, err, http);
                case "open-scenario" -> openScenario(parsed, flags, out, err, http);
                case "example" -> example(parsed, flags, out, err, http);
                case "diagnostic-run" -> diagnosticRun(parsed, flags, out, err, http);
                case "play", "from-here", "pause", "save", "refresh",
                     "session-sync", "worker-start", "worker-restart", "worker-stop" ->
                        postCommand(parsed, flags, out, err, http);
                case "status" -> status(parsed, flags, out, err, http);
                case "events" -> events(parsed, out, err, http);
                case "stop", "kill" -> stop(parsed, out, err, http);
                case "open-window" -> openWindow(parsed, out, err, http, uiStarter);
                case "close-window" -> closeWindow(parsed, out, err, http);
                case "show-run" -> showRun(parsed, out, err, http);
                default -> {
                    err.println("Unknown Workbench session command: " + parsed.command());
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("Workbench " + parsed.command() + " failed: " + failure.getMessage());
            return 1;
        }
    }

    static Process startControllerSession(SessionLaunch launch) throws IOException {
        Path controllerJar = PickleballWorkbenchLauncher.extractEmbeddedPayload(launch.project());
        List<String> forwarded = new ArrayList<>();
        forwarded.add("session");
        forwarded.add(launch.project().toString());
        if (launch.tags() != null && !launch.tags().isBlank()) {
            forwarded.add("--tags");
            forwarded.add(launch.tags());
        }
        if (launch.name() != null && !launch.name().isBlank()) {
            forwarded.add("--name");
            forwarded.add(launch.name());
        }
        if (launch.example() != null && !launch.example().isBlank()) {
            forwarded.add("--example");
            forwarded.add(launch.example());
        }
        if (launch.sessionFile() != null) {
            forwarded.add("--session-file");
            forwarded.add(launch.sessionFile().toString());
        }
        if (launch.runId() != null && !launch.runId().isBlank()) {
            forwarded.add("--run-id");
            forwarded.add(launch.runId());
        }
        if (launch.agentId() != null && !launch.agentId().isBlank()) {
            forwarded.add("--agent-id");
            forwarded.add(launch.agentId());
        }
        return startDetached(launch.project(), PickleballWorkbenchLauncher.command(
                controllerJar, forwarded.toArray(String[]::new)
        ), launch.logFile());
    }

    static Process startDetached(Path project, List<String> command, Path logFile) throws IOException {
        Files.createDirectories(logFile.getParent());
        if (!Files.exists(logFile)) Files.createFile(logFile);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectInput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    static Process startUi(Path project, String runId, String agentId) throws IOException {
        Path controllerJar = PickleballWorkbenchLauncher.extractEmbeddedPayload(project);
        List<String> forwarded = new ArrayList<>();
        forwarded.add("ui");
        forwarded.add(project.toString());
        if (runId != null && !runId.isBlank()) {
            forwarded.add("--run-id");
            forwarded.add(runId);
        }
        if (agentId != null && !agentId.isBlank()) {
            forwarded.add("--agent-id");
            forwarded.add(agentId);
        }
        Path logFile = PickleballLocalLayout.workbenchStateRoot(project).resolve("window.log");
        return startDetached(project, PickleballWorkbenchLauncher.command(
                controllerJar, forwarded.toArray(String[]::new)
        ), logFile);
    }

    private static int openWindow(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http,
            UiStarter uiStarter
    ) {
        return presentRun(parsed, out, err, http, uiStarter, true);
    }

    private static int showRun(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        return presentRun(parsed, out, err, http, null, false);
    }

    private static int presentRun(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http,
            UiStarter uiStarter,
            boolean openIfClosed
    ) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.println("Usage: " + parsed.command() + " --run-id=<id>");
            return 2;
        }
        RunView.Loaded loaded;
        try {
            loaded = RunView.load(parsed.project(), runId);
        } catch (RuntimeException failure) {
            err.println(failure.getMessage());
            return 1;
        }
        out.println("run-id=" + loaded.runId());
        out.println("run-record=" + loaded.recordFile());
        out.println(loaded.recordText());
        for (Path log : loaded.logs()) out.println("log=" + log);
        for (Path report : loaded.reports()) out.println("report=" + report);
        for (Path config : loaded.config()) out.println("config=" + config);
        out.println("Opening the window loads the run you already have. It does not start a test.");

        Optional<SessionState> window = openWindow(parsed.project(), http);
        boolean pointTheWindow = openIfClosed || window.isPresent();
        WindowDriver.Decision decision = null;
        if (pointTheWindow) {
            boolean claim = openIfClosed && parsed.coordination().agentId() != null;
            decision = WindowDriver.show(parsed.project(), runId, parsed.coordination().agentId(), claim);
            out.println("live-run=" + (decision.state().liveRunId() == null ? "" : decision.state().liveRunId()));
            out.println("viewed-run=" + loaded.runId());
            if (decision.readOnly()
                    && parsed.coordination().agentId() != null
                    && decision.state().driverAgent() != null
                    && !decision.state().driverAgent().equals(parsed.coordination().agentId())) {
                out.println("A second agent does not become a second driver of the live session "
                        + decision.state().liveRunId() + ".");
            }
        }
        if (window.isEmpty() && openIfClosed) {
            if (uiStarter == null) {
                err.println("No Workbench window is open. open-window starts it; show-run does not.");
                return 1;
            }
            Path logFile = PickleballLocalLayout.workbenchStateRoot(parsed.project()).resolve("window.log");
            Process process;
            try {
                process = uiStarter.start(parsed.project(), runId, parsed.coordination().agentId());
            } catch (IOException failure) {
                err.println("Could not open the Workbench window: " + failure.getMessage());
                return 1;
            }
            long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && openWindow(parsed.project(), http).isEmpty()) {
                if (process != null && !process.isAlive() && openWindow(parsed.project(), http).isEmpty()) {
                    err.println("Workbench window exited before it could show the run. See " + logFile);
                    return 1;
                }
                sleep(POLL);
            }
            window = openWindow(parsed.project(), http);
            if (window.isEmpty()) {
                err.println("Workbench window did not become healthy within "
                        + HEALTH_TIMEOUT.toSeconds() + "s. See " + logFile);
                return 1;
            }
        }
        if (window.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", "show-run");
            body.put("runId", loaded.runId());
            if (parsed.coordination().agentId() != null) body.put("agent", parsed.coordination().agentId());
            boolean driving = decision != null && decision.driving();
            body.put("claim", Boolean.toString(openIfClosed && driving));
            try {
                postJson(http, window.get(), "/commands", body);
            } catch (RuntimeException failure) {
                err.println("Workbench window did not show the run: " + failure.getMessage());
                return 1;
            }
        }
        String driver = decision != null && decision.state().driverAgent() != null
                ? decision.state().driverAgent()
                : "";
        out.println("ACK WINDOW " + (openIfClosed ? "opened" : "shown")
                + " run-id=" + loaded.runId()
                + (decision != null && decision.driving() ? " driver=" + driver : " read-only"));
        return 0;
    }

    private static int closeWindow(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        WindowDriver.closeWindow(parsed.project());
        Optional<SessionState> window = openWindow(parsed.project(), http);
        if (window.isPresent()) {
            try {
                postJson(http, window.get(), "/commands", Map.of("op", "close-window"));
            } catch (RuntimeException failure) {
                err.println("Workbench window did not close: " + failure.getMessage());
                return 1;
            }
        }
        out.println("ACK WINDOW closed");
        out.println("The run directory and the short log are unchanged. A headless run keeps going.");
        return 0;
    }

    private static Optional<SessionState> openWindow(Path project, HttpClient http) {
        Optional<SessionState> attached = readHealthyFile(PickleballLocalLayout.attachFile(project), project, http);
        if (attached.isPresent() && "ui-attach".equals(attached.get().mode())) return attached;
        return Optional.empty();
    }

    private static Integer refusedDriver(WorkbenchCommandLine.Parsed parsed, PrintStream err) {
        if (parsed.coordination().runId() != null && !parsed.coordination().runId().isBlank()) return null;
        Optional<String> refused = WindowDriver.refuseSecondDriver(
                parsed.project(), parsed.coordination().agentId()
        );
        if (refused.isPresent()) {
            err.println(refused.get());
            return 1;
        }
        WindowDriver.claimVacant(parsed.project(), parsed.coordination().agentId());
        return null;
    }

    private static int sessionStart(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter,
            HttpClient http
    ) {
        Path project = parsed.project();
        AgentCoordination.Run run = AgentCoordination.begin(
                project,
                AgentCoordination.Request.of(
                        parsed.coordination().runId(),
                        parsed.coordination().agentId(),
                        parsed.coordination().group(),
                        parsed.coordination().sequence(),
                        parsed.coordination().who(),
                        parsed.coordination().why(),
                        "isolate"
                )
        );
        Optional<SessionState> existing = readHealthy(project, http);
        if (existing.isPresent()) {
            SessionState state = existing.get();
            out.println("ACK SESSION already-running pid=" + state.pid() + " url=" + state.url());
            out.println("Only one agent drives an open Workbench window. This run stays headless on run-id="
                    + run.runId() + ".");
            return 0;
        }

        Path logFile = run.sessionDirectory().resolve("session.log");
        Path sessionFile = run.sessionStateFile();
        Process process;
        try {
            process = starter.start(new SessionLaunch(
                    project,
                    parsed.tags(),
                    parsed.name(),
                    parsed.example(),
                    logFile,
                    sessionFile,
                    run.runId(),
                    run.agentId()
            ));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start Workbench session: " + failure.getMessage(), failure);
        }

        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SessionState> ready = readHealthyFile(sessionFile, project, http);
            if (ready.isPresent()) {
                SessionState state = ready.get();
                out.println("ACK SESSION pid=" + state.pid() + " url=" + state.url());
                return 0;
            }
            if (process != null && !process.isAlive()) {
                Optional<SessionState> afterExit = readHealthyFile(sessionFile, project, http);
                if (afterExit.isPresent()) {
                    SessionState state = afterExit.get();
                    out.println("ACK SESSION pid=" + state.pid() + " url=" + state.url());
                    return 0;
                }
                err.println("Workbench session process exited before becoming healthy. See " + logFile);
                return 1;
            }
            sleep(POLL);
        }
        err.println("Workbench session did not become healthy within " + HEALTH_TIMEOUT.toSeconds() + "s. See " + logFile);
        return 1;
    }

    private static int executeStep(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        SessionState state = requireSession(parsed, http);
        String text = flags.text;
        if (text == null || text.isBlank()) {
            err.println("Usage: execute-step <gherkin> or execute-step --text=<gherkin> [--ack-only]");
            return 2;
        }
        Map<String, Object> body = commandBody(parsed, flags);
        body.put("text", text);
        return postAndMaybeWait(state, flags, body, out, err, http);
    }

    private static int textCommand(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        if (flags.text == null || flags.text.isBlank()) {
            err.println("Usage: " + parsed.command() + " --text=<gherkin>");
            return 2;
        }
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        SessionState state = requireSession(parsed, http);
        return postAndMaybeWait(state, flags, commandBody(parsed, flags), out, err, http);
    }

    private static int openScenario(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        boolean featureBlank = flags.feature == null || flags.feature.isBlank();
        boolean nameBlank = parsed.name() == null || parsed.name().isBlank();
        if (featureBlank && nameBlank) {
            err.println("Usage: open-scenario --feature=<path-or-name> --name=<scenario> [--example=<rows>]");
            return 2;
        }
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        SessionState state = requireSession(parsed, http);
        return postAndMaybeWait(state, flags, commandBody(parsed, flags), out, err, http);
    }

    private static int example(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        String selector = parsed.example();
        if (selector == null || selector.isBlank()) selector = flags.text;
        if (selector == null || selector.isBlank()) {
            err.println("Usage: example --example=<rows>");
            return 2;
        }
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        SessionState state = requireSession(parsed, http);
        Map<String, Object> body = commandBody(parsed, flags);
        body.put("example", selector);
        return postAndMaybeWait(state, flags, body, out, err, http);
    }

    private static int diagnosticRun(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        if (flags.run == null || flags.run.isBlank()) {
            err.println("Usage: diagnostic-run --run=<id>");
            return 2;
        }
        SessionState state = requireSession(parsed, http);
        return postAndMaybeWait(state, flags, commandBody(parsed, flags), out, err, http);
    }

    private static int postCommand(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        SessionState state = requireSession(parsed, http);
        return postAndMaybeWait(state, flags, commandBody(parsed, flags), out, err, http);
    }

    private static Map<String, Object> commandBody(WorkbenchCommandLine.Parsed parsed, SessionFlags flags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", parsed.command());
        if (flags.text != null && !flags.text.isBlank()) body.put("text", flags.text);
        if (flags.id != null && !flags.id.isBlank()) body.put("id", flags.id);
        if (flags.feature != null && !flags.feature.isBlank()) body.put("feature", flags.feature);
        if (parsed.name() != null && !parsed.name().isBlank()) body.put("name", parsed.name());
        if (parsed.example() != null && !parsed.example().isBlank()) body.put("example", parsed.example());
        if (flags.run != null && !flags.run.isBlank()) body.put("run", flags.run);
        if (flags.fromHere != null && !flags.fromHere.isBlank()) body.put("fromHere", flags.fromHere);
        if (parsed.coordination().agentId() != null) body.put("agent", parsed.coordination().agentId());
        if (parsed.coordination().runId() != null) body.put("runId", parsed.coordination().runId());
        return body;
    }

    private static int postAndMaybeWait(
            SessionState state,
            SessionFlags flags,
            Map<String, Object> body,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        JsonNode ack = postJson(http, state, "/commands", body);
        String id = textOr(ack, "id", "");
        String status = textOr(ack, "status", "QUEUED");
        out.println("ACK " + id + " " + status);
        if (flags.ackOnly || !flags.wait) return 0;
        return waitForDone(http, state, id, out, err);
    }

    private static int status(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Optional<SessionState> state = resolveSession(parsed, http);
        if (state.isEmpty()) {
            err.println("No healthy Workbench session. Run isolate / session-start, or use the window that is already open.");
            return 1;
        }
        String id = flags.id;
        if (id == null || id.isBlank()) {
            SessionState session = state.get();
            out.println("SESSION pid=" + session.pid() + " url=" + session.url() + " mode=" + session.mode());
            return 0;
        }
        try {
            JsonNode view = getJson(http, state.get(), "/commands/" + id);
            String status = textOr(view, "status", "");
            out.println(id + " " + status);
            if (view.has("result")) out.println(view.get("result").toString());
            return terminalExit(status);
        } catch (RuntimeException failure) {
            err.println("DONE " + id + " TIMEOUT");
            return 1;
        }
    }

    private static int events(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        SessionState state = requireSession(parsed, http);
        try {
            JsonNode events = postJson(http, state, "/tools/workbench_events", Map.of());
            out.println(events.toString());
            return 0;
        } catch (RuntimeException failure) {
            err.println("Workbench events failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int stop(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        Optional<SessionState> state = resolveSession(parsed, http);
        if (state.isEmpty()) {
            out.println("ACK SESSION already-stopped");
            finishRequestedRun(parsed, out);
            return 0;
        }
        SessionState session = state.get();
        boolean watched = "ui-attach".equals(session.mode());
        try {
            postJson(http, session, "/commands", Map.of("op", "stop"));
        } catch (RuntimeException failure) {
            if (watched) {
                err.println("Workbench stop failed: " + failure.getMessage());
                return 1;
            }
        }
        if (watched) {
            out.println("ACK SESSION playback-stopped pid=" + session.pid());
            finishRequestedRun(parsed, out);
            return 0;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (readHealthyFile(session.stateFile(), parsed.project(), http).isEmpty() && !pidAlive(session.pid())) {
                out.println("ACK SESSION stopped pid=" + session.pid());
                finishRequestedRun(parsed, out);
                return 0;
            }
            sleep(POLL);
        }
        ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroy);
        sleep(Duration.ofMillis(400));
        if (pidAlive(session.pid())) ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroyForcibly);
        try {
            Files.deleteIfExists(session.stateFile());
        } catch (IOException ignored) {
            // Disposable session state.
        }
        out.println("ACK SESSION killed pid=" + session.pid());
        finishRequestedRun(parsed, out);
        return 0;
    }

    private static void finishRequestedRun(WorkbenchCommandLine.Parsed parsed, PrintStream out) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) return;
        AgentCoordination.finish(
                parsed.project(),
                runId,
                parsed.coordination().learned(),
                "STOPPED",
                Instant.now(),
                out
        );
    }

    private static int waitForDone(
            HttpClient http,
            SessionState state,
            String id,
            PrintStream out,
            PrintStream err
    ) {
        String last = "";
        while (true) {
            JsonNode view;
            try {
                if (!pidAlive(state.pid()) && readHealthyFile(state.stateFile(), state.project(), http).isEmpty()) {
                    err.println("DONE " + id + " TIMEOUT");
                    return 1;
                }
                view = getJson(http, state, "/commands/" + id);
            } catch (RuntimeException failure) {
                err.println("DONE " + id + " TIMEOUT");
                return 1;
            }
            String status = textOr(view, "status", "");
            if ("STILL_WORKING".equals(status) || "RUNNING".equals(status) || "QUEUED".equals(status)) {
                if (!status.equals(last)) {
                    out.println("STILL_WORKING " + id);
                    last = status;
                }
                sleep(POLL);
                continue;
            }
            if ("TIMEOUT".equals(status)) {
                err.println("DONE " + id + " TIMEOUT");
                return 1;
            }
            out.println("DONE " + id + " " + status);
            if (view.has("result")) out.println(view.get("result").toString());
            return terminalExit(status);
        }
    }

    private static int terminalExit(String status) {
        return "SUCCESS".equals(status) ? 0 : 1;
    }

    private static SessionState requireSession(WorkbenchCommandLine.Parsed parsed, HttpClient http) {
        return resolveSession(parsed, http).orElseThrow(() -> new IllegalStateException(
                "No healthy Workbench session. Run isolate / session-start, or use the window that is already open."
        ));
    }

    private static Optional<SessionState> resolveSession(WorkbenchCommandLine.Parsed parsed, HttpClient http) {
        String runId = parsed.coordination().runId();
        if (runId != null && !runId.isBlank()) {
            Path file = AgentCoordination.runDirectory(parsed.project(), runId)
                    .resolve("session")
                    .resolve("cli-session.json");
            return readHealthyFile(file, parsed.project(), http);
        }
        return readHealthy(parsed.project(), http);
    }

    static Optional<SessionState> readHealthy(Path project, HttpClient http) {
        Optional<SessionState> attached = readHealthyFile(PickleballLocalLayout.attachFile(project), project, http);
        if (attached.isPresent() && "ui-attach".equals(attached.get().mode())) {
            return attached;
        }
        return readHealthyFile(sessionFile(project), project, http);
    }

    private static Optional<SessionState> readHealthyFile(Path file, Path project, HttpClient http) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(file.toFile());
            SessionState state = SessionState.from(project, file, root);
            if (!pidAlive(state.pid())) return Optional.empty();
            JsonNode health = getJson(http, state, "/health");
            if (!"ok".equals(textOr(health, "status", ""))) return Optional.empty();
            return Optional.of(state);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static Path sessionFile(Path project) {
        return PickleballLocalLayout.cliSessionState(project);
    }

    private static boolean pidAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static JsonNode postJson(HttpClient http, SessionState state, String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(state.url() + path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + state.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
            }
            return JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Workbench session.", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static JsonNode getJson(HttpClient http, SessionState state, String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(state.url() + path))
                    .timeout(HTTP_TIMEOUT)
                    .GET();
            if (!"/health".equals(path)) {
                builder.header("Authorization", "Bearer " + state.token());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
            }
            return JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Workbench session.", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Workbench session.");
        }
    }

    private static HttpClient defaultHttp() {
        return HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    record SessionState(Path project, String url, String token, long pid, String mode, Path stateFile) {
        static SessionState from(Path project, Path stateFile, JsonNode root) {
            return new SessionState(
                    project.toAbsolutePath().normalize(),
                    root.path("url").asText(""),
                    root.path("token").asText(""),
                    root.path("pid").asLong(0L),
                    root.path("mode").asText(""),
                    stateFile
            );
        }
    }

    static final class SessionFlags {
        final String text;
        final String id;
        final String feature;
        final String run;
        final String fromHere;
        final boolean ackOnly;
        final boolean wait;

        private SessionFlags(
                String text,
                String id,
                String feature,
                String run,
                String fromHere,
                boolean ackOnly,
                boolean wait
        ) {
            this.text = text;
            this.id = id;
            this.feature = feature;
            this.run = run;
            this.fromHere = fromHere;
            this.ackOnly = ackOnly;
            this.wait = wait;
        }

        static SessionFlags parse(String[] args) {
            String text = null;
            String id = null;
            String feature = null;
            String run = null;
            String fromHere = null;
            boolean ackOnly = false;
            boolean wait = true;
            List<String> words = new ArrayList<>();
            if (args == null) return new SessionFlags(null, null, null, null, null, false, true);
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (token == null) continue;
                if (token.startsWith("--text=")) {
                    text = token.substring("--text=".length());
                    continue;
                }
                if ("--text".equals(token) && index + 1 < args.length) {
                    text = args[++index];
                    continue;
                }
                if (token.startsWith("--id=")) {
                    id = token.substring("--id=".length());
                    continue;
                }
                if ("--id".equals(token) && index + 1 < args.length) {
                    id = args[++index];
                    continue;
                }
                if ("--ack-only".equals(token)) {
                    ackOnly = true;
                    wait = false;
                    continue;
                }
                if ("--wait".equals(token)) {
                    wait = true;
                    continue;
                }
                if ("--no-wait".equals(token)) {
                    wait = false;
                    continue;
                }
                if (token.startsWith("--feature=")) {
                    feature = token.substring("--feature=".length());
                    continue;
                }
                if ("--feature".equals(token) && index + 1 < args.length) {
                    feature = args[++index];
                    continue;
                }
                if (token.startsWith("--run=")) {
                    run = token.substring("--run=".length());
                    continue;
                }
                if ("--run".equals(token) && index + 1 < args.length) {
                    run = args[++index];
                    continue;
                }
                if (token.startsWith("--from-here=")) {
                    fromHere = token.substring("--from-here=".length());
                    continue;
                }
                if ("--from-here".equals(token) && index + 1 < args.length) {
                    fromHere = args[++index];
                    continue;
                }
                if (token.startsWith("--tags=") || token.startsWith("--name=")) continue;
                if (("--tags".equals(token) || "--name".equals(token)) && index + 1 < args.length) {
                    index++;
                    continue;
                }
                if (token.startsWith("--example=")) {
                    while (index + 1 < args.length && isSelectorTail(args[index + 1])) {
                        index++;
                    }
                    continue;
                }
                if ("--example".equals(token) && index + 1 < args.length) {
                    index++;
                    while (index + 1 < args.length && isSelectorTail(args[index + 1])) {
                        index++;
                    }
                    continue;
                }
                if (token.startsWith("-")) continue;
                if (looksLikeProject(token)) continue;
                words.add(token);
            }
            if (text == null && !words.isEmpty()) {
                if ("status".equals(args[0]) && words.size() == 1) {
                    id = words.getFirst();
                } else {
                    text = String.join(" ", words);
                    if ("status".equals(args[0]) && id == null && words.size() == 1) id = words.getFirst();
                }
            }
            return new SessionFlags(text, id, feature, run, fromHere, ackOnly, wait);
        }

        private static boolean isSelectorTail(String token) {
            return token != null && !token.startsWith("-") && !looksLikeProject(token);
        }

        private static boolean looksLikeProject(String token) {
            if (token == null || token.isBlank()) return false;
            if (".".equals(token) || "..".equals(token)) return true;
            if (token.contains("/") || token.contains("\\")) return true;
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-d")) return true;
            return Files.isDirectory(Path.of(token));
        }
    }
}
