package tools.dscode.common.domoperations;

import com.xpathy.Condition;

import static com.xpathy.Style.*;
import static com.xpathy.Attribute.*;
import static com.xpathy.Style.height;
import static com.xpathy.Style.width;

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
public final class VisibilityConditions {

    private VisibilityConditions() {
        // utility class
    }

    public static Condition invisible() {
        return Condition.not( visible() );
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
