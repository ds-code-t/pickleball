package tools.dscode.workbench.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerReadyForwarderTest {
    @TempDir
    Path temp;

    @Test
    void commandUsesConsumerClasspathEntries() throws Exception {
        Path jar = Files.createFile(temp.resolve("pickleball.jar"));
        List<String> verb = List.of("start", "--url=http://127.0.0.1/", "--browser=chrome", "--env=FOO=bar");
        List<String> command = ConsumerReadyForwarder.command(temp, jar, verb);
        assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toString(), command.get(0));
        assertEquals("-cp", command.get(1));
        assertEquals(ConsumerClasspath.MAIN_CLASS, command.get(3));
        StringBuilder expected = new StringBuilder();
        for (Path entry : ConsumerClasspath.entries(temp, jar)) {
            if (!expected.isEmpty()) expected.append(java.io.File.pathSeparator);
            expected.append(entry.toAbsolutePath().normalize());
        }
        assertEquals(expected.toString(), command.get(2));
        assertTrue(command.contains("start"));
        assertTrue(command.contains("--url=http://127.0.0.1/"));
        assertTrue(command.contains("--browser=chrome"));
        assertTrue(command.get(command.size() - 1).startsWith("--project="));
    }

    @Test
    void forwardUsesTheSameCommandWithoutAProcessWhenTheSeamIsSet() throws Exception {
        Path jar = Files.createFile(temp.resolve("pickleball.jar"));
        List<String> seen = new ArrayList<>();
        ConsumerReadyForwarder.runner = command -> {
            seen.addAll(command);
            return new ConsumerReadyForwarder.Result(0, "session=live1\n", "", command);
        };
        String previous = System.getProperty("pkb.pickleball.jar");
        System.setProperty("pkb.pickleball.jar", jar.toString());
        try {
            ConsumerReadyForwarder.Result result = ConsumerReadyForwarder.forward(
                    temp, List.of("bridge-health", "--url=http://127.0.0.1:9"));
            assertEquals(0, result.exit());
            assertEquals(ConsumerReadyForwarder.command(
                    temp, jar, List.of("bridge-health", "--url=http://127.0.0.1:9")), seen);
        } finally {
            ConsumerReadyForwarder.runner = null;
            if (previous == null) System.clearProperty("pkb.pickleball.jar");
            else System.setProperty("pkb.pickleball.jar", previous);
        }
    }
}
