package tools.dscode.launcher;

import tools.dscode.common.reporting.diagnostic.AgentDiscoverPlanner;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
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
                parsed.project(), parsed.tags(), parsed.name(), parsed.retention()
        );
        out.println("Recommended complete diagnostic Discover `pkb_runvars` (Workbench honors the project browser ladder; headed Chrome / pretty / @all project defaults do not sneak in):");
        out.println("pkb_runvars=" + plan.runVars());
        out.println();
        printDryRunResolve(parsed.project(), plan.runVars(), false, out);
        out.println("Browser: " + plan.browser().browser() + " (" + plan.browser().reason() + ").");
        out.println("Multi-scenario Discover/Confirm use this high pkb_parallel. Live isolate starts a headless Workbench session; then use execute-step / status / events / stop. Same launcher, only change exec.args.");
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
        AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(
                parsed.project(), parsed.tags(), parsed.name(), parsed.retention()
        );
        out.println("Workbench discover " + plan.browser().reason() + ".");
        out.println("pkb_runvars=" + plan.runVars());
        List<String> command = ConsumerMavenTestRunner.command(parsed.project(), plan.runVars());
        int exit = maven.run(parsed.project(), command, out, err);
        return recordDiscover(parsed.project(), out, err, exit);
    }

    private static int confirm(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            MavenRunner maven
    ) {
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.require(parsed.project());
        Map<String, String> retained = LastDiscoverSnapshot.retainedRunVars(snapshot);
        String runVars = AgentDiscoverPlanner.confirmRunVars(
                retained, parsed.tags(), parsed.name(), parsed.retention()
        );
        out.println("Workbench confirm replaying Discover snapshot as pkb_runvars.");
        out.println("pkb_runvars=" + runVars);
        List<String> command = ConsumerMavenTestRunner.confirmCommand(parsed.project(), runVars);
        int exit = maven.run(parsed.project(), command, out, err);
        return printCatalog(parsed.project(), out, err, exit, "workbench-confirm", false);
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
                out.println("NEXT: confirm --tags/--name. For live debug: isolate (starts session), then execute-step / status / events / stop.");
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
