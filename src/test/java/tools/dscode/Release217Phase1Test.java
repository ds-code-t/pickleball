package tools.dscode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapperComparisons;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.BusinessTime;
import tools.dscode.common.util.datetime.TemporalValue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

class Release217Phase1Test {

    private static final ZoneId PHOENIX = ZoneId.of("America/Phoenix");

    @BeforeEach
    void resetMaps() {
        MappingProcessor.resetCommonMaps();
    }

    @Test
    void invalidExpressionsInLogTextStayLiteral() {
        ParsingMap map = new ParsingMap();
        map.put("who", "Ada");

        assertEquals("INFO: hi Ada 2", map.resolveWholeText("INFO: hi <who> <{ 1 + 1 }>"));
        assertEquals("INFO: bad <{ 1 + }> ok", map.resolveWholeText("INFO: bad <{ 1 + }> ok"));
        assertEquals("TRACE: <{ 1 + }>", map.resolveWholeText("TRACE: <{ 1 + }>"));
        assertEquals("DEBUG: <{ 1 + }>", map.resolveWholeText("DEBUG: <{ 1 + }>"));
        assertEquals("WARN: <{ 1 + }>", map.resolveWholeText("WARN: <{ 1 + }>"));
        assertEquals("ERROR: <{ 1 + }>", map.resolveWholeText("ERROR: <{ 1 + }>"));
        assertEquals("FAIL: <{ 1 + }>", map.resolveWholeText("FAIL: <{ 1 + }>"));
        assertEquals(
                GlobalConstants.INFO_STEP + "x <{ 1 + }>",
                map.resolveWholeText(GlobalConstants.INFO_STEP + "x <{ 1 + }>"));
        assertEquals("2", map.resolveWholeText("<{ 1 + 1 }>"));
        assertEquals("Ada", map.resolveWholeText("<who>"));
        assertThrows(RuntimeException.class, () -> map.resolveWholeText("THEN: <{ 1 + }>"));
        assertThrows(RuntimeException.class, () -> map.resolveWholeText("<{ 1 + }>"));
    }

    @Test
    void nestedReferencesStayStableThroughFourLevels() {
        ParsingMap map = new ParsingMap();
        map.put("a", "VALUE");
        map.put("b", "<a>");
        map.put("c", "<b>");
        map.put("d", "<c>");

        assertEquals("VALUE", map.resolveWholeText("<a>"));
        assertEquals("VALUE", map.resolveWholeText("<b>"));
        assertEquals("VALUE", map.resolveWholeText("<c>"));
        assertEquals("VALUE", map.resolveWholeText("<d>"));

        map.put("gt", "left>right");
        map.put("mid", "<gt>");
        map.put("outer", "<mid>");
        assertEquals("left>right", map.resolveWholeText("<gt>"));
        assertEquals("left>right", map.resolveWholeText("<mid>"));
        assertEquals("left>right", map.resolveWholeText("<outer>"));
        assertEquals("pre left>right post", map.resolveWholeText("pre <outer> post"));

        assertEquals("2", map.resolveWholeText("<{ 1 + 1 }>"));
        assertEquals("3", map.resolveWholeText("<{ <{ 1 + 1 }> + 1 }>"));
        assertEquals("4", map.resolveWholeText("<{ <{ <{ 1 + 1 }> + 1 }> + 1 }>"));
    }

    @Test
    void stringEqualityIgnoresMarginUntilBothSidesAreDateTimes() {
        ValueWrapper alpha = createValueWrapper("\"alpha\"");
        ValueWrapper same = createValueWrapper("\"alpha\"");
        ValueWrapper beta = createValueWrapper("\"beta\"");
        ValueWrapper minutes = createValueWrapper("\"5 minutes\"");
        ValueWrapper numeric = createValueWrapper("5");

        assertTrue(ValueWrapperComparisons.equals(alpha, same));
        assertTrue(ValueWrapperComparisons.equals(alpha, same, minutes));
        assertTrue(ValueWrapperComparisons.equals(alpha, same, numeric));
        assertFalse(ValueWrapperComparisons.equals(alpha, beta));
        assertFalse(ValueWrapperComparisons.equals(alpha, beta, minutes));
        assertFalse(ValueWrapperComparisons.equals(alpha, beta, numeric));
    }

