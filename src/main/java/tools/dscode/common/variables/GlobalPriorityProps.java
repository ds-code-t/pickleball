package tools.dscode.common.variables;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static tools.dscode.common.variables.SysEnv.getPickleBallProperty;
import static tools.dscode.common.variables.SysEnv.pickleBallVarPrefix;
import static tools.dscode.common.variables.SysEnv.systemOrEnv;

public final class GlobalPriorityProps {

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static long SEQ = 0;

    private static Predicate<Object> VALID = v -> v != null;

    private static Consumer<GlobalPriorityProps> BOOTSTRAP = r -> {
    };
    private static boolean BOOTSTRAPPED = false;

    private static final Comparator<Entry> ORDER =
            Comparator.<Entry>comparingDouble(e -> e.priority).reversed()
                    .thenComparingLong(e -> e.seq); // insertion order for ties

    private GlobalPriorityProps() {
    }

    // ---- registration (call in a static initializer elsewhere, or right below) ----

    public static void validIf(Predicate<Object> predicate) {
        VALID = predicate == null ? (v -> v != null) : predicate;
    }

    public static void bootstrap(Consumer<GlobalPriorityProps> bootstrap) {
        BOOTSTRAP = bootstrap == null ? (r -> {
        }) : bootstrap;
    }

    public static void register(double priority, Function<String, ?> resolver) {
        ENTRIES.add(new Entry(priority, SEQ++, (Function<String, Object>) resolver));
        ENTRIES.sort(ORDER);
    }

    // ---- lookup ----

    public static Object get(String key, Object... overrides) {
        Object v = firstValid(overrides);
        if (!isValid(v)) v = fromResolvers(key);

        if (!BOOTSTRAPPED && isValid(v)) {
            BOOTSTRAPPED = true;
            BOOTSTRAP.accept(GlobalPriorityProps.INSTANCE);
            ENTRIES.sort(ORDER);
        }
        return v;
    }

    // ---- internals ----

    private static Object firstValid(Object... overrides) {
        if (overrides == null) return null;
        for (Object o : overrides) if (isValid(o)) return o;
        return null;
    }

    private static Object fromResolvers(String key) {
        for (Entry e : ENTRIES) {
            Object v = e.resolver.apply(key);
            if (isValid(v)) return v;
        }
        return null;
    }

    private static boolean isValid(Object v) {
        return VALID.test(v);
    }

    // marker instance for bootstrap callback signature
    private static final GlobalPriorityProps INSTANCE = new GlobalPriorityProps();

    private record Entry(double priority, long seq, Function<String, Object> resolver) {
    }




    // ---- Option A: one-time global registration (edit this block) ----
    static {
        // Customize validity (optional)
        validIf(Objects::nonNull);
//         validIf(v -> v != null && (!(v instanceof String s) || !s.isEmpty()));

        // Bootstrap (optional): runs once, the first time get(...) returns a valid value
        bootstrap(r -> {
            // insert/reprioritize based on env/system, IntelliJ vs CI, etc.
            // register(95.0, k -> ...);
        });

        // Example resolvers (replace with yours)
        register(70.0, SysEnv::getPickleBallProperty);
        register(50.0, SysEnv::systemOrEnv);

        // register(70.0, k -> yamlLookup(k));
        // register(60.0, k -> cucumberScenarioLookup(k));
    }
}
