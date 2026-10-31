package tools.dscode.launcher;

import tools.dscode.common.reporting.diagnostic.ReportRetentionPolicy;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.DynamicSuiteBootstrap;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;
import tools.dscode.testengine.WorkspaceSeed;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One launch preparation for every Workbench mode.
 * Seed once, edit the workspace for non-selection overrides, then pass
 * {@code -Dpkb_workspace} and selection keys only.
 */
public final class WorkspaceLaunch {
    /**
     * Framework tests set this because the suite has more than one runner.
     * Production leaves it null and captures the consumer runner.
     */
    public static volatile Consumer<Path> testCapture;

    private WorkspaceLaunch() {
    }

    public record Prepared(Path workspace, Map<String, String> selection) {
    }

    public static Prepared prepare(
            Path project,
            String runId,
            String fromRunId,
            String tags,
            String name,
            String example,
            String retention,
            List<String> cliRunVars,
            Appendable warnings
    ) {
        return prepare(project, runId, fromRunId, tags, name, example, null, null, retention, cliRunVars, warnings);
    }

    public static Prepared prepare(
            Path project,
            String runId,
            String fromRunId,
            String tags,
            String name,
            String example,
            String runIdProperty,
            String agentId,
            String retention,
            List<String> cliRunVars,
            Appendable warnings
    ) {
        Path own = Workspace.directory(project, runId);
        boolean copy = fromRunId != null && !fromRunId.isBlank() && !Workspace.exists(own);
        Path workspace = WorkspaceSeed.seed(
                project,
                runId,
                false,
                copy ? fromRunId : null,
                null,
                path -> capture(project, path, false, warnings)
        );
        Map<String, String> selection = new LinkedHashMap<>();
        put(selection, PKB_props.PKB_TAGS, tags);
        put(selection, PKB_props.PKB_NAME, name);
        put(selection, PKB_props.PKB_EXAMPLE, example);
        put(selection, PKB_props.PKB_RUN_ID, runIdProperty == null ? runId : runIdProperty);
        put(selection, PKB_props.PKB_AGENT_ID, agentId);
        if (retention != null && !retention.isBlank()) {
            String parsed = ReportRetentionPolicy.parseExact(retention).name().toLowerCase(Locale.ROOT);
            Map<String, String> values = new LinkedHashMap<>(Workspace.readRunVars(workspace));
            String existing = values.get(PKB_props.PKB_REPORT_RETENTION);
            if (existing != null && !existing.equals(parsed)) {
                warn(warnings, "WARNING: --runvar " + PKB_props.PKB_REPORT_RETENTION
                        + " replaces " + Workspace.shown(existing) + " with " + Workspace.shown(parsed));
            }
            if (!parsed.equals(existing)) {
                values.put(PKB_props.PKB_REPORT_RETENTION, parsed);
                Workspace.writeRunVars(workspace, values);
            }
        }
        defaultDiagnosticReporting(workspace);
        Workspace.applyLaunchEdits(workspace, selection, cliRunVars, warnings);
        stamp(workspace);
        return new Prepared(workspace, Map.copyOf(selection));
    }

    /**
     * Prefer {@code java -cp} of the synced consumer classpath. Tests inject
     * {@link #testCapture}. In-process capture is only the fallback when sync
     * has not written a classpath yet and this JVM can see the consumer runner.
     */
    static void capture(Path project, Path workspace, boolean reseed, Appendable warnings) {
        Consumer<Path> hook = testCapture;
        if (hook != null) {
            hook.accept(workspace);
            if (!Workspace.exists(workspace)) {
                throw new IllegalStateException("Workspace seed did not write " + workspace);
            }
            return;
        }
        List<String> classpath = cachedClasspath(project);
        if (!classpath.isEmpty()) {
            forkSeed(project, workspace, reseed, classpath, warnings);
            return;
        }
        PickleballRunner runner = DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
        boolean fallback = DynamicSuiteBootstrap.isDefaultSuite(runner == null ? null : runner.getClass());
        if (fallback && compileAndPublishClasspath(project, warnings)) {
            forkSeed(project, workspace, reseed, cachedClasspath(project), warnings);
            return;
        }
        String message = defaultSuiteWarning(runner == null ? null : runner.getClass());
        if (message != null) warn(warnings, message);
        for (String warning : WorkspaceSeed.writeCaptured(workspace, runner, reseed)) {
            warn(warnings, warning);
        }
    }

