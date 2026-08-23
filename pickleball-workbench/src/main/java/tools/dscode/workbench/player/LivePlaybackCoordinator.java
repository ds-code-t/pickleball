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
    private java.nio.file.Path originFile;

    public LivePlaybackCoordinator(LiveScenarioPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    public LiveScenarioPlayer player() {
        return player;
    }

    public Optional<java.nio.file.Path> originFile() {
        return Optional.ofNullable(originFile);
    }

    public void clearOrigin() {
        originFile = null;
    }

    public void loadDefaultDemo() {
        originFile = null;
        player.loadDocument(LiveScenarioPlayer.DEFAULT_DEMO_SCENARIO);
    }

    public void loadScenario(List<String> lines, java.nio.file.Path origin) {
        originFile = origin;
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
}
