package tools.dscode.common.domoperations.elementstates;

import com.xpathy.Condition;
import com.xpathy.XPathy;

import static com.xpathy.Attribute.*;
import static com.xpathy.Condition.*;

/**
 * Reusable XPathy conditions related to "binary on/off" state.
 *
 * API GUARANTEES:
 * - Public methods and signatures are unchanged.
 * - All XPath-returning methods return FULL XPaths (e.g. //*[...]).
 *
 * SEMANTICS:
 * - ON is detected on the element itself OR any descendant.
 * - ON signals are based ONLY on:
 *     @checked / @selected (presence = ON unless explicitly false)
 *     ARIA attributes
 *     data-* state attributes
 *     known class tokens
 * - NO @value fallback.
 * - If no signals exist anywhere, state defaults to OFF.
 */
public final class BinaryStateConditions {

    private BinaryStateConditions() {
        // utility class
    }

    /* -------------------------------------------------------------------------
     * Public Condition API (unchanged, shallow/self-only)
     * ------------------------------------------------------------------------- */

    public static Condition on() {
        return or(
                checkedLike(),
                selectedLike(),
                checkedClassLike(),
                selectedClassLike()
        );
    }

    public static Condition off() {
        return not(on());
    }

    public static Condition checked() {
        return or(
                checkedLike(),
                checkedClassLike()
        );
    }

    public static Condition notChecked() {
        return not(checked());
    }

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
     * Public XPathy API (FULL XPaths, deep evaluation)
     * ------------------------------------------------------------------------- */

    /** Any element that is ON (self OR descendants). */
    public static XPathy onElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + buildOnPredicateDeep() + "]");
    }

    /** Any element that is OFF (default when no signals exist). */
    public static XPathy offElement() {
        String base = new XPathy().getXpath();
        return new XPathy(base + "[not(" + buildOnPredicateDeep() + ")]");
    }

    /** Checked (self OR descendants). */
    public static XPathy checkedElement() {
        String base = new XPathy().getXpath();
        return new XPathy(base + "[" + buildCheckedPredicateDeep() + "]");
    }

    /** Selected (self OR descendants). */
    public static XPathy selectedElement() {
        String base = new XPathy().getXpath();
        return new XPathy(base + "[" + buildSelectedPredicateDeep() + "]");
    }

    /* -------------------------------------------------------------------------
     * Condition helpers (unchanged)
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
     * Deep XPath predicate builders
     * ------------------------------------------------------------------------- */

    private static String buildOnPredicateDeep() {
        return "(" + primaryOnSelf() + " or " + primaryOnDesc() + ")";
    }

    private static String buildCheckedPredicateDeep() {
        return "(" + checkedOnSelf() + " or " + checkedOnDesc() + ")";
    }

    private static String buildSelectedPredicateDeep() {
        return "(" + selectedOnSelf() + " or " + selectedOnDesc() + ")";
    }

    /* ---------------- Primary ON signals ---------------- */

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

    /* ---------------- Checked ---------------- */

    private static String checkedOnSelf() {
        return "(" +
                "(@checked and not(" + isFalse("@checked") + ")) or " +
                "(" + lc("@aria-checked") + "='true' or " + lc("@aria-checked") + "='mixed') or " +
                "(" + lc("@data-checked") + "='true') or " +
                "(" + lc("@data-state") + "='checked' or " + lc("@data-state") + "='on') or " +
                checkedClassExpr("@class") +
                ")";
    }

    private static String checkedOnDesc() {
        return ".//*[" + checkedOnAnyNode() + "]";
    }

    private static String checkedOnAnyNode() {
        return checkedOnSelf();
    }

    /* ---------------- Selected ---------------- */

    private static String selectedOnSelf() {
        return "(" +
                "(@selected and not(" + isFalse("@selected") + ")) or " +
                "(" + lc("@aria-selected") + "='true') or " +
                "(" + lc("@data-selected") + "='true') or " +
                "(" + lc("@data-state") + "='selected') or " +
                selectedClassExpr("@class") +
                ")";
    }

    private static String selectedOnDesc() {
        return ".//*[" + selectedOnAnyNode() + "]";
    }

    private static String selectedOnAnyNode() {
        return selectedOnSelf();
    }

    /* ---------------- ARIA / DATA ---------------- */

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
                "(" + lc("@data-state") + "='checked' or " +
                lc("@data-state") + "='selected' or " +
                lc("@data-state") + "='on')" +
                ")";
    }

    private static String dataOnAnyNode() {
        return dataOnSelf();
    }

    /* ---------------- Class tokens ---------------- */

    private static String classOnSelf() {
        return "(" +
                checkedClassExpr("@class") + " or " +
                selectedClassExpr("@class") +
                ")";
    }

    private static String classOnAnyNode() {
        return classOnSelf();
    }

    private static String checkedClassExpr(String classExpr) {
        String spaced = "concat(' ', normalize-space(" + classExpr + "), ' ')";
        return "(" +
                "contains(" + spaced + ", ' checked ') or " +
                "contains(" + spaced + ", ' is-checked ') or " +
                "contains(" + spaced + ", ' mat-checkbox-checked ') or " +
                "contains(" + spaced + ", ' mat-radio-checked ') or " +
                "contains(" + spaced + ", ' p-highlight ')" +
                ")";
    }

    private static String selectedClassExpr(String classExpr) {
        String spaced = "concat(' ', normalize-space(" + classExpr + "), ' ')";
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

    private static String lc(String attrExpr) {
        return "translate(" + attrExpr +
                ",'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
    }

    private static String isFalse(String attrExpr) {
        String a = lc(attrExpr);
        return a + "='false' or " + a + "='0'";
    }

    /** Kept for compatibility */
    public static String extractPredicate(String base, String xpathWithPredicate) {
        if (!xpathWithPredicate.startsWith(base + "[") || !xpathWithPredicate.endsWith("]")) {
            throw new IllegalArgumentException("Unexpected XPath format: " + xpathWithPredicate);
        }
        return xpathWithPredicate.substring(base.length() + 1, xpathWithPredicate.length() - 1);
    }
}
