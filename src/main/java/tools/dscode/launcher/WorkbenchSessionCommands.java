package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.runtime.ProcessTrees;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.RunView;
import tools.dscode.control.protocol.SleepGapMonitor;
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
        SessionFlags flags;
        try {
            flags = SessionFlags.parse(args);
        } catch (IllegalArgumentException failure) {
            err.println(failure.getMessage());
            return 2;
        }
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
                case "stop", "kill" -> stop(parsed, flags, out, err, http);
                case "open-window" -> openWindow(parsed, out, err, http, uiStarter);
                case "close-window" -> closeWindow(parsed, out, err, http);
                case "show-run" -> showRun(parsed, out, err, http);
                default -> {
                    err.println("Unknown Workbench session command: " + parsed.command());
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("Workbench " + parsed.command() + " failed: " + WorkbenchCliErrors.explain(failure));
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
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
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
                ),
                Instant.now(),
                null,
                false
        );
        Optional<SessionState> existing = readHealthy(project, http);
        if (existing.isPresent()) {
            SessionState state = existing.get();
            AgentCoordination.logStart(project, run, "isolate");
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
            String reason = "Session start failed: " + WorkbenchCliErrors.explain(failure);
            finishReason(project, run.runId(), reason, out);
            throw new IllegalStateException(reason, failure);
        }

        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SessionState> ready = readHealthyFile(sessionFile, project, http);
            if (ready.isPresent()) {
                SessionState state = ready.get();
                AgentCoordination.logStart(project, run, "isolate");
                out.println("ACK SESSION pid=" + state.pid() + " url=" + state.url());
                return 0;
            }
            if (process != null && !process.isAlive()) {
                Optional<SessionState> afterExit = readHealthyFile(sessionFile, project, http);
                if (afterExit.isPresent()) {
                    SessionState state = afterExit.get();
                    AgentCoordination.logStart(project, run, "isolate");
                    out.println("ACK SESSION pid=" + state.pid() + " url=" + state.url());
                    return 0;
                }
                err.println("Workbench session process exited before becoming healthy. See " + logFile);
                finishReason(project, run.runId(), "Session start failed: the process exited before it was healthy.", out);
                return 1;
            }
            sleep(POLL);
        }
        err.println("Workbench session did not become healthy within " + HEALTH_TIMEOUT.toSeconds() + "s. See " + logFile);
        finishReason(project, run.runId(), "Session start failed: the session did not become healthy.", out);
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
        return postAndMaybeWait(parsed, state, flags, body, out, err, http);
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
        return postAndMaybeWait(parsed, state, flags, commandBody(parsed, flags), out, err, http);
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
        return postAndMaybeWait(parsed, state, flags, commandBody(parsed, flags), out, err, http);
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
        return postAndMaybeWait(parsed, state, flags, body, out, err, http);
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
        return postAndMaybeWait(parsed, state, flags, commandBody(parsed, flags), out, err, http);
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
        return postAndMaybeWait(parsed, state, flags, commandBody(parsed, flags), out, err, http);
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
        if (flags.stopAfter != null && !flags.stopAfter.isBlank()) body.put("stopAfter", flags.stopAfter);
        if (parsed.coordination().agentId() != null) body.put("agent", parsed.coordination().agentId());
        if (parsed.coordination().runId() != null) body.put("runId", parsed.coordination().runId());
        return body;
    }

    private static int postAndMaybeWait(
            WorkbenchCommandLine.Parsed parsed,
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
        return waitForDone(parsed, http, state, flags, id, out, err);
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
            err.println(unhealthyMessage(parsed.project(), parsed.coordination().runId()));
            return 1;
        }
        String id = flags.id;
        if (id == null || id.isBlank()) {
            SessionState session = state.get();
            out.println("SESSION pid=" + session.pid() + " url=" + session.url() + " mode=" + session.mode());
            printTiming(out, parsed, http, session);
            return 0;
        }
        try {
            JsonNode view = getJson(http, state.get(), "/commands/" + id);
            String status = textOr(view, "status", "");
            out.println(id + " " + status);
            if (view.has("result")) out.println(view.get("result").toString());
            printTiming(out, parsed, http, state.get());
            return terminalExit(status);
        } catch (RuntimeException failure) {
            err.println("DONE " + id + " TIMEOUT");
            recordRunStatus(parsed, out, "TIMEOUT");
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
            out.println(formatReport(timingOf(events, runStarted(parsed), Instant.now()), sleepGaps(http, state)));
            return 0;
        } catch (RuntimeException failure) {
            err.println("Workbench events failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int stop(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Integer refused = refusedDriver(parsed, err);
        if (refused != null) return refused;
        Optional<SessionState> state = resolveSession(parsed, http);
        if (state.isEmpty()) {
            out.println("ACK SESSION already-stopped");
            finishRequestedRun(parsed, out, "STOPPED");
            return 0;
        }
        SessionState session = state.get();
        boolean watched = "ui-attach".equals(session.mode());
        if (flags.force) {
            killTree(session.pid());
            deleteSessionFile(session.stateFile());
            out.println("ACK SESSION killed pid=" + session.pid());
            finishRequestedRun(parsed, out, "STOPPED");
            return 0;
        }
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
            finishRequestedRun(parsed, out, "STOPPED");
            return 0;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (readHealthyFile(session.stateFile(), parsed.project(), http).isEmpty() && !pidAlive(session.pid())) {
                out.println("ACK SESSION stopped pid=" + session.pid());
                finishRequestedRun(parsed, out, "STOPPED");
                return 0;
            }
            sleep(POLL);
        }
        ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroy);
        sleep(Duration.ofMillis(400));
        if (pidAlive(session.pid())) ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroyForcibly);
        deleteSessionFile(session.stateFile());
        out.println("ACK SESSION killed pid=" + session.pid());
        finishRequestedRun(parsed, out, "STOPPED");
        return 0;
    }

    private static void killTree(long pid) {
        ProcessHandle.of(pid).ifPresent(ProcessTrees::killTree);
    }

    private static void deleteSessionFile(Path stateFile) {
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException ignored) {
            // Disposable session state.
        }
    }

    private static void finishRequestedRun(WorkbenchCommandLine.Parsed parsed, PrintStream out, String status) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) return;
        AgentCoordination.finish(
                parsed.project(),
                runId,
                parsed.coordination().learned(),
                status,
                Instant.now(),
                out
        );
    }

    private static int waitForDone(
            WorkbenchCommandLine.Parsed parsed,
            HttpClient http,
            SessionState state,
            SessionFlags flags,
            String id,
            PrintStream out,
            PrintStream err
    ) {
        String last = "";
        int transientFailures = 0;
        long deadline = waitDeadline(parsed, flags);
        while (true) {
            if (System.nanoTime() > deadline) {
                return timedOut(parsed, id, out, err, true);
            }
            JsonNode view;
            try {
                if (!pidAlive(state.pid()) && readHealthyFile(state.stateFile(), state.project(), http).isEmpty()) {
                    return timedOut(parsed, id, out, err, false);
                }
                view = getJson(http, state, "/commands/" + id);
                transientFailures = 0;
            } catch (RuntimeException failure) {
                if (transientHttp(failure) && transientFailures < TRANSIENT_HTTP_RETRIES) {
                    transientFailures++;
                    sleep(POLL);
                    continue;
                }
                return timedOut(parsed, id, out, err, false);
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
                return timedOut(parsed, id, out, err, true);
            }
            out.println("DONE " + id + " " + status);
            if (view.has("result")) out.println(view.get("result").toString());
            return terminalExit(status);
        }
    }

    private static final int TRANSIENT_HTTP_RETRIES = 2;

    private static long waitDeadline(WorkbenchCommandLine.Parsed parsed, SessionFlags flags) {
        long now = System.nanoTime();
        long deadline = Long.MAX_VALUE;
        if (flags.stepTimeoutSeconds != null) {
            deadline = now + Duration.ofSeconds(flags.stepTimeoutSeconds).toNanos();
        }
        if (flags.runTimeoutSeconds != null) {
            long runDeadline = now + Duration.ofSeconds(flags.runTimeoutSeconds).toNanos();
            Instant started = runStarted(parsed);
            if (started != null) {
                long remainingMillis = Duration.between(Instant.now(), started.plusSeconds(flags.runTimeoutSeconds)).toMillis();
                runDeadline = remainingMillis <= 0 ? now - 1 : now + Duration.ofMillis(remainingMillis).toNanos();
            }
            deadline = Math.min(deadline, runDeadline);
        }
        return deadline;
    }

    private static boolean transientHttp(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.startsWith("HTTP 502") || message.startsWith("HTTP 503") || message.startsWith("HTTP 504")) {
            return true;
        }
        if (failure.getCause() instanceof IOException) return true;
        return message.contains("Connection") || message.contains("timed out") || message.contains("reset");
    }

    private static int timedOut(
            WorkbenchCommandLine.Parsed parsed,
            String id,
            PrintStream out,
            PrintStream err,
            boolean stepStillRunning
    ) {
        err.println("DONE " + id + " TIMEOUT");
        if (stepStillRunning) {
            err.println("The step is still running. Use stop --force.");
        }
        recordRunStatus(parsed, out, "TIMEOUT");
        return 1;
    }

    private static void recordRunStatus(WorkbenchCommandLine.Parsed parsed, PrintStream out, String status) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) return;
        try {
            AgentCoordination.finish(
                    parsed.project(),
                    runId,
                    parsed.coordination().learned(),
                    status,
                    Instant.now(),
                    out
            );
        } catch (RuntimeException ignored) {
            // A missing run record must not hide the TIMEOUT line.
        }
    }

    private static int terminalExit(String status) {
        return "SUCCESS".equals(status) ? 0 : 1;
    }

    private static SessionState requireSession(WorkbenchCommandLine.Parsed parsed, HttpClient http) {
        return resolveSession(parsed, http).orElseThrow(() -> new IllegalStateException(
                unhealthyMessage(parsed.project(), parsed.coordination().runId())
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

    private static void printTiming(
            PrintStream out,
            WorkbenchCommandLine.Parsed parsed,
            HttpClient http,
            SessionState state
    ) {
        JsonNode events = null;
        try {
            events = postJson(http, state, "/tools/workbench_events", Map.of());
        } catch (RuntimeException ignored) {
            // Elapsed time still prints from the run record when the event feed is down.
        }
        out.println(formatReport(timingOf(events, runStarted(parsed), Instant.now()), sleepGaps(http, state)));
    }

    static String unhealthyMessage(Path project, String runId) {
        return "No healthy Workbench session. Run isolate / session-start, or use the window that is already open. ("
                + unhealthyReason(project, runId) + ")";
    }

    static String unhealthyReason(Path project, String runId) {
        Path file = sessionFileFor(project, runId);
        if (Files.isRegularFile(file)) {
            Long pid = readPid(file);
            if (pid == null) return "missing-session-file";
            if (!pidAlive(pid)) return "crashed";
            return "still-starting";
        }
        if (runId == null || runId.isBlank()) return "never-started";
        Path record = AgentCoordination.runDirectory(project, runId).resolve(AgentCoordination.RECORD_FILE);
        if (!Files.isRegularFile(record)) return "never-started";
        try {
            JsonNode node = JSON.readTree(record.toFile());
            String status = node.path("status").asText("");
            String stopped = node.path("stoppedAt").asText("");
            boolean running = "RUNNING".equalsIgnoreCase(status) && (stopped.isBlank() || "null".equals(stopped));
            return running ? "still-starting" : "missing-session-file";
        } catch (IOException failure) {
            return "missing-session-file";
        }
    }

    private static Path sessionFileFor(Path project, String runId) {
        if (runId != null && !runId.isBlank()) {
            return AgentCoordination.runDirectory(project, runId).resolve("session").resolve("cli-session.json");
        }
        Path attach = PickleballLocalLayout.attachFile(project);
        if (Files.isRegularFile(attach)) return attach;
        return sessionFile(project);
    }

    private static Long readPid(Path file) {
        try {
            JsonNode root = JSON.readTree(file.toFile());
            if (!root.has("pid")) return null;
            long pid = root.path("pid").asLong(0L);
            return pid <= 0 ? null : pid;
        } catch (IOException failure) {
            return null;
        }
    }

    private static Instant runStarted(WorkbenchCommandLine.Parsed parsed) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) return null;
        Path record = AgentCoordination.runDirectory(parsed.project(), runId).resolve(AgentCoordination.RECORD_FILE);
        if (!Files.isRegularFile(record)) return null;
        try {
            String started = JSON.readTree(record.toFile()).path("startedAt").asText("");
            if (started.isBlank()) return null;
            return Instant.parse(started);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String formatReport(Timing timing, List<SleepGapMonitor.Gap> gaps) {
        return "elapsed-run-ms=" + timing.elapsedRunMs
                + System.lineSeparator() + "elapsed-step-ms=" + timing.elapsedStepMs
                + System.lineSeparator() + formatSleepGaps(gaps);
    }

    static String formatSleepGaps(List<SleepGapMonitor.Gap> gaps) {
        if (gaps == null || gaps.isEmpty()) return "sleep-gap=none";
        StringBuilder text = new StringBuilder();
        for (SleepGapMonitor.Gap gap : gaps) {
            if (!text.isEmpty()) text.append(System.lineSeparator());
            text.append("sleep-gap=").append(gap.gapMs()).append("ms at ").append(gap.detectedAt());
        }
        return text.toString();
    }

    private static List<SleepGapMonitor.Gap> sleepGaps(HttpClient http, SessionState state) {
        try {
            JsonNode health = getJson(http, state, "/health");
            JsonNode array = health == null ? null : health.get("sleepGaps");
            if (array == null || !array.isArray()) return List.of();
            List<SleepGapMonitor.Gap> gaps = new ArrayList<>();
            for (JsonNode node : array) {
                String detected = node.path("detectedAt").asText("");
                long gapMs = node.path("gapMs").asLong(-1);
                if (detected.isBlank() || gapMs < 0) continue;
                try {
                    gaps.add(new SleepGapMonitor.Gap(Instant.parse(detected), gapMs));
                } catch (RuntimeException ignored) {
                    // A bad record does not hide the rest.
                }
            }
            return gaps;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    static final class Timing {
        final long elapsedRunMs;
        final long elapsedStepMs;

        Timing(long elapsedRunMs, long elapsedStepMs) {
            this.elapsedRunMs = elapsedRunMs;
            this.elapsedStepMs = elapsedStepMs;
        }
    }

    static Timing timingOf(JsonNode events, Instant runStarted, Instant now) {
        Instant clock = now == null ? Instant.now() : now;
        List<Mark> marks = eventMarks(events);
        Instant origin = runStarted;
        if (origin == null) {
            for (Mark mark : marks) {
                if (mark.at != null) {
                    origin = mark.at;
                    break;
                }
            }
        }
        long runMs = origin == null ? 0 : Math.max(0, Duration.between(origin, clock).toMillis());
        Instant stepAt = null;
        for (Mark mark : marks) {
            if (mark.step && mark.at != null) stepAt = mark.at;
        }
        long stepMs = stepAt == null ? 0 : Math.max(0, Duration.between(stepAt, clock).toMillis());
        return new Timing(runMs, stepMs);
    }

    private record Mark(Instant at, boolean step, String text) {
    }

    private static List<Mark> eventMarks(JsonNode events) {
        JsonNode array = events;
        if (events != null && events.isObject()) {
            if (events.has("events") && events.get("events").isArray()) array = events.get("events");
            else if (events.has("result") && events.get("result").isArray()) array = events.get("result");
            else array = null;
        }
        if (array == null || !array.isArray()) return List.of();
        List<Mark> marks = new ArrayList<>();
        for (JsonNode event : array) {
            Instant at = parseInstant(firstText(event, "timestamp", "time", "at"));
            String stepText = firstText(event, "stepText", "step", "text");
            String phrase = firstText(event, "phraseText", "phrase");
            String hook = firstText(event, "hook", "type", "kind");
            boolean step = (stepText != null && !stepText.isBlank())
                    || (hook != null && hook.toUpperCase(Locale.ROOT).contains("STEP"));
            String text = ((stepText == null ? "" : stepText) + " " + (phrase == null ? "" : phrase)).trim();
            marks.add(new Mark(at, step, text));
        }
        return marks;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) return value.asText();
        }
        return null;
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text.trim());
        } catch (RuntimeException ignored) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(text.trim()));
            } catch (RuntimeException again) {
                return null;
            }
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

    private static void finishReason(Path project, String runId, String reason, PrintStream out) {
        try {
            AgentCoordination.finish(project, runId, reason, "FAILED", java.time.Instant.now(), out);
        } catch (RuntimeException ignored) {
            // The session error is already printed.
        }
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
        final String stopAfter;
        final boolean ackOnly;
        final boolean wait;
        final boolean force;
        final Long stepTimeoutSeconds;
        final Long runTimeoutSeconds;

        private SessionFlags(
                String text,
                String id,
                String feature,
                String run,
                String fromHere,
                String stopAfter,
                boolean ackOnly,
                boolean wait,
                boolean force,
                Long stepTimeoutSeconds,
                Long runTimeoutSeconds
        ) {
            this.text = text;
            this.id = id;
            this.feature = feature;
            this.run = run;
            this.fromHere = fromHere;
            this.stopAfter = stopAfter;
            this.ackOnly = ackOnly;
            this.wait = wait;
            this.force = force;
            this.stepTimeoutSeconds = stepTimeoutSeconds;
            this.runTimeoutSeconds = runTimeoutSeconds;
        }

        static SessionFlags parse(String[] args) {
            String text = null;
            String id = null;
            String feature = null;
            String run = null;
            String fromHere = null;
            String stopAfter = null;
            boolean ackOnly = false;
            boolean wait = true;
            boolean force = false;
            Long stepTimeoutSeconds = null;
            Long runTimeoutSeconds = null;
            String absorb = null;
            List<String> words = new ArrayList<>();
            if (args == null) {
                return new SessionFlags(null, null, null, null, null, null, false, true, false, null, null);
            }
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (token == null) continue;
                if (token.startsWith("--text=")) {
                    text = token.substring("--text=".length());
                    absorb = null;
                    continue;
                }
                if ("--text".equals(token) && index + 1 < args.length) {
                    text = args[++index];
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--id=")) {
                    id = token.substring("--id=".length());
                    absorb = null;
                    continue;
                }
                if ("--id".equals(token) && index + 1 < args.length) {
                    id = args[++index];
                    absorb = null;
                    continue;
                }
                if ("--ack-only".equals(token)) {
                    ackOnly = true;
                    wait = false;
                    absorb = null;
                    continue;
                }
                if ("--wait".equals(token)) {
                    wait = true;
                    absorb = null;
                    continue;
                }
                if ("--no-wait".equals(token)) {
                    wait = false;
                    absorb = null;
                    continue;
                }
                if ("--force".equals(token)) {
                    force = true;
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--step-timeout=") || "--step-timeout".equals(token)) {
                    String value = token.startsWith("--step-timeout=")
                            ? token.substring("--step-timeout=".length())
                            : (index + 1 < args.length ? args[++index] : null);
                    stepTimeoutSeconds = positiveSeconds(value, "--step-timeout");
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--run-timeout=") || "--run-timeout".equals(token)) {
                    String value = token.startsWith("--run-timeout=")
                            ? token.substring("--run-timeout=".length())
                            : (index + 1 < args.length ? args[++index] : null);
                    runTimeoutSeconds = positiveSeconds(value, "--run-timeout");
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--stop-after=")) {
                    stopAfter = token.substring("--stop-after=".length());
                    absorb = "stopAfter";
                    continue;
                }
                if ("--stop-after".equals(token) && index + 1 < args.length) {
                    stopAfter = args[++index];
                    absorb = "stopAfter";
                    continue;
                }
                if (token.startsWith("--feature=")) {
                    feature = token.substring("--feature=".length());
                    absorb = null;
                    continue;
                }
                if ("--feature".equals(token) && index + 1 < args.length) {
                    feature = args[++index];
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--run=")) {
                    run = token.substring("--run=".length());
                    absorb = null;
                    continue;
                }
                if ("--run".equals(token) && index + 1 < args.length) {
                    run = args[++index];
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--from-here=")) {
                    fromHere = token.substring("--from-here=".length());
                    absorb = null;
                    continue;
                }
                if ("--from-here".equals(token) && index + 1 < args.length) {
                    fromHere = args[++index];
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--tags=") || token.startsWith("--name=")) {
                    absorb = null;
                    continue;
                }
                if (("--tags".equals(token) || "--name".equals(token)) && index + 1 < args.length) {
                    index++;
                    absorb = null;
                    continue;
                }
                if (token.startsWith("--example=")) {
                    absorb = null;
                    while (index + 1 < args.length && isSelectorTail(args[index + 1])) {
                        index++;
                    }
                    continue;
                }
                if ("--example".equals(token) && index + 1 < args.length) {
                    index++;
                    absorb = null;
                    while (index + 1 < args.length && isSelectorTail(args[index + 1])) {
                        index++;
                    }
                    continue;
                }
                if (absorb != null && !token.startsWith("-") && !looksLikeProject(token)) {
                    if ("stopAfter".equals(absorb)) stopAfter = stopAfter + " " + token;
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
            return new SessionFlags(
                    text, id, feature, run, fromHere, stopAfter, ackOnly, wait, force,
                    stepTimeoutSeconds, runTimeoutSeconds
            );
        }

        private static Long positiveSeconds(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " requires a positive number of seconds.");
            }
            try {
                long seconds = Long.parseLong(value.trim());
                if (seconds <= 0) {
                    throw new IllegalArgumentException(label + " requires a positive number of seconds.");
                }
                return seconds;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(label + " must be a whole number of seconds: " + value);
            }
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
