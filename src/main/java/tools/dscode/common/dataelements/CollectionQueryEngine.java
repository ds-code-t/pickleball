package tools.dscode.common.dataelements;

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
        if (query.predicates().isEmpty()) {
            return true;
        }
        if (query.comparisonAttribute() == null) {
            throw new DataQueryException(
                    query.kind().singularName()
                            + " predicates require an explicit comparison attribute. "
                            + "Use syntax such as 'with key equaling ...', "
                            + "'with value equaling ...', 'with values containing ...', "
                            + "'with first equaling ...', 'with last equaling ...', "
                            + "or 'with size equaling ...'."
            );
        }
        return DataSelectionSupport.matchesProjection(
                candidate.comparisonProjection(query.comparisonAttribute()),
                query.predicates()
        );
    }
}
