package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "binary on/off" state that is commonly expressed via
 * boolean-ish attributes (checked/selected) and framework conventions (class tokens, ARIA attributes, etc.).
 *
 * What this class provides:
 *  1) Condition helpers usable with: someTag.byCondition(...)
 *  2) XPathy helpers that can include raw XPath for attributes not modeled in XPathy's Attribute enum
 *     (e.g. aria-checked, aria-selected, data-*), while still reusing Condition where possible.
 *
 * Notes / Heuristics:
 *  - Standard HTML boolean attributes:
 *      * @checked   (checkboxes, radios, toggles implemented with <input>)
 *      * @selected  (<option>)
 *    These are often represented as presence-only, but sometimes appear as checked="true"/"checked".
 *
 *  - Common framework patterns (best-effort; adjust to your UI stack):
 *      * class contains "checked" tokens (Angular Material, PrimeNG, etc.)
 *      * ARIA state attributes: @aria-checked / @aria-selected (raw XPath in this helper)
 *      * data-* state attributes like @data-checked / @data-selected (raw XPath in this helper)
 *
 * If you want to tune this to your specific app, edit the token lists below.
 */
public final class BinaryStateConditions {


    private BinaryStateConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (pure XPathy Condition)
     * ------------------------------------------------------------------------- */

    /** Generic "on" state (checked OR selected OR known 'checked' class tokens). */
    public static Condition on() {
        return or(
                checkedLike(),
                selectedLike(),
                checkedClassLike()
        );
    }

    /** Generic "off" state as the negation of {@link #on()}. */
    public static Condition off() {
        return not(on());
    }

    /** Specifically "checked" (checkbox / radio / toggle implemented as <input>, or class tokens). */
    public static Condition checked() {
        return or(
                checkedLike(),
                checkedClassLike()
        );
    }

    public static Condition notChecked() {
        return not(checked());
    }

    /** Specifically "selected" (usually <option>, sometimes class tokens). */
    public static Condition selected() {
        return or(
                selectedLike(),
                selectedClassLike()
        );
    }

    public static Condition notSelected() {
        return not(selected());
    }

    /**
     * "Checked" based only on @checked presence/value.
     * Useful when you know you're dealing with <input>.
     */
    public static Condition checkedAttributeOnly() {
        return checkedLike();
    }

    /**
     * "Selected" based only on @selected presence/value.
     * Useful when you know you're dealing with <option>.
     */
    public static Condition selectedAttributeOnly() {
        return selectedLike();
    }

    /* -------------------------------------------------------------------------
     * Public XPathy API (supports raw XPath for attrs not in Attribute enum)
     * ------------------------------------------------------------------------- */

    /**
     * Returns an XPathy locator for "any element" (//*)
     * that is considered "on" using both:
     *  - Condition-based rules (checked/selected/class tokens)
     *  - Raw XPath rules for ARIA/data-* state attributes
     */
    public static XPathy onElement() {
        XPathy any = new XPathy();
        XPathy selfOnByCondition = any.byCondition(on());

        String base = any.getXpath();                 // "//*"
        String withPred = selfOnByCondition.getXpath(); // "//*[<predicate>]"
        String predicate = extractPredicate(base, withPred);

        // Add raw XPath "on" rules (ARIA/data-*) to the predicate:
        String rawOn =
                "(" +
                        "@aria-checked='true' or @aria-checked='mixed' or " +
                        "@aria-selected='true' or " +
                        "@data-checked='true' or @data-selected='true' or " +
                        "@data-state='checked' or @data-state='selected' or @data-state='on'" +
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawOn + "]";
        return new XPathy(finalXpath);
    }

    /** Same as {@link #onElement()} but negated (off). */
    public static XPathy offElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();
        String onXpath = onElement().getXpath();

        // onElement is: "//*[ ... ]" -> extract its predicate then negate it
        String onPred = extractPredicate(base, onXpath);

        String finalXpath = base + "[not(" + onPred + ")]";
        return new XPathy(finalXpath);
    }

    /**
     * "Checked" locator with raw ARIA/data-* fallback.
     * Useful for checkbox/radio/toggle UIs where the native <input> is hidden and state is on a wrapper.
     */
    public static XPathy checkedElement() {
        XPathy any = new XPathy();
        XPathy selfCheckedByCondition = any.byCondition(checked());

        String base = any.getXpath();
        String withPred = selfCheckedByCondition.getXpath();
        String predicate = extractPredicate(base, withPred);

        String rawChecked =
                "(" +
                        "@aria-checked='true' or @aria-checked='mixed' or " +
                        "@data-checked='true' or @data-state='checked' or @data-state='on'" +
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawChecked + "]";
        return new XPathy(finalXpath);
    }

    /** "Selected" locator with raw ARIA/data-* fallback. */
    public static XPathy selectedElement() {
        XPathy any = new XPathy();
        XPathy selfSelectedByCondition = any.byCondition(selected());

        String base = any.getXpath();
        String withPred = selfSelectedByCondition.getXpath();
        String predicate = extractPredicate(base, withPred);

        String rawSelected =
                "(" +
                        "@aria-selected='true' or " +
                        "@data-selected='true' or @data-state='selected'" +
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawSelected + "]";
        return new XPathy(finalXpath);
    }

    /* -------------------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------------------- */

    /**
     * @checked is often presence-only, but can also be:
     *  - checked="checked"
     *  - checked="true"
     */
    private static Condition checkedLike() {
        return or(
                attribute(checked).haveIt(),
                attribute(checked).equals("true"),
                attribute(checked).equals("checked")
        );
    }

    /**
     * @selected is often presence-only, but can also be:
     *  - selected="selected"
     *  - selected="true"
     */
    private static Condition selectedLike() {
        return or(
                attribute(selected).haveIt(),
                attribute(selected).equals("true"),
                attribute(selected).equals("selected")
        );
    }

    /**
     * Framework class conventions (best-effort).
     * Adjust these tokens to match your UI libraries.
     */
    private static Condition checkedClassLike() {
        return or(
                attribute(class_).contains("checked"),
                attribute(class_).contains("is-checked"),
                attribute(class_).contains("mat-checkbox-checked"),
                attribute(class_).contains("mat-radio-checked"),
                attribute(class_).contains("p-highlight") // e.g., PrimeNG uses p-highlight for selected-ish
        );
    }

    private static Condition selectedClassLike() {
        return or(
                attribute(class_).contains("selected"),
                attribute(class_).contains("is-selected"),
                attribute(class_).contains("active"),
                attribute(class_).contains("p-highlight")
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
