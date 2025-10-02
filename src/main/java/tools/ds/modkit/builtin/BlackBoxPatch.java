package tools.ds.modkit.builtin;

import net.bytebuddy.agent.builder.AgentBuilder;
import tools.ds.modkit.ModPatch;
import tools.ds.modkit.blackbox.BlackBoxBootstrap;
import tools.ds.modkit.blackbox.Plans;
import tools.ds.modkit.blackbox.Registry;
import tools.ds.modkit.blackbox.Weaver;
import tools.ds.modkit.patches.PickleStepRunPatch;

import java.lang.instrument.Instrumentation;

/**
 * Single patch that installs the black-box weaver:
 * - populates the registry with your registrations,
 * - applies all rules to one AgentBuilder,
 * - eagerly retransforms already-loaded targets.
 */
public final class BlackBoxPatch implements ModPatch {
    static {
        System.out.println("@@Static block of BlackBoxPatch");
    }
    @Override public String id() { return "BlackBoxPatch"; }

    @Override
    public AgentBuilder apply(AgentBuilder base, Instrumentation inst) {
        // Populate Registry with user-defined registrations
        BlackBoxBootstrap.register();
        PickleStepRunPatch.register();
        // Debug: print the registered method plan keys
        System.err.println("[modkit] plans=" +
                Registry.allMethodPlans().stream()
                        .map(Plans.MethodPlan::key)
                        .sorted()
                        .toList());


        // Apply all declared plans
        return Weaver.apply(base);
    }

    @Override
    public void eagerRetransform(Instrumentation inst) {
        if (inst == null || !inst.isRetransformClassesSupported()) return;
        var targets = tools.ds.modkit.blackbox.Weaver.targetTypes();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (targets.contains(c.getName()) && inst.isModifiableClass(c)) {
                try { inst.retransformClasses(c); } catch (Throwable ignore) {}
            }
        }
    }
}
