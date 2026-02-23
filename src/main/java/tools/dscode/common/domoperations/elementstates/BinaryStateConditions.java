package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.List;

public final class BinaryStateConditions {

    private BinaryStateConditions() {
        // utility class
    }

    /**
     * Shared descendant-or-self predicate.
     * Preserves original semantics exactly (self OR any descendant can drive ON).
     *
     * Any self-or-descendant with a "truthy" binary attribute.
     * Presence counts as true unless explicitly "false" or "0".
     *
     * NOTE: This predicate is attribute-based only. Selenium's isSelected() uses DOM properties.
     * We keep this predicate unchanged to avoid breaking XPath behavior.
     */
    private static final String ON_DESC_OR_SELF_PRED =
            "descendant-or-self::*[" +
                    "(" +
                    "(@checked and not(@checked='false' or @checked='0')) or " +
                    "(@selected and not(@selected='false' or @selected='0')) or " +
                    "(@aria-checked and not(@aria-checked='false' or @aria-checked='0')) or " +
                    "(@aria-selected and not(@aria-selected='false' or @aria-selected='0')) or " +
                    "(@aria-pressed and not(@aria-pressed='false' or @aria-pressed='0')) or " +
                    "(@data-checked and not(@data-checked='false' or @data-checked='0')) or " +
                    "(@data-selected and not(@data-selected='false' or @data-selected='0'))" +
                    ")" +
                    "]";

    public static XPathy onElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + ON_DESC_OR_SELF_PRED + "]");
    }

    public static XPathy offElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[not(" + ON_DESC_OR_SELF_PRED + ")]");
    }


    public static boolean isCheckedSelectedOrOn(WebElement element) {
        if (element == null) {
            return false;
        }

        // 1) Native Selenium-defined selection semantics
        //    - <input type="checkbox|radio">
        //    - <option>
        if (isSeleniumSelectable(element)) {
            return element.isSelected();
        }

        // 2) Broader attribute/ARIA/data-attribute semantics (original behavior)
        List<WebElement> matches =
                element.findElements(By.xpath("self::*[" + ON_DESC_OR_SELF_PRED + "]"));

        return !matches.isEmpty();
    }

    /**
     * OFF is the logical negation of ON (matches offElement()).
     */
    public static boolean isOff(WebElement element) {
        return !isCheckedSelectedOrOn(element);
    }

    private static boolean isSeleniumSelectable(WebElement element) {
        // Be defensive: some drivers can throw for getTagName/getAttribute on stale elements.
        // Let those exceptions bubble the same way isSelected() would.
        String tag = element.getTagName();
        if (tag == null) {
            return false;
        }

        if ("option".equalsIgnoreCase(tag)) {
            return true;
        }

        if ("input".equalsIgnoreCase(tag)) {
            String type = element.getAttribute("type");
            return "checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type);
        }

        return false;
    }
}