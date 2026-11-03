package tools.dscode.control.protocol;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Step advance waits while a live descriptor is {@code held}.
 * No descriptor means this is not a live session, so the step proceeds.
 */
public final class SessionStepGate {
    public static final Duration DEFAULT_WAIT = Duration.ofMinutes(10);

    private SessionStepGate() {
    }

    public static SessionDescriptor.State awaitAdvance(Path project, String runId, Duration timeout) {
        if (project == null || runId == null || runId.isBlank()) return null;
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<SessionDescriptor> current = SessionDescriptorWriter.read(project, runId);
            if (current.isEmpty()) return null;
            SessionDescriptor.State state = current.get().state();
            if (state != SessionDescriptor.State.HELD) return state;
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Session " + runId + " stayed held.");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while session " + runId + " was held.",
                        interrupted
                );
            }
        }
    }
}
