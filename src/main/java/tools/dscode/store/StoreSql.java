package tools.dscode.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqliteOpen;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One read-only statement against the embedded store. {@code re:} is applied in Java.
 */
public final class StoreSql {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ROWS = 200;

    private StoreSql() {
    }

    public static int execute(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String query = flag(tokens, "--query");
        if (query == null || query.isBlank()) {
            err.println("error: store sql needs --query");
            err.println("fix: store sql --query=\"SELECT 1\"");
            return 2;
        }
        String rejected = reject(query);
        if (rejected != null) {
            err.println("error: " + rejected);
            err.println("fix: store sql --query=\"SELECT 1\"");
            return 2;
        }
        String text = flag(tokens, "--text");
        Pattern regex = null;
        if (text != null && text.startsWith("re:")) {
            try {
                regex = Pattern.compile(text.substring(3));
            } catch (PatternSyntaxException failure) {
                err.println("error: re: is not a Java regular expression");
                err.println("fix: store sql --query=\"SELECT 1\" --text=re:<pattern>");
                return 1;
            }
        }
        Binding binding = StoreScope.current();
        Path file = binding == null || binding.primary()
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, binding.name());
        try (StoreDatabase ignored = StoreDatabase.open(project);
             Connection connection = SqliteOpen.openReadOnly(file);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            ResultSetMetaData meta = rows.getMetaData();
            int columns = meta.getColumnCount();
            List<String> names = new ArrayList<>();
            for (int index = 1; index <= columns; index++) names.add(meta.getColumnLabel(index));
            List<List<String>> kept = new ArrayList<>();
            while (rows.next()) {
                List<String> cells = new ArrayList<>();
                for (int index = 1; index <= columns; index++) {
                    String value = rows.getString(index);
                    cells.add(value == null ? "" : value);
                }
                if (!keep(cells, text, regex)) continue;
                if (kept.size() >= MAX_ROWS) break;
                kept.add(cells);
            }
            if (json) out.println(json(names, kept));
            else out.print(text(names, kept));
            return 0;
        } catch (SQLException failure) {
            String message = failure.getMessage() == null ? "store sql failed" : failure.getMessage();
            err.println("error: " + message);
            err.println("fix: store sql --query=\"SELECT 1\"");
            return 1;
        }
    }

    static String reject(String query) {
        Scan scan = scan(query);
        if (scan.semicolon) return "store sql accepts one SELECT";
        if (scan.tokens.isEmpty()) return "store sql accepts one SELECT";
        String first = scan.tokens.get(0);
        if (same(first, "select")) return null;
        if (!same(first, "with")) return "store sql accepts one SELECT";
        int depth = 0;
        for (String token : scan.tokens) {
            if ("(".equals(token)) {
                depth++;
                continue;
            }
            if (")".equals(token)) {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) continue;
            if (same(token, "select") || same(token, "insert") || same(token, "update")
                    || same(token, "delete") || same(token, "replace")) {
                return same(token, "select") ? null : "store sql accepts one SELECT";
            }
        }
        return "store sql accepts one SELECT";
    }

    private static boolean keep(List<String> cells, String text, Pattern regex) {
        if (text == null || text.isBlank()) return true;
        if (text.startsWith("exact:")) {
            String want = text.substring("exact:".length());
            for (String cell : cells) {
                if (want.equals(cell)) return true;
            }
            return false;
        }
        String joined = String.join("\t", cells);
        if (regex != null) return regex.matcher(joined).find();
        return joined.contains(text);
    }

    private static String text(List<String> names, List<List<String>> rows) {
        StringBuilder body = new StringBuilder();
        body.append(String.join("\t", names)).append('\n');
        for (List<String> row : rows) body.append(String.join("\t", row)).append('\n');
        return body.toString();
    }

    private static String json(List<String> names, List<List<String>> rows) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode columns = root.putArray("columns");
        for (String name : names) columns.add(name);
        ArrayNode table = root.putArray("rows");
        for (List<String> row : rows) {
            ArrayNode line = table.addArray();
            for (String cell : row) line.add(cell);
        }
        root.put("count", rows.size());
        try {
            return JSON.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return "{\"columns\":[],\"rows\":[],\"count\":0}";
        }
    }

    private static String flag(List<String> tokens, String name) {
        String prefix = name + "=";
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith(prefix)) return token.substring(prefix.length());
            if (name.equals(token) && index + 1 < tokens.size()) return tokens.get(index + 1);
        }
        return null;
    }

    private static boolean same(String token, String word) {
        return token.equalsIgnoreCase(word);
    }

    private static Scan scan(String sql) {
        Scan scan = new Scan();
        StringBuilder token = new StringBuilder();
        int length = sql.length();
        for (int index = 0; index < length; index++) {
            char character = sql.charAt(index);
            if (character == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
                flush(scan, token);
                while (index < length && sql.charAt(index) != '\n') index++;
                continue;
            }
            if (character == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                flush(scan, token);
                index += 2;
                while (index + 1 < length && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) index++;
                if (index < length) index++;
                continue;
            }
            if (character == '\'') {
                flush(scan, token);
                index++;
                while (index < length) {
                    if (sql.charAt(index) == '\'' && index + 1 < length && sql.charAt(index + 1) == '\'') {
                        index += 2;
                        continue;
                    }
                    if (sql.charAt(index) == '\'') break;
                    index++;
                }
                continue;
            }
            if (character == '"') {
                flush(scan, token);
                index++;
                while (index < length && sql.charAt(index) != '"') index++;
                continue;
            }
            if (character == ';') {
                flush(scan, token);
                scan.semicolon = true;
                continue;
            }
            if (character == '(' || character == ')') {
                flush(scan, token);
                scan.tokens.add(String.valueOf(character));
                continue;
            }
            if (Character.isWhitespace(character)) {
                flush(scan, token);
                continue;
            }
            token.append(character);
        }
        flush(scan, token);
        return scan;
    }

    private static void flush(Scan scan, StringBuilder token) {
        if (token.isEmpty()) return;
        scan.tokens.add(token.toString().toLowerCase(Locale.ROOT));
        token.setLength(0);
    }

    private static final class Scan {
        private final List<String> tokens = new ArrayList<>();
        private boolean semicolon;
    }
}
