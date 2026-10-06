package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
