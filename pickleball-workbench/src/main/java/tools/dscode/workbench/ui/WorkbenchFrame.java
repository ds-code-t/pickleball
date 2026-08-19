package tools.dscode.workbench.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small execution-oriented Swing shell for Workbench lifecycle and live interaction. */
final class WorkbenchFrame extends JFrame {
    private final WorkbenchUiController controller;
    private final JButton syncButton = new JButton("Synchronize");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton startButton = new JButton("Start Worker");
    private final JButton restartButton = new JButton("Restart Worker");
    private final JButton stopButton = new JButton("Stop Worker");

    private final JButton executeStepButton = new JButton("Execute Step");
    private final JButton mappingGetButton = new JButton("Get");
    private final JButton mappingPutButton = new JButton("Put");
    private final JButton mappingResolveButton = new JButton("Resolve");
    private final JButton eventsRefreshButton = new JButton("Refresh Events");

    private final JButton overrideCompileButton = new JButton("Compile / Replace");
    private final JButton overrideRefreshButton = new JButton("Refresh List");
    private final JButton overrideRemoveButton = new JButton("Remove ID");
    private final JButton overrideClearButton = new JButton("Clear All");

    private final JButton browserPageButton = new JButton("Read Page");
    private final JButton browserScreenshotButton = new JButton("Capture Screenshot");
    private final JButton serviceCallButton = new JButton("Execute Service Call");

    private final JButton breakpointAddButton = new JButton("Add");
    private final JButton breakpointRefreshButton = new JButton("Refresh List");
    private final JButton breakpointRemoveButton = new JButton("Remove ID");
    private final JButton breakpointClearButton = new JButton("Clear All");

    private final JTextArea statusArea = outputArea();
    private final JTextField stepText = new JTextField("CONTROL API TEST STEP");
    private final JTextArea stepArgument = new JTextArea(4, 60);
    private final JTextArea liveOutput = outputArea();

    private final JTextField mappingReference = new JTextField("OVERRIDE");
    private final JTextField mappingKey = new JTextField("workbenchLiveValue");
    private final JTextField mappingValue = new JTextField("first");
    private final JTextField mappingInput = new JTextField("<workbenchLiveValue>");
    private final JTextArea mappingOutput = outputArea();
    private final JTextArea eventsArea = outputArea();

    private final JTextField overrideId = new JTextField("workbench-ui-generated");
    private final JTextField overrideRegex = new JTextField("^WORKBENCH UI OVERRIDE ([A-Za-z]+)$");
    private final JTextArea overrideSource = new JTextArea(defaultOverrideSource(), 16, 70);
    private final JTextArea overrideOutput = outputArea();
    private final JTextArea overrideList = outputArea();

    private final JTextArea browserOutput = outputArea();
    private final JTabbedPane browserEvidenceTabs = new JTabbedPane();
    private final JLabel screenshotLabel = new JLabel("No screenshot captured.", SwingConstants.CENTER);
    private final JTextField serviceSelector = new JTextField("%health-full-url");
    private final JTextArea serviceOutput = outputArea();

    private final JTextField breakpointId = new JTextField();
    private final JTextField breakpointHook = new JTextField("BEFORE_STEP");
    private final JTextField breakpointSignature = new JTextField();
    private final JTextField breakpointStep = new JTextField("CONTROL API TEST STEP");
    private final JTextField breakpointPhrase = new JTextField();
    private final JCheckBox breakpointOneShot = new JCheckBox("One shot", true);
    private final JTextField breakpointLease = new JTextField("120");
    private final JTextArea breakpointOutput = outputArea();
    private final JTextArea breakpointList = outputArea();

    private final JLabel activityLabel = new JLabel("Ready");
    private WorkbenchUiController.State lastState;
    private boolean closing;

