package tools.dscode.launcher;

import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.reporting.diagnostic.AgentDiscoverPlanner;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                case "discover" -> discover(parsed, out, err, maven);
                case "confirm" -> confirm(parsed, out, err, maven);
                case "short-log" -> shortLog(parsed, out, err);
                case "note" -> note(parsed, out, err);
                case "inbox" -> inbox(parsed, out, err);
                case "finish" -> finish(parsed, out, err);
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
        out.println("Multi-scenario Discover/Confirm use this high pkb_parallel. When a Workbench window is already open, only one agent drives that session with open-scenario, example, play, execute-step, stop, and diagnostic-run so the person sees it. Other agents stay headless on their own run ids. Otherwise isolate starts a headless session; then use execute-step / status / events / stop. Do not start the GUI. Same launcher, only change exec.args.");
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
        out.println("Dry-run resolve (does not start tests or browsers):");
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
            List<String> command = withCoordination(
                    ConsumerMavenTestRunner.command(parsed.project(), plan.runVars()),
                    run
            );
            int exit = maven.run(parsed.project(), command, out, err);
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
            out.println("Workbench confirm replaying Discover snapshot as pkb_runvars.");
            out.println("pkb_runvars=" + runVars);
            List<String> command = withCoordination(
                    ConsumerMavenTestRunner.confirmCommand(parsed.project(), runVars),
                    run
            );
            int exit = maven.run(parsed.project(), command, out, err);
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
                out.println("NEXT: confirm --tags/--name/--example. When a Workbench window is already open, drive that session with open-scenario, example, play, execute-step, stop, and diagnostic-run. Otherwise isolate, then execute-step / status / events / stop. Do not start the GUI.");
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
