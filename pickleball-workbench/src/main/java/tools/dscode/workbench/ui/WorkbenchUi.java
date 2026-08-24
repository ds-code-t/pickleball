package tools.dscode.workbench.ui;

import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.mcp.WorkbenchAttachServer;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/** Launches the thin Swing Workbench adapter for one consumer project. */
public final class WorkbenchUi {
    private WorkbenchUi() {
    }

    public static void launch(Path projectRoot) {
        Runnable launch = () -> {
            WorkbenchTheme.install();
            WorkbenchController services = new WorkbenchController(projectRoot);
            services.attachUi();
            WorkbenchAttachServer attach = WorkbenchAttachServer.start(services, projectRoot);
            WorkbenchUiController controller = new WorkbenchUiController(projectRoot, services);
            new WorkbenchFrame(controller, attach).setVisible(true);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            launch.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(launch);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workbench UI launch was interrupted.", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not launch Workbench UI.", cause);
        }
    }
}
