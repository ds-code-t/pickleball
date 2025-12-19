package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import tools.dscode.common.domoperations.ExecutionDictionary.Op;
import tools.dscode.common.treeparsing.parsedComponents.ValueWrapper;

public final class XPathyBuilder {

    private static final String U = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String L = "abcdefghijklmnopqrstuvwxyz";

    public static XPathy build(Tag tag, Attribute attr, ValueWrapper v, Op op) {
        String t = (tag == null) ? "*" : tag.toString();
        XPathy base = XPathy.from("//" + t);
        Op o = (op == null) ? Op.DEFAULT : op;

        // null ValueWrapper + (EQUALS/DEFAULT) => presence-only (attr) or no constraint (text)
        if (v == null && (o == Op.DEFAULT || o == Op.EQUALS)) {
            return (attr == null) ? base : XPathy.from("//" + t + "[" + attr + "]");
        }

        String raw = (v == null) ? "" : v.value;
        if (raw == null || raw.isBlank()) {
            // blank value => match blank attr/text after normalization
            String normExpr = (attr == null) ? normTextExpr() : normAttrExpr(attr);
            return XPathy.from("//" + t + "[" + maybeCI(normExpr, v) + " = ''" + "]");
        }

        // Numeric ops
        if (o == Op.GT || o == Op.GTE || o == Op.LT || o == Op.LTE) {
            String lhsNum = (attr == null) ? "number(normalize-space(.))" : "number(" + attr + ")";
            String opSym = switch (o) {
                case GT -> ">";
                case GTE -> ">=";
                case LT -> "<";
                default -> "<="; // LTE
            };
            return XPathy.from("//" + t + "[" + lhsNum + " " + opSym + " " + v.trimmedValue() + "]");
        }

        // EQUALS/DEFAULT should be numeric equality if the wrapper is NUMERIC
        if ((o == Op.DEFAULT || o == Op.EQUALS) && v.type == ValueWrapper.ValueTypes.NUMERIC) {
            String lhsNum = (attr == null) ? "number(normalize-space(.))" : "number(" + attr + ")";
            return XPathy.from("//" + t + "[" + lhsNum + " = " + v.trimmedValue() + "]");
        }

        // String ops (normalized) — delegate to XPathyUtils where possible
        return switch (o) {
            case CONTAINS, STARTS_WITH -> applyNormalizedStringOp(base, attr, o, v);
            case ENDS_WITH -> endsWithNormalized(t, attr, v);
            case DEFAULT, EQUALS -> applyNormalizedStringOp(base, attr, Op.EQUALS, v);
            default -> applyNormalizedStringOp(base, attr, Op.EQUALS, v);
        };
    }

    private static XPathy applyNormalizedStringOp(XPathy base, Attribute attr, Op op, ValueWrapper v) {
        boolean ci = v != null && v.type == ValueWrapper.ValueTypes.SINGLE_QUOTED;
        String value = ci ? v.trimmedValue().toLowerCase() : v.trimmedValue();

        if (!ci) {
            return (attr == null)
                    ? XPathyUtils.applyTextOp(base, op, value)
                    : XPathyUtils.applyAttrOp(base, attr, op, value);
        }

        // Case-insensitive: rebuild predicate using the same normalization tables as XPathyUtils.
        String normExpr = (attr == null) ? normTextExpr() : normAttrExpr(attr);
        String lhs = "translate(" + normExpr + "," + lit(U) + "," + lit(L) + ")";
        String rhs = lit(XPathyUtils.normalizeText(value));

        String pred = switch (op) {
            case EQUALS -> lhs + " = " + rhs;
            case CONTAINS -> "contains(" + lhs + ", " + rhs + ")";
            case STARTS_WITH -> "starts-with(" + lhs + ", " + rhs + ")";
            default -> lhs + " = " + rhs;
        };

        return XPathy.from("(" + base.getXpath() + ")[" + pred + "]");
    }

    private static XPathy endsWithNormalized(String tag, Attribute attr, ValueWrapper v) {
        boolean ci = v != null && v.type == ValueWrapper.ValueTypes.SINGLE_QUOTED;
        String expected = ci ? v.trimmedValue().toLowerCase() : v.trimmedValue();
        expected = XPathyUtils.normalizeText(expected);

        String normExpr = (attr == null) ? normTextExpr() : normAttrExpr(attr);
        if (ci) normExpr = "translate(" + normExpr + "," + lit(U) + "," + lit(L) + ")";

        String rhs = lit(expected);
        String pred =
                "substring(" + normExpr + ", " +
                        "string-length(" + normExpr + ") - string-length(" + rhs + ") + 1" +
                        ") = " + rhs;

        return XPathy.from("//" + tag + "[" + pred + "]");
    }

    // Use the same whitespace normalization tables from XPathyUtils (no duplication of the tables)
    private static String normAttrExpr(Attribute attr) {
        return "normalize-space(translate(" + attr + ", " + lit(XPathyUtils.from) + " , " + lit(XPathyUtils.to) + "))";
    }

    private static String normTextExpr() {
        return "normalize-space(translate(string(.), " + lit(XPathyUtils.from) + " , " + lit(XPathyUtils.to) + "))";
    }

    private static String maybeCI(String normExpr, ValueWrapper v) {
        if (v != null && v.type == ValueWrapper.ValueTypes.SINGLE_QUOTED) {
            return "translate(" + normExpr + "," + lit(U) + "," + lit(L) + ")";
        }
        return normExpr;
    }

    // Minimal XPath literal helper (kept small on purpose)
    private static String lit(String s) {
        if (s.indexOf('\'') < 0) return "'" + s + "'";
        if (s.indexOf('"') < 0) return "\"" + s + "\"";
        // very rare: both quotes present; good enough for your “concise / minimal checks” requirement
        return "concat('" + s.replace("'", "',\"'\",'") + "')";
    }

    private XPathyBuilder() {}
}
