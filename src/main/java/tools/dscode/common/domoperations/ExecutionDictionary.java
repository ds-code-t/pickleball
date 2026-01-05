package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.xpathy.Tag.any;
//import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.toSelfStep;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.xpathSpecificityScore;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.maybeDeepestMatches;

public class ExecutionDictionary {

    @FunctionalInterface
    public interface Builder {
        XPathy build(String category, ValueWrapper value, Op op);
    }

    @FunctionalInterface
    public interface ContextBuilder {
        SearchContext build(String category, ValueWrapper value, Op op,  WebDriver driver, SearchContext context);
    }


    // Default base category that *every* category implicitly inherits from
    public static final String CONTAINS_TEXT = "ContainsTextInternalUSE";

    public static final String BASE_CATEGORY = "BaseCategoryInternalUSE";

    public static final String STARTING_CONTEXT = "DefaultStartingContextInternalUSE";

    public static final String VISIBILITY_FILTER = "VisibilityFilterInternalUSE";

    public enum Op {DEFAULT, EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, GT, GTE, LT, LTE}

    public enum CategoryFlags {PAGE_CONTEXT, PAGE_TOP_CONTEXT, ELEMENT_CONTEXT, SHADOW_HOST, IFRAME}


    //========================================================
    // Instance state
    //========================================================


    // childCategory -> [parentCategory1, parentCategory2, ...]
    private final Multimap<String, String> categoryParents =
            ArrayListMultimap.create();

    private final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> orReg =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> andReg =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ContextBuilder> contextReg =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Set<CategoryFlags>> categoryFlags =
            new ConcurrentHashMap<>();


    //========================================================
    // Construction / registration hook
    //========================================================

    protected ExecutionDictionary() {
        // Subclasses override register() to populate this dictionary.
        // This will be called during construction.
        register();
        defaultRegistrations();
    }

    /**
     * Override in anonymous subclasses to register builders, category inheritance, html types, etc.
     */
    protected void register(){};

    private void defaultRegistrations() {
//        registerDefaultStartingContext((category, v, op, ctx) -> {

//            return wrapContext(((WebDriver)ctx).switchTo().defaultContent());
//        });

        category("Text").inheritsFrom(CONTAINS_TEXT);

        category(CONTAINS_TEXT)
                .and(
                        (category, v, op) -> {
                            if (v == null || v.isNullOrBlank())
                                return null;
                            return any.byHaving(
                                    maybeDeepestMatches(XPathy.from("descendant-or-self::*")
                                            .byHaving(deepNormalizedText(v, op)))
                            );
                        }
                );
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
    }

    /**
     * Convenience method: declare that MANY categories inherit from ONE parent.
     * <p>
     * Example:
     * registerParentOfCategories("Clickable", "Button", "Link", "MenuItem");
     */
    public void registerParentOfCategories(String parentCategory, String... childCategories) {
        Objects.requireNonNull(parentCategory, "parentCategory must not be null");
        Objects.requireNonNull(childCategories, "childCategories must not be null");


        for (String child : childCategories) {
            if (child == null) continue;
            if (child.equals(parentCategory)) continue; // avoid trivial cycle

            registerCategoryInheritance(child, parentCategory);
        }
    }

    /**
     * Resolve full inheritance chain for a category.
     */
    private List<String> resolveCategoryLineage(String category) {
        if (category == null || category.isBlank())
            return new ArrayList<>();


        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(category);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!ordered.add(current)) {
                continue; // already visited
            }

