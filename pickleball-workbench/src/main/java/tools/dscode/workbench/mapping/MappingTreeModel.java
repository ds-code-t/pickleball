package tools.dscode.workbench.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured view of one worker-supplied NodeMap snapshot. Edits stay in this
 * presentation model until they are sent through {@code mappingPut} or
 * {@code mappingRestore}.
 */
public final class MappingTreeModel {
    public record Property(
            String key,
            MappingValueCodec.ValueType type,
            String text,
            Object value
    ) {
        public Property {
            key = key == null ? "" : key;
            type = type == null ? MappingValueCodec.ValueType.STRING : type;
            text = text == null ? "" : text;
        }
    }

    private final String mapReference;
    private final String mapType;
    private final boolean restorable;
    private final List<Property> properties;

    public MappingTreeModel(
            String mapReference,
            String mapType,
            boolean restorable,
            Map<String, Object> values
    ) {
        this.mapReference = mapReference == null ? "" : mapReference;
        this.mapType = mapType == null ? "" : mapType;
        this.restorable = restorable;
        this.properties = propertiesFrom(values);
    }

    public String mapReference() {
        return mapReference;
    }

    public String mapType() {
        return mapType;
    }

    public boolean restorable() {
        return restorable;
    }

    public List<Property> properties() {
        return List.copyOf(properties);
    }

    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Property property : properties) {
            values.put(property.key(), property.value());
        }
        return values;
    }

    public MappingTreeModel upsert(String key, MappingValueCodec.ValueType type, String text) {
        Objects.requireNonNull(type, "type");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Mapping property key must not be blank.");
        }
        Object value = MappingValueCodec.decode(type, text);
        List<Property> updated = new ArrayList<>();
        boolean replaced = false;
        for (Property property : properties) {
            if (property.key().equals(key)) {
                updated.add(new Property(key, type, MappingValueCodec.encode(value), value));
                replaced = true;
            } else {
                updated.add(property);
            }
        }
        if (!replaced) {
            updated.add(new Property(key, type, MappingValueCodec.encode(value), value));
        }
        return withProperties(updated);
    }

    public MappingTreeModel rename(String fromKey, String toKey) {
        if (fromKey == null || fromKey.isBlank()) {
            throw new IllegalArgumentException("Original Mapping key must not be blank.");
        }
        if (toKey == null || toKey.isBlank()) {
            throw new IllegalArgumentException("New Mapping key must not be blank.");
        }
        List<Property> updated = new ArrayList<>();
        boolean found = false;
        for (Property property : properties) {
            if (property.key().equals(fromKey)) {
                updated.add(new Property(toKey, property.type(), property.text(), property.value()));
                found = true;
            } else {
                updated.add(property);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Unknown Mapping key: " + fromKey);
        }
        return withProperties(updated);
    }

    public MappingTreeModel remove(String key) {
        List<Property> updated = new ArrayList<>();
        for (Property property : properties) {
            if (!property.key().equals(key)) updated.add(property);
        }
        return withProperties(updated);
    }

    private MappingTreeModel withProperties(List<Property> properties) {
        MappingTreeModel copy = new MappingTreeModel(mapReference, mapType, restorable, Map.of());
        copy.properties.clear();
        copy.properties.addAll(properties);
        return copy;
    }

    private static List<Property> propertiesFrom(Map<String, Object> values) {
        List<Property> properties = new ArrayList<>();
        if (values == null) return properties;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            MappingValueCodec.ValueType type = MappingValueCodec.inferType(entry.getValue());
            properties.add(new Property(
                    Objects.toString(entry.getKey(), ""),
                    type,
                    MappingValueCodec.encode(entry.getValue()),
                    entry.getValue()
            ));
        }
        return properties;
    }
}
