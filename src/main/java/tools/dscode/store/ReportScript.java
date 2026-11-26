package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.store.dashboard.DashboardHtml;
import tools.dscode.store.dashboard.DashboardQuery;
import tools.dscode.store.dashboard.DashboardSnapshot;
import tools.dscode.store.dashboard.SqliteDashboardSource;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Report scripts are JSON files ending in {@code .pkb.json}. They name a head
 * and step references. They do not embed screenshot bytes or cleartext secrets.
 */
public final class ReportScript {
    public static final String FORMAT = "pickleball.report-script";
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportScript() {
    }

    public static int write(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String runId = option(tokens, "--run=");
        String destination = option(tokens, "--out=");
        if (runId == null || runId.isBlank() || destination == null || destination.isBlank()) {
            return fail(err, "error: store report write needs --run and --out",
                    "fix: store report write --run=<id> --out=<file.pkb.json>");
        }
        if (!destination.endsWith(".pkb.json")) {
            return fail(err, "error: report script must end with .pkb.json",
                    "fix: store report write --out=<file.pkb.json>");
        }
        ObjectNode script = scriptFor(project, runId);
        if (script == null) {
            return fail(err, "error: run not found: " + runId, "fix: store history list");
        }
        Path file = Path.of(destination);
        if (!file.isAbsolute()) file = project.resolve(destination);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(script), StandardCharsets.UTF_8);
        out.println("script " + file.toAbsolutePath().normalize());
        return 0;
    }

    public static int export(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String runId = option(tokens, "--run=");
        String html = option(tokens, "--html=");
        if (runId == null || runId.isBlank() || html == null || html.isBlank()) {
            return fail(err, "error: store report export needs --run and --html",
                    "fix: store report export --run=<id> --html=<file>");
        }
        if (!runExists(project, runId)) {
            return fail(err, "error: run not found: " + runId, "fix: store history list");
        }
        DashboardSnapshot snapshot = new SqliteDashboardSource(project).read(DashboardQuery.all());
        Path file = Path.of(html);
        if (!file.isAbsolute()) file = project.resolve(html);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, DashboardHtml.render(snapshot), StandardCharsets.UTF_8);
        out.println("html " + file.toAbsolutePath().normalize());
        return 0;
    }

    public static int runScript(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String scriptPath = option(tokens, "--script=");
        if (scriptPath == null || scriptPath.isBlank()) {
            return fail(err, "error: store run --script needs a file",
                    "fix: store run --script=<file.pkb.json>");
        }
        if (!scriptPath.endsWith(".pkb.json")) {
            return fail(err, "error: report script must end with .pkb.json",
                    "fix: store report write --out=<file.pkb.json>");
        }
        Path file = Path.of(scriptPath);
        if (!file.isAbsolute()) file = project.resolve(scriptPath);
        if (!Files.isRegularFile(file)) {
            return fail(err, "error: report script is missing",
                    "fix: store report write --run=<id> --out=<file.pkb.json>");
        }
        JsonNode script = JSON.readTree(Files.readString(file));
        if (!FORMAT.equals(script.path("format").asText()) || script.path("formatVersion").asInt(-1) != FORMAT_VERSION) {
            return fail(err, "error: report script format must be pickleball.report-script version 1",
                    "fix: store report write --run=<id> --out=<file.pkb.json>");
        }
        String requested = option(tokens, "--head=");
        String headId = requested == null || requested.isBlank() ? script.path("headId").asText("") : requested;
        if (headId.isBlank()) headId = UniversalEntity.HEAD_ID;
        String loaded = requireHead(project, headId);
        if (loaded == null) {
            return fail(err, "error: unknown head " + headId, "fix: store head show");
        }
        String runId = StoreExecution.newRunId();
        String now = Instant.now().toString();
        String commit = mask(project, script.path("commit").asText(""));
        String sandbox = mask(project, script.path("sandbox").asText("main"));
        if (sandbox.isBlank()) sandbox = "main";
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runs(
                        run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox,
                        git_commit, feature_path, commit_sha, head_id, parent_commit, parent_head_id)
                    VALUES (?, ?, ?, 'PASSED', 'script', 0, 0, '', ?, '', '', ?, ?, '', '')
                    """)) {
                statement.setString(1, runId);
                statement.setString(2, now);
                statement.setString(3, now);
                statement.setString(4, sandbox);
                statement.setString(5, commit);
                statement.setString(6, loaded);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO run_snapshots(
                        run_id, commit_sha, head_id, sandbox, parent_commit, parent_head_id, enabled_hash, started_at)
                    VALUES (?, ?, ?, ?, '', '', ?, ?)
                    """)) {
                statement.setString(1, runId);
                statement.setString(2, commit);
                statement.setString(3, loaded);
                statement.setString(4, sandbox);
                statement.setString(5, StoreHashes.sha256(new byte[0]));
                statement.setString(6, now);
                statement.executeUpdate();
            }
        }
        StoreLog.log(project, "pin", runId, "snapshot head=" + loaded);
        out.println("run " + runId + " head " + loaded);
        return 0;
    }

    public static int diff(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        List<String> runs = options(tokens, "--run=");
        if (runs.size() < 2 || runs.get(0).isBlank() || runs.get(1).isBlank()) {
            return fail(err, "error: store diff needs two --run values",
                    "fix: store diff --run=<a> --run=<b> --json");
        }
        String left = runs.get(0);
        String right = runs.get(1);
        if (!runExists(project, left) || !runExists(project, right)) {
            return fail(err, "error: run not found", "fix: store history list");
        }
        List<Map<String, Object>> lines = compare(project, left, right);
        if (!json(tokens)) {
            if (lines.isEmpty()) out.println("diff 0");
            for (Map<String, Object> line : lines) out.println(String.valueOf(line.get("kind")) + " " + line);
            return 0;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", lines);
        out.println(JSON.writeValueAsString(AgentEnvelope.apply(body, 0, "diff", project, left)));
        return 0;
    }

    private static List<Map<String, Object>> compare(Path project, String left, String right) throws SQLException {
        Map<String, String> leftHashes = definitionHashes(project, left);
        Map<String, String> rightHashes = definitionHashes(project, right);
        Map<String, String> catalog = catalogHashes(project);
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(leftHashes.keySet());
        ids.addAll(rightHashes.keySet());
        for (String id : ids) {
            boolean inLeft = leftHashes.containsKey(id);
            boolean inRight = rightHashes.containsKey(id);
            if (inLeft != inRight) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", "break");
                row.put("definitionId", mask(project, id));
                row.put("run", inLeft ? left : right);
                lines.add(row);
                continue;
            }
            String hashLeft = firstHash(leftHashes.get(id), catalog.get(id));
            String hashRight = firstHash(rightHashes.get(id), catalog.get(id));
            if (!hashLeft.equals(hashRight)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", "changed");
                row.put("definitionId", mask(project, id));
                row.put("hashA", mask(project, hashLeft));
                row.put("hashB", mask(project, hashRight));
                lines.add(row);
            }
        }
        Map<String, String> leftAttributes = attributes(project, left);
        Map<String, String> rightAttributes = attributes(project, right);
        Set<String> names = new LinkedHashSet<>();
        names.addAll(leftAttributes.keySet());
        names.addAll(rightAttributes.keySet());
        for (String name : names) {
            String a = leftAttributes.getOrDefault(name, "");
            String b = rightAttributes.getOrDefault(name, "");
            if (a.equals(b)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "config");
            row.put("name", mask(project, name));
            row.put("valueA", mask(project, a));
            row.put("valueB", mask(project, b));
            lines.add(row);
        }
        return lines;
    }

    /** Step text hash when the runs disagree, otherwise the catalog content hash. */
    private static String firstHash(String stepTextHash, String contentHash) {
        if (stepTextHash != null && !stepTextHash.isBlank()) return stepTextHash;
        return contentHash == null ? "" : contentHash;
    }

    private static Map<String, String> definitionHashes(Path project, String runId) throws SQLException {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT s.definition_id, s.step_text_hash
                     FROM step_executions s
                     JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                     WHERE e.run_id = ? AND s.definition_id IS NOT NULL AND s.definition_id <> ''
                     ORDER BY s.step_ord, s.step_exec_id
                     """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    if (!hashes.containsKey(id)) hashes.put(id, text(rows.getString(2)));
                }
            }
        }
        return hashes;
    }

    private static Map<String, String> catalogHashes(Path project) throws SQLException {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT definition_id, content_hash, source_path FROM step_definitions");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String source = rows.getString(3);
                if (secretFile(project, source)) continue;
                hashes.put(rows.getString(1), text(rows.getString(2)));
            }
        }
        return hashes;
    }

    private static boolean secretFile(Path project, String source) {
        if (source == null || source.isBlank() || project == null) return false;
        Path file = Path.of(source);
        if (!file.isAbsolute()) file = project.resolve(source);
        if (!Files.isRegularFile(file)) return false;
        try {
            StoreSecrets.rejectAssignment(project, Files.readString(file));
            return false;
        } catch (IllegalArgumentException secret) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, String> attributes(Path project, String runId) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT name, value FROM run_attributes WHERE run_id = ? ORDER BY name")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.put(text(rows.getString(1)), text(rows.getString(2)));
            }
        }
        return values;
    }

    private static ObjectNode scriptFor(Path project, String runId) throws Exception {
        String commit = "";
        String headId = "";
        String sandbox = "main";
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT commit_sha, head_id, sandbox FROM run_snapshots WHERE run_id = ?
                     """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    if (!runExists(project, runId)) return null;
                } else {
                    commit = text(rows.getString(1));
                    headId = text(rows.getString(2));
                    sandbox = text(rows.getString(3));
                }
            }
        }
        if (headId.isBlank()) headId = runColumn(project, runId, "head_id");
        if (commit.isBlank()) commit = runColumn(project, runId, "commit_sha");
        if (sandbox.isBlank()) sandbox = runColumn(project, runId, "sandbox");
        ObjectNode script = JSON.createObjectNode();
        script.put("format", FORMAT);
        script.put("formatVersion", FORMAT_VERSION);
        script.put("commit", mask(project, commit));
        script.put("headId", mask(project, headId.isBlank() ? UniversalEntity.HEAD_ID : headId));
        script.put("sandbox", mask(project, sandbox.isBlank() ? "main" : sandbox));
        ArrayNode steps = script.putArray("steps");
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT s.definition_id, s.step_text_hash, s.step_ord
                     FROM step_executions s
                     JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                     WHERE e.run_id = ?
                     ORDER BY s.step_ord, s.step_exec_id
                     """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ObjectNode step = steps.addObject();
                    step.put("definitionId", mask(project, text(rows.getString(1))));
                    step.put("textHash", mask(project, text(rows.getString(2))));
                    step.put("ord", rows.getInt(3));
                }
            }
        }
        ArrayNode artifacts = script.putArray("artifacts");
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT path FROM artifacts WHERE run_id = ? ORDER BY artifact_id")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String path = text(rows.getString(1));
                    ObjectNode artifact = artifacts.addObject();
                    artifact.put("path", mask(project, path));
                    artifact.put("sha256", sha256(project, path));
                }
            }
        }
        return script;
    }

    private static String sha256(Path project, String relative) {
        if (relative == null || relative.isBlank()) return StoreHashes.sha256(new byte[0]);
        Path file = Path.of(relative);
        if (!file.isAbsolute()) file = project.resolve(relative);
        try {
            if (!Files.isRegularFile(file)) return StoreHashes.sha256(new byte[0]);
            return StoreHashes.sha256(Files.readAllBytes(file));
        } catch (Exception ignored) {
            return StoreHashes.sha256(new byte[0]);
        }
    }

    private static String requireHead(Path project, String headId) throws SQLException {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            String found = UniversalEntity.loadHead(connection, headId);
            if (found != null) return found;
            if (UniversalEntity.HEAD_ID.equals(headId)) {
                HeadMaterializer.rebuild(connection, project);
                found = UniversalEntity.loadHead(connection, headId);
            }
            return found;
        }
    }

    private static boolean runExists(Path project, String runId) throws SQLException {
        return !runColumn(project, runId, "run_id").isBlank();
    }

    private static String runColumn(Path project, String runId, String column) throws SQLException {
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT " + column + " FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? text(rows.getString(1)) : "";
            }
        }
    }

    static String mask(Path project, String value) {
        if (value == null || value.isBlank()) return value == null ? "" : value;
        try {
            StoreSecrets.rejectAssignment(project, value);
            return value;
        } catch (IllegalArgumentException secret) {
            return "~MASK;";
        }
    }

    private static String option(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private static List<String> options(List<String> tokens, String prefix) {
        List<String> found = new ArrayList<>();
        for (String token : tokens) {
            if (token.startsWith(prefix)) found.add(token.substring(prefix.length()));
        }
        return found;
    }

    private static boolean json(List<String> tokens) {
        return tokens.contains("--json");
    }

    private static int fail(PrintStream err, String error, String fix) {
        err.println(error);
        err.println(fix);
        return 1;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
