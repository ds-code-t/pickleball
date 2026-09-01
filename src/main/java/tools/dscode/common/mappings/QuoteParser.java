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

import static tools.dscode.common.GlobalConstants.BOOK_END;

/**
 * A concise quoted-text extractor that IS the map: placeholder -> inner
 * (unescaped) value - Parses ' " ` and ''' quoted segments (unescaped, matching
 * bookends) - Masks them with ␅␄…␅ placeholders - Lets you edit the outer
 * masked text and the inner values separately - Restores by re-wrapping inner
 * values with their original quote/bookend - Or force a uniform wrapper (', ",
 * or `); triple uses the ' escape char
 */
public final class QuoteParser extends LinkedHashMap<String, String> {
    // “Untypable” control pictures for placeholders
    private static final char MASK_CONTENT = '\u2404'; // ␄
    public static final char MASK_BOUNDARY = '\u2405'; // ␅

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

        // Pass 1: mask ' " `. Pass 2: mask ''' on the residual so triples
        // inside earlier segments are ignored. Both passes are linear scans
        // equivalent to the former quote-masking regexes, which recursed one
        // Java stack frame per inner character and overflowed on large values.
        ParsePass pass1 = maskSingleCharacterQuotes(input, n);
        ParsePass pass2 = maskTripleSingleQuotes(pass1.out.toString(), n);

