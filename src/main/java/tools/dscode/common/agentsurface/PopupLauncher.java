package tools.dscode.common.agentsurface;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds the app-mode argv and starts the browser. The process tree is killed on close. */
public final class PopupLauncher {
    private PopupLauncher() {
    }

    public static List<String> argv(String browser, String url, Path profile, int width, int height, int x, int y, boolean headless) {
        List<String> command = new ArrayList<>();
        command.add(browser);
        command.add("--app=" + url);
        command.add("--user-data-dir=" + profile);
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-features=Translate");
        command.add("--window-size=" + width + "," + height);
        command.add("--window-position=" + x + "," + y);
        if (headless) command.add("--headless=new");
        return command;
    }

    public static Process start(List<String> command, Path output) throws IOException {
        if (output != null && output.getParent() != null) Files.createDirectories(output.getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (output != null) {
            builder.redirectOutput(output.toFile());
            builder.redirectError(ProcessBuilder.Redirect.appendTo(output.toFile()));
        }
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }

    public static void destroyTree(Process process) {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
