package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleRowSelectorTest {
    private static final List<String> TWO_TABLES = List.of(
            "Scenario Outline: Buy",
            "  When buy <v>",
            "  Examples:",
            "    | v |",
            "    | a |",
            "    | b |",
            "    | c |",
            "  Examples:",
            "    | v |",
            "    | d |",
            "    | e |",
            "    | f |"
    );

    @Test
    void integerFiveIsTableTwoRowTwo() {
        ExampleRowSelector selector = ExampleRowSelector.parse("5");
        assertTrue(selector.matches(5, 2, 2));
        assertFalse(selector.matches(4, 2, 1));
        assertEquals(5, ExampleRowSelector.firstOverallIndex(TWO_TABLES, 0, TWO_TABLES.size(), "5"));
        assertEquals(5, ExampleRowSelector.firstOverallIndex(TWO_TABLES, 0, TWO_TABLES.size(), "2.2"));
    }

    @Test
    void listUsesTheFirstSourceMatch() {
        assertEquals(1, ExampleRowSelector.firstOverallIndex(TWO_TABLES, 0, TWO_TABLES.size(), "5 1 2.2"));
    }

    @Test
    void normalScenarioWithoutExamplesIsImplicitRowOne() {
        java.util.List<String> lines = java.util.List.of(
                "Feature: F",
                "  Scenario: Plain",
                "    Given stay"
        );
        assertEquals(1, ExampleRowSelector.firstOverallIndex(lines, 0, lines.size(), "1"));
        assertEquals(1, ExampleRowSelector.firstOverallIndex(lines, 0, lines.size(), "1.1"));
        assertEquals(0, ExampleRowSelector.firstOverallIndex(lines, 0, lines.size(), "2"));
    }

    @Test
    void normalScenarioRowOneMatchesOneAndOnePointOne() {
        ExampleRowSelector one = ExampleRowSelector.parse("1");
        ExampleRowSelector table = ExampleRowSelector.parse("1.1");
        assertTrue(one.matches(1, 1, 1));
        assertTrue(table.matches(1, 1, 1));
        assertFalse(ExampleRowSelector.parse("2").matches(1, 1, 1));
    }

    @Test
    void invalidTokensFail() {
        for (String token : List.of("0", "5-3", "2.0", "3.", "1.2.3")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ExampleRowSelector.parse(token)
            );
            assertTrue(failure.getMessage().contains("'" + token + "'"));
        }
    }
}
