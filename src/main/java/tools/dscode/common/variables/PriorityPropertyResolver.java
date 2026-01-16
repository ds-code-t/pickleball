package tools.dscode.common.variables;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class PriorityPropertyResolver {

    private static final Comparator<Entry> ORDER =
            Comparator.<Entry>comparingDouble(e -> e.priority).reversed()
                    .thenComparingLong(e -> e.seq); // insertion order for ties

    private final List<Entry> entries = new ArrayList<>();
    private long seq = 0;

    private Predicate<Object> valid = v -> v != null;

    private Consumer<PriorityPropertyResolver> bootstrap = r -> {};
    private boolean bootstrapped = false;

    public PriorityPropertyResolver register(double priority, Function<String, ?> resolver) {
        entries.add(new Entry(priority, seq++, (Function<String, Object>) resolver));
        entries.sort(ORDER);
        return this;
    }

    /** Optional. Runs at most once: first time a retrieval returns a valid value. */
    public PriorityPropertyResolver bootstrap(Consumer<PriorityPropertyResolver> bootstrap) {
        this.bootstrap = bootstrap == null ? (r -> {}) : bootstrap;
        return this;
    }

    /** Optional. Defaults to non-null. Customize however you want. */
    public PriorityPropertyResolver validIf(Predicate<Object> predicate) {
        this.valid = predicate == null ? (v -> v != null) : predicate;
        return this;
    }

    /** Convenience: checks overrides (in order) first, then lambdas. */
    public Object get(String key, Object... overrides) {
        Object v = firstValid(overrides);
        if (!isValid(v)) v = fromResolvers(key);

        if (!bootstrapped && isValid(v)) {
            bootstrapped = true;
            bootstrap.accept(this);
            entries.sort(ORDER); // in case bootstrap inserted/reprioritized
        }
        return v;
    }

    private Object firstValid(Object... overrides) {
        if (overrides == null) return null;
        for (Object o : overrides) if (isValid(o)) return o;
        return null;
    }

    private Object fromResolvers(String key) {
        for (Entry e : entries) {
            Object v = e.resolver.apply(key);
            if (isValid(v)) return v;
        }
        return null;
    }

    private boolean isValid(Object v) {
        return valid.test(v);
    }

    private record Entry(double priority, long seq, Function<String, Object> resolver) {}
}
