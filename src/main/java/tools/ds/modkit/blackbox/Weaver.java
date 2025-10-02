package tools.ds.modkit.blackbox;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class Weaver {
    private Weaver() {}

    public static AgentBuilder apply(AgentBuilder base) {
        AgentBuilder b = base;

        // -------- methods (unchanged) ----------------------------------------
        Map<String, List<Plans.MethodPlan>> byType = new HashMap<>();
        for (Plans.MethodPlan mp : Registry.allMethodPlans()) {
            byType.computeIfAbsent(mp.fqcn, k -> new ArrayList<>()).add(mp);
        }

        for (Map.Entry<String, List<Plans.MethodPlan>> e : byType.entrySet()) {
            String fqcn = e.getKey();
            List<Plans.MethodPlan> plans = e.getValue();
            b = b.type(named(fqcn))
                    .transform((builder, td, cl, module, pd) -> {
                        for (Plans.MethodPlan mp : plans) {
                            var m = named(mp.method).and(takesArguments(mp.argCount));
                            if (mp.returnTypeFqcn != null) {
                                try {
                                    Class<?> rt = Class.forName(mp.returnTypeFqcn, false, cl);
                                    m = m.and(returns(rt));
                                } catch (Throwable ignored) {
                                    // tolerate missing RT filter
                                }
                            }
                            builder = builder.method(m).intercept(Advice.to(MethodAdvice.class));
                        }
                        return builder;
                    });
        }

        // -------- constructors (install once per type, match ALL ctors) -------
        // We only need to attach CtorAdvice once per FQCN; CtorAdvice decides what to do
        // based on the registry plans (arg-count still matters for 'before' arg mutation).
        Set<String> ctorTypes = new HashSet<>();
        for (Plans.CtorPlan cp : Registry.allCtorPlans()) {
            ctorTypes.add(cp.fqcn);
        }

        for (String fqcn : ctorTypes) {
            b = b.type(named(fqcn))
                    .transform((builder, td, cl, module, pd) ->
                            builder.visit(Advice.to(CtorAdvice.class).on(isConstructor()))
                    );
        }

        return b;
    }

    public static Set<String> targetTypes() {
        Set<String> s = new HashSet<>();
        for (var p : Registry.allMethodPlans()) s.add(p.fqcn);
        for (var p : Registry.allCtorPlans())   s.add(p.fqcn);
        return s;
    }
}
