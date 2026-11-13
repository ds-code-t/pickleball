package dev.domengine.query;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One relative-locator term. G3 reads {@code relation()}, {@code negated()},
 * {@code anchor()} and {@code weight()}. The query verb does not evaluate
 * these terms yet.
 */
public record RelationTerm(Relation relation, boolean negated, String anchor, double weight) {
    public static final double DEFAULT_WEIGHT = 1.0;

    public enum Relation {
        NEAR("near"), ABOVE("above"), BELOW("below"), LEFT_OF("left-of"), RIGHT_OF("right-of"),
        INSIDE("inside"), AFTER("after"), BEFORE("before");

        private final String engineName;

        Relation(String engineName) {
            this.engineName = engineName;
        }

        public String engine() {
            return engineName;
        }

        /** False only for document-order relations. */
        public boolean geometric() {
            return this != AFTER && this != BEFORE;
        }

        /** Case-insensitive. {@code left}/{@code right} are aliases. A {@code not-} prefix is not accepted. */
        public static Optional<Relation> fromEngine(String name) {
            if (name == null) return Optional.empty();
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("not-")) return Optional.empty();
            if ("left".equals(normalized)) return Optional.of(LEFT_OF);
            if ("right".equals(normalized)) return Optional.of(RIGHT_OF);
            for (Relation relation : values()) {
                if (relation.engineName.equals(normalized)) return Optional.of(relation);
            }
            return Optional.empty();
        }
    }

    public RelationTerm {
        if (relation == null) throw new NullPointerException("relation");
        anchor = anchor == null ? "" : anchor.trim();
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException("invalid relation weight: " + weight);
        }
    }

    public RelationTerm(Relation relation, boolean negated, String anchor) {
        this(relation, negated, anchor, DEFAULT_WEIGHT);
    }

    public RelationTerm negate() {
        return new RelationTerm(relation, !negated, anchor, weight);
    }

    /** Filter form. {@code @weight} is omitted when the weight is {@code 1.0}. */
    public String canonical() {
        StringBuilder text = new StringBuilder();
        if (negated) text.append("not-");
        text.append(relation.engine());
        if (Double.compare(weight, DEFAULT_WEIGHT) != 0) {
            text.append('@').append(formatWeight(weight));
        }
        text.append('(').append(anchor).append(')');
        return text.toString();
    }

    public static RelationTerm parse(String term) {
        return Expressions.parseFilterTerm(term);
    }

    public static List<RelationTerm> parseAll(String query) {
        return Expressions.relationTerms(query);
    }

    static String formatWeight(double weight) {
        if (weight == Math.rint(weight) && weight >= Long.MIN_VALUE && weight <= Long.MAX_VALUE) {
            return Long.toString((long) weight);
        }
        return Double.toString(weight);
    }
}
