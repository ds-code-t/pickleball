package tools.dscode.workbench.worker;

import tools.dscode.control.protocol.ControlBridgeBreakpoint;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.ControlBridgeScenarioStatus;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.workbench.bridge.ControlBridgeClient;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;
import tools.dscode.workbench.terminal.WorkerLogFiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns one persistent interactive consumer worker for a Workbench controller session. */
public final class WorkbenchWorkerManager implements AutoCloseable {
    static final int PAUSE_LEASE_SECONDS = 120;
    static final int LEASE_RENEW_SECONDS = 30;
    static final String INTERACTIVE_PAUSE_HOOK = "BEFORE_STEP";
    static final String INTERACTIVE_PAUSE_STEP = "---pickleball-workbench-anchor";
    static final Duration START_TIMEOUT = Duration.ofSeconds(180);
    static final String START_TIMEOUT_PROPERTY = "pkb_workbench_worker_start_timeout";
    static final String START_TIMEOUT_ENV = "PKB_WORKBENCH_WORKER_START_TIMEOUT";
    static final String START_TIMEOUT_RUNVAR = "pkb_runvars.pkb_workbench_worker_start_timeout";
    static final List<String> STARTUP_STAGES = List.of(
            "compile", "classpath", "worker-launch", "anchor-parse", "paused"
    );
    static final Duration GRACEFUL_STOP_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TERMINATE_TIMEOUT = Duration.ofSeconds(5);

    private final Path projectRoot;
    private final Map<String, String> workerSystemProperties;
    private final SecureRandom random = new SecureRandom();
    private WorkerSession active;

    public WorkbenchWorkerManager(Path projectRoot) {
        this(projectRoot, Map.of());
    }

