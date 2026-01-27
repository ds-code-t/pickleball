package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.Tag;
import com.xpathy.XPathy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static tools.dscode.common.util.debug.DebugUtils.printDebug;

public final class XPathyAssembly {

    private XPathyAssembly() {
        // utility class
    }


    // In whatever util class you like, e.g. XPathyScopes or similar

    /**
     * Normalize an XPath to be used as a relative step after an axis
     * (descendant::, preceding::, following::, etc.).
     *
     * Examples:
     *   //button[@id='x'] -> button[@id='x']
     *   .//span          -> span
     *   /div             -> div
     *   a[@href]         -> a[@href]
     */
    public static String asRelativeStep(XPathy xp) {
        String s = xp.getXpath().trim();

        if (s.startsWith(".//")) return s.substring(3);
        if (s.startsWith("./"))  return s.substring(2);
        if (s.startsWith("//"))  return s.substring(2);
        if (s.startsWith("/"))   return s.substring(1);
        return s;
    }

    /**
     * Build the predicate used for "between" relationships:
     * [preceding::beforeStep and following::afterStep]
     *
     * Example:
     *   before = //h2
     *   after  = //footer
     *   -> "preceding::h2 and following::footer"
     */
    public static String betweenPredicate(XPathy before, XPathy after) {
        String beforeStep = asRelativeStep(before);
        String afterStep  = asRelativeStep(after);
        return "preceding::" + beforeStep + " and following::" + afterStep;
    }

// -------------------------------------------------------------------------
// Scope builders: inside / before / after / in-between
// -------------------------------------------------------------------------

    /**
     * Scope: all nodes that are inside (descendants of) any node matched by {@code anchor}.
     *
     * Example:
     *   anchor = //div[contains(@class,'classA')]
     *   result = //*[ancestor::div[contains(@class,'classA')]]
     */
    public static XPathy insideOf(XPathy anchor) {
        String step = asRelativeStep(anchor);
        String expr = "//*[ancestor::" + step + "]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that appear before any node matched by {@code anchor} in document order.
     *
     * Example:
     *   anchor = //div[contains(@class,'classA')]
     *   result = //*[following::div[contains(@class,'classA')]]
     */
    public static XPathy beforeOf(XPathy anchor) {
        String step = asRelativeStep(anchor);
        String expr = "//*[following::" + step + "]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that appear after any node matched by {@code anchor} in document order.
     *
     * Example:
     *   anchor = //div[contains(@class,'classA')]
     *   result = //*[preceding::div[contains(@class,'classA')]]
     */
    public static XPathy afterOf(XPathy anchor) {
        String step = asRelativeStep(anchor);
        String expr = "//*[preceding::" + step + "]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that are between any {@code before} node and any {@code after} node
     * in document order.
     *
     * Example:
     *   before = //h2
     *   after  = //footer
     *   result = //*[preceding::h2 and following::footer]
     */
    public static XPathy inBetweenOf(XPathy before, XPathy after) {
        String predicate = betweenPredicate(before, after);
        String expr = "//*[" + predicate + "]";
        return new XPathy(expr);
    }

    public static XPathy combineAnd(XPathy... items) {
        return combineAnd(List.of(items));
    }

    public static XPathy combineOr(Tag... items) {
        return combineOr(Arrays.stream(items).map(t -> XPathy.from(t.toString())).toList());
    }

    public static XPathy combineOr(XPathy... items) {
        return combineOr(List.of(items));
    }

    public static String combineAnd(String... items) {
        return combineAnd(Arrays.stream(items).map(XPathy::from).toList()).getXpath();
    }

    public static String combineOr(String... items) {
        return combineOr(Arrays.stream(items).map(XPathy::from).toList()).getXpath();
    }


    /** Combine all XPathy with logical AND: //*[ self::... and self::... and ... ] */
    public static XPathy combineAnd(List<XPathy> list) {

        return XPathy.from(combine(list, "and").getXpath().replace("[]",""));
    }

    /** Combine all XPathy with logical OR: //*[ self::... or self::... or ... ] */
    public static XPathy combineOr(List<XPathy> list) {

        return combine(list, "or");
    }


    private static XPathy combine(List<XPathy> list, String joiner) {
        // Debug sanity checks
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null) {
                throw new IllegalStateException("Null XPathy at index " + i);
            }
            if (list.get(i).getXpath() == null) {
                throw new IllegalStateException("Null xpath string at index " + i);
            }
        }

        List<XPathy> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));

        String combinedExpr = sorted.stream()
                .map(XPathy::getXpath)
                .map(XPathyAssembly::toSelfStep)
                .collect(Collectors.joining(" " + joiner + " "));

