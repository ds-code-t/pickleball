package dev.domengine.premap;

import dev.domengine.log.EngineLog;
import dev.domengine.snapshot.SnapshotWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneMatchTest {
    @TempDir
    Path temp;

    @Test
    void twoEqualButtonsAreAmbiguous() {
        OneMatchException failure = assertThrows(OneMatchException.class, () -> OneMatch.resolve(List.of(
                candidate("a", "button", "Go", null, false, false, true),
                candidate("b", "button", "Go", null, false, false, true)
        ), OneMatch.Policy.act(0.15, Set.of())));
        assertEquals("ambiguous: 2 candidates", failure.getMessage());
        assertEquals(2, failure.candidates().size());
        assertEquals("kept", failure.candidates().get(0).reason());
        assertEquals("kept", failure.candidates().get(1).reason());
        assertEquals("a", failure.toJson().get(0).path("handle").asText());
    }

    @Test
    void marginPicksTheLeader() {
        OneMatch.Resolved resolved = OneMatch.resolve(List.of(
                candidate("a", "button", "Go", 0.90, false, false, true),
                candidate("b", "button", "Stop", 0.70, false, false, true)
        ), OneMatch.Policy.resolve(0.15, Set.of()));
        assertEquals("a", resolved.chosen().handle());
        assertEquals("chosen by score margin 0.20", resolved.explain());
    }

    @Test
    void aCloseScoreIsATie() {
        OneMatchException failure = assertThrows(OneMatchException.class, () -> OneMatch.resolve(List.of(
                candidate("a", "button", "Go", 0.81, false, false, true),
                candidate("b", "button", "Go", 0.80, false, false, true)
        ), OneMatch.Policy.resolve(0.15, Set.of())));
        assertTrue(failure.getMessage().startsWith("ambiguous: 2 candidates"), failure.getMessage());
        assertTrue(failure.candidates().get(0).reason().startsWith("tie: 0.81 vs 0.80 < margin 0.15"),
                failure.candidates().get(0).reason());
    }

    @Test
    void hiddenAndNegatedRelationsAreRejected() {
        OneMatchException hidden = assertThrows(OneMatchException.class, () -> OneMatch.resolve(List.of(
                candidate("a", "button", "Go", 1.0, true, false, true)
        ), OneMatch.Policy.act(0.15, Set.of())));
        assertEquals("not found", hidden.getMessage());
        assertEquals("rejected: hidden", hidden.candidates().get(0).reason());

        Candidate negated = new Candidate("a", "button", "Go", "Go", new Box(0, 0, 10, 10), 0.4,
                false, "", false, true, false, List.of("not-inside dialog"), "");
        OneMatchException rejected = assertThrows(OneMatchException.class,
                () -> OneMatch.resolve(List.of(negated), OneMatch.Policy.act(0.15, Set.of())));
        assertEquals("rejected: not-inside dialog satisfied", rejected.candidates().get(0).reason());
    }

    @Test
    void snapshotWarningFiresOnce() throws Exception {
        SnapshotWarnings.resetForTests();
        EngineLog.configure(Map.of(
                "DOMENGINE_LOG", "snapshot",
                "DOMENGINE_LOG_DIR", temp.toString()
        ));
        try {
            assertTrue(SnapshotWarnings.once("robot reason=native-tool-missing"));
            assertTrue(!SnapshotWarnings.once("robot reason=native-tool-missing"));
            assertTrue(SnapshotWarnings.once("x11 reason=grim-missing"));
            String text = Files.readString(Files.list(temp).filter(path -> path.toString().endsWith(".log")).findFirst().orElseThrow());
            int first = count(text, "robot reason=native-tool-missing");
            assertEquals(1, first, text);
            assertTrue(text.contains("WARN"), text);
        } finally {
            SnapshotWarnings.resetForTests();
            EngineLog.resetForTests();
        }
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            found++;
            at += needle.length();
        }
        return found;
    }

    private static Candidate candidate(
            String handle, String role, String name, Double score, boolean hidden, boolean capped, boolean interactable
    ) {
        return new Candidate(handle, role, name, name, new Box(0, 0, 10, 10), score, capped,
                capped ? "area 0.92 > 0.60" : "", hidden, interactable, false, List.of(), "");
    }
}
