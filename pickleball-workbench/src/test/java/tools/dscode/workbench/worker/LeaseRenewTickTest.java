package tools.dscode.workbench.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.AgentEvents;
import tools.dscode.control.protocol.ClientHeartbeat;
import tools.dscode.workbench.bridge.ControlBridgeClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B5: scheduleLeaseRenewal's tick counts a CLI heartbeat, hold, and session_ended. */
class LeaseRenewTickTest {
    @TempDir
    Path project;

    @AfterEach
    void restoreClock() {
        ControlBridgeClient.noteClientAt(System.currentTimeMillis());
    }

    @Test
    void freshCliHeartbeatKeepsTheLeaseAndAStaleClockEndsIt() throws Exception {
        long now = System.currentTimeMillis();
        ControlBridgeClient.noteClientAt(now - 200_000L);
        ClientHeartbeat.touch(project);
        AtomicInteger pauses = new AtomicInteger();
        WorkbenchWorkerManager.Renewal kept = WorkbenchWorkerManager.renewTick(
                project, true, "sess", 0, () -> {
                    pauses.incrementAndGet();
                    return true;
                }
        );
        assertFalse(kept.sessionEnded);
        assertFalse(kept.stopRenewal);
        assertEquals(1, pauses.get());

        Files.deleteIfExists(ClientHeartbeat.file(project));
        ControlBridgeClient.noteClientAt(now - 200_000L);
        AtomicInteger skipped = new AtomicInteger();
        WorkbenchWorkerManager.Renewal ended = WorkbenchWorkerManager.renewTick(
                project, true, "sess", 0, () -> {
                    skipped.incrementAndGet();
                    return true;
                }
        );
        assertTrue(ended.sessionEnded);
        assertTrue(ended.stopRenewal);
        assertEquals(0, skipped.get());
        String events = Files.readString(project.resolve(".pickleball/agent-events.jsonl"));
        assertTrue(events.contains("session_ended"), events);
    }

    @Test
    void holdSurvivesAFailedPause() throws Exception {
        Files.createDirectories(project.resolve(".pickleball/workbench"));
        Files.writeString(project.resolve(LeasePolicy.HOLD_RELATIVE), "hold\n");
        ControlBridgeClient.noteClientAt(System.currentTimeMillis() - 200_000L);
        Files.deleteIfExists(ClientHeartbeat.file(project));
        WorkbenchWorkerManager.Renewal held = WorkbenchWorkerManager.renewTick(
                project, true, "held", 0, () -> false
        );
        assertFalse(held.sessionEnded);
        assertFalse(held.stopRenewal);
        assertTrue(AgentEvents.tail(project, 20).isEmpty()
                || Files.notExists(project.resolve(".pickleball/agent-events.jsonl")));

        WorkbenchWorkerManager.Renewal third = WorkbenchWorkerManager.renewTick(
                project, true, "open", 2, () -> false
        );
        Files.deleteIfExists(project.resolve(LeasePolicy.HOLD_RELATIVE));
        ControlBridgeClient.noteClientAt(System.currentTimeMillis());
        ClientHeartbeat.touch(project);
        WorkbenchWorkerManager.Renewal stopped = WorkbenchWorkerManager.renewTick(
                project, true, "open", 2, () -> false
        );
        assertFalse(third.sessionEnded, "hold is still on for the third call above");
        assertTrue(stopped.sessionEnded);
        assertTrue(stopped.stopRenewal);
        String events = Files.readString(project.resolve(".pickleball/agent-events.jsonl"));
        assertTrue(events.contains("session_ended"), events);
    }
}
