package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code start} must not hand a Maven classworlds classpath to LiveBrowserHost. */
class LiveHostClasspathTest {
    @TempDir
    Path project;

    @Test
    void cacheBeatsAClassworldsClasspathThatLacksLiveBrowserHost() throws Exception {
        Path classes = project.resolve("host-classes");
        writeHostClass(classes);
        Path versioned = project.resolve(".pickleball/v/3.0.0/workbench");
        Files.createDirectories(versioned);
        Files.writeString(project.resolve(".pickleball/current.json"), """
                {
                  "pickleballVersion": "3.0.0",
                  "complete": true
                }
                """);
        Files.writeString(versioned.resolve("classpath.cache"), quote(classes) + "\n");
        Files.createDirectories(project.resolve(".pickleball/workbench"));
        Files.writeString(project.resolve(".pickleball/workbench/classpath.cache"), quote(Path.of("maven-launcher.jar")) + "\n");
        String chosen = ConsumerReadyCommands.hostClasspath(project, "C:\\maven\\boot\\plexus-classworlds.jar");
        assertEquals(classes.toString(), chosen);
        assertTrue(ConsumerReadyCommands.classpathHasHost(chosen));
        assertFalse(chosen.contains("plexus-classworlds"), chosen);
    }

    @Test
    void javaClassPathWinsWhenItAlreadyContainsLiveBrowserHost() throws Exception {
        Path classes = project.resolve("on-jvm");
        writeHostClass(classes);
        Path elsewhere = project.resolve("stale-cache");
        writeHostClass(elsewhere);
        Files.createDirectories(project.resolve(".pickleball/workbench"));
        Files.writeString(project.resolve(".pickleball/workbench/classpath.cache"), quote(elsewhere) + "\n");
        String chosen = ConsumerReadyCommands.hostClasspath(project, classes.toString());
        assertEquals(classes.toString(), chosen);
    }

    @Test
    void loaderClasspathIsUsedWhenTheCacheAndTheWrapperLackLiveBrowserHost() throws Exception {
        Files.createDirectories(project.resolve(".pickleball/workbench"));
        Files.writeString(project.resolve(".pickleball/workbench/classpath.cache"), quote(Path.of("maven-launcher.jar")) + "\n");
        String wrapper = "C:\\maven\\boot\\plexus-classworlds.jar";
        String chosen = ConsumerReadyCommands.hostClasspath(project, wrapper);
        assertTrue(ConsumerReadyCommands.classpathHasHost(chosen), chosen);
        assertFalse(chosen.equals(wrapper), chosen);
        assertFalse(chosen.contains("plexus-classworlds"), chosen);
    }

    private static void writeHostClass(Path classes) throws IOException {
        Path file = classes.resolve(ConsumerReadyCommands.HOST_CLASS_ENTRY);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
    }

    /** Java argument-file quoting: one pair of quotes and doubled backslashes. */
    private static String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
