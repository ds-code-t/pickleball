package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.CalendarRegistry;
import tools.dscode.common.util.datetime.TemporalValue;

import static tools.dscode.common.util.datetime.CalendarRegistry.getCalendar;

public class DateTimeUtilitySteps {

    @Given("^(?i:DateTime:)\\s*(?:(?i:Calendar:)(\\S+)\\s+)?(.+)$")
    public static TemporalValue dateTime(String calendar, String dateTimeString) {
        BusinessCalendar bc = (calendar == null || calendar.isBlank())
                ? getCalendar()
                : CalendarRegistry.get(calendar.trim());
        return bc.eval(dateTimeString);
    }

    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:today)(?:\\s+(.+))?$")
    public static TemporalValue todayShortcut(String calendar, String rest) {
        return dateTime(calendar, append("today", rest));
    }

    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:tomorrow)(?:\\s+(.+))?$")
    public static TemporalValue tomorrowShortcut(String calendar, String rest) {
        return dateTime(calendar, append("tomorrow", rest));
    }

    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:yesterday)(?:\\s+(.+))?$")
    public static TemporalValue yesterdayShortcut(String calendar, String rest) {
        return dateTime(calendar, append("yesterday", rest));
    }

    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:now)(?:\\s+(.+))?$")
    public static TemporalValue nowShortcut(String calendar, String rest) {
        return dateTime(calendar, append("now", rest));
    }

    @Given("^(?i:Duration:)\\s*(.+)$")
    public static TemporalValue duration(String durationString) {
        return TemporalValue.duration(durationString);
    }

    @Given("^(?i:TimeRange:)\\s*(.+)$")
    public static TemporalValue timeRange(String rangeString) {
        return TemporalValue.timeRange(rangeString);
    }

    private static String append(String base, String rest) {
        return (rest == null || rest.isBlank()) ? base : base + " " + rest.trim();
    }
}