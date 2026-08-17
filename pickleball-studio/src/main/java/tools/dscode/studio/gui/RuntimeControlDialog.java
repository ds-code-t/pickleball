package tools.dscode.studio.gui;

import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.ProcessState;
import tools.dscode.studio.runtime.RuntimeBridgeDescriptor;
import tools.dscode.studio.runtime.RuntimeBridgeError;
import tools.dscode.studio.runtime.RuntimeBridgeStatus;
import tools.dscode.studio.runtime.RuntimeControlResult;
import tools.dscode.studio.runtime.RuntimeLaunchResult;
import tools.dscode.studio.runtime.RuntimeScenarioStatus;
import tools.dscode.studio.runtime.RuntimeValue;
import tools.dscode.studio.runtime.RuntimeValueResult;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class RuntimeControlDialog extends JDialog {
    private static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;

    private final StudioDesktopSession session;
    private final JButton startButton = new JButton("Start Control Run");
    private final JButton cancelButton = new JButton("Cancel Run");
    private final JButton refreshButton = new JButton("Refresh");
    private final JComboBox<RuntimeBridgeDescriptor> runtimeBox = new JComboBox<>();
    private final JComboBox<RuntimeScenarioStatus> scenarioBox = new JComboBox<>();
    private final JTextArea runtimeStatus = textArea(7, 40);
    private final JTextArea result = textArea(9, 40);
    private final JTextArea buildOutput = textArea(14, 80);
    private final JTextField stepText = new JTextField();
    private final JTextArea stepArgument = inputArea(4, 40);
    private final JTextField mapReference = new JTextField("OVERRIDE");
    private final JTextField mapKey = new JTextField();
    private final JTextField mapJsonValue = new JTextField("\"value\"");
    private final JTextField mapResolveInput = new JTextField();
    private final JLabel stateLabel = new JLabel("No controlled run started.");

    private final Timer processTimer;
    private final Timer bridgeTimer;

    private String bridgeSessionId;
    private String activeProcessId;
    private long stdoutOffset;
    private long stderrOffset;
    private boolean stdoutTruncationReported;
    private boolean stderrTruncationReported;
    private boolean bridgeRefreshInFlight;

    RuntimeControlDialog(JFrame owner, StudioDesktopSession session) {
        super(owner, "Pickleball Runtime Control", false);
        this.session = session;

        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(900, 700));
        setSize(1100, 820);
        setLocationRelativeTo(owner);

        processTimer = new Timer(250, event -> pollProcess());
        bridgeTimer = new Timer(750, event -> refreshBridge());

        configureSelectionRenderers();
        add(launchBar(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(stateLabel, BorderLayout.SOUTH);

        startButton.addActionListener(event -> startControlRun());
        cancelButton.addActionListener(event -> cancelRun());
        refreshButton.addActionListener(event -> refreshBridge());
        cancelButton.setEnabled(false);
    }

    private JPanel launchBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel actions = new JPanel();
        actions.add(startButton);
        actions.add(cancelButton);
        actions.add(refreshButton);
        panel.add(actions, BorderLayout.WEST);

        JPanel selection = new JPanel(new GridLayout(2, 2, 6, 4));
        selection.add(new JLabel("Runtime"));
        selection.add(runtimeBox);
        selection.add(new JLabel("Scenario"));
        selection.add(scenarioBox);
        panel.add(selection, BorderLayout.CENTER);
        return panel;
    }

    private JTabbedPane content() {
        JTabbedPane controls = new JTabbedPane();
        controls.addTab("Step", stepPanel());
        controls.addTab("Mappings", mappingPanel());

        JPanel live = new JPanel(new BorderLayout(6, 6));
        JPanel pauseBar = new JPanel();
        JButton pauseButton = new JButton("Pause");
        JButton resumeButton = new JButton("Resume");
        pauseButton.addActionListener(event -> runControlAction(() ->
                session.pauseRuntime(
                        bridgeSessionId,
                        selectedRuntimeId(),
                        selectedScenarioId()
                )
        ));
        resumeButton.addActionListener(event -> runControlAction(() ->
                session.resumeRuntime(
                        bridgeSessionId,
                        selectedRuntimeId(),
                        selectedScenarioId()
                )
        ));
        pauseBar.add(pauseButton);
        pauseBar.add(resumeButton);
        live.add(pauseBar, BorderLayout.NORTH);

        JSplitPane statusAndControls = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(runtimeStatus),
                controls
        );
        statusAndControls.setResizeWeight(0.35);
        statusAndControls.setDividerLocation(180);
        live.add(statusAndControls, BorderLayout.CENTER);
        live.add(new JScrollPane(result), BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Runtime", live);
        tabs.addTab("Build Output", new JScrollPane(buildOutput));
        return tabs;
    }

    private JPanel stepPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(stepText, BorderLayout.NORTH);
        panel.add(new JScrollPane(stepArgument), BorderLayout.CENTER);

        JButton execute = new JButton("Execute Detached Step");
        execute.addActionListener(event -> runControlAction(() ->
                session.executeRuntimeStep(
                        bridgeSessionId,
                        selectedRuntimeId(),
                        selectedScenarioId(),
                        stepText.getText(),
                        stepArgument.getText()
                )
        ));
        panel.add(execute, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel mappingPanel() {
        JPanel fields = new JPanel(new GridLayout(4, 2, 6, 4));
        fields.add(new JLabel("Map reference"));
        fields.add(mapReference);
        fields.add(new JLabel("Key"));
        fields.add(mapKey);
        fields.add(new JLabel("JSON value"));
        fields.add(mapJsonValue);
        fields.add(new JLabel("Resolve input"));
        fields.add(mapResolveInput);

        JPanel actions = new JPanel();
        JButton get = new JButton("Get");
        JButton put = new JButton("Put");
        JButton resolve = new JButton("Resolve");
        get.addActionListener(event -> runValueAction(() ->
                session.runtimeMappingGet(
                        bridgeSessionId,
                        selectedRuntimeId(),
                        selectedScenarioId(),
                        mapReference.getText(),
                        mapKey.getText()
                )
        ));
        put.addActionListener(event -> runValueAction(() ->
                session.runtimeMappingPut(
                        bridgeSessionId,
                        selectedRuntimeId(),
                        selectedScenarioId(),
                        mapReference.getText(),
                        mapKey.getText(),
                        mapJsonValue.getText()
                )
        ));
        resolve.addActionListener(event -> runValueAction(() ->
                session.runtimeMappingResolve(
                        bridgeSessionId,
                        selectedRuntimeId(),
                        selectedScenarioId(),
                        mapResolveInput.getText()
                )
        ));
        actions.add(get);
        actions.add(put);
        actions.add(resolve);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(fields, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void configureSelectionRenderers() {
        runtimeBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean focused
            ) {
                String text = value instanceof RuntimeBridgeDescriptor runtime
                        ? runtime.runtimeId() + "  pid=" + runtime.pid()
                        : String.valueOf(value);
                return super.getListCellRendererComponent(
                        list,
                        text,
                        index,
                        selected,
                        focused
                );
            }
        });
        scenarioBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean focused
            ) {
                String text = value instanceof RuntimeScenarioStatus scenario
                        ? scenario.scenarioName()
                        + "  [" + scenario.scenarioId() + "]"
                        + (scenario.paused() ? "  PAUSED" : "")
                        : String.valueOf(value);
                return super.getListCellRendererComponent(
                        list,
                        text,
                        index,
                        selected,
                        focused
                );
            }
        });
    }

    private void startControlRun() {
        if (activeProcessId != null) {
            stateLabel.setText("A controlled test run is already active.");
            return;
        }

        bridgeSessionId = null;
        runtimeBox.removeAllItems();
        scenarioBox.removeAllItems();
        runtimeStatus.setText("Waiting for a controlled run...");
        result.setText("");
        buildOutput.setText("");
        stdoutOffset = 0;
        stderrOffset = 0;
        stdoutTruncationReported = false;
        stderrTruncationReported = false;
        startButton.setEnabled(false);
        stateLabel.setText("Starting controlled " + session.testBuildTool() + " tests...");

        async(
                session::startControlledTests,
                launch -> {
                    bridgeSessionId = launch.sessionId();
                    activeProcessId = launch.process().id();
                    cancelButton.setEnabled(true);
                    stateLabel.setText(
                            "Control run active — " + launch.buildTool()
                                    + " process " + activeProcessId
                    );
                    processTimer.start();
                    bridgeTimer.start();
                    refreshBridge();
                },
                failure -> {
                    startButton.setEnabled(true);
                    showFailure(failure);
                }
        );
    }

    private void refreshBridge() {
        String sessionId = bridgeSessionId;
        if (sessionId == null || bridgeRefreshInFlight) {
            return;
        }

        String preferredRuntimeId = selectedRuntimeId();
        String preferredScenarioId = selectedScenarioId();
        bridgeRefreshInFlight = true;
        async(
                () -> session.runtimeState(sessionId, preferredRuntimeId),
                state -> {
                    applyRuntimeState(state, preferredScenarioId);
                    bridgeRefreshInFlight = false;
                },
                failure -> {
                    bridgeRefreshInFlight = false;
                    runtimeStatus.setText(
                            failure.getMessage() == null
                                    ? failure.getClass().getSimpleName()
                                    : failure.getMessage()
                    );
                }
        );
    }

    private void applyRuntimeState(
            RuntimeDesktopState state,
            String preferredScenarioId
    ) {
        runtimeBox.setModel(new DefaultComboBoxModel<>(
                state.runtimes().toArray(RuntimeBridgeDescriptor[]::new)
        ));
        selectRuntime(state.selectedRuntimeId());

        scenarioBox.setModel(new DefaultComboBoxModel<>(
                state.scenarios().toArray(RuntimeScenarioStatus[]::new)
        ));
        selectScenario(preferredScenarioId);

        RuntimeBridgeStatus runtime = state.runtimeStatus();
        if (runtime == null) {
            runtimeStatus.setText("Waiting for a consumer Pickleball runtime...");
            return;
        }

        StringBuilder text = new StringBuilder();
        text.append("Runtime: ").append(runtime.runtimeId()).append(System.lineSeparator());
        text.append("PID: ").append(runtime.pid()).append(System.lineSeparator());
        text.append("Active scenarios: ").append(runtime.activeScenarioCount()).append(System.lineSeparator());
        text.append("Paused: ").append(runtime.paused()).append(System.lineSeparator());
        text.append("Pause requested: ").append(runtime.pauseRequested()).append(System.lineSeparator());
        if (runtime.lastHook() != null) {
            text.append("Last hook: ").append(runtime.lastHook()).append(System.lineSeparator());
        }
        RuntimeScenarioStatus scenario = selectedScenario();
        if (scenario != null) {
            text.append(System.lineSeparator());
            text.append("Scenario: ").append(scenario.scenarioName()).append(System.lineSeparator());
            text.append("Scenario id: ").append(scenario.scenarioId()).append(System.lineSeparator());
            text.append("Thread: ").append(scenario.threadId()).append(System.lineSeparator());
            if (scenario.stepText() != null) {
                text.append("Step: ").append(scenario.stepText()).append(System.lineSeparator());
            }
            if (scenario.phraseText() != null) {
                text.append("Phrase: ").append(scenario.phraseText()).append(System.lineSeparator());
            }
        }
        runtimeStatus.setText(text.toString());
        runtimeStatus.setCaretPosition(0);
    }

    private void runControlAction(Supplier<RuntimeControlResult> action) {
        if (!hasRuntimeSelection()) {
            result.setText("No live runtime is selected.");
            return;
        }
        async(
                action,
                value -> {
                    result.setText(format(value));
                    refreshBridge();
                },
                this::showFailure
        );
    }

    private void runValueAction(Supplier<RuntimeValueResult> action) {
        if (!hasRuntimeSelection()) {
            result.setText("No live runtime is selected.");
            return;
        }
        async(
                action,
                value -> {
                    result.setText(format(value));
                    refreshBridge();
                },
                this::showFailure
        );
    }

    private void pollProcess() {
        String processId = activeProcessId;
        if (processId == null) {
            processTimer.stop();
            return;
        }

        try {
            ProcessOutputChunk chunk = session.processOutput(
                    processId,
                    stdoutOffset,
                    stderrOffset
            );
            stdoutOffset = chunk.nextStdoutOffset();
            stderrOffset = chunk.nextStderrOffset();

            if (chunk.stdoutGap()) {
                appendBuildOutput("[stdout history gap]" + System.lineSeparator());
            }
            if (!chunk.stdout().isEmpty()) {
                appendBuildOutput(chunk.stdout());
            }
            if (chunk.stderrGap()) {
                appendBuildOutput("[stderr history gap]" + System.lineSeparator());
            }
            if (!chunk.stderr().isEmpty()) {
                appendBuildOutput("[stderr]" + System.lineSeparator());
                appendBuildOutput(chunk.stderr());
            }
            if (chunk.stdoutTruncated() && !stdoutTruncationReported) {
                appendBuildOutput("[stdout history truncated]" + System.lineSeparator());
                stdoutTruncationReported = true;
            }
            if (chunk.stderrTruncated() && !stderrTruncationReported) {
                appendBuildOutput("[stderr history truncated]" + System.lineSeparator());
                stderrTruncationReported = true;
            }

            ManagedProcessSummary summary = session.processStatus(processId);
            stateLabel.setText("Control run: " + summary.state());
            if (summary.state() != ProcessState.RUNNING) {
                appendBuildOutput(
                        System.lineSeparator()
                                + "Run finished: " + summary.state()
                                + (summary.exitCode() == null
                                ? ""
                                : " (exit " + summary.exitCode() + ")")
                                + System.lineSeparator()
                );
                activeProcessId = null;
                processTimer.stop();
                cancelButton.setEnabled(false);
                startButton.setEnabled(session.testBuildTool() != null);
                bridgeTimer.stop();
                refreshBridge();
            }
        } catch (RuntimeException failure) {
            activeProcessId = null;
            processTimer.stop();
            bridgeTimer.stop();
            cancelButton.setEnabled(false);
            startButton.setEnabled(session.testBuildTool() != null);
            showFailure(failure);
        }
    }

    private void cancelRun() {
        String processId = activeProcessId;
        if (processId == null) {
            return;
        }
        cancelButton.setEnabled(false);
        stateLabel.setText("Cancelling " + processId + "...");
        async(
                () -> session.cancelProcess(processId),
                summary -> stateLabel.setText("Control run: " + summary.state()),
                this::showFailure
        );
    }

    private boolean hasRuntimeSelection() {
        return bridgeSessionId != null && selectedRuntimeId() != null;
    }

    private String selectedRuntimeId() {
        Object selected = runtimeBox.getSelectedItem();
        return selected instanceof RuntimeBridgeDescriptor runtime
                ? runtime.runtimeId()
                : null;
    }

    private RuntimeScenarioStatus selectedScenario() {
        Object selected = scenarioBox.getSelectedItem();
        return selected instanceof RuntimeScenarioStatus scenario ? scenario : null;
    }

    private String selectedScenarioId() {
        RuntimeScenarioStatus scenario = selectedScenario();
        return scenario == null ? null : scenario.scenarioId();
    }

    private void selectRuntime(String runtimeId) {
        if (runtimeId == null) {
            return;
        }
        for (int index = 0; index < runtimeBox.getItemCount(); index++) {
            RuntimeBridgeDescriptor runtime = runtimeBox.getItemAt(index);
            if (runtimeId.equals(runtime.runtimeId())) {
                runtimeBox.setSelectedIndex(index);
                return;
            }
        }
    }

    private void selectScenario(String scenarioId) {
        if (scenarioId == null) {
            return;
        }
        for (int index = 0; index < scenarioBox.getItemCount(); index++) {
            RuntimeScenarioStatus scenario = scenarioBox.getItemAt(index);
            if (scenarioId.equals(scenario.scenarioId())) {
                scenarioBox.setSelectedIndex(index);
                return;
            }
        }
    }

    private void appendBuildOutput(String text) {
        buildOutput.append(text);
        int excess = buildOutput.getDocument().getLength() - MAX_OUTPUT_CHARS;
        if (excess > 0) {
            try {
                buildOutput.getDocument().remove(0, excess);
            } catch (javax.swing.text.BadLocationException ignored) {
                buildOutput.setText(buildOutput.getText());
            }
        }
        buildOutput.setCaretPosition(buildOutput.getDocument().getLength());
    }

    private String format(RuntimeControlResult value) {
        StringBuilder text = new StringBuilder(value.status());
        if (value.valueType() != null) {
            text.append(System.lineSeparator())
                    .append("Type: ").append(value.valueType());
        }
        if (value.valueText() != null) {
            text.append(System.lineSeparator())
                    .append("Value: ").append(value.valueText());
        }
        appendError(text, value.error());
        return text.toString();
    }

    private String format(RuntimeValueResult value) {
        StringBuilder text = new StringBuilder(value.status());
        RuntimeValue runtimeValue = value.value();
        if (runtimeValue != null) {
            text.append(System.lineSeparator())
                    .append("Type: ").append(runtimeValue.type());
            text.append(System.lineSeparator())
                    .append("JSON compatible: ").append(runtimeValue.jsonCompatible());
            if (runtimeValue.jsonCompatible()) {
                text.append(System.lineSeparator())
                        .append("JSON value: ")
                        .append(Objects.toString(runtimeValue.jsonValue(), "null"));
            }
            if (runtimeValue.text() != null) {
                text.append(System.lineSeparator())
                        .append("Text: ").append(runtimeValue.text());
            }
        }
        appendError(text, value.error());
        return text.toString();
    }

    private static void appendError(StringBuilder text, RuntimeBridgeError error) {
        if (error == null) {
            return;
        }
        text.append(System.lineSeparator())
                .append("Error: ").append(error.type());
        if (error.message() != null && !error.message().isBlank()) {
            text.append(System.lineSeparator()).append(error.message());
        }
    }

    private void showFailure(RuntimeException failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        result.setText(message);
        stateLabel.setText(message);
    }

    private <T> void async(
            Supplier<T> work,
            Consumer<T> success,
            Consumer<RuntimeException> failure
    ) {
        Thread.ofVirtual().name("studio-runtime-ui-work").start(() -> {
            try {
                T value = work.get();
                SwingUtilities.invokeLater(() -> success.accept(value));
            } catch (RuntimeException runtimeFailure) {
                SwingUtilities.invokeLater(() -> failure.accept(runtimeFailure));
            }
        });
    }

    private static JTextArea textArea(int rows, int columns) {
        JTextArea area = inputArea(rows, columns);
        area.setEditable(false);
        return area;
    }

    private static JTextArea inputArea(int rows, int columns) {
        JTextArea area = new JTextArea(rows, columns);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }
}
