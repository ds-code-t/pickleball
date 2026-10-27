package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/**
 * Consumer-style project: hooks supply the six context keys, a browser,
 * a fixed parallel count, and a marker that must survive plain resolution.
 * Not a scanned test-output runner.
 */
public final class ConsumerStyleRunner extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "src/test/resources/calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "src/test/resources/component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_PARALLEL, "4");
        values.put(PKB_props.PKB_BROWSER, "firefox");
    }

    @Override
    public void globalTestProperties() {
        values.put("pkb_environment", "qa");
        values.put("pkb_hook_marker", "from-hook");
    }
}
