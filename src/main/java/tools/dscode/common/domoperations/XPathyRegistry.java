package tools.dscode.common.domoperations;

import com.xpathy.XPathy;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

// Minimal, threadsafe registry for: (category, value, op) -> XPathy
public final class XPathyRegistry {
    private XPathyRegistry() {}

    // Updated functional type: (String category, Object value, Op op) -> XPathy
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

    public static void addHtmlTypes(String category, HtmlType... htmlTypes) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(htmlTypes, "htmlTypes must not be null");

        if (htmlTypes.length == 0) {
            return; // nothing to register; you can also choose to throw
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
                : Set.copyOf(set); // defensive copy, unmodifiable
    }

    public static boolean hasHtmlType(String category, HtmlType type) {
        var set = HTML_TYPE_REG.get(category);
        return set != null && set.contains(type);
    }

    // category -> registered builders
    private static final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> REG =
            new ConcurrentHashMap<>();

    // ---- register ----
    public static void add(String category, Builder builder) {
        REG.computeIfAbsent(
                Objects.requireNonNull(category),
                k -> new CopyOnWriteArrayList<>()
        ).add(Objects.requireNonNull(builder));
    }

    public static void addAll(String category, Collection<? extends Builder> builders) {
        REG.computeIfAbsent(
                Objects.requireNonNull(category),
                k -> new CopyOnWriteArrayList<>()
        ).addAll(Objects.requireNonNull(builders));
    }

    // ---- expand/apply ----
    public static List<XPathy> expand(String category, Object value, Op op) {
        // Try specific category first
        var list = REG.get(category);

        // Fallback to "*" default category if missing/empty
        String effectiveCategory = category;
        if (list == null || list.isEmpty()) {
            list = REG.get("*");
        }

        if (list == null || list.isEmpty()) {
            return List.of();
        }

        String cat = effectiveCategory;

        return list.stream()
                .map(b -> b.build(cat, value, op))
                .toList();
    }

    // OR-combine all produced XPathy (empty if none)
    public static Optional<XPathy> orAll(String category, Object value, Op op) {
        return expand(category, value, op).stream().reduce(XPathy::or);
    }
}
