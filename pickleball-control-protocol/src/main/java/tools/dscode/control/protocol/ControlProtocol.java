package tools.dscode.control.protocol;

import java.util.List;

/** Versioned, dependency-neutral constants shared by the controller and consumer worker. */
public final class ControlProtocol {
    public static final int CURRENT_VERSION = 2;
    public static final int MINIMUM_COMPATIBLE_VERSION = 2;

    public static final String WORKER_MAIN_CLASS = "tools.dscode.testengine.WorkbenchWorkerMain";
    public static final String WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY =
            "pickleball.workbench.testOutputRoot";
    public static final String EMBEDDED_WORKBENCH_RESOURCE =
            "META-INF/pickleball/workbench/pickleball-workbench.jar";
    /**
     * Legacy relative path for Discover snapshots. Live reads/writes go through
     * {@link PickleballLocalLayout#lastDiscoverSnapshot(java.nio.file.Path)} so a
     * versioned {@code v/<version>/workbench/} tree is used when {@code current.json}
     * is complete.
     */
    public static final String LAST_DISCOVER_SNAPSHOT_RELATIVE =
            ".pickleball/workbench/last-discover.json";
    /**
     * Legacy relative path for headless CLI session state. Live reads/writes go
     * through {@link PickleballLocalLayout#cliSessionState(java.nio.file.Path)}.
     */
    public static final String CLI_SESSION_STATE_RELATIVE =
            ".pickleball/workbench/cli-session.json";

    /*
     * Reserved neutral references used over the existing Mapping snapshot/restore
     * contract. The worker resolves these against the currently running ParsingMap;
     * the Workbench never imports ParsingMap or NodeMap classes.
     */
    public static final String CURRENT_NODE_MAP_CATALOG_REFERENCE =
            "__pickleball_workbench_current_nodemap_catalog__";
    public static final String CURRENT_NODE_MAP_REFERENCE_PREFIX =
            "__pickleball_workbench_current_nodemap__:";
    /**
     * Workbench-authored STEP_MAP values for a Gherkin step that has not run
     * yet. The worker materializes an ordinary NodeMap for the reference and
     * copies it onto the live step map when that step first executes.
     */
    public static final String STEP_SEED_REFERENCE_PREFIX =
            "__pickleball_workbench_step_seed__:";

    public static String stepSeedReference(String stepText) {
        String normalized = normalizeStepSeedText(stepText);
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return STEP_SEED_REFERENCE_PREFIX + encoded;
    }

    public static String stepSeedText(String reference) {
        if (reference == null || !reference.startsWith(STEP_SEED_REFERENCE_PREFIX)) return "";
        String encoded = reference.substring(STEP_SEED_REFERENCE_PREFIX.length());
        try {
            return new String(
                    java.util.Base64.getUrlDecoder().decode(encoded),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String normalizeStepSeedText(String stepText) {
        String trimmed = stepText == null ? "" : stepText.strip();
        for (String keyword : java.util.List.of("Given ", "When ", "Then ", "And ", "But ", "* ")) {
            if (trimmed.startsWith(keyword)) {
                return trimmed.substring(keyword.length()).strip();
            }
        }
        return trimmed;
    }

    public static final String SESSION_DIRECTORY_ENV = "PKB_CONTROL_BRIDGE_SESSION_DIR";
    public static final String SESSION_ID_ENV = "PKB_CONTROL_BRIDGE_SESSION_ID";
    public static final String SESSION_TOKEN_ENV = "PKB_CONTROL_BRIDGE_TOKEN";
    public static final String PAUSE_FIRST_SCENARIO_ENV =
            "PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO";

    public static final List<String> WORKER_CAPABILITIES = List.of(
            "status", "scenarios", "events", "pause", "resume", "execute_step", "resolve_step",
            "mapping_get", "mapping_put", "mapping_resolve", "mapping_snapshot", "mapping_restore",
            "browser_page", "browser_screenshot", "element_inspect", "service_call", "breakpoints",
            "step_overrides", "step_override_compile"
    );

    public static final List<String> CONTROLLER_REQUIRED_CAPABILITIES = WORKER_CAPABILITIES;

    private ControlProtocol() {
    }
}
