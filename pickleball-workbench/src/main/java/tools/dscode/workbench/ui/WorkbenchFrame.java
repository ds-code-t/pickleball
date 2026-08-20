package tools.dscode.workbench.ui;

import tools.dscode.workbench.player.LiveScenarioPlayer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Player-style Swing presentation adapter over the shared Workbench service seam. */
final class WorkbenchFrame extends JFrame {
    private final WorkbenchUiController controller;
    private final LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();

    private final DefaultListModel<LiveScenarioPlayer.Line> scenarioModel = new DefaultListModel<>();
    private final JList<LiveScenarioPlayer.Line> scenarioList = new JList<>(scenarioModel);
    private final JTextField stepText = new JTextField();

    private final JButton firstButton = playerButton("⏮", "First step (navigation only; does not undo runtime side effects)");
    private final JButton backButton = playerButton("◀", "Previous step (navigation only; does not undo runtime side effects)");
    private final JButton playButton = playerButton("▶", "Phase 1 player-state control; runtime run loop is added in Phase 2");
    private final JButton pauseButton = playerButton("⏸", "Pause player advancement at the next safe boundary");
    private final JButton playerStopButton = playerButton("■", "Stop the live player state");
    private final JButton isolatedStepButton = smallPlayerButton("▶", "Execute the Step Editor text in isolation");

    private final JLabel projectLabel = new JLabel("Project: loading...");
    private final JLabel readinessLabel = new JLabel("Loading status...");
    private final JLabel playerStatusLabel = new JLabel("Stopped");
    private final JLabel activityLabel = new JLabel("Ready");

    private final JMenuItem syncItem = new JMenuItem("Synchronize");
    private final JMenuItem refreshItem = new JMenuItem("Refresh Status");
    private final JMenuItem startItem = new JMenuItem("Start Worker");
    private final JMenuItem restartItem = new JMenuItem("Restart Worker");
    private final JMenuItem stopItem = new JMenuItem("Stop Worker");

    private final JButton mappingGetButton = new JButton("Get");
    private final JButton mappingPutButton = new JButton("Put");
    private final JButton mappingResolveButton = new JButton("Resolve");
    private final JComboBox<String> nodeMapSelector = new JComboBox<>();
    private final JTextField mappingReference = new JTextField("OVERRIDE");
    private final JTextField mappingKey = new JTextField("workbenchLiveValue");
    private final JTextField mappingValue = new JTextField("first");
    private final JTextField mappingInput = new JTextField("<workbenchLiveValue>");
    private final JTextArea mappingOutput = outputArea();

    private final JTextArea terminalArea = outputArea();

    private final JTextArea statusArea = outputArea();
    private final JButton eventsRefreshButton = new JButton("Refresh Events");
    private final JTextArea eventsArea = outputArea();

    private final JButton overrideCompileButton = new JButton("Compile / Replace");
    private final JButton overrideRefreshButton = new JButton("Refresh List");
    private final JButton overrideRemoveButton = new JButton("Remove ID");
    private final JButton overrideClearButton = new JButton("Clear All");
    private final JTextField overrideId = new JTextField("workbench-ui-generated");
    private final JTextField overrideRegex = new JTextField("^WORKBENCH UI OVERRIDE ([A-Za-z]+)$");
    private final JTextArea overrideSource = new JTextArea(defaultOverrideSource(), 16, 70);
    private final JTextArea overrideOutput = outputArea();
    private final JTextArea overrideList = outputArea();

    private final JButton browserPageButton = new JButton("Read Page");
    private final JButton browserScreenshotButton = new JButton("Capture Screenshot");
    private final JTextArea browserOutput = outputArea();
    private final JTabbedPane browserEvidenceTabs = new JTabbedPane();
    private final JLabel screenshotLabel = new JLabel("No screenshot captured.", SwingConstants.CENTER);
    private final JButton serviceCallButton = new JButton("Execute Service Call");
    private final JTextField serviceSelector = new JTextField("%health-full-url");
    private final JTextArea serviceOutput = outputArea();

