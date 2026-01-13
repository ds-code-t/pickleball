package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "collapsed/expanded" UI state.
 *
 * Supports common signals:
 *  - ARIA:         aria-expanded="true|false", aria-hidden="true|false"
 *  - HTML:         <details open>  (uses raw XPath: @open)
 *  - data-* :      data-expanded, data-collapsed, data-state=open/closed/expanded/collapsed
 *  - class tokens: expanded/collapsed/open/closed/show/hidden/etc.
 *
 * FIXES:
 *  - collapsedElement() no longer treats "not expanded" as collapsed unless the element
 *    looks like it participates in a collapsible/expandable pattern (a "candidate").
 *  - expandedContentElement() no longer matches everything due to not(@aria-hidden='true')
 *    when @aria-hidden is absent.
 */
public final class CollapsedExpandedConditions {

    private CollapsedExpandedConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (pure XPathy Condition)
     * ------------------------------------------------------------------------- */

    /**
     * Expanded/open state using only what we can express via XPathy Condition:
     *  - class tokens that strongly indicate expanded/open.
     *
     * (ARIA/data/open checks are added in the XPathy helpers below.)
     */
    public static Condition expanded() {
        return expandedClassLike();
    }

    /**
     * Collapsed/closed is the negation of {@link #expanded()} (Condition-only).
     *
     * NOTE: This is inherently lossy: "not expanded markers" != "collapsed".
     * Prefer {@link #collapsedElement()} / {@link #collapsedElementExplicit()} for XPathy queries.
     */
    public static Condition collapsed() {
        return not(expanded());
    }

    /* -------------------------------------------------------------------------
     * Public XPathy API (adds raw XPath fallbacks for aria-/data-* and @open)
     * ------------------------------------------------------------------------- */

    /**
     * Any element (//*) that is considered expanded/open using:
     *  - expanded() Condition rules (class tokens), plus
     *  - raw XPath checks:
     *      @open
     *      @aria-expanded='true'
     *      @data-expanded='true'
     *      @data-state in {'open','opened','expanded'}
     */
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

    /**
     * Any element (//*) that is considered collapsed/closed.
     *
     * FIX:
     *  - We only apply the "not(expanded)" fallback if the element is a
     *    "collapsible candidate" (has any collapse/expand-related signal).
     *
     * If you only want explicitly-collapsed elements, use {@link #collapsedElementExplicit()}.
     */
    public static XPathy collapsedElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        // expanded predicate for the current node (self)
        String expandedXpath = expandedElement().getXpath();
        String expandedPred = extractPredicate(base, expandedXpath);

        // candidate predicate for the current node (self)
        String candidatePred = buildCollapsibleCandidatePredicate(base);

        // explicit collapsed signals (self)
        String rawCollapsed =
                "(" +
                        "@aria-expanded='false' or " +
                        "@aria-hidden='true' or " +
                        "@data-collapsed='true' or " +
                        "@data-expanded='false' or " +
                        "@data-state='closed' or @data-state='collapsed'" +
                        ")";

        // Collapsed if:
        //  - explicitly collapsed
        //  - OR (candidate AND NOT expanded)
        String finalXpath =
                base + "[" +
                        rawCollapsed +
                        " or (" + candidatePred + " and not(" + expandedPred + "))" +
                        "]";

        return new XPathy(finalXpath);
    }

    /**
     * Collapsed/closed elements ONLY when explicitly marked as collapsed/closed
     * via ARIA/data-* or class tokens (no "not expanded" fallback).
     */
    public static XPathy collapsedElementExplicit() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        XPathy byCondCollapsedMarkers = any.byCondition(collapsedClassLike());
        String collapsedMarkersPred = extractPredicate(base, byCondCollapsedMarkers.getXpath());

        String rawCollapsed =
                "(" +
                        "@aria-expanded='false' or " +
                        "@aria-hidden='true' or " +
                        "@data-collapsed='true' or " +
                        "@data-expanded='false' or " +
                        "@data-state='closed' or @data-state='collapsed'" +
                        ")";

        String finalXpath = base + "[" + collapsedMarkersPred + " or " + rawCollapsed + "]";
        return new XPathy(finalXpath);
    }

    /**
     * Often you have:
     *  - a trigger element that has aria-expanded
     *  - a content element that has aria-hidden
     *
     * FIX:
     *  - Do NOT use "not(@aria-hidden='true')" by itself because if @aria-hidden is absent
     *    that expression is true, matching almost every element.
     *
     * We now require @aria-hidden to be present for that part:
     *  - (@aria-hidden and not(@aria-hidden='true'))
     */
    public static XPathy expandedContentElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String rawExpandedContent =
                "(" +
                        "(@aria-hidden and not(@aria-hidden='true')) or " +
                        "@data-state='open' or @data-state='opened' or " +
                        "@data-expanded='true'" +
                        ")";

        XPathy byCondExpandedClass = any.byCondition(expandedClassLike());
        String expandedClassPred = extractPredicate(base, byCondExpandedClass.getXpath());

        // still allow strong expanded class tokens as a signal
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
                attribute(class_).contains("is-closed"),
                attribute(class_).contains("hide"),
                attribute(class_).contains("hidden")
        );
    }

    /**
     * Build a predicate that tries to identify elements that participate in
     * a collapsible/expandable UI pattern.
     *
     * This is used to avoid "not(expanded)" matching random elements.
     */
    private static String buildCollapsibleCandidatePredicate(String base) {
        XPathy any = new XPathy();

        // class tokens (Condition-based)
        XPathy byCondAnyStateClass = any.byCondition(or(expandedClassLike(), collapsedClassLike()));
        String classStatePred = extractPredicate(base, byCondAnyStateClass.getXpath());

        // raw attribute presence checks (these indicate "this element is stateful")
        String rawCandidate =
                "(" +
                        "@open or " +
                        "@aria-expanded or " +
                        "@aria-hidden or " +
                        "@data-expanded or " +
                        "@data-collapsed or " +
                        "@data-state" +
                        ")";

        // candidate if either class tokens suggest state OR explicit state attrs exist
        return "(" + classStatePred + " or " + rawCandidate + ")";
    }

    /**
     * Helper: given base "//*" and "//*[(...)]" return "(...)"
     */
    public static String extractPredicate(String base, String xpathWithPredicate) {
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }
}
