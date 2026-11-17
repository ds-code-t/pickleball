package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** overlay-poll --after returns only newer events plus the next cursor; no --after keeps the old reply. */
class OverlayPollCursorTest {
    @TempDir
    Path project;

    @Test
    void afterReturnsOnlyNewerEvents() throws Exception {
        PageSurfaces.recordInput(project, "cur", Map.of("type", "click", "id", "pkb-1"));
        PageSurfaces.recordInput(project, "cur", Map.of("type", "click", "id", "pkb-2"));
        AgentControl.Result all = AgentControl.execute(project, "cur", "overlay-poll", Map.of("target", "page"), List.of(), "a");
        assertEquals(0, all.exit(), String.valueOf(all.body()));
        assertEquals(2, all.body().get("count"));
        assertEquals(null, all.body().get("next"));
        AgentControl.Result first = AgentControl.execute(project, "cur", "overlay-poll", Map.of("target", "page", "after", "0"), List.of(), "a");
        assertEquals(2, first.body().get("next"));
        PageSurfaces.recordInput(project, "cur", Map.of("type", "click", "id", "pkb-3"));
        AgentControl.Result newer = AgentControl.execute(project, "cur", "overlay-poll", Map.of("target", "page", "after", "2"), List.of(), "a");
        assertEquals(1, newer.body().get("count"), String.valueOf(newer.body()));
        assertEquals(3, newer.body().get("next"));
        assertEquals("pkb-3", String.valueOf(((List<?>) newer.body().get("events")).get(0) instanceof Map<?, ?> m ? m.get("id") : ""));
        AgentControl.Result bad = AgentControl.execute(project, "cur", "overlay-poll", Map.of("target", "page", "after", "x"), List.of(), "a");
        assertEquals(2, bad.exit());
    }

    @Test
    void chatPollWaitReturnsAsSoonAsAMessageArrives() throws Exception {
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(600);
                ChatLog.send(project, "wait", "run", "human", "", "late hello");
            } catch (Exception ignored) {
                // The assertion below reports a missing message.
            }
        });
        ChatLog.send(project, "wait", "run", "agent", "", "first");
        writer.start();
        long started = System.nanoTime();
        AgentControl.Result polled = AgentControl.execute(project, "wait", "chat-poll", Map.of("after", "1", "wait", "5"), List.of(), "a");
        long millis = (System.nanoTime() - started) / 1_000_000L;
        writer.join();
        assertEquals(0, polled.exit(), String.valueOf(polled.body()));
        assertEquals(1, polled.body().get("count"), String.valueOf(polled.body()));
        assertEquals(true, millis < 4000, "waited " + millis + " ms");
    }
}
