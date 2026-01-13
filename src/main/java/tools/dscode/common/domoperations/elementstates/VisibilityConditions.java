package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Attribute.style;
import static com.xpathy.Style.*;
import static com.xpathy.Style.height;
import static com.xpathy.Style.width;

/**
 * Reusable XPathy conditions related to "visibility".
 *
 * This approximates "visible" using only what XPath can see on the element
 * (inline styles / attributes). It intentionally does not try to look at
 * computed styles or layout.
 *
 * NOTE on aria-hidden:
 *  - aria-hidden is an accessibility-tree hint, not a visual rendering hint.
 *    So it is NOT used in visible() anymore.
 *
 * NOTE on ancestor checks:
 *  - Some properties (notably width/height) are unsafe to apply to ancestors
 *    because descendants can still be visually present (overflow, abs/fixed, etc).
 *  - visibleElement() now uses a reduced "ancestorInvisible()" predicate.
 */
public final class VisibilityConditions {

    private VisibilityConditions() {
        // utility class
    }

    /** Backwards-compatible: still means "not(visible())". */
    public static Condition invisible() {
        return Condition.not(visible());
    }

    /**
     * Optional helper if you ever want to exclude aria-hidden content explicitly
     * (accessibility visibility, not visual visibility).
     */
    public static Condition accessible() {
        return Condition.not(Condition.attribute(aria_hidden).equals("true"));
    }

    /**
     * XPathy locator for:
     *  - any tag that is visible according to visible()
     *  - AND has no ancestor that is "ancestorInvisible()" (reduced, safer set)
     */
    public static XPathy visibleElement() {

        XPathy any = new XPathy(); // "//*"

        // Self predicate (full visible() checks)
        XPathy selfVisible = any.byCondition(visible());

        // Ancestor predicate (reduced, safer checks)
        XPathy ancestorInv = any.byCondition(ancestorInvisible());

        String base = any.getXpath();                   // "//*"
        String selfVisibleXpath = selfVisible.getXpath();
        String ancestorInvXpath = ancestorInv.getXpath();

        String visiblePredicate = extractPredicate(base, selfVisibleXpath);
        String ancestorInvisiblePredicate = extractPredicate(base, ancestorInvXpath);

        // //*[ <visiblePredicate> and not(ancestor::*[ <ancestorInvisiblePredicate> ]) ]
        String finalXpath =
                base + "[" +
                        visiblePredicate +
                        " and not(ancestor::*[" +
                        ancestorInvisiblePredicate +
                        "])]";

        return new XPathy(finalXpath);
    }

    // Helper: given "//*" and "//*[(...)]" return "(...)"
    public static String extractPredicate(String base, String xpathWithPredicate) {
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }

    /**
     * Visual "visible" condition for the element itself.
     *
     * Fixes applied:
     *  - Removed aria-hidden from visual visibility.
     *  - Made raw @style substring fallbacks for opacity/width/height less error-prone
     *    (avoid matching 0.2, 0.5rem, etc).
     */
    public static Condition visible() {

        return Condition.and(

                // -------------------------------------------------------------
                // display != none
                // -------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(display).equals("none"),
                                // allow missing semicolon / spaces a bit
                                Condition.attribute(style).contains("display:none"),
                                Condition.attribute(style).contains("display: none")
                        )
                ),

