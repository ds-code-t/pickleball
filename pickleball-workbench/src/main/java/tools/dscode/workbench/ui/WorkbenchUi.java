package tools.dscode.workbench.ui;

import tools.dscode.control.protocol.Workspace;
import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.mcp.WorkbenchAttachServer;

import javax.swing.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Launches the thin Swing Workbench adapter for one consumer project. */
public final class WorkbenchUi {
    private WorkbenchUi() {
    }

    public static void launch(Path projectRoot) {
        launch(projectRoot, null, null);
    }

    public static void launch(Path projectRoot, String runId, String agentId) {
        Map<String, String> properties = workerProperties(projectRoot, runId, agentId);
        Runnable show = () -> {
            WorkbenchTheme.install();
            WorkbenchController services = new WorkbenchController(projectRoot, properties);
            services.attachUi();
            WorkbenchAttachServer attach = WorkbenchAttachServer.start(services, projectRoot);
            WorkbenchUiController controller = new WorkbenchUiController(projectRoot, services);
            WorkbenchFrame frame = new WorkbenchFrame(controller, attach, runId, agentId);
            frame.setVisible(true);
            // JFXPanel starts JavaFX with a SecondaryLoop on this EDT. Defer it until
            // after the window is showing so the unnamed-module warning is not a hang.
            SwingUtilities.invokeLater(frame::installInteractiveViews);
        };

        SwingUtilities.invokeLater(show);
    }

    /** A named run launches from its workspace. An unnamed window keeps the last snapshot fallback. */
    static Map<String, String> workerProperties(Path projectRoot, String runId, String agentId) {
        if (runId == null || runId.isBlank()) return Map.of();
        Map<String, String> selection = new LinkedHashMap<>();
        selection.put("pkb_run_id", runId.trim());
        if (agentId != null && !agentId.isBlank()) selection.put("pkb_agent_id", agentId.trim());
        return Workspace.launchProperties(Workspace.directory(projectRoot, runId.trim()), selection);
    }
}
