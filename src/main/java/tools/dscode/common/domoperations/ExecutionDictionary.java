package tools.dscode.common.domoperations;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.xpathy.Attribute;
import com.xpathy.XPathy;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.xpathy.Attribute.href;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.src;
import static com.xpathy.Attribute.title;
import static com.xpathy.Tag.any;
import static tools.dscode.common.domoperations.TableColumnByHeaderXPath.matchCellsByHeader;
import static tools.dscode.common.reporting.logging.LogForwarder.stepError;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineOr;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.xpathSpecificityScore;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.colocatedDeepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.combineXPathParts;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.descendantDeepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.tryConvertToXPathy;

public class ExecutionDictionary {


    public static final String singleControlElementContainer =
            "       [" +
                    "   count(descendant-or-self::*[self::input or self::textarea or self::select][not(@type='hidden')]) = 1" +
                    "   and descendant-or-self::*[self::input or self::textarea or self::select][not(@type='hidden')]" +
                    "]";


    @FunctionalInterface
    public interface Builder {
        Object build(String category, ValueWrapper value, Op op);
    }

    @FunctionalInterface
    public interface ContextBuilder {
        SearchContext build(String category, ValueWrapper value, Op op, WebDriver driver, SearchContext context);
    }

    // Default base category that *every* category implicitly inherits from
    public static final String DIRECT_TEXT = "DirectTextInternalUSE";
    public static final String CONTAINS_TEXT = "ContainsTextInternalUSE";
    public static final String TEXT_CONTENT_OR_ATTRIBUTE = "MatchesTextOrAttributeInternalUSE";
    public static final String COLOCATED_TEXT = "ColocatedTextInternalUSE";
    public static final String HTML_NAME_ATTRIBUTES = "htmlNaming";
    public static final String BASE_CATEGORY = "BaseCategoryInternalUSE";
    public static final String STARTING_CONTEXT = "DefaultStartingContextInternalUSE";
    public static final String VISIBILITY_FILTER = "VisibilityFilterInternalUSE";


    public static final Builder htmlMatchBuilder  =  (category, v, op) -> {
        if (v == null)
            return null;
        ValueWrapper strippedValueWrapper = v.stripAllNonLetters();
        return combineOr(
                XPathyBuilder.build(any, id, strippedValueWrapper, op),
                XPathyBuilder.build(any, title, strippedValueWrapper, op),
                XPathyBuilder.build(any, name, strippedValueWrapper, op),
                XPathyBuilder.build(any, Attribute.custom("node_name"), strippedValueWrapper, op),
                XPathyBuilder.build(any, Attribute.custom("data-node-id"), strippedValueWrapper, op)
        );
    };

    public static final Builder SrcHrfMatchBuilder =  (category, v, op) -> {
        ValueWrapper strippedValue = v == null ? null : v.normalizeLowerCaseAndStripAllWhiteSpace();
        if (strippedValue == null)
            return null;
        return combineXPathParts(XPathyBuilder.build(any, src, strippedValue, op) , XPathyBuilder.build(any, href, strippedValue, op));
    };


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

    public enum CategoryFlags {PAGE_CONTEXT, PAGE_TOP_CONTEXT, ELEMENT_CONTEXT, DATA_CONTEXT, SHADOW_HOST, IFRAME, NON_DISPLAY_ELEMENT, NO_NESTING_FILTER, OUTER_NESTING_FILTER}

    //========================================================
    // Instance state
    //========================================================

    // childCategory -> [parentCategory1, parentCategory2, ...]
    private final Multimap<String, String> categoryParents = ArrayListMultimap.create();

    private final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> orReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> andReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ContextBuilder> contextReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<CategoryFlags>> categoryFlags = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, XPathy> primaryBaseReg = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, XPathy> alternateBaseReg = new ConcurrentHashMap<>();

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



