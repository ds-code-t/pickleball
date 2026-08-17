package tools.dscode.studio.gui;

import tools.dscode.studio.collaboration.StudioActivity;
import tools.dscode.studio.collaboration.StudioActivityPage;
import tools.dscode.studio.collaboration.StudioAgentSession;
import tools.dscode.studio.collaboration.StudioEditorState;
import tools.dscode.studio.mcp.StudioServerHandle;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

final class StudioCollaborationDialog extends JDialog {
    private static final int MAX_ACTIVITY_CHARS = 400_000;

    private final StudioDesktopSession session;
    private final StudioServerHandle server;
    private final JTextArea activity = textArea();
    private final JTextArea agents = textArea();
    private final JTextArea editors = textArea();
    private final JLabel status = new JLabel("Ready");
    private final Timer timer;
    private long activitySequence;
    private boolean refreshRunning;

    StudioCollaborationDialog(
            java.awt.Window owner,
            StudioDesktopSession session,
            StudioServerHandle server
    ) {
        super(owner, "Pickleball Studio — AI Collaboration", ModalityType.MODELESS);
        this.session = session;
        this.server = server;
        setMinimumSize(new Dimension(800, 520));
        setSize(1000, 680);
        setLayout(new BorderLayout(6, 6));
        add(connectionPanel(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        timer = new Timer(600, event -> refresh());
    }


    @Override
    public void setVisible(boolean visible) {
        if (timer != null) {
            if (visible) {
                timer.start();
                refresh();
            } else {
                timer.stop();
            }
        }
        super.setVisible(visible);
    }

    private JPanel connectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JTextField endpoint = new JTextField(server.endpointUrl());
        endpoint.setEditable(false);
        endpoint.setCaretPosition(0);

        JButton copy = new JButton("Copy MCP URL");
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(server.endpointUrl()),
                    null
            );
            status.setText("MCP URL copied");
        });

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refresh());

        JPanel buttons = new JPanel();
        buttons.add(copy);
        buttons.add(refresh);

        panel.add(new JLabel("Local MCP:"), BorderLayout.WEST);
        panel.add(endpoint, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private JTabbedPane content() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Activity", new JScrollPane(activity));

        JSplitPane state = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                titled("Agent sessions", agents),
                titled("Desktop editors", editors)
        );
        state.setResizeWeight(0.5);
        state.setDividerLocation(250);
        tabs.addTab("Shared State", state);
        return tabs;
    }

    private static JScrollPane titled(String title, JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder(title));
        return scroll;
    }

    private void refresh() {
        if (refreshRunning) {
            return;
        }
        refreshRunning = true;
        long after = activitySequence;
        Thread.ofVirtual().name("studio-collaboration-refresh").start(() -> {
            try {
                StudioActivityPage page = session.activity(after, 250);
                List<StudioAgentSession> agentSessions = session.agentSessions(true);
                List<StudioEditorState> editorStates = session.editorStates();
                SwingUtilities.invokeLater(() -> apply(page, agentSessions, editorStates));
            } catch (RuntimeException failure) {
                SwingUtilities.invokeLater(() -> {
                    status.setText(failure.getMessage());
                    refreshRunning = false;
                });
            }
        });
    }

    private void apply(
            StudioActivityPage page,
            List<StudioAgentSession> agentSessions,
            List<StudioEditorState> editorStates
    ) {
        if (page.gap()) {
            activity.append("[older Studio activity was evicted from the bounded journal]" + System.lineSeparator());
        }
        for (StudioActivity event : page.activities()) {
            activity.append(format(event));
            activitySequence = Math.max(activitySequence, event.sequence());
        }
        trimActivity();
        activity.setCaretPosition(activity.getDocument().getLength());

        StringBuilder sessionText = new StringBuilder();
        for (StudioAgentSession agent : agentSessions) {
            sessionText.append(agent.active() ? "ACTIVE  " : "ENDED   ")
                    .append(agent.name())
                    .append("  ")
                    .append(agent.id())
                    .append(System.lineSeparator())
                    .append("  started: ").append(agent.startedAt())
                    .append("  last activity: ").append(agent.lastActivityAt())
                    .append(System.lineSeparator());
        }
        agents.setText(sessionText.toString());
        agents.setCaretPosition(0);

        StringBuilder editorText = new StringBuilder();
        for (StudioEditorState editor : editorStates) {
            editorText.append(editor.dirty() ? "UNSAVED  " : "clean    ")
                    .append(editor.path())
                    .append(System.lineSeparator())
                    .append("  desktop: ").append(editor.clientSessionId())
                    .append("  base: ").append(shortHash(editor.baseSha256()))
                    .append(System.lineSeparator());
        }
        editors.setText(editorText.toString());
        editors.setCaretPosition(0);
        status.setText(
                "Activity sequence " + activitySequence
                        + " — agents " + agentSessions.stream().filter(StudioAgentSession::active).count()
                        + " active — editors " + editorStates.size()
        );
        refreshRunning = false;
    }

    private static String format(StudioActivity event) {
        StringBuilder line = new StringBuilder()
                .append(event.sequence())
                .append("  ")
                .append(event.timestamp())
                .append("  ")
                .append(event.clientKind())
                .append("  ")
                .append(event.operation());
        if (!event.target().isBlank()) {
            line.append("  ").append(event.target());
        }
        if (!event.detail().isBlank()) {
            line.append(" — ").append(event.detail());
        }
        return line.append(System.lineSeparator()).toString();
    }

    private void trimActivity() {
        int excess = activity.getDocument().getLength() - MAX_ACTIVITY_CHARS;
        if (excess <= 0) {
            return;
        }
        try {
            activity.getDocument().remove(0, excess);
        } catch (javax.swing.text.BadLocationException ignored) {
            activity.setText(activity.getText());
        }
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
