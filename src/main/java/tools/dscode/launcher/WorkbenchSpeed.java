package tools.dscode.launcher;

import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;
import tools.dscode.control.protocol.speed.StageScheduler;
import tools.dscode.control.protocol.speed.WarmProcess;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * One speed path for discover, confirm, and isolate. Cache hit skips Maven
 * resolve. A live warm JVM is reused until the watch hash changes. Any miss,
 * daemon error, or parallel failure falls back to the sequential wrapper path.
 */
public final class WorkbenchSpeed {
    public static final String MODE_PROPERTY = "pkb_workbench_speed";
    public static final String MODE_ENV = "PKB_WORKBENCH_SPEED";

    private static final ThreadLocal<Boolean> DIRECT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private WorkbenchSpeed() {
    }

    /** Production entry points enable the direct launch. Tests leave it off. */
    static void enableDirect() {
        DIRECT.set(Boolean.TRUE);
    }

    static void clearDirect() {
        DIRECT.remove();
    }

    public static boolean sequential() {
        String property = System.getProperty(MODE_PROPERTY);
        String mode = property == null ? System.getenv(MODE_ENV) : property;
        return mode != null && "sequential".equalsIgnoreCase(mode.trim());
    }

    static boolean directEnabled() {
        return Boolean.TRUE.equals(DIRECT.get()) && !sequential();
    }

    /**
     * Runs index beside classpath resolve. On a cache hit, {@code seed} also
     * starts immediately so seed overlaps the cache check. Empty means the
     * caller runs index, compile, and seed one after another.
     */
    static <T> Optional<T> indexBesideClasspath(
            Path project,
            String runId,
            Callable<T> index,
            Callable<Void> seed,
            PrintStream err
    ) {
        if (sequential() || WorkspaceLaunch.testCapture != null || project == null || index == null) {
            return Optional.empty();
        }
        Path runFolder = AgentRun.folder(project, runId);
        boolean hit = cacheHit(project);
        java.util.concurrent.atomic.AtomicReference<T> indexed = new java.util.concurrent.atomic.AtomicReference<>();
        List<StageScheduler.Task> tasks = new ArrayList<>();
        tasks.add(new StageScheduler.Task("index", () -> {
            indexed.set(index.call());
            return "index";
        }));
        tasks.add(new StageScheduler.Task("compile", () -> {
            long started = System.nanoTime();
            boolean ready = WorkspaceLaunch.warmClasspath(project, runFolder, err);
            long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            WorkbenchLaunchTiming.note(runFolder, "maven", (hit ? "cache-hit" : "resolved") + " elapsedMs=" + elapsed);
            if (!ready && !hit) return "miss";
            return "ready";
        }));
        if (hit && seed != null) {
            tasks.add(new StageScheduler.Task("seed", () -> {
                WorkspaceLaunch.warmClasspath(project, runFolder, err);
                seed.call();
                return "seed";
            }));
        }
        StageScheduler.Outcome outcome = StageScheduler.run(tasks, true);
        if (outcome.failed() || indexed.get() == null) {
            WorkbenchLaunchTiming.note(runFolder, "fallback", "parallel");
            return Optional.empty();
        }
        if (!outcome.overlaps().isEmpty()) {
            WorkbenchLaunchTiming.note(runFolder, "overlap", outcome.overlapDetail());
        }
        return Optional.of(indexed.get());
    }

    /**
     * Runs the launch on the warm JVM when the classpath cache hits.
     * Empty means the caller must use the wrapper.
     */
    static Optional<Integer> tryDirect(Path project, List<String> command, PrintStream out, PrintStream err) {
        if (!directEnabled() || project == null || !cacheHit(project)) return Optional.empty();
        long started = System.nanoTime();
        try {
            Optional<Integer> warm = warmRun(project, command, out, err);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            if (warm.isPresent()) {
                WorkbenchLaunchTiming.note(AgentRun.folder(project, runIdOf(command)), "direct", "run elapsedMs=" + elapsed);
                return warm;
            }
        } catch (RuntimeException failure) {
            if (err != null) err.println("Workbench warm JVM missed: " + failure.getMessage());
        }
        WorkbenchLaunchTiming.note(AgentRun.folder(project, runIdOf(command)), "fallback", "fresh-jvm");
        return Optional.empty();
    }

