package tools.dscode.modkit.blackbox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Registry {
    private Registry() {}

    private static final Map<String, Plans.MethodPlan> METHOD_PLANS = new ConcurrentHashMap<>();
    private static final Map<String, Plans.CtorPlan>   CTOR_PLANS   = new ConcurrentHashMap<>();

    // Ensure “afterInstance” runs once per object per ctor key
    private static final Map<String, Set<Object>> SEEN_BY_CTOR_KEY = new ConcurrentHashMap<>();

    private static Set<Object> seen(String ctorKey) {
        return SEEN_BY_CTOR_KEY.computeIfAbsent(
                ctorKey, k -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()))
        );
    }
    private static boolean firstTime(String ctorKey, Object self) {
        return self != null && seen(ctorKey).add(self);
    }

    public static void register(Plans.MethodPlan p) {
        System.out.println("@@register1");
        METHOD_PLANS.put(p.key(), p); }

    public static void register(Plans.CtorPlan p) {
        final String key = p.key();
        final Plans.InstanceMutator orig = p.afterInstance;
        CTOR_PLANS.put(key, new Plans.CtorPlan(p.fqcn, p.argCount, p.before, self -> {
            if (firstTime(key, self) && orig != null) orig.mutate(self);
        }));
    }

    public static Plans.MethodPlan methodPlan(String key) { return METHOD_PLANS.get(key); }
    public static Plans.CtorPlan   ctorPlan(String key)   { return CTOR_PLANS.get(key); }

    public static Collection<Plans.MethodPlan> allMethodPlans() { return METHOD_PLANS.values(); }
    public static Collection<Plans.CtorPlan>   allCtorPlans()   { return CTOR_PLANS.values(); }
}
