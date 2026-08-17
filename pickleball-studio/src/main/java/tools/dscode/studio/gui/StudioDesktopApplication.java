package tools.dscode.studio.gui;

import tools.dscode.studio.mcp.StudioServer;
import tools.dscode.studio.mcp.StudioServerHandle;

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

        StudioServerHandle server;
        StudioDesktopSession session;
        try {
            server = StudioServer.open(workspace, 0, null);
            session = server.context().getBean(StudioDesktopSession.class);
            session.activateDesktop();
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
                StudioCollaborationDialog collaborationDialog = new StudioCollaborationDialog(
                        frame,
                        session,
                        server
                );
                frame.setJMenuBar(menuBar(frame, runtimeDialog, collaborationDialog, session));
                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent event) {
                        server.close();
                    }
                });
                frame.setVisible(true);
            } catch (RuntimeException failure) {
                session.close();
                server.close();
                failure.printStackTrace(error);
            }
        });
        return 0;
    }

    private static JMenuBar menuBar(
            StudioFrame frame,
            RuntimeControlDialog runtimeDialog,
            StudioCollaborationDialog collaborationDialog,
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

        JMenuItem collaboration = new JMenuItem("AI Collaboration...");
        collaboration.addActionListener(event -> {
            collaborationDialog.setLocationRelativeTo(frame);
            collaborationDialog.setVisible(true);
        });
        JMenu studio = new JMenu("Studio");
        studio.add(collaboration);

        JMenuBar menuBar = new JMenuBar();
        menuBar.add(studio);
        menuBar.add(runtime);
        return menuBar;
    }
}
