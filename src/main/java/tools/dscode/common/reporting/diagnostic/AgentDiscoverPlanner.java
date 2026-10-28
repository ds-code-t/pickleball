package tools.dscode.common.reporting.diagnostic;

/**
 * Discover snapshot errors only. Launch modes do not build a run-var string here.
 * {@code --runvar} edits go through {@code Workspace.applyLaunchEdits}.
 */
public final class AgentDiscoverPlanner {
    private AgentDiscoverPlanner() {
    }

    public static String missingDiscoverSnapshotMessage() {
        return "No prior Discover snapshot. Run Workbench discover first. "
                + "Isolate/Confirm will not silently re-resolve from project defaults.";
    }
}
