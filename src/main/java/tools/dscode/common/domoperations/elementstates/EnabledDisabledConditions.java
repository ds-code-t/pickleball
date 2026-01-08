package tools.dscode.common.domoperations;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "enabled/disabled" state.
 *
 * What "disabled" typically looks like:
 *  - Standard HTML boolean attribute: @disabled (presence-only or disabled="true"/"disabled")
 *  - Accessibility state:            @aria-disabled="true"   (common on custom controls)
 *  - Framework conventions:
 *      * class contains "disabled", "is-disabled", "mat-button-disabled", etc.
 *      * data-* flags like @data-disabled="true" or @data-state="disabled"
 *
 * This class provides:
 *  - Condition helpers usable with: someTag.byCondition(...)
 *  - XPathy helpers that include raw XPath for attributes not modeled in XPathy's Attribute enum
 *    (e.g. aria-disabled, data-disabled)
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

    /** Enabled is defined as NOT disabled(). */
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

    /** Any element (//*) that is considered enabled (negation of disabledElement() predicate). */
    public static XPathy enabledElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String disabledXpath = disabledElement().getXpath();
        String disabledPred = extractPredicate(base, disabledXpath);

        String finalXpath = base + "[not(" + disabledPred + ")]";
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
     * Helper: given base "//*" and "//*[(...)]" return "(...)"
     */
    public static String extractPredicate(String base, String xpathWithPredicate) {
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }
}
