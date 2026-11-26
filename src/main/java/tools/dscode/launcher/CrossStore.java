package tools.dscode.launcher;

import tools.dscode.store.StoreHashes;
import tools.dscode.store.StoreSecrets;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-cutting notes, session journal, and links. The file lives outside the
 * project tree. The launcher opens it. Workbench does not.
 */
public final class CrossStore {
    private static final AtomicBoolean JOURNAL_ERROR = new AtomicBoolean();
    private static final int DEFAULT_LIMIT = 50;

    private CrossStore() {
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "cross".equals(args[0].trim());
    }

    public static String help() {
        return """
                Usage: cross note add --text=<text> [--agent=<id>] [--project=<dir>]
                       cross note list [--agent=<id>] [--json] [--project=<dir>]
                       cross journal list [--verb=<name>] [--limit=N] [--json] [--project=<dir>]
                       cross journal retain --days=N [--project=<dir>]
                       cross link add --commit=<sha> --head=<id> --run=<id> [--project=<dir>]
                       cross link list [--json] [--project=<dir>]
                The file is %USERPROFILE%\\.pickleball\\spine\\cross.db on Windows and ~/.pickleball/spine/cross.db elsewhere.
                pkb_cross_store overrides that path. This is not a project store.
                pkb_journal defaults on. pkb_journal=off writes no journal row and does not create the file for the journal alone.
                A note body that looks like a secret is rejected. Arguments are stored as a sha256.
                """;
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: cross CLI expected the cross command");
            stderr.println("fix: cross help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        if (tokens.isEmpty() || tokens.get(0).startsWith("--") || isHelp(tokens.get(0))) {
            stdout.print(help());
            return 0;
        }
        String group = tokens.get(0);
        String action = tokens.size() > 1 && !tokens.get(1).startsWith("--") ? tokens.get(1) : "";
        try {
            return switch (group) {
                case "note" -> note(action, tokens, stdout, stderr);
                case "journal" -> journalCommand(action, tokens, stdout, stderr);
                case "link" -> link(action, tokens, stdout, stderr);
                default -> unknown(group, stderr);
            };
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null
                    ? "error: cross command failed\nfix: cross help"
                    : failure.getMessage();
            stderr.println(message);
            return 2;
        } catch (SQLException failure) {
            stderr.println("error: cross store was not opened");
            stderr.println("fix: check pkb_cross_store and org.xerial:sqlite-jdbc");
            return 1;
        }
    }

