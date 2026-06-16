package tools.dscode.common.util.datetime;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable date/time instance bound to a BusinessCalendar.
 *
 * value() is the real stored time. asZone()/asPattern() only affect rendering.
 */
public final class BusinessTime {

    private final BusinessCalendar cal;
    private final ZonedDateTime zdt;
    private final ZoneId outputZone;
    private final String outputFormat;

    BusinessTime(BusinessCalendar cal) {
        this(cal, ZonedDateTime.now(cal.zone()));
    }

    BusinessTime(BusinessCalendar cal, ZonedDateTime zdt) {
        this(cal, zdt, null, null);
    }

    private BusinessTime(BusinessCalendar cal, ZonedDateTime zdt, ZoneId outputZone, String outputFormat) {
        this.cal = Objects.requireNonNull(cal, "cal");
        this.zdt = Objects.requireNonNull(zdt, "zdt");
        this.outputZone = outputZone;
        this.outputFormat = blankToNull(outputFormat);
    }

    public static BusinessTime of(BusinessCalendar cal, String dateTime) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(dateTime, "dateTime");

        String s = dateTime.trim();
        ZonedDateTime z = looksLikeIsoZoned(s)
                ? ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME).withZoneSameInstant(cal.zone())
                : cal.toCalendarZoneAssumeGmtIfMissing(s);

        return applyCalendarDefaultOutput(new BusinessTime(cal, z));
    }

    private static BusinessTime of(BusinessCalendar cal, String dateTime, List<DateTimeFormatter> inputFormatters) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(dateTime, "dateTime");

        ZonedDateTime z = cal.toCalendarZoneAssumeGmtIfMissing(dateTime.trim(), inputFormatters);
        return applyCalendarDefaultOutput(new BusinessTime(cal, z));
    }

    /**
     * Evaluates the same syntax that eval(...) used to support, but returns the resolved object.
     */
    public static BusinessTime evaluate(BusinessCalendar cal, String spec) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(spec, "spec");

        ParsedDateTimeSpec parsed = parseSpec(spec);
        BusinessTime bt = evaluateBase(cal, parsed);

        if (parsed.zoneText() != null) bt = bt.asZone(parseZoneIdLenient(parsed.zoneText()));
        if (parsed.formatText() != null) bt = bt.asPattern(parsed.formatText());

        return bt;
    }

    static ZonedDateTime evaluateValue(BusinessCalendar cal, String spec) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(spec, "spec");

        return evaluateBase(cal, parseSpec(spec)).value();
    }

    private static ParsedDateTimeSpec parseSpec(String spec) {
        String raw = stripPrefix(spec.trim(), "DateTime:");
        if (raw.isEmpty()) throw new IllegalArgumentException("spec is blank");

        ExtractedClauses extractedClauses = extractClauses(raw);
        raw = extractedClauses.remaining();

        ExtractedZone extractedZone = extractOutputZone(raw);
        raw = extractedZone.remaining();

        int deltaIdx = findFirstDeltaStart(raw);
        String baseText = (deltaIdx < 0) ? raw.trim() : raw.substring(0, deltaIdx).trim();
        String deltaText = (deltaIdx < 0) ? "" : raw.substring(deltaIdx).trim();

        if (baseText.isEmpty()) throw new IllegalArgumentException("Missing base time in spec: \"" + spec + "\"");

        return new ParsedDateTimeSpec(
                baseText,
                deltaText,
                extractedZone.zoneText(),
                extractedClauses.formatText(),
                extractedClauses.patternTexts()
        );
    }

    private static BusinessTime evaluateBase(BusinessCalendar cal, ParsedDateTimeSpec parsed) {
        List<DateTimeFormatter> inputFormatters = inputFormatters(cal, parsed.patternTexts());

        BusinessTime bt = switch (parsed.baseText().toLowerCase(Locale.ROOT)) {
            case "today" -> cal.today();
            case "tomorrow" -> cal.tomorrow();
            case "yesterday" -> cal.yesterday();
            case "now" -> cal.now();
            default -> inputFormatters == null ? cal.of(parsed.baseText()) : of(cal, parsed.baseText(), inputFormatters);
        };

        if (!parsed.deltaText().isBlank()) bt = bt.add(parsed.deltaText());

        return bt;
    }

    public TemporalValue eval(String spec) {
        return TemporalValue.dateTime(spec, evaluate(cal, spec));
    }

    public BusinessCalendar calendar() { return cal; }
    public ZonedDateTime value() { return zdt; }
    public ZoneId outputZone() { return outputZone; }
    public String outputFormat() { return outputFormat; }

    public String render() { return toString(); }

    public String format(String pattern) {
        return zdt.format(DateTimeFormatter.ofPattern(pattern));
    }

    public BusinessTime asPattern(String pattern) {
        return new BusinessTime(cal, zdt, outputZone, Objects.requireNonNull(pattern, "pattern"));
    }

    public BusinessTime asZone(String zoneId) {
        return asZone(ZoneId.of(Objects.requireNonNull(zoneId, "zoneId")));
    }

    public BusinessTime asZone(ZoneId zone) {
        return new BusinessTime(cal, zdt, Objects.requireNonNull(zone, "zone"), outputFormat);
    }

    public BusinessTime resetOutput() {
        return new BusinessTime(cal, zdt, null, null);
    }

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

    /** General date/time math. Supports months/years because it is applied to a real instance. */
    public BusinessTime add(String expr) {
        ZonedDateTime cur = DateTimeDeltaParsingUtils.applyTo(zdt, expr);
        return new BusinessTime(cal, cur, outputZone, outputFormat);
    }

    /** Open/business-time math. Converts to exact Duration and rejects months/years. */
    public BusinessTime addOpen(String expr) {
        Duration dur = DateTimeDeltaParsingUtils.toDuration(expr);
        ZonedDateTime out = cal.addOpenDuration(zdt, dur);
        return out == null ? null : new BusinessTime(cal, out, outputZone, outputFormat);
    }

    public Duration durationBetween(String otherDateTime) {
        return Duration.between(this.zdt, of(cal, otherDateTime).zdt);
    }

    @Override
    public String toString() {
        ZonedDateTime view = outputZone == null ? zdt : zdt.withZoneSameInstant(outputZone);
        if (outputFormat == null) return view.toString();
        return renderFormat(view, outputFormat);
    }

    private static BusinessTime applyCalendarDefaultOutput(BusinessTime bt) {
        if (bt.cal.defaultOutputPattern().isEmpty()) return bt;
        ZoneId zone = bt.cal.defaultOutputZone().orElse(bt.cal.zone());
        return bt.asZone(zone).asPattern(bt.cal.defaultOutputPattern().get());
    }

    private static String renderFormat(ZonedDateTime view, String format) {
        String f = format.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        Instant instant = view.toInstant();

        return switch (f) {
            case "iso", "iso_instant", "instant" -> instant.toString();
            case "iso_zoned", "iso_zoned_date_time" -> DateTimeFormatter.ISO_ZONED_DATE_TIME.format(view);
            case "iso_offset", "iso_offset_date_time" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(view);
            case "epoch_millis", "epochmillis", "millis" -> String.valueOf(instant.toEpochMilli());
            case "epoch_seconds", "epochseconds", "seconds" -> String.valueOf(instant.getEpochSecond());
            case "epoch_nanos", "epochnanos", "nanos" -> epochNanos(instant).toString();
            default -> view.format(DateTimeFormatter.ofPattern(format));
        };
    }

    private static BigInteger epochNanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(instant.getNano()));
    }

    private static ExtractedClauses extractClauses(String raw) {
        ClauseMarker first = nextClauseMarker(raw, 0);
        if (first == null) return new ExtractedClauses(raw.trim(), null, List.of());

        String remaining = raw.substring(0, first.index()).trim();
        String format = null;
        List<String> patterns = new ArrayList<>();

        ClauseMarker cur = first;
        while (cur != null) {
            int valueStart = cur.index() + cur.tokenLength();
            ClauseMarker next = nextClauseMarker(raw, valueStart);
            String value = raw.substring(valueStart, next == null ? raw.length() : next.index()).trim();

            if (cur.format()) {
                format = blankToNull(value);
            } else if (!value.isBlank()) {
                patterns.add(value);
            }

            cur = next;
        }

        return new ExtractedClauses(remaining, format, List.copyOf(patterns));
    }

    private static List<DateTimeFormatter> inputFormatters(BusinessCalendar cal, List<String> patternTexts) {
        if (patternTexts.isEmpty()) return null;

        List<DateTimeFormatter> out = new ArrayList<>();
        for (String pattern : patternTexts) {
            String p = pattern.trim();
            if (p.isEmpty()) throw new IllegalArgumentException("Date/time input pattern is blank");
            if ("*".equals(p)) out.addAll(cal.dateTimeFormatters());
            else out.addAll(DateTimeParsingUtils.formattersFromPatterns(List.of(p)));
        }
        return List.copyOf(out);
    }

    private static ClauseMarker nextClauseMarker(String raw, int from) {
        int formatIdx = indexOfIgnoreCase(raw, "format:", from);
        int patternIdx = indexOfIgnoreCase(raw, "pattern:", from);

        if (formatIdx < 0 && patternIdx < 0) return null;
        if (patternIdx < 0 || (formatIdx >= 0 && formatIdx < patternIdx)) {
            return new ClauseMarker(formatIdx, "format:".length(), true);
        }
        return new ClauseMarker(patternIdx, "pattern:".length(), false);
    }

    private static ExtractedZone extractOutputZone(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        int toIdx = lower.indexOf(" to ");
        if (toIdx < 0) return new ExtractedZone(raw.trim(), null);

        int tzIdx = lower.indexOf(" timezone", toIdx);
        int tzLen = " timezone".length();
        if (tzIdx < 0) {
            tzIdx = lower.indexOf(" time zone", toIdx);
            tzLen = " time zone".length();
        }
        if (tzIdx <= toIdx) return new ExtractedZone(raw.trim(), null);

        String zone = blankToNull(raw.substring(toIdx + 4, tzIdx));
        String left = raw.substring(0, toIdx).trim();
        String right = raw.substring(tzIdx + tzLen).trim();
        return new ExtractedZone((left + " " + right).trim(), zone);
    }

    private static int findFirstDeltaStart(String s) {
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isWhitespace(s.charAt(i - 1))) continue;
            if (ch == '+' || ch == '-') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (Character.isDigit(s.charAt(j)) || isIsoDurationStart(s.charAt(j)))) return i;
            } else if (isIsoDurationStart(ch)) {
                int j = i + 1;
                if (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == 'T' || s.charAt(j) == 't')) return i;
            }
        }
        return -1;
    }

    private static boolean isIsoDurationStart(char ch) {
        return ch == 'P' || ch == 'p';
    }

    private static ZoneId parseZoneIdLenient(String zoneText) {
        String z = zoneText.trim();
        try { return ZoneId.of(z); } catch (Exception ignored) {}
        try { return ZoneId.of(z.toUpperCase(Locale.ROOT), ZoneId.SHORT_IDS); } catch (Exception ignored) {}
        throw new IllegalArgumentException("Unrecognized time zone: \"" + zoneText + "\"");
    }

    private static boolean looksLikeIsoZoned(String s) {
        return s.contains("[") && s.contains("]") && s.contains("T")
                && (s.contains("Z") || s.contains("+") || s.contains("-"));
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return indexOfIgnoreCase(s, needle, 0);
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length())
                ? text.substring(prefix.length()).trim()
                : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record ExtractedClauses(String remaining, String formatText, List<String> patternTexts) {}
    private record ParsedDateTimeSpec(
            String baseText,
            String deltaText,
            String zoneText,
            String formatText,
            List<String> patternTexts
    ) {}
    private record ClauseMarker(int index, int tokenLength, boolean format) {}
    private record ExtractedZone(String remaining, String zoneText) {}
}
