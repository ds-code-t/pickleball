package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/** Consumer-style hooks used by workspace seed tests. Not a scanned test-output runner. */
public final class WorkspaceSeedHooked extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_PARALLEL, "auto");
        values.put(PKB_props.PKB_BROWSER, "firefox");
    }

    @Override
    public void globalTestProperties() {
        values.put(PKB_props.PKB_ENVIRONMENT, "qa");
        values.put("pkb_hook_marker", "from-hook");
    }
}
