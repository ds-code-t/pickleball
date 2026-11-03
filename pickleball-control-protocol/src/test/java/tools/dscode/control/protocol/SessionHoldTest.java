package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHoldTest {
    @TempDir
    Path temp;

    @Test
    void runningStepDoesNotAdvanceUntilRelease() throws Exception {
        Instant started = Instant.parse("2026-10-29T16:00:00Z");
        SessionDescriptor running = descriptor(SessionDescriptor.State.RUNNING, started);
        SessionDescriptorWriter.publish(temp, running, true);
        AtomicBoolean advanced = new AtomicBoolean();
        Thread step = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            SessionDescriptor.State state = SessionStepGate.awaitAdvance(temp, "run-1", Duration.ofSeconds(3));
            if (state == SessionDescriptor.State.RUNNING) advanced.set(true);
        });
        step.start();
        Thread.sleep(20);
        SessionDescriptor held = SessionDescriptorWriter.hold(temp, "session-1", started.plusSeconds(1));
        assertEquals(SessionDescriptor.State.HELD, held.state());
        assertEquals(started, held.startedAt());
        Thread.sleep(150);
        assertFalse(advanced.get(), "the next step advanced while the session was held");
        SessionDescriptor released = SessionDescriptorWriter.release(temp, "session-1", started.plusSeconds(2));
        assertEquals(SessionDescriptor.State.RUNNING, released.state());
        assertEquals(started, released.startedAt());
        step.join(3000);
        assertFalse(step.isAlive());
        assertTrue(advanced.get());
        assertFalse(Files.isRegularFile(SessionDescriptorWriter.file(temp, "run-1").getParent().resolve("hold.json")));
    }

    @Test
    void releaseRestoresPausedAndASecondHoldKeepsIt() {
        Instant started = Instant.parse("2026-10-29T16:00:00Z");
        SessionDescriptorWriter.publish(temp, descriptor(SessionDescriptor.State.PAUSED, started), true);
        SessionDescriptorWriter.hold(temp, "run-1", started.plusSeconds(1));
        SessionDescriptorWriter.hold(temp, "session-1", started.plusSeconds(2));
        SessionDescriptor released = SessionDescriptorWriter.release(temp, "session-1", started.plusSeconds(3));
        assertEquals(SessionDescriptor.State.PAUSED, released.state());
        assertEquals(started, released.startedAt());
    }

    @Test
    void idleCannotBeHeldAndTheFileStays() throws Exception {
        Instant started = Instant.parse("2026-10-29T16:00:00Z");
        SessionDescriptorWriter.publish(temp, descriptor(SessionDescriptor.State.IDLE, started), true);
        String before = Files.readString(SessionDescriptorWriter.file(temp, "run-1"));
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                SessionDescriptorWriter.hold(temp, "session-1", started.plusSeconds(1)));
        assertTrue(rejected.getMessage().contains("idle"));
        assertEquals(before, Files.readString(SessionDescriptorWriter.file(temp, "run-1")));
    }

    private SessionDescriptor descriptor(SessionDescriptor.State state, Instant started) {
        return new SessionDescriptor(
                "session-1",
                "run-1",
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "chrome",
                "127.0.0.1:9222",
                "https://example.test/",
                List.of("window-a"),
                state,
                started,
                started
        );
    }
}
