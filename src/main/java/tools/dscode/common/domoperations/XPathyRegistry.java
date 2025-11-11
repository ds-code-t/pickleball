package tools.dscode.common.domoperations;

import com.xpathy.XPathy;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

// Minimal, threadsafe registry for: (value, operator) -> XPathy
public final class XPathyRegistry {
    private XPathyRegistry() {}

    // Choose your functional type: (Object value, Op op) -> XPathy
    @FunctionalInterface public interface Builder extends BiFunction<Object, Op, XPathy> {}

    public enum Op { EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, GT, GTE, LT, LTE }

    // category -> registered builders
    private static final ConcurrentMap<String, CopyOnWriteArrayList<Builder>> REG = new ConcurrentHashMap<>();

    // ---- register ----
    public static void add(String category, Builder builder) {
        REG.computeIfAbsent(Objects.requireNonNull(category), k -> new CopyOnWriteArrayList<>())
                .add(Objects.requireNonNull(builder));
    }

    public static void addAll(String category, Collection<? extends Builder> builders) {
        REG.computeIfAbsent(Objects.requireNonNull(category), k -> new CopyOnWriteArrayList<>())
                .addAll(Objects.requireNonNull(builders));
    }

    // ---- expand/apply ----
    public static List<XPathy> expand(String category, Object value, Op op) {
        var list = REG.get(category);
        if (list == null || list.isEmpty()) return List.of();
        return list.stream().map(b -> b.apply(value, op)).toList();
    }

    // OR-combine all produced XPathy (empty if none)
    public static Optional<XPathy> orAll(String category, Object value, Op op) {
        return expand(category, value, op).stream().reduce(XPathy::or);
    }
}
