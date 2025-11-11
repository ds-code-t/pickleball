package tools.dscode.common.domoperations;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;

import java.util.function.Function;
import java.util.function.Supplier;

public final class XPathyMini {
    private XPathyMini() {}

    /** Attribute predicates */
    public static XPathy applyAttrOp(XPathy base, Attribute attr, XPathyRegistry.Op op, Object value) {
        if(op ==null)
            return base;
        String s = String.valueOf(value);
        return switch (op) {
            case EQUALS      -> base.byAttribute(attr).equals(s);
            case CONTAINS    -> base.byAttribute(attr).contains(s);
            case STARTS_WITH -> base.byAttribute(attr).startsWith(s);
            default -> throw new IllegalArgumentException("Unsupported attr op: " + op);
        };
    }

    /** Text predicates (single impl) */
    public static XPathy applyTextOp(XPathy base, XPathyRegistry.Op op, Object value) {
        if(op ==null)
            return base;
        String s = String.valueOf(value);
        return switch (op) {
            case EQUALS      -> base.byText().equals(s);
            case CONTAINS    -> base.byText().contains(s);
            case STARTS_WITH -> base.byText().startsWith(s);
            default -> throw new IllegalArgumentException("Unsupported text op: " + op);
        };
    }

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
