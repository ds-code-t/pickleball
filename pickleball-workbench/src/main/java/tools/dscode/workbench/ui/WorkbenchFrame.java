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
    private final JLabel activityLabel = new JLabel("Ready");
    private WorkbenchUiController.State lastState;
    private boolean closing;

    WorkbenchFrame(WorkbenchUiController controller) {
        super("Pickleball Workbench");
        this.controller = controller;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 580));
        setSize(940, 680);
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
        panel.add(eventsRefreshButton, BorderLayout.NORTH);
        panel.add(new JScrollPane(eventsArea), BorderLayout.CENTER);
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
    }

    private void appendEvents(String text) {
        if (text == null || text.isBlank()) return;
        if (!eventsArea.getText().isBlank()) eventsArea.append("\n");
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
}
