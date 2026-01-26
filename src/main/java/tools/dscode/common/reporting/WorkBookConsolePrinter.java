package tools.dscode.common.reporting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Prints WorkBook contents to the console (in-memory; does NOT require writing the .xlsx).
 *
 * If a "STATUS" header exists:
 *  - STATUS == "FAILED" => row printed in red
 *  - STATUS == "PASSED" => row printed in green
 *
 * Also prints a highlighted summary line:
 *  "Ran N SCENARIOS, X <STATUS1>, Y <STATUS2>, ..."
 */
public final class WorkBookConsolePrinter {

    // ANSI styles
    private static final String RESET  = "\u001B[0m";
    private static final String RED    = "\u001B[31m";
    private static final String GREEN  = "\u001B[32m";
    private static final String BLUE   = "\u001B[34m";
    private static final String BOLD   = "\u001B[1m";
    private static final String BLINK  = "\u001B[5m";

    private static final String FRAME_LINE =
            "================================================================================";

    private static final String STAR_LINE = "************";

    private WorkBookConsolePrinter() {}

    public static void printToConsole(WorkBook book) {
        printToConsole(book, true);
    }

    public static void printToConsole(WorkBook book, boolean useAnsiColors) {
        Objects.requireNonNull(book, "book");

        // ---- global spacer + frame ----
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

            // Build display grid
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

            // ---- blinking blue summary block ----
            String summary = buildSummaryLine(scenarioCount, statusCol, statusCounts);

            System.out.println();
            if (useAnsiColors) {
                System.out.println(BLUE + BLINK + STAR_LINE + RESET);
                System.out.println(BLUE + BOLD + summary + RESET);
                System.out.println(BLUE + BLINK + STAR_LINE + RESET);
            } else {
                System.out.println(STAR_LINE);
                System.out.println(summary);
                System.out.println(STAR_LINE);
            }
            System.out.println();

            int[] widths = computeWidths(grid, cols);

            // ---- table ----
            for (int r = 0; r < grid.size(); r++) {
                List<String> row = grid.get(r);

                String color = null;
                if (r > 0 && statusCol >= 0) {
                    String status = safeGet(row, statusCol).trim();
                    if ("FAILED".equalsIgnoreCase(status)) color = RED;
                    else if ("PASSED".equalsIgnoreCase(status)) color = GREEN;
                }

                String line = formatRow(row, cols, widths);

                if (useAnsiColors && color != null)
                    System.out.println(color + line + RESET);
                else
                    System.out.println(line);
            }
        }

        // ---- bottom frame ----
        System.out.println();
        System.out.println(FRAME_LINE);
        System.out.println("END OF WORKBOOK REPORT");
        System.out.println(FRAME_LINE);
        System.out.println();
    }

    // ---------------- summary ----------------

    private static String buildSummaryLine(
            int scenarioCount,
            int statusCol,
            LinkedHashMap<String, Integer> statusCounts
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ran ").append(scenarioCount).append(" SCENARIOS");

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

    // ---------------- rendering helpers ----------------

    private static List<String> renderRow(
            List<String> headers,
            Object rowObj,
            boolean includeRowKeyColumn,
            String rowKeyHeaderName
    ) {
        String rowKey = (String) getField(rowObj, "rowKey");
        @SuppressWarnings("unchecked")
        Map<String, Object> values =
                (Map<String, Object>) getField(rowObj, "values");

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
        int[] w = new int[cols];
        for (List<String> row : grid) {
            for (int c = 0; c < cols; c++) {
                w[c] = Math.max(w[c], safeGet(row, c).length());
            }
        }
        for (int c = 0; c < cols; c++) w[c] = Math.max(w[c], 3);
        return w;
    }

    private static String formatRow(List<String> row, int cols, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            String v = safeGet(row, c);
            sb.append(" ").append(v).append(" ".repeat(widths[c] - v.length())).append(" |");
        }
        return sb.toString();
    }

    private static String safeGet(List<String> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size() || row.get(idx) == null) return "";
        return row.get(idx);
    }

    // ---------------- reflection ----------------

    private static List<?> snapshotAllSheetsReflective(WorkBook book) {
        try {
            Method m = WorkBook.class.getDeclaredMethod("snapshotAllSheets");
            m.setAccessible(true);
            Object out = m.invoke(book);
            return out instanceof List<?> l ? l : List.of();
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
                    "Unable to read field '" + fieldName + "' from " + target.getClass(), e);
        }
    }
}
