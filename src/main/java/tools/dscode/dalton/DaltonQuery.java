package tools.dscode.dalton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Read-only Dalton queries. Missing databases are empty and are not created. */
public final class DaltonQuery {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DaltonQuery() {
    }

    public record FileSummary(
            String path,
            String sourceType,
            boolean edited,
            int bytes,
            String contentHash,
            String lastSyncedHash
    ) {
    }

    public record FileDetail(FileSummary summary, String text, String base64) {
    }

    public record FeatureSummary(
            String path,
            String keyword,
            String name,
            String language,
            String tags,
            String description,
            int scenarios
    ) {
    }

    public record ScenarioRow(
            String path,
            int ord,
            String keyword,
            String name,
            int line,
            String tags,
            String description
    ) {
    }

    public record StepRow(
            String path,
            int scenarioOrd,
            String scenario,
            int ord,
            String keyword,
            String text,
            int line,
            String docstring,
            String docstringType,
            String tableJson
    ) {
    }

    public record Conflict(String path, String kind, String message) {
    }

    public static boolean databasePresent(Path project) {
        return Files.isRegularFile(DaltonPaths.database(project));
    }

    public static List<FileSummary> files(Path project) throws SQLException {
        if (!databasePresent(project)) return List.of();
        try (DaltonDatabase database = DaltonDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT path, source_type, db_edited, length(raw_content), content_hash, last_synced_hash FROM files ORDER BY path");
             ResultSet rows = statement.executeQuery()) {
            List<FileSummary> found = new ArrayList<>();
            while (rows.next()) {
                found.add(new FileSummary(
                        rows.getString(1),
                        rows.getString(2),
                        rows.getInt(3) != 0,
                        rows.getInt(4),
                        rows.getString(5),
                        rows.getString(6)
                ));
            }
            return found;
        }
    }

