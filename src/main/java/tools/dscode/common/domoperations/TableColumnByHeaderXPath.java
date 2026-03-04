package tools.dscode.common.domoperations;

import com.xpathy.XPathy;

public final class TableColumnByHeaderXPath {
    private TableColumnByHeaderXPath() {
        // Utility class: no instances
    }

    // ---------------------------------------------------------------------
    // Helper: wrap any boolean expression in parentheses for safe grouping
    // ---------------------------------------------------------------------
    private static String group(String expr) {
        return "(" + expr + ")";
    }

    // ---------------------------------------------------------------------
    // Helper: build element boolean expression with optional custom predicate
    // ---------------------------------------------------------------------
    private static String buildElementExpr(String baseConditions, String customSuffixPredicate) {
        final String custom = safePredicate(customSuffixPredicate);
        if (custom.isEmpty()) {
            return group(baseConditions);
        }
        return group(baseConditions + " or (self::*" + custom + ")");
    }

    // ---------------------------------------------------------------------
    // Alternate element "definitions" (boolean expressions for use inside predicates: *[EXPR])
    // ---------------------------------------------------------------------
    public static String ROW_EXPR(String customRowSuffixPredicate) {
        return buildElementExpr("self::tr or @role='row'", customRowSuffixPredicate);
    }

    public static String HEADER_CELL_EXPR(String customHeaderSuffixPredicate) {
        return buildElementExpr("self::th or @role='columnheader'", customHeaderSuffixPredicate);
    }

    public static String CELL_EXPR(String customCellSuffixPredicate) {
        return buildElementExpr(
                "self::td or self::th or @role='cell' or @role='gridcell' or @role='columnheader' or @role='rowheader'",
                customCellSuffixPredicate
        );
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------
    public static XPathy matchCellsByHeader(
            String headerTextPred,
            String customRowSuffixPredicate,
            String customCellSuffixPredicate,
            String customHeaderSuffixPredicate
    ) {

        final String ROW = ROW_EXPR(customRowSuffixPredicate);
        final String CELL = CELL_EXPR(customCellSuffixPredicate);
        final String HDR = HEADER_CELL_EXPR(customHeaderSuffixPredicate);

        final String TABLE = "ancestor::table[1]";
        final String ROW_STEP = "*[" + ROW + "]";

        /*
         * Header-like cell definition:
         * - Prefer explicit header semantics (HDR)
         * - But also allow plain cells (CELL) in the chosen header row (covers <td>-based headers)
         *
         * Note: HDR can include custom header predicate; CELL can include custom cell predicate.
         */
        final String HCELL = group(HDR + " or " + CELL);
        final String HCELL_STEP = "*[" + HCELL + "]";

        /*
         * Minimal fix #1 (table association):
         * - Find the header row ONLY from the same table’s structural areas (thead first, else tbody/tfoot/direct).
         * - Avoid TABLE + "//" + ROW_STEP as that can drift into nested tables.
         */
        final String HEADER_ROW_CANDIDATE =
                "("
                        + TABLE + "/thead//" + ROW_STEP + "[1]"
                        + " | "
                        + "("
                        + "("
                        + TABLE + "/tbody/" + ROW_STEP
                        + " | "
                        + TABLE + "/tfoot/" + ROW_STEP
                        + " | "
                        + TABLE + "/" + ROW_STEP
                        + ")[1]"
                        + ")"
                        + ")";

        /*
         * Minimal fix #2 (nested-table safety inside the row):
         * - When scanning descendants for header cells, require the nearest table to still be the same TABLE.
         *   This prevents matching header-like cells from a nested table inside a header-row cell.
         */
        final String IN_SAME_TABLE =
                "[ancestor::table[1] = " + TABLE + "]";

        final String HDR_CELL_CANDIDATE =
                "("
                        + HEADER_ROW_CANDIDATE + "//" + HCELL_STEP + IN_SAME_TABLE + headerTextPred
                        + ")";

        final String HDR_INDEX =
                "(count(" + HDR_CELL_CANDIDATE + "[1]/preceding-sibling::*[" + HCELL + "]) + 1)";

        final String HDR_EXISTS =
                "count(" + HDR_CELL_CANDIDATE + ") > 0";

        final String CELL_INDEX =
                "(count(preceding-sibling::*[" + CELL + "]) + 1)";

        /*
         * Exclude the header row itself, even when it's made of <td>.
         * We do this by excluding any cell whose nearest "row" ancestor is the same node as HEADER_ROW_CANDIDATE[1].
         */
        final String NOT_IN_HEADER_ROW =
                "not(ancestor::*[" + ROW + "][1] = (" + HEADER_ROW_CANDIDATE + ")[1])";

        final String xpath =
                "//*[" + CELL + "]"
                        + "[ancestor::table and " + HDR_EXISTS + " and " + CELL_INDEX + " = " + HDR_INDEX + "]"
                        + "[" + NOT_IN_HEADER_ROW + "]"
                        + "[not(" + HDR + ")]";

        return XPathy.from(xpath);
    }

    // ---------------------------------------------------------------------
    // Predicate utilities
    // ---------------------------------------------------------------------
    private static String safePredicate(String bracketPredicate) {
        if (bracketPredicate == null) return "";
        final String s = bracketPredicate.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("[") || !s.endsWith("]")) {
            throw new IllegalArgumentException("Predicate must include brackets, e.g. \"[... ]\": " + s);
        }
        return s;
    }
}