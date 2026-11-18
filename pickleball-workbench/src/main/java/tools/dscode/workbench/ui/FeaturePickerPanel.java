package tools.dscode.workbench.ui;

import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;
import tools.dscode.workbench.catalog.ScenarioFilter;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left-rail scenario picker. Name and tag filters are the primary controls;
 * feature-file selection is a collapsed secondary filter.
 */
final class FeaturePickerPanel extends JPanel {
    static final int FIXED_ROW_HEIGHT = 20;
    private static final EmptyBorder CELL_BORDER = new EmptyBorder(4, 6, 4, 6);

    private DefaultListModel<ConsumerFeatureCatalog.FeatureEntry> featureModel = new DefaultListModel<>();
    private DefaultListModel<ConsumerFeatureCatalog.ScenarioEntry> scenarioModel = new DefaultListModel<>();
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
        setMinimumSize(new Dimension(0, 0));

        add(northChrome(), BorderLayout.NORTH);
        add(labeled("Scenarios", scenarioList), BorderLayout.CENTER);
        add(southChrome(), BorderLayout.SOUTH);

        featureList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        featureList.setCellRenderer(featureRenderer);
        scenarioList.setCellRenderer(scenarioRenderer);
        featureList.setFixedCellHeight(FIXED_ROW_HEIGHT);
        scenarioList.setFixedCellHeight(FIXED_ROW_HEIGHT);
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

    ConsumerFeatureCatalog catalog() {
        return catalog;
    }

    void setCatalog(ConsumerFeatureCatalog catalog) {
        this.catalog = catalog;
        int featureSets = installFeatureModel(catalog == null
                ? List.of()
                : catalog.featuresForBrowse());
        int selections = 0;
        if (catalog != null) {
            applyFiltersFromUi();
            selections = syncFeatureSelection();
        }
        int scenarioSets = refreshScenarios();
        lastLoad = new CatalogLoad(
                featureSets,
                scenarioSets,
                selections,
                scenarioList.getFixedCellHeight(),
                featureModel.getSize(),
                scenarioModel.getSize()
        );
    }

    private int installFeatureModel(List<ConsumerFeatureCatalog.FeatureEntry> features) {
        DefaultListModel<ConsumerFeatureCatalog.FeatureEntry> next = new DefaultListModel<>();
        next.addAll(features);
        featureModel = next;
        featureList.setModel(next);
        return 1;
    }

    private int syncFeatureSelection() {
        if (catalog == null) return 0;
        int[] selected = new int[featureModel.size()];
        int count = 0;
        for (int i = 0; i < featureModel.size(); i++) {
            if (catalog.selected(featureModel.get(i).file())) {
                selected[count++] = i;
            }
        }
        featureList.setSelectedIndices(Arrays.copyOf(selected, count));
        return 1;
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
        WorkbenchTheme.styleScroll(featureScroll);
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
        WorkbenchTheme.styleScroll(scroll);
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

    private int refreshScenarios() {
        if (catalog == null) {
            installScenarioModel(List.of());
            status.setText("No synchronized consumer project.");
            return 1;
        }
        List<ConsumerFeatureCatalog.ScenarioEntry> visible = catalog.visibleScenarios();
        installScenarioModel(visible);
        int candidates = catalog.candidateScenarios().size();
        int shown = scenarioModel.size();
        String featureNote = catalog.selectedFeatureFiles().isEmpty()
                ? "all features"
                : catalog.selectedFeatureFiles().size() + " feature(s)";
        if (shown == candidates) {
            status.setText(shown + " scenario(s) · " + featureNote);
        } else {
            status.setText(shown + " of " + candidates + " scenario(s) · " + featureNote);
        }
        return 1;
    }

    private void installScenarioModel(List<ConsumerFeatureCatalog.ScenarioEntry> scenarios) {
        DefaultListModel<ConsumerFeatureCatalog.ScenarioEntry> next = new DefaultListModel<>();
        next.addAll(scenarios);
        scenarioModel = next;
        scenarioList.setModel(next);
    }

    private final DefaultListCellRenderer featureRenderer = new DefaultListCellRenderer() {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused
        ) {
            ConsumerFeatureCatalog.FeatureEntry feature = value instanceof ConsumerFeatureCatalog.FeatureEntry entry
                    ? entry
                    : null;
            String text = feature == null
                    ? ""
                    : feature.browseLabel(catalog == null
                    ? ConsumerFeatureCatalog.BrowseMode.FEATURE_NAME
                    : catalog.browseMode());
            Component rendered = super.getListCellRendererComponent(list, text, index, selected, focused);
            if (rendered instanceof JLabel label) {
                label.setOpaque(true);
                label.setBorder(CELL_BORDER);
                label.setBackground(selected ? WorkbenchTheme.ACCENT_SOFT : WorkbenchTheme.SURFACE);
                label.setForeground(WorkbenchTheme.TEXT);
            }
            return rendered;
        }
    };

    private final DefaultListCellRenderer scenarioRenderer = new DefaultListCellRenderer() {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused
        ) {
            ConsumerFeatureCatalog.ScenarioEntry scenario = value instanceof ConsumerFeatureCatalog.ScenarioEntry entry
                    ? entry
                    : null;
            String text = scenario == null ? "" : scenario.displayLabel();
            Component rendered = super.getListCellRendererComponent(list, text, index, selected, focused);
            if (rendered instanceof JLabel label) {
                label.setOpaque(true);
                label.setBorder(CELL_BORDER);
                label.setBackground(selected ? WorkbenchTheme.PLAYHEAD : WorkbenchTheme.SURFACE);
                label.setForeground(WorkbenchTheme.TEXT);
                if (scenario == null) {
                    label.setToolTipText(null);
                } else {
                    String tags = scenario.effectiveTags().isEmpty()
                            ? ""
                            : "  @" + String.join(" @", scenario.effectiveTags());
                    label.setToolTipText(scenario.featureName() + " — " + scenario.relativePath() + tags);
                }
            }
            return rendered;
        }
    };

    private CatalogLoad lastLoad = new CatalogLoad(0, 0, 0, FIXED_ROW_HEIGHT, 0, 0);

    CatalogLoad lastCatalogLoad() {
        return lastLoad;
    }

    JList<ConsumerFeatureCatalog.FeatureEntry> featureListForTest() {
        return featureList;
    }

    JList<ConsumerFeatureCatalog.ScenarioEntry> scenarioListForTest() {
        return scenarioList;
    }

    record CatalogLoad(
            int featureModelSets,
            int scenarioModelSets,
            int selectionSets,
            int fixedCellHeight,
            int featureRows,
            int scenarioRows
    ) {
    }
}
