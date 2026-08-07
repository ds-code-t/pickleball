package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class FormatQueryEngine {
    public DataSelection query(DataContext context, DataQuery query) {
        if (context == null) {
            throw new IllegalArgumentException("DataContext cannot be null");
        }
        if (query == null) {
            throw new IllegalArgumentException("DataQuery cannot be null");
        }

        List<DataCandidate> projected = context.sourceNull()
                ? List.of()
                : project(context, query);
        return DataSelectionSupport.finish(
                context,
                query,
                projected,
                this::matches
        );
    }

    private List<DataCandidate> project(
            DataContext context,
            DataQuery query
    ) {
        List<Object> sources = query.form() == DataElementForm.PLURAL
                ? directValues(context.nativeSource())
                : List.of(context.nativeSource());

        if (sources.isEmpty() && query.form() == DataElementForm.PLURAL) {
            return List.of();
        }

        List<DataCandidate> candidates = new ArrayList<>(sources.size());
        for (Object source : sources) {
            Object converted = StructuredDataConverter.convert(
                    source,
                    query.kind()
            );
            candidates.add(DataCandidate.scalar(
                    query.kind(),
                    context,
                    converted,
                    null
            ));
        }
        return candidates;
    }

    private boolean matches(DataCandidate candidate, DataQuery query) {
        Object projection = candidate.comparisonProjection(
                query.comparisonAttribute()
        );
        return DataSelectionSupport.matchesProjection(
                projection,
                query.predicates()
        );
    }

    private List<Object> directValues(Object source) {
        if (source instanceof ArrayNode arrayNode) {
            List<Object> values = new ArrayList<>(arrayNode.size());
            arrayNode.forEach(values::add);
            return values;
        }
        if (source instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (source != null && source.getClass().isArray()) {
            int length = Array.getLength(source);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(source, index));
            }
            return values;
        }
        return List.of(source);
    }
}