    private final JButton breakpointAddButton = new JButton("Add");
    private final JButton breakpointRefreshButton = new JButton("Refresh List");
    private final JButton breakpointRemoveButton = new JButton("Remove ID");
    private final JButton breakpointClearButton = new JButton("Clear All");
    private final JTextField breakpointId = new JTextField();
    private final JTextField breakpointHook = new JTextField("BEFORE_STEP");
    private final JTextField breakpointSignature = new JTextField();
    private final JTextField breakpointStep = new JTextField("CONTROL API TEST STEP");
    private final JTextField breakpointPhrase = new JTextField();
    private final JCheckBox breakpointOneShot = new JCheckBox("One shot", true);
    private final JTextField breakpointLease = new JTextField("120");
    private final JTextArea breakpointOutput = outputArea();
    private final JTextArea breakpointList = outputArea();

    private WorkbenchUiController.State lastState;
    private JDialog advancedDialog;
    private boolean syncingScenarioSelection;
    private boolean closing;

    WorkbenchFrame(WorkbenchUiController controller) {
        super("Pickleball Workbench");
        this.controller = controller;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 720));
        setSize(1480, 900);
        setLocationByPlatform(true);
        setJMenuBar(menuBar());

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.add(playerBar(), BorderLayout.NORTH);

        JSplitPane workspace = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftWorkspace(), rightWorkspace());
        workspace.setResizeWeight(0.52);
        workspace.setDividerLocation(760);
        root.add(workspace, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        wirePlayerActions();
        wireRuntimeActions();
        configureScenarioEditor();
        configureStepEditor();
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
        controls.add(firstButton);
        controls.add(backButton);
        controls.add(playButton);
        controls.add(pauseButton);
        controls.add(playerStopButton);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(new JLabel("Speed:"));
        JComboBox<String> speed = new JComboBox<>(new String[]{"0.5x", "1.0x", "2.0x"});
        speed.setSelectedItem("1.0x");
        speed.setEnabled(false);
        speed.setToolTipText("Runtime playback speed is introduced with the Phase 2 execution loop.");
        controls.add(speed);
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

        scenarioList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        scenarioList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scenarioList.setFixedCellHeight(26);
        scenarioList.setCellRenderer(new ScenarioRenderer());
        panel.add(new JScrollPane(scenarioList), BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 2));
        legend.add(new JLabel("▶ Playhead"));
        legend.add(new JLabel("Selected line = highlight"));
        legend.add(new JLabel("Executed = dimmed"));
        legend.add(new JLabel("Failed = red"));
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
        JLabel title = new JLabel("Step Editor / Command");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title);
        header.add(isolatedStepButton);
        panel.add(header, BorderLayout.NORTH);

        stepText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        stepText.setToolTipText("Enter inserts ahead of the playhead. Ctrl+Enter updates the selected pending step.");
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

    private JPanel mappingPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel selector = new JPanel(new BorderLayout(6, 0));
        selector.add(new JLabel("NodeMap:"), BorderLayout.WEST);
        nodeMapSelector.setEnabled(false);
        nodeMapSelector.setToolTipText("Populated from the selected step ParsingMap after the Phase 3 bridge contract is added.");
        selector.add(nodeMapSelector, BorderLayout.CENTER);
        JLabel pending = new JLabel("ParsingMap inspection API required");
        pending.setForeground(Color.GRAY);
        selector.add(pending, BorderLayout.EAST);
        panel.add(selector, BorderLayout.NORTH);

        JPanel legacy = new JPanel(new BorderLayout(6, 6));
        legacy.setBorder(BorderFactory.createTitledBorder("Existing Mapping operations"));

        JPanel fields = new JPanel(new GridLayout(4, 2, 6, 6));
        fields.add(new JLabel("Mapping reference"));
        fields.add(mappingReference);
        fields.add(new JLabel("Key"));
        fields.add(mappingKey);
        fields.add(new JLabel("Value (text)"));
        fields.add(mappingValue);
        fields.add(new JLabel("Resolve input"));
        fields.add(mappingInput);
        legacy.add(fields, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(mappingGetButton);
        actions.add(mappingPutButton);
        actions.add(mappingResolveButton);
        legacy.add(actions, BorderLayout.CENTER);
        legacy.add(new JScrollPane(mappingOutput), BorderLayout.SOUTH);
        mappingOutput.setRows(10);

        panel.add(legacy, BorderLayout.CENTER);
        return panel;
    }

    private JPanel terminalPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout(6, 0));
        JLabel note = new JLabel("Workbench activity; worker log streaming is introduced in Phase 4.");
        note.setForeground(Color.GRAY);
        top.add(note, BorderLayout.CENTER);
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

                Phase 5 will bind this tab to Pickleball's retained diagnostic artifacts using the existing
                run-catalog -> run-index/clusters -> summary -> events -> visual evidence escalation model.

                No fake run catalog or competing diagnostic storage is created by the Swing UI.
                """);
        message.setCaretPosition(0);
        panel.add(new JScrollPane(message), BorderLayout.CENTER);
        return panel;
    }

    private void configureScenarioEditor() {
        scenarioList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || syncingScenarioSelection) return;
            LiveScenarioPlayer.Line selected = scenarioList.getSelectedValue();
            if (selected == null) {
                player.clearSelection();
                return;
            }
            player.select(selected.id());
            if (selected.executable()) stepText.setText(selected.text());
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

    private void wirePlayerActions() {
        firstButton.addActionListener(event -> {
            player.movePlayheadToFirstStep();
            updatePlayerView("Playhead moved to first step. Navigation does not undo runtime side effects.");
        });
        backButton.addActionListener(event -> {
            player.movePlayheadToPreviousStep();
            updatePlayerView("Playhead moved back. Navigation does not undo runtime side effects.");
        });
        playButton.addActionListener(event -> {
            player.play();
            updatePlayerView("Player state updated. Buffered automatic execution is implemented in Phase 2.");
        });
        pauseButton.addActionListener(event -> {
            player.pause();
            updatePlayerView("Player paused.");
        });
        playerStopButton.addActionListener(event -> {
            player.stop();
            updatePlayerView("Player stopped. Worker lifecycle remains available from Session.");
        });
        isolatedStepButton.addActionListener(event -> executeIsolatedStep());
    }

    private void wireRuntimeActions() {
        syncItem.addActionListener(event -> runStateAction("Synchronizing project", controller::synchronize));
        refreshItem.addActionListener(event -> runStateAction("Refreshing status", controller::refresh));
        startItem.addActionListener(event -> runStateAction("Starting worker", controller::startWorker));
        restartItem.addActionListener(event -> runStateAction("Restarting worker", controller::restartWorker));
        stopItem.addActionListener(event -> runStateAction("Stopping worker", controller::stopWorker));

        mappingGetButton.addActionListener(event -> runLiveAction(
                "Reading Mapping value",
                () -> controller.mappingGet(mappingReference.getText(), mappingKey.getText()),
                mappingOutput::setText
        ));
        mappingPutButton.addActionListener(event -> runLiveAction(
                "Writing Mapping value",
                () -> controller.mappingPut(mappingReference.getText(), mappingKey.getText(), mappingValue.getText()),
                mappingOutput::setText
        ));
        mappingResolveButton.addActionListener(event -> runLiveAction(
                "Resolving Mapping input",
                () -> controller.mappingResolve(mappingInput.getText()),
                mappingOutput::setText
        ));
        eventsRefreshButton.addActionListener(event -> runTextAction(
                "Refreshing semantic events",
                controller::refreshEvents,
                this::appendEvents
        ));

        overrideCompileButton.addActionListener(event -> runManagementAction(
                "Compiling Step Override",
                () -> controller.compileStepOverride(overrideId.getText(), overrideRegex.getText(), overrideSource.getText()),
                overrideOutput,
                overrideList
        ));
        overrideRefreshButton.addActionListener(event -> runTextAction(
                "Refreshing Step Overrides", controller::stepOverrides, overrideList::setText));
        overrideRemoveButton.addActionListener(event -> runManagementAction(
                "Removing Step Override", () -> controller.removeStepOverride(overrideId.getText()), overrideOutput, overrideList));
        overrideClearButton.addActionListener(event -> runManagementAction(
                "Clearing Step Overrides", controller::clearStepOverrides, overrideOutput, overrideList));

        browserPageButton.addActionListener(event -> runLiveAction(
                "Reading browser page evidence", controller::browserPage, browserOutput::setText));
        browserScreenshotButton.addActionListener(event -> runBackground(
                "Capturing browser screenshot", controller::browserScreenshot, this::applyScreenshot));
        serviceCallButton.addActionListener(event -> runLiveAction(
                "Executing service call", () -> controller.serviceCall(serviceSelector.getText()), serviceOutput::setText));

        breakpointAddButton.addActionListener(event -> runManagementAction(
                "Adding breakpoint",
                () -> controller.addBreakpoint(
                        breakpointHook.getText(), breakpointSignature.getText(), breakpointStep.getText(),
                        breakpointPhrase.getText(), breakpointOneShot.isSelected(), breakpointLease.getText()),
                breakpointOutput,
                breakpointList
        ));
        breakpointRefreshButton.addActionListener(event -> runTextAction(
                "Refreshing breakpoints", controller::breakpoints, breakpointList::setText));
        breakpointRemoveButton.addActionListener(event -> runManagementAction(
                "Removing breakpoint", () -> controller.removeBreakpoint(breakpointId.getText()), breakpointOutput, breakpointList));
        breakpointClearButton.addActionListener(event -> runManagementAction(
                "Clearing breakpoints", controller::clearBreakpoints, breakpointOutput, breakpointList));
    }

    private void insertStep() {
        try {
            LiveScenarioPlayer.Line inserted = player.insertStep(stepText.getText());
            syncScenarioView();
            appendTerminal("Inserted live buffer step #" + inserted.id() + ": " + inserted.text());
            updatePlayerView(null);
        } catch (RuntimeException failure) {
            showFailure("Insert step", failure);
        }
    }

    private void updateSelectedStep() {
        try {
            LiveScenarioPlayer.Line updated = player.updateSelectedStep(stepText.getText());
            syncScenarioView();
            appendTerminal("Updated pending live buffer step #" + updated.id() + ": " + updated.text());
            updatePlayerView(null);
        } catch (RuntimeException failure) {
            showFailure("Update selected step", failure);
        }
    }

    private void executeIsolatedStep() {
        String text = stepText.getText();
        if (text == null || text.isBlank()) {
            showFailure("Execute isolated step", new IllegalArgumentException("Gherkin step must not be blank."));
            return;
        }
        player.pauseForIsolatedExecution();
        updatePlayerView("Main player paused for isolated execution.");
        runLiveAction(
                "Executing isolated Gherkin",
                () -> controller.executeStep(text, ""),
                output -> appendTerminal("Isolated step\n" + output)
        );
    }

    private void syncScenarioView() {
        Long selectedId = player.selectedId().isPresent() ? player.selectedId().getAsLong() : null;
        syncingScenarioSelection = true;
        try {
            scenarioModel.clear();
            int selectedIndex = -1;
            int index = 0;
            for (LiveScenarioPlayer.Line line : player.lines()) {
                scenarioModel.addElement(line);
                if (selectedId != null && line.id() == selectedId) selectedIndex = index;
                index++;
            }
            if (selectedIndex >= 0) scenarioList.setSelectedIndex(selectedIndex);
        } finally {
            syncingScenarioSelection = false;
        }
        scenarioList.repaint();
    }

    private void updatePlayerView(String activity) {
        playerStatusLabel.setText(switch (player.state()) {
            case STOPPED -> "Stopped";
            case PAUSED -> "Paused";
            case RUNNING -> "Running";
            case WAITING_FOR_STEP -> "Waiting for next step...";
        });
        if (activity != null && !activity.isBlank()) {
            activityLabel.setText(activity);
            appendTerminal(activity);
        }
        scenarioList.repaint();
        restoreControls();
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(activityLabel, BorderLayout.WEST);
        JLabel hint = new JLabel("Session lifecycle: Session menu   •   Existing investigation tools: Tools > Advanced Controls");
        hint.setForeground(Color.GRAY);
        panel.add(hint, BorderLayout.EAST);
        return panel;
    }

    private void showAdvancedControls() {
        if (advancedDialog == null) {
            advancedDialog = new JDialog(this, "Workbench Advanced Controls", false);
            advancedDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            advancedDialog.setSize(980, 720);
            advancedDialog.setLocationRelativeTo(this);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Status", new JScrollPane(statusArea));
            tabs.addTab("Recent Events", eventsPanel());
            tabs.addTab("Step Overrides", stepOverridePanel());
            tabs.addTab("Evidence", evidencePanel());
            tabs.addTab("Breakpoints", breakpointPanel());
            advancedDialog.setContentPane(tabs);
        }
        advancedDialog.setVisible(true);
        advancedDialog.toFront();
    }

    private JPanel eventsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(eventsRefreshButton, BorderLayout.NORTH);
        panel.add(new JScrollPane(eventsArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel stepOverridePanel() {
        JPanel fields = new JPanel(new GridLayout(2, 2, 6, 6));
        fields.add(new JLabel("ID"));
        fields.add(overrideId);
        fields.add(new JLabel("Regex"));
        fields.add(overrideRegex);

        JPanel source = new JPanel(new BorderLayout(6, 6));
        source.setBorder(new EmptyBorder(8, 8, 8, 8));
        source.add(fields, BorderLayout.NORTH);
        source.add(new JScrollPane(overrideSource), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(overrideCompileButton);
        actions.add(overrideRefreshButton);
        actions.add(overrideRemoveButton);
        actions.add(overrideClearButton);
        source.add(actions, BorderLayout.SOUTH);

        JTabbedPane outputs = new JTabbedPane();
        outputs.addTab("Result", new JScrollPane(overrideOutput));
        outputs.addTab("Installed", new JScrollPane(overrideList));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, source, outputs);
        split.setResizeWeight(0.62);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel evidencePanel() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Browser", browserEvidencePanel());
        tabs.addTab("Service Call", serviceEvidencePanel());
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(tabs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel browserEvidencePanel() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(browserPageButton);
        actions.add(browserScreenshotButton);

        screenshotLabel.setVerticalAlignment(SwingConstants.TOP);
        JScrollPane screenshotScroll = new JScrollPane(screenshotLabel);
        screenshotScroll.getVerticalScrollBar().setUnitIncrement(16);
        screenshotScroll.getHorizontalScrollBar().setUnitIncrement(16);

        browserEvidenceTabs.addTab("Page Evidence", new JScrollPane(browserOutput));
        browserEvidenceTabs.addTab("Screenshot", screenshotScroll);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(actions, BorderLayout.NORTH);
        panel.add(browserEvidenceTabs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel serviceEvidencePanel() {
        JPanel controls = new JPanel(new BorderLayout(6, 6));
        controls.add(new JLabel("Selector"), BorderLayout.WEST);
        controls.add(serviceSelector, BorderLayout.CENTER);
        controls.add(serviceCallButton, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(new JScrollPane(serviceOutput), BorderLayout.CENTER);
        return panel;
    }

    private JPanel breakpointPanel() {
        JPanel fields = new JPanel(new GridLayout(7, 2, 6, 6));
        fields.add(new JLabel("Breakpoint ID (for remove)"));
        fields.add(breakpointId);
        fields.add(new JLabel("Hook"));
        fields.add(breakpointHook);
        fields.add(new JLabel("Signature contains"));
        fields.add(breakpointSignature);
        fields.add(new JLabel("Step contains"));
        fields.add(breakpointStep);
        fields.add(new JLabel("Phrase contains"));
        fields.add(breakpointPhrase);
        fields.add(new JLabel("Lease seconds"));
        fields.add(breakpointLease);
        fields.add(new JLabel("Behavior"));
        fields.add(breakpointOneShot);

        JPanel controls = new JPanel(new BorderLayout());
        controls.setBorder(new EmptyBorder(8, 8, 8, 8));
        controls.add(fields, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(breakpointAddButton);
        actions.add(breakpointRefreshButton);
        actions.add(breakpointRemoveButton);
        actions.add(breakpointClearButton);
        controls.add(actions, BorderLayout.SOUTH);

        JTabbedPane outputs = new JTabbedPane();
        outputs.addTab("Result", new JScrollPane(breakpointOutput));
        outputs.addTab("Installed", new JScrollPane(breakpointList));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, controls, outputs);
        split.setResizeWeight(0.5);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void runStateAction(String label, Supplier<WorkbenchUiController.State> action) {
        runBackground(label, action, this::applyState);
    }

    private void runLiveAction(
            String label,
            Supplier<WorkbenchUiController.LiveActionResult> action,
            Consumer<String> output
    ) {
        runBackground(label, action, result -> {
            output.accept(result.output());
            appendEvents(result.events());
        });
    }

    private void runManagementAction(
            String label,
            Supplier<WorkbenchUiController.ManagementResult> action,
            JTextArea output,
            JTextArea listing
    ) {
        runBackground(label, action, result -> {
            output.setText(result.output());
            listing.setText(result.listing());
        });
    }

    private void runTextAction(String label, Supplier<String> action, Consumer<String> output) {
        runBackground(label, action, output);
    }

    private <T> void runBackground(String label, Supplier<T> action, Consumer<T> success) {
        if (closing) return;
        setControlsEnabled(false);
        activityLabel.setText(label + "...");
        appendTerminal(label + "...");

        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return action.get();
            }

            @Override
            protected void done() {
                try {
                    success.accept(get());
                    activityLabel.setText(label + " complete.");
                    appendTerminal(label + " complete.");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    showFailure(label, failure);
                } catch (ExecutionException failure) {
                    showFailure(label, failure.getCause());
                } finally {
                    restoreControls();
                }
            }
        }.execute();
    }

    private void applyState(WorkbenchUiController.State state) {
        lastState = state;
        projectLabel.setText("Project: " + displayProject(state));
        readinessLabel.setText(readiness(state));
        statusArea.setText(state.render());
        statusArea.setCaretPosition(0);
        if (!state.workerRunning()) {
            screenshotLabel.setIcon(null);
            screenshotLabel.setText("No screenshot captured.");
        }
    }

    private static String displayProject(WorkbenchUiController.State state) {
        return state.projectRoot().getFileName() == null
                ? state.projectRoot().toString()
                : state.projectRoot().getFileName().toString();
    }

    private static String readiness(WorkbenchUiController.State state) {
        if (!state.synchronizedProject()) return "Synchronization required";
        if (!state.workerRunning()) return "Synchronized";
        return state.liveReady() ? "Ready" : "Worker running";
    }

    private void applyScreenshot(WorkbenchUiController.ScreenshotResult result) {
        browserOutput.setText(result.output());
        appendEvents(result.events());
        if (result.png() == null || result.png().length == 0) {
            screenshotLabel.setIcon(null);
            screenshotLabel.setText("No screenshot returned.");
            return;
        }
        screenshotLabel.setText(null);
        screenshotLabel.setIcon(new ImageIcon(result.png()));
        browserEvidenceTabs.setSelectedIndex(1);
    }

    private void appendEvents(String text) {
        if (text == null || text.isBlank()) return;
        if (!eventsArea.getText().isBlank()) eventsArea.append("\n\n");
        eventsArea.append(text);
        eventsArea.setCaretPosition(eventsArea.getDocument().getLength());
    }

    private void appendTerminal(String text) {
        if (text == null || text.isBlank()) return;
        if (!terminalArea.getText().isBlank()) terminalArea.append("\n");
        terminalArea.append(text.stripTrailing() + "\n");
        terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
    }

    private void showFailure(String label, Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        String detail = (message == null || message.isBlank()) ? String.valueOf(failure) : message;
        activityLabel.setText(label + " failed: " + detail);
        appendTerminal(label + " failed: " + detail);
    }

    private void restoreControls() {
        if (closing) return;
        boolean running = lastState != null && lastState.workerRunning();
        boolean liveReady = lastState != null && lastState.liveReady();

        syncItem.setEnabled(!running);
        refreshItem.setEnabled(true);
        startItem.setEnabled(lastState != null && lastState.synchronizedProject() && !running);
        restartItem.setEnabled(running);
        stopItem.setEnabled(running);

        boolean hasSteps = player.lines().stream().anyMatch(LiveScenarioPlayer.Line::executable);
        firstButton.setEnabled(hasSteps);
        backButton.setEnabled(hasSteps);
        playButton.setEnabled(true);
        pauseButton.setEnabled(player.state() == LiveScenarioPlayer.State.RUNNING
                || player.state() == LiveScenarioPlayer.State.WAITING_FOR_STEP);
        playerStopButton.setEnabled(player.state() != LiveScenarioPlayer.State.STOPPED);
        isolatedStepButton.setEnabled(liveReady);
        stepText.setEnabled(true);
        scenarioList.setEnabled(true);

        mappingGetButton.setEnabled(liveReady);
        mappingPutButton.setEnabled(liveReady);
        mappingResolveButton.setEnabled(liveReady);
        eventsRefreshButton.setEnabled(liveReady);
        overrideCompileButton.setEnabled(liveReady);
        overrideRefreshButton.setEnabled(liveReady);
        overrideRemoveButton.setEnabled(liveReady);
        overrideClearButton.setEnabled(liveReady);
        browserPageButton.setEnabled(liveReady);
        browserScreenshotButton.setEnabled(liveReady);
        serviceCallButton.setEnabled(liveReady);
        breakpointAddButton.setEnabled(liveReady);
        breakpointRefreshButton.setEnabled(liveReady);
        breakpointRemoveButton.setEnabled(liveReady);
        breakpointClearButton.setEnabled(liveReady);
    }

    private void setControlsEnabled(boolean enabled) {
        syncItem.setEnabled(enabled);
        refreshItem.setEnabled(enabled);
        startItem.setEnabled(enabled);
        restartItem.setEnabled(enabled);
        stopItem.setEnabled(enabled);

        firstButton.setEnabled(enabled);
        backButton.setEnabled(enabled);
        playButton.setEnabled(enabled);
        pauseButton.setEnabled(enabled);
        playerStopButton.setEnabled(enabled);
        isolatedStepButton.setEnabled(enabled);
        stepText.setEnabled(enabled);
        scenarioList.setEnabled(enabled);

        mappingGetButton.setEnabled(enabled);
        mappingPutButton.setEnabled(enabled);
        mappingResolveButton.setEnabled(enabled);
        eventsRefreshButton.setEnabled(enabled);
        overrideCompileButton.setEnabled(enabled);
        overrideRefreshButton.setEnabled(enabled);
        overrideRemoveButton.setEnabled(enabled);
        overrideClearButton.setEnabled(enabled);
        browserPageButton.setEnabled(enabled);
        browserScreenshotButton.setEnabled(enabled);
        serviceCallButton.setEnabled(enabled);
        breakpointAddButton.setEnabled(enabled);
        breakpointRefreshButton.setEnabled(enabled);
        breakpointRemoveButton.setEnabled(enabled);
        breakpointClearButton.setEnabled(enabled);
    }

    private void closeWorkbench() {
        if (closing) return;
        closing = true;
        setControlsEnabled(false);
        activityLabel.setText("Stopping Workbench resources...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                controller.close();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    System.err.println("Workbench UI close failed: "
                            + (cause == null ? failure.getMessage() : cause.getMessage()));
                } finally {
                    dispose();
                }
            }
        }.execute();
    }

    private static JButton playerButton(String glyph, String tooltip) {
        JButton button = new JButton(glyph);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(46, 32));
        return button;
    }

    private static JButton smallPlayerButton(String glyph, String tooltip) {
        JButton button = new JButton(glyph);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(1, 7, 1, 7));
        button.setFocusable(false);
        return button;
    }

    private static JTextArea outputArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setMargin(new Insets(8, 8, 8, 8));
        return area;
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
                            "workbenchStepOverrideValue",
                            "ui-" + context.captures().getFirst()
                        );
                        return null;
                    }
                }
                """;
    }

    private final class ScenarioRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean selected,
                boolean focus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            LiveScenarioPlayer.Line line = (LiveScenarioPlayer.Line) value;
            boolean playhead = index == player.playheadIndex();
            label.setText("%s%3d   %s".formatted(playhead ? "▶ " : "  ", index + 1, line.text()));

            if (!selected) {
                if (line.executionStatus() == LiveScenarioPlayer.ExecutionStatus.EXECUTED) {
                    label.setForeground(Color.GRAY);
                } else if (line.executionStatus() == LiveScenarioPlayer.ExecutionStatus.FAILED) {
                    label.setForeground(new Color(180, 55, 55));
                } else if (playhead) {
                    label.setForeground(new Color(40, 140, 70));
                } else if (line.type() == LiveScenarioPlayer.LineType.COMMENT) {
                    label.setForeground(Color.GRAY);
                }
            }
            return label;
        }
    }

}
