package tools.dscode.control.protocol;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One display rule for secret run vars. A stored protected reference is shown
 * as written. A plain secret value is {@code <redacted>} and is never printed.
 */
public final class SecretRedactor {
    public static final String REDACTED = "<redacted>";
    public static final String HINT = "secret redacted; use workspace-show for reference";

    private static final int SCRUB_MIN_LENGTH = 8;
    private static final ThreadLocal<Set<String>> KNOWN = ThreadLocal.withInitial(LinkedHashSet::new);

    private SecretRedactor() {
    }

    /** True when {@code value} is a protected reference that must be shown unchanged. */
    public static boolean isStoredReference(String value) {
        if (value == null) return false;
        if (Workspace.protectedKey(value) != null) return true;
        return value.startsWith("$(protected:") && value.endsWith(")")
                && value.length() > "$(protected:)".length();
    }

    /**
     * Display form of one keyed value. Blank stays blank. A stored reference
     * stays the characters on disk. Any other non-blank sensitive value is redacted.
     */
    public static String display(String key, String value) {
        if (value == null) return "";
        if (isStoredReference(value)) return value;
        if (key != null && Workspace.isSensitiveKey(key) && !value.isBlank()) return REDACTED;
        return value;
    }

    public static boolean leaks(String key, String value) {
        return value != null && !value.isBlank()
                && !isStoredReference(value)
                && key != null && Workspace.isSensitiveKey(key);
    }

    public static void note(String key, String value) {
        if (leaks(key, value) && value.length() >= SCRUB_MIN_LENGTH) KNOWN.get().add(value);
    }

    public static void noteAll(Map<String, String> values) {
        if (values == null) return;
        values.forEach(SecretRedactor::note);
    }

    /** {@code key=value} assignments from {@code --runvar}. */
    public static void noteAssignments(Collection<String> assignments) {
        if (assignments == null) return;
        for (String item : assignments) {
            if (item == null) continue;
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String key = item.substring(0, equals).trim();
            String value = item.substring(equals + 1);
            if (!key.toLowerCase(java.util.Locale.ROOT).startsWith("pkb_")) key = "pkb_" + key;
            note(key, value);
        }
    }

    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        Set<String> known = KNOWN.get();
        if (known.isEmpty() || !containsAny(text, known)) return text;
        String result = text;
        for (String secret : known.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            if (secret == null || secret.length() < SCRUB_MIN_LENGTH || isStoredReference(secret)) continue;
            if (result.contains(secret)) result = result.replace(secret, REDACTED);
        }
        return result;
    }

    public static void clear() {
        KNOWN.remove();
    }

    private static boolean containsAny(String text, Set<String> known) {
        for (String secret : known) {
            if (secret != null && text.contains(secret)) return true;
        }
        return false;
    }
}
