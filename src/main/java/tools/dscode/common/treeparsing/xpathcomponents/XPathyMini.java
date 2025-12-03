//package tools.dscode.common.treeparsing.xpathcomponents;
//
//import com.xpathy.Attribute;
//import com.xpathy.Tag;
//import com.xpathy.XPathy;
//import tools.dscode.common.domoperations.ExecutionDictionary;
//import tools.dscode.common.domoperations.VisibilityConditions;
//
//import java.util.Arrays;
//import java.util.function.Function;
//import java.util.function.Supplier;
//import java.util.stream.Collectors;
//
//import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.from;
//import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.to;
//
//public final class XPathyMini {
//
//
//    private XPathyMini() {}
//
//    // =====================================================================
//    //  VALUE NORMALIZATION
//    // =====================================================================
//
//    private static String normalizeValue(Object value) {
//
//        if (value == null) {
//            return "";
//        }
//
//        String s = String.valueOf(value)
//                .replace('\u00A0', ' ')
//                .replaceAll("\\s+", " ")
//                .strip();
//
//        return s;
//    }
//
//    private static String toXPathLiteral(String s) {
//        String out;
//        if (!s.contains("'")) {
//            out = "'" + s + "'";
//        } else if (!s.contains("\"")) {
//            out = "\"" + s + "\"";
//        } else {
//            String[] parts = s.split("'", -1);
//            StringBuilder sb = new StringBuilder("concat(");
//            for (int i = 0; i < parts.length; i++) {
//                if (i > 0) sb.append(", \"'\", ");
//                sb.append("'").append(parts[i]).append("'");
//            }
//            sb.append(")");
//            out = sb.toString();
//        }
//
//        return out;
//    }
//
//
//    private static String normalizedAttrExpr(Attribute attr) {
//        return "normalize-space(translate(@" + attr + ", " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
//    }
//
//    private static String normalizedTextExpr() {
//        return "normalize-space(translate(string(.), " + toXPathLiteral(from) + " , " + toXPathLiteral(to) + "))";
//    }
//
//    // =====================================================================
//    //  NORMALIZED OPS
//    // =====================================================================
//
//    private static XPathy applyNormalizedOp(
//            XPathy base,
//            ExecutionDictionary.Op op,
//            String normExpr,
//            String normalizedValue,
//            String label
//    ) {
//        if (op == null) {
//            return base;
//        }
//
//        String literal = toXPathLiteral(normalizedValue);
//
//        String predicate = switch (op) {
//            case EQUALS      -> "[" + normExpr + " = " + literal + "]";
//            case CONTAINS    -> "[contains(" + normExpr + ", " + literal + ")]";
//            case STARTS_WITH -> "[starts-with(" + normExpr + ", " + literal + ")]";
//            default -> {
//                String msg = "Unsupported " + label + " op: " + op;
//                throw new IllegalArgumentException(msg);
//            }
//        };
//
//        String out = "(" + base.getXpath().trim() + ")" + predicate;
//
//        return XPathy.from(out);
//    }
//
//
//    public static XPathy applyAttrOp(XPathy base, Attribute attr, ExecutionDictionary.Op op, Object value) {
//        if (attr == null) {
//            return base;
//        }
//
//        XPathy out = applyNormalizedOp(
//                base,
//                op,
//                normalizedAttrExpr(attr),
//                normalizeValue(value),
//                "attr"
//        );
//
//        return out;
//    }
//
//
//    public static XPathy applyTextOp(XPathy base, ExecutionDictionary.Op op, Object value) {
//
//        XPathy out = applyNormalizedOp(
//                base,
//                op,
//                normalizedTextExpr(),
//                normalizeValue(value),
//                "text"
//        );
//
//        return out;
//    }
//
//    public static Function<XPathy, XPathy> textOp(ExecutionDictionary.Op op, Object v) {
//        return base -> applyTextOp(base, op, v);
//    }
//
//
//
//
//
//
////
////    // =====================================================================
////    //  combineWithVisibility — central OR/AND logic
////    // =====================================================================
////
////    @SafeVarargs
////    private static XPathy combineWithVisibility(String joiner, Supplier<XPathy>... bases) {
////
////        if (bases == null || bases.length == 0) {
////            return VisibilityConditions.visibleElement();
////        }
////
////        String bundled = Arrays.stream(bases)
////                .map(Supplier::get)
////                .map(XPathy::getXpath)
////                .map(XPathyMini::toSelfStep)
////                .collect(Collectors.joining(joiner));
////
////        XPathy visible = VisibilityConditions.visibleElement();
////        String finalXpath = visible.getXpath() + "[" + bundled + "]";
////        return XPathy.from(finalXpath);
////    }
////
////    // =====================================================================
////    //  Public OR/AND entry points
////    // =====================================================================
////
////    @SafeVarargs
////    public static XPathy orMap(Supplier<XPathy>... bases) {
////        return combineWithVisibility(" or ", bases);
////    }
////
////    @SafeVarargs
////    public static XPathy andMap(Supplier<XPathy>... bases) {
////        return combineWithVisibility(" and ", bases);
////    }
////
////    @SafeVarargs
////    public static XPathy orMap(Function<XPathy, XPathy> mapper, Supplier<XPathy>... bases) {
////        XPathy out = mapper.apply(orMap(bases));
////        return out;
////    }
////
////    @SafeVarargs
////    public static XPathy andMap(Function<XPathy, XPathy> mapper, Supplier<XPathy>... bases) {
////        XPathy out = mapper.apply(andMap(bases));
////        return out;
////    }
////
////    // =====================================================================
////    //  Other small helpers
////    // =====================================================================
////
////    public static XPathy orTags(Function<XPathy, XPathy> mapper, Tag... tags) {
////        XPathy out = Arrays.stream(tags)
////                .map(XPathy::from)
////                .map(mapper)
////                .reduce(XPathy::or)
////                .orElseThrow();
////
////        return out;
////    }
////
////    public static XPathy orOf(XPathy... parts) {
////        XPathy out = Arrays.stream(parts)
////                .reduce(XPathy::or)
////                .orElseThrow();
////
////        return out;
////    }
//}
