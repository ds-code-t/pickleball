package tools.dscode.common.variables;

import tools.dscode.common.reporting.diagnostic.SourceProvenance;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.testengine.PKB_props.PKB_PLATFORM_LOG;

/** Formats the automatic caller/platform stamp without changing its default output. */
public final class PlatformLogFormatter {
    public static final String DISABLED_MARKER = "Platform identity logging disabled by pkb_platformlog=none";
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]+)}");

    private PlatformLogFormatter() {
    }

    public static void validate(Map<String, String> values) {
        String configured = find(values, PKB_PLATFORM_LOG);
        if (configured == null || configured.isBlank()) return;
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("default")
                || normalized.equals("default+git")
                || normalized.equals("none")
                || normalized.startsWith("keys:")
                || normalized.startsWith("template:")) {
            return;
        }
        throw new IllegalArgumentException(
                "pkb_platformlog must be default, default+git, none, keys:<list>, or template:<text>"
        );
    }

    public static String format(String defaultText) {
        String configured = System.getProperty(PKB_PLATFORM_LOG, "default").trim();
        if (configured.isBlank() || configured.equalsIgnoreCase("default")) return defaultText;
        if (configured.equalsIgnoreCase("none")) return DISABLED_MARKER;

        Map<String, String> values = availableValues();
        if (configured.equalsIgnoreCase("default+git")) {
            String git = joinKeys(values, "git.consumer.name,git.consumer.branch,git.consumer.commit,pickleball.version,git.pickleball.commit");
            return git.isBlank() ? defaultText : defaultText + " | Source: " + git;
        }
        if (configured.regionMatches(true, 0, "keys:", 0, "keys:".length())) {
            return joinKeys(values, configured.substring("keys:".length()));
        }
        if (configured.regionMatches(true, 0, "template:", 0, "template:".length())) {
            return applyTemplate(configured.substring("template:".length()), values);
        }
        return defaultText;
    }


    public static String formatPlatformData(String defaultText) {
        String configured = System.getProperty(PKB_PLATFORM_LOG, "default").trim();
        if (configured.isBlank() || configured.equalsIgnoreCase("default")) return defaultText;
        if (configured.equalsIgnoreCase("none")) return DISABLED_MARKER;

        Map<String, String> values = availableValues();
        if (configured.equalsIgnoreCase("default+git")) {
            String git = joinKeys(values, "git.consumer.name,git.consumer.branch,git.consumer.commit,pickleball.version,git.pickleball.commit");
            return git.isBlank() ? defaultText : defaultText + " | Source: " + git;
        }
        if (configured.regionMatches(true, 0, "keys:", 0, "keys:".length())) {
            return "Platform Data: " + joinKeys(values, configured.substring("keys:".length()));
        }
        if (configured.regionMatches(true, 0, "template:", 0, "template:".length())) {
            return applyTemplate(configured.substring("template:".length()), values);
        }
        return defaultText;
    }

    public static boolean isDisabled() {
        return "none".equalsIgnoreCase(System.getProperty(PKB_PLATFORM_LOG, "default").trim());
    }

    public static boolean isDisabledMarker(String value) {
        return DISABLED_MARKER.equals(value);
    }

    public static Map<String, String> availableValues() {
        Map<String, String> values = new LinkedHashMap<>();
        PlatformSnapshot.asMap().forEach((key, value) -> values.put(key, value == null ? "" : String.valueOf(value)));
        values.putAll(SourceProvenance.platformValues());
        return values;
    }

    private static String joinKeys(Map<String, String> values, String csv) {
        StringBuilder out = new StringBuilder();
        for (String raw : csv.split(",")) {
            String key = raw.trim();
            if (key.isBlank()) continue;
            if (!out.isEmpty()) out.append(" | ");
            String value = values.get(key);
            out.append(key).append('=').append(value == null || value.isBlank() ? "<unavailable>" : value);
        }
        return out.toString();
    }

    private static String applyTemplate(String template, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null || value.isBlank() ? "<unavailable>" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String find(Map<String, String> values, String key) {
        if (values == null) return null;
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
