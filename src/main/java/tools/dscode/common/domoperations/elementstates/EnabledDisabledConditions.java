package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "enabled/disabled" state.
 *
 * FIX:
 *  - enabledElement() no longer matches "everything that is not disabled".
 *    It now matches only elements that look like they participate in an enabled/disabled pattern
 *    (a "candidate") AND are not disabled.
 *
 * Condition helpers are unchanged for compatibility, but note that enabled() = not(disabled())
 * can still be too broad if applied to an unscoped selector.
 */
public final class EnabledDisabledConditions {

    private EnabledDisabledConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (pure XPathy Condition)
     * ------------------------------------------------------------------------- */

    /** Disabled based on standard HTML and common class tokens (no raw ARIA/data-*). */
    public static Condition disabled() {
        return or(
                disabledAttributeLike(),
                disabledClassLike()
        );
    }

    /** Enabled is defined as NOT disabled(). (Logical negation; may be broad if unscoped.) */
    public static Condition enabled() {
        return not(disabled());
    }

    /* -------------------------------------------------------------------------
     * Public XPathy API (adds raw XPath fallbacks for aria-/data-* attributes)
     * ------------------------------------------------------------------------- */

    /**
     * Any element (//*) that is considered disabled using:
     *  - disabled() Condition rules, plus
     *  - raw XPath checks: @aria-disabled, @data-disabled, @data-state='disabled'
     */
    public static XPathy disabledElement() {
        XPathy any = new XPathy();
        XPathy byCond = any.byCondition(disabled());

        String base = any.getXpath();        // "//*"
        String withPred = byCond.getXpath(); // "//*[<predicate>]"
        String predicate = extractPredicate(base, withPred);

        String rawDisabled =
                "(" +
                        "@aria-disabled='true' or " +
                        "@data-disabled='true' or " +
                        "@data-state='disabled'" +
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawDisabled + "]";
        return new XPathy(finalXpath);
    }

    /**
     * Any element (//*) that is considered enabled.
     *
     * FIX:
     *  - Previously: //*[not(disabledPred)]  (matched almost everything)
     *  - Now:        //*[(candidatePred) and not(disabledPred)]
     *
     * "Candidate" means the element appears to participate in an enabled/disabled pattern
     * (native disabled attr, aria/data flags, or disabled-ish class tokens).
     */
    public static XPathy enabledElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String disabledXpath = disabledElement().getXpath();
        String disabledPred = extractPredicate(base, disabledXpath);

        String candidatePred = buildEnabledDisabledCandidatePredicate(base);

        String finalXpath = base + "[(" + candidatePred + ") and not(" + disabledPred + ")]";
        return new XPathy(finalXpath);
    }

    /* -------------------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------------------- */

    /**
     * @disabled is often presence-only, but can appear as:
     *  - disabled="disabled"
     *  - disabled="true"
     */
    private static Condition disabledAttributeLike() {
        return or(
                attribute(disabled).haveIt(),
                attribute(disabled).equals("true"),
                attribute(disabled).equals("disabled")
        );
    }

    /**
     * Framework class conventions (best-effort).
     * Tune these for your component libraries.
     */
    private static Condition disabledClassLike() {
        return or(
                attribute(class_).contains("disabled"),
                attribute(class_).contains("is-disabled"),
                attribute(class_).contains("mat-button-disabled"),
                attribute(class_).contains("mat-checkbox-disabled"),
                attribute(class_).contains("mat-radio-disabled"),
                attribute(class_).contains("p-disabled")
        );
    }

    /**
     * Candidate predicate to avoid enabledElement() matching random nodes that simply
     * aren't disabled.
     *
     * Candidate if either:
     *  - has disabled-ish class tokens, OR
     *  - has native @disabled, OR
     *  - has aria/data state attributes that express disabled/enabled state.
     */
    private static String buildEnabledDisabledCandidatePredicate(String base) {
        XPathy any = new XPathy();

        // candidate via class tokens (Condition-based)
        XPathy byCondCandidateClass = any.byCondition(disabledClassLike());
        String classCandidatePred = extractPredicate(base, byCondCandidateClass.getXpath());

        // candidate via native/aria/data presence (presence implies the pattern exists)
        String rawCandidate =
                "(" +
                        "@disabled or " +
                        "@aria-disabled or " +
                        "@data-disabled or " +
                        "@data-state" +
                        ")";

        return "(" + classCandidatePred + " or " + rawCandidate + ")";
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
