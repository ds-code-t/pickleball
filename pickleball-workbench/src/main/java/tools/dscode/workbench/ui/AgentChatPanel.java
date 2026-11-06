package tools.dscode.workbench.ui;

import tools.dscode.workbench.WorkbenchSessionActions;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/** Chat tab. It only forwards chat-send, chat-history, and chat-poll for the selected run. */
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
            show(history(actions, 50));
        });
        row.add(send, BorderLayout.EAST);
        add(row, BorderLayout.SOUTH);
        show(history(actions, 50));
        Timer timer = new Timer(1000, event -> show(poll(actions, "")));
        timer.setRepeats(true);
        timer.start();
    }

    public static Map<String, Object> send(WorkbenchSessionActions actions, String text, String runId, String source) {
        String run = selectedRun(actions, runId);
        if (run == null) return refused("chat-send");
        Map<String, String> args = new LinkedHashMap<>();
        args.put("text", text == null ? "" : text);
        args.put("source", source == null || source.isBlank() ? "human" : source);
        args.put("runId", run);
        args.put("channel", "run");
        return actions.dispatch("chat-send", args);
    }

    public static Map<String, Object> history(WorkbenchSessionActions actions, int limit) {
        String run = selectedRun(actions, null);
        if (run == null) return refused("chat-history");
        Map<String, String> args = new LinkedHashMap<>();
        args.put("limit", Integer.toString(limit));
        args.put("runId", run);
        args.put("channel", "run");
        return actions.dispatch("chat-history", args);
    }

    public static Map<String, Object> poll(WorkbenchSessionActions actions, String after) {
        String run = selectedRun(actions, null);
        if (run == null) return refused("chat-poll");
        Map<String, String> args = new LinkedHashMap<>();
        if (after != null && !after.isBlank()) args.put("id", after);
        args.put("runId", run);
        args.put("channel", "run");
        return actions.dispatch("chat-poll", args);
    }

    private void show(Map<String, Object> body) {
        history.setText(body == null ? "" : String.valueOf(body));
    }

    private static String selectedRun(WorkbenchSessionActions actions, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (actions == null) return null;
        String selected = actions.selectedDiagnosticRun();
        if (selected == null || selected.isBlank()) return null;
        return selected;
    }

    private static Map<String, Object> refused(String op) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("op", op);
        body.put("error", "chat requires a selected run");
        return body;
    }
}
