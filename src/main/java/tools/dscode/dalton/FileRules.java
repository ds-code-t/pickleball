package tools.dscode.dalton;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Config and data files. A secret field is stored and shown only as a
 * reference ({@code <name>} or {@code ~MASK}). Cleartext is rejected before
 * the write. Dalton-off stays a file write. Dalton-on uses the shared put.
 */
final class FileRules {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(?:^|[\\s,{])([\"']?)([A-Za-z0-9_.-]*?(?:password|passwd|secret|token|api[-_]?key|credential|private[-_]?key))\\1\\s*[:=]\\s*(\\S+)");

    private FileRules() {
    }

    static void validate(Path project, String path, byte[] body) {
        if (body == null || body.length == 0) return;
        String text = new String(body, StandardCharsets.UTF_8);
        Matcher matcher = ASSIGNMENT.matcher(text);
        while (matcher.find()) {
            reject(matcher.group(2), unquote(matcher.group(3)));
        }
        for (String name : names(project, text)) {
            Pattern extra = Pattern.compile("(?i)(?:^|[\\s,{])([\"']?)" + java.util.regex.Pattern.quote(name)
                    + "\\1\\s*[:=]\\s*(\\S+)");
            Matcher named = extra.matcher(text);
            while (named.find()) reject(name, unquote(named.group(2)));
        }
        if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            csv(project, text);
        }
    }

    private static void csv(Path project, String text) {
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) return;
        String[] header = lines[0].split(",", -1);
        boolean[] secret = new boolean[header.length];
        boolean any = false;
        for (int index = 0; index < header.length; index++) {
            String name = header[index].trim();
            if (secretColumn(project, name)) {
                secret[index] = true;
                any = true;
            }
        }
        if (!any) return;
        for (int row = 1; row < lines.length; row++) {
            if (lines[row].isBlank()) continue;
            String[] cells = lines[row].split(",", -1);
            for (int index = 0; index < secret.length && index < cells.length; index++) {
                if (secret[index]) reject(header[index].trim(), cells[index].trim());
            }
        }
    }

    private static java.util.List<String> names(Path project, String text) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>(DaltonSecrets.extraNames(project));
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int split = trimmed.indexOf('=');
            if (split < 0) continue;
            if (!"secret-fields".equalsIgnoreCase(trimmed.substring(0, split).trim())) continue;
            for (String part : trimmed.substring(split + 1).split(",")) {
                if (!part.isBlank()) found.add(part.trim());
            }
        }
        return java.util.List.copyOf(found);
    }

    private static boolean secretColumn(Path project, String name) {
        if (DaltonSecrets.secretName(project, name)) return true;
        return Pattern.compile(DaltonSecrets.DEFAULT_NAMES).matcher(name).find();
    }

    private static void reject(String field, String value) {
        if (value == null || value.isBlank() || DaltonSecrets.reference(value)) return;
        throw new IllegalArgumentException("secret field must be a reference such as <name> or ~MASK: " + field);
    }

    /** Hides a cleartext secret that was already on disk. References stay visible. */
    static String redact(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        Matcher matcher = ASSIGNMENT.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = unquote(matcher.group(3));
            if (DaltonSecrets.reference(value)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String raw = matcher.group(3);
            String masked = raw.startsWith("\"") || raw.startsWith("'")
                    ? raw.charAt(0) + "~MASK" + raw.charAt(0)
                    : "~MASK";
            String replaced = matcher.group().substring(0, matcher.group().length() - raw.length()) + masked;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String unquote(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && last == first) {
                value = value.substring(1, value.length() - 1);
            }
        }
        if (value.endsWith(",") || value.endsWith("}")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.trim();
    }
}