    /**
     * Compile test classes, then publish a worker classpath so the seed fork
     * sees the consumer runner. The launcher JVM does not.
     */
    private static boolean compileAndPublishClasspath(Path project, Appendable warnings) {
        if (project == null) return false;
        Path root = project.toAbsolutePath().normalize();
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"));
        if (!maven && !gradle) return false;
        try {
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            PrintStream quiet = new PrintStream(sink, true, StandardCharsets.UTF_8);
            int exit = tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.run(
                    root,
                    tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.compileCommand(root),
                    quiet,
                    quiet
            );
            if (exit != 0) {
                warn(warnings, "WARNING: test-compile exited " + exit + ".");
                return false;
            }
        } catch (RuntimeException failure) {
            warn(warnings, "WARNING: test-compile did not run: " + failure.getMessage());
            return false;
        }
        List<String> entries = WorkbenchOneShotLaunch.testRuntimeEntries(root, warnings);
        if (entries.isEmpty()) return false;
        Path file = PickleballLocalLayout.workbenchStateRoot(root).resolve("classpath.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", entries) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            warn(warnings, "WARNING: could not write classpath.txt: " + failure.getMessage());
            return false;
        }
        return !cachedClasspath(root).isEmpty();
    }

    static List<String> cachedClasspath(Path project) {
        if (project == null) return List.of();
        Path file = PickleballLocalLayout.workbenchStateRoot(project).resolve("classpath.txt");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Workbench classpath: " + file, failure);
        }
    }

    private static void forkSeed(Path project, Path workspace, boolean reseed, List<String> classpath, Appendable warnings) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, classpath));
        command.add(ControlProtocol.SEED_MAIN_CLASS);
        command.add("--project=" + project.toAbsolutePath().normalize());
        command.add("--workspace=" + workspace.toAbsolutePath().normalize());
        if (reseed) command.add("--reseed");
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0 || !Workspace.exists(workspace)) {
                throw new IllegalStateException(
                        "Workspace seed failed for " + workspace + " exit=" + exit
                                + (output.isBlank() ? "" : System.lineSeparator() + output)
                );
            }
            for (String line : output.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("WARNING:") || trimmed.startsWith("WARN:")) warn(warnings, trimmed);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace seed was interrupted for " + workspace, interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not seed workspace " + workspace, failure);
        }
    }

    /**
     * Discover's catalog is the diagnostic run index. A blank saved
     * {@code pkb_reportingmode} becomes {@code diagnostic} once, here, before
     * {@code --runvar} edits. An explicit value, including {@code normal}, stays.
     * This is not a second resolver and does not reapply hooks.
     */
    private static void defaultDiagnosticReporting(Path workspace) {
        Map<String, String> values = new LinkedHashMap<>(Workspace.readRunVars(workspace));
        String reporting = values.get(PKB_props.PKB_REPORTING_MODE);
        if (reporting != null && !reporting.isBlank()) return;
        values.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");
        Workspace.writeRunVars(workspace, values);
    }

    /** Warning text when seed capture used the built-in DefaultDynamicSuite, else null. */
    static String defaultSuiteWarning(Class<?> runnerType) {
        if (!DynamicSuiteBootstrap.isDefaultSuite(runnerType)) return null;
        return "WARNING: workspace seed found no consumer runner and fell back to DefaultDynamicSuite, "
                + "so runner hook defaults were not captured. Compile the tests (mvn test-compile) "
                + "so the consumer PickleballRunner subclass is in target/test-classes, "
                + "then run workspace-seed --reseed.";
    }

    private static void warn(Appendable warnings, String message) {
        if (warnings == null) return;
        try {
            warnings.append(message).append(System.lineSeparator());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void put(Map<String, String> selection, String key, String value) {
        if (value != null && !value.isBlank()) selection.put(key, value.trim());
    }

    private static void stamp(Path workspace) {
        Path record = workspace.getParent() == null ? null : workspace.getParent().resolve("record.json");
        if (record != null && Files.isRegularFile(record)) {
            Workspace.writeFingerprint(record, Workspace.fingerprint(workspace));
        }
    }
}