        category("Text").children("Texts").inheritsFrom(CONTAINS_TEXT).
                and(
                        (category, v, op) ->
                        {
                            if (v == null || v.isNull())
                                return null;
//                                return XPathy.from("//*[ancestor-or-self::body and descendant::text()]");
                            return XPathy.from("//*[count(descendant::node()) <= 12]");
                        }
                );

        category(COLOCATED_TEXT).and((category, v, op) -> {
            if (v == null || v.isNull())
                return null;
            return XPathy.from("//*" + colocatedDeepNormalizedVisibleText(v, op));
        });

        category(CONTAINS_TEXT).and((category, v, op) -> {
            if (v == null || v.isNull())
                return null;
            return XPathy.from("//*" + descendantDeepNormalizedVisibleText(v, op));
        });

        category(TEXT_CONTENT_OR_ATTRIBUTE).or(
                (category, v, op) -> {
            if (v == null || v.isNull())
                return null;
            return XPathy.from("//*" + descendantDeepNormalizedVisibleText(v, op));
        },
                (category, v, op) -> {
                    if (v == null || v.isNull())
                        return null;
                    return  getXPathFromBuilder(htmlMatchBuilder, category, v, op) + "[normalize-space(.) = '']";
                }
        );

        category(DIRECT_TEXT).and((category, v, op) -> {
            if (v == null || v.isNull())
                return null;
            return XPathy.from("//*" + deepNormalizedVisibleText(v, op));
        });
    }

    public XPathy cellsInColumnByHeaderText(
            ValueWrapper v,
            ExecutionDictionary.Op op,
            String customRowSuffixPredicate,
            String customCellSuffixPredicate,
            String customHeaderSuffixPredicate
    ) {
        if (v == null) throw new IllegalArgumentException("ValueWrapper v must not be null");
        if (op == null) throw new IllegalArgumentException("Op op must not be null");

        // Assumes your framework returns a BRACKETED predicate like:
        //   "[normalize-space(.)='Status']"
        // or similar XPath 1.0-compatible predicate.
        final String headerTextPred = deepNormalizedVisibleText(v, op);

        return matchCellsByHeader(
                headerTextPred,
                customRowSuffixPredicate,
                customCellSuffixPredicate,
                customHeaderSuffixPredicate
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

    private void registerPrimaryBaseXpath(String category, XPathy base) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(base, "base xpath must not be null");
        primaryBaseReg.put(category, base);
    }

    private void registerAlternateBaseXpath(String category, XPathy base) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(base, "base xpath must not be null");
        alternateBaseReg.put(category, base);
    }

    private XPathy getPrimaryBaseXpath(String category) {
        if (category == null || category.isBlank()) return null;
        return primaryBaseReg.get(category);
    }

    private XPathy getAlternateBaseXpath(String category) {
        if (category == null || category.isBlank()) return null;
        return alternateBaseReg.get(category);
    }

    private XPathy getResolvedPrimaryBaseXpath(String category) {
        if (category == null || category.isBlank()) return null;

        List<String> lineage = resolveCategoryLineage(category);
        for (String catKey : lineage) {
            XPathy base = primaryBaseReg.get(catKey);
            if (base != null) return base;
        }
        return null;
    }

    private Builder constantBuilder(XPathy xpath) {
        return (ignoredCategory, ignoredValue, ignoredOp) -> xpath;
    }

    private boolean hasLocalBase(String category) {
        return getPrimaryBaseXpath(category) != null || getAlternateBaseXpath(category) != null;
    }

    private enum BaseExpansionMode {
        NONE,
        NORMAL,
        FINAL_XPATH
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


    //========================================================
    // Expansion with inheritance
    //========================================================



    private List<XPathy> expandInternal(
            ConcurrentMap<String, CopyOnWriteArrayList<Builder>> map,
            String category,
            ValueWrapper value,
            Op op,
            BaseExpansionMode baseMode
    ) {
        List<String> lineage = resolveCategoryLineage(category);

        XPathy resolvedPrimaryBase =
                (map == andReg && baseMode == BaseExpansionMode.FINAL_XPATH)
                        ? getResolvedPrimaryBaseXpath(category)
                        : null;

        List<Builder> allBuilders = new ArrayList<>();
        for (String catKey : lineage) {
            var builders = map.get(catKey);
            if (builders != null && !builders.isEmpty()) {
                allBuilders.addAll(builders);
            }

            if (map == andReg) {
                XPathy baseToAdd = null;

                switch (baseMode) {
                    case NORMAL -> {
                        XPathy alt = getAlternateBaseXpath(catKey);
                        XPathy primary = getPrimaryBaseXpath(catKey);
                        baseToAdd = (alt != null) ? alt : primary;
                    }
                    case FINAL_XPATH -> {
                        if (resolvedPrimaryBase == null) {
                            baseToAdd = getAlternateBaseXpath(catKey);
                        }
                    }
                    case NONE -> {
                        // no-op
                    }
                }

                if (baseToAdd != null) {
                    allBuilders.add(constantBuilder(baseToAdd));
                }
            }
        }

        if (allBuilders.isEmpty()) {
            if (category.equals(BASE_CATEGORY)) {
                return List.of();
            }
            if (!hasAnyRegisteredBuilders(lineage) && !hasResolvedBase(lineage)) {
                var starList = map.get("*");
                if (starList == null || starList.isEmpty()) return List.of();
                allBuilders.addAll(starList);
            } else {
                return List.of();
            }
        }

        return allBuilders.stream()
                .map(b -> tryConvertToXPathy(b.build(category, value, op)))
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
        return expandInternal(orReg, category, value, op, BaseExpansionMode.NONE);
    }

    public List<XPathy> expandAnd(String category, ValueWrapper value, Op op) {
        return Stream.concat(
                        expandInternal(andReg, category, value, op, BaseExpansionMode.NORMAL).stream(),
                        expandInternal(andReg, BASE_CATEGORY, value, op, BaseExpansionMode.NONE).stream()
                )
                .collect(Collectors.toList());
    }

    private List<XPathy> expandAndForFinalXPath(String category, ValueWrapper value, Op op) {
        return Stream.concat(
                        expandInternal(andReg, category, value, op, BaseExpansionMode.FINAL_XPATH).stream(),
                        expandInternal(andReg, BASE_CATEGORY, value, op, BaseExpansionMode.NONE).stream()
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

    private Optional<XPathy> andAllForFinalXPath(String category, ValueWrapper value, Op op) {
        return combine(expandAndForFinalXPath(category, value, op), "and");
    }


    public Optional<XPathy> orAll(String category, ValueWrapper value, Op op) {
        return combine(expandOr(category, value, op), "or");
    }

    public XPathy andThenOr(String category, ValueWrapper value, Op op) {
        return resolveToXPathy(category, value, op).orElse(null);
    }

    public XPathy resolveFinalXPath(String category, ValueWrapper value, Op op) {
        return resolveToFinalXPathy(category, value, op).orElse(null);
    }

    public XPathy getCategoryXPathy(String category) {
        return andThenOr(category, null, null);
    }

    public XPathy getFinalCategoryXPathy(String category) {
        return resolveFinalXPath(category, null, null);
    }

    public record CategoryResolution(String category, ValueWrapper value, Op op, XPathy xpath,
                                     Set<CategoryFlags> flags) {
    }

    public CategoryResolution andThenOrWithFlags(String category, ValueWrapper value, Op op) {
        XPathy xpath = andThenOr(category, value, op);
        Set<CategoryFlags> flags = getResolvedCategoryFlags(category);
        return new CategoryResolution(category, value, op, xpath, flags);
    }

    private Optional<XPathy> resolveFromParts(
            Optional<XPathy> andPart,
            Optional<XPathy> orPart,
            String prefix,
            boolean returnPrefixIfNoParts
    ) {
        if (andPart.isEmpty() && orPart.isEmpty()) {
            if (returnPrefixIfNoParts && prefix != null && !prefix.isBlank()) {
                return Optional.of(XPathy.from(prefix.trim()));
            }
            return Optional.empty();
        }

        String andStep = andPart.map(xPathy -> XPathyAssembly.toSelfStep(xPathy.getXpath())).orElse(null);
        String orStep = orPart.map(xPathy -> XPathyAssembly.toSelfStep(xPathy.getXpath())).orElse(null);

        record Step(String step, int score) {
        }

        List<Step> steps = new ArrayList<>(2);
        if (andStep != null && !andStep.isBlank()) {
            steps.add(new Step(andStep, xpathSpecificityScore(andPart.get().getXpath())));
        }
        if (orStep != null && !orStep.isBlank()) {
            steps.add(new Step(orStep, xpathSpecificityScore(orPart.get().getXpath())));
        }

        if (steps.isEmpty()) {
            if (returnPrefixIfNoParts && prefix != null && !prefix.isBlank()) {
                return Optional.of(XPathy.from(prefix.trim()));
            }
            return Optional.empty();
        }

        steps.sort(Comparator.comparingInt(Step::score));
        String combined = steps.stream()
                .map(Step::step)
                .map(s -> "(" + s + ")")
                .collect(Collectors.joining(" and "));

        String resolvedPrefix = (prefix == null || prefix.isBlank()) ? "//*" : prefix.trim();
        return Optional.of(XPathy.from(resolvedPrefix + "[" + combined + "]"));
    }

    public Optional<XPathy> resolveToXPathy(String category, ValueWrapper value, Op op) {
        Optional<XPathy> orPart = orAll(category, value, op);
        Optional<XPathy> andPart = andAll(category, value, op);
        return resolveFromParts(andPart, orPart, null, false);
    }

    public Optional<XPathy> resolveToFinalXPathy(String category, ValueWrapper value, Op op) {
        XPathy resolvedPrimaryBase = getResolvedPrimaryBaseXpath(category);
        Optional<XPathy> orPart = orAll(category, value, op);
        Optional<XPathy> andPart = andAllForFinalXPath(category, value, op);

        String prefix = resolvedPrimaryBase == null ? "//*" : resolvedPrimaryBase.getXpath();
        return resolveFromParts(andPart, orPart, prefix, resolvedPrimaryBase != null);
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

    private boolean hasResolvedBase(List<String> lineage) {
        for (String catKey : lineage) {
            if (hasLocalBase(catKey)) return true;
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
                dict.registerPrimaryBaseXpath(cat, xpath);
            }
            return this;
        }

        public CategorySpec addAlternateBase(String xpath) {
            return addAlternateBase(XPathy.from(xpath));
        }

        public CategorySpec addAlternateBase(XPathy xpath) {
            Objects.requireNonNull(xpath, "xpath must not be null");

            for (String cat : categories) {
                if (cat == null || cat.isBlank()) continue;
                dict.registerAlternateBaseXpath(cat, xpath);
            }
            return this;
        }


        // --- builders ---
// --- builders ---
        public CategorySpec or(Builder... builders) {
            dict.registerOrForCategories(categories, builders);
            return this;
        }

        public CategorySpec and(Builder... builders) {
            dict.registerAndForCategories(categories, builders);
            return this;
        }

        public CategorySpec or(Object... args) {
            Builder[] builders = dict.adaptToBuilders(args);
            if (builders.length > 0) {
                dict.registerOrForCategories(categories, builders);
            }
            return this;
        }

        public CategorySpec and(Object... args) {
            Builder[] builders = dict.adaptToBuilders(args);
            if (builders.length > 0) {
                dict.registerAndForCategories(categories, builders);
            }
            return this;
        }


        public CategorySpec orCategories(Object... categoryNames) {
            if (categoryNames.length == 0) return this;

            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                for (Object ref : categoryNames) {
                    if (ref instanceof String s) {
                        if (s.isBlank()) continue;
                        if (s.equals(host)) continue;

                        dict.registerOrBuilder(host, (ignoredCategory, v, op) -> dict.andThenOr(s, v, op));
                    } else if (ref instanceof Builder b) {
                        dict.registerOrBuilder(host, b);
                    }
                }
            }
            return this;
        }

        public CategorySpec andCategories(Object... categoryNames) {
            if (categoryNames.length == 0) return this;

            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                for (Object ref : categoryNames) {
                    if (ref instanceof String s) {
                        if (s.isBlank()) continue;
                        if (s.equals(host)) continue;

                        dict.registerAndBuilder(host, (ignoredCategory, v, op) -> dict.andThenOr(s, v, op));
                    } else if (ref instanceof Builder b) {
                        dict.registerAndBuilder(host, b);
                    }
                }
            }
            return this;
        }

        public CategorySpec orAllCategories(Object... categoryNames) {
            if (categoryNames.length == 0) return this;

            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                dict.registerOrBuilder(host, (ignoredCategory, v, op) -> {
                    List<XPathy> parts = new ArrayList<>();

                    for (Object ref : categoryNames) {
                        if (ref instanceof String s) {
                            if (s.isBlank()) continue;
                            if (s.equals(host)) continue;

                            XPathy x = dict.andThenOr(s, v, op);
                            if (x != null) parts.add(x);
                        } else if (ref instanceof Builder b) {
                            XPathy x = tryConvertToXPathy(b.build(ignoredCategory, v, op));
                            if (x != null) parts.add(x);
                        }
                    }

                    return dict.combine(parts, "and").orElse(null);
                });
            }
            return this;
        }

        public CategorySpec andAnyCategories(Object... categoryNames) {
            if (categoryNames.length == 0) return this;

            for (String host : categories) {
                if (host == null || host.isBlank()) continue;

                dict.registerAndBuilder(host, (ignoredCategory, v, op) -> {
                    List<XPathy> parts = new ArrayList<>();

                    for (Object ref : categoryNames) {
                        if (ref instanceof String s) {
                            if (s.isBlank()) continue;
                            if (s.equals(host)) continue;

                            XPathy x = dict.andThenOr(s, v, op);
                            if (x != null) parts.add(x);
                        } else if (ref instanceof Builder b) {
                            XPathy x = tryConvertToXPathy(b.build(ignoredCategory, v, op));
                            if (x != null) parts.add(x);
                        }
                    }

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
                    XPathy result = tryConvertToXPathy(b.build(category, value, op));
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
                    XPathy result = tryConvertToXPathy(b.build(category, value, op));
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
            stepError("Could not return SearchContext for '" + category + "'");
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

                    XPathy xpathy = resolveFinalXPath(category, value, op);
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
                    XPathy xpathy = resolveFinalXPath(category, value, op);
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

                    XPathy xpathy = resolveFinalXPath(category, value, op);
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
                    XPathy xpathy = resolveFinalXPath(category, value, op);
                    if (xpathy == null) return context;

                    WebElement host = context.findElement(By.xpath(xpathy.getXpath()));
                    return host.getShadowRoot();
                });
    }

    //builder helpers

    private Builder adaptToBuilder(Object arg) {
        if (arg == null) {
            return null;
        }

        if (arg instanceof Builder builder) {
            return builder;
        }

        XPathy constantXPath = tryConvertToXPathy(arg);
        return (category, value, op) -> constantXPath;
    }

    private Builder[] adaptToBuilders(Object... args) {
        if (args == null || args.length == 0) {
            return new Builder[0];
        }

        List<Builder> result = new ArrayList<>();
        for (Object arg : args) {
            Builder builder = adaptToBuilder(arg);
            if (builder != null) {
                result.add(builder);
            }
        }

        return result.toArray(new Builder[0]);
    }

    public static String getXPathFromBuilder(Builder builder, String category, ValueWrapper value, Op op) {
        Object xpath = builder.build(category, value, op);
        if (xpath == null) return null;
        if (xpath instanceof XPathy) return ((XPathy)xpath).getXpath();
        return xpath.toString();
    }
}
