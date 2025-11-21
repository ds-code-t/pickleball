package tools.dscode.common.domoperations;


import com.xpathy.Condition;

import static com.xpathy.Style.*;
import static com.xpathy.Attribute.*;
import static com.xpathy.Style.height;
import static com.xpathy.Style.width;

/**
 * Reusable XPathy conditions related to "visibility".
 *
 * This approximates "visible" using only what XPath can see:
 *  - display        != none
 *  - visibility     != hidden / collapse
 *  - opacity        != 0
 *  - width          not in {0, 0px, 0%}
 *  - height         not in {0, 0px, 0%}
 *  - no @hidden
 *  - @aria-hidden   != true
 *  - @type          != hidden
 *
 * NOTE: This only checks inline styles / attributes, not computed CSS.
 */
public final class VisibilityConditions {

    private VisibilityConditions() {
        // utility class
    }

    /**
     * Condition that can be plugged into any XPathy via .byCondition(...).
     *
     * Example:
     *   XPathy visibleButtons = button.byText().contains("Save").byCondition(visible());
     */
    public static Condition visible() {

        return Condition.and(
                // display != none
                Condition.not(
                        Condition.style(display).equals("none")
                ),

                // visibility != hidden
                Condition.not(
                        Condition.style(visibility).equals("hidden")
                ),

                // visibility != collapse
                Condition.not(
                        Condition.style(visibility).equals("collapse")
                ),

                // opacity != 0
                Condition.not(
                        Condition.style(opacity).equals("0")
                ),

                // width not in {0, 0px, 0%}
                Condition.not(
                        Condition.or(
                                Condition.style(width).equals("0"),
                                Condition.style(width).equals("0px"),
                                Condition.style(width).equals("0%")
                        )
                ),

                // height not in {0, 0px, 0%}
                Condition.not(
                        Condition.or(
                                Condition.style(height).equals("0"),
                                Condition.style(height).equals("0px"),
                                Condition.style(height).equals("0%")
                        )
                ),

                // no @hidden attribute
                // (interpreted as "element is hidden" when present)
                Condition.not(
                        // assumes Condition.attribute(...).haveIt() or equivalent exists
                        // adjust if your Condition API uses a different name
                        Condition.attribute(hidden).haveIt()
                ),

                // aria-hidden != "true"
                Condition.not(
                        Condition.attribute(aria_hidden).equals("true")
                ),

                // type != "hidden"  (simplified, no special-case for <input>)
                Condition.not(
                        Condition.attribute(type).equals("hidden")
                )
        );
    }
}
