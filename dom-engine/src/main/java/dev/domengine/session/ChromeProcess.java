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
    private final Path launchLog;
    private final int port;

    private ChromeProcess(Process process, Path profile, int port, Path launchLog) {
        this.process = process;
        this.profile = profile;
        this.launchLog = launchLog;
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
        Path log;
        try {
            log = Files.createTempFile("pickleball-chrome-", ".log");
        } catch (IOException failure) {
            throw new CdpException("chrome launch log: " + failure.getMessage());
        }
        Process handle;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            // Not the caller's cwd. A Windows child keeps that directory locked after it is reparented.
            builder.directory(Path.of(System.getProperty("java.io.tmpdir", ".")).toFile());
            // The JVM bus stays as-is so AT-SPI can use it. Only this browser child changes.
            sanitizeSessionBus(builder.environment());
            handle = builder.start();
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // The temp log is outside the profile.
            }
            throw new CdpException("chrome launch: " + failure.getMessage());
        }
        int chosen;
        try {
            chosen = awaitPort(dir, handle, portWaitSeconds, log);
        } catch (RuntimeException failure) {
            destroyTree(handle);
            releaseProfile(dir);
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // Windows may still have the redirect handle. The file is not in the profile.
            }
            throw failure;
        }
        return new ChromeProcess(handle, dir, chosen, log);
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
        if (launchLog != null) {
            try {
                Files.deleteIfExists(launchLog);
            } catch (IOException ignored) {
                // The redirect handle can outlive the process on Windows. The log is not in the profile.
            }
        }
    }

    public static void destroyTree(Process process) {
        if (process == null) return;
        ProcessHandle handle = process.toHandle();
        List<Long> pids = new ArrayList<>();
        pids.add(handle.pid());
        try {
            handle.descendants().forEach(child -> pids.add(child.pid()));
        } catch (RuntimeException ignored) {
            // A dead process has no descendants to add.
        }
        handle.descendants().forEach(ProcessHandle::destroy);
        if (process.isAlive()) process.destroy();
        taskkill(pids);
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                taskkill(pids);
                process.waitFor(5, TimeUnit.SECONDS);
            } else {
                handle.descendants().forEach(child -> {
                    if (child.isAlive()) child.destroyForcibly();
                });
                taskkill(pids);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            taskkill(pids);
        }
    }

    /** Windows reparents Chrome. {@code taskkill /T} still reaches that tree. No-op elsewhere. */
    private static void taskkill(List<Long> pids) {
        if (!isWindows() || pids == null) return;
        for (long pid : pids) {
            if (pid <= 0 || pid == ProcessHandle.current().pid()) continue;
            try {
                Process killer = new ProcessBuilder("taskkill", "/F", "/T", "/PID", Long.toString(pid))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!killer.waitFor(5, TimeUnit.SECONDS)) killer.destroyForcibly();
            } catch (IOException ignored) {
                // taskkill is absent. destroyForcibly already ran.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
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
        List<String> markers = new ArrayList<>();
        markers.add(profile.toAbsolutePath().toString());
        try {
            String real = profile.toRealPath().toString();
            if (!markers.contains(real)) markers.add(real);
        } catch (IOException ignored) {
            // The directory can already be gone. The recorded path is still the marker.
        }
        boolean signaled = false;
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            if (handle.pid() == ProcessHandle.current().pid()) continue;
            if (!namesAny(commandLine(handle), markers)) continue;
            signaled = true;
            destroyHandle(handle);
        }
        if (!signaled && isWindows() && Files.isDirectory(profile)) signaled = signalWindows(markers);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (signaled && System.nanoTime() < deadline) {
            boolean alive = false;
            for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
                if (handle.pid() == ProcessHandle.current().pid()) continue;
                if (namesAny(commandLine(handle), markers) && handle.isAlive()) {
                    alive = true;
                    destroyHandle(handle);
                }
            }
            if (!alive) break;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return signaled;
    }

    private static void destroyHandle(ProcessHandle handle) {
        handle.descendants().forEach(child -> {
            child.destroy();
            child.destroyForcibly();
        });
        handle.destroy();
        handle.destroyForcibly();
    }

    private static boolean namesAny(String command, List<String> markers) {
        for (String marker : markers) {
            if (names(command, marker)) return true;
        }
        return false;
    }

    /** Path match that ignores slash direction and, on Windows, letter case. */
    private static boolean names(String command, String marker) {
        if (command == null || command.isEmpty() || marker == null || marker.isEmpty()) return false;
        if (command.contains(marker)) return true;
        String hay = command.replace('\\', '/');
        String needle = marker.replace('\\', '/');
        if (isWindows()) {
            hay = hay.toLowerCase(java.util.Locale.ROOT);
            needle = needle.toLowerCase(java.util.Locale.ROOT);
        }
        return hay.contains(needle);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * One filtered CIM query. A full process list has hung Windows runners, so this
     * asks only for chrome.exe and msedge.exe.
     */
    private static boolean signalWindows(List<String> markers) {
        boolean signaled = false;
        for (String marker : markers) {
            for (long pid : windowsPids(marker)) {
                if (pid <= 0 || pid == ProcessHandle.current().pid()) continue;
                ProcessHandle.of(pid).ifPresent(ChromeProcess::destroyHandle);
                taskkill(List.of(pid));
                signaled = true;
            }
        }
        return signaled;
    }

    private static List<Long> windowsPids(String marker) {
        String script = "$n=$env:PKB_PROFILE; "
                + "if ($n) { Get-CimInstance Win32_Process -Filter \"Name = 'chrome.exe' OR Name = 'msedge.exe'\" | "
                + "Where-Object { $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Contains($n.ToLowerInvariant()) } | "
                + "ForEach-Object { $_.ProcessId } }";
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script
        );
        builder.environment().put("PKB_PROFILE", marker);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            List<Long> pids = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    pids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // A CIM header is not a pid.
                }
            }
            return pids;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
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

    private static int awaitPort(Path profile, Process process, long timeoutSeconds, Path log) {
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
        String tail = launchTail(log);
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

    private static String launchTail(Path log) {
        if (log == null || !Files.isRegularFile(log)) return "";
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
