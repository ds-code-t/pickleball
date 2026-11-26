package tools.dscode.store.sql;

import java.util.List;

/** Ported Store SQL. Generated from the Store statements; SQLite still runs the product until P3. */
public final class SqlStatements {
    private SqlStatements() {}

    public static final List<String> ALL = List.of(
            """
SELECT started_at AS at, 'run' AS kind, run_id AS id, run_id AS run_id, status AS status,
                           scenario AS scenario_key, '' AS step_exec_id, '' AS step_text, '' AS definition_id,
                           '' AS entity_type, '' AS entity_id, '' AS field, '' AS operation, '' AS value_ref,
                           '' AS message, '' AS path, 0 AS kind_rank
                    FROM runs
                    UNION ALL
""",
            """
SELECT s.started_at AS at, 'step' AS kind, s.step_exec_id AS id, e.run_id AS run_id, s.status AS status,
                       e.scenario_key AS scenario_key, s.step_exec_id AS step_exec_id, s.step_text AS step_text,
                       COALESCE(s.definition_id, '') AS definition_id,
                       '' AS entity_type, '' AS entity_id, '' AS field, '' AS operation, '' AS value_ref,
                       '' AS message, '' AS path, 1 AS kind_rank
                FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                UNION ALL
                SELECT u.logged_at, 'usage', CAST(u.usage_id AS TEXT), e.run_id, s.status, e.scenario_key,
                       u.step_exec_id, s.step_text, COALESCE(s.definition_id, ''),
                       u.entity_type, u.entity_id, COALESCE(u.field, ''), u.operation, COALESCE(u.value_ref, ''),
                       '', '', 2
                FROM entity_usages u
                JOIN step_executions s ON s.step_exec_id = u.step_exec_id
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                UNION ALL
                SELECT g.logged_at, 'log', CAST(l.log_id AS TEXT), g.run_id, runs.status, runs.scenario,
                       '', '', '',
                       l.entity_type, l.entity_id, '', '', '',
                       g.message, '', 3
                FROM log_entity_links l
                JOIN run_logs g ON g.id = l.log_id
                JOIN runs ON runs.run_id = g.run_id
""",
            """
SELECT e.run_id FROM entity_usages u JOIN step_executions s ON s.step_exec_id = u.step_exec_id
""",
            """
SELECT u.step_exec_id FROM entity_usages u WHERE u.entity_type = ?
""",
            """
SELECT s.step_exec_id, s.scenario_exec_id, e.run_id, s.step_ord, s.step_text, s.definition_id, s.status
                     FROM step_executions s
                     JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                     WHERE e.run_id = ?
                     ORDER BY s.step_ord, s.step_exec_id
""",
            """
INSERT INTO runs(run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox)
                VALUES (?, ?, ?, 'RUNNING', ?, 0, 0, '', ?)
                ON CONFLICT(run_id) DO UPDATE SET finished_at = excluded.finished_at, sandbox = excluded.sandbox
""",
            """
INSERT INTO scenario_executions(
                    exec_id, run_id, scenario_key, scenario_hash, examples_row, started_at, finished_at, status, passed, failed, sandbox)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, 0, 0, ?)
                ON CONFLICT(exec_id) DO UPDATE SET scenario_hash = excluded.scenario_hash, sandbox = excluded.sandbox
""",
            """
INSERT INTO step_executions(
                    step_exec_id, scenario_exec_id, step_ord, step_text, step_text_hash, definition_id,
                    source_line, started_at, finished_at, status, sandbox)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(step_exec_id) DO UPDATE SET
                    definition_id = excluded.definition_id,
                    status = excluded.status,
                    finished_at = excluded.finished_at,
                    source_line = COALESCE(excluded.source_line, step_executions.source_line),
                    sandbox = excluded.sandbox
""",
            """
SELECT COUNT(*) FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                WHERE e.run_id = ? AND s.status = 'PASSED'
""",
            """
SELECT COUNT(*) FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                WHERE e.run_id = ? AND s.status = 'FAILED'
""",
            """
UPDATE step_executions SET status = ?, finished_at = ? WHERE step_exec_id = ?
""",
            """
UPDATE scenario_executions SET status = ?, finished_at = ? WHERE exec_id = ? AND status = 'RUNNING'
""",
            """
SELECT COUNT(*) FROM step_executions WHERE scenario_exec_id = ? AND status = 'PASSED'
""",
            """
SELECT COUNT(*) FROM step_executions WHERE scenario_exec_id = ? AND status = 'FAILED'
""",
            """
UPDATE scenario_executions SET passed = ?, failed = ?, status = ?, finished_at = ? WHERE exec_id = ?
""",
            """
UPDATE runs SET passed = ?, failed = ?, status = ?, finished_at = ? WHERE run_id = ?
""",
            """
SELECT definition_id, pattern FROM step_definitions
""",
            """
INSERT INTO artifacts(artifact_id, run_id, step_exec_id, kind, path, retained_at, sandbox)
                    VALUES (?, ?, ?, 'screenshot', ?, ?, ?)
                    ON CONFLICT(artifact_id) DO UPDATE SET
                        step_exec_id = COALESCE(excluded.step_exec_id, artifacts.step_exec_id),
                        path = excluded.path,
                        sandbox = excluded.sandbox
""",
            """
SELECT artifact_id, run_id, step_exec_id, kind, path, retained_at
                     FROM artifacts WHERE run_id = ? ORDER BY path
""",
            """
SELECT d.file_path, d.keyword, d.name, d.language, d.tags, d.description,
                            (SELECT COUNT(*) FROM scenarios s WHERE s.file_path = d.file_path)
                     FROM feature_docs d
                     ORDER BY d.file_path
""",
            """
SELECT file_path, ord, keyword, name, line, tags, description
                     FROM scenarios
                     WHERE (? IS NULL OR file_path = ?)
                     ORDER BY file_path, ord
""",
            """
SELECT s.file_path, s.scenario_ord, sc.name, s.ord, s.keyword, s.text, s.line,
                            s.docstring, s.docstring_type, s.table_json
                     FROM steps s
                     LEFT JOIN scenarios sc ON sc.file_path = s.file_path AND sc.ord = s.scenario_ord
                     WHERE (? IS NULL OR s.file_path = ?)
                       AND (? IS NULL OR s.scenario_ord = ?)
                       AND (? IS NULL OR sc.name = ?)
                     ORDER BY s.file_path, s.scenario_ord, s.ord
""",
            """
SELECT path, source_type, db_edited, length(raw_content), content_hash, last_synced_hash FROM files ORDER BY path
""",
            """
INSERT INTO step_definitions(
                    definition_id, language, pattern, method_name, source_path, source_line, content_hash, hydrated_at, removed, host)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT(definition_id) DO UPDATE SET
                    language = excluded.language,
                    pattern = excluded.pattern,
                    method_name = excluded.method_name,
                    source_path = excluded.source_path,
                    source_line = excluded.source_line,
                    content_hash = excluded.content_hash,
                    hydrated_at = excluded.hydrated_at,
                    removed = 0,
                    host = excluded.host
""",
            """
SELECT definition_id, language, pattern, method_name, source_path, source_line, content_hash
                     FROM step_definitions
                     WHERE removed = 0
                     ORDER BY source_path, source_line, definition_id
""",
            """
UPDATE step_executions SET definition_id = ? WHERE definition_id = ?
""",
            """
SELECT COUNT(*) FROM step_executions WHERE definition_id = ?
""",
            """
DELETE FROM step_definitions WHERE definition_id = ?
""",
            """
UPDATE step_definitions SET removed = 1, hydrated_at = ? WHERE definition_id = ?
""",
            """
SELECT definition_id, language, pattern, source_path FROM step_definitions
""",
            """
UPDATE provenance SET hydration_id = ?, last_hydrated_at = ?, schema_version = ?,
                                git_commit = ?, git_branch = ?, git_remote = ?, github_repo = ?, git_dirty = ?
                            WHERE id = 1
""",
            """
SELECT COUNT(*) FROM files
""",
            """
SELECT COUNT(*) FROM scenarios
""",
            """
SELECT COUNT(*) FROM entities
""",
            """
SELECT COUNT(*) FROM runs
""",
            """
SELECT COUNT(*) FROM files WHERE binary_ref = 1
""",
            """
DELETE FROM steps WHERE file_path = ?
""",
            """
DELETE FROM scenarios WHERE file_path = ?
""",
            """
DELETE FROM feature_docs WHERE file_path = ?
""",
            """
DELETE FROM entities WHERE source_file = ?
""",
            """
DELETE FROM files WHERE path = ?
""",
            """
INSERT OR IGNORE INTO example_blocks(file_path, scenario_ord, ord, line, tags)
                VALUES (?, ?, ?, ?, ?)
""",
            """
SELECT value FROM schema_meta WHERE key = 'schema_version'
""",
            """
SELECT value FROM schema_meta WHERE key = 'example_blocks'
""",
            """
SELECT file_path, scenario_ord, ord, line, text FROM steps WHERE keyword = 'Examples'
""",
            """
INSERT INTO schema_meta(key, value) VALUES ('example_blocks', '1')
""",
            """
INSERT INTO runs(run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(run_id) DO UPDATE SET
                            finished_at = excluded.finished_at,
                            status = excluded.status,
                            scenario = excluded.scenario,
                            passed = excluded.passed,
                            failed = excluded.failed,
                            note = excluded.note,
                            sandbox = excluded.sandbox
""",
            """
SELECT l.log_id, l.entity_type, l.entity_id
                     FROM log_entity_links l
                     JOIN run_logs r ON r.id = l.log_id
                     WHERE r.run_id = ?
                     ORDER BY l.log_id, l.entity_type, l.entity_id
""",
            """
INSERT OR IGNORE INTO log_entity_links(log_id, entity_type, entity_id, sandbox) VALUES (?, ?, ?, ?)
""",
            """
INSERT INTO run_logs(run_id, logged_at, entity, message, sandbox) VALUES (?, ?, ?, ?, ?)
""",
            """
SELECT run_id, started_at, finished_at, status, scenario, passed, failed, note FROM runs ORDER BY started_at, run_id
""",
            """
SELECT run_id, started_at, finished_at, status, scenario, passed, failed, note FROM runs WHERE run_id = ?
""",
            """
SELECT id, logged_at, entity, message FROM run_logs WHERE run_id = ? ORDER BY id
""",
            """
DELETE FROM runs WHERE run_id = ?
""",
            """
SELECT note, scenario FROM runs
""",
            """
SELECT message FROM run_logs
""",
            """
INSERT INTO feature_docs(file_path, keyword, name, language, tags, description)
                VALUES (?, ?, ?, ?, ?, ?)
""",
            """
INSERT INTO scenarios(file_path, ord, keyword, name, line, tags, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
""",
            """
INSERT INTO steps(file_path, scenario_ord, ord, keyword, text, line, docstring, docstring_type, table_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
""",
            """
INSERT INTO example_blocks(file_path, scenario_ord, ord, line, tags)
                VALUES (?, ?, ?, ?, ?)
""",
            """
SELECT 1 FROM feature_docs WHERE file_path = ?
""",
            """
DELETE FROM example_blocks WHERE file_path = ?
""",
            """
INSERT INTO entity_usages(step_exec_id, entity_type, entity_id, field, operation, value_ref, logged_at, sandbox)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
""",
            """
SELECT usage_id, step_exec_id, entity_type, entity_id, field, operation, value_ref, logged_at
                     FROM entity_usages ORDER BY usage_id
""",
            """
INSERT INTO entities(entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                    version = excluded.version,
                    payload_json = excluded.payload_json,
                    secret_refs = excluded.secret_refs,
                    source_file = excluded.source_file,
                    content_hash = excluded.content_hash,
                    updated_at = excluded.updated_at
""",
            """
SELECT entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash FROM entities ORDER BY entity_type, entity_id
""",
            """
DELETE FROM entities WHERE entity_type = ? AND entity_id = ?
""",
            """
SELECT entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash FROM entities WHERE entity_type = ? AND entity_id = ?
""",
            """
INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    content_hash = excluded.content_hash,
                    raw_content = excluded.raw_content,
                    mtime = excluded.mtime,
                    source_type = excluded.source_type,
                    last_hydrated_at = excluded.last_hydrated_at,
                    last_synced_hash = excluded.last_synced_hash,
                    db_edited = excluded.db_edited,
                    git_blob = excluded.git_blob,
                    binary_ref = excluded.binary_ref,
                    byte_size = excluded.byte_size,
                    location = excluded.location,
                    git_commit = excluded.git_commit
""",
            """
SELECT path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit FROM files
""",
            """
SELECT path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit FROM files WHERE path = ?
""",
            """
SELECT hydration_id FROM provenance WHERE id = 1
""",
            """
SELECT path, content_hash FROM files WHERE source_type IN ('feature', 'call', 'component') ORDER BY path
"""
    );
}
