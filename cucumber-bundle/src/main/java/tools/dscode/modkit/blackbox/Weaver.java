package tools.dscode.modkit.blackbox;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class Weaver {
    private Weaver() {}

    /** Applies all current plans to the given AgentBuilder. */
    public static AgentBuilder apply(AgentBuilder base) {
        AgentBuilder b = base;

        // Methods by type
        Map<String, List<Plans.MethodPlan>> byType = new HashMap<>();
        for (Plans.MethodPlan mp : Registry.allMethodPlans()) {
            byType.computeIfAbsent(mp.fqcn, k -> new ArrayList<>()).add(mp);
        }
        for (Map.Entry<String, List<Plans.MethodPlan>> e : byType.entrySet()) {
            String fqcn = e.getKey();
            List<Plans.MethodPlan> plans = e.getValue();
            b = b.type(named(fqcn)).transform((builder, td, cl, module, pd) -> {
                for (Plans.MethodPlan mp : plans) {
                    var m = named(mp.method).and(takesArguments(mp.argCount));
                    if (mp.returnTypeFqcn != null) {
                        try {
                            Class<?> rt = Class.forName(mp.returnTypeFqcn, false, cl);
                            m = m.and(returns(rt));
                        } catch (Throwable ignore) { /* filter best-effort */ }
                    }
                    builder = builder.method(m).intercept(Advice.to(MethodAdvice.class));
                }
                return builder;
            });
        }

        // Constructors: one advice per type, matches all ctors; arg-count handled in advice
        Set<String> ctorTypes = new HashSet<>();
        for (Plans.CtorPlan cp : Registry.allCtorPlans()) ctorTypes.add(cp.fqcn);

        for (String fqcn : ctorTypes) {
            b = b.type(named(fqcn)).transform(
                    (builder, td, cl, module, pd) -> builder.visit(Advice.to(CtorAdvice.class).on(isConstructor()))
            );
        }
        return b;
    }
}
