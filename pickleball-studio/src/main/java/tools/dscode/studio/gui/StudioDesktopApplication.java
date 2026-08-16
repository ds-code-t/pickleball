
package tools.dscode.studio.gui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Path;

public final class StudioDesktopApplication {
    private StudioDesktopApplication() {
    }

    public static int launch(Path workspace, PrintStream error) {
        if (GraphicsEnvironment.isHeadless()) {
            error.println("Pickleball Studio UI requires a desktop graphics environment.");
            return 2;
        }

        StudioDesktopSession session;
        try {
            session = StudioDesktopSession.open(workspace);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            error.println(failure.getMessage());
            return 2;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Keep the default Swing look and feel.
            }

            try {
                new StudioFrame(session).setVisible(true);
            } catch (RuntimeException failure) {
                session.close();
                failure.printStackTrace(error);
            }
        });
        return 0;
    }
}
