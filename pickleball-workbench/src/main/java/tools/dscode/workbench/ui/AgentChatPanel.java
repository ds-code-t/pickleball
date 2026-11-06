package tools.dscode.workbench.ui;

import tools.dscode.workbench.WorkbenchSessionActions;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/** Chat tab. It only forwards chat-send, chat-history, and chat-poll. */
public final class AgentChatPanel extends JPanel {
    private final WorkbenchSessionActions actions;
    private final JTextArea history = new JTextArea(12, 40);
    private final JTextField input = new JTextField();

    AgentChatPanel(WorkbenchSessionActions actions) {
        super(new BorderLayout());
        this.actions = actions;
        history.setEditable(false);
        history.setLineWrap(true);
        add(new JScrollPane(history), BorderLayout.CENTER);
        JPanel row = new JPanel(new BorderLayout());
        row.add(input, BorderLayout.CENTER);
        JButton send = new JButton("Send");
        send.addActionListener(event -> {
            send(actions, input.getText(), null, "human");
            input.setText("");
            history.setText(String.valueOf(history(actions, 50)));
        });
        row.add(send, BorderLayout.EAST);
        add(row, BorderLayout.SOUTH);
    }

    public static Map<String, Object> send(WorkbenchSessionActions actions, String text, String runId, String source) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("text", text == null ? "" : text);
        args.put("source", source == null || source.isBlank() ? "human" : source);
        if (runId != null && !runId.isBlank()) args.put("runId", runId);
        return actions.dispatch("chat-send", args);
    }

    public static Map<String, Object> history(WorkbenchSessionActions actions, int limit) {
        return actions.dispatch("chat-history", Map.of("limit", Integer.toString(limit)));
    }

    public static Map<String, Object> poll(WorkbenchSessionActions actions, String after) {
        Map<String, String> args = new LinkedHashMap<>();
        if (after != null && !after.isBlank()) args.put("id", after);
        return actions.dispatch("chat-poll", args);
    }
}
