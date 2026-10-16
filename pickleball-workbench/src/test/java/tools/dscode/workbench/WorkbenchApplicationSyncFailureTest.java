package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchApplicationSyncFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void syncFailureShowsTheIoCauseAndWritesTheRunRecord() throws Exception {
        IllegalStateException failure = new IllegalStateException(
                "Could not install Workbench directory",
                new IOException("access denied")
        );
        String text = WorkbenchApplication.explain(failure);
        assertTrue(text.contains("IOException"), text);
        assertTrue(text.contains("access denied"), text);

        Path record = tempDir.resolve(".pickleball").resolve("runs").resolve("run-sync").resolve("record.json");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "{\"runId\":\"run-sync\",\"status\":\"RUNNING\"}");
        WorkbenchApplication.noteRun(tempDir, "run-sync", "Sync failed: " + text);
        String written = Files.readString(record);
        assertTrue(written.contains("access denied"), written);
        assertTrue(written.contains("Sync failed"), written);
    }
}
