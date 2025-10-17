package tools.dscode.modkit.blackbox;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import tools.dscode.modkit.contrib.cucumber.CucumberModKitBootstrap;

/**
 * Bridges the DSL/registry to the ByteBuddy builder during offline weaving.
 * - Initializes the registry by calling CucumberModKitBootstrap.registerAll()
 * - Applies every MethodPlan and CtorPlan to the current type.
 */
public final class OfflineWeaver {
    private OfflineWeaver() {}

    public static void initialize() {
        // Register all DSL entries into the Registry
        CucumberModKitBootstrap.registerAll();

        System.out.println(
                "[Weaver][init] Initialized. methodPlans=" + Registry.allMethodPlans().size() +
                        ", ctorPlans=" + Registry.allCtorPlans().size()
        );
    }

    /**
     * Apply all registered plans to the given ByteBuddy builder for the provided type.
     * This is invoked from OfflineWeaverMain for each targeted class.
     */
    public static DynamicType.Builder<Object> applyTo(DynamicType.Builder<Object> builder,
                                                      TypeDescription type,
                                                      TypePool pool) {
        System.out.println(
                "[OfflineWeaver] applying " + Registry.allMethodPlans().size() +
                        " method plans and " + Registry.allCtorPlans().size() +
                        " ctor plans to " + type.getName()
        );

        DynamicType.Builder<Object> b = builder;

        // Let each plan wire its own matchers/advices
        for (Plans.MethodPlan p : Registry.allMethodPlans()) {
            b = p.apply(b, type, pool);
        }
        for (Plans.CtorPlan p : Registry.allCtorPlans()) {
            b = p.apply(b, type, pool);
        }
        return b;
    }
}
