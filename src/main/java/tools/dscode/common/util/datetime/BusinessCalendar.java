package tools.dscode.common.util.datetime;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static tools.dscode.common.mappings.NodeMap.MAPPER;

/**
 * Immutable + thread-safe (read-only after construction).
 * "Closed" overrides "Open". Outside open hours is closed by default.
 */
public final class BusinessCalendar {

    private static final List<DateTimeFormatter> DEFAULT_DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ISO_LOCAL_TIME
            // , DateTimeFormatter.RFC_1123_DATE_TIME // optional
    );


    public enum Status { OPEN, CLOSED }

    private final ZoneId zone;
    private final List<WeeklyOpenRule> openRules;     // general rules
    private final List<ClosedRule> closedRules;       // exceptions/overrides
    private final List<DateTimeFormatter> dateTimeFormatters;

    private final String defaultOutputPattern;  // nullable
    private final ZoneId defaultOutputZone;     // nullable

    public Optional<String> defaultOutputPattern() {
        return Optional.ofNullable(defaultOutputPattern);
    }

    public Optional<ZoneId> defaultOutputZone() {
        return Optional.ofNullable(defaultOutputZone);
    }
    private BusinessCalendar(
            ZoneId zone,
            List<WeeklyOpenRule> openRules,
            List<ClosedRule> closedRules,
            List<DateTimeFormatter> dateTimeFormatters,
            String defaultOutputPattern,
            ZoneId defaultOutputZone
    ) {
        this.zone = zone;
        this.openRules = List.copyOf(openRules);
        this.closedRules = List.copyOf(closedRules);
        this.dateTimeFormatters = dateTimeFormatters == null ? List.of() : List.copyOf(dateTimeFormatters);

        this.defaultOutputPattern = (defaultOutputPattern == null || defaultOutputPattern.isBlank())
                ? null
                : defaultOutputPattern.trim();

        this.defaultOutputZone = defaultOutputZone;
    }





    public ZoneId zone() { return zone; }

    public static BusinessCalendar fromJson(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return fromJson(n);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid calendar JSON", e);
        }
    }

    public static BusinessCalendar fromJson(JsonNode n) {
        ZoneId zone = ZoneId.of(optText(n, "TimeZone", "UTC"));

        List<String> open = readStringArray(n, "Open");
        List<String> closed = readStringArray(n, "Closed");

        // JSON-defined formats first, then ALWAYS fall back to universal ISO/Java defaults
        List<DateTimeFormatter> formats = Stream.concat(
                        readStringArray(n, "DateTimeFormats").stream()
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(p -> DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT)),
                        DEFAULT_DATE_TIME_FORMATTERS.stream()
                )
                .distinct()
                .collect(Collectors.toList());

        List<WeeklyOpenRule> openRules = open.stream()
                .flatMap(s -> RulesParser.parseWeeklyOpen(s).stream())
                .collect(Collectors.toList());

        List<ClosedRule> closedRules = closed.stream()
                .flatMap(s -> RulesParser.parseClosed(s).stream())
                .collect(Collectors.toList());

        // --- NEW (optional) output defaults ---
        String defaultPattern = optText(n, "DefaultOutputPattern", null);
        if (defaultPattern != null && defaultPattern.isBlank()) defaultPattern = null;

        ZoneId defaultOutZone = null;
        if (defaultPattern != null) {
            String outZoneStr = optText(n, "DefaultOutputZone", null);
            if (outZoneStr == null || outZoneStr.isBlank()) {
                defaultOutZone = zone; // default to calendar zone
            } else {
                defaultOutZone = ZoneId.of(outZoneStr.trim());
            }
        }

        return new BusinessCalendar(zone, openRules, closedRules, formats, defaultPattern, defaultOutZone);
    }



    /** Returns OPEN if in open hours and not overridden by closed, else CLOSED. */
    public Status status(Object t) {
        ZonedDateTime z = normalizeForQuery(t);
        return isOpenAt(z) ? Status.OPEN : Status.CLOSED;
    }

    public boolean isOpen(Object t) { return status(t) == Status.OPEN; }
    public boolean isClosed(Object t) { return !isOpen(t); }

    /** Next open time >= input time (best-effort for ambiguous inputs). */
    public ZonedDateTime nextOpen(Object t) {
        ZonedDateTime z = normalizeForQuery(t);
        if (isOpenAt(z)) return z;

        for (int d = 0; d < 370; d++) {
            LocalDate date = z.toLocalDate().plusDays(d);
            for (Interval open : openIntervalsFor(date)) {
                ZonedDateTime cand = open.start;
                if (d == 0 && cand.isBefore(z)) {
                    if (z.isBefore(open.end)) cand = z;
                    else continue;
                }
                cand = skipIfClosed(cand);
                if (cand.isBefore(open.end) && isOpenAt(cand)) return cand;
            }
        }
        return null;
    }

    public ZonedDateTime lastOpen(Object t) {
        ZonedDateTime z = normalizeForQuery(t);
        if (isOpenAt(z)) return z;

        for (int d = 0; d < 370; d++) {
            LocalDate date = z.toLocalDate().minusDays(d);

            List<Interval> opens = openIntervalsFor(date);
            // reverse order for backward search
            for (int i = opens.size() - 1; i >= 0; i--) {
                Interval open = opens.get(i);
                ZonedDateTime cand = open.end.minusMinutes(1);

                if (d == 0 && cand.isAfter(z)) {
                    if (z.isAfter(open.start)) cand = z;
                    else continue;
                }

                cand = rewindIfClosed(cand);

                if (!cand.isBefore(open.start) && isOpenAt(cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    private ZonedDateTime rewindIfClosed(ZonedDateTime z) {
        while (isClosedAt(z)) {
            Interval c = closedIntervalCovering(z);
            if (c == null) return z.minusMinutes(1);
            z = c.start.minusMinutes(1);
        }
        return z;
    }

    private ZonedDateTime prevClosedEndOnDate(ZonedDateTime z) {
        List<Interval> cs = closedIntervalsFor(z.toLocalDate());
        ZonedDateTime best = null;
        for (Interval i : cs) {
            // we want the latest closed interval end strictly before z
            if (i.end.isBefore(z) && (best == null || i.end.isAfter(best))) best = i.end;
        }
        return best;
    }



    /**
     * Add "working/open" duration. If start is not open, it begins at nextOpen(start).
     * Closed overrides are always skipped.
     */
    public ZonedDateTime addOpenDuration(Object start, Duration openDuration) {
        long minutes = openDuration == null ? 0 : openDuration.toMinutes();

        if (minutes < 0) {
            return subtractOpenDuration(start, Duration.ofMinutes(Math.abs(minutes)));
        }

        long remaining = minutes;
        ZonedDateTime cur = nextOpen(start);
        if (cur == null || remaining == 0) return cur;

        while (remaining > 0) {
            cur = skipIfClosed(cur);
            Interval open = openIntervalCovering(cur);
            if (open == null) { cur = nextOpen(cur.plusMinutes(1)); continue; }

            ZonedDateTime limit = open.end;
            ZonedDateTime nextClosedStart = nextClosedStartOnDate(cur);
            if (nextClosedStart != null && nextClosedStart.isAfter(cur) && nextClosedStart.isBefore(limit)) {
                limit = nextClosedStart;
            }

            long chunk = Math.min(remaining, Duration.between(cur, limit).toMinutes());
            if (chunk <= 0) { cur = nextOpen(cur.plusMinutes(1)); continue; }

            cur = cur.plusMinutes(chunk);
            remaining -= chunk;

            if (remaining > 0) cur = nextOpen(cur);
            if (cur == null) return null;
        }
        return cur;
    }


    /**
     * Subtract "working/open" duration moving backward in time.
     * If start is not open, it begins at lastOpen(start).
     * Closed overrides are always skipped.
     */
    public ZonedDateTime subtractOpenDuration(Object start, Duration openDuration) {
        long remaining = Math.max(0, openDuration == null ? 0 : openDuration.toMinutes());
        if (remaining == 0) {
            ZonedDateTime cur0 = normalizeForQuery(start);
            return isOpenAt(cur0) ? cur0 : lastOpen(cur0);
        }

        ZonedDateTime cur = normalizeForQuery(start);

        // Cursor is EXCLUSIVE: we subtract open minutes strictly before "cur".
        // If start isn't open, move to the most recent open instant <= start, then advance cursor by 1 minute
        // so we subtract from the end of that last open minute slot.
        if (!isOpenAt(cur)) {
            ZonedDateTime lo = lastOpen(cur);
            if (lo == null) return null;
            cur = lo.plusMinutes(1); // exclusive cursor at the end of that open minute
        }

        while (remaining > 0) {
            LocalDate date = cur.toLocalDate();

            // Effective open segments for this date (open - closed), sorted ascending
            List<Interval> segs = effectiveOpenIntervalsFor(date);

            // Find the segment that is immediately "before" the cursor:
            // choose the last segment with seg.start < cur
            Interval seg = null;
            for (int i = segs.size() - 1; i >= 0; i--) {
                Interval s = segs.get(i);
                if (s.start.isBefore(cur)) {
                    seg = s;
                    break;
                }
            }

            if (seg == null) {
                // No effective open time before cursor on this date: jump to previous open minute
                ZonedDateTime lo = lastOpen(cur.minusMinutes(1));
                if (lo == null) return null;
                cur = lo.plusMinutes(1); // keep cursor exclusive
                continue;
            }

            // Segment end to subtract from is the earlier of cursor and seg.end (cursor can be mid-segment)
            ZonedDateTime segEndExclusive = seg.end.isBefore(cur) ? seg.end : cur;

            // available open minutes in this segment before the cursor
            long available = Duration.between(seg.start, segEndExclusive).toMinutes();

            if (available <= 0) {
                // Cursor is at/before seg.start; hop to previous open minute
                ZonedDateTime lo = lastOpen(seg.start.minusMinutes(1));
                if (lo == null) return null;
                cur = lo.plusMinutes(1);
                continue;
            }

            long chunk = Math.min(remaining, available);

            // Move cursor back by chunk minutes (cursor stays exclusive)
            cur = segEndExclusive.minusMinutes(chunk);
            remaining -= chunk;

            if (remaining > 0) {
                // If we landed exactly on a boundary that is not open, hop to previous open minute
                if (!isOpenAt(cur)) {
                    ZonedDateTime lo = lastOpen(cur.minusMinutes(1));
                    if (lo == null) return null;
                    cur = lo.plusMinutes(1);
                }
            }
        }

        // At the end, "cur" is an exclusive cursor that is also the desired result instant under this model.
        // Example: 14:00 minus 90 open minutes => cur = 11:30.
        return cur;
    }


    /**
     * Returns effective open intervals for the date, with "Closed" overrides removed.
     * Result intervals are half-open: [start, end).
     */
    private List<Interval> effectiveOpenIntervalsFor(LocalDate date) {
        List<Interval> opens = openIntervalsFor(date);
        if (opens.isEmpty()) return List.of();

        List<Interval> closeds = closedIntervalsFor(date);
        if (closeds.isEmpty()) return opens;

        // Ensure sorted
        closeds.sort(Comparator.comparing(a -> a.start));

        List<Interval> out = new ArrayList<>();

        for (Interval o : opens) {
            ZonedDateTime cur = o.start;

            for (Interval c : closeds) {
                // no overlap
                if (!c.end.isAfter(cur)) continue;           // closed ends before our current start
                if (!c.start.isBefore(o.end)) break;         // closed starts after open ends

                // overlap: add the open part before the closed interval
                ZonedDateTime cutEnd = c.start.isBefore(o.end) ? c.start : o.end;
                if (cur.isBefore(cutEnd)) {
                    out.add(new Interval(cur, cutEnd));
                }

                // advance cur past the closed interval
                cur = c.end.isAfter(cur) ? c.end : cur;

                // if we've reached/passed end of open interval, stop
                if (!cur.isBefore(o.end)) break;
            }

            // remaining tail after processing closed intervals
            if (cur.isBefore(o.end)) {
                out.add(new Interval(cur, o.end));
            }
        }

        out.sort(Comparator.comparing(a -> a.start));
        return out;
    }


    /**
     * Convert any temporal input to this calendar's timezone.
     * If the input has no zone/offset, assume it was GMT/UTC.
     */
    public ZonedDateTime toCalendarZoneAssumeGmtIfMissing(Object t) {
        // Option B: allow String here too (this method bypasses normalizeForQuery)
        if (t instanceof String s) {
            Object parsed = DateTimeParsingUtils.tryParse(dateTimeFormatters, s);
            if (parsed == null) {
                throw new IllegalArgumentException("Unparseable date/time: \"" + s + "\"");
            }
            t = parsed;
        }
        if (t == null) return null;
        if (t instanceof ZonedDateTime zdt) return zdt.withZoneSameInstant(zone);
        if (t instanceof OffsetDateTime odt) return odt.toInstant().atZone(zone);
        if (t instanceof Instant ins) return ins.atZone(zone);

        if (t instanceof LocalDateTime ldt) return ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone);
        if (t instanceof LocalDate ld) return ld.atStartOfDay(ZoneOffset.UTC).withZoneSameInstant(zone);
        if (t instanceof LocalTime lt) {
            LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
            return ZonedDateTime.of(todayUtc, lt, ZoneOffset.UTC).withZoneSameInstant(zone);
        }
        if (t instanceof TemporalAccessor ta) {
            Instant ins = Instant.from(ta);
            return ins.atZone(zone);
        }
        throw new IllegalArgumentException("Unsupported temporal type: " + t.getClass().getName());
    }

    // ---------------- internals ----------------

    private boolean isOpenAt(ZonedDateTime z) {
        if (isClosedAt(z)) return false;
        return openRules.stream().anyMatch(r -> r.matches(z));
    }

    private boolean isClosedAt(ZonedDateTime z) {
        LocalDate date = z.toLocalDate();
        int minute = z.getHour() * 60 + z.getMinute();
        for (ClosedRule r : closedRules) {
            if (r.appliesTo(date) && r.matchesMinute(minute)) return true;
        }
        return false;
    }

    private ZonedDateTime skipIfClosed(ZonedDateTime z) {
        while (isClosedAt(z)) {
            Interval c = closedIntervalCovering(z);
            if (c == null) return z.plusMinutes(1);
            z = c.end;
        }
        return z;
    }

    private Interval openIntervalCovering(ZonedDateTime z) {
        for (Interval i : openIntervalsFor(z.toLocalDate())) {
            if (!z.isBefore(i.start) && z.isBefore(i.end)) return i;
        }
        return null;
    }

    private Interval closedIntervalCovering(ZonedDateTime z) {
        for (Interval i : closedIntervalsFor(z.toLocalDate())) {
            if (!z.isBefore(i.start) && z.isBefore(i.end)) return i;
        }
        return null;
    }

    private ZonedDateTime nextClosedStartOnDate(ZonedDateTime z) {
        List<Interval> cs = closedIntervalsFor(z.toLocalDate());
        ZonedDateTime best = null;
        for (Interval i : cs) {
            if (i.start.isAfter(z) && (best == null || i.start.isBefore(best))) best = i.start;
        }
        return best;
    }

    private List<Interval> openIntervalsFor(LocalDate date) {
        List<Interval> out = new ArrayList<>();
        for (WeeklyOpenRule r : openRules) out.addAll(r.materialize(date, zone));
        out.sort(Comparator.comparing(a -> a.start));
        return out;
    }

    private List<Interval> closedIntervalsFor(LocalDate date) {
        List<Interval> out = new ArrayList<>();
        for (ClosedRule r : closedRules) out.addAll(r.materialize(date, zone));
        out.sort(Comparator.comparing(a -> a.start));
        return out;
    }

    private ZonedDateTime normalizeForQuery(Object t) {
        if (t == null) return ZonedDateTime.now(zone);

        ZonedDateTime now = ZonedDateTime.now(zone);

        if (t instanceof String s) {
            Object parsed = DateTimeParsingUtils.tryParse(dateTimeFormatters, s);
            if (parsed != null) return normalizeForQuery(parsed);
            throw new IllegalArgumentException("Unparseable date/time: \"" + s + "\"");
        }

        if (t instanceof ZonedDateTime zdt) return zdt.withZoneSameInstant(zone);
        if (t instanceof OffsetDateTime odt) return odt.toInstant().atZone(zone);
        if (t instanceof Instant ins) return ins.atZone(zone);

        if (t instanceof LocalDateTime ldt) return ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone);
        if (t instanceof LocalDate ld) return ld.atStartOfDay(ZoneOffset.UTC).withZoneSameInstant(zone);


        if (t instanceof LocalTime lt) {
            ZonedDateTime cand = now.with(lt);
            if (cand.isBefore(now)) cand = cand.plusDays(1);
            return cand;
        }

        if (t instanceof TemporalAccessor ta) {
            try { return Instant.from(ta).atZone(zone); } catch (Exception ignored) {}
            return now;
        }

        throw new IllegalArgumentException("Unsupported temporal type: " + t.getClass().getName());
    }

    private static String optText(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asText(def);
    }

    private static List<String> readStringArray(JsonNode n, String field) {
        JsonNode arr = n.get(field);
        if (arr == null || arr.isNull() || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    // ---------------- value types ----------------

    static final class Interval {
        final ZonedDateTime start;
        final ZonedDateTime end;
        Interval(ZonedDateTime start, ZonedDateTime end) { this.start = start; this.end = end; }
    }

    static final class TimeRange {
        final int startMin;   // inclusive
        final int endMin;     // exclusive (can be 1440)
        TimeRange(int s, int e) { this.startMin = s; this.endMin = e; }
        boolean containsMinute(int m) { return m >= startMin && m < endMin; }

        Interval materialize(LocalDate date, ZoneId zone) {
            ZonedDateTime base = date.atStartOfDay(zone);
            ZonedDateTime s = base.plusMinutes(startMin);
            ZonedDateTime e = base.plusMinutes(endMin);
            return new Interval(s, e);
        }
    }

    static final class WeeklyOpenRule {
        final EnumSet<DayOfWeek> days;
        final List<TimeRange> ranges;
        WeeklyOpenRule(EnumSet<DayOfWeek> days, List<TimeRange> ranges) { this.days = days; this.ranges = ranges; }

        boolean matches(ZonedDateTime z) {
            if (!days.contains(z.getDayOfWeek())) return false;
            int m = z.getHour() * 60 + z.getMinute();
            for (TimeRange r : ranges) if (r.containsMinute(m)) return true;
            return false;
        }

        List<Interval> materialize(LocalDate date, ZoneId zone) {
            if (!days.contains(date.getDayOfWeek())) return List.of();
            List<Interval> out = new ArrayList<>(ranges.size());
            for (TimeRange r : ranges) out.add(r.materialize(date, zone));
            return out;
        }
    }

    /** Inclusive int ranges like "2,5,24-27,31" (supports contains()). */
    static final class IntRanges {
        final int min, max;
        final List<int[]> ranges; // each [a,b] inclusive

        IntRanges(int min, int max, List<int[]> ranges) {
            this.min = min;
            this.max = max;
            this.ranges = ranges;
        }

        boolean contains(int v) {
            for (int[] r : ranges) if (v >= r[0] && v <= r[1]) return true;
            return false;
        }

        static IntRanges parse(String s, int min, int max) {
            List<int[]> rs = new ArrayList<>();
            for (String part : s.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int a, b;
                if (p.contains("-")) {
                    String[] lr = p.split("-", 2);
                    a = Integer.parseInt(lr[0].trim());
                    b = Integer.parseInt(lr[1].trim());
                } else {
                    a = b = Integer.parseInt(p);
                }
                if (a > b) { int t = a; a = b; b = t; }
                a = Math.max(min, a);
                b = Math.min(max, b);
                rs.add(new int[]{a, b});
            }
            return rs.isEmpty() ? null : new IntRanges(min, max, rs);
        }
    }

    /**
     * Closed selector:
     * - any of: days-of-month, day-of-week, months, years
     * - times: empty => full day
     */
    static final class ClosedRule {
        final IntRanges dom;                // null => any day-of-month
        final EnumSet<DayOfWeek> dows;      // empty => any dow
        final EnumSet<Month> months;        // empty => any month
        final IntRanges years;              // null => any year
        final List<TimeRange> times;        // empty => full day

        ClosedRule(IntRanges dom, EnumSet<DayOfWeek> dows, EnumSet<Month> months, IntRanges years, List<TimeRange> times) {
            this.dom = dom;
            this.dows = dows == null ? EnumSet.noneOf(DayOfWeek.class) : dows;
            this.months = months == null ? EnumSet.noneOf(Month.class) : months;
            this.years = years;
            this.times = times == null ? List.of() : times;
        }

        boolean appliesTo(LocalDate d) {
            if (years != null && !years.contains(d.getYear())) return false;
            if (!months.isEmpty() && !months.contains(d.getMonth())) return false;
            if (dom != null && !dom.contains(d.getDayOfMonth())) return false;
            if (!dows.isEmpty() && !dows.contains(d.getDayOfWeek())) return false;
            return true;
        }

        boolean matchesMinute(int m) {
            if (times.isEmpty()) return true; // full day
            for (TimeRange r : times) if (r.containsMinute(m)) return true;
            return false;
        }

        List<Interval> materialize(LocalDate d, ZoneId zone) {
            if (!appliesTo(d)) return List.of();
            if (times.isEmpty()) return List.of(new Interval(d.atStartOfDay(zone), d.plusDays(1).atStartOfDay(zone)));
            List<Interval> out = new ArrayList<>(times.size());
            for (TimeRange tr : times) out.add(tr.materialize(d, zone));
            return out;
        }
    }

    // ---------------- parsing ----------------

    static final class RulesParser {
        private static final Map<String, DayOfWeek> DOW = Map.ofEntries(
                Map.entry("MON", DayOfWeek.MONDAY), Map.entry("MONDAY", DayOfWeek.MONDAY),
                Map.entry("TUE", DayOfWeek.TUESDAY), Map.entry("TUESDAY", DayOfWeek.TUESDAY),
                Map.entry("WED", DayOfWeek.WEDNESDAY), Map.entry("WEDNESDAY", DayOfWeek.WEDNESDAY),
                Map.entry("THU", DayOfWeek.THURSDAY), Map.entry("THURSDAY", DayOfWeek.THURSDAY),
                Map.entry("FRI", DayOfWeek.FRIDAY), Map.entry("FRIDAY", DayOfWeek.FRIDAY),
                Map.entry("SAT", DayOfWeek.SATURDAY), Map.entry("SATURDAY", DayOfWeek.SATURDAY),
                Map.entry("SUN", DayOfWeek.SUNDAY), Map.entry("SUNDAY", DayOfWeek.SUNDAY)
        );

        private static final Map<String, Month> MON = Map.ofEntries(
                Map.entry("JAN", Month.JANUARY), Map.entry("FEB", Month.FEBRUARY), Map.entry("MAR", Month.MARCH),
                Map.entry("APR", Month.APRIL), Map.entry("MAY", Month.MAY), Map.entry("JUN", Month.JUNE),
                Map.entry("JUL", Month.JULY), Map.entry("AUG", Month.AUGUST), Map.entry("SEP", Month.SEPTEMBER),
                Map.entry("OCT", Month.OCTOBER), Map.entry("NOV", Month.NOVEMBER), Map.entry("DEC", Month.DECEMBER)
        );

        static List<WeeklyOpenRule> parseWeeklyOpen(String s) {
            String raw = s.trim();
            if (raw.isEmpty()) return List.of();

            List<String> tokens = splitSpace(raw);
            int firstTimeIdx = -1;
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).matches(".*\\d.*")) { firstTimeIdx = i; break; }
            }
            if (firstTimeIdx < 0) return List.of();

            String daysPart = String.join(" ", tokens.subList(0, firstTimeIdx)).trim();
            String timesPart = String.join(" ", tokens.subList(firstTimeIdx, tokens.size())).trim();

            EnumSet<DayOfWeek> days = parseDows(daysPart);
            List<TimeRange> ranges = parseTimeRanges(timesPart);

            return days.isEmpty() || ranges.isEmpty()
                    ? List.of()
                    : List.of(new WeeklyOpenRule(days, ranges));
        }

        /**
         * Closed parsing with consistent ',' and '-' for all date units.
         *
         * Supports:
         *  - "24-27 DEC"
         *  - "24,25,26,27 DEC"
         *  - "2,5,24-27,31 DEC"
         *  - "WED,TUE DEC"
         *  - "3,5 FEB 2026 1300-1500 , 1100-1200" (time-only clause continues previous date selector)
         */
        static List<ClosedRule> parseClosed(String s) {
            // IMPORTANT:
            // split only on comma + whitespace, so unit lists like "2,5,24-27,31" stay intact
            // while allowing clause separators like "..., 1100-1200" or "..., 7 SEP 2026"
            List<String> clauses = Arrays.stream(s.split(",\\s+"))
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .toList();

            List<ClosedRule> rules = new ArrayList<>();
            ClosedSpec last = null;

            for (String clause : clauses) {
                ClosedSpec spec = parseClosedClause(clause);

                if (spec.hasAnyDateSelector()) {
                    last = spec;
                    rules.add(spec.toRule());
                } else {
                    // time-only clause => applies to previous selector (if any)
                    if (last != null && !spec.times.isEmpty()) {
                        last.times.addAll(spec.times);
                        rules.set(rules.size() - 1, last.toRule()); // replace last rule with updated times
                    }
                }
            }

            return rules;
        }


        private static ClosedSpec parseClosedClause(String clause) {
            List<String> t = splitSpace(clause.toUpperCase(Locale.ROOT));
            ClosedSpec spec = new ClosedSpec();

            // Identify tokens: month token, year token, dow token(s), dom token(s), time token(s)
            for (String tok : t) {
                if (tok.matches("\\d{4}-\\d{4}") || tok.matches("\\d{4}")) {
                    // could be year list/range OR time range; disambiguate by colon-free + length:
                    // times are HHmm-HHmm (both 4 digits with dash); years can also be yyyy-yyyy
                    if (tok.matches("\\d{4}-\\d{4}") && isLikelyTimeRange(tok)) spec.times.addAll(parseTimeRanges(tok));
                    else spec.yearsTok.add(tok);
                } else if (tok.matches("\\d{3,4}-\\d{3,4}") && isLikelyTimeRange(tok)) {
                    spec.times.addAll(parseTimeRanges(tok));
                } else if (tok.matches(".*\\d.*") && tok.contains("-") && isLikelyTimeRange(tok)) {
                    spec.times.addAll(parseTimeRanges(tok));
                } else if (MON.containsKey(stripPunct(tok))) {
                    spec.monthsTok.add(tok);
                } else if (looksLikeDowExpr(tok)) {
                    spec.dowsTok.add(tok);
                } else if (looksLikeDomExpr(tok)) {
                    spec.domTok.add(tok);
                } else if (tok.contains("-") && (MON.containsKey(stripPunct(tok.split("-", 2)[0])) || looksLikeDowExpr(tok))) {
                    // allow "NOV-DEC" or "MON-THU" as single token
                    if (tok.chars().anyMatch(Character::isDigit)) spec.domTok.add(tok);
                    else if (tok.contains("-") && tok.matches(".*[A-Z].*")) {
                        String left = stripPunct(tok.split("-", 2)[0]);
                        if (MON.containsKey(left)) spec.monthsTok.add(tok);
                        else spec.dowsTok.add(tok);
                    }
                }
            }

            // Build selectors from tokens (each unit can be "a,b,c-d" within a token)
            if (!spec.domTok.isEmpty()) spec.dom = IntRanges.parse(joinTokens(spec.domTok), 1, 31);
            if (!spec.yearsTok.isEmpty()) spec.years = IntRanges.parse(joinTokens(spec.yearsTok), 0, 9999);
            if (!spec.monthsTok.isEmpty()) spec.months = parseMonths(joinTokens(spec.monthsTok));
            if (!spec.dowsTok.isEmpty()) spec.dows = parseDows(joinTokens(spec.dowsTok));

            return spec;
        }

        private static boolean isLikelyTimeRange(String tok) {
            // HHmm-HHmm where HH is 00-24 and mm 00-59, but 24 is only valid with 00 minutes.
            String[] lr = tok.split("-", 2);
            if (lr.length != 2) return false;
            if (!lr[0].matches("\\d{4}") || !lr[1].matches("\\d{4}")) return false;

            int aH = Integer.parseInt(lr[0].substring(0, 2));
            int aM = Integer.parseInt(lr[0].substring(2, 4));
            int bH = Integer.parseInt(lr[1].substring(0, 2));
            int bM = Integer.parseInt(lr[1].substring(2, 4));

            if (aH < 0 || aH > 24 || bH < 0 || bH > 24) return false;
            if (aM < 0 || aM > 59 || bM < 0 || bM > 59) return false;

            // 24:xx only allowed as 24:00
            if (aH == 24 && aM != 0) return false;
            if (bH == 24 && bM != 0) return false;

            return true;
        }


        private static boolean looksLikeDomExpr(String tok) {
            // Allow dom lists/ranges like:
            //  "24-27"
            //  "2,5,24-27,31"
            //  "1"
            String x = tok.trim().replaceAll("\\s+", "");
            x = x.replaceAll("[^0-9,\\-]", ""); // keep digits, commas, dashes
            if (x.isEmpty()) return false;

            // One or more day numbers (1-2 digits), separated by ',' or '-' patterns
            // We keep it permissive; IntRanges.parse() will clamp/normalize.
            return x.matches("\\d{1,2}([,-]\\d{1,2})*");
        }


        private static boolean looksLikeDowExpr(String tok) {
            String x = stripPunct(tok);
            if (DOW.containsKey(x)) return true;
            if (x.contains("-")) {
                String[] lr = x.split("-", 2);
                return DOW.containsKey(lr[0]) && DOW.containsKey(lr[1]);
            }
            return false;
        }

        private static String stripPunct(String s) {
            return s.trim().replaceAll("[^A-Z0-9\\-]", "");
        }

        private static String joinTokens(List<String> toks) {
            // outer parse may have split commas into separate clauses; within a clause we still want "a-b" tokens joined by commas
            // e.g. domTok: ["2,5,24-27,31"] is one token; or ["24-27"] etc.
            return String.join(",", toks.stream().map(String::trim).filter(x -> !x.isEmpty()).toList());
        }

        private static EnumSet<DayOfWeek> parseDows(String s) {
            String up = s.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            if (up.isEmpty()) return EnumSet.noneOf(DayOfWeek.class);

            EnumSet<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
            for (String part : up.split(",")) {
                if (part.isEmpty()) continue;
                if (part.contains("-")) {
                    String[] lr = part.split("-", 2);
                    DayOfWeek l = DOW.get(stripPunct(lr[0]));
                    DayOfWeek r = DOW.get(stripPunct(lr[1]));
                    addDowRange(out, l, r);
                } else {
                    DayOfWeek d = DOW.get(stripPunct(part));
                    if (d != null) out.add(d);
                }
            }
            return out;
        }

        private static void addDowRange(EnumSet<DayOfWeek> set, DayOfWeek a, DayOfWeek b) {
            if (a == null || b == null) return;
            int ai = a.getValue(), bi = b.getValue();
            if (ai <= bi) for (int i = ai; i <= bi; i++) set.add(DayOfWeek.of(i));
            else {
                for (int i = ai; i <= 7; i++) set.add(DayOfWeek.of(i));
                for (int i = 1; i <= bi; i++) set.add(DayOfWeek.of(i));
            }
        }

        private static EnumSet<Month> parseMonths(String s) {
            String up = s.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            if (up.isEmpty()) return EnumSet.noneOf(Month.class);

            EnumSet<Month> out = EnumSet.noneOf(Month.class);
            for (String part : up.split(",")) {
                if (part.isEmpty()) continue;
                if (part.contains("-")) {
                    String[] lr = part.split("-", 2);
                    Month l = MON.get(stripPunct(lr[0]));
                    Month r = MON.get(stripPunct(lr[1]));
                    addMonthRange(out, l, r);
                } else {
                    Month m = MON.get(stripPunct(part));
                    if (m != null) out.add(m);
                }
            }
            return out;
        }

        private static void addMonthRange(EnumSet<Month> set, Month a, Month b) {
            if (a == null || b == null) return;
            int ai = a.getValue(), bi = b.getValue();
            if (ai <= bi) for (int i = ai; i <= bi; i++) set.add(Month.of(i));
            else {
                for (int i = ai; i <= 12; i++) set.add(Month.of(i));
                for (int i = 1; i <= bi; i++) set.add(Month.of(i));
            }
        }

        private static List<TimeRange> parseTimeRanges(String s) {
            String norm = s.trim().replace(",", " ");
            List<TimeRange> out = new ArrayList<>();
            for (String tok : splitSpace(norm)) {
                if (tok.isBlank()) continue;
                if (!tok.contains("-")) continue;
                String[] lr = tok.split("-", 2);
                int a = parseHHmmToMin(lr[0]);
                int b = parseHHmmToMin(lr[1]);
                if (b < a) { int tmp = a; a = b; b = tmp; }
                out.add(new TimeRange(a, b));
            }
            return out;
        }

        private static int parseHHmmToMin(String hhmm) {
            String x = hhmm.trim();
            int h = Integer.parseInt(x.substring(0, 2));
            int m = Integer.parseInt(x.substring(2, 4));
            if (h == 24 && m == 0) return 1440;
            return h * 60 + m;
        }

        private static List<String> splitSpace(String s) {
            return Arrays.stream(s.trim().split("\\s+"))
                    .filter(x -> !x.isBlank())
                    .toList();
        }

        static final class ClosedSpec {
            // raw tokens, later merged
            final List<String> domTok = new ArrayList<>();
            final List<String> dowsTok = new ArrayList<>();
            final List<String> monthsTok = new ArrayList<>();
            final List<String> yearsTok = new ArrayList<>();
            final List<TimeRange> times = new ArrayList<>();

            IntRanges dom;
            EnumSet<DayOfWeek> dows = EnumSet.noneOf(DayOfWeek.class);
            EnumSet<Month> months = EnumSet.noneOf(Month.class);
            IntRanges years;

            boolean hasAnyDateSelector() {
                return !domTok.isEmpty() || !dowsTok.isEmpty() || !monthsTok.isEmpty() || !yearsTok.isEmpty();
            }

            ClosedRule toRule() {
                return new ClosedRule(dom, dows, months, years, List.copyOf(times));
            }
        }
    }

    public BusinessTime of(String dateTime) {
        return BusinessTime.of(this, dateTime);
    }

    public BusinessTime now() {
        ZonedDateTime z = ZonedDateTime.now(this.zone());

        BusinessTime bt = new BusinessTime(this, z);

        if (this.defaultOutputPattern().isPresent()) {
            String p = this.defaultOutputPattern().get();
            ZoneId oz = this.defaultOutputZone().orElse(this.zone());
            bt = bt.asZone(oz).asPattern(p);
        }

        return bt;
    }


    public  BusinessTime today() {
        ZonedDateTime z = ZonedDateTime.now(this.zone()).truncatedTo(ChronoUnit.DAYS);

        BusinessTime bt = new BusinessTime(this, z);

        if (this.defaultOutputPattern().isPresent()) {
            String p = this.defaultOutputPattern().get();
            ZoneId oz = this.defaultOutputZone().orElse(this.zone());
            bt = bt.asZone(oz).asPattern(p);
        }
        return bt;
    }

    public BusinessTime tomorrow() {
        return today().add("+ 1 day");
    }

    public BusinessTime yesterday() {
        return today().add("- 1 day");
    }

    public Duration durationBetween(String start, String end) {
        return of(start).durationBetween(end);
    }

    public String eval(String spec) {
        return (new BusinessTime(this)).eval(spec);
    }

}
