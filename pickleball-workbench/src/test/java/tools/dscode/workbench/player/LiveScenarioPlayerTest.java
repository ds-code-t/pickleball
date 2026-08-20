package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveScenarioPlayerTest {

    @Test
    void selectionAndPlayheadAreIndependentAndIdsStayStable() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Feature: Demo",
                "Scenario: Demo",
                "Given first",
                "And second"
        ));
        long firstId = player.lines().get(2).id();
        long secondId = player.lines().get(3).id();

        player.select(secondId);
        LiveScenarioPlayer.Line inserted = player.insertStep("When inserted");

        assertEquals(secondId, player.selectedId().orElseThrow());
        assertNotEquals(firstId, inserted.id());
        assertNotEquals(secondId, inserted.id());
        assertEquals(inserted.id(), player.nextStep().orElseThrow().id());
        assertEquals(secondId, player.lines().stream()
                .filter(line -> line.text().equals("And second"))
                .findFirst().orElseThrow().id());
    }

    @Test
    void insertionOccursAtPlayheadAndBecomesNextStep() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "Then second"
        ));
        long originalFirst = player.nextStep().orElseThrow().id();

        LiveScenarioPlayer.Line inserted = player.insertStep("When inserted");

        assertEquals(0, player.playheadIndex());
        assertEquals(inserted.id(), player.nextStep().orElseThrow().id());
        assertEquals(originalFirst, player.lines().get(1).id());
    }

    @Test
    void runningWaitsAtEndAndNewStepMakesWorkEligibleAgain() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("Given first"));
        player.play();
        long firstId = player.nextStep().orElseThrow().id();

        player.markCurrentStepExecuted(firstId);

        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());
        assertTrue(player.nextStep().isEmpty());

        LiveScenarioPlayer.Line added = player.insertStep("And continue");

        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
        assertEquals(added.id(), player.nextStep().orElseThrow().id());
    }

    @Test
    void isolatedExecutionRequestLeavesMainPlayerPaused() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("Given first"));
        player.play();

        player.pauseForIsolatedExecution();

        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
    }

    @Test
    void failurePausesOnFailedStepWithoutAdvancing() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "Then second"
        ));
        player.play();
        long firstId = player.nextStep().orElseThrow().id();

        player.markCurrentStepFailed(firstId);

        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
        assertEquals(firstId, player.nextStep().orElseThrow().id());
        assertEquals(LiveScenarioPlayer.ExecutionStatus.FAILED, player.nextStep().orElseThrow().executionStatus());
    }

    @Test
    void updatingPendingSelectionPreservesIdButExecutedStepCannotBeEdited() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("Given first"));
        long id = player.nextStep().orElseThrow().id();
        player.select(id);

        LiveScenarioPlayer.Line updated = player.updateSelectedStep("Given changed");

        assertEquals(id, updated.id());
        assertEquals("Given changed", updated.text());

        player.play();
        player.markCurrentStepExecuted(id);
        assertThrows(IllegalStateException.class, () -> player.updateSelectedStep("Given changed again"));
    }

    @Test
    void nonExecutableLinesAreSkippedWhenAdvancing() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Feature: Demo",
                "Given first",
                "# comment",
                "",
                "Then second"
        ));
        player.play();
        long firstId = player.nextStep().orElseThrow().id();
        player.markCurrentStepExecuted(firstId);

        assertEquals("Then second", player.nextStep().orElseThrow().text());
    }
    @Test
    void basicPlayerStateTransitionsAreExplicit() {
        LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();

        assertEquals(LiveScenarioPlayer.State.STOPPED, player.state());
        player.play();
        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());
        player.pause();
        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
        player.stop();
        assertEquals(LiveScenarioPlayer.State.STOPPED, player.state());
    }

    @Test
    void firstAndBackMoveOnlyThePlayhead() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long selected = player.lines().get(2).id();
        player.select(selected);
        player.play();
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());

        player.movePlayheadToPreviousStep();
        assertEquals("And second", player.nextStep().orElseThrow().text());
        assertEquals(selected, player.selectedId().orElseThrow());

        player.movePlayheadToFirstStep();
        assertEquals("Given first", player.nextStep().orElseThrow().text());
        assertEquals(selected, player.selectedId().orElseThrow());
    }

}
