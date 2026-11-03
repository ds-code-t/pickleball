package tools.dscode.testengine.parity;

import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.DynamicSuiteBootstrap;
import tools.dscode.testengine.PickleballRunner;
import tools.dscode.testengine.WorkspaceSeed;

import java.nio.file.Path;

/** Captures one normal runner construction. Hooks run here and not again at launch. */
public final class LaunchSeedMain {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: LaunchSeedMain <workspace>");
            System.exit(2);
        }
        Path workspace = Path.of(args[0]);
        PickleballRunner runner = DynamicSuiteBootstrap.initializeFromRuntimeClasspath();
        WorkspaceSeed.writeCaptured(workspace, runner, false);
        if (!Workspace.exists(workspace)) {
            System.err.println("seed did not write " + workspace);
            System.exit(1);
        }
    }
}
