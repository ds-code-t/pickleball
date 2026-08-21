package tools.dscode.workbench.worker;

import tools.dscode.control.protocol.ControlBridgeBreakpoint;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.ControlBridgeScenarioStatus;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.workbench.bridge.ControlBridgeClient;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;

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
    static final Duration START_TIMEOUT = Duration.ofSeconds(30);
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

        WorkbenchManifest manifest = WorkbenchManifest.read(projectRoot);
        List<String> classpath = WorkbenchSynchronizer.readWorkerClasspath(projectRoot);
        Path stateRoot = WorkbenchManifest.workbenchRoot(projectRoot);
        String sessionId = UUID.randomUUID().toString();
        String token = token();
        Path sessionDirectory = stateRoot.resolve("sessions").resolve(sessionId);
        Path anchor = sessionDirectory.resolve("anchor.feature");
        Path stdout = stateRoot.resolve("logs").resolve("worker-" + sessionId + ".out.log");
        Path stderr = stateRoot.resolve("logs").resolve("worker-" + sessionId + ".err.log");
        createDirectories(sessionDirectory, stdout.getParent());
        writeAnchor(anchor);

        ProcessBuilder builder = new ProcessBuilder(workerCommand(
                manifest, classpath, anchor, workerSystemProperties
        ))
                .directory(projectRoot.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().put(ControlProtocol.SESSION_DIRECTORY_ENV, sessionDirectory.toString());
        builder.environment().put(ControlProtocol.SESSION_ID_ENV, sessionId);
        builder.environment().put(ControlProtocol.SESSION_TOKEN_ENV, token);
        builder.environment().put(ControlProtocol.PAUSE_FIRST_SCENARIO_ENV, "true");

        Process process = null;
        try {
            process = builder.start();
            WorkerSession session = awaitBridge(
                    process, sessionId, token, sessionDirectory, stdout, stderr, manifest, classpath
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

    private WorkerSession awaitBridge(
            Process process,
            String sessionId,
            String token,
            Path sessionDirectory,
            Path stdout,
            Path stderr,
            WorkbenchManifest manifest,
            List<String> classpath
    ) {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "Workbench worker exited before publishing its control bridge. exit="
                                + process.exitValue() + ", stdout=" + stdout + ", stderr=" + stderr
                );
            }

            Path descriptor = firstDescriptor(sessionDirectory);
            if (descriptor != null) {
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
                            process, client, scenario, deadline, stdout, stderr
                    );
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
        throw new IllegalStateException(
                "Timed out waiting for Workbench worker anchor to pause. stdout=" + stdout + ", stderr=" + stderr
        );
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
            Path stderr
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
                throw new IllegalStateException(
                        "Workbench worker exited before reaching the interactive pause boundary. exit="
                                + process.exitValue() + ", stdout=" + stdout + ", stderr=" + stderr
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
        throw new IllegalStateException(
                "Timed out waiting for Workbench worker interactive boundary "
                        + INTERACTIVE_PAUSE_HOOK + ". stdout=" + stdout + ", stderr=" + stderr
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
