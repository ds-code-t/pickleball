package tools.dscode.studio.gui;

import tools.dscode.studio.runtime.RuntimeEvent;
import tools.dscode.studio.runtime.RuntimeEventPage;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Objects;

final class RuntimeEventPanel extends JPanel {
    private static final int PAGE_LIMIT = 500;

    private final StudioDesktopSession session;
    private final RuntimeEventTimeline timeline = new RuntimeEventTimeline();
    private final JCheckBox selectedScenarioOnly = new JCheckBox("Selected scenario only");
    private final JCheckBox autoTail = new JCheckBox("Auto-tail", true);
    private final JLabel summary = new JLabel("No runtime evidence yet.");
    private final JTextArea events = new JTextArea();

    private String sessionId;
    private String runtimeId;
    private String selectedScenarioId;
    private long generation;
    private long inFlightGeneration = -1;

    RuntimeEventPanel(StudioDesktopSession session) {
        super(new BorderLayout(6, 6));
        this.session = session;

        events.setEditable(false);
        events.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton reload = new JButton("Reload Retained");
        JButton clear = new JButton("Clear View");
        JPanel actions = new JPanel();
        actions.add(selectedScenarioOnly);
        actions.add(autoTail);
        actions.add(reload);
        actions.add(clear);

        JPanel top = new JPanel(new BorderLayout());
        top.add(actions, BorderLayout.WEST);
        top.add(summary, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(events), BorderLayout.CENTER);

        selectedScenarioOnly.addActionListener(event -> resetStream());
        reload.addActionListener(event -> resetStream());
        clear.addActionListener(event -> {
            timeline.clearVisible();
            render();
        });
    }

    void reset() {
        generation++;
        sessionId = null;
        runtimeId = null;
        selectedScenarioId = null;
        timeline.select(null, null, null);
        timeline.reload();
        render();
    }

    void refresh(String sessionId, String runtimeId, String selectedScenarioId) {
        this.sessionId = normalized(sessionId);
        this.runtimeId = normalized(runtimeId);
        this.selectedScenarioId = normalized(selectedScenarioId);
        String filter = scenarioFilter();

        if (timeline.select(this.sessionId, this.runtimeId, filter)) {
            generation++;
            render();
        }
        if (this.sessionId == null || this.runtimeId == null
                || inFlightGeneration == generation) {
            return;
        }

        long requestGeneration = generation;
        long cursor = timeline.afterSequence();
        String requestSession = this.sessionId;
        String requestRuntime = this.runtimeId;
        String requestScenario = filter;
        inFlightGeneration = requestGeneration;

        Thread.ofVirtual().name("studio-runtime-events").start(() -> {
            try {
                RuntimeEventPage page = session.runtimeEvents(
                        requestSession,
                        requestRuntime,
                        requestScenario,
                        cursor,
                        PAGE_LIMIT
                );
                SwingUtilities.invokeLater(() -> complete(
                        requestGeneration,
                        requestSession,
                        requestRuntime,
                        requestScenario,
                        page
                ));
            } catch (RuntimeException failure) {
                SwingUtilities.invokeLater(() -> fail(requestGeneration, failure));
            }
        });
    }

    private void complete(
            long requestGeneration,
            String requestSession,
            String requestRuntime,
            String requestScenario,
            RuntimeEventPage page
    ) {
        if (inFlightGeneration == requestGeneration) {
            inFlightGeneration = -1;
        }
        if (requestGeneration != generation
                || !Objects.equals(requestSession, sessionId)
                || !Objects.equals(requestRuntime, runtimeId)
                || !Objects.equals(requestScenario, scenarioFilter())) {
            return;
        }

        timeline.accept(page);
        render();
        if (page.hasMore()) {
            SwingUtilities.invokeLater(() -> refresh(sessionId, runtimeId, selectedScenarioId));
        }
    }

    private void fail(long requestGeneration, RuntimeException failure) {
        if (inFlightGeneration == requestGeneration) {
            inFlightGeneration = -1;
        }
        if (requestGeneration != generation) {
            return;
        }
        summary.setText(
                failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage()
        );
    }

    private void resetStream() {
        generation++;
        timeline.select(sessionId, runtimeId, scenarioFilter());
        timeline.reload();
        render();
        refresh(sessionId, runtimeId, selectedScenarioId);
    }

    private String scenarioFilter() {
        return selectedScenarioOnly.isSelected() ? selectedScenarioId : null;
    }

    private void render() {
        int caret = events.getCaretPosition();
        StringBuilder text = new StringBuilder();
        if (timeline.gapObserved()) {
            text.append("[retention gap: earliest available sequence ")
                    .append(timeline.gapEarliestSequence())
                    .append(']')
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        if (timeline.omittedVisibleEvents() > 0) {
            text.append("[desktop view omitted ")
                    .append(timeline.omittedVisibleEvents())
                    .append(" older loaded events]")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        for (RuntimeEvent event : timeline.events()) {
            append(text, event);
        }
        events.setText(text.toString());
        if (autoTail.isSelected()) {
            events.setCaretPosition(events.getDocument().getLength());
        } else {
            events.setCaretPosition(Math.min(caret, events.getDocument().getLength()));
        }

        if (runtimeId == null) {
            summary.setText("No live runtime selected.");
            return;
        }
        String filter = scenarioFilter();
        summary.setText(
                "Cursor " + timeline.afterSequence()
                        + " — retained " + timeline.earliestAvailableSequence()
                        + ".." + timeline.latestSequence()
                        + " — showing " + timeline.events().size()
                        + " — " + (filter == null ? "all scenarios" : "scenario " + filter)
                        + (timeline.gapObserved() ? " — GAP" : "")
        );
    }

    private static void append(StringBuilder text, RuntimeEvent event) {
        text.append('#').append(event.sequence()).append(' ')
                .append(Objects.toString(event.timestamp(), ""))
                .append("  ").append(Objects.toString(event.hook(), ""))
                .append("  ").append(Objects.toString(event.scenarioName(), ""))
                .append("  [").append(Objects.toString(event.scenarioId(), "")).append(']')
                .append("  thread=").append(event.threadId())
                .append(System.lineSeparator());
        appendDetail(text, "step", event.stepText());
        appendDetail(text, "phrase", event.phraseText());
        appendDetail(text, "signature", event.signature());
        text.append(System.lineSeparator());
    }

    private static void appendDetail(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        text.append("  ").append(label).append(": ")
                .append(normalized.replace("\n", System.lineSeparator() + "    "))
                .append(System.lineSeparator());
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
