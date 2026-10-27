package tools.dscode.testengine;

/** Resolve-only entry point. Runs the consumer runner, writes the workspace, and does not start Cucumber. */
public final class WorkspaceSeedMain {
    private WorkspaceSeedMain() {
    }

    public static void main(String[] args) {
        System.exit(WorkspaceSeed.runMain(args));
    }
}
