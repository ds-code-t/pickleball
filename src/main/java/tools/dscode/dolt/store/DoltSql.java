package tools.dscode.dolt.store;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Rewrites the Dolt SQLite statements onto the Dolt dialect at execution time.
 * Callers keep their SQL text; the connection they receive from {@link DoltSession}
 * is this wrapper. {@code jdbc:sqlite} is not opened here.
 */
public final class DoltSql {
    private DoltSql() {
    }

    public static Connection wrap(DoltSession session) {
        Connection delegate = session.connection();
        boolean[] closed = new boolean[1];
        InvocationHandler handler = (proxy, method, args) -> {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                if (!closed[0]) {
                    closed[0] = true;
                    session.close();
                }
                return null;
            }
            if ("isClosed".equals(method.getName()) && method.getParameterCount() == 0) {
                return closed[0];
            }
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && ("prepareStatement".equals(method.getName()) || "prepareCall".equals(method.getName())
                    || "nativeSQL".equals(method.getName()))) {
                args = args.clone();
                args[0] = translate(sql);
                if ("prepareStatement".equals(method.getName()) && method.getParameterCount() == 1
                        && startsWith(args[0].toString(), "insert")) {
                    return delegate.prepareStatement(args[0].toString(), Statement.RETURN_GENERATED_KEYS);
                }
            }
            Object value = invoke(delegate, method, args);
            if (value instanceof Statement statement && "createStatement".equals(method.getName())) {
                return wrapStatement(statement);
            }
            return value;
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    public static String translate(String sql) {
        if (sql == null) return null;
        String trimmed = sql.strip();
        String head = trimmed.toLowerCase(Locale.ROOT);
        if (head.startsWith("create table") || head.startsWith("create index")
                || head.startsWith("create unique") || head.startsWith("alter table")) {
            return "SELECT 1";
        }
        if (head.startsWith("pragma")) {
            return pragma(trimmed);
        }
        String rewritten = trimmed;
        rewritten = rewritten.replaceAll("(?i)INSERT\\s+OR\\s+IGNORE", "INSERT IGNORE");
        rewritten = rewritten.replaceAll("(?i)INSERT\\s+OR\\s+REPLACE", "REPLACE");
        rewritten = rewritten.replaceAll(
                "(?i)ON\\s+CONFLICT\\s*\\([^)]*\\)\\s*DO\\s+UPDATE\\s+SET", "ON DUPLICATE KEY UPDATE");
        rewritten = rewritten.replaceAll("(?i)\\bexcluded\\.(\\w+)", "VALUES($1)");
        rewritten = rewritten.replaceAll("(?i)\\blast_insert_rowid\\s*\\(\\s*\\)", "LAST_INSERT_ID()");
        rewritten = rewritten.replaceAll("(?i)\\bAS\\s+INTEGER\\b", "AS SIGNED");
        rewritten = rewritten.replaceAll("(?i)\\bAS\\s+TEXT\\b", "AS CHAR");
        rewritten = rewritten.replaceAll(
                "(?i)(\\w+(?:\\.\\w+)?)\\s*=\\s*(\\?|\\w+(?:\\.\\w+)?)\\s+COLLATE\\s+NOCASE",
                "LOWER($1) = LOWER($2)");
        rewritten = rewritten.replaceAll("(?i)\\s+COLLATE\\s+NOCASE", "");
        rewritten = rewritten.replace("ESCAPE '\\'", "ESCAPE '\\\\'");
        rewritten = rewritten.replaceAll("(?i)\\blength\\s*\\(", "CHAR_LENGTH(");
        rewritten = sqliteMaster(rewritten);
        rewritten = concat(rewritten);
        rewritten = quoteKey(rewritten);
        return rewritten;
    }

    private static String pragma(String sql) {
        String body = sql.strip().substring(6).trim();
        if (body.toLowerCase(Locale.ROOT).startsWith("table_info")) {
            int open = body.indexOf('(');
            int close = body.lastIndexOf(')');
            String table = open >= 0 && close > open ? body.substring(open + 1, close).trim() : body;
            table = table.replace("'", "").replace("`", "");
            return "SELECT ORDINAL_POSITION AS cid, COLUMN_NAME AS name, DATA_TYPE AS type, "
                    + "CASE WHEN IS_NULLABLE = 'NO' THEN 1 ELSE 0 END AS notnull, "
                    + "COLUMN_DEFAULT AS dflt_value, CASE WHEN COLUMN_KEY = 'PRI' THEN 1 ELSE 0 END AS pk "
                    + "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '"
                    + table + "'";
        }
        String name = body;
        int equals = body.indexOf('=');
        if (equals >= 0) name = body.substring(0, equals).trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if ("journal_mode".equals(lower)) return "SELECT 'dolt'";
        if ("busy_timeout".equals(lower)) return "SELECT '0'";
        if ("foreign_keys".equals(lower)) return "SELECT '1'";
        return "SELECT 1";
    }

    private static String sqliteMaster(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("sqlite_master");
        if (at < 0) return sql;
        int nameAt = lower.indexOf("name =");
        if (nameAt < 0) nameAt = lower.indexOf("name=");
        if (nameAt < 0) {
            return "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()";
        }
        int quote = sql.indexOf('\'', nameAt);
        int end = quote < 0 ? -1 : sql.indexOf('\'', quote + 1);
        String table = quote >= 0 && end > quote ? sql.substring(quote + 1, end) : "";
        return "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '"
                + table + "'";
    }

