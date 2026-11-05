package tools.dscode.common.domoperations;

import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One position rule for element finds.
 *
 * <p>Callers are {@link NestedByLocator}, {@code ContextWrapper},
 * {@code AllContextsScan}, and the composite ordinal slice. The order around
 * this call stays displayed, then nesting, then regex, then position.
 * {@code last} keeps the last item. A blank selection keeps one 1-based index.
 * {@code every} (and the other non-blank selection words) keeps that step,
 * starting at the same index. Index {@code 1} keeps the whole list.
 * Out of range keeps nothing. {@code pkb_composite_log=position} writes
 * {@code TRACE position apply}.
 */
public final class PositionSelection {
    private static final int LAST = -1;

    private PositionSelection() {
    }

    /**
     * Engine {@code --nth} / {@code --every}. {@code nth} is 0-based, {@code -1} is last.
     * Null keeps the whole list. A regex filter still has to run in Java before this slice.
     */
    public record EnginePosition(int nth, Integer every) {
        public String logLine() {
            if (every == null) return "nth " + nth;
            return "nth " + nth + " every " + every;
        }
    }

    /** True when a {@code MATCHES} op must see the list before position. */
    public static boolean regexBeforePosition(ElementMatch match) {
        if (match == null || match.textOps == null) return false;
        for (ElementMatch.TextOp textOp : match.textOps) {
            if (textOp.op() == ExecutionDictionary.Op.MATCHES) return true;
        }
        return false;
    }

    /**
     * The same choice as {@link #apply}, as engine flags.
     * Null keeps every item. An index below 1 is an out-of-range nth.
     */
    public static EnginePosition enginePosition(ElementMatch match) {
        if (match == null) return null;
        String position = match.elementPosition == null ? "" : match.elementPosition.trim();
        String selection = match.selectionType == null ? "" : match.selectionType.trim();
        int index = index(position, match.elementIndex);
        if (index == LAST) return new EnginePosition(LAST, null);
        if (selection.isEmpty()) {
            if (index < 1) return new EnginePosition(Integer.MAX_VALUE, null);
            return new EnginePosition(index - 1, null);
        }
        if (index == 1) return null;
        if (index < 1) return new EnginePosition(Integer.MAX_VALUE, null);
        return new EnginePosition(index - 1, index);
    }

    public static <T> List<T> apply(List<T> items, ElementMatch match) {
        long start = System.nanoTime();
        List<T> chosen = choose(items, match);
        log(items, match, chosen, start);
        return chosen;
    }

    /**
     * Composite query ordinal. {@code null} keeps every hit. {@code -1} is last.
     * Any other value is a 0-based index. Out of range keeps nothing.
     */
    public static <T> List<T> applyOrdinal(List<T> items, Integer zeroBasedOrLast) {
        long start = System.nanoTime();
        if (items == null) {
            logOrdinal(0, zeroBasedOrLast, 0, start);
            return List.of();
        }
        if (zeroBasedOrLast == null || items.isEmpty()) {
            logOrdinal(items.size(), zeroBasedOrLast, items.size(), start);
            return items;
        }
        int at = zeroBasedOrLast < 0 ? items.size() - 1 : zeroBasedOrLast;
        if (at < 0 || at >= items.size()) {
            logOrdinal(items.size(), zeroBasedOrLast, 0, start);
            return List.of();
        }
        logOrdinal(items.size(), zeroBasedOrLast, 1, start);
        return List.of(items.get(at));
    }

    /** Same step {@code every N} uses. {@code step} below 1 keeps nothing. */
    public static <T> ArrayList<T> every(List<T> list, int startIndex, int step) {
        ArrayList<T> out = new ArrayList<>();
        if (list == null || list.isEmpty() || step < 1) {
            return out;
        }
        for (int i = Math.max(0, startIndex); i < list.size(); i += step) {
            out.add(list.get(i));
        }
        return out;
    }

    private static <T> List<T> choose(List<T> items, ElementMatch match) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        if (match == null) {
            return items;
        }
        String position = match.elementPosition == null ? "" : match.elementPosition.trim();
        String selection = match.selectionType == null ? "" : match.selectionType.trim();
        int index = index(position, match.elementIndex);
        if (index == LAST) {
            return one(items.get(items.size() - 1));
        }
        if (selection.isEmpty()) {
            if (index < 1 || index > items.size()) {
                return new ArrayList<>();
            }
            return one(items.get(index - 1));
        }
        if (index == 1) {
            return items;
        }
        if (index < 1) {
            return new ArrayList<>();
        }
        return every(items, index - 1, index);
    }

    private static int index(String position, int elementIndex) {
        if (position.equalsIgnoreCase("last")) {
            return LAST;
        }
        if (position.isEmpty()) {
            return elementIndex > 0 ? elementIndex : 1;
        }
        if (position.equalsIgnoreCase("first")) {
            return 1;
        }
        return Integer.parseInt(position);
    }

    private static <T> List<T> one(T item) {
        ArrayList<T> out = new ArrayList<>(1);
        out.add(item);
        return out;
    }

    private static void log(List<?> items, ElementMatch match, List<?> chosen, long start) {
        int size = items == null ? 0 : items.size();
        String position = match == null || match.elementPosition == null ? "" : match.elementPosition.trim();
        String selection = match == null || match.selectionType == null ? "" : match.selectionType.trim();
        int kept = chosen == null ? 0 : chosen.size();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        CompositeLog.trace(
                "position",
                "apply size=" + size
                        + " position=" + position
                        + " selection=" + selection
                        + " kept=" + kept
                        + " durationMs=" + durationMs
        );
    }

    private static void logOrdinal(int size, Integer ordinal, int kept, long start) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        CompositeLog.trace(
                "position",
                "ordinal size=" + size
                        + " at=" + (ordinal == null ? "" : ordinal)
                        + " kept=" + kept
                        + " durationMs=" + durationMs
        );
    }
}
