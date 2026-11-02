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
import java.util.function.Function;

/**
 * One speed path for discover, confirm, and isolate. Cache hit skips Maven
 * resolve. A live warm JVM is reused until the watch hash changes. Any miss,
 * daemon error, or parallel failure falls back to the sequential wrapper path.
 */
public final class WorkbenchSpeed {
    public static final String MODE_PROPERTY = "pkb_workbench_speed";
    public static final String MODE_ENV = "PKB_WORKBENCH_SPEED";
    public static final String IDLE_PROPERTY = "pkb_workbench_warm_idle_timeout";
    public static final String IDLE_ENV = "PKB_WORKBENCH_WARM_IDLE_TIMEOUT";
    public static final String IDLE_RUNVAR = "pkb_runvars.pkb_workbench_warm_idle_timeout";

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
     * Same key order as the Workbench worker start timeout.
     * System property, environment, dotted {@code pkb_runvars} property,
     * then a compact {@code pkb_runvars} member.
     * Blank, non-numeric, or not greater than zero stays {@link WarmProcess#DEFAULT_IDLE_SECONDS}.
     * Read when the warm JVM starts. A running JVM does not re-read it.
     */
    static long idleSeconds() {
        return idleSeconds(System::getProperty, System::getenv);
    }

    static long idleSeconds(Function<String, String> property, Function<String, String> env) {
        Function<String, String> readProperty = property == null ? key -> null : property;
        Function<String, String> readEnv = env == null ? key -> null : env;
        String raw = firstNonBlank(
                readProperty.apply(IDLE_PROPERTY),
                readEnv.apply(IDLE_ENV),
                readProperty.apply(IDLE_RUNVAR),
                compactValue(readProperty.apply("pkb_runvars"), IDLE_PROPERTY)
        );
        if (raw == null || raw.isBlank()) return WarmProcess.DEFAULT_IDLE_SECONDS;
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) return WarmProcess.DEFAULT_IDLE_SECONDS;
            return seconds;
        } catch (NumberFormatException failure) {
            return WarmProcess.DEFAULT_IDLE_SECONDS;
        }
    }

    /** Stops the warm JVM for this project, including descendants. No handle is a no-op. */
    static void release(Path project) {
        if (project == null) return;
        WarmProcess.release(state(project));
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
            writer.println(Long.toString(ProcessHandle.current().pid()));
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
            WarmProcess.release(state);
            return Optional.empty();
        }
    }

    private static void stopStale(Path state) {
        WarmProcess.release(state);
    }

    private static boolean startDaemon(Path project, Path state, String watch) {
        ClasspathCache.Hit hit = ClasspathCache.load(state, InputFingerprint.lockHash(project));
        if (!hit.hit()) return false;
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        if (Boolean.getBoolean("pkb_warm_test_child")) command.add("-Dpkb_warm_test_child=true");
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, hit.entries()));
        command.add(WorkbenchWarmMain.class.getName());
        command.add(state.toAbsolutePath().normalize().toString());
        command.add(watch);
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add(Long.toString(idleSeconds()));
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
