package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.xpathy.Attribute.*;

/**
 * Reusable XPathy conditions related to "visibility".
 *
 * This approximates "visible" using only what XPath can see on the element
 * (inline styles / attributes). It intentionally does not try to look at
 * computed styles or layout.
 *
 * Important:
 * - This class intentionally does NOT use Condition.style(...).equals(...)
 *   because XPathy expands that into substring-style checks like:
 *     contains(translate(@style, ' ', ''), 'width:0px;')
 *   which can false-match "border-width:0px;".
 *
 * Efficiency notes:
 * - Expensive normalization/translation checks are placed last behind cheap
 *   existence/sub-string guards so XPath can fail fast.
 * - Repeated class-marker checks for noDisplay are grouped so normalized @class
 *   is evaluated once instead of once per marker.
 */
public final class VisibilityConditions {

    private static final String NORMALIZED_CLASS_EXPRESSION =
            "concat(' ', normalize-space(@class), ' ')";

    private static final String NORMALIZED_INLINE_STYLE_EXPRESSION =
            "concat(';', translate(normalize-space(@style), ' ', ''), ';')";

    private VisibilityConditions() {
        // utility class
    }

    /** Backwards-compatible: still means "not(visible())". */
    public static Condition invisible() {
        return Condition.not(visible());
    }

    /**
     * Optional helper if you want to exclude aria-hidden content explicitly
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

        XPathy selfVisible = any.byCondition(visible());
        XPathy ancestorInv = any.byCondition(ancestorInvisible());

        String base = any.getXpath(); // "//*"
        String selfVisibleXpath = selfVisible.getXpath();
        String ancestorInvXpath = ancestorInv.getXpath();

        String visiblePredicate = extractPredicate(base, selfVisibleXpath);
        String ancestorInvisiblePredicate = extractPredicate(base, ancestorInvXpath);

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
     * Heuristics for elements commonly made non-visible.
     *
     * Notes:
     * - CSS property checks are boundary-safe and whitespace-normalized.
     * - Utility-class checks are done against @class tokens, not @style substrings.
     */
    public static Condition noDisplay = Condition.or(
            cssPropertyEqualsAny("display", "none"),
            noDisplayClassMarkers()
    );

    /**
     * Visual "visible" condition for the element itself.
     */
    public static Condition visible() {
        return Condition.and(

                // display != none
                Condition.not(noDisplay),

                // visibility != hidden/collapse
                Condition.not(cssPropertyEqualsAny("visibility", "hidden", "collapse")),

                // opacity != 0
                Condition.not(cssPropertyEqualsAny("opacity", "0")),

                // width not in {0, 0px, 0%}
                Condition.not(cssPropertyEqualsAny("width", "0", "0px", "0%")),

                // height not in {0, 0px, 0%}
                Condition.not(cssPropertyEqualsAny("height", "0", "0px", "0%")),

                // no @hidden attribute
                Condition.not(Condition.attribute(hidden).haveIt()),

                // type != "hidden" (mostly for <input>, kept for backwards behavior)
                Condition.not(Condition.attribute(type).equals("hidden"))

                // NOTE: aria-hidden intentionally not used for visual visibility.
        );
    }

    /**
     * Reduced invisibility checks that are safer to apply to ancestors.
     *
     * Kept:
     * - display:none
     * - visibility:hidden / collapse
     * - opacity:0
     * - @hidden
     *
     * Excluded:
     * - width/height
     * - type=hidden
     * - aria-hidden
     */
    public static Condition ancestorInvisible() {
        return Condition.or(

                // display:none
                noDisplay,

                // visibility:hidden or collapse
                cssPropertyEqualsAny("visibility", "hidden", "collapse"),

                // opacity:0
                cssPropertyEqualsAny("opacity", "0"),

                // @hidden attribute
                Condition.attribute(hidden).haveIt()
        );
    }

    /**
     * Matches one or more exact CSS declarations in inline @style
     * using normalized declaration boundaries.
     *
     * Example:
     *   style=" color : red ; width : 0px "
     *
     * normalized to:
     *   ;color:red;width:0px;
     *
     * then matched against:
     *   ;width:0px;
     *
     * This avoids false positives on:
     *   border-width:0px
     *   min-width:0px
     *   width:0.5rem
     *
     * Efficiency:
     * - Short-circuits on @style existence.
     * - Short-circuits on raw @style containing the property name.
     * - Short-circuits on raw @style containing at least one candidate value.
     * - Only then evaluates the normalized/boundary-safe contains checks.
     */
    private static Condition cssPropertyEqualsAny(String property, String... values) {
        List<String> exactMatches = new ArrayList<>(values.length);
        Set<String> quickValueHints = new LinkedHashSet<>();

        for (String value : values) {
            String compactValue = value.replace(" ", "");
            quickValueHints.add(compactValue);
            exactMatches.add(
                    "contains(" + NORMALIZED_INLINE_STYLE_EXPRESSION +
                            ", ';" + property + ":" + compactValue + ";')"
            );
        }

        String predicate =
                "@style" +
                        " and contains(@style, '" + property + "')" +
                        " and (" + orContains("@style", quickValueHints) + ")" +
                        " and (" + orJoin(exactMatches) + ")";

        return rawPredicate(predicate);
    }

