package tools.dscode.control.protocol.speed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedTypesTest {
    @TempDir
    Path root;

    @Test
    void classpathCacheHitsUntilTheHashOrAnEntryChanges() throws Exception {
        Path jar = root.resolve("dep.jar");
        Files.writeString(jar, "bytes");
        ClasspathCache.store(root, "hash-a", List.of(jar.toString()));
        ClasspathCache.Hit hit = ClasspathCache.load(root, "hash-a");
        assertTrue(hit.hit());
        assertEquals(List.of(jar.toString()), hit.entries());
        assertFalse(ClasspathCache.load(root, "hash-b").hit());
        Files.delete(jar);
        assertFalse(ClasspathCache.load(root, "hash-a").hit());
    }

    @Test
    void lockHashChangesWithPomBytesAndWatchHashSeesSources() throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project.resolve("src/test/java"));
        Files.writeString(project.resolve("pom.xml"), "<project>one</project>\n");
        Files.writeString(project.resolve("src/test/java/A.java"), "class A {}\n");
        String lock = InputFingerprint.lockHash(project);
        String watch = InputFingerprint.watchHash(project);
        assertFalse(lock.equals(watch));
        Files.writeString(project.resolve("pom.xml"), "<project>two</project>\n");
        String lockAfterPom = InputFingerprint.lockHash(project);
        assertFalse(lock.equals(lockAfterPom));
        String watchAfterPom = InputFingerprint.watchHash(project);
        Files.writeString(project.resolve("src/test/java/A.java"), "class A { int x; }\n");
        assertEquals(lockAfterPom, InputFingerprint.lockHash(project));
        assertFalse(watchAfterPom.equals(InputFingerprint.watchHash(project)));
    }

    @Test
    void parallelStagesOverlapAndSequentialDoesNot() {
        StageScheduler.Outcome parallel = StageScheduler.run(List.of(
                new StageScheduler.Task("index", () -> {
                    Thread.sleep(150);
                    return "index";
                }),
                new StageScheduler.Task("compile", () -> {
                    Thread.sleep(150);
                    return "compile";
                })
        ), true);
        assertFalse(parallel.failed());
        assertTrue(parallel.overlaps().contains("index+compile"), parallel.overlaps().toString());
        StageScheduler.Outcome sequential = StageScheduler.run(List.of(
                new StageScheduler.Task("index", () -> "index"),
                new StageScheduler.Task("compile", () -> "compile")
        ), false);
        assertTrue(sequential.overlaps().isEmpty());
        AtomicBoolean second = new AtomicBoolean();
        StageScheduler.Outcome failed = StageScheduler.run(List.of(
                new StageScheduler.Task("sync", () -> {
                    throw new IllegalStateException("boom");
                }),
                new StageScheduler.Task("index", () -> {
                    second.set(true);
                    return "index";
                })
        ), false);
        assertTrue(failed.failed());
        assertFalse(second.get());
    }

    @Test
    void releaseDropsADeadHandleWithoutWaiting() throws Exception {
        WarmProcess.write(root, new WarmProcess.Handle(1_000_000_001L, 9, "watch"));
        long started = System.nanoTime();
        WarmProcess.release(root);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertFalse(Files.isRegularFile(WarmProcess.file(root)));
        assertTrue(elapsedMs < 2_000, "release waited " + elapsedMs + "ms");
    }

    @Test
    void ownerClaimTracksOnlyALivePidAndReleaseClearsIt() throws Exception {
        assertEquals(0L, WarmProcess.liveOwner(root));
        WarmProcess.claim(root, ProcessHandle.current().pid());
        assertEquals(ProcessHandle.current().pid(), WarmProcess.readOwner(root));
        assertEquals(ProcessHandle.current().pid(), WarmProcess.liveOwner(root));
        WarmProcess.claim(root, 1_000_000_001L);
        assertEquals(1_000_000_001L, WarmProcess.readOwner(root));
        assertEquals(0L, WarmProcess.liveOwner(root));
        assertFalse(Files.isRegularFile(WarmProcess.ownerFile(root)));
        WarmProcess.claim(root, ProcessHandle.current().pid());
        WarmProcess.release(root);
        assertEquals(0L, WarmProcess.liveOwner(root));
        assertFalse(Files.isRegularFile(WarmProcess.ownerFile(root)));
    }

    @Test
    void warmHandleIsReusableOnlyWhileThePidAndHashMatch() throws Exception {
        Process process = new ProcessBuilder("sleep", "30").start();
        try {
            WarmProcess.write(root, new WarmProcess.Handle(process.pid(), 9, "watch"));
            assertTrue(WarmProcess.reusable(root, "watch").isPresent());
            assertTrue(WarmProcess.reusable(root, "other").isEmpty());
            assertTrue(Files.isRegularFile(WarmProcess.file(root)));
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
        assertTrue(WarmProcess.reusable(root, "watch").isEmpty());
        assertFalse(Files.exists(WarmProcess.file(root)));
    }
}
