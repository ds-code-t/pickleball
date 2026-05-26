package tools.dscode.common.reporting;

import org.apache.logging.log4j.core.pattern.AnsiEscape;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static tools.dscode.testengine.PickleballRunner.LOG_LEVEL;

public final class WorkBookConsolePrinter {

    private static final String RESET = "\u001B[0m";

    private static final String RED = "RED";
    private static final String GREEN = "GREEN";
    private static final String YELLOW = "YELLOW";
    private static final String BLUE = "BLUE";
    private static final String BOLD = "BOLD";
    private static final String BLINK = "BLINK";
    private static final String UNDERLINE = "UNDERLINE";
    private static final String DIM = "DIM";
    private static final String BG_BLACK = "BG_BLACK";

    private static final String FRAME_LINE =
            "================================================================================";
    private static final String STAR_LINE = "************";

    public enum PrintStyle {
        PLAIN,
        LEVEL,
        HEADER,
        DIM
    }

    private WorkBookConsolePrinter() {
    }

    public static void printToConsole(WorkBook book) {
        printToConsole(book, true);
    }

    public static void printToConsole(WorkBook book, boolean useAnsiColors) {
        Objects.requireNonNull(book, "book");

        System.out.println();
        System.out.println(FRAME_LINE);
        System.out.println("WORKBOOK REPORT");
        System.out.println(FRAME_LINE);
        System.out.println();

        List<?> snapshots = snapshotAllSheetsReflective(book);
        if (snapshots.isEmpty()) {
            System.out.println("(no sheets)");
            System.out.println();
            System.out.println(FRAME_LINE);
            return;
        }

        for (Object snap : snapshots) {
            String sheetName = (String) getField(snap, "sheetName");

            @SuppressWarnings("unchecked")
            List<String> headers = (List<String>) getField(snap, "headers");

            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) getField(snap, "rows");

            boolean includeRowKeyColumn = (boolean) getField(snap, "includeRowKeyColumn");
            String rowKeyHeaderName = (String) getField(snap, "rowKeyHeaderName");

            System.out.println();
            System.out.println("=== SHEET: " + sheetName + " ===");
            System.out.println();

            if (headers == null || headers.isEmpty()) {
                System.out.println("(empty)");
                continue;
            }

            int statusCol = findColumn(headers, "STATUS");
            int cols = headers.size();

            List<List<String>> grid = new ArrayList<>();
            grid.add(new ArrayList<>(headers));

            int scenarioCount = rows.size();
            LinkedHashMap<String, Integer> statusCounts = new LinkedHashMap<>();

            for (Object rowObj : rows) {
                List<String> rendered =
                        renderRow(headers, rowObj, includeRowKeyColumn, rowKeyHeaderName);
                grid.add(rendered);

                if (statusCol >= 0) {
                    String raw = safeGet(rendered, statusCol).trim();
                    if (!raw.isBlank()) {
                        String key = findExistingKeyIgnoreCase(statusCounts, raw);
                        if (key == null) key = raw;
                        statusCounts.put(key, statusCounts.getOrDefault(key, 0) + 1);
                    }
                }
            }

            String summary = buildSummaryLine(scenarioCount, statusCol, statusCounts);

            System.out.println();
            printStyled(STAR_LINE, useAnsiColors, BLUE, BLINK);
            printStyled(summary, useAnsiColors, BLUE, BOLD);
            printStyled(STAR_LINE, useAnsiColors, BLUE, BLINK);
            System.out.println();

            int[] widths = computeWidths(grid, cols);

            for (int r = 0; r < grid.size(); r++) {
                List<String> row = grid.get(r);

                String color = null;
                if (r > 0 && statusCol >= 0) {
                    String status = safeGet(row, statusCol).trim();
                    if ("FAILED".equalsIgnoreCase(status)) color = RED;
                    else if ("PASSED".equalsIgnoreCase(status)) color = GREEN;
                }

                String line = formatRow(row, cols, widths);

                if (color == null) {
                    System.out.println(line);
                } else {
                    printStyled(line, useAnsiColors, color);
                }
            }
        }

