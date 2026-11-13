package tools.dscode.workbench.ui;

import tools.dscode.workbench.dalton.DaltonBrowser;
import tools.dscode.workbench.dalton.DaltonCliForwarder;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Unified editor for categories, config files, scenarios, steps, and glue.
 * Every action is a {@link DaltonCliForwarder} argument list. Deletes are confirmed
 * before {@code --confirm} is sent. This panel does not open SQLite.
 */
final class EditPanel extends JPanel {
    @FunctionalInterface
    interface Forwarder {
        DaltonCliForwarder.Result run(Path root, List<String> args);
    }

    @FunctionalInterface
    interface Confirmer {
        boolean confirm(String message);
    }

    private final Supplier<Path> project;
    private final Forwarder forwarder;
    private final Confirmer confirmer;
    private final JComboBox<String> surface = new JComboBox<>(new String[] {
            "Categories", "Files", "Scenarios", "Steps", "Glue"
    });
    private final JTextField path = new JTextField(24);
    private final JTextField scenario = new JTextField(12);
    private final JTextField step = new JTextField(6);
    private final JTextField name = new JTextField(12);
    private final DefaultListModel<String> rows = new DefaultListModel<>();
    private final JList<String> list = new JList<>(rows);
    private final JTextArea editor = new JTextArea();
    private final JLabel status = new JLabel("Editor");
    private final JButton refresh = WorkbenchTheme.flatButton("Refresh", "List this surface through the Dalton CLI");
    private final JButton load = WorkbenchTheme.flatButton("Load", "Show the selected entity through the Dalton CLI");
    private final JButton save = WorkbenchTheme.accentButton("Save", "Validate and write through the Dalton CLI");
    private final JButton delete = WorkbenchTheme.flatButton("Delete", "Confirm, then delete through the Dalton CLI");

    EditPanel(Supplier<Path> project) {
        this(project, DaltonCliForwarder::run, EditPanel::confirmDialog);
    }

    EditPanel(Supplier<Path> project, Forwarder forwarder, Confirmer confirmer) {
        super(new BorderLayout(8, 8));
        this.project = project;
        this.forwarder = forwarder;
        this.confirmer = confirmer;
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
        north.add(surface);
        north.add(new JLabel("Path"));
        north.add(path);
        north.add(new JLabel("Name"));
        north.add(name);
        north.add(new JLabel("Scenario"));
        north.add(scenario);
        north.add(new JLabel("Step"));
        north.add(step);
        north.add(refresh);
        north.add(load);
        north.add(save);
        north.add(delete);
        refresh.addActionListener(event -> refresh());
        load.addActionListener(event -> load());
        save.addActionListener(event -> save());
        delete.addActionListener(event -> delete());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(editor));
        split.setResizeWeight(0.4);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private void refresh() {
        List<String> args = switch (String.valueOf(surface.getSelectedItem())) {
            case "Files" -> DaltonBrowser.files();
            case "Scenarios" -> DaltonBrowser.scenarios(path.getText());
            case "Steps" -> DaltonBrowser.steps(path.getText(), scenario.getText());
            case "Glue" -> DaltonBrowser.glueList();
            default -> DaltonBrowser.categoryList();
        };
        run(args);
    }

    private void load() {
        String selected = String.valueOf(surface.getSelectedItem());
        try {
            List<String> args = switch (selected) {
                case "Files" -> DaltonBrowser.fileShow(required(path.getText(), "a path"));
                case "Scenarios", "Steps" -> DaltonBrowser.featureShow(required(path.getText(), "a feature path"));
                case "Glue" -> DaltonBrowser.glueShow(required(path.getText(), "a glue path"));
                default -> DaltonBrowser.categoryShow(required(id(), "a category id"));
            };
            run(args);
        } catch (IllegalArgumentException failure) {
            status.setText(failure.getMessage());
        }
    }

    private void save() {
        try {
            Path file = Files.createTempFile("pickleball-edit", ".txt");
            Files.writeString(file, editor.getText(), StandardCharsets.UTF_8);
            String selected = String.valueOf(surface.getSelectedItem());
            List<String> args = switch (selected) {
                case "Files" -> DaltonBrowser.filePut(required(path.getText(), "a path"), file);
                case "Scenarios" -> scenario.getText().isBlank()
                        ? DaltonBrowser.scenarioAdd(required(path.getText(), "a feature path"), required(name.getText(), "a scenario name"), file)
                        : DaltonBrowser.scenarioModify(required(path.getText(), "a feature path"), scenario.getText().trim(), file);
                case "Steps" -> step.getText().isBlank()
                        ? DaltonBrowser.stepAdd(required(path.getText(), "a feature path"), required(scenario.getText(), "a scenario"), file)
                        : DaltonBrowser.stepModify(required(path.getText(), "a feature path"), required(scenario.getText(), "a scenario"), step.getText().trim(), file);
                case "Glue" -> DaltonBrowser.gluePut(required(path.getText(), "a glue path"), file);
                default -> DaltonBrowser.categoryPut(required(id(), "a category id"), file);
            };
            run(args);
        } catch (IllegalArgumentException failure) {
            status.setText(failure.getMessage());
        } catch (Exception failure) {
            status.setText("error: " + failure.getMessage());
        }
    }

    private void delete() {
        String selected = String.valueOf(surface.getSelectedItem());
        String target = switch (selected) {
            case "Steps" -> path.getText() + " step " + step.getText();
            case "Scenarios" -> path.getText() + " scenario " + scenario.getText();
            default -> path.getText().isBlank() ? name.getText() : path.getText();
        };
        if (!confirmer.confirm("Delete " + target + "?")) {
            status.setText("delete cancelled");
            return;
        }
        try {
            List<String> args = switch (selected) {
                case "Files" -> DaltonBrowser.fileDelete(required(path.getText(), "a path"));
                case "Scenarios" -> DaltonBrowser.scenarioDelete(required(path.getText(), "a feature path"), required(scenario.getText(), "a scenario"));
                case "Steps" -> DaltonBrowser.stepDelete(required(path.getText(), "a feature path"), required(scenario.getText(), "a scenario"), required(step.getText(), "a step"));
                case "Glue" -> DaltonBrowser.glueRemove(required(path.getText(), "a glue path"));
                default -> DaltonBrowser.categoryRemove(required(id(), "a category id"));
            };
            run(args);
        } catch (IllegalArgumentException failure) {
            status.setText(failure.getMessage());
        }
    }

    private String id() {
        if (!name.getText().isBlank()) return name.getText().trim();
        return path.getText().trim();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Enter " + label);
        return value.trim();
    }

    private static boolean confirmDialog(String message) {
        if (GraphicsEnvironment.isHeadless()) return false;
        return JOptionPane.showConfirmDialog(null, message, "Confirm delete", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void run(List<String> args) {
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
                    show(get());
                } catch (Exception failure) {
                    status.setText("error: " + failure.getMessage());
                }
            }
        }.execute();
    }

    private void show(DaltonCliForwarder.Result result) {
        String body = result.out() == null ? "" : result.out();
        rows.clear();
        for (String line : DaltonBrowser.labels(body)) rows.addElement(line);
        if (body.trim().startsWith("{")) {
            editor.setText(DaltonBrowser.editableText(body));
        }
        String err = result.err() == null ? "" : result.err().trim();
        status.setText(result.exit() == 0 ? "exit 0" : (err.isEmpty() ? "exit " + result.exit() : err));
    }

    private void setBusy(boolean busy) {
        refresh.setEnabled(!busy);
        load.setEnabled(!busy);
        save.setEnabled(!busy);
        delete.setEnabled(!busy);
    }
}
