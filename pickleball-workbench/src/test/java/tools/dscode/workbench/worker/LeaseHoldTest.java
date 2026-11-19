package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decision table for the 120s lease, 30s renew, and --hold. */
class LeaseHoldTest {
    @Test
    void constantsMatchTheDocumentedLease() {
        assertEquals(120, LeasePolicy.PAUSE_LEASE_SECONDS);
        assertEquals(30, LeasePolicy.LEASE_RENEW_SECONDS);
        assertEquals(3600, LeasePolicy.MAX_LEASE_SECONDS);
        assertEquals(120, WorkbenchWorkerManager.PAUSE_LEASE_SECONDS);
        assertEquals(30, WorkbenchWorkerManager.LEASE_RENEW_SECONDS);
    }

    @Test
    void freshHeartbeatRenewsAndAStaleOneEndsTheSession() {
        long now = 1_000_000L;
        LeasePolicy.Decision held = LeasePolicy.decide(true, false, now - 1_000, now, 0, true);
        assertTrue(held.renew());
        assertEquals(120, held.leaseSeconds());
        assertFalse(held.sessionEnded());
        assertFalse(held.stopRenewal());

        LeasePolicy.Decision stale = LeasePolicy.decide(true, false, now - 121_000, now, 0, true);
        assertFalse(stale.renew());
        assertTrue(stale.sessionEnded());
        assertTrue(stale.stopRenewal());
    }

    @Test
    void onePauseFailureRetriesAndTheThirdEndsTheSession() {
        LeasePolicy.Decision first = LeasePolicy.decide(true, false, 0, 10, 0, false);
        assertTrue(first.renew());
        assertFalse(first.sessionEnded());

        LeasePolicy.Decision third = LeasePolicy.decide(true, false, 0, 10, 2, false);
        assertFalse(third.renew());
        assertTrue(third.sessionEnded());
        assertTrue(third.stopRenewal());
    }

    @Test
    void holdKeepsTheLeaseWhileTheProcessIsAlive() {
        LeasePolicy.Decision held = LeasePolicy.decide(true, true, 0, 500_000, 5, false);
        assertTrue(held.renew());
        assertEquals(3600, held.leaseSeconds());
        assertFalse(held.sessionEnded());
        assertFalse(held.stopRenewal());

        LeasePolicy.Decision dead = LeasePolicy.decide(false, true, 0, 500_000, 0, true);
        assertTrue(dead.sessionEnded());
        assertTrue(dead.stopRenewal());
    }

    @Test
    void holdFileAndPropertyEnableTheLease(@TempDir Path project) throws Exception {
        assertFalse(LeasePolicy.holdEnabled(project));
        Path file = project.resolve(LeasePolicy.HOLD_RELATIVE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hold\n");
        assertTrue(LeasePolicy.holdEnabled(project));

        String previous = System.getProperty("pkb_pause_hold");
        try {
            System.setProperty("pkb_pause_hold", "true");
            assertTrue(LeasePolicy.holdEnabled(null));
        } finally {
            if (previous == null) System.clearProperty("pkb_pause_hold");
            else System.setProperty("pkb_pause_hold", previous);
        }
    }
}