        String fullXpath = "//*[" + combinedExpr + "]";
        return XPathy.from(fullXpath);
    }


    /**
     * Normalize an XPath into a "self::..." step usable inside a predicate.
     *
     * Examples:
     *   //span[@class='A']                   -> self::span[@class='A']
     *   //*[ancestor::div[@class='content']] -> self::*[ancestor::div[@class='content']]
     *   (//*)[contains(@class,'btn')]        -> self::*[contains(@class,'btn')]
     */
    public static String toSelfStep(String xpath) {
        String s = xpath.trim();

        // 1. Preserve leading parentheses, operate on the core after them
        int coreStart = 0;
        while (coreStart < s.length() && s.charAt(coreStart) == '(') {
            coreStart++;
        }
        String prefix = s.substring(0, coreStart); // "(((..."
        String core   = s.substring(coreStart);    // everything after '('s

        // 2. If core already starts with an axis, keep whole string as-is
        if (core.matches("^[A-Za-z-]+::.*")) {
            return s;
        }

        // 3. Strip leading //, /, .//, ./ from the core
        String stripped = core
                .replaceFirst("^\\.//", "")  // .//...
                .replaceFirst("^\\./", "")   // ./...
                .replaceFirst("^//", "")     // //...
                .replaceFirst("^/", "");     // /...

        // 4. If nothing was stripped (no leading /), we still want self::...
        //    Otherwise, we now have something like:
        //      "span[@class='A']"
        //      "*[ancestor::div]"
        //      "input[@type='text'][1]"
        String step = stripped;

        // 5. If step already starts with an axis, just restore prefix + step
        if (step.matches("^[A-Za-z-]+::.*")) {
            return prefix + step;
        }

        // 6. Turn it into self::nodeTest...[predicates...]
        //    Handle wildcard vs. named node test
        if (step.startsWith("*")) {
            // "*[...]" -> "self::*[...]"
            step = "self::*" + step.substring(1);
        } else {
            // "span[@class='A']" -> "self::span[@class='A']"
            step = "self::" + step;
        }

        // 7. Put leading parentheses back
        return prefix + step;
    }

    static final Pattern xPathScorePattern = Pattern.compile("\\bself|\\*|display|height|width|visbility|collapse|opacity|hidden|style|not|contains|translate|or|descendant|ancestor|preceding|following|sibling|parent|child\\b");

    /** Heuristic specificity scoring. Lower score = "more specific". */
    public static int xpathSpecificityScore(String xpath) {
        String s = xpath.trim();
        String noSpace = s.replaceAll("\\s+", "");
        printDebug("##xpathSpecificityScore xpath: " + s);


        int score = Math.toIntExact(1000 + s.length() + (xPathScorePattern.matcher(s).results().count() * 20));

        if (noSpace.contains("//*[(not(")) {
            score += 20000;
        }

        // Penalize wildcards / generic patterns
        if (s.contains("//*")) {
            score += 200;
        }

        if (s.startsWith("//*[preceding") || s.startsWith("//*[following")) {
            score += 10000;
        }

        if (s.startsWith("//*[ancestor")) {
            score += 2000;
        }

        if (noSpace.contains("descendant::text()")) {
            score += 4000;
        }



        score += s.replaceAll("[^*]","").length() * 20;

//        if (!s.contains("\"") && !s.contains("'")) {
//            score += 600;
//        }

        if (s.matches(".*\\b\\*\\b.*")) {
            score += 50;
        }
        if (s.contains("self::*")) {
            score += 30;
        }

        // Reward predicates
        int predicateCount = countChar(s, '[');
        score -= predicateCount * 5;

        // Reward strong attributes
        if (s.contains("@id")) {
            score -= 40;
        }

        if (s.contains("@name")) {
            score -= 15;
        }
        if (s.contains("@class")) {
            score -= 10;
        }

        // Reward custom elements
        if (s.matches(".*//[a-zA-Z0-9]+-[a-zA-Z0-9_-]+.*")) {
            score -= 20;
        }

        // Prefer explicit tag at root vs wildcard
        if (s.matches("^\\s*//[a-zA-Z].*")) {
            score -= 10;
        } else if (s.matches("^\\s*//\\*.*")) {
            score += 10;
        }

        printDebug("##xpathSpecificityScore score: " + score);

        return Math.max(score, 0);
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }


    public static String prettyPrintXPath(XPathy xpathy) {
        return prettyPrintXPath(xpathy.getXpath());
    }

    public static String prettyPrintXPath(String xpath) {
        if (xpath == null) return null;

        // Split before "self::*" only when NOT preceded by '-'
        String[] parts = xpath.split("(?<!-)(?=self::\\*)");

        StringBuilder sb = new StringBuilder();
        int indent = 0;

        for (String part : parts) {
            String trimmed = part.trim();

            // If part begins with a closing paren, reduce indent
            if (trimmed.startsWith(")")) {
                indent = Math.max(0, indent - 1);
            }

            sb.append("    ".repeat(indent))
                    .append(trimmed)
                    .append("\n");

            // Increase indent if the part begins with or contains a new self::*
            if (trimmed.contains("self::*") && !trimmed.startsWith("-self::*")) {
                indent++;
            }
        }

        return sb.toString();
    }



}
