package tools.dscode.common.treeparsing.parsedComponents;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ElementType {
    SINGLE_ELEMENT_IN_PHRASE, MULTIPLE_ELEMENTS_IN_PHRASE,
    FIRST_ELEMENT, SECOND_ELEMENT, LAST_ELEMENT,
    PRECEDING_OPERATION, FOLLOWING_OPERATION, NO_OPERATION,
    HTML_TYPE, HTML_ELEMENT, HTML_IFRAME, HTML_SHADOW_ROOT,
    BROWSER_TYPE, ALERT, BROWSER, BROWSER_WINDOW, BROWSER_TAB, URL,
    DATA_TYPE, DATA_ROW, DATA_TABLE,
    VALUE_TYPE, TIME_VALUE, NUMERIC_VALUE, INTEGER_VALUE, DECIMAL_VALUE, TEXT_VALUE,
    RETURNS_VALUE;

    public static final String VALUE_TYPE_MATCH = "InternalValueUnit";

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

    public static final Set<String> TIME_UNITS = Set.of(
            "MILLISECOND",
            "SECOND",
            "MINUTE",
            "HOUR",
            "DAY",
            "WEEK",
            "MONTH",
            "YEAR"
    );

    public static final Set<String> NUMERIC_TYPES = Set.of(
            "DECIMAL",
            "NUMBER",
            "INTEGER"
            );

    public static Set<ElementType> fromString(String raw) {
        Set<ElementType> returnSet = new java.util.HashSet<>();
        String normalized = raw.trim()
                .replace(' ', '_')
                .replaceAll("S$", "")
                .toUpperCase(Locale.ROOT);
        System.out.println("@@normalized: " + normalized);
        System.out.println("@@VALUE_TYPE_MATCH: " + VALUE_TYPE_MATCH);
        System.out.println("@@normalized.startsWith(VALUE_TYPE_MATCH.toUpperCase(Locale.ROOT)): " + normalized.startsWith(VALUE_TYPE_MATCH.toUpperCase(Locale.ROOT)));
        if (normalized.startsWith(VALUE_TYPE_MATCH.toUpperCase(Locale.ROOT))) {
            switch (normalized.replace(VALUE_TYPE_MATCH, "")) {
                case String x when TIME_UNITS.contains(x) -> returnSet.add(TIME_VALUE);
                case String x when NUMERIC_TYPES.contains(x) -> returnSet.add(NUMERIC_VALUE);
                default -> returnSet.add(TEXT_VALUE);
            }
            returnSet.add(VALUE_TYPE);
            return returnSet;
        }


        returnSet.add(LOOKUP.getOrDefault(normalized, HTML_TYPE));

        return returnSet;
    }
}