        System.out.println();
        System.out.println(FRAME_LINE);
        System.out.println("END OF WORKBOOK REPORT");
        System.out.println(FRAME_LINE);
        System.out.println();
    }

    // ---------------------------------------------------------
    // ENTRY PRINTING
    // ---------------------------------------------------------

    public static Entry print(Entry entry) {
        return print(entry, PrintStyle.LEVEL, true);
    }

    public static Entry print(Entry entry, PrintStyle printStyle) {
        return print(entry, printStyle, true);
    }

    public static Entry print(Entry entry, PrintStyle printStyle, boolean useAnsiColors) {
        if (entry == null) return null;

        Level level = entry.level;
        if (level != null && LOG_LEVEL != null && level.ordinal() < LOG_LEVEL.ordinal()) {
            return entry;
        }

        String text = switch (printStyle) {
            case LEVEL -> level == null
                    ? safe(entry.text)
                    : "[" + level + "] " + safe(entry.text);
            case PLAIN, HEADER, DIM -> safe(entry.text);
        };

        printLine(entry.indentedSpace(), text, useAnsiColors, stylesFor(entry, printStyle));
        return entry;
    }

    private static String[] stylesFor(Entry entry, PrintStyle printStyle) {
        return switch (printStyle) {
            case PLAIN -> new String[0];
            case HEADER -> new String[]{BG_BLACK, YELLOW, BOLD, UNDERLINE};
            case DIM -> new String[]{YELLOW, DIM};
            case LEVEL -> stylesForLevel(entry.level);
        };
    }

    private static String[] stylesForLevel(Level level) {
        if (level == null) return new String[0];

        return switch (level) {
            case INFO -> new String[]{BLUE};
            case WARN -> new String[]{YELLOW, BOLD};
            case ERROR -> new String[]{RED, BOLD};
            case DEBUG -> new String[]{BLUE, BOLD};
            case TRACE -> new String[]{BLUE};
        };
    }

    // ---------------------------------------------------------
    // CENTRAL ANSI PRINTING
    // ---------------------------------------------------------

    public static void printStyled(String text, String... styles) {
        printStyled(text, true, styles);
    }

    public static void printStyled(String text, boolean useAnsiColors, String... styles) {
        if (text == null) {
            System.out.println();
            return;
        }

        int split = firstNonIndentWhitespaceIndex(text);
        printLine(text.substring(0, split), text.substring(split), useAnsiColors, styles);
    }

    private static void printLine(
            String indent,
            String text,
            boolean useAnsiColors,
            String... styles
    ) {
        indent = indent == null ? "" : indent;
        text = text == null ? "" : text;

        int textIndentEnd = firstNonIndentWhitespaceIndex(text);
        if (textIndentEnd > 0) {
            indent += text.substring(0, textIndentEnd);
            text = text.substring(textIndentEnd);
        }

        if (!useAnsiColors || styles == null || styles.length == 0) {
            System.out.println(indent + text);
            return;
        }

        System.out.println(indent + AnsiEscape.createSequence(styles) + text + RESET);
    }

    private static int firstNonIndentWhitespaceIndex(String text) {
        int i = 0;

        while (i < text.length()
                && Character.isWhitespace(text.charAt(i))
                && text.charAt(i) != '\n'
                && text.charAt(i) != '\r') {
            i++;
        }

        return i;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    // ---------------------------------------------------------
    // WORKBOOK HELPERS
    // ---------------------------------------------------------

    private static String buildSummaryLine(
            int scenarioCount,
            int statusCol,
            LinkedHashMap<String, Integer> statusCounts
    ) {
        StringBuilder sb = new StringBuilder("Ran ")
                .append(scenarioCount)
                .append(" SCENARIOS");

        if (statusCol >= 0 && !statusCounts.isEmpty()) {
            for (Map.Entry<String, Integer> e : statusCounts.entrySet()) {
                sb.append(", ").append(e.getValue()).append(" ").append(e.getKey());
            }
        }

        return sb.toString();
    }

    private static String findExistingKeyIgnoreCase(
            LinkedHashMap<String, Integer> map,
            String candidate
    ) {
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(candidate)) return k;
        }
        return null;
    }

    private static List<String> renderRow(
            List<String> headers,
            Object rowObj,
            boolean includeRowKeyColumn,
            String rowKeyHeaderName
    ) {
        String rowKey = (String) getField(rowObj, "rowKey");

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) getField(rowObj, "values");

        List<String> out = new ArrayList<>(headers.size());

        for (String h : headers) {
            if (includeRowKeyColumn && Objects.equals(h, rowKeyHeaderName)) {
                out.add(rowKey == null ? "" : rowKey);
            } else {
                Object v = values == null ? null : values.get(h);
                out.add(v == null ? "" : String.valueOf(v));
            }
        }

        return out;
    }

    private static int findColumn(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (name.equals(headers.get(i))) return i;
        }
        return -1;
    }

    private static int[] computeWidths(List<List<String>> grid, int cols) {
        int[] widths = new int[cols];

        for (List<String> row : grid) {
            for (int c = 0; c < cols; c++) {
                widths[c] = Math.max(widths[c], safeGet(row, c).length());
            }
        }

        for (int c = 0; c < cols; c++) {
            widths[c] = Math.max(widths[c], 3);
        }

        return widths;
    }

    private static String formatRow(List<String> row, int cols, int[] widths) {
        StringBuilder sb = new StringBuilder("|");

        for (int c = 0; c < cols; c++) {
            String v = safeGet(row, c);

            sb.append(" ")
                    .append(v)
                    .append(" ".repeat(widths[c] - v.length()))
                    .append(" |");
        }

        return sb.toString();
    }

    private static String safeGet(List<String> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size() || row.get(idx) == null) {
            return "";
        }

        return row.get(idx);
    }

    private static List<?> snapshotAllSheetsReflective(WorkBook book) {
        try {
            Method m = WorkBook.class.getDeclaredMethod("snapshotAllSheets");
            m.setAccessible(true);

            Object out = m.invoke(book);
            return out instanceof List<?> list ? list : List.of();
        } catch (Exception e) {
            throw new RuntimeException("Unable to snapshot WorkBook", e);
        }
    }

    private static Object getField(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to read field '" + fieldName + "' from " + target.getClass(), e
            );
        }
    }
}