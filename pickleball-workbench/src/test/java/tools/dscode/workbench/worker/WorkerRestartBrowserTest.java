package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worker stop quits the driver hook, kills the child tree, and emits browser_lost. */
class WorkerRestartBrowserTest {
    @TempDir
    Path project;

    @Test
    void stopKillsTheChildTreeAndEmitsBrowserLost() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 60 & wait").redirectErrorStream(true).start();
        try {
            long child = awaitChild(process);
            AtomicBoolean quit = new AtomicBoolean();
            WorkerShutdown.release(process, () -> quit.set(true), project, "sess-1");
            assertTrue(quit.get(), "WebDriver quit hook ran");
            assertFalse(process.isAlive(), "worker shell still alive");
            assertTrue(ProcessHandle.of(child).map(handle -> !handle.isAlive()).orElse(true), "orphan sleep pid=" + child);
            String events = Files.readString(project.resolve(".pickleball/agent-events.jsonl"));
            assertTrue(events.contains("\"event\":\"browser_lost\""), events);
            assertTrue(events.contains("sess-1"), events);
            assertFalse(Files.exists(project.resolve(".pickleball/workbench/live-bridge.json")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static long awaitChild(Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            long child = process.descendants().mapToLong(ProcessHandle::pid).findFirst().orElse(-1);
            if (child > 0) return child;
            Thread.sleep(20);
        }
        throw new AssertionError("sleep child did not start");
    }
}
