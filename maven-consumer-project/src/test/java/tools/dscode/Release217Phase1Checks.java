package tools.dscode;

import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapperComparisons;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.TemporalValue;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

public class Release217Phase1Checks {

    @Test
    void logExpressionsAndNestedReferences() {
        ParsingMap map = new ParsingMap();
        map.put("who", "Ada");
        assertEquals("INFO: hi Ada 2", map.resolveWholeText("INFO: hi <who> <{ 1 + 1 }>"));
        assertEquals("WARN: <{ 1 + }>", map.resolveWholeText("WARN: <{ 1 + }>"));
        assertEquals("2", map.resolveWholeText("<{ 1 + 1 }>"));
        assertThrows(RuntimeException.class, () -> map.resolveWholeText("STEP: <{ 1 + }>"));

        map.put("a", "VALUE");
        map.put("b", "<a>");
        map.put("c", "<b>");
        map.put("d", "<c>");
        assertEquals("VALUE", map.resolveWholeText("<a>"));
        assertEquals("VALUE", map.resolveWholeText("<b>"));
        assertEquals("VALUE", map.resolveWholeText("<c>"));
        assertEquals("VALUE", map.resolveWholeText("<d>"));
    }

    @Test
    void stringDateTimesHonorADurationMargin() {
        ValueWrapper left = createValueWrapper("\"2026-07-20T23:45:00Z\"");
        ValueWrapper near = createValueWrapper("\"2026-07-20T23:49:00Z\"");
        ValueWrapper far = createValueWrapper("\"2026-07-20T23:56:00Z\"");
        ValueWrapper same = createValueWrapper("\"2026-07-20T23:45:00Z\"");
        ValueWrapper minutes = createValueWrapper(Duration.ofMinutes(5));
        ValueWrapper numeric = createValueWrapper("5");
        ValueWrapper alpha = createValueWrapper("\"alpha\"");

        assertTrue(ValueWrapperComparisons.equals(left, same, minutes));
        assertTrue(ValueWrapperComparisons.equals(left, near, minutes));
        assertFalse(ValueWrapperComparisons.equals(left, far, minutes));
        assertTrue(ValueWrapperComparisons.equals(alpha, alpha, minutes));
        assertTrue(ValueWrapperComparisons.equals(left, same, numeric));
        assertFalse(ValueWrapperComparisons.equals(left, near, numeric));
        assertTrue(ValueWrapperComparisons.equals(left, same));
    }

    @Test
    void ownOutputUnzonedZoneAndBusinessDayModifiers() {
        BusinessCalendar ownOutput = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "DefaultOutputPattern": "MMMM d, yyyy h:mm a VV",
                  "DefaultOutputZone": "America/New_York",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        String rendered = ownOutput.of("2026-07-20T23:45:00Z").toString();
        assertEquals("July 20, 2026 7:45 PM America/New_York", rendered);
        assertEquals(
                ownOutput.of("2026-07-20T23:45:00Z").value().toInstant(),
                ownOutput.of(rendered).value().toInstant());

        BusinessCalendar local = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "UnzonedInputZone": "calendar",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        BusinessCalendar utc = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/New_York",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        assertEquals(0, local.of("2026-07-20T00:00:00").value().getHour());
        assertEquals(20, utc.of("2026-07-20T00:00:00").value().getHour());

        BusinessCalendar calendar = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/Phoenix",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
        ZonedDateTime friday = ZonedDateTime.of(2026, 7, 17, 15, 0, 0, 0, calendar.zone());
        assertEquals(
                friday.plusDays(3).toInstant(),
                TemporalValue.dateTime(calendar, friday + " | next business day")
                        .requireBusinessTime().value().toInstant());
        assertEquals(
                friday.plusDays(3).toInstant(),
                TemporalValue.dateTime(calendar, "2026-07-17T15:00:00-07:00 + 1 business day")
                        .requireBusinessTime().value().toInstant());
    }
}
