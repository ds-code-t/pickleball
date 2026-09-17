package tools.dscode.common.assertions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssertionClauseSplitterTest {
    @Test
    void orExpressionIsOneClause() {
        assertEquals(List.of("1 < 2 || false"), AssertionClauseSplitter.split("1 < 2 || false"));
    }

    @Test
    void parenthesizedOrExpressionIsOneClause() {
        assertEquals(
                List.of("(1 < 2) || (3 < 1)"),
                AssertionClauseSplitter.split("(1 < 2) || (3 < 1)")
        );
    }

    @Test
    void singlePipeSplitsExpressionAndPhraseClauses() {
        assertEquals(
                List.of("1 < 2", "\"A\" equals \"A\""),
                AssertionClauseSplitter.split("1 < 2 | \"A\" equals \"A\"")
        );
    }

    @Test
    void orExpressionThenPipeIsTwoClauses() {
        assertEquals(
                List.of("1 < 2 || false", "\"A\" equals \"A\""),
                AssertionClauseSplitter.split("1 < 2 || false | \"A\" equals \"A\"")
        );
    }

    @Test
    void pipeInsideDoubleQuotesIsNotADelimiter() {
        assertEquals(
                List.of("\"A|B\" equals \"A|B\""),
                AssertionClauseSplitter.split("\"A|B\" equals \"A|B\"")
        );
    }

    @Test
    void pipeInsideSingleQuotesIsNotADelimiter() {
        assertEquals(
                List.of("'A|B' equals 'a|b'"),
                AssertionClauseSplitter.split("'A|B' equals 'a|b'")
        );
    }

    @Test
    void pipeInsideBackticksIsNotADelimiter() {
        assertEquals(
                List.of("`A|B` equals `A|B`"),
                AssertionClauseSplitter.split("`A|B` equals `A|B`")
        );
    }

    @Test
    void blanksAroundPipesAreIgnored() {
        assertEquals(
                List.of("1 < 2", "\"A\" equals \"A\""),
                AssertionClauseSplitter.split("  1 < 2  |  \"A\" equals \"A\"  |   ")
        );
    }

    @Test
    void blankPayloadIsEmpty() {
        assertEquals(List.of(), AssertionClauseSplitter.split(null));
        assertEquals(List.of(), AssertionClauseSplitter.split(""));
        assertEquals(List.of(), AssertionClauseSplitter.split("   "));
    }
}
