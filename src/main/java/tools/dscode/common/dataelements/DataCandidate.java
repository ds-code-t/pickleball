package tools.dscode.common.dataelements;

import com.google.common.collect.Multimap;

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

    public Object currentValue() {
        if (coordinate != null) {
            TabularMatrix matrix = context.workingMatrix();
            if (kind == DataElementKind.DATA_HEADER) {
                return matrix.cell(
                        0,
                        coordinate.column()
                ).externalValue();
            }
            if (kind == DataElementKind.DATA_CELL
                    || kind == DataElementKind.DATA_ENTRY
                    || kind == DataElementKind.DATA_VALUE) {
                return matrix.cell(
                        coordinate.row(),
                        coordinate.column()
                ).externalValue();
            }
        }
        return context.currentValue(value);
    }

    public Object currentKey() {
        if (kind == DataElementKind.DATA_ENTRY
                && coordinate != null) {
            return context.workingMatrix().cell(
                    0,
                    coordinate.column()
            ).externalValue();
        }
        return key;
    }

    public List<DataEntryValue> currentEntries() {
        if (kind == DataElementKind.MAP
                && currentValue() instanceof Map<?, ?> map) {
            List<DataEntryValue> current =
                    new ArrayList<>(map.size());
            map.forEach((entryKey, entryValue) ->
                    current.add(new DataEntryValue(
                            entryKey,
                            entryValue,
                            null,
                            false
                    ))
            );
            return Collections.unmodifiableList(current);
        }
        if (kind == DataElementKind.MULTIMAP
                && currentValue() instanceof Multimap<?, ?> multimap) {
            List<DataEntryValue> current =
                    new ArrayList<>(multimap.size());
            multimap.entries().forEach(entry ->
                    current.add(new DataEntryValue(
                            entry.getKey(),
                            entry.getValue(),
                            null,
                            false
                    ))
            );
            return Collections.unmodifiableList(current);
        }
        if (entries.isEmpty()) {
            return entries;
        }

        TabularMatrix matrix = context.workingMatrix();
        List<DataEntryValue> current =
                new ArrayList<>(entries.size());
        for (DataEntryValue entry : entries) {
            DataCoordinate entryCoordinate = entry.coordinate();
            if (entryCoordinate == null) {
                current.add(entry);
                continue;
            }
            Object entryKey = switch (kind) {
                case DATA_ROW -> matrix.cell(
                        0,
                        entryCoordinate.column()
                ).externalValue();
                case DATA_COLUMN -> matrix.cell(
                        entryCoordinate.row(),
                        0
                ).externalValue();
                case DATA_ENTRY -> matrix.cell(
                        0,
                        entryCoordinate.column()
                ).externalValue();
                default -> entry.key();
            };
            TabularCell cell = matrix.cell(
                    entryCoordinate.row(),
                    entryCoordinate.column()
            );
            current.add(new DataEntryValue(
                    entryKey,
                    cell.externalValue(),
                    entryCoordinate,
                    cell.missing()
            ));
        }
        return Collections.unmodifiableList(current);
    }

    public List<?> currentListValue() {
        if (kind == DataElementKind.DATA_LIST
                && coordinate != null) {
            return context.workingMatrix()
                    .physicalValues(coordinate.row());
        }
        if (kind == DataElementKind.DATA_COLUMN_LIST
                && coordinate != null) {
            TabularMatrix matrix = context.workingMatrix();
            List<Object> values =
                    new ArrayList<>(matrix.rowCount());
            for (int row = 0; row < matrix.rowCount(); row++) {
                values.add(matrix.cell(
                        row,
                        coordinate.column()
                ).externalValue());
            }
            return Collections.unmodifiableList(values);
        }
        Object current = currentValue();
        return current instanceof List<?> list
                ? list
                : List.of();
    }

    public Object comparisonProjection(DataAttribute attribute) {
        return attribute == null
                ? defaultComparisonProjection()
                : attributeProjection(attribute);
    }

    public Object returnProjection(DataAttribute attribute) {
        return attribute == null
                ? DataMaterializer.materialize(this)
                : attributeProjection(attribute);
    }

    List<Object> associatedKeys() {
        List<DataEntryValue> currentEntries = currentEntries();
        if (!currentEntries.isEmpty()) {
            List<Object> keys =
                    new ArrayList<>(currentEntries.size());
            currentEntries.forEach(entry -> keys.add(entry.key()));
            return Collections.unmodifiableList(keys);
        }
        Object current = currentValue();
        if (current instanceof Map<?, ?> map) {
            return Collections.unmodifiableList(
                    new ArrayList<>(map.keySet())
            );
        }
        if (current instanceof Multimap<?, ?> multimap) {
            return Collections.unmodifiableList(
                    new ArrayList<>(multimap.keys())
            );
        }
        Object currentKey = currentKey();
        return currentKey == null
                ? List.of()
                : Collections.singletonList(currentKey);
    }

    List<Object> associatedValues() {
        List<DataEntryValue> currentEntries = currentEntries();
        if (!currentEntries.isEmpty()) {
            List<Object> values =
                    new ArrayList<>(currentEntries.size());
            currentEntries.forEach(entry ->
                    values.add(entry.value())
            );
            return Collections.unmodifiableList(values);
        }
        Object current = currentValue();
        if (current instanceof Multimap<?, ?> multimap) {
            return Collections.unmodifiableList(
                    new ArrayList<>(multimap.values())
            );
        }
        if (current instanceof Collection<?> collection) {
            return Collections.unmodifiableList(
                    new ArrayList<>(collection)
            );
        }
        if (current != null && current.getClass().isArray()) {
            int length = Array.getLength(current);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(current, index));
            }
            return Collections.unmodifiableList(values);
        }
        if (current instanceof Map<?, ?> map) {
            return Collections.unmodifiableList(
                    new ArrayList<>(map.values())
            );
        }
        return Collections.singletonList(current);
    }

    private Object defaultComparisonProjection() {
        return switch (kind) {
            case DATA_ROW,
                 DATA_COLUMN,
                 DATA_LIST,
                 DATA_COLUMN_LIST,
                 DATA_ENTRY -> currentKey();
            case DATA_CELL,
                 DATA_HEADER,
                 DATA_VALUE -> currentValue();
            case MAP, MULTIMAP -> associatedKeys();
            case SET -> associatedValues();
            default -> currentValue();
        };
    }

    private Object attributeProjection(DataAttribute attribute) {
        return switch (attribute) {
            case VALUE -> switch (kind) {
                case MAP, MULTIMAP -> associatedValues();
                default -> currentValue();
            };
            case STRING -> DataStringFormatter.format(this);
            case KEY -> switch (kind) {
                case MAP, MULTIMAP -> associatedKeys();
                default -> currentKey();
            };
            case VALUES -> associatedValues();
            case SIZE -> size();
            case COUNT -> count();
            case FIRST -> first();
            case LAST -> last();
            case TYPE -> kind.singularName();
        };
    }

    private int size() {
        List<DataEntryValue> currentEntries = currentEntries();
        if (!currentEntries.isEmpty()) {
            return currentEntries.size();
        }
        Object current = currentValue();
        if (current instanceof Multimap<?, ?> multimap) {
            return multimap.size();
        }
        if (current instanceof Collection<?> collection) {
            return collection.size();
        }
        if (current instanceof Map<?, ?> map) {
            return map.size();
        }
        if (current != null && current.getClass().isArray()) {
            return Array.getLength(current);
        }
        return current == null ? 0 : 1;
    }

    private int count() {
        return currentEntries().isEmpty()
                ? size()
                : currentEntries().size();
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
