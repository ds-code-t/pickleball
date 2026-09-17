package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballLocalLayoutTest {
    @TempDir
    Path tempDir;

    @Test
    void currentPointerRoundTripAndRejectsUnsafeVersions() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        Files.createDirectories(pickleball);
        assertTrue(PickleballLocalLayout.parseCurrent("").isEmpty());
        assertTrue(PickleballLocalLayout.parseCurrent("{\"pickleballVersion\":\"../x\"}").isEmpty());
        PickleballLocalLayout.CurrentPointer pointer =
                new PickleballLocalLayout.CurrentPointer("2.1.11", true, "2026-09-12T00:00:00Z");
        PickleballLocalLayout.writeCurrent(pickleball, pointer);
        Optional<PickleballLocalLayout.CurrentPointer> read = PickleballLocalLayout.readCurrent(pickleball);
        assertTrue(read.isPresent());
        assertEquals("2.1.11", read.get().pickleballVersion());
        assertTrue(read.get().usable());
        assertFalse(PickleballLocalLayout.isSafeVersion("2.1.11/evil"));
        assertFalse(PickleballLocalLayout.isSafeVersion(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> PickleballLocalLayout.requireSafeVersion("a:b"));
    }

    @Test
    void workbenchAndInvestigationsStayLegacyWithoutCurrentJson() {
        Path workbench = PickleballLocalLayout.workbenchStateRoot(tempDir);
        assertEquals(tempDir.resolve(".pickleball").resolve("workbench"), workbench);
        assertEquals(
                tempDir.resolve(".pickleball").resolve("investigations"),
                PickleballLocalLayout.investigationsDirectory(tempDir)
        );
        assertEquals(
                tempDir.resolve(".pickleball/workbench/last-discover.json"),
                PickleballLocalLayout.lastDiscoverSnapshot(tempDir)
        );
    }

    @Test
    void completeCurrentJsonSelectsVersionedWorkbenchEvenWhenLegacyExists() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        Files.createDirectories(pickleball.resolve("workbench"));
        PickleballLocalLayout.writeCurrent(
                pickleball,
                PickleballLocalLayout.CurrentPointer.completeNow("2.1.11")
        );
        assertEquals(
                pickleball.resolve("v/2.1.11/workbench"),
                PickleballLocalLayout.workbenchStateRoot(tempDir)
        );
        assertEquals(
                pickleball.resolve("v/2.1.11/investigations"),
                PickleballLocalLayout.investigationsDirectory(tempDir)
        );
        assertEquals(
                pickleball.resolve("v/2.1.11/workbench"),
                PickleballLocalLayout.workbenchStateRoot(tempDir, "2.1.11")
        );
    }

    @Test
    void findProjectRootWalksUpFromNestedOpenScripts() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path open = tempDir.resolve(".pickleball/open");
        Files.createDirectories(open);
        assertEquals(tempDir.toAbsolutePath().normalize(), PickleballLocalLayout.findProjectRoot(open));
        assertTrue(PickleballLocalLayout.looksLikeProject(tempDir));
        assertTrue(PickleballLocalLayout.isPickleballDirectory(tempDir.resolve(".pickleball")));
    }
}
