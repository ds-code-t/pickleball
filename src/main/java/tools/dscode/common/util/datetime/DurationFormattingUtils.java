package tools.dscode.common.util.datetime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Formatting and evaluation support for standalone temporal deltas. */
public final class DurationFormattingUtils {

    private DurationFormattingUtils() {}

    public record ParsedDurationSpec(String originalInput, TemporalDelta delta, String outputFormat) {
        public Duration duration() {
            return delta.toDuration();
        }
    }

    /** Parses a duration/delta expression and returns the resolved temporal wrapper. */
    public static TemporalValue evaluate(String spec) {
        ParsedDurationSpec parsed = parseSpec(spec);
        return TemporalValue.delta(parsed.originalInput(), parsed.delta(), parsed.outputFormat());
    }

    /** Alias for evaluate(...), useful when callers want value-like naming. */
    public static TemporalValue eval(String spec) {
        return evaluate(spec);
    }

    /** Parses a delta expression and converts it to a Duration using the current time as anchor. */
    public static Duration parseDuration(String spec) {
        return parseSpec(spec).duration();
    }

    /** Parses optional "Duration:"/"Delta:" plus optional trailing "format:"/"pattern:" clauses. */
    public static ParsedDurationSpec parseSpec(String spec) {
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(stripPrefix(spec.trim(), "Duration:"), "Delta:");
        if (raw.isEmpty()) throw new IllegalArgumentException("Delta spec is blank");

        ExtractedClauses clauses = extractClauses(raw);
        TemporalDelta delta = TemporalDelta.parse(clauses.remaining(), clauses.patternTexts());
        return new ParsedDurationSpec(spec, delta, clauses.formatText());
    }

    /** Formats a java.time.Duration as a delta. Null/blank defaults to friendly delta syntax. */
    public static String format(Duration duration, String format) {
        Objects.requireNonNull(duration, "duration");
        return TemporalDelta.fromDuration(duration).render(format);
    }

    /** Formats a TemporalDelta. Null/blank defaults to friendly delta syntax. */
    public static String format(TemporalDelta delta, String format) {
        Objects.requireNonNull(delta, "delta");
        return delta.render(format);
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

    private static ClauseMarker nextClauseMarker(String raw, int from) {
        int formatIdx = indexOfIgnoreCase(raw, "format:", from);
        int patternIdx = indexOfIgnoreCase(raw, "pattern:", from);

        if (formatIdx < 0 && patternIdx < 0) return null;
        if (patternIdx < 0 || (formatIdx >= 0 && formatIdx < patternIdx)) {
            return new ClauseMarker(formatIdx, "format:".length(), true);
        }
        return new ClauseMarker(patternIdx, "pattern:".length(), false);
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length()) ? text.substring(prefix.length()).trim() : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record ExtractedClauses(String remaining, String formatText, List<String> patternTexts) {}
    private record ClauseMarker(int index, int tokenLength, boolean format) {}
}
