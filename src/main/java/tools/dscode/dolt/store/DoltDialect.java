package tools.dscode.dolt.store;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dolt schema and the ported statement catalog. Column types are the P2 finals:
 * path keys {@code VARCHAR(1024)}, file bytes {@code LONGBLOB}, ids {@code BIGINT AUTO_INCREMENT}.
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
            "DaltonContent.put",
            "DaltonContent.delete",
            "DaltonEntities.put",
            "DaltonEntities.remove",
            "DaltonExecution.recordStep",
            "DaltonExecution.beforeStep",
            "DaltonExtractor.extract",
            "DaltonHistory.record",
            "DaltonHistory.retain",
            "DaltonHydrator.hydrate",
            "DaltonStepCatalog.refresh",
            "DoltDialect.ensureSchema");

    public static final List<String> SOURCE_DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS schema_meta (
                `key` VARCHAR(128) NOT NULL,
                value VARCHAR(1024) NOT NULL,
                PRIMARY KEY (`key`)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS files (
                path VARCHAR(1024) NOT NULL,
                content_hash VARCHAR(128) NOT NULL,
                raw_content LONGBLOB NOT NULL,
                mtime BIGINT NOT NULL,
                source_type VARCHAR(64) NOT NULL,
                last_hydrated_at VARCHAR(64) NOT NULL,
                last_synced_hash VARCHAR(128) NOT NULL,
                db_edited BIGINT NOT NULL DEFAULT 0,
                git_blob VARCHAR(128),
                binary_ref BIGINT NOT NULL DEFAULT 0,
                byte_size BIGINT,
                PRIMARY KEY (path)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS provenance (
                id BIGINT NOT NULL,
                schema_version BIGINT NOT NULL,
                hydration_id VARCHAR(128),
                last_hydrated_at VARCHAR(64),
                git_commit VARCHAR(64),
                git_branch VARCHAR(256),
                git_remote VARCHAR(512),
                github_repo VARCHAR(256),
                git_dirty BIGINT,
                PRIMARY KEY (id),
                CHECK (id = 1)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS feature_docs (
                file_path VARCHAR(1024) NOT NULL,
                keyword VARCHAR(64) NOT NULL,
                name VARCHAR(1024) NOT NULL,
                language VARCHAR(32) NOT NULL,
                tags LONGTEXT NOT NULL,
                description LONGTEXT NOT NULL,
                PRIMARY KEY (file_path)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS scenarios (
                file_path VARCHAR(1024) NOT NULL,
                ord BIGINT NOT NULL,
                keyword VARCHAR(64) NOT NULL,
                name VARCHAR(1024) NOT NULL,
                line BIGINT NOT NULL,
                tags LONGTEXT NOT NULL,
                description LONGTEXT NOT NULL,
                PRIMARY KEY (file_path, ord)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS example_blocks (
                file_path VARCHAR(1024) NOT NULL,
                scenario_ord BIGINT NOT NULL,
                ord BIGINT NOT NULL,
                line BIGINT NOT NULL,
                tags LONGTEXT NOT NULL,
                PRIMARY KEY (file_path, scenario_ord, ord)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS steps (
                file_path VARCHAR(1024) NOT NULL,
                scenario_ord BIGINT NOT NULL,
                ord BIGINT NOT NULL,
                keyword VARCHAR(64) NOT NULL,
                text LONGTEXT NOT NULL,
                line BIGINT NOT NULL,
                docstring LONGTEXT,
                docstring_type VARCHAR(32),
                table_json LONGTEXT,
                PRIMARY KEY (file_path, scenario_ord, ord)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS entities (
                entity_type VARCHAR(128) NOT NULL,
                entity_id VARCHAR(256) NOT NULL,
                version BIGINT NOT NULL,
                payload_json LONGTEXT NOT NULL,
                secret_refs LONGTEXT NOT NULL,
                source_file VARCHAR(1024),
                content_hash VARCHAR(128) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                PRIMARY KEY (entity_type, entity_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS step_definitions (
                definition_id VARCHAR(512) NOT NULL,
                language VARCHAR(32) NOT NULL,
                pattern LONGTEXT NOT NULL,
                method_name VARCHAR(512),
                source_path VARCHAR(1024) NOT NULL,
                source_line BIGINT NOT NULL,
                content_hash VARCHAR(128) NOT NULL,
                hydrated_at VARCHAR(64) NOT NULL,
                removed BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (definition_id)
            )
            """);

    public static final List<String> RUN_DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS runs (
                run_id VARCHAR(128) NOT NULL,
                started_at VARCHAR(64) NOT NULL,
                finished_at VARCHAR(64),
                status VARCHAR(32) NOT NULL,
                scenario VARCHAR(1024) NOT NULL,
                passed BIGINT NOT NULL DEFAULT 0,
                failed BIGINT NOT NULL DEFAULT 0,
                note LONGTEXT NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (run_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS run_logs (
                id BIGINT NOT NULL AUTO_INCREMENT,
                run_id VARCHAR(128) NOT NULL,
                logged_at VARCHAR(64) NOT NULL,
                entity VARCHAR(1024) NOT NULL,
                message LONGTEXT NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (id),
                FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS scenario_executions (
                exec_id VARCHAR(128) NOT NULL,
                run_id VARCHAR(128) NOT NULL,
                scenario_key VARCHAR(1024) NOT NULL,
                scenario_hash VARCHAR(128) NOT NULL,
                examples_row BIGINT,
                started_at VARCHAR(64) NOT NULL,
                finished_at VARCHAR(64),
                status VARCHAR(32) NOT NULL,
                passed BIGINT NOT NULL DEFAULT 0,
                failed BIGINT NOT NULL DEFAULT 0,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (exec_id),
                FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS step_executions (
                step_exec_id VARCHAR(128) NOT NULL,
                scenario_exec_id VARCHAR(128) NOT NULL,
                step_ord BIGINT NOT NULL,
                step_text LONGTEXT NOT NULL,
                step_text_hash VARCHAR(128) NOT NULL,
                definition_id VARCHAR(512),
                source_line BIGINT,
                started_at VARCHAR(64) NOT NULL,
                finished_at VARCHAR(64),
                status VARCHAR(32) NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (step_exec_id),
                FOREIGN KEY (scenario_exec_id) REFERENCES scenario_executions (exec_id) ON DELETE CASCADE,
                FOREIGN KEY (definition_id) REFERENCES step_definitions (definition_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS entity_usages (
                usage_id BIGINT NOT NULL AUTO_INCREMENT,
                step_exec_id VARCHAR(128) NOT NULL,
                entity_type VARCHAR(128) NOT NULL,
                entity_id VARCHAR(256) NOT NULL,
                field VARCHAR(256),
                operation VARCHAR(32) NOT NULL,
                value_ref VARCHAR(1024),
                logged_at VARCHAR(64) NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (usage_id),
                FOREIGN KEY (step_exec_id) REFERENCES step_executions (step_exec_id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS log_entity_links (
                log_id BIGINT NOT NULL,
                entity_type VARCHAR(128) NOT NULL,
                entity_id VARCHAR(256) NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (log_id, entity_type, entity_id),
                FOREIGN KEY (log_id) REFERENCES run_logs (id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS artifacts (
                artifact_id VARCHAR(128) NOT NULL,
                run_id VARCHAR(128),
                step_exec_id VARCHAR(128),
                kind VARCHAR(64) NOT NULL,
                path VARCHAR(1024) NOT NULL,
                retained_at VARCHAR(64) NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (artifact_id),
                FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE,
                FOREIGN KEY (step_exec_id) REFERENCES step_executions (step_exec_id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sandboxes (
                name VARCHAR(48) NOT NULL,
                owner VARCHAR(128) NOT NULL,
                base_commit VARCHAR(64) NOT NULL,
                created VARCHAR(64) NOT NULL,
                last_used VARCHAR(64) NOT NULL,
                ttl VARCHAR(64) NOT NULL,
                sandbox VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (name)
            )
            """);

    public static final String UPSERT_META = """
            INSERT INTO schema_meta(`key`, value) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE value = VALUES(value)
            """;

    public static final String UPSERT_PROVENANCE = """
            INSERT INTO provenance(id, schema_version) VALUES (1, ?)
            ON DUPLICATE KEY UPDATE schema_version = VALUES(schema_version)
            """;

    public static final String COLUMNS = """
            SELECT column_name, data_type, column_key FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ?
            """;

    public static final String ACTIVE_BRANCH = "SELECT active_branch()";

    public static final List<String> FILTERS = List.of(
            tagLike("sc.tags"),
            taggedScenario("scenario_key"),
            taggedExamples("scenario_key"),
            "AND LOWER(status) = LOWER(?)",
            "AND instr(scenario_key, ?) > 0",
            "AND (scenario_key LIKE ? ESCAPE '\\\\' OR (kind = 'run' AND run_id IN"
                    + " (SELECT e.run_id FROM scenario_executions e WHERE e.scenario_key LIKE ? ESCAPE '\\\\')))");

    private static final List<String> ALL = combine();

    private DoltDialect() {
    }

    public static List<String> statements() {
        return ALL;
    }

    public static String tagLike(String column) {
        return "CONCAT(' ', " + column + ", ' ') LIKE ? ESCAPE '\\\\'";
    }

    public static String taggedScenario(String keyExpr) {
        return "instr(" + keyExpr + ", CONCAT(sc.file_path, '|')) = 1"
                + " AND CAST(substr(" + keyExpr + ", CHAR_LENGTH(sc.file_path) + 2) AS SIGNED) >= sc.line"
                + " AND CAST(substr(" + keyExpr + ", CHAR_LENGTH(sc.file_path) + 2) AS SIGNED) < COALESCE("
                + "(SELECT MIN(nx.line) FROM scenarios nx WHERE nx.file_path = sc.file_path AND nx.line > sc.line),"
                + " 2147483647)";
    }

    public static String taggedExamples(String keyExpr) {
        return "instr(" + keyExpr + ", CONCAT(eb.file_path, '|')) = 1"
                + " AND CAST(substr(" + keyExpr + ", CHAR_LENGTH(eb.file_path) + 2) AS SIGNED) >= eb.line"
                + " AND CAST(substr(" + keyExpr + ", CHAR_LENGTH(eb.file_path) + 2) AS SIGNED) < COALESCE("
                + "(SELECT MIN(nx.line) FROM steps nx WHERE nx.file_path = eb.file_path"
                + " AND nx.scenario_ord = eb.scenario_ord AND nx.keyword = 'Examples' AND nx.line > eb.line),"
                + "(SELECT MIN(sc.line) FROM scenarios sc WHERE sc.file_path = eb.file_path AND sc.line > eb.line),"
                + " 2147483647)";
    }

    public static void ensureSchema(DoltSession session) throws SQLException {
        String lock = session.scalar("SELECT GET_LOCK('pkb:hydrate:main', 30)");
        if (!"1".equals(lock)) throw new SQLException("error: could not lock hydrate on main");
        try {
            boolean created = false;
            for (String ddl : SOURCE_DDL) session.script(ddl);
            if (!"1".equals(session.scalar("SELECT value FROM schema_meta WHERE `key` = 'dolt_schema'"))) {
                session.update(UPSERT_META, "dolt_schema", "1");
                session.update(UPSERT_META, "schema_version", "1");
                session.update(UPSERT_PROVENANCE, SCHEMA_VERSION);
                created = true;
            }
            if (created) {
                session.script(doltAdd());
                try {
                    session.script("CALL DOLT_COMMIT('-m', 'schema dolt_schema=1')");
                } catch (SQLException failure) {
                    if (!nothingToCommit(failure)) throw failure;
                }
            }
            for (String ddl : RUN_DDL) session.script(ddl);
        } finally {
            try {
                session.scalar("SELECT RELEASE_LOCK('pkb:hydrate:main')");
            } catch (SQLException ignored) {
                // The lock is also released when this connection closes.
            }
        }
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

    private static String doltAdd() {
        StringBuilder sql = new StringBuilder("CALL DOLT_ADD(");
        for (int index = 0; index < SOURCE_TABLES.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append("'").append(SOURCE_TABLES.get(index)).append("'");
        }
        return sql.append(")").toString();
    }

    private static boolean nothingToCommit(SQLException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("nothing to commit") || message.contains("no changes");
    }

    private static List<String> combine() {
        List<String> all = new ArrayList<>();
        all.addAll(SOURCE_DDL);
        all.addAll(RUN_DDL);
        all.add(UPSERT_META);
        all.add(UPSERT_PROVENANCE);
        all.add(COLUMNS);
        all.add(ACTIVE_BRANCH);
        all.addAll(FILTERS);
        all.addAll(DoltStatements.ALL);
        return List.copyOf(all);
    }
}
