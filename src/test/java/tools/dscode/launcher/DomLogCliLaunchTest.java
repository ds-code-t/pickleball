package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomLogCliLaunchTest {
    @TempDir
    Path project;

    @Test
    void launcherListsHistoryWithoutRewritingIt() throws Exception {
        Path history = project.resolve(".pickleball").resolve("history.log");
        Files.createDirectories(history.getParent());
        Files.writeString(history, "keep-history\n");
        Path engine = project.resolve("composite-logs").resolve("composite-day.log");
        Files.createDirectories(engine.getParent());
        Files.writeString(engine, "engine-line\n");
        long size = Files.size(history);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchLogIfAny(
                new String[] {"log", "list", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("history.log"), out);
        assertTrue(out.contains("composite-day.log"), out);
        assertEquals(size, Files.size(history));
        assertEquals("keep-history\n", Files.readString(history));

        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        assertEquals(0, PickleballWorkbenchLauncher.dispatchLogIfAny(
                new String[] {"log", "help"},
                new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        ));
        assertTrue(helpOut.toString(StandardCharsets.UTF_8).contains("history.log"));
        assertEquals(-1, PickleballWorkbenchLauncher.dispatchLogIfAny(
                new String[] {"help"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        ));
    }
}
