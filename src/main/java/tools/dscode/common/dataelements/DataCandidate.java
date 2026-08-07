package tools.dscode.common.dataelements;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DataCandidate {
    private final DataElementKind kind;
    private final DataContext context;
    private final Object value;
    private final Object key;
    private final List<DataEntryValue> entries;
    private final DataCoordinate coordinate;

    private DataCandidate(
            DataElementKind kind,
            DataContext context,
            Object value,
            Object key,
            List<DataEntryValue> entries,
            DataCoordinate coordinate
    ) {
        this.kind = Objects.requireNonNull(kind);
        this.context = Objects.requireNonNull(context);
        this.value = value;
        this.key = key;
        this.entries = List.copyOf(
                entries == null ? List.of() : entries
        );
        this.coordinate = coordinate;
    }

    public static DataCandidate table(DataContext context) {
        return new DataCandidate(
                DataElementKind.DATA_TABLE,
                context,
                context.nativeSource(),
                null,
                List.of(),
                null
        );
    }

    public static DataCandidate structured(
            DataElementKind kind,
            DataContext context,
            Object value,
            Object key,
            List<DataEntryValue> entries,
            DataCoordinate coordinate
    ) {
        return new DataCandidate(
                kind,
                context,
                value,
                key,
                entries,
                coordinate
        );
    }

    public static DataCandidate scalar(
            DataElementKind kind,
            DataContext context,
            Object value,
            DataCoordinate coordinate
    ) {
        return new DataCandidate(
                kind,
                context,
                value,
                value,
                List.of(),
                coordinate
        );
    }

    public DataElementKind kind() {
        return kind;
    }

    public DataContext context() {
        return context;
    }

    public Object value() {
        return value;
    }

    public Object key() {
        return key;
    }

    public List<DataEntryValue> entries() {
        return entries;
    }

    public DataCoordinate coordinate() {
        return coordinate;
    }

    public Object comparisonProjection(DataAttribute attribute) {
        if (attribute == null) {
            return defaultComparisonProjection();
        }
        return attributeProjection(attribute);
    }

    public Object returnProjection(DataAttribute attribute) {
        return attribute == null
                ? DataMaterializer.materialize(this)
                : attributeProjection(attribute);
    }

    private Object defaultComparisonProjection() {
        return switch (kind) {
            case DATA_ROW,
                 DATA_COLUMN,
                 DATA_LIST,
                 DATA_COLUMN_LIST,
                 DATA_ENTRY -> key;
            case DATA_CELL,
                 DATA_HEADER,
                 DATA_VALUE -> value;
            default -> value;
        };
    }

    private Object attributeProjection(DataAttribute attribute) {
        return switch (attribute) {
            case VALUE -> kind == DataElementKind.DATA_ENTRY
                    ? value
                    : value;
            case STRING -> DataStringFormatter.format(this);
            case KEY -> key;
            case VALUES -> associatedValues();
            case SIZE -> size();
            case COUNT -> count();
            case FIRST -> first();
            case LAST -> last();
            case TYPE -> kind.singularName();
        };
    }

    private List<Object> associatedValues() {
        if (!entries.isEmpty()) {
            List<Object> values = new ArrayList<>(entries.size());
            entries.forEach(entry -> values.add(entry.value()));
            return Collections.unmodifiableList(values);
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(
                    new ArrayList<>(collection)
            );
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(value, index));
            }
            return Collections.unmodifiableList(values);
        }
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableList(
                    new ArrayList<>(map.values())
            );
        }
        return Collections.singletonList(value);
    }

    private int size() {
        if (!entries.isEmpty()) {
            return entries.size();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return value == null ? 0 : 1;
    }

    private int count() {
        return entries.isEmpty() ? size() : entries.size();
    }

    private Object first() {
        List<Object> values = associatedValues();
        return values.isEmpty() ? null : values.getFirst();
    }

    private Object last() {
        List<Object> values = associatedValues();
        return values.isEmpty() ? null : values.getLast();
    }
}
