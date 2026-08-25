package tools.dscode.control.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal defensive-copy support for JSON-compatible protocol values. */
final class ControlBridgeJson {
    private ControlBridgeJson() {
    }

    static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(
                            "Control protocol JSON object keys must be strings."
                    );
                }
                copy.put(text, immutableValue(child));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ControlBridgeJson::immutableValue).toList();
        }
        throw new IllegalArgumentException(
                "Control protocol value is not JSON-compatible: " + value.getClass().getName()
        );
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> immutableObject(Map<String, ?> value) {
        return (Map<String, Object>) immutableValue(value);
    }
}
