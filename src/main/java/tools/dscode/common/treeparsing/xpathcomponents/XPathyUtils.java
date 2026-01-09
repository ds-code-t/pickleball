package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.Attribute;
import com.xpathy.XPathy;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;

public final class XPathyUtils {

    private XPathyUtils() {
        // utility class
    }

    private static XPathy wrapWithPredicate(XPathy xp, String predicate) {
        if (xp == null) {
            throw new IllegalArgumentException("XPathy must not be null");
        }
        String raw = xp.getXpath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("XPathy xpath must not be null/blank");
        }
        // (<expr>)[<predicate>]
        return new XPathy("(" + raw + ")[" + predicate + "]");
    }

    // ---- Position helpers ---------------------------------------------------

    /**
     * Every nth match (1-based, multiples of step):
     * step=3 → 3rd, 6th, 9th, ...
     * Expression: (<xpath>)[position() mod step = 0]
     */
    public static XPathy everyNth(XPathy xp, int step) {
        if (step < 1) {
            throw new IllegalArgumentException("step must be >= 1");
        }
        return wrapWithPredicate(xp, "position() mod " + step + " = 0");
    }

    /**
     * Every nth match starting at a given 1-based offset:
     * start=1, step=3 → 1st, 4th, 7th, ...
     * start=2, step=3 → 2nd, 5th, 8th, ...
     * Expression: (<xpath>)[(position() - start) mod step = 0 and position() >= start]
     */
    public static XPathy everyNthFrom(XPathy xp, int start, int step) {
        if (start < 1) {
            throw new IllegalArgumentException("start must be >= 1");
        }
        if (step < 1) {
            throw new IllegalArgumentException("step must be >= 1");
        }
        String predicate =
                "(position() - " + start + ") mod " + step + " = 0 and position() >= " + start;
        return wrapWithPredicate(xp, predicate);
    }

    // =========================================================================
    //  WHITESPACE NORMALIZATION TABLE
    // =========================================================================

    public static final String from = ""
            + "\u0020"       // space
            + "\u0009"       // tab
            + ((char) 0x000A) // LF
            + ((char) 0x000D) // CR
            + "\u00A0"       // NBSP
            + "\u200B"       // ZWSP
            + "\u200C"       // ZWNJ
            + "\u200D"       // ZWJ
            + "\uFEFF"       // ZERO WIDTH NBSP/BOM
            + "\u1680"       // OGHAM SPACE MARK
            + "\u180E"       // MONGOLIAN VOWEL SEPARATOR (deprecated but still found)
            + "\u2000"       // EN QUAD
            + "\u2001"       // EM QUAD
            + "\u2002"       // EN SPACE
            + "\u2003"       // EM SPACE
            + "\u2004"       // THREE-PER-EM SPACE
            + "\u2005"       // FOUR-PER-EM SPACE
            + "\u2006"       // SIX-PER-EM SPACE
            + "\u2007"       // FIGURE SPACE
            + "\u2008"       // PUNCTUATION SPACE
            + "\u2009"       // THIN SPACE
            + "\u200A"       // HAIR SPACE
            + "\u2028"       // LINE SEPARATOR
            + "\u2029"       // PARAGRAPH SEPARATOR
            + "\u202F"       // NARROW NBSP
            + "\u205F"       // MMSP
            + "\u3000";      // IDEOGRAPHIC SPACE

    public static final String to = " ".repeat(from.length());

    // =========================================================================
    //  ASCII CASE-FOLD (XPath 1.0 friendly)
    // =========================================================================

    private static final String A2Z = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String a2z = "abcdefghijklmnopqrstuvwxyz";

    private static String caseFoldExpr(String expr) {
        return "translate(" + expr + ", " + toXPathLiteral(A2Z) + ", " + toXPathLiteral(a2z) + ")";
    }

    private static String caseFoldValue(String s) {
        return s == null ? null : s.toLowerCase();
    }

    // =========================================================================
    //  DEEP NORMALIZED TEXT
    // =========================================================================

    public static XPathy deepNormalizedText(String rawText) {
        return deepNormalizedText(rawText, ExecutionDictionary.Op.EQUALS);
    }

    /**
     * Type-driven behavior:
     * - DOUBLE_QUOTED (and all non-text types by default): normalized, case-sensitive
     * - SINGLE_QUOTED: normalized, case-insensitive
     * - BACK_TICKED: exact match (no normalization/trimming/casefold)
     */
    public static XPathy deepNormalizedText(ValueWrapper rawValue, ExecutionDictionary.Op mode) {
        if (rawValue == null) {
            throw new IllegalArgumentException("rawValue must not be null");
        }
        ExecutionDictionary.Op op = normalizeOp(mode);

        return switch (rawValue.type) {
            case BACK_TICKED -> deepExactText(rawValue.toString(), op);
            case SINGLE_QUOTED -> deepNormalizedTextInternal(rawValue.asNormalizedText(), op, true);
            default -> deepNormalizedTextInternal(rawValue.asNormalizedText(), op, false);
        };
    }

    /**
     * Preserves existing behavior (normalized, case-sensitive).
     */
    public static XPathy deepNormalizedText(String rawText, ExecutionDictionary.Op mode) {
        return deepNormalizedTextInternal(rawText, normalizeOp(mode), false);
    }

    private static XPathy deepNormalizedTextInternal(String rawText, ExecutionDictionary.Op mode, boolean caseInsensitive) {
        if (rawText == null) {
            throw new IllegalArgumentException("rawText must not be null");
        }

        // Existing Java-side normalization helper (kept)
        String expected = normalizeText(rawText);

        String norm = normalizedTextExpr(); // normalize-space(translate(string(.), ...))

        String haystack = caseInsensitive ? caseFoldExpr(norm) : norm;
        String needleVal = caseInsensitive ? caseFoldValue(expected) : expected;
        String needle = toXPathLiteral(needleVal);

        String predicate = buildTextPredicate(haystack, needle, mode);

        return XPathy.from("(" + predicate + ")");
    }

    private static XPathy deepExactText(String rawText, ExecutionDictionary.Op mode) {
        if (rawText == null) {
            throw new IllegalArgumentException("rawText must not be null");
        }
        String expr = "string(.)";
        String needle = toXPathLiteral(rawText);

        String predicate = buildTextPredicate(expr, needle, mode);

        return XPathy.from("(" + predicate + ")");
    }

    // =========================================================================
    //  NEW: ATTRIBUTE PREDICATE BUILDER (returns String predicate without [])
    // =========================================================================

    /**
     * Builds an XPath predicate (WITHOUT surrounding brackets) that matches an attribute
     * using ValueWrapper type semantics.
     *
     * - DOUBLE_QUOTED / DEFAULT / other: normalized, case-sensitive text compare
     * - SINGLE_QUOTED: normalized, case-insensitive text compare
     * - BACK_TICKED: exact (no normalization)
     * - NUMERIC: supports EQUALS, GT, GTE, LT, LTE using number(@attr)
     * - BOOLEAN: supports EQUALS only (presence semantics + common explicit values)
     */
    public static String attributePredicate(String attrName, ValueWrapper value, ExecutionDictionary.Op mode) {
        if (attrName == null || attrName.isBlank()) {
            throw new IllegalArgumentException("attrName must not be null/blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        ExecutionDictionary.Op op = normalizeOp(mode);

        String attrExpr = "@" + attrName.trim();

        return switch (value.type) {
            case NUMERIC -> buildNumericAttrPredicate(attrExpr, value, op);
            case BOOLEAN -> buildBooleanAttrPredicate(attrExpr, value, op);
            case BACK_TICKED -> buildAttrTextPredicate(attrExpr, value.toString(), op, false, false);
            case SINGLE_QUOTED -> buildAttrTextPredicate(attrExpr, value.asNormalizedText(), op, true, true);
            default -> buildAttrTextPredicate(attrExpr, value.asNormalizedText(), op, true, false);
        };
    }

    /**
     * Convenience: apply attribute predicate to a base XPathy, returning a new XPathy.
     * Does not replace or modify existing applyAttrOp overloads.
     */
    public static XPathy applyAttrPredicate(XPathy base, String attrName, ValueWrapper value, ExecutionDictionary.Op mode) {
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        String pred = attributePredicate(attrName, value, mode);
        return XPathy.from("(" + base.getXpath().trim() + ")[" + pred + "]");
    }

    // =========================================================================
    //  NORMALIZATION + LITERALS
    // =========================================================================

    public static String normalizeText(String rawText) {
        return rawText.replaceAll("[" + from + "]", " ").replaceAll("\\s+", " ").strip();
    }

    private static String normalizeValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String toXPathLiteral(String s) {
        if (s == null) return "''";
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }

        // Contains both ' and " → use concat('foo', "'", 'bar')
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1); // keep empties
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", \"'\", ");
            }
            sb.append("'").append(parts[i]).append("'");
        }
        sb.append(")");
        return sb.toString();
    }

    private static String normalizedAttrExpr(String attrExpr) {
        return "normalize-space(translate(" + attrExpr + ", " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }

    private static String normalizedTextExpr() {
        return "normalize-space(translate(string(.), " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }

    // =========================================================================
    //  PREDICATE BUILDERS (consolidated)
    // =========================================================================

    private static ExecutionDictionary.Op normalizeOp(ExecutionDictionary.Op op) {
        if (op == null || op == ExecutionDictionary.Op.DEFAULT) {
            return ExecutionDictionary.Op.EQUALS;
        }
        return op;
    }

    /**
     * Builds a text predicate using the given haystack expression and a literal needle.
     * needleLiteral must already be a valid XPath literal (e.g. 'foo', "foo", concat(...)).
     */
    private static String buildTextPredicate(String haystackExpr, String needleLiteral, ExecutionDictionary.Op op) {
        return switch (op) {
            case STARTS_WITH ->
                    "starts-with(" + haystackExpr + ", " + needleLiteral + ")";
            case CONTAINS ->
                    "contains(" + haystackExpr + ", " + needleLiteral + ")";
            case ENDS_WITH ->
                    "substring(" +
                            haystackExpr + ", " +
                            "string-length(" + haystackExpr + ") - string-length(" + needleLiteral + ") + 1" +
                            ") = " + needleLiteral;
            case EQUALS ->
                    haystackExpr + " = " + needleLiteral;
            default ->
                    throw new IllegalArgumentException("Unsupported mode: " + op);
        };
    }

    private static String buildAttrTextPredicate(
            String attrExpr,
            String input,
            ExecutionDictionary.Op op,
            boolean normalized,
            boolean caseInsensitive
    ) {
        if (input == null) {
            // Only sensible for equals: absent attr
            if (op == ExecutionDictionary.Op.EQUALS) {
                return "not(" + attrExpr + ")";
            }
            throw new IllegalArgumentException("Cannot apply " + op + " to null attribute value");
        }

        String expr;
        String expected;

        if (normalized) {
            expr = normalizedAttrExpr(attrExpr);
            expected = normalizeText(input);
        } else {
            expr = attrExpr;     // exact (BACK_TICKED)
            expected = input;
        }

        if (caseInsensitive) {
            expr = caseFoldExpr(expr);
            expected = caseFoldValue(expected);
        }

        String needle = toXPathLiteral(expected);

        return buildTextPredicate(expr, needle, op);
    }

    private static String buildNumericAttrPredicate(String attrExpr, ValueWrapper value, ExecutionDictionary.Op op) {
        String lhs = "number(" + attrExpr + ")";
        String rhs = value.asBigInteger().toString();

        return switch (op) {
            case EQUALS -> lhs + " = " + rhs;
            case GT -> lhs + " > " + rhs;
            case GTE -> lhs + " >= " + rhs;
            case LT -> lhs + " < " + rhs;
            case LTE -> lhs + " <= " + rhs;
            default -> throw new IllegalArgumentException("Unsupported numeric op: " + op);
        };
    }

    private static String buildBooleanAttrPredicate(String attrExpr, ValueWrapper value, ExecutionDictionary.Op op) {
        if (op != ExecutionDictionary.Op.EQUALS) {
            throw new IllegalArgumentException("Unsupported boolean op: " + op);
        }

        String raw = value.toNonNullString().trim().toLowerCase();

        boolean wantTrue;
        if (raw.isBlank()) {
            wantTrue = true; // presence semantics
        } else if (raw.equals("true") || raw.equals("1") || raw.equals("yes") || raw.equals("y") || raw.equals("on")) {
            wantTrue = true;
        } else if (raw.equals("false") || raw.equals("0") || raw.equals("no") || raw.equals("n") || raw.equals("off")) {
            wantTrue = false;
        } else {
            // Unknown token: treat as normalized, case-insensitive string match
            return buildAttrTextPredicate(attrExpr, value.asNormalizedText(), ExecutionDictionary.Op.EQUALS, true, true);
        }

        // Normalize + casefold for explicit string values some frameworks use.
        String norm = normalizedAttrExpr(attrExpr);
        String ciNorm = caseFoldExpr(norm);

        if (wantTrue) {
            // present AND not explicitly false/0
            return "(" + attrExpr + " and not(" +
                    ciNorm + " = " + toXPathLiteral("false") +
                    " or " + ciNorm + " = " + toXPathLiteral("0") +
                    "))";
        } else {
            // absent OR explicitly false/0/empty
            return "(" +
                    "not(" + attrExpr + ")" +
                    " or " + ciNorm + " = " + toXPathLiteral("false") +
                    " or " + ciNorm + " = " + toXPathLiteral("0") +
                    " or " + norm + " = " + toXPathLiteral("") +
                    ")";
        }
    }

    // =========================================================================
    //  EXISTING NORMALIZED OPS (kept, non-breaking)
    // =========================================================================

    private static XPathy applyNormalizedOp(
            XPathy base,
            ExecutionDictionary.Op op,
            String normExpr,
            String normalizedValue,
            String label
    ) {
        if (op == null) {
            return base;
        }

        String literal = toXPathLiteral(normalizedValue);

        String predicate = switch (op) {
            case EQUALS      -> "[" + normExpr + " = " + literal + "]";
            case CONTAINS    -> "[contains(" + normExpr + ", " + literal + ")]";
            case STARTS_WITH -> "[starts-with(" + normExpr + ", " + literal + ")]";
            default -> {
                String msg = "Unsupported " + label + " op: " + op;
                throw new IllegalArgumentException(msg);
            }
        };

        String out = "(" + base.getXpath().trim() + ")" + predicate;

        return XPathy.from(out);
    }

    public static XPathy applyAttrOp(XPathy base, String attr, ExecutionDictionary.Op op, Object value) {
        if (attr == null) {
            return base;
        }

        return applyNormalizedOp(
                base,
                op,
                normalizedAttrExpr(attr),
                normalizeValue(value),
                "attr"
        );
    }

    public static XPathy applyTextOp(XPathy base, ExecutionDictionary.Op op, Object value) {
        return applyNormalizedOp(
                base,
                op,
                normalizedTextExpr(),
                normalizeValue(value),
                "text"
        );
    }

    // =========================================================================
    //  DEEPEST MATCH LOGIC (unchanged)
    // =========================================================================

    public static XPathy maybeDeepestMatches(XPathy xpathy) {
        return XPathy.from(maybeDeepestMatches(xpathy.getXpath()));
    }

    /**
     * If the XPath looks like a context-independent, absolute expression
     * (e.g. //div[@x], /html/body//a, (//div | //span[@x])),
     * wrap it so that it returns only nodes that are NOT ancestors of
     * any other node in the original result.
     *
     * Otherwise, return the original XPath unchanged.
     */
    public static String maybeDeepestMatches(String xpath) {
        if (xpath == null || xpath.isBlank()) return xpath;

        String normalized = xpath.strip();

        if (!looksAbsolutelyScoped(normalized)) {
            // Context-dependent or weird-looking → don't transform
            return xpath;
        }

        if (looksLikeItUsesRelativeDots(normalized)) {
            // Mixed absolute + .// or ./ etc → too risky → don't transform
            return xpath;
        }

        String wrapped = "(" + xpath + ")";
        return wrapped +
                "[not(descendant::*[count(. | " + wrapped + ") = count(" + wrapped + ")])]";
    }

    /**
     * Very simple heuristic:
     *   - Strip leading whitespace and '('
     *   - Expression is considered absolute if first non-paren char is '/'
     *     (covers both '/' and '//' and things like (/html|//div)).
     */
    private static boolean looksAbsolutelyScoped(String xpath) {
        int i = 0;
        int len = xpath.length();
        while (i < len && Character.isWhitespace(xpath.charAt(i))) {
            i++;
        }
        while (i < len && xpath.charAt(i) == '(') {
            i++;
            while (i < len && Character.isWhitespace(xpath.charAt(i))) {
                i++;
            }
        }
        if (i >= len) return false;
        char c = xpath.charAt(i);
        return c == '/';
    }

    /**
     * Cheap disqualifier: if we see obvious relative-dot patterns anywhere
     * (./foo, .//bar, .., etc.), we bail out and don't transform.
     *
     * This is intentionally conservative: it may reject some XPaths
     * that would actually be safe, but it won't break any.
     */
    private static boolean looksLikeItUsesRelativeDots(String xpath) {
        String s = xpath;
        return s.contains(".//")
                || s.contains("./")
                || s.contains("..")
                || s.contains(" | .")  // union with a dot-relative part
                || s.startsWith(".")   // purely relative at top-level
                ;
    }
}
