package tools.dscode.common.domoperations;

import org.intellij.lang.annotations.RegExp;
import org.openqa.selenium.WebElement;

import java.util.List;

import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.normalizeText;

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

    public static CheckResult equalsNormalized(Object a, Object b) {
        String sa = normalizeText(String.valueOf(a));
        String sb = normalizeText(String.valueOf(b));
        boolean ok = sa.equals(sb);
        return new CheckResult(
                ok,
                "equalsNormalized: [" + sa + "] vs [" + sb + "] -> " + ok
        );
    }

    public static CheckResult startsWithNormalized(Object a, Object b) {
        String sa = normalizeText(String.valueOf(a));
        String sb = normalizeText(String.valueOf(b));
        boolean ok = sa.startsWith(sb);
        return new CheckResult(
                ok,
                "startsWithNormalized: [" + sa + "] vs [" + sb + "] -> " + ok
        );
    }

    public static CheckResult endsWithNormalized(Object a, Object b) {
        String sa = normalizeText(String.valueOf(a));
        String sb = normalizeText(String.valueOf(b));
        boolean ok = sa.startsWith(sb);
        return new CheckResult(
                ok,
                "endsWithNormalized: [" + sa + "] vs [" + sb + "] -> " + ok
        );
    }


    public static CheckResult containsNormalized(Object a, Object b) {
        String sa = normalizeText(String.valueOf(a));
        String sb = normalizeText(String.valueOf(b));
        boolean ok = sa.startsWith(sb);
        return new CheckResult(
                ok,
                "containsNormalized: [" + sa + "] vs [" + sb + "] -> " + ok
        );
    }

    public static CheckResult matchesNormalized(Object a, @RegExp String regex) {
        String sa = normalizeText(String.valueOf(a));
        boolean ok = sa.matches(regex);
        return new CheckResult(
                ok,
                "matchesNormalized: [" + sa + "] vs [" + regex + "] -> " + ok
        );
    }


    /* -------------------------------------------------------------
     *  Internal helpers
     * ------------------------------------------------------------- */

    private static List<WebElement> find(List<WebElement> elements) {
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
    public static CheckResult hasAny(List<WebElement> els) {
        boolean ok = !els.isEmpty();
        String desc = ok
                ? "Found " + els.size() + " elements. First: " + summarizeFirst(els)
                : "No elements matched.";
        return new CheckResult(ok, desc);
    }

    /** Does this XPathy match exactly {@code expectedCount} elements? */
    public static CheckResult matchCount(List<WebElement> els, int expectedCount) {
        int actual = els.size();
        boolean ok = (actual == expectedCount);
        String desc = "Match count expected=" + expectedCount +
                ", actual=" + actual +
                ". First: " + summarizeFirst(els);
        return new CheckResult(ok, desc);
    }

    /** Is the first matched element selected (checkbox, radio, option, etc.)? */
    public static CheckResult firstIsSelected(List<WebElement> els) {
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
    public static CheckResult firstHasValue(List<WebElement> els) {
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
    public static CheckResult firstHasText(List<WebElement> els) {
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
    public static CheckResult firstHasAttribute(List<WebElement> els,
                                                String attrName) {
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
    public static CheckResult firstTextEquals(List<WebElement> els,
                                              Object expected) {
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
    public static CheckResult firstAttributeEquals(List<WebElement> els,
                                                   String attrName,
                                                   Object expected) {
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
