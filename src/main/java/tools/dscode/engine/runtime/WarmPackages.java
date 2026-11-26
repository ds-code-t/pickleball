package tools.dscode.engine.runtime;

import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StorePaths;

import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Parent-last packages. {@code pkb_warm_packages} overrides the ingested glue packages.
 */
public final class WarmPackages {
    private WarmPackages() {
    }

    public static List<String> resolve(Path project) {
        String override = WarmFlags.setting("pkb_warm_packages", "PKB_WARM_PACKAGES");
        if (override != null && !override.isBlank()) return split(override);
        return ingested(project);
    }

    static List<String> split(String raw) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return List.copyOf(names);
    }

    private static List<String> ingested(Path project) {
        if (!StorePaths.ready(project)) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT source_path FROM step_definitions WHERE removed = 0 AND language = 'java'")) {
            while (rows.next()) {
                String pkg = packageOf(rows.getString(1));
                if (pkg != null) names.add(pkg);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(names);
    }

    static String packageOf(String sourcePath) {
        if (sourcePath == null) return null;
        String norm = sourcePath.replace('\\', '/');
        String marker = null;
        if (norm.contains("src/main/java/")) marker = "src/main/java/";
        else if (norm.contains("src/test/java/")) marker = "src/test/java/";
        if (marker == null) return null;
        String relative = norm.substring(norm.indexOf(marker) + marker.length());
        int slash = relative.lastIndexOf('/');
        if (slash <= 0 || !relative.endsWith(".java")) return null;
        return relative.substring(0, slash).replace('/', '.');
    }

}