    @Test
    void withinMarginWordingBecomesMarginOf() {
        String marginOf = DefinitionContext.preParseDynamicStepString(
                "\"A\" is equal to \"B\" margin of 5 minutes");
        String withinMargin = DefinitionContext.preParseDynamicStepString(
                "\"A\" is equal to \"B\" within a margin of 5 minutes");
        String withinMinutes = DefinitionContext.preParseDynamicStepString(
                "\"A\" is equal to \"B\" within 5 minutes");

        assertTrue(marginOf.contains("margin-of"));
        assertTrue(marginOf.contains("minutes"));
        assertFalse(marginOf.toLowerCase().contains("within"));
        assertTrue(withinMargin.contains("margin-of"));
        assertFalse(withinMargin.toLowerCase().contains("within"));
        assertTrue(withinMinutes.contains("margin-of"));
        assertTrue(withinMinutes.contains("5"));
        assertTrue(withinMinutes.contains("minutes"));
        assertFalse(withinMinutes.toLowerCase().contains("within"));
    }

    @Test
    void calendarParsesItsOwnDefaultOutputAfterExistingFormats() {
        BusinessCalendar calendar = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "DefaultOutputPattern": "MMMM d, yyyy h:mm a VV",
                  "DefaultOutputZone": "America/New_York",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        BusinessTime parsedIso = calendar.of("2026-07-20T23:45:00Z");
        String rendered = parsedIso.toString();
        BusinessTime roundTrip = calendar.of(rendered);

        assertEquals("July 20, 2026 7:45 PM America/New_York", rendered);
        assertEquals(parsedIso.value().toInstant(), roundTrip.value().toInstant());
        assertEquals(
                ZonedDateTime.parse("2026-07-20T23:45:00Z").toInstant(),
                calendar.of("2026-07-20T23:45:00Z").value().toInstant());
    }

    @Test
    void unzonedInputStaysUtcUnlessTheCalendarOptsIn() {
        BusinessCalendar utc = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "UnzonedInputZone": "UTC",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        BusinessCalendar local = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "UnzonedInputZone": "calendar",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        BusinessCalendar omitted = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);

        assertFalse(utc.unzonedInputInCalendarZone());
        assertFalse(omitted.unzonedInputInCalendarZone());
        assertTrue(local.unzonedInputInCalendarZone());
        assertEquals(LocalDate.of(2026, 7, 19), utc.of("2026-07-20T00:00:00").value().toLocalDate());
        assertEquals(20, utc.of("2026-07-20T00:00:00").value().getHour());
        assertEquals(omitted.of("2026-07-20T00:00:00").value().toInstant(),
                utc.of("2026-07-20T00:00:00").value().toInstant());
        assertEquals(LocalDate.of(2026, 7, 20), local.of("2026-07-20T00:00:00").value().toLocalDate());
        assertEquals(0, local.of("2026-07-20T00:00:00").value().getHour());
        assertEquals(utc.of("2026-07-20T00:00:00Z").value().toInstant(),
                local.of("2026-07-20T00:00:00Z").value().toInstant());
        assertThrows(IllegalArgumentException.class, () -> BusinessCalendar.fromJson("""
                { "TimeZone": "UTC", "UnzonedInputZone": "Mars" }
                """));
    }

    @Test
    void weekendRollsForwardWithoutChangingBusinessDayMath() {
        BusinessCalendar calendar = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/Phoenix",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        ZonedDateTime friday = ZonedDateTime.of(2026, 7, 17, 15, 0, 0, 0, PHOENIX);
        ZonedDateTime saturday = friday.plusDays(1);
        ZonedDateTime monday = friday.plusDays(3);

        assertEquals(friday.toInstant(), evaluated(calendar, friday, "| business day on or after").toInstant());
        assertEquals(monday.toInstant(), evaluated(calendar, saturday, "| business day on or after").toInstant());
        assertEquals(monday.toInstant(), evaluated(calendar, friday, "| next business day").toInstant());
        assertEquals(monday.plusDays(1).toInstant(), evaluated(calendar, monday, "| next business day").toInstant());
        assertEquals(
                monday.toInstant(),
                TemporalValue.dateTime(calendar, "2026-07-17T15:00:00-07:00 + 1 business day")
                        .requireBusinessTime().value().toInstant());
        assertEquals(
                ZonedDateTime.of(2026, 7, 20, 9, 0, 0, 0, PHOENIX).toInstant(),
                evaluated(calendar, saturday, "| next open").toInstant());
        assertThrows(IllegalArgumentException.class,
                () -> calendar.of("2026-07-17T15:00:00-07:00 | next open day"));
    }

    private static ZonedDateTime evaluated(BusinessCalendar calendar, ZonedDateTime start, String modifier) {
        TemporalValue value = TemporalValue.dateTime(calendar, start + " " + modifier);
        return value.requireBusinessTime().value();
    }
}
