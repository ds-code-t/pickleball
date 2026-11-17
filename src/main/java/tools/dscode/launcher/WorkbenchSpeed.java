package tools.dscode.launcher;

import tools.dscode.control.protocol.AgentLog;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;
import tools.dscode.control.protocol.speed.StageScheduler;
import tools.dscode.control.protocol.speed.WarmGeneration;
import tools.dscode.control.protocol.speed.WarmPool;
import tools.dscode.control.protocol.speed.WarmProcess;
import tools.dscode.control.protocol.speed.WarmSlot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public static final String POOL_PROPERTY = "pkb_workbench_warm_pool_max";
    public static final String POOL_ENV = "PKB_WORKBENCH_WARM_POOL_MAX";
    public static final String POOL_RUNVAR = "pkb_runvars.pkb_workbench_warm_pool_max";
    public static final String QUEUE_PROPERTY = "pkb_workbench_warm_queue_timeout";
    public static final String QUEUE_ENV = "PKB_WORKBENCH_WARM_QUEUE_TIMEOUT";
    public static final String QUEUE_RUNVAR = "pkb_runvars.pkb_workbench_warm_queue_timeout";
    /** Connect, send RUN, and read STARTED. Same order of magnitude as {@code startDaemon}. */
    static final int HANDSHAKE_TIMEOUT_MILLIS = 20_000;
    private static final ThreadLocal<Integer> WARM_WAIT = new ThreadLocal<>();

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

    /** Stops idle warm JVMs for this project. A busy member stays up. */
    static void release(Path project) {
        if (project == null) return;
        WarmPool.releaseIdle(state(project));
    }

    /** Stops the member serving {@code runId} after idle members are released. */
    static void releaseRun(Path project, String runId) {
        if (project == null) return;
        Path state = state(project);
        WarmPool.releaseIdle(state);
        WarmPool.releaseRun(state, runId);
    }

    /** {@code warm-stop --all}. Kills every member, including a busy one. */
    static void releaseAll(Path project) {
        if (project == null) return;
        WarmPool.releaseAll(state(project));
    }

    static void warmWait(Integer seconds) {
        if (seconds == null) WARM_WAIT.remove();
        else WARM_WAIT.set(seconds);
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
        WarmGeneration.Snapshot activeSnapshot = WarmGeneration.readActive(state);
        String generation = activeSnapshot == null || activeSnapshot.hash() == null ? "" : activeSnapshot.hash();
        String runId = runIdOf(command);
        int cap = poolMax();
        int wait = queueSeconds();
        Path folder = AgentRun.folder(project, runId);
        Long runTimeoutMillis = timeoutMillis(runTimeoutSeconds);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(wait, 0));
        boolean queued = false;
        int misses = 0;
        while (true) {
            Selection selection;
            try {
                selection = selectWarm(project, state, folder, watch, generation, runId, cap, wait, deadline);
            } catch (java.io.IOException failure) {
                return DirectAttempt.missed();
            }
            switch (selection.pick) {
                case ALREADY -> {
                    if (err != null) {
                        err.println("error: run " + runId + " is already running");
                        err.println("fix: wait for that run to finish or use a different --run-id.");
                    }
                    return DirectAttempt.rejected();
                }
                case REFUSE -> {
                    if (err != null) {
                        err.println("error: all " + cap + " warm JVMs are busy (runs: " + String.join(", ", selection.runs) + ")");
                        err.println("fix: wait for a run to finish, pass --warm-wait=<seconds>, or raise pkb_workbench_warm_pool_max.");
                    }
                    return DirectAttempt.rejected();
                }
                case MISS -> {
                    return DirectAttempt.missed();
                }
                case WAIT -> {
                    if (!queued) {
                        poolNote(project, folder, 0, "queue", "wait=" + wait);
                        queued = true;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return DirectAttempt.missed();
                    }
                }
                case EXCHANGE -> {
                    DirectAttempt attempt = exchange(
                            selection.member.handle().port(),
                            command,
                            project,
                            0L,
                            out,
                            err,
                            HANDSHAKE_TIMEOUT_MILLIS,
                            runTimeoutMillis,
                            selection.member.directory(),
                            false
                    );
                    if (attempt.kind() == DirectKind.REJECTED) {
                        misses = 0;
                        continue;
                    }
                    if (attempt.kind() == DirectKind.MISSED) {
                        if (++misses > cap + 8) return DirectAttempt.missed();
                        continue;
                    }
                    return attempt;
                }
            }
        }
    }

    private enum Pick {
        EXCHANGE, WAIT, REFUSE, MISS, ALREADY
    }

    private record Selection(Pick pick, WarmSlot.Member member, List<String> runs) {
        static Selection exchange(WarmSlot.Member member) {
            return new Selection(Pick.EXCHANGE, member, List.of());
        }

        static Selection waitTurn() {
            return new Selection(Pick.WAIT, null, List.of());
        }

        static Selection refuse(List<String> runs) {
            return new Selection(Pick.REFUSE, null, runs == null ? List.of() : runs);
        }

        static Selection miss() {
            return new Selection(Pick.MISS, null, List.of());
        }

        static Selection already() {
            return new Selection(Pick.ALREADY, null, List.of());
        }
    }

    /**
     * Picks or starts one slot. The lock covers the choice and the start,
     * not the run, so a second launcher waits its turn instead of forking
     * a second JVM into the same slot directory.
     */
    private static Selection selectWarm(
            Path project,
            Path state,
            Path folder,
            String watch,
            String generation,
            String runId,
            int cap,
            int wait,
            long deadline
    ) throws java.io.IOException {
        try (WarmGeneration.Lock ignored = WarmGeneration.Lock.open(state)) {
            if (runId != null && !runId.isBlank() && WarmPool.runInFlight(state, runId)) {
                poolNote(project, folder, 0, "refuse", "run=" + runId);
                return Selection.already();
            }
            for (WarmSlot.Member member : WarmSlot.list(state)) {
                if (!matches(member, generation, watch) || !WarmPool.idle(member)) continue;
                poolNote(project, folder, member.index(), "reuse", "");
                return Selection.exchange(member);
            }
            retireOrRecycle(project, folder, state, generation, watch);
            if (WarmPool.aliveCount(state) < cap) {
                int index = WarmSlot.nextIndex(state);
                poolNote(project, folder, index, "start", "");
                if (!startSlot(project, state, watch, generation, index)) return Selection.miss();
                for (WarmSlot.Member member : WarmSlot.list(state)) {
                    if (member.index() == index && matches(member, generation, watch)) {
                        return Selection.exchange(member);
                    }
                }
                return Selection.miss();
            }
            if (wait <= 0 || System.nanoTime() >= deadline) {
                poolNote(project, folder, 0, "refuse", "cap=" + cap);
                return Selection.refuse(WarmPool.busyRuns(state));
            }
            return Selection.waitTurn();
        }
    }

    private static boolean matches(WarmSlot.Member member, String generation, String watch) {
        if (member == null || member.handle() == null || !WarmProcess.alive(member.handle().pid())) return false;
        if (generation != null && !generation.isBlank()) return generation.equals(member.handle().generation());
        return watch != null && watch.equals(member.handle().watchHash());
    }

    private static void retireOrRecycle(Path project, Path folder, Path state, String generation, String watch) {
        for (WarmSlot.Member member : WarmSlot.list(state)) {
            if (!WarmProcess.alive(member.handle().pid())) continue;
            if (matches(member, generation, watch)) continue;
            if (WarmPool.idle(member)) {
                poolNote(project, folder, member.index(), "recycle", "");
                WarmSlot.release(member.directory());
            } else if (!WarmSlot.retirePending(member.directory())) {
                WarmSlot.markRetire(member.directory());
                poolNote(project, folder, member.index(), "recycle", "retire");
            }
        }
    }

    /**
     * One RUN on an already-open warm port. {@code releaseState} is killed only
     * when the run was never acknowledged and that JVM is not serving another run.
     * A handshake timeout is not proof that a long run failed.
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
        return exchange(port, command, project, ownerPid, out, err, handshakeTimeoutMillis, runTimeoutMillis, releaseState, true);
    }

    static DirectAttempt exchange(
            int port,
            List<String> command,
            Path project,
            long ownerPid,
            PrintStream out,
            PrintStream err,
            int handshakeTimeoutMillis,
            Long runTimeoutMillis,
            Path releaseState,
            boolean announceReject
    ) {
        boolean acknowledged = false;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(handshakeTimeoutMillis);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("RUN");
            for (String token : generationProperties(poolState(releaseState), WorkbenchDirectRun.dashD(command))) writer.println(token);
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
                if ("EXIT 2".equals(status.trim())) {
                    if (announceReject) noteRejected(err);
                    return DirectAttempt.rejected();
                }
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
        if (state == null || WarmPool.serving(state)) return;
        if (WarmSlot.isSlotDirectory(state)) WarmSlot.release(state);
        else WarmProcess.release(state);
    }

    /** Tests supply a slot instead of forking a JVM. Production leaves this null. */
    static volatile java.util.function.IntPredicate testSlotStarter;

    private static boolean startSlot(Path project, Path state, String watch, String generation, int index) {
        java.util.function.IntPredicate hook = testSlotStarter;
        if (hook != null) return hook.test(index);
        ClasspathCache.Hit hit = ClasspathCache.load(state, InputFingerprint.lockHash(project));
        if (!hit.hit()) return false;
        WarmGeneration.Snapshot snapshot = WarmGeneration.readActive(state);
        List<String> entries = hit.entries();
        String pinnedGeneration = generation == null ? "" : generation;
        if (snapshot != null) {
            entries = WarmGeneration.pinClasspath(project, entries, snapshot.classes(), snapshot.testClasses());
            pinnedGeneration = snapshot.hash() == null ? pinnedGeneration : snapshot.hash();
            WarmGeneration.writeJvmGeneration(state, pinnedGeneration);
        }
        Path slot = WarmSlot.directory(state, index);
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        if (Boolean.getBoolean("pkb_warm_test_child")) command.add("-Dpkb_warm_test_child=true");
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, entries));
        command.add(WorkbenchWarmMain.class.getName());
        command.add(slot.toAbsolutePath().normalize().toString());
        command.add(watch == null ? "" : watch);
        command.add(Long.toString(WarmProcess.liveOwner(state)));
        command.add(Long.toString(idleSeconds()));
        command.add(pinnedGeneration);
        try {
            Files.createDirectories(slot);
            new ProcessBuilder(command)
                    .directory(project.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                Optional<WarmSlot.Handle> ready = WarmSlot.read(slot);
                if (ready.isPresent() && WarmProcess.alive(ready.get().pid())) return true;
                Thread.sleep(50);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Caller falls back.
        }
        return WarmSlot.read(slot).filter(handle -> WarmProcess.alive(handle.pid())).isPresent();
    }

    static int poolMax() {
        return poolMax(System::getProperty, System::getenv);
    }

    static int poolMax(Function<String, String> property, Function<String, String> env) {
        String raw = configured(property, env, POOL_PROPERTY, POOL_ENV, POOL_RUNVAR);
        if (raw == null) return 2;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > 8) return 2;
            return value;
        } catch (NumberFormatException failure) {
            return 2;
        }
    }

    static int queueSeconds() {
        Integer cli = WARM_WAIT.get();
        if (cli != null && cli >= 0) return cli;
        return queueSeconds(System::getProperty, System::getenv);
    }

    static int queueSeconds(Function<String, String> property, Function<String, String> env) {
        String raw = configured(property, env, QUEUE_PROPERTY, QUEUE_ENV, QUEUE_RUNVAR);
        if (raw == null) return 0;
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(value, 0);
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static String configured(
            Function<String, String> property,
            Function<String, String> env,
            String propertyKey,
            String envKey,
            String runvarKey
    ) {
        Function<String, String> readProperty = property == null ? key -> null : property;
        Function<String, String> readEnv = env == null ? key -> null : env;
        return firstNonBlank(
                readProperty.apply(propertyKey),
                readEnv.apply(envKey),
                readProperty.apply(runvarKey),
                compactValue(readProperty.apply("pkb_runvars"), propertyKey)
        );
    }

    private static Long timeoutMillis(Long runTimeoutSeconds) {
        if (runTimeoutSeconds == null || runTimeoutSeconds <= 0) return null;
        try {
            return Math.multiplyExact(runTimeoutSeconds, 1000L);
        } catch (ArithmeticException overflow) {
            return (long) Integer.MAX_VALUE;
        }
    }

    private static Path poolState(Path releaseState) {
        if (WarmSlot.isSlotDirectory(releaseState)
                && releaseState.getParent() != null
                && releaseState.getParent().getParent() != null) {
            return releaseState.getParent().getParent();
        }
        return releaseState;
    }

    private static void poolNote(Path project, Path folder, int slot, String action, String extra) {
        String detail = extra == null || extra.isBlank() ? "" : extra;
        WorkbenchLaunchTiming.note(folder, "warm-pool slot=" + slot + " action=" + action, detail);
        String line = "slot=" + slot + " action=" + action + (detail.isBlank() ? "" : " " + detail);
        WarmPoolLog.append(state(project), line);
        try {
            String runId = folder == null || folder.getFileName() == null ? "-" : folder.getFileName().toString();
            AgentLog.append(project, java.time.Instant.now(), "launcher", runId, "warm-pool", line, null);
        } catch (Exception ignored) {
            // launch-timing.txt still has the decision.
        }
    }

    private static List<String> generationProperties(Path state, List<String> dashD) {
        List<String> tokens = new ArrayList<>(dashD == null ? List.of() : dashD);
        WarmGeneration.Snapshot snapshot = WarmGeneration.readActive(state);
        if (snapshot == null || snapshot.testClasses() == null) return tokens;
        String token = "-D" + WarmGeneration.TEST_CLASSES_PROPERTY + "=" + snapshot.testClasses();
        if (tokens.stream().noneMatch(item -> item != null && item.startsWith("-D" + WarmGeneration.TEST_CLASSES_PROPERTY + "="))) {
            tokens.add(token);
        }
        return tokens;
    }

    record SlotView(int slot, long pid, int port, String state, String runId, String generation, long ageSeconds) {
    }

    static List<SlotView> warmSlots(Path project) {
        if (project == null) return List.of();
        Path root = state(project);
        List<SlotView> views = new ArrayList<>();
        WarmProcess.read(root).ifPresent(handle -> views.add(view(0, root, handle.pid(), handle.port(), "", handle.watchHash())));
        for (WarmSlot.Member member : WarmSlot.list(root)) {
            views.add(view(
                    member.index(),
                    member.directory(),
                    member.handle().pid(),
                    member.handle().port(),
                    member.handle().generation(),
                    member.handle().watchHash()
            ));
        }
        return List.copyOf(views);
    }

    private static SlotView view(int slot, Path directory, long pid, int port, String generation, String watch) {
        boolean alive = WarmProcess.alive(pid);
        WarmPool.Ping ping = alive ? WarmPool.ping(port) : WarmPool.Ping.unknown();
        String run = ping.busy() ? ping.runId() : "";
        if (run.isBlank() && alive) run = WarmSlot.busyRun(directory);
        String stateName = !alive ? "dead" : ping.busy() || !run.isBlank() ? "busy" : "idle";
        long age = 0;
        Path handle = WarmSlot.isSlotDirectory(directory) ? WarmSlot.handleFile(directory) : WarmProcess.file(directory);
        try {
            if (Files.isRegularFile(handle)) {
                age = Math.max(0L, (System.currentTimeMillis() - Files.getLastModifiedTime(handle).toMillis()) / 1000L);
            }
        } catch (Exception ignored) {
            age = 0;
        }
        String shownGeneration = generation == null || generation.isBlank() ? (watch == null ? "" : watch) : generation;
        return new SlotView(slot, pid, port, stateName, run == null ? "" : run, shownGeneration, age);
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
