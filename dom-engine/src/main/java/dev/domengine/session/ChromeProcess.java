package dev.domengine.session;

import dev.domengine.cdp.CdpException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One Chrome process started with {@code --remote-debugging-port=0}.
 * The port is read from {@code DevToolsActivePort} so a free-port race cannot
 * hand the debugger to another process. Tests and {@link BrowserSession} share
 * this launcher.
 */
public final class ChromeProcess {
    public static final long DEBUGGER_WAIT_SECONDS = 30;
    /** Each attempt waits this long for {@code DevToolsActivePort}. A timeout relaunches once. */
    static final long PORT_WAIT_SECONDS = 45;

    private final Process process;
    private final Path profile;
    private final int port;

    private ChromeProcess(Process process, Path profile, int port) {
        this.process = process;
        this.profile = profile;
        this.port = port;
    }

    /** A profile directory outside any JUnit {@code @TempDir}. */
    public static Path newProfile() {
        try {
            return Files.createTempDirectory("pickleball-chrome-");
        } catch (IOException failure) {
            throw new CdpException("chrome profile: " + failure.getMessage());
        }
    }

    /**
     * Starts headless (or headed) Chrome. {@code extraArgs} are Chrome flags.
     * {@code initialUrl} is the page argument, or {@code about:blank} when blank.
     */
    public static ChromeProcess start(String binary, Path profile, boolean headless, List<String> extraArgs, String initialUrl) {
        return start(binary, profile, headless, extraArgs, initialUrl, PORT_WAIT_SECONDS);
    }

    /**
     * Same launcher with an explicit port wait. A {@code DevToolsActivePort} timeout
     * wipes {@code profile} and relaunches once. Other failures are not retried.
     */
    static ChromeProcess start(String binary, Path profile, boolean headless, List<String> extraArgs, String initialUrl, long portWaitSeconds) {
        if (binary == null || binary.isBlank()) throw new CdpException("chrome binary is missing");
        Path dir = profile;
        try {
            if (dir == null) dir = newProfile();
            else Files.createDirectories(dir);
        } catch (IOException failure) {
            throw new CdpException("profile: " + failure.getMessage());
        }
        try {
            return spawn(binary, dir, headless, extraArgs, initialUrl, portWaitSeconds);
        } catch (CdpException failure) {
            if (!isPortTimeout(failure)) throw failure;
            try {
                resetProfile(dir);
            } catch (CdpException cleanup) {
                throw new CdpException(failure.getMessage() + " relaunch-cleanup=" + cleanup.getMessage());
            }
            try {
                return spawn(binary, dir, headless, extraArgs, initialUrl, portWaitSeconds);
            } catch (CdpException again) {
                if (!isPortTimeout(again)) throw again;
                throw new CdpException(again.getMessage() + " relaunch=1");
            }
        }
    }

    private static ChromeProcess spawn(String binary, Path dir, boolean headless, List<String> extraArgs, String initialUrl, long portWaitSeconds) {
        List<String> command = command(binary, dir, headless, extraArgs, initialUrl);
        Process handle;
        Path log = dir.resolve("launch.log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
            // The JVM bus stays as-is so AT-SPI can use it. Only this browser child changes.
            sanitizeSessionBus(builder.environment());
            handle = builder.start();
        } catch (IOException failure) {
            throw new CdpException("chrome launch: " + failure.getMessage());
        }
        int chosen;
        try {
            chosen = awaitPort(dir, handle, portWaitSeconds);
        } catch (RuntimeException failure) {
            destroyTree(handle);
            releaseProfile(dir);
            throw failure;
        }
        return new ChromeProcess(handle, dir, chosen);
    }

