package tools.dscode.common.domoperations;

import com.xpathy.Condition;
import com.xpathy.Tag;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.style;
import static com.xpathy.Style.*;
import static com.xpathy.Attribute.*;
import static com.xpathy.Style.height;
import static com.xpathy.Style.width;
import com.xpathy.XPathy;

import static com.xpathy.Tag.*;  // needed for wildcard tag: all tags → Tag.STAR if available
import static com.xpathy.Condition.*;
/**
 * Reusable XPathy conditions related to "visibility".
 *
 * This approximates "visible" using only what XPath can see on the element
 * (inline styles / attributes). It intentionally does not try to look at
 * computed styles or layout.
 *
 * Heuristics for "visible":
 *  - display        != none
 *  - visibility     != hidden / collapse
 *  - opacity        != 0
 *  - width          not in {0, 0px, 0%}
 *  - height         not in {0, 0px, 0%}
 *  - no @hidden
 *  - @aria-hidden   != true
 *  - @type          != hidden
 *
 * NOTE:
 *  - This version adds *extra* checks using the raw @style attribute to
 *    handle cases where inline styles omit the trailing semicolon.
 *  - Applying these visibility rules to *ancestors* (i.e. making sure no
 *    ancestor is hidden) requires extending the core XPathy Condition API
 *    to allow raw XPath like `ancestor-or-self::*[...]`. That is not done
 *    here, because it cannot be implemented solely in this helper class.
 */
import com.xpathy.XPathy;
// existing imports...
// import com.xpathy.Condition;
// import static com.xpathy.Style.*;
// import static com.xpathy.Attribute.*;
// etc.

public final class VisibilityConditions {



    private VisibilityConditions() {
        // utility class
    }

    public static Condition invisible() {
        return Condition.not(visible());
    }

    // ... your existing visible() here ...

    /**
     * XPathy locator for:
     *  - any tag that is visible according to visible()
     *  - AND has no ancestor that is invisible() according to the same rules.
     */
    public static XPathy visibleElement() {

        // Base "any element" XPathy, i.e. //*
        XPathy any = new XPathy();

        // Reuse the existing visibility conditions to let XPathy generate
        // the *self* predicates for us.
        XPathy selfVisible = any.byCondition(visible());
        XPathy selfInvisible = any.byCondition(invisible());

        String base = any.getXpath();               // "//*"
        String selfVisibleXpath = selfVisible.getXpath();     // "//*[<visible predicate>]"
        String selfInvisibleXpath = selfInvisible.getXpath(); // "//*[<invisible predicate>]"

        String visiblePredicate = extractPredicate(base, selfVisibleXpath);
        String invisiblePredicate = extractPredicate(base, selfInvisibleXpath);

        // Build final XPath string:
        //
        // //*[
        //    <visiblePredicate>
        //    and not(ancestor::*[<invisiblePredicate>])
        // ]
        String finalXpath =
                base + "[" +
                        visiblePredicate +
                        " and not(ancestor::*[" +
                        invisiblePredicate +
                        "])]";

        return new XPathy(finalXpath);
    }

    // Helper: given "//*" and "//*[(...)]" return "(...)"
    public static String extractPredicate(String base, String xpathWithPredicate) {
        // Expect pattern: base + "[" + predicate + "]"
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }


    /**
     * Condition that can be plugged into any XPathy via .byCondition(...).
     *
     * Example:
     *   XPathy visibleButtons =
     *       button.byText().contains("Save").byCondition(visible());
     *
     * This condition currently checks *only* the element itself. It is made
     * more robust to inline styles that omit trailing semicolons by using
     * additional substring checks on the @style attribute.
     */
    public static Condition visible() {

        return Condition.and(

                // -----------------------------------------------------------------
                // display != none
                // -----------------------------------------------------------------
                // Original semantics (depends on "display:none;" with semicolon)
                // plus a fallback that also matches "display:none" (no semicolon)
                // in the raw @style string, using Attribute.style.contains.
                Condition.not(
                        Condition.or(
                                // Existing XPathy style helper (semicolon-based)
                                Condition.style(display).equals("none"),

                                // Fallback: match "display:none" in @style,
                                // which covers:
                                //   display:none
                                //   display:none;
                                //   display:none!important
                                //   display : none
                                // depending on how Attribute.contains is implemented.
                                Condition.attribute(style).contains("display:none")
                        )
                ),

                // -----------------------------------------------------------------
                // visibility != hidden
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(visibility).equals("hidden"),
                                Condition.attribute(style).contains("visibility:hidden")
                        )
                ),

                // -----------------------------------------------------------------
                // visibility != collapse
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(visibility).equals("collapse"),
                                Condition.attribute(style).contains("visibility:collapse")
                        )
                ),

                // -----------------------------------------------------------------
                // opacity != 0
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                Condition.style(opacity).equals("0"),
                                Condition.attribute(style).contains("opacity:0")
                        )
                ),

                // -----------------------------------------------------------------
                // width not in {0, 0px, 0%}
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                // Original semicolon-based checks:
                                Condition.style(width).equals("0"),
                                Condition.style(width).equals("0px"),
                                Condition.style(width).equals("0%"),
                                // Fallback substring checks that do not require
                                // a trailing semicolon:
                                Condition.attribute(style).contains("width:0"),
                                Condition.attribute(style).contains("width:0px"),
                                Condition.attribute(style).contains("width:0%")
                        )
                ),

                // -----------------------------------------------------------------
                // height not in {0, 0px, 0%}
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.or(
                                // Original semicolon-based checks:
                                Condition.style(height).equals("0"),
                                Condition.style(height).equals("0px"),
                                Condition.style(height).equals("0%"),
                                // Fallback substring checks:
                                Condition.attribute(style).contains("height:0"),
                                Condition.attribute(style).contains("height:0px"),
                                Condition.attribute(style).contains("height:0%")
                        )
                ),

                // -----------------------------------------------------------------
                // no @hidden attribute
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.attribute(hidden).haveIt()
                ),

                // -----------------------------------------------------------------
                // aria-hidden != "true"
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.attribute(aria_hidden).equals("true")
                ),

                // -----------------------------------------------------------------
                // type != "hidden"  (simplified, no special-case for <input>)
                // -----------------------------------------------------------------
                Condition.not(
                        Condition.attribute(type).equals("hidden")
                )
        );
    }
}
