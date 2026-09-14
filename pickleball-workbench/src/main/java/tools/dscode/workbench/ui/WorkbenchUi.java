package tools.dscode.workbench.ui;

import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.mcp.WorkbenchAttachServer;

import javax.swing.*;
import java.nio.file.Path;

/** Launches the thin Swing Workbench adapter for one consumer project. */
public final class WorkbenchUi {
    private WorkbenchUi() {
    }

    public static void launch(Path projectRoot) {
        Runnable show = () -> {
            WorkbenchTheme.install();
            WorkbenchController services = new WorkbenchController(projectRoot);
            services.attachUi();
            WorkbenchAttachServer attach = WorkbenchAttachServer.start(services, projectRoot);
            WorkbenchUiController controller = new WorkbenchUiController(projectRoot, services);
            WorkbenchFrame frame = new WorkbenchFrame(controller, attach);
            frame.setVisible(true);
            // JFXPanel starts JavaFX with a SecondaryLoop on this EDT. Defer it until
            // after the window is showing so the unnamed-module warning is not a hang.
            SwingUtilities.invokeLater(frame::installInteractiveViews);
        };

        SwingUtilities.invokeLater(show);
    }
}
