package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.Attribute;
import com.xpathy.XPathy;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.assertions.ValueWrapper;

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



    public final static  String from = ""
            + "\u0020"  // space
            + "\u0009"  // tab
            + ((char) 0x000A)  // LF
            + ((char) 0x000D)  // CR
            + "\u00A0"  // NBSP

            + "\u200B"  // ZWSP
            + "\u200C"  // ZWNJ
            + "\u200D"  // ZWJ
            + "\uFEFF"  // ZERO WIDTH NBSP/BOM

            + "\u1680"  // OGHAM SPACE MARK
            + "\u180E"  // MONGOLIAN VOWEL SEPARATOR (deprecated but still found)
            + "\u2000"  // EN QUAD
            + "\u2001"  // EM QUAD
            + "\u2002"  // EN SPACE
            + "\u2003"  // EM SPACE
            + "\u2004"  // THREE-PER-EM SPACE
            + "\u2005"  // FOUR-PER-EM SPACE
            + "\u2006"  // SIX-PER-EM SPACE
            + "\u2007"  // FIGURE SPACE
            + "\u2008"  // PUNCTUATION SPACE
            + "\u2009"  // THIN SPACE
            + "\u200A"  // HAIR SPACE
            + "\u2028"  // LINE SEPARATOR
            + "\u2029"  // PARAGRAPH SEPARATOR
            + "\u202F"  // NARROW NBSP
            + "\u205F"  // MMSP
            + "\u3000"; // IDEOGRAPHIC SPACE

    public final static String to = " ".repeat(from.length());

//    public enum TextMatchMode {
//        EQUALS,
//        STARTS_WITH,
//        CONTAINS,
//        ENDS_WITH
//    }


    public static XPathy deepNormalizedText(String rawText) {
        return deepNormalizedText(rawText, ExecutionDictionary.Op.EQUALS);
    }


    public static XPathy deepNormalizedText(ValueWrapper rawValue, ExecutionDictionary.Op mode) {
        return deepNormalizedText(rawValue.asNormalizedText(), mode);
    }

    public static XPathy deepNormalizedText(String rawText, ExecutionDictionary.Op mode) {
        if (rawText == null) {
            throw new IllegalArgumentException("rawText must not be null");
        }

        // Your existing Java-side normalization helper
        String expected = normalizeText(rawText);

        String from =
                " \t\u00A0\u200B\u200C\u200D\uFEFF\u1680\u180E" +
                        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006" +
                        "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000";

        String to = " ".repeat(from.length());

        String norm =
                "normalize-space(" +
                        "translate(string(.), " +
                        toXPathLiteral(from) + ", " +
                        toXPathLiteral(to) +
                        ")" +
                        ")";

        String predicate = switch (mode) {
            case STARTS_WITH ->
                    "starts-with(" + norm + ", " + toXPathLiteral(expected) + ")";

            case CONTAINS ->
                    "contains(" + norm + ", " + toXPathLiteral(expected) + ")";

            case ENDS_WITH ->
                    "substring(" +
                            norm + ", " +
                            "string-length(" + norm + ") - string-length(" + toXPathLiteral(expected) + ") + 1" +
                            ") = " + toXPathLiteral(expected);

            case EQUALS ->
                    norm + " = " + toXPathLiteral(expected);
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };

        return XPathy.from("(" + predicate + ")");
    }





    public static String normalizeText(String rawText) {
        return rawText.replaceAll("[" + from + "]", " ").replaceAll("\\s+", " ").strip();
    }




    private static String normalizeValue(Object value) {

        if (value == null) {
            return "";
        }

        String s = String.valueOf(value)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .strip();

        return s;
    }



    private static String toXPathLiteral(String s) {
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




    private static String normalizedAttrExpr(String attr) {
        return "normalize-space(translate(" + attr + ", " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }

    private static String normalizedTextExpr() {
        return "normalize-space(translate(string(.), " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
    }

    // =====================================================================
    //  NORMALIZED OPS
    // =====================================================================

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

        XPathy out = applyNormalizedOp(
                base,
                op,
                normalizedAttrExpr(attr),
                normalizeValue(value),
                "attr"
        );

        return out;
    }


    public static XPathy applyTextOp(XPathy base, ExecutionDictionary.Op op, Object value) {

        XPathy out = applyNormalizedOp(
                base,
                op,
                normalizedTextExpr(),
                normalizeValue(value),
                "text"
        );

        return out;
    }


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


