package tools.dscode.common.dataelements;

import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.dataoperations.TextPredicateMatcher;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

final class DataSelectionSupport {
    private DataSelectionSupport() {
    }

    static DataSelection finish(
            DataContext context,
            DataQuery query,
            List<DataCandidate> projected,
            BiPredicate<DataCandidate, DataQuery> matcher
    ) {
        if (context.sourceNull()) {
            if (query.cardinality().required()) {
                throw new DataQueryException(
                        "Cannot select " + requestedName(query)
                                + " because the "
                                + context.declaredKind().singularName()
                                + " context resolved to null."
                );
            }
            return new DataSelection(context, query, List.of(), 0, 0);
        }

        List<DataCandidate> filtered = filter(projected, query, matcher);
        List<DataCandidate> selected = select(filtered, query);
        enforceCardinality(
                context,
                query,
                projected.size(),
                filtered.size(),
                selected.size()
        );
        return new DataSelection(
                context,
                query,
                selected,
                projected.size(),
                filtered.size()
        );
    }

    static boolean matchesProjection(
            Object projection,
            List<TextOp> predicates
    ) {
        for (TextOp predicate : predicates) {
            if (!matchesProjection(projection, predicate)) {
                return false;
            }
        }
        return true;
    }

    static boolean matchesProjection(
            Object projection,
            TextOp predicate
    ) {
        if (projection instanceof Iterable<?> values) {
            for (Object value : values) {
                if (TextPredicateMatcher.matches(value, predicate)) {
                    return true;
                }
            }
            return false;
        }
        if (projection != null && projection.getClass().isArray()) {
            int length = Array.getLength(projection);
            for (int index = 0; index < length; index++) {
                if (TextPredicateMatcher.matches(
                        Array.get(projection, index),
                        predicate
                )) {
                    return true;
                }
            }
            return false;
        }
        return TextPredicateMatcher.matches(projection, predicate);
    }

    private static List<DataCandidate> filter(
            List<DataCandidate> candidates,
            DataQuery query,
            BiPredicate<DataCandidate, DataQuery> matcher
    ) {
        if (query.predicates().isEmpty()) {
            return candidates;
        }
        List<DataCandidate> filtered = new ArrayList<>();
        for (DataCandidate candidate : candidates) {
            if (matcher.test(candidate, query)) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private static List<DataCandidate> select(
            List<DataCandidate> filtered,
            DataQuery query
    ) {
        if (filtered.isEmpty()) {
            return List.of();
        }
        if (query.ordinal() != null) {
            int index = query.ordinal() - 1;
            return index < filtered.size()
                    ? List.of(filtered.get(index))
                    : List.of();
        }
        if (query.boundary() == DataBoundary.FIRST) {
            return List.of(filtered.getFirst());
        }
        if (query.boundary() == DataBoundary.LAST) {
            return List.of(filtered.getLast());
        }
        if (!query.cardinality().many()) {
            return List.of(filtered.getFirst());
        }
        if (query.stride() == null) {
            return filtered;
        }

        List<DataCandidate> selected = new ArrayList<>();
        int stride = query.stride();
        for (int position = stride;
             position <= filtered.size();
             position += stride) {
            selected.add(filtered.get(position - 1));
        }
        return selected;
    }

    private static void enforceCardinality(
            DataContext context,
            DataQuery query,
            int projectedCount,
            int filteredCount,
            int selectedCount
    ) {
        if (!query.cardinality().required() || selectedCount > 0) {
            return;
        }

        String contextType = context.declaredKind().singularName();
        if (projectedCount == 0) {
            throw new DataQueryException(
                    "Cannot select " + requestedName(query)
                            + " because the " + contextType
                            + " context contains no "
                            + pluralName(query.kind()) + "."
            );
        }
        if (filteredCount == 0) {
            throw new DataQueryException(
                    "No " + pluralName(query.kind())
                            + " matched the requested predicates. "
                            + "Context type: " + contextType + ". "
                            + "Candidate count before filtering: "
                            + projectedCount + "."
            );
        }
        if (query.ordinal() != null) {
            throw new DataQueryException(
                    "Cannot select the " + ordinal(query.ordinal())
                            + " " + query.kind().singularName()
                            + " because only " + filteredCount
                            + " candidates matched."
            );
        }
        if (query.stride() != null) {
            throw new DataQueryException(
                    "Cannot select every " + ordinal(query.stride())
                            + " " + query.kind().singularName()
                            + " because only " + filteredCount
                            + " candidates remained after filtering."
            );
        }
        throw new DataQueryException(
                "No " + pluralName(query.kind())
                        + " were selected from context type "
                        + contextType + "."
        );
    }

    private static String requestedName(DataQuery query) {
        return query.cardinality().many()
                ? pluralName(query.kind())
                : query.kind().singularName();
    }

    private static String pluralName(DataElementKind kind) {
        return kind.pluralName() == null
                ? kind.singularName() + " values"
                : kind.pluralName();
    }

    private static String ordinal(int value) {
        int mod100 = value % 100;
        int mod10 = value % 10;
        String suffix = mod100 >= 11 && mod100 <= 13
                ? "th"
                : switch (mod10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return value + suffix;
    }
}
