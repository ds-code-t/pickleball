package tools.dscode.workbench.ui;

import tools.dscode.control.protocol.Workspace;
import tools.dscode.workbench.discover.LastDiscoverSnapshot;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collapsible editor for one run's workspace {@code runvars.properties}.
 * Save writes that file atomically. A change on disk since load is a warning
 * and is not overwritten. There is no sealed snapshot.
 */
final class RunVarOverridePanel extends JPanel {
    static final List<String> BROWSER_CHOICES = List.of(
            "CHROME_HEADLESS", "chrome", "firefox", "edge", "safari"
    );
    static final List<String> REPORTING_MODE_CHOICES = List.of("diagnostic", "standard");
    static final List<String> RETENTION_CHOICES = List.of("all", "failed", "none");
    static final List<String> LOG_LEVEL_CHOICES = List.of("trace", "debug", "info", "warn", "error");
    static final List<String> GIT_SNAPSHOT_CHOICES = List.of("metadata", "diff", "none");
    static final List<String> PARALLEL_CHOICES = List.of("auto", "1", "2", "4", "8", "16");

    private final Path projectRoot;
    private final JToggleButton toggle = new JToggleButton("Run vars");
    private final JPanel editor = new JPanel(new BorderLayout(6, 6));
    private final JPanel fields = new JPanel();
    private final JLabel status = WorkbenchTheme.muted("Edit the workspace run vars. Save writes runvars.properties.");
    private final List<FieldRow> rows = new ArrayList<>();
    private String runId = "";
    private Workspace.DiskStamp loadedStamp;

    RunVarOverridePanel(Path projectRoot) {
        this.projectRoot = projectRoot;
        setOpaque(false);
        setLayout(new BorderLayout(0, 4));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        toggle.setSelected(false);
        toggle.setFocusable(false);
        toggle.addActionListener(event -> editor.setVisible(toggle.isSelected()));
        JButton load = WorkbenchTheme.flatButton("Reload", "Reload run vars from the run workspace");
        load.addActionListener(event -> loadSnapshot());
        JButton save = WorkbenchTheme.flatButton("Save", "Write runvars.properties in the run workspace");
        save.addActionListener(event -> save());
        header.add(toggle, BorderLayout.WEST);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.add(load);
        actions.add(save);
        header.add(actions, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setBackground(WorkbenchTheme.SURFACE);
        JScrollPane scroll = new JScrollPane(fields);
        scroll.setPreferredSize(new Dimension(420, 160));
        WorkbenchTheme.styleScroll(scroll);
        editor.add(scroll, BorderLayout.CENTER);
        editor.add(status, BorderLayout.SOUTH);
        editor.setVisible(false);
        editor.setBackground(WorkbenchTheme.SURFACE);
        editor.setBorder(WorkbenchTheme.cardBorder());
        add(editor, BorderLayout.CENTER);
        loadSnapshot();
    }

    void loadSnapshot() {
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.read(projectRoot);
        runId = snapshot == null || snapshot.runId() == null ? "" : snapshot.runId().trim();
        Map<String, String> values = new TreeMap<>();
        Path workspace = runId.isEmpty() ? null : Workspace.directory(projectRoot, runId);
        if (workspace != null && Workspace.exists(workspace)) {
            values.putAll(Workspace.readRunVars(workspace));
            loadedStamp = Workspace.stamp(workspace);
            status.setText("Editing workspace run vars for " + runId + ". Save writes that file.");
        } else if (snapshot != null && snapshot.present()) {
            if (snapshot.runVars() != null) values.putAll(snapshot.runVars());
            loadedStamp = null;
            status.setText(runId.isEmpty()
                    ? "Discover snapshot has no run id, so there is no workspace to edit."
                    : "No workspace yet for " + runId + ". Seed before editing.");
        } else {
            loadedStamp = null;
            status.setText("No snapshot yet. Discover first.");
        }
        rebuildFields(values);
    }

    void save() {
        if (runId.isEmpty()) {
            status.setText("No run is open. Nothing was written.");
            return;
        }
        Path workspace = Workspace.directory(projectRoot, runId);
        if (!Workspace.exists(workspace)) {
            status.setText("No workspace yet. Seed before editing. Nothing was written.");
            return;
        }
        if (loadedStamp != null && Workspace.changedSince(workspace, loadedStamp)) {
            status.setText("WARNING: workspace changed on disk. Reload before saving. Nothing was written.");
            return;
        }
        Workspace.writeRunVars(workspace, currentValues());
        loadedStamp = Workspace.stamp(workspace);
        status.setText("Saved runvars.properties in the workspace for " + runId + ".");
    }

    String statusText() {
        return status.getText();
    }

    Map<String, String> currentValues() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (FieldRow row : rows) {
            values.put(row.key(), row.value());
        }
        return values;
    }

