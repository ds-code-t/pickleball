package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballLocalStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void versionedExportWritesCurrentJsonOpenScriptsAndRootAliases() throws Exception {
        Path project = Files.createTempDirectory(tempDir, "consumer");
        Path pickleball = project.resolve(".pickleball");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = PickleballLocalStore.exportGuidance(
                pickleball,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                System.err
        );
        assertEquals(0, status);
        String version = PickleballVersion.running(PickleballLocalStore.class);
        assertTrue(PickleballLocalLayout.isSafeVersion(version));
        Path versionRoot = pickleball.resolve("v").resolve(version);
        assertTrue(Files.isRegularFile(versionRoot.resolve("AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(versionRoot.resolve("GUIDANCE-MANIFEST.json")));
        assertTrue(Files.isRegularFile(versionRoot.resolve("docs/README.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("GUIDANCE-MANIFEST.json")));
        assertTrue(Files.isRegularFile(pickleball.resolve("current.json")));
        assertTrue(Files.isRegularFile(pickleball.resolve("open/pickleball-workbench.sh")));
        assertTrue(Files.isRegularFile(pickleball.resolve("open/pickleball-workbench.cmd")));
        assertTrue(Files.isRegularFile(pickleball.resolve("open/pickleball-workbench.ps1")));
        var current = PickleballLocalLayout.readCurrent(pickleball).orElseThrow();
        assertEquals(version, current.pickleballVersion());
        assertTrue(current.usable());
        String script = Files.readString(pickleball.resolve("open/pickleball-workbench.sh"));
        assertTrue(script.contains("PKB_OPEN_BOOTSTRAP=1"));
        assertTrue(script.contains("java"));
        assertFalse(script.contains(project.toString()));
        assertFalse(script.contains("/home/"));
        assertTrue(Files.readString(pickleball.resolve("AGENT-GUIDE.md")).contains("v/" + version + "/"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("NEXT: follow AGENT-GUIDE"));
        assertTrue(Files.isRegularFile(pickleball.resolve("v").resolve(version).resolve(".last-used")));
        assertTrue(Files.isRegularFile(versionRoot.resolve("open/pickleball-workbench.sh")));
        assertTrue(PickleballLocalStore.alreadyCurrent(pickleball, version));
    }

    @Test
    void flatExportKeepsFilesAtTheRequestedDirectory() throws Exception {
        Path dest = tempDir.resolve("guidance");
        assertEquals(0, PickleballLocalStore.exportGuidance(dest, System.out, System.err));
        assertTrue(Files.isRegularFile(dest.resolve("AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(dest.resolve("GUIDANCE-MANIFEST.json")));
        assertFalse(Files.isDirectory(dest.resolve("v")));
        assertFalse(Files.isRegularFile(dest.resolve("current.json")));
    }

    @Test
    void openScriptsDoNotBakeVersionOrM2Path() {
        String sh = PickleballLocalStore.unixOpenScript();
        String cmd = PickleballLocalStore.cmdOpenScript();
        String ps = PickleballLocalStore.powershellOpenScript();
        for (String script : List.of(sh, cmd, ps)) {
            assertTrue(script.contains("current.json"));
            assertTrue(script.contains("pickleball-") && script.contains(".jar"));
            assertTrue(script.contains(".gradle"));
            assertFalse(script.contains("/workspace/"));
            assertFalse(script.contains("2.1.11"));
        }
        assertTrue(cmd.contains("build.gradle.kts"));
        assertTrue(cmd.contains("SNAPSHOT"));
        assertTrue(cmd.contains("PROJECT:~-1"));
        assertTrue(ps.contains("SNAPSHOT"));
        assertTrue(sh.contains("classpath.cache"));
        assertTrue(sh.contains("-cp"));
        assertTrue(sh.contains("tools.dscode.launcher.PickleballWorkbenchLauncher"));
        assertTrue(sh.contains("build-classpath"));
        assertFalse(sh.contains("exec-maven-plugin"), sh);
        assertTrue(cmd.contains("classpath.cache"));
        assertTrue(cmd.contains("build-classpath"));
        assertFalse(cmd.contains("exec-maven-plugin"), cmd);
        assertTrue(ps.contains("classpath.cache"));
        assertTrue(ps.contains("build-classpath"));
        assertFalse(ps.contains("exec-maven-plugin"), ps);
    }

    @Test
    void openScriptOneShotDoesNotStartMavenWhenTheCacheIsFresh() throws Exception {
        Path project = tempDir.resolve("script-consumer");
        Files.createDirectories(project.resolve(".pickleball/open"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.write(project.resolve("pickleball-test.jar"), new byte[]{1, 2, 3});
        Path cache = project.resolve(".pickleball/workbench/classpath.cache");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "\"/tmp/pickleball-oneshot.jar\"\n");
        Files.setLastModifiedTime(project.resolve("pom.xml"), FileTime.from(Instant.now().minusSeconds(60)));
        Path javaHome = tempDir.resolve("java-home");
        Path bin = javaHome.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("java"), """
                #!/bin/sh
                printf '%s\\n' "$@" > "$(dirname "$0")/recorded"
                exit 0
                """);
        bin.resolve("java").toFile().setExecutable(true);
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                echo maven >> "$PWD/maven-ran"
                exit 9
                """);
        mvnw.toFile().setExecutable(true);
        Path script = project.resolve(".pickleball/open/pickleball-workbench.sh");
        Files.writeString(script, PickleballLocalStore.unixOpenScript());
        script.toFile().setExecutable(true);

        ProcessBuilder builder = new ProcessBuilder(script.toString(), "hint")
                .directory(project.toFile())
                .redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", javaHome.toString());
        builder.environment().put("PATH", bin + ":" + builder.environment().getOrDefault("PATH", ""));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, output);
        String recorded = Files.readString(bin.resolve("recorded"));
        assertTrue(recorded.contains("-cp"), recorded);
        assertTrue(recorded.contains("classpath.cache"), recorded);
        assertTrue(recorded.contains("tools.dscode.launcher.PickleballWorkbenchLauncher"), recorded);
        assertTrue(recorded.contains("hint"), recorded);
        assertFalse(recorded.contains("mvnw"), recorded);
        assertFalse(Files.isRegularFile(project.resolve("maven-ran")), output);

        ProcessBuilder discover = new ProcessBuilder(script.toString(), "discover", "--tags=@smoke")
                .directory(project.toFile())
                .redirectErrorStream(true);
        discover.environment().put("JAVA_HOME", javaHome.toString());
        discover.environment().put("PATH", bin + ":" + discover.environment().getOrDefault("PATH", ""));
        Process discoverProcess = discover.start();
        String discoverOut = new String(discoverProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, discoverProcess.waitFor(), discoverOut);
        String discovered = Files.readString(bin.resolve("recorded"));
        assertTrue(discovered.contains("discover"), discovered);
        assertTrue(discovered.contains("tools.dscode.launcher.PickleballWorkbenchLauncher"), discovered);
        assertFalse(discovered.contains(" ui "), discovered);

        ProcessBuilder unknown = new ProcessBuilder(script.toString(), "not-a-verb")
                .directory(project.toFile())
                .redirectErrorStream(true);
        unknown.environment().put("JAVA_HOME", javaHome.toString());
        unknown.environment().put("PATH", bin + ":" + unknown.environment().getOrDefault("PATH", ""));
        Process unknownProcess = unknown.start();
        String unknownOut = new String(unknownProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, unknownProcess.waitFor(), unknownOut);
        String unknownRecorded = Files.readString(bin.resolve("recorded"));
        assertTrue(unknownRecorded.contains("not-a-verb"), unknownRecorded);
        assertTrue(unknownRecorded.contains("tools.dscode.launcher.PickleballWorkbenchLauncher"), unknownRecorded);
        assertFalse(unknownRecorded.lines().anyMatch("ui"::equals), unknownRecorded);

        ProcessBuilder gui = new ProcessBuilder(script.toString())
                .directory(project.toFile())
                .redirectErrorStream(true);
        gui.environment().put("JAVA_HOME", javaHome.toString());
        gui.environment().put("PATH", bin + ":" + gui.environment().getOrDefault("PATH", ""));
        Process guiProcess = gui.start();
        String guiOut = new String(guiProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, guiProcess.waitFor(), guiOut);
        String guiRecorded = Files.readString(bin.resolve("recorded"));
        assertTrue(guiRecorded.lines().anyMatch("ui"::equals), guiRecorded);
        assertFalse(guiRecorded.contains("PickleballWorkbenchLauncher"), guiRecorded);
    }

    @Test
    void staleCacheOneShotExitsWithJavaNotMaven() throws Exception {
        Path project = tempDir.resolve("stale-exit");
        Files.createDirectories(project.resolve(".pickleball/open"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.write(project.resolve("pickleball-test.jar"), new byte[]{1, 2, 3});
        Path javaHome = tempDir.resolve("java-home-exit");
        Path bin = javaHome.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("java"), """
                #!/bin/sh
                printf '%s\\n' "$@" > "$(dirname "$0")/recorded"
                exit 2
                """);
        bin.resolve("java").toFile().setExecutable(true);
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                echo "$@" >> "$PWD/mvn-args"
                out=""
                for arg in "$@"; do
                  case "$arg" in
                    -Dmdep.outputFile=*) out="${arg#-Dmdep.outputFile=}" ;;
                  esac
                done
                if [ -n "$out" ]; then
                  printf '%s\\n' "/tmp/pickleball-oneshot.jar" > "$out"
                  exit 0
                fi
                exit 1
                """);
        mvnw.toFile().setExecutable(true);
        Path script = project.resolve(".pickleball/open/pickleball-workbench.sh");
        Files.writeString(script, PickleballLocalStore.unixOpenScript());
        script.toFile().setExecutable(true);
        ProcessBuilder builder = new ProcessBuilder(script.toString(), "not-a-verb")
                .directory(project.toFile())
                .redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", javaHome.toString());
        builder.environment().put("PATH", bin + ":" + builder.environment().getOrDefault("PATH", ""));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, process.waitFor(), output);
        String recorded = Files.readString(bin.resolve("recorded"));
        assertTrue(recorded.contains("-cp"), recorded);
        assertTrue(recorded.contains("tools.dscode.launcher.PickleballWorkbenchLauncher"), recorded);
        assertTrue(recorded.contains("not-a-verb"), recorded);
        String maven = Files.readString(project.resolve("mvn-args"));
        assertTrue(maven.contains("build-classpath"), maven);
        assertFalse(maven.contains("exec-maven-plugin"), maven);
    }

    @Test
    void exportGuidanceDoesNotDeleteEitherInvestigationsTree() throws Exception {
        Path project = Files.createTempDirectory(tempDir, "consumer");
        Path pickleball = project.resolve(".pickleball");
        String version = PickleballVersion.running(PickleballLocalStore.class);
        Path versionRoot = pickleball.resolve("v").resolve(version);
        Path rootInvestigation = pickleball.resolve("investigations/keep-root");
        Path versioned = versionRoot.resolve("investigations/keep-version");
        Path other = pickleball.resolve("v/9.9.9/investigations/keep-other");
        Files.createDirectories(rootInvestigation);
        Files.createDirectories(versioned);
        Files.createDirectories(other);
        Files.writeString(rootInvestigation.resolve("investigation.json"), "{\"id\":\"root\"}\n");
        Files.writeString(rootInvestigation.resolve("report.html"), "<p>root</p>\n");
        Files.writeString(versioned.resolve("investigation.json"), "{\"id\":\"version\"}\n");
        Files.writeString(versioned.resolve("report.html"), "<p>version</p>\n");
        Files.writeString(other.resolve("investigation.json"), "{\"id\":\"other\"}\n");
        Files.writeString(other.getParent().getParent().resolve("AGENT-GUIDE.md"), "keep-other-version\n");
        Files.writeString(versionRoot.resolve("GUIDANCE-MANIFEST.json"), """
                {
                  "files": [
                    "investigations/keep-version/investigation.json",
                    "investigations/keep-version/report.html",
                    "AGENT-GUIDE.md"
                  ]
                }
                """);

        assertEquals(0, PickleballLocalStore.exportGuidance(pickleball, System.out, System.err));
        assertTrue(Files.isRegularFile(rootInvestigation.resolve("investigation.json")));
        assertTrue(Files.isRegularFile(rootInvestigation.resolve("report.html")));
        assertTrue(Files.isRegularFile(versioned.resolve("investigation.json")));
        assertTrue(Files.isRegularFile(versioned.resolve("report.html")));
        assertTrue(Files.isRegularFile(other.resolve("investigation.json")));
        assertEquals("keep-other-version\n", Files.readString(pickleball.resolve("v/9.9.9/AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(versionRoot.resolve(".last-used")));
    }

    @Test
    void useVersionPointsAtAnExistingExportAndLeavesTheOtherTree() throws Exception {
        Path project = Files.createTempDirectory(tempDir, "consumer");
        Path pickleball = project.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "guide-two");
        writeComplete(pickleball, "2.1.0", "guide-two-one");
        PickleballLocalLayout.writeCurrent(
                pickleball,
                PickleballLocalLayout.CurrentPointer.completeNow("2.0.0")
        );
        String siblingBefore = Files.readString(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md"));

        assertEquals(0, PickleballLocalStore.useVersion(project, "2.1.0", System.out, System.err));
        assertEquals("2.1.0", PickleballLocalLayout.readCurrent(pickleball).orElseThrow().pickleballVersion());
        assertEquals("guide-two-one", Files.readString(pickleball.resolve("AGENT-GUIDE.md")).trim());
        assertEquals(siblingBefore, Files.readString(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.1.0/.last-used")));
        assertTrue(Files.readString(pickleball.resolve("open/pickleball-workbench.sh")).contains("open-2.1.0"));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.0.0/workbench/controller/keep.jar")));
    }

    @Test
    void useVersionDoesNotMoveThePointerWhenTheExportIsMissing() throws Exception {
        Path project = Files.createTempDirectory(tempDir, "consumer");
        Path pickleball = project.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "guide-two");
        PickleballLocalLayout.writeCurrent(
                pickleball,
                PickleballLocalLayout.CurrentPointer.completeNow("2.0.0")
        );
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = PickleballLocalStore.useVersion(
                project,
                "9.9.9",
                System.out,
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(1, status);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("export-guidance from Pickleball 9.9.9 is required"));
        assertEquals("2.0.0", PickleballLocalLayout.readCurrent(pickleball).orElseThrow().pickleballVersion());
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md")));
    }

    private static void writeComplete(Path pickleball, String version, String marker) throws Exception {
        Path root = pickleball.resolve("v").resolve(version);
        Files.createDirectories(root.resolve("open"));
        Files.createDirectories(root.resolve("workbench/controller"));
        Files.writeString(root.resolve("AGENT-GUIDE.md"), marker + "\n");
        Files.writeString(root.resolve("GUIDANCE-MANIFEST.json"), """
                {
                  "pickleballVersion": "%s",
                  "files": ["AGENT-GUIDE.md"]
                }
                """.formatted(version));
        Files.writeString(root.resolve("open/pickleball-workbench.sh"), "#!/bin/sh\n# open-" + version + "\n");
        Files.writeString(root.resolve("workbench/controller/keep.jar"), "jar-" + version);
    }
}