        this.masked = pass2.out.toString();
        super.putAll(pass1.captured);
        super.putAll(pass2.captured);
        delimiterOf.putAll(pass1.delimiterByPlaceholder);
        delimiterOf.putAll(pass2.delimiterByPlaceholder);
    }

    private static ParsePass maskSingleCharacterQuotes(String in, AtomicInteger n) {
        StringBuffer out = new StringBuffer(in.length());
        Map<String, String> captured = new LinkedHashMap<>();
        Map<String, String> delims = new HashMap<>();
        int i = 0;
        while (i < in.length()) {
            char opening = in.charAt(i);
            if (isSingleQuote(opening) && !precededByBackslash(in, i)) {
                int close = findSingleQuoteClose(in, i, opening);
                if (close >= 0) {
                    int trailingBackslashes = countConsecutiveBackslashesBefore(in, close, i + 1);
                    String inner = unescapeSameQuote(
                            in.substring(i + 1, close - trailingBackslashes),
                            opening);
                    recordMaskedSpan(out, captured, delims, n, String.valueOf(opening), inner);
                    i = close + 1;
                    continue;
                }
            }
            out.append(opening);
            i++;
        }
        return new ParsePass(out, captured, delims);
    }

    private static ParsePass maskTripleSingleQuotes(String in, AtomicInteger n) {
        StringBuffer out = new StringBuffer(in.length());
        Map<String, String> captured = new LinkedHashMap<>();
        Map<String, String> delims = new HashMap<>();
        int i = 0;
        while (i < in.length()) {
            int opening = findTripleQuoteOpen(in, i);
            if (opening >= 0) {
                int contentStart = opening + TRIPLE_SINGLE.length();
                int close = findTripleQuoteClose(in, contentStart);
                if (close >= 0) {
                    int trailingBackslashes = countConsecutiveBackslashesBefore(in, close, contentStart);
                    String inner = unescapeSameQuote(
                            in.substring(contentStart, close - trailingBackslashes),
                            SINGLE);
                    recordMaskedSpan(out, captured, delims, n, TRIPLE_SINGLE, inner);
                    i = close + TRIPLE_SINGLE.length();
                    continue;
                }
            }
            out.append(in.charAt(i));
            i++;
        }
        return new ParsePass(out, captured, delims);
    }

    private static void recordMaskedSpan(
            StringBuffer out,
            Map<String, String> captured,
            Map<String, String> delims,
            AtomicInteger n,
            String opening,
            String inner
    ) {
        String placeholder = MASK_BOUNDARY
                + String.valueOf(MASK_CONTENT).repeat(n.getAndIncrement())
                + MASK_BOUNDARY;
        captured.put(placeholder, inner);
        delims.put(placeholder, opening);
        out.append(placeholder);
    }

    private static boolean isSingleQuote(char c) {
        return c == SINGLE || c == DOUBLE || c == BACKTICK;
    }

    private static boolean precededByBackslash(String in, int index) {
        return index > 0 && in.charAt(index - 1) == '\\';
    }

    private static boolean isRegexLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private static int countConsecutiveBackslashesBefore(String in, int index, int minIndex) {
        int count = 0;
        int i = index - 1;
        while (i >= minIndex && in.charAt(i) == '\\') {
            count++;
            i--;
        }
        return count;
    }

    /**
     * Leftmost closer matching the former single-quote regex: first unescaped
     * matching quote, with no regex line terminator in the span. Trailing even
     * backslashes before the closer are not part of the captured inner value.
     */
    private static int findSingleQuoteClose(String in, int open, char quote) {
        int i = open + 1;
        while (i < in.length()) {
            char c = in.charAt(i);
            if (isRegexLineTerminator(c)) {
                return -1;
            }
            if (c == quote && (countConsecutiveBackslashesBefore(in, i, open + 1) % 2) == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Leftmost {@code '''} start matching the former triple-quote regex,
     * including its greedy even-backslash prefix. Returns the index of
     * {@code '''}, or {@code -1} if this position cannot open a triple span.
     */
    private static int findTripleQuoteOpen(String in, int index) {
        if (precededByBackslash(in, index)) {
            return -1;
        }
        int backslashes = 0;
        int i = index;
        while (i < in.length() && in.charAt(i) == '\\') {
            backslashes++;
            i++;
        }
        int leadingPairs = backslashes - (backslashes % 2);
        int opening = index + leadingPairs;
        if (opening + TRIPLE_SINGLE.length() <= in.length()
                && in.startsWith(TRIPLE_SINGLE, opening)) {
            return opening;
        }
        return -1;
    }

    private static int findTripleQuoteClose(String in, int contentStart) {
        int i = contentStart;
        while (i < in.length()) {
            char c = in.charAt(i);
            if (isRegexLineTerminator(c)) {
                return -1;
            }
            if (in.startsWith(TRIPLE_SINGLE, i)
                    && (countConsecutiveBackslashesBefore(in, i, contentStart) % 2) == 0) {
                return i;
            }
            i++;
        }
        return -1;
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


    public String restoreAndStripBookEnds(String textWithPlaceholders) {
        return restoreFrom(textWithPlaceholders).replaceAll(BOOK_END, "");
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



    /**
     * Restore current masked text using current map values, wrapping with each
     * value's original bookend, PLUS an extra bookend STRING on the outside.
     *
     * Example (extraBookend = "&lt;&lt;"):
     *   placeholder -> &lt;&lt;'inner'&lt;&lt;  or  &lt;&lt;"inner"&lt;&lt;  or  &lt;&lt;`inner`&lt;&lt;  or  &lt;&lt;'''inner'''&lt;&lt;
     */
    public String restoreWithOuterBookend(String extraBookend) {
        return restoreFromWithOuterBookend(masked, extraBookend);
    }

    /**
     * Restore any text containing placeholders, honoring each placeholder's
     * original bookend, PLUS an extra bookend STRING on the outside.
     */
    public String restoreFromWithOuterBookend(String textWithPlaceholders, String extraBookend) {
        Objects.requireNonNull(textWithPlaceholders, "textWithPlaceholders");
        Objects.requireNonNull(extraBookend, "extraBookend");

        String out = textWithPlaceholders;
        List<String> keys = new ArrayList<>(super.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length())); // longest first

        for (String k : keys) {
            String delim = delimiterOf.get(k); // "'", "\"", "`", or "'''"
            String val = super.get(k);
            char escapeChar = delim.charAt(0); // escape only this char inside the value
            String escaped = escapeForQuote(val, escapeChar);

            String restored = extraBookend + delim + escaped + delim + extraBookend;
            out = out.replace(k, restored);
        }
        return out;
    }


}
