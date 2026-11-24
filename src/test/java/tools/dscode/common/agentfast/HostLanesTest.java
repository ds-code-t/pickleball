package tools.dscode.common.agentfast;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command lane stays free while a wait is only counted, not parked on that thread. */
class HostLanesTest {
    @Test
    void commandReturnsWhileAWaitIsEntered() throws Exception {
        int before = HostLanes.waits();
        HostLanes.enterWait();
        Thread parked = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                HostLanes.leaveWait();
            }
        });
        parked.start();
        try {
            long started = System.nanoTime();
            String value = HostLanes.onCommand(() -> "snap");
            long millis = (System.nanoTime() - started) / 1_000_000L;
            assertEquals("snap", value);
            assertTrue(millis < 300, "command lane took " + millis + " ms while a wait was entered");
            assertTrue(HostLanes.waits() >= before + 1, "waits=" + HostLanes.waits());
        } finally {
            parked.join(2000);
        }
        assertEquals(before, HostLanes.waits());
        assertFalse(HostLanes.commandBusy());
    }

    @Test
    void commandLaneReentersAndReportsBusy() throws Exception {
        AtomicBoolean busy = new AtomicBoolean();
        String value = HostLanes.onCommand(() -> {
            busy.set(HostLanes.commandBusy());
            return HostLanes.onCommand(() -> "nested");
        });
        assertEquals("nested", value);
        assertTrue(busy.get());
        assertFalse(HostLanes.commandBusy());
    }
}
