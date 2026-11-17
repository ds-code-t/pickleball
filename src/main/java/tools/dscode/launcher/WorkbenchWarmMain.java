package tools.dscode.launcher;

import tools.dscode.control.protocol.speed.ProcessTree;
import tools.dscode.control.protocol.speed.WarmProcess;
import tools.dscode.control.protocol.speed.WarmSlot;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One long-lived JVM. Later CLI commands connect instead of starting Maven or a
 * new process. Each command runs in a fresh class loader so runner statics
 * do not leak. A one-shot CLI is not an owner. The process exits when a
 * long-lived owner dies (Workbench, MCP host, or session), when it has been
 * idle, or when stop or session teardown deletes the handle and kills the tree.
 */
public final class WorkbenchWarmMain {
    private static final AtomicBoolean EXITING = new AtomicBoolean();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile long parentPid;
    private static volatile boolean oneShotRun;
    private static volatile String currentRunId = "";
    private static volatile long lastUsedNanos = System.nanoTime();
    private static volatile long idleNanos = TimeUnit.SECONDS.toNanos(WarmProcess.DEFAULT_IDLE_SECONDS);
    private static volatile Runnable afterStarted;
    private static volatile Path stateDir;

    private WorkbenchWarmMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length >= 1 && "hold".equals(args[0])) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (args == null || args.length < 2) {
            System.err.println("error: warm JVM needs a state directory and a watch hash.");
            System.err.println("fix: the launcher starts this process. Do not invoke it.");
            System.exit(2);
            return;
        }
        Path state = Path.of(args[0]);
        stateDir = state;
        String watch = args[1];
        parentPid = args.length >= 3 ? positiveLong(args[2]) : 0L;
        long idleSeconds = args.length >= 4 ? positiveLong(args[3]) : 0L;
        String generation = args.length >= 5 ? args[4] : "";
        boolean slot = WarmSlot.isSlotDirectory(state);
        if (idleSeconds <= 0) idleSeconds = WarmProcess.DEFAULT_IDLE_SECONDS;
        try {
            idleNanos = TimeUnit.SECONDS.toNanos(idleSeconds);
        } catch (ArithmeticException overflow) {
            idleNanos = TimeUnit.SECONDS.toNanos(WarmProcess.DEFAULT_IDLE_SECONDS);
        }
        lastUsedNanos = System.nanoTime();
        boolean started = false;
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            long pid = ProcessHandle.current().pid();
            if (slot) {
                WarmSlot.write(state, new WarmSlot.Handle(pid, server.getLocalPort(), generation, parentPid, watch));
            } else {
                WarmProcess.write(state, new WarmProcess.Handle(pid, server.getLocalPort(), watch));
            }
            spawnTestChild(state);
            started = true;
            Thread watchThread = new Thread(() -> watch(state), "pickleball-warm-idle");
            watchThread.setDaemon(true);
            watchThread.start();
            while (!EXITING.get()) {
                try {
                    acceptOne(server);
                } catch (Exception failure) {
                    if (EXITING.get()) break;
                    System.err.println("warm JVM request failed: " + failure.getMessage());
                }
            }
        } finally {
            if (started) shutdown(state);
        }
    }

    /**
     * Parent death exits even during a run. Idle does not.
     * A missing state file exits even during a run.
     * {@code parentPid} of zero is not watched.
     */
    static boolean exitDue(long parent, boolean running, long idleLimitNanos, long usedAt, long now) {
        return exitDue(parent, running, idleLimitNanos, usedAt, now, false);
    }

    static boolean exitDue(
            long parent,
            boolean running,
            long idleLimitNanos,
            long usedAt,
            long now,
            boolean stateMissing
    ) {
        if (stateMissing) return true;
        if (parent > 0 && !WarmProcess.alive(parent)) return true;
        if (running || idleLimitNanos <= 0) return false;
        return now - usedAt >= idleLimitNanos;
    }

    private static void watch(Path state) {
        while (!EXITING.get()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean missing = !Files.isRegularFile(handleFile(state));
            if (!missing) {
                long filed = WarmProcess.readOwner(ownerRoot(state));
                boolean running = BUSY.get();
                if (filed > 0 && !(running && oneShotRun)) parentPid = filed;
            }
            boolean running = BUSY.get();
            long parent = running && oneShotRun ? 0L : parentPid;
            if (exitDue(parent, running, idleNanos, lastUsedNanos, System.nanoTime(), missing)) shutdown(state);
            if (!running && WarmSlot.retirePending(state)) shutdown(state);
        }
    }

    private static void shutdown(Path state) {
        if (!EXITING.compareAndSet(false, true)) return;
        try {
            ProcessTree.killDescendants(ProcessHandle.current());
        } catch (RuntimeException ignored) {
            // A child can exit between the snapshot and the kill.
        }
        WarmSlot.clearBusy(state);
        if (WarmSlot.isSlotDirectory(state)) {
            try {
                Files.deleteIfExists(WarmSlot.handleFile(state));
                Files.deleteIfExists(state.resolve(WarmProcess.PID_FILE_NAME));
            } catch (java.io.IOException ignored) {
                // The killer also deletes the handle.
            }
        } else {
            WarmProcess.delete(state);
            WarmProcess.deleteRememberedPid(state);
        }
        System.exit(0);
    }

    /**
     * Accept without blocking the next connection on a long RUN.
     * A second RUN can then receive EXIT 2 instead of sitting in the backlog
     * until the handshake timeout kills this JVM.
     */
    static void acceptOne(ServerSocket server) throws java.io.IOException {
        Socket socket = server.accept();
        Thread worker = new Thread(() -> {
            try (Socket ignored = socket) {
                serve(socket);
            } catch (Exception failure) {
                if (!EXITING.get()) {
                    System.err.println("warm JVM request failed: " + failure.getMessage());
                }
            }
        }, "pickleball-warm-serve");
        worker.setDaemon(true);
        worker.start();
    }

    static void serve(Socket socket) throws Exception {
        socket.setSoTimeout(180_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        String verb = reader.readLine();
        if ("PING".equals(verb)) {
            lastUsedNanos = System.nanoTime();
            if (BUSY.get()) writer.println("PONG busy " + currentRunId);
            else writer.println("PONG idle");
            return;
        }
        if (!"RUN".equals(verb)) {
            writer.println("EXIT 2");
            writer.println("END");
            return;
        }
        lastUsedNanos = System.nanoTime();
        List<String> dashD = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !".".equals(line)) dashD.add(line);
        String projectLine = reader.readLine();
        adoptParent(reader.readLine());
        if (!BUSY.compareAndSet(false, true)) {
            writer.println("EXIT 2");
            writer.println("END");
            return;
        }
        Path project = projectLine == null || projectLine.isBlank() ? Path.of("") : Path.of(projectLine);
        currentRunId = runIdOf(dashD);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PrintStream out = System.out;
        PrintStream err = System.err;
        int exit = 1;
        ClassLoader loader = null;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            loader = newLoader();
            writer.println("STARTED");
            WarmSlot.markBusy(stateDir, currentRunId);
            Runnable hook = afterStarted;
            if (hook != null) hook.run();
            System.setOut(tee);
            System.setErr(tee);
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> type = Class.forName("tools.dscode.launcher.WorkbenchDirectRun", true, loader);
            exit = (Integer) type.getMethod("run", Path.class, List.class).invoke(null, project, dashD);
        } catch (Throwable failure) {
            exit = 1;
            tee.println("error: warm run failed: " + failure.getMessage());
            tee.println("fix: the wrapper path still runs this command.");
        } finally {
            BUSY.set(false);
            currentRunId = "";
            WarmSlot.clearBusy(stateDir);
            lastUsedNanos = System.nanoTime();
            Thread.currentThread().setContextClassLoader(previous);
            System.setOut(out);
            System.setErr(err);
            loader = null;
            if (WarmSlot.retirePending(stateDir)) shutdown(stateDir);
        }
        writer.println("EXIT " + exit);
        for (String capturedLine : captured.toString(StandardCharsets.UTF_8).split("\\R", -1)) {
            if ("END".equals(capturedLine)) capturedLine = "END ";
            writer.println(capturedLine);
        }
        writer.println("END");
    }

    /** Tests mark an in-flight run so a second {@code RUN} is rejected. */
    static void setBusy(boolean value) {
        BUSY.set(value);
    }

    /** Tests hold the worker after STARTED so a second accept can run. */
    static void setAfterStarted(Runnable hook) {
        afterStarted = hook;
    }

    private static void adoptParent(String line) {
        long adopted = positiveLong(line);
        if (adopted > 0) {
            parentPid = adopted;
            oneShotRun = false;
        } else {
            oneShotRun = true;
        }
        lastUsedNanos = System.nanoTime();
    }

    private static Path handleFile(Path state) {
        return WarmSlot.isSlotDirectory(state) ? WarmSlot.handleFile(state) : WarmProcess.file(state);
    }

    /** A slot's owner file lives on the workbench state root, two levels up. */
    private static Path ownerRoot(Path state) {
        if (WarmSlot.isSlotDirectory(state) && state.getParent() != null && state.getParent().getParent() != null) {
            return state.getParent().getParent();
        }
        return state;
    }

    private static String runIdOf(List<String> dashD) {
        if (dashD == null) return "";
        String prefix = "-Dpkb_run_id=";
        for (String token : dashD) {
            if (token != null && token.startsWith(prefix)) return token.substring(prefix.length()).trim();
        }
        return "";
    }

    private static long positiveLong(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : 0L;
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }

    /** Windows-safe sleeper so a test can prove the process tree dies. Not used in production. */
    private static void spawnTestChild(Path state) {
        if (!Boolean.getBoolean("pkb_warm_test_child")) return;
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            String javaBin = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
            Path cpFile = state.resolve("warm-child-cp.txt");
            Files.writeString(cpFile, System.getProperty("java.class.path", ""), StandardCharsets.UTF_8);
            new ProcessBuilder(
                    javaBin,
                    "-cp",
                    "@" + cpFile.toAbsolutePath().normalize(),
                    WorkbenchWarmMain.class.getName(),
                    "hold"
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception failure) {
            System.err.println("warm test child failed: " + failure.getMessage());
        }
    }

    private static ClassLoader newLoader() {
        String classpath = System.getProperty("java.class.path", "");
        List<java.net.URL> urls = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
            if (entry.isBlank()) continue;
            try {
                urls.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
            } catch (java.net.MalformedURLException ignored) {
                // Skip a broken entry. The fallback path still runs.
            }
        }
        return new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}
