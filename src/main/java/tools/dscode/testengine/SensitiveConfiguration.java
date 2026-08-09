package tools.dscode.testengine;

import java.util.Locale;
import java.util.Set;

/** Central registry for configuration values that must never be rendered in plaintext. */
public final class SensitiveConfiguration {
    public static final String REDACTED = "<redacted>";
    private static final String PROTECTED_PREFIX = "${protected:";
    private static final String PROTECTED_SUFFIX = "}";

    /*
     * Keep explicit names here so future protected variables are easy to audit and add.
     * Native ReportPortal names are normalized to their pkb_rp_* aliases before lookup.
     */
    private static final Set<String> PROTECTED_PROPERTIES = Set.of(
            "pkb_rp_api_key",
            "pkb_rp_uuid",
            "pkb_rp_oauth_password",
            "pkb_rp_oauth_client_secret",
            "pkb_rp_keystore_password",
            "pkb_rp_truststore_password",
            "pkb_rp_http_proxy_password"
    );

    /* Conservative fallback so new secret-like RunVars cannot bypass redaction before being audited above. */
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "accesskey", "privatekey", "credential", "authorization", "cookie"
    );

    private SensitiveConfiguration() {
    }

    public static Set<String> protectedProperties() {
        return PROTECTED_PROPERTIES;
    }

    public static boolean isSensitive(String key) {
        String normalized = normalize(key);
        return PROTECTED_PROPERTIES.contains(normalized)
                || SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    public static String displayValue(String key, String value) {
        return isSensitive(key) && value != null && !value.isBlank() ? REDACTED : value;
    }

    public static String protectedReference(String key) {
        return PROTECTED_PREFIX + normalize(key) + PROTECTED_SUFFIX;
    }

    public static String protectedKey(String value) {
        if (value == null || !value.startsWith(PROTECTED_PREFIX) || !value.endsWith(PROTECTED_SUFFIX)) {
            return null;
        }
        String key = value.substring(PROTECTED_PREFIX.length(), value.length() - PROTECTED_SUFFIX.length()).trim();
        return key.isEmpty() ? null : normalize(key);
    }

    private static String normalize(String key) {
        if (key == null) {
            return "";
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("rp.")) {
            return PickleballProfiles.reportPortalAliasKey(normalized);
        }
        return normalized;
    }
}