                // -------------------------------------------------------------
                // visibility != hidden
                // -------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(visibility).equals("hidden"),
                                Condition.attribute(style).contains("visibility:hidden"),
                                Condition.attribute(style).contains("visibility: hidden")
                        )
                ),

                // -------------------------------------------------------------
                // visibility != collapse
                // -------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(visibility).equals("collapse"),
                                Condition.attribute(style).contains("visibility:collapse"),
                                Condition.attribute(style).contains("visibility: collapse")
                        )
                ),

                // -------------------------------------------------------------
                // opacity != 0  (make substring fallback safer: don't match 0.2)
                // -------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(opacity).equals("0"),
                                isInlineStyleExactlyZero("opacity")
                        )
                ),

                // -------------------------------------------------------------
                // width not in {0, 0px, 0%}  (safer substring fallback)
                // -------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(width).equals("0"),
                                Condition.style(width).equals("0px"),
                                Condition.style(width).equals("0%"),
                                isInlineStyleExactlyZero("width"),
                                Condition.attribute(style).contains("width:0px"),
                                Condition.attribute(style).contains("width: 0px"),
                                Condition.attribute(style).contains("width:0%") ,
                                Condition.attribute(style).contains("width: 0%")
                        )
                ),

                // -------------------------------------------------------------
                // height not in {0, 0px, 0%} (safer substring fallback)
                // -------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(height).equals("0"),
                                Condition.style(height).equals("0px"),
                                Condition.style(height).equals("0%"),
                                isInlineStyleExactlyZero("height"),
                                Condition.attribute(style).contains("height:0px"),
                                Condition.attribute(style).contains("height: 0px"),
                                Condition.attribute(style).contains("height:0%"),
                                Condition.attribute(style).contains("height: 0%")
                        )
                ),

                // -------------------------------------------------------------
                // no @hidden attribute
                // -------------------------------------------------------------
                Condition.not(Condition.attribute(hidden).haveIt()),

                // -------------------------------------------------------------
                // type != "hidden" (mostly for <input>, kept for backwards behavior)
                // -------------------------------------------------------------
                Condition.not(Condition.attribute(type).equals("hidden"))

                // NOTE: aria-hidden intentionally removed from visual visibility.
        );
    }

    /**
     * Reduced invisibility checks that are safer to apply to ancestors.
     *
     * Fixes applied:
     *  - Does NOT use aria-hidden (a11y-only).
     *  - Does NOT use width/height (unsafe on ancestors).
     *  - Does NOT use type=hidden (not meaningful for ancestors).
     *
     * Kept:
     *  - display:none, hidden attribute, visibility hidden/collapse, opacity:0
     *    (these usually *do* hide descendants visually).
     */
    public static Condition ancestorInvisible() {
        return Condition.or(

                // display:none
                Condition.or(
                        Condition.style(display).equals("none"),
                        Condition.attribute(style).contains("display:none"),
                        Condition.attribute(style).contains("display: none")
                ),

                // visibility:hidden or collapse (mostly correct for ancestors; can be overridden)
                Condition.or(
                        Condition.style(visibility).equals("hidden"),
                        Condition.attribute(style).contains("visibility:hidden"),
                        Condition.attribute(style).contains("visibility: hidden"),
                        Condition.style(visibility).equals("collapse"),
                        Condition.attribute(style).contains("visibility:collapse"),
                        Condition.attribute(style).contains("visibility: collapse")
                ),

                // opacity:0 (safer substring fallback)
                Condition.or(
                        Condition.style(opacity).equals("0"),
                        isInlineStyleExactlyZero("opacity")
                ),

                // @hidden attribute (HTML boolean attribute)
                Condition.attribute(hidden).haveIt()
        );
    }

    /**
     * Safer-ish "prop:0" matcher for raw @style.
     *
     * Goal: match opacity:0 / width:0 / height:0 but NOT opacity:0.2 or width:0.5rem.
     * We can't do perfect tokenization with only "contains", so we:
     *  - require "prop:0" or "prop: 0"
     *  - and reject the common decimal forms "prop:0." / "prop: 0."
     */
    private static Condition isInlineStyleExactlyZero(String prop) {
        Condition has0 =
                Condition.or(
                        Condition.attribute(style).contains(prop + ":0"),
                        Condition.attribute(style).contains(prop + ": 0")
                );

        Condition hasDecimal =
                Condition.or(
                        Condition.attribute(style).contains(prop + ":0."),
                        Condition.attribute(style).contains(prop + ": 0.")
                );

        return Condition.and(has0, Condition.not(hasDecimal));
    }
}
