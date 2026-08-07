package tools.dscode.common.dataelements;

import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.dataoperations.TextPredicateMatcher;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class DataQueryEngine {

    public DataSelection query(DataContext context, DataQuery query) {
        if (context == null) {
            throw new IllegalArgumentException("DataContext cannot be null");
        }
        if (query == null) {
            throw new IllegalArgumentException("DataQuery cannot be null");
        }

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

        List<DataCandidate> projected = project(context, query.kind());
        List<DataCandidate> filtered = filter(projected, query);
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

    private List<DataCandidate> project(
            DataContext context,
            DataElementKind kind
    ) {
        return switch (kind) {
            case DATA_TABLE -> List.of(DataCandidate.table(context));
            case DATA_ROW -> rows(context);
            case DATA_COLUMN -> columns(context);
            case DATA_LIST -> lists(context);
            case DATA_COLUMN_LIST -> columnLists(context);
            case DATA_CELL -> cells(context);
            case DATA_ENTRY -> entries(context);
            case DATA_HEADER -> headers(context);
            case DATA_VALUE -> values(context);
            default -> throw new DataQueryException(
                    "Read-only tabular projection is not implemented for "
                            + kind.singularName()
                            + ". Context type: "
                            + context.declaredKind().singularName()
                            + "."
            );
        };
    }

    private List<DataCandidate> rows(DataContext context) {
        TabularMatrix matrix = context.workingMatrix();
        if (matrix.rowCount() < 2) {
            return List.of();
        }

        List<DataCandidate> candidates =
                new ArrayList<>(matrix.rowCount() - 1);
        for (int row = 1; row < matrix.rowCount(); row++) {
            List<DataEntryValue> entries = rowEntries(matrix, row);
            Object key = matrix.hasPhysicalCell(row, 0)
                    ? matrix.cell(row, 0).externalValue()
                    : null;
            candidates.add(DataCandidate.structured(
                    DataElementKind.DATA_ROW,
                    context,
                    matrix.physicalValues(row),
                    key,
                    entries,
                    new DataCoordinate(row, 0)
            ));
        }
        return candidates;
    }

    private List<DataCandidate> columns(DataContext context) {
        TabularMatrix matrix = context.workingMatrix();
        if (matrix.rowCount() == 0 || matrix.width() < 2) {
            return List.of();
        }

        List<DataCandidate> candidates =
                new ArrayList<>(matrix.width() - 1);
        for (int column = 1; column < matrix.width(); column++) {
            List<DataEntryValue> entries =
                    new ArrayList<>(matrix.rowCount());
            List<Object> columnValues =
                    new ArrayList<>(matrix.rowCount());

            for (int row = 0; row < matrix.rowCount(); row++) {
                TabularCell keyCell = matrix.cell(row, 0);
                TabularCell valueCell = matrix.cell(row, column);
                Object key = keyCell.missing()
                        ? ""
                        : keyCell.externalValue();
                entries.add(new DataEntryValue(
                        key,
                        valueCell.externalValue(),
                        new DataCoordinate(row, column),
                        valueCell.missing()
                ));
                columnValues.add(valueCell.externalValue());
            }

            Object key = matrix.cell(0, column).externalValue();
            candidates.add(DataCandidate.structured(
                    DataElementKind.DATA_COLUMN,
                    context,
                    Collections.unmodifiableList(columnValues),
                    key,
                    entries,
                    new DataCoordinate(0, column)
            ));
        }
        return candidates;
    }

    private List<DataCandidate> lists(DataContext context) {
        TabularMatrix matrix = context.workingMatrix();
        List<DataCandidate> candidates =
                new ArrayList<>(matrix.rowCount());

        for (int row = 0; row < matrix.rowCount(); row++) {
            List<Object> values = matrix.physicalValues(row);
            Object key = values.isEmpty() ? null : values.getFirst();
            candidates.add(DataCandidate.structured(
                    DataElementKind.DATA_LIST,
                    context,
                    values,
                    key,
                    List.of(),
                    new DataCoordinate(row, 0)
            ));
        }
        return candidates;
    }

    private List<DataCandidate> columnLists(DataContext context) {
        TabularMatrix matrix = context.workingMatrix();
        if (matrix.rowCount() == 0) {
            return List.of();
        }

        List<DataCandidate> candidates =
                new ArrayList<>(matrix.width());
        for (int column = 0; column < matrix.width(); column++) {
            List<Object> values = new ArrayList<>(matrix.rowCount());
            for (int row = 0; row < matrix.rowCount(); row++) {
                values.add(matrix.cell(row, column).externalValue());
            }
            Object key = values.isEmpty() ? null : values.getFirst();
            candidates.add(DataCandidate.structured(
                    DataElementKind.DATA_COLUMN_LIST,
                    context,
                    Collections.unmodifiableList(values),
                    key,
                    List.of(),
                    new DataCoordinate(0, column)
            ));
        }
        return candidates;
    }

    private List<DataCandidate> cells(DataContext context) {
        TabularMatrix matrix = context.workingMatrix();
        List<DataCandidate> candidates = new ArrayList<>();

        for (int row = 0; row < matrix.rowCount(); row++) {
            for (int column = 0;
                 column < matrix.physicalRowLength(row);
                 column++) {
                candidates.add(DataCandidate.scalar(
                        DataElementKind.DATA_CELL,
                        context,
                        matrix.cell(row, column).externalValue(),
                        new DataCoordinate(row, column)
                ));
            }
        }
        return candidates;
    }

    private List<DataCandidate> entries(DataContext context) {
        TabularMatrix matrix = context.workingMatrix();
        if (matrix.rowCount() < 2) {
            return List.of();
        }

        List<DataCandidate> candidates = new ArrayList<>();
        for (int row = 1; row < matrix.rowCount(); row++) {
            for (int column = 0;
                 column < matrix.physicalRowLength(row);
                 column++) {
                Object key = matrix.hasPhysicalCell(0, column)
                        ? matrix.cell(0, column).externalValue()
                        : "";
                Object value = matrix.cell(row, column).externalValue();
                DataCoordinate coordinate = new DataCoordinate(row, column);
                DataEntryValue entry = new DataEntryValue(
                        key,
                        value,
                        coordinate,
                        false
                );
                candidates.add(DataCandidate.structured(
                        DataElementKind.DATA_ENTRY,
                        context,
                        value,
                        key,
                        List.of(entry),
                        coordinate
                ));
            }
        }
        return candidates;
    }

    private List<DataCandidate> headers(DataContext context) {
        List<DataCandidate> headers = new ArrayList<>();
        for (DataCandidate entry : entries(context)) {
            headers.add(DataCandidate.scalar(
                    DataElementKind.DATA_HEADER,
                    context,
                    entry.key(),
                    entry.coordinate()
            ));
        }
        return headers;
    }

    private List<DataCandidate> values(DataContext context) {
        List<DataCandidate> values = new ArrayList<>();
        for (DataCandidate entry : entries(context)) {
            values.add(DataCandidate.scalar(
                    DataElementKind.DATA_VALUE,
                    context,
                    entry.value(),
                    entry.coordinate()
            ));
        }
        return values;
    }

    private List<DataEntryValue> rowEntries(
            TabularMatrix matrix,
            int row
    ) {
        List<DataEntryValue> entries =
                new ArrayList<>(matrix.width());
        for (int column = 0; column < matrix.width(); column++) {
            TabularCell keyCell = matrix.cell(0, column);
            TabularCell valueCell = matrix.cell(row, column);
            Object key = keyCell.missing()
                    ? ""
                    : keyCell.externalValue();
            entries.add(new DataEntryValue(
                    key,
                    valueCell.externalValue(),
                    new DataCoordinate(row, column),
                    valueCell.missing()
            ));
        }
        return entries;
    }

    private List<DataCandidate> filter(
            List<DataCandidate> candidates,
            DataQuery query
    ) {
        if (query.predicates().isEmpty()) {
            return candidates;
        }

        List<DataCandidate> filtered = new ArrayList<>();
        for (DataCandidate candidate : candidates) {
            Object projection = candidate.comparisonProjection(
                    query.comparisonAttribute()
            );
            if (matchesAll(projection, query.predicates())) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private boolean matchesAll(
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

    private boolean matchesProjection(
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

    private List<DataCandidate> select(
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

    private void enforceCardinality(
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
                            + query.kind().pluralName() + "."
            );
        }

        if (filteredCount == 0) {
            throw new DataQueryException(
                    "No " + query.kind().pluralName()
                            + " matched: " + describe(query) + ". "
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
                "No " + query.kind().pluralName()
                        + " were selected from context type "
                        + contextType + "."
        );
    }

    private String requestedName(DataQuery query) {
        if (query.cardinality().many()) {
            return query.kind().pluralName();
        }
        return query.kind().singularName();
    }

    private String describe(DataQuery query) {
        StringBuilder description =
                new StringBuilder(query.kind().singularName());
        if (!query.predicates().isEmpty()) {
            description.append(" with ")
                    .append(query.predicates().size())
                    .append(" predicate");
            if (query.predicates().size() != 1) {
                description.append('s');
            }
        }
        return description.toString();
    }

    private String ordinal(int value) {
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
