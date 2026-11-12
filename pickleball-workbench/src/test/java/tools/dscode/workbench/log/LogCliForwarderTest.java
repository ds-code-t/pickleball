package tools.dscode.workbench.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsToTheLogCli() {
        Path jar = Path.of("pickleball.jar");
        List<String> command = LogCliForwarder.command(jar, project, List.of("tail", "--file=history.log", "--lines=20"));
        assertEquals("log", command.get(3));
        assertEquals("tail", command.get(4));
        assertEquals("--file=history.log", command.get(5));
        assertEquals("--lines=20", command.get(6));
        assertTrue(command.get(7).startsWith("--project="), command.toString());
        assertTrue(command.get(2).endsWith("pickleball.jar"), command.toString());
        assertEquals("-jar", command.get(1));
    }

    @Test
    void missingJarDoesNotReadALogFile() {
        LogCliForwarder.Result result = LogCliForwarder.run(project, List.of("list"), project.resolve("missing.jar"));
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("pickleball jar"), result.err());
        assertTrue(result.command().isEmpty());
    }
}
