package tools.dscode.testengine.fixtures;

import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/** Consumer-style hooks. Capture freezes these; a later workspace edit must win at launch. */
public final class Phase7Hooked extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "");
        values.put(PKB_props.PKB_CALL_PATH, "src/test/resources/calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "src/test/resources/component");
        values.put(PKB_props.PKB_CONFIG_PATH, "wd-file:configs");
        values.put(PKB_props.PKB_PARALLEL, Workspace.PARALLEL_AUTO);
        values.put(PKB_props.PKB_BROWSER, "firefox");
    }

    @Override
    public void globalTestProperties() {
        values.put(PKB_props.PKB_BROWSER, "firefox");
        values.put(PKB_props.PKB_PARALLEL, Workspace.PARALLEL_AUTO);
        values.put("pkb_hook_marker", "from-hook");
        values.put(PKB_props.PKB_RP_API_KEY, "do-not-store-this");
    }
}
