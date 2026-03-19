package tools.dscode.common.mappings.custommappings;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningParsingMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public class TildeReader extends CustomReader {
    public TildeReader(ObjectMapper mapper) {
        super(mapper);
    }

    public static final CustomReader tildeReader = new TildeReader(MAPPER);

    @Override
    protected Object modify(Object value, Object parent) {
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

        Object innerValue = entry.getValue();

        if ("~PARSE~".equals(key)) {
            return getRunningParsingMap().resolveWholeText("{" + innerValue + "}");
        }

        Class<?> targetType = resolveType(key);
        if (targetType == null) {
            return value;
        }

        if (targetType == String.class) {
            return innerValue == null ? null : String.valueOf(innerValue);
        }

        return mapper.convertValue(innerValue, targetType);
    }


    private Class<?> resolveType(String marker) {
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