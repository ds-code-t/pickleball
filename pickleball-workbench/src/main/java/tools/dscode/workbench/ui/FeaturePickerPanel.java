package tools.dscode.workbench.ui;

import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;
import tools.dscode.workbench.catalog.ScenarioFilter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Left-rail scenario picker. Name and tag filters are the primary controls;
 * feature-file selection is a collapsed secondary filter.
 */
final class FeaturePickerPanel extends JPanel {
    private final DefaultListModel<ConsumerFeatureCatalog.FeatureEntry> featureModel = new DefaultListModel<>();
    private final DefaultListModel<ConsumerFeatureCatalog.ScenarioEntry> scenarioModel = new DefaultListModel<>();
    private final JList<ConsumerFeatureCatalog.FeatureEntry> featureList = new JList<>(featureModel);
    private final JList<ConsumerFeatureCatalog.ScenarioEntry> scenarioList = new JList<>(scenarioModel);
    private final JTextField nameField = new JTextField();
    private final JComboBox<ScenarioFilter.NameMatchMode> nameMatchMode =
            new JComboBox<>(ScenarioFilter.NameMatchMode.values());
    private final JTextField includeTags = new JTextField();
    private final JTextField excludeTags = new JTextField();
    private final JToggleButton featureNameMode = new JToggleButton("Feature name");
    private final JToggleButton filePathMode = new JToggleButton("File path");
    private final JToggleButton featureFilterToggle = new JToggleButton("Filter by feature");
    private final JPanel featureFilterPanel = new JPanel(new BorderLayout(0, 4));
    private final JButton saveButton = WorkbenchTheme.flatButton("Save", "Write the live buffer back to the loaded .feature file");
    private final JLabel status = WorkbenchTheme.muted("Showing all project scenarios");

    private ConsumerFeatureCatalog catalog;
    private Consumer<ConsumerFeatureCatalog.ScenarioEntry> onScenario;
    private Runnable onSave;
    private boolean saveEnabled;
    private boolean locked;

