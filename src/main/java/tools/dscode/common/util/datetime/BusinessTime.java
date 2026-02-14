package tools.dscode.common.util.datetime;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact fluent wrapper around BusinessCalendar.
 *
 * Adds non-breaking "presentation state":
 *  - output format pattern
 *  - output zone
 *
 * These do NOT change the underlying stored zdt; they only affect render/toString().
 */
public final class BusinessTime {

    private final BusinessCalendar cal;
    private final ZonedDateTime zdt;

    // Presentation-only state (nullable; does not change underlying time)
    private final ZoneId outputZone;              // display zone
    private final DateTimeFormatter outputFormat; // display format

    // ---- constructors ----

    BusinessTime(BusinessCalendar cal) {
        this(cal, ZonedDateTime.now(cal.zone()));
    }

    BusinessTime(BusinessCalendar cal, ZonedDateTime zdt) {
        this(cal, zdt, null, null);
    }

    private BusinessTime(BusinessCalendar cal, ZonedDateTime zdt, ZoneId outputZone, DateTimeFormatter outputFormat) {
        this.cal = Objects.requireNonNull(cal, "cal");
        this.zdt = Objects.requireNonNull(zdt, "zdt");
        this.outputZone = outputZone;
        this.outputFormat = outputFormat;
    }

    // ---- Calendar binding ----





    public static BusinessTime of(BusinessCalendar cal, String dateTime) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(dateTime, "dateTime");
        ZonedDateTime z;
        String s = dateTime.trim();

        if (looksLikeIsoZoned(s)) {
            z = ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                    .withZoneSameInstant(cal.zone());
        } else {
            z = cal.toCalendarZoneAssumeGmtIfMissing(s);
        }

        BusinessTime bt = new BusinessTime(cal, z);

        // Apply calendar default presentation (if defined)
        if (cal.defaultOutputPattern().isPresent()) {
            String p = cal.defaultOutputPattern().get();
            ZoneId oz = cal.defaultOutputZone().orElse(cal.zone());
            bt = bt.asZone(oz).asPattern(p);
        }

