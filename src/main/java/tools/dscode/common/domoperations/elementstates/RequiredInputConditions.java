package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "required input" state.
 *
 * What "required" typically looks like:
 *  - Standard HTML boolean attribute: @required (presence-only or required="true"/"required")
 *  - Accessibility attribute:         @aria-required="true" (common on custom controls)
 *  - Framework conventions:
 *      * class tokens like "required", "is-required"
 *      * Angular validation classes like "ng-invalid-required" (seen in some setups)
 *      * data-* flags like @data-required="true" or @data-state="required"
 *
 * This class provides:
 *  - Condition helpers usable with: someTag.byCondition(...)
 *  - XPathy helpers that include raw XPath for attrs not modeled in XPathy's Attribute constants
 *    (e.g. aria-required, data-required).
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

    /** Any element (//*) that is considered not required (negation of requiredElement() predicate). */
    public static XPathy notRequiredElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String requiredXpath = requiredElement().getXpath();
        String requiredPred = extractPredicate(base, requiredXpath);

        String finalXpath = base + "[not(" + requiredPred + ")]";
        return new XPathy(finalXpath);
    }

    /* -------------------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------------------- */

    /**
     * @required is often presence-only, but can appear as:
     *  - required="required"
     *  - required="true"
     */
    private static Condition requiredAttributeLike() {
        return or(
                attribute(required).haveIt(),
                attribute(required).equals("true"),
                attribute(required).equals("required")
        );
    }

    /**
     * Framework class conventions (best-effort).
     * Tune these for your component libraries / validators.
     */
    private static Condition requiredClassLike() {
        return or(
                attribute(class_).contains("required"),
                attribute(class_).contains("is-required"),
                attribute(class_).contains("ng-invalid-required")
        );
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
