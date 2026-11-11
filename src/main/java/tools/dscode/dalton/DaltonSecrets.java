package tools.dscode.dalton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Name-based secret rejection. {@code secret-fields} adds names. It does not
 * encrypt anything. A long spaceless value on any other field is a warning.
 */
public final class DaltonSecrets {
    public static final String DEFAULT_NAMES =
            "(?i)password|passwd|secret|token|api[-_]?key|credential|private[-_]?key";
    private static final Pattern DEFAULT_KEY = Pattern.compile(DEFAULT_NAMES);
    private static final Pattern DEFAULT_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|secret|token|api[-_]?key|credential|private[-_]?key)\\b\\s*[:=]\\s*(\\S+)");

    private DaltonSecrets() {
    }

    public static boolean secretName(Path project, String key) {
        if (key == null || key.isBlank()) return false;
        if (DEFAULT_KEY.matcher(key).matches()) return true;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String name : extraNames(project)) {
            if (lower.equals(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static boolean reference(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.startsWith("~MASK")) return true;
        return text.startsWith("<") && text.endsWith(">") && text.length() > 2;
    }

    public static boolean highEntropy(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() < 32 || text.indexOf(' ') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\t') >= 0) {
            return false;
        }
        if (reference(text)) return false;
        return text.chars().distinct().count() >= 10;
    }

    public static String warning(String field) {
        return "warn: high-entropy value on " + field
                + "; stored as written. Dalton does not encrypt at rest. Secrets must be references such as <name> or ~MASK;";
    }

    public static void rejectAssignment(Path project, String value) {
        if (value == null || value.isBlank()) return;
        rejectOne(DEFAULT_ASSIGNMENT.matcher(value));
        List<String> extras = extraNames(project);
        if (extras.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (String name : extras) {
            if (names.length() > 0) names.append('|');
            names.append(literal(name));
        }
        Pattern extra = Pattern.compile("\\b(?:" + names + ")\\b\\s*[:=]\\s*(\\S+)");
        rejectOne(extra.matcher(value));
    }

    private static void rejectOne(Matcher matcher) {
        while (matcher.find()) {
            String secret = matcher.group(matcher.groupCount());
            if (reference(secret)) continue;
            throw new IllegalArgumentException("history holds secret references only, not cleartext");
        }
    }

    static List<String> extraNames(Path project) {
        String raw = setting("pkb_dalton_secret_fields", "PKB_DALTON_SECRET_FIELDS");
        if (raw == null || raw.isBlank()) raw = fileValue(project);
        if (raw == null || raw.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names;
    }

    private static String literal(String name) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < name.length(); ) {
            int point = name.codePointAt(index);
            if (Character.isLetter(point)) {
                out.append('[');
                out.appendCodePoint(Character.toLowerCase(point));
                out.appendCodePoint(Character.toUpperCase(point));
                out.append(']');
            } else {
                out.append(Pattern.quote(new String(Character.toChars(point))));
            }
            index += Character.charCount(point);
        }
        return out.toString();
    }

    private static String setting(String property, String env) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        String envValue = System.getenv(env);
        if (envValue != null && !envValue.isBlank()) return envValue;
        return null;
    }

    private static String fileValue(Path project) {
        if (project == null) return null;
        Path file = project.resolve("dalton.properties");
        if (!Files.isRegularFile(file)) return null;
        String joined = null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int split = trimmed.indexOf('=');
                if (split < 0) continue;
                String key = trimmed.substring(0, split).trim();
                if (!"secret-fields".equalsIgnoreCase(key)) continue;
                String value = trimmed.substring(split + 1).trim();
                if (value.isEmpty()) continue;
                joined = joined == null ? value : joined + "," + value;
            }
        } catch (IOException ignored) {
            return null;
        }
        return joined;
    }
}
