package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.browseroperations.WindowSwitch;
import tools.dscode.common.dataelements.DataElementRegistry;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;

public enum ElementType {
    DEFAULT_STARTING_CONTEXT,
    SINGLE_ELEMENT_IN_PHRASE,
    MULTIPLE_ELEMENTS_IN_PHRASE,
    HTML_TYPE,
    HTML_ELEMENT,
    HTML_IFRAME,
    HTML_SHADOW_ROOT,
    HTML_OPTION,
    HTML_DROPDOWN,
    HTML_LOADING,
    BROWSER_TYPE,
    ALERT,
    BROWSER,
    BROWSER_WINDOW,
    BROWSER_TAB,
    URL,
    DATA_TYPE,
    VALUE_TYPE,
    TIME_VALUE,
    NUMERIC_VALUE,
    INTEGER_VALUE,
    DECIMAL_VALUE,
    TEXT_VALUE,
    KEY_VALUE,
    TIME_DURATION,
    TIME_INSTANCE,
    TIME_RANGE,
    TIME_UNIT,
    RETURNS_VALUE,
    STEP_TYPE,
    STEP_DURATION,
    STEP_REPETITION,
    REGEX_MATCH;

    public static final String VALUE_TYPE_MATCH = "InternalValueUnit";
    public static final String PLACE_HOLDER_MATCH = "InternalPLACEHOLDER";

    private static final Map<String, ElementType> LOOKUP =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(
                            ElementType::key,
                            Function.identity()
                    ));

    private String key() {
        return name();
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

    public static final Set<String> DATA_ELEMENTS =
            DataElementRegistry.DATA_ELEMENTS;

    public static final Set<String> BROWSER_ELEMENTS =
            Set.of("Alert", "Window", "BROWSER", "Browser Tab", "Address Bar");

    public static Set<ElementType> fromString(String raw) {
        Set<ElementType> returnSet = new java.util.HashSet<>();

        if (raw.equals(STARTING_CONTEXT)) {
            returnSet.add(DEFAULT_STARTING_CONTEXT);
            return returnSet;
        }

        if (DataElementRegistry.contains(raw)) {
            returnSet.add(DATA_TYPE);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }

        String singular = raw.replaceAll("s$", "");

        if (singular.matches("^Step\\b.*")) {
            returnSet.add(STEP_TYPE);
            if (singular.contains("Repetition")) {
                returnSet.add(STEP_REPETITION);
                returnSet.add(RETURNS_VALUE);
            } else if (singular.contains("Duration")) {
                returnSet.add(STEP_DURATION);
                returnSet.add(TIME_DURATION);
                returnSet.add(TIME_VALUE);
                returnSet.add(RETURNS_VALUE);
            }
            return returnSet;
        }

        if (singular.equals("Duration")) {
            returnSet.add(TIME_DURATION);
            returnSet.add(TIME_VALUE);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }

        if (singular.equals("Time")) {
            returnSet.add(TIME_INSTANCE);
            returnSet.add(TIME_VALUE);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }

        if (singular.equals("Time Range")) {
            returnSet.add(TIME_RANGE);
            returnSet.add(TIME_VALUE);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }

        if (singular.equals("Match")) {
            returnSet.add(REGEX_MATCH);
            return returnSet;
        }

        if (singular.equals("Loading")) {
            returnSet.add(HTML_LOADING);
            returnSet.add(HTML_TYPE);
            return returnSet;
        }

        if (BROWSER_ELEMENTS.contains(singular)) {
            returnSet.add(BROWSER_TYPE);
        }

        if (singular.equals("Browser")) {
            returnSet.add(BROWSER_TYPE);
            returnSet.add(BROWSER_WINDOW);
            return returnSet;
        }

        if (raw.contains("Window")) {
            String windowNormalized = raw.replaceAll("Windows?", "")
                    .trim()
                    .toUpperCase(Locale.ROOT);
            if (windowNormalized.isBlank()) {
                windowNormalized = "TITLE";
            }

            WindowSwitch.WindowSelectionType windowSelectionType =
                    WindowSwitch.WindowSelectionType.LOOKUP.get(windowNormalized);

            if (windowSelectionType != null) {
                returnSet.add(BROWSER_TYPE);
                returnSet.add(BROWSER_WINDOW);
                return returnSet;
            }
        }

        if (singular.equals("Alert")) {
            returnSet.add(BROWSER_TYPE);
            returnSet.add(ALERT);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }

        if (returnSet.contains(BROWSER_TYPE)) {
            return returnSet;
        }

        String normalized = raw.trim()
                .replace(' ', '_')
                .replaceAll("S$", "")
                .toUpperCase(Locale.ROOT);

        if (normalized.startsWith(VALUE_TYPE_MATCH.toUpperCase(Locale.ROOT))) {
            switch (normalized.substring(VALUE_TYPE_MATCH.length())) {
                case String value when value.equals(KEY_NAME) ->
                        returnSet.add(KEY_VALUE);
                case String value when TIME_UNITS.contains(value) -> {
                    returnSet.add(TIME_DURATION);
                    returnSet.add(TIME_UNIT);
                    returnSet.add(TIME_VALUE);
                    returnSet.add(RETURNS_VALUE);
                }
                case String value when NUMERIC_TYPES.contains(value) -> {
                    returnSet.add(NUMERIC_VALUE);
                    returnSet.add(RETURNS_VALUE);
                }
                default -> {
                    returnSet.add(TEXT_VALUE);
                    returnSet.add(RETURNS_VALUE);
                }
            }
            returnSet.add(VALUE_TYPE);
            return returnSet;
        }

        returnSet.add(LOOKUP.getOrDefault(normalized, HTML_TYPE));
        return returnSet;
    }
}
