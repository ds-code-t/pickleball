package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public final class ElementMatcher {

    private final EnumSet<ElementType> mustMatchAll = EnumSet.noneOf(ElementType.class);
    private final EnumSet<ElementType> mustNotMatchAny = EnumSet.noneOf(ElementType.class);
    private final EnumSet<ElementType> mustMatchAtLeastOne = EnumSet.noneOf(ElementType.class);
    private final EnumSet<ElementType> mustMatchOnlyOne = EnumSet.noneOf(ElementType.class);

    public ElementMatcher mustMatchAll(ElementType... types) {
        for (var t : types) mustMatchAll.add(t);
        return this;
    }

    public ElementMatcher mustNotMatchAny(ElementType... types) {
        for (var t : types) mustNotMatchAny.add(t);
        return this;
    }

    public ElementMatcher mustMatchAtLeastOne(ElementType... types) {
        for (var t : types) mustMatchAtLeastOne.add(t);
        return this;
    }

    public ElementMatcher mustMatchOnlyOne(ElementType... types) {
        for (var t : types) mustMatchOnlyOne.add(t);
        return this;
    }

    public boolean matches(ElementType... elementTypes) {
        return matches(Set.of(elementTypes));
    }

    public boolean matches(Set<ElementType> candidate) {
        return candidate.containsAll(mustMatchAll)
                && mustNotMatchAny.stream().noneMatch(candidate::contains)
                && (mustMatchAtLeastOne.isEmpty()
                || mustMatchAtLeastOne.stream().anyMatch(candidate::contains))
                && (mustMatchOnlyOne.isEmpty()
                || mustMatchOnlyOne.stream().filter(candidate::contains).count() == 1);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ElementMatcher\n");

        if (!mustMatchAll.isEmpty())
            sb.append("  must match ALL:      ").append(mustMatchAll).append('\n');

        if (!mustMatchAtLeastOne.isEmpty())
            sb.append("  must match AT LEAST ONE: ")
                    .append(mustMatchAtLeastOne).append('\n');

        if (!mustMatchOnlyOne.isEmpty())
            sb.append("  must match ONLY ONE: ")
                    .append(mustMatchOnlyOne).append('\n');

        if (!mustNotMatchAny.isEmpty())
            sb.append("  must NOT match ANY:  ").append(mustNotMatchAny).append('\n');

        return sb.toString().trim();
    }

}
