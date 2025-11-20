package tools.dscode.common.domoperations;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;

import java.util.List;
import java.util.Objects;

import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedText;

/**
 * Concise DOM check helpers for XPathy + Selenium.
 * All methods:
 *   - take a ChromiumDriver and an XPathy
 *   - return a CheckResult (no exceptions)
 *   - describe what was found
 */
public final class DomChecks {


    private DomChecks() {}

    /** Result of any check. */
    public record CheckResult(boolean result, String description) {}

    /* -------------------------------------------------------------
     *  Generic normalization + equality
     * ------------------------------------------------------------- */

    /**
     * Normalize any object to a canonical string:
     *  - null -> ""
     *  - toString()
     *  - collapse all whitespace (tabs/newlines/indentation) to a single space
     *  - collapse consecutive spaces to one
     *  - trim ends
     */
    public static String normalize(Object value) {
        if (value == null) return "";
        return value.toString()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Simple normalized equality between two arbitrary objects.
     * Uses {@link #normalize(Object)} on both.
     */
    public static CheckResult equalsNormalized(Object a, Object b) {
        String sa = normalize(a);
        String sb = normalize(b);
        boolean ok = sa.equals(sb);
        return new CheckResult(
                ok,
                "equalsNormalized: [" + sa + "] vs [" + sb + "] -> " + ok
        );
    }

    /* -------------------------------------------------------------
     *  Internal helpers
     * ------------------------------------------------------------- */

    private static List<WebElement> find(ChromiumDriver driver, XPathy xpathy) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(xpathy, "xpathy must not be null");

        By by = xpathy.getLocator();  // from XPathy API :contentReference[oaicite:0]{index=0}
        List<WebElement> elements = driver.findElements(by);
        return elements == null ? List.of() : elements;
    }

    private static String summarizeFirst(List<WebElement> elements) {
        if (elements.isEmpty()) return "<none>";
        WebElement el = elements.getFirst();
        try {
            String tag = el.getTagName();
            String id = el.getAttribute("id");
            String cls = el.getAttribute("class");
            String text = el.getText();
            if (text != null && text.length() > 40) {
                text = text.substring(0, 37) + "...";
            }
            return "<" + tag +
                    (id != null && !id.isBlank() ? " id='" + id + "'" : "") +
                    (cls != null && !cls.isBlank() ? " class='" + cls + "'" : "") +
                    (text != null && !text.isBlank() ? " text='" + text + "'" : "") +
                    ">";
        } catch (Exception e) {
            return "<element>";
        }
    }

    /* -------------------------------------------------------------
     *  XPathy-based WebElement checks
     * ------------------------------------------------------------- */

    /** Are there any matches for this XPathy? */
    public static CheckResult hasAny(ChromiumDriver driver, XPathy xpathy) {
        List<WebElement> els = find(driver, xpathy);
        boolean ok = !els.isEmpty();
        String desc = ok
                ? "Found " + els.size() + " elements. First: " + summarizeFirst(els)
                : "No elements matched.";
        return new CheckResult(ok, desc);
    }

    /** Does this XPathy match exactly {@code expectedCount} elements? */
    public static CheckResult matchCount(ChromiumDriver driver, XPathy xpathy, int expectedCount) {
        List<WebElement> els = find(driver, xpathy);
        int actual = els.size();
        boolean ok = (actual == expectedCount);
        String desc = "Match count expected=" + expectedCount +
                ", actual=" + actual +
                ". First: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }

    /** Is the first matched element selected (checkbox, radio, option, etc.)? */
    public static CheckResult firstIsSelected(ChromiumDriver driver, XPathy xpathy) {
        List<WebElement> els = find(driver, xpathy);
        if (els.isEmpty()) {
            return new CheckResult(false, "No elements found for isSelected check.");
        }
        WebElement el = els.getFirst();
        boolean selected = el.isSelected();
        String desc = "First element selected=" + selected +
                ". Element: " + summarizeFirst(els);
        return new CheckResult(selected, desc);
    }

    /** Does the first matched element have a non-empty 'value' attribute? */
    public static CheckResult firstHasValue(ChromiumDriver driver, XPathy xpathy) {
        List<WebElement> els = find(driver, xpathy);
        if (els.isEmpty()) {
            return new CheckResult(false, "No elements found for value check.");
        }
        WebElement el = els.getFirst();
        String value = normalize(el.getAttribute("value"));
        boolean ok = !value.isEmpty();
        String desc = "First element value=[" + value + "], hasValue=" + ok +
                ". Element: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }

    /** Does the first matched element have non-empty visible text content? */
    public static CheckResult firstHasText(ChromiumDriver driver, XPathy xpathy) {
        List<WebElement> els = find(driver, xpathy);
        if (els.isEmpty()) {
            return new CheckResult(false, "No elements found for text check.");
        }
        WebElement el = els.getFirst();
        String text = normalize(el.getText());
        boolean ok = !text.isEmpty();
        String desc = "First element text=[" + text + "], hasText=" + ok +
                ". Element: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }

    /** Does the first matched element have this attribute at all (value may be empty)? */
    public static CheckResult firstHasAttribute(ChromiumDriver driver,
                                                XPathy xpathy,
                                                String attrName) {
        List<WebElement> els = find(driver, xpathy);
        if (els.isEmpty()) {
            return new CheckResult(false,
                    "No elements found for attribute @" + attrName + " existence check.");
        }
        WebElement el = els.getFirst();
        String raw = el.getAttribute(attrName);
        boolean ok = raw != null;
        String desc = "First element @" + attrName + " exists=" + ok +
                ", raw=[" + raw + "]. Element: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }

    /** Is the first element's text (normalized) equal to the expected value (normalized)? */
    public static CheckResult firstTextEquals(ChromiumDriver driver,
                                              XPathy xpathy,
                                              Object expected) {
        List<WebElement> els = find(driver, xpathy);
        if (els.isEmpty()) {
            return new CheckResult(false, "No elements found for text equality check.");
        }
        WebElement el = els.getFirst();
        String actual = normalize(el.getText());
        String exp = normalize(expected);
        boolean ok = actual.equals(exp);
        String desc = "First element text equals (normalized)? actual=[" + actual +
                "], expected=[" + exp + "], result=" + ok +
                ". Element: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }

    /** Is the first element's attribute value (normalized) equal to the expected value (normalized)? */
    public static CheckResult firstAttributeEquals(ChromiumDriver driver,
                                                   XPathy xpathy,
                                                   String attrName,
                                                   Object expected) {
        List<WebElement> els = find(driver, xpathy);
        if (els.isEmpty()) {
            return new CheckResult(false,
                    "No elements found for attribute @" + attrName + " equality check.");
        }
        WebElement el = els.getFirst();
        String actual = normalize(el.getAttribute(attrName));
        String exp = normalize(expected);
        boolean ok = actual.equals(exp);
        String desc = "First element @" + attrName + " equals (normalized)? actual=[" + actual +
                "], expected=[" + exp + "], result=" + ok +
                ". Element: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }
}
