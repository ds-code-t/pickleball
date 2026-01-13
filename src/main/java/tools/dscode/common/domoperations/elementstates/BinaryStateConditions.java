package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "binary on/off" state that is commonly expressed via
 * boolean-ish attributes (checked/selected) and framework conventions (class tokens, ARIA attributes, etc.).
 *
 * FIX:
 *  - offElement() no longer matches "everything that is not on".
 *    It now matches only elements that look like a "binary-state candidate"
 *    (i.e., they carry any checked/selected/aria/data/class signals) AND are not on.
 *
 *  - Condition helpers (off/notChecked/notSelected) are unchanged for compatibility,
 *    but note: using them on very broad selectors can still match unrelated elements,
 *    because "not(on)" is logically true for elements that don't participate at all.
 *
 * UPDATE:
 *  - Treat @value='true' as an additional "on" signal for onElement()
 *  - Treat @value='true' or @value='false' as a "binary candidate" signal for offElement()
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
                checkedClassLike(),
                selectedClassLike()
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
     *  - Raw XPath rule for @value='true' (binary widgets that store state in value)
     */
    public static XPathy onElement() {
        XPathy any = new XPathy();
        XPathy selfOnByCondition = any.byCondition(on());

        String base = any.getXpath();                    // "//*"
        String withPred = selfOnByCondition.getXpath();  // "//*[<predicate>]"
        String predicate = extractPredicate(base, withPred);

        String rawOn =
                "(" +
                        "@aria-checked='true' or @aria-checked='mixed' or " +
                        "@aria-selected='true' or " +
                        "@data-checked='true' or @data-selected='true' or " +
                        "@data-state='checked' or @data-state='selected' or @data-state='on' or " +
                        "@value='true'" + // NEW
                        ")";

        String finalXpath = base + "[" + predicate + " or " + rawOn + "]";
        return new XPathy(finalXpath);
    }

    /**
     * Off elements:
     *
     * FIX:
     *  - Previously:  //*[not(<onPred>)]  (matched almost everything)
     *  - Now:         //*[(<candidatePred>) and not(<onPred>)]
     *
     * "Candidate" means the element appears to participate in a binary state pattern
     * (checked/selected attrs, aria/data state attrs, known class tokens, or value='true|false').
     */
    public static XPathy offElement() {
        XPathy any = new XPathy();
        String base = any.getXpath();

        String onXpath = onElement().getXpath();
        String onPred = extractPredicate(base, onXpath);

        String candidatePred = buildBinaryCandidatePredicate(base);

        String finalXpath = base + "[(" + candidatePred + ") and not(" + onPred + ")]";
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

    private static Condition checkedLike() {
        return or(
                attribute(checked).haveIt(),
                attribute(checked).equals("true"),
                attribute(checked).equals("checked")
        );
    }

    private static Condition selectedLike() {
        return or(
                attribute(selected).haveIt(),
                attribute(selected).equals("true"),
                attribute(selected).equals("selected")
        );
    }

    private static Condition checkedClassLike() {
        return or(
                attribute(class_).contains("checked"),
                attribute(class_).contains("is-checked"),
                attribute(class_).contains("mat-checkbox-checked"),
                attribute(class_).contains("mat-radio-checked"),
                attribute(class_).contains("p-highlight") // PrimeNG uses p-highlight for selected-ish
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
     * Build a predicate that identifies elements that likely participate in a binary-state pattern.
     * This prevents offElement() from matching random elements that simply aren't "on".
     */
    private static String buildBinaryCandidatePredicate(String base) {
        XPathy any = new XPathy();

        // candidate via class tokens (Condition-based)
        XPathy byCondCandidateClass = any.byCondition(or(checkedClassLike(), selectedClassLike()));
        String classCandidatePred = extractPredicate(base, byCondCandidateClass.getXpath());

        // candidate via native attrs (presence)
        String rawNativeCandidate =
                "(" +
                        "@checked or @selected" +
                        ")";

        // candidate via ARIA/data-* presence (not values; presence means the pattern exists)
        String rawStateCandidate =
                "(" +
                        "@aria-checked or @aria-selected or " +
                        "@data-checked or @data-selected or @data-state" +
                        ")";

        // candidate via "value is explicitly boolean-ish"
        String rawValueCandidate =
                "(" +
                        "@value='true' or @value='false'" + // NEW
                        ")";

        return "("
                + classCandidatePred
                + " or " + rawNativeCandidate
                + " or " + rawStateCandidate
                + " or " + rawValueCandidate
                + ")";
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
