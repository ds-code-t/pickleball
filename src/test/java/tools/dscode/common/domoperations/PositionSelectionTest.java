package tools.dscode.common.domoperations;

import org.junit.jupiter.api.Test;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionSelectionTest {
    @Test
    void lastThirdEveryEmptyAndOutOfRange() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("e"), PositionSelection.apply(items, match("last", "")));
        assertEquals(List.of("c"), PositionSelection.apply(items, match("3", "")));
        assertEquals(List.of("b", "d"), PositionSelection.apply(items, match("2", "every")));
        assertEquals(List.of(), PositionSelection.apply(List.of(), match("1", "")));
        assertEquals(List.of(), PositionSelection.apply(items, match("9", "")));
        assertEquals(List.of(), PositionSelection.apply(items, match("0", "")));
        assertEquals(items, PositionSelection.apply(items, match("", "every")));
        assertEquals(List.of("e"), PositionSelection.apply(items, match("last", "every")));
    }

    @Test
    void blankPositionUsesElementIndex() {
        ElementMatch match = ElementMatch.resolved("Button");
        match.elementPosition = "";
        match.selectionType = "";
        match.elementIndex = 3;
        assertEquals(List.of("c"), PositionSelection.apply(List.of("a", "b", "c"), match));
    }

    @Test
    void positionLogIsTraceAndOffUnlessNamed() throws Exception {
        Path dir = Files.createTempDirectory("pkb-position-log");
        String previous = System.setProperty(CompositeLog.KEY, "position");
        String previousDir = System.setProperty(CompositeLog.DIR_KEY, dir.toString());
        CompositeRuntime.resetForTests();
        try {
            PositionSelection.apply(List.of("a", "b", "c"), match("3", ""));
            String log = Files.readString(dir.resolve("composite-" + LocalDate.now(ZoneOffset.UTC) + ".log"));
            assertTrue(log.contains("TRACE position apply size=3 position=3 selection= kept=1"), log);
            assertTrue(log.contains("durationMs="), log);
        } finally {
            restore(CompositeLog.KEY, previous);
            restore(CompositeLog.DIR_KEY, previousDir);
            CompositeRuntime.resetForTests();
        }
    }

    private static ElementMatch match(String position, String selection) {
        ElementMatch match = ElementMatch.resolved("Button");
        match.elementPosition = position;
        match.selectionType = selection;
        return match;
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
