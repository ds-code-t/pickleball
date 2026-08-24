package tools.dscode.workbench.player;

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

    public LivePlaybackCoordinator(LiveScenarioPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
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
    }

    public void loadDefaultDemo() {
        origin = ScenarioOrigin.none();
        player.loadDocument(LiveScenarioPlayer.DEFAULT_DEMO_SCENARIO);
    }

    public void loadScenario(List<String> lines, java.nio.file.Path originFile) {
        loadScenario(lines, originFile, "", 0, 0);
    }

    public void loadScenario(
            List<String> lines,
            java.nio.file.Path originFile,
            String scenarioName,
            int startLine,
            int endLine
    ) {
        origin = originFile == null
                ? ScenarioOrigin.none()
                : new ScenarioOrigin(originFile, scenarioName, startLine, endLine);
        player.loadDocument(lines);
    }

    public GherkinBlockDocument blocks() {
        return GherkinBlockDocument.fromPlayer(player);
    }

    public void replaceFromBlocks(GherkinBlockDocument document) {
        Objects.requireNonNull(document, "document").applyTo(player);
    }

    public void replaceFromLines(List<String> lines) {
        player.replaceDocument(lines);
    }

    public void seek(long lineId) {
        player.clickLine(lineId);
    }

    public void playFromStart() {
        player.startFromBeginning();
    }

    public void playFromHere() {
        player.startFromSelectedStep();
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
        LiveScenarioPlayer.Line next = player.nextStep().orElse(null);
        if (next == null || text == null || !next.text().equals(text)
                || player.state() != LiveScenarioPlayer.State.RUNNING) {
            return;
        }
        if (successful) {
            player.markCurrentStepExecuted(next.id());
        } else {
            player.markCurrentStepFailed(next.id());
        }
    }
}
