package dynamicparsing;


import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal bracket masker:
 * - Extends HashMap: placeholder -> inner (unbracketed) value
 * - Masks deepest-first, then outward, with order: () -> {} -> [] -> <>
 * - Placeholders look like "\u2408_1", "\u2408_2", ... (untypable prefix ␈)
 * - Restores by re-wrapping each placeholder with its original bracket type
 *
 * Notes:
 * - "Same-bookend characters are not allowed inside" is enforced by patterns like:
 *     \\([^()]*\\), \\{[^{}]*\\}, \\[[^\\[\\]]*\\], <[^<>]*>
 *   which match only non-nested segments of the SAME bracket type; we loop
 *   until no change to achieve deepest-first masking.
 */
public final class BracketMasker extends HashMap<String, String> {
    private static final long serialVersionUID = 1L;

    // Untypable prefix used in all placeholder keys (U+2408 "SYMBOL FOR BACKSPACE": ␈)
    public static final String KEY_PREFIX = "\u2408_"; // "␈_"

    private final String original;
    private String masked; // mutable
    private final Map<String, String> bracketOf = new HashMap<>(); // placeholder -> "()", "{}", "[]", "<>"

    public BracketMasker(String input) {
        Objects.requireNonNull(input, "input");
        this.original = input;
        this.masked = input;

        // global loop: keep attempting all bracket types until no change
        for (;;) {
            String before = masked;

            // Process each bracket type to exhaustion (deepest-first for that type)
            maskAllOfType('(', ')');
            maskAllOfType('{', '}');
            maskAllOfType('[', ']');
            maskAllOfType('<', '>');

            if (masked.equals(before)) break; // no changes in this entire pass
        }
    }

    /** Original input string. */
    public String original() { return original; }

    /** Current masked text (with placeholders). */
    public String masked() { return masked; }

    /** Replace the masked text (e.g., after outer edits). */
    public void setMasked(String newMasked) { this.masked = Objects.requireNonNull(newMasked); }

    /** Return the original bracket pair for a placeholder: "()", "{}", "[]", or "<>". */
    public String bracketOf(String placeholder) {
        String b = bracketOf.get(placeholder);
        if (b == null) throw new IllegalArgumentException("Unknown placeholder: " + placeholder);
        return b;
    }

    /** Restore current masked text using current map values. */
    public String restore() { return restoreFrom(masked); }

    /** Restore an arbitrary text that contains these placeholders. */
    public String restoreFrom(String textWithPlaceholders) {
        String out = Objects.requireNonNull(textWithPlaceholders);

        // Optimization: if no prefix, nothing to do
        if (out.indexOf(KEY_PREFIX) < 0) return out;

        // Loop until no change, per requirement
        for (;;) {
            String before = out;

            // Early break if prefix no longer present
            if (before.indexOf(KEY_PREFIX) < 0) break;

            // Replace all placeholders found in the string
            for (Map.Entry<String, String> e : entrySet()) {
                String key = e.getKey();
                int idx = out.indexOf(key);
                if (idx >= 0) {
                    String br = bracketOf.get(key);
                    char open = br.charAt(0), close = br.charAt(1);
                    String wrapped = open + e.getValue() + close;
                    out = out.replace(key, wrapped);
                }
            }
            if (out.equals(before)) break;
        }
        return out;
    }

    // ---------------- internal masking ----------------

    /** Mask all innermost occurrences of a single bracket type until none remain. */
    private void maskAllOfType(char open, char close) {
        // Build a pattern matching innermost segments for this bracket type only.
        // Examples:
        //   '(': "\\([^()]*\\)"
        //   '{': "\\{[^{}]*\\}"
        //   '[': "\\[[^\\[\\]]*\\]"
        //   '<': "<[^<>]*>"
        final Pattern pat = Pattern.compile(innermostPatternFor(open, close));
        final AtomicInteger counter = localCounter();

        for (;;) {
            Matcher m = pat.matcher(masked);
            if (!m.find()) break; // none of this type left

            StringBuffer sb = new StringBuffer();
            do {
                String whole = m.group(); // e.g. "(abc)"
                String inner = whole.substring(1, whole.length() - 1);

                String placeholder = KEY_PREFIX + counter.getAndIncrement();
                super.put(placeholder, inner);
                bracketOf.put(placeholder, "" + open + close);

                m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
            } while (m.find());
            m.appendTail(sb);
            masked = sb.toString();
        }
    }

    /** Create a local counter that continues global numbering based on current map size. */
    private AtomicInteger localCounter() {
        // Start from size()+1 to keep incrementing across bracket types/runs
        return new AtomicInteger(this.size() + 1);
    }

    /** Innermost pattern for a bracket pair, matching no same-bracket chars inside. */
    private static String innermostPatternFor(char open, char close) {
        // Escape for regex if needed
        String o = Pattern.quote(String.valueOf(open));
        String c = Pattern.quote(String.valueOf(close));
        String negClass;
        if (open == '[') {
            // In a character class, ']' must be escaped specially; we avoid char classes for '[' by explicit negation.
            // But for simplicity and clarity, we stick with a negated class form and escape both.
            negClass = "[^\\[\\]]*";
        } else if (open == '(') {
            negClass = "[^()]*";
        } else if (open == '{') {
            negClass = "[^{}]*";
        } else if (open == '<') {
            negClass = "[^<>]*";
        } else {
            throw new IllegalArgumentException("Unsupported bracket: " + open);
        }
        return o + negClass + c;
    }
}
