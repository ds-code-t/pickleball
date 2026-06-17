package tools.dscode.common.assertions;

import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.TemporalValue;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Consumer-project smoke test for margin-aware ValueWrapper comparisons.
 *
 * This intentionally uses only the published pickleball dependency on the
 * consumer test classpath. Run directly with main(), or compile/run it from
 * Maven after publishing pickleball to mavenLocal.
 */
public class ValueWrapperMarginComparisonSmokeTest {

    private static final BusinessCalendar CALENDAR = BusinessCalendar.fromJson("""
            {
              "TimeZone": "UTC",
              "Open": [
                "MON-FRI 0900-1700"
              ],
              "Closed": [],
              "DefaultOutputPattern": "uuuu-MM-dd HH:mm:ss VV",
              "DefaultOutputZone": "UTC"
            }
            """);

    private static int passed;
    private static int failed;
    private static int testNumber;

    public static void main(String[] args) {
        RunResult result = runAll();
        if (result.failed() > 0) {
            System.exit(1);
        }
    }

    public void testMarginComparisons() {
        RunResult result = runAll();
        if (result.failed() > 0) {
            throw new AssertionError("Margin comparison smoke test failed: " + result.failed() + " failure(s)");
        }
    }

    private static RunResult runAll() {
        passed = 0;
        failed = 0;
        testNumber = 1;

        printHeader("Numeric Margin Comparisons");
        check("numeric equals within margin",
                "equals(5, 7, margin 2)",
                true,
                () -> ValueWrapperComparisons.equals(numeric("5"), numeric("7"), numeric("2")));

        check("numeric equals outside margin",
                "equals(5, 7, margin 1)",
                false,
                () -> ValueWrapperComparisons.equals(numeric("5"), numeric("7"), numeric("1")));

        check("numeric equals with null margin keeps old behavior",
                "equals(5, 7, margin null)",
                false,
                () -> ValueWrapperComparisons.equals(numeric("5"), numeric("7"), null));

        check("numeric greater-than near miss within margin",
                "numericGreaterThan(5, 7, margin 2)",
                true,
                () -> ValueWrapperComparisons.numericGreaterThan(numeric("5"), numeric("7"), numeric("2")));

        check("numeric greater-than outside margin",
                "numericGreaterThan(5, 8, margin 2)",
                false,
                () -> ValueWrapperComparisons.numericGreaterThan(numeric("5"), numeric("8"), numeric("2")));

        check("numeric less-than near miss within margin",
                "numericLessThan(7, 5, margin 2)",
                true,
                () -> ValueWrapperComparisons.numericLessThan(numeric("7"), numeric("5"), numeric("2")));

        check("numeric less-than outside margin",
                "numericLessThan(8, 5, margin 2)",
                false,
                () -> ValueWrapperComparisons.numericLessThan(numeric("8"), numeric("5"), numeric("2")));

        check("numeric greater-than-or-equal near miss within margin",
                "numericGreaterThanOrEqualTo(5, 7, margin 2)",
                true,
                () -> ValueWrapperComparisons.numericGreaterThanOrEqualTo(numeric("5"), numeric("7"), numeric("2")));

        check("numeric less-than-or-equal near miss within margin",
                "numericLessThanOrEqualTo(7, 5, margin 2)",
                true,
                () -> ValueWrapperComparisons.numericLessThanOrEqualTo(numeric("7"), numeric("5"), numeric("2")));

        printHeader("Temporal Date-Time Margin Comparisons");
        check("date-time equals within duration margin",
                "equals(2026-02-02T10:00:00Z, 2026-02-02T10:00:02Z, margin 2 seconds)",
                true,
                () -> ValueWrapperComparisons.equals(dateTime("2026-02-02T10:00:00Z"), dateTime("2026-02-02T10:00:02Z"), duration("2 seconds")));

        check("date-time equals outside duration margin",
                "equals(2026-02-02T10:00:00Z, 2026-02-02T10:00:02Z, margin 1 second)",
                false,
                () -> ValueWrapperComparisons.equals(dateTime("2026-02-02T10:00:00Z"), dateTime("2026-02-02T10:00:02Z"), duration("1 second")));

        check("date-time greater-than near miss within margin",
                "numericGreaterThan(2026-02-02T10:00:00Z, 2026-02-02T10:00:02Z, margin 2 seconds)",
                true,
                () -> ValueWrapperComparisons.numericGreaterThan(dateTime("2026-02-02T10:00:00Z"), dateTime("2026-02-02T10:00:02Z"), duration("2 seconds")));

        check("date-time greater-than outside margin",
                "numericGreaterThan(2026-02-02T10:00:00Z, 2026-02-02T10:00:03Z, margin 2 seconds)",
                false,
                () -> ValueWrapperComparisons.numericGreaterThan(dateTime("2026-02-02T10:00:00Z"), dateTime("2026-02-02T10:00:03Z"), duration("2 seconds")));

        check("date-time less-than near miss within margin",
                "numericLessThan(2026-02-02T10:00:02Z, 2026-02-02T10:00:00Z, margin 2 seconds)",
                true,
                () -> ValueWrapperComparisons.numericLessThan(dateTime("2026-02-02T10:00:02Z"), dateTime("2026-02-02T10:00:00Z"), duration("2 seconds")));

        check("date-time greater-than-or-equal near miss within margin",
                "numericGreaterThanOrEqualTo(2026-02-02T10:00:00Z, 2026-02-02T10:00:02Z, margin 2 seconds)",
                true,
                () -> ValueWrapperComparisons.numericGreaterThanOrEqualTo(dateTime("2026-02-02T10:00:00Z"), dateTime("2026-02-02T10:00:02Z"), duration("2 seconds")));

        check("date-time less-than-or-equal near miss within margin",
                "numericLessThanOrEqualTo(2026-02-02T10:00:02Z, 2026-02-02T10:00:00Z, margin 2 seconds)",
                true,
                () -> ValueWrapperComparisons.numericLessThanOrEqualTo(dateTime("2026-02-02T10:00:02Z"), dateTime("2026-02-02T10:00:00Z"), duration("2 seconds")));

        check("date-time equals with numeric nanosecond margin",
                "equals(1970-01-01T00:00:00Z, 1970-01-01T00:00:00.000000001Z, margin 1)",
                true,
                () -> ValueWrapperComparisons.equals(dateTime("1970-01-01T00:00:00Z"), dateTime("1970-01-01T00:00:00.000000001Z"), numeric("1")));

        printHeader("Temporal Duration Margin Comparisons");
        check("duration equals within duration margin",
                "equals(5 minutes, 7 minutes, margin 2 minutes)",
                true,
                () -> ValueWrapperComparisons.equals(duration("5 minutes"), duration("7 minutes"), duration("2 minutes")));

        check("duration equals outside duration margin",
                "equals(5 minutes, 7 minutes, margin 119 seconds)",
                false,
                () -> ValueWrapperComparisons.equals(duration("5 minutes"), duration("7 minutes"), duration("119 seconds")));

        check("duration greater-than near miss within margin",
                "numericGreaterThan(5 minutes, 7 minutes, margin 2 minutes)",
                true,
                () -> ValueWrapperComparisons.numericGreaterThan(duration("5 minutes"), duration("7 minutes"), duration("2 minutes")));

        check("duration less-than near miss within margin",
                "numericLessThan(7 minutes, 5 minutes, margin 2 minutes)",
                true,
                () -> ValueWrapperComparisons.numericLessThan(duration("7 minutes"), duration("5 minutes"), duration("2 minutes")));

        printHeader("AssertionOperations-Style Reducer Calls");
        check("reducer equality lambda receives margin",
                "ValueWrapperCompareReducer.eval((left, right) -> equals(left, right, 2), [5], [7])",
                true,
                () -> ValueWrapperCompareReducer.eval(
                        (left, right) -> ValueWrapperComparisons.equals(left, right, numeric("2")),
                        List.of(numeric("5")),
                        List.of(numeric("7")),
                        Collections.emptySet()
                ));

        check("reducer greater-than lambda receives margin",
                "ValueWrapperCompareReducer.eval((left, right) -> numericGreaterThan(left, right, 2), [5], [7])",
                true,
                () -> ValueWrapperCompareReducer.eval(
                        (left, right) -> ValueWrapperComparisons.numericGreaterThan(left, right, numeric("2")),
                        List.of(numeric("5")),
                        List.of(numeric("7")),
                        Collections.emptySet()
                ));

        printHeader("Summary");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        return new RunResult(passed, failed);
    }

