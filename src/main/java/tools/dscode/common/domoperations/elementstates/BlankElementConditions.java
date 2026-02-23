package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.List;

/**
 * XPath predicates for selecting elements that are "blank" vs "non-blank"
 * from a UI text/value perspective.
 *
 * Definition used here:
 *  - "Blank" means: (self + descendants) contain no non-whitespace visible text nodes
 *    AND (self + descendants) have no @value OR only @value that is null/empty/blank.
 *  - "Non-blank" is the inverse: there exists (self + descendants) either
 *    a non-whitespace text node OR a @value with non-blank content.
 *
 * Notes:
 *  - XPath cannot see JS properties like element.value unless it is reflected into @value.
 *  - We also treat "null" (case-insensitive) in @value as blank.
 */
public final class BlankElementConditions {

    private BlankElementConditions() {
        // utility class
    }

    // Reusable sub-predicates (kept identical to preserve behavior)
    private static final String HAS_ANY_NON_BLANK_TEXT =
            "descendant-or-self::node()[self::text() and normalize-space(.) != '']";

    private static final String HAS_ANY_NON_BLANK_VALUE_ATTR =
            "descendant-or-self::*[@" +
                    "value and " +
                    "normalize-space(@value) != '' and " +
                    "translate(normalize-space(@value), " +
                    "  'ABCDEFGHIJKLMNOPQRSTUVWXYZ', " +
                    "  'abcdefghijklmnopqrstuvwxyz'" +
                    ") != 'null'" +
                    "]";

    // Derived predicates
    private static final String BLANK_PRED =
            "not(" + HAS_ANY_NON_BLANK_TEXT + ") and " +
                    "not(" + HAS_ANY_NON_BLANK_VALUE_ATTR + ")";

    private static final String NON_BLANK_PRED =
            "(" + HAS_ANY_NON_BLANK_TEXT + ") or (" + HAS_ANY_NON_BLANK_VALUE_ATTR + ")";

    public static XPathy blankElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + BLANK_PRED + "]");
    }

    public static XPathy nonBlankElement() {
        String base = new XPathy().getXpath(); // "//*"
        return new XPathy(base + "[" + NON_BLANK_PRED + "]");
    }

    public static boolean isBlank(WebElement element) {
        if (element == null) {
            return false;
        }
        List<WebElement> matches =
                element.findElements(By.xpath("self::*[" + BLANK_PRED + "]"));
        return !matches.isEmpty();
    }

}