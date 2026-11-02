package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/**
 * Hook writes a plain key, a {@code pkb_} key, and a secret through {@link PKB_props#put}
 * next to {@code glue}. Used to prove every hook key is {@code source=hook}.
 */
public final class HookSourceRunner extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        PKB_props.glue("com.example.steps");
        PKB_props.features("classpath:features");
        PKB_props.dataPath("data");
        PKB_props.callPath("calls");
        PKB_props.componentPath("component");
        PKB_props.configPath("configs");
    }

    @Override
    public void globalTestProperties() {
        PKB_props.put("description", "hook plain description");
        PKB_props.put("pkb_hook_marker", "from-hook");
        PKB_props.put("pkb_password", "runner-hook-secret-value");
    }
}
