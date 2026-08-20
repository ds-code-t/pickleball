package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Headless presentation model for the Workbench live scenario buffer.
 *
 * <p>This class owns only interactive-buffer state. It does not parse or execute
 * Pickleball steps, mutate Mapping state, or implement runtime rewind semantics.</p>
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

    public enum ExecutionStatus {
        NONE,
        PENDING,
        EXECUTED,
        FAILED
    }

    public record Line(long id, String text, LineType type, ExecutionStatus executionStatus) {
        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(executionStatus, "executionStatus");
        }

        public boolean executable() {
            return type == LineType.STEP;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private long nextId = 1;
    private Long selectedId;
    private int playheadIndex;
    private State state = State.STOPPED;

    public LiveScenarioPlayer(List<String> initialLines) {
        if (initialLines != null) {
            for (String text : initialLines) {
                addInitialLine(text == null ? "" : text);
            }
        }
        playheadIndex = findNextExecutableIndex(0);
    }

    public static LiveScenarioPlayer interactiveBuffer() {
        return new LiveScenarioPlayer(List.of(
                "Feature: Workbench Live Scenario",
                "",
                "Scenario: Interactive session",
                "",
                "# Enter a live Gherkin step in the Step Editor below."
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
        if (playheadIndex >= lines.size()) return Optional.empty();
        Line line = lines.get(playheadIndex);
        return line.executable() ? Optional.of(line) : Optional.empty();
    }

    /** Returns the display index of the next executable step, or {@code lines().size()} at end-of-buffer. */
    public int playheadIndex() {
        return playheadIndex;
    }

    public void select(long id) {
        requireLineIndex(id);
        selectedId = id;
    }

    public void clearSelection() {
        selectedId = null;
    }

    /**
     * Inserts a new live command at the playhead insertion point.
     * The inserted line receives a stable id and becomes the next executable step.
     */
    public Line insertStep(String text) {
        String stepText = requiredText(text, "Step");
        int insertAt = Math.min(playheadIndex, lines.size());
        Line inserted = new Line(nextId++, stepText, LineType.STEP, ExecutionStatus.PENDING);
        lines.add(insertAt, inserted);
        playheadIndex = insertAt;
        if (state == State.WAITING_FOR_STEP) state = State.RUNNING;
        return inserted;
    }

    /**
     * Updates the selected pending buffer step while preserving its durable id.
     * Already executed/failed steps are intentionally not editable because this
     * presentation model does not imply runtime rewind or side-effect rollback.
     */
    public Line updateSelectedStep(String text) {
        String stepText = requiredText(text, "Step");
        Line selected = selectedLine().orElseThrow(() ->
                new IllegalStateException("Select an executable pending step to update."));
        if (!selected.executable() || selected.executionStatus() != ExecutionStatus.PENDING) {
            throw new IllegalStateException("Only pending executable steps can be updated.");
        }
        int index = requireLineIndex(selected.id());
        Line updated = new Line(selected.id(), stepText, LineType.STEP, ExecutionStatus.PENDING);
        lines.set(index, updated);
        return updated;
    }

    public void play() {
        state = nextStep().isPresent() ? State.RUNNING : State.WAITING_FOR_STEP;
    }

    public void pause() {
        if (state == State.RUNNING || state == State.WAITING_FOR_STEP) {
            state = State.PAUSED;
        }
    }

    public void stop() {
        state = State.STOPPED;
    }

    /** Isolated execution always leaves the main live player paused. */
    public void pauseForIsolatedExecution() {
        state = State.PAUSED;
    }

    /** Marks the current playhead step executed and advances to the next executable buffer line. */
    public void markCurrentStepExecuted(long stepId) {
        int index = requireCurrentStep(stepId);
        Line current = lines.get(index);
        lines.set(index, new Line(current.id(), current.text(), current.type(), ExecutionStatus.EXECUTED));
        playheadIndex = findNextExecutableIndex(index + 1);
        if (state == State.RUNNING && playheadIndex >= lines.size()) {
            state = State.WAITING_FOR_STEP;
        }
    }

    /** Marks the current playhead step failed and pauses without advancing it. */
    public void markCurrentStepFailed(long stepId) {
        int index = requireCurrentStep(stepId);
        Line current = lines.get(index);
        lines.set(index, new Line(current.id(), current.text(), current.type(), ExecutionStatus.FAILED));
        playheadIndex = index;
        state = State.PAUSED;
    }

    /** Navigation only. Does not reset execution status or claim to undo runtime side effects. */
    public void movePlayheadToFirstStep() {
        playheadIndex = findNextExecutableIndex(0);
    }

    /** Navigation only. Does not reset execution status or claim to undo runtime side effects. */
    public void movePlayheadToPreviousStep() {
        int from = Math.min(playheadIndex - 1, lines.size() - 1);
        for (int i = from; i >= 0; i--) {
            if (lines.get(i).executable()) {
                playheadIndex = i;
                return;
            }
        }
        movePlayheadToFirstStep();
    }

    private void addInitialLine(String text) {
        LineType type = classify(text);
        ExecutionStatus status = type == LineType.STEP ? ExecutionStatus.PENDING : ExecutionStatus.NONE;
        lines.add(new Line(nextId++, text, type, status));
    }

    private int findNextExecutableIndex(int from) {
        for (int i = Math.max(0, from); i < lines.size(); i++) {
            if (lines.get(i).executable()) return i;
        }
        return lines.size();
    }

    private int requireCurrentStep(long id) {
        if (playheadIndex >= lines.size() || !lines.get(playheadIndex).executable()
                || lines.get(playheadIndex).id() != id) {
            throw new IllegalStateException("Step " + id + " is not the current playhead step.");
        }
        return playheadIndex;
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
