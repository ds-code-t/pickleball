package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.mappings.ParsingMap;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getRunningParsingMap;

public class DataProcessingSteps {


    @Given("^non-blank:\\s*((?:#\\d+|first|last)\\s+)?(.*)$")
    public static Object getNonBlank(String position, String key) {
        return getResolvedMatching(position, key, value -> !value.isBlank());
    }

    @Given("^non-empty:\\s*((?:#\\d+|first|last)\\s+)?(.*)$")
    public static Object getNonEmpty(String position, String key) {
        return getResolvedMatching(position, key, value -> !value.isEmpty());
    }

    @Given("^numeric:\\s*((?:#\\d+|first|last)\\s+)?(.*)$")
    public static Object getNumeric(String position, String key) {
        return getResolvedMatching(position, key, DataProcessingSteps::isNumericValue);
    }

    @Given("^integer:\\s*((?:#\\d+|first|last)\\s+)?(.*)$")
    public static Object getInteger(String position, String key) {
        return getResolvedMatching(position, key, DataProcessingSteps::isIntegerValue);
    }

    @Given("^decimal:\\s*((?:#\\d+|first|last)\\s+)?(.*)$")
    public static Object getDecimal(String position, String key) {
        return getResolvedMatching(position, key, DataProcessingSteps::isDecimalValue);
    }


    private static Object getResolvedMatching(String position, String key, Predicate<String> predicate) {
        position = position == null || position.isBlank() ? "last" : position.trim();
        ParsingMap parsingMap= getRunningParsingMap();
        List<Object> list = parsingMap.getList(key);
        if (list == null) {
            return key;
        }
        List<String> matches = list.stream()
                .filter(Objects::nonNull)
                .map(m -> parsingMap.resolveWholeText(String.valueOf(m)))
                .filter(Objects::nonNull)
                .filter(predicate)
                .toList();

        if (matches.isEmpty()) {
            return key;
        }

        if ("first".equalsIgnoreCase(position)) {
            return matches.get(0);
        }

        if ("last".equalsIgnoreCase(position)) {
            return matches.get(matches.size() - 1);
        }

        if (position.startsWith("#")) {
            int index = Integer.parseInt(position.substring(1));
            return index >= 0 && index < matches.size() ? matches.get(index) : key;
        }

        return matches.get(matches.size() - 1);
    }



    @Given("^firstNon-blank\\s*(\\S)(.*)$")
    public static Object getFirstNonBlank(String delimiter, String values) {
        return getFirstResolvedMatching(splitValues(delimiter, values), value -> !value.isBlank());
    }

    @Given("^firstNon-empty\\s*(\\S)(.*)$")
    public static Object getFirstNonEmpty(String delimiter, String values) {
        return getFirstResolvedMatching(splitValues(delimiter, values), value -> !value.isEmpty());
    }

    @Given("^firstNumeric\\s*(\\S)(.*)$")
    public static Object getFirstNumeric(String delimiter, String values) {
        return getFirstResolvedMatching(splitValues(delimiter, values), DataProcessingSteps::isNumericValue);
    }

    @Given("^firstInteger\\s*(\\S)(.*)$")
    public static Object getFirstInteger(String delimiter, String values) {
        return getFirstResolvedMatching(splitValues(delimiter, values), DataProcessingSteps::isIntegerValue);
    }

    @Given("^firstDecimal\\s*(\\S)(.*)$")
    public static Object getFirstDecimal(String delimiter, String values) {
        return getFirstResolvedMatching(splitValues(delimiter, values), DataProcessingSteps::isDecimalValue);
    }


    private static Object getFirstResolvedMatching(String[] values, Predicate<String> predicate) {
        if (values == null) {
            return null;
        }

        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .filter(predicate)
                .findFirst()
                .orElse("");
    }

    private static String[] splitValues(String delimiter, String values) {
        if (values == null) {
            return new String[0];
        }
        return values.split(Pattern.quote(delimiter), -1);
    }



    private static boolean isNumericValue(String value) {
        String s = value.trim();

        // Plain/scientific numeric forms:
        // 123
        // -123
        // 123.45
        // .45
        // 45.
        // 1e3
        // -1.2E-4
        if (s.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            return true;
        }

        // Grouped thousands, optional decimal, optional scientific notation:
        // 1,000
        // 12,345.67
        // 1,000e3
        // 1,234.5E-2
        return s.matches("[+-]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d*)?(?:[eE][+-]?\\d+)?");
    }

    private static boolean isIntegerValue(String value) {
        String s = value.trim();

        // Plain integer or scientific notation with integer mantissa only
        if (s.matches("[+-]?\\d+(?:[eE][+-]?\\d+)?")) {
            return true;
        }

        // Grouped integer, optional scientific notation
        return s.matches("[+-]?\\d{1,3}(?:,\\d{3})+(?:[eE][+-]?\\d+)?");
    }

    private static boolean isDecimalValue(String value) {
        String s = value.trim();

        // Requires a decimal point:
        // 5.
        // .5
        // 5.0
        // 1.2e3
        // -0.5E-2
        if (s.matches("[+-]?(?:\\d+\\.\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            return true;
        }

        // Grouped decimal forms:
        // 1,234.56
        // 1,000.
        return s.matches("[+-]?\\d{1,3}(?:,\\d{3})+\\.\\d*(?:[eE][+-]?\\d+)?");
    }


}