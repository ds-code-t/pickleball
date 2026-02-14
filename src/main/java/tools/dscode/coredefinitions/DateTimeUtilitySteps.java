package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.CalendarRegistry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.*;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.util.datetime.CalendarRegistry.getCalendar;


public class DateTimeUtilitySteps {


    @Given("^DateTime:(?:(?i:Calendar:)(.*))?(.*)")
    public static String dateTime(String calendar, String dateTimeString) {
        BusinessCalendar bc = (calendar == null || calendar.isBlank()) ? getCalendar():  CalendarRegistry.get(calendar.trim());
        return bc.eval(dateTimeString);
    }

    // -----------------------------
    // "Now / today / tomorrow / yesterday" (no-arg)
    // Default Java-ish formats: ISO-8601
    // -----------------------------

    @Given("^now$")
    public static String now() {
        String out = Instant.now().toString(); // e.g. 2026-02-11T21:34:12.123Z
        System.out.println("now -> " + out);
        return out;
    }

    @Given("^today$")
    public static String today() {
        String out = LocalDate.now(ZoneId.systemDefault()).toString(); // yyyy-MM-dd
        System.out.println("today -> " + out);
        return out;
    }

    @Given("^tomorrow$")
    public static String tomorrow() {
        String out = LocalDate.now(ZoneId.systemDefault()).plusDays(1).toString();
        System.out.println("tomorrow -> " + out);
        return out;
    }

    @Given("^yesterday$")
    public static String yesterday() {
        String out = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString();
        System.out.println("yesterday -> " + out);
        return out;
    }

    // -----------------------------
    // Overloads with explicit format patterns (DateTimeFormatter)
    // -----------------------------

    @Given("^now:(.*)$")
    public static String nowFormatted(String pattern) {
        System.out.println("now formatted pattern=" + pattern);
        DateTimeFormatter fmt = formatter(pattern);
        String out = ZonedDateTime.now(ZoneId.systemDefault()).format(fmt);
        return out;
    }

    @Given("^today:(.*)$")
    public static String todayFormatted(String pattern) {
        System.out.println("today formatted pattern=" + pattern);
        DateTimeFormatter fmt = formatter(pattern);
        String out = LocalDate.now(ZoneId.systemDefault()).format(fmt);
        return out;
    }

    @Given("^tomorrow:(.*)$")
    public static String tomorrowFormatted(String pattern) {
        System.out.println("tomorrow formatted pattern=" + pattern);
        DateTimeFormatter fmt = formatter(pattern);
        String out = LocalDate.now(ZoneId.systemDefault()).plusDays(1).format(fmt);
        return out;
    }

    @Given("^yesterday:(.*)$")
    public static String yesterdayFormatted(String pattern) {
        System.out.println("yesterday formatted pattern=" + pattern);
        DateTimeFormatter fmt = formatter(pattern);
        String out = LocalDate.now(ZoneId.systemDefault()).minusDays(1).format(fmt);
        return out;
    }

    // Timestamp convenience (epoch millis/seconds) as strings

    @Given("^epochMillis$")
    public static String epochMillis() {
        String out = String.valueOf(Instant.now().toEpochMilli());
        System.out.println("epochMillis -> " + out);
        return out;
    }

    @Given("^epochSeconds$")
    public static String epochSeconds() {
        String out = String.valueOf(Instant.now().getEpochSecond());
        System.out.println("epochSeconds -> " + out);
        return out;
    }

    // -----------------------------
    // Parse + format (convert formats)
    // Example:
    //   reformat:yyyy-MM-dd HH:mm:ss;MM/dd/yyyy;2026-02-11 14:03:00
    // -----------------------------

    @Given("^reformat:(.*?);(.*?);(.*)$")
    public static String reformat(String fromPattern, String toPattern, String input) {
        System.out.println("reformat from=" + fromPattern + " to=" + toPattern + " input=" + input);
        DateTimeFormatter from = formatter(fromPattern);
        DateTimeFormatter to = formatter(toPattern);

        // Try parse as date-time first, then date
        try {
            LocalDateTime ldt = LocalDateTime.parse(input.trim(), from);
            return ldt.format(to);
        } catch (DateTimeParseException ignored) {
        }

        LocalDate ld = LocalDate.parse(input.trim(), from);
        return ld.format(to);
    }

    // -----------------------------
    // Add/Subtract expressions
    //
    // Supported input styles:
    //   shift:<datetime>;<expr>
    //   shiftFmt:<pattern>;<datetime>;<expr>
    //
    // expr examples:
    //   + 2 days - 5 hours
    //   -90 minutes
    //   +1d +2h -30m
    //   + 3 weeks + 1 day
    //
    // Units supported:
    //   years, months, weeks, days, hours, minutes, seconds, millis
    //   plus short forms: y, mo, w, d, h, m, s, ms
    //
    // Behavior:
    //   - If datetime looks like an Instant (ends with Z or has offset), we use Instant/ZonedDateTime.
    //   - Else we parse as LocalDateTime (or LocalDate -> atStartOfDay).
    // -----------------------------

