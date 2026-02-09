package tools.dscode.common.domoperations;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly;

import java.sql.SQLOutput;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.xpathy.Tag.any;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.xpathSpecificityScore;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedText;

public class ExecutionDictionary {


    public static final String singleControlElementContainer =
            "       [" +
                    "   count(descendant::*[self::input or self::textarea or self::select][not(@type='hidden')]) = 1" +
                    "   and descendant::*[self::input or self::textarea or self::select]" +
                    "   [" +
                    "       not(preceding-sibling::*[normalize-space(string(.)) != ''])" +
                    "       and " +
                    "       not(following-sibling::*[normalize-space(string(.)) != ''])" +
                    "   ]" +
                    "]";



    @FunctionalInterface
    public interface Builder {
        XPathy build(String category, ValueWrapper value, Op op);
    }

    @FunctionalInterface
    public interface ContextBuilder {
        SearchContext build(String category, ValueWrapper value, Op op, WebDriver driver, SearchContext context);
    }

    // Default base category that *every* category implicitly inherits from
    public static final String CONTAINS_TEXT = "ContainsTextInternalUSE";
    public static final String BASE_CATEGORY = "BaseCategoryInternalUSE";
    public static final String STARTING_CONTEXT = "DefaultStartingContextInternalUSE";
    public static final String VISIBILITY_FILTER = "VisibilityFilterInternalUSE";


    //TODO  HAS , HAS_NOT, and negative versions of all operations
    public enum Op {
        DEFAULT, EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, GT, GTE, LT, LTE, MATCHES, HAS, HAS_NOT;


        public static Op getOpFromString(String input) {
            return switch (input.trim().toLowerCase()) {
                case String s when s.startsWith("has") -> s.contains("no") ? Op.HAS_NOT : Op.HAS;
                case String s when s.startsWith("equal") -> Op.EQUALS;
                case String s when s.startsWith("contain") -> Op.CONTAINS;
                case String s when s.startsWith("start") -> Op.STARTS_WITH;
                case String s when s.startsWith("matches") -> Op.MATCHES;
                case String s when s.startsWith("end") -> Op.ENDS_WITH;
                case String s when s.startsWith("greater") -> s.contains("equal") ? Op.GTE : Op.GT;
                case String s when s.startsWith("lesser") -> s.contains("equal") ? Op.LTE : Op.LT;
                default -> null;
            };
        }
    }

    public enum CategoryFlags {PAGE_CONTEXT, PAGE_TOP_CONTEXT, ELEMENT_CONTEXT, SHADOW_HOST, IFRAME, NON_DISPLAY_ELEMENT}

    //========================================================
    // Instance state
    //========================================================

    // childCategory -> [parentCategory1, parentCategory2, ...]
    private final Multimap<String, String> categoryParents = ArrayListMultimap.create();

    private final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> orReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> andReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ContextBuilder> contextReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<CategoryFlags>> categoryFlags = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, XPathy> baseReg = new ConcurrentHashMap<>();

    // Per-instance cache: category -> resolved lineage (includes the category itself).
// Safe for concurrent reads during XPath generation, and isolated per ExecutionDictionary instance.
    private final ConcurrentMap<String, List<String>> lineageCache = new ConcurrentHashMap<>();
    //========================================================
    // Construction / registration hook
    //========================================================

    protected ExecutionDictionary() {
        register();
        defaultRegistrations();
    }

    /**
     * Override in anonymous subclasses to register builders, category inheritance, html types, etc.
     */
    protected void register() {
    }

    private void defaultRegistrations() {

        category("Text").children("Texts").context((category, v, op, webDriver, ctx) ->
                ctx
        );

//        category("Text").children("Texts").inheritsFrom(CONTAINS_TEXT).
//                and(
//                        (category, v, op) ->
//                        {
//                            if (v == null || v.isNull())
//                                return XPathy.from("//*[ancestor-or-self::body and descendant::text()]");
//                            return XPathy.from("//*[count(descendant::node()) <= 12]");
//                        }
//                );

        category(CONTAINS_TEXT).and((category, v, op) -> {
            if (v == null || v.isNull())
                return null;
            return any.byHaving(
                    XPathy.from("descendant-or-self::*")
                            .byHaving(deepNormalizedText(v, op))
            );
        });
    }


    //========================================================
    // Inheritance configuration
    //========================================================

