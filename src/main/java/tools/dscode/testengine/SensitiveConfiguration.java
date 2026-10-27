package tools.dscode.testengine;

import tools.dscode.control.protocol.Workspace;

import java.util.Set;

/** Central registry for configuration values that must never be rendered in plaintext. */
public final class SensitiveConfiguration {
    public static final String REDACTED = "<redacted>";

    private SensitiveConfiguration() {
    }

    public static Set<String> protectedProperties() {
        return Workspace.protectedPropertyNames();
    }

    public static boolean isSensitive(String key) {
        return Workspace.isSensitiveKey(key);
    }

    public static String displayValue(String key, String value) {
        return isSensitive(key) && value != null && !value.isBlank() ? REDACTED : value;
    }

    public static String protectedReference(String key) {
        return Workspace.protectedReference(key);
    }

    public static String protectedKey(String value) {
        return Workspace.protectedKey(value);
    }
}