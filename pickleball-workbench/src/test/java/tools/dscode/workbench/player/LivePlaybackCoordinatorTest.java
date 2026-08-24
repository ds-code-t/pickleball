package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePlaybackCoordinatorTest {
    @Test
    void playHeaderStillStartsFromBeginningStepsFromHereAndWaitsAtEnd() {
        LivePlaybackCoordinator coordinator = new LivePlaybackCoordinator(new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        )));
        LiveScenarioPlayer player = coordinator.player();
        coordinator.seek(player.lines().get(2).id());

        coordinator.playFromStart();
        assertEquals("Given first", player.nextStep().orElseThrow().text());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        assertEquals("Then third", player.nextStep().orElseThrow().text());

        coordinator.playFromStart();
        assertEquals("Given first", player.nextStep().orElseThrow().text());

        coordinator.seek(player.lines().get(1).id());
        coordinator.playFromHere();
        assertEquals("And second", player.nextStep().orElseThrow().text());

        coordinator.stepOnly();
        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());

        player.startFromBeginning();
        while (player.nextStep().isPresent()) {
            player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        }
        assertTrue(coordinator.waitingForStep());
        coordinator.insertAndMaybeContinue("And appended");
        assertTrue(coordinator.running());
        assertEquals("And appended", player.nextStep().orElseThrow().text());
    }

    @Test
    void loadingAScenarioReplacesTheLiveBufferWithoutWritingFiles() {
        LivePlaybackCoordinator coordinator = new LivePlaybackCoordinator(LiveScenarioPlayer.interactiveBuffer());
        coordinator.loadScenario(List.of(
                "Feature: From picker",
                "Scenario: Loaded",
                "  Given stay in session"
        ), null);

        assertEquals("Feature: From picker\nScenario: Loaded\n  Given stay in session",
                coordinator.player().documentText());
        assertEquals(LiveScenarioPlayer.State.STOPPED, coordinator.player().state());
        assertTrue(coordinator.originFile().isEmpty());

        coordinator.loadDefaultDemo();
        assertTrue(coordinator.player().documentText().contains("navigate to: URL.home"));
        assertTrue(coordinator.origin().savable() == false);
    }
}
