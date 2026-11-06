package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchWorkerMainTest {
    @Test
    void mainMarksJvmAndClasspathScanBeforeGlueInitialization() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            WorkbenchWorkerMain.reportStartupStages();
        } finally {
            System.setErr(previous);
        }
        String text = err.toString(StandardCharsets.UTF_8);
        int jvm = text.indexOf("progress stage=jvm-main");
        int scan = text.indexOf("progress stage=classpath-scan");
        assertTrue(jvm >= 0 && scan > jvm, text);
        System.out.println("windows-shaped-worker " + text.replace('\n', ' ').trim());
    }

    @Test
    void absentLiveReadyFileDoesNotWaitAndAFailureFileAborts() throws Exception {
        assertEquals("ready", WorkbenchWorkerMain.pollLiveReady(null, null));
        Path dir = Files.createTempDirectory("live-ready");
        try {
            Path ready = dir.resolve("live.ready");
            Path failed = dir.resolve("live.failed");
            Files.writeString(ready, "ok");
            assertEquals("ready", WorkbenchWorkerMain.pollLiveReady(ready, failed));
            Files.delete(ready);
            Files.writeString(failed, "no");
            assertEquals("aborted", WorkbenchWorkerMain.pollLiveReady(ready, failed));
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // temp cleanup
                    }
                });
            }
        }
    }
}