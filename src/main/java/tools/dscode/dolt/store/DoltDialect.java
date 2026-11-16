package tools.dscode.dolt.store;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dolt schema and the ported statement catalog. Column types are the P2 finals:
 * path keys {@code TEXT}, file bytes {@code BLOB}, ids {@code INTEGER AUTO_INCREMENT}.
 * {@code ||} is never used; tag and feature filters call {@code CONCAT}.
 */
public final class DoltDialect {
    public static final int SCHEMA_VERSION = 1;

    /** Test seam. Each remaining count throws one serialization failure before the next write. */
    static volatile int forcedConflictRemaining;

    public static final List<String> SOURCE_TABLES = List.of(
            "schema_meta", "files", "provenance", "feature_docs", "scenarios", "example_blocks", "steps",
            "entities", "step_definitions");

    public static final List<String> RUN_STATE_TABLES = List.of(
            "runs", "run_logs", "scenario_executions", "step_executions", "entity_usages",
            "log_entity_links", "artifacts", "sandboxes");

    /** The eleven {@code setAutoCommit(false)} sites, plus the schema commit. */
    public static final List<String> TRANSACTIONS = List.of(
            "DoltContent.put",
            "DoltContent.delete",
            "DoltEntities.put",
            "DoltEntities.remove",
            "DoltExecution.recordStep",
            "DoltExecution.beforeStep",
            "DoltExtractor.extract",
            "DoltHistory.record",
            "DoltHistory.retain",
            "DoltHydrator.hydrate",
            "DoltStepCatalog.refresh",
            "DoltDialect.ensureSchema");

