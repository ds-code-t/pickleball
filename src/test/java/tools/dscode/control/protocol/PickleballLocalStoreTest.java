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
}
