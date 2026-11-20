package tools.dscode.launcher;

import tools.dscode.common.reporting.diagnostic.ReportRetentionPolicy;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.control.protocol.AgentLog;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;
import tools.dscode.control.protocol.speed.SplitInputFingerprint;
import tools.dscode.control.protocol.speed.WarmGeneration;
import tools.dscode.testengine.DynamicSuiteBootstrap;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;
import tools.dscode.testengine.WorkspaceSeed;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final ConcurrentHashMap<String, Object> COMPILE_LOCKS = new ConcurrentHashMap<>();

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
                path -> capture(project, path, false, warnings),
                warnings
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
                Map<String, String> before = Map.copyOf(values);
                values.put(PKB_props.PKB_REPORT_RETENTION, parsed);
                Workspace.writeRunVars(workspace, values);
                markChanged(workspace, before, values, "cli/override");
            }
        }
        defaultDiagnosticReporting(workspace);
        Map<String, String> beforeEdits = Workspace.readRunVars(workspace);
        Workspace.applyLaunchEdits(workspace, selection, cliRunVars, warnings);
        markChanged(workspace, beforeEdits, Workspace.readRunVars(workspace), "cli/override");
        stamp(workspace);
        return new Prepared(workspace, Map.copyOf(selection));
    }

    /**
     * Prefer {@code java -cp} of the synced consumer classpath when that file
     * still starts with live classes. Otherwise use the pre-sync seed classpath.
     * Tests inject {@link #testCapture}. In-process capture is only the fallback
     * when neither list exists and this JVM can see the consumer runner.
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
        if (fallback && buildProject(project)) {
            stage(workspace, "compile", "start");
            boolean compiled = warmClasspath(project, workspace == null ? null : workspace.getParent(), warnings);
            stage(workspace, "compile", compiled ? "exit=0" : "exit=failed");
            if (compiled) {
                forkSeed(project, workspace, reseed, cachedClasspath(project), warnings);
                return;
            }
        }
        String message = defaultSuiteWarning(runner == null ? null : runner.getClass());
        if (message != null) warn(warnings, message);
        WorkspaceSeed.writeCaptured(workspace, runner, reseed);
    }

    private static boolean buildProject(Path project) {
        if (project == null) return false;
        Path root = project.toAbsolutePath().normalize();
        return Files.isRegularFile(root.resolve("pom.xml"))
                || Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"));
    }

    /**
     * Compile test classes, then publish a seed-fork classpath so the seed
     * JVM sees the consumer runner. The launcher JVM does not.
     * <p>
     * That list is {@code seed-classpath.txt}. {@code classpath.txt} is the
     * session worker classpath and always starts with live classes. Sync is
     * the only writer of that file; this method does not replace it.
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
        if (!hasConsumerOutput(root, entries)) return false;
        try {
            publishSeedClasspath(root, entries);
        } catch (IOException failure) {
            warn(warnings, "WARNING: could not write seed-classpath.txt: " + failure.getMessage());
            return false;
        }
        return !cachedClasspath(root).isEmpty();
    }

    /**
     * Cache hit republishes the seed classpath and does not call the wrapper.
     * A miss compiles, then stores the resolved list under the same hash.
     */
    static boolean warmClasspath(Path project, Path runFolder, Appendable warnings) {
        if (testCapture != null) return true;
        if (project == null) return false;
        Path root = project.toAbsolutePath().normalize();
        synchronized (COMPILE_LOCKS.computeIfAbsent(root.toString(), key -> new Object())) {
            return warmClasspathLocked(root, runFolder, warnings);
        }
    }

    /**
     * A cache hit compares split fingerprints under {@code warm/sync.lock}.
     * Build or Java changes compile. Resource changes process test resources.
     * An unchanged tree skips the wrapper. Either way the warm JVM is pinned
     * to a copied generation, not to {@code target/}.
     */
    private static boolean warmClasspathLocked(Path root, Path runFolder, Appendable warnings) {
        Path state = PickleballLocalLayout.workbenchStateRoot(root);
        try (WarmGeneration.Lock ignored = WarmGeneration.Lock.open(state)) {
            String hash = InputFingerprint.lockHash(root);
            ClasspathCache.Hit hit = ClasspathCache.load(state, hash);
            if (hit.hit() && ClasspathCache.hasProjectOutput(root, hit.entries())) {
                if (syncCachedHit(root, state, runFolder, warnings, hit)) return true;
            }
            WorkbenchLaunchTiming.note(runFolder, "compile", "start");
            boolean compiled = compileAndPublishClasspath(root, warnings);
            WorkbenchLaunchTiming.note(runFolder, "compile", compiled ? "exit=0" : "exit=failed");
            if (compiled) {
                List<String> seed = readClasspathLines(state.resolve(PickleballLocalLayout.SEED_CLASSPATH_FILE));
                WorkbenchSpeed.remember(root, seed.isEmpty() ? cachedClasspath(root) : seed);
                publishGeneration(root, state, runFolder, null, null);
            }
            return compiled;
        } catch (IOException failure) {
            warn(warnings, "WARNING: classpath sync lock failed: " + failure.getMessage());
            return false;
        }
    }

    private static boolean syncCachedHit(
            Path root,
            Path state,
            Path runFolder,
            Appendable warnings,
            ClasspathCache.Hit hit
    ) {
        SplitInputFingerprint.Observation observed = SplitInputFingerprint.observe(root, state);
        SplitInputFingerprint.Mode mode = SplitInputFingerprint.decide(
                SplitInputFingerprint.load(state), observed.parts()
        );
        boolean ran = switch (mode) {
            case SKIP -> true;
            case RESOURCES -> runProcessTestResources(root, runFolder, warnings);
            case FULL -> runTestCompile(root, warnings);
        };
        if (!ran) return false;
        try {
            publishSeedClasspath(root, hit.entries());
            publishGeneration(root, state, runFolder, mode.wire, observed);
            WorkbenchLaunchTiming.note(runFolder, "compile", "cache-hit");
            return true;
        } catch (IOException failure) {
            warn(warnings, "WARNING: classpath cache was not published: " + failure.getMessage());
            return false;
        }
    }

    private static void publishGeneration(
            Path root,
            Path state,
            Path runFolder,
            String mode,
            SplitInputFingerprint.Observation observed
    ) {
        try {
            SplitInputFingerprint.Observation snapshot = observed == null
                    ? SplitInputFingerprint.observe(root, state)
                    : observed;
            SplitInputFingerprint.store(state, snapshot);
            WarmGeneration.Snapshot copy = WarmGeneration.materialize(root, state, snapshot.parts());
            if (mode != null) noteSync(root, runFolder, mode, copy);
            WarmGeneration.gc(state, copy.hash());
        } catch (IOException failure) {
            // The wrapper path still has target/. A later hit tries the copy again.
        }
    }

    private static void noteSync(Path project, Path runFolder, String mode, WarmGeneration.Snapshot snapshot) {
        WorkbenchLaunchTiming.note(runFolder, "sync mode=" + mode, "");
        if (snapshot != null && snapshot.hash() != null) {
            WorkbenchLaunchTiming.note(
                    runFolder,
                    "materialize gen=" + snapshot.hash()
                            + " files=" + snapshot.files()
                            + " bytes=" + snapshot.bytes()
                            + " ms=" + snapshot.millis(),
                    ""
            );
        }
        try {
            String runId = runFolder == null || runFolder.getFileName() == null ? "-" : runFolder.getFileName().toString();
            String purpose = "mode=" + mode + (snapshot == null || snapshot.hash() == null ? "" : " gen=" + snapshot.hash());
            AgentLog.append(project, Instant.now(), "launcher", runId, "sync", purpose, null);
        } catch (IOException ignored) {
            // The launch-timing line is the record the next command reads.
        }
    }

    /** {@code process-test-resources} / {@code processTestResources}, offline first, then once online. */
    private static boolean runProcessTestResources(Path root, Path runFolder, Appendable warnings) {
        try {
            List<String> command = tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.processTestResourcesCommand(root, true);
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            PrintStream quiet = new PrintStream(sink, true, StandardCharsets.UTF_8);
            int exit = tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.run(root, command, quiet, quiet);
            String text = sink.toString(StandardCharsets.UTF_8);
            if (exit != 0 && tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.offlineResolutionFailure(text)) {
                WorkbenchLaunchTiming.note(runFolder, "maven-retry-online", "offlineExit=" + exit);
                sink.reset();
                exit = tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.run(
                        root,
                        tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner.withoutOffline(command),
                        quiet,
                        quiet
                );
            }
            if (exit != 0) {
                warn(warnings, "WARNING: process-test-resources exited " + exit + ".");
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            warn(warnings, "WARNING: process-test-resources did not run: " + failure.getMessage());
            return false;
        }
    }

    /** Java inputs changed. Compile tests. Do not resolve dependencies again. */
    private static boolean runTestCompile(Path root, Appendable warnings) {
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
            return true;
        } catch (RuntimeException failure) {
            warn(warnings, "WARNING: test-compile did not run: " + failure.getMessage());
            return false;
        }
    }

    /**
     * Writes the pre-sync seed classpath and leaves {@code classpath.txt} alone.
     * A live-first session file stays the worker classpath.
     */
    static void publishSeedClasspath(Path project, List<String> entries) throws IOException {
        synchronized (WorkspaceLaunch.class) {
            Path file = PickleballLocalLayout.workbenchStateRoot(project)
                    .resolve(PickleballLocalLayout.SEED_CLASSPATH_FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", entries) + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * A launcher code-source by itself is not a consumer classpath. The seed
     * fork needs an output that lives in the consumer project.
     */
    private static boolean hasConsumerOutput(Path root, List<String> entries) {
        if (entries == null || entries.isEmpty()) return false;
        Path base = root.toAbsolutePath().normalize();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            Path path = Path.of(entry);
            if (!path.isAbsolute()) path = base.resolve(path);
            if (path.toAbsolutePath().normalize().startsWith(base)) return true;
        }
        return false;
    }

    static List<String> cachedClasspath(Path project) {
        if (project == null) return List.of();
        Path state = PickleballLocalLayout.workbenchStateRoot(project);
        List<String> session = readClasspathLines(state.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE));
        if (PickleballLocalLayout.startsWithLiveClasses(state, session)) return session;
        List<String> seed = readClasspathLines(state.resolve(PickleballLocalLayout.SEED_CLASSPATH_FILE));
        if (!seed.isEmpty()) return seed;
        return session;
    }

    private static List<String> readClasspathLines(Path file) {
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
        stage(workspace, "seed-fork", "start");
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
                stage(workspace, "seed-fork", "exit=" + exit);
                throw new IllegalStateException(
                        "Workspace seed failed for " + workspace + " exit=" + exit
                                + (output.isBlank() ? "" : System.lineSeparator() + output)
                );
            }
            stage(workspace, "seed-fork", "exit=0");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stage(workspace, "seed-fork", "exit=failed");
            throw new IllegalStateException("Workspace seed was interrupted for " + workspace, interrupted);
        } catch (IOException failure) {
            stage(workspace, "seed-fork", "exit=failed");
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
        markChanged(workspace, Map.of(), Map.of(PKB_props.PKB_REPORTING_MODE, "diagnostic"), "default");
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

    private static void stage(Path workspace, String name, String detail) {
        Path folder = workspace == null ? null : workspace.getParent();
        WorkbenchLaunchTiming.note(folder, name, detail);
    }

    private static void markChanged(
            Path workspace,
            Map<String, String> before,
            Map<String, String> after,
            String source
    ) {
        List<String> changed = new ArrayList<>();
        if (after != null) {
            after.forEach((key, value) -> {
                String previous = before == null ? null : before.get(key);
                if (previous == null || !previous.equals(value)) changed.add(key);
            });
        }
        if (!changed.isEmpty()) Workspace.markValueSources(workspace, changed, source);
    }
}
