package tools.dscode.common.dataelements;

import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.util.List;

public final class CollectionQueryEngine {
    public DataSelection query(DataContext context, DataQuery query) {
        if (context == null) {
            throw new IllegalArgumentException("DataContext cannot be null");
        }
        if (query == null) {
            throw new IllegalArgumentException("DataQuery cannot be null");
        }

        List<DataCandidate> projected = context.sourceNull()
                ? List.of()
                : CollectionDataAdapter.project(context, query.kind());
        return DataSelectionSupport.finish(
                context,
                query,
                projected,
                this::matches
        );
    }

    private boolean matches(DataCandidate candidate, DataQuery query) {
        if (query.comparisonAttribute() != null) {
            return DataSelectionSupport.matchesProjection(
                    candidate.comparisonProjection(
                            query.comparisonAttribute()
                    ),
                    query.predicates()
            );
        }

        for (TextOp predicate : query.predicates()) {
            if (!matchesDefault(candidate, predicate)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesDefault(
            DataCandidate candidate,
            TextOp predicate
    ) {
        return switch (candidate.kind()) {
            case LIST -> matchesList(candidate, predicate);
            case SET -> matchesSet(candidate, predicate);
            case MAP, MULTIMAP -> matchesKeys(candidate, predicate);
            default -> DataSelectionSupport.matchesProjection(
                    candidate.value(),
                    predicate
            );
        };
    }

    private boolean matchesList(
            DataCandidate candidate,
            TextOp predicate
    ) {
        List<Object> values = candidate.associatedValues();
        return switch (predicate.op()) {
            case DEFAULT, EQUALS, STARTS_WITH ->
                    matchesBoundary(values, predicate, true);
            case ENDS_WITH ->
                    matchesBoundary(values, predicate, false);
            case CONTAINS, MATCHES ->
                    DataSelectionSupport.matchesProjection(values, predicate);
            case GT, GTE, LT, LTE -> throw new DataQueryException(
                    "Numeric List comparisons require a size or count attribute."
            );
            case HAS, HAS_NOT ->
                    DataSelectionSupport.matchesProjection(values, predicate);
        };
    }

    private boolean matchesSet(
            DataCandidate candidate,
            TextOp predicate
    ) {
        return switch (predicate.op()) {
            case STARTS_WITH, ENDS_WITH -> throw new DataQueryException(
                    "Set does not support starts-with or ends-with "
                            + "without an explicit ordered or string attribute."
            );
            case GT, GTE, LT, LTE -> throw new DataQueryException(
                    "Numeric Set comparisons require a size or count attribute."
            );
            default -> DataSelectionSupport.matchesProjection(
                    candidate.associatedValues(),
                    predicate
            );
        };
    }

    private boolean matchesKeys(
            DataCandidate candidate,
            TextOp predicate
    ) {
        if (isNumeric(predicate.op())) {
            throw new DataQueryException(
                    "Numeric " + candidate.kind().singularName()
                            + " comparisons require a size or count attribute."
            );
        }
        return DataSelectionSupport.matchesProjection(
                candidate.associatedKeys(),
                predicate
        );
    }

    private boolean matchesBoundary(
            List<Object> values,
            TextOp predicate,
            boolean first
    ) {
        if (values.isEmpty()) {
            return false;
        }
        Object value = first ? values.getFirst() : values.getLast();
        return DataSelectionSupport.matchesProjection(value, predicate);
    }

    private boolean isNumeric(ExecutionDictionary.Op op) {
        return op == ExecutionDictionary.Op.GT
                || op == ExecutionDictionary.Op.GTE
                || op == ExecutionDictionary.Op.LT
                || op == ExecutionDictionary.Op.LTE;
    }
}
