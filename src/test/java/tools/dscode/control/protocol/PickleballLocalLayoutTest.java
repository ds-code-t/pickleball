package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
                pickleball.resolve("investigations"),
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

    @Test
    void newInvestigationsWriteToTheRootAndLegacyVersionedIdsStayReadable() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        PickleballLocalLayout.writeCurrent(
                pickleball,
                PickleballLocalLayout.CurrentPointer.completeNow("2.1.14")
        );
        Path legacy = pickleball.resolve("v/2.1.11/investigations/legacy-id");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("investigation.json"), "{\"pkb_investigation_id\":\"legacy-id\"}\n");
        Files.writeString(legacy.resolve("report.html"), "<p>legacy</p>\n");

        InvestigationHandoff.EmitResult emitted = InvestigationHandoff.emit(tempDir, Map.of(
                "pkb_investigation_id", "new-id",
                "cause", "the row was empty",
                "fix", "not fixed"
        ));
        assertEquals(".pickleball/investigations/new-id/report.html", emitted.reportPath());
        assertTrue(Files.isRegularFile(pickleball.resolve("investigations/new-id/investigation.json")));
        assertFalse(Files.exists(pickleball.resolve("v/2.1.14/investigations/new-id")));

        assertEquals(pickleball.resolve("investigations/new-id"), InvestigationHandoff.directoryFor(tempDir, "new-id"));
        assertEquals(legacy, InvestigationHandoff.directoryFor(tempDir, "legacy-id"));
        assertTrue(InvestigationHandoff.readJson(tempDir, "legacy-id").orElseThrow().contains("legacy-id"));
        assertTrue(InvestigationHandoff.reportFiles(tempDir).stream()
                .anyMatch(path -> path.toString().replace('\\', '/').contains("v/2.1.11/investigations/legacy-id/report.html")));

        Path rootInvestigations = pickleball.resolve("investigations");
        Path versioned = pickleball.resolve("v/2.1.11/investigations");
        assertTrue(InvestigationHandoff.isInvestigationsPath(pickleball, rootInvestigations));
        assertTrue(InvestigationHandoff.isInvestigationsPath(pickleball, rootInvestigations.resolve("new-id/investigation.json")));
        assertTrue(InvestigationHandoff.isInvestigationsPath(pickleball, versioned));
        assertTrue(InvestigationHandoff.isInvestigationsPath(pickleball, versioned.resolve("legacy-id/report.html")));
        assertFalse(InvestigationHandoff.isInvestigationsPath(pickleball, pickleball.resolve("runs/run-1")));
    }
}
