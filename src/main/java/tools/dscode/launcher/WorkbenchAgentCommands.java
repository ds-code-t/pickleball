package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.control.protocol.RunConfigs;
import tools.dscode.control.protocol.SessionDescriptor;
import tools.dscode.control.protocol.SessionDescriptorWriter;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.WorkspaceSeed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Agent-facing Workbench verbs that run in the consumer JVM (not the controller JAR). */
public final class WorkbenchAgentCommands {
    /**
     * Flags read from the raw argument vector rather than {@link WorkbenchCommandLine.Parsed} fields.
     */
    static final List<String> MAJOR_FLAGS = List.of(
            "--json", "--expect", "--config", "--reseed", "--from-run",
            "--html", "--category", "--status"
    );

    private WorkbenchAgentCommands() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, ConsumerMavenTestRunner::run);
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            MavenRunner maven
    ) {
        WorkbenchCommandLine.Parsed parsed;
        try {
            parsed = WorkbenchCommandLine.parse(args);
        } catch (IllegalArgumentException failure) {
            err.println(failure.getMessage());
            return 2;
        }
        try {
            return switch (parsed.command()) {
                case "export-guidance" -> exportGuidance(parsed, out, err);
                case "hint", "discover-hint" -> hint(parsed, out);
                case "resolve-runvars" -> resolveRunVars(parsed, out);
                case "workspace-show" -> workspaceShow(parsed, out, err);
                case "workspace-edit" -> workspaceEdit(parsed, out, err);
                case "workspace-seed" -> workspaceSeed(parsed, out, err);
                case "discover" -> {
                    Integer rejected = rejectStepTimeout(parsed, err);
                    if (rejected != null) yield rejected;
                    yield parsed.planOnly() ? plan(parsed, out, err) : discover(parsed, out, err, maven);
                }
                case "confirm" -> {
                    Integer rejected = rejectStepTimeout(parsed, err);
                    if (rejected != null) yield rejected;
                    yield parsed.planOnly() ? plan(parsed, out, err) : confirm(parsed, out, err, maven);
                }
                case "plan" -> plan(parsed, out, err);
                case "short-log" -> shortLog(parsed, out, err);
                case "note" -> note(parsed, out, err);
                case "inbox" -> inbox(parsed, out, err);
                case "finish" -> finish(parsed, out, err);
                case "presence" -> presence(parsed, out, err);
                case "post" -> post(parsed, out, err);
                case "history" -> history(parsed, out, err);
                case "gc-runs" -> gcRuns(parsed, out, err);
                case "gc-versions" -> gcVersions(parsed, out, err);
                case "use-version" -> useVersion(parsed, out, err);
                case "inspect-page" -> inspectPage(parsed, out, err);
                case "find-element" -> findElement(parsed, out, err);
                case "session-list" -> sessionList(parsed, out, err);
                case "session-info" -> sessionInfo(parsed, out, err);
                case "session-hold" -> sessionHold(parsed, out, err, true);
                case "session-release" -> sessionHold(parsed, out, err, false);
                default -> {
                    err.println("Unknown Workbench agent command: " + parsed.command());
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("Workbench " + parsed.command() + " failed: " + WorkbenchCliErrors.explain(failure));
            return 1;
        }
    }

    private static int exportGuidance(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        try {
            return PickleballLocalStore.exportGuidance(parsed.outputDirectory(), out, err);
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static int hint(WorkbenchCommandLine.Parsed parsed, PrintStream out) {
        String named = WorkspaceSeed.namedProjectBrowser(parsed.project());
        out.println("Seed writes .pickleball/runs/<run-id>/workspace/runvars.properties and workspace/configs. Edit those files with workspace-show, workspace-edit, and workspace-seed before the next start.");
        out.println("Discover and confirm launch with -Dpkb_workspace plus selection keys (pkb_tags, pkb_name, pkb_example, pkb_features, pkb_parallel, pkb_run_id, pkb_agent_id). Isolate does not force pkb_parallel=1.");
        if (named.isBlank()) {
            out.println("Browser: pkb_browser=CHROME_HEADLESS (project names no browser; CHROME_HEADLESS is the only fallback).");
        } else {
            out.println("Browser: pkb_browser=" + named + " (project pkb_browser is kept).");
        }
        out.println("pkb_parallel=auto stays auto in the workspace and is resolved at launch. Hint does not print a recommended pkb_runvars string.");
        out.println("When a Workbench window is already open, only one agent drives that session with open-scenario, example, play, execute-step, stop, and diagnostic-run so the person sees it. Other agents stay headless on their own run ids. Otherwise isolate starts a headless session; then use execute-step / status / events / stop. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Same launcher, only change exec.args.");
        out.println("Read the short log (.pickleball/agent-log) before and after a run, and during a long session, instead of the dense diagnostic log.");
        out.println("Never supply pkb_run_profile as input. pkb_overriderunvars is deprecated for Workbench. It still works for a plain mvn test that does not set pkb_workspace.");
        out.println();
        out.println("NEXT: run discover");
        printDryRunResolve(parsed.project(), false, out);
        return 0;
    }

    private static int resolveRunVars(WorkbenchCommandLine.Parsed parsed, PrintStream out) {
        printDryRunResolve(parsed.project(), true, out);
        return 0;
    }

    private static int workspaceShow(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.println("workspace-show requires --run-id.");
            return 2;
        }
        Path workspace = Workspace.directory(parsed.project(), runId);
        out.println("workspace=" + workspace.toAbsolutePath().normalize());
        if (!Workspace.exists(workspace)) {
            out.println("present=false");
            err.println("No workspace for " + runId + ". Run workspace-seed first.");
            return 1;
        }
        out.println("present=true");
        out.println("fingerprint=" + Workspace.fingerprint(workspace));
        Workspace.readRunVars(workspace).forEach((key, value) -> out.println(key + "=" + value));
        for (String file : RunConfigs.files(parsed.project(), runId)) {
            out.println("config " + file);
        }
        return 0;
    }

    private static int workspaceEdit(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.println("workspace-edit requires --run-id.");
            return 2;
        }
        Path workspace = Workspace.directory(parsed.project(), runId);
        if (!Workspace.exists(workspace)) {
            err.println("No workspace for " + runId + ". Run workspace-seed first.");
            return 1;
        }
        String expect = flagValue(parsed.forwarded(), "--expect");
        String fingerprint = Workspace.fingerprint(workspace);
        if (expect != null && !expect.equals(fingerprint)) {
            err.println("WARNING: workspace changed on disk. Nothing was written.");
            err.println("fingerprint=" + fingerprint);
            return 3;
        }
        String config = flagValue(parsed.forwarded(), "--config");
        if (config == null && parsed.runVars().isEmpty()) {
            err.println("workspace-edit requires --runvar and/or --config.");
            return 2;
        }
        if (config != null) {
            String text = parsed.coordination().text();
            if (text == null) {
                err.println("workspace-edit --config requires --text.");
                return 2;
            }
            RunConfigs.write(parsed.project(), runId, config, text.endsWith("\n") ? text : text + "\n");
        }
        if (!parsed.runVars().isEmpty()) {
            Map<String, String> selection = new LinkedHashMap<>();
            Workspace.applyLaunchEdits(workspace, selection, parsed.runVars(), err);
            if (!selection.isEmpty()) {
                Map<String, String> values = new LinkedHashMap<>(Workspace.readRunVars(workspace));
                values.putAll(selection);
                Workspace.writeRunVars(workspace, values);
            }
        }
        out.println("workspace=" + workspace.toAbsolutePath().normalize());
        out.println("fingerprint=" + Workspace.fingerprint(workspace));
        return 0;
    }

    private static int workspaceSeed(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.println("workspace-seed requires --run-id.");
            return 2;
        }
        boolean reseed = hasFlag(parsed.forwarded(), "--reseed");
        String fromRun = flagValue(parsed.forwarded(), "--from-run");
        Path workspace = WorkspaceSeed.seed(
                parsed.project(),
                runId,
                reseed,
                fromRun,
                null,
                path -> WorkspaceLaunch.capture(parsed.project(), path, reseed)
        );
        out.println("workspace=" + workspace.toAbsolutePath().normalize());
        out.println("fingerprint=" + Workspace.fingerprint(workspace));
        out.println("present=true");
        return 0;
    }

    private static int sessionList(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        boolean json = hasFlag(parsed.forwarded(), "--json");
        List<Path> files = SessionDescriptorWriter.liveFiles(parsed.project());
        if (json) {
            List<String> documents = new ArrayList<>();
            for (Path file : files) documents.add(readText(file));
            out.print(jsonArray(documents));
            return 0;
        }
        if (files.isEmpty()) {
            out.println("(no live sessions)");
            return 0;
        }
        for (Path file : files) {
            SessionDescriptor descriptor = SessionDescriptorWriter.readFile(file);
            out.println(String.join("\t",
                    descriptor.sessionId(),
                    descriptor.runId(),
                    descriptor.state().wire(),
                    textOrDash(descriptor.browserType()),
                    textOrDash(descriptor.cdpDebuggerAddress()),
                    textOrDash(descriptor.currentUrl())
            ));
        }
        return 0;
    }

    private static int sessionInfo(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String id = parsed.coordination().id();
        if (id == null || id.isBlank()) {
            err.println("Usage: session info <sessionId> [--json]");
            return 2;
        }
        Optional<Path> file = SessionDescriptorWriter.findFile(parsed.project(), id);
        if (file.isEmpty()) {
            err.println("No session descriptor for " + id + ".");
            return 1;
        }
        String text = readText(file.get());
        if (!text.endsWith("\n")) text = text + "\n";
        out.print(text);
        return 0;
    }

    private static int sessionHold(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err, boolean hold) {
        String id = parsed.coordination().id();
        String verb = hold ? "hold" : "release";
        if (id == null || id.isBlank()) {
            err.println("Usage: session " + verb + " <sessionId> [--json]");
            return 2;
        }
        try {
            SessionDescriptor updated = hold
                    ? SessionDescriptorWriter.hold(parsed.project(), id, java.time.Instant.now())
                    : SessionDescriptorWriter.release(parsed.project(), id, java.time.Instant.now());
            String text = readText(SessionDescriptorWriter.file(parsed.project(), updated.runId()));
            if (!text.endsWith("\n")) text = text + "\n";
            out.print(text);
            return 0;
        } catch (IllegalStateException failure) {
            err.println(failure.getMessage());
            return 1;
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + file + ": " + failure.getMessage(), failure);
        }
    }

    private static String textOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String jsonArray(List<String> documents) {
        if (documents.isEmpty()) return "[]\n";
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < documents.size(); i++) {
            String[] lines = documents.get(i).stripTrailing().split("\n", -1);
            for (int line = 0; line < lines.length; line++) {
                json.append("  ").append(lines[line]);
                if (line + 1 == lines.length && i + 1 < documents.size()) json.append(',');
                json.append('\n');
            }
        }
        json.append("]\n");
        return json.toString();
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) return false;
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static String flagValue(String[] args, String name) {
        if (args == null) return null;
        String prefix = name + "=";
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (arg == null) continue;
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
            if (name.equals(arg) && index + 1 < args.length && args[index + 1] != null && !args[index + 1].isBlank()) {
                return args[index + 1].trim();
            }
        }
        return null;
    }

    private static void printDryRunResolve(
            Path project,
            boolean includeAmbient,
            PrintStream out
    ) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        loadProperties(values, project.resolve("src/test/resources/pickleball.properties"));
        loadProperties(values, project.resolve("src/test/resources/pickleball_local.properties"));
        LinkedHashMap<String, String> jvm = new LinkedHashMap<>();
        if (includeAmbient) {
            PKB_props.copyJvmDryRunInputs(values, jvm, true, true);
        }
        PKB_props.ResolvedRunVars resolved = PKB_props.resolveRunVars(values, jvm);
        out.println("Dry-run resolve of the launcher JVM, not the Discover worker (does not start tests, browsers, or Discover):");
        out.println("Do not treat resolve-runvars as the environment Discover will use. Trust the environment on the run record after Discover.");
        out.println("sealed=" + resolved.sealed());
        out.println("pkb_run_profile=" + resolved.runProfile());
        out.println("runProfileFingerprint=" + resolved.fingerprint());
        out.println("provenance=" + resolved.provenance());
        out.println("pkb_overriderunvars is deprecated for Workbench. A plain run that does not set pkb_workspace can still use -Dpkb_overriderunvars=<complete compact map>, never -Dpkb_run_profile=.");
        out.println();
    }

    private static void loadProperties(Map<String, String> values, Path file) {
        if (!java.nio.file.Files.isRegularFile(file)) return;
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
            return;
        }
        for (String key : props.stringPropertyNames()) {
            if (key != null) values.put(key.toLowerCase(java.util.Locale.ROOT), props.getProperty(key));
        }
    }

    private static int discover(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            MavenRunner maven
    ) {
        AgentCoordination.Run run = AgentCoordination.begin(
                parsed.project(),
                request(parsed, "discover")
        );
        try {
            launchNote(parsed.project(), run.runId(), "command", "discover");
            echoFilters(parsed, out);
            WorkbenchFeatureIndex.Selection selection = select(
                    parsed.project(), parsed.tags(), parsed.name(), parsed.example(), parsed
            );
            launchNote(parsed.project(), run.runId(), "index", indexDetail(selection));
            String tags = tagsForLaunch(selection, parsed.tags(), err);
            WorkspaceLaunch.Prepared prepared = WorkspaceLaunch.prepare(
                    parsed.project(),
                    run.runId(),
                    null,
                    tags,
                    parsed.name(),
                    parsed.example(),
                    run.runId(),
                    run.agentId(),
                    parsed.retention(),
                    parsed.runVars(),
                    err
            );
            Map<String, String> stored = Workspace.readRunVars(prepared.workspace());
            String environment = blankToNull(stored.get(PKB_props.PKB_ENVIRONMENT));
            String browser = blankToNull(stored.get(PKB_props.PKB_BROWSER));
            out.println("workspace=" + prepared.workspace());
            out.println("workspace-runvars=" + PKB_props.serializeRunVars(new java.util.TreeMap<>(stored)));
            List<String> command = withCoordination(
                    ConsumerMavenTestRunner.command(
                            parsed.project(),
                            prepared.workspace(),
                            prepared.selection(),
                            featurePaths(parsed, selection)
                    ),
                    run
            );
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            launchNote(parsed.project(), run.runId(), "maven-start", "");
            Bounded mavenResult = runBounded(
                    parsed,
                    command,
                    new WorkbenchRunReports.Tee(out, capturedOut),
                    new WorkbenchRunReports.Tee(err, capturedErr),
                    maven
            );
            launchNote(parsed.project(), run.runId(), "maven-done", "exit=" + mavenResult.exit() + " timedOut=" + mavenResult.timedOut());
            if (mavenResult.timedOut()) {
                publishQuietly(parsed, run, selection, capturedOut, capturedErr, "TIMEOUT", environment, browser, out, err);
                return timeoutExit(parsed, run, out, err);
            }
            int exit = mavenResult.exit();
            boolean empty = selection.matchedNone();
            Observed observed = observe(parsed.project(), run.runId(), exit, empty);
            String status = observed.status();
            int reported = observed.exitCode();
            if (empty) printEmpty(parsed, browser, out, err);
            AgentCoordination.finish(
                    parsed.project(),
                    run.runId(),
                    outcomeLearned(parsed, empty, browser),
                    status,
                    java.time.Instant.now(),
                    out,
                    observed.failureCount() > 0 ? observed.failureCount() : null,
                    environment,
                    browser
            );
            publishQuietly(parsed, run, selection, capturedOut, capturedErr, status, environment, browser, out, err);
            return recordDiscover(parsed, out, err, reported, run.runId());
        } catch (RuntimeException failure) {
            finishQuietly(parsed.project(), run.runId(), "FAILED", out);
            throw failure;
        }
    }

    private static int confirm(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            MavenRunner maven
    ) {
        AgentCoordination.Run run = AgentCoordination.begin(
                parsed.project(),
                request(parsed, "confirm")
        );
        try {
            launchNote(parsed.project(), run.runId(), "command", "confirm");
            echoFilters(parsed, out);
            LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.requireScoped(
                    parsed.project(),
                    parsed.coordination().agentId(),
                    parsed.coordination().runId()
            );
            Map<String, String> retained = LastDiscoverSnapshot.retainedRunVars(snapshot);
            String fromRun = snapshot.runId();
            Path fromWorkspace = fromRun == null ? null : Workspace.directory(parsed.project(), fromRun);
            String tags = tagsForLaunch(
                    null,
                    firstNonBlank(parsed.tags(), retained.get(PKB_props.PKB_TAGS)),
                    err
            );
            WorkbenchFeatureIndex.Selection selection = select(
                    parsed.project(),
                    firstNonBlank(parsed.tags(), retained.get(PKB_props.PKB_TAGS)),
                    firstNonBlank(parsed.name(), retained.get(PKB_props.PKB_NAME)),
                    firstNonBlank(parsed.example(), retained.get(PKB_props.PKB_EXAMPLE)),
                    parsed
            );
            tags = tagsForLaunch(selection, tags, err);
            launchNote(parsed.project(), run.runId(), "index", indexDetail(selection));
            WorkspaceLaunch.Prepared prepared = WorkspaceLaunch.prepare(
                    parsed.project(),
                    run.runId(),
                    fromWorkspace != null && Workspace.exists(fromWorkspace) ? fromRun : null,
                    tags,
                    firstNonBlank(parsed.name(), retained.get(PKB_props.PKB_NAME)),
                    firstNonBlank(parsed.example(), retained.get(PKB_props.PKB_EXAMPLE)),
                    run.runId(),
                    run.agentId(),
                    parsed.retention(),
                    parsed.runVars(),
                    err
            );
            Map<String, String> stored = Workspace.readRunVars(prepared.workspace());
            String environment = blankToNull(stored.get(PKB_props.PKB_ENVIRONMENT));
            String browser = blankToNull(stored.get(PKB_props.PKB_BROWSER));
            out.println("workspace=" + prepared.workspace());
            out.println("workspace-runvars=" + PKB_props.serializeRunVars(new java.util.TreeMap<>(stored)));
            List<String> command = withCoordination(
                    ConsumerMavenTestRunner.confirmCommand(
                            parsed.project(),
                            prepared.workspace(),
                            prepared.selection(),
                            featurePaths(parsed, selection)
                    ),
                    run
            );
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            launchNote(parsed.project(), run.runId(), "maven-start", "");
            Bounded mavenResult = runBounded(
                    parsed,
                    command,
                    new WorkbenchRunReports.Tee(out, capturedOut),
                    new WorkbenchRunReports.Tee(err, capturedErr),
                    maven
            );
            launchNote(parsed.project(), run.runId(), "maven-done", "exit=" + mavenResult.exit() + " timedOut=" + mavenResult.timedOut());
            if (mavenResult.timedOut()) {
                publishQuietly(parsed, run, selection, capturedOut, capturedErr, "TIMEOUT", environment, browser, out, err);
                return timeoutExit(parsed, run, out, err);
            }
            int exit = mavenResult.exit();
            boolean empty = selection.matchedNone();
            Observed observed = observe(parsed.project(), run.runId(), exit, empty);
            String status = observed.status();
            int reported = observed.exitCode();
            if (empty) printEmpty(parsed, browser, out, err);
            AgentCoordination.finish(
                    parsed.project(),
                    run.runId(),
                    outcomeLearned(parsed, empty, browser),
                    status,
                    java.time.Instant.now(),
                    out,
                    observed.failureCount() > 0 ? observed.failureCount() : null,
                    environment,
                    browser
            );
            publishQuietly(parsed, run, selection, capturedOut, capturedErr, status, environment, browser, out, err);
            return printCatalog(parsed.project(), out, err, reported, "workbench-confirm", false, run.runId(), agentId(parsed));
        } catch (RuntimeException failure) {
            finishQuietly(parsed.project(), run.runId(), "FAILED", out);
            throw failure;
        }
    }

    private record Bounded(int exit, boolean timedOut) {
    }

    /**
     * Workbench-only watchdog. A null timeout keeps the existing blocking Maven call.
     * Timeout records TIMEOUT on the run and does not go through StepData.
     */
    private static Bounded runBounded(
            WorkbenchCommandLine.Parsed parsed,
            List<String> command,
            PrintStream out,
            PrintStream err,
            MavenRunner maven
    ) {
        Long seconds = runBudgetSeconds(parsed);
        if (seconds == null) {
            return new Bounded(maven.run(parsed.project(), command, out, err), false);
        }
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pickleball-workbench-run-watch");
            thread.setDaemon(true);
            return thread;
        });
        Future<Integer> future = pool.submit(() -> maven.run(parsed.project(), command, out, err));
        try {
            return new Bounded(future.get(seconds, TimeUnit.SECONDS), false);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return new Bounded(1, true);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException(
                    "Interrupted while waiting for Workbench " + parsed.command() + ".",
                    interrupted
            );
        } finally {
            pool.shutdownNow();
        }
    }

    private static Integer rejectStepTimeout(WorkbenchCommandLine.Parsed parsed, PrintStream err) {
        if (parsed.stepTimeoutSeconds() == null) return null;
        err.println(parsed.command() + " does not accept --step-timeout; use --run-timeout; per-step limits apply to execute-step");
        return 2;
    }

    private static Long runBudgetSeconds(WorkbenchCommandLine.Parsed parsed) {
        return parsed.runTimeoutSeconds();
    }

    private static int timeoutExit(
            WorkbenchCommandLine.Parsed parsed,
            AgentCoordination.Run run,
            PrintStream out,
            PrintStream err
    ) {
        long seconds = runBudgetSeconds(parsed);
        err.println("Workbench " + parsed.command() + " TIMEOUT after " + seconds + "s");
        AgentCoordination.finish(
                parsed.project(),
                run.runId(),
                parsed.coordination().learned(),
                "TIMEOUT",
                java.time.Instant.now(),
                out
        );
        return 1;
    }

    private static int plan(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        try {
            return printPlan(parsed, select(parsed.project(), parsed.tags(), parsed.name(), parsed.example(), parsed), out, err);
        } catch (IllegalArgumentException failure) {
            err.println(failure.getMessage());
            return 2;
        }
    }

    private static WorkbenchFeatureIndex.Selection select(
            Path project,
            String tags,
            String name,
            String example,
            WorkbenchCommandLine.Parsed parsed
    ) {
        return WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project,
                java.util.List.of(),
                tags,
                name,
                example,
                parsed.selects(),
                parsed.refreshIndex()
        ));
    }

    private static String featurePaths(WorkbenchCommandLine.Parsed parsed, WorkbenchFeatureIndex.Selection selection) {
        if (parsed.fullScan()) return null;
        return featurePaths(parsed.project(), selection);
    }

    private static String featurePaths(Path project, WorkbenchFeatureIndex.Selection selection) {
        if (selection == null || selection.fullScanFallback() || !selection.narrowed()) return null;
        if (selection.matchedNone()) {
            try {
                return WorkbenchFeatureIndex.noMatchFeatureProperty(project);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Could not record an empty Workbench selection: " + failure.getMessage(),
                        failure
                );
            }
        }
        return selection.featureProperty();
    }

    private static void launchNote(Path project, String runId, String stage, String detail) {
        Path folder = null;
        try {
            if (project != null && runId != null && !runId.isBlank()) {
                folder = AgentCoordination.runDirectory(project, runId);
            }
        } catch (RuntimeException ignored) {
            folder = null;
        }
        WorkbenchLaunchTiming.note(folder, stage, detail);
    }

    private static String indexDetail(WorkbenchFeatureIndex.Selection selection) {
        if (selection == null) return "";
        String property = selection.featureProperty();
        return "files=" + selection.filesIndexed()
                + " reparsed=" + selection.filesReparsed()
                + " cacheHits=" + selection.cacheHits()
                + " scenarios=" + selection.hits().size()
                + " narrowed=" + selection.narrowed()
                + " fullScan=" + selection.fullScanFallback()
                + " listChars=" + (property == null ? 0 : property.length());
    }

    private static int printPlan(
            WorkbenchCommandLine.Parsed parsed,
            WorkbenchFeatureIndex.Selection selection,
            PrintStream out,
            PrintStream err
    ) {
        out.println("tags=" + text(parsed.tags()));
        out.println("name=" + text(parsed.name()));
        out.println("example=" + text(parsed.example()));
        out.println("select=" + parsed.selects());
        if (selection.fullScanFallback() || parsed.fullScan()) {
            if (selection.fullScanFallback()
                    && selection.fallbackReason() != null
                    && selection.fallbackReason().contains("unsupported tag expression")) {
                err.println(WorkbenchTagFallback.warning(selection.fallbackReason(), parsed.tags()));
            } else {
                err.println("WARNING: full-scan fallback: " + (
                        parsed.fullScan() && !selection.fullScanFallback()
                                ? "top-level narrowing skipped (--no-narrow / --full-scan); nested lookups keep the configured pkb_features"
                                : selection.fallbackReason()
                ));
            }
            out.println("PLAN full-scan");
            out.println("features=full-scan");
            return 0;
        }
        out.println("PLAN " + selection.hits().size() + " scenario(s)");
        for (WorkbenchFeatureIndex.Hit hit : selection.hits()) {
            StringBuilder line = new StringBuilder();
            line.append(hit.relativePath()).append(':').append(hit.line()).append(' ').append(hit.scenarioName());
            if (hit.exampleRow() > 0) {
                line.append(" example ").append(hit.exampleRow());
                String arguments = hit.argumentText();
                if (!arguments.isEmpty()) line.append(' ').append(arguments);
            }
            out.println(line);
        }
        if (selection.hits().isEmpty()) {
            err.println("WARNING: no scenarios matched tags=" + text(parsed.tags())
                    + " name=" + text(parsed.name())
                    + " example=" + text(parsed.example())
                    + " select=" + parsed.selects());
            out.println("features=none");
            return 1;
        }
        out.println(selection.narrowed()
                ? "features=" + selection.featureProperty()
                : "features=full-scan");
        if (selection.narrowed()) {
            out.println("nested-features=configured pkb_features");
        }
        return 0;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback;
    }

    private static int shortLog(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        int limit = parsed.coordination().limit() == null ? 40 : parsed.coordination().limit();
        try {
            java.util.List<String> lines = AgentCoordination.recentLog(parsed.project(), limit);
            if (lines.isEmpty()) {
                out.println("(no short-log lines)");
                return 0;
            }
            for (String line : lines) out.println(line);
            return 0;
        } catch (java.io.IOException failure) {
            err.println("Could not read the short log: " + failure.getMessage());
            return 1;
        }
    }

    private static int note(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String text = firstText(parsed);
        if (text == null) {
            err.println("Usage: note --text=<one line> [--agent=<id>] [--run-id=<id>]");
            return 2;
        }
        AgentCoordination.note(
                parsed.project(),
                parsed.coordination().agentId(),
                parsed.coordination().runId(),
                text,
                recordPath(parsed),
                out
        );
        return 0;
    }

    private static int inbox(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        WorkbenchCommandLine.Coordination flags = parsed.coordination();
        boolean write = flags.inboxWrite() || (flags.text() != null && !flags.inboxList() && !flags.inboxTake());
        boolean take = flags.inboxTake();
        boolean list = flags.inboxList() || (!write && !take);
        if (write) {
            String message = firstText(parsed);
            if (message == null) {
                err.println("Usage: inbox --write --to=<agent-id|any> --text=<one line> [--from=<agent-id>] [--run-id=<id>]");
                return 2;
            }
            String from = flags.inboxFrom() != null ? flags.inboxFrom() : flags.agentId();
            java.nio.file.Path file = AgentCoordination.writeInbox(
                    parsed.project(),
                    flags.inboxTo(),
                    from,
                    message,
                    flags.runId() == null ? null : recordPath(parsed) == null ? null : recordPath(parsed).toString()
            );
            out.println("inbox=" + file);
            return 0;
        }
        String agent = flags.agentId() != null ? flags.agentId() : flags.inboxTo();
        if (agent == null || agent.isBlank()) {
            err.println("Usage: inbox --list|--take --agent=<id>");
            return 2;
        }
        java.util.List<AgentCoordination.InboxNote> notes = take
                ? AgentCoordination.takeInbox(parsed.project(), agent)
                : AgentCoordination.listInbox(parsed.project(), agent);
        if (notes.isEmpty()) {
            out.println("(no inbox notes)");
            return 0;
        }
        for (AgentCoordination.InboxNote note : notes) {
            out.println(note.at() + "\t" + note.from() + "\t" + note.message()
                    + (note.record() == null ? "" : "\t" + note.record()));
        }
        return list || take ? 0 : 2;
    }

    private static int presence(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        WorkbenchCommandLine.Coordination flags = parsed.coordination();
        if (flags.sweep()) {
            int removed = AgentCoordination.sweepPresence(parsed.project(), java.time.Instant.now());
            out.println("presence-removed=" + removed);
            return 0;
        }
        if (flags.inboxList() && !flags.touch()) {
            java.util.List<AgentCoordination.Presence> rows = AgentCoordination.listPresence(parsed.project());
            if (rows.isEmpty()) {
                out.println("(no presence)");
                return 0;
            }
            for (AgentCoordination.Presence row : rows) {
                out.println(row.agentId() + "\t" + row.lastSeen() + "\t" + String.join(",", row.runIds()));
            }
            return 0;
        }
        String agent = flags.agentId();
        if (agent == null || agent.isBlank()) {
            err.println("Usage: presence --touch --agent=<id> [--run-id=<id>] | presence --list | presence --sweep");
            return 2;
        }
        AgentCoordination.Presence row = AgentCoordination.touchPresence(
                parsed.project(), agent, flags.runId(), java.time.Instant.now()
        );
        out.println("presence=" + row.file());
        return 0;
    }

    private static int post(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        WorkbenchCommandLine.Coordination flags = parsed.coordination();
        if (flags.sweep()) {
            int removed = AgentCoordination.sweepPosts(parsed.project(), java.time.Instant.now());
            out.println("posts-removed=" + removed);
            return 0;
        }
        java.time.Duration ttl = null;
        if (flags.ttl() != null) {
            try {
                ttl = parseTtl(flags.ttl());
            } catch (IllegalArgumentException failure) {
                err.println(failure.getMessage());
                return 2;
            }
        }
        if (flags.renew()) {
            if (flags.id() == null) {
                err.println("Usage: post --renew --id=<id> [--ttl=1h]");
                return 2;
            }
            AgentCoordination.BoardPost renewed = AgentCoordination.renewPost(
                    parsed.project(), flags.id(), ttl, java.time.Instant.now()
            );
            out.println("post=" + renewed.file());
            return 0;
        }
        boolean write = flags.inboxWrite() || (flags.text() != null && !flags.inboxList());
        if (write) {
            String message = firstText(parsed);
            if (message == null || flags.inboxTo() == null) {
                err.println("Usage: post --write --to=<agent-id|all> --text=<one line> [--run-id=<id>] [--ttl=1h]");
                return 2;
            }
            String from = flags.inboxFrom() != null ? flags.inboxFrom() : flags.agentId();
            AgentCoordination.BoardPost written = AgentCoordination.writePost(
                    parsed.project(),
                    from,
                    flags.inboxTo(),
                    flags.runId(),
                    message,
                    ttl,
                    java.time.Instant.now()
            );
            out.println("post=" + written.file());
            return 0;
        }
        java.util.List<AgentCoordination.BoardPost> posts = AgentCoordination.listPosts(
                parsed.project(), java.time.Instant.now()
        );
        if (posts.isEmpty()) {
            out.println("(no posts)");
            return 0;
        }
        for (AgentCoordination.BoardPost note : posts) {
            out.println(note.id() + "\t" + note.from() + "\t" + note.to() + "\t" + note.expiresAt() + "\t" + note.text());
        }
        return 0;
    }

    private static int history(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        WorkbenchCommandLine.Coordination flags = parsed.coordination();
        if (flags.append() || (flags.text() != null && !flags.inboxList())) {
            String text = firstText(parsed);
            if (text == null) {
                err.println("Usage: history --append --text=<one line>");
                return 2;
            }
            AgentCoordination.appendHistory(parsed.project(), text);
            out.println("history=" + AgentCoordination.historyFile(parsed.project()));
            return 0;
        }
        int limit = flags.limit() == null ? 40 : flags.limit();
        try {
            java.util.List<String> lines = AgentCoordination.tailHistory(parsed.project(), limit);
            if (lines.isEmpty()) {
                out.println("(no history)");
                return 0;
            }
            for (String line : lines) out.println(line);
            return 0;
        } catch (java.io.IOException failure) {
            err.println("Could not read history.log: " + failure.getMessage());
            return 1;
        }
    }

    private static int gcRuns(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        AgentCoordination.RunSweep sweep = AgentCoordination.sweepRuns(parsed.project(), java.time.Instant.now());
        out.println("profiles-removed=" + sweep.profilesRemoved());
        out.println("payloads-removed=" + sweep.payloadsRemoved());
        return 0;
    }

    private static int gcVersions(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        AgentCoordination.VersionSweep sweep = AgentCoordination.sweepVersions(parsed.project(), java.time.Instant.now());
        out.println("versions-removed=" + sweep.versionsRemoved());
        return 0;
    }

    private static int useVersion(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        if (parsed.version() == null || parsed.version().isBlank()) {
            err.println("Usage: use-version --version=<safe-version>");
            return 2;
        }
        try {
            return PickleballLocalStore.useVersion(parsed.project(), parsed.version(), out, err);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static java.time.Duration parseTtl(String raw) {
        String value = raw.trim();
        if (value.startsWith("P") || value.startsWith("p")) {
            try {
                return java.time.Duration.parse(value.toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("ttl must be a duration such as 1h or PT1H: " + raw);
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)\\s*([smhd])$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("ttl must be a duration such as 1h, 30m, or PT1H: " + raw);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "s" -> java.time.Duration.ofSeconds(amount);
            case "m" -> java.time.Duration.ofMinutes(amount);
            case "h" -> java.time.Duration.ofHours(amount);
            case "d" -> java.time.Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("ttl must be a duration such as 1h or PT1H: " + raw);
        };
    }

    private static int finish(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.println("Usage: finish --run-id=<id> [--learned=<one line>] [--status=<status>]");
            return 2;
        }
        try {
            AgentCoordination.finish(
                    parsed.project(),
                    runId,
                    parsed.coordination().learned(),
                    "STOPPED",
                    java.time.Instant.now(),
                    out
            );
            return 0;
        } catch (RuntimeException failure) {
            err.println(failure.getMessage());
            return 1;
        }
    }

    private static AgentCoordination.Request request(WorkbenchCommandLine.Parsed parsed, String purpose) {
        WorkbenchCommandLine.Coordination flags = parsed.coordination();
        return AgentCoordination.Request.of(
                flags.runId(),
                flags.agentId(),
                flags.group(),
                flags.sequence(),
                flags.who(),
                flags.why(),
                purpose
        );
    }

    private static List<String> withCoordination(List<String> command, AgentCoordination.Run run) {
        List<String> copy = new ArrayList<>(command);
        for (String property : AgentCoordination.mavenProperties(run)) {
            int equals = property.indexOf('=');
            String prefix = equals < 0 ? property : property.substring(0, equals + 1);
            if (copy.stream().anyMatch(item -> item.startsWith(prefix))) continue;
            copy.add(property);
        }
        return List.copyOf(copy);
    }

    private static void finishQuietly(Path project, String runId, String status, PrintStream out) {
        try {
            AgentCoordination.finish(project, runId, null, status, java.time.Instant.now(), out);
        } catch (RuntimeException ignored) {
            // The original failure is the one to report.
        }
    }

    private static String firstText(WorkbenchCommandLine.Parsed parsed) {
        WorkbenchCommandLine.Coordination flags = parsed.coordination();
        if (flags.text() != null && !flags.text().isBlank()) return flags.text();
        if (flags.why() != null && !flags.why().isBlank()) return flags.why();
        return null;
    }

    private static Path recordPath(WorkbenchCommandLine.Parsed parsed) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) return null;
        return AgentCoordination.runDirectory(parsed.project(), runId).resolve(AgentCoordination.RECORD_FILE);
    }

    private static void echoFilters(WorkbenchCommandLine.Parsed parsed, PrintStream out) {
        out.println("tags=" + text(parsed.tags()));
        out.println("name=" + text(parsed.name()));
    }

    private static String outcomeLearned(WorkbenchCommandLine.Parsed parsed, boolean empty, String browser) {
        String supplied = parsed.coordination().learned();
        if (!empty) return supplied;
        if (supplied != null && !supplied.isBlank()) return supplied;
        return "No scenarios matched tags=" + text(parsed.tags())
                + " name=" + text(parsed.name())
                + " example=" + text(parsed.example())
                + " browser=" + text(browser);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static int inspectPage(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String html = readHtml(parsed, err);
        if (html == null && flag(parsed, "--html") != null) return 2;
        if (html != null) {
            out.println("source=offline-html");
            for (WorkbenchElementInspect.Hit hit : WorkbenchElementInspect.dump(html)) {
                printHit(out, hit.phrase(), hit.xpath(), hit.rawMatchCount(), hit.visibleCount());
            }
            return 0;
        }
        return liveQuery(parsed, null, null, out, err);
    }

    private static int findElement(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String category = flag(parsed, "--category");
        if (category == null || category.isBlank()) {
            err.println("Usage: find-element --category=<Button> [--text=<label>] [--html=<file>]");
            return 2;
        }
        String htmlFlag = flag(parsed, "--html");
        String label = parsed.coordination().text();
        if (htmlFlag != null) {
            String html = readHtml(parsed, err);
            if (html == null) return 2;
            out.println("source=offline-html");
            WorkbenchElementInspect.Hit hit = WorkbenchElementInspect.find(category, label, html);
            printHit(out, hit.phrase(), hit.xpath(), hit.rawMatchCount(), hit.visibleCount());
            return hit.xpath().isBlank() ? 1 : 0;
        }
        return liveQuery(parsed, category, label, out, err);
    }

    private static void printHit(PrintStream out, String phrase, String xpath, int raw, int visible) {
        out.println("phrase=" + phrase);
        out.println("xpath=" + xpath);
        out.println("rawMatchCount=" + raw);
        out.println("visibleCount=" + visible);
    }

    /** Live inspect and find-element poll. Still in flight after this is {@code TIMEOUT}, exit {@link #LIVE_QUERY_TIMEOUT_EXIT}. */
    static final long LIVE_QUERY_TIMEOUT_SECONDS = 30L;
    /** Distinct from exit 1 (query failed) and exit 2 (no session or bad usage). */
    static final int LIVE_QUERY_TIMEOUT_EXIT = 3;
    private static volatile long liveQueryTimeoutMillis = LIVE_QUERY_TIMEOUT_SECONDS * 1000L;

    static void setLiveQueryTimeoutMillisForTest(long millis) {
        liveQueryTimeoutMillis = millis;
    }

    static void resetLiveQueryTimeoutForTest() {
        liveQueryTimeoutMillis = LIVE_QUERY_TIMEOUT_SECONDS * 1000L;
    }

    private static int liveQuery(
            WorkbenchCommandLine.Parsed parsed,
            String category,
            String text,
            PrintStream out,
            PrintStream err
    ) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        java.util.Optional<WorkbenchSessionCommands.SessionState> session =
                WorkbenchSessionCommands.readHealthy(parsed.project(), http);
        if (session.isEmpty()) {
            err.println("start a session first");
            return 2;
        }
        String op = category == null || category.isBlank() ? "inspect-page" : "find-element";
        try {
            WorkbenchSessionCommands.SessionState state = session.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", op);
            if (category != null && !category.isBlank()) body.put("category", category);
            if (text != null && !text.isBlank()) body.put("text", text);
            JsonNode ack = postSession(http, state, "/commands", body);
            String id = ack.path("id").asText("");
            JsonNode view = ack;
            long deadline = System.nanoTime() + java.time.Duration.ofMillis(liveQueryTimeoutMillis).toNanos();
            while (id != null && !id.isBlank() && System.nanoTime() < deadline) {
                String status = view.path("status").asText("");
                if (!inFlight(status)) {
                    break;
                }
                long remainingMillis = java.time.Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (remainingMillis <= 0) {
                    break;
                }
                Thread.sleep(Math.min(100L, remainingMillis));
                if (System.nanoTime() >= deadline) {
                    break;
                }
                view = getSession(http, state, "/commands/" + id);
            }
            String status = view.path("status").asText("");
            if (inFlight(status) || "TIMEOUT".equals(status)) {
                return liveQueryTimeout(op, out, err);
            }
            JsonNode result = view.has("result") ? view.get("result") : view;
            if ("TIMEOUT".equals(result.path("status").asText(""))) {
                return liveQueryTimeout(op, out, err);
            }
            if (result.has("message") && !"SUCCESS".equals(result.path("status").asText(""))) {
                err.println(result.path("message").asText("Page query failed."));
                return 1;
            }
            out.println("source=" + result.path("source").asText("live-session"));
            JsonNode hits = result.get("hits");
            if (hits != null && hits.isArray()) {
                for (JsonNode hit : hits) {
                    printHit(
                            out,
                            hit.path("phrase").asText(""),
                            hit.path("xpath").asText(""),
                            hit.path("rawMatchCount").asInt(0),
                            hit.path("visibleCount").asInt(0)
                    );
                }
            }
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Workbench inspect was interrupted.");
            return 1;
        } catch (RuntimeException | IOException failure) {
            err.println("Workbench inspect failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int liveQueryTimeout(String op, PrintStream out, PrintStream err) {
        String budget = liveQueryTimeoutMillis % 1000L == 0
                ? (liveQueryTimeoutMillis / 1000L) + "s"
                : liveQueryTimeoutMillis + "ms";
        err.println("Workbench " + op + " TIMEOUT after " + budget);
        out.println("status=TIMEOUT");
        return LIVE_QUERY_TIMEOUT_EXIT;
    }

    private static boolean inFlight(String status) {
        return "QUEUED".equals(status)
                || "RUNNING".equals(status)
                || "STILL_WORKING".equals(status)
                || "STOPPING".equals(status);
    }

    private static JsonNode postSession(
            HttpClient http,
            WorkbenchSessionCommands.SessionState state,
            String path,
            Object body
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(state.url() + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + state.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body().isBlank() ? "{}" : response.body());
    }

    private static JsonNode getSession(
            HttpClient http,
            WorkbenchSessionCommands.SessionState state,
            String path
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(state.url() + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + state.token())
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body().isBlank() ? "{}" : response.body());
    }

    private static String readHtml(WorkbenchCommandLine.Parsed parsed, PrintStream err) {
        String raw = flag(parsed, "--html");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path file = Path.of(raw);
        if (!file.isAbsolute()) file = parsed.project().resolve(raw);
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException failure) {
            err.println("Workbench inspect failed: " + WorkbenchCliErrors.explain(failure));
            return null;
        }
    }

    private static String flag(WorkbenchCommandLine.Parsed parsed, String name) {
        String[] args = parsed.forwarded();
        if (args == null) return null;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
            if (name.equals(token) && index + 1 < args.length) return args[index + 1];
        }
        return null;
    }

    private static void printEmpty(
            WorkbenchCommandLine.Parsed parsed,
            String browser,
            PrintStream out,
            PrintStream err
    ) {
        String block = "status=EMPTY\n"
                + "tags=" + text(parsed.tags()) + "\n"
                + "name=" + text(parsed.name()) + "\n"
                + "example=" + text(parsed.example()) + "\n"
                + "browser=" + text(browser) + "\n"
                + "select=" + parsed.selects();
        out.println(block);
        err.println(block);
    }

    private static void publishQuietly(
            WorkbenchCommandLine.Parsed parsed,
            AgentCoordination.Run run,
            WorkbenchFeatureIndex.Selection selection,
            ByteArrayOutputStream capturedOut,
            ByteArrayOutputStream capturedErr,
            String status,
            String environment,
            String browser,
            PrintStream out,
            PrintStream err
    ) {
        try {
            String console = capturedOut.toString(StandardCharsets.UTF_8) + capturedErr.toString(StandardCharsets.UTF_8);
            WorkbenchRunReports.publish(
                    parsed.project(),
                    run.runId(),
                    selection == null ? List.of() : selection.hits(),
                    console,
                    status,
                    environment,
                    browser,
                    out
            );
        } catch (Exception failure) {
            err.println("Workbench summary was not written: " + failure.getMessage());
        }
    }

    private static String tagsForLaunch(
            WorkbenchFeatureIndex.Selection selection,
            String tags,
            PrintStream err
    ) {
        if (selection == null || !selection.fullScanFallback()) return tags;
        String reason = selection.fallbackReason() == null ? "" : selection.fallbackReason();
        if (!reason.contains("unsupported tag expression")) return tags;
        String original = tags == null ? "" : tags;
        err.println(WorkbenchTagFallback.warning(reason, original));
        String rewritten = WorkbenchTagFallback.cucumberTags(original);
        return rewritten == null || rewritten.isBlank() ? tags : rewritten;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String agentId(WorkbenchCommandLine.Parsed parsed) {
        return parsed.coordination().agentId();
    }

    private record Observed(String status, int exitCode, int failureCount, String failureSummary) {
        private Observed(String status, int exitCode) {
            this(status, exitCode, 0, null);
        }
    }

    /**
     * Maven {@code testFailureIgnore} can exit 0 when scenarios failed.
     * The current run's {@code run-index.json} is authoritative, including a
     * Surefire Error recorded as {@code counts.errors} or outcome {@code ERROR}.
     * Surefire XML is used only when that index was not written.
     */
    private static Observed observe(Path project, String runId, int mavenExit, boolean empty) {
        if (empty) {
            return new Observed("EMPTY", mavenExit == 0 ? 1 : mavenExit);
        }
        AgentCoordination.DiagnosticSignal signal = AgentCoordination.readDiagnosticSignal(project, runId);
        if (signal != null && (signal.failed() || "PASSED".equalsIgnoreCase(signal.outcome()))) {
            if (signal.failed()) {
                int exit = mavenExit == 0 ? Math.max(signal.failureCount(), 1) : mavenExit;
                return new Observed("FAILED", exit, Math.max(signal.failureCount(), 1), signal.failureSummary());
            }
            return new Observed("PASSED", 0);
        }
        DiagnosticVerdict verdict = surefireVerdict(project, AgentCoordination.runDirectory(project, runId).resolve(AgentCoordination.RECORD_FILE));
        if (verdict == null) {
            return new Observed(mavenExit == 0 ? "PASSED" : "FAILED", mavenExit);
        }
        if (verdict.failed()) {
            int exit = mavenExit == 0 ? Math.max(verdict.failures(), 1) : mavenExit;
            return new Observed("FAILED", exit, verdict.failures(), null);
        }
        if (verdict.passed()) {
            return new Observed("PASSED", 0);
        }
        return new Observed(mavenExit == 0 ? "PASSED" : "FAILED", mavenExit);
    }

    private record DiagnosticVerdict(String outcome, int failures) {
        boolean failed() {
            return failures > 0
                    || "FAILED".equalsIgnoreCase(outcome)
                    || "AMBIGUOUS".equalsIgnoreCase(outcome);
        }

        boolean passed() {
            return failures == 0 && "PASSED".equalsIgnoreCase(outcome);
        }
    }

    private static final Pattern SUREFIRE_FAILURES = Pattern.compile("\\bfailures=\"(\\d+)\"");
    private static final Pattern SUREFIRE_ERRORS = Pattern.compile("\\berrors=\"(\\d+)\"");

    private static DiagnosticVerdict surefireVerdict(Path project, Path recordFile) {
        Instant started = recordStarted(recordFile);
        int failures = 0;
        boolean sawReport = false;
        for (Path dir : List.of(
                project.resolve("target").resolve("surefire-reports"),
                project.resolve("build").resolve("test-results").resolve("test")
        )) {
            if (!Files.isDirectory(dir)) continue;
            try (var children = Files.list(dir)) {
                for (Path file : children.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName() == null ? "" : file.getFileName().toString();
                    if (!name.endsWith(".xml")) continue;
                    if (started != null && !surefireBelongsToRun(file, started)) continue;
                    String text;
                    try {
                        text = Files.readString(file);
                    } catch (IOException ignored) {
                        continue;
                    }
                    if (!text.contains("<testsuite") && !text.contains("<testsuites")) continue;
                    sawReport = true;
                    failures += attributeSum(SUREFIRE_FAILURES, text) + attributeSum(SUREFIRE_ERRORS, text);
                }
            } catch (IOException ignored) {
                // A missing report directory is not a result.
            }
        }
        if (!sawReport) return null;
        return new DiagnosticVerdict(failures > 0 ? "FAILED" : "PASSED", failures);
    }

    private static boolean surefireBelongsToRun(Path file, Instant started) {
        try {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return !modified.isBefore(started.minusSeconds(2));
        } catch (IOException ignored) {
            return true;
        }
    }

    private static Instant recordStarted(Path recordFile) {
        if (recordFile == null || !Files.isRegularFile(recordFile)) return null;
        try {
            JsonNode root = JSON.readTree(recordFile.toFile());
            String text = root.path("startedAt").asText("");
            if (text.isBlank()) return null;
            return Instant.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int attributeSum(Pattern pattern, String text) {
        int sum = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            sum += Integer.parseInt(matcher.group(1));
        }
        return sum;
    }

    private static int recordDiscover(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            int mavenExit,
            String runId
    ) {
        return printCatalog(
                parsed.project(), out, err, mavenExit, "workbench-discover", true, runId, agentId(parsed)
        );
    }

    private static int printCatalog(
            Path project,
            PrintStream out,
            PrintStream err,
            int mavenExit,
            String purpose,
            boolean writeSnapshot,
            String runId,
            String agentId
    ) {
        try {
            LastDiscoverSnapshot.CatalogRun latest = LastDiscoverSnapshot.catalogRun(project, purpose, runId);
            if (latest == null) latest = LastDiscoverSnapshot.latestCatalogRun(project);
            if (latest == null || latest.runProfile() == null || latest.runProfile().isBlank()) {
                err.println("Workbench finished but run-catalog.json has no retained pkb_run_profile.");
                return mavenExit == 0 ? 1 : mavenExit;
            }
            if (writeSnapshot) {
                LastDiscoverSnapshot.write(project, latest.runId(), latest.catalog(), latest.runProfile(), agentId);
            }
            out.println("run-catalog.json: " + latest.catalog());
            out.println("retained pkb_run_profile: " + latest.runProfile());
            if (writeSnapshot) {
                out.println("NEXT: confirm --tags/--name/--example. When a Workbench window is already open, drive that session with open-scenario, example, play, execute-step, stop, and diagnostic-run. Otherwise isolate, then execute-step / status / events / stop. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test.");
            }
            return mavenExit;
        } catch (Exception failure) {
            err.println("Could not read retained pkb_run_profile: " + failure.getMessage());
            return mavenExit == 0 ? 1 : mavenExit;
        }
    }

    @FunctionalInterface
    interface MavenRunner {
        int run(Path project, List<String> command, PrintStream out, PrintStream err);
    }
}
