package tools.dscode.common.agentsurface;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Builds the app-mode argv and starts the browser. The process tree is killed on close. */
public final class PopupLauncher {
    private PopupLauncher() {
    }

    public static List<String> argv(String browser, String url, Path profile, int width, int height, int x, int y, boolean headless) {
        return argv(browser, url, profile, width, height, x, y, headless, false);
    }

    /**
     * {@code noSandbox} adds {@code --no-sandbox}. The host passes it only after the sandboxed launch
     * exited with a sandbox error (Linux without user namespaces or a SUID helper, or root), or when
     * {@code pkb_agent_popup_no_sandbox=true}. The popup loads only its own loopback bridge page.
     */
    public static List<String> argv(String browser, String url, Path profile, int width, int height, int x, int y, boolean headless, boolean noSandbox) {
        List<String> command = new ArrayList<>();
        command.add(browser);
        command.add("--app=" + url);
        command.add("--user-data-dir=" + profile);
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-features=Translate");
        command.add("--window-size=" + width + "," + height);
        command.add("--window-position=" + x + "," + y);
        if (noSandbox) command.add("--no-sandbox");
        if (headless) command.add("--headless=new");
        return command;
    }

    /**
     * Starts the browser with its own output file. A null output discards the streams. The browser
     * never shares {@code host.out}: on Windows a child that inherits that handle keeps the file
     * locked after the host exits.
     */
    public static Process start(List<String> command, Path output) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (output != null) {
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            builder.redirectOutput(output.toFile());
            builder.redirectError(ProcessBuilder.Redirect.appendTo(output.toFile()));
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }

    /** True when the browser output says the sandbox could not start (no user namespaces, no SUID helper, or root). */
    public static boolean sandboxFailure(Path output) {
        if (output == null || !Files.isRegularFile(output)) return false;
        try {
            String text = Files.readString(output).toLowerCase(Locale.ROOT);
            return text.contains("no usable sandbox")
                    || text.contains("without --no-sandbox")
                    || text.contains("zygote_host_impl_linux")
                    || text.contains("sandbox_linux")
                    || text.contains("chrome-sandbox");
        } catch (Exception failure) {
            return false;
        }
    }

    /** The first output line that names the sandbox, trimmed to 300 characters, or an empty string. */
    public static String sandboxLine(Path output) {
        if (output == null || !Files.isRegularFile(output)) return "";
        try {
            for (String line : Files.readAllLines(output)) {
                if (line.toLowerCase(Locale.ROOT).contains("sandbox")) return line.length() > 300 ? line.substring(0, 300) : line;
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    /** Stops the browser and its children, then waits so the host reaps it and no zombie keeps the pid. */
    public static void destroyTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
