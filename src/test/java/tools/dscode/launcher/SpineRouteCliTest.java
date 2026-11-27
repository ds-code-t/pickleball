package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.runtime.WarmProcessFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spine session} reaches the session handler. Warm verbs reach {@code SpineCli}.
 * The commands are a separate launcher process and an argument file.
 */
class SpineRouteCliTest {
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    @TempDir
    Path project;

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void sessionThenWarmThenStoreThenStop() throws Exception {
        Path args = LauncherCli.projectFile(project);
        try {
            LauncherCli.Capture created = LauncherCli.run(project, args, "spine", "session", "new");
            assertEquals(0, created.code(), created.out() + created.err());
            assertTrue(created.out().contains("\"session\""), created.out() + created.err());
            assertTrue(created.out().contains("agent-1"), created.out() + created.err());

            LauncherCli.Capture started = LauncherCli.run(project, args, "spine", "start");
            assertEquals(0, started.code(), started.out() + started.err());
            assertTrue(started.out().contains("warm=on"), started.out());
            assertTrue(started.out().matches("(?s).*pid=[1-9][0-9]*.*"), started.out());

            LauncherCli.Capture status = LauncherCli.run(project, args, "spine", "warm", "status");
            assertEquals(0, status.code(), status.out() + status.err());
            assertTrue(status.out().contains("warm=on"), status.out());
            assertTrue(status.out().matches("(?s).*pid=[1-9][0-9]*.*"), status.out());

            LauncherCli.Capture store = LauncherCli.run(project, args, "store", "status");
            assertEquals(0, store.code(), store.out() + store.err());
            assertTrue(store.out().contains("enabled="), store.out());
            assertTrue(
                    store.out().contains("store=embedded") || store.out().contains("Store database missing"),
                    store.out());

            LauncherCli.Capture stopped = LauncherCli.run(project, args, "spine", "stop");
            assertEquals(0, stopped.code(), stopped.out() + stopped.err());
            assertTrue(stopped.out().contains("warm=off"), stopped.out());
            assertTrue(stopped.out().contains("pid="), stopped.out());
        } finally {
            killWarm(project);
        }
    }

    private static void killWarm(Path project) {
        Path file = WarmProcessFile.file(project);
        if (!Files.isRegularFile(file)) return;
        try {
            Matcher pid = PID.matcher(Files.readString(file, StandardCharsets.UTF_8));
            if (!pid.find()) return;
            long id = Long.parseLong(pid.group(1));
            if (id > 0 && id != ProcessHandle.current().pid()) {
                ProcessHandle.of(id).ifPresent(handle -> {
                    handle.descendants().forEach(ProcessHandle::destroyForcibly);
                    handle.destroyForcibly();
                });
            }
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // The assertion already recorded the command result.
        }
    }
}
