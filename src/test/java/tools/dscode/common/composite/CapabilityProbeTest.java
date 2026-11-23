package tools.dscode.common.composite;

import tools.dscode.testsupport.StdioFake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed capabilities read is not cached. The next call probes again. */
class CapabilityProbeTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void secondReadSeesTheRealActionList() throws Exception {
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log, "2.4.0", "", "once");
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
        assertFalse(DomCompositeAdapter.get().actionListed("hover"));
        assertTrue(DomCompositeAdapter.get().actionListed("hover"));
    }

    @Test
    void javaEngineFailureUsesTheJavaDesktopVerbs() throws Exception {
        Path log = temp.resolve("java.log");
        Path binary = stub(temp.resolve("java-composite"), log, "3.0.0", "domengine", "always");
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        ElementOperations.JsonDesktop first = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        ElementOperations.JsonDesktop second = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        ElementOperations.JsonDesktop third = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(1, third.size());
        int probes = 0;
        for (String line : Files.readString(log).split("\n")) {
            if (line.equals("capabilities") || line.endsWith(" capabilities") || line.contains(" capabilities")) probes++;
        }
        assertEquals(2, probes, Files.readString(log));
    }

    @Test
    void rustFailureKeepsTheTwoFourDesktopSet() throws Exception {
        Path log = temp.resolve("rust.log");
        Path binary = stub(temp.resolve("rust-composite"), log, "2.4.0", "", "always");
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        ElementOperations.JsonDesktop hit = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        assertEquals(1, hit.size());
        String error = CompositeLog.lastErrorForTests();
        assertTrue(error == null || !error.contains("composite-native"), String.valueOf(error));
        assertTrue(Files.readString(log).contains("desktop"), Files.readString(log));
    }

    private static Path stub(Path path, Path log, String version, String name, String fail) throws Exception {
        return StdioFake.install(path, "capability", log.toString(), version, name, fail);
    }
}
