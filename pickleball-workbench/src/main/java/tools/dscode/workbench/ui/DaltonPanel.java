package tools.dscode.workbench.ui;

import tools.dscode.workbench.dalton.DaltonBrowser;
import tools.dscode.workbench.dalton.DaltonCliForwarder;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Dalton browse and edit tab. Every button shells out through {@link DaltonCliForwarder}.
 */
final class DaltonPanel extends JPanel {
    @FunctionalInterface
    interface Forwarder {
        DaltonCliForwarder.Result run(Path root, List<String> args);
    }

    private final Supplier<Path> project;
    private final Forwarder forwarder;
    private final JComboBox<String> view = new JComboBox<>(new String[] {
            "Files", "Features", "Scenarios", "Steps", "Entities", "History"
    });
    private final JTextField featurePath = new JTextField(28);
    private final JTextField scenario = new JTextField(12);
    private final JTextField entityType = new JTextField(10);
    private final JTextField entityId = new JTextField(10);
    private final JTextField entityVersion = new JTextField(4);
    private final DefaultListModel<String> rows = new DefaultListModel<>();
    private final JList<String> list = new JList<>(rows);
    private final JTextArea editor = new JTextArea();
    private final JTextArea conflicts = new JTextArea(4, 20);
    private final JLabel status = new JLabel("Dalton");
    private final JButton refresh = WorkbenchTheme.flatButton("Refresh", "Read this view from the Dalton CLI");
    private final JButton load = WorkbenchTheme.flatButton("Load", "Show the feature text or entity");
    private final JButton saveFeature = WorkbenchTheme.accentButton("Save feature", "dalton feature put of the editor text");
    private final JButton saveEntity = WorkbenchTheme.flatButton("Save entity", "dalton entity put of the editor text");

    DaltonPanel(Supplier<Path> project) {
        this(project, DaltonCliForwarder::run);
    }

