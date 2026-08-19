package tools.dscode.workbench.worker;

import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeScenarioStatus;
import tools.dscode.workbench.bridge.ControlBridgeClient;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;
import tools.dscode.testengine.DynamicSuiteBootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns one persistent interactive consumer worker for a Workbench controller session. */
public final class WorkbenchWorkerManager implements AutoCloseable {
    static final int PAUSE_LEASE_SECONDS = 120;
    static final int LEASE_RENEW_SECONDS = 30;
    static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    static final Duration GRACEFUL_STOP_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TERMINATE_TIMEOUT = Duration.ofSeconds(5);

    private final Path projectRoot;
    private final SecureRandom random = new SecureRandom();
    private WorkerSession active;

    public WorkbenchWorkerManager(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
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

        ProcessBuilder builder = new ProcessBuilder(workerCommand(manifest, classpath, anchor))
                .directory(projectRoot.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().put("PKB_CONTROL_BRIDGE_SESSION_DIR", sessionDirectory.toString());
        builder.environment().put("PKB_CONTROL_BRIDGE_SESSION_ID", sessionId);
        builder.environment().put("PKB_CONTROL_BRIDGE_TOKEN", token);
        builder.environment().put("PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO", "true");

        Process process = null;
        try {
            process = builder.start();
            WorkerSession session = awaitBridge(
                    process, sessionId, token, sessionDirectory, stdout, stderr
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
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-D" + DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY
                + "=" + manifest.liveOutputPath());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add("tools.dscode.testengine.WorkbenchWorkerMain");
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
            Path stderr
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
                try {
                    ControlBridgeClient client = ControlBridgeClient.fromDescriptor(descriptor, token);
                    for (ControlBridgeScenarioStatus scenario : client.scenarios()) {
                        if (scenario.paused()) {
                            ScheduledExecutorService renewal = Executors.newSingleThreadScheduledExecutor(runnable -> {
                                Thread thread = new Thread(runnable, "pickleball-workbench-lease-" + sessionId);
                                thread.setDaemon(true);
                                return thread;
                            });
                            return new WorkerSession(
                                    sessionId, process, client, scenario.scenarioId(), renewal, stdout, stderr
                            );
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Descriptor may have been published just before the HTTP server is ready.
                }
            }
            sleep(50);
        }
        process.destroy();
        throw new IllegalStateException(
                "Timed out waiting for Workbench worker anchor to pause. stdout=" + stdout + ", stderr=" + stderr
        );
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
