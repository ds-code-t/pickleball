package tools.dscode.workbench.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;
import tools.dscode.workbench.diagnostics.DiagnosticEvidenceNavigator;
import tools.dscode.workbench.lease.WorkbenchControlLeaseSnapshot;
import tools.dscode.workbench.lease.WorkbenchPermissionRequest;
import tools.dscode.workbench.mapping.MappingTreeModel;
import tools.dscode.workbench.mapping.MappingValueCodec;
import tools.dscode.workbench.mcp.WorkbenchAttachServer;
import tools.dscode.workbench.player.LivePlaybackCoordinator;
import tools.dscode.workbench.player.LiveScenarioPlayer;
import tools.dscode.workbench.player.WorkbenchSavePreview;
import tools.dscode.workbench.player.WorkbenchSaveResult;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.ui.web.DiagnosticExplorerHost;
import tools.dscode.workbench.ui.web.GherkinEditorHost;
import tools.dscode.workbench.ui.web.JavaFxSupport;
import tools.dscode.workbench.ui.web.MappingEditorHost;
import tools.dscode.workbench.ui.web.WebViewPanel;
import tools.dscode.workbench.ui.web.WorkbenchWebJson;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Player-style Swing presentation adapter over the shared Workbench service seam. */
final class WorkbenchFrame extends JFrame {
    private static final int MAPPING_SAVE_DELAY_MS = 650;

    private final WorkbenchUiController controller;
    private final WorkbenchAttachServer attach;
    private final LiveScenarioPlayer player;
    private final LivePlaybackCoordinator playback;
    private final ObjectMapper json = new ObjectMapper();
    private final FeaturePickerPanel picker = new FeaturePickerPanel();
    private final TerminalPanel terminal = new TerminalPanel();
    private final GherkinEditorHost gherkinHost = new GherkinEditorHost();
    private final MappingEditorHost mappingHost = new MappingEditorHost();
    private final DiagnosticExplorerHost diagnosticHost = new DiagnosticExplorerHost();
    private WebViewPanel gherkinView;
    private WebViewPanel mappingView;
    private WebViewPanel diagnosticView;
    private JComponent pickerSplit;
    private final JToggleButton pickerToggle = new JToggleButton("Features");
    private List<WorkbenchUiController.MappingCatalogEntry> mappingEntries = List.of();
    private MappingTreeModel mappingModel;
    private DiagnosticEvidenceNavigator diagnosticNavigator;

    private static final Color PLAYHEAD_COLOR = new Color(255, 228, 150);
    private final JTextArea scenarioEditor = new JTextArea();
    private final Highlighter.HighlightPainter playheadPainter =
            new DefaultHighlighter.DefaultHighlightPainter(PLAYHEAD_COLOR);
    private final JTextField stepText = new JTextField();

    private final JButton playButton = playerButton("▶", "Run the scenario from the first step in a fresh scenario context");
    private final JButton pauseButton = playerButton("⏸", "Pause after the current in-flight step");
    private final JButton playerStopButton = playerButton("■", "Stop automatic scenario advancement");
    private final JButton stepOnlyButton =
            smallPlayerButton("▶ Step", "Execute only the Step Editor text in the current paused scenario context");
    private final JButton fromHereButton =
            smallPlayerButton("▶ From Here", "Start a fresh scenario context and run from the selected step");

    private final JLabel projectLabel = new JLabel("Project: loading...");
    private final JLabel readinessLabel = new JLabel("Loading status...");
    private final JLabel playerStatusLabel = new JLabel("Stopped");
    private final JLabel activityLabel = new JLabel("Ready");
    private final JPanel agentBanner = new JPanel(new BorderLayout(12, 0));
    private final JLabel agentBannerLabel = new JLabel();
    private final JButton takeControlButton = WorkbenchTheme.accentButton(
            "Take control",
            "Return live Workbench controls to the human and cancel in-flight agent permission waits"
    );
    private final JPanel permissionBar = new JPanel(new BorderLayout(12, 0));
    private final JLabel permissionLabel = new JLabel();
    private final JButton allowButton = WorkbenchTheme.accentButton("Allow", "Allow the agent to copy the live scenario into the original feature file");
    private final JButton denyButton = WorkbenchTheme.flatButton("Deny", "Deny the write; the original feature file is left unchanged");
    private WorkbenchControlLeaseSnapshot lastLease;
    private String pendingPermissionId;

    private final JMenuItem syncItem = new JMenuItem("Synchronize");
    private final JMenuItem refreshItem = new JMenuItem("Refresh Status");
    private final JMenuItem startItem = new JMenuItem("Start Worker");
    private final JMenuItem restartItem = new JMenuItem("Restart Worker");
    private final JMenuItem stopItem = new JMenuItem("Stop Worker");

    private final JComboBox<WorkbenchUiController.MappingCatalogEntry> nodeMapSelector =
            new JComboBox<>();
    private final JTextArea mappingEditor = new JTextArea();
    private final JLabel mappingStatus = new JLabel("Start the live worker to inspect Mapping.");
    private final Timer mappingSaveTimer = new Timer(
            MAPPING_SAVE_DELAY_MS,
            event -> saveEditedMapping()
    );

    private final JLabel webViewNote = WorkbenchTheme.muted("");

    private WorkbenchUiController.State lastState;
    private ControlBridgeMappingSnapshot loadedMapping;
    private boolean loadingMapping;
    private boolean refreshingCatalog;
    private boolean mappingSaveBusy;
    private long mappingEditGeneration;

    private boolean playbackPreparing;
    private boolean playbackBusy;
    private Long executingStepId;
    private boolean pendingFreshRun;
    private Long pendingFreshRunStepId;
    private String pendingIsolatedStep;
    private boolean syncingScenarioDocument;
    private boolean closing;

    WorkbenchFrame(WorkbenchUiController controller) {
        this(controller, null);
    }

