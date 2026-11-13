package tools.dscode.common.composite;

import java.util.Locale;
import java.util.Map;

/**
 * {@code pkb_composite} selects the element path for this run.
 * {@code auto} (default) uses the DOM engine (the in-repo Java engine unless
 * an explicit binary or {@code pkb_composite_engine=rust} is set) and the
 * legacy Selenium path when no engine can be located or started.
 * {@code on} (also {@code true}, {@code required}, {@code composite}) is the
 * same choice, always prints the missing-engine line, and accepts
 * {@code composite} on {@code PATH}. {@code off} (also {@code false},
 * {@code legacy}, {@code selenium}) keeps Selenium and does not look for an engine.
 */
public enum CompositeMode {
    AUTO,
    ON,
    OFF;

    public static final String KEY = "pkb_composite";

    public static CompositeMode current() {
        CompositeRuntime.Sources pinned = CompositeRuntime.pinned();
        if (pinned != null) return parse(read(KEY, pinned));
        return parse(setting(KEY));
    }

    public static CompositeMode parse(String raw) {
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

    static String read(String key, CompositeRuntime.Sources sources) {
        Map<String, String> properties = sources.properties() == null ? Map.of() : sources.properties();
        String property = text(properties.get(key));
        if (property != null) return property;
        property = text(properties.get("pkb_runvars." + key));
        if (property != null) return property;
        Map<String, String> env = sources.env() == null ? Map.of() : sources.env();
        property = text(env.get(key.toUpperCase(Locale.ROOT)));
        if (property != null) return property;
        property = text(env.get(key));
        if (property != null) return property;
        return sources.runVars() ? runVar(key) : null;
    }

    static String setting(String key) {
        CompositeRuntime.Sources pinned = CompositeRuntime.pinned();
        if (pinned != null) return read(key, pinned);
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
