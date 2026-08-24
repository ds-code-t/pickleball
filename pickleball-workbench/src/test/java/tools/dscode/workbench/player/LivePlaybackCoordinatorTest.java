package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void executeStepOwnsThePlayheadSoALeftoverUiMarkCannotStallPlay() {
        LivePlaybackCoordinator coordinator = new LivePlaybackCoordinator(LiveScenarioPlayer.interactiveBuffer());
        LiveScenarioPlayer player = coordinator.player();
        coordinator.playFromStart();

        LiveScenarioPlayer.Line first = player.nextStep().orElseThrow();
        assertTrue(first.text().contains("navigate to: URL.home"));
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        coordinator.followExecutedStep(first.text(), true);
        assertDoesNotThrow(() -> simulateFrameSuccessCallback(player, first));

        LiveScenarioPlayer.Line second = player.nextStep().orElseThrow();
        assertNotEquals(first.id(), second.id());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        int walked = 1;
        while (player.state() == LiveScenarioPlayer.State.RUNNING && player.nextStep().isPresent()) {
            LiveScenarioPlayer.Line step = player.nextStep().orElseThrow();
            coordinator.followExecutedStep(step.text(), true);
            assertDoesNotThrow(() -> simulateFrameSuccessCallback(player, step));
            walked++;
        }

        assertTrue(walked >= 3, "demo buffer should walk more than the first live step, walked=" + walked);
        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());
        assertTrue(player.nextStep().isEmpty());
    }

    @Test
    void agentExecuteStepAdvancesThePlayheadOnceWhileRunning() {
        LivePlaybackCoordinator coordinator = new LivePlaybackCoordinator(new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        )));
        LiveScenarioPlayer player = coordinator.player();
        coordinator.playFromStart();
        LiveScenarioPlayer.Line first = player.nextStep().orElseThrow();

        coordinator.followExecutedStep(first.text(), true);
        assertEquals("And second", player.nextStep().orElseThrow().text());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        coordinator.followExecutedStep(first.text(), true);
        assertEquals("And second", player.nextStep().orElseThrow().text());
    }

    @Test
    void isolatedStepOnlyDoesNotAdvanceThePlayhead() {
        LivePlaybackCoordinator coordinator = new LivePlaybackCoordinator(new LiveScenarioPlayer(List.of(
                "Given first",
                "And second"
        )));
        LiveScenarioPlayer player = coordinator.player();
        coordinator.playFromStart();
        LiveScenarioPlayer.Line first = player.nextStep().orElseThrow();

        coordinator.stepOnly();
        coordinator.followExecutedStep(first.text(), true);

        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
        assertEquals(first.id(), player.nextStep().orElseThrow().id());
    }

    /**
     * Former WorkbenchFrame success callback: remake the captured step mark after
     * executeStep already followed the playhead, then continue while RUNNING.
     */
    private static void simulateFrameSuccessCallback(LiveScenarioPlayer player, LiveScenarioPlayer.Line captured) {
        player.markCurrentStepExecuted(captured.id());
    }
}
