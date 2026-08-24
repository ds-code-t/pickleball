package tools.dscode.workbench.ui;

import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Expandable left-rail feature/scenario picker over the project-owned catalog. */
final class FeaturePickerPanel extends JPanel {
    private final DefaultListModel<ConsumerFeatureCatalog.FeatureEntry> featureModel = new DefaultListModel<>();
    private final DefaultListModel<ConsumerFeatureCatalog.ScenarioEntry> scenarioModel = new DefaultListModel<>();
    private final JList<ConsumerFeatureCatalog.FeatureEntry> featureList = new JList<>(featureModel);
    private final JList<ConsumerFeatureCatalog.ScenarioEntry> scenarioList = new JList<>(scenarioModel);
    private final JTextField search = new JTextField();
    private final JToggleButton featureNameMode = new JToggleButton("Feature name");
    private final JToggleButton filePathMode = new JToggleButton("File path");
    private final JButton saveButton = WorkbenchTheme.flatButton("Save", "Write the live buffer back to the loaded .feature file");
    private final JLabel status = WorkbenchTheme.muted("No features selected — search all project scenarios");

    private ConsumerFeatureCatalog catalog;
    private final Map<Path, ConsumerFeatureCatalog.FeatureEntry> featuresByPath = new LinkedHashMap<>();
    private Consumer<ConsumerFeatureCatalog.ScenarioEntry> onScenario;
    private Runnable onSave;
    private boolean syncing;
    private boolean locked;

