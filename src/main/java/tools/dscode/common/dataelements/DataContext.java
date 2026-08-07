package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import io.cucumber.datatable.DataTable;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DataContext {
    private final Object nativeSource;
    private final DataElementKind declaredKind;
    private TabularMatrix privateWorkingMatrix;
    private final Object privateWorkingValue;
    private final IdentityHashMap<Object, Object> detachedValues;
    private final DataSourceMetadata sourceMetadata;
    private final boolean sourceNull;
    private boolean modified;

    DataContext(
            Object nativeSource,
            DataElementKind declaredKind,
            TabularMatrix privateWorkingMatrix,
            DataSourceMetadata sourceMetadata
    ) {
        this.nativeSource = nativeSource;
        this.declaredKind = Objects.requireNonNull(declaredKind);
        this.privateWorkingMatrix =
                Objects.requireNonNull(privateWorkingMatrix);
        this.sourceMetadata = Objects.requireNonNull(sourceMetadata);
        this.sourceNull = nativeSource == null;
        this.detachedValues = new IdentityHashMap<>();
        this.privateWorkingValue = detach(
                nativeSource,
                declaredKind,
                detachedValues
        );
    }

    public Object nativeSource() {
        return nativeSource;
    }

    public DataElementKind declaredKind() {
        return declaredKind;
    }

    public TabularMatrix workingMatrix() {
        return privateWorkingMatrix;
    }

    public Object workingValue() {
        return modified ? privateWorkingValue : nativeSource;
    }

    Object currentValue(Object originalValue) {
        if (!modified || originalValue == null) {
            return originalValue;
        }
        return detachedValues.getOrDefault(
                originalValue,
                originalValue
        );
    }

    public DataSourceMetadata sourceMetadata() {
        return sourceMetadata;
    }

    public boolean sourceNull() {
        return sourceNull;
    }

    public boolean modified() {
        return modified;
    }

    public void replaceCell(
            DataCoordinate coordinate,
            Object value
    ) {
        Objects.requireNonNull(coordinate, "coordinate");
        if (!privateWorkingMatrix.hasPhysicalCell(
                coordinate.row(),
                coordinate.column()
        )) {
            throw new DataQueryException(
                    "Structural table mutation is not supported. "
                            + "The target cell must already exist."
            );
        }
        privateWorkingMatrix = privateWorkingMatrix.withCell(
                coordinate.row(),
                coordinate.column(),
                value
        );
        modified = true;
    }

    public void replaceListItem(
            Object sourceList,
            int index,
            Object value
    ) {
        List<Object> list = mutableList(sourceList);
        requireExistingIndex(list.size(), index, "List");
        list.set(index, value);
        modified = true;
    }

    public void replaceMapValue(
            Object sourceMap,
            Object key,
            Object value
    ) {
        Map<Object, Object> map = mutableMap(sourceMap);
        if (!map.containsKey(key)) {
            throw new DataQueryException(
                    "Structural Map mutation is not supported. "
                            + "The key must already exist: " + key
            );
        }
        map.put(key, value);
        modified = true;
    }

    public void replaceMultimapValue(
            Object sourceMultimap,
            Object key,
            int occurrence,
            Object value
    ) {
        Multimap<Object, Object> multimap =
                mutableMultimap(sourceMultimap);
        List<Map.Entry<Object, Object>> entries =
                new ArrayList<>(multimap.entries());

        int matchingIndex = -1;
        int seen = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (!Objects.equals(entries.get(index).getKey(), key)) {
                continue;
            }
            if (seen++ == occurrence) {
                matchingIndex = index;
                break;
            }
        }
        if (matchingIndex < 0) {
            throw new DataQueryException(
                    "Structural Multimap mutation is not supported. "
                            + "The requested key occurrence must already "
                            + "exist: " + key + "[" + occurrence + "]"
            );
        }

        multimap.clear();
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<Object, Object> entry = entries.get(index);
            multimap.put(
                    entry.getKey(),
                    index == matchingIndex ? value : entry.getValue()
            );
        }
        modified = true;
    }

    public Object materializeDeclaredType() {
        if (!modified && nativeSource != null) {
            switch (declaredKind) {
                case DATA_TABLE -> {
                    if (nativeSource instanceof DataTable) {
                        return nativeSource;
                    }
                }
                case DATA_LIST -> {
                    if (nativeSource instanceof List<?>) {
                        return nativeSource;
                    }
                }
                case MAP, LIST, SET, MULTIMAP -> {
                    return nativeSource;
                }
                default -> {
                }
            }
        }
        return materializeWorkingValue();
    }

    public Object convertTo(DataElementKind requestedKind) {
        return switch (requestedKind) {
            case DATA_TABLE ->
                    DataMaterializer.toDataTable(privateWorkingMatrix);
            case DATA_LIST -> privateWorkingMatrix.isEmpty()
                    ? List.of()
                    : privateWorkingMatrix.physicalValues(0);
            default -> throw new IllegalArgumentException(
                    "Tabular DataContext conversion is not implemented for "
                            + requestedKind.singularName()
            );
        };
    }

    private Object materializeWorkingValue() {
        return switch (declaredKind) {
            case DATA_TABLE ->
                    DataMaterializer.toDataTable(privateWorkingMatrix);
            case DATA_LIST -> privateWorkingMatrix.isEmpty()
                    ? List.of()
                    : privateWorkingMatrix.physicalValues(0);
            case MAP, LIST, SET, MULTIMAP -> privateWorkingValue;
            default -> privateWorkingValue != null
                    ? privateWorkingValue
                    : privateWorkingMatrix;
        };
    }

    @SuppressWarnings("unchecked")
    private List<Object> mutableList(Object sourceList) {
        Object detached = mutableTarget(sourceList);
        if (!(detached instanceof List<?>)) {
            throw new DataQueryException(
                    "The selected value is not a mutable List cursor."
            );
        }
        return (List<Object>) detached;
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> mutableMap(Object sourceMap) {
        Object detached = mutableTarget(sourceMap);
        if (!(detached instanceof Map<?, ?>)) {
            throw new DataQueryException(
                    "The selected value is not a mutable Map cursor."
            );
        }
        return (Map<Object, Object>) detached;
    }

    @SuppressWarnings("unchecked")
    private Multimap<Object, Object> mutableMultimap(
            Object sourceMultimap
    ) {
        Object detached = mutableTarget(sourceMultimap);
        if (!(detached instanceof Multimap<?, ?>)) {
            throw new DataQueryException(
                    "The selected value is not a mutable Multimap cursor."
            );
        }
        return (Multimap<Object, Object>) detached;
    }

    private Object mutableTarget(Object sourceValue) {
        if (sourceValue == privateWorkingValue) {
            return sourceValue;
        }
        Object detached = detachedValues.get(sourceValue);
        if (detached == null) {
            throw new DataQueryException(
                    "The selected value is not owned by this DataContext."
            );
        }
        return detached;
    }

    private static void requireExistingIndex(
            int size,
            int index,
            String type
    ) {
        if (index < 0 || index >= size) {
            throw new DataQueryException(
                    "Structural " + type + " mutation is not supported. "
                            + "The index must already exist: " + index
            );
        }
    }

    private static Object detach(
            Object source,
            DataElementKind declaredKind,
            IdentityHashMap<Object, Object> detached
    ) {
        if (source == null) {
            return null;
        }
        if (declaredKind == DataElementKind.MULTIMAP
                && source instanceof Map<?, ?> map) {
            LinkedListMultimap<Object, Object> copy =
                    LinkedListMultimap.create();
            detached.put(source, copy);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                for (Object item : collectionValues(entry.getValue())) {
                    copy.put(entry.getKey(), detach(item, null, detached));
                }
            }
            return copy;
        }
        return detach(source, detached);
    }

    private static Object detach(
            Object source,
            IdentityHashMap<Object, Object> detached
    ) {
        if (source == null) {
            return null;
        }
        Object existing = detached.get(source);
        if (existing != null) {
            return existing;
        }
        if (source instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            detached.put(source, copy);
            list.forEach(value -> copy.add(detach(value, detached)));
            return copy;
        }
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            detached.put(source, copy);
            map.forEach((key, value) ->
                    copy.put(key, detach(value, detached))
            );
            return copy;
        }
        if (source instanceof Multimap<?, ?> multimap) {
            LinkedListMultimap<Object, Object> copy =
                    LinkedListMultimap.create();
            detached.put(source, copy);
            multimap.entries().forEach(entry -> copy.put(
                    entry.getKey(),
                    detach(entry.getValue(), detached)
            ));
            return copy;
        }
        if (source instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            detached.put(source, copy);
            set.forEach(value -> copy.add(detach(value, detached)));
            return copy;
        }
        if (source instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            detached.put(source, copy);
            collection.forEach(value ->
                    copy.add(detach(value, detached))
            );
            return copy;
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            List<Object> copy = new ArrayList<>(length);
            detached.put(source, copy);
            for (int index = 0; index < length; index++) {
                copy.add(detach(Array.get(source, index), detached));
            }
            return copy;
        }
        if (source instanceof JsonNode jsonNode) {
            JsonNode copy = jsonNode.deepCopy();
            detached.put(source, copy);
            return copy;
        }
        return source;
    }

    private static List<?> collectionValues(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(value, index));
            }
            return values;
        }
        throw new DataQueryException(
                "A Map can be adapted to Multimap only when every value "
                        + "is a collection or array."
        );
    }
}
