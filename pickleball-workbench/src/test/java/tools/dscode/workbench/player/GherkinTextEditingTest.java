package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GherkinTextEditingTest {
    @Test
    void tabAtBeginningOfEmptyOrColonOnlyLinePrependsColon() {
        assertEquals(new GherkinTextEditing.LineCaret(":", 1), GherkinTextEditing.indent("", 0));
        assertEquals(new GherkinTextEditing.LineCaret("::", 2), GherkinTextEditing.indent(":", 1));
        assertEquals(new GherkinTextEditing.LineCaret(":::", 3), GherkinTextEditing.indent("::", 2));
        assertEquals(new GherkinTextEditing.LineCaret(":: And click", 2),
                GherkinTextEditing.indent(": And click", 1));
    }

    @Test
    void shiftTabRemovesOneLeadingColon() {
        assertEquals(new GherkinTextEditing.LineCaret(":", 1), GherkinTextEditing.outdent("::", 2));
        assertEquals(new GherkinTextEditing.LineCaret("", 0), GherkinTextEditing.outdent(":", 1));
        assertEquals(new GherkinTextEditing.LineCaret(": And click", 1),
                GherkinTextEditing.outdent(":: And click", 2));
        assertEquals(new GherkinTextEditing.LineCaret("When open", 0),
                GherkinTextEditing.outdent("When open", 0));
    }

    @Test
    void midLineTabDoesNotPrependColon() {
        String line = "When I open URL.home";
        GherkinTextEditing.LineCaret edited = GherkinTextEditing.tab(line, 8, false, null);
        assertEquals("When I o pen URL.home", edited.line());
        assertFalse(edited.line().startsWith(":"));
        assertEquals(9, edited.caretColumn());
        assertFalse(GherkinTextEditing.inIndentPrefix(line, 8));
    }

    @Test
    void tabAcceptsOpenCompletionInsteadOfIndenting() {
        GherkinTextEditing.LineCaret accepted = GherkinTextEditing.tab("Gi", 2, true, "Given");
        assertEquals("Given ", accepted.line());
        assertEquals(6, accepted.caretColumn());
    }

    @Test
    void keywordCompletionMatchesStepsAndStructureAfterColons() {
        assertEquals(List.of("Given"), GherkinTextEditing.completions("Gi", 2));
        assertEquals(List.of("When"), GherkinTextEditing.completions("Wh", 2));
        assertEquals(List.of("Then"), GherkinTextEditing.completions("Th", 2));
        assertEquals(List.of("And"), GherkinTextEditing.completions("An", 2));
        assertEquals(List.of("But"), GherkinTextEditing.completions("Bu", 2));
        assertEquals(List.of("*"), GherkinTextEditing.completions("*", 1));
        assertEquals(List.of("Feature"), GherkinTextEditing.completions("Fe", 2));
        assertEquals(List.of("Scenario", "Scenario Outline"), GherkinTextEditing.completions("Sc", 2));
        assertEquals(List.of("Given"), GherkinTextEditing.completions(": Gi", 4));
        assertEquals(List.of("When"), GherkinTextEditing.completions("::Wh", 4));
        assertEquals(List.of("Then"), GherkinTextEditing.completions("  Th", 4));
        assertEquals(List.of("IF"), GherkinTextEditing.completions("I", 1));
        assertEquals(List.of("IF"), GherkinTextEditing.completions("IF", 2));
        assertEquals(List.of("ELSE", "ELSE-IF"), GherkinTextEditing.completions("EL", 2));
        assertEquals(List.of(), GherkinTextEditing.completions("Given navigate", 14));
        assertEquals(List.of(), GherkinTextEditing.completions("When I open", 16));
    }

    @Test
    void acceptingAKeywordInsertsATrailingSpaceWithoutRewritingTheRest() {
        assertEquals(new GherkinTextEditing.LineCaret("Given ", 6),
                GherkinTextEditing.acceptCompletion("Gi", 2, "Given"));
        assertEquals(new GherkinTextEditing.LineCaret(": When ", 7),
                GherkinTextEditing.acceptCompletion(": Wh", 4, "When"));
        assertEquals(new GherkinTextEditing.LineCaret("Scenario Outline ", 17),
                GherkinTextEditing.acceptCompletion("Sc", 2, "Scenario Outline"));
        assertEquals("Given navigate to: URL.home",
                GherkinTextEditing.acceptCompletion("Given navigate to: URL.home", 5, "Given").line());
    }

    @Test
    void indentUsesLivePlayerInPlaceEditsWithoutChangingLineIds() {
        LiveScenarioPlayer player = new LiveScenarioPlayer(List.of(
                "When I open URL.home",
                ""
        ));
        long emptyId = player.lines().get(1).id();
        long firstId = player.lines().get(0).id();
        player.clickLine(emptyId);
        GherkinTextEditing.LineCaret indented = GherkinTextEditing.indent("", 0);
        player.updateLine(emptyId, indented.line());
        assertEquals(":", player.lines().get(1).text());
        assertEquals(emptyId, player.lines().get(1).id());
        assertEquals(firstId, player.lines().get(0).id());
        assertEquals(emptyId, player.playheadId().orElseThrow());
    }
}
