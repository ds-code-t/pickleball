package tools.dscode.agent;

import tools.ds.modkit.api.ModKit;
import tools.ds.modkit.contrib.cucumber.CucumberModKitBootstrap;

/**
 * Ensures the Byte Buddy agent is attached and all registered ModKit
 * instrumentation plans (e.g., for Cucumber) are applied once at the earliest
 * possible entry point.
 *
 * Call {@link #InitAgent()} from any Cucumber lifecycle hook or launcher code.
 * The static initializer will run only once per JVM.
 */
public final class InitOnce {

    static {
        System.out.println("InitOnce logic executed! Attaching ModKit agent...");

        try {
            // 1️⃣ Register all Cucumber-related method/constructor hooks
            CucumberModKitBootstrap.registerAll();

            // 2️⃣ Attach the Byte Buddy agent and weave all registered plans
            ModKit.attach();

            System.out.println("ModKit agent attached and instrumentation applied.");
        } catch (Throwable t) {
            System.err.println("[InitOnce] Failed to initialize ModKit agent:");
            t.printStackTrace(System.err);
        }
    }

    /**
     * Entry point to trigger the static initializer once.
     * Call this early (e.g., in a Cucumber plugin, test runner, or @BeforeAll).
     */
    public static void InitAgent() {
        // intentionally empty – class loading triggers the static block
    }

    // prevent instantiation
    private InitOnce() {}
}