    FeaturePickerPanel() {
        super(new BorderLayout(8, 8));
        WorkbenchTheme.surface(this);
        setBorder(WorkbenchTheme.cardBorder());
        setPreferredSize(new Dimension(300, 640));
        setMinimumSize(new Dimension(240, 320));

        add(northChrome(), BorderLayout.NORTH);
        add(labeled("Scenarios", scenarioList), BorderLayout.CENTER);
        add(southChrome(), BorderLayout.SOUTH);

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
            if (value != null) {
                String tags = value.effectiveTags().isEmpty()
                        ? ""
                        : "  @" + String.join(" @", value.effectiveTags());
                label.setToolTipText(value.featureName() + " — " + value.relativePath() + tags);
            }
            return label;
        });
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
        nameMatchMode.setSelectedItem(ScenarioFilter.DEFAULT_NAME_MATCH);
        nameMatchMode.setToolTipText("Case-insensitive match against the Scenario / Scenario Outline title.");
        nameMatchMode.addActionListener(event -> filterChanged());
        listen(nameField);
        listen(includeTags);
        listen(excludeTags);
        nameField.setToolTipText("Filter by scenario name. All four match modes are case-insensitive.");
        includeTags.setToolTipText("Tags the scenario must have (AND). With or without @; split on commas or spaces. Empty means no include constraint.");
        excludeTags.setToolTipText("Tags the scenario must not have (NOT). Any listed tag drops it. Empty means no exclude constraint.");
        saveButton.setEnabled(false);
        saveButton.addActionListener(event -> {
            if (onSave != null && !locked) onSave.run();
        });
        featureFilterToggle.setFocusable(false);
        featureFilterToggle.setToolTipText("Optional. Leave collapsed to apply name/tag filters to every catalog scenario.");
        featureFilterToggle.addActionListener(event -> {
            featureFilterPanel.setVisible(featureFilterToggle.isSelected());
            revalidate();
            repaint();
        });
        featureNameMode.setSelected(true);
        featureNameMode.addActionListener(event -> setMode(ConsumerFeatureCatalog.BrowseMode.FEATURE_NAME));
        filePathMode.addActionListener(event -> setMode(ConsumerFeatureCatalog.BrowseMode.FILE_PATH));
        featureFilterPanel.setVisible(false);
    }

    void setCatalog(ConsumerFeatureCatalog catalog) {
        this.catalog = catalog;
        featureModel.clear();
        if (catalog == null) {
            refreshScenarios();
            return;
        }
        applyFiltersFromUi();
        for (ConsumerFeatureCatalog.FeatureEntry feature : catalog.featuresForBrowse()) {
            featureModel.addElement(feature);
        }
        syncFeatureSelection();
        refreshScenarios();
    }

    private void syncFeatureSelection() {
        if (catalog == null) return;
        featureList.clearSelection();
        for (int i = 0; i < featureModel.size(); i++) {
            if (catalog.selected(featureModel.get(i).file())) {
                featureList.addSelectionInterval(i, i);
            }
        }
    }

    void onScenarioSelected(Consumer<ConsumerFeatureCatalog.ScenarioEntry> onScenario) {
        this.onScenario = onScenario;
    }

    void onSave(Runnable onSave) {
        this.onSave = onSave;
    }

    void setSaveEnabled(boolean enabled) {
        saveEnabled = enabled;
        saveButton.setEnabled(enabled && !locked);
    }

    void setLocked(boolean locked) {
        this.locked = locked;
        saveButton.setEnabled(saveEnabled && !locked);
        featureList.setEnabled(!locked);
        scenarioList.setEnabled(!locked);
        nameField.setEnabled(!locked);
        nameMatchMode.setEnabled(!locked);
        includeTags.setEnabled(!locked);
        excludeTags.setEnabled(!locked);
        featureFilterToggle.setEnabled(!locked);
        featureNameMode.setEnabled(!locked);
        filePathMode.setEnabled(!locked);
    }

    private JPanel northChrome() {
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.add(WorkbenchTheme.heading("Scenarios"), BorderLayout.WEST);
        header.add(saveButton, BorderLayout.EAST);
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height + 8));
        north.add(header);
        north.add(Box.createVerticalStrut(8));

        JPanel nameRow = new JPanel(new BorderLayout(6, 0));
        nameRow.setOpaque(false);
        nameRow.add(nameField, BorderLayout.CENTER);
        nameMatchMode.setMaximumSize(nameMatchMode.getPreferredSize());
        nameRow.add(nameMatchMode, BorderLayout.EAST);
        north.add(labeledField("Scenario name", nameRow));
        north.add(Box.createVerticalStrut(6));
        north.add(labeledField("Must have all tags", includeTags));
        north.add(Box.createVerticalStrut(6));
        north.add(labeledField("Must not have tags", excludeTags));
        north.add(Box.createVerticalStrut(8));
        return north;
    }

    private JPanel southChrome() {
        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));

        featureFilterToggle.setAlignmentX(LEFT_ALIGNMENT);
        featureFilterToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, featureFilterToggle.getPreferredSize().height));
        south.add(featureFilterToggle);
        south.add(Box.createVerticalStrut(4));

        JPanel modes = new JPanel(new GridLayout(1, 2, 4, 0));
        modes.setOpaque(false);
        modes.add(featureNameMode);
        modes.add(filePathMode);
        featureFilterPanel.setOpaque(false);
        featureFilterPanel.setAlignmentX(LEFT_ALIGNMENT);
        featureFilterPanel.add(modes, BorderLayout.NORTH);
        JScrollPane featureScroll = new JScrollPane(featureList);
        featureScroll.setBorder(BorderFactory.createLineBorder(WorkbenchTheme.BORDER));
        featureScroll.setPreferredSize(new Dimension(240, 140));
        featureFilterPanel.add(featureScroll, BorderLayout.CENTER);
        south.add(featureFilterPanel);
        south.add(Box.createVerticalStrut(6));
        status.setAlignmentX(LEFT_ALIGNMENT);
        south.add(status);
        return south;
    }

    private JPanel labeledField(String title, JTextField field) {
        JPanel wrap = new JPanel(new BorderLayout(0, 0));
        wrap.setOpaque(false);
        wrap.add(field, BorderLayout.CENTER);
        return labeledField(title, wrap);
    }

    private JPanel labeledField(String title, JPanel field) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(WorkbenchTheme.muted(title), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height + 18));
        return panel;
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

    private void listen(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { filterChanged(); }
        });
    }

    private void filterChanged() {
        if (catalog == null) return;
        applyFiltersFromUi();
        refreshScenarios();
    }

    private void applyFiltersFromUi() {
        if (catalog == null) return;
        ScenarioFilter filter = catalog.filter();
        filter.setNameQuery(nameField.getText());
        Object selected = nameMatchMode.getSelectedItem();
        filter.setNameMatchMode(selected instanceof ScenarioFilter.NameMatchMode mode
                ? mode
                : ScenarioFilter.DEFAULT_NAME_MATCH);
        filter.setIncludeTagsQuery(includeTags.getText());
        filter.setExcludeTagsQuery(excludeTags.getText());
    }

    private void refreshScenarios() {
        scenarioModel.clear();
        if (catalog == null) {
            status.setText("No synchronized consumer project.");
            return;
        }
        int candidates = catalog.candidateScenarios().size();
        for (ConsumerFeatureCatalog.ScenarioEntry scenario : catalog.visibleScenarios()) {
            scenarioModel.addElement(scenario);
        }
        int visible = scenarioModel.size();
        String featureNote = catalog.selectedFeatureFiles().isEmpty()
                ? "all features"
                : catalog.selectedFeatureFiles().size() + " feature(s)";
        if (visible == candidates) {
            status.setText(visible + " scenario(s) · " + featureNote);
        } else {
            status.setText(visible + " of " + candidates + " scenario(s) · " + featureNote);
        }
    }
}
