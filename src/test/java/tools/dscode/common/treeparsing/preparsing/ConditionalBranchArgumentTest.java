package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.gherkin.messages.NGherkinFactory;
import io.cucumber.messages.types.InlinePickleArgument;
import org.junit.jupiter.api.Test;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.mappings.QuoteParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalBranchArgumentTest {
    private static final String DEFAULT_TABLE = "      | city |\n      | Tempe |";
    private static final String DEFAULT_DOC = "      \"\"\"\n      hello from the if line\n      \"\"\"";

    @Test
    void extractKeepsTypeAndRawTextUntilACallerParsesATable() {
        InlinePickleArgument.Extracted extracted = InlinePickleArgument.extract(
                "enter the row DT:::\"city\":Paris|");

        assertEquals("enter the row", extracted.stepText());
        assertEquals("DT", extracted.argumentType());
        assertEquals("\"city\":Paris|", extracted.argumentText());
        assertNull(NGherkinFactory.gherkinDataTableForInlineType("NOTE", "hello|"));

        String parsed = NGherkinFactory.gherkinDataTableForInlineType("DT", extracted.argumentText());
        assertTrue(parsed.contains("city"));
        assertTrue(parsed.contains("Paris"));
    }

    @Test
    void backtickWrapStillPeelsAMarkerThatEndsWithAPipe() {
        assertExtracts("enter the name DT:::city|Paris|");
        assertExtracts("`enter the name DT:::city|Paris|`");
        assertExtracts("`enter the name DT:::city|Paris|`.");
        assertExtracts(GlobalConstants.BOOK_END + "`enter the name DT:::city|Paris|`" + GlobalConstants.BOOK_END);
        assertNull(InlinePickleArgument.extract(
                "IF: ready THEN: enter the name DT:::city|Paris| ELSE: skip"));
        assertNull(InlinePickleArgument.extract("click the button."));
    }

    @Test
    void conditionalOperatorStripDoesNotRemoveTheMarkerFromExecutedText() {
        String markerOnly = "enter the name DT:::a|b|";
        assertEquals(markerOnly, LineData.wrapLooseConditionalExpression(markerOnly));

        String compared = LineData.wrapLooseConditionalExpression("if the value = 1 DT:::a|b|");
        assertTrue(compared.contains("DT:::a|b|"));
    }

    @Test
    void eachBranchKeepsItsOwnInlineTableAndOtherwiseInheritsTheStepArgument() {
        String rewritten = ParsedLine.rewriteConditionalBranches(
                "IF: ready THEN: enter the name DT:::city|Paris| ELSE: skip the field");
        assertTrue(rewritten.contains("run step `enter the name DT:::city|Paris|`"));
        assertTrue(rewritten.contains("run step `skip the field`"));
        assertFalse(rewritten.contains("skip the field DT:::"));

        List<String> branches = backtickValues(rewritten);
        String withMarker = branches.stream().filter(text -> text.contains("DT:::")).findFirst().orElseThrow();
        String withoutMarker = branches.stream().filter(text -> !text.contains("DT:::")).findFirst().orElseThrow();

        String override = ParsedLine.gherkinArgumentForBranch(withMarker, DEFAULT_TABLE);
        String inherited = ParsedLine.gherkinArgumentForBranch(withoutMarker, DEFAULT_TABLE);
        assertTrue(override.contains("Paris"));
        assertFalse(override.contains("Tempe"));
        assertTrue(inherited.contains("Tempe"));
        assertFalse(inherited.contains("Paris"));
        assertEquals(DEFAULT_DOC, ParsedLine.gherkinArgumentForBranch("enter the note", DEFAULT_DOC));
        assertFalse(ParsedLine.gherkinArgumentForBranch("enter the note DT:::city|Paris|", DEFAULT_DOC)
                .contains("hello from the if line"));
        assertEquals(DEFAULT_TABLE, ParsedLine.gherkinArgumentForBranch("enter the note NOTE:::hello|", DEFAULT_TABLE));
    }

    @Test
    void elseIfThenAndElseInheritOrOverrideOnTheirOwn() {
        String rewritten = ParsedLine.rewriteConditionalBranches(
                "IF: ready THEN: enter one ELSE-IF: later THEN: enter two DT:::city|Paris| ELSE: enter three");
        List<String> branches = backtickValues(rewritten);
        assertEquals(3, branches.size());
        assertEquals(DEFAULT_TABLE, ParsedLine.gherkinArgumentForBranch(branches.get(0), DEFAULT_TABLE));
        assertTrue(ParsedLine.gherkinArgumentForBranch(branches.get(1), DEFAULT_TABLE).contains("Paris"));
        assertFalse(ParsedLine.gherkinArgumentForBranch(branches.get(1), DEFAULT_TABLE).contains("Tempe"));
        assertEquals(DEFAULT_DOC, ParsedLine.gherkinArgumentForBranch(branches.get(2), DEFAULT_DOC));
    }

    @Test
    void peeledMarkerIsRestoredOntoTheLastBranchOnly() {
        String restored = ParsedLine.appendStoredInlineMarker(
                "IF: ready THEN: enter one ELSE: enter two",
                "DT",
                "city|Paris|");
        List<String> branches = backtickValues(ParsedLine.rewriteConditionalBranches(restored));
        assertEquals(2, branches.size());
        assertFalse(branches.get(0).contains("DT:::"));
        assertTrue(branches.get(1).contains("DT:::city|Paris|"));

        String first = ParsedLine.gherkinArgumentForBranch(branches.get(0), DEFAULT_TABLE);
        String last = ParsedLine.gherkinArgumentForBranch(branches.get(1), DEFAULT_TABLE);
        assertTrue(first.contains("Tempe"));
        assertFalse(first.contains("Paris"));
        assertTrue(last.contains("Paris"));
        assertFalse(last.contains("Tempe"));

        String duplicated = ParsedLine.appendStoredInlineMarker(
                "IF: ready THEN: enter one DT:::city|Paris| ELSE: enter two",
                "DT",
                "city|Paris|");
        assertTrue(duplicated.stripTrailing().endsWith("ELSE: enter two DT:::city|Paris|"));
        List<String> both = backtickValues(ParsedLine.rewriteConditionalBranches(duplicated));
        assertEquals(2, both.size());
        assertTrue(both.get(0).contains("DT:::city|Paris|"));
        assertTrue(both.get(1).contains("DT:::city|Paris|"));
        assertEquals(1, both.get(1).split("DT:::", -1).length - 1);
    }

    private static void assertExtracts(String text) {
        InlinePickleArgument.Extracted extracted = InlinePickleArgument.extract(text);
        assertEquals("enter the name", extracted.stepText());
        assertEquals("DT", extracted.argumentType());
        assertEquals("city|Paris|", extracted.argumentText());
    }

    private static List<String> backtickValues(String rewritten) {
        String lined = rewritten.stripTrailing();
        char last = lined.charAt(lined.length() - 1);
        if (",;:.!?".indexOf(last) < 0) {
            lined = lined + ".";
        }
        return new QuoteParser(lined).entriesBacktick().stream().map(entry -> entry.getValue()).toList();
    }
}
