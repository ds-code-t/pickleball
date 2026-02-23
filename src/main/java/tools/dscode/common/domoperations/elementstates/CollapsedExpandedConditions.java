package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.List;

public final class CollapsedExpandedConditions {

    private CollapsedExpandedConditions() {
        // utility class
    }

    /**
     * Broadest "this thing participates in expand/collapse" predicate.
     *
     * Intentionally matches BOTH:
     *  - toggles (buttons/headers) that carry state via aria-expanded / classes
     *  - sections/panels/details that carry state via data-state / open / classes
     *
     * Current state is irrelevant here; this is just "collapsible-capable".
     */
    public static final String COLLAPSIBLE_PREDICATE =
            "(" +
                    // native <details open>
                    "@open or " +

                    // common ARIA/data hooks (present whether true/false)
                    "@aria-expanded or " +
                    "@data-expanded or " +
                    "@data-collapsed or " +
                    "@data-state or " +

                    // broad class-token heuristics (includes both expanded-ish & collapsed-ish)
                    "contains(concat(' ', normalize-space(@class), ' '), ' expanded ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' is-expanded ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' open ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' is-open ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' show ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' collapsed ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' is-collapsed ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' closed ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' is-closed ')" +
                    ")";

    /**
     * State predicate that can be used:
     *  - alone (on any element), OR
     *  - alongside COLLAPSIBLE_PREDICATE for "only collapsible things"
     *
     * Rules:
     *  - TRUE  => treat as EXPANDED
     *  - FALSE => treat as COLLAPSED
     *  - Defaults to EXPANDED when there are no explicit collapsed indicators.
     *
     * In other words: Expanded unless explicitly marked collapsed,
     * but explicit expanded markers win if both are present.
     */
    public static final String EXPANDED_STATE_PREDICATE =
            "(" +
                    // explicit expanded signals
                    "@open or " +
                    "@aria-expanded='true' or " +
                    "@data-expanded='true' or " +
                    "@data-state='open' or @data-state='opened' or @data-state='expanded' or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' expanded ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' is-expanded ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' open ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' is-open ') or " +
                    "contains(concat(' ', normalize-space(@class), ' '), ' show ') or " +

                    // default-to-expanded when NOT explicitly collapsed
                    "not(" + EXPLICIT_COLLAPSED_PREDICATE() + ")" +
                    ")";

    // Kept as a method so EXPANDED_STATE_PREDICATE can inline it without duplicating text manually.
    // This method is private, but the collapsed logic is still fully expressed via EXPANDED_STATE_PREDICATE and not(...).
    private static String EXPLICIT_COLLAPSED_PREDICATE() {
        return "(" +
                "@aria-expanded='false' or " +
                "@data-collapsed='true' or " +
                "@data-expanded='false' or " +
                "@data-state='closed' or @data-state='collapsed' or " +
                "contains(concat(' ', normalize-space(@class), ' '), ' collapsed ') or " +
                "contains(concat(' ', normalize-space(@class), ' '), ' is-collapsed ') or " +
                "contains(concat(' ', normalize-space(@class), ' '), ' closed ') or " +
                "contains(concat(' ', normalize-space(@class), ' '), ' is-closed ')" +
                ")";
    }

    /**
     * Finds collapsible-capable elements that are treated as expanded
     * (expanded by explicit markers, or by default if not explicitly collapsed).
     */
    public static XPathy expandedElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + COLLAPSIBLE_PREDICATE + " and " + EXPANDED_STATE_PREDICATE + "]");
    }

    /**
     * Finds collapsible-capable elements that are treated as collapsed.
     *
     * Per your requirement: uses BOTH predicates and wraps the *second* predicate in not(...).
     */
    public static XPathy collapsedElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + COLLAPSIBLE_PREDICATE + " and not(" + EXPANDED_STATE_PREDICATE + ")]");
    }

    /**
     * WebElement utility that applies ONLY the "state" predicate using self::*.
     *
     * This means it will return TRUE (expanded) by default if the element has no explicit
     * collapsed indicators — even if the element isn't actually a collapsible widget.
     * (That’s what you asked for.)
     */
    public static boolean isExpandedState(WebElement element) {
        return matchesSelf(element, EXPANDED_STATE_PREDICATE);
    }

    public static boolean isCollapsedState(WebElement element) {
        return !matchesSelf(element, EXPANDED_STATE_PREDICATE);
    }

    private static boolean matchesSelf(WebElement element, String predicate) {
        if (element == null) {
            return false;
        }
        List<WebElement> matches = element.findElements(By.xpath("self::*[" + predicate + "]"));
        return !matches.isEmpty();
    }
}