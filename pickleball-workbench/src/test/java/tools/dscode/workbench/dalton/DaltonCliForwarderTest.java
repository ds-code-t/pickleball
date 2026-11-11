package tools.dscode.workbench.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsToTheDaltonCli() {
        Path jar = Path.of("pickleball.jar");
        List<String> command = DaltonCliForwarder.command(jar, project, List.of("status"));
        assertEquals("dalton", command.get(3));
        assertEquals("status", command.get(4));
        assertTrue(command.get(5).startsWith("--project="), command.toString());
        assertTrue(command.get(2).endsWith("pickleball.jar"), command.toString());
        assertEquals("-jar", command.get(1));
    }

    @Test
    void missingJarDoesNotInventADatabaseCommand() {
        DaltonCliForwarder.Result result = DaltonCliForwarder.run(project, List.of("hydrate", "--dry-run"), project.resolve("missing.jar"));
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("error:"), result.err());
        assertTrue(result.err().contains("pickleball jar"), result.err());
        assertTrue(result.command().isEmpty());
    }
}
