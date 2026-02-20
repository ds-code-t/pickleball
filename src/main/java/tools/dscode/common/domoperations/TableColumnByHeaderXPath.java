package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import tools.dscode.common.assertions.ValueWrapper;

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
        /**
         * "Row" boolean expression (omitted custom if null/empty):
         * - native <tr>
         * - role="row"
         * - custom element matching caller-provided predicate
         */
        public static String ROW_EXPR(String customRowSuffixPredicate) {
            return buildElementExpr("self::tr or @role='row'", customRowSuffixPredicate);
        }

        /**
         * "Header cell" boolean expression (omitted custom if null/empty):
         * - native <th>
         * - role="columnheader"
         * - custom element matching caller-provided predicate
         */
        public static String HEADER_CELL_EXPR(String customHeaderSuffixPredicate) {
            return buildElementExpr("self::th or @role='columnheader'", customHeaderSuffixPredicate);
        }

        /**
         * "Cell" boolean expression (omitted custom if null/empty):
         * - native <td> or <th>
         * - roles: cell/gridcell/columnheader/rowheader
         * - custom element matching caller-provided predicate
         */
        public static String CELL_EXPR(String customCellSuffixPredicate) {
            return buildElementExpr(
                    "self::td or self::th or @role='cell' or @role='gridcell' or @role='columnheader' or @role='rowheader'",
                    customCellSuffixPredicate);
        }

        // ---------------------------------------------------------------------
        // Public API
        // ---------------------------------------------------------------------
        /**
         * Build an XPath that selects all cells in the column whose header matches the given text predicate.
         *
         * Inputs (requirements)
         * ---------------------
         * @param getDirectTextPredicate a predicate string INCLUDING brackets that matches header text.
         * @param customRowSuffixPredicate caller-provided predicate INCLUDING brackets (null/empty = no custom)
         * @param customCellSuffixPredicate caller-provided predicate INCLUDING brackets (null/empty = no custom)
         * @param customHeaderSuffixPredicate caller-provided predicate INCLUDING brackets (null/empty = no custom)
         */
        public static XPathy matchCellsByHeader(
                String getDirectTextPredicate,
                String customRowSuffixPredicate,
                String customCellSuffixPredicate,
                String customHeaderSuffixPredicate
        ) {
            final String headerTextPred = requireBracketPredicate(getDirectTextPredicate, "getDirectTextPredicate");

            final String ROW = ROW_EXPR(customRowSuffixPredicate);
            final String CELL = CELL_EXPR(customCellSuffixPredicate);
            final String HDR = HEADER_CELL_EXPR(customHeaderSuffixPredicate);


            final String HDR_MATCH_STEP = "*[" + HDR + "]";  // remove headerTextPred here
            final String ROW_STEP = "*[" + ROW + "]";
            final String HDR_CELL_CANDIDATE =
                    "(" +
                            "ancestor::table[1]//thead//" + ROW_STEP + "[1]//" + HDR_MATCH_STEP + headerTextPred +
                            " | " +
                            "ancestor::table[1]//" + ROW_STEP + "[1]//" + HDR_MATCH_STEP + headerTextPred +
                            ")";


            final String HDR_INDEX =
                    "(count(" + HDR_CELL_CANDIDATE + "[1]/preceding-sibling::*[" + HDR + "]) + 1)";

            final String HDR_EXISTS =
                    "count(" + HDR_CELL_CANDIDATE + ") > 0";

            final String CELL_INDEX =
                    "(count(preceding-sibling::*[" + CELL + "]) + 1)";

            final String xpath =
                    "//*[" + CELL + "]" +
                            "[not(" + HDR + ")]" +
                            "[ancestor::table and " + HDR_EXISTS + " and " + CELL_INDEX + " = " + HDR_INDEX + "]";

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

        static String requireBracketPredicate(String bracketPredicate, String name) {
            if (bracketPredicate == null || bracketPredicate.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must be a non-empty bracketed predicate like \"[... ]\"");
            }
            final String s = bracketPredicate.trim();
            if (!s.startsWith("[") || !s.endsWith("]")) {
                throw new IllegalArgumentException(name + " must include brackets, e.g. \"[... ]\": " + s);
            }
            return s;
        }

        // ---------------------------------------------------------------------
// Convenience overload: build header text predicate from ValueWrapper + Op
// ---------------------------------------------------------------------



    }