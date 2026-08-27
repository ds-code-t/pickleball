package tools.dscode.launcher;

import tools.dscode.common.reporting.diagnostic.AgentDiscoverPlanner;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.common.reporting.diagnostic.DiagnosticCli;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;

import java.io.PrintStream;
import java.nio.file.Path;
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
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(args);
        try {
            return switch (parsed.command()) {
                case "export-guidance" -> DiagnosticCli.run(
                        new String[]{"export-guidance", parsed.outputDirectory().toString()},
                        out,
                        err
                );
                case "hint", "discover-hint" -> hint(parsed, out);
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

    private static int hint(WorkbenchCommandLine.Parsed parsed, PrintStream out) {
        AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(
                parsed.project(), parsed.tags(), parsed.name()
        );
        out.println("Recommended complete diagnostic Discover `pkb_runvars` (Workbench honors the project browser ladder; headed Chrome / pretty / @all project defaults do not sneak in):");
        out.println("pkb_runvars=" + plan.runVars());
        out.println();
        out.println("Browser: " + plan.browser().browser() + " (" + plan.browser().reason() + ").");
        out.println("Multi-scenario Discover/Confirm use this high pkb_parallel. Isolate stays one paused scenario — do not parallelize isolate.");
        out.println("After Discover, isolate and confirm replay the retained pkb_run_profile through pkb_runvars. Never supply pkb_run_profile as input.");
        out.println();
        out.println("NEXT: run discover");
        return 0;
    }

    private static int discover(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            MavenRunner maven
    ) {
        AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(
                parsed.project(), parsed.tags(), parsed.name()
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
        String runVars = AgentDiscoverPlanner.confirmRunVars(retained, parsed.tags(), parsed.name());
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
                out.println("NEXT: isolate a known failing scenario, then confirm.");
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
