package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

public final class XPathyRegistry {
    private XPathyRegistry() {}

    @FunctionalInterface
    public interface Builder {
        XPathy build(String category, String value, Op op);
    }

    public enum Op { EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, GT, GTE, LT, LTE }

    public enum HtmlType {
        SHADOW_HOST,
        IFRAME
    }

    private static final ConcurrentMap<String, CopyOnWriteArraySet<HtmlType>> HTML_TYPE_REG =
            new ConcurrentHashMap<>();

    //========================================================
    // Inheritance configuration
    //========================================================

    // Default base category that *every* category implicitly inherits from
    private static final String BASE_CATEGORY = "BaseCategory";

    // childCategory -> [parentCategory1, parentCategory2, ...]
    //
    // Multiple inheritance allowed.
    // Parents do NOT have to be pre-registered; missing ones are just ignored.
    private static final Multimap<String, String> CATEGORY_PARENTS =
            ArrayListMultimap.create();

    /**
     * Register inheritance relationships for a category.
     */
    public static void registerCategoryInheritance(String category, String... parentCategories) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(parentCategories, "parentCategories must not be null");

        System.out.println("[XPathyRegistry] registerCategoryInheritance: category=" + category +
                ", parents=" + Arrays.toString(parentCategories));

        for (String parent : parentCategories) {
            if (parent == null) continue;
            if (parent.equals(category)) continue; // avoid trivial self-cycle
            CATEGORY_PARENTS.put(category, parent);
        }
    }

    /**
     * Convenience method: declare that MANY categories inherit from ONE parent.
     *
     * Example:
     *     registerParentOfCategories("Clickable", "Button", "Link", "MenuItem");
     *
     * This is equivalent to:
     *     registerCategoryInheritance("Button", "Clickable");
     *     registerCategoryInheritance("Link", "Clickable");
     *     registerCategoryInheritance("MenuItem", "Clickable");
     */
    public static void registerParentOfCategories(String parentCategory, String... childCategories) {
        Objects.requireNonNull(parentCategory, "parentCategory must not be null");
        Objects.requireNonNull(childCategories, "childCategories must not be null");

        System.out.println("[XPathyRegistry] registerParentOfCategories: parent=" + parentCategory +
                ", children=" + Arrays.toString(childCategories));

        for (String child : childCategories) {
            if (child == null) continue;
            if (child.equals(parentCategory)) continue; // avoid trivial cycle

            // This is just the same as the original operation, but inverted:
            registerCategoryInheritance(child, parentCategory);
        }
    }



    /**
     * Resolve full inheritance chain for a category.
     */
    private static List<String> resolveCategoryLineage(String category) {
        String root = (category == null || category.isBlank())
                ? BASE_CATEGORY
                : category;

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!ordered.add(current)) {
                continue; // already visited
            }

            Collection<String> parents = CATEGORY_PARENTS.get(current);
            if (parents != null) {
                for (String p : parents) {
                    if (p != null && !p.equals(current)) {
                        stack.push(p);
                    }
                }
            }
        }

        // Ensure all categories inherit from BaseCategory by default,
        // but do not force it for BaseCategory itself.
        if (!BASE_CATEGORY.equals(root) && !ordered.contains(BASE_CATEGORY)) {
            ordered.add(BASE_CATEGORY);
        }

        List<String> lineage = new ArrayList<>(ordered);
        System.out.println("[XPathyRegistry] resolveCategoryLineage: category=" + category +
                " -> lineage=" + lineage);
        return lineage;
    }

    //========================================================
    // Existing HTML type registration (unchanged)
    //========================================================

    public static void addHtmlTypes(String category, HtmlType... htmlTypes) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(htmlTypes, "htmlTypes must not be null");

        System.out.println("[XPathyRegistry] addHtmlTypes: category=" + category +
                ", htmlTypes=" + Arrays.toString(htmlTypes));

        if (htmlTypes.length == 0) {
            return;
        }

        CopyOnWriteArraySet<HtmlType> set = HTML_TYPE_REG.computeIfAbsent(
                category,
                k -> new CopyOnWriteArraySet<>()
        );

        for (HtmlType type : htmlTypes) {
            set.add(Objects.requireNonNull(type, "HtmlType must not be null"));
        }
    }

    public static Set<HtmlType> getHtmlTypes(String category) {
        var set = HTML_TYPE_REG.get(category);
        Set<HtmlType> result = (set == null || set.isEmpty())
                ? Set.of()
                : Set.copyOf(set);
        System.out.println("[XPathyRegistry] getHtmlTypes: category=" + category +
                " -> " + result);
        return result;
    }

    public static boolean hasHtmlType(String category, HtmlType type) {
        var set = HTML_TYPE_REG.get(category);
        boolean has = set != null && set.contains(type);
        System.out.println("[XPathyRegistry] hasHtmlType: category=" + category +
                ", type=" + type + " -> " + has);
        return has;
    }

    // ========================================================
    // OR / AND builder registries
    // ========================================================

    private static final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> OR_REG =
            new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> AND_REG =
            new ConcurrentHashMap<>();

    // --- OR builders ---

    /**
     * Register one or more OR-based builders for a single category.
     */
    public static void registerOrBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) {
            return;
        }

        System.out.println("[XPathyRegistry] registerOrBuilder: category=" + category +
                ", builderCount=" + builders.length);

        var list = OR_REG.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) {
            list.add(Objects.requireNonNull(b, "builder must not be null"));
        }
    }

    /**
     * Convenience: register the same OR-builders for multiple categories.
     */
    public static void registerOrForCategories(List<String> categories, Builder... builders) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (categories.isEmpty() || builders.length == 0) {
            return;
        }

        System.out.println("[XPathyRegistry] registerOrForCategories: categories=" + categories +
                ", builderCount=" + builders.length);

        for (String category : categories) {
            if (category != null && !category.isBlank()) {
                registerOrBuilder(category, builders);
            }
        }
    }

    // --- AND builders ---

    /**
     * Register one or more AND-based builders for a single category.
     */
    public static void registerAndBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) {
            return;
        }

        System.out.println("[XPathyRegistry] registerAndBuilder: category=" + category +
                ", builderCount=" + builders.length);

        var list = AND_REG.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) {
            list.add(Objects.requireNonNull(b, "builder must not be null"));
        }
    }

    /**
     * Convenience: register the same AND-builders for multiple categories.
     */
    public static void registerAndForCategories(List<String> categories, Builder... builders) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (categories.isEmpty() || builders.length == 0) {
            return;
        }

        System.out.println("[XPathyRegistry] registerAndForCategories: categories=" + categories +
                ", builderCount=" + builders.length);

        for (String category : categories) {
            if (category != null && !category.isBlank()) {
                registerAndBuilder(category, builders);
            }
        }
    }

    //========================================================
    // Expansion with inheritance
    //========================================================

    /**
     * Core expansion that honors inheritance + "*" fallback.
     */
    private static List<XPathy> expandInternal(
            ConcurrentMap<String, CopyOnWriteArrayList<Builder>> map,
            String category,
            String value,
            Op op
    ) {
        String requestCategory = (category == null || category.isBlank())
                ? BASE_CATEGORY
                : category;

        List<String> lineage = resolveCategoryLineage(requestCategory);
        List<Builder> allBuilders = new ArrayList<>();

        for (String catKey : lineage) {
            var builders = map.get(catKey);
            if (builders != null && !builders.isEmpty()) {
                System.out.println("[XPathyRegistry] expandInternal: category=" + requestCategory +
                        ", map=" + (map == OR_REG ? "OR_REG" : "AND_REG") +
                        ", catKey=" + catKey + ", buildersCount=" + builders.size());
                allBuilders.addAll(builders);
            }
        }

        // If nothing found in lineage, fallback to "*"
        if (allBuilders.isEmpty()) {
            var starList = map.get("*");
            if (starList == null || starList.isEmpty()) {
                System.out.println("[XPathyRegistry] expandInternal: category=" + requestCategory +
                        ", map=" + (map == OR_REG ? "OR_REG" : "AND_REG") +
                        " -> no builders found, no '*' fallback.");
                return List.of();
            }
            System.out.println("[XPathyRegistry] expandInternal: category=" + requestCategory +
                    ", map=" + (map == OR_REG ? "OR_REG" : "AND_REG") +
                    " -> using '*' fallback, buildersCount=" + starList.size());
            allBuilders.addAll(starList);
        }

        List<XPathy> result = allBuilders.stream()
                .map(b -> b.build(requestCategory, value, op)).filter(Objects::nonNull)
                .toList();

        System.out.println("[XPathyRegistry] expandInternal: category=" + requestCategory +
                ", map=" + (map == OR_REG ? "OR_REG" : "AND_REG") +
                ", value=" + value + ", op=" + op +
                " -> produced XPathy count=" + result.size());
        for (int i = 0; i < result.size(); i++) {
            XPathy x = result.get(i);
            System.out.println("    [expandInternal] xpath[" + i + "]=" + safeXpath(x));
        }

        return result;
    }

    private static String safeXpath(XPathy x) {
        try {
            return x == null ? "null" : x.getXpath();
        } catch (Exception e) {
            return "ERROR_GETTING_XPATH: " + e;
        }
    }

    public static List<XPathy> expandOr(String category, String value, Op op) {
        System.out.println("[XPathyRegistry] expandOr: category=" + category +
                ", value=" + value + ", op=" + op);
        return expandInternal(OR_REG, category, value, op);
    }

    public static List<XPathy> expandAnd(String category, String value, Op op) {
        System.out.println("[XPathyRegistry] expandAnd: category=" + category +
                ", value=" + value + ", op=" + op);
        return expandInternal(AND_REG, category, value, op);
    }

    //========================================================
    // String-based combination helpers
    //========================================================

    /**
     * Combine a list of XPathy into a single XPathy using "and"/"or".
     *
     * We:
     *   - Sort by heuristic specificity,
     *   - Convert each to a self:: step,
     *   - Join them into a boolean expression,
     *   - Wrap that expression into a valid selector: //*[(... boolean ...)]
     *
     * NOTE: This is what fixes invalid selectors like "(self::* or self::*)[1]".
     */
    private static Optional<XPathy> combine(List<XPathy> list, String joiner) {
        System.out.println("[XPathyRegistry] combine: joiner=" + joiner +
                ", listSize=" + list.size());

        if (list.isEmpty()) {
            System.out.println("[XPathyRegistry] combine: list empty, returning Optional.empty()");
            return Optional.empty();
        }

        // Make a defensive copy so we don't mutate caller's list
        List<XPathy> sorted = new ArrayList<>(list);

        // Sort by heuristic specificity score (lower is "better")
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));

        System.out.println("[XPathyRegistry] combine: after sort, xpaths=");
        for (int i = 0; i < sorted.size(); i++) {
            System.out.println("    [combine] sorted[" + i + "]=" + safeXpath(sorted.get(i)));
        }

        // Build boolean expression over self::... steps
        String combinedExpr = sorted.stream()
                .map(XPathy::getXpath)
                .map(XPathyRegistry::toSelfStep)
                .peek(s -> System.out.println("    [combine] selfStep=" + s))
                .collect(java.util.stream.Collectors.joining(" " + joiner + " "));

        System.out.println("[XPathyRegistry] combine: combinedExpr=" + combinedExpr);

        // Wrap into a valid selector path for Selenium: //*[(self::... or self::...)]
        String fullXpath = "//*[" + combinedExpr + "]";
        System.out.println("[XPathyRegistry] combine: fullXpath=" + fullXpath);

        XPathy x = XPathy.from(fullXpath);
        System.out.println("[XPathyRegistry] combine: final XPathy.getXpath()=" + safeXpath(x));

        return Optional.of(x);
    }

    public static Optional<XPathy> andAll(String category, String value, Op op) {
        System.out.println("[XPathyRegistry] andAll: category=" + category +
                ", value=" + value + ", op=" + op);
        Optional<XPathy> result = combine(expandAnd(category, value, op), "and");
        System.out.println("[XPathyRegistry] andAll: resultPresent=" + result.isPresent() +
                (result.isPresent() ? ", xpath=" + safeXpath(result.get()) : ""));
        return result;
    }

    public static Optional<XPathy> orAll(String category, String value, Op op) {
        System.out.println("[XPathyRegistry] orAll: category=" + category +
                ", value=" + value + ", op=" + op);
        Optional<XPathy> result = combine(expandOr(category, value, op), "or");
        System.out.println("[XPathyRegistry] orAll: resultPresent=" + result.isPresent() +
                (result.isPresent() ? ", xpath=" + safeXpath(result.get()) : ""));
        return result;
    }

    /**
     * Combined filter:
     *   - First: all AND-based builders must match (intersection).
     *   - Then: at least one OR-based builder must match (union).
     *
     * Returns a concrete XPathy, or throws if both sides are empty.
     */
    public static XPathy andThenOr(String category, String value, Op op) {
        System.out.println("[XPathyRegistry] andThenOr: category=" + category +
                ", value=" + value + ", op=" + op);

        Optional<XPathy> andPart = andAll(category, value, op);
        Optional<XPathy> orPart  = orAll(category, value, op);

        System.out.println("[XPathyRegistry] andThenOr: andPartPresent=" + andPart.isPresent() +
                (andPart.isPresent() ? ", andXpath=" + safeXpath(andPart.get()) : ""));
        System.out.println("[XPathyRegistry] andThenOr: orPartPresent=" + orPart.isPresent() +
                (orPart.isPresent() ? ", orXpath=" + safeXpath(orPart.get()) : ""));

        if (andPart.isEmpty() && orPart.isEmpty()) {
            throw new IllegalStateException(
                    "[XPathyRegistry] andThenOr: both AND and OR parts empty " +
                            "for category='" + category + "', value=" + value + ", op=" + op);
        }
        if (andPart.isEmpty()) {
            System.out.println("[XPathyRegistry] andThenOr: only orPart present, returning it.");
            return orPart.get();
        }
        if (orPart.isEmpty()) {
            System.out.println("[XPathyRegistry] andThenOr: only andPart present, returning it.");
            return andPart.get();
        }

        String andStep = toSelfStep(andPart.get().getXpath());
        String orStep  = toSelfStep(orPart.get().getXpath());

        String combinedExpr = "(" + andStep + ") and (" + orStep + ")";
        System.out.println("[XPathyRegistry] andThenOr: combinedExpr=" + combinedExpr);

        // Wrap in a selector path as well
        String fullXpath = "//*[" + combinedExpr + "]";
        System.out.println("[XPathyRegistry] andThenOr: fullXpath=" + fullXpath);

        XPathy x = XPathy.from(fullXpath);
        System.out.println("[XPathyRegistry] andThenOr: final XPathy.getXpath()=" + safeXpath(x));

        return x;
    }

    /**
     * Convenience: OR-combine an initial XPathy with additional XPathy expressions.
     */
    public static XPathy combineOr(XPathy first, XPathy... rest) {
        Objects.requireNonNull(first, "first XPathy must not be null");
        List<XPathy> list = new ArrayList<>();
        list.add(first);

        if (rest != null && rest.length > 0) {
            for (XPathy x : rest) {
                list.add(Objects.requireNonNull(x, "XPathy in rest must not be null"));
            }
        }

        System.out.println("[XPathyRegistry] combineOr: totalCount=" + list.size());
        Optional<XPathy> result = combine(list, "or");
        if (result.isEmpty()) {
            throw new IllegalStateException("combineOr was given an empty list, which should not happen.");
        }
        return result.get();
    }

    /**
     * Convenience: AND-combine an initial XPathy with additional XPathy expressions.
     */
    public static XPathy combineAnd(XPathy first, XPathy... rest) {
        Objects.requireNonNull(first, "first XPathy must not be null");
        List<XPathy> list = new ArrayList<>();
        list.add(first);

        if (rest != null && rest.length > 0) {
            for (XPathy x : rest) {
                list.add(Objects.requireNonNull(x, "XPathy in rest must not be null"));
            }
        }

        System.out.println("[XPathyRegistry] combineAnd: totalCount=" + list.size());
        Optional<XPathy> result = combine(list, "and");
        if (result.isEmpty()) {
            throw new IllegalStateException("combineAnd was given an empty list, which should not happen.");
        }
        return result.get();
    }

    //========================================================
    // Utility: normalize an XPath to a "self::" step
    //========================================================

    public static String toSelfStep(String xpath) {
        if (xpath == null) {
            return "self::*";
        }

        String s = xpath.trim();

        // Special-case: patterns like (//*)[...]
        // We want: self::*[...]
        if (s.startsWith("(//*)[")) {
            String tail = s.substring("(//*)".length()); // keep the [...] part
            String out = "self::*" + tail;
            System.out.println("[XPathyRegistry] toSelfStep (paren //*): input=" + xpath + " -> " + out);
            return out;
        }

        // Strip leading //, /, .//, ./ etc.
        s = s.replaceFirst("^\\.?/+", "");

        // Find the last "/" that is NOT inside "[" ... "]"
        int depth = 0;
        int lastTopLevelSlash = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                if (depth > 0) depth--;
            } else if (c == '/' && depth == 0) {
                lastTopLevelSlash = i;
            }
        }

        if (lastTopLevelSlash >= 0) {
            s = s.substring(lastTopLevelSlash + 1);
        }

        // If already starts with an axis ("descendant::", "self::", etc.), keep it
        if (s.matches("^[A-Za-z-]+::.*")) {
            System.out.println("[XPathyRegistry] toSelfStep: input=" + xpath + " (already axis) -> " + s);
            return s;
        }

        String out = "self::" + s;
        System.out.println("[XPathyRegistry] toSelfStep: input=" + xpath + " -> " + out);
        return out;
    }

    // Heuristic scoring for XPath specificity / efficiency.
    // Lower score == considered "better" (more specific / likely more selective).
    private static int xpathSpecificityScore(String xpath) {
        if (xpath == null || xpath.isBlank()) {
            return Integer.MAX_VALUE;
        }

        String s = xpath.trim();
        int score = 1000;

        // 1) Penalize wildcards and very generic patterns
        if (s.contains("//*")) {
            score += 80;
        }
        if (s.matches(".*\\b\\*\\b.*")) { // any bare '*' node test
            score += 50;
        }
        if (s.contains("self::*")) {
            score += 30;
        }

        // 2) Reward predicates: more predicates usually mean more selectivity
        int predicateCount = countChar(s, '[');
        score -= predicateCount * 5;

        // 3) Reward "strong" attributes
        if (s.contains("@id")) {
            score -= 40;
        }
        if (s.contains("@data-testid") || s.contains("@data-test") || s.contains("@data-")) {
            score -= 25;
        }
        if (s.contains("@name")) {
            score -= 15;
        }
        if (s.contains("@class")) {
            score -= 10;
        }

        // 4) Reward custom element tags (web components) as likely rarer = more selective
        if (s.matches(".*//[a-zA-Z0-9]+-[a-zA-Z0-9_-]+.*")) {
            score -= 20;
        }

        // 5) Reward explicit tag at the root instead of wildcard
        if (s.matches("^\\s*//[a-zA-Z].*")) {
            score -= 10; // starts with explicit tag
        } else if (s.matches("^\\s*//\\*.*")) {
            score += 10; // starts with wildcard
        }

        if (score < 0) score = 0;

        System.out.println("[XPathyRegistry] xpathSpecificityScore: xpath=" + xpath +
                ", score=" + score);

        return score;
    }

    // Simple helper for counting characters (predicates, etc.)
    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
