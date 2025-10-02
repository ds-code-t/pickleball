package tools.ds.modkit;

import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.stream.Collectors;

import tools.ds.modkit.blackbox.Plans;
import tools.ds.modkit.blackbox.Registry;
import tools.ds.modkit.blackbox.Weaver;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class ModKitCore {
    private static volatile boolean enabled;

    /** Simple probe for callers/tests */
    public static boolean isEnabled() { return enabled; }

    /** Called by EnsureInstalled *after* Instrumentation is available. */
    public static synchronized void install(Instrumentation inst) {
        System.out.println("@@ModKitCore-install");
        if (enabled) return;
        enabled = true;

        boolean debug = Boolean.getBoolean("modkit.debug");

        AgentBuilder base = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // keep ignores conservative so we don't wander into JDK/BB internals
                .ignore(
                        nameStartsWith("net.bytebuddy.")
                                .or(nameStartsWith("org.slf4j."))
                                .or(nameStartsWith("tools.ds.modkit.")) // avoid weaving your own classes
                                .or(isSynthetic())
                );

        // 1) Apply built-in / SPI patches first
        int patchCount = 0;
        for (ModPatch p : ServiceLoader.load(ModPatch.class)) {
            try {
                base = p.apply(base, inst);
                patchCount++;
            } catch (Throwable t) {
                if (debug) System.err.println("[modkit] patch '" + p.id() + "' apply failed: " + t);
            }
        }

        // 2) Programmatic plugin hooks (no SPI)
        ModKitPlugins.fire();

        // Optional: print planned method intercepts
        if (debug) {
            var planKeys = Registry.allMethodPlans().stream()
                    .map(Plans.MethodPlan::key)
                    .sorted()
                    .collect(Collectors.toList());
            System.err.println("[modkit] plans=" + planKeys);
        }

        // 3) Apply Registry-backed weaver
        base = Weaver.apply(base);

        // Optional Byte Buddy listener spam for debugging
        if (debug) {
            base = base.with(new AgentBuilder.Listener.StreamWriting(System.out));
        }

        // 4) Install transformer
        base.installOn(inst);

        // 5) Eagerly retransform already-loaded targets
        Set<String> types = Weaver.targetTypes();
        if (!types.isEmpty()) {
            eagerRetransform(inst, types);
        }

        System.err.println("[modkit] installed with " + patchCount + " patch(es). Debug=" + debug);
    }

    /** Retransform by FQCN for classes that are already loaded. */
    public static void eagerRetransform(Instrumentation inst, Set<String> fqcnTargets) {
        if (fqcnTargets == null || fqcnTargets.isEmpty()) return;

        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && fqcnTargets.contains(c.getName()) && inst.isModifiableClass(c)) {
                loaded.add(c);
            }
        }
        if (!loaded.isEmpty()) {
            try {
                inst.retransformClasses(loaded.toArray(new Class<?>[0]));
            } catch (Throwable t) {
                if (Boolean.getBoolean("modkit.debug")) {
                    System.err.println("[modkit] eagerRetransform failed: " + t);
                }
            }
        }
    }

    /** Tiny debug logger (used by the listener). Prints only when -Dmodkit.debug=true. */
    public static void log(String msg) {
        if (Boolean.getBoolean("modkit.debug")) {
            System.err.println(msg);
        }
    }

    private ModKitCore() {}
}
