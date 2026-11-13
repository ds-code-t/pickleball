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
        if (binary == null || binary.isBlank()) throw new CdpException("chrome binary is missing");
        Path dir = profile;
        try {
            if (dir == null) dir = newProfile();
            else Files.createDirectories(dir);
        } catch (IOException failure) {
            throw new CdpException("profile: " + failure.getMessage());
        }
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + dir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-background-networking");
        command.add("--disable-component-update");
        command.add("--no-sandbox");
        command.add("--disable-dev-shm-usage");
        command.add("--disable-gpu");
        if (headless) command.add("--headless=new");
        if (extraArgs != null) {
            for (String arg : extraArgs) {
                if (arg != null && !arg.isBlank()) command.add(arg);
            }
        }
        command.add(initialUrl == null || initialUrl.isBlank() ? "about:blank" : initialUrl);
        Process handle;
        try {
            handle = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException failure) {
            throw new CdpException("chrome launch: " + failure.getMessage());
        }
        int chosen = awaitPort(dir, handle, DEBUGGER_WAIT_SECONDS);
        return new ChromeProcess(handle, dir, chosen);
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
            if (!process.isAlive()) throw new CdpException("chrome exited before DevToolsActivePort");
            if (Files.isRegularFile(file)) {
                try {
                    String line = Files.readString(file).lines().findFirst().orElse("").trim();
                    if (!line.isEmpty()) return Integer.parseInt(line);
                } catch (IOException | NumberFormatException ignored) {
                    // Chrome is still writing the file.
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CdpException("chrome launch interrupted");
            }
        }
        throw new CdpException("timed out waiting for DevToolsActivePort");
    }
}
