package tools.dscode.engine.adapter.cucumber;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.match.PatternMatcher;
import tools.dscode.store.StoreLog;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreRunVariables;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates hydrated feature and glue rows into the native catalog.
 * Feature membership and {@code @pkb-ignore} stay on the columns R1 already writes.
 * This class is the only writer of {@code scenario_tags}.
 */
public final class CucumberIngest {
    private CucumberIngest() {
    }

    public record Prior(
            String id,
            String language,
            String pattern,
            String parameterTypes,
            String methodName,
            String sourcePath,
            int sourceLine,
            String contentHash,
            int enabled,
            int removed
    ) {
    }

    public record Counts(int translated, int failed, List<String> notices) {
        public Counts {
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    public static Map<String, Prior> snapshot(Connection connection) throws SQLException {
        Map<String, Prior> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_id, language, pattern, parameter_types, method_name, source_path,
                       source_line, content_hash, enabled, removed
                FROM step_definitions
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Prior prior = new Prior(
                        result.getString(1),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4) == null ? "[]" : result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getInt(7),
                        result.getString(8) == null ? "" : result.getString(8),
                        result.getInt(9),
                        result.getInt(10));
                rows.put(prior.id(), prior);
            }
        }
        return rows;
    }

    public static Counts apply(Connection connection, java.nio.file.Path project, Map<String, Prior> prior)
            throws SQLException, IOException {
        Map<String, Prior> snapshot = prior == null ? Map.of() : prior;
        List<String> notices = new ArrayList<>();
        int translated = translateDefinitions(connection, project, snapshot, notices);
        int failed = notices.size();
        applyGluePackages(connection);
        reaffirmFiles(connection);
        syncAllTags(connection);
        StoreRunVariables.copyNamed(connection, project, "pkb_callpath", "pkb_componentpath");
        StoreLog.log(project, "hydrate", "ingest", "translated=" + translated + " failed=" + failed);
        return new Counts(translated, failed, notices);
    }

    /** Rewrites {@code scenario_tags} for one feature from the columns already stored. */
    public static void syncTags(Connection connection, String path) throws SQLException {
        if (path == null || path.isBlank()) return;
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM scenario_tags WHERE file_path = ?")) {
            delete.setString(1, path);
            delete.executeUpdate();
        }
        try (PreparedStatement scenarios = connection.prepareStatement(
                "SELECT ord, tags FROM scenarios WHERE file_path = ?");
             PreparedStatement examples = connection.prepareStatement(
                     "SELECT scenario_ord, tags FROM example_blocks WHERE file_path = ?");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO scenario_tags(file_path, scenario_ord, tag) VALUES (?, ?, ?)
                     ON CONFLICT(file_path, scenario_ord, tag) DO NOTHING
                     """)) {
            scenarios.setString(1, path);
            try (ResultSet rows = scenarios.executeQuery()) {
                while (rows.next()) insertTags(insert, path, rows.getInt(1), rows.getString(2));
            }
            examples.setString(1, path);
            try (ResultSet rows = examples.executeQuery()) {
                while (rows.next()) insertTags(insert, path, rows.getInt(1), rows.getString(2));
            }
        }
    }

    private static int translateDefinitions(
            Connection connection, java.nio.file.Path project, Map<String, Prior> snapshot, List<String> notices)
            throws SQLException {
        List<Prior> current = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_id, language, pattern, parameter_types, method_name, source_path,
                       source_line, content_hash, enabled, removed
                FROM step_definitions WHERE removed = 0
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                current.add(new Prior(
                        result.getString(1),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4) == null ? "[]" : result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getInt(7),
                        result.getString(8) == null ? "" : result.getString(8),
                        result.getInt(9),
                        result.getInt(10)));
            }
        }
        int translated = 0;
        String now = Instant.now().toString();
        for (Prior row : current) {
            Prior same = snapshot.get(row.id());
            if (same != null && row.pattern().equals(same.pattern()) && typed(same.parameterTypes())) {
                translated++;
                EngineLog.log(project, "ingest", row.sourcePath(), "translated " + row.pattern());
                continue;
            }
            PatternMatcher.Translation translation = PatternMatcher.translation(row.pattern());
            if (translation.rejected()) {
                String message = translation.message() == null || translation.message().isBlank()
                        ? "pattern was not translated"
                        : translation.message();
                notices.add("error: " + message + " in " + row.sourcePath()
                        + "\nfix: store glue show " + row.sourcePath());
                EngineLog.log(project, "ingest", row.sourcePath(), "failed " + message);
                Prior previous = previousDifferent(snapshot, row);
                if (previous != null) {
                    restore(connection, previous, now);
                    if (!previous.id().equals(row.id())) deleteDefinition(connection, row.id());
                } else {
                    disable(connection, row.id());
                }
                continue;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE step_definitions SET pattern = ?, parameter_types = ? WHERE definition_id = ?")) {
                update.setString(1, translation.regex());
                update.setString(2, translation.parameterTypesJson());
                update.setString(3, row.id());
                update.executeUpdate();
            }
            translated++;
            EngineLog.log(project, "ingest", row.sourcePath(),
                    "translated " + row.pattern() + " types=" + translation.parameterTypesJson());
        }
        return translated;
    }

    private static Prior previousDifferent(Map<String, Prior> snapshot, Prior row) {
        Prior same = snapshot.get(row.id());
        if (same != null && same.pattern() != null && !same.pattern().equals(row.pattern())) return same;
        for (Prior prior : snapshot.values()) {
            if (prior.removed() != 0) continue;
            if (prior.id().equals(row.id())) continue;
            if (!location(prior).equals(location(row))) continue;
            if (prior.pattern() != null && prior.pattern().equals(row.pattern())) continue;
            return prior;
        }
        return null;
    }

    private static String location(Prior row) {
        return (row.language() == null ? "" : row.language()) + "\n"
                + (row.sourcePath() == null ? "" : row.sourcePath()) + "\n" + row.sourceLine();
    }

    private static boolean typed(String parameterTypes) {
        if (parameterTypes == null) return false;
        String trimmed = parameterTypes.trim();
        return !trimmed.isEmpty() && !"[]".equals(trimmed);
    }

    private static void restore(Connection connection, Prior prior, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO step_definitions(
                    definition_id, language, pattern, method_name, source_path, source_line,
                    content_hash, hydrated_at, removed, enabled, parameter_types)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                ON CONFLICT(definition_id) DO UPDATE SET
                    pattern = excluded.pattern,
                    parameter_types = excluded.parameter_types,
                    removed = 0,
                    enabled = excluded.enabled
                """)) {
            statement.setString(1, prior.id());
            statement.setString(2, prior.language());
            statement.setString(3, prior.pattern());
            statement.setString(4, prior.methodName());
            statement.setString(5, prior.sourcePath());
            statement.setInt(6, prior.sourceLine());
            statement.setString(7, prior.contentHash());
            statement.setString(8, now);
            statement.setInt(9, prior.enabled());
            statement.setString(10, prior.parameterTypes() == null ? "[]" : prior.parameterTypes());
            statement.executeUpdate();
        }
    }

    private static void deleteDefinition(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM step_definitions WHERE definition_id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static void disable(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE step_definitions SET enabled = 0 WHERE definition_id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static void applyGluePackages(Connection connection) throws SQLException {
        String glue = variable(connection, "pkb_glue");
        if (glue.isBlank()) return;
        java.util.Set<String> packages = new java.util.LinkedHashSet<>();
        for (String token : glue.split("[,;\\s]+")) {
            if (!token.isBlank()) packages.add(token.trim());
        }
        if (packages.isEmpty()) return;
        try (PreparedStatement read = connection.prepareStatement("""
                SELECT definition_id, language, source_path FROM step_definitions WHERE removed = 0 AND enabled = 1
                """);
             ResultSet rows = read.executeQuery();
             PreparedStatement off = connection.prepareStatement(
                     "UPDATE step_definitions SET enabled = 0 WHERE definition_id = ?")) {
            while (rows.next()) {
                if (!"java".equals(rows.getString(2))) continue;
                String packageName = packageName(rows.getString(3));
                if (packageName == null || packages.contains(packageName)) continue;
                off.setString(1, rows.getString(1));
                off.executeUpdate();
            }
        }
    }

    private static void reaffirmFiles(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("SELECT path FROM files");
             ResultSet rows = read.executeQuery();
             PreparedStatement off = connection.prepareStatement("UPDATE files SET enabled = 0 WHERE path = ?")) {
            while (rows.next()) {
                String path = rows.getString(1);
                if (StorePaths.inFeatureOrGlueRoot(path)) continue;
                off.setString(1, path);
                off.executeUpdate();
            }
        }
    }

    private static void syncAllTags(Connection connection) throws SQLException {
        List<String> paths = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT file_path FROM feature_docs");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) paths.add(rows.getString(1));
        }
        for (String path : paths) syncTags(connection, path);
    }

    private static String variable(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM run_variables WHERE name = ? AND enabled = 1")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) return "";
                return rows.getString(1).trim();
            }
        }
    }

    private static String packageName(String sourcePath) {
        if (sourcePath == null) return null;
        String norm = sourcePath.replace('\\', '/');
        String rel = null;
        int main = norm.indexOf("src/main/java/");
        int test = norm.indexOf("src/test/java/");
        if (main >= 0) rel = norm.substring(main + "src/main/java/".length());
        else if (test >= 0) rel = norm.substring(test + "src/test/java/".length());
        if (rel == null || !rel.endsWith(".java")) return null;
        String className = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static void insertTags(PreparedStatement insert, String path, int ord, String text) throws SQLException {
        if (text == null || text.isBlank()) return;
        for (String part : text.split("\\s+")) {
            if (!part.startsWith("@") || part.length() < 2) continue;
            insert.setString(1, path);
            insert.setInt(2, ord);
            insert.setString(3, part);
            insert.executeUpdate();
        }
    }
}
