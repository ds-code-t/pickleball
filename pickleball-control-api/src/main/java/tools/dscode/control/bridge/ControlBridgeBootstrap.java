package tools.dscode.control.bridge;

import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.ControlProtocol;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ControlBridgeBootstrap {
    public static final String ENV_SESSION_DIR = ControlProtocol.SESSION_DIRECTORY_ENV;
    public static final String ENV_SESSION_ID = ControlProtocol.SESSION_ID_ENV;
    public static final String ENV_TOKEN = ControlProtocol.SESSION_TOKEN_ENV;
    public static final String ENV_PAUSE_FIRST_SCENARIO = ControlProtocol.PAUSE_FIRST_SCENARIO_ENV;

    private static final String LEGACY_ENV_SESSION_DIR = "PKB_STUDIO_BRIDGE_SESSION_DIR";
    private static final String LEGACY_ENV_SESSION_ID = "PKB_STUDIO_BRIDGE_SESSION_ID";
    private static final String LEGACY_ENV_TOKEN = "PKB_STUDIO_BRIDGE_TOKEN";
    private static final String LEGACY_ENV_PAUSE_FIRST_SCENARIO = "PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO";

    private static final AtomicReference<ControlBridgeRuntime> ACTIVE = new AtomicReference<>();

    private ControlBridgeBootstrap() {
    }

    public static ControlBridgeDescriptor startFromEnvironment() {
        Map<String, String> environment = System.getenv();
        String sessionDirectory = environmentValue(
                environment, ENV_SESSION_DIR, LEGACY_ENV_SESSION_DIR
        );
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            return null;
        }

        String sessionId = requireEnvironment(
                environment, ENV_SESSION_ID, LEGACY_ENV_SESSION_ID
        );
        String token = requireEnvironment(
                environment, ENV_TOKEN, LEGACY_ENV_TOKEN
        );
        String pauseValue = environmentValue(
                environment, ENV_PAUSE_FIRST_SCENARIO, LEGACY_ENV_PAUSE_FIRST_SCENARIO
        );
        boolean pauseFirstScenario = Boolean.parseBoolean(
                pauseValue == null ? "false" : pauseValue
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

    private static String requireEnvironment(
            Map<String, String> environment,
            String name,
            String legacyName
    ) {
        String value = environmentValue(environment, name, legacyName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required Pickleball control bridge environment variable: " + name
            );
        }
        return value;
    }

    private static String environmentValue(
            Map<String, String> environment,
            String name,
            String legacyName
    ) {
        String value = environment.get(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return environment.get(legacyName);
    }
}
