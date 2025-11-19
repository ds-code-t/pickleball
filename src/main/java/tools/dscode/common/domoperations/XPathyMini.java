package tools.dscode.common.domoperations;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;

import java.util.function.Function;
import java.util.function.Supplier;

public final class XPathyMini {
    private XPathyMini() {}

    // =========================================================
    // Normalization helpers (shared by text & attr)
    // =========================================================

    /**
     * Normalize a Java-side value the same way we normalize DOM text/attr:
     *  - replace NBSP with normal spaces
     *  - collapse all runs of whitespace to a single space
     *  - trim leading/trailing whitespace
     */
    private static String normalizeValue(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        // Map NBSP to normal space first
        s = s.replace('\u00A0', ' ');
        // Collapse all whitespace runs to a single space
        s = s.replaceAll("\\s+", " ");
        // Trim ends
        return s.strip();
    }

    /**
     * Safely turn a Java String into an XPath string literal.
     * Uses '...' when possible and concat(...) when the text contains both
     * single and double quotes.
     */
    private static String toXPathLiteral(String s) {
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }

        // Contains both ' and " → use concat('foo', "'", 'bar')
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1); // keep empty parts
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", \"'\", ");
            }
            sb.append('\'').append(parts[i]).append('\'');
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Wrap an existing XPathy in a new XPath with an extra predicate appended.
     * We always wrap the base XPath in parentheses so predicates apply
     * correctly even when the base contains unions ("|") or other constructs.
     */
    private static XPathy withPredicate(XPathy base, String predicate) {
        String baseXpath = base.toString().trim();
        String combined = "(" + baseXpath + ")" + predicate;
        return XPathy.from(combined);
    }

    // Normalized expression builders
    private static String normalizedAttrExpr(Attribute attr) {
        String attrName = attr.toString();
        return "normalize-space(translate(@" + attrName
                + ", ' \t\r\n\u00A0', '     '))";
    }

    private static String normalizedTextExpr() {
        // string(.) flattens descendant text
        return "normalize-space(translate(string(.), ' \t\r\n\u00A0', '     '))";
    }

    /**
     * Common implementation for EQUALS / CONTAINS / STARTS_WITH,
     * shared by attribute and text cases.
     */
    private static XPathy applyNormalizedOp(
            XPathy base,
            XPathyRegistry.Op op,
            String normExpr,
            String normalizedValue,
            String kindLabel // "attr" or "text" for error messages
    ) {
        String literal = toXPathLiteral(normalizedValue);
        String predicate = switch (op) {
            case EQUALS      -> "[ " + normExpr + " = " + literal + " ]";
            case CONTAINS    -> "[ contains(" + normExpr + ", " + literal + ") ]";
            case STARTS_WITH -> "[ starts-with(" + normExpr + ", " + literal + ") ]";
            default -> throw new IllegalArgumentException("Unsupported " + kindLabel + " op: " + op);
        };
        return withPredicate(base, predicate);
    }

    // =========================================================
    // Attribute predicates
    // =========================================================

    public static XPathy applyAttrOp(XPathy base, Attribute attr, XPathyRegistry.Op op, Object value) {
        if (op == null || attr == null) {
            return base;
        }

        String normalized = normalizeValue(value);

        // Only these three are supported here; the shared helper will throw for others.
        return applyNormalizedOp(
                base,
                op,
                normalizedAttrExpr(attr),
                normalized,
                "attr"
        );
    }

    // =========================================================
    // Text predicates (single impl)
    // =========================================================

    public static XPathy applyTextOp(XPathy base, XPathyRegistry.Op op, Object value) {
        if (op == null) {
            return base;
        }

        String normalized = normalizeValue(value);

        // Same helper as attributes, different normalized expression + label
        return applyNormalizedOp(
                base,
                op,
                normalizedTextExpr(),
                normalized,
                "text"
        );
    }

    // =========================================================
    // Existing helpers (unchanged API)
    // =========================================================

    /** Function factory that delegates to applyTextOp (no duplicate logic). */
    public static Function<XPathy, XPathy> textOp(XPathyRegistry.Op op, Object v) {
        return base -> applyTextOp(base, op, v);
    }

    @SafeVarargs
    public static XPathy orMap(Function<XPathy, XPathy> mapper, Supplier<XPathy>... bases) {
        return java.util.Arrays.stream(bases)
                .map(Supplier::get)
                .map(mapper)
                .reduce(XPathy::or)
                .orElseThrow();
    }

    // 2) Simpler overload when all bases are plain tags
    public static XPathy orTags(Function<XPathy, XPathy> mapper, Tag... tags) {
        return java.util.Arrays.stream(tags)
                .map(XPathy::from)
                .map(mapper)
                .reduce(XPathy::or)
                .orElseThrow();
    }

    // 3) No mapping at all (you already built the roots)
    public static XPathy orOf(XPathy... parts) {
        return java.util.Arrays.stream(parts)
                .reduce(XPathy::or)
                .orElseThrow();
    }
}
