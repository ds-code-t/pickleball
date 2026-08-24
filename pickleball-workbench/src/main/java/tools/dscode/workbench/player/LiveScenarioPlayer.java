package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * Headless presentation model for the Workbench live scenario buffer.
 *
 * <p>The playhead is the user-visible needle: clicking a line seeks immediately,
 * like clicking a waveform. Global Play ignores the playhead and always starts
 * from the first executable step. Isolated Step Editor play leaves this model
 * paused. Real Gherkin matching and execution stay in the consumer worker.</p>
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

    /**
     * Workbench-owned demo buffer. Steps use consumer config keys such as
     * {@code URL.home}, not machine-specific filesystem paths.
     */
    public static final List<String> DEFAULT_DEMO_SCENARIO = List.of(
            "Feature: Workbench Live Scenario",
            "",
            "Scenario: Open the local test site",
            "  Given navigate to: URL.home",
            "  When , ensure \"Pickleball Test Lab\" Text is displayed",
            "  And , click the \"Open Forms Playground\" Link",
            "  Then , ensure \"Forms Playground\" Text is displayed",
            "",
            "# Click a step to move the playhead. Global Play always starts from the first step."
    );

    private final List<Line> lines = new ArrayList<>();
    private long nextId = 1;
    private Long selectedId;
    private Long playheadId;
    private Long lastExecutedId;
    private int executionIndex;
    private State state = State.STOPPED;

    public LiveScenarioPlayer(List<String> initialLines) {
        if (initialLines != null) {
            for (String text : initialLines) {
                addInitialLine(text == null ? "" : text);
            }
        }
        initializeCursors();
    }

    /** Default live buffer: a small browser demo against the consumer local test site. */
    public static LiveScenarioPlayer interactiveBuffer() {
        return new LiveScenarioPlayer(DEFAULT_DEMO_SCENARIO);
    }

    /**
     * Replaces the session buffer with a newly loaded scenario and returns to
     * {@link State#STOPPED}. Used by the feature/scenario picker. The origin
     * file is presentation metadata only; this model never writes {@code .feature}
     * files.
     */
    public void loadDocument(List<String> texts) {
        lines.clear();
        nextId = 1;
        selectedId = null;
        playheadId = null;
        lastExecutedId = null;
        executionIndex = 0;
        state = State.STOPPED;
        List<String> incoming = texts == null || texts.isEmpty() ? List.of("") : texts;
        for (String text : incoming) {
            addInitialLine(text == null ? "" : text);
        }
        initializeCursors();
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public String documentText() {
        return lines.stream().map(Line::text).collect(Collectors.joining("\n"));
    }

    public State state() {
        return state;
    }

    public OptionalLong selectedId() {
        return selectedId == null ? OptionalLong.empty() : OptionalLong.of(selectedId);
    }

    public Optional<Line> selectedLine() {
        return line(selectedId);
    }

    public OptionalLong playheadId() {
        return playheadId == null ? OptionalLong.empty() : OptionalLong.of(playheadId);
    }

    public Optional<Line> playheadLine() {
        return line(playheadId);
    }

    public Optional<Line> nextStep() {
        if (executionIndex >= lines.size()) return Optional.empty();
        Line line = lines.get(executionIndex);
        return line.executable() ? Optional.of(line) : Optional.empty();
    }

    /**
     * Audio-player seek: clicking a line instantly moves the playhead and
     * makes that line the editor selection.
     */
    public void clickLine(long id) {
        requireLineIndex(id);
        selectedId = id;
        playheadId = id;
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
        lastExecutedId = null;
        executionIndex = findNextExecutableIndex(0);
        state = executionIndex < lines.size() ? State.RUNNING : State.WAITING_FOR_STEP;
        if (executionIndex < lines.size()) {
            playheadId = lines.get(executionIndex).id();
        }
    }

    /** Starts a new buffer run at the selected executable step. */
    public void startFromSelectedStep() {
        Line selected = selectedLine().orElseGet(() -> playheadLine().orElseThrow(() ->
                new IllegalStateException("Select a scenario step to run from here.")));
        if (!selected.executable()) {
            throw new IllegalStateException("Select an executable scenario step to run from here.");
        }
        lastExecutedId = null;
        executionIndex = requireLineIndex(selected.id());
        selectedId = selected.id();
        playheadId = selected.id();
        state = State.RUNNING;
    }

    /**
     * Inserts a new command. While waiting at end-of-buffer, the step is
     * appended and playback continues. Otherwise it is inserted after the
     * selected line, or after the last executable step when nothing is selected.
     */
    public Line insertStep(String text) {
        String stepText = requiredText(text, "Step");
        int appendAt = insertionAfterLastExecutable();
        int insertAt = state == State.WAITING_FOR_STEP ? appendAt : insertionIndex();
        Line inserted = new Line(nextId++, stepText, LineType.STEP);
        lines.add(insertAt, inserted);
        selectedId = inserted.id();
        playheadId = inserted.id();

        if (state == State.WAITING_FOR_STEP && insertAt == appendAt) {
            executionIndex = insertAt;
            state = State.RUNNING;
        } else if (insertAt <= executionIndex && executionIndex < lines.size()) {
            executionIndex++;
        }
        return inserted;
    }

    /** Updates the selected line in place while preserving its stable id. */
    public Line updateSelectedStep(String text) {
        Line selected = selectedLine().orElseThrow(() ->
                new IllegalStateException("Select a scenario line to update."));
        return updateLine(selected.id(), text);
    }

    /**
     * In-place edit of any buffer line, including previously executed Gherkin.
     * Stable identity is preserved; classification follows the new text.
     */
    public Line updateLine(long id, String text) {
        int index = requireLineIndex(id);
        String value = text == null ? "" : text;
        Line updated = new Line(id, value, classify(value));
        lines.set(index, updated);
        if (executionIndex == index && !updated.executable() && state == State.RUNNING) {
            executionIndex = findNextExecutableIndex(index + 1);
            if (executionIndex >= lines.size()) {
                state = State.WAITING_FOR_STEP;
            }
        }
        return updated;
    }

    /**
     * Replaces the whole document while preserving stable ids for lines that
     * stay at the same index, and LCS-matched lines when the line count changes.
     * Appending an executable step while waiting resumes playback.
     */
    public void replaceDocument(List<String> texts) {
        List<String> incoming = normalizeDocument(texts);
        boolean waiting = state == State.WAITING_FOR_STEP;
        Long previousPlayhead = playheadId;
        Long previousSelected = selectedId;
        Long previousExecId = executionIndex < lines.size() ? lines.get(executionIndex).id() : null;

        List<Line> rebuilt = alignLines(List.copyOf(lines), incoming);
        lines.clear();
        lines.addAll(rebuilt);

        playheadId = present(previousPlayhead) ? previousPlayhead : defaultPlayheadId();
        selectedId = present(previousSelected) ? previousSelected : null;

        if (previousExecId != null && present(previousExecId)) {
            executionIndex = requireLineIndex(previousExecId);
            if (executionIndex < lines.size() && !lines.get(executionIndex).executable()) {
                executionIndex = findNextExecutableIndex(executionIndex + 1);
            }
        } else if (waiting || state == State.RUNNING) {
            executionIndex = nextExecutableAfter(lastExecutedId);
        } else {
            executionIndex = findNextExecutableIndex(0);
        }

        if (waiting) {
            int next = nextExecutableAfter(lastExecutedId);
            if (next < lines.size()) {
                executionIndex = next;
                state = State.RUNNING;
                playheadId = lines.get(next).id();
            } else {
                executionIndex = lines.size();
            }
        }
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

    /**
     * Advances a successful run to the next executable line and stays in play at end.
     * Already-consumed or stale ids are ignored so a leftover Play-loop callback
     * cannot abort automatic playback.
     */
    public void markCurrentStepExecuted(long stepId) {
        int index = currentExecutableIndex(stepId);
        if (index < 0) {
            return;
        }
        lastExecutedId = stepId;
        executionIndex = findNextExecutableIndex(index + 1);
        if (executionIndex < lines.size()) {
            playheadId = lines.get(executionIndex).id();
        } else if (state == State.RUNNING) {
            state = State.WAITING_FOR_STEP;
        }
    }

    /**
     * Leaves a failed run paused on its failed line. Already-consumed or stale
     * ids are ignored.
     */
    public void markCurrentStepFailed(long stepId) {
        int index = currentExecutableIndex(stepId);
        if (index < 0) {
            return;
        }
        executionIndex = index;
        playheadId = stepId;
        selectedId = stepId;
        state = State.PAUSED;
    }

    private void initializeCursors() {
        executionIndex = findNextExecutableIndex(0);
        playheadId = defaultPlayheadId();
    }

    private Long defaultPlayheadId() {
        if (executionIndex < lines.size()) return lines.get(executionIndex).id();
        return lines.isEmpty() ? null : lines.getFirst().id();
    }

    private Optional<Line> line(Long id) {
        if (id == null) return Optional.empty();
        return lines.stream().filter(line -> line.id() == id).findFirst();
    }

    private boolean present(Long id) {
        return id != null && line(id).isPresent();
    }

    private int insertionIndex() {
        if (selectedId != null) {
            return requireLineIndex(selectedId) + 1;
        }
        if (playheadId != null) {
            return requireLineIndex(playheadId) + 1;
        }
        return insertionAfterLastExecutable();
    }

    private int insertionAfterLastExecutable() {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).executable()) return i + 1;
        }
        return lines.size();
    }

    private int nextExecutableAfter(Long afterId) {
        if (afterId != null && present(afterId)) {
            return findNextExecutableIndex(requireLineIndex(afterId) + 1);
        }
        return findNextExecutableIndex(0);
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

    private int currentExecutableIndex(long id) {
        if (executionIndex >= lines.size() || !lines.get(executionIndex).executable()
                || lines.get(executionIndex).id() != id) {
            return -1;
        }
        return executionIndex;
    }

    private int requireLineIndex(long id) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id() == id) return i;
        }
        throw new IllegalArgumentException("Unknown live scenario line id: " + id);
    }

    private static List<String> normalizeDocument(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of("");
        List<String> incoming = new ArrayList<>(texts.size());
        for (String text : texts) {
            incoming.add(text == null ? "" : text);
        }
        return incoming;
    }

    private List<Line> alignLines(List<Line> previous, List<String> incoming) {
        if (previous.size() == incoming.size()) {
            List<Line> updated = new ArrayList<>(incoming.size());
            for (int i = 0; i < incoming.size(); i++) {
                updated.add(new Line(previous.get(i).id(), incoming.get(i), classify(incoming.get(i))));
            }
            return updated;
        }

        int n = previous.size();
        int m = incoming.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (previous.get(i).text().equals(incoming.get(j))) {
                    dp[i][j] = 1 + dp[i + 1][j + 1];
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<Line> result = new ArrayList<>(m);
        int i = 0;
        int j = 0;
        while (j < m) {
            if (i < n && previous.get(i).text().equals(incoming.get(j))) {
                result.add(new Line(previous.get(i).id(), incoming.get(j), classify(incoming.get(j))));
                i++;
                j++;
            } else if (i < n && (j >= m || dp[i + 1][j] >= dp[i][j + 1])) {
                i++;
            } else {
                result.add(new Line(nextId++, incoming.get(j), classify(incoming.get(j))));
                j++;
            }
        }
        return result;
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