    /**
     * Register inheritance relationships for a category.
     */
    public void registerCategoryInheritance(String category, String... parentCategories) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(parentCategories, "parentCategories must not be null");

        for (String parent : parentCategories) {
            if (parent == null) continue;
            if (parent.equals(category)) continue; // avoid trivial self-cycle
            categoryParents.put(category, parent);
        }

        // Invalidate per-instance lineage cache (safe + minimal).
        // Ensures correctness even if someone resolves before all registrations finish.
        lineageCache.clear();
    }


    /**
     * Convenience method: declare that MANY categories inherit from ONE parent.
     */
    public void registerParentOfCategories(String parentCategory, String... childCategories) {
        Objects.requireNonNull(parentCategory, "parentCategory must not be null");
        Objects.requireNonNull(childCategories, "childCategories must not be null");

        for (String child : childCategories) {
            if (child == null) continue;
            if (child.equals(parentCategory)) continue; // avoid trivial cycle
            registerCategoryInheritance(child, parentCategory);
        }

        // registerCategoryInheritance already clears, but keeping this is harmless and explicit.
        lineageCache.clear();
    }


    /**
     * Resolve full inheritance chain for a category.
     */
    private List<String> resolveCategoryLineage(String category) {
        if (category == null || category.isBlank()) return new ArrayList<>();

        // computeIfAbsent is concurrency-safe; list is immutable to prevent accidental mutation.
        return lineageCache.computeIfAbsent(category, key -> {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(key);

            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!ordered.add(current)) continue;

                Collection<String> parents = categoryParents.get(current);
                if (parents != null) {
                    for (String p : parents) {
                        if (p != null && !p.equals(current)) stack.push(p);
                    }
                }
            }

            return List.copyOf(ordered); // immutable cached value
        });
    }

    private void registerBaseXpath(String category, XPathy base) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(base, "base xpath must not be null");
        baseReg.put(category, base);
    }

    private XPathy getBaseXpath(String category) {
        if (category == null || category.isBlank()) return null;

        // Prefer the most-specific category first, then parents (matches your lineage order)
        List<String> lineage = resolveCategoryLineage(category);
        for (String catKey : lineage) {
            XPathy base = baseReg.get(catKey);
            if (base != null) return base;
        }
        return null;
    }



    // ========================================================
    // OR / AND builder registries
    // ========================================================

    public void registerOrBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) return;

        var list = orReg.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) list.add(Objects.requireNonNull(b, "builder must not be null"));
    }

    public void registerOrForCategories(List<String> categories, Builder... builders) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (categories.isEmpty() || builders.length == 0) return;

        for (String category : categories) {
            if (category != null && !category.isBlank()) registerOrBuilder(category, builders);
        }
    }

    public void registerAndBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) return;

        var list = andReg.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) list.add(Objects.requireNonNull(b, "builder must not be null"));
    }

    public void registerAndForCategories(List<String> categories, Builder... builders) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (categories.isEmpty() || builders.length == 0) return;

        for (String category : categories) {
            if (category != null && !category.isBlank()) registerAndBuilder(category, builders);
        }
    }

    public boolean categoryHasRegistration(String category) {
        Objects.requireNonNull(category, "category must not be null");
        List<String> lineage = resolveCategoryLineage(category);
        return hasAnyRegisteredBuilders(lineage);
    }

    //========================================================
    // Expansion with inheritance
    //========================================================

    private List<XPathy> expandInternal(
            ConcurrentMap<String, CopyOnWriteArrayList<Builder>> map,
            String category,
            ValueWrapper value,
            Op op
    ) {
        List<String> lineage = resolveCategoryLineage(category);

        List<Builder> allBuilders = new ArrayList<>();
        for (String catKey : lineage) {
            var builders = map.get(catKey);
            if (builders != null && !builders.isEmpty()) allBuilders.addAll(builders);
        }

        if (allBuilders.isEmpty()) {
            if (!hasAnyRegisteredBuilders(lineage)) {
                var starList = map.get("*");
                if (starList == null || starList.isEmpty()) return List.of();
                allBuilders.addAll(starList);
            } else {
                return List.of();
            }
        }

        return allBuilders.stream()
                .map(b -> b.build(category, value, op))
                .filter(Objects::nonNull)
                .toList();
    }

    private String safeXpath(XPathy x) {
        try {
            return x == null ? "null" : x.getXpath();
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR_GETTING_XPATH: " + e;
        }
    }

    public List<XPathy> expandOr(String category, ValueWrapper value, Op op) {
        return expandInternal(orReg, category, value, op);
    }

    public List<XPathy> expandAnd(String category, ValueWrapper value, Op op) {
        return Stream.concat(
                        expandInternal(andReg, category, value, op).stream(),
                        expandInternal(andReg, BASE_CATEGORY, value, op).stream()
                )
                .collect(Collectors.toList());
    }

    //========================================================
    // String-based combination helpers
    //========================================================

    private Optional<XPathy> combine(List<XPathy> list, String joiner) {
        if (list == null || list.isEmpty()) return Optional.empty();

        List<XPathy> filtered = list.stream()
                .filter(Objects::nonNull)
                .filter(x -> {
                    try {
                        String xp = x.getXpath();
                        return xp != null && !xp.isBlank();
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();

        if (filtered.isEmpty()) return Optional.empty();

        List<XPathy> sorted = new ArrayList<>(filtered);
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));

        String combinedExpr = sorted.stream()
                .map(XPathy::getXpath)
                .map(XPathyAssembly::toSelfStep)
                .filter(s -> s != null && !s.isBlank() && !s.equals("self::*[]"))
                .collect(Collectors.joining(" " + joiner + " "));

        if (combinedExpr.isBlank()) return Optional.empty();

        return Optional.of(XPathy.from("//*[" + combinedExpr + "]"));
    }

    public Optional<XPathy> andAll(String category, ValueWrapper value, Op op) {
        return combine(expandAnd(category, value, op), "and");
    }

    public Optional<XPathy> orAll(String category, ValueWrapper value, Op op) {
        return combine(expandOr(category, value, op), "or");
    }

    public XPathy andThenOr(String category, ValueWrapper value, Op op) {
        Optional<XPathy> result = resolveToXPathy(category, value, op);
        return result.orElse(null);
    }

    public XPathy getCategoryXPathy(String category) {
        return andThenOr(category, null, null);
    }

    public record CategoryResolution(String category, ValueWrapper value, Op op, XPathy xpath,
                                     Set<CategoryFlags> flags) {
    }

    public CategoryResolution andThenOrWithFlags(String category, ValueWrapper value, Op op) {
        XPathy xpath = andThenOr(category, value, op);
        Set<CategoryFlags> flags = getResolvedCategoryFlags(category);
        return new CategoryResolution(category, value, op, xpath, flags);
    }

    public Optional<XPathy> resolveToXPathy(String category, ValueWrapper value, Op op) {
        XPathy base = getBaseXpath(category); // <-- resolve base first
        Optional<XPathy> orPart = orAll(category, value, op);
        Optional<XPathy> andPart = andAll(category, value, op);
        // If nothing else exists (or everything filtered out), return base if present
        if (andPart.isEmpty() && orPart.isEmpty()) {
            return base == null ? Optional.empty() : Optional.of(base);
        }

        // Convert each part into a predicate step (self::... form)
        String andStep = andPart.map(xPathy -> XPathyAssembly.toSelfStep(xPathy.getXpath())).orElse(null);
        String orStep = orPart.map(xPathy -> XPathyAssembly.toSelfStep(xPathy.getXpath())).orElse(null);
        // Order them by specificity score (lower first, consistent with combine())
        record Step(String step, int score) {}

        List<Step> steps = new ArrayList<>(2);
        if (andStep != null && !andStep.isBlank()) {
            steps.add(new Step(andStep, xpathSpecificityScore(andPart.get().getXpath())));
        }
        if (orStep != null && !orStep.isBlank()) {
            steps.add(new Step(orStep, xpathSpecificityScore(orPart.get().getXpath())));
        }

        if (steps.isEmpty()) return Optional.empty();
        steps.sort(Comparator.comparingInt(Step::score));
        String combined = steps.stream()
                .map(Step::step)
                .map(s -> "(" + s + ")")
                .collect(Collectors.joining(" and "));
        String prefix = (base != null && base.getXpath() != null && !base.getXpath().isBlank())
                ? base.getXpath().trim()
                : "//*";

        return Optional.of(XPathy.from(prefix + "[" + combined + "]"));

    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }

    private boolean hasAnyRegisteredBuilders(List<String> lineage) {
        for (String catKey : lineage) {
            var orBuilders = orReg.get(catKey);
            if (orBuilders != null && !orBuilders.isEmpty()) return true;
            var andBuilders = andReg.get(catKey);
            if (andBuilders != null && !andBuilders.isEmpty()) return true;
        }
        return false;
    }

    // ======================================================
    // Fluent API entry points (single spec type)
    // ======================================================

    /**
     * Start a fluent definition for a single category on this dictionary instance.
     */
    public CategorySpec category(String name) {
        return new CategorySpec(this, List.of(name));
    }

    /**
     * Start a fluent definition for multiple categories at once on this dictionary instance.
     */
    public CategorySpec categories(String... names) {
        return new CategorySpec(this, Arrays.asList(names));
    }

    public CategorySpec registerDefaultStartingContext(ContextBuilder builder) {
        return category(STARTING_CONTEXT)
                .context(builder)
                .flags(CategoryFlags.PAGE_TOP_CONTEXT, CategoryFlags.PAGE_CONTEXT);
    }

    // ======================================================
    // CategorySpec: single class that supports 1..N categories
    // ======================================================

    public static final class CategorySpec {
        private final ExecutionDictionary dict;
        private final List<String> categories;

        private CategorySpec(ExecutionDictionary dict, List<String> categories) {
            this.dict = dict;
            this.categories = List.copyOf(categories);
        }

        // --- parent -> children convenience ---
        public CategorySpec children(String... childCategories) {
            dict.registerParentOfCategories(parent(), childCategories);
            return this;
        }

        public CategorySpec addBase(String xpath) {
            return addBase(XPathy.from(xpath));
        }

        public CategorySpec addBase(XPathy xpath) {
            Objects.requireNonNull(xpath, "xpath must not be null");

            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                dict.registerBaseXpath(cat, xpath);
            }
            return this;
        }


        // --- builders ---
        public CategorySpec or(Builder... builders) {
            dict.registerOrForCategories(categories, builders);
            return this;
        }

        public CategorySpec and(Builder... builders) {
            dict.registerAndForCategories(categories, builders);
            return this;
        }

        /**
         * Register the resolved XPathy of one or more other categories as OR-components
         * for the category(ies) this spec is defining.
         *
         * Example:
         *   category("newcategory").orCategories("CatA", "CatB");
         *
         * At runtime, resolving "newcategory" will include the OR parts from:
         *   andThenOr("CatA", v, op) and andThenOr("CatB", v, op)
         */
        public CategorySpec orCategories(String... categoryNames) {
            Objects.requireNonNull(categoryNames, "categoryNames must not be null");
            if (categoryNames.length == 0) return this;

            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                for (String ref : categoryNames) {
                    if (ref == null || ref.isBlank()) continue;
                    if (ref.equals(host)) continue; // avoid trivial self recursion

                    dict.registerOrBuilder(host, (ignoredCategory, v, op) -> dict.andThenOr(ref, v, op));
                }
            }
            return this;
        }

        /**
         * Register the resolved XPathy of one or more other categories as AND-components
         * for the category(ies) this spec is defining.
         *
         * Example:
         *   category("newcategory").andCategories("CatA", "CatB");
         *
         * At runtime, resolving "newcategory" will include the AND parts from:
         *   andThenOr("CatA", v, op) and andThenOr("CatB", v, op)
         */
        public CategorySpec andCategories(String... categoryNames) {
            Objects.requireNonNull(categoryNames, "categoryNames must not be null");
            if (categoryNames.length == 0) return this;

            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                for (String ref : categoryNames) {
                    if (ref == null || ref.isBlank()) continue;
                    if (ref.equals(host)) continue; // avoid trivial self recursion

                    dict.registerAndBuilder(host, (ignoredCategory, v, op) -> dict.andThenOr(ref, v, op));
                }
            }
            return this;
        }


        // Add these two methods inside ExecutionDictionary.CategorySpec
