package tools.dscode.control.protocol.speed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmGenerationTest {
    @TempDir
    Path root;

    @Test
    void materializeCopiesAndKeepsAnInUseGeneration() throws Exception {
        Path project = root.resolve("project");
        Path feature = project.resolve("target/test-classes/features/a.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: one\n");
        Path state = root.resolve("state");
        SplitInputFingerprint.Parts parts = SplitInputFingerprint.capture(project);
        WarmGeneration.Snapshot snapshot = WarmGeneration.materialize(project, state, parts);
        Path copied = snapshot.testClasses().resolve("features/a.feature");
        assertTrue(Files.isRegularFile(copied));
        assertEquals("Feature: one\n", Files.readString(copied));
        assertFalse(Files.isSameFile(feature, copied));
        Object sourceInode = Files.getAttribute(feature, "unix:ino");
        Object copyInode = Files.getAttribute(copied, "unix:ino");
        assertFalse(sourceInode.equals(copyInode));

        Path stale = WarmGeneration.warm(state).resolve("gen-stale");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("left.txt"), "x");
        WarmGeneration.markInUse(state, snapshot.hash());
        WarmGeneration.gc(state, null);
        assertTrue(Files.isDirectory(WarmGeneration.warm(state).resolve("gen-" + snapshot.hash())));
        assertFalse(Files.exists(stale));

        Path deferred = WarmGeneration.warm(state).resolve("gen-later");
        Files.createDirectories(deferred);
        Files.writeString(deferred.resolve("later.txt"), "y");
        Files.writeString(
                WarmGeneration.warm(state).resolve("gc-deferred.txt"),
                deferred.toAbsolutePath().normalize() + "\n"
        );
        WarmGeneration.gc(state, snapshot.hash());
        assertFalse(Files.exists(deferred));
        assertTrue(Files.isDirectory(WarmGeneration.warm(state).resolve("gen-" + snapshot.hash())));
    }

    @Test
    void pinClasspathDropsLiveOutputAndKeepsJars() throws Exception {
        Path project = root.resolve("pin");
        Path classes = project.resolve("target/classes");
        Path tests = project.resolve("target/test-classes");
        Files.createDirectories(classes);
        Files.createDirectories(tests);
        Path jar = project.resolve("lib.jar");
        Files.writeString(jar, "jar");
        Path genClasses = root.resolve("gen/classes");
        Path genTests = root.resolve("gen/test-classes");
        List<String> pinned = WarmGeneration.pinClasspath(
                project,
                List.of(tests.toString(), classes.toString(), jar.toString()),
                genClasses,
                genTests
        );
        assertEquals(genTests.toAbsolutePath().normalize().toString(), pinned.get(0));
        assertEquals(genClasses.toAbsolutePath().normalize().toString(), pinned.get(1));
        assertTrue(pinned.contains(jar.toAbsolutePath().normalize().toString()));
        assertFalse(pinned.contains(tests.toAbsolutePath().normalize().toString()));
        assertFalse(pinned.contains(classes.toAbsolutePath().normalize().toString()));
    }
}