        return bt;
    }



    // ---- Output & identity ----

    public BusinessCalendar calendar() { return cal; }

    /**
     * Underlying stored time (in calendar zone per your construction rules).
     * This is NOT affected by asZone()/asPattern().
     */
    public ZonedDateTime value() { return zdt; }

    /**
     * Convenience explicit render (same as toString()).
     */
    public String render() { return toString(); }

    /**
     * Legacy formatting helper (kept). This formats the underlying zdt in its own zone.
     * If you want display formatting with outputZone/outputFormat, use render()/toString().
     */
    public String format(String pattern) {
        return zdt.format(DateTimeFormatter.ofPattern(pattern));
    }

    // ---- Presentation-only fluent state ----

    /** Sets a display format pattern used by toString()/render(). Does not change underlying zdt. */
    public BusinessTime asPattern(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
        return new BusinessTime(cal, zdt, outputZone, fmt);
    }

    /** Sets a display time zone used by toString()/render(). Does not change underlying zdt. */
    public BusinessTime asZone(String zoneId) {
        Objects.requireNonNull(zoneId, "zoneId");
        return asZone(ZoneId.of(zoneId));
    }

    /** Sets a display time zone used by toString()/render(). Does not change underlying zdt. */
    public BusinessTime asZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return new BusinessTime(cal, zdt, zone, outputFormat);
    }

    /** Clears display overrides (back to default zdt.toString()). */
    public BusinessTime resetOutput() {
        return new BusinessTime(cal, zdt, null, null);
    }

    /** Exposes current display zone (nullable). */
    public ZoneId outputZone() { return outputZone; }

    /** Exposes current display formatter (nullable). */
    public DateTimeFormatter outputFormat() { return outputFormat; }

    // ---- Calendar queries ----

    public BusinessCalendar.Status status() { return cal.status(zdt); }
    public boolean isOpen() { return cal.isOpen(zdt); }
    public boolean isClosed() { return cal.isClosed(zdt); }

    public BusinessTime nextOpen() {
        ZonedDateTime n = cal.nextOpen(zdt);
        return n == null ? null : new BusinessTime(cal, n, outputZone, outputFormat);
    }

    public BusinessTime lastOpen() {
        ZonedDateTime p = cal.lastOpen(zdt);
        return p == null ? null : new BusinessTime(cal, p, outputZone, outputFormat);
    }

    // ---- Fluent operations ----

    /** General time math (supports months/years). */
    public BusinessTime add(String expr) {
        ZonedDateTime cur = zdt;
        for (Delta d : Delta.parse(expr)) {
            cur = cur.plus(d.amount, d.unit);
        }
        return new BusinessTime(cal, cur, outputZone, outputFormat);
    }

    /** Open/business time math (duration-based; rejects months/years). Negative values allowed. */
    public BusinessTime addOpen(String expr) {
        Duration dur = Delta.toDuration(expr); // negative OK
        ZonedDateTime out = cal.addOpenDuration(zdt, dur);
        return out == null ? null : new BusinessTime(cal, out, outputZone, outputFormat);
    }

    /** Duration between this and another time (other - this). */
    public Duration durationBetween(String otherDateTime) {
        ZonedDateTime other = of(cal, otherDateTime).zdt;
        return Duration.between(this.zdt, other);
    }


    // ---- Rendering ----

    @Override
    public String toString() {
        ZonedDateTime view = (outputZone == null) ? zdt : zdt.withZoneSameInstant(outputZone);

        if (outputFormat != null) {
            return view.format(outputFormat);
        }
        return view.toString();
    }

    // ---- Delta parsing (split on +/-) ----

    private static final class Delta {
        final long amount;
        final ChronoUnit unit;

        private Delta(long amount, ChronoUnit unit) {
            this.amount = amount;
            this.unit = unit;
        }

        /**
         * Parses expressions like:
         *   "+ 3 days 4 hours - 30 minutes"
         *   "- 4 hours 30 minutes"
         *   "2d -5h"
         *
         * Rules:
         *  - A leading sign is optional; if omitted the default sign is '+'.
         *  - If a chunk omits a sign, it inherits the most recent explicit sign.
         */
        private static final Pattern TOKEN = Pattern.compile("([+-])?\\s*(\\d+)\\s*([A-Za-z]+)");

        static List<Delta> parse(String expr) {
            String s = (expr == null ? "" : expr.trim());
            if (s.isEmpty()) return List.of();

            // If the first token doesn't include an explicit sign, default to '+'
            // (this also makes carry-forward semantics deterministic).
            if (s.charAt(0) != '+' && s.charAt(0) != '-') s = "+ " + s;

            List<Delta> out = new ArrayList<>();

            Matcher m = TOKEN.matcher(s);
            int lastEnd = 0;
            char carrySign = '+'; // most recent explicit sign

            while (m.find()) {
                // Ensure anything between tokens is whitespace only
                if (!s.substring(lastEnd, m.start()).trim().isEmpty()) {
                    throw new IllegalArgumentException("Bad delta chunk: \"" + s.substring(lastEnd, m.start()).trim()
                            + "\" in \"" + expr + "\"");
                }
                lastEnd = m.end();

                String signGroup = m.group(1);
                if (signGroup != null && !signGroup.isBlank()) {
                    carrySign = signGroup.charAt(0);
                }

                long qty = Long.parseLong(m.group(2));
                String unitRaw = m.group(3);

                long signed = (carrySign == '-') ? -qty : qty;
                ChronoUnit unit = normalizeUnit(unitRaw);

                out.add(new Delta(signed, unit));
            }

            // Ensure trailing characters (after last token) are whitespace only
            if (!s.substring(lastEnd).trim().isEmpty()) {
                throw new IllegalArgumentException("Bad delta chunk: \"" + s.substring(lastEnd).trim()
                        + "\" in \"" + expr + "\"");
            }

            if (out.isEmpty()) {
                throw new IllegalArgumentException("Bad delta chunk: \"" + expr + "\" in \"" + expr + "\"");
            }

            return out;
        }

        static Duration toDuration(String expr) {
            long seconds = 0;
            long nanos = 0;

            for (Delta d : parse(expr)) {
                if (d.unit == ChronoUnit.YEARS || d.unit == ChronoUnit.MONTHS) {
                    throw new IllegalArgumentException("Open-time expressions cannot include months/years: \"" + expr + "\"");
                }
                switch (d.unit) {
                    case WEEKS -> seconds += d.amount * 7L * 24L * 3600L;
                    case DAYS -> seconds += d.amount * 24L * 3600L;
                    case HOURS -> seconds += d.amount * 3600L;
                    case MINUTES -> seconds += d.amount * 60L;
                    case SECONDS -> seconds += d.amount;
                    case MILLIS -> nanos += d.amount * 1_000_000L;
                    default -> throw new IllegalArgumentException("Unsupported unit for duration: " + d.unit);
                }
            }
            return Duration.ofSeconds(seconds).plusNanos(nanos);
        }

        private static ChronoUnit normalizeUnit(String raw) {
            String u = raw.toLowerCase(Locale.ROOT).trim();
            if (u.endsWith("s") && u.length() > 1) u = u.substring(0, u.length() - 1);

            return switch (u) {
                case "y", "yr", "year" -> ChronoUnit.YEARS;
                case "mo", "mon", "month" -> ChronoUnit.MONTHS;
                case "w", "week" -> ChronoUnit.WEEKS;
                case "d", "day" -> ChronoUnit.DAYS;
                case "h", "hr", "hour" -> ChronoUnit.HOURS;
                case "m", "min", "minute" -> ChronoUnit.MINUTES;
                case "s", "sec", "second" -> ChronoUnit.SECONDS;
                case "ms", "milli", "millis", "millisecond" -> ChronoUnit.MILLIS;
                default -> throw new IllegalArgumentException("Unknown unit: " + raw);
            };
        }
    }


    private static boolean looksLikeIsoZoned(String s) {
        return s.contains("[") && s.contains("]") && (s.contains("T")) && (s.contains("Z") || s.contains("+") || s.contains("-"));
    }



    public String eval(String spec) {
        Objects.requireNonNull(spec, "spec");

        String raw = spec.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("spec is blank");

        // 1) Extract optional "format: ..."
        String pattern = null;
        int fmtIdx = indexOfIgnoreCase(raw, "format:");
        if (fmtIdx >= 0) {
            pattern = raw.substring(fmtIdx + "format:".length()).trim();
            raw = raw.substring(0, fmtIdx).trim();
            if (pattern.isEmpty()) pattern = null;
        }

        // 2) Extract optional "to <tz> TimeZone"
        String outTz = null;
        {
            String lower = raw.toLowerCase(Locale.ROOT);
            int toIdx = lower.indexOf(" to ");
            if (toIdx >= 0) {
                int tzIdx = lower.indexOf(" timezone", toIdx);
                int tzLen = " timezone".length();

                if (tzIdx < 0) {
                    tzIdx = lower.indexOf(" time zone", toIdx);
                    tzLen = " time zone".length();
                }

                if (tzIdx > toIdx) {
                    outTz = raw.substring(toIdx + 4, tzIdx).trim(); // between "to " and "timezone"
                    String left = raw.substring(0, toIdx).trim();
                    String right = raw.substring(tzIdx + tzLen).trim();
                    raw = (left + " " + right).trim();
                    if (outTz.isEmpty()) outTz = null;
                }
            }
        }

        // 3) Split into baseText + deltaText by locating the FIRST real delta sign.
        //    This avoids breaking on spaces inside explicit date/time strings.
        int deltaIdx = findFirstDeltaStart(raw);
        String baseText = (deltaIdx < 0) ? raw.trim() : raw.substring(0, deltaIdx).trim();
        String deltaText = (deltaIdx < 0) ? "" : raw.substring(deltaIdx).trim();

        if (baseText.isEmpty()) {
            throw new IllegalArgumentException("Missing base time in spec: \"" + spec + "\"");
        }

        // 4) Build starting BusinessTime
        BusinessTime bt;
        switch (baseText.toLowerCase(Locale.ROOT)) {
            case "today" -> bt = cal.today();
            case "tomorrow" -> bt = cal.tomorrow();
            case "yesterday" -> bt = cal.yesterday();
            case "now" -> bt = cal.now();
            default -> bt = cal.of(baseText); // baseText can be full datetime string (with spaces)
        }

        // 5) Apply general time deltas (months/years allowed)
        if (!deltaText.isEmpty()) {
            String normalized = normalizeCarrySignDelta(deltaText);
            if (!normalized.isEmpty()) {
                bt = bt.add(normalized);
            }
        }

        // 6) Apply presentation-only output rules (do NOT change underlying instant)
        if (outTz != null) {
            bt = bt.asZone(parseZoneIdLenient(outTz));
        }
        if (pattern != null) {
            bt = bt.asPattern(pattern);
        }

        return bt.render();
    }

    private static int findFirstDeltaStart(String s) {
        // Find the first '+' or '-' that:
        //  - is preceded by whitespace
        //  - is followed (after optional whitespace) by a digit
        // This avoids treating date hyphens like "2026-02-05" as deltas.
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '+' && ch != '-') continue;

            if (!Character.isWhitespace(s.charAt(i - 1))) continue;

            int j = i + 1;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;

            if (j < s.length() && Character.isDigit(s.charAt(j))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * Converts " + 5 days 3 hours - 10 minutes" into "+ 5 days + 3 hours - 10 minutes".
     * Sign "carries" until changed by another +/-
     */
    private static String normalizeCarrySignDelta(String text) {
        String t = text.trim();
        if (t.isEmpty()) return "";

        // Tokenize: insert spaces around + and -
        t = t.replace("+", " + ").replace("-", " - ");
        List<String> toks = new ArrayList<>();
        for (String p : t.trim().split("\\s+")) {
            if (!p.isBlank()) toks.add(p);
        }

        StringBuilder out = new StringBuilder();
        int sign = +1; // carry sign default (+) unless set

        for (int i = 0; i < toks.size(); ) {
            String tok = toks.get(i);

            if (tok.equals("+")) { sign = +1; i++; continue; }
            if (tok.equals("-")) { sign = -1; i++; continue; }

            // Expect number then unit
            if (!tok.matches("\\d+")) {
                throw new IllegalArgumentException("Expected number in delta expression near: \"" + tok + "\" (full: \"" + text + "\")");
            }
            long qty = Long.parseLong(tok);

            if (i + 1 >= toks.size()) {
                throw new IllegalArgumentException("Missing unit after number in delta expression: \"" + text + "\"");
            }
            String unit = toks.get(i + 1);
            if (!unit.matches("[A-Za-z]+")) {
                throw new IllegalArgumentException("Expected unit after number in delta expression near: \"" + unit + "\" (full: \"" + text + "\")");
            }

            char sgn = (sign < 0) ? '-' : '+';
            out.append(' ').append(sgn).append(' ').append(qty).append(' ').append(unit);

            i += 2;
        }

        return out.toString().trim();
    }

    /**
     * Allows "MST", "PST", "EST", etc via ZoneId.SHORT_IDS, otherwise uses ZoneId.of(...).
     * Also accepts full IANA IDs like "America/Phoenix".
     */
    private static ZoneId parseZoneIdLenient(String zoneText) {
        String z = zoneText.trim();
        if (z.isEmpty()) throw new IllegalArgumentException("Blank output timezone");

        // Try direct ZoneId first (IANA IDs like America/Phoenix)
        try {
            return ZoneId.of(z);
        } catch (Exception ignored) {}

        // Try SHORT_IDS (MST, PST, EST, HST, etc)
        try {
            return ZoneId.of(z.toUpperCase(Locale.ROOT), ZoneId.SHORT_IDS);
        } catch (Exception ignored) {}

        throw new IllegalArgumentException("Unrecognized time zone: \"" + zoneText + "\"");
    }

}
