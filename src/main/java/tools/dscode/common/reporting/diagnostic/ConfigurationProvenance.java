package tools.dscode.common.reporting.diagnostic;

import tools.dscode.testengine.SensitiveConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigurationProvenance {
    public record Value(String value, String source, boolean defaulted, boolean redacted, String valueHash) {}

    private static final Map<String, String> lastValues = new ConcurrentHashMap<>();
    private static final Map<String, String> sources = new ConcurrentHashMap<>();
    private static final Set<String> sensitiveFragments = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "accesskey", "privatekey", "credential", "authorization", "cookie"
    );

    private ConfigurationProvenance() {
    }

    public static synchronized void begin() {
        lastValues.clear();
        sources.clear();
    }

    public static synchronized void capture(String source, Map<String, String> values) {
        if (values == null) return;
        String winningSource = source == null ? "unknown" : source;
        values.forEach((key, value) -> {
            String normalized = normalize(key);
            String previous = lastValues.put(normalized, value);
            if (!sources.containsKey(normalized) || !Objects.equals(previous, value)) {
                sources.put(normalized, winningSource);
            }
        });
    }

    public static synchronized void captureSupplied(String source, String key, String value) {
        if (key == null) return;
        String normalized = normalize(key);
        lastValues.put(normalized, value);
        sources.put(normalized, source == null ? "unknown" : source);
    }

    public static synchronized Map<String, Value> effective(Map<String, String> values) {
        Map<String, Value> result = new LinkedHashMap<>();
        if (values == null) return result;

        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Comparator.comparing(e -> normalize(e.getKey())));
        for (Map.Entry<String, String> entry : entries) {
            if (!executionRelevant(entry.getKey())) continue;
            String normalized = normalize(entry.getKey());
            boolean redacted = sensitive(normalized);
            String source = sources.getOrDefault(normalized, "resolved");
            result.put(entry.getKey(), new Value(
                    redacted ? SensitiveConfiguration.REDACTED : entry.getValue(),
                    source,
                    source.toLowerCase(Locale.ROOT).contains("default"),
                    redacted,
                    redacted ? valueHash(entry.getValue()) : null
            ));
        }
        return result;
    }

    public static boolean sensitive(String key) {
        String normalized = normalize(key);
        return SensitiveConfiguration.isSensitive(normalized)
                || sensitiveFragments.stream().anyMatch(normalized::contains);
    }

    private static boolean executionRelevant(String key) {
        String k = normalize(key);
        return k.startsWith("pkb_")
                || k.startsWith("cucumber.")
                || k.startsWith("rp.")
                || k.startsWith("selenium.")
                || k.startsWith("webdriver.")
                || k.equals("browser")
                || k.equals("environment")
                || k.equals("env");
    }

    private static String valueHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