    private static List<String> command(String binary, Path dir, boolean headless, List<String> extraArgs, String initialUrl) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + dir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-background-networking");
        command.add("--disable-component-update");
        command.add("--disable-extensions");
        command.add("--disable-default-apps");
        command.add("--disable-sync");
        command.add("--disable-breakpad");
        command.add("--disable-features=Translate,MediaRouter,DialMediaRouteProvider,OptimizationHints");
        command.add("--no-sandbox");
        command.add("--disable-dev-shm-usage");
        command.add("--disable-gpu");
        command.add("--remote-allow-origins=*");
        if (headless) command.add("--headless=new");
        if (extraArgs != null) {
            for (String arg : extraArgs) {
                if (arg != null && !arg.isBlank()) command.add(arg);
            }
        }
        command.add(initialUrl == null || initialUrl.isBlank() ? "about:blank" : initialUrl);
        return command;
    }

    /**
     * Chromium logs a parse error and can skip {@code DevToolsActivePort} when
     * {@code DBUS_SESSION_BUS_ADDRESS} is not {@code unix:} or {@code tcp:}.
     * {@code disabled:} stops that retry loop on this process only.
     */
    static void sanitizeSessionBus(Map<String, String> environment) {
        if (environment == null) return;
        String address = environment.get("DBUS_SESSION_BUS_ADDRESS");
        if (address == null || address.isBlank()) return;
        String trimmed = address.trim();
        if (trimmed.startsWith("unix:") || trimmed.startsWith("tcp:")) return;
        environment.put("DBUS_SESSION_BUS_ADDRESS", "disabled:");
    }

    private static boolean isPortTimeout(CdpException failure) {
        String message = failure.getMessage();
        return message != null && message.contains("timed out waiting for DevToolsActivePort");
    }

    private static void resetProfile(Path dir) {
        releaseProfile(dir);
        deleteProfile(dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException failure) {
            throw new CdpException("profile: " + failure.getMessage());
        }
    }

    public Process process() {
        return process;
    }

    public Path profile() {
        return profile;
    }

    public int port() {
        return port;
    }

    public String http() {
        return "http://127.0.0.1:" + port;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Polls {@code /json/version} for up to 30 seconds. */
    public boolean debuggerOpen() {
        URI version = URI.create(http() + "/json/version");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEBUGGER_WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) version.toURL().openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                try {
                    if (connection.getResponseCode() == 200) return true;
                } finally {
                    connection.disconnect();
                }
            } catch (IOException ignored) {
                // Chrome is still starting.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Destroys child processes, then this process, waits up to 10 seconds,
     * then force-destroys anything still alive.
     */
    public void destroyTree() {
        destroyTree(process);
        releaseProfile(profile);
    }

    public static void destroyTree(Process process) {
        if (process == null) return;
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        if (process.isAlive()) process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            } else {
                handle.descendants().forEach(child -> {
                    if (child.isAlive()) child.destroyForcibly();
                });
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * Stops every process whose command line still names this profile.
     * A Chromium launcher can exit after its browser child is up; that child
     * is no longer a descendant of the process this JVM started.
     *
     * @return true when at least one process was signaled
     */
    public static boolean releaseProfile(Path profile) {
        if (profile == null) return false;
        String marker = profile.toAbsolutePath().toString();
        boolean signaled = false;
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            if (!commandLine(handle).contains(marker)) continue;
            signaled = true;
            handle.descendants().forEach(child -> {
                child.destroy();
                child.destroyForcibly();
            });
            handle.destroy();
            handle.destroyForcibly();
        }
        return signaled;
    }

    /** Deletes a profile directory, retrying while Chrome still has files open. */
    public static void deleteProfile(Path profile) {
        if (profile == null || !Files.exists(profile)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                deleteNow(profile);
                return;
            } catch (IOException | UncheckedIOException failure) {
                last = failure instanceof IOException io ? io
                        : new IOException(failure.getMessage(), failure);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (Files.exists(profile)) {
            throw new CdpException("chrome profile was not deleted: " + profile
                    + (last == null ? "" : " (" + last.getMessage() + ")"));
        }
    }

    private static void deleteNow(Path profile) throws IOException {
        if (!Files.exists(profile)) return;
        try (Stream<Path> walk = Files.walk(profile)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static int awaitPort(Path profile, Process process, long timeoutSeconds) {
        Path file = profile.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            Integer port = readPort(file);
            if (port != null) return port;
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CdpException("chrome launch interrupted");
            }
        }
        Integer exit = null;
        if (!process.isAlive()) {
            try {
                exit = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                exit = null;
            }
        }
        String tail = launchTail(profile);
        throw new CdpException("timed out waiting for DevToolsActivePort"
                + (exit == null ? "" : " launcher-exit=" + exit)
                + (tail.isEmpty() ? "" : " log=" + tail));
    }

    private static Integer readPort(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            String line = Files.readString(file).lines().findFirst().orElse("").trim();
            if (line.isEmpty()) return null;
            return Integer.parseInt(line);
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private static String launchTail(Path profile) {
        Path log = profile.resolve("launch.log");
        if (!Files.isRegularFile(log)) return "";
        try {
            return collapseLog(Files.readString(log));
        } catch (IOException failure) {
            return "";
        }
    }

    /** Repeated stderr lines become one line plus a count, so a dbus loop does not hide the rest. */
    static String collapseLog(String text) {
        if (text == null || text.isBlank()) return "";
        List<String> collapsed = new ArrayList<>();
        String previous = null;
        int run = 0;
        for (String raw : text.split("\\R", -1)) {
            String line = raw.replace('\r', ' ').trim();
            if (line.isEmpty()) continue;
            if (line.equals(previous)) {
                run++;
                continue;
            }
            appendRun(collapsed, previous, run);
            previous = line;
            run = 1;
        }
        appendRun(collapsed, previous, run);
        if (collapsed.isEmpty()) return "";
        String joined = String.join(" | ", collapsed);
        if (joined.length() <= 900) return joined;
        String head = collapsed.get(0);
        String tail = collapsed.get(collapsed.size() - 1);
        if (collapsed.size() == 1) {
            return head.substring(0, 400) + " … " + head.substring(head.length() - 400);
        }
        return head + " | … " + (collapsed.size() - 2) + " more | " + tail;
    }

    private static void appendRun(List<String> collapsed, String line, int run) {
        if (line == null || run <= 0) return;
        collapsed.add(run == 1 ? line : line + " (x" + run + ")");
    }

    private static String commandLine(ProcessHandle handle) {
        String reported = handle.info().commandLine().orElse("");
        if (!reported.isEmpty()) return reported;
        Path cmdline = Path.of("/proc", Long.toString(handle.pid()), "cmdline");
        try {
            return Files.readString(cmdline).replace('\0', ' ');
        } catch (IOException failure) {
            return "";
        }
    }
}
