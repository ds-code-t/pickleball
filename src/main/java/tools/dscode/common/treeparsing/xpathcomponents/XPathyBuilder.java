package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary.Op;

import java.util.Arrays;
import java.util.Objects;

public final class XPathyBuilder {

    private XPathyBuilder() {}

    public static XPathy buildIfAllTrue(Tag tag, Attribute xPathyAttr, ValueWrapper v, Op op, boolean... bools) {
        for (boolean b : bools) {
            if (!b) return null;
        }
        return build(tag, xPathyAttr, v, op);
    }

    public static XPathy buildIfNonNull(Tag tag, Attribute xPathyAttr, ValueWrapper v, Op op, Object... nonNulls) {
        if (Arrays.stream(nonNulls).anyMatch(Objects::isNull)) return null;
        return build(tag, xPathyAttr, v, op);
    }

    /**
     * Builds: //tag[ predicate ]
     *
     * Delegates ALL string/attr matching to XPathyUtils ValueWrapper-based APIs.
     * Only keeps small numeric-text handling here (because XPathyUtils numeric ops are attribute-focused).
     */
    public static XPathy build(Tag tag, Attribute xPathyAttr, ValueWrapper v, Op op) {
        String t = (tag == null) ? "*" : tag.toString();
        XPathy base = XPathy.from("//" + t);

        Op o = normalizeOp(op);

        // Attribute name normalization (builder previously carried leading @ sometimes)
        String attrName = (xPathyAttr == null) ? null : xPathyAttr.toString();
        if (attrName != null) {
            attrName = attrName.strip();
            if (attrName.startsWith("@")) attrName = attrName.substring(1);
            if (attrName.isBlank()) attrName = null;
        }

        // ---------------------------------------------------------------------
        // Null ValueWrapper handling (preserve old intent)
        //
        // - If attr is present and v is null with (DEFAULT/EQUALS): presence-only predicate.
        // - If text (attr == null) and v is null with (DEFAULT/EQUALS): no constraint.
        // ---------------------------------------------------------------------
        if (v == null && (o == Op.DEFAULT || o == Op.EQUALS)) {
            if (attrName == null) return base;
            return XPathy.from("//" + t + "[@" + attrName + "]");
        }

        // If v is null but op isn't EQUALS/DEFAULT, previous code effectively treated it as empty.
        // We'll match empty string/blank normalization instead (safe + deterministic).
        ValueWrapper effective = (v == null) ? ValueWrapper.createValueWrapper("") : v;

        // ---------------------------------------------------------------------
        // Blank value wrapper: match blank text/attr after normalization
        // (BACK_TICKED blank means exact empty; this still works because value is "")
        // ---------------------------------------------------------------------
        String raw = effective.toString(); // unquoted content for quoted types; raw string for others
        if (raw == null || raw.isBlank()) {
            ValueWrapper blank = ValueWrapper.createValueWrapper("");
            if (attrName == null) {
                return XPathyUtils.applyTextPredicate(base, blank, Op.EQUALS);
            }
            return XPathyUtils.applyAttrPredicate(base, attrName, blank, Op.EQUALS);
        }

        // ---------------------------------------------------------------------
        // Numeric text comparisons (attr == null)
        // XPathyUtils numeric ops are for ATTR predicates; builder used numeric on text too.
        // Keep that capability here, still driven by ValueWrapper.
        // ---------------------------------------------------------------------
        if (attrName == null && isNumericOp(o)) {
            // Use ValueWrapper numeric parsing semantics (BigInteger-based)
            // For non-numeric wrappers, previous behavior would still try; we enforce numeric.
            if (effective.type != ValueWrapper.ValueTypes.NUMERIC && !effective.isNumeric()) {
                // fall back to string equals (old behavior was ambiguous); keep conservative
                return XPathyUtils.applyTextPredicate(base, effective, Op.EQUALS);
            }

            String lhs = "number(normalize-space(.))";
            String rhs = effective.asBigInteger().toString();
            String sym = switch (o) {
                case GT -> ">";
                case GTE -> ">=";
                case LT -> "<";
                case LTE -> "<=";
                default -> "="; // not reachable for isNumericOp except EQUALS in some flows
            };

            return XPathy.from("//" + t + "[" + lhs + " " + sym + " " + rhs + "]");
        }

        // Numeric equality on text when wrapper is NUMERIC and op is DEFAULT/EQUALS
        if (attrName == null && (o == Op.DEFAULT || o == Op.EQUALS) && effective.type == ValueWrapper.ValueTypes.NUMERIC) {
            String lhs = "number(normalize-space(.))";
            String rhs = effective.asBigInteger().toString();
            return XPathy.from("//" + t + "[" + lhs + " = " + rhs + "]");
        }

        // ---------------------------------------------------------------------
        // String/text operations (ValueWrapper-driven) for text or attribute
        // Everything routes through XPathyUtils (no duplicated normalization/CI logic here).
        // ---------------------------------------------------------------------
        Op routed = (o == Op.DEFAULT) ? Op.EQUALS : o;

        if (attrName == null) {
            // text predicate on base
            return XPathyUtils.applyTextPredicate(base, effective, routed);
        }

        // attribute predicate on base (includes numeric + boolean semantics inside XPathyUtils)
        return XPathyUtils.applyAttrPredicate(base, attrName, effective, routed);
    }

    // =========================================================================
    // Small helpers (non-redundant)
    // =========================================================================

    private static Op normalizeOp(Op op) {
        return (op == null) ? Op.DEFAULT : op;
    }

    private static boolean isNumericOp(Op op) {
        return op == Op.GT || op == Op.GTE || op == Op.LT || op == Op.LTE;
    }
}
