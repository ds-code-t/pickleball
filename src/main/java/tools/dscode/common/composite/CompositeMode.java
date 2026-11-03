package tools.dscode.common.composite;

import java.util.Locale;

/**
 * {@code pkb_composite} selects the element backend for this run.
 * {@code auto} (default) uses dom-composite when a binary is located and
 * otherwise the legacy Selenium path. {@code on} is the same choice but the
 * missing-binary line is always printed. {@code off} keeps Selenium and does
 * not look for the binary.
 */
public enum CompositeMode {
    AUTO,
    ON,
    OFF;

    public static final String KEY = "pkb_composite";

    public static CompositeMode current() {
        String raw = setting(KEY);
        if (raw == null) return AUTO;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "off", "false", "legacy", "selenium" -> OFF;
            case "on", "true", "required", "composite" -> ON;
            default -> AUTO;
        };
    }

    public boolean required() {
        return this == ON;
    }

    public boolean disabled() {
        return this == OFF;
    }

    static String setting(String key) {
        String property = text(System.getProperty(key));
        if (property != null) return property;
        property = text(System.getProperty("pkb_runvars." + key));
        if (property != null) return property;
        property = text(System.getenv(key.toUpperCase(Locale.ROOT)));
        if (property != null) return property;
        property = text(System.getenv(key));
        if (property != null) return property;
        return runVar(key);
    }

    private static String runVar(String key) {
        try {
            Class<?> type = Class.forName(
                    "tools.dscode.common.variables.RunVars",
                    false,
                    CompositeMode.class.getClassLoader()
            );
            if (type.getClassLoader() == null) return null;
            Object value = Class.forName("tools.dscode.common.variables.RunVars")
                    .getMethod("resolveFromVars", String.class)
                    .invoke(null, key);
            return text(value == null ? null : value.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String text(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
