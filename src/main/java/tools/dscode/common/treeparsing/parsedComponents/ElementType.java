package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.browseroperations.WindowSwitch;

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
    HTML_OPTION, HTML_DROPDOWN, HTML_LOADING,
    BROWSER_TYPE, ALERT, BROWSER, BROWSER_WINDOW, BROWSER_TAB, URL,
    DATA_TYPE, DATA_ROW, DATA_TABLE,
    VALUE_TYPE, TIME_VALUE, NUMERIC_VALUE, INTEGER_VALUE, DECIMAL_VALUE, TEXT_VALUE, KEY_VALUE,
    RETURNS_VALUE;

    public static final String VALUE_TYPE_MATCH = "InternalValueUnit";
    public static final String PLACE_HOLDER_MATCH = "InternalPLACEHOLDER";


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

    public static final String KEY_NAME = "KEYNAME";


    public static Set<ElementType> fromString(String raw) {
        Set<ElementType> returnSet = new java.util.HashSet<>();
        if (raw.equals("Loading")) {
            returnSet.add(HTML_LOADING);
            returnSet.add(HTML_TYPE);
            return returnSet;
        }

        if (raw.equals("Browser")) {
            returnSet.add(BROWSER_TYPE);
            returnSet.add(BROWSER_WINDOW);
            return returnSet;
        }

        if (raw.contains("Window")) {
            String windowNormalized = raw.replaceAll("Windows?", "").trim().toUpperCase(Locale.ROOT);
            if(windowNormalized.isBlank())
            {
                windowNormalized = "TITLE";
            }

            WindowSwitch.WindowSelectionType windowSelectionType = WindowSwitch.WindowSelectionType.LOOKUP.get(windowNormalized);

            if (windowSelectionType != null) {
                returnSet.add(BROWSER_TYPE);
                returnSet.add(BROWSER_WINDOW);
                return returnSet;
            }
        }


        if (raw.matches("Alerts?")) {
            returnSet.add(BROWSER_TYPE);
            returnSet.add(ALERT);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }

        String normalized = raw.trim()
                .replace(' ', '_')
                .replaceAll("S$", "")
                .toUpperCase(Locale.ROOT);
        if (normalized.startsWith(VALUE_TYPE_MATCH.toUpperCase(Locale.ROOT))) {
            switch (normalized.substring(VALUE_TYPE_MATCH.length())) {
                case String x when x.equals(KEY_NAME) -> returnSet.add(KEY_VALUE);
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