    DaltonPanel(Supplier<Path> project, Forwarder forwarder) {
        super(new BorderLayout(8, 8));
        this.project = project;
        this.forwarder = forwarder;
        editor.setLineWrap(false);
        conflicts.setEditable(false);
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
        north.add(button("Status", "dalton status", () -> run(DaltonBrowser.status(), false)));
        north.add(button("Hydrate", "dalton hydrate", () -> run(DaltonBrowser.hydrate(false), false)));
        north.add(button("Extract", "dalton extract", () -> run(DaltonBrowser.extract(false), false)));
        north.add(button("Conflicts", "dalton conflicts", () -> run(DaltonBrowser.conflicts(), true)));
        north.add(button("Run", "dalton run", () -> run(DaltonBrowser.run(false), false)));
        north.add(view);
        north.add(new JLabel("Feature"));
        north.add(featurePath);
        north.add(new JLabel("Scenario"));
        north.add(scenario);
        north.add(refresh);
        refresh.addActionListener(event -> refreshView());
        JPanel editorBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        editorBar.add(new JLabel("Entity"));
        editorBar.add(entityType);
        editorBar.add(entityId);
        editorBar.add(new JLabel("version"));
        editorBar.add(entityVersion);
        editorBar.add(load);
        editorBar.add(saveFeature);
        editorBar.add(saveEntity);
        load.addActionListener(event -> loadEditor());
        saveFeature.addActionListener(event -> saveFeature());
        saveEntity.addActionListener(event -> saveEntity());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(editor));
        split.setResizeWeight(0.45);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(editorBar, BorderLayout.NORTH);
        south.add(new JScrollPane(conflicts), BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    private void refreshView() {
        String selected = String.valueOf(view.getSelectedItem());
        List<String> args = switch (selected) {
            case "Features" -> DaltonBrowser.features();
            case "Scenarios" -> DaltonBrowser.scenarios(featurePath.getText());
            case "Steps" -> DaltonBrowser.steps(featurePath.getText(), scenario.getText());
            case "Entities" -> DaltonBrowser.entities();
            case "History" -> DaltonBrowser.history();
            default -> DaltonBrowser.files();
        };
        run(args, false);
    }

    private void loadEditor() {
        String type = entityType.getText().trim();
        String id = entityId.getText().trim();
        if (!type.isEmpty() && !id.isEmpty()) {
            run(DaltonBrowser.entityGet(type, id), false);
            return;
        }
        String path = featurePath.getText().trim();
        if (path.isEmpty()) {
            status.setText("Enter a feature path or an entity type and id");
            return;
        }
        run(path.endsWith(".feature") ? DaltonBrowser.featureShow(path) : DaltonBrowser.fileShow(path), false);
    }

    private void saveFeature() {
        String path = featurePath.getText().trim();
        if (!path.endsWith(".feature")) {
            status.setText("Feature path must end with .feature");
            return;
        }
        try {
            Path file = Files.createTempFile("dalton-feature", ".feature");
            Files.writeString(file, editor.getText(), StandardCharsets.UTF_8);
            run(DaltonBrowser.featurePut(path, file), false);
        } catch (Exception failure) {
            status.setText("error: " + failure.getMessage());
        }
    }

    private void saveEntity() {
        String type = entityType.getText().trim();
        String id = entityId.getText().trim();
        if (type.isEmpty() || id.isEmpty()) {
            status.setText("Entity type and id are required");
            return;
        }
        try {
            Path file = Files.createTempFile("dalton-entity", ".json");
            Files.writeString(file, editor.getText(), StandardCharsets.UTF_8);
            run(DaltonBrowser.entityPut(type, id, entityVersion.getText(), file), false);
        } catch (Exception failure) {
            status.setText("error: " + failure.getMessage());
        }
    }

    private JButton button(String title, String tip, Runnable action) {
        JButton button = WorkbenchTheme.flatButton(title, tip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void run(List<String> args, boolean conflictsView) {
        setBusy(true);
        status.setText(String.join(" ", args));
        Path root = project.get();
        new SwingWorker<DaltonCliForwarder.Result, Void>() {
            @Override
            protected DaltonCliForwarder.Result doInBackground() {
                return forwarder.run(root, args);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    show(get(), conflictsView);
                } catch (Exception failure) {
                    status.setText("error: " + failure.getMessage());
                }
            }
        }.execute();
    }

    private void show(DaltonCliForwarder.Result result, boolean conflictsView) {
        String body = result.out() == null ? "" : result.out();
        if (conflictsView || body.contains("\"conflicts\"")) {
            conflicts.setText(String.join("\n", DaltonBrowser.labels(body)));
        } else {
            rows.clear();
            for (String line : DaltonBrowser.labels(body)) rows.addElement(line);
            if (body.trim().startsWith("{")) {
                editor.setText(DaltonBrowser.editableText(body));
                editor.setEditable(!DaltonBrowser.binary(body));
            }
        }
        String err = result.err() == null ? "" : result.err().trim();
        status.setText(result.exit() == 0 ? "exit 0" + enabledHint(body) : (err.isEmpty() ? "exit " + result.exit() : err));
    }

    static String enabledHint(String body) {
        if (body == null || !body.contains("\"enabledSource\"")) return "";
        String source = quotedAfter(body, "\"enabledSource\"");
        String enabled = booleanAfter(body, "\"enabled\"");
        if (enabled.isEmpty() && source.isEmpty()) return "";
        return " enabled=" + enabled + " source=" + source;
    }

    private static String quotedAfter(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return "";
        int colon = body.indexOf(':', at + key.length());
        if (colon < 0) return "";
        int open = body.indexOf('"', colon + 1);
        if (open < 0) return "";
        int close = body.indexOf('"', open + 1);
        if (close < 0) return "";
        return body.substring(open + 1, close);
    }

    private static String booleanAfter(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) return "";
        int colon = body.indexOf(':', at + key.length());
        if (colon < 0) return "";
        String rest = body.substring(colon + 1).trim();
        if (rest.startsWith("true")) return "true";
        if (rest.startsWith("false")) return "false";
        return "";
    }

    private void setBusy(boolean busy) {
        refresh.setEnabled(!busy);
        load.setEnabled(!busy);
        saveFeature.setEnabled(!busy);
        saveEntity.setEnabled(!busy);
    }
}
