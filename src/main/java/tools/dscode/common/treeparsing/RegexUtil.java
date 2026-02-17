package tools.dscode.common.treeparsing;


import java.util.regex.Pattern;


/** Small helpers for composing regex fragments. */
public final class RegexUtil {
    private RegexUtil() {}

    /**
     * Matches a block starting with literal 'start' and ending with literal 'end',
     * allowing escaped delimiters (e.g., \") inside.
     *
     * Example: betweenWithEscapes("\"", "\"")
     */
    public static String betweenWithEscapes(String start, String end) {
        String s = Pattern.quote(start);
        String e = Pattern.quote(end);
        // (?s) dotall; (?:\\.|(?!end).)*? matches any escaped char or any char not starting the end delimiter
        return "(?s)" + s + "(?:\\\\.|(?!"+e+").)*" + e;
    }


    public static String stripObscureNonText(String input) {
        if (input == null || input.isEmpty()) return input;
        // 1) Remove all Cf EXCEPT token sentinels
        String out = input.replaceAll("[\\p{Cf}&&[^\\u2060\\u2063]]+", "");
        // 2) Remove all Cc EXCEPT tab/newline/CR
        out = out.replaceAll("[\\p{Cc}&&[^\\t\\n\\r]]+", "");
        return out.strip();
    }

    public static String normalizeWhitespace(String input) {
        if (input == null || input.isEmpty()) return input;
        // All Unicode spaces + classic \s, plus zero-width space \u200B.
        // (We intentionally EXCLUDE \u2060 and \u2063 so tokens survive.)
        String collapsed = input.replaceAll(
                "[\\p{Z}\\s\\u00A0\\u1680\\u2000-\\u200A\\u200B\\u2028\\u2029\\u202F\\u205F\\u3000]+",
                " "
        );
        return collapsed.trim();
    }

//    public static String safeColorize(String input, Attribute... attributes) {
//        return " " + colorize(input, attributes) + " ";
//    }
//
//    public static String colorizeBookends(String input, Attribute... attributes) {
//        return " " + colorize(" (", attributes) + " " + input  + colorize(") ", attributes);
//    }

    public static final String M1 = "\u001C";
    public static final String M2 = "\u001D";
    public static final String M3 = "\u001E";
    public static final String M4 = "\u001F";


    public static final String TOKEN_START = "_\u2060";
    public static final String TOKEN_END   = "\u2063_";
    // Escaped sentinels (safe to embed in regex)
    public static final String TOKEN_START_ESC = Pattern.quote(TOKEN_START);
    public static final String TOKEN_END_ESC   = Pattern.quote(TOKEN_END);

    // Token name part used by your engine: _<name>_<index>_
    // (Name can include underscores; index is digits.)
    public static final String TOKEN_NAME = "[A-Za-z][A-Za-z0-9_]*";
    public static final String TOKEN_BODY = "_" + TOKEN_NAME + "_\\d+_\\d+_";

    // Full token pattern: \Q\u2060\E_<name>_\d+_\Q\u2063\E
    public static final String TOKEN = TOKEN_START_ESC + TOKEN_BODY + TOKEN_END_ESC;

    // ---------- “Unmasked” text matchers ----------
    // Equivalent to DOTALL ".*" but NEVER consumes a token start (i.e., avoids masked regions)

    // One or more non-token chars, DOTALL
    public static final String UNMASKED_ANY_PLUS = "(?s:(?!" + TOKEN_START_ESC + ").)+";

    // Zero or more non-token chars, DOTALL (greedy)
    public static final String UNMASKED_ANY_STAR = "(?s:(?!" + TOKEN_START_ESC + ").)*";

    // Zero or more non-token chars, DOTALL (lazy)
    public static final String UNMASKED_ANY_LAZY = "(?s:(?!" + TOKEN_START_ESC + ").)*?";

    // A convenience for “N or more” non-token chars
    public static String unmaskedAtLeast(int n) {
        return "(?s:(?!" + TOKEN_START_ESC + ").){" + n + ",}";
    }
}
