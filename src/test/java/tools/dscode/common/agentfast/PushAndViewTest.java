package tools.dscode.common.agentfast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushAndViewTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        PushChannel.resetForTests();
    }

    @Test
    void unboundAcceptDoesNotWrite() {
        assertFalse(PushChannel.accept(Map.of("type", "click", "source", "human")));
        assertTrue(tools.dscode.control.protocol.AgentEvents.lines(temp).isEmpty());
    }

    @Test
    void followReturnsAPushedLineUnderOneSecond() {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("type", "chat");
        event.put("source", "human");
        event.put("text", "hello panel");
        assertTrue(PushChannel.accept(temp, event));
        long started = System.nanoTime();
        PushChannel.Hit hit = PushChannel.follow(temp, "text=hello", "chat", 0, true, 0);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(0, hit.exit());
        assertEquals("human", hit.event().get("source"));
        assertEquals("chat", hit.event().get("type"));
        assertTrue(ms < 1000, "follow took " + ms + "ms");
    }

    @Test
    void humanIsNotAKeystrokeAndFollowWithoutACursorStartsNow() {
        assertFalse(PushChannel.matches("human", Map.of("type", "input", "kind", "input", "source", "human", "text", "a")));
        assertTrue(PushChannel.matches("human", Map.of("type", "chat", "source", "human", "text", "hi")));
        assertFalse(PushChannel.matches("human", Map.of("type", "chat", "source", "agent", "text", "mine")));
        assertTrue(PushChannel.matches("human", Map.of("type", "answer", "id", "q1", "choice", "Yes")));
        assertTrue(PushChannel.matches("human", Map.of("type", "click", "id", "pkb-9", "source", "human")));
        assertFalse(PushChannel.matches("human", Map.of("type", "click", "id", "save", "source", "human")));
        assertTrue(PushChannel.quietKeystroke(Map.of("type", "input", "kind", "input"), null));
        assertTrue(PushChannel.quietKeystroke(Map.of("type", "select", "kind", "select"), temp));
        assertFalse(PushChannel.quietKeystroke(Map.of("type", "click", "id", "pkb-1"), null));
        tools.dscode.common.recording.RecordingSession.start(temp, "rec");
        assertFalse(PushChannel.quietKeystroke(Map.of("type", "input", "kind", "input"), temp));
        PushChannel.accept(temp, Map.of("type", "chat", "source", "human", "text", "old"));
        int now = tools.dscode.control.protocol.AgentEvents.lineCount(temp);
        PushChannel.Hit missed = PushChannel.follow(temp, "text=old", "", 0, true, now);
        assertEquals(1, missed.exit());
        PushChannel.Hit found = PushChannel.follow(temp, "text=old", "", 0, true, 0);
        assertEquals(0, found.exit());
        assertEquals("old", found.event().get("text"));
    }

    @Test
    void followHidesOwnEchoAndCapsTheRate() {
        PushChannel.accept(temp, Map.of("type", "chat", "source", "agent", "text", "mine"));
        PushChannel.accept(temp, Map.of("type", "chat", "source", "human", "text", "yours"));
        PushChannel.Hit hidden = PushChannel.follow(temp, "", "", 0, true, 0);
        assertEquals("human", hidden.event().get("source"));
        PushChannel.Hit shown = PushChannel.follow(temp, "", "", 0, false, 0);
        assertEquals("agent", shown.event().get("source"));
        PushChannel.resetForTests();
        int stored = 0;
        int refused = 0;
        for (int index = 0; index < 25; index++) {
            if (PushChannel.accept(temp, Map.of("type", "click", "source", "human", "text", "n" + index))) stored++;
            else refused++;
        }
        assertEquals(PushChannel.RATE, stored);
        assertTrue(refused > 0);
    }

    @Test
    void standardViewRanksGroupsAndStripsNoise() {
        assertEquals("Save", StandardView.normalize(" \u200bSave\u200d "));
        List<Map<String, Object>> rows = List.of(
                Map.of("type", "chat", "source", "agent", "text", "echo"),
                Map.of("type", "click", "source", "human", "text", "go"),
                Map.of("type", "chat", "source", "human", "text", "hi")
        );
        Map<String, String> args = new LinkedHashMap<>();
        args.put("echo", "hide");
        args.put("priority", "on");
        args.put("group", "type");
        List<Map<String, Object>> shaped = StandardView.shape(rows, args);
        assertTrue(shaped.stream().noneMatch(row -> "agent".equals(row.get("source"))));
        assertEquals("# chat", shaped.get(0).get("line"));
        assertEquals("human", shaped.get(1).get("source"));
        assertTrue(StandardView.elementFields().contains("frame"));
    }

    @Test
    void compactInspectKeepsAnElementLinePerPhrase() {
        String raw = "source=page\nframe=top\nphrase=Save\nvisibleCount=1\nxpath=/html/body/button[1]\n"
                + "phrase=Cancel\nvisibleCount=1\nxpath=/html/body/button[2]\n";
        List<String> lines = CompactReply.elementLines(raw);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("Save"));
        assertTrue(lines.get(1).contains("Cancel"));
        assertTrue(lines.get(0).contains("frame=top"));
    }
}