    private void rebuildFields(Map<String, String> values) {
        fields.removeAll();
        rows.clear();
        Map<String, String> shown = new TreeMap<>();
        if (values != null) shown.putAll(values);
        if (values != null && !values.isEmpty()) {
            for (String key : HTML_REPORT_KEYS) {
                shown.putIfAbsent(key, "false");
            }
        }
        if (shown.isEmpty()) {
            fields.add(WorkbenchTheme.muted("No retained RunVars."));
        } else {
            shown.forEach((key, value) -> {
                FieldRow row = FieldRow.create(key, value == null ? "" : value);
                rows.add(row);
                fields.add(row.component());
                fields.add(Box.createVerticalStrut(4));
            });
        }
        fields.revalidate();
        fields.repaint();
    }

    static Kind kindFor(String key, String value) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (BOOLEAN_KEYS.contains(normalized) || booleanValue(value)) {
            return Kind.BOOLEAN;
        }
        if (enumChoices(normalized) != null) {
            return Kind.ENUM;
        }
        return Kind.TEXT;
    }

    static List<String> enumChoices(String normalizedKey) {
        return switch (normalizedKey) {
            case "pkb_browser" -> BROWSER_CHOICES;
            case "pkb_reportingmode" -> REPORTING_MODE_CHOICES;
            case "pkb_reportretention" -> RETENTION_CHOICES;
            case "pkb_loglevel" -> LOG_LEVEL_CHOICES;
            case "pkb_gitsnapshot" -> GIT_SNAPSHOT_CHOICES;
            case "pkb_parallel" -> PARALLEL_CHOICES;
            default -> null;
        };
    }

    private static boolean booleanValue(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "false".equals(normalized)
                || "yes".equals(normalized) || "no".equals(normalized);
    }

    private static final java.util.Set<String> BOOLEAN_KEYS = java.util.Set.of(
            "pkb_debugbrowser",
            "pkb_rp_enable",
            "pkb_compositereport",
            "pkb_scenarioreport"
    );
    private static final List<String> HTML_REPORT_KEYS = List.of(
            "pkb_compositereport",
            "pkb_scenarioreport"
    );

    enum Kind { BOOLEAN, ENUM, TEXT }

    private static final class FieldRow {
        private final String key;
        private final Kind kind;
        private final JComponent editor;
        private final JPanel component;
        private final JCheckBox check;
        private final JComboBox<String> combo;
        private final JTextField text;

        private FieldRow(String key, Kind kind, JCheckBox check, JComboBox<String> combo, JTextField text) {
            this.key = key;
            this.kind = kind;
            this.check = check;
            this.combo = combo;
            this.text = text;
            this.editor = check != null ? check : combo != null ? combo : text;
            this.component = new JPanel(new BorderLayout(8, 0));
            component.setOpaque(false);
            JLabel label = new JLabel(key);
            label.setPreferredSize(new Dimension(160, 24));
            component.add(label, BorderLayout.WEST);
            component.add(editor, BorderLayout.CENTER);
        }

        static FieldRow create(String key, String value) {
            Kind kind = kindFor(key, value);
            return switch (kind) {
                case BOOLEAN -> {
                    JCheckBox box = new JCheckBox();
                    box.setSelected(isTrue(value));
                    yield new FieldRow(key, kind, box, null, null);
                }
                case ENUM -> {
                    List<String> choices = enumChoices(key.toLowerCase(Locale.ROOT));
                    JComboBox<String> combo = new JComboBox<>(choices.toArray(String[]::new));
                    combo.setEditable(true);
                    combo.setSelectedItem(value);
                    yield new FieldRow(key, kind, null, combo, null);
                }
                case TEXT -> {
                    JTextField field = new JTextField(value);
                    yield new FieldRow(key, kind, null, null, field);
                }
            };
        }

        String key() {
            return key;
        }

        String value() {
            return switch (kind) {
                case BOOLEAN -> Boolean.toString(check.isSelected());
                case ENUM -> String.valueOf(combo.getSelectedItem() == null ? "" : combo.getSelectedItem());
                case TEXT -> text.getText();
            };
        }

        JComponent component() {
            return component;
        }

        private static boolean isTrue(String value) {
            if (value == null) return false;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized);
        }
    }
}
