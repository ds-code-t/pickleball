package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "binary on/off" state.
 *
 * IMPORTANT:
 * - Public API is unchanged (same methods/signatures/return types).
 * - The XPath-producing methods now evaluate state on the element OR ANY DESCENDANT.
 * - Non-value state signals are preferred (@checked/@selected/ARIA/data + class tokens).
 * - @value='true' is only used as a fallback when NO non-value signals exist anywhere in subtree.
 * - If none of the above exist, state defaults to OFF (so offElement() will match).
 */
public final class BinaryStateConditions {

    private BinaryStateConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (pure XPathy Condition) — unchanged
     * NOTE: These are shallow/self-only, because Condition cannot express descendant checks.
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

    public static Condition checkedAttributeOnly() {
        return checkedLike();
    }

    public static Condition selectedAttributeOnly() {
        return selectedLike();
    }

    /* -------------------------------------------------------------------------
     * Public XPathy API — same methods, but predicates are now deep (self OR descendants)
     * ------------------------------------------------------------------------- */

    /**
     * Any element that is considered "on" by checking the element itself OR any descendant.
     *
     * Priority:
     *  1) Non-value signals (checked/selected/ARIA/data + class tokens)
     *  2) Fallback to @value='true' ONLY if no non-value signals exist anywhere in subtree
     *  3) Otherwise ON is false
     */
    public static XPathy onElement() {
        String base = new XPathy().getXpath(); // "//*"
        String onDeepPred = buildOnPredicateDeep();
        return new XPathy(base + "[" + onDeepPred + "]");
    }

    /**
     * Any element that is considered "off".
     *
     * OFF = not(ON), and ON defaults to false when no signals exist,
     * so OFF defaults to true (matches "assume false state when nothing exists").
     */
    public static XPathy offElement() {
        String base = new XPathy().getXpath(); // "//*"
        String onDeepPred = buildOnPredicateDeep();
        return new XPathy(base + "[not(" + onDeepPred + ")]");
    }

    /**
     * "Checked" locator, deep (self OR descendants).
     * Uses checked-like non-value signals only (no @value fallback).
     */
    public static XPathy checkedElement() {
        String base = new XPathy().getXpath(); // "//*"
        String pred = buildCheckedPredicateDeep();
        return new XPathy(base + "[" + pred + "]");
    }

    /**
     * "Selected" locator, deep (self OR descendants).
     * Uses selected-like non-value signals only (no @value fallback).
     */
    public static XPathy selectedElement() {
        String base = new XPathy().getXpath(); // "//*"
        String pred = buildSelectedPredicateDeep();
        return new XPathy(base + "[" + pred + "]");
    }

    /* -------------------------------------------------------------------------
     * Internals — Conditions (unchanged)
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

    /* -------------------------------------------------------------------------
     * Internals — Deep predicate builders (self OR descendants)
     * ------------------------------------------------------------------------- */

    /**
     * Deep ON predicate with required precedence:
     * - First: any non-value "ON" signals (self or descendant)
     * - Else: if NO non-value state signals exist anywhere, then @value='true' (self or descendant) means ON
     * - Else: OFF
     */
    private static String buildOnPredicateDeep() {
        String primaryOn = "(" + primaryOnSelf() + " or " + primaryOnDesc() + ")";
        String primaryPresent = "(" + primaryPresentSelf() + " or " + primaryPresentDesc() + ")";
        String valueTrue = "(" + valueTrueSelf() + " or " + valueTrueDesc() + ")";

        // value is a fallback only if no primary signals exist anywhere
        String valueFallbackOn = "(not(" + primaryPresent + ") and " + valueTrue + ")";

        return "(" + primaryOn + " or " + valueFallbackOn + ")";
    }

    private static String buildCheckedPredicateDeep() {
        return "(" + checkedOnSelf() + " or " + checkedOnDesc() + ")";
    }

    private static String buildSelectedPredicateDeep() {
        return "(" + selectedOnSelf() + " or " + selectedOnDesc() + ")";
    }

    /* ---------------- Primary ON signals (non-value) ---------------- */

    private static String primaryOnSelf() {
        return "(" +
                checkedOnSelf() + " or " +
                selectedOnSelf() + " or " +
                ariaOnSelf() + " or " +
                dataOnSelf() + " or " +
                classOnSelf() +
                ")";
    }

    private static String primaryOnDesc() {
        return ".//*[" +
                checkedOnAnyNode() + " or " +
                selectedOnAnyNode() + " or " +
                ariaOnAnyNode() + " or " +
                dataOnAnyNode() + " or " +
                classOnAnyNode() +
                "]";
    }

    /* ---------------- Primary PRESENT signals (non-value) ---------------- */

    private static String primaryPresentSelf() {
        // Presence of any non-value state mechanism on self (attributes or class tokens)
        return "(" +
                "@checked or @selected or @aria-checked or @aria-selected or @aria-pressed or " +
                "@data-checked or @data-selected or @data-state or " +
                classTokenPresentSelf() +
                ")";
    }

    private static String primaryPresentDesc() {
        // Any descendant has any non-value state mechanism
        return ".//*[" +
                "@checked or @selected or @aria-checked or @aria-selected or @aria-pressed or " +
                "@data-checked or @data-selected or @data-state or " +
                classTokenPresentAnyNode() +
                "]";
    }

    /* ---------------- Value fallback ---------------- */

    private static String valueTrueSelf() {
        return "(" + lc("@value") + "='true')";
    }

    private static String valueTrueDesc() {
        return ".//*[" + lc("@value") + "='true']";
    }