    WorkbenchFrame(WorkbenchUiController controller) {
        super("Pickleball Workbench");
        this.controller = controller;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 650));
        setSize(1080, 780);
        setLocationByPlatform(true);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(syncButton);
        actions.add(refreshButton);
        actions.add(startButton);
        actions.add(restartButton);
        actions.add(stopButton);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Status", new JScrollPane(statusArea));
        tabs.addTab("Live Gherkin", livePanel());
        tabs.addTab("Mapping", mappingPanel());
        tabs.addTab("Recent Events", eventsPanel());
        tabs.addTab("Step Overrides", stepOverridePanel());
        tabs.addTab("Evidence", evidencePanel());
        tabs.addTab("Breakpoints", breakpointPanel());

        add(actions, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(activityLabel, BorderLayout.SOUTH);

        syncButton.addActionListener(event -> runStateAction("Synchronizing project", controller::synchronize));
        refreshButton.addActionListener(event -> runStateAction("Refreshing status", controller::refresh));
        startButton.addActionListener(event -> runStateAction("Starting worker", controller::startWorker));
        restartButton.addActionListener(event -> runStateAction("Restarting worker", controller::restartWorker));
        stopButton.addActionListener(event -> runStateAction("Stopping worker", controller::stopWorker));

        executeStepButton.addActionListener(event -> runLiveAction(
                "Executing live Gherkin",
                () -> controller.executeStep(stepText.getText(), stepArgument.getText()),
                liveOutput::setText
        ));
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
                () -> controller.compileStepOverride(
                        overrideId.getText(), overrideRegex.getText(), overrideSource.getText()
                ),
                overrideOutput,
                overrideList
        ));
        overrideRefreshButton.addActionListener(event -> runTextAction(
                "Refreshing Step Overrides",
                controller::stepOverrides,
                overrideList::setText
        ));
        overrideRemoveButton.addActionListener(event -> runManagementAction(
                "Removing Step Override",
                () -> controller.removeStepOverride(overrideId.getText()),
                overrideOutput,
                overrideList
        ));
        overrideClearButton.addActionListener(event -> runManagementAction(
                "Clearing Step Overrides",
                controller::clearStepOverrides,
                overrideOutput,
                overrideList
        ));

        browserPageButton.addActionListener(event -> runLiveAction(
                "Reading browser page evidence",
                controller::browserPage,
                browserOutput::setText
        ));
        browserScreenshotButton.addActionListener(event -> runBackground(
                "Capturing browser screenshot",
                controller::browserScreenshot,
                this::applyScreenshot
        ));
        serviceCallButton.addActionListener(event -> runLiveAction(
                "Executing service call",
                () -> controller.serviceCall(serviceSelector.getText()),
                serviceOutput::setText
        ));

        breakpointAddButton.addActionListener(event -> runManagementAction(
                "Adding breakpoint",
                () -> controller.addBreakpoint(
                        breakpointHook.getText(),
                        breakpointSignature.getText(),
                        breakpointStep.getText(),
                        breakpointPhrase.getText(),
                        breakpointOneShot.isSelected(),
                        breakpointLease.getText()
                ),
                breakpointOutput,
                breakpointList
        ));
        breakpointRefreshButton.addActionListener(event -> runTextAction(
                "Refreshing breakpoints",
                controller::breakpoints,
                breakpointList::setText
        ));
        breakpointRemoveButton.addActionListener(event -> runManagementAction(
                "Removing breakpoint",
                () -> controller.removeBreakpoint(breakpointId.getText()),
                breakpointOutput,
                breakpointList
        ));
        breakpointClearButton.addActionListener(event -> runManagementAction(
                "Clearing breakpoints",
                controller::clearBreakpoints,
                breakpointOutput,
                breakpointList
        ));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWorkbench();
            }
        });

        runStateAction("Loading project status", controller::refresh);
    }

    private JPanel livePanel() {
        JPanel input = new JPanel(new BorderLayout(6, 6));
        input.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel stepLine = new JPanel(new BorderLayout(6, 6));
        stepLine.add(new JLabel("Step"), BorderLayout.WEST);
        stepLine.add(stepText, BorderLayout.CENTER);
        stepLine.add(executeStepButton, BorderLayout.EAST);

        JPanel argument = new JPanel(new BorderLayout(6, 6));
        argument.add(new JLabel("Optional argument"), BorderLayout.NORTH);
        argument.add(new JScrollPane(stepArgument), BorderLayout.CENTER);

        input.add(stepLine, BorderLayout.NORTH);
        input.add(argument, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, input, new JScrollPane(liveOutput));
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel mappingPanel() {
        JPanel fields = new JPanel(new GridLayout(4, 2, 6, 6));
        fields.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        fields.add(new JLabel("Mapping reference"));
        fields.add(mappingReference);
        fields.add(new JLabel("Key"));
        fields.add(mappingKey);
        fields.add(new JLabel("Value (text)"));
        fields.add(mappingValue);
        fields.add(new JLabel("Resolve input"));
        fields.add(mappingInput);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(mappingGetButton);
        actions.add(mappingPutButton);
        actions.add(mappingResolveButton);

        JPanel controls = new JPanel(new BorderLayout());
        controls.add(fields, BorderLayout.CENTER);
        controls.add(actions, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, controls, new JScrollPane(mappingOutput));
        split.setResizeWeight(0.42);
        split.setBorder(null);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel eventsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
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
        source.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        source.add(fields, BorderLayout.NORTH);
        source.add(new JScrollPane(overrideSource), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(overrideCompileButton);
        actions.add(overrideRefreshButton);
        actions.add(overrideRemoveButton);
        actions.add(overrideClearButton);
        source.add(actions, BorderLayout.SOUTH);

        JTabbedPane outputTabs = new JTabbedPane();
        outputTabs.addTab("Result", new JScrollPane(overrideOutput));
        outputTabs.addTab("Installed", new JScrollPane(overrideList));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, source, outputTabs);
        split.setResizeWeight(0.62);
        split.setBorder(null);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel evidencePanel() {
        JTabbedPane evidenceTabs = new JTabbedPane();
        evidenceTabs.addTab("Browser", browserEvidencePanel());
        evidenceTabs.addTab("Service Call", serviceEvidencePanel());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(evidenceTabs, BorderLayout.CENTER);
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
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
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
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
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
        controls.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        controls.add(fields, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(breakpointAddButton);
        actions.add(breakpointRefreshButton);
        actions.add(breakpointRemoveButton);
        actions.add(breakpointClearButton);
        controls.add(actions, BorderLayout.SOUTH);

        JTabbedPane outputTabs = new JTabbedPane();
        outputTabs.addTab("Result", new JScrollPane(breakpointOutput));
        outputTabs.addTab("Installed", new JScrollPane(breakpointList));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, controls, outputTabs);
        split.setResizeWeight(0.5);
        split.setBorder(null);

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
        statusArea.setText(state.render());
        statusArea.setCaretPosition(0);
        if (!state.workerRunning()) {
            screenshotLabel.setIcon(null);
            screenshotLabel.setText("No screenshot captured.");
        }
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

    private void showFailure(String label, Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        activityLabel.setText(label + " failed: " + ((message == null || message.isBlank())
                ? String.valueOf(failure)
                : message));
    }

    private void restoreControls() {
        if (closing) return;
        boolean running = lastState != null && lastState.workerRunning();
        boolean liveReady = lastState != null && lastState.liveReady();
        syncButton.setEnabled(!running);
        refreshButton.setEnabled(true);
        startButton.setEnabled(lastState != null && lastState.synchronizedProject() && !running);
        restartButton.setEnabled(running);
        stopButton.setEnabled(running);

        executeStepButton.setEnabled(liveReady);
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
        syncButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        startButton.setEnabled(enabled);
        restartButton.setEnabled(enabled);
        stopButton.setEnabled(enabled);

        executeStepButton.setEnabled(enabled);
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
}
