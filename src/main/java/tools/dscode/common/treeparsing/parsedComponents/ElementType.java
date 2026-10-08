package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.browseroperations.WindowSwitch;
import tools.dscode.common.dataelements.DataElementRegistry;

import java.util.Locale;
import java.util.Set;

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

    public static final String RESERVED_STEP_MESSAGE_TAIL =
            "Names starting with 'Step' are reserved for step-state elements; supported: Step Repetition, Step Duration.";

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

        String trimmed = raw.trim().replaceAll("\\s+", " ");
        String singular = trimmed.replaceAll("s$", "");

        if (singular.matches("(?i)^Step\\b.*")) {
            if (isStepRepetition(singular)) {
                returnSet.add(STEP_TYPE);
                returnSet.add(STEP_REPETITION);
                returnSet.add(RETURNS_VALUE);
                return returnSet;
            }
            if (isStepDuration(singular)) {
                returnSet.add(STEP_TYPE);
                returnSet.add(STEP_DURATION);
                returnSet.add(TIME_DURATION);
                returnSet.add(TIME_VALUE);
                returnSet.add(RETURNS_VALUE);
                return returnSet;
            }
            throw new IllegalArgumentException(reservedStepNameMessage(trimmed));
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

        returnSet.add(HTML_TYPE);
        return returnSet;
    }

    /**
     * Reason a custom {@code ExecutionDictionary} category must not replace this
     * name, or null when the name is an ordinary HTML category (including
     * built-ins such as {@code Button} and {@code Loading}).
     */
    public static String reservationWarning(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        String singular = trimmed.replaceAll("s$", "");
        if (singular.matches("(?i)^Step\\b.*")) {
            if (isStepRepetition(singular) || isStepDuration(singular)) {
                return "'" + trimmed + "' is reserved for step-state elements and cannot be replaced by a custom element category.";
            }
            return reservedStepNameMessage(trimmed);
        }
        Set<ElementType> types = fromString(trimmed);
        if (types.contains(DATA_TYPE)) {
            return "'" + trimmed + "' is reserved for Data Elements.";
        }
        if (types.contains(TIME_VALUE)) {
            return "'" + trimmed + "' is reserved for time values (Time, Time Range, Duration).";
        }
        if (types.contains(REGEX_MATCH)) {
            return "'" + trimmed + "' is reserved for Match.";
        }
        if (types.contains(BROWSER_TYPE)) {
            return "'" + trimmed + "' is reserved for browser, alert, and window elements.";
        }
        return null;
    }

    public static String reservedStepNameMessage(String name) {
        return "'" + name + "' is a reserved element name. " + RESERVED_STEP_MESSAGE_TAIL;
    }

    private static boolean isStepRepetition(String singular) {
        return singular.equalsIgnoreCase("Step Repetition");
    }

    private static boolean isStepDuration(String singular) {
        return singular.equalsIgnoreCase("Step Duration");
    }
}
