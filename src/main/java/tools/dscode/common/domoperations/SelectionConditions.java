//package tools.dscode.common.domoperations;
//
//
//import com.xpathy.Attribute;
//import com.xpathy.Condition;
//
//import static com.xpathy.Attribute.*;
//
///**
// * Reusable predicates for "selected" / "unselected" style states.
// *
// * Broad definition:
// *  - native checked/selected attributes
// *  - aria-* true/false values
// *  - data-* boolean-ish flags
// *  - value="on"/"off"/"true"/"false"/"1"/"0"
// *  - common class-based patterns like "active", "selected", "inactive", "off"
// */
//public final class SelectionConditions {
//
//    // ARIA attributes not predefined in com.xpathy.Attribute
//    private static final Attribute ARIA_CHECKED  = Attribute.custom("aria-checked");
//    private static final Attribute ARIA_SELECTED = Attribute.custom("aria-selected");
//    private static final Attribute ARIA_PRESSED  = Attribute.custom("aria-pressed");
//    private static final Attribute ARIA_EXPANDED = Attribute.custom("aria-expanded");
//    private static final Attribute ARIA_CURRENT  = Attribute.custom("aria-current");
//
//    // Common data-* attributes for toggled state
//    private static final Attribute DATA_SELECTED = Attribute.custom("data-selected");
//    private static final Attribute DATA_STATE    = Attribute.custom("data-state");
//    private static final Attribute DATA_CHECKED  = Attribute.custom("data-checked");
//    private static final Attribute DATA_ACTIVE   = Attribute.custom("data-active");
//
//    private SelectionConditions() {
//        // utility class
//    }
//
//    /**
//     * Broad definition of "selected"/"on"/"active".
//     *
//     * Use like:
//     *   XPathy selectedItems = any.byCondition(SelectionConditions.selected());
//     */
//    public static Condition selected() {
//        return Condition.or(
//
//                // 1) Native HTML boolean attributes
//                Condition.attribute(checked).haveIt(),
//                Condition.attribute(selected).haveIt(),
//                // some libs store "true" as the value
//                Condition.attribute(checked).equals("true"),
//                Condition.attribute(selected).equals("true"),
//
//                // 2) ARIA "true" states
//                Condition.attribute(ARIA_CHECKED).equals("true"),
//                Condition.attribute(ARIA_SELECTED).equals("true"),
//                Condition.attribute(ARIA_PRESSED).equals("true"),
//                Condition.attribute(ARIA_EXPANDED).equals("true"),
//                Condition.attribute(ARIA_CURRENT).equals("true"),
//
//                // 3) data-* boolean-ish flags
//                Condition.attribute(DATA_SELECTED).equals("true"),
//                Condition.attribute(DATA_SELECTED).equals("1"),
//                Condition.attribute(DATA_SELECTED).equals("on"),
//
//                Condition.attribute(DATA_STATE).equals("on"),
//                Condition.attribute(DATA_STATE).equals("checked"),
//                Condition.attribute(DATA_STATE).equals("active"),
//
//                Condition.attribute(DATA_CHECKED).equals("true"),
//                Condition.attribute(DATA_CHECKED).equals("1"),
//
//                Condition.attribute(DATA_ACTIVE).equals("true"),
//                Condition.attribute(DATA_ACTIVE).equals("1"),
//
//                // 4) value attribute used as boolean
//                Condition.attribute(value).equals("true"),
//                Condition.attribute(value).equals("on"),
//                Condition.attribute(value).equals("yes"),
//                Condition.attribute(value).equals("1"),
//
//                // 5) Class-based conventions
//                Condition.attribute(class_).contains("active"),
//                Condition.attribute(class_).contains("selected"),
//                Condition.attribute(class_).contains("checked"),
//                Condition.attribute(class_).contains("is-active"),
//                Condition.attribute(class_).contains("is-selected"),
//                Condition.attribute(class_).contains("toggle-on")
//        );
//    }
//
//    /**
//     * Broad definition of "unselected"/"off"/"inactive".
//     *
//     * Use like:
//     *   XPathy unselectedItems = any.byCondition(SelectionConditions.unselected());
//     */
//    public static Condition unselected() {
//        return Condition.or(
//
//                // 1) ARIA "false" states
//                Condition.attribute(ARIA_CHECKED).equals("false"),
//                Condition.attribute(ARIA_SELECTED).equals("false"),
//                Condition.attribute(ARIA_PRESSED).equals("false"),
//                Condition.attribute(ARIA_EXPANDED).equals("false"),
//                Condition.attribute(ARIA_CURRENT).equals("false"),
//
//                // 2) data-* false/off flags
//                Condition.attribute(DATA_SELECTED).equals("false"),
//                Condition.attribute(DATA_SELECTED).equals("0"),
//                Condition.attribute(DATA_SELECTED).equals("off"),
//
//                Condition.attribute(DATA_STATE).equals("off"),
//                Condition.attribute(DATA_STATE).equals("inactive"),
//
//                Condition.attribute(DATA_CHECKED).equals("false"),
//                Condition.attribute(DATA_CHECKED).equals("0"),
//
//                Condition.attribute(DATA_ACTIVE).equals("false"),
//                Condition.attribute(DATA_ACTIVE).equals("0"),
//
//                // 3) value attribute used as boolean false
//                Condition.attribute(value).equals("false"),
//                Condition.attribute(value).equals("off"),
//                Condition.attribute(value).equals("no"),
//                Condition.attribute(value).equals("0"),
//
//                // 4) Class-based conventions
//                Condition.attribute(class_).contains("inactive"),
//                Condition.attribute(class_).contains("is-inactive"),
//                Condition.attribute(class_).contains("is-disabled"),
//                Condition.attribute(class_).contains("disabled"),
//                Condition.attribute(class_).contains("off"),
//                Condition.attribute(class_).contains("toggle-off")
//        );
//    }
//}
