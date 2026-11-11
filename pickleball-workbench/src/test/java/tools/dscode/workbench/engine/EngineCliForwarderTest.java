package tools.dscode.workbench.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineCliForwarderTest {
    @Test
    void commandForwardsToTheEngineCli() {
        Path jar = Path.of("pickleball.jar");
        Path project = Path.of("project");
        List<String> command = EngineCliForwarder.command(jar, project, List.of("run", "--text=Feature: F"));
        assertEquals("engine", command.get(3));
        assertTrue(command.contains("run"));
        assertTrue(command.getLast().startsWith("--project="));
    }
}
