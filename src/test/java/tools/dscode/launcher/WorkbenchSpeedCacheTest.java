package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSpeedCacheTest {
    @TempDir
    Path project;

    @Test
    void cacheHitRepublishesTheClasspathWithoutMaven() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project>one</project>\n");
        Path classes = project.resolve("target/test-classes");
        Files.createDirectories(classes);
        Path dependency = project.resolve("lib.jar");
        Files.writeString(dependency, "jar");
        ClasspathCache.store(
                PickleballLocalLayout.workbenchStateRoot(project),
                InputFingerprint.lockHash(project),
                List.of(classes.toString(), dependency.toString())
        );
        assertTrue(WorkbenchSpeed.cacheHit(project));
        long started = System.nanoTime();
        boolean ready = WorkspaceLaunch.warmClasspath(project, null, new StringBuilder());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(ready);
        assertTrue(elapsed < 1000, "maven cache hit took " + elapsed + "ms");
        assertTrue(Files.isRegularFile(PickleballLocalLayout.workbenchStateRoot(project)
                .resolve(PickleballLocalLayout.SEED_CLASSPATH_FILE)));
        Files.writeString(project.resolve("pom.xml"), "<project>two</project>\n");
        assertFalse(WorkbenchSpeed.cacheHit(project));
    }

    @Test
    void sequentialModeIsTheFreshJvmFallback() {
        String previous = System.getProperty(WorkbenchSpeed.MODE_PROPERTY);
        try {
            System.setProperty(WorkbenchSpeed.MODE_PROPERTY, "sequential");
            assertTrue(WorkbenchSpeed.sequential());
        } finally {
            if (previous == null) System.clearProperty(WorkbenchSpeed.MODE_PROPERTY);
            else System.setProperty(WorkbenchSpeed.MODE_PROPERTY, previous);
        }
    }
}
