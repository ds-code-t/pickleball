package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GherkinBlockDocumentTest {
    @Test
    void nestedColonStepsBecomeParentChildBlocksAndRoundTripToGherkin() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Feature: Nested",
                "Scenario: IF ELSE",
                "  * IF: the field is visible:",
                "  : * Then , click save",
                "  * ELSE:",
                "  : * Then , click cancel"
        ));

        GherkinBlockDocument document = GherkinBlockDocument.fromPlayer(player);
        assertEquals(4, document.roots().size());
        GherkinBlockDocument.Block ifBlock = document.roots().get(2);
        assertEquals("* IF: the field is visible:", ifBlock.text().strip());
        assertEquals(1, ifBlock.children().size());
        assertEquals("* Then , click save", ifBlock.children().getFirst().text().strip());
        assertTrue(ifBlock.children().getFirst().text().contains("Then"));

        LiveScenarioPlayer copy = LiveScenarioPlayer.interactiveBuffer();
        document.applyTo(copy);
        assertTrue(copy.documentText().contains(": * Then , click save"));
        assertTrue(copy.documentText().contains("Then"));
    }

    @Test
    void movingAStepNestsItUnderIfElseAndUpdatesThePlayerBuffer() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given parent stays",
                "And child moves",
                "* IF: ready:"
        ));
        GherkinBlockDocument document = GherkinBlockDocument.fromPlayer(player);
        long child = document.roots().get(1).id();
        long ifId = document.roots().get(2).id();

        GherkinBlockDocument nested = document.move(child, OptionalLong.of(ifId), 0);
        assertEquals(2, nested.roots().size());
        assertEquals(1, nested.find(ifId).orElseThrow().children().size());
        LivePlaybackCoordinator coordinator = new LivePlaybackCoordinator(player);
        coordinator.replaceFromBlocks(nested);

        assertTrue(player.documentText().contains(": And child moves"));
        assertTrue(player.documentText().contains("* IF: ready:"));
        assertTrue(player.lines().stream().anyMatch(line -> line.text().contains("And child moves")));
        assertTrue(GherkinBlockDocument.fromPlayer(player).roots().stream()
                .anyMatch(block -> block.text().contains("IF:") && block.children().size() == 1));
    }

    @Test
    void editingBlockTextKeepsGivenWhenThen() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of("  Given navigate to: URL.home"));
        GherkinBlockDocument document = GherkinBlockDocument.fromPlayer(player);
        long id = document.roots().getFirst().id();
        document.updateText(id, "  When , click the \"Open Forms Playground\" Link").applyTo(player);
        assertEquals("  When , click the \"Open Forms Playground\" Link", player.lines().getFirst().text());
        assertEquals(LiveScenarioPlayer.LineType.STEP, player.lines().getFirst().type());
    }
}