    /* ---------------- Checked-specific ---------------- */

    private static String checkedOnSelf() {
        return "(" +
                "(@checked and not(" + isFalseLiteral("@checked") + ")) or " +
                "(" + lc("@aria-checked") + "='true' or " + lc("@aria-checked") + "='mixed') or " +
                "(" + lc("@data-checked") + "='true') or " +
                "(" + lc("@data-state") + "='checked' or " + lc("@data-state") + "='on') or " +
                checkedClassTokenExpr("@class") +
                ")";
    }

    private static String checkedOnDesc() {
        return ".//*[" + checkedOnAnyNode() + "]";
    }

    private static String checkedOnAnyNode() {
        // Same as checkedOnSelf but written without @class helper needing context
        return "(" +
                "(@checked and not(" + isFalseLiteral("@checked") + ")) or " +
                "(" + lc("@aria-checked") + "='true' or " + lc("@aria-checked") + "='mixed') or " +
                "(" + lc("@data-checked") + "='true') or " +
                "(" + lc("@data-state") + "='checked' or " + lc("@data-state") + "='on') or " +
                checkedClassTokenExpr("@class") +
                ")";
    }

    /* ---------------- Selected-specific ---------------- */

    private static String selectedOnSelf() {
        return "(" +
                "(@selected and not(" + isFalseLiteral("@selected") + ")) or " +
                "(" + lc("@aria-selected") + "='true') or " +
                "(" + lc("@data-selected") + "='true') or " +
                "(" + lc("@data-state") + "='selected')" + " or " +
                selectedClassTokenExpr("@class") +
                ")";
    }

    private static String selectedOnDesc() {
        return ".//*[" + selectedOnAnyNode() + "]";
    }

    private static String selectedOnAnyNode() {
        return "(" +
                "(@selected and not(" + isFalseLiteral("@selected") + ")) or " +
                "(" + lc("@aria-selected") + "='true') or " +
                "(" + lc("@data-selected") + "='true') or " +
                "(" + lc("@data-state") + "='selected')" + " or " +
                selectedClassTokenExpr("@class") +
                ")";
    }

    /* ---------------- ARIA/Data ON ---------------- */

    private static String ariaOnSelf() {
        return "(" +
                "(" + lc("@aria-checked") + "='true' or " + lc("@aria-checked") + "='mixed') or " +
                "(" + lc("@aria-selected") + "='true') or " +
                "(" + lc("@aria-pressed") + "='true')" +
                ")";
    }

    private static String ariaOnAnyNode() {
        return ariaOnSelf();
    }

    private static String dataOnSelf() {
        return "(" +
                "(" + lc("@data-checked") + "='true') or " +
                "(" + lc("@data-selected") + "='true') or " +
                "(" + lc("@data-state") + "='checked' or " + lc("@data-state") + "='selected' or " + lc("@data-state") + "='on')" +
                ")";
    }

    private static String dataOnAnyNode() {
        return dataOnSelf();
    }

    /* ---------------- Class token ON / PRESENT ---------------- */

    private static String classOnSelf() {
        // "ON" via class tokens — same tokens you already used, but deep-capable
        return "(" +
                checkedClassTokenExpr("@class") + " or " +
                selectedClassTokenExpr("@class") +
                ")";
    }

    private static String classOnAnyNode() {
        return classOnSelf();
    }

    private static String classTokenPresentSelf() {
        // "present" means any known token exists (checked-ish or selected-ish)
        return "(" +
                checkedClassTokenExpr("@class") + " or " +
                selectedClassTokenExpr("@class") +
                ")";
    }

    private static String classTokenPresentAnyNode() {
        return classTokenPresentSelf();
    }

    private static String checkedClassTokenExpr(String classAttrExpr) {
        // Safer “token-ish” matching using normalize-space + contains.
        // (Still intentionally permissive like your original contains().)
        String cls = "normalize-space(" + classAttrExpr + ")";
        String spaced = "concat(' ', " + cls + ", ' ')";

        return "(" +
                "contains(" + spaced + ", ' checked ') or " +
                "contains(" + spaced + ", ' is-checked ') or " +
                "contains(" + spaced + ", ' mat-checkbox-checked ') or " +
                "contains(" + spaced + ", ' mat-radio-checked ') or " +
                "contains(" + spaced + ", ' p-highlight ')" +
                ")";
    }

    private static String selectedClassTokenExpr(String classAttrExpr) {
        String cls = "normalize-space(" + classAttrExpr + ")";
        String spaced = "concat(' ', " + cls + ", ' ')";

        return "(" +
                "contains(" + spaced + ", ' selected ') or " +
                "contains(" + spaced + ", ' is-selected ') or " +
                "contains(" + spaced + ", ' active ') or " +
                "contains(" + spaced + ", ' p-highlight ')" +
                ")";
    }

    /* -------------------------------------------------------------------------
     * Helpers
     * ------------------------------------------------------------------------- */

    // Case-folding for XPath 1.0 via translate()
    private static String lc(String attrExpr) {
        return "translate(" + attrExpr + ",'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
    }

    // Treat explicit false-ish values as OFF when attribute is present:
    // - checked="false", checked="0" (common)
    // NOTE: Presence with empty value is treated as ON (as requested).
    private static String isFalseLiteral(String attrExpr) {
        String a = lc(attrExpr);
        return a + "='false' or " + a + "='0'";
    }

    /**
     * Helper: given base "//*" and "//*[(...)]" return "(...)"
     * Kept for compatibility (public API).
     */
    public static String extractPredicate(String base, String xpathWithPredicate) {
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }
}
