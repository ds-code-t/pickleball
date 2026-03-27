package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.Tag;
import com.xpathy.XPathy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static tools.dscode.common.util.debug.DebugUtils.printDebug;
import static tools.dscode.common.util.debug.DebugUtils.substrings;

public final class XPathyAssembly {

    private XPathyAssembly() {
        // utility class
    }

    private static final AtomicInteger selfCounter = new AtomicInteger(1);

    private static final Pattern SELF_WRAP =
            Pattern.compile(
                    "^[^a-zA-Z]*self::(?:[A-Za-z_][A-Za-z0-9_.:-]*|\\*)\\[(\\d+)>-(\\d+)\\].*\\[\\1>-\\2\\][^a-zA-Z]*$",
                    Pattern.DOTALL
            );

    private static final Pattern ID_PAIR_PATTERN =
            Pattern.compile("\\[\\d+>-\\d+\\]");

    private static final Pattern AXIS_PATTERN =
            Pattern.compile("^[A-Za-z-]+::.*");

    // Boundary is either a full self-step with tag OR a bare tag
    private static final Pattern BOUNDARY =
            Pattern.compile("self::(?:[A-Za-z_][A-Za-z0-9_.:-]*|\\*)\\[\\d+>-\\d+\\]|\\[\\d+>-\\d+\\]");

    // Extract b from any tag [a>-b]
    private static final Pattern PSEUDO_TAG =
            Pattern.compile("\\[(\\d+)>-(\\d+)\\]");

    public static final char match1 = '\u206A';
    public static final char match2 = '\u207A';
    public static final char match3 = '\u208A';
    public static final String match1s = String.valueOf(match1);
    public static final String match2s = String.valueOf(match2);
    public static final String match3s = String.valueOf(match3);

    public static Boolean stripPseudoTags = null;
    public static Boolean normalizeWhiteSpace = null;

    /**
     * Normalize an XPath to be used as a relative step after an axis
     * (descendant::, preceding::, following::, etc.).
     * <p>
     * Examples:
     * //button[@id='x'] -> button[@id='x']
     * .//span          -> span
     * /div             -> div
     * a[@href]         -> a[@href]
     *
     * For parenthesized/node-set expressions like (//div)[3], this returns the
     * expression unchanged. Those must not be forced into axis::step form.
     */
    public static String asRelativeStep(XPathy xp) {
        String s = xp.getXpath().trim();

        if (isNodeSetExpression(s)) return s;
        if (s.startsWith(".//")) return s.substring(3);
        if (s.startsWith("./")) return s.substring(2);
        if (s.startsWith("//")) return s.substring(2);
        if (s.startsWith("/")) return s.substring(1);
        return s;
    }

    /**
     * Build the predicate used for "between" relationships.
     *
     * For normal step-like anchors:
     *   preceding::beforeStep and following::afterStep
     *
     * For parenthesized/node-set expressions:
     *   current node is in afterOf(before) AND current node is in beforeOf(after)
     */
    public static String betweenPredicate(XPathy before, XPathy after) {
        String beforeRaw = before.getXpath().trim();
        String afterRaw = after.getXpath().trim();

        if (!isNodeSetExpression(beforeRaw) && !isNodeSetExpression(afterRaw)) {
            String beforeStep = asRelativeStep(before);
            String afterStep = asRelativeStep(after);
            return "preceding::" + beforeStep + " and following::" + afterStep;
        }

        String afterBeforeSet = afterOf(before).getXpath();
        String beforeAfterSet = beforeOf(after).getXpath();

        return toMembershipPredicate(afterBeforeSet) + " and " + toMembershipPredicate(beforeAfterSet);
    }

    // -------------------------------------------------------------------------
    // Scope builders: inside / before / after / in-between
    // -------------------------------------------------------------------------

    /**
     * Scope: all nodes that are inside (descendants of) any node matched by {@code anchor}.
     *
     * Examples:
     *   //div[contains(@class,'x')] -> //*[ancestor::div[contains(@class,'x')]]
     *   (//div)[3]                  -> ((//div)[3])/descendant::*
     */
    public static XPathy insideOf(XPathy anchor) {
        String raw = anchor.getXpath().trim();

        if (isNodeSetExpression(raw)) {
            return XPathy.from(wrapExpression(raw) + "/descendant::*");
        }

        String step = asRelativeStep(anchor);

        if (step.startsWith("select[")) {
            String expr = "//*[ancestor::*[position()<=3][self::" + step + "][1]]";
            return XPathy.from(expr);
        }

        String expr = "//*[ancestor::" + step + "]";
        return XPathy.from(expr);
    }

