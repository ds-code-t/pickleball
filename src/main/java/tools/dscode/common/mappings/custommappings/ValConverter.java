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


import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMap;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;
import static tools.dscode.common.mappings.ParsingMap.resolveToStringWithRunningParsingMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.variables.RunVars.resolveFromVars;


public class ValConverter extends CustomReader {
    public ValConverter(ObjectMapper mapper) {
        super(mapper);
    }

    public static final CustomReader valConverter = new ValConverter(MAPPER);

    @Override
    protected Object modify(Object value, Object parent) {
        if (value instanceof String s && s.trim().startsWith("~") && s.contains(":")) {
            int colonIndex = s.indexOf(':');
            if (colonIndex > 0) {
                String key = s.substring(0, colonIndex);
                String innerValue = s.substring(colonIndex + 1);
                Object converted = convertMarkedValue(key, innerValue, s, parent);
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

        return convertMarkedValue(key, entry.getValue(), value, parent);
    }

    private Object convertMarkedValue(String key, Object innerValue, Object originalValue, Object parent) {
        if (innerValue == null) return null;


        if (key == null
                || key.length() < 3
                || key.charAt(0) != '~'
                || key.charAt(key.length() - 1) != '~') {
            return originalValue;
        }

        if ("~PARSE~".equals(key)) {
            return resolveToStringWithRunningParsingMap("{" + modify(innerValue, parent) + "}");
        }

        if ("~VAR~".equals(key)) {
            String val = String.valueOf(modify(innerValue, parent));
            return resolveFromVars(val);
        }

        if (key.startsWith("~RESOLVE")) {
            if ("~RESOLVE~".equals(key)) {
                return getFromRunningParsingMap(String.valueOf(modify(innerValue, parent)));
            }
            if ("~RESOLVE-CASE-INSENSITIVE~".equals(key)) {
                return getFromRunningParsingMapCaseInsensitive(String.valueOf(modify(innerValue, parent)));
            }
        }

        Object structured = convertStructuredType(key, modify(innerValue, parent), originalValue);
        if (structured != originalValue) {
            return structured;
        }

        Class<?> targetType = resolveScalarType(key);
        if (targetType == null) {
            return originalValue;
        }

        if (targetType == String.class) {
            return String.valueOf(modify(innerValue, parent));
        }

        return mapper.convertValue(modify(innerValue, parent), targetType);
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
            throw new RuntimeException("Failed to convert value: " + originalValue, e);
        }
    }

    private Object convertToMap(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, new TypeReference<Map<String, Object>>() {
            });
        }
        return mapper.convertValue(innerValue, new TypeReference<Map<String, Object>>() {
        });
    }

    private Object convertToList(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, new TypeReference<List<Object>>() {
            });
        }
        return mapper.convertValue(innerValue, new TypeReference<List<Object>>() {
        });
    }

    private Object convertToSet(Object innerValue) throws Exception {
        if (innerValue instanceof String s) {
            return mapper.readValue(s, new TypeReference<LinkedHashSet<Object>>() {
            });
        }
        return mapper.convertValue(innerValue, new TypeReference<LinkedHashSet<Object>>() {
        });
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