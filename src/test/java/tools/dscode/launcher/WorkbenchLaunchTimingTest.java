package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.nio.file.Files;
import java.nio.file.Path;

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

    private static void assertFalsePrinted(String text) {
        assertTrue(!text.contains("phase4 timing"), text);
    }
}