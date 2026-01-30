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
import static tools.dscode.common.util.debug.DebugUtils.substrings;

public final class XPathyAssembly {

    private XPathyAssembly() {
        // utility class
    }


    // In whatever util class you like, e.g. XPathyScopes or similar

    /**
     * Normalize an XPath to be used as a relative step after an axis
     * (descendant::, preceding::, following::, etc.).
     * <p>
     * Examples:
     * //button[@id='x'] -> button[@id='x']
     * .//span          -> span
     * /div             -> div
     * a[@href]         -> a[@href]
     */
    public static String asRelativeStep(XPathy xp) {
        String s = xp.getXpath().trim();

        if (s.startsWith(".//")) return s.substring(3);
        if (s.startsWith("./")) return s.substring(2);
        if (s.startsWith("//")) return s.substring(2);
        if (s.startsWith("/")) return s.substring(1);
        return s;
    }

    /**
     * Build the predicate used for "between" relationships:
     * [preceding::beforeStep and following::afterStep]
     * <p>
     * Example:
     * before = //h2
     * after  = //footer
     * -> "preceding::h2 and following::footer"
     */
    public static String betweenPredicate(XPathy before, XPathy after) {
        String beforeStep = asRelativeStep(before);
        String afterStep = asRelativeStep(after);
        return "preceding::" + beforeStep + " and following::" + afterStep;
    }

// -------------------------------------------------------------------------
// Scope builders: inside / before / after / in-between
// -------------------------------------------------------------------------

    /**
     * Scope: all nodes that are inside (descendants of) any node matched by {@code anchor}.
     * <p>
     * Example:
     * anchor = //div[contains(@class,'classA')]
     * result = //*[ancestor::div[contains(@class,'classA')]]
     */
    public static XPathy insideOf(XPathy anchor) {
        String step = asRelativeStep(anchor);

        if (step.startsWith("select[")) {
            String expr = "//*[ancestor::*[position()<=5][self::" + step + "]]";
            return new XPathy(expr);
        }

        String expr = "//*[ancestor::" + step + "]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that appear before any node matched by {@code anchor} in document order.
     * <p>
     * Example:
     * anchor = //div[contains(@class,'classA')]
     * result = //*[following::div[contains(@class,'classA')]]
     */
    public static XPathy beforeOf(XPathy anchor) {
        String step = asRelativeStep(anchor);
        String expr = "//*[following::" + step + "]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that appear after any node matched by {@code anchor} in document order.
     * <p>
     * Example:
     * anchor = //div[contains(@class,'classA')]
     * result = //*[preceding::div[contains(@class,'classA')]]
     */
    public static XPathy afterOf(XPathy anchor) {
        String step = asRelativeStep(anchor);
        String expr = "//*[preceding::" + step + "]";
        return new XPathy(expr);
    }

    /**
     * Scope: all nodes that are between any {@code before} node and any {@code after} node
     * in document order.
     * <p>
     * Example:
     * before = //h2
     * after  = //footer
     * result = //*[preceding::h2 and following::footer]
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


//
//    public static XPathy combineAnd2(List<XPathy> list) {
//        if (list.size() == 1) return list.getFirst();
//        return XPathy.from(combine(list, "and").getXpath().replace("[]", ""));
//    }

    /**
     * Combine all XPathy with logical AND: //*[ self::... and self::... and ... ]
     */
    public static XPathy combineAnd(List<XPathy> list) {
        if (list.size() == 1) return list.getFirst();
        List<XPathy> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));
        return combineAndFinal(sorted);
    }


    public static XPathy combineAndFinal(List<XPathy> list) {
        XPathy first = list.getFirst();
        if (list.size() == 1) return first;
        List<String> xpathStrings = list.stream().map(xPathy -> xPathy.getXpath().trim()).toList();
        if (xpathStrings.stream().allMatch(s -> s.startsWith("//*"))) {
            return XPathy.from("//*" + xpathStrings.stream().map(string -> string.substring(3)).collect(Collectors.joining("")));
        }
        if (!xpathStrings.getFirst().startsWith("//*") && xpathStrings.getFirst().startsWith("//")) {
            return XPathy.from(xpathStrings.getFirst() + combineAndFinal(list.subList(1, list.size())).getXpath().replace("[]", "").replaceFirst("^//\\*", ""));
        } else {
            return XPathy.from(combine(list, "and").getXpath().replace("[]", ""));
        }
    }