    public WorkbenchWorkerManager(Path projectRoot, Map<String, String> workerSystemProperties) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.workerSystemProperties = Map.copyOf(workerSystemProperties);
    }

    public synchronized WorkbenchWorkerStatus startInteractive() {
        if (active != null && active.process().isAlive()) {
            throw new IllegalStateException("Workbench already owns an active interactive worker for " + projectRoot);
        }
        active = null;

        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        String sessionId = UUID.randomUUID().toString();
        String token = token();
        Path sessionDirectory = stateRoot.resolve("sessions").resolve(sessionId);
        Path anchor = sessionDirectory.resolve("anchor.feature");
        Path stdout = stateRoot.resolve("logs").resolve("worker-" + sessionId + ".out.log");
        Path stderr = stateRoot.resolve("logs").resolve("worker-" + sessionId + ".err.log");
        createDirectories(sessionDirectory, stdout.getParent());
        writeProgress(stderr, "compile");
        WorkbenchManifest manifest = WorkbenchManifest.read(projectRoot);
        List<String> classpath = WorkbenchSynchronizer.readWorkerClasspath(projectRoot);
        writeProgress(stderr, "classpath");
        writeAnchor(anchor);

        Map<String, String> properties = workerSystemProperties.isEmpty()
                ? tools.dscode.workbench.discover.LastDiscoverSnapshot.workerSystemPropertiesIfPresent(projectRoot)
                : workerSystemProperties;
        ensureSeed(projectRoot, classpath, properties);
        ProcessBuilder builder = new ProcessBuilder(workerCommand(
                manifest, classpath, anchor, properties
        ))
                .directory(projectRoot.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(stderr.toFile()));
        builder.environment().put(ControlProtocol.SESSION_DIRECTORY_ENV, sessionDirectory.toString());
        builder.environment().put(ControlProtocol.SESSION_ID_ENV, sessionId);
        builder.environment().put(ControlProtocol.SESSION_TOKEN_ENV, token);
        builder.environment().put(ControlProtocol.PAUSE_FIRST_SCENARIO_ENV, "true");

        Process process = null;
        long started = System.nanoTime();
        String stage = "worker-launch";
        try {
            writeProgress(stderr, stage);
            process = builder.start();
            WorkerSession session = awaitBridge(
                    process, sessionId, token, sessionDirectory, stdout, stderr, manifest, classpath,
                    started
            );
            active = session;
            scheduleLeaseRenewal(session);
            return status();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start Workbench consumer worker.", failure);
        } catch (RuntimeException failure) {
            if (active != null) {
                stop();
            } else if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            throw failure;
        }
    }

    public synchronized WorkbenchWorkerStatus restartInteractive() {
        WorkerSession previous = active;
        WorkbenchWorkerStatus stopped = stop();
        if (stopped.exitCode() != null && stopped.exitCode() != 0) {
            throw new IllegalStateException(
                    "Previous Workbench worker did not stop gracefully. exit=" + stopped.exitCode()
                            + (previous == null ? "" : ", stdout=" + previous.stdout() + ", stderr=" + previous.stderr())
            );
        }
        return startInteractive();
    }

    public synchronized WorkbenchWorkerStatus status() {
        WorkerSession session = active;
        if (session == null) {
            return new WorkbenchWorkerStatus(false, null, null, null, null, false, null);
        }
        boolean running = session.process().isAlive();
        Integer exitCode = running ? null : session.process().exitValue();
        boolean paused = false;
        if (running) {
            try {
                paused = session.client().scenarios().stream()
                        .filter(scenario -> session.scenarioId().equals(scenario.scenarioId()))
                        .findFirst()
                        .map(ControlBridgeScenarioStatus::paused)
                        .orElse(false);
            } catch (RuntimeException ignored) {
                // A worker may be between resume and normal process exit.
            }
        }
        return new WorkbenchWorkerStatus(
                running,
                session.sessionId(),
                session.process().pid(),
                session.client().descriptor().runtimeId(),
                session.scenarioId(),
                paused,
                exitCode
        );
    }

    synchronized ControlBridgeClient activeClient() {
        return requireActive().client();
    }

    synchronized String activeScenarioId() {
        return requireActive().scenarioId();
    }

    public synchronized Optional<WorkerLogFiles> workerLogFiles() {
        WorkerSession session = active;
        if (session == null) return Optional.empty();
        return Optional.of(new WorkerLogFiles(session.stdout(), session.stderr()));
    }

    public synchronized WorkbenchWorkerStatus stop() {
        WorkerSession session = active;
        active = null;
        if (session == null) {
            return new WorkbenchWorkerStatus(false, null, null, null, null, false, null);
        }

        stopLeaseRenewal(session.renewal());
        if (session.process().isAlive()) {
            try {
                session.client().resume(session.scenarioId());
            } catch (RuntimeException ignored) {
                // Continue to bounded process shutdown when the bridge is already gone.
            }

            if (!awaitExit(session.process(), GRACEFUL_STOP_TIMEOUT)) {
                session.process().destroy();
                if (!awaitExit(session.process(), TERMINATE_TIMEOUT)) {
                    session.process().destroyForcibly();
                    awaitExit(session.process(), TERMINATE_TIMEOUT);
                }
            }
        }
        return stoppedStatus(session);
    }

    @Override
    public void close() {
        stop();
    }

    private WorkerSession requireActive() {
        WorkerSession session = active;
        if (session == null || !session.process().isAlive()) {
            throw new IllegalStateException("Workbench does not own an active interactive worker for " + projectRoot);
        }
        return session;
    }

    private static WorkbenchWorkerStatus stoppedStatus(WorkerSession session) {
        boolean running = session.process().isAlive();
        Integer exitCode = running ? null : session.process().exitValue();
        return new WorkbenchWorkerStatus(
                running,
                session.sessionId(),
                session.process().pid(),
                session.client().descriptor().runtimeId(),
                session.scenarioId(),
                false,
                exitCode
        );
    }

    static List<String> workerCommand(
            WorkbenchManifest manifest,
            List<String> classpath,
            Path anchorFeature
    ) {
        return workerCommand(manifest, classpath, anchorFeature, Map.of());
    }

    static List<String> workerCommand(
            WorkbenchManifest manifest,
            List<String> classpath,
            Path anchorFeature,
            Map<String, String> systemProperties
    ) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY
                + "=" + manifest.liveOutputPath());
        systemProperties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> command.add("-D" + entry.getKey() + "=" + entry.getValue()));
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(ControlProtocol.WORKER_MAIN_CLASS);
        command.add("--tags");
        command.add("@pickleball-workbench-anchor");
        command.add(anchorFeature.toAbsolutePath().normalize().toUri().toString());
        return List.copyOf(command);
    }

    /** Runs the resolve-only main when a launch names a workspace that is not on disk yet. */
    static void ensureSeed(Path project, List<String> classpath, Map<String, String> properties) {
        if (properties == null) return;
        String configured = properties.get(Workspace.PROPERTY);
        if (configured == null || configured.isBlank() || Workspace.exists(Path.of(configured))) return;
        Path workspace = Path.of(configured).toAbsolutePath().normalize();
        Path parent = workspace.getParent();
        if (parent == null || parent.getFileName() == null) {
            throw new IllegalStateException("Workspace path has no run id: " + workspace);
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(ControlProtocol.SEED_MAIN_CLASS);
        command.add("--project=" + project.toAbsolutePath().normalize());
        command.add("--run=" + parent.getFileName());
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
        try {
            Process process = builder.start();
            int exit = process.waitFor();
            if (exit != 0 || !Workspace.exists(workspace)) {
                throw new IllegalStateException("Workspace seed failed for " + workspace + " exit=" + exit);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace seed was interrupted for " + workspace, interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not seed workspace " + workspace, failure);
        }
    }

    private WorkerSession awaitBridge(
            Process process,
            String sessionId,
            String token,
            Path sessionDirectory,
            Path stdout,
            Path stderr,
            WorkbenchManifest manifest,
            List<String> classpath,
            long startedNanos
    ) {
        String stage = "worker-launch";
        long deadline = startedNanos + startTimeout(workerSystemProperties).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw startupFailure(
                        "Workbench worker exited before publishing its control bridge. exit="
                                + process.exitValue(),
                        startedNanos, stage, stdout, stderr
                );
            }

            Path descriptor = firstDescriptor(sessionDirectory);
            if (descriptor != null) {
                if (!"anchor-parse".equals(stage)) {
                    stage = "anchor-parse";
                    writeProgress(stderr, stage);
                }
                ControlBridgeClient client;
                try {
                    client = ControlBridgeClient.fromDescriptor(descriptor, token);
                } catch (IllegalArgumentException incompatibleDescriptor) {
                    // A complete descriptor with an incompatible protocol or host is
                    // never made valid by retrying the same consumer process.
                    throw incompatibleDescriptor;
                } catch (RuntimeException ignored) {
                    // A descriptor is atomically published, but tolerate a short read race.
                    sleep(50);
                    continue;
                }

                // Origin and process-boundary failures are permanent safety failures.
                // Keep this outside the startup retry block so they are reported clearly.
                verifyConsumerRuntime(client.descriptor(), manifest, classpath);

                List<ControlBridgeScenarioStatus> scenarios;
                try {
                    scenarios = client.scenarios();
                } catch (RuntimeException ignored) {
                    // Descriptor publication can precede the first accepted HTTP request.
                    sleep(50);
                    continue;
                }

                for (ControlBridgeScenarioStatus scenario : scenarios) {
                    if (!scenario.paused()) continue;

                    ControlBridgeScenarioStatus interactive = awaitInteractivePause(
                            process, client, scenario, deadline, stdout, stderr, startedNanos, stage
                    );
                    stage = "paused";
                    writeProgress(stderr, stage);
                    ScheduledExecutorService renewal = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "pickleball-workbench-lease-" + sessionId);
                        thread.setDaemon(true);
                        return thread;
                    });
                    return new WorkerSession(
                            sessionId, process, client, interactive.scenarioId(), renewal, stdout, stderr
                    );
                }
            }
            sleep(50);
        }
        process.destroy();
        throw startupFailure(
                "Timed out waiting for Workbench worker anchor to pause.",
                startedNanos, stage, stdout, stderr
        );
    }

    static Duration startTimeout(Map<String, String> workerProperties) {
        Map<String, String> properties = workerProperties == null ? Map.of() : workerProperties;
        String raw = firstNonBlank(
                System.getProperty(START_TIMEOUT_PROPERTY),
                System.getenv(START_TIMEOUT_ENV),
                System.getProperty(START_TIMEOUT_RUNVAR),
                compactValue(System.getProperty("pkb_runvars"), START_TIMEOUT_PROPERTY),
                properties.get(START_TIMEOUT_PROPERTY),
                compactValue(properties.get("pkb_runvars"), START_TIMEOUT_PROPERTY)
        );
        if (raw == null || raw.isBlank()) return START_TIMEOUT;
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) return START_TIMEOUT;
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException failure) {
            return START_TIMEOUT;
        }
    }

    static void writeProgress(Path log, String stage) {
        if (log == null || stage == null || stage.isBlank()) return;
        try {
            if (log.getParent() != null) Files.createDirectories(log.getParent());
            Files.writeString(
                    log,
                    "progress stage=" + stage + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Progress must not hide the startup error that follows.
        }
    }

    static String startupContext(Duration elapsed, String stage, Path stderr) {
        Duration shown = elapsed == null ? Duration.ZERO : elapsed;
        String where = stage == null || stage.isBlank() ? "worker-launch" : stage;
        return "elapsed=" + shown.toMillis() + "ms stage=" + where
                + " last-worker-output=" + tail(stderr);
    }

    private static IllegalStateException startupFailure(
            String message,
            long startedNanos,
            String stage,
            Path stdout,
            Path stderr
    ) {
        String context = startupContext(Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)), stage, stderr);
        return new IllegalStateException(message + " " + context + " stdout=" + stdout + " stderr=" + stderr);
    }

    private static String tail(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "";
        try {
            String text = Files.readString(file);
            int max = 800;
            String trimmed = text.length() <= max ? text : text.substring(text.length() - max);
            return trimmed.replace('\n', ' ').replace('\r', ' ').trim();
        } catch (IOException failure) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String compactValue(String compact, String key) {
        if (compact == null || compact.isBlank() || key == null) return null;
        for (String part : compact.split(",")) {
            String token = part.trim();
            int equals = token.indexOf('=');
            if (equals <= 0) continue;
            if (key.equals(token.substring(0, equals).trim())) {
                return token.substring(equals + 1).trim();
            }
        }
        return null;
    }

    static void verifyConsumerRuntime(
            ControlBridgeDescriptor descriptor,
            WorkbenchManifest manifest,
            List<String> classpath
    ) {
        if (descriptor.pid() <= 0) {
            throw new IllegalStateException("Consumer worker reported an invalid process id.");
        }
        if (descriptor.pid() == ProcessHandle.current().pid()) {
            throw new IllegalStateException(
                    "Consumer worker must run in a process distinct from the Workbench controller."
            );
        }
        if (descriptor.runtimeCodeSource() == null
                || descriptor.runtimeCodeSource().isBlank()
                || "unknown".equals(descriptor.runtimeCodeSource())) {
            throw new IllegalStateException(
                    "Consumer worker did not report the Pickleball runtime code source."
            );
        }

        Path runtimeSource = canonicalPath(Path.of(descriptor.runtimeCodeSource()));
        Path consumerProject = canonicalPath(Path.of(manifest.projectRoot()));
        List<Path> capturedClasspath = classpath.stream()
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : consumerProject.resolve(path))
                .map(WorkbenchWorkerManager::canonicalPath)
                .toList();
        long runtimeSourceMatches = capturedClasspath.stream()
                .filter(runtimeSource::equals)
                .count();
        if (runtimeSourceMatches == 0) {
            throw new IllegalStateException(
                    "Consumer worker loaded Pickleball outside the synchronized test runtime classpath: "
                            + runtimeSource
            );
        }
        if (runtimeSourceMatches != 1) {
            throw new IllegalStateException(
                    "Consumer worker Pickleball code source must appear exactly once on the "
                            + "synchronized test runtime classpath: " + runtimeSource
            );
        }

        Path controllerSource = codeSource(WorkbenchWorkerManager.class);
        boolean controllerOnWorkerClasspath = capturedClasspath.stream().anyMatch(path ->
                (controllerSource != null && controllerSource.equals(path))
                        || (path.getFileName() != null
                        && path.getFileName().toString().matches(
                                "(?i)pickleball-workbench(?:-[^/]*)?\\.jar"
                        ))
        );
        if (controllerOnWorkerClasspath) {
            throw new IllegalStateException(
                    "Consumer worker classpath must not contain the Workbench controller artifact."
            );
        }
        if (controllerSource != null && controllerSource.equals(runtimeSource)) {
            throw new IllegalStateException(
                    "Consumer worker must not load Pickleball core from the Workbench controller artifact."
            );
        }

        if (manifest.pickleballVersion() == null || manifest.pickleballVersion().isBlank()) {
            throw new IllegalStateException(
                    "Synchronized Workbench manifest did not record its Pickleball version."
            );
        }
        if (descriptor.runtimeVersion() == null || descriptor.runtimeVersion().isBlank()) {
            throw new IllegalStateException(
                    "Consumer worker did not report its Pickleball runtime version."
            );
        }
        if (!"development".equals(manifest.pickleballVersion())
                && !"development".equals(descriptor.runtimeVersion())
                && !manifest.pickleballVersion().equals(descriptor.runtimeVersion())) {
            throw new IllegalStateException(
                    "Consumer worker Pickleball version " + descriptor.runtimeVersion()
                            + " does not match synchronized version " + manifest.pickleballVersion() + "."
            );
        }
    }

    private static Path codeSource(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return canonicalPath(Path.of(source.getLocation().toURI()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path canonicalPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }


    private static ControlBridgeScenarioStatus awaitInteractivePause(
            Process process,
            ControlBridgeClient client,
            ControlBridgeScenarioStatus initialPause,
            long deadline,
            Path stdout,
            Path stderr,
            long startedNanos,
            String stage
    ) {
        if (isInteractivePause(initialPause)) {
            return initialPause;
        }

        ControlBridgeBreakpoint breakpoint = client.addBreakpoint(
                initialPause.scenarioId(),
                INTERACTIVE_PAUSE_HOOK,
                null,
                INTERACTIVE_PAUSE_STEP,
                null,
                true,
                PAUSE_LEASE_SECONDS
        );
        ControlBridgeCallResult resumed = client.resume(initialPause.scenarioId());
        if (!"SUCCESS".equals(resumed.status())) {
            client.removeBreakpoint(breakpoint.breakpointId());
            throw new IllegalStateException(
                    "Could not resume Workbench bootstrap pause before the interactive boundary. status="
                            + resumed.status()
            );
        }

        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw startupFailure(
                        "Workbench worker exited before reaching the interactive pause boundary. exit="
                                + process.exitValue(),
                        startedNanos, stage, stdout, stderr
                );
            }
            ControlBridgeScenarioStatus scenario = client.scenarios().stream()
                    .filter(candidate -> initialPause.scenarioId().equals(candidate.scenarioId()))
                    .findFirst()
                    .orElse(null);
            if (scenario != null && scenario.paused() && isInteractivePause(scenario)) {
                return scenario;
            }
            sleep(25);
        }

        client.removeBreakpoint(breakpoint.breakpointId());
        throw startupFailure(
                "Timed out waiting for Workbench worker interactive boundary " + INTERACTIVE_PAUSE_HOOK + ".",
                startedNanos, stage, stdout, stderr
        );
    }


    private static boolean isInteractivePause(ControlBridgeScenarioStatus scenario) {
        return scenario != null
                && INTERACTIVE_PAUSE_HOOK.equals(scenario.lastHook())
                && scenario.stepText() != null
                && scenario.stepText().contains(INTERACTIVE_PAUSE_STEP);
    }

    private static void scheduleLeaseRenewal(WorkerSession session) {
        session.renewal().scheduleAtFixedRate(() -> {
            if (!session.process().isAlive()) return;
            try {
                ControlBridgeCallResult result = session.client().pause(
                        session.scenarioId(), 5, PAUSE_LEASE_SECONDS
                );
                if (!"SUCCESS".equals(result.status())) {
                    session.renewal().shutdown();
                }
            } catch (RuntimeException ignored) {
                session.renewal().shutdown();
            }
        }, LEASE_RENEW_SECONDS, LEASE_RENEW_SECONDS, TimeUnit.SECONDS);
    }

    private static void stopLeaseRenewal(ScheduledExecutorService renewal) {
        renewal.shutdownNow();
        try {
            renewal.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path firstDescriptor(Path sessionDirectory) {
        if (!Files.isDirectory(sessionDirectory)) return null;
        try (var paths = Files.list(sessionDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith("runtime-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not inspect Workbench worker session " + sessionDirectory, failure);
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Path javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                WorkbenchProjectOs.isWindows() ? "java.exe" : "java"
        );
    }

    private static boolean awaitExit(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Workbench worker startup.", failure);
        }
    }

    static String anchorFeature() {
        return """
                @pickleball-workbench-anchor
                Feature: Pickleball Workbench interactive anchor

                  Scenario: Keep an interactive Pickleball context available
                    Given ---pickleball-workbench-anchor
                """;
    }

    private static void writeAnchor(Path anchor) {
        try {
            Files.writeString(anchor, anchorFeature(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create Workbench anchor feature: " + anchor, failure);
        }
    }

    private static void createDirectories(Path... directories) {
        try {
            for (Path directory : directories) Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create Workbench worker directories.", failure);
        }
    }

    private record WorkerSession(
            String sessionId,
            Process process,
            ControlBridgeClient client,
            String scenarioId,
            ScheduledExecutorService renewal,
            Path stdout,
            Path stderr
    ) { }

    private static final class WorkbenchProjectOs {
        private static boolean isWindows() {
            return System.getProperty("os.name", "").startsWith("Windows");
        }
    }
}
