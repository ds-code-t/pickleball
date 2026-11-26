package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLaunchTest {
    @Test
    void windowsLaunchAttachesToTheFrame() throws Exception {
        assertTrue(DesktopLaunch.supportedOs(System.getProperty("os.name")), System.getProperty("os.name"));
        try (Held held = open()) {
            assertEquals("pkb-r4-launch", held.window, held.launch.stdout());
            assertTrue(held.pid > 0, held.launch.stdout());
            assertEquals("live", held.backend, held.launch.stdout());
            assertTrue(held.live, held.launch.stdout());
        }
    }

    @Test
    void otherOperatingSystemsRefuse() {
        assertFalse(DesktopLaunch.supportedOs("Linux"));
        assertFalse(DesktopLaunch.supportedOs("Mac OS X"));
        assertTrue(DesktopLaunch.supportedOs("Windows 11"));
        assertNull(DesktopLaunch.refusalFor("Windows 11"));
        String linux = DesktopLaunch.refusalFor("Linux");
        assertTrue(linux.contains("error: desktop launch is implemented for Windows"), linux);
        assertTrue(linux.contains("fix: run this verb on Windows"), linux);
        String mac = DesktopLaunch.refusalFor("Mac OS X");
        assertTrue(mac.contains("error: desktop launch is implemented for Windows"), mac);
        assertTrue(mac.contains("fix: run this verb on Windows"), mac);
    }

    static Held open() throws Exception {
        Path dir = Files.createTempDirectory("pkb-r4-launch");
        Path cmd = writeLauncher(dir);
        CliResult result = DomEngineMain.dispatch(List.of(
                "desktop", "launch",
                "--exe=" + cmd.toAbsolutePath(),
                "--name=pkb-r4-launch"
        ));
        long pid = pidOf(result.stdout());
        if (result.exit() != 0) {
            stop(dir, pid);
            throw new AssertionError("desktop launch exit " + result.exit() + "\n" + result.stdout() + "\n" + result.stderr());
        }
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        return new Held(dir, json.path("pid").asLong(), json.path("window").asText(), json.path("backend").asText(), json.path("live").asBoolean(), result);
    }

    private static long pidOf(String stdout) {
        if (stdout == null || !stdout.contains("\"pid\"")) return 0;
        try {
            return Jsons.MAPPER.readTree(stdout).path("pid").asLong(0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void stop(Path dir, long pid) {
        try {
            Files.writeString(dir.resolve("stop"), "stop");
        } catch (Exception ignored) {
            // The process tree is destroyed next.
        }
        if (pid > 0) DesktopLaunch.destroyPid(pid);
        DesktopTree.resetForTests();
    }

    private static Path writeLauncher(Path dir) throws Exception {
        String classpath = System.getProperty("java.class.path", "").replace('\\', '/');
        Path args = dir.resolve("args.txt");
        String quoted = "\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        Files.writeString(args, String.join("\n",
                "-Djava.awt.headless=false",
                "-cp",
                quoted,
                "dev.domengine.desktop.DesktopLaunchTest",
                "frame"
        ), StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Path cmd = dir.resolve("launch.cmd");
        Files.writeString(cmd, "@echo off\r\n\"" + java + "\" @\"" + args.toAbsolutePath() + "\"\r\n", StandardCharsets.UTF_8);
        return cmd;
    }

    /** Child process entry. Opens the frame named {@code pkb-r4-launch} until {@code stop} appears. */
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !"frame".equals(args[0])) return;
        CountDownLatch shown = new CountDownLatch(1);
        JFrame[] box = new JFrame[1];
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("pkb-r4-launch");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setSize(360, 240);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
            frame.toFront();
            box[0] = frame;
            shown.countDown();
        });
        shown.await(15, TimeUnit.SECONDS);
        Path stop = Path.of("stop");
        while (!Files.exists(stop)) Thread.sleep(200);
        if (box[0] != null) SwingUtilities.invokeAndWait(() -> box[0].dispose());
    }

    static final class Held implements AutoCloseable {
        final Path dir;
        final long pid;
        final String window;
        final String backend;
        final boolean live;
        final CliResult launch;

        Held(Path dir, long pid, String window, String backend, boolean live, CliResult launch) {
            this.dir = dir;
            this.pid = pid;
            this.window = window;
            this.backend = backend;
            this.live = live;
            this.launch = launch;
        }

        @Override
        public void close() {
            stop(dir, pid);
            try {
                if (Files.isDirectory(dir)) {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // A live child may still hold the directory.
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                // Temp cleanup is best effort.
            }
        }
    }
}
