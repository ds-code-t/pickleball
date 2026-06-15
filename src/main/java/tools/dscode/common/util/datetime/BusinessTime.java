package tools.dscode.common.util.datetime;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * Evaluates the same syntax that eval(...) used to support, but returns the resolved object.
     */
    public static BusinessTime evaluate(BusinessCalendar cal, String spec) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(spec, "spec");

        String raw = stripPrefix(spec.trim(), "DateTime:");
        if (raw.isEmpty()) throw new IllegalArgumentException("spec is blank");

        ExtractedFormat extractedFormat = extractFormat(raw);
        raw = extractedFormat.remaining();

        ExtractedZone extractedZone = extractOutputZone(raw);
        raw = extractedZone.remaining();

        int deltaIdx = findFirstDeltaStart(raw);
        String baseText = (deltaIdx < 0) ? raw.trim() : raw.substring(0, deltaIdx).trim();
        String deltaText = (deltaIdx < 0) ? "" : raw.substring(deltaIdx).trim();

        if (baseText.isEmpty()) throw new IllegalArgumentException("Missing base time in spec: \"" + spec + "\"");

        BusinessTime bt = switch (baseText.toLowerCase(Locale.ROOT)) {
            case "today" -> cal.today();
            case "tomorrow" -> cal.tomorrow();
            case "yesterday" -> cal.yesterday();
            case "now" -> cal.now();
            default -> cal.of(baseText);
        };

        if (!deltaText.isBlank()) bt = bt.add(deltaText);
        if (extractedZone.zoneText() != null) bt = bt.asZone(parseZoneIdLenient(extractedZone.zoneText()));
        if (extractedFormat.formatText() != null) bt = bt.asPattern(extractedFormat.formatText());

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

    private static ExtractedFormat extractFormat(String raw) {
        int idx = indexOfIgnoreCase(raw, "format:");
        if (idx < 0) return new ExtractedFormat(raw.trim(), null);
        String format = blankToNull(raw.substring(idx + "format:".length()));
        String remaining = raw.substring(0, idx).trim();
        return new ExtractedFormat(remaining, format);
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
            if (ch != '+' && ch != '-') continue;
            if (!Character.isWhitespace(s.charAt(i - 1))) continue;
            int j = i + 1;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
            if (j < s.length() && Character.isDigit(s.charAt(j))) return i;
        }
        return -1;
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
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length())
                ? text.substring(prefix.length()).trim()
                : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record ExtractedFormat(String remaining, String formatText) {}
    private record ExtractedZone(String remaining, String zoneText) {}
}
