package tools.dscode.workbench.worker;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pause-lease decisions for the interactive worker.
 * The lease is 120 seconds and renews every 30 seconds while a client heartbeat
 * is fresh. {@code --hold}, {@code PKB_PAUSE_HOLD}, {@code pkb_pause_hold}, or
 * {@code .pickleball/workbench/pause-hold} keeps the lease at 3600 seconds.
 * One failed renew does not drop the lease. Three failures, a dead process, or
 * a heartbeat older than 120 seconds (unless held) ends the session.
 */
public final class LeasePolicy {
    public static final int PAUSE_LEASE_SECONDS = 120;
    public static final int LEASE_RENEW_SECONDS = 30;
    public static final int MAX_LEASE_SECONDS = 3600;
    public static final int FAILURES_BEFORE_STOP = 3;
    public static final long HEARTBEAT_GAP_MS = PAUSE_LEASE_SECONDS * 1000L;
    public static final String HOLD_RELATIVE = ".pickleball/workbench/pause-hold";

    public record Decision(boolean renew, int leaseSeconds, boolean sessionEnded, boolean stopRenewal) { }

    private LeasePolicy() {
    }

    /**
     * @param consecutiveFailures failures already recorded before this attempt
     * @param pauseOk            whether the renew pause that just ran succeeded.
     *                           Pass {@code true} when no pause has been attempted yet.
     */
    public static Decision decide(
            boolean alive,
            boolean hold,
            long lastHeartbeatMs,
            long nowMs,
            int consecutiveFailures,
            boolean pauseOk
    ) {
        if (!alive) return new Decision(false, 0, true, true);
        if (hold) return new Decision(true, MAX_LEASE_SECONDS, false, false);
        if (!pauseOk) {
            int failures = consecutiveFailures + 1;
            if (failures >= FAILURES_BEFORE_STOP) return new Decision(false, 0, true, true);
            return new Decision(true, PAUSE_LEASE_SECONDS, false, false);
        }
        long gap = lastHeartbeatMs <= 0 ? Long.MAX_VALUE : nowMs - lastHeartbeatMs;
        if (gap > HEARTBEAT_GAP_MS) return new Decision(false, 0, true, true);
        return new Decision(true, PAUSE_LEASE_SECONDS, false, false);
    }

    public static boolean holdEnabled(Path project) {
        String property = System.getProperty("pkb_pause_hold");
        if (truth(property)) return true;
        if (truth(System.getenv("PKB_PAUSE_HOLD"))) return true;
        if (project == null) return false;
        return Files.isRegularFile(project.resolve(HOLD_RELATIVE));
    }

    private static boolean truth(String value) {
        if (value == null) return false;
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }
}