    public static FileDetail file(Path project, String relativePath) throws SQLException {
        if (!databasePresent(project)) return null;
        String path = DaltonPaths.normalizeRelative(relativePath);
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), path);
            if (row == null) return null;
            byte[] raw = row.raw() == null ? new byte[0] : row.raw();
            String text = utf8(raw);
            return new FileDetail(
                    new FileSummary(row.path(), row.sourceType(), row.edited(), raw.length, row.contentHash(), row.lastSyncedHash()),
                    text,
                    text == null ? Base64.getEncoder().encodeToString(raw) : null
            );
        }
    }

    public static List<FeatureSummary> features(Path project) throws SQLException {
        if (!databasePresent(project)) return List.of();
        try (DaltonDatabase database = DaltonDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT d.file_path, d.keyword, d.name, d.language, d.tags, d.description,
                            (SELECT COUNT(*) FROM scenarios s WHERE s.file_path = d.file_path)
                     FROM feature_docs d
                     ORDER BY d.file_path
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<FeatureSummary> found = new ArrayList<>();
            while (rows.next()) {
                found.add(new FeatureSummary(
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getString(6), rows.getInt(7)));
            }
            return found;
        }
    }

    public static FeatureSummary feature(Path project, String relativePath) throws SQLException {
        String path = DaltonPaths.normalizeRelative(relativePath);
        for (FeatureSummary feature : features(project)) {
            if (feature.path().equals(path)) return feature;
        }
        return null;
    }

    public static List<ScenarioRow> scenarios(Path project, String featurePath) throws SQLException {
        if (!databasePresent(project)) return List.of();
        String filter = featurePath == null || featurePath.isBlank() ? null : DaltonPaths.normalizeRelative(featurePath);
        try (DaltonDatabase database = DaltonDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT file_path, ord, keyword, name, line, tags, description
                     FROM scenarios
                     WHERE (? IS NULL OR file_path = ?)
                     ORDER BY file_path, ord
                     """)) {
            statement.setString(1, filter);
            statement.setString(2, filter);
            try (ResultSet rows = statement.executeQuery()) {
                List<ScenarioRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new ScenarioRow(
                            rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4),
                            rows.getInt(5), rows.getString(6), rows.getString(7)));
                }
                return found;
            }
        }
    }

    public static List<StepRow> steps(Path project, String featurePath, String scenario) throws SQLException {
        if (!databasePresent(project)) return List.of();
        String filter = featurePath == null || featurePath.isBlank() ? null : DaltonPaths.normalizeRelative(featurePath);
        Integer ord = scenarioOrd(scenario);
        String name = ord == null ? blankToNull(scenario) : null;
        try (DaltonDatabase database = DaltonDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT s.file_path, s.scenario_ord, sc.name, s.ord, s.keyword, s.text, s.line,
                            s.docstring, s.docstring_type, s.table_json
                     FROM steps s
                     LEFT JOIN scenarios sc ON sc.file_path = s.file_path AND sc.ord = s.scenario_ord
                     WHERE (? IS NULL OR s.file_path = ?)
                       AND (? IS NULL OR s.scenario_ord = ?)
                       AND (? IS NULL OR sc.name = ?)
                     ORDER BY s.file_path, s.scenario_ord, s.ord
                     """)) {
            statement.setString(1, filter);
            statement.setString(2, filter);
            if (ord == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, ord);
            if (ord == null) statement.setNull(4, java.sql.Types.INTEGER);
            else statement.setInt(4, ord);
            statement.setString(5, name);
            statement.setString(6, name);
            try (ResultSet rows = statement.executeQuery()) {
                List<StepRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new StepRow(
                            rows.getString(1), rows.getInt(2), rows.getString(3) == null ? "" : rows.getString(3),
                            rows.getInt(4), rows.getString(5), rows.getString(6), rows.getInt(7),
                            rows.getString(8), rows.getString(9), rows.getString(10)));
                }
                return found;
            }
        }
    }

    public static List<Conflict> conflicts(Path project) throws SQLException {
        if (!databasePresent(project)) return List.of();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            return conflicts(project, database.connection());
        }
    }

    public static List<Conflict> conflicts(Path project, Connection connection) throws SQLException {
        try {
            List<Conflict> found = new ArrayList<>();
            for (DaltonFiles.Row row : DaltonFiles.index(connection).values()) {
                if (DaltonContent.untracked(row)) {
                    found.add(new Conflict(row.path(), "untracked", DaltonContent.untrackedMessage(row.path())));
                    continue;
                }
                if (!row.edited()) continue;
                Path disk = project.resolve(row.path());
                if (!Files.isRegularFile(disk)) continue;
                String synced = row.lastSyncedHash() == null ? "" : row.lastSyncedHash();
                if (synced.isBlank()) continue;
                String diskHash = DaltonHashes.sha256(Files.readAllBytes(disk));
                if (!diskHash.equals(synced)) {
                    found.add(new Conflict(row.path(), "both", "conflict: " + row.path()));
                }
            }
            found.sort(java.util.Comparator.comparing(Conflict::path));
            return found;
        } catch (java.io.IOException failure) {
            throw new SQLException(failure.getMessage(), failure);
        }
    }

    public static String filesJson(List<FileSummary> files) {
        ArrayNode array = JSON.createArrayNode();
        for (FileSummary file : files) array.add(fileNode(file, null, null, false));
        ObjectNode root = JSON.createObjectNode();
        root.set("files", array);
        return write(root);
    }

    public static String fileJson(FileDetail detail) {
        return write(fileNode(detail.summary(), detail.text(), detail.base64(), true));
    }

    public static String featuresJson(List<FeatureSummary> features) {
        ArrayNode array = JSON.createArrayNode();
        for (FeatureSummary feature : features) array.add(featureNode(feature));
        ObjectNode root = JSON.createObjectNode();
        root.set("features", array);
        return write(root);
    }

    public static String featureJson(FeatureSummary feature, String text, List<ScenarioRow> scenarios) {
        ObjectNode node = featureNode(feature);
        node.put("text", text == null ? "" : text);
        node.put("scenarioCount", feature.scenarios());
        ArrayNode array = JSON.createArrayNode();
        for (ScenarioRow scenario : scenarios) array.add(scenarioNode(scenario));
        node.set("scenarios", array);
        return write(node);
    }

    public static String scenariosJson(List<ScenarioRow> scenarios) {
        ArrayNode array = JSON.createArrayNode();
        for (ScenarioRow scenario : scenarios) array.add(scenarioNode(scenario));
        ObjectNode root = JSON.createObjectNode();
        root.set("scenarios", array);
        return write(root);
    }

    public static String stepsJson(List<StepRow> steps) {
        ArrayNode array = JSON.createArrayNode();
        for (StepRow step : steps) array.add(stepNode(step));
        ObjectNode root = JSON.createObjectNode();
        root.set("steps", array);
        return write(root);
    }

    public static String conflictsJson(List<Conflict> conflicts) {
        ArrayNode array = JSON.createArrayNode();
        for (Conflict conflict : conflicts) {
            ObjectNode node = JSON.createObjectNode();
            node.put("path", conflict.path());
            node.put("kind", conflict.kind());
            node.put("message", conflict.message());
            array.add(node);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("conflicts", array);
        return write(root);
    }

    private static ObjectNode fileNode(FileSummary file, String text, String base64, boolean body) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", file.path());
        node.put("sourceType", file.sourceType());
        node.put("edited", file.edited());
        node.put("bytes", file.bytes());
        node.put("contentHash", file.contentHash());
        node.put("lastSyncedHash", file.lastSyncedHash() == null ? "" : file.lastSyncedHash());
        if (body) {
            if (text == null) node.putNull("text");
            else node.put("text", text);
            if (base64 == null) node.putNull("base64");
            else node.put("base64", base64);
        }
        return node;
    }

    private static ObjectNode featureNode(FeatureSummary feature) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", feature.path());
        node.put("keyword", feature.keyword());
        node.put("name", feature.name());
        node.put("language", feature.language());
        node.put("tags", feature.tags() == null ? "" : feature.tags());
        node.put("description", feature.description() == null ? "" : feature.description());
        node.put("scenarios", feature.scenarios());
        return node;
    }

    private static ObjectNode scenarioNode(ScenarioRow scenario) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", scenario.path());
        node.put("ord", scenario.ord());
        node.put("keyword", scenario.keyword());
        node.put("name", scenario.name());
        node.put("line", scenario.line());
        node.put("tags", scenario.tags() == null ? "" : scenario.tags());
        node.put("description", scenario.description() == null ? "" : scenario.description());
        return node;
    }

    private static ObjectNode stepNode(StepRow step) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", step.path());
        node.put("scenarioOrd", step.scenarioOrd());
        node.put("scenario", step.scenario());
        node.put("ord", step.ord());
        node.put("keyword", step.keyword());
        node.put("text", step.text() == null ? "" : step.text());
        node.put("line", step.line());
        if (step.docstring() == null) node.putNull("docstring");
        else node.put("docstring", step.docstring());
        if (step.docstringType() == null) node.putNull("docstringType");
        else node.put("docstringType", step.docstringType());
        if (step.tableJson() == null || step.tableJson().isBlank()) node.putNull("table");
        else {
            try {
                node.set("table", JSON.readTree(step.tableJson()));
            } catch (java.io.IOException failure) {
                node.put("table", step.tableJson());
            }
        }
        return node;
    }

    private static String write(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static String utf8(byte[] raw) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
    }

    private static Integer scenarioOrd(String scenario) {
        if (scenario == null || scenario.isBlank()) return null;
        try {
            return Integer.valueOf(scenario.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value;
    }
}
