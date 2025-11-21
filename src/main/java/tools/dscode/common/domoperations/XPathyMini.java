package tools.dscode.common.domoperations;

import com.xpathy.Attribute;
import com.xpathy.Condition;
import com.xpathy.Tag;
import com.xpathy.XPathy;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static tools.dscode.common.domoperations.VisibilityConditions.visible;

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
    public static XPathy orMap(Function<XPathy, XPathy> mapper,
                               Supplier<XPathy>... bases) {

        // 1. Build: self::input[@class='A'] or descendant::*...
        String bundled = Arrays.stream(bases)
                .map(Supplier::get)
                .map(x -> {
                    String xp = x.getXpath();
                    System.out.println("## orMap(mapper) base xpath: " + xp);
                    String step = toSelfStep(xp);
                    System.out.println("## orMap(mapper) toSelfStep -> " + step);
                    return step;
                })
                .collect(Collectors.joining(" or "));

        System.out.println("## orMap(mapper) bundled predicate: " + bundled);

        // 2. Apply mapper ONCE to the whole combined expression
        XPathy baseForMapper = XPathy.from("//*[" + bundled + "]");
        System.out.println("## orMap(mapper) baseForMapper: " + baseForMapper.getXpath());

        XPathy mapped = mapper.apply(baseForMapper);
        System.out.println("## orMap(mapper) mapped xpath: " + mapped.getXpath());

        // 3. Extract mapper predicate from the XPathy object
        String mapperPredicate = toSelfStep(mapped.getXpath());
        System.out.println("## orMap(mapper) mapperPredicate (self-step): " + mapperPredicate);

        // 4. Combine base + mapper predicate
        String baseAndMapper = bundled + " and " + mapperPredicate;
        System.out.println("## orMap(mapper) baseAndMapper: " + baseAndMapper);

        // 5. Append "no ancestor-or-self is invisible" predicate
        String fullPredicate = withNoInvisibleAncestorOrSelf(baseAndMapper);
        System.out.println("## orMap(mapper) fullPredicate: " + fullPredicate);

        String finalXpath = "//*[" + fullPredicate + "]";
        System.out.println("## orMap(mapper) FINAL XPATH: " + finalXpath);

        return XPathy.from(finalXpath);
    }

    @SafeVarargs
    public static XPathy orMap(Supplier<XPathy>... bases) {

        String predicate = Arrays.stream(bases)
                .map(Supplier::get)
                .map(x -> {
                    String xp = x.getXpath();
                    System.out.println("## orMap base xpath: " + xp);
                    String step = toSelfStep(xp);
                    System.out.println("## orMap toSelfStep -> " + step);
                    return step;
                })
                .collect(Collectors.joining(" or "));

        System.out.println("## orMap bundled predicate: " + predicate);

        // Result before: //*[(self::input[@class='A'] or self::div)]
        // Now:          //*[(self::... or ...) and not(ancestor-or-self::*[invisible])]
        String fullPredicate = withNoInvisibleAncestorOrSelf(predicate);
        System.out.println("## orMap fullPredicate: " + fullPredicate);

        String finalXpath = "//*[" + fullPredicate + "]";
        System.out.println("## orMap FINAL XPATH: " + finalXpath);

        return XPathy.from(finalXpath);
    }

    @SafeVarargs
    public static XPathy andMap(Function<XPathy, XPathy> mapper,
                                Supplier<XPathy>... bases) {

        // 1. Build: self::input[@class='A'] and descendant::* ...
        String bundled = Arrays.stream(bases)
                .map(Supplier::get)
                .map(x -> {
                    String xp = x.getXpath();
                    System.out.println("## andMap(mapper) base xpath: " + xp);
                    String step = toSelfStep(xp);
                    System.out.println("## andMap(mapper) toSelfStep -> " + step);
                    return step;
                })
                .collect(Collectors.joining(" and "));

        System.out.println("## andMap(mapper) bundled predicate: " + bundled);

        // 2. Apply mapper ONCE to the whole combined expression
        XPathy baseForMapper = XPathy.from("//*[" + bundled + "]");
        System.out.println("## andMap(mapper) baseForMapper: " + baseForMapper.getXpath());

        XPathy mapped = mapper.apply(baseForMapper);
        System.out.println("## andMap(mapper) mapped xpath: " + mapped.getXpath());

        // 3. Extract its predicate part
        String mapperPredicate = toSelfStep(mapped.getXpath());
        System.out.println("## andMap(mapper) mapperPredicate (self-step): " + mapperPredicate);

        // 4. Combine base and mapper predicate
        String baseAndMapper = bundled + " and " + mapperPredicate;
        System.out.println("## andMap(mapper) baseAndMapper: " + baseAndMapper);

        // 5. Append "no ancestor-or-self is invisible" predicate
        String fullPredicate = withNoInvisibleAncestorOrSelf(baseAndMapper);
        System.out.println("## andMap(mapper) fullPredicate: " + fullPredicate);

        String finalXpath = "//*[" + fullPredicate + "]";
        System.out.println("## andMap(mapper) FINAL XPATH: " + finalXpath);

        return XPathy.from(finalXpath);
    }

    @SafeVarargs
    public static XPathy andMap(Supplier<XPathy>... bases) {

        String predicate = Arrays.stream(bases)
                .map(Supplier::get)
                .map(x -> {
                    String xp = x.getXpath();
                    System.out.println("## andMap base xpath: " + xp);
                    String step = toSelfStep(xp);
                    System.out.println("## andMap toSelfStep -> " + step);
                    return step;
                })
                .collect(Collectors.joining(" and "));

        System.out.println("## andMap bundled predicate: " + predicate);

        // Result before: //*[(self::input[@class='A'] and self::div and ...)]
        // Now:           //*[(self::... and ...) and not(ancestor-or-self::*[invisible])]
        String fullPredicate = withNoInvisibleAncestorOrSelf(predicate);
        System.out.println("## andMap fullPredicate: " + fullPredicate);

        String finalXpath = "//*[" + fullPredicate + "]";
        System.out.println("## andMap FINAL XPATH: " + finalXpath);

        return XPathy.from(finalXpath);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static String withNoInvisibleAncestorOrSelf(String predicate) {
        System.out.println("## withNoInvisibleAncestorOrSelf IN: " + predicate);
        String ancestorNotInvisible = buildAncestorNotInvisiblePredicate();
        System.out.println("## withNoInvisibleAncestorOrSelf ancestorNotInvisible: " + ancestorNotInvisible);

        String out = "(" + predicate + ") and " + ancestorNotInvisible;
        System.out.println("## withNoInvisibleAncestorOrSelf OUT: " + out);
        return out;
    }

    private static String buildAncestorNotInvisiblePredicate() {
        // Build a temporary XPath that uses NOT visible() as a predicate
        XPathy tmp = XPathy.from("//*").byCondition(
                Condition.not(VisibilityConditions.visible())
        );
        String xpath = tmp.getXpath();
        System.out.println("## buildAncestorNotInvisiblePredicate tmp xpath: " + xpath);

        // Extract the predicate body between the outer [ ... ]
        String invisibleBody = extractPredicateBody(xpath);
        System.out.println("## buildAncestorNotInvisiblePredicate invisibleBody: " + invisibleBody);

        String ancestor = "not(ancestor-or-self::*[" + invisibleBody + "])";
        System.out.println("## buildAncestorNotInvisiblePredicate ancestor predicate: " + ancestor);
        return ancestor;
    }

    private static String extractPredicateBody(String xpath) {
        int open = xpath.indexOf('[');
        int close = xpath.lastIndexOf(']');
        if (open < 0 || close <= open + 1) {
            throw new IllegalStateException(
                    "Unexpected XPath format when extracting predicate: " + xpath
            );
        }
        String body = xpath.substring(open + 1, close);
        System.out.println("## extractPredicateBody from: " + xpath);
        System.out.println("## extractPredicateBody body: " + body);
        return body;
    }

    private static String toSelfStep(String xpath) {
        System.out.println("\n@@toSelfStep IN: " + xpath);

        String s = xpath.trim();

        // Strip leading //, .//, /, ./ etc.
        s = s.replaceFirst("^\\.?/+", "");

        // Keep only the last step (e.g. "input[@class='A']" from "div/span/input[@class='A']").
        int lastSlash = s.lastIndexOf('/');
        String step = (lastSlash >= 0) ? s.substring(lastSlash + 1) : s;
        System.out.println("@@  raw step: [" + step + "]");

        // 1) If it ALREADY has an axis anywhere (e.g. "descendant::*[...]",
        //    "ancestor::*[@hidden]", "following-sibling::a[@x]"),
        //    DO NOT touch it. Just return as-is.
        //
        //    This also covers the bad "self::descendant::*" if it ever sneaks in
        //    from upstream – we won't add another "self::" in front of it.
        if (step.contains("::")) {
            System.out.println("@@  step contains '::', returning as-is: [" + step + "]");
            return step;
        }

        // 2) Otherwise, prepend self:: (original behavior).
        String result = "self::" + step;
        System.out.println("@@  default -> [" + result + "]");
        return result;
    }



    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------







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
