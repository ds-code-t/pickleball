package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballArtifactLocator;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
    void rewrittenSystemExitStillHaltsWithTheUsageCode() {
        int[] halted = {-1};
        int[] calls = {0};
        PickleballWorkbenchLauncher.keepExitCode(
                2,
                code -> {
                    throw new RuntimeException("System::exit was called with return code " + code);
                },
                code -> {
                    calls[0]++;
                    halted[0] = code;
                }
        );
        assertEquals(2, halted[0]);
        assertEquals(1, calls[0]);
    }

    @Test
    void ignoredSystemExitStillHaltsWithTheUsageCode() {
        int[] halted = {-1};
        int[] calls = {0};
        PickleballWorkbenchLauncher.keepExitCode(
                2,
                code -> {
                },
                code -> {
                    calls[0]++;
                    halted[0] = code;
                }
        );
        assertEquals(2, halted[0]);
        assertEquals(1, calls[0]);
    }

    @Test
    void windowsWrapperReturnsTheJvmStatusInsteadOfMvnCmdCollapse() throws Exception {
        String script = Files.readString(Path.of("maven-consumer-project", "mvnw.cmd"));
        assertTrue(script.contains("org.codehaus.plexus.classworlds.launcher.Launcher"), script);
        assertTrue(script.contains("set \"PKB_EXIT=%ERRORLEVEL%\""), script);
        assertTrue(script.contains("exit /b %PKB_EXIT%"), script);
        assertTrue(script.contains("set \"PKB_RC=%ERRORLEVEL%\""), script);
        assertTrue(script.contains("@exit %PKB_RC%"), script);
        assertFalse(script.contains("@exit /b %ERRORLEVEL%"), script);
        String homeLine = "Write-Output \"MAVEN_HOME=$MAVEN_HOME\"";
        int homes = 0;
        for (int from = 0; (from = script.indexOf(homeLine, from)) >= 0; from += homeLine.length()) {
            homes++;
        }
        assertEquals(2, homes, script);
        assertFalse(script.contains("(\"%__MVNW_CMD__%\" %*)"), script);
        assertFalse(script.contains("set ERROR_CODE=1"), script);
        assertFalse(script.contains("if ERRORLEVEL 1 goto error"), script);
    }

    @Test
    void extractUsesVersionedWorkbenchWhenCurrentJsonIsComplete() throws Exception {
        PickleballLocalLayout.writeCurrent(
                tempDir.resolve(".pickleball"),
                PickleballLocalLayout.CurrentPointer.completeNow("2.1.11")
        );
        Path extracted = PickleballWorkbenchLauncher.extractPayload(tempDir, new byte[]{1, 2, 3});
        Path expected = tempDir.resolve(".pickleball/v/2.1.11/workbench/controller");
        assertTrue(extracted.startsWith(expected));
        assertTrue(extracted.getFileName().toString().equals("pickleball-workbench.jar"));
    }

    @Test
    void bootstrapReexecHopsToPinnedJarAndSkipsWhenAlreadyOnIt() throws Exception {
        Path m2 = tempDir.resolve("m2");
        Path gradle = tempDir.resolve("gradle");
        Path jar2111 = PickleballArtifactLocator.mavenJar(m2, "2.1.11");
        Files.createDirectories(jar2111.getParent());
        Files.writeString(jar2111, "2111");
        Path jar2112 = PickleballArtifactLocator.mavenJar(m2, "2.1.12");
        Files.createDirectories(jar2112.getParent());
        Files.writeString(jar2112, "2112");
        PickleballLocalLayout.writeCurrent(
                tempDir.resolve(".pickleball"),
                PickleballLocalLayout.CurrentPointer.completeNow("2.1.11")
        );
        PickleballArtifactLocator.Repositories repos =
                new PickleballArtifactLocator.Repositories(m2, gradle);

        assertEquals(
                jar2111,
                PickleballWorkbenchLauncher.reexecJar(tempDir, "2.1.12", true, false, repos).orElseThrow()
        );
        assertTrue(PickleballWorkbenchLauncher.reexecJar(tempDir, "2.1.11", true, false, repos).isEmpty());
        assertTrue(PickleballWorkbenchLauncher.reexecJar(tempDir, "2.1.12", false, false, repos).isEmpty());
        assertTrue(PickleballWorkbenchLauncher.reexecJar(tempDir, "2.1.12", true, true, repos).isEmpty());
    }

    @Test
    void bootstrapWithoutCurrentHopsToLatestRelease() throws Exception {
        Path m2 = tempDir.resolve("m2");
        Path jar = PickleballArtifactLocator.mavenJar(m2, "2.1.11");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "latest");
        PickleballArtifactLocator.Repositories repos =
                new PickleballArtifactLocator.Repositories(m2, tempDir.resolve("gradle"));
        assertEquals(
                jar,
                PickleballWorkbenchLauncher.reexecJar(tempDir, "2.1.10", true, false, repos).orElseThrow()
        );
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

        String[] isolateExample = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"isolate", "--example=1", "2", "5", "3.4", "7-11"}
        );
        assertEquals("--example", isolateExample[2]);
        assertEquals("1 2 5 3.4 7-11", isolateExample[3]);

        assertTrue(WorkbenchCommandLine.isSessionClientCommand("isolate"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("session-start"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("execute-step"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("status"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("events"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("stop"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("kill"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("open-scenario"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("example"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("play"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("from-here"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("pause"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("diagnostic-run"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("save"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("session-sync"));
        assertTrue(WorkbenchCommandLine.isSessionClientCommand("worker-start"));
        assertFalse(WorkbenchCommandLine.isSessionClientCommand("session"));
        assertFalse(WorkbenchCommandLine.isForwardedCommand("play"));
        assertFalse(WorkbenchCommandLine.isSessionClientCommand("hint"));
        assertFalse(WorkbenchCommandLine.isAgentCoreCommand("isolate"));

        WorkbenchCommandLine.Parsed confirm = WorkbenchCommandLine.parse(
                new String[]{"confirm", "--name=Agent", "pointer", "eval", "failing", "fruit", "mismatch"}
        );
        assertEquals("confirm", confirm.command());
        assertEquals("Agent pointer eval failing fruit mismatch", confirm.name());
        assertEquals(Path.of("").toAbsolutePath().normalize(), confirm.project());
    }

    @Test
    void helpExitsZeroWithoutJacksonAndUsageErrorsExit2() throws Exception {
        String classpath = classpathWithoutJackson();
        Run help = launch(classpath, "help");
        assertEquals(0, help.exit, help.text());
        assertTrue(help.out.contains("Pickleball Workbench"), help.text());
        assertTrue(help.out.contains("thin controller jar"), help.text());
        assertFalse(help.text().contains("NoClassDefFoundError"), help.text());
        assertFalse(help.text().contains("ObjectMapper"), help.text());

        Run flag = launch(classpath, "help", "--json");
        assertEquals(0, flag.exit, flag.text());
        assertTrue(flag.out.contains("Usage: --json"), flag.text());
        assertFalse(flag.text().contains("NoClassDefFoundError"), flag.text());

        Run unknownTopic = launch(classpath, "help", "not-a-command");
        assertEquals(2, unknownTopic.exit, unknownTopic.text());
        assertTrue(unknownTopic.err.contains("Unknown Workbench command"), unknownTopic.text());

        Run unknown = launch(classpath, "not-a-verb");
        assertEquals(2, unknown.exit, unknown.text());
        assertTrue(unknown.text().contains("error:"), unknown.text());
        assertTrue(unknown.text().contains("fix:"), unknown.text());

        Run tags = launch(classpath, "plan", "--tags=@a", "and", "not", "@b");
        assertEquals(2, tags.exit, tags.text());
        assertTrue(tags.text().contains("Tag quoting error"), tags.text());
        assertFalse(tags.text().contains("NoClassDefFoundError"), tags.text());
    }

    @Test
    void linuxWrapperReturnsTheJvmStatusInsteadOfCollapsingToOne() throws Exception {
        String script = Files.readString(Path.of("maven-consumer-project", "mvnw"));
        assertTrue(script.contains("org.codehaus.plexus.classworlds.launcher.Launcher"), script);
        assertTrue(script.contains("Do not collapse a non-zero status to 1"), script);
        assertFalse(script.contains("exec \"$MAVEN_HOME/bin/$MVN_CMD\""), script);
    }

    private static String classpathWithoutJackson() {
        String separator = System.getProperty("path.separator");
        List<String> kept = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator))) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.contains("jackson")) continue;
            kept.add(entry);
        }
        return String.join(separator, kept);
    }

    private static Run launch(String classpath, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", javaExecutableName()).toString());
        command.add("-cp");
        command.add(classpath);
        command.add("tools.dscode.launcher.PickleballWorkbenchLauncher");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = pump(process.getInputStream(), out);
        Thread stderr = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        stdout.join(2_000);
        stderr.join(2_000);
        return new Run(
                finished ? process.exitValue() : -1,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private static Thread pump(java.io.InputStream input, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(target);
            } catch (Exception ignored) {
                // The process ending closes the pipe.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record Run(int exit, String out, String err) {
        String text() {
            return out + "\n" + err;
        }
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
