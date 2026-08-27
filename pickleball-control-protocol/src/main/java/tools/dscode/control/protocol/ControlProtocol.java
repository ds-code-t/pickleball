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
    /** Written by Workbench discover; isolate/confirm replay this snapshot as {@code pkb_runvars}. */
    public static final String LAST_DISCOVER_SNAPSHOT_RELATIVE =
            ".pickleball/workbench/last-discover.json";

    /*
     * Reserved neutral references used over the existing Mapping snapshot/restore
     * contract. The worker resolves these against the currently running ParsingMap;
     * the Workbench never imports ParsingMap or NodeMap classes.
     */
    public static final String CURRENT_NODE_MAP_CATALOG_REFERENCE =
            "__pickleball_workbench_current_nodemap_catalog__";
    public static final String CURRENT_NODE_MAP_REFERENCE_PREFIX =
            "__pickleball_workbench_current_nodemap__:";

    public static final String SESSION_DIRECTORY_ENV = "PKB_CONTROL_BRIDGE_SESSION_DIR";
    public static final String SESSION_ID_ENV = "PKB_CONTROL_BRIDGE_SESSION_ID";
    public static final String SESSION_TOKEN_ENV = "PKB_CONTROL_BRIDGE_TOKEN";
    public static final String PAUSE_FIRST_SCENARIO_ENV =
            "PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO";

    public static final List<String> WORKER_CAPABILITIES = List.of(
            "status", "scenarios", "events", "pause", "resume", "execute_step",
            "mapping_get", "mapping_put", "mapping_resolve", "mapping_snapshot", "mapping_restore",
            "browser_page", "browser_screenshot", "element_inspect", "service_call", "breakpoints",
            "step_overrides", "step_override_compile"
    );

    public static final List<String> CONTROLLER_REQUIRED_CAPABILITIES = WORKER_CAPABILITIES;

    private ControlProtocol() {
    }
}
