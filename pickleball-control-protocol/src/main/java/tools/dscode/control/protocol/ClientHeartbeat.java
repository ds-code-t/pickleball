package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared attachment clock. The CLI writes it when a page verb reaches the live
 * worker. The controller reads it so a CLI-only session still renews the lease.
 */
public final class ClientHeartbeat {
    public static final String RELATIVE = ".pickleball/workbench/client-heartbeat";

    private ClientHeartbeat() {
    }

    public static Path file(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(RELATIVE);
    }

    public static void touch(Path project) {
        write(project, System.currentTimeMillis());
    }

    public static void write(Path project, long epochMillis) {
        Path target = file(project);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, Long.toString(epochMillis), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A missed heartbeat still falls back to the controller clock.
        }
    }

    public static long lastMillis(Path project) {
        Path target = file(project);
        if (!Files.isRegularFile(target)) return 0L;
        try {
            String text = Files.readString(target).trim();
            if (text.isEmpty()) return 0L;
            return Long.parseLong(text);
        } catch (IOException | NumberFormatException failure) {
            return 0L;
        }
    }
}
