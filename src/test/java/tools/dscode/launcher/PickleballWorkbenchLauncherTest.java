package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballWorkbenchLauncherTest {
    @TempDir
    Path tempDir;

    @Test
    void extractionIsContentAddressedAndCommandAlwaysStartsASeparateJarProcess() throws Exception {
        byte[] firstPayload = {1, 2, 3, 4};
        byte[] secondPayload = {1, 2, 3, 5};

        Path first = PickleballWorkbenchLauncher.extractPayload(tempDir, firstPayload);
        Path repeated = PickleballWorkbenchLauncher.extractPayload(tempDir, firstPayload);
        Path second = PickleballWorkbenchLauncher.extractPayload(tempDir, secondPayload);

        assertEquals(first, repeated);
        assertNotEquals(first, second);
        assertArrayEquals(firstPayload, Files.readAllBytes(first));
        assertArrayEquals(secondPayload, Files.readAllBytes(second));

        Files.write(first, new byte[]{9});
        Path repaired = PickleballWorkbenchLauncher.extractPayload(tempDir, firstPayload);
        assertEquals(first, repaired);
        assertArrayEquals(firstPayload, Files.readAllBytes(repaired));

        List<String> command = PickleballWorkbenchLauncher.command(
                first,
                new String[]{"ui", tempDir.toString()}
        );
        assertTrue(command.get(0).endsWith(javaExecutableName()));
        assertEquals("-jar", command.get(1));
        assertEquals(first.toAbsolutePath().normalize().toString(), command.get(2));
        assertEquals(List.of("ui", tempDir.toString()), command.subList(3, command.size()));

        String[] defaults = PickleballWorkbenchLauncher.normalizedArguments(new String[0]);
        String[] implicitProject = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"mcp"}
        );
        String[] isolate = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"isolate", "--tags=@failing"}
        );
        String[] discoverHint = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"hint"}
        );
        assertEquals("ui", defaults[0]);
        assertEquals(Path.of("").toAbsolutePath().normalize().toString(), defaults[1]);
        assertEquals("mcp", implicitProject[0]);
        assertEquals(Path.of("").toAbsolutePath().normalize().toString(), implicitProject[1]);
        assertEquals("isolate", isolate[0]);
        assertEquals(Path.of("").toAbsolutePath().normalize().toString(), isolate[1]);
        assertEquals("--tags", isolate[2]);
        assertEquals("@failing", isolate[3]);
        assertEquals("hint", discoverHint[0]);

        String[] exportGuidance = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"export-guidance", ".pickleball"}
        );
        assertEquals("export-guidance", exportGuidance[0]);
        assertEquals(".pickleball", exportGuidance[1]);

        String[] isolateName = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"isolate", "--name=The", "failing", "scenario"}
        );
        assertEquals("isolate", isolateName[0]);
        assertEquals(Path.of("").toAbsolutePath().normalize().toString(), isolateName[1]);
        assertEquals("--name", isolateName[2]);
        assertEquals("The failing scenario", isolateName[3]);

        assertTrue(WorkbenchCommandLine.isSessionClientCommand("isolate"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("session-start"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("execute-step"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("status"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("events"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("stop"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("kill"));
        assertFalse(WorkbenchCommandLine.isSessionClientCommand("session"));
        assertFalse(WorkbenchCommandLine.isSessionClientCommand("hint"));
        assertFalse(WorkbenchCommandLine.isAgentCoreCommand("isolate"));

        WorkbenchCommandLine.Parsed confirm = WorkbenchCommandLine.parse(
                new String[]{"confirm", "--name=Agent", "pointer", "eval", "failing", "fruit", "mismatch"}
        );
        assertEquals("confirm", confirm.command());
        assertEquals("Agent pointer eval failing fruit mismatch", confirm.name());
        assertEquals(Path.of("").toAbsolutePath().normalize(), confirm.project());

        byte[] streamed = {9, 8, 7, 6};
        Path fromStream = PickleballWorkbenchLauncher.extractPayload(
                tempDir,
                new ByteArrayInputStream(streamed)
        );
        assertArrayEquals(streamed, Files.readAllBytes(fromStream));
        assertTrue(PickleballWorkbenchLauncher.MAX_PAYLOAD_BYTES >= 164L * 1024 * 1024);
    }

    private static String javaExecutableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
    }
}