    private static ValueWrapper numeric(String value) {
        return ValueWrapper.createValueWrapper(value);
    }

    private static ValueWrapper dateTime(String spec) {
        return typedValueWrapper(CALENDAR.eval(spec), ValueWrapper.ValueTypes.DATE_TIME);
    }

    private static ValueWrapper duration(String spec) {
        return typedValueWrapper(TemporalValue.duration(spec), ValueWrapper.ValueTypes.DURATION);
    }

    private static ValueWrapper typedValueWrapper(Object value, ValueWrapper.ValueTypes type) {
        try {
            Constructor<ValueWrapper> constructor = ValueWrapper.class.getDeclaredConstructor(Object.class, ValueWrapper.ValueTypes.class);
            constructor.setAccessible(true);
            return constructor.newInstance(value, type);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to create typed ValueWrapper for smoke test", e);
        }
    }

    private static void check(String name, String input, boolean expected, Supplier<Boolean> actualSupplier) {
        String actualText;
        boolean ok = false;

        try {
            Boolean actual = actualSupplier.get();
            actualText = String.valueOf(actual);
            ok = Objects.equals(expected, actual);
        } catch (Throwable t) {
            actualText = "THREW " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        printCase(name, input, expected, actualText, ok);
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println(title);
        System.out.println("================================================================================");
    }

    private static void printCase(String name, String input, boolean expected, String actual, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }

        System.out.println();
        System.out.printf("Test %02d - %s%n", testNumber++, name);
        System.out.println("Input:");
        System.out.println(input);
        System.out.println("Expected:");
        System.out.println(expected);
        System.out.println("Actual:");
        System.out.println(actual);
        System.out.println("Result: " + (ok ? "PASS" : "FAIL"));
    }

    private record RunResult(int passed, int failed) {
    }
}
