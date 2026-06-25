package tools.dscode.common.util.datetime;

import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessTimePostModifierTest {

    private static final ZoneId PHOENIX = ZoneId.of("America/Phoenix");

    @Test
    void stateSeekersReturnCurrentTimeWhenAlreadyInRequestedState() {
        BusinessCalendar calendar = weekdayCalendar();

        assertEquals(zdt(2025, 12, 18, 10, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| next open").value());
        assertEquals(zdt(2025, 12, 18, 10, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| previous open").value());
        assertEquals(zdt(2025, 12, 18, 18, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 18, 0, 0), "| next closed").value());
        assertEquals(zdt(2025, 12, 18, 18, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 18, 0, 0), "| previous closed").value());
    }

    @Test
    void stateSeekersMoveToClosestRequestedState() {
        BusinessCalendar calendar = weekdayCalendar();

        assertEquals(zdt(2025, 12, 19, 9, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 18, 30, 0), "| next open").value());
        assertEquals(zdt(2025, 12, 19, 16, 59, 59, 999_999_999), businessTime(calendar, zdt(2025, 12, 22, 8, 0, 0), "| previous open").value());
        assertEquals(zdt(2025, 12, 18, 17, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| next closed").value());
        assertEquals(zdt(2025, 12, 18, 8, 59, 59, 999_999_999), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| previous closed").value());
    }

    @Test
    void transitionBoundariesFindOpenAndCloseTransitions() {
        BusinessCalendar calendar = weekdayCalendar();

        assertEquals(zdt(2025, 12, 19, 9, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| next opening").value());
        assertEquals(zdt(2025, 12, 18, 9, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| previous opening").value());
        assertEquals(zdt(2025, 12, 19, 17, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 18, 0, 0), "| next closing").value());
        assertEquals(zdt(2025, 12, 17, 17, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 10, 0, 0), "| previous closing").value());
    }

    @Test
    void transitionBoundariesReturnCurrentTimeWhenExactlyOnBoundary() {
        BusinessCalendar calendar = weekdayCalendar();

        assertEquals(zdt(2025, 12, 18, 9, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 9, 0, 0), "| next opening").value());
        assertEquals(zdt(2025, 12, 18, 17, 0, 0), businessTime(calendar, zdt(2025, 12, 18, 17, 0, 0), "| previous closing").value());
    }

    @Test
    void dailyBoundariesReturnFirstOpeningAndLastClosingForLocalDate() {
        BusinessCalendar calendar = BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/Phoenix",
                  "Open": ["MON 22:00-02:00", "TUE 09:00-11:00"]
                }
                """);

        assertEquals(zdt(2025, 12, 15, 22, 0, 0), businessTime(calendar, zdt(2025, 12, 15, 10, 0, 0), "| opening time").value());
        assertEquals(zdt(2025, 12, 16, 2, 0, 0), businessTime(calendar, zdt(2025, 12, 15, 10, 0, 0), "| closing time").value());

        assertEquals(zdt(2025, 12, 15, 22, 0, 0), businessTime(calendar, zdt(2025, 12, 16, 1, 0, 0), "| opening time").value());
        assertEquals(zdt(2025, 12, 16, 11, 0, 0), businessTime(calendar, zdt(2025, 12, 16, 1, 0, 0), "| closing time").value());
    }

    @Test
    void dailyBoundariesReturnNullForDatesWithNoOpenIntervals() {
        BusinessCalendar calendar = weekdayCalendar();

        assertNull(BusinessTime.evaluate(calendar, spec(zdt(2025, 12, 20, 10, 0, 0)) + " | opening time"));
        assertEquals(TemporalValue.Kind.NULL, TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 20, 10, 0, 0)) + " | closing time").kind());
    }

    @Test
    void scalarQueriesReturnTextAndBooleans() {
        BusinessCalendar calendar = weekdayCalendar();

        TemporalValue openStatus = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 10, 0, 0)) + " | status");
        TemporalValue closedStatus = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 18, 0, 0)) + " | status");
        TemporalValue isOpen = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 10, 0, 0)) + " | is open");
        TemporalValue isClosed = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 18, 0, 0)) + " | is closed");
        TemporalValue isBusinessDay = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 18, 0, 0)) + " | is business day");
        TemporalValue isNonBusinessDay = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 20, 18, 0, 0)) + " | is non-business day");

        assertEquals("open", openStatus.requireText());
        assertEquals("closed", closedStatus.requireText());
        assertEquals(true, isOpen.requireBoolean());
        assertEquals(true, isClosed.requireBoolean());
        assertEquals(true, isBusinessDay.requireBoolean());
        assertEquals(true, isNonBusinessDay.requireBoolean());
        assertEquals(ValueWrapper.ValueTypes.BOOLEAN, ValueWrapper.createValueWrapper(isNonBusinessDay).type);
    }

    @Test
    void businessTimeEvaluateRejectsScalarPipeResults() {
        BusinessCalendar calendar = weekdayCalendar();

        assertThrows(IllegalArgumentException.class, () -> BusinessTime.evaluate(calendar, spec(zdt(2025, 12, 18, 10, 0, 0)) + " | status"));
    }

    @Test
    void scalarPipeResultsCannotBeChainedFurther() {
        BusinessCalendar calendar = weekdayCalendar();

        assertThrows(IllegalArgumentException.class, () -> TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 10, 0, 0)) + " | status | next open"));
    }

    @Test
    void dateReturningPipeModifiersCanBeChained() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = BusinessTime.evaluate(calendar, spec(zdt(2025, 12, 18, 18, 0, 0)) + " | next open | next closed | previous opening");

        assertEquals(zdt(2025, 12, 19, 9, 0, 0), result.value());
    }

    @Test
    void pipeModifiersWorkAfterBusinessDeltasAndPreserveOutputFormatting() {
        BusinessCalendar calendar = weekdayCalendar();

        TemporalValue status = TemporalValue.dateTime(calendar, spec(zdt(2025, 12, 18, 16, 0, 0)) + " + 2 business hours | status");
        BusinessTime formatted = BusinessTime.evaluate(calendar, spec(zdt(2025, 12, 18, 18, 0, 0)) + " format: yyyy-MM-dd HH:mm | next open");

        assertEquals("open", status.requireText());
        assertEquals("2025-12-19 09:00", formatted.toString());
    }

    private static BusinessCalendar weekdayCalendar() {
        return BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/Phoenix",
                  "Open": ["MON-FRI 09:00-17:00"]
                }
                """);
    }

    private static BusinessTime businessTime(BusinessCalendar calendar, ZonedDateTime start, String modifier) {
        return BusinessTime.evaluate(calendar, spec(start) + " " + modifier);
    }

    private static String spec(ZonedDateTime zdt) {
        return zdt.toString();
    }

    private static ZonedDateTime zdt(int year, int month, int day, int hour, int minute, int second) {
        return zdt(year, month, day, hour, minute, second, 0);
    }

    private static ZonedDateTime zdt(int year, int month, int day, int hour, int minute, int second, int nano) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, nano, PHOENIX);
    }
}
