package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.List;

public final class RequiredInputConditions {

    private RequiredInputConditions() {
        // utility class
    }

    /**
     * IMPORTANT: This predicate is intentionally a "descendant-or-self::*[ ... ]" test
     * because the original implementation checked the element *or any descendant*
     * for required-ness markers.
     *
     * Keeping this EXACT string (and how it's embedded) preserves behavior.
     */
    private static final String REQUIRED_DESC_OR_SELF_PRED =
            "descendant-or-self::*[" +
                    "(" +
                    "contains(concat(',', normalize-space(@validationtype), ','), ',required,') or " +
                    "(@required and not(@required='false' or @required='0')) or " +
                    "(@aria-required and not(@aria-required='false' or @aria-required='0')) or " +
                    "(@data-required and not(@data-required='false' or @data-required='0')) or " +
                    "(@data-state='required')" +
                    ")" +
                    "]";

    /**
     * Returns the XPath for selecting elements that are considered "required"
     * based on REQUIRED_DESC_OR_SELF_PRED.
     */
    public static XPathy requiredElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + REQUIRED_DESC_OR_SELF_PRED + "]");
    }

    /**
     * Returns the XPath for selecting elements that are considered "not required"
     * based on REQUIRED_DESC_OR_SELF_PRED.
     */
    public static XPathy notRequiredElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[not(" + REQUIRED_DESC_OR_SELF_PRED + ")]");
    }


    public static boolean isElementRequired(WebElement element) {
        if (element == null) {
            return false;
        }
        List<WebElement> matches = element.findElements(By.xpath("self::*[" + REQUIRED_DESC_OR_SELF_PRED + "]"));
        return !matches.isEmpty();
    }

}