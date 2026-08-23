package tools.dscode.workbench.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.workbench.player.LiveScenarioPlayer;

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
    private final LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();
    private final ObjectMapper json = new ObjectMapper();

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

    private final JTextArea terminalArea = outputArea();

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
        super("Pickleball Workbench");
        this.controller = controller;

        mappingSaveTimer.setRepeats(false);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 720));
        setSize(1480, 900);
        setLocationByPlatform(true);
        setJMenuBar(menuBar());

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.add(playerBar(), BorderLayout.NORTH);

        JSplitPane workspace = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                leftWorkspace(),
                rightWorkspace()
        );
        workspace.setResizeWeight(0.52);
        workspace.setDividerLocation(760);
        root.add(workspace, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        configureScenarioEditor();
        configureStepEditor();
        configureMappingEditor();
        wirePlayerActions();
        wireSessionActions();
        syncScenarioView();
        updatePlayerView(null);

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

    private JPanel playerBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));

        JPanel project = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        project.add(projectLabel);
        project.add(readinessLabel);
        bar.add(project, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        controls.add(playButton);
        controls.add(pauseButton);
        controls.add(playerStopButton);
        bar.add(controls, BorderLayout.CENTER);

        JPanel state = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        state.add(new JLabel("Status:"));
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
        panel.setBorder(BorderFactory.createTitledBorder("Live Scenario Editor"));

        scenarioEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        scenarioEditor.setLineWrap(false);
        scenarioEditor.setTabSize(2);
        panel.add(new JScrollPane(scenarioEditor), BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 2));
        legend.add(new JLabel("Click a step to move the playhead"));
        legend.add(new JLabel("Global Play starts from the first step"));
        legend.add(new JLabel("Edit Gherkin in place"));
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
        tabs.addTab("Terminal", terminalPanel());
        tabs.addTab("Diagnostic Log Explorer", diagnosticsPanel());
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
        return panel;
    }

    private JPanel terminalPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout());
        JLabel note = new JLabel("Live player and Workbench activity");
        top.add(note, BorderLayout.WEST);
        JButton clear = new JButton("Clear");
        clear.addActionListener(event -> terminalArea.setText(""));
        top.add(clear, BorderLayout.EAST);

        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(terminalArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel diagnosticsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JTextArea message = outputArea();
        message.setText("""
                Diagnostic Log Explorer foundation

                This tab remains intentionally separate from the live scenario player.
                It should continue to bind to Pickleball's retained diagnostic artifacts
                rather than inventing a second diagnostic store in Workbench.
                """);
        message.setCaretPosition(0);
        panel.add(new JScrollPane(message), BorderLayout.CENTER);
        return panel;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        activityLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        footer.add(activityLabel, BorderLayout.CENTER);
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
            player.pause();
            updatePlayerView(playbackBusy
                    ? "Pause requested; the current step will finish first."
                    : "Scenario playback paused.");
        });
        playerStopButton.addActionListener(event -> {
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

                    if (result.successful()) {
                        player.markCurrentStepExecuted(step.id());
                    } else {
                        player.markCurrentStepFailed(step.id());
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
                    try {
                        String formatted = json.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(snapshot.values());
                        setMappingEditor(formatted, snapshot.restorable());
                        mappingStatus.setText(
                                snapshot.restorable()
                                        ? "Live JSON snapshot. Valid edits are applied automatically."
                                        : "Inspection only: this NodeMap implementation is not safely restorable."
                        );
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
        if (syncingScenarioDocument) return;
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
        if (syncingScenarioDocument) return;
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
    }

    private void updateFromHereAvailability() {
        fromHereButton.setEnabled(
                player.selectedLine()
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
        if (!terminalArea.getText().isEmpty()) terminalArea.append("\n\n");
        terminalArea.append(heading + "\n");
        if (output != null && !output.isBlank()) terminalArea.append(output + "\n");
        if (events != null && !events.isBlank()) {
            terminalArea.append("Events\n" + events + "\n");
        }
        terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
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
                    if (state.liveReady()) refreshMappingCatalog();
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
        activityLabel.setText("Closing Workbench...");
        runTask(
                () -> {
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
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
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
