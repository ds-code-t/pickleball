package tools.dscode.common.treeparsing.xpathcomponents;


import com.xpathy.XPathy;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.util.Objects;
import java.util.Set;

import static com.xpathy.Tag.any;
import static tools.dscode.common.domoperations.elementstates.VisibilityConditions.noDisplay;

public final class XPathyUtils {

    private XPathyUtils() {
        // utility class
    }

    // =========================================================================
    //  NON-MATCHING UTILITIES (kept)
    // =========================================================================

    private static XPathy wrapWithPredicate(XPathy xp, String predicate) {
        if (xp == null) throw new IllegalArgumentException("XPathy must not be null");
        String raw = xp.getXpath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("XPathy xpath must not be null/blank");
        }
        return new XPathy("(" + raw + ")[" + predicate + "]");
    }

    /**
     * Every nth match (1-based, multiples of step):
     * step=3 → 3rd, 6th, 9th, ...
     * Expression: (<xpath>)[position() mod step = 0]
     */
    public static XPathy everyNth(XPathy xp, int step) {
        if (step < 1) throw new IllegalArgumentException("step must be >= 1");
        return wrapWithPredicate(xp, "position() mod " + step + " = 0");
    }

    /**
     * Every nth match starting at a given 1-based offset:
     * start=1, step=3 → 1st, 4th, 7th, ...
     * start=2, step=3 → 2nd, 5th, 8th, ...
     * Expression: (<xpath>)[(position() - start) mod step = 0 and position() >= start]
     */
    public static XPathy everyNthFrom(XPathy xp, int start, int step) {
        if (start < 1) throw new IllegalArgumentException("start must be >= 1");
        if (step < 1) throw new IllegalArgumentException("step must be >= 1");
        String predicate = "(position() - " + start + ") mod " + step + " = 0 and position() >= " + start;
        return wrapWithPredicate(xp, predicate);
    }

    // =========================================================================
    //  WHITESPACE NORMALIZATION TABLE (kept public for ValueWrapper static import)
    // =========================================================================

    public static final String from = ""
            + "\u0020"        // space
            + "\u0009"        // tab
            + ((char) 0x000A) // LF
            + ((char) 0x000D) // CR
            + "\u00A0"        // NBSP
            + "\u200B"        // ZWSP
            + "\u200C"        // ZWNJ
            + "\u200D"        // ZWJ
            + "\uFEFF"        // ZERO WIDTH NBSP/BOM
            + "\u1680"        // OGHAM SPACE MARK
            + "\u180E"        // MONGOLIAN VOWEL SEPARATOR (deprecated but still found)
            + "\u2000"        // EN QUAD
            + "\u2001"        // EM QUAD
            + "\u2002"        // EN SPACE
            + "\u2003"        // EM SPACE
            + "\u2004"        // THREE-PER-EM SPACE
            + "\u2005"        // FOUR-PER-EM SPACE
            + "\u2006"        // SIX-PER-EM SPACE
            + "\u2007"        // FIGURE SPACE
            + "\u2008"        // PUNCTUATION SPACE
            + "\u2009"        // THIN SPACE
            + "\u200A"        // HAIR SPACE
            + "\u2028"        // LINE SEPARATOR
            + "\u2029"        // PARAGRAPH SEPARATOR
            + "\u202F"        // NARROW NBSP
            + "\u205F"        // MMSP
            + "\u3000";       // IDEOGRAPHIC SPACE

    public static final String to = " ".repeat(from.length());

    /**
     * Java-side text normalization (kept public; ValueWrapper imports it).
     */
    public static String normalizeText(String rawText) {
        if (rawText == null) return null;
        return rawText.replaceAll("[" + from + "]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    // =========================================================================
    //  PUBLIC MATCHING APIs (ValueWrapper-only)
    // =========================================================================

    public static String noDisplayPredicate = any.byCondition(noDisplay).getXpath().replaceFirst("//\\*", "");

    public static String deepNormalizedText(ValueWrapper value, ExecutionDictionary.Op mode) {
        Objects.requireNonNull(value, "value must not be null");
        ExecutionDictionary.Op op = normalizeOp(mode);

        String predicate = buildDeepTextPredicate(value, op);
        return "[" + predicate + "]";
    }

    public static String deepNormalizedVisibleText(ValueWrapper value, ExecutionDictionary.Op mode) {
        return deepNormalizedText(value, mode) + noDisplayPredicate;
    }

    public static String descendantDeepNormalizedVisibleText(ValueWrapper value, ExecutionDictionary.Op mode) {
        return  "[descendant-or-self::node()[" + deepNormalizedVisibleText(value, mode) + "]]";
    }


    /**
     * Builds a predicate (WITHOUT surrounding brackets) that matches an attribute
     * using ValueWrapper type semantics.
     *
     * - Text types follow the same rules as deepNormalizedText(ValueWrapper,...)
     * - NUMERIC supports EQUALS, GT, GTE, LT, LTE using number(@attr)
     * - BOOLEAN supports EQUALS using presence semantics + explicit true/false-ish values
     */
    public static String attributePredicate(String attrName, ValueWrapper value, ExecutionDictionary.Op mode) {
        if (attrName == null || attrName.isBlank()) throw new IllegalArgumentException("attrName must not be null/blank");
        Objects.requireNonNull(value, "value must not be null");

        ExecutionDictionary.Op op = normalizeOp(mode);
        String attrExpr = "@" + attrName.trim();

        return buildAttributePredicate(attrExpr, value, op);
    }

    /**
     * Apply a ValueWrapper-based text predicate to an existing base XPath.
     */
    public static XPathy applyTextPredicate(XPathy base, ValueWrapper value, ExecutionDictionary.Op mode) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(value, "value must not be null");

        ExecutionDictionary.Op op = normalizeOp(mode);
        String pred = buildDeepTextPredicate(value, op);
        return XPathy.from( base.getXpath().trim() + "[" + pred + "]");
//        return XPathy.from("(" + base.getXpath().trim() + ")[" + pred + "]");
    }

    /**
     * Apply a ValueWrapper-based attribute predicate to an existing base XPath.
     */
    public static XPathy applyAttrPredicate(XPathy base, String attrName, ValueWrapper value, ExecutionDictionary.Op mode) {
        Objects.requireNonNull(base, "base must not be null");
        if (attrName == null || attrName.isBlank()) throw new IllegalArgumentException("attrName must not be null/blank");
        Objects.requireNonNull(value, "value must not be null");

        ExecutionDictionary.Op op = normalizeOp(mode);
        String pred = buildAttributePredicate("@" + attrName.trim(), value, op);
        return XPathy.from(base.getXpath().trim() + "[" + pred + "]");
//        return XPathy.from("(" + base.getXpath().trim() + ")[" + pred + "]");
    }

    // =========================================================================
    //  CONSOLIDATED CORE MATCH LOGIC
    // =========================================================================

    private static ExecutionDictionary.Op normalizeOp(ExecutionDictionary.Op op) {
        if (op == null || op == ExecutionDictionary.Op.DEFAULT) return ExecutionDictionary.Op.EQUALS;
        return op;
    }

    // XPath 1.0 ASCII-only case folding
    private static final String A2Z = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String a2z = "abcdefghijklmnopqrstuvwxyz";

    /**
     * Characters we will REMOVE for TILDE_QUOTED comparisons.
     * - digits
     * - common ASCII punctuation/symbols
     * - space (we remove space separately too, but harmless here)
     *
     * IMPORTANT: This is ASCII-focused because XPath 1.0 has limited string tooling.
     */
    private static final String STRIP_NON_LETTERS_ASCII =
            "0123456789" +
                    " \t\r\n" +
                    "!\"#$%&()*+,./:;<=>?@[\\]^`{|}" + // no apostrophe
                    "_-";

    private static String lettersOnlyCaseFoldExpr(String expr) {
        String norm = normalizeWhitespaceExpr(expr);
        norm = caseFoldExpr(norm);
        // strip digits/punct/underscore/dash/space
        return "translate(" + norm + ", " + toXPathLiteral(STRIP_NON_LETTERS_ASCII) + ", '')";
    }

    private static String caseFoldExpr(String expr) {
        return "translate(" + expr + ", " + toXPathLiteral(A2Z) + ", " + toXPathLiteral(a2z) + ")";
    }

    private static String normalizeWhitespaceExpr(String expr) {
        // Your existing whitespace normalization strategy
        return "normalize-space(translate(" + expr + ", " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }


    /** Java-side counterpart for expected values (TILDE_QUOTED). */
    private static String normalizeLettersOnlyExpected(String s) {
        if (s == null) return "";
        String norm = normalizeText(s);
        if (norm == null) return "";
        // ASCII letters only, lower-case
        return norm.toLowerCase().replaceAll("[^a-z]+", "");
    }

    private static String caseFoldValue(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static String rawValue(ValueWrapper vw) {
        Object v = vw.getValue();
        return v == null ? null : v.toString();
    }

    private static boolean isTextQuotedType(ValueWrapper.ValueTypes t) {
        return t == ValueWrapper.ValueTypes.DOUBLE_QUOTED
                || t == ValueWrapper.ValueTypes.SINGLE_QUOTED
                || t == ValueWrapper.ValueTypes.BACK_TICKED
                || t == ValueWrapper.ValueTypes.TILDE_QUOTED;
    }

    private static String buildDeepTextPredicate(ValueWrapper value, ExecutionDictionary.Op op) {
        ValueWrapper.ValueTypes t = value.type;

        boolean exact = (t == ValueWrapper.ValueTypes.BACK_TICKED);
        boolean tilde = (t == ValueWrapper.ValueTypes.TILDE_QUOTED);
        boolean caseInsensitive = (t == ValueWrapper.ValueTypes.SINGLE_QUOTED) || tilde;

        // Build the DOM-side expression evaluated per TEXT NODE (.)
        String textNodeExpr;
        if (exact) {
            textNodeExpr = "string(.)";
        } else if (tilde) {
            // fuzzy letters-only matching
            textNodeExpr = lettersOnlyCaseFoldExpr("string(.)");
        } else {
            // your existing normalized-text matching
            textNodeExpr = normalizeWhitespaceExpr("string(.)");
            if (caseInsensitive) {
                textNodeExpr = caseFoldExpr(textNodeExpr);
            }
        }

        // Expected value
        String expected = rawValue(value);
        if (expected == null) expected = "";

        if (exact) {
            // no normalization
        } else if (tilde) {
            expected = normalizeLettersOnlyExpected(expected);
        } else {
            expected = normalizeText(expected);
            if (expected == null) expected = "";
            if (caseInsensitive) expected = caseFoldValue(expected);
        }

        String needleLiteral = toXPathLiteral(expected);
        return buildStringPredicate(textNodeExpr, needleLiteral, op);
    }


    private static String buildAttributePredicate(String attrExpr, ValueWrapper value, ExecutionDictionary.Op op) {
        ValueWrapper.ValueTypes t = value.type;

        if (t == ValueWrapper.ValueTypes.NUMERIC) {
            return buildNumericPredicate("number(" + attrExpr + ")", value, op);
        }

        if (t == ValueWrapper.ValueTypes.BOOLEAN) {
            return buildBooleanAttrPredicate(attrExpr, value, op);
        }

        if (t == ValueWrapper.ValueTypes.BACK_TICKED) {
            String expected = rawValue(value);
            if (expected == null) {
                if (op == ExecutionDictionary.Op.EQUALS) return "not(" + attrExpr + ")";
                throw new IllegalArgumentException("Cannot apply " + op + " to null BACK_TICKED attribute value");
            }
            return buildStringPredicate(attrExpr, toXPathLiteral(expected), op);
        }

        boolean tilde = (t == ValueWrapper.ValueTypes.TILDE_QUOTED);
        boolean caseInsensitive = (t == ValueWrapper.ValueTypes.SINGLE_QUOTED) || tilde;

        String domExpr;
        String expectedRaw = rawValue(value);

        if (expectedRaw == null) {
            if (op == ExecutionDictionary.Op.EQUALS) return "not(" + attrExpr + ")";
            throw new IllegalArgumentException("Cannot apply " + op + " to null attribute value");
        }

        if (tilde) {
            domExpr = lettersOnlyCaseFoldExpr(attrExpr);
            String expected = normalizeLettersOnlyExpected(expectedRaw);
            return buildStringPredicate(domExpr, toXPathLiteral(expected), op);
        }

        // existing normalized attribute comparisons
        domExpr = normalizedAttrExpr(attrExpr);
        String expected = normalizeText(expectedRaw);

        if (expected == null) {
            if (op == ExecutionDictionary.Op.EQUALS) return "not(" + attrExpr + ")";
            throw new IllegalArgumentException("Cannot apply " + op + " to null attribute value");
        }

        if (caseInsensitive) {
            domExpr = caseFoldExpr(domExpr);
            expected = caseFoldValue(expected);
        }

        return buildStringPredicate(domExpr, toXPathLiteral(expected), op);
    }

    private static String buildStringPredicate(String haystackExpr, String needleLiteral, ExecutionDictionary.Op op) {
        return switch (op) {
            case EQUALS -> haystackExpr + " = " + needleLiteral;
            case CONTAINS -> "contains(" + haystackExpr + ", " + needleLiteral + ")";
            case STARTS_WITH -> "starts-with(" + haystackExpr + ", " + needleLiteral + ")";
            case ENDS_WITH -> "substring(" +
                    haystackExpr + ", " +
                    "string-length(" + haystackExpr + ") - string-length(" + needleLiteral + ") + 1" +
                    ") = " + needleLiteral;
            default -> throw new IllegalArgumentException("Unsupported string op: " + op);
        };
    }

    private static String buildNumericPredicate(String lhsNumberExpr, ValueWrapper value, ExecutionDictionary.Op op) {
        // Uses your ValueWrapper numeric parsing (BigInteger-style); consistent with your semantics.
        String rhs = value.asBigInteger().toString();

        return switch (op) {
            case EQUALS -> lhsNumberExpr + " = " + rhs;
            case GT -> lhsNumberExpr + " > " + rhs;
            case GTE -> lhsNumberExpr + " >= " + rhs;
            case LT -> lhsNumberExpr + " < " + rhs;
            case LTE -> lhsNumberExpr + " <= " + rhs;
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
            // e.g., @disabled (presence) semantics
            wantTrue = true;
        } else if (raw.equals("true") || raw.equals("1") || raw.equals("yes") || raw.equals("y") || raw.equals("on")) {
            wantTrue = true;
        } else if (raw.equals("false") || raw.equals("0") || raw.equals("no") || raw.equals("n") || raw.equals("off")) {
            wantTrue = false;
        } else {
            // Unknown token -> treat as normalized, case-insensitive string compare on the attribute value
            String domExpr = caseFoldExpr(normalizedAttrExpr(attrExpr));
            String expected = caseFoldValue(normalizeText(raw));
            return domExpr + " = " + toXPathLiteral(expected);
        }

        // Handle frameworks that set explicit "false"/"0" strings, while still supporting presence semantics.
        String norm = normalizedAttrExpr(attrExpr);
        String ciNorm = caseFoldExpr(norm);

        if (wantTrue) {
            // attribute exists AND not explicitly false/0
            return "(" + attrExpr + " and not(" +
                    ciNorm + " = " + toXPathLiteral("false") +
                    " or " + ciNorm + " = " + toXPathLiteral("0") +
                    "))";
        } else {
            // attribute missing OR explicitly false/0/empty
            return "(" +
                    "not(" + attrExpr + ")" +
                    " or " + ciNorm + " = " + toXPathLiteral("false") +
                    " or " + ciNorm + " = " + toXPathLiteral("0") +
                    " or " + norm + " = " + toXPathLiteral("") +
                    ")";
        }
    }

    // =========================================================================
    //  XPATH NORMALIZATION EXPRESSIONS (single source of truth)
    // =========================================================================

    private static String normalizedAttrExpr(String attrExpr) {
        return "normalize-space(translate(" + attrExpr + ", " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }

    private static String normalizedTextExpr() {
        return "normalize-space(translate(string(.), " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }

    private static String toXPathLiteral(String s) {
        if (s == null) return "''";
        if (!s.contains("'")) return "'" + s + "'";
        if (!s.contains("\"")) return "\"" + s + "\"";

        // Contains both ' and " → use concat('foo', "'", 'bar')
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append("'").append(parts[i]).append("'");
        }
        sb.append(")");
        return sb.toString();
    }

    // =========================================================================
    //  DEEPEST MATCH LOGIC (unchanged)
    // =========================================================================

    public static XPathy deepestOnlyXPath(XPathy xpathy) {
        return XPathy.from(deepestOnlyXPath(xpathy.getXpath()));
    }


    /**
     * Given an XPath that selects a set of nodes, returns a new XPath that selects
     * only the "deepest" matches (i.e., excludes any match that contains another
     * match inside it).
     *
     * Assumptions:
     * - XPath 1.0 compatible
     * - Input XPath starts with "//" (as requested)
     * - Input XPath contains at least one predicate "[...]" that defines the match
     */
    public static String deepestOnlyXPath(String xpath1) {
        if (xpath1 == null) {
            throw new IllegalArgumentException("xpath1 cannot be null");
        }

        String x = xpath1.trim();
        if (!x.startsWith("//")) {
            throw new IllegalArgumentException("Expected xpath1 to start with '//' but was: " + x);
        }

        int firstBracket = x.indexOf('[');
        if (firstBracket < 0) {
            // No predicate to reuse -> can't safely build a "same match" test without parsing.
            // Return unchanged rather than generating a wrong XPath.
            return x;
        }

        // Reuse the predicate part as the "same match" definition, but relative to the candidate node.
        String predicate = x.substring(firstBracket);          // "[ ... ]"
        String inner = "descendant::*" + predicate;            // "descendant::*[ ... ]"

        // Exclude any node that has a descendant which would also match the same predicate.
        return x + "[not(" + inner + ")]";
    }

    public static String customElementSuffixPredicate(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            throw new IllegalArgumentException("Suffix must not be null or blank");
        }

        String clean = suffix.trim();

        // XPath string literal safe quoting
        String literal = toXPathLiteral("-" + clean);

        int len = clean.length() + 1; // +1 for leading hyphen

        return "[contains(local-name(), '-') and " +
                "substring(local-name(), string-length(local-name()) - " + (len - 1) + ") = " +
                literal +
                "]";
    }

}
