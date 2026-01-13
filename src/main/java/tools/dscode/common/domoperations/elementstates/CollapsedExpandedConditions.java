package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "collapsed/expanded" UI state.
 *
 * Supports common signals:
 *  - ARIA:         aria-expanded="true|false"
 *  - HTML:         <details open>  (uses raw XPath: @open)
 *  - data-* :      data-expanded, data-collapsed, data-state=open/closed/expanded/collapsed
 *  - class tokens: expanded/collapsed/open/closed/show/etc.
 *
 * NOTE:
 *  - All references to aria-hidden, hide, hidden have been removed because they are not
 *    reliable indicators of collapsed/expanded state in many UIs (e.g., decorative icons).
 */
public final class CollapsedExpandedConditions {

    private CollapsedExpandedConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (pure XPathy Condition)
     * ------------------------------------------------------------------------- */

    public static Condition expanded() {
        return expandedClassLike();
    }

    public static Condition collapsed() {
        return not(expanded());
    }

    /* -------------------------------------------------------------------------
     * Public XPathy API (adds raw XPath fallbacks for aria-/data-* and @open)
     * ------------------------------------------------------------------------- */

    public static XPathy expandedElement() {
        XPathy any = new XPathy();
        XPathy byCond = any.byCondition(expanded());

        String base = any.getXpath();        // "//*"
        String withPred = byCond.getXpath(); // "//*[<predicate>]"
        String predicate = extractPredicate(base, withPred);

        String rawExpanded =
                "(" +
                        "@open or " +
                        "@aria-expanded='true' or " +
                        "@data-expanded='true' or " +
                        "@data-state='open' or @data-state='opened' or @data-state='expanded'" +
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawExpanded + "]";
        return new XPathy(finalXpath);
    }

    public static XPathy collapsedElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String expandedXpath = expandedElement().getXpath();
        String expandedPred = extractPredicate(base, expandedXpath);

        String candidatePred = buildCollapsibleCandidatePredicate(base);

        // explicit collapsed signals (self) — aria-hidden removed
        String rawCollapsed =
                "(" +
                        "@aria-expanded='false' or " +
                        "@data-collapsed='true' or " +
                        "@data-expanded='false' or " +
                        "@data-state='closed' or @data-state='collapsed'" +
                        ")";

        String finalXpath =
                base + "[" +
                        rawCollapsed +
                        " or (" + candidatePred + " and not(" + expandedPred + "))" +
                        "]";

        return new XPathy(finalXpath);
    }

    public static XPathy collapsedElementExplicit() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        XPathy byCondCollapsedMarkers = any.byCondition(collapsedClassLike());
        String collapsedMarkersPred = extractPredicate(base, byCondCollapsedMarkers.getXpath());

        // explicit collapsed signals — aria-hidden removed
        String rawCollapsed =
                "(" +
                        "@aria-expanded='false' or " +
                        "@data-collapsed='true' or " +
                        "@data-expanded='false' or " +
                        "@data-state='closed' or @data-state='collapsed'" +
                        ")";

        String finalXpath = base + "[" + collapsedMarkersPred + " or " + rawCollapsed + "]";
        return new XPathy(finalXpath);
    }

    /**
     * "Expanded content element" heuristic without aria-hidden.
     * Falls back to data-state / data-expanded and expanded-ish class tokens.
     */
    public static XPathy expandedContentElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String rawExpandedContent =
                "(" +
                        "@data-state='open' or @data-state='opened' or " +
                        "@data-expanded='true'" +
                        ")";

        XPathy byCondExpandedClass = any.byCondition(expandedClassLike());
        String expandedClassPred = extractPredicate(base, byCondExpandedClass.getXpath());

        String finalXpath = base + "[" + rawExpandedContent + " or " + expandedClassPred + "]";
        return new XPathy(finalXpath);
    }

    /* -------------------------------------------------------------------------
     * Internals (Condition-only class token heuristics)
     * ------------------------------------------------------------------------- */

    private static Condition expandedClassLike() {
        return or(
                attribute(class_).contains("expanded"),
                attribute(class_).contains("is-expanded"),
                attribute(class_).contains("open"),
                attribute(class_).contains("is-open"),
                attribute(class_).contains("show")     // e.g. Bootstrap collapse "show"
        );
    }

    private static Condition collapsedClassLike() {
        return or(
                attribute(class_).contains("collapsed"),
                attribute(class_).contains("is-collapsed"),
                attribute(class_).contains("closed"),
                attribute(class_).contains("is-closed")
        );
    }

    private static String buildCollapsibleCandidatePredicate(String base) {
        XPathy any = new XPathy();

        XPathy byCondAnyStateClass = any.byCondition(or(expandedClassLike(), collapsedClassLike()));
        String classStatePred = extractPredicate(base, byCondAnyStateClass.getXpath());

        // raw attribute presence checks — aria-hidden removed
        String rawCandidate =
                "(" +
                        "@open or " +
                        "@aria-expanded or " +
                        "@data-expanded or " +
                        "@data-collapsed or " +
                        "@data-state" +
                        ")";

        return "(" + classStatePred + " or " + rawCandidate + ")";
    }

    public static String extractPredicate(String base, String xpathWithPredicate) {
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }
}
