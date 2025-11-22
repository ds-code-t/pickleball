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
        XPathy build(String category, Object value, Op op);
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
     *
     * Example:
     *   registerCategoryInheritance("Button.Primary", "Button", "Clickable");
     *
     * This means:
     *   - "Button.Primary" inherits all builders from "Button" and "Clickable"
     *   - And recursively from *their* parents as well.
     *
     * NOTE:
     *   - Does NOT throw if a parent category never registers any builders.
     *     Those parents simply contribute nothing.
     */
    public static void registerCategoryInheritance(String category, String... parentCategories) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(parentCategories, "parentCategories must not be null");

        for (String parent : parentCategories) {
            if (parent == null) continue;
            if (parent.equals(category)) continue; // avoid trivial self-cycle
            CATEGORY_PARENTS.put(category, parent);
        }
    }

    /**
     * Resolve full inheritance chain for a category, including:
     *   - The category itself
     *   - All ancestors (recursively)
     *   - BASE_CATEGORY (by default) for all categories except BaseCategory itself
     *
     * Order is stable and de-duplicated (first occurrence preserved).
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

        return new ArrayList<>(ordered);
    }

    //========================================================
    // Existing HTML type registration (unchanged)
    //========================================================

    public static void addHtmlTypes(String category, HtmlType... htmlTypes) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(htmlTypes, "htmlTypes must not be null");

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
        return (set == null || set.isEmpty())
                ? Set.of()
                : Set.copyOf(set);
    }

    public static boolean hasHtmlType(String category, HtmlType type) {
        var set = HTML_TYPE_REG.get(category);
        return set != null && set.contains(type);
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
     *
     * Usage:
     *   registerOrBuilder("Button", b1);
     *   registerOrBuilder("Button", b1, b2, b3);
     */
    public static void registerOrBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) {
            return;
        }
        var list = OR_REG.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) {
            list.add(Objects.requireNonNull(b, "builder must not be null"));
        }
    }

    /**
     * Convenience: register the same OR-builders for multiple categories.
     *
     * Example:
     *   registerOrForCategories(List.of("Button", "Link"), b1, b2);
     */
    public static void registerOrForCategories(List<String> categories, Builder... builders) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (categories.isEmpty() || builders.length == 0) {
            return;
        }

        for (String category : categories) {
            if (category != null && !category.isBlank()) {
                registerOrBuilder(category, builders);
            }
        }
    }

    // --- AND builders ---

    /**
     * Register one or more AND-based builders for a single category.
     *
     * Usage:
     *   registerAndBuilder("Button", b1);
     *   registerAndBuilder("Button", b1, b2, b3);
     */
    public static void registerAndBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) {
            return;
        }

        var list = AND_REG.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) {
            list.add(Objects.requireNonNull(b, "builder must not be null"));
        }
    }

    /**
     * Convenience: register the same AND-builders for multiple categories.
     *
     * Example:
     *   registerAndForCategories(List.of("Button", "Clickable"), b1, b2);
     */
    public static void registerAndForCategories(List<String> categories, Builder... builders) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (categories.isEmpty() || builders.length == 0) {
            return;
        }

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
     * Core expansion that honors inheritance.
     *
     * For the requested category:
     *   - Resolve full lineage (category + ancestors + BaseCategory).
     *   - Accumulate builders from all categories in that lineage.
     *   - If no builders at all are found, fall back to "*" as before.
     *
     * IMPORTANT:
     *   - Builders are always invoked with the original requested category
     *     (not the ancestor category name), so existing Builder logic that
     *     expects the "consumer category" is preserved.
     */
    private static List<XPathy> expandInternal(
            ConcurrentMap<String, CopyOnWriteArrayList<Builder>> map,
            String category,
            Object value,
            Op op
    ) {
        String requestCategory = (category == null || category.isBlank())
                ? BASE_CATEGORY
                : category;

        // Resolve inheritance chain: [requestCategory, parent1, parent2, ..., BaseCategory]
        List<String> lineage = resolveCategoryLineage(requestCategory);

        List<Builder> allBuilders = new ArrayList<>();

        for (String catKey : lineage) {
            var builders = map.get(catKey);
            if (builders != null && !builders.isEmpty()) {
                allBuilders.addAll(builders);
            }
        }

        // Preserve old behavior: if nothing found in lineage, fallback to "*"
        if (allBuilders.isEmpty()) {
            var starList = map.get("*");
            if (starList == null || starList.isEmpty()) {
                return List.of();
            }

            // Informative message when we have to use the fallback category.
            System.out.println(
                    "XPathyRegistry: no builders found for category '" + requestCategory +
                            "' (including ancestors). Falling back to '*' category."
            );

            allBuilders.addAll(starList);
        }

        return allBuilders.stream()
                .map(b -> b.build(requestCategory, value, op))
                .toList();
    }

    public static List<XPathy> expandOr(String category, Object value, Op op) {
        return expandInternal(OR_REG, category, value, op);
    }

    public static List<XPathy> expandAnd(String category, Object value, Op op) {
        return expandInternal(AND_REG, category, value, op);
    }

    //========================================================
    // String-based combination helpers
    //========================================================

    private static Optional<XPathy> combine(List<XPathy> list, String joiner) {
        if (list.isEmpty()) return Optional.empty();

        // Make a defensive copy so we don't mutate caller's list
        List<XPathy> sorted = new ArrayList<>(list);

        // Sort by our heuristic specificity score (lower is "better")
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));

        String combined = sorted.stream()
                .map(XPathy::getXpath)
                .map(XPathyRegistry::toSelfStep)
                .collect(java.util.stream.Collectors.joining(" " + joiner + " "));

        return Optional.of(XPathy.from(combined));
    }

    /**
     * Combine all AND-based XPathy expressions for the given category into a single XPathy.
     * Assumes that, through fallback "*", there is always at least one builder registered.
     */
    public static XPathy andAll(String category, Object value, Op op) {
        List<XPathy> list = expandAnd(category, value, op);
        return combine(list, "and")
                .orElseThrow(() -> new IllegalStateException(
                        "No XPathy builders registered for category '" + category +
                                "' or fallback '*' (andAll)."));
    }

    /**
     * Combine all OR-based XPathy expressions for the given category into a single XPathy.
     * Assumes that, through fallback "*", there is always at least one builder registered.
     */
    public static XPathy orAll(String category, Object value, Op op) {
        List<XPathy> list = expandOr(category, value, op);
        return combine(list, "or")
                .orElseThrow(() -> new IllegalStateException(
                        "No XPathy builders registered for category '" + category +
                                "' or fallback '*' (orAll)."));
    }

    /**
     * Combined filter:
     *   - First: all AND-based builders must match (intersection).
     *   - Then: at least one OR-based builder must match (union).
     *
     * Final XPath: (AND part) and (OR part)
     *
     * Always returns a non-null XPathy due to the fallback "*" category.
     */
    public static XPathy andThenOr(String category, Object value, Op op) {
        // Expand using existing logic (which prints fallback notice if needed)
        List<XPathy> andList = expandAnd(category, value, op);
        List<XPathy> orList  = expandOr(category, value, op);

        // Combine both sides using existing combine() method
        XPathy andPart = combine(andList, "and")
                .orElseThrow(() -> new IllegalStateException(
                        "No AND builders found for category '" + category +
                                "' or fallback '*', which should be impossible."));
        XPathy orPart = combine(orList, "or")
                .orElseThrow(() -> new IllegalStateException(
                        "No OR builders found for category '" + category +
                                "' or fallback '*', which should be impossible."));

        // Convert both to self:: steps
        String andStep = toSelfStep(andPart.getXpath());
        String orStep  = toSelfStep(orPart.getXpath());

        // Final combined XPath
        String combined = "(" + andStep + ") and (" + orStep + ")";
        return XPathy.from(combined);
    }


    /**
     * Convenience: OR-combine an initial XPathy with additional XPathy expressions.
     *
     * Example:
     *   XPathy combined = combineOr(x1, x2, x3);
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

        return combine(list, "or")
                .orElseThrow(() -> new IllegalStateException("combineOr was given an empty list, which should not happen."));
    }

    /**
     * Convenience: AND-combine an initial XPathy with additional XPathy expressions.
     *
     * Example:
     *   XPathy combined = combineAnd(x1, x2, x3);
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

        return combine(list, "and")
                .orElseThrow(() -> new IllegalStateException("combineAnd was given an empty list, which should not happen."));
    }

    //========================================================
    // Utility: normalize an XPath to a "self::" step
    //========================================================

    public static String toSelfStep(String xpath) {
        if (xpath == null) {
            return "self::*";
        }

        String s = xpath.trim();

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
            return s;
        }

        return "self::" + s;
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
        // e.g., //<my-button>, //app-root, etc.
        if (s.matches(".*//[a-zA-Z0-9]+-[a-zA-Z0-9_-]+.*")) {
            score -= 20;
        }

        // 5) Reward explicit tag at the root instead of wildcard
        if (s.matches("^\\s*//[a-zA-Z].*")) {
            score -= 10; // starts with explicit tag
        } else if (s.matches("^\\s*//\\*.*")) {
            score += 10; // starts with wildcard
        }

        // Don't let it go crazy negative; keep in a reasonable range
        if (score < 0) score = 0;

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
