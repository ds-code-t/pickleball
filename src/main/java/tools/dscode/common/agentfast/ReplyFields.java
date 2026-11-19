package tools.dscode.common.agentfast;

import java.util.LinkedHashMap;
import java.util.Map;

/** Timing fields stamped onto every agent reply. */
public final class ReplyFields {
    private ReplyFields() {
    }

    public static Map<String, Object> stamp(Map<String, Object> body, long elapsedMs) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (body != null) copy.putAll(body);
        long ms = Math.max(0L, elapsedMs);
        copy.put("elapsedMs", ms);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("totalMs", ms);
        timing.put("jvmMs", ms);
        Object existing = copy.get("timing");
        if (existing instanceof Map<?, ?> prior) {
            prior.forEach((key, value) -> {
                if (key != null && value != null && !"totalMs".equals(key) && !"jvmMs".equals(key)) {
                    timing.put(String.valueOf(key), value);
                }
            });
        }
        copy.put("timing", timing);
        return copy;
    }
}
