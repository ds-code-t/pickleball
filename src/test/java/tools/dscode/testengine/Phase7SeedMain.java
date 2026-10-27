package tools.dscode.testengine;

import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.fixtures.Phase7Hooked;

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
                        Phase7Hooked runner = new Phase7Hooked();
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
}
