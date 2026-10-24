package tools.dscode.common.assertions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.preparsing.LineData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

/**
 * Consumer evidence, quoted and unquoted:
 * {@code * ASSERT: "<Probe.ml>" contains "line two"} with {@code line one\nline two}.
 * {@code * ASSERT: <Q.RESPONSE.error> contains "no_such_table_x"} against
 * {@code relation "no_such_table_x" does not exist\nPosition: 15}.
 */
class MultilineAssertTest {

    private static final String LINES = "line one\nline two";
    private static final String JDBC = "relation \"no_such_table_x\" does not exist\nPosition: 15";
    private static final String JDBC_CRLF = "relation \"no_such_table_x\" does not exist\r\nPosition: 15";
    private static final String WINDOWS_PATH = "C:\\Users\\Dan She'll\\page.html";
    private static final String SPACED_PATH = "C:/Program Files/Pickleball/body.json";

    @BeforeEach
    void resetMaps() {
        MappingProcessor.resetCommonMaps();
    }

    @Test
    void resolvedAssertTextKeepsTheConsumerLineBreak() {
        ParsingMap map = new ParsingMap();
        map.put("Probe.ml", LINES);
        map.put("Q.RESPONSE.error", JDBC);
        map.put("Q.RESPONSE.errorCrlf", JDBC_CRLF);

        String quoted = map.resolveWholeText("* ASSERT: \"<Probe.ml>\" contains \"line two\"");
        String bare = map.resolveWholeText("* ASSERT: <Probe.ml> contains \"line two\"");
        String jdbc = map.resolveWholeText("* ASSERT: <Q.RESPONSE.error> contains \"no_such_table_x\"");
        String jdbcQuoted = map.resolveWholeText("* ASSERT: \"<Q.RESPONSE.error>\" contains \"no_such_table_x\"");
        String jdbcCrlf = map.resolveWholeText("* ASSERT: <Q.RESPONSE.errorCrlf> contains \"Position: 15\"");

        assertEquals("* ASSERT: \"line one\nline two\" contains \"line two\"", quoted);
        assertEquals("* ASSERT: line one\nline two contains \"line two\"", bare);
        assertEquals("* ASSERT: " + JDBC + " contains \"no_such_table_x\"", jdbc);
        String escapedJdbc = JDBC.replace("\"", "\\\"");
        assertEquals("* ASSERT: \"" + escapedJdbc + "\" contains \"no_such_table_x\"", jdbcQuoted);
        assertTrue(jdbcCrlf.contains("\r\n"), jdbcCrlf);
        assertTrue(jdbc.contains("\n"), jdbc);
    }

    @Test
    void comparisonsKeepLineBreaksAndStillCollapseSingleLineSpace() {
        ValueWrapper lines = createValueWrapper("\"" + LINES + "\"");
        ValueWrapper same = createValueWrapper("\"" + LINES + "\"");
        ValueWrapper flat = createValueWrapper("\"line one line two\"");
        ValueWrapper crlf = createValueWrapper("\"" + JDBC_CRLF + "\"");
        ValueWrapper lf = createValueWrapper("\"" + JDBC + "\"");

        assertTrue(ValueWrapperComparisons.equals(lines, same));
        assertFalse(ValueWrapperComparisons.equals(lines, flat));
        assertTrue(ValueWrapperComparisons.equals(crlf, lf));
        assertTrue(ValueWrapperComparisons.contains(lines, createValueWrapper("\"line two\"")));
        assertTrue(ValueWrapperComparisons.contains(lines, createValueWrapper("'LINE TWO'")));
        assertTrue(ValueWrapperComparisons.startsWith(lines, createValueWrapper("\"line one\"")));
        assertTrue(ValueWrapperComparisons.endsWith(lines, createValueWrapper("\"line two\"")));
        assertTrue(ValueWrapperComparisons.matchesRegex(
                lines,
                createValueWrapper("\"(?s)line one\nline two\"")));
        assertTrue(ValueWrapperComparisons.contains(lf, createValueWrapper("\"no_such_table_x\"")));
        assertTrue(ValueWrapperComparisons.contains(crlf, createValueWrapper("\"Position: 15\"")));
        assertTrue(ValueWrapperComparisons.startsWith(lf, createValueWrapper("\"relation\"")));
        assertTrue(ValueWrapperComparisons.endsWith(crlf, createValueWrapper("\"Position: 15\"")));
        assertTrue(ValueWrapperComparisons.matchesRegex(
                lf,
                createValueWrapper("\"(?s).*no_such_table_x.*Position: 15\"")));
        assertFalse(ValueWrapperComparisons.matchesRegex(
                lines,
                createValueWrapper("\".*line two\"")));
        assertTrue(ValueWrapperComparisons.contains(
                createValueWrapper("`" + LINES + "`"),
                createValueWrapper("`line two`")));

        assertTrue(ValueWrapperComparisons.equals(
                createValueWrapper("\"hello   world\""),
                createValueWrapper("\"hello world\"")));
        assertTrue(ValueWrapperComparisons.contains(
                createValueWrapper("\"" + WINDOWS_PATH + "\""),
                createValueWrapper("\"She'll\"")));
        assertTrue(ValueWrapperComparisons.contains(
                createValueWrapper("\"" + SPACED_PATH + "\""),
                createValueWrapper("\"Program Files\"")));
        assertFalse(ValueWrapperComparisons.equals(
                createValueWrapper("\"alpha\""),
                createValueWrapper("\"beta\"")));
    }

