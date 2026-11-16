package tools.dscode.store.sql;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL literals in the Store core live in the store. The catalog is the ported statement list. */
class StoreDialectInventoryTest {
    private static final String[] SQL_STARTS = {
            "select ", "insert ", "update ", "delete ", "create ", "alter ", "pragma ", "call "
    };

    @Test
    void everyStatementIsInTheStoreCatalog() throws Exception {
        List<String> all = SqlDialect.statements();
        assertTrue(all.size() >= 90, "ported statements " + all.size());
        String joined = String.join("\n", all);
        String upper = joined.toUpperCase();
        for (String sql : all) {
            assertFalse(sql.contains("||"), sql);
            String shape = sql.toUpperCase();
            assertFalse(shape.contains("ON DUPLICATE"), sql);
            assertFalse(shape.contains("INSERT IGNORE"), sql);
            assertFalse(shape.contains("INFORMATION_SCHEMA"), sql);
            assertFalse(shape.contains("LONGBLOB"), sql);
            assertFalse(shape.contains("AUTO_INCREMENT"), sql);
            assertFalse(shape.contains("AS SIGNED"), sql);
            assertFalse(shape.contains("AS CHAR"), sql);
        }
        assertTrue(upper.contains("CONCAT("), "CONCAT");
        assertTrue(upper.contains("AS INTEGER"), "CAST AS INTEGER");
        assertTrue(upper.contains("ON CONFLICT"), "upsert");
        assertTrue(upper.contains("INSERT OR IGNORE"), "insert or ignore");
        assertTrue(upper.contains("PRAGMA_TABLE_INFO"), "columns");
        assertTrue(upper.contains("BLOB"), "blob");
        assertTrue(upper.contains("AUTOINCREMENT"), "autoincrement");
        assertEquals(12, SqlDialect.TRANSACTIONS.size());
        assertTrue(Files.readString(Path.of("src/main/java/tools/dscode/store/sql/SqlDialect.java")).contains("store_schema"));

        Path root = Path.of("src/main/java/tools/dscode/store");
        assertTrue(Files.isDirectory(root), root.toAbsolutePath().toString());
        List<String> problems = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("sql/")) continue;
                String text = Files.readString(file);
                if (text.contains("DriverManager")) problems.add(relative + " uses DriverManager");
                if (text.contains("jdbc:mariadb://") || text.contains("jdbc:sqlite:") || text.contains("jdbc:mysql:")) {
                    problems.add(relative + " has a jdbc url");
                }
                boolean infrastructure = relative.startsWith("install/") || relative.startsWith("server/");
                if (infrastructure) problems.addAll(sqlLiterals(relative, text));
            }
        }
        assertEquals(List.of(), problems);
    }

    private static List<String> sqlLiterals(String relative, String text) {
        List<String> problems = new ArrayList<>();
        boolean block = false;
        StringBuilder blockText = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!block) {
                int open = line.indexOf("\"\"\"");
                if (open >= 0 && line.indexOf("\"\"\"", open + 3) < 0) {
                    block = true;
                    blockText.setLength(0);
                } else {
                    noteIfSql(problems, relative, quoted(line));
                }
            } else if (line.contains("\"\"\"")) {
                block = false;
                noteIfSql(problems, relative, blockText.toString());
                blockText.setLength(0);
            } else {
                blockText.append(line).append('\n');
            }
        }
        return problems;
    }

    private static void noteIfSql(List<String> problems, String relative, String literal) {
        if (literal == null) return;
        String trimmed = literal.strip().toLowerCase(Locale.ROOT);
        for (String start : SQL_STARTS) {
            if (trimmed.startsWith(start)) {
                problems.add(relative + " sql literal " + literal.strip().substring(0, Math.min(60, literal.strip().length())));
                return;
            }
        }
    }

    /** First ordinary double-quoted string on the line, or null. */
    private static String quoted(String line) {
        int open = line.indexOf('"');
        if (open < 0 || open + 1 >= line.length()) return null;
        StringBuilder value = new StringBuilder();
        for (int index = open + 1; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '\\' && index + 1 < line.length()) {
                value.append(line.charAt(index + 1));
                index++;
                continue;
            }
            if (ch == '"') return value.toString();
            value.append(ch);
        }
        return value.toString();
    }
}
