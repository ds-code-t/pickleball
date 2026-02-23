package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.List;

public final class EnabledDisabledConditions {

    private EnabledDisabledConditions() {
        // utility class
    }

    /**
     * Shared descendant-or-self predicate.
     * This preserves the original semantics exactly.
     */
    private static final String DISABLED_DESC_OR_SELF_PRED =
            "descendant-or-self::*[" +
                    "(" +
                    "(@disabled and not(@disabled='false' or @disabled='0')) or " +
                    "(@aria-disabled and not(@aria-disabled='false' or @aria-disabled='0')) or " +
                    "(@data-disabled and not(@data-disabled='false' or @data-disabled='0')) or " +
                    "(@data-state='disabled')" +
                    ")" +
                    "]";

    public static XPathy disabledElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + DISABLED_DESC_OR_SELF_PRED + "]");
    }

    public static XPathy enabledElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[not(" + DISABLED_DESC_OR_SELF_PRED + ")]");
    }

    /**
     * Evaluates disabled predicate against an already retrieved WebElement.
     */
    public static boolean isDisabled(WebElement element) {
        if (element == null) {
            return false;
        }

        List<WebElement> matches =
                element.findElements(By.xpath("self::*[" + DISABLED_DESC_OR_SELF_PRED + "]"));

        return !matches.isEmpty();
    }

    /**
     * Evaluates enabled state using logical negation of disabled.
     */
    public static boolean isEnabled(WebElement element) {
        return !isDisabled(element);
    }
}