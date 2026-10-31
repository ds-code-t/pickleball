package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionDescriptorTest {
    @TempDir
    Path temp;

    @Test
    void populatesEveryDescriptorField() throws Exception {
        Path workspace = temp.resolve("My Workspace").toAbsolutePath().normalize();
        Instant started = Instant.parse("2026-10-29T15:41:00Z");
        Instant updated = Instant.parse("2026-10-29T15:41:08Z");
        List<String> handles = new ArrayList<>(List.of("CDwindow-1", "CDwindow-2"));
        SessionDescriptor descriptor = new SessionDescriptor(
                "session-1",
                "run-1",
                workspace.toString(),
                " chrome ",
                "127.0.0.1:9222",
                "https://example.test/a?q=\"x\"&p=\\\\y",
                handles,
                SessionDescriptor.State.RUNNING,
                started,
                updated
        );
        handles.add("mutated-after-copy");

        assertEquals("session-1", descriptor.sessionId());
        assertEquals("run-1", descriptor.runId());
        assertEquals(workspace.toString(), descriptor.workspacePath());
        assertEquals("chrome", descriptor.browserType());
        assertEquals("127.0.0.1:9222", descriptor.cdpDebuggerAddress());
        assertEquals("https://example.test/a?q=\"x\"&p=\\\\y", descriptor.currentUrl());
        assertEquals(List.of("CDwindow-1", "CDwindow-2"), descriptor.windowHandles());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.windowHandles().add("nope"));
        assertEquals(SessionDescriptor.State.RUNNING, descriptor.state());
        assertEquals(started, descriptor.startedAt());
        assertEquals(updated, descriptor.updatedAt());
        assertEquals(SessionDescriptor.CONTRACT_VERSION, descriptor.contractVersion());
        assertEquals("1", descriptor.contractVersion());

        Path written = SessionDescriptorWriter.publish(temp, descriptor, true).orElseThrow();
        assertEquals(SessionDescriptorWriter.file(temp, "run-1"), written);
        String json = Files.readString(written);
        assertEquals(descriptor.toJson(), json);
        assertTrue(json.contains("\"schema\": \"session-descriptor.v1\""));
        assertTrue(json.contains("\"contractVersion\": \"1\""));
        assertEquals(descriptor, SessionDescriptor.parse(json));
        assertEquals(descriptor, SessionDescriptorWriter.read(temp, "run-1").orElseThrow());
    }

    @Test
    void missingCdpAddressStaysNull() throws Exception {
        SessionDescriptor descriptor = descriptor(
                "session-1", "run-null", SessionDescriptor.State.IDLE,
                null, null, "edge", List.of()
        );
        SessionDescriptorWriter.publish(temp, descriptor, true);
        String json = Files.readString(SessionDescriptorWriter.file(temp, "run-null"));
        assertTrue(json.contains("\"cdpDebuggerAddress\": null"));
        assertTrue(json.contains("\"currentUrl\": null"));
        assertTrue(json.contains("\"browserType\": \"edge\""));
        assertTrue(json.contains("\"windowHandles\": []"));
        assertFalse(json.contains("\"cdpDebuggerAddress\": \"null\""));

        SessionDescriptor read = SessionDescriptorWriter.read(temp, "run-null").orElseThrow();
        assertEquals(null, read.cdpDebuggerAddress());
        assertEquals(null, read.currentUrl());
        assertEquals("edge", read.browserType());
        assertEquals(List.of(), read.windowHandles());
        assertEquals(null, read.compositeSessionDir());
        assertEquals(null, read.compositeSessionId());
        String older = json.replace("  \"compositeSessionDir\": null,\n", "")
                .replace("  \"compositeSessionId\": null,\n", "");
        assertFalse(older.contains("compositeSession"));
        assertEquals(descriptor, SessionDescriptor.parse(older));
    }

    @Test
    void atomicReplaceNeverLeavesAPartialDescriptor() throws Exception {
        SessionDescriptor first = descriptor(
                "session-a", "run-atomic", SessionDescriptor.State.RUNNING,
                null, "https://example.test/one", "chrome", List.of("only")
        );
        SessionDescriptor second = descriptor(
                "session-a", "run-atomic", SessionDescriptor.State.PAUSED,
                "127.0.0.1:9333", "https://example.test/two", "chrome",
                List.of("left", "right")
        );
        Path file = SessionDescriptorWriter.publish(temp, first, true).orElseThrow();
        String original = Files.readString(file);
        assertEquals(first.toJson(), original);

        Path replaced = SessionDescriptorWriter.publish(temp, second, true).orElseThrow();
        assertEquals(file, replaced);
        String json = Files.readString(file);
        assertEquals(second.toJson(), json);
        assertFalse(json.contains("example.test/one"));
        assertFalse(json.contains("\"only\""));
        assertEquals(second, SessionDescriptor.parse(json));
        try (var children = Files.list(file.getParent())) {
            assertEquals(List.of("descriptor.json"), children.map(path -> path.getFileName().toString()).toList());
        }

        SessionDescriptor left = second;
        SessionDescriptor right = descriptor(
                "session-b", "run-atomic", SessionDescriptor.State.HELD,
                "127.0.0.1:9444", null, "chrome", List.of("held-window")
        );
        AtomicBoolean stop = new AtomicBoolean();
        CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(2);
        Thread writer = new Thread(() -> {
            started.countDown();
            SessionDescriptor flip = left;
            while (!stop.get()) {
                SessionDescriptorWriter.publish(temp, flip, true);
                flip = flip == left ? right : left;
            }
        });
        Thread reader = new Thread(() -> {
            started.countDown();
            while (!stop.get()) {
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    SessionDescriptor parsed = SessionDescriptor.parse(text);
                    if (!parsed.equals(left) && !parsed.equals(right)) {
                        failures.add("partial " + text);
                    } else if (!text.equals(parsed.toJson())) {
                        failures.add("torn " + text);
                    }
                } catch (Exception failure) {
                    failures.add(failure.toString());
                }
            }
        });
        writer.start();
        reader.start();
        started.await();
        Thread.sleep(200);
        stop.set(true);
        writer.join();
        reader.join();
        assertEquals(List.of(), failures);
        try (var children = Files.list(file.getParent())) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
        SessionDescriptor resting = SessionDescriptorWriter.read(temp, "run-atomic").orElseThrow();
        assertTrue(resting.equals(left) || resting.equals(right));
    }

    @Test
    void stateTransitionsPreserveIdentityAndRejectIllegalMoves() throws Exception {
        Instant started = Instant.parse("2026-10-29T15:00:00Z");
        SessionDescriptor initial = new SessionDescriptor(
                "session-1", "run-states", temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "chrome", "127.0.0.1:9222", "https://example.test/",
                List.of("window-a"), SessionDescriptor.State.IDLE, started, started
        );
        SessionDescriptorWriter.publish(temp, initial, true);

        Instant runningAt = started.plusSeconds(1);
        SessionDescriptor running = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.RUNNING, runningAt);
        assertEquals(SessionDescriptor.State.RUNNING, running.state());
        assertEquals(started, running.startedAt());
        assertEquals(runningAt, running.updatedAt());
        assertEquals(initial.sessionId(), running.sessionId());
        assertEquals(initial.cdpDebuggerAddress(), running.cdpDebuggerAddress());
        assertEquals(initial.windowHandles(), running.windowHandles());

        SessionDescriptor paused = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.PAUSED, runningAt.plusSeconds(1));
        SessionDescriptor held = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.HELD, runningAt.plusSeconds(2));
        SessionDescriptor resumed = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.RUNNING, runningAt.plusSeconds(3));
        SessionDescriptor stopping = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.STOPPING, runningAt.plusSeconds(4));
        SessionDescriptor stopped = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.STOPPED, runningAt.plusSeconds(5));

        assertEquals(SessionDescriptor.State.PAUSED, paused.state());
        assertEquals(SessionDescriptor.State.HELD, held.state());
        assertEquals(SessionDescriptor.State.RUNNING, resumed.state());
        assertEquals(SessionDescriptor.State.STOPPING, stopping.state());
        assertEquals(SessionDescriptor.State.STOPPED, stopped.state());
        assertEquals(started, stopped.startedAt());
        assertEquals(stopped, SessionDescriptorWriter.read(temp, "run-states").orElseThrow());

        SessionDescriptor refreshed = SessionDescriptorWriter.transition(
                temp, "run-states", SessionDescriptor.State.STOPPED, runningAt.plusSeconds(6));
        assertEquals(stopped.startedAt(), refreshed.startedAt());
        assertEquals(runningAt.plusSeconds(6), refreshed.updatedAt());

        String beforeReject = Files.readString(SessionDescriptorWriter.file(temp, "run-states"));
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                SessionDescriptorWriter.transition(
                        temp, "run-states", SessionDescriptor.State.RUNNING, runningAt.plusSeconds(7)));
        assertTrue(rejected.getMessage().contains("stopped"));
        assertEquals(beforeReject, Files.readString(SessionDescriptorWriter.file(temp, "run-states")));

        SessionDescriptor idle = descriptor(
                "session-2", "run-idle", SessionDescriptor.State.IDLE,
                null, null, "chrome", List.of()
        );
        SessionDescriptorWriter.publish(temp, idle, true);
        assertThrows(IllegalStateException.class, () ->
                SessionDescriptorWriter.transition(temp, "run-idle", SessionDescriptor.State.HELD, started.plusSeconds(1)));
        assertEquals(SessionDescriptor.State.IDLE, SessionDescriptorWriter.read(temp, "run-idle").orElseThrow().state());

        SessionDescriptorWriter.transition(temp, "run-idle", SessionDescriptor.State.RUNNING, started.plusSeconds(1));
        SessionDescriptorWriter.transition(temp, "run-idle", SessionDescriptor.State.STOPPING, started.plusSeconds(2));
        assertThrows(IllegalStateException.class, () ->
                SessionDescriptorWriter.transition(temp, "run-idle", SessionDescriptor.State.PAUSED, started.plusSeconds(3)));
        assertEquals(SessionDescriptor.State.STOPPING, SessionDescriptorWriter.read(temp, "run-idle").orElseThrow().state());
        assertThrows(IllegalStateException.class, () ->
                SessionDescriptorWriter.transition(temp, "missing", SessionDescriptor.State.RUNNING, started));
    }

    @Test
    void nonLiveRunProducesNoFile() throws Exception {
        Path run = RunView.runDirectory(temp, "ordinary");
        Files.createDirectories(run);
        Path record = run.resolve(RunView.RECORD_FILE);
        Files.writeString(record, "{\"runId\":\"ordinary\"}\n");
        Workspace.writeFingerprint(record, "abc123");

        SessionDescriptor descriptor = descriptor(
                "session-ordinary", "ordinary", SessionDescriptor.State.RUNNING,
                "127.0.0.1:9222", "https://example.test/", "chrome", List.of("window-a")
        );
        assertTrue(SessionDescriptorWriter.publish(temp, descriptor, false).isEmpty());
        assertFalse(Files.exists(SessionDescriptorWriter.file(temp, "ordinary")));
        assertFalse(Files.exists(run.resolve(SessionDescriptorWriter.DIRECTORY)));
        assertTrue(SessionDescriptorWriter.read(temp, "ordinary").isEmpty());
        assertTrue(Files.isRegularFile(record));
        assertFalse(Files.readString(record).contains("descriptor"));

        SessionDescriptorWriter.publish(temp, descriptor, true);
        assertTrue(Files.isRegularFile(SessionDescriptorWriter.file(temp, "ordinary")));
        String kept = Files.readString(SessionDescriptorWriter.file(temp, "ordinary"));
        assertTrue(SessionDescriptorWriter.publish(temp, descriptor, false).isEmpty());
        assertEquals(kept, Files.readString(SessionDescriptorWriter.file(temp, "ordinary")));
    }

    private SessionDescriptor descriptor(
            String sessionId,
            String runId,
            SessionDescriptor.State state,
            String cdp,
            String url,
            String browser,
            List<String> handles
    ) {
        Instant instant = Instant.parse("2026-10-29T15:41:00Z");
        return new SessionDescriptor(
                sessionId,
                runId,
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                browser,
                cdp,
                url,
                handles,
                state,
                instant,
                instant
        );
    }
}
