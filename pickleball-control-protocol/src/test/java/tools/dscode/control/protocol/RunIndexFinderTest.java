package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunIndexFinderTest {
    @TempDir
    Path project;

    @Test
    void parentNameBeatsJsonRunIdAndSkipsProfileConfigAndScratch() throws Exception {
        Path hit = project.resolve(".pickleball/runs/run-deep/a/b/c/d/run-deep/run-index.json");
        Files.createDirectories(hit.getParent());
        Files.writeString(hit, "{\"runId\":\"ignored\",\"outcome\":\"FAILED\"}");
        for (String skipped : new String[]{"browser-profile", "config", "scratch"}) {
            Path decoy = project.resolve(".pickleball/runs/run-deep/" + skipped + "/run-deep/run-index.json");
            Files.createDirectories(decoy.getParent());
            Files.writeString(decoy, "{\"runId\":\"run-deep\",\"outcome\":\"PASSED\"}");
        }
        Path tooDeep = project.resolve(".pickleball/runs/run-deep/a/b/c/d/e/run-deep/run-index.json");
        Files.createDirectories(tooDeep.getParent());
        Files.writeString(tooDeep, "{\"runId\":\"run-deep\",\"outcome\":\"PASSED\"}");

        Path found = RunIndexFinder.find(project, "run-deep");
        assertEquals(hit.toAbsolutePath().normalize(), found.toAbsolutePath().normalize());
    }

    @Test
    void unsafeIdAndMissingProjectReturnNull() {
        assertNull(RunIndexFinder.find(null, "run-1"));
        assertNull(RunIndexFinder.find(project, null));
        assertNull(RunIndexFinder.find(project, "../run"));
        assertNull(RunIndexFinder.find(project, ""));
    }

    @Test
    void flexibleStripsTheWindowsPrefix() throws Exception {
        Path file = project.resolve("run-index.json");
        Files.writeString(file, "{}");
        String windows = "\\\\?\\" + file.toAbsolutePath().toString().replace("/", "\\");
        assertEquals(file.toAbsolutePath().normalize(), RunIndexFinder.flexible(windows).toAbsolutePath().normalize());
        assertTrue(Files.isRegularFile(RunIndexFinder.flexible(windows)));
        assertNull(RunIndexFinder.flexible("  "));
    }
}
