package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEditorViewTest {
    @Test
    void togglingTextAndBlocksDoesNotChangeDocumentTextOrPlayhead() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long second = player.lines().get(1).id();
        player.clickLine(second);
        String document = player.documentText();
        long selected = player.selectedId().orElseThrow();

        LiveEditorView view = LiveEditorView.blocksAvailable();
        assertEquals(LiveEditorView.Mode.BLOCKS, view.mode());
        assertTrue(view.showText());
        assertEquals(LiveEditorView.Mode.TEXT, view.mode());
        assertEquals(document, player.documentText());
        assertEquals(second, player.playheadId().orElseThrow());
        assertEquals(selected, player.selectedId().orElseThrow());

        assertTrue(view.showBlocks());
        assertEquals(LiveEditorView.Mode.BLOCKS, view.mode());
        assertEquals(document, player.documentText());
        assertEquals(second, player.playheadId().orElseThrow());
        assertEquals(selected, player.selectedId().orElseThrow());
        assertEquals("And second", player.playheadLine().orElseThrow().text());
    }

    @Test
    void unavailableBlocksStayOnTextAndRefuseBlockMode() {
        LiveEditorView view = LiveEditorView.blocksUnavailable();
        assertFalse(view.canShowBlocks());
        assertEquals(LiveEditorView.Mode.TEXT, view.mode());
        assertFalse(view.showBlocks());
        assertEquals(LiveEditorView.Mode.TEXT, view.mode());
        assertTrue(view.showText());
        assertEquals(LiveEditorView.Mode.TEXT, view.mode());
    }
}
