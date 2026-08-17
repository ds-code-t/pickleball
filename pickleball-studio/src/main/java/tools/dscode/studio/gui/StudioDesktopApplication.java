package tools.dscode.studio.gui;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
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
                StudioFrame frame = new StudioFrame(session);
                RuntimeControlDialog runtimeDialog = new RuntimeControlDialog(frame, session);
                frame.setJMenuBar(menuBar(frame, runtimeDialog, session));
                frame.setVisible(true);
            } catch (RuntimeException failure) {
                session.close();
                failure.printStackTrace(error);
            }
        });
        return 0;
    }

    private static JMenuBar menuBar(
            StudioFrame frame,
            RuntimeControlDialog runtimeDialog,
            StudioDesktopSession session
    ) {
        JMenuItem runtimeControl = new JMenuItem("Runtime Control...");
        runtimeControl.setEnabled(session.testBuildTool() != null);
        runtimeControl.addActionListener(event -> {
            runtimeDialog.setLocationRelativeTo(frame);
            runtimeDialog.setVisible(true);
        });

        JMenu runtime = new JMenu("Runtime");
        runtime.add(runtimeControl);

        JMenuBar menuBar = new JMenuBar();
        menuBar.add(runtime);
        return menuBar;
    }
}
