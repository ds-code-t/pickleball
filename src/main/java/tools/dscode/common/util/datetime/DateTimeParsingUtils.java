package tools.dscode.common.util.datetime;

import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class DateTimeParsingUtils {

    private DateTimeParsingUtils() {}

    /**
     * Strict parsing:
     * - tries each formatter
     * - requires FULL string consumption (no partial matches)
     * - returns the best temporal type (ZDT, ODT, LDT, LD, LT)
     */
    static Object tryParse(List<DateTimeFormatter> fmts, String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;

        for (DateTimeFormatter f : fmts) {
            try {
                ParsePosition pos = new ParsePosition(0);

                TemporalAccessor ta = f.parseUnresolved(s, pos);
                if (ta == null) continue;

                // FULL CONSUMPTION REQUIRED
                if (pos.getErrorIndex() >= 0) continue;
                if (pos.getIndex() != s.length()) continue;

                // Resolve to real types (prefer more specific types first)
                TemporalAccessor resolved = f.parse(s);

                // parseBest gives us the “most specific” supported type
                Object best = f.parseBest(
                        s,
                        ZonedDateTime::from,
                        OffsetDateTime::from,
                        LocalDateTime::from,
                        LocalDate::from,
                        LocalTime::from
                );
                return best;
            } catch (DateTimeParseException ignored) {
                // try next formatter
            } catch (Exception ignored) {
                // formatter might not support parseBest for this text; try next
            }
        }
        return null;
    }

/**
     * Convenience overload: builds {@link DateTimeFormatter}s from patterns (STRICT) and delegates.
     */
    public static Object tryParse(String text, String... patterns) {
        if (patterns == null || patterns.length == 0) return null;

        List<DateTimeFormatter> fs = Arrays.stream(patterns)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(p -> DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT))
                .collect(Collectors.toList());

        return tryParse(fs, text);
    }
}
