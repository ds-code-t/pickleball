package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.browseroperations.WindowSwitch;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.util.TableUtils.CELL_KEY;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.HEADER_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.VALUE_KEY;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;

public enum ElementType {
    DEFAULT_STARTING_CONTEXT,
    SINGLE_ELEMENT_IN_PHRASE, MULTIPLE_ELEMENTS_IN_PHRASE,
    FIRST_ELEMENT, SECOND_ELEMENT, LAST_ELEMENT,
    PRECEDING_OPERATION, FOLLOWING_OPERATION, NO_OPERATION,
    HTML_TYPE, HTML_ELEMENT, HTML_IFRAME, HTML_SHADOW_ROOT,
    HTML_OPTION, HTML_DROPDOWN, HTML_LOADING,
    BROWSER_TYPE, ALERT, BROWSER, BROWSER_WINDOW, BROWSER_TAB, URL,
    DATA_TYPE,
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


    public static final Set<String> DATA_ELEMENTS =
            Set.of(DOCSTRING_KEY, TABLE_KEY, ROW_KEY, CELL_KEY, HEADER_KEY , VALUE_KEY);

    public static final Set<String> BROWSER_ELEMENTS =
            Set.of("Alert", "Window", "BROWSER", "Browser Tab", "Address Bar");

    public static Set<ElementType> fromString(String raw) {
        Set<ElementType> returnSet = new java.util.HashSet<>();
        if(raw.equals(STARTING_CONTEXT))
        {
            returnSet.add(DEFAULT_STARTING_CONTEXT);
            return returnSet;
        }

        String singular = raw.replaceAll("s$", "");
        if (singular.equals("Loading")) {
            returnSet.add(HTML_LOADING);
            returnSet.add(HTML_TYPE);
            return returnSet;
        }
        if(DATA_ELEMENTS.contains(singular)) {
            returnSet.add(DATA_TYPE);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }
        if(BROWSER_ELEMENTS.contains(singular)) {
            returnSet.add(BROWSER_TYPE);
        }
        if (singular.equals("Browser")) {
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

        if (singular.equals("Alert")) {
            returnSet.add(BROWSER_TYPE);
            returnSet.add(ALERT);
            returnSet.add(RETURNS_VALUE);
            return returnSet;
        }
        if(returnSet.contains(BROWSER_TYPE)) {
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
