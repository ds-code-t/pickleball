package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "required input" state.
 *
 * FIX:
 *  - notRequiredElement() no longer matches "everything that is not required".
 *    It now matches only elements that look like they participate in a required/not-required pattern
 *    (a "candidate") AND are not required.
 *
 * Condition helpers are unchanged for compatibility, but note that notRequired() = not(required())
 * can still be too broad if applied to an unscoped selector.
 */
public final class RequiredInputConditions {

    private RequiredInputConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (pure XPathy Condition)
     * ------------------------------------------------------------------------- */

    /** Required based on standard HTML @required and common class tokens (no raw ARIA/data-*). */
    public static Condition required() {
        return or(
                requiredAttributeLike(),
                requiredClassLike()
        );
    }

    /** Not required (negation of {@link #required()}). */
    public static Condition notRequired() {
        return not(required());
    }

    /* -------------------------------------------------------------------------
     * Public XPathy API (adds raw XPath fallbacks for aria-/data-* attributes)
     * ------------------------------------------------------------------------- */

    /**
     * Any element (//*) that is considered required using:
     *  - required() Condition rules, plus
     *  - raw XPath checks: @aria-required, @data-required, @data-state='required'
     */
    public static XPathy requiredElement() {
        XPathy any = new XPathy();
        XPathy byCond = any.byCondition(required());

        String base = any.getXpath();        // "//*"
        String withPred = byCond.getXpath(); // "//*[<predicate>]"
        String predicate = extractPredicate(base, withPred);

        String rawRequired =
                "(" +
                        "@aria-required='true' or " +
                        "@data-required='true' or " +
                        "@data-state='required'" +
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawRequired + "]";
        return new XPathy(finalXpath);
    }

    /**
     * Any element (//*) that is considered not required.
     *
     * FIX:
     *  - Previously: //*[not(requiredPred)]  (matched almost everything)
     *  - Now:        //*[(candidatePred) and not(requiredPred)]
     *
     * "Candidate" means the element appears to participate in a required/not-required pattern
     * (native required attr, aria/data flags, or required-ish class tokens).
     */
    public static XPathy notRequiredElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String requiredXpath = requiredElement().getXpath();
        String requiredPred = extractPredicate(base, requiredXpath);

        String candidatePred = buildRequiredCandidatePredicate(base);

        String finalXpath = base + "[(" + candidatePred + ") and not(" + requiredPred + ")]";
        return new XPathy(finalXpath);
    }

    /* -------------------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------------------- */

    private static Condition requiredAttributeLike() {
        return or(
                attribute(required).haveIt(),
                attribute(required).equals("true"),
                attribute(required).equals("required")
        );
    }

    private static Condition requiredClassLike() {
        return or(
                attribute(class_).contains("required"),
                attribute(class_).contains("is-required"),
                attribute(class_).contains("ng-invalid-required")
        );
    }

    /**
     * Candidate predicate to avoid notRequiredElement() matching random nodes that simply
     * aren't required.
     *
     * Candidate if either:
     *  - has required-ish class tokens, OR
     *  - has native @required, OR
     *  - has aria/data state attributes that express required-ness.
     */
    private static String buildRequiredCandidatePredicate(String base) {
        XPathy any = new XPathy();

        // candidate via class tokens (Condition-based)
        XPathy byCondCandidateClass = any.byCondition(requiredClassLike());
        String classCandidatePred = extractPredicate(base, byCondCandidateClass.getXpath());

        // candidate via native/aria/data presence (presence implies the pattern exists)
        String rawCandidate =
                "(" +
                        "@required or " +
                        "@aria-required or " +
                        "@data-required or " +
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
