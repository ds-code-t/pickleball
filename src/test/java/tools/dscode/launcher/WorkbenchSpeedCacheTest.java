package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;
import tools.dscode.control.protocol.speed.SplitInputFingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void liveWorkerListIsNotADiscoverCacheHit() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project>live</project>\n");
        Path live = PickleballLocalLayout.workbenchStateRoot(project).resolve("live/classes");
        Files.createDirectories(live);
        Path jar = project.resolve("lib.jar");
        Files.writeString(jar, "jar");
        ClasspathCache.store(
                PickleballLocalLayout.workbenchStateRoot(project),
                InputFingerprint.lockHash(project),
                List.of(live.toString(), jar.toString())
        );
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

    @Test
    void cacheHitSyncModeFollowsResourcesJavaAndUnchangedInputs() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project>one</project>\n");
        Path feature = project.resolve("src/test/resources/features/called.feature");
        Path java = project.resolve("src/test/java/Glue.java");
        Files.createDirectories(feature.getParent());
        Files.createDirectories(java.getParent());
        Files.writeString(feature, "Feature: old\n");
        Files.writeString(java, "class Glue {}\n");
        Path classes = project.resolve("target/test-classes");
        Files.createDirectories(classes);
        Files.writeString(project.resolve("lib.jar"), "jar");
        Path state = PickleballLocalLayout.workbenchStateRoot(project);
        SplitInputFingerprint.Parts stored = SplitInputFingerprint.capture(project);
        SplitInputFingerprint.store(state, stored);
        ClasspathCache.store(state, InputFingerprint.lockHash(project), List.of(classes.toString(), project.resolve("lib.jar").toString()));
        assertTrue(WorkbenchSpeed.cacheHit(project));

        assertEquals(SplitInputFingerprint.Mode.SKIP, syncMode());
        Files.writeString(feature, "Feature: new text\n");
        assertEquals(SplitInputFingerprint.Mode.RESOURCES, syncMode());
        Files.writeString(feature, "Feature: old\n");
        Files.writeString(java, "class Glue { int changed; }\n");
        assertEquals(SplitInputFingerprint.Mode.FULL, syncMode());
    }

    private SplitInputFingerprint.Mode syncMode() {
        Path state = PickleballLocalLayout.workbenchStateRoot(project);
        return SplitInputFingerprint.decide(SplitInputFingerprint.load(state), SplitInputFingerprint.capture(project));
    }
}