            Collection<String> parents = categoryParents.get(current);
            if (parents != null) {
                for (String p : parents) {
                    if (p != null && !p.equals(current)) {
                        stack.push(p);
                    }
                }
            }
        }


        List<String> lineage = new ArrayList<>(ordered);

        return lineage;
    }


    // ========================================================
    // OR / AND builder registries
    // ========================================================

    // --- OR builders ---

    /**
     * Register one or more OR-based builders for a single category.
     */
    public void registerOrBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) {
            return;
        }


        var list = orReg.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) {
            list.add(Objects.requireNonNull(b, "builder must not be null"));
        }
    }

    /**
     * Convenience: register the same OR-builders for multiple categories.
     */
    public void registerOrForCategories(List<String> categories, Builder... builders) {


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
     */
    public void registerAndBuilder(String category, Builder... builders) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builders, "builders must not be null");
        if (builders.length == 0) {
            return;
        }


        var list = andReg.computeIfAbsent(category, k -> new CopyOnWriteArrayList<>());
        for (Builder b : builders) {
            list.add(Objects.requireNonNull(b, "builder must not be null"));
        }
    }

    /**
     * Convenience: register the same AND-builders for multiple categories.
     */
    public void registerAndForCategories(List<String> categories, Builder... builders) {
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

    public boolean categoryHasRegistration(String category) {
        Objects.requireNonNull(category, "category must not be null");
        List<String> lineage = resolveCategoryLineage(category);
        return hasAnyRegisteredBuilders(lineage);
    }

    //========================================================
    // Expansion with inheritance
    //========================================================

    /**
     * Core expansion that honors inheritance + "*" fallback.
     */
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
            if (builders != null) {

                if (!builders.isEmpty()) {
                    allBuilders.addAll(builders);
                }
            }
        }


        // 2) If nothing found yet in THIS map, decide whether to fallback to "*"
        if (allBuilders.isEmpty()) {
            // Only use "*" fallback if there are NO AND or OR builders
            // anywhere in the non-base lineage.

            if (!hasAnyRegisteredBuilders(lineage)) {
                var starList = map.get("*");

                if (starList == null || starList.isEmpty()) {

                    return List.of();
                }

                allBuilders.addAll(starList);
            } else {
                // There ARE builders in the other map (AND vs OR), so we skip "*"
                return List.of();
            }
        }


        // 4) Build XPaths
        List<XPathy> result = allBuilders.stream()
                .map(b -> b.build(category, value, op))
          .filter(Objects::nonNull)
                .toList();


        return result;
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
        return Stream.concat(expandInternal(andReg, category, value, op).stream(), expandInternal(andReg, BASE_CATEGORY, value, op).stream())
                .collect(Collectors.toList());
    }

    //========================================================
    // String-based combination helpers
    //========================================================

    /**
     * Combine a list of XPathy into a single XPathy using "and"/"or".
     */
    private Optional<XPathy> combine(List<XPathy> list, String joiner) {
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }

        // Filter FIRST (this is the critical fix)
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

        // If everything was null/blank -> treat as "no expression"
        if (filtered.isEmpty()) {
            return Optional.empty();
        }

        // Defensive copy so we don't mutate caller's list
        List<XPathy> sorted = new ArrayList<>(filtered);

        // Sort by heuristic specificity score (lower is "better")
        sorted.sort(Comparator.comparingInt(x -> xpathSpecificityScore(x.getXpath())));

        // Build boolean expression over self::... steps
        String combinedExpr = sorted.stream()
                .map(XPathy::getXpath)
                .map(XPathyAssembly::toSelfStep)
                .filter(s -> s != null && !s.isBlank() && !s.equals("self::*[]"))
                .collect(Collectors.joining(" " + joiner + " "));


        // If somehow we still ended up empty, bail out
        if (combinedExpr.isBlank()) {
            return Optional.empty();
        }

        String fullXpath = "//*[" + combinedExpr + "]";
        return Optional.of(XPathy.from(fullXpath));
    }


    public Optional<XPathy> andAll(String category, ValueWrapper value, Op op) {

        Optional<XPathy> result = combine(expandAnd(category, value, op), "and");

        return result;
    }

    public Optional<XPathy> orAll(String category, ValueWrapper value, Op op) {

        Optional<XPathy> result = combine(expandOr(category, value, op), "or");

        return result;
    }

    /**
     * Combined filter (non-Optional version).
     * Delegates to resolveToXPathy and unwraps the result.
     */
    public XPathy andThenOr(String category, ValueWrapper value, Op op) {


        Optional<XPathy> result = resolveToXPathy(category, value, op);

        if (result.isEmpty()) {
            return null;  // preserve prior semantics
        }

        return result.get();
    }

    public XPathy getCategoryXPathy(String category) {
        return andThenOr(category, null, null);
    }

    public record CategoryResolution(String category, ValueWrapper value, Op op, XPathy xpath, Set<CategoryFlags> flags) {
    }

    /**
     * Convenience: call andThenOr (preserving its semantics, including null),
     * and return the result together with the resolved category flags.
     */
    public CategoryResolution andThenOrWithFlags(String category, ValueWrapper value, Op op) {

        XPathy xpath = andThenOr(category, value, op); // preserves old behavior, including null

        Set<CategoryFlags> flags = getResolvedCategoryFlags(category);

        return new CategoryResolution(category, value, op, xpath, flags);
    }



    /**
     * Optional-returning version of andThenOr.
     */
    public Optional<XPathy> resolveToXPathy(String category, ValueWrapper value, Op op) {

        Optional<XPathy> orPart = orAll(category, value, op);
        Optional<XPathy> andPart = andAll(category, value, op);



        if (andPart.isEmpty() && orPart.isEmpty()) {
            return Optional.empty();
        }
        if (andPart.isEmpty()) {
            return orPart;
        }
        if (orPart.isEmpty()) {
            return andPart;
        }

        String andStep =  XPathyAssembly.toSelfStep(andPart.get().getXpath());
        String orStep =  XPathyAssembly.toSelfStep(orPart.get().getXpath());




        String combinedExpr = "(" + andStep + ") and (" + orStep + ")";


        String fullXpath = "//*[" + combinedExpr + "]";

        XPathy x = XPathy.from(fullXpath);

        return Optional.of(x);
    }


    // Simple helper for counting characters (predicates, etc.)
    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    private boolean hasAnyRegisteredBuilders(List<String> lineage) {
        for (String catKey : lineage) {
            var orBuilders = orReg.get(catKey);
            if (orBuilders != null && !orBuilders.isEmpty()) {
                return true;
            }
            var andBuilders = andReg.get(catKey);
            if (andBuilders != null && !andBuilders.isEmpty()) {
                return true;
            }
        }
        return false;
    }


    /**
     * Start a fluent definition for a single category on this dictionary instance.
     */
    public CategorySpec category(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return new CategorySpec(this, List.of(name));
    }

    /**
     * Start a fluent definition for multiple categories at once on this dictionary instance.
     */
    public CategoriesSpec categories(String... names) {
        Objects.requireNonNull(names, "names must not be null");
        return new CategoriesSpec(this, Arrays.asList(names));
    }

    /**
     * Start a fluent definition for a parent category whose children inherit from it,
     * on this dictionary instance.
     */
    public ParentSpec parent(String parentName) {
        return new ParentSpec(this, parentName);
    }

    public CategorySpec registerDefaultStartingContext(ContextBuilder builder) {
        return category(STARTING_CONTEXT)
                .context(builder)
                .flags(CategoryFlags.PAGE_TOP_CONTEXT, CategoryFlags.PAGE_CONTEXT);
    }


    // ======================================================
    // CategorySpec: fluent config for single or multiple categories
    // ======================================================

    public static class CategorySpec {
        protected final ExecutionDictionary dict;
        protected final List<String> categories;

        private CategorySpec(ExecutionDictionary dict, List<String> categories) {
            this.dict = Objects.requireNonNull(dict, "dict must not be null");
            Objects.requireNonNull(categories, "categories must not be null");
            if (categories.isEmpty()) {
                throw new IllegalArgumentException("categories must not be empty");
            }
            // Make an unmodifiable defensive copy
            this.categories = List.copyOf(categories);
        }

        /**
         * Register OR-based builders for all categories in this spec on this dictionary instance.
         */
        public CategorySpec or(Builder... builders) {
            dict.registerOrForCategories(categories, builders);
            return this;
        }

        /**
         * Register AND-based builders for all categories in this spec on this dictionary instance.
         */
        public CategorySpec and(Builder... builders) {
            dict.registerAndForCategories(categories, builders);
            return this;
        }

        /**
         * Declare that all categories in this spec inherit from the given parents,
         * on this dictionary instance.
         */
        public CategorySpec inheritsFrom(String... parents) {
            Objects.requireNonNull(parents, "parents must not be null");
            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                dict.registerCategoryInheritance(cat, parents);
            }
            return this;
        }

        /**
         * Register one or more flags for all categories in this spec.
         */
        public CategorySpec flags(CategoryFlags... flags) {
            Objects.requireNonNull(flags, "flags must not be null");
            if (flags.length == 0) {
                return this;
            }
            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                dict.addCategoryFlags(cat, flags);
            }
            return this;
        }

        /**
         * Expose the category name.
         * Only valid if this spec represents exactly one category.
         */
        public String name() {
            if (categories.size() != 1) {
                throw new IllegalStateException(
                        "CategorySpec contains multiple categories: " + categories
                );
            }
            return categories.get(0);
        }

        /**
         * Register a SearchContext-based builder for all categories in this spec.
         * If present, callers can choose to ignore the XPathy builders.
         */
        public CategorySpec context(ContextBuilder builder) {
            Objects.requireNonNull(builder, "builder must not be null");
            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                dict.registerContextBuilder(cat, builder);
            }
            // Preserve original behavior: mark as PAGE_CONTEXT
            flags(CategoryFlags.PAGE_CONTEXT);
            return this;
        }

        /**
         * Register a "starting" SearchContext-based builder for all categories in this spec.
         * Adds both PAGE_CONTEXT and PAGE_TOP_CONTEXT flags.
         */
        public CategorySpec startingContext(ContextBuilder builder) {

            try {
                Objects.requireNonNull(builder, "builder must not be null");
                for (String cat : categories) {
                    if (cat == null || cat.isBlank()) continue;
                    dict.registerContextBuilder(cat, builder);
                }
                flags(CategoryFlags.PAGE_CONTEXT, CategoryFlags.PAGE_TOP_CONTEXT);

                return this;
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(
                        "Error registering startingContext::: " + categories,
                        e
                );
            }
        }

        /**
         * Return the resolved flags for all categories in this spec, honoring inheritance.
         * If there are multiple categories, this returns the union of their flags.
         */
        public Set<CategoryFlags> getResolvedFlags() {
            EnumSet<CategoryFlags> result = EnumSet.noneOf(CategoryFlags.class);
            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                result.addAll(dict.getResolvedCategoryFlags(cat));
            }
            return result.isEmpty() ? Set.of() : EnumSet.copyOf(result);
        }
    }


    // ======================================================
    // CategoriesSpec: fluent config for multiple categories
    // (thin wrapper around CategorySpec)
    // ======================================================

    public static final class CategoriesSpec extends CategorySpec {

        private CategoriesSpec(ExecutionDictionary dict, List<String> categories) {
            super(dict, categories);
        }

        /**
         * Register OR-based builders for all categories in this group,
         * returning CategoriesSpec for fluent chaining.
         */
        public CategoriesSpec or(Builder... builders) {
            super.or(builders);
            return this;
        }

        /**
         * Register AND-based builders for all categories in this group,
         * returning CategoriesSpec for fluent chaining.
         */
        public CategoriesSpec and(Builder... builders) {
            super.and(builders);
            return this;
        }

        /**
         * Declare that all categories in this group inherit from the given parents,
         * returning CategoriesSpec for fluent chaining.
         */
        public CategoriesSpec inheritFrom(String... parents) {
            super.inheritsFrom(parents);
            return this;
        }

        /**
         * Convenience for registering flags on all categories in this group,
         * returning CategoriesSpec for fluent chaining.
         */
        public CategoriesSpec addCategoryFlags(CategoryFlags... flags) {
            super.flags(flags);
            return this;
        }

        /**
         * Expose the underlying list of category names.
         */
        public List<String> names() {
            return categories;
        }
    }

    // ======================================================
    // ParentSpec: fluent config for parent → children inheritance
    // ======================================================

    public static final class ParentSpec {
        private final ExecutionDictionary dict;
        private final String parent;

        private ParentSpec(ExecutionDictionary dict, String parent) {
            this.dict = Objects.requireNonNull(dict, "dict must not be null");
            this.parent = Objects.requireNonNull(parent, "parent must not be null");
        }

        /**
         * Declare that the given child categories inherit from this parent,
         * on this dictionary instance.
         */
        public ParentSpec children(String... childCategories) {
            dict.registerParentOfCategories(parent, childCategories);
            return this;
        }

        /**
         * Expose the parent category name.
         */
        public String name() {
            return parent;
        }
    }


    public void printDefinitions(String category, ValueWrapper value, Op op) {
        System.out.println("======================================");
        System.out.println(" ExecutionDictionary Definitions");
        System.out.println(" Instance: " + this);
        System.out.println(" Args -> category='" + category + "', value='" + value + "', op=" + op);
        System.out.println("======================================");

        // ------ OR Builders ------
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

        // ------ AND Builders ------
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


        // ------ Category Inheritance ------
        System.out.println("\n--- Category Inheritance ---");
        if (categoryParents.isEmpty()) {
            System.out.println("(none)");
        } else {
            categoryParents.asMap().forEach((child, parents) -> {
                System.out.println("Child: " + child + " inherits from -> " + parents);
            });
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
        if (flags.length == 0) {
            return;
        }

        var set = categoryFlags.computeIfAbsent(
                category,
                k -> ConcurrentHashMap.<CategoryFlags>newKeySet()
        );
        for (CategoryFlags f : flags) {
            set.add(Objects.requireNonNull(f, "CategoryFlag must not be null"));
        }
    }

    /**
     * Resolve all flags for a category, honoring inheritance (just like the
     * builder resolution does).
     */
    public Set<CategoryFlags> getResolvedCategoryFlags(String category) {
        if (category == null || category.isBlank())
            return new HashSet<>();
        List<String> lineage = resolveCategoryLineage(category);
        if (lineage.isEmpty()) {
            return Set.of();
        }

        EnumSet<CategoryFlags> result = EnumSet.noneOf(CategoryFlags.class);
        for (String catKey : lineage) {
            var set = categoryFlags.get(catKey);
            if (set != null && !set.isEmpty()) {
                result.addAll(set);
            }
        }
        return result.isEmpty() ? Set.of() : EnumSet.copyOf(result);
    }

// ========================================================
// SearchContext builders (optional override per category)
// ========================================================

    public void registerContextBuilder(String category, ContextBuilder builder) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(builder, "builder must not be null");
        // Only one SearchContext builder per category; overwrite any existing

        contextReg.put(category, builder);
    }


    /**
     * Invoke the SearchContext builder for this category (if present).
     * If none is registered, returns the original context unchanged.
     */
    public SearchContext applyContextBuilder(
            String category,
            ValueWrapper value,
            Op op,
            WebDriver webDriver,
            SearchContext context
    ) {
        Objects.requireNonNull(category, "category must not be null");

        ContextBuilder cb = contextReg.get(category);

        if (cb == null) {
            throw new RuntimeException("category '" + category +"' does not have a registered context");
        }
        try {
            return cb.build(category, value, op, webDriver, context);
        }
        catch (Throwable t) {
            System.out.println("Could not return SearchContext for '" + category + "'");
            return null;
        }
    }






    /**
     * Registers a category that resolves to an iframe/frame element via the normal XPathy builders
     * (.and/.or), but is invoked using applyContextBuilder(...) because this method automatically
     * registers a ContextBuilder that:
     *
     * 1) switches driver to defaultContent()
     * 2) resolves XPathy using andThenOr(...)
     * 3) finds the iframe/frame element on the driver
     * 4) switches the driver into that frame
     * 5) returns the WebDriver (as SearchContext)
     *
     * Flags: PAGE_CONTEXT + PAGE_TOP_CONTEXT (same semantics as startingContext()).
     */
    public CategorySpec registerTopLevelIframe(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames).flags(CategoryFlags.IFRAME).startingContext((category, value, op, driver, context) -> {
            // 1) Always start from the top of the page context
            driver.switchTo().defaultContent();

            // 2) Resolve the iframe/frame XPath from the category’s normal AND/OR registrations
            XPathy xpathy = andThenOr(category, value, op);
            if (xpathy == null) {
                // Preserve prior semantics: nothing to do
                return driver;
            }

            // 3) Find the iframe/frame element in the top-level DOM
            WebElement frameEl = driver.findElement(By.xpath(xpathy.getXpath()));

            // 4) Switch into it and return driver
            driver.switchTo().frame(frameEl);
            return driver;
        });
    }

    /**
     * Registers a category that resolves to a nested iframe/frame element via the normal XPathy builders
     * (.and/.or), but is invoked using applyContextBuilder(...) because this method automatically
     * registers a ContextBuilder that:
     *
     * 1) resolves XPathy using andThenOr(...)
     * 2) finds the iframe/frame element within the provided SearchContext
     * 3) switches the driver into that frame
     * 4) returns the WebDriver (as SearchContext)
     *
     * Flags: PAGE_CONTEXT (same semantics as context()).
     */
    public CategorySpec registerIframe(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");
        return categories(categoryNames).flags(CategoryFlags.IFRAME).context((category, value, op, driver, context) -> {
            // 1) Resolve the iframe/frame XPath from the category’s normal AND/OR registrations
            XPathy xpathy = andThenOr(category, value, op);
            if (xpathy == null) {
                return context;
            }
                // 2) Find the iframe/frame element within the current SearchContext
            WebElement  frameEl = context.findElement(By.xpath(xpathy.getXpath()));
            // 3) Switch into it and return driver
            driver.switchTo().frame(frameEl);
            return driver;
        });
    }


    /**
     * Registers a category that resolves (via normal .and/.or XPathy builders) to a SHADOW HOST element
     * at the TOP of the page (defaultContent), and returns its shadow root as the new SearchContext.
     *
     * Flags: PAGE_CONTEXT + PAGE_TOP_CONTEXT (same semantics as startingContext()).
     */
    public CategorySpec registerTopLevelShadowRoot(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames).flags(CategoryFlags.SHADOW_HOST).startingContext((category, value, op, driver, context) -> {
            // Always start from top-level document
            driver.switchTo().defaultContent();

            // Resolve XPath to the shadow host
            XPathy xpathy = andThenOr(category, value, op);
            if (xpathy == null) {
                return driver; // preserve your "no-op" semantics
            }

            // Find shadow host at top-level
            WebElement host = driver.findElement(By.xpath(xpathy.getXpath()));

            // Return the shadow root as the new SearchContext
            SearchContext shadowRoot = host.getShadowRoot();
            return shadowRoot;
        });
    }

    /**
     * Registers a category that resolves (via normal .and/.or XPathy builders) to a SHADOW HOST element
     * within the CURRENT SearchContext, and returns its shadow root as the new SearchContext.
     *
     * Flags: PAGE_CONTEXT (same semantics as context()).
     */
    public CategorySpec registerShadowRoot(String... categoryNames) {
        Objects.requireNonNull(categoryNames, "categoryNames must not be null");

        return categories(categoryNames).flags(CategoryFlags.SHADOW_HOST).context((category, value, op, driver, context) -> {
            // Resolve XPath to the shadow host
            XPathy xpathy = andThenOr(category, value, op);
            if (xpathy == null) {
                return context; // no-op: keep current context
            }
            // Find shadow host within current context
            WebElement host = context.findElement(By.xpath(xpathy.getXpath()));

            // Return the shadow root as the new SearchContext
            SearchContext shadowRoot = host.getShadowRoot();
            return shadowRoot;
        });
    }



}
