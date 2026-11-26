package tools.dscode.common.treeparsing;

import tools.dscode.engine.EngineLog;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One quote-aware reading of context words, shared by ingest and phrase parse.
 * A word inside quotes is not a relation.
 */
public final class ContextWords {
    private static final Pattern LEADING = Pattern.compile(
            "(?is)^\\s*(?:then\\s+)?(?:(?:and|or)\\s+)?(?:then\\s+)?(?:(?:until|else\\s+if|else|if)\\s+)?"
                    + "(next to|left of|right of|following|preceding|between|inside|within|before|after|from|near|below|above|for|in|on|of)\\b(.*)$");

    private ContextWords() {
    }

    /** {@code mode} is {@code inherit}, {@code local}, or {@code all}. {@code scope} is {@code any-parent} or null. */
    public record Reading(String mode, String scope, String relation, String word) {
    }

    public static Reading read(String text) {
        String visible = maskQuotes(text);
        String mode = "inherit";
        if (trailing(visible, "with local context")) mode = "local";
        else if (trailing(visible, "with all contexts")) mode = "all";
        String scope = Pattern.compile("(?i)\\bin\\s+any\\s+parent\\b").matcher(visible).find() ? "any-parent" : null;
        Matcher lead = LEADING.matcher(visible);
        String word = null;
        String relation = null;
        if (lead.matches()) {
            word = lead.group(1).toLowerCase(Locale.ROOT);
            relation = relationFor(word, lead.group(2));
        }
        if (relation != null && EngineLog.enabled("match")) {
            EngineLog.log(
                    Path.of(System.getProperty("user.dir", ".")),
                    "match",
                    "context",
                    word + " -> " + relation);
        }
        return new Reading(mode, scope, relation, relation == null ? null : word);
    }

    /** Drops a trailing mode suffix that sits outside quotes so the element parser still sees the target. */
    public static String withoutModeSuffix(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String visible = maskQuotes(text);
        String lower = visible.toLowerCase(Locale.ROOT);
        for (String suffix : new String[] {"with local context", "with all contexts"}) {
            int at = lower.lastIndexOf(suffix);
            if (at < 0) continue;
            if (!lower.substring(at + suffix.length()).isBlank()) continue;
            if (at > 0 && Character.isLetterOrDigit(lower.charAt(at - 1))) continue;
            return text.substring(0, at).stripTrailing();
        }
        return text;
    }

    static String maskQuotes(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == 0 && (c == '"' || c == '\'')) {
                quote = c;
                out.append(' ');
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                out.append(' ');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean trailing(String visible, String suffix) {
        String lower = visible.toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf(suffix);
        if (at < 0) return false;
        if (!lower.substring(at + suffix.length()).isBlank()) return false;
        return at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
    }

    private static String relationFor(String word, String rest) {
        return switch (word) {
            case "inside", "within" -> "inside";
            case "near", "next to" -> "near";
            case "following" -> "after";
            case "preceding" -> "before";
            case "on", "of" -> "in";
            case "between" -> rest != null && Pattern.compile("(?i)\\band\\b").matcher(rest).find() ? "between" : null;
            default -> null;
        };
    }
}
