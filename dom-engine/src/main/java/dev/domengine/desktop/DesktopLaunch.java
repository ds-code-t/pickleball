package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Windows {@code desktop launch}. Other operating systems refuse before a process starts.
 * The launched process stays up after a successful attach so the window can be used.
 */
public final class DesktopLaunch {
    private static final Map<Long, StringBuilder> STDERR = new ConcurrentHashMap<>();

    private DesktopLaunch() {
    }

    /** True when {@code os.name} is a Windows name. */
    public static boolean supportedOs(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /** Null on Windows. Otherwise the two-line refusal. */
    public static String refusalFor(String osName) {
        if (supportedOs(osName)) return null;
        return "error: desktop launch is implemented for Windows\nfix: run this verb on Windows";
    }

    /** Starts {@code exe}. A {@code .cmd} or {@code .bat} runs through {@code cmd.exe /c}. */
    public static Process start(Path exe) throws IOException {
        if (exe == null) throw new IOException("missing exe");
        Path absolute = exe.toAbsolutePath().normalize();
        String path = absolute.toString();
        String lower = path.toLowerCase(Locale.ROOT);
        List<String> command = new ArrayList<>();
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            command.add("cmd.exe");
            command.add("/c");
            command.add(absolute.getFileName().toString());
        } else {
            command.add(path);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        Path parent = absolute.getParent();
        if (parent != null) builder.directory(parent.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        pump(process);
        return process;
    }

    /**
     * Attaches with the existing live Windows path. The first try is immediate.
     * A miss is retried until {@code budget} elapses. The process is not destroyed here.
     */
    public static DesktopTree.Session attachWhenVisible(String name, Duration budget, Process process) {
        long end = System.nanoTime() + (budget == null ? Duration.ofSeconds(60) : budget).toNanos();
        CdpException last = null;
        while (true) {
            if (process != null && !process.isAlive()) {
                throw new CdpException("desktop launch exited before the window appeared\n" + stderrTail(process, 500));
            }
            try {
                return DesktopTree.attachLive("launch-" + Long.toUnsignedString(System.nanoTime()), name);
            } catch (CdpException failure) {
                last = failure;
            }
            if (System.nanoTime() >= end) break;
            try {
                Thread.sleep(400);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String tail = process == null ? "" : stderrTail(process, 500);
        String message = last == null || last.getMessage() == null ? "not found: " + name : last.getMessage();
        if (!tail.isBlank()) message = message + "\n" + tail;
        throw new CdpException(message);
    }

    /** Root box from {@link WindowsUia}, or the first child box when the root has none. */
    public static DesktopScreenshot.Rect windowRect(String name) {
        return boxOf(WindowsUia.capture(name));
    }

    static DesktopScreenshot.Rect boxOf(DomNode node) {
        if (node == null) return null;
        if (node.hasBox && node.width > 0 && node.height > 0) {
            return new DesktopScreenshot.Rect(
                    (int) Math.round(node.x),
                    (int) Math.round(node.y),
                    Math.max(1, (int) Math.round(node.width)),
                    Math.max(1, (int) Math.round(node.height))
            );
        }
        for (DomNode child : node.children) {
            DesktopScreenshot.Rect found = boxOf(child);
            if (found != null) return found;
        }
        return null;
    }

    /** Writes the PNG and closes it before the caller returns. */
    public static void writePng(Path file, byte[] png) throws IOException {
        Path target = file == null ? Path.of("screenshot.png") : file;
        byte[] payload = png == null ? new byte[0] : png;
        Path parent = target.getParent();
        Path temp = target.resolveSibling(target.getFileName().toString() + ".part");
        if (parent != null) Files.createDirectories(parent);
        Files.write(temp, payload);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String stderrTail(Process process, int cap) {
        if (process == null) return "";
        StringBuilder buffer = STDERR.get(process.pid());
        if (buffer == null) return "";
        String text;
        synchronized (buffer) {
            text = buffer.toString();
        }
        int limit = Math.max(0, cap);
        if (text.length() <= limit) return text;
        return text.substring(text.length() - limit);
    }

    public static void destroy(Process process) {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        STDERR.remove(process.pid());
    }

    public static void destroyPid(long pid) {
        ProcessHandle.of(pid).ifPresent(handle -> {
            handle.descendants().forEach(ProcessHandle::destroy);
            handle.destroy();
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
            STDERR.remove(pid);
        });
    }

    private static void pump(Process process) {
        StringBuilder buffer = new StringBuilder();
        STDERR.put(process.pid(), buffer);
        Thread thread = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] chunk = new byte[512];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    synchronized (buffer) {
                        if (buffer.length() < 4000) buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // The tail is whatever arrived.
            }
        }, "desktop-launch-err");
        thread.setDaemon(true);
        thread.start();
    }
}
