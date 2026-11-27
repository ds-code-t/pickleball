package tools.dscode.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Records scenario and step executions for one run. Ids are derived, so a
 * second write of the same step updates the row. Cleartext secrets are not stored.
 */
public final class StoreExecution {
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private StoreExecution() {
    }

    public record StepDraft(
            String runId,
            String scenarioKey,
            String scenarioName,
            String scenarioHash,
            Integer examplesRow,
            int stepOrd,
            String stepText,
            Integer sourceLine,
            String status,
            String startedAt,
            String finishedAt
    ) {
    }

    public record StepRow(
            String stepExecId,
            String scenarioExecId,
            String runId,
            int stepOrd,
            String stepText,
            String definitionId,
            String status
    ) {
    }

    /** Inserts or updates one finished step and the scenario and run around it. */
    public static String recordStep(Path project, StepDraft draft) throws SQLException {
        if (project == null || draft == null) throw new IllegalArgumentException("execution record needs a project");
        String runId = requireRun(draft.runId());
        String scenarioKey = draft.scenarioKey() == null || draft.scenarioKey().isBlank() ? "scenario|0" : draft.scenarioKey();
        String text = draft.stepText() == null ? "" : draft.stepText();
        String hash = StoreHashes.sha256(text.getBytes(StandardCharsets.UTF_8));
        String execId = executionId(runId, scenarioKey, draft.examplesRow());
        String stepId = stepId(execId, draft.stepOrd(), hash);
        String now = Instant.now().toString();
        String started = draft.startedAt() == null || draft.startedAt().isBlank() ? now : draft.startedAt();
        String finished = draft.finishedAt() == null || draft.finishedAt().isBlank() ? now : draft.finishedAt();
        String status = status(draft.status());
        String scenarioHash = draft.scenarioHash() == null || draft.scenarioHash().isBlank()
                ? StoreHashes.sha256((scenarioKey + "\n" + text).getBytes(StandardCharsets.UTF_8))
                : draft.scenarioHash();
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            connection.setAutoCommit(false);
            try {
                String definition = match(connection, text);
                ensureRun(connection, runId, started, finished, recordedScenario(draft.scenarioName()));
                RunProvenance.fill(connection, project, runId, scenarioKey);
                ensureScenario(connection, execId, runId, scenarioKey, scenarioHash, draft.examplesRow(), started, status);
                writeStep(connection, stepId, execId, draft.stepOrd(), text, hash, definition, draft.sourceLine(), started, finished, status);
                recount(connection, execId, runId, finished);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        StoreLog.log(project, "track", runId, "step " + status + " " + text);
        return stepId;
    }

    public static List<StepRow> steps(Path project, String runId) throws SQLException {
        if (!StorePaths.ready(project)) return List.of();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT s.step_exec_id, s.scenario_exec_id, e.run_id, s.step_ord, s.step_text, s.definition_id, s.status
                     FROM step_executions s
                     JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                     WHERE e.run_id = ?
                     ORDER BY s.step_ord, s.step_exec_id
                     """)) {
            statement.setString(1, requireRun(runId));
            try (ResultSet rows = statement.executeQuery()) {
                List<StepRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new StepRow(
                            rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getInt(4), rows.getString(5), rows.getString(6), rows.getString(7)));
                }
                return found;
            }
        }
    }

    public static void beforeStep(Path project, String scenarioName, String uri, int scenarioLine, String stepText, int stepLine) {
        beforeStep(project, scenarioName, uri, scenarioLine, stepText, stepLine, null);
    }

    /**
     * Opens a running step when tracking is on. Lookup during the step can attach
     * entity usages. Failures here never fail the scenario.
     * {@code examplesRow} is the 1-based Scenario Outline row, or null for a plain scenario.
     * The database is {@link StoreConfig#project()} when {@code pkb_store_project} is set.
     */
    public static void beforeStep(
            Path project, String scenarioName, String uri, int scenarioLine, String stepText, int stepLine, Integer examplesRow
    ) {
        if (!shouldTrack()) return;
        try {
            Path root = resolveProject(project);
            Active active = active(root, scenarioName, uri, scenarioLine, examplesRow);
            String text = stepText == null ? "" : stepText;
            if (active.current != null && text.equals(active.currentText)) {
                StoreRunContext.activate(root, active.current);
                return;
            }
            int ord = ++active.nextOrd;
            String hash = StoreHashes.sha256(text.getBytes(StandardCharsets.UTF_8));
            String stepExec = stepId(active.execId, ord, hash);
            String now = Instant.now().toString();
            active.texts.add(text);
            try (StoreDatabase database = StoreDatabase.open(root)) {
                Connection connection = database.connection();
                connection.setAutoCommit(false);
                try {
                    ensureRun(connection, active.runId, active.started, now, active.recordedScenario);
                    RunSnapshot.capture(connection, root, active.runId, active.started);
                    RunProvenance.fill(connection, root, active.runId, active.scenarioKey);
                    ensureScenario(connection, active.execId, active.runId, active.scenarioKey, active.scenarioHash(),
                            active.examplesRow, active.started, "RUNNING");
                    writeStep(connection, stepExec, active.execId, ord, text, hash, match(connection, text),
                            stepLine > 0 ? stepLine : null, now, null, "RUNNING");
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            active.current = stepExec;
            active.currentText = text;
            StoreRunContext.activate(root, stepExec);
        } catch (RuntimeException | SQLException ignored) {
            StoreRunContext.clear();
        }
    }

    /** Closes the step opened by {@link #beforeStep}. A finish without a start still upserts. */
    public static void afterStep(String status) {
        if (!shouldTrack()) return;
        try {
            Active active = ACTIVE.get();
            if (active == null || active.current == null) {
                return;
            }
            String now = Instant.now().toString();
            String normalized = status(status);
            try (StoreDatabase database = StoreDatabase.open(active.project)) {
                Connection connection = database.connection();
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE step_executions SET status = ?, finished_at = ? WHERE step_exec_id = ?")) {
                    statement.setString(1, normalized);
                    statement.setString(2, now);
                    statement.setString(3, active.current);
                    statement.executeUpdate();
                }
                recount(connection, active.execId, active.runId, now);
            }
            StoreLog.log(active.project, "track", active.runId, "step " + normalized);
        } catch (RuntimeException | SQLException ignored) {
            // A tracking write must not change the scenario result.
        } finally {
            Active active = ACTIVE.get();
            if (active != null) {
                active.current = null;
                active.currentText = null;
            }
            StoreRunContext.clear();
        }
    }

    /** Appends one finished nested step under the scenario already opened on this thread. */
    public static void appendFinishedStep(
            Path project, String scenarioName, String uri, int scenarioLine, String stepText, int stepLine, String status, Integer examplesRow
    ) {
        if (!shouldTrack()) return;
        try {
            Path root = resolveProject(project);
            Active active = active(root, scenarioName, uri, scenarioLine, examplesRow);
            String text = stepText == null ? "" : stepText;
            int ord = ++active.nextOrd;
            if (active.pluginOrd < ord) active.pluginOrd = ord;
            while (active.texts.size() < ord - 1) active.texts.add("");
            if (active.texts.size() == ord - 1) active.texts.add(text);
            else if (active.texts.size() >= ord) active.texts.set(ord - 1, text);
            recordStep(root, new StepDraft(
                    active.runId,
                    active.scenarioKey,
                    active.recordedScenario,
                    null,
                    active.examplesRow,
                    ord,
                    text,
                    stepLine > 0 ? stepLine : null,
                    status,
                    active.started,
                    Instant.now().toString()));
        } catch (RuntimeException | SQLException ignored) {
            // A tracking write must not change the scenario result.
        }
    }

    public static void finishUnopenedStep(Path project, String scenarioName, String uri, int scenarioLine, String stepText, int stepLine, String status) {
        finishUnopenedStep(project, scenarioName, uri, scenarioLine, stepText, stepLine, status, null);
    }

    /** Plugin finish for a step that was not opened with {@link #beforeStep}. */
    public static void finishUnopenedStep(
            Path project, String scenarioName, String uri, int scenarioLine, String stepText, int stepLine, String status, Integer examplesRow
    ) {
        if (!shouldTrack()) return;
        try {
            Path root = resolveProject(project);
            Active active = active(root, scenarioName, uri, scenarioLine, examplesRow);
            String text = stepText == null ? "" : stepText;
            int ord;
            if (active.pluginOrd < active.texts.size() && text.equals(active.texts.get(active.pluginOrd))) {
                ord = ++active.pluginOrd;
            } else {
                ord = active.nextOrd > active.pluginOrd ? ++active.pluginOrd : ++active.nextOrd;
                if (active.pluginOrd < ord) active.pluginOrd = ord;
                while (active.texts.size() < ord - 1) active.texts.add("");
                if (active.texts.size() == ord - 1) active.texts.add(text);
            }
            if (ord > active.nextOrd) active.nextOrd = ord;
            recordStep(root, new StepDraft(
                    active.runId,
                    active.scenarioKey,
                    active.recordedScenario,
                    null,
                    active.examplesRow,
                    ord,
                    stepText,
                    stepLine > 0 ? stepLine : null,
                    status,
                    active.started,
                    Instant.now().toString()));
        } catch (RuntimeException | SQLException ignored) {
        }
    }

    public static void finishScenario(String status) {
        if (!shouldTrack()) {
            ExceptionCluster.clear();
            return;
        }
        try {
            Active active = ACTIVE.get();
            if (active == null) return;
            String now = Instant.now().toString();
            try (StoreDatabase database = StoreDatabase.open(active.project)) {
                recount(database.connection(), active.execId, active.runId, now);
                try (PreparedStatement statement = database.connection().prepareStatement(
                        "UPDATE scenario_executions SET status = ?, finished_at = ? WHERE exec_id = ? AND (status = 'RUNNING' OR ? = 'FAILED')")) {
                    statement.setString(1, status(status));
                    statement.setString(2, now);
                    statement.setString(3, active.execId);
                    statement.setString(4, status(status));
                    statement.executeUpdate();
                }
                if ("FAILED".equals(status(status))) {
                    try (PreparedStatement statement = database.connection().prepareStatement(
                            "UPDATE runs SET status = 'FAILED', failed = CASE WHEN failed < 1 THEN 1 ELSE failed END WHERE run_id = ?")) {
                        statement.setString(1, active.runId);
                        statement.executeUpdate();
                    }
                    ExceptionCluster.write(database.connection(), active.runId);
                }
            }
            RunCatalog.publishStore(active.project, active.runId);
        } catch (RuntimeException | SQLException ignored) {
        } finally {
            ExceptionCluster.clear();
            ACTIVE.remove();
            StoreRunContext.clear();
        }
    }

    /** Records the failure while the step context is still active. Tracking failures stay quiet. */
    public static void noteFailure(Throwable failure) {
        try {
            ExceptionCluster.note(failure);
        } catch (RuntimeException ignored) {
        }
    }

    /** Run id of a tracked step. Both tables are run-state, so the join stays on runstate.db. */
    static String runOfStep(Path project, String stepExecId) throws SQLException {
        if (project == null || stepExecId == null || stepExecId.isBlank()) return null;
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT e.run_id
                     FROM step_executions s
                     JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                     WHERE s.step_exec_id = ?
                     """)) {
            statement.setString(1, stepExecId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    public static boolean shouldTrack() {
        try {
            if (!StoreConfig.enabled()) return false;
        } catch (RuntimeException failure) {
            return false;
        }
        String track = System.getProperty("pkb_store_track");
        if (track != null && !track.isBlank() && StoreConfig.falsy(track)) return false;
        if (track != null && !track.isBlank() && StoreConfig.truthy(track)) return true;
        String run = System.getProperty("pkb_run_id");
        return run != null && !run.isBlank();
    }

    public static String executionId(String runId, String scenarioKey, Integer examplesRow) {
        return runId + "|" + scenarioKey + "|" + (examplesRow == null ? "" : examplesRow);
    }

    public static String stepId(String executionId, int stepOrd, String stepTextHash) {
        return executionId + "|" + stepOrd + "|" + stepTextHash;
    }

    static String scenarioKey(String uri, int line) {
        String path = uri == null ? "" : uri.trim();
        if (path.startsWith("file:")) {
            try {
                path = Path.of(java.net.URI.create(path)).toString();
            } catch (RuntimeException ignored) {
                path = path.substring("file:".length());
            }
        }
        path = path.replace('\\', '/');
        String root = StoreConfig.project().toString().replace('\\', '/');
        if (path.startsWith(root + "/")) path = path.substring(root.length() + 1);
        int slash = path.indexOf(":/");
        if (slash > 1 && slash < 4) {
            // absolute after a drive prefix; keep the tail after the project name is unknown
        }
        if (path.isBlank()) path = "memory";
        return path + "|" + Math.max(line, 0);
    }

    /** A new id in the same shape as a diagnostic run id: UTC stamp, hyphen, 8 hex characters. */
    public static String newRunId() {
        return RUN_STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** True while this thread still has an open tracked scenario. */
    static boolean activePresent() {
        return ACTIVE.get() != null;
    }

    /**
     * One top-level {@code PickleballEngine.run}. A self-minted id is set for the
     * call, including a diagnostic reporter that starts during it, then removed.
     * A caller-set {@code pkb_run_id}, an active diagnostic id, and a nested run
     * are left alone.
     */
    public static final class RunLease {
        private final String previous;
        private final boolean restore;

        private RunLease(String previous, boolean restore) {
            this.previous = previous;
            this.restore = restore;
        }

        public static RunLease open(boolean topLevel) {
            if (!topLevel) return idle();
            String previous = System.getProperty("pkb_run_id");
            if (previous != null && !previous.isBlank()) return idle();
            String diagnostic = tools.dscode.common.reporting.diagnostic.DiagnosticRuntime.runId();
            if (diagnostic != null && !diagnostic.isBlank()) return idle();
            if (!trackingWithoutRunId()) return idle();
            System.setProperty("pkb_run_id", newRunId());
            return new RunLease(previous, true);
        }

        private static RunLease idle() {
            return new RunLease(null, false);
        }

        public void close() {
            if (!restore) return;
            if (previous == null) System.clearProperty("pkb_run_id");
            else System.setProperty("pkb_run_id", previous);
        }
    }

    private static boolean trackingWithoutRunId() {
        try {
            if (!StoreConfig.enabled()) return false;
        } catch (RuntimeException failure) {
            return false;
        }
        String track = System.getProperty("pkb_store_track");
        return track != null && !track.isBlank() && StoreConfig.truthy(track);
    }

    static String runId() {
        String raw = System.getProperty("pkb_run_id");
        if (raw != null && !raw.isBlank()) {
            return tools.dscode.common.coordination.AgentCoordination.requireSafeId(raw.trim(), "run id");
        }
        String diagnostic = tools.dscode.common.reporting.diagnostic.DiagnosticRuntime.runId();
        if (diagnostic != null && !diagnostic.isBlank()) {
            return tools.dscode.common.coordination.AgentCoordination.requireSafeId(diagnostic.trim(), "run id");
        }
        String created = newRunId();
        System.setProperty("pkb_run_id", created);
        return created;
    }

    /**
     * Configured {@code pkb_store_project} wins over the caller path.
     * A caller path is only the working directory when that property is unset.
     */
    static Path resolveProject(Path passed) {
        String configured = System.getProperty("pkb_store_project");
        if (configured != null && !configured.isBlank()) return StoreConfig.project();
        if (passed != null) return passed.toAbsolutePath().normalize();
        return StoreConfig.project();
    }

    /**
     * A fork must not append to the run id it was copied from.
     * When this JVM is bound to a sandbox that does not own {@code pkb_run_id}, the id is replaced.
     * Main, and a run that already belongs to this sandbox, stay on the caller id.
     */
    public static void adoptFork(Path project) {
        try {
            tools.dscode.store.sql.Binding binding = StoreScope.current();
            if (binding == null || binding.primary()) return;
            String current = System.getProperty("pkb_run_id");
            if (current == null || current.isBlank()) return;
            Path root = resolveProject(project);
            String sandbox = null;
            try (Connection connection = tools.dscode.store.sql.SqliteOpen.open(StorePaths.runState(root));
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT sandbox FROM runs WHERE run_id = ?")) {
                statement.setString(1, current.trim());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return;
                    sandbox = rows.getString(1);
                }
            }
            if (sandbox == null) sandbox = "";
            if (binding.sandboxColumn().equals(sandbox)) return;
            System.setProperty("pkb_run_id", newRunId());
        } catch (RuntimeException | SQLException ignored) {
            // Tracking must not fail the scenario.
        }
    }

    private static Active active(Path project, String scenarioName, String uri, int scenarioLine, Integer examplesRow) {
        adoptFork(project);
        Active active = ACTIVE.get();
        String key = scenarioKey(uri, scenarioLine);
        String recorded = recordedScenario(scenarioName);
        String name = recorded.isEmpty() ? key : recorded;
        if (active != null && project.equals(active.project)
                && Objects.equals(active.examplesRow, examplesRow)
                && (key.equals(active.scenarioKey) || name.equals(active.scenarioName))) {
            return active;
        }
        Active created = new Active(project, runId(), name, key, examplesRow, recorded);
        ACTIVE.set(created);
        return created;
    }

    private static String recordedScenario(String scenarioName) {
        return scenarioName == null || scenarioName.isBlank() ? "" : scenarioName;
    }

    private static void ensureRun(Connection connection, String runId, String started, String finished, String scenario) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runs(run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox)
                VALUES (?, ?, ?, 'RUNNING', ?, 0, 0, '', ?)
                ON CONFLICT(run_id) DO UPDATE SET finished_at = excluded.finished_at, sandbox = excluded.sandbox
                """)) {
            statement.setString(1, runId);
            statement.setString(2, started);
            statement.setString(3, finished);
            statement.setString(4, scenario == null ? "" : scenario);
            statement.setString(5, StoreScope.current().sandboxColumn());
            statement.executeUpdate();
        }
    }

    private static void ensureScenario(
            Connection connection,
            String execId,
            String runId,
            String scenarioKey,
            String scenarioHash,
            Integer examplesRow,
            String started,
            String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scenario_executions(
                    exec_id, run_id, scenario_key, scenario_hash, examples_row, started_at, finished_at, status, passed, failed, sandbox)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, 0, 0, ?)
                ON CONFLICT(exec_id) DO UPDATE SET scenario_hash = excluded.scenario_hash, sandbox = excluded.sandbox
                """)) {
            statement.setString(1, execId);
            statement.setString(2, runId);
            statement.setString(3, scenarioKey);
            statement.setString(4, scenarioHash);
            if (examplesRow == null) statement.setNull(5, java.sql.Types.INTEGER);
            else statement.setInt(5, examplesRow);
            statement.setString(6, started);
            statement.setString(7, status);
            statement.setString(8, StoreScope.current().sandboxColumn());
            statement.executeUpdate();
        }
    }

    private static void writeStep(
            Connection connection,
            String stepId,
            String execId,
            int ord,
            String text,
            String hash,
            String definitionId,
            Integer sourceLine,
            String started,
            String finished,
            String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
                """)) {
            bindStep(statement, stepId, execId, ord, text, hash, definitionId, sourceLine, started, finished, status);
            try {
                statement.executeUpdate();
            } catch (SQLException failure) {
                if (definitionId == null || !foreignKey(failure)) throw failure;
                bindStep(statement, stepId, execId, ord, text, hash, null, sourceLine, started, finished, status);
                statement.executeUpdate();
            }
        }
    }

    private static void bindStep(
            PreparedStatement statement,
            String stepId,
            String execId,
            int ord,
            String text,
            String hash,
            String definitionId,
            Integer sourceLine,
            String started,
            String finished,
            String status
    ) throws SQLException {
        statement.setString(1, stepId);
        statement.setString(2, execId);
        statement.setInt(3, ord);
        statement.setString(4, text);
        statement.setString(5, hash);
        if (definitionId == null) statement.setNull(6, java.sql.Types.VARCHAR);
        else statement.setString(6, definitionId);
        if (sourceLine == null) statement.setNull(7, java.sql.Types.INTEGER);
        else statement.setInt(7, sourceLine);
        statement.setString(8, started);
        if (finished == null) statement.setNull(9, java.sql.Types.VARCHAR);
        else statement.setString(9, finished);
        statement.setString(10, status);
        statement.setString(11, StoreScope.current().sandboxColumn());
    }

    private static boolean foreignKey(SQLException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("foreign key");
    }

    private static void recount(Connection connection, String execId, String runId, String finished) throws SQLException {
        int passed = count(connection, "SELECT COUNT(*) FROM step_executions WHERE scenario_exec_id = ? AND status = 'PASSED'", execId);
        int failed = count(connection, "SELECT COUNT(*) FROM step_executions WHERE scenario_exec_id = ? AND status = 'FAILED'", execId);
        int skipped = count(connection, "SELECT COUNT(*) FROM step_executions WHERE scenario_exec_id = ? AND status = 'SKIPPED'", execId);
        String status = failed > 0 ? "FAILED" : passed > 0 ? "PASSED" : skipped > 0 ? "SKIPPED" : "RUNNING";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE scenario_executions SET passed = ?, failed = ?, status = ?, finished_at = ? WHERE exec_id = ?")) {
            statement.setInt(1, passed);
            statement.setInt(2, failed);
            statement.setString(3, status);
            statement.setString(4, finished);
            statement.setString(5, execId);
            statement.executeUpdate();
        }
        int runPassed = count(connection, """
                SELECT COUNT(*) FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                WHERE e.run_id = ? AND s.status = 'PASSED'
                """, runId);
        int runFailed = count(connection, """
                SELECT COUNT(*) FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                WHERE e.run_id = ? AND s.status = 'FAILED'
                """, runId);
        int runSkipped = count(connection, """
                SELECT COUNT(*) FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                WHERE e.run_id = ? AND s.status = 'SKIPPED'
                """, runId);
        String runStatus = runFailed > 0 ? "FAILED" : runPassed > 0 ? "PASSED" : runSkipped > 0 ? "SKIPPED" : "PASSED";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE runs SET passed = ?, failed = ?, status = ?, finished_at = ? WHERE run_id = ?")) {
            statement.setInt(1, runPassed);
            statement.setInt(2, runFailed);
            statement.setString(3, runStatus);
            statement.setString(4, finished);
            statement.setString(5, runId);
            statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String sql, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static final AtomicInteger DEFINITION_LOADS = new AtomicInteger();
    private static final ConcurrentHashMap<String, Catalog> DEFINITION_CATALOG = new ConcurrentHashMap<>();

    /** How many times recording loaded {@code step_definitions} instead of reusing the catalog. */
    public static int definitionLoads() {
        return DEFINITION_LOADS.get();
    }

    /** Tests share a JVM. Drop cached definitions so one project cannot match another's catalog. */
    static void clearCatalog() {
        DEFINITION_CATALOG.clear();
    }

    private record CompiledDef(String id, String pattern, Pattern placeholder, Pattern regex) {
    }

    private record Catalog(String stamp, List<CompiledDef> defs) {
    }

    static String match(Connection connection, String text) throws SQLException {
        List<CompiledDef> defs = catalog(connection);
        String bestId = null;
        int best = Integer.MIN_VALUE;
        for (CompiledDef def : defs) {
            int score = score(def, text);
            if (score > best) {
                best = score;
                bestId = def.id();
            }
        }
        return best < 0 ? null : bestId;
    }

    private static List<CompiledDef> catalog(Connection connection) throws SQLException {
        String url = catalogKey(connection);
        String stamp = stamp(connection);
        if (url != null) {
            Catalog cached = DEFINITION_CATALOG.get(url);
            if (cached != null && cached.stamp().equals(stamp)) return cached.defs();
        }
        DEFINITION_LOADS.incrementAndGet();
        List<CompiledDef> loaded = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, pattern FROM step_definitions WHERE removed = 0");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) loaded.add(compile(rows.getString(1), rows.getString(2)));
        }
        List<CompiledDef> frozen = List.copyOf(loaded);
        if (url != null) DEFINITION_CATALOG.put(url, new Catalog(stamp, frozen));
        return frozen;
    }

    private static String catalogKey(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA database_list")) {
            while (rows.next()) {
                if ("main".equalsIgnoreCase(rows.getString("name"))) {
                    String file = rows.getString("file");
                    if (file != null && !file.isBlank()) return file;
                }
            }
        } catch (SQLException ignored) {
            // The JDBC URL is enough when the pragma cannot be read.
        }
        try {
            String url = connection.getMetaData().getURL();
            if (url != null && url.contains("/")) return url;
        } catch (SQLException ignored) {
            // No stable key. The caller loads definitions and does not cache them.
        }
        return null;
    }

    private static String stamp(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), COALESCE(MAX(hydrated_at), '') FROM step_definitions WHERE removed = 0");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) return "0|";
            return rows.getInt(1) + "|" + (rows.getString(2) == null ? "" : rows.getString(2));
        }
    }

    private static CompiledDef compile(String id, String pattern) {
        return new CompiledDef(id, pattern, placeholderPattern(pattern), regexPattern(pattern));
    }

    static int score(String pattern, String text) {
        return score(compile(null, pattern), text);
    }

    private static int score(CompiledDef def, String text) {
        if (def == null || def.pattern() == null || text == null) return -1;
        if (def.pattern().equals(text)) return 1_000_000 + def.pattern().length();
        if (def.placeholder() != null && def.placeholder().matcher(text).matches()) return def.pattern().length();
        if (def.regex() != null && def.regex().matcher(text).matches()) return def.pattern().length();
        return -1;
    }

    private static Pattern placeholderPattern(String pattern) {
        if (pattern == null || pattern.indexOf('{') < 0) return null;
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < pattern.length(); ) {
            char ch = pattern.charAt(index);
            if (ch == '{') {
                int end = pattern.indexOf('}', index + 1);
                if (end > index) {
                    regex.append("(.+)");
                    index = end + 1;
                    continue;
                }
            }
            if ("\\.^$|?*+()[]{}".indexOf(ch) >= 0) regex.append('\\');
            regex.append(ch);
            index++;
        }
        return compileQuiet(regex.toString());
    }

    private static Pattern regexPattern(String pattern) {
        if (pattern == null) return null;
        if (pattern.startsWith("^") || (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() >= 2)) {
            String body = pattern;
            if (body.startsWith("/") && body.endsWith("/")) body = body.substring(1, body.length() - 1);
            return compileQuiet(body);
        }
        return null;
    }

    private static Pattern compileQuiet(String body) {
        try {
            return Pattern.compile(body);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String status(String raw) {
        if (raw == null || raw.isBlank()) return "PASSED";
        String value = raw.trim();
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) value = value.substring(dot + 1);
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String requireRun(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("execution run id is blank");
        return runId.trim();
    }

    private static final class Active {
        private final Path project;
        private final String runId;
        private final String scenarioName;
        private final String scenarioKey;
        private final String recordedScenario;
        private final String execId;
        private final Integer examplesRow;
        private final String started = Instant.now().toString();
        private final List<String> texts = new ArrayList<>();
        private int nextOrd;
        private int pluginOrd;
        private String current;
        private String currentText;

        private Active(Path project, String runId, String scenarioName, String scenarioKey, Integer examplesRow, String recordedScenario) {
            this.project = project;
            this.runId = runId;
            this.scenarioName = scenarioName;
            this.scenarioKey = scenarioKey;
            this.examplesRow = examplesRow;
            this.recordedScenario = recordedScenario == null ? "" : recordedScenario;
            this.execId = executionId(runId, scenarioKey, examplesRow);
        }

        private String scenarioHash() {
            return StoreHashes.sha256((scenarioKey + "\n" + String.join("\n", texts)).getBytes(StandardCharsets.UTF_8));
        }
    }
}