    /**
     * Scope: all nodes that appear before any node matched by {@code anchor} in document order.
     *
     * Examples:
     *   //div[contains(@class,'x')] -> //*[following::div[contains(@class,'x')]]
     *   (//div)[3]                  -> ((//div)[3])/preceding::*
     */
    public static XPathy beforeOf(XPathy anchor) {
        String raw = anchor.getXpath().trim();

        if (isNodeSetExpression(raw)) {
            return XPathy.from(wrapExpression(raw) + "/preceding::*");
        }

        String step = asRelativeStep(anchor);
        String expr = "//*[following::" + step + "]";
        return XPathy.from(expr);
    }

    /**
     * Scope: all nodes that appear after any node matched by {@code anchor} in document order.
     *
     * Examples:
     *   //div[contains(@class,'x')] -> //*[preceding::div[contains(@class,'x')]]
     *   (//div)[3]                  -> ((//div)[3])/following::*
     */
    public static XPathy afterOf(XPathy anchor) {
        String raw = anchor.getXpath().trim();

        if (isNodeSetExpression(raw)) {
            return XPathy.from(wrapExpression(raw) + "/following::*");
        }

        String step = asRelativeStep(anchor);
        String expr = "//*[preceding::" + step + "]";
        return XPathy.from(expr);
    }