    static String concat(String sql) {
        String current = sql;
        while (true) {
            int pipes = indexOfPipes(current);
            if (pipes < 0) break;
            int left = startOfOperand(current, pipes);
            int right = endOfOperand(current, pipes + 2);
            String replacement = "CONCAT(" + current.substring(left, pipes).trim() + ", "
                    + current.substring(pipes + 2, right).trim() + ")";
            current = current.substring(0, left) + replacement + current.substring(right);
        }
        return current;
    }

    private static int indexOfPipes(String sql) {
        boolean quote = false;
        for (int index = 0; index + 1 < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '\'') {
                if (quote && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quote = !quote;
                continue;
            }
            if (!quote && ch == '|' && sql.charAt(index + 1) == '|') return index;
        }
        return -1;
    }

    private static int startOfOperand(String sql, int pipes) {
        int index = pipes - 1;
        while (index >= 0 && Character.isWhitespace(sql.charAt(index))) index--;
        if (index < 0) return 0;
        if (sql.charAt(index) == ')') {
            int depth = 1;
            boolean quote = false;
            index--;
            while (index >= 0 && depth > 0) {
                char ch = sql.charAt(index);
                if (ch == '\'') quote = !quote;
                if (!quote) {
                    if (ch == ')') depth++;
                    else if (ch == '(') depth--;
                }
                index--;
            }
            while (index >= 0 && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_'
                    || sql.charAt(index) == '.')) {
                index--;
            }
            return index + 1;
        }
        if (sql.charAt(index) == '\'') {
            index--;
            while (index >= 0) {
                if (sql.charAt(index) == '\'') return index;
                index--;
            }
            return 0;
        }
        while (index >= 0 && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_'
                || sql.charAt(index) == '.')) {
            index--;
        }
        return index + 1;
    }

    private static int endOfOperand(String sql, int start) {
        int index = start;
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) index++;
        if (index >= sql.length()) return index;
        char ch = sql.charAt(index);
        if (ch == '\'') {
            index++;
            while (index < sql.length()) {
                if (sql.charAt(index) == '\'') {
                    if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                        index += 2;
                        continue;
                    }
                    return index + 1;
                }
                index++;
            }
            return index;
        }
        if (ch == '(') {
            int depth = 1;
            boolean quote = false;
            index++;
            while (index < sql.length() && depth > 0) {
                char current = sql.charAt(index);
                if (current == '\'') quote = !quote;
                else if (!quote && current == '(') depth++;
                else if (!quote && current == ')') depth--;
                index++;
            }
            return index;
        }
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '_' || current == '.') {
                index++;
                if (index < sql.length() && sql.charAt(index) == '(') {
                    return endOfOperand(sql, index);
                }
                continue;
            }
            break;
        }
        return index;
    }

    static String quoteKey(String sql) {
        StringBuilder out = new StringBuilder();
        boolean quote = false;
        int index = 0;
        while (index < sql.length()) {
            char ch = sql.charAt(index);
            if (ch == '\'') {
                out.append(ch);
                if (quote && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    out.append(sql.charAt(index + 1));
                    index += 2;
                    continue;
                }
                quote = !quote;
                index++;
                continue;
            }
            if (!quote && isKeyAt(sql, index)) {
                String previous = previousWord(out);
                if (!"duplicate".equals(previous) && !"primary".equals(previous)
                        && !"unique".equals(previous) && !"foreign".equals(previous)
                        && !"index".equals(previous)) {
                    out.append("`key`");
                    index += 3;
                    continue;
                }
            }
            out.append(ch);
            index++;
        }
        return out.toString();
    }

    private static boolean isKeyAt(String sql, int index) {
        if (index + 3 > sql.length() || !sql.regionMatches(true, index, "key", 0, 3)) return false;
        if (index > 0) {
            char before = sql.charAt(index - 1);
            if (Character.isLetterOrDigit(before) || before == '_' || before == '`' || before == '.') return false;
        }
        if (index + 3 < sql.length()) {
            char after = sql.charAt(index + 3);
            if (Character.isLetterOrDigit(after) || after == '_' || after == '`') return false;
        }
        return true;
    }

    private static String previousWord(StringBuilder out) {
        int index = out.length() - 1;
        while (index >= 0 && Character.isWhitespace(out.charAt(index))) index--;
        int end = index + 1;
        while (index >= 0 && Character.isLetterOrDigit(out.charAt(index))) index--;
        if (end <= index + 1) return "";
        return out.substring(index + 1, end).toLowerCase(Locale.ROOT);
    }

    private static Statement wrapStatement(Statement delegate) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String sql && sqlMethod(method.getName())) {
                args = args.clone();
                args[0] = translate(sql);
            }
            return invoke(delegate, method, args);
        };
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, handler);
    }

    private static boolean sqlMethod(String name) {
        return "execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name)
                || "executeLargeUpdate".equals(name) || "addBatch".equals(name);
    }

    private static boolean startsWith(String sql, String word) {
        return sql.strip().toLowerCase(Locale.ROOT).startsWith(word);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException || cause instanceof Error || cause instanceof SQLException) {
                throw cause;
            }
            throw failure;
        }
    }
}
