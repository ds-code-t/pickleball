package tools.dscode.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run variables copied from the property and profile files the project already
 * loads. Secret names stay references. Values are not written to the store log.
 */
public final class StoreRunVariables {
    private static final List<String> PROPERTY_FILES = List.of(
            "pickleball.properties",
            "src/test/resources/pickleball.properties",
            "pickleball_local.properties",
            "src/test/resources/pickleball_local.properties",
            "pickleball_local2.properties",
            "src/test/resources/pickleball_local2.properties");
    private static final List<String> YAML_FILES = List.of(
            "profiles.yaml",
            "src/test/resources/profiles.yaml",
            "profiles_local.yaml",
            "src/test/resources/profiles_local.yaml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private StoreRunVariables() {
    }

    /**
     * Copies named {@code pkb_*} keys that are already in the property or profile
     * files. A missing key is left unset. Lookup rules are not applied.
     */
    public static void copyNamed(Connection connection, Path project, String... names) throws IOException, SQLException {
        if (names == null || names.length == 0) return;
        Map<String, Assigned> found = new LinkedHashMap<>();
        for (String relative : PROPERTY_FILES) readProperties(project, relative, found);
        for (String relative : YAML_FILES) readYaml(project, relative, found);
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Assigned assigned = found.get(name);
            if (assigned == null) continue;
            if (StoreSecrets.secretName(project, name) && !StoreSecrets.reference(assigned.value())) continue;
            upsert(connection, name, assigned.value(), assigned.source(), assigned.hash());
        }
    }

    static int sync(Connection connection, Path project) throws IOException, SQLException {
        Map<String, Assigned> found = new LinkedHashMap<>();
        for (String relative : PROPERTY_FILES) readProperties(project, relative, found);
        for (String relative : YAML_FILES) readYaml(project, relative, found);
        int count = 0;
        for (Map.Entry<String, Assigned> entry : found.entrySet()) {
            Assigned assigned = entry.getValue();
            if (StoreSecrets.secretName(project, entry.getKey()) && !StoreSecrets.reference(assigned.value())) {
                continue;
            }
            upsert(connection, entry.getKey(), assigned.value(), assigned.source(), assigned.hash());
            count++;
            if (StoreSecrets.highEntropy(assigned.value())) {
                StoreLog.log(project, "enable", entry.getKey(), StoreSecrets.warning(entry.getKey()));
            }
        }
        if (count > 0) StoreLog.log(project, "enable", "run_variables", "sync count=" + count);
        return count;
    }

    static void set(Connection connection, Path project, String name, String value, boolean writeFile)
            throws IOException, SQLException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("var set needs a name");
        if (value == null) throw new IllegalArgumentException("var set needs a value");
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("var value must be one line");
        }
        StoreSecrets.rejectAssignment(project, name + "=" + value);
        if (StoreSecrets.secretName(project, name) && !StoreSecrets.reference(value)) {
            throw new IllegalArgumentException("history holds secret references only, not cleartext");
        }
        String source = sourceFile(connection, name);
        String hash = "";
        if (writeFile) {
            if (source.isBlank() || !source.endsWith(".properties")) {
                source = "src/test/resources/pickleball.properties";
            }
            Path file = project.resolve(source);
            writeProperty(file, name, value);
            hash = StoreHashes.sha256(Files.readAllBytes(file));
        }
        upsert(connection, name, value, source, hash);
        if (StoreSecrets.highEntropy(value)) {
            StoreLog.log(project, "enable", name, StoreSecrets.warning(name));
        }
        StoreLog.log(project, "enable", name, "var-set");
    }

    static void enable(Connection connection, Path project, String name, boolean enabled) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE run_variables SET enabled = ? WHERE name = ?")) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setString(2, name);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("run variable not found: " + name);
            }
        }
        StoreLog.log(project, "enable", name, enabled ? "var-on" : "var-off");
    }

    static List<Row> list(Connection connection) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, value, enabled, source_file FROM run_variables ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new Row(result.getString(1), result.getString(2), result.getInt(3) != 0, result.getString(4)));
            }
        }
        return rows;
    }

    private static void upsert(Connection connection, String name, String value, String source, String hash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_variables(name, value, enabled, source_file, content_hash) VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    value = excluded.value,
                    source_file = excluded.source_file,
                    content_hash = excluded.content_hash
                """)) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.setString(3, source == null ? "" : source);
            statement.setString(4, hash == null ? "" : hash);
            statement.executeUpdate();
        }
    }

    private static String sourceFile(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_file FROM run_variables WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null) return "";
                return result.getString(1);
            }
        }
    }

    private static void readProperties(Path project, String relative, Map<String, Assigned> found) throws IOException {
        Path file = project.resolve(relative);
        if (!Files.isRegularFile(file)) return;
        byte[] raw = Files.readAllBytes(file);
        String hash = StoreHashes.sha256(raw);
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int split = separator(trimmed);
            if (split <= 0) continue;
            String key = trimmed.substring(0, split).trim();
            if (!PKB_props.isRunVariableKey(key)) continue;
            String value = trimmed.substring(split + 1).trim();
            found.put(key, new Assigned(value, relative, hash));
        }
    }

    private static int separator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) return colon;
        if (colon < 0) return equals;
        return Math.min(equals, colon);
    }

    private static void readYaml(Path project, String relative, Map<String, Assigned> found) throws IOException {
        Path file = project.resolve(relative);
        if (!Files.isRegularFile(file)) return;
        byte[] raw = Files.readAllBytes(file);
        String hash = StoreHashes.sha256(raw);
        Object tree;
        try {
            tree = YAML.readValue(raw, Object.class);
        } catch (IOException failure) {
            return;
        }
        collect(tree, relative, hash, found);
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, String source, String hash, Map<String, Assigned> found) {
        if (!(node instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                collect(value, source, hash, found);
                continue;
            }
            if (value == null || value instanceof List<?>) continue;
            if (!PKB_props.isRunVariableKey(key)) continue;
            found.put(key, new Assigned(String.valueOf(value), source, hash));
        }
    }

    private static void writeProperty(Path file, String name, String value) throws IOException {
        List<String> lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
        String assignment = name + "=" + value;
        boolean replaced = false;
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int split = separator(trimmed);
            if (split > 0 && trimmed.substring(0, split).trim().equals(name)) {
                lines.set(index, assignment);
                replaced = true;
                break;
            }
        }
        if (!replaced) lines.add(assignment);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    record Row(String name, String value, boolean enabled, String sourceFile) {
    }

    private record Assigned(String value, String source, String hash) {
    }
}
