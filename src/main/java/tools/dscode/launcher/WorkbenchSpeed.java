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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * One speed path for discover, confirm, and isolate. Cache hit skips Maven
 * resolve. A live warm JVM is reused until the watch hash changes. A miss
 * before the warm JVM acknowledges the run falls back to the sequential
 * wrapper path. An acknowledged run is never started a second time.
 */
public final class WorkbenchSpeed {
    public static final String MODE_PROPERTY = "pkb_workbench_speed";
    public static final String MODE_ENV = "PKB_WORKBENCH_SPEED";
    public static final String IDLE_PROPERTY = "pkb_workbench_warm_idle_timeout";
    public static final String IDLE_ENV = "PKB_WORKBENCH_WARM_IDLE_TIMEOUT";
    public static final String IDLE_RUNVAR = "pkb_runvars.pkb_workbench_warm_idle_timeout";
    /** Connect, send RUN, and read STARTED. Same order of magnitude as {@code startDaemon}. */
    static final int HANDSHAKE_TIMEOUT_MILLIS = 20_000;

    enum DirectKind {
        /** Direct launch was not attempted. The caller uses the wrapper. */
        SKIPPED,
        /** The warm JVM never acknowledged the run. The caller may start a fresh JVM. */
        MISSED,
        /** STARTED, then EXIT. */
        COMPLETED,
        /** STARTED, then the connection dropped. Not a fresh JVM. */
        INTERRUPTED,
        /** STARTED, then {@code --run-timeout} expired. Not a fresh JVM. */
        TIMED_OUT,
        /** EXIT 2 without STARTED. The warm JVM was busy and did not start this selection. */
        REJECTED
    }

    record DirectAttempt(DirectKind kind, int exit) {
        static DirectAttempt skipped() {
            return new DirectAttempt(DirectKind.SKIPPED, 0);
        }

        static DirectAttempt missed() {
            return new DirectAttempt(DirectKind.MISSED, 0);
        }

        static DirectAttempt completed(int exit) {
            return new DirectAttempt(DirectKind.COMPLETED, exit);
        }

        static DirectAttempt interrupted() {
            return new DirectAttempt(DirectKind.INTERRUPTED, 1);
        }

        static DirectAttempt timedOut() {
            return new DirectAttempt(DirectKind.TIMED_OUT, 1);
        }

        static DirectAttempt rejected() {
            return new DirectAttempt(DirectKind.REJECTED, 2);
        }

        boolean acknowledged() {
            return kind == DirectKind.COMPLETED || kind == DirectKind.INTERRUPTED || kind == DirectKind.TIMED_OUT;
        }

