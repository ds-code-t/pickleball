package tools.dscode.workbench.ui;

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
 * Collapsible editor for the next worker's sealed RunVar snapshot.
 * Apply writes {@code pkb_overriderunvars} for the next launch only; it does not
 * mutate an in-flight worker. Unused, it changes no behavior.
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
    private final JToggleButton toggle = new JToggleButton("Sealed RunVars");
    private final JPanel editor = new JPanel(new BorderLayout(6, 6));
    private final JPanel fields = new JPanel();
    private final JLabel status = WorkbenchTheme.muted("Load LastDiscoverSnapshot, edit, Apply for next launch.");
    private final List<FieldRow> rows = new ArrayList<>();

    RunVarOverridePanel(Path projectRoot) {
        this.projectRoot = projectRoot;
        setOpaque(false);
        setLayout(new BorderLayout(0, 4));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        toggle.setSelected(false);
        toggle.setFocusable(false);
        toggle.addActionListener(event -> editor.setVisible(toggle.isSelected()));
        JButton load = WorkbenchTheme.flatButton("Load snapshot", "Load retained RunVars from LastDiscoverSnapshot");
        JButton apply = WorkbenchTheme.accentButton("Apply sealed", "Write a sealed snapshot used on the next worker start");
        load.addActionListener(event -> loadSnapshot());
        apply.addActionListener(event -> applySealed());
        header.add(toggle, BorderLayout.WEST);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.add(load);
        actions.add(apply);
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
        Map<String, String> values = new TreeMap<>();
        if (snapshot != null && snapshot.present()) {
            if (snapshot.runVars() != null) values.putAll(snapshot.runVars());
            if (values.isEmpty() && snapshot.runProfile() != null && !snapshot.runProfile().isBlank()) {
                values.putAll(LastDiscoverSnapshot.parseCompact(snapshot.runProfile()));
            }
        }
        rebuildFields(values);
        if (snapshot != null && snapshot.sealed()) {
            status.setText("Loaded sealed snapshot. Apply writes the next worker -Dpkb_overriderunvars.");
        } else if (snapshot != null && snapshot.present()) {
            status.setText("Loaded LastDiscoverSnapshot. Apply seals it for the next worker launch.");
        } else {
            status.setText("No snapshot yet. Discover first, or type a complete sealed map and Apply.");
        }
    }

    void applySealed() {
        Map<String, String> values = currentValues();
        LastDiscoverSnapshot.writeSealed(projectRoot, values);
        status.setText("Sealed snapshot saved. Next worker start uses -Dpkb_overriderunvars=. In-flight worker unchanged.");
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
        if (values.isEmpty()) {
            fields.add(WorkbenchTheme.muted("No retained RunVars."));
        } else {
            values.forEach((key, value) -> {
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
            "pkb_rp_enable"
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