// (no other changes required)

        public CategorySpec orAllCategories(String... categoryNames) {
            Objects.requireNonNull(categoryNames, "categoryNames must not be null");
            if (categoryNames.length == 0) return this;

            // Register ONE OR builder per host category that, at runtime,
            // resolves referenced categories and AND-combines them into a single XPathy.
            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                dict.registerOrBuilder(host, (ignoredCategory, v, op) -> {
                    // Collect resolved XPathy from each referenced category
                    List<XPathy> parts = new ArrayList<>();
                    for (String ref : categoryNames) {
                        if (ref == null || ref.isBlank()) continue;
                        if (ref.equals(host)) continue; // avoid trivial self recursion

                        XPathy x = dict.andThenOr(ref, v, op);
                        if (x != null) parts.add(x);
                    }

                    // Combine them into ONE "and" XPathy, return it as a single OR component
                    return dict.combine(parts, "and").orElse(null);
                });
            }
            return this;
        }

        public CategorySpec andAnyCategories(String... categoryNames) {
            Objects.requireNonNull(categoryNames, "categoryNames must not be null");
            if (categoryNames.length == 0) return this;

            // Register ONE AND builder per host category that, at runtime,
            // resolves referenced categories and OR-combines them into a single XPathy.
            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                dict.registerAndBuilder(host, (ignoredCategory, v, op) -> {
                    // Collect resolved XPathy from each referenced category
                    List<XPathy> parts = new ArrayList<>();
                    for (String ref : categoryNames) {
                        if (ref == null || ref.isBlank()) continue;
                        if (ref.equals(host)) continue; // avoid trivial self recursion

                        XPathy x = dict.andThenOr(ref, v, op);
                        if (x != null) parts.add(x);
                    }

                    // Combine them into ONE "or" XPathy, return it as a single AND component
                    return dict.combine(parts, "or").orElse(null);
                });
            }
            return this;
        }


        // --- inheritance (single canonical method; alias kept) ---
        public CategorySpec inheritsFrom(String... parents) {
            for (String cat : categories) dict.registerCategoryInheritance(cat, parents);
            return this;
        }

        public CategorySpec inheritFrom(String... parents) {
            return inheritsFrom(parents);
        }

        // --- flags (single canonical method; alias kept) ---
        public CategorySpec flags(CategoryFlags... flags) {
            for (String cat : categories) dict.addCategoryFlags(cat, flags);
            return this;
        }

        public CategorySpec addCategoryFlags(CategoryFlags... flags) {
            return flags(flags);
        }

        // --- context ---
        public CategorySpec context(ContextBuilder builder) {
            for (String cat : categories) dict.registerContextBuilder(cat, builder);
            return flags(CategoryFlags.PAGE_CONTEXT);
        }

        public CategorySpec startingContext(ContextBuilder builder) {
            for (String cat : categories) dict.registerContextBuilder(cat, builder);
            return flags(CategoryFlags.PAGE_CONTEXT, CategoryFlags.PAGE_TOP_CONTEXT);
        }

        // --- info ---
        public List<String> names() {
            return categories;
        }

        public Set<CategoryFlags> getResolvedFlags() {
            EnumSet<CategoryFlags> result = EnumSet.noneOf(CategoryFlags.class);
            for (String cat : categories) result.addAll(dict.getResolvedCategoryFlags(cat));
            return result.isEmpty() ? Set.of() : EnumSet.copyOf(result);
        }

        private String parent() {
            return categories.get(0);
        }
    }

    // ========================================================
    // Debug / printing
    // ========================================================

    public void printDefinitions(String category, ValueWrapper value, Op op) {
        System.out.println("======================================");
        System.out.println(" ExecutionDictionary Definitions");
        System.out.println(" Instance: " + this);
        System.out.println(" Args -> category='" + category + "', value='" + value + "', op=" + op);
        System.out.println("======================================");

        System.out.println("\n--- OR Builders ---");
        if (orReg.isEmpty()) {
            System.out.println("(none)");
        } else {
            orReg.forEach((cat, builders) -> {
                System.out.println("Category: " + cat);
                for (int i = 0; i < builders.size(); i++) {
                    Builder b = builders.get(i);
                    XPathy result = safeInvoke(b, category, value, op);
                    System.out.println("  OR[" + i + "] -> " + safeXpath(result));
                }
            });
        }

        System.out.println("\n--- AND Builders ---");
        if (andReg.isEmpty()) {
            System.out.println("(none)");
        } else {
            andReg.forEach((cat, builders) -> {
                System.out.println("Category: " + cat);
                for (int i = 0; i < builders.size(); i++) {
                    Builder b = builders.get(i);
                    XPathy result = safeInvoke(b, category, value, op);
                    System.out.println("  AND[" + i + "] -> " + safeXpath(result));
                }
            });
        }

        System.out.println("\n--- Category Inheritance ---");
        if (categoryParents.isEmpty()) {
            System.out.println("(none)");
        } else {
            categoryParents.asMap().forEach((child, parents) ->
                    System.out.println("Child: " + child + " inherits from -> " + parents)
            );
        }

        System.out.println("\n======================================");
    }

    private XPathy safeInvoke(Builder builder, String category, ValueWrapper value, Op op) {
        try {
            return builder.build(category, value, op);
        } catch (Exception e) {
            return XPathy.from("//ERROR[" + e.getClass().getSimpleName() + "]");
        }
    }

    // ========================================================
    // Category flags registration / resolution
    // ========================================================

    private void addCategoryFlags(String category, CategoryFlags... flags) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(flags, "flags must not be null");
        if (flags.length == 0) return;

        var set = categoryFlags.computeIfAbsent(category, k -> ConcurrentHashMap.<CategoryFlags>newKeySet());
        for (CategoryFlags f : flags) set.add(Objects.requireNonNull(f, "CategoryFlag must not be null"));
    }

    public Set<CategoryFlags> getResolvedCategoryFlags(String category) {
        if (category == null || category.isBlank()) return new HashSet<>();
        List<String> lineage = resolveCategoryLineage(category);
        if (lineage.isEmpty()) return Set.of();

        EnumSet<CategoryFlags> result = EnumSet.noneOf(CategoryFlags.class);
        for (String catKey : lineage) {
            var set = categoryFlags.get(catKey);
            if (set != null && !set.isEmpty()) result.addAll(set);
        }
        return result.isEmpty() ? Set.of() : EnumSet.copyOf(result);
    }

    // ========================================================
    // SearchContext builders (optional override per category)
    // ========================================================

    public void registerContextBuilder(String category, ContextBuilder builder) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builder, "builder must not be null");
        contextReg.put(category, builder);
    }

    public SearchContext applyContextBuilder(
            String category,
            ValueWrapper value,
            Op op,
            WebDriver webDriver,
            SearchContext context
    ) {
        Objects.requireNonNull(category, "category must not be null");
        ContextBuilder cb = contextReg.get(category);

        if (cb == null) throw new RuntimeException("category '" + category + "' does not have a registered context");

        try {
            return cb.build(category, value, op, webDriver, context);
        } catch (Throwable t) {
            System.out.println("Could not return SearchContext for '" + category + "'");
            throw new RuntimeException("Could not return SearchContext for '" + category + "'", t);
        }
    }

    // ========================================================
    // Convenience registrations for iframe/shadow-root categories
    // ========================================================

    public CategorySpec registerTopLevelIframe(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames)
                .flags(CategoryFlags.IFRAME)
                .startingContext((category, value, op, driver, context) -> {
                    driver.switchTo().defaultContent();

                    XPathy xpathy = andThenOr(category, value, op);
                    if (xpathy == null) return driver;

                    WebElement frameEl = driver.findElement(By.xpath(xpathy.getXpath()));
                    driver.switchTo().frame(frameEl);
                    return driver;
                });
    }

    public CategorySpec registerIframe(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames)
                .flags(CategoryFlags.IFRAME)
                .context((category, value, op, driver, context) -> {
                    XPathy xpathy = andThenOr(category, value, op);
                    if (xpathy == null) return context;

                    WebElement frameEl = context.findElement(By.xpath(xpathy.getXpath()));
                    driver.switchTo().frame(frameEl);
                    return driver;
                });
    }

    public CategorySpec registerTopLevelShadowRoot(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames)
                .flags(CategoryFlags.SHADOW_HOST)
                .startingContext((category, value, op, driver, context) -> {
                    driver.switchTo().defaultContent();

                    XPathy xpathy = andThenOr(category, value, op);
                    if (xpathy == null) return driver;

                    WebElement host = driver.findElement(By.xpath(xpathy.getXpath()));
                    return host.getShadowRoot();
                });
    }

    public CategorySpec registerShadowRoot(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames)
                .flags(CategoryFlags.SHADOW_HOST)
                .context((category, value, op, driver, context) -> {
                    XPathy xpathy = andThenOr(category, value, op);
                    if (xpathy == null) return context;

                    WebElement host = context.findElement(By.xpath(xpathy.getXpath()));
                    return host.getShadowRoot();
                });
    }
}
