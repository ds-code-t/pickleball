package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when the pickleball jar or sources jar was built without dev.domengine.
 * Build {@code shadowJar} and {@code sourcesJar} first. This test does not publish.
 */
class DomEnginePackagingTest {
    @Test
    void pickleballJarContainsEngineClassesAndSources() throws Exception {
        Path jar = Path.of("build/libs/pickleball-3.0.0.jar");
        Path sources = Path.of("build/libs/pickleball-3.0.0-sources.jar");
        assertTrue(Files.isRegularFile(jar), "missing " + jar + "; run shadowJar");
        assertTrue(Files.isRegularFile(sources), "missing " + sources + "; run sourcesJar");
        assertEntry(jar, "dev/domengine/cli/DomEngineMain.class");
        assertEntry(jar, "dev/domengine/desktop/DesktopTree.class");
        assertEntry(sources, "dev/domengine/cli/DomEngineMain.java");
        assertEntry(sources, "dev/domengine/desktop/DesktopTree.java");
    }

    private static void assertEntry(Path archive, String name) throws Exception {
        try (JarFile file = new JarFile(archive.toFile())) {
            assertNotNull(file.getJarEntry(name), name + " missing from " + archive.getFileName());
        }
    }
}
