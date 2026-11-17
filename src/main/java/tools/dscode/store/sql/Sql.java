package tools.dscode.store.sql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the Store SQLite statements onto the Store dialect at execution time.
 * Callers keep their SQL text; the connection they receive from {@link SqlSession}
 * is this wrapper. The legacy SQLite driver is not opened here.
 */
public final class Sql {
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)(?:\\bfrom|\\bjoin|\\binto|\\btable)\\s+(`?)([a-zA-Z0-9_]+)\\1");
    /** {@code UPDATE table SET}. An assignment in {@code ON DUPLICATE KEY UPDATE col} is not a table. */
    private static final Pattern UPDATE_REF = Pattern.compile(
            "(?i)\\bupdate\\s+(`?)([a-zA-Z0-9_]+)\\1\\s+set\\b");

    private Sql() {
    }

    public static Connection wrap(SqlSession session) {
        Router router = new Router(session);
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("close".equals(name) && method.getParameterCount() == 0) {
                router.close();
                return null;
            }
            if ("isClosed".equals(name) && method.getParameterCount() == 0) {
                return router.closed;
            }
            if ("setAutoCommit".equals(name) && method.getParameterCount() == 1) {
                boolean auto = Boolean.TRUE.equals(args[0]);
                router.delegate.setAutoCommit(auto);
                if (router.main != null) router.main.setAutoCommit(auto);
                return null;
            }
            if ("commit".equals(name) && method.getParameterCount() == 0) {
                if (router.main != null && !router.main.getAutoCommit()) router.main.commit();
                router.delegate.commit();
                return null;
            }
            if ("rollback".equals(name) && method.getParameterCount() == 0) {
                if (router.main != null && !router.main.getAutoCommit()) router.main.rollback();
                router.delegate.rollback();
                return null;
            }
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && ("prepareStatement".equals(name) || "prepareCall".equals(name)
                    || "nativeSQL".equals(name))) {
                Routed routed = router.route(sql);
                if ("nativeSQL".equals(name)) {
                    args = args.clone();
                    args[0] = routed.sql;
                    return invoke(router.delegate, method, args);
                }
                if ("prepareStatement".equals(name) && method.getParameterCount() == 1
                        && startsWith(routed.sql, "insert")) {
                    return routed.connection.prepareStatement(routed.sql, Statement.RETURN_GENERATED_KEYS);
                }
                args = args.clone();
                args[0] = routed.sql;
                return invoke(routed.connection, method, args);
            }
            Object value = invoke(router.delegate, method, args);
            if (value instanceof Statement statement && "createStatement".equals(name)) {
                return wrapStatement(statement, router);
            }
            return value;
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    public static String translate(String sql) {
        return rewrite(sql, null);
    }

    /** SQLite text stays SQLite. Mixed joins are unqualified; run-state names resolve through the attachment. */
    public static String rewrite(String sql, Binding binding) {
        return translateBody(sql);
    }

    private static String qualifyRunState(String sql) {
        String current = sql;
        for (String table : SqlDialect.RUN_STATE_TABLES) {
            current = current.replaceAll(
                    "(?i)(?<![\\w.`])`?" + table + "`?(?![\\w])",
                    "pickleball." + table);
        }
        return current;
    }

    /** Product SQL is already SQLite. Quote the bare {@code key} identifier and leave the rest. */
    public static String translateBody(String sql) {
        if (sql == null) return null;
        return quoteKey(sql.strip());
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
        if ("journal_mode".equals(lower)) return "SELECT 'store'";
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

    private static Statement wrapStatement(Statement delegate, Router router) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String sql && sqlMethod(method.getName())) {
                Routed routed = router.route(sql);
                args = args.clone();
                args[0] = routed.sql;
                if (routed.connection != router.delegate) {
                    Statement onMain = routed.connection.createStatement();
                    router.extras.add(onMain);
                    return invoke(onMain, method, args);
                }
                return invoke(delegate, method, args);
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

    static boolean runStatePragma(String sql) {
        if (sql == null) return false;
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.contains("table_info")) return false;
        for (String table : SqlDialect.RUN_STATE_TABLES) {
            if (lower.contains("table_info(" + table) || lower.contains("table_info('" + table + "')")) return true;
        }
        return false;
    }

    /** A follow-up such as {@code last_insert_rowid()} must stay on the connection that wrote. */
    private static boolean followUp(String sql) {
        return sql != null && sql.toLowerCase(Locale.ROOT).contains("last_insert_rowid");
    }

    enum SqlKind { RUN_STATE, MIXED, OTHER, NONE }

    static SqlKind classify(String sql) {
        if (sql == null || sql.isBlank()) return SqlKind.NONE;
        Matcher matcher = TABLE_REF.matcher(sql);
        boolean run = false;
        boolean other = false;
        boolean any = false;
        while (matcher.find()) {
            any = true;
            String table = matcher.group(2).toLowerCase(Locale.ROOT);
            if (SqlDialect.RUN_STATE_TABLES.contains(table)) run = true;
            else other = true;
        }
        Matcher updates = UPDATE_REF.matcher(sql);
        while (updates.find()) {
            any = true;
            String table = updates.group(2).toLowerCase(Locale.ROOT);
            if (SqlDialect.RUN_STATE_TABLES.contains(table)) run = true;
            else other = true;
        }
        if (!any) return SqlKind.NONE;
        if (run && other) return SqlKind.MIXED;
        if (run) return SqlKind.RUN_STATE;
        return SqlKind.OTHER;
    }

    /**
     * Pure run-state statements go to a main session. A statement that joins
     * run-state to a source table cannot change branch mid-query, so that text
     * stays on the sandbox connection and run-state names are qualified.
     */
    private static final class Router {
        final SqlSession session;
        final Connection delegate;
        final List<Statement> extras = new ArrayList<>();
        boolean closed;
        boolean lastOnMain;
        Connection main;
        SqlConnections.Pool mainPool;

        Router(SqlSession session) {
            this.session = session;
            this.delegate = session.connection();
        }

        Routed route(String original) throws SQLException {
            String translated = translateBody(original);
            SqlKind kind = classify(translated);
            if (kind == SqlKind.RUN_STATE || runStatePragma(translated)
                    || (kind == SqlKind.NONE && lastOnMain && followUp(translated))) {
                Connection runState = main();
                lastOnMain = true;
                return new Routed(runState, translated);
            }
            lastOnMain = false;
            return new Routed(delegate, translated);
        }

        Connection main() throws SQLException {
            if (main != null) return main;
            try {
                SqlConnections.Borrowed borrowed = SqlConnections.borrowRunState(session.project());
                main = borrowed.connection();
                mainPool = borrowed.pool();
            } catch (java.io.IOException failure) {
                throw new SQLException(failure.getMessage() == null ? "run-state database did not open" : failure.getMessage(), failure);
            }
            if (!delegate.getAutoCommit()) main.setAutoCommit(false);
            return main;
        }

        void close() {
            if (closed) return;
            closed = true;
            for (Statement extra : extras) {
                try {
                    extra.close();
                } catch (SQLException ignored) {
                    // Closing a finished statement is best-effort.
                }
            }
            if (main != null) {
                try {
                    if (!main.getAutoCommit()) main.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The pool replaces a connection that will not reset.
                }
                if (mainPool != null) SqlConnections.giveBack(mainPool, main);
            }
            session.close();
        }
    }

    private record Routed(Connection connection, String sql) {
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
