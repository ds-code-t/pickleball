package tools.dscode.launcher;

import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.reporting.diagnostic.AgentDiscoverPlanner;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;
import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Agent-facing Workbench verbs that run in the consumer JVM (not the controller JAR). */
public final class WorkbenchAgentCommands {
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
                case "discover" -> parsed.planOnly() ? plan(parsed, out, err) : discover(parsed, out, err, maven);
                case "confirm" -> parsed.planOnly() ? plan(parsed, out, err) : confirm(parsed, out, err, maven);
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
                default -> {
                    err.println("Unknown Workbench agent command: " + parsed.command());
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("Workbench " + parsed.command() + " failed: " + failure.getMessage());
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
        AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(
                parsed.project(), parsed.tags(), parsed.name(), parsed.retention(), parsed.example()
        );
        out.println("Recommended complete diagnostic Discover `pkb_runvars` (Workbench honors the project browser ladder; headed Chrome / pretty / @all project defaults do not sneak in):");
        out.println("pkb_runvars=" + plan.runVars());
        out.println();
        printDryRunResolve(parsed.project(), plan.runVars(), false, out);
        out.println("Browser: " + plan.browser().browser() + " (" + plan.browser().reason() + ").");
        out.println("Multi-scenario Discover/Confirm use this high pkb_parallel. When a Workbench window is already open, only one agent drives that session with open-scenario, example, play, execute-step, stop, and diagnostic-run so the person sees it. Other agents stay headless on their own run ids. Otherwise isolate starts a headless session; then use execute-step / status / events / stop. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Same launcher, only change exec.args.");
        out.println("Read the short log (.pickleball/agent-log) before and after a run, and during a long session, instead of the dense diagnostic log.");
        out.println("After Discover, confirm (and isolate/execute-step for live debug) replay the retained pkb_run_profile through pkb_runvars. Never supply pkb_run_profile as input.");
        out.println("Sealed runs are opt-in: resolve → inspect → complete map including the six context keys → pkb_overriderunvars. Do not mix with pkb_runvars or pkb_profile. Compare runProfileFingerprint after the sealed run.");
        out.println();
        out.println("NEXT: run discover");
        return 0;
    }

    private static int resolveRunVars(WorkbenchCommandLine.Parsed parsed, PrintStream out) {
        printDryRunResolve(parsed.project(), null, true, out);
        return 0;
    }

    private static void printDryRunResolve(
            Path project,
            String recommendedRunVars,
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
        if (recommendedRunVars != null && !recommendedRunVars.isBlank()) {
            values.put(PKB_props.PKB_RUN_VARS, recommendedRunVars);
        }
        PKB_props.ResolvedRunVars resolved = PKB_props.resolveRunVars(values, jvm);
        out.println("Dry-run resolve of the launcher JVM, not the Discover worker (does not start tests, browsers, or Discover):");
        out.println("Do not treat resolve-runvars as the environment Discover will use. Trust the environment on the run record after Discover.");
        out.println("sealed=" + resolved.sealed());
        out.println("pkb_run_profile=" + resolved.runProfile());
        out.println("runProfileFingerprint=" + resolved.fingerprint());
        out.println("provenance=" + resolved.provenance());
        out.println("Sealed launch uses -Dpkb_overriderunvars=<complete compact map>, never -Dpkb_run_profile=.");
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
            AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(
                    parsed.project(), parsed.tags(), parsed.name(), parsed.retention(), parsed.example()
            );
            out.println("Workbench discover " + plan.browser().reason() + ".");
            out.println("pkb_runvars=" + plan.runVars());
            WorkbenchFeatureIndex.Selection selection = select(
                    parsed.project(), parsed.tags(), parsed.name(), parsed.example(), parsed
            );
            List<String> command = withCoordination(
                    ConsumerMavenTestRunner.command(
                            parsed.project(),
                            plan.runVars(),
                            featurePaths(parsed.project(), selection)
                    ),
                    run
            );
            Bounded mavenResult = runBounded(parsed, command, out, err, maven);
            if (mavenResult.timedOut()) {
                return timeoutExit(parsed, run, out, err);
            }
            int exit = mavenResult.exit();
            AgentCoordination.finish(
                    parsed.project(),
                    run.runId(),
                    parsed.coordination().learned(),
                    exit == 0 ? "PASSED" : "FAILED",
                    java.time.Instant.now(),
                    out
            );
            return recordDiscover(parsed.project(), out, err, exit);
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
            LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.require(parsed.project());
            Map<String, String> retained = LastDiscoverSnapshot.retainedRunVars(snapshot);
            String runVars = AgentDiscoverPlanner.confirmRunVars(
                    retained, parsed.tags(), parsed.name(), parsed.retention(), parsed.example()
            );
            WorkbenchFeatureIndex.Selection selection = select(
                    parsed.project(),
                    firstNonBlank(parsed.tags(), retained.get(PKB_props.PKB_TAGS)),
                    firstNonBlank(parsed.name(), retained.get(PKB_props.PKB_NAME)),
                    firstNonBlank(parsed.example(), retained.get(PKB_props.PKB_EXAMPLE)),
                    parsed
            );
            out.println("Workbench confirm replaying Discover snapshot as pkb_runvars.");
            out.println("pkb_runvars=" + runVars);
            List<String> command = withCoordination(
                    ConsumerMavenTestRunner.confirmCommand(
                            parsed.project(),
                            runVars,
                            featurePaths(parsed.project(), selection)
                    ),
                    run
            );
            Bounded mavenResult = runBounded(parsed, command, out, err, maven);
            if (mavenResult.timedOut()) {
                return timeoutExit(parsed, run, out, err);
            }
            int exit = mavenResult.exit();
            AgentCoordination.finish(
                    parsed.project(),
                    run.runId(),
                    parsed.coordination().learned(),
                    exit == 0 ? "PASSED" : "FAILED",
                    java.time.Instant.now(),
                    out
            );
            return printCatalog(parsed.project(), out, err, exit, "workbench-confirm", false);
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

    private static Long runBudgetSeconds(WorkbenchCommandLine.Parsed parsed) {
        Long step = parsed.stepTimeoutSeconds();
        Long run = parsed.runTimeoutSeconds();
        if (step == null) return run;
        if (run == null) return step;
        return Math.min(step, run);
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
        if (selection.fullScanFallback()) {
            err.println("WARNING: full-scan fallback: " + selection.fallbackReason());
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
        copy.addAll(AgentCoordination.mavenProperties(run));
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

    private static int recordDiscover(Path project, PrintStream out, PrintStream err, int mavenExit) {
        return printCatalog(project, out, err, mavenExit, "workbench-discover", true);
    }

    private static int printCatalog(
            Path project,
            PrintStream out,
            PrintStream err,
            int mavenExit,
            String purpose,
            boolean writeSnapshot
    ) {
        try {
            LastDiscoverSnapshot.CatalogRun latest = LastDiscoverSnapshot.latestCatalogRun(project, purpose);
            if (latest == null) latest = LastDiscoverSnapshot.latestCatalogRun(project);
            if (latest == null || latest.runProfile() == null || latest.runProfile().isBlank()) {
                err.println("Workbench finished but run-catalog.json has no retained pkb_run_profile.");
                return mavenExit == 0 ? 1 : mavenExit;
            }
            if (writeSnapshot) {
                LastDiscoverSnapshot.write(project, latest.runId(), latest.catalog(), latest.runProfile());
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
