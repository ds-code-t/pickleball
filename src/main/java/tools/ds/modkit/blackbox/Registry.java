package tools.ds.modkit.blackbox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Registry {
    private static final Map<String, Plans.MethodPlan> METHOD_PLANS = new ConcurrentHashMap<>();
    private static final Map<String, Plans.CtorPlan>   CTOR_PLANS   = new ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicLong PLAN_SEQ = new java.util.concurrent.atomic.AtomicLong();
    public static long nextPlanId() { return PLAN_SEQ.incrementAndGet(); }

    // ---- de-dupe store: once per ctor key, per object (weak) ----
    // key = fqcn#<init>/argc, value = synchronized Set<Object> with weak keys
    private static final Map<String, Set<Object>> SEEN_BY_CTOR_KEY = new ConcurrentHashMap<>();

    private static Set<Object> seenSetFor(String ctorKey) {
        return SEEN_BY_CTOR_KEY.computeIfAbsent(
                ctorKey,
                k -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()))
        );
    }

    private static boolean firstTimeForCtor(String ctorKey, Object self) {
        if (self == null) return false;
        return seenSetFor(ctorKey).add(self);
    }

    private Registry() {}

    public static void register(Plans.MethodPlan plan) {
        METHOD_PLANS.put(plan.key(), plan);
    }

    public static void register(Plans.CtorPlan plan) {
        final String key = plan.key();
        final Plans.InstanceMutator orig = plan.afterInstance; // may be null

        // Wrap the afterInstance so it fires only once per instance for this ctor key
        Plans.CtorPlan wrapped = new Plans.CtorPlan(
                plan.fqcn, plan.argCount, plan.before,
                self -> {
                    if (!firstTimeForCtor(key, self)) return;
                    if (orig != null) orig.mutate(self);
                }
        );

        CTOR_PLANS.put(key, wrapped);
    }

    public static Plans.MethodPlan methodPlan(String key) { return METHOD_PLANS.get(key); }
    public static Plans.CtorPlan   ctorPlan(String key)   { return CTOR_PLANS.get(key); }

    public static Collection<Plans.MethodPlan> allMethodPlans() { return METHOD_PLANS.values(); }
    public static Collection<Plans.CtorPlan>   allCtorPlans()   { return CTOR_PLANS.values(); }
}