    /**
     * Scope: all nodes that are between any {@code before} node and any {@code after} node
     * in document order.
     */
    public static XPathy inBetweenOf(XPathy before, XPathy after) {
        String predicate = betweenPredicate(before, after);
        String expr = "//*[" + predicate + "]";
        return XPathy.from(expr);
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

    /**
     * Combine all XPathy with logical AND.
     */
    public static XPathy combineAnd(List<XPathy> list) {
        if (list.size() == 1) return list.getFirst();
        List<XPathy> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));
        return combineAndFinal(sorted);
    }

    public static XPathy combineAndFinal(List<XPathy> list) {
        validateList(list);

        XPathy first = list.getFirst();
        if (list.size() == 1) return first;

        List<String> xpathStrings = list.stream()
                .map(xPathy -> xPathy.getXpath().trim())
                .toList();

        // Fast path: all are //*[...]-style predicates -> concatenate predicate suffixes
        if (xpathStrings.stream().allMatch(XPathyAssembly::isUniversalSelection)) {
            return XPathy.from("//*" + xpathStrings.stream()
                    .map(string -> string.substring(3))
                    .collect(Collectors.joining("")));
        }

        // Existing fast path: //tag[...] + //*[...] -> //tag[...][...]
        String firstXpath = xpathStrings.getFirst();
        if (isSimpleDoubleSlashPath(firstXpath)) {
            String tail = combineAndFinal(list.subList(1, list.size()))
                    .getXpath()
                    .replace("[]", "")
                    .replaceFirst("^//\\*", "");
            return XPathy.from(firstXpath + tail);
        }

        // General path: convert each input to a predicate that means
        // "current node is selected by this component"
        return XPathy.from(combine(list, "and").getXpath().replace("[]", ""));
    }

    /**
     * Combine all XPathy with logical OR.
     */
    public static XPathy combineOr(List<XPathy> list) {
        if (list.size() == 1) return list.getFirst();
        List<XPathy> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));
        return combine(sorted, "or");
    }

    private static XPathy combine(List<XPathy> list, String joiner) {
        validateList(list);
        if (list.size() == 1) return list.getFirst();

        String combinedExpr = list.stream()
                .map(XPathy::getXpath)
                .map(XPathyAssembly::predicateForCurrentNode)
                .collect(Collectors.joining(" " + joiner + " "));

        String fullXpath = "//*[" + combinedExpr + "]";
        return XPathy.from(fullXpath);
    }



    /**
     * Membership predicate: true iff the current node is part of the node-set selected by xpath.
     */
    private static String toMembershipPredicate(String xpath) {
        String s = xpath.trim();
        return "count(. | " + s + ") = count(" + s + ")";
    }

    /**
     * Convert a step-like xpath into a self::... predicate.
     *
     * For parenthesized/node-set expressions, fall back to membership predicate form.
     */
    public static String toSelfStep(String xpath) {
        String s = xpath.trim();

        while (s.startsWith("(") && s.endsWith(")")) {
            s = s.substring(1, s.length() - 1).trim();
        }

        if (SELF_WRAP.matcher(s).matches()) {
            if (s.startsWith("//*[self")) {
                return s.substring(4, s.length() - 1);
            } else if (s.startsWith("//*[(self")) {
                return s.substring(5, s.length() - 2);
            }
            return xpath;
        }

        if (isNodeSetExpression(xpath)) {
            return toMembershipPredicate(xpath);
        }

        if (AXIS_PATTERN.matcher(s).matches()) {
            return s;
        }

        String stripped = s.replaceFirst("^[\\./]+", "").trim();

        if (AXIS_PATTERN.matcher(stripped).matches()) {
            return stripped;
        }

        int id = selfCounter.incrementAndGet();
        int nestingCount = countIdPairs(stripped);

        String step = stripped.replaceFirst(
                "^([A-Za-z_][A-Za-z0-9_.:-]*|\\*)",
                "self::$1[" + id + ">-" + nestingCount + "]"
        ) + "[" + id + ">-" + nestingCount + "]";

        return step;
    }

    public static int countIdPairs(String input) {
        if (input == null || input.isEmpty()) {
            return 0;
        }

        Matcher m = ID_PAIR_PATTERN.matcher(input);
        int count = 0;

        while (m.find()) {
            count++;
        }

        return count;
    }

    /**
     * Heuristic specificity scoring. Lower score = "more specific".
     */
    public static int xpathSpecificityScore(String xpath) {
        printDebug("\n##SpecificityScore xpath: " + xpath);
        xpath = xpath.trim();

        int score = countChar(xpath, '*') * 10;
        printDebug("##Xscore score1: " + score);

        if (xpath.startsWith("//*[preceding")
                || xpath.startsWith("//*[following")
                || xpath.startsWith("//*[ancestor")
                || xpath.startsWith("//*[descendant")) {
            score += 10_000_000;
            String firstTag = "";
            if (xpath.contains("::")) {
                firstTag = xpath.split("::", 2)[1].trim();
                if (!firstTag.startsWith("*")) {
                    score -= 1_000_000;
                    if (!firstTag.startsWith("div")) {
                        score -= 1_000_000;
                    }
                }
            }
            if (xpath.contains("\\[")) {
                firstTag = firstTag.split("\\[", 2)[1].trim();
                if (firstTag.startsWith("position")) {
                    score -= 1_500_000;
                }
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

        if (noSpace.contains("'opacity:0'")) {
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

        if (stripPseudoTags == null) stripPseudoTags = !substrings.contains("pseudotags");
        if (normalizeWhiteSpace == null) normalizeWhiteSpace = !substrings.contains("normalizexpaths");

        final int threshold = 20;
        final String indentUnit = " ";

        List<String> parts = splitAtBoundaries(xpath);

        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            int indentLevel = 0;
            Matcher t = PSEUDO_TAG.matcher(trimmed);
            if (t.find()) {
                int b = Integer.parseInt(t.group(2));
                indentLevel = Math.max(0, threshold - b);
            }

            if (stripPseudoTags) {
                trimmed = PSEUDO_TAG.matcher(trimmed).replaceAll("");
            }

            if (normalizeWhiteSpace) {
                trimmed = trimmed.replaceAll("[\\p{Z}\\s\\p{Cf}\\p{Cc}]+", " ").trim();
            }

            sb.append(indentUnit.repeat(indentLevel))
                    .append(trimmed)
                    .append('\n');
        }

        return sb.toString();
    }

    private static List<String> splitAtBoundaries(String s) {
        List<Integer> starts = new ArrayList<>();
        Matcher m = BOUNDARY.matcher(s);

        while (m.find()) {
            starts.add(m.start());
        }

        if (starts.isEmpty()) return List.of(s);

        List<String> parts = new ArrayList<>();

        int first = starts.get(0);
        if (first > 0) {
            parts.add(s.substring(0, first));
        }

        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : s.length();
            parts.add(s.substring(from, to));
        }

        return parts;
    }

    private static void validateList(List<XPathy> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null) {
                throw new IllegalStateException("Null XPathy at index " + i);
            }
            if (list.get(i).getXpath() == null) {
                throw new IllegalStateException("Null xpath string at index " + i);
            }
        }
    }

    private static boolean isNodeSetExpression(String xpath) {
        return xpath != null && xpath.trim().startsWith("(");
    }

    private static boolean isUniversalSelection(String xpath) {
        if (xpath == null) return false;
        String s = xpath.trim();
        return s.startsWith("//*[") && isSingleTopLevelPredicatePath(s);
    }

    private static boolean isSingleTopLevelPredicatePath(String xpath) {
        if (xpath == null) return false;
        String s = xpath.trim();
        if (!s.startsWith("//*[")) return false;

        int depth = 0;
        for (int i = 3; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (depth == 0) return false; // any top-level char outside predicates is not allowed
            if (depth < 0) return false;
        }
        return depth == 0;
    }

    private static boolean isSimpleDoubleSlashPath(String xpath) {
        if (xpath == null) return false;
        String s = xpath.trim();
        return !isNodeSetExpression(s) && !s.startsWith("//*") && s.startsWith("//");
    }

    private static String wrapExpression(String xpath) {
        return "(" + xpath.trim() + ")";
    }

    private static String unwrapOuterPredicate(String s) {
        s = s.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            int depth = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '[') depth++;
                else if (ch == ']') depth--;
                if (depth == 0 && i < s.length() - 1) {
                    return s;
                }
            }
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String predicateForCurrentNode(String xpath) {
        String s = xpath.trim();

        if (isUniversalSelection(s)) {
            return unwrapOuterPredicate(s.substring(3));
        }

        if (isNodeSetExpression(s)) {
            return toMembershipPredicate(s);
        }

        return toSelfStep(s);
    }
}