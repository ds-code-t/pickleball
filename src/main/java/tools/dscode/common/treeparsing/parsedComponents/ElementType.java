package tools.dscode.common.treeparsing.parsedComponents;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ElementType {
    HTML, ALERT, BROWSER, BROWSER_WINDOW, BROWSER_TAB, URL, VALUE, DATA_ROW, DATA_TABLE;

    private static final Map<String, ElementType> LOOKUP =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(
                            ElementType::key,
                            Function.identity()
                    ));

    // canonical key for matching (covers spaces, underscores, plurals, case)
    private String key() {
        return name(); // enum constant name is the canonical key
    }

    public static Optional<ElementType> fromString(String raw) {
        if (raw == null) return Optional.empty();

        String normalized = raw.trim()
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        // naive plural handling: drop trailing 'S' (customize if needed)
        if (normalized.endsWith("S")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return Optional.ofNullable(LOOKUP.get(normalized));
    }
}
