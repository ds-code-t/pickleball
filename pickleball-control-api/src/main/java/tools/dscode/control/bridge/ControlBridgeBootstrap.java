
package tools.dscode.control.bridge;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class ControlBridgeBootstrap {
    public static final String ENV_SESSION_DIR = "PKB_STUDIO_BRIDGE_SESSION_DIR";
    public static final String ENV_SESSION_ID = "PKB_STUDIO_BRIDGE_SESSION_ID";
    public static final String ENV_TOKEN = "PKB_STUDIO_BRIDGE_TOKEN";
    public static final String ENV_PAUSE_FIRST_SCENARIO = "PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO";

    private static final AtomicReference<ControlBridgeRuntime> ACTIVE = new AtomicReference<>();

    private ControlBridgeBootstrap() {
    }

    public static ControlBridgeDescriptor startFromEnvironment() {
        String sessionDirectory = System.getenv(ENV_SESSION_DIR);
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            return null;
        }

        String sessionId = requireEnvironment(ENV_SESSION_ID);
        String token = requireEnvironment(ENV_TOKEN);
        boolean pauseFirstScenario = Boolean.parseBoolean(
                System.getenv().getOrDefault(ENV_PAUSE_FIRST_SCENARIO, "false")
        );

        return start(Path.of(sessionDirectory), sessionId, token, pauseFirstScenario);
    }

    public static ControlBridgeDescriptor start(
            Path sessionDirectory,
            String sessionId,
            String token,
            boolean pauseFirstScenario
    ) {
        ControlBridgeRuntime current = ACTIVE.get();
        if (current != null) {
            return current.descriptor();
        }

        ControlBridgeRuntime created = ControlBridgeRuntime.start(
                sessionDirectory,
                sessionId,
                token,
                pauseFirstScenario
        );
        if (ACTIVE.compareAndSet(null, created)) {
            Runtime.getRuntime().addShutdownHook(
                    Thread.ofPlatform()
                            .name("pickleball-control-bridge-shutdown")
                            .unstarted(ControlBridgeBootstrap::stop)
            );
            return created.descriptor();
        }

        created.close();
        return ACTIVE.get().descriptor();
    }

    public static void stop() {
        ControlBridgeRuntime runtime = ACTIVE.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
    }

    public static ControlBridgeDescriptor current() {
        ControlBridgeRuntime runtime = ACTIVE.get();
        return runtime == null ? null : runtime.descriptor();
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required Pickleball Studio bridge environment variable: " + name
            );
        }
        return value;
    }
}
