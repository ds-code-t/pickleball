package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LiveEditorViewTest {
    @Test
    void liveEditorIsTextOnlyWithNoBlocksToggle() {
        LiveEditorView view = LiveEditorView.textOnly();
        assertEquals(LiveEditorView.Mode.TEXT, view.mode());
        assertFalse(view.canShowBlocks());
        assertFalse(view.showingBlocks());
        assertEquals(1, LiveEditorView.Mode.values().length);
        assertEquals(LiveEditorView.Mode.TEXT, LiveEditorView.Mode.values()[0]);
    }

    @Test
    void textOnlyViewDoesNotChangeDocumentTextOrPlayhead() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "Given first",
                "And second",
                "Then third"
        ));
        long second = player.lines().get(1).id();
        player.clickLine(second);
        String document = player.documentText();
        long selected = player.selectedId().orElseThrow();

        LiveEditorView view = LiveEditorView.textOnly();
        assertEquals(LiveEditorView.Mode.TEXT, view.mode());
        assertEquals(document, player.documentText());
        assertEquals(second, player.playheadId().orElseThrow());
        assertEquals(selected, player.selectedId().orElseThrow());
        assertEquals("And second", player.playheadLine().orElseThrow().text());
    }
}
