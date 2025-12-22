package tools.dscode.common.treeparsing.parsedComponents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public abstract class PassedData {

    public int elementCount;
    public ElementMatch firstElement = null;
    public ElementMatch secondElement = null;
    public ElementMatch elementBeforeOperation = null;
    public ElementMatch elementAfterOperation = null;
    public ElementMatch lastElement = null;

    public List<ElementMatch> elementMatches = new ArrayList<>();
    public List<ElementMatch> elementMatchesProceedingOperation = new ArrayList<>();
    public List<ElementMatch> elementMatchesFollowingOperation = new ArrayList<>();

    protected final Map<ElementType, ElementMatch> elementMap1 = new HashMap<>();
    protected final Map<ElementType, ElementMatch> elementMap2 = new HashMap<>();

    public PhraseData previousPhrase;
    public PhraseData nextPhrase;

    public record MatchPair(ElementMatch first, ElementMatch second) { }

    // =========================================================
    // Priority (single ElementMatch) variants
    // =========================================================

    public LinkedHashSet<ElementMatch> getElementMatchAll(ElementMatch priorityElementMatch, ElementType... types) {
        return findAll(false, priorityElementMatch, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatchAllReversed(ElementMatch priorityElementMatch, ElementType... types) {
        return findAll(true, priorityElementMatch, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatch(ElementMatch priorityElementMatch, ElementType... types) {
        return findAny(false, priorityElementMatch, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatchReversed(ElementMatch priorityElementMatch, ElementType... types) {
        return findAny(true, priorityElementMatch, types);
    }

    // =========================================================
    // Priority (list) variants: seed up to first 2 entries
    // =========================================================

    public LinkedHashSet<ElementMatch> getElementMatchAll(List<ElementMatch> priorityElementMatches, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        addUpToTwo(out, priorityElementMatches);
        mergeUpToTwo(out, findAll(false, null, types));
        return out;
    }

    public LinkedHashSet<ElementMatch> getElementMatchAllReversed(List<ElementMatch> priorityElementMatches, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        addUpToTwo(out, priorityElementMatches);
        mergeUpToTwo(out, findAll(true, null, types));
        return out;
    }

    public LinkedHashSet<ElementMatch> getElementMatch(List<ElementMatch> priorityElementMatches, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        addUpToTwo(out, priorityElementMatches);
        mergeUpToTwo(out, findAny(false, null, types));
        return out;
    }

    public LinkedHashSet<ElementMatch> getElementMatchReversed(List<ElementMatch> priorityElementMatches, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        addUpToTwo(out, priorityElementMatches);
        mergeUpToTwo(out, findAny(true, null, types));
        return out;
    }

    // =========================================================
    // Non-priority variants
    // =========================================================

    public ElementMatch getSingleElementMatch(ElementType... types) {
        return findAny(false, null, types).stream().findFirst().orElse(null);
    }

    public LinkedHashSet<ElementMatch> getElementMatchAll(ElementType... types) {
        return findAll(false, null, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatchAllReversed(ElementType... types) {
        return findAll(true, null, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatch(ElementType... types) {
        return findAny(false, null, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatchReversed(ElementType... types) {
        return findAny(true, null, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatch1(ElementType... types) {
        return findAnyInMapOnly(1, null, types);
    }

    public LinkedHashSet<ElementMatch> getElementMatch2(ElementType... types) {
        return findAnyInMapOnly(2, null, types);
    }

    public MatchPair getDistinctElementMatches(ElementType firstType, ElementType secondType) {
        LinkedHashSet<ElementMatch> a = findAny(false, null, firstType);
        LinkedHashSet<ElementMatch> b = findAny(false, first(a), secondType);
        return new MatchPair(first(a), first(b));
    }

    public MatchPair getDistinctElementMatchesReversed(ElementType firstType, ElementType secondType) {
        LinkedHashSet<ElementMatch> a = findAny(true, null, firstType);
        LinkedHashSet<ElementMatch> b = findAny(true, first(a), secondType);
        return new MatchPair(first(a), first(b));
    }

    // =========================================================
    // Core (ANY): collect up to 2 unique in encounter order
    // =========================================================

    private LinkedHashSet<ElementMatch> findAny(boolean reversed, ElementMatch priority, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        if (priority != null) out.add(priority);

        PassedData phrase = this;
        while (phrase != null && out.size() < 2) {
            if (!reversed) {
                scanAnyInto(out, phrase.elementMap1, types);
                if (out.size() < 2) scanAnyInto(out, phrase.elementMap2, types);
            } else {
                scanAnyInto(out, phrase.elementMap2, types);
                if (out.size() < 2) scanAnyInto(out, phrase.elementMap1, types);
            }
            phrase = phrase.previousPhrase;
        }
        return out;
    }

    private LinkedHashSet<ElementMatch> findAnyInMapOnly(int which, ElementMatch priority, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        if (priority != null) out.add(priority);

        PassedData phrase = this;
        while (phrase != null && out.size() < 2) {
            Map<ElementType, ElementMatch> map = (which == 1) ? phrase.elementMap1 : phrase.elementMap2;
            scanAnyInto(out, map, types);
            phrase = phrase.previousPhrase;
        }
        return out;
    }

    private static void scanAnyInto(LinkedHashSet<ElementMatch> out, Map<ElementType, ElementMatch> map, ElementType... types) {
        for (ElementType t : types) {
            ElementMatch m = map.get(t);
            if (m != null) out.add(m); // LinkedHashSet handles uniqueness + preserves insertion order
            if (out.size() >= 2) return;
        }
    }

    // =========================================================
    // Core (ALL): collect up to 2 unique matches satisfying ALL-types rule
    // =========================================================

    private LinkedHashSet<ElementMatch> findAll(boolean reversed, ElementMatch priority, ElementType... types) {
        LinkedHashSet<ElementMatch> out = new LinkedHashSet<>(2);
        if (priority != null) out.add(priority);

        PassedData phrase = this;
        while (phrase != null && out.size() < 2) {
            if (!reversed) {
                ElementMatch m = scanAllOne(phrase.elementMap1, types);
                if (m != null) out.add(m);
                if (out.size() < 2) {
                    m = scanAllOne(phrase.elementMap2, types);
                    if (m != null) out.add(m);
                }
            } else {
                ElementMatch m = scanAllOne(phrase.elementMap2, types);
                if (m != null) out.add(m);
                if (out.size() < 2) {
                    m = scanAllOne(phrase.elementMap1, types);
                    if (m != null) out.add(m);
                }
            }
            phrase = phrase.previousPhrase;
        }
        return out;
    }

    private static ElementMatch scanAllOne(Map<ElementType, ElementMatch> map, ElementType... types) {
        if (types.length == 0) return null;
        ElementMatch first = null;

        for (ElementType t : types) {
            ElementMatch m = map.get(t);
            if (m == null) return null;

            if (first == null) first = m;
            else if (m != first) return null; // ALL requested types must point to same ElementMatch
        }
        return first;
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static void addUpToTwo(LinkedHashSet<ElementMatch> out, List<ElementMatch> priorityElementMatches) {
        int limit = Math.min(2, priorityElementMatches.size());
        for (int i = 0; i < limit && out.size() < 2; i++) {
            out.add(priorityElementMatches.get(i));
        }
    }

    private static void mergeUpToTwo(LinkedHashSet<ElementMatch> target, LinkedHashSet<ElementMatch> source) {
        for (ElementMatch m : source) {
            target.add(m);
            if (target.size() >= 2) return;
        }
    }

    private static ElementMatch first(LinkedHashSet<ElementMatch> set) {
        return (set == null || set.isEmpty()) ? null : set.iterator().next();
    }
}
