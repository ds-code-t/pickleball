package tools.dscode.common.util.datetime;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessTemporalDeltaTest {

    private static final ZoneId PHOENIX = ZoneId.of("America/Phoenix");

    @Test
    void businessDaysCountBusinessDatesAndPreserveTimeOfDay() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2025, 12, 18, 19, 11, 2)
                .add("+ 3 Business Days");

        assertEquals(zdt(2025, 12, 23, 19, 11, 2), result.value());
    }

    @Test
    void businessDaysSkipClosedDateOverrides() {
        BusinessCalendar calendar = weekdayCalendar("22 DEC 2025");

        BusinessTime result = at(calendar, 2025, 12, 18, 19, 11, 2)
                .add("+ 3 business days");

        assertEquals(zdt(2025, 12, 24, 19, 11, 2), result.value());
    }

    @Test
    void negativeBusinessDaysMoveBackwardToPriorBusinessDate() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2025, 12, 22, 10, 0, 0)
                .add("- 1 business day");

        assertEquals(zdt(2025, 12, 19, 10, 0, 0), result.value());
    }

    @Test
    void businessMonthsRollToNextBusinessDateAndPreserveTimeOfDay() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2026, 1, 30, 10, 0, 0)
                .add("+ 1 business month");

        assertEquals(zdt(2026, 3, 2, 10, 0, 0), result.value());
    }

    @Test
    void businessHoursUseOpenElapsedTime() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2025, 12, 18, 16, 0, 0)
                .add("+ 2 business hours");

        assertEquals(zdt(2025, 12, 19, 10, 0, 0), result.value());
    }

    @Test
    void businessSecondsUseOpenElapsedTime() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2025, 12, 18, 16, 59, 59)
                .add("+ 2 business seconds");

        assertEquals(zdt(2025, 12, 19, 9, 0, 1), result.value());
    }

    @Test
    void mixedBusinessAndRegularTermsPreserveOrder() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2025, 12, 18, 19, 11, 2)
                .add("+ 1 day + 1 business day");

        assertEquals(zdt(2025, 12, 22, 19, 11, 2), result.value());
    }

    @Test
    void fractionalBusinessTimeUnitsAreAllowed() {
        BusinessCalendar calendar = weekdayCalendar();

        BusinessTime result = at(calendar, 2025, 12, 18, 16, 0, 0)
                .add("+ .5 business hours");

        assertEquals(zdt(2025, 12, 18, 16, 30, 0), result.value());
    }

    @Test
    void fractionalBusinessDateUnitsAreRejected() {
        BusinessCalendar calendar = weekdayCalendar();
        BusinessTime start = at(calendar, 2025, 12, 18, 19, 11, 2);

        assertThrows(IllegalArgumentException.class, () -> start.add("+ 1.5 business days"));
    }

    private static BusinessCalendar weekdayCalendar(String... closed) {
        String closedJson = Arrays.stream(closed)
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(","));

        return BusinessCalendar.fromJson("""
                {
                  "TimeZone": "America/Phoenix",
                  "Open": ["MON-FRI 09:00-17:00"],
                  "Closed": [%s]
                }
                """.formatted(closedJson));
    }

    private static BusinessTime at(
            BusinessCalendar calendar,
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second
    ) {
        return new BusinessTime(calendar, zdt(year, month, day, hour, minute, second));
    }

    private static ZonedDateTime zdt(int year, int month, int day, int hour, int minute, int second) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0, PHOENIX);
    }
}
