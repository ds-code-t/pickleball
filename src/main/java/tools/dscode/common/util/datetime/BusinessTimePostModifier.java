package tools.dscode.common.util.datetime;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class BusinessTimePostModifier {

    private BusinessTimePostModifier() {}

    static Parsed split(String spec) {
        Objects.requireNonNull(spec, "spec");
        String[] rawParts = spec.split("\\|", -1);
        String base = rawParts[0].trim();
        if (base.isEmpty()) throw new IllegalArgumentException("Missing base time in spec: \"" + spec + "\"");

        List<String> modifiers = new ArrayList<>();
        for (int i = 1; i < rawParts.length; i++) {
            String modifier = rawParts[i].trim();
            if (modifier.isEmpty()) {
                throw new IllegalArgumentException("Blank date/time pipe modifier in spec: \"" + spec + "\"");
            }
            modifiers.add(modifier);
        }

        return new Parsed(base, List.copyOf(modifiers));
    }

    static Object apply(BusinessTime start, List<String> modifiers) {
        Object cur = Objects.requireNonNull(start, "start");

        for (String modifier : modifiers) {
            if (!(cur instanceof BusinessTime bt)) {
                throw new IllegalArgumentException("Date/time pipe modifier cannot follow scalar result: " + modifier);
            }
            cur = applyOne(bt, modifier);
            if (cur == null) return null;
        }

        return cur;
    }

    private static Object applyOne(BusinessTime bt, String rawModifier) {
        String modifier = normalize(rawModifier);
        BusinessCalendar cal = bt.calendar();

        return switch (modifier) {
            case "next open" -> bt.nextOpen();
            case "previous open" -> bt.lastOpen();
            case "next closed" -> bt.withValue(cal.nextClosed(bt.value()));
            case "previous closed" -> bt.withValue(cal.lastClosed(bt.value()));
            case "next opening" -> bt.withValue(cal.nextOpening(bt.value()));
            case "previous opening" -> bt.withValue(cal.previousOpening(bt.value()));
            case "next closing" -> bt.withValue(cal.nextClosing(bt.value()));
            case "previous closing" -> bt.withValue(cal.previousClosing(bt.value()));
            case "opening time" -> bt.withValue(cal.openingTime(bt.value()));
            case "closing time" -> bt.withValue(cal.closingTime(bt.value()));
            case "status" -> bt.status().name().toLowerCase(Locale.ROOT);
            case "is open" -> bt.isOpen();
            case "is closed" -> bt.isClosed();
            case "is business day" -> cal.isBusinessDate(bt.value().toLocalDate());
            case "is non-business day", "is non business day" -> !cal.isBusinessDate(bt.value().toLocalDate());
            default -> throw new IllegalArgumentException("Unknown date/time pipe modifier: \"" + rawModifier + "\"");
        };
    }

    private static String normalize(String modifier) {
        return modifier.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
    }

    record Parsed(String baseSpec, List<String> modifiers) {}
}
