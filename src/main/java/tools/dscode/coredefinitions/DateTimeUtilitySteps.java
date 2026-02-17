package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.CalendarRegistry;

import static tools.dscode.common.util.datetime.CalendarRegistry.getCalendar;

public class DateTimeUtilitySteps {

    // General form (already exists)
    @Given("^DateTime:(?:(?i:Calendar:)(\\S+)\\s+)?(.+)$")
    public static String dateTime(String calendar, String dateTimeString) {
        BusinessCalendar bc = (calendar == null || calendar.isBlank())
                ? getCalendar()
                : CalendarRegistry.get(calendar.trim());
        return bc.eval(dateTimeString);
    }

    // ------------------------------------------------------------
    // Convenience shortcuts (NO "DateTime:" prefix)
    // These forward to dateTime(...) and allow trailing text.
    // ------------------------------------------------------------

    // today [<anything...>]
    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:today)(?:\\s+(.+))?$")
    public static String todayShortcut(String calendar, String rest) {
        String spec = (rest == null || rest.isBlank()) ? "today" : "today " + rest.trim();
        return dateTime(calendar, spec);
    }

    // tomorrow [<anything...>]
    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:tomorrow)(?:\\s+(.+))?$")
    public static String tomorrowShortcut(String calendar, String rest) {
        String spec = (rest == null || rest.isBlank()) ? "tomorrow" : "tomorrow " + rest.trim();
        return dateTime(calendar, spec);
    }

    // yesterday [<anything...>]
    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:yesterday)(?:\\s+(.+))?$")
    public static String yesterdayShortcut(String calendar, String rest) {
        String spec = (rest == null || rest.isBlank()) ? "yesterday" : "yesterday " + rest.trim();
        return dateTime(calendar, spec);
    }

    // now [<anything...>]
    @Given("^(?:(?i:Calendar:)(\\S+)\\s+)?(?i:now)(?:\\s+(.+))?$")
    public static String nowShortcut(String calendar, String rest) {
        String spec = (rest == null || rest.isBlank()) ? "now" : "now " + rest.trim();
        return dateTime(calendar, spec);
    }
}