    WorkbenchFrame(WorkbenchUiController controller, WorkbenchAttachServer attach) {
        super("Pickleball Workbench");
        this.controller = controller;
        this.attach = attach;
        this.player = controller.player();
        this.playback = controller.playback();

        mappingSaveTimer.setRepeats(false);
        WorkbenchTheme.install();
        getContentPane().setBackground(WorkbenchTheme.BACKGROUND);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 740));
        setSize(1560, 920);
        setLocationByPlatform(true);
        setJMenuBar(menuBar());
        configureWebViews();

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(WorkbenchTheme.BACKGROUND);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));
        root.add(topChrome(), BorderLayout.NORTH);

        JSplitPane editorAndRight = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                leftWorkspace(),
                rightWorkspace()
        );
        WorkbenchTheme.styleSplit(editorAndRight);
        editorAndRight.setResizeWeight(0.56);
        editorAndRight.setDividerLocation(820);

        JSplitPane withPicker = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                picker,
                editorAndRight
        );
        WorkbenchTheme.styleSplit(withPicker);
        withPicker.setResizeWeight(0.18);
        withPicker.setDividerLocation(280);
        pickerSplit = withPicker;
        root.add(withPicker, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        configureScenarioEditor();
        configureStepEditor();
        configureMappingEditor();
        configurePicker();
        wirePlayerActions();
        wireSessionActions();
        syncScenarioView();
        updatePlayerView(null);
        refreshFeatureCatalog();
        terminal.start();

        configureAgentChrome();
        controller.addLeaseListener(snapshot -> SwingUtilities.invokeLater(() -> applyLease(snapshot)));
        controller.addPlayerListener(() -> SwingUtilities.invokeLater(() -> {
            syncScenarioView();
            updatePlayerView(null);
        }));
        applyLease(controller.controlLease());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWorkbench();
            }
        });

        runStateAction("Loading project status", controller::refresh);
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(event -> closeWorkbench());
        file.add(exit);
        bar.add(file);

        JMenu session = new JMenu("Session");
        session.add(syncItem);
        session.add(refreshItem);
        session.addSeparator();
        session.add(startItem);
        session.add(restartItem);
        session.add(stopItem);
        bar.add(session);

        JMenu tools = new JMenu("Tools");
        JMenuItem advanced = new JMenuItem("Advanced Controls...");
        advanced.addActionListener(event -> showAdvancedControls());
        tools.add(advanced);
        bar.add(tools);

        return bar;
    }

    private JComponent topChrome() {
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(agentBanner);
        north.add(Box.createVerticalStrut(6));
        north.add(permissionBar);
        north.add(Box.createVerticalStrut(6));
        north.add(playerBar());
        return north;
    }

    private void configureAgentChrome() {
        agentBanner.setBackground(new Color(0xFE, 0xF3, 0xC7));
        agentBanner.setBorder(WorkbenchTheme.cardBorder());
        agentBannerLabel.setForeground(WorkbenchTheme.TEXT);
        agentBannerLabel.setFont(agentBannerLabel.getFont().deriveFont(Font.BOLD, 13f));
        takeControlButton.addActionListener(event -> {
            controller.takeControl();
            applyLease(controller.controlLease());
            updatePlayerView("You took control of Workbench.");
        });
        agentBanner.add(agentBannerLabel, BorderLayout.CENTER);
        agentBanner.add(takeControlButton, BorderLayout.EAST);
        agentBanner.setVisible(false);

        permissionBar.setBackground(new Color(0xDB, 0xEA, 0xFE));
        permissionBar.setBorder(WorkbenchTheme.cardBorder());
        permissionLabel.setForeground(WorkbenchTheme.TEXT);
        JPanel permissionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        permissionButtons.setOpaque(false);
        allowButton.addActionListener(event -> answerPermission(true));
        denyButton.addActionListener(event -> answerPermission(false));
        permissionButtons.add(allowButton);
        permissionButtons.add(denyButton);
        permissionBar.add(permissionLabel, BorderLayout.CENTER);
        permissionBar.add(permissionButtons, BorderLayout.EAST);
        permissionBar.setVisible(false);
    }

    private void answerPermission(boolean allow) {
        if (pendingPermissionId == null) return;
        String id = pendingPermissionId;
        pendingPermissionId = null;
        controller.answerPermission(id, allow);
        applyLease(controller.controlLease());
        updatePlayerView(allow
                ? "Allowed the agent Save request."
                : "Denied the agent Save request. The original feature file was not changed.");
    }

    private JPanel playerBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(WorkbenchTheme.SURFACE);
        bar.setBorder(WorkbenchTheme.cardBorder());

        JPanel project = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        project.setOpaque(false);
        pickerToggle.setSelected(true);
        pickerToggle.setFocusable(false);
        pickerToggle.addActionListener(event -> togglePicker());
        project.add(pickerToggle);
        project.add(projectLabel);
        project.add(readinessLabel);
        bar.add(project, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        controls.setOpaque(false);
        controls.add(playButton);
        controls.add(pauseButton);
        controls.add(playerStopButton);
        bar.add(controls, BorderLayout.CENTER);

        JPanel state = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        state.setOpaque(false);
        state.add(WorkbenchTheme.muted("Status"));
        state.add(playerStatusLabel);
        bar.add(state, BorderLayout.EAST);
        return bar;
    }

    private JComponent leftWorkspace() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.add(scenarioPanel(), BorderLayout.CENTER);
        left.add(stepPanel(), BorderLayout.SOUTH);
        return left;
    }

    private JComponent scenarioPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(WorkbenchTheme.SURFACE);
        panel.setBorder(WorkbenchTheme.cardBorder());
        panel.add(WorkbenchTheme.heading("Live Scenario Editor"), BorderLayout.NORTH);

        scenarioEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        scenarioEditor.setLineWrap(false);
        scenarioEditor.setTabSize(2);
        JPanel editorHost = new JPanel(new CardLayout());
        editorHost.setOpaque(false);
        editorHost.add(new JScrollPane(scenarioEditor), "text");
        if (gherkinView != null) {
            editorHost.add(gherkinView, "web");
            ((CardLayout) editorHost.getLayout()).show(editorHost, "web");
        }
        panel.add(editorHost, BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 2));
        legend.setOpaque(false);
        legend.add(WorkbenchTheme.muted("Blocks are Gherkin text"));
        legend.add(WorkbenchTheme.muted("Play starts from the first step"));
        legend.add(WorkbenchTheme.muted("Click a block to move the playhead"));
        panel.add(legend, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent stepPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 5));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                new EmptyBorder(5, 7, 7, 7)
        ));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel title = new JLabel("Step Editor");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title);
        header.add(stepOnlyButton);
        header.add(fromHereButton);
        header.add(new JLabel("Enter = append/insert   Ctrl+Enter = update selected line"));
        panel.add(header, BorderLayout.NORTH);

        stepText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        panel.add(stepText, BorderLayout.CENTER);
        return panel;
    }

    private JComponent rightWorkspace() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Mapping", mappingPanel());
        tabs.addTab("Terminal", terminal);
        tabs.addTab("Diagnostic Log Explorer", diagnosticsPanel());
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedIndex() == 2) refreshDiagnostics();
        });
        return tabs;
    }

    /**
     * Mapping deliberately has no get/put/resolve workflow. The selected current
     * NodeMap is represented as one editable JSON object snapshot and valid edits
     * are restored automatically after a short debounce.
     */
    private JPanel mappingPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel selector = new JPanel(new BorderLayout(6, 0));
        selector.add(new JLabel("NodeMap:"), BorderLayout.WEST);
        nodeMapSelector.setEnabled(false);
        selector.add(nodeMapSelector, BorderLayout.CENTER);
        panel.add(selector, BorderLayout.NORTH);

        mappingEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        mappingEditor.setTabSize(2);
        mappingEditor.setLineWrap(false);
        mappingEditor.setEnabled(false);
        panel.add(new JScrollPane(mappingEditor), BorderLayout.CENTER);

        mappingStatus.setBorder(new EmptyBorder(2, 2, 2, 2));
        panel.add(mappingStatus, BorderLayout.SOUTH);
        if (mappingView != null) {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.add(mappingView, BorderLayout.CENTER);
            wrap.add(mappingStatus, BorderLayout.SOUTH);
            return wrap;
        }
        return panel;
    }

    private JPanel diagnosticsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(WorkbenchTheme.SURFACE);
        if (diagnosticView != null) {
            panel.add(diagnosticView, BorderLayout.CENTER);
            return panel;
        }
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JTextArea message = outputArea();
        message.setText("""
                Diagnostic Log Explorer

                JavaFX WebView is unavailable in this process, so the explorer
                cannot open the timeline UI. Workbench still reads Pickleball's
                retained diagnostic artifacts from reports/diagnostic-runs and
                does not invent a second store.
                """);
        message.setCaretPosition(0);
        panel.add(new JScrollPane(message), BorderLayout.CENTER);
        return panel;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        activityLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        footer.add(activityLabel, BorderLayout.CENTER);
        footer.add(webViewNote, BorderLayout.EAST);
        return footer;
    }

    private void configureScenarioEditor() {
        scenarioEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scenarioDocumentChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scenarioDocumentChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scenarioDocumentChanged();
            }
        });
        scenarioEditor.addCaretListener(event -> seekPlayheadToCaret());
        scenarioEditor.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                seekPlayheadToCaret();
            }
        });
    }

    private void configureStepEditor() {
        stepText.addActionListener(event -> insertStep());
        stepText.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
                "update-selected-step"
        );
        stepText.getActionMap().put("update-selected-step", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                updateSelectedStep();
            }
        });
    }

    private void configureWebViews() {
        if (!JavaFxSupport.available()) {
            webViewNote.setText("JavaFX WebView unavailable: " + JavaFxSupport.failure()
                    + ". Using the text fallback. OpenJFX is Workbench-only.");
            return;
        }
        try {
            gherkinHost.onDocument(this::applyEditorLines);
            gherkinHost.onSeek(id -> {
                if (humanControlsLocked()) return;
                player.clickLine(id);
                updateFromHereAvailability();
                refreshPlayheadHighlight();
            });
            gherkinHost.onAddStep(this::insertStep);
            gherkinHost.onReady(this::pushGherkinView);
            gherkinView = new WebViewPanel(
                    "/tools/dscode/workbench/ui/web/gherkin-editor.html",
                    "gherkinHost",
                    gherkinHost
            );

            mappingHost.onSelect(reference -> {
                for (WorkbenchUiController.MappingCatalogEntry entry : mappingEntries) {
                    if (entry.reference().equals(reference)) {
                        nodeMapSelector.setSelectedItem(entry);
                        loadMapping(entry);
                        return;
                    }
                }
            });
            mappingHost.onEdit(this::applyMappingPropertyEdit);
            mappingHost.onReady(this::pushMappingView);
            mappingView = new WebViewPanel(
                    "/tools/dscode/workbench/ui/web/mapping-editor.html",
                    "mappingHost",
                    mappingHost
            );

            diagnosticHost.onSelectRun(this::showDiagnosticRun);
            diagnosticHost.onReady(this::refreshDiagnostics);
            diagnosticView = new WebViewPanel(
                    "/tools/dscode/workbench/ui/web/diagnostic-explorer.html",
                    "diagnosticHost",
                    diagnosticHost
            );
        } catch (RuntimeException failure) {
            gherkinView = null;
            mappingView = null;
            diagnosticView = null;
            webViewNote.setText("JavaFX WebView failed to start: " + failure.getMessage());
        }
    }

    private void configurePicker() {
        picker.onScenarioSelected(scenario -> {
            controller.loadPickerScenario(
                    scenario.lines(),
                    scenario.file(),
                    scenario.name(),
                    scenario.startLine(),
                    scenario.endLine()
            );
            picker.setSaveEnabled(true);
            syncScenarioView();
            updatePlayerView("Loaded " + scenario.displayLabel() + " into the live session buffer.");
        });
        picker.onSave(this::saveLoadedFeature);
    }

    private void refreshFeatureCatalog() {
        WorkbenchManifest manifest = null;
        try {
            manifest = WorkbenchManifest.read(controller.projectRoot());
        } catch (RuntimeException ignored) {
            // The picker can still scan conventional project feature folders.
        }
        picker.setCatalog(ConsumerFeatureCatalog.scan(controller.projectRoot(), manifest));
        diagnosticNavigator = new DiagnosticEvidenceNavigator(controller.projectRoot());
    }

    private void togglePicker() {
        if (!(pickerSplit instanceof JSplitPane split)) return;
        if (pickerToggle.isSelected()) {
            split.setDividerLocation(280);
            picker.setVisible(true);
        } else {
            split.setDividerLocation(0);
            picker.setVisible(false);
        }
        split.revalidate();
    }

    private void applyEditorLines(List<String> lines) {
        if (syncingScenarioDocument || humanControlsLocked()) return;
        player.replaceDocument(lines);
        updateFromHereAvailability();
        if (player.state() == LiveScenarioPlayer.State.RUNNING) {
            if (lastState == null || !lastState.liveReady()) {
                prepareLiveSession(this::schedulePlaybackStep);
            } else {
                schedulePlaybackStep();
            }
        }
    }

    private void pushGherkinView() {
        if (gherkinView == null) return;
        gherkinView.evalJsonCall("window.setEditorState", WorkbenchWebJson.editorState(
                player,
                executingStepId,
                humanControlsLocked()
        ));
    }

    private void pushMappingView() {
        if (mappingView == null) return;
        WorkbenchUiController.MappingCatalogEntry selected =
                (WorkbenchUiController.MappingCatalogEntry) nodeMapSelector.getSelectedItem();
        mappingView.evalJsonCall(
                "window.setMappingState",
                WorkbenchWebJson.mappingState(
                        mappingEntries.stream()
                                .map(entry -> new WorkbenchWebJson.MapChoice(
                                        entry.reference(), entry.label(), entry.restorable()))
                                .toList(),
                        selected == null ? null : new WorkbenchWebJson.MapChoice(
                                selected.reference(), selected.label(), selected.restorable()),
                        mappingModel,
                        mappingStatus.getText(),
                        humanControlsLocked()
                )
        );
    }

    private void applyMappingPropertyEdit(MappingEditorHost.PropertyEdit edit) {
        if (humanControlsLocked()) return;
        if (lastState == null || !lastState.liveReady() || loadedMapping == null) return;
        try {
            if (edit.oldKey() == null || edit.oldKey().isBlank() || edit.oldKey().equals(edit.key())) {
                runTask(
                        () -> controller.mappingPutTyped(edit.mapReference(), edit.key(), edit.type(), edit.text()),
                        result -> {
                            terminal.appendExecution("[Mapping] " + edit.key(), result.output(), result.events());
                            mappingStatus.setText("Saved " + edit.key() + " through mappingPut.");
                            if (mappingModel != null) {
                                mappingModel = mappingModel.upsert(
                                        edit.key(),
                                        MappingValueCodec.parseType(edit.type()),
                                        edit.text()
                                );
                            }
                            pushMappingView();
                        },
                        failure -> showFailure("Mapping property edit failed", failure)
                );
                return;
            }
            MappingTreeModel updated = (mappingModel == null
                    ? new MappingTreeModel(edit.mapReference(), loadedMapping.mapType(), true, Map.of())
                    : mappingModel)
                    .rename(edit.oldKey(), edit.key())
                    .upsert(edit.key(), MappingValueCodec.parseType(edit.type()), edit.text());
            runTask(
                    () -> controller.restoreMapping(loadedMapping, updated.values()),
                    output -> {
                        mappingModel = updated;
                        terminal.appendExecution("[Mapping] " + loadedMapping.mapType(), output, "");
                        mappingStatus.setText("Renamed property saved through mappingRestore.");
                        pushMappingView();
                    },
                    failure -> showFailure("Mapping restore failed", failure)
            );
        } catch (RuntimeException failure) {
            mappingStatus.setText(failure.getMessage());
        }
    }

    private void saveLoadedFeature() {
        if (humanControlsLocked()) return;
        WorkbenchSavePreview preview = controller.savePreview();
        if (!preview.savable()) {
            showFailure("Could not save", new IllegalStateException(preview.summary()));
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                preview.summary() + "\n\nWorkbench will not write the original feature file unless you confirm.",
                "Save live scenario",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            updatePlayerView("Save cancelled. The original feature file was not changed.");
            return;
        }
        try {
            WorkbenchSaveResult result = controller.commitSave();
            if (result.written()) {
                updatePlayerView(result.message());
            } else {
                showFailure("Could not save", new IllegalStateException(result.message()));
            }
        } catch (RuntimeException failure) {
            showFailure("Could not save feature file", failure);
        }
    }

    private void refreshDiagnostics() {
        if (diagnosticView == null) return;
        if (diagnosticNavigator == null) {
            diagnosticNavigator = new DiagnosticEvidenceNavigator(controller.projectRoot());
        }
        List<DiagnosticEvidenceNavigator.CatalogRun> runs = diagnosticNavigator.catalogRuns();
        if (runs.isEmpty()) {
            diagnosticView.evalJsonCall("window.setDiagnosticState", WorkbenchWebJson.write(Map.of(
                    "runs", List.of(),
                    "frames", List.of(),
                    "layers", List.of(),
                    "gap", diagnosticNavigator.available()
                            ? "The catalog has no retained runs."
                            : "No reports/diagnostic-runs/run-catalog.json in this consumer project."
            )));
            return;
        }
        showDiagnosticRun(runs.getFirst().runId());
    }

    private void showDiagnosticRun(String runId) {
        if (diagnosticView == null || diagnosticNavigator == null) return;
        DiagnosticEvidenceNavigator.CatalogRun selected = diagnosticNavigator.catalogRuns().stream()
                .filter(run -> run.runId().equals(runId))
                .findFirst()
                .orElse(null);
        if (selected == null) return;
        DiagnosticEvidenceNavigator.Timeline timeline = diagnosticNavigator.timeline(selected.runRoot());
        List<Map<String, Object>> frames = new ArrayList<>();
        for (DiagnosticEvidenceNavigator.ScreenshotFrame frame : timeline.frames()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepText", frame.stepText());
            item.put("scenarioId", frame.scenarioId());
            try {
                byte[] bytes = Files.readAllBytes(frame.file());
                item.put("dataUri", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
            } catch (Exception ignored) {
                continue;
            }
            frames.add(item);
        }
        String scenarioId = timeline.frames().isEmpty() ? "" : timeline.frames().getFirst().scenarioId();
        List<Map<String, Object>> layers = new ArrayList<>();
        for (var layer : diagnosticNavigator.layers(selected.runRoot(), scenarioId)) {
            layers.add(Map.of(
                    "layer", layer.layer().name(),
                    "present", layer.present(),
                    "excerpt", layer.excerpt() == null ? "" : layer.excerpt()
            ));
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        for (var run : diagnosticNavigator.catalogRuns()) {
            runs.add(Map.of(
                    "runId", run.runId(),
                    "label", run.runId(),
                    "selected", run.runId().equals(runId)
            ));
        }
        diagnosticView.evalJsonCall("window.setDiagnosticState", WorkbenchWebJson.write(Map.of(
                "runs", runs,
                "frames", frames,
                "layers", layers,
                "index", 0,
                "gap", frames.isEmpty() ? "This retained run has no PNG frames." : ""
        )));
    }

    private void configureMappingEditor() {
        nodeMapSelector.addActionListener(event -> {
            if (refreshingCatalog) return;
            WorkbenchUiController.MappingCatalogEntry selected =
                    (WorkbenchUiController.MappingCatalogEntry) nodeMapSelector.getSelectedItem();
            if (selected != null) loadMapping(selected);
        });

        mappingEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                mappingChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                mappingChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                mappingChanged();
            }
        });
    }

    private void wirePlayerActions() {
        playButton.addActionListener(event -> runScenarioFromBeginning());
        pauseButton.addActionListener(event -> {
            if (humanControlsLocked()) return;
            player.pause();
            updatePlayerView(playbackBusy
                    ? "Pause requested; the current step will finish first."
                    : "Scenario playback paused.");
        });
        playerStopButton.addActionListener(event -> {
            if (humanControlsLocked()) return;
            player.stop();
            pendingFreshRun = false;
            pendingFreshRunStepId = null;
            pendingIsolatedStep = null;
            updatePlayerView(playbackBusy
                    ? "Scenario playback stopped; the current step will finish but no next step will start."
                    : "Scenario playback stopped.");
        });
        stepOnlyButton.addActionListener(event -> executeStepOnly());
        fromHereButton.addActionListener(event -> runScenarioFromSelectedStep());
    }

    private void wireSessionActions() {
        syncItem.addActionListener(event ->
                runStateAction("Synchronizing project", controller::synchronize));
        refreshItem.addActionListener(event ->
                runStateAction("Refreshing status", controller::refresh));
        startItem.addActionListener(event ->
                runStateAction("Starting worker", controller::startWorker));
        restartItem.addActionListener(event ->
                runStateAction("Restarting worker", controller::restartWorker));
        stopItem.addActionListener(event -> {
            player.stop();
            runStateAction("Stopping worker", controller::stopWorker);
        });
    }

    private void runScenarioFromBeginning() {
        requestFreshRun(null);
    }

    private void runScenarioFromSelectedStep() {
        LiveScenarioPlayer.Line selected = player.selectedLine()
                .or(player::playheadLine)
                .orElse(null);
        if (selected == null || !selected.executable()) {
            showFailure("Could not run from selected step",
                    new IllegalStateException("Select an executable scenario step first."));
            return;
        }
        requestFreshRun(selected.id());
    }

    private void requestFreshRun(Long startStepId) {
        if (humanControlsLocked()) return;
        pendingIsolatedStep = null;
        if (playbackBusy || playbackPreparing) {
            pendingFreshRun = true;
            pendingFreshRunStepId = startStepId;
            player.stop();
            updatePlayerView(startStepId == null
                    ? "Run from start queued after the current operation."
                    : "Run from selected step queued after the current operation.");
            return;
        }
        startFreshRunNow(startStepId);
    }

    private void startFreshRunNow(Long startStepId) {
        try {
            if (startStepId == null) {
                player.startFromBeginning();
                updatePlayerView("Starting a fresh scenario run from the first step...");
            } else {
                player.clickLine(startStepId);
                player.startFromSelectedStep();
                showLine(startStepId);
                updatePlayerView("Starting a fresh scenario run from the selected step...");
            }
        } catch (RuntimeException failure) {
            showFailure("Could not start scenario playback", failure);
            return;
        }
        prepareFreshLiveSession(this::schedulePlaybackStep);
    }

    private void prepareFreshLiveSession(Runnable readyAction) {
        if (playbackPreparing) return;
        playbackPreparing = true;
        activityLabel.setText("Preparing fresh scenario context...");
        runTask(
                controller::prepareFreshLiveSession,
                state -> {
                    playbackPreparing = false;
                    applyState(state);
                    if (runPendingFreshRun()) return;
                    if (readyAction != null) readyAction.run();
                    runPendingIsolatedStep();
                },
                failure -> {
                    playbackPreparing = false;
                    player.pause();
                    updatePlayerView("Fresh scenario preparation failed.");
                    showFailure("Could not prepare fresh live session", failure);
                }
        );
    }

    private void prepareLiveSession(Runnable readyAction) {
        if (playbackPreparing) return;
        playbackPreparing = true;
        activityLabel.setText("Preparing live session...");
        runTask(
                controller::prepareLiveSession,
                state -> {
                    playbackPreparing = false;
                    applyState(state);
                    if (runPendingFreshRun()) return;
                    if (readyAction != null) {
                        readyAction.run();
                    } else {
                        refreshMappingCatalog();
                    }
                    runPendingIsolatedStep();
                },
                failure -> {
                    playbackPreparing = false;
                    player.pause();
                    updatePlayerView("Live session preparation failed.");
                    showFailure("Could not prepare live session", failure);
                }
        );
    }

    /** Executes one scenario line per background task so pause/stop remain responsive. */
    private void schedulePlaybackStep() {
        if (playbackPreparing || playbackBusy || mappingSaveBusy || refreshingCatalog) return;
        if (player.state() != LiveScenarioPlayer.State.RUNNING) {
            updatePlayerView(null);
            return;
        }

        LiveScenarioPlayer.Line step = player.nextStep().orElse(null);
        if (step == null) {
            updatePlayerView("Scenario is waiting for another step.");
            return;
        }

        playbackBusy = true;
        executingStepId = step.id();
        updatePlayerView("Executing: " + step.text());
        runTask(
                () -> controller.executePlayerStep(step.text()),
                result -> {
                    playbackBusy = false;
                    executingStepId = null;
                    appendTerminal(step.text(), result.output(), result.events());

                    // executeStep already advanced or paused the playhead while RUNNING.
                    // Do not remake that mark here; a leftover mark of the captured id
                    // used to abort automatic playback after the first successful step.
                    if (!result.successful()) {
                        player.clickLine(step.id());
                        showLine(step.id());
                    }
                    syncScenarioView();
                    updatePlayerView(
                            result.successful()
                                    ? null
                                    : "Step failed. Scenario playback paused on the failed step."
                    );
                    if (runPendingFreshRun()) return;
                    if (player.state() != LiveScenarioPlayer.State.RUNNING) {
                        refreshMappingCatalog();
                    }
                    if (!runPendingIsolatedStep()) {
                        schedulePlaybackStep();
                    }
                },
                failure -> {
                    playbackBusy = false;
                    executingStepId = null;
                    player.markCurrentStepFailed(step.id());
                    player.clickLine(step.id());
                    syncScenarioView();
                    showLine(step.id());
                    updatePlayerView("Step execution failed. Scenario playback paused.");
                    showFailure("Could not execute live step", failure);
                    if (!runPendingFreshRun()) runPendingIsolatedStep();
                }
        );
    }

    private void insertStep() {
        if (humanControlsLocked()) return;
        try {
            LiveScenarioPlayer.Line inserted = player.insertStep(stepText.getText());
            stepText.setText("");
            player.clickLine(inserted.id());
            syncScenarioView();
            showLine(inserted.id());
            updatePlayerView(
                    player.state() == LiveScenarioPlayer.State.RUNNING
                            ? "Appended step and continued live playback."
                            : "Inserted step into the live scenario."
            );
            if (player.state() == LiveScenarioPlayer.State.RUNNING) {
                if (lastState == null || !lastState.liveReady()) {
                    prepareLiveSession(this::schedulePlaybackStep);
                } else {
                    schedulePlaybackStep();
                }
            }
        } catch (RuntimeException failure) {
            showFailure("Could not insert step", failure);
        }
    }

    private void updateSelectedStep() {
        if (humanControlsLocked()) return;
        try {
            LiveScenarioPlayer.Line updated = player.updateSelectedStep(stepText.getText());
            syncScenarioView();
            showLine(updated.id());
            updatePlayerView("Updated selected line in place.");
        } catch (RuntimeException failure) {
            showFailure("Could not update selected step", failure);
        }
    }

    private void executeStepOnly() {
        if (humanControlsLocked()) return;
        String text = stepText.getText();
        if (text == null || text.isBlank()) {
            showFailure("Could not execute step",
                    new IllegalArgumentException("Step Editor text must not be blank."));
            return;
        }

        player.pauseForIsolatedExecution();
        updatePlayerView("Scenario playback paused for Step Only execution.");

        if (playbackBusy || playbackPreparing) {
            pendingIsolatedStep = text;
            activityLabel.setText("Step Only execution queued after the current operation.");
            return;
        }
        executeStepOnlyNow(text);
    }

    private void executeStepOnlyNow(String text) {
        Runnable execute = () -> {
            playbackBusy = true;
            runTask(
                    () -> controller.executePlayerStep(text),
                    result -> {
                        playbackBusy = false;
                        appendTerminal("[step only] " + text, result.output(), result.events());
                        updatePlayerView("Step Only finished; automatic scenario playback remains paused.");
                        if (!runPendingFreshRun()) refreshMappingCatalog();
                    },
                    failure -> {
                        playbackBusy = false;
                        showFailure("Could not execute step", failure);
                        runPendingFreshRun();
                    }
            );
        };

        if (lastState != null && lastState.liveReady()) {
            execute.run();
        } else {
            prepareLiveSession(execute);
        }
    }

    private boolean runPendingFreshRun() {
        if (!pendingFreshRun || playbackBusy || playbackPreparing) return false;
        Long startStepId = pendingFreshRunStepId;
        pendingFreshRun = false;
        pendingFreshRunStepId = null;
        startFreshRunNow(startStepId);
        return true;
    }

    private boolean runPendingIsolatedStep() {
        if (pendingIsolatedStep == null || playbackBusy || playbackPreparing || pendingFreshRun) return false;
        String text = pendingIsolatedStep;
        pendingIsolatedStep = null;
        executeStepOnlyNow(text);
        return true;
    }

    private void mappingChanged() {
        if (humanControlsLocked()) return;
        if (loadingMapping || loadedMapping == null || !loadedMapping.restorable()) return;
        if (player.state() == LiveScenarioPlayer.State.RUNNING
                || player.state() == LiveScenarioPlayer.State.WAITING_FOR_STEP) {
            player.pause();
            updatePlayerView("Player paused for live Mapping edit.");
        }
        mappingEditGeneration++;
        mappingStatus.setText("Editing " + loadedMapping.mapType() + "...");
        mappingSaveTimer.restart();
    }

    private void refreshMappingCatalog() {
        if (refreshingCatalog || lastState == null || !lastState.liveReady()) {
            if (lastState == null || !lastState.liveReady()) {
                nodeMapSelector.setEnabled(false);
                mappingEditor.setEnabled(false);
            }
            return;
        }

        refreshingCatalog = true;
        WorkbenchUiController.MappingCatalogEntry previous =
                (WorkbenchUiController.MappingCatalogEntry) nodeMapSelector.getSelectedItem();
        String previousReference = previous == null ? null : previous.reference();

        runTask(
                controller::mappingCatalog,
                entries -> {
                    refreshingCatalog = false;
                    nodeMapSelector.removeAllItems();
                    WorkbenchUiController.MappingCatalogEntry selected = null;
                    for (WorkbenchUiController.MappingCatalogEntry entry : entries) {
                        nodeMapSelector.addItem(entry);
                        if (Objects.equals(previousReference, entry.reference())) selected = entry;
                    }
                    mappingEntries = List.copyOf(entries);
                    nodeMapSelector.setEnabled(!entries.isEmpty());
                    if (selected == null && !entries.isEmpty()) selected = entries.getFirst();
                    if (selected != null) {
                        nodeMapSelector.setSelectedItem(selected);
                        loadMapping(selected);
                    } else {
                        loadedMapping = null;
                        setMappingEditor("", false);
                        mappingStatus.setText("No NodeMaps are available in the current ParsingMap.");
                    }
                    schedulePlaybackStep();
                },
                failure -> {
                    refreshingCatalog = false;
                    nodeMapSelector.setEnabled(false);
                    mappingEditor.setEnabled(false);
                    mappingStatus.setText("Could not read current ParsingMap: " + failure.getMessage());
                    schedulePlaybackStep();
                }
        );
    }

    private void loadMapping(WorkbenchUiController.MappingCatalogEntry entry) {
        if (entry == null || lastState == null || !lastState.liveReady()) return;
        mappingStatus.setText("Loading " + entry.label() + "...");
        runTask(
                () -> controller.mappingSnapshot(entry.reference()),
                snapshot -> {
                    loadedMapping = snapshot;
                    mappingModel = new MappingTreeModel(
                            snapshot.mapReference(),
                            snapshot.mapType(),
                            snapshot.restorable(),
                            snapshot.values()
                    );
                    try {
                        String formatted = json.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(snapshot.values());
                        setMappingEditor(formatted, snapshot.restorable());
                        mappingStatus.setText(
                                snapshot.restorable()
                                        ? "Live NodeMap from the worker ParsingMap. Edits use mappingPut / mappingRestore."
                                        : "Inspection only: this NodeMap implementation is not safely restorable."
                        );
                        pushMappingView();
                    } catch (Exception failure) {
                        showFailure("Could not render NodeMap JSON", failure);
                    }
                },
                failure -> {
                    loadedMapping = null;
                    setMappingEditor("", false);
                    mappingStatus.setText("Could not load NodeMap: " + failure.getMessage());
                }
        );
    }

    private void saveEditedMapping() {
        if (loadedMapping == null || !loadedMapping.restorable() || mappingSaveBusy) return;
        if (playbackBusy || playbackPreparing) {
            mappingStatus.setText("Waiting for the current player operation before applying Mapping edit...");
            mappingSaveTimer.restart();
            return;
        }

        Map<String, Object> values;
        try {
            values = json.readValue(
                    mappingEditor.getText(),
                    new TypeReference<Map<String, Object>>() { }
            );
        } catch (Exception invalidJson) {
            mappingStatus.setText("Invalid JSON — edit has not been applied.");
            return;
        }

        long generation = mappingEditGeneration;
        ControlBridgeMappingSnapshot snapshot = loadedMapping;
        mappingSaveBusy = true;
        mappingStatus.setText("Applying live Mapping edit...");
        runTask(
                () -> controller.restoreMapping(snapshot, values),
                output -> {
                    mappingSaveBusy = false;
                    appendTerminal("[Mapping] " + snapshot.mapType(), output, "");
                    if (generation == mappingEditGeneration) {
                        mappingStatus.setText("Saved to live " + snapshot.mapType() + ".");
                    } else {
                        mappingSaveTimer.restart();
                    }
                    schedulePlaybackStep();
                },
                failure -> {
                    mappingSaveBusy = false;
                    mappingStatus.setText("Mapping edit was not applied: " + failure.getMessage());
                }
        );
    }

    private void setMappingEditor(String text, boolean editable) {
        loadingMapping = true;
        try {
            mappingEditor.setText(text);
            mappingEditor.setCaretPosition(0);
            mappingEditor.setEnabled(true);
            mappingEditor.setEditable(editable);
        } finally {
            loadingMapping = false;
        }
    }

    private void scenarioDocumentChanged() {
        if (syncingScenarioDocument || humanControlsLocked()) return;
        player.replaceDocument(List.of(scenarioEditor.getText().split("\n", -1)));
        seekPlayheadToCaret();
        updateFromHereAvailability();
        refreshPlayheadHighlight();
        if (player.state() == LiveScenarioPlayer.State.RUNNING) {
            if (lastState == null || !lastState.liveReady()) {
                prepareLiveSession(this::schedulePlaybackStep);
            } else {
                schedulePlaybackStep();
            }
        }
    }

    private void seekPlayheadToCaret() {
        if (syncingScenarioDocument || humanControlsLocked()) return;
        int lineIndex = lineIndexAtCaret();
        List<LiveScenarioPlayer.Line> lines = player.lines();
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            updateFromHereAvailability();
            refreshPlayheadHighlight();
            return;
        }
        LiveScenarioPlayer.Line line = lines.get(lineIndex);
        player.clickLine(line.id());
        if (line.executable()) {
            stepText.setText(line.text());
        }
        updateFromHereAvailability();
        refreshPlayheadHighlight();
    }

    private void syncScenarioView() {
        String document = player.documentText();
        if (!Objects.equals(scenarioEditor.getText(), document)) {
            syncingScenarioDocument = true;
            try {
                int caret = Math.min(scenarioEditor.getCaretPosition(), document.length());
                scenarioEditor.setText(document);
                scenarioEditor.setCaretPosition(Math.max(0, caret));
            } finally {
                syncingScenarioDocument = false;
            }
        }
        refreshPlayheadHighlight();
        updateFromHereAvailability();
        pushGherkinView();
    }

    private void updateFromHereAvailability() {
        fromHereButton.setEnabled(
                !humanControlsLocked()
                        && player.selectedLine()
                        .or(player::playheadLine)
                        .map(LiveScenarioPlayer.Line::executable)
                        .orElse(false)
        );
    }

    private void showLine(long id) {
        List<LiveScenarioPlayer.Line> lines = player.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() == id) {
                try {
                    int start = scenarioEditor.getLineStartOffset(i);
                    syncingScenarioDocument = true;
                    try {
                        scenarioEditor.setCaretPosition(start);
                    } finally {
                        syncingScenarioDocument = false;
                    }
                    scenarioEditor.getCaret().setVisible(true);
                } catch (BadLocationException ignored) {
                    // The document can briefly lag the model during a rebuild.
                }
                refreshPlayheadHighlight();
                return;
            }
        }
    }

    private int lineIndexAtCaret() {
        try {
            return scenarioEditor.getLineOfOffset(scenarioEditor.getCaretPosition());
        } catch (BadLocationException ignored) {
            return -1;
        }
    }

    private void refreshPlayheadHighlight() {
        Highlighter highlighter = scenarioEditor.getHighlighter();
        highlighter.removeAllHighlights();
        Long playhead = player.playheadId().isPresent() ? player.playheadId().getAsLong() : null;
        if (playhead == null && executingStepId != null) playhead = executingStepId;
        if (playhead == null && player.state() == LiveScenarioPlayer.State.RUNNING) {
            playhead = player.nextStep().map(LiveScenarioPlayer.Line::id).orElse(null);
        }
        if (playhead == null) return;

        List<LiveScenarioPlayer.Line> lines = player.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() != playhead) continue;
            try {
                int start = scenarioEditor.getLineStartOffset(i);
                int end = scenarioEditor.getLineEndOffset(i);
                highlighter.addHighlight(start, end, playheadPainter);
            } catch (BadLocationException ignored) {
                return;
            }
            return;
        }
    }

    private void updatePlayerView(String activity) {
        playerStatusLabel.setText(switch (player.state()) {
            case STOPPED -> "Stopped";
            case PAUSED -> "Paused";
            case RUNNING -> playbackBusy ? "Running" : "Playing";
            case WAITING_FOR_STEP -> "Waiting for step";
        });
        if (activity != null && !activity.isBlank()) activityLabel.setText(activity);
        syncScenarioView();
    }

    private void appendTerminal(String heading, String output, String events) {
        terminal.appendExecution(heading, output, events);
        controller.workerLogFiles().ifPresent(terminal::setFiles);
    }

    private void runStateAction(
            String label,
            Supplier<WorkbenchUiController.State> action
    ) {
        activityLabel.setText(label + "...");
        runTask(
                action,
                state -> {
                    applyState(state);
                    activityLabel.setText(label + " complete.");
                    if (state.liveReady()) {
                        refreshMappingCatalog();
                        controller.workerLogFiles().ifPresent(terminal::setFiles);
                    }
                    refreshFeatureCatalog();
                },
                failure -> showFailure(label + " failed", failure)
        );
    }

    private void applyState(WorkbenchUiController.State state) {
        lastState = state;
        projectLabel.setText("Project: " + state.projectRoot().getFileName());
        readinessLabel.setText(
                state.liveReady()
                        ? "Live worker ready"
                        : state.synchronizedProject()
                                ? "Synchronized"
                                : "Not synchronized"
        );
        syncItem.setEnabled(!state.workerRunning());
        startItem.setEnabled(state.synchronizedProject() && !state.workerRunning());
        restartItem.setEnabled(state.workerRunning());
        stopItem.setEnabled(state.workerRunning());

        if (!state.liveReady()) {
            nodeMapSelector.setEnabled(false);
            loadedMapping = null;
            setMappingEditor("", false);
            mappingStatus.setText("Start the live worker to inspect Mapping.");
        }
        if (humanControlsLocked()) {
            applyLease(lastLease);
        }
    }

    private boolean humanControlsLocked() {
        return lastLease != null && lastLease.agentHolds();
    }

    private void applyLease(WorkbenchControlLeaseSnapshot snapshot) {
        lastLease = snapshot;
        boolean locked = snapshot != null && snapshot.agentHolds();
        agentBanner.setVisible(locked);
        if (locked) {
            agentBannerLabel.setText(snapshot.bannerText());
        }
        WorkbenchPermissionRequest pending = snapshot == null ? null : snapshot.pendingPermission();
        permissionBar.setVisible(pending != null);
        if (pending != null) {
            pendingPermissionId = pending.id();
            permissionLabel.setText(pending.summary());
        } else {
            pendingPermissionId = null;
        }

        picker.setLocked(locked);
        scenarioEditor.setEditable(!locked);
        stepText.setEditable(!locked);
        playButton.setEnabled(!locked);
        pauseButton.setEnabled(!locked);
        playerStopButton.setEnabled(!locked);
        stepOnlyButton.setEnabled(!locked);
        takeControlButton.setEnabled(locked);
        if (locked) {
            fromHereButton.setEnabled(false);
            syncItem.setEnabled(false);
            startItem.setEnabled(false);
            restartItem.setEnabled(false);
            stopItem.setEnabled(false);
            nodeMapSelector.setEnabled(false);
            mappingEditor.setEnabled(false);
        } else if (lastState != null) {
            syncItem.setEnabled(!lastState.workerRunning());
            startItem.setEnabled(lastState.synchronizedProject() && !lastState.workerRunning());
            restartItem.setEnabled(lastState.workerRunning());
            stopItem.setEnabled(lastState.workerRunning());
            if (lastState.liveReady()) {
                nodeMapSelector.setEnabled(nodeMapSelector.getItemCount() > 0);
            }
        }
        updateFromHereAvailability();
        pushGherkinView();
        pushMappingView();
    }

    private <T> void runTask(
            Supplier<T> action,
            Consumer<T> success,
            Consumer<Throwable> failure
    ) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return action.get();
            }

            @Override
            protected void done() {
                try {
                    success.accept(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.accept(interrupted);
                } catch (ExecutionException execution) {
                    failure.accept(execution.getCause() == null ? execution : execution.getCause());
                } catch (RuntimeException runtime) {
                    failure.accept(runtime);
                }
            }
        }.execute();
    }

    private void showFailure(String label, Throwable failure) {
        String message = failure == null
                ? label
                : label + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        activityLabel.setText(message);
        JOptionPane.showMessageDialog(
                this,
                message,
                "Pickleball Workbench",
                JOptionPane.ERROR_MESSAGE
        );
    }

    /**
     * Existing non-Mapping investigation features remain available without
     * competing with the primary player workspace.
     */
    private void showAdvancedControls() {
        JDialog dialog = new JDialog(this, "Advanced Controls", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(900, 700);
        dialog.setLocationRelativeTo(this);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Status / Events", advancedStatusPanel());
        tabs.addTab("Step Overrides", advancedOverridesPanel());
        tabs.addTab("Browser / Service", advancedBrowserServicePanel());
        tabs.addTab("Breakpoints", advancedBreakpointsPanel());
        dialog.setContentPane(tabs);
        dialog.setVisible(true);
    }

    private JComponent advancedStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextArea output = outputArea();
        JButton refresh = new JButton("Refresh status and events");
        refresh.addActionListener(event -> runTask(
                () -> {
                    WorkbenchUiController.State state = controller.refresh();
                    String events = state.liveReady() ? controller.refreshEvents() : "";
                    return state.render() + (events.isBlank() ? "" : "\nEvents\n" + events);
                },
                output::setText,
                failure -> showFailure("Advanced status refresh failed", failure)
        ));
        panel.add(refresh, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        return panel;
    }

    private JComponent advancedOverridesPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextField id = new JTextField("workbench-ui-generated");
        JTextField regex = new JTextField("^WORKBENCH UI OVERRIDE ([A-Za-z]+)$");
        JTextArea source = new JTextArea(defaultOverrideSource(), 14, 60);
        JTextArea output = outputArea();

        JPanel fields = new JPanel(new GridLayout(2, 2, 6, 6));
        fields.add(new JLabel("ID"));
        fields.add(id);
        fields.add(new JLabel("Regex"));
        fields.add(regex);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton compile = new JButton("Compile / Replace");
        JButton list = new JButton("Refresh List");
        JButton remove = new JButton("Remove ID");
        JButton clear = new JButton("Clear All");
        buttons.add(compile);
        buttons.add(list);
        buttons.add(remove);
        buttons.add(clear);

        compile.addActionListener(event -> runTask(
                () -> controller.compileStepOverride(id.getText(), regex.getText(), source.getText()),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Step Override compile failed", failure)
        ));
        list.addActionListener(event -> runTask(
                controller::stepOverrides,
                output::setText,
                failure -> showFailure("Step Override list failed", failure)
        ));
        remove.addActionListener(event -> runTask(
                () -> controller.removeStepOverride(id.getText()),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Step Override remove failed", failure)
        ));
        clear.addActionListener(event -> runTask(
                controller::clearStepOverrides,
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Step Override clear failed", failure)
        ));

        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(fields, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(source),
                new JScrollPane(output)
        );
        split.setResizeWeight(0.55);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JComponent advancedBrowserServicePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextArea output = outputArea();
        JTextField selector = new JTextField("%health-full-url");
        JButton page = new JButton("Read Page");
        JButton screenshot = new JButton("Capture Screenshot");
        JButton service = new JButton("Execute Service Call");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(page);
        buttons.add(screenshot);
        buttons.add(new JLabel("Service:"));
        selector.setPreferredSize(new Dimension(220, selector.getPreferredSize().height));
        buttons.add(selector);
        buttons.add(service);

        page.addActionListener(event -> runTask(
                controller::browserPage,
                result -> output.setText(result.output()),
                failure -> showFailure("Browser page read failed", failure)
        ));
        screenshot.addActionListener(event -> runTask(
                controller::browserScreenshot,
                result -> output.setText(result.output()),
                failure -> showFailure("Browser screenshot failed", failure)
        ));
        service.addActionListener(event -> runTask(
                () -> controller.serviceCall(selector.getText()),
                result -> output.setText(result.output()),
                failure -> showFailure("Service call failed", failure)
        ));

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        return panel;
    }

    private JComponent advancedBreakpointsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JTextField id = new JTextField();
        JTextField hook = new JTextField("BEFORE_STEP");
        JTextField signature = new JTextField();
        JTextField step = new JTextField("CONTROL API TEST STEP");
        JTextField phrase = new JTextField();
        JTextField lease = new JTextField("120");
        JCheckBox oneShot = new JCheckBox("One shot", true);
        JTextArea output = outputArea();

        JPanel fields = new JPanel(new GridLayout(6, 2, 6, 6));
        fields.add(new JLabel("Breakpoint ID"));
        fields.add(id);
        fields.add(new JLabel("Hook"));
        fields.add(hook);
        fields.add(new JLabel("Signature contains"));
        fields.add(signature);
        fields.add(new JLabel("Step contains"));
        fields.add(step);
        fields.add(new JLabel("Phrase contains"));
        fields.add(phrase);
        fields.add(new JLabel("Lease seconds"));
        fields.add(lease);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("Add");
        JButton list = new JButton("Refresh List");
        JButton remove = new JButton("Remove ID");
        JButton clear = new JButton("Clear All");
        buttons.add(oneShot);
        buttons.add(add);
        buttons.add(list);
        buttons.add(remove);
        buttons.add(clear);

        add.addActionListener(event -> runTask(
                () -> controller.addBreakpoint(
                        hook.getText(),
                        signature.getText(),
                        step.getText(),
                        phrase.getText(),
                        oneShot.isSelected(),
                        lease.getText()
                ),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Breakpoint add failed", failure)
        ));
        list.addActionListener(event -> runTask(
                controller::breakpoints,
                output::setText,
                failure -> showFailure("Breakpoint list failed", failure)
        ));
        remove.addActionListener(event -> runTask(
                () -> controller.removeBreakpoint(id.getText()),
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Breakpoint remove failed", failure)
        ));
        clear.addActionListener(event -> runTask(
                controller::clearBreakpoints,
                result -> output.setText(result.output() + "\n\n" + result.listing()),
                failure -> showFailure("Breakpoint clear failed", failure)
        ));

        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(fields, BorderLayout.CENTER);
        north.add(buttons, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        return panel;
    }

    private void closeWorkbench() {
        if (closing) return;
        closing = true;
        player.stop();
        mappingSaveTimer.stop();
        terminal.stop();
        activityLabel.setText("Closing Workbench...");
        runTask(
                () -> {
                    if (attach != null) attach.close();
                    controller.close();
                    return Boolean.TRUE;
                },
                ignored -> {
                    dispose();
                },
                failure -> {
                    dispose();
                }
        );
    }

    private static JTextArea outputArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        return area;
    }

    private static JButton playerButton(String text, String tooltip) {
        return WorkbenchTheme.flatButton(text, tooltip);
    }

    private static JButton smallPlayerButton(String text, String tooltip) {
        JButton button = playerButton(text, tooltip);
        button.setMargin(new Insets(1, 7, 1, 7));
        return button;
    }

    private static String defaultOverrideSource() {
        return """
                package tools.dscode.workbench.generated;

                import tools.dscode.control.api.MappingControl;
                import tools.dscode.control.override.StepOverrideContext;
                import tools.dscode.control.override.StepOverrideHandler;

                public final class {{CLASS_NAME}} implements StepOverrideHandler {
                    public Object execute(StepOverrideContext context) {
                        MappingControl.put(
                            "OVERRIDE",
                            "workbenchUiOverrideValue",
                            context.captures().isEmpty() ? "matched" : context.captures().getFirst()
                        );
                        return null;
                    }
                }
                """;
    }

}
