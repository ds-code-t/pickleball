package tools.dscode.workbench.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.launch.ConsumerClasspath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomEngineCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsDesktopVerbsAndDoesNotEmbedEngineTypes() throws Exception {
        Path jar = project.resolve("pickleball.jar");
        Files.write(jar, new byte[] {1});
        List<String> command = DomEngineCliForwarder.command(jar, project, List.of("desktop", "list"));
        int verb = command.indexOf("domengine");
        assertTrue(verb > 3, command.toString());
        assertEquals("-cp", command.get(1));
        assertEquals(ConsumerClasspath.MAIN_CLASS, command.get(verb - 1));
        assertEquals(List.of("desktop", "list"), command.subList(verb + 1, command.size()));
        assertFalse(command.stream().anyMatch(arg -> arg.contains("dev.domengine")));
        assertFalse(command.contains("-jar"), command.toString());
        String classpath = command.get(2);
        assertTrue(classpath.contains("sqlite-jdbc"), classpath);
        assertTrue(classpath.contains("jackson-databind"), classpath);
        assertTrue(classpath.contains("jackson-core"), classpath);
        assertTrue(classpath.contains("jackson-annotations"), classpath);
    }
}
