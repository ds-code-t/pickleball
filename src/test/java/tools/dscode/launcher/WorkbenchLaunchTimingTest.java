package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchLaunchTimingTest {
    @TempDir
    Path tempDir;

    @Test
    void stageLineNamesTheJvmAndLandsInTheRunFolder() throws Exception {
        assertEquals(
                "workbench launch jvm=launcher stage=index elapsedMs=12 files=3 reparsed=0",
                WorkbenchLaunchTiming.format("launcher", "index", 12, "files=3 reparsed=0")
        );
        WorkbenchLaunchTiming.record(tempDir, "test", "scenario-start", 40, "");
        String text = Files.readString(tempDir.resolve(WorkbenchLaunchTiming.FILE_NAME));
        assertTrue(text.contains("jvm=test stage=scenario-start elapsedMs=40"), text);
        assertFalsePrinted(text);
    }

    @Test
    void uncompiledCaptureNotesTheCompileStage() throws Exception {
        Path project = tempDir.resolve("app");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path workspace = project.resolve(".pickleball/runs/gap/workspace");
        Files.createDirectories(workspace);
        WorkspaceLaunch.capture(project, workspace, false, new StringBuilder());
        String text = Files.readString(workspace.getParent().resolve(WorkbenchLaunchTiming.FILE_NAME));
        assertTrue(text.contains("stage=compile"), text);
        assertTrue(text.contains(" start"), text);
        assertTrue(text.contains("exit=failed"), text);
        assertTrue(text.indexOf("stage=compile") < text.indexOf("exit=failed"), text);
    }

    @Test
    void publishedClasspathNotesTheSeedFork() throws Exception {
        Path project = tempDir.resolve("fork");
        Files.createDirectories(project);
        Path classpath = PickleballLocalLayout.workbenchStateRoot(project).resolve("classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, project.resolve("missing-classes").toString() + "\n");
        Path workspace = project.resolve(".pickleball/runs/gap/workspace");
        Files.createDirectories(workspace);
        assertThrows(IllegalStateException.class,
                () -> WorkspaceLaunch.capture(project, workspace, false, new StringBuilder()));
        String text = Files.readString(workspace.getParent().resolve(WorkbenchLaunchTiming.FILE_NAME));
        int start = text.indexOf("stage=seed-fork");
        assertTrue(start >= 0, text);
        assertTrue(text.indexOf("exit=", start) > start, text);
    }

    @Test
    void seedPublishLeavesTheLiveWorkerClasspathFirst() throws Exception {
        Path project = tempDir.resolve("order");
        Files.createDirectories(project.resolve("target/test-classes"));
        Path state = PickleballLocalLayout.workbenchStateRoot(project);
        Path live = state.resolve("live").resolve("classes");
        Files.createDirectories(live);
        Path session = state.resolve(PickleballLocalLayout.WORKER_CLASSPATH_FILE);
        String liveText = live.toAbsolutePath().normalize().toString();
        String classes = project.resolve("target/test-classes").toAbsolutePath().normalize().toString();
        String dep = project.resolve("dep.jar").toString();
        Files.writeString(session, liveText + "\n" + dep + "\n");
        String before = Files.readString(session);

        WorkspaceLaunch.publishSeedClasspath(project, List.of(classes, dep));

        assertEquals(before, Files.readString(session), "seed publish replaced the session classpath");
        List<String> cached = WorkspaceLaunch.cachedClasspath(project);
        assertEquals(liveText, Path.of(cached.getFirst()).toAbsolutePath().normalize().toString());
        String seed = Files.readString(state.resolve(PickleballLocalLayout.SEED_CLASSPATH_FILE));
        assertTrue(seed.startsWith(classes), seed);

        Files.delete(session);
        List<String> seeded = WorkspaceLaunch.cachedClasspath(project);
        assertEquals(classes, Path.of(seeded.getFirst()).toAbsolutePath().normalize().toString());
    }

    private static void assertFalsePrinted(String text) {
        assertTrue(!text.contains("phase4 timing"), text);
    }
}