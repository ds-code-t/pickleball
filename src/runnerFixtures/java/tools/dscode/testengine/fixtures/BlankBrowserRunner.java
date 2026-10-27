package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/** Names no browser, so capture falls back to CHROME_HEADLESS. Not a scanned test-output runner. */
public final class BlankBrowserRunner extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_PARALLEL, "4");
    }
}
