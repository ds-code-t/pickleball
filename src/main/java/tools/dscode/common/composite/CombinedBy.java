package tools.dscode.common.composite;

import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;

import java.util.List;

/**
 * Dictionary layer. A category resolution that has a combined clause and an
 * XPath. Selenium, and {@code pkb_composite=off}, still find by the XPath.
 * The engine runs {@link #combined()} first and uses {@link #xpath()} only
 * when that matches nothing.
 */
public final class CombinedBy extends By {
    private final String combined;
    private final String xpath;

    private CombinedBy(String combined, String xpath) {
        this.combined = combined == null ? "" : combined;
        this.xpath = xpath == null ? "" : xpath;
    }

    /** {@link By#xpath(String)} when {@code combined} is blank, so the Selenium path is unchanged. */
    public static By of(String combined, String xpath) {
        if (combined == null || combined.isBlank()) return By.xpath(xpath);
        return new CombinedBy(combined, xpath);
    }

    public String combined() {
        return combined;
    }

    public String xpath() {
        return xpath;
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        return By.xpath(xpath).findElements(context);
    }

    @Override
    public String toString() {
        return "By.xpath: " + xpath;
    }
}
