package tools.dscode.workbench.player;

import tools.dscode.control.protocol.ExampleRowSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Headless play-header / block-buffer coordinator. Swing and WebView adapters
 * report selection, playhead, and document changes here; execution still goes
 * through {@code WorkbenchServices.executeStep}.
 */
public final class LivePlaybackCoordinator {
    private final LiveScenarioPlayer player;
    private ScenarioOrigin origin = ScenarioOrigin.none();
    private GherkinPlayPlan playPlan = new GherkinPlayPlan(List.of());
    private int planIndex;

    public LivePlaybackCoordinator(LiveScenarioPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
        rebuildPlan();
    }

    public LiveScenarioPlayer player() {
        return player;
    }

    public ScenarioOrigin origin() {
        return origin;
    }

    public Optional<java.nio.file.Path> originFile() {
        return origin.originFile();
    }

    public void clearOrigin() {
        origin = ScenarioOrigin.none();
    }

    public void updateOrigin(ScenarioOrigin origin) {
        this.origin = origin == null ? ScenarioOrigin.none() : origin;
        rebuildPlan();
    }

    public GherkinPlayPlan playPlan() {
        return playPlan;
    }

    public void rebuildPlan() {
        playPlan = GherkinPlayPlan.from(player, origin);
        planIndex = 0;
    }

    public void loadDefaultDemo() {
        origin = ScenarioOrigin.none();
        player.loadDocument(LiveScenarioPlayer.DEFAULT_DEMO_SCENARIO);
        rebuildPlan();
    }

    public void loadScenario(List<String> lines, java.nio.file.Path originFile) {
        loadScenario(lines, originFile, "", 0, 0, 0, "");
    }

    public void loadScenario(
            List<String> lines,
            java.nio.file.Path originFile,
            String scenarioName,
            int startLine,
            int endLine
    ) {
        loadScenario(lines, originFile, scenarioName, startLine, endLine, 0, "");
    }

    public void loadScenario(
            List<String> lines,
            java.nio.file.Path originFile,
            String scenarioName,
            int startLine,
            int endLine,
            int exampleRow,
            String exampleLabel
    ) {
        loadScenario(lines, originFile, scenarioName, startLine, endLine, exampleRow, exampleLabel, "");
    }

    public void loadScenario(
            List<String> lines,
            java.nio.file.Path originFile,
            String scenarioName,
            int startLine,
            int endLine,
            int exampleRow,
            String exampleLabel,
            String exampleSelector
    ) {
        String selector = exampleSelector == null ? "" : exampleSelector.strip();
        if (ExampleRowSelector.isInactive(selector) && exampleRow > 0) {
            selector = Integer.toString(exampleRow);
        }
        if (!ExampleRowSelector.isInactive(selector)) {
            ExampleRowSelector.parse(selector);
        }
        origin = originFile == null
                ? ScenarioOrigin.none()
                : new ScenarioOrigin(
                        originFile, scenarioName, startLine, endLine, exampleRow, exampleLabel, selector
                );
        player.loadDocument(lines);
        if (originFile != null && !ExampleRowSelector.isInactive(selector)) {
            int resolved = ExampleRowSelector.firstOverallIndex(
                    lineTexts(),
                    startLine > 0 ? startLine - 1 : 0,
                    endLine > 0 ? endLine : -1,
                    selector
            );
            if (resolved <= 0) {
                throw new IllegalArgumentException("No Examples row matched '" + selector + "'.");
            }
            String label = exampleLabel == null || exampleLabel.isBlank() ? "row " + resolved : exampleLabel;
            origin = new ScenarioOrigin(
                    originFile, scenarioName, startLine, endLine, resolved, label, selector
            );
        }
        rebuildPlan();
    }