    static boolean cacheHit(Path project) {
        if (project == null) return false;
        ClasspathCache.Hit hit = ClasspathCache.load(state(project), InputFingerprint.lockHash(project));
        return hit.hit();
    }

    static void remember(Path project, List<String> entries) {
        if (project == null || entries == null || entries.isEmpty()) return;
        try {
            ClasspathCache.store(state(project), InputFingerprint.lockHash(project), entries);
        } catch (Exception ignored) {
            // The wrapper path does not need the cache.
        }
    }

    private static Optional<Integer> warmRun(Path project, List<String> command, PrintStream out, PrintStream err) {
        String watch = InputFingerprint.watchHash(project);
        Path state = state(project);
        Optional<WarmProcess.Handle> reusable = WarmProcess.reusable(state, watch);
        if (reusable.isEmpty()) {
            stopStale(state);
            if (!startDaemon(project, state, watch)) return Optional.empty();
            reusable = WarmProcess.reusable(state, watch);
        }
        if (reusable.isEmpty()) return Optional.empty();
        WarmProcess.Handle handle = reusable.get();
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), handle.port())) {
            socket.setSoTimeout((int) TimeUnit.MINUTES.toMillis(10));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("RUN");
            for (String token : WorkbenchDirectRun.dashD(command)) writer.println(token);
            writer.println(".");
            writer.println(project.toAbsolutePath().normalize());
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            if (status == null || !status.startsWith("EXIT ")) return Optional.empty();
            int exit = Integer.parseInt(status.substring("EXIT ".length()).trim());
            StringBuilder captured = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !"END".equals(line)) {
                captured.append(line).append(System.lineSeparator());
            }
            if (out != null && captured.length() > 0) out.print(captured);
            if (exit != 0 && err != null) {
                err.println("error: warm launch exited " + exit);
                err.println("fix: rerun with -Dpkb_workbench_speed=sequential to use the wrapper.");
            }
            return Optional.of(exit);
        } catch (Exception failure) {
            WarmProcess.delete(state);
            return Optional.empty();
        }
    }

    private static void stopStale(Path state) {
        WarmProcess.read(state).ifPresent(handle -> {
            if (WarmProcess.alive(handle.pid())) {
                ProcessHandle.of(handle.pid()).ifPresent(ProcessHandle::destroy);
            }
        });
        WarmProcess.delete(state);
    }

    private static boolean startDaemon(Path project, Path state, String watch) {
        ClasspathCache.Hit hit = ClasspathCache.load(state, InputFingerprint.lockHash(project));
        if (!hit.hit()) return false;
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString());
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, hit.entries()));
        command.add(WorkbenchWarmMain.class.getName());
        command.add(state.toAbsolutePath().normalize().toString());
        command.add(watch);
        try {
            new ProcessBuilder(command)
                    .directory(project.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                if (WarmProcess.reusable(state, watch).isPresent()) return true;
                Thread.sleep(50);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Caller falls back.
        }
        return WarmProcess.reusable(state, watch).isPresent();
    }

    private static Path state(Path project) {
        return PickleballLocalLayout.workbenchStateRoot(project);
    }

    private static String runIdOf(List<String> command) {
        if (command == null) return null;
        for (String token : command) {
            if (token == null) continue;
            String prefix = "-Dpkb_run_id=";
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    /** Run-folder lookup without pulling coordination into the protocol. */
    static final class AgentRun {
        private AgentRun() {
        }

        static Path folder(Path project, String runId) {
            if (project == null || runId == null || runId.isBlank()) return null;
            if (runId.indexOf('/') >= 0 || runId.indexOf('\\') >= 0 || runId.contains("..")) return null;
            return project.resolve(".pickleball").resolve("runs").resolve(runId);
        }
    }
}
