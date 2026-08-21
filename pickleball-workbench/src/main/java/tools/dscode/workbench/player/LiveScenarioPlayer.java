package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Headless presentation model for the Workbench live scenario buffer.
 *
 * <p>The editor selection is the user's navigation model. The execution cursor
 * is internal and exists only while a run is active; it is not a separately
 * editable playhead.</p>
 */
public final class LiveScenarioPlayer {
    public enum State {
        STOPPED,
        PAUSED,
        RUNNING,
        WAITING_FOR_STEP
    }

    public enum LineType {
        STRUCTURE,
        STEP,
        COMMENT,
        BLANK,
        TEXT
    }

    public record Line(long id, String text, LineType type) {
        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(type, "type");
        }

        public boolean executable() {
            return type == LineType.STEP;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private long nextId = 1;
    private Long selectedId;
    private int executionIndex;
    private State state = State.STOPPED;

    public LiveScenarioPlayer(List<String> initialLines) {
        if (initialLines != null) {
            for (String text : initialLines) {
                addInitialLine(text == null ? "" : text);
            }
        }
        executionIndex = findNextExecutableIndex(0);
    }

    /** A consumer-independent Pickleball-core smoke scenario. */
    public static LiveScenarioPlayer interactiveBuffer() {
        return new LiveScenarioPlayer(List.of(
                "Feature: Workbench Live Scenario",
                "",
                "Scenario: Quick player smoke test",
                "  Given ---workbench-player-smoke-1",
                "  And ---workbench-player-smoke-2",
                "  Then ---workbench-player-smoke-3",
                "",
                "# Global Play starts fresh from the first step. Select a step for From Here."
        ));
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public State state() {
        return state;
    }

    public OptionalLong selectedId() {
        return selectedId == null ? OptionalLong.empty() : OptionalLong.of(selectedId);
    }

    public Optional<Line> selectedLine() {
        if (selectedId == null) return Optional.empty();
        return lines.stream().filter(line -> line.id() == selectedId).findFirst();
    }

    public Optional<Line> nextStep() {
        if (executionIndex >= lines.size()) return Optional.empty();
        Line line = lines.get(executionIndex);
        return line.executable() ? Optional.of(line) : Optional.empty();
    }

    public void select(long id) {
        requireLineIndex(id);
        selectedId = id;
    }

    public void clearSelection() {
        selectedId = null;
    }

    /** Starts a new buffer run at the first executable step. */
    public void startFromBeginning() {
        executionIndex = findNextExecutableIndex(0);
        state = executionIndex < lines.size() ? State.RUNNING : State.WAITING_FOR_STEP;
    }

    /** Starts a new buffer run at the selected executable step. */
    public void startFromSelectedStep() {
        Line selected = selectedLine().orElseThrow(() ->
                new IllegalStateException("Select a scenario step to run from here."));
        if (!selected.executable()) {
            throw new IllegalStateException("Select an executable scenario step to run from here.");
        }
        executionIndex = requireLineIndex(selected.id());
        state = State.RUNNING;
    }

    /**
     * Inserts a new command directly after the selected line. With no selection,
     * it is appended after the last executable scenario step.
     */
    public Line insertStep(String text) {
        String stepText = requiredText(text, "Step");
        int insertAt = insertionIndex();
        int appendAt = insertionAfterLastExecutable();
        Line inserted = new Line(nextId++, stepText, LineType.STEP);
        lines.add(insertAt, inserted);

        if (state == State.WAITING_FOR_STEP && insertAt == appendAt) {
            executionIndex = insertAt;
            state = State.RUNNING;
        } else if (insertAt < executionIndex) {
            executionIndex++;
        }
        return inserted;
    }

    /** Updates the selected executable step while preserving its stable id. */
    public Line updateSelectedStep(String text) {
        String stepText = requiredText(text, "Step");
        Line selected = selectedLine().orElseThrow(() ->
                new IllegalStateException("Select an executable step to update."));
        if (!selected.executable()) {
            throw new IllegalStateException("Only executable scenario steps can be updated.");
        }
        int index = requireLineIndex(selected.id());
        Line updated = new Line(selected.id(), stepText, LineType.STEP);
        lines.set(index, updated);
        return updated;
    }

    public void pause() {
        if (state == State.RUNNING || state == State.WAITING_FOR_STEP) {
            state = State.PAUSED;
        }
    }

    public void stop() {
        state = State.STOPPED;
    }

    /** Step-only execution always leaves automatic scenario playback paused. */
    public void pauseForIsolatedExecution() {
        state = State.PAUSED;
    }

    /** Advances a successful run to the next executable line. */
    public void markCurrentStepExecuted(long stepId) {
        int index = requireCurrentStep(stepId);
        executionIndex = findNextExecutableIndex(index + 1);
        if (state == State.RUNNING && executionIndex >= lines.size()) {
            state = State.WAITING_FOR_STEP;
        }
    }

    /** Leaves a failed run paused on its failed line. */
    public void markCurrentStepFailed(long stepId) {
        executionIndex = requireCurrentStep(stepId);
        state = State.PAUSED;
    }

    private int insertionIndex() {
        if (selectedId != null) {
            return requireLineIndex(selectedId) + 1;
        }
        return insertionAfterLastExecutable();
    }

    private int insertionAfterLastExecutable() {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).executable()) return i + 1;
        }
        return lines.size();
    }

    private void addInitialLine(String text) {
        lines.add(new Line(nextId++, text, classify(text)));
    }

    private int findNextExecutableIndex(int from) {
        for (int i = Math.max(0, from); i < lines.size(); i++) {
            if (lines.get(i).executable()) return i;
        }
        return lines.size();
    }

    private int requireCurrentStep(long id) {
        if (executionIndex >= lines.size() || !lines.get(executionIndex).executable()
                || lines.get(executionIndex).id() != id) {
            throw new IllegalStateException("Step " + id + " is not the current execution step.");
        }
        return executionIndex;
    }

    private int requireLineIndex(long id) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() == id) return i;
        }
        throw new IllegalArgumentException("Unknown live scenario line id: " + id);
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
        return value.strip();
    }

    private static LineType classify(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.isBlank()) return LineType.BLANK;
        if (trimmed.startsWith("#")) return LineType.COMMENT;
        if (startsWithAny(trimmed,
                "Feature:", "Rule:", "Background:", "Scenario:", "Scenario Outline:", "Examples:")) {
            return LineType.STRUCTURE;
        }
        if (startsWithAny(trimmed, "Given ", "When ", "Then ", "And ", "But ", "* ")) {
            return LineType.STEP;
        }
        return LineType.TEXT;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }
}
