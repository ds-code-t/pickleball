package tools.dscode.control.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK-only Examples-row selector shared by Pickleball {@code pkb_example} and
 * the Workbench player. Tokens are a positive integer, {@code table.row}, or
 * an inclusive overall {@code low-high} range. No leading zeros.
 */
public final class ExampleRowSelector {
    private static final Pattern INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern TABLE_ROW = Pattern.compile("([1-9][0-9]*)\\.([1-9][0-9]*)");
    private static final Pattern RANGE = Pattern.compile("([1-9][0-9]*)-([1-9][0-9]*)");

    private final List<Clause> clauses;

    private ExampleRowSelector(List<Clause> clauses) {
        this.clauses = List.copyOf(clauses);
    }

    public static boolean isInactive(String expression) {
        return expression == null || expression.isBlank();
    }

    public static ExampleRowSelector parse(String expression) {
        if (isInactive(expression)) {
            throw invalid("");
        }
        String[] tokens = expression.trim().split("\\s+");
        List<Clause> clauses = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                throw invalid("");
            }
            clauses.add(parseToken(token));
        }
        if (clauses.isEmpty()) {
            throw invalid("");
        }
        return new ExampleRowSelector(clauses);
    }

    public boolean matches(int overall, int table, int row) {
        for (Clause clause : clauses) {
            if (clause.matches(overall, table, row)) {
                return true;
            }
        }
        return false;
    }

    /**
     * First value-row match in source order. Table numbers and value rows are
     * 1-based. The header row of each Examples table is not a value row.
     * Returns 0 when nothing matches.
     */
    public static int firstOverallIndex(List<String> lines, int startInclusive, int endExclusive, String expression) {
        if (isInactive(expression) || lines == null || lines.isEmpty()) {
            return 0;
        }
        ExampleRowSelector selector = parse(expression);
        int table = 0;
        int overall = 0;
        int rowInTable = 0;
        boolean headerSeen = false;
        int from = Math.max(0, startInclusive);
        int to = Math.min(lines.size(), endExclusive < 0 ? lines.size() : endExclusive);
        for (int i = from; i < to; i++) {
            String trimmed = lines.get(i) == null ? "" : lines.get(i).strip();
            if (startsWithKeyword(trimmed, "Examples:") || startsWithKeyword(trimmed, "Example:")) {
                table++;
                headerSeen = false;
                rowInTable = 0;
                continue;
            }
            if (!trimmed.startsWith("|")) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            rowInTable++;
            overall++;
            if (selector.matches(overall, table, rowInTable)) {
                return overall;
            }
        }
        // A normal scenario has no Examples table. It is one implicit row.
        if (overall == 0 && table == 0 && selector.matches(1, 1, 1)) {
            return 1;
        }
        return 0;
    }

    private static boolean startsWithKeyword(String trimmed, String keyword) {
        return trimmed.startsWith(keyword) || trimmed.startsWith(keyword.toLowerCase());
    }

    private static Clause parseToken(String token) {
        if (INTEGER.matcher(token).matches()) {
            return new Overall(parsePositive(token));
        }
        Matcher table = TABLE_ROW.matcher(token);
        if (table.matches()) {
            return new TableRow(parsePositive(table.group(1)), parsePositive(table.group(2)));
        }
        Matcher range = RANGE.matcher(token);
        if (range.matches()) {
            int low = parsePositive(range.group(1));
            int high = parsePositive(range.group(2));
            if (low > high) {
                throw invalid(token);
            }
            return new Range(low, high);
        }
        throw invalid(token);
    }

    private static int parsePositive(String token) {
        try {
            int value = Integer.parseInt(token);
            if (value <= 0) {
                throw invalid(token);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw invalid(token);
        }
    }

    private static IllegalArgumentException invalid(String token) {
        return new IllegalArgumentException(
                "Invalid pkb_example token '" + token + "'. "
                        + "Expected a positive integer, table.row, or low-high range of positive integers "
                        + "with no leading zeros."
        );
    }

    private interface Clause {
        boolean matches(int overall, int table, int row);
    }

    private record Overall(int index) implements Clause {
        @Override
        public boolean matches(int overall, int table, int row) {
            return overall == index;
        }
    }

    private record TableRow(int tableNumber, int rowInTable) implements Clause {
        @Override
        public boolean matches(int overall, int table, int row) {
            return table == tableNumber && row == rowInTable;
        }
    }

    private record Range(int low, int high) implements Clause {
        @Override
        public boolean matches(int overall, int table, int row) {
            return overall >= low && overall <= high;
        }
    }
}
