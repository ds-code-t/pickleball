package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "binary on/off" state that is commonly expressed via
 * boolean-ish attributes (checked/selected) and framework conventions (class tokens, ARIA attributes, etc.).
 *
 * Key behaviors:
 * - offElement() matches only "binary-state candidates" AND not(on).
 * - Binary candidates now include common input types and ARIA roles (checkbox/radio/switch/toggle patterns),
 *   so unchecked controls with no @checked/@selected are still detected as binary candidates.
 * - @value='true'/'false' is treated as a LAST-RESORT state signal (only when no other state signals exist).
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
     * Public XPathy API
     * ------------------------------------------------------------------------- */

    /**
     * Returns an XPathy locator for "any element" (//*)
     * that is considered "on" using:
     *  - Condition-based rules (checked/selected/class tokens)
     *  - Raw XPath rules for ARIA/data-* state attributes
     *  - Raw XPath @value='true' ONLY as a last resort (when no other state signals exist)
     */
    public static XPathy onElement() {
        XPathy any = new XPathy();
        XPathy selfOnByCondition = any.byCondition(on());

        String base = any.getXpath();                    // "//*"
        String withPred = selfOnByCondition.getXpath();  // "//*[<predicate>]"
        String predicate = extractPredicate(base, withPred);

        // Primary "on" signals (non-value)
        String rawOnPrimary =
                "(" +
                        "@checked or @selected or " +
                        "@aria-checked='true' or @aria-checked='mixed' or " +
                        "@aria-selected='true' or " +
                        "@aria-pressed='true' or " +
                        "@data-checked='true' or @data-selected='true' or " +
                        "@data-state='checked' or @data-state='selected' or @data-state='on'" +
                        ")";

        // NEW: value-based "on" is only used if NONE of the primary signals exist
        // (so value doesn't override/compete with checked/selected/aria/data)
        String rawOnValueFallback =
                "(" +
                        "@value='true' and not(" +
                        "@checked or @selected or " +
                        "@aria-checked or @aria-selected or @aria-pressed or " +
                        "@data-checked or @data-selected or @data-state" +
                        ")" +
                        ")";

        String finalXpath = base + "[(" + predicate + ") or " + rawOnPrimary + " or " + rawOnValueFallback + "]";
        return new XPathy(finalXpath);
    }

    /**
     * Off elements:
     *  - Matches only binary-state candidates AND not(on).
     *
     * This will now include unchecked <input type=checkbox|radio> even when @checked/@selected are absent,
     * because those are considered binary candidates by type/role.
     *
     * Additionally:
     *  - If an element is a binary candidate and has no explicit state signals at all,
     *    it will be treated as off (because it won't match onPred).
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
     * Note: does NOT use @value fallback because "checked" is a stronger semantic than value.
     */
    public static XPathy checkedElement() {
        XPathy any = new XPathy();
        XPathy selfCheckedByCondition = any.byCondition(checked());

        String base = any.getXpath();
        String withPred = selfCheckedByCondition.getXpath();
        String predicate = extractPredicate(base, withPred);

        String rawChecked =
                "(" +
                        "@checked or " +
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
                        "@selected or " +
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
                attribute(class_).contains("p-highlight")
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
     *
     * UPDATED:
     *  - Includes common input types for checkboxes/radios (even when unchecked, no @checked attr)
     *  - Includes common ARIA roles used for binary/toggle widgets
     *  - Includes aria-pressed presence as a toggle signal
     *  - Includes @value='true'/'false' as candidate (but value is only a fallback for ON)
     */
    private static String buildBinaryCandidatePredicate(String base) {
        XPathy any = new XPathy();

        // candidate via class tokens (Condition-based)
        XPathy byCondCandidateClass = any.byCondition(or(checkedClassLike(), selectedClassLike()));
        String classCandidatePred = extractPredicate(base, byCondCandidateClass.getXpath());

        // candidate via native binary-ish attrs (presence)
        String rawNativeCandidate =
                "(" +
                        "@checked or @selected" +
                        ")";

        // candidate via ARIA/data-* presence (presence means the pattern exists)
        String rawStateCandidate =
                "(" +
                        "@aria-checked or @aria-selected or @aria-pressed or " +
                        "@data-checked or @data-selected or @data-state" +
                        ")";

        // NEW: candidate via common <input type=...> (case-insensitive)
        String rawTypeCandidate =
                "(" +
                        "self::input[" +
                        "translate(@type,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='checkbox' or " +
                        "translate(@type,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='radio'" +
                        "]" +
                        ")";

        // NEW: candidate via common ARIA roles for binary/toggle widgets (case-insensitive)
        // includes a few common patterns beyond checkbox/radio/switch.
        String rawRoleCandidate =
                "(" +
                        "@role and (" +
                        "translate(@role,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='checkbox' or " +
                        "translate(@role,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='radio' or " +
                        "translate(@role,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='switch' or " +
                        "translate(@role,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='menuitemcheckbox' or " +
                        "translate(@role,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='menuitemradio' or " +
                        "translate(@role,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='option'" +
                        ")" +
                        ")";

        // candidate via explicit boolean-ish value
        String rawValueCandidate =
                "(" +
                        "@value='true' or @value='false'" +
                        ")";

        return "("
                + classCandidatePred
                + " or " + rawNativeCandidate
                + " or " + rawStateCandidate
                + " or " + rawTypeCandidate
                + " or " + rawRoleCandidate
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
