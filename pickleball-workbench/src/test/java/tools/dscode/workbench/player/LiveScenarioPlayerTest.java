package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveScenarioPlayerTest {
    @Test
    void loadDocumentReplacesTheSessionBufferAndStopsPlayback() {
        LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();
        player.startFromBeginning();
        player.loadDocument(List.of("Feature: Picker", "  Given stay in session"));
        assertEquals(LiveScenarioPlayer.State.STOPPED, player.state());
        assertEquals("Feature: Picker\n  Given stay in session", player.documentText());
        assertTrue(player.lines().get(1).executable());
    }

    @Test
    void defaultDemoScenarioIsNonEmptyAndIncludesBrowserInteraction() {
        List<String> demo = LiveScenarioPlayer.DEFAULT_DEMO_SCENARIO;
        assertFalse(demo.isEmpty());
        assertTrue(demo.stream().anyMatch(line -> line.contains("navigate to: URL.home")));
        assertTrue(demo.stream().anyMatch(line -> line.contains("click the \"Open Forms Playground\" Link")));

        LiveScenarioPlayer player = LiveScenarioPlayer.interactiveBuffer();
        assertEquals(LiveScenarioPlayer.State.STOPPED, player.state());
        player.startFromBeginning();
        assertEquals("  Given navigate to: URL.home", player.nextStep().orElseThrow().text());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
    }

    @Test
    void clickingAStepInstantlyMovesThePlayhead() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long first = player.lines().get(0).id();
        long second = player.lines().get(1).id();
        long third = player.lines().get(2).id();

        assertEquals(first, player.playheadId().orElseThrow());

        player.clickLine(third);
        assertEquals(third, player.playheadId().orElseThrow());
        assertEquals(third, player.selectedId().orElseThrow());

        player.clickLine(second);
        assertEquals(second, player.playheadId().orElseThrow());
        assertEquals("And second", player.playheadLine().orElseThrow().text());
    }

    @Test
    void globalPlayAlwaysStartsFromBeginning() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        player.clickLine(player.lines().get(2).id());
        player.startFromBeginning();

        assertEquals("Given first", player.nextStep().orElseThrow().text());
        assertEquals(player.lines().getFirst().id(), player.playheadId().orElseThrow());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        assertEquals("Then third", player.nextStep().orElseThrow().text());

        player.startFromBeginning();
        assertEquals("Given first", player.nextStep().orElseThrow().text());
    }

    @Test
    void stepEditorHasIsolatedAndFromHerePlayActions() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long second = player.lines().get(1).id();
        player.clickLine(second);

        player.startFromSelectedStep();
        assertEquals("And second", player.nextStep().orElseThrow().text());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        player.pauseForIsolatedExecution();
        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
        assertEquals(second, player.nextStep().orElseThrow().id());
    }

    @Test
    void fromHereRequiresExecutableSelection() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Scenario: sample",
                "Given first"
        ));
        player.clickLine(player.lines().getFirst().id());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                player::startFromSelectedStep
        );
        assertTrue(failure.getMessage().contains("executable"));
    }

    @Test
    void successfulRunWaitsAtEndAndEnterAppendsAndContinues() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("Given first"));
        player.startFromBeginning();
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());

        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());
        assertTrue(player.nextStep().isEmpty());

        player.clickLine(player.lines().getFirst().id());
        LiveScenarioPlayer.Line added = player.insertStep("And added");

        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
        assertEquals(added.id(), player.nextStep().orElseThrow().id());
        assertEquals(added.id(), player.playheadId().orElseThrow());
        assertEquals("And added", player.lines().get(1).text());
    }

    @Test
    void typingAtEndWhileWaitingQueuesTheNewStep() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("Given first"));
        player.startFromBeginning();
        player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());

        List<String> edited = new ArrayList<>(player.lines().stream().map(LiveScenarioPlayer.Line::text).toList());
        edited.add("Then continue");
        player.replaceDocument(edited);

        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
        assertEquals("Then continue", player.nextStep().orElseThrow().text());
    }

    @Test
    void executedLinesRemainEditableInPlace() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "Then third"
        ));
        long first = player.lines().getFirst().id();
        player.clickLine(first);
        LiveScenarioPlayer.Line inserted = player.insertStep("And second");

        assertEquals("And second", player.lines().get(1).text());
        player.startFromBeginning();
        player.markCurrentStepExecuted(first);
        player.markCurrentStepExecuted(inserted.id());

        LiveScenarioPlayer.Line updated = player.updateLine(first, "Given edited first");
        assertEquals(first, updated.id());
        assertEquals("Given edited first", player.lines().getFirst().text());

        player.clickLine(inserted.id());
        LiveScenarioPlayer.Line fromEditor = player.updateSelectedStep("And edited second");
        assertEquals(inserted.id(), fromEditor.id());
        assertEquals("And edited second", player.lines().get(1).text());
    }

    @Test
    void replaceDocumentKeepsStableIdsWhenEditingInPlace() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "Then second"
        ));
        long first = player.lines().getFirst().id();
        long second = player.lines().get(1).id();

        player.replaceDocument(List.of("Given edited first", "Then second"));

        assertEquals(first, player.lines().getFirst().id());
        assertEquals(second, player.lines().get(1).id());
        assertEquals("Given edited first", player.lines().getFirst().text());
        assertEquals(LiveScenarioPlayer.LineType.STEP, player.lines().getFirst().type());
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
        assertEquals(first, player.playheadId().orElseThrow());
    }

    @Test
    void insertingIntoCompletedMiddleDoesNotReplayLaterSteps() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        player.startFromBeginning();
        while (player.nextStep().isPresent()) {
            player.markCurrentStepExecuted(player.nextStep().orElseThrow().id());
        }
        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());

        List<String> edited = new ArrayList<>(player.lines().stream().map(LiveScenarioPlayer.Line::text).toList());
        edited.add(1, "And inserted in middle");
        player.replaceDocument(edited);

        assertEquals(LiveScenarioPlayer.State.WAITING_FOR_STEP, player.state());
        assertTrue(player.nextStep().isEmpty());
        assertNotEquals("And inserted in middle", player.lines().getFirst().text());
    }

    @Test
    void leftoverPlayheadMarksAreIdempotentAndDoNotThrow() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        player.startFromBeginning();
        long first = player.nextStep().orElseThrow().id();
        long second = player.lines().get(1).id();

        assertDoesNotThrow(() -> player.markCurrentStepExecuted(first));
        assertDoesNotThrow(() -> player.markCurrentStepExecuted(first));
        assertDoesNotThrow(() -> player.markCurrentStepExecuted(second + 99));

        assertEquals(second, player.nextStep().orElseThrow().id());
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());

        assertDoesNotThrow(() -> player.markCurrentStepFailed(first));
        assertEquals(LiveScenarioPlayer.State.RUNNING, player.state());
        assertEquals(second, player.nextStep().orElseThrow().id());

        assertDoesNotThrow(() -> player.markCurrentStepFailed(second));
        assertDoesNotThrow(() -> player.markCurrentStepFailed(second));
        assertEquals(LiveScenarioPlayer.State.PAUSED, player.state());
        assertEquals(second, player.nextStep().orElseThrow().id());
    }
}