    public static final List<String> SOURCE_DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS schema_meta (
                `key` TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (`key`)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS files (
                path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                raw_content BLOB NOT NULL,
                mtime INTEGER NOT NULL,
                source_type TEXT NOT NULL,
                last_hydrated_at TEXT NOT NULL,
                last_synced_hash TEXT NOT NULL,
                db_edited INTEGER NOT NULL DEFAULT 0,
                git_blob TEXT,
                binary_ref INTEGER NOT NULL DEFAULT 0,
                byte_size INTEGER,
                location TEXT NOT NULL DEFAULT 'db',
                git_commit TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (path)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS provenance (
                id INTEGER NOT NULL PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                hydration_id TEXT,
                last_hydrated_at TEXT,
                git_commit TEXT,
                git_branch TEXT,
                git_remote TEXT,
                github_repo TEXT,
                git_dirty INTEGER,
                CHECK (id = 1)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS feature_docs (
                file_path TEXT NOT NULL,
                keyword TEXT NOT NULL,
                name TEXT NOT NULL,
                language TEXT NOT NULL,
                tags TEXT NOT NULL,
                description TEXT NOT NULL,
                PRIMARY KEY (file_path)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS scenarios (
                file_path TEXT NOT NULL,
                ord INTEGER NOT NULL,
                keyword TEXT NOT NULL,
                name TEXT NOT NULL,
                line INTEGER NOT NULL,
                tags TEXT NOT NULL,
                description TEXT NOT NULL,
                PRIMARY KEY (file_path, ord)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS example_blocks (
                file_path TEXT NOT NULL,
                scenario_ord INTEGER NOT NULL,
                ord INTEGER NOT NULL,
                line INTEGER NOT NULL,
                tags TEXT NOT NULL,
                PRIMARY KEY (file_path, scenario_ord, ord)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS steps (
                file_path TEXT NOT NULL,
                scenario_ord INTEGER NOT NULL,
                ord INTEGER NOT NULL,
                keyword TEXT NOT NULL,
                text TEXT NOT NULL,
                line INTEGER NOT NULL,
                docstring TEXT,
                docstring_type TEXT,
                table_json TEXT,
                PRIMARY KEY (file_path, scenario_ord, ord)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS entities (
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                secret_refs TEXT NOT NULL,
                source_file TEXT,
                content_hash TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (entity_type, entity_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS step_definitions (
                definition_id TEXT NOT NULL,
                language TEXT NOT NULL,
                pattern TEXT NOT NULL,
                method_name TEXT,
                source_path TEXT NOT NULL,
                source_line INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                hydrated_at TEXT NOT NULL,
                removed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (definition_id)
            )
            """);

    public static final List<String> RUN_DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS runs (
                run_id TEXT NOT NULL,
                started_at TEXT NOT NULL,
                finished_at TEXT,
                status TEXT NOT NULL,
                scenario TEXT NOT NULL,
                passed INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (run_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS run_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                logged_at TEXT NOT NULL,
                entity TEXT NOT NULL,
                message TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS scenario_executions (
                exec_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                scenario_key TEXT NOT NULL,
                scenario_hash TEXT NOT NULL,
                examples_row INTEGER,
                started_at TEXT NOT NULL,
                finished_at TEXT,
                status TEXT NOT NULL,
                passed INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0,
                sandbox TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (exec_id),
                FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS step_executions (
                step_exec_id TEXT NOT NULL,
                scenario_exec_id TEXT NOT NULL,
                step_ord INTEGER NOT NULL,
                step_text TEXT NOT NULL,
                step_text_hash TEXT NOT NULL,
                definition_id TEXT,
                source_line INTEGER,
                started_at TEXT NOT NULL,
                finished_at TEXT,
                status TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (step_exec_id),
                FOREIGN KEY (scenario_exec_id) REFERENCES scenario_executions (exec_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS entity_usages (
                usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
                step_exec_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                field TEXT,
                operation TEXT NOT NULL,
                value_ref TEXT,
                logged_at TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (step_exec_id) REFERENCES step_executions (step_exec_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS log_entity_links (
                log_id INTEGER NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (log_id, entity_type, entity_id),
                FOREIGN KEY (log_id) REFERENCES run_logs (id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS artifacts (
                artifact_id TEXT NOT NULL,
                run_id TEXT,
                step_exec_id TEXT,
                kind TEXT NOT NULL,
                path TEXT NOT NULL,
                retained_at TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (artifact_id),
                FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE,
                FOREIGN KEY (step_exec_id) REFERENCES step_executions (step_exec_id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sandboxes (
                name TEXT NOT NULL,
                owner TEXT NOT NULL,
                base_commit TEXT NOT NULL,
                created TEXT NOT NULL,
                last_used TEXT NOT NULL,
                ttl TEXT NOT NULL,
                sandbox TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (name)
            )
            """);

    public static final String UPSERT_META = """
            INSERT INTO schema_meta(`key`, value) VALUES (?, ?)
            ON CONFLICT(`key`) DO UPDATE SET value = excluded.value
            """;

    public static final String UPSERT_PROVENANCE = """
            INSERT INTO provenance(id, schema_version) VALUES (1, ?)
            ON CONFLICT(id) DO UPDATE SET schema_version = excluded.schema_version
            """;

    public static final String COLUMNS = """
            SELECT name AS column_name, type AS data_type, pk AS column_key
            FROM pragma_table_info(?)
            """;

    public static final String ACTIVE_BRANCH = "SELECT value FROM _pkb_branch WHERE `key` = 'name'";

    public static final List<String> BRANCH_DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS _pkb_branch (
                `key` TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (`key`)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS _pkb_base (
                tbl TEXT NOT NULL,
                pk TEXT NOT NULL,
                base_hash TEXT,
                location TEXT,
                git_commit TEXT,
                PRIMARY KEY (tbl, pk)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS _pkb_actor (
                id INTEGER NOT NULL PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                CHECK (id = 1)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS _pkb_changes (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                at TEXT NOT NULL,
                tbl TEXT NOT NULL,
                pk TEXT NOT NULL,
                op TEXT NOT NULL,
                old TEXT,
                new TEXT,
                actor TEXT NOT NULL DEFAULT ''
            )
            """);

    public static final List<String> FILTERS = List.of(
            tagLike("sc.tags"),
            taggedScenario("scenario_key"),
            taggedExamples("scenario_key"),
            "AND LOWER(status) = LOWER(?)",
            "AND instr(scenario_key, ?) > 0",
            "AND (scenario_key LIKE ? ESCAPE '\\' OR (kind = 'run' AND run_id IN"
                    + " (SELECT e.run_id FROM scenario_executions e WHERE e.scenario_key LIKE ? ESCAPE '\\')))");

    private static final List<String> ALL = combine();

    private DoltDialect() {
    }

    public static List<String> statements() {
        return ALL;
    }

    public static String tagLike(String column) {
        return "CONCAT(' ', " + column + ", ' ') LIKE ? ESCAPE '\\'";
    }

    public static String taggedScenario(String keyExpr) {
        return "instr(" + keyExpr + ", CONCAT(sc.file_path, '|')) = 1"
                + " AND CAST(substr(" + keyExpr + ", length(sc.file_path) + 2) AS INTEGER) >= sc.line"
                + " AND CAST(substr(" + keyExpr + ", length(sc.file_path) + 2) AS INTEGER) < COALESCE("
                + "(SELECT MIN(nx.line) FROM scenarios nx WHERE nx.file_path = sc.file_path AND nx.line > sc.line),"
                + " 2147483647)";
    }

    public static String taggedExamples(String keyExpr) {
        return "instr(" + keyExpr + ", CONCAT(eb.file_path, '|')) = 1"
                + " AND CAST(substr(" + keyExpr + ", length(eb.file_path) + 2) AS INTEGER) >= eb.line"
                + " AND CAST(substr(" + keyExpr + ", length(eb.file_path) + 2) AS INTEGER) < COALESCE("
                + "(SELECT MIN(nx.line) FROM steps nx WHERE nx.file_path = eb.file_path"
                + " AND nx.scenario_ord = eb.scenario_ord AND nx.keyword = 'Examples' AND nx.line > eb.line),"
                + "(SELECT MIN(sc.line) FROM scenarios sc WHERE sc.file_path = eb.file_path AND sc.line > eb.line),"
                + " 2147483647)";
    }

    public static void ensureSchema(DoltSession session) throws SQLException {
        for (String ddl : SOURCE_DDL) session.script(ddl);
        for (String ddl : BRANCH_DDL) session.script(ddl);
        String current = session.scalar("SELECT value FROM schema_meta WHERE `key` = 'store_schema'");
        if (current != null && !current.isBlank()) {
            int version;
            try {
                version = Integer.parseInt(current.trim());
            } catch (NumberFormatException failure) {
                version = 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new SQLException("store schema " + version + " is newer than this build ("
                        + SCHEMA_VERSION + ")");
            }
        }
        if (!"1".equals(current)) {
            session.update(UPSERT_META, "store_schema", "1");
            session.update(UPSERT_META, "schema_version", "1");
            session.update(UPSERT_META, "hydrate_generation", "0");
            session.update(UPSERT_PROVENANCE, SCHEMA_VERSION);
            session.update(
                    "INSERT INTO _pkb_branch(`key`, value) VALUES ('name', 'main') "
                            + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value");
            session.update(
                    "INSERT INTO _pkb_branch(`key`, value) VALUES ('store_schema', '1') "
                            + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value");
        }
        session.update("INSERT OR IGNORE INTO _pkb_actor(id, name) VALUES (1, '')");
        String trigger = session.scalar(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'files_pkb_insert'");
        if (trigger == null) installChangeLog(session);
    }

    public static void ensureRunSchema(DoltSession session) throws SQLException {
        for (String ddl : RUN_DDL) session.script(ddl);
    }

    /** Triggers omit BLOB columns. JSON cannot hold a multi-byte blob. */
    public static void installChangeLog(DoltSession session) throws SQLException {
        for (String table : List.of("files", "entities", "step_definitions")) {
            List<String> columns = session.column("SELECT name FROM pragma_table_info('" + table + "')");
            List<String> types = session.column("SELECT type FROM pragma_table_info('" + table + "')");
            String pk = primaryKey(session, table);
            StringBuilder json = new StringBuilder();
            for (int index = 0; index < columns.size(); index++) {
                String type = types.get(index) == null ? "" : types.get(index).toLowerCase(Locale.ROOT);
                if (type.contains("blob")) continue;
                if (!json.isEmpty()) json.append(", ");
                json.append('\'').append(columns.get(index)).append("', NEW.").append(columns.get(index));
            }
            for (String when : List.of("INSERT", "UPDATE", "DELETE")) {
                String name = table + "_pkb_" + when.toLowerCase(Locale.ROOT);
                session.script("DROP TRIGGER IF EXISTS " + name);
                String op = when.toLowerCase(Locale.ROOT);
                String row = "DELETE".equals(when) ? "OLD" : "NEW";
                String oldJson = "DELETE".equals(when) || "UPDATE".equals(when)
                        ? json.toString().replace("NEW.", "OLD.") : "NULL";
                String newJson = "DELETE".equals(when) ? "NULL" : json.toString();
                session.script("""
                        CREATE TRIGGER %s AFTER %s ON %s BEGIN
                            INSERT INTO _pkb_changes(at, tbl, pk, op, old, new, actor)
                            VALUES (
                                strftime('%%Y-%%m-%%dT%%H:%%M:%%fZ','now'),
                                '%s',
                                %s,
                                '%s',
                                %s,
                                %s,
                                COALESCE((SELECT name FROM _pkb_actor WHERE id = 1), ''));
                        END
                        """.formatted(
                        name, when, table, table, pkExpr(pk, row), op,
                        oldJson.equals("NULL") ? "NULL" : "json_object(" + oldJson + ")",
                        newJson.equals("NULL") ? "NULL" : "json_object(" + newJson + ")"));
            }
        }
    }

    private static String primaryKey(DoltSession session, String table) throws SQLException {
        List<String> names = session.column(
                "SELECT name FROM pragma_table_info('" + table + "') WHERE pk > 0 ORDER BY pk");
        if (names.isEmpty()) return "''";
        if (names.size() == 1) return "NEW." + names.get(0);
        StringBuilder expr = new StringBuilder("concat(");
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) expr.append(", '#', ");
            expr.append("NEW.").append(names.get(index));
        }
        return expr.append(')').toString();
    }

    private static String pkExpr(String pk, String row) {
        return pk.replace("NEW.", row + ".");
    }

    public static boolean conflict(SQLException failure) {
        String message = failure == null || failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        String state = failure == null || failure.getSQLState() == null ? "" : failure.getSQLState();
        return message.contains("conflict")
                || message.contains("serialization")
                || message.contains("deadlock")
                || message.contains("try restarting")
                || message.contains("lock wait")
                || message.contains("database is locked")
                || message.contains("sqlite_busy")
                || "40001".equals(state);
    }

    public static void retry(String table, SqlTask task) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (forcedConflictRemaining > 0) {
                    forcedConflictRemaining--;
                    throw new SQLException("serialization failure");
                }
                task.run();
                return;
            } catch (SQLException failure) {
                last = failure;
                if (!conflict(failure) || attempt == 3) break;
                try {
                    Thread.sleep(50L + ThreadLocalRandom.current().nextLong(151));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("error: concurrent update on " + table, interrupted);
                }
            }
        }
        if (last != null && conflict(last)) {
            throw new SQLException("error: concurrent update on " + table, last);
        }
        if (last != null) throw last;
    }

    public interface SqlTask {
        void run() throws SQLException;
    }

    public static boolean sourceDirty(java.sql.Connection connection) {
        return false;
    }

    /**
     * Records a hydrate generation when the caller changed rows.
     * There is no server commit. Returns false when nothing changed.
     */
    public static boolean commitHydrate(java.sql.Connection connection, String gitSha, boolean dirty) throws SQLException {
        String sha = gitSha == null || gitSha.isBlank() ? "unknown" : gitSha.replace("'", "");
        String label = sha + (dirty ? " dirty" : "");
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("UPDATE schema_meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) "
                    + "WHERE `key` = 'hydrate_generation'");
            statement.execute("INSERT INTO schema_meta(`key`, value) VALUES ('hydrate_label', '" + label + "') "
                    + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value");
        }
        return true;
    }

    public static String lastHydrateCommit(java.sql.Connection connection) {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT value FROM schema_meta WHERE `key` = 'hydrate_generation'")) {
            if (!rows.next()) return "none";
            String generation = rows.getString(1);
            try (java.sql.ResultSet label = statement.executeQuery(
                    "SELECT value FROM schema_meta WHERE `key` = 'hydrate_label'")) {
                String text = label.next() ? label.getString(1) : "";
                return generation + " " + text;
            }
        } catch (SQLException failure) {
            return "unavailable";
        }
    }

    private static List<String> combine() {
        List<String> all = new ArrayList<>();
        all.addAll(SOURCE_DDL);
        all.addAll(RUN_DDL);
        all.addAll(BRANCH_DDL);
        all.add(UPSERT_META);
        all.add(UPSERT_PROVENANCE);
        all.add(COLUMNS);
        all.add(ACTIVE_BRANCH);
        all.addAll(FILTERS);
        all.addAll(DoltStatements.ALL);
        return List.copyOf(all);
    }
}
