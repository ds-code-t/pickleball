package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import tools.dscode.common.treeparsing.PhraseExecution.ElementMatch;

import java.util.List;

public final class XPathyUtils {

    private XPathyUtils() {
        // utility class
    }

    /**
     * Combines all ElementMatch.xPathy into a single union XPath:
     * xpath1 | xpath2 | xpath3 ...
     */
    public static XPathy orAllElements(List<ElementMatch> elements) {
        if (elements == null || elements.isEmpty()) {
            throw new IllegalArgumentException("Need at least one ElementMatch");
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (ElementMatch el : elements) {
            if (el == null || el.xPathy == null) {
                continue;
            }

            String xpath = el.xPathy.getXpath();
            if (xpath == null || xpath.isBlank()) {
                continue;
            }

            if (!first) {
                sb.append(" | ");
            } else {
                first = false;
            }
            sb.append(xpath);
        }

        if (first) {
            // never appended anything
            throw new IllegalArgumentException("All ElementMatch instances had null/blank XPathy");
        }

        return new XPathy(sb.toString());
    }

    /**
     * Combines all ElementMatch.xPathy into a boolean AND expression:
     * (xpath1) and (xpath2) and (xpath3) ...
     * <p>
     * This is a boolean expression, not a standalone location path.
     * Use it where a boolean XPath expression is valid (e.g. inside predicates).
     */
    public static XPathy andAllElements(List<ElementMatch> elements) {
        if (elements == null || elements.isEmpty()) {
            throw new IllegalArgumentException("Need at least one ElementMatch");
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (ElementMatch el : elements) {
            if (el == null || el.xPathy == null) {
                continue;
            }

            String xpath = el.xPathy.getXpath();
            if (xpath == null || xpath.isBlank()) {
                continue;
            }

            if (!first) {
                sb.append(" and ");
            } else {
                first = false;
            }

            // Wrap each expression so any internal "and/or" stays grouped correctly
            sb.append('(').append(xpath).append(')');
        }

        if (first) {
            // never appended anything
            throw new IllegalArgumentException("All ElementMatch instances had null/blank XPathy");
        }

        return new XPathy(sb.toString());
    }

    private static XPathy wrapWithPredicate(XPathy xp, String predicate) {
        if (xp == null) {
            throw new IllegalArgumentException("XPathy must not be null");
        }
        String raw = xp.getXpath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("XPathy xpath must not be null/blank");
        }
        // (<expr>)[<predicate>]
        return new XPathy("(" + raw + ")[" + predicate + "]");
    }

    // ---- Position helpers ---------------------------------------------------


    /**
     * Every nth match (1-based, multiples of step):
     * step=3 → 3rd, 6th, 9th, ...
     * Expression: (<xpath>)[position() mod step = 0]
     */
    public static XPathy everyNth(XPathy xp, int step) {
        if (step < 1) {
            throw new IllegalArgumentException("step must be >= 1");
        }
        return wrapWithPredicate(xp, "position() mod " + step + " = 0");
    }

    /**
     * Every nth match starting at a given 1-based offset:
     * start=1, step=3 → 1st, 4th, 7th, ...
     * start=2, step=3 → 2nd, 5th, 8th, ...
     * Expression: (<xpath>)[(position() - start) mod step = 0 and position() >= start]
     */
    public static XPathy everyNthFrom(XPathy xp, int start, int step) {
        if (start < 1) {
            throw new IllegalArgumentException("start must be >= 1");
        }
        if (step < 1) {
            throw new IllegalArgumentException("step must be >= 1");
        }
        String predicate =
                "(position() - " + start + ") mod " + step + " = 0 and position() >= " + start;
        return wrapWithPredicate(xp, predicate);
    }


    private static String requireNonBlankXpath(XPathy xp, String label) {
        if (xp == null) {
            throw new IllegalArgumentException(label + " XPathy must not be null");
        }
        String xpath = xp.getXpath();
        if (xpath == null || xpath.isBlank()) {
            throw new IllegalArgumentException(label + " XPath must not be null/blank");
        }
        return xpath.trim();
    }

    /**
     * Normalize an XPath to be used as a relative step after an axis
     * (descendant::, preceding::, following::, etc.).
     * <p>
     * Examples:
     * "//button[@id='x']" -> "button[@id='x']"
     * ".//span"          -> "span"
     * "/div"             -> "div"
     * "a[@href]"         -> "a[@href]"
     */
    private static String asRelativeStep(XPathy xp, String label) {
        String s = requireNonBlankXpath(xp, label);

        if (s.startsWith(".//")) return s.substring(3);
        else if (s.startsWith("./")) return s.substring(2);
        else if (s.startsWith("//")) return s.substring(2);
        else if (s.startsWith("/")) return s.substring(1);
        return s;
    }

    /**
     * Build the predicate used for "between" relationships:
     * [preceding::beforeStep and following::afterStep]
     */
    private static String betweenPredicate(XPathy before, XPathy after) {
        String beforeStep = asRelativeStep(before, "before");
        String afterStep = asRelativeStep(after, "after");
        return "preceding::" + beforeStep + " and following::" + afterStep;
    }

    // -------------------------------------------------------------------------
    // Scope builders: inside / before / after / in-between
    // -------------------------------------------------------------------------

    /**
     * Scope: all nodes that are inside (descendants of) any node matched by {@code anchor}.
     * <p>
     * Example:
     * anchor  = //div[contains(@class,'classA')]
     * result  = //* [ancestor::(//div[contains(@class,'classA')])]
     */
    public static XPathy insideOf(XPathy anchor) {
        String anchorXpath = requireNonBlankXpath(anchor, "anchor");
        String expr = "//*[ancestor::(" + anchorXpath + ")]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that appear before any node matched by {@code anchor} in document order.
     * <p>
     * Example:
     * anchor  = //div[contains(@class,'classA')]
     * result  = //* [following::(//div[contains(@class,'classA')])]
     */
    public static XPathy beforeOf(XPathy anchor) {
        String anchorXpath = requireNonBlankXpath(anchor, "anchor");
        String expr = "//*[following::(" + anchorXpath + ")]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that appear after any node matched by {@code anchor} in document order.
     * <p>
     * Example:
     * anchor  = //div[contains(@class,'classA')]
     * result  = //* [preceding::(//div[contains(@class,'classA')])]
     */
    public static XPathy afterOf(XPathy anchor) {
        String anchorXpath = requireNonBlankXpath(anchor, "anchor");
        String expr = "//*[preceding::(" + anchorXpath + ")]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that are between any {@code before} node and any {@code after} node
     * in document order.
     * <p>
     * Example:
     * before = //h2
     * after  = //footer
     * result = //* [preceding::h2 and following::footer]
     */
    public static XPathy inBetweenOf(XPathy before, XPathy after) {
        String predicate = betweenPredicate(before, after);
        String expr = "//*[" + predicate + "]";
        return new XPathy(expr);
    }

    // -------------------------------------------------------------------------
    // General-purpose refiner
    // -------------------------------------------------------------------------

    /**
     * General-purpose refinement:
     * <p>
     * result0 = base
     * result1 = (result0)/descendant::filter1Step
     * result2 = (result1)/descendant::filter2Step
     * ...
     * <p>
     * Each {@code filter} is treated as a relative step under the current result.
     * <p>
     * Examples:
     * <p>
     * // All <button> inside any div.classA
     * XPathy divs    = new XPathy("//div[contains(@class,'classA')]");
     * XPathy buttons = new XPathy("button");
     * XPathy buttonsInside =
     * XPathyUtils.refine(XPathyUtils.insideOf(divs), buttons);
     * <p>
     * // All <strong> inside <span> that live between <h2> and <footer>
     * XPathy h2     = new XPathy("//h2");
     * XPathy footer = new XPathy("//footer");
     * XPathy span   = new XPathy("span");
     * XPathy strong = new XPathy("strong");
     * XPathy strongBetween =
     * XPathyUtils.refine(XPathyUtils.inBetweenOf(h2, footer), span, strong);
     */
    public static XPathy refine(XPathy base, XPathy... filters) {
        String current = requireNonBlankXpath(base, "base");

        if (filters == null || filters.length == 0) {
            // nothing to refine; return an equivalent XPathy
            return new XPathy(current);
        }

        for (int i = 0; i < filters.length; i++) {
            XPathy f = filters[i];
            String step = asRelativeStep(f, "filter[" + i + "]");
            current = "(" + current + ")/descendant::" + step;
        }

        return new XPathy(current);
    }


    /**
     * Returns an XPathy that represents a *condition template* for "deep" text
     * matching, suitable for use with byHaving(...) / byHaving().descendant(...).
     *
     * Semantics:
     *  - string(.) concatenates all descendant text of the candidate element
     *  - translate(...) maps tabs/newlines/NBSP to regular spaces
     *  - normalize-space(...) collapses runs of spaces and trims ends
     *
     * IMPORTANT: The returned XPath has the form:
     *     //*[ <condition on string(.)> ]
     * This is intentional: the Having transformer strips off the leading
     * '//*[' and trailing ']' and uses only the condition part.
     */
    public static XPathy deepNormalizedText(String rawText) {
        if (rawText == null) {
            throw new IllegalArgumentException("rawText must not be null");
        }

        // Normalize the expected value the same way we normalize DOM text:
        // Collapse all whitespace to a single space, then trim.
        String expected = rawText.replaceAll("\\s+", " ").strip();

        // This is the *condition* we care about:
        //   normalize-space(translate(string(.), ' \t\r\n\u00A0', '     '))
        //     = '<expected>'
        String condition =
                "normalize-space(" +
                        "translate(string(.), ' \t\r\n\u00A0', '     ')" +
                        ") = " + toXPathLiteral(expected);

        // Wrap it in the "//*[...]" template that Having expects.
        // Having code will later do substring(4, len-1) to extract just `condition`.
        String xpath = "//*[" + condition + "]";

        return XPathy.from(xpath);
    }

    /**
     * Safely turns a Java string into an XPath string literal.
     * Uses '...' where possible, and falls back to concat(...) if the string
     * contains both single and double quotes.
     */
    private static String toXPathLiteral(String s) {
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }

        // Contains both ' and " → use concat('foo', "'", 'bar')
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1); // keep empties
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", \"'\", ");
            }
            sb.append("'").append(parts[i]).append("'");
        }
        sb.append(")");
        return sb.toString();
    }


}
