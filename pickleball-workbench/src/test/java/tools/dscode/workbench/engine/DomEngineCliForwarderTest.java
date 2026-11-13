package tools.dscode.workbench.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DomEngineCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsDesktopVerbsAndDoesNotEmbedEngineTypes() {
        Path jar = project.resolve("pickleball.jar");
        List<String> command = DomEngineCliForwarder.command(jar, List.of("desktop", "list"));
        assertEquals("domengine", command.get(3));
        assertEquals(List.of("desktop", "list"), command.subList(4, command.size()));
        assertFalse(command.stream().anyMatch(arg -> arg.contains("dev.domengine")));
    }
}
