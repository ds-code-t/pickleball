package tools.dscode.common.mappings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyBooleanSideTest {
    @Test
    void trueLeftSideDoesNotResolveTheMissingRightSide() {
        CountingMap map = mapOf("A", "true");
        assertEquals("true", map.resolveWholeText("<{ <A> || <MISSING> }>"));
        assertTrue(map.reads.contains("A"));
        assertFalse(map.reads.contains("MISSING"));
    }

    @Test
    void missingLeftSideFallsThroughToATrueRightSide() {
        CountingMap map = mapOf("B", "true");
        assertEquals("true", map.resolveWholeText("<{ <MISSING> || <B> }>"));
        assertTrue(map.reads.contains("B"));
    }

    @Test
    void blankLeftSideIsFalseInsteadOfASyntaxError() {
        CountingMap map = mapOf("A", "", "B", "true");
        assertEquals("true", map.resolveWholeText("<{ <A> || <B> }>"));
    }

    @Test
    void skippedDollarCallDoesNotRunAndATakenOneStillThrows() {
        CountingMap map = new CountingMap();
        assertEquals("true", map.resolveWholeText("<{ true || <$nope> }>"));
        assertThrows(RuntimeException.class, () -> map.resolveWholeText("<{ <$nope> || true }>"));
    }

    @Test
    void takenComparisonPastesANumericString() {
        CountingMap map = mapOf("A", "6");
        assertEquals("true", map.resolveWholeText("<{ <A> > 5 || <MISSING> }>"));
        assertFalse(map.reads.contains("MISSING"));
    }

    @Test
    void singlePipeStillResolvesBothSides() {
        CountingMap map = mapOf("L", "1", "R", "1");
        assertEquals("1", map.resolveWholeText("<{ <L> | <R> }>"));
        assertTrue(map.reads.contains("L"));
        assertTrue(map.reads.contains("R"));

        CountingMap missing = mapOf("L", "1");
        assertThrows(RuntimeException.class, () -> missing.resolveWholeText("<{ <L> | <MISSING> }>"));
        assertTrue(missing.reads.contains("L"));
        assertTrue(missing.reads.contains("MISSING"));
    }

    @Test
    void badSyntaxOnASkippedSideDoesNotThrow() {
        CountingMap map = mapOf("A", "true");
        assertEquals("true", map.resolveWholeText("<{ <A> || ((( }>"));
    }

    @Test
    void badSyntaxOnATakenSideStillThrows() {
        CountingMap map = mapOf("A", "true");
        assertThrows(RuntimeException.class, () -> map.resolveWholeText("<{ ((( || <A> }>"));
    }

    @Test
    void nestedSkippedSideIsNotResolved() {
        CountingMap map = mapOf("A", "true", "B", "true");
        assertEquals("true", map.resolveWholeText("<{ <A> || (<MISSING> && <B>) }>"));
        assertFalse(map.reads.contains("MISSING"));
        assertFalse(map.reads.contains("B"));
        assertEquals("false", map.resolveWholeText("<{ false && (<MISSING> || <B>) }>"));
        assertFalse(map.reads.contains("MISSING"));
    }

    @Test
    void operatorsInsideQuotesAreNotSplits() {
        CountingMap map = mapOf("A", "true");
        assertEquals("true", map.resolveWholeText("<{ \"a || b\" == \"a || b\" || <MISSING> }>"));
        assertFalse(map.reads.contains("MISSING"));
    }

    @Test
    void falseAndSkipsTheMissingSide() {
        CountingMap map = mapOf("A", "false");
        assertEquals("false", map.resolveWholeText("<{ <A> && <MISSING> }>"));
        assertFalse(map.reads.contains("MISSING"));
    }

    private static CountingMap mapOf(String... pairs) {
        CountingMap map = new CountingMap();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static final class CountingMap extends ParsingMap {
        private final List<String> reads = new ArrayList<>();

        private CountingMap() {
            super(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        }

        @Override
        public Object get(String key) {
            reads.add(key);
            return super.get(key);
        }
    }
}