    @Test
    void quoteParserKeepsANewlineInsideOneSpan() {
        QuoteParser parsed = new QuoteParser("\"" + LINES + "\" contains \"line two\"");
        assertTrue(parsed.values().stream().anyMatch(value -> value.contains("\n") && value.contains("line two")),
                parsed.toString());

        QuoteParser jdbc = new QuoteParser("\"" + JDBC.replace("\"", "\\\"") + "\"");
        assertEquals(JDBC, jdbc.values().iterator().next());

        QuoteParser crlf = new QuoteParser("\"" + JDBC_CRLF.replace("\"", "\\\"") + "\"");
        assertEquals(JDBC_CRLF, crlf.values().iterator().next());

        QuoteParser nel = new QuoteParser("'\u0085not-a-span'");
        assertTrue(nel.isEmpty(), nel.toString());
    }

    @Test
    void unquotedMultilineOperandIsOneQuotedSpanAndSingleLineStays() {
        String bare = ", ensure " + LINES + " contains \"line two\"";
        String wrapped = LineData.protectMultilineComparisonOperands(bare);
        assertEquals(", ensure \"line one\nline two\" contains \"line two\"", wrapped);

        String jdbc = ", ensure " + JDBC + " contains \"no_such_table_x\"";
        String jdbcWrapped = LineData.protectMultilineComparisonOperands(jdbc);
        assertEquals(
                ", ensure \"" + JDBC.replace("\"", "\\\"") + "\" contains \"no_such_table_x\"",
                jdbcWrapped);
        QuoteParser parsed = new QuoteParser(jdbcWrapped);
        assertEquals(JDBC, parsed.values().iterator().next());

        String crlf = ", ensure " + JDBC_CRLF + " starts with \"relation\"";
        String crlfWrapped = LineData.protectMultilineComparisonOperands(crlf);
        assertTrue(crlfWrapped.startsWith(", ensure \""), crlfWrapped);
        assertEquals(JDBC_CRLF, new QuoteParser(crlfWrapped).values().iterator().next());

        String already = ", ensure \"" + LINES + "\" equals \"" + LINES + "\"";
        assertEquals(already, LineData.protectMultilineComparisonOperands(already));
        String negated = ", ensure \"" + LINES + "\" does not contain \"missing\"";
        assertEquals(negated, LineData.protectMultilineComparisonOperands(negated));
        assertEquals(
                ", ensure \"hello   world\" equals \"hello world\"",
                LineData.protectMultilineComparisonOperands(", ensure \"hello   world\" equals \"hello world\""));
        assertEquals(
                "INFO: " + LINES,
                LineData.protectMultilineComparisonOperands("INFO: " + LINES));
        assertEquals(
                "\"" + WINDOWS_PATH + "\" contains \"She'll\"",
                LineData.protectMultilineComparisonOperands("\"" + WINDOWS_PATH + "\" contains \"She'll\""));
    }
}
