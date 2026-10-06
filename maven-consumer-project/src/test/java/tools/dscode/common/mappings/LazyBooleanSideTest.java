package tools.dscode.common.mappings;

import org.junit.jupiter.api.Test;

import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.LogForwarder;

import java.util.ArrayDeque;
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

    @Test
    void skippedTernaryArmDoesNotResolveAMissingReference() {
        CountingMap map = mapOf("Flag", "true", "Taken", "6");
        assertEquals("6", map.resolveWholeText("<{ <Flag> ? <Taken> : <MISSING> }>"));
        assertTrue(map.reads.contains("Flag"));
        assertTrue(map.reads.contains("Taken"));
        assertFalse(map.reads.contains("MISSING"));
        String log = latestLog("-> true ? 6 : <MISSING> -> 6");
        assertTrue(log.contains("<{ <Flag> ? <Taken> : <MISSING> }> -> true ? 6 : <MISSING> -> 6"), log);
        assertFalse(log.contains("pkbLazy"), log);
    }

    @Test
    void skippedTernaryArmKeepsNestedOperatorsUnpasted() {
        CountingMap map = mapOf("A", "true");
        assertEquals("1", map.resolveWholeText("<{ false ? (<MISSING> && <A>) : 1 }>"));
        assertFalse(map.reads.contains("MISSING"));
        assertFalse(map.reads.contains("A"));
        assertEquals("true", map.resolveWholeText("<{ false ? <MISSING> : (<A> || <NOPE>) }>"));
        assertTrue(map.reads.contains("A"));
        assertFalse(map.reads.contains("MISSING"));
        assertFalse(map.reads.contains("NOPE"));
    }

    @Test
    void takenTernaryArmPastesAndCanThrow() {
        CountingMap map = mapOf("Taken", "6");
        assertEquals("1", map.resolveWholeText("<{ false ? ((( : 1 }>"));
        assertFalse(map.reads.contains("MISSING"));
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> map.resolveWholeText("<{ true ? ((( : <MISSING> }>"));
        assertTrue(thrown.getMessage().contains("((("), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("pkbLazy"), thrown.getMessage());
        assertFalse(map.reads.contains("MISSING"));
        String log = latestLog("true ? ((( : <MISSING>");
        assertTrue(log.contains("evaluation failed"), log);
        assertTrue(log.contains("true ? ((( : <MISSING>"), log);
        assertFalse(log.contains("pkbLazy"), log);
        assertEquals("6", map.resolveWholeText("<{ false ? <MISSING> : <Taken> }>"));
        assertTrue(map.reads.contains("Taken"));
    }

    @Test
    void trailingQuestionMarkIsStillTheBooleanMarker() {
        CountingMap map = mapOf("A", "true");
        assertEquals("true", map.resolveWholeText("<{ <A>? }>"));
        assertEquals("false", map.resolveWholeText("<{ 1 == 0? }>"));
        assertEquals("true", map.resolveWholeText("<{ true ? true : <MISSING>? }>"));
        assertFalse(map.reads.contains("MISSING"));
        assertThrows(RuntimeException.class, () -> map.resolveWholeText("<{ true ? <MISSING> : false? }>"));
    }

    @Test
    void plainExpressionLogsFilledText() {
        CountingMap map = mapOf("A", "6", "B", "1");
        assertEquals("7", map.resolveWholeText("<{ <A> + <B> }>"));
        assertTrue(map.reads.contains("A"));
        assertTrue(map.reads.contains("B"));
        String log = latestLog("-> 6 + 1 -> 7");
        assertTrue(log.contains("<{ <A> + <B> }> -> 6 + 1 -> 7"), log);
        assertFalse(log.contains("pkbLazy"), log);
    }

    @Test
    void notAndComparisonAndFunctionArgumentStillRun() {
        CountingMap map = mapOf("A", "true", "B", "9", "C", "4");
        assertEquals("false", map.resolveWholeText("<{ !<A> && <MISSING> }>"));
        assertFalse(map.reads.contains("MISSING"));
        assertEquals("true", map.resolveWholeText("<{ <B> > <C> || <MISSING> }>"));
        assertTrue(map.reads.contains("B"));
        assertTrue(map.reads.contains("C"));
        assertFalse(map.reads.contains("MISSING"));
        assertEquals("true", map.resolveWholeText("<{ bool(true ? 1 : <MISSING>) }>"));
        assertFalse(map.reads.contains("MISSING"));
        assertEquals("true", map.resolveWholeText("<{ !(true ? false : <MISSING>) }>"));
    }

    private static String latestLog(String needle) {
        String found = null;
        ArrayDeque<Entry> pending = new ArrayDeque<>();
        pending.add(LogForwarder.getDefaultEntry());
        while (!pending.isEmpty()) {
            Entry entry = pending.removeFirst();
            if (entry.text != null && entry.text.contains(needle)) {
                found = entry.text;
            }
            pending.addAll(entry.children);
        }
        return found == null ? "" : found;
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
