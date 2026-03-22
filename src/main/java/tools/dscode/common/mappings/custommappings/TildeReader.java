package tools.dscode.common.mappings.custommappings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public class TildeReader extends CustomReader {
    public TildeReader(ObjectMapper mapper) {
        super(mapper);
    }

    public static final CustomReader tildeReader = new TildeReader(MAPPER);

    public static Object attemptConversion(Object value) {
        if (value instanceof String s && s.trim().startsWith("~") && s.contains(":"))
            return tildeReader.convertValue(value);
        return value;
    }

    @Override
    protected Object modify(Object value, Object parent) {
        if (value instanceof String s) {
            int colonIndex = s.indexOf(':');
            if (colonIndex > 0) {
                String key = s.substring(0, colonIndex);
                String innerValue = s.substring(colonIndex + 1);
                Object converted = convertMarkedValue(key, innerValue, s);
                if (converted != s) {
                    return converted;
                }
            }
            return value;
        }

        if (!(value instanceof Map<?, ?> map) || map.size() != 1) {
            return value;
        }

        Map.Entry<?, ?> entry = map.entrySet().iterator().next();
        if (!(entry.getKey() instanceof String key)
                || key.length() < 3
                || key.charAt(0) != '~'
                || key.charAt(key.length() - 1) != '~') {
            return value;
        }

        return convertMarkedValue(key, entry.getValue(), value);
    }

    private Object convertMarkedValue(String key, Object innerValue, Object originalValue) {
        if (key == null
                || key.length() < 3
                || key.charAt(0) != '~'
                || key.charAt(key.length() - 1) != '~') {
            return originalValue;
        }

        if ("~PARSE~".equals(key)) {
            return getRunningParsingMap().resolveWholeText("{" + innerValue + "}");
        }

        Object structured = convertStructuredType(key, innerValue, originalValue);
        if (structured != originalValue) {
            return structured;
        }

        Class<?> targetType = resolveScalarType(key);
        if (targetType == null) {
            return originalValue;
        }

        if (targetType == String.class) {
            return innerValue == null ? null : String.valueOf(innerValue);
        }

        return mapper.convertValue(innerValue, targetType);
    }

    private Object convertStructuredType(String key, Object innerValue, Object originalValue) {
        try {
            return switch (key) {
                case "~MAP~" -> convertToMap(innerValue);
                case "~LIST~" -> convertToList(innerValue);
                case "~SET~" -> convertToSet(innerValue);
                case "~OBJECT~" -> convertToObject(innerValue);
                case "~JSON~" -> convertToJsonNode(innerValue);
                default -> originalValue;
            };
        } catch (Exception e) {
            return originalValue;
        }
    }

    private Object convertToMap(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
        }
        return mapper.convertValue(innerValue, new TypeReference<Map<String, Object>>() {});
    }

    private Object convertToList(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, new TypeReference<List<Object>>() {});
        }
        return mapper.convertValue(innerValue, new TypeReference<List<Object>>() {});
    }

    private Object convertToSet(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, new TypeReference<LinkedHashSet<Object>>() {});
        }
        return mapper.convertValue(innerValue, new TypeReference<LinkedHashSet<Object>>() {});
    }

    private Object convertToObject(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, Object.class);
        }
        return mapper.convertValue(innerValue, Object.class);
    }

    private JsonNode convertToJsonNode(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readTree(s);
        }
        return mapper.valueToTree(innerValue);
    }

    private Class<?> resolveScalarType(String marker) {
        return switch (marker) {
            case "~STRING~" -> String.class;
            case "~INT~" -> Integer.class;
            case "~LONG~" -> Long.class;
            case "~DOUBLE~" -> Double.class;
            case "~BOOLEAN~" -> Boolean.class;
            case "~DECIMAL~" -> BigDecimal.class;
            case "~BIGINT~" -> BigInteger.class;
            default -> null;
        };
    }
}