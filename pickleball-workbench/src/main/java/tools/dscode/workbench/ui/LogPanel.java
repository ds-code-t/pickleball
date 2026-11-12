package tools.dscode.workbench.ui;

import tools.dscode.workbench.log.LogCliForwarder;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Log dashboard tab. Every button shells out through {@link LogCliForwarder}.
 */
final class LogPanel extends JPanel {
    @FunctionalInterface
    interface Forwarder {
        LogCliForwarder.Result run(Path root, List<String> args);
    }

    private final Supplier<Path> project;
    private final Forwarder forwarder;
    private final JTextField file = new JTextField(24);
    private final JTextField lines = new JTextField("40", 4);
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel("Logs");

    LogPanel(Supplier<Path> project) {
        this(project, LogCliForwarder::run);
    }

    LogPanel(Supplier<Path> project, Forwarder forwarder) {
        super(new BorderLayout(8, 8));
        this.project = project;
        this.forwarder = forwarder;
        output.setEditable(false);
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
        north.add(button("List", () -> run(List.of("list"))));
        north.add(button("Tail", () -> run(tailArgs())));
        north.add(button("Show", () -> run(showArgs())));
        north.add(new JLabel("File"));
        north.add(file);
        north.add(new JLabel("Lines"));
        north.add(lines);
        north.add(status);
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(output), BorderLayout.CENTER);
    }

    private List<String> tailArgs() {
        List<String> args = new ArrayList<>();
        args.add("tail");
        String name = file.getText() == null ? "" : file.getText().trim();
        if (!name.isEmpty()) args.add("--file=" + name);
        String count = lines.getText() == null ? "" : lines.getText().trim();
        if (!count.isEmpty()) args.add("--lines=" + count);
        return args;
    }

    private List<String> showArgs() {
        List<String> args = new ArrayList<>();
        args.add("show");
        String name = file.getText() == null ? "" : file.getText().trim();
        if (!name.isEmpty()) args.add("--file=" + name);
        return args;
    }

    private JButton button(String title, Runnable action) {
        JButton button = WorkbenchTheme.flatButton(title, "log " + title.toLowerCase());
        button.addActionListener(event -> action.run());
        return button;
    }

    private void run(List<String> args) {
        status.setText(String.join(" ", args));
        Path root = project.get();
        new SwingWorker<LogCliForwarder.Result, Void>() {
            @Override
            protected LogCliForwarder.Result doInBackground() {
                return forwarder.run(root, args);
            }

            @Override
            protected void done() {
                LogCliForwarder.Result result;
                try {
                    result = get();
                } catch (Exception failure) {
                    status.setText("error: " + failure.getMessage());
                    return;
                }
                output.setText(result.out().isEmpty() ? result.err() : result.out());
                output.setCaretPosition(0);
                status.setText(result.exit() == 0 ? "exit 0" : "exit " + result.exit());
            }
        }.execute();
    }
}
