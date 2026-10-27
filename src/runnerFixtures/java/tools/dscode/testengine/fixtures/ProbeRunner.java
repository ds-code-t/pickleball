package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/**
 * Hooks would force headless and a different marker. They must not run when
 * {@code pkb_workspace} is set, so a workspace edit wins.
 */
public final class ProbeRunner extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        values.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        values.put("pkb_hook_marker", "hook-ran-at-launch");
    }

    @Override
    public void globalTestProperties() {
        values.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        values.put("pkb_hook_marker", "hook-ran-at-launch");
    }
}
