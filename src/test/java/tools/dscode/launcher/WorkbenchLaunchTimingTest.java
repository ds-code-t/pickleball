package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static void assertFalsePrinted(String text) {
        assertTrue(!text.contains("phase4 timing"), text);
    }
}