        /** A busy rejection also must not start a second JVM. */
        boolean preventFallback() {
            return acknowledged() || kind == DirectKind.REJECTED;
        }
    }

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
        AtomicReference<T> indexed = new AtomicReference<>();
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
                seed.call();
                return "seed";
            }));
        }
        StageScheduler.Outcome outcome = StageScheduler.run(startTogether(tasks, runFolder), true);
        if (outcome.failed() || indexed.get() == null) {
            WorkbenchLaunchTiming.note(runFolder, "fallback", "parallel");
            return Optional.empty();
        }
        return Optional.of(indexed.get());
    }

    /**
     * Log index start and end, and log overlap when every stage has entered
     * and none has done its work yet. The line is not written after compile.
     */
    private static List<StageScheduler.Task> startTogether(List<StageScheduler.Task> tasks, Path runFolder) {
        int count = tasks.size();
        if (count < 2) return tasks;
        AtomicInteger arrived = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(count);
        AtomicBoolean noted = new AtomicBoolean();
        String detail = overlapNames(tasks);
        List<StageScheduler.Task> gated = new ArrayList<>();
        for (StageScheduler.Task task : tasks) {
            boolean index = "index".equals(task.name());
            gated.add(new StageScheduler.Task(task.name(), () -> {
                boolean released = false;
                if (index) WorkbenchLaunchTiming.note(runFolder, "index", "start");
                try {
                    if (arrived.incrementAndGet() == count && noted.compareAndSet(false, true)) {
                        WorkbenchLaunchTiming.note(runFolder, "overlap", detail);
                    }
                    started.countDown();
                    released = true;
                    started.await();
                    return task.body() == null ? "" : task.body().call();
                } finally {
                    if (!released) started.countDown();
                    if (index) WorkbenchLaunchTiming.note(runFolder, "index", "end");
                }
            }));
        }
        return gated;
    }

    private static String overlapNames(List<StageScheduler.Task> tasks) {
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            for (int j = i + 1; j < tasks.size(); j++) {
                if (!detail.isEmpty()) detail.append(',');
                detail.append(tasks.get(i).name()).append('+').append(tasks.get(j).name());
            }
        }
        return detail.toString();
    }

    /**
     * Runs the launch on the warm JVM when the classpath cache hits.
     * {@link DirectKind#SKIPPED} and {@link DirectKind#MISSED} mean the caller
     * must use the wrapper. An acknowledged run must not be started again.
     */
    static DirectAttempt tryDirect(
            Path project,
            List<String> command,
            PrintStream out,
            PrintStream err,
            Long runTimeoutSeconds
    ) {
        if (!directEnabled() || project == null || !cacheHit(project)) return DirectAttempt.skipped();
        long started = System.nanoTime();
        DirectAttempt warm;
        try {
            warm = warmRun(project, command, out, err, runTimeoutSeconds);
        } catch (RuntimeException failure) {
            if (err != null) err.println("Workbench warm JVM missed: " + failure.getMessage());
            warm = DirectAttempt.missed();
        }
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return record(project, command, warm, elapsed);
    }

    /**
     * Same decision {@link #tryDirect} records. A stub server calls this with
     * {@link #exchange} so a short handshake timeout can prove the fallback.
     */
    static DirectAttempt record(Path project, List<String> command, DirectAttempt attempt, long elapsedMs) {
        if (attempt == null) attempt = DirectAttempt.missed();
        Path folder = AgentRun.folder(project, runIdOf(command));
        switch (attempt.kind()) {
            case COMPLETED -> WorkbenchLaunchTiming.note(folder, "direct", "run elapsedMs=" + elapsedMs);
            case TIMED_OUT -> WorkbenchLaunchTiming.note(folder, "direct", "timeout elapsedMs=" + elapsedMs);
            case INTERRUPTED -> WorkbenchLaunchTiming.note(folder, "direct", "interrupted elapsedMs=" + elapsedMs);
            case REJECTED -> WorkbenchLaunchTiming.note(folder, "direct", "rejected elapsedMs=" + elapsedMs);
            case MISSED -> WorkbenchLaunchTiming.note(folder, "fallback", "fresh-jvm");
            case SKIPPED -> {
            }
        }
        return attempt;
    }

    static boolean cacheHit(Path project) {
        if (project == null) return false;
        ClasspathCache.Hit hit = ClasspathCache.load(state(project), InputFingerprint.lockHash(project));
        return hit.hit() && ClasspathCache.hasProjectOutput(project, hit.entries());
    }

    static void remember(Path project, List<String> entries) {
        if (project == null || entries == null || entries.isEmpty()) return;
        try {
            ClasspathCache.store(state(project), InputFingerprint.lockHash(project), entries);
        } catch (Exception ignored) {
            // The wrapper path does not need the cache.
        }
    }

    private static DirectAttempt warmRun(
            Path project,
            List<String> command,
            PrintStream out,
            PrintStream err,
            Long runTimeoutSeconds
    ) {
        String watch = InputFingerprint.watchHash(project);
        Path state = state(project);
        Optional<WarmProcess.Handle> reusable = WarmProcess.reusable(state, watch);
        if (reusable.isEmpty()) {
            stopStale(state);
            if (!startDaemon(project, state, watch)) return DirectAttempt.missed();
            reusable = WarmProcess.reusable(state, watch);
        }
        if (reusable.isEmpty()) return DirectAttempt.missed();
        WarmProcess.Handle handle = reusable.get();
        Long runTimeoutMillis = null;
        if (runTimeoutSeconds != null && runTimeoutSeconds > 0) {
            try {
                runTimeoutMillis = Math.multiplyExact(runTimeoutSeconds, 1000L);
            } catch (ArithmeticException overflow) {
                runTimeoutMillis = (long) Integer.MAX_VALUE;
            }
        }
        return exchange(
                handle.port(),
                command,
                project,
                WarmProcess.liveOwner(state),
                out,
                err,
                HANDSHAKE_TIMEOUT_MILLIS,
                runTimeoutMillis,
                state
        );
    }

    /**
     * One RUN on an already-open warm port. {@code releaseState} is killed only
     * when the run was never acknowledged. A handshake timeout is not proof
     * that a long run failed.
     */
    static DirectAttempt exchange(
            int port,
            List<String> command,
            Path project,
            long ownerPid,
            PrintStream out,
            PrintStream err,
            int handshakeTimeoutMillis,
            Long runTimeoutMillis,
            Path releaseState
    ) {
        boolean acknowledged = false;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(handshakeTimeoutMillis);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("RUN");
            for (String token : WorkbenchDirectRun.dashD(command)) writer.println(token);
            writer.println(".");
            writer.println(project == null ? "" : project.toAbsolutePath().normalize());
            writer.println(Long.toString(ownerPid));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            if (status == null) {
                releaseQuietly(releaseState);
                return DirectAttempt.missed();
            }
            if (!"STARTED".equals(status.trim())) {
                // EXIT 2 without STARTED means the warm JVM is busy. Leave it up
                // and do not start a second execution beside the in-flight run.
                if ("EXIT 2".equals(status.trim())) {
                    noteRejected(err);
                    return DirectAttempt.rejected();
                }
                // A clean EXIT without STARTED is a rejection. Leave the JVM up.
                if (!status.startsWith("EXIT ")) releaseQuietly(releaseState);
                return DirectAttempt.missed();
            }
            acknowledged = true;
            if (runTimeoutMillis != null && runTimeoutMillis > 0) {
                long capped = Math.min(runTimeoutMillis, Integer.MAX_VALUE);
                socket.setSoTimeout((int) capped);
            } else {
                socket.setSoTimeout(0);
            }
            String exitLine = reader.readLine();
            if (exitLine == null || !exitLine.startsWith("EXIT ")) {
                noteInterrupted(err);
                return DirectAttempt.interrupted();
            }
            int exit = Integer.parseInt(exitLine.substring("EXIT ".length()).trim());
            StringBuilder captured = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !"END".equals(line)) {
                captured.append(line).append(System.lineSeparator());
            }
            if (out != null && captured.length() > 0) out.print(captured);
            return DirectAttempt.completed(exit);
        } catch (SocketTimeoutException timeout) {
            if (acknowledged) {
                if (err != null) {
                    err.println("error: warm run timed out after the warm JVM accepted it");
                    err.println("fix: raise --run-timeout. This selection was not started again.");
                }
                return DirectAttempt.timedOut();
            }
            releaseQuietly(releaseState);
            return DirectAttempt.missed();
        } catch (Exception failure) {
            if (acknowledged) {
                noteInterrupted(err);
                return DirectAttempt.interrupted();
            }
            releaseQuietly(releaseState);
            return DirectAttempt.missed();
        }
    }

    private static void noteInterrupted(PrintStream err) {
        if (err == null) return;
        err.println("error: warm run was accepted and then the connection dropped");
        err.println("fix: this selection was not started again.");
    }

    private static void noteRejected(PrintStream err) {
        if (err == null) return;
        err.println("error: warm JVM rejected the run because it is already busy");
        err.println("fix: wait for the in-flight selection to finish. This selection was not started again.");
    }

    private static void releaseQuietly(Path state) {
        if (state == null) return;
        WarmProcess.release(state);
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
        command.add(Long.toString(WarmProcess.liveOwner(state)));
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