    @Given("^shift:(.*?);(.*)$")
    public static String shiftIso(String dateTime, String expr) {
        System.out.println("shift ISO dt=" + dateTime + " expr=" + expr);
        TemporalWrapper tw = parseBest(dateTime);
        tw = applyExpression(tw, expr);
        return tw.toDefaultString();
    }

    @Given("^shift:(.*?);(.*?);(.*)$")
    public static String shiftIsoWithZone(String zoneId, String dateTime, String expr) {
        // Allows: shift:America/Phoenix;2026-02-11T10:00:00;+2h
        System.out.println("shift ISO zone=" + zoneId + " dt=" + dateTime + " expr=" + expr);
        TemporalWrapper tw = parseBest(dateTime).withZone(zoneId);
        tw = applyExpression(tw, expr);
        return tw.toDefaultString();
    }

    @Given("^shiftFmt:(.*?);(.*?);(.*)$")
    public static String shiftWithPattern(String pattern, String dateTime, String expr) {
        System.out.println("shiftFmt pattern=" + pattern + " dt=" + dateTime + " expr=" + expr);
        DateTimeFormatter fmt = formatter(pattern);

        TemporalWrapper tw;
        String input = dateTime.trim();

        // Try date-time, then date
        try {
            LocalDateTime ldt = LocalDateTime.parse(input, fmt);
            tw = TemporalWrapper.of(ldt, ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            LocalDate ld = LocalDate.parse(input, fmt);
            tw = TemporalWrapper.of(ld.atStartOfDay(), ZoneId.systemDefault());
        }

        tw = applyExpression(tw, expr);
        // Return in SAME pattern by default (often what you want when using shiftFmt)
        return tw.format(fmt);
    }

    @Given("^shiftFmt:(.*?);(.*?);(.*?);(.*)$")
    public static String shiftWithPatternAndZone(String pattern, String zoneId, String dateTime, String expr) {
        System.out.println("shiftFmt pattern=" + pattern + " zone=" + zoneId + " dt=" + dateTime + " expr=" + expr);
        DateTimeFormatter fmt = formatter(pattern);
        ZoneId zone = ZoneId.of(zoneId.trim());

        TemporalWrapper tw;
        String input = dateTime.trim();

        try {
            LocalDateTime ldt = LocalDateTime.parse(input, fmt);
            tw = TemporalWrapper.of(ldt, zone);
        } catch (DateTimeParseException ignored) {
            LocalDate ld = LocalDate.parse(input, fmt);
            tw = TemporalWrapper.of(ld.atStartOfDay(), zone);
        }

        tw = applyExpression(tw, expr);
        return tw.format(fmt);
    }

    // -----------------------------
    // Implementation helpers
    // -----------------------------

    private static DateTimeFormatter formatter(String pattern) {
        // Locale.ROOT keeps things deterministic for month/day names if you ever use them.
        return DateTimeFormatter.ofPattern(pattern.trim(), Locale.ROOT);
    }

    private static final Pattern TOKEN =
            Pattern.compile("([+-])\\s*(\\d+)\\s*([a-zA-Z]+)");

    private static TemporalWrapper applyExpression(TemporalWrapper tw, String expr) {
        String e = expr == null ? "" : expr.trim();
        if (e.isEmpty()) return tw;

        // allow "+1d +2h -30m" by inserting spaces between number+unit pairs if needed
        // (still works for spaced input)
        // We'll just token-scan with regex.
        Matcher m = TOKEN.matcher(e);
        boolean found = false;

        while (m.find()) {
            found = true;
            String sign = m.group(1);
            long amount = Long.parseLong(m.group(2));
            String unitRaw = m.group(3);

            long signed = "+".equals(sign) ? amount : -amount;

            TimeUnit unit = TimeUnit.from(unitRaw);
            System.out.println("  apply " + signed + " " + unit);

            tw = tw.plus(signed, unit);
        }

        if (!found) {
            System.out.println("  (no shift tokens found in expr) " + expr);
        }
        return tw;
    }

    private static TemporalWrapper parseBest(String input) {
        String s = input == null ? "" : input.trim();
        ZoneId zone = ZoneId.systemDefault();

        // 1) Instant (strict): 2026-02-11T21:34:12Z
        try {
            Instant inst = Instant.parse(s);
            return TemporalWrapper.of(inst, zone);
        } catch (DateTimeParseException ignored) {
        }

        // 2) Offset/Zoned: 2026-02-11T14:03:00-07:00 or ...[America/Phoenix]
        try {
            OffsetDateTime odt = OffsetDateTime.parse(s);
            return TemporalWrapper.of(odt.toInstant(), zone);
        } catch (DateTimeParseException ignored) {
        }

        try {
            ZonedDateTime zdt = ZonedDateTime.parse(s);
            return TemporalWrapper.of(zdt.toInstant(), zone);
        } catch (DateTimeParseException ignored) {
        }

        // 3) LocalDateTime: 2026-02-11T14:03:00
        try {
            LocalDateTime ldt = LocalDateTime.parse(s);
            return TemporalWrapper.of(ldt, zone);
        } catch (DateTimeParseException ignored) {
        }

        // 4) LocalDate: 2026-02-11
        LocalDate ld = LocalDate.parse(s);
        return TemporalWrapper.of(ld.atStartOfDay(), zone);
    }

    // -----------------------------
    // Internal representation that can behave like:
    //  - "instant-based" (good for hours/min/sec with time zones)
    //  - "local" (good for date-only / local datetime)
    // -----------------------------

    private static final class TemporalWrapper {
        private final ZoneId zone;
        private final Instant instant;          // if non-null, we treat it as instant-based
        private final LocalDateTime localDateTime; // if instant is null, use local

        private TemporalWrapper(ZoneId zone, Instant instant, LocalDateTime localDateTime) {
            this.zone = zone;
            this.instant = instant;
            this.localDateTime = localDateTime;
        }

        static TemporalWrapper of(Instant instant, ZoneId zone) {
            return new TemporalWrapper(zone, instant, null);
        }

        static TemporalWrapper of(LocalDateTime ldt, ZoneId zone) {
            return new TemporalWrapper(zone, null, ldt);
        }

        TemporalWrapper withZone(String zoneId) {
            ZoneId z = ZoneId.of(zoneId.trim());
            if (instant != null) return of(instant, z);
            return of(localDateTime, z);
        }

        TemporalWrapper plus(long amount, TimeUnit unit) {
            if (instant != null) {
                // for months/years, do it via zoned time (calendar-aware)
                if (unit.isCalendarUnit()) {
                    ZonedDateTime zdt = instant.atZone(zone).plus(amount, unit.chronoUnit);
                    return of(zdt.toInstant(), zone);
                }
                Instant out = instant.plus(amount, unit.durationUnit);
                return of(out, zone);
            } else {
                // local datetime
                return of(localDateTime.plus(amount, unit.chronoUnit), zone);
            }
        }

        String toDefaultString() {
            if (instant != null) {
                // Default back to Instant ISO string, easy for Java to parse.
                return instant.toString();
            }
            // LocalDateTime ISO string
            return localDateTime.toString();
        }

        String format(DateTimeFormatter fmt) {
            if (instant != null) {
                return instant.atZone(zone).format(fmt);
            }
            return localDateTime.format(fmt);
        }
    }

    private enum TimeUnit {
        YEARS("years", "year", "y", ChronoUnit.YEARS, true),
        MONTHS("months", "month", "mo", ChronoUnit.MONTHS, true),
        WEEKS("weeks", "week", "w", ChronoUnit.WEEKS, true),
        DAYS("days", "day", "d", ChronoUnit.DAYS, true),
        HOURS("hours", "hour", "h", ChronoUnit.HOURS, false),
        MINUTES("minutes", "minute", "mins", "min", "m", ChronoUnit.MINUTES, false),
        SECONDS("seconds", "second", "secs", "sec", "s", ChronoUnit.SECONDS, false),
        MILLIS("millis", "ms", ChronoUnit.MILLIS, false);

        final ChronoUnit chronoUnit;
        final boolean calendarUnit;
        final java.time.temporal.TemporalUnit durationUnit; // for instant.plus for non-calendar units
        final String[] aliases;

        TimeUnit(String a1, String a2, String a3, ChronoUnit chronoUnit, boolean calendarUnit) {
            this.aliases = new String[]{a1, a2, a3};
            this.chronoUnit = chronoUnit;
            this.calendarUnit = calendarUnit;
            this.durationUnit = chronoUnit; // works for HOURS/MINUTES/SECONDS/MILLIS
        }

        TimeUnit(String a1, String a2, String a3, String a4, String a5, ChronoUnit chronoUnit, boolean calendarUnit) {
            this.aliases = new String[]{a1, a2, a3, a4, a5};
            this.chronoUnit = chronoUnit;
            this.calendarUnit = calendarUnit;
            this.durationUnit = chronoUnit;
        }

        TimeUnit(String a1, String a2, ChronoUnit chronoUnit, boolean calendarUnit) {
            this.aliases = new String[]{a1, a2};
            this.chronoUnit = chronoUnit;
            this.calendarUnit = calendarUnit;
            this.durationUnit = chronoUnit;
        }

        boolean isCalendarUnit() {
            return calendarUnit;
        }

        static TimeUnit from(String unitRaw) {
            String u = unitRaw.trim().toLowerCase(Locale.ROOT);

            for (TimeUnit tu : values()) {
                for (String a : tu.aliases) {
                    if (u.equals(a)) return tu;
                }
            }

            // common singular/plural fallback
            if (u.endsWith("s")) u = u.substring(0, u.length() - 1);
            for (TimeUnit tu : values()) {
                for (String a : tu.aliases) {
                    if (u.equals(a)) return tu;
                }
            }

            throw new IllegalArgumentException("Unsupported time unit: " + unitRaw);
        }
    }


}
