package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/**
 * Framework-test runner. Compiled outside {@code build/classes/java/test}
 * so consumer discovery still requires exactly one runner in a real test-output root.
 */
public final class WorkspaceLaunchHooked extends PickleballRunner {
    public static boolean hookRan;

    @Override
    public void globalTestDefaults() {
        hookRan = true;
        values.put(PKB_props.PKB_BROWSER, "firefox");
        values.put("pkb_hook_marker", "from-hook");
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_PARALLEL, "4");
    }
}
