package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CollectionDataAdapter {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private CollectionDataAdapter() {
    }

    static List<DataCandidate> project(
            DataContext context,
            DataElementKind kind
    ) {
        return switch (kind) {
            case MAP -> mapCandidates(context);
            case LIST -> listCandidates(context);
            case SET -> setCandidates(context);
            case MULTIMAP -> multimapCandidates(context);
            default -> throw new DataQueryException(
                    "Java collection projection is not implemented for "
                            + kind.singularName() + "."
            );
        };
    }

    private static List<DataCandidate> mapCandidates(DataContext context) {
        Object source = context.workingValue();
        if (source instanceof Map<?, ?> map) {
            return List.of(mapCandidate(context, map));
        }
        if (source instanceof ObjectNode objectNode) {
            return List.of(mapCandidate(context, toMap(objectNode)));
        }

        List<DataCandidate> candidates = new ArrayList<>();
        for (Object item : directValues(source)) {
            if (item instanceof Map<?, ?> map) {
                candidates.add(mapCandidate(context, map));
            } else if (item instanceof ObjectNode objectNode) {
                candidates.add(mapCandidate(context, toMap(objectNode)));
            }
        }
        return candidates;
    }

    private static List<DataCandidate> listCandidates(DataContext context) {
        Object source = context.workingValue();

        if (source instanceof ArrayNode arrayNode) {
            List<Object> values = jsonArrayValues(arrayNode);
            if (containsListLike(values)) {
                return listChildren(context, values);
            }
            return List.of(listCandidate(context, values));
        }

        if (source instanceof List<?> list) {
            if (containsListLike(list)) {
                return listChildren(context, list);
            }
            return List.of(listCandidate(context, list));
        }

        if (source != null && source.getClass().isArray()) {
            List<Object> values = arrayValues(source);
            if (containsListLike(values)) {
                return listChildren(context, values);
            }
            return List.of(listCandidate(context, values));
        }

        return List.of();
    }

    private static List<DataCandidate> setCandidates(DataContext context) {
        Object source = context.workingValue();
        if (source instanceof Set<?> set) {
            List<Object> values = new ArrayList<>(set);
            if (containsSets(values)) {
                return setChildren(context, values);
            }
            return List.of(setCandidate(context, set));
        }

        List<DataCandidate> candidates = new ArrayList<>();
        for (Object item : directValues(source)) {
            if (item instanceof Set<?> set) {
                candidates.add(setCandidate(context, set));
            }
        }
        return candidates;
    }

    private static List<DataCandidate> multimapCandidates(DataContext context) {
        Object source = context.workingValue();
        if (source instanceof Multimap<?, ?> multimap) {
            return List.of(multimapCandidate(context, multimap));
        }
        if (source instanceof Map<?, ?> map) {
            Multimap<Object, Object> converted = toMultimap(map);
            return converted == null
                    ? List.of()
                    : List.of(multimapCandidate(context, converted));
        }
        if (source instanceof ObjectNode objectNode) {
            Multimap<Object, Object> converted = toMultimap(toMap(objectNode));
            return converted == null
                    ? List.of()
                    : List.of(multimapCandidate(context, converted));
        }

        List<DataCandidate> candidates = new ArrayList<>();
        for (Object item : directValues(source)) {
            if (item instanceof Multimap<?, ?> multimap) {
                candidates.add(multimapCandidate(context, multimap));
            }
        }
        return candidates;
    }

    private static DataCandidate mapCandidate(
            DataContext context,
            Map<?, ?> map
    ) {
        List<DataEntryValue> entries = new ArrayList<>(map.size());
        map.forEach((key, value) -> entries.add(
                new DataEntryValue(key, value, null, false)
        ));
        return DataCandidate.structured(
                DataElementKind.MAP,
                context,
                map,
                null,
                entries,
                null
        );
    }

    private static DataCandidate listCandidate(
            DataContext context,
            List<?> list
    ) {
        return DataCandidate.structured(
                DataElementKind.LIST,
                context,
                list,
                null,
                List.of(),
                null
        );
    }

    private static DataCandidate setCandidate(
            DataContext context,
            Set<?> set
    ) {
        return DataCandidate.structured(
                DataElementKind.SET,
                context,
                set,
                null,
                List.of(),
                null
        );
    }

    private static DataCandidate multimapCandidate(
            DataContext context,
            Multimap<?, ?> multimap
    ) {
        List<DataEntryValue> entries = new ArrayList<>(multimap.size());
        multimap.entries().forEach(entry -> entries.add(
                new DataEntryValue(
                        entry.getKey(),
                        entry.getValue(),
                        null,
                        false
                )
        ));
        return DataCandidate.structured(
                DataElementKind.MULTIMAP,
                context,
                multimap,
                null,
                entries,
                null
        );
    }

    private static List<DataCandidate> listChildren(
            DataContext context,
            Collection<?> values
    ) {
        List<DataCandidate> candidates = new ArrayList<>();
        for (Object value : values) {
            List<?> list = toList(value);
            if (list != null) {
                candidates.add(listCandidate(context, list));
            }
        }
        return candidates;
    }

    private static List<DataCandidate> setChildren(
            DataContext context,
            Collection<?> values
    ) {
        List<DataCandidate> candidates = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Set<?> set) {
                candidates.add(setCandidate(context, set));
            }
        }
        return candidates;
    }

    private static boolean containsListLike(Collection<?> values) {
        return values.stream().anyMatch(CollectionDataAdapter::isListLike);
    }

    private static boolean containsSets(Collection<?> values) {
        return values.stream().anyMatch(Set.class::isInstance);
    }

    private static boolean isListLike(Object value) {
        return value instanceof List<?>
                || value instanceof ArrayNode
                || value != null && value.getClass().isArray();
    }

    private static List<?> toList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof ArrayNode arrayNode) {
            return jsonArrayValues(arrayNode);
        }
        if (value != null && value.getClass().isArray()) {
            return arrayValues(value);
        }
        return null;
    }

    private static List<Object> directValues(Object source) {
        if (source instanceof ArrayNode arrayNode) {
            return jsonArrayValues(arrayNode);
        }
        if (source instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (source != null && source.getClass().isArray()) {
            return arrayValues(source);
        }
        return List.of();
    }

    private static List<Object> jsonArrayValues(ArrayNode arrayNode) {
        List<Object> values = new ArrayList<>(arrayNode.size());
        arrayNode.forEach(value -> values.add(jsonValue(value)));
        return values;
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return MAPPER.convertValue(node, Object.class);
    }

    private static List<Object> arrayValues(Object array) {
        int length = Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(Array.get(array, index));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> toMap(ObjectNode objectNode) {
        Map<String, Object> converted = MAPPER.convertValue(
                objectNode,
                LinkedHashMap.class
        );
        return new LinkedHashMap<>(converted);
    }

    private static Multimap<Object, Object> toMultimap(Map<?, ?> source) {
        LinkedListMultimap<Object, Object> result =
                LinkedListMultimap.create();

        for (Map.Entry<?, ?> entry : source.entrySet()) {
            List<?> values = multimapValues(entry.getValue());
            if (values == null) {
                return null;
            }
            values.forEach(value -> result.put(entry.getKey(), value));
        }
        return result;
    }

    private static List<?> multimapValues(Object value) {
        if (value instanceof ArrayNode arrayNode) {
            return jsonArrayValues(arrayNode);
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            return arrayValues(value);
        }
        return null;
    }
}
