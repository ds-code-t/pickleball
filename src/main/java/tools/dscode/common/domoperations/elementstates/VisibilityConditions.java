package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.Style;
import com.xpathy.XPathy;

import java.util.ArrayList;
import java.util.List;

import static com.xpathy.Attribute.*;
import static com.xpathy.Style.display;
import static com.xpathy.Style.height;
import static com.xpathy.Style.opacity;
import static com.xpathy.Style.visibility;
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

        String base = any.getXpath(); // "//*"
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
     * NOTE:
     *  - Actual CSS-property checks are boundary-aware and whitespace-normalized.
     *  - The hidden-marker checks below are class-based heuristics, tightened to
     *    whole class tokens or token prefixes instead of raw substring matching.
     */
    public static Condition noDisplay = Condition.or(
            styleOrInlineEqualsAny(display, "display", "none"),

            // Common utility classes
            hasClassToken("sr-only"),
            hasClassToken("visually-hidden"),
            hasClassToken("visuallyhidden"),
            hasClassToken("offscreen"),
            hasClassToken("off-screen"),

            // Common class prefixes
            hasClassTokenStartingWith("screen-reader-"),
            hasClassTokenStartingWith("screenreader-"),
            hasClassTokenStartingWith("offscreen-"),
            hasClassTokenStartingWith("off-screen-")
    );

    /**
     * Visual "visible" condition for the element itself.
     */
    public static Condition visible() {

        return Condition.and(

                // display != none
                Condition.not(noDisplay),

                // visibility != hidden
                Condition.not(styleOrInlineEqualsAny(visibility, "visibility", "hidden")),

                // visibility != collapse
                Condition.not(styleOrInlineEqualsAny(visibility, "visibility", "collapse")),

                // opacity != 0
                Condition.not(styleOrInlineEqualsAny(opacity, "opacity", "0")),

                // width not in {0, 0px, 0%}
                Condition.not(styleOrInlineEqualsAny(width, "width", "0", "0px", "0%")),

                // height not in {0, 0px, 0%}
                Condition.not(styleOrInlineEqualsAny(height, "height", "0", "0px", "0%")),

                // no @hidden attribute
                Condition.not(Condition.attribute(hidden).haveIt()),

                // type != "hidden" (mostly for <input>, kept for backwards behavior)
                Condition.not(Condition.attribute(type).equals("hidden"))

                // NOTE: aria-hidden intentionally removed from visual visibility.
        );
    }

    /**
     * Reduced invisibility checks that are safer to apply to ancestors.
     *
     * Kept:
     *  - display:none, hidden attribute, visibility hidden/collapse, opacity:0
     *    (these usually *do* hide descendants visually).
     */
    public static Condition ancestorInvisible() {

        return Condition.or(

                // display:none
                noDisplay,

                // visibility:hidden or collapse
                styleOrInlineEqualsAny(visibility, "visibility", "hidden", "collapse"),

                // opacity:0
                styleOrInlineEqualsAny(opacity, "opacity", "0"),

                // @hidden attribute
                Condition.attribute(hidden).haveIt()
        );
    }

    /**
     * Combines:
     *  1) XPathy's direct style(...) equals(...) checks
     *  2) a boundary-aware raw @style declaration matcher
     *
     * The raw @style matcher normalizes whitespace and matches whole CSS declarations,
     * so width:0 no longer matches border-width:0.
     */
    private static Condition styleOrInlineEqualsAny(Style styleName, String property, String... values) {
        List<Condition> conditions = new ArrayList<>(values.length + 1);

        for (String value : values) {
            conditions.add(Condition.style(styleName).equals(value));
        }

        conditions.add(inlineStylePropertyEqualsAny(property, values));

        return Condition.or(conditions.toArray(new Condition[0]));
    }

    /**
     * Matches a whole inline CSS declaration after normalizing whitespace:
     *
     *   style=" color : red ; width : 0px "
     *
     * becomes effectively:
     *
     *   ;color:red;width:0px;
     *
     * Then we search for:
     *
     *   ;width:0px;
     *
     * This avoids false positives on:
     *   border-width:0px
     *   min-width:0px
     *   width:0.5rem
     */
    private static Condition inlineStylePropertyEqualsAny(String property, String... values) {
        List<Condition> conditions = new ArrayList<>(values.length);

        for (String value : values) {
            String compactValue = value.replace(" ", "");
            conditions.add(rawPredicate(
                    "contains(" + normalizedInlineStyleExpression() + ", ';" + property + ":" + compactValue + ";')"
            ));
        }

        return Condition.or(conditions.toArray(new Condition[0]));
    }

    /**
     * Whole-token class match:
     *   class="foo sr-only bar" -> matches sr-only
     *   class="not-sr-only-ish" -> does not match sr-only
     */
    private static Condition hasClassToken(String token) {
        return rawPredicate(
                "contains(concat(' ', normalize-space(@class), ' '), ' " + token + " ')"
        );
    }

    /**
     * Whole-token-prefix class match:
     *   class="foo screen-reader-text bar" -> matches "screen-reader-"
     *   class="foo myscreen-reader-text bar" -> does not match "screen-reader-"
     */
    private static Condition hasClassTokenStartingWith(String tokenPrefix) {
        return rawPredicate(
                "contains(concat(' ', normalize-space(@class), ' '), ' " + tokenPrefix + "')"
        );
    }

    /**
     * Builds a normalized inline-style string expression:
     *
     *   concat(';', translate(normalize-space(@style), ' ', ''), ';')
     *
     * Examples:
     *   "width: 0px; color: red"
     *   -> ";width:0px;color:red;"
     */
    private static String normalizedInlineStyleExpression() {
        return "concat(';', translate(normalize-space(@style), ' ', ''), ';')";
    }

    /**
     * Creates a raw XPath predicate Condition.
     */
    private static Condition rawPredicate(String predicate) {
        return new RawCondition(predicate);
    }

    /**
     * Minimal Condition subclass so we can safely inject a raw predicate string
     * without changing the public API of this utility class.
     */
    private static final class RawCondition extends Condition {
        private RawCondition(String predicate) {
            super(ConditionType.ATTRIBUTE);
            this.condition = predicate;
        }
    }
}