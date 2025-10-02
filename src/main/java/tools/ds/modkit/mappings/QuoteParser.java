package tools.ds.modkit.mappings;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A concise quoted-text extractor that IS the map:
 *   placeholder -> inner (unescaped) value
 *
 * - Parses ' " ` quoted segments (unescaped, matching bookends)
 * - Masks them with ␅␄…␅ placeholders
 * - Lets you edit the outer masked text and the inner values separately
 * - Restores by re-wrapping inner values with their original quote char
 */
public final class QuoteParser extends LinkedHashMap<String, String> {
    private static final String QUOTED =
            "(?<!\\\\)(['\"`])((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1";
    private static final Pattern P = Pattern.compile(QUOTED);

    // “Untypable” control pictures for placeholders
    private static final char MASK_CONTENT  = '\u2404'; // ␄
    private static final char MASK_BOUNDARY = '\u2405'; // ␅

    public static final char SINGLE = '\'';
    public static final char DOUBLE = '"';
    public static final char BACKTICK = '`';

    private final String original;
    private String masked; // mutable
    private final Map<String, Character> quoteOf = new HashMap<>();

    public QuoteParser(String input) {
        this.original = Objects.requireNonNull(input, "input");
        AtomicInteger n = new AtomicInteger(1);

        Matcher m = P.matcher(input);
        StringBuffer out = new StringBuffer();

        while (m.find()) {
            char q = m.group(1).charAt(0);
            String inner = unescapeSameQuote(m.group(2), q);

            String placeholder = MASK_BOUNDARY
                    + String.valueOf(MASK_CONTENT).repeat(n.getAndIncrement())
                    + MASK_BOUNDARY;

            // store inner (unescaped) value
            super.put(placeholder, inner);
            quoteOf.put(placeholder, q);

            m.appendReplacement(out, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(out);
        this.masked = out.toString();
    }

    // ---- accessors / mutators ----
    public String original() { return original; }
    public String masked()   { return masked;  }
    public void setMasked(String newMasked) { this.masked = Objects.requireNonNull(newMasked); }

    // ---- concise filtered views (by quote type) ----
    public List<Map.Entry<String,String>> entriesSingle()  { return entriesByQuote(SINGLE); }
    public List<Map.Entry<String,String>> entriesDouble()  { return entriesByQuote(DOUBLE); }
    public List<Map.Entry<String,String>> entriesBacktick(){ return entriesByQuote(BACKTICK); }

    private List<Map.Entry<String,String>> entriesByQuote(char q) {
        List<Map.Entry<String,String>> list = new ArrayList<>();
        for (Map.Entry<String,String> e : super.entrySet()) {
            if (quoteOf.get(e.getKey()) == q) list.add(e);
        }
        return list;
    }

// ---- restore ----
    /** Restore current masked text using current map values, wrapping with their original quote chars.
     *  Any occurrences of that wrapping quote inside each value are escaped at restore time. */
    public String restore() { return restoreFrom(masked); }

    /** Restore any text that contains these placeholders, honoring each placeholder's original quote type.
     *  Escapes ONLY the same quote as the wrapper for each segment. */
    public String restoreFrom(String textWithPlaceholders) {
        String out = textWithPlaceholders;
        // replace longer keys first to avoid partial overlaps
        List<String> keys = new ArrayList<>(super.keySet());
        keys.sort((a,b) -> Integer.compare(b.length(), a.length()));
        for (String k : keys) {
            char wrap = quoteOf.get(k);              // original bookend for this placeholder
            String val = super.get(k);
            String escaped = escapeForQuote(val, wrap);
            out = out.replace(k, wrap + escaped + wrap);
        }
        return out;
    }

    /** Restore using a specific quote for ALL segments (', ", or `).
     *  Escapes ONLY that quote inside the values; other quote chars remain unescaped. */
    public String restoreWithQuote(char q) {
        if (q != SINGLE && q != DOUBLE && q != BACKTICK) {
            throw new IllegalArgumentException("Quote must be ', \", or `");
        }
        String out = masked;
        List<String> keys = new ArrayList<>(super.keySet());
        keys.sort((a,b) -> Integer.compare(b.length(), a.length()));
        for (String k : keys) {
            String val = super.get(k);
            String escaped = escapeForQuote(val, q);
            out = out.replace(k, q + escaped + q);
        }
        return out;
    }

    public String restoreAsDouble()  { return restoreWithQuote(DOUBLE); }
    public String restoreAsSingle()  { return restoreWithQuote(SINGLE); }
    public String restoreAsBacktick(){ return restoreWithQuote(BACKTICK); }

// ---- helper ----

    public char quoteTypeOf(String placeholder) {
        Character q = quoteOf.get(placeholder);
        if (q == null) throw new IllegalArgumentException("Unknown placeholder: " + placeholder);
        return q;
    }

    /** Escape ONLY the given quote char inside s (e.g., " -> \", ' -> \', ` -> \`) */
    private static String escapeForQuote(String s, char q) {
        if (s == null || s.isEmpty()) return s;
        return s.replace(String.valueOf(q), "\\" + q);
    }


    // ---- helper ----
    private static String unescapeSameQuote(String s, char q) {
        // turn \" -> " (or \' -> ', \` -> `)
        return s.replace("\\" + q, String.valueOf(q));
    }
}