    /**
     * One journal row after a launcher verb. A write failure logs {@code error:}
     * once and does not fail the verb. {@code pkb_journal=off} writes nothing
     * and does not create the file.
     */
    public static void journal(String[] args, int code, PrintStream err) {
        try {
            if (!journalOn()) return;
            if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) return;
            writeJournal(args, code);
        } catch (Throwable failure) {
            if (JOURNAL_ERROR.compareAndSet(false, true)) {
                PrintStream stderr = err == null ? System.err : err;
                stderr.println("error: session journal was not written");
                stderr.println("fix: check pkb_cross_store and that the directory is writable");
            }
        }
    }

    /** SHA-256 of the canonical flag JSON. The raw arguments are not stored. */
    static String argsHash(String[] args) {
        return StoreHashes.sha256(canonicalFlags(args).getBytes(StandardCharsets.UTF_8));
    }

    private static int note(String action, List<String> tokens, PrintStream stdout, PrintStream stderr) throws SQLException {
        if ("add".equals(action)) return noteAdd(tokens, stdout, stderr);
        if ("list".equals(action)) return noteList(tokens, stdout);
        return unknown("note " + action, stderr);
    }

    private static int noteAdd(List<String> tokens, PrintStream stdout, PrintStream stderr) throws SQLException {
        String text = flag(tokens, "--text");
        if (text == null || text.isBlank()) {
            stderr.println("error: cross note add needs --text=<text>");
            stderr.println("fix: cross note add --text=<text>");
            return 2;
        }
        try {
            StoreSecrets.rejectAssignment(null, text);
        } catch (IllegalArgumentException failure) {
            stderr.println("error: history holds secret references only, not cleartext");
            stderr.println("fix: pass a reference such as <name> or ~MASK;");
            return 1;
        }
        String agent = flag(tokens, "--agent");
        if (agent == null) agent = setting("pkb_agent_id", "PKB_AGENT_ID");
        Path project = project(tokens);
        try (Connection connection = open()) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO notes(at, agent_id, project_hash, body) VALUES (?, ?, ?, ?)
                    """)) {
                insert.setString(1, Instant.now().toString());
                insert.setString(2, agent);
                insert.setString(3, projectHash(project));
                insert.setString(4, text);
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet id = statement.executeQuery("SELECT last_insert_rowid()")) {
                id.next();
                stdout.println("note " + id.getLong(1));
            }
        }
        return 0;
    }

    private static int noteList(List<String> tokens, PrintStream stdout) throws SQLException {
        String agent = flag(tokens, "--agent");
        if (agent == null) agent = "";
        boolean json = json(tokens);
        StringBuilder body = json ? new StringBuilder("{\"notes\":[") : new StringBuilder();
        boolean first = true;
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, at, agent_id, body FROM notes
                     WHERE (? = '' OR agent_id = ?)
                     ORDER BY id DESC
                     LIMIT ?
                     """)) {
            query.setString(1, agent);
            query.setString(2, agent);
            query.setInt(3, limit(tokens));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (json) {
                        if (!first) body.append(',');
                        first = false;
                        body.append("{\"id\":").append(rows.getLong(1))
                                .append(",\"at\":").append(jsonString(rows.getString(2)))
                                .append(",\"agent_id\":").append(jsonString(rows.getString(3)))
                                .append(",\"body\":").append(jsonString(rows.getString(4)))
                                .append('}');
                    } else {
                        stdout.println("note " + rows.getLong(1) + " " + rows.getString(3) + " " + rows.getString(4));
                    }
                }
            }
        }
        if (json) {
            body.append("]}");
            stdout.println(body);
        }
        return 0;
    }

    private static int journalCommand(String action, List<String> tokens, PrintStream stdout, PrintStream stderr) throws SQLException {
        if ("list".equals(action)) return journalList(tokens, stdout);
        if ("retain".equals(action)) return journalRetain(tokens, stdout, stderr);
        return unknown("journal " + action, stderr);
    }

    private static int journalList(List<String> tokens, PrintStream stdout) throws SQLException {
        String verb = flag(tokens, "--verb");
        if (verb == null) verb = "";
        boolean json = json(tokens);
        StringBuilder body = json ? new StringBuilder("{\"journal\":[") : new StringBuilder();
        boolean first = true;
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, at, agent_id, session_id, verb, args_hash, ok, code FROM journal
                     WHERE (? = '' OR verb = ?)
                     ORDER BY id DESC
                     LIMIT ?
                     """)) {
            query.setString(1, verb);
            query.setString(2, verb);
            query.setInt(3, limit(tokens));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (json) {
                        if (!first) body.append(',');
                        first = false;
                        body.append("{\"id\":").append(rows.getLong(1))
                                .append(",\"at\":").append(jsonString(rows.getString(2)))
                                .append(",\"agent_id\":").append(jsonString(rows.getString(3)))
                                .append(",\"session_id\":").append(jsonString(rows.getString(4)))
                                .append(",\"verb\":").append(jsonString(rows.getString(5)))
                                .append(",\"args_hash\":").append(jsonString(rows.getString(6)))
                                .append(",\"ok\":").append(rows.getInt(7))
                                .append(",\"code\":").append(jsonString(rows.getString(8)))
                                .append('}');
                    } else {
                        stdout.println("journal " + rows.getLong(1) + " " + rows.getString(5) + " "
                                + rows.getString(6) + " ok=" + rows.getInt(7));
                    }
                }
            }
        }
        if (json) {
            body.append("]}");
            stdout.println(body);
        }
        return 0;
    }

    private static int journalRetain(List<String> tokens, PrintStream stdout, PrintStream stderr) throws SQLException {
        String raw = flag(tokens, "--days");
        if (raw == null || raw.isBlank()) {
            stderr.println("error: cross journal retain needs --days=<n>");
            stderr.println("fix: cross journal retain --days=14");
            return 2;
        }
        long days;
        try {
            days = Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            stderr.println("error: --days must be a non-negative integer");
            stderr.println("fix: cross journal retain --days=14");
            return 2;
        }
        if (days < 0) {
            stderr.println("error: --days must be a non-negative integer");
            stderr.println("fix: cross journal retain --days=14");
            return 2;
        }
        Instant cutoff;
        try {
            cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        } catch (DateTimeException failure) {
            cutoff = Instant.EPOCH;
        }
        int dropped = 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                List<Long> ids = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT id, at FROM journal")) {
                    while (rows.next()) {
                        String at = rows.getString(2);
                        try {
                            if (at != null && !Instant.parse(at).isAfter(cutoff)) ids.add(rows.getLong(1));
                        } catch (DateTimeParseException ignored) {
                            // An unreadable stamp stays. The row was not dated inside the window.
                        }
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM journal WHERE id = ?")) {
                    for (Long id : ids) {
                        delete.setLong(1, id);
                        dropped += delete.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
        stdout.println("dropped=" + dropped);
        return 0;
    }

    private static int link(String action, List<String> tokens, PrintStream stdout, PrintStream stderr) throws SQLException {
        if ("add".equals(action)) return linkAdd(tokens, stdout, stderr);
        if ("list".equals(action)) return linkList(tokens, stdout);
        return unknown("link " + action, stderr);
    }

    private static int linkAdd(List<String> tokens, PrintStream stdout, PrintStream stderr) throws SQLException {
        String commit = flag(tokens, "--commit");
        String head = flag(tokens, "--head");
        String run = flag(tokens, "--run");
        if (commit == null || commit.isBlank() || head == null || head.isBlank() || run == null || run.isBlank()) {
            stderr.println("error: cross link add needs --commit, --head, and --run");
            stderr.println("fix: cross link add --commit=<sha> --head=<id> --run=<id>");
            return 2;
        }
        try (Connection connection = open();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO links(at, project_hash, commit_sha, head_id, run_id) VALUES (?, ?, ?, ?, ?)
                     """)) {
            insert.setString(1, Instant.now().toString());
            insert.setString(2, projectHash(project(tokens)));
            insert.setString(3, commit);
            insert.setString(4, head);
            insert.setString(5, run);
            insert.executeUpdate();
        }
        stdout.println("link " + commit);
        return 0;
    }

    private static int linkList(List<String> tokens, PrintStream stdout) throws SQLException {
        boolean json = json(tokens);
        StringBuilder body = json ? new StringBuilder("{\"links\":[") : new StringBuilder();
        boolean first = true;
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, at, commit_sha, head_id, run_id FROM links
                     ORDER BY id DESC
                     LIMIT ?
                     """)) {
            query.setInt(1, limit(tokens));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (json) {
                        if (!first) body.append(',');
                        first = false;
                        body.append("{\"id\":").append(rows.getLong(1))
                                .append(",\"at\":").append(jsonString(rows.getString(2)))
                                .append(",\"commit_sha\":").append(jsonString(rows.getString(3)))
                                .append(",\"head_id\":").append(jsonString(rows.getString(4)))
                                .append(",\"run_id\":").append(jsonString(rows.getString(5)))
                                .append('}');
                    } else {
                        stdout.println("link " + rows.getLong(1) + " " + rows.getString(3) + " "
                                + rows.getString(4) + " " + rows.getString(5));
                    }
                }
            }
        }
        if (json) {
            body.append("]}");
            stdout.println(body);
        }
        return 0;
    }

    private static void writeJournal(String[] args, int code) throws SQLException {
        String verb = args[0].trim();
        try (Connection connection = open();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO journal(at, agent_id, session_id, project_hash, verb, args_hash, ok, code)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            insert.setString(1, Instant.now().toString());
            insert.setString(2, setting("pkb_agent_id", "PKB_AGENT_ID"));
            insert.setString(3, setting("pkb_session_id", "PKB_SESSION_ID"));
            insert.setString(4, projectHash(project(tokenList(args))));
            insert.setString(5, verb);
            insert.setString(6, argsHash(args));
            insert.setInt(7, code == 0 ? 1 : 0);
            insert.setString(8, Integer.toString(code));
            insert.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        if (!driverPresent()) {
            throw new SQLException("org.xerial:sqlite-jdbc is not on the classpath");
        }
        Path file = location();
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException failure) {
                throw new SQLException("could not create the cross-store directory", failure);
            }
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            try (Statement statement = connection.createStatement()) {
                pragma(statement, "PRAGMA foreign_keys=ON");
                pragma(statement, "PRAGMA busy_timeout=5000");
                pragma(statement, "PRAGMA journal_mode=DELETE");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_meta (
                            `key` TEXT NOT NULL,
                            value TEXT NOT NULL,
                            PRIMARY KEY (`key`)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            at TEXT,
                            agent_id TEXT,
                            project_hash TEXT,
                            body TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS journal (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            at TEXT,
                            agent_id TEXT,
                            session_id TEXT,
                            project_hash TEXT,
                            verb TEXT,
                            args_hash TEXT,
                            ok INTEGER,
                            code TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS links (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            at TEXT,
                            project_hash TEXT,
                            commit_sha TEXT,
                            head_id TEXT,
                            run_id TEXT
                        )
                        """);
                markSchema(statement);
            }
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    private static void pragma(Statement statement, String sql) throws SQLException {
        if (!statement.execute(sql)) return;
        try (ResultSet rows = statement.getResultSet()) {
            while (rows != null && rows.next()) {
                // A pragma such as journal_mode returns one row. Consume it.
            }
        }
    }

    private static List<String> tokenList(String[] args) {
        List<String> tokens = new ArrayList<>();
        if (args == null) return tokens;
        for (String arg : args) {
            if (arg != null) tokens.add(arg);
        }
        return tokens;
    }

    private static void markSchema(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT value FROM schema_meta WHERE `key` = 'cross_schema'")) {
            if (rows.next()) {
                String value = rows.getString(1);
                if (!"1".equals(value)) throw new SQLException("cross_schema " + value + " is not supported");
                return;
            }
        }
        statement.executeUpdate("INSERT INTO schema_meta(`key`, value) VALUES ('cross_schema', '1')");
    }

    private static boolean driverPresent() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    static Path location() {
        String override = setting("pkb_cross_store", "PKB_CROSS_STORE");
        if (!override.isEmpty()) return Path.of(override);
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            String profile = System.getenv("USERPROFILE");
            home = profile == null || profile.isBlank() ? "." : profile;
        }
        return Path.of(home, ".pickleball", "spine", "cross.db");
    }

    private static boolean journalOn() {
        String value = setting("pkb_journal", "PKB_JOURNAL");
        if (value.isEmpty()) return true;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "off", "false", "0", "no" -> false;
            default -> true;
        };
    }

    /**
     * JSON array of the {@code --} tokens in sort order, then the other
     * arguments in their original order. The verb is the journal column, not
     * an element of this array.
     */
    private static String canonicalFlags(String[] args) {
        List<String> flags = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        if (args != null) {
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (token == null) continue;
                if (token.startsWith("--")) flags.add(token);
                else rest.add(token);
            }
        }
        flags.sort(String::compareTo);
        StringBuilder json = new StringBuilder();
        json.append('[');
        boolean first = true;
        for (String token : flags) {
            if (!first) json.append(',');
            first = false;
            json.append(jsonString(token));
        }
        for (String token : rest) {
            if (!first) json.append(',');
            first = false;
            json.append(jsonString(token));
        }
        json.append(']');
        return json.toString();
    }

    private static Path project(List<String> tokens) {
        String raw = flag(tokens, "--project");
        if (raw == null || raw.isBlank()) return Path.of("").toAbsolutePath().normalize();
        return Path.of(raw).toAbsolutePath().normalize();
    }

    private static String projectHash(Path project) {
        return StoreHashes.sha256(project.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String flag(List<String> tokens, String name) {
        String prefix = name + "=";
        for (String token : tokens) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private static boolean json(List<String> tokens) {
        return tokens.contains("--json");
    }

    private static boolean isHelp(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }

    private static int limit(List<String> tokens) {
        String raw = flag(tokens, "--limit");
        if (raw == null || raw.isBlank()) return DEFAULT_LIMIT;
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 1) {
                throw new IllegalArgumentException("error: --limit must be a positive integer\nfix: cross journal list --limit=50");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("error: --limit must be a positive integer\nfix: cross journal list --limit=50");
        }
    }

    private static int unknown(String command, PrintStream stderr) {
        stderr.println("error: Unknown cross command: " + command);
        stderr.println("fix: cross help");
        return 2;
    }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) return value.trim();
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        return "";
    }

    private static String jsonString(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder();
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20) out.append(String.format("\\u%04x", (int) current));
                    else out.append(current);
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
