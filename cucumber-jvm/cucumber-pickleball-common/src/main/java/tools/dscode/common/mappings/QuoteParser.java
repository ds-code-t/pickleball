package tools.dscode.common.mappings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A concise quoted-text extractor that IS the map: placeholder -> inner
 * (unescaped) value - Parses ' " ` and ''' quoted segments (unescaped, matching
 * bookends) - Masks them with ␅␄…␅ placeholders - Lets you edit the outer
 * masked text and the inner values separately - Restores by re-wrapping inner
 * values with their original quote/bookend - Or force a uniform wrapper (', ",
 * or `); triple uses the ' escape char
 */
public final class QuoteParser extends LinkedHashMap<String, String> {
    // Single-character bookends: ' " `
    private static final String QUOTED_SINGLECHAR = "(?<!\\\\)(['\"`])((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1";
    private static final Pattern P_SINGLECHAR = Pattern.compile(QUOTED_SINGLECHAR);

    // Triple single quotes: '''
    // Group(1) captures the literal ''' so we can negative-lookahead against
    // it.
    private static final String QUOTED_TRIPLE_SINGLE = "(?<!\\\\)(?:\\\\\\\\)*(''')((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1";
    private static final Pattern P_TRIPLE_SINGLE = Pattern.compile(QUOTED_TRIPLE_SINGLE);

    // “Untypable” control pictures for placeholders
    private static final char MASK_CONTENT = '\u2404'; // ␄
    private static final char MASK_BOUNDARY = '\u2405'; // ␅

    public static final char SINGLE = '\'';
    public static final char DOUBLE = '"';
    public static final char BACKTICK = '`';
    public static final String TRIPLE_SINGLE = "'''";

    private final String original;
    private String masked; // mutable
    // placeholder -> delimiter (bookend string), e.g. "'", "\"", "`", or "'''"
    private final Map<String, String> delimiterOf = new HashMap<>();

    public QuoteParser(String input) {
        this.original = input;
        AtomicInteger n = new AtomicInteger(1);

        // Pass 1: mask ' " `
        ParsePass pass1 = applyMaskingPass(input, P_SINGLECHAR, n);
        // Pass 2: mask ''' on the residual (so ''' inside the earlier segments
        // is ignored)
        ParsePass pass2 = applyMaskingPass(pass1.out.toString(), P_TRIPLE_SINGLE, n);

        // Merge results
        this.masked = pass2.out.toString();
        super.putAll(pass1.captured);
        super.putAll(pass2.captured);
        delimiterOf.putAll(pass1.delimiterByPlaceholder);
        delimiterOf.putAll(pass2.delimiterByPlaceholder);
    }

    // Single masking pass over a particular pattern
    private ParsePass applyMaskingPass(String in, Pattern pattern, AtomicInteger n) {
        Matcher m = pattern.matcher(in);
        StringBuffer out = new StringBuffer();
        Map<String, String> captured = new LinkedHashMap<>();
        Map<String, String> delims = new HashMap<>();

        while (m.find()) {
            String opening = m.group(1); // "'", "\"", "`", or "'''"
            char escapeQuoteChar = opening.charAt(0); // the quote char to
                                                      // unescape (single char)
            String inner = unescapeSameQuote(m.group(2), escapeQuoteChar);

            String placeholder = MASK_BOUNDARY
                    + String.valueOf(MASK_CONTENT).repeat(n.getAndIncrement())
                    + MASK_BOUNDARY;

            captured.put(placeholder, inner);
            delims.put(placeholder, opening);
            m.appendReplacement(out, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(out);
        return new ParsePass(out, captured, delims);
    }

    private static final class ParsePass {
        final StringBuffer out;
        final Map<String, String> captured;
        final Map<String, String> delimiterByPlaceholder;
        ParsePass(StringBuffer out, Map<String, String> captured, Map<String, String> delimiterByPlaceholder) {
            this.out = out;
            this.captured = captured;
            this.delimiterByPlaceholder = delimiterByPlaceholder;
        }
    }

    // ---- accessors / mutators ----
    public String original() {
        return original;
    }

    public String masked() {
        return masked;
    }

    public void setMasked(String newMasked) {
        this.masked = Objects.requireNonNull(newMasked);
    }

    // ---- concise filtered views (by quote type) ----
    public List<Map.Entry<String, String>> entriesSingle() {
        return entriesByDelimiter("'");
    }

    public List<Map.Entry<String, String>> entriesDouble() {
        return entriesByDelimiter("\"");
    }

    public List<Map.Entry<String, String>> entriesBacktick() {
        return entriesByDelimiter("`");
    }

    public List<Map.Entry<String, String>> entriesTripleSingle() {
        return entriesByDelimiter(TRIPLE_SINGLE);
    }

    private List<Map.Entry<String, String>> entriesByDelimiter(String delim) {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, String> e : super.entrySet()) {
            if (Objects.equals(delimiterOf.get(e.getKey()), delim))
                list.add(e);
        }
        return list;
    }

    // ---- restore ----
    /**
     * Restore current masked text using current map values, wrapping with each
     * value's original bookend.
     */
    public String restore() {
        return restoreFrom(masked);
    }

    /**
     * Restore any text containing placeholders, honoring each placeholder's
     * original bookend.
     */
    public String restoreFrom(String textWithPlaceholders) {
        String out = textWithPlaceholders;
        List<String> keys = new ArrayList<>(super.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length())); // longest
                                                                      // first
        for (String k : keys) {
            String delim = delimiterOf.get(k); // "'", "\"", "`", or "'''"
            String val = super.get(k);
            char escapeChar = delim.charAt(0); // we escape only this char
                                               // inside the value
            String escaped = escapeForQuote(val, escapeChar);
            out = out.replace(k, delim + escaped + delim);
        }
        return out;
    }

    /**
     * Restore using a specific single-character quote (', ", or `) for ALL
     * segments.
     */
    public String restoreWithQuote(char q) {
        if (q != SINGLE && q != DOUBLE && q != BACKTICK) {
            throw new IllegalArgumentException("Quote must be ', \", or `");
        }
        String out = masked;
        List<String> keys = new ArrayList<>(super.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String k : keys) {
            String val = super.get(k);
            String escaped = escapeForQuote(val, q);
            out = out.replace(k, q + escaped + q);
        }
        return out;
    }

    public String restoreAsDouble() {
        return restoreWithQuote(DOUBLE);
    }

    public String restoreAsSingle() {
        return restoreWithQuote(SINGLE);
    }

    public String restoreAsBacktick() {
        return restoreWithQuote(BACKTICK);
    }

    // ---- helper ----

    /** Return the original delimiter/bookend string for this placeholder. */
    public String delimiterOf(String placeholder) {
        String d = delimiterOf.get(placeholder);
        if (d == null)
            throw new IllegalArgumentException("Unknown placeholder: " + placeholder);
        return d;
    }

    /**
     * Escape ONLY the given quote char inside s (e.g., " -> \", ' -> \', ` ->
     * \`)
     */
    private static String escapeForQuote(String s, char q) {
        if (s == null || s.isEmpty())
            return s;
        return s.replace(String.valueOf(q), "\\" + q);
    }

    /** Turn \" -> " (or \' -> ', \` -> `) */
    private static String unescapeSameQuote(String s, char q) {
        return s.replace("\\" + q, String.valueOf(q));
    }

    /**
     * Returns an entrySet view excluding all triple-single-quote (''') entries.
     */
    public Set<Map.Entry<String, String>> entrySetWithoutTripleSingle() {
        Set<Map.Entry<String, String>> filtered = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : super.entrySet()) {
            String delim = delimiterOf.get(e.getKey());
            if (!TRIPLE_SINGLE.equals(delim)) {
                filtered.add(e);
            }
        }
        return filtered;
    }

}