    /**
     * Whole-token class match.
     *
     * Matches:
     *   class="foo sr-only bar"
     *
     * Does not match:
     *   class="not-sr-only-ish"
     *
     * Efficiency:
     * - Short-circuits on @class existence.
     * - Short-circuits on raw @class containing the token text at all.
     * - Only then evaluates normalized token-boundary matching.
     */
    private static Condition hasClassToken(String token) {
        return rawPredicate(
                "@class" +
                        " and contains(@class, '" + token + "')" +
                        " and contains(" + NORMALIZED_CLASS_EXPRESSION + ", ' " + token + " ')"
        );
    }

    /**
     * Whole-token-prefix class match.
     *
     * Matches:
     *   class="foo screen-reader-text bar"
     *
     * Does not match:
     *   class="foo myscreen-reader-text bar"
     *
     * Efficiency:
     * - Short-circuits on @class existence.
     * - Short-circuits on raw @class containing the prefix text at all.
     * - Only then evaluates normalized token-prefix matching.
     */
    private static Condition hasClassTokenStartingWith(String tokenPrefix) {
        return rawPredicate(
                "@class" +
                        " and contains(@class, '" + tokenPrefix + "')" +
                        " and contains(" + NORMALIZED_CLASS_EXPRESSION + ", ' " + tokenPrefix + "')"
        );
    }

    /**
     * Groups the common class-based noDisplay markers into one predicate so
     * normalized @class is evaluated once instead of once per OR branch.
     */
    private static Condition noDisplayClassMarkers() {
        List<String> exactTokens = List.of(
                "sr-only",
                "visually-hidden",
                "visuallyhidden",
                "offscreen",
                "off-screen"
        );

        List<String> tokenPrefixes = List.of(
                "screen-reader-",
                "screenreader-",
                "offscreen-",
                "off-screen-"
        );

        List<String> normalizedChecks = new ArrayList<>(exactTokens.size() + tokenPrefixes.size());
        Set<String> quickHints = new LinkedHashSet<>();

        for (String token : exactTokens) {
            quickHints.add(token);
            normalizedChecks.add(
                    "contains(" + NORMALIZED_CLASS_EXPRESSION + ", ' " + token + " ')"
            );
        }

        for (String prefix : tokenPrefixes) {
            quickHints.add(prefix);
            normalizedChecks.add(
                    "contains(" + NORMALIZED_CLASS_EXPRESSION + ", ' " + prefix + "')"
            );
        }

        String predicate =
                "@class" +
                        " and (" + orContains("@class", quickHints) + ")" +
                        " and (" + orJoin(normalizedChecks) + ")";

        return rawPredicate(predicate);
    }

    /**
     * Builds an XPath OR-chain of contains(expression, 'value') checks.
     */
    private static String orContains(String expression, Iterable<String> values) {
        List<String> parts = new ArrayList<>();

        for (String value : values) {
            parts.add("contains(" + expression + ", '" + value + "')");
        }

        return orJoin(parts);
    }

    /**
     * Joins already-built XPath boolean expressions with OR.
     */
    private static String orJoin(List<String> expressions) {
        if (expressions.isEmpty()) {
            throw new IllegalArgumentException("At least one expression is required");
        }

        if (expressions.size() == 1) {
            return expressions.get(0);
        }

        return String.join(" or ", expressions);
    }

    /**
     * Builds a normalized inline-style string expression:
     *
     *   concat(';', translate(normalize-space(@style), ' ', ''), ';')
     *
     * Example:
     *   "width: 0px; color: red"
     * becomes:
     *   ";width:0px;color:red;"
     */
    private static String normalizedInlineStyleExpression() {
        return NORMALIZED_INLINE_STYLE_EXPRESSION;
    }

    /**
     * Creates a raw XPath predicate Condition.
     */
    private static Condition rawPredicate(String predicate) {
        return new RawCondition(predicate);
    }

    /**
     * Minimal Condition subclass so we can inject a raw predicate string
     * without changing the public API of this utility class.
     */
    private static final class RawCondition extends Condition {
        private RawCondition(String predicate) {
            super(ConditionType.ATTRIBUTE);
            this.condition = predicate;
        }
    }
}