    FeaturePickerPanel() {
        super(new BorderLayout(8, 8));
        WorkbenchTheme.surface(this);
        setBorder(WorkbenchTheme.cardBorder());
        setPreferredSize(new Dimension(280, 640));
        setMinimumSize(new Dimension(220, 320));

        add(header(), BorderLayout.NORTH);
        add(lists(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        featureList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        featureList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel label = new JLabel(value == null ? "" : value.browseLabel(catalog == null
                    ? ConsumerFeatureCatalog.BrowseMode.FEATURE_NAME
                    : catalog.browseMode()));
            label.setOpaque(true);
            label.setBorder(new EmptyBorder(4, 6, 4, 6));
            label.setBackground(selected ? WorkbenchTheme.ACCENT_SOFT : WorkbenchTheme.SURFACE);
            label.setForeground(WorkbenchTheme.TEXT);
            return label;
        });
        scenarioList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel label = new JLabel(value == null ? "" : value.displayLabel());
            label.setOpaque(true);
            label.setBorder(new EmptyBorder(4, 6, 4, 6));
            label.setBackground(selected ? WorkbenchTheme.PLAYHEAD : WorkbenchTheme.SURFACE);
            label.setForeground(WorkbenchTheme.TEXT);
            label.setToolTipText(value == null ? null : value.featureName() + " — " + value.relativePath());
            return label;
        });
        featureList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        featureList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (catalog == null || locked) return;
                int index = featureList.locationToIndex(event.getPoint());
                if (index < 0) return;
                ConsumerFeatureCatalog.FeatureEntry feature = featureModel.get(index);
                catalog.toggleFeature(feature.file());
                javax.swing.SwingUtilities.invokeLater(() -> {
                    syncFeatureSelection();
                    refreshScenarios();
                });
                event.consume();
            }
        });
        scenarioList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            ConsumerFeatureCatalog.ScenarioEntry selected = scenarioList.getSelectedValue();
            if (selected != null && onScenario != null && !locked) onScenario.accept(selected);
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { filterChanged(); }
        });
        saveButton.setEnabled(false);
        saveButton.addActionListener(event -> {
            if (onSave != null && !locked) onSave.run();
        });
    }

    void setCatalog(ConsumerFeatureCatalog catalog) {
        this.catalog = catalog;
        featuresByPath.clear();
        featureModel.clear();
        if (catalog == null) {
            refreshScenarios();
            return;
        }
        for (ConsumerFeatureCatalog.FeatureEntry feature : catalog.featuresForBrowse()) {
            featuresByPath.put(feature.file(), feature);
            featureModel.addElement(feature);
        }
        syncFeatureSelection();
        refreshScenarios();
    }

    private void syncFeatureSelection() {
        if (catalog == null) return;
        syncing = true;
        try {
            featureList.clearSelection();
            for (int i = 0; i < featureModel.size(); i++) {
                if (catalog.selected(featureModel.get(i).file())) {
                    featureList.addSelectionInterval(i, i);
                }
            }
        } finally {
            syncing = false;
        }
    }

    void onScenarioSelected(Consumer<ConsumerFeatureCatalog.ScenarioEntry> onScenario) {
        this.onScenario = onScenario;
    }

    void onSave(Runnable onSave) {
        this.onSave = onSave;
    }

    private boolean saveEnabled;

    void setSaveEnabled(boolean enabled) {
        saveEnabled = enabled;
        saveButton.setEnabled(enabled && !locked);
    }

    void setLocked(boolean locked) {
        this.locked = locked;
        saveButton.setEnabled(saveEnabled && !locked);
        featureList.setEnabled(!locked);
        scenarioList.setEnabled(!locked);
        search.setEnabled(!locked);
        featureNameMode.setEnabled(!locked);
        filePathMode.setEnabled(!locked);
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(6, 6));
        header.setOpaque(false);
        header.add(WorkbenchTheme.heading("Features"), BorderLayout.WEST);

        JPanel modes = new JPanel(new GridLayout(1, 2, 4, 0));
        modes.setOpaque(false);
        featureNameMode.setSelected(true);
        featureNameMode.addActionListener(event -> setMode(ConsumerFeatureCatalog.BrowseMode.FEATURE_NAME));
        filePathMode.addActionListener(event -> setMode(ConsumerFeatureCatalog.BrowseMode.FILE_PATH));
        modes.add(featureNameMode);
        modes.add(filePathMode);
        header.add(modes, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.setOpaque(false);
        search.setToolTipText("Filter scenarios. With no feature selected this searches the whole consumer project.");
        south.add(search, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(saveButton);
        south.add(actions, BorderLayout.EAST);
        header.add(south, BorderLayout.SOUTH);
        return header;
    }

    private JPanel lists() {
        JPanel lists = new JPanel(new GridLayout(2, 1, 0, 8));
        lists.setOpaque(false);
        lists.add(labeled("Project features", featureList));
        lists.add(labeled("Scenarios", scenarioList));
        return lists;
    }

    private JPanel labeled(String title, JList<?> list) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.add(WorkbenchTheme.muted(title), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(WorkbenchTheme.BORDER));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void setMode(ConsumerFeatureCatalog.BrowseMode mode) {
        featureNameMode.setSelected(mode == ConsumerFeatureCatalog.BrowseMode.FEATURE_NAME);
        filePathMode.setSelected(mode == ConsumerFeatureCatalog.BrowseMode.FILE_PATH);
        if (catalog != null) {
            catalog.setBrowseMode(mode);
            setCatalog(catalog);
        }
    }

    private void filterChanged() {
        if (catalog == null) return;
        catalog.setScenarioQuery(search.getText());
        refreshScenarios();
    }

    private void refreshScenarios() {
        scenarioModel.clear();
        if (catalog == null) {
            status.setText("No synchronized consumer project.");
            return;
        }
        for (ConsumerFeatureCatalog.ScenarioEntry scenario : catalog.visibleScenarios()) {
            scenarioModel.addElement(scenario);
        }
        if (catalog.selectedFeatureFiles().isEmpty()) {
            status.setText(catalog.scenarioQuery().isBlank()
                    ? "No features selected — showing all project scenarios"
                    : "Searching all project scenarios");
        } else {
            status.setText(catalog.selectedFeatureFiles().size() + " feature(s) selected");
        }
    }
}
