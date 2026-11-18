package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.SelectorWatchdog;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerWatchdogTest {
    @Test
    void simulatedStuckSelectorRestartsTheWorker() {
        SelectorWatchdog watchdog = new SelectorWatchdog(Duration.ofMillis(50), 80);
        watchdog.observe(0L, 0L);
        assertFalse(watchdog.unhealthy());
        watchdog.observe(20_000_000L, 19_000_000L);
        assertFalse(watchdog.unhealthy(), "inside the window");
        watchdog.observe(60_000_000L, 55_000_000L);
        assertTrue(watchdog.unhealthy());
        assertEquals(SelectorWatchdog.SPIN_REASON, watchdog.reason());

        WorkbenchWorkerStatus killed = new WorkbenchWorkerStatus(
                false, "old-session", 42L, "runtime-old", "scenario-old", false, 137
        );
        assertTrue(WorkerRestart.canStart(killed));
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<WorkbenchWorkerStatus> restarted = new AtomicReference<>();
        restarted.set(WorkerRestart.afterUnhealthy(true, killed, () -> {
            starts.incrementAndGet();
            return new WorkbenchWorkerStatus(
                    true, "fresh-session", 99L, "runtime-fresh", "scenario-fresh", true, null
            );
        }));

        assertEquals(1, starts.get());
        assertTrue(restarted.get().running());
        assertEquals("fresh-session", restarted.get().sessionId());
        assertEquals(99L, restarted.get().pid());
    }

    @Test
    void aLiveProcessIsNotReplacedAndAQuietSelectorDoesNotRestart() {
        WorkbenchWorkerStatus live = new WorkbenchWorkerStatus(
                true, "live", 7L, "runtime", "scenario", true, null
        );
        assertFalse(WorkerRestart.canStart(live));
        assertThrows(IllegalStateException.class, () -> WorkerRestart.afterUnhealthy(true, live, () -> {
            throw new AssertionError("must not start while the old process is alive");
        }));

        WorkbenchWorkerStatus killed = new WorkbenchWorkerStatus(
                false, "old", 7L, "runtime", "scenario", false, 143
        );
        AtomicInteger starts = new AtomicInteger();
        WorkbenchWorkerStatus same = WorkerRestart.afterUnhealthy(false, killed, () -> {
            starts.incrementAndGet();
            return killed;
        });
        assertEquals(0, starts.get());
        assertEquals(killed, same);
        assertTrue(WorkerRestart.canStart(killed));
    }
}
