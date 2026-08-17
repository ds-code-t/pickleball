package tools.dscode.studio.gui;

import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.ProcessState;
import tools.dscode.studio.runtime.*;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class RuntimeControlDialog extends JDialog {
    private static final int MAX_OUTPUT_CHARS = 2 * 1024 * 1024;

    private final StudioDesktopSession session;
    private final RuntimeEventPanel eventPanel;
    private final RuntimeInvestigationPanel investigationPanel;
    private final JButton startButton = new JButton("Start Control Run");
    private final JButton cancelButton = new JButton("Cancel Run");
    private final JButton refreshButton = new JButton("Refresh");
    private final JComboBox<RuntimeBridgeDescriptor> runtimeBox = new JComboBox<>();
    private final JComboBox<RuntimeScenarioStatus> scenarioBox = new JComboBox<>();
    private final JTextArea runtimeStatus = textArea(7, 40);
    private final JTextArea result = textArea(11, 40);
    private final JTextArea buildOutput = textArea(14, 80);
    private final JTextField stepText = new JTextField();
    private final JTextArea stepArgument = inputArea(4, 40);
    private final JTextField mapReference = new JTextField("OVERRIDE");
    private final JTextField mapKey = new JTextField();
    private final JTextField mapJsonValue = new JTextField("\"value\"");
    private final JTextField mapResolveInput = new JTextField();
    private final JComboBox<RuntimeMappingSnapshotSummary> mappingSnapshotBox = new JComboBox<>();
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
        this.eventPanel = new RuntimeEventPanel(session);
        this.investigationPanel = new RuntimeInvestigationPanel(
                session,
                () -> bridgeSessionId,
                this::selectedRuntimeId,
                this::selectedScenarioId,
                result::setText
        );

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
        actions.add(startButton); actions.add(cancelButton); actions.add(refreshButton);
        panel.add(actions, BorderLayout.WEST);
        JPanel selection = new JPanel(new GridLayout(2, 2, 6, 4));
        selection.add(new JLabel("Runtime")); selection.add(runtimeBox);
        selection.add(new JLabel("Scenario")); selection.add(scenarioBox);
        panel.add(selection, BorderLayout.CENTER);
        return panel;
    }

    private JTabbedPane content() {
        JTabbedPane controls = new JTabbedPane();
        controls.addTab("Step", stepPanel());
        controls.addTab("Mappings", mappingPanel());
        controls.addTab("Investigation", investigationPanel);

        JPanel live = new JPanel(new BorderLayout(6, 6));
        JPanel pauseBar = new JPanel();
        JButton pauseButton = new JButton("Pause");
        JButton resumeButton = new JButton("Resume");
        pauseButton.addActionListener(event -> runControlAction(() ->
                session.pauseRuntime(bridgeSessionId, selectedRuntimeId(), selectedScenarioId())));
        resumeButton.addActionListener(event -> runControlAction(() ->
                session.resumeRuntime(bridgeSessionId, selectedRuntimeId(), selectedScenarioId())));
        pauseBar.add(pauseButton); pauseBar.add(resumeButton);
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
        tabs.addTab("Events", eventPanel);
        tabs.addTab("Build Output", new JScrollPane(buildOutput));
        return tabs;
    }

    private JPanel stepPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(stepText, BorderLayout.NORTH);
        panel.add(new JScrollPane(stepArgument), BorderLayout.CENTER);
        JButton execute = new JButton("Execute Detached Step");
        execute.addActionListener(event -> runControlAction(() -> session.executeRuntimeStep(
                bridgeSessionId, selectedRuntimeId(), selectedScenarioId(), stepText.getText(), stepArgument.getText()
        )));
        panel.add(execute, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel mappingPanel() {
        JPanel fields = new JPanel(new GridLayout(5, 2, 6, 4));
        fields.add(new JLabel("Map reference")); fields.add(mapReference);
        fields.add(new JLabel("Key")); fields.add(mapKey);
        fields.add(new JLabel("JSON value")); fields.add(mapJsonValue);
        fields.add(new JLabel("Resolve input")); fields.add(mapResolveInput);
        fields.add(new JLabel("Snapshot")); fields.add(mappingSnapshotBox);

        JPanel actions = new JPanel();
        JButton get = new JButton("Get");
        JButton put = new JButton("Put");
        JButton resolve = new JButton("Resolve");
        JButton snapshot = new JButton("Snapshot");
        JButton restore = new JButton("Restore Snapshot");
        get.addActionListener(event -> runValueAction(() -> session.runtimeMappingGet(
                bridgeSessionId, selectedRuntimeId(), selectedScenarioId(), mapReference.getText(), mapKey.getText())));
        put.addActionListener(event -> runValueAction(() -> session.runtimeMappingPut(
                bridgeSessionId, selectedRuntimeId(), selectedScenarioId(), mapReference.getText(), mapKey.getText(), mapJsonValue.getText())));
        resolve.addActionListener(event -> runValueAction(() -> session.runtimeMappingResolve(
                bridgeSessionId, selectedRuntimeId(), selectedScenarioId(), mapResolveInput.getText())));
        snapshot.addActionListener(event -> runSnapshotAction(() -> session.runtimeMappingSnapshot(
                bridgeSessionId, selectedRuntimeId(), selectedScenarioId(), mapReference.getText())));
        restore.addActionListener(event -> {
            RuntimeMappingSnapshotSummary selected = selectedMappingSnapshot();
            if (selected == null) { result.setText("No mapping snapshot is selected."); return; }
            runControlAction(() -> session.runtimeMappingRestore(bridgeSessionId, selected.snapshotId()));
        });
        actions.add(get); actions.add(put); actions.add(resolve); actions.add(snapshot); actions.add(restore);
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(fields, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void configureSelectionRenderers() {
        runtimeBox.setRenderer(renderer(value -> value instanceof RuntimeBridgeDescriptor runtime
                ? runtime.runtimeId() + "  pid=" + runtime.pid() : String.valueOf(value)));
        scenarioBox.setRenderer(renderer(value -> value instanceof RuntimeScenarioStatus scenario
                ? scenario.scenarioName() + "  [" + scenario.scenarioId() + "]" + (scenario.paused() ? "  PAUSED" : "")
                : String.valueOf(value)));
        mappingSnapshotBox.setRenderer(renderer(value -> value instanceof RuntimeMappingSnapshotSummary snapshot
                ? snapshot.mapReference() + "  " + snapshot.capturedAt() + (snapshot.restorable() ? "" : "  INSPECT ONLY")
                : String.valueOf(value)));
    }

    private DefaultListCellRenderer renderer(java.util.function.Function<Object, String> text) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, text.apply(value), index, selected, focused);
            }
        };
    }

    private void startControlRun() {
        if (activeProcessId != null) { stateLabel.setText("A controlled test run is already active."); return; }
        bridgeSessionId = null;
        runtimeBox.removeAllItems(); scenarioBox.removeAllItems(); mappingSnapshotBox.removeAllItems();
        runtimeStatus.setText("Waiting for a controlled run..."); result.setText(""); buildOutput.setText("");
        eventPanel.reset();
        stdoutOffset = 0; stderrOffset = 0; stdoutTruncationReported = false; stderrTruncationReported = false;
        startButton.setEnabled(false);
        stateLabel.setText("Starting controlled " + session.testBuildTool() + " tests...");
        async(session::startControlledTests, launch -> {
            bridgeSessionId = launch.sessionId();
            activeProcessId = launch.process().id();
            cancelButton.setEnabled(true);
            stateLabel.setText("Control run active — " + launch.buildTool() + " process " + activeProcessId);
            processTimer.start(); bridgeTimer.start(); refreshBridge();
        }, failure -> { startButton.setEnabled(true); showFailure(failure); });
    }

    private void refreshBridge() {
        String sessionId = bridgeSessionId;
        if (sessionId == null || bridgeRefreshInFlight) return;
        String preferredRuntimeId = selectedRuntimeId();
        String preferredScenarioId = selectedScenarioId();
        bridgeRefreshInFlight = true;
        async(() -> session.runtimeState(sessionId, preferredRuntimeId), state -> {
            applyRuntimeState(state, preferredScenarioId);
            bridgeRefreshInFlight = false;
            eventPanel.refresh(bridgeSessionId, selectedRuntimeId(), selectedScenarioId());
            investigationPanel.refreshBreakpoints();
        }, failure -> {
            bridgeRefreshInFlight = false;
            runtimeStatus.setText(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        });
    }

    private void applyRuntimeState(RuntimeDesktopState state, String preferredScenarioId) {
        runtimeBox.setModel(new DefaultComboBoxModel<>(state.runtimes().toArray(RuntimeBridgeDescriptor[]::new)));
        selectRuntime(state.selectedRuntimeId());
        scenarioBox.setModel(new DefaultComboBoxModel<>(state.scenarios().toArray(RuntimeScenarioStatus[]::new)));
        selectScenario(preferredScenarioId);
        refreshMappingSnapshots(selectedMappingSnapshotId());
        RuntimeBridgeStatus runtime = state.runtimeStatus();
        if (runtime == null) { runtimeStatus.setText("Waiting for a consumer Pickleball runtime..."); return; }
        StringBuilder text = new StringBuilder();
        text.append("Runtime: ").append(runtime.runtimeId()).append('\n');
        text.append("PID: ").append(runtime.pid()).append('\n');
        text.append("Active scenarios: ").append(runtime.activeScenarioCount()).append('\n');
        text.append("Paused: ").append(runtime.paused()).append('\n');
        text.append("Pause requested: ").append(runtime.pauseRequested()).append('\n');
        if (runtime.lastHook() != null) text.append("Last hook: ").append(runtime.lastHook()).append('\n');
        RuntimeScenarioStatus scenario = selectedScenario();
        if (scenario != null) {
            text.append("\nScenario: ").append(scenario.scenarioName()).append('\n');
            text.append("Scenario id: ").append(scenario.scenarioId()).append('\n');
            text.append("Thread: ").append(scenario.threadId()).append('\n');
            if (scenario.stepText() != null) text.append("Step: ").append(scenario.stepText()).append('\n');
            if (scenario.phraseText() != null) text.append("Phrase: ").append(scenario.phraseText()).append('\n');
        }
        runtimeStatus.setText(text.toString());
        runtimeStatus.setCaretPosition(0);
    }

    private void runControlAction(Supplier<RuntimeControlResult> action) {
        if (!hasRuntimeSelection()) { result.setText("No live runtime is selected."); return; }
        async(action, value -> { result.setText(format(value)); refreshBridge(); }, this::showFailure);
    }
    private void runValueAction(Supplier<RuntimeValueResult> action) {
        if (!hasRuntimeSelection()) { result.setText("No live runtime is selected."); return; }
        async(action, value -> { result.setText(format(value)); refreshBridge(); }, this::showFailure);
    }
    private void runSnapshotAction(Supplier<RuntimeMappingSnapshotResult> action) {
        if (!hasRuntimeSelection()) { result.setText("No live runtime is selected."); return; }
        async(action, value -> {
            result.setText(format(value));
            refreshMappingSnapshots(value.snapshot() == null ? null : value.snapshot().snapshotId());
            refreshBridge();
        }, this::showFailure);
    }

    private void pollProcess() {
        String processId = activeProcessId;
        if (processId == null) { processTimer.stop(); return; }
        try {
            ProcessOutputChunk chunk = session.processOutput(processId, stdoutOffset, stderrOffset);
            stdoutOffset = chunk.nextStdoutOffset(); stderrOffset = chunk.nextStderrOffset();
            if (chunk.stdoutGap()) appendBuildOutput("[stdout history gap]\n");
            if (!chunk.stdout().isEmpty()) appendBuildOutput(chunk.stdout());
            if (chunk.stderrGap()) appendBuildOutput("[stderr history gap]\n");
            if (!chunk.stderr().isEmpty()) appendBuildOutput("[stderr]\n" + chunk.stderr());
            if (chunk.stdoutTruncated() && !stdoutTruncationReported) { appendBuildOutput("[stdout history truncated]\n"); stdoutTruncationReported = true; }
            if (chunk.stderrTruncated() && !stderrTruncationReported) { appendBuildOutput("[stderr history truncated]\n"); stderrTruncationReported = true; }
            ManagedProcessSummary summary = session.processStatus(processId);
            stateLabel.setText("Control run: " + summary.state());
            if (summary.state() != ProcessState.RUNNING) {
                appendBuildOutput("\nRun finished: " + summary.state()
                        + (summary.exitCode() == null ? "" : " (exit " + summary.exitCode() + ")") + "\n");
                activeProcessId = null; processTimer.stop(); cancelButton.setEnabled(false);
                startButton.setEnabled(session.testBuildTool() != null); bridgeTimer.stop(); refreshBridge();
            }
        } catch (RuntimeException failure) {
            activeProcessId = null; processTimer.stop(); bridgeTimer.stop(); cancelButton.setEnabled(false);
            startButton.setEnabled(session.testBuildTool() != null); showFailure(failure);
        }
    }

    private void cancelRun() {
        String processId = activeProcessId;
        if (processId == null) return;
        cancelButton.setEnabled(false);
        stateLabel.setText("Cancelling " + processId + "...");
        async(() -> session.cancelProcess(processId), summary -> stateLabel.setText("Control run: " + summary.state()), this::showFailure);
    }

    private boolean hasRuntimeSelection() { return bridgeSessionId != null && selectedRuntimeId() != null; }
    private String selectedRuntimeId() {
        Object selected = runtimeBox.getSelectedItem();
        return selected instanceof RuntimeBridgeDescriptor runtime ? runtime.runtimeId() : null;
    }
    private RuntimeScenarioStatus selectedScenario() {
        Object selected = scenarioBox.getSelectedItem();
        return selected instanceof RuntimeScenarioStatus scenario ? scenario : null;
    }
    private String selectedScenarioId() { RuntimeScenarioStatus scenario = selectedScenario(); return scenario == null ? null : scenario.scenarioId(); }
    private RuntimeMappingSnapshotSummary selectedMappingSnapshot() {
        Object selected = mappingSnapshotBox.getSelectedItem();
        return selected instanceof RuntimeMappingSnapshotSummary snapshot ? snapshot : null;
    }
    private String selectedMappingSnapshotId() { RuntimeMappingSnapshotSummary snapshot = selectedMappingSnapshot(); return snapshot == null ? null : snapshot.snapshotId(); }

    private void refreshMappingSnapshots(String preferredSnapshotId) {
        if (!hasRuntimeSelection()) { mappingSnapshotBox.removeAllItems(); return; }
        List<RuntimeMappingSnapshotSummary> snapshots = session.runtimeMappingSnapshots(
                bridgeSessionId, selectedRuntimeId(), selectedScenarioId());
        mappingSnapshotBox.setModel(new DefaultComboBoxModel<>(snapshots.toArray(RuntimeMappingSnapshotSummary[]::new)));
        if (preferredSnapshotId != null) {
            for (int i = 0; i < mappingSnapshotBox.getItemCount(); i++) {
                if (preferredSnapshotId.equals(mappingSnapshotBox.getItemAt(i).snapshotId())) {
                    mappingSnapshotBox.setSelectedIndex(i); break;
                }
            }
        }
    }
    private void selectRuntime(String id) {
        if (id == null) return;
        for (int i = 0; i < runtimeBox.getItemCount(); i++) if (id.equals(runtimeBox.getItemAt(i).runtimeId())) { runtimeBox.setSelectedIndex(i); return; }
    }
    private void selectScenario(String id) {
        if (id == null) return;
        for (int i = 0; i < scenarioBox.getItemCount(); i++) if (id.equals(scenarioBox.getItemAt(i).scenarioId())) { scenarioBox.setSelectedIndex(i); return; }
    }

    private void appendBuildOutput(String text) {
        buildOutput.append(text);
        int excess = buildOutput.getDocument().getLength() - MAX_OUTPUT_CHARS;
        if (excess > 0) {
            try { buildOutput.getDocument().remove(0, excess); }
            catch (javax.swing.text.BadLocationException ignored) { buildOutput.setText(buildOutput.getText()); }
        }
        buildOutput.setCaretPosition(buildOutput.getDocument().getLength());
    }

    private String format(RuntimeControlResult value) {
        StringBuilder text = new StringBuilder(value.status());
        if (value.valueType() != null) text.append("\nType: ").append(value.valueType());
        if (value.valueText() != null) text.append("\nValue: ").append(value.valueText());
        appendError(text, value.error());
        return text.toString();
    }
    private String format(RuntimeValueResult value) {
        StringBuilder text = new StringBuilder(value.status());
        RuntimeValue runtimeValue = value.value();
        if (runtimeValue != null) {
            text.append("\nType: ").append(runtimeValue.type());
            text.append("\nJSON compatible: ").append(runtimeValue.jsonCompatible());
            if (runtimeValue.jsonCompatible()) text.append("\nJSON value: ").append(Objects.toString(runtimeValue.jsonValue(), "null"));
            if (runtimeValue.text() != null) text.append("\nText: ").append(runtimeValue.text());
        }
        appendError(text, value.error());
        return text.toString();
    }
    private String format(RuntimeMappingSnapshotResult value) {
        StringBuilder text = new StringBuilder(value.status());
        RuntimeMappingSnapshot snapshot = value.snapshot();
        if (snapshot != null) {
            text.append("\nSnapshot id: ").append(snapshot.snapshotId());
            text.append("\nMap: ").append(snapshot.mapReference());
            text.append("\nType: ").append(snapshot.mapType());
            text.append("\nClass: ").append(snapshot.mapClass());
            text.append("\nRestorable: ").append(snapshot.restorable());
            text.append("\nValues: ").append(snapshot.state().values());
        }
        appendError(text, value.error());
        return text.toString();
    }
    private static void appendError(StringBuilder text, RuntimeBridgeError error) {
        if (error == null) return;
        text.append("\nError: ").append(error.type());
        if (error.message() != null && !error.message().isBlank()) text.append('\n').append(error.message());
    }

    private void showFailure(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        result.setText(message); stateLabel.setText(message);
    }

    private <T> void async(Supplier<T> work, Consumer<T> success, Consumer<RuntimeException> failure) {
        Thread.ofVirtual().name("studio-runtime-ui-work").start(() -> {
            try { T value = work.get(); SwingUtilities.invokeLater(() -> success.accept(value)); }
            catch (RuntimeException runtimeFailure) { SwingUtilities.invokeLater(() -> failure.accept(runtimeFailure)); }
        });
    }
    private static JTextArea textArea(int rows, int columns) { JTextArea area = inputArea(rows, columns); area.setEditable(false); return area; }
    private static JTextArea inputArea(int rows, int columns) { JTextArea area = new JTextArea(rows, columns); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); return area; }
}
