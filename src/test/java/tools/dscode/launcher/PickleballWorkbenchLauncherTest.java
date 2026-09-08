package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballWorkbenchLauncherTest {
    @TempDir
    Path tempDir;

    @Test
    void extractionIsContentAddressedAndRepairsCorruptedCache() throws Exception {
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

        byte[] streamed = {9, 8, 7, 6};
        Path fromStream = PickleballWorkbenchLauncher.extractPayload(
                tempDir,
                new ByteArrayInputStream(streamed)
        );
        assertArrayEquals(streamed, Files.readAllBytes(fromStream));
        assertEquals(32L * 1024 * 1024, PickleballWorkbenchLauncher.MAX_PAYLOAD_BYTES);
    }

    @Test
    void commandUsesClasspathThinJarCachedLibsAndMainClass() throws Exception {
        byte[] payload = thinJar("2.1.11", "tools.dscode.test:fixture:1.0.0");
        Path extracted = PickleballWorkbenchLauncher.extractPayload(tempDir, payload);
        Path libDir = tempDir.resolve(".pickleball").resolve("workbench").resolve("lib").resolve("2.1.11");
        Files.createDirectories(libDir);
        Path fixture = libDir.resolve("fixture-1.0.0.jar");
        Files.write(fixture, new byte[]{7, 7, 7});

        List<String> command = PickleballWorkbenchLauncher.command(
                extracted,
                new String[]{"ui", tempDir.toString()}
        );
        assertTrue(command.get(0).endsWith(javaExecutableName()));
        assertEquals("-cp", command.get(1));
        String classpath = command.get(2);
        assertTrue(classpath.contains(extracted.toAbsolutePath().normalize().toString()));
        assertTrue(classpath.contains(fixture.toAbsolutePath().normalize().toString()));
        assertFalse(classpath.contains("-jar"));
        assertEquals("tools.dscode.workbench.WorkbenchApplication", command.get(3));
        assertEquals(List.of("ui", tempDir.toString()), command.subList(4, command.size()));
    }

    @Test
    void commandSubstitutesJavaFxClassifierAndFailsWithGavLocations() throws Exception {
        WorkbenchRuntimeLibs.Manifest parsed = WorkbenchRuntimeLibs.parse("""
                # format=1
                # workbench-version=2.1.11
                org.openjfx:javafx-base:21.0.6:CLASSIFIER
                com.fasterxml.jackson.core:jackson-databind:2.20.0
                """.stripIndent());
        assertEquals("2.1.11", parsed.version());
        WorkbenchRuntimeLibs.Manifest launch = WorkbenchRuntimeLibs.withLaunchClassifier(
                parsed,
                WorkbenchRuntimeLibs.platformKey()
        );
        assertEquals(
                "org.openjfx:javafx-base:21.0.6:" + WorkbenchRuntimeLibs.platformKey(),
                launch.libs().get(0).coordinates()
        );

        byte[] payload = thinJar("2.1.11", "tools.dscode.test:missing-lib:0.0.1");
        Path extracted = PickleballWorkbenchLauncher.extractPayload(tempDir, payload);
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PickleballWorkbenchLauncher.command(extracted, new String[]{"ui", tempDir.toString()})
        );
        assertTrue(failure.getMessage().contains("tools.dscode.test:missing-lib:0.0.1"));
        assertTrue(failure.getMessage().contains("Maven local"));
        assertTrue(failure.getMessage().contains("Gradle cache"));
        assertTrue(failure.getMessage().contains("repo1.maven.org")
                || failure.getMessage().contains("maven2"));
    }

    @Test
    void argumentNormalizationAndSessionCommandsAreUnchanged() {
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
    }

    private static byte[] thinJar(String version, String... rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry("META-INF/pickleball/workbench-runtime-libs.txt"));
            StringBuilder body = new StringBuilder();
            body.append("# format=1\n");
            body.append("# workbench-version=").append(version).append('\n');
            for (String row : rows) {
                body.append(row).append('\n');
            }
            jar.write(body.toString().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String javaExecutableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
    }
}