    /**
     * Combine all XPathy with logical OR: //*[ self::... or self::... or ... ]
     */
    public static XPathy combineOr(List<XPathy> list) {
        if (list.size() == 1) return list.getFirst();
        List<XPathy> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));
        return combine(sorted, "or");
    }


    private static XPathy combine(List<XPathy> list, String joiner) {
        if (list.size() == 1) return list.getFirst();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null) {
                throw new IllegalStateException("Null XPathy at index " + i);
            }
            if (list.get(i).getXpath() == null) {
                throw new IllegalStateException("Null xpath string at index " + i);
            }
        }


        String combinedExpr = list.stream()
                .map(XPathy::getXpath)
                .map(XPathyAssembly::toSelfStep)
                .collect(Collectors.joining(" " + joiner + " "));

        String fullXpath = "//*[" + combinedExpr + "]";
        return XPathy.from(fullXpath);
    }


    /**
     * Normalize an XPath into a "self::..." step usable inside a predicate.
     * <p>
     * Examples:
     * //span[@class='A']                   -> self::span[@class='A']
     * //*[ancestor::div[@class='content']] -> self::*[ancestor::div[@class='content']]
     * (//*)[contains(@class,'btn')]        -> self::*[contains(@class,'btn')]
     */
    public static String toSelfStep(String xpath) {
        String s = xpath.trim();

        // 1. Preserve leading parentheses, operate on the core after them
        int coreStart = 0;
        while (coreStart < s.length() && s.charAt(coreStart) == '(') {
            coreStart++;
        }
        String prefix = s.substring(0, coreStart); // "(((..."
        String core = s.substring(coreStart);    // everything after '('s

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


        String step = stripped;

        if (step.matches("^[A-Za-z-]+::.*")) {
            return prefix + step;
        }

        step = "self::" + step;

        return prefix + step;
    }

    public static final char match1 = '\u206A';
    public static final char match2 = '\u207A';
    public static final char match3 = '\u208A';
    public static final String match1s = String.valueOf(match1);
    public static final String match2s = String.valueOf(match2);
    public static final String match3s = String.valueOf(match3);

    /**
     * Heuristic specificity scoring. Lower score = "more specific".
     */
    public static int xpathSpecificityScore(String xpath) {
        printDebug("\n##SpecificityScore xpath: " + xpath);
        xpath = xpath.trim();

        int score = countChar(xpath, '*') * 10;
        printDebug("##Xscore score1: " + score);
        if (xpath.startsWith("//*[preceding") || xpath.startsWith("//*[following") || xpath.startsWith("//*[ancestor") || xpath.startsWith("//*[descendant")) {
            score += 10_000_000;
            String firstTag = xpath.split("::")[1].trim();
            if (!firstTag.startsWith("*")) {
                score -= 1_000_000;
                if (!firstTag.startsWith("div")) {
                    score -= 1_000_000;
                }
            }
            if (firstTag.matches("^\\*|[a-zA-Z]+\\[position")) {
                score -= 1_000_000;
            }
        }

        printDebug("##Xscore score2: " + score);

        if (!xpath.startsWith("//*")) {
            score -= 10_000_000;
        }

        printDebug("##Xscore score3: " + score);
        String noSpace = xpath
                .replaceAll("\\b(?:or|body)\\b|\\|", match1s)
                .replaceAll("(?:\\b::text|count|not|descendant|ancestor|preceding|following\\b)|//", match2s)
                .replaceAll("(?:\\btranslate|contains|starts-with|position\\b)", match3s)
                .replaceAll("\\s+|\\(|\\)", "");


        score += countChar(noSpace, match1) * 1000;
        score += countChar(noSpace, match2) * 500;
        score += countChar(noSpace, match3) * 100;

        printDebug("##Xscore score4: " + score);

        if (noSpace.contains("//*[not")) {
            score += 50_000;
        }
        printDebug("##Xscore score5: " + score);

        if (noSpace.contains("'screen-reader-text'")) {
            score += 10_000_000;
        }

        printDebug("##Xscore final: " + score);
        printDebug("##SpecificityScore score final: " + score);

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