    public void selectExample(String expression) {
        if (ExampleRowSelector.isInactive(expression)) {
            throw new IllegalArgumentException("example selector must not be blank.");
        }
        ExampleRowSelector.parse(expression);
        String selector = expression.strip();
        int start = origin.startLine() > 0 ? origin.startLine() - 1 : 0;
        int end = origin.endLine() > 0 ? origin.endLine() : -1;
        int resolved = ExampleRowSelector.firstOverallIndex(lineTexts(), start, end, selector);
        if (resolved <= 0) {
            throw new IllegalArgumentException("No Examples row matched '" + selector + "'.");
        }
        String label = origin.exampleLabel();
        if (label.isBlank() || label.startsWith("row ")) label = "row " + resolved;
        origin = new ScenarioOrigin(
                origin.file(), origin.scenarioName(), origin.startLine(), origin.endLine(),
                resolved, label, selector
        );
        rebuildPlan();
    }

    public int planIndex() {
        return planIndex;
    }

    public GherkinBlockDocument blocks() {
        return GherkinBlockDocument.fromPlayer(player);
    }

    public void replaceFromBlocks(GherkinBlockDocument document) {
        Objects.requireNonNull(document, "document").applyTo(player);
    }

    public void replaceFromLines(List<String> lines) {
        player.replaceDocument(lines);
        rebuildPlan();
    }

    public void seek(long lineId) {
        player.clickLine(lineId);
    }

    public void playFromStart() {
        rebuildPlan();
        planIndex = 0;
        player.startFromBeginning();
        seekPlanPlayhead();
    }

    public void playFromHere() {
        rebuildPlan();
        LiveScenarioPlayer.Line selected = player.selectedLine().orElse(player.playheadLine().orElse(null));
        if (selected == null || !selected.executable()) {
            playFromStart();
            return;
        }
        planIndex = 0;
        for (int i = 0; i < playPlan.steps().size(); i++) {
            if (playPlan.steps().get(i).sourceLineId() == selected.id()) {
                planIndex = i;
                break;
            }
        }
        player.startFromSelectedStep();
        seekPlanPlayhead();
    }

    public Optional<GherkinPlayPlan.Step> nextPlanStep() {
        if (planIndex < 0 || planIndex >= playPlan.steps().size()) return Optional.empty();
        return Optional.of(playPlan.steps().get(planIndex));
    }

    public Optional<GherkinPlayPlan.Step> planStepAt(int index) {
        if (index < 0 || index >= playPlan.steps().size()) return Optional.empty();
        return Optional.of(playPlan.steps().get(index));
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        player.stop();
    }

    public void stepOnly() {
        player.pauseForIsolatedExecution();
    }

    public LiveScenarioPlayer.Line insertAndMaybeContinue(String text) {
        return player.insertStep(text);
    }

    public boolean waitingForStep() {
        return player.state() == LiveScenarioPlayer.State.WAITING_FOR_STEP;
    }

    public boolean running() {
        return player.state() == LiveScenarioPlayer.State.RUNNING;
    }

    /**
     * Single owner for playhead follow after a worker {@code executeStep}.
     * Advances only while {@link LiveScenarioPlayer.State#RUNNING} and only when
     * the executed text is the current next step. Isolated Step Only pauses first,
     * so it does not move the playhead. Attached-agent {@code execute_step} uses
     * this same follow while a UI Play run is in progress.
     */
    public void followExecutedStep(String text, boolean successful) {
        GherkinPlayPlan.Step current = nextPlanStep().orElse(null);
        if (current == null || text == null || player.state() != LiveScenarioPlayer.State.RUNNING) {
            return;
        }
        if (!text.equals(current.executeText()) && !text.equals(current.sourceText())) {
            return;
        }
        if (successful) {
            planIndex++;
            boolean more = planIndex < playPlan.steps().size();
            Long nextId = more ? playPlan.steps().get(planIndex).sourceLineId() : null;
            player.finishPlanStep(current.sourceLineId(), more, nextId);
            seekPlanPlayhead();
        } else {
            player.markCurrentStepFailed(current.sourceLineId());
        }
    }

    private void seekPlanPlayhead() {
        nextPlanStep().ifPresent(step -> player.clickLine(step.sourceLineId()));
    }

    private List<String> lineTexts() {
        List<String> texts = new ArrayList<>();
        for (LiveScenarioPlayer.Line line : player.lines()) {
            texts.add(line.text());
        }
        return texts;
    }
}
