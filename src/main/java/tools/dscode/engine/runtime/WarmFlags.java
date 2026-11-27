package tools.dscode.engine.runtime;

import java.util.Locale;

/**
 * {@code pkb_warm} defaults to off. CI and {@code mvn test} stay cold unless a test sets the flag.
 */
public final class WarmFlags {
    private WarmFlags() {
    }

    public static boolean enabled() {
        String raw = setting("pkb_warm", "PKB_WARM");
        if (raw == null || raw.isBlank()) return false;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            default -> false;
        };
    }

    public static String setting(String property, String env) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        String envValue = System.getenv(env);
        if (envValue != null && !envValue.isBlank()) return envValue;
        return null;
    }
}
