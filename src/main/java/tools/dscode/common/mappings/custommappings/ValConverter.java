package tools.dscode.common.mappings.custommappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.mappings.ParsingMap.MappingDirectiveResolver;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public class ValConverter extends CustomReader {
    private static final Set<String> REMOVED_BARE_VALUE_MARKERS = Set.of(
            "^~NULL~^",
            "^~NAN~^",
            "^~INF~^",
            "^~-INF~^",
            "^~TAB~^",
            "^~EMPTY~^"
    );

    private static final Set<String> REMOVED_MARKERS = Set.of(
            "~PARSE~",
            "~VAR~",
            "~RESOLVE~",
            "~RESOLVE-CASE-INSENSITIVE~",
            "~MAP~",
            "~LIST~",
            "~SET~",
            "~OBJECT~",
            "~JSON~",
            "~STRING~",
            "~INT~",
            "~LONG~",
            "~DOUBLE~",
            "~BOOLEAN~",
            "~DECIMAL~",
            "~BIGINT~"
    );

    public ValConverter(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public Object convert(Object input) {
        rejectRemovedMarkers(input);
        return super.convert(input);
    }

    public static final CustomReader valConverter = new ValConverter(MAPPER);

    public static Object convertSpecialValues(Object value) {
        if (value instanceof JsonNode || value instanceof DataTable || value instanceof DocString) {
            return value;
        }
        return valConverter.convertValue(value);
    }

    public static JsonNode convertSpecialValuesToTree(Object value) {
        return valConverter.valueToTree(value);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Object modify(Object value, Object parent) {
        Object special = MappingDirectiveResolver.convertSpecialLiteral(value);
        if (special != value) {
            return special;
        }

        if (value instanceof String text && isRemovedStringMarker(text)) {
            throw removedMarker(text);
        }

        if (!(value instanceof Map<?, ?> rawMap)) {
            return value;
        }

        Map map = (Map) rawMap;
        Map<Object, Object> rewritten = new LinkedHashMap<>();
        for (Object rawEntry : map.entrySet()) {
            Map.Entry entry = (Map.Entry) rawEntry;
            Object rawKey = entry.getKey();
            Object entryValue = entry.getValue();

            if (!(rawKey instanceof String key)) {
                rewritten.put(rawKey, entryValue);
                continue;
            }
            if (isRemovedObjectMarker(key)) {
                throw removedMarker(key);
            }

            MappingDirectiveResolver.DirectiveSpec spec =
                    MappingDirectiveResolver.parseDirectiveSuffix(key);
            if (spec.directives().isEmpty()) {
                if (rewritten.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Mapping directive key collision for '" + key + "'."
                    );
                }
                rewritten.put(key, entryValue);
                continue;
            }
            MappingDirectiveResolver.validateStructuredKeyDirectives(spec);

            Object converted = MappingDirectiveResolver.applyDirectives(
                    entryValue,
                    spec.directives()
            );
            String rewrittenKey = spec.base() == null ? "" : spec.base().trim();
            if (rewrittenKey.isEmpty()) {
                if (map.size() != 1) {
                    throw new IllegalArgumentException(
                            "A key made only of mapping directives can only be used "
                                    + "as the single key of its object: '" + key + "'."
                    );
                }
                return converted;
            }
            if (rewritten.containsKey(rewrittenKey)) {
                throw new IllegalArgumentException(
                        "Mapping directive key '" + key
                                + "' collides with existing key '" + rewrittenKey + "'."
                );
            }
            rewritten.put(rewrittenKey, converted);
        }

        map.clear();
        map.putAll(rewritten);
        return map;
    }

    private static void rejectRemovedMarkers(Object value) {
        if (value instanceof String text) {
            if (isRemovedStringMarker(text) || isRemovedObjectMarker(text)) {
                throw removedMarker(text);
            }
            return;
        }
        if (value instanceof JsonNode node) {
            if (node.isTextual()) {
                rejectRemovedMarkers(node.textValue());
            } else if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    if (isRemovedObjectMarker(entry.getKey())) {
                        throw removedMarker(entry.getKey());
                    }
                    rejectRemovedMarkers(entry.getValue());
                });
            } else if (node.isArray()) {
                node.forEach(ValConverter::rejectRemovedMarkers);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key instanceof String text && isRemovedObjectMarker(text)) {
                    throw removedMarker(text);
                }
                rejectRemovedMarkers(item);
            });
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(ValConverter::rejectRemovedMarkers);
            return;
        }
        if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                rejectRemovedMarkers(Array.get(value, index));
            }
        }
    }

    private static boolean isRemovedStringMarker(String text) {
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        return REMOVED_BARE_VALUE_MARKERS.contains(trimmed)
                || colon > 0 && REMOVED_MARKERS.contains(trimmed.substring(0, colon));
    }

    private static boolean isRemovedObjectMarker(String text) {
        return REMOVED_MARKERS.contains(text.trim());
    }

    private static IllegalArgumentException removedMarker(String marker) {
        return new IllegalArgumentException(
                "ValConverter marker syntax '" + marker + "' was removed. "
                        + "Use mapping directives such as '~JSON;', '~STRING;', "
                        + "or the explicit special markers '<^~NULL~^>', "
                        + "'<^~NAN~^>', '<^~INF~^>', '<^~-INF~^>', "
                        + "'<^~TAB~^>', and '<^~EMPTY~^>'."
        );
    }
}
