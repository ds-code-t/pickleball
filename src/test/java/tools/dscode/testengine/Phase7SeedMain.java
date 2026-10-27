package tools.dscode.testengine;

import tools.dscode.control.protocol.Workspace;

/**
 * Resolve-only capture for the Phase 7 launch test.
 * Production {@link WorkspaceSeedMain} scans the consumer classpath for the one
 * runner. This fixture is that runner: the same {@link WorkspaceSeed#writeCaptured}
 * path, stopped before Cucumber.
 */
public final class Phase7SeedMain {
    private Phase7SeedMain() {
    }

    public static void main(String[] args) {
        System.setProperty(WorkspaceSeed.SEED_PROPERTY, "true");
        System.clearProperty(Workspace.PROPERTY);
        int code = 1;
        try {
            WorkspaceSeed.Parsed parsed = WorkspaceSeed.Parsed.parse(args);
            WorkspaceSeed.seed(
                    parsed.project(),
                    parsed.runId(),
                    parsed.reseed(),
                    parsed.fromRunId(),
                    null,
                    workspace -> {
                        Hooked runner = new Hooked();
                        WorkspaceSeed.writeCaptured(workspace, runner, parsed.reseed());
                    }
            );
            System.out.println("workspace=" + Workspace.directory(parsed.project(), parsed.runId()));
            code = 0;
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            code = 2;
        } catch (RuntimeException failure) {
            failure.printStackTrace(System.err);
            code = 1;
        }
        System.exit(code);
    }

    /** Consumer-style hooks. Capture freezes these; a later workspace edit must win at launch. */
    public static final class Hooked extends PickleballRunner {
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
}
