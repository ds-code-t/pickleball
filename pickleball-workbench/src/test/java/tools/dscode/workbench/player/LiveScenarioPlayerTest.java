package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveScenarioPlayerTest {
    @Test
    void interactiveBufferStartsWithWorkingSmokeSteps() {
        LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();

        assertEquals(LiveScenarioPlayer.State.STOPPED, player.state());
        player.startFromBeginning();
        assertEquals("  Given ---workbench-player-smoke-1", player.nextStep().orElseThrow().text());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
    }

    @Test
    void globalRunAlwaysStartsFromBeginning() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));

        player.startFromBeginning();
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        assertEquals("Then third", player.nextStep().orElseThrow().text());

        player.startFromBeginning();
        assertEquals("Given first", player.nextStep().orElseThrow().text());
    }

    @Test
    void fromHereStartsAtSelectedStep() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long second = player.lines().get(1).id();
        player.select(second);

        player.startFromSelectedStep();

        assertEquals("And second", player.nextStep().orElseThrow().text());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
    }

    @Test
    void fromHereRequiresExecutableSelection() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Scenario: sample",
                "Given first"
        ));
        player.select(player.lines().getFirst().id());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                player::startFromSelectedStep
        );
        assertTrue(failure.getMessage().contains("executable"));
    }

    @Test
    void successfulRunWaitsAtEndAndAddedStepContinues() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("Given first"));
        player.startFromBeginning();
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());

        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());

        LiveScenarioPlayer.Line added = player.insertStep("And added");
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
        assertEquals(added.id(), player.nextStep().orElseThrow().id());
    }

    @Test
    void enterInsertsAfterSelectionAndStepsRemainEditableAfterExecution() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "Then third"
        ));
        long first = player.lines().getFirst().id();
        player.select(first);
        LiveScenarioPlayer.Line inserted = player.insertStep("And second");

        assertEquals("And second", player.lines().get(1).text());
        player.select(inserted.id());
        player.startFromSelectedStep();
        player.markCurrentStepExecuted(inserted.id());

        LiveScenarioPlayer.Line updated = player.updateSelectedStep("And edited second");
        assertEquals("And edited second", updated.text());
    }

    @Test
    void failurePausesOnCurrentStep() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "Then second"
        ));
        player.startFromBeginning();
        long first = player.nextStep().orElseThrow().id();

        player.markCurrentStepFailed(first);

        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
        assertEquals(first, player.nextStep().orElseThrow().id());
    }
    @Test
    void insertingIntoCompletedMiddleDoesNotReplayLaterSteps() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long first = player.lines().getFirst().id();
        player.startFromBeginning();
        while (player.nextStep().isPresent()) {
            player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        }
        player.select(first);

        player.insertStep("And inserted in middle");

        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());
        assertTrue(player.nextStep().isEmpty());
    }

}
