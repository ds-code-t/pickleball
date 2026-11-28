package tools.dscode.launcher;

import tools.dscode.control.protocol.PickleballLocalStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR: an export removed neither the old controller classpath nor the old controller log. */
class StaleControllerFilesTest {
    @TempDir
    Path temp;

    @Test
    void exportRemovesTheLeftoversOfAControllerThatIsNotRunning() throws Exception {
        Path pickleball = Files.createDirectories(temp.resolve(".pickleball"));
        Path classpath = Files.writeString(pickleball.resolve("agent-controller.classpath"), "\"old.jar\"\n");
        Path log = Files.writeString(pickleball.resolve("agent-controller.log"), "ClassNotFoundException: PkbMain\n");
        PickleballLocalStore.exportGuidance(pickleball, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        assertFalse(Files.exists(classpath), "stale classpath kept");
        assertFalse(Files.exists(log), "stale log kept");
    }

    @Test
    void exportKeepsTheFilesOfARunningController() throws Exception {
        Path pickleball = Files.createDirectories(temp.resolve(".pickleball"));
        Files.writeString(pickleball.resolve("agent-controller.json"), "{\"pid\":1,\"port\":2,\"token\":\"t\"}");
        Path classpath = Files.writeString(pickleball.resolve("agent-controller.classpath"), "\"live.jar\"\n");
        Path log = Files.writeString(pickleball.resolve("agent-controller.log"), "started\n");
        PickleballLocalStore.exportGuidance(pickleball, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        assertTrue(Files.exists(classpath));
        assertTrue(Files.exists(log));
    }
}
